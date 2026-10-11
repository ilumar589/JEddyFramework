package dev.jeddy.app.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.AssetManager;
import dev.jeddy.asset.AssetPaths;
import dev.jeddy.asset.AssetType;
import dev.jeddy.asset.Vfs;
import dev.jeddy.asset.format.LevelData;
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

/**
 * Prefab subtrees and levels in the world (TASKS M14-03): {@code instantiateNode}, {@code
 * destroyGroup}, {@code setGroupTransform} and {@link LevelLoader}.
 */
class LevelLoaderTest {
    @TempDir Path dir;
    private JobSystem jobs;
    private Vfs vfs;
    private AssetManager assets;
    private AssetType<GpuMesh> meshes;
    private AssetType<SceneData> scenes;

    @BeforeEach
    void setUp() throws Exception {
        float[] pos = {-1, 0, -1, 1, 0, -1, 1, 2, 1, -1, 2, 1};
        float[] normals = {0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0};
        float[] tangents = {1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1};
        float[] uv = {0, 0, 1, 0, 1, 1, 0, 1};
        Files.createDirectories(dir.resolve("levels"));
        Files.write(
                dir.resolve("levels/cube.jmesh"),
                new MeshWriter()
                        .positions(pos)
                        .attributes(normals, tangents, uv, null)
                        .indices(new int[] {0, 1, 2, 0, 2, 3})
                        .materials(0)
                        .toBytes());
        // A library of two pieces: "wall" (one mesh) and "table" (a base with a top on it).
        SceneData.Builder b = new SceneData.Builder();
        int mesh = b.mesh(AssetPaths.id("levels/cube.jmesh"));
        int wall = b.node("wall", -1, -1, -1, -1, trs(0, 0, 0, 1));
        b.node("wall_mesh", wall, mesh, -1, -1, trs(0, 1, 0, 1));
        int table = b.node("table", -1, -1, -1, -1, trs(0, 0, 0, 1));
        int base = b.node("table_base", table, mesh, -1, -1, trs(0, 0, 0, 1));
        b.node("table_top", base, mesh, -1, -1, trs(0, 1, 0, 2));
        Files.write(dir.resolve("levels/dungeon.jscene"), b.build().toBytes());
        jobs = new JobSystem(1);
        vfs = new Vfs();
        vfs.mountDirectory(dir);
        assets = new AssetManager(vfs, jobs);
        meshes = MeshAssets.registerHeadless(assets);
        scenes = assets.register("scene", WorldAssets.scenes());
    }

    @AfterEach
    void tearDown() {
        assets.close();
        vfs.close();
        jobs.close();
    }

    private static float[] trs(float x, float y, float z, float scale) {
        return new float[] {x, y, z, 0, 0, 0, 1, scale, scale, scale};
    }

    private static LevelData level() {
        LevelData l = new LevelData();
        int wall = l.prefab("levels/dungeon.jscene", "wall");
        int table = l.prefab("levels/dungeon.jscene", "table");
        l.addPlacement(
                wall, "wall 1", "wall", LevelData.PLACEMENT_STATIC, 4, 0, 0, 0, 0, 0, 1, 1, 1, 1);
        l.addPlacement(table, "table 1", "prop", 0, 0, 0, 6, 0, 0, 0, 1, 1, 1, 1);
        l.addPointLight("torch", 1, 2, 3, 1, 0.5f, 0.2f, 8, 9, LevelData.LIGHT_FLICKER);
        return l;
    }

