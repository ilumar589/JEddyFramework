package dev.jeddy.math;

/**
 * Quaternion kernels on struct-of-arrays data ({@code float[] qx, qy, qz, qw}). Allocation-free.
 *
 * <p>Thread ownership: stateless, callable from any thread.
 */
public final class QuatOps {
    private QuatOps() {}

    /** Normalizes quaternions {@code [start, end)} in place (identity if zero). */
    public static void normalize(float[] qx, float[] qy, float[] qz, float[] qw, int start, int end) {
        for (int i = start; i < end; i++) {
            float n = qx[i] * qx[i] + qy[i] * qy[i] + qz[i] * qz[i] + qw[i] * qw[i];
            if (n > 0) {
                float inv = (float) (1 / Math.sqrt(n));
                qx[i] *= inv;
                qy[i] *= inv;
                qz[i] *= inv;
                qw[i] *= inv;
            } else {
                qx[i] = 0;
                qy[i] = 0;
                qz[i] = 0;
                qw[i] = 1;
            }
        }
    }

    /**
     * Normalized lerp along the shortest arc: {@code out[i] = nlerp(a[i], b[i], t)} for {@code
     * [start, end)}. Good enough for animation blending between close keys.
     */
    public static void nlerp(
            float[] ax, float[] ay, float[] az, float[] aw,
            float[] bx, float[] by, float[] bz, float[] bw,
            float t,
            float[] ox, float[] oy, float[] oz, float[] ow,
            int start, int end) {
        float s = 1 - t;
        for (int i = start; i < end; i++) {
            float d = ax[i] * bx[i] + ay[i] * by[i] + az[i] * bz[i] + aw[i] * bw[i];
            float tt = d < 0 ? -t : t;
            float x = ax[i] * s + bx[i] * tt;
            float y = ay[i] * s + by[i] * tt;
            float z = az[i] * s + bz[i] * tt;
            float w = aw[i] * s + bw[i] * tt;
            float inv = (float) (1 / Math.sqrt(x * x + y * y + z * z + w * w));
            ox[i] = x * inv;
            oy[i] = y * inv;
            oz[i] = z * inv;
            ow[i] = w * inv;
        }
    }

    /**
     * Rotates struct-of-arrays vectors {@code [start, end)} in place, each by its own unit
     * quaternion.
     */
    public static void rotate(
            float[] qx, float[] qy, float[] qz, float[] qw, float[] vx, float[] vy, float[] vz, int start, int end) {
        for (int i = start; i < end; i++) {
            float x = qx[i];
            float y = qy[i];
            float z = qz[i];
            float w = qw[i];
            float px = vx[i];
            float py = vy[i];
            float pz = vz[i];
            float tx = 2 * (y * pz - z * py);
            float ty = 2 * (z * px - x * pz);
            float tz = 2 * (x * py - y * px);
            vx[i] = px + w * tx + (y * tz - z * ty);
            vy[i] = py + w * ty + (z * tx - x * tz);
            vz[i] = pz + w * tz + (x * ty - y * tx);
        }
    }

    /**
     * Writes the yaw rotation {@code (0, sin(a/2), 0, cos(a/2))} for each angle in {@code [start,
     * end)}, the common case for characters turning on the ground.
     */
    public static void fromYaw(float[] radians, float[] qx, float[] qy, float[] qz, float[] qw, int start, int end) {
        for (int i = start; i < end; i++) {
            float h = radians[i] * 0.5f;
            qx[i] = 0;
            qy[i] = (float) Math.sin(h);
            qz[i] = 0;
            qw[i] = (float) Math.cos(h);
        }
    }
}
