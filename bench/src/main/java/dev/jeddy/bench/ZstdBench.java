package dev.jeddy.bench;

import dev.jeddy.compress.Zstd;
import dev.jeddy.compress.ZstdCompressor;
import dev.jeddy.compress.ZstdDecompressor;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * zstd throughput through the FFM facade on 8 MiB of asset-like data (TASKS M4-01): divide 8 MiB by
 * the time per operation for MB/s and compare with {@code zstd -b<level>} on the same machine. The
 * facade only adds a fixed ~20 ns per call, so the goal (within 10 % of the CLI) is about reusing
 * contexts, not about FFM. With {@code JEDDY_ZSTD_CORPUS} set, the corpus is also written to that
 * file for {@code zstd -b}.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(1)
public class ZstdBench {
    private static final int SIZE = 8 << 20;

    @Param({"1", "3", "19"})
    private int level;

    private Arena arena;
    private MemorySegment src;
    private MemorySegment packed;
    private MemorySegment out;
    private long packedSize;
    private ZstdCompressor compressor;
    private ZstdDecompressor decompressor;

    /** Builds the corpus: half vertex-like floats, a quarter text, a quarter noise. */
    @Setup(Level.Trial)
    public void setup() {
        arena = Arena.ofShared();
        src = arena.allocate(SIZE, 64);
        Random r = new Random(11);
        for (int i = 0; i < SIZE / 2; i += 4) {
            src.set(ValueLayout.JAVA_FLOAT, i, (i % 389) * 0.125f + r.nextInt(8));
        }
        byte[] text =
                "material crypt_wall_04 roughness 0.82 metallic 0.0 normal t_crypt_wall_04_n "
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        for (int i = SIZE / 2; i < SIZE * 3 / 4; i++) {
            src.set(ValueLayout.JAVA_BYTE, i, text[(i * 7 + r.nextInt(2)) % text.length]);
        }
        for (int i = SIZE * 3 / 4; i < SIZE; i++) {
            src.set(ValueLayout.JAVA_BYTE, i, (byte) r.nextInt());
        }
        String corpus = System.getenv("JEDDY_ZSTD_CORPUS"); // for the CLI comparison in CI
        if (corpus != null && level == 1) {
            try {
                Files.write(Path.of(corpus), src.toArray(ValueLayout.JAVA_BYTE));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        packed = arena.allocate(Zstd.compressBound(SIZE), 64);
        out = arena.allocate(SIZE, 64);
        compressor = new ZstdCompressor(level);
        decompressor = new ZstdDecompressor();
        packedSize = compressor.compress(packed, src);
    }

    /** Frees the contexts. */
    @TearDown(Level.Trial)
    public void tearDown() {
        compressor.close();
        decompressor.close();
        arena.close();
    }

    /** Compresses 8 MiB at the given level. */
    @Benchmark
    public long compress() {
        return compressor.compress(packed, src);
    }

    /** Decompresses the 8 MiB frame. */
    @Benchmark
    public long decompress() {
        return decompressor.decompress(out, 0, SIZE, packed, 0, packedSize);
    }
}
