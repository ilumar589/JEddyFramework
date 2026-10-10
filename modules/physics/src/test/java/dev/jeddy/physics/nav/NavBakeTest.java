package dev.jeddy.physics.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.core.job.JobSystem;
import dev.jeddy.physics.bvh.TriFlags;
import dev.jeddy.physics.bvh.TriangleMesh;
import dev.jeddy.physics.nav.bake.NavBakeConfig;
import dev.jeddy.physics.nav.bake.NavMeshBaker;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

/** Navmesh baking: voxelize, filter, regions, contours, polygons, tiles (TASKS M8-04, M8-05). */
class NavBakeTest {
    static final NavBakeConfig CFG =
            new NavBakeConfig(0.2f, 0.1f, 1.8f, 0.4f, 0.4f, 45, 0, 8, 20, 12, 1.3f, 6);

    /** A 20×20 m floor with a 4×4 m block in the middle. */
    static TriangleMesh room() {
        TriangleMesh m = new TriangleMesh();
        m.addGround(0, 0, 20, 20, 0, TriFlags.WALKABLE);
        // 1.5 m: lower than an agent, so its hollow inside is not walkable (Recast voxelizes
        // surfaces, so closed objects taller than the agent leave unreachable islands inside).
        m.addBox(8, 0, 8, 12, 1.5f, 12, TriFlags.BLOCKS_MOVEMENT | TriFlags.BLOCKS_LOS);
        return m;
    }

    static double area(NavMeshData nav) {
        double sum = 0;
        for (NavTile t : nav.tiles()) {
            for (int p = 0; p < t.polyCount; p++) {
                if (t.type[p] != NavTile.TYPE_GROUND) {
                    continue;
                }
                int n = t.polyVertCount[p];
                double a = 0;
                for (int i = 0, j = n - 1; i < n; j = i++) {
                    int vi = t.vertex(p, i);
                    int vj = t.vertex(p, j);
                    a += t.verts[vj] * t.verts[vi + 2] - t.verts[vi] * t.verts[vj + 2];
                }
                sum += Math.abs(a) / 2;
            }
        }
        return sum;
    }

    @Test
    void aFloorWithABlockBakesToPolygonsAroundIt() {
        NavMeshData nav = NavMeshBaker.bake(room(), CFG, null);
        assertEquals(1, nav.tiles().size());
        NavTile t = nav.tiles().get(0);
        assertTrue(t.polyCount > 3, "polygons: " + t.polyCount);
        // Outer edges drop off: the ledge filter takes one cell, erosion the agent radius (0.6 m);
        // around the block only the radius.
        double expected = 18.8 * 18.8 - 4.8 * 4.8;
        assertEquals(expected, area(nav), expected * 0.03, "walkable area minus the eroded block");
        // Every polygon is convex, on the floor, and outside the block plus the agent radius.
        for (int p = 0; p < t.polyCount; p++) {
            int n = t.polyVertCount[p];
            assertTrue(n >= 3 && n <= 6);
            for (int i = 0; i < n; i++) {
                int a = t.vertex(p, i);
                int b = t.vertex(p, (i + 1) % n);
                int c = t.vertex(p, (i + 2) % n);
                float cross =
                        (t.verts[b] - t.verts[a]) * (t.verts[c + 2] - t.verts[a + 2])
                                - (t.verts[c] - t.verts[a]) * (t.verts[b + 2] - t.verts[a + 2]);
                assertTrue(cross <= 1e-3f, "convex, one winding");
                assertEquals(0f, t.verts[a + 1], 0.15f, "on the floor");
            }
            float cx = 0, cz = 0;
            for (int i = 0; i < n; i++) {
                cx += t.verts[t.vertex(p, i)] / n;
                cz += t.verts[t.vertex(p, i) + 2] / n;
            }
            StringBuilder desc = new StringBuilder();
            for (int i = 0; i < n; i++) {
                int v = t.vertex(p, i);
                desc.append(
                        String.format(
                                " (%.2f %.2f %.2f)", t.verts[v], t.verts[v + 1], t.verts[v + 2]));
            }
            assertTrue(
                    !(cx > 8 && cx < 12 && cz > 8 && cz < 12),
                    "no polygon inside the block:" + desc);
        }
        // Edges between polygons are linked both ways.
        for (int p = 0; p < t.polyCount; p++) {
            for (int e = 0; e < t.polyVertCount[p]; e++) {
                int nb = t.neighbours[p * NavTile.MAX_VERTS + e];
                if (nb >= 0 && (nb & NavTile.PORTAL) == 0) {
                    boolean back = false;
                    for (int f = 0; f < t.polyVertCount[nb]; f++) {
                        back |= t.neighbours[nb * NavTile.MAX_VERTS + f] == p;
                    }
                    assertTrue(back, "symmetric links");
                }
            }
        }
    }

