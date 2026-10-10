package dev.jeddy.math;

/**
 * Immutable 3×3 matrix, column-major ({@code mCR} = column C, row R). Used for rotations and normal
 * matrices (value type, DESIGN §8.1).
 *
 * <p>Thread ownership: immutable.
 *
 * @param m00 column 0 row 0
 * @param m01 column 0 row 1
 * @param m02 column 0 row 2
 * @param m10 column 1 row 0
 * @param m11 column 1 row 1
 * @param m12 column 1 row 2
 * @param m20 column 2 row 0
 * @param m21 column 2 row 1
 * @param m22 column 2 row 2
 */
public record Mat3f(
        float m00,
        float m01,
        float m02,
        float m10,
        float m11,
        float m12,
        float m20,
        float m21,
        float m22) {

    /** The identity matrix. */
    public static final Mat3f IDENTITY = new Mat3f(1, 0, 0, 0, 1, 0, 0, 0, 1);

    /** Returns the rotation matrix of a unit quaternion. */
    public static Mat3f rotation(Quatf q) {
        Mat4f r = Mat4f.rotation(q);
        return new Mat3f(
                r.m00(), r.m01(), r.m02(), r.m10(), r.m11(), r.m12(), r.m20(), r.m21(), r.m22());
    }

    /** Returns the upper-left 3×3 of {@code m}. */
    public static Mat3f of(Mat4f m) {
        return new Mat3f(
                m.m00(), m.m01(), m.m02(), m.m10(), m.m11(), m.m12(), m.m20(), m.m21(), m.m22());
    }

    /**
     * Returns the normal matrix of {@code model}: the inverse transpose of its upper-left 3×3,
     * which keeps normals perpendicular under non-uniform scale.
     */
    public static Mat3f normalMatrix(Mat4f model) {
        return of(model).invert().transpose();
    }

    /** Returns {@code this * o}. */
    public Mat3f mul(Mat3f o) {
        return new Mat3f(
                m00 * o.m00 + m10 * o.m01 + m20 * o.m02,
                m01 * o.m00 + m11 * o.m01 + m21 * o.m02,
                m02 * o.m00 + m12 * o.m01 + m22 * o.m02,
                m00 * o.m10 + m10 * o.m11 + m20 * o.m12,
                m01 * o.m10 + m11 * o.m11 + m21 * o.m12,
                m02 * o.m10 + m12 * o.m11 + m22 * o.m12,
                m00 * o.m20 + m10 * o.m21 + m20 * o.m22,
                m01 * o.m20 + m11 * o.m21 + m21 * o.m22,
                m02 * o.m20 + m12 * o.m21 + m22 * o.m22);
    }

    /** Returns {@code this * v}. */
    public Vec3f transform(Vec3f v) {
        return new Vec3f(
                m00 * v.x() + m10 * v.y() + m20 * v.z(),
                m01 * v.x() + m11 * v.y() + m21 * v.z(),
                m02 * v.x() + m12 * v.y() + m22 * v.z());
    }

    /** Returns the transpose. */
    public Mat3f transpose() {
        return new Mat3f(m00, m10, m20, m01, m11, m21, m02, m12, m22);
    }

    /** Returns the determinant. */
    public float determinant() {
        return m00 * (m11 * m22 - m21 * m12)
                - m10 * (m01 * m22 - m21 * m02)
                + m20 * (m01 * m12 - m11 * m02);
    }

    /** Returns the inverse. Throws {@link ArithmeticException} if singular. */
    public Mat3f invert() {
        float det = determinant();
        if (det == 0 || !Float.isFinite(det)) {
            throw new ArithmeticException("singular matrix");
        }
        float inv = 1 / det;
        return new Mat3f(
                (m11 * m22 - m21 * m12) * inv,
                (m21 * m02 - m01 * m22) * inv,
                (m01 * m12 - m11 * m02) * inv,
                (m20 * m12 - m10 * m22) * inv,
                (m00 * m22 - m20 * m02) * inv,
                (m10 * m02 - m00 * m12) * inv,
                (m10 * m21 - m20 * m11) * inv,
                (m20 * m01 - m00 * m21) * inv,
                (m00 * m11 - m10 * m01) * inv);
    }
}
