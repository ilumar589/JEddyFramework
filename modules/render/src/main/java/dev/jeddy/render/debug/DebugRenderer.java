package dev.jeddy.render.debug;

import static dev.jeddy.gl.ffi.GL46.*;

import dev.jeddy.gl.GLShaders;
import dev.jeddy.render.RenderPacket;
import dev.jeddy.render.gpu.FrameRing;
import dev.jeddy.render.gpu.GpuDevice;
import dev.jeddy.render.gpu.PipelineState;
import dev.jeddy.render.gpu.StateCache;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * Draws a packet's debug lines (TASKS M5-10): vertices pulled from the frame ring by {@code
 * gl_VertexID}, depth-tested against the scene without writing depth; lines from {@code
 * RenderPacket.debugOverlayStart} on are drawn without the depth test (an editor's gizmo).
 *
 * <p>Thread ownership: the GL thread.
 */
public final class DebugRenderer implements AutoCloseable {
    private static final ValueLayout.OfFloat F32 =
            ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.nativeOrder());
    private static final ValueLayout.OfInt I32 =
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.nativeOrder());

    private static final String VS =
            """
            #version 460 core
            layout(std430, binding = 2) readonly buffer Lines { vec4 vertices[]; };
            layout(location = 0) uniform mat4 viewProjection;
            layout(location = 0) out vec4 color;
            void main() {
                vec4 v = vertices[gl_VertexID];
                vec4 argb = unpackUnorm4x8(floatBitsToUint(v.w));
                color = vec4(argb.z, argb.y, argb.x, argb.w);
                gl_Position = viewProjection * vec4(v.xyz, 1.0);
            }
            """;
    private static final String FS =
            """
            #version 460 core
            layout(location = 0) in vec4 color;
            layout(location = 0) out vec4 outColor;
            void main() { outColor = color; }
            """;

    private final int program;
    private final int vao;
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment matrix = arena.allocate(64, 16);

    /** Compiles the line program. */
    public DebugRenderer(GpuDevice device) {
        program = GLShaders.program(VS, FS);
        MemorySegment id = arena.allocate(4);
        glCreateVertexArrays(1, id);
        vao = id.get(ValueLayout.JAVA_INT, 0);
    }

    /** Draws {@code packet}'s debug lines with its camera into the bound framebuffer. */
    public void draw(RenderPacket packet, StateCache state, FrameRing ring) {
        int lines = packet.debugLineCount;
        if (lines == 0) {
            return;
        }
        long bytes = (long) lines * 32;
        long at = ring.allocate(bytes, 256);
        if (at < 0) {
            return;
        }
        MemorySegment m = ring.memory();
        float[] p = packet.debugLines;
        int[] c = packet.debugColors;
        for (int i = 0; i < lines; i++) {
            long o = at + (long) i * 32;
            int s = i * 6;
            m.set(F32, o, p[s]);
            m.set(F32, o + 4, p[s + 1]);
            m.set(F32, o + 8, p[s + 2]);
            m.set(I32, o + 12, c[i]);
            m.set(F32, o + 16, p[s + 3]);
            m.set(F32, o + 20, p[s + 4]);
            m.set(F32, o + 24, p[s + 5]);
            m.set(I32, o + 28, c[i]);
        }
        MemorySegment.copy(packet.viewProjection, 0, matrix, ValueLayout.JAVA_FLOAT, 0, 16);
        state.useProgram(program);
        state.bindVertexArray(vao);
        state.bindStorage(2, ring.glName(), at, bytes);
        glProgramUniformMatrix4fv(program, 0, 1, false, matrix);
        int overlay =
                packet.debugOverlayStart < 0 ? lines : Math.min(packet.debugOverlayStart, lines);
        if (overlay > 0) {
            state.apply(PipelineState.withDepthWrite(PipelineState.TRANSPARENT, false));
            glDrawArrays(GL_LINES, 0, overlay * 2);
        }
        if (overlay < lines) {
            // The overlay (an editor's gizmo) ignores the scene's depth so it shows through walls.
            state.apply(
                    PipelineState.withDepth(
                            PipelineState.TRANSPARENT, false, false, PipelineState.DEPTH_ALWAYS));
            glDrawArrays(GL_LINES, overlay * 2, (lines - overlay) * 2);
        }
    }

    @Override
    public void close() {
        glDeleteProgram(program);
        MemorySegment id = arena.allocate(4);
        id.set(ValueLayout.JAVA_INT, 0, vao);
        glDeleteVertexArrays(1, id);
        arena.close();
    }
}
