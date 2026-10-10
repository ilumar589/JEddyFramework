package dev.jeddy.render.fog;

import static dev.jeddy.gl.ffi.GL46.*;

import dev.jeddy.core.handle.Handles;
import dev.jeddy.render.RenderPacket;
import dev.jeddy.render.gpu.GpuDevice;
import dev.jeddy.render.gpu.SamplerDesc;
import dev.jeddy.render.gpu.StateCache;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * The fog-of-war texture (TASKS M7-10): the packet's visibility grid ({@link
 * RenderPacket#setFogOfWar}, filled by the game from the physics visibility system) uploaded as an
 * R8 texture whenever its version changes, and sampled bilinearly by the lit pass at unit {@value
 * #UNIT} ({@code engine/fog_of_war.glsl}): explored cells out of sight are dimmed and desaturated,
 * unexplored ones black.
 *
 * <p>Thread ownership: the render thread.
 */
public final class FogOfWar implements AutoCloseable {
    /** Texture unit of the grid. */
    public static final int UNIT = 12;

    private final GpuDevice device;
    private final int sampler;
    private Arena arena = Arena.ofConfined();
    private MemorySegment staging = MemorySegment.NULL;
    private int texture = Handles.NULL;
    private int width;
    private int height;
    private int version = Integer.MIN_VALUE;
    private boolean active;

    /** Creates an empty fog (inactive until a packet carries a grid). */
    public FogOfWar(GpuDevice device) {
        this.device = device;
        this.sampler = device.samplers().get(SamplerDesc.LINEAR_CLAMP);
    }

    /**
     * Uploads the packet's grid if its version changed (reallocating only when the grid size
     * changes). A packet without a grid turns the fog off.
     */
    public void update(RenderPacket p) {
        active = p.fogOfWar != null && p.fogWidth > 0 && p.fogHeight > 0;
        if (!active || p.fogVersion == version && p.fogWidth == width && p.fogHeight == height) {
            return;
        }
        if (p.fogWidth != width || p.fogHeight != height) {
            if (texture != Handles.NULL) {
                device.textures().destroy(texture);
            }
            width = p.fogWidth;
            height = p.fogHeight;
            texture = device.textures().create2D(GL_R8, width, height, 1, "fog of war");
            arena.close();
            arena = Arena.ofConfined();
            staging = arena.allocate((long) width * height, 16);
        }
        MemorySegment.copy(p.fogOfWar, 0, staging, ValueLayout.JAVA_BYTE, 0, width * height);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        device.textures().uploadLevel(texture, 0, staging.address(), width * height);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        version = p.fogVersion;
    }

    /** Returns whether this frame has a grid. */
    public boolean active() {
        return active;
    }

    /** Binds the grid for the lit passes. */
    public void bind(StateCache state) {
        if (active) {
            state.bindTexture(UNIT, device.textures().glName(texture));
            state.bindSampler(UNIT, sampler);
        }
    }

    @Override
    public void close() {
        if (texture != Handles.NULL) {
            device.textures().destroy(texture);
            texture = Handles.NULL;
        }
        arena.close();
    }
}
