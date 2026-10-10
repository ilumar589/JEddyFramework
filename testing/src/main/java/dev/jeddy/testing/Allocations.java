package dev.jeddy.testing;

import org.junit.jupiter.api.Assertions;

import java.lang.management.ManagementFactory;

/**
 * Allocation assertions for hot paths (DESIGN §4.2, §4.7): proves that code allocates 0 bytes once
 * the JIT has compiled it.
 *
 * <pre>{@code
 * Allocations.assertNoAllocation("IntIntMap.get", () -> sink += map.get(key));
 * }</pre>
 *
 * Measurement uses the calling thread's allocated-bytes counter. Because compilation happens in the
 * background, the body runs in rounds of warm-up plus measurement until a round allocates nothing,
 * or the round limit is reached; the test fails with the smallest per-round amount seen.
 *
 * <p>Thread ownership: call from the test thread; the body runs on that thread.
 */
public final class Allocations {
    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    private Allocations() {}

    /** Asserts that {@code body} allocates nothing, with default warm-up (25 rounds of 4000). */
    public static void assertNoAllocation(String what, Runnable body) {
        assertNoAllocation(what, 25, 4_000, body);
    }

    /**
     * Asserts that {@code body} allocates nothing once warmed up.
     *
     * @param what name for the failure message
     * @param maxRounds most warm-up and measurement rounds to try
     * @param iterationsPerRound body calls per round
     * @param body the code under test
     */
    public static void assertNoAllocation(
            String what, int maxRounds, int iterationsPerRound, Runnable body) {
        long best = Long.MAX_VALUE;
        for (int round = 0; round < maxRounds; round++) {
            for (int i = 0; i < iterationsPerRound; i++) {
                body.run(); // warm-up, lets the JIT compile
            }
            long before = THREADS.getCurrentThreadAllocatedBytes();
            for (int i = 0; i < iterationsPerRound; i++) {
                body.run();
            }
            long allocated = THREADS.getCurrentThreadAllocatedBytes() - before;
            best = Math.min(best, allocated);
            if (allocated == 0) {
                return;
            }
            if (round >= 2) {
                try {
                    Thread.sleep(20); // give the background compiler time to install C2 code
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        Assertions.fail(
                what
                        + " allocates: at best "
                        + best
                        + " bytes per "
                        + iterationsPerRound
                        + " calls ("
                        + (best / (double) iterationsPerRound)
                        + " bytes/call)");
    }

    /** Returns the bytes the calling thread has allocated so far (for custom measurements). */
    public static long threadAllocatedBytes() {
        return THREADS.getCurrentThreadAllocatedBytes();
    }

    /**
     * Returns the bytes another thread has allocated so far. Sampling from a separate monitor
     * thread keeps the measurement code out of the loop being measured (no JIT effects there).
     */
    public static long threadAllocatedBytes(Thread thread) {
        return THREADS.getThreadAllocatedBytes(thread.threadId());
    }
}
