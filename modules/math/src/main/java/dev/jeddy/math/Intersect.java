package dev.jeddy.math;

/**
 * Intersection and distance tests on primitive arguments (no objects, no allocation), used by
 * picking, physics queries and AoE targeting (DESIGN §8.3, §11.3).
 *
 * <p>Ray tests return the hit distance {@code t} along the direction, or {@link #MISS} ({@code
 * +∞}). Directions need not be unit length; {@code t} is then in direction units. 2D tests work in
 * the ground plane {@code (x, z)}.
 *
 * <p>Thread ownership: stateless, callable from any thread.
 */
public final class Intersect {
    /** Returned by ray tests that miss. */
    public static final float MISS = Float.POSITIVE_INFINITY;

    private Intersect() {}

    // ---------------------------------------------------------------- rays

    /**
     * Ray against an axis-aligned box (slab method). Returns the entry distance in {@code [0,
     * tMax]} (0 if the origin is inside), or {@link #MISS}.
     */
    public static float rayAabb(
            float ox, float oy, float oz, float dx, float dy, float dz,
            float minX, float minY, float minZ, float maxX, float maxY, float maxZ, float tMax) {
        float ix = 1 / dx;
        float iy = 1 / dy;
        float iz = 1 / dz;
        float t1 = (minX - ox) * ix;
        float t2 = (maxX - ox) * ix;
        float tNear = Math.min(t1, t2);
        float tFar = Math.max(t1, t2);
        t1 = (minY - oy) * iy;
        t2 = (maxY - oy) * iy;
        tNear = Math.max(tNear, Math.min(t1, t2));
        tFar = Math.min(tFar, Math.max(t1, t2));
        t1 = (minZ - oz) * iz;
        t2 = (maxZ - oz) * iz;
        tNear = Math.max(tNear, Math.min(t1, t2));
        tFar = Math.min(tFar, Math.max(t1, t2));
        // NaN (origin on a slab plane with zero direction) compares false, which counts as a miss.
        if (!(tFar >= Math.max(tNear, 0f)) || tNear > tMax) {
            return MISS;
        }
        return Math.max(tNear, 0f);
    }

    /**
     * Watertight ray–triangle test (Woop, Benthin and Wald 2013): no gaps between neighbouring
     * triangles, both sides hit. Returns {@code t} in {@code [0, tMax]} or {@link #MISS}. If
     * {@code barycentric} is not {@code null}, writes the weights of v0, v1, v2 there.
     */
    public static float rayTriangle(
            float ox, float oy, float oz, float dx, float dy, float dz,
            float v0x, float v0y, float v0z, float v1x, float v1y, float v1z, float v2x, float v2y, float v2z,
            float tMax, float[] barycentric) {
        float adx = Math.abs(dx);
        float ady = Math.abs(dy);
        float adz = Math.abs(dz);
        int kz = adx > ady ? (adx > adz ? 0 : 2) : (ady > adz ? 1 : 2);
        int kx = kz == 2 ? 0 : kz + 1;
        int ky = kx == 2 ? 0 : kx + 1;
        if (pick(kz, dx, dy, dz) < 0) {
            int t = kx;
            kx = ky;
            ky = t;
        }
        float dkz = pick(kz, dx, dy, dz);
        float sx = pick(kx, dx, dy, dz) / dkz;
        float sy = pick(ky, dx, dy, dz) / dkz;
        float sz = 1 / dkz;

        float ax = v0x - ox, ay = v0y - oy, az = v0z - oz;
        float bx = v1x - ox, by = v1y - oy, bz = v1z - oz;
        float cx = v2x - ox, cy = v2y - oy, cz = v2z - oz;
        float akz = pick(kz, ax, ay, az);
        float bkz = pick(kz, bx, by, bz);
        float ckz = pick(kz, cx, cy, cz);
        float axx = pick(kx, ax, ay, az) - sx * akz;
        float ayy = pick(ky, ax, ay, az) - sy * akz;
        float bxx = pick(kx, bx, by, bz) - sx * bkz;
        float byy = pick(ky, bx, by, bz) - sy * bkz;
        float cxx = pick(kx, cx, cy, cz) - sx * ckz;
        float cyy = pick(ky, cx, cy, cz) - sy * ckz;

        float u = cxx * byy - cyy * bxx;
        float v = axx * cyy - ayy * cxx;
        float w = bxx * ayy - byy * axx;
        if (u == 0 || v == 0 || w == 0) {
            u = (float) ((double) cxx * byy - (double) cyy * bxx);
            v = (float) ((double) axx * cyy - (double) ayy * cxx);
            w = (float) ((double) bxx * ayy - (double) byy * axx);
        }
        if ((u < 0 || v < 0 || w < 0) && (u > 0 || v > 0 || w > 0)) {
            return MISS;
        }
        float det = u + v + w;
        if (det == 0) {
            return MISS;
        }
        float t = (u * sz * akz + v * sz * bkz + w * sz * ckz) / det;
        if (!(t >= 0) || t > tMax) {
            return MISS;
        }
        if (barycentric != null) {
            float inv = 1 / det;
            barycentric[0] = u * inv;
            barycentric[1] = v * inv;
            barycentric[2] = w * inv;
        }
        return t;
    }

