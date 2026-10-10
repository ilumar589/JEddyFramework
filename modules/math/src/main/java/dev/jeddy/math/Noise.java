package dev.jeddy.math;

/**
 * Coherent noise for VFX, terrain variation and procedural placement: 2D/3D simplex noise
 * (Gustavson's public-domain formulation) and fractal sums. Deterministic for a given seed.
 *
 * <p>Thread ownership: immutable after construction; callable from any thread.
 */
public final class Noise {
    private static final int[][] GRAD3 = {
        {1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0}, {1, 0, 1}, {-1, 0, 1},
        {1, 0, -1}, {-1, 0, -1}, {0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1}
    };
    private static final float F2 = (float) (0.5 * (Math.sqrt(3) - 1));
    private static final float G2 = (float) ((3 - Math.sqrt(3)) / 6);
    private static final float F3 = 1f / 3f;
    private static final float G3 = 1f / 6f;

    private final short[] perm = new short[512];
    private final short[] permMod12 = new short[512];

    /** Creates noise with a permutation derived from {@code seed}. */
    public Noise(long seed) {
        short[] p = new short[256];
        for (int i = 0; i < 256; i++) {
            p[i] = (short) i;
        }
        Rng rng = new Rng(seed);
        for (int i = 255; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            short t = p[i];
            p[i] = p[j];
            p[j] = t;
        }
        for (int i = 0; i < 512; i++) {
            perm[i] = p[i & 255];
            permMod12[i] = (short) (perm[i] % 12);
        }
    }

    /** 2D simplex noise in about {@code [-1, 1]}. */
    public float noise(float x, float y) {
        float s = (x + y) * F2;
        int i = MathF.floorToInt(x + s);
        int j = MathF.floorToInt(y + s);
        float t = (i + j) * G2;
        float x0 = x - (i - t);
        float y0 = y - (j - t);
        int i1 = x0 > y0 ? 1 : 0;
        int j1 = x0 > y0 ? 0 : 1;
        float x1 = x0 - i1 + G2;
        float y1 = y0 - j1 + G2;
        float x2 = x0 - 1 + 2 * G2;
        float y2 = y0 - 1 + 2 * G2;
        int ii = i & 255;
        int jj = j & 255;
        float n0 = corner2(permMod12[ii + perm[jj]], x0, y0);
        float n1 = corner2(permMod12[ii + i1 + perm[jj + j1]], x1, y1);
        float n2 = corner2(permMod12[ii + 1 + perm[jj + 1]], x2, y2);
        return 70 * (n0 + n1 + n2);
    }

    private static float corner2(int gi, float x, float y) {
        float t = 0.5f - x * x - y * y;
        if (t < 0) {
            return 0;
        }
        t *= t;
        return t * t * (GRAD3[gi][0] * x + GRAD3[gi][1] * y);
    }

    /** 3D simplex noise in about {@code [-1, 1]}. */
    public float noise(float x, float y, float z) {
        float s = (x + y + z) * F3;
        int i = MathF.floorToInt(x + s);
        int j = MathF.floorToInt(y + s);
        int k = MathF.floorToInt(z + s);
        float t = (i + j + k) * G3;
        float x0 = x - (i - t);
        float y0 = y - (j - t);
        float z0 = z - (k - t);
        int i1, j1, k1, i2, j2, k2;
        if (x0 >= y0) {
            if (y0 >= z0) {
                i1 = 1; j1 = 0; k1 = 0; i2 = 1; j2 = 1; k2 = 0;
            } else if (x0 >= z0) {
                i1 = 1; j1 = 0; k1 = 0; i2 = 1; j2 = 0; k2 = 1;
            } else {
                i1 = 0; j1 = 0; k1 = 1; i2 = 1; j2 = 0; k2 = 1;
            }
        } else {
            if (y0 < z0) {
                i1 = 0; j1 = 0; k1 = 1; i2 = 0; j2 = 1; k2 = 1;
            } else if (x0 < z0) {
                i1 = 0; j1 = 1; k1 = 0; i2 = 0; j2 = 1; k2 = 1;
            } else {
                i1 = 0; j1 = 1; k1 = 0; i2 = 1; j2 = 1; k2 = 0;
            }
        }
        float x1 = x0 - i1 + G3, y1 = y0 - j1 + G3, z1 = z0 - k1 + G3;
        float x2 = x0 - i2 + 2 * G3, y2 = y0 - j2 + 2 * G3, z2 = z0 - k2 + 2 * G3;
        float x3 = x0 - 1 + 3 * G3, y3 = y0 - 1 + 3 * G3, z3 = z0 - 1 + 3 * G3;
        int ii = i & 255;
        int jj = j & 255;
        int kk = k & 255;
        float n0 = corner3(permMod12[ii + perm[jj + perm[kk]]], x0, y0, z0);
        float n1 = corner3(permMod12[ii + i1 + perm[jj + j1 + perm[kk + k1]]], x1, y1, z1);
        float n2 = corner3(permMod12[ii + i2 + perm[jj + j2 + perm[kk + k2]]], x2, y2, z2);
        float n3 = corner3(permMod12[ii + 1 + perm[jj + 1 + perm[kk + 1]]], x3, y3, z3);
        return 32 * (n0 + n1 + n2 + n3);
    }

    private static float corner3(int gi, float x, float y, float z) {
        float t = 0.6f - x * x - y * y - z * z;
        if (t < 0) {
            return 0;
        }
        t *= t;
        return t * t * (GRAD3[gi][0] * x + GRAD3[gi][1] * y + GRAD3[gi][2] * z);
    }

    /** Fractal Brownian motion: {@code octaves} layers of 2D noise, normalized to about [-1, 1]. */
    public float fbm(float x, float y, int octaves, float lacunarity, float gain) {
        float sum = 0;
        float amp = 1;
        float norm = 0;
        float fx = x;
        float fy = y;
        for (int o = 0; o < octaves; o++) {
            sum += amp * noise(fx, fy);
            norm += amp;
            amp *= gain;
            fx *= lacunarity;
            fy *= lacunarity;
        }
        return sum / norm;
    }
}
