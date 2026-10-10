package dev.jeddy.compress;

import static dev.jeddy.compress.ffi.ZstdLib.*;

import dev.jeddy.core.memory.CStrings;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * A zstd dictionary, digested once for compression ({@code ZSTD_CDict}) and decompression ({@code
 * ZSTD_DDict}) (TASKS M4-01). Dictionaries make many small, similar assets (materials, small
 * meshes, dialogue) compress far better; the cooker trains one with {@link #train} and stores it in
 * the pak.
 *
 * <p>Thread ownership: immutable once built; share it between contexts on any thread.
 */
public final class ZstdDictionary implements AutoCloseable {
    private final Arena arena = Arena.ofShared();
    private final MemorySegment bytes;
    private final MemorySegment cdict;
    private final MemorySegment ddict;
    private final int id;

    private ZstdDictionary(MemorySegment source, int level) {
        Zstd.loadNatives();
        bytes = arena.allocate(source.byteSize(), 16);
        bytes.copyFrom(source);
        cdict = ZSTD_createCDict(bytes, bytes.byteSize(), level);
        ddict = ZSTD_createDDict(bytes, bytes.byteSize());
        if (cdict.address() == 0 || ddict.address() == 0) {
            close();
            throw new ZstdException(
                    "ZSTD_createCDict", ZSTD_error_dictionary_corrupted(), "invalid dictionary");
        }
        id = ZSTD_getDictID_fromDict(bytes, bytes.byteSize());
    }

    /**
     * Builds a dictionary from its serialized bytes (copied).
     *
     * @param level the compression level compressors using it will apply
     */
    public static ZstdDictionary of(MemorySegment dictionary, int level) {
        return new ZstdDictionary(dictionary, level);
    }

    /**
     * Trains a dictionary from samples (cooker; cold path).
     *
     * @param samples the samples, stored back to back
     * @param sampleSizes the size of each sample in {@code samples}, in order
     * @param capacity the largest dictionary to produce, for example 64 KiB
     * @return the dictionary bytes
     * @throws ZstdException if training fails (too few or too small samples)
     */
    public static byte[] train(MemorySegment samples, long[] sampleSizes, int capacity) {
        Zstd.loadNatives();
        try (Arena a = Arena.ofConfined()) {
            MemorySegment sizes = a.allocateFrom(ValueLayout.JAVA_LONG, sampleSizes);
            MemorySegment out = a.allocate(capacity);
            long n = ZDICT_trainFromBuffer(out, capacity, samples, sizes, sampleSizes.length);
            if (ZDICT_isError(n) != 0) {
                throw new ZstdException(
                        "ZDICT_trainFromBuffer", -1, CStrings.read(ZDICT_getErrorName(n)));
            }
            return out.asSlice(0, n).toArray(ValueLayout.JAVA_BYTE);
        }
    }

    /** Returns the dictionary ID stored in frames compressed with it (0 for raw content). */
    public int id() {
        return id;
    }

    /** Returns the serialized dictionary. */
    public MemorySegment bytes() {
        return bytes;
    }

    MemorySegment cdict() {
        return cdict;
    }

    MemorySegment ddict() {
        return ddict;
    }

    /** Frees the digested dictionaries. Contexts must stop referencing it first. */
    @Override
    public void close() {
        if (arena.scope().isAlive()) {
            if (cdict != null && cdict.address() != 0) {
                ZSTD_freeCDict(cdict);
            }
            if (ddict != null && ddict.address() != 0) {
                ZSTD_freeDDict(ddict);
            }
            arena.close();
        }
    }
}
