package dev.jeddy.asset.format;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a {@code .jfont} file ({@link FontData}): the cooker's font importer and tests use it.
 *
 * <p>Thread ownership: one thread; cold path.
 */
public final class FontWriter {
    private record Glyph(
            int codePoint,
            float advance,
            float bearingX,
            float bearingY,
            int width,
            int height,
            int atlasX,
            int atlasY) {}

    private record Kerning(int left, int right, float adjust) {}

    private float emSize = 48;
    private float ascent;
    private float descent;
    private float lineHeight;
    private float sdfRange = 8;
    private int atlasWidth;
    private int atlasHeight;
    private final List<Glyph> glyphs = new ArrayList<>();
    private final List<Kerning> kerning = new ArrayList<>();

    /** Creates an empty writer. */
    public FontWriter() {}

    /** Sets the em size, line metrics and distance range, all in pixels at the em size. */
    public FontWriter metrics(
            float emSize, float ascent, float descent, float lineHeight, float sdfRange) {
        this.emSize = emSize;
        this.ascent = ascent;
        this.descent = descent;
        this.lineHeight = lineHeight;
        this.sdfRange = sdfRange;
        return this;
    }

    /** Sets the atlas size in texels. */
    public FontWriter atlas(int width, int height) {
        this.atlasWidth = width;
        this.atlasHeight = height;
        return this;
    }

    /** Adds a glyph (see {@link FontData} for the meaning of each value). */
    public FontWriter glyph(
            int codePoint,
            float advance,
            float bearingX,
            float bearingY,
            int width,
            int height,
            int atlasX,
            int atlasY) {
        if (width < 0 || width > 0xFFFF || height < 0 || height > 0xFFFF) {
            throw new IllegalArgumentException("glyph size out of range");
        }
        if (atlasX < 0 || atlasX > 0xFFFF || atlasY < 0 || atlasY > 0xFFFF) {
            throw new IllegalArgumentException("atlas position out of range");
        }
        glyphs.add(
                new Glyph(codePoint, advance, bearingX, bearingY, width, height, atlasX, atlasY));
        return this;
    }

    /** Adds a kerning pair (code points); zero adjustments are dropped. */
    public FontWriter kerning(int left, int right, float adjust) {
        if (adjust != 0) {
            kerning.add(new Kerning(left, right, adjust));
        }
        return this;
    }

    /** Returns the file. Glyphs and kerning pairs are sorted; duplicates are an error. */
    public byte[] toBytes() {
        glyphs.sort((a, b) -> Integer.compare(a.codePoint, b.codePoint));
        kerning.sort(
                (a, b) ->
                        Long.compare(
                                FontData.kerningKey(a.left, a.right),
                                FontData.kerningKey(b.left, b.right)));
        for (int i = 1; i < glyphs.size(); i++) {
            if (glyphs.get(i).codePoint == glyphs.get(i - 1).codePoint) {
                throw new IllegalStateException("duplicate glyph " + glyphs.get(i).codePoint);
            }
        }
        for (int i = 1; i < kerning.size(); i++) {
            Kerning a = kerning.get(i - 1);
            Kerning b = kerning.get(i);
            if (a.left == b.left && a.right == b.right) {
                throw new IllegalStateException("duplicate kerning pair " + a.left + "," + a.right);
            }
        }
        int glyphsOffset = FontData.HEADER_BYTES;
        int kerningOffset = glyphsOffset + glyphs.size() * FontData.GLYPH_BYTES;
        int total = kerningOffset + kerning.size() * FontData.KERNING_BYTES;
        ByteBuffer b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0, FontData.MAGIC);
        b.putShort(4, (short) FontData.VERSION);
        b.putShort(6, (short) 0);
        b.putFloat(8, emSize);
        b.putFloat(12, ascent);
        b.putFloat(16, descent);
        b.putFloat(20, lineHeight);
        b.putFloat(24, sdfRange);
        b.putInt(28, atlasWidth);
        b.putInt(32, atlasHeight);
        b.putInt(36, glyphs.size());
        b.putInt(40, kerning.size());
        b.putInt(44, glyphsOffset);
        b.putInt(48, kerningOffset);
        for (int i = 0; i < glyphs.size(); i++) {
            Glyph g = glyphs.get(i);
            int o = glyphsOffset + i * FontData.GLYPH_BYTES;
            b.putInt(o, g.codePoint);
            b.putFloat(o + 4, g.advance);
            b.putFloat(o + 8, g.bearingX);
            b.putFloat(o + 12, g.bearingY);
            b.putShort(o + 16, (short) g.width);
            b.putShort(o + 18, (short) g.height);
            b.putShort(o + 20, (short) g.atlasX);
            b.putShort(o + 22, (short) g.atlasY);
        }
        for (int i = 0; i < kerning.size(); i++) {
            Kerning k = kerning.get(i);
            int o = kerningOffset + i * FontData.KERNING_BYTES;
            b.putInt(o, k.left);
            b.putInt(o + 4, k.right);
            b.putFloat(o + 8, k.adjust);
        }
        return b.array();
    }
}
