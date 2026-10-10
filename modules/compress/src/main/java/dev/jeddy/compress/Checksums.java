package dev.jeddy.compress;

import java.lang.foreign.MemorySegment;
import java.util.zip.CRC32C;

/**
 * CRC32C over memory segments (TASKS M4-02), used for pak entries and cooked file integrity. {@link
 * CRC32C} is a JIT intrinsic (the SSE4.2 {@code crc32} instruction on x64), so checksums run at
 * memory speed. Each call allocates one small {@code ByteBuffer} view, which is fine on the load
 * path.
 *
 * <p>Thread ownership: thread-safe (stateless).
 */
public final class Checksums {
    private Checksums() {}

    /** Returns the CRC32C of {@code data}. */
    public static int crc32c(MemorySegment data) {
        CRC32C crc = new CRC32C();
        update(crc, data);
        return (int) crc.getValue();
    }

    /** Returns the CRC32C of {@code data[offset, offset + length)}. */
    public static int crc32c(MemorySegment data, long offset, long length) {
        return crc32c(data.asSlice(offset, length));
    }

    /** Returns the CRC32C of a byte array. */
    public static int crc32c(byte[] data) {
        CRC32C crc = new CRC32C();
        crc.update(data, 0, data.length);
        return (int) crc.getValue();
    }

    /** Feeds {@code data} into {@code crc}, in chunks of at most 1 GiB for very large segments. */
    public static void update(CRC32C crc, MemorySegment data) {
        long size = data.byteSize();
        long chunk = 1L << 30;
        for (long offset = 0; offset < size; offset += chunk) {
            crc.update(data.asSlice(offset, Math.min(chunk, size - offset)).asByteBuffer());
        }
    }
}
