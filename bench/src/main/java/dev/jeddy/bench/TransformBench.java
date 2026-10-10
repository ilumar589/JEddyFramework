package dev.jeddy.bench;

import dev.jeddy.core.handle.Handles;
import dev.jeddy.core.job.JobSystem;
import dev.jeddy.scene.EntityPool;
import dev.jeddy.scene.TransformStore;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * The transform hierarchy update for 100k transforms (TASKS M5-02): a CRPG-like forest (props and
 * characters as roots, attachments one or two levels down), with every transform moving or 10 % of
 * the roots moving, on one thread or on the job system.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(1)
public class TransformBench {
    private static final int COUNT = 100_000;

    @Param({"false", "true"})
    private boolean parallel;

    private JobSystem jobs;
    private TransformStore transforms;
    private int[] roots;
    private int rootCount;
    private int[] all;
    private float t;

    /** Builds the forest: 40 % roots, 40 % children, 20 % grandchildren. */
    @Setup(Level.Trial)
    public void setup() {
        jobs = new JobSystem(JobSystem.defaultWorkerCount());
        EntityPool pool = new EntityPool();
        transforms = new TransformStore(pool, COUNT);
        Random r = new Random(3);
        all = new int[COUNT];
        roots = new int[COUNT];
        int[] children = new int[COUNT];
        int childCount = 0;
        for (int i = 0; i < COUNT; i++) {
            int e = pool.create();
            all[i] = e;
            int kind = i % 5;
            int parent =
                    kind < 2 || rootCount == 0
                            ? Handles.NULL
                            : kind < 4 || childCount == 0
                                    ? roots[r.nextInt(rootCount)]
                                    : children[r.nextInt(childCount)];
            transforms.add(e, parent);
            transforms.setPosition(e, r.nextFloat() * 100, 0, r.nextFloat() * 100);
            if (parent == Handles.NULL) {
                roots[rootCount++] = e;
            } else if (kind < 4) {
                children[childCount++] = e;
            }
        }
        transforms.update(jobs);
    }

    /** Stops the workers. */
    @TearDown(Level.Trial)
    public void tearDown() {
        jobs.close();
    }

    /** Every transform changed (worst case, e.g. the first frame). */
    @Benchmark
    public int allDirty() {
        t += 0.01f;
        for (int e : all) {
            transforms.setPosition(e, t, 0, t);
        }
        return transforms.update(parallel ? jobs : null);
    }

    /** 10 % of the roots moved, with their attachments (a typical combat frame). */
    @Benchmark
    public int tenPercentOfRoots() {
        t += 0.01f;
        for (int i = 0; i < rootCount; i += 10) {
            transforms.setPosition(roots[i], t, 0, t);
        }
        return transforms.update(parallel ? jobs : null);
    }
}
