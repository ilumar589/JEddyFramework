package dev.jeddy.render.gpu;

import static dev.jeddy.gl.ffi.GL46.*;

import dev.jeddy.asset.format.Ktx2;
import dev.jeddy.core.handle.HandlePool;
import dev.jeddy.core.handle.Handles;
import dev.jeddy.gl.GLDebug;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;

/**
 * Textures behind generational handles (TASKS M5-03): immutable storage ({@code glTextureStorage*})
 * for 2D, 2D arrays, cube maps and 3D textures, uploads of BCn blocks or pixels per level, and
 * creation from KTX2 (DESIGN §9.2).
 *
 * <p>Block-compressed 2D arrays keep a CPU mirror of their <em>tail</em>: the mip levels smaller
 * than a 4x4 block in either direction (2x2, 1x1). Intel's Windows driver rejects both the GPU copy
 * and the readback of those levels, so {@link #copyLayers} (which grows the material arrays)
 * re-uploads them from the mirror instead (D101). The mirror is a few bytes per layer; only a thin
 * texture (2xN) makes it as large as the texture itself.
 *
 * <p>Thread ownership: the GL context thread (main thread).
 */
public final class GpuTextures {
    /** Kind: 2D. */
    public static final int KIND_2D = 0;

    /** Kind: 2D array. */
    public static final int KIND_2D_ARRAY = 1;

    /** Kind: cube map. */
    public static final int KIND_CUBE = 2;

    /** Kind: cube map array. */
    public static final int KIND_CUBE_ARRAY = 3;

    /** Kind: 3D (volume; the layer count is the depth). */
    public static final int KIND_3D = 4;

    private final GpuDevice device;
    private final HandlePool handles = new HandlePool(64, Handles.MAX_INDEX + 1);
    private int[] names = new int[64];
    private int[] formats = new int[64];
    private int[] kinds = new int[64];
    private int[] widths = new int[64];
    private int[] heights = new int[64];
    private int[] layers = new int[64];
    private int[] levels = new int[64];
    // The tail mirrors (class comment): level-major, one run of `layers × levelBytes` per tail
    // level, in a confined arena per texture; null for textures without a tail.
    private MemorySegment[] tails = new MemorySegment[64];
    private Arena[] tailArenas = new Arena[64];
    private int[] tailFirst = new int[64]; // the first tail level; the level count when none
    private boolean[] tailStale = new boolean[64]; // an upload from a PBO bypassed the mirror

    /** Every address, so a copy out of raw client memory needs no segment per upload. */
    private static final MemorySegment ALL = MemorySegment.NULL.reinterpret(Long.MAX_VALUE);

    GpuTextures(GpuDevice device) {
        this.device = device;
    }

    /**
     * Creates texture storage.
     *
     * @param kind {@link #KIND_2D}, ...
     * @param internalFormat a GL sized or compressed internal format ({@link GpuFormats})
     * @param layerCount array layers (1 for non-arrays), or the depth of a 3D texture
     * @param levelCount mip levels
     */
    public int create(
            int kind,
            int internalFormat,
            int width,
            int height,
            int layerCount,
            int levelCount,
            String label) {
        String ext = GpuFormats.requiredExtension(internalFormat);
        if (ext != null && !device.caps().has(ext)) {
            throw new UnsupportedOperationException(label + " needs " + ext);
        }
        int target =
                switch (kind) {
                    case KIND_2D -> GL_TEXTURE_2D;
                    case KIND_2D_ARRAY -> GL_TEXTURE_2D_ARRAY;
                    case KIND_CUBE -> GL_TEXTURE_CUBE_MAP;
                    case KIND_CUBE_ARRAY -> GL_TEXTURE_CUBE_MAP_ARRAY;
                    case KIND_3D -> GL_TEXTURE_3D;
                    default -> throw new IllegalArgumentException("texture kind " + kind);
                };
        MemorySegment id = device.scratch();
        glCreateTextures(target, 1, id);
        int name = id.get(ValueLayout.JAVA_INT, 0);
        switch (kind) {
            case KIND_2D, KIND_CUBE ->
                    glTextureStorage2D(name, levelCount, internalFormat, width, height);
            case KIND_2D_ARRAY, KIND_3D ->
                    glTextureStorage3D(name, levelCount, internalFormat, width, height, layerCount);
            default ->
                    glTextureStorage3D(
                            name, levelCount, internalFormat, width, height, layerCount * 6);
        }
        glTextureParameteri(name, GL_TEXTURE_MAX_LEVEL, levelCount - 1);
        if (label != null) {
            GLDebug.label(GL_TEXTURE, name, label);
        }
        int h = handles.allocate();
        int i = Handles.index(h);
        ensure(i);
        names[i] = name;
        formats[i] = internalFormat;
        kinds[i] = kind;
        widths[i] = width;
        heights[i] = height;
        layers[i] = layerCount;
        levels[i] = levelCount;
        tailFirst[i] = levelCount;
        tailStale[i] = false;
        if (kind == KIND_2D_ARRAY && GpuFormats.isCompressed(internalFormat)) {
            for (int l = 0; l < levelCount; l++) {
                if ((width >> l) < 4 || (height >> l) < 4) {
                    tailFirst[i] = l;
                    break;
                }
            }
            if (tailFirst[i] < levelCount) {
                long bytes = tailOffset(i, levelCount, 0); // one past the last tail level
                tailArenas[i] = Arena.ofConfined();
                tails[i] = tailArenas[i].allocate(bytes, 16);
            }
        }
        return h;
    }

