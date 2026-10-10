package dev.jeddy.core.collections;

import java.util.Arrays;

/**
 * Open-addressing hash set of {@code int}s (linear probing, backward-shift deletion, no boxing).
 * Iterate with {@link #firstSlot()}/{@link #nextSlot(int)}/{@link #keyAt(int)}.
 *
 * <p>Thread ownership: single owner thread; not thread-safe.
 */
public final class IntSet {
    private static final float LOAD_FACTOR = 0.6f;

    private int[] keys;
    private int mask;
    private int size;
    private int maxFill;
    private boolean hasZero;

    /** Creates a set for 16 elements. */
    public IntSet() {
        this(16);
    }

    /**
     * Creates a set.
     *
     * @param expected number of elements to hold without rehashing
     */
    public IntSet(int expected) {
        allocate(Hashing.tableSize(expected, LOAD_FACTOR));
    }

    /** Returns the number of elements. */
    public int size() {
        return size;
    }

    /** Returns {@code true} when the set is empty. */
    public boolean isEmpty() {
        return size == 0;
    }

    /** Returns {@code true} if {@code value} is in the set. */
    public boolean contains(int value) {
        if (value == 0) {
            return hasZero;
        }
        int pos = Hashing.mix(value) & mask;
        int k;
        while ((k = keys[pos]) != 0) {
            if (k == value) {
                return true;
            }
            pos = (pos + 1) & mask;
        }
        return false;
    }

    /** Adds {@code value}. Returns {@code true} if it was not already present. */
    public boolean add(int value) {
        if (value == 0) {
            if (hasZero) {
                return false;
            }
            hasZero = true;
            size++;
            return true;
        }
        int pos = Hashing.mix(value) & mask;
        int k;
        while ((k = keys[pos]) != 0) {
            if (k == value) {
                return false;
            }
            pos = (pos + 1) & mask;
        }
        keys[pos] = value;
        if (++size > maxFill) {
            rehash(keys.length << 1);
        }
        return true;
    }

    /** Removes {@code value}. Returns {@code true} if it was present. */
    public boolean remove(int value) {
        if (value == 0) {
            if (!hasZero) {
                return false;
            }
            hasZero = false;
            size--;
            return true;
        }
        int pos = Hashing.mix(value) & mask;
        int k;
        while ((k = keys[pos]) != 0) {
            if (k == value) {
                size--;
                shiftKeys(pos);
                return true;
            }
            pos = (pos + 1) & mask;
        }
        return false;
    }

    /** Removes every element; keeps the table. */
    public void clear() {
        if (size == 0) {
            return;
        }
        Arrays.fill(keys, 0);
        hasZero = false;
        size = 0;
    }

    /** Returns the first occupied slot, or -1 when empty. */
    public int firstSlot() {
        return nextSlot(-1);
    }

    /** Returns the occupied slot after {@code slot}, or -1. */
    public int nextSlot(int slot) {
        int capacity = keys.length;
        for (int i = slot + 1; i < capacity; i++) {
            if (keys[i] != 0) {
                return i;
            }
        }
        return slot < capacity && hasZero ? capacity : -1;
    }

    /** Returns the element stored in an occupied {@code slot}. */
    public int keyAt(int slot) {
        return slot == keys.length ? 0 : keys[slot];
    }

    private void shiftKeys(int pos) {
        int last;
        for (; ; ) {
            last = pos;
            pos = (pos + 1) & mask;
            int k;
            for (; ; ) {
                if ((k = keys[pos]) == 0) {
                    keys[last] = 0;
                    return;
                }
                int home = Hashing.mix(k) & mask;
                if (!Hashing.cyclicBetween(last, home, pos)) {
                    break;
                }
                pos = (pos + 1) & mask;
            }
            keys[last] = k;
        }
    }

    private void allocate(int capacity) {
        keys = new int[capacity];
        mask = capacity - 1;
        maxFill = (int) (capacity * LOAD_FACTOR);
    }

    private void rehash(int newCapacity) {
        int[] old = keys;
        allocate(newCapacity);
        for (int k : old) {
            if (k != 0) {
                int pos = Hashing.mix(k) & mask;
                while (keys[pos] != 0) {
                    pos = (pos + 1) & mask;
                }
                keys[pos] = k;
            }
        }
    }
}
