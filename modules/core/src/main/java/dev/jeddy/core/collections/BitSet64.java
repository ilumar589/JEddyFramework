package dev.jeddy.core.collections;

import java.util.Arrays;

/**
 * A growable bit set over a {@code long[]}. Used for flags, masks, visibility and fog of war. The
 * raw words are exposed through {@link #words()} for bulk work (OR-merging, uploads).
 *
 * <p>Thread ownership: single owner thread; not thread-safe.
 */
public final class BitSet64 {
    private long[] words;

    /** Creates a bit set with room for 64 bits. */
    public BitSet64() {
        this(64);
    }

    /**
     * Creates a bit set.
     *
     * @param bits initial number of bits it can hold without growing
     */
    public BitSet64(int bits) {
        words = new long[Math.max(1, wordCount(bits))];
    }

    /** Returns the number of 64-bit words needed for {@code bits} bits. */
    public static int wordCount(int bits) {
        return (bits + 63) >>> 6;
    }

    /** Returns the backing words. Replaced when the set grows. */
    public long[] words() {
        return words;
    }

    /** Returns how many bits fit without growing. */
    public int capacity() {
        return words.length << 6;
    }

    /** Returns bit {@code index}; bits beyond the capacity are 0. */
    public boolean get(int index) {
        int w = index >>> 6;
        return w < words.length && (words[w] & (1L << index)) != 0;
    }

    /** Sets bit {@code index}, growing if needed. */
    public void set(int index) {
        int w = index >>> 6;
        if (w >= words.length) {
            grow(w + 1);
        }
        words[w] |= 1L << index;
    }

    /** Sets bit {@code index} to {@code value}. */
    public void set(int index, boolean value) {
        if (value) {
            set(index);
        } else {
            clear(index);
        }
    }

    /** Clears bit {@code index}. */
    public void clear(int index) {
        int w = index >>> 6;
        if (w < words.length) {
            words[w] &= ~(1L << index);
        }
    }

    /** Flips bit {@code index}. */
    public void flip(int index) {
        int w = index >>> 6;
        if (w >= words.length) {
            grow(w + 1);
        }
        words[w] ^= 1L << index;
    }

    /** Clears every bit; keeps the capacity. */
    public void clear() {
        Arrays.fill(words, 0L);
    }

    /** Returns the number of set bits. */
    public int cardinality() {
        int n = 0;
        for (long w : words) {
            n += Long.bitCount(w);
        }
        return n;
    }

    /** Returns the index of the first set bit at or after {@code from}, or -1. */
    public int nextSetBit(int from) {
        int w = from >>> 6;
        if (w >= words.length) {
            return -1;
        }
        long word = words[w] & (-1L << from);
        while (true) {
            if (word != 0) {
                return (w << 6) + Long.numberOfTrailingZeros(word);
            }
            if (++w == words.length) {
                return -1;
            }
            word = words[w];
        }
    }

    /** Returns the index of the first clear bit at or after {@code from}. */
    public int nextClearBit(int from) {
        int w = from >>> 6;
        if (w >= words.length) {
            return from;
        }
        long word = ~words[w] & (-1L << from);
        while (true) {
            if (word != 0) {
                return (w << 6) + Long.numberOfTrailingZeros(word);
            }
            if (++w == words.length) {
                return w << 6;
            }
            word = ~words[w];
        }
    }

    /** Sets every bit that is set in {@code other}. */
    public void or(BitSet64 other) {
        if (other.words.length > words.length) {
            grow(other.words.length);
        }
        long[] o = other.words;
        for (int i = 0; i < o.length; i++) {
            words[i] |= o[i];
        }
    }

    /** Keeps only bits also set in {@code other}. */
    public void and(BitSet64 other) {
        long[] o = other.words;
        int n = Math.min(words.length, o.length);
        for (int i = 0; i < n; i++) {
            words[i] &= o[i];
        }
        Arrays.fill(words, n, words.length, 0L);
    }

    /** Clears every bit that is set in {@code other}. */
    public void andNot(BitSet64 other) {
        long[] o = other.words;
        int n = Math.min(words.length, o.length);
        for (int i = 0; i < n; i++) {
            words[i] &= ~o[i];
        }
    }

    /** Makes this set an exact copy of {@code other}. */
    public void copyFrom(BitSet64 other) {
        if (words.length < other.words.length) {
            words = new long[other.words.length];
        }
        System.arraycopy(other.words, 0, words, 0, other.words.length);
        Arrays.fill(words, other.words.length, words.length, 0L);
    }

    /** Makes sure bit {@code bits - 1} fits without growing. */
    public void ensureCapacity(int bits) {
        int needed = wordCount(bits);
        if (needed > words.length) {
            grow(needed);
        }
    }

    private void grow(int minWords) {
        words = Arrays.copyOf(words, Math.max(minWords, words.length * 2));
    }
}
