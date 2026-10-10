package dev.jeddy.math;

/**
 * Immutable 3D vector (value type, DESIGN §8.1). Y is up; the coordinate system is right-handed.
 * Becomes a {@code value record} once JEP 401 is final. In hot loops use {@link MVec3f} scratch
 * objects or {@link Vec3Ops} kernels instead.
 *
 * <p>Thread ownership: immutable.
 *
 * @param x x component
 * @param y y component (up)
 * @param z z component
 */
public record Vec3f(float x, float y, float z) {
    /** (0, 0, 0). */
    public static final Vec3f ZERO = new Vec3f(0, 0, 0);

    /** (1, 1, 1). */
    public static final Vec3f ONE = new Vec3f(1, 1, 1);

    /** (1, 0, 0). */
    public static final Vec3f UNIT_X = new Vec3f(1, 0, 0);

    /** (0, 1, 0), up. */
    public static final Vec3f UNIT_Y = new Vec3f(0, 1, 0);

    /** (0, 0, 1). */
    public static final Vec3f UNIT_Z = new Vec3f(0, 0, 1);

    /** Returns {@code this + o}. */
    public Vec3f add(Vec3f o) {
        return new Vec3f(x + o.x, y + o.y, z + o.z);
    }

    /** Returns {@code this + (x, y, z)}. */
    public Vec3f add(float ox, float oy, float oz) {
        return new Vec3f(x + ox, y + oy, z + oz);
    }

    /** Returns {@code this - o}. */
    public Vec3f sub(Vec3f o) {
        return new Vec3f(x - o.x, y - o.y, z - o.z);
    }

    /** Returns {@code this * s}. */
    public Vec3f mul(float s) {
        return new Vec3f(x * s, y * s, z * s);
    }

    /** Returns the component-wise product. */
    public Vec3f mul(Vec3f o) {
        return new Vec3f(x * o.x, y * o.y, z * o.z);
    }

    /** Returns {@code -this}. */
    public Vec3f negate() {
        return new Vec3f(-x, -y, -z);
    }

    /** Returns the dot product. */
    public float dot(Vec3f o) {
        return x * o.x + y * o.y + z * o.z;
    }

    /** Returns the cross product {@code this × o}. */
    public Vec3f cross(Vec3f o) {
        return new Vec3f(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
    }

    /** Returns the length. */
    public float length() {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    /** Returns the squared length. */
    public float lengthSquared() {
        return x * x + y * y + z * z;
    }

    /** Returns this vector at unit length, or zero if it has no length. */
    public Vec3f normalize() {
        float l = length();
        return l > 0 ? new Vec3f(x / l, y / l, z / l) : ZERO;
    }

    /** Returns the distance to {@code o}. */
    public float distance(Vec3f o) {
        return (float) Math.sqrt(distanceSquared(o));
    }

    /** Returns the squared distance to {@code o}. */
    public float distanceSquared(Vec3f o) {
        float dx = x - o.x;
        float dy = y - o.y;
        float dz = z - o.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Returns {@code this + (o - this) * t}. */
    public Vec3f lerp(Vec3f o, float t) {
        return new Vec3f(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t);
    }

    /** Returns the component-wise minimum. */
    public Vec3f min(Vec3f o) {
        return new Vec3f(Math.min(x, o.x), Math.min(y, o.y), Math.min(z, o.z));
    }

    /** Returns the component-wise maximum. */
    public Vec3f max(Vec3f o) {
        return new Vec3f(Math.max(x, o.x), Math.max(y, o.y), Math.max(z, o.z));
    }

    /** Returns this vector with y set to 0 (projected onto the ground plane). */
    public Vec3f flat() {
        return new Vec3f(x, 0, z);
    }

    /** Returns {@code true} if every component is within {@code epsilon} of {@code o}'s. */
    public boolean approxEquals(Vec3f o, float epsilon) {
        return Math.abs(x - o.x) <= epsilon && Math.abs(y - o.y) <= epsilon && Math.abs(z - o.z) <= epsilon;
    }
}
