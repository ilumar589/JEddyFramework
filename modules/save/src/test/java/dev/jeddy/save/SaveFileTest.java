package dev.jeddy.save;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.core.io.ByteReader;
import dev.jeddy.core.io.ByteWriter;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

class SaveFileTest {
    static final int GAME = SaveFile.tag("GAME");
    static final int FOG = SaveFile.tag("FOG ");

    /** A capture with two chunks and two meta entries; the fog is compressible. */
    static SaveWriter capture(SaveWriter w, int fogCells) {
        w.reset();
        w.meta("title", "Crypt, 2 rounds");
        w.meta("level", "levels/crypt");
        ByteWriter g = w.chunk(GAME, 3);
        g.float64(12.5);
        g.int32(7);
        g.string("turn-based");
        ByteWriter f = w.chunk(FOG, 1);
        long[] bits = new long[fogCells / 64];
        Arrays.fill(bits, 0, bits.length / 3, -1L);
        f.longs(bits);
        w.finish();
        return w;
    }

    @Test
    void tagsAreFourPrintableCharacters() {
        assertEquals("GAME", SaveFile.tagName(GAME));
        assertEquals("JSAV", SaveFile.tagName(SaveFile.MAGIC));
        assertEquals((byte) 'J', (byte) SaveFile.MAGIC);
        assertThrows(IllegalArgumentException.class, () -> SaveFile.tag("TOOLONG"));
        assertThrows(IllegalArgumentException.class, () -> SaveFile.tag("A\nBC"));
        assertEquals("????", SaveFile.tagName(0x01020304));
    }

    @Test
    void encodeAndReadRoundTrip() throws Exception {
        SaveWriter w = capture(new SaveWriter(), 64 * 128);
        assertEquals(2, w.chunkCount());
        byte[] file = SaveFile.encode(w, SaveFile.DEFAULT_LEVEL);
        assertEquals((byte) 'J', file[0]);
        assertEquals((byte) 'V', file[3]);
        assertTrue(file.length < SaveFile.HEADER_BYTES + w.payloadSize(), "compressed");
        SaveFile f = SaveFile.read(file);
        assertEquals(1, f.formatVersion());
        assertEquals("Crypt, 2 rounds", f.meta().get("title"));
        assertEquals("levels/crypt", f.meta().get("level"));
        assertEquals(2, f.chunks().size());
        SaveFile.Chunk game = f.chunk(GAME);
        assertNotNull(game);
        assertEquals(3, game.version());
        assertEquals("GAME", game.tagName());
        assertEquals(12.5, game.data().float64());
        assertEquals(7, game.data().int32());
        assertEquals("turn-based", game.data().string());
        assertFalse(game.data().hasRemaining(), "a chunk's reader ends at its length");
        SaveFile.Chunk fog = f.chunk(FOG);
        assertEquals(128, fog.data().longs().length);
        assertNull(f.chunk(SaveFile.tag("NONE")));
        // Uncompressed works too and marks the flag off.
        byte[] plain = SaveFile.encode(w, 0);
        assertEquals(0, new ByteReader(plain, 6, 2).int16() & SaveFile.FLAG_ZSTD);
        assertEquals("turn-based", readString(SaveFile.read(plain).chunk(GAME)));
    }

    private static String readString(SaveFile.Chunk c) {
        c.data().float64();
        c.data().int32();
        return c.data().string();
    }

    @Test
    void peekReadsOnlyTheMeta() throws Exception {
        SaveWriter w = capture(new SaveWriter(), 64 * 4);
        byte[] file = SaveFile.encode(w, SaveFile.DEFAULT_LEVEL);
        SaveFile peeked = SaveFile.peek(file);
        assertEquals("levels/crypt", peeked.meta().get("level"));
        assertTrue(peeked.chunks().isEmpty());
        // The meta block is readable from the header and the block alone.
        int metaBytes = new ByteReader(file, 12, 4).int32();
        assertEquals(0, metaBytes & 7, "padded to 8");
        byte[] prefix = Arrays.copyOf(file, SaveFile.HEADER_BYTES + metaBytes);
        assertEquals("Crypt, 2 rounds", SaveFile.peek(prefix).meta().get("title"));
    }

