package dev.jeddy.asset.format;

/**
 * Vertex attribute encodings of {@code .jmesh} ({@code docs/formats/jmesh.md}): the tangent frame
 * as a QTangent (a unit quaternion in four {@code snorm16}; the sign of {@code w} is the bitangent
 * handedness), texture coordinates as half floats and skin weights as {@code unorm16}. The shaders
 * decode the same way as {@link #qtangentNormal} and {@link #qtangentTangent}.
 *
 * <p>Thread ownership: stateless, callable from any thread.
 */
public final class MeshEncoding {
    private MeshEncoding() {}

    /** Smallest |w| written, so the handedness sign survives quantization. */
    private static final float W_BIAS = 1f / 32767f;

    /**
     * Encodes a tangent frame as a QTangent.
     *
     * @param n unit normal (x, y, z at {@code nOff})
     * @param t tangent (x, y, z at {@code tOff}); orthogonalized against the normal here
     * @param handedness +1 or -1: {@code bitangent = cross(normal, tangent) * handedness}
     * @param out receives four {@code snorm16} values (x, y, z, w) at {@code outOff}
     */
    public static void encodeQTangent(
            float[] n, int nOff, float[] t, int tOff, float handedness, short[] out, int outOff) {
        float nx = n[nOff];
        float ny = n[nOff + 1];
        float nz = n[nOff + 2];
        float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (nl < 1e-12f) {
            nx = 0;
            ny = 0;
            nz = 1;
        } else {
            nx /= nl;
            ny /= nl;
            nz /= nl;
        }
        float tx = t[tOff];
        float ty = t[tOff + 1];
        float tz = t[tOff + 2];
        float d = nx * tx + ny * ty + nz * tz;
        tx -= nx * d;
        ty -= ny * d;
        tz -= nz * d;
        float tl = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (tl < 1e-12f) { // degenerate: any vector orthogonal to the normal
            if (Math.abs(nx) < 0.9f) {
                tx = 0;
                ty = -nz;
                tz = ny;
            } else {
                tx = nz;
                ty = 0;
                tz = -nx;
            }
            tl = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
        }
        tx /= tl;
        ty /= tl;
        tz /= tl;
        // b = cross(n, t): the matrix [t b n] is a rotation.
        float bx = ny * tz - nz * ty;
        float by = nz * tx - nx * tz;
        float bz = nx * ty - ny * tx;

        float m00 = tx, m10 = ty, m20 = tz;
        float m01 = bx, m11 = by, m21 = bz;
        float m02 = nx, m12 = ny, m22 = nz;
        float qx, qy, qz, qw;
        float trace = m00 + m11 + m22;
        if (trace > 0) {
            float s = (float) Math.sqrt(trace + 1) * 2;
            qw = 0.25f * s;
            qx = (m21 - m12) / s;
            qy = (m02 - m20) / s;
            qz = (m10 - m01) / s;
        } else if (m00 > m11 && m00 > m22) {
            float s = (float) Math.sqrt(1 + m00 - m11 - m22) * 2;
            qw = (m21 - m12) / s;
            qx = 0.25f * s;
            qy = (m01 + m10) / s;
            qz = (m02 + m20) / s;
        } else if (m11 > m22) {
            float s = (float) Math.sqrt(1 + m11 - m00 - m22) * 2;
            qw = (m02 - m20) / s;
            qx = (m01 + m10) / s;
            qy = 0.25f * s;
            qz = (m12 + m21) / s;
        } else {
            float s = (float) Math.sqrt(1 + m22 - m00 - m11) * 2;
            qw = (m10 - m01) / s;
            qx = (m02 + m20) / s;
            qy = (m12 + m21) / s;
            qz = 0.25f * s;
        }
        if (qw < 0) {
            qx = -qx;
            qy = -qy;
            qz = -qz;
            qw = -qw;
        }
        if (qw < W_BIAS) {
            float scale = (float) Math.sqrt(1 - W_BIAS * W_BIAS);
            float len = (float) Math.sqrt(qx * qx + qy * qy + qz * qz);
            qx = qx / len * scale;
            qy = qy / len * scale;
            qz = qz / len * scale;
            qw = W_BIAS;
        }
        if (handedness < 0) {
            qx = -qx;
            qy = -qy;
            qz = -qz;
            qw = -qw;
        }
        out[outOff] = snorm16(qx);
        out[outOff + 1] = snorm16(qy);
        out[outOff + 2] = snorm16(qz);
        out[outOff + 3] = snorm16(qw);
    }

    /** Decodes the normal of a QTangent into {@code out[outOff..+3)}. */
    public static void qtangentNormal(short[] q, int qOff, float[] out, int outOff) {
        rotate(q, qOff, 0, 0, 1, out, outOff);
    }

    /** Decodes the tangent of a QTangent into {@code out[outOff..+3)}. */
    public static void qtangentTangent(short[] q, int qOff, float[] out, int outOff) {
        rotate(q, qOff, 1, 0, 0, out, outOff);
    }

    /** Returns the bitangent handedness (+1 or -1) of a QTangent. */
    public static float qtangentHandedness(short[] q, int qOff) {
        return q[qOff + 3] < 0 ? -1f : 1f;
    }

    private static void rotate(
            short[] q, int qOff, float vx, float vy, float vz, float[] out, int outOff) {
        float x = unsnorm16(q[qOff]);
        float y = unsnorm16(q[qOff + 1]);
        float z = unsnorm16(q[qOff + 2]);
        float w = unsnorm16(q[qOff + 3]);
        float len = (float) Math.sqrt(x * x + y * y + z * z + w * w);
        x /= len;
        y /= len;
        z /= len;
        w /= len;
        // v' = v + 2 * cross(q.xyz, cross(q.xyz, v) + w * v)
        float cx = y * vz - z * vy + w * vx;
        float cy = z * vx - x * vz + w * vy;
        float cz = x * vy - y * vx + w * vz;
        out[outOff] = vx + 2 * (y * cz - z * cy);
        out[outOff + 1] = vy + 2 * (z * cx - x * cz);
        out[outOff + 2] = vz + 2 * (x * cy - y * cx);
    }

    /** Returns {@code v} clamped to [-1, 1] as a rounded {@code snorm16}. */
    public static short snorm16(float v) {
        return (short) Math.round(Math.max(-1f, Math.min(1f, v)) * 32767f);
    }

    /** Returns the value of an {@code snorm16}. */
    public static float unsnorm16(short v) {
        return Math.max(-1f, v / 32767f);
    }

    /** Returns {@code v} clamped to [0, 1] as a rounded {@code unorm16} (in a {@code short}). */
    public static short unorm16(float v) {
        return (short) Math.round(Math.max(0f, Math.min(1f, v)) * 65535f);
    }

    /** Returns the value of an {@code unorm16}. */
    public static float ununorm16(short v) {
        return Short.toUnsignedInt(v) / 65535f;
    }

    /** Returns {@code v} as an IEEE half float. */
    public static short half(float v) {
        return Float.floatToFloat16(v);
    }

    /** Returns the value of an IEEE half float. */
    public static float unhalf(short v) {
        return Float.float16ToFloat(v);
    }
}
