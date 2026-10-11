package dev.jeddy.editor;

import dev.jeddy.math.Intersect;

/**
 * The geometry behind the gizmos (DESIGN §19, learn chapter 14), as pure functions on floats so the
 * tests can hand them rays and check the drags: the parameter of the point on an axis closest to
 * the mouse ray, the hit of the ray on a handle's plane, the signed angle a rotation ring has
 * turned, snapping, screen projection, and the few quaternion operations a rotation needs.
 *
 * <p>Conventions follow the math module: right-handed, Y up, quaternions {@code (x, y, z, w)},
 * radians. Rays have a unit direction.
 *
 * <p>Thread ownership: any (stateless).
 */
public final class GizmoMath {
    private GizmoMath() {}

    /**
     * Returns the parameter {@code t} of the point {@code c + t a} on the axis line through {@code
     * c} along unit {@code a} that is closest to the ray {@code o + s d}, or {@code fallback} when
     * the two are (nearly) parallel.
     *
     * <p>Minimizing {@code |w + s d - t a|²} with {@code w = o - c} over {@code s} and {@code t}
     * gives two linear equations, {@code w·d + s - t (a·d) = 0} and {@code w·a + s (a·d) - t = 0};
     * eliminating {@code s} leaves {@code t = (w·a - (a·d)(w·d)) / (1 - (a·d)²)}. The denominator
     * is {@code sin²} of the angle between the ray and the axis: looking straight down an axis
     * makes its handle useless, which is why the gizmo hides it then.
     */
    public static float closestAxisParam(
            float ox,
            float oy,
            float oz,
            float dx,
            float dy,
            float dz,
            float cx,
            float cy,
            float cz,
            float ax,
            float ay,
            float az,
            float fallback) {
        float wx = ox - cx;
        float wy = oy - cy;
        float wz = oz - cz;
        float b = ax * dx + ay * dy + az * dz;
        float denom = 1 - b * b;
        if (denom < 1e-4f) {
            return fallback;
        }
        float wa = wx * ax + wy * ay + wz * az;
        float wd = wx * dx + wy * dy + wz * dz;
        return (wa - b * wd) / denom;
    }

    /**
     * Intersects the ray with the plane through {@code c} with unit normal {@code n} and writes the
     * point to {@code out[o..o+2]}. Returns {@code false} (and leaves {@code out}) when the ray is
     * parallel to the plane or hits it behind the origin.
     */
    public static boolean rayPlanePoint(
            float ox,
            float oy,
            float oz,
            float dx,
            float dy,
            float dz,
            float cx,
            float cy,
            float cz,
            float nx,
            float ny,
            float nz,
            float[] out,
            int o) {
        float d = -(nx * cx + ny * cy + nz * cz);
        float t = Intersect.rayPlane(ox, oy, oz, dx, dy, dz, nx, ny, nz, d);
        if (t == Intersect.MISS) {
            return false;
        }
        out[o] = ox + dx * t;
        out[o + 1] = oy + dy * t;
        out[o + 2] = oz + dz * t;
        return true;
    }

    /**
     * Returns the signed angle in radians from {@code v0} to {@code v1} around unit axis {@code a}
     * (both vectors lie in the plane perpendicular to it; a positive angle is a right-handed turn
     * about {@code a}): {@code atan2((v0 × v1) · a, v0 · v1)}.
     */
    public static float signedAngle(
            float v0x,
            float v0y,
            float v0z,
            float v1x,
            float v1y,
            float v1z,
            float ax,
            float ay,
            float az) {
        float cx = v0y * v1z - v0z * v1y;
        float cy = v0z * v1x - v0x * v1z;
        float cz = v0x * v1y - v0y * v1x;
        float sin = cx * ax + cy * ay + cz * az;
        float cos = v0x * v1x + v0y * v1y + v0z * v1z;
        return (float) Math.atan2(sin, cos);
    }

