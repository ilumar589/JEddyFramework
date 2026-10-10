package dev.jeddy.core.job;

import dev.jeddy.core.concurrent.MpmcIntQueue;
import dev.jeddy.core.concurrent.SpinParkWaiter;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.locks.LockSupport;

/**
 * Fixed pool of worker threads running data-parallel jobs (DESIGN §4.5).
 *
 * <ul>
 *   <li>{@link #parallelFor} splits {@code [0, count)} into chunks of {@code grain} items; workers
 *       claim chunks with an atomic counter and the calling thread helps until all are done.
 *   <li>{@link #submit} starts a job asynchronously, optionally after other jobs; {@link
 *       #wait(int)} helps run work until it completes. {@link JobGraph} builds reusable DAGs.
 *   <li>Jobs live in preallocated slots and handles are plain {@code int}s, so steady-state use
 *       allocates nothing.
 * </ul>
 *
 * Idle workers spin, then yield, then park; submitting work unparks them. Platform threads only:
 * never block a worker on I/O (use virtual threads for that).
 *
 * <p>Thread ownership: thread-safe. Any thread may submit and wait; at most {@link
 * #MAX_EXTERNAL_THREADS} non-worker threads may use one system.
 */
public final class JobSystem implements AutoCloseable {
    /** Handle meaning "no job"; {@link #isDone(int)} is always {@code true} for it. */
    public static final int NO_JOB = 0;

    /** Non-worker threads (main, game, tests) that can submit and wait. */
    public static final int MAX_EXTERNAL_THREADS = 8;

    private static final int SLOT_BITS = 10;
    private static final int MAX_JOBS = 1 << SLOT_BITS;
    private static final int SLOT_MASK = MAX_JOBS - 1;
    private static final int MAX_GENERATION = (1 << (31 - SLOT_BITS)) - 1;
    private static final int MAX_DEPENDENTS = 32;
    private static final int EXHAUSTED = Integer.MAX_VALUE;

