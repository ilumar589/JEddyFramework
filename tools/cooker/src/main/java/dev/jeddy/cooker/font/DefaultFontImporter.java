package dev.jeddy.cooker.font;

import dev.jeddy.cooker.CookContext;
import dev.jeddy.cooker.ImportSettings;
import dev.jeddy.cooker.Importer;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Built-in outputs {@value #FONT} and {@value #ATLAS}: the engine's default UI font, Inter Regular
 * (SIL Open Font License; see {@code fonts/OFL.txt} next to the font in the cooker's resources),
 * cooked with the default {@link FontCooker.Options}. It has no source file; every cook writes it,
 * so a game has text before it ships a font of its own (DESIGN §18, D83).
 *
 * <p>Thread ownership: stateless; any thread.
 */
public final class DefaultFontImporter implements Importer {
    /** Output path of the metrics. */
    public static final String FONT = "engine/fonts/inter.jfont";

    /** Output path of the atlas. */
    public static final String ATLAS = "engine/fonts/inter.ktx2";

    private static final String RESOURCE = "/dev/jeddy/cooker/fonts/Inter-Regular.otf";

    @Override
    public String name() {
        return "default-font";
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public boolean accepts(String path, ImportSettings settings) {
        return false;
    }

    @Override
    public void cook(CookContext ctx) throws Exception {
        byte[] bytes;
        try (InputStream in = DefaultFontImporter.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("missing cooker resource " + RESOURCE);
            }
            bytes = in.readAllBytes();
        }
        FontCooker.Cooked cooked = FontCooker.cook(bytes, FontCooker.Options.defaults());
        ctx.output(FONT, cooked.jfont());
        ctx.output(ATLAS, cooked.atlasKtx2());
        for (Map.Entry<String, Object> e : cooked.stats().entrySet()) {
            ctx.stat(e.getKey(), e.getValue());
        }
    }
}
