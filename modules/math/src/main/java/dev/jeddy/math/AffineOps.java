package dev.jeddy.math;

/**
 * Kernels for affine transforms stored as <b>row-major 3×4</b> blocks of 12 floats: three rows
 * {@code [r0c0 r0c1 r0c2 tx | r1c0 r1c1 r1c2 ty | r2c0 r2c1 r2c2 tz]}. The same layout goes into
 * GPU instance buffers (three {@code vec4} rows), so world transforms are copied, never converted
 * (DESIGN §8.2, §9.2).
 *
 * <p>Batch methods work on struct-of-arrays inputs and are allocation-free.
 *
 * <p>Thread ownership: stateless, callable from any thread.
 */
public final class AffineOps {
    /** Floats per transform. */
    public static final int STRIDE = 12;

    private AffineOps() {}

    /** Writes the identity transform. */
    public static void identity(float[] out, int o) {
        out[o] = 1;
        out[o + 1] = 0;
        out[o + 2] = 0;
        out[o + 3] = 0;
        out[o + 4] = 0;
        out[o + 5] = 1;
        out[o + 6] = 0;
        out[o + 7] = 0;
        out[o + 8] = 0;
        out[o + 9] = 0;
        out[o + 10] = 1;
        out[o + 11] = 0;
    }

    /** Writes translation * rotation(quaternion) * scale. */
    public static void composeTRS(
            float px,
            float py,
            float pz,
            float qx,
            float qy,
            float qz,
            float qw,
            float sx,
            float sy,
            float sz,
            float[] out,
            int o) {
        float xx = qx * qx;
        float yy = qy * qy;
        float zz = qz * qz;
        float xy = qx * qy;
        float xz = qx * qz;
        float yz = qy * qz;
        float wx = qw * qx;
        float wy = qw * qy;
        float wz = qw * qz;
        out[o] = (1 - 2 * (yy + zz)) * sx;
        out[o + 1] = 2 * (xy - wz) * sy;
        out[o + 2] = 2 * (xz + wy) * sz;
        out[o + 3] = px;
        out[o + 4] = 2 * (xy + wz) * sx;
        out[o + 5] = (1 - 2 * (xx + zz)) * sy;
        out[o + 6] = 2 * (yz - wx) * sz;
        out[o + 7] = py;
        out[o + 8] = 2 * (xz - wy) * sx;
        out[o + 9] = 2 * (yz + wx) * sy;
        out[o + 10] = (1 - 2 * (xx + yy)) * sz;
        out[o + 11] = pz;
    }

    /**
     * Composes TRS transforms for items {@code [start, end)} from struct-of-arrays inputs into
     * {@code out[i * 12]}.
     */
    public static void composeTRS(
            float[] px,
            float[] py,
            float[] pz,
            float[] qx,
            float[] qy,
            float[] qz,
            float[] qw,
            float[] sx,
            float[] sy,
            float[] sz,
            int start,
            int end,
            float[] out) {
        for (int i = start; i < end; i++) {
            composeTRS(
                    px[i],
                    py[i],
                    pz[i],
                    qx[i],
                    qy[i],
                    qz[i],
                    qw[i],
                    sx[i],
                    sy[i],
                    sz[i],
                    out,
                    i * STRIDE);
        }
    }

    /** {@code out = a * b} (apply {@code b} first). {@code out} may overlap neither input. */
    public static void mul(float[] a, int ao, float[] b, int bo, float[] out, int o) {
        for (int r = 0; r < 3; r++) {
            float a0 = a[ao + r * 4];
            float a1 = a[ao + r * 4 + 1];
            float a2 = a[ao + r * 4 + 2];
            float a3 = a[ao + r * 4 + 3];
            out[o + r * 4] = a0 * b[bo] + a1 * b[bo + 4] + a2 * b[bo + 8];
            out[o + r * 4 + 1] = a0 * b[bo + 1] + a1 * b[bo + 5] + a2 * b[bo + 9];
            out[o + r * 4 + 2] = a0 * b[bo + 2] + a1 * b[bo + 6] + a2 * b[bo + 10];
            out[o + r * 4 + 3] = a0 * b[bo + 3] + a1 * b[bo + 7] + a2 * b[bo + 11] + a3;
        }
    }

    /**
     * Hierarchy pass: for items {@code [start, end)}, {@code world[i] = world[parent[i]] *
     * local[i]}, or {@code local[i]} when {@code parent[i] < 0}. Items must be sorted so parents
     * come before children (DESIGN §13.1).
     */
    public static void concatenate(int[] parent, float[] local, float[] world, int start, int end) {
        for (int i = start; i < end; i++) {
            int p = parent[i];
            if (p < 0) {
                System.arraycopy(local, i * STRIDE, world, i * STRIDE, STRIDE);
            } else {
                mul(world, p * STRIDE, local, i * STRIDE, world, i * STRIDE);
            }
        }
    }

