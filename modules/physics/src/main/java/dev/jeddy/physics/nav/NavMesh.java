package dev.jeddy.physics.nav;

import dev.jeddy.physics.debug.DebugSink;

import java.util.Arrays;

/**
 * The runtime navmesh (DESIGN §11.4): a grid of {@link NavTile}s added and removed at run time
 * (streaming, rebakes), with links between polygons inside a tile, across tile portals (an edge may
 * link to several polygons of the neighbouring tile, each over part of the edge) and through
 * off-mesh links. Polygons are addressed by {@code int} references {@code salt(4) | tile(12) |
 * poly(16)}; removing a tile bumps its slot's salt, so stale references fail {@link #isValid}.
 *
 * <p>Thread ownership: one writer (tile add/remove, polygon flags) between query phases; any number
 * of {@link NavMeshQuery} readers otherwise.
 */
public final class NavMesh {
    /** The invalid reference. */
    public static final int NULL_REF = 0;

    private static final int POLY_BITS = 16;
    private static final int TILE_BITS = 12;
    private static final int MAX_TILES = 1 << TILE_BITS;

    private final float originX;
    private final float originZ;
    private final float tileSize;
    private final float walkableClimb;
    private final NavTile[] tiles = new NavTile[MAX_TILES];
    private final int[] salt = new int[MAX_TILES];
    private final TileLinks[] links = new TileLinks[MAX_TILES];
    private final int[] tileByPos;
    private final int gridW;
    private final int gridH;
    private final int gridMinX;
    private final int gridMinZ;
    private int[] freeSlots = new int[MAX_TILES];
    private int freeCount;
    private int tileCount;

    /** Links of one tile's polygons (SoA pool with per-polygon lists). */
    static final class TileLinks {
        int[] first;
        int[] ref = new int[64];
        int[] edge = new int[64];
        int[] side = new int[64]; // -1 internal or off-mesh, else the portal side
        float[] bmin = new float[64];
        float[] bmax = new float[64];
        int[] next = new int[64];
        int count;
        int free = -1;

        TileLinks(int polys) {
            first = new int[polys];
            Arrays.fill(first, -1);
        }

        int add(int poly, int toRef, int edgeIndex, int portalSide, float min, float max) {
            int l;
            if (free >= 0) {
                l = free;
                free = next[l];
            } else {
                if (count == ref.length) {
                    int cap = count * 2;
                    ref = Arrays.copyOf(ref, cap);
                    edge = Arrays.copyOf(edge, cap);
                    side = Arrays.copyOf(side, cap);
                    bmin = Arrays.copyOf(bmin, cap);
                    bmax = Arrays.copyOf(bmax, cap);
                    next = Arrays.copyOf(next, cap);
                }
                l = count++;
            }
            ref[l] = toRef;
            edge[l] = edgeIndex;
            side[l] = portalSide;
            bmin[l] = min;
            bmax[l] = max;
            next[l] = first[poly];
            first[poly] = l;
            return l;
        }

        /** Removes every link of any polygon that points into tile slot {@code tile}. */
        void unlinkTile(int tileSlot) {
            for (int p = 0; p < first.length; p++) {
                int prev = -1;
                for (int l = first[p]; l >= 0; ) {
                    int nx = next[l];
                    if (tileOf(ref[l]) == tileSlot) {
                        if (prev >= 0) {
                            next[prev] = nx;
                        } else {
                            first[p] = nx;
                        }
                        next[l] = free;
                        free = l;
                    } else {
                        prev = l;
                    }
                    l = nx;
                }
            }
        }
    }

    /**
     * Creates an empty navmesh over the tile grid of {@code data} (origin, tile size, agent climb)
     * and adds its tiles.
     */
    public NavMesh(NavMeshData data) {
        this(data.originX(), data.originZ(), data.tileSize(), data.agentClimb(), 1024, 1024);
        for (NavTile t : data.tiles()) {
            addTile(t);
        }
    }

