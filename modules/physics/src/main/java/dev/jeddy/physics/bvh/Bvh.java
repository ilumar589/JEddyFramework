package dev.jeddy.physics.bvh;

import dev.jeddy.physics.debug.DebugSink;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The flattened static collision BVH (DESIGN §11.1): 8-float nodes {@code [minX, minY, minZ, a,
 * maxX, maxY, maxZ, count]} in depth-first order, where a leaf holds {@code count > 0} triangles
 * starting at {@code a} and an interior node ({@code count == 0}) has its left child next to it and
 * its right child at index {@code a} (int bits in the float array). Triangles are stored in leaf
 * order with their three vertices inline (9 floats), so a leaf reads one contiguous range; their
 * flags and original indices sit in parallel arrays.
 *
 * <p>Queries take a caller-owned {@link RayHit} that carries the traversal stack, so any number of
 * threads can query one BVH at once without allocating.
 *
 * <p>Thread ownership: immutable once built; any thread may query.
 */
public final class Bvh {
    /** Format magic of serialized BVHs ({@code .jcol}, docs/formats/jcol.md). */
    public static final int MAGIC = 0x4C4F434A; // "JCOL"

    /** Format version. */
    public static final int VERSION = 1;

    /** Floats per node. */
    public static final int NODE_FLOATS = 8;

    /** Deepest traversal stack a query may need. */
    public static final int MAX_DEPTH = 64;

    final float[] nodes;
    final int nodeCount;
    final float[] triangles;
    final int[] flags;
    final int[] ids;
    final int triangleCount;

    Bvh(float[] nodes, int nodeCount, float[] triangles, int[] flags, int[] ids, int count) {
        this.nodes = nodes;
        this.nodeCount = nodeCount;
        this.triangles = triangles;
        this.flags = flags;
        this.ids = ids;
        this.triangleCount = count;
    }

    /** Returns the number of nodes. */
    public int nodeCount() {
        return nodeCount;
    }

    /** Returns the number of triangles. */
    public int triangleCount() {
        return triangleCount;
    }

    /** Returns the root bounds into {@code out[0..6)} (min xyz, max xyz). */
    public void bounds(float[] out) {
        if (nodeCount == 0) {
            java.util.Arrays.fill(out, 0, 6, 0f);
            return;
        }
        out[0] = nodes[0];
        out[1] = nodes[1];
        out[2] = nodes[2];
        out[3] = nodes[4];
        out[4] = nodes[5];
        out[5] = nodes[6];
    }

    /** Returns the flags of stored triangle {@code t} (in BVH order, as reported by hits). */
    public int triangleFlags(int t) {
        return flags[t];
    }

    /** Returns the original mesh index of stored triangle {@code t}. */
    public int triangleId(int t) {
        return ids[t];
    }

    /** Writes vertex {@code v} (0..2) of stored triangle {@code t} into {@code out[0..3)}. */
    public void vertex(int t, int v, float[] out) {
        int o = t * 9 + v * 3;
        out[0] = triangles[o];
        out[1] = triangles[o + 1];
        out[2] = triangles[o + 2];
    }

    /**
     * Finds the closest hit along the ray {@code o + t·d}, {@code t} in {@code [0, maxT]}, among
     * triangles whose flags share a bit with {@code mask}. {@code d} need not be normalized ({@code
     * t} is in units of {@code |d|}). Allocates nothing.
     *
     * @return {@code true} if something was hit; details in {@code hit}
     */
    public boolean raycast(
            float ox,
            float oy,
            float oz,
            float dx,
            float dy,
            float dz,
            float maxT,
            int mask,
            RayHit hit) {
        return trace(ox, oy, oz, dx, dy, dz, maxT, mask, hit, false);
    }

    /** Like {@link #raycast} but stops at the first hit found (line of sight, shadows). */
    public boolean raycastAny(
            float ox,
            float oy,
            float oz,
            float dx,
            float dy,
            float dz,
            float maxT,
            int mask,
            RayHit hit) {
        return trace(ox, oy, oz, dx, dy, dz, maxT, mask, hit, true);
    }

