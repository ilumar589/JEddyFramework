package dev.jeddy.physics.nav;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.core.job.JobSystem;
import dev.jeddy.physics.bvh.TriFlags;
import dev.jeddy.physics.bvh.TriangleMesh;
import dev.jeddy.physics.nav.bake.NavMeshBaker;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.SplittableRandom;

/** Path corridors, batched parallel paths and the 200×200 m path benchmark (TASKS M8-07). */
class NavPathTest {
    /** Follows a corridor in steps of {@code step} m; returns the steps taken (or -1). */
    static int follow(
            PathCorridor c,
            NavMeshQuery q,
            QueryFilter f,
            float step,
            int maxSteps,
            boolean[] jumped) {
        float[] corners = new float[3 * 4];
        int[] flags = new int[4];
        int[] polys = new int[4];
        int[] refs = new int[2];
        float[] a = new float[3];
        float[] b = new float[3];
        for (int s = 0; s < maxSteps; s++) {
            int n = c.findCorners(corners, flags, polys, 4, q);
            if (n == 0) {
                return s;
            }
            if (n > 1) {
                c.optimizePathVisibility(corners[3], corners[4], corners[5], 8, q, f);
            }
            float[] pos = c.pos();
            float dx = corners[0] - pos[0];
            float dz = corners[2] - pos[2];
            float d = (float) Math.sqrt(dx * dx + dz * dz);
            if ((flags[0] & NavMeshQuery.STRAIGHT_OFFMESH) != 0 && d < step) {
                assertTrue(c.moveOverOffMeshLink(polys[0], refs, a, b, q.mesh()));
                jumped[0] = true;
                continue;
            }
            if ((flags[0] & NavMeshQuery.STRAIGHT_END) != 0 && d < 0.05f) {
                return s;
            }
            float k = Math.min(step, d) / Math.max(d, 1e-6f);
            assertTrue(c.movePosition(pos[0] + dx * k, pos[1], pos[2] + dz * k, q, f));
            float x = c.pos()[0];
            float z = c.pos()[2];
            assertTrue(!(x > 7.7f && x < 12.3f && z > 7.7f && z < 12.3f), "never inside the block");
        }
        return -1;
    }

    @Test
    void corridorsAreFollowedAroundObstacles() {
        NavMesh mesh =
                new NavMesh(NavMeshQueryTest.tiled == null ? bakeRoom() : NavMeshQueryTest.tiled);
        NavMeshQuery q = new NavMeshQuery(mesh, 2048);
        QueryFilter f = new QueryFilter();
        float[] p = new float[3];
        int[] path = new int[256];
        int start = q.findNearestPoly(2, 0, 10, 0.5f, 1, 0.5f, f, p);
        int end = q.findNearestPoly(18, 0, 10, 0.5f, 1, 0.5f, f, p);
        int n = q.findPath(start, end, 2, 0, 10, 18, 0, 10, f, path);
        PathCorridor c = new PathCorridor(256);
        c.reset(start, 2, 0, 10);
        c.setCorridor(18, 0, 10, path, n);
        int steps = follow(c, q, f, 0.3f, 200, new boolean[1]);
        assertTrue(steps > 0, "arrived");
        // ≈ 17 m of path in 0.3 m steps.
        assertTrue(steps < 70, "steps: " + steps);
        assertEquals(18, c.pos()[0], 0.06f);
        assertEquals(10, c.pos()[2], 0.06f);
        assertEquals(end, c.firstPoly());
    }

    static NavMeshData bakeRoom() {
        return NavMeshBaker.bake(NavBakeTest.room(), NavBakeTest.CFG.withTileSize(32), null);
    }

