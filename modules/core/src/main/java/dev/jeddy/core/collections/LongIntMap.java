package dev.jeddy.core.collections;

import java.util.Arrays;

/**
 * Open-addressing hash map from {@code long} to {@code int}: linear probing, backward-shift
 * deletion (no tombstones), no boxing. Lookups of absent keys return the {@linkplain
 * #missingValue() missing value} chosen at construction.
 *
 * <p>Iterate without allocating:
 *
 * <pre>{@code
 * for (int s = map.firstSlot(); s >= 0; s = map.nextSlot(s)) {
 *     use(map.keyAt(s), map.valueAt(s));
 * }
 * }</pre>
 *
 * Changing the map during iteration is undefined, except {@link #setValueAt}.
 *
 * <p>Thread ownership: single owner thread; not thread-safe.
 */
public final class LongIntMap {
    private static final float LOAD_FACTOR = 0.6f;

    private long[] keys;
    private int[] values;
    private int mask;
    private int size;
    private int maxFill;
    private boolean hasZeroKey;
    private int zeroValue;
    private final int missing;

    /** Creates a map for 16 entries whose missing value is -1. */
    public LongIntMap() {
        this(16, -1);
    }

    /**
     * Creates a map.
     *
     * @param expected number of entries to hold without rehashing
     * @param missingValue value returned for absent keys
     */
    public LongIntMap(int expected, int missingValue) {
        missing = missingValue;
        allocate(Hashing.tableSize(expected, LOAD_FACTOR));
    }

    /** Returns the value returned for absent keys. */
    public int missingValue() {
        return missing;
    }

    /** Returns the number of entries. */
    public int size() {
        return size;
    }

    /** Returns {@code true} when the map is empty. */
    public boolean isEmpty() {
        return size == 0;
    }

    /** Returns the value for {@code key}, or the missing value. */
    public int get(long key) {
        if (key == 0L) {
            return hasZeroKey ? zeroValue : missing;
        }
        int pos = Hashing.mix(key) & mask;
        long k;
        while ((k = keys[pos]) != 0) {
            if (k == key) {
                return values[pos];
            }
            pos = (pos + 1) & mask;
        }
        return missing;
    }

    /** Returns the value for {@code key}, or {@code defaultValue}. */
    public int getOrDefault(long key, int defaultValue) {
        if (key == 0L) {
            return hasZeroKey ? zeroValue : defaultValue;
        }
        int pos = Hashing.mix(key) & mask;
        long k;
        while ((k = keys[pos]) != 0) {
            if (k == key) {
                return values[pos];
            }
            pos = (pos + 1) & mask;
        }
        return defaultValue;
    }

    /** Returns {@code true} if the map holds {@code key}. */
    public boolean containsKey(long key) {
        if (key == 0L) {
            return hasZeroKey;
        }
        int pos = Hashing.mix(key) & mask;
        long k;
        while ((k = keys[pos]) != 0) {
            if (k == key) {
                return true;
            }
            pos = (pos + 1) & mask;
        }
        return false;
    }

    /** Maps {@code key} to {@code value}. Returns the previous value, or the missing value. */
    public int put(long key, int value) {
        if (key == 0L) {
            int old = hasZeroKey ? zeroValue : missing;
            if (!hasZeroKey) {
                hasZeroKey = true;
                size++;
            }
            zeroValue = value;
            return old;
        }
        int pos = Hashing.mix(key) & mask;
        long k;
        while ((k = keys[pos]) != 0) {
            if (k == key) {
                int old = values[pos];
                values[pos] = value;
                return old;
            }
            pos = (pos + 1) & mask;
        }
        keys[pos] = key;
        values[pos] = value;
        if (++size > maxFill) {
            rehash(keys.length << 1);
        }
        return missing;
    }

    /**
     * Adds {@code delta} to the value of {@code key}, treating an absent key as 0. Returns the new
     * value.
     */
    public int addTo(long key, int delta) {
        if (key == 0L) {
            if (!hasZeroKey) {
                hasZeroKey = true;
                size++;
                zeroValue = 0;
            }
            return zeroValue += delta;
        }
        int pos = Hashing.mix(key) & mask;
        long k;
        while ((k = keys[pos]) != 0) {
            if (k == key) {
                return values[pos] += delta;
            }
            pos = (pos + 1) & mask;
        }
        keys[pos] = key;
        values[pos] = delta;
        if (++size > maxFill) {
            rehash(keys.length << 1);
        }
        return delta;
    }

    /** Removes {@code key}. Returns its value, or the missing value if it was absent. */
    public int remove(long key) {
        if (key == 0L) {
            if (!hasZeroKey) {
                return missing;
            }
            hasZeroKey = false;
            size--;
            return zeroValue;
        }
        int pos = Hashing.mix(key) & mask;
        long k;
        while ((k = keys[pos]) != 0) {
            if (k == key) {
                int old = values[pos];
                size--;
                shiftKeys(pos);
                return old;
            }
            pos = (pos + 1) & mask;
        }
        return missing;
    }

    /** Removes every entry; keeps the table. */
    public void clear() {
        if (size == 0) {
            return;
        }
        Arrays.fill(keys, 0);
        hasZeroKey = false;
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
        return slot < capacity && hasZeroKey ? capacity : -1;
    }

    /** Returns the key stored in an occupied {@code slot}. */
    public long keyAt(int slot) {
        return slot == keys.length ? 0 : keys[slot];
    }

    /** Returns the value stored in an occupied {@code slot}. */
    public int valueAt(int slot) {
        return slot == keys.length ? zeroValue : values[slot];
    }

    /** Replaces the value stored in an occupied {@code slot}. */
    public void setValueAt(int slot, int value) {
        if (slot == keys.length) {
            zeroValue = value;
        } else {
            values[slot] = value;
        }
    }

    private void shiftKeys(int pos) {
        int last;
        for (; ; ) {
            last = pos;
            pos = (pos + 1) & mask;
            long k;
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
            values[last] = values[pos];
        }
    }

    private void allocate(int capacity) {
        keys = new long[capacity];
        values = new int[capacity];
        mask = capacity - 1;
        maxFill = (int) (capacity * LOAD_FACTOR);
    }

    private void rehash(int newCapacity) {
        long[] oldKeys = keys;
        int[] oldValues = values;
        allocate(newCapacity);
        for (int i = 0; i < oldKeys.length; i++) {
            long k = oldKeys[i];
            if (k != 0) {
                int pos = Hashing.mix(k) & mask;
                while (keys[pos] != 0) {
                    pos = (pos + 1) & mask;
                }
                keys[pos] = k;
                values[pos] = oldValues[i];
            }
        }
    }
}