    /**
     * Writes the inverse of affine {@code m} to {@code out} (may not overlap). Returns {@code
     * false} if the linear part is singular.
     */
    public static boolean invert(float[] m, int mo, float[] out, int o) {
        float a = m[mo], b = m[mo + 1], c = m[mo + 2];
        float d = m[mo + 4], e = m[mo + 5], f = m[mo + 6];
        float g = m[mo + 8], h = m[mo + 9], k = m[mo + 10];
        float co0 = e * k - f * h;
        float co1 = f * g - d * k;
        float co2 = d * h - e * g;
        float det = a * co0 + b * co1 + c * co2;
        if (det == 0 || !Float.isFinite(det)) {
            return false;
        }
        float inv = 1 / det;
        float i00 = co0 * inv;
        float i01 = (c * h - b * k) * inv;
        float i02 = (b * f - c * e) * inv;
        float i10 = co1 * inv;
        float i11 = (a * k - c * g) * inv;
        float i12 = (c * d - a * f) * inv;
        float i20 = co2 * inv;
        float i21 = (b * g - a * h) * inv;
        float i22 = (a * e - b * d) * inv;
        float tx = m[mo + 3];
        float ty = m[mo + 7];
        float tz = m[mo + 11];
        out[o] = i00;
        out[o + 1] = i01;
        out[o + 2] = i02;
        out[o + 3] = -(i00 * tx + i01 * ty + i02 * tz);
        out[o + 4] = i10;
        out[o + 5] = i11;
        out[o + 6] = i12;
        out[o + 7] = -(i10 * tx + i11 * ty + i12 * tz);
        out[o + 8] = i20;
        out[o + 9] = i21;
        out[o + 10] = i22;
        out[o + 11] = -(i20 * tx + i21 * ty + i22 * tz);
        return true;
    }

    /** Transforms point {@code (x, y, z)} into {@code out[o..o+2]}. */
    public static void transformPoint(
            float[] m, int mo, float x, float y, float z, float[] out, int o) {
        out[o] = m[mo] * x + m[mo + 1] * y + m[mo + 2] * z + m[mo + 3];
        out[o + 1] = m[mo + 4] * x + m[mo + 5] * y + m[mo + 6] * z + m[mo + 7];
        out[o + 2] = m[mo + 8] * x + m[mo + 9] * y + m[mo + 10] * z + m[mo + 11];
    }

    /** Transforms direction {@code (x, y, z)} (no translation) into {@code out[o..o+2]}. */
    public static void transformDirection(
            float[] m, int mo, float x, float y, float z, float[] out, int o) {
        out[o] = m[mo] * x + m[mo + 1] * y + m[mo + 2] * z;
        out[o + 1] = m[mo + 4] * x + m[mo + 5] * y + m[mo + 6] * z;
        out[o + 2] = m[mo + 8] * x + m[mo + 9] * y + m[mo + 10] * z;
    }

    /** Transforms struct-of-arrays points {@code [start, end)} by one transform, in place. */
    public static void transformPoints(
            float[] m, int mo, float[] xs, float[] ys, float[] zs, int start, int end) {
        float m00 = m[mo], m01 = m[mo + 1], m02 = m[mo + 2], m03 = m[mo + 3];
        float m10 = m[mo + 4], m11 = m[mo + 5], m12 = m[mo + 6], m13 = m[mo + 7];
        float m20 = m[mo + 8], m21 = m[mo + 9], m22 = m[mo + 10], m23 = m[mo + 11];
        for (int i = start; i < end; i++) {
            float x = xs[i];
            float y = ys[i];
            float z = zs[i];
            xs[i] = m00 * x + m01 * y + m02 * z + m03;
            ys[i] = m10 * x + m11 * y + m12 * z + m13;
            zs[i] = m20 * x + m21 * y + m22 * z + m23;
        }
    }

    /**
     * Transforms an axis-aligned box (min/max) and writes the bounding box of the result to {@code
     * out[o..o+5]} as {@code minX, minY, minZ, maxX, maxY, maxZ} (Arvo's method).
     */
    public static void transformAabb(
            float[] m,
            int mo,
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ,
            float[] out,
            int o) {
        for (int r = 0; r < 3; r++) {
            float lo = m[mo + r * 4 + 3];
            float hi = lo;
            float a = m[mo + r * 4] * minX;
            float b = m[mo + r * 4] * maxX;
            lo += Math.min(a, b);
            hi += Math.max(a, b);
            a = m[mo + r * 4 + 1] * minY;
            b = m[mo + r * 4 + 1] * maxY;
            lo += Math.min(a, b);
            hi += Math.max(a, b);
            a = m[mo + r * 4 + 2] * minZ;
            b = m[mo + r * 4 + 2] * maxZ;
            lo += Math.min(a, b);
            hi += Math.max(a, b);
            out[o + r] = lo;
            out[o + 3 + r] = hi;
        }
    }

    /** Converts to a column-major 4×4 matrix (for uniforms). */
    public static void toMat4(float[] m, int mo, float[] out, int o) {
        out[o] = m[mo];
        out[o + 1] = m[mo + 4];
        out[o + 2] = m[mo + 8];
        out[o + 3] = 0;
        out[o + 4] = m[mo + 1];
        out[o + 5] = m[mo + 5];
        out[o + 6] = m[mo + 9];
        out[o + 7] = 0;
        out[o + 8] = m[mo + 2];
        out[o + 9] = m[mo + 6];
        out[o + 10] = m[mo + 10];
        out[o + 11] = 0;
        out[o + 12] = m[mo + 3];
        out[o + 13] = m[mo + 7];
        out[o + 14] = m[mo + 11];
        out[o + 15] = 1;
    }

    /**
     * Linearly interpolates two transforms element-wise (fine for render interpolation between two
     * close simulation ticks; not a true rotation blend).
     */
    public static void lerp(float[] a, int ao, float[] b, int bo, float t, float[] out, int o) {
        for (int i = 0; i < STRIDE; i++) {
            float x = a[ao + i];
            out[o + i] = x + (b[bo + i] - x) * t;
        }
    }
}