    private boolean trace(
            float ox,
            float oy,
            float oz,
            float dx,
            float dy,
            float dz,
            float maxT,
            int mask,
            RayHit hit,
            boolean any) {
        hit.t = Float.POSITIVE_INFINITY;
        hit.triangle = -1;
        if (nodeCount == 0) {
            return false;
        }
        float ix = 1f / dx;
        float iy = 1f / dy;
        float iz = 1f / dz;
        float best = maxT;
        int found = -1;
        float bu = 0;
        float bv = 0;
        float[] n = nodes;
        float[] tri = triangles;
        int[] fl = flags;
        int[] stack = hit.stack;
        float[] entry = hit.entry;
        float rootT = slab(n, 0, ox, oy, oz, ix, iy, iz, best);
        if (rootT == Float.POSITIVE_INFINITY) {
            return false;
        }
        int sp = 0;
        stack[sp] = 0;
        entry[sp++] = rootT;
        while (sp > 0) {
            sp--;
            if (entry[sp] > best) {
                continue; // a closer hit was found since this node was pushed
            }
            int node = stack[sp];
            int o = node * NODE_FLOATS;
            int count = Float.floatToRawIntBits(n[o + 7]);
            if (count > 0) {
                int first = Float.floatToRawIntBits(n[o + 3]);
                for (int t = first, end = first + count; t < end; t++) {
                    if ((fl[t] & mask) == 0) {
                        continue;
                    }
                    int v = t * 9;
                    // Möller–Trumbore, two-sided.
                    float e1x = tri[v + 3] - tri[v];
                    float e1y = tri[v + 4] - tri[v + 1];
                    float e1z = tri[v + 5] - tri[v + 2];
                    float e2x = tri[v + 6] - tri[v];
                    float e2y = tri[v + 7] - tri[v + 1];
                    float e2z = tri[v + 8] - tri[v + 2];
                    float px = dy * e2z - dz * e2y;
                    float py = dz * e2x - dx * e2z;
                    float pz = dx * e2y - dy * e2x;
                    float det = e1x * px + e1y * py + e1z * pz;
                    if (det > -1e-12f && det < 1e-12f) {
                        continue;
                    }
                    float inv = 1f / det;
                    float sx = ox - tri[v];
                    float sy = oy - tri[v + 1];
                    float sz = oz - tri[v + 2];
                    float u = (sx * px + sy * py + sz * pz) * inv;
                    if (u < 0f || u > 1f) {
                        continue;
                    }
                    float qx = sy * e1z - sz * e1y;
                    float qy = sz * e1x - sx * e1z;
                    float qz = sx * e1y - sy * e1x;
                    float w = (dx * qx + dy * qy + dz * qz) * inv;
                    if (w < 0f || u + w > 1f) {
                        continue;
                    }
                    float d = (e2x * qx + e2y * qy + e2z * qz) * inv;
                    if (d >= 0f && d <= best) {
                        best = d;
                        found = t;
                        bu = u;
                        bv = w;
                        if (any) {
                            return finish(hit, found, best, bu, bv);
                        }
                    }
                }
                continue;
            }
            int left = node + 1;
            int right = Float.floatToRawIntBits(n[o + 3]);
            float tl = slab(n, left * NODE_FLOATS, ox, oy, oz, ix, iy, iz, best);
            float tr = slab(n, right * NODE_FLOATS, ox, oy, oz, ix, iy, iz, best);
            // Push the farther child first so the nearer one is visited next.
            if (tl <= tr) {
                if (tr != Float.POSITIVE_INFINITY) {
                    stack[sp] = right;
                    entry[sp++] = tr;
                }
                if (tl != Float.POSITIVE_INFINITY) {
                    stack[sp] = left;
                    entry[sp++] = tl;
                }
            } else {
                if (tl != Float.POSITIVE_INFINITY) {
                    stack[sp] = left;
                    entry[sp++] = tl;
                }
                stack[sp] = right;
                entry[sp++] = tr;
            }
        }
        return found >= 0 && finish(hit, found, best, bu, bv);
    }