    private static float pick(int axis, float x, float y, float z) {
        return axis == 0 ? x : axis == 1 ? y : z;
    }

    /** Ray against a sphere. Returns the first {@code t ≥ 0} (0 if inside) or {@link #MISS}. */
    public static float raySphere(
            float ox, float oy, float oz, float dx, float dy, float dz, float cx, float cy, float cz, float r) {
        float mx = ox - cx;
        float my = oy - cy;
        float mz = oz - cz;
        float c = mx * mx + my * my + mz * mz - r * r;
        if (c <= 0) {
            return 0;
        }
        float a = dx * dx + dy * dy + dz * dz;
        float b = mx * dx + my * dy + mz * dz;
        if (b > 0) {
            return MISS;
        }
        float disc = b * b - a * c;
        if (disc < 0) {
            return MISS;
        }
        return (float) ((-b - Math.sqrt(disc)) / a);
    }

    /**
     * Ray against a vertical cylinder (the shape of units, DESIGN §11.2): axis along Y through
     * {@code (cx, cz)}, from {@code yMin} to {@code yMax}. Returns the first {@code t ≥ 0} or
     * {@link #MISS}.
     */
    public static float rayVerticalCylinder(
            float ox, float oy, float oz, float dx, float dy, float dz,
            float cx, float cz, float r, float yMin, float yMax) {
        float mx = ox - cx;
        float mz = oz - cz;
        float r2 = r * r;
        boolean insideCircle = mx * mx + mz * mz <= r2;
        if (insideCircle && oy >= yMin && oy <= yMax) {
            return 0;
        }
        float best = MISS;
        // Side surface.
        float a = dx * dx + dz * dz;
        if (a > 0) {
            float b = mx * dx + mz * dz;
            float c = mx * mx + mz * mz - r2;
            float disc = b * b - a * c;
            if (disc >= 0) {
                float t = (float) ((-b - Math.sqrt(disc)) / a);
                if (t >= 0) {
                    float y = oy + dy * t;
                    if (y >= yMin && y <= yMax) {
                        best = t;
                    }
                }
            }
        }
        // Caps.
        if (dy != 0) {
            for (int k = 0; k < 2; k++) {
                float capY = k == 0 ? yMin : yMax;
                float t = (capY - oy) / dy;
                if (t >= 0 && t < best) {
                    float px = mx + dx * t;
                    float pz = mz + dz * t;
                    if (px * px + pz * pz <= r2) {
                        best = t;
                    }
                }
            }
        }
        return best;
    }

