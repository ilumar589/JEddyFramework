package dev.jeddy.physics.nav;

import dev.jeddy.core.collections.IntHeap;

/**
 * Turn-based movement helpers (DESIGN §11.4): the movement-range flood (Dijkstra over polygons
 * limited by the movement budget, with area costs such as difficult terrain ×2), point costs and a
 * raster of the reachable area for the UI overlay, the boundary polygons, the cost of walking a
 * string-pulled path, and the threat-zone crossing query (where a path enters and leaves enemy
 * reach circles, to preview attacks of opportunity).
 *
 * <p>Costs are distances times area costs. The flood reaches each polygon at the point of its entry
 * portal closest to where the previous polygon was entered, a tight estimate of the walking
 * distance (exact on open ground, slightly high around chains of corners).
 *
 * <p>Thread ownership: one thread per instance; allocates nothing after construction.
 */
public final class TurnBasedNav {
    /** Threat crossing kind: the path enters a reach circle. */
    public static final int ENTER = 1;

    /** Threat crossing kind: the path leaves a reach circle (provokes attacks of opportunity). */
    public static final int EXIT = 2;

    private final NavMesh mesh;
    private final NavNodePool nodes;
    private final IntHeap open;
    private final float[] portal = new float[6];
    private final float[] p3 = new float[3];
    private final float[] seg = new float[2];
    private final int[] segEdges = new int[2];
    private final int[] rayPath = new int[256];
    private final NavRayHit hit = new NavRayHit();
    private float budget;
    private QueryFilter floodFilter;

    /** Creates helpers over {@code mesh}, flooding at most {@code maxPolys} polygons. */
    public TurnBasedNav(NavMesh mesh, int maxPolys) {
        this.mesh = mesh;
        nodes = new NavNodePool(maxPolys);
        open = new IntHeap(maxPolys, maxPolys);
    }

    // Movement range -----------------------------------------------------------------------------

    /**
     * Floods from {@code (x, y, z)} in polygon {@code startRef} with {@code budget} meters of
     * movement (area costs applied); returns the number of polygons reached.
     */
    public int flood(int startRef, float x, float y, float z, float movement, QueryFilter filter) {
        nodes.reset();
        open.clear();
        budget = movement;
        floodFilter = filter;
        if (!mesh.isValid(startRef)) {
            return 0;
        }
        int s = nodes.get(startRef);
        nodes.pos[s * 3] = x;
        nodes.pos[s * 3 + 1] = y;
        nodes.pos[s * 3 + 2] = z;
        nodes.flags[s] = NavNodePool.OPEN;
        open.push(s, 0);
        float[] pos = nodes.pos;
        while (!open.isEmpty()) {
            int n = open.pop();
            nodes.flags[n] = NavNodePool.CLOSED;
            int ref = nodes.ref[n];
            int parentRef = nodes.parent[n] >= 0 ? nodes.ref[nodes.parent[n]] : NavMesh.NULL_REF;
            int area = mesh.polyArea(ref);
            float nx = pos[n * 3];
            float ny = pos[n * 3 + 1];
            float nz = pos[n * 3 + 2];
            for (int l = mesh.firstLink(ref); l >= 0; l = mesh.nextLink(ref, l)) {
                int nb = mesh.linkTarget(ref, l);
                if (nb == parentRef || !filter.passes(mesh, nb)) {
                    continue;
                }
                portalPoints(ref, nb);
                float s0 =
                        NavGeometry.segmentParam2D(
                                nx, nz, portal[0], portal[2], portal[3], portal[5]);
                float cx = portal[0] + (portal[3] - portal[0]) * s0;
                float cy = portal[1] + (portal[4] - portal[1]) * s0;
                float cz = portal[2] + (portal[5] - portal[2]) * s0;
                float c = nodes.cost[n] + filter.cost(nx, ny, nz, cx, cy, cz, area);
                if (c > movement) {
                    continue;
                }
                int m = nodes.get(nb);
                if (m < 0) {
                    continue;
                }
                if (nodes.flags[m] != 0 && c >= nodes.cost[m]) {
                    continue;
                }
                nodes.parent[m] = n;
                nodes.cost[m] = c;
                pos[m * 3] = cx;
                pos[m * 3 + 1] = cy;
                pos[m * 3 + 2] = cz;
                nodes.flags[m] = NavNodePool.OPEN;
                open.push(m, c);
            }
        }
        return nodes.count();
    }

