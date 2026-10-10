package dev.jeddy.core.profile;

import java.util.Arrays;
import java.util.Locale;

/**
 * Per-scope totals filled by {@link Profiler#drain(ScopeStats)}: call count, total and maximum
 * duration. Reset it each frame (or each reporting interval).
 *
 * <p>Thread ownership: single reader thread.
 */
public final class ScopeStats {
    private long[] totalNanos = new long[32];
    private long[] maxNanos = new long[32];
    private int[] counts = new int[32];

    /** Creates empty stats. */
    public ScopeStats() {}

    void add(int scope, long nanos) {
        if (scope >= counts.length) {
            int n = Math.max(scope + 1, counts.length * 2);
            totalNanos = Arrays.copyOf(totalNanos, n);
            maxNanos = Arrays.copyOf(maxNanos, n);
            counts = Arrays.copyOf(counts, n);
        }
        totalNanos[scope] += nanos;
        counts[scope]++;
        if (nanos > maxNanos[scope]) {
            maxNanos[scope] = nanos;
        }
    }

    /** Clears every total. */
    public void reset() {
        Arrays.fill(totalNanos, 0);
        Arrays.fill(maxNanos, 0);
        Arrays.fill(counts, 0);
    }

    /** Returns how many times scope {@code id} completed. */
    public int count(int id) {
        return id < counts.length ? counts[id] : 0;
    }

    /** Returns the summed duration of scope {@code id} in nanoseconds. */
    public long totalNanos(int id) {
        return id < totalNanos.length ? totalNanos[id] : 0;
    }

    /** Returns the longest single duration of scope {@code id} in nanoseconds. */
    public long maxNanos(int id) {
        return id < maxNanos.length ? maxNanos[id] : 0;
    }

    /** Formats a table of every scope that ran (allocates; for logs and overlays). */
    public String format() {
        StringBuilder sb = new StringBuilder();
        for (int id = 0; id < counts.length; id++) {
            if (counts[id] == 0) {
                continue;
            }
            sb.append(
                    String.format(
                            Locale.ROOT,
                            "%-28s %6d calls %9.3f ms total %8.3f ms max%n",
                            ScopeIds.name(id),
                            counts[id],
                            totalNanos[id] / 1e6,
                            maxNanos[id] / 1e6));
        }
        return sb.toString();
    }
}
