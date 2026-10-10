package dev.jeddy.input;

/**
 * Mouse gestures of an isometric CRPG (DESIGN §12.2, TASKS M2-07): click vs drag, double click, box
 * selection, edge scrolling, and camera rotate, pan and zoom input.
 *
 * <ul>
 *   <li><b>Click vs drag:</b> a press that moves less than {@link #dragThreshold} (window
 *       coordinates, scaled by the display scale) before release is a click; otherwise it is a
 *       drag. With the select button (left) a drag is a box selection.
 *   <li><b>Double click:</b> uses the OS click count reported by SDL, so it honors the user's
 *       double-click speed setting.
 *   <li><b>Edge scrolling:</b> the cursor within {@link #edgeMargin} of a window edge gives a
 *       scroll direction from -1 to 1, ramping up towards the edge. Only while the window has focus
 *       and the cursor is inside it.
 *   <li><b>Camera:</b> dragging with the rotate button (middle) gives a rotation delta; the pan
 *       button (none by default) gives a pan delta; the wheel gives zoom steps.
 * </ul>
 *
 * When a {@link VirtualCursor} is attached and the player is using a gamepad, the virtual cursor
 * position and its buttons replace the mouse.
 *
 * <p>Gestures produce screen positions only; picking against the world is gameplay's job. Call
 * {@link #update(Input)} once per game frame after {@link Input#update()}. Allocates nothing.
 *
 * <p>Thread ownership: game thread only.
 */
public final class CrpgGestures {
    private float dragThreshold = 6f;
    private float edgeMargin = 12f;
    private boolean edgeScrollEnabled = true;
    private int selectButton = MouseButton.LEFT;
    private int commandButton = MouseButton.RIGHT;
    private int rotateButton = MouseButton.MIDDLE;
    private int panButton;
    private VirtualCursor virtualCursor;

    private float x;
    private float y;
    private final boolean[] pressed = new boolean[MouseButton.COUNT];
    private final boolean[] dragging = new boolean[MouseButton.COUNT];
    private final float[] startX = new float[MouseButton.COUNT];
    private final float[] startY = new float[MouseButton.COUNT];
    private final int[] clickFrame = new int[MouseButton.COUNT];
    private final int[] doubleClickFrame = new int[MouseButton.COUNT];
    private final int[] dragEndFrame = new int[MouseButton.COUNT];
    private final float[] clickX = new float[MouseButton.COUNT];
    private final float[] clickY = new float[MouseButton.COUNT];
    private int frame;
    private float lastX;
    private float lastY;
    private float edgeX;
    private float edgeY;
    private float rotate;
    private float panX;
    private float panY;
    private float zoom;
    private int boxFinishedFrame;

    /** Creates gestures with the defaults described above. */
    public CrpgGestures() {}

    /** Drag threshold in window coordinates before display scaling (default 6). */
    public CrpgGestures dragThreshold(float threshold) {
        this.dragThreshold = threshold;
        return this;
    }

    /** Width of the edge-scroll band in window coordinates before display scaling (default 12). */
    public CrpgGestures edgeMargin(float margin) {
        this.edgeMargin = margin;
        return this;
    }

    /** Turns edge scrolling on or off (default on; usually off in windowed mode without a grab). */
    public CrpgGestures edgeScroll(boolean enabled) {
        this.edgeScrollEnabled = enabled;
        return this;
    }

    /** Buttons for selection, commands, camera rotation and panning (0 = none). */
    public CrpgGestures buttons(int select, int command, int rotate, int pan) {
        this.selectButton = select;
        this.commandButton = command;
        this.rotateButton = rotate;
        this.panButton = pan;
        return this;
    }

    /** Uses {@code cursor} instead of the mouse while the player is on a gamepad (or null). */
    public CrpgGestures virtualCursor(VirtualCursor cursor) {
        this.virtualCursor = cursor;
        return this;
    }

