package dev.jeddy.save;

import dev.jeddy.compress.Zstd;
import dev.jeddy.compress.ZstdCompressor;
import dev.jeddy.compress.ZstdDecompressor;
import dev.jeddy.core.io.ByteReader;
import dev.jeddy.core.io.ByteWriter;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32C;

/**
 * A {@code .jsave} file in memory (DESIGN §21, D116; [docs/formats/jsave.md]): the meta entries and
 * the chunks, read by {@link #read} from the bytes of a file, or encoded by {@link #encode} from a
 * {@link SaveWriter}. The layout, all little-endian:
 *
 * <pre>
 *   0  u32  magic 'JSAV'
 *   4  u16  format version (1)
 *   6  u16  flags: bit 0 = the payload is one zstd frame
 *   8  u32  chunk count
 *  12  u32  meta block bytes (padded to 8)
 *  16  u64  payload bytes, uncompressed
 *  24  u64  payload bytes as stored
 *  32  u32  CRC-32C of the stored payload
 *  36  u32  CRC-32C of bytes 0..48 with this field zero
 *  40  u64  reserved (0)
 *  48       meta block: u32 pair count, then (string key, string value)*, zero padding
 *           payload: chunks of [u32 tag][u32 version][u32 length][u32 reserved] + bytes,
 *           each padded to 8; compressed as one frame when bit 0 of the flags is set
 * </pre>
 *
 * {@link #peek} reads the header and the meta block only: a slot list costs one small read per
 * file. Every size is checked against the file before it is used, both checksums must match and a
 * newer format version is refused, so a damaged or foreign file fails with a {@link
 * SaveFormatException} that says why.
 *
 * <p>Thread ownership: any single thread per instance; {@link #encode} runs on the save job's
 * virtual thread, {@link #read} on the game thread during a load.
 */
public final class SaveFile {
    /** The bytes {@code J S A V} as a little-endian {@code u32}. */
    public static final int MAGIC = tag("JSAV");

    /** The format this build writes and the newest it reads. */
    public static final int FORMAT_VERSION = 1;

    public static final int HEADER_BYTES = 48;

    /** Flag bit 0: the payload is compressed as one zstd frame. */
    public static final int FLAG_ZSTD = 1;

    /** The zstd level the jobs use: fast, and a save compresses four to ten times at it. */
    public static final int DEFAULT_LEVEL = Zstd.DEFAULT_LEVEL;

    private static final long MAX_PAYLOAD = 1L << 31; // a save larger than this is a bug

    /** One chunk: its tag, its version and a reader over its bytes. */
    public record Chunk(int tag, int version, ByteReader data) {
        /** The tag as its four characters. */
        public String tagName() {
            return SaveFile.tagName(tag);
        }
    }

    private final int formatVersion;
    private final Map<String, String> meta;
    private final List<Chunk> chunks;

    private SaveFile(int formatVersion, Map<String, String> meta, List<Chunk> chunks) {
        this.formatVersion = formatVersion;
        this.meta = Collections.unmodifiableMap(meta);
        this.chunks = Collections.unmodifiableList(chunks);
    }