    private static final VarHandle STATE; // long: generation << 32 | next chunk
    private static final VarHandle REMAINING;
    private static final VarHandle PENDING;
    private static final VarHandle FAILURE;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            STATE = l.findVarHandle(Job.class, "state", long.class);
            REMAINING = l.findVarHandle(Job.class, "remaining", int.class);
            PENDING = l.findVarHandle(Job.class, "pendingDeps", int.class);
            FAILURE = l.findVarHandle(Job.class, "failure", Throwable.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final class Job {
        volatile long state;
        volatile int remaining;
        volatile int pendingDeps;
        volatile Throwable failure;
        volatile Throwable upstreamFailure;
        volatile int completedGeneration;
        volatile Thread waiter;
        int generation;
        JobKernel kernel;
        int count;
        int grain;
        int chunkCount;
        // Guarded by synchronized (this):
        final int[] dependents = new int[MAX_DEPENDENTS];
        int dependentCount;
        boolean completed = true;

        Job() {
            state = EXHAUSTED; // generation 0, nothing claimable
        }
    }

    private final class Worker extends Thread {
        final int index;

        Worker(int index) {
            super("jeddy-worker-" + index);
            this.index = index;
            setDaemon(true);
        }

        JobSystem system() {
            return JobSystem.this;
        }

        @Override
        public void run() {
            workerLoop(index);
        }
    }

    private final Job[] jobs = new Job[MAX_JOBS];
    private final MpmcIntQueue freeSlots = new MpmcIntQueue(MAX_JOBS);
    private final MpmcIntQueue ready;
    private final Worker[] workers;
    private final AtomicIntegerArray sleeping;
    private final SpinParkWaiter waiter = new SpinParkWaiter(256, 32, 50_000);
    private final AtomicInteger nextExternal;
    private final ThreadLocal<int[]> externalIndex = new ThreadLocal<>();
    private volatile boolean running = true;

    /**
     * Starts a job system.
     *
     * @param workerCount number of worker threads; 0 runs everything on the waiting threads
     */
    public JobSystem(int workerCount) {
        if (workerCount < 0 || workerCount > 256) {
            throw new IllegalArgumentException("workerCount out of range: " + workerCount);
        }
        for (int i = 0; i < MAX_JOBS; i++) {
            jobs[i] = new Job();
            freeSlots.offer(i);
        }
        ready = new MpmcIntQueue(Integer.highestOneBit(MAX_JOBS * Math.max(1, workerCount)) << 1);
        nextExternal = new AtomicInteger(workerCount);
        sleeping = new AtomicIntegerArray(Math.max(1, workerCount));
        workers = new Worker[workerCount];
        for (int i = 0; i < workerCount; i++) {
            workers[i] = new Worker(i);
        }
        for (Worker w : workers) {
            w.start();
        }
    }

    /** Returns the default worker count: available processors minus 2 (main and game thread). */
    public static int defaultWorkerCount() {
        return Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
    }

    /** Returns the number of worker threads. */
    public int workerCount() {
        return workers.length;
    }

    /**
     * Returns the number of distinct thread indices: workers plus external threads. Size
     * per-thread data ({@code PerWorker}) with this.
     */
    public int threadSlots() {
        return workers.length + MAX_EXTERNAL_THREADS;
    }

    /** Returns the calling thread's index, registering it as an external thread on first use. */
    public int threadIndex() {
        if (Thread.currentThread() instanceof Worker w && w.system() == this) {
            return w.index;
        }
        int[] holder = externalIndex.get();
        if (holder == null) {
            int index = nextExternal.getAndIncrement();
            if (index >= threadSlots()) {
                throw new IllegalStateException(
                        "more than " + MAX_EXTERNAL_THREADS + " external threads use this JobSystem");
            }
            holder = new int[] {index};
            externalIndex.set(holder);
        }
        return holder[0];
    }

    /**
     * Runs {@code kernel} over {@code [0, count)} in chunks of {@code grain} and returns when all
     * are done. The calling thread runs chunks too. Rethrows a kernel failure as {@link
     * JobFailedException}.
     */
    public void parallelFor(JobKernel kernel, int count, int grain) {
        if (count <= 0) {
            return;
        }
        if (count <= grain || workers.length == 0) {
            int t = threadIndex();
            int g = Math.max(1, grain);
            try {
                for (int start = 0; start < count; start += g) {
                    kernel.run(start, Math.min(count, start + g), t);
                }
            } catch (RuntimeException | Error e) {
                throw new JobFailedException(e);
            }
            return;
        }
        wait(submit(kernel, count, grain));
    }

    /** Starts {@code kernel} over {@code [0, count)} asynchronously. Returns its handle. */
    public int submit(JobKernel kernel, int count, int grain) {
        return submitInternal(kernel, count, grain, null, 0, NO_JOB, NO_JOB);
    }

    /** Starts {@code kernel} once job {@code dependency} has completed. */
    public int submit(JobKernel kernel, int count, int grain, int dependency) {
        return submitInternal(kernel, count, grain, null, 0, dependency, NO_JOB);
    }

    /** Starts {@code kernel} once jobs {@code dep1} and {@code dep2} have completed. */
    public int submit(JobKernel kernel, int count, int grain, int dep1, int dep2) {
        return submitInternal(kernel, count, grain, null, 0, dep1, dep2);
    }

    /** Starts {@code kernel} once the first {@code depCount} jobs in {@code deps} completed. */
    public int submit(JobKernel kernel, int count, int grain, int[] deps, int depCount) {
        return submitInternal(kernel, count, grain, deps, depCount, NO_JOB, NO_JOB);
    }

    /** Returns {@code true} once the job has completed (or {@code handle} is {@link #NO_JOB}). */
    public boolean isDone(int handle) {
        if (handle == NO_JOB) {
            return true;
        }
        Job j = jobs[handle & SLOT_MASK];
        int gen = handle >>> SLOT_BITS;
        return j.completedGeneration == gen || (int) (j.state >>> 32) != gen;
    }

    /**
     * Waits for the job, running its chunks and other ready jobs meanwhile. Rethrows a kernel
     * failure as {@link JobFailedException}.
     */
    public void wait(int handle) {
        if (handle == NO_JOB) {
            return;
        }
        Job j = jobs[handle & SLOT_MASK];
        int gen = handle >>> SLOT_BITS;
        int t = threadIndex();
        int idle = 0;
        while (!isDone(handle)) {
            if (runChunks(handle, t)) {
                idle = 0;
                continue;
            }
            int other = ready.poll();
            if (other != MpmcIntQueue.EMPTY) {
                runChunks(other, t);
                idle = 0;
                continue;
            }
            if (waiter.isParking(idle)) {
                j.waiter = Thread.currentThread();
                if (!isDone(handle)) {
                    LockSupport.parkNanos(this, 200_000);
                }
                j.waiter = null;
                idle = 0;
            } else {
                idle = waiter.idle(idle);
            }
        }
        Throwable f = j.completedGeneration == gen ? j.failure : null;
        if (f != null) {
            throw new JobFailedException(f);
        }
    }

    /** Stops the workers. Pending jobs are abandoned. */
    @Override
    public void close() {
        running = false;
        for (Worker w : workers) {
            LockSupport.unpark(w);
        }
        for (Worker w : workers) {
            try {
                w.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ---------------------------------------------------------------------------------------

    private int submitInternal(
            JobKernel kernel, int count, int grain, int[] deps, int depCount, int dep1, int dep2) {
        if (!running) {
            throw new IllegalStateException("JobSystem is closed");
        }
        if (count <= 0) {
            return NO_JOB;
        }
        int g = Math.max(1, grain);
        int slot = allocateSlot();
        Job j = jobs[slot];
        int gen = j.generation == MAX_GENERATION ? 1 : j.generation + 1;
        j.generation = gen;
        STATE.setVolatile(j, ((long) gen << 32) | EXHAUSTED); // not claimable until ready
        j.kernel = kernel;
        j.count = count;
        j.grain = g;
        j.chunkCount = (int) ((count + (long) g - 1) / g);
        j.failure = null;
        j.upstreamFailure = null;
        synchronized (j) {
            j.dependentCount = 0;
            j.completed = false;
        }
        REMAINING.setVolatile(j, j.chunkCount);
        PENDING.setVolatile(j, 1); // our own hold while registering dependencies
        int handle = (gen << SLOT_BITS) | slot;
        addDependency(dep1, j, handle);
        addDependency(dep2, j, handle);
        for (int i = 0; i < depCount; i++) {
            addDependency(deps[i], j, handle);
        }
        if ((int) PENDING.getAndAdd(j, -1) == 1) {
            makeReady(j, handle);
        }
        return handle;
    }

    private void addDependency(int depHandle, Job dependent, int dependentHandle) {
        if (depHandle == NO_JOB) {
            return;
        }
        Job d = jobs[depHandle & SLOT_MASK];
        int gen = depHandle >>> SLOT_BITS;
        synchronized (d) {
            if (d.generation != gen || d.completed) {
                if (d.completedGeneration == gen && d.failure != null) {
                    dependent.upstreamFailure = d.failure;
                }
                return; // already complete
            }
            if (d.dependentCount == MAX_DEPENDENTS) {
                throw new IllegalStateException("more than " + MAX_DEPENDENTS + " jobs depend on one job");
            }
            d.dependents[d.dependentCount++] = dependentHandle;
            PENDING.getAndAdd(dependent, 1);
        }
    }

    private void makeReady(Job j, int handle) {
        int gen = handle >>> SLOT_BITS;
        if (j.upstreamFailure != null) {
            j.failure = j.upstreamFailure;
            complete(j, handle);
            return;
        }
        STATE.setVolatile(j, (long) gen << 32); // chunk 0 is now claimable
        int entries = Math.max(1, Math.min(j.chunkCount, workers.length));
        for (int i = 0; i < entries; i++) {
            while (!ready.offer(handle)) {
                helpOnce();
            }
        }
        wakeWorkers(entries);
    }

    /** Claims and runs chunks of one job until none are left. Returns whether it ran any. */
    private boolean runChunks(int handle, int threadIndex) {
        Job j = jobs[handle & SLOT_MASK];
        int gen = handle >>> SLOT_BITS;
        boolean ran = false;
        while (true) {
            long s = j.state;
            if ((int) (s >>> 32) != gen) {
                return ran;
            }
            int chunk = (int) s;
            if (chunk >= j.chunkCount) {
                return ran;
            }
            if (!STATE.compareAndSet(j, s, s + 1)) {
                continue;
            }
            ran = true;
            int start = chunk * j.grain;
            int end = (int) Math.min(j.count, (long) start + j.grain);
            try {
                j.kernel.run(start, end, threadIndex);
            } catch (Throwable t) {
                FAILURE.compareAndSet(j, null, t);
            }
            if ((int) REMAINING.getAndAdd(j, -1) == 1) {
                complete(j, handle);
                return true;
            }
        }
    }

    private void complete(Job j, int handle) {
        int gen = handle >>> SLOT_BITS;
        int n;
        synchronized (j) {
            j.completed = true;
            n = j.dependentCount;
        }
        Throwable failure = j.failure;
        for (int i = 0; i < n; i++) {
            int depHandle = j.dependents[i];
            Job d = jobs[depHandle & SLOT_MASK];
            if (failure != null) {
                d.upstreamFailure = failure;
            }
            if ((int) PENDING.getAndAdd(d, -1) == 1) {
                makeReady(d, depHandle);
            }
        }
        j.kernel = null;
        j.completedGeneration = gen;
        Thread w = j.waiter;
        if (w != null) {
            LockSupport.unpark(w);
        }
        freeSlots.offer(handle & SLOT_MASK);
    }

    private int allocateSlot() {
        int slot;
        while ((slot = freeSlots.poll()) == MpmcIntQueue.EMPTY) {
            if (!helpOnce()) {
                Thread.onSpinWait();
            }
        }
        return slot;
    }

    private boolean helpOnce() {
        int h = ready.poll();
        return h != MpmcIntQueue.EMPTY && runChunks(h, threadIndex());
    }

    private void wakeWorkers(int count) {
        int woken = 0;
        for (int i = 0; i < workers.length && woken < count; i++) {
            if (sleeping.get(i) == 1) {
                LockSupport.unpark(workers[i]);
                woken++;
            }
        }
    }

    private void workerLoop(int index) {
        int idle = 0;
        while (running) {
            int h = ready.poll();
            if (h != MpmcIntQueue.EMPTY) {
                runChunks(h, index);
                idle = 0;
                continue;
            }
            if (waiter.isParking(idle)) {
                sleeping.set(index, 1);
                if (ready.isEmpty() && running) {
                    LockSupport.parkNanos(this, 2_000_000);
                }
                sleeping.set(index, 0);
                idle = 0;
            } else {
                idle = waiter.idle(idle);
            }
        }
    }
}
