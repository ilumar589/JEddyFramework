package dev.jeddy.compress;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/** zstd facade: fuzzed round trips, streaming, dictionaries, errors, allocation (TASKS M4-01). */
class ZstdTest {
    private static ZstdCompressor compressor;
    private static ZstdDecompressor decompressor;
    private static final Arena ARENA = Arena.ofShared();

    @BeforeAll
    static void init() {
        compressor = new ZstdCompressor(Zstd.DEFAULT_LEVEL);
        decompressor = new ZstdDecompressor();
    }

    @AfterAll
    static void close() {
        compressor.close();
        decompressor.close();
    }

    /** Data that looks like game assets: vertex floats, repeated text, noise, zero runs. */
    private static byte[] sample(Random r, int size, int kind) {
        byte[] b = new byte[size];
        switch (kind) {
            case 0 -> r.nextBytes(b);
            case 1 -> {
                byte[] text =
                        "{\"mesh\":\"crypt_floor_02\",\"lod\":2,\"material\":\"stone\"}"
                                .getBytes(StandardCharsets.UTF_8);
                for (int i = 0; i < size; i++) {
                    b[i] = text[(i + r.nextInt(3)) % text.length];
                }
            }
            case 2 -> {
                MemorySegment s = MemorySegment.ofArray(b);
                for (int i = 0; i + 4 <= size; i += 4) {
                    s.set(ValueLayout.JAVA_FLOAT_UNALIGNED, i, (i % 97) * 0.25f + r.nextInt(4));
                }
            }
            default -> {} // zeros
        }
        return b;
    }

    @Test
    void oneShotRoundTripsFuzzed() {
        Random r = new Random(42);
        for (int round = 0; round < 60; round++) {
            int size = round == 0 ? 0 : r.nextInt(1 << (4 + r.nextInt(16)));
            byte[] data = sample(r, size, round % 4);
            int level = round % 10 == 0 ? 19 : 1 + r.nextInt(5);
            try (Arena a = Arena.ofConfined()) {
                MemorySegment src = a.allocateFrom(ValueLayout.JAVA_BYTE, data);
                MemorySegment packed = a.allocate(Math.max(1, Zstd.compressBound(size)));
                long n = compressor.level(level).compress(packed, src);
                MemorySegment frame = packed.asSlice(0, n);
                assertEquals(size, Zstd.frameContentSize(frame), "content size in header");
                assertEquals(n, Zstd.frameCompressedSize(frame));
                MemorySegment out = a.allocate(Math.max(1, size));
                long m = decompressor.decompress(out.asSlice(0, size), frame);
                assertEquals(size, m);
                assertArrayEquals(data, out.asSlice(0, size).toArray(ValueLayout.JAVA_BYTE));
            }
        }
        compressor.level(Zstd.DEFAULT_LEVEL);
    }

    @Test
    void streamingRoundTripsInSmallChunks() {
        Random r = new Random(7);
        byte[] data = sample(r, 300_000, 2);
        try (Arena a = Arena.ofConfined()) {
            MemorySegment src = a.allocateFrom(ValueLayout.JAVA_BYTE, data);
            MemorySegment packed = a.allocate(Zstd.compressBound(data.length) + 1024);
            long in = 0;
            long out = 0;
            compressor.reset();
            while (true) {
                long chunk = Math.min(1 + r.nextInt(5000), data.length - in);
                boolean last = in + chunk == data.length;
                long room = Math.min(1 + r.nextInt(3000), packed.byteSize() - out);
                long left =
                        compressor.compressStream(
                                src,
                                in,
                                chunk,
                                packed,
                                out,
                                room,
                                last ? ZstdCompressor.END : ZstdCompressor.CONTINUE);
                in += compressor.consumed();
                out += compressor.produced();
                if (last && in == data.length && left == 0) {
                    break;
                }
            }
            MemorySegment frame = packed.asSlice(0, out);
            assertEquals(Zstd.CONTENT_SIZE_UNKNOWN, Zstd.frameContentSize(frame));

            MemorySegment dst = a.allocate(data.length);
            long read = 0;
            long written = 0;
            decompressor.reset();
            long hint = 1;
            while (hint != 0) {
                long chunk = Math.min(1 + r.nextInt(4000), out - read);
                hint =
                        decompressor.decompressStream(
                                packed,
                                read,
                                chunk,
                                dst,
                                written,
                                Math.min(8192, data.length - written));
                read += decompressor.consumed();
                written += decompressor.produced();
            }
            assertEquals(data.length, written);
            assertArrayEquals(data, dst.toArray(ValueLayout.JAVA_BYTE));
        }
    }