    /** A four-character chunk tag as its {@code u32} ({@code tag("COMP")}). */
    public static int tag(String name) {
        if (name.length() != 4) {
            throw new IllegalArgumentException("a tag has four characters: " + name);
        }
        int t = 0;
        for (int i = 0; i < 4; i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                throw new IllegalArgumentException("a tag is printable ASCII: " + name);
            }
            t |= c << 8 * i;
        }
        return t;
    }

    /** The four characters of {@code tag}; a byte outside printable ASCII shows as {@code ?}. */
    public static String tagName(int tag) {
        char[] c = new char[4];
        for (int i = 0; i < 4; i++) {
            int b = tag >>> 8 * i & 0xFF;
            c[i] = b < 0x20 || b > 0x7E ? '?' : (char) b;
        }
        return new String(c);
    }

    /** The format version the file was written with. */
    public int formatVersion() {
        return formatVersion;
    }

    /** The meta entries in file order. */
    public Map<String, String> meta() {
        return meta;
    }

    /** The chunks in file order. */
    public List<Chunk> chunks() {
        return chunks;
    }

    /** The first chunk tagged {@code tag}, or {@code null}. */
    public Chunk chunk(int tag) {
        for (Chunk c : chunks) {
            if (c.tag == tag) {
                return c;
            }
        }
        return null;
    }

    // ---- Encoding -----------------------------------------------------------------------------

    /**
     * Encodes {@code writer}'s capture as a complete file image, compressing the payload at zstd
     * level {@code level} (0 leaves it uncompressed). Allocates: the job's half of a save.
     */
    public static byte[] encode(SaveWriter writer, int level) {
        writer.finish();
        return encode(
                writer.metaBuffer(),
                writer.metaSize(),
                writer.payloadBuffer(),
                writer.payloadSize(),
                writer.chunkCount(),
                level);
    }

    /** {@link #encode(SaveWriter, int)} over copied buffers (what a {@link SaveJob} holds). */
    static byte[] encode(
            byte[] meta, int metaSize, byte[] payload, int payloadSize, int chunkCount, int level) {
        int metaPadded = metaSize + ((8 - (metaSize & 7)) & 7);
        byte[] stored;
        int storedSize;
        int flags = 0;
        if (level > 0 && payloadSize > 0) {
            stored = compress(payload, payloadSize, level);
            storedSize = stored.length;
            flags |= FLAG_ZSTD;
        } else {
            stored = payload;
            storedSize = payloadSize;
        }
        ByteWriter w = new ByteWriter(HEADER_BYTES + metaPadded + storedSize);
        w.int32(MAGIC);
        w.int16(FORMAT_VERSION);
        w.int16(flags);
        w.int32(chunkCount);
        w.int32(metaPadded);
        w.int64(payloadSize);
        w.int64(storedSize);
        w.int32(crc(stored, 0, storedSize));
        w.int32(0); // the header CRC, patched below
        w.int64(0);
        w.raw(meta, 0, metaSize);
        w.zeros(metaPadded - metaSize);
        w.raw(stored, 0, storedSize);
        byte[] file = w.toBytes();
        int headerCrc = crc(file, 0, HEADER_BYTES);
        file[36] = (byte) headerCrc;
        file[37] = (byte) (headerCrc >>> 8);
        file[38] = (byte) (headerCrc >>> 16);
        file[39] = (byte) (headerCrc >>> 24);
        return file;
    }

    private static byte[] compress(byte[] src, int n, int level) {
        long bound = Zstd.compressBound(n);
        try (Arena arena = Arena.ofConfined();
                ZstdCompressor c = new ZstdCompressor(level)) {
            MemorySegment in = arena.allocate(n, 8);
            MemorySegment.copy(src, 0, in, ValueLayout.JAVA_BYTE, 0, n);
            MemorySegment out = arena.allocate(bound, 8);
            long produced = c.compress(out, in);
            byte[] result = new byte[(int) produced];
            MemorySegment.copy(out, ValueLayout.JAVA_BYTE, 0, result, 0, (int) produced);
            return result;
        }
    }

    private static byte[] decompress(byte[] file, int offset, int n, long expected)
            throws SaveFormatException {
        try (Arena arena = Arena.ofConfined();
                ZstdDecompressor d = new ZstdDecompressor()) {
            MemorySegment in = arena.allocate(n, 8);
            MemorySegment.copy(file, offset, in, ValueLayout.JAVA_BYTE, 0, n);
            long size = Zstd.frameContentSize(in);
            if (size != expected) {
                throw new SaveFormatException(
                        "payload frame holds " + size + " bytes, the header says " + expected);
            }
            MemorySegment out = arena.allocate(expected, 8);
            long produced = d.decompress(out, in);
            if (produced != expected) {
                throw new SaveFormatException(
                        "payload decompressed to " + produced + " bytes, expected " + expected);
            }
            byte[] result = new byte[(int) expected];
            MemorySegment.copy(out, ValueLayout.JAVA_BYTE, 0, result, 0, (int) expected);
            return result;
        } catch (RuntimeException e) {
            throw new SaveFormatException("payload is not a zstd frame: " + e.getMessage(), e);
        }
    }

    private static int crc(byte[] a, int offset, int n) {
        CRC32C crc = new CRC32C();
        crc.update(a, offset, n);
        return (int) crc.getValue();
    }

    // ---- Reading ------------------------------------------------------------------------------

    /** Reads a whole file. */
    public static SaveFile read(Path path) throws IOException {
        return read(Files.readAllBytes(path));
    }

    /** Reads a file image. */
    public static SaveFile read(byte[] file) throws SaveFormatException {
        return parse(file, true);
    }

    /** Reads the header and the meta block of a file; {@link #chunks} is empty. */
    public static SaveFile peek(Path path) throws IOException {
        // The slot list reads the smallest prefix that can hold the meta block: the header first,
        // then the block's bytes.
        try (var in = Files.newInputStream(path)) {
            byte[] header = in.readNBytes(HEADER_BYTES);
            if (header.length < HEADER_BYTES) {
                throw new SaveFormatException("file is " + header.length + " bytes; not a save");
            }
            int metaBytes = new ByteReader(header, 12, 4).int32();
            if (metaBytes < 4 || metaBytes > 1 << 20) {
                throw new SaveFormatException("meta block of " + metaBytes + " bytes");
            }
            byte[] meta = in.readNBytes(metaBytes);
            byte[] prefix = new byte[HEADER_BYTES + meta.length];
            System.arraycopy(header, 0, prefix, 0, HEADER_BYTES);
            System.arraycopy(meta, 0, prefix, HEADER_BYTES, meta.length);
            return parse(prefix, false);
        }
    }

    /** {@link #peek(Path)} over a file image. */
    public static SaveFile peek(byte[] file) throws SaveFormatException {
        return parse(file, false);
    }

    private static SaveFile parse(byte[] file, boolean payloadToo) throws SaveFormatException {
        if (file.length < HEADER_BYTES) {
            throw new SaveFormatException("file is " + file.length + " bytes; not a save");
        }
        ByteReader h = new ByteReader(file, 0, HEADER_BYTES);
        int magic = h.int32();
        if (magic != MAGIC) {
            throw new SaveFormatException(
                    "not a save file (magic " + tagName(magic) + ", expected JSAV)");
        }
        int version = h.int16() & 0xFFFF;
        int flags = h.int16() & 0xFFFF;
        int chunkCount = h.int32();
        int metaBytes = h.int32();
        long payloadBytes = h.int64();
        long storedBytes = h.int64();
        int payloadCrc = h.int32();
        int headerCrc = h.int32();
        byte[] zeroed = Arrays.copyOf(file, HEADER_BYTES);
        zeroed[36] = zeroed[37] = zeroed[38] = zeroed[39] = 0;
        if (crc(zeroed, 0, HEADER_BYTES) != headerCrc) {
            throw new SaveFormatException("header checksum does not match; the file is damaged");
        }
        if (version > FORMAT_VERSION) {
            throw new SaveFormatException(
                    "save format "
                            + version
                            + " is newer than this build reads ("
                            + FORMAT_VERSION
                            + ")");
        }
        if (metaBytes < 4 || (metaBytes & 7) != 0 || HEADER_BYTES + metaBytes > file.length) {
            throw new SaveFormatException(
                    "meta block of " + metaBytes + " bytes at 48 overruns the file");
        }
        if (chunkCount < 0
                || payloadBytes < 0
                || storedBytes < 0
                || payloadBytes > MAX_PAYLOAD
                || storedBytes > MAX_PAYLOAD
                || (long) chunkCount * SaveWriter.CHUNK_HEADER_BYTES > payloadBytes) {
            // Every chunk has a 16-byte header, so a count the payload cannot hold is damage (and
            // would otherwise size the chunk list before anything checks it).
            throw new SaveFormatException("header sizes are out of range");
        }
        Map<String, String> meta = new LinkedHashMap<>();
        ByteReader m = new ByteReader(file, HEADER_BYTES, metaBytes);
        int pairs = m.int32();
        try {
            for (int i = 0; i < pairs; i++) {
                meta.put(m.string(), m.string());
            }
        } catch (IllegalStateException e) {
            throw new SaveFormatException("meta block is damaged: " + e.getMessage(), e);
        }
        if (!payloadToo) {
            return new SaveFile(version, meta, new ArrayList<>());
        }
        int payloadAt = HEADER_BYTES + metaBytes;
        if (payloadAt + storedBytes > file.length) {
            throw new SaveFormatException(
                    "payload of "
                            + storedBytes
                            + " bytes at "
                            + payloadAt
                            + " overruns the file ("
                            + file.length
                            + " bytes)");
        }
        if (crc(file, payloadAt, (int) storedBytes) != payloadCrc) {
            throw new SaveFormatException("payload checksum does not match; the file is damaged");
        }
        byte[] payload;
        int payloadOffset;
        if ((flags & FLAG_ZSTD) != 0) {
            payload = decompress(file, payloadAt, (int) storedBytes, payloadBytes);
            payloadOffset = 0;
        } else {
            if (storedBytes != payloadBytes) {
                throw new SaveFormatException("uncompressed payload sizes differ");
            }
            payload = file;
            payloadOffset = payloadAt;
        }
        List<Chunk> chunks = new ArrayList<>(chunkCount);
        ByteReader p = new ByteReader(payload, payloadOffset, (int) payloadBytes);
        for (int i = 0; i < chunkCount; i++) {
            if (p.remaining() < SaveWriter.CHUNK_HEADER_BYTES) {
                throw new SaveFormatException(
                        "chunk " + i + " of " + chunkCount + " has no header (payload is short)");
            }
            int tag = p.int32();
            int chunkVersion = p.int32();
            int length = p.int32();
            p.int32(); // reserved
            if (length < 0 || length > p.remaining()) {
                throw new SaveFormatException(
                        "chunk "
                                + tagName(tag)
                                + " claims "
                                + length
                                + " bytes, "
                                + p.remaining()
                                + " remain");
            }
            chunks.add(new Chunk(tag, chunkVersion, p.slice(length)));
            int pad = (8 - (length & 7)) & 7;
            if (pad > p.remaining()) {
                throw new SaveFormatException(
                        "chunk " + tagName(tag) + " is not padded to 8 bytes (payload is short)");
            }
            p.skip(pad);
        }
        return new SaveFile(version, meta, chunks);
    }
}
