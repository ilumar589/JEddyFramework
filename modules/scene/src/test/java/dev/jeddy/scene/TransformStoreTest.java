package dev.jeddy.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.core.handle.Handles;
import dev.jeddy.core.job.JobSystem;
import dev.jeddy.math.AffineOps;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Random;

/** The transform hierarchy against a recursive reference (TASKS M5-02). */
class TransformStoreTest {
    private static JobSystem jobs;

    @BeforeAll
    static void start() {
        jobs = new JobSystem(3);
    }

    @AfterAll
    static void stop() {
        jobs.close();
    }

    /** Reference: world(e) = world(parent(e)) * TRS(e), recursively, from scratch. */
    private static float[] reference(TransformStore t, int entity) {
        float[] local = new float[12];
        AffineOps.composeTRS(
                t.position(entity, 0),
                t.position(entity, 1),
                t.position(entity, 2),
                t.rotation(entity, 0),
                t.rotation(entity, 1),
                t.rotation(entity, 2),
                t.rotation(entity, 3),
                t.scale(entity, 0),
                t.scale(entity, 1),
                t.scale(entity, 2),
                local,
                0);
        int p = t.parent(entity);
        if (p == Handles.NULL) {
            return local;
        }
        float[] out = new float[12];
        AffineOps.mul(reference(t, p), 0, local, 0, out, 0);
        return out;
    }

    private static void assertMatchesReference(TransformStore t) {
        float[] w = t.world();
        for (int s = 0; s < t.size(); s++) {
            float[] ref = reference(t, t.entityAt(s));
            for (int k = 0; k < 12; k++) {
                assertEquals(ref[k], w[s * 12 + k], 2e-4f * (1 + Math.abs(ref[k])), "slot " + s);
            }
        }
    }

    private static void randomLocal(TransformStore t, int e, Random r) {
        t.setPosition(e, r.nextFloat() * 4 - 2, r.nextFloat() * 4 - 2, r.nextFloat() * 4 - 2);
        float x = r.nextFloat() - 0.5f;
        float y = r.nextFloat() - 0.5f;
        float z = r.nextFloat() - 0.5f;
        float w = r.nextFloat() + 0.1f;
        float n = (float) Math.sqrt(x * x + y * y + z * z + w * w);
        t.setRotation(e, x / n, y / n, z / n, w / n);
        float s = 0.5f + r.nextFloat();
        t.setScale(e, s, s, s);
    }

    /** A forest: {@code n} entities, each attached to a random earlier one or a root. */
    private static int[] forest(EntityPool pool, TransformStore t, int n, Random r) {
        int[] e = new int[n];
        for (int i = 0; i < n; i++) {
            e[i] = pool.create();
            int parent = i > 0 && r.nextInt(4) > 0 ? e[r.nextInt(i)] : Handles.NULL;
            t.add(e[i], parent);
            randomLocal(t, e[i], r);
        }
        return e;
    }

    @Test
    void matchesTheReferenceSequentialAndParallel() {
        for (JobSystem j : new JobSystem[] {null, jobs}) {
            Random r = new Random(1);
            EntityPool pool = new EntityPool();
            TransformStore t = new TransformStore(pool, 16);
            int[] e = forest(pool, t, 12_000, r);
            assertEquals(12_000, t.update(j));
            assertMatchesReference(t);
            // Reparent some to later entities (breaks insertion order), then move a few.
            for (int i = 0; i < 500; i++) {
                int child = e[r.nextInt(e.length)];
                int parent = e[r.nextInt(e.length)];
                try {
                    t.setParent(child, parent);
                } catch (IllegalArgumentException cycle) {
                    // fine: skip
                }
            }
            for (int i = 0; i < 300; i++) {
                randomLocal(t, e[r.nextInt(e.length)], r);
            }
            t.update(j);
            assertMatchesReference(t);
            assertEquals(0, t.update(j), "nothing changed");
        }
    }

    @Test
    void onlyChangedSubtreesRecompute() {
        EntityPool pool = new EntityPool();
        TransformStore t = new TransformStore(pool, 16);
        int root = pool.create();
        int other = pool.create();
        t.add(root);
        t.add(other);
        int child = pool.create();
        t.add(child, root);
        int grandChild = pool.create();
        t.add(grandChild, child);
        t.setStatic(other, true);
        assertEquals(4, t.update(null));
        t.setPosition(root, 1, 2, 3);
        assertEquals(3, t.update(null), "root and its two descendants");
        assertTrue(t.changedInLastUpdate(t.indexOf(grandChild)));
        assertFalse(t.changedInLastUpdate(t.indexOf(other)));
        assertTrue(t.isStaticAt(t.indexOf(other)));
        assertEquals(3, t.world()[t.indexOf(grandChild) * 12 + 11]);
        t.setPosition(grandChild, 0, 0, 1);
        assertEquals(1, t.update(null));
        assertEquals(4, t.world()[t.indexOf(grandChild) * 12 + 11]);
        assertMatchesReference(t);
    }

    @Test
    void cyclesAreRejected() {
        EntityPool pool = new EntityPool();
        TransformStore t = new TransformStore(pool, 4);
        int a = pool.create();
        int b = pool.create();
        int c = pool.create();
        t.add(a);
        t.add(b, a);
        t.add(c, b);
        assertThrows(IllegalArgumentException.class, () -> t.setParent(a, c));
        assertThrows(IllegalArgumentException.class, () -> t.setParent(a, a));
        t.setParent(c, Handles.NULL);
        t.setParent(a, c);
        assertEquals(c, t.parent(a));
        t.update(null);
        assertMatchesReference(t);
    }

    @Test
    void removingAParentMovesChildrenUp() {
        EntityPool pool = new EntityPool();
        TransformStore t = new TransformStore(pool, 4);
        int a = pool.create();
        int b = pool.create();
        int c = pool.create();
        int d = pool.create();
        t.add(a);
        t.setPosition(a, 10, 0, 0);
        t.add(b, a);
        t.setPosition(b, 0, 5, 0);
        t.add(c, b);
        t.add(d, b);
        t.update(null);
        assertEquals(10, t.world()[t.indexOf(c) * 12 + 3]);
        pool.destroy(b);
        assertFalse(t.has(b));
        assertEquals(a, t.parent(c));
        assertEquals(a, t.parent(d));
        t.update(null);
        assertEquals(0, t.world()[t.indexOf(c) * 12 + 7], "b's offset is gone");
        assertEquals(10, t.world()[t.indexOf(d) * 12 + 3]);
        pool.destroy(a);
        assertEquals(Handles.NULL, t.parent(c));
        t.update(null);
        assertEquals(0, t.world()[t.indexOf(c) * 12 + 3]);
        assertMatchesReference(t);
        assertEquals(2, t.size());
    }

    @Test
    void updatesAllocateNothing() {
        Random r = new Random(4);
        EntityPool pool = new EntityPool();
        TransformStore t = new TransformStore(pool, 16);
        int[] e = forest(pool, t, 10_000, r);
        t.update(jobs);
        Allocations.assertNoAllocation(
                "transform update",
                10,
                200,
                () -> {
                    for (int i = 0; i < 64; i++) {
                        t.setPosition(e[(i * 157) % e.length], i, 0, 0);
                    }
                    t.update(jobs);
                });
    }
}