    @Test
    void corridorsTakeOffMeshLinks() {
        NavMesh mesh = new NavMesh(NavMeshQueryTest.gap(true, false));
        NavMeshQuery q = new NavMeshQuery(mesh, 512);
        QueryFilter f = new QueryFilter();
        float[] p = new float[3];
        int[] path = new int[64];
        int a = q.findNearestPoly(2, 0, 5, 0.5f, 1, 0.5f, f, p);
        int b = q.findNearestPoly(18, 0, 5, 0.5f, 1, 0.5f, f, p);
        int n = q.findPath(a, b, 2, 0, 5, 18, 0, 5, f, path);
        PathCorridor c = new PathCorridor(64);
        c.reset(a, 2, 0, 5);
        c.setCorridor(18, 0, 5, path, n);
        boolean[] jumped = new boolean[1];
        int steps = follow(c, q, f, 0.5f, 100, jumped);
        assertTrue(steps > 0);
        assertTrue(jumped[0], "jumped the gap");
        assertEquals(18, c.pos()[0], 0.06f);
    }

    @Test
    void corridorSplicing() {
        // Moving forward drops the polygons left behind.
        int[] path = {1, 2, 3, 4, 0, 0};
        assertEquals(3, PathCorridor.mergeStartMoved(path, 4, 6, new int[] {1, 2}, 2));
        assertArrayEquals(new int[] {2, 3, 4}, Arrays.copyOf(path, 3));
        // Moving off the corridor prepends the way back.
        path = new int[] {1, 2, 3, 0, 0, 0};
        assertEquals(4, PathCorridor.mergeStartMoved(path, 3, 6, new int[] {1, 9}, 2));
        assertArrayEquals(new int[] {9, 1, 2, 3}, Arrays.copyOf(path, 4));
        // A shortcut replaces the start up to where it rejoins.
        path = new int[] {1, 2, 3, 4, 5, 0};
        assertEquals(4, PathCorridor.mergeStartShortcut(path, 5, new int[] {1, 7, 4}, 3));
        assertArrayEquals(new int[] {1, 7, 4, 5}, Arrays.copyOf(path, 4));
        // Moving the target extends the end.
        path = new int[] {1, 2, 3, 0, 0, 0};
        assertEquals(5, PathCorridor.mergeEndMoved(path, 3, 6, new int[] {3, 8, 9}, 3));
        assertArrayEquals(new int[] {1, 2, 3, 8, 9}, Arrays.copyOf(path, 5));
    }

    @Test
    void batchesRunInParallelWithTheSameResults() {
        NavMeshData data = NavMeshQueryTest.tiled == null ? bakeRoom() : NavMeshQueryTest.tiled;
        NavMesh mesh = new NavMesh(data);
        QueryFilter f = new QueryFilter();
        SplittableRandom rnd = new SplittableRandom(7);
        try (JobSystem jobs = new JobSystem(3)) {
            NavPathBatch serial = new NavPathBatch(mesh, 64, 128, 16, 2048, jobs.threadSlots());
            NavPathBatch parallel = new NavPathBatch(mesh, 64, 128, 16, 2048, jobs.threadSlots());
            for (int i = 0; i < 300; i++) {
                float sx = (float) rnd.nextDouble(1, 19);
                float sz = (float) rnd.nextDouble(1, 19);
                float ex = (float) rnd.nextDouble(1, 19);
                float ez = (float) rnd.nextDouble(1, 19);
                serial.add(0, sx, 0, sz, 0, ex, 0, ez);
                parallel.add(0, sx, 0, sz, 0, ex, 0, ez);
            }
            serial.run(f, null);
            parallel.run(f, jobs);
            int ok = 0;
            for (int i = 0; i < 300; i++) {
                assertEquals(serial.status[i], parallel.status[i]);
                assertEquals(serial.polyCount[i], parallel.polyCount[i]);
                assertEquals(serial.cornerCount[i], parallel.cornerCount[i]);
                if (serial.status[i] == NavMeshQuery.SUCCESS) {
                    ok++;
                }
            }
            assertArrayEquals(serial.polys, parallel.polys);
            assertArrayEquals(serial.corners, parallel.corners);
            assertTrue(ok > 250, "most random points are reachable: " + ok);
            Allocations.assertNoAllocation("NavPathBatch.run", 10, 2, () -> parallel.run(f, jobs));
        }
    }

