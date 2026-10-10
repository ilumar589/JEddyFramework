package dev.jeddy.physics.nav;

import dev.jeddy.core.collections.IntHeap;

/**
 * Queries over a {@link NavMesh} (DESIGN §11.4): nearest polygon, polygon height, navmesh raycast,
 * constrained movement, A* pathfinding (whole or time-sliced) and string pulling. Each query object
 * owns its search nodes and scratch, so path requests run in parallel with one query per worker;
 * nothing here allocates once constructed.
 *
 * <p>Status codes ({@link #SUCCESS}, {@link #FAILURE}, {@link #IN_PROGRESS} plus detail bits) are
 * returned by the sliced calls and recorded by the others in {@link #lastStatus()}.
 *
 * <p>Thread ownership: one thread at a time; the mesh must not change while a query runs.
 */
public final class NavMeshQuery {
    /** The query failed (bad input, or no route). */
    public static final int FAILURE = 1;

    /** The query succeeded (check the detail bits). */
    public static final int SUCCESS = 1 << 1;

    /** A sliced query is still running. */
    public static final int IN_PROGRESS = 1 << 2;

    /** Detail: the path leads to the reachable polygon closest to the goal, not the goal. */
    public static final int PARTIAL = 1 << 3;

    /** Detail: the search ran out of nodes; the result may be suboptimal or partial. */
    public static final int OUT_OF_NODES = 1 << 4;

    /** Detail: the output array was too small; the result was truncated. */
    public static final int BUFFER_TOO_SMALL = 1 << 5;

    /** Straight path vertex flag: the start point. */
    public static final int STRAIGHT_START = 1;

    /** Straight path vertex flag: the end point. */
    public static final int STRAIGHT_END = 2;

    /** Straight path vertex flag: the start of an off-mesh link (its ref is the link). */
    public static final int STRAIGHT_OFFMESH = 4;

    private static final float H_SCALE = 0.999f;
    private static final int TINY_NODES = 64;

    private final NavMesh mesh;
    private final NavNodePool nodes;
    private final NavNodePool tiny;
    private final IntHeap open;
    private final int[] tinyQueue = new int[TINY_NODES];
    private final int[] stack = new int[64];
    private final float[] p3 = new float[3];
    private final float[] portal = new float[6];
    private final float[] seg = new float[2];
    private final int[] segEdges = new int[2];
    private final int[] neis = new int[8];
    private int lastStatus;

    // Nearest-polygon search state (the visitor reads it; no capturing lambdas).
    private final NavTile.PolyVisitor nearestVisitor = this::visitNearest;
    private QueryFilter nFilter;
    private int nBase;
    private float nx;
    private float ny;
    private float nz;
    private float nBestDist;
    private int nBestRef;
    private final float[] nBest = new float[3];

    // Polygon collection state.
    private final NavTile.PolyVisitor collectVisitor = this::visitCollect;
    private int[] cOut;
    private int cCount;
    private int cMax;

    // Sliced path query state.
    private int qStatus;
    private int qStartRef;
    private int qEndRef;
    private float qex;
    private float qey;
    private float qez;
    private QueryFilter qFilter;
    private int qLastBest;
    private float qLastBestCost;
    private boolean qOutOfNodes;

    /** Creates a query over {@code mesh} with {@code maxNodes} search nodes (2048 is typical). */
    public NavMeshQuery(NavMesh mesh, int maxNodes) {
        this.mesh = mesh;
        this.nodes = new NavNodePool(maxNodes);
        this.tiny = new NavNodePool(TINY_NODES);
        this.open = new IntHeap(maxNodes, maxNodes);
    }

    /** Returns the navmesh. */
    public NavMesh mesh() {
        return mesh;
    }

    /** Returns the status of the last non-sliced call. */
    public int lastStatus() {
        return lastStatus;
    }

    // Nearest polygon ----------------------------------------------------------------------------

