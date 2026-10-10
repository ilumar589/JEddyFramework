package dev.jeddy.core.concurrent;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lock-free triple-buffer index exchange between one producer and one consumer (DESIGN §5: the game
 * thread fills frame N while the main thread renders N-1). The buffers themselves are the caller's
 * (three preallocated render packets); this class only hands out indices 0, 1 and 2.
 *
 * <p>The producer always owns one index ({@link #writeIndex()}), the consumer one ({@link
 * #readIndex()}), and the third is the latest published frame. {@link #publish()} swaps the write
 * index with the latest; {@link #acquire()} swaps the read index with the latest if a new frame was
 * published. Neither side ever waits, and nothing allocates. A producer that wants to stay at most
 * one frame ahead waits while {@link #hasUnconsumed()} is {@code true} before publishing again.
 *
 * <p>Thread ownership: exactly one producer thread ({@code writeIndex}, {@code publish}) and one
 * consumer thread ({@code readIndex}, {@code acquire}); {@code hasUnconsumed} from either.
 */
public final class TripleBuffer {
    private static final int INDEX_MASK = 3;
    private static final int FRESH = 4;

    private final AtomicInteger latest = new AtomicInteger(2);
    private int writeIndex;
    private int readIndex = 1;
    private long published;
    private long overwritten;

    /** Creates the exchange with nothing published. */
    public TripleBuffer() {}

    /** Producer: returns the index of the buffer to fill now. */
    public int writeIndex() {
        return writeIndex;
    }

    /**
     * Producer: publishes the buffer at {@link #writeIndex()} and takes a new one to fill.
     *
     * @return {@code true} if the previous publication had not been acquired and was overwritten
     */
    public boolean publish() {
        int previous = latest.getAndSet(writeIndex | FRESH);
        writeIndex = previous & INDEX_MASK;
        published++;
        if ((previous & FRESH) != 0) {
            overwritten++;
            return true;
        }
        return false;
    }

    /** Returns {@code true} while a published buffer has not been acquired yet. */
    public boolean hasUnconsumed() {
        return (latest.get() & FRESH) != 0;
    }

    /**
     * Consumer: takes the newest published buffer if there is one.
     *
     * @return {@code true} if {@link #readIndex()} now refers to a newly published buffer
     */
    public boolean acquire() {
        if ((latest.get() & FRESH) == 0) {
            return false;
        }
        int previous = latest.getAndSet(readIndex);
        readIndex = previous & INDEX_MASK;
        return true;
    }

    /** Consumer: returns the index of the buffer last acquired (to render). */
    public int readIndex() {
        return readIndex;
    }

    /** Producer: returns how many buffers were published. */
    public long publishedCount() {
        return published;
    }

    /** Producer: returns how many publications were overwritten before the consumer saw them. */
    public long overwrittenCount() {
        return overwritten;
    }
}
