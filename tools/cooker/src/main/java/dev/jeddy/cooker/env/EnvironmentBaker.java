package dev.jeddy.cooker.env;

import dev.jeddy.cooker.texture.ImageRgba;
import dev.jeddy.core.job.JobSystem;

import java.util.ArrayList;
import java.util.List;

/**
 * Image-based lighting bakes on the CPU (TASKS M6-07, DESIGN §9.3, §10.3):
 *
 * <ul>
 *   <li>{@link #fromEquirect}: an equirectangular radiance map to a cube map. The image center
 *       looks down −Z, row 0 is straight up (+Y), and {@code rotation} turns it about +Y.
 *   <li>{@link #prefilter}: GGX-prefiltered specular mips ({@code roughness = level / (levels -
 *       1)}, {@code α = roughness²}) by filtered importance sampling: each sample reads the source
 *       mip whose texel solid angle matches the sample's (Křivánek and Colbert), which keeps bright
 *       spots from turning into fireflies at moderate sample counts.
 *   <li>{@link #irradianceSh9}: diffuse irradiance as 9 spherical-harmonic coefficients per
 *       channel, already convolved with the clamped cosine ({@code E(n) = Σ c_i Y_i(n)}).
 *   <li>{@link #brdfLut}: the split-sum environment BRDF (scale, bias) over {@code (N·V,
 *       roughness)}.
 * </ul>
 *
 * Cube faces are in GL order (+X, −X, +Y, −Y, +Z, −Z) and use GL's face coordinate table, so the
 * runtime samples them with a plain {@code samplerCube}.
 *
 * <p>Thread ownership: stateless; any thread. The heavy loops fan out on the given job system.
 */
public final class EnvironmentBaker {
    private EnvironmentBaker() {}

    /** A cube map: 6 faces of {@code size × size} linear RGB floats. */
    public record Cube(int size, float[][] faces) {
        /** An empty cube. */
        public Cube(int size) {
            this(size, new float[6][size * size * 3]);
        }
    }

    /** Writes the direction through face {@code f}'s texel center {@code (x, y)} into {@code d}. */
    static void direction(int f, int x, int y, int size, float[] d) {
        float s = 2f * (x + 0.5f) / size - 1f;
        float t = 2f * (y + 0.5f) / size - 1f;
        float dx;
        float dy;
        float dz;
        switch (f) {
            case 0 -> {
                dx = 1;
                dy = -t;
                dz = -s;
            }
            case 1 -> {
                dx = -1;
                dy = -t;
                dz = s;
            }
            case 2 -> {
                dx = s;
                dy = 1;
                dz = t;
            }
            case 3 -> {
                dx = s;
                dy = -1;
                dz = -t;
            }
            case 4 -> {
                dx = s;
                dy = -t;
                dz = 1;
            }
            default -> {
                dx = -s;
                dy = -t;
                dz = -1;
            }
        }
        float inv = (float) (1 / Math.sqrt(dx * dx + dy * dy + dz * dz));
        d[0] = dx * inv;
        d[1] = dy * inv;
        d[2] = dz * inv;
    }

    /** Converts an equirectangular map, supersampling when the source is much larger. */
    public static Cube fromEquirect(ImageRgba eq, int size, float rotationRadians, JobSystem jobs) {
        Cube cube = new Cube(size);
        int k = Math.max(1, Math.min(4, eq.width() / (4 * size)));
        float cos = (float) Math.cos(rotationRadians);
        float sin = (float) Math.sin(rotationRadians);
        jobs.parallelFor(
                (start, end, thread) -> {
                    float[] d = new float[3];
                    float[] rgb = new float[3];
                    for (int row = start; row < end; row++) {
                        int f = row / size;
                        int y = row % size;
                        float[] face = cube.faces()[f];
                        for (int x = 0; x < size; x++) {
                            float r = 0;
                            float g = 0;
                            float b = 0;
                            for (int sy = 0; sy < k; sy++) {
                                for (int sx = 0; sx < k; sx++) {
                                    direction(f, x * k + sx, y * k + sy, size * k, d);
                                    float dx = d[0] * cos + d[2] * sin;
                                    float dz = -d[0] * sin + d[2] * cos;
                                    sampleEquirect(eq, dx, d[1], dz, rgb);
                                    r += rgb[0];
                                    g += rgb[1];
                                    b += rgb[2];
                                }
                            }
                            float w = 1f / (k * k);
                            int o = (y * size + x) * 3;
                            face[o] = r * w;
                            face[o + 1] = g * w;
                            face[o + 2] = b * w;
                        }
                    }
                },
                6 * size,
                Math.max(1, size / 8));
        return cube;
    }