    /** Rounds {@code v} to the nearest multiple of {@code step} ({@code step <= 0}: unchanged). */
    public static float snap(float v, float step) {
        return step > 0 ? Math.round(v / step) * step : v;
    }

    /**
     * Projects world point {@code (x, y, z)} through the column-major {@code viewProjection} into
     * window pixels of a region at {@code (rx, ry)} of size {@code (rw, rh)} (y down), writing
     * {@code out[o], out[o+1]} and the NDC depth to {@code out[o+2]}. Returns {@code false} when
     * the point is behind the camera (then {@code out} is unusable).
     */
    public static boolean project(
            float[] viewProjection,
            float x,
            float y,
            float z,
            float rx,
            float ry,
            float rw,
            float rh,
            float[] out,
            int o) {
        float[] m = viewProjection;
        float px = m[0] * x + m[4] * y + m[8] * z + m[12];
        float py = m[1] * x + m[5] * y + m[9] * z + m[13];
        float pz = m[2] * x + m[6] * y + m[10] * z + m[14];
        float pw = m[3] * x + m[7] * y + m[11] * z + m[15];
        if (pw <= 1e-6f) {
            return false;
        }
        float iw = 1 / pw;
        out[o] = rx + (px * iw * 0.5f + 0.5f) * rw;
        out[o + 1] = ry + (0.5f - py * iw * 0.5f) * rh;
        out[o + 2] = pz * iw;
        return true;
    }

    /** Returns the distance from pixel {@code p} to segment {@code a}–{@code b} on screen. */
    public static float pointSegmentDistance(
            float px, float py, float ax, float ay, float bx, float by) {
        float abx = bx - ax;
        float aby = by - ay;
        float len2 = abx * abx + aby * aby;
        float t = len2 == 0 ? 0 : ((px - ax) * abx + (py - ay) * aby) / len2;
        t = Math.max(0, Math.min(1, t));
        float cx = ax + abx * t - px;
        float cy = ay + aby * t - py;
        return (float) Math.sqrt(cx * cx + cy * cy);
    }

    /**
     * Returns how many meters one pixel covers at distance {@code depth} from the eye for a
     * perspective camera of vertical field of view {@code fovY} on a viewport {@code heightPx}
     * tall: the view frustum is {@code 2 depth tan(fovY / 2)} meters tall there.
     */
    public static float metersPerPixel(float fovY, float depth, float heightPx) {
        return 2 * depth * (float) Math.tan(fovY * 0.5) / heightPx;
    }

    // ---- Quaternions (x, y, z, w)
    // ----------------------------------------------------------------

    /**
     * Writes the rotation of {@code angle} radians about unit axis {@code a} to {@code out[o..]}.
     */
    public static void axisAngle(float ax, float ay, float az, float angle, float[] out, int o) {
        float h = angle * 0.5f;
        float s = (float) Math.sin(h);
        out[o] = ax * s;
        out[o + 1] = ay * s;
        out[o + 2] = az * s;
        out[o + 3] = (float) Math.cos(h);
    }

    /** Writes {@code a * b} (apply {@code b} first, then {@code a}) to {@code out[o..]}. */
    public static void mul(float[] a, int ao, float[] b, int bo, float[] out, int o) {
        float ax = a[ao], ay = a[ao + 1], az = a[ao + 2], aw = a[ao + 3];
        float bx = b[bo], by = b[bo + 1], bz = b[bo + 2], bw = b[bo + 3];
        float x = aw * bx + ax * bw + ay * bz - az * by;
        float y = aw * by - ax * bz + ay * bw + az * bx;
        float z = aw * bz + ax * by - ay * bx + az * bw;
        float w = aw * bw - ax * bx - ay * by - az * bz;
        out[o] = x;
        out[o + 1] = y;
        out[o + 2] = z;
        out[o + 3] = w;
    }