    private boolean finish(RayHit hit, int t, float distance, float u, float v) {
        hit.t = distance;
        hit.triangle = t;
        hit.flags = flags[t];
        hit.u = u;
        hit.v = v;
        int o = t * 9;
        float[] tri = triangles;
        float e1x = tri[o + 3] - tri[o];
        float e1y = tri[o + 4] - tri[o + 1];
        float e1z = tri[o + 5] - tri[o + 2];
        float e2x = tri[o + 6] - tri[o];
        float e2y = tri[o + 7] - tri[o + 1];
        float e2z = tri[o + 8] - tri[o + 2];
        float nx = e1y * e2z - e1z * e2y;
        float ny = e1z * e2x - e1x * e2z;
        float nz = e1x * e2y - e1y * e2x;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        float inv = len > 0 ? 1 / len : 0;
        hit.nx = nx * inv;
        hit.ny = ny * inv;
        hit.nz = nz * inv;
        return true;
    }

    /** Entry distance of the ray into node box at {@code o}, or +∞ if it misses within maxT. */
    private static float slab(
            float[] n,
            int o,
            float ox,
            float oy,
            float oz,
            float ix,
            float iy,
            float iz,
            float maxT) {
        float t1 = (n[o] - ox) * ix;
        float t2 = (n[o + 4] - ox) * ix;
        float tmin = Math.min(t1, t2);
        float tmax = Math.max(t1, t2);
        t1 = (n[o + 1] - oy) * iy;
        t2 = (n[o + 5] - oy) * iy;
        tmin = Math.max(tmin, Math.min(t1, t2));
        tmax = Math.min(tmax, Math.max(t1, t2));
        t1 = (n[o + 2] - oz) * iz;
        t2 = (n[o + 6] - oz) * iz;
        tmin = Math.max(tmin, Math.min(t1, t2));
        tmax = Math.min(tmax, Math.max(t1, t2));
        // NaN (0 × ∞ on a slab plane) fails both comparisons: treated as a miss.
        return tmax >= Math.max(tmin, 0f) && tmin <= maxT
                ? Math.max(tmin, 0f)
                : Float.POSITIVE_INFINITY;
    }

    /**
     * Visits the stored triangles whose boxes overlap the box {@code [min, max]} and share a flag
     * with {@code mask}, calling {@code visitor} with each stored triangle index. Allocates
     * nothing.
     *
     * @return the number of triangles visited
     */
    public int overlapBox(
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ,
            int mask,
            int[] stack,
            TriangleVisitor visitor) {
        if (nodeCount == 0) {
            return 0;
        }
        float[] n = nodes;
        int sp = 0;
        stack[sp++] = 0;
        int visited = 0;
        while (sp > 0) {
            int node = stack[--sp];
            int o = node * NODE_FLOATS;
            if (n[o] > maxX
                    || n[o + 4] < minX
                    || n[o + 1] > maxY
                    || n[o + 5] < minY
                    || n[o + 2] > maxZ
                    || n[o + 6] < minZ) {
                continue;
            }
            int count = Float.floatToRawIntBits(n[o + 7]);
            if (count > 0) {
                int first = Float.floatToRawIntBits(n[o + 3]);
                for (int t = first; t < first + count; t++) {
                    if ((flags[t] & mask) != 0
                            && triangleOverlapsBox(t, minX, minY, minZ, maxX, maxY, maxZ)) {
                        visited++;
                        if (!visitor.visit(t)) {
                            return visited;
                        }
                    }
                }
            } else {
                stack[sp++] = Float.floatToRawIntBits(n[o + 3]);
                stack[sp++] = node + 1;
            }
        }
        return visited;
    }

