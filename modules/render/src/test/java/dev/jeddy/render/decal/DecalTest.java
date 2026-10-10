package dev.jeddy.render.decal;

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
import java.util.Random;

/** Clustered decals: selection circles, AoE templates and textured marks (TASKS M7-07). */
@Tag("gpu")
class DecalTest {
    private static final int SIZE = 160;
    private static final Arena ARENA = Arena.ofShared();
    private static GpuTestContext context;
    private static GpuDevice device;
    private static JobSystem jobs;
    private static UploadRing upload;
    private static MeshRegistry meshes;
    private static ProgramCache programs;
    private static SceneRenderer renderer;
    private static int ground;
    private static int tile;
    private static int white;
    private static MemorySegment pixels;
    private final RenderPacket packet = new RenderPacket(64);

    @BeforeAll
    static void init() {
        context = new GpuTestContext("decal test", SIZE);
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
        ground = meshes.add(TestMeshes.plane(ARENA, 6));
        tile = meshes.add(TestMeshes.plane(ARENA, 0.3f));
        upload.endFrame();
        white =
                renderer.materials()
                        .add(
                                new MaterialData.Builder()
                                        .baseColor(1, 1, 1, 1)
                                        .metallicRoughness(0, 0.9f)
                                        .build());
        renderer.materials().compileBlocking(white);
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
        Mat4Ops.lookAt(0, 6, 4, 0, 0, 0, 0, 1, 0, view, 0);
        Mat4Ops.perspectiveReversedZ((float) Math.toRadians(40), 1, 0.1f, proj, 0);
        Mat4Ops.mul(proj, 0, view, 0, vp, 0);
        Mat4Ops.invert(vp, 0, inv, 0);
        float[] planes = new float[CullOps.PLANES_FLOATS];
        CullOps.extractPlanes(vp, 0, planes);
        packet.setCamera(view, proj, vp, inv, planes, 0, 6, 4);
        packet.near = 0.1f;
        packet.far = 50f;
        packet.alpha = 1;
        packet.sunColor[0] = packet.sunColor[1] = packet.sunColor[2] = 0;
        renderer.environment().setUniform(1, 1, 1);
        addPlane(ground, 0, 0, 0, 0);
    }

    private void addPlane(int mesh, float x, float y, float z, int flags) {
        float[] world = new float[12];
        AffineOps.identity(world, 0);
        world[3] = x;
        world[7] = y;
        world[11] = z;
        int i = packet.add(world, 0, world, 0, mesh, white, flags, 0);
        packet.setBounds(i, x, y, z, mesh == ground ? 4.3f : 0.25f);
    }

    private void render() {
        renderer.render(packet);
        glGetTextureImage(
                renderer.hdrTexture(), 0, GL_RGBA, GL_FLOAT, (int) pixels.byteSize(), pixels);
    }

    /** HDR color at the window position of world point (x, y, z). */
    private float[] at(float x, float y, float z) {
        float[] ndc = new float[3];
        Mat4Ops.project(packet.viewProjection, 0, x, y, z, ndc, 0);
        int px = (int) ((ndc[0] * 0.5f + 0.5f) * SIZE);
        int py = (int) ((ndc[1] * 0.5f + 0.5f) * SIZE);
        assertTrue(px >= 0 && px < SIZE && py >= 0 && py < SIZE, "on screen");
        float[] c = new float[3];
        for (int i = 0; i < 3; i++) {
            c[i] = pixels.getAtIndex(ValueLayout.JAVA_FLOAT, ((long) py * SIZE + px) * 4 + i);
        }
        return c;
    }

    private static String rgb(float[] c) {
        return String.format("(%.3f, %.3f, %.3f)", c[0], c[1], c[2]);
    }

    @Test
    void selectionCirclesGlowOnTheGroundButNotOnFlaggedObjects() {
        packet.addSelectionCircle(0, 0, 0, 1, 0, 2, 0, 1);
        addPlane(tile, 0.92f, 0.2f, 0, RenderPacket.FLAG_NO_DECALS); // a character's foot
        addPlane(tile, -0.92f, 0.2f, 0, 0); // a crate, which takes the decal
        render();
        float[] ring = at(0, 0, 0.92f);
        assertTrue(ring[1] > ring[0] + 1.5f, "ring glows green " + rgb(ring));
        float[] inside = at(0, 0, 0.4f);
        assertEquals(inside[0], inside[1], 0.05f, "nothing inside the ring " + rgb(inside));
        float[] outside = at(0, 0, 1.3f);
        assertEquals(outside[0], outside[1], 0.05f, "nothing outside " + rgb(outside));
        float[] flagged = at(0.92f, 0.2f, 0);
        assertEquals(flagged[0], flagged[1], 0.05f, "FLAG_NO_DECALS " + rgb(flagged));
        float[] crate = at(-0.92f, 0.2f, 0);
        assertTrue(crate[1] > crate[0] + 1.5f, "other surfaces in the box take it " + rgb(crate));
        // Ground lit by the uniform environment is about 1.
        assertEquals(1f, inside[0], 0.12f);
    }