    @Test
    void tilesBakeInParallelAndMeetAtPortals() {
        NavMeshData single = NavMeshBaker.bake(room(), CFG, null);
        NavMeshData tiled;
        try (JobSystem jobs = new JobSystem(3)) {
            tiled = NavMeshBaker.bake(room(), CFG.withTileSize(32), jobs);
        }
        assertTrue(
                tiled.tiles().size() >= 15, "6.4 m tiles over 20 m: 4 × 4, a corner may be empty");
        assertEquals(area(single), area(tiled), area(single) * 0.03, "same walkable area");
        int portals = 0;
        for (NavTile t : tiled.tiles()) {
            for (int i = 0; i < t.polyCount * NavTile.MAX_VERTS; i++) {
                if (t.neighbours[i] != NavTile.NO_NEIGHBOUR
                        && (t.neighbours[i] & NavTile.PORTAL) != 0) {
                    portals++;
                }
            }
        }
        assertTrue(portals > 20, "edges on tile sides are portals: " + portals);
        // Round trip.
        NavMeshData back = NavMeshData.read(ByteBuffer.wrap(tiled.toBytes()));
        assertEquals(tiled.tiles().size(), back.tiles().size());
        assertEquals(area(tiled), area(back), 1e-3);
    }

    @Test
    void slopesLedgesAndLowCeilingsAreFiltered() {
        TriangleMesh m = new TriangleMesh();
        m.addGround(0, 0, 10, 10, 0, TriFlags.WALKABLE);
        // A 60° ramp (too steep) and a 1 m high table to crawl under (too low), both walkable.
        int a = m.addVertex(12, 0, 0);
        int b = m.addVertex(14, 0, 0);
        int c = m.addVertex(14, 6.93f, 4);
        int d = m.addVertex(12, 6.93f, 4);
        m.addTriangle(a, d, c, TriFlags.WALKABLE);
        m.addTriangle(a, c, b, TriFlags.WALKABLE);
        m.addGround(4, 4, 8, 8, 1.0f, TriFlags.WALKABLE | TriFlags.BLOCKS_MOVEMENT);
        NavMeshData nav = NavMeshBaker.bake(m, CFG, null);
        NavTile t = nav.tiles().get(0);
        for (int p = 0; p < t.polyCount; p++) {
            for (int i = 0; i < t.polyVertCount[p]; i++) {
                int v = t.vertex(p, i);
                assertTrue(
                        t.verts[v] < 11,
                        "nothing on the steep ramp: poly "
                                + p
                                + " vertex "
                                + t.verts[v]
                                + ", "
                                + t.verts[v + 1]
                                + ", "
                                + t.verts[v + 2]);
            }
        }
        // The table top (1 m up, 4×4) is walkable on its own; under it is not (0.9 m clearance).
        // Both drop off at their edges: one ledge cell plus the agent radius.
        double floorArea = 8.8 * 8.8 - 4.8 * 4.8;
        double tableTop = 2.8 * 2.8;
        assertEquals(floorArea + tableTop, area(nav), (floorArea + tableTop) * 0.05);
    }

    @Test
    void areaVolumesAndOffMeshLinksAreBaked() {
        TriangleMesh m = new TriangleMesh();
        m.addGround(0, 0, 10, 10, 0, TriFlags.WALKABLE);
        List<NavMeshBaker.AreaVolume> mud =
                List.of(
                        new NavMeshBaker.AreaVolume(
                                new float[] {2, 2, 6, 2, 6, 6, 2, 6}, -1, 1, 5));
        List<NavMeshBaker.OffMeshLink> jump =
                List.of(new NavMeshBaker.OffMeshLink(1, 0, 1, 9, 0, 9, 0.5f, true, 63, 77));
        NavMeshData nav = NavMeshBaker.bake(m, CFG, mud, jump, null);
        NavTile t = nav.tiles().get(0);
        double mudArea = 0;
        int links = 0;
        for (int p = 0; p < t.polyCount; p++) {
            if (t.type[p] == NavTile.TYPE_OFFMESH) {
                links++;
                assertEquals(77, t.offMeshUserId[p]);
                continue;
            }
            if (t.area[p] == 5) {
                int n = t.polyVertCount[p];
                double a = 0;
                for (int i = 0, j = n - 1; i < n; j = i++) {
                    int vi = t.vertex(p, i);
                    int vj = t.vertex(p, j);
                    a += t.verts[vj] * t.verts[vi + 2] - t.verts[vi] * t.verts[vj + 2];
                }
                mudArea += Math.abs(a) / 2;
            }
        }
        assertEquals(1, links);
        assertEquals(16, mudArea, 2.5, "the mud polygons cover the volume");
    }
}
