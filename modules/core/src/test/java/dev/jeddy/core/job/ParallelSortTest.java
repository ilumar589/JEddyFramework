package dev.jeddy.core.job;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.core.collections.LongSort;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.Test;

import java.util.Random;

/** The parallel radix sort matches the sequential one, stably (render sort keys, TASKS M5-08). */
class ParallelSortTest {
    @Test
    void matchesTheSequentialSortAndIsStable() {
        try (JobSystem jobs = new JobSystem(3)) {
            ParallelSort sorter = new ParallelSort(jobs);
            Random r = new Random(8);
            for (int n : new int[] {0, 1, 100, 20_000, 100_003}) {
                long[] keys = new long[n];
                int[] values = new int[n];
                for (int i = 0; i < n; i++) {
                    // Few distinct high bytes, random low bits, many duplicates, some negative.
                    keys[i] =
                            ((long) r.nextInt(4) << 60)
                                    ^ (r.nextInt(1000) * 0x10001L)
                                    ^ (r.nextBoolean() ? Long.MIN_VALUE : 0);
                    values[i] = i;
                }
                long[] refKeys = keys.clone();
                int[] refValues = values.clone();
                LongSort.radixSort(refKeys, refValues, n, new long[n], new int[n], new int[2048]);
                sorter.sort(keys, values, n);
                assertArrayEquals(refKeys, keys);
                assertArrayEquals(refValues, values, "stable: equal keys keep their order");
                for (int i = 1; i < n; i++) {
                    assertTrue(Long.compareUnsigned(keys[i - 1], keys[i]) <= 0);
                }
            }
            long[] keys = new long[50_000];
            int[] values = new int[50_000];
            Allocations.assertNoAllocation(
                    "parallel sort",
                    5,
                    20,
                    () -> {
                        for (int i = 0; i < keys.length; i++) {
                            keys[i] = (i * 0x9E3779B97F4A7C15L) >>> 20;
                            values[i] = i;
                        }
                        sorter.sort(keys, values, keys.length);
                    });
        }
    }
}
