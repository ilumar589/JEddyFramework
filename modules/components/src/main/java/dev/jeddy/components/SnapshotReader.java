package dev.jeddy.components;

import java.nio.charset.StandardCharsets;

/**
 * Reads what a {@link SnapshotWriter} wrote (DESIGN §22, D111). Every read checks the bounds and
 * throws {@link IllegalStateException} on a truncated or malformed stream.
 *
 * <p>Thread ownership: single owner thread.
 */
public final class SnapshotReader {
    private final byte[] bytes;
    private final int end;
    private int at;

    /** Reads {@code bytes[offset, offset + length)}. */
    public SnapshotReader(byte[] bytes, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > bytes.length) {
            throw new IllegalArgumentException("range " + offset + "+" + length);
        }
        this.bytes = bytes;
        this.at = offset;
        this.end = offset + length;
    }

    /** Reads all of {@code bytes}. */
    public SnapshotReader(byte[] bytes) {
        this(bytes, 0, bytes.length);
    }

    private void need(int n) {
        if (at + n > end) {
            throw new IllegalStateException("snapshot truncated at byte " + at);
        }
    }

    public int int32() {
        need(4);
        int v =
                (bytes[at] & 0xFF)
                        | (bytes[at + 1] & 0xFF) << 8
                        | (bytes[at + 2] & 0xFF) << 16
                        | (bytes[at + 3] & 0xFF) << 24;
        at += 4;
        return v;
    }

    public long int64() {
        long low = int32() & 0xFFFFFFFFL;
        long high = int32() & 0xFFFFFFFFL;
        return low | high << 32;
    }

    public float float32() {
        return Float.intBitsToFloat(int32());
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

    /** Whether anything is left. */
    public boolean hasRemaining() {
        return at < end;
    }

    /** Bytes left. */
    public int remaining() {
        return end - at;
    }
}
