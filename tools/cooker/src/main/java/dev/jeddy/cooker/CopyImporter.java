package dev.jeddy.cooker;

import java.util.Locale;
import java.util.Set;

/**
 * Copies files the runtime reads as they are: audio (stored uncompressed in paks because it is
 * already compressed, DESIGN §10.1), fonts and data files. The output path is the normalized source
 * path.
 *
 * <p>Thread ownership: stateless; any thread.
 */
public final class CopyImporter implements Importer {
    private static final Set<String> EXTENSIONS =
            Set.of("ogg", "wav", "flac", "mp3", "json", "txt", "csv", "toml", "ttf", "otf", "bin");

    @Override
    public String name() {
        return "copy";
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public boolean accepts(String path, ImportSettings settings) {
        return EXTENSIONS.contains(extension(path));
    }

    @Override
    public void cook(CookContext ctx) {
        ctx.output(CookContext.sanitize(ctx.path()), ctx.bytes());
    }

    /** Returns the lower-case extension after the last dot, or {@code ""}. */
    public static String extension(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return dot > slash + 1 ? path.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }
}