    private boolean triangleOverlapsBox(
            int t, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        int o = t * 9;
        float[] v = triangles;
        float lx = Math.min(v[o], Math.min(v[o + 3], v[o + 6]));
        float hx = Math.max(v[o], Math.max(v[o + 3], v[o + 6]));
        float ly = Math.min(v[o + 1], Math.min(v[o + 4], v[o + 7]));
        float hy = Math.max(v[o + 1], Math.max(v[o + 4], v[o + 7]));
        float lz = Math.min(v[o + 2], Math.min(v[o + 5], v[o + 8]));
        float hz = Math.max(v[o + 2], Math.max(v[o + 5], v[o + 8]));
        return lx <= maxX && hx >= minX && ly <= maxY && hy >= minY && lz <= maxZ && hz >= minZ;
    }

    /** Receives triangles from {@link #overlapBox}. */
    @FunctionalInterface
    public interface TriangleVisitor {
        /**
         * Called with a stored triangle index.
         *
         * @return {@code false} to stop
         */
        boolean visit(int triangle);
    }

    // Serialization ------------------------------------------------------------------------

    /** Returns the serialized size in bytes. */
    public int byteSize() {
        return 16 + nodeCount * NODE_FLOATS * 4 + triangleCount * (9 * 4 + 4 + 4);
    }

    /** Serializes into a new little-endian byte array ({@code .jcol}). */
    public byte[] toBytes() {
        ByteBuffer b = ByteBuffer.allocate(byteSize()).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(MAGIC).putInt(VERSION).putInt(nodeCount).putInt(triangleCount);
        for (int i = 0; i < nodeCount * NODE_FLOATS; i++) {
            b.putFloat(nodes[i]);
        }
        for (int i = 0; i < triangleCount * 9; i++) {
            b.putFloat(triangles[i]);
        }
        for (int i = 0; i < triangleCount; i++) {
            b.putInt(flags[i]);
        }
        for (int i = 0; i < triangleCount; i++) {
            b.putInt(ids[i]);
        }
        return b.array();
    }

    /**
     * Reads a serialized BVH from {@code data} (little-endian; position to limit).
     *
     * @throws IllegalArgumentException if it is not a valid {@code .jcol}
     */
    public static Bvh read(ByteBuffer data) {
        ByteBuffer b = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        if (b.remaining() < 16 || b.getInt() != MAGIC) {
            throw new IllegalArgumentException("not a .jcol collision BVH");
        }
        int version = b.getInt();
        if (version != VERSION) {
            throw new IllegalArgumentException(".jcol version " + version + " is not supported");
        }
        int nodes = b.getInt();
        int tris = b.getInt();
        long need = (long) nodes * NODE_FLOATS * 4 + (long) tris * 44;
        if (nodes < 0 || tris < 0 || need > b.remaining()) {
            throw new IllegalArgumentException(".jcol is truncated");
        }
        float[] n = new float[nodes * NODE_FLOATS];
        b.asFloatBuffer().get(n);
        b.position(b.position() + n.length * 4);
        float[] t = new float[tris * 9];
        b.asFloatBuffer().get(t);
        b.position(b.position() + t.length * 4);
        int[] f = new int[tris];
        b.asIntBuffer().get(f);
        b.position(b.position() + tris * 4);
        int[] ids = new int[tris];
        b.asIntBuffer().get(ids);
        return new Bvh(n, nodes, t, f, ids, tris);
    }

    /**
     * Draws the boxes of the tree's top {@code maxDepth} levels (debug view; allocates nothing).
     */
    public void debugDraw(DebugSink sink, int thread, int maxDepth, int argb) {
        if (nodeCount > 0) {
            debugNode(sink, thread, 0, 0, maxDepth, argb);
        }
    }

    private void debugNode(
            DebugSink sink, int thread, int node, int depth, int maxDepth, int argb) {
        int o = node * NODE_FLOATS;
        float[] n = nodes;
        sink.box(thread, n[o], n[o + 1], n[o + 2], n[o + 4], n[o + 5], n[o + 6], argb);
        if (depth + 1 >= maxDepth || Float.floatToRawIntBits(n[o + 7]) > 0) {
            return;
        }
        debugNode(sink, thread, node + 1, depth + 1, maxDepth, argb);
        debugNode(sink, thread, Float.floatToRawIntBits(n[o + 3]), depth + 1, maxDepth, argb);
    }
}
