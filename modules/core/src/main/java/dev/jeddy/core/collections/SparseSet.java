package dev.jeddy.core.collections;

import java.util.Arrays;

/**
 * Sparse set of non-negative integer ids: O(1) add, remove and lookup, with the members packed
 * densely in {@link #dense()} for cache-friendly iteration. Component stores keep their SoA data
 * columns in the same dense order (DESIGN §4.1).
 *
 * <p>{@link #remove} moves the last dense element into the hole; callers that keep parallel data
 * columns must do the same swap (use the index returned by {@link #remove}).
 *
 * <p>Thread ownership: single owner thread; not thread-safe.
 */
public final class SparseSet {
    private static final int ABSENT = -1;

    private int[] sparse;
    private int[] dense;
    private int size;

    /** Creates an empty set for ids below 64. */
    public SparseSet() {
        this(64, 64);
    }

    /**
     * Creates an empty set.
     *
     * @param universe initial id range {@code [0, universe)}; grows on demand
     * @param capacity initial number of members
     */
    public SparseSet(int universe, int capacity) {
        sparse = new int[Math.max(1, universe)];
        Arrays.fill(sparse, ABSENT);
        dense = new int[Math.max(1, capacity)];
    }

    /** Returns the number of members. */
    public int size() {
        return size;
    }

    /** Returns the dense member array; indices {@code [0, size())} are valid. */
    public int[] dense() {
        return dense;
    }

    /** Returns {@code true} if {@code id} is a member. */
    public boolean contains(int id) {
        return id >= 0 && id < sparse.length && sparse[id] != ABSENT;
    }

    /** Returns the dense index of {@code id}, or -1 if it is not a member. */
    public int indexOf(int id) {
        return id >= 0 && id < sparse.length ? sparse[id] : ABSENT;
    }

    /**
     * Adds {@code id} if absent. Returns its dense index (the new last index when it was added).
     */
    public int add(int id) {
        if (id < 0) {
            throw new IllegalArgumentException("negative id " + id);
        }
        if (id >= sparse.length) {
            int old = sparse.length;
            sparse = Arrays.copyOf(sparse, Math.max(id + 1, old * 2));
            Arrays.fill(sparse, old, sparse.length, ABSENT);
        }
        int existing = sparse[id];
        if (existing != ABSENT) {
            return existing;
        }
        if (size == dense.length) {
            dense = Arrays.copyOf(dense, dense.length * 2);
        }
        dense[size] = id;
        sparse[id] = size;
        return size++;
    }

    /**
     * Removes {@code id}. Returns the dense index it occupied (the last member was moved there), or
     * -1 if it was not a member.
     */
    public int remove(int id) {
        int index = indexOf(id);
        if (index == ABSENT) {
            return ABSENT;
        }
        int last = dense[--size];
        dense[index] = last;
        sparse[last] = index;
        sparse[id] = ABSENT;
        return index;
    }

    /** Removes every member. */
    public void clear() {
        for (int i = 0; i < size; i++) {
            sparse[dense[i]] = ABSENT;
        }
        size = 0;
    }
}
