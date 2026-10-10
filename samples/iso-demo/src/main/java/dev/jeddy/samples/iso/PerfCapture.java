package dev.jeddy.samples.iso;

import dev.jeddy.core.json.Json;
import dev.jeddy.render.graph.FrameGraph;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Automated measurement of the perf scene (TASKS M10-05): per-frame game-thread and main-thread CPU
 * times, GPU time per frame-graph pass, allocated bytes of the game, main and job worker threads,
 * and GC activity over the measured window, checked against the DESIGN §14 budgets and written as
 * {@code perf.json} and {@code perf.md}. Steady-state allocation is judged on the second half of
 * the window: the first absorbs one-time effects that the JIT does on its own schedule
 * (deoptimization, D66). The allocation counters are read on every recorded frame, on the frame's
 * regular path, so the measurement adds no rarely-taken branch that would itself deoptimize. An
 * allocating frame in the second half that coincides with a C2 compilation in the {@link JitLog} is
 * reported as JIT warm-up rather than failed (D70).
 *
 * <p>Thread ownership: the game thread records its frames and opens and closes the window; the main
 * thread records its frames (the {@code measuring} flag travels with each frame's HUD slot, so both
 * sides agree on which frames count); {@link #write} runs after both threads stopped.
 */
final class PerfCapture {
    static final double GAME_BUDGET_MS = 8;
    static final double MAIN_BUDGET_MS = 5;
    static final double GPU_BUDGET_MS = 12;
    static final double GC_PAUSE_BUDGET_MS = 1;
    static final double LOAD_BUDGET_MS = 3000;

    private final int capacity;
    private final boolean headless;

    // Game thread.
    private final long[] gameNanos;
    private int gameFrames;
    private final long[] gameAllocated;
    private final long[] workerAllocated;
    private final long[] gameStamp;
    private long[] workerIds = new long[0];
    private long gcCountStart;
    private long gcMillisStart;
    private long gcPauseCountStart;
    private long gcPauseMillisStart;
    private long gcCountEnd;
    private long gcMillisEnd;
    private long gcPauseCountEnd;
    private long gcPauseMillisEnd;
    private long startNanos;
    private long endNanos;
    private long uptimeOriginNanos;

    /** The run's {@link JitLog}, or {@code null} to judge allocation strictly. */
    Path jitLog;

    long loadNanos;
    int agents;
    int instances;
    int lights;
    int casts;

    /** Extra Markdown appended to the report (the allocation probe's table). */
    String notes = "";

    /**
     * The allocation probe's second-half allocations by frame and system ({@code --alloc-probe}).
     */
    String probeEvents = "";

    private long instanceSum;
    private int instanceFrames;

    // Main thread.
    private final long[] mainAllocated;
    private final long[] mainStamp;
    private int mainAllocFrames;
    private final long[] mainNanos;
    private final float[] gpuMillis;
    private int mainFrames;
    private volatile long mainThreadId;
    private double[] passSum = new double[0];
    private String[] passNames = new String[0];

    PerfCapture(int capacity, boolean headless) {
        this.capacity = capacity;
        this.headless = headless;
        gameNanos = new long[capacity];
        gameAllocated = new long[capacity];
        workerAllocated = new long[capacity];
        gameStamp = new long[capacity];
        mainAllocated = new long[capacity];
        mainStamp = new long[capacity];
        mainNanos = new long[capacity];
        gpuMillis = new float[capacity];
    }

    /** Main thread, once: remembers which thread renders. */
    void setMainThread(long id) {
        mainThreadId = id;
    }

    /** Game thread: starts the measured window (cold; allocates). */
    void begin() {
        List<Long> workers = new ArrayList<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().startsWith("jeddy-worker")) {
                workers.add(t.threadId());
            }
        }
        workerIds = new long[workers.size()];
        for (int i = 0; i < workerIds.length; i++) {
            workerIds[i] = workers.get(i);
        }
        long[] gc = gc();
        gcCountStart = gc[0];
        gcMillisStart = gc[1];
        gcPauseCountStart = gc[2];
        gcPauseMillisStart = gc[3];
        workerSum(); // links the counter calls before the first recorded frame
        currentThreadAllocated();
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
        startNanos = System.nanoTime();
        uptimeOriginNanos = startNanos - uptime * 1_000_000L;
    }

    /** Bytes allocated so far by every job worker (one id at a time: no garbage). */
    private long workerSum() {
        long sum = 0;
        for (long id : workerIds) {
            sum += THREADS == null ? 0 : Math.max(0, THREADS.getThreadAllocatedBytes(id));
        }
        return sum;
    }

    /**
     * Game thread: records one game frame's CPU time, instance and light counts, and the game and
     * worker allocation counters. Allocates nothing.
     */
    void recordGame(long nanos, int frameInstances, int frameLights) {
        if (gameFrames < capacity) {
            gameNanos[gameFrames] = nanos;
            gameAllocated[gameFrames] = currentThreadAllocated();
            workerAllocated[gameFrames] = workerSum();
            gameStamp[gameFrames] = System.nanoTime();
            gameFrames++;
        }
        instanceSum += frameInstances;
        instanceFrames++;
        lights = Math.max(lights, frameLights);
    }

    /** Game thread: closes the measured window (cold; allocates). */
    void end() {
        endNanos = System.nanoTime();
        long[] gc = gc();
        gcCountEnd = gc[0];
        gcMillisEnd = gc[1];
        gcPauseCountEnd = gc[2];
        gcPauseMillisEnd = gc[3];
    }

    /** Main thread: records its allocation counter for a measured frame (headless runs too). */
    void recordMainAllocation() {
        if (mainAllocFrames < capacity) {
            mainAllocated[mainAllocFrames] = currentThreadAllocated();
            mainStamp[mainAllocFrames] = System.nanoTime();
            mainAllocFrames++;
        }
    }

    /** Main thread: records one main frame's CPU time and the GPU pass times. */
    void recordMain(long nanos, FrameGraph graph) {
        if (mainFrames >= capacity) {
            return;
        }
        int passes = graph.passCount();
        if (passSum.length != passes) {
            passSum = new double[passes];
            passNames = new String[passes];
            for (int i = 0; i < passes; i++) {
                passNames[i] = graph.passName(i);
            }
        }
        double gpu = 0;
        for (int i = 0; i < passes; i++) {
            if (graph.isLive(i)) {
                double ms = graph.passMillis(i);
                passSum[i] += ms;
                gpu += ms;
            }
        }
        mainNanos[mainFrames] = nanos;
        gpuMillis[mainFrames] = (float) gpu;
        mainFrames++;
    }

    /**
     * The JDK's thread bean, looked up once: {@code ManagementFactory.getThreadMXBean()} itself
     * allocates about 1 KB per call.
     */
    private static final com.sun.management.ThreadMXBean THREADS =
            ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean b
                    ? b
                    : null;

    /** The calling thread's allocated bytes; reading it allocates nothing. */
    static long currentThreadAllocated() {
        return THREADS == null ? -1 : THREADS.getCurrentThreadAllocatedBytes();
    }

    /** Collections, collection ms, pauses and pause ms (ZGC reports pauses as their own beans). */
    private static long[] gc() {
        long[] out = new long[4];
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = Math.max(0, bean.getCollectionCount());
            long millis = Math.max(0, bean.getCollectionTime());
            if (bean.getName().contains("Pauses")) {
                out[2] += count;
                out[3] += millis;
            } else {
                out[0] += count;
                out[1] += millis;
                if (!bean.getName().startsWith("ZGC")) {
                    // Stop-the-world collectors: every collection is a pause.
                    out[2] += count;
                    out[3] += millis;
                }
            }
        }
        return out;
    }

    boolean hasData() {
        return gameFrames > 0 && endNanos > startNanos;
    }

    /** What {@link #write} produced: the Markdown report and the keys of the failed budgets. */
    record Report(String markdown, List<String> failed) {}

    /**
     * Writes {@code perf.json} and {@code perf.md} into {@code dir}. Budget keys: {@code game},
     * {@code main}, {@code gpu}, {@code alloc}, {@code gc}, {@code load}.
     */
    Report write(Path dir, String gpuName, String jvm) throws IOException {
        Files.createDirectories(dir);
        double seconds = (endNanos - startNanos) * 1e-9;
        Stats game = Stats.of(gameNanos, gameFrames);
        Stats main = Stats.of(mainNanos, mainFrames);
        Stats gpu = Stats.ofMillis(gpuMillis, mainFrames);
        long gcCount = gcCountEnd - gcCountStart;
        long gcMillis = gcMillisEnd - gcMillisStart;
        long pauses = gcPauseCountEnd - gcPauseCountStart;
        long pauseMillis = gcPauseMillisEnd - gcPauseMillisStart;
        long frames = Math.max(1, gameFrames);
        long avgInstances = instanceFrames == 0 ? instances : instanceSum / instanceFrames;
        long[] gameHalves = halves(gameAllocated, gameFrames);
        long[] workerHalves = halves(workerAllocated, gameFrames);
        long[] mainHalves = halves(mainAllocated, mainAllocFrames);
        long allocTotal = gameHalves[0] + workerHalves[0] + mainHalves[0];
        long allocSecondHalf = gameHalves[1] + workerHalves[1] + mainHalves[1];
        String[] threadNames = {"jeddy-game", "jeddy-worker-*", "main"};
        long[][] byThread = {gameHalves, workerHalves, mainHalves};
        Map<String, Object> alloc = new LinkedHashMap<>();
        for (int i = 0; i < threadNames.length; i++) {
            alloc.put(threadNames[i], (double) byThread[i][0]);
        }
        List<JitLog.Compile> compiles =
                jitLog != null && Files.isRegularFile(jitLog) ? JitLog.read(jitLog) : List.of();
        // Second-half allocating frames, unexplained (failures) and at C2 compiles (JIT warm-up).
        int allocatingFrames = 0;
        int mainAllocating = 0;
        int jitFrames = 0;
        long jitBytes = 0;
        long unexplained = 0;
        StringBuilder events = new StringBuilder();
        StringBuilder jitEvents = new StringBuilder();
        for (int f = gameFrames / 2 + 1; f < gameFrames; f++) {
            long g = gameAllocated[f] - gameAllocated[f - 1];
            long w = workerAllocated[f] - workerAllocated[f - 1];
            if (g == 0 && w == 0) {
                continue;
            }
            JitLog.Compile c =
                    JitLog.explain(
                            compiles, uptimeMillis(gameStamp[f - 1]), uptimeMillis(gameStamp[f]));
            String what = fmt("game frame %d: game %d B, workers %d B", f, g, w);
            if (c != null) {
                jitFrames++;
                jitBytes += g + w;
                jitEvents.append(jitNote(what, c));
            } else {
                unexplained += g + w;
                if (++allocatingFrames <= 8) {
                    events.append(what).append("; ");
                }
            }
        }
        for (int f = mainAllocFrames / 2 + 1; f < mainAllocFrames; f++) {
            long m = mainAllocated[f] - mainAllocated[f - 1];
            if (m == 0) {
                continue;
            }
            JitLog.Compile c =
                    JitLog.explain(
                            compiles, uptimeMillis(mainStamp[f - 1]), uptimeMillis(mainStamp[f]));
            String what = fmt("main frame %d of %d: %d B", f, mainAllocFrames, m);
            if (c != null) {
                jitFrames++;
                jitBytes += m;
                jitEvents.append(jitNote(what, c));
            } else {
                unexplained += m;
                if (++mainAllocating <= 8) {
                    events.append(what).append("; ");
                }
            }
        }
        double bytesPerFrame = (double) allocTotal / frames;

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("seconds", seconds);
        json.put("gpu", gpuName);
        json.put("jvm", jvm);
        json.put("agents", (double) agents);
        json.put("renderables", (double) instances);
        json.put("instancesPerFrame", (double) avgInstances);
        json.put("lights", (double) lights);
        json.put("fireballs", (double) casts);
        json.put("loadMs", loadNanos * 1e-6);
        json.put("gameFrames", (double) gameFrames);
        json.put("mainFrames", (double) mainFrames);
        json.put("gameMs", game.toJson());
        json.put("mainMs", main.toJson());
        json.put("gpuMs", gpu.toJson());
        Map<String, Object> passes = new LinkedHashMap<>();
        for (int i = 0; i < passSum.length; i++) {
            passes.put(passNames[i], passSum[i] / Math.max(1, mainFrames));
        }
        json.put("gpuPassMs", passes);
        json.put("allocatedBytes", alloc);
        json.put("allocatedBytesPerFrame", bytesPerFrame);
        json.put("allocatedBytesSecondHalf", (double) allocSecondHalf);
        json.put("allocatedBytesSecondHalfUnexplained", (double) unexplained);
        json.put("allocatingFramesSecondHalf", (double) allocatingFrames);
        json.put("mainAllocatingFramesSecondHalf", (double) mainAllocating);
        json.put("jitAllocatingFramesSecondHalf", (double) jitFrames);
        json.put("allocatedBytesSecondHalfJit", (double) jitBytes);
        json.put("jitLog", jitLog == null ? "none" : compiles.size() + " C2 compiles");
        Map<String, Object> halves = new LinkedHashMap<>();
        for (int i = 0; i < threadNames.length; i++) {
            halves.put(threadNames[i], (double) byThread[i][1]);
        }
        json.put("allocatedBytesSecondHalfByThread", halves);
        json.put("allocationEvents", events.toString());
        json.put("jitAllocationEvents", jitEvents.toString());
        json.put("probeAllocationEvents", probeEvents);
        Map<String, Object> gc = new LinkedHashMap<>();
        gc.put("collections", (double) gcCount);
        gc.put("collectionMs", (double) gcMillis);
        gc.put("pauses", (double) pauses);
        gc.put("pauseMs", (double) pauseMillis);
        json.put("gc", gc);

        List<String[]> rows = new ArrayList<>();
        rows.add(
                row(
                        "game",
                        "Game thread per frame (" + agents + " agents)",
                        "<= 8 ms",
                        fmt("avg %.2f / p99 %.2f / max %.2f ms", game.avg, game.p99, game.max),
                        game.p99 <= GAME_BUDGET_MS));
        rows.add(
                row(
                        "main",
                        "Main thread CPU (" + avgInstances + " instances per frame)",
                        "<= 5 ms",
                        headless
                                ? "not measured (headless)"
                                : fmt(
                                        "avg %.2f / p99 %.2f / max %.2f ms",
                                        main.avg, main.p99, main.max),
                        headless ? null : main.p99 <= MAIN_BUDGET_MS));
        rows.add(
                row(
                        "gpu",
                        "GPU (" + lights + " lights)",
                        "<= 12 ms",
                        headless
                                ? "not measured (headless)"
                                : fmt("avg %.2f / p99 %.2f ms", gpu.avg, gpu.p99),
                        headless ? null : gpu.p99 <= GPU_BUDGET_MS));
        rows.add(
                row(
                        "alloc",
                        "Steady-state allocation (game, main, workers)",
                        "0 B/frame",
                        fmt(
                                "%d B in the second half, %d game and %d main frames allocating"
                                        + "%s (%.2f B/frame over all %d frames, %d B)",
                                unexplained,
                                allocatingFrames,
                                mainAllocating,
                                jitFrames == 0
                                        ? ""
                                        : fmt(
                                                "; JIT warm-up: %d B in %d frames at C2 compiles",
                                                jitBytes, jitFrames),
                                bytesPerFrame,
                                frames,
                                allocTotal),
                        unexplained == 0));
        rows.add(
                row(
                        "gc",
                        "GC pauses",
                        "< 1 ms",
                        pauses == 0 ? "none" : fmt("%d pauses, %d ms total", pauses, pauseMillis),
                        pauses == 0 || pauseMillis < GC_PAUSE_BUDGET_MS * pauses));
        rows.add(
                row(
                        "load",
                        "Area load (crypt + characters)",
                        "<= 3 s",
                        fmt("%.0f ms", loadNanos * 1e-6),
                        loadNanos * 1e-6 <= LOAD_BUDGET_MS));
        List<Object> budgets = new ArrayList<>();
        for (String[] r : rows) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("budget", r[0]);
            b.put("target", r[1]);
            b.put("measured", r[2]);
            b.put("pass", r[3].equals("n/a") ? null : Boolean.valueOf(r[3]));
            budgets.add(b);
        }
        json.put("budgets", budgets);
        Files.writeString(dir.resolve("perf.json"), Json.write(json));

        StringBuilder md = new StringBuilder();
        md.append("# Iso demo perf run\n\n");
        md.append(
                fmt(
                        "%.0f s measured, %d game frames, %d main frames; GPU: %s; JVM: %s.\n\n",
                        seconds, gameFrames, mainFrames, gpuName, jvm));
        md.append("| Budget | Target | Measured | Pass |\n|---|---|---|---|\n");
        for (String[] r : rows) {
            md.append("| ")
                    .append(r[0])
                    .append(" | ")
                    .append(r[1])
                    .append(" | ")
                    .append(r[2])
                    .append(" | ")
                    .append(
                            r[3].equals("n/a")
                                    ? "n/a"
                                    : Boolean.parseBoolean(r[3]) ? "yes" : "**no**")
                    .append(" |\n");
        }
        md.append("\n## Allocation by thread\n\n| Thread | Bytes | Second half |\n|---|---|---|\n");
        for (int i = 0; i < threadNames.length; i++) {
            md.append("| ")
                    .append(threadNames[i])
                    .append(" | ")
                    .append(byThread[i][0])
                    .append(" | ")
                    .append(byThread[i][1])
                    .append(" |\n");
        }
        md.append("\n## GPU passes (average ms)\n\n| Pass | ms |\n|---|---|\n");
        for (int i = 0; i < passSum.length; i++) {
            md.append("| ")
                    .append(passNames[i])
                    .append(" | ")
                    .append(fmt("%.3f", passSum[i] / Math.max(1, mainFrames)))
                    .append(" |\n");
        }
        if (!events.isEmpty()) {
            md.append("\nAllocating frames in the second half: ").append(events).append('\n');
        }
        if (!jitEvents.isEmpty()) {
            md.append("\nJIT warm-up in the second half (a C2 compile request resolves its class's")
                    .append(" string literals on the requesting thread, D70): ")
                    .append(jitEvents)
                    .append('\n');
        }
        md.append(notes);
        if (!probeEvents.isEmpty()) {
            md.append("\nGame-thread allocations in the second half by system: ")
                    .append(probeEvents)
                    .append('\n');
        }
        Files.writeString(dir.resolve("perf.md"), md.toString());
        List<String> failed = new ArrayList<>();
        for (String[] r : rows) {
            if (r[3].equals("false")) {
                failed.add(r[4]);
            }
        }
        return new Report(md.toString(), failed);
    }

    private long uptimeMillis(long nanos) {
        return (nanos - uptimeOriginNanos) / 1_000_000L;
    }

    private static String jitNote(String what, JitLog.Compile c) {
        return fmt(
                "%s at %.1f s, C2 compile of %s%s; ",
                what,
                c.uptimeMillis() / 1000.0,
                c.method(),
                c.firstC2OfClass() ? " (its class's first)" : "");
    }

    /** Bytes over all {@code n} recorded frames and over the second half of them. */
    private static long[] halves(long[] counter, int n) {
        if (n < 2) {
            return new long[2];
        }
        return new long[] {counter[n - 1] - counter[0], counter[n - 1] - counter[n / 2]};
    }

    /** A budget row; {@code pass} is {@code null} when it was not measured. */
    private static String[] row(
            String key, String name, String target, String measured, Boolean pass) {
        return new String[] {name, target, measured, pass == null ? "n/a" : pass.toString(), key};
    }

    private static String fmt(String format, Object... args) {
        return String.format(Locale.ROOT, format, args);
    }

    /** Frame time statistics in milliseconds. */
    record Stats(double avg, double p50, double p95, double p99, double max) {
        static Stats of(long[] nanos, int n) {
            double[] ms = new double[n];
            for (int i = 0; i < n; i++) {
                ms[i] = nanos[i] * 1e-6;
            }
            return of(ms);
        }

        static Stats ofMillis(float[] millis, int n) {
            double[] ms = new double[n];
            for (int i = 0; i < n; i++) {
                ms[i] = millis[i];
            }
            return of(ms);
        }

        private static Stats of(double[] ms) {
            if (ms.length == 0) {
                return new Stats(0, 0, 0, 0, 0);
            }
            double sum = 0;
            for (double v : ms) {
                sum += v;
            }
            Arrays.sort(ms);
            return new Stats(
                    sum / ms.length,
                    ms[(int) (ms.length * 0.5)],
                    ms[Math.min(ms.length - 1, (int) (ms.length * 0.95))],
                    ms[Math.min(ms.length - 1, (int) (ms.length * 0.99))],
                    ms[ms.length - 1]);
        }

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("avg", avg);
            m.put("p50", p50);
            m.put("p95", p95);
            m.put("p99", p99);
            m.put("max", max);
            return m;
        }
    }
}
