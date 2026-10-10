package dev.jeddy.math;

/**
 * Immutable capsule: the points within {@code radius} of segment {@code a}–{@code b} (value type).
 *
 * <p>Thread ownership: immutable.
 *
 * @param a first segment end
 * @param b second segment end
 * @param radius radius
 */
public record Capsulef(Vec3f a, Vec3f b, float radius) {
    /** Returns {@code true} if {@code p} is inside or on the capsule. */
    public boolean contains(Vec3f p) {
        return Intersect.pointSegmentDistanceSquared(
                        p.x(), p.y(), p.z(), a.x(), a.y(), a.z(), b.x(), b.y(), b.z())
                <= radius * radius;
    }
}
