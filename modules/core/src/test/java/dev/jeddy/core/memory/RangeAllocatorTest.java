package dev.jeddy.core.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Random;

/** Free-list sub-allocation (mega buffers, TASKS M5-07). */
class RangeAllocatorTest {
    @Test
    void randomChurnNeverOverlapsAndCoalescesBack() {
        Random r = new Random(2);
        RangeAllocator a = new RangeAllocator(100_000);
        BitSet used = new BitSet();
        List<long[]> live = new ArrayList<>();
        for (int step = 0; step < 20_000; step++) {
            if (live.isEmpty() || r.nextInt(3) > 0) {
                long size = 1 + r.nextInt(500);
                long align = 1L << r.nextInt(5);
                long off = a.allocate(size, align);
                if (off < 0) {
                    continue;
                }
                assertEquals(0, off % align);
                assertTrue(used.get((int) off, (int) (off + size)).isEmpty(), "overlap");
                used.set((int) off, (int) (off + size));
                live.add(new long[] {off, size});
            } else {
                long[] x = live.remove(r.nextInt(live.size()));
                a.free(x[0], x[1]);
                used.clear((int) x[0], (int) (x[0] + x[1]));
            }
            assertEquals(100_000 - used.cardinality(), a.free());
        }
        for (long[] x : live) {
            a.free(x[0], x[1]);
        }
        assertEquals(1, a.freeRanges(), "fully coalesced");
        assertEquals(100_000, a.largestFree());
    }

    @Test
    void growsAndDetectsMisuse() {
        RangeAllocator a = new RangeAllocator(100);
        assertEquals(0, a.allocate(100, 1));
        assertEquals(-1, a.allocate(1, 1));
        a.grow(150);
        assertEquals(100, a.allocate(50, 1));
        a.free(0, 10);
        assertThrows(IllegalStateException.class, () -> a.free(0, 10));
        assertThrows(IllegalStateException.class, () -> a.free(5, 10));
        assertThrows(IllegalArgumentException.class, () -> a.free(140, 20));
    }
}
