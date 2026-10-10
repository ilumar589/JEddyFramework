package dev.jeddy.physics.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.core.job.JobSystem;
import dev.jeddy.physics.bvh.TriFlags;
import dev.jeddy.physics.bvh.TriangleMesh;
import dev.jeddy.physics.nav.bake.NavMeshBaker;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

/** Navmesh runtime and queries (TASKS M8-06, M8-07). */
class NavMeshQueryTest {
    static NavMeshData tiled;

    @BeforeAll
    static void bake() {
        try (JobSystem jobs = new JobSystem(3)) {
            tiled = NavMeshBaker.bake(NavBakeTest.room(), NavBakeTest.CFG.withTileSize(32), jobs);
        }
    }

    /** Two 8×10 m floors with a 4 m gap between them and a one-way jump across it. */
    static NavMeshData gap(boolean withJump, boolean bidirectional) {
        TriangleMesh m = new TriangleMesh();
        m.addGround(0, 0, 8, 10, 0, TriFlags.WALKABLE);
        m.addGround(12, 0, 20, 10, 0, TriFlags.WALKABLE);
        List<NavMeshBaker.OffMeshLink> links =
                withJump
                        ? List.of(
                                new NavMeshBaker.OffMeshLink(
                                        6.5f, 0, 5, 13.5f, 0, 5, 0.6f, bidirectional, 63, 9))
                        : List.of();
        return NavMeshBaker.bake(m, NavBakeTest.CFG.withTileSize(32), List.of(), links, null);
    }

    static float pathLength(float[] pts, int n) {
        float len = 0;
        for (int i = 1; i < n; i++) {
            float dx = pts[i * 3] - pts[i * 3 - 3];
            float dz = pts[i * 3 + 2] - pts[i * 3 - 1];
            len += (float) Math.sqrt(dx * dx + dz * dz);
        }
        return len;
    }

    @Test
    void tilesLinkAcrossPortals() {
        NavMesh mesh = new NavMesh(tiled);
        assertEquals(tiled.tiles().size(), mesh.tileCount());
        int portalEdges = 0;
        for (NavTile t : tiled.tiles()) {
            for (int p = 0; p < t.polyCount; p++) {
                int ref = mesh.refAt(t.tileX, t.tileZ, p);
                assertTrue(mesh.isValid(ref));
                for (int e = 0; e < t.polyVertCount[p]; e++) {
                    int nb = t.neighbours[p * NavTile.MAX_VERTS + e];
                    if (nb == NavTile.NO_NEIGHBOUR || (nb & NavTile.PORTAL) == 0) {
                        continue;
                    }
                    int side = nb & 0xf;
                    if (mesh.tileAt(t.tileX + NavMesh.sideX(side), t.tileZ + NavMesh.sideZ(side))
                            == null) {
                        continue; // the corner tile is empty (too small a region)
                    }
                    portalEdges++;
                    float covered = 0;
                    for (int l = mesh.firstLink(ref); l >= 0; l = mesh.nextLink(ref, l)) {
                        if (mesh.linkEdge(ref, l) == e) {
                            assertTrue(mesh.isValid(mesh.linkTarget(ref, l)));
                            assertEquals(nb & 0xf, mesh.linkSide(ref, l));
                            covered += mesh.linkMax(ref, l) - mesh.linkMin(ref, l);
                        }
                    }
                    assertEquals(1f, covered, 0.02f, "a portal edge is covered by its neighbours");
                }
            }
        }
        assertTrue(portalEdges > 20);
    }

    @Test
    void nearestPolygonAndHeight() {
        NavMeshQuery q = new NavMeshQuery(new NavMesh(tiled), 2048);
        QueryFilter f = new QueryFilter();
        float[] p = new float[3];
        int ref = q.findNearestPoly(3, 0.3f, 4, 0.5f, 1, 0.5f, f, p);
        assertNotEquals(NavMesh.NULL_REF, ref);
        assertEquals(3, p[0], 1e-4f);
        assertEquals(4, p[2], 1e-4f);
        assertEquals(0, p[1], 0.15f, "snapped to the surface");
        assertEquals(0, q.polyHeight(ref, 3, 4), 0.15f);
        // Inside the block: the nearest point is on the eroded border (block ± 0.4 m radius).
        ref = q.findNearestPoly(9.5f, 0, 10, 3, 2, 3, f, p);
        assertNotEquals(NavMesh.NULL_REF, ref);
        assertEquals(7.6f, p[0], 0.25f);
        assertEquals(10f, p[2], 0.25f);
        // Nothing within the box far off the map.
        assertEquals(NavMesh.NULL_REF, q.findNearestPoly(50, 0, 50, 1, 1, 1, f, p));
    }

