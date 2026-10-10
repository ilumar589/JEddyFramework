package dev.jeddy.bench;

import dev.jeddy.cooker.texture.ImageRgba;
import dev.jeddy.cooker.texture.MipGenerator;
import dev.jeddy.cooker.texture.TextureCooker;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Cook time of one 2048² base color texture (TASKS M6-05 AC: ≤ 1.5 s on 8 cores): Kaiser mips in
 * Java, then UASTC level 1 → BC7 → zstd 10 through libktx with one encoder thread per core. {@code
 * mips} isolates the Java part. Single-shot: each operation is a whole cook.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 3)
@Fork(1)
public class TextureCookBench {
    @Param({"2048"})
    private int size;

    private ImageRgba image;
    private int threads;

    /** A painted-looking color image: smooth gradients with fine noise. */
    @Setup
    public void setup() {
        image = new ImageRgba(size, size);
        Random r = new Random(1);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double u = x / (double) size;
                double v = y / (double) size;
                image.set(x, y, 0, (float) (0.45 + 0.35 * Math.sin(u * 23 + Math.sin(v * 7))));
                image.set(x, y, 1, (float) (0.4 + 0.3 * Math.cos(v * 19 + u * 3)));
                image.set(
                        x,
                        y,
                        2,
                        (float) (0.3 + 0.2 * Math.sin((u + v) * 31) + r.nextGaussian() * 0.01));
                image.set(x, y, 3, 1);
            }
        }
        threads = Runtime.getRuntime().availableProcessors();
    }

    /** The whole texture cook. */
    @Benchmark
    public byte[] cook() throws IOException {
        TextureCooker.Cooked c =
                TextureCooker.cook(
                        image, TextureCooker.Options.defaults(TextureCooker.Kind.COLOR), threads);
        return c.ktx2();
    }

    /** The Java mip chain alone. */
    @Benchmark
    public List<ImageRgba> mips() {
        return MipGenerator.chain(image, MipGenerator.Options.COLOR);
    }
}
