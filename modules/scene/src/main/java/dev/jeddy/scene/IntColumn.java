package dev.jeddy.scene;

import java.util.Arrays;

/**
 * A {@code int} column of a {@link ComponentStore}. Hot loops fetch {@link #data()} once per pass
 * and index it with the dense index; the array is replaced when the store grows, so do not keep it
 * across structural changes.
 *
 * <p>Thread ownership: see {@link Column}.
 */
public final class IntColumn extends Column {
    private int[] data;
    private final int defaultValue;

    IntColumn(String name, int capacity, int defaultValue) {
        super(name);
        this.defaultValue = defaultValue;
        data = new int[capacity];
    }

    /** Returns the backing array; indices {@code [0, store.size())} are valid. */
    public int[] data() {
        return data;
    }

    /** Returns the value at dense index {@code index}. */
    public int get(int index) {
        return data[index];
    }

    /** Sets the value at dense index {@code index}. */
    public void set(int index, int value) {
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
