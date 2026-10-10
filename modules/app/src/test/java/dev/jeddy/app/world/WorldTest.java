package dev.jeddy.app.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.AssetManager;
import dev.jeddy.asset.AssetPaths;
import dev.jeddy.asset.AssetType;
import dev.jeddy.asset.Vfs;
import dev.jeddy.asset.format.MeshWriter;
import dev.jeddy.asset.format.SceneData;
import dev.jeddy.core.handle.Handles;
import dev.jeddy.core.job.JobSystem;
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

/** Scene instantiation, renderables and packet extraction of the app {@link World} (M10-02). */
class WorldTest {
    @TempDir Path dir;
    private JobSystem jobs;
    private Vfs vfs;
    private AssetManager assets;
    private AssetType<GpuMesh> meshes;
    private long cube;

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

    /** A rig whose sword hangs off a turned, offset hand, as KayKit characters are built. */
    private SceneData character() {
        SceneData.Builder b = new SceneData.Builder();
        int mesh = b.mesh(cube);
        float s = (float) Math.sin(Math.PI / 4);
        float c = (float) Math.cos(Math.PI / 4);
        int rig = b.node("Rig", -1, -1, -1, -1, trs(0, 0, 0, 0, 0, 0, 1, 1));
        b.node("Body", rig, mesh, -1, -1, trs(0, 0, 0, 0, 0, 0, 1, 1));
        // The hand: 1 m right and 1.5 m up, turned 90° about +Y, scaled 2.
        int hand = b.node("hand.r", rig, -1, -1, -1, trs(1, 1.5f, 0, 0, s, 0, c, 2));
        b.node("1H_Sword", hand, mesh, -1, -1, trs(0, 0, 0.5f, 0, 0, 0, 1, 1));
        b.node("2H_Sword", hand, mesh, -1, -1, trs(0, 0, 0.5f, 0, 0, 0, 1, 1));
        return b.build();
    }

    private static float[] trs(
            float x, float y, float z, float qx, float qy, float qz, float qw, float scale) {
        return new float[] {x, y, z, qx, qy, qz, qw, scale, scale, scale};
    }

    private void loadAll(World world) {
        for (int i = 0; i < 1000 && world.loadingRenderables() > 0; i++) {
            assets.update();
            Thread.onSpinWait();
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
        assertEquals(0, world.loadingRenderables(), "meshes load");
    }

    @Test
    void flattenedCharactersMatchTheirHierarchy() {
        World world = new World(256, meshes);
        World.NodeFlags noAlternatives = (name, flags) -> name.startsWith("2H_") ? -1 : flags;
        int full =
                world.instantiate(
                        character(),
                        5,
                        0,
                        0,
                        0,
                        RenderPacket.FLAG_CASTS_SHADOW,
                        noAlternatives,
                        false);
        int flat =
                world.instantiate(
                        character(),
                        5,
                        0,
                        0,
                        0,
                        RenderPacket.FLAG_CASTS_SHADOW,
                        noAlternatives,
                        true);
        // Hierarchy: root + 5 nodes; flattened: root + the 2 mesh nodes kept.
        assertEquals(1 + 5 + 1 + 2, world.transforms().size());
        assertEquals(4, world.renderableCount(), "two per character, the 2H sword left out");
        world.updateTransforms(jobs);
        loadAll(world);

        RenderPacket p = new RenderPacket(16);
        world.extract(p);
        assertEquals(4, p.count());
        // Instances come in creation order; the sword is the second of each character.
        float[] hierarchy = new float[12];
        float[] flattened = new float[12];
        System.arraycopy(p.currWorld, 12, hierarchy, 0, 12);
        System.arraycopy(p.currWorld, 3 * 12, flattened, 0, 12);
        assertArrayEquals(hierarchy, flattened, 1e-5f, "same sword transform");
        // Row-major 3×4: the sword sits 0.5 m along the hand's +Z, turned to +X, scaled by 2.
        assertEquals(5 + 1 + 1, hierarchy[3], 1e-5f);
        assertEquals(1.5f, hierarchy[7], 1e-5f);
        assertEquals(0, hierarchy[11], 1e-5f);
        assertNotEquals(full, flat);
    }

    @Test
    void staticInstancesComeFirstAndHiddenGroupsDisappear() {
        World world = new World(256, meshes);
        int character = world.instantiate(character(), 0, 0, 0, 0, 0, null, true);
        int wall = world.create(Handles.NULL, 3, 0, 3, 0);
        world.transforms().setStatic(wall, true);
        world.addRenderable(wall, cube, RenderPacket.MESH_MATERIALS, RenderPacket.FLAG_STATIC);
        world.updateTransforms(jobs);
        loadAll(world);

        RenderPacket p = new RenderPacket(16);
        world.extract(p);
        assertEquals(4, p.count());
        assertEquals(wall, p.entity[0], "statics first");
        assertTrue((p.flags[0] & RenderPacket.FLAG_STATIC) != 0);
        int version = p.staticVersion;
        assertEquals(1, p.bounds[1 * 4 + 1], 1e-5f, "bounding sphere centre follows the mesh");
        assertEquals(3.5f, p.bounds[2 * 4 + 1], 1e-5f, "and the hand's scale");

        // Same static set: same version, so cached static draws stay valid.
        world.beginTick();
        world.transforms().setPosition(character, 1, 0, 0);
        world.updateTransforms(jobs);
        p.reset();
        world.extract(p);
        assertEquals(version, p.staticVersion);
        assertEquals(0, p.prevWorld[12 + 3], 1e-5f, "dynamic instances interpolate from last tick");
        assertEquals(1, p.currWorld[12 + 3], 1e-5f);

        // The fog of war hides the character's whole group, but never the statics.
        world.setGroupHidden(character, true);
        p.reset();
        world.extract(p);
        assertEquals(1, p.count());
        world.setGroupHidden(character, false);

        // A new static renderable changes the static set.
        int crate = world.create(Handles.NULL, 6, 0, 3, 0);
        world.addRenderable(crate, cube, RenderPacket.MESH_MATERIALS, RenderPacket.FLAG_STATIC);
        world.updateTransforms(jobs);
        loadAll(world);
        p.reset();
        world.extract(p);
        assertEquals(5, p.count());
        assertNotEquals(version, p.staticVersion);

        world.setGroupHighlight(character, RenderPacket.HIGHLIGHT_SELECTED);
        Allocations.assertNoAllocation(
                "world tick and extract",
                () -> {
                    world.beginTick();
                    world.transforms().setPosition(character, 2, 0, 0);
                    world.updateTransforms(jobs);
                    p.reset();
                    world.extract(p);
                });
        assertEquals(
                RenderPacket.HIGHLIGHT_SELECTED,
                (p.flags[2] >> RenderPacket.HIGHLIGHT_SHIFT) & 0xF,
                "group outline");
    }
}
