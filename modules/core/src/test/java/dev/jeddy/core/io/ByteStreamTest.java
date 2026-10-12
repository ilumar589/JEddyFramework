package dev.jeddy.core.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.Test;

class ByteStreamTest {

    @Test
    void scalarsRoundTripLittleEndian() {
        ByteWriter w = new ByteWriter(4);
        w.int8(-5);
        w.int16(-1234);
        w.int32(0x11223344);
        w.int64(0x0102030405060708L);
        w.float32(1.5f);
        w.float64(-2.25);
        w.bool(true);
        w.bool(false);
        w.string("héllo €\uD83D\uDE00"); // 1-, 2-, 3- and 4-byte sequences
        w.string(null);
        byte[] b = w.toBytes();
        // The int32 0x11223344 is stored least significant byte first.
        assertEquals((byte) 0x44, b[3]);
        assertEquals((byte) 0x11, b[6]);
        ByteReader r = new ByteReader(b);
        assertEquals(-5, r.int8());
        assertEquals(-1234, r.int16());
        assertEquals(0x11223344, r.int32());
        assertEquals(0x0102030405060708L, r.int64());
        assertEquals(1.5f, r.float32());
        assertEquals(-2.25, r.float64());
        assertTrue(r.bool());
        assertFalse(r.bool());
        assertEquals("héllo €\uD83D\uDE00", r.string());
        assertEquals("", r.string());
        assertFalse(r.hasRemaining());
    }

    @Test
    void bulkRoundTripAndIntoArrays() {
        ByteWriter w = new ByteWriter();
        int[] ints = {1, -2, 3};
        long[] longs = {1L << 40, -7};
        float[] floats = {0.5f, -1e-3f};
        boolean[] bools = {true, false, true};
        byte[] bytes = {9, 8, 7, 6};
        w.ints(ints);
        w.longs(longs);
        w.floats(floats, 1);
        w.bools(bools);
        w.bytes(bytes, 1, 2);
        ByteReader r = new ByteReader(w.toBytes());
        int[] i2 = new int[8];
        assertEquals(3, r.intsInto(i2));
        assertEquals(-2, i2[1]);
        assertArrayEquals(longs, r.longs());
        assertArrayEquals(new float[] {0.5f}, r.floats());
        assertArrayEquals(bools, r.bools());
        assertArrayEquals(new byte[] {8, 7}, r.bytes());
        assertEquals(0, r.remaining());
    }

    @Test
    void truncatedStreamsFailAtTheRead() {
        ByteWriter w = new ByteWriter();
        w.int32(7);
        w.string("abc");
        byte[] b = w.toBytes();
        ByteReader cut = new ByteReader(b, 0, b.length - 2);
        assertEquals(7, cut.int32());
        ByteReader.Truncated t = assertThrows(ByteReader.Truncated.class, cut::string);
        assertTrue(t.getMessage().contains("byte 6"), t.getMessage());
        // A count that claims more than the stream holds fails before any element is read.
        ByteWriter bad = new ByteWriter();
        bad.int32(1_000_000);
        assertThrows(ByteReader.Truncated.class, () -> new ByteReader(bad.toBytes()).ints());
        ByteWriter negative = new ByteWriter();
        negative.int32(-1);
        assertThrows(IllegalStateException.class, () -> new ByteReader(negative.toBytes()).ints());
    }

    @Test
    void hugeSizesFailWithoutOverflowing() {
        // 0x20000001 longs are 2^32 + 8 bytes: in int arithmetic that is 8, which the 8 bytes
        // that follow would satisfy, and the reader would then allocate a 4 GiB array.
        ByteWriter w = new ByteWriter();
        w.int32(0x2000_0001);
        w.int64(0);
        assertThrows(ByteReader.Truncated.class, () -> new ByteReader(w.toBytes()).longs());
        assertThrows(
                ByteReader.Truncated.class,
                () -> new ByteReader(w.toBytes()).longsInto(new long[1]));
        // A skip near Integer.MAX_VALUE from a non-zero position must not wrap around.
        ByteReader r = new ByteReader(w.toBytes());
        r.int32();
        assertThrows(ByteReader.Truncated.class, () -> r.skip(Integer.MAX_VALUE));
        assertEquals(8, r.remaining(), "a failed read moves nothing");
    }

    @Test
    void patchSliceAndSeek() {
        ByteWriter w = new ByteWriter();
        int lengthAt = w.size();
        w.int32(0);
        w.string("payload");
        w.patchInt32(lengthAt, w.size() - 4);
        w.zeros(3);
        ByteReader r = new ByteReader(w.toBytes());
        int n = r.int32();
        ByteReader inner = r.slice(n);
        assertEquals("payload", inner.string());
        assertFalse(inner.hasRemaining());
        assertEquals(3, r.remaining());
        r.seek(0);
        assertEquals(n, r.int32());
        assertThrows(IllegalArgumentException.class, () -> r.seek(1000));
    }

    @Test
    void aResetWriterAllocatesNothing() {
        ByteWriter w = new ByteWriter(16);
        int[] column = new int[1000];
        float[] floats = new float[1000];
        boolean[] flags = new boolean[1000];
        Runnable capture =
                () -> {
                    w.reset();
                    w.int32(column.length);
                    w.ints(column);
                    w.floats(floats);
                    w.bools(flags);
                    w.int64(-1);
                    w.string("Health");
                };
        capture.run(); // grows once
        int size = w.size();
        Allocations.assertNoAllocation("capture into a kept buffer", capture);
        assertEquals(size, w.size());
    }
}
