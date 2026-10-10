package dev.jeddy.scene;

import dev.jeddy.core.collections.SparseSet;
import dev.jeddy.core.handle.Handles;
import dev.jeddy.core.job.JobKernel;
import dev.jeddy.core.job.JobSystem;
import dev.jeddy.math.AffineOps;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The transform hierarchy (TASKS M5-02, DESIGN §13.1): local position, rotation and scale in SoA
 * columns, a parent per entity, and world transforms as row-major 3×4 blocks ({@link AffineOps},
 * the GPU instance layout) in {@link #world()}.
 *
 * <p>{@link #update} recomputes only what changed: an entity whose local transform was set, and
 * every descendant of an entity whose world transform changed. It walks a depth-sorted order
 * (parents before children), one depth level at a time; levels with many entities run in parallel
 * on the job system, since everything a level reads (its parents' world transforms) was finished by
 * the level before (D28). Data stays in dense slots (swap-remove); only the traversal order, an
 * {@code int} permutation, is rebuilt after structural changes (add, remove, reparent).
 *
 * <p>Static entities ({@link #setStatic}) are marked for consumers such as render extraction, which
 * upload them once; their world transforms are computed like any other, so they cost nothing per
 * frame while they do not change.
 *
 * <p>Thread ownership: the owner thread (the game thread) for every method; {@link #update} borrows
 * job workers internally.
 */
public final class TransformStore implements EntityStore {
    private static final byte DIRTY = 1;
    private static final byte STATIC = 2;
    private static final int PARALLEL_LEVEL = 2048;
    private static final int GRAIN = 512;
    private static final int STRIDE = AffineOps.STRIDE;

    private final EntityPool pool;
    private final SparseSet members = new SparseSet(1024, 64);
    private int capacity;
    private int[] entities;
    private float[] px, py, pz, qx, qy, qz, qw, sx, sy, sz;
    private int[] parentEntity; // handle of the parent, or NULL
    private int[] parentSlot; // dense index of the parent, or -1 (rebuilt with the order)
    private int[] childCount;
    private byte[] flags;
    private int[] changedFrame; // the update in which the world transform last changed
    private float[] world;

    private int[] order = new int[0];
    private int[] depth = new int[0];
    private int[] levelStart = new int[2];
    private boolean[] levelDirty = new boolean[1]; // a local transform changed in the level
    private int levelCount;
    private boolean orderDirty;
    private int frame;

    private final LevelKernel kernel = new LevelKernel();

    /** Creates a store and registers it with {@code pool}. */
    public TransformStore(EntityPool pool, int initialCapacity) {
        this.pool = pool;
        allocate(Math.max(16, initialCapacity));
        pool.register(this);
    }

    private void allocate(int cap) {
        capacity = cap;
        entities = grow(entities, cap);
        px = grow(px, cap);
        py = grow(py, cap);
        pz = grow(pz, cap);
        qx = grow(qx, cap);
        qy = grow(qy, cap);
        qz = grow(qz, cap);
        qw = grow(qw, cap);
        sx = grow(sx, cap);
        sy = grow(sy, cap);
        sz = grow(sz, cap);
        parentEntity = grow(parentEntity, cap);
        parentSlot = grow(parentSlot, cap);
        childCount = grow(childCount, cap);
        flags = flags == null ? new byte[cap] : Arrays.copyOf(flags, cap);
        changedFrame = grow(changedFrame, cap);
        world = world == null ? new float[cap * STRIDE] : Arrays.copyOf(world, cap * STRIDE);
    }

    private static float[] grow(float[] a, int cap) {
        return a == null ? new float[cap] : Arrays.copyOf(a, cap);
    }

    private static int[] grow(int[] a, int cap) {
        return a == null ? new int[cap] : Arrays.copyOf(a, cap);
    }

    // ---------------------------------------------------------------------------------------
    // Structure

    /** Gives {@code entity} an identity transform with no parent; returns its dense index. */
    public int add(int entity) {
        return add(entity, Handles.NULL);
    }

    /**
     * Gives {@code entity} an identity local transform under {@code parent} ({@link Handles#NULL}
     * for a root); returns its dense index.
     */
    public int add(int entity, int parent) {
        pool.check(entity);
        int index = Handles.index(entity);
        if (members.indexOf(index) >= 0) {
            throw new IllegalStateException(
                    "entity " + Handles.toString(entity) + " has a transform");
        }
        int ps = parent == Handles.NULL ? -1 : slotOf(parent);
        int s = members.add(index);
        if (s == capacity) {
            allocate(capacity * 2);
        }
        entities[s] = entity;
        px[s] = 0;
        py[s] = 0;
        pz[s] = 0;
        qx[s] = 0;
        qy[s] = 0;
        qz[s] = 0;
        qw[s] = 1;
        sx[s] = 1;
        sy[s] = 1;
        sz[s] = 1;
        parentEntity[s] = parent;
        childCount[s] = 0;
        if (ps >= 0) {
            childCount[ps]++;
        }
        flags[s] = DIRTY;
        changedFrame[s] = -1;
        AffineOps.identity(world, s * STRIDE);
        orderDirty = true;
        return s;
    }

    /**
     * Removes {@code entity}'s transform. Its children move to its parent, keeping their local
     * transforms (so they move in the world unless the removed transform was the identity).
     */
    @Override
    public void remove(int entity) {
        int index = Handles.index(entity);
        int s = members.indexOf(index);
        if (s < 0 || entities[s] != entity) {
            return;
        }
        int grandParent = parentEntity[s];
        int gs = grandParent == Handles.NULL ? -1 : members.indexOf(Handles.index(grandParent));
        int n = members.size();
        if (childCount[s] > 0) {
            for (int i = 0; i < n; i++) {
                if (parentEntity[i] == entity) {
                    parentEntity[i] = grandParent;
                    flags[i] |= DIRTY;
                }
            }
            if (gs >= 0) {
                childCount[gs] += childCount[s];
            }
        }
        if (gs >= 0) {
            childCount[gs]--;
        }
        int last = n - 1;
        members.remove(index);
        if (s != last) {
            entities[s] = entities[last];
            px[s] = px[last];
            py[s] = py[last];
            pz[s] = pz[last];
            qx[s] = qx[last];
            qy[s] = qy[last];
            qz[s] = qz[last];
            qw[s] = qw[last];
            sx[s] = sx[last];
            sy[s] = sy[last];
            sz[s] = sz[last];
            parentEntity[s] = parentEntity[last];
            childCount[s] = childCount[last];
            flags[s] = flags[last];
            changedFrame[s] = changedFrame[last];
            System.arraycopy(world, last * STRIDE, world, s * STRIDE, STRIDE);
        }
        orderDirty = true;
    }

    /**
     * Moves {@code entity} under {@code parent} ({@link Handles#NULL} for a root), keeping its
     * local transform.
     *
     * @throws IllegalArgumentException if that would make a cycle
     */
    public void setParent(int entity, int parent) {
        int s = slotOf(entity);
        if (parent != Handles.NULL) {
            int p = slotOf(parent);
            for (int a = p, guard = 0; ; guard++) {
                if (entities[a] == entity) {
                    throw new IllegalArgumentException("reparenting would create a cycle");
                }
                int up = parentEntity[a];
                if (up == Handles.NULL || guard > members.size()) {
                    break;
                }
                a = members.indexOf(Handles.index(up));
            }
        }
        if (parentEntity[s] != parent) {
            int old = parentEntity[s];
            if (old != Handles.NULL) {
                childCount[members.indexOf(Handles.index(old))]--;
            }
            if (parent != Handles.NULL) {
                childCount[members.indexOf(Handles.index(parent))]++;
            }
            parentEntity[s] = parent;
            flags[s] |= DIRTY;
            orderDirty = true;
        }
    }

    /** Returns the parent of {@code entity}, or {@link Handles#NULL}. */
    public int parent(int entity) {
        return parentEntity[slotOf(entity)];
    }

    /** Returns {@code true} if {@code entity} has a transform. */
    public boolean has(int entity) {
        int s = members.indexOf(Handles.index(entity));
        return s >= 0 && entities[s] == entity;
    }

    /** Returns the dense index of {@code entity} (its block in {@link #world()}), or -1. */
    public int indexOf(int entity) {
        int s = members.indexOf(Handles.index(entity));
        return s >= 0 && entities[s] == entity ? s : -1;
    }

    /** Returns the number of transforms. */
    public int size() {
        return members.size();
    }

    /** Returns the entity at dense index {@code s}. */
    public int entityAt(int s) {
        return entities[s];
    }

    private int slotOf(int entity) {
        int s = members.indexOf(Handles.index(entity));
        if (s < 0 || entities[s] != entity) {
            throw new IllegalArgumentException(
                    "entity " + Handles.toString(entity) + " has no transform");
        }
        return s;
    }

    // ---------------------------------------------------------------------------------------
    // Local transforms

    /** Sets the local position. */
    public void setPosition(int entity, float x, float y, float z) {
        int s = slotOf(entity);
        px[s] = x;
        py[s] = y;
        pz[s] = z;
        markDirty(s);
    }

    /** Sets the local rotation (a unit quaternion). */
    public void setRotation(int entity, float x, float y, float z, float w) {
        int s = slotOf(entity);
        qx[s] = x;
        qy[s] = y;
        qz[s] = z;
        qw[s] = w;
        markDirty(s);
    }

    /** Sets the local scale. */
    public void setScale(int entity, float x, float y, float z) {
        int s = slotOf(entity);
        sx[s] = x;
        sy[s] = y;
        sz[s] = z;
        markDirty(s);
    }

    private void markDirty(int s) {
        flags[s] |= DIRTY;
        if (!orderDirty) {
            levelDirty[depth[s]] = true;
        }
    }

    /** Marks {@code entity} static (render may upload it once) or dynamic. */
    public void setStatic(int entity, boolean value) {
        int s = slotOf(entity);
        flags[s] = (byte) (value ? flags[s] | STATIC : flags[s] & ~STATIC);
    }

    /** Returns {@code true} if {@code entity} is marked static. */
    public boolean isStatic(int entity) {
        return (flags[slotOf(entity)] & STATIC) != 0;
    }

    /** Returns local position component {@code axis} (0 x, 1 y, 2 z). */
    public float position(int entity, int axis) {
        int s = slotOf(entity);
        return axis == 0 ? px[s] : axis == 1 ? py[s] : pz[s];
    }

    /** Returns local rotation component {@code i} (0 x, 1 y, 2 z, 3 w). */
    public float rotation(int entity, int i) {
        int s = slotOf(entity);
        return i == 0 ? qx[s] : i == 1 ? qy[s] : i == 2 ? qz[s] : qw[s];
    }

    /** Returns local scale component {@code axis}. */
    public float scale(int entity, int axis) {
        int s = slotOf(entity);
        return axis == 0 ? sx[s] : axis == 1 ? sy[s] : sz[s];
    }

    // ---------------------------------------------------------------------------------------
    // World transforms

    /**
     * Returns the world transforms: 12 floats (row-major 3×4) per dense index, valid after {@link
     * #update}. Replaced when the store grows.
     */
    public float[] world() {
        return world;
    }

    /** Returns the number of the last {@link #update} (starts at 1). */
    public int frame() {
        return frame;
    }

    /**
     * Returns {@code true} if the world transform at dense index {@code s} changed in the last
     * update.
     */
    public boolean changedInLastUpdate(int s) {
        return changedFrame[s] == frame;
    }

    /** Returns {@code true} if the entity at dense index {@code s} is static. */
    public boolean isStaticAt(int s) {
        return (flags[s] & STATIC) != 0;
    }

    /**
     * Recomputes changed world transforms. Allocates nothing unless the structure changed (then the
     * traversal order may grow).
     *
     * @param jobs workers for large depth levels, or {@code null} to run on this thread
     * @return the number of world transforms that changed
     */
    public int update(JobSystem jobs) {
        if (orderDirty) {
            rebuildOrder();
        }
        frame++;
        kernel.frame = frame;
        int changed = 0;
        boolean previousLevelChanged = false;
        for (int l = 0; l < levelCount; l++) {
            if (!levelDirty[l] && !previousLevelChanged) {
                continue; // nothing set here and no parent moved: skip the scan
            }
            levelDirty[l] = false;
            int start = levelStart[l];
            int end = levelStart[l + 1];
            int n;
            kernel.levelBase = start;
            kernel.changed.set(0);
            if (jobs != null && end - start >= PARALLEL_LEVEL) {
                jobs.parallelFor(kernel, end - start, GRAIN);
                n = kernel.changed.get();
            } else {
                n = kernel.process(start, end);
            }
            changed += n;
            previousLevelChanged = n > 0;
        }
        return changed;
    }

    /** Sorts the dense indices by depth (counting sort) and resolves parent slots. */
    private void rebuildOrder() {
        int n = members.size();
        if (order.length < n) {
            order = new int[capacity];
            depth = new int[capacity];
        }
        for (int s = 0; s < n; s++) {
            int p = parentEntity[s];
            if (p == Handles.NULL) {
                parentSlot[s] = -1;
            } else {
                int ps = members.indexOf(Handles.index(p));
                if (ps < 0 || entities[ps] != p) {
                    parentEntity[s] = Handles.NULL; // the parent was destroyed with no transform
                    parentSlot[s] = -1;
                } else {
                    parentSlot[s] = ps;
                }
            }
            depth[s] = -1;
        }
        int maxDepth = 0;
        for (int s = 0; s < n; s++) {
            maxDepth = Math.max(maxDepth, depthOf(s));
        }
        levelCount = n == 0 ? 0 : maxDepth + 1;
        if (levelStart.length < levelCount + 1) {
            levelStart = new int[levelCount + 1];
            levelDirty = new boolean[levelCount + 1];
        }
        Arrays.fill(levelDirty, true); // conservative after a structural change
        Arrays.fill(levelStart, 0, levelCount + 1, 0);
        for (int s = 0; s < n; s++) {
            levelStart[depth[s] + 1]++;
        }
        for (int l = 0; l < levelCount; l++) {
            levelStart[l + 1] += levelStart[l];
        }
        // Fill using levelStart as cursors, then shift back.
        for (int s = 0; s < n; s++) {
            order[levelStart[depth[s]]++] = s;
        }
        for (int l = levelCount; l > 0; l--) {
            levelStart[l] = levelStart[l - 1];
        }
        levelStart[0] = 0;
        orderDirty = false;
    }

    /** Depth of slot {@code s}, memoized; iterative so deep chains cannot overflow the stack. */
    private int depthOf(int s) {
        if (depth[s] >= 0) {
            return depth[s];
        }
        int d = 0;
        int a = s;
        while (parentSlot[a] >= 0 && depth[a] < 0) {
            a = parentSlot[a];
            d++;
            if (d > members.size()) {
                throw new IllegalStateException("cycle in the transform hierarchy");
            }
        }
        int base = depth[a] >= 0 ? depth[a] : 0;
        int total = base + d;
        // Fill in the chain.
        int k = total;
        for (int b = s; b != a; b = parentSlot[b]) {
            depth[b] = k--;
        }
        depth[a] = base;
        return total;
    }

    /** Processes one depth level; ranges are relative to {@link #levelBase}. */
    private final class LevelKernel implements JobKernel {
        int levelBase;
        int frame;
        final AtomicInteger changed = new AtomicInteger();
        private final float[][] scratch = new float[JobSystem.MAX_EXTERNAL_THREADS + 257][];

        LevelKernel() {
            for (int i = 0; i < scratch.length; i++) {
                scratch[i] = new float[STRIDE];
            }
        }

        @Override
        public void run(int start, int end, int threadIndex) {
            int n = process(levelBase + start, levelBase + end, threadIndex);
            if (n != 0) {
                changed.addAndGet(n);
            }
        }

        int process(int start, int end) {
            return process(start, end, scratch.length - 1);
        }

        private int process(int start, int end, int threadIndex) {
            float[] local = scratch[threadIndex];
            int[] ord = order;
            int[] parents = parentSlot;
            byte[] f = flags;
            int[] changedAt = changedFrame;
            float[] w = world;
            int fr = frame;
            int n = 0;
            for (int k = start; k < end; k++) {
                int s = ord[k];
                int p = parents[s];
                boolean dirty = (f[s] & DIRTY) != 0;
                if (!dirty && (p < 0 || changedAt[p] != fr)) {
                    continue;
                }
                if (p < 0) {
                    AffineOps.composeTRS(
                            px[s],
                            py[s],
                            pz[s],
                            qx[s],
                            qy[s],
                            qz[s],
                            qw[s],
                            sx[s],
                            sy[s],
                            sz[s],
                            w,
                            s * STRIDE);
                } else {
                    AffineOps.composeTRS(
                            px[s], py[s], pz[s], qx[s], qy[s], qz[s], qw[s], sx[s], sy[s], sz[s],
                            local, 0);
                    AffineOps.mul(w, p * STRIDE, local, 0, w, s * STRIDE);
                }
                if (dirty) {
                    f[s] &= ~DIRTY;
                }
                changedAt[s] = fr;
                n++;
            }
            return n;
        }
    }
}
