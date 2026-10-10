package dev.jeddy.render.draw;

import static dev.jeddy.gl.ffi.GL46.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.format.MeshData;
import dev.jeddy.asset.format.MeshWriter;
import dev.jeddy.core.job.JobSystem;
import dev.jeddy.gl.GLDebug;
import dev.jeddy.math.AffineOps;
import dev.jeddy.math.CullOps;
import dev.jeddy.math.Mat4Ops;
import dev.jeddy.render.GpuTestContext;
import dev.jeddy.render.RenderPacket;
import dev.jeddy.render.gpu.FrameRing;
import dev.jeddy.render.gpu.GpuDevice;
import dev.jeddy.render.gpu.UploadRing;
import dev.jeddy.render.mesh.MeshRegistry;
import dev.jeddy.render.shader.ProgramCache;
import dev.jeddy.render.shader.ShaderFeatures;
import dev.jeddy.render.shader.ShaderSources;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.NoSuchFileException;

/** Culling, sort keys, batching and multi-draw-indirect for 20k instances (TASKS M5-08, M5-09). */
@Tag("gpu")
class DrawBuilderTest {
    private static final int SIZE = 128;
    private static GpuTestContext context;
    private static GpuDevice device;
    private static JobSystem jobs;
    private static final Arena ARENA = Arena.ofShared();

    private static final String FLAT_VERT =
            """
            #version 460 core
            #include "/engine/vertex.glsl"
            #include "/engine/instance.glsl"
            layout(location = 0) uniform mat4 viewProjection;
            layout(location = 0) flat out uint material;
            void main() {
                JeddyInstance i = jeddyInstance();
                material = i.data.x;
                gl_Position = viewProjection * vec4(jeddyTransformPoint(i, jeddyPosition), 1.0);
            }
            """;

    private static final String FLAT_FRAG =
            """
            #version 460 core
            layout(std430, binding = 1) readonly buffer Materials { vec4 rows[]; };
            layout(location = 0) flat in uint material;
            layout(location = 0) out vec4 color;
            void main() {
                color = rows[material * 4u];
            #ifdef HALF
                color.rgb *= 0.5;
            #endif
            }
            """;

    @BeforeAll
    static void init() {
        context = new GpuTestContext("draw builder test", SIZE);
        device = new GpuDevice(context.caps());
        jobs = new JobSystem(3);
    }

    @AfterAll
    static void shutdown() {
        jobs.close();
        device.close();
        context.close();
        ARENA.close();
    }

