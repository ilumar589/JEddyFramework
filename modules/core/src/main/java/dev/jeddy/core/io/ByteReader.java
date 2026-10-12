package dev.jeddy.core.io;

import java.nio.charset.StandardCharsets;

/**
 * Reads what a {@link ByteWriter} wrote (DESIGN §21, D113). Every read checks the bounds first and
 * throws {@link Truncated} rather than reading past the end, so a cut or corrupted file fails at
 * the read that notices it with the byte offset in the message. The bulk methods read a count and
 * the elements into an array the caller passes, growing it only when it is too small (a {@code
 * null} array is allocated at the exact size).
 *
 * <p>Thread ownership: single owner thread.
 */
public final class ByteReader {
    /** The stream ended before a read completed. */
    public static final class Truncated extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        Truncated(int at, int needed) {
            super("stream truncated at byte " + at + " (needed " + needed + " more)");
        }
    }

    private final byte[] bytes;
    private final int end;
    private int at;

    /** Reads {@code bytes[offset, offset + length)}. */
    public ByteReader(byte[] bytes, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > bytes.length) {
            throw new IllegalArgumentException("range " + offset + "+" + length);
        }
        this.bytes = bytes;
        this.at = offset;
        this.end = offset + length;
    }

    /** Reads all of {@code bytes}. */
    public ByteReader(byte[] bytes) {
        this(bytes, 0, bytes.length);
    }

    private void need(int n) {
        if (n < 0 || at + n > end) {
            throw new Truncated(at, n);
        }
    }

    public int int8() {
        need(1);
        return bytes[at++];
    }

    public int int16() {
        need(2);
        int v = (bytes[at] & 0xFF) | (bytes[at + 1] & 0xFF) << 8;
        at += 2;
        return (short) v;
    }

    private int get32(int p) {
        return (bytes[p] & 0xFF)
                | (bytes[p + 1] & 0xFF) << 8
                | (bytes[p + 2] & 0xFF) << 16
                | (bytes[p + 3] & 0xFF) << 24;
    }

    public int int32() {
        need(4);
        int v = get32(at);
        at += 4;
        return v;
    }

    public long int64() {
        need(8);
        long low = get32(at) & 0xFFFFFFFFL;
        long high = get32(at + 4) & 0xFFFFFFFFL;
        at += 8;
        return low | high << 32;
    }

    public float float32() {
        return Float.intBitsToFloat(int32());
    }

    public double float64() {
        return Double.longBitsToDouble(int64());
    }

    public boolean bool() {
        need(1);
        return bytes[at++] != 0;
    }

    public String string() {
        need(2);
        int n = (bytes[at] & 0xFF) | (bytes[at + 1] & 0xFF) << 8;
        at += 2;
        need(n);
        String s = new String(bytes, at, n, StandardCharsets.UTF_8);
        at += n;
        return s;
    }

    /** The next string without consuming it: a registry routes a store by its name. */
    public String peekString() {
        int mark = at;
        String s = string();
        at = mark;
        return s;
    }

    // ---- Bulk -----------------------------------------------------------------------------------

    private int count(int elementBytes) {
        int n = int32();
        if (n < 0) {
            throw new IllegalStateException("negative count " + n + " at byte " + (at - 4));
        }
        need(n * elementBytes);
        return n;
    }

    /** Reads a count and that many {@code int}s into a new array of exactly that size. */
    public int[] ints() {
        int n = count(4);
        int[] a = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = get32(at + 4 * i);
        }
        at += 4 * n;
        return a;
    }

    /**
     * Reads a count and that many {@code int}s into {@code into}, which must be at least that long;
     * returns the count.
     */
    public int intsInto(int[] into) {
        int n = count(4);
        if (into.length < n) {
            throw new IllegalStateException("array of " + into.length + " for " + n + " ints");
        }
        for (int i = 0; i < n; i++) {
            into[i] = get32(at + 4 * i);
        }
        at += 4 * n;
        return n;
    }

    public long[] longs() {
        int n = count(8);
        long[] a = new long[n];
        for (int i = 0; i < n; i++) {
            long low = get32(at + 8 * i) & 0xFFFFFFFFL;
            long high = get32(at + 8 * i + 4) & 0xFFFFFFFFL;
            a[i] = low | high << 32;
        }
        at += 8 * n;
        return a;
    }

    /** Reads a count and that many {@code long}s into {@code into} (at least that long). */
    public int longsInto(long[] into) {
        int n = count(8);
        if (into.length < n) {
            throw new IllegalStateException("array of " + into.length + " for " + n + " longs");
        }
        for (int i = 0; i < n; i++) {
            long low = get32(at + 8 * i) & 0xFFFFFFFFL;
            long high = get32(at + 8 * i + 4) & 0xFFFFFFFFL;
            into[i] = low | high << 32;
        }
        at += 8 * n;
        return n;
    }

    public float[] floats() {
        int n = count(4);
        float[] a = new float[n];
        for (int i = 0; i < n; i++) {
            a[i] = Float.intBitsToFloat(get32(at + 4 * i));
        }
        at += 4 * n;
        return a;
    }

    /** Reads a count and that many {@code float}s into {@code into} (at least that long). */
    public int floatsInto(float[] into) {
        int n = count(4);
        if (into.length < n) {
            throw new IllegalStateException("array of " + into.length + " for " + n + " floats");
        }
        for (int i = 0; i < n; i++) {
            into[i] = Float.intBitsToFloat(get32(at + 4 * i));
        }
        at += 4 * n;
        return n;
    }

    public boolean[] bools() {
        int n = count(1);
        boolean[] a = new boolean[n];
        for (int i = 0; i < n; i++) {
            a[i] = bytes[at + i] != 0;
        }
        at += n;
        return a;
    }

    /** Reads a count and that many booleans into {@code into} (at least that long). */
    public int boolsInto(boolean[] into) {
        int n = count(1);
        if (into.length < n) {
            throw new IllegalStateException("array of " + into.length + " for " + n + " bools");
        }
        for (int i = 0; i < n; i++) {
            into[i] = bytes[at + i] != 0;
        }
        at += n;
        return n;
    }

    public byte[] bytes() {
        int n = count(1);
        byte[] a = new byte[n];
        System.arraycopy(bytes, at, a, 0, n);
        at += n;
        return a;
    }

    /** Skips {@code n} bytes. */
    public void skip(int n) {
        need(n);
        at += n;
    }

    /** The current offset into the backing array. */
    public int position() {
        return at;
    }

    /** Moves to {@code position} (an absolute offset within the range). */
    public void seek(int position) {
        if (position < 0 || position > end) {
            throw new IllegalArgumentException("seek to " + position + " of " + end);
        }
        at = position;
    }

    /** A reader over the next {@code length} bytes, which this reader then skips. */
    public ByteReader slice(int length) {
        need(length);
        ByteReader r = new ByteReader(bytes, at, length);
        at += length;
        return r;
    }

    /** Whether anything is left. */
    public boolean hasRemaining() {
        return at < end;
    }

    /** Bytes left. */
    public int remaining() {
        return end - at;
    }

    /** The backing array (shared, not copied). */
    public byte[] buffer() {
        return bytes;
    }
}
