package dev.jeddy.render;

import static dev.jeddy.gl.ffi.GL46.*;

import dev.jeddy.core.job.JobSystem;
import dev.jeddy.math.CullOps;
import dev.jeddy.math.Mat4Ops;
import dev.jeddy.render.decal.DecalTextures;
import dev.jeddy.render.draw.Culling;
import dev.jeddy.render.draw.DrawBuilder;
import dev.jeddy.render.draw.StaticScene;
import dev.jeddy.render.fog.FogOfWar;
import dev.jeddy.render.gpu.FrameRing;
import dev.jeddy.render.gpu.GpuDevice;
import dev.jeddy.render.gpu.PipelineState;
import dev.jeddy.render.gpu.StateCache;
import dev.jeddy.render.graph.FrameGraph;
import dev.jeddy.render.graph.PassContext;
import dev.jeddy.render.light.Environment;
import dev.jeddy.render.light.LightClusters;
import dev.jeddy.render.material.MaterialSystem;
import dev.jeddy.render.mesh.MeshRegistry;
import dev.jeddy.render.outline.Outlines;
import dev.jeddy.render.particle.GpuParticles;
import dev.jeddy.render.post.Gtao;
import dev.jeddy.render.post.PostProcess;
import dev.jeddy.render.shader.ProgramCache;
import dev.jeddy.render.shader.ShaderFeatures;
import dev.jeddy.render.shadow.LocalShadows;
import dev.jeddy.render.shadow.SunShadows;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * The forward+ PBR frame (TASKS M7, DESIGN §9.3): culling and draw building for the main view,
 * per-frame constants, clustered light assignment, sun and local shadows, a depth prepass, GTAO,
 * the lit opaque pass, the sky and the post chain to the window, wired as {@link FrameGraph}
 * passes. Decals, outlines, transparency and particles plug into the same graph.
 *
 * <p>Thread ownership: the render (main) thread; {@link #render} borrows job workers for culling
 * and draw building.
 */
public final class SceneRenderer implements AutoCloseable {
    /** Uniform binding of the frame constants. */
    public static final int FRAME_BINDING = 0;

    /**
     * Storage binding of the frame's skinning palettes ({@code engine/skinning.glsl}, TASKS
     * M12-04).
     */
    public static final int PALETTE_BINDING = 9;

    /** Bytes of the frame constants block ({@code engine/frame.glsl}). */
    public static final int FRAME_BYTES = 544;

    /**
     * Settings.
     *
     * @param ringBytesPerFrame per-frame ring size (instance records, commands, lights, constants)
     * @param maxMaterials initial material capacity
     * @param sunShadows cascade settings, or {@code null} for no sun shadows
     * @param localShadows spot and point shadow atlas settings, or {@code null} for none
     * @param ambientOcclusion half-resolution GTAO settings, or {@code null} for none
     * @param decalTextureSize layer size of the decal texture array, or 0 for no decals
     * @param outlines whether highlighted instances get outlines
     * @param particleCapacity GPU particle pool size, or 0 for no particles
     * @param fogOfWar whether the lit pass applies the packet's fog-of-war grid
     * @param gpuCulling whether static instances are culled and drawn GPU-driven ({@link
     *     StaticScene})
     * @param post bloom, exposure, tone mapping, grading and anti-aliasing settings
     */
    public record Config(
            long ringBytesPerFrame,
            int maxMaterials,
            SunShadows.Config sunShadows,
            LocalShadows.Config localShadows,
            Gtao.Config ambientOcclusion,
            int decalTextureSize,
            boolean outlines,
            int particleCapacity,
            boolean fogOfWar,
            boolean gpuCulling,
            PostProcess.Config post) {
        /**
         * 32 MiB per frame, 256 materials, shadows, AO, 256² decal textures, outlines, 64k
         * particles, fog of war, GPU culling of static instances, default post.
         */
        public static final Config DEFAULT =
                new Config(
                        32L << 20,
                        256,
                        SunShadows.Config.DEFAULT,
                        LocalShadows.Config.DEFAULT,
                        Gtao.Config.DEFAULT,
                        256,
                        true,
                        1 << 16,
                        true,
                        true,
                        PostProcess.Config.DEFAULT);

        /** Checks that the post settings are present. */
        public Config {
            java.util.Objects.requireNonNull(post, "post");
        }

        /** Returns a copy with other sun shadow settings ({@code null} turns them off). */
        public Config withSunShadows(SunShadows.Config value) {
            return new Config(
                    ringBytesPerFrame,
                    maxMaterials,
                    value,
                    localShadows,
                    ambientOcclusion,
                    decalTextureSize,
                    outlines,
                    particleCapacity,
                    fogOfWar,
                    gpuCulling,
                    post);
        }

        /** Returns a copy with other local shadow settings ({@code null} turns them off). */
        public Config withLocalShadows(LocalShadows.Config value) {
            return new Config(
                    ringBytesPerFrame,
                    maxMaterials,
                    sunShadows,
                    value,
                    ambientOcclusion,
                    decalTextureSize,
                    outlines,
                    particleCapacity,
                    fogOfWar,
                    gpuCulling,
                    post);
        }

        /** Returns a copy with other ambient occlusion settings ({@code null} turns it off). */
        public Config withAmbientOcclusion(Gtao.Config value) {
            return new Config(
                    ringBytesPerFrame,
                    maxMaterials,
                    sunShadows,
                    localShadows,
                    value,
                    decalTextureSize,
                    outlines,
                    particleCapacity,
                    fogOfWar,
                    gpuCulling,
                    post);
        }

        /** Returns a copy with another decal texture size (0 turns decals off). */
        public Config withDecalTextureSize(int value) {
            return new Config(
                    ringBytesPerFrame,
                    maxMaterials,
                    sunShadows,
                    localShadows,
                    ambientOcclusion,
                    value,
                    outlines,
                    particleCapacity,
                    fogOfWar,
                    gpuCulling,
                    post);
        }

        /** Returns a copy with outlines on or off. */
        public Config withOutlines(boolean value) {
            return new Config(
                    ringBytesPerFrame,
                    maxMaterials,
                    sunShadows,
                    localShadows,
                    ambientOcclusion,
                    decalTextureSize,
                    value,
                    particleCapacity,
                    fogOfWar,
                    gpuCulling,
                    post);
        }

        /** Returns a copy with another particle pool size (0 turns particles off). */
        public Config withParticleCapacity(int value) {
            return new Config(
                    ringBytesPerFrame,
                    maxMaterials,
                    sunShadows,
                    localShadows,
                    ambientOcclusion,
                    decalTextureSize,
                    outlines,
                    value,
                    fogOfWar,
                    gpuCulling,
                    post);
        }

        /** Returns a copy with fog of war on or off. */
        public Config withFogOfWar(boolean value) {
            return new Config(
                    ringBytesPerFrame,
                    maxMaterials,
                    sunShadows,
                    localShadows,
                    ambientOcclusion,
                    decalTextureSize,
                    outlines,
                    particleCapacity,
                    value,
                    gpuCulling,
                    post);
        }

        /** Returns a copy with GPU-driven static instances on or off. */
        public Config withGpuCulling(boolean value) {
            return new Config(
                    ringBytesPerFrame,
                    maxMaterials,
                    sunShadows,
                    localShadows,
                    ambientOcclusion,
                    decalTextureSize,
                    outlines,
                    particleCapacity,
                    fogOfWar,
                    value,
                    post);
        }

        /** Returns a copy with other post-processing settings. */
        public Config withPost(PostProcess.Config value) {
            return new Config(
                    ringBytesPerFrame,
                    maxMaterials,
                    sunShadows,
                    localShadows,
                    ambientOcclusion,
                    decalTextureSize,
                    outlines,
                    particleCapacity,
                    fogOfWar,
                    gpuCulling,
                    value);
        }
    }

    private final GpuDevice device;
    private final ProgramCache programs;
    private final MeshRegistry meshes;
    private final MaterialSystem materials;
    private final Environment environment;
    private final LightClusters clusters;
    private final SunShadows sunShadows;
    private final LocalShadows localShadows;
    private final Gtao gtao;
    private final PostProcess post;
    private final DecalTextures decalTextures;
    private final Outlines outlines;
    private final GpuParticles particles;
    private final FogOfWar fogOfWar;
    private final StaticScene staticScene;
    private final FrameRing ring;
    private final Culling culling;
    private final DrawBuilder draws;
    private final FrameGraph graph;
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment matrix = arena.allocate(64, 16);
    private final float[] scratch = new float[16];
    private final int emptyVao;
    private final int skyProgram;
    private final int hdr;
    private final int depth;
    private final int clusterItems;
    private final int sunCascades;
    private final int shadowAtlas;
    private final int aoRaw;
    private final int ao;
    private int[] visible = new int[1024];
    private RenderPacket packet;
    private long frameOffset;
    private long frameNumber;
    private float lastTime = Float.NaN;
    private long prepareNanos;
    private int width;
    private int height;

    /** Creates the renderer; call {@link #resize} before the first {@link #render}. */
    public SceneRenderer(
            GpuDevice device,
            ProgramCache programs,
            MeshRegistry meshes,
            JobSystem jobs,
            Config config) {
        this.device = device;
        this.programs = programs;
        this.meshes = meshes;
        this.materials = new MaterialSystem(device, programs, config.maxMaterials());
        this.environment = new Environment(device, programs);
        this.clusters = new LightClusters(device, programs);
        this.ring = new FrameRing(device, config.ringBytesPerFrame(), 3, "scene ring");
        this.culling = new Culling(jobs);
        this.draws = new DrawBuilder(meshes, materials.table(), jobs);
        this.staticScene =
                config.gpuCulling()
                        ? new StaticScene(device, programs, meshes, materials.table())
                        : null;
        this.sunShadows =
                config.sunShadows() == null
                        ? null
                        : new SunShadows(device, meshes, materials, jobs, config.sunShadows());
        if (sunShadows != null) {
            materials.setGlobalFeatures(materials.globalFeatures() | MaterialSystem.SUN_SHADOWS);
            sunShadows.setStaticScene(staticScene);
        }
        this.localShadows =
                config.localShadows() == null
                        ? null
                        : new LocalShadows(device, meshes, materials, jobs, config.localShadows());
        if (localShadows != null) {
            materials.setGlobalFeatures(materials.globalFeatures() | MaterialSystem.LOCAL_SHADOWS);
        }
        MemorySegment id = arena.allocate(4);
        glCreateVertexArrays(1, id);
        emptyVao = id.get(ValueLayout.JAVA_INT, 0);
        gtao =
                config.ambientOcclusion() == null
                        ? null
                        : new Gtao(device, programs, config.ambientOcclusion(), emptyVao);
        if (gtao != null) {
            materials.setGlobalFeatures(materials.globalFeatures() | MaterialSystem.SCREEN_AO);
        }
        skyProgram =
                programs.define(
                        "sky", "engine/fullscreen.vert", "pbr/sky.frag", ShaderFeatures.NONE);
        post = new PostProcess(device, programs, config.post(), emptyVao);
        decalTextures =
                config.decalTextureSize() <= 0
                        ? null
                        : new DecalTextures(device, programs, config.decalTextureSize(), emptyVao);
        if (decalTextures != null) {
            materials.setGlobalFeatures(materials.globalFeatures() | MaterialSystem.DECALS);
        }
        outlines =
                config.outlines()
                        ? new Outlines(device, programs, meshes, materials, jobs, emptyVao)
                        : null;
        fogOfWar = config.fogOfWar() ? new FogOfWar(device) : null;
        if (fogOfWar != null) {
            materials.setGlobalFeatures(materials.globalFeatures() | MaterialSystem.FOG_OF_WAR);
        }
        particles =
                config.particleCapacity() <= 0
                        ? null
                        : new GpuParticles(device, programs, config.particleCapacity(), emptyVao);

        graph = new FrameGraph(device);
        hdr = graph.texture("hdr", GL_RGBA16F, 1f);
        depth = graph.texture("depth", GL_DEPTH_COMPONENT32F, 1f);
        clusterItems = graph.importedBuffer("cluster lights");
        sunCascades = graph.importedTexture("sun cascades");
        shadowAtlas = graph.importedTexture("shadow atlas");
        if (sunShadows != null) {
            graph.setTexture(sunCascades, device.textures().glName(sunShadows.texture()));
        }
        if (localShadows != null) {
            graph.setTexture(shadowAtlas, device.textures().glName(localShadows.texture()));
        }
        graph.addComputePass("light cull", this::lightCull).writes(clusterItems);
        if (staticScene != null) {
            int scene = graph.importedBuffer("static scene");
            graph.addComputePass("static cull", this::staticCull).writes(scene);
        }
        graph.addPass("sun shadows", this::sunShadowPass).writes(sunCascades);
        graph.addPass("local shadows", this::localShadowPass).writes(shadowAtlas);
        graph.addPass("depth prepass", this::depthPrepass).depth(depth, 0f);
        aoRaw = graph.texture("ao raw", GL_R8, 0.5f);
        ao = graph.texture("ao", GL_R8, 0.5f);
        if (gtao != null) {
            graph.addPass("ao", this::aoPass).readsTexture(depth).color(aoRaw);
            graph.addPass("ao blur", this::aoBlurPass)
                    .readsTexture(aoRaw)
                    .readsTexture(depth)
                    .color(ao);
        }
        FrameGraph.Pass opaque =
                graph.addPass("opaque", this::opaque)
                        .color(hdr, 0, 0, 0, 1)
                        .depthReadOnly(depth)
                        .readsStorage(clusterItems)
                        .readsTexture(sunCascades)
                        .readsTexture(shadowAtlas);
        if (gtao != null) {
            opaque.readsTexture(ao);
        }
        graph.addPass("sky", this::sky).color(hdr).depthReadOnly(depth);
        FrameGraph.Pass transparent =
                graph.addPass("transparent", this::transparent)
                        .color(hdr)
                        .depthReadOnly(depth)
                        .readsStorage(clusterItems)
                        .readsTexture(sunCascades)
                        .readsTexture(shadowAtlas);
        if (gtao != null) {
            transparent.readsTexture(ao);
        }
        if (particles != null) {
            particles.declare(graph, hdr, depth);
        }
        int overlay = outlines == null ? -1 : outlines.declare(graph, depth);
        post.declare(graph, hdr, overlay);
    }

    /** Returns the material system. */
    public MaterialSystem materials() {
        return materials;
    }

    /** Returns the image-based lighting environment. */
    public Environment environment() {
        return environment;
    }

    /** Returns the light clusters. */
    public LightClusters clusters() {
        return clusters;
    }

    /** Returns the sun cascades, or {@code null} when disabled. */
    public SunShadows sunShadows() {
        return sunShadows;
    }

    /** Returns the spot and point light shadows, or {@code null} when disabled. */
    public LocalShadows localShadows() {
        return localShadows;
    }

    /** Returns the ambient occlusion, or {@code null} when disabled. */
    public Gtao ambientOcclusion() {
        return gtao;
    }

    /** Returns the GL name of the blurred half-resolution AO target (0 when disabled). */
    public int aoTexture() {
        return gtao == null ? 0 : graph.glTexture(ao);
    }

    /** Returns the decal textures, or {@code null} when decals are off. */
    public DecalTextures decalTextures() {
        return decalTextures;
    }

    /** Returns the outlines, or {@code null} when off. */
    public Outlines outlines() {
        return outlines;
    }

    /** Returns the GPU particles, or {@code null} when off. */
    public GpuParticles particles() {
        return particles;
    }

    /** Returns the fog of war, or {@code null} when off. */
    public FogOfWar fogOfWar() {
        return fogOfWar;
    }

    /**
     * Returns the wall time the last {@link #render} spent preparing the frame on the CPU before
     * issuing GL work: culling, draw building, uploads (the part GPU-driven culling removes).
     */
    public long prepareNanos() {
        return prepareNanos;
    }

    /** Returns the GPU-driven static scene, or {@code null} when off. */
    public StaticScene staticScene() {
        return staticScene;
    }

    /** Returns the post-processing chain. */
    public PostProcess post() {
        return post;
    }

    /** Returns the frame graph (debug views, timings, extra passes). */
    public FrameGraph graph() {
        return graph;
    }

    /** Returns the main-view draw builder. */
    public DrawBuilder draws() {
        return draws;
    }

    /** Returns the GL name of the HDR scene color target (after {@link #resize}). */
    public int hdrTexture() {
        return graph.glTexture(hdr);
    }

    /** Sizes the targets for a {@code w}×{@code h} framebuffer. */
    public void resize(int w, int h) {
        width = w;
        height = h;
        clusters.resize(w, h);
        graph.compile(w, h);
    }

    /** Draws one frame of {@code p} into the window's framebuffer. */
    public void render(RenderPacket p) {
        if (width == 0) {
            throw new IllegalStateException("call resize first");
        }
        long start = System.nanoTime();
        packet = p;
        frameNumber++;
        ring.beginFrame();
        uploadPalettes(p);
        if (visible.length < p.count()) {
            visible = new int[Math.max(p.count(), visible.length * 2)];
        }
        if (staticScene != null) {
            staticScene.update(p, ring);
        }
        int n = cullMainView(p);
        float lod = lodPixels(p);
        draws.setLod(lod, p.orthographic, 1f);
        draws.build(p, visible, n, ring);
        if (outlines != null) {
            outlines.collect(p, ring, lod);
        }
        float elapsed = Float.isNaN(lastTime) ? 0f : Math.clamp(p.time - lastTime, 0f, 1f);
        lastTime = p.time;
        if (particles != null) {
            particles.collect(p, ring, elapsed);
        }
        if (sunShadows != null) {
            sunShadows.update(p, ring, lod);
        }
        materials.flush();
        if (fogOfWar != null) {
            fogOfWar.update(p);
        }
        clusters.upload(p, ring);
        if (localShadows != null) {
            localShadows.update(p, ring, clusters.lightsOffset(), lod);
        }
        frameOffset = writeFrameConstants(p);
        post.prepare(p.exposure, elapsed);
        StateCache state = device.state();
        state.bindUniform(FRAME_BINDING, ring.glName(), frameOffset, FRAME_BYTES);
        materials.invalidateBindings();
        prepareNanos = System.nanoTime() - start;
        graph.execute(frameNumber);
        ring.endFrame();
        packet = null;
    }

    /** Copies the packet's skinning palettes into the ring and binds them for every pass. */
    private void uploadPalettes(RenderPacket p) {
        int n = p.paletteCount;
        if (n == 0) {
            return;
        }
        long bytes = (long) n * RenderPacket.PALETTE_FLOATS * 4;
        long o = ring.allocate(bytes, device.caps().shaderStorageBufferOffsetAlignment());
        if (o < 0) {
            throw new IllegalStateException("scene ring is full; raise Config.ringBytesPerFrame");
        }
        MemorySegment.copy(
                p.palettes,
                0,
                ring.memory(),
                ValueLayout.JAVA_FLOAT,
                o,
                n * RenderPacket.PALETTE_FLOATS);
        device.state().bindStorage(PALETTE_BINDING, ring.glName(), o, bytes);
    }

    /**
     * Culls the main view on the CPU: everything, or with GPU culling the dynamic instances plus
     * the static ones the GPU path leaves to the CPU (blended materials).
     */
    private int cullMainView(RenderPacket p) {
        boolean gpu = staticScene != null && staticScene.active();
        int n =
                culling.cull(
                        p,
                        p.frustumPlanes,
                        0,
                        gpu ? RenderPacket.FLAG_STATIC : 0,
                        0,
                        0,
                        0,
                        -1,
                        visible);
        if (gpu) {
            int[] cpu = staticScene.cpuInstances();
            float[] b = p.bounds;
            for (int k = 0; k < staticScene.cpuCount(); k++) {
                int i = cpu[k];
                int o = i * 4;
                if (CullOps.sphereVisible(p.frustumPlanes, b[o], b[o + 1], b[o + 2], b[o + 3])) {
                    visible[n++] = i;
                }
            }
        }
        return n;
    }

    private float lodPixels(RenderPacket p) {
        // Pixels per world unit: orthographic from the projection's y scale; perspective at
        // distance 1.
        return p.projection[5] * height * 0.5f;
    }

    private long writeFrameConstants(RenderPacket p) {
        long o =
                ring.allocate(
                        FRAME_BYTES, Math.max(16, device.caps().uniformBufferOffsetAlignment()));
        if (o < 0) {
            throw new IllegalStateException("scene ring is full; raise Config.ringBytesPerFrame");
        }
        MemorySegment m = ring.memory();
        putMatrix(m, o, p.view);
        putMatrix(m, o + 64, p.projection);
        putMatrix(m, o + 128, p.viewProjection);
        putMatrix(m, o + 192, p.inverseViewProjection);
        Mat4Ops.invert(p.projection, 0, scratch, 0);
        putMatrix(m, o + 256, scratch);
        putVec4(m, o + 320, p.eye[0], p.eye[1], p.eye[2], p.time);
        putVec4(m, o + 336, width, height, 1f / width, 1f / height);
        float near = Math.max(1e-3f, p.near);
        float far = Math.max(near * 2, p.far);
        float logRatio = (float) Math.log(far / near);
        float scale = LightClusters.SLICES / logRatio;
        float bias = (float) (-LightClusters.SLICES * Math.log(near) / logRatio);
        putVec4(m, o + 352, near, far, scale, bias);
        m.set(ValueLayout.JAVA_INT, o + 368, clusters.dimX());
        m.set(ValueLayout.JAVA_INT, o + 372, clusters.dimY());
        m.set(ValueLayout.JAVA_INT, o + 376, LightClusters.SLICES);
        m.set(ValueLayout.JAVA_INT, o + 380, LightClusters.TILE);
        float[] d = p.sunDirection;
        float len = (float) Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        float inv = len > 0 ? 1 / len : 0;
        putVec4(m, o + 384, d[0] * inv, d[1] * inv, d[2] * inv, 0);
        float[] c = p.sunColor;
        boolean sun = c[0] > 0 || c[1] > 0 || c[2] > 0;
        putVec4(m, o + 400, c[0], c[1], c[2], sun ? 1 : 0);
        putVec4(
                m,
                o + 416,
                p.environmentSpecular,
                p.environmentDiffuse,
                environment.levels(),
                p.exposure);
        m.set(ValueLayout.JAVA_INT, o + 432, clusters.frameLightCount());
        m.set(ValueLayout.JAVA_INT, o + 436, clusters.frameDecalCount());
        m.set(ValueLayout.JAVA_INT, o + 440, (int) frameNumber);
        m.set(ValueLayout.JAVA_INT, o + 444, p.orthographic ? 1 : 0);
        int reveals = Math.min(4, p.revealCount);
        for (int i = 0; i < 4; i++) {
            if (i < reveals) {
                MemorySegment.copy(
                        p.reveal, i * 4, m, ValueLayout.JAVA_FLOAT, o + 448 + i * 16L, 4);
            } else {
                putVec4(m, o + 448 + i * 16L, 0, 0, 0, 1);
            }
        }
        boolean fog = fogOfWar != null && fogOfWar.active();
        putVec4(
                m,
                o + 512,
                p.fogMinX,
                p.fogMinZ,
                p.fogSizeX > 0 ? 1 / p.fogSizeX : 0,
                p.fogSizeZ > 0 ? 1 / p.fogSizeZ : 0);
        putVec4(m, o + 528, p.fogExploredBrightness, p.fogExploredSaturation, fog ? 1 : 0, reveals);
        return o;
    }

    private static void putMatrix(MemorySegment m, long o, float[] v) {
        MemorySegment.copy(v, 0, m, ValueLayout.JAVA_FLOAT, o, 16);
    }

    private static void putVec4(MemorySegment m, long o, float x, float y, float z, float w) {
        m.set(ValueLayout.JAVA_FLOAT, o, x);
        m.set(ValueLayout.JAVA_FLOAT, o + 4, y);
        m.set(ValueLayout.JAVA_FLOAT, o + 8, z);
        m.set(ValueLayout.JAVA_FLOAT, o + 12, w);
    }

    // Passes ---------------------------------------------------------------------------------

    private void lightCull(PassContext ctx) {
        StateCache state = ctx.state();
        state.bindUniform(FRAME_BINDING, ring.glName(), frameOffset, FRAME_BYTES);
        clusters.dispatch(state, packet, ring);
    }

    private void staticCull(PassContext ctx) {
        StateCache state = ctx.state();
        state.bindUniform(FRAME_BINDING, ring.glName(), frameOffset, FRAME_BYTES);
        staticScene.cull(
                state,
                StaticScene.VIEW_MAIN,
                packet.frustumPlanes,
                packet.eye,
                lodPixels(packet),
                packet.orthographic,
                0);
    }

    private void sunShadowPass(PassContext ctx) {
        if (sunShadows != null) {
            sunShadows.render(ctx.state(), ring);
        }
    }

    private void localShadowPass(PassContext ctx) {
        if (localShadows != null) {
            localShadows.render(ctx.state(), ring);
        }
    }

    private final MaterialSystem.DepthUniforms mainViewProjection =
            program -> {
                glProgramUniformMatrix4fv(program, 0, 1, false, matrix);
                glProgramUniform1i(program, 1, 1); // camera fade, like the lit pass
            };

    private void depthPrepass(PassContext ctx) {
        ctx.state().bindUniform(FRAME_BINDING, ring.glName(), frameOffset, FRAME_BYTES);
        MemorySegment.copy(packet.viewProjection, 0, matrix, ValueLayout.JAVA_FLOAT, 0, 16);
        materials.configureDepth(mainViewProjection, 0);
        draws.submit(ctx.state(), ring, materials.depthOnly);
        if (staticScene != null) {
            staticScene.submit(ctx.state(), StaticScene.VIEW_MAIN, materials.depthOnly);
        }
    }

    private void aoPass(PassContext ctx) {
        StateCache state = ctx.state();
        state.bindUniform(FRAME_BINDING, ring.glName(), frameOffset, FRAME_BYTES);
        gtao.occlusion(state, ctx.texture(depth));
    }

    private void aoBlurPass(PassContext ctx) {
        StateCache state = ctx.state();
        state.bindUniform(FRAME_BINDING, ring.glName(), frameOffset, FRAME_BYTES);
        gtao.blur(state, ctx.texture(aoRaw), ctx.texture(depth));
    }

    private void opaque(PassContext ctx) {
        bindLighting(ctx);
        draws.submit(ctx.state(), ring, materials.litOpaque);
        if (staticScene != null) {
            staticScene.submit(ctx.state(), StaticScene.VIEW_MAIN, materials.litOpaque);
        }
    }

    private void transparent(PassContext ctx) {
        bindLighting(ctx);
        draws.submit(ctx.state(), ring, materials.litTranslucent);
    }

    /** Binds what the lit passes read: frame constants, AO, decals, IBL, lights, shadows. */
    private void bindLighting(PassContext ctx) {
        StateCache state = ctx.state();
        state.bindUniform(FRAME_BINDING, ring.glName(), frameOffset, FRAME_BYTES);
        if (gtao != null) {
            gtao.bind(state, ctx.texture(ao));
        }
        if (decalTextures != null) {
            decalTextures.bind(state);
        }
        if (fogOfWar != null) {
            fogOfWar.bind(state);
        }
        environment.bind(state);
        clusters.bind(state, ring);
        if (sunShadows != null) {
            sunShadows.bind(state, ring);
        }
        if (localShadows != null) {
            localShadows.bind(state, ring);
        }
        materials.invalidateBindings();
    }

    private void sky(PassContext ctx) {
        int program = programs.get(skyProgram, 0);
        if (program == 0) {
            return;
        }
        StateCache state = ctx.state();
        state.apply(
                PipelineState.withDepth(
                        PipelineState.FULLSCREEN, true, false, PipelineState.DEPTH_GEQUAL));
        state.useProgram(program);
        state.bindUniform(FRAME_BINDING, ring.glName(), frameOffset, FRAME_BYTES);
        environment.bind(state);
        state.bindVertexArray(emptyVao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    @Override
    public void close() {
        graph.close();
        post.close();
        if (outlines != null) {
            outlines.close();
        }
        if (particles != null) {
            particles.close();
        }
        if (fogOfWar != null) {
            fogOfWar.close();
        }
        if (staticScene != null) {
            staticScene.close();
        }
        if (decalTextures != null) {
            decalTextures.close();
        }
        MemorySegment id = arena.allocate(4);
        id.set(ValueLayout.JAVA_INT, 0, emptyVao);
        glDeleteVertexArrays(1, id);
        ring.close();
        if (sunShadows != null) {
            sunShadows.close();
        }
        if (localShadows != null) {
            localShadows.close();
        }
        clusters.close();
        environment.close();
        materials.close();
        arena.close();
    }
}
