package dev.jeddy.asset.pak;

import static dev.jeddy.asset.pak.PakFormat.*;

import dev.jeddy.asset.AssetPaths;
import dev.jeddy.compress.Checksums;
import dev.jeddy.compress.Zstd;
import dev.jeddy.compress.ZstdDecompressor;
import dev.jeddy.compress.ZstdDictionary;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * A memory-mapped {@code .jpak} archive (TASKS M4-03, {@code docs/formats/jpak.md}).
 *
 * <p>Opening maps the file and copies the TOC columns into primitive arrays (structure of arrays);
 * {@link #find(long)} is a binary search over the sorted ids. Entry data stays in the mapping: the
 * OS page cache does the IO, and {@link #read} decompresses straight from the mapping into the
 * caller's memory without intermediate copies or allocation.
 *
 * <p>Thread ownership: thread-safe for reads after {@link #open}; {@link #close()} must happen
 * after every reader is done (the asset manager's reference counts guarantee it).
 */
public final class Pak implements AutoCloseable {
    private final Path path;
    private final Arena arena;
    private final MemorySegment file;
    private final long[] ids;
    private final long[] offsets;
    private final int[] storedSizes;
    private final int[] rawSizes;
    private final int[] crcs;
    private final byte[] codecs;
    private final String[] names;
    private final int dictionaryIndex;
    private final boolean hasCrc;
    private volatile ZstdDictionary dictionary;

    private Pak(Path path, Arena arena, MemorySegment file) throws IOException {
        this.path = path;
        this.arena = arena;
        this.file = file;
        long size = file.byteSize();
        if (size < HEADER_SIZE || file.get(U32, 0) != MAGIC) {
            throw new IOException(path + " is not a .jpak file");
        }
        int version = Short.toUnsignedInt(file.get(U16, 4));
        if (version != VERSION) {
            throw new IOException(path + " has .jpak version " + version + ", expected " + VERSION);
        }
        int flags = Short.toUnsignedInt(file.get(U16, 6));
        int n = file.get(U32, 8);
        dictionaryIndex = file.get(U32, 12);
        long toc = file.get(U64, 16);
        long namesOffset = file.get(U64, 24);
        long fileSize = file.get(U64, 40);
        if (fileSize != size || n < 0 || toc != HEADER_SIZE || toc + tocSize(n) > size) {
            throw new IOException(path + " is truncated or corrupt (header)");
        }
        int tocCrc = Checksums.crc32c(file, toc, tocSize(n));
        if (tocCrc != file.get(U32, 48)) {
            throw new IOException(path + " has a corrupt table of contents (CRC mismatch)");
        }
        hasCrc = (flags & FLAG_CRC) != 0;
        ids = new long[n];
        offsets = new long[n];
        storedSizes = new int[n];
        rawSizes = new int[n];
        crcs = new int[n];
        codecs = new byte[n];
        long p = toc;
        MemorySegment.copy(file, U64, p, ids, 0, n);
        p += align(8L * n, 8);
        MemorySegment.copy(file, U64, p, offsets, 0, n);
        p += align(8L * n, 8);
        MemorySegment.copy(file, U32, p, storedSizes, 0, n);
        p += align(4L * n, 8);
        MemorySegment.copy(file, U32, p, rawSizes, 0, n);
        p += align(4L * n, 8);
        MemorySegment.copy(file, U32, p, crcs, 0, n);
        p += align(4L * n, 8);
        MemorySegment.copy(file, ValueLayout.JAVA_BYTE, p, codecs, 0, n);
        for (int i = 0; i < n; i++) {
            if (i > 0 && ids[i - 1] >= ids[i]) {
                throw new IOException(path + " has an unsorted table of contents");
            }
            if (offsets[i] < 0 || offsets[i] + Integer.toUnsignedLong(storedSizes[i]) > size) {
                throw new IOException(path + " entry " + i + " lies outside the file");
            }
            if (codecs[i] < CODEC_STORED || codecs[i] > CODEC_ZSTD_DICTIONARY) {
                throw new IOException(path + " entry " + i + " has unknown codec " + codecs[i]);
            }
            if (codecs[i] == CODEC_STORED && storedSizes[i] != rawSizes[i]) {
                throw new IOException(path + " entry " + i + " is stored with two sizes");
            }
        }
        if (dictionaryIndex < -1
                || dictionaryIndex >= n
                || (dictionaryIndex >= 0
                        && (ids[dictionaryIndex] != DICTIONARY_ID
                                || codecs[dictionaryIndex] != CODEC_STORED))) {
            throw new IOException(path + " has an invalid dictionary entry");
        }
        if ((flags & FLAG_NAMES) != 0 && namesOffset > 0) {
            names = new String[n];
            long q = namesOffset;
            for (int i = 0; i < n; i++) {
                int len = Short.toUnsignedInt(file.get(U16, q));
                byte[] utf8 = file.asSlice(q + 2, len).toArray(ValueLayout.JAVA_BYTE);
                names[i] = new String(utf8, StandardCharsets.UTF_8);
                q += 2 + len;
            }
        } else {
            names = null;
        }
    }

    /**
     * Maps and validates a pak (header, TOC CRC, entry bounds). Cold path.
     *
     * @throws IOException if the file cannot be read or is not a valid version-1 pak
     */
    public static Pak open(Path path) throws IOException {
        Arena arena = Arena.ofShared();
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            MemorySegment file = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size(), arena);
            return new Pak(path, arena, file);
        } catch (IOException | RuntimeException e) {
            arena.close();
            throw e;
        }
    }

    /** Returns the file this pak was opened from. */
    public Path path() {
        return path;
    }

    /** Returns the number of entries. */
    public int size() {
        return ids.length;
    }

    /** Returns the index of the entry with {@code id}, or -1. Allocates nothing. */
    public int find(long id) {
        int i = Arrays.binarySearch(ids, id);
        return i >= 0 ? i : -1;
    }

    /** Returns the index of the entry at the asset path {@code path}, or -1. */
    public int find(String path) {
        return find(AssetPaths.id(path));
    }

    /** Returns the id of entry {@code i}. */
    public long id(int i) {
        return ids[i];
    }

    /** Returns the file offset of entry {@code i}'s stored bytes. */
    public long offset(int i) {
        return offsets[i];
    }

    /** Returns the stored size of entry {@code i}. */
    public long storedSize(int i) {
        return Integer.toUnsignedLong(storedSizes[i]);
    }

    /** Returns the decompressed size of entry {@code i}. */
    public long rawSize(int i) {
        return Integer.toUnsignedLong(rawSizes[i]);
    }

    /** Returns the codec of entry {@code i} ({@link PakFormat#CODEC_STORED} ...). */
    public int codec(int i) {
        return codecs[i];
    }

    /** Returns the asset path of entry {@code i}, or {@code null} if the pak has no name table. */
    public String name(int i) {
        return names == null ? null : names[i];
    }

    /** Returns the whole mapped file (for zero-copy access to stored entries). */
    public MemorySegment mapping() {
        return file;
    }

    /** Returns entry {@code i}'s stored bytes as a slice of the mapping (allocates the slice). */
    public MemorySegment stored(int i) {
        return file.asSlice(offsets[i], storedSize(i));
    }

    /** Returns {@code true} if entry {@code i}'s stored bytes match its CRC (or it has none). */
    public boolean verify(int i) {
        return !hasCrc || Checksums.crc32c(file, offsets[i], storedSize(i)) == crcs[i];
    }

    /**
     * Writes entry {@code i}'s decompressed bytes to {@code dst[dstOffset, dstOffset +
     * rawSize(i))}: a straight copy for stored entries, a zstd decompression otherwise. With a
     * native {@code dst} it allocates nothing.
     *
     * @param decompressor the calling thread's context (one per worker)
     * @return the number of bytes written ({@link #rawSize(int)})
     */
    public long read(int i, MemorySegment dst, long dstOffset, ZstdDecompressor decompressor) {
        long raw = rawSize(i);
        switch (codecs[i]) {
            case CODEC_STORED -> MemorySegment.copy(file, offsets[i], dst, dstOffset, raw);
            case CODEC_ZSTD -> {
                decompressor.dictionary(null);
                checkSize(
                        i,
                        decompressor.decompress(
                                dst, dstOffset, raw, file, offsets[i], storedSize(i)));
            }
            case CODEC_ZSTD_DICTIONARY -> {
                decompressor.dictionary(dictionary());
                checkSize(
                        i,
                        decompressor.decompress(
                                dst, dstOffset, raw, file, offsets[i], storedSize(i)));
            }
            default ->
                    throw new IllegalStateException(
                            path + " entry " + i + " has unknown codec " + codecs[i]);
        }
        return raw;
    }

    private void checkSize(int i, long written) {
        if (written != rawSize(i)) {
            throw new IllegalStateException(
                    path
                            + " entry "
                            + i
                            + " decompressed to "
                            + written
                            + " bytes, expected "
                            + rawSize(i));
        }
    }

    /** Returns the pak's zstd dictionary, or {@code null} if it has none. */
    public ZstdDictionary dictionary() {
        ZstdDictionary d = dictionary;
        if (d == null && dictionaryIndex >= 0) {
            synchronized (this) {
                d = dictionary;
                if (d == null) {
                    d = ZstdDictionary.of(stored(dictionaryIndex), Zstd.DEFAULT_LEVEL);
                    dictionary = d;
                }
            }
        }
        return d;
    }

    /** Unmaps the file. Every reader must be done with it. */
    @Override
    public synchronized void close() {
        if (arena.scope().isAlive()) {
            if (dictionary != null) {
                dictionary.close();
            }
            arena.close();
        }
    }
}
