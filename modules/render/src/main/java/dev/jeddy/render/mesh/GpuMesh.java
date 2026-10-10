package dev.jeddy.render.mesh;

import dev.jeddy.asset.format.MeshData;

/**
 * A loaded mesh: its {@link MeshRegistry} handle once uploaded, plus what the game thread needs
 * without touching the registry: the local bounding sphere and box, and the default material of
 * each slot.
 *
 * <p>Thread ownership: created on a worker, completed on the main thread, then read-only (any
 * thread, once the asset is ready).
 */
public final class GpuMesh {
    MeshData data;
    int handle;
    final long[] materialIds;
    final float[] sphere = new float[4];
    final float[] box = new float[6];
    final int submeshes;
    boolean wired;

    GpuMesh(MeshData data) {
        this.data = data;
        materialIds = new long[data.materialSlotCount()];
        for (int s = 0; s < materialIds.length; s++) {
            materialIds[s] = data.materialId(s);
        }
        for (int i = 0; i < 4; i++) {
            sphere[i] = data.sphere(i);
        }
        for (int k = 0; k < 3; k++) {
            box[k] = data.boundsMin(k);
            box[3 + k] = data.boundsMax(k);
        }
        submeshes = data.submeshCount();
    }

    /** Returns the registry handle. */
    public int handle() {
        return handle;
    }

    /** Returns the local bounding sphere: {@code i} 0 to 2 the center, 3 the radius. */
    public float sphere(int i) {
        return sphere[i];
    }

    /** Returns the local bounds: min x, y, z for {@code i} 0 to 2, max for 3 to 5. */
    public float bounds(int i) {
        return box[i];
    }

    /** Returns the number of material slots. */
    public int materialSlots() {
        return materialIds.length;
    }

    /** Returns the asset id of slot {@code s}'s default material. */
    public long materialId(int s) {
        return materialIds[s];
    }

    /** Returns the number of submeshes. */
    public int submeshCount() {
        return submeshes;
    }
}
