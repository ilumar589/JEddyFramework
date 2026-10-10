package dev.jeddy.core.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.testing.Allocations;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ConcurrencyTest {

    @Test
    void spscRingKeepsRecordsIntact() throws InterruptedException {
        SpscIntRing ring = new SpscIntRing(256);
        int records = 300_000;
        Thread producer =
                new Thread(
                        () -> {
                            int[] rec = new int[8];
                            for (int i = 0; i < records; i++) {
                                for (int k = 0; k < 8; k++) {
                                    rec[k] = i * 8 + k;
                                }
                                while (!ring.offer(rec, 0, 8)) {
                                    Thread.onSpinWait();
                                }
                            }
                        });
        producer.start();
        int[] got = new int[8];
        for (int i = 0; i < records; i++) {
            while (!ring.poll(got, 0, 8)) {
                Thread.onSpinWait();
            }
            for (int k = 0; k < 8; k++) {
                if (got[k] != i * 8 + k) {
                    throw new AssertionError("record " + i + " corrupted at " + k + ": " + got[k]);
                }
            }
        }
        producer.join();
        assertEquals(0, ring.size());
    }

    @Test
    void spscRingFullAndDrain() {
        SpscIntRing ring = new SpscIntRing(4);
        assertTrue(ring.offer(1));
        assertTrue(ring.offer(new int[] {2, 3, 4}, 0, 3));
        assertFalse(ring.offer(5));
        assertFalse(ring.offer(new int[] {5}, 0, 1));
        int[] out = new int[8];
        assertEquals(4, ring.drain(out, 0, 8));
        assertEquals(4, out[3]);
        assertThrows(IllegalArgumentException.class, () -> new SpscIntRing(3));
        SpscLongRing longs = new SpscLongRing(8);
        assertTrue(longs.offer(Long.MIN_VALUE));
        long[] lo = new long[1];
        assertTrue(longs.poll(lo, 0, 1));
        assertEquals(Long.MIN_VALUE, lo[0]);
    }

    @Test
    void mpmcQueueDeliversEveryValueOnce() throws InterruptedException {
        MpmcIntQueue q = new MpmcIntQueue(1024);
        int producers = 3;
        int consumers = 3;
        int perProducer = 200_000;
        AtomicLong sum = new AtomicLong();
        AtomicLong count = new AtomicLong();
        Thread[] threads = new Thread[producers + consumers];
        for (int p = 0; p < producers; p++) {
            int base = p * perProducer;
            threads[p] =
                    new Thread(
                            () -> {
                                for (int i = 0; i < perProducer; i++) {
                                    while (!q.offer(base + i)) {
                                        Thread.onSpinWait();
                                    }
                                }
                            });
        }
        long total = (long) producers * perProducer;
        for (int c = 0; c < consumers; c++) {
            threads[producers + c] =
                    new Thread(
                            () -> {
                                while (count.get() < total) {
                                    int v = q.poll();
                                    if (v == MpmcIntQueue.EMPTY) {
                                        Thread.onSpinWait();
                                        continue;
                                    }
                                    sum.addAndGet(v);
                                    count.incrementAndGet();
                                }
                            });
        }
        for (Thread t : threads) {
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }
        assertEquals(total, count.get());
        assertEquals(total * (total - 1) / 2, sum.get());
        assertEquals(MpmcIntQueue.EMPTY, q.poll());
    }

    @Test
    void paddedAtomicLong() {
        PaddedAtomicLong a = new PaddedAtomicLong(5);
        assertEquals(6, a.incrementAndGet());
        assertEquals(6, a.getAndAdd(4));
        assertTrue(a.compareAndSet(10, 1));
        assertEquals(1, a.get());
    }

    @Test
    void ringsDoNotAllocate() {
        SpscIntRing ring = new SpscIntRing(64);
        MpmcIntQueue q = new MpmcIntQueue(64);
        int[] rec = {1, 2, 3, 4, 5, 6, 7, 8};
        int[] out = new int[8];
        Allocations.assertNoAllocation(
                "rings",
                () -> {
                    ring.offer(rec, 0, 8);
                    ring.poll(out, 0, 8);
                    q.offer(3);
                    q.poll();
                });
    }
}
