package dev.jeddy.cooker.texture;

/**
 * Image quality numbers for the cook report (TASKS M6-05): PSNR over the RGB channels of 8-bit
 * images and SSIM of their luma, computed in 8×8 windows.
 *
 * <p>Thread ownership: stateless; any thread.
 */
public final class ImageMetrics {
    private ImageMetrics() {}

    /**
     * Returns the PSNR in dB of {@code b} against {@code a} (RGBA8, alpha ignored); 99 if equal.
     */
    public static double psnr(byte[] a, byte[] b, int channels) {
        double sum = 0;
        long n = 0;
        for (int i = 0; i < a.length; i++) {
            if ((i & 3) >= channels) {
                continue;
            }
            int d = (a[i] & 0xFF) - (b[i] & 0xFF);
            sum += d * d;
            n++;
        }
        if (sum == 0) {
            return 99;
        }
        double mse = sum / n;
        return 10 * Math.log10(255.0 * 255.0 / mse);
    }

    /**
     * Returns the mean SSIM of the luma of two RGBA8 images (8×8 windows, stride 4; rows of windows
     * in parallel).
     */
    public static double ssim(byte[] a, byte[] b, int width, int height) {
        double c1 = (0.01 * 255) * (0.01 * 255);
        double c2 = (0.03 * 255) * (0.03 * 255);
        int rows = height >= 8 ? (height - 8) / 4 + 1 : 0;
        int cols = width >= 8 ? (width - 8) / 4 + 1 : 0;
        if (rows == 0 || cols == 0) {
            return 1;
        }
        double total =
                java.util.stream.IntStream.range(0, rows)
                        .parallel()
                        .mapToDouble(
                                r -> {
                                    int y0 = r * 4;
                                    double sum = 0;
                                    for (int x0 = 0; x0 + 8 <= width; x0 += 4) {
                                        double ma = 0, mb = 0, va = 0, vb = 0, cov = 0;
                                        for (int y = y0; y < y0 + 8; y++) {
                                            for (int x = x0; x < x0 + 8; x++) {
                                                double la = luma(a, (y * width + x) * 4);
                                                double lb = luma(b, (y * width + x) * 4);
                                                ma += la;
                                                mb += lb;
                                                va += la * la;
                                                vb += lb * lb;
                                                cov += la * lb;
                                            }
                                        }
                                        ma /= 64;
                                        mb /= 64;
                                        va = va / 64 - ma * ma;
                                        vb = vb / 64 - mb * mb;
                                        cov = cov / 64 - ma * mb;
                                        sum +=
                                                ((2 * ma * mb + c1) * (2 * cov + c2))
                                                        / ((ma * ma + mb * mb + c1)
                                                                * (va + vb + c2));
                                    }
                                    return sum;
                                })
                        .sum();
        return total / ((double) rows * cols);
    }

    private static double luma(byte[] p, int o) {
        return 0.299 * (p[o] & 0xFF) + 0.587 * (p[o + 1] & 0xFF) + 0.114 * (p[o + 2] & 0xFF);
    }
}
