package dev.jeddy.math;

/**
 * Mutable column-major 4×4 matrix for per-frame camera and transform math without allocation
 * (DESIGN §8.1, layer 2). Elements live in {@link #m} ({@code m[c * 4 + r]}), ready to upload as
 * a uniform.
 *
 * <p>Thread ownership: single owner thread.
 */
public final class MMat4f {
    /** Column-major elements. */
    public final float[] m = new float[16];

    private final float[] tmp = new float[16];

    /** Creates an identity matrix. */
    public MMat4f() {
        Mat4Ops.identity(m, 0);
    }

    /** Sets to identity. */
    public MMat4f identity() {
        Mat4Ops.identity(m, 0);
        return this;
    }

    /** Copies {@code o}. */
    public MMat4f set(MMat4f o) {
        System.arraycopy(o.m, 0, m, 0, 16);
        return this;
    }

    /** Copies an immutable matrix. */
    public MMat4f set(Mat4f o) {
        o.toArray(m, 0);
        return this;
    }

    /** Sets {@code this = this * o}. */
    public MMat4f mul(MMat4f o) {
        Mat4Ops.mul(m, 0, o.m, 0, tmp, 0);
        System.arraycopy(tmp, 0, m, 0, 16);
        return this;
    }

    /** Sets {@code this = a * b}. {@code this} may be {@code a} or {@code b}. */
    public MMat4f mul(MMat4f a, MMat4f b) {
        Mat4Ops.mul(a.m, 0, b.m, 0, tmp, 0);
        System.arraycopy(tmp, 0, m, 0, 16);
        return this;
    }

    /** Inverts in place. Throws {@link ArithmeticException} if singular. */
    public MMat4f invert() {
        if (!Mat4Ops.invert(m, 0, m, 0)) {
            throw new ArithmeticException("singular matrix");
        }
        return this;
    }

    /** Transposes in place. */
    public MMat4f transpose() {
        Mat4Ops.transpose(m, 0, tmp, 0);
        System.arraycopy(tmp, 0, m, 0, 16);
        return this;
    }

    /** Sets an infinite reversed-Z perspective projection. */
    public MMat4f perspectiveReversedZ(float fovY, float aspect, float near) {
        Mat4Ops.perspectiveReversedZ(fovY, aspect, near, m, 0);
        return this;
    }

    /** Sets a reversed-Z orthographic projection. */
    public MMat4f orthoReversedZ(float left, float right, float bottom, float top, float near, float far) {
        Mat4Ops.orthoReversedZ(left, right, bottom, top, near, far, m, 0);
        return this;
    }

    /** Sets a look-at view matrix. */
    public MMat4f lookAt(MVec3f eye, MVec3f target, MVec3f up) {
        Mat4Ops.lookAt(eye.x, eye.y, eye.z, target.x, target.y, target.z, up.x, up.y, up.z, m, 0);
        return this;
    }

    /** Copies the elements into {@code out[o..o+15]}. */
    public void get(float[] out, int o) {
        System.arraycopy(m, 0, out, o, 16);
    }

    /** Returns an immutable copy (allocates). */
    public Mat4f toMat4f() {
        return Mat4f.fromArray(m, 0);
    }
}
