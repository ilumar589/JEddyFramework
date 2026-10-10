package dev.jeddy.core.collections;

/**
 * Growable ring buffer (double-ended queue) of {@code int}s. Used for FIFO free lists and BFS
 * frontiers.
 *
 * <p>Thread ownership: single owner thread; not thread-safe. For cross-thread queues use {@code
 * dev.jeddy.core.concurrent}.
 */
public final class IntRing {
    private int[] data;
    private int head;
    private int size;

    /** Creates a ring with capacity 16. */
    public IntRing() {
        this(16);
    }

    /**
     * Creates a ring.
     *
     * @param capacity initial capacity; rounded up to a power of two
     */
    public IntRing(int capacity) {
        data = new int[Integer.highestOneBit(Math.max(2, capacity) - 1) << 1];
    }

    /** Returns the number of elements. */
    public int size() {
        return size;
    }

    /** Returns {@code true} when empty. */
    public boolean isEmpty() {
        return size == 0;
    }

    /** Appends at the back. */
    public void addLast(int value) {
        if (size == data.length) {
            grow();
        }
        data[(head + size) & (data.length - 1)] = value;
        size++;
    }

    /** Prepends at the front. */
    public void addFirst(int value) {
        if (size == data.length) {
            grow();
        }
        head = (head - 1) & (data.length - 1);
        data[head] = value;
        size++;
    }

    /** Removes and returns the front element. */
    public int removeFirst() {
        if (size == 0) {
            throw new IllegalStateException("empty");
        }
        int v = data[head];
        head = (head + 1) & (data.length - 1);
        size--;
        return v;
    }

    /** Removes and returns the back element. */
    public int removeLast() {
        if (size == 0) {
            throw new IllegalStateException("empty");
        }
        size--;
        return data[(head + size) & (data.length - 1)];
    }

    /** Returns the front element without removing it. */
    public int peekFirst() {
        if (size == 0) {
            throw new IllegalStateException("empty");
        }
        return data[head];
    }

    /** Returns the element at position {@code index} from the front. */
    public int get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException(index);
        }
        return data[(head + index) & (data.length - 1)];
    }

    /** Removes every element. */
    public void clear() {
        head = 0;
        size = 0;
    }

    private void grow() {
        int[] next = new int[data.length * 2];
        int firstPart = Math.min(size, data.length - head);
        System.arraycopy(data, head, next, 0, firstPart);
        System.arraycopy(data, 0, next, firstPart, size - firstPart);
        data = next;
        head = 0;
    }
}
