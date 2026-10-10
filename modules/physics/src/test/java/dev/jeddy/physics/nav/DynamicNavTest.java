package dev.jeddy.physics.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.core.job.JobSystem;
import dev.jeddy.physics.bvh.TriFlags;
import dev.jeddy.physics.bvh.TriangleMesh;
import dev.jeddy.physics.crowd.AgentParams;
import dev.jeddy.physics.crowd.Crowd;
import dev.jeddy.physics.nav.bake.NavMeshBaker;
import dev.jeddy.physics.nav.bake.TileCache;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.Test;

import java.util.List;

/** Doors and temporary obstacles with background tile rebuilds (TASKS M8-11). */
class DynamicNavTest {
    static float[] pts = new float[3 * 32];

    /** Straight path corners from a to b (x, z); returns the count or -1 when partial. */
    static int straight(NavMeshQuery q, QueryFilter f, float ax, float az, float bx, float bz) {
        float[] p = new float[3];
        int[] path = new int[256];
        int s = q.findNearestPoly(ax, 0, az, 0.5f, 1, 0.5f, f, p);
        int e = q.findNearestPoly(bx, 0, bz, 0.5f, 1, 0.5f, f, p);
        if (s == 0 || e == 0) {
            return -1;
        }
        int n = q.findPath(s, e, ax, 0, az, bx, 0, bz, f, path);
        if ((q.lastStatus() & NavMeshQuery.PARTIAL) != 0) {
            return -1;
        }
        return q.findStraightPath(ax, 0, az, bx, 0, bz, path, n, pts, null, null, 32);
    }

    static TriangleMesh open() {
        TriangleMesh m = new TriangleMesh();
        m.addGround(0, 0, 20, 10, 0, TriFlags.WALKABLE);
        return m;
    }

    @Test
    void obstaclesRebuildTilesInTheBackground() throws InterruptedException {
        try (JobSystem jobs = new JobSystem(2)) {
            TileCache cache =
                    TileCache.build(
                            open(), NavBakeTest.CFG.withTileSize(32), List.of(), List.of(), jobs);
            NavMesh mesh = new NavMesh(cache.data());
            NavMeshQuery q = new NavMeshQuery(mesh, 2048);
            QueryFilter f = new QueryFilter();
            assertEquals(2, straight(q, f, 2, 5, 18, 5), "straight across");

            int pillar = cache.addCylinder(10, 0, 5, 1, 2);
            assertTrue(cache.busy());
            int swapped = cache.update(mesh, jobs);
            assertEquals(0, swapped, "the rebuild runs in the background");
            for (int i = 0; i < 400 && swapped == 0; i++) {
                Thread.sleep(5);
                swapped = cache.update(mesh, jobs);
            }
            assertTrue(swapped >= 1, "tiles under the pillar were swapped: " + swapped);
            assertTrue(!cache.busy());
            int n = straight(q, f, 2, 5, 18, 5);
            assertTrue(n >= 3, "the path bends around the pillar: " + n);
            for (int i = 0; i < n; i++) {
                float dx = pts[i * 3] - 10;
                float dz = pts[i * 3 + 2] - 5;
                assertTrue(Math.sqrt(dx * dx + dz * dz) > 1.3f, "corners keep the agent radius");
            }
            // A raycast through it now hits.
            NavRayHit hit = new NavRayHit();
            float[] p = new float[3];
            int start = q.findNearestPoly(2, 0, 5, 0.5f, 1, 0.5f, f, p);
            q.raycast(start, 2, 0, 5, 18, 0, 5, f, hit, null);
            assertTrue(hit.hit());
            assertEquals(8.6f, 2 + 16 * hit.t, 0.3f);

            cache.remove(pillar);
            cache.flush(mesh, jobs);
            assertEquals(2, straight(q, f, 2, 5, 18, 5), "straight again");

            // A wall of boxes across the field cuts it in two.
            cache.addBox(9.5f, 0, -1, 10.5f, 2, 11);
            cache.flush(mesh, jobs);
            assertEquals(-1, straight(q, f, 2, 5, 18, 5));
            Allocations.assertNoAllocation(
                    "TileCache.update (idle)", () -> cache.update(mesh, jobs));
        }
    }

    @Test
    void doorsOpenAndClose() {
        // Two rooms joined by a doorway at z 4..6, baked as its own area so it gets own polygons.
        TriangleMesh m = open();
        m.addBox(9.5f, 0, 0, 10.5f, 1.5f, 4, TriFlags.BLOCKS_MOVEMENT);
        m.addBox(9.5f, 0, 6, 10.5f, 1.5f, 10, TriFlags.BLOCKS_MOVEMENT);
        List<NavMeshBaker.AreaVolume> doorway =
                List.of(
                        new NavMeshBaker.AreaVolume(
                                new float[] {9, 3.5f, 11, 3.5f, 11, 6.5f, 9, 6.5f}, -1, 1, 10));
        TileCache cache =
                TileCache.build(m, NavBakeTest.CFG.withTileSize(32), doorway, List.of(), null);
        NavMesh mesh = new NavMesh(cache.data());
        NavMeshQuery q = new NavMeshQuery(mesh, 2048);
        QueryFilter f = new QueryFilter();
        assertTrue(straight(q, f, 3, 5, 17, 5) > 0, "through the doorway");
        NavDoors doors = new NavDoors(q);
        int door = doors.add(9, -1, 3.5f, 11, 1, 6.5f, true);
        assertTrue(doors.polygonCount(door) > 0);
        doors.setOpen(door, false);
        assertEquals(-1, straight(q, f, 3, 5, 17, 5), "the closed door blocks");
        QueryFilter opener = new QueryFilter();
        opener.exclude = 0;
        assertTrue(straight(q, opener, 3, 5, 17, 5) > 0, "creatures that open doors path through");
        // A rebuild elsewhere in the tile keeps the door closed after a refresh.
        cache.addCylinder(3, 0, 8, 0.5f, 2);
        assertTrue(cache.update(mesh, null) > 0);
        doors.refresh();
        assertEquals(-1, straight(q, f, 3, 5, 17, 5));
        doors.setOpen(door, true);
        assertTrue(straight(q, f, 3, 5, 17, 5) > 0);
    }

    @Test
    void crowdsReplanAroundNewObstacles() {
        TileCache cache =
                TileCache.build(
                        open(), NavBakeTest.CFG.withTileSize(32), List.of(), List.of(), null);
        NavMesh mesh = new NavMesh(cache.data());
        Crowd crowd = new Crowd(mesh, 4, 0, 0, 20, 10, null);
        int a = crowd.addAgent(2, 0, 5, new AgentParams());
        crowd.requestMoveTarget(a, 18, 0, 5);
        for (int t = 0; t < 15; t++) {
            crowd.update(1 / 30f);
        }
        cache.addBox(9, 0, 3, 11, 2, 7); // dropped in front of it
        cache.update(mesh, null);
        for (int t = 0; t < 30 * 12 && crowd.targetState(a) != Crowd.TARGET_ARRIVED; t++) {
            crowd.update(1 / 30f);
            float x = crowd.x(a);
            float z = crowd.z(a);
            assertTrue(!(x > 9.1f && x < 10.9f && z > 3.1f && z < 6.9f), "never inside the box");
        }
        assertEquals(Crowd.TARGET_ARRIVED, crowd.targetState(a));
        assertEquals(18, crowd.x(a), 0.15f);
    }
}
