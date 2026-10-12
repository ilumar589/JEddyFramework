package dev.jeddy.render.pbr;

import static dev.jeddy.gl.ffi.GL46.*;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.format.MaterialData;
import dev.jeddy.asset.shader.ShaderSources;
import dev.jeddy.core.job.JobSystem;
import dev.jeddy.math.AffineOps;
import dev.jeddy.math.CullOps;
import dev.jeddy.math.Mat4Ops;
import dev.jeddy.render.GpuTestContext;
import dev.jeddy.render.RenderPacket;
import dev.jeddy.render.SceneRenderer;
import dev.jeddy.render.TestMeshes;
import dev.jeddy.render.gpu.GpuDevice;
import dev.jeddy.render.gpu.UploadRing;
import dev.jeddy.render.material.MaterialSystem;
import dev.jeddy.render.material.TextureArrays;
import dev.jeddy.render.mesh.MeshRegistry;
import dev.jeddy.render.post.PostProcess;
import dev.jeddy.render.shader.ProgramCache;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * A driver bisect: the same sun-lit white sphere rendered through the default renderer
 * configuration and through each configuration with one feature turned off, with a factor-only
 * material and with a textured one. Every cell must come out lit. When a driver fails, the failure
 * message is the whole table, which says which feature (or shader variant) the driver mishandles;
 * the first Intel Windows driver we met rendered factor-only materials black under the default
 * configuration while textured ones were fine (running.md, "Drivers seen so far").
 */
@Tag("gpu")
class FeatureBisectTest {
    private static final int SIZE = 64;
    private static final Arena ARENA = Arena.ofShared();
    private static GpuTestContext context;
    private static GpuDevice device;
    private static JobSystem jobs;
    private static UploadRing upload;
    private static MeshRegistry meshes;
    private static ProgramCache programs;
    private static int sphere;
    private static int skinnedSphere;
    private static MemorySegment pixels;

    private static final PostProcess.Config PLAIN_POST =
            PostProcess.Config.DEFAULT.withBloom(0, 0).withAutoExposure(null).withFxaa(false);

    @BeforeAll
    static void init() {
        context = new GpuTestContext("feature bisect", SIZE);
        device = new GpuDevice(context.caps());
        device.useReversedZ();
        jobs = new JobSystem(2);
        upload = new UploadRing(device, 4 << 20, 4 << 20);
        meshes = new MeshRegistry(device, upload, 1 << 16, 1 << 16);
        programs =
                new ProgramCache(
                        ShaderSources.classpath(MeshRegistry.class, "/dev/jeddy/render/shaders"),
                        context.caps(),
                        null);
        upload.beginFrame();
        sphere = meshes.add(TestMeshes.sphere(ARENA, 0.4f, 32, 16));
        skinnedSphere = meshes.add(TestMeshes.skinnedSphere(ARENA, 0.4f, 32, 16));
        upload.endFrame();
        pixels = ARENA.allocate((long) SIZE * SIZE * 16, 16);
    }

    @AfterAll
    static void shutdown() {
        programs.close();
        meshes.close();
        upload.close();
        jobs.close();
        device.close();
        context.close();
        ARENA.close();
    }

