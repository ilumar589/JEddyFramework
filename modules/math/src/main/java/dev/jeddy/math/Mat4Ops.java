package dev.jeddy.math;

/**
 * 4×4 matrix kernels over {@code float[]} with offsets, column-major (element {@code [c * 4 +
 * r]}). Allocation-free; inputs and output may not overlap unless stated (DESIGN §8.1).
 *
 * <p>Thread ownership: stateless, callable from any thread.
 */
public final class Mat4Ops {
    private Mat4Ops() {}

    /** Writes the identity matrix. */
    public static void identity(float[] out, int o) {
        for (int i = 0; i < 16; i++) {
            out[o + i] = (i % 5 == 0) ? 1 : 0;
        }
    }

    /** {@code out = a * b}. {@code out} must not overlap {@code a} or {@code b}. */
    public static void mul(float[] a, int ao, float[] b, int bo, float[] out, int o) {
        for (int c = 0; c < 4; c++) {
            float b0 = b[bo + c * 4];
            float b1 = b[bo + c * 4 + 1];
            float b2 = b[bo + c * 4 + 2];
            float b3 = b[bo + c * 4 + 3];
            out[o + c * 4] = a[ao] * b0 + a[ao + 4] * b1 + a[ao + 8] * b2 + a[ao + 12] * b3;
            out[o + c * 4 + 1] = a[ao + 1] * b0 + a[ao + 5] * b1 + a[ao + 9] * b2 + a[ao + 13] * b3;
            out[o + c * 4 + 2] = a[ao + 2] * b0 + a[ao + 6] * b1 + a[ao + 10] * b2 + a[ao + 14] * b3;
            out[o + c * 4 + 3] = a[ao + 3] * b0 + a[ao + 7] * b1 + a[ao + 11] * b2 + a[ao + 15] * b3;
        }
    }

    /**
     * {@code out = m * affine}, where {@code affine} is a row-major 3×4 transform (see {@link
     * AffineOps}) treated as a 4×4 with last row (0, 0, 0, 1). Typical use: view-projection times
     * model.
     */
    public static void mulAffine(float[] m, int mo, float[] affine, int ao, float[] out, int o) {
        for (int c = 0; c < 4; c++) {
            float b0 = affine[ao + c];
            float b1 = affine[ao + 4 + c];
            float b2 = affine[ao + 8 + c];
            float b3 = c == 3 ? 1 : 0;
            out[o + c * 4] = m[mo] * b0 + m[mo + 4] * b1 + m[mo + 8] * b2 + m[mo + 12] * b3;
            out[o + c * 4 + 1] = m[mo + 1] * b0 + m[mo + 5] * b1 + m[mo + 9] * b2 + m[mo + 13] * b3;
            out[o + c * 4 + 2] = m[mo + 2] * b0 + m[mo + 6] * b1 + m[mo + 10] * b2 + m[mo + 14] * b3;
            out[o + c * 4 + 3] = m[mo + 3] * b0 + m[mo + 7] * b1 + m[mo + 11] * b2 + m[mo + 15] * b3;
        }
    }

