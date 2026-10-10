package dev.jeddy.render.shadow;

import static dev.jeddy.gl.ffi.GL46.*;

import dev.jeddy.core.handle.Handles;
import dev.jeddy.core.job.JobSystem;
import dev.jeddy.math.CullOps;
import dev.jeddy.math.Mat4Ops;
import dev.jeddy.render.RenderPacket;
import dev.jeddy.render.draw.Culling;
import dev.jeddy.render.draw.DrawBuilder;
import dev.jeddy.render.draw.StaticScene;
import dev.jeddy.render.gpu.FrameRing;
import dev.jeddy.render.gpu.GpuDevice;
import dev.jeddy.render.gpu.GpuTextures;
import dev.jeddy.render.gpu.PipelineState;
import dev.jeddy.render.gpu.SamplerDesc;
import dev.jeddy.render.gpu.StateCache;
import dev.jeddy.render.material.MaterialSystem;
import dev.jeddy.render.mesh.MeshRegistry;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Cascaded sun shadows (TASKS M7-04, DESIGN §9.3 pass 3): the view range up to the shadow distance
 * is split into 2-4 cascades (practical split scheme), each covered by an orthographic light view
 * fitted to a bounding sphere of its slice of the camera frustum. The sphere's radius only depends
 * on the camera's projection and the split depths, and the light-space center snaps to whole
 * texels, so shadow edges stay still while the camera pans (stable cascades). Casters behind a
 * cascade's near plane are clamped onto it ({@code GL_DEPTH_CLAMP}), so tall objects off screen
 * still cast.
 *
 * <p>The receivers read {@code engine/sun_shadows.glsl}: cascade matrices and split depths in
 * uniform block 1, the depth array at unit 7 with hardware comparison and a 3×3 PCF kernel.
 *
 * <p>Thread ownership: the render thread.
 */
public final class SunShadows implements AutoCloseable {
    /** Texture unit of the cascade array. */
    public static final int UNIT = 7;

    /** Uniform binding of the cascade data. */
    public static final int BINDING = 1;

    /** Bytes of the uniform block. */
    public static final int BLOCK_BYTES = 4 * 64 + 3 * 16;

    /**
     * Settings.
     *
     * @param cascades 1 to 4
     * @param size texels per side of each cascade
     * @param distance how far from the camera shadows reach (meters)
     * @param lambda split scheme: 0 uniform, 1 logarithmic
     * @param pullback extra distance towards the light that still casts into a cascade
     * @param normalOffset receiver offset along the normal, in texels
     */
    public record Config(
            int cascades,
            int size,
            float distance,
            float lambda,
            float pullback,
            float normalOffset) {
        /** Three 2048² cascades over 60 m. */
        public static final Config DEFAULT = new Config(3, 2048, 60f, 0.75f, 60f, 1.5f);
    }

    private final GpuDevice device;
    private final MaterialSystem materials;
    private final Config config;
    private final int texture;
    private final int[] framebuffers;
    private final DrawBuilder[] builders;
    private final Culling culling;
    private final int sampler;
    private final float[][] matrices;
    private final float[][] planes;
    private final float[] splits = new float[4];
    private final float[] texelWorld = new float[4];
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment matrix = arena.allocate(64, 16);
    private final MemorySegment clearDepth = arena.allocate(16, 16);
    private final float[] scratch = new float[16];
    private final float[] inverseView = new float[16];
    private final float[] inverseProjection = new float[16];
    private final float[] lightRotation = new float[16];
    private final float[] lightProjection = new float[16];
    private final float[] corners = new float[24];
    private final float[] point = new float[3];
    private int[] visible = new int[1024];
    private int cascadeCount;
    private StaticScene staticScene;
    private final float[] eye = new float[3];
    private float lod;
    private boolean orthographic;
    private long blockOffset = -1;
    private final MaterialSystem.DepthUniforms uniforms =
            program -> {
                glProgramUniformMatrix4fv(program, 0, 1, false, matrix);
                glProgramUniform1i(program, 1, 0); // no camera fade in shadow maps
            };

