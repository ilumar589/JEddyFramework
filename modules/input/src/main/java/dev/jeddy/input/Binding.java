package dev.jeddy.input;

import java.util.Locale;

/**
 * One physical input bound to an action, in the text form used by settings files and rebinding UIs
 * (DESIGN §12.2, TASKS M2-06).
 *
 * <pre>
 * Key:W              a key, by scancode name ({@link Scancode#name(int)})
 * Shift+Mouse:Left   a mouse button with a modifier chord (Shift, Ctrl, Alt, Gui)
 * Keys:S/W           a key pair as an axis: negative/positive
 * Wheel:Y            the scroll wheel as an axis (X or Y); Wheel:Up, Down, Left, Right as buttons
 * MouseAxis:X        mouse motion as an axis (X or Y), in window coordinates per frame
 * Pad:South          a gamepad button ({@link GamepadButton#name(int)})
 * PadAxis:RightX     a gamepad axis ({@link GamepadAxis#name(int)}); with + or - as a button
 * PadAxis:LeftY*-1   any axis can carry a scale
 * </pre>
 *
 * <p>Thread ownership: immutable.
 *
 * @param kind one of the {@code KIND_*} constants
 * @param code scancode, button or axis
 * @param code2 second key of a key pair, or the direction (+1/-1) of an axis used as a button
 * @param modifiers {@link Modifiers} bits that must be held
 * @param scale multiplier for axis values
 */
public record Binding(int kind, int code, int code2, int modifiers, float scale) {
    /** A key ({@code code} = scancode). */
    public static final int KIND_KEY = 1;

    /** A mouse button ({@code code} = {@link MouseButton}). */
    public static final int KIND_MOUSE = 2;

    /** The wheel: {@code code} 0 = Y axis, 1 = X axis, 2 = up, 3 = down, 4 = left, 5 = right. */
    public static final int KIND_WHEEL = 3;

    /** Mouse motion: {@code code} 0 = X, 1 = Y. */
    public static final int KIND_MOUSE_AXIS = 4;

    /** A gamepad button ({@code code} = {@link GamepadButton}). */
    public static final int KIND_PAD_BUTTON = 5;

    /**
     * A gamepad axis ({@code code} = {@link GamepadAxis}, {@code code2} = 0, or ±1 as a button).
     */
    public static final int KIND_PAD_AXIS = 6;

    /** Two keys as an axis ({@code code} = negative, {@code code2} = positive). */
    public static final int KIND_KEY_AXIS = 7;

    private static final String[] WHEEL = {"Y", "X", "Up", "Down", "Left", "Right"};
    private static final String[] MOUSE_AXES = {"X", "Y"};

    /** A key with optional modifiers. */
    public static Binding key(int scancode, int modifiers) {
        return new Binding(KIND_KEY, scancode, 0, modifiers, 1);
    }

    /** A key without modifiers. */
    public static Binding key(int scancode) {
        return key(scancode, Modifiers.NONE);
    }

    /** A mouse button with optional modifiers. */
    public static Binding mouse(int button, int modifiers) {
        return new Binding(KIND_MOUSE, button, 0, modifiers, 1);
    }

    /** A mouse button without modifiers. */
    public static Binding mouse(int button) {
        return mouse(button, Modifiers.NONE);
    }

    /** The vertical wheel as an axis. */
    public static Binding wheelY(float scale) {
        return new Binding(KIND_WHEEL, 0, 0, 0, scale);
    }

    /** Mouse motion as an axis: 0 = X, 1 = Y. */
    public static Binding mouseAxis(int axis, float scale) {
        return new Binding(KIND_MOUSE_AXIS, axis, 0, 0, scale);
    }

    /** A gamepad button. */
    public static Binding pad(int button) {
        return new Binding(KIND_PAD_BUTTON, button, 0, 0, 1);
    }

    /** A gamepad axis as an axis. */
    public static Binding padAxis(int axis, float scale) {
        return new Binding(KIND_PAD_AXIS, axis, 0, 0, scale);
    }

    /** A gamepad axis as a button, pressed past half travel in {@code direction} (+1 or -1). */
    public static Binding padAxisButton(int axis, int direction) {
        return new Binding(KIND_PAD_AXIS, axis, direction < 0 ? -1 : 1, 0, 1);
    }

    /** Two keys as an axis: {@code negative} gives -1, {@code positive} +1. */
    public static Binding keyAxis(int negative, int positive) {
        return new Binding(KIND_KEY_AXIS, negative, positive, 0, 1);
    }