    /** Writes the transpose of {@code m} to {@code out} (may not overlap). */
    public static void transpose(float[] m, int mo, float[] out, int o) {
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) {
                out[o + r * 4 + c] = m[mo + c * 4 + r];
            }
        }
    }

    /** Returns the determinant. */
    public static float determinant(float[] m, int o) {
        float a00 = m[o], a01 = m[o + 1], a02 = m[o + 2], a03 = m[o + 3];
        float a10 = m[o + 4], a11 = m[o + 5], a12 = m[o + 6], a13 = m[o + 7];
        float a20 = m[o + 8], a21 = m[o + 9], a22 = m[o + 10], a23 = m[o + 11];
        float a30 = m[o + 12], a31 = m[o + 13], a32 = m[o + 14], a33 = m[o + 15];
        float b00 = a00 * a11 - a01 * a10;
        float b01 = a00 * a12 - a02 * a10;
        float b02 = a00 * a13 - a03 * a10;
        float b03 = a01 * a12 - a02 * a11;
        float b04 = a01 * a13 - a03 * a11;
        float b05 = a02 * a13 - a03 * a12;
        float b06 = a20 * a31 - a21 * a30;
        float b07 = a20 * a32 - a22 * a30;
        float b08 = a20 * a33 - a23 * a30;
        float b09 = a21 * a32 - a22 * a31;
        float b10 = a21 * a33 - a23 * a31;
        float b11 = a22 * a33 - a23 * a32;
        return b00 * b11 - b01 * b10 + b02 * b09 + b03 * b08 - b04 * b07 + b05 * b06;
    }

    /**
     * Writes the inverse of {@code m} to {@code out} (may overlap). Returns {@code false}, leaving
     * {@code out} untouched, if {@code m} is singular.
     */
    public static boolean invert(float[] m, int o, float[] out, int oo) {
        float a00 = m[o], a01 = m[o + 1], a02 = m[o + 2], a03 = m[o + 3];
        float a10 = m[o + 4], a11 = m[o + 5], a12 = m[o + 6], a13 = m[o + 7];
        float a20 = m[o + 8], a21 = m[o + 9], a22 = m[o + 10], a23 = m[o + 11];
        float a30 = m[o + 12], a31 = m[o + 13], a32 = m[o + 14], a33 = m[o + 15];
        float b00 = a00 * a11 - a01 * a10;
        float b01 = a00 * a12 - a02 * a10;
        float b02 = a00 * a13 - a03 * a10;
        float b03 = a01 * a12 - a02 * a11;
        float b04 = a01 * a13 - a03 * a11;
        float b05 = a02 * a13 - a03 * a12;
        float b06 = a20 * a31 - a21 * a30;
        float b07 = a20 * a32 - a22 * a30;
        float b08 = a20 * a33 - a23 * a30;
        float b09 = a21 * a32 - a22 * a31;
        float b10 = a21 * a33 - a23 * a31;
        float b11 = a22 * a33 - a23 * a32;
        float det = b00 * b11 - b01 * b10 + b02 * b09 + b03 * b08 - b04 * b07 + b05 * b06;
        if (det == 0 || !Float.isFinite(det)) {
            return false;
        }
        float inv = 1 / det;
        out[oo] = (a11 * b11 - a12 * b10 + a13 * b09) * inv;
        out[oo + 1] = (a02 * b10 - a01 * b11 - a03 * b09) * inv;
        out[oo + 2] = (a31 * b05 - a32 * b04 + a33 * b03) * inv;
        out[oo + 3] = (a22 * b04 - a21 * b05 - a23 * b03) * inv;
        out[oo + 4] = (a12 * b08 - a10 * b11 - a13 * b07) * inv;
        out[oo + 5] = (a00 * b11 - a02 * b08 + a03 * b07) * inv;
        out[oo + 6] = (a32 * b02 - a30 * b05 - a33 * b01) * inv;
        out[oo + 7] = (a20 * b05 - a22 * b02 + a23 * b01) * inv;
        out[oo + 8] = (a10 * b10 - a11 * b08 + a13 * b06) * inv;
        out[oo + 9] = (a01 * b08 - a00 * b10 - a03 * b06) * inv;
        out[oo + 10] = (a30 * b04 - a31 * b02 + a33 * b00) * inv;
        out[oo + 11] = (a21 * b02 - a20 * b04 - a23 * b00) * inv;
        out[oo + 12] = (a11 * b07 - a10 * b09 - a12 * b06) * inv;
        out[oo + 13] = (a00 * b09 - a01 * b07 + a02 * b06) * inv;
        out[oo + 14] = (a31 * b01 - a30 * b03 - a32 * b00) * inv;
        out[oo + 15] = (a20 * b03 - a21 * b01 + a22 * b00) * inv;
        return true;
    }

    /**
     * Transforms point {@code (x, y, z, 1)} and writes the perspective-divided result to {@code
     * out[o..o+2]}.
     */
    public static void project(float[] m, int mo, float x, float y, float z, float[] out, int o) {
        float px = m[mo] * x + m[mo + 4] * y + m[mo + 8] * z + m[mo + 12];
        float py = m[mo + 1] * x + m[mo + 5] * y + m[mo + 9] * z + m[mo + 13];
        float pz = m[mo + 2] * x + m[mo + 6] * y + m[mo + 10] * z + m[mo + 14];
        float pw = m[mo + 3] * x + m[mo + 7] * y + m[mo + 11] * z + m[mo + 15];
        float iw = 1 / pw;
        out[o] = px * iw;
        out[o + 1] = py * iw;
        out[o + 2] = pz * iw;
    }

    /** Writes an infinite reversed-Z perspective projection (see {@link Mat4f}). */
    public static void perspectiveReversedZ(float fovY, float aspect, float near, float[] out, int o) {
        float f = (float) (1.0 / Math.tan(fovY * 0.5));
        for (int i = 0; i < 16; i++) {
            out[o + i] = 0;
        }
        out[o] = f / aspect;
        out[o + 5] = f;
        out[o + 11] = -1;
        out[o + 14] = near;
    }

    /** Writes a reversed-Z orthographic projection (see {@link Mat4f}). */
    public static void orthoReversedZ(
            float left, float right, float bottom, float top, float near, float far, float[] out, int o) {
        for (int i = 0; i < 16; i++) {
            out[o + i] = 0;
        }
        float rl = 1 / (right - left);
        float tb = 1 / (top - bottom);
        float fn = 1 / (far - near);
        out[o] = 2 * rl;
        out[o + 5] = 2 * tb;
        out[o + 10] = fn;
        out[o + 12] = -(right + left) * rl;
        out[o + 13] = -(top + bottom) * tb;
        out[o + 14] = far * fn;
        out[o + 15] = 1;
    }

    /** Writes a right-handed look-at view matrix. */
    public static void lookAt(
            float ex, float ey, float ez, float tx, float ty, float tz, float ux, float uy, float uz,
            float[] out, int o) {
        float fx = tx - ex;
        float fy = ty - ey;
        float fz = tz - ez;
        float fl = (float) (1 / Math.sqrt(fx * fx + fy * fy + fz * fz));
        fx *= fl;
        fy *= fl;
        fz *= fl;
        float rx = fy * uz - fz * uy;
        float ry = fz * ux - fx * uz;
        float rz = fx * uy - fy * ux;
        float rl = (float) (1 / Math.sqrt(rx * rx + ry * ry + rz * rz));
        rx *= rl;
        ry *= rl;
        rz *= rl;
        float vx = ry * fz - rz * fy;
        float vy = rz * fx - rx * fz;
        float vz = rx * fy - ry * fx;
        out[o] = rx;
        out[o + 1] = vx;
        out[o + 2] = -fx;
        out[o + 3] = 0;
        out[o + 4] = ry;
        out[o + 5] = vy;
        out[o + 6] = -fy;
        out[o + 7] = 0;
        out[o + 8] = rz;
        out[o + 9] = vz;
        out[o + 10] = -fz;
        out[o + 11] = 0;
        out[o + 12] = -(rx * ex + ry * ey + rz * ez);
        out[o + 13] = -(vx * ex + vy * ey + vz * ez);
        out[o + 14] = fx * ex + fy * ey + fz * ez;
        out[o + 15] = 1;
    }
}
