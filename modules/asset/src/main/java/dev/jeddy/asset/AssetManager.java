package dev.jeddy.asset;

import dev.jeddy.compress.ZstdDecompressor;
import dev.jeddy.core.collections.IntHeap;
import dev.jeddy.core.concurrent.MpmcIntQueue;
import dev.jeddy.core.handle.HandlePool;
import dev.jeddy.core.handle.Handles;
import dev.jeddy.core.job.JobKernel;
import dev.jeddy.core.job.JobSystem;
import dev.jeddy.core.log.Log;
import dev.jeddy.core.log.Logger;
import dev.jeddy.core.pool.PerWorker;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

/**
 * Loads assets asynchronously (TASKS M4-05, DESIGN §10.2).
 *
 * <p>Pipeline, per asset:
 *
 * <ol>
 *   <li><b>Request</b> ({@link AssetType#load}, owner thread): a handle, a reference and a place in
 *       the priority queue.
 *   <li><b>Schedule</b> ({@link #update}, owner thread): while fewer than {@code maxInFlight} loads
 *       run and the staging ring has room, the most urgent asset gets a staging region and goes to
 *       an IO thread.
 *   <li><b>IO</b> (virtual threads): loose files are read into the staging region; pak entries are
 *       prefetched (their pages touched) so the workers do not fault on disk.
 *   <li><b>Decode</b> (a job on the {@link JobSystem}): pak entries are decompressed straight from
 *       the mapping into the staging region, then {@link AssetLoader#decode} parses them.
 *   <li><b>Upload</b> ({@link #pumpUploads}, main thread, budgeted per frame) for types that need
 *       it, then {@link AssetState#READY} with a release store.
 *   <li><b>Complete</b> ({@link #update}): staging is freed and declared dependencies are loaded.
 * </ol>
 *
 * Slots live in fixed-size arrays (structure of arrays), so steady-state frames allocate nothing;
 * each load allocates a little (the decoded value, dependency lists).
 *
 * <p>Thread ownership: one <em>owner</em> thread (the game thread) calls {@link #register}, {@link
 * #update}, {@link #reload} and the {@link AssetType} methods. The main thread calls {@link
 * #pumpUploads}. {@link #close} runs on the main thread after the owner has stopped (or on the
 * owner when nothing needs uploads).
 */
public final class AssetManager implements AutoCloseable {
    private static final Logger LOG = Log.logger("asset");
    private static final VarHandle STATE = MethodHandles.arrayElementVarHandle(byte[].class);
    private static final VarHandle VALUE = MethodHandles.arrayElementVarHandle(Object[].class);
    private static final int MAX_TYPES = 64;
    private static final int MAX_RETRIES = 3;

    /** Construction settings. */
    public static final class Config {
        int maxAssets = 1 << 16;
        long stagingBytes = 64L << 20;
        int maxInFlight = 32;
        int ioThreads = 2;

        /** Creates the default settings. */
        public Config() {}

        /** Maximum number of live asset slots (fixed arrays; default 65536). */
        public Config maxAssets(int n) {
            maxAssets = n;
            return this;
        }

        /**
         * Size of the staging ring (default 64 MiB). Larger assets get dedicated memory while they
         * load.
         */
        public Config stagingBytes(long n) {
            stagingBytes = n;
            return this;
        }

        /** Maximum number of assets between scheduling and completion (default 32). */
        public Config maxInFlight(int n) {
            maxInFlight = n;
            return this;
        }

        /** Number of IO threads (virtual threads; default 2). */
        public Config ioThreads(int n) {
            ioThreads = n;
            return this;
        }
    }

    private final Vfs vfs;
    private final JobSystem jobs;
    private final int maxInFlight;

