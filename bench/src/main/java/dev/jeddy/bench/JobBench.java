package dev.jeddy.bench;

import dev.jeddy.core.job.JobKernel;
import dev.jeddy.core.job.JobSystem;
import java.util.concurrent.TimeUnit;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/** Job system dispatch and scaling (TASKS M1-07 AC: ≥ 6× on 8 cores). */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class JobBench {
    private static final int N = 1 << 20;

    private final float[] data = new float[N];
    private final double[] partial = new double[64];
    private JobSystem jobs;
    private final JobKernel kernel =
            (start, end, t) -> {
                double sum = 0;
                for (int i = start; i < end; i++) {
                    float v = data[i];
                    sum += Math.sqrt(v * v + 1.0);
                }
                partial[t] += sum;
            };
    private final JobKernel empty = (start, end, t) -> {};

    /** Starts the workers. */
    @Setup(Level.Trial)
    public void setup() {
        jobs = new JobSystem(JobSystem.defaultWorkerCount());
        for (int i = 0; i < N; i++) {
            data[i] = i * 0.001f;
        }
    }

    /** Stops the workers. */
    @TearDown(Level.Trial)
    public void tearDown() {
        jobs.close();
    }

    /** 1M elements on the calling thread only. */
    @Benchmark
    public double sequential1M() {
        kernel.run(0, N, 0);
        return partial[0];
    }

    /** 1M elements across all workers in 16k chunks. */
    @Benchmark
    public double parallel1M() {
        jobs.parallelFor(kernel, N, 16_384);
        return partial[0];
    }

    /** Overhead of dispatching an empty job of 64 chunks. */
    @Benchmark
    public void dispatchEmpty64() {
        jobs.parallelFor(empty, 64, 1);
    }
}
