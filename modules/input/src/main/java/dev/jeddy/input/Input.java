package dev.jeddy.input;

import dev.jeddy.core.collections.IntList;
import dev.jeddy.core.concurrent.SpscIntRing;
import dev.jeddy.sdl.SdlEventType;

/**
 * Device state on the game thread (DESIGN §12.2, TASKS M2-05): keyboard, mouse, text input,
 * gamepads and the game thread's view of the window, rebuilt each frame from the {@link EventPump}
 * records.
 *
 * <p><b>Edges by frame stamp.</b> Pressing a key stores the current frame number in {@code
 * pressedFrame[key]}; "pressed this frame" compares it with {@link #frame()}. Nothing is cleared
 * per frame, and a key pressed and released within one frame reports both edges, so short taps are
 * never lost.
 *
 * <p>Call {@link #update()} once per game frame before reading anything. It allocates nothing as
 * long as one frame's text and key events fit the preallocated lists (256 entries each).
 *
 * <p>Thread ownership: game thread only (the consumer of the pump's ring).
 */
public final class Input {
    /** Last device used: keyboard and mouse. */
    public static final int DEVICE_KEYBOARD_MOUSE = 0;

    /** Last device used: a gamepad. */
    public static final int DEVICE_GAMEPAD = 1;

    private static final int PADS = EventPump.MAX_GAMEPADS;
    private static final float AXIS_SCALE = 1f / 32767f;

    private final SpscIntRing in;
    private final int[] rec = new int[InputRecord.INTS];
    private int frame;
    private long lastEventNanos;
    private int lastDevice = DEVICE_KEYBOARD_MOUSE;

    // Keyboard
    private final boolean[] keyDown = new boolean[Scancode.COUNT];
    private final int[] keyPressedFrame = new int[Scancode.COUNT];
    private final int[] keyReleasedFrame = new int[Scancode.COUNT];
    private final int[] keyRepeatFrame = new int[Scancode.COUNT];
    private int modifiers;
    private final IntList keyEvents = new IntList(3 * 256);
    private final IntList text = new IntList(256);
    private final IntList composition = new IntList(64);
    private int compositionCaret;
    private boolean textInputRequested;

    // Mouse
    private float mouseX;
    private float mouseY;
    private float deltaX;
    private float deltaY;
    private float wheelX;
    private float wheelY;
    private int buttons;
    private boolean mouseInWindow = true;
    private final int[] buttonPressedFrame = new int[MouseButton.COUNT];
    private final int[] buttonReleasedFrame = new int[MouseButton.COUNT];
    private final int[] buttonClicks = new int[MouseButton.COUNT];
    private final long[] buttonPressedNanos = new long[MouseButton.COUNT];
    private final float[] buttonPressedX = new float[MouseButton.COUNT];
    private final float[] buttonPressedY = new float[MouseButton.COUNT];

    // Window, as seen by the game thread
    private int windowWidth;
    private int windowHeight;
    private int pixelWidth;
    private int pixelHeight;
    private float displayScale = 1f;
    private boolean focused = true;
    private boolean minimized;
    private boolean quitRequested;
    private boolean closeRequested;
    private int windowGeneration;

    // Gamepads
    private final boolean[] padConnected = new boolean[PADS];
    private final int[] padType = new int[PADS];
    private final int[] padJoystick = new int[PADS];
    private final float[] padAxes = new float[PADS * GamepadAxis.COUNT];
    private final boolean[] padDown = new boolean[PADS * GamepadButton.COUNT];
    private final int[] padPressedFrame = new int[PADS * GamepadButton.COUNT];
    private final int[] padReleasedFrame = new int[PADS * GamepadButton.COUNT];
    private int padConnectFrame;
    private int lastPad = -1;

    /**
     * Creates the state, reading from the ring the {@link EventPump} writes.
     *
     * @param in the pump's output ring
     * @param windowWidth initial window width (window coordinates)
     * @param windowHeight initial window height
     */
    public Input(SpscIntRing in, int windowWidth, int windowHeight) {
        this.in = in;
        this.windowWidth = windowWidth;
        this.windowHeight = windowHeight;
        this.pixelWidth = windowWidth;
        this.pixelHeight = windowHeight;
    }

