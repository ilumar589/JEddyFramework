package dev.jeddy.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/** Entities and sparse-set component stores (TASKS M5-01). */
class ComponentStoreTest {
    @Test
    void entitiesAreGenerational() {
        EntityPool pool = new EntityPool(16);
        int a = pool.create();
        assertTrue(pool.isAlive(a));
        pool.destroy(a);
        assertFalse(pool.isAlive(a));
        int b = pool.create();
        assertFalse(pool.isAlive(a), "a reused slot gets a new generation");
        assertTrue(pool.isAlive(b));
        assertThrows(IllegalArgumentException.class, () -> pool.destroy(a));
        assertEquals(1, pool.size());
    }

    @Test
    void storesKeepColumnsDenseThroughRandomChurn() {
        EntityPool pool = new EntityPool();
        ComponentStore health = new ComponentStore(pool, "health");
        FloatColumn hp = health.floatColumn("hp", 10f);
        IntColumn team = health.intColumn("team", -1);
        ComponentStore tags = new ComponentStore(pool, "tags");
        LongColumn tag = tags.longColumn("tag", 7L);
        Map<Integer, Float> expected = new HashMap<>();
        Random r = new Random(9);
        int[] live = new int[5000];
        int liveCount = 0;
        for (int step = 0; step < 50_000; step++) {
            if (liveCount < 100 || r.nextInt(3) > 0) {
                int e = pool.create();
                live[liveCount++] = e;
                if (r.nextBoolean()) {
                    int i = health.add(e);
                    assertEquals(10f, hp.get(i));
                    assertEquals(-1, team.get(i));
                    float v = r.nextFloat();
                    hp.set(i, v);
                    team.set(i, e);
                    expected.put(e, v);
                }
                tags.add(e);
                if (liveCount == live.length) {
                    liveCount = shrink(pool, live, liveCount, r, expected);
                }
            } else {
                liveCount = shrink(pool, live, liveCount, r, expected);
            }
        }
        assertEquals(expected.size(), health.size());
        for (Map.Entry<Integer, Float> e : expected.entrySet()) {
            int i = health.indexOf(e.getKey());
            assertTrue(i >= 0);
            assertEquals((float) e.getValue(), hp.get(i));
            assertEquals((int) e.getKey(), team.get(i));
            assertEquals((int) e.getKey(), health.entityAt(i));
        }
        assertEquals(pool.size(), tags.size());
        assertEquals(7L, tag.get(0));
    }

    private static int shrink(
            EntityPool pool, int[] live, int liveCount, Random r, Map<Integer, Float> expected) {
        int k = r.nextInt(liveCount);
        int e = live[k];
        live[k] = live[--liveCount];
        pool.destroy(e);
        expected.remove(e);
        return liveCount;
    }

    @Test
    void staleHandlesDoNotMatchReusedSlots() {
        EntityPool pool = new EntityPool(4);
        ComponentStore store = new ComponentStore(pool, "s");
        int a = pool.create();
        store.add(a);
        pool.destroy(a);
        int b = pool.create();
        assertFalse(store.has(a));
        assertFalse(store.has(b));
        store.add(b);
        assertFalse(store.has(a));
        assertEquals(-1, store.indexOf(a));
        store.remove(a); // no effect
        assertTrue(store.has(b));
        assertThrows(IllegalArgumentException.class, () -> store.add(a));
    }

    @Test
    void iterationAllocatesNothing() {
        EntityPool pool = new EntityPool();
        ComponentStore s = new ComponentStore(pool, "velocity");
        FloatColumn x = s.floatColumn("x", 0);
        FloatColumn vx = s.floatColumn("vx", 1);
        for (int i = 0; i < 1000; i++) {
            s.add(pool.create());
        }
        Allocations.assertNoAllocation(
                "column pass",
                () -> {
                    float[] p = x.data();
                    float[] v = vx.data();
                    for (int i = 0, n = s.size(); i < n; i++) {
                        p[i] += v[i] * 0.016f;
                    }
                });
    }
}
