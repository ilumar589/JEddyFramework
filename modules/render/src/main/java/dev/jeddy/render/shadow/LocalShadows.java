package dev.jeddy.render.shadow;

import static dev.jeddy.gl.ffi.GL46.*;

import dev.jeddy.core.job.JobSystem;
import dev.jeddy.math.CullOps;
import dev.jeddy.math.Mat4Ops;
import dev.jeddy.render.RenderPacket;
import dev.jeddy.render.draw.Culling;
import dev.jeddy.render.draw.DrawBuilder;
import dev.jeddy.render.gpu.FrameRing;
import dev.jeddy.render.gpu.GpuDevice;
import dev.jeddy.render.gpu.PipelineState;
import dev.jeddy.render.gpu.SamplerDesc;
import dev.jeddy.render.gpu.StateCache;
import dev.jeddy.render.material.MaterialSystem;
import dev.jeddy.render.mesh.MeshRegistry;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Spot and point light shadows in a shadow atlas with cached static casters (TASKS M7-04, DESIGN
 * §9.3 pass 3). Lights opt in with {@link RenderPacket#setLightShadow} and a stable key; the
 * closest of them (up to {@link Config#slots()}) get a slot of six atlas tiles (a spot light uses
 * one, a point light one per cube face).
 *
 * <p>Each slot keeps its static casters' depth in a second atlas. While the light, its range and
 * cone and the packet's {@code staticVersion} stay the same, a frame only copies the cached tiles
 * into the live atlas and draws the dynamic casters on top; torches in a tavern cost a copy and the
 * few characters near them.
 *
 * <p>Receivers read {@code engine/local_shadows.glsl}: the light's shadow index points at view
 * records in storage buffer 8 (matrix, atlas rectangle, texel size), the live atlas is at unit 8.
 *
 * <p>Thread ownership: the render thread.
 */
public final class LocalShadows implements AutoCloseable {
    /** Texture unit of the atlas. */
    public static final int UNIT = 8;

    /** Storage binding of the view records. */
    public static final int BINDING = 8;

    /** Bytes per view record (mat4, rect, params). */
    public static final int VIEW_BYTES = 96;

    private static final int FACES = 6;

    /**
     * Settings.
     *
     * @param atlasSize atlas side in texels
     * @param tileSize tile side in texels
     * @param slots shadowed lights per frame (each reserves six tiles)
     * @param near near plane of the light views
     */
    public record Config(int atlasSize, int tileSize, int slots, float near) {
        /** A 2048² atlas of 256² tiles, 10 lights. */
        public static final Config DEFAULT = new Config(2048, 256, 10, 0.05f);
    }

    private final GpuDevice device;
    private final MaterialSystem materials;
    private final Config config;
    private final int live;
    private final int cache;
    private final int liveFbo;
    private final int cacheFbo;
    private final int sampler;
    private final Culling culling;
    private final DrawBuilder[] staticDraws;
    private final DrawBuilder[] dynamicDraws;
    private final float[][] matrices;
    private final float[] tanHalf;
    private final float[] planes = new float[CullOps.PLANES_FLOATS];
    private final float[] view = new float[16];
    private final float[] projection = new float[16];
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment matrix = arena.allocate(64, 16);
    private final MemorySegment clearDepth = arena.allocate(16, 16);
    private final MaterialSystem.DepthUniforms uniforms =
            program -> {
                glProgramUniformMatrix4fv(program, 0, 1, false, matrix);
                glProgramUniform1i(program, 1, 0); // no camera fade in shadow maps
            };

    // Slots.
    private final int[] slotKey;
    private final long[] slotHash;
    private final int[] slotStaticVersion;
    private final boolean[] slotValid;
    private final long[] slotUsed;
    private final int[] slotLight; // light index this frame, -1 if unused
    private final boolean[] slotPoint;
    private final boolean[] slotRefresh;
    // Selection scratch.
    private final int[] candidates;
    private final float[] candidateScore;
    private int[] visible = new int[1024];
    private long frame;
    private long viewsOffset = -1;
    private int staticRenders;

    /** Creates the atlases. */
    public LocalShadows(
            GpuDevice device,
            MeshRegistry meshes,
            MaterialSystem materials,
            JobSystem jobs,
            Config config) {
        int tilesPerSide = config.atlasSize() / config.tileSize();
        if (tilesPerSide * tilesPerSide < config.slots() * FACES) {
            throw new IllegalArgumentException("the atlas has fewer tiles than slots × 6");
        }
        this.device = device;
        this.materials = materials;
        this.config = config;
        live =
                device.textures()
                        .create2D(
                                GL_DEPTH_COMPONENT32F,
                                config.atlasSize(),
                                config.atlasSize(),
                                1,
                                "shadow atlas");
        cache =
                device.textures()
                        .create2D(
                                GL_DEPTH_COMPONENT32F,
                                config.atlasSize(),
                                config.atlasSize(),
                                1,
                                "shadow atlas static cache");
        liveFbo = device.framebuffers().createDepthLayer(live, -1, "shadow atlas");
        cacheFbo = device.framebuffers().createDepthLayer(cache, -1, "shadow atlas static cache");
        sampler = device.samplers().get(SamplerDesc.SHADOW);
        culling = new Culling(jobs);
        int views = config.slots() * FACES;
        staticDraws = new DrawBuilder[views];
        dynamicDraws = new DrawBuilder[views];
        matrices = new float[views][16];
        tanHalf = new float[views];
        for (int v = 0; v < views; v++) {
            staticDraws[v] = new DrawBuilder(meshes, materials.table(), null);
            dynamicDraws[v] = new DrawBuilder(meshes, materials.table(), null);
        }
        int s = config.slots();
        slotKey = new int[s];
        slotHash = new long[s];
        slotStaticVersion = new int[s];
        slotValid = new boolean[s];
        slotUsed = new long[s];
        slotLight = new int[s];
        slotPoint = new boolean[s];
        slotRefresh = new boolean[s];
        java.util.Arrays.fill(slotKey, -1);
        candidates = new int[s];
        candidateScore = new float[s];
    }

    /** Returns the live atlas texture handle. */
    public int texture() {
        return live;
    }

    /** Returns how many times static casters were (re)rendered, for tests and stats. */
    public int staticRenders() {
        return staticRenders;
    }

    /**
     * Picks this frame's shadowed lights, refreshes slots whose static content is stale, culls and
     * builds their draws, writes the view records into the ring and patches the lights' shadow
     * indices in the uploaded light array at {@code lightsOffset}.
     */
    public void update(RenderPacket p, FrameRing ring, long lightsOffset, float lodPixels) {
        frame++;
        int n = select(p);
        java.util.Arrays.fill(slotLight, -1);
        for (int c = 0; c < n; c++) {
            int light = candidates[c];
            int key = Float.floatToRawIntBits(p.lights[light * RenderPacket.LIGHT_FLOATS + 14]);
            int slot = slotFor(key);
            slotLight[slot] = light;
            slotUsed[slot] = frame;
        }
        viewsOffset =
                ring.allocate(
                        (long) config.slots() * FACES * VIEW_BYTES,
                        device.caps().shaderStorageBufferOffsetAlignment());
        if (viewsOffset < 0) {
            return;
        }
        MemorySegment mem = ring.memory();
        if (visible.length < p.count()) {
            visible = new int[Math.max(p.count(), visible.length * 2)];
        }
        for (int s = 0; s < config.slots(); s++) {
            int light = slotLight[s];
            slotRefresh[s] = false;
            if (light < 0) {
                continue;
            }
            int o = light * RenderPacket.LIGHT_FLOATS;
            float[] l = p.lights;
            boolean point = Float.floatToRawIntBits(l[o + 7]) == RenderPacket.LIGHT_POINT;
            long hash = hash(l, o);
            if (!slotValid[s]
                    || slotHash[s] != hash
                    || slotStaticVersion[s] != p.staticVersion
                    || slotPoint[s] != point) {
                slotRefresh[s] = true;
                slotValid[s] = true;
                slotHash[s] = hash;
                slotStaticVersion[s] = p.staticVersion;
                slotPoint[s] = point;
            }
            int faces = point ? FACES : 1;
            float range = l[o + 3] > 0 ? l[o + 3] : 1000f;
            for (int f = 0; f < faces; f++) {
                int v = s * FACES + f;
                lightView(l, o, point, f, v);
                CullOps.extractPlanes(matrices[v], 0, planes);
                if (slotRefresh[s]) {
                    int count =
                            culling.cull(
                                    p,
                                    planes,
                                    RenderPacket.FLAG_CASTS_SHADOW | RenderPacket.FLAG_STATIC,
                                    0,
                                    l[o],
                                    l[o + 1],
                                    l[o + 2],
                                    range,
                                    visible);
                    staticDraws[v].setLod(lodPixels, p.orthographic, 1f);
                    staticDraws[v].build(p, visible, count, ring);
                }
                int count =
                        culling.cull(
                                p,
                                planes,
                                RenderPacket.FLAG_CASTS_SHADOW,
                                RenderPacket.FLAG_STATIC,
                                l[o],
                                l[o + 1],
                                l[o + 2],
                                range,
                                visible);
                dynamicDraws[v].setLod(lodPixels, p.orthographic, 1f);
                dynamicDraws[v].build(p, visible, count, ring);
                writeView(mem, viewsOffset + (long) v * VIEW_BYTES, v, point);
            }
            // The receiver finds its records through the light's shadow index.
            mem.set(ValueLayout.JAVA_INT, lightsOffset + (long) o * 4 + 13 * 4, s * FACES);
        }
    }

    /** Chooses up to {@code slots} shadowed lights, closest to the camera first. */
    private int select(RenderPacket p) {
        int n = 0;
        int max = config.slots();
        for (int i = 0; i < p.lightCount; i++) {
            int o = i * RenderPacket.LIGHT_FLOATS;
            if (Float.floatToRawIntBits(p.lights[o + 14]) < 0) {
                continue;
            }
            float dx = p.lights[o] - p.eye[0];
            float dy = p.lights[o + 1] - p.eye[1];
            float dz = p.lights[o + 2] - p.eye[2];
            float score = dx * dx + dy * dy + dz * dz;
            if (n < max) {
                int k = n++;
                while (k > 0 && candidateScore[k - 1] > score) {
                    candidates[k] = candidates[k - 1];
                    candidateScore[k] = candidateScore[k - 1];
                    k--;
                }
                candidates[k] = i;
                candidateScore[k] = score;
            } else if (score < candidateScore[n - 1]) {
                int k = n - 1;
                while (k > 0 && candidateScore[k - 1] > score) {
                    candidates[k] = candidates[k - 1];
                    candidateScore[k] = candidateScore[k - 1];
                    k--;
                }
                candidates[k] = i;
                candidateScore[k] = score;
            }
        }
        return n;
    }

    /** The slot holding {@code key}, or the least recently used one (which loses its cache). */
    private int slotFor(int key) {
        int oldest = 0;
        for (int s = 0; s < slotKey.length; s++) {
            if (slotKey[s] == key) {
                return s;
            }
            if (slotUsed[s] < slotUsed[oldest]) {
                oldest = s;
            }
        }
        slotKey[oldest] = key;
        slotValid[oldest] = false;
        return oldest;
    }

    private static long hash(float[] l, int o) {
        long h = 1125899906842597L;
        for (int k = 0; k < 13; k++) {
            if (k >= 4 && k <= 7) {
                continue; // color and type do not change the depth
            }
            h = 31 * h + Float.floatToRawIntBits(l[o + k]);
        }
        return h;
    }

    /** Builds the view-projection of view {@code v}: a spot cone or one cube face. */
    private void lightView(float[] l, int o, boolean point, int face, int v) {
        float px = l[o];
        float py = l[o + 1];
        float pz = l[o + 2];
        float dx;
        float dy;
        float dz;
        float ux = 0;
        float uy = 1;
        float uz = 0;
        float fov;
        if (point) {
            dx = face == 0 ? 1 : face == 1 ? -1 : 0;
            dy = face == 2 ? 1 : face == 3 ? -1 : 0;
            dz = face == 4 ? 1 : face == 5 ? -1 : 0;
            if (face == 2 || face == 3) {
                uy = 0;
                uz = 1;
            }
            fov = (float) (Math.PI / 2);
        } else {
            dx = l[o + 8];
            dy = l[o + 9];
            dz = l[o + 10];
            if (Math.abs(dy) > 0.99f) {
                uy = 0;
                uz = 1;
            }
            float outer = (float) Math.acos(Math.max(-1, Math.min(1, l[o + 11])));
            fov = Math.min((float) Math.toRadians(170), 2 * outer + (float) Math.toRadians(4));
        }
        Mat4Ops.lookAt(px, py, pz, px + dx, py + dy, pz + dz, ux, uy, uz, view, 0);
        Mat4Ops.perspectiveReversedZ(fov, 1, config.near(), projection, 0);
        Mat4Ops.mul(projection, 0, view, 0, matrices[v], 0);
        // World size of one texel at 1 m, for the receiver's normal offset.
        tanHalf[v] = (float) Math.tan(fov / 2) * 2 / config.tileSize();
    }

    private void writeView(MemorySegment mem, long at, int v, boolean point) {
        MemorySegment.copy(matrices[v], 0, mem, ValueLayout.JAVA_FLOAT, at, 16);
        int tiles = config.atlasSize() / config.tileSize();
        int tile = v; // slot s owns tiles s*6 .. s*6+5
        float scale = (float) config.tileSize() / config.atlasSize();
        mem.set(ValueLayout.JAVA_FLOAT, at + 64, (tile % tiles) * scale);
        mem.set(ValueLayout.JAVA_FLOAT, at + 68, (tile / tiles) * scale);
        mem.set(ValueLayout.JAVA_FLOAT, at + 72, scale);
        mem.set(ValueLayout.JAVA_FLOAT, at + 76, scale);
        mem.set(ValueLayout.JAVA_FLOAT, at + 80, tanHalf[v]);
        mem.set(ValueLayout.JAVA_FLOAT, at + 84, 1f / config.atlasSize());
        mem.set(ValueLayout.JAVA_FLOAT, at + 88, point ? 1 : 0);
        mem.set(ValueLayout.JAVA_FLOAT, at + 92, 0);
    }

    /** Renders stale static tiles into the cache, copies cached tiles and draws dynamic casters. */
    public void render(StateCache state, FrameRing ring) {
        if (viewsOffset < 0) {
            return;
        }
        glPolygonOffset(-1.5f, -2f);
        long pipeline =
                PipelineState.withScissor(
                        PipelineState.withPolygonOffset(PipelineState.DEPTH_ONLY, true), true);
        clearDepth.set(ValueLayout.JAVA_FLOAT, 0, 0f);
        int tiles = config.atlasSize() / config.tileSize();
        int size = config.tileSize();
        int cacheName = device.framebuffers().glName(cacheFbo);
        int liveName = device.framebuffers().glName(liveFbo);
        for (int s = 0; s < config.slots(); s++) {
            if (slotLight[s] < 0) {
                continue;
            }
            int faces = slotPoint[s] ? FACES : 1;
            for (int f = 0; f < faces; f++) {
                int v = s * FACES + f;
                int x = (v % tiles) * size;
                int y = (v / tiles) * size;
                MemorySegment.copy(matrices[v], 0, matrix, ValueLayout.JAVA_FLOAT, 0, 16);
                if (slotRefresh[s]) {
                    drawTile(state, cacheName, x, y, size, staticDraws[v], ring, pipeline);
                    staticRenders++;
                }
                glCopyImageSubData(
                        device.textures().glName(cache),
                        GL_TEXTURE_2D,
                        0,
                        x,
                        y,
                        0,
                        device.textures().glName(live),
                        GL_TEXTURE_2D,
                        0,
                        x,
                        y,
                        0,
                        size,
                        size,
                        1);
                drawTile(state, liveName, x, y, size, dynamicDraws[v], ring, pipeline);
            }
        }
        materials.configureDepth(null, 0);
    }

    private void drawTile(
            StateCache state,
            int fbo,
            int x,
            int y,
            int size,
            DrawBuilder draws,
            FrameRing ring,
            long pipeline) {
        state.bindFramebuffer(fbo);
        state.viewport(x, y, size, size);
        glScissor(x, y, size, size);
        if (fbo == device.framebuffers().glName(cacheFbo)) {
            state.apply(
                    PipelineState.withScissor(
                            PipelineState.withDepthWrite(state.state(), true), true));
            glClearNamedFramebufferfv(fbo, GL_DEPTH, 0, clearDepth); // scissored to the tile
        }
        materials.configureDepth(uniforms, pipeline);
        draws.submit(state, ring, materials.depthOnly);
    }

    /** Binds the atlas and the view records for receivers. */
    public void bind(StateCache state, FrameRing ring) {
        state.bindTexture(UNIT, device.textures().glName(live));
        state.bindSampler(UNIT, sampler);
        if (viewsOffset >= 0) {
            state.bindStorage(
                    BINDING,
                    ring.glName(),
                    viewsOffset,
                    (long) config.slots() * FACES * VIEW_BYTES);
        }
    }

    @Override
    public void close() {
        device.framebuffers().destroy(liveFbo);
        device.framebuffers().destroy(cacheFbo);
        device.textures().destroy(live);
        device.textures().destroy(cache);
        arena.close();
    }
}
