package dev.jeddy.render.gpu;

/**
 * Fixed-function pipeline state packed into a {@code long} (TASKS M5-03): culling, depth test and
 * write, depth function, blending, color mask, polygon offset, wireframe and scissor. {@link
 * StateCache#apply} changes only the bits that differ from the current state.
 *
 * <p>Depth defaults to reversed-Z ({@code GL_GEQUAL} against a depth cleared to 0, DESIGN §8.2).
 *
 * <p>Thread ownership: stateless, any thread.
 */
public final class PipelineState {
    private PipelineState() {}

    /** Cull mode: none. */
    public static final int CULL_NONE = 0;

    /** Cull mode: back faces. */
    public static final int CULL_BACK = 1;

    /** Cull mode: front faces. */
    public static final int CULL_FRONT = 2;

    /** Blend: off. */
    public static final int BLEND_OPAQUE = 0;

    /** Blend: {@code src.a, 1 - src.a}. */
    public static final int BLEND_ALPHA = 1;

    /** Blend: {@code 1, 1 - src.a} (premultiplied alpha). */
    public static final int BLEND_PREMULTIPLIED = 2;

    /** Blend: {@code 1, 1} (additive light, VFX). */
    public static final int BLEND_ADDITIVE = 3;

    /** Blend: {@code dst, 0} (multiply, decals). */
    public static final int BLEND_MULTIPLY = 4;

    /** Depth function index: never .. always, in GL order ({@code GL_NEVER + i}). */
    public static final int DEPTH_GEQUAL = 6;

    /** Depth function index for {@code GL_ALWAYS}. */
    public static final int DEPTH_ALWAYS = 7;

    /** Depth function index for {@code GL_EQUAL}. */
    public static final int DEPTH_EQUAL = 2;

    static final int CULL_SHIFT = 0; // 2 bits
    static final long DEPTH_TEST = 1L << 2;
    static final long DEPTH_WRITE = 1L << 3;
    static final int DEPTH_FUNC_SHIFT = 4; // 3 bits
    static final int BLEND_SHIFT = 7; // 3 bits
    static final int COLOR_MASK_SHIFT = 10; // 4 bits (r g b a)
    static final long POLYGON_OFFSET = 1L << 14;
    static final long WIREFRAME = 1L << 15;
    static final long SCISSOR = 1L << 16;

    /** Opaque geometry: back-face culling, reversed-Z depth test and write, all colors. */
    public static final long OPAQUE =
            ((long) CULL_BACK << CULL_SHIFT)
                    | DEPTH_TEST
                    | DEPTH_WRITE
                    | ((long) DEPTH_GEQUAL << DEPTH_FUNC_SHIFT)
                    | (0xFL << COLOR_MASK_SHIFT);

    /** Depth prepass and shadow casters: no color writes. */
    public static final long DEPTH_ONLY = OPAQUE & ~(0xFL << COLOR_MASK_SHIFT);

    /** Transparent geometry: alpha blending, depth test without writes, no culling. */
    public static final long TRANSPARENT =
            withBlend((OPAQUE & ~DEPTH_WRITE) & ~(3L << CULL_SHIFT), BLEND_ALPHA);

    /** Full-screen passes: no depth, no culling, all colors. */
    public static final long FULLSCREEN = 0xFL << COLOR_MASK_SHIFT;

    /** The GL defaults (what {@link StateCache} assumes after {@link StateCache#invalidate}). */
    public static final long GL_DEFAULT =
            ((long) 1 << DEPTH_FUNC_SHIFT) /* GL_LESS */ | DEPTH_WRITE | (0xFL << COLOR_MASK_SHIFT);

    /** Returns {@code state} with cull mode {@code mode}. */
    public static long withCull(long state, int mode) {
        return (state & ~(3L << CULL_SHIFT)) | ((long) mode << CULL_SHIFT);
    }

    /** Returns {@code state} with blend mode {@code mode}. */
    public static long withBlend(long state, int mode) {
        return (state & ~(7L << BLEND_SHIFT)) | ((long) mode << BLEND_SHIFT);
    }

    /** Returns {@code state} with depth test, write and function index ({@code GL_NEVER + i}). */
    public static long withDepth(long state, boolean test, boolean write, int function) {
        long s = state & ~(DEPTH_TEST | DEPTH_WRITE | (7L << DEPTH_FUNC_SHIFT));
        return s
                | (test ? DEPTH_TEST : 0)
                | (write ? DEPTH_WRITE : 0)
                | ((long) function << DEPTH_FUNC_SHIFT);
    }

    /** Returns {@code state} with depth writes on or off, test and function unchanged. */
    public static long withDepthWrite(long state, boolean write) {
        return write ? state | DEPTH_WRITE : state & ~DEPTH_WRITE;
    }

    /** Returns {@code state} with the color write mask (bit 0 red .. bit 3 alpha). */
    public static long withColorMask(long state, int rgba) {
        return (state & ~(0xFL << COLOR_MASK_SHIFT)) | ((long) (rgba & 0xF) << COLOR_MASK_SHIFT);
    }

    /** Returns {@code state} with polygon offset on or off (shadow casters). */
    public static long withPolygonOffset(long state, boolean on) {
        return on ? state | POLYGON_OFFSET : state & ~POLYGON_OFFSET;
    }

    /** Returns {@code state} with wireframe on or off (debug). */
    public static long withWireframe(long state, boolean on) {
        return on ? state | WIREFRAME : state & ~WIREFRAME;
    }

    /** Returns {@code state} with the scissor test on or off. */
    public static long withScissor(long state, boolean on) {
        return on ? state | SCISSOR : state & ~SCISSOR;
    }
}
