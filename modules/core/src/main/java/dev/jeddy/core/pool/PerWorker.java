package dev.jeddy.core.pool;

import java.util.function.IntFunction;

/**
 * One instance of {@code T} per job-system thread slot, indexed by the {@code threadIndex} that job
 * kernels receive (DESIGN §4.5). Gives every worker its own scratch buffers, query contexts or
 * pools with no synchronization and no {@code ThreadLocal} lookups.
 *
 * <p>Thread ownership: slot {@code i} belongs to the thread running with index {@code i}.
 *
 * @param <T> per-thread value type
 */
public final class PerWorker<T> {
    private final Object[] slots;

    /**
     * Creates one value per thread slot.
     *
     * @param threadSlots number of slots, normally {@code JobSystem.threadSlots()}
     * @param factory creates the value for a slot index
     */
    public PerWorker(int threadSlots, IntFunction<? extends T> factory) {
        slots = new Object[threadSlots];
        for (int i = 0; i < threadSlots; i++) {
            slots[i] = factory.apply(i);
        }
    }

    /** Returns the value of slot {@code threadIndex}. */
    @SuppressWarnings("unchecked")
    public T get(int threadIndex) {
        return (T) slots[threadIndex];
    }

    /** Returns the number of slots. */
    public int size() {
        return slots.length;
    }
}
