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
 * A reusable zstd compression context ({@code ZSTD_CCtx}) for one-shot and streaming compression
 * (TASKS M4-01). Keep one per thread, for example in a {@code PerWorker}: contexts are expensive to
 * create and cheap to reuse, and every call on a warm context allocates nothing.
 *
 * <pre>{@code
 * try (ZstdCompressor z = new ZstdCompressor(Zstd.DEFAULT_LEVEL)) {
 *     long n = z.compress(dst, src); // dst at least Zstd.compressBound(src.byteSize())
 * }
 * }</pre>
 *
 * <p>Thread ownership: one thread at a time.
 */
public final class ZstdCompressor implements AutoCloseable {
    /** Stream directive: keep buffering. */
    public static final int CONTINUE = 0;

    /** Stream directive: flush everything compressed so far (ends a block, not the frame). */
    public static final int FLUSH = 1;

    /** Stream directive: finish the frame. */
    public static final int END = 2;

    /** {@code ZSTD_compress2} taking raw addresses, so offset-based calls allocate no slice. */
    private static final class Raw {
        static final MethodHandle COMPRESS2 =
                Linker.nativeLinker()
                        .downcallHandle(
                                ZSTD_compress2$address(),
                                FunctionDescriptor.of(
                                        ValueLayout.JAVA_LONG,
                                        ValueLayout.ADDRESS,
                                        ValueLayout.JAVA_LONG,
                                        ValueLayout.JAVA_LONG,
                                        ValueLayout.JAVA_LONG,
                                        ValueLayout.JAVA_LONG));
    }

    private final Arena arena = Arena.ofShared();
    private final MemorySegment cctx;
    private final MemorySegment in = arena.allocate(ZSTD_inBuffer.layout());
    private final MemorySegment out = arena.allocate(ZSTD_outBuffer.layout());
    private long consumed;
    private long produced;

    /** Creates a context at {@code level} with frame checksums on. */
    public ZstdCompressor(int level) {
        Zstd.loadNatives();
        MemorySegment ctx = ZSTD_createCCtx();
        if (ctx.address() == 0) {
            arena.close();
            throw new OutOfMemoryError("ZSTD_createCCtx");
        }
        cctx = ctx;
        level(level);
        checksum(true);
    }

    /** Sets the compression level (1 to {@link Zstd#maxLevel()}; negative = faster). */
    public ZstdCompressor level(int level) {
        Zstd.check(
                ZSTD_CCtx_setParameter(cctx, ZSTD_c_compressionLevel(), level),
                "ZSTD_c_compressionLevel");
        return this;
    }

    /** Turns the 32-bit content checksum at the end of each frame on or off. */
    public ZstdCompressor checksum(boolean enabled) {
        Zstd.check(
                ZSTD_CCtx_setParameter(cctx, ZSTD_c_checksumFlag(), enabled ? 1 : 0),
                "ZSTD_c_checksumFlag");
        return this;
    }

    /**
     * Uses {@code workers} background threads inside zstd for large inputs (0 = single-threaded,
     * the default). Only for offline work such as cooking.
     *
     * @return {@code false} if this zstd build has no multithreading
     */
    public boolean workers(int workers) {
        return !Zstd.isError(ZSTD_CCtx_setParameter(cctx, ZSTD_c_nbWorkers(), workers));
    }

    /** Compresses with {@code dictionary} from now on ({@code null} to stop). */
    public ZstdCompressor dictionary(ZstdDictionary dictionary) {
        Zstd.check(
                ZSTD_CCtx_refCDict(
                        cctx, dictionary == null ? MemorySegment.NULL : dictionary.cdict()),
                "ZSTD_CCtx_refCDict");
        return this;
    }

    /**
     * Compresses all of {@code src} into {@code dst} as one frame.
     *
     * @return the compressed size
     * @throws ZstdException if {@code dst} is too small ({@link Zstd#compressBound(long)} always
     *     suffices)
     */
    public long compress(MemorySegment dst, MemorySegment src) {
        return Zstd.check(
                ZSTD_compress2(cctx, dst, dst.byteSize(), src, src.byteSize()), "ZSTD_compress2");
    }

    /**
     * Compresses {@code src[srcOffset, srcOffset + srcLength)} into {@code dst[dstOffset, dstOffset
     * + dstCapacity)} as one frame, without allocating slices. Both segments must be native and
     * stay alive during the call (pak mappings, staging rings).
     *
     * @return the compressed size
     */
    public long compress(
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
                    (long) Raw.COMPRESS2.invokeExact(cctx, d, dstCapacity, s, srcLength),
                    "ZSTD_compress2");
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /**
     * Streams: compresses as much of {@code src[srcOffset, srcLength)} into {@code dst[dstOffset,
     * dstLength)} as fits. Read {@link #consumed()} and {@link #produced()} afterwards.
     *
     * @param directive {@link #CONTINUE}, {@link #FLUSH} or {@link #END}
     * @return 0 when the directive is complete (for {@link #END}: the frame is finished); otherwise
     *     call again with more output space
     */
    public long compressStream(
            MemorySegment src,
            long srcOffset,
            long srcLength,
            MemorySegment dst,
            long dstOffset,
            long dstLength,
            int directive) {
        ZSTD_inBuffer.src(in, src);
        ZSTD_inBuffer.size(in, srcOffset + srcLength);
        ZSTD_inBuffer.pos(in, srcOffset);
        ZSTD_outBuffer.dst(out, dst);
        ZSTD_outBuffer.size(out, dstOffset + dstLength);
        ZSTD_outBuffer.pos(out, dstOffset);
        long remaining =
                Zstd.check(ZSTD_compressStream2(cctx, out, in, directive), "ZSTD_compressStream2");
        consumed = ZSTD_inBuffer.pos(in) - srcOffset;
        produced = ZSTD_outBuffer.pos(out) - dstOffset;
        return remaining;
    }

    /** Returns the input bytes the last {@link #compressStream} call consumed. */
    public long consumed() {
        return consumed;
    }

    /** Returns the output bytes the last {@link #compressStream} call produced. */
    public long produced() {
        return produced;
    }

    /**
     * Abandons the current stream and starts a new frame on the next call; keeps the parameters.
     */
    public void reset() {
        Zstd.check(ZSTD_CCtx_reset(cctx, ZSTD_reset_session_only()), "ZSTD_CCtx_reset");
    }

    /** Announces the total size of the next streamed frame, so it is stored in the header. */
    public void pledgeSize(long totalBytes) {
        Zstd.check(ZSTD_CCtx_setPledgedSrcSize(cctx, totalBytes), "ZSTD_CCtx_setPledgedSrcSize");
    }

    /** Frees the context. */
    @Override
    public void close() {
        if (arena.scope().isAlive()) {
            ZSTD_freeCCtx(cctx);
            arena.close();
        }
    }
}
