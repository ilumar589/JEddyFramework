package dev.jeddy.bench;

import dev.jeddy.asset.AssetPaths;
import dev.jeddy.asset.Vfs;
import dev.jeddy.asset.pak.Pak;
import dev.jeddy.asset.pak.PakWriter;
import dev.jeddy.compress.ZstdDecompressor;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Asset lookups and reads (TASKS M4-03, M4-04): a pak TOC binary search over 10k entries, a VFS
 * lookup, and reading a 64 KiB zstd entry straight from the mapping.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(1)
public class AssetBench {
    private static final int ENTRIES = 10_000;

    private Path dir;
    private Pak pak;
    private Vfs vfs;
    private long[] ids;
    private int next;
    private long bigId;
    private Arena arena;
    private MemorySegment out;
    private ZstdDecompressor decompressor;

    /** Writes a pak of 10k small entries and one 64 KiB entry. */
    @Setup(Level.Trial)
    public void setup() throws IOException {
        dir = Files.createTempDirectory("jeddy-asset-bench");
        Path file = dir.resolve("bench.jpak");
        Random r = new Random(5);
        ids = new long[ENTRIES];
        try (PakWriter w = new PakWriter(3).names(false)) {
            byte[] small = new byte[64];
            for (int i = 0; i < ENTRIES; i++) {
                String path = "mat/m" + i + ".jmat";
                ids[i] = AssetPaths.id(path);
                w.add(path, small, PakWriter.Compression.STORE);
            }
            byte[] big = new byte[64 << 10];
            for (int i = 0; i < big.length; i++) {
                big[i] = (byte) (i % 61 < 40 ? i % 13 : r.nextInt());
            }
            w.add("mesh/big.jmesh", big, PakWriter.Compression.ZSTD);
            w.write(file);
        }
        bigId = AssetPaths.id("mesh/big.jmesh");
        pak = Pak.open(file);
        vfs = new Vfs();
        vfs.mountPak(file);
        // Shuffle the lookup order so the branch predictor cannot learn it.
        for (int i = ENTRIES - 1; i > 0; i--) {
            int j = r.nextInt(i + 1);
            long t = ids[i];
            ids[i] = ids[j];
            ids[j] = t;
        }
        arena = Arena.ofConfined();
        out = arena.allocate(64 << 10, 64);
        decompressor = new ZstdDecompressor();
    }

    /** Releases the pak and deletes the files. */
    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        decompressor.close();
        arena.close();
        vfs.close();
        pak.close();
        try (var files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                Files.delete(p);
            }
        }
        Files.delete(dir);
    }

    /** Binary search in the pak's sorted TOC. */
    @Benchmark
    public int pakFind() {
        long id = ids[next++ % ENTRIES];
        return pak.find(id);
    }

    /** The VFS index lookup (hash of every visible id). */
    @Benchmark
    public long vfsSize() {
        long id = ids[next++ % ENTRIES];
        return vfs.size(id);
    }

    /** Reads (decompresses) a 64 KiB zstd entry from the mapping. */
    @Benchmark
    public long vfsRead64KiB() throws IOException {
        return vfs.read(bigId, out, 0, out.byteSize(), decompressor);
    }
}
