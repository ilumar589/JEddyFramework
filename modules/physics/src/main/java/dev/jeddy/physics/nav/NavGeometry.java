package dev.jeddy.physics.nav;

/**
 * Geometry on navmesh polygons, mostly 2D on the XZ plane. Polygons wind so that {@code (b - a) ×
 * (c - a) < 0} in XZ for consecutive vertices (Recast's convention), which the funnel and segment
 * tests rely on. Allocates nothing; results go into caller arrays.
 *
 * <p>Thread ownership: stateless; any thread.
 */
public final class NavGeometry {
    private static final float HEIGHT_EPS = 1e-4f;

    private NavGeometry() {}

    /**
     * Twice the signed area of triangle {@code abc} in XZ, positive for consecutive polygon
     * vertices (Recast's {@code dtTriArea2D}). The funnel uses its sign for left and right.
     */
    public static float triArea2D(float ax, float az, float bx, float bz, float cx, float cz) {
        return (cx - ax) * (bz - az) - (bx - ax) * (cz - az);
    }

    /** Returns whether {@code (x, z)} is inside polygon {@code p} (crossing number, in XZ). */
    public static boolean pointInPoly(NavTile t, int p, float x, float z) {
        int n = t.polyVertCount[p];
        float[] v = t.verts;
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            int vi = t.vertex(p, i);
            int vj = t.vertex(p, j);
            float xi = v[vi];
            float zi = v[vi + 2];
            float xj = v[vj];
            float zj = v[vj + 2];
            if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }

    /**
     * Returns the height of polygon {@code p} at {@code (x, z)} from its triangle fan, or {@code
     * NaN} when the point is not over the polygon.
     */
    public static float polyHeight(NavTile t, int p, float x, float z) {
        int n = t.polyVertCount[p];
        float[] v = t.verts;
        int a = t.vertex(p, 0);
        float ax = v[a];
        float ay = v[a + 1];
        float az = v[a + 2];
        for (int k = 1; k + 1 < n; k++) {
            int b = t.vertex(p, k);
            int c = t.vertex(p, k + 1);
            float v0x = v[c] - ax;
            float v0y = v[c + 1] - ay;
            float v0z = v[c + 2] - az;
            float v1x = v[b] - ax;
            float v1y = v[b + 1] - ay;
            float v1z = v[b + 2] - az;
            float v2x = x - ax;
            float v2z = z - az;
            float denom = v0x * v1z - v0z * v1x;
            if (Math.abs(denom) < 1e-12f) {
                continue;
            }
            float u = v1z * v2x - v1x * v2z;
            float w = v0x * v2z - v0z * v2x;
            if (denom < 0) {
                denom = -denom;
                u = -u;
                w = -w;
            }
            float eps = HEIGHT_EPS * denom;
            if (u >= -eps && w >= -eps && u + w <= denom + eps) {
                return ay + (v0y * u + v1y * w) / denom;
            }
        }
        return Float.NaN;
    }

    /**
     * Writes the point of polygon {@code p} closest to {@code (x, y, z)} into {@code out[0..3)}:
     * the point itself at the polygon's height when it is over the polygon (returns {@code true}),
     * else the closest point on the polygon's boundary in XZ (returns {@code false}).
     */
    public static boolean closestPointOnPoly(
            NavTile t, int p, float x, float y, float z, float[] out) {
        if (pointInPoly(t, p, x, z)) {
            float h = polyHeight(t, p, x, z);
            if (h == h) {
                out[0] = x;
                out[1] = h;
                out[2] = z;
                return true;
            }
        }
        closestPointOnBoundary(t, p, x, z, out);
        return false;
    }

