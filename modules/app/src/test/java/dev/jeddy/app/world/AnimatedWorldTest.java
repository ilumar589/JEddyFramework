package dev.jeddy.app.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.animation.Animations;
import dev.jeddy.animation.Clip;
import dev.jeddy.animation.Skeleton;
import dev.jeddy.asset.AssetManager;
import dev.jeddy.asset.AssetPaths;
import dev.jeddy.asset.AssetType;
import dev.jeddy.asset.Vfs;
import dev.jeddy.asset.format.MeshWriter;
import dev.jeddy.asset.format.SceneData;
import dev.jeddy.core.job.JobSystem;
import dev.jeddy.math.AffineOps;
import dev.jeddy.render.RenderPacket;
import dev.jeddy.render.mesh.GpuMesh;
import dev.jeddy.render.mesh.MeshAssets;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Animated scenes in the app {@link World}: skinned renderables, attachments, palettes (M12-05).
 */
class AnimatedWorldTest {
    @TempDir Path dir;
    private JobSystem jobs;
    private Vfs vfs;
    private AssetManager assets;
    private AssetType<GpuMesh> meshes;
    private long cube;

    // Node indices of the test character.
    private static final int HIPS = 1;
    private static final int HAND = 2;
    private static final int SWORD = 4;

    @BeforeEach
    void setUp() throws Exception {
        float[] pos = {-1, 0, -1, 1, 0, -1, 1, 2, 1, -1, 2, 1};
        float[] normals = {0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0};
        float[] tangents = {1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1};
        float[] uv = {0, 0, 1, 0, 1, 1, 0, 1};
        Files.createDirectories(dir.resolve("m"));
        Files.write(
                dir.resolve("m/cube.jmesh"),
                new MeshWriter()
                        .positions(pos)
                        .attributes(normals, tangents, uv, null)
                        .indices(new int[] {0, 1, 2, 0, 2, 3})
                        .materials(0)
                        .toBytes());
        cube = AssetPaths.id("m/cube.jmesh");
        jobs = new JobSystem(1);
        vfs = new Vfs();
        vfs.mountDirectory(dir);
        assets = new AssetManager(vfs, jobs);
        meshes = MeshAssets.registerHeadless(assets);
    }

    @AfterEach
    void tearDown() {
        assets.close();
        vfs.close();
        jobs.close();
    }

    private static float[] trs(
            float x, float y, float z, float qx, float qy, float qz, float qw, float scale) {
        return new float[] {x, y, z, qx, qy, qz, qw, scale, scale, scale};
    }

