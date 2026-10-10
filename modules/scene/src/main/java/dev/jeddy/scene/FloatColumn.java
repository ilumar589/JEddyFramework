package dev.jeddy.scene;

import java.util.Arrays;

/**
 * A {@code float} column of a {@link ComponentStore}. Hot loops fetch {@link #data()} once per pass
 * and index it with the dense index; the array is replaced when the store grows, so do not keep it
 * across structural changes.
 *
 * <p>Thread ownership: see {@link Column}.
 */
public final class FloatColumn extends Column {
    private float[] data;
    private final float defaultValue;

    FloatColumn(String name, int capacity, float defaultValue) {
        super(name);
        this.defaultValue = defaultValue;
        data = new float[capacity];
    }

    /** Returns the backing array; indices {@code [0, store.size())} are valid. */
    public float[] data() {
        return data;
    }

    /** Returns the value at dense index {@code index}. */
    public float get(int index) {
        return data[index];
    }

    /** Sets the value at dense index {@code index}. */
    public void set(int index, float value) {
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
