package dev.jeddy.render.graph;

import static dev.jeddy.gl.ffi.GL46.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.core.profile.FrameStats;
import dev.jeddy.gl.GLDebug;
import dev.jeddy.gl.GLShaders;
import dev.jeddy.math.Mat4Ops;
import dev.jeddy.render.GpuTestContext;
import dev.jeddy.render.RenderPacket;
import dev.jeddy.render.debug.DebugDraw;
import dev.jeddy.render.debug.DebugRenderer;
import dev.jeddy.render.debug.Overlay;
import dev.jeddy.render.debug.ProfilerOverlay;
import dev.jeddy.render.gpu.FrameRing;
import dev.jeddy.render.gpu.GpuBuffers;
import dev.jeddy.render.gpu.GpuDevice;
import dev.jeddy.render.gpu.PipelineState;
import dev.jeddy.render.gpu.SamplerDesc;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/** Frame graph, barriers, timers, debug lines and the overlay (TASKS M5-10). */
@Tag("gpu")
class FrameGraphTest {
    private static final int SIZE = 128;
    private static GpuTestContext context;
    private static GpuDevice device;
    private static final Arena ARENA = Arena.ofShared();

    @BeforeAll
    static void init() {
        context = new GpuTestContext("frame graph test", SIZE);
        device = new GpuDevice(context.caps());
        device.useReversedZ();
    }

    @AfterAll
    static void shutdown() {
        device.close();
        context.close();
        ARENA.close();
    }

    private static int pixel(int x, int y, int channel) {
        MemorySegment px = ARENA.allocate(4);
        glReadPixels(x, y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px);
        return Byte.toUnsignedInt(px.get(ValueLayout.JAVA_BYTE, channel));
    }

