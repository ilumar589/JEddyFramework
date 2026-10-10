package dev.jeddy.render.decal;

import static dev.jeddy.gl.ffi.GL46.*;

import dev.jeddy.core.handle.Handles;
import dev.jeddy.render.gpu.GpuDevice;
import dev.jeddy.render.gpu.GpuTextures;
import dev.jeddy.render.gpu.PipelineState;
import dev.jeddy.render.gpu.SamplerDesc;
import dev.jeddy.render.gpu.StateCache;
import dev.jeddy.render.shader.ProgramCache;
import dev.jeddy.render.shader.ShaderFeatures;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;

/**
 * The textures of {@code DECAL_TEXTURE} decals (TASKS M7-07): one sRGB 2D array texture of square
 * layers with full mip chains, bound at unit {@value #UNIT} for the lit passes. Textures of any
 * size or format are copied in on the GPU ({@link #copy}), so cooked assets (scorch marks, blood,
 * runes) load as ordinary textures and are then registered here; {@link #add} uploads RGBA8 pixels
 * directly. Layers are reused after {@link #remove}; the array doubles when full.
 *
 * <p>Thread ownership: the GL thread.
 */
public final class DecalTextures implements AutoCloseable {
    /** Texture unit of the decal array ({@code engine/decals.glsl}). */
    public static final int UNIT = 10;

    private static final int INITIAL_LAYERS = 4;

    private final GpuDevice device;
    private final ProgramCache programs;
    private final int size;
    private final int levels;
    private final int blitProgram;
    private final ShaderFeatures features = new ShaderFeatures("SOURCE_ARRAY");
    private final int emptyVao;
    private final int sampler;
    private final int blitSampler;
    private int texture;
    private int capacity;
    private int used;
    private int[] free = new int[8];
    private int freeCount;

    /**
     * Creates the array with {@code size}×{@code size} layers ({@code emptyVao}: a vertex array
     * without attributes, for the copy draws).
     */
    public DecalTextures(GpuDevice device, ProgramCache programs, int size, int emptyVao) {
        if (Integer.bitCount(size) != 1 || size < 4) {
            throw new IllegalArgumentException(
                    "decal texture size must be a power of two: " + size);
        }
        this.device = device;
        this.programs = programs;
        this.size = size;
        this.levels = Integer.numberOfTrailingZeros(size) + 1;
        this.emptyVao = emptyVao;
        blitProgram =
                programs.define("blit", "engine/fullscreen.vert", "engine/blit.frag", features);
        sampler =
                device.samplers()
                        .get(
                                SamplerDesc.of(
                                        SamplerDesc.LINEAR,
                                        SamplerDesc.LINEAR,
                                        SamplerDesc.MIP_LINEAR,
                                        SamplerDesc.CLAMP));
        blitSampler = sampler;
        capacity = INITIAL_LAYERS;
        texture = create(capacity);
    }

    private int create(int layers) {
        return device.textures()
                .create(
                        GpuTextures.KIND_2D_ARRAY,
                        GL_SRGB8_ALPHA8,
                        size,
                        size,
                        layers,
                        levels,
                        "decal textures");
    }

    /** Returns the layer size in texels. */
    public int size() {
        return size;
    }

    /** Returns the number of layers in use. */
    public int count() {
        return used - freeCount;
    }

    /** Returns the GL name of the array texture. */
    public int glName() {
        return device.textures().glName(texture);
    }

    /**
     * Uploads {@code size}×{@code size} sRGB RGBA8 pixels (rows bottom-up, as GL expects) into a
     * new layer and builds its mips.
     *
     * @return the layer, for {@code RenderPacket.setDecalTexture}
     */
    public int add(MemorySegment rgba8) {
        if (rgba8.byteSize() < (long) size * size * 4) {
            throw new IllegalArgumentException("need " + size + "×" + size + " RGBA8 pixels");
        }
        int layer = allocate();
        device.textures().uploadLayer(texture, 0, layer, rgba8.address(), size * size * 4);
        glGenerateTextureMipmap(glName());
        return layer;
    }

    /**
     * Copies a texture into a new layer on the GPU, rescaling it: {@code glTexture} is a 2D texture
     * ({@code sourceLayer < 0}) or a 2D array texture and {@code sourceLayer} its layer (material
     * textures live in arrays). sRGB sources stay sRGB; linear ones are taken as linear color.
     *
     * @return the layer
     */
    public int copy(int glTexture, int sourceLayer) {
        int layer = allocate();
        boolean array = sourceLayer >= 0;
        int program = programs.getBlocking(blitProgram, array ? features.bit("SOURCE_ARRAY") : 0);
        int fbo = device.framebuffers().createColorLayer(texture, layer, "decal copy");
        StateCache state = device.state();
        try {
            state.bindFramebuffer(device.framebuffers().glName(fbo));
            state.viewport(0, 0, size, size);
            state.apply(PipelineState.FULLSCREEN);
            state.useProgram(program);
            glProgramUniform1f(program, 0, Math.max(0, sourceLayer));
            state.bindTexture(0, glTexture);
            state.bindSampler(0, blitSampler);
            state.bindVertexArray(emptyVao);
            glEnable(GL_FRAMEBUFFER_SRGB);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            glDisable(GL_FRAMEBUFFER_SRGB);
        } finally {
            state.bindFramebuffer(0);
            device.framebuffers().destroy(fbo);
        }
        glGenerateTextureMipmap(glName());
        return layer;
    }

    /** Frees a layer for reuse. */
    public void remove(int layer) {
        if (freeCount == free.length) {
            free = Arrays.copyOf(free, free.length * 2);
        }
        free[freeCount++] = layer;
    }

    /** Binds the array for the lit passes. */
    public void bind(StateCache state) {
        state.bindTexture(UNIT, glName());
        state.bindSampler(UNIT, sampler);
    }

    private int allocate() {
        if (freeCount > 0) {
            return free[--freeCount];
        }
        if (used == capacity) {
            int cap = capacity * 2;
            if (cap > device.caps().maxArrayTextureLayers()) {
                throw new IllegalStateException("decal texture array is full");
            }
            int bigger = create(cap);
            device.textures().copyLayers(texture, bigger, used);
            device.textures().destroy(texture);
            texture = bigger;
            capacity = cap;
        }
        return used++;
    }

    @Override
    public void close() {
        if (texture != Handles.NULL) {
            device.textures().destroy(texture);
            texture = Handles.NULL;
        }
    }
}
