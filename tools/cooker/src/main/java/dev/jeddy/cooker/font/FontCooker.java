package dev.jeddy.cooker.font;

import dev.jeddy.asset.format.FontWriter;
import dev.jeddy.asset.format.Ktx2;
import dev.jeddy.cooker.texture.KtxEncoder;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.font.LineMetrics;
import java.awt.font.TextAttribute;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a TrueType or OpenType font into a signed-distance-field atlas and its metrics (TASKS
 * M13-02, DESIGN §18, D83), with nothing but the JDK: {@code java.awt} rasterizes each glyph at the
 * em size with antialiasing, {@link DistanceField} turns the coverage into distances, and a shelf
 * packer lays the glyphs out in a power-of-two atlas.
 *
 * <p>Thread ownership: one cook per instance; any thread. {@code java.awt} runs headless.
 */
public final class FontCooker {
    /** Default rasterization size in pixels per em. */
    public static final int DEFAULT_EM = 48;

    /** Default distance range in pixels at the em size. */
    public static final int DEFAULT_RANGE = 8;

    /**
     * Default code point ranges: ASCII, Latin-1 supplement, Latin Extended-A, general punctuation
     * (dashes, quotes, ellipsis, bullet), the euro sign, the arrows, triangles, circles and check
     * mark the built-in widgets draw, and the replacement character.
     */
    public static final int[][] DEFAULT_RANGES = {
        {0x20, 0x7E},
        {0xA0, 0xFF},
        {0x100, 0x17F},
        {0x2010, 0x2027},
        {0x20AC, 0x20AC},
        {0x2190, 0x2193}, // arrows
        {0x25B2, 0x25B2}, // triangles the widgets use (tree nodes, dropdowns)
        {0x25B6, 0x25B6},
        {0x25BC, 0x25BC},
        {0x25C0, 0x25C0},
        {0x25CB, 0x25CB}, // circles (radio buttons)
        {0x25CF, 0x25CF},
        {0x2713, 0x2713}, // check mark
        {0xFFFD, 0xFFFD}
    };

    /** Code points whose kerning pairs are measured: ASCII letters and punctuation. */
    private static final int KERN_FROM = 0x21;

    private static final int KERN_TO = 0x7E;

    /**
     * Cook options.
     *
     * @param emSize rasterization size in pixels per em
     * @param range distance range in pixels at the em size (the atlas padding too)
     * @param ranges inclusive code point ranges to include
     * @param kerning whether to measure kerning pairs
     * @param zstdLevel KTX2 supercompression level
     */
    public record Options(int emSize, int range, int[][] ranges, boolean kerning, int zstdLevel) {
        /** 48 px, range 8, the default ranges, kerning, zstd 10. */
        public static Options defaults() {
            return new Options(DEFAULT_EM, DEFAULT_RANGE, DEFAULT_RANGES, true, 10);
        }
    }

    /** The cooked font: the {@code .jfont} bytes, the R8 KTX2 atlas, and statistics. */
    public record Cooked(byte[] jfont, byte[] atlasKtx2, Map<String, Object> stats) {}

    private record Raster(
            int codePoint,
            float advance,
            float bearingX,
            float bearingY,
            int width,
            int height,
            byte[] sdf) {}

    private FontCooker() {}

    /**
     * Cooks {@code fontBytes} (a TTF or OTF file).
     *
     * @throws IOException if the font cannot be parsed
     */
    public static Cooked cook(byte[] fontBytes, Options options) throws IOException {
        Font base;
        try {
            base = Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(fontBytes));
        } catch (FontFormatException e) {
            throw new IOException("not a TrueType or OpenType font: " + e.getMessage(), e);
        }
        Map<TextAttribute, Object> attributes = new LinkedHashMap<>();
        attributes.put(TextAttribute.SIZE, (float) options.emSize);
        attributes.put(TextAttribute.KERNING, TextAttribute.KERNING_ON);
        Font font = base.deriveFont(attributes);
        FontRenderContext frc = new FontRenderContext(null, true, true);
        LineMetrics lm = font.getLineMetrics("Ag", frc);

        List<Raster> rasters = new ArrayList<>();
        int missing = 0;
        for (int[] r : options.ranges) {
            for (int cp = r[0]; cp <= r[1]; cp++) {
                if (!font.canDisplay(cp)) {
                    missing++;
                    continue;
                }
                rasters.add(raster(font, frc, cp, options.range));
            }
        }
        if (rasters.isEmpty()) {
            throw new IOException("the font has none of the requested code points");
        }

