package dev.jeddy.asset.pak;

import static dev.jeddy.asset.pak.PakFormat.*;

import dev.jeddy.asset.AssetPaths;
import dev.jeddy.compress.Checksums;
import dev.jeddy.compress.Zstd;
import dev.jeddy.compress.ZstdCompressor;
import dev.jeddy.compress.ZstdDictionary;
import dev.jeddy.core.id.StrId;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@code .jpak} archive (TASKS M4-03, {@code docs/formats/jpak.md}). The cooker adds every
 * cooked asset, then {@link #write(Path)} sorts the table of contents by id and writes the file
 * atomically (a temporary file renamed over the target), so a running game never maps a
 * half-written pak.
 *
 * <p>Entries are compressed when added, so memory holds only the stored bytes. Entry data is laid
 * out in the order entries were added (the dictionary first): the cooker adds assets that load
 * together next to each other, which keeps reads sequential.
 *
 * <p>Thread ownership: one thread (tool code; not used by the runtime).
 */
public final class PakWriter implements AutoCloseable {
    /** How {@link #add} stores an entry. */
    public enum Compression {
        /**
         * zstd (with the dictionary for entries up to {@link #DICTIONARY_ENTRY_LIMIT} bytes, if
         * there is one), unless that saves less than 1/16 of the size; then stored. Already
         * compressed data (KTX2 with supercompression, audio) ends up stored.
         */
        AUTO,
        /** Stored as is (data the runtime maps directly). */
        STORE,
        /** One zstd frame without the dictionary. */
        ZSTD,
        /** One zstd frame with the dictionary; requires {@link #dictionary(byte[])}. */
        ZSTD_DICTIONARY
    }

    /** {@link Compression#AUTO} uses the dictionary only for entries up to this size. */
    public static final int DICTIONARY_ENTRY_LIMIT = 128 << 10;

    private record Entry(
            int order, String path, long id, int codec, byte[] stored, long rawSize, int crc) {}

    private final List<Entry> entries = new ArrayList<>();
    private final Map<Long, String> paths = new HashMap<>();
    private final ZstdCompressor compressor;
    private final int level;
    private final Arena arena = Arena.ofConfined();
    private MemorySegment src = MemorySegment.NULL;
    private MemorySegment dst = MemorySegment.NULL;
    private ZstdDictionary dictionary;
    private boolean names = true;
    private boolean crc = true;

    /** Creates a writer compressing at {@code level} ({@link Zstd#RELEASE_LEVEL} for shipping). */
    public PakWriter(int level) {
        this.level = level;
        compressor = new ZstdCompressor(level).checksum(true);
    }

    /** Includes the name table (default on; release paks may leave it out). */
    public PakWriter names(boolean enabled) {
        names = enabled;
        return this;
    }

    /** Stores a CRC32C per entry (default on). */
    public PakWriter crc(boolean enabled) {
        crc = enabled;
        return this;
    }

    /**
     * Uses multithreaded zstd for large entries ({@code 0} for single-threaded).
     *
     * @return {@code false} if the zstd library was built without threads
     */
    public boolean workers(int workers) {
        return compressor.workers(workers);
    }

    /**
     * Sets the pak's dictionary (from {@link ZstdDictionary#train}) and stores it as the entry
     * {@link PakFormat#DICTIONARY_ID}. Call before adding entries that use it, at most once.
     */
    public PakWriter dictionary(byte[] bytes) {
        if (dictionary != null) {
            throw new IllegalStateException("the pak already has a dictionary");
        }
        MemorySegment staged = staging(bytes.length);
        MemorySegment.copy(bytes, 0, staged, ValueLayout.JAVA_BYTE, 0, bytes.length);
        dictionary = ZstdDictionary.of(staged.asSlice(0, bytes.length), level);
        put("__jpak/dictionary", DICTIONARY_ID, CODEC_STORED, bytes.clone(), bytes.length);
        return this;
    }

    /** Adds an entry from a byte array. {@code path} is normalized ({@link AssetPaths}). */
    public PakWriter add(String path, byte[] data, Compression mode) {
        MemorySegment.copy(data, 0, staging(data.length), ValueLayout.JAVA_BYTE, 0, data.length);
        return addStaged(path, data.length, mode);
    }

    /** Adds an entry from memory (copied). */
    public PakWriter add(String path, MemorySegment data, Compression mode) {
        long size = data.byteSize();
        if (size > Integer.MAX_VALUE - 64) {
            throw new IllegalArgumentException(path + ": entries are limited to 2 GiB");
        }
        staging(size).asSlice(0, size).copyFrom(data);
        return addStaged(path, size, mode);
    }

    /** Adds the file at {@code file} under the asset path {@code path}. */
    public PakWriter add(String path, Path file, Compression mode) throws IOException {
        return add(path, Files.readAllBytes(file), mode);
    }

    /** Returns the number of entries added so far, including the dictionary. */
    public int size() {
        return entries.size();
    }

    private PakWriter addStaged(String assetPath, long size, Compression mode) {
        String path = AssetPaths.normalize(assetPath);
        long id = StrId.of(path);
        if (id == DICTIONARY_ID) {
            throw new IllegalArgumentException(path + " is reserved");
        }
        switch (mode) {
            case STORE -> put(path, id, CODEC_STORED, stagedBytes(src, size), size);
            case ZSTD -> putCompressed(path, id, size, false, false);
            case ZSTD_DICTIONARY -> {
                if (dictionary == null) {
                    throw new IllegalStateException(path + ": no dictionary set");
                }
                putCompressed(path, id, size, true, false);
            }
            case AUTO ->
                    putCompressed(
                            path,
                            id,
                            size,
                            dictionary != null && size <= DICTIONARY_ENTRY_LIMIT,
                            true);
        }
        return this;
    }

    private void putCompressed(
            String path, long id, long size, boolean useDictionary, boolean storeIfLarger) {
        long bound = Zstd.compressBound(size);
        ensureDst(bound);
        compressor.dictionary(useDictionary ? dictionary : null);
        long n = compressor.compress(dst, 0, bound, src, 0, size);
        if (storeIfLarger && (size < 64 || n > size - size / 16)) {
            put(path, id, CODEC_STORED, stagedBytes(src, size), size);
        } else {
            put(
                    path,
                    id,
                    useDictionary ? CODEC_ZSTD_DICTIONARY : CODEC_ZSTD,
                    stagedBytes(dst, n),
                    size);
        }
    }

    private void put(String path, long id, int codec, byte[] stored, long rawSize) {
        String previous = paths.putIfAbsent(id, path);
        if (previous != null) {
            throw new IllegalArgumentException(
                    previous.equals(path)
                            ? "duplicate entry " + path
                            : "StrId collision: " + previous + " and " + path);
        }
        if (rawSize > 0xFFFF_FFFFL || stored.length > Integer.MAX_VALUE - 64) {
            throw new IllegalArgumentException(path + " is too large for a version-1 pak");
        }
        int sum = crc ? Checksums.crc32c(stored) : 0;
        entries.add(new Entry(entries.size(), path, id, codec, stored, rawSize, sum));
    }

    private static byte[] stagedBytes(MemorySegment segment, long size) {
        return segment.asSlice(0, size).toArray(ValueLayout.JAVA_BYTE);
    }

    /** Returns the staging source buffer, grown to {@code size} bytes. */
    private MemorySegment staging(long size) {
        if (src.byteSize() < Math.max(1, size)) {
            src = arena.allocate(Math.max(size, src.byteSize() * 2), 64);
        }
        return src;
    }

    private void ensureDst(long size) {
        if (dst.byteSize() < size) {
            dst = arena.allocate(Math.max(size, dst.byteSize() * 2), 64);
        }
    }

    /**
     * Writes the pak to {@code target}, replacing it atomically where the file system allows.
     *
     * @throws IOException if writing fails; {@code target} is then left unchanged
     */
    public void write(Path target) throws IOException {
        Entry[] sorted = entries.toArray(new Entry[0]);
        Arrays.sort(sorted, (a, b) -> Long.compare(a.id, b.id));
        int n = sorted.length;
        int dictionaryIndex = -1;
        for (int i = 0; i < n; i++) {
            if (sorted[i].id == DICTIONARY_ID) {
                dictionaryIndex = i;
            }
        }

        byte[][] nameBytes = new byte[n][];
        long namesSize = 0;
        if (names) {
            for (int i = 0; i < n; i++) {
                nameBytes[i] = sorted[i].path.getBytes(StandardCharsets.UTF_8);
                if (nameBytes[i].length > 0xFFFF) {
                    throw new IllegalArgumentException("asset path too long: " + sorted[i].path);
                }
                namesSize += 2 + nameBytes[i].length;
            }
        }
        long tocSize = tocSize(n);
        long namesOffset = names ? HEADER_SIZE + tocSize : 0;
        long dataOffset = align(HEADER_SIZE + tocSize + namesSize, PAGE_ALIGNMENT);

        // Data in insertion order.
        long[] offsets = new long[n];
        long cursor = dataOffset;
        for (Entry e : entries) {
            long alignment = e.stored.length >= LARGE_ENTRY ? PAGE_ALIGNMENT : ENTRY_ALIGNMENT;
            cursor = align(cursor, alignment);
            offsets[e.order] = cursor;
            cursor += e.stored.length;
        }
        long fileSize = cursor;

        int metaSize = Math.toIntExact(dataOffset);
        ByteBuffer meta = ByteBuffer.allocate(metaSize).order(ByteOrder.LITTLE_ENDIAN);
        meta.position(HEADER_SIZE);
        for (Entry e : sorted) {
            meta.putLong(e.id);
        }
        pad(meta);
        for (Entry e : sorted) {
            meta.putLong(offsets[e.order]);
        }
        pad(meta);
        for (Entry e : sorted) {
            meta.putInt(e.stored.length);
        }
        pad(meta);
        for (Entry e : sorted) {
            meta.putInt((int) e.rawSize);
        }
        pad(meta);
        for (Entry e : sorted) {
            meta.putInt(e.crc);
        }
        pad(meta);
        for (Entry e : sorted) {
            meta.put((byte) e.codec);
        }
        pad(meta);
        if (meta.position() != HEADER_SIZE + tocSize) {
            throw new IllegalStateException("TOC size mismatch");
        }
        if (names) {
            for (byte[] name : nameBytes) {
                meta.putShort((short) name.length);
                meta.put(name);
            }
        }
        int tocCrc =
                Checksums.crc32c(MemorySegment.ofArray(meta.array()).asSlice(HEADER_SIZE, tocSize));

        meta.position(0);
        meta.putInt(MAGIC);
        meta.putShort((short) VERSION);
        meta.putShort((short) ((crc ? FLAG_CRC : 0) | (names ? FLAG_NAMES : 0)));
        meta.putInt(n);
        meta.putInt(dictionaryIndex);
        meta.putLong(HEADER_SIZE);
        meta.putLong(namesOffset);
        meta.putLong(dataOffset);
        meta.putLong(fileSize);
        meta.putInt(tocCrc);
        meta.position(0);

        Path dir = target.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, target.getFileName().toString(), ".tmp");
        try {
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                writeFully(ch, meta, 0);
                for (Entry e : entries) {
                    writeFully(ch, ByteBuffer.wrap(e.stored), offsets[e.order]);
                }
                if (ch.size() < fileSize) { // the last entry is empty and sits after padding
                    writeFully(ch, ByteBuffer.allocate(1), fileSize - 1);
                }
                ch.force(true);
            }
            try {
                Files.move(
                        tmp,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void pad(ByteBuffer b) {
        while ((b.position() & 7) != 0) {
            b.put((byte) 0);
        }
    }

    private static void writeFully(FileChannel ch, ByteBuffer b, long position) throws IOException {
        long p = position;
        while (b.hasRemaining()) {
            p += ch.write(b, p);
        }
    }

    /** Frees the compression context and buffers. */
    @Override
    public void close() {
        compressor.close();
        if (dictionary != null) {
            dictionary.close();
        }
        arena.close();
    }
}