    /** A unit quad in the XY plane centred on the origin with two submeshes (left, right half). */
    private static MeshData splitQuad() {
        byte[] bytes =
                new MeshWriter()
                        .positions(
                                new float[] {
                                    -0.5f,
                                    -0.5f,
                                    0,
                                    0,
                                    -0.5f,
                                    0,
                                    0,
                                    0.5f,
                                    0,
                                    -0.5f,
                                    0.5f,
                                    0, // left
                                    0,
                                    -0.5f,
                                    0,
                                    0.5f,
                                    -0.5f,
                                    0,
                                    0.5f,
                                    0.5f,
                                    0,
                                    0,
                                    0.5f,
                                    0 // right
                                })
                        .attributes(new float[24], null, null, null)
                        .indices(new int[] {0, 1, 2, 0, 2, 3, 0, 1, 2, 0, 2, 3, 0, 1, 2, 0, 1, 2})
                        .submesh(0, 0, 4)
                        .submesh(1, 4, 4)
                        .lod(0, new int[] {0, 6}, new int[] {6, 6})
                        .lod(0.5f, new int[] {12, 15}, new int[] {3, 3})
                        .materials(1, 2)
                        .toBytes();
        MemorySegment s = ARENA.allocate(bytes.length, 64);
        MemorySegment.copy(bytes, 0, s, ValueLayout.JAVA_BYTE, 0, bytes.length);
        try {
            return MeshData.read(s);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void twentyThousandInstancesBecomeAFewMultiDraws() {
        long errors = GLDebug.errorCount();
        ShaderSources engine =
                ShaderSources.classpath(MeshRegistry.class, "/dev/jeddy/render/shaders");
        ShaderSources sources =
                path ->
                        switch (path) {
                            case "flat.vert" -> FLAT_VERT;
                            case "flat.frag" -> FLAT_FRAG;
                            default -> {
                                if (path.startsWith("engine/")) {
                                    yield engine.load(path);
                                }
                                throw new NoSuchFileException(path);
                            }
                        };
        try (UploadRing upload = new UploadRing(device, 1 << 20, 1 << 20);
                MeshRegistry meshes = new MeshRegistry(device, upload, 1024, 1024);
                ProgramCache programs = new ProgramCache(sources, context.caps(), null);
                MaterialTable materials = new MaterialTable(device, programs, 8);
                FrameRing ring = new FrameRing(device, 4 << 20, 3, "draw ring")) {
            upload.beginFrame();
            int quad = meshes.add(splitQuad());
            upload.endFrame();
            int flat =
                    programs.define("flat", "flat.vert", "flat.frag", new ShaderFeatures("HALF"));
            float[] row = new float[MaterialTable.FLOATS];
            row[0] = 1; // red
            row[3] = 1;
            int red = materials.add(flat, 0, row);
            row[0] = 0;
            row[1] = 1; // green, drawn at half brightness by the HALF variant
            int green = materials.add(flat, 1, row);
            materials.flush();
            assertEquals(2, materials.programSlots());
            for (int slot = 0; slot < 2; slot++) {
                int program;
                while ((program = materials.glProgram(slot)) == 0) {
                    Thread.onSpinWait();
                }
                float[] vp = new float[16];
                Mat4Ops.orthoReversedZ(-100, 100, -100, 100, -10, 10, vp, 0);
                glProgramUniformMatrix4fv(
                        program, 0, 1, false, ARENA.allocateFrom(ValueLayout.JAVA_FLOAT, vp));
            }

            // 200 x 100 grid in [-100, 100]: the left half red, the right half green; one extra
            // row far outside the frustum.
            RenderPacket packet = new RenderPacket(20_100);
            float[] world = new float[12];
            for (int y = 0; y < 100; y++) {
                for (int x = 0; x < 200; x++) {
                    AffineOps.identity(world, 0);
                    world[3] = -99.5f + x;
                    world[7] = -99f + 2 * y;
                    int i =
                            packet.add(
                                    world,
                                    0,
                                    world,
                                    0,
                                    quad,
                                    x < 100 ? red : green,
                                    0,
                                    y * 200 + x);
                    packet.setBounds(i, world[3], world[7], 0, 0.75f);
                }
            }
            for (int x = 0; x < 100; x++) {
                AffineOps.identity(world, 0);
                world[3] = 500 + x;
                int i = packet.add(world, 0, world, 0, quad, red, 0, -1);
                packet.setBounds(i, world[3], 0, 0, 0.75f);
            }
            packet.alpha = 1;
            float[] vp = new float[16];
            Mat4Ops.orthoReversedZ(-100, 100, -100, 100, -10, 10, vp, 0);
            CullOps.extractPlanes(vp, 0, packet.frustumPlanes);
            packet.eye[2] = 5;

            Culling culling = new Culling(jobs);
            DrawBuilder builder = new DrawBuilder(meshes, materials, jobs);
            builder.setLod(
                    SIZE / 200f,
                    true,
                    1f); // orthographic: 0.64 px per unit, LOD 1 error 0.5 → 0.32 px
            int[] visible = new int[packet.capacity()];

            glViewport(0, 0, SIZE, SIZE);
            MemorySegment clear = ARENA.allocateFrom(ValueLayout.JAVA_FLOAT, 0f, 0f, 0f, 1f);
            Runnable frame =
                    () -> {
                        ring.beginFrame();
                        int n = culling.cull(packet, visible);
                        builder.build(packet, visible, n, ring);
                        glClearNamedFramebufferfv(0, GL_COLOR, 0, clear);
                        builder.submit(device.state(), ring);
                        ring.endFrame();
                    };
            frame.run();
            assertEquals(40_000, builder.itemCount(), "20k visible instances x 2 submeshes");
            assertEquals(2, builder.batchCount(), "one multi-draw per program variant");
            assertTrue(
                    builder.commandCount() <= 4,
                    "runs per mesh, LOD, submesh: " + builder.commandCount());
            assertEquals(20_000, culling.cull(packet, visible));

            MemorySegment px = ARENA.allocate(4);
            glReadPixels(10, SIZE / 2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px);
            assertEquals(
                    255, Byte.toUnsignedInt(px.get(ValueLayout.JAVA_BYTE, 0)), "red on the left");
            glReadPixels(SIZE - 10, SIZE / 2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px);
            assertEquals(
                    128,
                    Byte.toUnsignedInt(px.get(ValueLayout.JAVA_BYTE, 1)),
                    1,
                    "half green on the right");

            for (int i = 0; i < 20; i++) {
                frame.run(); // warm up before measuring
            }
            long t0 = System.nanoTime();
            for (int i = 0; i < 20; i++) {
                ring.beginFrame();
                int n = culling.cull(packet, visible);
                builder.build(packet, visible, n, ring);
                ring.endFrame();
            }
            double ms = (System.nanoTime() - t0) / 20 / 1e6;
            System.out.printf("cull + build for 20k instances: %.2f ms per frame%n", ms);
            Allocations.assertNoAllocation("cull, build, submit", 10, 20, frame);
        }
        assertEquals(errors, GLDebug.errorCount());
    }
}
