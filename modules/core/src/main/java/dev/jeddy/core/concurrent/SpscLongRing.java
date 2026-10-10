package dev.jeddy.core.concurrent;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

// Padding through inheritance keeps the producer's and consumer's hot fields on separate cache
// lines (the JVM lays out superclass fields first and never reorders across classes).
abstract class SpscLongPad0 {
    long p00, p01, p02, p03, p04, p05, p06, p07;
}

abstract class SpscLongConsumer extends SpscLongPad0 {
    long head; // next slot to read; written by the consumer only
    long tailCache; // consumer's last view of tail
}

abstract class SpscLongPad1 extends SpscLongConsumer {
    long p10, p11, p12, p13, p14, p15, p16, p17;
}

abstract class SpscLongProducer extends SpscLongPad1 {
    long tail; // next slot to write; written by the producer only
    long headCache; // producer's last view of head
}

abstract class SpscLongPad2 extends SpscLongProducer {
    long p20, p21, p22, p23, p24, p25, p26, p27;
}

/**
 * Lock-free single-producer, single-consumer ring of {@code long}s (DESIGN §4.5). Moves fixed-size
 * records atomically: {@link #offer(int[], int, int)} publishes all {@code n} values or none, so
 * the consumer never sees half a record. Used for profiler records and timestamps.
 *
 * <p>Thread ownership: exactly one producer thread calls {@code offer}; exactly one consumer
 * thread calls {@code poll}/{@code drain}. {@link #size()} may be called from either.
 */
public final class SpscLongRing extends SpscLongPad2 {
    private static final VarHandle HEAD;
    private static final VarHandle TAIL;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            HEAD = lookup.findVarHandle(SpscLongConsumer.class, "head", long.class);
            TAIL = lookup.findVarHandle(SpscLongProducer.class, "tail", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final long[] buffer;
    private final int mask;

    /**
     * Creates a ring.
     *
     * @param capacity number of longs; must be a power of two
     */
    public SpscLongRing(int capacity) {
        if (capacity < 2 || Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("capacity must be a power of two >= 2: " + capacity);
        }
        buffer = new long[capacity];
        mask = capacity - 1;
    }

    /** Returns the capacity in longs. */
    public int capacity() {
        return buffer.length;
    }

    /** Returns an estimate of the number of longs waiting. */
    public int size() {
        long t = (long) TAIL.getAcquire(this);
        long h = (long) HEAD.getAcquire(this);
        return (int) Math.max(0, Math.min(buffer.length, t - h));
    }

    /** Producer: appends one value. Returns {@code false} if the ring is full. */
    public boolean offer(long value) {
        long t = tail;
        if (headCache <= t - buffer.length) {
            headCache = (long) HEAD.getAcquire(this);
            if (headCache <= t - buffer.length) {
                return false;
            }
        }
        buffer[(int) t & mask] = value;
        TAIL.setRelease(this, t + 1);
        return true;
    }

    /**
     * Producer: appends {@code n} values as one record. Returns {@code false}, writing nothing, if
     * they don't all fit.
     */
    public boolean offer(long[] src, int offset, int n) {
        if (n > buffer.length) {
            throw new IllegalArgumentException("record larger than the ring: " + n);
        }
        long t = tail;
        long wrap = t + n - buffer.length;
        if (headCache < wrap) {
            headCache = (long) HEAD.getAcquire(this);
            if (headCache < wrap) {
                return false;
            }
        }
        for (int i = 0; i < n; i++) {
            buffer[(int) (t + i) & mask] = src[offset + i];
        }
        TAIL.setRelease(this, t + n);
        return true;
    }

    /**
     * Consumer: removes {@code n} values into {@code dst[offset..]}. Returns {@code false},
     * reading nothing, if fewer than {@code n} are available.
     */
    public boolean poll(long[] dst, int offset, int n) {
        long h = head;
        if (tailCache < h + n) {
            tailCache = (long) TAIL.getAcquire(this);
            if (tailCache < h + n) {
                return false;
            }
        }
        for (int i = 0; i < n; i++) {
            dst[offset + i] = buffer[(int) (h + i) & mask];
        }
        HEAD.setRelease(this, h + n);
        return true;
    }

    /**
     * Consumer: removes up to {@code max} values into {@code dst[offset..]}. Returns how many were
     * removed.
     */
    public int drain(long[] dst, int offset, int max) {
        long h = head;
        long t = (long) TAIL.getAcquire(this);
        tailCache = t;
        int n = (int) Math.min(max, t - h);
        for (int i = 0; i < n; i++) {
            dst[offset + i] = buffer[(int) (h + i) & mask];
        }
        if (n > 0) {
            HEAD.setRelease(this, h + n);
        }
        return n;
    }
}
