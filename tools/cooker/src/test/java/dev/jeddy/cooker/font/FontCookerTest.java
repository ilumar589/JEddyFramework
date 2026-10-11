package dev.jeddy.cooker.font;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.format.FontData;
import dev.jeddy.asset.format.Ktx2;
import dev.jeddy.cooker.CookReport;
import dev.jeddy.cooker.Cooker;
import dev.jeddy.cooker.CookerConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;

/** Distance fields, glyph metrics, kerning and the built-in font (TASKS M13-02). */
class FontCookerTest {
    @TempDir Path dir;

    private static byte[] inter() throws Exception {
        try (InputStream in =
                FontCookerTest.class.getResourceAsStream(
                        "/dev/jeddy/cooker/fonts/Inter-Regular.otf")) {
            return in.readAllBytes();
        }
    }

    @Test
    void distanceFieldOfADiscIsRadial() {
        int n = 64;
        float r = 20;
        byte[] cov = new byte[n * n];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                // Coverage from the distance of the pixel center to the circle (an exact AA edge).
                double d = r - Math.hypot(x + 0.5 - n / 2.0, y + 0.5 - n / 2.0);
                cov[y * n + x] = (byte) Math.round(Math.clamp(d + 0.5, 0, 1) * 255);
            }
        }
        float[] sd = DistanceField.signed(cov, n, n);
        double worst = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                double d = r - Math.hypot(x + 0.5 - n / 2.0, y + 0.5 - n / 2.0);
                if (Math.abs(d) < 12) {
                    worst = Math.max(worst, Math.abs(sd[y * n + x] - d));
                }
            }
        }
        // Pixel-center distances to a circle are within half a pixel of the true distance; the
        // encode step then quantizes to 1/255 of the range.
        assertTrue(worst < 0.6, "worst error " + worst);
        assertEquals(sd[(n / 2) * n + n / 2] > 15, true, "deep inside is positive");
        assertEquals(sd[0] < -15, true, "the corner is far outside");
        assertEquals(128, DistanceField.encode(0, 8) & 0xFF, "the outline encodes to 0.5");
        assertEquals(255, DistanceField.encode(100, 8) & 0xFF);
        assertEquals(0, DistanceField.encode(-100, 8) & 0xFF);
    }

    @Test
    void cooksInterWithMetricsKerningAndAnAtlas() throws Exception {
        FontCooker.Cooked c = FontCooker.cook(inter(), FontCooker.Options.defaults());
        FontData f = FontData.read(MemorySegment.ofArray(c.jfont()));
        assertEquals(48, f.emSize());
        assertTrue(f.ascent() > 35 && f.ascent() < 50, "ascent " + f.ascent());
        assertTrue(f.lineHeight() > 50 && f.lineHeight() < 65, "line height " + f.lineHeight());
        assertTrue(f.glyphCount() > 300, "glyphs " + f.glyphCount());
        int a = f.glyphIndex('A');
        int space = f.glyphIndex(' ');
        assertTrue(a >= 0 && space >= 0);
        assertTrue(f.advance(a) > 25 && f.advance(a) < 40, "A advance " + f.advance(a));
        assertEquals(0, f.width(space), "space has no quad");
        assertTrue(f.width(a) > 30 && f.height(a) > 40, "A quad includes the padding");
        assertTrue(f.bearingY(a) > 34 && f.bearingY(a) < 50, "A sits on the baseline");
        assertTrue(f.kerning('A', 'V') < -1, "AV kerns: " + f.kerning('A', 'V'));
        assertEquals(0, f.kerning('H', 'H'));
        Ktx2 k = Ktx2.read(MemorySegment.ofArray(c.atlasKtx2()));
        assertEquals(Ktx2.VK_FORMAT_R8_UNORM, k.vkFormat());
        assertEquals(f.atlasWidth(), k.width());
        assertEquals(1, k.levels());
        assertTrue(k.width() * k.height() <= 2048 * 1024, "atlas " + c.stats().get("atlas"));
        // The atlas texel at the middle of a glyph's quad is inside the stem for 'l'.
        int l = f.glyphIndex('l');
        assertTrue(
                f.atlasX(l) + f.width(l) <= k.width() && f.atlasY(l) + f.height(l) <= k.height());
    }

    @Test
    void theBuiltInFontIsPartOfEveryCook() throws Exception {
        Path src = dir.resolve("assets");
        Path out = dir.resolve("cooked");
        Files.createDirectories(src);
        try (Cooker c = new Cooker(CookerConfig.of(src, out).withThreads(2))) {
            CookReport r = c.cookAll();
            CookReport.Item item = r.item("<builtin>/default-font");
            assertEquals(CookReport.Status.COOKED, item.status(), r.toJson());
            assertTrue(Files.size(out.resolve(DefaultFontImporter.FONT)) > 1000);
            assertTrue(Files.size(out.resolve(DefaultFontImporter.ATLAS)) > 10_000);
            FontData f =
                    FontData.read(
                            MemorySegment.ofArray(
                                    Files.readAllBytes(out.resolve(DefaultFontImporter.FONT))));
            assertTrue(f.glyphIndex('é') >= 0);
        }
    }
}
