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
        int n = p.count();
        if (visibleFlags.length < n) {
            visibleFlags = new byte[Math.max(n, visibleFlags.length * 2)];
        }
        packet = p;
        if (jobs != null && n >= PARALLEL_FROM) {
            jobs.parallelFor(kernel, n, GRAIN);
        } else {
            cullRange(0, n, 0);
        }
        packet = null;
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
        float[] planes = packet.frustumPlanes;
        float[] b = packet.bounds;
        byte[] f = visibleFlags;
        for (int i = start; i < end; i++) {
            int o = i * 4;
            f[i] = CullOps.sphereVisible(planes, b[o], b[o + 1], b[o + 2], b[o + 3]) ? (byte) 1 : 0;
        }
    }
}
