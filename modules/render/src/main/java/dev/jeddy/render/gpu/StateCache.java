package dev.jeddy.render.gpu;

import static dev.jeddy.gl.ffi.GL46.*;

import java.util.Arrays;

/**
 * Shadows the GL binding and fixed-function state so redundant calls are skipped (TASKS M5-03,
 * DESIGN §9.2): program, vertex array, framebuffer, texture units, samplers, indexed buffer
 * bindings and {@link PipelineState}. Every call goes through here; code that calls GL directly
 * must {@link #invalidate} afterwards.
 *
 * <p>{@link #issuedCalls()} counts the GL calls actually made, for tests and the profiler overlay.
 *
 * <p>Thread ownership: the GL context thread (main thread).
 */
public final class StateCache {
    private static final int UNITS = 32;
    private static final int BINDINGS = 32;

    private long state;
    private int program;
    private int vertexArray;
    private int drawFramebuffer;
    private final int[] textures = new int[UNITS];
    private final int[] samplers = new int[UNITS];
    private final int[] ssbo = new int[BINDINGS];
    private final long[] ssboOffset = new long[BINDINGS];
    private final long[] ssboSize = new long[BINDINGS];
    private final int[] ubo = new int[BINDINGS];
    private final long[] uboOffset = new long[BINDINGS];
    private final long[] uboSize = new long[BINDINGS];
    private int drawIndirect;
    private int viewportX = -1, viewportY, viewportW, viewportH;
    private long issued;

    /** Creates a cache that assumes GL defaults. */
    public StateCache() {
        invalidate();
    }

    /**
     * Forgets the shadowed state: the next calls re-issue everything. Then sets the pipeline state
     * to {@link PipelineState#GL_DEFAULT} as GL itself starts.
     */
    public void invalidate() {
        state = -1; // forces a full apply
        apply(PipelineState.GL_DEFAULT);
        program = -1;
        vertexArray = -1;
        drawFramebuffer = -1;
        Arrays.fill(textures, -1);
        Arrays.fill(samplers, -1);
        Arrays.fill(ssbo, -1);
        Arrays.fill(ubo, -1);
        drawIndirect = -1;
        viewportX = -1;
    }

    /** Returns the number of GL calls issued through this cache. */
    public long issuedCalls() {
        return issued;
    }

    /** Applies a {@link PipelineState}, issuing only the calls for bits that changed. */
    public void apply(long newState) {
        long old = state;
        long diff = old ^ newState;
        if (diff == 0) {
            return;
        }
        boolean full = old == -1;
        if (full || (diff & (3L << PipelineState.CULL_SHIFT)) != 0) {
            int cull = (int) ((newState >>> PipelineState.CULL_SHIFT) & 3);
            if (cull == PipelineState.CULL_NONE) {
                glDisable(GL_CULL_FACE);
                issued++;
            } else {
                glEnable(GL_CULL_FACE);
                glCullFace(cull == PipelineState.CULL_BACK ? GL_BACK : GL_FRONT);
                issued += 2;
            }
        }
        if (full || (diff & PipelineState.DEPTH_TEST) != 0) {
            toggle(GL_DEPTH_TEST, (newState & PipelineState.DEPTH_TEST) != 0);
        }
        if (full || (diff & PipelineState.DEPTH_WRITE) != 0) {
            glDepthMask((newState & PipelineState.DEPTH_WRITE) != 0);
            issued++;
        }
        if (full || (diff & (7L << PipelineState.DEPTH_FUNC_SHIFT)) != 0) {
            glDepthFunc(GL_NEVER + (int) ((newState >>> PipelineState.DEPTH_FUNC_SHIFT) & 7));
            issued++;
        }
        if (full || (diff & (7L << PipelineState.BLEND_SHIFT)) != 0) {
            int blend = (int) ((newState >>> PipelineState.BLEND_SHIFT) & 7);
            if (blend == PipelineState.BLEND_OPAQUE) {
                glDisable(GL_BLEND);
                issued++;
            } else {
                glEnable(GL_BLEND);
                switch (blend) {
                    case PipelineState.BLEND_ALPHA ->
                            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
                    case PipelineState.BLEND_PREMULTIPLIED ->
                            glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
                    case PipelineState.BLEND_ADDITIVE -> glBlendFunc(GL_ONE, GL_ONE);
                    default -> glBlendFunc(GL_DST_COLOR, GL_ZERO);
                }
                issued += 2;
            }
        }
        if (full || (diff & (0xFL << PipelineState.COLOR_MASK_SHIFT)) != 0) {
            int m = (int) ((newState >>> PipelineState.COLOR_MASK_SHIFT) & 0xF);
            glColorMask((m & 1) != 0, (m & 2) != 0, (m & 4) != 0, (m & 8) != 0);
            issued++;
        }
        if (full || (diff & PipelineState.POLYGON_OFFSET) != 0) {
            toggle(GL_POLYGON_OFFSET_FILL, (newState & PipelineState.POLYGON_OFFSET) != 0);
        }
        if (full || (diff & PipelineState.WIREFRAME) != 0) {
            glPolygonMode(
                    GL_FRONT_AND_BACK,
                    (newState & PipelineState.WIREFRAME) != 0 ? GL_LINE : GL_FILL);
            issued++;
        }
        if (full || (diff & PipelineState.SCISSOR) != 0) {
            toggle(GL_SCISSOR_TEST, (newState & PipelineState.SCISSOR) != 0);
        }
        state = newState;
    }

