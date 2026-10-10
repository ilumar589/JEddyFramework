package dev.jeddy.render.particle;

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

/** GPU particles: spawning, aging, forces, drawing (TASKS M7-09). */
@Tag("gpu")
class ParticlesTest {
    private static final int SIZE = 128;
    private static final int CAPACITY = 4096;
    private static final Arena ARENA = Arena.ofShared();
    private static GpuTestContext context;
    private static GpuDevice device;
    private static JobSystem jobs;
    private static UploadRing upload;
    private static MeshRegistry meshes;
    private static ProgramCache programs;
    private static SceneRenderer renderer;
    private static GpuParticles particles;
    private static int sphere;
    private static int black;
    private static MemorySegment pixels;
    private final RenderPacket packet = new RenderPacket(64);

    @BeforeAll
    static void init() {
        context = new GpuTestContext("particle test", SIZE);
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
                        .withParticleCapacity(CAPACITY)
                        .withPost(
                                PostProcess.Config.DEFAULT
                                        .withBloom(0, 0)
                                        .withAutoExposure(null)
                                        .withFxaa(false));
        renderer = new SceneRenderer(device, programs, meshes, jobs, config);
        renderer.resize(SIZE, SIZE);
        particles = renderer.particles();
        particles.compileBlocking();
        upload.beginFrame();
        sphere = meshes.add(TestMeshes.sphere(ARENA, 0.4f, 32, 16));
        upload.endFrame();
        black =
                renderer.materials()
                        .add(
                                new MaterialData.Builder()
                                        .baseColor(0, 0, 0, 1)
                                        .metallicRoughness(0, 1)
                                        .build());
        renderer.materials().compileBlocking(black);
        pixels = ARENA.allocate((long) SIZE * SIZE * 16, 16);
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
        renderer.environment().setUniform(0, 0, 0);
        // Let everything from earlier tests die (a frame advances time by at most a second).
        for (int i = 0; i < 12; i++) {
            packet.time += 1;
            renderer.render(packet);
        }
        int[] c = particles.readCounters();
        assertEquals(CAPACITY, c[0], "all dead before the test");
    }

    private int alive() {
        int[] c = particles.readCounters();
        return CAPACITY - c[0];
    }

    private void frame(float seconds) {
        packet.time += seconds;
        renderer.render(packet);
    }

    @Test
    void burstsSpawnOncePerGameFrameAndRatesPerSecond() {
        packet.frame = 1;
        int e = packet.addEmitter(0, 0, 0, 0, 100);
        packet.setEmitterLifetime(e, 10, 0);
        frame(0.01f);
        assertEquals(100, alive());
        frame(0.01f); // the same game frame rendered again: no second burst
        assertEquals(100, alive());
        packet.frame = 2;
        packet.emitters[e * RenderPacket.EMITTER_FLOATS + 21] = 50;
        frame(0.01f);
        assertEquals(150, alive());
        // 200 per second for 0.25 s: 50 more.
        packet.emitters[e * RenderPacket.EMITTER_FLOATS + 20] = 200;
        frame(0.25f);
        assertEquals(200, alive());
        int[] c = particles.readCounters();
        assertEquals(200, c[1 + particles.drawnList()], "the drawn list holds the living");
    }

    @Test
    void particlesDieAtTheEndOfTheirLifetime() {
        packet.frame = 10;
        int e = packet.addEmitter(0, 0, 0, 0, 300);
        packet.setEmitterLifetime(e, 0.5f, 0);
        frame(0.01f);
        assertEquals(300, alive());
        frame(0.3f);
        assertEquals(300, alive());
        frame(0.3f);
        assertEquals(0, alive(), "all returned to the pool");
        // The pool caps spawning.
        packet.frame = 11;
        packet.emitters[e * RenderPacket.EMITTER_FLOATS + 21] = CAPACITY + 500;
        frame(0.01f);
        assertEquals(CAPACITY, alive());
    }

