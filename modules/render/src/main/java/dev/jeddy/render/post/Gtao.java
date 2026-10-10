package dev.jeddy.render.post;

import static dev.jeddy.gl.ffi.GL46.*;

import dev.jeddy.render.gpu.GpuDevice;
import dev.jeddy.render.gpu.PipelineState;
import dev.jeddy.render.gpu.SamplerDesc;
import dev.jeddy.render.gpu.StateCache;
import dev.jeddy.render.shader.ProgramCache;
import dev.jeddy.render.shader.ShaderFeatures;

/**
 * Half-resolution ground-truth ambient occlusion (TASKS M7-05, DESIGN §9.3 pass 2): {@code
 * post/gtao.frag} computes occlusion from the depth prepass, {@code post/gtao_blur.frag} removes
 * its noise without crossing depth edges, and the lit passes read the result at unit 9 (the {@code
 * SCREEN_AO} feature), multiplying indirect light and specular occlusion.
 *
 * <p>Thread ownership: the render thread.
 */
public final class Gtao {
    /** Texture unit the lit passes read the AO from. */
    public static final int UNIT = 9;

    /**
     * Settings.
     *
     * @param radius world-space radius of the occlusion search (meters)
     * @param intensity exponent applied to the visibility (above 1 darkens)
     * @param slices directions per pixel
     * @param steps samples per direction and side
     */
    public record Config(float radius, float intensity, int slices, int steps) {
        /** One meter, two slices of four steps: enough for the iso camera's distance. */
        public static final Config DEFAULT = new Config(1.0f, 1.0f, 2, 4);
    }

    private final ProgramCache programs;
    private final int aoProgram;
    private final int blurProgram;
    private final int nearest;
    private final int linear;
    private final Config config;
    private final int emptyVao;

    /** Defines the programs. {@code emptyVao} is any vertex array without attributes. */
    public Gtao(GpuDevice device, ProgramCache programs, Config config, int emptyVao) {
        this.programs = programs;
        this.config = config;
        this.emptyVao = emptyVao;
        aoProgram =
                programs.define(
                        "gtao", "engine/fullscreen.vert", "post/gtao.frag", ShaderFeatures.NONE);
        blurProgram =
                programs.define(
                        "gtao blur",
                        "engine/fullscreen.vert",
                        "post/gtao_blur.frag",
                        ShaderFeatures.NONE);
        nearest = device.samplers().get(SamplerDesc.NEAREST_CLAMP);
        linear = device.samplers().get(SamplerDesc.LINEAR_CLAMP);
    }

    /** Draws the raw occlusion (the target is bound by the frame graph). */
    public void occlusion(StateCache state, int depthTexture) {
        int program = programs.get(aoProgram, 0);
        if (program == 0) {
            return;
        }
        state.apply(PipelineState.FULLSCREEN);
        state.useProgram(program);
        glProgramUniform4f(
                program, 0, config.radius(), config.intensity(), config.slices(), config.steps());
        state.bindTexture(0, depthTexture);
        state.bindSampler(0, nearest);
        state.bindVertexArray(emptyVao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    /** Draws the blurred occlusion. */
    public void blur(StateCache state, int rawTexture, int depthTexture) {
        int program = programs.get(blurProgram, 0);
        if (program == 0) {
            return;
        }
        state.apply(PipelineState.FULLSCREEN);
        state.useProgram(program);
        state.bindTexture(0, rawTexture);
        state.bindSampler(0, nearest);
        state.bindTexture(1, depthTexture);
        state.bindSampler(1, nearest);
        state.bindVertexArray(emptyVao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    /** Binds the blurred occlusion for the lit passes. */
    public void bind(StateCache state, int aoTexture) {
        state.bindTexture(UNIT, aoTexture);
        state.bindSampler(UNIT, linear);
    }

    /** Compiles both programs now (loading screens, tests). */
    public void compileBlocking() {
        programs.getBlocking(aoProgram, 0);
        programs.getBlocking(blurProgram, 0);
    }
}
