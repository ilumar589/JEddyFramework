package dev.jeddy.asset.format;

import static dev.jeddy.asset.format.Bytes.F32;
import static dev.jeddy.asset.format.Bytes.U16;
import static dev.jeddy.asset.format.Bytes.U32;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;

/**
 * A cooked font ({@code .jfont}, TASKS M13-02, DESIGN §18): the metrics of a signed-distance-field
 * glyph atlas. The atlas itself is a separate R8 KTX2 texture next to the font; this file says
 * where each glyph sits in it and how glyphs advance and kern.
 *
 * <p>All lengths are in pixels at the <em>em size</em> the glyphs were rasterized at ({@link
 * #emSize}, 48 by default). Drawing text at another size scales them by {@code size / emSize}.
 * Glyph rectangles include the distance field's padding, so a glyph's quad is {@code width ×
 * height} pixels placed at {@code (penX + bearingX, baseline - bearingY)} (y down), and its atlas
 * texels are {@code [atlasX, atlasX + width) × [atlasY, atlasY + height)}.
 *
 * <p>The distance field stores {@code 0.5 + d / (2 · sdfRange)} per texel, {@code d} being the
 * signed distance in pixels at the em size (positive inside the glyph), so a sample {@code s} maps
 * back to {@code d = (s - 0.5) · 2 · sdfRange}.
 *
 * <p>Layout (little-endian): a 64-byte header, then {@link #GLYPH_BYTES}-byte glyph records sorted
 * by code point, then {@link #KERNING_BYTES}-byte kerning records sorted by (left, right):
 *
 * <pre>
 * 0   u32 magic "JFNT"      4  u16 version   6  u16 flags
 * 8   f32 emSize            12 f32 ascent    16 f32 descent   20 f32 lineHeight
 * 24  f32 sdfRange          28 u32 atlasWidth  32 u32 atlasHeight
 * 36  u32 glyphCount        40 u32 kerningCount  44 u32 glyphsOffset  48 u32 kerningOffset
 * glyph:   u32 codePoint, f32 advance, f32 bearingX, f32 bearingY, u16 width, u16 height,
 *          u16 atlasX, u16 atlasY
 * kerning: u32 left, u32 right, f32 adjust
 * </pre>
 *
 * <p>Thread ownership: immutable after {@link #read}; any thread.
 */
public final class FontData {
    /** File magic, {@code "JFNT"}. */
    public static final int MAGIC = 0x544E464A;

    /** The format version this code reads and writes. */
    public static final int VERSION = 1;

    /** Bytes per glyph record. */
    public static final int GLYPH_BYTES = 24;

    /** Bytes per kerning record. */
    public static final int KERNING_BYTES = 12;

    /** Header size. */
    public static final int HEADER_BYTES = 64;

    private static final int DIRECT = 256;

    private final float emSize;
    private final float ascent;
    private final float descent;
    private final float lineHeight;
    private final float sdfRange;
    private final int atlasWidth;
    private final int atlasHeight;
    private final int[] codePoints;
    private final float[] advance;
    private final float[] bearingX;
    private final float[] bearingY;
    private final short[] width;
    private final short[] height;
    private final short[] atlasX;
    private final short[] atlasY;
    private final long[] kerningKeys;
    private final float[] kerningValues;
    private final int[] direct = new int[DIRECT];
    private final int fallback;

