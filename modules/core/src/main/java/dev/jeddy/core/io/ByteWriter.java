package dev.jeddy.core.io;

import java.util.Arrays;

/**
 * The engine's binary stream for state that leaves the process (DESIGN §21, D113): a growable
 * little-endian {@code byte[]} that every system with something to save writes itself into. Scalars
 * are {@link #int32}, {@link #int64}, {@link #float32} and {@link #bool} (one byte); a {@link
 * #string} is a {@code u16} length and UTF-8 bytes; the bulk methods ({@link #ints}, {@link
 * #floats}, {@link #longs}, {@link #bools}, {@link #bytes}) write a {@code u32} count and the
 * elements, which is how a store's column or a bitset goes out in one call.
 *
 * <p>The buffer is kept between uses: {@link #reset} rewinds without freeing, so a capture that
 * fits the previous one allocates nothing (the save path's "no hitch" rule, D118). Growth doubles.
 * Everything is little-endian regardless of the host, because every target this engine has is
 * little-endian and a fixed byte order is what makes a file portable.
 *
 * <p>Thread ownership: single owner thread (the game thread during a capture; the writer is then
 * handed whole to the background job, which reads {@link #buffer} and {@link #size}).
 */
public final class ByteWriter {
    private byte[] bytes;
    private int size;

    /** Creates a writer with {@code capacity} bytes of room (it grows). */
    public ByteWriter(int capacity) {
        bytes = new byte[Math.max(16, capacity)];
    }

    /** Creates a writer with a small initial buffer. */
    public ByteWriter() {
        this(1024);
    }

    private void ensure(int more) {
        if (size + more > bytes.length) {
            bytes = Arrays.copyOf(bytes, Math.max(bytes.length * 2, size + more));
        }
    }

    /** Rewinds to empty, keeping the buffer. */
    public void reset() {
        size = 0;
    }

    public void int8(int v) {
        ensure(1);
        bytes[size++] = (byte) v;
    }

    public void int16(int v) {
        ensure(2);
        bytes[size] = (byte) v;
        bytes[size + 1] = (byte) (v >>> 8);
        size += 2;
    }

    public void int32(int v) {
        ensure(4);
        put32(size, v);
        size += 4;
    }

    private void put32(int at, int v) {
        bytes[at] = (byte) v;
        bytes[at + 1] = (byte) (v >>> 8);
        bytes[at + 2] = (byte) (v >>> 16);
        bytes[at + 3] = (byte) (v >>> 24);
    }

    public void int64(long v) {
        ensure(8);
        put32(size, (int) v);
        put32(size + 4, (int) (v >>> 32));
        size += 8;
    }

    public void float32(float v) {
        int32(Float.floatToRawIntBits(v));
    }

    public void float64(double v) {
        int64(Double.doubleToRawLongBits(v));
    }

    public void bool(boolean v) {
        ensure(1);
        bytes[size++] = (byte) (v ? 1 : 0);
    }

