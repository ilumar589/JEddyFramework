package dev.jeddy.core.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

class TripleBufferTest {
    @Test
    void indicesAreAlwaysDistinct() {
        TripleBuffer tb = new TripleBuffer();
        assertFalse(tb.acquire(), "nothing published yet");
        assertNotEquals(tb.writeIndex(), tb.readIndex());
        int first = tb.writeIndex();
        assertFalse(tb.publish());
        assertTrue(tb.hasUnconsumed());
        assertNotEquals(first, tb.writeIndex());
        assertTrue(tb.acquire());
        assertEquals(first, tb.readIndex());
        assertFalse(tb.hasUnconsumed());
        assertFalse(tb.acquire());

        tb.publish();
        assertTrue(tb.publish(), "second publication overwrote the first");
        assertEquals(1, tb.overwrittenCount());
        assertEquals(3, tb.publishedCount());
    }

    @Test
    void consumerSeesWholeFramesInOrder() throws Exception {
        TripleBuffer tb = new TripleBuffer();
        long[][] frames = new long[3][16];
        int total = 200_000;
        AtomicReference<String> error = new AtomicReference<>();
        Thread producer =
                new Thread(
                        () -> {
                            for (long f = 1; f <= total; f++) {
                                long[] buf = frames[tb.writeIndex()];
                                for (int i = 0; i < buf.length; i++) {
                                    buf[i] = f;
                                }
                                tb.publish();
                            }
                        });
        producer.start();
        long last = 0;
        while (last < total) {
            if (tb.acquire()) {
                long[] buf = frames[tb.readIndex()];
                long f = buf[0];
                for (long v : buf) {
                    if (v != f) {
                        error.set("torn frame " + f + " / " + v);
                    }
                }
                if (f <= last) {
                    error.set("frame " + f + " after " + last);
                }
                last = f;
            } else {
                Thread.onSpinWait();
            }
        }
        producer.join();
        assertEquals(null, error.get());
    }

    @Test
    void exchangesAllocateNothing() {
        TripleBuffer tb = new TripleBuffer();
        Allocations.assertNoAllocation(
                "TripleBuffer",
                () -> {
                    tb.publish();
                    tb.acquire();
                });
    }
}
