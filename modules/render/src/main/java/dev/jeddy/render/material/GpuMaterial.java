package dev.jeddy.render.material;

import dev.jeddy.asset.format.MaterialData;

/**
 * A loaded material asset: its {@link MaterialSystem} index (what render packets and meshes refer
 * to) and its cooked data.
 *
 * <p>Thread ownership: created on a worker, then used on the GL thread.
 */
public final class GpuMaterial {
    /** The material index, -1 until uploaded. */
    public int index = -1;

    /** The asset id. */
    public final long id;

    /** The cooked material. */
    public final MaterialData data;

    GpuMaterial(long id, MaterialData data) {
        this.id = id;
        this.data = data;
    }
}