    private static void sampleEquirect(ImageRgba eq, float x, float y, float z, float[] out) {
        double u = 0.5 + Math.atan2(x, -z) / (2 * Math.PI);
        double v = Math.acos(Math.max(-1, Math.min(1, y))) / Math.PI;
        int w = eq.width();
        int h = eq.height();
        double px = u * w - 0.5;
        double py = Math.max(0, Math.min(h - 1, v * h - 0.5));
        int x0 = (int) Math.floor(px);
        int y0 = (int) Math.floor(py);
        float fx = (float) (px - x0);
        float fy = (float) (py - y0);
        int x1 = x0 + 1;
        int y1 = Math.min(y0 + 1, h - 1);
        x0 = Math.floorMod(x0, w);
        x1 = Math.floorMod(x1, w);
        for (int c = 0; c < 3; c++) {
            float top = eq.get(x0, y0, c) * (1 - fx) + eq.get(x1, y0, c) * fx;
            float bottom = eq.get(x0, y1, c) * (1 - fx) + eq.get(x1, y1, c) * fx;
            out[c] = top * (1 - fy) + bottom * fy;
        }
    }

    /** Returns the 2×2 box-filtered mip chain of {@code base} down to 1×1 (level 0 = base). */
    public static List<Cube> mips(Cube base) {
        List<Cube> out = new ArrayList<>();
        out.add(base);
        Cube c = base;
        while (c.size() > 1) {
            int n = c.size() / 2;
            Cube next = new Cube(n);
            for (int f = 0; f < 6; f++) {
                float[] src = c.faces()[f];
                float[] dst = next.faces()[f];
                for (int y = 0; y < n; y++) {
                    for (int x = 0; x < n; x++) {
                        for (int ch = 0; ch < 3; ch++) {
                            int s0 = ((2 * y) * c.size() + 2 * x) * 3 + ch;
                            int s1 = ((2 * y + 1) * c.size() + 2 * x) * 3 + ch;
                            dst[(y * n + x) * 3 + ch] =
                                    0.25f * (src[s0] + src[s0 + 3] + src[s1] + src[s1 + 3]);
                        }
                    }
                }
            }
            out.add(next);
            c = next;
        }
        return out;
    }

