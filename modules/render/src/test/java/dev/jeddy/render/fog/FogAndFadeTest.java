package dev.jeddy.render.fog;

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

/** Fog of war and camera fade (TASKS M7-10). */
@Tag("gpu")
class FogAndFadeTest {
    private static final int SIZE = 128;
    private static final Arena ARENA = Arena.ofShared();
    private static GpuTestContext context;
    private static GpuDevice device;
    private static JobSystem jobs;
    private static UploadRing upload;
    private static MeshRegistry meshes;
    private static ProgramCache programs;
    private static SceneRenderer renderer;
    private static int ground;
    private static int wall;
    private static int sphere;
    private static int white;
    private static int red;
    private static MemorySegment pixels;
    private final RenderPacket packet = new RenderPacket(64);

    @BeforeAll
    static void init() {
        context = new GpuTestContext("fog test", SIZE);
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
                                        .withBloom(0, 0)
                                        .withAutoExposure(null)
                                        .withFxaa(false));
        renderer = new SceneRenderer(device, programs, meshes, jobs, config);
        renderer.resize(SIZE, SIZE);
        upload.beginFrame();
        ground = meshes.add(TestMeshes.plane(ARENA, 4));
        wall = meshes.add(TestMeshes.plane(ARENA, 1.2f));
        sphere = meshes.add(TestMeshes.sphere(ARENA, 0.3f, 24, 12));
        upload.endFrame();
        white = material(1, 1, 1);
        red = material(1, 0, 0);
        pixels = ARENA.allocate((long) SIZE * SIZE * 16, 16);
    }

    private static int material(float r, float g, float b) {
        int m =
                renderer.materials()
                        .add(
                                new MaterialData.Builder()
                                        .baseColor(r, g, b, 1)
                                        .metallicRoughness(0, 0.9f)
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
        packet.fogOfWar = null;
        lookFrom(0, 4, 3);
        packet.near = 0.1f;
        packet.far = 50f;
        packet.alpha = 1;
        packet.sunColor[0] = packet.sunColor[1] = packet.sunColor[2] = 0;
        renderer.environment().setUniform(1, 1, 1);
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

    private int add(int mesh, float[] world, int material, int flags, float radius) {
        int i = packet.add(world, 0, world, 0, mesh, material, flags, 0);
        packet.setBounds(i, world[3], world[7], world[11], radius);
        return i;
    }

    private void addGround() {
        float[] world = new float[12];
        AffineOps.identity(world, 0);
        add(ground, world, white, 0, 2.9f);
    }

    private void render() {
        renderer.render(packet);
        glGetTextureImage(
                renderer.hdrTexture(), 0, GL_RGBA, GL_FLOAT, (int) pixels.byteSize(), pixels);
    }

    private float[] at(float x, float y, float z) {
        float[] ndc = new float[3];
        Mat4Ops.project(packet.viewProjection, 0, x, y, z, ndc, 0);
        int px = (int) ((ndc[0] * 0.5f + 0.5f) * SIZE);
        int py = (int) ((ndc[1] * 0.5f + 0.5f) * SIZE);
        return pixel(px, py);
    }

    private static float[] pixel(int px, int py) {
        float[] c = new float[3];
        for (int i = 0; i < 3; i++) {
            c[i] = pixels.getAtIndex(ValueLayout.JAVA_FLOAT, ((long) py * SIZE + px) * 4 + i);
        }
        return c;
    }

    @Test
    void fogDimsExploredAndHidesUnexploredGround() {
        addGround();
        // 2×2 grid over [-2, 2]²: visible (x < 0, z < 0), explored (x > 0, z < 0), unexplored
        // (z > 0).
        byte[] cells = {(byte) 255, (byte) 128, 0, 0};
        packet.setFogOfWar(cells, 2, 2, -2, -2, 4, 4, 1);
        packet.fogExploredBrightness = 0.4f;
        render();
        float visible = at(-1.2f, 0, -1.2f)[0];
        float explored = at(1.2f, 0, -1.2f)[0];
        float unexplored = at(0, 0, 1.2f)[0];
        assertEquals(1f, visible, 0.12f, "visible ground is lit normally");
        assertEquals(0.4f * visible, explored, 0.06f, "explored ground is dimmed");
        assertTrue(unexplored < 0.02f, "unexplored ground is black: " + unexplored);
        // Without a grid there is no fog.
        packet.fogOfWar = null;
        render();
        assertEquals(visible, at(0, 0, 1.2f)[0], 0.05f, "fog off");
    }

    @Test
    void exploredColorsAreDesaturated() {
        float[] world = new float[12];
        AffineOps.identity(world, 0);
        add(ground, world, red, 0, 2.9f);
        byte[] cells = {(byte) 128, (byte) 128, (byte) 128, (byte) 128};
        packet.setFogOfWar(cells, 2, 2, -2, -2, 4, 4, 2);
        packet.fogExploredBrightness = 1;
        packet.fogExploredSaturation = 0;
        render();
        float[] c = at(0, 0, 0);
        assertEquals(c[0], c[1], 0.03f, "grey: " + c[0] + " " + c[1]);
        assertTrue(c[0] > 0.1f, "luma kept: " + c[0]);
    }

    @Test
    void wallsInFrontOfThePartyDissolveAroundIt() {
        lookFrom(0, 2, 4); // low enough for the wall to hide the sphere
        addGround();
        float[] s = new float[12];
        AffineOps.identity(s, 0);
        s[7] = 0.3f;
        add(sphere, s, red, 0, 0.3f);
        // A wall facing the camera between it and the sphere.
        float[] w = {1, 0, 0, 0, 0, 0, -1, 0.6f, 0, 1, 0, 1.0f};
        int wallIndex = add(wall, w, white, RenderPacket.FLAG_FADE_OCCLUDER, 0.9f);
        render();
        float[] hidden = at(0, 0.3f, 0.3f);
        assertTrue(hidden[1] > 0.8f, "the wall hides the sphere: " + hidden[1]);
        packet.addReveal(0, 0.3f, 0, 0.5f);
        render();
        float[] revealed = at(0, 0.3f, 0.3f);
        assertTrue(
                revealed[1] < 0.15f && revealed[0] > 0.5f,
                "the sphere shows through: " + revealed[0] + " " + revealed[1]);
        // Away from the line of sight the wall stays solid.
        float[] edge = at(0.55f, 0.0f, 1.0f);
        assertTrue(edge[1] > 0.8f, "wall outside the circle: " + edge[1]);
        // Without the occluder flag, reveal points do nothing.
        packet.flags[wallIndex] = 0;
        render();
        assertTrue(at(0, 0.3f, 0.3f)[1] > 0.8f, "unflagged walls stay");
    }

    @Test
    void instanceFadeDithersOutAShareOfPixels() {
        addGround();
        float[] w = {1, 0, 0, 0, 0, 0, -1, 0.6f, 0, 1, 0, 1.0f};
        int i = add(wall, w, red, 0, 0.9f);
        packet.setFade(i, 0.5f);
        render();
        int redPixels = 0;
        int whitePixels = 0;
        int[] c = screen(0, 0.6f, 1.0f);
        for (int y = c[1] - 8; y < c[1] + 8; y++) {
            for (int x = c[0] - 8; x < c[0] + 8; x++) {
                float[] p = pixel(x, y);
                if (p[1] < 0.2f) {
                    redPixels++;
                } else {
                    whitePixels++;
                }
            }
        }
        assertEquals(
                128, redPixels, 8, "half the wall's pixels remain (white " + whitePixels + ")");
        packet.setFade(i, 1);
        render();
        float[] gone = at(0, 0.6f, 1.0f);
        assertTrue(gone[1] > 0.8f, "fully faded: " + gone[1]);
    }

    private int[] screen(float x, float y, float z) {
        float[] ndc = new float[3];
        Mat4Ops.project(packet.viewProjection, 0, x, y, z, ndc, 0);
        return new int[] {
            (int) ((ndc[0] * 0.5f + 0.5f) * SIZE), (int) ((ndc[1] * 0.5f + 0.5f) * SIZE)
        };
    }

    @Test
    void fogAndFadeFramesAllocateNothing() {
        addGround();
        byte[] cells = new byte[64 * 64];
        java.util.Arrays.fill(cells, (byte) 200);
        packet.setFogOfWar(cells, 64, 64, -2, -2, 4, 4, 7);
        float[] w = {1, 0, 0, 0, 0, 0, -1, 0.6f, 0, 1, 0, 1.0f};
        add(wall, w, white, RenderPacket.FLAG_FADE_OCCLUDER, 0.9f);
        packet.addReveal(0, 0.3f, 0, 0.5f);
        render();
        int[] version = {7};
        Allocations.assertNoAllocation(
                "fog frame",
                8,
                10,
                () -> {
                    packet.fogVersion = ++version[0]; // a new grid every frame
                    renderer.render(packet);
                });
    }
}
