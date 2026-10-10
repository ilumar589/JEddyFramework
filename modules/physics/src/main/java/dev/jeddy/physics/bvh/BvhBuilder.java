package dev.jeddy.physics.bvh;

import dev.jeddy.core.job.JobSystem;

import java.util.Arrays;

/**
 * Builds a {@link Bvh} with the binned surface-area heuristic (DESIGN §11.1): each node bins its
 * triangle centroids into 16 buckets per axis and takes the cheapest of the 45 split planes, or
 * becomes a leaf when that is cheaper (at most {@value #MAX_LEAF} triangles). The top of the tree
 * is built on the calling thread until there are enough independent subtrees, which are then built
 * in parallel on the job system and flattened into one depth-first array. Used offline by the
 * cooker and at load time for small dynamic sets.
 *
 * <p>Thread ownership: {@link #build} may be called from any thread; it borrows job workers.
 */
public final class BvhBuilder {
    /** Most triangles in a leaf. */
    public static final int MAX_LEAF = 8;

    private static final int BINS = 16;
    private static final float TRAVERSAL_COST = 1f;
    private static final int PARALLEL_FROM = 4096;

    private BvhBuilder() {}

    /** Builds a BVH over every triangle of {@code mesh}; {@code jobs} may be {@code null}. */
    public static Bvh build(TriangleMesh mesh, JobSystem jobs) {
        int n = mesh.triangleCount();
        float[] v = mesh.vertices();
        int[] idx = mesh.indices();
        Prims p = new Prims(n);
        for (int t = 0; t < n; t++) {
            int a = idx[t * 3] * 3;
            int b = idx[t * 3 + 1] * 3;
            int c = idx[t * 3 + 2] * 3;
            for (int k = 0; k < 3; k++) {
                float lo = Math.min(v[a + k], Math.min(v[b + k], v[c + k]));
                float hi = Math.max(v[a + k], Math.max(v[b + k], v[c + k]));
                p.min[t * 3 + k] = lo;
                p.max[t * 3 + k] = hi;
                p.centroid[t * 3 + k] = (lo + hi) * 0.5f;
            }
            p.order[t] = t;
        }
        if (n == 0) {
            return new Bvh(new float[0], 0, new float[0], new int[0], new int[0], 0);
        }
        // Top of the tree on this thread, until the open ranges are small or numerous enough.
        Tree top = new Tree(64);
        int workers = jobs == null ? 1 : jobs.threadSlots();
        int target = jobs == null || n < PARALLEL_FROM ? 0 : workers * 4;
        int[] pendingNode = new int[64];
        int[] pendingStart = new int[64];
        int[] pendingEnd = new int[64];
        int[] pendingDepth = new int[64];
        int pending = 0;
        int root = top.add();
        int[] queueNode = new int[256];
        int[] queueStart = new int[256];
        int[] queueEnd = new int[256];
        int[] queueDepth = new int[256];
        int head = 0;
        int tail = 0;
        queueNode[tail] = root;
        queueStart[tail] = 0;
        queueEnd[tail] = n;
        queueDepth[tail++] = 0;
        Scratch scratch = new Scratch();
        while (head < tail) {
            int node = queueNode[head];
            int start = queueStart[head];
            int end = queueEnd[head];
            int depth = queueDepth[head++];
            boolean small = end - start <= PARALLEL_FROM / 4;
            if (target > 0 && (small || tail - head + pending >= target)) {
                // Leave it for a parallel subtree build.
                if (pending == pendingNode.length) {
                    pendingNode = Arrays.copyOf(pendingNode, pending * 2);
                    pendingStart = Arrays.copyOf(pendingStart, pending * 2);
                    pendingEnd = Arrays.copyOf(pendingEnd, pending * 2);
                    pendingDepth = Arrays.copyOf(pendingDepth, pending * 2);
                }
                pendingNode[pending] = node;
                pendingStart[pending] = start;
                pendingEnd[pending] = end;
                pendingDepth[pending++] = depth;
                continue;
            }
            if (target == 0) {
                buildRecursive(p, top, node, start, end, depth, scratch);
                continue;
            }
            int mid = split(p, top, node, start, end, depth, scratch);
            if (mid < 0) {
                continue; // became a leaf
            }
            if (tail + 2 > queueNode.length) {
                int cap = queueNode.length * 2;
                queueNode = Arrays.copyOf(queueNode, cap);
                queueStart = Arrays.copyOf(queueStart, cap);
                queueEnd = Arrays.copyOf(queueEnd, cap);
                queueDepth = Arrays.copyOf(queueDepth, cap);
            }
            int left = top.add();
            int right = top.add();
            top.left[node] = left;
            top.right[node] = right;
            queueNode[tail] = left;
            queueStart[tail] = start;
            queueEnd[tail] = mid;
            queueDepth[tail++] = depth + 1;
            queueNode[tail] = right;
            queueStart[tail] = mid;
            queueEnd[tail] = end;
            queueDepth[tail++] = depth + 1;
        }
        // Subtrees in parallel; each top-level placeholder points at its subtree.
        Tree[] subtrees = new Tree[pending];
        if (pending > 0) {
            int[] pn = pendingNode;
            int[] ps = pendingStart;
            int[] pe = pendingEnd;
            int[] pd = pendingDepth;
            jobs.parallelFor(
                    (from, to, thread) -> {
                        Scratch s = new Scratch();
                        for (int i = from; i < to; i++) {
                            Tree t = new Tree(Math.max(16, (pe[i] - ps[i]) / 2));
                            int r = t.add();
                            buildRecursive(p, t, r, ps[i], pe[i], pd[i], s);
                            subtrees[i] = t;
                        }
                    },
                    pending,
                    1);
            for (int i = 0; i < pending; i++) {
                top.subtree[pendingNode[i]] = i;
            }
        }
        // Flatten depth first, left child adjacent.
        int total = top.count;
        for (Tree t : subtrees) {
            total += t.count;
        }
        float[] nodes = new float[total * Bvh.NODE_FLOATS];
        float[] tris = new float[n * 9];
        int[] flags = new int[n];
        int[] ids = new int[n];
        for (int i = 0; i < n; i++) {
            int t = p.order[i];
            ids[i] = t;
            flags[i] = mesh.flags()[t];
            for (int k = 0; k < 3; k++) {
                int vi = idx[t * 3 + k] * 3;
                tris[i * 9 + k * 3] = v[vi];
                tris[i * 9 + k * 3 + 1] = v[vi + 1];
                tris[i * 9 + k * 3 + 2] = v[vi + 2];
            }
        }
        int[] cursor = {0};
        flatten(top, root, subtrees, nodes, cursor);
        return new Bvh(nodes, cursor[0], tris, flags, ids, n);
    }

