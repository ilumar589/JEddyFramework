package dev.jeddy.math;

/**
 * Immutable 4×4 matrix, column-major like OpenGL (value type, DESIGN §8.1/§8.2). Component {@code
 * mCR} is column {@code C}, row {@code R}: {@code m30, m31, m32} hold the translation. Vectors are
 * columns; {@code a.mul(b)} applies {@code b} first.
 *
 * <p>Projections are reversed-Z with depth in {@code [0, 1]} (near maps to 1), for use with {@code
 * glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)} and a {@code GL_GEQUAL} depth test.
 *
 * <p>Thread ownership: immutable.
 *
 * @param m00 column 0 row 0
 * @param m01 column 0 row 1
 * @param m02 column 0 row 2
 * @param m03 column 0 row 3
 * @param m10 column 1 row 0
 * @param m11 column 1 row 1
 * @param m12 column 1 row 2
 * @param m13 column 1 row 3
 * @param m20 column 2 row 0
 * @param m21 column 2 row 1
 * @param m22 column 2 row 2
 * @param m23 column 2 row 3
 * @param m30 column 3 row 0 (translation x)
 * @param m31 column 3 row 1 (translation y)
 * @param m32 column 3 row 2 (translation z)
 * @param m33 column 3 row 3
 */
public record Mat4f(
        float m00,
        float m01,
        float m02,
        float m03,
        float m10,
        float m11,
        float m12,
        float m13,
        float m20,
        float m21,
        float m22,
        float m23,
        float m30,
        float m31,
        float m32,
        float m33) {

    /** The identity matrix. */
    public static final Mat4f IDENTITY = new Mat4f(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1);

    /** Reads 16 column-major floats starting at {@code offset}. */
    public static Mat4f fromArray(float[] a, int offset) {
        return new Mat4f(
                a[offset],
                a[offset + 1],
                a[offset + 2],
                a[offset + 3],
                a[offset + 4],
                a[offset + 5],
                a[offset + 6],
                a[offset + 7],
                a[offset + 8],
                a[offset + 9],
                a[offset + 10],
                a[offset + 11],
                a[offset + 12],
                a[offset + 13],
                a[offset + 14],
                a[offset + 15]);
    }

    /** Writes 16 column-major floats starting at {@code offset}. */
    public void toArray(float[] a, int offset) {
        a[offset] = m00;
        a[offset + 1] = m01;
        a[offset + 2] = m02;
        a[offset + 3] = m03;
        a[offset + 4] = m10;
        a[offset + 5] = m11;
        a[offset + 6] = m12;
        a[offset + 7] = m13;
        a[offset + 8] = m20;
        a[offset + 9] = m21;
        a[offset + 10] = m22;
        a[offset + 11] = m23;
        a[offset + 12] = m30;
        a[offset + 13] = m31;
        a[offset + 14] = m32;
        a[offset + 15] = m33;
    }

    /** Returns the element at {@code column}, {@code row}. */
    public float get(int column, int row) {
        float[] a = new float[16];
        toArray(a, 0);
        return a[column * 4 + row];
    }

    /** Returns a translation matrix. */
    public static Mat4f translation(float x, float y, float z) {
        return new Mat4f(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, x, y, z, 1);
    }

    /** Returns a scaling matrix. */
    public static Mat4f scaling(float x, float y, float z) {
        return new Mat4f(x, 0, 0, 0, 0, y, 0, 0, 0, 0, z, 0, 0, 0, 0, 1);
    }

    /** Returns the rotation matrix of a unit quaternion. */
    public static Mat4f rotation(Quatf q) {
        return trs(Vec3f.ZERO, q, Vec3f.ONE);
    }

    /** Returns translation * rotation * scale. */
    public static Mat4f trs(Vec3f t, Quatf q, Vec3f s) {
        float x = q.x();
        float y = q.y();
        float z = q.z();
        float w = q.w();
        float xx = x * x;
        float yy = y * y;
        float zz = z * z;
        float xy = x * y;
        float xz = x * z;
        float yz = y * z;
        float wx = w * x;
        float wy = w * y;
        float wz = w * z;
        return new Mat4f(
                (1 - 2 * (yy + zz)) * s.x(),
                2 * (xy + wz) * s.x(),
                2 * (xz - wy) * s.x(),
                0,
                2 * (xy - wz) * s.y(),
                (1 - 2 * (xx + zz)) * s.y(),
                2 * (yz + wx) * s.y(),
                0,
                2 * (xz + wy) * s.z(),
                2 * (yz - wx) * s.z(),
                (1 - 2 * (xx + yy)) * s.z(),
                0,
                t.x(),
                t.y(),
                t.z(),
                1);
    }

    /**
     * Returns an infinite reversed-Z perspective projection: depth 1 at {@code near}, approaching 0
     * at infinity.
     *
     * @param fovY vertical field of view in radians
     * @param aspect width / height
     * @param near distance to the near plane, &gt; 0
     */
    public static Mat4f perspectiveReversedZ(float fovY, float aspect, float near) {
        float f = (float) (1.0 / Math.tan(fovY * 0.5));
        return new Mat4f(f / aspect, 0, 0, 0, 0, f, 0, 0, 0, 0, 0, -1, 0, 0, near, 0);
    }

    /** Returns a reversed-Z orthographic projection: depth 1 at {@code near}, 0 at {@code far}. */
    public static Mat4f orthoReversedZ(
            float left, float right, float bottom, float top, float near, float far) {
        float rl = 1 / (right - left);
        float tb = 1 / (top - bottom);
        float fn = 1 / (far - near);
        return new Mat4f(
                2 * rl,
                0,
                0,
                0,
                0,
                2 * tb,
                0,
                0,
                0,
                0,
                fn,
                0,
                -(right + left) * rl,
                -(top + bottom) * tb,
                far * fn,
                1);
    }

    /** Returns a right-handed view matrix looking from {@code eye} at {@code target}. */
    public static Mat4f lookAt(Vec3f eye, Vec3f target, Vec3f up) {
        Vec3f f = target.sub(eye).normalize();
        Vec3f r = f.cross(up).normalize();
        Vec3f u = r.cross(f);
        return new Mat4f(
                r.x(),
                u.x(),
                -f.x(),
                0,
                r.y(),
                u.y(),
                -f.y(),
                0,
                r.z(),
                u.z(),
                -f.z(),
                0,
                -r.dot(eye),
                -u.dot(eye),
                f.dot(eye),
                1);
    }

    /** Returns {@code this * o}. */
    public Mat4f mul(Mat4f o) {
        float[] a = new float[16];
        float[] b = new float[16];
        float[] r = new float[16];
        toArray(a, 0);
        o.toArray(b, 0);
        Mat4Ops.mul(a, 0, b, 0, r, 0);
        return fromArray(r, 0);
    }

    /** Transforms a point (w = 1) without the perspective divide. */
    public Vec3f transformPoint(Vec3f p) {
        return new Vec3f(
                m00 * p.x() + m10 * p.y() + m20 * p.z() + m30,
                m01 * p.x() + m11 * p.y() + m21 * p.z() + m31,
                m02 * p.x() + m12 * p.y() + m22 * p.z() + m32);
    }

    /** Transforms a direction (w = 0). */
    public Vec3f transformDirection(Vec3f d) {
        return new Vec3f(
                m00 * d.x() + m10 * d.y() + m20 * d.z(),
                m01 * d.x() + m11 * d.y() + m21 * d.z(),
                m02 * d.x() + m12 * d.y() + m22 * d.z());
    }

    /** Transforms a homogeneous vector. */
    public Vec4f transform(Vec4f v) {
        return new Vec4f(
                m00 * v.x() + m10 * v.y() + m20 * v.z() + m30 * v.w(),
                m01 * v.x() + m11 * v.y() + m21 * v.z() + m31 * v.w(),
                m02 * v.x() + m12 * v.y() + m22 * v.z() + m32 * v.w(),
                m03 * v.x() + m13 * v.y() + m23 * v.z() + m33 * v.w());
    }

    /** Transforms a point and divides by w. */
    public Vec3f project(Vec3f p) {
        return transform(new Vec4f(p, 1)).perspectiveDivide();
    }

    /** Returns the transpose. */
    public Mat4f transpose() {
        return new Mat4f(
                m00, m10, m20, m30, m01, m11, m21, m31, m02, m12, m22, m32, m03, m13, m23, m33);
    }

    /** Returns the determinant. */
    public float determinant() {
        float[] a = new float[16];
        toArray(a, 0);
        return Mat4Ops.determinant(a, 0);
    }

    /** Returns the inverse. Throws {@link ArithmeticException} if the matrix is singular. */
    public Mat4f invert() {
        float[] a = new float[16];
        float[] r = new float[16];
        toArray(a, 0);
        if (!Mat4Ops.invert(a, 0, r, 0)) {
            throw new ArithmeticException("singular matrix");
        }
        return fromArray(r, 0);
    }

    /** Returns the translation column. */
    public Vec3f translation() {
        return new Vec3f(m30, m31, m32);
    }

    /** Returns {@code true} if every element is within {@code epsilon} of {@code o}'s. */
    public boolean approxEquals(Mat4f o, float epsilon) {
        float[] a = new float[16];
        float[] b = new float[16];
        toArray(a, 0);
        o.toArray(b, 0);
        for (int i = 0; i < 16; i++) {
            if (Math.abs(a[i] - b[i]) > epsilon) {
                return false;
            }
        }
        return true;
    }
}