    // Owner thread.
    private final HandlePool handles;
    private final IntHeap queue;
    private final StagingRing staging;
    private final AssetType<?>[] types = new AssetType<?>[MAX_TYPES];
    private int typeCount;
    private int inFlight;
    private final int[] refs;
    private final float[] priority;
    private final int[] stagingToken;
    private final Arena[] dedicated;
    private final boolean[] busy; // between scheduling and completion
    private final boolean[] dropped;
    private final boolean[] reloadAgain;
    private final boolean[] settled;
    private final int[] retries;
    private final int[] versions;
    private final int[][] dependencies;

    // Written by the owner before a slot is handed to the pipeline, read by pipeline threads.
    private final long[] ids;
    private final byte[] typeOf;
    private final int[] handleOf;
    private final long[] sizes;
    private final MemorySegment[] data;
    private final boolean[] reloading;

    // Written by pipeline threads, read by the owner after the completion handoff.
    private final byte[] state; // STATE var handle
    private final Object[] values; // VALUE var handle
    private final Object[] pending; // decoded value waiting for upload or for a reload swap
    private final Object[] unloadValues;
    private final Throwable[] errors;
    private final boolean[] readDone;
    private final boolean[] retry;
    private final long[][] declaredIds;
    private final byte[][] declaredTypes;

    // Handoff queues (slot indices).
    private final MpmcIntQueue ioQueue;
    private final MpmcIntQueue decodeQueue;
    private final MpmcIntQueue uploadQueue;
    private final MpmcIntQueue completed;
    private final MpmcIntQueue unloadQueue;
    private final MpmcIntQueue unloaded;

    private final PerWorker<DecodeContext> contexts;
    private final PerWorker<ZstdDecompressor> decompressors;
    private final JobKernel decodeKernel = this::decodeChunk;
    private final Thread[] ioThreads;
    private volatile boolean running = true;

    /**
     * Creates a manager reading from {@code vfs} and decoding on {@code jobs}, which must have at
     * least one worker.
     */
    public AssetManager(Vfs vfs, JobSystem jobs, Config config) {
        if (jobs.workerCount() == 0) {
            throw new IllegalArgumentException("the asset manager needs job workers");
        }
        this.vfs = vfs;
        this.jobs = jobs;
        int n = config.maxAssets;
        if (n < 1 || n > Handles.MAX_INDEX + 1) {
            throw new IllegalArgumentException("maxAssets out of range: " + n);
        }
        maxInFlight = Math.max(1, config.maxInFlight);
        handles = new HandlePool(Math.min(n, 1024), n);
        queue = new IntHeap(n, 256);
        staging = new StagingRing(Math.max(64, config.stagingBytes), maxInFlight);
        refs = new int[n];
        priority = new float[n];
        stagingToken = new int[n];
        dedicated = new Arena[n];
        busy = new boolean[n];
        dropped = new boolean[n];
        reloadAgain = new boolean[n];
        settled = new boolean[n];
        retries = new int[n];
        versions = new int[n];
        dependencies = new int[n][];
        ids = new long[n];
        typeOf = new byte[n];
        handleOf = new int[n];
        sizes = new long[n];
        data = new MemorySegment[n];
        reloading = new boolean[n];
        state = new byte[n];
        values = new Object[n];
        pending = new Object[n];
        unloadValues = new Object[n];
        errors = new Throwable[n];
        readDone = new boolean[n];
        retry = new boolean[n];
        declaredIds = new long[n][];
        declaredTypes = new byte[n][];
        int flight = pow2(maxInFlight * 2);
        ioQueue = new MpmcIntQueue(flight);
        decodeQueue = new MpmcIntQueue(flight);
        uploadQueue = new MpmcIntQueue(flight);
        completed = new MpmcIntQueue(flight);
        unloadQueue = new MpmcIntQueue(pow2(n));
        unloaded = new MpmcIntQueue(pow2(n));
        decompressors = new PerWorker<>(jobs.threadSlots(), i -> new ZstdDecompressor());
        contexts =
                new PerWorker<>(
                        jobs.threadSlots(), i -> new DecodeContext(i, decompressors.get(i)));
        ioThreads = new Thread[Math.max(1, config.ioThreads)];
        for (int i = 0; i < ioThreads.length; i++) {
            ioThreads[i] = Thread.ofVirtual().name("jeddy-asset-io-" + i).start(this::ioLoop);
        }
    }