    /**
     * Starts a new frame: increments {@link #frame()}, resets per-frame values (mouse delta, wheel,
     * text, key events) and applies every pending event.
     *
     * @return the number of event records applied
     */
    public int update() {
        frame++;
        deltaX = 0;
        deltaY = 0;
        wheelX = 0;
        wheelY = 0;
        keyEvents.clear();
        text.clear();
        int n = 0;
        int[] r = rec;
        while (in.poll(r, 0, InputRecord.INTS)) {
            apply(r);
            n++;
        }
        return n;
    }

    private void apply(int[] r) {
        lastEventNanos =
                (r[InputRecord.TIME_LO] & 0xFFFF_FFFFL) | ((long) r[InputRecord.TIME_HI] << 32);
        int a = r[InputRecord.A];
        int b = r[InputRecord.B];
        int c = r[InputRecord.C];
        int d = r[InputRecord.D];
        int e = r[InputRecord.E];
        switch (r[InputRecord.KIND]) {
            case InputRecord.KEY -> onKey(a, c, d);
            case InputRecord.TEXT -> {
                if (text.size() < text.capacity()) {
                    text.add(a);
                }
            }
            case InputRecord.TEXT_EDIT -> {
                if (a < 0) {
                    composition.clear();
                    compositionCaret = b;
                } else if (composition.size() < composition.capacity()) {
                    composition.add(a);
                }
            }
            case InputRecord.MOUSE_MOTION -> {
                mouseX = Float.intBitsToFloat(a);
                mouseY = Float.intBitsToFloat(b);
                deltaX += Float.intBitsToFloat(c);
                deltaY += Float.intBitsToFloat(d);
                // Button state comes from button events only, so edges stay consistent.
                lastDevice = DEVICE_KEYBOARD_MOUSE;
            }
            case InputRecord.MOUSE_BUTTON -> onMouseButton(a, b != 0, c, d, e);
            case InputRecord.MOUSE_WHEEL -> {
                wheelX += Float.intBitsToFloat(a);
                wheelY += Float.intBitsToFloat(b);
                mouseX = Float.intBitsToFloat(d);
                mouseY = Float.intBitsToFloat(e);
                lastDevice = DEVICE_KEYBOARD_MOUSE;
            }
            case InputRecord.WINDOW -> onWindow(a, b, c);
            case InputRecord.QUIT -> quitRequested = true;
            case InputRecord.PAD_ADDED -> {
                padConnected[a] = true;
                padJoystick[a] = b;
                padType[a] = c;
                padConnectFrame = frame;
            }
            case InputRecord.PAD_REMOVED -> {
                padConnected[a] = false;
                for (int i = 0; i < GamepadAxis.COUNT; i++) {
                    padAxes[a * GamepadAxis.COUNT + i] = 0;
                }
                for (int i = 0; i < GamepadButton.COUNT; i++) {
                    onPadButton(a, i, false);
                }
                padConnectFrame = frame;
                if (lastPad == a) {
                    lastPad = -1;
                }
            }
            case InputRecord.PAD_BUTTON -> {
                if (b < GamepadButton.COUNT) {
                    onPadButton(a, b, c != 0);
                    lastPad = a;
                    lastDevice = DEVICE_GAMEPAD;
                }
            }
            case InputRecord.PAD_AXIS -> {
                if (b < GamepadAxis.COUNT) {
                    float v = Math.max(-1f, c * AXIS_SCALE);
                    padAxes[a * GamepadAxis.COUNT + b] = v;
                    if (Math.abs(v) > 0.5f) {
                        lastPad = a;
                        lastDevice = DEVICE_GAMEPAD;
                    }
                }
            }
            default -> {}
        }
    }

    private void onKey(int scancode, int sdlMods, int flags) {
        if (scancode <= 0 || scancode >= Scancode.COUNT) {
            return;
        }
        boolean down = (flags & InputRecord.KEY_DOWN) != 0;
        boolean repeat = (flags & InputRecord.KEY_REPEAT) != 0;
        modifiers = Modifiers.fromSdl(sdlMods);
        if (down) {
            if (repeat) {
                keyRepeatFrame[scancode] = frame;
            } else if (!keyDown[scancode]) {
                keyDown[scancode] = true;
                keyPressedFrame[scancode] = frame;
                keyRepeatFrame[scancode] = frame;
            }
        } else if (keyDown[scancode]) {
            keyDown[scancode] = false;
            keyReleasedFrame[scancode] = frame;
        }
        if (keyEvents.size() + 3 <= keyEvents.capacity()) {
            keyEvents.add(scancode);
            keyEvents.add(flags);
            keyEvents.add(modifiers);
        }
        lastDevice = DEVICE_KEYBOARD_MOUSE;
    }

