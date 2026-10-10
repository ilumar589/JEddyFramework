package dev.jeddy.render.post;

import static dev.jeddy.gl.ffi.GL46.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import dev.jeddy.render.mesh.MeshRegistry;
import dev.jeddy.render.shader.ProgramCache;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/** The post chain on the GPU: tone curves, exposure, bloom, grading and FXAA (TASKS M7-06). */
@Tag("gpu")
class PostProcessTest {
    private static final int SIZE = 128;
    private static final float TOLERANCE = 2.5f / 255f;
    private static final Arena ARENA = Arena.ofShared();
    private static GpuTestContext context;
    private static GpuDevice device;
    private static JobSystem jobs;
    private static UploadRing upload;
    private static MeshRegistry meshes;
    private static ProgramCache programs;
    private static SceneRenderer renderer;
    private static PostProcess post;
    private static int sphere;
    private static MemorySegment pixels;
    private final RenderPacket packet = new RenderPacket(64);

    @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir;

    @BeforeAll
    static void init() {
        context = new GpuTestContext("post process test", SIZE);
        device = new GpuDevice(context.caps());
        device.useReversedZ();
        jobs = new JobSystem(2);
        upload = new UploadRing(device, 1 << 20, 1 << 20);
        meshes = new MeshRegistry(device, upload, 1 << 16, 1 << 16);
        programs =
                new ProgramCache(
                        ShaderSources.classpath(MeshRegistry.class, "/dev/jeddy/render/shaders"),
                        context.caps(),
                        null);
        SceneRenderer.Config config =
                SceneRenderer.Config.DEFAULT
                        .withSunShadows(null)
                        .withLocalShadows(null)
                        .withAmbientOcclusion(null)
                        .withPost(
                                PostProcess.Config.DEFAULT
                                        .withBloom(0f, 6)
                                        .withAutoExposure(null)
                                        .withFxaa(true));
        renderer = new SceneRenderer(device, programs, meshes, jobs, config);
        renderer.resize(SIZE, SIZE);
        post = renderer.post();
        post.compileBlocking();
        upload.beginFrame();
        sphere = meshes.add(TestMeshes.sphere(ARENA, 0.4f, 32, 16));
        upload.endFrame();
        pixels = ARENA.allocate((long) SIZE * SIZE * 4, 16);
    }

    @AfterAll
    static void shutdown() {
        renderer.close();
        programs.close();
        meshes.close();
        upload.close();
        jobs.close();
        device.close();
        context.close();
        ARENA.close();
    }

    @BeforeEach
    void reset() {
        packet.reset();
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
        packet.sunColor[0] = packet.sunColor[1] = packet.sunColor[2] = 0;
        post.setTonemapper(PostProcess.Tonemapper.AGX);
        post.setBloomIntensity(0);
        post.setAutoExposure(null);
        post.setFxaa(false);
        post.setGradingLut(0, 0);
    }

    // Reference curves (engine/color.glsl) -----------------------------------------------------

    private static float srgb(float c) {
        c = Math.clamp(c, 0f, 1f);
        return c <= 0.0031308f ? c * 12.92f : (float) (1.055 * Math.pow(c, 1 / 2.4) - 0.055);
    }

    private static float aces(float x) {
        return Math.clamp((x * (2.51f * x + 0.03f)) / (x * (2.43f * x + 0.59f) + 0.14f), 0f, 1f);
    }

    private static final double[][] INSET = {
        {0.842479062253094, 0.0784335999999992, 0.0792237451477643},
        {0.0423282422610123, 0.878468636469772, 0.0791661274605434},
        {0.0423756549057051, 0.0784336, 0.879142973793104}
    };
    private static final double[][] OUTSET = {
        {1.19687900512017, -0.0980208811401368, -0.0990297440797205},
        {-0.0528968517574562, 1.15190312990417, -0.0989611768448433},
        {-0.0529716355144438, -0.0980434501171241, 1.15107367264116}
    };

