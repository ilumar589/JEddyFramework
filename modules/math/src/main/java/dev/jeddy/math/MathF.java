package dev.jeddy.math;

/**
 * Scalar helpers in {@code float}: clamping, interpolation, angles and frame-rate independent
 * smoothing.
 *
 * <p>Thread ownership: stateless, callable from any thread.
 */
public final class MathF {
    /** π. */
    public static final float PI = (float) Math.PI;

    /** 2π. */
    public static final float TAU = (float) (2 * Math.PI);

    /** π / 2. */
    public static final float HALF_PI = (float) (Math.PI / 2);

    private MathF() {}

    /** Clamps {@code v} to {@code [min, max]}. */
    public static float clamp(float v, float min, float max) {
        return v < min ? min : v > max ? max : v;
    }

    /** Clamps {@code v} to {@code [min, max]}. */
    public static int clamp(int v, int min, int max) {
        return v < min ? min : v > max ? max : v;
    }

    /** Clamps {@code v} to {@code [0, 1]}. */
    public static float saturate(float v) {
        return clamp(v, 0, 1);
    }

    /** Returns {@code a + (b - a) * t}. */
    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** Returns where {@code v} lies between {@code a} and {@code b} (0 at a, 1 at b). */
    public static float inverseLerp(float a, float b, float v) {
        return a == b ? 0 : (v - a) / (b - a);
    }

    /** Maps {@code v} from {@code [inMin, inMax]} to {@code [outMin, outMax]}. */
    public static float remap(float v, float inMin, float inMax, float outMin, float outMax) {
        return lerp(outMin, outMax, inverseLerp(inMin, inMax, v));
    }

    /** Hermite smoothstep between edges {@code a} and {@code b}. */
    public static float smoothstep(float a, float b, float v) {
        float t = saturate(inverseLerp(a, b, v));
        return t * t * (3 - 2 * t);
    }

    /**
     * Frame-rate independent exponential approach: moves {@code current} towards {@code target}
     * with decay rate {@code lambda} (per second) over {@code dt} seconds.
     */
    public static float damp(float current, float target, float lambda, float dt) {
        return lerp(target, current, (float) Math.exp(-lambda * dt));
    }

    /** Moves {@code current} towards {@code target} by at most {@code maxDelta}. */
    public static float moveTowards(float current, float target, float maxDelta) {
        float d = target - current;
        return Math.abs(d) <= maxDelta ? target : current + Math.signum(d) * maxDelta;
    }

    /** Wraps an angle to {@code (-π, π]}. */
    public static float wrapAngle(float radians) {
        float a = (float) Math.IEEEremainder(radians, TAU);
        return a <= -PI ? a + TAU : a;
    }

    /** Interpolates angles along the shortest arc. */
    public static float lerpAngle(float a, float b, float t) {
        return a + wrapAngle(b - a) * t;
    }

    /** Returns {@code true} if {@code |a - b| <= epsilon}. */
    public static boolean approx(float a, float b, float epsilon) {
        return Math.abs(a - b) <= epsilon;
    }

    /** Converts degrees to radians. */
    public static float toRadians(float degrees) {
        return degrees * (PI / 180f);
    }

    /** Converts radians to degrees. */
    public static float toDegrees(float radians) {
        return radians * (180f / PI);
    }

    /** Floor that is faster than {@link Math#floor(double)} for floats in int range. */
    public static int floorToInt(float v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    /** Returns the smallest power of two ≥ {@code v} (v ≥ 1). */
    public static int nextPowerOfTwo(int v) {
        return v <= 1 ? 1 : Integer.highestOneBit(v - 1) << 1;
    }
}
