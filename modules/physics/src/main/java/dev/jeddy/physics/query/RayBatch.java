package dev.jeddy.physics.query;

import dev.jeddy.core.job.JobKernel;
import dev.jeddy.core.job.JobSystem;
import dev.jeddy.core.pool.PerWorker;

import java.util.Arrays;

/**
 * A batch of rays run as one {@code parallelFor} (DESIGN §11.3 {@code QueryBatch}): SoA inputs
 * (origin, direction, length) and outputs (distance, entity, triangle, normal), so AI perception,
 * projectile updates and line-of-sight sweeps for many units cost one dispatch. Each worker uses
 * its own {@link QueryContext}.
 *
 * <p>Thread ownership: one thread fills and runs a batch; workers write disjoint outputs.
 */
public final class RayBatch {
    private static final int GRAIN = 64;

    /** Ray origins and directions (x, y, z, dx, dy, dz per ray). */
    public float[] rays;

    /** Ray lengths in units of the direction's length. */
    public float[] maxT;

    /** Output: hit distance, or +∞. */
    public float[] t;

    /** Output: entity hit, or -1. */
    public int[] entity;

    /** Output: static triangle hit, or -1. */
    public int[] triangle;

    /** Output: hit normals (3 per ray). */
    public float[] normals;

    private int count;
    private final PerWorker<QueryContext> contexts;
    private CollisionWorld world;
    private int triangleMask;
    private int layerMask;
    private boolean any;
    private final JobKernel kernel = this::runRange;

    /** Creates a batch for {@code capacity} rays (grows) with contexts for {@code threadSlots}. */
    public RayBatch(int capacity, int threadSlots) {
        allocate(Math.max(16, capacity));
        contexts = new PerWorker<>(threadSlots, i -> new QueryContext());
    }

    private void allocate(int cap) {
        rays = rays == null ? new float[cap * 6] : Arrays.copyOf(rays, cap * 6);
        maxT = maxT == null ? new float[cap] : Arrays.copyOf(maxT, cap);
        t = t == null ? new float[cap] : Arrays.copyOf(t, cap);
        entity = entity == null ? new int[cap] : Arrays.copyOf(entity, cap);
        triangle = triangle == null ? new int[cap] : Arrays.copyOf(triangle, cap);
        normals = normals == null ? new float[cap * 3] : Arrays.copyOf(normals, cap * 3);
    }

    /** Empties the batch. */
    public void clear() {
        count = 0;
    }

    /** Returns the number of rays. */
    public int count() {
        return count;
    }

    /** Adds a ray and returns its index. */
    public int add(float ox, float oy, float oz, float dx, float dy, float dz, float length) {
        if (count == maxT.length) {
            allocate(count * 2);
        }
        int i = count++;
        int o = i * 6;
        rays[o] = ox;
        rays[o + 1] = oy;
        rays[o + 2] = oz;
        rays[o + 3] = dx;
        rays[o + 4] = dy;
        rays[o + 5] = dz;
        maxT[i] = length;
        return i;
    }

    /**
     * Casts every ray against {@code w} (closest hits, or any hit when {@code anyHit}); {@code
     * jobs} may be {@code null}. Allocates nothing.
     */
    public void run(CollisionWorld w, int triangles, int layers, boolean anyHit, JobSystem jobs) {
        world = w;
        triangleMask = triangles;
        layerMask = layers;
        any = anyHit;
        if (jobs != null && count > GRAIN) {
            jobs.parallelFor(kernel, count, GRAIN);
        } else {
            runRange(0, count, 0);
        }
        world = null;
    }

    private void runRange(int start, int end, int thread) {
        QueryContext q = contexts.get(thread);
        CollisionWorld w = world;
        for (int i = start; i < end; i++) {
            int o = i * 6;
            boolean hit =
                    any
                            ? w.raycastAny(
                                    rays[o],
                                    rays[o + 1],
                                    rays[o + 2],
                                    rays[o + 3],
                                    rays[o + 4],
                                    rays[o + 5],
                                    maxT[i],
                                    triangleMask,
                                    layerMask,
                                    -1,
                                    q)
                            : w.raycast(
                                    rays[o],
                                    rays[o + 1],
                                    rays[o + 2],
                                    rays[o + 3],
                                    rays[o + 4],
                                    rays[o + 5],
                                    maxT[i],
                                    triangleMask,
                                    layerMask,
                                    -1,
                                    q);
            t[i] = hit ? q.t : Float.POSITIVE_INFINITY;
            entity[i] = hit ? q.entity : -1;
            triangle[i] = hit ? q.triangle : -1;
            normals[i * 3] = q.nx;
            normals[i * 3 + 1] = q.ny;
            normals[i * 3 + 2] = q.nz;
        }
    }
}
