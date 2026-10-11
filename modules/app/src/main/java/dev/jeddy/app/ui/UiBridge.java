package dev.jeddy.app.ui;

import dev.jeddy.app.AppContext;
import dev.jeddy.input.GamepadButton;
import dev.jeddy.input.Input;
import dev.jeddy.input.MouseButton;
import dev.jeddy.ui.Ui;
import dev.jeddy.ui.UiInput;
import dev.jeddy.ui.UiKey;
import dev.jeddy.ui.Widgets;
import dev.jeddy.window.WindowCommands;

/**
 * Connects a {@link Ui} to the application (TASKS M13-05, DESIGN §18 "Threads"): copies the frame's
 * {@link Input} into the context's {@link UiInput} (pointer, buttons, wheel, keys, typed text,
 * modifiers, window size and display scale), begins the UI frame, and asks the main thread to start
 * or stop SDL text input as keyboard focus comes and goes, with the focused field's rectangle so an
 * IME can place its candidate window.
 *
 * <pre>{@code
 * public void update(AppContext ctx, float dt, float alpha) {
 *     bridge.beginFrame(dt);              // input → UiInput, ui.beginFrame
 *     ... widgets ...
 *     if (!ui.wantsMouse()) pickWorld();  // the UI did not consume the pointer
 * }
 * public void extract(AppContext ctx, int index) {
 *     ui.endFrame(packets.packet(index).ui);
 * }
 * }</pre>
 *
 * <p>Thread ownership: the game thread.
 */
public final class UiBridge {
    private final AppContext ctx;
    private final Ui ui;
    private final UiInput in = new UiInput();
    private boolean textInput;
    private int fieldX = -1;
    private int fieldY;
    private int fieldW;
    private int fieldH;

    /** Creates the bridge for {@code ui}. */
    public UiBridge(AppContext ctx, Ui ui) {
        this.ctx = ctx;
        this.ui = ui;
    }

    /** Returns the UI input the bridge fills. */
    public UiInput input() {
        return in;
    }

    /** Returns a clipboard for {@link Widgets#clipboard} backed by the system clipboard. */
    public Widgets.Clipboard clipboard() {
        return new Widgets.Clipboard() {
            @Override
            public void set(CharSequence text) {
                ctx.clipboard().request(ctx.mainThread(), text.toString());
            }

            @Override
            public CharSequence get() {
                return ctx.clipboard().text();
            }
        };
    }

    /** Copies this frame's input, begins the UI frame and updates the text-input request. */
    public void beginFrame(float dt) {
        copy(ctx.input(), in, dt);
        ui.beginFrame(in);
        boolean want = ui.wantsKeyboard();
        if (want) {
            // The focused field's rectangle, back in window coordinates for SDL's text-input area
            // (the IME candidate window goes next to it).
            float k = 1 / ctx.input().uiScale();
            int r = ui.lastRecord(ui.focusedId());
            int x = r >= 0 ? (int) (ui.recordX(r) * k) : 0;
            int y = r >= 0 ? (int) (ui.recordY(r) * k) : 0;
            int w = r >= 0 ? (int) (ui.recordWidth(r) * k) : 0;
            int h = r >= 0 ? (int) (ui.recordHeight(r) * k) : 0;
            if (!textInput || x != fieldX || y != fieldY || w != fieldW || h != fieldH) {
                fieldX = x;
                fieldY = y;
                fieldW = w;
                fieldH = h;
                WindowCommands.postStartTextInput(ctx.mainThread(), x, y, w, h, 0);
            }
        } else if (textInput) {
            WindowCommands.postStopTextInput(ctx.mainThread());
            fieldX = -1;
        }
        if (want != textInput) {
            textInput = want;
            ctx.input().setTextInputRequested(want);
        }
    }

    /**
     * Copies one frame of {@code src} into {@code dst}, in UI logical pixels ({@link
     * Input#uiScale}); allocates nothing.
     */
    public static void copy(Input src, UiInput dst, float dt) {
        float k = src.uiScale();
        dst.clearEvents();
        dst.mouseX = src.mouseX() * k;
        dst.mouseY = src.mouseY() * k;
        dst.mouseInWindow = src.isMouseInWindow() && src.isFocused();
        dst.buttonsDown = buttons(src, 0);
        dst.buttonsPressed = buttons(src, 1);
        dst.buttonsReleased = buttons(src, 2);
        dst.wheelX = src.wheelX();
        dst.wheelY = src.wheelY();
        dst.modifiers = src.modifiers(); // the same bit values as UiKey.MOD_*
        dst.width = src.windowWidth() * k;
        dst.height = src.windowHeight() * k;
        dst.displayScale = src.displayScale();
        dst.dt = dt;
        dst.navigation = src.lastDevice() == Input.DEVICE_GAMEPAD;
        int keys = src.keyEventCount();
        for (int i = 0; i < keys; i++) {
            dst.key(
                    src.keyEventScancode(i),
                    src.keyEventModifiers(i),
                    src.keyEventDown(i),
                    src.keyEventRepeat(i));
        }
        int text = src.textLength();
        for (int i = 0; i < text; i++) {
            dst.text(src.textCodePoint(i));
        }
        // Gamepad navigation: the d-pad moves focus like the arrows, South activates, East
        // escapes (DESIGN §18 "Interaction").
        if (src.lastDevice() == Input.DEVICE_GAMEPAD) {
            int pad = src.lastPad();
            if (pad >= 0 && src.isPadConnected(pad)) {
                padKey(src, dst, pad, GamepadButton.DPAD_UP, UiKey.UP);
                padKey(src, dst, pad, GamepadButton.DPAD_DOWN, UiKey.DOWN);
                padKey(src, dst, pad, GamepadButton.DPAD_LEFT, UiKey.LEFT);
                padKey(src, dst, pad, GamepadButton.DPAD_RIGHT, UiKey.RIGHT);
                padKey(src, dst, pad, GamepadButton.SOUTH, UiKey.RETURN);
                padKey(src, dst, pad, GamepadButton.EAST, UiKey.ESCAPE);
            }
        }
    }

    private static void padKey(Input src, UiInput dst, int pad, int button, int scancode) {
        if (src.wasPadPressed(pad, button)) {
            dst.key(scancode, 0, true, false);
        }
    }

    private static int buttons(Input src, int edge) {
        int bits = 0;
        if (button(src, MouseButton.LEFT, edge)) {
            bits |= UiInput.LEFT;
        }
        if (button(src, MouseButton.MIDDLE, edge)) {
            bits |= UiInput.MIDDLE;
        }
        if (button(src, MouseButton.RIGHT, edge)) {
            bits |= UiInput.RIGHT;
        }
        return bits;
    }

    private static boolean button(Input src, int b, int edge) {
        return switch (edge) {
            case 0 -> src.isMouseDown(b);
            case 1 -> src.wasMousePressed(b);
            default -> src.wasMouseReleased(b);
        };
    }
}
