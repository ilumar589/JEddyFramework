package dev.jeddy.render.pbr;

import static dev.jeddy.gl.ffi.GL46.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.format.MaterialData;
import dev.jeddy.asset.format.MeshData;
import dev.jeddy.asset.format.MeshWriter;
import dev.jeddy.asset.shader.ShaderSources;
import dev.jeddy.core.job.JobSystem;
import dev.jeddy.gl.GLDebug;
import dev.jeddy.math.AffineOps;
import dev.jeddy.math.CullOps;
import dev.jeddy.math.Mat4Ops;
import dev.jeddy.render.GpuTestContext;
import dev.jeddy.render.RenderPacket;
import dev.jeddy.render.SceneRenderer;
import dev.jeddy.render.TestMeshes;
import dev.jeddy.render.draw.MaterialTable;
import dev.jeddy.render.gpu.GpuDevice;
import dev.jeddy.render.gpu.UploadRing;
import dev.jeddy.render.material.MaterialSystem;
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

/**
 * GPU skinning (TASKS M12-04): a two-joint strip bent 90° at its middle through the lit, depth
 * prepass, sun shadow and outline programs.
 */
@Tag("gpu")
class SkinningTest {
    private static final int SIZE = 128;
    private static final Arena ARENA = Arena.ofShared();
    private static GpuTestContext context;
    private static GpuDevice device;
    private static JobSystem jobs;
    private static UploadRing upload;
    private static MeshRegistry meshes;
    private static ProgramCache programs;
    private static SceneRenderer renderer;
    private static int strip;
    private static int wall;
    private static int orange;
    private static int white;
    private static MemorySegment hdrPixels;
    private static MemorySegment windowPixels;

    /** Joint 0 stays put; joint 1 (at y = 0.5) turns -90° about Z: the top half points to +X. */
    private static final float[] BENT = new float[24];

    private final RenderPacket packet = new RenderPacket(16);

    static {
        AffineOps.identity(BENT, 0);
        // T(0, 0.5, 0) Rz(-90°) T(0, -0.5, 0): (x, y) -> (y - 0.5, 0.5 - x).
        float[] m = {0, 1, 0, -0.5f, -1, 0, 0, 0.5f, 0, 0, 1, 0};
        System.arraycopy(m, 0, BENT, 12, 12);
    }

    @BeforeAll
    static void init() {
        context = new GpuTestContext("skinning test", SIZE);
        device = new GpuDevice(context.caps());
        device.useReversedZ();
        jobs = new JobSystem(2);
        upload = new UploadRing(device, 1 << 20, 1 << 20);
        meshes = new MeshRegistry(device, upload, 1 << 12, 1 << 12);
        programs =
                new ProgramCache(
                        ShaderSources.classpath(MeshRegistry.class, "/dev/jeddy/render/shaders"),
                        context.caps(),
                        null);
        SceneRenderer.Config config =
                SceneRenderer.Config.DEFAULT
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
        wall = meshes.add(TestMeshes.plane(ARENA, 6));
        assertTrue(!meshes.hasSkins(), "no skin buffer before the first skinned mesh");
        strip = meshes.add(strip());
        upload.endFrame();
        MaterialSystem m = renderer.materials();
        orange =
                m.add(
                        new MaterialData.Builder()
                                .baseColor(0, 0, 0, 1)
                                .emissive(1, 0.4f, 0.1f, 1)
                                .metallicRoughness(0, 1)
                                .build());
        white =
                m.add(
                        new MaterialData.Builder()
                                .baseColor(0.8f, 0.8f, 0.8f, 1)
                                .metallicRoughness(0, 0.9f)
                                .build());
        m.compileBlocking(orange);
        m.compileBlockingSkinned(orange);
        m.compileBlocking(white);
        hdrPixels = ARENA.allocate((long) SIZE * SIZE * 16, 16);
        windowPixels = ARENA.allocate((long) SIZE * SIZE * 4, 16);
    }

