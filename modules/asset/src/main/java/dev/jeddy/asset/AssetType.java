package dev.jeddy.asset;

import dev.jeddy.core.collections.LongIntMap;
import dev.jeddy.core.handle.Handles;

/**
 * The handles of one asset type (TASKS M4-05): {@code int} handles with a generation, reference
 * counted by {@link #load} and {@link #release}. Loading the same id twice returns the same handle
 * while it is alive.
 *
 * <p>Priorities order the load queue: lower values load first (for example the distance to the
 * camera); the default is 0.
 *
 * <p>Thread ownership: the asset manager's owner thread (the game thread), except {@link #state}
 * and {@link #isReady}, which any thread may call for a handle the owner keeps alive.
 *
 * @param <T> the loaded value
 */
public final class AssetType<T> {
    private final AssetManager manager;
    private final int index;
    private final String name;
    final AssetLoader<T> loader;
    final boolean upload;
    final LongIntMap byId = new LongIntMap(64, Handles.NULL);

    AssetType(AssetManager manager, int index, String name, AssetLoader<T> loader) {
        this.manager = manager;
        this.index = index;
        this.name = name;
        this.loader = loader;
        this.upload = loader.needsUpload();
    }

    /** Returns the name given at registration. */
    public String name() {
        return name;
    }

    /** Returns the manager this type belongs to. */
    public AssetManager manager() {
        return manager;
    }

    int index() {
        return index;
    }

    /** Requests the asset {@code id} with priority 0 and returns its handle (one reference). */
    public int load(long id) {
        return manager.load(this, id, 0f);
    }

    /** Requests the asset {@code id} and returns its handle (one reference). */
    public int load(long id, float priority) {
        return manager.load(this, id, priority);
    }

    /** Requests the asset at {@code path} (normalized) and returns its handle. */
    public int load(String path) {
        return manager.load(this, AssetPaths.id(path), 0f);
    }

    /** Requests the asset at {@code path} (normalized) and returns its handle. */
    public int load(String path, float priority) {
        return manager.load(this, AssetPaths.id(path), priority);
    }

    /** Drops one reference; the asset unloads when none are left. */
    public void release(int handle) {
        manager.release(this, handle);
    }

    /** Returns the {@link AssetState} of the asset. Any thread. */
    public int state(int handle) {
        return manager.stateOf(handle);
    }

    /** Returns {@code true} once {@link #get} returns the value. Any thread. */
    public boolean isReady(int handle) {
        return manager.stateOf(handle) == AssetState.READY;
    }

    /**
     * Returns {@code true} when the asset is ready and every dependency has finished loading (ready
     * or failed), recursively.
     */
    public boolean isComplete(int handle) {
        return manager.isComplete(manager.checkedIndex(this, handle));
    }

    /** Returns the loaded value, or {@code null} unless the asset is {@link AssetState#READY}. */
    @SuppressWarnings("unchecked")
    public T get(int handle) {
        return (T) manager.value(manager.checkedIndex(this, handle));
    }

    /** Returns how many times the asset was reloaded (0 for the first load). */
    public int version(int handle) {
        return manager.version(manager.checkedIndex(this, handle));
    }

    /** Returns the asset id of a handle. */
    public long id(int handle) {
        return manager.idAt(manager.checkedIndex(this, handle));
    }

    /** Returns why the asset failed (or the last reload failed), or {@code null}. */
    public Throwable error(int handle) {
        return manager.error(manager.checkedIndex(this, handle));
    }

    /** Returns the number of references to the asset. */
    public int refCount(int handle) {
        return manager.refCount(manager.checkedIndex(this, handle));
    }

    /** Changes the asset's load priority (lower loads first) while it is queued. */
    public void setPriority(int handle, float priority) {
        manager.setPriority(manager.checkedIndex(this, handle), priority);
    }

    /** Returns the number of dependencies the asset declared while decoding. */
    public int dependencyCount(int handle) {
        return manager.dependencyCount(manager.checkedIndex(this, handle));
    }

    /**
     * Returns the handle of the asset's {@code i}-th dependency (in {@link DecodeContext#dependsOn}
     * order), valid in the dependency's own type.
     */
    public int dependency(int handle, int i) {
        return manager.dependency(manager.checkedIndex(this, handle), i);
    }

    /** Returns the handle of a loaded or loading asset, or {@link Handles#NULL}. No reference. */
    public int find(long id) {
        return byId.get(id);
    }

    @Override
    public String toString() {
        return "AssetType[" + name + "]";
    }
}
