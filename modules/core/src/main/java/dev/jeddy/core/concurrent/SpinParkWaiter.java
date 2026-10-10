package dev.jeddy.core.concurrent;

import java.util.concurrent.locks.LockSupport;

/**
 * Idle strategy for threads waiting on lock-free state: spin with {@link Thread#onSpinWait()},
 * then yield, then park briefly. Low wake-up latency without burning idle cores (DESIGN §4.5).
 *
 * <pre>{@code
 * int idle = 0;
 * while (!ready()) {
 *     idle = waiter.idle(idle);
 * }
 * }</pre>
 *
 * <p>Thread ownership: immutable and stateless; share freely. The counter lives with the caller.
 */
public final class SpinParkWaiter {
    private final int spins;
    private final int yields;
    private final long parkNanos;

    /** Creates a waiter: 200 spins, 50 yields, then 50 µs parks. */
    public SpinParkWaiter() {
        this(200, 50, 50_000);
    }

    /**
     * Creates a waiter.
     *
     * @param spins busy-spin iterations before yielding
     * @param yields yields before parking
     * @param parkNanos park duration once spinning and yielding are exhausted
     */
    public SpinParkWaiter(int spins, int yields, long parkNanos) {
        this.spins = spins;
        this.yields = yields;
        this.parkNanos = parkNanos;
    }

    /** Waits a little according to how long the caller has been idle; returns the next counter. */
    public int idle(int counter) {
        if (counter < spins) {
            Thread.onSpinWait();
        } else if (counter < spins + yields) {
            Thread.yield();
        } else {
            LockSupport.parkNanos(parkNanos);
        }
        return counter == Integer.MAX_VALUE ? counter : counter + 1;
    }

    /** Returns {@code true} once the counter has reached the parking phase. */
    public boolean isParking(int counter) {
        return counter >= spins + yields;
    }
}
