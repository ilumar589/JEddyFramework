package dev.jeddy.components;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A growable little-endian byte stream the stores write themselves into (DESIGN §22, D111): strings
 * are a {@code u16} length and UTF-8 bytes. Allocates; a save-time path.
 *
 * <p>Thread ownership: single owner thread.
 */
public final class SnapshotWriter {
    private byte[] bytes;
    private int size;

    /** Creates a writer with {@code capacity} bytes of room (it grows). */
    public SnapshotWriter(int capacity) {
        bytes = new byte[Math.max(16, capacity)];
    }

    /** Creates a writer with a small initial buffer. */
    public SnapshotWriter() {
        this(1024);
    }

    private void ensure(int more) {
        if (size + more > bytes.length) {
            bytes = Arrays.copyOf(bytes, Math.max(bytes.length * 2, size + more));
        }
    }

    public void int32(int v) {
        ensure(4);
        bytes[size] = (byte) v;
        bytes[size + 1] = (byte) (v >>> 8);
        bytes[size + 2] = (byte) (v >>> 16);
        bytes[size + 3] = (byte) (v >>> 24);
        size += 4;
    }

    public void int64(long v) {
        int32((int) v);
        int32((int) (v >>> 32));
    }

    public void float32(float v) {
        int32(Float.floatToRawIntBits(v));
    }

    public void bool(boolean v) {
        ensure(1);
        bytes[size++] = (byte) (v ? 1 : 0);
    }

    /** Writes a string of at most 65535 UTF-8 bytes. */
    public void string(String s) {
        byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
        if (utf8.length > 0xFFFF) {
            throw new IllegalArgumentException("string too long for a snapshot: " + utf8.length);
        }
        ensure(2 + utf8.length);
        bytes[size] = (byte) utf8.length;
        bytes[size + 1] = (byte) (utf8.length >>> 8);
        size += 2;
        System.arraycopy(utf8, 0, bytes, size, utf8.length);
        size += utf8.length;
    }

    /** Bytes written so far. */
    public int size() {
        return size;
    }

    /** A copy of the bytes written so far. */
    public byte[] toBytes() {
        return Arrays.copyOf(bytes, size);
    }
}