    private void onMouseButton(int button, boolean down, int clicks, int xBits, int yBits) {
        if (button <= 0 || button >= MouseButton.COUNT) {
            return;
        }
        mouseX = Float.intBitsToFloat(xBits);
        mouseY = Float.intBitsToFloat(yBits);
        int bit = 1 << (button - 1);
        if (down) {
            buttonPressedFrame[button] = frame;
            buttons |= bit;
            buttonClicks[button] = clicks;
            buttonPressedNanos[button] = lastEventNanos;
            buttonPressedX[button] = mouseX;
            buttonPressedY[button] = mouseY;
        } else {
            if ((buttons & bit) != 0) {
                buttonReleasedFrame[button] = frame;
            }
            buttons &= ~bit;
        }
        lastDevice = DEVICE_KEYBOARD_MOUSE;
    }

    private void onWindow(int type, int data1, int data2) {
        switch (type) {
            case SdlEventType.WINDOW_RESIZED -> {
                windowWidth = data1;
                windowHeight = data2;
                windowGeneration++;
            }
            case SdlEventType.WINDOW_PIXEL_SIZE_CHANGED -> {
                pixelWidth = data1;
                pixelHeight = data2;
                windowGeneration++;
            }
            case SdlEventType.WINDOW_DISPLAY_SCALE_CHANGED -> {
                if (data1 != 0) {
                    displayScale = Float.intBitsToFloat(data1);
                }
                windowGeneration++;
            }
            case SdlEventType.WINDOW_FOCUS_GAINED -> focused = true;
            case SdlEventType.WINDOW_FOCUS_LOST -> {
                focused = false;
                releaseAll();
            }
            case SdlEventType.WINDOW_MOUSE_ENTER -> mouseInWindow = true;
            case SdlEventType.WINDOW_MOUSE_LEAVE -> mouseInWindow = false;
            case SdlEventType.WINDOW_MINIMIZED -> minimized = true;
            case SdlEventType.WINDOW_RESTORED, SdlEventType.WINDOW_MAXIMIZED -> minimized = false;
            case SdlEventType.WINDOW_CLOSE_REQUESTED -> closeRequested = true;
            default -> {}
        }
    }

    /** Releases every held key and button (focus loss), reporting release edges. */
    private void releaseAll() {
        for (int sc = 0; sc < Scancode.COUNT; sc++) {
            if (keyDown[sc]) {
                keyDown[sc] = false;
                keyReleasedFrame[sc] = frame;
            }
        }
        for (int bt = 1; bt < MouseButton.COUNT; bt++) {
            if ((buttons & (1 << (bt - 1))) != 0) {
                buttonReleasedFrame[bt] = frame;
            }
        }
        buttons = 0;
        modifiers = 0;
    }

    private void onPadButton(int slot, int button, boolean down) {
        int i = slot * GamepadButton.COUNT + button;
        if (down && !padDown[i]) {
            padDown[i] = true;
            padPressedFrame[i] = frame;
        } else if (!down && padDown[i]) {
            padDown[i] = false;
            padReleasedFrame[i] = frame;
        }
    }

    // ---- Frame -------------------------------------------------------------------------------

    /** Returns the frame number, starting at 1 after the first {@link #update()}. */
    public int frame() {
        return frame;
    }

    /** Returns the SDL timestamp (ns) of the newest event applied. */
    public long lastEventNanos() {
        return lastEventNanos;
    }

    /** Returns {@link #DEVICE_KEYBOARD_MOUSE} or {@link #DEVICE_GAMEPAD}, for button prompts. */
    public int lastDevice() {
        return lastDevice;
    }

    // ---- Keyboard ----------------------------------------------------------------------------

