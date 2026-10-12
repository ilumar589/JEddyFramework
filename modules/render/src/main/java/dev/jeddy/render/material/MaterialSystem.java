package dev.jeddy.render.material;

import dev.jeddy.asset.format.MaterialData;
import dev.jeddy.render.draw.DrawBuilder;
import dev.jeddy.render.draw.MaterialTable;
import dev.jeddy.render.gpu.FrameRing;
import dev.jeddy.render.gpu.GpuDevice;
import dev.jeddy.render.gpu.PipelineState;
import dev.jeddy.render.gpu.SamplerDesc;
import dev.jeddy.render.gpu.StateCache;
import dev.jeddy.render.shader.ProgramCache;
import dev.jeddy.render.shader.ShaderFeatures;

import java.util.Arrays;

/**
 * PBR materials on the GPU (TASKS M7-01): rows of the {@link MaterialTable} in the layout of {@code
 * engine/material.glsl}, textures as layers of {@link TextureArrays}, and the program variants they
 * draw with. A material's program slot combines its shader features ({@link
 * MaterialData#featureMask} plus the renderer's global features) with its <em>texture set</em> (the
 * bucket of each of its five texture slots), so every multi-draw batch binds one program and one
 * set of arrays and per-instance materials only vary the layer.
 *
 * <p>Textures that are not loaded yet simply leave their feature bit off; {@link #setTexture} turns
 * it on when they arrive, which moves the material to another slot.
 *
 * <p>Thread ownership: the GL thread.
 */
public final class MaterialSystem implements AutoCloseable {
    /**
     * Texture slots, as in {@link MaterialData} ({@code SLOT_BASE_COLOR} .. {@code
     * SLOT_OCCLUSION}).
     */
    public static final int TEXTURE_SLOTS = 5;

    /** Global feature: sun shadows. */
    public static final long SUN_SHADOWS = 1L << 32;

    /** Global feature: spot and point light shadows. */
    public static final long LOCAL_SHADOWS = 1L << 33;

    /** Global feature: screen-space ambient occlusion. */
    public static final long SCREEN_AO = 1L << 34;

    /** Global feature: clustered decals. */
    public static final long DECALS = 1L << 35;

    /** Global feature: fog of war. */
    public static final long FOG_OF_WAR = 1L << 36;

    /** Feature bits the depth-only variants care about. */
    private static final long DEPTH_FEATURES =
            MaterialData.FEATURE_ALPHA_MASK
                    | MaterialData.FEATURE_BASE_COLOR_MAP
                    | MaterialData.FEATURE_UV_TRANSFORM
                    | MaterialData.FEATURE_UV1
                    | MaterialTable.SKINNED;

    private static final int[] FEATURE_OF_SLOT = {
        MaterialData.FEATURE_BASE_COLOR_MAP,
        MaterialData.FEATURE_NORMAL_MAP,
        MaterialData.FEATURE_ORM_MAP,
        MaterialData.FEATURE_EMISSIVE_MAP,
        MaterialData.FEATURE_OCCLUSION_MAP
    };

    private final GpuDevice device;
    private final ProgramCache programs;
    private final MaterialTable table;
    private final TextureArrays arrays;
    private final int pbrProgram;
    private final int depthProgram;
    private final int sampler;
    private long globalFeatures;

    // Per material: feature mask without texture bits, texture refs, uv1 bits, flags.
    private int count;
    private int[] freeList = new int[16];
    private int freeCount;
    private boolean[] live = new boolean[64];
    private long[] baseFeatures = new long[64];
    private int[] textureRefs = new int[64 * TEXTURE_SLOTS];
    private float[] rows = new float[64 * MaterialTable.FLOATS];
    private final float[] row = new float[MaterialTable.FLOATS];

    // Texture sets: bucket per slot (-1 none).
    private int setCount = 1;
    private int[] setBuckets = new int[16 * TEXTURE_SLOTS];
    private final int[] lookup = new int[TEXTURE_SLOTS];
    private int boundSet = -1;
    private int boundGeneration = -1;

    /** Default material: white, rough dielectric (index 0). */
    public static final int DEFAULT_MATERIAL = 0;

