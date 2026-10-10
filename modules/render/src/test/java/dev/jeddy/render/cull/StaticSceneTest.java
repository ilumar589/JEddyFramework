package dev.jeddy.render.cull;

import static dev.jeddy.gl.ffi.GL46.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.format.MaterialData;
import dev.jeddy.asset.format.MeshData;
import dev.jeddy.asset.format.MeshWriter;
import dev.jeddy.asset.shader.ShaderSources;
import dev.jeddy.core.job.JobSystem;
import dev.jeddy.math.AffineOps;
import dev.jeddy.math.CullOps;
import dev.jeddy.math.Mat4Ops;
import dev.jeddy.render.GpuTestContext;
import dev.jeddy.render.RenderPacket;
import dev.jeddy.render.SceneRenderer;
import dev.jeddy.render.TestMeshes;
import dev.jeddy.render.draw.StaticScene;
import dev.jeddy.render.gpu.GpuDevice;
import dev.jeddy.render.gpu.UploadRing;
import dev.jeddy.render.mesh.MeshRegistry;
import dev.jeddy.render.post.PostProcess;
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
import java.lang.management.ManagementFactory;

/** GPU-driven culling of static instances (TASKS M7-11). */
@Tag("gpu")
class StaticSceneTest {
    private static final int SIZE = 96;
    private static final Arena ARENA = Arena.ofShared();
    private static GpuTestContext context;
    private static GpuDevice device;
    private static JobSystem jobs;
    private static UploadRing upload;
    private static MeshRegistry meshes;
    private static ProgramCache programs;
    private static SceneRenderer renderer;
    private static StaticScene scene;
    private static int sphere;
    private static int ground;
    private static int quad;
    private static int white;
    private static int glass;
    private static MemorySegment pixels;
    private static MemorySegment reference;
    private static int version;
    private final RenderPacket packet = new RenderPacket(256);

    @BeforeAll
    static void init() {
        context = new GpuTestContext("static scene test", SIZE);
        device = new GpuDevice(context.caps());
        device.useReversedZ();
        jobs = new JobSystem(2);
        upload = new UploadRing(device, 1 << 20, 1 << 20);
        meshes = new MeshRegistry(device, upload, 1 << 18, 1 << 18);
        programs =
                new ProgramCache(
                        ShaderSources.classpath(MeshRegistry.class, "/dev/jeddy/render/shaders"),
                        context.caps(),
                        null);
        SceneRenderer.Config config =
                SceneRenderer.Config.DEFAULT
                        .withLocalShadows(null)
                        .withAmbientOcclusion(null)
                        .withParticleCapacity(0)
                        .withPost(
                                PostProcess.Config.DEFAULT
                                        .withBloom(0, 0)
                                        .withAutoExposure(null)
                                        .withFxaa(false));
        renderer = new SceneRenderer(device, programs, meshes, jobs, config);
        renderer.resize(SIZE, SIZE);
        scene = renderer.staticScene();
        upload.beginFrame();
        sphere = meshes.add(TestMeshes.sphere(ARENA, 0.2f, 16, 8));
        ground = meshes.add(TestMeshes.plane(ARENA, 12));
        quad = meshes.add(twoLodQuad());
        upload.endFrame();
        white = material(MaterialData.ALPHA_OPAQUE, 1);
        glass = material(MaterialData.ALPHA_BLEND, 0.4f);
        pixels = ARENA.allocate((long) SIZE * SIZE * 16, 16);
        reference = ARENA.allocate((long) SIZE * SIZE * 16, 16);
    }

