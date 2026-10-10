package dev.jeddy.cooker.pak;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.pak.Pak;
import dev.jeddy.asset.pak.PakFormat;
import dev.jeddy.compress.ZstdDecompressor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

/** Pak building: grouping, dictionaries, verification (TASKS M6-08). */
class PakBuilderTest {
    @TempDir Path dir;

    @Test
    void globs() {
        assertTrue(Glob.of("areas/tavern/**").matches("areas/tavern/a/b.jmesh"));
        assertFalse(Glob.of("areas/tavern/**").matches("areas/tavernx/b.jmesh"));
        assertTrue(Glob.of("**/*.ogg").matches("click.ogg"));
        assertTrue(Glob.of("**/*.ogg").matches("audio/ui/click.ogg"));
        assertFalse(Glob.of("*.ogg").matches("audio/click.ogg"));
        assertTrue(Glob.of("shaders/*.{vert,frag}").matches("shaders/lit.frag"));
        assertFalse(Glob.of("shaders/*.{vert,frag}").matches("shaders/lit.comp"));
        assertTrue(Glob.of("a?c.txt").matches("abc.txt"));
        assertTrue(Glob.of("Models/**").matches("models/x"), "case-insensitive");
        assertThrows(IllegalArgumentException.class, () -> Glob.of("{a,b"));
    }

    @Test
    void groupsTrainsADictionaryAndVerifies() throws Exception {
        Path cooked = dir.resolve("cooked");
        Random r = new Random(5);
        // 60 small, similar materials: dictionary material.
        for (int i = 0; i < 60; i++) {
            String json =
                    "{\"shading\":\"pbr\",\"baseColor\":["
                            + r.nextFloat()
                            + ",0.5,0.25,1],\"roughness\":"
                            + r.nextFloat()
                            + ",\"textures\":{\"baseColor\":\"textures/stone_"
                            + i
                            + ".ktx2\",\"normal\":\"textures/stone_"
                            + i
                            + "_n.ktx2\"}}";
            write(
                    cooked.resolve("materials/m" + i + ".json"),
                    json.getBytes(StandardCharsets.UTF_8));
        }
        byte[] big = new byte[200_000];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) (i % 251 < 100 ? i : r.nextInt());
        }
        write(cooked.resolve("areas/tavern/floor.jmesh"), big);
        write(cooked.resolve("audio/music.ogg"), "OggS...".getBytes(StandardCharsets.UTF_8));
        Files.writeString(
                dir.resolve("paks.json"),
                """
                {"default": "data", "level": 9,
                 "dictionary": {"size": 4096, "maxEntry": 4096, "minSamples": 16},
                 "paks": [{"name": "tavern", "include": ["areas/tavern/**"]},
                          {"name": "audio", "include": ["**/*.ogg"], "compression": "store"}]}
                """);
        Path out = dir.resolve("paks");
        List<PakBuilder.PakInfo> paks =
                PakBuilder.build(cooked, out, PakBuilder.Layout.read(dir.resolve("paks.json")), 2);
        assertEquals(
                List.of("tavern", "audio", "data"),
                paks.stream().map(PakBuilder.PakInfo::name).toList());
        PakBuilder.PakInfo data = paks.get(2);
        assertEquals(60, data.entries());
        assertTrue(data.dictionaryBytes() > 0, "trained a dictionary");
        assertTrue(Files.readString(out.resolve("paks.json")).contains("\"tavern.jpak\""));
        try (Pak pak = Pak.open(out.resolve("data.jpak"));
                ZstdDecompressor d = new ZstdDecompressor();
                Arena a = Arena.ofConfined()) {
            assertTrue(pak.find(PakFormat.DICTIONARY_ID) >= 0);
            int e = pak.find("materials/m7.json");
            MemorySegment dst = a.allocate(pak.rawSize(e));
            pak.read(e, dst, 0, d);
            assertArrayEquals(
                    Files.readAllBytes(cooked.resolve("materials/m7.json")),
                    dst.toArray(ValueLayout.JAVA_BYTE));
        }
        try (Pak pak = Pak.open(out.resolve("audio.jpak"))) {
            assertEquals(PakFormat.CODEC_STORED, pak.codec(pak.find("audio/music.ogg")));
        }
        try (Pak pak = Pak.open(out.resolve("tavern.jpak"))) {
            assertTrue(pak.storedSize(0) < big.length, "compressed");
        }
    }

    @Test
    void rejectsBadNamesAndUncookedPaths() throws Exception {
        Files.writeString(dir.resolve("bad.json"), "{\"default\": \"Data Pak\"}");
        assertThrows(
                IllegalArgumentException.class,
                () -> PakBuilder.Layout.read(dir.resolve("bad.json")));
        Path cooked = dir.resolve("cooked2");
        write(cooked.resolve("Upper/Case.bin"), new byte[] {1});
        assertThrows(
                java.io.IOException.class,
                () -> PakBuilder.build(cooked, dir.resolve("o"), PakBuilder.Layout.DEFAULT, 1));
    }

    private static void write(Path p, byte[] data) throws Exception {
        Files.createDirectories(p.getParent());
        Files.write(p, data);
    }
}