    private void portalPoints(int from, int to) {
        // Inline of NavMeshQuery.portalPoints (no query object needed here).
        NavMesh.TileLinks tl = mesh.links(from);
        NavTile ft = mesh.tile(from);
        int fp = mesh.poly(from);
        for (int l = tl.first[fp]; l >= 0; l = tl.next[l]) {
            if (tl.ref[l] != to) {
                continue;
            }
            float[] v = ft.verts;
            if (ft.type[fp] == NavTile.TYPE_OFFMESH) {
                point(v, ft.vertex(fp, tl.edge[l]));
                return;
            }
            if (tl.edge[l] == 0xff) {
                NavTile tt = mesh.tile(to);
                point(tt.verts, tt.vertex(mesh.poly(to), (int) tl.bmin[l]));
                return;
            }
            int n = ft.polyVertCount[fp];
            int a = ft.vertex(fp, tl.edge[l]);
            int b = ft.vertex(fp, tl.edge[l] + 1 < n ? tl.edge[l] + 1 : 0);
            float t0 = tl.side[l] >= 0 ? tl.bmin[l] : 0;
            float t1 = tl.side[l] >= 0 ? tl.bmax[l] : 1;
            for (int k = 0; k < 3; k++) {
                portal[k] = v[a + k] + (v[b + k] - v[a + k]) * t0;
                portal[3 + k] = v[a + k] + (v[b + k] - v[a + k]) * t1;
            }
            return;
        }
    }

    private void point(float[] v, int a) {
        portal[0] = portal[3] = v[a];
        portal[1] = portal[4] = v[a + 1];
        portal[2] = portal[5] = v[a + 2];
    }

    /** Returns the number of polygons the last flood reached. */
    public int reachedCount() {
        return nodes.count();
    }

    /** Returns polygon {@code i} (0..{@link #reachedCount()}) of the last flood. */
    public int reachedRef(int i) {
        return nodes.ref[i];
    }

    /** Returns the movement spent to enter reached polygon {@code i}. */
    public float reachedCost(int i) {
        return nodes.cost[i];
    }

    /**
     * Returns the movement needed to reach {@code (x, y, z)} in polygon {@code ref}, or {@code +∞}
     * when it is out of range.
     */
    public float costTo(int ref, float x, float y, float z) {
        int n = nodes.find(ref);
        if (n < 0) {
            return Float.POSITIVE_INFINITY;
        }
        float c =
                nodes.cost[n]
                        + floodFilter.cost(
                                nodes.pos[n * 3],
                                nodes.pos[n * 3 + 1],
                                nodes.pos[n * 3 + 2],
                                x,
                                y,
                                z,
                                mesh.polyArea(ref));
        return c <= budget ? c : Float.POSITIVE_INFINITY;
    }

    /**
     * Writes the reached polygons that are only partly in range or border unreached ground (the
     * edge of the range overlay) to {@code out}; returns how many.
     */
    public int boundary(int[] out) {
        int count = 0;
        for (int n = 0, total = nodes.count(); n < total && count < out.length; n++) {
            int ref = nodes.ref[n];
            NavTile t = mesh.tile(ref);
            int p = mesh.poly(ref);
            if (t.type[p] != NavTile.TYPE_GROUND) {
                continue;
            }
            boolean edge = false;
            float areaCost = floodFilter.areaCost(t.area[p]);
            for (int k = 0; k < t.polyVertCount[p] && !edge; k++) {
                int v = t.vertex(p, k);
                float d =
                        NavMeshQuery.dist(
                                nodes.pos[n * 3],
                                nodes.pos[n * 3 + 1],
                                nodes.pos[n * 3 + 2],
                                t.verts[v],
                                t.verts[v + 1],
                                t.verts[v + 2]);
                edge = nodes.cost[n] + d * areaCost > budget;
            }
            for (int l = mesh.firstLink(ref); l >= 0 && !edge; l = mesh.nextLink(ref, l)) {
                int nb = mesh.linkTarget(ref, l);
                edge = floodFilter.passes(mesh, nb) && nodes.find(nb) < 0;
            }
            if (edge) {
                out[count++] = ref;
            }
        }
        return count;
    }

