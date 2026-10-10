package dev.jeddy.scene;

/**
 * A per-entity store that the {@link EntityPool} notifies when an entity is destroyed.
 *
 * <p>Thread ownership: the pool's owner thread.
 */
public interface EntityStore {
    /** Drops the data of {@code entity} if the store has any. Called by {@link EntityPool}. */
    void remove(int entity);
}
