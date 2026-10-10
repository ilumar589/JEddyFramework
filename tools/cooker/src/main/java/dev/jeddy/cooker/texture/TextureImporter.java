package dev.jeddy.cooker.texture;

import dev.jeddy.cooker.CookContext;
import dev.jeddy.cooker.CopyImporter;
import dev.jeddy.cooker.ImportSettings;
import dev.jeddy.cooker.Importer;
import dev.jeddy.cooker.texture.TextureCooker.Kind;

import java.util.Map;
import java.util.Set;

/**
 * Cooks a standalone image (PNG, JPEG, TGA, BMP, GIF; Radiance HDR only with an explicit {@code
 * kind}) into {@code <path>.ktx2} (TASKS M6-05). The usage comes from the {@code kind} setting
 * ({@code color}, {@code linear}, {@code normal}, {@code mask}) or the file name suffix ({@link
 * TextureCooker#guessKind}); other settings are those of {@link TextureCooker.Options#with}.
 *
 * <p>Thread ownership: stateless; any thread.
 */
public final class TextureImporter implements Importer {
    private static final Set<String> EXTENSIONS = Set.of("png", "jpg", "jpeg", "tga", "bmp", "gif");

    @Override
    public String name() {
        return "texture";
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public boolean accepts(String path, ImportSettings settings) {
        String ext = CopyImporter.extension(path);
        return EXTENSIONS.contains(ext) || (ext.equals("hdr") && settings.has("kind"));
    }

    @Override
    public void cook(CookContext ctx) throws Exception {
        ImportSettings s = ctx.settings();
        Kind kind =
                s.has("kind")
                        ? TextureCooker.parseKind(s.string("kind", "color"))
                        : TextureCooker.guessKind(ctx.path());
        ImageRgba image = ImageDecoder.decode(ctx.bytes(), ctx.path(), TextureCooker.srgb(kind));
        TextureCooker.Cooked c =
                TextureCooker.cook(
                        image, TextureCooker.Options.defaults(kind).with(s), ctx.threads());
        ctx.output(ctx.outputBase() + ".ktx2", c.ktx2());
        for (Map.Entry<String, Object> e : c.stats().entrySet()) {
            ctx.stat(e.getKey(), e.getValue());
        }
        if (kind == Kind.COLOR && c.stats().get("psnr") instanceof Double psnr && psnr < 40) {
            ctx.warn(String.format(java.util.Locale.ROOT, "low PSNR %.1f dB", psnr));
        }
    }
}