    /**
     * Creates the system and defines the PBR and depth programs ({@code pbr/pbr.vert} + {@code
     * pbr/pbr.frag}, {@code pbr/depth.vert} + {@code pbr/depth.frag}) in {@code programs}.
     */
    public MaterialSystem(GpuDevice device, ProgramCache programs, int capacity) {
        this.device = device;
        this.programs = programs;
        this.table = new MaterialTable(device, programs, capacity);
        this.arrays = new TextureArrays(device);
        ShaderFeatures features = features();
        this.pbrProgram = programs.define("pbr", "pbr/pbr.vert", "pbr/pbr.frag", features);
        this.depthProgram = programs.define("depth", "pbr/depth.vert", "pbr/depth.frag", features);
        float anisotropy = Math.min(8f, device.caps().maxAnisotropy());
        this.sampler =
                device.samplers()
                        .get(
                                SamplerDesc.withAnisotropy(
                                        SamplerDesc.TRILINEAR_REPEAT, (int) anisotropy));
        Arrays.fill(setBuckets, -1);
        MaterialData.Builder b = new MaterialData.Builder().metallicRoughness(0, 0.8f);
        this.litOpaque =
                (state, slot, translucent) ->
                        !translucent
                                && bindLit(
                                        state,
                                        slot,
                                        PipelineState.withDepthWrite(PipelineState.OPAQUE, false));
        this.litOpaqueNoPrepass =
                (state, slot, translucent) ->
                        !translucent && bindLit(state, slot, PipelineState.OPAQUE);
        // Back faces of blended surfaces are culled unless the material is double-sided (unsorted
        // back and front faces of one mesh would blend in arbitrary order).
        this.litTranslucent =
                (state, slot, translucent) ->
                        translucent
                                && bindLit(
                                        state,
                                        slot,
                                        PipelineState.withCull(
                                                PipelineState.TRANSPARENT,
                                                PipelineState.CULL_BACK));
        this.depthOnly =
                (state, slot, translucent) -> {
                    if (translucent) {
                        return false;
                    }
                    long mask = this.table.slotMask(slot);
                    int program = this.programs.get(this.depthProgram, mask & DEPTH_FEATURES);
                    if (program == 0) {
                        return false;
                    }
                    long pipeline = cullFor(PipelineState.DEPTH_ONLY, mask);
                    long override = this.depthPipelineOverride;
                    state.apply(override != 0 ? cullFor(override, mask) : pipeline);
                    state.useProgram(program);
                    DepthUniforms sink = this.depthUniformSink;
                    if (sink != null) {
                        sink.prepare(program);
                    }
                    if ((mask & MaterialData.FEATURE_ALPHA_MASK) != 0) {
                        bindSet(state, this.table.slotTextureSet(slot));
                    }
                    return true;
                };
        add(b.build());
    }

    /** The feature table shared by the PBR and depth programs. */
    public static ShaderFeatures features() {
        String[] names = new String[37];
        for (int i = 0; i < 16; i++) {
            names[i] =
                    i < MaterialData.FEATURE_NAMES.size()
                            ? MaterialData.FEATURE_NAMES.get(i)
                            : "J_RESERVED_" + i;
        }
        names[15] = "SKINNED"; // MaterialTable.SKINNED
        for (int i = 16; i < 32; i++) {
            names[i] = "MATERIAL_CUSTOM_" + i;
        }
        names[32] = "SUN_SHADOWS";
        names[33] = "LOCAL_SHADOWS";
        names[34] = "SCREEN_AO";
        names[35] = "DECALS";
        names[36] = "FOG_OF_WAR";
        return new ShaderFeatures(names);
    }

    /** Returns the material table (bind as storage; draw builders read its slots). */
    public MaterialTable table() {
        return table;
    }

    /** Returns the texture arrays. */
    public TextureArrays arrays() {
        return arrays;
    }

    /** Returns the PBR program definition. */
    public int pbrProgram() {
        return pbrProgram;
    }

    /** Returns the depth-only program definition. */
    public int depthProgram() {
        return depthProgram;
    }

    /** Returns the number of materials. */
    public int count() {
        return count;
    }

    /**
     * Sets the renderer-wide features ({@link #SUN_SHADOWS}, {@link #SCREEN_AO}, ...) that every
     * material's lit variant includes.
     */
    public void setGlobalFeatures(long mask) {
        if (mask == globalFeatures) {
            return;
        }
        globalFeatures = mask;
        for (int m = 0; m < count; m++) {
            reslot(m);
        }
    }

