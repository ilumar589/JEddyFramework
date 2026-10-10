package dev.jeddy.math;

/**
 * Immutable sphere (value type).
 *
 * <p>Thread ownership: immutable.
 *
 * @param center center
 * @param radius radius
 */
public record Spheref(Vec3f center, float radius) {
    /** Returns {@code true} if {@code p} is inside or on the sphere. */
    public boolean contains(Vec3f p) {
        return center.distanceSquared(p) <= radius * radius;
    }

    /** Returns {@code true} if the spheres overlap. */
    public boolean intersects(Spheref o) {
        float r = radius + o.radius;
        return center.distanceSquared(o.center) <= r * r;
    }
}
