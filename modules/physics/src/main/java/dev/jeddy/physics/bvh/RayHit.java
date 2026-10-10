package dev.jeddy.physics.bvh;

/**
 * The result of a BVH ray query, and the scratch space the query needs (a traversal stack), so
 * queries allocate nothing. Keep one per thread and reuse it.
 *
 * <p>Thread ownership: one thread at a time.
 */
public final class RayHit {
    /** Distance along the ray in units of its direction's length (+∞ when nothing was hit). */
    public float t = Float.POSITIVE_INFINITY;

    /** Stored triangle index ({@link Bvh#triangleId} gives the mesh index), or -1. */
    public int triangle = -1;

    /** The triangle's {@link TriFlags}. */
    public int flags;

    /** Barycentric coordinates of the hit (weights of vertices 1 and 2). */
    public float u;

    /** Barycentric coordinate of vertex 2. */
    public float v;

    /** Unit geometric normal of the hit triangle (winding order; may face away from the ray). */
    public float nx;

    /** Normal y. */
    public float ny;

    /** Normal z. */
    public float nz;

    final int[] stack = new int[Bvh.MAX_DEPTH * 2];
    final float[] entry = new float[Bvh.MAX_DEPTH * 2];

    /** Creates an empty hit. */
    public RayHit() {}

    /** Returns whether the last query hit something. */
    public boolean hit() {
        return triangle >= 0;
    }
}
