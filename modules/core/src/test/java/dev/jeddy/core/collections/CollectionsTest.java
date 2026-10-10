package dev.jeddy.core.collections;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.testing.Allocations;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CollectionsTest {

    @Test
    void intListBasics() {
        IntList l = new IntList(2);
        for (int i = 0; i < 100; i++) {
            l.add(i * 3);
        }
        assertEquals(100, l.size());
        assertEquals(297, l.get(99));
        assertEquals(10, l.indexOf(30));
        l.insert(0, -1);
        assertEquals(-1, l.get(0));
        assertEquals(0, l.removeAt(1));
        assertEquals(-1, l.removeAtSwap(0));
        assertEquals(297, l.get(0));
        l.sort();
        assertEquals(3, l.get(0));
        assertThrows(IndexOutOfBoundsException.class, () -> l.get(l.size()));
        l.setSize(3);
        assertArrayEquals(new int[] {3, 6, 9}, l.toArray());
        l.clear();
        assertTrue(l.isEmpty());
    }

    @Test
    void longAndFloatLists() {
        LongList ll = new LongList();
        ll.add(Long.MAX_VALUE);
        ll.add(-5L);
        ll.sort();
        assertEquals(-5L, ll.get(0));
        FloatList fl = new FloatList();
        fl.add(2.5f);
        fl.add(-1f);
        fl.sort();
        assertEquals(-1f, fl.get(0));
        assertEquals(1, fl.indexOf(2.5f));
    }

    @Test
    void intIntMapMatchesHashMap() {
        Random r = new Random(42);
        IntIntMap m = new IntIntMap(4, Integer.MIN_VALUE);
        Map<Integer, Integer> ref = new HashMap<>();
        for (int i = 0; i < 200_000; i++) {
            int key = r.nextInt(2000) - 1000; // includes 0 and negatives
            switch (r.nextInt(4)) {
                case 0, 1 -> {
                    int v = r.nextInt();
                    Integer old = ref.put(key, v);
                    assertEquals(old == null ? Integer.MIN_VALUE : old, m.put(key, v));
                }
                case 2 -> {
                    Integer old = ref.remove(key);
                    assertEquals(old == null ? Integer.MIN_VALUE : old, m.remove(key));
                }
                default -> {
                    Integer v = ref.get(key);
                    assertEquals(v == null ? Integer.MIN_VALUE : v, m.get(key));
                    assertEquals(v != null, m.containsKey(key));
                }
            }
            assertEquals(ref.size(), m.size());
        }
        Map<Integer, Integer> iterated = new HashMap<>();
        for (int s = m.firstSlot(); s >= 0; s = m.nextSlot(s)) {
            iterated.put(m.keyAt(s), m.valueAt(s));
        }
        assertEquals(ref, iterated);
        m.clear();
        assertEquals(0, m.size());
        assertEquals(-1, m.firstSlot());
    }

    @Test
    void intIntMapAddTo() {
        IntIntMap m = new IntIntMap();
        assertEquals(5, m.addTo(0, 5));
        assertEquals(8, m.addTo(0, 3));
        assertEquals(2, m.addTo(7, 2));
        assertEquals(8, m.get(0));
        assertEquals(-1, m.get(99));
    }

    @Test
    void longIntMapMatchesHashMap() {
        Random r = new Random(7);
        LongIntMap m = new LongIntMap(4, -1);
        Map<Long, Integer> ref = new HashMap<>();
        for (int i = 0; i < 100_000; i++) {
            long key = r.nextInt(500) == 0 ? 0L : r.nextLong() % 3000;
            if (r.nextBoolean()) {
                int v = r.nextInt(1 << 20);
                ref.put(key, v);
                m.put(key, v);
            } else {
                Integer old = ref.remove(key);
                assertEquals(old == null ? -1 : old, m.remove(key));
            }
        }
        assertEquals(ref.size(), m.size());
        for (Map.Entry<Long, Integer> e : ref.entrySet()) {
            assertEquals((int) e.getValue(), m.get(e.getKey()));
        }
    }

    @Test
    void intObjMapMatchesHashMap() {
        Random r = new Random(9);
        IntObjMap<String> m = new IntObjMap<>(2);
        Map<Integer, String> ref = new HashMap<>();
        for (int i = 0; i < 50_000; i++) {
            int key = r.nextInt(300) - 150;
            if (r.nextInt(3) > 0) {
                String v = "v" + i;
                assertEquals(ref.put(key, v), m.put(key, v));
            } else {
                assertEquals(ref.remove(key), m.remove(key));
            }
        }
        assertEquals(ref.size(), m.size());
        for (int k = -150; k < 150; k++) {
            assertEquals(ref.get(k), m.get(k));
        }
        m.clear();
        assertNull(m.get(0));
    }

    @Test
    void intSetMatchesHashSet() {
        Random r = new Random(3);
        IntSet s = new IntSet(1);
        Set<Integer> ref = new HashSet<>();
        for (int i = 0; i < 100_000; i++) {
            int v = r.nextInt(1000) - 500;
            if (r.nextBoolean()) {
                assertEquals(ref.add(v), s.add(v));
            } else {
                assertEquals(ref.remove(v), s.remove(v));
            }
        }
        assertEquals(ref.size(), s.size());
        Set<Integer> iterated = new HashSet<>();
        for (int slot = s.firstSlot(); slot >= 0; slot = s.nextSlot(slot)) {
            iterated.add(s.keyAt(slot));
        }
        assertEquals(ref, iterated);
    }

    @Test
    void bitSetOperations() {
        BitSet64 b = new BitSet64(10);
        b.set(3);
        b.set(64);
        b.set(200);
        assertTrue(b.get(64));
        assertFalse(b.get(65));
        assertEquals(3, b.cardinality());
        assertEquals(64, b.nextSetBit(4));
        assertEquals(200, b.nextSetBit(65));
        assertEquals(-1, b.nextSetBit(201));
        assertEquals(4, b.nextClearBit(3));
        BitSet64 other = new BitSet64();
        other.set(3);
        other.set(5);
        b.or(other);
        assertTrue(b.get(5));
        b.andNot(other);
        assertFalse(b.get(3));
        b.and(other);
        assertEquals(0, b.cardinality());
        b.flip(7);
        assertTrue(b.get(7));
    }

    @Test
    void sparseSetSwapRemove() {
        SparseSet s = new SparseSet(4, 2);
        assertEquals(0, s.add(10));
        assertEquals(1, s.add(3));
        assertEquals(2, s.add(77));
        assertEquals(1, s.add(3)); // already present
        assertEquals(0, s.remove(10)); // 77 moves to index 0
        assertEquals(77, s.dense()[0]);
        assertEquals(0, s.indexOf(77));
        assertFalse(s.contains(10));
        assertEquals(-1, s.remove(10));
        s.clear();
        assertEquals(0, s.size());
        assertFalse(s.contains(77));
    }

    @Test
    void intHeapOrdersLikePriorityQueue() {
        Random r = new Random(11);
        IntHeap h = new IntHeap(16, 4);
        float[] key = new float[1000];
        for (int i = 0; i < 1000; i++) {
            key[i] = r.nextFloat() * 100;
            h.push(i, key[i]);
        }
        for (int i = 0; i < 1000; i += 3) {
            key[i] -= 50;
            h.decreaseKey(i, key[i]);
        }
        PriorityQueue<Integer> ref = new PriorityQueue<>((a, b) -> Float.compare(key[a], key[b]));
        for (int i = 0; i < 1000; i++) {
            ref.add(i);
        }
        float last = Float.NEGATIVE_INFINITY;
        while (!h.isEmpty()) {
            float k = h.peekKey();
            int item = h.pop();
            assertEquals(key[ref.poll()], k);
            assertEquals(key[item], k);
            assertTrue(k >= last);
            last = k;
        }
    }

    @Test
    void intRingFifoAndLifo() {
        IntRing r = new IntRing(2);
        for (int i = 0; i < 10; i++) {
            r.addLast(i);
        }
        r.addFirst(-1);
        assertEquals(-1, r.removeFirst());
        assertEquals(0, r.removeFirst());
        assertEquals(9, r.removeLast());
        assertEquals(8, r.size());
        assertEquals(1, r.get(0));
    }

    @Test
    void radixSortIsStableAndUnsigned() {
        Random r = new Random(5);
        int n = 20_000;
        long[] keys = new long[n];
        int[] values = new int[n];
        for (int i = 0; i < n; i++) {
            keys[i] = r.nextInt(4) == 0 ? r.nextLong() : r.nextInt(64); // many duplicates
            values[i] = i;
        }
        List<long[]> ref = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ref.add(new long[] {keys[i], i});
        }
        ref.sort((a, b) -> Long.compareUnsigned(a[0], b[0])); // List.sort is stable
        LongSort.radixSort(keys, values, n, new long[n], new int[n], new int[2048]);
        for (int i = 0; i < n; i++) {
            assertEquals(ref.get(i)[0], keys[i]);
            assertEquals((int) ref.get(i)[1], values[i]);
        }
    }

    @Test
    void radixSortSkipsUniformBytes() {
        long[] keys = {5, 3, 9, 1};
        int[] values = {0, 1, 2, 3};
        LongSort.radixSort(keys, values, 4, new long[4], new int[4], new int[2048]);
        assertArrayEquals(new long[] {1, 3, 5, 9}, keys);
        assertArrayEquals(new int[] {3, 1, 0, 2}, values);
        long[] same = new long[3];
        Arrays.fill(same, 42);
        LongSort.radixSort(same, new int[3], 3, new long[3], new int[3], new int[2048]);
        assertArrayEquals(new long[] {42, 42, 42}, same);
    }

    private long sink;

    @Test
    void hotOperationsDoNotAllocate() {
        IntIntMap map = new IntIntMap(1024, -1);
        IntList list = new IntList(1024);
        IntHeap heap = new IntHeap(256, 256);
        BitSet64 bits = new BitSet64(4096);
        Allocations.assertNoAllocation(
                "collections",
                () -> {
                    for (int i = 0; i < 256; i++) {
                        map.put(i, i);
                        list.add(i);
                        heap.push(i, i * 0.5f);
                        bits.set(i);
                    }
                    long s = 0;
                    for (int i = 0; i < 256; i++) {
                        s += map.get(i) + list.get(i) + heap.pop();
                    }
                    for (int b = bits.nextSetBit(0); b >= 0; b = bits.nextSetBit(b + 1)) {
                        s += b;
                    }
                    map.clear();
                    list.clear();
                    bits.clear();
                    sink += s;
                });
    }
}
