package dev.jeddy.math;

/**
 * 2D polygon utilities for navigation and level data (DESIGN §8.3, §11.4). Polygons are packed as
 * {@code [x0, z0, x1, z1, ...]} with {@code n} vertices.
 *
 * <p>Thread ownership: stateless, callable from any thread.
 */
public final class Poly2 {
    private Poly2() {}

    /** Returns the signed area: positive for counter-clockwise winding (x right, z up). */
    public static float signedArea(float[] xz, int n) {
        double sum = 0;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            sum += (double) xz[2 * j] * xz[2 * i + 1] - (double) xz[2 * i] * xz[2 * j + 1];
        }
        return (float) (sum * 0.5);
    }

    /** Returns {@code true} if the polygon is wound counter-clockwise. */
    public static boolean isCounterClockwise(float[] xz, int n) {
        return signedArea(xz, n) > 0;
    }

    /** Returns {@code true} if the polygon is convex (collinear vertices allowed). */
    public static boolean isConvex(float[] xz, int n) {
        int sign = 0;
        for (int i = 0; i < n; i++) {
            float cross = cross(xz, (i + n - 1) % n, i, (i + 1) % n);
            int s = cross > 0 ? 1 : cross < 0 ? -1 : 0;
            if (s != 0) {
                if (sign != 0 && s != sign) {
                    return false;
                }
                sign = s;
            }
        }
        return true;
    }

    /** Returns {@code true} if point {@code (px, pz)} is inside (even-odd rule). */
    public static boolean contains(float[] xz, int n, float px, float pz) {
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            float xi = xz[2 * i], zi = xz[2 * i + 1];
            float xj = xz[2 * j], zj = xz[2 * j + 1];
            if ((zi > pz) != (zj > pz) && px < (xj - xi) * (pz - zi) / (zj - zi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }

    /**
     * Triangulates a simple polygon by ear clipping (O(n²)). Writes vertex-index triples to {@code
     * outTriangles} (room for {@code 3 * (n - 2)}) and returns the number of triangles. Output
     * triangles are counter-clockwise whatever the input winding.
     *
     * @param scratch at least {@code n} ints
     */
    public static int triangulate(float[] xz, int n, int[] outTriangles, int[] scratch) {
        if (n < 3) {
            return 0;
        }
        boolean ccw = isCounterClockwise(xz, n);
        for (int i = 0; i < n; i++) {
            scratch[i] = ccw ? i : n - 1 - i;
        }
        int remaining = n;
        int count = 0;
        int guard = 0;
        int i = 0;
        while (remaining > 3 && guard < remaining * remaining) {
            int prev = scratch[(i + remaining - 1) % remaining];
            int cur = scratch[i % remaining];
            int next = scratch[(i + 1) % remaining];
            if (isEar(xz, scratch, remaining, prev, cur, next)) {
                outTriangles[3 * count] = prev;
                outTriangles[3 * count + 1] = cur;
                outTriangles[3 * count + 2] = next;
                count++;
                int at = i % remaining;
                System.arraycopy(scratch, at + 1, scratch, at, remaining - at - 1);
                remaining--;
                guard = 0;
            } else {
                i++;
                guard++;
            }
        }
        if (remaining == 3) {
            outTriangles[3 * count] = scratch[0];
            outTriangles[3 * count + 1] = scratch[1];
            outTriangles[3 * count + 2] = scratch[2];
            count++;
        }
        return count;
    }

    private static boolean isEar(float[] xz, int[] ring, int remaining, int a, int b, int c) {
        if (cross(xz, a, b, c) <= 0) {
            return false; // reflex or degenerate
        }
        for (int k = 0; k < remaining; k++) {
            int p = ring[k];
            if (p == a || p == b || p == c) {
                continue;
            }
            if (pointInTriangle(xz[2 * p], xz[2 * p + 1], xz, a, b, c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean pointInTriangle(float px, float pz, float[] xz, int a, int b, int c) {
        float d1 = side(px, pz, xz, a, b);
        float d2 = side(px, pz, xz, b, c);
        float d3 = side(px, pz, xz, c, a);
        return d1 >= 0 && d2 >= 0 && d3 >= 0;
    }

    private static float side(float px, float pz, float[] xz, int a, int b) {
        return (xz[2 * b] - xz[2 * a]) * (pz - xz[2 * a + 1]) - (xz[2 * b + 1] - xz[2 * a + 1]) * (px - xz[2 * a]);
    }

    private static float cross(float[] xz, int a, int b, int c) {
        float abx = xz[2 * b] - xz[2 * a];
        float abz = xz[2 * b + 1] - xz[2 * a + 1];
        float bcx = xz[2 * c] - xz[2 * b];
        float bcz = xz[2 * c + 1] - xz[2 * b + 1];
        return abx * bcz - abz * bcx;
    }

    /**
     * Offsets a convex counter-clockwise polygon outward by {@code distance} (negative shrinks),
     * moving each vertex along its miter. Writes {@code n} vertices to {@code out}.
     */
    public static void offsetConvex(float[] xz, int n, float distance, float[] out) {
        for (int i = 0; i < n; i++) {
            int p = (i + n - 1) % n;
            int q = (i + 1) % n;
            // Outward normals of the two edges at vertex i (CCW: outward = (dz, -dx)).
            float e0x = xz[2 * i] - xz[2 * p];
            float e0z = xz[2 * i + 1] - xz[2 * p + 1];
            float e1x = xz[2 * q] - xz[2 * i];
            float e1z = xz[2 * q + 1] - xz[2 * i + 1];
            float l0 = (float) Math.sqrt(e0x * e0x + e0z * e0z);
            float l1 = (float) Math.sqrt(e1x * e1x + e1z * e1z);
            float n0x = e0z / l0, n0z = -e0x / l0;
            float n1x = e1z / l1, n1z = -e1x / l1;
            float mx = n0x + n1x;
            float mz = n0z + n1z;
            float dot = mx * n0x + mz * n0z;
            float scale = dot > 1e-6f ? distance / dot : distance;
            out[2 * i] = xz[2 * i] + mx * scale;
            out[2 * i + 1] = xz[2 * i + 1] + mz * scale;
        }
    }
}
