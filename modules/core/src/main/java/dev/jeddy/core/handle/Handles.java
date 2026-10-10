package dev.jeddy.core.handle;

/**
 * Packing of generational {@code int} handles: {@code generation (10 bits) | index (22 bits)}.
 * Handle 0 is {@link #NULL}; valid handles never use generation 0, so they are never 0.
 *
 * <p>Stale handles (whose slot was freed and reused) are detected by comparing generations (DESIGN
 * §4.1).
 *
 * <p>Thread ownership: stateless, callable from any thread.
 */
public final class Handles {
    /** The null handle. */
    public static final int NULL = 0;

    /** Bits used for the slot index. */
    public static final int INDEX_BITS = 22;

    /** Bits used for the generation. */
    public static final int GENERATION_BITS = 32 - INDEX_BITS;

    /** Largest number of slots a handle can address. */
    public static final int MAX_INDEX = (1 << INDEX_BITS) - 1;

    /** Largest generation; generations cycle through {@code [1, MAX_GENERATION]}. */
    public static final int MAX_GENERATION = (1 << GENERATION_BITS) - 1;

    private static final int INDEX_MASK = MAX_INDEX;

    private Handles() {}

    /** Packs an index and a generation in {@code [1, MAX_GENERATION]}. */
    public static int pack(int index, int generation) {
        return (generation << INDEX_BITS) | (index & INDEX_MASK);
    }

    /** Returns the slot index of a handle. */
    public static int index(int handle) {
        return handle & INDEX_MASK;
    }

    /** Returns the generation of a handle. */
    public static int generation(int handle) {
        return handle >>> INDEX_BITS;
    }

    /** Returns the generation that follows {@code generation}, skipping 0. */
    public static int nextGeneration(int generation) {
        return generation == MAX_GENERATION ? 1 : generation + 1;
    }

    /** Formats a handle as {@code index:generation} for logs. */
    public static String toString(int handle) {
        return handle == NULL ? "NULL" : index(handle) + ":" + generation(handle);
    }
}
