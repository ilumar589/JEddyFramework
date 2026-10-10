package dev.jeddy.core.memory;

import java.util.Arrays;

/**
 * Sub-allocates ranges of a fixed-size space (elements of a GPU buffer, slots of an array) with a
 * free list sorted by offset (DESIGN §9.2 mega buffers): first fit, and freed ranges merge with
 * their neighbours. Free ranges are kept as SoA {@code long} arrays, so allocating and freeing
 * allocate nothing unless the free list grows.
 *
 * <p>Thread ownership: single owner thread; not thread-safe.
 */
public final class RangeAllocator {
    private long capacity;
    private long[] offsets = new long[16];
    private long[] sizes = new long[16];
    private int count;
    private long free;

    /** Creates an allocator over {@code [0, capacity)}, all free. */
    public RangeAllocator(long capacity) {
        this.capacity = capacity;
        if (capacity > 0) {
            offsets[0] = 0;
            sizes[0] = capacity;
            count = 1;
        }
        free = capacity;
    }

    /** Returns the managed size. */
    public long capacity() {
        return capacity;
    }

    /** Returns the free size (possibly fragmented). */
    public long free() {
        return free;
    }

    /** Returns the size of the largest free range. */
    public long largestFree() {
        long max = 0;
        for (int i = 0; i < count; i++) {
            max = Math.max(max, sizes[i]);
        }
        return max;
    }

    /** Returns the number of free ranges (fragmentation). */
    public int freeRanges() {
        return count;
    }

    /**
     * Allocates {@code size} units aligned to {@code alignment} (a power of two). Returns the
     * offset, or -1 if no free range fits.
     */
    public long allocate(long size, long alignment) {
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive: " + size);
        }
        for (int i = 0; i < count; i++) {
            long start = offsets[i];
            long aligned = (start + alignment - 1) & -alignment;
            long pad = aligned - start;
            if (sizes[i] < size + pad) {
                continue;
            }
            long end = start + sizes[i];
            long tailStart = aligned + size;
            // Replace range i by the padding before and the remainder after.
            if (pad > 0 && tailStart < end) {
                sizes[i] = pad;
                insert(i + 1, tailStart, end - tailStart);
            } else if (pad > 0) {
                sizes[i] = pad;
            } else if (tailStart < end) {
                offsets[i] = tailStart;
                sizes[i] = end - tailStart;
            } else {
                remove(i);
            }
            free -= size;
            return aligned;
        }
        return -1;
    }

    /** Frees {@code [offset, offset + size)}, merging with adjacent free ranges. */
    public void free(long offset, long size) {
        if (offset < 0 || size <= 0 || offset + size > capacity) {
            throw new IllegalArgumentException("range outside the space: " + offset + "+" + size);
        }
        int i = Arrays.binarySearch(offsets, 0, count, offset);
        if (i >= 0) {
            throw new IllegalStateException("double free at " + offset);
        }
        i = -i - 1; // insertion point
        if (i > 0 && offsets[i - 1] + sizes[i - 1] > offset
                || i < count && offset + size > offsets[i]) {
            throw new IllegalStateException("free overlaps a free range at " + offset);
        }
        boolean mergePrev = i > 0 && offsets[i - 1] + sizes[i - 1] == offset;
        boolean mergeNext = i < count && offset + size == offsets[i];
        if (mergePrev && mergeNext) {
            sizes[i - 1] += size + sizes[i];
            remove(i);
        } else if (mergePrev) {
            sizes[i - 1] += size;
        } else if (mergeNext) {
            offsets[i] = offset;
            sizes[i] += size;
        } else {
            insert(i, offset, size);
        }
        free += size;
    }

    /** Extends the space to {@code newCapacity} (after the backing buffer grew). */
    public void grow(long newCapacity) {
        if (newCapacity <= capacity) {
            return;
        }
        long added = newCapacity - capacity;
        long old = capacity;
        capacity = newCapacity;
        if (count > 0 && offsets[count - 1] + sizes[count - 1] == old) {
            sizes[count - 1] += added;
        } else {
            insert(count, old, added);
        }
        free += added;
    }

    private void insert(int at, long offset, long size) {
        if (count == offsets.length) {
            offsets = Arrays.copyOf(offsets, count * 2);
            sizes = Arrays.copyOf(sizes, count * 2);
        }
        System.arraycopy(offsets, at, offsets, at + 1, count - at);
        System.arraycopy(sizes, at, sizes, at + 1, count - at);
        offsets[at] = offset;
        sizes[at] = size;
        count++;
    }

    private void remove(int at) {
        System.arraycopy(offsets, at + 1, offsets, at, count - at - 1);
        System.arraycopy(sizes, at + 1, sizes, at, count - at - 1);
        count--;
    }
}