    private void toggle(int cap, boolean on) {
        if (on) {
            glEnable(cap);
        } else {
            glDisable(cap);
        }
        issued++;
    }

    /** Returns the current pipeline state. */
    public long state() {
        return state;
    }

    /** Binds a program. */
    public void useProgram(int name) {
        if (program != name) {
            glUseProgram(name);
            program = name;
            issued++;
        }
    }

    /** Binds a vertex array. */
    public void bindVertexArray(int name) {
        if (vertexArray != name) {
            glBindVertexArray(name);
            vertexArray = name;
            issued++;
        }
    }

    /** Binds the draw framebuffer (0 for the window). */
    public void bindFramebuffer(int name) {
        if (drawFramebuffer != name) {
            glBindFramebuffer(GL_FRAMEBUFFER, name);
            drawFramebuffer = name;
            issued++;
        }
    }

    /** Binds a texture to a unit. */
    public void bindTexture(int unit, int name) {
        if (textures[unit] != name) {
            glBindTextureUnit(unit, name);
            textures[unit] = name;
            issued++;
        }
    }

    /** Binds a sampler to a unit. */
    public void bindSampler(int unit, int name) {
        if (samplers[unit] != name) {
            glBindSampler(unit, name);
            samplers[unit] = name;
            issued++;
        }
    }

    /** Binds a range of a buffer to a shader storage binding point. */
    public void bindStorage(int binding, int buffer, long offset, long size) {
        if (ssbo[binding] != buffer || ssboOffset[binding] != offset || ssboSize[binding] != size) {
            glBindBufferRange(GL_SHADER_STORAGE_BUFFER, binding, buffer, offset, size);
            ssbo[binding] = buffer;
            ssboOffset[binding] = offset;
            ssboSize[binding] = size;
            issued++;
        }
    }

    /** Binds a range of a buffer to a uniform binding point. */
    public void bindUniform(int binding, int buffer, long offset, long size) {
        if (ubo[binding] != buffer || uboOffset[binding] != offset || uboSize[binding] != size) {
            glBindBufferRange(GL_UNIFORM_BUFFER, binding, buffer, offset, size);
            ubo[binding] = buffer;
            uboOffset[binding] = offset;
            uboSize[binding] = size;
            issued++;
        }
    }

    /** Binds the draw-indirect buffer. */
    public void bindDrawIndirect(int buffer) {
        if (drawIndirect != buffer) {
            glBindBuffer(GL_DRAW_INDIRECT_BUFFER, buffer);
            drawIndirect = buffer;
            issued++;
        }
    }

    /** Sets the viewport. */
    public void viewport(int x, int y, int width, int height) {
        if (viewportX != x || viewportY != y || viewportW != width || viewportH != height) {
            glViewport(x, y, width, height);
            viewportX = x;
            viewportY = y;
            viewportW = width;
            viewportH = height;
            issued++;
        }
    }
}
