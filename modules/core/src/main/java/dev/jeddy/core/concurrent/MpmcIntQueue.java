package dev.jeddy.core.concurrent;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

abstract class MpmcPad0 {
    long p00, p01, p02, p03, p04, p05, p06, p07;
}

abstract class MpmcEnqueue extends MpmcPad0 {
    long enqueuePos;
}

abstract class MpmcPad1 extends MpmcEnqueue {
    long p10, p11, p12, p13, p14, p15, p16, p17;
}

abstract class MpmcDequeue extends MpmcPad1 {
    long dequeuePos;
}

abstract class MpmcPad2 extends MpmcDequeue {
    long p20, p21, p22, p23, p24, p25, p26, p27;
}

/**
 * Bounded lock-free multi-producer, multi-consumer queue of non-negative {@code int}s (Dmitry
 * Vyukov's algorithm: one sequence number per slot, CAS on the positions). Used for the job
 * system's ready queue.
 *
 * <p>Thread ownership: thread-safe for any number of producers and consumers.
 */
public final class MpmcIntQueue extends MpmcPad2 {
    /** Returned by {@link #poll()} when the queue is empty. */
    public static final int EMPTY = -1;

    private static final VarHandle ENQUEUE;
    private static final VarHandle DEQUEUE;
    private static final VarHandle SEQ = MethodHandles.arrayElementVarHandle(long[].class);

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            ENQUEUE = lookup.findVarHandle(MpmcEnqueue.class, "enqueuePos", long.class);
            DEQUEUE = lookup.findVarHandle(MpmcDequeue.class, "dequeuePos", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final int[] buffer;
    private final long[] sequence;
    private final int mask;

    /**
     * Creates a queue.
     *
     * @param capacity number of slots; must be a power of two
     */
    public MpmcIntQueue(int capacity) {
        if (capacity < 2 || Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("capacity must be a power of two >= 2: " + capacity);
        }
        buffer = new int[capacity];
        sequence = new long[capacity];
        for (int i = 0; i < capacity; i++) {
            sequence[i] = i;
        }
        mask = capacity - 1;
        VarHandle.releaseFence();
    }

    /** Returns the capacity. */
    public int capacity() {
        return buffer.length;
    }

    /** Appends {@code value} (must be &gt;= 0). Returns {@code false} if the queue is full. */
    public boolean offer(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("negative value " + value);
        }
        long pos = (long) ENQUEUE.getOpaque(this);
        while (true) {
            int index = (int) pos & mask;
            long seq = (long) SEQ.getAcquire(sequence, index);
            long dif = seq - pos;
            if (dif == 0) {
                if (ENQUEUE.weakCompareAndSet(this, pos, pos + 1)) {
                    buffer[index] = value;
                    SEQ.setRelease(sequence, index, pos + 1);
                    return true;
                }
                pos = (long) ENQUEUE.getOpaque(this);
            } else if (dif < 0) {
                return false;
            } else {
                pos = (long) ENQUEUE.getOpaque(this);
            }
        }
    }

    /** Removes and returns the oldest value, or {@link #EMPTY}. */
    public int poll() {
        long pos = (long) DEQUEUE.getOpaque(this);
        while (true) {
            int index = (int) pos & mask;
            long seq = (long) SEQ.getAcquire(sequence, index);
            long dif = seq - (pos + 1);
            if (dif == 0) {
                if (DEQUEUE.weakCompareAndSet(this, pos, pos + 1)) {
                    int value = buffer[index];
                    SEQ.setRelease(sequence, index, pos + mask + 1);
                    return value;
                }
                pos = (long) DEQUEUE.getOpaque(this);
            } else if (dif < 0) {
                return EMPTY;
            } else {
                pos = (long) DEQUEUE.getOpaque(this);
            }
        }
    }

    /** Returns an estimate of the number of queued values. */
    public int size() {
        long e = (long) ENQUEUE.getOpaque(this);
        long d = (long) DEQUEUE.getOpaque(this);
        return (int) Math.max(0, Math.min(buffer.length, e - d));
    }

    /** Returns {@code true} if the queue looks empty right now. */
    public boolean isEmpty() {
        return size() == 0;
    }
}
