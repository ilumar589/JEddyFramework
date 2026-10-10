package dev.jeddy.bench;

import dev.jeddy.core.collections.IntIntMap;
import dev.jeddy.core.collections.LongSort;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/** Primitive collections against their boxed JDK counterparts (TASKS M1-03, M1-15). */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class CollectionsBench {
    private static final int KEYS = 4096;

    private final IntIntMap primitive = new IntIntMap(KEYS, -1);
    private final HashMap<Integer, Integer> boxed = new HashMap<>();
    private final int[] lookups = new int[KEYS];
    private final long[] sortKeys = new long[16_384];
    private final long[] work = new long[16_384];
    private final int[] values = new int[16_384];
    private final long[] scratchKeys = new long[16_384];
    private final int[] scratchValues = new int[16_384];
    private final int[] histogram = new int[2048];

    /** Fills the maps and key arrays. */
    @Setup(Level.Trial)
    public void setup() {
        Random r = new Random(1);
        for (int i = 0; i < KEYS; i++) {
            int k = r.nextInt();
            primitive.put(k, i);
            boxed.put(k, i);
            lookups[i] = k;
        }
        for (int i = 0; i < sortKeys.length; i++) {
            sortKeys[i] = r.nextLong() >>> 24; // 40-bit keys, like render sort keys
        }
    }

    /** 4096 lookups in {@code IntIntMap}. */
    @Benchmark
    public long intIntMapGet() {
        long sum = 0;
        for (int k : lookups) {
            sum += primitive.get(k);
        }
        return sum;
    }

    /** 4096 lookups in {@code HashMap<Integer, Integer>}. */
    @Benchmark
    public long hashMapGet() {
        long sum = 0;
        for (int k : lookups) {
            sum += boxed.get(k);
        }
        return sum;
    }

    /** Radix sort of 16k 40-bit keys with payload. */
    @Benchmark
    public long radixSort16k() {
        System.arraycopy(sortKeys, 0, work, 0, work.length);
        LongSort.radixSort(work, values, work.length, scratchKeys, scratchValues, histogram);
        return work[0];
    }

    /** {@code Arrays.sort} of the same keys (no payload). */
    @Benchmark
    public long arraysSort16k() {
        System.arraycopy(sortKeys, 0, work, 0, work.length);
        Arrays.sort(work);
        return work[0];
    }
}