    /** Creates the cascade array and its framebuffers. */
    public SunShadows(
            GpuDevice device,
            MeshRegistry meshes,
            MaterialSystem materials,
            JobSystem jobs,
            Config config) {
        if (config.cascades() < 1 || config.cascades() > 4) {
            throw new IllegalArgumentException("1 to 4 cascades");
        }
        this.device = device;
        this.materials = materials;
        this.config = config;
        texture =
                device.textures()
                        .create(
                                GpuTextures.KIND_2D_ARRAY,
                                GL_DEPTH_COMPONENT32F,
                                config.size(),
                                config.size(),
                                config.cascades(),
                                1,
                                "sun shadow cascades");
        framebuffers = new int[config.cascades()];
        builders = new DrawBuilder[config.cascades()];
        matrices = new float[config.cascades()][16];
        planes = new float[config.cascades()][CullOps.PLANES_FLOATS];
        for (int c = 0; c < config.cascades(); c++) {
            framebuffers[c] =
                    device.framebuffers().createDepthLayer(texture, c, "sun cascade " + c);
            builders[c] = new DrawBuilder(meshes, materials.table(), jobs);
        }
        culling = new Culling(jobs);
        sampler = device.samplers().get(SamplerDesc.SHADOW);
    }

    /** Returns the cascade array texture handle. */
    public int texture() {
        return texture;
    }

    /** Returns the world→shadow matrix of cascade {@code c} (after {@link #update}). */
    public float[] matrix(int c) {
        return matrices[c];
    }

    /** Returns the far view depth of cascade {@code c}. */
    public float split(int c) {
        return splits[c];
    }

    /** Returns the number of cascades in use this frame (0 when the sun is off). */
    public int cascadeCount() {
        return cascadeCount;
    }

    /**
     * Culls static casters on the GPU from {@code scene} (when it is active) instead of on the CPU.
     */
    public void setStaticScene(StaticScene scene) {
        staticScene = scene;
    }

    /**
     * Fits the cascades to the packet's camera and sun, culls casters for each and builds their
     * draws in {@code ring}, and writes the uniform block. Main-view LOD settings are copied from
     * {@code lodSource} so shadows match what is drawn.
     */
    public void update(RenderPacket p, FrameRing ring, float lodPixels) {
        cascadeCount = 0;
        float[] sun = p.sunColor;
        if (!p.sunShadows || (sun[0] <= 0 && sun[1] <= 0 && sun[2] <= 0)) {
            writeBlock(ring);
            return;
        }
        float[] d = p.sunDirection;
        float len = (float) Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        if (len == 0) {
            writeBlock(ring);
            return;
        }
        float lx = d[0] / len;
        float ly = d[1] / len;
        float lz = d[2] / len;
        // Rotation-only light view: looking along the light direction from the origin.
        boolean nearlyVertical = Math.abs(ly) > 0.99f;
        Mat4Ops.lookAt(
                0,
                0,
                0,
                lx,
                ly,
                lz,
                nearlyVertical ? 1 : 0,
                nearlyVertical ? 0 : 1,
                0,
                lightRotation,
                0);
        Mat4Ops.invert(p.view, 0, inverseView, 0);
        Mat4Ops.invert(p.projection, 0, inverseProjection, 0);
        int n = config.cascades();
        float near = Math.max(1e-3f, p.near);
        float far = Math.max(near * 2, Math.min(p.far, config.distance()));
        float previous = near;
        for (int c = 0; c < n; c++) {
            float t = (float) (c + 1) / n;
            float log = (float) (near * Math.pow(far / near, t));
            float uni = near + (far - near) * t;
            float split = config.lambda() * log + (1 - config.lambda()) * uni;
            fitCascade(c, previous, split, p);
            splits[c] = split;
            previous = split;
        }
        cascadeCount = n;
        if (visible.length < p.count()) {
            visible = new int[Math.max(p.count(), visible.length * 2)];
        }
        boolean gpu = staticScene != null && staticScene.active();
        System.arraycopy(p.eye, 0, eye, 0, 3);
        lod = lodPixels;
        orthographic = p.orthographic;
        for (int c = 0; c < n; c++) {
            int count =
                    culling.cull(
                            p,
                            planes[c],
                            RenderPacket.FLAG_CASTS_SHADOW,
                            gpu ? RenderPacket.FLAG_STATIC : 0,
                            0,
                            0,
                            0,
                            -1,
                            visible);
            if (gpu) {
                count = appendCpuStatic(p, planes[c], count);
            }
            builders[c].setLod(lodPixels, p.orthographic, 1f);
            builders[c].build(p, visible, count, ring);
        }
        writeBlock(ring);
    }

