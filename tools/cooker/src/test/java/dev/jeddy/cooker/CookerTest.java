package dev.jeddy.cooker;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.AssetPaths;
import dev.jeddy.asset.format.Ktx2;
import dev.jeddy.asset.format.MaterialData;
import dev.jeddy.cooker.CookReport.Status;
import dev.jeddy.cooker.env.BrdfLutImporter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The cooker framework: importers, cache, incremental cooks, stale outputs, report (M6-02). */
class CookerTest {
    @TempDir Path dir;

    private Path src;
    private Path out;

    private void write(String path, byte[] data) throws IOException {
        Path p = src.resolve(path);
        Files.createDirectories(p.getParent());
        Files.write(p, data);
    }

    private void write(String path, String text) throws IOException {
        write(path, text.getBytes(StandardCharsets.UTF_8));
    }

    private void sources() throws IOException {
        src = dir.resolve("assets");
        out = dir.resolve("cooked");
        write("textures/rock.png", TestImages.painted(32, 1));
        write("textures/rock_n.png", TestImages.normalMap(32));
        write("textures/mask.png", TestImages.painted(16, 2));
        write("textures/mask.png.meta", "{\"kind\": \"mask\"}");
        write("textures/_defaults.meta", "{\"maxSize\": 16}");
        write(
                "materials/rock.jmat.json",
                """
                {"baseColor": [1, 0.9, 0.8, 1], "roughness": 0.7, "metallic": 0,
                 "textures": {"baseColor": "../textures/rock.png", "normal": "/textures/rock_n.png",
                              "orm": {"path": "../textures/mask.png", "uv": 1}}}
                """);
        write("shaders/common/light.glsl", "#pragma once\nvec3 light() { return vec3(1.0); }\n");
        write(
                "shaders/lit.frag",
                "#version 460 core\n"
                        + "#include \"common/light.glsl\"\n"
                        + "out vec4 color;\n"
                        + "void main() { color = vec4(light(), 1.0); }\n");
        write("audio/click.ogg", new byte[] {'O', 'g', 'g', 'S', 1, 2, 3});
        write(".hidden/ignored.png", TestImages.painted(4, 3));
        write("notes.unknown", "not an asset");
    }

    private Cooker cooker() throws IOException {
        return new Cooker(CookerConfig.of(src, out).withThreads(4).withShaders(List.of(), false));
    }

    private static MemorySegment segment(Path p) throws IOException {
        return MemorySegment.ofArray(Files.readAllBytes(p));
    }