    /** A 0.5 m quad facing +Z; LOD 1 (error 0.05 m) is a single triangle. */
    private static MeshData twoLodQuad() {
        float h = 0.25f;
        float[] pos = {-h, -h, 0, h, -h, 0, h, h, 0, -h, h, 0};
        float[] nrm = {0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1};
        float[] tan = {1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1};
        float[] uv = {0, 0, 1, 0, 1, 1, 0, 1};
        int[] idx = {0, 1, 2, 0, 2, 3, 0, 1, 2};
        byte[] bytes =
                new MeshWriter()
                        .positions(pos)
                        .attributes(nrm, tan, uv, null)
                        .indices(idx)
                        .lod(0, new int[] {0}, new int[] {6})
                        .lod(0.05f, new int[] {6}, new int[] {3})
                        .materials(0)
                        .toBytes();
        MemorySegment s = ARENA.allocate(bytes.length, 64);
        MemorySegment.copy(bytes, 0, s, ValueLayout.JAVA_BYTE, 0, bytes.length);
        try {
            return MeshData.read(s);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static int material(int alphaMode, float alpha) {
        int m =
                renderer.materials()
                        .add(
                                new MaterialData.Builder()
                                        .baseColor(0.8f, 0.8f, 0.8f, alpha)
                                        .metallicRoughness(0, 0.7f)
                                        .alpha(alphaMode, 0)
                                        .deriveFeatures()
                                        .build());
        renderer.materials().compileBlocking(m);
        return m;
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
        lookFrom(0, 6, 6);
        packet.near = 0.1f;
        packet.far = 60f;
        packet.alpha = 1;
        packet.sunDirection[0] = 0.5f;
        packet.sunDirection[1] = -1;
        packet.sunDirection[2] = 0.3f;
        packet.sunColor[0] = packet.sunColor[1] = packet.sunColor[2] = 2;
        packet.staticVersion = ++version; // unique across tests: each test has its own packet
        renderer.environment().setUniform(0.3f, 0.3f, 0.3f);
    }

    private void lookFrom(float x, float y, float z) {
        float[] view = new float[16];
        float[] proj = new float[16];
        float[] vp = new float[16];
        float[] inv = new float[16];
        Mat4Ops.lookAt(x, y, z, 0, 0, 0, 0, 1, 0, view, 0);
        Mat4Ops.perspectiveReversedZ((float) Math.toRadians(40), 1, 0.1f, proj, 0);
        Mat4Ops.mul(proj, 0, view, 0, vp, 0);
        Mat4Ops.invert(vp, 0, inv, 0);
        float[] planes = new float[CullOps.PLANES_FLOATS];
        CullOps.extractPlanes(vp, 0, planes);
        packet.setCamera(view, proj, vp, inv, planes, x, y, z);
    }

    private int add(int mesh, float x, float y, float z, int material, int flags, float r) {
        float[] world = new float[12];
        AffineOps.identity(world, 0);
        world[3] = x;
        world[7] = y;
        world[11] = z;
        int i = packet.add(world, 0, world, 0, mesh, material, flags, 0);
        packet.setBounds(i, x, y, z, r);
        return i;
    }

    /** A ground and a 9×9 grid of spheres, all with {@code flags}. */
    private void grid(int flags) {
        add(ground, 0, -0.2f, 0, white, flags | RenderPacket.FLAG_CASTS_SHADOW, 8.5f);
        for (int z = -4; z <= 4; z++) {
            for (int x = -4; x <= 4; x++) {
                add(sphere, x, 0, z, white, flags | RenderPacket.FLAG_CASTS_SHADOW, 0.2f);
            }
        }
    }

    private void render(MemorySegment into) {
        renderer.render(packet);
        renderer.render(packet);
        glGetTextureImage(renderer.hdrTexture(), 0, GL_RGBA, GL_FLOAT, (int) into.byteSize(), into);
    }

    private static float maxDifference() {
        float max = 0;
        for (long i = 0; i < (long) SIZE * SIZE * 4; i++) {
            max =
                    Math.max(
                            max,
                            Math.abs(
                                    pixels.getAtIndex(ValueLayout.JAVA_FLOAT, i)
                                            - reference.getAtIndex(ValueLayout.JAVA_FLOAT, i)));
        }
        return max;
    }

    @Test
    void gpuCulledStaticInstancesLookLikeTheCpuPath() {
        grid(0);
        render(reference);
        assertTrue(!scene.active(), "nothing static yet");
        packet.reset();
        grid(RenderPacket.FLAG_STATIC);
        packet.staticVersion = ++version;
        render(pixels);
        assertTrue(scene.active());
        assertEquals(82, scene.staticCount());
        assertEquals(82, scene.itemCount());
        assertEquals(2, scene.commandCount(), "one bin per mesh, one LOD each");
        assertTrue(maxDifference() < 2e-3f, "same image, shadows included: " + maxDifference());
        // The main view sees part of the grid: fewer instances than items, as many as the CPU
        // culls.
        int[] counts = scene.readInstanceCounts(StaticScene.VIEW_MAIN);
        int drawn = counts[0] + counts[1];
        int expected = 0;
        for (int i = 0; i < packet.count(); i++) {
            int o = i * 4;
            if (CullOps.sphereVisible(
                    packet.frustumPlanes,
                    packet.bounds[o],
                    packet.bounds[o + 1],
                    packet.bounds[o + 2],
                    packet.bounds[o + 3])) {
                expected++;
            }
        }
        assertEquals(expected, drawn, "GPU and CPU frustum tests agree");
        assertTrue(drawn < 82, "some spheres are outside: " + drawn);
        // Cascades cull for casters too.
        int[] cascade = scene.readInstanceCounts(StaticScene.VIEW_CASCADES);
        assertTrue(cascade[0] + cascade[1] > 0, "the first cascade has casters");
    }

    @Test
    void farInstancesUseCoarserLods() {
        add(quad, 0, 0, 0, white, RenderPacket.FLAG_STATIC, 0.36f);
        add(quad, 0, 0, -40, white, RenderPacket.FLAG_STATIC, 0.36f);
        lookFrom(0, 0, 3);
        render(pixels);
        int[] counts = scene.readInstanceCounts(StaticScene.VIEW_MAIN);
        assertEquals(2, counts.length, "LOD 0 and LOD 1 commands");
        assertEquals(1, counts[0], "the near quad at full detail");
        assertEquals(1, counts[1], "the far quad at LOD 1");
    }

    @Test
    void blendedStaticInstancesStayOnTheCpuPath() {
        grid(RenderPacket.FLAG_STATIC);
        int glassIndex = add(sphere, 0, 1, 0, glass, RenderPacket.FLAG_STATIC, 0.2f);
        render(pixels);
        assertEquals(1, scene.cpuCount());
        assertEquals(glassIndex, scene.cpuInstances()[0]);
        assertEquals(83, scene.staticCount());
    }

    @Test
    void flagsAndFadeFollowThePacketWithoutRebuilding() {
        grid(RenderPacket.FLAG_STATIC);
        render(reference);
        int commands = scene.commandCount();
        // Fade out every sphere: only the ground remains.
        for (int i = 1; i < packet.count(); i++) {
            packet.setFade(i, 1);
        }
        render(pixels);
        assertEquals(commands, scene.commandCount());
        assertTrue(maxDifference() > 0.1f, "spheres gone");
        packet.reset();
        grid(0);
        add(ground, 0, -0.2f, 0, white, 0, 8.5f); // keep the index layout comparable
        render(reference);
    }

    @Test
    void rebuildsWhenTheStaticVersionChanges() {
        grid(RenderPacket.FLAG_STATIC);
        render(pixels);
        assertEquals(82, scene.itemCount());
        packet.reset();
        add(ground, 0, -0.2f, 0, white, RenderPacket.FLAG_STATIC, 8.5f);
        packet.staticVersion = ++version;
        render(pixels);
        assertEquals(1, scene.itemCount());
    }

    @Test
    void staticFramesAllocateNothing() {
        grid(RenderPacket.FLAG_STATIC);
        add(sphere, 0, 1, 0, white, 0, 0.2f); // and a dynamic one
        render(pixels);
        Allocations.assertNoAllocation("static scene frame", 8, 10, () -> renderer.render(packet));
    }

    @Test
    void gpuCullingCutsMainThreadCpuTimeOnTwentyThousandInstances() {
        // 20k small static quads over a 200 m field; the camera sees a part of it.
        int n = 20_000;
        java.util.Random random = new java.util.Random(5);
        for (int i = 0; i < n; i++) {
            float x = random.nextFloat() * 200 - 100;
            float z = random.nextFloat() * 200 - 100;
            add(
                    quad,
                    x,
                    0.25f,
                    z,
                    white,
                    RenderPacket.FLAG_STATIC | RenderPacket.FLAG_CASTS_SHADOW,
                    0.36f);
        }
        lookFrom(0, 30, 30);
        packet.far = 200;
        double[] gpu = mainThreadMillis();
        assertTrue(scene.active());
        int[] flags = packet.flags;
        for (int i = 0; i < n; i++) {
            flags[i] &= ~RenderPacket.FLAG_STATIC;
        }
        packet.staticVersion = ++version;
        double[] cpu = mainThreadMillis();
        assertTrue(!scene.active());
        System.out.printf(
                "20k instances, main thread per frame: preparation (cull, build, upload) CPU path"
                        + " %.2f ms, GPU path %.2f ms (%.0f%% less); thread CPU incl. the GL"
                        + " driver: %.2f ms vs %.2f ms%n",
                cpu[0], gpu[0], 100 * (1 - gpu[0] / cpu[0]), cpu[1], gpu[1]);
        // llvmpipe runs "GPU" compute on the CPU, inside GL calls, so the AC compares the
        // renderer's own preparation; the driver part is confirmed on real GPUs (M10).
        assertTrue(gpu[0] < cpu[0] * 0.7, "at least 30% less: " + gpu[0] + " vs " + cpu[0]);
    }

    /**
     * Median per-frame preparation wall time and main-thread CPU time (thread CPU time, so waits
     * for the driver's threads do not count).
     */
    private double[] mainThreadMillis() {
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        for (int i = 0; i < 20; i++) {
            renderer.render(packet);
        }
        glFinish();
        double[] prepare = new double[25];
        double[] cpu = new double[25];
        for (int i = 0; i < prepare.length; i++) {
            long t0 = threads.getCurrentThreadCpuTime();
            renderer.render(packet);
            cpu[i] = (threads.getCurrentThreadCpuTime() - t0) / 1e6;
            prepare[i] = renderer.prepareNanos() / 1e6;
            glFinish();
        }
        java.util.Arrays.sort(prepare);
        java.util.Arrays.sort(cpu);
        return new double[] {prepare[prepare.length / 2], cpu[cpu.length / 2]};
    }
}
