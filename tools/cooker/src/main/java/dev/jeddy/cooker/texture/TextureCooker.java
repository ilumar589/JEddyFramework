package dev.jeddy.cooker.texture;

import dev.jeddy.cooker.ImportSettings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Texture cooking shared by the texture and glTF importers (TASKS M6-05): mips in linear space,
 * then UASTC → BCn through {@link KtxEncoder}, with the format chosen by the texture's usage.
 *
 * <p>Thread ownership: stateless; any thread.
 */
public final class TextureCooker {
    private TextureCooker() {}

    /** How a texture is used, which decides its format (DESIGN §10.1). */
    public enum Kind {
        /** sRGB color (base color, emissive): BC7 sRGB. */
        COLOR,
        /** Linear data in RGB(A) (ORM): BC7 UNORM. */
        LINEAR,
        /** Tangent-space normal map: BC5, Z reconstructed, mips renormalized. */
        NORMAL,
        /** One channel (masks, height, separate occlusion): BC4. */
        MASK
    }

    /**
     * Cook options.
     *
     * @param wrap the texture tiles (the mip filter wraps instead of clamping)
     * @param alphaCutoff keep alpha-test coverage at this cutoff in every mip ({@code < 0} off)
     * @param maxSize drop top mips until both sides are at most this ({@code 0} unlimited)
     * @param mips build the mip chain (otherwise level 0 only)
     * @param uastcLevel first UASTC speed/quality level to try (0..4)
     * @param rdo UASTC rate-distortion quality ({@code 0} off)
     * @param zstd supercompression level ({@code 0} off)
     * @param minPsnr encode again one UASTC level higher (up to 2) while level 0's PSNR is below
     *     this ({@code 0} off)
     */
    public record Options(
            Kind kind,
            boolean wrap,
            float alphaCutoff,
            int maxSize,
            boolean mips,
            int uastcLevel,
            float rdo,
            int zstd,
            float minPsnr) {
        /**
         * Defaults for {@code kind}: UASTC level 0, escalating for color and ORM textures under 40
         * dB. Level 0 is 3-4× faster than level 1 and within 1 dB on most content, but loses 8 dB
         * or more on some saturated gradients; the retry catches those (TASKS M6-05).
         */
        public static Options defaults(Kind kind) {
            float minPsnr = kind == Kind.COLOR || kind == Kind.LINEAR ? 40 : 0;
            return new Options(kind, true, -1, 0, true, 0, 0, 10, minPsnr);
        }

        /** Applies the settings keys {@code wrap}, {@code alphaCutoff}, {@code maxSize}, ... */
        public Options with(ImportSettings s) {
            return new Options(
                    kind,
                    s.bool("wrap", wrap),
                    s.number("alphaCutoff", alphaCutoff),
                    s.integer("maxSize", maxSize),
                    s.bool("mips", mips),
                    s.integer("uastcLevel", uastcLevel),
                    s.number("rdo", rdo),
                    s.integer("zstd", zstd),
                    s.number("minPsnr", minPsnr));
        }

        /** Returns a copy with another kind (and that kind's PSNR floor). */
        public Options withKind(Kind k) {
            float floor = k == Kind.COLOR || k == Kind.LINEAR ? minPsnr : 0;
            return new Options(k, wrap, alphaCutoff, maxSize, mips, uastcLevel, rdo, zstd, floor);
        }

        /** Returns a copy with another wrap mode. */
        public Options withWrap(boolean w) {
            return new Options(kind, w, alphaCutoff, maxSize, mips, uastcLevel, rdo, zstd, minPsnr);
        }

        /** Returns a copy with another alpha cutoff. */
        public Options withAlphaCutoff(float cutoff) {
            return new Options(kind, wrap, cutoff, maxSize, mips, uastcLevel, rdo, zstd, minPsnr);
        }
    }

    /** A cooked texture and its report numbers. */
    public record Cooked(byte[] ktx2, Map<String, Object> stats) {}

