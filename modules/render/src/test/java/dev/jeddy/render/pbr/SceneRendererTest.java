package dev.jeddy.render.pbr;

import static dev.jeddy.gl.ffi.GL46.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.format.MaterialData;
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
import dev.jeddy.render.gpu.GpuDevice;
import dev.jeddy.render.gpu.UploadRing;
import dev.jeddy.render.material.MaterialSystem;
import dev.jeddy.render.material.TextureArrays;
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
import java.util.Random;

/** The forward+ PBR frame: BRDF, IBL, sun, clustered lights, textures (TASKS M7-01..03). */
@Tag("gpu")
class SceneRendererTest {
    private static final int SIZE = 128;
    private static final Arena ARENA = Arena.ofShared();
    private static GpuTestContext context;
    private static GpuDevice device;
    private static JobSystem jobs;
    private static UploadRing upload;
    private static MeshRegistry meshes;
    private static ProgramCache programs;
    private static SceneRenderer renderer;
    private static int sphere;
    private static int ground;
    private static MemorySegment pixels;

    private final RenderPacket packet = new RenderPacket(64);

    @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir;

    @BeforeAll
    static void init() {
        context = new GpuTestContext("scene renderer test", SIZE);
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
        renderer = new SceneRenderer(device, programs, meshes, jobs, SceneRenderer.Config.DEFAULT);
        renderer.resize(SIZE, SIZE);
        upload.beginFrame();
        sphere = meshes.add(TestMeshes.sphere(ARENA, 0.4f, 32, 16));
        ground = meshes.add(TestMeshes.plane(ARENA, 6));
        upload.endFrame();
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
    void camera() {
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
    }

    private int addSphere(float x, float y, float z, int material) {
        float[] world = new float[12];
        AffineOps.identity(world, 0);
        world[3] = x;
        world[7] = y;
        world[11] = z;
        int i = packet.add(world, 0, world, 0, sphere, material, RenderPacket.FLAG_CASTS_SHADOW, 0);
        packet.setBounds(i, x, y, z, 0.4f);
        return i;
    }

    private void lookFrom(float ex, float ey, float ez, float tx, float ty, float tz) {
        float[] view = new float[16];
        float[] proj = new float[16];
        float[] vp = new float[16];
        float[] inv = new float[16];
        Mat4Ops.lookAt(ex, ey, ez, tx, ty, tz, 0, 1, 0, view, 0);
        Mat4Ops.perspectiveReversedZ((float) Math.toRadians(40), 1, 0.1f, proj, 0);
        Mat4Ops.mul(proj, 0, view, 0, vp, 0);
        Mat4Ops.invert(vp, 0, inv, 0);
        float[] planes = new float[CullOps.PLANES_FLOATS];
        CullOps.extractPlanes(vp, 0, planes);
        packet.setCamera(view, proj, vp, inv, planes, ex, ey, ez);
    }

    private static int material(float metallic, float roughness, float r, float g, float b) {
        MaterialSystem m = renderer.materials();
        int index =
                m.add(
                        new MaterialData.Builder()
                                .metallicRoughness(metallic, roughness)
                                .baseColor(r, g, b, 1)
                                .build());
        m.compileBlocking(index);
        return index;
    }

    /** Renders and reads the HDR target back. */
    private void render() {
        for (int i = 0; i < 2; i++) {
            renderer.render(packet);
        }
        glGetTextureImage(
                renderer.hdrTexture(), 0, GL_RGBA, GL_FLOAT, (int) pixels.byteSize(), pixels);
    }

    private static float hdr(int x, int y, int c) {
        return pixels.getAtIndex(ValueLayout.JAVA_FLOAT, ((long) y * SIZE + x) * 4 + c);
    }

    /** Window position of a world point. */
    private int[] screen(float x, float y, float z) {
        float[] ndc = new float[3];
        Mat4Ops.project(packet.viewProjection, 0, x, y, z, ndc, 0);
        return new int[] {
            (int) ((ndc[0] * 0.5f + 0.5f) * SIZE), (int) ((ndc[1] * 0.5f + 0.5f) * SIZE)
        };
    }

    @Test
    void whiteFurnaceReflectsTheEnvironment() {
        long errors = GLDebug.errorCount();
        renderer.environment().setUniform(1, 1, 1);
        float[][] params = {{0, 0.2f}, {0, 0.8f}, {1, 0.2f}, {1, 0.8f}};
        for (int i = 0; i < params.length; i++) {
            addSphere(-1.5f + i, 0, 0, material(params[i][0], params[i][1], 1, 1, 1));
        }
        render();
        for (int i = 0; i < params.length; i++) {
            int[] p = screen(-1.5f + i, 0, 0.39f);
            for (int c = 0; c < 3; c++) {
                float v = hdr(p[0], p[1], c);
                assertEquals(
                        1f,
                        v,
                        0.12f,
                        "sphere "
                                + i
                                + " (metallic "
                                + params[i][0]
                                + ", roughness "
                                + params[i][1]
                                + ") channel "
                                + c);
            }
        }
        // Background: the environment itself.
        assertEquals(1f, hdr(2, 2, 0), 0.01f);
        assertEquals(errors, GLDebug.errorCount());
    }

    @Test
    void sunLightsTheFacingSide() {
        renderer.environment().setUniform(0, 0, 0);
        packet.sunDirection[0] = 1;
        packet.sunDirection[1] = 0;
        packet.sunDirection[2] = 0;
        packet.sunColor[0] = packet.sunColor[1] = packet.sunColor[2] = 3;
        addSphere(0, 0, 0, material(0, 0.6f, 1, 1, 1));
        render();
        int[] left = screen(-0.3f, 0, 0.25f);
        int[] right = screen(0.3f, 0, 0.25f);
        float lit = hdr(left[0], left[1], 0);
        float dark = hdr(right[0], right[1], 0);
        assertTrue(lit > 0.5f, "lit side " + lit);
        assertTrue(dark < 0.02f, "dark side " + dark);
    }

    @Test
    void clusteredPointLightsShadeOnlyWhereTheyReach() {
        renderer.environment().setUniform(0, 0, 0);
        addSphere(0, 0, 0, material(0, 0.5f, 1, 1, 1));
        packet.addPointLight(-0.6f, 0, 0.6f, 1.2f, 1, 0, 0, 2);
        render();
        int[] left = screen(-0.3f, 0, 0.25f);
        int[] right = screen(0.3f, 0, 0.25f);
        assertTrue(
                hdr(left[0], left[1], 0) > 0.2f,
                "red light on the left: " + hdr(left[0], left[1], 0));
        assertTrue(hdr(left[0], left[1], 1) < 0.01f, "no green");
        assertTrue(
                hdr(right[0], right[1], 0) < 0.02f,
                "out of range on the right: " + hdr(right[0], right[1], 0));
        int items = clusterItems();
        assertTrue(
                items > 0 && items < 200, "a single small light touches a few clusters: " + items);

        // A light behind the camera is in no cluster.
        packet.lightCount = 0;
        packet.addPointLight(0, 0, 10, 1, 1, 1, 1, 1);
        render();
        assertEquals(0, clusterItems());

        // 1500 lights in front of the camera: assigned within the list capacity, and the image
        // stays finite.
        packet.lightCount = 0;
        Random r = new Random(2);
        for (int i = 0; i < 1500; i++) {
            packet.addPointLight(
                    r.nextFloat() * 4 - 2,
                    r.nextFloat() * 4 - 2,
                    r.nextFloat() * -6,
                    0.5f,
                    r.nextFloat(),
                    r.nextFloat(),
                    r.nextFloat(),
                    0.2f);
        }
        render();
        int many = clusterItems();
        assertTrue(many > 1500, "lights overlap several clusters each: " + many);
        for (int y = 0; y < SIZE; y += 7) {
            for (int x = 0; x < SIZE; x += 7) {
                assertTrue(Float.isFinite(hdr(x, y, 0)), "finite at " + x + "," + y);
            }
        }
    }

    private int clusterItems() {
        MemorySegment out = ARENA.allocate(4);
        glGetNamedBufferSubData(renderer.clusters().itemsGlName(), 0, 4, out);
        return out.get(ValueLayout.JAVA_INT, 0);
    }

    @Test
    void baseColorTexturesComeFromArrayLayers() {
        renderer.environment().setUniform(1, 1, 1);
        MaterialSystem ms = renderer.materials();
        int m = ms.add(new MaterialData.Builder().metallicRoughness(0, 0.9f).build());
        int ref = ms.arrays().allocate(GL_RGBA8, 4, 4, 1);
        MemorySegment green = ARENA.allocate(4 * 4 * 4);
        for (int i = 0; i < 16; i++) {
            green.set(
                    ValueLayout.JAVA_INT,
                    i * 4L,
                    0xFF00FF00); // RGBA8 little-endian: (0, 255, 0, 255)
        }
        device.textures()
                .uploadLayer(
                        ms.arrays().textureHandle(TextureArrays.bucket(ref)),
                        0,
                        TextureArrays.layer(ref),
                        green.address(),
                        64);
        ms.setTexture(m, MaterialData.SLOT_BASE_COLOR, ref, false);
        assertTrue((ms.features(m) & MaterialData.FEATURE_BASE_COLOR_MAP) != 0);
        ms.compileBlocking(m);
        addSphere(0, 0, 0, m);
        render();
        int[] c = screen(0, 0, 0.39f);
        assertTrue(hdr(c[0], c[1], 1) > 0.8f, "green " + hdr(c[0], c[1], 1));
        assertTrue(hdr(c[0], c[1], 0) < 0.15f, "little red " + hdr(c[0], c[1], 0));
        // A second texture of the same size shares the bucket (and the binding set).
        int ref2 = ms.arrays().allocate(GL_RGBA8, 4, 4, 1);
        assertEquals(TextureArrays.bucket(ref), TextureArrays.bucket(ref2));
    }

    @Test
    void framesAllocateNothing() {
        renderer.environment().setUniform(0.5f, 0.5f, 0.5f);
        packet.sunColor[0] = packet.sunColor[1] = packet.sunColor[2] = 2;
        int m = material(0.5f, 0.5f, 0.8f, 0.7f, 0.6f);
        for (int i = 0; i < 9; i++) {
            addSphere(-1 + (i % 3), -1 + i / 3, 0, m);
        }
        for (int i = 0; i < 64; i++) {
            packet.addPointLight(i % 8 - 4, i / 8 - 4, 0.5f, 1.5f, 1, 0.5f, 0.2f, 1);
        }
        for (int i = 0; i < 10; i++) {
            renderer.render(packet);
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < 10; i++) {
            renderer.render(packet);
        }
        glFinish();
        System.out.printf(
                "scene frame (llvmpipe, %d px): %.2f ms%n",
                SIZE * SIZE, (System.nanoTime() - t0) / 10 / 1e6);
        Allocations.assertNoAllocation("scene frame", 5, 10, () -> renderer.render(packet));
    }

    @Test
    void materialsAndTexturesLoadAndReloadThroughTheAssetManager() throws Exception {
        java.nio.file.Path dir = tempDir;
        renderer.environment().setUniform(1, 1, 1);
        java.nio.file.Path cooked =
                java.nio.file.Files.createDirectories(dir.resolve("cooked/tex"));
        java.nio.file.Files.write(
                cooked.resolve("leaf.ktx2"),
                dev.jeddy.render.TestKtx2.solid(8, 8, 0x00FF00FF, true));
        java.nio.file.Files.createDirectories(dir.resolve("cooked/mat"));
        java.nio.file.Files.write(
                dir.resolve("cooked/mat/leaf.jmat"),
                new MaterialData.Builder()
                        .metallicRoughness(0, 0.9f)
                        .texture(
                                MaterialData.SLOT_BASE_COLOR,
                                dev.jeddy.asset.AssetPaths.id("tex/leaf.ktx2"),
                                0)
                        .deriveFeatures()
                        .build()
                        .toBytes());
        try (dev.jeddy.asset.Vfs vfs = new dev.jeddy.asset.Vfs()) {
            vfs.mountDirectory(dir.resolve("cooked"));
            try (dev.jeddy.asset.AssetManager manager =
                    new dev.jeddy.asset.AssetManager(vfs, jobs)) {
                dev.jeddy.render.material.MaterialAssets assets =
                        new dev.jeddy.render.material.MaterialAssets(
                                manager, device, renderer.materials());
                int h = assets.materials().load("mat/leaf.jmat");
                long deadline = System.nanoTime() + 20_000_000_000L;
                while (!assets.materials().isReady(h) || assets.pendingTextures() > 0) {
                    manager.update();
                    manager.pumpUploads(1_000_000_000L);
                    assets.update();
                    assertTrue(System.nanoTime() < deadline, "loading");
                }
                int m = assets.materials().get(h).index;
                assertEquals(m, assets.indexOf(dev.jeddy.asset.AssetPaths.id("mat/leaf.jmat")));
                assertTrue(
                        (renderer.materials().features(m) & MaterialData.FEATURE_BASE_COLOR_MAP)
                                != 0);
                renderer.materials().compileBlocking(m);
                addSphere(0, 0, 0, m);
                render();
                int[] c = screen(0, 0, 0.39f);
                assertTrue(hdr(c[0], c[1], 1) > 0.8f && hdr(c[0], c[1], 0) < 0.15f, "green leaf");

                // Reload the texture as red: the same material index picks it up.
                java.nio.file.Files.write(
                        cooked.resolve("leaf.ktx2"),
                        dev.jeddy.render.TestKtx2.solid(8, 8, 0xFF0000FF, false));
                int tex = assets.textures().find(dev.jeddy.asset.AssetPaths.id("tex/leaf.ktx2"));
                manager.reload(dev.jeddy.asset.AssetPaths.id("tex/leaf.ktx2"));
                while (assets.textures().version(tex) == 0) {
                    manager.update();
                    manager.pumpUploads(1_000_000_000L);
                    assertTrue(System.nanoTime() < deadline, "reloading");
                }
                assets.update();
                render();
                assertTrue(
                        hdr(c[0], c[1], 0) > 0.8f && hdr(c[0], c[1], 1) < 0.15f,
                        "red after reload: " + hdr(c[0], c[1], 0));
                assets.materials().release(h);
                manager.update();
            }
        }
    }

    @Test
    void sunCastsShadowsOnTheGround() {
        renderer.environment().setUniform(0, 0, 0);
        lookFrom(0, 3, 3, 0, -0.4f, 0);
        packet.sunDirection[0] = 1; // 45 degrees: the shadow falls on +x, in view of the camera
        packet.sunDirection[1] = -1;
        packet.sunDirection[2] = 0;
        packet.sunColor[0] = packet.sunColor[1] = packet.sunColor[2] = 2;
        int white = material(0, 0.8f, 1, 1, 1);
        addSphere(0, 0, 0, white);
        float[] world = new float[12];
        AffineOps.identity(world, 0);
        world[7] = -0.4f;
        int g = packet.add(world, 0, world, 0, ground, white, RenderPacket.FLAG_CASTS_SHADOW, 0);
        packet.setBounds(g, 0, -0.4f, 0, 4.3f);
        render();
        assertEquals(3, renderer.sunShadows().cascadeCount());
        int[] under = screen(0.8f, -0.4f, 0.0f);
        int[] open = screen(-1.0f, -0.4f, 0.5f);
        float shadowed = hdr(under[0], under[1], 0);
        float lit = hdr(open[0], open[1], 0);
        assertTrue(lit > 0.3f, "open ground is lit: " + lit);
        assertTrue(
                shadowed < lit * 0.25f,
                "ground under the sphere is in shadow: " + shadowed + " vs " + lit);
        // The top of the sphere faces the sun and is not self-shadowed (no acne).
        int[] top = screen(0, 0.38f, 0.05f);
        assertTrue(hdr(top[0], top[1], 0) > 0.3f, "sphere top lit: " + hdr(top[0], top[1], 0));

        // Without the shadow flag the sphere casts nothing.
        packet.flags[0] = 0;
        render();
        assertTrue(
                hdr(under[0], under[1], 0) > lit * 0.8f,
                "no caster, no shadow: " + hdr(under[0], under[1], 0));
    }

    @Test
    void spotAndPointLightsCastCachedShadows() {
        renderer.environment().setUniform(0, 0, 0);
        lookFrom(0, 3, 3, 0, -0.4f, 0);
        int white = material(0, 0.8f, 1, 1, 1);
        addSphere(0, 0, 0, white); // dynamic caster
        float[] world = new float[12];
        AffineOps.identity(world, 0);
        world[7] = -0.4f;
        int g =
                packet.add(
                        world,
                        0,
                        world,
                        0,
                        ground,
                        white,
                        RenderPacket.FLAG_CASTS_SHADOW | RenderPacket.FLAG_STATIC,
                        0);
        packet.setBounds(g, 0, -0.4f, 0, 4.3f);
        float dx = 2;
        float dy = -2.4f;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        int spot =
                packet.addSpotLight(-1.5f, 2, 0, dx / len, dy / len, 0, 8, 1, 1, 1, 12, 0.6f, 1.0f);
        packet.setLightShadow(spot, 7);
        int[] shadowed = screen(0.6f, -0.4f, 0);
        int[] lit = screen(-0.8f, -0.4f, 0.8f);
        render();
        float s = hdr(shadowed[0], shadowed[1], 0);
        float l = hdr(lit[0], lit[1], 0);
        assertTrue(l > 0.2f, "spot-lit ground: " + l);
        assertTrue(s < l * 0.15f, "spot shadow of the sphere: " + s + " vs " + l);
        int renders = renderer.localShadows().staticRenders();
        assertEquals(1, renders, "one spot view rendered its static casters once");

        // Moving a dynamic caster keeps the static cache.
        packet.currWorld[3] = 3;
        packet.prevWorld[3] = 3;
        packet.bounds[0] = 3;
        render();
        assertEquals(renders, renderer.localShadows().staticRenders());
        // Farther from the light and more grazing, the point gets about 0.55 of the reference.
        float unshadowed = hdr(shadowed[0], shadowed[1], 0);
        assertTrue(
                unshadowed > s * 4 && unshadowed > l * 0.4f,
                "the shadow moved away with the sphere: " + unshadowed + " vs " + s);
        packet.currWorld[3] = 0;
        packet.prevWorld[3] = 0;
        packet.bounds[0] = 0;

        // Moving the light refreshes it; a point light renders all six faces.
        packet.lightCount = 0;
        int point = packet.addPointLight(-1.5f, 2, 0, 8, 1, 1, 1, 12);
        packet.setLightShadow(point, 7);
        render();
        assertEquals(renders + 6, renderer.localShadows().staticRenders());
        float ps = hdr(shadowed[0], shadowed[1], 0);
        float pl = hdr(lit[0], lit[1], 0);
        assertTrue(ps < pl * 0.15f, "point light shadow: " + ps + " vs " + pl);
        render();
        assertEquals(renders + 6, renderer.localShadows().staticRenders(), "cached again");
        packet.staticVersion++;
        render();
        assertEquals(
                renders + 12, renderer.localShadows().staticRenders(), "static geometry changed");
    }
}