    /**
     * Creates an empty navmesh; tiles with coordinates in {@code [-gridW / 2, gridW / 2)} × {@code
     * [-gridH / 2, gridH / 2)} can be added.
     */
    public NavMesh(
            float originX,
            float originZ,
            float tileSize,
            float walkableClimb,
            int gridW,
            int gridH) {
        this.originX = originX;
        this.originZ = originZ;
        this.tileSize = tileSize;
        this.walkableClimb = walkableClimb;
        this.gridW = gridW;
        this.gridH = gridH;
        this.gridMinX = -gridW / 2;
        this.gridMinZ = -gridH / 2;
        this.tileByPos = new int[gridW * gridH];
        Arrays.fill(tileByPos, -1);
        for (int i = 0; i < MAX_TILES; i++) {
            freeSlots[i] = MAX_TILES - 1 - i;
            salt[i] = 1;
        }
        freeCount = MAX_TILES;
    }

    // References ---------------------------------------------------------------------------------

    static int tileOf(int ref) {
        return ref >>> POLY_BITS & (MAX_TILES - 1);
    }

    static int polyOf(int ref) {
        return ref & ((1 << POLY_BITS) - 1);
    }

    private int ref(int tileSlot, int poly) {
        return salt[tileSlot] << (POLY_BITS + TILE_BITS) | tileSlot << POLY_BITS | poly;
    }

    /** Returns whether {@code ref} names a polygon of a loaded tile. */
    public boolean isValid(int ref) {
        if (ref == NULL_REF) {
            return false;
        }
        int t = tileOf(ref);
        return tiles[t] != null
                && ref >>> (POLY_BITS + TILE_BITS) == salt[t]
                && polyOf(ref) < tiles[t].polyCount;
    }

    /** Returns the tile of a (valid) reference. */
    public NavTile tile(int ref) {
        return tiles[tileOf(ref)];
    }

    /** Returns the polygon index of a reference within its tile. */
    public int poly(int ref) {
        return polyOf(ref);
    }

    /** Returns the reference of polygon {@code poly} of the tile at {@code (tx, tz)}, or 0. */
    public int refAt(int tx, int tz, int poly) {
        int slot = slotAt(tx, tz);
        return slot < 0 ? NULL_REF : ref(slot, poly);
    }

    TileLinks links(int ref) {
        return links[tileOf(ref)];
    }

    /** Returns the agent climb the mesh was baked with (portal height tolerance). */
    public float walkableClimb() {
        return walkableClimb;
    }

    /** Returns the tile size in meters. */
    public float tileSize() {
        return tileSize;
    }

    /** Returns the number of loaded tiles. */
    public int tileCount() {
        return tileCount;
    }

    /** Returns the column of the tile containing world {@code x}. */
    public int tileX(float x) {
        return (int) Math.floor((x - originX) / tileSize);
    }

    /** Returns the row of the tile containing world {@code z}. */
    public int tileZ(float z) {
        return (int) Math.floor((z - originZ) / tileSize);
    }

    /** Returns the slot of the tile at {@code (tx, tz)}, or -1. */
    int slotAt(int tx, int tz) {
        int gx = tx - gridMinX;
        int gz = tz - gridMinZ;
        if (gx < 0 || gz < 0 || gx >= gridW || gz >= gridH) {
            return -1;
        }
        return tileByPos[gx + gz * gridW];
    }

    /** Returns the tile at {@code (tx, tz)}, or {@code null}. */
    public NavTile tileAt(int tx, int tz) {
        int s = slotAt(tx, tz);
        return s < 0 ? null : tiles[s];
    }

    /** Returns a slot's tile (for queries iterating slots), or {@code null}. */
    NavTile tileInSlot(int slot) {
        return tiles[slot];
    }

    int slotRefBase(int slot) {
        return ref(slot, 0);
    }