    @Test
    void raycastsStopAtWallsAndCrossTiles() {
        NavMeshQuery q = new NavMeshQuery(new NavMesh(tiled), 2048);
        QueryFilter f = new QueryFilter();
        float[] p = new float[3];
        NavRayHit hit = new NavRayHit();
        int[] path = new int[64];
        // Into the block.
        int start = q.findNearestPoly(2, 0, 10, 0.5f, 1, 0.5f, f, p);
        q.raycast(start, 2, 0, 10, 18, 0, 10, f, hit, path);
        assertTrue(hit.hit());
        assertEquals(7.6f, 2 + 16 * hit.t, 0.25f, "stops at the eroded block");
        assertEquals(-1, hit.normalX, 0.05f);
        assertEquals(0, hit.normalZ, 0.05f);
        // Along a clear row, through several tiles.
        start = q.findNearestPoly(2, 0, 3, 0.5f, 1, 0.5f, f, p);
        q.raycast(start, 2, 0, 3, 18, 0, 3, f, hit, path);
        assertFalse(hit.hit());
        assertTrue(hit.pathCount >= 3, "crossed polygons: " + hit.pathCount);
        assertTrue(q.mesh().tileX(2) != q.mesh().tileX(18));
        for (int i = 1; i < hit.pathCount; i++) {
            assertNotEquals(path[i - 1], path[i]);
        }
    }

    @Test
    void pathsGoAroundTheBlock() {
        NavMeshQuery q = new NavMeshQuery(new NavMesh(tiled), 2048);
        QueryFilter f = new QueryFilter();
        float[] p = new float[3];
        int[] path = new int[256];
        float[] pts = new float[3 * 32];
        int[] flags = new int[32];
        int[] refs = new int[32];
        int start = q.findNearestPoly(2, 0, 10, 0.5f, 1, 0.5f, f, p);
        int end = q.findNearestPoly(18, 0, 10, 0.5f, 1, 0.5f, f, p);
        int n = q.findPath(start, end, 2, 0, 10, 18, 0, 10, f, path);
        assertEquals(NavMeshQuery.SUCCESS, q.lastStatus());
        assertEquals(start, path[0]);
        assertEquals(end, path[n - 1]);
        int c = q.findStraightPath(2, 0, 10, 18, 0, 10, path, n, pts, flags, refs, 32);
        assertEquals(NavMeshQuery.SUCCESS, q.lastStatus());
        assertEquals(4, c, "start, two block corners, end");
        assertEquals(NavMeshQuery.STRAIGHT_START, flags[0]);
        assertEquals(NavMeshQuery.STRAIGHT_END, flags[c - 1]);
        // Around the 4.8 m eroded square: 2 × √(5.6² + 2.4²) + 4.8.
        float expected = 2 * (float) Math.hypot(5.6, 2.4) + 4.8f;
        assertEquals(expected, pathLength(pts, c), 0.4f);
        // The corners hug the eroded block (its corners are chamfered by the contours).
        for (int i = 1; i < c - 1; i++) {
            float x = pts[i * 3];
            float z = pts[i * 3 + 2];
            float d = Math.max(Math.abs(x - 10), Math.abs(z - 10));
            assertEquals(2.4f, d, 0.25f, "corner " + x + ", " + z);
            assertTrue(Math.abs(x - 10) > 1.8f && Math.abs(z - 10) > 1.8f, "near a block corner");
        }
    }

    @Test
    void slicedSearchMatchesTheWholeSearch() {
        NavMeshQuery q = new NavMeshQuery(new NavMesh(tiled), 2048);
        QueryFilter f = new QueryFilter();
        float[] p = new float[3];
        int start = q.findNearestPoly(1.5f, 0, 1.5f, 0.5f, 1, 0.5f, f, p);
        int end = q.findNearestPoly(18.5f, 0, 18.5f, 0.5f, 1, 0.5f, f, p);
        int[] whole = new int[256];
        int n = q.findPath(start, end, 1.5f, 0, 1.5f, 18.5f, 0, 18.5f, f, whole);
        assertTrue(n > 3);
        int status = q.initSlicedFindPath(start, end, 1.5f, 0, 1.5f, 18.5f, 0, 18.5f, f);
        int slices = 0;
        while ((status & NavMeshQuery.IN_PROGRESS) != 0) {
            status = q.updateSlicedFindPath(2);
            slices++;
        }
        assertTrue(slices > 2, "took several slices: " + slices);
        int[] sliced = new int[256];
        assertEquals(n, q.finalizeSlicedFindPath(sliced));
        for (int i = 0; i < n; i++) {
            assertEquals(whole[i], sliced[i]);
        }
    }

