package dev.jeddy.cooker.texture;

import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import javax.imageio.ImageIO;

/**
 * Decodes source images into linear {@link ImageRgba} (TASKS M6-05): PNG (8 and 16 bit), JPEG, BMP
 * and GIF through ImageIO, plus TGA (uncompressed and RLE) and Radiance HDR ({@code .hdr}, RGBE)
 * decoded here, so the cooker needs no image library beyond the JDK.
 *
 * <p>Thread ownership: stateless; any thread.
 */
public final class ImageDecoder {
    private ImageDecoder() {}

    /**
     * Decodes {@code file}.
     *
     * @param srgb {@code true} if the color channels are sRGB-encoded (base color, emissive); HDR
     *     files are always linear
     */
    public static ImageRgba decode(Path file, boolean srgb) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".tga")) {
            return decodeTga(bytes, srgb);
        }
        if (name.endsWith(".hdr")) {
            return decodeHdr(bytes);
        }
        return decode(bytes, srgb, file.toString());
    }

    /** Decodes an ImageIO-supported image from memory. */
    public static ImageRgba decode(byte[] bytes, boolean srgb, String what) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
        if (img == null) {
            throw new IOException(what + ": unsupported image format");
        }
        int w = img.getWidth();
        int h = img.getHeight();
        float[] out = new float[w * h * 4];
        Raster raster = img.getRaster();
        int bands = raster.getNumBands();
        if (img.getColorModel() instanceof IndexColorModel || bands < 1) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = img.getRGB(x, y);
                    int o = (y * w + x) * 4;
                    out[o] = ((argb >>> 16) & 0xFF) / 255f;
                    out[o + 1] = ((argb >>> 8) & 0xFF) / 255f;
                    out[o + 2] = (argb & 0xFF) / 255f;
                    out[o + 3] = (argb >>> 24) / 255f;
                }
            }
        } else {
            int[] sizes = raster.getSampleModel().getSampleSize();
            float[] scale = new float[bands];
            for (int b = 0; b < bands; b++) {
                scale[b] = 1f / ((1L << sizes[b]) - 1);
            }
            boolean alpha = img.getColorModel().hasAlpha();
            int[] px = new int[bands];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    raster.getPixel(x, y, px);
                    int o = (y * w + x) * 4;
                    if (bands <= 2) { // gray (+ alpha)
                        float g = px[0] * scale[0];
                        out[o] = g;
                        out[o + 1] = g;
                        out[o + 2] = g;
                        out[o + 3] = bands == 2 ? px[1] * scale[1] : 1f;
                    } else {
                        out[o] = px[0] * scale[0];
                        out[o + 1] = px[1] * scale[1];
                        out[o + 2] = px[2] * scale[2];
                        out[o + 3] = alpha && bands > 3 ? px[3] * scale[3] : 1f;
                    }
                }
            }
        }
        if (srgb) {
            linearize(out);
        }
        return new ImageRgba(w, h, out);
    }

    private static void linearize(float[] p) {
        for (int i = 0; i < p.length; i++) {
            if ((i & 3) != 3) {
                p[i] = ImageRgba.srgbToLinear(p[i]);
            }
        }
    }

    /** Decodes a TGA: true-color or grayscale, 8/24/32 bits, uncompressed or RLE. */
    static ImageRgba decodeTga(byte[] b, boolean srgb) throws IOException {
        if (b.length < 18) {
            throw new IOException("TGA too short");
        }
        int idLength = b[0] & 0xFF;
        int colorMapType = b[1] & 0xFF;
        int type = b[2] & 0xFF;
        int w = (b[12] & 0xFF) | (b[13] & 0xFF) << 8;
        int h = (b[14] & 0xFF) | (b[15] & 0xFF) << 8;
        int bpp = b[16] & 0xFF;
        int descriptor = b[17] & 0xFF;
        boolean rle = type == 10 || type == 11;
        boolean gray = type == 3 || type == 11;
        if (colorMapType != 0 || !(type == 2 || type == 3 || rle) || w == 0 || h == 0) {
            throw new IOException("unsupported TGA (type " + type + ")");
        }
        int pixelBytes = bpp / 8;
        if (gray ? pixelBytes != 1 : pixelBytes != 3 && pixelBytes != 4) {
            throw new IOException("unsupported TGA depth " + bpp);
        }
        boolean topDown = (descriptor & 0x20) != 0;
        int p = 18 + idLength;
        float[] out = new float[w * h * 4];
        int total = w * h;
        int i = 0;
        while (i < total) {
            int count = 1;
            boolean repeat = false;
            if (rle) {
                int header = b[p++] & 0xFF;
                count = (header & 0x7F) + 1;
                repeat = (header & 0x80) != 0;
            } else {
                count = total;
            }
            for (int k = 0; k < count && i < total; k++, i++) {
                int src = repeat ? p : p + k * pixelBytes;
                if (src + pixelBytes > b.length) {
                    throw new IOException("TGA truncated");
                }
                int row = i / w;
                int col = i % w;
                int y = topDown ? row : h - 1 - row;
                int o = (y * w + col) * 4;
                if (gray) {
                    float g = (b[src] & 0xFF) / 255f;
                    out[o] = g;
                    out[o + 1] = g;
                    out[o + 2] = g;
                    out[o + 3] = 1;
                } else {
                    out[o] = (b[src + 2] & 0xFF) / 255f;
                    out[o + 1] = (b[src + 1] & 0xFF) / 255f;
                    out[o + 2] = (b[src] & 0xFF) / 255f;
                    out[o + 3] = pixelBytes == 4 ? (b[src + 3] & 0xFF) / 255f : 1f;
                }
            }
            p += repeat ? pixelBytes : count * pixelBytes;
        }
        if (srgb) {
            linearize(out);
        }
        return new ImageRgba(w, h, out);
    }

    /** Decodes a Radiance HDR (RGBE) image, flat or with new-style RLE scanlines. */
    static ImageRgba decodeHdr(byte[] b) throws IOException {
        int p = 0;
        String first = line(b, p);
        if (!first.startsWith("#?")) {
            throw new IOException("not a Radiance HDR file");
        }
        p += first.length() + 1;
        while (true) {
            String l = line(b, p);
            p += l.length() + 1;
            if (l.isEmpty()) {
                break;
            }
            if (l.startsWith("FORMAT=") && !l.equals("FORMAT=32-bit_rle_rgbe")) {
                throw new IOException("unsupported HDR format " + l);
            }
        }
        String res = line(b, p);
        p += res.length() + 1;
        String[] t = res.trim().split("\\s+");
        if (t.length != 4 || !t[0].equals("-Y") || !t[2].equals("+X")) {
            throw new IOException("unsupported HDR orientation " + res);
        }
        int h = Integer.parseInt(t[1]);
        int w = Integer.parseInt(t[3]);
        float[] out = new float[w * h * 4];
        byte[] scan = new byte[w * 4];
        for (int y = 0; y < h; y++) {
            if (w >= 8
                    && w < 32768
                    && p + 4 <= b.length
                    && b[p] == 2
                    && b[p + 1] == 2
                    && ((b[p + 2] & 0xFF) << 8 | (b[p + 3] & 0xFF)) == w) {
                p += 4;
                for (int c = 0; c < 4; c++) {
                    int x = 0;
                    while (x < w) {
                        int count = b[p++] & 0xFF;
                        if (count > 128) {
                            count -= 128;
                            byte v = b[p++];
                            for (int k = 0; k < count; k++) {
                                scan[(x++) * 4 + c] = v;
                            }
                        } else {
                            for (int k = 0; k < count; k++) {
                                scan[(x++) * 4 + c] = b[p++];
                            }
                        }
                    }
                }
            } else {
                System.arraycopy(b, p, scan, 0, w * 4);
                p += w * 4;
            }
            for (int x = 0; x < w; x++) {
                int e = scan[x * 4 + 3] & 0xFF;
                int o = (y * w + x) * 4;
                if (e == 0) {
                    out[o + 3] = 1;
                    continue;
                }
                float f = (float) Math.scalb(1.0, e - 136);
                out[o] = (scan[x * 4] & 0xFF) * f;
                out[o + 1] = (scan[x * 4 + 1] & 0xFF) * f;
                out[o + 2] = (scan[x * 4 + 2] & 0xFF) * f;
                out[o + 3] = 1;
            }
        }
        return new ImageRgba(w, h, out);
    }

    private static String line(byte[] b, int from) {
        int end = from;
        while (end < b.length && b[end] != '\n') {
            end++;
        }
        return new String(b, from, end - from, StandardCharsets.US_ASCII);
    }
}