        // Shelf packing, tallest first, into the smallest power-of-two atlas that fits.
        List<Raster> order = new ArrayList<>(rasters);
        order.sort(
                Comparator.comparingInt((Raster g) -> -g.height).thenComparingInt(g -> -g.width));
        int atlasW = 256;
        int atlasH = 256;
        int[] placement;
        while (true) {
            placement = pack(order, atlasW, atlasH);
            if (placement != null) {
                break;
            }
            if (atlasW <= atlasH) {
                atlasW *= 2;
            } else {
                atlasH *= 2;
            }
            if (atlasW > 8192) {
                throw new IOException("glyphs do not fit a 8192² atlas");
            }
        }
        byte[] atlas = new byte[atlasW * atlasH];
        FontWriter writer =
                new FontWriter()
                        .metrics(
                                options.emSize,
                                lm.getAscent(),
                                lm.getDescent(),
                                lm.getHeight(),
                                options.range)
                        .atlas(atlasW, atlasH);
        for (int i = 0; i < order.size(); i++) {
            Raster g = order.get(i);
            int ax = placement[i * 2];
            int ay = placement[i * 2 + 1];
            for (int y = 0; y < g.height; y++) {
                System.arraycopy(g.sdf, y * g.width, atlas, (ay + y) * atlasW + ax, g.width);
            }
            writer.glyph(g.codePoint, g.advance, g.bearingX, g.bearingY, g.width, g.height, ax, ay);
        }
        int pairs = 0;
        if (options.kerning) {
            pairs = kerning(font, frc, rasters, writer);
        }
        byte[] ktx =
                KtxEncoder.encodeRaw(
                        Ktx2.VK_FORMAT_R8_UNORM,
                        atlasW,
                        atlasH,
                        1,
                        new byte[][][] {{atlas}},
                        Map.of(),
                        options.zstdLevel);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("glyphs", rasters.size());
        stats.put("missing", missing);
        stats.put("kerningPairs", pairs);
        stats.put("atlas", atlasW + "x" + atlasH);
        stats.put("atlasBytes", ktx.length);
        return new Cooked(writer.toBytes(), ktx, stats);
    }

    /** Rasterizes one glyph with {@code pad} pixels of margin and computes its distance field. */
    private static Raster raster(Font font, FontRenderContext frc, int cp, int pad) {
        String s = new String(Character.toChars(cp));
        GlyphVector gv = font.createGlyphVector(frc, s);
        float advance = gv.getGlyphMetrics(0).getAdvance();
        Rectangle2D bounds = gv.getVisualBounds();
        if (bounds.isEmpty()) {
            return new Raster(cp, advance, 0, 0, 0, 0, new byte[0]);
        }
        // Integer box around the outline, plus the padding the distance field needs.
        int x0 = (int) Math.floor(bounds.getX()) - pad;
        int y0 = (int) Math.floor(bounds.getY()) - pad;
        int x1 = (int) Math.ceil(bounds.getMaxX()) + pad;
        int y1 = (int) Math.ceil(bounds.getMaxY()) + pad;
        int w = x1 - x0;
        int h = y1 - y0;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, w, h);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(
                    RenderingHints.KEY_FRACTIONALMETRICS,
                    RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE);
            // The glyph origin (pen at the baseline) lands at (-x0, -y0) in the image.
            g.fill(gv.getOutline(-x0, -y0));
        } finally {
            g.dispose();
        }
        byte[] coverage = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
        float[] sd = DistanceField.signed(coverage, w, h);
        byte[] encoded = new byte[w * h];
        for (int i = 0; i < encoded.length; i++) {
            encoded[i] = DistanceField.encode(sd[i], pad);
        }
        // bearingY is measured upward from the baseline to the quad's top: the top row is y0
        // below the baseline in image coordinates (y down), so the bearing is -y0.
        return new Raster(cp, advance, x0, -y0, w, h, encoded);
    }

    /**
     * Places {@code order} (tallest first) on shelves; returns x, y per glyph or {@code null} if
     * they do not fit.
     */
    private static int[] pack(List<Raster> order, int atlasW, int atlasH) {
        int[] out = new int[order.size() * 2];
        int shelfY = 0;
        int shelfH = 0;
        int x = 0;
        for (int i = 0; i < order.size(); i++) {
            Raster g = order.get(i);
            if (g.width == 0) {
                continue;
            }
            if (x + g.width > atlasW) {
                shelfY += shelfH;
                shelfH = 0;
                x = 0;
            }
            if (g.height > shelfH) {
                shelfH = g.height;
            }
            if (shelfY + shelfH > atlasH || g.width > atlasW) {
                return null;
            }
            out[i * 2] = x;
            out[i * 2 + 1] = shelfY;
            x += g.width;
        }
        return out;
    }

    /**
     * Measures kerning by laying out each pair with kerning on and comparing the second glyph's
     * position with the first glyph's advance.
     */
    private static int kerning(
            Font font, FontRenderContext frc, List<Raster> rasters, FontWriter writer) {
        int[] cps = new int[rasters.size()];
        float[] advances = new float[rasters.size()];
        int n = 0;
        for (Raster r : rasters) {
            if (r.codePoint >= KERN_FROM && r.codePoint <= KERN_TO) {
                cps[n] = r.codePoint;
                advances[n] = r.advance;
                n++;
            }
        }
        cps = Arrays.copyOf(cps, n);
        advances = Arrays.copyOf(advances, n);
        int pairs = 0;
        char[] two = new char[2];
        for (int i = 0; i < n; i++) {
            two[0] = (char) cps[i];
            for (int j = 0; j < n; j++) {
                two[1] = (char) cps[j];
                // layoutGlyphVector applies the font's GPOS kerning; createGlyphVector does not.
                GlyphVector gv = font.layoutGlyphVector(frc, two, 0, 2, Font.LAYOUT_LEFT_TO_RIGHT);
                if (gv.getNumGlyphs() != 2) {
                    continue; // a ligature swallowed the pair
                }
                float second = (float) gv.getGlyphPosition(1).getX();
                float adjust = second - advances[i];
                if (Math.abs(adjust) > 0.01f) {
                    writer.kerning(cps[i], cps[j], adjust);
                    pairs++;
                }
            }
        }
        return pairs;
    }
}