    @Test
    void unreachableGoalsGivePartialPathsAndJumpsCrossGaps() {
        float[] p = new float[3];
        int[] path = new int[64];
        float[] pts = new float[3 * 16];
        int[] flags = new int[16];
        int[] refs = new int[16];
        QueryFilter f = new QueryFilter();

        NavMeshQuery q = new NavMeshQuery(new NavMesh(gap(false, false)), 512);
        int a = q.findNearestPoly(2, 0, 5, 0.5f, 1, 0.5f, f, p);
        int b = q.findNearestPoly(18, 0, 5, 0.5f, 1, 0.5f, f, p);
        int n = q.findPath(a, b, 2, 0, 5, 18, 0, 5, f, path);
        assertTrue((q.lastStatus() & NavMeshQuery.PARTIAL) != 0, "the gap cuts the floors apart");
        int c = q.findStraightPath(2, 0, 5, 18, 0, 5, path, n, pts, flags, refs, 16);
        assertEquals(7.4f, pts[(c - 1) * 3], 0.3f, "ends at the near edge of the gap");

        // With a one-way jump.
        q = new NavMeshQuery(new NavMesh(gap(true, false)), 512);
        a = q.findNearestPoly(2, 0, 5, 0.5f, 1, 0.5f, f, p);
        b = q.findNearestPoly(18, 0, 5, 0.5f, 1, 0.5f, f, p);
        n = q.findPath(a, b, 2, 0, 5, 18, 0, 5, f, path);
        assertEquals(NavMeshQuery.SUCCESS, q.lastStatus());
        c = q.findStraightPath(2, 0, 5, 18, 0, 5, path, n, pts, flags, refs, 16);
        int jump = -1;
        for (int i = 0; i < c; i++) {
            if ((flags[i] & NavMeshQuery.STRAIGHT_OFFMESH) != 0) {
                jump = i;
            }
        }
        assertTrue(jump > 0, "the path takes the jump");
        assertEquals(6.5f, pts[jump * 3], 0.01f);
        assertEquals(
                NavTile.TYPE_OFFMESH, q.mesh().tile(refs[jump]).type[q.mesh().poly(refs[jump])]);
        assertEquals(9, q.mesh().tile(refs[jump]).offMeshUserId[q.mesh().poly(refs[jump])]);
        assertEquals(13.5f, pts[jump * 3 + 3], 0.01f, "then lands");
        // One way only.
        q.findPath(b, a, 18, 0, 5, 2, 0, 5, f, path);
        assertTrue((q.lastStatus() & NavMeshQuery.PARTIAL) != 0);
        // Both ways.
        q = new NavMeshQuery(new NavMesh(gap(true, true)), 512);
        a = q.findNearestPoly(2, 0, 5, 0.5f, 1, 0.5f, f, p);
        b = q.findNearestPoly(18, 0, 5, 0.5f, 1, 0.5f, f, p);
        q.findPath(b, a, 18, 0, 5, 2, 0, 5, f, path);
        assertEquals(NavMeshQuery.SUCCESS, q.lastStatus());
    }

    @Test
    void moveAlongSurfaceSlidesOnWalls() {
        NavMeshQuery q = new NavMeshQuery(new NavMesh(tiled), 2048);
        QueryFilter f = new QueryFilter();
        float[] p = new float[3];
        int[] visited = new int[16];
        int start = q.findNearestPoly(6, 0, 9, 0.5f, 1, 0.5f, f, p);
        int n = q.moveAlongSurface(start, 6, 0, 9, 9, 0, 10, f, p, visited);
        assertTrue(n >= 1);
        assertEquals(start, visited[0]);
        assertEquals(7.6f, p[0], 0.2f, "stopped by the block");
        assertEquals(10f, p[2], 0.2f, "slid along it");
        assertEquals(0f, p[1], 0.15f);
        // A free move lands where asked.
        n = q.moveAlongSurface(start, 6, 0, 9, 5, 0, 7, f, p, visited);
        assertEquals(5f, p[0], 1e-4f);
        assertEquals(7f, p[2], 1e-4f);
        assertTrue(q.polyHeight(visited[n - 1], 5, 7) == q.polyHeight(visited[n - 1], 5, 7));
    }