    /** Writes the closest point on polygon {@code p}'s boundary to {@code (x, z)} (in XZ). */
    public static void closestPointOnBoundary(NavTile t, int p, float x, float z, float[] out) {
        int n = t.polyVertCount[p];
        float[] v = t.verts;
        float best = Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            int a = t.vertex(p, i);
            int b = t.vertex(p, i + 1 < n ? i + 1 : 0);
            float s = segmentParam2D(x, z, v[a], v[a + 2], v[b], v[b + 2]);
            float px = v[a] + (v[b] - v[a]) * s;
            float pz = v[a + 2] + (v[b + 2] - v[a + 2]) * s;
            float dx = px - x;
            float dz = pz - z;
            float d = dx * dx + dz * dz;
            if (d < best) {
                best = d;
                out[0] = px;
                out[1] = v[a + 1] + (v[b + 1] - v[a + 1]) * s;
                out[2] = pz;
            }
        }
    }

    /** Returns the parameter (0..1) of the point of segment {@code ab} closest to {@code p}. */
    public static float segmentParam2D(float px, float pz, float ax, float az, float bx, float bz) {
        float dx = bx - ax;
        float dz = bz - az;
        float d = dx * dx + dz * dz;
        if (d <= 0) {
            return 0;
        }
        return Math.clamp(((px - ax) * dx + (pz - az) * dz) / d, 0f, 1f);
    }

    /** Returns the squared XZ distance from {@code p} to segment {@code ab}. */
    public static float distPtSegSqr2D(float px, float pz, float ax, float az, float bx, float bz) {
        float s = segmentParam2D(px, pz, ax, az, bx, bz);
        float dx = ax + (bx - ax) * s - px;
        float dz = az + (bz - az) * s - pz;
        return dx * dx + dz * dz;
    }

    /**
     * Clips segment {@code s → e} against convex polygon {@code p} in XZ (Cyrus-Beck). Writes the
     * entry and exit parameters to {@code out[0]} and {@code out[1]} and the entry and exit edge
     * indices (or -1 where the segment starts or ends inside) to {@code edges[0]} and {@code
     * edges[1]}. Returns {@code false} when the segment misses the polygon.
     */
    public static boolean intersectSegmentPoly2D(
            NavTile t, int p, float sx, float sz, float ex, float ez, float[] out, int[] edges) {
        final float eps = 1e-8f;
        int n = t.polyVertCount[p];
        float[] v = t.verts;
        float tmin = 0;
        float tmax = 1;
        int segMin = -1;
        int segMax = -1;
        float dirX = ex - sx;
        float dirZ = ez - sz;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            int vi = t.vertex(p, i);
            int vj = t.vertex(p, j);
            float edgeX = v[vi] - v[vj];
            float edgeZ = v[vi + 2] - v[vj + 2];
            float diffX = sx - v[vj];
            float diffZ = sz - v[vj + 2];
            float num = edgeZ * diffX - edgeX * diffZ;
            float den = dirZ * edgeX - dirX * edgeZ;
            if (Math.abs(den) < eps) {
                if (num < 0) {
                    return false; // parallel and outside this edge
                }
                continue;
            }
            float tt = num / den;
            if (den < 0) {
                if (tt > tmin) { // entering across edge j
                    tmin = tt;
                    segMin = j;
                    if (tmin > tmax) {
                        return false;
                    }
                }
            } else if (tt < tmax) { // leaving across edge j
                tmax = tt;
                segMax = j;
                if (tmax < tmin) {
                    return false;
                }
            }
        }
        out[0] = tmin;
        out[1] = tmax;
        edges[0] = segMin;
        edges[1] = segMax;
        return true;
    }

    /** Writes the XZ centroid (average of the vertices, with height) of polygon {@code p}. */
    public static void polyCenter(NavTile t, int p, float[] out) {
        int n = t.polyVertCount[p];
        float x = 0;
        float y = 0;
        float z = 0;
        for (int i = 0; i < n; i++) {
            int a = t.vertex(p, i);
            x += t.verts[a];
            y += t.verts[a + 1];
            z += t.verts[a + 2];
        }
        out[0] = x / n;
        out[1] = y / n;
        out[2] = z / n;
    }
}