    /** Bilinear lookup of {@code dir} in one mip of a cube (clamped at face edges). */
    static void sample(Cube c, float x, float y, float z, float[] out) {
        float ax = Math.abs(x);
        float ay = Math.abs(y);
        float az = Math.abs(z);
        int f;
        float sc;
        float tc;
        float ma;
        if (ax >= ay && ax >= az) {
            ma = ax;
            if (x > 0) {
                f = 0;
                sc = -z;
            } else {
                f = 1;
                sc = z;
            }
            tc = -y;
        } else if (ay >= az) {
            ma = ay;
            sc = x;
            if (y > 0) {
                f = 2;
                tc = z;
            } else {
                f = 3;
                tc = -z;
            }
        } else {
            ma = az;
            tc = -y;
            if (z > 0) {
                f = 4;
                sc = x;
            } else {
                f = 5;
                sc = -x;
            }
        }
        int n = c.size();
        float px = ((sc / ma + 1) * 0.5f) * n - 0.5f;
        float py = ((tc / ma + 1) * 0.5f) * n - 0.5f;
        px = Math.max(0, Math.min(n - 1, px));
        py = Math.max(0, Math.min(n - 1, py));
        int x0 = (int) px;
        int y0 = (int) py;
        int x1 = Math.min(x0 + 1, n - 1);
        int y1 = Math.min(y0 + 1, n - 1);
        float fx = px - x0;
        float fy = py - y0;
        float[] face = c.faces()[f];
        for (int ch = 0; ch < 3; ch++) {
            float top = face[(y0 * n + x0) * 3 + ch] * (1 - fx) + face[(y0 * n + x1) * 3 + ch] * fx;
            float bottom =
                    face[(y1 * n + x0) * 3 + ch] * (1 - fx) + face[(y1 * n + x1) * 3 + ch] * fx;
            out[ch] = top * (1 - fy) + bottom * fy;
        }
    }

    /**
     * Prefilters the specular levels: {@code levels} cubes from {@code base.size()} down, level 0
     * being the unfiltered radiance.
     */
    public static List<Cube> prefilter(Cube base, int levels, int samples, JobSystem jobs) {
        List<Cube> chain = mips(base);
        List<Cube> out = new ArrayList<>(levels);
        out.add(base);
        float texelSolidAngle = (float) (4 * Math.PI / (6.0 * base.size() * base.size()));
        float[][] hammersley = hammersley(samples);
        for (int level = 1; level < levels; level++) {
            int size = Math.max(1, base.size() >> level);
            float roughness = (float) level / (levels - 1);
            float alpha = roughness * roughness;
            Cube dst = new Cube(size);
            jobs.parallelFor(
                    (start, end, thread) -> {
                        float[] n = new float[3];
                        float[] rgb = new float[3];
                        for (int row = start; row < end; row++) {
                            int f = row / size;
                            int y = row % size;
                            for (int x = 0; x < size; x++) {
                                direction(f, x, y, size, n);
                                filterTexel(chain, n, alpha, hammersley, texelSolidAngle, rgb);
                                int o = (y * size + x) * 3;
                                dst.faces()[f][o] = rgb[0];
                                dst.faces()[f][o + 1] = rgb[1];
                                dst.faces()[f][o + 2] = rgb[2];
                            }
                        }
                    },
                    6 * size,
                    Math.max(1, size / 8));
            out.add(dst);
        }
        return out;
    }

