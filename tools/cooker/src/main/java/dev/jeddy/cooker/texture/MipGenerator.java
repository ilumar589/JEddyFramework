package dev.jeddy.cooker.texture;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a full mip chain in linear space with a Kaiser-windowed sinc filter (TASKS M6-05, DESIGN
 * §10.3). Separable, three destination pixels of support, wrap or clamp at the edges. Normal maps
 * are renormalized per level; alpha-tested textures can keep their alpha coverage.
 *
 * <p>Thread ownership: stateless; any thread.
 */
public final class MipGenerator {
    private MipGenerator() {}

    private static final double ALPHA = 4.0;
    private static final double RADIUS = 1.5; // in destination pixels

    /** Options. */
    public record Options(boolean wrap, boolean normalMap, float alphaCutoff) {
        /** Color texture, tiling. */
        public static final Options COLOR = new Options(true, false, -1);
    }

    /** Returns levels 0..n (level 0 is {@code base} itself) down to 1×1. */
    public static List<ImageRgba> chain(ImageRgba base, Options options) {
        List<ImageRgba> levels = new ArrayList<>();
        levels.add(base);
        float coverage = options.alphaCutoff > 0 ? coverage(base, options.alphaCutoff, 1) : -1;
        ImageRgba current = base;
        while (current.width() > 1 || current.height() > 1) {
            int w = Math.max(1, current.width() / 2);
            int h = Math.max(1, current.height() / 2);
            ImageRgba next = downsample(current, w, h, options.wrap);
            if (options.normalMap) {
                renormalize(next);
            }
            if (coverage >= 0) {
                keepCoverage(next, options.alphaCutoff, coverage);
            }
            levels.add(next);
            current = next;
        }
        return levels;
    }

    /** Resamples {@code src} to {@code w}×{@code h} (each axis shrinks or stays). */
    public static ImageRgba downsample(ImageRgba src, int w, int h, boolean wrap) {
        ImageRgba horizontal = new ImageRgba(w, src.height());
        filterAxis(src.pixels(), src.width(), src.height(), horizontal.pixels(), w, true, wrap);
        ImageRgba out = new ImageRgba(w, h);
        filterAxis(horizontal.pixels(), w, src.height(), out.pixels(), h, false, wrap);
        return out;
    }

    private static void filterAxis(
            float[] src,
            int sw,
            int sh,
            float[] dst,
            int dstLength,
            boolean horizontal,
            boolean wrap) {
        int srcLength = horizontal ? sw : sh;
        double scale = (double) srcLength / dstLength;
        int taps = (int) Math.ceil(RADIUS * scale) * 2 + 2;
        int[] index = new int[dstLength * taps];
        float[] weight = new float[dstLength * taps];
        for (int i = 0; i < dstLength; i++) {
            double center = (i + 0.5) * scale - 0.5;
            int first = (int) Math.floor(center - RADIUS * scale);
            double sum = 0;
            for (int k = 0; k < taps; k++) {
                int j = first + k;
                double t = (j - center) / scale;
                double wgt = Math.abs(t) >= RADIUS ? 0 : sinc(t) * kaiser(t / RADIUS);
                if (scale == 1) {
                    wgt = j == Math.round(center) ? 1 : 0;
                }
                int s =
                        wrap
                                ? Math.floorMod(j, srcLength)
                                : Math.max(0, Math.min(srcLength - 1, j));
                index[i * taps + k] = s;
                weight[i * taps + k] = (float) wgt;
                sum += wgt;
            }
            for (int k = 0; k < taps; k++) {
                weight[i * taps + k] /= (float) sum;
            }
        }
        int other = horizontal ? sh : sw;
        int dw = horizontal ? dstLength : sw;
        for (int o = 0; o < other; o++) {
            for (int i = 0; i < dstLength; i++) {
                float r = 0, g = 0, b = 0, a = 0;
                for (int k = 0; k < taps; k++) {
                    float wgt = weight[i * taps + k];
                    if (wgt == 0) {
                        continue;
                    }
                    int s = index[i * taps + k];
                    int p = horizontal ? (o * sw + s) * 4 : (s * sw + o) * 4;
                    r += src[p] * wgt;
                    g += src[p + 1] * wgt;
                    b += src[p + 2] * wgt;
                    a += src[p + 3] * wgt;
                }
                int d = horizontal ? (o * dw + i) * 4 : (i * dw + o) * 4;
                dst[d] = r;
                dst[d + 1] = g;
                dst[d + 2] = b;
                dst[d + 3] = a;
            }
        }
    }

    private static double sinc(double x) {
        if (Math.abs(x) < 1e-6) {
            return 1;
        }
        double px = Math.PI * x;
        return Math.sin(px) / px;
    }

    private static double kaiser(double x) {
        double t = 1 - x * x;
        return t <= 0 ? 0 : bessel0(ALPHA * Math.sqrt(t)) / bessel0(ALPHA);
    }

    private static double bessel0(double x) {
        double sum = 1;
        double term = 1;
        double h = x * x / 4;
        for (int k = 1; k < 40; k++) {
            term *= h / (k * k);
            sum += term;
            if (term < 1e-12 * sum) {
                break;
            }
        }
        return sum;
    }

    /** Re-unit-lengths tangent-space normals stored as {@code [0, 1]} RGB. */
    static void renormalize(ImageRgba img) {
        float[] p = img.pixels();
        for (int i = 0; i < p.length; i += 4) {
            float x = p[i] * 2 - 1;
            float y = p[i + 1] * 2 - 1;
            float z = p[i + 2] * 2 - 1;
            float len = (float) Math.sqrt(x * x + y * y + z * z);
            if (len < 1e-6f) {
                x = 0;
                y = 0;
                z = 1;
                len = 1;
            }
            p[i] = (x / len + 1) * 0.5f;
            p[i + 1] = (y / len + 1) * 0.5f;
            p[i + 2] = (z / len + 1) * 0.5f;
        }
    }

    /** Fraction of pixels whose alpha × {@code scale} passes {@code cutoff}. */
    static float coverage(ImageRgba img, float cutoff, float scale) {
        float[] p = img.pixels();
        int pass = 0;
        for (int i = 3; i < p.length; i += 4) {
            if (p[i] * scale >= cutoff) {
                pass++;
            }
        }
        return pass / (float) (p.length / 4);
    }

    /** Scales alpha so the level's coverage at {@code cutoff} matches {@code target}. */
    static void keepCoverage(ImageRgba img, float cutoff, float target) {
        float lo = 0;
        float hi = 4;
        for (int iter = 0; iter < 20; iter++) {
            float mid = (lo + hi) / 2;
            if (coverage(img, cutoff, mid) < target) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        float s = (lo + hi) / 2;
        float[] p = img.pixels();
        for (int i = 3; i < p.length; i += 4) {
            p[i] = Math.min(1, p[i] * s);
        }
    }
}