    /**
     * A 0.2 m wide strip from y = 0 to 1 facing +Z: three rows of two vertices, the bottom row on
     * joint 0, the top row on joint 1 and the middle row half on each.
     */
    private static MeshData strip() {
        float[] pos = new float[18];
        float[] nrm = new float[18];
        float[] tan = new float[24];
        float[] uv = new float[12];
        int[] joints = new int[24];
        float[] weights = new float[24];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 2; c++) {
                int v = r * 2 + c;
                pos[v * 3] = c == 0 ? -0.1f : 0.1f;
                pos[v * 3 + 1] = 0.5f * r;
                nrm[v * 3 + 2] = 1;
                tan[v * 4] = 1;
                tan[v * 4 + 3] = 1;
                uv[v * 2] = c;
                uv[v * 2 + 1] = r / 2f;
                joints[v * 4] = 0;
                joints[v * 4 + 1] = 1;
                weights[v * 4] = 1 - r / 2f;
                weights[v * 4 + 1] = r / 2f;
            }
        }
        int[] idx = {0, 1, 3, 0, 3, 2, 2, 3, 5, 2, 5, 4};
        byte[] bytes =
                new MeshWriter()
                        .positions(pos)
                        .attributes(nrm, tan, uv, null)
                        .skin(joints, weights)
                        .indices(idx)
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
    void scene() {
        packet.reset();
        float[] view = new float[16];
        float[] proj = new float[16];
        float[] vp = new float[16];
        float[] inv = new float[16];
        Mat4Ops.lookAt(0, 0.5f, 4, 0, 0.5f, 0, 0, 1, 0, view, 0);
        Mat4Ops.perspectiveReversedZ((float) Math.toRadians(40), 1, 0.1f, proj, 0);
        Mat4Ops.mul(proj, 0, view, 0, vp, 0);
        Mat4Ops.invert(vp, 0, inv, 0);
        float[] planes = new float[CullOps.PLANES_FLOATS];
        CullOps.extractPlanes(vp, 0, planes);
        packet.setCamera(view, proj, vp, inv, planes, 0, 0.5f, 4);
        packet.near = 0.1f;
        packet.far = 20f;
        packet.alpha = 1;
        // The sun travels towards +X and into the screen: shadows land right of their casters.
        packet.sunDirection[0] = 0.6f;
        packet.sunDirection[1] = 0;
        packet.sunDirection[2] = -1;
        packet.sunColor[0] = packet.sunColor[1] = packet.sunColor[2] = 2;
        renderer.environment().setUniform(0.05f, 0.05f, 0.05f);
        // A lit wall 1 m behind the strip, facing the camera.
        float[] w = {1, 0, 0, 0, 0, 0, -1, 0.5f, 0, 1, 0, -1};
        int i = packet.add(w, 0, w, 0, wall, white, RenderPacket.FLAG_CASTS_SHADOW, 0);
        packet.setBounds(i, 0, 0.5f, -1, 4.3f);
    }

    private int addStrip(boolean bent) {
        float[] world = new float[12];
        AffineOps.identity(world, 0);
        int i =
                packet.add(
                        world,
                        0,
                        world,
                        0,
                        strip,
                        orange,
                        RenderPacket.FLAG_CASTS_SHADOW | RenderPacket.FLAG_STATIC,
                        1);
        packet.setBounds(i, 0, 0.5f, 0, 1);
        if (bent) {
            int base = packet.addPalette(BENT, 0, 2);
            packet.setPalette(i, base);
        }
        return i;
    }

    private void render() {
        for (int k = 0; k < 2; k++) {
            renderer.render(packet);
        }
        glGetTextureImage(
                renderer.hdrTexture(), 0, GL_RGBA, GL_FLOAT, (int) hdrPixels.byteSize(), hdrPixels);
        device.state().bindFramebuffer(0);
        glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_UNSIGNED_BYTE, windowPixels);
    }

    /** HDR color at the pixel where world point {@code (x, y, z)} lands. */
    private float[] hdrAt(float x, float y, float z) {
        int[] p = screen(x, y, z);
        float[] c = new float[3];
        for (int k = 0; k < 3; k++) {
            c[k] =
                    hdrPixels.getAtIndex(
                            ValueLayout.JAVA_FLOAT, ((long) p[1] * SIZE + p[0]) * 4 + k);
        }
        return c;
    }

    private static float[] window(int x, int y) {
        float[] c = new float[3];
        for (int k = 0; k < 3; k++) {
            c[k] =
                    (windowPixels.get(ValueLayout.JAVA_BYTE, ((long) y * SIZE + x) * 4 + k) & 0xFF)
                            / 255f;
        }
        return c;
    }

    private int[] screen(float x, float y, float z) {
        float[] ndc = new float[3];
        Mat4Ops.project(packet.viewProjection, 0, x, y, z, ndc, 0);
        int[] p = {(int) ((ndc[0] * 0.5f + 0.5f) * SIZE), (int) ((ndc[1] * 0.5f + 0.5f) * SIZE)};
        assertTrue(p[0] >= 0 && p[0] < SIZE && p[1] >= 0 && p[1] < SIZE, "on screen");
        return p;
    }

    private static boolean isOrange(float[] c) {
        return c[0] > 0.6f && c[2] < 0.3f * c[0];
    }

    private static String rgb(float[] c) {
        return String.format("(%.3f, %.3f, %.3f)", c[0], c[1], c[2]);
    }

    @Test
    void skinnedInstancesDrawWithTheSkinnedVariantOfTheirSlot() {
        assertTrue(meshes.isSkinned(strip));
        assertTrue(!meshes.isSkinned(wall));
        assertTrue(meshes.hasSkins());
        MaterialTable table = renderer.materials().table();
        int slot = table.programSlot(orange);
        int skinned = table.skinnedSlot(slot);
        assertNotEquals(slot, skinned);
        assertEquals(table.slotMask(slot) | MaterialTable.SKINNED, table.slotMask(skinned));
        assertEquals(skinned, table.prepareSkinned(slot), "prepared once");
        assertEquals(skinned, table.skinnedSlot(skinned));
        int i = addStrip(true);
        assertEquals(0, packet.flags[i] & RenderPacket.FLAG_STATIC, "skinned is never static");
        assertEquals(0, packet.palette(i));
        renderer.render(packet);
        // One batch for the wall and one for the skinned strip.
        assertEquals(2, renderer.draws().batchCount());
    }

    @Test
    void aBentStripFollowsItsPaletteInEveryPass() {
        long errors = GLDebug.errorCount();
        addStrip(true);
        render();
        float[] lower = hdrAt(0, 0.25f, 0);
        float[] bentArm = hdrAt(0.35f, 0.5f, 0);
        float[] wherePointedUp = hdrAt(0, 0.9f, 0);
        assertTrue(isOrange(lower), "lower half " + rgb(lower));
        assertTrue(isOrange(bentArm), "the top half points right " + rgb(bentArm));
        // Nothing where the unbent top half would be: the lit wall shows, so the depth prepass
        // (which the lit pass tests against) was skinned too.
        assertTrue(!isOrange(wherePointedUp), "no top half " + rgb(wherePointedUp));
        assertTrue(wherePointedUp[1] > 0.3f, "the lit wall " + rgb(wherePointedUp));
        // Shadows on the wall follow the bent shape: the arm's shadow, and none from the top.
        float[] armShadow = hdrAt(0.95f, 0.5f, -1);
        float[] noTopShadow = hdrAt(0.6f, 0.9f, -1);
        assertTrue(
                armShadow[1] < 0.5f * noTopShadow[1],
                "arm shadow " + rgb(armShadow) + " vs lit " + rgb(noTopShadow));
        assertEquals(errors, GLDebug.errorCount());

        // Without its palette the same mesh draws its bind pose, upright.
        scene();
        addStrip(false);
        render();
        assertTrue(isOrange(hdrAt(0, 0.9f, 0)), "upright " + rgb(hdrAt(0, 0.9f, 0)));
        assertTrue(!isOrange(hdrAt(0.35f, 0.5f, 0)), "no arm " + rgb(hdrAt(0.35f, 0.5f, 0)));
        float[] topShadow = hdrAt(0.6f, 0.9f, -1);
        float[] noArmShadow = hdrAt(0.95f, 0.5f, -1);
        assertTrue(
                topShadow[1] < 0.5f * noArmShadow[1],
                "top shadow " + rgb(topShadow) + " vs lit " + rgb(noArmShadow));
        assertEquals(errors, GLDebug.errorCount());
    }

    @Test
    void outlinesFollowTheSkinnedShape() {
        int i = addStrip(true);
        packet.setHighlight(i, RenderPacket.HIGHLIGHT_SELECTED);
        render();
        assertEquals(1, renderer.outlines().count());
        // Green (selected) just right of the arm's tip at (0.5, 0.5), not above the upright top.
        int[] tip = screen(0.5f, 0.5f, 0);
        float best = 0;
        for (int k = 0; k <= 4; k++) {
            best = Math.max(best, window(tip[0] + k, tip[1])[1] - window(tip[0] + k, tip[1])[0]);
        }
        assertTrue(best > 0.4f, "outline past the tip: " + best);
        int[] top = screen(0, 1.0f, 0);
        for (int k = 0; k <= 4; k++) {
            float[] c = window(top[0], top[1] + k);
            assertTrue(c[1] - c[0] < 0.2f, "no outline above the upright top " + rgb(c));
        }
    }

    @Test
    void skinnedFramesAllocateNothing() {
        int i = addStrip(true);
        packet.setHighlight(i, RenderPacket.HIGHLIGHT_HOVER);
        Allocations.assertNoAllocation(
                "skinned frame",
                5,
                10,
                () -> {
                    packet.paletteCount = 0;
                    packet.setPalette(i, packet.addPalette(BENT, 0, 2));
                    renderer.render(packet);
                });
    }
}
