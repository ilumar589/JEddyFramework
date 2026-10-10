package dev.jeddy.cooker;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.function.IntBinaryOperator;

import javax.imageio.ImageIO;

/** Small generated source images for cooker tests. */
public final class TestImages {
    private TestImages() {}

    /** A PNG whose pixel {@code (x, y)} is {@code argb.applyAsInt(x, y)}. */
    public static byte[] png(int w, int h, IntBinaryOperator argb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, argb.applyAsInt(x, y));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** A smooth opaque color image with some detail. */
    public static byte[] painted(int size, int seed) {
        return png(
                size,
                size,
                (x, y) -> {
                    int r = (int) (128 + 100 * Math.sin((x + seed) * 0.21));
                    int g = (int) (128 + 90 * Math.cos(y * 0.17 + seed));
                    int b = (int) (100 + 60 * Math.sin((x + y) * 0.09));
                    return 0xFF000000 | r << 16 | g << 8 | b;
                });
    }

    /** A tangent-space normal map with gentle bumps. */
    public static byte[] normalMap(int size) {
        return png(
                size,
                size,
                (x, y) -> {
                    double nx = 0.3 * Math.sin(x * 0.4);
                    double ny = 0.3 * Math.cos(y * 0.3);
                    double nz = Math.sqrt(1 - nx * nx - ny * ny);
                    int r = (int) Math.round((nx * 0.5 + 0.5) * 255);
                    int g = (int) Math.round((ny * 0.5 + 0.5) * 255);
                    int b = (int) Math.round((nz * 0.5 + 0.5) * 255);
                    return 0xFF000000 | r << 16 | g << 8 | b;
                });
    }

    /** A Radiance HDR (flat scanlines) of {@code w × h} with radiance {@code rgb(x, y)}. */
    public static byte[] hdr(int w, int h, RgbFunction rgb) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(
                ("#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y " + h + " +X " + w + "\n")
                        .getBytes(StandardCharsets.US_ASCII));
        float[] c = new float[3];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                rgb.at(x, y, c);
                float max = Math.max(c[0], Math.max(c[1], c[2]));
                if (max < 1e-32f) {
                    out.write(0);
                    out.write(0);
                    out.write(0);
                    out.write(0);
                    continue;
                }
                int e = Math.getExponent(max) + 1; // max = m * 2^e with m in [0.5, 1)
                double scale = 256.0 / Math.scalb(1.0, e);
                out.write((int) Math.min(255, c[0] * scale));
                out.write((int) Math.min(255, c[1] * scale));
                out.write((int) Math.min(255, c[2] * scale));
                out.write(e + 128);
            }
        }
        return out.toByteArray();
    }

    /** Radiance per pixel. */
    @FunctionalInterface
    public interface RgbFunction {
        /** Writes the radiance at {@code (x, y)} into {@code out}. */
        void at(int x, int y, float[] out);
    }
}
