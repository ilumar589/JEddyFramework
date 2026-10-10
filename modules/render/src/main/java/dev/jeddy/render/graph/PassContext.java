package dev.jeddy.render.graph;

import dev.jeddy.render.gpu.GpuDevice;
import dev.jeddy.render.gpu.StateCache;

/**
 * What a pass sees while it executes: the device, the state cache, the frame size and the GL names
 * of the graph's resources.
 *
 * <p>Thread ownership: the GL thread, during {@link FrameGraph#execute}.
 */
public final class PassContext {
    final FrameGraph graph;
    int width;
    int height;
    long frame;

    PassContext(FrameGraph graph) {
        this.graph = graph;
    }

    /** Returns the device. */
    public GpuDevice device() {
        return graph.device();
    }

    /** Returns the state cache. */
    public StateCache state() {
        return graph.device().state();
    }

    /** Returns the frame width in pixels. */
    public int width() {
        return width;
    }

    /** Returns the frame height in pixels. */
    public int height() {
        return height;
    }

    /** Returns the frame number. */
    public long frame() {
        return frame;
    }

    /** Returns the GL texture name of a texture resource. */
    public int texture(int resource) {
        return graph.textureName(resource);
    }

    /** Returns the GL buffer name of a buffer resource. */
    public int buffer(int resource) {
        return graph.bufferName(resource);
    }
}
