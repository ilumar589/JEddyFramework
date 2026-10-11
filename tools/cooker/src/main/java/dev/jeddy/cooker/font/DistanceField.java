package dev.jeddy.cooker.font;

/**
 * Signed distance fields of antialiased glyph masks (TASKS M13-02, DESIGN §18, D83).
 *
 * <p>The exact Euclidean distance transform is Felzenszwalb and Huttenlocher's: the squared
 * distance to the nearest "on" pixel is the lower envelope of parabolas, one per on pixel, which a
 * separable one-dimensional pass computes in linear time, first along rows and then along columns.
 * Running it on the mask gives every pixel's distance to the glyph; running it on the inverted mask
 * gives the distance to the background. The signed distance is positive inside.
 *
 * <p>A binary mask only knows distances in whole pixels, so the field would place the outline at
 * pixel centers. The antialiased coverage fixes that at the edge: a pixel covered by a fraction
 * {@code c} of the outline has its edge about {@code c - 0.5} pixels from its center, which is
 * exactly the sub-pixel distance we want for the pixels that matter most.
 *
 * <p>Thread ownership: stateless; any thread.
 */
public final class DistanceField {
    private static final float INF = 1e20f;

    private DistanceField() {}

    /**
     * Computes the signed distance of every pixel of an antialiased coverage mask.
     *
     * @param coverage {@code width × height} coverage values in 0..255, row-major
     * @return the signed distance in pixels, positive inside the shape
     */
    public static float[] signed(byte[] coverage, int width, int height) {
        float[] inside = new float[width * height]; // squared distance to the nearest inside pixel
        float[] outside = new float[width * height]; // to the nearest outside pixel
        for (int i = 0; i < inside.length; i++) {
            boolean on = (coverage[i] & 0xFF) >= 128;
            inside[i] = on ? 0 : INF;
            outside[i] = on ? INF : 0;
        }
        transform(inside, width, height);
        transform(outside, width, height);
        float[] sd = new float[width * height];
        for (int i = 0; i < sd.length; i++) {
            int c = coverage[i] & 0xFF;
            if (c > 0 && c < 255) {
                // Partially covered: the outline runs through this pixel.
                sd[i] = c / 255f - 0.5f;
            } else if (c == 255) {
                // Inside: the nearest outside pixel's center is sqrt(outside) away, and the
                // outline sits half a pixel before it.
                sd[i] = (float) Math.sqrt(outside[i]) - 0.5f;
            } else {
                sd[i] = 0.5f - (float) Math.sqrt(inside[i]);
            }
        }
        return sd;
    }

    /**
     * Encodes a signed distance as the atlas stores it: {@code 0.5 + d / (2 · range)}, clamped, in
     * 0..255.
     */
    public static byte encode(float distance, float range) {
        float v = 0.5f + distance / (2 * range);
        int b = Math.round(Math.clamp(v, 0f, 1f) * 255f);
        return (byte) b;
    }

    /**
     * In-place squared Euclidean distance transform: on entry a cell is 0 where it is a source and
     * {@code INF} elsewhere; on exit it holds the squared distance to the nearest source.
     */
    static void transform(float[] grid, int width, int height) {
        int n = Math.max(width, height);
        float[] f = new float[n];
        float[] d = new float[n];
        int[] v = new int[n];
        float[] z = new float[n + 1];
        // Rows.
        for (int y = 0; y < height; y++) {
            int o = y * width;
            System.arraycopy(grid, o, f, 0, width);
            transform1d(f, width, d, v, z);
            System.arraycopy(d, 0, grid, o, width);
        }
        // Columns.
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                f[y] = grid[y * width + x];
            }
            transform1d(f, height, d, v, z);
            for (int y = 0; y < height; y++) {
                grid[y * width + x] = d[y];
            }
        }
    }

    /**
     * One-dimensional transform of {@code f[0..n)} into {@code d}: {@code d[q] = min_p (q - p)² +
     * f[p]}. {@code v} holds the parabola vertices of the lower envelope and {@code z} the
     * boundaries between them.
     */
    static void transform1d(float[] f, int n, float[] d, int[] v, float[] z) {
        int k = 0;
        v[0] = 0;
        z[0] = -INF;
        z[1] = INF;
        for (int q = 1; q < n; q++) {
            // Intersection of the parabola from q with the one from v[k].
            float s = intersect(f, q, v[k]);
            while (s <= z[k]) {
                k--;
                s = intersect(f, q, v[k]);
            }
            k++;
            v[k] = q;
            z[k] = s;
            z[k + 1] = INF;
        }
        k = 0;
        for (int q = 0; q < n; q++) {
            while (z[k + 1] < q) {
                k++;
            }
            float dq = q - v[k];
            d[q] = dq * dq + f[v[k]];
        }
    }

    private static float intersect(float[] f, int q, int p) {
        // ((f[q] + q²) - (f[p] + p²)) / (2q - 2p); INF - INF would be NaN, so treat it as +INF.
        float fq = f[q];
        float fp = f[p];
        if (fq >= INF && fp >= INF) {
            return INF;
        }
        return ((fq + (float) q * q) - (fp + (float) p * p)) / (2f * q - 2f * p);
    }
}