    /** Updates every gesture from this frame's input. */
    public void update(Input input) {
        frame = input.frame();
        boolean virtual = virtualCursor != null && virtualCursor.isActive(input);
        lastX = x;
        lastY = y;
        if (virtual) {
            x = virtualCursor.x();
            y = virtualCursor.y();
        } else {
            x = input.mouseX();
            y = input.mouseY();
        }
        float scale = Math.max(0.5f, input.displayScale());
        float threshold = dragThreshold * scale;
        for (int b = 1; b < MouseButton.COUNT; b++) {
            boolean wentDown;
            boolean wentUp;
            boolean isDown;
            int clicks;
            float pressX;
            float pressY;
            if (virtual) {
                wentDown = virtualCursor.wasPressed(input, b);
                wentUp = virtualCursor.wasReleased(input, b);
                isDown = virtualCursor.isDown(input, b);
                clicks = 1;
                pressX = x;
                pressY = y;
            } else {
                wentDown = input.wasMousePressed(b);
                wentUp = input.wasMouseReleased(b);
                isDown = input.isMouseDown(b);
                clicks = input.mouseClicks(b);
                pressX = input.mousePressX(b);
                pressY = input.mousePressY(b);
            }
            if (wentDown) {
                pressed[b] = true;
                dragging[b] = false;
                startX[b] = pressX;
                startY[b] = pressY;
            }
            if (pressed[b] && !dragging[b]) {
                float dx = x - startX[b];
                float dy = y - startY[b];
                if (dx * dx + dy * dy > threshold * threshold) {
                    dragging[b] = true;
                }
            }
            if (wentUp && pressed[b]) {
                if (dragging[b]) {
                    dragEndFrame[b] = frame;
                    if (b == selectButton) {
                        boxFinishedFrame = frame;
                    }
                } else {
                    clickFrame[b] = frame;
                    clickX[b] = startX[b];
                    clickY[b] = startY[b];
                    if (clicks >= 2) {
                        doubleClickFrame[b] = frame;
                    }
                }
                pressed[b] = isDown; // pressed again within the same frame
                dragging[b] = false;
                if (isDown) {
                    startX[b] = x;
                    startY[b] = y;
                }
            }
        }
        // Camera.
        rotate = 0;
        panX = 0;
        panY = 0;
        if (rotateButton != 0 && pressed[rotateButton]) {
            rotate = virtual ? 0 : input.mouseDeltaX();
        }
        if (panButton != 0 && pressed[panButton]) {
            panX = virtual ? 0 : input.mouseDeltaX();
            panY = virtual ? 0 : input.mouseDeltaY();
        }
        zoom = input.wheelY();
        // Edge scrolling.
        edgeX = 0;
        edgeY = 0;
        if (edgeScrollEnabled
                && input.isFocused()
                && (virtual || input.isMouseInWindow())
                && !anyDragging()) {
            float margin = edgeMargin * scale;
            edgeX = edge(x, input.windowWidth(), margin);
            edgeY = edge(y, input.windowHeight(), margin);
        }
    }

    private static float edge(float pos, int size, float margin) {
        if (size <= 0 || margin <= 0) {
            return 0;
        }
        if (pos < margin) {
            return -Math.min(1f, (margin - pos) / margin);
        }
        if (pos > size - margin) {
            return Math.min(1f, (pos - (size - margin)) / margin);
        }
        return 0;
    }

    private boolean anyDragging() {
        return dragging[selectButton] || (panButton != 0 && dragging[panButton]);
    }

    /** Returns the pointer x (mouse or virtual cursor), window coordinates. */
    public float x() {
        return x;
    }

    /** Returns the pointer y, window coordinates. */
    public float y() {
        return y;
    }

    /** Returns how far the pointer moved this frame in x. */
    public float movedX() {
        return x - lastX;
    }

    /** Returns how far the pointer moved this frame in y. */
    public float movedY() {
        return y - lastY;
    }

    /** Returns {@code true} if {@code button} completed a click (no drag) this frame. */
    public boolean clicked(int button) {
        return clickFrame[button] == frame;
    }

    /** Returns {@code true} if the select button clicked this frame. */
    public boolean selectClicked() {
        return clicked(selectButton);
    }

    /** Returns {@code true} if the command button clicked this frame. */
    public boolean commandClicked() {
        return clicked(commandButton);
    }

    /** Returns {@code true} if {@code button}'s click this frame was a double click. */
    public boolean doubleClicked(int button) {
        return doubleClickFrame[button] == frame;
    }

    /** Returns where the click of {@code button} started (x). */
    public float clickX(int button) {
        return clickX[button];
    }

    /** Returns where the click of {@code button} started (y). */
    public float clickY(int button) {
        return clickY[button];
    }

    /** Returns {@code true} while {@code button} is dragging (moved past the threshold). */
    public boolean isDragging(int button) {
        return dragging[button];
    }

    /** Returns {@code true} if a drag with {@code button} ended this frame. */
    public boolean dragEnded(int button) {
        return dragEndFrame[button] == frame;
    }

    /** Returns where the current or last drag of {@code button} started (x). */
    public float dragStartX(int button) {
        return startX[button];
    }

    /** Returns where the current or last drag of {@code button} started (y). */
    public float dragStartY(int button) {
        return startY[button];
    }

    /** Returns {@code true} while a box selection is being dragged. */
    public boolean isBoxSelecting() {
        return dragging[selectButton];
    }

    /** Returns {@code true} if a box selection was released this frame. */
    public boolean boxFinished() {
        return boxFinishedFrame == frame;
    }

    /** Returns the left edge of the selection box (during or at the end of the drag). */
    public float boxMinX() {
        return Math.min(startX[selectButton], x);
    }

    /** Returns the top edge of the selection box. */
    public float boxMinY() {
        return Math.min(startY[selectButton], y);
    }

    /** Returns the right edge of the selection box. */
    public float boxMaxX() {
        return Math.max(startX[selectButton], x);
    }

    /** Returns the bottom edge of the selection box. */
    public float boxMaxY() {
        return Math.max(startY[selectButton], y);
    }

    /** Returns the horizontal edge-scroll direction, -1 (left) to 1 (right). */
    public float edgeScrollX() {
        return edgeX;
    }

    /** Returns the vertical edge-scroll direction, -1 (top) to 1 (bottom). */
    public float edgeScrollY() {
        return edgeY;
    }

    /** Returns the camera rotation input this frame (window coordinates dragged). */
    public float rotateDelta() {
        return rotate;
    }

    /** Returns the camera pan input this frame in x. */
    public float panDeltaX() {
        return panX;
    }

    /** Returns the camera pan input this frame in y. */
    public float panDeltaY() {
        return panY;
    }

    /** Returns the zoom input this frame (wheel steps, positive = in). */
    public float zoomDelta() {
        return zoom;
    }
}