    private static float[] agx(float r, float g, float b) {
        double[] in = {Math.max(r, 0), Math.max(g, 0), Math.max(b, 0)};
        double[] c = new double[3];
        double minEv = -12.47393;
        double maxEv = 4.026069;
        for (int i = 0; i < 3; i++) {
            double v = INSET[i][0] * in[0] + INSET[i][1] * in[1] + INSET[i][2] * in[2];
            v = Math.clamp(Math.log(Math.max(v, 1e-10)) / Math.log(2), minEv, maxEv);
            v = (v - minEv) / (maxEv - minEv);
            double x2 = v * v;
            double x4 = x2 * x2;
            c[i] =
                    15.5 * x4 * x2
                            - 40.14 * x4 * v
                            + 31.96 * x4
                            - 6.868 * x2 * v
                            + 0.4298 * x2
                            + 0.1191 * v
                            - 0.00232;
        }
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) {
            double v = OUTSET[i][0] * c[0] + OUTSET[i][1] * c[1] + OUTSET[i][2] * c[2];
            out[i] = (float) Math.pow(Math.clamp(v, 0, 1), 2.2);
        }
        return out;
    }

    private static float half(float v) {
        return Float.float16ToFloat(Float.floatToFloat16(v));
    }

    // Helpers ------------------------------------------------------------------------------------

    private void render(int frames) {
        for (int i = 0; i < frames; i++) {
            renderer.render(packet);
        }
        device.state().bindFramebuffer(0);
        glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    }

    private static float px(int x, int y, int c) {
        return (pixels.get(ValueLayout.JAVA_BYTE, ((long) y * SIZE + x) * 4 + c) & 0xFF) / 255f;
    }

    private static float exposure() {
        MemorySegment out = ARENA.allocate(4);
        glGetNamedBufferSubData(post.exposureBufferGlName(), PostProcess.EXPOSURE_OFFSET, 4, out);
        return out.get(ValueLayout.JAVA_FLOAT, 0);
    }

    private static int emissive(float strength) {
        int m =
                renderer.materials()
                        .add(
                                new MaterialData.Builder()
                                        .baseColor(0, 0, 0, 1)
                                        .metallicRoughness(0, 1)
                                        .emissive(1, 1, 1, strength)
                                        .build());
        renderer.materials().compileBlocking(m);
        return m;
    }

    private void addSphere(int material) {
        float[] world = new float[12];
        AffineOps.identity(world, 0);
        int i = packet.add(world, 0, world, 0, sphere, material, 0, 0);
        packet.setBounds(i, 0, 0, 0, 0.4f);
    }

    // Tests --------------------------------------------------------------------------------------

    @Test
    void toneCurvesMatchTheReference() {
        float[] radiance = {0f, 0.02f, 0.18f, 1f, 4f, 30f};
        for (PostProcess.Tonemapper t : PostProcess.Tonemapper.values()) {
            post.setTonemapper(t);
            float previous = -1;
            for (float v : radiance) {
                renderer.environment().setUniform(v, v, v);
                packet.exposure = 1.5f;
                render(1);
                float x = half(v) * 1.5f;
                float expected = srgb(t == PostProcess.Tonemapper.AGX ? agx(x, x, x)[0] : aces(x));
                float actual = px(SIZE / 2, SIZE / 2, 0);
                assertEquals(expected, actual, TOLERANCE, t + " at " + v);
                assertTrue(actual >= previous, t + " is monotonic");
                previous = actual;
            }
        }
    }

    @Test
    void autoExposureMapsTheAverageToTheKeyAndAdapts() {
        PostProcess.AutoExposure ae = PostProcess.AutoExposure.DEFAULT;
        post.setAutoExposure(ae);
        renderer.environment().setUniform(0.05f, 0.05f, 0.05f);
        packet.time = 0;
        render(1); // the first frame snaps
        float snapped = exposure();
        assertEquals(0.18f / half(0.05f), snapped, 0.18f / 0.05f * 0.08f, "exposure");
        float key = srgb(agx(0.18f, 0.18f, 0.18f)[0]);
        assertEquals(key, px(SIZE / 2, SIZE / 2, 0), 0.03f, "the average shows as the key");

        // Ten times brighter: the exposure moves towards 0.18 / 0.5 over a few frames.
        renderer.environment().setUniform(0.5f, 0.5f, 0.5f);
        packet.time += 0.1f;
        render(1);
        float step = exposure();
        assertTrue(step < snapped * 0.95f && step > 0.36f * 1.05f, "adapting: " + step);
        for (int i = 0; i < 40; i++) {
            packet.time += 0.1f;
            renderer.render(packet);
        }
        render(1);
        assertEquals(0.36f, exposure(), 0.36f * 0.08f, "adapted");
        assertEquals(key, px(SIZE / 2, SIZE / 2, 0), 0.03f, "the brighter scene shows as the key");

        // Compensation still applies on top.
        packet.exposure = 2;
        render(1);
        float x = 0.18f * 2;
        assertEquals(srgb(agx(x, x, x)[0]), px(SIZE / 2, SIZE / 2, 0), 0.03f, "compensation");
    }

    @Test
    void bloomSpreadsBrightLightIntoTheSurroundings() {
        renderer.environment().setUniform(0, 0, 0);
        addSphere(emissive(40));
        // The sphere covers about 0.4 / (4 tan 20°) of the half-width: ~18 px of radius.
        int probe = SIZE / 2 + 30;
        render(1);
        float without = px(probe, SIZE / 2, 0);
        assertTrue(without < 2f / 255f, "no bloom: " + without);
        post.setBloomIntensity(0.05f);
        render(1);
        float with = px(probe, SIZE / 2, 0);
        assertTrue(with > 8f / 255f, "bloom reaches past the silhouette: " + with);
        assertTrue(px(SIZE / 2, SIZE / 2, 0) > 0.9f, "the emitter itself stays bright");
    }

    @Test
    void gradingLutRemapsDisplayColors() {
        int n = 16;
        float[] swap = new float[n * n * n * 3];
        for (int b = 0; b < n; b++) {
            for (int g = 0; g < n; g++) {
                for (int r = 0; r < n; r++) {
                    int i = ((b * n + g) * n + r) * 3;
                    swap[i] = b / (n - 1f); // red <- blue
                    swap[i + 1] = g / (n - 1f);
                    swap[i + 2] = r / (n - 1f); // blue <- red
                }
            }
        }
        int lut = PostProcess.createLut(device, n, swap);
        try {
            renderer.environment().setUniform(0.6f, 0.1f, 0.02f);
            post.setGradingLut(device.textures().glName(lut), n);
            render(1);
            float[] expected = agx(half(0.6f), half(0.1f), half(0.02f));
            assertEquals(srgb(expected[2]), px(4, 4, 0), TOLERANCE, "red shows blue's value");
            assertEquals(srgb(expected[1]), px(4, 4, 1), TOLERANCE, "green unchanged");
            assertEquals(srgb(expected[0]), px(4, 4, 2), TOLERANCE, "blue shows red's value");
        } finally {
            post.setGradingLut(0, 0);
            device.textures().destroy(lut);
        }
    }

    @Test
    void cookedLutsLoadAsVolumeTextures() throws Exception {
        int n = 8;
        float[] dim = new float[n * n * n * 3];
        for (int i = 0; i < n * n * n; i++) {
            int r = i % n;
            int g = i / n % n;
            int b = i / (n * n);
            dim[i * 3] = 0.5f * r / (n - 1f);
            dim[i * 3 + 1] = 0.5f * g / (n - 1f);
            dim[i * 3 + 2] = 0.5f * b / (n - 1f);
        }
        java.nio.file.Path cooked = java.nio.file.Files.createDirectories(tempDir.resolve("grade"));
        java.nio.file.Files.write(
                cooked.resolve("dim.ktx2"), dev.jeddy.render.TestKtx2.volume(n, dim, true));
        try (dev.jeddy.asset.Vfs vfs = new dev.jeddy.asset.Vfs()) {
            vfs.mountDirectory(tempDir);
            try (dev.jeddy.asset.AssetManager manager =
                    new dev.jeddy.asset.AssetManager(vfs, jobs)) {
                dev.jeddy.render.material.MaterialAssets assets =
                        new dev.jeddy.render.material.MaterialAssets(
                                manager, device, renderer.materials());
                int h = assets.textures().load("grade/dim.ktx2");
                long deadline = System.nanoTime() + 20_000_000_000L;
                while (!assets.textures().isReady(h)) {
                    manager.update();
                    manager.pumpUploads(1_000_000_000L);
                    assertTrue(System.nanoTime() < deadline, "loading");
                }
                dev.jeddy.render.material.GpuTexture t = assets.textures().get(h);
                assertEquals(
                        dev.jeddy.render.gpu.GpuTextures.KIND_3D, device.textures().kind(t.handle));
                renderer.environment().setUniform(1, 1, 1);
                post.setGradingLut(device.textures().glName(t.handle), n);
                render(1);
                float full = srgb(agx(1, 1, 1)[0]);
                assertEquals(full * 0.5f, px(4, 4, 1), TOLERANCE, "halved by the LUT");
                post.setGradingLut(0, 0);
                assets.textures().release(h);
                manager.update();
            }
        }
    }

    @Test
    void fxaaSoftensTheSilhouette() {
        renderer.environment().setUniform(0, 0, 0);
        addSphere(emissive(2));
        render(1);
        int hard = intermediatePixels();
        post.setFxaa(true);
        render(1);
        int soft = intermediatePixels();
        assertTrue(soft > hard * 1.5f, "FXAA blends edge pixels: " + hard + " -> " + soft);
        // Flat areas are untouched.
        assertTrue(px(2, 2, 0) < 2f / 255f, "background");
    }

    private static int intermediatePixels() {
        float inside = px(SIZE / 2, SIZE / 2, 0);
        int count = 0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float v = px(x, y, 0) / inside;
                if (v > 0.15f && v < 0.85f) {
                    count++;
                }
            }
        }
        return count;
    }

    @Test
    void thePostChainAllocatesNothing() {
        renderer.environment().setUniform(0.5f, 0.5f, 0.5f);
        addSphere(emissive(10));
        post.setAutoExposure(PostProcess.AutoExposure.DEFAULT);
        post.setBloomIntensity(0.04f);
        post.setFxaa(true);
        for (int i = 0; i < 5; i++) {
            packet.time += 0.016f;
            renderer.render(packet);
        }
        Allocations.assertNoAllocation(
                "post frame",
                5,
                10,
                () -> {
                    packet.time += 0.016f;
                    renderer.render(packet);
                });
    }
}
