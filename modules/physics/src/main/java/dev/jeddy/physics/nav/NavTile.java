package dev.jeddy.physics.nav;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * One baked navmesh tile (DESIGN §11.4), SoA: world-space vertices; convex polygons of up to {@link
 * #MAX_VERTS} vertices with, per edge, the neighbouring polygon in the tile, {@link #NO_NEIGHBOUR}
 * (a wall) or {@link #PORTAL}{@code | side} (an edge on the tile's side 0..3: −x, +z, +x, −z,
 * linked to the next tile at run time); an area id per polygon (cost class) and flags (doors and
 * the like, changed at run time). Off-mesh links (jumps, ladders) are 2-vertex polygons. A small
 * AABB tree over the polygons answers nearest-polygon queries.
 *
 * <p>Thread ownership: immutable geometry; polygon {@link #flags} are written by the game thread
 * between queries.
 */
public final class NavTile {
    /** Most vertices per polygon. */
    public static final int MAX_VERTS = 6;

    /** Neighbour value of a wall edge. */
    public static final int NO_NEIGHBOUR = -1;

    /** Neighbour flag of a portal edge on the tile's side (low bits: the side). */
    public static final int PORTAL = 0x40000000;

    /** Polygon type: a ground polygon. */
    public static final int TYPE_GROUND = 0;

    /** Polygon type: an off-mesh link (2 vertices: start, end). */
    public static final int TYPE_OFFMESH = 1;

    /** Tile column. */
    public final int tileX;

    /** Tile row. */
    public final int tileZ;

    /** Vertices, {@code x, y, z} each. */
    public final float[] verts;

    /** Number of vertices. */
    public final int vertCount;

    /** Vertex indices, {@link #MAX_VERTS} per polygon (unused slots −1). */
    public final int[] polys;

    /** Neighbour of each polygon edge (see the class comment). */
    public final int[] neighbours;

    /** Vertex count per polygon. */
    public final int[] polyVertCount;

    /** Area id per polygon (1..63; 63 is default ground). */
    public final int[] area;

    /** Polygon flags (the query filter's include and exclude masks test them). */
    public final int[] flags;

    /** Polygon type ({@link #TYPE_GROUND} or {@link #TYPE_OFFMESH}). */
    public final int[] type;

    /** Off-mesh link properties per polygon: bidirectional (1) or one-way (0). */
    public final int[] offMeshBidirectional;

    /** Off-mesh link radius (how close the endpoints snap to ground polygons). */
    public final float[] offMeshRadius;

    /** Game id of each off-mesh link (0 for ground polygons). */
    public final int[] offMeshUserId;

    /** Number of polygons. */
    public final int polyCount;

    /** Tile bounds: min x, y, z, max x, y, z. */
    public final float[] bounds = new float[6];

    // Polygon AABB tree: nodes (min xyz, max xyz) with children or a polygon.
    final float[] nodeBounds;
    final int[] nodeData; // >= 0: polygon (leaf); < 0: -(right child) (left is next)
    final int nodeCount;

    /** Creates a tile from its arrays (copied by reference) and builds its polygon tree. */
    public NavTile(
            int tileX,
            int tileZ,
            float[] verts,
            int vertCount,
            int[] polys,
            int[] neighbours,
            int[] polyVertCount,
            int[] area,
            int[] type,
            int polyCount) {
        this.tileX = tileX;
        this.tileZ = tileZ;
        this.verts = verts;
        this.vertCount = vertCount;
        this.polys = polys;
        this.neighbours = neighbours;
        this.polyVertCount = polyVertCount;
        this.area = area;
        this.type = type;
        this.polyCount = polyCount;
        this.flags = new int[polyCount];
        this.offMeshBidirectional = new int[polyCount];
        this.offMeshRadius = new float[polyCount];
        this.offMeshUserId = new int[polyCount];
        Arrays.fill(flags, 1); // walkable by default
        bounds[0] = bounds[1] = bounds[2] = Float.MAX_VALUE;
        bounds[3] = bounds[4] = bounds[5] = -Float.MAX_VALUE;
        for (int v = 0; v < vertCount; v++) {
            for (int k = 0; k < 3; k++) {
                bounds[k] = Math.min(bounds[k], verts[v * 3 + k]);
                bounds[3 + k] = Math.max(bounds[3 + k], verts[v * 3 + k]);
            }
        }
        // Polygon tree: median splits on the longest axis of the centroids.
        float[] pb = new float[Math.max(1, polyCount) * 6];
        int[] items = new int[polyCount];
        for (int p = 0; p < polyCount; p++) {
            items[p] = p;
            polyBounds(p, pb, p * 6);
        }
        int maxNodes = Math.max(1, polyCount * 2);
        nodeBounds = new float[maxNodes * 6];
        nodeData = new int[maxNodes];
        int[] count = {0};
        if (polyCount > 0) {
            buildTree(items, 0, polyCount, pb, count);
        }
        nodeCount = count[0];
    }

    /** Writes polygon {@code p}'s bounds into {@code out[o..o+6)}. */
    public void polyBounds(int p, float[] out, int o) {
        out[o] = out[o + 1] = out[o + 2] = Float.MAX_VALUE;
        out[o + 3] = out[o + 4] = out[o + 5] = -Float.MAX_VALUE;
        for (int k = 0; k < polyVertCount[p]; k++) {
            int v = polys[p * MAX_VERTS + k] * 3;
            for (int c = 0; c < 3; c++) {
                out[o + c] = Math.min(out[o + c], verts[v + c]);
                out[o + 3 + c] = Math.max(out[o + 3 + c], verts[v + c]);
            }
        }
    }

    private int buildTree(int[] items, int from, int to, float[] pb, int[] count) {
        int node = count[0]++;
        int o = node * 6;
        nodeBounds[o] = nodeBounds[o + 1] = nodeBounds[o + 2] = Float.MAX_VALUE;
        nodeBounds[o + 3] = nodeBounds[o + 4] = nodeBounds[o + 5] = -Float.MAX_VALUE;
        for (int i = from; i < to; i++) {
            int b = items[i] * 6;
            for (int c = 0; c < 3; c++) {
                nodeBounds[o + c] = Math.min(nodeBounds[o + c], pb[b + c]);
                nodeBounds[o + 3 + c] = Math.max(nodeBounds[o + 3 + c], pb[b + 3 + c]);
            }
        }
        if (to - from == 1) {
            nodeData[node] = items[from];
            return node;
        }
        int axis = 0;
        float ext = -1;
        for (int c = 0; c < 3; c += 2) {
            float e = nodeBounds[o + 3 + c] - nodeBounds[o + c];
            if (e > ext) {
                ext = e;
                axis = c;
            }
        }
        // Sort the range by centroid along the axis (insertion sort: tiles have few polygons).
        for (int i = from + 1; i < to; i++) {
            int it = items[i];
            float key = pb[it * 6 + axis] + pb[it * 6 + 3 + axis];
            int j = i - 1;
            while (j >= from && pb[items[j] * 6 + axis] + pb[items[j] * 6 + 3 + axis] > key) {
                items[j + 1] = items[j];
                j--;
            }
            items[j + 1] = it;
        }
        int mid = (from + to) >>> 1;
        buildTree(items, from, mid, pb, count);
        int right = buildTree(items, mid, to, pb, count);
        nodeData[node] = -right;
        return node;
    }

    /**
     * Visits the polygons whose bounds overlap the box, calling {@code visitor}; allocates nothing
     * ({@code stack} needs room for the tree's depth, 64 is plenty).
     */
    public void queryPolygons(
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ,
            int[] stack,
            PolyVisitor visitor) {
        if (nodeCount == 0) {
            return;
        }
        int sp = 0;
        stack[sp++] = 0;
        while (sp > 0) {
            int node = stack[--sp];
            int o = node * 6;
            if (nodeBounds[o] > maxX
                    || nodeBounds[o + 3] < minX
                    || nodeBounds[o + 1] > maxY
                    || nodeBounds[o + 4] < minY
                    || nodeBounds[o + 2] > maxZ
                    || nodeBounds[o + 5] < minZ) {
                continue;
            }
            int d = nodeData[node];
            if (d >= 0) {
                visitor.visit(this, d);
            } else {
                stack[sp++] = -d;
                stack[sp++] = node + 1;
            }
        }
    }

    /** Receives polygons from {@link #queryPolygons}. */
    @FunctionalInterface
    public interface PolyVisitor {
        /** Called with a polygon of {@code tile}. */
        void visit(NavTile tile, int poly);
    }

    /** Returns vertex {@code k} of polygon {@code p}'s index into {@link #verts} (times 3). */
    public int vertex(int p, int k) {
        return polys[p * MAX_VERTS + k] * 3;
    }

    // Serialization ---------------------------------------------------------------------------

    /** Returns the serialized size. */
    int byteSize() {
        return 4 * 4 + vertCount * 12 + polyCount * (MAX_VERTS * 8 + 4 * 4 + 4 + 4);
    }

    void write(ByteBuffer b) {
        b.putInt(tileX).putInt(tileZ).putInt(vertCount).putInt(polyCount);
        for (int i = 0; i < vertCount * 3; i++) {
            b.putFloat(verts[i]);
        }
        for (int p = 0; p < polyCount; p++) {
            for (int k = 0; k < MAX_VERTS; k++) {
                b.putInt(polys[p * MAX_VERTS + k]);
            }
            for (int k = 0; k < MAX_VERTS; k++) {
                b.putInt(neighbours[p * MAX_VERTS + k]);
            }
            b.putInt(polyVertCount[p])
                    .putInt(area[p])
                    .putInt(type[p])
                    .putInt(offMeshBidirectional[p]);
            b.putFloat(offMeshRadius[p]).putInt(offMeshUserId[p]);
        }
    }

    static NavTile read(ByteBuffer b) {
        int tx = b.getInt();
        int tz = b.getInt();
        int nv = b.getInt();
        int np = b.getInt();
        float[] v = new float[nv * 3];
        for (int i = 0; i < v.length; i++) {
            v[i] = b.getFloat();
        }
        int[] p = new int[np * MAX_VERTS];
        int[] n = new int[np * MAX_VERTS];
        int[] pvc = new int[np];
        int[] ar = new int[np];
        int[] ty = new int[np];
        int[] bi = new int[np];
        float[] rad = new float[np];
        int[] uid = new int[np];
        for (int i = 0; i < np; i++) {
            for (int k = 0; k < MAX_VERTS; k++) {
                p[i * MAX_VERTS + k] = b.getInt();
            }
            for (int k = 0; k < MAX_VERTS; k++) {
                n[i * MAX_VERTS + k] = b.getInt();
            }
            pvc[i] = b.getInt();
            ar[i] = b.getInt();
            ty[i] = b.getInt();
            bi[i] = b.getInt();
            rad[i] = b.getFloat();
            uid[i] = b.getInt();
        }
        NavTile t = new NavTile(tx, tz, v, nv, p, n, pvc, ar, ty, np);
        System.arraycopy(bi, 0, t.offMeshBidirectional, 0, np);
        System.arraycopy(rad, 0, t.offMeshRadius, 0, np);
        System.arraycopy(uid, 0, t.offMeshUserId, 0, np);
        return t;
    }
}