    private static void filterTexel(
            List<Cube> chain,
            float[] n,
            float alpha,
            float[][] xi,
            float texelSolidAngle,
            float[] out) {
        // Tangent frame around N (V = N = R).
        float ux = Math.abs(n[2]) < 0.999f ? 0 : 1;
        float uz = Math.abs(n[2]) < 0.999f ? 1 : 0;
        // t = normalize(cross(up, n)); b = cross(n, t)
        float tx = 0 * n[2] - uz * n[1];
        float ty = uz * n[0] - ux * n[2];
        float tz = ux * n[1] - 0 * n[0];
        float tl = (float) (1 / Math.sqrt(tx * tx + ty * ty + tz * tz));
        tx *= tl;
        ty *= tl;
        tz *= tl;
        float bx = n[1] * tz - n[2] * ty;
        float by = n[2] * tx - n[0] * tz;
        float bz = n[0] * ty - n[1] * tx;
        float a2 = alpha * alpha;
        float r = 0;
        float g = 0;
        float b = 0;
        float weight = 0;
        float[] rgb = new float[3];
        int maxLevel = chain.size() - 1;
        for (float[] e : xi) {
            float phi = (float) (2 * Math.PI * e[0]);
            float cosTheta = (float) Math.sqrt((1 - e[1]) / (1 + (a2 - 1) * e[1]));
            float sinTheta = (float) Math.sqrt(1 - cosTheta * cosTheta);
            float hx = sinTheta * (float) Math.cos(phi);
            float hy = sinTheta * (float) Math.sin(phi);
            float hz = cosTheta;
            float wx = tx * hx + bx * hy + n[0] * hz;
            float wy = ty * hx + by * hy + n[1] * hz;
            float wz = tz * hx + bz * hy + n[2] * hz;
            // L = reflect(-V, H) with V = N.
            float vh = n[0] * wx + n[1] * wy + n[2] * wz;
            float lx = 2 * vh * wx - n[0];
            float ly = 2 * vh * wy - n[1];
            float lz = 2 * vh * wz - n[2];
            float nl = n[0] * lx + n[1] * ly + n[2] * lz;
            if (nl <= 0) {
                continue;
            }
            // pdf(L) = D(h) (n·h) / (4 (v·h)) = D / 4 when V = N.
            float nh = cosTheta;
            float d = a2 / (float) (Math.PI * Math.pow(nh * nh * (a2 - 1) + 1, 2));
            float pdf = d / 4;
            float sampleSolidAngle = 1f / (xi.length * pdf + 1e-6f);
            float lod =
                    alpha == 0
                            ? 0
                            : Math.max(0, 0.5f * log2(sampleSolidAngle / texelSolidAngle) + 1);
            lod = Math.min(lod, maxLevel);
            int l0 = (int) lod;
            int l1 = Math.min(l0 + 1, maxLevel);
            float t = lod - l0;
            sample(chain.get(l0), lx, ly, lz, rgb);
            float sr = rgb[0] * (1 - t);
            float sg = rgb[1] * (1 - t);
            float sb = rgb[2] * (1 - t);
            if (t > 0) {
                sample(chain.get(l1), lx, ly, lz, rgb);
                sr += rgb[0] * t;
                sg += rgb[1] * t;
                sb += rgb[2] * t;
            }
            r += sr * nl;
            g += sg * nl;
            b += sb * nl;
            weight += nl;
        }
        float inv = weight > 0 ? 1 / weight : 0;
        out[0] = r * inv;
        out[1] = g * inv;
        out[2] = b * inv;
    }

    private static float log2(float v) {
        return (float) (Math.log(v) / Math.log(2));
    }

    /** The Hammersley point set: radical inverse in base 2 against {@code i / n}. */
    static float[][] hammersley(int n) {
        float[][] out = new float[n][2];
        for (int i = 0; i < n; i++) {
            out[i][0] = (float) i / n;
            out[i][1] = (Integer.reverse(i) >>> 1) / (float) (1L << 31);
        }
        return out;
    }

    /**
     * Projects the cube's radiance onto 9 SH coefficients per channel and convolves them with the
     * clamped cosine, giving irradiance {@code E(n)}: {@code out[3 * i + c]} for basis {@code i}
     * (order {@code Y00, Y1-1, Y10, Y11, Y2-2, Y2-1, Y20, Y21, Y22}).
     */
    public static float[] irradianceSh9(Cube cube) {
        double[] sh = new double[27];
        int n = cube.size();
        float[] d = new float[3];
        double[] y = new double[9];
        for (int f = 0; f < 6; f++) {
            float[] face = cube.faces()[f];
            for (int py = 0; py < n; py++) {
                for (int px = 0; px < n; px++) {
                    direction(f, px, py, n, d);
                    double s = 2.0 * (px + 0.5) / n - 1;
                    double t = 2.0 * (py + 0.5) / n - 1;
                    double solidAngle = (4.0 / (n * n)) / Math.pow(1 + s * s + t * t, 1.5);
                    basis(d[0], d[1], d[2], y);
                    int o = (py * n + px) * 3;
                    for (int i = 0; i < 9; i++) {
                        double w = y[i] * solidAngle;
                        sh[i * 3] += face[o] * w;
                        sh[i * 3 + 1] += face[o + 1] * w;
                        sh[i * 3 + 2] += face[o + 2] * w;
                    }
                }
            }
        }
        double[] band = {
            Math.PI,
            2 * Math.PI / 3,
            2 * Math.PI / 3,
            2 * Math.PI / 3,
            Math.PI / 4,
            Math.PI / 4,
            Math.PI / 4,
            Math.PI / 4,
            Math.PI / 4
        };
        float[] out = new float[27];
        for (int i = 0; i < 9; i++) {
            for (int c = 0; c < 3; c++) {
                out[i * 3 + c] = (float) (sh[i * 3 + c] * band[i]);
            }
        }
        return out;
    }

