package dev.jeddy.render.outline;

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

/** Highlight outlines and x-ray fills (TASKS M7-08). */
@Tag("gpu")
class OutlinesTest {
    private static final int SIZE = 128;
    private static final int C = SIZE / 2;
    private static final Arena ARENA = Arena.ofShared();
    private static GpuTestContext context;
    private static GpuDevice device;
    private static JobSystem jobs;
    private static UploadRing upload;
    private static MeshRegistry meshes;
    private static ProgramCache programs;
    private static SceneRenderer renderer;
    private static int sphere;
    private static int wall;
    private static int grey;
    private static MemorySegment pixels;
    private final RenderPacket packet = new RenderPacket(64);

    @BeforeAll
    static void init() {
        context = new GpuTestContext("outline test", SIZE);
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
        renderer.outlines().compileBlocking();
        upload.beginFrame();
        sphere = meshes.add(TestMeshes.sphere(ARENA, 0.4f, 32, 16));
        wall = meshes.add(TestMeshes.plane(ARENA, 1));
        upload.endFrame();
        grey =
                renderer.materials()
                        .add(
                                new MaterialData.Builder()
                                        .baseColor(0.5f, 0.5f, 0.5f, 1)
                                        .metallicRoughness(0, 0.9f)
                                        .build());
        renderer.materials().compileBlocking(grey);
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
        renderer.environment().setUniform(0.3f, 0.3f, 0.3f);
    }

    private int addSphere() {
        float[] world = new float[12];
        AffineOps.identity(world, 0);
        int i = packet.add(world, 0, world, 0, sphere, grey, 0, 0);
        packet.setBounds(i, 0, 0, 0, 0.4f);
        return i;
    }

    /** A 1 m wall facing the camera covering x in [-1, 0] in front of the sphere. */
    private void addWall() {
        float[] world = {1, 0, 0, -0.5f, 0, 0, -1, 0, 0, 1, 0, 0.6f};
        int i = packet.add(world, 0, world, 0, wall, grey, 0, 0);
        packet.setBounds(i, -0.5f, 0, 0.6f, 0.75f);
    }

    private void render() {
        renderer.render(packet);
        device.state().bindFramebuffer(0);
        glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    }

    private static float[] px(int x, int y) {
        float[] c = new float[3];
        for (int i = 0; i < 3; i++) {
            c[i] = (pixels.get(ValueLayout.JAVA_BYTE, ((long) y * SIZE + x) * 4 + i) & 0xFF) / 255f;
        }
        return c;
    }

    private static String rgb(float[] c) {
        return String.format("(%.3f, %.3f, %.3f)", c[0], c[1], c[2]);
    }

    /** Silhouette radius of the sphere in pixels (≈ 17.7 at this camera). */
    private static final int EDGE = 18;

    /**
     * The pixel of row {@code y} between {@code from} and {@code from + 5 * dir} that is closest to
     * {@code target} (the outline pixel adjacent to the shape).
     */
    private static float[] closest(int from, int dir, int y, float[] target) {
        float[] best = null;
        float bestError = Float.MAX_VALUE;
        for (int k = 0; k <= 5; k++) {
            float[] c = px(from + k * dir, y);
            float e = 0;
            for (int i = 0; i < 3; i++) {
                e += Math.abs(c[i] - target[i]);
            }
            if (e < bestError) {
                bestError = e;
                best = c;
            }
        }
        return best;
    }

    /** Pixels of row {@code y} right of the sphere whose channel {@code c} exceeds {@code min}. */
    private static int countRight(int y, int c, float min) {
        int n = 0;
        for (int x = C + EDGE - 3; x < SIZE; x++) {
            if (px(x, y)[c] > min) {
                n++;
            }
        }
        return n;
    }

