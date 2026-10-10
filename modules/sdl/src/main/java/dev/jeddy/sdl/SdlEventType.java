package dev.jeddy.sdl;

/**
 * {@code SDL_EventType} values as compile-time constants, so the event pump and window code can
 * {@code switch} on them. The generated bindings only offer them as methods. {@code SdlTest} checks
 * every value against the bindings.
 *
 * <p>Thread ownership: constants.
 */
public final class SdlEventType {
    private SdlEventType() {}

    /** {@code SDL_EVENT_QUIT}. */
    public static final int QUIT = 0x100;

    /** {@code SDL_EVENT_TERMINATING}. */
    public static final int TERMINATING = 0x101;

    /** {@code SDL_EVENT_LOW_MEMORY}. */
    public static final int LOW_MEMORY = 0x102;

    /** {@code SDL_EVENT_LOCALE_CHANGED}. */
    public static final int LOCALE_CHANGED = 0x107;

    /** {@code SDL_EVENT_SYSTEM_THEME_CHANGED}. */
    public static final int SYSTEM_THEME_CHANGED = 0x108;

    /** {@code SDL_EVENT_DISPLAY_ORIENTATION}, the first display event. */
    public static final int DISPLAY_FIRST = 0x151;

    /** {@code SDL_EVENT_DISPLAY_ADDED}. */
    public static final int DISPLAY_ADDED = 0x152;

    /** {@code SDL_EVENT_DISPLAY_REMOVED}. */
    public static final int DISPLAY_REMOVED = 0x153;

    /** {@code SDL_EVENT_DISPLAY_CONTENT_SCALE_CHANGED}. */
    public static final int DISPLAY_CONTENT_SCALE_CHANGED = 0x157;

    /** {@code SDL_EVENT_DISPLAY_USABLE_BOUNDS_CHANGED}, the last display event. */
    public static final int DISPLAY_LAST = 0x158;

    /** {@code SDL_EVENT_WINDOW_SHOWN}, the first window event. */
    public static final int WINDOW_SHOWN = 0x202;

    /** {@code SDL_EVENT_WINDOW_HIDDEN}. */
    public static final int WINDOW_HIDDEN = 0x203;

    /** {@code SDL_EVENT_WINDOW_EXPOSED}. */
    public static final int WINDOW_EXPOSED = 0x204;

    /** {@code SDL_EVENT_WINDOW_MOVED}. */
    public static final int WINDOW_MOVED = 0x205;

    /** {@code SDL_EVENT_WINDOW_RESIZED}: data1, data2 are the new size in window coordinates. */
    public static final int WINDOW_RESIZED = 0x206;

    /** {@code SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED}: data1, data2 are the new size in pixels. */
    public static final int WINDOW_PIXEL_SIZE_CHANGED = 0x207;

    /** {@code SDL_EVENT_WINDOW_MINIMIZED}. */
    public static final int WINDOW_MINIMIZED = 0x209;

    /** {@code SDL_EVENT_WINDOW_MAXIMIZED}. */
    public static final int WINDOW_MAXIMIZED = 0x20A;

    /** {@code SDL_EVENT_WINDOW_RESTORED}. */
    public static final int WINDOW_RESTORED = 0x20B;

    /** {@code SDL_EVENT_WINDOW_MOUSE_ENTER}. */
    public static final int WINDOW_MOUSE_ENTER = 0x20C;

    /** {@code SDL_EVENT_WINDOW_MOUSE_LEAVE}. */
    public static final int WINDOW_MOUSE_LEAVE = 0x20D;

    /** {@code SDL_EVENT_WINDOW_FOCUS_GAINED}. */
    public static final int WINDOW_FOCUS_GAINED = 0x20E;

    /** {@code SDL_EVENT_WINDOW_FOCUS_LOST}. */
    public static final int WINDOW_FOCUS_LOST = 0x20F;

    /** {@code SDL_EVENT_WINDOW_CLOSE_REQUESTED}. */
    public static final int WINDOW_CLOSE_REQUESTED = 0x210;

    /** {@code SDL_EVENT_WINDOW_DISPLAY_CHANGED}: data1 is the new display ID. */
    public static final int WINDOW_DISPLAY_CHANGED = 0x213;

