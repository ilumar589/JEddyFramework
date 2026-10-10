package dev.jeddy.asset.pak;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.pak.PakWriter.Compression;
import dev.jeddy.compress.Zstd;
import dev.jeddy.compress.ZstdDecompressor;
import dev.jeddy.compress.ZstdDictionary;
import dev.jeddy.core.id.StrId;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/** Pak round trips, layout, corruption detection and allocation-free reads (TASKS M4-03). */
class PakTest {
    @TempDir Path dir;

    private static byte[] material(Random r, int i) {
        String json =
                "{\"name\":\"mat_"
                        + i
                        + "\",\"shader\":\"pbr_opaque\",\"baseColor\":[0."
                        + r.nextInt(10)
                        + ",0.5,0.25,1.0],\"metallic\":0."
                        + r.nextInt(10)
                        + ",\"roughness\":0.8,\"textures\":{\"albedo\":\"tex/stone_"
                        + r.nextInt(20)
                        + "_albedo.ktx2\",\"normal\":\"tex/stone_"
                        + r.nextInt(20)
                        + "_normal.ktx2\",\"orm\":\"tex/stone_orm.ktx2\"},\"flags\":[\"cast\",\"receive\"]}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] dictionary(Random r) {
        int count = 400;
        byte[][] samples = new byte[count][];
        long[] sizes = new long[count];
        int total = 0;
        for (int i = 0; i < count; i++) {
            samples[i] = material(r, i);
            sizes[i] = samples[i].length;
            total += samples[i].length;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment all = arena.allocate(total);
            long p = 0;
            for (byte[] s : samples) {
                MemorySegment.copy(s, 0, all, ValueLayout.JAVA_BYTE, p, s.length);
                p += s.length;
            }
            return ZstdDictionary.train(all, sizes, 4096);
        }
    }

    /** Builds a pak with every codec, an empty entry and a large entry; returns path → content. */
    private Map<String, byte[]> writeSample(Path file, boolean names) throws IOException {
        Random r = new Random(42);
        Map<String, byte[]> content = new LinkedHashMap<>();
        byte[] noise = new byte[10_000];
        r.nextBytes(noise);
        byte[] large = new byte[(1 << 20) + 123];
        for (int i = 0; i < large.length; i++) {
            large[i] = (byte) (i % 251 < 200 ? i % 7 : r.nextInt());
        }
        byte[] text = "dialogue line ".repeat(500).getBytes(StandardCharsets.UTF_8);
        try (PakWriter w = new PakWriter(Zstd.DEFAULT_LEVEL).names(names)) {
            w.dictionary(dictionary(r));
            content.put("text/dialogue.txt", text);
            w.add("text/dialogue.txt", text, Compression.ZSTD);
            content.put("tex/noise.bin", noise);
            w.add("tex/noise.bin", noise, Compression.AUTO); // incompressible: stored
            content.put("mesh/large.jmesh", large);
            w.add("mesh/large.jmesh", large, Compression.STORE); // page-aligned
            content.put("empty", new byte[0]);
            w.add("empty", new byte[0], Compression.STORE);
            for (int i = 0; i < 20; i++) {
                byte[] m = material(r, 1000 + i);
                content.put("mat/m" + i + ".jmat", m);
                w.add(
                        "mat/m" + i + ".jmat",
                        m,
                        i % 2 == 0 ? Compression.ZSTD_DICTIONARY : Compression.AUTO);
            }
            byte[] raw = {1, 2, 3, 4, 5};
            content.put("raw/five.bin", raw);
            try (Arena a = Arena.ofConfined()) {
                w.add(
                        "raw/five.bin",
                        a.allocateFrom(ValueLayout.JAVA_BYTE, raw),
                        Compression.STORE);
            }
            assertEquals(content.size() + 1, w.size());
            w.write(file);
        }
        return content;
    }

    @Test
    void roundTripsEveryCodec() throws IOException {
        Path file = dir.resolve("data.jpak");
        Map<String, byte[]> content = writeSample(file, true);
        try (Pak pak = Pak.open(file);
                Arena arena = Arena.ofConfined();
                ZstdDecompressor d = new ZstdDecompressor()) {
            assertEquals(content.size() + 1, pak.size());
            assertNotNull(pak.dictionary());
            int codecsSeen = 0;
            for (Map.Entry<String, byte[]> e : content.entrySet()) {
                int i = pak.find(StrId.of(e.getKey()));
                assertTrue(i >= 0, e.getKey());
                assertEquals(e.getKey(), pak.name(i));
                assertEquals(e.getValue().length, pak.rawSize(i));
                assertTrue(pak.verify(i), e.getKey());
                assertEquals(0, pak.offset(i) % PakFormat.ENTRY_ALIGNMENT, e.getKey());
                if (pak.storedSize(i) >= PakFormat.LARGE_ENTRY) {
                    assertEquals(0, pak.offset(i) % PakFormat.PAGE_ALIGNMENT, e.getKey());
                }
                MemorySegment out = arena.allocate(Math.max(1, pak.rawSize(i)) + 16);
                assertEquals(e.getValue().length, pak.read(i, out, 16, d));
                assertArrayEquals(
                        e.getValue(),
                        out.asSlice(16, e.getValue().length).toArray(ValueLayout.JAVA_BYTE),
                        e.getKey());
                codecsSeen |= 1 << pak.codec(i);
            }
            assertEquals(0b111, codecsSeen, "stored, zstd and dictionary entries");
            int large = pak.find(StrId.of("mesh/large.jmesh"));
            assertEquals(0, pak.offset(large) % PakFormat.PAGE_ALIGNMENT, "large entry");
            assertEquals(PakFormat.CODEC_STORED, pak.codec(pak.find(StrId.of("tex/noise.bin"))));
            assertEquals(
                    PakFormat.CODEC_ZSTD_DICTIONARY, pak.codec(pak.find(StrId.of("mat/m0.jmat"))));
            assertEquals(-1, pak.find(StrId.of("not/there")));
            for (int i = 1; i < pak.size(); i++) {
                assertTrue(pak.id(i - 1) < pak.id(i), "sorted ids");
            }
        }
    }

