package dev.jeddy.core.collections;

import java.util.Arrays;

/**
 * Open-addressing hash map from {@code int} to objects: linear probing, backward-shift
 * deletion (no tombstones), no boxing. Lookups of absent keys return {@code null}.
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
public final class IntObjMap<V> {
    private static final float LOAD_FACTOR = 0.6f;

    private int[] keys;
    private Object[] values;
    private int mask;
    private int size;
    private int maxFill;
    private boolean hasZeroKey;
    private Object zeroValue;

    /** Creates a map for 16 entries. */
    public IntObjMap() {
        this(16);
    }

    /**
     * Creates a map.
     *
     * @param expected number of entries to hold without rehashing
     */
    public IntObjMap(int expected) {
        allocate(Hashing.tableSize(expected, LOAD_FACTOR));
    }

    /** Returns the number of entries. */
    public int size() {
        return size;
    }

    /** Returns {@code true} when the map is empty. */
    public boolean isEmpty() {
        return size == 0;
    }

    /** Returns the value for {@code key}, or {@code null}. */
    @SuppressWarnings("unchecked")
    public V get(int key) {
        if (key == 0) {
            return hasZeroKey ? (V) zeroValue : null;
        }
        int pos = Hashing.mix(key) & mask;
        int k;
        while ((k = keys[pos]) != 0) {
            if (k == key) {
                return (V) values[pos];
            }
            pos = (pos + 1) & mask;
        }
        return null;
    }

    /** Returns the value for {@code key}, or {@code defaultValue}. */
    @SuppressWarnings("unchecked")
    public V getOrDefault(int key, V defaultValue) {
        if (key == 0) {
            return hasZeroKey ? (V) zeroValue : defaultValue;
        }
        int pos = Hashing.mix(key) & mask;
        int k;
        while ((k = keys[pos]) != 0) {
            if (k == key) {
                return (V) values[pos];
            }
            pos = (pos + 1) & mask;
        }
        return defaultValue;
    }

    /** Returns {@code true} if the map holds {@code key}. */
    public boolean containsKey(int key) {
        if (key == 0) {
            return hasZeroKey;
        }
        int pos = Hashing.mix(key) & mask;
        int k;
        while ((k = keys[pos]) != 0) {
            if (k == key) {
                return true;
            }
            pos = (pos + 1) & mask;
        }
        return false;
    }

    /** Maps {@code key} to {@code value}. Returns the previous value, or {@code null}. */
    @SuppressWarnings("unchecked")
    public V put(int key, V value) {
        if (key == 0) {
            V old = hasZeroKey ? (V) zeroValue : null;
            if (!hasZeroKey) {
                hasZeroKey = true;
                size++;
            }
            zeroValue = value;
            return old;
        }
        int pos = Hashing.mix(key) & mask;
        int k;
        while ((k = keys[pos]) != 0) {
            if (k == key) {
                V old = (V) values[pos];
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
        return null;
    }

    /** Removes {@code key}. Returns its value, or {@code null} if it was absent. */
    @SuppressWarnings("unchecked")
    public V remove(int key) {
        if (key == 0) {
            if (!hasZeroKey) {
                return null;
            }
            hasZeroKey = false;
            size--;
            V old = (V) zeroValue;
            zeroValue = null;
            return old;
        }
        int pos = Hashing.mix(key) & mask;
        int k;
        while ((k = keys[pos]) != 0) {
            if (k == key) {
                V old = (V) values[pos];
                size--;
                shiftKeys(pos);
                return old;
            }
            pos = (pos + 1) & mask;
        }
        return null;
    }

    /** Removes every entry; keeps the table. */
    public void clear() {
        if (size == 0) {
            return;
        }
        Arrays.fill(keys, 0);
        Arrays.fill(values, null);
        hasZeroKey = false;
        zeroValue = null;
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
    public int keyAt(int slot) {
        return slot == keys.length ? 0 : keys[slot];
    }

    /** Returns the value stored in an occupied {@code slot}. */
    @SuppressWarnings("unchecked")
    public V valueAt(int slot) {
        return (V) (slot == keys.length ? zeroValue : values[slot]);
    }

    /** Replaces the value stored in an occupied {@code slot}. */
    public void setValueAt(int slot, V value) {
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
            int k;
            for (; ; ) {
                if ((k = keys[pos]) == 0) {
                    keys[last] = 0;
                    values[last] = null;
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
        keys = new int[capacity];
        values = new Object[capacity];
        mask = capacity - 1;
        maxFill = (int) (capacity * LOAD_FACTOR);
    }

    private void rehash(int newCapacity) {
        int[] oldKeys = keys;
        Object[] oldValues = values;
        allocate(newCapacity);
        for (int i = 0; i < oldKeys.length; i++) {
            int k = oldKeys[i];
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