    /** Bytes of one layer of one level of a compressed texture: whole 4x4 blocks. */
    private static int levelBytes(int format, int w, int h) {
        return ((w + 3) >> 2) * ((h + 3) >> 2) * GpuFormats.blockBytes(format);
    }

    /** Offset of {@code layer} of tail level {@code level} (which may be one past the last). */
    private long tailOffset(int i, int level, int layer) {
        int fmt = formats[i];
        long off = 0;
        for (int l = tailFirst[i]; l < level; l++) {
            off +=
                    (long) layers[i]
                            * levelBytes(
                                    fmt, Math.max(1, widths[i] >> l), Math.max(1, heights[i] >> l));
        }
        if (level < levels[i]) {
            off +=
                    (long) layer
                            * levelBytes(
                                    fmt,
                                    Math.max(1, widths[i] >> level),
                                    Math.max(1, heights[i] >> level));
        }
        return off;
    }

    /** Copies {@code count} layers of a tail level from client memory into the mirror. */
    private void mirror(int i, int level, int layer, int count, long address, int bytes) {
        int expected =
                count
                        * levelBytes(
                                formats[i],
                                Math.max(1, widths[i] >> level),
                                Math.max(1, heights[i] >> level));
        if (bytes != expected) {
            throw new IllegalArgumentException(
                    bytes + " bytes for level " + level + ", expected " + expected);
        }
        MemorySegment.copy(ALL, address, tails[i], tailOffset(i, level, layer), bytes);
    }

    /** Creates a 2D texture. */
    public int create2D(int internalFormat, int width, int height, int levelCount, String label) {
        return create(KIND_2D, internalFormat, width, height, 1, levelCount, label);
    }

    /**
     * Uploads one mip level (all layers and faces, KTX2 order: layer-major, then face) from native
     * memory at {@code address}. Allocates nothing.
     *
     * @param bytes the level's size in bytes (compressed formats need it)
     */
    public void uploadLevel(int handle, int level, long address, int bytes) {
        int i = check(handle);
        if (tails[i] != null && level >= tailFirst[i]) {
            mirror(i, level, 0, layers[i], address, bytes);
        }
        level(i, level, address, bytes);
    }