    /**
     * Parses the text form (case-insensitive names).
     *
     * @throws IllegalArgumentException for unknown names
     */
    public static Binding parse(String text) {
        String s = text.trim();
        float scale = 1;
        int star = s.lastIndexOf('*');
        if (star > 0) {
            scale = Float.parseFloat(s.substring(star + 1).trim());
            s = s.substring(0, star).trim();
        }
        int modifiers = 0;
        int colon = s.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("binding needs Kind:Name: '" + text + "'");
        }
        String head = s.substring(0, colon);
        String name = s.substring(colon + 1).trim();
        int plus;
        while ((plus = head.indexOf('+')) >= 0) {
            String mod = head.substring(0, plus).trim().toLowerCase(Locale.ROOT);
            modifiers |=
                    switch (mod) {
                        case "shift" -> Modifiers.SHIFT;
                        case "ctrl" -> Modifiers.CTRL;
                        case "alt" -> Modifiers.ALT;
                        case "gui" -> Modifiers.GUI;
                        default ->
                                throw new IllegalArgumentException(
                                        "unknown modifier '" + mod + "' in '" + text + "'");
                    };
            head = head.substring(plus + 1);
        }
        String kind = head.trim().toLowerCase(Locale.ROOT);
        return switch (kind) {
            case "key" -> new Binding(KIND_KEY, scancode(name, text), 0, modifiers, scale);
            case "mouse" -> {
                int b = MouseButton.fromName(name);
                if (b == 0) {
                    throw new IllegalArgumentException("unknown mouse button in '" + text + "'");
                }
                yield new Binding(KIND_MOUSE, b, 0, modifiers, scale);
            }
            case "keys" -> {
                int slash = name.indexOf('/');
                if (slash < 0) {
                    throw new IllegalArgumentException("Keys:Negative/Positive: '" + text + "'");
                }
                yield new Binding(
                        KIND_KEY_AXIS,
                        scancode(name.substring(0, slash), text),
                        scancode(name.substring(slash + 1), text),
                        0,
                        scale);
            }
            case "wheel" ->
                    new Binding(KIND_WHEEL, indexOf(WHEEL, name, text), 0, modifiers, scale);
            case "mouseaxis" ->
                    new Binding(KIND_MOUSE_AXIS, indexOf(MOUSE_AXES, name, text), 0, 0, scale);
            case "pad" -> {
                int b = GamepadButton.fromName(name);
                if (b < 0) {
                    throw new IllegalArgumentException("unknown gamepad button in '" + text + "'");
                }
                yield new Binding(KIND_PAD_BUTTON, b, 0, 0, scale);
            }
            case "padaxis" -> {
                int direction = 0;
                if (name.endsWith("+") || name.endsWith("-")) {
                    direction = name.endsWith("+") ? 1 : -1;
                    name = name.substring(0, name.length() - 1);
                }
                int a = GamepadAxis.fromName(name);
                if (a < 0) {
                    throw new IllegalArgumentException("unknown gamepad axis in '" + text + "'");
                }
                yield new Binding(KIND_PAD_AXIS, a, direction, 0, scale);
            }
            default -> throw new IllegalArgumentException("unknown binding kind in '" + text + "'");
        };
    }

    private static int scancode(String name, String text) {
        int sc = Scancode.fromName(name.trim());
        if (sc == Scancode.UNKNOWN) {
            throw new IllegalArgumentException("unknown key '" + name + "' in '" + text + "'");
        }
        return sc;
    }

    private static int indexOf(String[] names, String name, String text) {
        for (int i = 0; i < names.length; i++) {
            if (names[i].equalsIgnoreCase(name)) {
                return i;
            }
        }
        throw new IllegalArgumentException("unknown name '" + name + "' in '" + text + "'");
    }

    /** Returns {@code true} if this binding produces an axis value rather than a button. */
    public boolean isAxis() {
        return switch (kind) {
            case KIND_KEY_AXIS, KIND_MOUSE_AXIS -> true;
            case KIND_WHEEL -> code < 2;
            case KIND_PAD_AXIS -> code2 == 0;
            default -> false;
        };
    }

    /** Returns the text form, which {@link #parse(String)} reads back. */
    @Override
    public String toString() {
        String body =
                switch (kind) {
                    case KIND_KEY -> "Key:" + Scancode.name(code);
                    case KIND_MOUSE -> "Mouse:" + MouseButton.name(code);
                    case KIND_KEY_AXIS ->
                            "Keys:" + Scancode.name(code) + "/" + Scancode.name(code2);
                    case KIND_WHEEL -> "Wheel:" + WHEEL[code];
                    case KIND_MOUSE_AXIS -> "MouseAxis:" + MOUSE_AXES[code];
                    case KIND_PAD_BUTTON -> "Pad:" + GamepadButton.name(code);
                    case KIND_PAD_AXIS ->
                            "PadAxis:"
                                    + GamepadAxis.name(code)
                                    + (code2 > 0 ? "+" : code2 < 0 ? "-" : "");
                    default -> "?";
                };
        String text = Modifiers.toString(modifiers) + body;
        return scale == 1 ? text : text + "*" + scale;
    }

    /** Returns a label for the UI, using the keyboard layout for keys. */
    public String label(KeyLabels labels) {
        return switch (kind) {
            case KIND_KEY -> Modifiers.toString(modifiers) + labels.label(code);
            case KIND_KEY_AXIS -> labels.label(code) + "/" + labels.label(code2);
            default -> toString();
        };
    }
}
