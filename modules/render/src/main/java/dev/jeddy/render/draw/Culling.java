package dev.jeddy.render.draw;

import dev.jeddy.core.job.JobKernel;
import dev.jeddy.core.job.JobSystem;
import dev.jeddy.math.CullOps;
import dev.jeddy.render.RenderPacket;

/**
 * Frustum culling of a packet's instances by their world bounding spheres (TASKS M5-09, DESIGN
 * §9.4): a parallel pass over the SoA bounds writes a visibility byte per instance, then a short
 * sequential pass compacts the visible indices in packet order (deterministic).
 *
 * <p>Thread ownership: the render (main) thread calls {@link #cull}; it borrows job workers.
 */
public final class Culling {
    private static final int PARALLEL_FROM = 4096;
    private static final int GRAIN = 1024;

    private final JobSystem jobs;
    private byte[] visibleFlags = new byte[1024];
    private RenderPacket packet;
    private float[] planes;
    private int requiredFlags;
    private int excludedFlags;
    private float sphereX;
    private float sphereY;
    private float sphereZ;
    private float sphereRadius = -1;
    private final JobKernel kernel = this::cullRange;

    /** Creates a culler; {@code jobs} may be {@code null} to cull on the calling thread. */
    public Culling(JobSystem jobs) {
        this.jobs = jobs;
    }

    /**
     * Writes the indices of visible instances into {@code out} (at least {@code packet.count()}
     * long) and returns how many there are. Allocates nothing unless the flag array grows.
     */
    public int cull(RenderPacket p, int[] out) {
        return cull(p, p.frustumPlanes, 0, out);
    }

    /**
     * Culls against other planes (shadow views), keeping only instances with all {@code
     * requiredFlags} (for example {@code RenderPacket.FLAG_CASTS_SHADOW}).
     */
    public int cull(RenderPacket p, float[] frustumPlanes, int requiredFlags, int[] out) {
        return cull(p, frustumPlanes, requiredFlags, 0, 0, 0, -1, out);
    }

    /**
     * Like {@link #cull(RenderPacket, float[], int, int[])}, also dropping instances whose bounds
     * miss the sphere {@code (x, y, z, radius)} (a light's range; {@code radius < 0} for none).
     */
    public int cull(
            RenderPacket p,
            float[] frustumPlanes,
            int requiredFlags,
            float x,
            float y,
            float z,
            float radius,
            int[] out) {
        return cull(p, frustumPlanes, requiredFlags, 0, x, y, z, radius, out);
    }

    /** The general form: also drops instances with any of {@code excludedFlags}. */
    public int cull(
            RenderPacket p,
            float[] frustumPlanes,
            int requiredFlags,
            int excludedFlags,
            float x,
            float y,
            float z,
            float radius,
            int[] out) {
        this.excludedFlags = excludedFlags;
        int n = p.count();
        if (visibleFlags.length < n) {
            visibleFlags = new byte[Math.max(n, visibleFlags.length * 2)];
        }
        packet = p;
        planes = frustumPlanes;
        this.requiredFlags = requiredFlags;
        sphereX = x;
        sphereY = y;
        sphereZ = z;
        sphereRadius = radius;
        if (jobs != null && n >= PARALLEL_FROM) {
            jobs.parallelFor(kernel, n, GRAIN);
        } else {
            cullRange(0, n, 0);
        }
        packet = null;
        planes = null;
        byte[] f = visibleFlags;
        int visible = 0;
        for (int i = 0; i < n; i++) {
            if (f[i] != 0) {
                out[visible++] = i;
            }
        }
        return visible;
    }

    private void cullRange(int start, int end, int thread) {
        float[] pl = planes;
        float[] b = packet.bounds;
        int[] flags = packet.flags;
        int required = requiredFlags;
        int excluded = excludedFlags;
        float r = sphereRadius;
        byte[] f = visibleFlags;
        for (int i = start; i < end; i++) {
            int o = i * 4;
            boolean v =
                    (flags[i] & required) == required
                            && (flags[i] & excluded) == 0
                            && CullOps.sphereVisible(pl, b[o], b[o + 1], b[o + 2], b[o + 3]);
            if (v && r >= 0) {
                float dx = b[o] - sphereX;
                float dy = b[o + 1] - sphereY;
                float dz = b[o + 2] - sphereZ;
                float reach = r + b[o + 3];
                v = dx * dx + dy * dy + dz * dz <= reach * reach;
            }
            f[i] = v ? (byte) 1 : 0;
        }
    }
}
