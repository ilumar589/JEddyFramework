package dev.jeddy.core.handle;

import java.util.Arrays;

/**
 * Maps generational handles to objects. Use it for coarse resources (GPU objects, sounds,
 * documents); per-entity data belongs in primitive arrays indexed by {@link Handles#index(int)}.
 *
 * <p>Thread ownership: single owner thread; not thread-safe.
 *
 * @param <T> value type
 */
public final class SlotMap<T> {
    private final HandlePool pool;
    private Object[] values;

    /** Creates an empty map. */
    public SlotMap() {
        this(64);
    }

    /**
     * Creates an empty map.
     *
     * @param initialCapacity slots allocated up front
     */
    public SlotMap(int initialCapacity) {
        pool = new HandlePool(initialCapacity, Handles.MAX_INDEX + 1);
        values = new Object[Math.max(1, initialCapacity)];
    }

    /** Returns the number of live entries. */
    public int size() {
        return pool.size();
    }

    /** Inserts {@code value} and returns its new handle. */
    public int insert(T value) {
        int handle = pool.allocate();
        int index = Handles.index(handle);
        if (index >= values.length) {
            values = Arrays.copyOf(values, Math.max(index + 1, values.length * 2));
        }
        values[index] = value;
        return handle;
    }

    /** Returns the value for {@code handle}, or {@code null} if the handle is stale. */
    @SuppressWarnings("unchecked")
    public T get(int handle) {
        return pool.isValid(handle) ? (T) values[Handles.index(handle)] : null;
    }

    /** Returns {@code true} if {@code handle} is live. */
    public boolean contains(int handle) {
        return pool.isValid(handle);
    }

    /** Removes the entry for {@code handle} and returns its value, or {@code null} if stale. */
    @SuppressWarnings("unchecked")
    public T remove(int handle) {
        if (!pool.isValid(handle)) {
            return null;
        }
        int index = Handles.index(handle);
        T old = (T) values[index];
        values[index] = null;
        pool.free(handle);
        return old;
    }

    /**
     * Returns the value in slot {@code index} if live, else {@code null}. Iterate slots {@code [0,
     * slotLimit())} to visit every entry without allocating.
     */
    @SuppressWarnings("unchecked")
    public T valueAt(int index) {
        return pool.isAlive(index) ? (T) values[index] : null;
    }

    /** Returns the handle of slot {@code index}, or {@link Handles#NULL}. */
    public int handleAt(int index) {
        return pool.handleAt(index);
    }

    /** Returns one past the highest slot index ever used. */
    public int slotLimit() {
        return pool.highWaterMark();
    }
}