    private static int flatten(Tree t, int node, Tree[] subtrees, float[] out, int[] cursor) {
        if (t.subtree[node] >= 0) {
            Tree s = subtrees[t.subtree[node]];
            return flatten(s, 0, subtrees, out, cursor);
        }
        int at = cursor[0]++;
        int o = at * Bvh.NODE_FLOATS;
        System.arraycopy(t.bounds, node * 6, out, o, 3);
        System.arraycopy(t.bounds, node * 6 + 3, out, o + 4, 3);
        if (t.left[node] < 0) {
            out[o + 3] = Float.intBitsToFloat(t.first[node]);
            out[o + 7] = Float.intBitsToFloat(t.count(node));
            return at;
        }
        flatten(t, t.left[node], subtrees, out, cursor);
        int right = flatten(t, t.right[node], subtrees, out, cursor);
        out[o + 3] = Float.intBitsToFloat(right);
        out[o + 7] = Float.intBitsToFloat(0);
        return at;
    }

    private static void buildRecursive(
            Prims p, Tree t, int node, int start, int end, int depth, Scratch s) {
        int mid = split(p, t, node, start, end, depth, s);
        if (mid < 0) {
            return;
        }
        int left = t.add();
        int right = t.add();
        t.left[node] = left;
        t.right[node] = right;
        buildRecursive(p, t, left, start, mid, depth + 1, s);
        buildRecursive(p, t, right, mid, end, depth + 1, s);
    }

