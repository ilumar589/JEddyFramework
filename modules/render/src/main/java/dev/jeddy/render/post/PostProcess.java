package dev.jeddy.render.post;

import static dev.jeddy.gl.ffi.GL46.*;

import dev.jeddy.render.gpu.GpuBuffers;
import dev.jeddy.render.gpu.GpuDevice;
import dev.jeddy.render.gpu.GpuTextures;
import dev.jeddy.render.gpu.PipelineState;
import dev.jeddy.render.gpu.SamplerDesc;
import dev.jeddy.render.gpu.StateCache;
import dev.jeddy.render.graph.FrameGraph;
import dev.jeddy.render.graph.PassContext;
import dev.jeddy.render.shader.ProgramCache;
import dev.jeddy.render.shader.ShaderFeatures;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * The HDR-to-display chain (TASKS M7-06, DESIGN §9.3 pass 9), declared as frame graph passes after
 * the scene: a bloom pyramid (13-tap downsamples with a Karis average on the first, tent
 * upsamples), a luminance histogram and an adapted exposure on the GPU (no read-back), a composite
 * with AgX or ACES tone mapping, a display-space color-grading LUT and a dither, and FXAA into the
 * window.
 *
 * <p>The exposure lives in a small storage buffer at binding {@link #EXPOSURE_BINDING}: {@code
 * jExposure} is 1 without auto exposure, otherwise the multiplier that maps the trimmed average
 * luminance to {@link AutoExposure#key}, approached exponentially. {@link #prepare}'s compensation
 * multiplies it.
 *
 * <p>Thread ownership: the render thread.
 */
public final class PostProcess implements AutoCloseable {
    /** Storage-buffer binding of the exposure state ({@code post/exposure.glsl}). */
    public static final int EXPOSURE_BINDING = 5;

    /** Byte offset of {@code jExposure} in the exposure buffer. */
    public static final int EXPOSURE_OFFSET = 1024;

    /** Byte offset of {@code jAverageLuminance} in the exposure buffer. */
    public static final int AVERAGE_OFFSET = 1028;

    private static final int EXPOSURE_BYTES = 1040;
    private static final int MAX_BLOOM_LEVELS = 8;
    private static final int FLAG_AGX = 1;
    private static final int FLAG_LUT = 2;
    private static final int FLAG_DITHER = 4;
    private static final int FLAG_BLOOM = 8;
    private static final int FLAG_OVERLAY = 16;

    /** Tone curves. */
    public enum Tonemapper {
        /** Sobotka's AgX: smooth hue-preserving highlight roll-off (default). */
        AGX,
        /** The Narkowicz fit of the ACES reference rendering transform: punchier, shifts hues. */
        ACES
    }

    /**
     * Automatic exposure.
     *
     * @param minLog2 darkest log2 luminance the histogram covers (and the most it brightens)
     * @param maxLog2 brightest log2 luminance the histogram covers (and the most it darkens)
     * @param lowFraction darkest fraction of pixels ignored by the average
     * @param highFraction pixels above this fraction (the brightest) are ignored
     * @param key display luminance the average maps to (0.18: middle grey)
     * @param brightenRate adaptation rate when the scene gets brighter (1 / seconds)
     * @param darkenRate adaptation rate when the scene gets darker (1 / seconds)
     */
    public record AutoExposure(
            float minLog2,
            float maxLog2,
            float lowFraction,
            float highFraction,
            float key,
            float brightenRate,
            float darkenRate) {
        /**
         * Luminance 2^-8..2^10, pixels from the median to the 95th percentile, middle grey, 3 / s
         * and 1 / s.
         */
        public static final AutoExposure DEFAULT =
                new AutoExposure(-8f, 10f, 0.5f, 0.95f, 0.18f, 3f, 1f);

        /** Checks the ranges. */
        public AutoExposure {
            if (!(maxLog2 > minLog2)
                    || !(lowFraction >= 0 && lowFraction < highFraction && highFraction <= 1)
                    || !(key > 0)) {
                throw new IllegalArgumentException("invalid auto exposure settings");
            }
        }
    }

    /**
     * Settings. The bloom pyramid depth and FXAA shape the frame graph and are fixed; the rest can
     * change per frame through the setters.
     *
     * @param tonemapper the tone curve
     * @param bloomIntensity mix of the bloom into the image (0..1; about 0.04 is subtle)
     * @param bloomLevels pyramid depth (0 for no bloom, else 2..8)
     * @param autoExposure automatic exposure, or {@code null} for a fixed multiplier of 1
     * @param fxaa anti-alias the display image
     */
    public record Config(
            Tonemapper tonemapper,
            float bloomIntensity,
            int bloomLevels,
            AutoExposure autoExposure,
            boolean fxaa) {
        /** AgX, 4% bloom over 6 levels, default auto exposure, FXAA. */
        public static final Config DEFAULT =
                new Config(Tonemapper.AGX, 0.04f, 6, AutoExposure.DEFAULT, true);

        /** Checks the ranges. */
        public Config {
            if (bloomLevels != 0 && (bloomLevels < 2 || bloomLevels > MAX_BLOOM_LEVELS)) {
                throw new IllegalArgumentException("bloom levels must be 0 or 2..8");
            }
        }

        /** Returns a copy with another tone curve. */
        public Config withTonemapper(Tonemapper value) {
            return new Config(value, bloomIntensity, bloomLevels, autoExposure, fxaa);
        }

        /** Returns a copy with another bloom mix and depth. */
        public Config withBloom(float intensity, int levels) {
            return new Config(tonemapper, intensity, levels, autoExposure, fxaa);
        }

        /** Returns a copy with other auto exposure settings ({@code null}: fixed). */
        public Config withAutoExposure(AutoExposure value) {
            return new Config(tonemapper, bloomIntensity, bloomLevels, value, fxaa);
        }

        /** Returns a copy with FXAA on or off. */
        public Config withFxaa(boolean value) {
            return new Config(tonemapper, bloomIntensity, bloomLevels, autoExposure, value);
        }
    }

    private final GpuDevice device;
    private final ProgramCache programs;
    private final int emptyVao;
    private final int bloomLevels;
    private final boolean fxaaPass;
    private final int downProgram;
    private final int upProgram;
    private final int histogramProgram;
    private final int averageProgram;
    private final int tonemapProgram;
    private final int fxaaProgram;
    private final int linearClamp;
    private final int exposureBuffer;
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment one = arena.allocate(4);
    private final int[] down = new int[MAX_BLOOM_LEVELS];
    private final int[] up = new int[MAX_BLOOM_LEVELS];
    private int hdr = -1;
    private int ldr = -1;
    private int overlay = -1;
    private Tonemapper tonemapper;
    private float bloomIntensity;
    private AutoExposure autoExposure;
    private boolean fxaa;
    private int lut;
    private int lutSize;
    private float compensation = 1f;
    private float seconds;

    /** Defines the programs and the exposure buffer. {@code emptyVao}: no attributes. */
    public PostProcess(GpuDevice device, ProgramCache programs, Config config, int emptyVao) {
        this.device = device;
        this.programs = programs;
        this.emptyVao = emptyVao;
        this.bloomLevels = config.bloomLevels();
        this.fxaaPass = config.fxaa();
        this.tonemapper = config.tonemapper();
        this.bloomIntensity = config.bloomIntensity();
        this.fxaa = config.fxaa();
        String vs = "engine/fullscreen.vert";
        downProgram =
                programs.define("bloom down", vs, "post/bloom_down.frag", ShaderFeatures.NONE);
        upProgram = programs.define("bloom up", vs, "post/bloom_up.frag", ShaderFeatures.NONE);
        histogramProgram =
                programs.defineCompute(
                        "exposure histogram", "post/exposure_histogram.comp", ShaderFeatures.NONE);
        averageProgram =
                programs.defineCompute(
                        "exposure average", "post/exposure_average.comp", ShaderFeatures.NONE);
        tonemapProgram = programs.define("tonemap", vs, "post/tonemap.frag", ShaderFeatures.NONE);
        fxaaProgram = programs.define("fxaa", vs, "post/fxaa.frag", ShaderFeatures.NONE);
        linearClamp = device.samplers().get(SamplerDesc.LINEAR_CLAMP);
        exposureBuffer = device.buffers().create(EXPOSURE_BYTES, GpuBuffers.DYNAMIC, "exposure");
        setAutoExposure(config.autoExposure());
    }

    /**
     * Declares the passes after everything that draws into {@code hdrResource}: they read it and
     * write the window's framebuffer. {@code overlayResource} (or -1) is a premultiplied,
     * sRGB-encoded RGBA image composited over the tone-mapped scene before anti-aliasing
     * (outlines).
     */
    public void declare(FrameGraph graph, int hdrResource, int overlayResource) {
        if (hdr >= 0) {
            throw new IllegalStateException("already declared");
        }
        hdr = hdrResource;
        overlay = overlayResource;
        int exposure = graph.importedBuffer("exposure");
        graph.setBuffer(exposure, device.buffers().glName(exposureBuffer));
        float scale = 1f;
        for (int i = 0; i < bloomLevels; i++) {
            scale *= 0.5f;
            down[i] = graph.texture("bloom down " + i, GL_R11F_G11F_B10F, scale);
        }
        scale = 0.5f;
        for (int i = 0; i < bloomLevels - 1; i++) {
            up[i] = graph.texture("bloom up " + i, GL_R11F_G11F_B10F, scale);
            scale *= 0.5f;
        }
        for (int i = 0; i < bloomLevels; i++) {
            int level = i;
            int source = i == 0 ? hdr : down[i - 1];
            graph.addPass("bloom down " + i, ctx -> bloomDown(ctx, level))
                    .readsTexture(source)
                    .color(down[i]);
        }
        for (int i = bloomLevels - 2; i >= 0; i--) {
            int level = i;
            int low = i == bloomLevels - 2 ? down[i + 1] : up[i + 1];
            graph.addPass("bloom up " + i, ctx -> bloomUp(ctx, level, low))
                    .readsTexture(low)
                    .readsTexture(down[i])
                    .color(up[i]);
        }
        graph.addComputePass("exposure histogram", this::histogram)
                .readsTexture(hdr)
                .writes(exposure);
        graph.addComputePass("exposure average", this::average)
                .readsStorage(exposure)
                .writes(exposure);
        FrameGraph.Pass composite =
                graph.addPass("tonemap", this::tonemap).readsTexture(hdr).readsStorage(exposure);
        if (bloomLevels > 0) {
            composite.readsTexture(up[0]);
        }
        if (overlay >= 0) {
            composite.readsTexture(overlay);
        }
        if (fxaaPass) {
            ldr = graph.texture("display", GL_RGBA8, 1f);
            composite.color(ldr);
            graph.addPass("fxaa", this::fxaaPass).readsTexture(ldr).color(FrameGraph.BACKBUFFER);
        } else {
            composite.color(FrameGraph.BACKBUFFER);
        }
    }

    /**
     * Sets this frame's exposure compensation (a multiplier on top of the automatic exposure) and
     * the seconds since the previous frame (for adaptation). Call before the graph executes.
     */
    public void prepare(float exposureCompensation, float elapsedSeconds) {
        compensation = exposureCompensation;
        seconds = elapsedSeconds;
    }

    /** Selects the tone curve. */
    public void setTonemapper(Tonemapper value) {
        tonemapper = value;
    }

    /** Returns the tone curve. */
    public Tonemapper tonemapper() {
        return tonemapper;
    }

    /** Sets the bloom mix (ignored without a bloom pyramid). */
    public void setBloomIntensity(float value) {
        bloomIntensity = value;
    }

    /**
     * Turns automatic exposure on with {@code value} (snapping to the scene on the next frame), or
     * off with {@code null} (exposure 1).
     */
    public void setAutoExposure(AutoExposure value) {
        autoExposure = value;
        one.set(ValueLayout.JAVA_FLOAT, 0, value == null ? 1f : 0f);
        device.buffers().update(exposureBuffer, EXPOSURE_OFFSET, one.address(), 4);
    }

    /** Snaps the automatic exposure to the next frame instead of adapting (camera cuts). */
    public void resetExposure() {
        setAutoExposure(autoExposure);
    }

    /** Returns the automatic exposure settings, or {@code null}. */
    public AutoExposure autoExposure() {
        return autoExposure;
    }

    /** Turns FXAA on or off (when the graph has the pass; otherwise a no-op). */
    public void setFxaa(boolean value) {
        fxaa = value;
    }

    /**
     * Grades with a 3D LUT: {@code glTexture} is a {@code size}³ 3D texture indexed by the
     * sRGB-encoded display color, holding the graded encoded color ({@link #createLut}, or a cooked
     * {@code .cube}); 0 turns grading off. The caller owns the texture.
     */
    public void setGradingLut(int glTexture, int size) {
        lut = glTexture;
        lutSize = size;
    }

    /** Returns the GL name of the exposure buffer (debug views, tests). */
    public int exposureBufferGlName() {
        return device.buffers().glName(exposureBuffer);
    }

    /**
     * Creates a grading LUT texture ({@link GpuTextures} handle, RGBA16F) from {@code size}³ RGB
     * triples, red varying fastest. Not for per-frame use.
     */
    public static int createLut(GpuDevice device, int size, float[] rgb) {
        if (rgb.length != size * size * size * 3) {
            throw new IllegalArgumentException("a " + size + "³ LUT needs size³ × 3 floats");
        }
        int handle =
                device.textures()
                        .create(
                                GpuTextures.KIND_3D,
                                GL_RGBA16F,
                                size,
                                size,
                                size,
                                1,
                                "grading lut");
        try (Arena a = Arena.ofConfined()) {
            int texels = size * size * size;
            MemorySegment data = a.allocate((long) texels * 8, 8);
            for (int i = 0; i < texels; i++) {
                for (int c = 0; c < 3; c++) {
                    data.set(
                            ValueLayout.JAVA_SHORT,
                            i * 8L + c * 2L,
                            Float.floatToFloat16(rgb[i * 3 + c]));
                }
                data.set(ValueLayout.JAVA_SHORT, i * 8L + 6, Float.floatToFloat16(1f));
            }
            device.textures().uploadLevel(handle, 0, data.address(), (int) data.byteSize());
        }
        return handle;
    }

    /** Compiles every program now (loading screens, tests). */
    public void compileBlocking() {
        programs.getBlocking(downProgram, 0);
        programs.getBlocking(upProgram, 0);
        programs.getBlocking(histogramProgram, 0);
        programs.getBlocking(averageProgram, 0);
        programs.getBlocking(tonemapProgram, 0);
        programs.getBlocking(fxaaProgram, 0);
    }

    // Passes ---------------------------------------------------------------------------------

    private boolean fullscreen(StateCache state, int program) {
        if (program == 0) {
            return false;
        }
        state.apply(PipelineState.FULLSCREEN);
        state.useProgram(program);
        state.bindVertexArray(emptyVao);
        return true;
    }

    private void bloomDown(PassContext ctx, int level) {
        StateCache state = ctx.state();
        int program = programs.get(downProgram, 0);
        if (!fullscreen(state, program)) {
            return;
        }
        glProgramUniform1i(program, 0, level == 0 ? 1 : 0);
        state.bindTexture(0, ctx.texture(level == 0 ? hdr : down[level - 1]));
        state.bindSampler(0, linearClamp);
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    private void bloomUp(PassContext ctx, int level, int low) {
        StateCache state = ctx.state();
        if (!fullscreen(state, programs.get(upProgram, 0))) {
            return;
        }
        state.bindTexture(0, ctx.texture(low));
        state.bindSampler(0, linearClamp);
        state.bindTexture(1, ctx.texture(down[level]));
        state.bindSampler(1, linearClamp);
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    private void histogram(PassContext ctx) {
        AutoExposure ae = autoExposure;
        int program = programs.get(histogramProgram, 0);
        if (ae == null || program == 0) {
            return;
        }
        StateCache state = ctx.state();
        state.useProgram(program);
        glProgramUniform2f(program, 0, ae.minLog2(), 1f / (ae.maxLog2() - ae.minLog2()));
        state.bindTexture(0, ctx.texture(hdr));
        state.bindSampler(0, linearClamp);
        state.bindStorage(
                EXPOSURE_BINDING, device.buffers().glName(exposureBuffer), 0, EXPOSURE_BYTES);
        int w = (ctx.width() + 1) / 2;
        int h = (ctx.height() + 1) / 2;
        glDispatchCompute((w + 15) / 16, (h + 15) / 16, 1);
    }

    private void average(PassContext ctx) {
        AutoExposure ae = autoExposure;
        int program = programs.get(averageProgram, 0);
        if (ae == null || program == 0) {
            return;
        }
        StateCache state = ctx.state();
        state.useProgram(program);
        glProgramUniform2f(program, 0, ae.minLog2(), 1f / (ae.maxLog2() - ae.minLog2()));
        glProgramUniform4f(program, 1, ae.lowFraction(), ae.highFraction(), seconds, ae.key());
        glProgramUniform2f(program, 2, ae.brightenRate(), ae.darkenRate());
        state.bindStorage(
                EXPOSURE_BINDING, device.buffers().glName(exposureBuffer), 0, EXPOSURE_BYTES);
        glDispatchCompute(1, 1, 1);
    }

    private void tonemap(PassContext ctx) {
        StateCache state = ctx.state();
        int program = programs.get(tonemapProgram, 0);
        if (!fullscreen(state, program)) {
            return;
        }
        int flags = tonemapper == Tonemapper.AGX ? FLAG_AGX : 0;
        if (lut != 0) {
            flags |= FLAG_LUT;
            state.bindTexture(2, lut);
            state.bindSampler(2, linearClamp);
        }
        if (!fxaaPass) {
            flags |= FLAG_DITHER;
        }
        if (overlay >= 0) {
            flags |= FLAG_OVERLAY;
            state.bindTexture(3, ctx.texture(overlay));
            state.bindSampler(3, linearClamp);
        }
        if (bloomLevels > 0 && bloomIntensity > 0) {
            flags |= FLAG_BLOOM;
            state.bindTexture(1, ctx.texture(up[0]));
            state.bindSampler(1, linearClamp);
        }
        glProgramUniform4f(
                program,
                0,
                compensation,
                bloomIntensity,
                bloomLevels > 0 ? 1f / bloomLevels : 0f,
                lutSize);
        glProgramUniform1ui(program, 1, flags);
        state.bindTexture(0, ctx.texture(hdr));
        state.bindSampler(0, linearClamp);
        state.bindStorage(
                EXPOSURE_BINDING, device.buffers().glName(exposureBuffer), 0, EXPOSURE_BYTES);
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    private void fxaaPass(PassContext ctx) {
        StateCache state = ctx.state();
        int program = programs.get(fxaaProgram, 0);
        if (!fullscreen(state, program)) {
            return;
        }
        glProgramUniform4f(program, 0, 0.75f, 0.166f, 0.0833f, 1f);
        glProgramUniform1i(program, 1, fxaa ? 1 : 0);
        state.bindTexture(0, ctx.texture(ldr));
        state.bindSampler(0, linearClamp);
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    @Override
    public void close() {
        device.buffers().destroy(exposureBuffer);
        arena.close();
    }
}
