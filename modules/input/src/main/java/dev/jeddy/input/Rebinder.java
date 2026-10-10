package dev.jeddy.input;

/**
 * Captures the next input the player presses, for "press a key" rebinding screens (TASKS M2-06).
 *
 * <p>Modifier chords are supported: holding Shift and pressing A yields {@code Shift+Key:A};
 * pressing and releasing Shift alone yields {@code Key:LSHIFT}. Escape cancels. In axis mode a
 * stick or the wheel gives an axis binding, and two keys pressed one after the other give a key
 * pair (negative first).
 *
 * <pre>{@code
 * rebinder.start(false);
 * // every frame, after input.update():
 * if (rebinder.poll(input)) {
 *     Binding b = rebinder.result(); // null if cancelled
 * }
 * }</pre>
 *
 * <p>Allocates one {@link Binding} when it finishes; otherwise nothing.
 *
 * <p>Thread ownership: game thread only.
 */
public final class Rebinder {
    private static final float STICK_THRESHOLD = 0.6f;

    private boolean active;
    private boolean axisMode;
    private int cancelKey = Scancode.ESCAPE;
    private int heldModifierKey;
    private int firstAxisKey;
    private boolean otherKeySinceModifier;
    private boolean acceptMouse = true;
    private boolean acceptGamepad = true;
    private Binding result;
    private int startFrame;

    /** Creates an idle rebinder. */
    public Rebinder() {}

    /** Sets the key that cancels capture (default Escape; 0 = none). */
    public Rebinder cancelKey(int scancode) {
        this.cancelKey = scancode;
        return this;
    }

    /** Whether mouse buttons can be captured (default {@code true}). */
    public Rebinder acceptMouse(boolean accept) {
        this.acceptMouse = accept;
        return this;
    }

    /** Whether gamepad buttons and axes can be captured (default {@code true}). */
    public Rebinder acceptGamepad(boolean accept) {
        this.acceptGamepad = accept;
        return this;
    }

    /**
     * Starts capturing. Inputs from the frame in which this is called are ignored, so the click
     * that opened the prompt is not captured.
     *
     * @param axis capture an axis binding instead of a button binding
     */
    public void start(boolean axis) {
        active = true;
        axisMode = axis;
        result = null;
        heldModifierKey = 0;
        firstAxisKey = 0;
        otherKeySinceModifier = false;
        startFrame = -1;
    }

    /** Returns {@code true} while capturing. */
    public boolean isActive() {
        return active;
    }

    /** Stops capturing without a result. */
    public void cancel() {
        active = false;
        result = null;
    }

    /** Returns the captured binding, or {@code null} if capture was cancelled. */
    public Binding result() {
        return result;
    }

    /**
     * Looks at this frame's input. Returns {@code true} once capture ended (with a result or
     * cancelled).
     */
    public boolean poll(Input input) {
        if (!active) {
            return false;
        }
        if (startFrame < 0) {
            startFrame = input.frame();
            return false;
        }
        if (cancelKey != 0 && input.wasKeyPressed(cancelKey)) {
            return finish(null);
        }
        int mods = input.modifiers();
        for (int sc = 1; sc < Scancode.COUNT; sc++) {
            if (sc == cancelKey) {
                continue;
            }
            if (input.wasKeyPressed(sc)) {
                if (Modifiers.ofScancode(sc) != 0 && !axisMode) {
                    heldModifierKey = sc;
                    otherKeySinceModifier = false;
                } else {
                    otherKeySinceModifier = true;
                    if (axisMode) {
                        if (firstAxisKey == 0) {
                            firstAxisKey = sc; // the negative key; wait for the positive one
                            continue;
                        }
                        return finish(Binding.keyAxis(firstAxisKey, sc));
                    }
                    return finish(Binding.key(sc, mods & ~Modifiers.ofScancode(sc)));
                }
            }
        }
        if (heldModifierKey != 0
                && input.wasKeyReleased(heldModifierKey)
                && !otherKeySinceModifier) {
            return finish(Binding.key(heldModifierKey));
        }
        if (acceptMouse) {
            if (axisMode) {
                if (input.wheelY() != 0) {
                    return finish(Binding.wheelY(1));
                }
            } else {
                for (int b = 1; b < MouseButton.COUNT; b++) {
                    if (input.wasMousePressed(b)) {
                        return finish(Binding.mouse(b, mods));
                    }
                }
                if (input.wheelY() != 0) {
                    return finish(
                            new Binding(
                                    Binding.KIND_WHEEL, input.wheelY() > 0 ? 2 : 3, 0, mods, 1));
                }
            }
        }
        if (acceptGamepad) {
            for (int s = 0; s < EventPump.MAX_GAMEPADS; s++) {
                if (!input.isPadConnected(s)) {
                    continue;
                }
                if (!axisMode) {
                    for (int b = 0; b < GamepadButton.COUNT; b++) {
                        if (input.wasPadPressed(s, b)) {
                            return finish(Binding.pad(b));
                        }
                    }
                }
                for (int a = 0; a < GamepadAxis.COUNT; a++) {
                    float v = input.padAxis(s, a);
                    if (Math.abs(v) > STICK_THRESHOLD) {
                        return finish(
                                axisMode
                                        ? Binding.padAxis(a, 1)
                                        : Binding.padAxisButton(a, v > 0 ? 1 : -1));
                    }
                }
            }
        }
        return false;
    }

    private boolean finish(Binding binding) {
        result = binding;
        active = false;
        return true;
    }
}