    /** Creates a manager with the default {@link Config}. */
    public AssetManager(Vfs vfs, JobSystem jobs) {
        this(vfs, jobs, new Config());
    }

    /** Returns the VFS assets are read from. */
    public Vfs vfs() {
        return vfs;
    }

    /**
     * Registers an asset type. Owner thread, before loading.
     *
     * @param name for logs
     */
    public <T> AssetType<T> register(String name, AssetLoader<T> loader) {
        if (typeCount == MAX_TYPES) {
            throw new IllegalStateException("more than " + MAX_TYPES + " asset types");
        }
        AssetType<T> type = new AssetType<>(this, typeCount, name, loader);
        types[typeCount++] = type;
        return type;
    }

    // ----------------------------------------------------------------------------------------
    // Owner thread

    /**
     * Completes finished loads (frees staging, loads dependencies, swaps reloads, logs failures)
     * and starts new ones. Call once per frame on the owner thread; allocates nothing when no load
     * starts or finishes.
     */
    public void update() {
        int s;
        while ((s = unloaded.poll()) != MpmcIntQueue.EMPTY) {
            handles.free(handleOf[s]);
        }
        while ((s = completed.poll()) != MpmcIntQueue.EMPTY) {
            complete(s);
        }
        schedule();
    }

    /** Returns the number of assets queued but not started. */
    public int queuedCount() {
        return queue.size();
    }

    /** Returns the number of assets between scheduling and completion. */
    public int inFlightCount() {
        return inFlight;
    }

    /** Returns the number of live asset slots. */
    public int liveCount() {
        return handles.size();
    }

    /**
     * Reloads every loaded asset with this id in place: the handle stays {@link AssetState#READY}
     * with the old value until the new one has been decoded (and uploaded); then the value is
     * swapped, the old one unloaded and {@link AssetType#version} incremented. Failed assets try
     * again. Owner thread.
     *
     * @return the number of assets (across types) that will reload
     */
    public int reload(long id) {
        int n = 0;
        for (int t = 0; t < typeCount; t++) {
            int h = types[t].byId.get(id);
            if (h != Handles.NULL && handles.isValid(h)) {
                scheduleReload(Handles.index(h));
                n++;
            }
        }
        return n;
    }

    <T> int load(AssetType<T> type, long id, float prio) {
        int h = type.byId.get(id);
        if (h != Handles.NULL) {
            int s = Handles.index(h);
            refs[s]++;
            if (prio < priority[s]) {
                setPriority(s, prio);
            }
            return h;
        }
        h = handles.allocate();
        int s = Handles.index(h);
        handleOf[s] = h;
        ids[s] = id;
        typeOf[s] = (byte) type.index();
        refs[s] = 1;
        priority[s] = prio;
        stagingToken[s] = -1;
        busy[s] = false;
        dropped[s] = false;
        reloadAgain[s] = false;
        reloading[s] = false;
        settled[s] = false;
        retries[s] = 0;
        versions[s] = 0;
        dependencies[s] = null;
        errors[s] = null;
        VALUE.setRelease(values, s, null);
        setState(s, AssetState.QUEUED);
        type.byId.put(id, h);
        queue.push(s, prio);
        return h;
    }

    <T> void release(AssetType<T> type, int handle) {
        int s = checkedIndex(type, handle);
        if (refs[s] <= 0) {
            throw new IllegalStateException("released more often than loaded: " + describe(s));
        }
        if (--refs[s] > 0) {
            return;
        }
        if (type.byId.get(ids[s]) == handle) {
            type.byId.remove(ids[s]);
        }
        if (queue.remove(s)) {
            reloading[s] = false;
            if (stateAt(s) != AssetState.READY) {
                setState(s, AssetState.UNLOADED);
                handles.free(handle); // never started: nothing to unload
                return;
            }
        } else if (inFlight(s)) {
            dropped[s] = true; // freed when the load completes
            return;
        }
        freeSlot(s);
    }

