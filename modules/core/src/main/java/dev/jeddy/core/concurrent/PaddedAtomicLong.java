package dev.jeddy.core.concurrent;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

abstract class PaddedLongPad0 {
    long p00, p01, p02, p03, p04, p05, p06, p07;
}

abstract class PaddedLongValue extends PaddedLongPad0 {
    long value;
}

abstract class PaddedLongPad1 extends PaddedLongValue {
    long p10, p11, p12, p13, p14, p15, p16, p17;
}

/**
 * An atomic {@code long} alone on its cache line, so hot counters updated by different threads
 * don't slow each other down through false sharing (DESIGN §4.5).
 *
 * <p>Thread ownership: thread-safe.
 */
public final class PaddedAtomicLong extends PaddedLongPad1 {
    private static final VarHandle VALUE;

    static {
        try {
            VALUE = MethodHandles.lookup().findVarHandle(PaddedLongValue.class, "value", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Creates a counter starting at 0. */
    public PaddedAtomicLong() {}

    /** Creates a counter. */
    public PaddedAtomicLong(long initial) {
        VALUE.setVolatile(this, initial);
    }

    /** Volatile read. */
    public long get() {
        return (long) VALUE.getVolatile(this);
    }

    /** Acquire read. */
    public long getAcquire() {
        return (long) VALUE.getAcquire(this);
    }

    /** Volatile write. */
    public void set(long v) {
        VALUE.setVolatile(this, v);
    }

    /** Release write. */
    public void setRelease(long v) {
        VALUE.setRelease(this, v);
    }

    /** Atomically adds {@code delta}; returns the previous value. */
    public long getAndAdd(long delta) {
        return (long) VALUE.getAndAdd(this, delta);
    }

    /** Atomically adds {@code delta}; returns the new value. */
    public long addAndGet(long delta) {
        return (long) VALUE.getAndAdd(this, delta) + delta;
    }

    /** Atomically increments; returns the new value. */
    public long incrementAndGet() {
        return addAndGet(1);
    }

    /** Atomically sets to {@code update} if the value is {@code expected}. */
    public boolean compareAndSet(long expected, long update) {
        return VALUE.compareAndSet(this, expected, update);
    }
}
