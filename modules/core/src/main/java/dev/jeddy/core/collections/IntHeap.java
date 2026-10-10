package dev.jeddy.core.collections;

import java.util.Arrays;

/**
 * Binary min-heap of integer items keyed by {@code float} priorities, with O(log n) decrease-key.
 * Items are integers in {@code [0, universe)} (for example navmesh node indices); each item is in
 * the heap at most once. Built for A* open lists (DESIGN §11.4).
 *
 * <p>Thread ownership: single owner thread; not thread-safe.
 */
public final class IntHeap {
    private int[] items;
    private float[] keys;
    private int[] position; // heap index per item, -1 when absent
    private int size;

    /**
     * Creates a heap.
     *
     * @param universe items must be in {@code [0, universe)}; grows on demand
     * @param capacity initial number of items it holds without growing
     */
    public IntHeap(int universe, int capacity) {
        items = new int[Math.max(1, capacity)];
        keys = new float[items.length];
        position = new int[Math.max(1, universe)];
        Arrays.fill(position, -1);
    }

    /** Returns the number of items in the heap. */
    public int size() {
        return size;
    }

    /** Returns {@code true} when the heap is empty. */
    public boolean isEmpty() {
        return size == 0;
    }

    /** Returns {@code true} if {@code item} is in the heap. */
    public boolean contains(int item) {
        return item < position.length && position[item] >= 0;
    }

    /** Returns the current key of {@code item}, which must be in the heap. */
    public float keyOf(int item) {
        return keys[position[item]];
    }

    /** Returns the item with the smallest key without removing it. */
    public int peek() {
        if (size == 0) {
            throw new IllegalStateException("empty");
        }
        return items[0];
    }

    /** Returns the smallest key. */
    public float peekKey() {
        if (size == 0) {
            throw new IllegalStateException("empty");
        }
        return keys[0];
    }

    /**
     * Adds {@code item} with {@code key}; if already present, updates its key in either direction.
     */
    public void push(int item, float key) {
        if (item < 0) {
            throw new IllegalArgumentException("negative item " + item);
        }
        if (item >= position.length) {
            int old = position.length;
            position = Arrays.copyOf(position, Math.max(item + 1, old * 2));
            Arrays.fill(position, old, position.length, -1);
        }
        int at = position[item];
        if (at >= 0) {
            float old = keys[at];
            keys[at] = key;
            if (key < old) {
                siftUp(at);
            } else {
                siftDown(at);
            }
            return;
        }
        if (size == items.length) {
            items = Arrays.copyOf(items, size * 2);
            keys = Arrays.copyOf(keys, size * 2);
        }
        items[size] = item;
        keys[size] = key;
        position[item] = size;
        siftUp(size++);
    }

    /** Lowers the key of {@code item} (which must be present) to {@code key}. */
    public void decreaseKey(int item, float key) {
        int at = position[item];
        if (key > keys[at]) {
            throw new IllegalArgumentException("new key is larger");
        }
        keys[at] = key;
        siftUp(at);
    }

    /** Removes and returns the item with the smallest key. */
    public int pop() {
        if (size == 0) {
            throw new IllegalStateException("empty");
        }
        int top = items[0];
        position[top] = -1;
        if (--size > 0) {
            items[0] = items[size];
            keys[0] = keys[size];
            position[items[0]] = 0;
            siftDown(0);
        }
        return top;
    }

    /** Removes {@code item} if it is in the heap. Returns whether it was. */
    public boolean remove(int item) {
        if (!contains(item)) {
            return false;
        }
        int at = position[item];
        position[item] = -1;
        if (--size > at) {
            int moved = items[size];
            items[at] = moved;
            keys[at] = keys[size];
            position[moved] = at;
            siftUp(at);
            if (items[at] == moved) {
                siftDown(at);
            }
        }
        return true;
    }

    /** Removes every item in O(size). */
    public void clear() {
        for (int i = 0; i < size; i++) {
            position[items[i]] = -1;
        }
        size = 0;
    }

    private void siftUp(int i) {
        int item = items[i];
        float key = keys[i];
        while (i > 0) {
            int parent = (i - 1) >>> 1;
            if (keys[parent] <= key) {
                break;
            }
            items[i] = items[parent];
            keys[i] = keys[parent];
            position[items[i]] = i;
            i = parent;
        }
        items[i] = item;
        keys[i] = key;
        position[item] = i;
    }

    private void siftDown(int i) {
        int item = items[i];
        float key = keys[i];
        int half = size >>> 1;
        while (i < half) {
            int child = 2 * i + 1;
            int right = child + 1;
            if (right < size && keys[right] < keys[child]) {
                child = right;
            }
            if (key <= keys[child]) {
                break;
            }
            items[i] = items[child];
            keys[i] = keys[child];
            position[items[i]] = i;
            i = child;
        }
        items[i] = item;
        keys[i] = key;
        position[item] = i;
    }
}
