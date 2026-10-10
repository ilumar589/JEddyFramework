package dev.jeddy.render.gpu;

/**
 * A sampler description packed into an {@code int} (TASKS M5-03), the key of {@link GpuSamplers}'
 * cache. Build with the {@code with*} methods from a preset:
 *
 * <pre>{@code
 * int desc = SamplerDesc.withAnisotropy(SamplerDesc.TRILINEAR_REPEAT, 8);
 * }</pre>
 *
 * <p>Thread ownership: stateless, any thread.
 */
public final class SamplerDesc {
    private SamplerDesc() {}

    /** Filter: nearest. */
    public static final int NEAREST = 0;

    /** Filter: linear. */
    public static final int LINEAR = 1;

    /** Mip mode: no mipmaps. */
    public static final int MIP_NONE = 0;

    /** Mip mode: nearest level. */
    public static final int MIP_NEAREST = 1;

    /** Mip mode: blend levels. */
    public static final int MIP_LINEAR = 2;

    /** Wrap: repeat. */
    public static final int REPEAT = 0;

    /** Wrap: clamp to edge. */
    public static final int CLAMP = 1;

    /** Wrap: mirrored repeat. */
    public static final int MIRROR = 2;

    /** Wrap: clamp to border (black). */
    public static final int BORDER = 3;

    private static final int MAG = 0; // 1 bit
    private static final int MIN = 1; // 1 bit
    private static final int MIP = 2; // 2 bits
    private static final int WRAP_S = 4; // 2 bits
    private static final int WRAP_T = 6;
    private static final int WRAP_R = 8;
    private static final int ANISO = 10; // 5 bits
    private static final int COMPARE = 15; // 1 bit: depth comparison (shadow maps), GEQUAL

    /** Linear filtering with linear mip blending, repeating. */
    public static final int TRILINEAR_REPEAT = of(LINEAR, LINEAR, MIP_LINEAR, REPEAT);

    /** Linear filtering, no mipmaps, clamped (render targets, LUTs). */
    public static final int LINEAR_CLAMP = of(LINEAR, LINEAR, MIP_NONE, CLAMP);

    /** Nearest filtering, clamped (G-buffer reads). */
    public static final int NEAREST_CLAMP = of(NEAREST, NEAREST, MIP_NONE, CLAMP);

    /** Shadow-map comparison (reversed-Z: {@code GL_GEQUAL}), linear, clamped to border. */
    public static final int SHADOW = of(LINEAR, LINEAR, MIP_NONE, BORDER) | (1 << COMPARE);

    /** Packs filters, mip mode and one wrap mode for all axes. */
    public static int of(int mag, int min, int mip, int wrap) {
        return (mag << MAG)
                | (min << MIN)
                | (mip << MIP)
                | (wrap << WRAP_S)
                | (wrap << WRAP_T)
                | (wrap << WRAP_R);
    }

    /** Returns {@code desc} with anisotropic filtering (1 to 16; 0 or 1 off). */
    public static int withAnisotropy(int desc, int anisotropy) {
        int a = Math.max(0, Math.min(16, anisotropy));
        return (desc & ~(31 << ANISO)) | (a << ANISO);
    }

    static int mag(int d) {
        return (d >>> MAG) & 1;
    }

    static int min(int d) {
        return (d >>> MIN) & 1;
    }

    static int mip(int d) {
        return (d >>> MIP) & 3;
    }

    static int wrap(int d, int axis) {
        return (d >>> (WRAP_S + 2 * axis)) & 3;
    }

    static int anisotropy(int d) {
        return (d >>> ANISO) & 31;
    }

    static boolean compare(int d) {
        return ((d >>> COMPARE) & 1) != 0;
    }
}