    @Test
    void chunksArePaddedToEightBytes() {
        SaveWriter w = new SaveWriter();
        w.chunk(GAME, 1).int8(1); // 1 byte of data
        w.chunk(FOG, 1).int32(2); // starts at 16 + 8
        w.finish();
        byte[] p = Arrays.copyOf(w.payloadBuffer(), w.payloadSize());
        ByteReader r = new ByteReader(p);
        assertEquals(GAME, r.int32());
        assertEquals(1, r.int32());
        assertEquals(1, r.int32(), "the patched length");
        r.int32();
        assertEquals(1, r.int8());
        r.skip(7);
        assertEquals(FOG, r.int32());
        assertEquals(24, r.position() - 4);
        assertEquals(48, w.payloadSize());
    }

    @Test
    void damageIsReportedByWhatItBroke() throws Exception {
        SaveWriter w = capture(new SaveWriter(), 64 * 8);
        byte[] file = SaveFile.encode(w, SaveFile.DEFAULT_LEVEL);
        // Not a save at all.
        byte[] notOne = file.clone();
        notOne[0] = 'X';
        assertMessage(notOne, "not a save file");
        assertMessage(new byte[10], "not a save");
        // A flipped header byte: the header checksum catches it.
        byte[] header = file.clone();
        header[20] ^= 1;
        assertMessage(header, "header checksum");
        // A newer format version (header re-signed so only the version differs).
        byte[] newer = file.clone();
        newer[4] = 9;
        resignHeader(newer);
        assertMessage(newer, "newer than this build");
        // A flipped payload byte: the payload checksum.
        byte[] payload = file.clone();
        payload[file.length - 1] ^= 0x40;
        assertMessage(payload, "payload checksum");
        // Truncated after the meta block.
        byte[] cut = Arrays.copyOf(file, SaveFile.HEADER_BYTES + 56);
        assertMessage(cut, "overruns the file");
        // A chunk that claims more than the payload has (uncompressed so the bytes are visible).
        byte[] plain = SaveFile.encode(w, 0);
        int metaBytes = new ByteReader(plain, 12, 4).int32();
        int lengthAt = SaveFile.HEADER_BYTES + metaBytes + 8;
        plain[lengthAt] = (byte) 0xFF;
        plain[lengthAt + 1] = (byte) 0xFF;
        resignPayload(plain);
        assertMessage(plain, "claims");
    }

    private static void assertMessage(byte[] file, String fragment) {
        SaveFormatException e = assertThrows(SaveFormatException.class, () -> SaveFile.read(file));
        assertTrue(e.getMessage().contains(fragment), fragment + " in: " + e.getMessage());
    }

    static void resignHeader(byte[] file) {
        byte[] z = Arrays.copyOf(file, SaveFile.HEADER_BYTES);
        z[36] = z[37] = z[38] = z[39] = 0;
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(z, 0, SaveFile.HEADER_BYTES);
        int v = (int) crc.getValue();
        file[36] = (byte) v;
        file[37] = (byte) (v >>> 8);
        file[38] = (byte) (v >>> 16);
        file[39] = (byte) (v >>> 24);
    }

    static void resignPayload(byte[] file) {
        int metaBytes = new ByteReader(file, 12, 4).int32();
        int at = SaveFile.HEADER_BYTES + metaBytes;
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(file, at, file.length - at);
        int v = (int) crc.getValue();
        file[32] = (byte) v;
        file[33] = (byte) (v >>> 8);
        file[34] = (byte) (v >>> 16);
        file[35] = (byte) (v >>> 24);
        resignHeader(file);
    }

    @Test
    void aSecondCaptureAllocatesNothing() {
        SaveWriter w = new SaveWriter(64);
        long[] bits = new long[256];
        float[] positions = new float[900];
        Runnable capture =
                () -> {
                    w.reset();
                    w.meta("title", "Crypt");
                    w.chunk(GAME, 1).floats(positions);
                    w.chunk(FOG, 1).longs(bits);
                    w.finish();
                };
        capture.run();
        int size = w.payloadSize();
        Allocations.assertNoAllocation("save capture", capture);
        assertEquals(size, w.payloadSize());
    }
}
