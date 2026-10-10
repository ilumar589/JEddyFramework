package dev.jeddy.physics.nav;

import dev.jeddy.core.job.JobKernel;
import dev.jeddy.core.job.JobSystem;
import dev.jeddy.core.pool.PerWorker;

import java.util.Arrays;

/**
 * A batch of path requests run as one {@code parallelFor} (DESIGN §11.4): each worker uses its own
 * {@link NavMeshQuery}, so a frame's repaths (crowd agents, AI planners) cost one dispatch. Inputs
 * and outputs are SoA: for request {@code i}, its corridor is {@code polys[i * maxPath .. +
 * polyCount[i])} and its corners {@code corners[i * maxCorners * 3 ..]} ({@code cornerCount[i]} of
 * them, flags in {@code cornerFlags}).
 *
 * <p>Thread ownership: one thread fills and runs a batch; workers write disjoint outputs. The mesh
 * must not change during {@link #run}.
 */
public final class NavPathBatch {
    private static final int GRAIN = 4;

    private final int maxPath;
    private final int maxCorners;
    private final PerWorker<Worker> workers;
    private final JobKernel kernel = this::runRange;
    private QueryFilter filter;
    private int count;

    /** Request endpoints: start x, y, z, end x, y, z. */
    public float[] points;

    /** Request polygons (0: the nearest within {@link #searchExtents}). */
    public int[] startRefs;

    /** Request polygons (0: the nearest within {@link #searchExtents}). */
    public int[] endRefs;

    /** Output: {@link NavMeshQuery} status. */
    public int[] status;

    /** Output: corridors, {@code maxPath} slots per request. */
    public int[] polys;

    /** Output: corridor lengths. */
    public int[] polyCount;

    /** Output: string-pulled corners, {@code maxCorners × 3} floats per request. */
    public float[] corners;

    /** Output: corner flags ({@code STRAIGHT_*}), {@code maxCorners} per request. */
    public int[] cornerFlags;

    /** Output: number of corners. */
    public int[] cornerCount;

    /** Half extents of the nearest-polygon search for requests without polygons. */
    public final float[] searchExtents = {2, 4, 2};

    private static final class Worker {
        final NavMeshQuery query;
        final int[] path;
        final float[] pts;
        final int[] flags;
        final float[] p = new float[3];

        Worker(NavMesh mesh, int maxNodes, int maxPath, int maxCorners) {
            query = new NavMeshQuery(mesh, maxNodes);
            path = new int[maxPath];
            pts = new float[maxCorners * 3];
            flags = new int[maxCorners];
        }
    }

    /**
     * Creates a batch over {@code mesh} for {@code capacity} requests (grows), corridors of up to
     * {@code maxPath} polygons and {@code maxCorners} corners, with a query of {@code maxNodes}
     * nodes for each of {@code threadSlots} threads.
     */
    public NavPathBatch(
            NavMesh mesh,
            int capacity,
            int maxPath,
            int maxCorners,
            int maxNodes,
            int threadSlots) {
        this.maxPath = maxPath;
        this.maxCorners = maxCorners;
        allocate(Math.max(8, capacity));
        workers =
                new PerWorker<>(threadSlots, i -> new Worker(mesh, maxNodes, maxPath, maxCorners));
    }

    private void allocate(int cap) {
        points = points == null ? new float[cap * 6] : Arrays.copyOf(points, cap * 6);
        startRefs = startRefs == null ? new int[cap] : Arrays.copyOf(startRefs, cap);
        endRefs = endRefs == null ? new int[cap] : Arrays.copyOf(endRefs, cap);
        status = status == null ? new int[cap] : Arrays.copyOf(status, cap);
        polys = polys == null ? new int[cap * maxPath] : Arrays.copyOf(polys, cap * maxPath);
        polyCount = polyCount == null ? new int[cap] : Arrays.copyOf(polyCount, cap);
        corners =
                corners == null
                        ? new float[cap * maxCorners * 3]
                        : Arrays.copyOf(corners, cap * maxCorners * 3);
        cornerFlags =
                cornerFlags == null
                        ? new int[cap * maxCorners]
                        : Arrays.copyOf(cornerFlags, cap * maxCorners);
        cornerCount = cornerCount == null ? new int[cap] : Arrays.copyOf(cornerCount, cap);
    }

    /** Returns the corridor capacity per request. */
    public int maxPath() {
        return maxPath;
    }

    /** Returns the corner capacity per request. */
    public int maxCorners() {
        return maxCorners;
    }

    /** Empties the batch. */
    public void clear() {
        count = 0;
    }

    /** Returns the number of requests. */
    public int count() {
        return count;
    }

    /**
     * Adds a request from {@code start} to {@code end} (polygons may be 0: found by position) and
     * returns its index.
     */
    public int add(
            int startRef, float sx, float sy, float sz, int endRef, float ex, float ey, float ez) {
        if (count == startRefs.length) {
            allocate(count * 2);
        }
        int i = count++;
        int o = i * 6;
        points[o] = sx;
        points[o + 1] = sy;
        points[o + 2] = sz;
        points[o + 3] = ex;
        points[o + 4] = ey;
        points[o + 5] = ez;
        startRefs[i] = startRef;
        endRefs[i] = endRef;
        return i;
    }

    /** Runs every request with {@code f}; {@code jobs} may be {@code null}. Allocates nothing. */
    public void run(QueryFilter f, JobSystem jobs) {
        filter = f;
        if (jobs != null && count > GRAIN) {
            jobs.parallelFor(kernel, count, GRAIN);
        } else {
            runRange(0, count, 0);
        }
        filter = null;
    }

    private void runRange(int from, int to, int thread) {
        Worker w = workers.get(thread);
        NavMeshQuery q = w.query;
        QueryFilter f = filter;
        float hx = searchExtents[0];
        float hy = searchExtents[1];
        float hz = searchExtents[2];
        for (int i = from; i < to; i++) {
            int o = i * 6;
            float sx = points[o];
            float sy = points[o + 1];
            float sz = points[o + 2];
            float ex = points[o + 3];
            float ey = points[o + 4];
            float ez = points[o + 5];
            int s = startRefs[i];
            if (s == NavMesh.NULL_REF) {
                s = q.findNearestPoly(sx, sy, sz, hx, hy, hz, f, w.p);
                sx = w.p[0];
                sy = w.p[1];
                sz = w.p[2];
            }
            int e = endRefs[i];
            if (e == NavMesh.NULL_REF) {
                e = q.findNearestPoly(ex, ey, ez, hx, hy, hz, f, w.p);
                ex = w.p[0];
                ey = w.p[1];
                ez = w.p[2];
            }
            if (s == NavMesh.NULL_REF || e == NavMesh.NULL_REF) {
                status[i] = NavMeshQuery.FAILURE;
                polyCount[i] = 0;
                cornerCount[i] = 0;
                continue;
            }
            int n = q.findPath(s, e, sx, sy, sz, ex, ey, ez, f, w.path);
            status[i] = q.lastStatus();
            polyCount[i] = n;
            System.arraycopy(w.path, 0, polys, i * maxPath, n);
            int c =
                    n == 0
                            ? 0
                            : q.findStraightPath(
                                    sx,
                                    sy,
                                    sz,
                                    ex,
                                    ey,
                                    ez,
                                    w.path,
                                    n,
                                    w.pts,
                                    w.flags,
                                    null,
                                    maxCorners);
            cornerCount[i] = c;
            System.arraycopy(w.pts, 0, corners, i * maxCorners * 3, c * 3);
            System.arraycopy(w.flags, 0, cornerFlags, i * maxCorners, c);
        }
    }
}