    /** Evaluates the real SH basis (bands 0..2) for a unit direction. */
    static void basis(double x, double y, double z, double[] out) {
        out[0] = 0.282095;
        out[1] = 0.488603 * y;
        out[2] = 0.488603 * z;
        out[3] = 0.488603 * x;
        out[4] = 1.092548 * x * y;
        out[5] = 1.092548 * y * z;
        out[6] = 0.315392 * (3 * z * z - 1);
        out[7] = 1.092548 * x * z;
        out[8] = 0.546274 * (x * x - y * y);
    }

    /** Evaluates irradiance from {@link #irradianceSh9} coefficients in direction {@code n}. */
    public static void evaluateSh9(float[] sh, float x, float y, float z, float[] out) {
        double[] b = new double[9];
        basis(x, y, z, b);
        for (int c = 0; c < 3; c++) {
            double e = 0;
            for (int i = 0; i < 9; i++) {
                e += sh[i * 3 + c] * b[i];
            }
            out[c] = (float) e;
        }
    }

    /**
     * The split-sum environment BRDF (Karis 2013): {@code out[2 * (y * size + x)]} is the scale and
     * {@code +1} the bias of F0, for {@code N·V = (x + 0.5) / size} and {@code roughness = (y +
     * 0.5) / size}, with the Smith-GGX geometry term ({@code k = α / 2}).
     */
    public static float[] brdfLut(int size, int samples, JobSystem jobs) {
        float[] out = new float[size * size * 2];
        float[][] xi = hammersley(samples);
        jobs.parallelFor(
                (start, end, thread) -> {
                    for (int y = start; y < end; y++) {
                        float roughness = (y + 0.5f) / size;
                        float alpha = roughness * roughness;
                        float a2 = alpha * alpha;
                        float k = alpha / 2;
                        for (int x = 0; x < size; x++) {
                            float nv = (x + 0.5f) / size;
                            float vx = (float) Math.sqrt(1 - nv * nv);
                            float vz = nv;
                            float sa = 0;
                            float sb = 0;
                            for (float[] e : xi) {
                                float phi = (float) (2 * Math.PI * e[0]);
                                float cosTheta =
                                        (float) Math.sqrt((1 - e[1]) / (1 + (a2 - 1) * e[1]));
                                float sinTheta = (float) Math.sqrt(1 - cosTheta * cosTheta);
                                // H's y component only rotates around N and drops out: V lies
                                // in the xz plane.
                                float hx = sinTheta * (float) Math.cos(phi);
                                float hz = cosTheta;
                                float vh = vx * hx + vz * hz;
                                float lz = 2 * vh * hz - vz;
                                float nl = Math.max(lz, 0);
                                float nh = Math.max(hz, 0);
                                vh = Math.max(vh, 0);
                                if (nl > 0) {
                                    float gv = nv / (nv * (1 - k) + k);
                                    float gl = nl / (nl * (1 - k) + k);
                                    float gVis = gv * gl * vh / (nh * nv);
                                    float fc = (float) Math.pow(1 - vh, 5);
                                    sa += (1 - fc) * gVis;
                                    sb += fc * gVis;
                                }
                            }
                            out[(y * size + x) * 2] = sa / xi.length;
                            out[(y * size + x) * 2 + 1] = sb / xi.length;
                        }
                    }
                },
                size,
                4);
        return out;
    }
}
