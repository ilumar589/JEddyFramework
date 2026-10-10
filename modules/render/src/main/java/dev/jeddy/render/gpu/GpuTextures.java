package dev.jeddy.render.gpu;

import static dev.jeddy.gl.ffi.GL46.*;

import dev.jeddy.asset.format.Ktx2;
import dev.jeddy.core.handle.HandlePool;
import dev.jeddy.core.handle.Handles;
import dev.jeddy.gl.GLDebug;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;

/**
 * Textures behind generational handles (TASKS M5-03): immutable storage ({@code glTextureStorage*})
 * for 2D, 2D arrays and cube maps, uploads of BCn blocks or pixels per level, and creation from
 * KTX2 (DESIGN §9.2).
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

    private final GpuDevice device;
    private final HandlePool handles = new HandlePool(64, Handles.MAX_INDEX + 1);
    private int[] names = new int[64];
    private int[] formats = new int[64];
    private int[] kinds = new int[64];
    private int[] widths = new int[64];
    private int[] heights = new int[64];
    private int[] layers = new int[64];
    private int[] levels = new int[64];

    GpuTextures(GpuDevice device) {
        this.device = device;
    }

    /**
     * Creates texture storage.
     *
     * @param kind {@link #KIND_2D}, ...
     * @param internalFormat a GL sized or compressed internal format ({@link GpuFormats})
     * @param layerCount array layers (1 for non-arrays)
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
                    default -> throw new IllegalArgumentException("texture kind " + kind);
                };
        MemorySegment id = device.scratch();
        glCreateTextures(target, 1, id);
        int name = id.get(ValueLayout.JAVA_INT, 0);
        switch (kind) {
            case KIND_2D, KIND_CUBE ->
                    glTextureStorage2D(name, levelCount, internalFormat, width, height);
            case KIND_2D_ARRAY ->
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
        return h;
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
                kinds[i] == KIND_2D_ARRAY ? layers[i] : kinds[i] == KIND_CUBE ? 6 : layers[i] * 6;
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

    /** Creates storage matching a KTX2 file (format, size, layers, faces, levels). */
    public int createFor(Ktx2 ktx, String label) {
        int fmt = GpuFormats.internalFormat(ktx.vkFormat());
        if (fmt == 0) {
            throw new UnsupportedOperationException(label + ": vkFormat " + ktx.vkFormat());
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
        MemorySegment id = device.scratch();
        id.set(ValueLayout.JAVA_INT, 0, names[i]);
        glDeleteTextures(1, id);
        names[i] = 0;
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
        }
    }

    private int check(int handle) {
        if (!handles.isValid(handle)) {
            throw new IllegalArgumentException("stale texture handle " + Handles.toString(handle));
        }
        return Handles.index(handle);
    }
}