    @Test
    void areaCostsAndFlagsSteerPaths() {
        NavMesh mesh = new NavMesh(tiled);
        NavMeshQuery q = new NavMeshQuery(mesh, 2048);
        QueryFilter f = new QueryFilter();
        float[] p = new float[3];
        int[] path = new int[256];
        float[] pts = new float[3 * 32];
        int start = q.findNearestPoly(2, 0, 10, 0.5f, 1, 0.5f, f, p);
        int end = q.findNearestPoly(18, 0, 10, 0.5f, 1, 0.5f, f, p);
        // Close every polygon south of the block: the path goes north, and stays so.
        int[] all = new int[512];
        int na = q.queryPolygons(-1, -1, -1, 21, 2, 21, f, all);
        float[] b = new float[6];
        int closed = 0;
        for (int i = 0; i < na; i++) {
            mesh.tile(all[i]).polyBounds(mesh.poly(all[i]), b, 0);
            if (b[5] <= 7.7f) {
                mesh.setPolyFlags(all[i], 2);
                closed++;
            }
        }
        assertTrue(closed > 3);
        f.include = 1;
        int n = q.findPath(start, end, 2, 0, 10, 18, 0, 10, f, path);
        assertEquals(NavMeshQuery.SUCCESS, q.lastStatus());
        int c = q.findStraightPath(2, 0, 10, 18, 0, 10, path, n, pts, null, null, 32);
        for (int i = 0; i < c; i++) {
            assertTrue(pts[i * 3 + 2] > 9, "north of the block");
        }
        // Excluding the north too leaves no route.
        f.include = 0xffff;
        f.exclude = 2;
        for (int i = 0; i < na; i++) {
            mesh.tile(all[i]).polyBounds(mesh.poly(all[i]), b, 0);
            if (b[2] >= 12.3f) {
                mesh.setPolyFlags(all[i], 2);
            }
        }
        q.findPath(start, end, 2, 0, 10, 18, 0, 10, f, path);
        assertTrue((q.lastStatus() & NavMeshQuery.PARTIAL) != 0);
    }

    @Test
    void tilesComeAndGo() {
        NavMesh mesh = new NavMesh(tiled);
        NavMeshQuery q = new NavMeshQuery(mesh, 2048);
        QueryFilter f = new QueryFilter();
        float[] p = new float[3];
        int[] path = new int[256];
        int start = q.findNearestPoly(1.5f, 0, 1.5f, 0.5f, 1, 0.5f, f, p);
        int end = q.findNearestPoly(18.5f, 0, 1.5f, 0.5f, 1, 0.5f, f, p);
        int n = q.findPath(start, end, 1.5f, 0, 1.5f, 18.5f, 0, 1.5f, f, path);
        assertEquals(NavMeshQuery.SUCCESS, q.lastStatus());
        // Unload the second tile column of the bottom row: the route detours north.
        int tx = mesh.tileX(8);
        int tz = mesh.tileZ(1.5f);
        NavTile removed = mesh.tileAt(tx, tz);
        int stale = mesh.refAt(tx, tz, 0);
        mesh.removeTile(tx, tz);
        assertFalse(mesh.isValid(stale));
        int n2 = q.findPath(start, end, 1.5f, 0, 1.5f, 18.5f, 0, 1.5f, f, path);
        assertEquals(NavMeshQuery.SUCCESS, q.lastStatus());
        for (int i = 0; i < n2; i++) {
            assertNotEquals(
                    tz * 1000 + tx, mesh.tile(path[i]).tileZ * 1000 + mesh.tile(path[i]).tileX);
        }
        assertTrue(n2 > n, "a longer corridor");
        mesh.addTile(removed);
        assertFalse(mesh.isValid(stale), "the slot's salt changed");
        assertEquals(n, q.findPath(start, end, 1.5f, 0, 1.5f, 18.5f, 0, 1.5f, f, path));
    }

    @Test
    void queriesAllocateNothing() {
        NavMeshQuery q = new NavMeshQuery(new NavMesh(tiled), 2048);
        QueryFilter f = new QueryFilter();
        float[] p = new float[3];
        int[] path = new int[256];
        float[] pts = new float[3 * 32];
        int[] flags = new int[32];
        int[] refs = new int[32];
        int[] visited = new int[16];
        NavRayHit hit = new NavRayHit();
        int start = q.findNearestPoly(2, 0, 10, 0.5f, 1, 0.5f, f, p);
        int end = q.findNearestPoly(18, 0, 10, 0.5f, 1, 0.5f, f, p);
        Allocations.assertNoAllocation(
                "navmesh queries",
                10,
                200,
                () -> {
                    q.findNearestPoly(5, 0, 5, 1, 1, 1, f, p);
                    q.raycast(start, 2, 0, 10, 18, 0, 10, f, hit, path);
                    int n = q.findPath(start, end, 2, 0, 10, 18, 0, 10, f, path);
                    q.findStraightPath(2, 0, 10, 18, 0, 10, path, n, pts, flags, refs, 32);
                    q.moveAlongSurface(start, 2, 0, 10, 2.3f, 0, 10.2f, f, p, visited);
                });
    }
}
