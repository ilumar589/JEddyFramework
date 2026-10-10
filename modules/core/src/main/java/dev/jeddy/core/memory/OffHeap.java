package dev.jeddy.core.memory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Explicitly owned off-heap blocks with {@link MemoryStats} accounting. Each block has its own
 * shared {@link Arena}, so it can be freed deterministically from any thread (never {@code
 * Arena.ofAuto()}, DESIGN §4.3).
 *
 * <p>Thread ownership: a block may be used from any thread; {@link #close()} must not race with
 * use.
 */
public final class OffHeap implements AutoCloseable {
    private final Arena arena;
    private final MemorySegment segment;
    private final MemoryCategory category;
    private boolean closed;

    private OffHeap(long bytes, long alignment, MemoryCategory category) {
        this.arena = Arena.ofShared();
        this.segment = arena.allocate(bytes, alignment);
        this.category = category;
        MemoryStats.allocated(category, bytes);
    }

    /**
     * Allocates a zeroed block.
     *
     * @param bytes size in bytes
     * @param alignment power-of-two alignment in bytes
     * @param category what the memory is for
     */
    public static OffHeap allocate(long bytes, long alignment, MemoryCategory category) {
        if (bytes <= 0) {
            throw new IllegalArgumentException("bytes <= 0: " + bytes);
        }
        return new OffHeap(bytes, alignment, category);
    }

    /** Returns the block's memory. Invalid after {@link #close()}. */
    public MemorySegment segment() {
        return segment;
    }

    /** Returns the block size in bytes. */
    public long byteSize() {
        return segment.byteSize();
    }

    /** Frees the block. Idempotent. */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            MemoryStats.released(category, segment.byteSize());
            arena.close();
        }
    }
}
