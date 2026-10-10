package dev.jeddy.cooker.texture;

/**
 * A float RGBA image in linear space, the cooker's working format for textures (TASKS M6-05):
 * decoders convert into it (sRGB inputs are linearized), mip generation and channel packing work on
 * it, and the encoder quantizes it back to 8 bits.
 *
 * <p>Thread ownership: not thread-safe; one cook job owns an image.
 */
public final class ImageRgba {
    private final int width;
    private final int height;
    private final float[] pixels;

    /** Creates a black, transparent image. */
    public ImageRgba(int width, int height) {
        this(width, height, new float[width * height * 4]);
    }

    /** Wraps {@code pixels} (4 floats per pixel, row-major, top row first). */
    public ImageRgba(int width, int height, float[] pixels) {
        if (width <= 0 || height <= 0 || pixels.length != width * height * 4) {
            throw new IllegalArgumentException("bad image size " + width + "x" + height);
        }
        this.width = width;
        this.height = height;
        this.pixels = pixels;
    }

    /** Returns the width. */
    public int width() {
        return width;
    }

    /** Returns the height. */
    public int height() {
        return height;
    }

    /** Returns the pixel array (RGBA, 4 floats per pixel). */
    public float[] pixels() {
        return pixels;
    }

    /** Returns channel {@code c} of pixel {@code (x, y)}. */
    public float get(int x, int y, int c) {
        return pixels[(y * width + x) * 4 + c];
    }

    /** Sets channel {@code c} of pixel {@code (x, y)}. */
    public void set(int x, int y, int c, float v) {
        pixels[(y * width + x) * 4 + c] = v;
    }

    /** Returns a copy. */
    public ImageRgba copy() {
        return new ImageRgba(width, height, pixels.clone());
    }

    /** sRGB to linear (exact piecewise curve). */
    public static float srgbToLinear(float s) {
        return s <= 0.04045f ? s / 12.92f : (float) Math.pow((s + 0.055) / 1.055, 2.4);
    }

    /** Linear to sRGB. */
    public static float linearToSrgb(float l) {
        return l <= 0.0031308f ? l * 12.92f : (float) (1.055 * Math.pow(l, 1 / 2.4) - 0.055);
    }

    /**
     * Linear [0, 1] quantized to 16 bits → sRGB byte. 65536 entries keep the error under 0.03 of an
     * 8-bit step even where the curve is steepest (near black).
     */
    private static final byte[] SRGB_ENCODE = new byte[65536];

    static {
        for (int i = 0; i < SRGB_ENCODE.length; i++) {
            SRGB_ENCODE[i] = (byte) Math.round(linearToSrgb(i / 65535f) * 255f);
        }
    }

    /**
     * Quantizes to RGBA8 (sRGB-encoding the color channels when {@code srgb}; alpha is always
     * linear). Large images convert rows in parallel.
     */
    public byte[] toRgba8(boolean srgb) {
        byte[] out = new byte[width * height * 4];
        java.util.stream.IntStream rows = java.util.stream.IntStream.range(0, height);
        if ((long) width * height >= 1 << 16) {
            rows = rows.parallel();
        }
        rows.forEach(
                y -> {
                    int end = (y + 1) * width * 4;
                    for (int i = y * width * 4; i < end; i++) {
                        float v = Math.max(0, Math.min(1, pixels[i]));
                        out[i] =
                                srgb && (i & 3) != 3
                                        ? SRGB_ENCODE[(int) (v * 65535f + 0.5f)]
                                        : (byte) Math.round(v * 255f);
                    }
                });
        return out;
    }

    /** Creates an image from RGBA8 bytes, linearizing the color channels when {@code srgb}. */
    public static ImageRgba fromRgba8(int width, int height, byte[] rgba, boolean srgb) {
        float[] p = new float[width * height * 4];
        float[] lut = new float[256];
        for (int i = 0; i < 256; i++) {
            lut[i] = srgb ? srgbToLinear(i / 255f) : i / 255f;
        }
        for (int i = 0; i < p.length; i++) {
            int b = rgba[i] & 0xFF;
            p[i] = (i & 3) == 3 ? b / 255f : lut[b];
        }
        return new ImageRgba(width, height, p);
    }
}