    private FontData(
            float emSize,
            float ascent,
            float descent,
            float lineHeight,
            float sdfRange,
            int atlasWidth,
            int atlasHeight,
            int[] codePoints,
            float[] advance,
            float[] bearingX,
            float[] bearingY,
            short[] width,
            short[] height,
            short[] atlasX,
            short[] atlasY,
            long[] kerningKeys,
            float[] kerningValues) {
        this.emSize = emSize;
        this.ascent = ascent;
        this.descent = descent;
        this.lineHeight = lineHeight;
        this.sdfRange = sdfRange;
        this.atlasWidth = atlasWidth;
        this.atlasHeight = atlasHeight;
        this.codePoints = codePoints;
        this.advance = advance;
        this.bearingX = bearingX;
        this.bearingY = bearingY;
        this.width = width;
        this.height = height;
        this.atlasX = atlasX;
        this.atlasY = atlasY;
        this.kerningKeys = kerningKeys;
        this.kerningValues = kerningValues;
        Arrays.fill(direct, -1);
        for (int i = 0; i < codePoints.length; i++) {
            if (codePoints[i] >= 0 && codePoints[i] < DIRECT) {
                direct[codePoints[i]] = i;
            }
        }
        int f = glyphIndex('?');
        if (f < 0) {
            f = glyphIndex(0xFFFD);
        }
        fallback = f < 0 ? (codePoints.length > 0 ? 0 : -1) : f;
    }

    /**
     * Parses a {@code .jfont} file.
     *
     * @throws AssetFormatException if the file is malformed
     */
    public static FontData read(MemorySegment file) throws AssetFormatException {
        if (file.byteSize() < HEADER_BYTES) {
            throw new AssetFormatException(".jfont is shorter than its header");
        }
        if (file.get(U32, 0) != MAGIC) {
            throw new AssetFormatException("not a .jfont file");
        }
        int version = Short.toUnsignedInt(file.get(U16, 4));
        if (version != VERSION) {
            throw new AssetFormatException(".jfont version " + version + ", expected " + VERSION);
        }
        float emSize = file.get(F32, 8);
        float ascent = file.get(F32, 12);
        float descent = file.get(F32, 16);
        float lineHeight = file.get(F32, 20);
        float sdfRange = file.get(F32, 24);
        int atlasWidth = file.get(U32, 28);
        int atlasHeight = file.get(U32, 32);
        int glyphCount = file.get(U32, 36);
        int kerningCount = file.get(U32, 40);
        long glyphsOffset = Integer.toUnsignedLong(file.get(U32, 44));
        long kerningOffset = Integer.toUnsignedLong(file.get(U32, 48));
        if (glyphCount < 0 || kerningCount < 0 || emSize <= 0 || sdfRange <= 0) {
            throw new AssetFormatException(".jfont header out of range");
        }
        Bytes.checkRange(file, glyphsOffset, (long) glyphCount * GLYPH_BYTES, "glyphs");
        Bytes.checkRange(file, kerningOffset, (long) kerningCount * KERNING_BYTES, "kerning");
        int[] cps = new int[glyphCount];
        float[] adv = new float[glyphCount];
        float[] bx = new float[glyphCount];
        float[] by = new float[glyphCount];
        short[] w = new short[glyphCount];
        short[] h = new short[glyphCount];
        short[] ax = new short[glyphCount];
        short[] ay = new short[glyphCount];
        for (int i = 0; i < glyphCount; i++) {
            long o = glyphsOffset + (long) i * GLYPH_BYTES;
            cps[i] = file.get(U32, o);
            if (i > 0 && cps[i] <= cps[i - 1]) {
                throw new AssetFormatException(".jfont glyphs are not sorted by code point");
            }
            adv[i] = file.get(F32, o + 4);
            bx[i] = file.get(F32, o + 8);
            by[i] = file.get(F32, o + 12);
            w[i] = file.get(U16, o + 16);
            h[i] = file.get(U16, o + 18);
            ax[i] = file.get(U16, o + 20);
            ay[i] = file.get(U16, o + 22);
        }
        long[] keys = new long[kerningCount];
        float[] values = new float[kerningCount];
        for (int i = 0; i < kerningCount; i++) {
            long o = kerningOffset + (long) i * KERNING_BYTES;
            keys[i] = kerningKey(file.get(U32, o), file.get(U32, o + 4));
            values[i] = file.get(F32, o + 8);
            if (i > 0 && keys[i] <= keys[i - 1]) {
                throw new AssetFormatException(".jfont kerning pairs are not sorted");
            }
        }
        return new FontData(
                emSize,
                ascent,
                descent,
                lineHeight,
                sdfRange,
                atlasWidth,
                atlasHeight,
                cps,
                adv,
                bx,
                by,
                w,
                h,
                ax,
                ay,
                keys,
                values);
    }

