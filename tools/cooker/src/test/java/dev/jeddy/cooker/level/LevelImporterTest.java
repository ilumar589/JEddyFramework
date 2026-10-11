package dev.jeddy.cooker.level;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.format.LevelData;
import dev.jeddy.cooker.CookReport;
import dev.jeddy.cooker.Cooker;
import dev.jeddy.cooker.CookerConfig;
import dev.jeddy.cooker.gltf.GltfBuilder;
import dev.jeddy.physics.bvh.Bvh;
import dev.jeddy.physics.bvh.RayHit;
import dev.jeddy.physics.bvh.TriFlags;
import dev.jeddy.physics.nav.NavMesh;
import dev.jeddy.physics.nav.NavMeshData;
import dev.jeddy.physics.nav.NavMeshQuery;
import dev.jeddy.physics.nav.QueryFilter;
import dev.jeddy.physics.vis.OcclusionGrid;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Levels of placed prefabs bake physics from their pieces (TASKS M14-04, D90). */
class LevelImporterTest {
    @TempDir Path dir;

    /**
     * A prefab library: {@code floor} (a 4×4 m tile, mesh under a root node), {@code block} (a 1 m
     * cube, scaled 2 tall through a child node) and {@code decor} (a cube nobody collides with).
     */
    static void writeLibrary(Path file) throws Exception {
        GltfBuilder b = new GltfBuilder();
        int floorPos = b.floats(new float[] {-2, 0, -2, -2, 0, 2, 2, 0, 2, 2, 0, -2}, "VEC3");
        int floorIdx = b.indices(new int[] {0, 1, 2, 0, 2, 3});
        float[][] cube = GltfBuilder.cube();
        int cubePos = b.floats(cube[0], "VEC3");
        int cubeIdx = b.indices(GltfBuilder.toInts(cube[3]));
        b.add(
                "meshes",
                Map.of(
                        "name",
                        "floor",
                        "primitives",
                        List.of(
                                Map.of(
                                        "attributes",
                                        Map.of("POSITION", floorPos),
                                        "indices",
                                        floorIdx))));
        b.add(
                "meshes",
                Map.of(
                        "name",
                        "cube",
                        "primitives",
                        List.of(
                                Map.of(
                                        "attributes",
                                        Map.of("POSITION", cubePos),
                                        "indices",
                                        cubeIdx))));
        b.add("nodes", Map.of("name", "floor", "children", List.of(1)));
        b.add("nodes", Map.of("name", "floor_mesh", "mesh", 0));
        b.add("nodes", Map.of("name", "block", "children", List.of(3)));
        b.add(
                "nodes",
                Map.of(
                        "name",
                        "block_mesh",
                        "mesh",
                        1,
                        "translation",
                        List.of(0.0, 1.0, 0.0),
                        "scale",
                        List.of(1.0, 2.0, 1.0)));
        b.add("nodes", Map.of("name", "decor", "mesh", 1));
        b.add("scenes", Map.of("nodes", List.of(0, 2, 4)));
        b.root.put("scene", 0);
        b.writeGltf(file);
    }

    @Test
    void placementsBecomeCollisionNavmeshAndOcclusion() throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/levels"));
        writeLibrary(src.resolve("Dungeon.gltf"));
        LevelData level = new LevelData();
        int floor = level.prefab("levels/dungeon.jscene", "floor");
        int block = level.prefab("levels/dungeon.jscene", "block");
        int decor = level.prefab("levels/dungeon.jscene", "decor");
        // A 3×3 floor of tiles from (0,0) to (12,12), a block at (6, 6) turned 90°, decor ignored.
        for (int z = 0; z < 3; z++) {
            for (int x = 0; x < 3; x++) {
                level.addPlacement(
                        floor,
                        "floor",
                        "floor",
                        LevelData.PLACEMENT_STATIC,
                        2 + 4 * x,
                        0,
                        2 + 4 * z,
                        0,
                        0,
                        0,
                        1,
                        1,
                        1,
                        1);
            }
        }
        float s = (float) Math.sin(Math.PI / 4);
        level.addPlacement(
                block, "block", "wall", LevelData.PLACEMENT_STATIC, 6, 0, 6, 0, s, 0, s, 1, 1, 1);
        level.addPlacement(decor, "vase", "decor", 0, 2, 0, 10, 0, 0, 0, 1, 1, 1, 1);
        int ignored =
                level.addPlacement(
                        block,
                        "ghost",
                        "wall",
                        LevelData.PLACEMENT_NO_COLLISION,
                        10,
                        0,
                        10,
                        0,
                        0,
                        0,
                        1,
                        1,
                        1,
                        1);
        int crate =
                level.addPlacement(block, "crate", "decor", 0, 10, 0, 2, 0, 0, 0, 1, 1, 0.25f, 1);
        level.setPlacementCollision(
                crate, TriFlags.withSurface(TriFlags.SOLID, 3)); // override: walkable on top
        level.addRule("floor*", TriFlags.WALKABLE, 2, 0);
        level.addRule(
                "wall",
                TriFlags.BLOCKS_MOVEMENT | TriFlags.BLOCKS_LOS | TriFlags.BLOCKS_PROJECTILE,
                1,
                0);
        level.addRule("*", 0, 0, 0);
        level.addSizeClass("medium", 0.4f, 1.8f, 0.4f);
        level.addSizeClass("large", 1.0f, 2.6f, 0.5f);
        level.addArea(5, -1, 1, new float[] {0, 0, 4, 0, 4, 4, 0, 4});
        level.settings.navCellSize = 0.2f;
        level.settings.navTileSize = 6.4f;
        byte[] bytes = level.toBytes();
        Files.write(src.resolve("crypt.jlevel"), bytes);

