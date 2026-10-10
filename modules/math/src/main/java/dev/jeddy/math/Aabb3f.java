package dev.jeddy.math;

/**
 * Immutable axis-aligned bounding box (value type).
 *
 * <p>Thread ownership: immutable.
 *
 * @param min minimum corner
 * @param max maximum corner
 */
public record Aabb3f(Vec3f min, Vec3f max) {
    /** An empty box: unions with it return the other box. */
    public static final Aabb3f EMPTY =
            new Aabb3f(
                    new Vec3f(
                            Float.POSITIVE_INFINITY,
                            Float.POSITIVE_INFINITY,
                            Float.POSITIVE_INFINITY),
                    new Vec3f(
                            Float.NEGATIVE_INFINITY,
                            Float.NEGATIVE_INFINITY,
                            Float.NEGATIVE_INFINITY));

    /** Returns the box with the given center and half-extents. */
    public static Aabb3f ofCenter(Vec3f center, Vec3f halfExtents) {
        return new Aabb3f(center.sub(halfExtents), center.add(halfExtents));
    }

    /** Returns {@code true} if min ≤ max on every axis. */
    public boolean isValid() {
        return min.x() <= max.x() && min.y() <= max.y() && min.z() <= max.z();
    }

    /** Returns the center. */
    public Vec3f center() {
        return min.add(max).mul(0.5f);
    }

    /** Returns the half-extents. */
    public Vec3f halfExtents() {
        return max.sub(min).mul(0.5f);
    }

    /** Returns the surface area (for SAH BVH building). */
    public float surfaceArea() {
        float dx = max.x() - min.x();
        float dy = max.y() - min.y();
        float dz = max.z() - min.z();
        return 2 * (dx * dy + dy * dz + dz * dx);
    }

    /** Returns {@code true} if {@code p} is inside or on the boundary. */
    public boolean contains(Vec3f p) {
        return p.x() >= min.x()
                && p.x() <= max.x()
                && p.y() >= min.y()
                && p.y() <= max.y()
                && p.z() >= min.z()
                && p.z() <= max.z();
    }

    /** Returns {@code true} if the boxes overlap (touching counts). */
    public boolean intersects(Aabb3f o) {
        return min.x() <= o.max.x()
                && max.x() >= o.min.x()
                && min.y() <= o.max.y()
                && max.y() >= o.min.y()
                && min.z() <= o.max.z()
                && max.z() >= o.min.z();
    }

    /** Returns the smallest box containing both. */
    public Aabb3f union(Aabb3f o) {
        return new Aabb3f(min.min(o.min), max.max(o.max));
    }

    /** Returns the smallest box containing this box and {@code p}. */
    public Aabb3f include(Vec3f p) {
        return new Aabb3f(min.min(p), max.max(p));
    }

    /** Returns this box grown by {@code amount} on every side. */
    public Aabb3f expand(float amount) {
        return new Aabb3f(min.add(-amount, -amount, -amount), max.add(amount, amount, amount));
    }
}
