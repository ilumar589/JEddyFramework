package dev.jeddy.input;

/**
 * Layout of the fixed 8-{@code int} event records that {@link EventPump} writes on the main thread
 * and {@link Input} reads on the game thread (DESIGN §12.2).
 *
 * <pre>
 * [0] kind   [1] timestamp ns, low 32 bits   [2] timestamp ns, high 32 bits   [3..7] a..e
 * </pre>
 *
 * Floats travel as {@link Float#floatToRawIntBits} values.
 *
 * <p>Thread ownership: constants.
 */
final class InputRecord {
    private InputRecord() {}

    static final int INTS = 8;

    static final int KIND = 0;
    static final int TIME_LO = 1;
    static final int TIME_HI = 2;
    static final int A = 3;
    static final int B = 4;
    static final int C = 5;
    static final int D = 6;
    static final int E = 7;

    /** The application should quit (window closed, OS logout). */
    static final int QUIT = 1;

    /** a = scancode, b = keycode, c = SDL modifier state, d = flags ({@link #KEY_DOWN} ...). */
    static final int KEY = 2;

    /** a = Unicode code point of committed text. */
    static final int TEXT = 3;

    /** a = code point of IME composition text, or -1 to start a new composition (b = caret). */
    static final int TEXT_EDIT = 4;

    /** a, b = x, y; c, d = relative x, y (float bits); e = button mask. */
    static final int MOUSE_MOTION = 5;

    /** a = button (1-5), b = 1 down / 0 up, c = click count, d, e = x, y (float bits). */
    static final int MOUSE_BUTTON = 6;

    /** a, b = scroll x, y (float bits, flipped to "normal"), d, e = cursor x, y (float bits). */
    static final int MOUSE_WHEEL = 7;

    /** a = SDL window event type, b = data1, c = data2, d = window ID. */
    static final int WINDOW = 8;

    /** a = slot, b = joystick ID, c = gamepad type. */
    static final int PAD_ADDED = 9;

    /** a = slot, b = joystick ID. */
    static final int PAD_REMOVED = 10;

    /** a = slot, b = button, c = 1 down / 0 up. */
    static final int PAD_BUTTON = 11;

    /** a = slot, b = axis, c = value (-32768..32767). */
    static final int PAD_AXIS = 12;

    /** The keyboard layout changed; key labels were refreshed. */
    static final int KEYMAP_CHANGED = 13;

    /** Key flag: pressed (otherwise released). */
    static final int KEY_DOWN = 1;

    /** Key flag: OS key repeat. */
    static final int KEY_REPEAT = 2;
}
