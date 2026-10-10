package dev.jeddy.core.handle;

import dev.jeddy.core.collections.IntRing;

import java.util.Arrays;

/**
 * Allocates generational {@code int} handles over dense slot indices {@code [0, capacity)}. Freed
 * slots are reused in FIFO order, which spreads generation wrap-around over all slots.
 *
 * <p>Data for each handle lives in arrays owned by the caller and indexed by {@link
 * Handles#index(int)} (struct-of-arrays). Before reading, validate with {@link #isValid(int)}.
 *
 * <p>Thread ownership: single owner thread; not thread-safe.
 */
public final class HandlePool {
    private int[] generations; // current generation per slot
    private boolean[] alive;
    private final IntRing free = new IntRing(64);
    private int highWater;
    private int liveCount;
    private final int maxSlots;

    /** Creates a pool with an initial capacity of 64 slots and the maximum handle range. */
    public HandlePool() {
        this(64, Handles.MAX_INDEX + 1);
    }

    /**
     * Creates a pool.
     *
     * @param initialCapacity slots allocated up front
     * @param maxSlots hard limit on live plus freed slots, at most {@code Handles.MAX_INDEX + 1}
     */
    public HandlePool(int initialCapacity, int maxSlots) {
        if (maxSlots < 1 || maxSlots > Handles.MAX_INDEX + 1) {
            throw new IllegalArgumentException("maxSlots out of range: " + maxSlots);
        }
        this.maxSlots = maxSlots;
        int cap = Math.max(1, Math.min(initialCapacity, maxSlots));
        generations = new int[cap];
        Arrays.fill(generations, 1);
        alive = new boolean[cap];
    }

    /** Returns the number of live handles. */
    public int size() {
        return liveCount;
    }

    /**
     * Returns one past the highest slot index ever used; caller-owned data arrays must be at least
     * this long.
     */
    public int highWaterMark() {
        return highWater;
    }

    /** Returns the current slot capacity (grows on demand up to {@code maxSlots}). */
    public int capacity() {
        return generations.length;
    }

    /** Allocates a new handle. */
    public int allocate() {
        int index;
        if (!free.isEmpty()) {
            index = free.removeFirst();
        } else {
            if (highWater == maxSlots) {
                throw new IllegalStateException("handle pool exhausted (" + maxSlots + " slots)");
            }
            index = highWater++;
            if (index == generations.length) {
                int newCap = Math.min(maxSlots, Math.max(index + 1, generations.length * 2));
                int old = generations.length;
                generations = Arrays.copyOf(generations, newCap);
                Arrays.fill(generations, old, newCap, 1);
                alive = Arrays.copyOf(alive, newCap);
            }
        }
        alive[index] = true;
        liveCount++;
        return Handles.pack(index, generations[index]);
    }

    /** Frees a live handle. Throws if it is stale or already freed. */
    public void free(int handle) {
        if (!isValid(handle)) {
            throw new IllegalArgumentException(
                    "stale or invalid handle " + Handles.toString(handle));
        }
        int index = Handles.index(handle);
        alive[index] = false;
        generations[index] = Handles.nextGeneration(generations[index]);
        free.addLast(index);
        liveCount--;
    }

    /** Returns {@code true} if {@code handle} refers to a live slot of the current generation. */
    public boolean isValid(int handle) {
        int index = Handles.index(handle);
        return handle != Handles.NULL
                && index < highWater
                && alive[index]
                && generations[index] == Handles.generation(handle);
    }

    /** Returns {@code true} if slot {@code index} currently holds a live handle. */
    public boolean isAlive(int index) {
        return index < highWater && alive[index];
    }

    /** Returns the live handle of slot {@code index}, or {@link Handles#NULL}. */
    public int handleAt(int index) {
        return isAlive(index) ? Handles.pack(index, generations[index]) : Handles.NULL;
    }

    /** Frees every handle; all outstanding handles become stale. */
    public void clear() {
        for (int i = 0; i < highWater; i++) {
            if (alive[i]) {
                free(Handles.pack(i, generations[i]));
            }
        }
    }
}
