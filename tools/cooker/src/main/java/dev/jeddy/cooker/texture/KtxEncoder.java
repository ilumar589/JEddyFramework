package dev.jeddy.cooker.texture;

import static dev.jeddy.cooker.ffi.ktx.Ktx.*;

import dev.jeddy.asset.format.Ktx2;
import dev.jeddy.cooker.ffi.ktx.ktxBasisParams;
import dev.jeddy.cooker.ffi.ktx.ktxTexture2;
import dev.jeddy.cooker.ffi.ktx.ktxTextureCreateInfo;
import dev.jeddy.cooker.natives.ToolNatives;
import dev.jeddy.core.memory.CStrings;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;

/**
 * Encodes a mip chain into a KTX2 file through libktx (TASKS M6-05, DESIGN §10.3, D8): RGBA8 levels
 * are compressed to UASTC (optionally with rate-distortion optimization), transcoded to the BCn
 * target (BC7 for color and ORM, BC5 for normal maps, BC4 for masks), zstd-supercompressed per
 * level and written to memory. The UASTC intermediate is also decoded to RGBA for the PSNR/SSIM
 * report: BC7 from UASTC is a near-lossless transcode, so it measures what ships.
 *
 * <p>Thread ownership: one encode per call; calls may run in parallel (libktx is thread-safe per
 * texture object).
 */
public final class KtxEncoder {
    private KtxEncoder() {}

    /** Target block format. */
    public enum Target {
        /** BC7, RGBA (base color, emissive, ORM). */
        BC7(KTX_TTF_BC7_RGBA()),
        /** BC5, two channels (tangent-space normal XY; Z is reconstructed). */
        BC5(KTX_TTF_BC5_RG()),
        /** BC4, one channel (masks, height). */
        BC4(KTX_TTF_BC4_R());

        final int transcode;

        Target(int transcode) {
            this.transcode = transcode;
        }
    }

    /**
     * Settings.
     *
     * @param srgb the levels' color channels are sRGB-encoded (base color, emissive)
     * @param uastcLevel {@code KTX_PACK_UASTC_LEVEL_*} speed/quality (0 fastest .. 4 very slow)
     * @param rdoQuality UASTC RDO quality scalar (0 disables RDO; ~1 is a good default)
     * @param zstdLevel supercompression level (0 for none)
     * @param threads encoder threads
     */
    public record Settings(
            Target target,
            boolean srgb,
            int uastcLevel,
            float rdoQuality,
            int zstdLevel,
            int threads) {
        /** Base color defaults. */
        public static Settings color(int threads) {
            return new Settings(Target.BC7, true, 0, 0, 10, threads);
        }
    }

    /** The encoded file and its quality. */
    public record Result(byte[] ktx2, int vkFormat, double psnr, double ssim, long nanos) {}

