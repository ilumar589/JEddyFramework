package dev.jeddy.input;

/**
 * A gamepad-driven cursor for playing a mouse-centric CRPG on a controller (TASKS M2-08). The left
 * stick moves it with a response curve (precise when barely tilted, fast at full tilt), and face
 * buttons act as mouse buttons: South = left, East = right, North = middle.
 *
 * <p>It becomes the pointer of {@link CrpgGestures} while the last device used is a gamepad. The
 * game draws it, or keeps the OS cursor in sync by posting {@code WindowCommands.postWarpMouse}
 * when it moved. Allocates nothing.
 *
 * <p>Thread ownership: game thread only.
 */
public final class VirtualCursor {
    private float x;
    private float y;
    private float speed = 900f;
    private float exponent = 2.2f;
    private float deadZone = 0.15f;
    private boolean enabled = true;
    private boolean moved;
    private int stickX = GamepadAxis.LEFT_X;
    private int stickY = GamepadAxis.LEFT_Y;

    /** Creates a cursor at the origin. */
    public VirtualCursor() {}

    /** Speed at full tilt in window coordinates per second before display scaling (default 900). */
    public VirtualCursor speed(float unitsPerSecond) {
        this.speed = unitsPerSecond;
        return this;
    }

    /** Response curve exponent; higher is more precise near the center (default 2.2). */
    public VirtualCursor exponent(float exponent) {
        this.exponent = exponent;
        return this;
    }

    /** Stick dead zone (default 0.15). */
    public VirtualCursor deadZone(float deadZone) {
        this.deadZone = deadZone;
        return this;
    }

    /** Uses the right stick instead of the left one. */
    public VirtualCursor useRightStick(boolean right) {
        stickX = right ? GamepadAxis.RIGHT_X : GamepadAxis.LEFT_X;
        stickY = right ? GamepadAxis.RIGHT_Y : GamepadAxis.LEFT_Y;
        return this;
    }

    /** Enables or disables the cursor. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** Places the cursor, for example where the mouse was when the player picked up the pad. */
    public void warpTo(float x, float y) {
        this.x = x;
        this.y = y;
    }

    /** Returns {@code true} when enabled and the player's last input came from a gamepad. */
    public boolean isActive(Input input) {
        return enabled && input.lastDevice() == Input.DEVICE_GAMEPAD && input.lastPad() >= 0;
    }

    /**
     * Moves the cursor from the last-used pad's stick.
     *
     * @param dt frame time in seconds
     */
    public void update(Input input, float dt) {
        moved = false;
        int pad = input.lastPad();
        if (!enabled || pad < 0 || !input.isPadConnected(pad)) {
            return;
        }
        if (input.lastDevice() != Input.DEVICE_GAMEPAD) {
            x = input.mouseX(); // follow the mouse so switching back to the pad is seamless
            y = input.mouseY();
            return;
        }
        float sx = input.padAxis(pad, stickX);
        float sy = input.padAxis(pad, stickY);
        float len = (float) Math.sqrt(sx * sx + sy * sy);
        if (len <= deadZone) {
            return;
        }
        float norm = Math.min(1f, (len - deadZone) / (1f - deadZone));
        float magnitude = (float) Math.pow(norm, exponent) * speed * input.displayScale() * dt;
        x += sx / len * magnitude;
        y += sy / len * magnitude;
        x = Math.max(0, Math.min(input.windowWidth() - 1, x));
        y = Math.max(0, Math.min(input.windowHeight() - 1, y));
        moved = true;
    }

    /** Returns {@code true} if the cursor moved in the last {@link #update}. */
    public boolean moved() {
        return moved;
    }

    /** Returns x in window coordinates. */
    public float x() {
        return x;
    }

    /** Returns y in window coordinates. */
    public float y() {
        return y;
    }

    private static int padButton(int mouseButton) {
        return switch (mouseButton) {
            case MouseButton.LEFT -> GamepadButton.SOUTH;
            case MouseButton.RIGHT -> GamepadButton.EAST;
            case MouseButton.MIDDLE -> GamepadButton.NORTH;
            default -> -1;
        };
    }

    /** Returns {@code true} while the pad button standing in for {@code mouseButton} is held. */
    public boolean isDown(Input input, int mouseButton) {
        int b = padButton(mouseButton);
        int pad = input.lastPad();
        return b >= 0 && pad >= 0 && input.isPadDown(pad, b);
    }

    /** Returns {@code true} if the pad button standing in for {@code mouseButton} went down. */
    public boolean wasPressed(Input input, int mouseButton) {
        int b = padButton(mouseButton);
        int pad = input.lastPad();
        return b >= 0 && pad >= 0 && input.wasPadPressed(pad, b);
    }

    /** Returns {@code true} if the pad button standing in for {@code mouseButton} went up. */
    public boolean wasReleased(Input input, int mouseButton) {
        int b = padButton(mouseButton);
        int pad = input.lastPad();
        return b >= 0 && pad >= 0 && input.wasPadReleased(pad, b);
    }
}
