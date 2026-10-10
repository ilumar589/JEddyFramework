package dev.jeddy.physics.nav;

/**
 * Result of {@link NavMeshQuery#raycast}: reused by the caller, so a raycast allocates nothing.
 *
 * <p>Thread ownership: owned by the thread running the query.
 */
public final class NavRayHit {
    /**
     * Parameter along the ray where it left the navmesh (0..1), or {@link Float#MAX_VALUE} when it
     * reached its end without hitting a wall.
     */
    public float t;

    /** Normal of the wall that was hit (XZ, unit, pointing back into the navmesh). */
    public float normalX;

    /** Normal of the wall that was hit (XZ, unit, pointing back into the navmesh). */
    public float normalZ;

    /** Edge of {@link #lastRef} that was hit, or -1. */
    public int edge;

    /** The polygon the ray ended in. */
    public int lastRef;

    /** Number of polygons written to the path array (the polygons the ray crossed, in order). */
    public int pathCount;

    /** Creates an empty result. */
    public NavRayHit() {}

    /** Returns whether the ray hit a wall before its end. */
    public boolean hit() {
        return t != Float.MAX_VALUE;
    }
}