    /**
     * Ray against a capsule (segment {@code a}–{@code b} with radius {@code r}). Returns the first
     * {@code t ≥ 0} or {@link #MISS}.
     */
    public static float rayCapsule(
            float ox, float oy, float oz, float dx, float dy, float dz,
            float ax, float ay, float az, float bx, float by, float bz, float r) {
        if (pointSegmentDistanceSquared(ox, oy, oz, ax, ay, az, bx, by, bz) <= r * r) {
            return 0;
        }
        float dl = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float ux = dx / dl, uy = dy / dl, uz = dz / dl; // unit direction; convert t back at the end
        float bax = bx - ax, bay = by - ay, baz = bz - az;
        float oax = ox - ax, oay = oy - ay, oaz = oz - az;
        float baba = bax * bax + bay * bay + baz * baz;
        float bard = bax * ux + bay * uy + baz * uz;
        float baoa = bax * oax + bay * oay + baz * oaz;
        float rdoa = ux * oax + uy * oay + uz * oaz;
        float oaoa = oax * oax + oay * oay + oaz * oaz;
        float a = baba - bard * bard;
        float b = baba * rdoa - baoa * bard;
        float c = baba * oaoa - baoa * baoa - r * r * baba;
        float h = b * b - a * c;
        if (h >= 0 && a != 0) {
            float t = (float) ((-b - Math.sqrt(h)) / a);
            float y = baoa + t * bard;
            if (y > 0 && y < baba && t >= 0) {
                return t / dl;
            }
        }
        // Hemispherical caps.
        float best = MISS;
        float ta = raySphere(ox, oy, oz, ux, uy, uz, ax, ay, az, r);
        float tb = raySphere(ox, oy, oz, ux, uy, uz, bx, by, bz, r);
        best = Math.min(ta, tb);
        return best == MISS ? MISS : best / dl;
    }

    /**
     * Ray against the plane {@code n · p + d = 0}. Returns {@code t ≥ 0} or {@link #MISS} if
     * parallel or behind.
     */
    public static float rayPlane(
            float ox, float oy, float oz, float dx, float dy, float dz, float nx, float ny, float nz, float d) {
        float denom = nx * dx + ny * dy + nz * dz;
        if (Math.abs(denom) < 1e-12f) {
            return MISS;
        }
        float t = -(nx * ox + ny * oy + nz * oz + d) / denom;
        return t >= 0 ? t : MISS;
    }

    // ---------------------------------------------------------------- 3D overlap and distance

    /** Returns the squared distance from point {@code p} to segment {@code a}–{@code b}. */
    public static float pointSegmentDistanceSquared(
            float px, float py, float pz, float ax, float ay, float az, float bx, float by, float bz) {
        float t = closestPointOnSegment(px, py, pz, ax, ay, az, bx, by, bz);
        float cx = ax + (bx - ax) * t - px;
        float cy = ay + (by - ay) * t - py;
        float cz = az + (bz - az) * t - pz;
        return cx * cx + cy * cy + cz * cz;
    }

    /** Returns the parameter in {@code [0, 1]} of the point on {@code a}–{@code b} closest to p. */
    public static float closestPointOnSegment(
            float px, float py, float pz, float ax, float ay, float az, float bx, float by, float bz) {
        float abx = bx - ax;
        float aby = by - ay;
        float abz = bz - az;
        float len2 = abx * abx + aby * aby + abz * abz;
        if (len2 == 0) {
            return 0;
        }
        float t = ((px - ax) * abx + (py - ay) * aby + (pz - az) * abz) / len2;
        return Math.max(0, Math.min(1, t));
    }

    /** Returns {@code true} if a sphere and a box overlap. */
    public static boolean sphereAabb(
            float cx, float cy, float cz, float r,
            float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        float dx = cx - Math.max(minX, Math.min(cx, maxX));
        float dy = cy - Math.max(minY, Math.min(cy, maxY));
        float dz = cz - Math.max(minZ, Math.min(cz, maxZ));
        return dx * dx + dy * dy + dz * dz <= r * r;
    }

    /** Returns {@code true} if two boxes overlap (touching counts). */
    public static boolean aabbAabb(
            float aMinX, float aMinY, float aMinZ, float aMaxX, float aMaxY, float aMaxZ,
            float bMinX, float bMinY, float bMinZ, float bMaxX, float bMaxY, float bMaxZ) {
        return aMinX <= bMaxX && aMaxX >= bMinX && aMinY <= bMaxY && aMaxY >= bMinY && aMinZ <= bMaxZ
                && aMaxZ >= bMinZ;
    }

