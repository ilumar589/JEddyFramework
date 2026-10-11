package dev.jeddy.editor;

/**
 * The editor's own preferences (DESIGN §19): snapping steps, what the viewport shows, camera
 * speeds. Public fields; the Settings panel edits them and the host persists them in its settings
 * file ({@code Editor.saveSettings} / {@code loadSettings}).
 *
 * <p>Thread ownership: the game thread.
 */
public final class EditorSettings {
    /** Creates the defaults. */
    public EditorSettings() {}

    /** Whether gizmo drags snap (held Ctrl inverts it for the drag). */
    public boolean snap = true;

    /** Translation snapping step in meters (the level's grid step when a level opens). */
    public float translateStep = 0.5f;

    /** Rotation snapping step in degrees. */
    public float rotateStepDegrees = 15;

    /** Scale snapping step (a factor increment). */
    public float scaleStep = 0.25f;

    /** Draw the ground grid. */
    public boolean showGrid = true;

    /** Draw light, spawn and trigger markers. */
    public boolean showMarkers = true;

    /** Draw the bounds of the selection. */
    public boolean showBounds = true;

    /** Gizmo size on screen, in pixels. */
    public float gizmoPixels = 90;

    /** Orbit speed: radians per pixel of mouse movement. */
    public float orbitSpeed = 0.006f;

    /** Zoom factor per wheel notch. */
    public float zoomStep = 1.15f;

    /** Copies every field from {@code o}. */
    public void copyFrom(EditorSettings o) {
        snap = o.snap;
        translateStep = o.translateStep;
        rotateStepDegrees = o.rotateStepDegrees;
        scaleStep = o.scaleStep;
        showGrid = o.showGrid;
        showMarkers = o.showMarkers;
        showBounds = o.showBounds;
        gizmoPixels = o.gizmoPixels;
        orbitSpeed = o.orbitSpeed;
        zoomStep = o.zoomStep;
    }
}
