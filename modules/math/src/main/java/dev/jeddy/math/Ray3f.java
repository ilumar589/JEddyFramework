package dev.jeddy.math;

/**
 * Immutable ray (value type). The direction is normally unit length, so {@code t} values are
 * distances.
 *
 * <p>Thread ownership: immutable.
 *
 * @param origin start point
 * @param direction direction
 */
public record Ray3f(Vec3f origin, Vec3f direction) {
    /** Returns {@code origin + direction * t}. */
    public Vec3f at(float t) {
        return origin.add(direction.mul(t));
    }
}
