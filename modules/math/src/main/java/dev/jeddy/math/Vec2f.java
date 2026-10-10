package dev.jeddy.math;

/**
 * Immutable 2D vector (value type, DESIGN §8.1). Becomes a {@code value record} once JEP 401 is
 * final. For per-element hot loops use {@link Vec3Ops}-style kernels on arrays instead.
 *
 * <p>Thread ownership: immutable.
 *
 * @param x x component
 * @param y y component
 */
public record Vec2f(float x, float y) {
    /** (0, 0). */
    public static final Vec2f ZERO = new Vec2f(0, 0);

    /** (1, 1). */
    public static final Vec2f ONE = new Vec2f(1, 1);

    /** Returns {@code this + o}. */
    public Vec2f add(Vec2f o) {
        return new Vec2f(x + o.x, y + o.y);
    }

    /** Returns {@code this - o}. */
    public Vec2f sub(Vec2f o) {
        return new Vec2f(x - o.x, y - o.y);
    }

    /** Returns {@code this * s}. */
    public Vec2f mul(float s) {
        return new Vec2f(x * s, y * s);
    }

    /** Returns the dot product. */
    public float dot(Vec2f o) {
        return x * o.x + y * o.y;
    }

    /** Returns the z component of the 3D cross product (twice the signed triangle area). */
    public float cross(Vec2f o) {
        return x * o.y - y * o.x;
    }

    /** Returns the length. */
    public float length() {
        return (float) Math.sqrt(x * x + y * y);
    }

    /** Returns the squared length. */
    public float lengthSquared() {
        return x * x + y * y;
    }

    /** Returns this vector scaled to unit length, or zero if it has no length. */
    public Vec2f normalize() {
        float l = length();
        return l > 0 ? new Vec2f(x / l, y / l) : ZERO;
    }

    /** Returns the distance to {@code o}. */
    public float distance(Vec2f o) {
        return sub(o).length();
    }

    /** Returns the linear interpolation {@code this + (o - this) * t}. */
    public Vec2f lerp(Vec2f o, float t) {
        return new Vec2f(x + (o.x - x) * t, y + (o.y - y) * t);
    }

    /** Returns this vector rotated 90° counter-clockwise. */
    public Vec2f perpendicular() {
        return new Vec2f(-y, x);
    }
}
