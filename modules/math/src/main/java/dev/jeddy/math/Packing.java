package dev.jeddy.math;

/**
 * Compact encodings for vertex and texture data (DESIGN §8.3, §10.1): half floats, octahedral
 * normals, snorm/unorm integers, RGBA8 and sRGB transfer functions.
 *
 * <p>Thread ownership: stateless, callable from any thread.
 */
public final class Packing {
    private Packing() {}

    /** Converts to IEEE 754 binary16 bits (intrinsified). */
    public static short toHalf(float f) {
        return Float.floatToFloat16(f);
    }

    /** Converts binary16 bits to a float (intrinsified). */
    public static float fromHalf(short h) {
        return Float.float16ToFloat(h);
    }

    /** Packs {@code v} in [-1, 1] into a signed normalized 16-bit integer. */
    public static short packSnorm16(float v) {
        return (short) Math.round(Math.max(-1f, Math.min(1f, v)) * 32767f);
    }

    /** Unpacks a signed normalized 16-bit integer. */
    public static float unpackSnorm16(short s) {
        return Math.max(-1f, s / 32767f);
    }

    /** Packs {@code v} in [-1, 1] into a signed normalized 8-bit integer. */
    public static byte packSnorm8(float v) {
        return (byte) Math.round(Math.max(-1f, Math.min(1f, v)) * 127f);
    }

    /** Unpacks a signed normalized 8-bit integer. */
    public static float unpackSnorm8(byte b) {
        return Math.max(-1f, b / 127f);
    }

    /** Packs {@code v} in [0, 1] into an unsigned normalized 8-bit integer (returned as int). */
    public static int packUnorm8(float v) {
        return Math.round(Math.max(0f, Math.min(1f, v)) * 255f);
    }

    /** Packs {@code v} in [0, 1] into an unsigned normalized 16-bit integer (returned as int). */
    public static int packUnorm16(float v) {
        return Math.round(Math.max(0f, Math.min(1f, v)) * 65535f);
    }

    /**
     * Encodes a unit vector with octahedral mapping into two snorm16 values packed in an int (x in
     * the low 16 bits). About 0.002° error, 4 bytes instead of 12.
     */
    public static int packOctahedral16(float x, float y, float z) {
        float inv = 1 / (Math.abs(x) + Math.abs(y) + Math.abs(z));
        float u = x * inv;
        float v = y * inv;
        if (z < 0) {
            float ou = u;
            u = (1 - Math.abs(v)) * signNotZero(ou);
            v = (1 - Math.abs(ou)) * signNotZero(v);
        }
        return (packSnorm16(u) & 0xFFFF) | (packSnorm16(v) << 16);
    }

    /** Decodes {@link #packOctahedral16} into {@code out[o..o+2]} (unit length). */
    public static void unpackOctahedral16(int packed, float[] out, int o) {
        float u = unpackSnorm16((short) packed);
        float v = unpackSnorm16((short) (packed >>> 16));
        float z = 1 - Math.abs(u) - Math.abs(v);
        float x = u;
        float y = v;
        if (z < 0) {
            x = (1 - Math.abs(v)) * signNotZero(u);
            y = (1 - Math.abs(u)) * signNotZero(v);
        }
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        out[o] = x / len;
        out[o + 1] = y / len;
        out[o + 2] = z / len;
    }

    private static float signNotZero(float v) {
        return v >= 0 ? 1f : -1f;
    }

    /** Packs linear 0..1 components into {@code 0xAABBGGRR} (R in the lowest byte, GL order). */
    public static int packRgba8(float r, float g, float b, float a) {
        return packUnorm8(r) | (packUnorm8(g) << 8) | (packUnorm8(b) << 16) | (packUnorm8(a) << 24);
    }

    /** Returns component {@code index} (0 = R .. 3 = A) of an RGBA8 value as 0..1. */
    public static float unpackRgba8(int rgba, int index) {
        return ((rgba >>> (index * 8)) & 0xFF) / 255f;
    }

    /** Converts an sRGB-encoded component to linear light (exact piecewise curve). */
    public static float srgbToLinear(float c) {
        return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** Converts a linear component to sRGB encoding (exact piecewise curve). */
    public static float linearToSrgb(float c) {
        return c <= 0.0031308f ? c * 12.92f : (float) (1.055 * Math.pow(c, 1 / 2.4) - 0.055);
    }
}
