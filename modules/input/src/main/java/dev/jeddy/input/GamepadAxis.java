package dev.jeddy.input;

/**
 * Gamepad axes ({@code SDL_GamepadAxis}). Sticks read -1..1 (y grows downwards, as SDL reports it),
 * triggers 0..1.
 *
 * <p>Thread ownership: constants.
 */
public final class GamepadAxis {
    private GamepadAxis() {}

    /** Left stick, horizontal. */
    public static final int LEFT_X = 0;

    /** Left stick, vertical (down is positive). */
    public static final int LEFT_Y = 1;

    /** Right stick, horizontal. */
    public static final int RIGHT_X = 2;

    /** Right stick, vertical (down is positive). */
    public static final int RIGHT_Y = 3;

    /** Left trigger. */
    public static final int LEFT_TRIGGER = 4;

    /** Right trigger. */
    public static final int RIGHT_TRIGGER = 5;

    /** Number of axes. */
    public static final int COUNT = 6;

    private static final String[] NAMES = {
        "LeftX", "LeftY", "RightX", "RightY", "LeftTrigger", "RightTrigger"
    };

    /** Returns the stable name of an axis for settings files, or {@code null}. */
    public static String name(int axis) {
        return axis >= 0 && axis < COUNT ? NAMES[axis] : null;
    }

    /** Returns the axis for a {@link #name(int)} (case-insensitive), or -1. */
    public static int fromName(String name) {
        for (int a = 0; a < COUNT; a++) {
            if (NAMES[a].equalsIgnoreCase(name)) {
                return a;
            }
        }
        return -1;
    }

    /** Returns {@code true} for the two triggers, which only go from 0 to 1. */
    public static boolean isTrigger(int axis) {
        return axis == LEFT_TRIGGER || axis == RIGHT_TRIGGER;
    }
}