    static long kerningKey(int left, int right) {
        return (long) left << 32 | Integer.toUnsignedLong(right);
    }

    /** Returns the size in pixels the glyphs were rasterized at (one em). */
    public float emSize() {
        return emSize;
    }

    /** Returns the ascent above the baseline at the em size. */
    public float ascent() {
        return ascent;
    }

    /** Returns the descent below the baseline at the em size (positive). */
    public float descent() {
        return descent;
    }

    /** Returns the distance between baselines at the em size. */
    public float lineHeight() {
        return lineHeight;
    }

    /** Returns the distance-field range in pixels at the em size. */
    public float sdfRange() {
        return sdfRange;
    }

    /** Returns the atlas width in texels. */
    public int atlasWidth() {
        return atlasWidth;
    }

    /** Returns the atlas height in texels. */
    public int atlasHeight() {
        return atlasHeight;
    }

    /** Returns the number of glyphs. */
    public int glyphCount() {
        return codePoints.length;
    }

    /** Returns the index of {@code codePoint}'s glyph, or -1 if the font lacks it. */
    public int glyphIndex(int codePoint) {
        if (codePoint >= 0 && codePoint < DIRECT) {
            return direct[codePoint];
        }
        int lo = 0;
        int hi = codePoints.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int c = codePoints[mid];
            if (c < codePoint) {
                lo = mid + 1;
            } else if (c > codePoint) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        return -1;
    }

    /**
     * Returns the glyph of {@code codePoint}, or the fallback glyph ({@code ?}, else U+FFFD, else
     * glyph 0) when the font lacks it; -1 only for an empty font.
     */
    public int glyphOrFallback(int codePoint) {
        int g = glyphIndex(codePoint);
        return g < 0 ? fallback : g;
    }

    /** Returns the code point of glyph {@code g}. */
    public int codePoint(int g) {
        return codePoints[g];
    }

    /** Returns how far the pen moves after glyph {@code g}, at the em size. */
    public float advance(int g) {
        return advance[g];
    }

    /** Returns the horizontal offset from the pen to the glyph quad's left edge. */
    public float bearingX(int g) {
        return bearingX[g];
    }

    /** Returns the vertical offset from the baseline up to the glyph quad's top edge. */
    public float bearingY(int g) {
        return bearingY[g];
    }

    /** Returns the glyph quad's width in pixels at the em size (0 for blank glyphs). */
    public int width(int g) {
        return Short.toUnsignedInt(width[g]);
    }

    /** Returns the glyph quad's height in pixels at the em size (0 for blank glyphs). */
    public int height(int g) {
        return Short.toUnsignedInt(height[g]);
    }

    /** Returns the left texel of the glyph in the atlas. */
    public int atlasX(int g) {
        return Short.toUnsignedInt(atlasX[g]);
    }

    /** Returns the top texel of the glyph in the atlas. */
    public int atlasY(int g) {
        return Short.toUnsignedInt(atlasY[g]);
    }

    /** Returns the number of kerning pairs. */
    public int kerningCount() {
        return kerningKeys.length;
    }

    /**
     * Returns the kerning adjustment to add to the advance between the glyphs of {@code left} and
     * {@code right} (code points), at the em size; 0 for unlisted pairs. Allocates nothing.
     */
    public float kerning(int left, int right) {
        if (kerningKeys.length == 0) {
            return 0;
        }
        long key = kerningKey(left, right);
        int lo = 0;
        int hi = kerningKeys.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long k = kerningKeys[mid];
            if (k < key) {
                lo = mid + 1;
            } else if (k > key) {
                hi = mid - 1;
            } else {
                return kerningValues[mid];
            }
        }
        return 0;
    }
}
