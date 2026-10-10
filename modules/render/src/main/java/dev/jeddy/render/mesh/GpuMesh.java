package dev.jeddy.render.mesh;

import dev.jeddy.asset.format.MeshData;

/**
 * A loaded mesh: its {@link MeshRegistry} handle once uploaded.
 *
 * <p>Thread ownership: created on a worker, completed on the main thread, then read-only.
 */
public final class GpuMesh {
    MeshData data;
    int handle;

    GpuMesh(MeshData data) {
        this.data = data;
    }

    /** Returns the registry handle. */
    public int handle() {
        return handle;
    }
}
