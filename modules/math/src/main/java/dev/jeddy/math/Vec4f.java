package dev.jeddy.math;

/**
 * Immutable 4D vector (value type). Used for homogeneous points, planes and colors.
 *
 * <p>Thread ownership: immutable.
 *
 * @param x x component
 * @param y y component
 * @param z z component
 * @param w w component
 */
public record Vec4f(float x, float y, float z, float w) {
    /** (0, 0, 0, 0). */
    public static final Vec4f ZERO = new Vec4f(0, 0, 0, 0);

    /** Creates {@code (v, w)}. */
    public Vec4f(Vec3f v, float w) {
        this(v.x(), v.y(), v.z(), w);
    }

    /** Returns {@code this + o}. */
    public Vec4f add(Vec4f o) {
        return new Vec4f(x + o.x, y + o.y, z + o.z, w + o.w);
    }

    /** Returns {@code this - o}. */
    public Vec4f sub(Vec4f o) {
        return new Vec4f(x - o.x, y - o.y, z - o.z, w - o.w);
    }

    /** Returns {@code this * s}. */
    public Vec4f mul(float s) {
        return new Vec4f(x * s, y * s, z * s, w * s);
    }

    /** Returns the dot product. */
    public float dot(Vec4f o) {
        return x * o.x + y * o.y + z * o.z + w * o.w;
    }

    /** Returns the length. */
    public float length() {
        return (float) Math.sqrt(dot(this));
    }

    /** Returns {@code (x, y, z)}. */
    public Vec3f xyz() {
        return new Vec3f(x, y, z);
    }

    /** Returns {@code (x, y, z) / w}. */
    public Vec3f perspectiveDivide() {
        return new Vec3f(x / w, y / w, z / w);
    }

    /** Returns {@code this + (o - this) * t}. */
    public Vec4f lerp(Vec4f o, float t) {
        return new Vec4f(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t, w + (o.w - w) * t);
    }
}
