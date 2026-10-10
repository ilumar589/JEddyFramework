package dev.jeddy.compress;

import dev.jeddy.compress.ffi.ZstdLib;
import dev.jeddy.core.memory.CStrings;
import dev.jeddy.core.natives.NativeLoader;

import java.lang.foreign.MemorySegment;

/**
 * zstd entry points that need no context: library loading, bounds, frame inspection and error
 * checks (DESIGN §6.4, TASKS M4-01). Compression and decompression go through {@link
 * ZstdCompressor} and {@link ZstdDecompressor}, which own reusable contexts.
 *
 * <p>Thread ownership: thread-safe.
 */
public final class Zstd {
    /** zstd's default level, a good balance for cooking during development. */
    public static final int DEFAULT_LEVEL = 3;

    /** Level the cooker uses for release paks: slow to compress, as fast to decompress. */
    public static final int RELEASE_LEVEL = 19;

    /** {@code ZSTD_CONTENTSIZE_UNKNOWN}: the frame does not record its decompressed size. */
    public static final long CONTENT_SIZE_UNKNOWN = -1L;

    private static final long ERROR_MAX_CODE = 120; // ZSTD_error_maxCode in zstd_errors.h

    static {
        NativeLoader.load("zstd"); // every entry point below needs the library
    }

    private Zstd() {}

    /** Loads the zstd shared library (idempotent; also happens when this class initializes). */
    public static void loadNatives() {
        NativeLoader.load("zstd");
    }

    /** Returns the library version as {@code major * 10000 + minor * 100 + patch}. */
    public static int versionNumber() {
        return ZstdLib.ZSTD_versionNumber();
    }

    /** Returns the largest compressed size of {@code srcSize} bytes (worst case). */
    public static long compressBound(long srcSize) {
        return ZstdLib.ZSTD_compressBound(srcSize);
    }

    /** Returns the highest compression level. */
    public static int maxLevel() {
        return ZstdLib.ZSTD_maxCLevel();
    }

    /**
     * Returns the decompressed size recorded in the frame at the start of {@code frame}, or {@link
     * #CONTENT_SIZE_UNKNOWN}.
     *
     * @throws ZstdException if {@code frame} does not start with a valid zstd frame header
     */
    public static long frameContentSize(MemorySegment frame) {
        long size = ZstdLib.ZSTD_getFrameContentSize(frame, frame.byteSize());
        if (size == -2L) { // ZSTD_CONTENTSIZE_ERROR
            throw new ZstdException(
                    "ZSTD_getFrameContentSize",
                    ZstdLib.ZSTD_error_prefix_unknown(),
                    "not a zstd frame");
        }
        return size;
    }

    /** Returns the compressed size of the first frame in {@code data}. */
    public static long frameCompressedSize(MemorySegment data) {
        return check(
                ZstdLib.ZSTD_findFrameCompressedSize(data, data.byteSize()),
                "ZSTD_findFrameCompressedSize");
    }

    /** Returns {@code true} if a {@code size_t} result is an error code ({@code ZSTD_isError}). */
    public static boolean isError(long result) {
        return Long.compareUnsigned(result, -ERROR_MAX_CODE) > 0;
    }

    /**
     * Returns {@code result} unless it is an error code. Allocates nothing on success, so it wraps
     * every call in the hot paths.
     *
     * @throws ZstdException if {@code result} is an error code
     */
    static long check(long result, String operation) {
        if (isError(result)) {
            throw failure(result, operation);
        }
        return result;
    }

    /**
     * Checks that {@code [offset, offset + length)} lies inside {@code segment}, which must be
     * native memory, and returns the address of {@code offset}. For the offset-based calls, which
     * pass raw addresses so that no slice is allocated.
     */
    static long address(MemorySegment segment, long offset, long length) {
        if (!segment.isNative()) {
            throw new IllegalArgumentException("offset-based zstd calls need native segments");
        }
        java.util.Objects.checkFromIndexSize(offset, length, segment.byteSize());
        return segment.address() + offset;
    }

    static ZstdException failure(long result, String operation) {
        return new ZstdException(
                operation,
                ZstdLib.ZSTD_getErrorCode(result),
                CStrings.read(ZstdLib.ZSTD_getErrorName(result)));
    }
}