    /** {@code SDL_EVENT_WINDOW_DISPLAY_SCALE_CHANGED}. */
    public static final int WINDOW_DISPLAY_SCALE_CHANGED = 0x214;

    /** {@code SDL_EVENT_WINDOW_OCCLUDED}. */
    public static final int WINDOW_OCCLUDED = 0x216;

    /** {@code SDL_EVENT_WINDOW_ENTER_FULLSCREEN}. */
    public static final int WINDOW_ENTER_FULLSCREEN = 0x217;

    /** {@code SDL_EVENT_WINDOW_LEAVE_FULLSCREEN}. */
    public static final int WINDOW_LEAVE_FULLSCREEN = 0x218;

    /** {@code SDL_EVENT_WINDOW_DESTROYED}. */
    public static final int WINDOW_DESTROYED = 0x219;

    /** {@code SDL_EVENT_WINDOW_HDR_STATE_CHANGED}, the last window event. */
    public static final int WINDOW_LAST = 0x21A;

    /** {@code SDL_EVENT_KEY_DOWN}. */
    public static final int KEY_DOWN = 0x300;

    /** {@code SDL_EVENT_KEY_UP}. */
    public static final int KEY_UP = 0x301;

    /** {@code SDL_EVENT_TEXT_EDITING}. */
    public static final int TEXT_EDITING = 0x302;

    /** {@code SDL_EVENT_TEXT_INPUT}. */
    public static final int TEXT_INPUT = 0x303;

    /** {@code SDL_EVENT_KEYMAP_CHANGED}. */
    public static final int KEYMAP_CHANGED = 0x304;

    /** {@code SDL_EVENT_MOUSE_MOTION}. */
    public static final int MOUSE_MOTION = 0x400;

    /** {@code SDL_EVENT_MOUSE_BUTTON_DOWN}. */
    public static final int MOUSE_BUTTON_DOWN = 0x401;

    /** {@code SDL_EVENT_MOUSE_BUTTON_UP}. */
    public static final int MOUSE_BUTTON_UP = 0x402;

    /** {@code SDL_EVENT_MOUSE_WHEEL}. */
    public static final int MOUSE_WHEEL = 0x403;

    /** {@code SDL_EVENT_GAMEPAD_AXIS_MOTION}. */
    public static final int GAMEPAD_AXIS_MOTION = 0x650;

    /** {@code SDL_EVENT_GAMEPAD_BUTTON_DOWN}. */
    public static final int GAMEPAD_BUTTON_DOWN = 0x651;

    /** {@code SDL_EVENT_GAMEPAD_BUTTON_UP}. */
    public static final int GAMEPAD_BUTTON_UP = 0x652;

    /** {@code SDL_EVENT_GAMEPAD_ADDED}. */
    public static final int GAMEPAD_ADDED = 0x653;

    /** {@code SDL_EVENT_GAMEPAD_REMOVED}. */
    public static final int GAMEPAD_REMOVED = 0x654;

    /** {@code SDL_EVENT_GAMEPAD_REMAPPED}. */
    public static final int GAMEPAD_REMAPPED = 0x655;

    /** {@code SDL_EVENT_CLIPBOARD_UPDATE}. */
    public static final int CLIPBOARD_UPDATE = 0x900;

    /** {@code SDL_EVENT_DROP_FILE}. */
    public static final int DROP_FILE = 0x1000;

    /** {@code SDL_EVENT_AUDIO_DEVICE_ADDED}. */
    public static final int AUDIO_DEVICE_ADDED = 0x1100;

    /** {@code SDL_EVENT_AUDIO_DEVICE_REMOVED}. */
    public static final int AUDIO_DEVICE_REMOVED = 0x1101;

    /** {@code SDL_EVENT_USER}, the first user event. */
    public static final int USER = 0x8000;

    /** Returns {@code true} for {@code SDL_EVENT_WINDOW_*} types. */
    public static boolean isWindowEvent(int type) {
        return type >= WINDOW_SHOWN && type <= WINDOW_LAST;
    }

    /** Returns {@code true} for {@code SDL_EVENT_DISPLAY_*} types. */
    public static boolean isDisplayEvent(int type) {
        return type >= DISPLAY_FIRST && type <= DISPLAY_LAST;
    }
}
