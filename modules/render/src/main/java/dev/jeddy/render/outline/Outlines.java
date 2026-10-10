package dev.jeddy.render.outline;

import static dev.jeddy.gl.ffi.GL46.*;

import dev.jeddy.core.job.JobSystem;
import dev.jeddy.render.RenderPacket;
import dev.jeddy.render.draw.DrawBuilder;
import dev.jeddy.render.gpu.FrameRing;
import dev.jeddy.render.gpu.GpuDevice;
import dev.jeddy.render.gpu.PipelineState;
import dev.jeddy.render.gpu.SamplerDesc;
import dev.jeddy.render.gpu.StateCache;
import dev.jeddy.render.graph.FrameGraph;
import dev.jeddy.render.graph.PassContext;
import dev.jeddy.render.material.MaterialSystem;
import dev.jeddy.render.mesh.MeshRegistry;
import dev.jeddy.render.shader.ProgramCache;
import dev.jeddy.render.shader.ShaderFeatures;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Outlines and highlights (TASKS M7-08, DESIGN §9.3 pass 8): hovered, selected and hostile
 * characters and, while the highlight key is held, interactable objects. Instances carry a style in
 * their flags ({@link RenderPacket#setHighlight}); the visible ones are drawn into a mask (style,
 * and whether the scene hides them), and a screen pass turns the mask into a display-space overlay:
 * an anti-aliased outline of the style's width and color around each shape, plus a fill inside,
 * typically only where walls hide the shape (an x-ray silhouette). The tone-map pass composites the
 * overlay, so outline colors are exact sRGB values whatever the exposure.
 *
 * <p>Thread ownership: the render thread.
 */
public final class Outlines implements AutoCloseable {
    /** Number of styles (style 0 is "none"). */
    public static final int STYLES = 16;

    /** Texture unit of the scene depth for the mask pass. */
    public static final int SCENE_DEPTH_UNIT = 11;

    private static final int MAX_WIDTH = 8;

    private final GpuDevice device;
    private final ProgramCache programs;
    private final DrawBuilder draws;
    private final DrawBuilder.BatchBinder maskBinder;
    private final int compositeProgram;
    private final int emptyVao;
    private final int nearest;
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment matrix = arena.allocate(64, 16);
    private final MemorySegment palette = arena.allocate(2L * STYLES * 16, 16);
    private final MemorySegment colors = palette.asSlice(0, STYLES * 16L);
    private final MemorySegment params = palette.asSlice(STYLES * 16L, STYLES * 16L);
    private final float[] widths = new float[STYLES];
    private int[] highlighted = new int[64];
    private int count;
    private int radius;
    private int depth = -1;
    private int mask = -1;
    private int overlay = -1;
    private RenderPacket packet;
    private FrameRing ring;

    /** Defines the programs and the default styles. */
    public Outlines(
            GpuDevice device,
            ProgramCache programs,
            MeshRegistry meshes,
            MaterialSystem materials,
            JobSystem jobs,
            int emptyVao) {
        this.device = device;
        this.programs = programs;
        this.emptyVao = emptyVao;
        this.draws = new DrawBuilder(meshes, materials.table(), jobs);
        int maskProgram =
                programs.define(
                        "outline mask",
                        "pbr/outline_mask.vert",
                        "pbr/outline_mask.frag",
                        MaterialSystem.features());
        this.maskBinder =
                materials.depthBinder(
                        maskProgram,
                        program -> glProgramUniformMatrix4fv(program, 0, 1, false, matrix),
                        PipelineState.OPAQUE);
        this.compositeProgram =
                programs.define(
                        "outline",
                        "engine/fullscreen.vert",
                        "post/outline.frag",
                        ShaderFeatures.NONE);
        this.nearest = device.samplers().get(SamplerDesc.NEAREST_CLAMP);
        setStyle(RenderPacket.HIGHLIGHT_HOVER, 1f, 1f, 1f, 0.9f, 2f, 0f, 0.25f, 0.6f);
        setStyle(RenderPacket.HIGHLIGHT_SELECTED, 0.35f, 1f, 0.45f, 1f, 2f, 0f, 0.3f, 0.7f);
        setStyle(RenderPacket.HIGHLIGHT_HOSTILE, 1f, 0.25f, 0.2f, 1f, 2f, 0f, 0.3f, 0.7f);
        setStyle(RenderPacket.HIGHLIGHT_INTERACTABLE, 1f, 0.8f, 0.3f, 1f, 1.5f, 0.12f, 0f, 0f);
    }

    /**
     * Defines highlight style {@code style} (1..15).
     *
     * @param r red, sRGB-encoded display value
     * @param a opacity
     * @param widthPx outline width in pixels (up to 8)
     * @param visibleFill fill opacity where the shape is visible (a hover glow), 0..1
     * @param hiddenFill fill opacity where the scene hides the shape (x-ray), 0..1
     * @param hiddenOutline outline opacity where the scene hides the shape, 0..1
     */
    public void setStyle(
            int style,
            float r,
            float g,
            float b,
            float a,
            float widthPx,
            float visibleFill,
            float hiddenFill,
            float hiddenOutline) {
        if (style <= 0 || style >= STYLES) {
            throw new IllegalArgumentException("style must be 1..15: " + style);
        }
        widths[style] = Math.clamp(widthPx, 0f, MAX_WIDTH);
        long o = style * 16L;
        palette.set(ValueLayout.JAVA_FLOAT, o, r);
        palette.set(ValueLayout.JAVA_FLOAT, o + 4, g);
        palette.set(ValueLayout.JAVA_FLOAT, o + 8, b);
        palette.set(ValueLayout.JAVA_FLOAT, o + 12, a);
        long q = STYLES * 16L + o;
        palette.set(ValueLayout.JAVA_FLOAT, q, widths[style]);
        palette.set(ValueLayout.JAVA_FLOAT, q + 4, visibleFill);
        palette.set(ValueLayout.JAVA_FLOAT, q + 8, hiddenFill);
        palette.set(ValueLayout.JAVA_FLOAT, q + 12, hiddenOutline);
        float max = 0;
        for (float w : widths) {
            max = Math.max(max, w);
        }
        radius = (int) Math.ceil(max);
    }

    /**
     * Declares the mask and overlay passes; call after the passes that write the scene depth.
     *
     * @return the overlay resource, for {@code PostProcess.declare}
     */
    public int declare(FrameGraph graph, int sceneDepth) {
        depth = sceneDepth;
        mask = graph.texture("outline mask", GL_RG8, 1f);
        int maskDepth = graph.texture("outline depth", GL_DEPTH_COMPONENT32F, 1f);
        overlay = graph.texture("outline overlay", GL_RGBA8, 1f);
        graph.addPass("outline mask", this::maskPass)
                .readsTexture(sceneDepth)
                .color(mask, 0, 0, 0, 0)
                .depth(maskDepth, 0f);
        graph.addPass("outline", this::compositePass).readsTexture(mask).color(overlay, 0, 0, 0, 0);
        return overlay;
    }

    /**
     * Picks the highlighted instances among the visible ones and builds their draws with the main
     * view's level-of-detail scale. Allocates nothing unless more instances than ever before are
     * highlighted.
     */
    public void collect(
            RenderPacket p, int[] visible, int visibleCount, FrameRing frameRing, float lodPixels) {
        int n = 0;
        int[] flags = p.flags;
        for (int v = 0; v < visibleCount; v++) {
            int i = visible[v];
            if ((flags[i] & RenderPacket.FLAG_HIGHLIGHT) != 0) {
                if (n == highlighted.length) {
                    highlighted = java.util.Arrays.copyOf(highlighted, n * 2);
                }
                highlighted[n++] = i;
            }
        }
        count = n;
        packet = p;
        ring = frameRing;
        if (n > 0) {
            draws.setLod(lodPixels, p.orthographic, 1f);
            draws.build(p, highlighted, n, frameRing);
        }
    }

    /** Returns the number of highlighted instances drawn this frame. */
    public int count() {
        return count;
    }

    private void maskPass(PassContext ctx) {
        if (count == 0) {
            return;
        }
        MemorySegment.copy(packet.viewProjection, 0, matrix, ValueLayout.JAVA_FLOAT, 0, 16);
        StateCache state = ctx.state();
        state.bindTexture(SCENE_DEPTH_UNIT, ctx.texture(depth));
        state.bindSampler(SCENE_DEPTH_UNIT, nearest);
        draws.submit(state, ring, maskBinder);
    }

    private void compositePass(PassContext ctx) {
        int program = programs.get(compositeProgram, 0);
        if (count == 0 || program == 0) {
            return;
        }
        StateCache state = ctx.state();
        state.apply(PipelineState.FULLSCREEN);
        state.useProgram(program);
        glProgramUniform4fv(program, 0, STYLES, colors);
        glProgramUniform4fv(program, 16, STYLES, params);
        glProgramUniform1i(program, 32, radius);
        state.bindTexture(0, ctx.texture(mask));
        state.bindSampler(0, nearest);
        state.bindVertexArray(emptyVao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    /** Compiles the overlay program now (tests, loading screens). */
    public void compileBlocking() {
        programs.getBlocking(compositeProgram, 0);
    }

    @Override
    public void close() {
        arena.close();
    }
}