    @Test
    void everyConfigurationLightsTheSphere() {
        SceneRenderer.Config d = SceneRenderer.Config.DEFAULT;
        String[] names = {
            "default",
            "no sun shadows",
            "no local shadows",
            "no ambient occlusion",
            "no decals",
            "no outlines",
            "no particles",
            "no fog of war",
            "no gpu culling",
            "plain post",
            "minimal"
        };
        SceneRenderer.Config[] configs = {
            d,
            d.withSunShadows(null),
            d.withLocalShadows(null),
            d.withAmbientOcclusion(null),
            d.withDecalTextureSize(0),
            d.withOutlines(false),
            d.withParticleCapacity(0),
            d.withFogOfWar(false),
            d.withGpuCulling(false),
            d.withPost(PLAIN_POST),
            d.withSunShadows(null)
                    .withLocalShadows(null)
                    .withAmbientOcclusion(null)
                    .withDecalTextureSize(0)
                    .withOutlines(false)
                    .withParticleCapacity(0)
                    .withFogOfWar(false)
                    .withGpuCulling(false)
                    .withPost(PLAIN_POST)
        };
        StringBuilder table =
                new StringBuilder("\nconfiguration          factor-only   textured    skinned\n");
        List<String> failed = new ArrayList<>();
        for (int i = 0; i < configs.length; i++) {
            float[] lit = render(configs[i]);
            table.append(
                    String.format(
                            "%-22s %10.3f %10.3f %10.3f%n", names[i], lit[0], lit[1], lit[2]));
            if (lit[0] < 0.5f) {
                failed.add(names[i] + " (factor-only)");
            }
            if (lit[1] < 0.5f) {
                failed.add(names[i] + " (textured)");
            }
            if (lit[2] < 0.5f) {
                failed.add(names[i] + " (textured, skinned)");
            }
        }
        System.out.print(table);
        assertTrue(failed.isEmpty(), "unlit: " + failed + table);
    }

    /**
     * Draw-order scenarios under the default configuration: the Intel driver rendered factor-only
     * materials black once textures had been drawn on the same renderer (JUnit's hash order of
     * SceneRendererTest: three textured tests, then every lit test failed), which a fresh renderer
     * per cell never shows. Each scenario is a fresh renderer; the table says what the first frame
     * drew and what the sampled sphere read afterwards.
     */
    @Test
    void drawOrderDoesNotChangeTheShading() {
        StringBuilder table =
                new StringBuilder("\nscenario                                     value\n");
        List<String> failed = new ArrayList<>();
        String[] names = {
            "factor-only, then textured rgba8 (next frame)",
            "textured rgba8, then factor-only (next frame)",
            "textured bc7 srgb, then factor-only (next frame)",
            "bc7 bucket grown to 5 layers, textured, then factor-only",
            "factor-only beside textured bc7 (one frame)",
            "static textured bc7 beside skinned textured bc7 (one frame)",
            "textured bc7, units unbound, then factor-only",
            "translucent factor-only, then opaque factor-only",
            "textured made before frame 1, first drawn in frame 2",
            "variant compiled before frame 1, new row+texture after",
            "row+texture before frame 1, variant compiled after",
            "new factor-only row after frame 1 (no new variant)",
        };
        for (int i = 0; i < names.length; i++) {
            float[] v = scenario(i);
            table.append(String.format("%-44s %6.3f %6.3f%n", names[i], v[0], v[1]));
            if (v[0] < 0.5f || v[1] < 0.5f) {
                failed.add(names[i]);
            }
        }
        System.out.print(table);
        assertTrue(failed.isEmpty(), "unlit: " + failed + table);
    }

