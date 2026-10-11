package dev.jeddy.asset.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;

/** The {@code .jfont} format: round trip, lookups, kerning, fallbacks (TASKS M13-02). */
class FontDataTest {
    private static FontData sample() throws Exception {
        byte[] bytes =
                new FontWriter()
                        .metrics(48, 40, 10, 56, 8)
                        .atlas(256, 128)
                        .glyph('b', 26.5f, 1, 38, 30, 48, 100, 0)
                        .glyph('a', 24, 0.5f, 28, 28, 36, 0, 0)
                        .glyph('?', 20, 1, 37, 24, 44, 40, 0)
                        .glyph(0x20AC, 30, 2, 36, 32, 46, 130, 0)
                        .glyph(' ', 12, 0, 0, 0, 0, 0, 0)
                        .kerning('a', 'b', -1.5f)
                        .kerning('b', 'a', 0) // dropped
                        .kerning(0x20AC, 'a', 2)
                        .toBytes();
        return FontData.read(MemorySegment.ofArray(bytes));
    }

    @Test
    void roundTripsMetricsGlyphsAndKerning() throws Exception {
        FontData f = sample();
        assertEquals(48, f.emSize());
        assertEquals(40, f.ascent());
        assertEquals(10, f.descent());
        assertEquals(56, f.lineHeight());
        assertEquals(8, f.sdfRange());
        assertEquals(256, f.atlasWidth());
        assertEquals(128, f.atlasHeight());
        assertEquals(5, f.glyphCount());
        assertEquals(2, f.kerningCount(), "the zero pair is dropped");
        int b = f.glyphIndex('b');
        assertEquals('b', f.codePoint(b));
        assertEquals(26.5f, f.advance(b));
        assertEquals(1, f.bearingX(b));
        assertEquals(38, f.bearingY(b));
        assertEquals(30, f.width(b));
        assertEquals(48, f.height(b));
        assertEquals(100, f.atlasX(b));
        assertEquals(0, f.atlasY(b));
        // Sorted by code point: space, ?, a, b, euro.
        assertEquals(0, f.glyphIndex(' '));
        assertEquals(4, f.glyphIndex(0x20AC));
        assertEquals(-1.5f, f.kerning('a', 'b'));
        assertEquals(0, f.kerning('b', 'a'));
        assertEquals(2, f.kerning(0x20AC, 'a'));
        assertEquals(0, f.kerning('z', 'z'));
    }

    @Test
    void fallsBackToTheQuestionMark() throws Exception {
        FontData f = sample();
        assertEquals(-1, f.glyphIndex('z'));
        assertEquals(f.glyphIndex('?'), f.glyphOrFallback('z'));
        assertEquals(f.glyphIndex('a'), f.glyphOrFallback('a'));
        assertEquals(f.glyphIndex('?'), f.glyphOrFallback(0x1F600));
    }

    @Test
    void rejectsBadFiles() {
        assertThrows(
                AssetFormatException.class,
                () -> FontData.read(MemorySegment.ofArray(new byte[16])));
        byte[] bad = new FontWriter().metrics(48, 40, 10, 56, 8).toBytes();
        bad[0] = 0;
        assertThrows(AssetFormatException.class, () -> FontData.read(MemorySegment.ofArray(bad)));
        assertThrows(
                IllegalStateException.class,
                () ->
                        new FontWriter()
                                .glyph('a', 1, 0, 0, 1, 1, 0, 0)
                                .glyph('a', 1, 0, 0, 1, 1, 0, 0)
                                .toBytes());
    }

    @Test
    void lookupsAllocateNothing() throws Exception {
        FontData f = sample();
        int[] sink = new int[1];
        Allocations.assertNoAllocation(
                "glyph lookups",
                () -> {
                    sink[0] += f.glyphIndex('a') + f.glyphIndex(0x20AC) + f.glyphOrFallback('z');
                    sink[0] += (int) f.kerning('a', 'b');
                });
    }
}