    // Tiles --------------------------------------------------------------------------------------

    /** Adds a tile, links it to its loaded neighbours and returns its slot. */
    public int addTile(NavTile t) {
        if (slotAt(t.tileX, t.tileZ) >= 0) {
            throw new IllegalStateException(
                    "tile (" + t.tileX + ", " + t.tileZ + ") is already loaded");
        }
        int gx = t.tileX - gridMinX;
        int gz = t.tileZ - gridMinZ;
        if (gx < 0 || gz < 0 || gx >= gridW || gz >= gridH || freeCount == 0) {
            throw new IllegalStateException("no room for tile (" + t.tileX + ", " + t.tileZ + ")");
        }
        int slot = freeSlots[--freeCount];
        tiles[slot] = t;
        links[slot] = new TileLinks(t.polyCount);
        tileByPos[gx + gz * gridW] = slot;
        tileCount++;
        connectInternal(slot);
        for (int side = 0; side < 4; side++) {
            int ns = slotAt(t.tileX + sideX(side), t.tileZ + sideZ(side));
            if (ns >= 0) {
                connectExternal(slot, ns, side);
                connectExternal(ns, slot, (side + 2) & 3);
            }
        }
        // Off-mesh links: this tile's, and neighbours' that end here.
        connectOffMesh(slot, slot);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int ns = slotAt(t.tileX + dx, t.tileZ + dz);
                if (ns >= 0 && ns != slot) {
                    connectOffMesh(ns, slot);
                    connectOffMesh(slot, ns);
                }
            }
        }
        return slot;
    }

    /** Removes the tile at {@code (tx, tz)} and every link into it. */
    public void removeTile(int tx, int tz) {
        int slot = slotAt(tx, tz);
        if (slot < 0) {
            return;
        }
        for (int s = 0; s < MAX_TILES; s++) {
            if (tiles[s] != null && s != slot) {
                links[s].unlinkTile(slot);
            }
        }
        tiles[slot] = null;
        links[slot] = null;
        tileByPos[(tx - gridMinX) + (tz - gridMinZ) * gridW] = -1;
        salt[slot] = (salt[slot] & 0xf) + 1 > 0xf ? 1 : salt[slot] + 1;
        freeSlots[freeCount++] = slot;
        tileCount--;
    }

    static int sideX(int side) {
        return side == 0 ? -1 : side == 2 ? 1 : 0;
    }

    static int sideZ(int side) {
        return side == 1 ? 1 : side == 3 ? -1 : 0;
    }

    private void connectInternal(int slot) {
        NavTile t = tiles[slot];
        TileLinks tl = links[slot];
        for (int p = 0; p < t.polyCount; p++) {
            if (t.type[p] != NavTile.TYPE_GROUND) {
                continue;
            }
            for (int e = 0; e < t.polyVertCount[p]; e++) {
                int nb = t.neighbours[p * NavTile.MAX_VERTS + e];
                if (nb >= 0 && (nb & NavTile.PORTAL) == 0) {
                    tl.add(p, ref(slot, nb), e, -1, 0, 1);
                }
            }
        }
    }

    /** Links portal edges of {@code from} on {@code side} to polygons of {@code to}. */
    private void connectExternal(int from, int to, int side) {
        NavTile a = tiles[from];
        NavTile b = tiles[to];
        TileLinks tl = links[from];
        int opposite = (side + 2) & 3;
        for (int p = 0; p < a.polyCount; p++) {
            if (a.type[p] != NavTile.TYPE_GROUND) {
                continue;
            }
            int n = a.polyVertCount[p];
            for (int e = 0; e < n; e++) {
                int nb = a.neighbours[p * NavTile.MAX_VERTS + e];
                if (nb == NavTile.NO_NEIGHBOUR
                        || (nb & NavTile.PORTAL) == 0
                        || (nb & 0xf) != side) {
                    continue;
                }
                int va = a.vertex(p, e);
                int vb = a.vertex(p, (e + 1) % n);
                // Slab coordinates along the shared side: z for x-sides, x for z-sides.
                int axis = side == 0 || side == 2 ? 2 : 0;
                float aPos = a.verts[va + (2 - axis)];
                float a0 = a.verts[va + axis];
                float a1 = a.verts[vb + axis];
                float ay0 = a.verts[va + 1];
                float ay1 = a.verts[vb + 1];
                float amin = Math.min(a0, a1);
                float amax = Math.max(a0, a1);
                for (int q = 0; q < b.polyCount; q++) {
                    if (b.type[q] != NavTile.TYPE_GROUND) {
                        continue;
                    }
                    int m = b.polyVertCount[q];
                    for (int f = 0; f < m; f++) {
                        int nbq = b.neighbours[q * NavTile.MAX_VERTS + f];
                        if (nbq == NavTile.NO_NEIGHBOUR
                                || (nbq & NavTile.PORTAL) == 0
                                || (nbq & 0xf) != opposite) {
                            continue;
                        }
                        int vc = b.vertex(q, f);
                        int vd = b.vertex(q, (f + 1) % m);
                        if (Math.abs(b.verts[vc + (2 - axis)] - aPos) > 0.01f) {
                            continue;
                        }
                        float b0 = b.verts[vc + axis];
                        float b1 = b.verts[vd + axis];
                        float lo = Math.max(amin, Math.min(b0, b1));
                        float hi = Math.min(amax, Math.max(b0, b1));
                        if (hi - lo < 0.01f) {
                            continue;
                        }
                        // Heights at the overlap's middle must match within the climb.
                        float mid = (lo + hi) * 0.5f;
                        float ya = lerpY(a0, ay0, a1, ay1, mid);
                        float yb = lerpY(b0, b.verts[vc + 1], b1, b.verts[vd + 1], mid);
                        if (Math.abs(ya - yb) > walkableClimb + 0.01f) {
                            continue;
                        }
                        // Part of edge e (from va to vb) covered by the neighbour, as fractions.
                        float t0 = (lo - a0) / (a1 - a0);
                        float t1 = (hi - a0) / (a1 - a0);
                        tl.add(
                                p,
                                ref(to, q),
                                e,
                                side,
                                Math.clamp(Math.min(t0, t1), 0, 1),
                                Math.clamp(Math.max(t0, t1), 0, 1));
                    }
                }
            }
        }
    }

    private static float lerpY(float x0, float y0, float x1, float y1, float x) {
        if (x1 == x0) {
            return y0;
        }
        return y0 + (y1 - y0) * (x - x0) / (x1 - x0);
    }

    /**
     * Connects the off-mesh links of tile {@code from} whose start or end lies in tile {@code to}
     * to the nearest ground polygon there.
     */
    private void connectOffMesh(int from, int to) {
        NavTile a = tiles[from];
        NavTile b = tiles[to];
        float[] near = new float[3];
        for (int p = 0; p < a.polyCount; p++) {
            if (a.type[p] != NavTile.TYPE_OFFMESH) {
                continue;
            }
            float r = a.offMeshRadius[p];
            for (int end = 0; end < 2; end++) {
                if (from != to && end == 0) {
                    continue; // starts are always in their own tile
                }
                int v = a.vertex(p, end);
                float x = a.verts[v];
                float y = a.verts[v + 1];
                float z = a.verts[v + 2];
                if (tileX(x) != b.tileX || tileZ(z) != b.tileZ) {
                    continue;
                }
                int q = nearestGroundPoly(b, x, y, z, r, near);
                if (q < 0) {
                    continue;
                }
                int offRef = ref(from, p);
                int groundRef = ref(to, q);
                // off-mesh → ground at this end.
                links[from].add(p, groundRef, end, -1, 0, 1);
                // ground → off-mesh where travel may enter: the start, or either end if two-way.
                if (end == 0 || a.offMeshBidirectional[p] != 0) {
                    links[to].add(q, offRef, 0xff, -1, end, end);
                }
            }
        }
    }

    /** Nearest ground polygon of tile {@code t} within {@code r} (2D) of the point, or -1. */
    int nearestGroundPoly(NavTile t, float x, float y, float z, float r, float[] out) {
        int best = -1;
        float bestD = Float.MAX_VALUE;
        for (int p = 0; p < t.polyCount; p++) {
            if (t.type[p] != NavTile.TYPE_GROUND) {
                continue;
            }
            NavGeometry.closestPointOnPoly(t, p, x, y, z, out);
            float dx = out[0] - x;
            float dz = out[2] - z;
            float dy = out[1] - y;
            float d = dx * dx + dz * dz;
            if (d <= r * r && Math.abs(dy) <= Math.max(walkableClimb, r) * 2 && d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    /** Sets the flags of polygon {@code ref} (doors, disabled areas). */
    public void setPolyFlags(int ref, int flags) {
        tile(ref).flags[polyOf(ref)] = flags;
    }

    /** Returns the flags of polygon {@code ref}. */
    public int polyFlags(int ref) {
        return tile(ref).flags[polyOf(ref)];
    }

    /** Returns the area of polygon {@code ref}. */
    public int polyArea(int ref) {
        return tile(ref).area[polyOf(ref)];
    }

    /** Returns the first link of polygon {@code ref} (iterate with {@link #nextLink}), or -1. */
    public int firstLink(int ref) {
        return links[tileOf(ref)].first[polyOf(ref)];
    }

    /** Returns the link after {@code link} of polygon {@code ref}'s list, or -1. */
    public int nextLink(int ref, int link) {
        return links[tileOf(ref)].next[link];
    }

    /** Returns the polygon a link of polygon {@code ref} leads to. */
    public int linkTarget(int ref, int link) {
        return links[tileOf(ref)].ref[link];
    }

    /** Returns the edge of polygon {@code ref} a link crosses (0xff: entering an off-mesh link). */
    public int linkEdge(int ref, int link) {
        return links[tileOf(ref)].edge[link];
    }

    /** Returns the tile side of a portal link, or -1. */
    public int linkSide(int ref, int link) {
        return links[tileOf(ref)].side[link];
    }

    /** Returns where along its edge a portal link starts (0..1). */
    public float linkMin(int ref, int link) {
        return links[tileOf(ref)].bmin[link];
    }

    /** Returns where along its edge a portal link ends (0..1). */
    public float linkMax(int ref, int link) {
        return links[tileOf(ref)].bmax[link];
    }

    /** Returns the highest tile slot in use plus one (for iterating). */
    int slotLimit() {
        return MAX_TILES;
    }

    // Debug view ---------------------------------------------------------------------------------

    /** Debug colors: walls, internal edges, linked portals, unlinked portals, links, disabled. */
    private static final int WALL = 0xffe0e0e0;

    private static final int INNER = 0x6080a0c0;
    private static final int PORTAL_LINKED = 0xff40c0ff;
    private static final int PORTAL_OPEN = 0xffff9020;
    private static final int LINK_TWO_WAY = 0xff40ff60;
    private static final int LINK_ONE_WAY = 0xffffe040;
    private static final int DISABLED = 0xffff3030;

    /**
     * Draws the navmesh (debug view): walls, internal edges, portals (linked or not), off-mesh
     * links as arcs and crosses on disabled polygons, lifted {@code lift} meters. Allocates
     * nothing.
     */
    public void debugDraw(DebugSink sink, int thread, float lift) {
        for (int slot = 0; slot < MAX_TILES; slot++) {
            NavTile t = tiles[slot];
            if (t == null) {
                continue;
            }
            TileLinks tl = links[slot];
            float[] v = t.verts;
            for (int p = 0; p < t.polyCount; p++) {
                int n = t.polyVertCount[p];
                if (t.type[p] == NavTile.TYPE_OFFMESH) {
                    int a = t.vertex(p, 0);
                    int b = t.vertex(p, 1);
                    sink.arc(
                            thread,
                            v[a],
                            v[a + 1] + lift,
                            v[a + 2],
                            v[b],
                            v[b + 1] + lift,
                            v[b + 2],
                            1f,
                            t.offMeshBidirectional[p] != 0 ? LINK_TWO_WAY : LINK_ONE_WAY);
                    continue;
                }
                for (int e = 0; e < n; e++) {
                    int a = t.vertex(p, e);
                    int b = t.vertex(p, e + 1 < n ? e + 1 : 0);
                    int nb = t.neighbours[p * NavTile.MAX_VERTS + e];
                    int color;
                    if (nb == NavTile.NO_NEIGHBOUR) {
                        color = WALL;
                    } else if ((nb & NavTile.PORTAL) == 0) {
                        if (nb < p) {
                            continue; // drawn from the other side
                        }
                        color = INNER;
                    } else {
                        color = PORTAL_OPEN;
                        for (int l = tl.first[p]; l >= 0; l = tl.next[l]) {
                            if (tl.edge[l] == e) {
                                color = PORTAL_LINKED;
                                break;
                            }
                        }
                    }
                    sink.line(
                            thread,
                            v[a],
                            v[a + 1] + lift,
                            v[a + 2],
                            v[b],
                            v[b + 1] + lift,
                            v[b + 2],
                            color);
                }
                if ((t.flags[p] & NavFlags.DISABLED) != 0) {
                    float cx = 0;
                    float cy = 0;
                    float cz = 0;
                    for (int k = 0; k < n; k++) {
                        int a = t.vertex(p, k);
                        cx += v[a] / n;
                        cy += v[a + 1] / n;
                        cz += v[a + 2] / n;
                    }
                    sink.line(
                            thread, cx - 0.3f, cy + lift, cz - 0.3f, cx + 0.3f, cy + lift,
                            cz + 0.3f, DISABLED);
                    sink.line(
                            thread, cx - 0.3f, cy + lift, cz + 0.3f, cx + 0.3f, cy + lift,
                            cz - 0.3f, DISABLED);
                }
            }
        }
    }

    /** Draws the outlines of polygons {@code refs[0..n)} (movement range, corridors). */
    public void debugDrawPolygons(
            DebugSink sink, int thread, int[] refs, int n, float lift, int argb) {
        for (int i = 0; i < n; i++) {
            if (!isValid(refs[i])) {
                continue;
            }
            NavTile t = tile(refs[i]);
            int p = polyOf(refs[i]);
            int c = t.polyVertCount[p];
            if (t.type[p] != NavTile.TYPE_GROUND) {
                continue;
            }
            for (int e = 0; e < c; e++) {
                int a = t.vertex(p, e);
                int b = t.vertex(p, e + 1 < c ? e + 1 : 0);
                sink.line(
                        thread,
                        t.verts[a],
                        t.verts[a + 1] + lift,
                        t.verts[a + 2],
                        t.verts[b],
                        t.verts[b + 1] + lift,
                        t.verts[b + 2],
                        argb);
            }
        }
    }

    /** Draws a polyline {@code pts[0..n)} (x, y, z each; straight paths) lifted {@code lift}. */
    public static void debugDrawPath(
            DebugSink sink, int thread, float[] pts, int n, float lift, int argb) {
        for (int i = 0; i + 1 < n; i++) {
            int o = i * 3;
            sink.line(
                    thread,
                    pts[o],
                    pts[o + 1] + lift,
                    pts[o + 2],
                    pts[o + 3],
                    pts[o + 4] + lift,
                    pts[o + 5],
                    argb);
        }
    }
}
