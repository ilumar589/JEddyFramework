package dev.jeddy.scene;

import dev.jeddy.core.handle.HandlePool;
import dev.jeddy.core.handle.Handles;

import java.util.Arrays;

/**
 * Entities as generational {@code int} handles (TASKS M5-01, DESIGN §13.1). An entity is only an
 * id: its data lives in {@link ComponentStore}s and the {@link TransformStore}, indexed by {@link
 * Handles#index(int)}. Destroying an entity removes it from every registered store and makes the
 * handle stale.
 *
 * <p>Thread ownership: single owner thread (the game thread); not thread-safe.
 */
public final class EntityPool {
    private final HandlePool handles;
    private EntityStore[] stores = new EntityStore[8];
    private int storeCount;

    /** Creates a pool for at most {@code maxEntities} live entities. */
    public EntityPool(int maxEntities) {
        handles = new HandlePool(Math.min(1024, maxEntities), maxEntities);
    }

    /** Creates a pool with the maximum handle range. */
    public EntityPool() {
        this(Handles.MAX_INDEX + 1);
    }

    /** Creates an entity. */
    public int create() {
        return handles.allocate();
    }

    /** Destroys {@code entity}: removes it from every store, then frees the handle. */
    public void destroy(int entity) {
        if (!handles.isValid(entity)) {
            throw new IllegalArgumentException(
                    "stale or invalid entity " + Handles.toString(entity));
        }
        for (int i = 0; i < storeCount; i++) {
            stores[i].remove(entity);
        }
        handles.free(entity);
    }

    /** Returns {@code true} if {@code entity} is alive. */
    public boolean isAlive(int entity) {
        return handles.isValid(entity);
    }

    /** Returns the number of live entities. */
    public int size() {
        return handles.size();
    }

    /** Returns the highest slot index ever used, plus one (the range of {@link Handles#index}). */
    public int highWaterMark() {
        return handles.highWaterMark();
    }

    /** Returns the live entity in slot {@code index}, or {@link Handles#NULL}. */
    public int entityAt(int index) {
        return handles.handleAt(index);
    }

    /** Registers a store to be notified when entities are destroyed. */
    public void register(EntityStore store) {
        if (storeCount == stores.length) {
            stores = Arrays.copyOf(stores, storeCount * 2);
        }
        stores[storeCount++] = store;
    }

    /** Throws unless {@code entity} is alive. */
    void check(int entity) {
        if (!handles.isValid(entity)) {
            throw new IllegalArgumentException(
                    "stale or invalid entity " + Handles.toString(entity));
        }
    }
}