    /** Returns {@code true} while the key at {@code scancode} is held. */
    public boolean isKeyDown(int scancode) {
        return keyDown[scancode];
    }

    /** Returns {@code true} if the key went down this frame. */
    public boolean wasKeyPressed(int scancode) {
        return keyPressedFrame[scancode] == frame;
    }

    /** Returns {@code true} if the key went up this frame. */
    public boolean wasKeyReleased(int scancode) {
        return keyReleasedFrame[scancode] == frame;
    }

    /** Returns {@code true} if the key went down or auto-repeated this frame (UI navigation). */
    public boolean wasKeyPressedOrRepeated(int scancode) {
        return keyRepeatFrame[scancode] == frame;
    }

    /** Returns the frame in which the key last went down (0 = never). */
    public int keyPressedFrame(int scancode) {
        return keyPressedFrame[scancode];
    }

    /** Returns the held modifiers as {@link Modifiers} bits. */
    public int modifiers() {
        return modifiers;
    }

    /** Returns the number of key events this frame, in order, for UI widgets. */
    public int keyEventCount() {
        return keyEvents.size() / 3;
    }

    /** Returns the scancode of key event {@code i} of this frame. */
    public int keyEventScancode(int i) {
        return keyEvents.get(3 * i);
    }

    /** Returns {@code true} if key event {@code i} was a press (or a repeat). */
    public boolean keyEventDown(int i) {
        return (keyEvents.get(3 * i + 1) & InputRecord.KEY_DOWN) != 0;
    }

    /** Returns {@code true} if key event {@code i} was an OS auto-repeat. */
    public boolean keyEventRepeat(int i) {
        return (keyEvents.get(3 * i + 1) & InputRecord.KEY_REPEAT) != 0;
    }

    /** Returns the {@link Modifiers} held during key event {@code i}. */
    public int keyEventModifiers(int i) {
        return keyEvents.get(3 * i + 2);
    }

    // ---- Text --------------------------------------------------------------------------------

    /** Returns the number of code points typed this frame (while text input is active). */
    public int textLength() {
        return text.size();
    }

    /** Returns typed code point {@code i} of this frame. */
    public int textCodePoint(int i) {
        return text.get(i);
    }

    /** Appends this frame's typed text to {@code sb} (allocates only if {@code sb} must grow). */
    public void appendText(StringBuilder sb) {
        for (int i = 0; i < text.size(); i++) {
            sb.appendCodePoint(text.get(i));
        }
    }

    /** Returns the length in code points of the current IME composition (0 if none). */
    public int compositionLength() {
        return composition.size();
    }

    /** Returns code point {@code i} of the current IME composition. */
    public int compositionCodePoint(int i) {
        return composition.get(i);
    }

    /** Returns the caret position within the IME composition. */
    public int compositionCaret() {
        return compositionCaret;
    }

    /**
     * Records whether UI wants text input. The request itself goes to the main thread through
     * {@code WindowCommands.postStartTextInput}; this flag lets game code know the mode it asked
     * for.
     */
    public void setTextInputRequested(boolean requested) {
        textInputRequested = requested;
        if (!requested) {
            composition.clear();
        }
    }

    /** Returns the last value passed to {@link #setTextInputRequested(boolean)}. */
    public boolean isTextInputRequested() {
        return textInputRequested;
    }

    // ---- Mouse -------------------------------------------------------------------------------

    /** Returns the cursor x in window coordinates. */
    public float mouseX() {
        return mouseX;
    }

    /** Returns the cursor y in window coordinates (down is positive). */
    public float mouseY() {
        return mouseY;
    }

    /** Returns the relative x motion this frame (unbounded in relative mouse mode). */
    public float mouseDeltaX() {
        return deltaX;
    }

    /** Returns the relative y motion this frame. */
    public float mouseDeltaY() {
        return deltaY;
    }

    /** Returns the horizontal scroll this frame (positive = right). */
    public float wheelX() {
        return wheelX;
    }

    /** Returns the vertical scroll this frame (positive = away from the user). */
    public float wheelY() {
        return wheelY;
    }

    /** Returns {@code true} while {@code button} ({@link MouseButton}) is held. */
    public boolean isMouseDown(int button) {
        return (buttons & (1 << (button - 1))) != 0;
    }

