package dev.jeddy.physics.nav;

import java.util.Arrays;

/**
 * Which polygons a navmesh query may use and what they cost (DESIGN §11.4): a polygon passes when
 * its flags share a bit with {@link #include} and none with {@link #exclude}; travel cost is the
 * distance times the cost of the area it crosses (difficult terrain ×2, water, hazards).
 *
 * <p>Thread ownership: configured by one thread; read concurrently by any number of queries once
 * configured (do not change it while queries run).
 */
public final class QueryFilter {
    /** Number of area ids (0..63). */
    public static final int AREAS = 64;

    private final float[] areaCost = new float[AREAS];
    private float minCost = 1f;

    /** Polygons must have at least one of these flags. */
    public int include = 0xffff_ffff;

    /** Polygons must have none of these flags. */
    public int exclude;

    /** Creates a filter that passes every polygon at unit cost. */
    public QueryFilter() {
        Arrays.fill(areaCost, 1f);
    }

    /** Sets the cost multiplier of an area (1 is normal; {@code +∞} forbids it). */
    public QueryFilter areaCost(int area, float cost) {
        areaCost[area] = cost;
        float m = Float.POSITIVE_INFINITY;
        for (float c : areaCost) {
            m = Math.min(m, c);
        }
        minCost = m;
        return this;
    }

    /** Returns the smallest area cost (scales the A* heuristic so it never overestimates). */
    public float minCost() {
        return minCost;
    }

    /** Returns the cost multiplier of an area. */
    public float areaCost(int area) {
        return areaCost[area];
    }

    /** Returns whether polygon {@code ref} of {@code mesh} passes the flag and area tests. */
    public boolean passes(NavMesh mesh, int ref) {
        NavTile t = mesh.tile(ref);
        int p = mesh.poly(ref);
        int f = t.flags[p];
        return (f & include) != 0
                && (f & exclude) == 0
                && areaCost[t.area[p]] < Float.POSITIVE_INFINITY;
    }

    /** Returns the cost of moving from {@code a} to {@code b} inside a polygon of {@code area}. */
    public float cost(float ax, float ay, float az, float bx, float by, float bz, int area) {
        float dx = bx - ax;
        float dy = by - ay;
        float dz = bz - az;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz) * areaCost[area];
    }
}
