package dev.jeddy.math;

/**
 * Easing curves mapping {@code t} in {@code [0, 1]} to an eased value (usually in {@code [0, 1]}).
 * For UI, camera moves and VFX.
 *
 * <p>Thread ownership: stateless, callable from any thread.
 */
public final class Ease {
    private Ease() {}

    /** Identity. */
    public static float linear(float t) {
        return t;
    }

    /** Quadratic ease-in. */
    public static float inQuad(float t) {
        return t * t;
    }

    /** Quadratic ease-out. */
    public static float outQuad(float t) {
        return t * (2 - t);
    }

    /** Quadratic ease-in-out. */
    public static float inOutQuad(float t) {
        return t < 0.5f ? 2 * t * t : -1 + (4 - 2 * t) * t;
    }

    /** Cubic ease-in. */
    public static float inCubic(float t) {
        return t * t * t;
    }

    /** Cubic ease-out. */
    public static float outCubic(float t) {
        float u = t - 1;
        return u * u * u + 1;
    }

    /** Cubic ease-in-out. */
    public static float inOutCubic(float t) {
        return t < 0.5f ? 4 * t * t * t : (t - 1) * (2 * t - 2) * (2 * t - 2) + 1;
    }

    /** Sine ease-in-out. */
    public static float inOutSine(float t) {
        return (float) (-(Math.cos(Math.PI * t) - 1) / 2);
    }

    /** Exponential ease-out. */
    public static float outExpo(float t) {
        return t >= 1 ? 1 : (float) (1 - Math.pow(2, -10 * t));
    }

    /** Ease-out with a small overshoot. */
    public static float outBack(float t) {
        float c1 = 1.70158f;
        float c3 = c1 + 1;
        float u = t - 1;
        return 1 + c3 * u * u * u + c1 * u * u;
    }

    /** Elastic ease-out. */
    public static float outElastic(float t) {
        if (t <= 0 || t >= 1) {
            return t <= 0 ? 0 : 1;
        }
        return (float) (Math.pow(2, -10 * t) * Math.sin((t * 10 - 0.75) * (2 * Math.PI / 3)) + 1);
    }

    /** Bounce ease-out. */
    public static float outBounce(float t) {
        float n1 = 7.5625f;
        float d1 = 2.75f;
        if (t < 1 / d1) {
            return n1 * t * t;
        } else if (t < 2 / d1) {
            float u = t - 1.5f / d1;
            return n1 * u * u + 0.75f;
        } else if (t < 2.5f / d1) {
            float u = t - 2.25f / d1;
            return n1 * u * u + 0.9375f;
        }
        float u = t - 2.625f / d1;
        return n1 * u * u + 0.984375f;
    }

    /** Smootherstep (Perlin's 6t⁵ − 15t⁴ + 10t³). */
    public static float smootherstep(float t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }
}