    /**
     * Returns {@code true} if a sphere touches a finite cone (cone spells). The cone starts at
     * {@code apex}, opens along unit {@code axis} with half-angle given as cosine and sine, and is
     * {@code range} long.
     */
    public static boolean coneSphere(
            float apexX, float apexY, float apexZ, float axisX, float axisY, float axisZ,
            float cosHalf, float sinHalf, float range, float cx, float cy, float cz, float r) {
        float vx = cx - apexX;
        float vy = cy - apexY;
        float vz = cz - apexZ;
        float lenSq = vx * vx + vy * vy + vz * vz;
        float along = vx * axisX + vy * axisY + vz * axisZ;
        float perp = (float) Math.sqrt(Math.max(0, lenSq - along * along));
        float closest = cosHalf * perp - along * sinHalf;
        return !(closest > r || along > range + r || along < -r);
    }

    // ---------------------------------------------------------------- 2D (ground plane x, z)

    /** Returns the squared distance from {@code p} to segment {@code a}–{@code b} in 2D. */
    public static float pointSegmentDistanceSquared2(float px, float pz, float ax, float az, float bx, float bz) {
        float abx = bx - ax;
        float abz = bz - az;
        float len2 = abx * abx + abz * abz;
        float t = len2 == 0 ? 0 : ((px - ax) * abx + (pz - az) * abz) / len2;
        t = Math.max(0, Math.min(1, t));
        float dx = ax + abx * t - px;
        float dz = az + abz * t - pz;
        return dx * dx + dz * dz;
    }

    /** Returns {@code true} if segment {@code a}–{@code b} passes within {@code r} of {@code c}. */
    public static boolean segmentCircle(float ax, float az, float bx, float bz, float cx, float cz, float r) {
        return pointSegmentDistanceSquared2(cx, cz, ax, az, bx, bz) <= r * r;
    }

    /** Returns {@code true} if two circles overlap. */
    public static boolean circleCircle(float ax, float az, float ar, float bx, float bz, float br) {
        float dx = ax - bx;
        float dz = az - bz;
        float r = ar + br;
        return dx * dx + dz * dz <= r * r;
    }

    /**
     * Returns {@code true} if a circle touches a circular sector (a cone spell seen from above):
     * apex {@code a}, unit direction {@code dir}, half-angle as cosine/sine, radius {@code range}.
     */
    public static boolean sectorCircle(
            float ax, float az, float dirX, float dirZ, float cosHalf, float sinHalf, float range,
            float cx, float cz, float r) {
        return coneSphere(ax, 0, az, dirX, 0, dirZ, cosHalf, sinHalf, range, cx, 0, cz, r);
    }

    /**
     * Returns {@code true} if a circle touches an oriented rectangle (a line spell): it starts at
     * {@code a}, extends {@code length} along unit {@code dir} and {@code halfWidth} to each side.
     */
    public static boolean rectCircle(
            float ax, float az, float dirX, float dirZ, float length, float halfWidth, float cx, float cz, float r) {
        float vx = cx - ax;
        float vz = cz - az;
        float along = vx * dirX + vz * dirZ;
        float side = vx * -dirZ + vz * dirX;
        float qx = along - Math.max(0, Math.min(length, along));
        float qz = side - Math.max(-halfWidth, Math.min(halfWidth, side));
        return qx * qx + qz * qz <= r * r;
    }

    /**
     * Intersects segments {@code p1}–{@code p2} and {@code q1}–{@code q2}. Returns the parameter on
     * the first segment in {@code [0, 1]}, or NaN if they don't cross (parallel segments never
     * cross).
     */
    public static float segmentSegment2(
            float p1x, float p1z, float p2x, float p2z, float q1x, float q1z, float q2x, float q2z) {
        float rx = p2x - p1x;
        float rz = p2z - p1z;
        float sx = q2x - q1x;
        float sz = q2z - q1z;
        float denom = rx * sz - rz * sx;
        if (denom == 0) {
            return Float.NaN;
        }
        float qpx = q1x - p1x;
        float qpz = q1z - p1z;
        float t = (qpx * sz - qpz * sx) / denom;
        float u = (qpx * rz - qpz * rx) / denom;
        return t >= 0 && t <= 1 && u >= 0 && u <= 1 ? t : Float.NaN;
    }
}
