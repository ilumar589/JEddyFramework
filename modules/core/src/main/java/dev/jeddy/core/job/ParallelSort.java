package dev.jeddy.core.job;

import dev.jeddy.core.collections.LongSort;

import java.util.Arrays;

/**
 * Parallel least-significant-digit radix sort of 64-bit keys with an {@code int} payload (render
 * sort keys, DESIGN §9.2): per pass, every chunk builds a histogram in parallel, one thread turns
 * them into scatter offsets, and the chunks scatter in parallel. Stable, unsigned order, and the
 * same result as {@link LongSort#radixSort}; passes whose byte is equal in every key are skipped.
 * Small inputs go to the sequential sort.
 *
 * <p>Allocates nothing after construction for counts up to the capacity it has grown to.
 *
 * <p>Thread ownership: one thread calls {@link #sort} at a time; it borrows the job system's
 * workers.
 */
public final class ParallelSort {
    private static final int SEQUENTIAL_BELOW = 16_384;
    private static final int CHUNKS = 32;

    private final JobSystem jobs;
    private long[] scratchKeys = new long[0];
    private int[] scratchValues = new int[0];
    private final int[] histogram = new int[2048];
    private final int[] chunkHistograms = new int[CHUNKS * 256];

    // Kernel state for the current pass.
    private long[] srcK;
    private int[] srcV;
    private long[] dstK;
    private int[] dstV;
    private int count;
    private int chunkSize;
    private int shift;

    private final JobKernel histogramKernel = this::histogramChunk;
    private final JobKernel scatterKernel = this::scatterChunk;

    /** Creates a sorter on {@code jobs}. */
    public ParallelSort(JobSystem jobs) {
        this.jobs = jobs;
    }

    /** Sorts {@code keys[0..count)} ascending (unsigned) and permutes {@code values} alike. */
    public void sort(long[] keys, int[] values, int count) {
        if (count < 2) {
            return;
        }
        if (scratchKeys.length < count) {
            scratchKeys = new long[Math.max(count, scratchKeys.length * 2)];
            scratchValues = new int[scratchKeys.length];
        }
        if (count < SEQUENTIAL_BELOW || jobs.workerCount() == 0) {
            LongSort.radixSort(keys, values, count, scratchKeys, scratchValues, histogram);
            return;
        }
        this.count = count;
        this.chunkSize = (count + CHUNKS - 1) / CHUNKS;
        // Which passes vary? One sequential sweep over the keys (cheap next to the scatters).
        long first = keys[0];
        long varying = 0;
        for (int i = 1; i < count; i++) {
            varying |= keys[i] ^ first;
        }
        srcK = keys;
        srcV = values;
        dstK = scratchKeys;
        dstV = scratchValues;
        for (int pass = 0; pass < 8; pass++) {
            shift = pass << 3;
            if (((varying >>> shift) & 0xFF) == 0) {
                continue;
            }
            Arrays.fill(chunkHistograms, 0);
            jobs.parallelFor(histogramKernel, CHUNKS, 1);
            // Offsets: bucket-major, chunk-minor, so equal keys keep their order (stable).
            int sum = 0;
            for (int b = 0; b < 256; b++) {
                for (int c = 0; c < CHUNKS; c++) {
                    int n = chunkHistograms[c * 256 + b];
                    chunkHistograms[c * 256 + b] = sum;
                    sum += n;
                }
            }
            jobs.parallelFor(scatterKernel, CHUNKS, 1);
            long[] tk = srcK;
            srcK = dstK;
            dstK = tk;
            int[] tv = srcV;
            srcV = dstV;
            dstV = tv;
        }
        if (srcK != keys) {
            System.arraycopy(srcK, 0, keys, 0, count);
            System.arraycopy(srcV, 0, values, 0, count);
        }
        srcK = null;
        srcV = null;
        dstK = null;
        dstV = null;
    }

    private void histogramChunk(int start, int end, int thread) {
        long[] k = srcK;
        int s = shift;
        for (int c = start; c < end; c++) {
            int from = c * chunkSize;
            int to = Math.min(count, from + chunkSize);
            int base = c * 256;
            for (int i = from; i < to; i++) {
                chunkHistograms[base + (int) ((k[i] >>> s) & 0xFF)]++;
            }
        }
    }

    private void scatterChunk(int start, int end, int thread) {
        long[] k = srcK;
        int[] v = srcV;
        long[] dk = dstK;
        int[] dv = dstV;
        int s = shift;
        for (int c = start; c < end; c++) {
            int from = c * chunkSize;
            int to = Math.min(count, from + chunkSize);
            int base = c * 256;
            for (int i = from; i < to; i++) {
                long key = k[i];
                int slot = chunkHistograms[base + (int) ((key >>> s) & 0xFF)]++;
                dk[slot] = key;
                dv[slot] = v[i];
            }
        }
    }
}