    /**
     * Returns the polygon nearest to {@code (x, y, z)} among those whose bounds overlap the box of
     * half extents {@code (hx, hy, hz)} around it, writing the nearest point on it to {@code out};
     * 0 when there is none. Points over a polygon within the agent climb of it count as on it.
     */
    public int findNearestPoly(
            float x,
            float y,
            float z,
            float hx,
            float hy,
            float hz,
            QueryFilter filter,
            float[] out) {
        nFilter = filter;
        nx = x;
        ny = y;
        nz = z;
        nBestDist = Float.MAX_VALUE;
        nBestRef = NavMesh.NULL_REF;
        int tx0 = mesh.tileX(x - hx);
        int tx1 = mesh.tileX(x + hx);
        int tz0 = mesh.tileZ(z - hz);
        int tz1 = mesh.tileZ(z + hz);
        for (int tz = tz0; tz <= tz1; tz++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                int slot = mesh.slotAt(tx, tz);
                if (slot < 0) {
                    continue;
                }
                nBase = mesh.slotRefBase(slot);
                mesh.tileInSlot(slot)
                        .queryPolygons(
                                x - hx,
                                y - hy,
                                z - hz,
                                x + hx,
                                y + hy,
                                z + hz,
                                stack,
                                nearestVisitor);
            }
        }
        nFilter = null;
        if (nBestRef != NavMesh.NULL_REF && out != null) {
            out[0] = nBest[0];
            out[1] = nBest[1];
            out[2] = nBest[2];
        }
        lastStatus = nBestRef != NavMesh.NULL_REF ? SUCCESS : FAILURE;
        return nBestRef;
    }

    private void visitNearest(NavTile t, int p) {
        if (t.type[p] != NavTile.TYPE_GROUND) {
            return;
        }
        int ref = nBase | p;
        if (!nFilter.passes(mesh, ref)) {
            return;
        }
        boolean over = NavGeometry.closestPointOnPoly(t, p, nx, ny, nz, p3);
        float d;
        if (over) {
            float dy = Math.abs(ny - p3[1]) - mesh.walkableClimb();
            d = dy > 0 ? dy * dy : 0;
        } else {
            float dx = p3[0] - nx;
            float dy = p3[1] - ny;
            float dz = p3[2] - nz;
            d = dx * dx + dy * dy + dz * dz;
        }
        if (d < nBestDist) {
            nBestDist = d;
            nBestRef = ref;
            nBest[0] = p3[0];
            nBest[1] = p3[1];
            nBest[2] = p3[2];
        }
    }

    /**
     * Collects the ground polygons passing {@code filter} whose bounds overlap the box into {@code
     * out}; returns how many (at most {@code out.length}).
     */
    public int queryPolygons(
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ,
            QueryFilter filter,
            int[] out) {
        nFilter = filter;
        cOut = out;
        cCount = 0;
        cMax = out.length;
        for (int tz = mesh.tileZ(minZ); tz <= mesh.tileZ(maxZ); tz++) {
            for (int tx = mesh.tileX(minX); tx <= mesh.tileX(maxX); tx++) {
                int slot = mesh.slotAt(tx, tz);
                if (slot < 0) {
                    continue;
                }
                nBase = mesh.slotRefBase(slot);
                mesh.tileInSlot(slot)
                        .queryPolygons(minX, minY, minZ, maxX, maxY, maxZ, stack, collectVisitor);
            }
        }
        int n = cCount;
        lastStatus = cCount == cMax ? SUCCESS | BUFFER_TOO_SMALL : SUCCESS;
        nFilter = null;
        cOut = null;
        return n;
    }

    private void visitCollect(NavTile t, int p) {
        if (t.type[p] != NavTile.TYPE_GROUND || cCount == cMax) {
            return;
        }
        int ref = nBase | p;
        if (nFilter == null || nFilter.passes(mesh, ref)) {
            cOut[cCount++] = ref;
        }
    }

    // Polygon geometry ---------------------------------------------------------------------------

    /**
     * Writes the point of polygon {@code ref} closest to {@code (x, y, z)} to {@code out}; returns
     * whether the point is over the polygon.
     */
    public boolean closestPointOnPoly(int ref, float x, float y, float z, float[] out) {
        NavTile t = mesh.tile(ref);
        int p = mesh.poly(ref);
        if (t.type[p] == NavTile.TYPE_OFFMESH) {
            // The closest point on the link segment.
            int a = t.vertex(p, 0);
            int b = t.vertex(p, 1);
            float[] v = t.verts;
            float s = NavGeometry.segmentParam2D(x, z, v[a], v[a + 2], v[b], v[b + 2]);
            out[0] = v[a] + (v[b] - v[a]) * s;
            out[1] = v[a + 1] + (v[b + 1] - v[a + 1]) * s;
            out[2] = v[a + 2] + (v[b + 2] - v[a + 2]) * s;
            return false;
        }
        return NavGeometry.closestPointOnPoly(t, p, x, y, z, out);
    }

    /** Returns the height of polygon {@code ref} at {@code (x, z)}, or NaN when not over it. */
    public float polyHeight(int ref, float x, float z) {
        NavTile t = mesh.tile(ref);
        int p = mesh.poly(ref);
        if (t.type[p] != NavTile.TYPE_GROUND) {
            return Float.NaN;
        }
        return NavGeometry.polyHeight(t, p, x, z);
    }

    /**
     * Writes the portal between adjacent polygons {@code from} and {@code to} (left then right
     * point, 6 floats; one point twice for off-mesh links) to {@code out}. Returns {@code false}
     * when they are not linked.
     */
    public boolean portalPoints(int from, int to, float[] out) {
        NavMesh.TileLinks tl = mesh.links(from);
        for (int l = tl.first[mesh.poly(from)]; l >= 0; l = tl.next[l]) {
            if (tl.ref[l] == to) {
                portalFromLink(mesh.tile(from), mesh.poly(from), tl, l, to, out);
                return true;
            }
        }
        return false;
    }

    /**
     * Writes the portal of link {@code l} of polygon {@code fp} of {@code ft} (leading to {@code
     * to}).
     */
    private void portalFromLink(
            NavTile ft, int fp, NavMesh.TileLinks tl, int l, int to, float[] out) {
        float[] v = ft.verts;
        if (ft.type[fp] == NavTile.TYPE_OFFMESH) {
            setPortalPoint(v, ft.vertex(fp, tl.edge[l]), out);
            return;
        }
        int e = tl.edge[l];
        if (e == 0xff) { // entering an off-mesh link at its end bmin
            NavTile tt = mesh.tile(to);
            setPortalPoint(tt.verts, tt.vertex(mesh.poly(to), (int) tl.bmin[l]), out);
            return;
        }
        int n = ft.polyVertCount[fp];
        int a = ft.vertex(fp, e);
        int b = ft.vertex(fp, e + 1 < n ? e + 1 : 0);
        float t0 = 0;
        float t1 = 1;
        if (tl.side[l] >= 0) {
            t0 = tl.bmin[l];
            t1 = tl.bmax[l];
        }
        for (int k = 0; k < 3; k++) {
            float va = v[a + k];
            float d = v[b + k] - va;
            out[k] = va + d * t0;
            out[3 + k] = va + d * t1;
        }
    }

    private static void setPortalPoint(float[] v, int a, float[] out) {
        out[0] = out[3] = v[a];
        out[1] = out[4] = v[a + 1];
        out[2] = out[5] = v[a + 2];
    }

    // Raycast ------------------------------------------------------------------------------------

    /**
     * Casts a ray along the navmesh surface from {@code start} (in polygon {@code startRef}) toward
     * {@code end} in XZ, stopping at the first wall or polygon {@code filter} rejects. The polygons
     * crossed go to {@code path} (up to {@code path.length}; may be {@code null}). Used for
     * line-of-walk checks and path shortcuts.
     */
    public void raycast(
            int startRef,
            float sx,
            float sy,
            float sz,
            float ex,
            float ey,
            float ez,
            QueryFilter filter,
            NavRayHit hit,
            int[] path) {
        hit.t = 0;
        hit.normalX = 0;
        hit.normalZ = 0;
        hit.edge = -1;
        hit.lastRef = startRef;
        hit.pathCount = 0;
        int maxPath = path == null ? 0 : path.length;
        lastStatus = SUCCESS;
        if (!mesh.isValid(startRef)) {
            lastStatus = FAILURE;
            return;
        }
        int cur = startRef;
        for (int guard = 0; guard < 65_536 && cur != NavMesh.NULL_REF; guard++) {
            NavTile t = mesh.tile(cur);
            int p = mesh.poly(cur);
            hit.lastRef = cur;
            if (!NavGeometry.intersectSegmentPoly2D(t, p, sx, sz, ex, ez, seg, segEdges)) {
                return; // could not enter the polygon (start outside it): stop here
            }
            if (seg[1] > hit.t) {
                hit.t = seg[1];
            }
            if (hit.pathCount < maxPath) {
                path[hit.pathCount++] = cur;
            } else if (maxPath > 0) {
                lastStatus |= BUFFER_TOO_SMALL;
            }
            int segMax = segEdges[1];
            if (segMax == -1) {
                hit.t = Float.MAX_VALUE; // the end is inside this polygon
                return;
            }
            int next = NavMesh.NULL_REF;
            for (int l = mesh.firstLink(cur); l >= 0; l = mesh.nextLink(cur, l)) {
                if (mesh.linkEdge(cur, l) != segMax) {
                    continue;
                }
                int nb = mesh.linkTarget(cur, l);
                if (!filter.passes(mesh, nb)) {
                    continue;
                }
                if (mesh.linkSide(cur, l) < 0
                        || (mesh.linkMin(cur, l) <= 0 && mesh.linkMax(cur, l) >= 1)) {
                    next = nb;
                    break;
                }
                // A partial portal: the crossing point must lie in the linked part of the edge.
                int n = t.polyVertCount[p];
                int a = t.vertex(p, segMax);
                int b = t.vertex(p, segMax + 1 < n ? segMax + 1 : 0);
                float hx = sx + (ex - sx) * seg[1];
                float hz = sz + (ez - sz) * seg[1];
                float u =
                        NavGeometry.segmentParam2D(
                                hx, hz, t.verts[a], t.verts[a + 2], t.verts[b], t.verts[b + 2]);
                if (u >= mesh.linkMin(cur, l) - 1e-3f && u <= mesh.linkMax(cur, l) + 1e-3f) {
                    next = nb;
                    break;
                }
            }
            if (next == NavMesh.NULL_REF) {
                int n = t.polyVertCount[p];
                int a = t.vertex(p, segMax);
                int b = t.vertex(p, segMax + 1 < n ? segMax + 1 : 0);
                float dx = t.verts[b] - t.verts[a];
                float dz = t.verts[b + 2] - t.verts[a + 2];
                float len = (float) Math.sqrt(dx * dx + dz * dz);
                if (len > 0) {
                    hit.normalX = dz / len;
                    hit.normalZ = -dx / len;
                }
                hit.edge = segMax;
                return;
            }
            cur = next;
        }
    }

    // Constrained movement -----------------------------------------------------------------------

    /**
     * Moves from {@code start} (in {@code startRef}) toward {@code end} along the navmesh, sliding
     * along walls: a small breadth-first search around the move finds the reachable point nearest
     * to {@code end}. Writes it (at the surface height) to {@code outPos} and the polygons from the
     * start to it to {@code visited}; returns how many. Meant for short per-tick moves.
     */
    public int moveAlongSurface(
            int startRef,
            float sx,
            float sy,
            float sz,
            float ex,
            float ey,
            float ez,
            QueryFilter filter,
            float[] outPos,
            int[] visited) {
        lastStatus = FAILURE;
        if (!mesh.isValid(startRef)) {
            return 0;
        }
        tiny.reset();
        int startNode = tiny.get(startRef);
        tiny.flags[startNode] = NavNodePool.CLOSED;
        int head = 0;
        int tail = 0;
        tinyQueue[tail++] = startNode;
        float bestX = sx;
        float bestY = sy;
        float bestZ = sz;
        float bestDist = Float.MAX_VALUE;
        int bestNode = startNode;
        float searchX = (sx + ex) * 0.5f;
        float searchZ = (sz + ez) * 0.5f;
        float dmx = ex - sx;
        float dmz = ez - sz;
        float searchRad = (float) Math.sqrt(dmx * dmx + dmz * dmz) * 0.5f + 0.001f;
        float searchRadSqr = searchRad * searchRad;
        boolean reached = false;
        while (head < tail) {
            int cur = tinyQueue[head++];
            int curRef = tiny.ref[cur];
            NavTile t = mesh.tile(curRef);
            int p = mesh.poly(curRef);
            if (NavGeometry.pointInPoly(t, p, ex, ez)) {
                bestNode = cur;
                bestX = ex;
                bestZ = ez;
                reached = true;
                break;
            }
            int n = t.polyVertCount[p];
            float[] v = t.verts;
            for (int j = 0; j < n; j++) {
                int a = t.vertex(p, j);
                int b = t.vertex(p, j + 1 < n ? j + 1 : 0);
                int nn = 0;
                for (int l = mesh.firstLink(curRef); l >= 0; l = mesh.nextLink(curRef, l)) {
                    if (mesh.linkEdge(curRef, l) != j) {
                        continue;
                    }
                    int nb = mesh.linkTarget(curRef, l);
                    if (nn < neis.length && filter.passes(mesh, nb)) {
                        neis[nn++] = nb;
                    }
                }
                if (nn == 0) {
                    // A wall: the closest point on it is a candidate.
                    float s = NavGeometry.segmentParam2D(ex, ez, v[a], v[a + 2], v[b], v[b + 2]);
                    float px = v[a] + (v[b] - v[a]) * s;
                    float pz = v[a + 2] + (v[b + 2] - v[a + 2]) * s;
                    float dx = px - ex;
                    float dz = pz - ez;
                    float d = dx * dx + dz * dz;
                    if (d < bestDist) {
                        bestDist = d;
                        bestX = px;
                        bestZ = pz;
                        bestNode = cur;
                    }
                    continue;
                }
                if (NavGeometry.distPtSegSqr2D(searchX, searchZ, v[a], v[a + 2], v[b], v[b + 2])
                        > searchRadSqr) {
                    continue; // the edge is outside the move's neighbourhood
                }
                for (int k = 0; k < nn; k++) {
                    int node = tiny.get(neis[k]);
                    if (node < 0 || (tiny.flags[node] & NavNodePool.CLOSED) != 0) {
                        continue;
                    }
                    if (tail < tinyQueue.length) {
                        tiny.parent[node] = cur;
                        tiny.flags[node] |= NavNodePool.CLOSED;
                        tinyQueue[tail++] = node;
                    }
                }
            }
        }
        // The polygons from the start to the best node.
        int len = 0;
        for (int node = bestNode; node >= 0; node = tiny.parent[node]) {
            len++;
        }
        int written = Math.min(len, visited == null ? 0 : visited.length);
        int i = len - 1;
        for (int node = bestNode; node >= 0; node = tiny.parent[node], i--) {
            if (i < written) {
                visited[i] = tiny.ref[node];
            }
        }
        int bestRef = tiny.ref[bestNode];
        float h = polyHeight(bestRef, bestX, bestZ);
        if (h != h) {
            closestPointOnPoly(bestRef, bestX, bestY, bestZ, p3);
            h = p3[1];
        }
        outPos[0] = bestX;
        outPos[1] = h;
        outPos[2] = bestZ;
        lastStatus = SUCCESS | (written < len ? BUFFER_TOO_SMALL : 0) | (reached ? 0 : PARTIAL);
        return written;
    }

    // Pathfinding --------------------------------------------------------------------------------

    /**
     * Finds the cheapest polygon corridor from {@code start} (in {@code startRef}) to {@code end}
     * (in {@code endRef}) and writes it to {@code path}; returns its length. When the goal is
     * unreachable the corridor leads to the polygon nearest to it ({@link #PARTIAL} in {@link
     * #lastStatus()}).
     */
    public int findPath(
            int startRef,
            int endRef,
            float sx,
            float sy,
            float sz,
            float ex,
            float ey,
            float ez,
            QueryFilter filter,
            int[] path) {
        int st = initSlicedFindPath(startRef, endRef, sx, sy, sz, ex, ey, ez, filter);
        if ((st & IN_PROGRESS) != 0) {
            updateSlicedFindPath(Integer.MAX_VALUE);
        }
        return finalizeSlicedFindPath(path);
    }

    /**
     * Starts a time-sliced path search; continue it with {@link #updateSlicedFindPath} and collect
     * it with {@link #finalizeSlicedFindPath}. Returns the status.
     */
    public int initSlicedFindPath(
            int startRef,
            int endRef,
            float sx,
            float sy,
            float sz,
            float ex,
            float ey,
            float ez,
            QueryFilter filter) {
        nodes.reset();
        open.clear();
        qStartRef = startRef;
        qEndRef = endRef;
        qex = ex;
        qey = ey;
        qez = ez;
        qFilter = filter;
        qOutOfNodes = false;
        qLastBest = -1;
        if (!mesh.isValid(startRef) || !mesh.isValid(endRef)) {
            qStatus = FAILURE;
            return qStatus;
        }
        int s = nodes.get(startRef);
        nodes.pos[s * 3] = sx;
        nodes.pos[s * 3 + 1] = sy;
        nodes.pos[s * 3 + 2] = sz;
        nodes.cost[s] = 0;
        float h = dist(sx, sy, sz, ex, ey, ez) * H_SCALE * filter.minCost();
        nodes.total[s] = h;
        qLastBest = s;
        qLastBestCost = h;
        if (startRef == endRef) {
            qStatus = SUCCESS;
            return qStatus;
        }
        nodes.flags[s] = NavNodePool.OPEN;
        open.push(s, h);
        qStatus = IN_PROGRESS;
        return qStatus;
    }

    /**
     * Runs up to {@code maxIterations} node expansions of the sliced search; returns the status.
     */
    public int updateSlicedFindPath(int maxIterations) {
        if ((qStatus & IN_PROGRESS) == 0) {
            return qStatus;
        }
        QueryFilter filter = qFilter;
        float hScale = H_SCALE * filter.minCost();
        float[] pos = nodes.pos;
        int iter = 0;
        while (iter < maxIterations && !open.isEmpty()) {
            iter++;
            int best = open.pop();
            nodes.flags[best] = (nodes.flags[best] & ~NavNodePool.OPEN) | NavNodePool.CLOSED;
            int bestRef = nodes.ref[best];
            if (bestRef == qEndRef) {
                qLastBest = best;
                qStatus = SUCCESS | (qOutOfNodes ? OUT_OF_NODES : 0);
                return qStatus;
            }
            if (!mesh.isValid(bestRef)) {
                qStatus = FAILURE; // a tile went away under the search
                return qStatus;
            }
            int parentRef =
                    nodes.parent[best] >= 0 ? nodes.ref[nodes.parent[best]] : NavMesh.NULL_REF;
            NavTile bestTile = mesh.tile(bestRef);
            int bestPoly = mesh.poly(bestRef);
            NavMesh.TileLinks tl = mesh.links(bestRef);
            int bestArea = bestTile.area[bestPoly];
            float bx = pos[best * 3];
            float by = pos[best * 3 + 1];
            float bz = pos[best * 3 + 2];
            float bestCost = nodes.cost[best];
            for (int l = tl.first[bestPoly]; l >= 0; l = tl.next[l]) {
                int nb = tl.ref[l];
                if (nb == NavMesh.NULL_REF || nb == parentRef || !filter.passes(mesh, nb)) {
                    continue;
                }
                int node = nodes.get(nb);
                if (node < 0) {
                    qOutOfNodes = true;
                    continue;
                }
                int fl = nodes.flags[node];
                if (fl == 0) {
                    // First visit: the node sits at the middle of the portal it was reached by.
                    portalFromLink(bestTile, bestPoly, tl, l, nb, portal);
                    pos[node * 3] = (portal[0] + portal[3]) * 0.5f;
                    pos[node * 3 + 1] = (portal[1] + portal[4]) * 0.5f;
                    pos[node * 3 + 2] = (portal[2] + portal[5]) * 0.5f;
                }
                float nx = pos[node * 3];
                float ny = pos[node * 3 + 1];
                float nz = pos[node * 3 + 2];
                float cost = bestCost + filter.cost(bx, by, bz, nx, ny, nz, bestArea);
                float heuristic;
                if (nb == qEndRef) {
                    cost += filter.cost(nx, ny, nz, qex, qey, qez, mesh.polyArea(nb));
                    heuristic = 0;
                } else {
                    heuristic = dist(nx, ny, nz, qex, qey, qez) * hScale;
                }
                float total = cost + heuristic;
                if ((fl & (NavNodePool.OPEN | NavNodePool.CLOSED)) != 0
                        && total >= nodes.total[node]) {
                    continue;
                }
                nodes.parent[node] = best;
                nodes.cost[node] = cost;
                nodes.total[node] = total;
                nodes.flags[node] = (fl & ~NavNodePool.CLOSED) | NavNodePool.OPEN;
                open.push(node, total);
                if (heuristic < qLastBestCost) {
                    qLastBestCost = heuristic;
                    qLastBest = node;
                }
            }
        }
        if (open.isEmpty()) {
            qStatus = SUCCESS | (qOutOfNodes ? OUT_OF_NODES : 0);
        }
        return qStatus;
    }

    /**
     * Writes the corridor of the finished (or, if still running, best-so-far) sliced search to
     * {@code path} and returns its length; the status goes to {@link #lastStatus()}.
     */
    public int finalizeSlicedFindPath(int[] path) {
        if ((qStatus & FAILURE) != 0 || qLastBest < 0) {
            return failSliced();
        }
        int status = SUCCESS | (qOutOfNodes ? OUT_OF_NODES : 0);
        if (nodes.ref[qLastBest] != qEndRef) {
            status |= PARTIAL;
        }
        return writeChain(qLastBest, path, status);
    }

    /**
     * Like {@link #finalizeSlicedFindPath}, but for a search re-planning part of an {@code
     * existing} corridor: the result ends at the furthest polygon of {@code existing[0..n)} the
     * search reached (or its best guess, {@link #PARTIAL}).
     */
    public int finalizeSlicedFindPathPartial(int[] existing, int existingCount, int[] path) {
        if ((qStatus & FAILURE) != 0 || qLastBest < 0) {
            return failSliced();
        }
        int status = SUCCESS | (qOutOfNodes ? OUT_OF_NODES : 0);
        int node = -1;
        if (qStartRef == qEndRef) {
            node = qLastBest;
        } else {
            for (int i = existingCount - 1; i >= 0 && node < 0; i--) {
                node = nodes.find(existing[i]);
            }
        }
        if (node < 0) {
            status |= PARTIAL;
            node = qLastBest;
        }
        return writeChain(node, path, status);
    }

    private int failSliced() {
        lastStatus = FAILURE;
        qStatus = 0;
        qFilter = null;
        return 0;
    }

    /** Writes the parent chain ending at {@code endNode}, start first; returns its length. */
    private int writeChain(int endNode, int[] path, int status) {
        int len = 0;
        for (int n = endNode; n >= 0; n = nodes.parent[n]) {
            len++;
        }
        int written = Math.min(len, path.length);
        if (written < len) {
            status |= BUFFER_TOO_SMALL;
        }
        int i = len - 1;
        for (int n = endNode; n >= 0; n = nodes.parent[n], i--) {
            if (i < written) {
                path[i] = nodes.ref[n];
            }
        }
        lastStatus = status;
        qStatus = 0;
        qFilter = null;
        return written;
    }

    /** Returns the path cost from the start to {@code ref} in the last search, or NaN. */
    public float searchCost(int ref) {
        int n = nodes.find(ref);
        return n < 0 ? Float.NaN : nodes.cost[n];
    }

    // String pulling -----------------------------------------------------------------------------

    /**
     * Pulls the string through the corridor {@code path[0..pathCount)} from {@code start} to {@code
     * end} (funnel algorithm): writes the corner points to {@code outPoints} (3 floats each), their
     * {@code STRAIGHT_*} flags to {@code outFlags} and the polygon entered at each to {@code
     * outRefs} (either may be {@code null}); returns how many, at most {@code maxPoints}. Off-mesh
     * links appear as corners flagged {@link #STRAIGHT_OFFMESH}.
     */
    public int findStraightPath(
            float sx,
            float sy,
            float sz,
            float ex,
            float ey,
            float ez,
            int[] path,
            int pathCount,
            float[] outPoints,
            int[] outFlags,
            int[] outRefs,
            int maxPoints) {
        lastStatus = FAILURE;
        if (pathCount <= 0 || maxPoints <= 0 || !mesh.isValid(path[0])) {
            return 0;
        }
        closestPointOnPoly(path[0], sx, sy, sz, p3);
        float startX = p3[0];
        float startY = p3[1];
        float startZ = p3[2];
        if (!mesh.isValid(path[pathCount - 1])) {
            return 0;
        }
        closestPointOnPoly(path[pathCount - 1], ex, ey, ez, p3);
        float endX = p3[0];
        float endY = p3[1];
        float endZ = p3[2];

        int count = 0;
        // Start point.
        count =
                append(
                        startX,
                        startY,
                        startZ,
                        STRAIGHT_START,
                        path[0],
                        outPoints,
                        outFlags,
                        outRefs,
                        count);
        if (count >= maxPoints) {
            lastStatus = SUCCESS | BUFFER_TOO_SMALL;
            return count;
        }
        float apexX = startX;
        float apexY = startY;
        float apexZ = startZ;
        float leftX = apexX;
        float leftY = apexY;
        float leftZ = apexZ;
        float rightX = apexX;
        float rightY = apexY;
        float rightZ = apexZ;
        int apexIndex = 0;
        int leftIndex = 0;
        int rightIndex = 0;
        int leftRef = path[0];
        int rightRef = path[0];
        boolean leftOff = false;
        boolean rightOff = false;
        int status = SUCCESS;

        for (int i = 0; i < pathCount; i++) {
            float plx;
            float ply;
            float plz;
            float prx;
            float pry;
            float prz;
            boolean toOff;
            if (i + 1 < pathCount) {
                if (!portalPoints(path[i], path[i + 1], portal)) {
                    // path[i + 1] is gone: end at the closest point of path[i].
                    closestPointOnPoly(path[i], ex, ey, ez, p3);
                    count =
                            appendChecked(
                                    p3[0],
                                    p3[1],
                                    p3[2],
                                    STRAIGHT_END,
                                    NavMesh.NULL_REF,
                                    outPoints,
                                    outFlags,
                                    outRefs,
                                    count,
                                    maxPoints);
                    lastStatus = SUCCESS | PARTIAL | (count >= maxPoints ? BUFFER_TOO_SMALL : 0);
                    return count;
                }
                plx = portal[0];
                ply = portal[1];
                plz = portal[2];
                prx = portal[3];
                pry = portal[4];
                prz = portal[5];
                toOff = mesh.tile(path[i + 1]).type[mesh.poly(path[i + 1])] == NavTile.TYPE_OFFMESH;
                if (i == 0
                        && NavGeometry.distPtSegSqr2D(apexX, apexZ, plx, plz, prx, prz) < 1e-6f) {
                    if (toOff) {
                        // Standing on an off-mesh link's start: the start point is that corner.
                        if (outFlags != null) {
                            outFlags[0] |= STRAIGHT_OFFMESH;
                        }
                        if (outRefs != null) {
                            outRefs[0] = path[1];
                        }
                    }
                    continue; // starting on the first portal
                }
            } else {
                plx = prx = endX;
                ply = pry = endY;
                plz = prz = endZ;
                toOff = false;
            }

            // Right side.
            if (NavGeometry.triArea2D(apexX, apexZ, rightX, rightZ, prx, prz) <= 0) {
                if (vequal(apexX, apexY, apexZ, rightX, rightY, rightZ)
                        || NavGeometry.triArea2D(apexX, apexZ, leftX, leftZ, prx, prz) > 0) {
                    rightX = prx;
                    rightY = pry;
                    rightZ = prz;
                    rightRef = i + 1 < pathCount ? path[i + 1] : NavMesh.NULL_REF;
                    rightOff = toOff;
                    rightIndex = i;
                } else {
                    // The right side crossed the left: the left point is a corner.
                    apexX = leftX;
                    apexY = leftY;
                    apexZ = leftZ;
                    apexIndex = leftIndex;
                    int flags =
                            leftRef == NavMesh.NULL_REF
                                    ? STRAIGHT_END
                                    : leftOff ? STRAIGHT_OFFMESH : 0;
                    int before = count;
                    count =
                            append(
                                    apexX, apexY, apexZ, flags, leftRef, outPoints, outFlags,
                                    outRefs, count);
                    if (count >= maxPoints) {
                        lastStatus = status | BUFFER_TOO_SMALL;
                        return count;
                    }
                    if (count > before && flags == STRAIGHT_END) {
                        lastStatus = status;
                        return count;
                    }
                    leftX = rightX = apexX;
                    leftY = rightY = apexY;
                    leftZ = rightZ = apexZ;
                    leftIndex = rightIndex = apexIndex;
                    i = apexIndex;
                    continue;
                }
            }

            // Left side.
            if (NavGeometry.triArea2D(apexX, apexZ, leftX, leftZ, plx, plz) >= 0) {
                if (vequal(apexX, apexY, apexZ, leftX, leftY, leftZ)
                        || NavGeometry.triArea2D(apexX, apexZ, rightX, rightZ, plx, plz) < 0) {
                    leftX = plx;
                    leftY = ply;
                    leftZ = plz;
                    leftRef = i + 1 < pathCount ? path[i + 1] : NavMesh.NULL_REF;
                    leftOff = toOff;
                    leftIndex = i;
                } else {
                    apexX = rightX;
                    apexY = rightY;
                    apexZ = rightZ;
                    apexIndex = rightIndex;
                    int flags =
                            rightRef == NavMesh.NULL_REF
                                    ? STRAIGHT_END
                                    : rightOff ? STRAIGHT_OFFMESH : 0;
                    int before = count;
                    count =
                            append(
                                    apexX, apexY, apexZ, flags, rightRef, outPoints, outFlags,
                                    outRefs, count);
                    if (count >= maxPoints) {
                        lastStatus = status | BUFFER_TOO_SMALL;
                        return count;
                    }
                    if (count > before && flags == STRAIGHT_END) {
                        lastStatus = status;
                        return count;
                    }
                    leftX = rightX = apexX;
                    leftY = rightY = apexY;
                    leftZ = rightZ = apexZ;
                    leftIndex = rightIndex = apexIndex;
                    i = apexIndex;
                }
            }
        }
        count =
                appendChecked(
                        endX,
                        endY,
                        endZ,
                        STRAIGHT_END,
                        NavMesh.NULL_REF,
                        outPoints,
                        outFlags,
                        outRefs,
                        count,
                        maxPoints);
        lastStatus = status | (count >= maxPoints ? BUFFER_TOO_SMALL : 0);
        return count;
    }

    private static int appendChecked(
            float x,
            float y,
            float z,
            int flags,
            int ref,
            float[] pts,
            int[] fl,
            int[] refs,
            int count,
            int max) {
        if (count >= max) {
            return count;
        }
        return append(x, y, z, flags, ref, pts, fl, refs, count);
    }

    /** Appends a corner, or updates the last one when it is at the same place. */
    private static int append(
            float x,
            float y,
            float z,
            int flags,
            int ref,
            float[] pts,
            int[] fl,
            int[] refs,
            int count) {
        if (count > 0) {
            int o = (count - 1) * 3;
            if (vequal(pts[o], pts[o + 1], pts[o + 2], x, y, z)) {
                if (fl != null) {
                    fl[count - 1] = flags;
                }
                if (refs != null) {
                    refs[count - 1] = ref;
                }
                return count;
            }
        }
        int o = count * 3;
        pts[o] = x;
        pts[o + 1] = y;
        pts[o + 2] = z;
        if (fl != null) {
            fl[count] = flags;
        }
        if (refs != null) {
            refs[count] = ref;
        }
        return count + 1;
    }

    private static boolean vequal(float ax, float ay, float az, float bx, float by, float bz) {
        float dx = bx - ax;
        float dy = by - ay;
        float dz = bz - az;
        return dx * dx + dy * dy + dz * dz < 1e-8f;
    }

    static float dist(float ax, float ay, float az, float bx, float by, float bz) {
        float dx = bx - ax;
        float dy = by - ay;
        float dz = bz - az;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
