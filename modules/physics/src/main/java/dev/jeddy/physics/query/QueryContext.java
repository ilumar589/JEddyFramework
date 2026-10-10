package dev.jeddy.physics.query;

import dev.jeddy.core.collections.IntList;
import dev.jeddy.physics.bvh.Bvh;
import dev.jeddy.physics.bvh.RayHit;

/**
 * Per-thread scratch and results for {@link CollisionWorld} queries, so queries allocate nothing
 * and run concurrently. Keep one per thread (for example in a {@code PerWorker}).
 *
 * <p>Thread ownership: one thread at a time.
 */
public final class QueryContext {
    final RayHit rayHit = new RayHit();
    final IntList candidates;
    final int[] stack = new int[Bvh.MAX_DEPTH * 2];
    final float[] scratch = new float[16];
    final CollisionWorld.SweepVisitor sweep = new CollisionWorld.SweepVisitor();

    /** Distance of the last hit along the query direction (fraction for sweeps). */
    public float t;

    /** Hit point. */
    public float x;

    /** Hit point y. */
    public float y;

    /** Hit point z. */
    public float z;

    /** Hit normal (unit; for sweeps a horizontal normal). */
    public float nx;

    /** Normal y. */
    public float ny;

    /** Normal z. */
    public float nz;

    /** Entity of the body hit, or -1 when the static world was hit (or nothing). */
    public int entity = -1;

    /** Body index hit, or -1. */
    public int body = -1;

    /** Static triangle hit (BVH order), or -1. */
    public int triangle = -1;

    /** {@code TriFlags} of the static triangle hit. */
    public int triangleFlags;

    /** What {@link CollisionWorld#pick} found: one of the {@code PICK_*} constants. */
    public int pickKind;

    /** Creates a context. */
    public QueryContext() {
        this(64);
    }

    /**
     * Creates a context whose broadphase candidate list holds {@code expectedBodies} without
     * growing: size it for the most bodies one query can touch (a crowd) to keep queries
     * allocation-free from the first.
     */
    public QueryContext(int expectedBodies) {
        candidates = new IntList(Math.max(16, expectedBodies));
    }

    void clear() {
        t = Float.POSITIVE_INFINITY;
        entity = -1;
        body = -1;
        triangle = -1;
        triangleFlags = 0;
        nx = ny = nz = 0;
    }

    /** Returns whether the last query hit something. */
    public boolean hit() {
        return t != Float.POSITIVE_INFINITY;
    }
}
