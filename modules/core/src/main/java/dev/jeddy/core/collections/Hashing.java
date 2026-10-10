package dev.jeddy.core.collections;

/**
 * Hash mixing and table sizing for the open-addressing collections.
 *
 * <p>Thread ownership: stateless, callable from any thread.
 */
public final class Hashing {
    private Hashing() {}

    /** Scrambles an int so that sequential keys spread over a power-of-two table. */
    public static int mix(int x) {
        int h = x * 0x9E3779B9;
        return h ^ (h >>> 16);
    }

    /** Scrambles a long down to an int (Fibonacci hashing plus xor-folding). */
    public static int mix(long x) {
        long h = x * 0x9E3779B97F4A7C15L;
        h ^= h >>> 32;
        return (int) (h ^ (h >>> 16));
    }

    /**
     * Returns the power-of-two table size that holds {@code expected} entries below {@code
     * loadFactor}.
     */
    public static int tableSize(int expected, float loadFactor) {
        if (expected < 0) {
            throw new IllegalArgumentException("expected < 0: " + expected);
        }
        long needed = (long) Math.ceil(Math.max(expected, 2) / (double) loadFactor);
        if (needed > (1 << 30)) {
            throw new IllegalArgumentException("too many entries: " + expected);
        }
        return Integer.highestOneBit((int) needed - 1) << 1;
    }

    /** Returns {@code true} if {@code pos} lies cyclically in {@code (from, to]} of a table. */
    static boolean cyclicBetween(int from, int pos, int to) {
        return from <= to ? from < pos && pos <= to : from < pos || pos <= to;
    }
}
