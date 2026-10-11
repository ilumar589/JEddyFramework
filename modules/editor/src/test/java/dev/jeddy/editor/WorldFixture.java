package dev.jeddy.editor;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.app.world.LevelLoader;
import dev.jeddy.app.world.World;
import dev.jeddy.app.world.WorldAssets;
import dev.jeddy.asset.AssetManager;
import dev.jeddy.asset.AssetPaths;
import dev.jeddy.asset.AssetType;
import dev.jeddy.asset.Vfs;
import dev.jeddy.asset.format.LevelData;
import dev.jeddy.asset.format.MeshWriter;
import dev.jeddy.asset.format.SceneData;
import dev.jeddy.core.job.JobSystem;
import dev.jeddy.render.mesh.GpuMesh;
import dev.jeddy.render.mesh.MeshAssets;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A headless world showing a small level: a library scene of two prefabs ("wall", one mesh node 1 m
 * up; "table", a base with a scaled top) and a level of a wall, a table, a torch, a spawn and a
 * trigger. The tests edit it through the editor and look at the world.
 */
final class WorldFixture implements AutoCloseable {
    final JobSystem jobs;
    final Vfs vfs;
    final AssetManager assets;
    final AssetType<GpuMesh> meshes;
    final AssetType<SceneData> scenes;
    final World world;
    final LevelLoader loader;
    final LevelData level;

    WorldFixture(Path dir) throws Exception {
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
        world = new World(256, meshes);
        loader = new LevelLoader(world, scenes);
        level = level();
        loader.load(level);
        settle();
    }

    static float[] trs(float x, float y, float z, float scale) {
        return new float[] {x, y, z, 0, 0, 0, 1, scale, scale, scale};
    }

    static LevelData level() {
        LevelData l = new LevelData();
        int wall = l.prefab("levels/dungeon.jscene", "wall");
        int table = l.prefab("levels/dungeon.jscene", "table");
        l.addPlacement(
                wall, "wall 1", "wall", LevelData.PLACEMENT_STATIC, 4, 0, 0, 0, 0, 0, 1, 1, 1, 1);
        l.addPlacement(table, "table 1", "prop", 0, 0, 0, 6, 0, 0, 0, 1, 1, 1, 1);
        l.addPointLight("torch", 1, 2, 3, 1, 0.5f, 0.2f, 8, 9, LevelData.LIGHT_FLICKER);
        l.addSpawn("party", "party", -2, 0, -2, 0.5f);
        l.addTrigger("door", "door", "open", LevelData.TRIGGER_ENABLED, 3, 1, 3, 2, 2, 1, 0);
        l.addRule("wall", 30, 2, 0);
        l.addRule("*", 0, 0, 0);
        l.addSizeClass("medium", 0.4f, 1.8f, 0.4f);
        return l;
    }

    /** Pumps the asset manager and loader until the level is instantiated. */
    void settle() {
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
        world.updateTransforms(jobs);
    }

    @Override
    public void close() {
        assets.close();
        vfs.close();
        jobs.close();
    }
}