    private void settle(LevelLoader loader, World world) {
        for (int i = 0; i < 1000 && (!loader.isReady() || world.loadingRenderables() > 0); i++) {
            assets.update();
            loader.update();
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
        assertTrue(loader.isReady(), "the level instantiates");
        assertEquals(0, world.loadingRenderables());
    }

    @Test
    void placementsBecomeGroupsAtTheirTransforms() {
        World world = new World(256, meshes);
        LevelLoader loader = new LevelLoader(world, scenes);
        LevelData level = level();
        loader.load(level);
        settle(loader, world);
        assertEquals(3, world.renderableCount(), "wall + table base + table top");
        world.updateTransforms(jobs);
        RenderPacket p = new RenderPacket(16);
        world.extract(p);
        assertEquals(3, p.count());
        // The wall's mesh sits 1 m up from the placement at x = 4 (row-major 3×4: translation at
        // indices 3, 7, 11).
        assertEquals(4, p.currWorld[3], 1e-5);
        assertEquals(1, p.currWorld[7], 1e-5);
        // The table top: base at z = 6, top 1 m up and scaled 2 relative to the base.
        assertEquals(6, p.currWorld[2 * 12 + 11], 1e-5);
        assertEquals(1, p.currWorld[2 * 12 + 7], 1e-5);
        assertEquals(2, p.currWorld[2 * 12], 1e-5);
        // The mapping both ways.
        int tableRoot = loader.rootOf(level.placementId(1));
        assertNotEquals(Handles.NULL, tableRoot);
        assertEquals(level.placementId(1), loader.placementOf(tableRoot));
        assertEquals(tableRoot, world.groupOf(p.entity[2]));
        // Lights come from the level.
        p.reset();
        loader.extractLights(p, 0, 1);
        assertEquals(1, p.lightCount);
        Allocations.assertNoAllocation(
                "extract lights",
                () -> {
                    p.reset();
                    loader.extractLights(p, 0.5f, 1);
                });
    }

    @Test
    void movingRemovingAndAddingPlacementsFollowsTheLevel() {
        World world = new World(256, meshes);
        LevelLoader loader = new LevelLoader(world, scenes);
        LevelData level = level();
        loader.load(level);
        settle(loader, world);
        // Move the wall to x = 10.
        level.setPlacementFloat(0, 0, 10);
        loader.syncTransform(0);
        world.updateTransforms(jobs);
        RenderPacket p = new RenderPacket(16);
        int v = world.extract(p) >= 0 ? p.staticVersion : 0;
        assertEquals(10, p.currWorld[3], 1e-5);
        world.extract(p);
        // A static group that moved bumps the static version so the renderer rebuilds.
        level.setPlacementFloat(0, 0, 11);
        loader.syncTransform(0);
        world.updateTransforms(jobs);
        world.extract(p);
        assertTrue(p.staticVersion > v);
        // Remove the table: its two entities and renderables go.
        int tableId = level.placementId(1);
        int tableRoot = loader.rootOf(tableId);
        loader.placementRemoving(1);
        level.removePlacement(1);
        assertEquals(1, world.renderableCount());
        assertEquals(Handles.NULL, loader.rootOf(tableId));
        assertEquals(-1, loader.placementOf(tableRoot));
        assertFalse(world.entities().isAlive(tableRoot));
        assertEquals(2, world.transforms().size(), "the wall's root and its mesh");
        // Add a second wall at the front of the list: it instantiates at once (scene loaded).
        level.insertPlacement(
                0,
                99,
                0,
                "wall 0",
                "wall",
                LevelData.PLACEMENT_STATIC,
                LevelData.COLLISION_BY_LAYER);
        level.setPlacementFloat(0, 6, 1); // identity rotation
        level.setPlacementFloat(0, 7, 1);
        level.setPlacementFloat(0, 8, 1);
        level.setPlacementFloat(0, 9, 1);
        loader.placementInserted(0);
        assertEquals(2, world.renderableCount());
        assertNotEquals(Handles.NULL, loader.rootOf(99));
        assertEquals(99, loader.placementOf(loader.rootAt(0)));
        assertEquals(
                level.placementId(1), loader.placementOf(loader.rootAt(1)), "the old wall shifted");
        // Unload cleans up.
        loader.unload();
        assertEquals(0, world.renderableCount());
        assertEquals(0, world.transforms().size());
    }

    @Test
    void wholeScenesAndHiddenPlacements() {
        World world = new World(256, meshes);
        LevelLoader loader = new LevelLoader(world, scenes);
        LevelData level = new LevelData();
        int whole = level.prefab("levels/dungeon.jscene", "");
        level.addPlacement(
                whole, "all", "prop", LevelData.PLACEMENT_HIDDEN, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1);
        loader.load(level);
        settle(loader, world);
        assertEquals(3, world.renderableCount());
        world.updateTransforms(jobs);
        RenderPacket p = new RenderPacket(16);
        world.extract(p);
        assertEquals(0, p.count(), "hidden groups extract nothing, static ones included");
        level.setPlacementFlags(0, LevelData.PLACEMENT_STATIC);
        loader.syncFlags(0);
        world.extract(p);
        assertEquals(3, p.count());
        assertEquals(RenderPacket.FLAG_STATIC, p.flags[0] & RenderPacket.FLAG_STATIC);
    }
}
