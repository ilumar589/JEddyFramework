package dev.jeddy.render.camera;

import dev.jeddy.math.IsoCamera;
import dev.jeddy.math.MathF;

/**
 * Drives an {@link IsoCamera} the way CRPGs do (TASKS M5-09, DESIGN §9.4): keyboard and screen-edge
 * panning relative to the view direction, drag panning that keeps the grabbed ground point under
 * the cursor, smoothed zoom and yaw rotation, following a point, and clamping to the level bounds.
 * It takes plain numbers (no input dependency): the game maps actions to calls.
 *
 * <p>Thread ownership: the game thread (the render packet copies the resulting matrices).
 */
public final class IsoCameraController {
    private final IsoCamera camera;

    /** Pan speed in world units per second at distance 20 (scales with the zoom). */
    public float panSpeed = 18;

    /** Fraction of the screen at each edge that pans the camera (0 disables edge panning). */
    public float edgeMargin = 0.01f;

    /** Smoothing time constant for zoom, rotation and follow, seconds. */
    public float smoothing = 0.12f;

    /** Closest zoom distance. */
    public float minDistance = 6;

    /** Farthest zoom distance. */
    public float maxDistance = 60;

    /** Zoom factor per wheel step. */
    public float zoomStep = 1.15f;

    private float goalDistance;
    private float goalYaw;
    private float minX = -Float.MAX_VALUE, maxX = Float.MAX_VALUE;
    private float minZ = -Float.MAX_VALUE, maxZ = Float.MAX_VALUE;
    private float panX, panZ; // requested pan direction this frame, view-relative
    private boolean following;
    private float followX, followY, followZ;
    private boolean dragging;
    private final float[] dragAnchor = new float[3];
    private final float[] hit = new float[3];
    private final float[] origin = new float[3];
    private final float[] direction = new float[3];
    private float groundY;

    /** Controls {@code camera}, starting from its current distance and yaw. */
    public IsoCameraController(IsoCamera camera) {
        this.camera = camera;
        goalDistance = camera.distance;
        goalYaw = camera.yaw;
    }

    /**
     * Takes the camera's current distance (clamped to the zoom limits) and yaw as the goals, after
     * the game set them directly; otherwise the smoothing glides back to the old goals.
     */
    public void syncGoals() {
        goalDistance = MathF.clamp(camera.distance, minDistance, maxDistance);
        goalYaw = camera.yaw;
    }

    /** Returns the controlled camera. */
    public IsoCamera camera() {
        return camera;
    }

    /** Keeps the target inside {@code [minX, maxX] × [minZ, maxZ]} (the level bounds). */
    public void setBounds(float minX, float minZ, float maxX, float maxZ) {
        this.minX = minX;
        this.maxX = maxX;
        this.minZ = minZ;
        this.maxZ = maxZ;
    }

    /** Sets the height of the ground plane used for drag panning and picking. */
    public void setGroundHeight(float y) {
        groundY = y;
    }

    /**
     * Pans this frame: {@code right} and {@code forward} in [-1, 1], relative to the view (forward
     * moves away from the camera on the ground). Cancels following.
     */
    public void pan(float right, float forward) {
        panX += right;
        panZ += forward;
        if (right != 0 || forward != 0) {
            following = false;
        }
    }

    /** Pans when the cursor ({@code x, y} in pixels, y down) touches a screen edge. */
    public void edgePan(float x, float y, float width, float height) {
        if (edgeMargin <= 0) {
            return;
        }
        float mx = width * edgeMargin;
        float my = height * edgeMargin;
        pan(
                x <= mx ? -1 : x >= width - 1 - mx ? 1 : 0,
                y <= my ? 1 : y >= height - 1 - my ? -1 : 0);
    }

    /** Zooms by wheel steps (positive zooms in). */
    public void zoom(float steps) {
        goalDistance =
                MathF.clamp(
                        goalDistance * (float) Math.pow(zoomStep, -steps),
                        minDistance,
                        maxDistance);
    }

    /** Rotates the view by {@code radians} around the target (smoothed). */
    public void rotate(float radians) {
        goalYaw += radians;
    }

    /** Glides the target to a point and keeps it there until the player pans. */
    public void follow(float x, float y, float z) {
        following = true;
        followX = x;
        followY = y;
        followZ = z;
    }

    /** Stops following. */
    public void stopFollowing() {
        following = false;
    }

    /**
     * Starts a drag pan at the cursor (normalized device coordinates, y up): the ground point under
     * it stays under the cursor while dragging.
     */
    public void beginDrag(float ndcX, float ndcY) {
        dragging = groundPoint(ndcX, ndcY, dragAnchor);
        following = false;
    }

    /** Continues a drag pan. */
    public void drag(float ndcX, float ndcY) {
        if (dragging && groundPoint(ndcX, ndcY, hit)) {
            camera.target.x += dragAnchor[0] - hit[0];
            camera.target.z += dragAnchor[2] - hit[2];
            clampTarget();
            camera.update(lastAspect);
        }
    }

    /** Ends a drag pan. */
    public void endDrag() {
        dragging = false;
    }

    /**
     * Writes the ground point under the cursor (NDC, y up) to {@code out}; returns {@code false} if
     * the ray misses the ground plane. Uses the matrices of the last {@link #update}.
     */
    public boolean groundPoint(float ndcX, float ndcY, float[] out) {
        camera.screenRay(ndcX, ndcY, origin, direction);
        if (Math.abs(direction[1]) < 1e-6f) {
            return false;
        }
        float t = (groundY - origin[1]) / direction[1];
        if (t < 0) {
            return false;
        }
        out[0] = origin[0] + direction[0] * t;
        out[1] = groundY;
        out[2] = origin[2] + direction[2] * t;
        return true;
    }

    private float lastAspect = 16f / 9f;

    /** Applies this frame's input and smoothing, then updates the camera matrices. */
    public void update(float dt, float aspect) {
        lastAspect = aspect;
        float k = smoothing <= 0 ? 1 : 1 - (float) Math.exp(-dt / smoothing);
        camera.distance += (goalDistance - camera.distance) * k;
        camera.yaw += (goalYaw - camera.yaw) * k;
        if (panX != 0 || panZ != 0) {
            float len = (float) Math.sqrt(panX * panX + panZ * panZ);
            float scale = panSpeed * (camera.distance / 20f) * dt / Math.max(1, len);
            float s = (float) Math.sin(camera.yaw);
            float c = (float) Math.cos(camera.yaw);
            // Forward on the ground is (-sin yaw, -cos yaw); right is (cos yaw, -sin yaw).
            camera.target.x += (panX * c - panZ * s) * scale;
            camera.target.z += (-panX * s - panZ * c) * scale;
            panX = 0;
            panZ = 0;
        } else if (following) {
            camera.target.x += (followX - camera.target.x) * k;
            camera.target.y += (followY - camera.target.y) * k;
            camera.target.z += (followZ - camera.target.z) * k;
        }
        clampTarget();
        camera.update(aspect);
    }

    private void clampTarget() {
        camera.target.x = MathF.clamp(camera.target.x, minX, maxX);
        camera.target.z = MathF.clamp(camera.target.z, minZ, maxZ);
    }

    /**
     * Pixels per world unit at distance 1 for LOD selection ({@code DrawBuilder.setLod}), for a
     * viewport {@code heightPixels} tall.
     */
    public float lodPixels(float heightPixels) {
        return camera.orthographic
                ? heightPixels / camera.orthoHeight
                : heightPixels / (2f * (float) Math.tan(camera.fovY / 2f));
    }
}
