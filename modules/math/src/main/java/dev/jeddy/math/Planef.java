package dev.jeddy.math;

/**
 * Immutable plane {@code normal · p + d = 0} (value type). Points with positive {@link
 * #distance(Vec3f)} are in front.
 *
 * <p>Thread ownership: immutable.
 *
 * @param normal unit normal
 * @param d offset
 */
public record Planef(Vec3f normal, float d) {
    /** The ground plane y = 0, facing up. */
    public static final Planef GROUND = new Planef(Vec3f.UNIT_Y, 0);

    /** Returns the plane through {@code point} with unit {@code normal}. */
    public static Planef fromPointNormal(Vec3f point, Vec3f normal) {
        Vec3f n = normal.normalize();
        return new Planef(n, -n.dot(point));
    }

    /** Returns the plane through three points, facing towards the side where a→b→c is CCW. */
    public static Planef fromPoints(Vec3f a, Vec3f b, Vec3f c) {
        return fromPointNormal(a, b.sub(a).cross(c.sub(a)));
    }

    /** Returns the signed distance of {@code p}. */
    public float distance(Vec3f p) {
        return normal.dot(p) + d;
    }

    /** Returns the ray parameter where {@code ray} hits the plane, or NaN if parallel. */
    public float intersect(Ray3f ray) {
        float denom = normal.dot(ray.direction());
        if (Math.abs(denom) < 1e-12f) {
            return Float.NaN;
        }
        return -(normal.dot(ray.origin()) + d) / denom;
    }
}
