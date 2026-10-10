package dev.jeddy.scene;

/**
 * A data column of a {@link ComponentStore}: one primitive array in the store's dense order.
 *
 * <p>Thread ownership: written by the store's owner; systems may read and write elements from job
 * workers while the store's structure does not change.
 */
public abstract sealed class Column permits FloatColumn, IntColumn, LongColumn {
    private final String name;

    Column(String name) {
        this.name = name;
    }

    /** Returns the column name (diagnostics). */
    public String name() {
        return name;
    }

    abstract void grow(int capacity);

    /** Copies element {@code from} to {@code to} (swap-remove). */
    abstract void move(int from, int to);

    /** Writes the default value at {@code index}. */
    abstract void reset(int index);
}
