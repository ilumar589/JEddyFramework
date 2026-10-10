package dev.jeddy.cooker.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import dev.jeddy.physics.nav.NavTile;
import dev.jeddy.physics.nav.QueryFilter;
import dev.jeddy.physics.vis.OcclusionGrid;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Level physics bakes: .jcol, .jnav per size class, .jocc (TASKS M8-01, M8-05, M8-10). */
class PhysicsImporterTest {
    @TempDir Path dir;

    /** A 20×20 m floor, a 4×1.5×4 m block, a roof slab above and an ignored prop. */
    static void writeLevel(Path file) throws Exception {
        GltfBuilder b = new GltfBuilder();
        int floorPos = b.floats(new float[] {0, 0, 0, 0, 0, 20, 20, 0, 20, 20, 0, 0}, "VEC3");
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
        b.add("nodes", Map.of("name", "Ground_Floor", "mesh", 0));
        b.add(
                "nodes",
                Map.of(
                        "name",
                        "wall_block",
                        "mesh",
                        1,
                        "translation",
                        List.of(10.0, 0.75, 10.0),
                        "scale",
                        List.of(4.0, 1.5, 4.0)));
        b.add(
                "nodes",
                Map.of(
                        "name",
                        "roof",
                        "mesh",
                        1,
                        "translation",
                        List.of(3.0, 4.0, 3.0),
                        "scale",
                        List.of(2.0, 0.2, 2.0)));
        b.add(
                "nodes",
                Map.of("name", "decor_barrel", "mesh", 1, "translation", List.of(3.0, 0.5, 15.0)));
        // A node whose extras override the rules: a walkable crate one can stand on.
        b.add(
                "nodes",
                Map.of(
                        "name",
                        "crate",
                        "mesh",
                        1,
                        "translation",
                        List.of(16.0, 0.25, 4.0),
                        "scale",
                        List.of(1.0, 0.5, 1.0),
                        "extras",
                        Map.of("collision", Map.of("flags", List.of("SOLID"), "surface", 3))));
        b.add("scenes", Map.of("nodes", List.of(0, 1, 2, 3, 4)));
        b.root.put("scene", 0);
        b.writeGltf(file);
    }

    @Test
    void bakesCollisionNavmeshesAndOcclusion() throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/levels"));
        writeLevel(src.resolve("yard.gltf"));
        Files.writeString(
                src.resolve("yard.physics.json"),
                """
                {
                  "scene": "yard.gltf",
                  "collision": [
                    {"nodes": "ground_*", "flags": ["WALKABLE"], "surface": 2},
                    {"nodes": "wall_*", "flags": ["BLOCKS_MOVEMENT", "BLOCKS_LOS", "BLOCKS_PROJECTILE"]},
                    {"nodes": "roof", "flags": ["CAMERA_FADE"]},
                    {"nodes": "decor_*", "flags": []}
                  ],
                  "navmesh": {
                    "cellSize": 0.2, "tileSize": 6.4,
                    "sizes": {"medium": {"radius": 0.4}, "large": {"radius": 1.0, "height": 2.6}},
                    "areas": [{"polygon": [1, 1, 5, 1, 5, 5, 1, 5], "area": 5}],
                    "links": [{"start": [2, 0, 18], "end": [6, 0, 18], "bidirectional": false, "id": 4}]
                  },
                  "occlusion": {"cellSize": 0.5}
                }
                """);
        try (Cooker c = new Cooker(CookerConfig.of(dir.resolve("src"), dir.resolve("cooked")))) {
            CookReport r = c.cookAll();
            assertEquals(
                    CookReport.Status.COOKED,
                    r.item("levels/yard.physics.json").status(),
                    r.toJson());
            assertEquals(
                    CookReport.Status.COOKED,
                    r.item("levels/yard.gltf").status(),
                    "still cooked for rendering");
        }
        Path out = dir.resolve("cooked/levels");
        Bvh bvh = Bvh.read(ByteBuffer.wrap(Files.readAllBytes(out.resolve("yard.jcol"))));
        assertEquals(2 + 12 + 12 + 12, bvh.triangleCount(), "floor, block, roof, crate; no barrel");
        RayHit hit = new RayHit();
        assertTrue(bvh.raycast(10, 5, 10, 0, -1, 0, 10, -1, hit));
        assertEquals(3.5f, hit.t, 1e-3f, "the block's top");
        assertTrue((bvh.triangleFlags(hit.triangle) & TriFlags.BLOCKS_LOS) != 0);
        assertTrue(bvh.raycast(1, 5, 1, 0, -1, 0, 10, -1, hit));
        assertEquals(2, TriFlags.surface(bvh.triangleFlags(hit.triangle)), "floor surface type");
        assertTrue(bvh.raycast(16, 5, 4, 0, -1, 0, 10, -1, hit));
        assertEquals(3, TriFlags.surface(bvh.triangleFlags(hit.triangle)), "extras override");

        NavMeshData medium =
                NavMeshData.read(
                        ByteBuffer.wrap(Files.readAllBytes(out.resolve("yard.medium.jnav"))));
        NavMeshData large =
                NavMeshData.read(
                        ByteBuffer.wrap(Files.readAllBytes(out.resolve("yard.large.jnav"))));
        assertEquals(0.4f, medium.agentRadius());
        assertEquals(1.0f, large.agentRadius());
        assertTrue(medium.tiles().size() >= 9, "6.4 m tiles");
        boolean mud = false;
        boolean link = false;
        for (NavTile t : medium.tiles()) {
            for (int p = 0; p < t.polyCount; p++) {
                mud |= t.area[p] == 5;
                link |= t.type[p] == NavTile.TYPE_OFFMESH && t.offMeshUserId[p] == 4;
            }
        }
        assertTrue(mud && link, "area volume and off-mesh link baked");
        NavMeshQuery q = new NavMeshQuery(new NavMesh(medium), 512);
        float[] p = new float[3];
        assertTrue(q.findNearestPoly(2, 0, 10, 0.3f, 1, 0.3f, new QueryFilter(), p) != 0);
        assertEquals(
                0,
                q.findNearestPoly(10, 0, 10, 0.3f, 1, 0.3f, new QueryFilter(), p),
                "under the block");

        OcclusionGrid occ =
                OcclusionGrid.read(ByteBuffer.wrap(Files.readAllBytes(out.resolve("yard.jocc"))));
        assertTrue(occ.blocked(occ.cellX(10), occ.cellZ(10)), "the block blocks sight");
        assertTrue(!occ.blocked(occ.cellX(3), occ.cellZ(3)), "the roof is above eye level");
    }
}