    @Test
    void selectedInstancesGetAnOutlineOfTheStyleColor() {
        int i = addSphere();
        render();
        float[] plainOut = px(C + EDGE + 1, C);
        float[] plainIn = px(C, C);
        packet.setHighlight(i, RenderPacket.HIGHLIGHT_SELECTED);
        render();
        assertEquals(1, renderer.outlines().count());
        float[] outline = closest(C + EDGE - 2, 1, C, new float[] {0.35f, 1, 0.45f});
        // Selected: sRGB (0.35, 1, 0.45), fully opaque next to the edge.
        assertEquals(0.35f, outline[0], 0.04f, "outline " + rgb(outline));
        assertEquals(1f, outline[1], 0.04f, "outline " + rgb(outline));
        assertEquals(0.45f, outline[2], 0.04f, "outline " + rgb(outline));
        float[] far = px(C + EDGE + 7, C);
        assertEquals(plainOut[1], far[1], 2f / 255f, "nothing past the width " + rgb(far));
        float[] inside = px(C, C);
        assertEquals(plainIn[1], inside[1], 2f / 255f, "visible interior untouched");
        // Above and below too: the outline goes all the way round.
        int width = countRight(C, 1, 0.9f);
        assertTrue(width >= 1 && width <= 3, "about 2 px wide: " + width);
        float[] up = new float[3];
        float[] down = new float[3];
        for (int k = -2; k <= 3; k++) {
            up[1] = Math.max(up[1], px(C, C + EDGE + k)[1]);
            down[1] = Math.max(down[1], px(C, C - EDGE - k)[1]);
        }
        assertTrue(up[1] > 0.95f && down[1] > 0.95f, "all the way round");
        // Removing the highlight removes the outline.
        packet.setHighlight(i, 0);
        assertEquals(0, packet.flags[i] & RenderPacket.FLAG_HIGHLIGHT);
        render();
        assertEquals(plainOut[1], px(C + EDGE + 1, C)[1], 2f / 255f, "gone");
    }

    @Test
    void hiddenPartsShowAsAnXraySilhouette() {
        int i = addSphere();
        addWall();
        render();
        float[] wallHidingSphere = px(C - 8, C);
        float[] openSphere = px(C + 8, C);
        packet.setHighlight(i, RenderPacket.HIGHLIGHT_HOSTILE);
        render();
        float[] xray = px(C - 8, C);
        // Hostile: red (1, 0.25, 0.2) at 30% over the wall.
        for (int c = 0; c < 3; c++) {
            float hostile = c == 0 ? 1f : c == 1 ? 0.25f : 0.2f;
            float expected = wallHidingSphere[c] * 0.7f + hostile * 0.3f;
            assertEquals(expected, xray[c], 0.03f, "x-ray channel " + c + " " + rgb(xray));
        }
        float[] open = px(C + 8, C);
        assertEquals(openSphere[0], open[0], 2f / 255f, "the visible half has no fill");
        // The outline behind the wall is dimmer (70%) than in the open (100%).
        float[] hiddenEdge = closest(C - EDGE + 2, -1, C, new float[] {1, 0.25f, 0.2f});
        float[] openEdge = closest(C + EDGE - 2, 1, C, new float[] {1, 0.25f, 0.2f});
        assertTrue(openEdge[0] > 0.95f && openEdge[1] < 0.35f, "open outline " + rgb(openEdge));
        assertTrue(
                hiddenEdge[0] > 0.6f && hiddenEdge[0] < openEdge[0] - 0.02f,
                "hidden outline " + rgb(hiddenEdge));
    }

    @Test
    void stylesAreConfigurable() {
        int i = addSphere();
        render();
        float[] before = px(C, C);
        renderer.outlines().setStyle(7, 0, 0, 1, 1, 3, 0.5f, 0, 0);
        packet.setHighlight(i, 7);
        render();
        float[] fill = px(C, C);
        assertEquals(before[0] * 0.5f, fill[0], 0.03f, "half-blue fill " + rgb(fill));
        assertEquals(before[2] * 0.5f + 0.5f, fill[2], 0.03f, "half-blue fill " + rgb(fill));
        int wide = countRight(C, 2, 0.95f);
        assertTrue(wide >= 3 && wide <= 4, "3 px wide: " + wide);
    }

    @Test
    void highlightFramesAllocateNothing() {
        int i = addSphere();
        addWall();
        packet.setHighlight(i, RenderPacket.HIGHLIGHT_HOVER);
        Allocations.assertNoAllocation("outline frame", 8, 10, () -> renderer.render(packet));
    }
}
