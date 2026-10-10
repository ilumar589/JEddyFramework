package dev.jeddy.render.gpu;

import dev.jeddy.gl.GLCapabilities;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * The GPU resource layer (TASKS M5-03, DESIGN §9.2): buffers, textures, samplers and framebuffers
 * behind handles, plus the {@link StateCache}. Created once the GL context is current; closing it
 * deletes every resource it still owns.
 *
 * <p>Thread ownership: the GL context thread (main thread).
 */
public final class GpuDevice implements AutoCloseable {
    private final GLCapabilities caps;
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment scratch = arena.allocate(256, 16);
    private final GpuBuffers buffers = new GpuBuffers(this);
    private final GpuTextures textures = new GpuTextures(this);
    private final GpuSamplers samplers = new GpuSamplers(this);
    private final GpuFramebuffers framebuffers = new GpuFramebuffers(this);
    private final StateCache state = new StateCache();

    /** Creates the device for the current context. */
    public GpuDevice(GLCapabilities caps) {
        this.caps = caps;
    }

    /** Returns the context's capabilities. */
    public GLCapabilities caps() {
        return caps;
    }

    /** Returns the buffers. */
    public GpuBuffers buffers() {
        return buffers;
    }

    /** Returns the textures. */
    public GpuTextures textures() {
        return textures;
    }

    /** Returns the sampler cache. */
    public GpuSamplers samplers() {
        return samplers;
    }

    /** Returns the framebuffers. */
    public GpuFramebuffers framebuffers() {
        return framebuffers;
    }

    /** Returns the state cache. */
    public StateCache state() {
        return state;
    }

    /**
     * Switches clip space to depth [0, 1] with a lower-left origin ({@code glClipControl}), which
     * the reversed-Z projections need (DESIGN §8.2): clear depth to 0 and test {@code GL_GEQUAL}
     * ({@link PipelineState#OPAQUE} does). Call once after creating the context.
     */
    public void useReversedZ() {
        dev.jeddy.gl.ffi.GL46.glClipControl(
                dev.jeddy.gl.ffi.GL46.GL_LOWER_LEFT, dev.jeddy.gl.ffi.GL46.GL_ZERO_TO_ONE);
        dev.jeddy.gl.ffi.GL46.glClearDepthf(0f);
    }

    /** 256 bytes of scratch for GL out-parameters (names); main thread only. */
    MemorySegment scratch() {
        return scratch;
    }

    /** Deletes every resource. */
    @Override
    public void close() {
        framebuffers.destroyAll();
        textures.destroyAll();
        buffers.destroyAll();
        samplers.destroyAll();
        arena.close();
    }
}