    /**
     * Sets node {@code node}'s bounds over {@code [start, end)} and either makes it a leaf (returns
     * -1) or partitions the range and returns the split index.
     */
    private static int split(Prims p, Tree t, int node, int start, int end, int depth, Scratch s) {
        float bx0 = Float.MAX_VALUE, by0 = Float.MAX_VALUE, bz0 = Float.MAX_VALUE;
        float bx1 = -Float.MAX_VALUE, by1 = -Float.MAX_VALUE, bz1 = -Float.MAX_VALUE;
        float cx0 = Float.MAX_VALUE, cy0 = Float.MAX_VALUE, cz0 = Float.MAX_VALUE;
        float cx1 = -Float.MAX_VALUE, cy1 = -Float.MAX_VALUE, cz1 = -Float.MAX_VALUE;
        int[] order = p.order;
        for (int i = start; i < end; i++) {
            int o = order[i] * 3;
            bx0 = Math.min(bx0, p.min[o]);
            by0 = Math.min(by0, p.min[o + 1]);
            bz0 = Math.min(bz0, p.min[o + 2]);
            bx1 = Math.max(bx1, p.max[o]);
            by1 = Math.max(by1, p.max[o + 1]);
            bz1 = Math.max(bz1, p.max[o + 2]);
            cx0 = Math.min(cx0, p.centroid[o]);
            cy0 = Math.min(cy0, p.centroid[o + 1]);
            cz0 = Math.min(cz0, p.centroid[o + 2]);
            cx1 = Math.max(cx1, p.centroid[o]);
            cy1 = Math.max(cy1, p.centroid[o + 1]);
            cz1 = Math.max(cz1, p.centroid[o + 2]);
        }
        float[] b = t.bounds;
        b[node * 6] = bx0;
        b[node * 6 + 1] = by0;
        b[node * 6 + 2] = bz0;
        b[node * 6 + 3] = bx1;
        b[node * 6 + 4] = by1;
        b[node * 6 + 5] = bz1;
        t.first[node] = start;
        t.end[node] = end;
        int count = end - start;
        if (count <= 1 || depth >= Bvh.MAX_DEPTH - 2) {
            return -1;
        }
        float nodeArea = area(bx1 - bx0, by1 - by0, bz1 - bz0);
        float bestCost = Float.MAX_VALUE;
        int bestAxis = -1;
        int bestBin = -1;
        float[] cmin = {cx0, cy0, cz0};
        float[] cext = {cx1 - cx0, cy1 - cy0, cz1 - cz0};
        for (int axis = 0; axis < 3; axis++) {
            if (cext[axis] <= 1e-12f) {
                continue;
            }
            float k = BINS * (1 - 1e-5f) / cext[axis];
            s.reset();
            for (int i = start; i < end; i++) {
                int o = order[i] * 3;
                int bin = (int) ((p.centroid[o + axis] - cmin[axis]) * k);
                s.add(bin, p.min, p.max, o);
            }
            // Sweep: right-to-left areas, then left-to-right costs.
            float rx0 = Float.MAX_VALUE, ry0 = Float.MAX_VALUE, rz0 = Float.MAX_VALUE;
            float rx1 = -Float.MAX_VALUE, ry1 = -Float.MAX_VALUE, rz1 = -Float.MAX_VALUE;
            int rc = 0;
            for (int i = BINS - 1; i > 0; i--) {
                if (s.count[i] > 0) {
                    rx0 = Math.min(rx0, s.box[i * 6]);
                    ry0 = Math.min(ry0, s.box[i * 6 + 1]);
                    rz0 = Math.min(rz0, s.box[i * 6 + 2]);
                    rx1 = Math.max(rx1, s.box[i * 6 + 3]);
                    ry1 = Math.max(ry1, s.box[i * 6 + 4]);
                    rz1 = Math.max(rz1, s.box[i * 6 + 5]);
                    rc += s.count[i];
                }
                s.rightArea[i] = rc == 0 ? 0 : area(rx1 - rx0, ry1 - ry0, rz1 - rz0);
                s.rightCount[i] = rc;
            }
            float lx0 = Float.MAX_VALUE, ly0 = Float.MAX_VALUE, lz0 = Float.MAX_VALUE;
            float lx1 = -Float.MAX_VALUE, ly1 = -Float.MAX_VALUE, lz1 = -Float.MAX_VALUE;
            int lc = 0;
            for (int i = 0; i < BINS - 1; i++) {
                if (s.count[i] > 0) {
                    lx0 = Math.min(lx0, s.box[i * 6]);
                    ly0 = Math.min(ly0, s.box[i * 6 + 1]);
                    lz0 = Math.min(lz0, s.box[i * 6 + 2]);
                    lx1 = Math.max(lx1, s.box[i * 6 + 3]);
                    ly1 = Math.max(ly1, s.box[i * 6 + 4]);
                    lz1 = Math.max(lz1, s.box[i * 6 + 5]);
                    lc += s.count[i];
                }
                int r = s.rightCount[i + 1];
                if (lc == 0 || r == 0) {
                    continue;
                }
                float cost = area(lx1 - lx0, ly1 - ly0, lz1 - lz0) * lc + s.rightArea[i + 1] * r;
                if (cost < bestCost) {
                    bestCost = cost;
                    bestAxis = axis;
                    bestBin = i;
                }
            }
        }
        float splitCost =
                bestAxis < 0
                        ? Float.MAX_VALUE
                        : TRAVERSAL_COST + bestCost / Math.max(nodeArea, 1e-20f);
        if (count <= MAX_LEAF && count <= splitCost) {
            return -1;
        }
        if (bestAxis < 0) {
            // All centroids coincide: split the range in half.
            return start + count / 2;
        }
        // Partition by bin.
        float k = BINS * (1 - 1e-5f) / cext[bestAxis];
        float origin = cmin[bestAxis];
        int i = start;
        int j = end - 1;
        while (i <= j) {
            int bin = (int) ((p.centroid[order[i] * 3 + bestAxis] - origin) * k);
            if (bin <= bestBin) {
                i++;
            } else {
                int tmp = order[i];
                order[i] = order[j];
                order[j--] = tmp;
            }
        }
        if (i == start || i == end) {
            return start + count / 2;
        }
        return i;
    }

