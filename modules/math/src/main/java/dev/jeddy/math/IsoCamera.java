package dev.jeddy.math;

/**
 * Isometric-style RPG camera math (DESIGN §8.2, §9.4): orbits a target point at a fixed pitch,
 * rotatable yaw and zoom distance, with a narrow-FOV perspective or an orthographic projection.
 * Produces view, projection and view-projection matrices (reversed-Z) and screen-to-world rays for
 * picking. All results go into preallocated arrays.
 *
 * <p>Thread ownership: single owner thread (the game thread builds it; the render packet copies the
 * matrices).
 */
public final class IsoCamera {
    /** Point the camera looks at (usually on the ground). */
    public final MVec3f target = new MVec3f();

    /** Rotation around the vertical axis, radians. 0 looks towards -Z. */
    public float yaw = MathF.PI / 4;

    /** Downward tilt, radians (π/2 looks straight down). */
    public float pitch = MathF.toRadians(55);

    /** Distance from the target along the view direction. */
    public float distance = 20;

    /** Vertical field of view for perspective mode, radians. */
    public float fovY = MathF.toRadians(30);

    /** Near plane distance. */
    public float near = 0.5f;

    /** {@code true} for orthographic projection. */
    public boolean orthographic;

    /** Height of the visible area in orthographic mode, world units. */
    public float orthoHeight = 20;

    /** Far plane in orthographic mode. */
    public float orthoFar = 500;

    /** View matrix (column-major), updated by {@link #update(float)}. */
    public final float[] view = new float[16];

    /** Projection matrix (column-major), updated by {@link #update(float)}. */
    public final float[] projection = new float[16];

    /** View-projection matrix (column-major), updated by {@link #update(float)}. */
    public final float[] viewProjection = new float[16];

    /** Inverse of {@link #viewProjection}, updated by {@link #update(float)}. */
    public final float[] inverseViewProjection = new float[16];

    /** Frustum planes for {@link CullOps}, updated by {@link #update(float)}. */
    public final float[] frustumPlanes = new float[CullOps.PLANES_FLOATS];

    private final MVec3f eye = new MVec3f();
    private final float[] p0 = new float[3];
    private final float[] p1 = new float[3];

    /** Creates a camera with default RPG framing. */
    public IsoCamera() {}

    /** Returns the eye position computed by the last {@link #update(float)}. */
    public MVec3f eye() {
        return eye;
    }

    /** Recomputes every matrix for viewport aspect ratio {@code aspect} (width / height). */
    public void update(float aspect) {
        float cp = (float) Math.cos(pitch);
        float sp = (float) Math.sin(pitch);
        float sy = (float) Math.sin(yaw);
        float cy = (float) Math.cos(yaw);
        // Forward is (-sin yaw * cos pitch, -sin pitch, -cos yaw * cos pitch); eye is behind it.
        eye.set(
                target.x + sy * cp * distance,
                target.y + sp * distance,
                target.z + cy * cp * distance);
        Mat4Ops.lookAt(eye.x, eye.y, eye.z, target.x, target.y, target.z, 0, 1, 0, view, 0);
        if (orthographic) {
            float h = orthoHeight * 0.5f;
            float w = h * aspect;
            Mat4Ops.orthoReversedZ(-w, w, -h, h, near, orthoFar, projection, 0);
        } else {
            Mat4Ops.perspectiveReversedZ(fovY, aspect, near, projection, 0);
        }
        Mat4Ops.mul(projection, 0, view, 0, viewProjection, 0);
        Mat4Ops.invert(viewProjection, 0, inverseViewProjection, 0);
        CullOps.extractPlanes(viewProjection, 0, frustumPlanes);
    }

    /**
     * Builds the world-space ray through normalized device coordinates {@code (ndcX, ndcY)} in
     * {@code [-1, 1]} (y up). Writes origin to {@code origin[0..2]} and unit direction to {@code
     * direction[0..2]}.
     */
    public void screenRay(float ndcX, float ndcY, float[] origin, float[] direction) {
        // Reversed-Z: depth 1 is the near plane; 0.5 is a point further along the ray.
        unproject(ndcX, ndcY, 1f, p0);
        unproject(ndcX, ndcY, 0.5f, p1);
        float dx = p1[0] - p0[0];
        float dy = p1[1] - p0[1];
        float dz = p1[2] - p0[2];
        float inv = (float) (1 / Math.sqrt(dx * dx + dy * dy + dz * dz));
        origin[0] = p0[0];
        origin[1] = p0[1];
        origin[2] = p0[2];
        direction[0] = dx * inv;
        direction[1] = dy * inv;
        direction[2] = dz * inv;
    }

    private void unproject(float x, float y, float depth, float[] out) {
        Mat4Ops.project(inverseViewProjection, 0, x, y, depth, out, 0);
    }
}
