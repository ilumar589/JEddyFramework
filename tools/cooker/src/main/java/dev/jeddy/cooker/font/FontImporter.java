package dev.jeddy.cooker.font;

import dev.jeddy.cooker.CookContext;
import dev.jeddy.cooker.CopyImporter;
import dev.jeddy.cooker.ImportSettings;
import dev.jeddy.cooker.Importer;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cooks {@code .ttf} and {@code .otf} fonts into a {@code .jfont} plus an R8 KTX2 distance-field
 * atlas ({@link FontCooker}, TASKS M13-02). Settings ({@code <file>.meta}): {@code "size"} (px per
 * em, 48), {@code "range"} (distance range in px, 8), {@code "ranges"} (a list of {@code [from,
 * to]} code point ranges, inclusive) and {@code "kerning"} (true).
 *
 * <p>Thread ownership: stateless; any thread.
 */
public final class FontImporter implements Importer {
    private static final Set<String> EXTENSIONS = Set.of("ttf", "otf");

    @Override
    public String name() {
        return "font";
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public boolean accepts(String path, ImportSettings settings) {
        return EXTENSIONS.contains(CopyImporter.extension(path));
    }

    @Override
    public void cook(CookContext ctx) throws Exception {
        FontCooker.Cooked cooked = FontCooker.cook(ctx.bytes(), options(ctx.settings()));
        ctx.output(ctx.outputBase() + ".jfont", cooked.jfont());
        ctx.output(ctx.outputBase() + ".ktx2", cooked.atlasKtx2());
        for (Map.Entry<String, Object> e : cooked.stats().entrySet()) {
            ctx.stat(e.getKey(), e.getValue());
        }
        if (cooked.stats().get("missing") instanceof Integer m && m > 0) {
            ctx.warn(m + " requested code points are not in the font");
        }
    }

    /** Reads the cook options from import settings. */
    static FontCooker.Options options(ImportSettings s) {
        FontCooker.Options d = FontCooker.Options.defaults();
        int[][] ranges = d.ranges();
        if (s.raw("ranges") instanceof List<?> list) {
            ranges = new int[list.size()][];
            for (int i = 0; i < list.size(); i++) {
                if (!(list.get(i) instanceof List<?> pair) || pair.size() != 2) {
                    throw new IllegalArgumentException("ranges: expected [from, to] pairs");
                }
                ranges[i] =
                        new int[] {
                            ((Number) pair.get(0)).intValue(), ((Number) pair.get(1)).intValue()
                        };
            }
        }
        return new FontCooker.Options(
                Math.max(8, s.integer("size", d.emSize())),
                Math.max(2, s.integer("range", d.range())),
                ranges,
                s.bool("kerning", d.kerning()),
                d.zstdLevel());
    }
}