    /** Adds the static scene's CPU-path casters inside {@code frustum} to {@link #visible}. */
    private int appendCpuStatic(RenderPacket p, float[] frustum, int count) {
        int[] cpu = staticScene.cpuInstances();
        for (int k = 0; k < staticScene.cpuCount(); k++) {
            int i = cpu[k];
            int o = i * 4;
            if ((p.flags[i] & RenderPacket.FLAG_CASTS_SHADOW) != 0
                    && CullOps.sphereVisible(
                            frustum,
                            p.bounds[o],
                            p.bounds[o + 1],
                            p.bounds[o + 2],
                            p.bounds[o + 3])) {
                visible[count++] = i;
            }
        }
        return count;
    }

    /** Fits cascade {@code c} to the camera frustum slice between view depths a and b. */
    private void fitCascade(int c, float a, float b, RenderPacket p) {
        // World-space corners of the slice.
        for (int k = 0; k < 8; k++) {
            float nx = (k & 1) == 0 ? -1 : 1;
            float ny = (k & 2) == 0 ? -1 : 1;
            viewPointAtDepth(nx, ny, (k & 4) == 0 ? a : b);
            Mat4Ops.project(inverseView, 0, point[0], point[1], point[2], corners, k * 3);
        }
        float cx = 0;
        float cy = 0;
        float cz = 0;
        for (int k = 0; k < 8; k++) {
            cx += corners[k * 3];
            cy += corners[k * 3 + 1];
            cz += corners[k * 3 + 2];
        }
        cx /= 8;
        cy /= 8;
        cz /= 8;
        float r = 0;
        for (int k = 0; k < 8; k++) {
            float dx = corners[k * 3] - cx;
            float dy = corners[k * 3 + 1] - cy;
            float dz = corners[k * 3 + 2] - cz;
            r = Math.max(r, (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        r = (float) Math.ceil(r * 16) / 16; // quantized so it does not flicker with rounding
        // Center in light space, snapped to whole texels.
        Mat4Ops.project(lightRotation, 0, cx, cy, cz, point, 0);
        float texel = 2 * r / config.size();
        float sx = (float) Math.floor(point[0] / texel) * texel;
        float sy = (float) Math.floor(point[1] / texel) * texel;
        float distance = -point[2];
        Mat4Ops.orthoReversedZ(
                sx - r,
                sx + r,
                sy - r,
                sy + r,
                distance - r - config.pullback(),
                distance + r,
                lightProjection,
                0);
        Mat4Ops.mul(lightProjection, 0, lightRotation, 0, matrices[c], 0);
        CullOps.extractPlanes(matrices[c], 0, planes[c]);
        texelWorld[c] = texel;
    }

    /** View-space point on the camera ray through NDC (x, y) at view depth z. */
    private void viewPointAtDepth(float x, float y, float z) {
        Mat4Ops.project(inverseProjection, 0, x, y, 1f, scratch, 0);
        float ax = scratch[0];
        float ay = scratch[1];
        float az = scratch[2];
        Mat4Ops.project(inverseProjection, 0, x, y, 0.5f, scratch, 3);
        float t = (-z - az) / (scratch[5] - az);
        point[0] = ax + t * (scratch[3] - ax);
        point[1] = ay + t * (scratch[4] - ay);
        point[2] = -z;
    }

    private void writeBlock(FrameRing ring) {
        blockOffset =
                ring.allocate(
                        BLOCK_BYTES, Math.max(16, device.caps().uniformBufferOffsetAlignment()));
        if (blockOffset < 0) {
            return;
        }
        MemorySegment m = ring.memory();
        for (int c = 0; c < 4; c++) {
            float[] src = c < cascadeCount ? matrices[c] : matrices[0];
            MemorySegment.copy(src, 0, m, ValueLayout.JAVA_FLOAT, blockOffset + c * 64L, 16);
        }
        long o = blockOffset + 256;
        for (int c = 0; c < 4; c++) {
            m.set(ValueLayout.JAVA_FLOAT, o + c * 4L, c < cascadeCount ? splits[c] : 0f);
            m.set(ValueLayout.JAVA_FLOAT, o + 16 + c * 4L, c < cascadeCount ? texelWorld[c] : 0f);
        }
        m.set(ValueLayout.JAVA_FLOAT, o + 32, config.normalOffset());
        m.set(ValueLayout.JAVA_FLOAT, o + 36, config.size());
        m.set(ValueLayout.JAVA_FLOAT, o + 40, 0.9f);
        m.set(ValueLayout.JAVA_FLOAT, o + 44, cascadeCount);
    }

    /** Renders the cascades' casters. */
    public void render(StateCache state, FrameRing ring) {
        if (cascadeCount == 0) {
            return;
        }
        glEnable(GL_DEPTH_CLAMP);
        glPolygonOffset(-1.5f, -2f);
        long pipeline = PipelineState.withPolygonOffset(PipelineState.DEPTH_ONLY, true);
        clearDepth.set(ValueLayout.JAVA_FLOAT, 0, 0f); // the far value in reversed-Z
        for (int c = 0; c < cascadeCount; c++) {
            int fbo = device.framebuffers().glName(framebuffers[c]);
            state.bindFramebuffer(fbo);
            state.viewport(0, 0, config.size(), config.size());
            state.apply(PipelineState.withDepthWrite(state.state(), true));
            glClearNamedFramebufferfv(fbo, GL_DEPTH, 0, clearDepth);
            MemorySegment.copy(matrices[c], 0, matrix, ValueLayout.JAVA_FLOAT, 0, 16);
            materials.configureDepth(uniforms, pipeline);
            builders[c].submit(state, ring, materials.depthOnly);
            if (staticScene != null) {
                staticScene.cull(
                        state,
                        StaticScene.VIEW_CASCADES + c,
                        planes[c],
                        eye,
                        lod,
                        orthographic,
                        RenderPacket.FLAG_CASTS_SHADOW);
                staticScene.submit(state, StaticScene.VIEW_CASCADES + c, materials.depthOnly);
            }
        }
        materials.configureDepth(null, 0);
        glDisable(GL_DEPTH_CLAMP);
    }

    /** Binds the cascade array and the uniform block for receivers. */
    public void bind(StateCache state, FrameRing ring) {
        state.bindTexture(UNIT, device.textures().glName(texture));
        state.bindSampler(UNIT, sampler);
        if (blockOffset >= 0) {
            state.bindUniform(BINDING, ring.glName(), blockOffset, BLOCK_BYTES);
        }
    }

    @Override
    public void close() {
        for (int f : framebuffers) {
            if (f != Handles.NULL) {
                device.framebuffers().destroy(f);
            }
        }
        device.textures().destroy(texture);
        arena.close();
    }
}