    @Test
    void releasePaksCanDropNamesAndChecksums() throws IOException {
        Path file = dir.resolve("release.jpak");
        try (PakWriter w = new PakWriter(Zstd.RELEASE_LEVEL).names(false).crc(false)) {
            w.add("a", "alpha".repeat(100).getBytes(StandardCharsets.UTF_8), Compression.AUTO);
            w.write(file);
        }
        try (Pak pak = Pak.open(file)) {
            assertEquals(1, pak.size());
            assertNull(pak.name(0));
            assertNull(pak.dictionary());
            assertTrue(pak.verify(0));
            assertEquals(PakFormat.CODEC_ZSTD, pak.codec(0));
        }
    }

    @Test
    void emptyPak() throws IOException {
        Path file = dir.resolve("empty.jpak");
        try (PakWriter w = new PakWriter(1)) {
            w.write(file);
        }
        assertEquals(PakFormat.PAGE_ALIGNMENT, Files.size(file));
        try (Pak pak = Pak.open(file)) {
            assertEquals(0, pak.size());
            assertEquals(-1, pak.find(StrId.of("x")));
        }
    }

    @Test
    void rejectsDuplicatesAndMisuse() {
        try (PakWriter w = new PakWriter(1)) {
            w.add("a/b", new byte[10], Compression.STORE);
            IllegalArgumentException e =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> w.add("a/b", new byte[3], Compression.ZSTD));
            assertTrue(e.getMessage().contains("duplicate"), e.getMessage());
            assertThrows(
                    IllegalStateException.class,
                    () -> w.add("c", new byte[10], Compression.ZSTD_DICTIONARY));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> w.add("__jpak/dictionary", new byte[1], Compression.STORE));
        }
    }

    @Test
    void detectsCorruption() throws IOException {
        Path file = dir.resolve("data.jpak");
        writeSample(file, true);
        byte[] good = Files.readAllBytes(file);
        int dataOffset = (int) ByteBuffer.wrap(good).order(ByteOrder.LITTLE_ENDIAN).getLong(32);

        assertCorrupt(dir.resolve("magic.jpak"), good, 0, "not a .jpak");
        assertCorrupt(dir.resolve("version.jpak"), good, 4, "version");
        assertCorrupt(dir.resolve("toc.jpak"), good, PakFormat.HEADER_SIZE + 3, "CRC");

        Path truncated = dir.resolve("truncated.jpak");
        Files.write(truncated, java.util.Arrays.copyOf(good, good.length - 1));
        IOException e = assertThrows(IOException.class, () -> Pak.open(truncated));
        assertTrue(e.getMessage().contains("truncated"), e.getMessage());

        // A flipped data byte opens fine and is caught by verify().
        Path data = dir.resolve("entry.jpak");
        try (Pak pak = Pak.open(file)) {
            int i = pak.find(StrId.of("text/dialogue.txt"));
            byte[] bad = good.clone();
            bad[(int) pak.offset(i) + 5] ^= 0x40;
            Files.write(data, bad);
            assertTrue(pak.offset(i) >= dataOffset);
        }
        try (Pak pak = Pak.open(data)) {
            int i = pak.find(StrId.of("text/dialogue.txt"));
            assertFalse(pak.verify(i));
            assertTrue(pak.verify(pak.find(StrId.of("tex/noise.bin"))));
        }
    }

    private static void assertCorrupt(Path file, byte[] good, int offset, String message)
            throws IOException {
        byte[] bad = good.clone();
        bad[offset] ^= 0x01;
        Files.write(file, bad);
        IOException e = assertThrows(IOException.class, () -> Pak.open(file));
        assertTrue(e.getMessage().contains(message), e.getMessage());
    }

    @Test
    void writeReplacesTheTargetAndLeavesNoTemporaryFiles() throws IOException {
        Path file = dir.resolve("out/data.jpak");
        writeSample(file, false);
        long first = Files.size(file);
        try (PakWriter w = new PakWriter(1)) {
            w.add("only", new byte[100], Compression.STORE);
            w.write(file);
        }
        assertTrue(Files.size(file) < first);
        try (var files = Files.list(file.getParent())) {
            assertEquals(1, files.count());
        }
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            assertEquals(Files.size(file), ch.size());
        }
    }

    @Test
    void lookupsAndReadsAllocateNothing() throws IOException {
        Path file = dir.resolve("data.jpak");
        writeSample(file, false);
        try (Pak pak = Pak.open(file);
                Arena arena = Arena.ofConfined();
                ZstdDecompressor d = new ZstdDecompressor()) {
            long stored = StrId.of("tex/noise.bin");
            long zstd = StrId.of("text/dialogue.txt");
            long dict = StrId.of("mat/m4.jmat");
            pak.dictionary(); // digested once, on first use
            MemorySegment out = arena.allocate(1 << 16);
            Allocations.assertNoAllocation(
                    "pak find + read",
                    200,
                    2_000,
                    () -> {
                        pak.read(pak.find(stored), out, 0, d);
                        pak.read(pak.find(zstd), out, 0, d);
                        pak.read(pak.find(dict), out, 0, d);
                    });
        }
    }
}
