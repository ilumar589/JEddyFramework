package dev.jeddy.core.collections;

import java.util.Arrays;

/**
 * A growable list of primitive {@code int}s. No boxing, no iterator objects: iterate with {@code
 * for (int i = 0; i < list.size(); i++) list.get(i)} or over {@link #array()} directly.
 *
 * <p>Grows only when {@link #add} exceeds the capacity, so a list reused across frames stops
 * allocating once it has reached its working size.
 *
 * <p>Thread ownership: single owner thread; not thread-safe.
 */
public final class IntList {
    private int[] data;
    private int size;

    /** Creates an empty list with capacity 16. */
    public IntList() {
        this(16);
    }

    /**
     * Creates an empty list.
     *
     * @param capacity initial capacity, at least 0
     */
    public IntList(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity < 0: " + capacity);
        }
        data = new int[Math.max(capacity, 1)];
    }

    /** Returns the number of elements. */
    public int size() {
        return size;
    }

    /** Returns {@code true} when the list has no elements. */
    public boolean isEmpty() {
        return size == 0;
    }

    /** Returns the current capacity. */
    public int capacity() {
        return data.length;
    }

    /**
     * Returns the backing array. Only indices {@code [0, size())} are meaningful; the array is
     * replaced when the list grows, so don't keep the reference across {@link #add} calls.
     */
    public int[] array() {
        return data;
    }

    /** Returns the element at {@code index}. */
    public int get(int index) {
        checkIndex(index);
        return data[index];
    }

    /** Replaces the element at {@code index}. */
    public void set(int index, int value) {
        checkIndex(index);
        data[index] = value;
    }

    /** Appends a value. */
    public void add(int value) {
        if (size == data.length) {
            grow(size + 1);
        }
        data[size++] = value;
    }

    /** Appends {@code count} values from {@code src} starting at {@code offset}. */
    public void addAll(int[] src, int offset, int count) {
        ensureCapacity(size + count);
        System.arraycopy(src, offset, data, size, count);
        size += count;
    }

    /** Appends every element of {@code other}. */
    public void addAll(IntList other) {
        addAll(other.data, 0, other.size);
    }

    /** Inserts {@code value} at {@code index}, shifting later elements right. */
    public void insert(int index, int value) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException(index);
        }
        if (size == data.length) {
            grow(size + 1);
        }
        System.arraycopy(data, index, data, index + 1, size - index);
        data[index] = value;
        size++;
    }

    /** Removes and returns the last element. */
    public int removeLast() {
        if (size == 0) {
            throw new IllegalStateException("empty");
        }
        return data[--size];
    }

    /** Removes the element at {@code index}, preserving order. Returns the removed value. */
    public int removeAt(int index) {
        checkIndex(index);
        int v = data[index];
        System.arraycopy(data, index + 1, data, index, size - index - 1);
        size--;
        return v;
    }

    /**
     * Removes the element at {@code index} by moving the last element into its place (O(1), does
     * not preserve order). Returns the removed value.
     */
    public int removeAtSwap(int index) {
        checkIndex(index);
        int v = data[index];
        data[index] = data[--size];
        return v;
    }

    /** Returns the first index of {@code value}, or -1. */
    public int indexOf(int value) {
        for (int i = 0; i < size; i++) {
            if (data[i] == value) {
                return i;
            }
        }
        return -1;
    }

    /** Returns {@code true} if the list contains {@code value} (linear search). */
    public boolean contains(int value) {
        return indexOf(value) >= 0;
    }

    /** Removes all elements; keeps the capacity. */
    public void clear() {
        size = 0;
    }

    /** Sets the size, growing if needed. New elements are zero. */
    public void setSize(int newSize) {
        if (newSize < 0) {
            throw new IllegalArgumentException("size < 0: " + newSize);
        }
        ensureCapacity(newSize);
        if (newSize > size) {
            Arrays.fill(data, size, newSize, 0);
        }
        size = newSize;
    }

    /** Makes sure the list can hold {@code capacity} elements without growing. */
    public void ensureCapacity(int capacity) {
        if (capacity > data.length) {
            grow(capacity);
        }
    }

    /** Sorts the elements in ascending order. */
    public void sort() {
        Arrays.sort(data, 0, size);
    }

    /** Fills every element with {@code value}. */
    public void fill(int value) {
        Arrays.fill(data, 0, size, value);
    }

    /** Returns a new array with exactly the list's elements (allocates). */
    public int[] toArray() {
        return Arrays.copyOf(data, size);
    }

    private void grow(int minCapacity) {
        int newCapacity = Math.max(minCapacity, data.length + (data.length >> 1) + 1);
        data = Arrays.copyOf(data, newCapacity);
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index " + index + ", size " + size);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(data[i]);
        }
        return sb.append(']').toString();
    }
}