    int checkedIndex(AssetType<?> type, int handle) {
        if (!handles.isValid(handle)) {
            throw new IllegalArgumentException(
                    "stale or invalid asset handle " + Handles.toString(handle));
        }
        int s = Handles.index(handle);
        if (typeOf[s] != type.index()) {
            throw new IllegalArgumentException(
                    Handles.toString(handle) + " is not a " + type.name() + " handle");
        }
        return s;
    }

    int stateOf(int handle) {
        return (byte) STATE.getAcquire(state, Handles.index(handle));
    }

    Object value(int s) {
        return stateAt(s) == AssetState.READY ? VALUE.getAcquire(values, s) : null;
    }

    int version(int s) {
        return versions[s];
    }

    long idAt(int s) {
        return ids[s];
    }

    Throwable error(int s) {
        return errors[s];
    }

    int refCount(int s) {
        return refs[s];
    }

    int dependencyCount(int s) {
        int[] d = dependencies[s];
        return d == null ? 0 : d.length;
    }

    int dependency(int s, int i) {
        return dependencies[s][i];
    }

    void setPriority(int s, float prio) {
        priority[s] = prio;
        if (queue.contains(s)) {
            queue.push(s, prio);
        }
    }

    boolean isComplete(int s) {
        return isComplete(s, 0);
    }

