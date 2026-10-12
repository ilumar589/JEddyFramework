package dev.jeddy.render.light;

import static dev.jeddy.gl.ffi.GL46.*;

import dev.jeddy.render.RenderPacket;
import dev.jeddy.render.gpu.FrameRing;
import dev.jeddy.render.gpu.GpuBuffers;
import dev.jeddy.render.gpu.GpuDevice;
import dev.jeddy.render.gpu.StateCache;
import dev.jeddy.render.shader.ProgramCache;
import dev.jeddy.render.shader.ShaderFeatures;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Clustered light culling (TASKS M7-03, DESIGN §9.3 pass 4): the view frustum is cut into {@value
 * #TILE}-pixel screen tiles × {@value #SLICES} depth slices (exponential for perspective, linear
 * for orthographic). A compute pass computes each cluster's view-space bounds when the projection
 * or viewport changes; another assigns the frame's lights to clusters every frame, writing an
 * (offset, count) range per cluster into a shared index list. The forward shader walks only its
 * cluster's lights, so thousands of spell lights cost what the handful near each pixel costs.
 * Decals (TASKS M7-07) share the grid: a cluster's list holds its lights, then its decals, and the
 * count packs both (lights in the low 16 bits, decals in the high 16).
 *
 * <p>Thread ownership: the GL thread.
 */
public final class LightClusters implements AutoCloseable {
    /** Screen tile size in pixels. */
    public static final int TILE = 64;

    /** Depth slices. */
    public static final int SLICES = 24;

    /** Storage binding of the light array. */
    public static final int LIGHTS_BINDING = 2;

    /** Storage binding of the per-cluster ranges. */
    public static final int RANGES_BINDING = 3;

    /** Storage binding of the light index list. */
    public static final int ITEMS_BINDING = 4;

    /** Storage binding of the cluster bounds. */
    public static final int BOUNDS_BINDING = 7;

    /** Storage binding of the decal array. */
    public static final int DECALS_BINDING = 6;

    /** Average light references per cluster the index list has room for. */
    public static final int AVERAGE_PER_CLUSTER = 48;

    private static final int GROUP = 64;

    private final GpuDevice device;
    private final ProgramCache programs;
    private final int boundsProgram;
    private final int cullProgram;
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment header = arena.allocate(16, 16);
    private final float[] lastProjection = new float[16];
    private int dimX;
    private int dimY;
    private int clusters;
    private int boundsBuffer;
    private int rangesBuffer;
    private int itemsBuffer;
    private int capacity;
    private boolean boundsDirty = true;
    private long lightsOffset;
    private long lightsBytes;
    private int lightCount;
    private long decalsOffset;
    private long decalsBytes;
    private int decalCount;
    private int width;
    private int height;

    /** Creates the cluster grid; call {@link #resize} before use. */
    public LightClusters(GpuDevice device, ProgramCache programs) {
        this.device = device;
        this.programs = programs;
        boundsProgram =
                programs.defineCompute(
                        "cluster bounds", "pbr/cluster_bounds.comp", ShaderFeatures.NONE);
        cullProgram =
                programs.defineCompute("light cull", "pbr/light_cull.comp", ShaderFeatures.NONE);
    }

    /** Sizes the grid for a {@code w}×{@code h} viewport. */
    public void resize(int w, int h) {
        if (w == width && h == height) {
            return;
        }
        width = w;
        height = h;
        dimX = (w + TILE - 1) / TILE;
        dimY = (h + TILE - 1) / TILE;
        clusters = dimX * dimY * SLICES;
        release();
        boundsBuffer = device.buffers().create((long) clusters * 32, 0, "cluster bounds");
        rangesBuffer = device.buffers().create((long) clusters * 8, 0, "cluster ranges");
        capacity = clusters * AVERAGE_PER_CLUSTER;
        itemsBuffer =
                device.buffers()
                        .create(16 + (long) capacity * 4, GpuBuffers.DYNAMIC, "cluster lights");
        boundsDirty = true;
    }

    /** Clusters along x. */
    public int dimX() {
        return dimX;
    }

    /** Clusters along y. */
    public int dimY() {
        return dimY;
    }

    /** Total clusters. */
    public int clusterCount() {
        return clusters;
    }

    /** Returns the number of lights uploaded this frame. */
    public int lightCount() {
        return lightCount;
    }