    @Test
    void areaTemplatesTintTheirShapes() {
        int disc = packet.addAoeCircle(-1.5f, 0, -1, 0.6f, 1, 0, 0, 1);
        packet.setDecalEmission(disc, 0); // a painted tint, lit like the ground
        int cone = packet.addAoeCone(1, 0, -1.2f, 0, 1.2f, (float) Math.toRadians(30), 1, 0, 0, 1);
        packet.setDecalEmission(cone, 0);
        int line = packet.addAoeLine(-1, 1.2f, 1, 1.2f, 0, 0.4f, 1, 0, 0, 1);
        packet.setDecalEmission(line, 0);
        render();
        // Interiors: 30% red over white.
        float[] center = at(-1.5f, 0, -1);
        assertEquals(1f, center[0], 0.12f, "disc fill " + rgb(center));
        assertEquals(0.7f, center[1] / center[0], 0.06f, "disc fill " + rgb(center));
        float[] rim = at(-1.5f + 0.57f, 0, -1);
        assertTrue(rim[1] < rim[0] * 0.25f, "disc outline is solid red " + rgb(rim));
        float[] coneIn = at(1, 0, -0.6f);
        assertEquals(0.7f, coneIn[1] / coneIn[0], 0.06f, "cone axis " + rgb(coneIn));
        float[] coneOut = at(1.5f, 0, -0.8f);
        assertEquals(1f, coneOut[1] / coneOut[0], 0.03f, "beside the cone " + rgb(coneOut));
        float[] behind = at(1, 0, -1.5f);
        assertEquals(1f, behind[1] / behind[0], 0.03f, "behind the apex " + rgb(behind));
        float[] lineIn = at(0.3f, 0, 1.2f);
        assertEquals(0.7f, lineIn[1] / lineIn[0], 0.06f, "line " + rgb(lineIn));
        float[] lineOut = at(0.3f, 0, 1.6f);
        assertEquals(1f, lineOut[1] / lineOut[0], 0.03f, "beside the line " + rgb(lineOut));
        float[] lineEnd = at(1.3f, 0, 1.2f);
        assertEquals(1f, lineEnd[1] / lineEnd[0], 0.03f, "past the line's end " + rgb(lineEnd));
    }

    @Test
    void texturedDecalsUseCopiedAndUploadedLayers() {
        DecalTextures textures = renderer.decalTextures();
        // A small linear red texture copied (and scaled up) on the GPU.
        int red = device.textures().create2D(GL_RGBA8, 16, 16, 1, "red");
        MemorySegment px = ARENA.allocate(16 * 16 * 4);
        for (int i = 0; i < 256; i++) {
            px.set(
                    ValueLayout.JAVA_INT,
                    i * 4L,
                    0xFF0000FF); // RGBA8 little-endian: (255, 0, 0, 255)
        }
        device.textures().uploadLevel(red, 0, px.address(), 1024);
        int redLayer = textures.copy(device.textures().glName(red), -1);
        device.textures().destroy(red);
        // A full-size blue texture uploaded directly.
        int n = textures.size();
        MemorySegment blue = ARENA.allocate((long) n * n * 4);
        for (int i = 0; i < n * n; i++) {
            blue.set(ValueLayout.JAVA_INT, i * 4L, 0xFFFF0000); // (0, 0, 255, 255)
        }
        int blueLayer = textures.add(blue);
        assertEquals(2, textures.count());

        int a =
                packet.addDecal(
                        RenderPacket.DECAL_TEXTURE, -1, 0, 0, 0.5f, 0.5f, 0.5f, 0, 1, 1, 1, 1);
        packet.setDecalTexture(a, redLayer);
        int b =
                packet.addDecal(
                        RenderPacket.DECAL_TEXTURE, 1, 0, 0, 0.5f, 0.5f, 0.5f, 0.3f, 1, 1, 1, 1);
        packet.setDecalTexture(b, blueLayer);
        render();
        float[] r = at(-1, 0, 0);
        assertTrue(r[0] > 0.8f && r[1] < 0.1f && r[2] < 0.1f, "red mark " + rgb(r));
        float[] bl = at(1, 0, 0);
        assertTrue(bl[2] > 0.8f && bl[0] < 0.1f && bl[1] < 0.1f, "blue mark " + rgb(bl));
        float[] plain = at(0, 0, 0);
        assertEquals(plain[0], plain[2], 0.05f, "between the marks " + rgb(plain));
        textures.remove(redLayer);
        textures.remove(blueLayer);
        assertEquals(0, textures.count());
    }

    @Test
    void hundredsOfDecalsAreClusteredWithoutAllocating() {
        Random random = new Random(3);
        for (int i = 0; i < 300; i++) {
            packet.addSelectionCircle(
                    random.nextFloat() * 5 - 2.5f,
                    0,
                    random.nextFloat() * 5 - 2.5f,
                    0.2f,
                    random.nextFloat(),
                    random.nextFloat(),
                    random.nextFloat(),
                    1);
        }
        render();
        MemorySegment count = ARENA.allocate(4);
        glGetNamedBufferSubData(renderer.clusters().itemsGlName(), 0, 4, count);
        int items = count.get(ValueLayout.JAVA_INT, 0);
        assertTrue(items >= 300, "every decal lands in a cluster: " + items);
        for (int y = 0; y < SIZE; y += 9) {
            for (int x = 0; x < SIZE; x += 9) {
                float v = pixels.getAtIndex(ValueLayout.JAVA_FLOAT, ((long) y * SIZE + x) * 4);
                assertTrue(Float.isFinite(v), "finite");
            }
        }
        Allocations.assertNoAllocation("decal frame", 8, 10, () -> renderer.render(packet));
    }
}
