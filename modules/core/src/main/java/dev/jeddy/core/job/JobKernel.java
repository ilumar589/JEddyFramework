package dev.jeddy.core.job;

/**
 * The work of a job: processes items {@code [start, end)} on the calling thread.
 *
 * <p>Kernels should be long-lived objects (often the system that owns the data), configured through
 * fields before submission, so submitting allocates nothing. Use {@code threadIndex} to pick
 * per-thread scratch data ({@code PerWorker}); it is unique among threads running at the same time
 * and lies in {@code [0, JobSystem.threadSlots())}.
 *
 * <p>Thread ownership: called concurrently from several threads for disjoint ranges.
 */
@FunctionalInterface
public interface JobKernel {
    /**
     * Processes items {@code [start, end)}.
     *
     * @param start first item, inclusive
     * @param end last item, exclusive
     * @param threadIndex slot of the running thread
     */
    void run(int start, int end, int threadIndex);
}
