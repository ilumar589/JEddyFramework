package dev.jeddy.core.handle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.core.pool.ObjectPool;
import dev.jeddy.core.pool.PerWorker;
import dev.jeddy.testing.Allocations;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HandleAndPoolTest {

    @Test
    void packing() {
        int h = Handles.pack(123_456, 7);
        assertEquals(123_456, Handles.index(h));
        assertEquals(7, Handles.generation(h));
        int high = Handles.pack(Handles.MAX_INDEX, Handles.MAX_GENERATION);
        assertEquals(Handles.MAX_INDEX, Handles.index(high));
        assertEquals(Handles.MAX_GENERATION, Handles.generation(high));
        assertEquals(1, Handles.nextGeneration(Handles.MAX_GENERATION));
        assertEquals("NULL", Handles.toString(Handles.NULL));
    }

    @Test
    void staleHandlesAreDetected() {
        HandlePool pool = new HandlePool(2, 16);
        int a = pool.allocate();
        int b = pool.allocate();
        assertNotEquals(Handles.NULL, a);
        assertTrue(pool.isValid(a));
        pool.free(a);
        assertFalse(pool.isValid(a));
        assertThrows(IllegalArgumentException.class, () -> pool.free(a));
        int c = pool.allocate(); // FIFO: reuses a's slot
        assertEquals(Handles.index(a), Handles.index(c));
        assertNotEquals(a, c);
        assertTrue(pool.isValid(b));
        assertEquals(2, pool.size());
        pool.clear();
        assertFalse(pool.isValid(b));
        assertFalse(pool.isValid(Handles.NULL));
    }

    @Test
    void poolLimit() {
        HandlePool pool = new HandlePool(1, 3);
        pool.allocate();
        pool.allocate();
        pool.allocate();
        assertThrows(IllegalStateException.class, pool::allocate);
    }

    @Test
    void handlesStayUniqueAcrossReuse() {
        // One slot cycles through every generation before a handle value repeats.
        HandlePool pool = new HandlePool(4, 64);
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < Handles.MAX_GENERATION; i++) {
            int h = pool.allocate();
            assertTrue(seen.add(h), "duplicate handle " + Handles.toString(h));
            pool.free(h);
        }
    }

    @Test
    void slotMap() {
        SlotMap<String> map = new SlotMap<>(1);
        int a = map.insert("a");
        int b = map.insert("b");
        assertEquals("a", map.get(a));
        assertEquals("b", map.remove(b));
        assertNull(map.get(b));
        assertNull(map.remove(b));
        int c = map.insert("c");
        assertEquals("c", map.get(c));
        assertEquals(2, map.size());
        int visited = 0;
        for (int i = 0; i < map.slotLimit(); i++) {
            if (map.valueAt(i) != null) {
                assertEquals(map.valueAt(i), map.get(map.handleAt(i)));
                visited++;
            }
        }
        assertEquals(2, visited);
    }

    @Test
    void objectPoolReusesAndResets() {
        int[] resets = {0};
        ObjectPool<StringBuilder> pool = new ObjectPool<>(StringBuilder::new, sb -> {
            sb.setLength(0);
            resets[0]++;
        }, 2);
        pool.prefill(2);
        assertEquals(2, pool.created());
        StringBuilder a = pool.obtain();
        a.append("x");
        pool.release(a);
        assertEquals(1, resets[0]);
        assertSame(a, pool.obtain());
        assertEquals(0, a.length());
        pool.release(new StringBuilder());
        pool.release(new StringBuilder());
        assertEquals(2, pool.pooled()); // capped
    }

    @Test
    void perWorkerSlots() {
        PerWorker<int[]> scratch = new PerWorker<>(3, i -> new int[] {i});
        assertEquals(3, scratch.size());
        assertEquals(2, scratch.get(2)[0]);
    }

    @Test
    void handleChurnDoesNotAllocate() {
        HandlePool pool = new HandlePool(256, 1 << 16);
        int[] hs = new int[128];
        Allocations.assertNoAllocation(
                "HandlePool allocate/free",
                () -> {
                    for (int i = 0; i < hs.length; i++) {
                        hs[i] = pool.allocate();
                    }
                    for (int h : hs) {
                        pool.free(h);
                    }
                });
    }
}
