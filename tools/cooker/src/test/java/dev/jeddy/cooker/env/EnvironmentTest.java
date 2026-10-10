package dev.jeddy.cooker.env;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.format.Ktx2;
import dev.jeddy.cooker.CookReport;
import dev.jeddy.cooker.Cooker;
import dev.jeddy.cooker.CookerConfig;
import dev.jeddy.cooker.TestImages;
import dev.jeddy.core.job.JobSystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** HDR environment bakes: cube conversion, GGX prefilter, SH9, BRDF LUT (TASKS M6-07). */
class EnvironmentTest {
    @TempDir Path dir;

    @Test
    void cubeFacesFollowTheGlTable() {
        float[] d = new float[3];
        EnvironmentBaker.direction(0, 1, 1, 2, d); // +X, s > 0, t > 0
        assertTrue(d[0] > 0.5f && d[1] < 0 && d[2] < 0);
        EnvironmentBaker.direction(2, 0, 1, 2, d); // +Y, s < 0, t > 0
        assertTrue(d[1] > 0.5f && d[0] < 0 && d[2] > 0);
        EnvironmentBaker.direction(4, 1, 0, 2, d); // +Z, s > 0, t < 0 (up)
        assertTrue(d[2] > 0.5f && d[0] > 0 && d[1] > 0);
        // Sampling a texel's direction returns that texel.
        EnvironmentBaker.Cube c = new EnvironmentBaker.Cube(4);
        c.faces()[3][(2 * 4 + 1) * 3] = 7;
        float[] rgb = new float[3];
        EnvironmentBaker.direction(3, 1, 2, 4, d);
        EnvironmentBaker.sample(c, d[0], d[1], d[2], rgb);
        assertEquals(7f, rgb[0], 1e-4f);
    }

    @Test
    void uniformLightGivesPiIrradianceAndAFlatPrefilter() {
        try (JobSystem jobs = new JobSystem(1)) {
            EnvironmentBaker.Cube cube = new EnvironmentBaker.Cube(16);
            for (float[] f : cube.faces()) {
                Arrays.fill(f, 0.5f);
            }
            float[] sh = EnvironmentBaker.irradianceSh9(cube);
            float[] e = new float[3];
            EnvironmentBaker.evaluateSh9(sh, 0, 1, 0, e);
            assertEquals(Math.PI * 0.5, e[0], 0.01);
            EnvironmentBaker.evaluateSh9(sh, 0.6f, 0, -0.8f, e);
            assertEquals(Math.PI * 0.5, e[2], 0.01);
            List<EnvironmentBaker.Cube> levels = EnvironmentBaker.prefilter(cube, 3, 32, jobs);
            assertEquals(3, levels.size());
            assertEquals(4, levels.get(2).size());
            for (float v : levels.get(2).faces()[5]) {
                assertEquals(0.5f, v, 1e-3f);
            }
        }
    }

    @Test
    void skyLightComesFromAbove() {
        EnvironmentBaker.Cube cube = new EnvironmentBaker.Cube(16);
        float[] d = new float[3];
        for (int f = 0; f < 6; f++) {
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    EnvironmentBaker.direction(f, x, y, 16, d);
                    int o = (y * 16 + x) * 3;
                    Arrays.fill(cube.faces()[f], o, o + 3, d[1] > 0 ? 1f : 0f);
                }
            }
        }
        float[] sh = EnvironmentBaker.irradianceSh9(cube);
        float[] up = new float[3];
        float[] down = new float[3];
        EnvironmentBaker.evaluateSh9(sh, 0, 1, 0, up);
        EnvironmentBaker.evaluateSh9(sh, 0, -1, 0, down);
        assertEquals(Math.PI, up[0], 0.25); // a band-limited hemisphere step
        assertTrue(down[0] < 0.25, "down " + down[0]);
    }

    @Test
    void brdfLutIsBoundedAndMatchesKnownValues() {
        try (JobSystem jobs = new JobSystem(1)) {
            float[] lut = EnvironmentBaker.brdfLut(32, 256, jobs);
            for (float v : lut) {
                assertTrue(v >= 0 && v <= 1.0001f, "value " + v);
            }
            // Smooth surface seen head-on: scale ≈ 1, bias ≈ 0.
            int i = 31 * 2;
            assertEquals(1f, lut[i] + lut[i + 1], 0.03f);
            assertTrue(lut[i + 1] < 0.02f);
            // Rough at grazing angles loses energy (single scattering), and the total falls with
            // roughness at a fixed angle.
            int j = (31 * 32) * 2;
            assertTrue(lut[j] + lut[j + 1] < 0.75f, "rough grazing " + (lut[j] + lut[j + 1]));
            float previous = 2;
            for (int y = 0; y < 32; y += 4) {
                int k = (y * 32 + 16) * 2;
                float sum = lut[k] + lut[k + 1];
                assertTrue(sum <= previous + 1e-3f, "roughness row " + y + ": " + sum);
                previous = sum;
            }
        }
    }

    @Test
    void cooksAnHdrIntoAPrefilteredCube() throws Exception {
        Path src = dir.resolve("assets");
        Files.createDirectories(src.resolve("env"));
        Files.write(
                src.resolve("env/sky.hdr"),
                TestImages.hdr(
                        64,
                        32,
                        (x, y, c) -> {
                            float sky = y < 16 ? 2f : 0.2f;
                            c[0] = sky * 0.6f;
                            c[1] = sky * 0.8f;
                            c[2] = sky;
                        }));
        Files.writeString(src.resolve("env/sky.hdr.meta"), "{\"size\": 32, \"samples\": 32}");
        try (Cooker c = new Cooker(CookerConfig.of(src, dir.resolve("cooked")).withThreads(2))) {
            CookReport r = c.cookAll();
            assertEquals(CookReport.Status.COOKED, r.item("env/sky.hdr").status(), r.toJson());
            Ktx2 k =
                    Ktx2.read(
                            MemorySegment.ofArray(
                                    Files.readAllBytes(dir.resolve("cooked/env/sky.ktx2"))));
            assertTrue(k.isCubeMap());
            assertEquals(Ktx2.VK_FORMAT_R16G16B16A16_SFLOAT, k.vkFormat());
            assertEquals(4, k.levels(), "32, 16, 8, 4");
            byte[] sh = k.keyValueBytes(EnvironmentImporter.SH9_KEY);
            assertNotNull(sh);
            assertEquals(108, sh.length);
            ByteBuffer b = ByteBuffer.wrap(sh).order(ByteOrder.LITTLE_ENDIAN);
            float blueDc = b.getFloat(8);
            float blueUp = b.getFloat(4 * (3 * 1 + 2)); // Y1-1 (y) coefficient of blue
            assertTrue(blueDc > 0, "SH DC term of blue " + blueDc);
            assertTrue(blueUp > 0, "more light from above " + blueUp);
        }
    }
}
