package dev.jeddy.ui;

/**
 * The keys the UI reacts to, as SDL scancodes (the same numbers as {@code
 * dev.jeddy.input.Scancode}; the {@code ui} module does not depend on {@code input}, so the adapter
 * in {@code app} copies scancodes through unchanged). Any other scancode in a {@link UiInput} key
 * event is passed on to widgets as is.
 *
 * <p>Thread ownership: constants.
 */
public final class UiKey {
    /** {@code A}, for select-all with Ctrl. */
    public static final int A = 4;

    /** {@code C}, copy. */
    public static final int C = 6;

    /** {@code V}, paste. */
    public static final int V = 25;

    /** {@code X}, cut. */
    public static final int X = 27;

    /** {@code Z}, undo. */
    public static final int Z = 29;

    /** Enter. */
    public static final int RETURN = 40;

    /** Escape. */
    public static final int ESCAPE = 41;

    /** Backspace. */
    public static final int BACKSPACE = 42;

    /** Tab. */
    public static final int TAB = 43;

    /** Space. */
    public static final int SPACE = 44;

    /** Home. */
    public static final int HOME = 74;

    /** Page up. */
    public static final int PAGE_UP = 75;

    /** Delete. */
    public static final int DELETE = 76;

    /** End. */
    public static final int END = 77;

    /** Page down. */
    public static final int PAGE_DOWN = 78;

    /** Right arrow. */
    public static final int RIGHT = 79;

    /** Left arrow. */
    public static final int LEFT = 80;

    /** Down arrow. */
    public static final int DOWN = 81;

    /** Up arrow. */
    public static final int UP = 82;

    /** Modifier bit: shift. */
    public static final int MOD_SHIFT = 1;

    /** Modifier bit: control. */
    public static final int MOD_CTRL = 2;

    /** Modifier bit: alt. */
    public static final int MOD_ALT = 4;

    /** Modifier bit: the GUI (Windows or command) key. */
    public static final int MOD_GUI = 8;

    private UiKey() {}
}