    private Map<Path, FileTime> times() throws IOException {
        Map<Path, FileTime> m = new HashMap<>();
        try (var s = Files.walk(out)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                m.put(p, Files.getLastModifiedTime(p));
            }
        }
        return m;
    }

    @Test
    void cooksEveryImporterThenServesTheCache() throws Exception {
        sources();
        try (Cooker c = cooker()) {
            CookReport r = c.cookAll();
            assertFalse(r.failed(), r.toJson());
            assertEquals(Status.COOKED, r.item("textures/rock.png").status());
            assertEquals(null, r.item(".hidden/ignored.png"));
            assertEquals(null, r.item("notes.unknown"));
            assertEquals("copy", r.item("audio/click.ogg").importer());

            Ktx2 rock = Ktx2.read(segment(out.resolve("textures/rock.ktx2")));
            assertEquals(Ktx2.VK_FORMAT_BC7_SRGB_BLOCK, rock.vkFormat());
            assertEquals(16, rock.width(), "maxSize from _defaults.meta drops the top mip");
            assertEquals(5, rock.levels());
            assertEquals(
                    Ktx2.VK_FORMAT_BC5_UNORM_BLOCK,
                    Ktx2.read(segment(out.resolve("textures/rock_n.ktx2"))).vkFormat());
            assertEquals(
                    Ktx2.VK_FORMAT_BC4_UNORM_BLOCK,
                    Ktx2.read(segment(out.resolve("textures/mask.ktx2"))).vkFormat());

            MaterialData m = MaterialData.read(segment(out.resolve("materials/rock.jmat")));
            assertEquals(0.7f, m.roughness());
            assertEquals(
                    AssetPaths.id("textures/rock.ktx2"),
                    m.textureId(m.findTexture(MaterialData.SLOT_BASE_COLOR)));
            assertEquals(
                    AssetPaths.id("textures/rock_n.ktx2"),
                    m.textureId(m.findTexture(MaterialData.SLOT_NORMAL)));
            int orm = m.findTexture(MaterialData.SLOT_ORM);
            assertEquals(MaterialData.TEXTURE_UV1, m.textureFlags(orm));
            assertEquals(
                    MaterialData.FEATURE_BASE_COLOR_MAP
                            | MaterialData.FEATURE_NORMAL_MAP
                            | MaterialData.FEATURE_ORM_MAP
                            | MaterialData.FEATURE_UV1,
                    m.featureMask());
            List<String> warnings = r.item("materials/rock.jmat.json").warnings();
            assertEquals(1, warnings.size(), "the mask texture cooks as BC4, not ORM: " + warnings);

            assertArrayEquals(
                    Files.readAllBytes(src.resolve("shaders/lit.frag")),
                    Files.readAllBytes(out.resolve("shaders/lit.frag")));
            assertTrue(Files.isRegularFile(out.resolve("shaders/common/light.glsl")));
            assertTrue(Files.isRegularFile(out.resolve("audio/click.ogg")));
            Ktx2 lut = Ktx2.read(segment(out.resolve(BrdfLutImporter.OUTPUT)));
            assertEquals(Ktx2.VK_FORMAT_R16G16_SFLOAT, lut.vkFormat());
            assertTrue(
                    Files.isRegularFile(
                            out.resolveSibling("cooked.cache")
                                    .resolve("report")
                                    .resolve("report.html")));

            Map<Path, FileTime> before = times();
            Thread.sleep(20);
            CookReport again = c.cookAll();
            assertEquals(0, again.count(Status.COOKED), again.toJson());
            assertEquals(r.items().size(), again.count(Status.CACHED));
            assertEquals(before, times(), "a cached cook rewrites nothing");
            assertEquals(
                    r.item("textures/rock.png").stats(), again.item("textures/rock.png").stats());
        }
        // A fresh cooker (new process) reuses the cache too.
        try (Cooker c = cooker()) {
            assertEquals(0, c.cookAll().count(Status.COOKED));
        }
    }

    @Test
    void recooksWhatChangedAndRemovesStaleOutputs() throws Exception {
        sources();
        try (Cooker c = cooker()) {
            c.cookAll();
            // An edited texture cooks again; the material only refers to it by id.
            write("textures/rock.png", TestImages.painted(32, 9));
            Set<String> affected = c.affectedBy(List.of("textures/rock.png"));
            assertEquals(Set.of("textures/rock.png"), affected);
            CookReport r = c.cook(affected);
            assertEquals(Status.COOKED, r.item("textures/rock.png").status());
            assertEquals(
                    1, r.count(Status.COOKED), "only the texture and nothing else: " + r.summary());

            // Editing an include re-checks the shaders that use it.
            write(
                    "shaders/common/light.glsl",
                    "#pragma once\nvec3 light() { return vec3(0.5); }\n");
            assertEquals(
                    Set.of("shaders/common/light.glsl", "shaders/lit.frag"),
                    c.affectedBy(List.of("shaders/common/light.glsl")));

            // Directory defaults affect every source below them.
            assertTrue(
                    c.affectedBy(List.of("textures/_defaults.meta"))
                            .containsAll(
                                    Set.of(
                                            "textures/rock.png",
                                            "textures/rock_n.png",
                                            "textures/mask.png")));

            // A sidecar change recooks with the new settings.
            write("textures/mask.png.meta", "{\"kind\": \"linear\"}");
            r = c.cook(c.affectedBy(List.of("textures/mask.png.meta")));
            assertEquals(Status.COOKED, r.item("textures/mask.png").status());
            assertEquals(
                    Ktx2.VK_FORMAT_BC7_UNORM_BLOCK,
                    Ktx2.read(segment(out.resolve("textures/mask.ktx2"))).vkFormat());

            // A deleted source loses its outputs (and the directory they leave empty).
            Files.delete(src.resolve("audio/click.ogg"));
            r = c.cook(c.affectedBy(List.of("audio/click.ogg")));
            assertEquals(Status.REMOVED, r.item("audio/click.ogg").status());
            assertFalse(Files.exists(out.resolve("audio/click.ogg")));
            assertFalse(Files.exists(out.resolve("audio")));

            // A full cook also notices deletions nobody reported.
            Files.delete(src.resolve("textures/rock_n.png"));
            r = c.cookAll();
            assertEquals(Status.REMOVED, r.item("textures/rock_n.png").status());
            assertFalse(Files.exists(out.resolve("textures/rock_n.ktx2")));
            assertTrue(r.item("materials/rock.jmat.json").status() == Status.CACHED);
        }
    }

    @Test
    void failuresAreIsolatedAndReported() throws Exception {
        sources();
        write(
                "shaders/broken.frag",
                "#version 460 core\n#include \"missing.glsl\"\nvoid main() {}\n");
        write("models/bad.gltf", "{ not json");
        write("dup/a.png", TestImages.painted(8, 1));
        write("dup/a.jpg.meta", "{\"importer\": \"texture\"}");
        write("dup/a.jpg", TestImages.painted(8, 2)); // PNG bytes; ImageIO sniffs the content
        try (Cooker c = cooker()) {
            CookReport r = c.cookAll();
            assertTrue(r.failed());
            assertEquals(Status.FAILED, r.item("shaders/broken.frag").status());
            assertTrue(
                    r.item("shaders/broken.frag").error().contains("missing.glsl"),
                    r.item("shaders/broken.frag").error());
            assertEquals(Status.FAILED, r.item("models/bad.gltf").status());
            assertEquals(Status.FAILED, r.item("dup/a.png").status());
            assertEquals(Status.FAILED, r.item("dup/a.jpg").status());
            assertTrue(r.item("dup/a.png").error().contains("more than one source"));
            assertEquals(Status.COOKED, r.item("textures/rock.png").status());
            assertTrue(r.toHtml().contains("failed"));
            // Fixing the shader cooks it; the failure was not cached.
            write("shaders/missing.glsl", "float missing() { return 1.0; }\n");
            assertEquals(Status.COOKED, c.cookAll().item("shaders/broken.frag").status());
        }
    }

    @Test
    void forceIgnoresTheCacheAndPruneDropsUnusedEntries() throws Exception {
        sources();
        try (Cooker c = cooker()) {
            c.cookAll();
            write("textures/rock.png", TestImages.painted(32, 5));
            c.cookAll(); // the old rock entry is now unused
            assertTrue(c.pruneCache() >= 2, "an entry and its object");
        }
        try (Cooker c =
                new Cooker(
                        CookerConfig.of(src, out)
                                .withThreads(2)
                                .withForce(true)
                                .withShaders(List.of(), false))) {
            CookReport r = c.cookAll();
            assertEquals(0, r.count(Status.CACHED));
            assertFalse(r.failed());
        }
    }

    @Test
    void commandLine() throws Exception {
        sources();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        int code =
                CookerMain.run(
                        new String[] {
                            "--source",
                            src.toString(),
                            "--out",
                            out.toString(),
                            "--threads",
                            "2",
                            "--no-validate"
                        },
                        ps);
        assertEquals(0, code, bytes.toString(StandardCharsets.UTF_8));
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("cooked"));
        Path paks = dir.resolve("paks");
        code =
                CookerMain.run(
                        new String[] {
                            "pak",
                            "--cooked",
                            out.toString(),
                            "--out",
                            paks.toString(),
                            "--level",
                            "3"
                        },
                        ps);
        assertEquals(0, code, bytes.toString(StandardCharsets.UTF_8));
        assertTrue(Files.isRegularFile(paks.resolve("data.jpak")));
        assertEquals(2, CookerMain.run(new String[] {"--source", src.toString()}, ps));
        assertEquals(2, CookerMain.run(new String[] {"--bogus"}, ps));
        write("shaders/broken.frag", "#version 460 core\n#include \"nope.glsl\"\n");
        assertEquals(
                1,
                CookerMain.run(
                        new String[] {
                            "--source", src.toString(), "--out", out.toString(), "--no-validate"
                        },
                        ps));
        assertNotNull(
                Files.readString(
                        out.resolveSibling("cooked.cache")
                                .resolve("report")
                                .resolve("report.json")));
    }
}
