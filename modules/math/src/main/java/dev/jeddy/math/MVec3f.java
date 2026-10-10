package dev.jeddy.math;

/**
 * Mutable 3D vector for per-frame scratch work without allocation (DESIGN §8.1, layer 2). Methods
 * modify {@code this} and return it for chaining. Keep instances as fields of the system that
 * uses them.
 *
 * <p>Thread ownership: single owner thread.
 */
public final class MVec3f {
    /** X component. */
    public float x;

    /** Y component (up). */
    public float y;

    /** Z component. */
    public float z;

    /** Creates (0, 0, 0). */
    public MVec3f() {}

    /** Creates {@code (x, y, z)}. */
    public MVec3f(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** Sets the components. */
    public MVec3f set(float nx, float ny, float nz) {
        x = nx;
        y = ny;
        z = nz;
        return this;
    }

    /** Copies {@code o}. */
    public MVec3f set(MVec3f o) {
        return set(o.x, o.y, o.z);
    }

    /** Copies an immutable vector. */
    public MVec3f set(Vec3f o) {
        return set(o.x(), o.y(), o.z());
    }

    /** Adds {@code o}. */
    public MVec3f add(MVec3f o) {
        return set(x + o.x, y + o.y, z + o.z);
    }

    /** Adds {@code (ox, oy, oz)}. */
    public MVec3f add(float ox, float oy, float oz) {
        return set(x + ox, y + oy, z + oz);
    }

    /** Subtracts {@code o}. */
    public MVec3f sub(MVec3f o) {
        return set(x - o.x, y - o.y, z - o.z);
    }

    /** Scales by {@code s}. */
    public MVec3f mul(float s) {
        return set(x * s, y * s, z * s);
    }

    /** Adds {@code o * s}. */
    public MVec3f addScaled(MVec3f o, float s) {
        return set(x + o.x * s, y + o.y * s, z + o.z * s);
    }

    /** Replaces this with {@code this × o}. */
    public MVec3f cross(MVec3f o) {
        return set(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
    }

    /** Returns the dot product. */
    public float dot(MVec3f o) {
        return x * o.x + y * o.y + z * o.z;
    }

    /** Returns the length. */
    public float length() {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    /** Returns the squared length. */
    public float lengthSquared() {
        return x * x + y * y + z * z;
    }

    /** Scales to unit length (zero stays zero). */
    public MVec3f normalize() {
        float l2 = lengthSquared();
        return l2 > 0 ? mul((float) (1 / Math.sqrt(l2))) : this;
    }

    /** Interpolates towards {@code o}. */
    public MVec3f lerp(MVec3f o, float t) {
        return set(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t);
    }

    /** Returns the distance to {@code o}. */
    public float distance(MVec3f o) {
        float dx = x - o.x;
        float dy = y - o.y;
        float dz = z - o.z;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Transforms this point by a column-major 4×4 matrix (no perspective divide). */
    public MVec3f transformPoint(float[] m, int o) {
        return set(
                m[o] * x + m[o + 4] * y + m[o + 8] * z + m[o + 12],
                m[o + 1] * x + m[o + 5] * y + m[o + 9] * z + m[o + 13],
                m[o + 2] * x + m[o + 6] * y + m[o + 10] * z + m[o + 14]);
    }

    /** Returns an immutable copy (allocates). */
    public Vec3f toVec3f() {
        return new Vec3f(x, y, z);
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ")";
    }
}
