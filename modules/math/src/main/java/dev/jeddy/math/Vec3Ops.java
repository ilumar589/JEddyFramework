package dev.jeddy.math;

/**
 * Vector kernels on struct-of-arrays data ({@code float[] x, y, z}). Simple counted loops so C2 can
 * vectorize them. Allocation-free.
 *
 * <p>Thread ownership: stateless, callable from any thread.
 */
public final class Vec3Ops {
    private Vec3Ops() {}

    /** {@code p[i] += v[i] * dt} for {@code [start, end)}: integrate positions by velocities. */
    public static void addScaled(
            float[] px,
            float[] py,
            float[] pz,
            float[] vx,
            float[] vy,
            float[] vz,
            float dt,
            int start,
            int end) {
        for (int i = start; i < end; i++) {
            px[i] += vx[i] * dt;
            py[i] += vy[i] * dt;
            pz[i] += vz[i] * dt;
        }
    }

    /** {@code out[i] = |p[i] - (cx, cy, cz)|²} for {@code [start, end)}. */
    public static void distanceSquared(
            float[] px,
            float[] py,
            float[] pz,
            float cx,
            float cy,
            float cz,
            float[] out,
            int start,
            int end) {
        for (int i = start; i < end; i++) {
            float dx = px[i] - cx;
            float dy = py[i] - cy;
            float dz = pz[i] - cz;
            out[i] = dx * dx + dy * dy + dz * dz;
        }
    }

    /** Normalizes vectors {@code [start, end)} in place; zero vectors stay zero. */
    public static void normalize(float[] x, float[] y, float[] z, int start, int end) {
        for (int i = start; i < end; i++) {
            float l2 = x[i] * x[i] + y[i] * y[i] + z[i] * z[i];
            float inv = l2 > 0 ? (float) (1 / Math.sqrt(l2)) : 0;
            x[i] *= inv;
            y[i] *= inv;
            z[i] *= inv;
        }
    }

    /** {@code out[i] = a[i] · b[i]} for {@code [start, end)}. */
    public static void dot(
            float[] ax,
            float[] ay,
            float[] az,
            float[] bx,
            float[] by,
            float[] bz,
            float[] out,
            int start,
            int end) {
        for (int i = start; i < end; i++) {
            out[i] = ax[i] * bx[i] + ay[i] * by[i] + az[i] * bz[i];
        }
    }

    /** {@code out[i] = a[i] + (b[i] - a[i]) * t} for {@code [start, end)}. */
    public static void lerp(
            float[] ax,
            float[] ay,
            float[] az,
            float[] bx,
            float[] by,
            float[] bz,
            float t,
            float[] ox,
            float[] oy,
            float[] oz,
            int start,
            int end) {
        for (int i = start; i < end; i++) {
            ox[i] = ax[i] + (bx[i] - ax[i]) * t;
            oy[i] = ay[i] + (by[i] - ay[i]) * t;
            oz[i] = az[i] + (bz[i] - az[i]) * t;
        }
    }

    /**
     * Writes the bounds of points {@code [start, end)} to {@code out[0..5]} as {@code minX, minY,
     * minZ, maxX, maxY, maxZ}. Empty ranges produce an inverted (empty) box.
     */
    public static void bounds(float[] x, float[] y, float[] z, int start, int end, float[] out) {
        float minX = Float.POSITIVE_INFINITY,
                minY = Float.POSITIVE_INFINITY,
                minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY,
                maxY = Float.NEGATIVE_INFINITY,
                maxZ = Float.NEGATIVE_INFINITY;
        for (int i = start; i < end; i++) {
            minX = Math.min(minX, x[i]);
            minY = Math.min(minY, y[i]);
            minZ = Math.min(minZ, z[i]);
            maxX = Math.max(maxX, x[i]);
            maxY = Math.max(maxY, y[i]);
            maxZ = Math.max(maxZ, z[i]);
        }
        out[0] = minX;
        out[1] = minY;
        out[2] = minZ;
        out[3] = maxX;
        out[4] = maxY;
        out[5] = maxZ;
    }
}
