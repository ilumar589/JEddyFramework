package dev.jeddy.compress;

import static dev.jeddy.compress.ffi.ZstdLib.*;

import dev.jeddy.compress.ffi.ZSTD_inBuffer;
import dev.jeddy.compress.ffi.ZSTD_outBuffer;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * A reusable zstd decompression context ({@code ZSTD_DCtx}) for one-shot and streaming
 * decompression (TASKS M4-01). Asset loading keeps one per worker and decompresses straight from
 * the mapped pak into the destination (a staging ring for GPU uploads). Warm calls allocate
 * nothing.
 *
 * <p>Thread ownership: one thread at a time.
 */
public final class ZstdDecompressor implements AutoCloseable {
    /** {@code ZSTD_decompressDCtx} taking raw addresses, so offset-based calls allocate nothing. */
    private static final class Raw {
        static final MethodHandle DECOMPRESS =
                Linker.nativeLinker()
                        .downcallHandle(
                                ZSTD_decompressDCtx$address(),
                                FunctionDescriptor.of(
                                        ValueLayout.JAVA_LONG,
                                        ValueLayout.ADDRESS,
                                        ValueLayout.JAVA_LONG,
                                        ValueLayout.JAVA_LONG,
                                        ValueLayout.JAVA_LONG,
                                        ValueLayout.JAVA_LONG));
    }

    private final Arena arena = Arena.ofShared();
    private final MemorySegment dctx;
    private final MemorySegment in = arena.allocate(ZSTD_inBuffer.layout());
    private final MemorySegment out = arena.allocate(ZSTD_outBuffer.layout());
    private long consumed;
    private long produced;

    /** Creates a context. */
    public ZstdDecompressor() {
        Zstd.loadNatives();
        MemorySegment ctx = ZSTD_createDCtx();
        if (ctx.address() == 0) {
            arena.close();
            throw new OutOfMemoryError("ZSTD_createDCtx");
        }
        dctx = ctx;
    }

    /** Decompresses with {@code dictionary} from now on ({@code null} to stop). */
    public ZstdDecompressor dictionary(ZstdDictionary dictionary) {
        Zstd.check(
                ZSTD_DCtx_refDDict(
                        dctx, dictionary == null ? MemorySegment.NULL : dictionary.ddict()),
                "ZSTD_DCtx_refDDict");
        return this;
    }

    /**
     * Decompresses all frames in {@code src} into {@code dst}.
     *
     * @return the decompressed size
     * @throws ZstdException on corrupt input, a checksum mismatch or a too-small {@code dst}
     */
    public long decompress(MemorySegment dst, MemorySegment src) {
        return Zstd.check(
                ZSTD_decompressDCtx(dctx, dst, dst.byteSize(), src, src.byteSize()),
                "ZSTD_decompressDCtx");
    }

    /**
     * Decompresses {@code src[srcOffset, srcOffset + srcLength)} into {@code dst[dstOffset,
     * dstOffset + dstCapacity)} without allocating slices: straight from a mapped pak entry into a
     * staging ring. Both segments must be native and stay alive during the call.
     *
     * @return the decompressed size
     */
    public long decompress(
            MemorySegment dst,
            long dstOffset,
            long dstCapacity,
            MemorySegment src,
            long srcOffset,
            long srcLength) {
        long d = Zstd.address(dst, dstOffset, dstCapacity);
        long s = Zstd.address(src, srcOffset, srcLength);
        try {
            return Zstd.check(
                    (long) Raw.DECOMPRESS.invokeExact(dctx, d, dstCapacity, s, srcLength),
                    "ZSTD_decompressDCtx");
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /**
     * Streams: decompresses as much of {@code src[srcOffset, srcLength)} into {@code dst[dstOffset,
     * dstLength)} as fits. Read {@link #consumed()} and {@link #produced()} afterwards.
     *
     * @return 0 when a frame was completely decoded and flushed; otherwise a hint of how many more
     *     input bytes the frame needs
     */
    public long decompressStream(
            MemorySegment src,
            long srcOffset,
            long srcLength,
            MemorySegment dst,
            long dstOffset,
            long dstLength) {
        ZSTD_inBuffer.src(in, src);
        ZSTD_inBuffer.size(in, srcOffset + srcLength);
        ZSTD_inBuffer.pos(in, srcOffset);
        ZSTD_outBuffer.dst(out, dst);
        ZSTD_outBuffer.size(out, dstOffset + dstLength);
        ZSTD_outBuffer.pos(out, dstOffset);
        long hint = Zstd.check(ZSTD_decompressStream(dctx, out, in), "ZSTD_decompressStream");
        consumed = ZSTD_inBuffer.pos(in) - srcOffset;
        produced = ZSTD_outBuffer.pos(out) - dstOffset;
        return hint;
    }

    /** Returns the input bytes the last {@link #decompressStream} call consumed. */
    public long consumed() {
        return consumed;
    }

    /** Returns the output bytes the last {@link #decompressStream} call produced. */
    public long produced() {
        return produced;
    }

    /** Abandons the current stream. */
    public void reset() {
        Zstd.check(ZSTD_DCtx_reset(dctx, ZSTD_reset_session_only()), "ZSTD_DCtx_reset");
    }

    /** Frees the context. */
    @Override
    public void close() {
        if (arena.scope().isAlive()) {
            ZSTD_freeDCtx(dctx);
            arena.close();
        }
    }
}
