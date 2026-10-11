package dev.jeddy.editor;

import dev.jeddy.input.Input;
import dev.jeddy.input.Modifiers;
import dev.jeddy.input.MouseButton;
import dev.jeddy.ui.Ui;

/**
 * One frame of pointer input for the {@link Viewport}, copied out of {@link Input} by the editor
 * (or made up by a test): position and movement in window points, which buttons are held and which
 * went down this frame, the wheel, the modifiers, the window size, and whether the UI has the
 * pointer (then the viewport ignores it). Keys are handled by the {@link Editor}.
 *
 * <p>Thread ownership: the game thread.
 */
public final class ViewportInput {
    public float mouseX, mouseY;
    public float deltaX, deltaY;
    public float wheelY;
    public boolean leftDown, leftPressed;
    public boolean rightDown, rightPressed;
    public boolean middleDown, middlePressed;
    public boolean shift, ctrl;
    public int windowWidth = 1, windowHeight = 1;
    public boolean uiWantsPointer;

    /** Creates an empty frame. */
    public ViewportInput() {}

    /** Fills this frame from {@code in} and {@code ui}. */
    public void copyFrom(Input in, Ui ui) {
        mouseX = in.mouseX();
        mouseY = in.mouseY();
        deltaX = in.mouseDeltaX();
        deltaY = in.mouseDeltaY();
        wheelY = in.wheelY();
        leftDown = in.isMouseDown(MouseButton.LEFT);
        leftPressed = in.wasMousePressed(MouseButton.LEFT);
        rightDown = in.isMouseDown(MouseButton.RIGHT);
        rightPressed = in.wasMousePressed(MouseButton.RIGHT);
        middleDown = in.isMouseDown(MouseButton.MIDDLE);
        middlePressed = in.wasMousePressed(MouseButton.MIDDLE);
        int mods = in.modifiers();
        shift = (mods & Modifiers.SHIFT) != 0;
        ctrl = (mods & Modifiers.CTRL) != 0;
        windowWidth = Math.max(1, in.windowWidth());
        windowHeight = Math.max(1, in.windowHeight());
        uiWantsPointer = ui.wantsMouse();
    }

    /** Clears the per-frame events (presses, deltas, wheel) for the next frame of a test. */
    public void endFrame() {
        leftPressed = false;
        rightPressed = false;
        middlePressed = false;
        deltaX = 0;
        deltaY = 0;
        wheelY = 0;
    }
}
