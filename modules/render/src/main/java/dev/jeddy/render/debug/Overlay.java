package dev.jeddy.render.debug;

import static dev.jeddy.gl.ffi.GL46.*;

import dev.jeddy.gl.GLShaders;
import dev.jeddy.render.gpu.FrameRing;
import dev.jeddy.render.gpu.GpuDevice;
import dev.jeddy.render.gpu.PipelineState;
import dev.jeddy.render.gpu.StateCache;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * Screen-space text and rectangles for debug overlays (TASKS M5-10): every glyph or rectangle is
 * one instanced quad pulled from a storage buffer, and the fragment shader tests the glyph's bit in
 * a 5×7 font table, so there is no font texture. Text from {@link CharSequence}s and numbers
 * formatted into a scratch buffer, so a frame of overlay allocates nothing.
 *
 * <p>Thread ownership: the GL thread.
 */
public final class Overlay implements AutoCloseable {
    private static final int GLYPH_BYTES = 32;
    private static final ValueLayout.OfFloat F32 =
            ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.nativeOrder());
    private static final ValueLayout.OfInt I32 =
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.nativeOrder());

    private static final String VS =
            """
            #version 460 core
            struct Glyph { vec4 rect; uvec4 data; };
            layout(std430, binding = 2) readonly buffer Glyphs { Glyph glyphs[]; };
            layout(location = 0) uniform vec2 screen;
            layout(location = 0) out vec2 cell;
            layout(location = 1) flat out uint code;
            layout(location = 2) flat out vec4 color;
            const vec2 CORNERS[6] = vec2[6](vec2(0, 0), vec2(1, 0), vec2(1, 1), vec2(0, 0), vec2(1, 1), vec2(0, 1));
            void main() {
                Glyph g = glyphs[gl_InstanceID];
                vec2 c = CORNERS[gl_VertexID];
                vec2 p = g.rect.xy + c * g.rect.zw;
                gl_Position = vec4(p.x / screen.x * 2.0 - 1.0, 1.0 - p.y / screen.y * 2.0, 0.0, 1.0);
                cell = c * vec2(6.0, 8.0);
                code = g.data.x;
                vec4 argb = unpackUnorm4x8(g.data.y);
                color = vec4(argb.z, argb.y, argb.x, argb.w);
            }
            """;
    private static final String FS =
            """
            #version 460 core
            layout(std430, binding = 3) readonly buffer Font { uvec2 font[]; };
            layout(location = 0) in vec2 cell;
            layout(location = 1) flat in uint code;
            layout(location = 2) flat in vec4 color;
            layout(location = 0) out vec4 outColor;
            void main() {
                if (code != 127u) {
                    ivec2 c = ivec2(cell);
                    if (c.x >= 5 || c.y >= 7) discard;
                    uvec2 g = font[code];
                    uint row = uint(c.y);
                    uint bits = row < 4u ? (g.x >> (row * 5u)) & 31u : (g.y >> ((row - 4u) * 5u)) & 31u;
                    if (((bits >> (4u - uint(c.x))) & 1u) == 0u) discard;
                }
                outColor = color;
            }
            """;

    private final GpuDevice device;
    private final int program;
    private final int font;
    private final int vao;
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment cpu;
    private final int capacity;
    private int count;
    private final char[] digits = new char[32];

    /** Creates an overlay for up to {@code maxGlyphs} glyphs and rectangles per frame. */
    public Overlay(GpuDevice device, int maxGlyphs) {
        this.device = device;
        this.capacity = maxGlyphs;
        program = GLShaders.program(VS, FS);
        int[] table = Font5x7.table();
        MemorySegment t = arena.allocate(4L * table.length, 16);
        MemorySegment.copy(table, 0, t, ValueLayout.JAVA_INT, 0, table.length);
        font = device.buffers().create(t, 0, "overlay font");
        cpu = arena.allocate((long) maxGlyphs * GLYPH_BYTES, 16);
        MemorySegment id = arena.allocate(4);
        glCreateVertexArrays(1, id);
        vao = id.get(ValueLayout.JAVA_INT, 0);
    }

    /** Adds a filled rectangle (pixels, origin top-left). */
    public void rect(float x, float y, float w, float h, int argb) {
        glyph(x, y, w, h, Font5x7.SOLID, argb);
    }

    /**
     * Adds text at {@code (x, y)} (top-left, pixels) with glyphs {@code 6 × scale} pixels apart.
     *
     * @return the x after the last glyph
     */
    public float text(float x, float y, float scale, int argb, CharSequence s) {
        float cx = x;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ' ') {
                glyph(cx, y, 6 * scale, 8 * scale, c < 127 ? c : '?', argb);
            }
            cx += 6 * scale;
        }
        return cx;
    }

    /** Adds a number with {@code decimals} digits after the point. Allocates nothing. */
    public float number(float x, float y, float scale, int argb, double value, int decimals) {
        long factor = 1;
        for (int i = 0; i < decimals; i++) {
            factor *= 10;
        }
        boolean negative = value < 0;
        long v = Math.round(Math.abs(value) * factor);
        int n = 0;
        do {
            if (n == decimals && decimals > 0) {
                digits[n++] = '.';
            }
            digits[n++] = (char) ('0' + v % 10);
            v /= 10;
        } while ((v > 0 || n <= decimals) && n < digits.length - 1);
        if (negative) {
            digits[n++] = '-';
        }
        float cx = x;
        for (int i = n - 1; i >= 0; i--) {
            glyph(cx, y, 6 * scale, 8 * scale, digits[i], argb);
            cx += 6 * scale;
        }
        return cx;
    }

    private void glyph(float x, float y, float w, float h, int code, int argb) {
        if (count == capacity) {
            return;
        }
        long o = (long) count * GLYPH_BYTES;
        cpu.set(F32, o, x);
        cpu.set(F32, o + 4, y);
        cpu.set(F32, o + 8, w);
        cpu.set(F32, o + 12, h);
        cpu.set(I32, o + 16, code);
        cpu.set(I32, o + 20, argb);
        count++;
    }

    /** Draws and clears this frame's glyphs into the bound framebuffer. */
    public void draw(StateCache state, FrameRing ring, int width, int height) {
        if (count == 0) {
            return;
        }
        long bytes = (long) count * GLYPH_BYTES;
        long at = ring.allocate(bytes, 256);
        if (at < 0) {
            count = 0;
            return;
        }
        MemorySegment.copy(cpu, 0, ring.memory(), at, bytes);
        state.apply(PipelineState.withBlend(PipelineState.FULLSCREEN, PipelineState.BLEND_ALPHA));
        state.useProgram(program);
        state.bindVertexArray(vao);
        state.bindStorage(2, ring.glName(), at, bytes);
        state.bindStorage(3, device.buffers().glName(font), 0, device.buffers().size(font));
        glProgramUniform2f(program, 0, width, height);
        glDrawArraysInstanced(GL_TRIANGLES, 0, 6, count);
        count = 0;
    }

    /** Returns the glyphs queued this frame. */
    public int queued() {
        return count;
    }

    @Override
    public void close() {
        glDeleteProgram(program);
        MemorySegment id = arena.allocate(4);
        id.set(ValueLayout.JAVA_INT, 0, vao);
        glDeleteVertexArrays(1, id);
        device.buffers().destroy(font);
        arena.close();
    }
}
