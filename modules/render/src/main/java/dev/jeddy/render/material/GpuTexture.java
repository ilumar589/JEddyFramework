package dev.jeddy.render.material;

import dev.jeddy.asset.format.Ktx2;
import dev.jeddy.core.handle.Handles;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * A loaded texture asset: either a layer of the material {@link TextureArrays} (2D textures) or a
 * texture of its own (cube maps, arrays, LUTs), plus the SH9 irradiance of cooked environments.
 *
 * <p>Thread ownership: created by {@link TextureLoader} on a worker, finished and read on the GL
 * thread.
 */
public final class GpuTexture {
    /** Reference into {@link TextureArrays}, or {@link TextureArrays#NONE}. */
    public int arrayRef = TextureArrays.NONE;

    /** Texture handle ({@code GpuTextures}) for standalone textures, or {@link Handles#NULL}. */
    public int handle = Handles.NULL;

    /** Size, levels and format. */
    public int width;

    /** Height of level 0. */
    public int height;

    /** Mip levels. */
    public int levels;

    /** The KTX2 {@code vkFormat}. */
    public int vkFormat;

    /** SH9 irradiance (27 floats) of a cooked environment, else {@code null}. */
    public float[] sh9;

    // Decoded level data (supercompressed files), freed after upload.
    Ktx2 ktx;
    Arena levelArena;
    MemorySegment[] levelData;

    GpuTexture() {}

    /** Returns {@code true} for a layer of the material arrays. */
    public boolean isArrayLayer() {
        return arrayRef != TextureArrays.NONE;
    }
}