    /** Runs scenario {@code which}; returns the two lit readings it is about. */
    private static float[] scenario(int which) {
        try (Scene s = new Scene(SceneRenderer.Config.DEFAULT)) {
            int plain = s.plain();
            switch (which) {
                case 0 -> {
                    float a = s.frame(s.sphere(0, plain, 0));
                    float b = s.frame(s.sphere(0, s.textured(GL_RGBA8, true), 0));
                    return new float[] {a, b};
                }
                case 1 -> {
                    float a = s.frame(s.sphere(0, s.textured(GL_RGBA8, true), 0));
                    float b = s.frame(s.sphere(0, plain, 0));
                    return new float[] {a, b};
                }
                case 2 -> {
                    float a = s.frame(s.sphere(0, s.textured(BC7_SRGB, true), 0));
                    float b = s.frame(s.sphere(0, plain, 0));
                    return new float[] {a, b};
                }
                case 3 -> {
                    int m = -1;
                    for (int k = 0; k < 5; k++) {
                        m = s.textured(BC7_SRGB, true); // the fifth allocation grows the bucket
                    }
                    float a = s.frame(s.sphere(0, m, 0));
                    float b = s.frame(s.sphere(0, plain, 0));
                    return new float[] {a, b};
                }
                case 4 -> {
                    int f = s.sphere(-0.8f, plain, 0);
                    int t = s.sphere(0.8f, s.textured(BC7_SRGB, true), 0);
                    s.render();
                    return new float[] {s.lit(-0.8f), s.lit(0.8f)};
                }
                case 5 -> {
                    int t = s.textured(BC7_SRGB, true);
                    s.sphere(-0.8f, t, RenderPacket.FLAG_STATIC);
                    s.skinnedSphere(0.8f, t);
                    s.render();
                    return new float[] {s.lit(-0.8f), s.lit(0.8f)};
                }
                case 6 -> {
                    float a = s.frame(s.sphere(0, s.textured(BC7_SRGB, true), 0));
                    for (int u = 0; u < 5; u++) {
                        device.state().bindTexture(u, 0);
                    }
                    float b = s.frame(s.sphere(0, plain, 0));
                    return new float[] {a, b};
                }
                case 7 -> {
                    MaterialSystem ms = s.renderer.materials();
                    int glass =
                            ms.add(
                                    new MaterialData.Builder()
                                            .metallicRoughness(0, 0.6f)
                                            .baseColor(1, 1, 1, 0.9f)
                                            .alpha(MaterialData.ALPHA_BLEND, 0)
                                            .build());
                    ms.compileBlocking(glass);
                    float a = s.frame(s.sphere(0, glass, 0));
                    float b = s.frame(s.sphere(0, plain, 0));
                    return new float[] {a, b};
                }
                case 8 -> {
                    int t = s.textured(GL_RGBA8, true);
                    float a = s.frame(s.sphere(0, plain, 0));
                    float b = s.frame(s.sphere(0, t, 0));
                    return new float[] {a, b};
                }
                case 9 -> {
                    s.textured(GL_RGBA8, true); // compiles the textured variants, unused
                    float a = s.frame(s.sphere(0, plain, 0));
                    float b = s.frame(s.sphere(0, s.textured(GL_RGBA8, true), 0));
                    return new float[] {a, b};
                }
                case 10 -> {
                    int t = s.textured(GL_RGBA8, false); // row and texture, not compiled
                    float a = s.frame(s.sphere(0, plain, 0));
                    s.renderer.materials().compileBlocking(t);
                    float b = s.frame(s.sphere(0, t, 0));
                    return new float[] {a, b};
                }
                default -> {
                    float a = s.frame(s.sphere(0, plain, 0));
                    float b = s.frame(s.sphere(0, s.plain(), 0));
                    return new float[] {a, b};
                }
            }
        }
    }

    private static final int BC7_SRGB = GL_COMPRESSED_SRGB_ALPHA_BPTC_UNORM;

    /** One BC7 block (mode 6) of opaque white: endpoints 255 with the p-bits set, indices 0. */
    private static final byte[] WHITE_BC7 = {
        (byte) 0xC0,
        (byte) 0xFF,
        (byte) 0xFF,
        (byte) 0xFF,
        (byte) 0xFF,
        (byte) 0xFF,
        (byte) 0xFF,
        (byte) 0xFF,
        1,
        0,
        0,
        0,
        0,
        0,
        0,
        0
    };

    /**
     * A renderer with a packet, a sun from +X and no ambient; spheres of radius 0.4 at y = z = 0.
     */
    private static final class Scene implements AutoCloseable {
        final SceneRenderer renderer;
        final RenderPacket packet = new RenderPacket(8);

        Scene(SceneRenderer.Config config) {
            renderer = new SceneRenderer(device, programs, meshes, jobs, config);
            renderer.resize(SIZE, SIZE);
            renderer.environment().setUniform(0, 0, 0);
            reset();
        }

