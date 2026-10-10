package dev.jeddy.physics.nav;

import java.util.Arrays;

/**
 * Search nodes for one query (DESIGN §11.4): SoA arrays indexed by node, found by polygon reference
 * through an open-addressing hash whose slots are valid only when stamped with the current
 * generation, so {@link #reset} is O(1) instead of clearing the table.
 *
 * <p>Thread ownership: owned by one {@link NavMeshQuery}.
 */
final class NavNodePool {
    static final int OPEN = 1;
    static final int CLOSED = 2;

    final int capacity;
    final int[] ref;
    final int[] parent;
    final int[] flags;
    final float[] cost;
    final float[] total;
    final float[] pos; // x, y, z per node
    private final int[] hashStamp;
    private final int[] hashNode;
    private final int hashMask;
    private int generation = 1;
    private int count;

    NavNodePool(int capacity) {
        this.capacity = capacity;
        ref = new int[capacity];
        parent = new int[capacity];
        flags = new int[capacity];
        cost = new float[capacity];
        total = new float[capacity];
        pos = new float[capacity * 3];
        int h = Integer.highestOneBit(Math.max(4, capacity * 2 - 1)) << 1;
        hashStamp = new int[h];
        hashNode = new int[h];
        hashMask = h - 1;
    }

    /** Forgets every node. */
    void reset() {
        count = 0;
        if (++generation == 0) {
            Arrays.fill(hashStamp, 0);
            generation = 1;
        }
    }

    /** Returns the number of nodes in use. */
    int count() {
        return count;
    }

    private int hash(int r) {
        int h = r * 0x9E3779B1;
        return (h ^ h >>> 15) & hashMask;
    }

    /** Returns the node of {@code r}, or -1. */
    int find(int r) {
        int h = hash(r);
        while (hashStamp[h] == generation) {
            int n = hashNode[h];
            if (ref[n] == r) {
                return n;
            }
            h = (h + 1) & hashMask;
        }
        return -1;
    }

    /** Returns the node of {@code r}, creating a fresh one; -1 when the pool is full. */
    int get(int r) {
        int h = hash(r);
        while (hashStamp[h] == generation) {
            int n = hashNode[h];
            if (ref[n] == r) {
                return n;
            }
            h = (h + 1) & hashMask;
        }
        if (count == capacity) {
            return -1;
        }
        int n = count++;
        hashStamp[h] = generation;
        hashNode[h] = n;
        ref[n] = r;
        parent[n] = -1;
        flags[n] = 0;
        cost[n] = 0;
        total[n] = 0;
        return n;
    }
}