    /**
     * Writes a string of at most 65535 UTF-8 bytes ({@code null} is written as empty). Encodes by
     * hand so that a capture allocates nothing; an unpaired surrogate is written as U+FFFD.
     */
    public void string(String s) {
        int chars = s == null ? 0 : s.length();
        int n = 0;
        for (int i = 0; i < chars; i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                n += 1;
            } else if (c < 0x800) {
                n += 2;
            } else if (Character.isHighSurrogate(c)
                    && i + 1 < chars
                    && Character.isLowSurrogate(s.charAt(i + 1))) {
                n += 4;
                i++;
            } else {
                n += 3;
            }
        }
        if (n > 0xFFFF) {
            throw new IllegalArgumentException("string too long for the stream: " + n);
        }
        ensure(2 + n);
        bytes[size] = (byte) n;
        bytes[size + 1] = (byte) (n >>> 8);
        size += 2;
        for (int i = 0; i < chars; i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                bytes[size++] = (byte) c;
            } else if (c < 0x800) {
                bytes[size++] = (byte) (0xC0 | c >> 6);
                bytes[size++] = (byte) (0x80 | c & 0x3F);
            } else if (Character.isHighSurrogate(c)
                    && i + 1 < chars
                    && Character.isLowSurrogate(s.charAt(i + 1))) {
                int cp = Character.toCodePoint(c, s.charAt(++i));
                bytes[size++] = (byte) (0xF0 | cp >> 18);
                bytes[size++] = (byte) (0x80 | cp >> 12 & 0x3F);
                bytes[size++] = (byte) (0x80 | cp >> 6 & 0x3F);
                bytes[size++] = (byte) (0x80 | cp & 0x3F);
            } else {
                int cp = Character.isSurrogate(c) ? 0xFFFD : c;
                bytes[size++] = (byte) (0xE0 | cp >> 12);
                bytes[size++] = (byte) (0x80 | cp >> 6 & 0x3F);
                bytes[size++] = (byte) (0x80 | cp & 0x3F);
            }
        }
    }

    // ---- Bulk: a count and the elements -------------------------------------------------------

    /** Writes {@code n} and {@code a[0, n)}. */
    public void ints(int[] a, int n) {
        int32(n);
        ensure(4 * n);
        for (int i = 0; i < n; i++) {
            put32(size + 4 * i, a[i]);
        }
        size += 4 * n;
    }

    public void ints(int[] a) {
        ints(a, a.length);
    }

    /** Writes {@code n} and {@code a[0, n)}. */
    public void longs(long[] a, int n) {
        int32(n);
        ensure(8 * n);
        for (int i = 0; i < n; i++) {
            long v = a[i];
            put32(size + 8 * i, (int) v);
            put32(size + 8 * i + 4, (int) (v >>> 32));
        }
        size += 8 * n;
    }

    public void longs(long[] a) {
        longs(a, a.length);
    }

    /** Writes {@code n} and {@code a[0, n)} as raw IEEE-754 bits. */
    public void floats(float[] a, int n) {
        int32(n);
        ensure(4 * n);
        for (int i = 0; i < n; i++) {
            put32(size + 4 * i, Float.floatToRawIntBits(a[i]));
        }
        size += 4 * n;
    }

    public void floats(float[] a) {
        floats(a, a.length);
    }

    /** Writes {@code n} and {@code a[0, n)}, one byte each. */
    public void bools(boolean[] a, int n) {
        int32(n);
        ensure(n);
        for (int i = 0; i < n; i++) {
            bytes[size + i] = (byte) (a[i] ? 1 : 0);
        }
        size += n;
    }

    public void bools(boolean[] a) {
        bools(a, a.length);
    }

    /** Writes {@code n} and {@code a[offset, offset + n)}. */
    public void bytes(byte[] a, int offset, int n) {
        int32(n);
        raw(a, offset, n);
    }

    public void bytes(byte[] a) {
        bytes(a, 0, a.length);
    }

    /** Appends {@code a[offset, offset + n)} without a count (a nested stream, padding). */
    public void raw(byte[] a, int offset, int n) {
        ensure(n);
        System.arraycopy(a, offset, bytes, size, n);
        size += n;
    }

    /** Appends {@code n} zero bytes. */
    public void zeros(int n) {
        ensure(n);
        Arrays.fill(bytes, size, size + n, (byte) 0);
        size += n;
    }

    /** Overwrites the {@code int32} at {@code at} (a length patched after its contents). */
    public void patchInt32(int at, int v) {
        if (at < 0 || at + 4 > size) {
            throw new IndexOutOfBoundsException("patch at " + at + " of " + size);
        }
        put32(at, v);
    }

    /** Bytes written so far. */
    public int size() {
        return size;
    }

    /** The backing array; valid up to {@link #size} and until the next write grows it. */
    public byte[] buffer() {
        return bytes;
    }

    /** Bytes the buffer can hold before growing. */
    public int capacity() {
        return bytes.length;
    }

    /** A copy of the bytes written so far. */
    public byte[] toBytes() {
        return Arrays.copyOf(bytes, size);
    }
}