    /** Returns the renderer-wide features. */
    public long globalFeatures() {
        return globalFeatures;
    }

    /**
     * Adds a material with its factors; its textures follow through {@link #setTexture}.
     *
     * @return the material index (what render packets refer to)
     */
    public int add(MaterialData data) {
        int m;
        boolean reuse = freeCount > 0;
        if (reuse) {
            m = freeList[--freeCount];
        } else {
            m = count;
            ensure(m + 1);
            count++;
        }
        live[m] = true;
        write(m, data, !reuse);
        return m;
    }

    /**
     * Replaces a live material's factors and features in place (a reloaded {@code .jmat}), keeping
     * its index and textures.
     */
    public void update(int material, MaterialData data) {
        int[] refs =
                Arrays.copyOfRange(
                        textureRefs, material * TEXTURE_SLOTS, (material + 1) * TEXTURE_SLOTS);
        int uvBits = Float.floatToRawIntBits(rows[material * MaterialTable.FLOATS + 25]);
        write(material, data, false);
        for (int s = 0; s < TEXTURE_SLOTS; s++) {
            if (refs[s] != TextureArrays.NONE) {
                setTexture(material, s, refs[s], (uvBits & 1 << s) != 0);
            }
        }
    }

    /** Frees a material index for reuse; instances must no longer draw with it. */
    public void remove(int material) {
        if (material == DEFAULT_MATERIAL || !live[material]) {
            return;
        }
        live[material] = false;
        if (freeCount == freeList.length) {
            freeList = Arrays.copyOf(freeList, freeCount * 2);
        }
        freeList[freeCount++] = material;
    }

    /** Returns {@code true} if a material index is in use. */
    public boolean isLive(int material) {
        return material >= 0 && material < count && live[material];
    }

    private void write(int m, MaterialData data, boolean append) {
        long features = data.featureMask() & 0xFFFF_FFFFL;
        for (int bit : FEATURE_OF_SLOT) {
            features &= ~bit;
        }
        features &= ~MaterialData.FEATURE_UV1;
        baseFeatures[m] = features;
        Arrays.fill(textureRefs, m * TEXTURE_SLOTS, (m + 1) * TEXTURE_SLOTS, TextureArrays.NONE);
        int o = m * MaterialTable.FLOATS;
        Arrays.fill(rows, o, o + MaterialTable.FLOATS, 0f);
        for (int i = 0; i < 4; i++) {
            rows[o + i] = data.baseColor(i);
        }
        rows[o + 4] = data.emissive(0);
        rows[o + 5] = data.emissive(1);
        rows[o + 6] = data.emissive(2);
        rows[o + 7] = data.emissiveStrength();
        rows[o + 8] = data.metallic();
        rows[o + 9] = data.roughness();
        rows[o + 10] = data.normalScale();
        rows[o + 11] = data.occlusionStrength();
        for (int i = 0; i < 4; i++) {
            rows[o + 12 + i] = data.uvTransform(i);
        }
        rows[o + 16] = data.uvRotation();
        rows[o + 17] = data.alphaCutoff();
        rows[o + 26] = Float.intBitsToFloat(data.flags());
        System.arraycopy(rows, o, row, 0, MaterialTable.FLOATS);
        if (append) {
            int index = table.add(pbrProgram, features | globalFeatures, 0, row);
            if (index != m) {
                throw new IllegalStateException("material table out of step");
            }
        } else {
            table.set(m, row);
            reslot(m);
        }
    }

    /**
     * Sets texture slot {@code slot} ({@code MaterialData.SLOT_*}) of a material to a layer of
     * {@link #arrays()} ({@link TextureArrays#NONE} clears it).
     *
     * @param uv1 sample with the second UV set
     */
    public void setTexture(int material, int slot, int ref, boolean uv1) {
        int o = material * MaterialTable.FLOATS;
        textureRefs[material * TEXTURE_SLOTS + slot] = ref;
        int layer = ref == TextureArrays.NONE ? 0 : TextureArrays.layer(ref);
        // layers (uvec4) at floats 20..23, layers2.x at 24, uv1 bits at 25.
        int at = slot < 4 ? o + 20 + slot : o + 24;
        rows[at] = Float.intBitsToFloat(layer);
        int uvBits = Float.floatToRawIntBits(rows[o + 25]);
        uvBits = uv1 ? uvBits | 1 << slot : uvBits & ~(1 << slot);
        rows[o + 25] = Float.intBitsToFloat(uvBits);
        System.arraycopy(rows, o, row, 0, MaterialTable.FLOATS);
        table.set(material, row);
        reslot(material);
    }

