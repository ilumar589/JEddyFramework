package dev.jeddy.cooker.lut;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.format.Ktx2;
import dev.jeddy.compress.ZstdDecompressor;
import dev.jeddy.cooker.CookReport;
import dev.jeddy.cooker.Cooker;
import dev.jeddy.cooker.CookerConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;

/** {@code .cube} grading LUTs: parsing and cooking to a 3D KTX2 (TASKS M7-06). */
class CubeLutTest {
    @TempDir Path dir;

    /** A LUT that swaps red and blue, red fastest. */
    private static String swapLut(int n, String header) {
        StringBuilder b = new StringBuilder(header);
        b.append("LUT_3D_SIZE ").append(n).append('\n');
        for (int z = 0; z < n; z++) {
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    float s = 1f / (n - 1);
                    b.append(z * s)
                            .append(' ')
                            .append(y * s)
                            .append(' ')
                            .append(x * s)
                            .append('\n');
                }
            }
        }
        return b.toString();
    }

    @Test
    void parsesHeadersCommentsAndData() throws IOException {
        CubeLut lut = CubeLut.parse(swapLut(3, "# made by hand\nTITLE \"swap\"\n\n"), "swap.cube");
        assertEquals(3, lut.size());
        assertEquals("swap", lut.title());
        float[] out = new float[3];
        lut.sample(0.25f, 0.5f, 1f, out);
        assertEquals(1f, out[0], 1e-5f);
        assertEquals(0.5f, out[1], 1e-5f);
        assertEquals(0.25f, out[2], 1e-5f);
    }

    @Test
    void rejectsBrokenFiles() {
        assertThrows(IOException.class, () -> CubeLut.parse("LUT_1D_SIZE 4\n", "a.cube"));
        assertThrows(IOException.class, () -> CubeLut.parse("0 0 0\n", "b.cube"));
        assertThrows(IOException.class, () -> CubeLut.parse("LUT_3D_SIZE 2\n0 0 0\n", "c.cube"));
        IOException e =
                assertThrows(
                        IOException.class, () -> CubeLut.parse("LUT_3D_SIZE 2\n0 0 x\n", "d.cube"));
        assertTrue(e.getMessage().contains("d.cube:2"), e.getMessage());
    }

    @Test
    void cooksToAnRgba16fVolume() throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/grade"));
        Files.writeString(src.resolve("swap.cube"), swapLut(4, ""));
        // A LUT over a wider domain is resampled onto [0, 1].
        Files.writeString(
                src.resolve("wide.cube"), swapLut(5, "DOMAIN_MIN 0 0 0\nDOMAIN_MAX 2 2 2\n"));
        Files.writeString(src.resolve("wide.cube.meta"), "{\"size\": 3}");
        try (Cooker c = new Cooker(CookerConfig.of(dir.resolve("src"), dir.resolve("cooked")))) {
            CookReport r = c.cookAll();
            assertEquals(CookReport.Status.COOKED, r.item("grade/swap.cube").status(), r.toJson());
            assertEquals(CookReport.Status.COOKED, r.item("grade/wide.cube").status(), r.toJson());
        }
        try (Arena arena = Arena.ofConfined();
                ZstdDecompressor zstd = new ZstdDecompressor()) {
            Ktx2 k = read(arena, dir.resolve("cooked/grade/swap.ktx2"));
            assertEquals(4, k.width());
            assertEquals(4, k.depth());
            assertEquals(Ktx2.VK_FORMAT_R16G16B16A16_SFLOAT, k.vkFormat());
            MemorySegment level = arena.allocate(k.levelSize(0));
            k.decodeLevel(0, level, 0, zstd);
            // Entry (x 3, y 0, z 1): red 1 -> blue; blue 1/3 -> red.
            long at = ((1L * 4 + 0) * 4 + 3) * 8;
            assertEquals(1 / 3f, half(level, at), 1e-3f);
            assertEquals(0f, half(level, at + 2), 1e-3f);
            assertEquals(1f, half(level, at + 4), 1e-3f);

            Ktx2 wide = read(arena, dir.resolve("cooked/grade/wide.ktx2"));
            assertEquals(3, wide.depth());
            MemorySegment w = arena.allocate(wide.levelSize(0));
            wide.decodeLevel(0, w, 0, zstd);
            // Output entry x = 2 is input 1.0, halfway through the [0, 2] domain: blue 0.5.
            assertEquals(0.5f, half(w, 2 * 8 + 4), 1e-3f);
        }
    }

    private static Ktx2 read(Arena arena, Path p) throws Exception {
        byte[] bytes = Files.readAllBytes(p);
        MemorySegment file = arena.allocate(bytes.length, 8);
        MemorySegment.copy(bytes, 0, file, ValueLayout.JAVA_BYTE, 0, bytes.length);
        return Ktx2.read(file); // zstd works on native memory
    }

    private static float half(MemorySegment m, long at) {
        return Float.float16ToFloat(m.get(ValueLayout.JAVA_SHORT_UNALIGNED, at));
    }
}