    /** Rotates vector {@code v[vo..]} by quaternion {@code q[qo..]} into {@code out[o..]}. */
    public static void rotate(float[] q, int qo, float[] v, int vo, float[] out, int o) {
        float qx = q[qo], qy = q[qo + 1], qz = q[qo + 2], qw = q[qo + 3];
        float vx = v[vo], vy = v[vo + 1], vz = v[vo + 2];
        // t = 2 q × v; v' = v + w t + q × t
        float tx = 2 * (qy * vz - qz * vy);
        float ty = 2 * (qz * vx - qx * vz);
        float tz = 2 * (qx * vy - qy * vx);
        out[o] = vx + qw * tx + (qy * tz - qz * ty);
        out[o + 1] = vy + qw * ty + (qz * tx - qx * tz);
        out[o + 2] = vz + qw * tz + (qx * ty - qy * tx);
    }

    /** Normalizes quaternion {@code q[o..]} in place. */
    public static void normalize(float[] q, int o) {
        float n = q[o] * q[o] + q[o + 1] * q[o + 1] + q[o + 2] * q[o + 2] + q[o + 3] * q[o + 3];
        if (n > 0) {
            float inv = (float) (1 / Math.sqrt(n));
            q[o] *= inv;
            q[o + 1] *= inv;
            q[o + 2] *= inv;
            q[o + 3] *= inv;
        }
    }

    /**
     * Writes the yaw (rotation about Y), pitch (about X) and roll (about Z) in radians of
     * quaternion {@code q[o..]} to {@code out[oo..oo+2]}, for the Inspector's Euler fields. The
     * composition is {@code yaw * pitch * roll} (roll applied first).
     */
    public static void toEuler(float[] q, int o, float[] out, int oo) {
        float x = q[o], y = q[o + 1], z = q[o + 2], w = q[o + 3];
        // Rotation matrix entries of the Y-X-Z decomposition.
        float sinPitch = 2 * (w * x - y * z);
        sinPitch = Math.max(-1, Math.min(1, sinPitch));
        float pitch = (float) Math.asin(sinPitch);
        float yaw;
        float roll;
        if (Math.abs(sinPitch) > 0.9999f) {
            // Gimbal lock: all the turn goes to yaw.
            yaw = (float) Math.atan2(2 * (w * y - x * z), 1 - 2 * (y * y + z * z));
            roll = 0;
        } else {
            yaw = (float) Math.atan2(2 * (w * y + x * z), 1 - 2 * (x * x + y * y));
            roll = (float) Math.atan2(2 * (w * z + x * y), 1 - 2 * (x * x + z * z));
        }
        out[oo] = yaw;
        out[oo + 1] = pitch;
        out[oo + 2] = roll;
    }

    /**
     * Writes the quaternion of {@code yaw * pitch * roll} (see {@link #toEuler}) to {@code out}.
     */
    public static void fromEuler(float yaw, float pitch, float roll, float[] out, int o) {
        float cy = (float) Math.cos(yaw * 0.5), sy = (float) Math.sin(yaw * 0.5);
        float cp = (float) Math.cos(pitch * 0.5), sp = (float) Math.sin(pitch * 0.5);
        float cr = (float) Math.cos(roll * 0.5), sr = (float) Math.sin(roll * 0.5);
        // q = qy(yaw) * qx(pitch) * qz(roll)
        float ax = 0, ay = sy, az = 0, aw = cy; // yaw
        float bx = sp, by = 0, bz = 0, bw = cp; // pitch
        float x = aw * bx + ax * bw + ay * bz - az * by;
        float y = aw * by - ax * bz + ay * bw + az * bx;
        float z = aw * bz + ax * by - ay * bx + az * bw;
        float w = aw * bw - ax * bx - ay * by - az * bz;
        float rx = 0, ry = 0, rz = sr, rw = cr; // roll
        out[o] = w * rx + x * rw + y * rz - z * ry;
        out[o + 1] = w * ry - x * rz + y * rw + z * rx;
        out[o + 2] = w * rz + x * ry - y * rx + z * rw;
        out[o + 3] = w * rw - x * rx - y * ry - z * rz;
    }
}
