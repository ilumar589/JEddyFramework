package dev.jeddy.core.memory;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Counts off-heap bytes per {@link MemoryCategory}: current and peak. Updated by {@link OffHeap}
 * and anything else that allocates native memory (DESIGN §4.3).
 *
 * <p>Thread ownership: thread-safe, lock-free.
 */
public final class MemoryStats {
    private static final int N = MemoryCategory.values().length;
    private static final AtomicLongArray CURRENT = new AtomicLongArray(N);
    private static final AtomicLongArray PEAK = new AtomicLongArray(N);

    private MemoryStats() {}

    /** Records {@code bytes} allocated in {@code category}. */
    public static void allocated(MemoryCategory category, long bytes) {
        int i = category.ordinal();
        long now = CURRENT.addAndGet(i, bytes);
        long peak;
        while (now > (peak = PEAK.get(i))) {
            if (PEAK.compareAndSet(i, peak, now)) {
                break;
            }
        }
    }

    /** Records {@code bytes} released in {@code category}. */
    public static void released(MemoryCategory category, long bytes) {
        CURRENT.addAndGet(category.ordinal(), -bytes);
    }

    /** Returns the bytes currently held in {@code category}. */
    public static long current(MemoryCategory category) {
        return CURRENT.get(category.ordinal());
    }

    /** Returns the most bytes ever held at once in {@code category}. */
    public static long peak(MemoryCategory category) {
        return PEAK.get(category.ordinal());
    }

    /** Returns the bytes currently held across all categories. */
    public static long total() {
        long sum = 0;
        for (int i = 0; i < N; i++) {
            sum += CURRENT.get(i);
        }
        return sum;
    }

    /** Formats a one-line summary (allocates; for logs and overlays). */
    public static String summary() {
        StringBuilder sb = new StringBuilder("off-heap:");
        for (MemoryCategory c : MemoryCategory.values()) {
            long cur = current(c);
            if (cur != 0 || peak(c) != 0) {
                sb.append(' ')
                        .append(c.name().toLowerCase(java.util.Locale.ROOT))
                        .append('=')
                        .append(cur >> 10)
                        .append("KiB (peak ")
                        .append(peak(c) >> 10)
                        .append("KiB)");
            }
        }
        return sb.toString();
    }
}