        void reset() {
            packet.reset();
            camera(packet);
            packet.sunDirection[0] = 1;
            packet.sunDirection[1] = 0;
            packet.sunDirection[2] = 0;
            packet.sunColor[0] = packet.sunColor[1] = packet.sunColor[2] = 3;
        }

        int plain() {
            MaterialSystem ms = renderer.materials();
            int m =
                    ms.add(
                            new MaterialData.Builder()
                                    .metallicRoughness(0, 0.6f)
                                    .baseColor(1, 1, 1, 1)
                                    .build());
            ms.compileBlocking(m);
            return m;
        }

        /**
         * A white 4x4 texture of {@code format} in its own array layer, on a new material, with its
         * shader variants compiled when {@code compile}.
         */
        int textured(int format, boolean compile) {
            MaterialSystem ms = renderer.materials();
            int m = ms.add(new MaterialData.Builder().metallicRoughness(0, 0.6f).build());
            int ref = ms.arrays().allocate(format, 4, 4, 1);
            MemorySegment texels = ARENA.allocate(64, 16);
            if (format == GL_RGBA8) {
                texels.fill((byte) 0xFF);
            } else {
                MemorySegment.copy(WHITE_BC7, 0, texels, ValueLayout.JAVA_BYTE, 0, 16);
            }
            device.textures()
                    .uploadLayer(
                            ms.arrays().textureHandle(TextureArrays.bucket(ref)),
                            0,
                            TextureArrays.layer(ref),
                            texels.address(),
                            format == GL_RGBA8 ? 64 : 16);
            ms.setTexture(m, MaterialData.SLOT_BASE_COLOR, ref, false);
            if (compile) {
                ms.compileBlocking(m);
                ms.compileBlockingSkinned(m);
            }
            return m;
        }

        /** A sphere at height {@code y} (apart along Y, so neither shadows the other). */
        int sphere(float y, int material, int flags) {
            float[] world = new float[12];
            AffineOps.identity(world, 0);
            world[7] = y;
            int i =
                    packet.add(
                            world,
                            0,
                            world,
                            0,
                            sphere,
                            material,
                            RenderPacket.FLAG_CASTS_SHADOW | flags,
                            0);
            packet.setBounds(i, 0, y, 0, 0.4f);
            return i;
        }

        int skinnedSphere(float y, int material) {
            float[] world = new float[12];
            AffineOps.identity(world, 0);
            world[7] = y;
            int i =
                    packet.add(
                            world,
                            0,
                            world,
                            0,
                            skinnedSphere,
                            material,
                            RenderPacket.FLAG_CASTS_SHADOW,
                            0);
            packet.setBounds(i, 0, y, 0, 0.4f);
            float[] identity = new float[RenderPacket.PALETTE_FLOATS];
            AffineOps.identity(identity, 0);
            packet.setPalette(i, packet.addPalette(identity, 0, 1));
            return i;
        }

        /** Renders the packet twice and reads the HDR target. */
        void render() {
            for (int f = 0; f < 2; f++) {
                renderer.render(packet);
            }
            glGetTextureImage(
                    renderer.hdrTexture(), 0, GL_RGBA, GL_FLOAT, (int) pixels.byteSize(), pixels);
        }

        /** Renders the packet as it is, reads the lit side of {@code instance} and resets. */
        float frame(int instance) {
            render();
            float v = lit(packet.currWorld[instance * 12 + 7]);
            reset();
            return v;
        }

        /** The lit side's red of the sphere at height {@code y} (the sun travels along +X). */
        float lit(float y) {
            int[] p = screen(packet, -0.3f, y, 0.25f);
            return pixels.getAtIndex(ValueLayout.JAVA_FLOAT, ((long) p[1] * SIZE + p[0]) * 4);
        }

        @Override
        public void close() {
            renderer.close();
        }
    }