    @Test
    void dictionariesShrinkSmallSimilarAssets() {
        Random r = new Random(3);
        String[] names = {"crypt", "swamp", "keep", "forest", "city", "cave"};
        int count = 600;
        byte[][] samples = new byte[count][];
        long[] sizes = new long[count];
        int total = 0;
        for (int i = 0; i < count; i++) {
            String json =
                    "{\"shading\":\"pbr\",\"name\":\""
                            + names[r.nextInt(names.length)]
                            + "_"
                            + i
                            + "\",\"baseColor\":[0."
                            + r.nextInt(1000)
                            + ",0.5,0.25,1.0],\"roughness\":0."
                            + r.nextInt(100)
                            + ",\"metallic\":0.0,\"textures\":{\"albedo\":\"t_"
                            + i
                            + "_bc\",\"normal\":\"t_"
                            + i
                            + "_n\",\"orm\":\"t_"
                            + i
                            + "_orm\"},\"alpha\":\"opaque\"}";
            samples[i] = json.getBytes(StandardCharsets.UTF_8);
            sizes[i] = samples[i].length;
            total += samples[i].length;
        }
        MemorySegment all = ARENA.allocate(total);
        long offset = 0;
        for (byte[] s : samples) {
            MemorySegment.copy(s, 0, all, ValueLayout.JAVA_BYTE, offset, s.length);
            offset += s.length;
        }
        byte[] dictBytes = ZstdDictionary.train(all, sizes, 16 * 1024);
        assertTrue(dictBytes.length > 1000, "dictionary size " + dictBytes.length);
        try (ZstdDictionary dict =
                        ZstdDictionary.of(MemorySegment.ofArray(dictBytes), Zstd.DEFAULT_LEVEL);
                ZstdCompressor withDict = new ZstdCompressor(Zstd.DEFAULT_LEVEL);
                ZstdDecompressor unpack = new ZstdDecompressor();
                Arena a = Arena.ofConfined()) {
            assertTrue(dict.id() != 0);
            withDict.dictionary(dict);
            unpack.dictionary(dict);
            long plainTotal = 0;
            long dictTotal = 0;
            MemorySegment packed = a.allocate(4096);
            MemorySegment out = a.allocate(4096);
            for (byte[] s : samples) {
                MemorySegment src = a.allocateFrom(ValueLayout.JAVA_BYTE, s);
                plainTotal += compressor.compress(packed, src);
                long n = withDict.compress(packed, src);
                dictTotal += n;
                long m = unpack.decompress(out.asSlice(0, s.length), packed.asSlice(0, n));
                assertEquals(s.length, m);
                assertArrayEquals(s, out.asSlice(0, s.length).toArray(ValueLayout.JAVA_BYTE));
            }
            assertTrue(
                    dictTotal * 2 < plainTotal,
                    "dictionary " + dictTotal + " bytes vs plain " + plainTotal);
        }
    }

    @Test
    void errorsBecomeExceptions() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment src =
                    a.allocateFrom(ValueLayout.JAVA_BYTE, sample(new Random(1), 10_000, 0));
            MemorySegment tiny = a.allocate(16);
            ZstdException tooSmall =
                    assertThrows(ZstdException.class, () -> compressor.compress(tiny, src));
            assertTrue(tooSmall.getMessage().contains("ZSTD_compress2"), tooSmall.getMessage());
            assertTrue(tooSmall.code() > 0);
            MemorySegment garbage =
                    a.allocateFrom(ValueLayout.JAVA_BYTE, new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9});
            assertThrows(
                    ZstdException.class, () -> decompressor.decompress(a.allocate(64), garbage));
            assertThrows(ZstdException.class, () -> Zstd.frameContentSize(garbage));
        }
        assertTrue(Zstd.isError(-1L));
        assertTrue(!Zstd.isError(12345L));
        assertTrue(Zstd.versionNumber() >= 10507);
    }

    @Test
    void warmCallsAllocateNothing() {
        byte[] data = sample(new Random(5), 64 * 1024, 2);
        MemorySegment src = ARENA.allocateFrom(ValueLayout.JAVA_BYTE, data);
        MemorySegment packed = ARENA.allocate(Zstd.compressBound(data.length));
        MemorySegment out = ARENA.allocate(data.length);
        long[] sink = new long[1];
        Allocations.assertNoAllocation(
                "zstd compress + decompress",
                10,
                50,
                () -> {
                    long n = compressor.compress(packed, src);
                    sink[0] += decompressor.decompress(out, 0, out.byteSize(), packed, 0, n);
                    sink[0] +=
                            compressor.compress(packed, 16, packed.byteSize() - 16, src, 0, 4096);
                });
        Allocations.assertNoAllocation(
                "zstd stream",
                10,
                50,
                () -> {
                    compressor.compressStream(
                            src, 0, 4096, packed, 0, packed.byteSize(), ZstdCompressor.END);
                    sink[0] += compressor.produced();
                });
    }

    @Test
    void crc32cMatchesTheStandardCheckValue() {
        byte[] check = "123456789".getBytes(StandardCharsets.US_ASCII);
        assertEquals(0xE3069283, Checksums.crc32c(check));
        assertEquals(0xE3069283, Checksums.crc32c(MemorySegment.ofArray(check)));
        try (Arena a = Arena.ofConfined()) {
            MemorySegment off = a.allocateFrom(ValueLayout.JAVA_BYTE, check);
            assertEquals(0xE3069283, Checksums.crc32c(off));
            assertEquals(Checksums.crc32c(new byte[] {'3', '4', '5'}), Checksums.crc32c(off, 2, 3));
        }
    }
}