    /** Returns {@code true} if {@code button} went down this frame. */
    public boolean wasMousePressed(int button) {
        return buttonPressedFrame[button] == frame;
    }

    /** Returns {@code true} if {@code button} went up this frame. */
    public boolean wasMouseReleased(int button) {
        return buttonReleasedFrame[button] == frame;
    }

    /** Returns SDL's click count of the last press of {@code button} (2 = double click). */
    public int mouseClicks(int button) {
        return buttonClicks[button];
    }

    /** Returns the cursor x when {@code button} last went down. */
    public float mousePressX(int button) {
        return buttonPressedX[button];
    }

    /** Returns the cursor y when {@code button} last went down. */
    public float mousePressY(int button) {
        return buttonPressedY[button];
    }

    /** Returns the SDL timestamp (ns) of the last press of {@code button}. */
    public long mousePressNanos(int button) {
        return buttonPressedNanos[button];
    }

    /** Returns the held buttons as a mask (bit {@code button - 1}). */
    public int mouseButtons() {
        return buttons;
    }

    /** Returns {@code false} while the cursor is outside the window. */
    public boolean isMouseInWindow() {
        return mouseInWindow;
    }

    // ---- Window ------------------------------------------------------------------------------

    /** Returns the window width in window coordinates. */
    public int windowWidth() {
        return windowWidth;
    }

    /** Returns the window height in window coordinates. */
    public int windowHeight() {
        return windowHeight;
    }

    /** Returns the drawable width in pixels. */
    public int pixelWidth() {
        return pixelWidth;
    }

    /** Returns the drawable height in pixels. */
    public int pixelHeight() {
        return pixelHeight;
    }

    /** Returns the UI scale of the window's display. */
    public float displayScale() {
        return displayScale;
    }

    /** Returns a counter that changes whenever the window size or scale changes. */
    public int windowGeneration() {
        return windowGeneration;
    }

    /** Returns {@code true} while the window has focus. */
    public boolean isFocused() {
        return focused;
    }

    /** Returns {@code true} while the window is minimized. */
    public boolean isMinimized() {
        return minimized;
    }

    /** Returns {@code true} once the OS asked the application to quit. */
    public boolean isQuitRequested() {
        return quitRequested;
    }

    /** Returns {@code true} once the user clicked the window's close button. */
    public boolean isCloseRequested() {
        return closeRequested;
    }

    /** Clears {@link #isQuitRequested()} and {@link #isCloseRequested()} ("are you sure?"). */
    public void clearQuitRequest() {
        quitRequested = false;
        closeRequested = false;
    }

    // ---- Gamepads ----------------------------------------------------------------------------

    /** Returns {@code true} if a gamepad is connected in {@code slot} (0 to 7). */
    public boolean isPadConnected(int slot) {
        return padConnected[slot];
    }

    /** Returns the {@code SDL_GamepadType} of the pad in {@code slot}, for button prompts. */
    public int padType(int slot) {
        return padType[slot];
    }

    /** Returns the SDL joystick ID of the pad in {@code slot}. */
    public int padJoystickId(int slot) {
        return padJoystick[slot];
    }

    /** Returns the frame in which a pad last connected or disconnected. */
    public int padConnectionFrame() {
        return padConnectFrame;
    }

    /** Returns the slot of the last gamepad used, or -1. */
    public int lastPad() {
        return lastPad;
    }

    /** Returns the raw value of {@code axis} ({@link GamepadAxis}), -1..1, no dead zone. */
    public float padAxis(int slot, int axis) {
        return padAxes[slot * GamepadAxis.COUNT + axis];
    }

    /** Returns {@code true} while {@code button} ({@link GamepadButton}) is held. */
    public boolean isPadDown(int slot, int button) {
        return padDown[slot * GamepadButton.COUNT + button];
    }

    /** Returns {@code true} if {@code button} went down this frame. */
    public boolean wasPadPressed(int slot, int button) {
        return padPressedFrame[slot * GamepadButton.COUNT + button] == frame;
    }

    /** Returns {@code true} if {@code button} went up this frame. */
    public boolean wasPadReleased(int slot, int button) {
        return padReleasedFrame[slot * GamepadButton.COUNT + button] == frame;
    }
}
