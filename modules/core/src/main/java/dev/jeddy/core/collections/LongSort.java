package dev.jeddy.core.collections;

/**
 * Least-significant-digit radix sort of 64-bit keys with an {@code int} payload, used for render
 * sort keys (DESIGN §9.2). Stable, O(n), allocation-free: the caller provides the scratch arrays.
 *
 * <p>Keys are compared as <em>unsigned</em> 64-bit values. Digit passes whose byte is identical
 * for every key are skipped, so short keys sort in fewer passes.
 *
 * <p>Thread ownership: stateless, callable from any thread on distinct arrays.
 */
public final class LongSort {
    private LongSort() {}

    /**
     * Sorts {@code keys[0..count)} ascending (unsigned) and applies the same permutation to {@code
     * values}.
     *
     * @param keys keys to sort, sorted in place
     * @param values payload permuted alongside the keys
     * @param count number of elements
     * @param scratchKeys scratch array of at least {@code count} elements
     * @param scratchValues scratch array of at least {@code count} elements
     * @param histogram scratch array of at least 2048 elements (8 passes of 256 buckets)
     */
    public static void radixSort(
            long[] keys,
            int[] values,
            int count,
            long[] scratchKeys,
            int[] scratchValues,
            int[] histogram) {
        if (count < 2) {
            return;
        }
        if (scratchKeys.length < count || scratchValues.length < count || histogram.length < 2048) {
            throw new IllegalArgumentException("scratch arrays too small");
        }
        java.util.Arrays.fill(histogram, 0, 2048, 0);
        for (int i = 0; i < count; i++) {
            long k = keys[i];
            for (int pass = 0; pass < 8; pass++) {
                histogram[(pass << 8) | (int) ((k >>> (pass << 3)) & 0xFF)]++;
            }
        }
        long[] srcK = keys;
        int[] srcV = values;
        long[] dstK = scratchKeys;
        int[] dstV = scratchValues;
        for (int pass = 0; pass < 8; pass++) {
            int base = pass << 8;
            if (histogram[base | (int) ((keys[0] >>> (pass << 3)) & 0xFF)] == count) {
                continue; // every key has the same byte here
            }
            int sum = 0;
            for (int b = 0; b < 256; b++) {
                int c = histogram[base | b];
                histogram[base | b] = sum;
                sum += c;
            }
            int shift = pass << 3;
            for (int i = 0; i < count; i++) {
                long k = srcK[i];
                int slot = histogram[base | (int) ((k >>> shift) & 0xFF)]++;
                dstK[slot] = k;
                dstV[slot] = srcV[i];
            }
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
    }
}
