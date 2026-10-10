package dev.jeddy.asset.format;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/** Little-endian unaligned layouts and bounds checks shared by the readers. */
final class Bytes {
    private Bytes() {}

    static final ValueLayout.OfShort U16 =
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    static final ValueLayout.OfInt U32 =
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    static final ValueLayout.OfLong U64 =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    static final ValueLayout.OfFloat F32 =
            ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    static long align(long value, long alignment) {
        return (value + alignment - 1) & -alignment;
    }

    /** Throws unless {@code [offset, offset + length)} lies inside {@code data}. */
    static void checkRange(MemorySegment data, long offset, long length, String what)
            throws AssetFormatException {
        if (offset < 0 || length < 0 || offset > data.byteSize() - length) {
            throw new AssetFormatException(
                    what
                            + " ["
                            + offset
                            + ", +"
                            + length
                            + ") lies outside the "
                            + data.byteSize()
                            + "-byte file");
        }
    }
}
