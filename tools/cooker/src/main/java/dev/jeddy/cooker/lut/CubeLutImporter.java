package dev.jeddy.cooker.lut;

import dev.jeddy.asset.format.Ktx2;
import dev.jeddy.cooker.CookContext;
import dev.jeddy.cooker.CopyImporter;
import dev.jeddy.cooker.ImportSettings;
import dev.jeddy.cooker.Importer;
import dev.jeddy.cooker.texture.KtxEncoder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Cooks a {@code .cube} color-grading LUT into {@code <path>.ktx2} (TASKS M7-06): an RGBA16F 3D
 * texture that {@code PostProcess.setGradingLut} samples with the sRGB-encoded display color. LUTs
 * authored over another input domain are resampled onto [0, 1]. Settings: {@code size} (output
 * entries per axis, default the source size; 16..64 is typical).
 *
 * <p>Thread ownership: stateless; any thread.
 */
public final class CubeLutImporter implements Importer {
    @Override
    public String name() {
        return "lut";
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public boolean accepts(String path, ImportSettings settings) {
        String kind = settings.string("kind", null);
        return "lut".equals(kind) || (kind == null && CopyImporter.extension(path).equals("cube"));
    }

    @Override
    public void cook(CookContext ctx) throws Exception {
        CubeLut lut = CubeLut.parse(new String(ctx.bytes(), StandardCharsets.UTF_8), ctx.path());
        int size = Math.clamp(ctx.settings().integer("size", lut.size()), 2, CubeLut.MAX_SIZE);
        boolean unitDomain = true;
        for (int c = 0; c < 3; c++) {
            unitDomain &= lut.domainMin()[c] == 0f && lut.domainMax()[c] == 1f;
        }
        ByteBuffer b = ByteBuffer.allocate(size * size * size * 8).order(ByteOrder.LITTLE_ENDIAN);
        short one = Float.floatToFloat16(1f);
        float[] out = new float[3];
        float outside = 0;
        for (int z = 0; z < size; z++) {
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    if (unitDomain && size == lut.size()) {
                        int i = ((z * size + y) * size + x) * 3;
                        out[0] = lut.rgb()[i];
                        out[1] = lut.rgb()[i + 1];
                        out[2] = lut.rgb()[i + 2];
                    } else {
                        float s = 1f / (size - 1);
                        lut.sample(x * s, y * s, z * s, out);
                    }
                    for (int c = 0; c < 3; c++) {
                        outside = Math.max(outside, Math.max(-out[c], out[c] - 1));
                        b.putShort(Float.floatToFloat16(Math.clamp(out[c], -65504f, 65504f)));
                    }
                    b.putShort(one);
                }
            }
        }
        if (outside > 0.01f) {
            ctx.warn(
                    "values leave [0, 1] by up to "
                            + outside
                            + "; display-space grading expects encoded colors");
        }
        byte[] ktx =
                KtxEncoder.encodeVolume(
                        Ktx2.VK_FORMAT_R16G16B16A16_SFLOAT,
                        size,
                        size,
                        size,
                        b.array(),
                        Map.of("jeddy.lut", lut.title().getBytes(StandardCharsets.UTF_8)),
                        ctx.settings().integer("zstd", 10));
        ctx.output(ctx.outputBase() + ".ktx2", ktx);
        ctx.stat("size", size);
        ctx.stat("sourceSize", lut.size());
        ctx.stat("bytes", ktx.length);
    }
}
