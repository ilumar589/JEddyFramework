package dev.jeddy.input;

/**
 * Modifier key bits, left and right merged ({@link Input#modifiers()}, binding chords such as
 * Shift+Click to queue orders).
 *
 * <p>Thread ownership: constants.
 */
public final class Modifiers {
    private Modifiers() {}

    /** No modifier. */
    public static final int NONE = 0;

    /** Either Shift key. */
    public static final int SHIFT = 1;

    /** Either Ctrl key. */
    public static final int CTRL = 2;

    /** Either Alt key. */
    public static final int ALT = 4;

    /** Either GUI key (Windows key, Super). */
    public static final int GUI = 8;

    /** All four bits. */
    public static final int ALL = SHIFT | CTRL | ALT | GUI;

    /** Converts SDL's {@code SDL_Keymod} bits to these bits. */
    public static int fromSdl(int keymod) {
        int m = 0;
        if ((keymod & 0x0003) != 0) {
            m |= SHIFT;
        }
        if ((keymod & 0x00C0) != 0) {
            m |= CTRL;
        }
        if ((keymod & 0x0300) != 0) {
            m |= ALT;
        }
        if ((keymod & 0x0C00) != 0) {
            m |= GUI;
        }
        return m;
    }

    /** Returns the modifier bit for a modifier key's scancode, or 0 for other keys. */
    public static int ofScancode(int scancode) {
        return switch (scancode) {
            case Scancode.LSHIFT, Scancode.RSHIFT -> SHIFT;
            case Scancode.LCTRL, Scancode.RCTRL -> CTRL;
            case Scancode.LALT, Scancode.RALT -> ALT;
            case Scancode.LGUI, Scancode.RGUI -> GUI;
            default -> NONE;
        };
    }

    /** Formats bits as {@code Shift+Ctrl+...} (cold path). */
    public static String toString(int modifiers) {
        StringBuilder sb = new StringBuilder();
        if ((modifiers & SHIFT) != 0) {
            sb.append("Shift+");
        }
        if ((modifiers & CTRL) != 0) {
            sb.append("Ctrl+");
        }
        if ((modifiers & ALT) != 0) {
            sb.append("Alt+");
        }
        if ((modifiers & GUI) != 0) {
            sb.append("Gui+");
        }
        return sb.toString();
    }
}