    /**
     * Renders the sphere three times (factor-only, textured, textured and skinned with an identity
     * palette) and returns the lit side's red for each.
     */
    private static float[] render(SceneRenderer.Config config) {
        SceneRenderer renderer = new SceneRenderer(device, programs, meshes, jobs, config);
        try {
            renderer.resize(SIZE, SIZE);
            renderer.environment().setUniform(0, 0, 0);
            MaterialSystem ms = renderer.materials();
            int plain =
                    ms.add(
                            new MaterialData.Builder()
                                    .metallicRoughness(0, 0.6f)
                                    .baseColor(1, 1, 1, 1)
                                    .build());
            int textured = ms.add(new MaterialData.Builder().metallicRoughness(0, 0.6f).build());
            int ref = ms.arrays().allocate(GL_RGBA8, 4, 4, 1);
            MemorySegment white = ARENA.allocate(64, 16);
            white.fill((byte) 0xFF);
            device.textures()
                    .uploadLayer(
                            ms.arrays().textureHandle(TextureArrays.bucket(ref)),
                            0,
                            TextureArrays.layer(ref),
                            white.address(),
                            64);
            ms.setTexture(textured, MaterialData.SLOT_BASE_COLOR, ref, false);
            ms.compileBlocking(plain);
            ms.compileBlocking(textured);
            ms.compileBlockingSkinned(textured);
            float[] lit = new float[3];
            int[] materials = {plain, textured, textured};
            for (int k = 0; k < 3; k++) {
                RenderPacket packet = new RenderPacket(8);
                camera(packet);
                packet.sunDirection[0] = 1;
                packet.sunDirection[1] = 0;
                packet.sunDirection[2] = 0;
                packet.sunColor[0] = packet.sunColor[1] = packet.sunColor[2] = 3;
                float[] world = new float[12];
                AffineOps.identity(world, 0);
                int inst =
                        packet.add(
                                world,
                                0,
                                world,
                                0,
                                k == 2 ? skinnedSphere : sphere,
                                materials[k],
                                RenderPacket.FLAG_CASTS_SHADOW,
                                0);
                packet.setBounds(inst, 0, 0, 0, 0.4f);
                if (k == 2) {
                    float[] identity = new float[RenderPacket.PALETTE_FLOATS];
                    AffineOps.identity(identity, 0);
                    packet.setPalette(inst, packet.addPalette(identity, 0, 1));
                }
                for (int f = 0; f < 2; f++) {
                    renderer.render(packet);
                }
                glGetTextureImage(
                        renderer.hdrTexture(),
                        0,
                        GL_RGBA,
                        GL_FLOAT,
                        (int) pixels.byteSize(),
                        pixels);
                int[] p = screen(packet, -0.3f, 0, 0.25f);
                lit[k] = pixels.getAtIndex(ValueLayout.JAVA_FLOAT, ((long) p[1] * SIZE + p[0]) * 4);
            }
            return lit;
        } finally {
            renderer.close();
        }
    }

    private static void camera(RenderPacket packet) {
        float[] view = new float[16];
        float[] proj = new float[16];
        float[] vp = new float[16];
        float[] inv = new float[16];
        Mat4Ops.lookAt(0, 0, 4, 0, 0, 0, 0, 1, 0, view, 0);
        Mat4Ops.perspectiveReversedZ((float) Math.toRadians(40), 1, 0.1f, proj, 0);
        Mat4Ops.mul(proj, 0, view, 0, vp, 0);
        Mat4Ops.invert(vp, 0, inv, 0);
        float[] planes = new float[CullOps.PLANES_FLOATS];
        CullOps.extractPlanes(vp, 0, planes);
        packet.setCamera(view, proj, vp, inv, planes, 0, 0, 4);
        packet.near = 0.1f;
        packet.far = 50f;
        packet.alpha = 1;
    }

    private static int[] screen(RenderPacket packet, float x, float y, float z) {
        float[] ndc = new float[3];
        Mat4Ops.project(packet.viewProjection, 0, x, y, z, ndc, 0);
        return new int[] {
            (int) ((ndc[0] * 0.5f + 0.5f) * SIZE), (int) ((ndc[1] * 0.5f + 0.5f) * SIZE)
        };
    }
}
