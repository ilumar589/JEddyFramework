package dev.jeddy.math;

/**
 * Mutable rotation quaternion for per-frame scratch work without allocation (DESIGN §8.1, layer 2).
 * Same conventions as {@link Quatf}.
 *
 * <p>Thread ownership: single owner thread.
 */
public final class MQuatf {
    /** Vector part x. */
    public float x;

    /** Vector part y. */
    public float y;

    /** Vector part z. */
    public float z;

    /** Scalar part. */
    public float w = 1;

    /** Creates the identity rotation. */
    public MQuatf() {}

    /** Sets the components. */
    public MQuatf set(float nx, float ny, float nz, float nw) {
        x = nx;
        y = ny;
        z = nz;
        w = nw;
        return this;
    }

    /** Copies an immutable quaternion. */
    public MQuatf set(Quatf q) {
        return set(q.x(), q.y(), q.z(), q.w());
    }

    /** Sets to the identity. */
    public MQuatf identity() {
        return set(0, 0, 0, 1);
    }

    /** Sets to a rotation of {@code radians} around unit axis {@code (ax, ay, az)}. */
    public MQuatf fromAxisAngle(float ax, float ay, float az, float radians) {
        float h = radians * 0.5f;
        float s = (float) Math.sin(h);
        return set(ax * s, ay * s, az * s, (float) Math.cos(h));
    }

    /** Sets {@code this = this * o}. */
    public MQuatf mul(MQuatf o) {
        return set(
                w * o.x + x * o.w + y * o.z - z * o.y,
                w * o.y - x * o.z + y * o.w + z * o.x,
                w * o.z + x * o.y - y * o.x + z * o.w,
                w * o.w - x * o.x - y * o.y - z * o.z);
    }

    /** Normalizes in place (identity if zero). */
    public MQuatf normalize() {
        float n = x * x + y * y + z * z + w * w;
        if (n == 0) {
            return identity();
        }
        float inv = (float) (1 / Math.sqrt(n));
        return set(x * inv, y * inv, z * inv, w * inv);
    }

    /** Rotates {@code v} in place. */
    public MVec3f rotate(MVec3f v) {
        float tx = 2 * (y * v.z - z * v.y);
        float ty = 2 * (z * v.x - x * v.z);
        float tz = 2 * (x * v.y - y * v.x);
        return v.set(
                v.x + w * tx + (y * tz - z * ty),
                v.y + w * ty + (z * tx - x * tz),
                v.z + w * tz + (x * ty - y * tx));
    }

    /** Normalized lerp towards {@code o} along the shortest arc. */
    public MQuatf nlerp(MQuatf o, float t) {
        float a = 1 - t;
        float b = (x * o.x + y * o.y + z * o.z + w * o.w) < 0 ? -t : t;
        return set(x * a + o.x * b, y * a + o.y * b, z * a + o.z * b, w * a + o.w * b).normalize();
    }

    /** Returns an immutable copy (allocates). */
    public Quatf toQuatf() {
        return new Quatf(x, y, z, w);
    }
}