    /**
     * {@link #uploadLevel} from {@code offset} into the pixel-unpack buffer {@code buffer} (the
     * upload ring). The tail of a compressed array cannot be mirrored from a buffer, so {@link
     * #copyLayers} reads it back for such a texture from then on.
     */
    void uploadLevelFromBuffer(int handle, int level, int buffer, long offset, int bytes) {
        int i = check(handle);
        if (level >= tailFirst[i]) {
            tailStale[i] = true;
        }
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, buffer);
        try {
            level(i, level, offset, bytes);
        } finally {
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
        }
    }

    private void level(int i, int level, long address, int bytes) {
        int w = Math.max(1, widths[i] >> level);
        int hgt = Math.max(1, heights[i] >> level);
        int fmt = formats[i];
        boolean compressed = GpuFormats.isCompressed(fmt);
        int name = names[i];
        if (kinds[i] == KIND_2D) {
            if (compressed) {
                glCompressedTextureSubImage2D(name, level, 0, 0, w, hgt, fmt, bytes, address);
            } else {
                glTextureSubImage2D(
                        name,
                        level,
                        0,
                        0,
                        w,
                        hgt,
                        GpuFormats.transferFormat(fmt),
                        GpuFormats.transferType(fmt),
                        address);
            }
            return;
        }
        int depth =
                switch (kinds[i]) {
                    case KIND_2D_ARRAY -> layers[i];
                    case KIND_CUBE -> 6;
                    case KIND_3D -> Math.max(1, layers[i] >> level);
                    default -> layers[i] * 6;
                };
        if (compressed) {
            glCompressedTextureSubImage3D(name, level, 0, 0, 0, w, hgt, depth, fmt, bytes, address);
        } else {
            glTextureSubImage3D(
                    name,
                    level,
                    0,
                    0,
                    0,
                    w,
                    hgt,
                    depth,
                    GpuFormats.transferFormat(fmt),
                    GpuFormats.transferType(fmt),
                    address);
        }
    }

    /**
     * Uploads one mip level of one layer of a 2D array texture from native memory at {@code
     * address}. Allocates nothing.
     */
    public void uploadLayer(int handle, int level, int layer, long address, int bytes) {
        int i = check(handle);
        if (kinds[i] != KIND_2D_ARRAY) {
            throw new IllegalArgumentException("uploadLayer needs a 2D array texture");
        }
        if (tails[i] != null && level >= tailFirst[i]) {
            mirror(i, level, layer, 1, address, bytes);
        }
        layer(i, level, layer, address, bytes);
    }

    /**
     * {@link #uploadLayer} from {@code offset} into the pixel-unpack buffer {@code buffer}; see
     * {@link #uploadLevelFromBuffer} for what that costs a compressed array.
     */
    void uploadLayerFromBuffer(
            int handle, int level, int layer, int buffer, long offset, int bytes) {
        int i = check(handle);
        if (kinds[i] != KIND_2D_ARRAY) {
            throw new IllegalArgumentException("uploadLayer needs a 2D array texture");
        }
        if (level >= tailFirst[i]) {
            tailStale[i] = true;
        }
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, buffer);
        try {
            layer(i, level, layer, offset, bytes);
        } finally {
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
        }
    }

    private void layer(int i, int level, int layer, long address, int bytes) {
        int w = Math.max(1, widths[i] >> level);
        int hgt = Math.max(1, heights[i] >> level);
        int fmt = formats[i];
        if (GpuFormats.isCompressed(fmt)) {
            glCompressedTextureSubImage3D(
                    names[i], level, 0, 0, layer, w, hgt, 1, fmt, bytes, address);
        } else {
            glTextureSubImage3D(
                    names[i],
                    level,
                    0,
                    0,
                    layer,
                    w,
                    hgt,
                    1,
                    GpuFormats.transferFormat(fmt),
                    GpuFormats.transferType(fmt),
                    address);
        }
    }

    /**
     * Copies layers {@code [0, count)} of every level from one 2D array texture into another of the
     * same format, size and level count (growing an array). Runs on the GPU, except the tail of a
     * block-compressed texture (the levels smaller than a block, 2x2 and 1x1), which is uploaded
     * again from the CPU mirror: Intel's Windows driver rejects {@code glCopyImageSubData} on those
     * levels with {@code GL_INVALID_VALUE} and {@code glGetCompressedTextureSubImage} with {@code
     * GL_INVALID_OPERATION}, although the spec (and Mesa) allow a region that reaches the image's
     * edge. A cold path: arrays grow a handful of times while a level loads.
     */
    public void copyLayers(int from, int to, int count) {
        int a = check(from);
        int b = check(to);
        if (kinds[a] != KIND_2D_ARRAY
                || kinds[b] != KIND_2D_ARRAY
                || formats[a] != formats[b]
                || widths[a] != widths[b]
                || heights[a] != heights[b]
                || levels[a] != levels[b]) {
            throw new IllegalArgumentException("copyLayers needs two 2D arrays of one description");
        }
        if (count > layers[a] || count > layers[b]) {
            throw new IllegalArgumentException(count + " layers exceed the textures");
        }
        for (int l = 0; l < levels[a]; l++) {
            int w = Math.max(1, widths[a] >> l);
            int h = Math.max(1, heights[a] >> l);
            if (l >= tailFirst[a]) {
                copyTailLevel(a, b, l, w, h, count);
                continue;
            }
            glCopyImageSubData(
                    names[a],
                    GL_TEXTURE_2D_ARRAY,
                    l,
                    0,
                    0,
                    0,
                    names[b],
                    GL_TEXTURE_2D_ARRAY,
                    l,
                    0,
                    0,
                    0,
                    w,
                    h,
                    count);
        }
    }

    /**
     * A tail level: upload it into the other texture from the mirror, and copy the mirror along. A
     * mirror that a PBO upload bypassed is unknown, so the level is read back instead; the engine's
     * own uploads never take that path (it is the one Intel rejects).
     */
    private void copyTailLevel(int a, int b, int level, int w, int h, int count) {
        int bytes = count * levelBytes(formats[a], w, h);
        if (tailStale[a]) {
            tailStale[b] = true;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment blocks = arena.allocate(bytes, 16);
                glGetCompressedTextureSubImage(
                        names[a], level, 0, 0, 0, w, h, count, bytes, blocks);
                glCompressedTextureSubImage3D(
                        names[b], level, 0, 0, 0, w, h, count, formats[a], bytes, blocks);
            }
            return;
        }
        long from = tailOffset(a, level, 0);
        long to = tailOffset(b, level, 0);
        MemorySegment.copy(tails[a], from, tails[b], to, bytes);
        glCompressedTextureSubImage3D(
                names[b], level, 0, 0, 0, w, h, count, formats[a], bytes, tails[b].address() + to);
    }

    /** Returns the layer count of an array texture (1 otherwise). */
    public int layers(int handle) {
        return layers[check(handle)];
    }

    /** Returns the kind ({@link #KIND_2D}, ...). */
    public int kind(int handle) {
        return kinds[check(handle)];
    }

    /** Creates storage matching a KTX2 file (format, size, layers, faces, levels). */
    public int createFor(Ktx2 ktx, String label) {
        int fmt = GpuFormats.internalFormat(ktx.vkFormat());
        if (fmt == 0) {
            throw new UnsupportedOperationException(label + ": vkFormat " + ktx.vkFormat());
        }
        if (ktx.depth() > 1) {
            return create(
                    KIND_3D, fmt, ktx.width(), ktx.height(), ktx.depth(), ktx.levels(), label);
        }
        int kind =
                ktx.isCubeMap()
                        ? (ktx.isArray() ? KIND_CUBE_ARRAY : KIND_CUBE)
                        : (ktx.isArray() ? KIND_2D_ARRAY : KIND_2D);
        return create(kind, fmt, ktx.width(), ktx.height(), ktx.layers(), ktx.levels(), label);
    }

    /** Deletes the texture. */
    public void destroy(int handle) {
        int i = check(handle);
        device.state().forgetTexture(names[i]);
        MemorySegment id = device.scratch();
        id.set(ValueLayout.JAVA_INT, 0, names[i]);
        glDeleteTextures(1, id);
        names[i] = 0;
        if (tailArenas[i] != null) {
            tailArenas[i].close();
            tailArenas[i] = null;
            tails[i] = null;
        }
        handles.free(handle);
    }

    /** Returns the GL texture name. */
    public int glName(int handle) {
        return names[check(handle)];
    }

    /** Returns the internal format. */
    public int format(int handle) {
        return formats[check(handle)];
    }

    /** Returns the width of level 0. */
    public int width(int handle) {
        return widths[check(handle)];
    }

    /** Returns the height of level 0. */
    public int height(int handle) {
        return heights[check(handle)];
    }

    /** Returns the number of mip levels. */
    public int levels(int handle) {
        return levels[check(handle)];
    }

    /** Returns the number of live textures. */
    public int count() {
        return handles.size();
    }

    void destroyAll() {
        for (int i = 0; i < handles.highWaterMark(); i++) {
            int h = handles.handleAt(i);
            if (h != Handles.NULL) {
                destroy(h);
            }
        }
    }

    private void ensure(int i) {
        if (i >= names.length) {
            int cap = Math.max(i + 1, names.length * 2);
            names = Arrays.copyOf(names, cap);
            formats = Arrays.copyOf(formats, cap);
            kinds = Arrays.copyOf(kinds, cap);
            widths = Arrays.copyOf(widths, cap);
            heights = Arrays.copyOf(heights, cap);
            layers = Arrays.copyOf(layers, cap);
            levels = Arrays.copyOf(levels, cap);
            tails = Arrays.copyOf(tails, cap);
            tailArenas = Arrays.copyOf(tailArenas, cap);
            tailFirst = Arrays.copyOf(tailFirst, cap);
            tailStale = Arrays.copyOf(tailStale, cap);
        }
    }

    private int check(int handle) {
        if (!handles.isValid(handle)) {
            throw new IllegalArgumentException("stale texture handle " + Handles.toString(handle));
        }
        return Handles.index(handle);
    }
}