    /**
     * Rasterizes the range into {@code out} ({@code w × h} cells of {@code cell} meters from {@code
     * (originX, originZ)}, row-major along x): 255 in range, 0 out of range. Returns the number of
     * cells in range. Feed it to the render side as an overlay texture.
     */
    public int rasterize(float originX, float originZ, float cell, int w, int h, byte[] out) {
        java.util.Arrays.fill(out, 0, w * h, (byte) 0);
        int inRange = 0;
        for (int n = 0, total = nodes.count(); n < total; n++) {
            int ref = nodes.ref[n];
            NavTile t = mesh.tile(ref);
            int p = mesh.poly(ref);
            if (t.type[p] != NavTile.TYPE_GROUND) {
                continue;
            }
            t.polyBounds(p, portal, 0);
            int x0 = Math.max(0, (int) Math.floor((portal[0] - originX) / cell));
            int x1 = Math.min(w - 1, (int) Math.floor((portal[3] - originX) / cell));
            int z0 = Math.max(0, (int) Math.floor((portal[2] - originZ) / cell));
            int z1 = Math.min(h - 1, (int) Math.floor((portal[5] - originZ) / cell));
            float ex = nodes.pos[n * 3];
            float ez = nodes.pos[n * 3 + 2];
            float base = nodes.cost[n];
            float areaCost = floodFilter.areaCost(t.area[p]);
            for (int z = z0; z <= z1; z++) {
                float cz = originZ + (z + 0.5f) * cell;
                for (int x = x0; x <= x1; x++) {
                    int o = x + z * w;
                    if (out[o] != 0) {
                        continue;
                    }
                    float cx = originX + (x + 0.5f) * cell;
                    if (!NavGeometry.pointInPoly(t, p, cx, cz)) {
                        continue;
                    }
                    float dx = cx - ex;
                    float dz = cz - ez;
                    if (base + (float) Math.sqrt(dx * dx + dz * dz) * areaCost <= budget) {
                        out[o] = (byte) 255;
                        inRange++;
                    }
                }
            }
        }
        return inRange;
    }

    // Path cost -----------------------------------------------------------------------------------

    /**
     * Returns the movement cost of walking the string-pulled path {@code pts[0..count)} (x, y, z
     * each) from polygon {@code startRef}, splitting each leg by the areas it crosses; {@code +∞}
     * when a leg is blocked. Off-mesh legs cost their length times their area cost.
     */
    public float pathCost(
            NavMeshQuery q, float[] pts, int count, int startRef, QueryFilter filter) {
        float total = 0;
        int ref = startRef;
        for (int i = 0; i + 1 < count; i++) {
            float ax = pts[i * 3];
            float ay = pts[i * 3 + 1];
            float az = pts[i * 3 + 2];
            float bx = pts[i * 3 + 3];
            float by = pts[i * 3 + 4];
            float bz = pts[i * 3 + 5];
            float len = NavMeshQuery.dist(ax, ay, az, bx, by, bz);
            if (len < 1e-5f) {
                continue;
            }
            q.raycast(ref, ax, ay, az, bx, by, bz, filter, hit, rayPath);
            if (hit.hit()) {
                // Not a straight walk: an off-mesh link leg (jump), or blocked.
                int link = offMeshLinkBetween(ref, ax, az, bx, bz);
                if (link == NavMesh.NULL_REF) {
                    return Float.POSITIVE_INFINITY;
                }
                total += len * filter.areaCost(mesh.polyArea(link));
                ref = q.findNearestPoly(bx, by, bz, 0.5f, 2, 0.5f, filter, p3);
                if (ref == NavMesh.NULL_REF) {
                    return Float.POSITIVE_INFINITY;
                }
                continue;
            }
            for (int k = 0; k < hit.pathCount; k++) {
                int r = rayPath[k];
                NavTile t = mesh.tile(r);
                int p = mesh.poly(r);
                if (NavGeometry.intersectSegmentPoly2D(t, p, ax, az, bx, bz, seg, segEdges)) {
                    total += (seg[1] - seg[0]) * len * filter.areaCost(t.area[p]);
                }
            }
            ref = hit.lastRef;
        }
        return total;
    }