    private static float area(float x, float y, float z) {
        return x * y + y * z + z * x;
    }

    /** Per-triangle bounds and centroids, and the triangle order being partitioned. */
    private static final class Prims {
        final float[] min;
        final float[] max;
        final float[] centroid;
        final int[] order;

        Prims(int n) {
            min = new float[n * 3];
            max = new float[n * 3];
            centroid = new float[n * 3];
            order = new int[n];
        }
    }

    /** Binning scratch, one per thread. */
    private static final class Scratch {
        final int[] count = new int[BINS];
        final float[] box = new float[BINS * 6];
        final float[] rightArea = new float[BINS];
        final int[] rightCount = new int[BINS];

        void reset() {
            Arrays.fill(count, 0);
            for (int i = 0; i < BINS; i++) {
                box[i * 6] = box[i * 6 + 1] = box[i * 6 + 2] = Float.MAX_VALUE;
                box[i * 6 + 3] = box[i * 6 + 4] = box[i * 6 + 5] = -Float.MAX_VALUE;
            }
        }

        void add(int bin, float[] min, float[] max, int o) {
            count[bin]++;
            int b = bin * 6;
            box[b] = Math.min(box[b], min[o]);
            box[b + 1] = Math.min(box[b + 1], min[o + 1]);
            box[b + 2] = Math.min(box[b + 2], min[o + 2]);
            box[b + 3] = Math.max(box[b + 3], max[o]);
            box[b + 4] = Math.max(box[b + 4], max[o + 1]);
            box[b + 5] = Math.max(box[b + 5], max[o + 2]);
        }
    }

    /** A growable tree with child links, built by one thread. */
    private static final class Tree {
        float[] bounds;
        int[] left;
        int[] right;
        int[] first;
        int[] end;
        int[] subtree;
        int count;

        Tree(int capacity) {
            bounds = new float[capacity * 6];
            left = new int[capacity];
            right = new int[capacity];
            first = new int[capacity];
            end = new int[capacity];
            subtree = new int[capacity];
        }

        int add() {
            if (count == left.length) {
                int cap = count * 2;
                bounds = Arrays.copyOf(bounds, cap * 6);
                left = Arrays.copyOf(left, cap);
                right = Arrays.copyOf(right, cap);
                first = Arrays.copyOf(first, cap);
                end = Arrays.copyOf(end, cap);
                subtree = Arrays.copyOf(subtree, cap);
            }
            int i = count++;
            left[i] = -1;
            right[i] = -1;
            subtree[i] = -1;
            return i;
        }

        int count(int node) {
            return end[node] - first[node];
        }
    }
}