    /** Returns the texture reference in a slot, or {@link TextureArrays#NONE}. */
    public int texture(int material, int slot) {
        return textureRefs[material * TEXTURE_SLOTS + slot];
    }

    /** Returns the feature mask a material currently draws with (textures present only). */
    public long features(int material) {
        long f = baseFeatures[material];
        int uvBits = Float.floatToRawIntBits(rows[material * MaterialTable.FLOATS + 25]);
        for (int s = 0; s < TEXTURE_SLOTS; s++) {
            if (textureRefs[material * TEXTURE_SLOTS + s] != TextureArrays.NONE) {
                f |= FEATURE_OF_SLOT[s];
                if ((uvBits & 1 << s) != 0) {
                    f |= MaterialData.FEATURE_UV1;
                }
            }
        }
        return f;
    }

    private void reslot(int m) {
        for (int s = 0; s < TEXTURE_SLOTS; s++) {
            int ref = textureRefs[m * TEXTURE_SLOTS + s];
            lookup[s] = ref == TextureArrays.NONE ? -1 : TextureArrays.bucket(ref);
        }
        table.setSlot(m, pbrProgram, features(m) | globalFeatures, textureSet(lookup));
    }

    private int textureSet(int[] buckets) {
        outer:
        for (int set = 0; set < setCount; set++) {
            for (int s = 0; s < TEXTURE_SLOTS; s++) {
                if (setBuckets[set * TEXTURE_SLOTS + s] != buckets[s]) {
                    continue outer;
                }
            }
            return set;
        }
        if ((setCount + 1) * TEXTURE_SLOTS > setBuckets.length) {
            int old = setBuckets.length;
            setBuckets = Arrays.copyOf(setBuckets, old * 2);
            Arrays.fill(setBuckets, old, setBuckets.length, -1);
        }
        System.arraycopy(buckets, 0, setBuckets, setCount * TEXTURE_SLOTS, TEXTURE_SLOTS);
        return setCount++;
    }

    /**
     * Uploads changed material rows through the frame ring (a GPU-side copy, so rows added after
     * the buffer was first used reach every driver, D103). Call once per frame before drawing.
     */
    public void flush(FrameRing ring) {
        table.flush(ring);
    }

    /** {@link #flush(FrameRing)} from the CPU (tests). */
    public void flush() {
        table.flush(null);
    }

    /** Forgets cached texture bindings (after other passes used units 0-4). */
    public void invalidateBindings() {
        boundSet = -1;
    }

    private void bindSet(StateCache state, int set) {
        if (set == boundSet && boundGeneration == arrays.generation()) {
            return;
        }
        for (int s = 0; s < TEXTURE_SLOTS; s++) {
            int bucket = setBuckets[set * TEXTURE_SLOTS + s];
            if (bucket >= 0) {
                state.bindTexture(s, arrays.glName(bucket));
                state.bindSampler(s, sampler);
            }
        }
        boundSet = set;
        boundGeneration = arrays.generation();
    }

    private static long cullFor(long state, long mask) {
        return (mask & MaterialData.FEATURE_DOUBLE_SIDED) != 0
                ? PipelineState.withCull(state, PipelineState.CULL_NONE)
                : state;
    }

    /**
     * Binder for the lit opaque pass after a depth prepass: reversed-Z test {@code GL_GEQUAL}
     * without depth writes. (Not {@code GL_EQUAL}: the prepass and lit programs compute the same
     * positions, but nothing guarantees bit-identical depth across two different programs.)
     */
    public final DrawBuilder.BatchBinder litOpaque;

    /** Binder for the lit opaque pass without a prepass. */
    public final DrawBuilder.BatchBinder litOpaqueNoPrepass;