    private boolean isComplete(int s, int depth) {
        if (depth > 32) {
            return true; // a dependency cycle: everything on it has been visited
        }
        if (stateAt(s) != AssetState.READY || !settled[s]) {
            return false;
        }
        int[] d = dependencies[s];
        if (d != null) {
            for (int h : d) {
                if (!handles.isValid(h)) {
                    continue;
                }
                int ds = Handles.index(h);
                int st = stateAt(ds);
                if (st == AssetState.FAILED) {
                    continue;
                }
                if (ds != s && !isComplete(ds, depth + 1)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean inFlight(int s) {
        return busy[s];
    }

    private void scheduleReload(int s) {
        int st = stateAt(s);
        if (dropped[s]) {
            return;
        }
        if (queue.contains(s)) {
            return; // not started yet: it will read the current file
        }
        if (busy[s]) {
            reloadAgain[s] = true; // reload once the current load has completed
            return;
        }
        if (st == AssetState.READY) {
            reloading[s] = true;
            errors[s] = null;
            queue.push(s, priority[s]);
        } else if (st == AssetState.FAILED) {
            errors[s] = null;
            retries[s] = 0;
            setState(s, AssetState.QUEUED);
            queue.push(s, priority[s]);
        }
        // QUEUED: will load the current file anyway.
    }

    private void schedule() {
        while (inFlight < maxInFlight && !queue.isEmpty()) {
            int s = queue.peek();
            long size = vfs.size(ids[s]);
            if (size < 0) {
                queue.pop();
                fail(s, new NoSuchFileException(vfs.describe(ids[s])));
                continue;
            }
            MemorySegment region;
            if (size > staging.capacity() / 2) {
                Arena a = Arena.ofShared();
                dedicated[s] = a;
                region = a.allocate(Math.max(1, size), 64);
                stagingToken[s] = -1;
            } else {
                int token = staging.allocate(size);
                if (token < 0) {
                    return; // the ring is full: wait for loads to complete
                }
                stagingToken[s] = token;
                region = staging.memory().asSlice(staging.offset(token), size);
            }
            queue.pop();
            sizes[s] = size;
            data[s] = region;
            readDone[s] = false;
            retry[s] = false;
            pending[s] = null;
            if (!reloading[s]) {
                setState(s, AssetState.LOADING);
            }
            inFlight++;
            busy[s] = true;
            offer(ioQueue, s);
            wakeIo();
        }
    }

    /** A load finished (ready, failed, or a reload swapped on the main thread). */
    private void complete(int s) {
        inFlight--;
        busy[s] = false;
        if (stagingToken[s] >= 0) {
            staging.free(stagingToken[s]);
            stagingToken[s] = -1;
        }
        if (dedicated[s] != null) {
            dedicated[s].close();
            dedicated[s] = null;
        }
        data[s] = null;
        AssetType<?> type = types[typeOf[s]];
        boolean wasReload = reloading[s];
        reloading[s] = false;
        if (dropped[s]) {
            if (wasReload && pending[s] != null && !type.upload) {
                unloadOnOwner(type, pending[s]);
            }
            pending[s] = null;
            freeSlot(s);
            return;
        }
        if (retry[s] && retries[s] < MAX_RETRIES) {
            retries[s]++;
            retry[s] = false;
            reloading[s] = wasReload;
            if (!wasReload) {
                setState(s, AssetState.QUEUED);
            }
            queue.push(s, priority[s]); // the file changed size while loading: try again
            return;
        }
        Throwable error = errors[s];
        if (wasReload) {
            if (error != null) {
                LOG.error("reloading " + describe(s) + " failed; keeping the old version", error);
            } else {
                if (!type.upload) { // swap on the owner thread, which is the only reader
                    Object old = VALUE.getAcquire(values, s);
                    VALUE.setRelease(values, s, pending[s]);
                    pending[s] = null;
                    unloadOnOwner(type, old);
                }
                versions[s]++;
                int[] oldDeps = dependencies[s];
                acquireDependencies(s);
                releaseDependencies(oldDeps);
            }
        } else if (stateAt(s) == AssetState.READY) {
            acquireDependencies(s);
        } else if (error != null) {
            LOG.error("loading " + describe(s) + " failed", error);
        }
        settled[s] = true;
        if (reloadAgain[s]) {
            reloadAgain[s] = false;
            scheduleReload(s);
        }
    }

    private void acquireDependencies(int s) {
        long[] depIds = declaredIds[s];
        byte[] depTypes = declaredTypes[s];
        declaredIds[s] = null;
        declaredTypes[s] = null;
        if (depIds == null) {
            dependencies[s] = null;
            return;
        }
        int[] deps = new int[depIds.length];
        for (int i = 0; i < depIds.length; i++) {
            int t = depTypes[i];
            if (t < 0 || t >= typeCount) {
                throw new IllegalStateException("dependency of unknown type " + t);
            }
            deps[i] = load(types[t], depIds[i], priority[s]);
        }
        dependencies[s] = deps;
    }

    private void releaseDependencies(int[] deps) {
        if (deps == null) {
            return;
        }
        for (int h : deps) {
            int s = Handles.index(h);
            if (handles.isValid(h)) {
                release(types[typeOf[s]], h);
            }
        }
    }

    /** Unloads the value (if any) and frees the slot. The id mapping is already gone. */
    private void freeSlot(int s) {
        AssetType<?> type = types[typeOf[s]];
        int[] deps = dependencies[s];
        dependencies[s] = null;
        Object value = VALUE.getAcquire(values, s);
        VALUE.setRelease(values, s, null);
        setState(s, AssetState.UNLOADED);
        declaredIds[s] = null;
        declaredTypes[s] = null;
        errors[s] = null;
        if (value != null && type.upload) {
            unloadValues[s] = value;
            offer(unloadQueue, s); // the main thread unloads, then the slot is freed
        } else {
            if (value != null) {
                unloadOnOwner(type, value);
            }
            handles.free(handleOf[s]);
        }
        releaseDependencies(deps);
    }

    @SuppressWarnings("unchecked")
    private static <T> void unloadOnOwner(AssetType<T> type, Object value) {
        try {
            type.loader.unload((T) value);
        } catch (RuntimeException e) {
            LOG.error("unloading a " + type.name() + " failed", e);
        }
    }

    private void fail(int s, Throwable t) {
        errors[s] = t;
        if (reloading[s]) {
            reloading[s] = false;
            LOG.error("reloading " + describe(s) + " failed; keeping the old version", t);
            return;
        }
        setState(s, AssetState.FAILED);
        settled[s] = true;
        LOG.error("loading " + describe(s) + " failed", t);
    }

    private String describe(int s) {
        return types[typeOf[s]].name() + " " + vfs.describe(ids[s]);
    }

    // ----------------------------------------------------------------------------------------
    // IO threads

    private void wakeIo() {
        for (Thread t : ioThreads) {
            LockSupport.unpark(t);
        }
    }

    private void ioLoop() {
        ZstdDecompressor decompressor = new ZstdDecompressor();
        try {
            while (running) {
                int s = ioQueue.poll();
                if (s == MpmcIntQueue.EMPTY) {
                    // Untimed: schedule() unparks after queueing. A timed poll would unmount and
                    // remount this virtual thread 20 times a second, allocating a stack chunk
                    // each time even while nothing loads (D65).
                    LockSupport.park(this);
                    continue;
                }
                try {
                    long id = ids[s];
                    if (vfs.isLoose(id)) {
                        long n = vfs.read(id, data[s], 0, sizes[s], decompressor);
                        if (n != sizes[s]) {
                            throw new FileChangedException(
                                    vfs.describe(id) + " changed size while loading");
                        }
                        readDone[s] = true;
                    } else {
                        vfs.prefetch(id);
                    }
                    offer(decodeQueue, s);
                    jobs.submit(decodeKernel, 1, 1);
                } catch (Throwable t) {
                    pipelineFailed(s, t);
                }
            }
        } finally {
            decompressor.close();
        }
    }

    // ----------------------------------------------------------------------------------------
    // Workers

    private void decodeChunk(int start, int end, int threadIndex) {
        for (int i = start; i < end; i++) {
            int s = decodeQueue.poll();
            if (s != MpmcIntQueue.EMPTY) {
                decode(s, threadIndex);
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void decode(int s, int threadIndex) {
        AssetType type = types[typeOf[s]];
        DecodeContext ctx = contexts.get(threadIndex);
        try {
            long id = ids[s];
            MemorySegment bytes = data[s];
            if (!readDone[s]) {
                long n = vfs.read(id, bytes, 0, sizes[s], decompressors.get(threadIndex));
                if (n != sizes[s]) {
                    throw new FileChangedException(
                            vfs.describe(id) + " changed size while loading");
                }
            }
            ctx.reset(vfs, id);
            Object value = type.loader.decode(id, bytes, ctx);
            if (value == null) {
                throw new IllegalStateException(type.name() + " loader returned null");
            }
            declaredIds[s] = ctx.takeIds();
            declaredTypes[s] = ctx.takeTypes();
            if (type.upload) {
                pending[s] = value;
                if (!reloading[s]) {
                    setState(s, AssetState.UPLOADING);
                }
                offer(uploadQueue, s);
            } else if (reloading[s]) {
                pending[s] = value;
                offer(completed, s);
            } else {
                VALUE.setRelease(values, s, value);
                setState(s, AssetState.READY);
                offer(completed, s);
            }
        } catch (Throwable t) {
            pipelineFailed(s, t);
        }
    }

    /** Records a failure from a pipeline thread and hands the slot back to the owner. */
    private void pipelineFailed(int s, Throwable t) {
        errors[s] = t;
        retry[s] = t instanceof FileChangedException;
        if (!reloading[s] && !retry[s]) {
            setState(s, AssetState.FAILED);
        }
        offer(completed, s);
    }

    // ----------------------------------------------------------------------------------------
    // Main thread

    /**
     * Runs pending unloads, then uploads until {@code budgetNanos} have passed (at least one upload
     * per call, so progress never stalls). Call once per frame on the main thread. Allocates
     * nothing itself when the queues are empty.
     *
     * @return the number of assets uploaded
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public int pumpUploads(long budgetNanos) {
        int s;
        while ((s = unloadQueue.poll()) != MpmcIntQueue.EMPTY) {
            AssetType type = types[typeOf[s]];
            try {
                type.loader.unload(unloadValues[s]);
            } catch (RuntimeException e) {
                LOG.error("unloading a " + type.name() + " failed", e);
            }
            unloadValues[s] = null;
            offer(unloaded, s);
        }
        long start = System.nanoTime();
        int uploaded = 0;
        while ((s = uploadQueue.poll()) != MpmcIntQueue.EMPTY) {
            AssetType type = types[typeOf[s]];
            try {
                Object value = type.loader.upload(pending[s], data[s]);
                if (reloading[s]) {
                    Object old = VALUE.getAcquire(values, s);
                    VALUE.setRelease(values, s, value);
                    try {
                        type.loader.unload(old);
                    } catch (RuntimeException e) {
                        LOG.error("unloading the old " + type.name() + " failed", e);
                    }
                } else {
                    VALUE.setRelease(values, s, value);
                    setState(s, AssetState.READY);
                }
            } catch (Throwable t) {
                errors[s] = t;
                if (!reloading[s]) {
                    setState(s, AssetState.FAILED);
                }
            }
            pending[s] = null;
            offer(completed, s);
            uploaded++;
            if (System.nanoTime() - start >= budgetNanos) {
                break;
            }
        }
        return uploaded;
    }

    /**
     * Waits for loads in flight (running uploads on this thread), unloads every asset and stops the
     * IO threads. Call on the main thread after the owner thread has stopped.
     */
    @Override
    public void close() {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!queue.isEmpty()) {
            int s = queue.pop();
            if (!reloading[s]) {
                setState(s, AssetState.UNLOADED);
            }
            reloading[s] = false;
        }
        while (inFlight > 0 && System.nanoTime() < deadline) {
            pumpUploads(Long.MAX_VALUE);
            int s;
            while ((s = completed.poll()) != MpmcIntQueue.EMPTY) {
                dropped[s] = true;
                complete(s);
            }
            Thread.onSpinWait();
        }
        if (inFlight > 0) {
            LOG.error(inFlight + " asset loads did not finish within 10 s");
        }
        for (int s = 0; s < handleOf.length; s++) {
            if (handles.isAlive(s) && stateAt(s) != AssetState.UNLOADED) {
                AssetType<?> type = types[typeOf[s]];
                if (type.byId.get(ids[s]) == handleOf[s]) {
                    type.byId.remove(ids[s]);
                }
                freeSlot(s);
            }
        }
        pumpUploads(0);
        running = false;
        wakeIo();
        for (Thread t : ioThreads) {
            try {
                t.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (inFlight == 0) {
            staging.close();
            for (int i = 0; i < decompressors.size(); i++) {
                decompressors.get(i).close();
            }
        }
    }

    // ----------------------------------------------------------------------------------------

    private int stateAt(int s) {
        return (byte) STATE.getAcquire(state, s);
    }

    private void setState(int s, int st) {
        STATE.setRelease(state, s, (byte) st);
    }

    private static void offer(MpmcIntQueue q, int s) {
        while (!q.offer(s)) {
            Thread.onSpinWait(); // capacities exceed what can be in flight; never spins in practice
        }
    }

    private static int pow2(int n) {
        return Math.max(2, Integer.highestOneBit(Math.max(1, n - 1)) << 1);
    }

    /** Returns the asset types registered so far (diagnostics). */
    public List<AssetType<?>> types() {
        List<AssetType<?>> list = new ArrayList<>(typeCount);
        for (int i = 0; i < typeCount; i++) {
            list.add(types[i]);
        }
        return list;
    }
}
