package dev.jeddy.cooker.texture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.format.Ktx2;
import dev.jeddy.compress.ZstdDecompressor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javax.imageio.ImageIO;

/** Decoding, mips, UASTC → BCn encoding and quality numbers (TASKS M6-05). */
class TextureTest {
    @TempDir Path dir;

    /** A smooth color image with some detail, like a painted texture. */
    private static ImageRgba painted(int size) {
        ImageRgba img = new ImageRgba(size, size);
        Random r = new Random(3);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double u = x / (double) size;
                double v = y / (double) size;
                img.set(x, y, 0, (float) (0.5 + 0.4 * Math.sin(u * 6.3)));
                img.set(x, y, 1, (float) (0.4 + 0.3 * Math.cos(v * 9.1 + u * 2)));
                img.set(
                        x,
                        y,
                        2,
                        (float) (0.3 + 0.2 * Math.sin((u + v) * 12) + r.nextGaussian() * 0.01));
                img.set(x, y, 3, 1);
            }
        }
        return img;
    }

    private static List<byte[]> rgba8(List<ImageRgba> levels, boolean srgb) {
        List<byte[]> out = new ArrayList<>();
        for (ImageRgba l : levels) {
            out.add(l.toRgba8(srgb));
        }
        return out;
    }

    @Test
    void colorTexturesEncodeToBc7WithZstdAndHighPsnr() throws Exception {
        ImageRgba base = painted(256);
        List<ImageRgba> mips = MipGenerator.chain(base, MipGenerator.Options.COLOR);
        assertEquals(9, mips.size());
        assertEquals(1, mips.get(8).width());
        KtxEncoder.Result r =
                KtxEncoder.encode(256, 256, rgba8(mips, true), KtxEncoder.Settings.color(2));
        assertTrue(r.psnr() >= 40, "base color PSNR " + r.psnr());
        assertTrue(r.ssim() > 0.97, "SSIM " + r.ssim());
        assertEquals(Ktx2.VK_FORMAT_BC7_SRGB_BLOCK, r.vkFormat());
        try (Arena a = Arena.ofConfined();
                ZstdDecompressor d = new ZstdDecompressor()) {
            MemorySegment file = a.allocate(r.ktx2().length);
            MemorySegment.copy(r.ktx2(), 0, file, ValueLayout.JAVA_BYTE, 0, r.ktx2().length);
            Ktx2 k = Ktx2.read(file);
            assertEquals(9, k.levels());
            assertEquals(Ktx2.SUPERCOMPRESSION_ZSTD, k.supercompression());
            assertEquals(256 * 256, k.levelSize(0), "64 x 64 blocks of 16 bytes");
            MemorySegment level = a.allocate(k.levelSize(0));
            k.decodeLevel(0, level, 0, d);
        }
    }

    @Test
    void normalMapsGoToBc5AndMasksToBc4() throws Exception {
        ImageRgba n = new ImageRgba(64, 64);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                double nx = Math.sin(x * 0.2) * 0.4;
                double ny = Math.cos(y * 0.15) * 0.4;
                double nz = Math.sqrt(1 - nx * nx - ny * ny);
                n.set(x, y, 0, (float) (nx * 0.5 + 0.5));
                n.set(x, y, 1, (float) (ny * 0.5 + 0.5));
                n.set(x, y, 2, (float) (nz * 0.5 + 0.5));
                n.set(x, y, 3, 1);
            }
        }
        List<ImageRgba> mips = MipGenerator.chain(n, new MipGenerator.Options(true, true, -1));
        KtxEncoder.Result bc5 =
                KtxEncoder.encode(
                        64,
                        64,
                        rgba8(mips, false),
                        new KtxEncoder.Settings(KtxEncoder.Target.BC5, false, 1, 0, 3, 1));
        assertEquals(Ktx2.VK_FORMAT_BC5_UNORM_BLOCK, bc5.vkFormat());
        assertTrue(bc5.psnr() > 35, "normal XY PSNR " + bc5.psnr());
        KtxEncoder.Result bc4 =
                KtxEncoder.encode(
                        64,
                        64,
                        rgba8(mips, false),
                        new KtxEncoder.Settings(KtxEncoder.Target.BC4, false, 1, 0, 3, 1));
        assertEquals(Ktx2.VK_FORMAT_BC4_UNORM_BLOCK, bc4.vkFormat());
        // Renormalized mips stay unit length.
        ImageRgba m2 = mips.get(2);
        float x = m2.get(3, 3, 0) * 2 - 1;
        float y = m2.get(3, 3, 1) * 2 - 1;
        float z = m2.get(3, 3, 2) * 2 - 1;
        assertEquals(1, Math.sqrt(x * x + y * y + z * z), 1e-4);
    }

    @Test
    void mipFilterPreservesAverageAndCoverage() {
        ImageRgba flat = new ImageRgba(16, 8);
        java.util.Arrays.fill(flat.pixels(), 0.25f);
        ImageRgba half = MipGenerator.downsample(flat, 8, 4, false);
        for (float v : half.pixels()) {
            assertEquals(0.25f, v, 1e-5f);
        }
        ImageRgba checker = new ImageRgba(64, 64);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                float c = ((x + y) & 1) == 0 ? 1 : 0;
                for (int ch = 0; ch < 4; ch++) {
                    checker.set(x, y, ch, c);
                }
            }
        }
        ImageRgba small = MipGenerator.chain(checker, MipGenerator.Options.COLOR).get(3);
        assertEquals(0.5f, small.get(2, 2, 0), 0.02f, "a checkerboard filters to gray");
        // Alpha-tested foliage: coverage at the cutoff stays put down the chain.
        ImageRgba leaves = new ImageRgba(64, 64);
        Random r = new Random(1);
        for (int i = 3; i < leaves.pixels().length; i += 4) {
            leaves.pixels()[i] = r.nextFloat() < 0.3f ? 1 : 0;
        }
        List<ImageRgba> chain =
                MipGenerator.chain(leaves, new MipGenerator.Options(true, false, 0.5f));
        float c0 = MipGenerator.coverage(chain.get(0), 0.5f, 1);
        float c3 = MipGenerator.coverage(chain.get(3), 0.5f, 1);
        assertEquals(c0, c3, 0.08f);
    }

    @Test
    void decodesPngTgaAndHdr() throws IOException {
        BufferedImage png = new BufferedImage(4, 2, BufferedImage.TYPE_INT_ARGB);
        png.setRGB(1, 0, 0xFF808080);
        png.setRGB(3, 1, 0x80FF0000);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(png, "png", bytes);
        Path pngFile = dir.resolve("a.png");
        Files.write(pngFile, bytes.toByteArray());
        ImageRgba p = ImageDecoder.decode(pngFile, true);
        assertEquals(ImageRgba.srgbToLinear(128 / 255f), p.get(1, 0, 0), 1e-5f);
        assertEquals(128 / 255f, p.get(3, 1, 3), 1e-5f, "alpha stays linear");

        // 2x2 TGA, 24-bit, bottom-up, uncompressed; then the same pixels RLE-encoded.
        byte[] tga = new byte[18 + 12];
        tga[2] = 2;
        tga[12] = 2;
        tga[14] = 2;
        tga[16] = 24;
        byte[] bottomRow = {0, 0, (byte) 255, 0, (byte) 255, 0}; // BGR: red, green
        byte[] topRow = {(byte) 255, 0, 0, (byte) 255, (byte) 255, (byte) 255}; // blue, white
        System.arraycopy(bottomRow, 0, tga, 18, 6);
        System.arraycopy(topRow, 0, tga, 24, 6);
        ImageRgba t = ImageDecoder.decodeTga(tga, false);
        assertEquals(1f, t.get(0, 1, 0), "bottom-left red");
        assertEquals(1f, t.get(0, 0, 2), "top-left blue");
        byte[] rle = {
            0, 0, 10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 2, 0, 24, 0x20, (byte) 0x83, 10, 20, 30
        };
        ImageRgba tr = ImageDecoder.decodeTga(rle, false);
        assertEquals(30 / 255f, tr.get(1, 1, 0), 1e-6f, "one run of four pixels");

        String header = "#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y 1 +X 2\n";
        byte[] h = header.getBytes(StandardCharsets.US_ASCII);
        byte[] hdr = new byte[h.length + 8];
        System.arraycopy(h, 0, hdr, 0, h.length);
        byte[] px = {(byte) 128, 64, 0, (byte) 129, 0, 0, 0, 0}; // 1.0, 0.5, 0 ; black
        System.arraycopy(px, 0, hdr, h.length, 8);
        ImageRgba hd = ImageDecoder.decodeHdr(hdr);
        assertEquals(1.0f, hd.get(0, 0, 0), 1e-6f);
        assertEquals(0.5f, hd.get(0, 0, 1), 1e-6f);
        assertEquals(0f, hd.get(1, 0, 0));
        assertThrows(
                IOException.class,
                () -> ImageDecoder.decodeHdr("nope".getBytes(StandardCharsets.US_ASCII)));
    }
}
