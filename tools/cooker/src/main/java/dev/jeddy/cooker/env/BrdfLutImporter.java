package dev.jeddy.cooker.env;

import dev.jeddy.asset.format.Ktx2;
import dev.jeddy.cooker.CookContext;
import dev.jeddy.cooker.ImportSettings;
import dev.jeddy.cooker.Importer;
import dev.jeddy.cooker.texture.KtxEncoder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

/**
 * Built-in output {@value #OUTPUT}: the split-sum environment BRDF as an RG16F {@value #SIZE}²
 * texture ({@link EnvironmentBaker#brdfLut}; x = N·V, y = roughness, row 0 first). It has no source
 * file; the cooker runs it once per cook and caches it like any other result.
 *
 * <p>Thread ownership: stateless; any thread.
 */
public final class BrdfLutImporter implements Importer {
    /** Output path. */
    public static final String OUTPUT = "engine/brdf_lut.ktx2";

    /** Texture size. */
    public static final int SIZE = 128;

    @Override
    public String name() {
        return "brdf-lut";
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
        float[] lut = EnvironmentBaker.brdfLut(SIZE, 512, ctx.jobs());
        ByteBuffer b = ByteBuffer.allocate(lut.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : lut) {
            b.putShort(Float.floatToFloat16(v));
        }
        byte[] ktx =
                KtxEncoder.encodeRaw(
                        Ktx2.VK_FORMAT_R16G16_SFLOAT,
                        SIZE,
                        SIZE,
                        1,
                        new byte[][][] {{b.array()}},
                        Map.of(),
                        10);
        ctx.output(OUTPUT, ktx);
        ctx.stat("size", SIZE);
        ctx.stat("bytes", ktx.length);
    }
}
