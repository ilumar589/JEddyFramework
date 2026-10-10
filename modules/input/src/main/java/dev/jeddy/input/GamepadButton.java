package dev.jeddy.input;

/**
 * Gamepad buttons ({@code SDL_GamepadButton}), named by position: {@link #SOUTH} is A on Xbox pads
 * and Cross on PlayStation pads.
 *
 * <p>Thread ownership: constants.
 */
public final class GamepadButton {
    private GamepadButton() {}

    /** Bottom face button (A, Cross). */
    public static final int SOUTH = 0;

    /** Right face button (B, Circle). */
    public static final int EAST = 1;

    /** Left face button (X, Square). */
    public static final int WEST = 2;

    /** Top face button (Y, Triangle). */
    public static final int NORTH = 3;

    /** Back / Select / Share. */
    public static final int BACK = 4;

    /** Guide / Home / PS. */
    public static final int GUIDE = 5;

    /** Start / Menu / Options. */
    public static final int START = 6;

    /** Left stick click. */
    public static final int LEFT_STICK = 7;

    /** Right stick click. */
    public static final int RIGHT_STICK = 8;

    /** Left bumper. */
    public static final int LEFT_SHOULDER = 9;

    /** Right bumper. */
    public static final int RIGHT_SHOULDER = 10;

    /** D-pad up. */
    public static final int DPAD_UP = 11;

    /** D-pad down. */
    public static final int DPAD_DOWN = 12;

    /** D-pad left. */
    public static final int DPAD_LEFT = 13;

    /** D-pad right. */
    public static final int DPAD_RIGHT = 14;

    /** Extra button (Xbox Share, PS5 mic, Switch capture). */
    public static final int MISC1 = 15;

    /** Upper right paddle. */
    public static final int RIGHT_PADDLE1 = 16;

    /** Upper left paddle. */
    public static final int LEFT_PADDLE1 = 17;

    /** Lower right paddle. */
    public static final int RIGHT_PADDLE2 = 18;

    /** Lower left paddle. */
    public static final int LEFT_PADDLE2 = 19;

    /** Touchpad click. */
    public static final int TOUCHPAD = 20;

    /** Number of buttons SDL defines. */
    public static final int COUNT = 26;

    private static final String[] NAMES = {
        "South",
        "East",
        "West",
        "North",
        "Back",
        "Guide",
        "Start",
        "LeftStick",
        "RightStick",
        "LeftShoulder",
        "RightShoulder",
        "DpadUp",
        "DpadDown",
        "DpadLeft",
        "DpadRight",
        "Misc1",
        "RightPaddle1",
        "LeftPaddle1",
        "RightPaddle2",
        "LeftPaddle2",
        "Touchpad",
        "Misc2",
        "Misc3",
        "Misc4",
        "Misc5",
        "Misc6"
    };

    /** Returns the stable name of a button for settings files, or {@code null}. */
    public static String name(int button) {
        return button >= 0 && button < COUNT ? NAMES[button] : null;
    }

    /** Returns the button for a {@link #name(int)} (case-insensitive), or -1. */
    public static int fromName(String name) {
        for (int b = 0; b < COUNT; b++) {
            if (NAMES[b].equalsIgnoreCase(name)) {
                return b;
            }
        }
        return -1;
    }
}