    @Test
    void gravityAndDragMoveParticles() {
        packet.frame = 20;
        int e = packet.addEmitter(0, 1, 0, 0, 64);
        packet.setEmitterRadius(e, 0);
        packet.setEmitterVelocity(e, 2, 0, 0, 0);
        packet.setEmitterLifetime(e, 10, 0);
        packet.setEmitterForces(e, 1, 0);
        frame(0.01f);
        for (int i = 0; i < 10; i++) {
            frame(0.05f);
        }
        // After ~0.51 s: y ≈ 1 - 0.5 g t², x ≈ 2 t.
        float[] avg = averagePosition();
        float t = 0.51f;
        assertEquals(1 - 0.5f * 9.81f * t * t, avg[1], 0.15f, "falls");
        assertEquals(2 * t, avg[0], 0.1f, "moves sideways");
    }

    private float[] averagePosition() {
        int[] c = particles.readCounters();
        int list = particles.drawnList();
        int n = c[1 + list];
        MemorySegment indices = ARENA.allocate((long) n * 4);
        glGetNamedBufferSubData(
                particles.listsGlName(), 64 + (long) CAPACITY * 4 * (1 + list), n * 4L, indices);
        MemorySegment pool = ARENA.allocate((long) CAPACITY * GpuParticles.PARTICLE_BYTES);
        glGetNamedBufferSubData(particles.particlesGlName(), 0, pool.byteSize(), pool);
        float[] sum = new float[3];
        for (int i = 0; i < n; i++) {
            long p =
                    indices.getAtIndex(ValueLayout.JAVA_INT, i)
                            * (long) GpuParticles.PARTICLE_BYTES;
            for (int k = 0; k < 3; k++) {
                sum[k] += pool.get(ValueLayout.JAVA_FLOAT, p + k * 4L) / n;
            }
        }
        return sum;
    }

    @Test
    void additiveSpritesGlowAndHideBehindOpaqueSurfaces() {
        // A black sphere at the origin; glowing particles in front on the left and behind on the
        // right.
        float[] world = new float[12];
        AffineOps.identity(world, 0);
        int s = packet.add(world, 0, world, 0, sphere, black, 0, 0);
        packet.setBounds(s, 0, 0, 0, 0.4f);
        packet.frame = 30;
        int front = packet.addEmitter(-0.15f, 0, 1.0f, 0, 400);
        int back = packet.addEmitter(0.15f, 0, -1.0f, 0, 400);
        for (int e : new int[] {front, back}) {
            packet.setEmitterRadius(e, 0.05f);
            packet.setEmitterVelocity(e, 0, 0, 0, 0);
            packet.setEmitterLifetime(e, 10, 0);
            packet.setEmitterColors(e, 0.5f, 0.1f, 0, 0, 0.5f, 0.1f, 0, 0);
            packet.setEmitterSize(e, 0.08f, 0.08f);
        }
        frame(0.01f);
        glGetTextureImage(
                renderer.hdrTexture(), 0, GL_RGBA, GL_FLOAT, (int) pixels.byteSize(), pixels);
        float lit = brightestRed(SIZE / 2 - 20, SIZE / 2);
        float hidden = brightestRed(SIZE / 2 + 2, SIZE / 2 + 12);
        assertTrue(lit > 1f, "additive particles in front glow: " + lit);
        assertTrue(hidden < 0.01f, "particles behind the sphere are hidden: " + hidden);
    }

    private static float brightestRed(int x0, int x1) {
        float best = 0;
        for (int y = SIZE / 2 - 6; y <= SIZE / 2 + 6; y++) {
            for (int x = x0; x < x1; x++) {
                best =
                        Math.max(
                                best,
                                pixels.getAtIndex(
                                        ValueLayout.JAVA_FLOAT, ((long) y * SIZE + x) * 4));
            }
        }
        return best;
    }

    @Test
    void particleFramesAllocateNothing() {
        packet.frame = 40;
        int e = packet.addEmitter(0, 0, 0, 500, 100);
        packet.setEmitterForces(e, 0.5f, 0.3f);
        for (int i = 0; i < 5; i++) {
            frame(0.016f);
        }
        assertTrue(alive() > 100);
        Allocations.assertNoAllocation("particle frame", 8, 10, () -> frame(0.016f));
    }
}