    /** A 200×200 m floor with 1.5 m blocks (2×2 m) on a jittered 8 m grid and a few walls. */
    static TriangleMesh bigMap() {
        TriangleMesh m = new TriangleMesh();
        for (int z = 0; z < 200; z += 20) {
            for (int x = 0; x < 200; x += 20) {
                m.addGround(x, z, x + 20, z + 20, 0, TriFlags.WALKABLE);
            }
        }
        SplittableRandom rnd = new SplittableRandom(3);
        int flags = TriFlags.BLOCKS_MOVEMENT | TriFlags.BLOCKS_LOS;
        for (int z = 4; z < 196; z += 8) {
            for (int x = 4; x < 196; x += 8) {
                if (rnd.nextInt(4) == 0) {
                    continue;
                }
                float cx = x + (float) rnd.nextDouble(-1.5, 1.5);
                float cz = z + (float) rnd.nextDouble(-1.5, 1.5);
                m.addBox(cx - 1, 0, cz - 1, cx + 1, 1.5f, cz + 1, flags);
            }
        }
        // Long walls with gaps.
        for (int w = 40; w < 200; w += 50) {
            m.addBox(10, 0, w, 90, 1.5f, w + 0.6f, flags);
            m.addBox(110, 0, w, 190, 1.5f, w + 0.6f, flags);
        }
        return m;
    }

    @Test
    void thousandPathsAcrossA200mMap() {
        NavMeshData data;
        long t0 = System.nanoTime();
        try (JobSystem jobs = new JobSystem(JobSystem.defaultWorkerCount())) {
            data = NavMeshBaker.bake(bigMap(), NavBakeTest.CFG.withTileSize(160), jobs);
        }
        long bakeMs = (System.nanoTime() - t0) / 1_000_000;
        NavMesh mesh = new NavMesh(data);
        int polys = 0;
        for (NavTile t : data.tiles()) {
            polys += t.polyCount;
        }
        QueryFilter f = new QueryFilter();
        SplittableRandom rnd = new SplittableRandom(11);
        try (JobSystem jobs = new JobSystem(JobSystem.defaultWorkerCount())) {
            NavPathBatch batch = new NavPathBatch(mesh, 1000, 512, 32, 4096, jobs.threadSlots());
            for (int i = 0; i < 1000; i++) {
                batch.add(
                        0,
                        (float) rnd.nextDouble(1, 199),
                        0,
                        (float) rnd.nextDouble(1, 199),
                        0,
                        (float) rnd.nextDouble(1, 199),
                        0,
                        (float) rnd.nextDouble(1, 199));
            }
            for (int warm = 0; warm < 3; warm++) {
                batch.run(f, null);
            }
            long s0 = System.nanoTime();
            batch.run(f, null);
            double serialMs = (System.nanoTime() - s0) / 1e6;
            for (int warm = 0; warm < 3; warm++) {
                batch.run(f, jobs);
            }
            long p0 = System.nanoTime();
            batch.run(f, jobs);
            double parallelMs = (System.nanoTime() - p0) / 1e6;
            int ok = 0;
            double len = 0;
            for (int i = 0; i < 1000; i++) {
                if (batch.status[i] == NavMeshQuery.SUCCESS) {
                    ok++;
                    len += batch.polyCount[i];
                }
            }
            System.out.printf(
                    "navmesh 200×200 m: %d tiles, %d polygons, baked in %d ms; 1000 paths (%d"
                            + " complete, %.0f polygons avg): %.1f ms on one thread, %.1f ms on %d"
                            + " workers%n",
                    data.tiles().size(),
                    polys,
                    bakeMs,
                    ok,
                    len / Math.max(1, ok),
                    serialMs,
                    parallelMs,
                    jobs.workerCount());
            assertTrue(ok > 900, "complete paths: " + ok);
        }
    }
}