    @Test
    void computeIndirectSceneAndPostRunWithBarriersAndTimers() {
        long errors = GLDebug.errorCount();
        int fill =
                GLShaders.link(
                        GLShaders.compile(
                                GL_COMPUTE_SHADER,
                                """
                                #version 460 core
                                layout(local_size_x = 1) in;
                                layout(std430, binding = 4) buffer Args { uint args[4]; };
                                void main() { args[0] = 3u; args[1] = 1u; args[2] = 0u; args[3] = 0u; }
                                """));
        int tri =
                GLShaders.program(
                        """
                        #version 460 core
                        const vec2 P[3] = vec2[3](vec2(-0.5, -0.5), vec2(0.5, -0.5), vec2(0.0, 0.5));
                        void main() { gl_Position = vec4(P[gl_VertexID], 0.5, 1.0); }
                        """,
                        """
                        #version 460 core
                        layout(location = 0) out vec4 c;
                        void main() { c = vec4(1.0, 0.0, 0.0, 1.0); }
                        """);
        int post =
                GLShaders.program(
                        """
                        #version 460 core
                        layout(location = 0) out vec2 uv;
                        void main() {
                            vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                            uv = p;
                            gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
                        }
                        """,
                        """
                        #version 460 core
                        layout(binding = 0) uniform sampler2D scene;
                        layout(location = 0) in vec2 uv;
                        layout(location = 0) out vec4 c;
                        void main() { c = texture(scene, uv); }
                        """);
        MemorySegment noVao = ARENA.allocate(4);
        glCreateVertexArrays(1, noVao);
        int vao = noVao.get(ValueLayout.JAVA_INT, 0);
        int argsBuffer = device.buffers().create(16, GpuBuffers.DYNAMIC, "indirect args");
        int sampler = device.samplers().get(SamplerDesc.NEAREST_CLAMP);

        try (FrameGraph graph = new FrameGraph(device);
                FrameRing ring = new FrameRing(device, 1 << 20, 3, "graph ring");
                Overlay overlay = new Overlay(device, 4096);
                DebugRenderer lines = new DebugRenderer(device)) {
            int depth = graph.texture("depth", GL_DEPTH_COMPONENT32F, 1f);
            int color = graph.texture("color", GL_RGBA8, 1f);
            int half = graph.texture("half", GL_RGBA8, 0.5f);
            int args = graph.importedBuffer("args");
            graph.setBuffer(args, device.buffers().glName(argsBuffer));
            graph.addComputePass(
                            "fill args",
                            ctx -> {
                                ctx.state().useProgram(fill);
                                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 4, ctx.buffer(args));
                                glDispatchCompute(1, 1, 1);
                            })
                    .writes(args);
            graph.addPass(
                            "scene",
                            ctx -> {
                                ctx.state()
                                        .apply(
                                                PipelineState.withCull(
                                                        PipelineState.OPAQUE,
                                                        PipelineState.CULL_NONE));
                                ctx.state().useProgram(tri);
                                ctx.state().bindVertexArray(vao);
                                ctx.state().bindDrawIndirect(ctx.buffer(args));
                                glDrawArraysIndirect(GL_TRIANGLES, 0L);
                            })
                    .readsIndirect(args)
                    .color(color, 0f, 0f, 1f, 1f)
                    .depth(depth, 0f);
            graph.addPass(
                            "unused",
                            ctx -> {
                                throw new AssertionError("culled passes never run");
                            })
                    .color(half, 1, 1, 1, 1);
            graph.addPass(
                            "post",
                            ctx -> {
                                ctx.state().apply(PipelineState.FULLSCREEN);
                                ctx.state().useProgram(post);
                                ctx.state().bindTexture(0, ctx.texture(color));
                                ctx.state().bindSampler(0, sampler);
                                ctx.state().bindVertexArray(vao);
                                glDrawArrays(GL_TRIANGLES, 0, 3);
                            })
                    .readsTexture(color)
                    .color(FrameGraph.BACKBUFFER);
            graph.compile(SIZE, SIZE);
            assertEquals(3, graph.livePassCount());
            assertFalse(graph.isLive(2));

            RenderPacket packet = new RenderPacket(1);
            Mat4Ops.orthoReversedZ(-1, 1, -1, 1, -1, 1, packet.viewProjection, 0);
            DebugDraw debug = new DebugDraw(4, 64);
            ProfilerOverlay profiler = new ProfilerOverlay(overlay);
            FrameStats stats = new FrameStats(60);
            stats.record(4_000_000);
            boolean[] withProfiler = {false};
            Runnable frame =
                    new Runnable() {
                        long n;

                        @Override
                        public void run() {
                            ring.beginFrame();
                            packet.reset();
                            debug.line(
                                    1,
                                    -1,
                                    0.9f,
                                    0,
                                    1,
                                    0.9f,
                                    0,
                                    0xFF00FF00); // green line near the top
                            debug.collect(packet);
                            graph.execute(n++);
                            lines.draw(packet, device.state(), ring);
                            overlay.rect(0, SIZE - 10, 10, 10, 0xFFFFFF00); // yellow, bottom-left
                            if (withProfiler[0]) {
                                profiler.queue(stats, null, graph, 3); // covers the small window
                            }
                            overlay.draw(device.state(), ring, SIZE, SIZE);
                            ring.endFrame();
                        }
                    };
            for (int i = 0; i < 12; i++) {
                frame.run();
            }
            glFinish();
            assertEquals(
                    255, pixel(SIZE / 2, SIZE / 2 - 10, 0), "the indirect triangle, through post");
            assertEquals(255, pixel(SIZE - 3, SIZE / 3, 2), "the cleared blue");
            assertEquals(255, pixel(SIZE / 2, (int) (SIZE * 0.95f), 1), "the debug line");
            assertEquals(255, pixel(4, 4, 0), "the overlay rectangle (yellow)");
            assertEquals(255, pixel(4, 4, 1));
            assertTrue(graph.barriers() >= 12, "a command barrier per frame: " + graph.barriers());
            double gpu = 0;
            for (int p = 0; p < graph.passCount(); p++) {
                assertTrue(graph.passMillis(p) >= 0);
                gpu += graph.passMillis(p);
            }
            assertTrue(gpu > 0, "timer queries returned results");
            withProfiler[0] = true;
            Allocations.assertNoAllocation("graph frame", 10, 20, frame);
        } finally {
            device.buffers().destroy(argsBuffer);
            glDeleteProgram(fill);
            glDeleteProgram(tri);
            glDeleteProgram(post);
            noVao.set(ValueLayout.JAVA_INT, 0, vao);
            glDeleteVertexArrays(1, noVao);
        }
        assertEquals(errors, GLDebug.errorCount());
    }

    @Test
    void readingBeforeWritingIsAnError() {
        try (FrameGraph graph = new FrameGraph(device)) {
            int a = graph.texture("a", GL_RGBA8, 1f);
            graph.addPass("reads a", ctx -> {}).readsTexture(a).color(FrameGraph.BACKBUFFER);
            graph.addPass("writes a", ctx -> {}).color(a).sideEffect();
            IllegalStateException e =
                    assertThrows(IllegalStateException.class, () -> graph.compile(16, 16));
            assertTrue(e.getMessage().contains("before any pass writes it"), e.getMessage());
        }
    }
}