    /**
     * Encodes RGBA8 levels (level 0 first, each half the previous size) into a KTX2 file.
     *
     * @param levels RGBA8 pixels per level
     * @throws IOException if libktx reports an error
     */
    public static Result encode(int width, int height, List<byte[]> levels, Settings s)
            throws IOException {
        ToolNatives.load();
        long t0 = System.nanoTime();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment info = ktxTextureCreateInfo.allocate(arena);
            ktxTextureCreateInfo.vkFormat(
                    info, s.srgb() ? Ktx2.VK_FORMAT_R8G8B8A8_SRGB : Ktx2.VK_FORMAT_R8G8B8A8_UNORM);
            ktxTextureCreateInfo.baseWidth(info, width);
            ktxTextureCreateInfo.baseHeight(info, height);
            ktxTextureCreateInfo.baseDepth(info, 1);
            ktxTextureCreateInfo.numDimensions(info, 2);
            ktxTextureCreateInfo.numLevels(info, levels.size());
            ktxTextureCreateInfo.numLayers(info, 1);
            ktxTextureCreateInfo.numFaces(info, 1);
            ktxTextureCreateInfo.isArray(info, false);
            ktxTextureCreateInfo.generateMipmaps(info, false);
            MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
            check(ktxTexture2_Create(info, KTX_TEXTURE_CREATE_ALLOC_STORAGE(), out), "create");
            MemorySegment tex = out.get(ValueLayout.ADDRESS, 0).reinterpret(ktxTexture2.sizeof());
            MemorySegment copy = MemorySegment.NULL;
            try {
                MemorySegment data = ktxTexture2.pData(tex).reinterpret(ktxTexture2.dataSize(tex));
                MemorySegment offset = arena.allocate(ValueLayout.JAVA_LONG);
                for (int l = 0; l < levels.size(); l++) {
                    check(ktxTexture2_GetImageOffset(tex, l, 0, 0, offset), "image offset");
                    byte[] level = levels.get(l);
                    MemorySegment.copy(
                            level,
                            0,
                            data,
                            ValueLayout.JAVA_BYTE,
                            offset.get(ValueLayout.JAVA_LONG, 0),
                            level.length);
                }
                MemorySegment params = ktxBasisParams.allocate(arena);
                params.fill((byte) 0);
                ktxBasisParams.structSize(params, (int) ktxBasisParams.sizeof());
                ktxBasisParams.uastc(params, true);
                ktxBasisParams.threadCount(params, Math.max(1, s.threads()));
                ktxBasisParams.uastcFlags(params, s.uastcLevel());
                if (s.rdoQuality() > 0) {
                    ktxBasisParams.uastcRDO(params, true);
                    ktxBasisParams.uastcRDOQualityScalar(params, s.rdoQuality());
                }
                if (s.target() == Target.BC5) {
                    ktxBasisParams.normalMap(params, true);
                    setSwizzle(params, "rrrg"); // X in RGB, Y in alpha: what BC5 transcoding reads
                } else if (s.target() == Target.BC4) {
                    setSwizzle(params, "rrr1");
                }
                check(ktxTexture2_CompressBasisEx(tex, params), "UASTC compression");

                // Quality: decode the UASTC level 0 back to RGBA and compare with the input.
                check(ktxTexture2_CreateCopy(tex, out), "copy");
                copy = out.get(ValueLayout.ADDRESS, 0).reinterpret(ktxTexture2.sizeof());
                check(ktxTexture2_TranscodeBasis(copy, KTX_TTF_RGBA32(), 0), "decode for metrics");
                check(ktxTexture2_GetImageOffset(copy, 0, 0, 0, offset), "image offset");
                byte[] decoded = new byte[width * height * 4];
                MemorySegment copyData =
                        ktxTexture2.pData(copy).reinterpret(ktxTexture2.dataSize(copy));
                MemorySegment.copy(
                        copyData,
                        ValueLayout.JAVA_BYTE,
                        offset.get(ValueLayout.JAVA_LONG, 0),
                        decoded,
                        0,
                        decoded.length);
                byte[] reference = levels.get(0);
                int channels = s.target() == Target.BC4 ? 1 : s.target() == Target.BC5 ? 2 : 3;
                byte[] compared = reference;
                if (s.target() == Target.BC5) { // compare X and Y where the swizzle put them
                    decoded = unswizzleRrrg(decoded);
                }
                double psnr = ImageMetrics.psnr(compared, decoded, channels);
                double ssim =
                        channels == 3
                                ? ImageMetrics.ssim(compared, decoded, width, height)
                                : Double.NaN;

                check(
                        ktxTexture2_TranscodeBasis(
                                tex, s.target().transcode, KTX_TF_HIGH_QUALITY()),
                        "transcode");
                if (s.zstdLevel() > 0) {
                    check(ktxTexture2_DeflateZstd(tex, s.zstdLevel()), "zstd");
                }
                int vkFormat = ktxTexture2.vkFormat(tex);
                MemorySegment bytes = arena.allocate(ValueLayout.ADDRESS);
                MemorySegment size = arena.allocate(ValueLayout.JAVA_LONG);
                check(ktxTexture2_WriteToMemory(tex, bytes, size), "write");
                MemorySegment written =
                        bytes.get(ValueLayout.ADDRESS, 0)
                                .reinterpret(size.get(ValueLayout.JAVA_LONG, 0));
                byte[] file = written.toArray(ValueLayout.JAVA_BYTE);
                ToolNatives.free(written);
                return new Result(file, vkFormat, psnr, ssim, System.nanoTime() - t0);
            } finally {
                if (copy.address() != 0) {
                    ktxTexture2_Destroy(copy);
                }
                ktxTexture2_Destroy(tex);
            }
        }
    }

    private static void setSwizzle(MemorySegment params, String swizzle) {
        for (int i = 0; i < 4; i++) {
            ktxBasisParams.inputSwizzle(params, i, (byte) swizzle.charAt(i));
        }
    }

    /** After an "rrrg" swizzle, X is in R and Y in A; move Y back to G for comparison. */
    private static byte[] unswizzleRrrg(byte[] rgba) {
        byte[] out = rgba.clone();
        for (int i = 0; i < out.length; i += 4) {
            out[i + 1] = rgba[i + 3];
        }
        return out;
    }

    private static void check(int code, String what) throws IOException {
        if (code != KTX_SUCCESS()) {
            throw new IOException(
                    "libktx " + what + " failed: " + CStrings.read(ktxErrorString(code)));
        }
    }
}
