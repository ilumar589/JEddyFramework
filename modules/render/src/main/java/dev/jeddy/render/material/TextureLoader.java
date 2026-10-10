package dev.jeddy.render.material;

import dev.jeddy.asset.AssetLoader;
import dev.jeddy.asset.DecodeContext;
import dev.jeddy.asset.format.Ktx2;
import dev.jeddy.render.gpu.GpuDevice;
import dev.jeddy.render.gpu.GpuFormats;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Loads {@code .ktx2} textures (TASKS M7-01): validation and zstd decompression of every level on a
 * worker, then on the main thread an upload into a layer of the material {@link TextureArrays}
 * (single 2D textures) or into a texture of its own (cube maps, arrays). Cooked environments also
 * carry their SH9 irradiance ({@code jeddy.sh9}).
 *
 * <p>Thread ownership: see {@link AssetLoader}.
 */
public final class TextureLoader implements AssetLoader<GpuTexture> {
    /** Key/value entry of cooked environments' irradiance. */
    public static final String SH9_KEY = "jeddy.sh9";

    private final GpuDevice device;
    private final TextureArrays arrays;

    /** Creates the loader. */
    public TextureLoader(GpuDevice device, TextureArrays arrays) {
        this.device = device;
        this.arrays = arrays;
    }

    @Override
    public GpuTexture decode(long id, MemorySegment data, DecodeContext context) throws Exception {
        Ktx2 k = Ktx2.read(data);
        if (GpuFormats.internalFormat(k.vkFormat()) == 0) {
            throw new UnsupportedOperationException(
                    "vkFormat " + k.vkFormat() + " has no GL format");
        }
        GpuTexture t = new GpuTexture();
        t.width = k.width();
        t.height = k.height();
        t.levels = k.levels();
        t.vkFormat = k.vkFormat();
        byte[] sh = k.keyValueBytes(SH9_KEY);
        if (sh != null && sh.length == 27 * 4) {
            ByteBuffer b = ByteBuffer.wrap(sh).order(ByteOrder.LITTLE_ENDIAN);
            t.sh9 = new float[27];
            for (int i = 0; i < 27; i++) {
                t.sh9[i] = b.getFloat(i * 4);
            }
        }
        t.levelData = new MemorySegment[k.levels()];
        if (k.supercompression() != Ktx2.SUPERCOMPRESSION_NONE) {
            // Uncompressed levels are copied out of the staged file in upload.
            t.levelArena = Arena.ofShared();
            for (int l = 0; l < k.levels(); l++) {
                MemorySegment level = t.levelArena.allocate(Math.max(1, k.levelSize(l)), 16);
                k.decodeLevel(l, level, 0, context.decompressor());
                t.levelData[l] = level;
            }
        }
        t.ktx = k;
        return t;
    }

    @Override
    public boolean needsUpload() {
        return true;
    }

    @Override
    public GpuTexture upload(GpuTexture t, MemorySegment data) throws Exception {
        Ktx2 k = t.ktx;
        try {
            int format = GpuFormats.internalFormat(k.vkFormat());
            boolean layer = !k.isCubeMap() && !k.isArray() && k.depth() <= 1;
            if (layer) {
                t.arrayRef = arrays.allocate(format, k.width(), k.height(), k.levels());
                int texture = arrays.textureHandle(TextureArrays.bucket(t.arrayRef));
                for (int l = 0; l < k.levels(); l++) {
                    MemorySegment level = level(t, l);
                    device.textures()
                            .uploadLayer(
                                    texture,
                                    l,
                                    TextureArrays.layer(t.arrayRef),
                                    level.address(),
                                    (int) level.byteSize());
                }
            } else {
                t.handle = device.textures().createFor(k, "texture");
                for (int l = 0; l < k.levels(); l++) {
                    MemorySegment level = level(t, l);
                    device.textures()
                            .uploadLevel(t.handle, l, level.address(), (int) level.byteSize());
                }
            }
        } finally {
            release(t);
        }
        return t;
    }

    /** Level {@code l}: decompressed by {@link #decode}, or a copy out of the staged file. */
    private static MemorySegment level(GpuTexture t, int l) throws Exception {
        if (t.levelData[l] != null) {
            return t.levelData[l];
        }
        if (t.levelArena == null) {
            t.levelArena = Arena.ofShared();
        }
        MemorySegment out = t.levelArena.allocate(Math.max(1, t.ktx.levelSize(l)), 16);
        t.ktx.decodeLevel(l, out, 0, null);
        t.levelData[l] = out;
        return out;
    }

    private static void release(GpuTexture t) {
        if (t.levelArena != null) {
            t.levelArena.close();
            t.levelArena = null;
        }
        t.levelData = null;
        t.ktx = null;
    }

    @Override
    public void unload(GpuTexture t) {
        if (t.arrayRef != TextureArrays.NONE) {
            arrays.free(t.arrayRef);
            t.arrayRef = TextureArrays.NONE;
        }
        if (t.handle != dev.jeddy.core.handle.Handles.NULL) {
            device.textures().destroy(t.handle);
            t.handle = dev.jeddy.core.handle.Handles.NULL;
        }
        release(t);
    }
}
