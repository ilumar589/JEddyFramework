package dev.jeddy.cooker.env;

import dev.jeddy.asset.format.Ktx2;
import dev.jeddy.cooker.CookContext;
import dev.jeddy.cooker.CopyImporter;
import dev.jeddy.cooker.ImportSettings;
import dev.jeddy.cooker.Importer;
import dev.jeddy.cooker.texture.ImageDecoder;
import dev.jeddy.cooker.texture.ImageRgba;
import dev.jeddy.cooker.texture.KtxEncoder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;

/**
 * Cooks an equirectangular HDR environment (Radiance {@code .hdr}, or any image with {@code "kind":
 * "environment"}) into {@code <path>.ktx2} (TASKS M6-07): an RGBA16F cube map whose mip {@code i}
 * is GGX-prefiltered for {@code roughness = i / (levels - 1)}, with the diffuse irradiance as SH9
 * in the key {@value #SH9_KEY} (27 little-endian floats, {@link EnvironmentBaker#irradianceSh9}).
 * Settings: {@code size} (face size, default 256), {@code samples} (per texel, default 128), {@code
 * rotation} (degrees about +Y), {@code levels} (default: down to 4×4).
 *
 * <p>Thread ownership: stateless; any thread.
 */
public final class EnvironmentImporter implements Importer {
    /** Key/value entry holding the SH9 irradiance coefficients. */
    public static final String SH9_KEY = "jeddy.sh9";

    @Override
    public String name() {
        return "environment";
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public boolean accepts(String path, ImportSettings settings) {
        String kind = settings.string("kind", null);
        return "environment".equals(kind)
                || (kind == null && CopyImporter.extension(path).equals("hdr"));
    }

    @Override
    public void cook(CookContext ctx) throws Exception {
        ImportSettings s = ctx.settings();
        int size = Integer.highestOneBit(Math.max(4, s.integer("size", 256)));
        int maxLevels = Integer.numberOfTrailingZeros(size) - 1; // down to 4×4
        int levels = Math.max(1, Math.min(maxLevels, s.integer("levels", maxLevels)));
        int samples = Math.max(16, s.integer("samples", 128));
        ImageRgba eq = ImageDecoder.decode(ctx.bytes(), ctx.path(), false);
        if (eq.width() != 2 * eq.height()) {
            ctx.warn(
                    "an equirectangular map should be twice as wide as it is high, got "
                            + eq.width()
                            + "×"
                            + eq.height());
        }
        long t0 = System.nanoTime();
        EnvironmentBaker.Cube cube =
                EnvironmentBaker.fromEquirect(
                        eq, size, (float) Math.toRadians(s.number("rotation", 0)), ctx.jobs());
        List<EnvironmentBaker.Cube> specular =
                EnvironmentBaker.prefilter(cube, levels, samples, ctx.jobs());
        float[] sh = EnvironmentBaker.irradianceSh9(cube);
        long bake = System.nanoTime() - t0;
        byte[][][] data = new byte[levels][6][];
        for (int l = 0; l < levels; l++) {
            EnvironmentBaker.Cube c = specular.get(l);
            for (int f = 0; f < 6; f++) {
                data[l][f] = rgba16f(c.faces()[f]);
            }
        }
        ByteBuffer shBytes = ByteBuffer.allocate(27 * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : sh) {
            shBytes.putFloat(v);
        }
        byte[] ktx =
                KtxEncoder.encodeRaw(
                        Ktx2.VK_FORMAT_R16G16B16A16_SFLOAT,
                        size,
                        size,
                        6,
                        data,
                        Map.of(SH9_KEY, shBytes.array()),
                        s.integer("zstd", 10));
        ctx.output(ctx.outputBase() + ".ktx2", ktx);
        ctx.stat("faceSize", size);
        ctx.stat("levels", levels);
        ctx.stat("samples", samples);
        ctx.stat("bakeMs", Math.round(bake / 1e5) / 10.0);
        ctx.stat("bytes", ktx.length);
        ctx.stat("sh0", List.of((double) sh[0], (double) sh[1], (double) sh[2]));
    }

    /** Packs RGB floats into RGBA16F (alpha 1). */
    static byte[] rgba16f(float[] rgb) {
        int n = rgb.length / 3;
        ByteBuffer b = ByteBuffer.allocate(n * 8).order(ByteOrder.LITTLE_ENDIAN);
        short one = Float.floatToFloat16(1f);
        for (int i = 0; i < n; i++) {
            b.putShort(Float.floatToFloat16(Math.min(rgb[i * 3], 65504f)));
            b.putShort(Float.floatToFloat16(Math.min(rgb[i * 3 + 1], 65504f)));
            b.putShort(Float.floatToFloat16(Math.min(rgb[i * 3 + 2], 65504f)));
            b.putShort(one);
        }
        return b.array();
    }
}
