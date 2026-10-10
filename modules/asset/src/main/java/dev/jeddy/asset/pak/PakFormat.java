package dev.jeddy.asset.pak;

import dev.jeddy.core.id.StrId;

import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * Constants of the {@code .jpak} format, version 1 ({@code docs/formats/jpak.md}).
 *
 * <p>Thread ownership: constants.
 */
public final class PakFormat {
    private PakFormat() {}

    /** {@code "JPAK"} in file order. */
    public static final int MAGIC = 0x4B41504A;

    /** The format version this code reads and writes. */
    public static final int VERSION = 1;

    /** Header size; the TOC starts here. */
    public static final int HEADER_SIZE = 64;

    /** Flag: entries carry a CRC32C of their stored bytes. */
    public static final int FLAG_CRC = 1;

    /** Flag: a name table is present. */
    public static final int FLAG_NAMES = 2;

    /** Codec: bytes stored as is. */
    public static final int CODEC_STORED = 0;

    /** Codec: one zstd frame. */
    public static final int CODEC_ZSTD = 1;

    /** Codec: one zstd frame compressed with the pak's dictionary. */
    public static final int CODEC_ZSTD_DICTIONARY = 2;

    /** Alignment of every entry. */
    public static final int ENTRY_ALIGNMENT = 64;

    /** Alignment of the data section and of entries of at least {@link #LARGE_ENTRY} bytes. */
    public static final int PAGE_ALIGNMENT = 4096;

    /** Entries this large start on a page boundary. */
    public static final int LARGE_ENTRY = 1 << 20;

    /** Id of the entry that holds the pak's zstd dictionary. */
    public static final long DICTIONARY_ID = StrId.of("__jpak/dictionary");

    static final ValueLayout.OfInt U32 =
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    static final ValueLayout.OfLong U64 =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    static final ValueLayout.OfShort U16 =
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    static long align(long value, long alignment) {
        return (value + alignment - 1) & -alignment;
    }

    /** Returns the size of the TOC for {@code n} entries. */
    static long tocSize(int n) {
        return align(8L * n, 8) * 2 + align(4L * n, 8) * 3 + align(n, 8);
    }
}