    /**
     * Copies the packet's lights and decals into the frame ring (they are already in the GPU
     * layout).
     *
     * @return {@code false} if the ring is full (no lights or decals this frame)
     */
    public boolean upload(RenderPacket p, FrameRing ring) {
        int align = device.caps().shaderStorageBufferOffsetAlignment();
        decalCount = p.decalCount;
        decalsBytes = Math.max(112L, (long) p.decalCount * RenderPacket.DECAL_FLOATS * 4);
        decalsOffset = ring.allocate(decalsBytes, align);
        if (decalsOffset < 0) {
            decalCount = 0;
            lightCount = 0;
            return false;
        }
        if (p.decalCount > 0) {
            MemorySegment.copy(
                    p.decals,
                    0,
                    ring.memory(),
                    ValueLayout.JAVA_FLOAT,
                    decalsOffset,
                    p.decalCount * RenderPacket.DECAL_FLOATS);
        }
        lightCount = p.lightCount;
        lightsBytes = Math.max(64L, (long) p.lightCount * RenderPacket.LIGHT_FLOATS * 4);
        lightsOffset = ring.allocate(lightsBytes, align);
        if (lightsOffset < 0) {
            lightCount = 0;
            return false;
        }
        if (p.lightCount > 0) {
            MemorySegment.copy(
                    p.lights,
                    0,
                    ring.memory(),
                    ValueLayout.JAVA_FLOAT,
                    lightsOffset,
                    p.lightCount * RenderPacket.LIGHT_FLOATS);
        }
        return true;
    }

    /** Returns the ring offset of this frame's light array (to patch shadow indices). */
    public long lightsOffset() {
        return lightsOffset;
    }

    /** Returns the light count to put in the frame constants (0 if the upload failed). */
    public int frameLightCount() {
        return lightCount;
    }

    /** Returns the decal count to put in the frame constants (0 if the upload failed). */
    public int frameDecalCount() {
        return decalCount;
    }

    /**
     * Recomputes the bounds if the projection changed, then assigns lights to clusters. The frame
     * constants (binding 0) must be bound.
     */
    public void dispatch(StateCache state, RenderPacket p, FrameRing ring) {
        boolean changed = boundsDirty;
        for (int i = 0; i < 16 && !changed; i++) {
            changed = lastProjection[i] != p.projection[i];
        }
        state.bindStorage(
                BOUNDS_BINDING, device.buffers().glName(boundsBuffer), 0, (long) clusters * 32);
        int groups = (clusters + GROUP - 1) / GROUP;
        if (changed) {
            int program = programs.getBlocking(boundsProgram, 0);
            state.useProgram(program);
            glDispatchCompute(groups, 1, 1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            System.arraycopy(p.projection, 0, lastProjection, 0, 16);
            boundsDirty = false;
        }
        header.set(ValueLayout.JAVA_INT, 0, 0);
        header.set(ValueLayout.JAVA_INT, 4, capacity);
        // The item counter resets on the GPU, ordered after last frame's cull (D103).
        if (!ring.upload(device.buffers().glName(itemsBuffer), 0, header, 0, header.byteSize())) {
            device.buffers().update(itemsBuffer, 0, header);
        }
        bind(state, ring);
        state.useProgram(programs.getBlocking(cullProgram, 0));
        glDispatchCompute(groups, 1, 1);
        // Storage: the shading pass reads the lists. Buffer update: next frame's counter reset is
        // a buffer copy over memory these atomics wrote, which GL orders only after this bit.
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT);
    }

    /** Binds the lights, decals, ranges and index list for shading. */
    public void bind(StateCache state, FrameRing ring) {
        state.bindStorage(LIGHTS_BINDING, ring.glName(), lightsOffset, lightsBytes);
        state.bindStorage(DECALS_BINDING, ring.glName(), decalsOffset, decalsBytes);
        state.bindStorage(
                RANGES_BINDING, device.buffers().glName(rangesBuffer), 0, (long) clusters * 8);
        state.bindStorage(
                ITEMS_BINDING, device.buffers().glName(itemsBuffer), 0, 16 + (long) capacity * 4);
    }

    /** Returns the GL names of the ranges and index buffers (tests and debug views). */
    public int rangesGlName() {
        return device.buffers().glName(rangesBuffer);
    }

    /** Returns the GL name of the index list buffer. */
    public int itemsGlName() {
        return device.buffers().glName(itemsBuffer);
    }

    private void release() {
        if (boundsBuffer != dev.jeddy.core.handle.Handles.NULL) {
            device.buffers().destroy(boundsBuffer);
            device.buffers().destroy(rangesBuffer);
            device.buffers().destroy(itemsBuffer);
            boundsBuffer = dev.jeddy.core.handle.Handles.NULL;
        }
    }

    @Override
    public void close() {
        release();
        arena.close();
    }
}
