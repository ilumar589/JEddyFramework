package dev.jeddy.math;

/**
 * Immutable rotation quaternion {@code (x, y, z, w)}, Hamilton convention: {@code a.mul(b)}
 * applies {@code b} first, then {@code a}. Value type (DESIGN §8.1).
 *
 * <p>Thread ownership: immutable.
 *
 * @param x vector part x
 * @param y vector part y
 * @param z vector part z
 * @param w scalar part
 */
public record Quatf(float x, float y, float z, float w) {
    /** The identity rotation. */
    public static final Quatf IDENTITY = new Quatf(0, 0, 0, 1);

    /** Returns the rotation of {@code radians} around the unit {@code axis}. */
    public static Quatf fromAxisAngle(Vec3f axis, float radians) {
        float h = radians * 0.5f;
        float s = (float) Math.sin(h);
        return new Quatf(axis.x() * s, axis.y() * s, axis.z() * s, (float) Math.cos(h));
    }

    /** Returns a rotation around Y (yaw), the common case for characters and cameras. */
    public static Quatf fromYaw(float radians) {
        float h = radians * 0.5f;
        return new Quatf(0, (float) Math.sin(h), 0, (float) Math.cos(h));
    }

    /**
     * Returns the rotation from Euler angles applied as yaw (Y), then pitch (X), then roll (Z),
     * i.e. {@code q = qYaw * qPitch * qRoll}.
     */
    public static Quatf fromYawPitchRoll(float yaw, float pitch, float roll) {
        return fromAxisAngle(Vec3f.UNIT_Y, yaw)
                .mul(fromAxisAngle(Vec3f.UNIT_X, pitch))
                .mul(fromAxisAngle(Vec3f.UNIT_Z, roll));
    }

    /**
     * Returns the rotation that turns -Z (the forward axis) towards {@code forward}, keeping
     * {@code up} as close to +Y of the result as possible.
     */
    public static Quatf lookRotation(Vec3f forward, Vec3f up) {
        Vec3f f = forward.normalize();
        Vec3f r = f.cross(up).normalize();
        if (r.lengthSquared() == 0) {
            r = f.cross(Math.abs(f.y()) < 0.99f ? Vec3f.UNIT_Y : Vec3f.UNIT_X).normalize();
        }
        Vec3f u = r.cross(f);
        // Columns of the rotation matrix: right, up, -forward
        return fromMatrix(r.x(), u.x(), -f.x(), r.y(), u.y(), -f.y(), r.z(), u.z(), -f.z());
    }

    /** Returns the quaternion of a rotation matrix given row by row ({@code mRC}). */
    public static Quatf fromMatrix(
            float m00, float m01, float m02, float m10, float m11, float m12, float m20, float m21, float m22) {
        float trace = m00 + m11 + m22;
        float x;
        float y;
        float z;
        float w;
        if (trace > 0) {
            float s = (float) Math.sqrt(trace + 1.0) * 2;
            w = 0.25f * s;
            x = (m21 - m12) / s;
            y = (m02 - m20) / s;
            z = (m10 - m01) / s;
        } else if (m00 > m11 && m00 > m22) {
            float s = (float) Math.sqrt(1.0 + m00 - m11 - m22) * 2;
            w = (m21 - m12) / s;
            x = 0.25f * s;
            y = (m01 + m10) / s;
            z = (m02 + m20) / s;
        } else if (m11 > m22) {
            float s = (float) Math.sqrt(1.0 + m11 - m00 - m22) * 2;
            w = (m02 - m20) / s;
            x = (m01 + m10) / s;
            y = 0.25f * s;
            z = (m12 + m21) / s;
        } else {
            float s = (float) Math.sqrt(1.0 + m22 - m00 - m11) * 2;
            w = (m10 - m01) / s;
            x = (m02 + m20) / s;
            y = (m12 + m21) / s;
            z = 0.25f * s;
        }
        return new Quatf(x, y, z, w).normalize();
    }

    /** Returns {@code this * o} (rotate by {@code o}, then by {@code this}). */
    public Quatf mul(Quatf o) {
        return new Quatf(
                w * o.x + x * o.w + y * o.z - z * o.y,
                w * o.y - x * o.z + y * o.w + z * o.x,
                w * o.z + x * o.y - y * o.x + z * o.w,
                w * o.w - x * o.x - y * o.y - z * o.z);
    }

    /** Returns the conjugate (the inverse of a unit quaternion). */
    public Quatf conjugate() {
        return new Quatf(-x, -y, -z, w);
    }

    /** Returns the inverse. */
    public Quatf inverse() {
        float n = lengthSquared();
        return new Quatf(-x / n, -y / n, -z / n, w / n);
    }

    /** Returns the dot product. */
    public float dot(Quatf o) {
        return x * o.x + y * o.y + z * o.z + w * o.w;
    }

    /** Returns the squared norm. */
    public float lengthSquared() {
        return x * x + y * y + z * z + w * w;
    }

    /** Returns this quaternion at unit length (identity if it is zero). */
    public Quatf normalize() {
        float n = (float) Math.sqrt(lengthSquared());
        return n > 0 ? new Quatf(x / n, y / n, z / n, w / n) : IDENTITY;
    }

    /** Rotates {@code v} by this unit quaternion. */
    public Vec3f rotate(Vec3f v) {
        // v' = v + 2w(q×v) + 2q×(q×v)
        float tx = 2 * (y * v.z() - z * v.y());
        float ty = 2 * (z * v.x() - x * v.z());
        float tz = 2 * (x * v.y() - y * v.x());
        return new Vec3f(
                v.x() + w * tx + (y * tz - z * ty),
                v.y() + w * ty + (z * tx - x * tz),
                v.z() + w * tz + (x * ty - y * tx));
    }

    /** Spherical interpolation along the shortest arc. */
    public Quatf slerp(Quatf o, float t) {
        float d = dot(o);
        float ox = o.x;
        float oy = o.y;
        float oz = o.z;
        float ow = o.w;
        if (d < 0) {
            d = -d;
            ox = -ox;
            oy = -oy;
            oz = -oz;
            ow = -ow;
        }
        float a;
        float b;
        if (d > 0.9995f) {
            a = 1 - t;
            b = t;
        } else {
            double theta = Math.acos(d);
            double sin = Math.sin(theta);
            a = (float) (Math.sin((1 - t) * theta) / sin);
            b = (float) (Math.sin(t * theta) / sin);
        }
        return new Quatf(a * x + b * ox, a * y + b * oy, a * z + b * oz, a * w + b * ow).normalize();
    }

    /** Normalized linear interpolation along the shortest arc (cheaper than slerp). */
    public Quatf nlerp(Quatf o, float t) {
        float a = 1 - t;
        float b = dot(o) < 0 ? -t : t;
        return new Quatf(x * a + o.x * b, y * a + o.y * b, z * a + o.z * b, w * a + o.w * b).normalize();
    }

    /** Returns the rotation angle in radians, in {@code [0, π]}. */
    public float angle() {
        return (float) (2 * Math.acos(Math.min(1, Math.abs(w))));
    }

    /** Returns the rotated forward axis (-Z). */
    public Vec3f forward() {
        return rotate(new Vec3f(0, 0, -1));
    }

    /** Returns {@code true} if both represent the same rotation within {@code epsilon}. */
    public boolean approxEquals(Quatf o, float epsilon) {
        return 1 - Math.abs(dot(o)) <= epsilon;
    }
}