    /** Parses a {@code kind} setting value. */
    public static Kind parseKind(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "color", "srgb", "albedo", "basecolor", "emissive" -> Kind.COLOR;
            case "linear", "orm", "data" -> Kind.LINEAR;
            case "normal", "normalmap" -> Kind.NORMAL;
            case "mask", "gray", "grey", "height", "occlusion" -> Kind.MASK;
            default -> throw new IllegalArgumentException("unknown texture kind " + value);
        };
    }

    /**
     * Guesses the kind from a file name suffix ({@code _n}/{@code _normal} normal map, {@code
     * _orm}/{@code _arm} linear, {@code _ao}/{@code _rough}/{@code _mask}/{@code _height} mask),
     * else color.
     */
    public static Kind guessKind(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        int dot = name.indexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        int us = Math.max(name.lastIndexOf('_'), name.lastIndexOf('-'));
        String suffix = us >= 0 ? name.substring(us + 1) : "";
        return switch (suffix) {
            case "n", "nor", "nrm", "norm", "normal", "normals", "normalgl" -> Kind.NORMAL;
            case "orm", "arm", "mr", "rma", "mra", "metallicroughness", "data" -> Kind.LINEAR;
            case "ao",
                    "occlusion",
                    "rough",
                    "roughness",
                    "metal",
                    "metallic",
                    "mask",
                    "height",
                    "disp",
                    "h" ->
                    Kind.MASK;
            default -> Kind.COLOR;
        };
    }

    /** Returns {@code true} if {@code kind}'s pixels are sRGB-encoded. */
    public static boolean srgb(Kind kind) {
        return kind == Kind.COLOR;
    }

    /**
     * Builds the mips of {@code image} (linear values) and encodes them.
     *
     * @param threads threads for the UASTC encoder
     */
    public static Cooked cook(ImageRgba image, Options o, int threads) throws IOException {
        long t0 = System.nanoTime();
        List<ImageRgba> chain =
                o.mips()
                        ? MipGenerator.chain(
                                image,
                                new MipGenerator.Options(
                                        o.wrap(), o.kind() == Kind.NORMAL, o.alphaCutoff()))
                        : List.of(image);
        int first = 0;
        while (o.maxSize() > 0
                && first + 1 < chain.size()
                && Math.max(chain.get(first).width(), chain.get(first).height()) > o.maxSize()) {
            first++;
        }
        List<byte[]> levels = new ArrayList<>(chain.size() - first);
        boolean srgb = srgb(o.kind());
        for (int i = first; i < chain.size(); i++) {
            levels.add(chain.get(i).toRgba8(srgb));
        }
        long mipNanos = System.nanoTime() - t0;
        ImageRgba top = chain.get(first);
        KtxEncoder.Target target =
                switch (o.kind()) {
                    case COLOR, LINEAR -> KtxEncoder.Target.BC7;
                    case NORMAL -> KtxEncoder.Target.BC5;
                    case MASK -> KtxEncoder.Target.BC4;
                };
        int level = o.uastcLevel();
        KtxEncoder.Result r;
        long encodeNanos = 0;
        while (true) {
            r =
                    KtxEncoder.encode(
                            top.width(),
                            top.height(),
                            levels,
                            new KtxEncoder.Settings(
                                    target, srgb, level, o.rdo(), o.zstd(), threads));
            encodeNanos += r.nanos();
            if (r.psnr() >= o.minPsnr() || level >= 2) {
                break;
            }
            level++;
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("kind", o.kind().name().toLowerCase(Locale.ROOT));
        stats.put("format", target.name() + (srgb ? " sRGB" : ""));
        stats.put("width", top.width());
        stats.put("height", top.height());
        stats.put("levels", levels.size());
        stats.put("psnr", round(r.psnr()));
        if (!Double.isNaN(r.ssim())) {
            stats.put("ssim", Math.round(r.ssim() * 10000) / 10000.0);
        }
        stats.put("bytes", r.ktx2().length);
        stats.put("mipMs", Math.round(mipNanos / 1e5) / 10.0);
        stats.put("uastcLevel", level);
        stats.put("encodeMs", Math.round(encodeNanos / 1e5) / 10.0);
        return new Cooked(r.ktx2(), stats);
    }

    private static double round(double v) {
        return Double.isInfinite(v) ? 999 : Math.round(v * 100) / 100.0;
    }
}