    /**
     * A KayKit-like rig: joints hips and hand.r (turned 90° about +Y, scaled 2), a socket node
     * under the hand holding a sword, a skinned body and head and a barrel under the non-joint Rig
     * node, and a "wave" clip that raises the hand by 1 m over a second.
     */
    private SceneData character() {
        SceneData.Builder b = new SceneData.Builder();
        int mesh = b.mesh(cube);
        float s = (float) Math.sin(Math.PI / 4);
        float c = (float) Math.cos(Math.PI / 4);
        int rig = b.node("Rig", -1, -1, -1, -1, trs(0, 0, 0, 0, 0, 0, 1, 1));
        int hips = b.node("hips", rig, -1, -1, -1, trs(0, 1, 0, 0, 0, 0, 1, 1));
        int hand = b.node("hand.r", hips, -1, -1, -1, trs(1, 0.5f, 0, 0, s, 0, c, 2));
        int socket = b.node("handslot.r", hand, -1, -1, -1, trs(0, 0.1f, 0, 0, 0, 0, 1, 1));
        b.node("1H_Sword", socket, mesh, -1, -1, trs(0, 0, 0.5f, 0, 0, 0, 1, 1));
        b.node("Body", rig, mesh, -1, 0, trs(0, 0, 0, 0, 0, 0, 1, 1));
        b.node("Head", rig, mesh, -1, 0, trs(0, 0, 0, 0, 0, 0, 1, 1));
        b.node("Barrel", rig, mesh, -1, -1, trs(2, 0, 0, 0, 0, 0, 1, 1));
        // Inverse binds of hips and hand (glTF column-major 4×4).
        float[] world = new float[24];
        AffineOps.composeTRS(0, 1, 0, 0, 0, 0, 1, 1, 1, 1, world, 0);
        float[] local = new float[12];
        AffineOps.composeTRS(1, 0.5f, 0, 0, s, 0, c, 2, 2, 2, local, 0);
        AffineOps.mul(world, 0, local, 0, world, 12);
        float[] ibm = new float[32];
        float[] inv = new float[12];
        for (int j = 0; j < 2; j++) {
            AffineOps.invert(world, j * 12, inv, 0);
            for (int r = 0; r < 3; r++) {
                for (int col = 0; col < 4; col++) {
                    ibm[j * 16 + col * 4 + r] = inv[r * 4 + col];
                }
            }
            ibm[j * 16 + 15] = 1;
        }
        b.skin(new SceneData.Skin(-1, new int[] {hips, hand}, ibm));
        b.animation(
                new SceneData.Animation(
                        "wave",
                        1,
                        List.of(
                                new SceneData.Channel(
                                        hand,
                                        SceneData.PATH_TRANSLATION,
                                        SceneData.INTERPOLATION_LINEAR,
                                        3,
                                        new float[] {0, 1},
                                        new float[] {1, 0.5f, 0, 1, 1.5f, 0}))));
        return b.build();
    }

