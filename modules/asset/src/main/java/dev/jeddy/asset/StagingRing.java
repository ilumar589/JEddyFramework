package dev.jeddy.asset;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Native memory that loads decompress into (DESIGN §10.2): a ring of 64-byte-aligned regions
 * allocated in request order and freed in any order; space is reclaimed from the oldest region
 * forward. A full ring makes the scheduler wait, which bounds memory in flight.
 *
 * <p>Thread ownership: the asset manager's owner thread allocates and frees; workers and the main
 * thread only access the memory of regions they were handed.
 */
final class StagingRing implements AutoCloseable {
    private final Arena arena = Arena.ofShared();
    private final MemorySegment memory;
    private final long capacity;
    private final long[] starts;
    private final long[] ends;
    private final boolean[] freed;
    private int first;
    private int count;
    private long head; // logical start of the oldest live region
    private long tail; // logical end of the newest region

    StagingRing(long capacity, int maxRegions) {
        this.capacity = capacity;
        memory = arena.allocate(Math.max(64, capacity), 64);
        starts = new long[maxRegions];
        ends = new long[maxRegions];
        freed = new boolean[maxRegions];
    }

    MemorySegment memory() {
        return memory;
    }

    long capacity() {
        return capacity;
    }

    /** Allocates {@code size} bytes. Returns a token, or -1 if the ring is full right now. */
    int allocate(long size) {
        if (count == starts.length) {
            return -1;
        }
        long aligned = (size + 63) & -64L;
        long start = tail;
        long physical = start % capacity;
        if (physical + aligned > capacity) {
            start += capacity - physical; // skip the tail end, wrap to offset 0
        }
        long end = start + aligned;
        if (end - head > capacity) {
            return -1;
        }
        int token = (first + count) % starts.length;
        starts[token] = start;
        ends[token] = end;
        freed[token] = false;
        count++;
        tail = end;
        return token;
    }

    /** Returns the offset of a region in {@link #memory()}. */
    long offset(int token) {
        return starts[token] % capacity;
    }

    /** Frees a region; reclaims space up to the oldest region still in use. */
    void free(int token) {
        freed[token] = true;
        while (count > 0 && freed[first]) {
            head = ends[first];
            first = (first + 1) % starts.length;
            count--;
        }
    }

    /**
     * Returns the bytes between the oldest live region and the newest (including skipped tails).
     */
    long used() {
        return tail - head;
    }

    @Override
    public void close() {
        arena.close();
    }
}