        try (Cooker c = new Cooker(CookerConfig.of(dir.resolve("src"), dir.resolve("cooked")))) {
            CookReport r = c.cookAll();
            assertEquals(
                    CookReport.Status.COOKED, r.item("levels/crypt.jlevel").status(), r.toJson());
            assertEquals(CookReport.Status.COOKED, r.item("levels/Dungeon.gltf").status());
        }
        Path out = dir.resolve("cooked/levels");
        assertArrayEquals(
                bytes, Files.readAllBytes(out.resolve("crypt.jlevel")), "copied unchanged");
        Bvh bvh = Bvh.read(ByteBuffer.wrap(Files.readAllBytes(out.resolve("crypt.jcol"))));
        assertEquals(
                9 * 2 + 12 + 12,
                bvh.triangleCount(),
                "9 floors, the block, the crate; no decor, no ghost");
        RayHit hit = new RayHit();
        assertTrue(bvh.raycast(6, 5, 6, 0, -1, 0, 10, -1, hit));
        assertEquals(3, hit.t, 1e-3f, "the block's top: 2 m tall on a 4 m tile");
        assertTrue((bvh.triangleFlags(hit.triangle) & TriFlags.BLOCKS_LOS) != 0);
        assertEquals(1, TriFlags.surface(bvh.triangleFlags(hit.triangle)));
        assertTrue(bvh.raycast(1, 5, 1, 0, -1, 0, 10, -1, hit));
        assertEquals(5, hit.t, 1e-3f, "the floor");
        assertEquals(2, TriFlags.surface(bvh.triangleFlags(hit.triangle)));
        assertTrue(bvh.raycast(10, 5, 2, 0, -1, 0, 10, -1, hit));
        assertEquals(4.5f, hit.t, 1e-3f, "the crate: 0.25 × 2 m tall");
        assertEquals(3, TriFlags.surface(bvh.triangleFlags(hit.triangle)), "the override");
        assertFalse(bvh.raycast(10, 5, 10, 0, -1, 0, 4.5f, -1, hit), "the ghost has no collision");

        NavMeshData medium =
                NavMeshData.read(
                        ByteBuffer.wrap(Files.readAllBytes(out.resolve("crypt.medium.jnav"))));
        NavMeshData large =
                NavMeshData.read(
                        ByteBuffer.wrap(Files.readAllBytes(out.resolve("crypt.large.jnav"))));
        assertEquals(0.4f, medium.agentRadius());
        assertEquals(1.0f, large.agentRadius());
        NavMeshQuery q = new NavMeshQuery(new NavMesh(medium), 512);
        float[] p = new float[3];
        assertTrue(
                q.findNearestPoly(2, 0, 10, 0.3f, 1, 0.3f, new QueryFilter(), p) != 0,
                "walkable floor");
        assertEquals(
                0,
                q.findNearestPoly(6, 0, 6, 0.3f, 1, 0.3f, new QueryFilter(), p),
                "under the block");
        OcclusionGrid occ =
                OcclusionGrid.read(ByteBuffer.wrap(Files.readAllBytes(out.resolve("crypt.jocc"))));
        assertTrue(occ.blocked(occ.cellX(6), occ.cellZ(6)), "the block blocks sight");
        assertFalse(occ.blocked(occ.cellX(2), occ.cellZ(10)), "decor does not");
        assertTrue(ignored >= 0);
    }
}