    private void loadAll(World world) {
        for (int i = 0; i < 1000 && world.loadingRenderables() > 0; i++) {
            assets.update();
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
        assertEquals(0, world.loadingRenderables(), "meshes load");
    }

    private static float[] row(float[] a, int i) {
        return Arrays.copyOfRange(a, i * 12, i * 12 + 12);
    }

    @Test
    void animatedScenesMatchTheirBindPoseAndFollowTheirClips() {
        SceneData scene = character();
        World flat = new World(64, meshes);
        flat.instantiate(scene, 5, 0, 0, 0, RenderPacket.FLAG_CASTS_SHADOW, null, true);
        World world = new World(64, meshes, new Animations(8, 64, 1));
        int root =
                world.instantiateAnimated(scene, 5, 0, 0, 0, RenderPacket.FLAG_CASTS_SHADOW, null);
        int instance = world.animation(root);
        assertEquals(0, instance);
        assertEquals(-1, world.animation(root, 1), "one skin");
        // Root + the four mesh nodes, as direct children.
        assertEquals(5, world.transforms().size());
        assertEquals(4, world.renderableCount());
        int other = world.create(dev.jeddy.core.handle.Handles.NULL, 0, 0, 0, 0);
        assertEquals(-1, world.animation(other), "not animated");
        for (World w : new World[] {flat, world}) {
            w.updateTransforms(jobs);
            loadAll(w);
        }
        RenderPacket expected = new RenderPacket(8);
        RenderPacket p = new RenderPacket(8);
        flat.extract(expected);
        world.extract(p);
        assertEquals(4, p.count());
        // Node order: sword, body, head, barrel. In the bind pose every transform matches the
        // hierarchy.
        for (int k = 0; k < 4; k++) {
            assertArrayEquals(row(expected.currWorld, k), row(p.currWorld, k), 1e-5f, "inst " + k);
        }
        assertEquals(-1, p.palette(0), "the sword is an attachment");
        assertEquals(0, p.palette(1), "the body skins with the first palette");
        assertEquals(0, p.palette(2), "the head shares it");
        assertEquals(-1, p.palette(3), "the barrel is plain");
        assertEquals(2, p.paletteCount, "two joints, added once");
        float[] identity = new float[12];
        AffineOps.identity(identity, 0);
        assertArrayEquals(identity, row(p.palettes, 1), 1e-5f, "bind pose palette");
        // Joints at (0, 1, 0) and (1, 1.5, 0): the body is bounded by them plus the skin margin.
        assertEquals(5.5f, p.bounds[4], 1e-5f);
        assertEquals(1.25f, p.bounds[5], 1e-5f);
        assertEquals((float) Math.hypot(0.5, 0.25) + 0.5f, p.bounds[7], 1e-5f);

        // Waving: half a second raises the hand, and the sword with it, by 0.5 m.
        Clip wave = Clip.bake(scene, "wave", world.animations().skeleton(instance));
        world.animations().playOnce(instance, wave, 0);
        world.updateAnimations(0.5f, null);
        p.reset();
        world.extract(p);
        assertEquals(expected.currWorld[3], p.currWorld[3], 1e-4f);
        assertEquals(expected.currWorld[7] + 0.5f, p.currWorld[7], 1e-4f);
        assertEquals(0.5f, p.palettes[12 + 7], 1e-4f, "the hand's palette moved up");
        assertArrayEquals(row(expected.currWorld, 3), row(p.currWorld, 3), 1e-5f, "barrel");

        // Moving the root between ticks: attachments interpolate with it.
        world.beginTick();
        world.transforms().setPosition(root, 7, 0, 0);
        world.updateTransforms(jobs);
        p.reset();
        world.extract(p);
        assertEquals(p.prevWorld[3] + 2, p.currWorld[3], 1e-4f);
        assertEquals(p.prevWorld[7], p.currWorld[7], 1e-4f);
        assertEquals(7, p.currWorld[12 + 3], 1e-5f, "the skinned body follows the root");
    }

    @Test
    void palettesAreSharedHiddenScenesFreezeAndDestroyCleansUp() {
        SceneData scene = character();
        Animations animations = new Animations(8, 64, 1);
        World world = new World(64, meshes, animations);
        World.NodeFlags all = (name, flags) -> flags;
        int a = world.instantiateAnimated(scene, 0, 0, 0, 0, 0, all);
        int b = world.instantiateAnimated(scene, 3, 0, 0, 0, RenderPacket.FLAG_STATIC, all);
        assertSame(
                animations.skeleton(world.animation(a)),
                animations.skeleton(world.animation(b)),
                "one skeleton per scene");
        world.updateTransforms(jobs);
        loadAll(world);
        RenderPacket p = new RenderPacket(8);
        world.extract(p);
        assertEquals(8, p.count());
        assertEquals(4, p.paletteCount, "one palette per animated instance");
        for (int k = 0; k < p.count(); k++) {
            assertEquals(0, p.flags[k] & RenderPacket.FLAG_STATIC, "animated is never static");
        }

        world.setGroupHidden(a, true);
        assertFalse(animations.isActive(world.animation(a)));
        p.reset();
        world.extract(p);
        assertEquals(4, p.count());
        assertEquals(2, p.paletteCount);
        assertEquals(0, p.palette(1), "the visible scene's palette comes first");
        world.setGroupHidden(a, false);
        assertTrue(animations.isActive(world.animation(a)));

        Clip wave = Clip.bake(scene, "wave", animations.skeleton(world.animation(b)));
        animations.play(world.animation(b), wave, 0.1f);
        Allocations.assertNoAllocation(
                "animated world frame",
                () -> {
                    world.beginTick();
                    world.updateTransforms(jobs);
                    world.updateAnimations(1 / 60f, null);
                    p.reset();
                    world.extract(p);
                });

        int instance = world.animation(b);
        world.destroy(b);
        assertFalse(animations.isAlive(instance));
        assertEquals(1, animations.count());
        assertEquals(4, world.renderableCount());
        assertEquals(-1, world.animation(b));
        p.reset();
        world.extract(p);
        assertEquals(4, p.count());

        World plain = new World(8, meshes);
        assertThrows(
                IllegalStateException.class,
                () -> plain.instantiateAnimated(scene, 0, 0, 0, 0, 0, null));
        assertEquals(2, Skeleton.of(scene, 0).jointCount());
    }
}
