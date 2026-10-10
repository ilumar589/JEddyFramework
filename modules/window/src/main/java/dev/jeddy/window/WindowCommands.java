package dev.jeddy.window;

/**
 * Window opcodes for the {@link MainThreadQueue} (1–63) and the main-thread handler that executes
 * them against a {@link Window} and its {@link Cursors} (DESIGN §5, TASKS M2-10).
 *
 * <p>The static {@code post...} helpers are what game-thread code calls; they encode the request
 * and allocate nothing.
 *
 * <p>Thread ownership: the static helpers run on the queue's producer thread; {@link #handle} runs
 * on the main thread.
 */
public final class WindowCommands implements MainThreadQueue.Handler {
    /** Switch cursor: {@code a} = cursor ID. */
    public static final int SET_CURSOR = 1;

    /** Show ({@code a = 1}) or hide ({@code a = 0}) the cursor. */
    public static final int SHOW_CURSOR = 2;

    /** Start text input: {@code a..d} = field rect in window coordinates, {@code e} = caret x. */
    public static final int START_TEXT_INPUT = 3;

    /** Stop text input. */
    public static final int STOP_TEXT_INPUT = 4;

    /** Relative mouse mode on ({@code a = 1}) or off ({@code a = 0}). */
    public static final int SET_RELATIVE_MOUSE = 5;

    /** Warp the cursor: {@code a}, {@code b} = {@link Float#floatToRawIntBits} of x, y. */
    public static final int WARP_MOUSE = 6;

    /**
     * Change window mode: {@code a} = {@link WindowMode} ordinal, {@code b}, {@code c} = exclusive
     * resolution (0 = desktop), {@code d} = refresh rate in mHz (0 = desktop).
     */
    public static final int SET_WINDOW_MODE = 7;

    /** Change swap interval: {@code a} = {@link VSync} ordinal. */
    public static final int SET_VSYNC = 8;

    /** Confine the cursor to the window ({@code a = 1}) or release it ({@code a = 0}). */
    public static final int SET_MOUSE_GRAB = 9;

    /** Raise the window and request focus. */
    public static final int RAISE = 10;

    /** Highest opcode reserved for {@code window}. */
    public static final int LAST = 63;

    private static final WindowMode[] MODES = WindowMode.values();
    private static final VSync[] VSYNCS = VSync.values();

    private final Window window;
    private final Cursors cursors;

    /**
     * Creates the handler; register it with {@link MainThreadQueue#addHandler}.
     *
     * @param window the window the commands apply to
     * @param cursors the cursor pool, or {@code null} if the app has none
     */
    public WindowCommands(Window window, Cursors cursors) {
        this.window = window;
        this.cursors = cursors;
    }

    @Override
    public boolean handle(int opcode, int a, int b, int c, int d, int e) {
        switch (opcode) {
            case SET_CURSOR -> {
                if (cursors != null && a >= 0 && a < cursors.count()) {
                    cursors.set(a);
                }
            }
            case SHOW_CURSOR -> {
                if (cursors != null) {
                    cursors.setVisible(a != 0);
                }
            }
            case START_TEXT_INPUT -> window.startTextInput(a, b, c, d, e);
            case STOP_TEXT_INPUT -> window.stopTextInput();
            case SET_RELATIVE_MOUSE -> window.setRelativeMouseMode(a != 0);
            case WARP_MOUSE -> window.warpMouse(Float.intBitsToFloat(a), Float.intBitsToFloat(b));
            case SET_WINDOW_MODE -> window.setMode(MODES[a], b, c, d / 1000f);
            case SET_VSYNC -> window.setVSync(VSYNCS[a]);
            case SET_MOUSE_GRAB -> window.setMouseGrab(a != 0);
            case RAISE -> window.raise();
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Requests a cursor switch. */
    public static boolean postSetCursor(MainThreadQueue queue, int cursorId) {
        return queue.post(SET_CURSOR, cursorId);
    }

    /** Requests showing or hiding the cursor. */
    public static boolean postShowCursor(MainThreadQueue queue, boolean show) {
        return queue.post(SHOW_CURSOR, show ? 1 : 0);
    }

    /** Requests text input for a field at {@code x, y, w, h} with the caret at {@code cursor}. */
    public static boolean postStartTextInput(
            MainThreadQueue queue, int x, int y, int w, int h, int cursor) {
        return queue.post(START_TEXT_INPUT, x, y, w, h, cursor);
    }

    /** Requests the end of text input. */
    public static boolean postStopTextInput(MainThreadQueue queue) {
        return queue.post(STOP_TEXT_INPUT);
    }

    /** Requests relative mouse mode on or off. */
    public static boolean postRelativeMouse(MainThreadQueue queue, boolean enabled) {
        return queue.post(SET_RELATIVE_MOUSE, enabled ? 1 : 0);
    }

    /** Requests a cursor warp to window coordinates. */
    public static boolean postWarpMouse(MainThreadQueue queue, float x, float y) {
        return queue.post(
                WARP_MOUSE, Float.floatToRawIntBits(x), Float.floatToRawIntBits(y), 0, 0, 0);
    }

    /** Requests a window mode change (0 = desktop resolution and refresh rate). */
    public static boolean postWindowMode(
            MainThreadQueue queue, WindowMode mode, int width, int height, float refreshRate) {
        return queue.post(
                SET_WINDOW_MODE, mode.ordinal(), width, height, Math.round(refreshRate * 1000), 0);
    }

    /** Requests a swap interval change. */
    public static boolean postVSync(MainThreadQueue queue, VSync vsync) {
        return queue.post(SET_VSYNC, vsync.ordinal());
    }

    /** Requests confining or releasing the cursor. */
    public static boolean postMouseGrab(MainThreadQueue queue, boolean grab) {
        return queue.post(SET_MOUSE_GRAB, grab ? 1 : 0);
    }
}