    /** Binder for the sorted transparent pass. */
    public final DrawBuilder.BatchBinder litTranslucent;

    private boolean bindLit(StateCache state, int slot, long pipeline) {
        int program = table.glProgram(slot);
        if (program == 0) {
            return false;
        }
        long mask = table.slotMask(slot);
        state.apply(cullFor(pipeline, mask));
        state.useProgram(program);
        bindSet(state, table.slotTextureSet(slot));
        return true;
    }

    /**
     * Binder for depth-only passes (prepass, shadows): the depth variant of each batch's material
     * features; {@code viewProjectionLocation} uniform 0 must be set by the caller per program via
     * {@link #depthProgramFor}. Translucent batches are skipped.
     */
    public final DrawBuilder.BatchBinder depthOnly;

    /**
     * Creates a binder like {@link #depthOnly} for another program defined with {@link #features()}
     * (only the alpha-mask and base-color bits select variants): masks, IDs, custom depth-like
     * passes. Create it once; binding allocates nothing.
     *
     * @param definition the program definition
     * @param uniforms sets per-program uniforms as the binder switches programs
     * @param pipeline the pipeline state (double-sided materials disable culling)
     */
    public DrawBuilder.BatchBinder depthBinder(
            int definition, DepthUniforms uniforms, long pipeline) {
        return (state, slot, translucent) -> {
            if (translucent) {
                return false;
            }
            long mask = table.slotMask(slot);
            int program = programs.get(definition, mask & DEPTH_FEATURES);
            if (program == 0) {
                return false;
            }
            state.apply(cullFor(pipeline, mask));
            state.useProgram(program);
            uniforms.prepare(program);
            if ((mask & MaterialData.FEATURE_ALPHA_MASK) != 0) {
                bindSet(state, table.slotTextureSet(slot));
            }
            return true;
        };
    }

    /** Sets uniforms on depth programs as the depth binder switches to them. */
    @FunctionalInterface
    public interface DepthUniforms {
        /** Called with the GL program about to draw. */
        void prepare(int program);
    }

    private DepthUniforms depthUniformSink;
    private long depthPipelineOverride;

    /**
     * Configures {@link #depthOnly} for the next submit: a callback that sets the view-projection
     * uniform (location 0) of each depth program, and the pipeline state ({@code 0} for {@link
     * PipelineState#DEPTH_ONLY}).
     */
    public void configureDepth(DepthUniforms uniforms, long pipeline) {
        depthUniformSink = uniforms;
        depthPipelineOverride = pipeline;
    }

    /** Returns the GL depth program for a feature mask (0 while compiling). */
    public int depthProgramFor(long mask) {
        return programs.get(depthProgram, mask & DEPTH_FEATURES);
    }

    /**
     * Compiles a material's lit and depth variants now (loading screens, tests), so its first frame
     * draws.
     */
    public void compileBlocking(int material) {
        int slot = table.programSlot(material);
        programs.getBlocking(pbrProgram, table.slotMask(slot));
        programs.getBlocking(depthProgram, table.slotMask(slot) & DEPTH_FEATURES);
    }

    /**
     * Compiles the {@link MaterialTable#SKINNED} lit and depth variants of a material now (loading
     * screens, tests), preparing its skinned program slot.
     */
    public void compileBlockingSkinned(int material) {
        int slot = table.prepareSkinned(table.programSlot(material));
        programs.getBlocking(pbrProgram, table.slotMask(slot));
        programs.getBlocking(depthProgram, table.slotMask(slot) & DEPTH_FEATURES);
    }

    /** Returns the GL program of a material's lit variant (0 while compiling). */
    public int litProgramFor(int material) {
        return table.glProgram(table.programSlot(material));
    }

    private void ensure(int n) {
        if (n <= baseFeatures.length) {
            return;
        }
        int cap = Math.max(n, baseFeatures.length * 2);
        baseFeatures = Arrays.copyOf(baseFeatures, cap);
        live = Arrays.copyOf(live, cap);
        textureRefs = Arrays.copyOf(textureRefs, cap * TEXTURE_SLOTS);
        rows = Arrays.copyOf(rows, cap * MaterialTable.FLOATS);
    }

    @Override
    public void close() {
        table.close();
        arrays.close();
    }
}
