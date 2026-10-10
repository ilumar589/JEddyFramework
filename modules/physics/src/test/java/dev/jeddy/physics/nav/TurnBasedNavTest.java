package dev.jeddy.physics.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.physics.bvh.TriFlags;
import dev.jeddy.physics.bvh.TriangleMesh;
import dev.jeddy.physics.nav.bake.NavMeshBaker;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.Test;

import java.util.List;

/** Movement range, path cost and threat crossings (TASKS M8-12). */
class TurnBasedNavTest {
    /** A 30×20 m field whose right half (x > 15) is difficult terrain (area 5, cost ×2). */
    static NavMesh field() {
        TriangleMesh m = new TriangleMesh();
        m.addGround(0, 0, 30, 20, 0, TriFlags.WALKABLE);
        List<NavMeshBaker.AreaVolume> mud =
                List.of(
                        new NavMeshBaker.AreaVolume(
                                new float[] {15, -1, 31, -1, 31, 21, 15, 21}, -1, 1, 5));
        return new NavMesh(
                NavMeshBaker.bake(m, NavBakeTest.CFG.withTileSize(32), mud, List.of(), null));
    }

    @Test
    void movementRangeHonoursDifficultTerrain() {
        NavMesh mesh = field();
        NavMeshQuery q = new NavMeshQuery(mesh, 2048);
        QueryFilter f = new QueryFilter().areaCost(5, 2);
        TurnBasedNav tb = new TurnBasedNav(mesh, 1024);
        float[] p = new float[3];
        int start = q.findNearestPoly(12, 0, 10, 0.5f, 1, 0.5f, f, p);
        int reached = tb.flood(start, 12, 0, 10, 9, f);
        assertTrue(reached > 3);
        // 9 m of movement: 9 m west on normal ground; east, 3 m normal then 3 m of mud (×2).
        assertCost(tb, q, f, 4, 10, 8);
        assertCost(tb, q, f, 17, 10, 3 + 2 * 2);
        assertEquals(Float.POSITIVE_INFINITY, cost(tb, q, f, 2.5f, 10), "10 m west is too far");
        assertEquals(Float.POSITIVE_INFINITY, cost(tb, q, f, 19, 10), "3 + 2×4 m east is too far");
        // The raster: wider to the west than to the east.
        byte[] cells = new byte[60 * 40];
        int in = tb.rasterize(0, 0, 0.5f, 60, 40, cells);
        assertTrue(in > 100);
        int west = 0;
        int east = 0;
        for (int x = 0; x < 60; x++) {
            if (cells[x + 20 * 60] != 0) {
                if (x * 0.5f + 0.25f < 12) {
                    west++;
                } else {
                    east++;
                }
            }
        }
        assertEquals(18, west, 1, "9 m west in 0.5 m cells");
        assertEquals(12, east, 1, "6 m east (3 normal + 3 in mud)");
        int[] edge = new int[256];
        assertTrue(tb.boundary(edge) > 0);
        Allocations.assertNoAllocation(
                "TurnBasedNav.flood", () -> tb.flood(start, 12, 0, 10, 9, f));
    }

    static float cost(TurnBasedNav tb, NavMeshQuery q, QueryFilter f, float x, float z) {
        float[] p = new float[3];
        int ref = q.findNearestPoly(x, 0, z, 0.5f, 1, 0.5f, f, p);
        return tb.costTo(ref, x, p[1], z);
    }

    static void assertCost(
            TurnBasedNav tb, NavMeshQuery q, QueryFilter f, float x, float z, float expected) {
        assertEquals(expected, cost(tb, q, f, x, z), 0.15f, "cost to " + x + ", " + z);
    }

    @Test
    void pathCostSplitsLegsByArea() {
        NavMesh mesh = field();
        NavMeshQuery q = new NavMeshQuery(mesh, 2048);
        QueryFilter f = new QueryFilter().areaCost(5, 2);
        TurnBasedNav tb = new TurnBasedNav(mesh, 1024);
        float[] p = new float[3];
        int[] path = new int[64];
        float[] pts = new float[3 * 16];
        int a = q.findNearestPoly(5, 0, 5, 0.5f, 1, 0.5f, f, p);
        int b = q.findNearestPoly(25, 0, 5, 0.5f, 1, 0.5f, f, p);
        int n = q.findPath(a, b, 5, 0, 5, 25, 0, 5, f, path);
        int c = q.findStraightPath(5, 0, 5, 25, 0, 5, path, n, pts, null, null, 16);
        // 10 m normal + 10 m mud ×2.
        assertEquals(30, tb.pathCost(q, pts, c, a, f), 0.3f);
    }

    @Test
    void threatCrossingsAreOrderedAlongThePath() {
        float[] pts = {0, 0, 0, 10, 0, 0, 10, 0, 10};
        // Enemy 0 reaches across the first leg; enemy 1 stands at the start; enemy 2 is off the
        // path.
        float[] enemies = {5, 1, 2, 0, 0, 1.5f, 20, 20, 2};
        int[] enemy = new int[8];
        float[] dist = new float[8];
        int[] kind = new int[8];
        int n = TurnBasedNav.threatCrossings(pts, 3, enemies, 3, enemy, dist, kind);
        assertEquals(3, n);
        assertEquals(1, enemy[0]);
        assertEquals(TurnBasedNav.EXIT, kind[0], "starting in reach: leaving provokes");
        assertEquals(1.5f, dist[0], 1e-4f);
        assertEquals(0, enemy[1]);
        assertEquals(TurnBasedNav.ENTER, kind[1]);
        assertEquals(5 - (float) Math.sqrt(3), dist[1], 1e-4f);
        assertEquals(TurnBasedNav.EXIT, kind[2]);
        assertEquals(5 + (float) Math.sqrt(3), dist[2], 1e-4f);
    }
}
