package dev.jeddy.core.memory;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;

/**
 * Bump allocator over one fixed off-heap block, reset once per frame (DESIGN §4.3). No malloc, no
 * free, no GC work for native temporaries.
 *
 * <p>Two ways to use it:
 *
 * <ul>
 *   <li>{@link #allocateOffset(long, long)} returns an offset into {@link #segment()}. Reading and
 *       writing through {@code segment()} at that offset allocates nothing; use this in hot
 *       paths.
 *   <li>As a {@link SegmentAllocator} ({@code allocate}, {@code allocateFrom}): convenient for
 *       FFM calls, but each call returns a new slice object, which the JIT usually but not always
 *       removes.
 * </ul>
 *
 * Running out of space throws instead of growing, so frame budgets stay deterministic; size the
 * block from {@link #highWaterMark()}.
 *
 * <p>Thread ownership: one allocator per thread (for example per job worker via {@code
 * PerWorker}); not thread-safe.
 */
public final class FrameAllocator implements SegmentAllocator, AutoCloseable {
    private final OffHeap block;
    private final MemorySegment segment;
    private long offset;
    private long highWater;

    /**
     * Creates an allocator.
     *
     * @param capacityBytes size of the block
     */
    public FrameAllocator(long capacityBytes) {
        block = OffHeap.allocate(capacityBytes, 64, MemoryCategory.TRANSIENT);
        segment = block.segment();
    }

    /** Returns the whole block; offsets from {@link #allocateOffset} index into it. */
    public MemorySegment segment() {
        return segment;
    }

    /**
     * Reserves {@code byteSize} bytes aligned to {@code alignment} and returns their offset in
     * {@link #segment()}. The memory is not cleared.
     */
    public long allocateOffset(long byteSize, long alignment) {
        if (alignment <= 0 || (alignment & (alignment - 1)) != 0) {
            throw new IllegalArgumentException("alignment must be a power of two: " + alignment);
        }
        long start = (offset + alignment - 1) & -alignment;
        long end = start + byteSize;
        if (end > segment.byteSize()) {
            throw new IllegalStateException(
                    "FrameAllocator out of space: need "
                            + end
                            + " of "
                            + segment.byteSize()
                            + " bytes; raise its capacity");
        }
        offset = end;
        if (end > highWater) {
            highWater = end;
        }
        return start;
    }

    @Override
    public MemorySegment allocate(long byteSize, long byteAlignment) {
        long start = allocateOffset(byteSize, byteAlignment);
        return segment.asSlice(start, byteSize);
    }

    /** Releases everything allocated since the last reset. */
    public void reset() {
        offset = 0;
    }

    /** Returns the bytes currently in use. */
    public long used() {
        return offset;
    }

    /** Returns the most bytes used between two resets since creation. */
    public long highWaterMark() {
        return highWater;
    }

    /** Returns the block size. */
    public long capacity() {
        return segment.byteSize();
    }

    /** Frees the block. */
    @Override
    public void close() {
        block.close();
    }
}
