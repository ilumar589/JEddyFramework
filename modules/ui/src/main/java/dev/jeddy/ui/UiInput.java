package dev.jeddy.ui;

import java.util.Arrays;

/**
 * One frame of input as the UI sees it (TASKS M13-05, DESIGN §18): the pointer, its buttons as
 * edges and levels, the wheel, key events, typed text, the window size and the display scale. The
 * game fills it before {@link Ui#beginFrame} (the {@code app} module copies it from {@code Input})
 * and tests script it. Positions are window coordinates (logical pixels, y down).
 *
 * <p>Thread ownership: the thread that drives the {@link Ui}. Allocates nothing once the event
 * arrays are large enough.
 */
public final class UiInput {
    /** Mouse button bit: left. */
    public static final int LEFT = 1;

    /** Mouse button bit: middle. */
    public static final int MIDDLE = 2;

    /** Mouse button bit: right. */
    public static final int RIGHT = 4;

    /** Pointer position. */
    public float mouseX;

    /** Pointer position. */
    public float mouseY;

    /** Buttons held this frame ({@code LEFT | MIDDLE | RIGHT} bits). */
    public int buttonsDown;

    /** Buttons pressed this frame (edges). */
    public int buttonsPressed;

    /** Buttons released this frame (edges). */
    public int buttonsReleased;

    /** Wheel motion this frame, in lines (positive away from the user). */
    public float wheelX;

    /** Wheel motion this frame, in lines (positive away from the user). */
    public float wheelY;

    /** Whether the pointer is inside the window. */
    public boolean mouseInWindow = true;

    /** Modifier bits ({@link UiKey#MOD_SHIFT} and friends) held at the end of the frame. */
    public int modifiers;

    /** Window width in logical pixels. */
    public float width;

    /** Window height in logical pixels. */
    public float height;

    /** Physical pixels per logical pixel. */
    public float displayScale = 1;

    /** Seconds since the previous frame. */
    public float dt;

    /** Whether the game wants the UI to show keyboard focus (a gamepad or Tab was used). */
    public boolean navigation;

    // Key events: scancode, modifiers, flags (bit 0 down, bit 1 repeat).
    private int[] keys = new int[3 * 64];
    private int keyCount;
    private int[] text = new int[64];
    private int textCount;

    /** Creates an empty frame. */
    public UiInput() {}

    /** Clears the per-frame edges and events; levels (position, buttons held) stay. */
    public void clearEvents() {
        buttonsPressed = 0;
        buttonsReleased = 0;
        wheelX = 0;
        wheelY = 0;
        keyCount = 0;
        textCount = 0;
    }

    /** Adds a key event; {@code down} is {@code false} for a release. */
    public void key(int scancode, int modifiers, boolean down, boolean repeat) {
        if (keyCount * 3 == keys.length) {
            keys = Arrays.copyOf(keys, keys.length * 2);
        }
        int o = keyCount++ * 3;
        keys[o] = scancode;
        keys[o + 1] = modifiers;
        keys[o + 2] = (down ? 1 : 0) | (repeat ? 2 : 0);
    }

    /** Adds a typed code point. */
    public void text(int codePoint) {
        if (textCount == text.length) {
            text = Arrays.copyOf(text, text.length * 2);
        }
        text[textCount++] = codePoint;
    }

    /** Returns the number of key events. */
    public int keyCount() {
        return keyCount;
    }

    /** Returns the scancode of key event {@code i}. */
    public int keyScancode(int i) {
        return keys[i * 3];
    }

    /** Returns the modifiers of key event {@code i}. */
    public int keyModifiers(int i) {
        return keys[i * 3 + 1];
    }

    /** Returns whether key event {@code i} is a press (or a repeat), not a release. */
    public boolean keyDown(int i) {
        return (keys[i * 3 + 2] & 1) != 0;
    }

    /** Returns whether key event {@code i} is a repeat. */
    public boolean keyRepeat(int i) {
        return (keys[i * 3 + 2] & 2) != 0;
    }

    /** Returns the number of typed code points. */
    public int textCount() {
        return textCount;
    }

    /** Returns typed code point {@code i}. */
    public int textCodePoint(int i) {
        return text[i];
    }

    /** Returns whether a press (or repeat) of {@code scancode} happened this frame. */
    public boolean pressed(int scancode) {
        for (int i = 0; i < keyCount; i++) {
            if (keys[i * 3] == scancode && (keys[i * 3 + 2] & 1) != 0) {
                return true;
            }
        }
        return false;
    }

    /** Scripts a press and release of the left button at {@code (x, y)} (tests). */
    public void click(float x, float y) {
        mouseX = x;
        mouseY = y;
        buttonsPressed |= LEFT;
        buttonsReleased |= LEFT;
    }
}