    /** Returns the off-mesh link from polygon {@code ref} whose ends match the leg, or 0. */
    private int offMeshLinkBetween(int ref, float ax, float az, float bx, float bz) {
        for (int l = mesh.firstLink(ref); l >= 0; l = mesh.nextLink(ref, l)) {
            int to = mesh.linkTarget(ref, l);
            NavTile t = mesh.tile(to);
            int p = mesh.poly(to);
            if (t.type[p] != NavTile.TYPE_OFFMESH) {
                continue;
            }
            int s = t.vertex(p, 0);
            int e = t.vertex(p, 1);
            if (near(t.verts, s, ax, az) && near(t.verts, e, bx, bz)
                    || near(t.verts, e, ax, az) && near(t.verts, s, bx, bz)) {
                return to;
            }
        }
        return NavMesh.NULL_REF;
    }

    private static boolean near(float[] v, int a, float x, float z) {
        float dx = v[a] - x;
        float dz = v[a + 2] - z;
        return dx * dx + dz * dz < 0.01f;
    }

    // Threat zones --------------------------------------------------------------------------------

    /**
     * Finds where the path {@code pts[0..count)} (x, y, z each) enters and leaves the reach circles
     * {@code enemies[0..enemyCount)} ({@code x, z, reach} each). Writes up to {@code out.length}
     * events ordered along the path: enemy index to {@code outEnemy}, distance along the path to
     * {@code outDistance} and {@link #ENTER} or {@link #EXIT} to {@code outKind}. A path starting
     * inside a circle and leaving it reports only the exit. Returns how many.
     */
    public static int threatCrossings(
            float[] pts,
            int count,
            float[] enemies,
            int enemyCount,
            int[] outEnemy,
            float[] outDistance,
            int[] outKind) {
        int n = 0;
        int max = outEnemy.length;
        float walked = 0;
        for (int i = 0; i + 1 < count; i++) {
            float ax = pts[i * 3];
            float az = pts[i * 3 + 2];
            float dx = pts[i * 3 + 3] - ax;
            float dz = pts[i * 3 + 5] - az;
            float segLen = (float) Math.sqrt(dx * dx + dz * dz);
            if (segLen < 1e-6f) {
                continue;
            }
            float a = dx * dx + dz * dz;
            for (int e = 0; e < enemyCount; e++) {
                float ex = enemies[e * 3];
                float ez = enemies[e * 3 + 1];
                float r = enemies[e * 3 + 2];
                float fx = ax - ex;
                float fz = az - ez;
                float b = 2 * (fx * dx + fz * dz);
                float c = fx * fx + fz * fz - r * r;
                float disc = b * b - 4 * a * c;
                if (disc <= 0) {
                    continue;
                }
                float sq = (float) Math.sqrt(disc);
                float t0 = (-b - sq) / (2 * a);
                float t1 = (-b + sq) / (2 * a);
                // Entering at t0 (if the leg starts outside), leaving at t1 (if it ends outside).
                if (t0 > 0 && t0 <= 1 && n < max) {
                    n = insert(outEnemy, outDistance, outKind, n, e, walked + t0 * segLen, ENTER);
                }
                if (t1 >= 0 && t1 < 1 && n < max) {
                    n = insert(outEnemy, outDistance, outKind, n, e, walked + t1 * segLen, EXIT);
                }
            }
            walked += segLen;
        }
        return n;
    }

    private static int insert(int[] enemy, float[] dist, int[] kind, int n, int e, float d, int k) {
        int at = n;
        while (at > 0 && dist[at - 1] > d) {
            enemy[at] = enemy[at - 1];
            dist[at] = dist[at - 1];
            kind[at] = kind[at - 1];
            at--;
        }
        enemy[at] = e;
        dist[at] = d;
        kind[at] = k;
        return n + 1;
    }
}
