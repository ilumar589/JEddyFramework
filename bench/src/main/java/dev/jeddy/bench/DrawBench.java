package dev.jeddy.bench;

import static dev.jeddy.gl.ffi.GL46.*;

import dev.jeddy.asset.format.MeshData;
import dev.jeddy.asset.format.MeshWriter;
import dev.jeddy.core.job.JobSystem;
import dev.jeddy.gl.GLCapabilities;
import dev.jeddy.gl.GLLoader;
import dev.jeddy.math.AffineOps;
import dev.jeddy.math.CullOps;
import dev.jeddy.math.Mat4Ops;
import dev.jeddy.render.RenderPacket;
import dev.jeddy.render.draw.Culling;
import dev.jeddy.render.draw.DrawBuilder;
import dev.jeddy.render.draw.MaterialTable;
import dev.jeddy.render.gpu.FrameRing;
import dev.jeddy.render.gpu.GpuDevice;
import dev.jeddy.render.mesh.MeshRegistry;
import dev.jeddy.render.shader.ProgramCache;
import dev.jeddy.render.shader.ShaderFeatures;
import dev.jeddy.window.Platform;
import dev.jeddy.window.VSync;
import dev.jeddy.window.Window;
import dev.jeddy.window.WindowConfig;

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

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Main-thread submission for 20k instances (TASKS M5-08 AC: ≤ 2 ms, 0 B/frame): culling, sort keys,
 * the parallel sort, instance records and indirect commands built on the job system, then the GL
 * calls. Needs a GL 4.6 context (CI: Xvfb + llvmpipe, where the driver's own vertex work makes
 * {@code submit} slow; compare {@code cullAndBuild} with the budget).
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class DrawBench {
    private static final String VS =
            """
            #version 460 core
            #include "/engine/vertex.glsl"
            #include "/engine/instance.glsl"
            void main() {
                JeddyInstance i = jeddyInstance();
                gl_Position = vec4(jeddyTransformPoint(i, jeddyPosition) * 0.001, 1.0);
            }
            """;
    private static final String FS =
            """
            #version 460 core
            layout(location = 0) out vec4 color;
            void main() { color = vec4(1.0); }
            """;

    private Window window;
    private Arena arena;
    private JobSystem jobs;
    private GpuDevice device;
    private MeshRegistry meshes;
    private ProgramCache programs;
    private MaterialTable materials;
    private FrameRing ring;
    private RenderPacket packet;
    private Culling culling;
    private DrawBuilder builder;
    private int[] visible;

    /** Creates the context and a 20k-instance packet over 8 meshes and 16 materials. */
    @Setup(Level.Trial)
    public void setup() throws Exception {
        Platform.init("Jeddy draw bench", "0.1", "dev.jeddy.bench.draw");
        window =
                Window.create(
                        new WindowConfig()
                                .hidden(true)
                                .size(64, 64)
                                .minimumSize(16, 16)
                                .vsync(VSync.OFF));
        GLCapabilities caps = GLLoader.init(window::glProcAddress);
        arena = Arena.ofShared();
        jobs = new JobSystem(JobSystem.defaultWorkerCount());
        device = new GpuDevice(caps);
        meshes = new MeshRegistry(device, null, 1 << 16, 1 << 16);
        var engine =
                dev.jeddy.asset.shader.ShaderSources.classpath(
                        MeshRegistry.class, "/dev/jeddy/render/shaders");
        programs =
                new ProgramCache(
                        path ->
                                path.equals("b.vert")
                                        ? VS
                                        : path.equals("b.frag") ? FS : engine.load(path),
                        caps,
                        null);
        int def = programs.define("bench", "b.vert", "b.frag", new ShaderFeatures("A", "B"));
        materials = new MaterialTable(device, programs, 16);
        float[] row = new float[MaterialTable.FLOATS];
        for (int m = 0; m < 16; m++) {
            materials.add(def, m % 4, row);
        }
        materials.flush();
        for (int s = 0; s < 4; s++) {
            programs.getBlocking(def, s);
        }
        int[] meshHandles = new int[8];
        for (int m = 0; m < 8; m++) {
            byte[] bytes =
                    new MeshWriter()
                            .positions(new float[] {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0})
                            .attributes(new float[12], null, null, null)
                            .indices(new int[] {0, 1, 2, 0, 2, 3, 0, 1, 2})
                            .lod(0, new int[] {0}, new int[] {6})
                            .lod(0.5f, new int[] {6}, new int[] {3})
                            .toBytes();
            MemorySegment seg = arena.allocate(bytes.length, 64);
            MemorySegment.copy(bytes, 0, seg, ValueLayout.JAVA_BYTE, 0, bytes.length);
            meshHandles[m] = meshes.add(MeshData.read(seg));
        }
        ring = new FrameRing(device, 4 << 20, 3, "bench ring");
        packet = new RenderPacket(20_000);
        Random r = new Random(1);
        float[] world = new float[12];
        for (int i = 0; i < 20_000; i++) {
            AffineOps.identity(world, 0);
            world[3] = r.nextFloat() * 200 - 100;
            world[11] = r.nextFloat() * 200 - 100;
            int idx =
                    packet.add(world, 0, world, 0, meshHandles[r.nextInt(8)], r.nextInt(16), 0, i);
            packet.setBounds(idx, world[3], 0, world[11], 1);
        }
        packet.alpha = 0.5f;
        float[] vp = new float[16];
        Mat4Ops.orthoReversedZ(-150, 150, -150, 150, -150, 150, vp, 0);
        CullOps.extractPlanes(vp, 0, packet.frustumPlanes);
        culling = new Culling(jobs);
        builder = new DrawBuilder(meshes, materials, jobs);
        builder.setLod(500, false, 1);
        visible = new int[20_000];
    }

    /** Releases everything. */
    @TearDown(Level.Trial)
    public void tearDown() {
        glFinish();
        ring.close();
        materials.close();
        programs.close();
        meshes.close();
        device.close();
        jobs.close();
        window.close();
        Platform.shutdown();
        arena.close();
    }

    /** Culling, keys, sort, records and commands (the CPU side of submission). */
    @Benchmark
    public int cullAndBuild() {
        ring.beginFrame();
        int n = culling.cull(packet, visible);
        builder.build(packet, visible, n, ring);
        ring.endFrame();
        return builder.commandCount();
    }

    /** {@link #cullAndBuild} plus the GL calls (state, bindings, multi-draws). */
    @Benchmark
    public int cullBuildSubmit() {
        ring.beginFrame();
        int n = culling.cull(packet, visible);
        builder.build(packet, visible, n, ring);
        builder.submit(device.state(), ring);
        ring.endFrame();
        return builder.batchCount();
    }
}
