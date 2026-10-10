package dev.jeddy.input;

/**
 * Mouse buttons as SDL numbers them.
 *
 * <p>Thread ownership: constants.
 */
public final class MouseButton {
    private MouseButton() {}

    /** Left button. */
    public static final int LEFT = 1;

    /** Middle button (wheel click). */
    public static final int MIDDLE = 2;

    /** Right button. */
    public static final int RIGHT = 3;

    /** First side button (back). */
    public static final int X1 = 4;

    /** Second side button (forward). */
    public static final int X2 = 5;

    /** One past the highest button number. */
    public static final int COUNT = 6;

    private static final String[] NAMES = {null, "Left", "Middle", "Right", "X1", "X2"};

    /** Returns the stable name of a button for settings files, or {@code null}. */
    public static String name(int button) {
        return button > 0 && button < COUNT ? NAMES[button] : null;
    }

    /** Returns the button for a {@link #name(int)} (case-insensitive), or 0. */
    public static int fromName(String name) {
        for (int b = 1; b < COUNT; b++) {
            if (NAMES[b].equalsIgnoreCase(name)) {
                return b;
            }
        }
        return 0;
    }
}
