package dev.jeddy.core.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.testing.Allocations;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JobSystemTest {
    private JobSystem jobs;

    @BeforeEach
    void start() {
        jobs = new JobSystem(3);
    }

    @AfterEach
    void stop() {
        jobs.close();
    }

    @Test
    void parallelForVisitsEveryIndexOnce() {
        int n = 100_003;
        AtomicIntegerArray hits = new AtomicIntegerArray(n);
        AtomicIntegerArray threadsSeen = new AtomicIntegerArray(jobs.threadSlots());
        jobs.parallelFor(
                (start, end, t) -> {
                    threadsSeen.set(t, 1);
                    for (int i = start; i < end; i++) {
                        hits.incrementAndGet(i);
                    }
                },
                n,
                97);
        for (int i = 0; i < n; i++) {
            assertEquals(1, hits.get(i), "index " + i);
        }
    }

    @Test
    void dependenciesRunInOrder() {
        AtomicInteger clock = new AtomicInteger();
        int[] finished = new int[4];
        JobKernel slow =
                (s, e, t) -> {
                    sleep(5);
                    finished[0] = clock.incrementAndGet();
                };
        int a = jobs.submit(slow, 1, 1);
        int b = jobs.submit((s, e, t) -> finished[1] = clock.incrementAndGet(), 1, 1, a);
        int c = jobs.submit((s, e, t) -> finished[2] = clock.incrementAndGet(), 1, 1, a);
        int d = jobs.submit((s, e, t) -> finished[3] = clock.incrementAndGet(), 1, 1, b, c);
        jobs.wait(d);
        assertTrue(jobs.isDone(a) && jobs.isDone(b) && jobs.isDone(c));
        assertTrue(finished[0] < finished[1] && finished[0] < finished[2]);
        assertTrue(finished[3] > finished[1] && finished[3] > finished[2]);
    }

    @Test
    void failuresPropagateToWaitersAndDependents() {
        int bad = jobs.submit((s, e, t) -> { throw new IllegalStateException("boom"); }, 10, 1);
        AtomicInteger ran = new AtomicInteger();
        int dependent = jobs.submit((s, e, t) -> ran.incrementAndGet(), 1, 1, bad);
        JobFailedException e = assertThrows(JobFailedException.class, () -> jobs.wait(bad));
        assertTrue(e.getCause() instanceof IllegalStateException);
        assertThrows(JobFailedException.class, () -> jobs.wait(dependent));
        assertEquals(0, ran.get());
        assertThrows(
                JobFailedException.class,
                () -> jobs.parallelFor((s, en, t) -> { throw new IllegalStateException(); }, 4, 8));
    }

    @Test
    void nestedParallelForAndManyJobs() {
        AtomicInteger total = new AtomicInteger();
        jobs.parallelFor(
                (s, e, t) -> {
                    for (int i = s; i < e; i++) {
                        jobs.parallelFor((s2, e2, t2) -> total.addAndGet(e2 - s2), 100, 10);
                    }
                },
                8,
                1);
        assertEquals(800, total.get());
        int[] handles = new int[3000];
        AtomicInteger count = new AtomicInteger();
        for (int i = 0; i < handles.length; i++) {
            handles[i] = jobs.submit((s, e, t) -> count.incrementAndGet(), 1, 1);
        }
        for (int h : handles) {
            jobs.wait(h);
        }
        assertEquals(3000, count.get());
    }

    @Test
    void zeroWorkersRunsOnCaller() {
        try (JobSystem inline = new JobSystem(0)) {
            AtomicInteger n = new AtomicInteger();
            inline.parallelFor((s, e, t) -> n.addAndGet(e - s), 1000, 7);
            int a = inline.submit((s, e, t) -> n.addAndGet(e - s), 10, 3);
            int b = inline.submit((s, e, t) -> n.addAndGet(e - s), 10, 3, a);
            inline.wait(b);
            assertEquals(1020, n.get());
        }
    }

    @Test
    void graphRunsRepeatedly() {
        AtomicInteger order = new AtomicInteger();
        int[] stamp = new int[3];
        JobGraph g = new JobGraph();
        int first = g.add((s, e, t) -> stamp[0] = order.incrementAndGet(), 1, 1);
        int second = g.add((s, e, t) -> stamp[1] = order.incrementAndGet(), 1, 1, first);
        g.add((s, e, t) -> stamp[2] = order.incrementAndGet(), 1, 1, first, second);
        for (int run = 0; run < 50; run++) {
            g.run(jobs);
            assertTrue(stamp[0] < stamp[1] && stamp[1] < stamp[2]);
        }
        assertEquals(150, order.get());
    }

    private final long[] partial = new long[64];
    private final int[] data = new int[4096];

    @Test
    void dispatchDoesNotAllocate() {
        for (int i = 0; i < data.length; i++) {
            data[i] = i;
        }
        JobKernel sum =
                (start, end, t) -> {
                    long s = 0;
                    for (int i = start; i < end; i++) {
                        s += data[i];
                    }
                    partial[t] += s;
                };
        Allocations.assertNoAllocation("JobSystem.parallelFor", 25, 500, () -> jobs.parallelFor(sum, data.length, 256));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
