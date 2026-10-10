package dev.jeddy.scene;

import java.util.Arrays;

/**
 * A {@code long} column of a {@link ComponentStore}. Hot loops fetch {@link #data()} once per pass
 * and index it with the dense index; the array is replaced when the store grows, so do not keep it
 * across structural changes.
 *
 * <p>Thread ownership: see {@link Column}.
 */
public final class LongColumn extends Column {
    private long[] data;
    private final long defaultValue;

    LongColumn(String name, int capacity, long defaultValue) {
        super(name);
        this.defaultValue = defaultValue;
        data = new long[capacity];
    }

    /** Returns the backing array; indices {@code [0, store.size())} are valid. */
    public long[] data() {
        return data;
    }

    /** Returns the value at dense index {@code index}. */
    public long get(int index) {
        return data[index];
    }

    /** Sets the value at dense index {@code index}. */
    public void set(int index, long value) {
        data[index] = value;
    }

    @Override
    void grow(int capacity) {
        data = Arrays.copyOf(data, capacity);
    }

    @Override
    void move(int from, int to) {
        data[to] = data[from];
    }

    @Override
    void reset(int index) {
        data[index] = defaultValue;
    }
}
