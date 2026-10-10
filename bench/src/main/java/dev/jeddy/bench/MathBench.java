package dev.jeddy.bench;

import dev.jeddy.math.AffineOps;
import dev.jeddy.math.CullOps;
import dev.jeddy.math.IsoCamera;
import dev.jeddy.math.Mat4Ops;

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
import org.openjdk.jmh.annotations.Warmup;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/** Math kernels on struct-of-arrays data (TASKS M1-12, M1-15). */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class MathBench {
    private static final int N = 10_000;

    private final float[] px = new float[N];
    private final float[] py = new float[N];
    private final float[] pz = new float[N];
    private final float[] qx = new float[N];
    private final float[] qy = new float[N];
    private final float[] qz = new float[N];
    private final float[] qw = new float[N];
    private final float[] s = new float[N];
    private final float[] radius = new float[N];
    private final int[] parent = new int[N];
    private final float[] local = new float[N * AffineOps.STRIDE];
    private final float[] world = new float[N * AffineOps.STRIDE];
    private final int[] visible = new int[N];
    private final float[] a = new float[16];
    private final float[] b = new float[16];
    private final float[] out = new float[16];
    private final IsoCamera camera = new IsoCamera();

    /** Random transforms, a shallow hierarchy and a camera. */
    @Setup(Level.Trial)
    public void setup() {
        Random r = new Random(3);
        for (int i = 0; i < N; i++) {
            px[i] = r.nextFloat() * 200 - 100;
            pz[i] = r.nextFloat() * 200 - 100;
            float angle = r.nextFloat() * 6.28f;
            qy[i] = (float) Math.sin(angle * 0.5);
            qw[i] = (float) Math.cos(angle * 0.5);
            s[i] = 1;
            radius[i] = 1;
            parent[i] = i % 8 == 0 ? -1 : i - 1;
        }
        for (int i = 0; i < 16; i++) {
            a[i] = r.nextFloat();
            b[i] = r.nextFloat();
        }
        camera.update(16f / 9f);
    }

    /** Compose 10k TRS transforms. */
    @Benchmark
    public float composeTRS10k() {
        AffineOps.composeTRS(px, py, pz, qx, qy, qz, qw, s, s, s, 0, N, local);
        return local[N * AffineOps.STRIDE - 1];
    }

    /** Hierarchy pass over 10k transforms. */
    @Benchmark
    public float concatenate10k() {
        AffineOps.concatenate(parent, local, world, 0, N);
        return world[N * AffineOps.STRIDE - 1];
    }

    /** Frustum-cull 10k bounding spheres. */
    @Benchmark
    public int cullSpheres10k() {
        return CullOps.spheres(camera.frustumPlanes, px, py, pz, radius, 0, N, visible, 0);
    }

    /** One 4×4 matrix multiply. */
    @Benchmark
    public float mat4Mul() {
        Mat4Ops.mul(a, 0, b, 0, out, 0);
        return out[15];
    }
}
