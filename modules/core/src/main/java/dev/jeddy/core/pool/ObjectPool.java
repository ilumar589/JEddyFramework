package dev.jeddy.core.pool;

import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A thread-confined pool of reusable objects (DESIGN §4.2). Pool only objects that must exist and
 * are expensive or native-backed; small short-lived objects are cheaper to allocate than to pool.
 *
 * <p>{@link #obtain()} returns a pooled object or creates one with the factory; {@link #release}
 * runs the optional reset hook and returns it to the pool.
 *
 * <p>Thread ownership: single owner thread. Use {@link PerWorker} for one pool per job worker.
 *
 * @param <T> pooled type
 */
public final class ObjectPool<T> {
    private final Supplier<? extends T> factory;
    private final Consumer<? super T> reset;
    private final int maxPooled;
    private Object[] items;
    private int size;
    private int created;

    /**
     * Creates a pool.
     *
     * @param factory creates new objects when the pool is empty
     * @param reset called on every released object before it is pooled, or {@code null}
     * @param maxPooled objects beyond this many released at once are dropped for the GC
     */
    public ObjectPool(Supplier<? extends T> factory, Consumer<? super T> reset, int maxPooled) {
        if (maxPooled < 1) {
            throw new IllegalArgumentException("maxPooled < 1");
        }
        this.factory = factory;
        this.reset = reset;
        this.maxPooled = maxPooled;
        this.items = new Object[Math.min(16, maxPooled)];
    }

    /**
     * Creates objects until {@code count} are pooled, so later {@link #obtain()} calls don't
     * allocate.
     */
    public void prefill(int count) {
        int target = Math.min(count, maxPooled);
        while (size < target) {
            push(factory.get());
            created++;
        }
    }

    /** Returns a pooled object, creating one if the pool is empty. */
    @SuppressWarnings("unchecked")
    public T obtain() {
        if (size == 0) {
            created++;
            return factory.get();
        }
        T item = (T) items[--size];
        items[size] = null;
        return item;
    }

    /** Resets {@code item} and returns it to the pool. */
    public void release(T item) {
        if (item == null) {
            throw new IllegalArgumentException("null item");
        }
        if (reset != null) {
            reset.accept(item);
        }
        if (size < maxPooled) {
            push(item);
        }
    }

    /** Returns the number of objects currently pooled. */
    public int pooled() {
        return size;
    }

    /** Returns how many objects the factory has created in total. */
    public int created() {
        return created;
    }

    private void push(Object item) {
        if (size == items.length) {
            items = Arrays.copyOf(items, Math.min(maxPooled, items.length * 2));
        }
        items[size++] = item;
    }
}
