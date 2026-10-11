package dev.jeddy.render.ui;

import static dev.jeddy.gl.ffi.GL46.*;

import dev.jeddy.gl.GLShaders;
import dev.jeddy.render.gpu.FrameRing;
import dev.jeddy.render.gpu.GpuDevice;
import dev.jeddy.render.gpu.GpuFormats;
import dev.jeddy.render.gpu.PipelineState;
import dev.jeddy.render.gpu.SamplerDesc;
import dev.jeddy.render.gpu.StateCache;
import dev.jeddy.render.material.TextureArrays;
import dev.jeddy.ui.UiDrawList;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Draws a {@link UiDrawList} (TASKS M13-06, DESIGN §18, D84). The list's vertices, indices and clip
 * rectangles are copied into a frame ring; the vertex shader pulls vertices from the ring as a
 * storage buffer (no vertex attribute setup), the fragment shader tests each pixel against the
 * vertex's clip rectangle, renders rounded rectangles and borders as signed-distance boxes, decodes
 * glyphs from the signed-distance atlas with screen-space antialiasing, and samples sprites. Then
 * one {@code glDrawElements} per command (a command changes when a texture does), so a frame of UI
 * is usually a single draw.
 *
 * <p>Colors are sRGB display values blended with premultiplied alpha, like a browser: the UI draws
 * onto the already encoded display image after post-processing. Sprite textures cooked as sRGB are
 * re-encoded after sampling so they blend in the same space.
 *
 * <p>Coordinates: the list is in logical pixels; {@code displayScale} maps them to the viewport's
 * physical pixels, so a UI declared at 1280×720 logical looks the same on a 2× display.
 *
 * <p>Thread ownership: the GL thread.
 */
public final class UiRenderer implements AutoCloseable {
    private static final int VERTEX_BYTES = UiDrawList.VERTEX_FLOATS * 4;
    private static final int LOC_SCREEN = 0;
    private static final int LOC_VIEWPORT = 1;
    private static final int LOC_SCALE = 2;
    private static final int LOC_SPRITE_SRGB = 3;

    private static final String VS =
            """
            #version 460 core
            // One UI vertex: x, y, u, v, color bits, misc bits, p0, p1, p2, p3 (UiDrawList).
            layout(std430, binding = 4) readonly buffer Verts { uint v[]; };
            layout(location = 0) uniform vec2 screen; // logical pixels
            layout(location = 0) out vec2 uv;
            layout(location = 1) out vec4 color;
            layout(location = 2) flat out uint misc;
            layout(location = 3) flat out vec4 params;
            void main() {
                uint o = uint(gl_VertexID) * 10u;
                vec2 p = vec2(uintBitsToFloat(v[o]), uintBitsToFloat(v[o + 1u]));
                uv = vec2(uintBitsToFloat(v[o + 2u]), uintBitsToFloat(v[o + 3u]));
                vec4 bgra = unpackUnorm4x8(v[o + 4u]);
                color = vec4(bgra.z, bgra.y, bgra.x, bgra.w);
                misc = v[o + 5u];
                params = vec4(uintBitsToFloat(v[o + 6u]), uintBitsToFloat(v[o + 7u]),
                              uintBitsToFloat(v[o + 8u]), uintBitsToFloat(v[o + 9u]));
                gl_Position = vec4(p.x / screen.x * 2.0 - 1.0, 1.0 - p.y / screen.y * 2.0, 0.0, 1.0);
            }
            """;

    private static final String FS =
            """
            #version 460 core
            layout(std430, binding = 5) readonly buffer Clips { vec4 clips[]; }; // x, y, w, h
            layout(binding = 0) uniform sampler2DArray fontAtlas;
            layout(binding = 1) uniform sampler2DArray sprites;
            layout(location = 1) uniform vec2 viewport;   // physical pixels
            layout(location = 2) uniform float scale;     // physical per logical pixel
            layout(location = 3) uniform int spriteSrgb;  // 1: the sprite array is sRGB-encoded
            layout(location = 0) in vec2 uv;
            layout(location = 1) in vec4 color;
            layout(location = 2) flat in uint misc;
            layout(location = 3) flat in vec4 params;
            layout(location = 0) out vec4 outColor;

            vec3 encodeSrgb(vec3 c) {
                vec3 lo = c * 12.92;
                vec3 hi = 1.055 * pow(max(c, vec3(0.0)), vec3(1.0 / 2.4)) - 0.055;
                return mix(lo, hi, step(vec3(0.0031308), c));
            }

            void main() {
                uint kind = misc & 0xFu;
                uint clip = (misc >> 4) & 0xFFFu;
                float layer = float(misc >> 16);
                // Clip: the vertex's rectangle, in physical pixels with y down.
                vec4 c = clips[clip] * scale;
                vec2 p = vec2(gl_FragCoord.x, viewport.y - gl_FragCoord.y);
                if (p.x < c.x || p.y < c.y || p.x >= c.x + c.z || p.y >= c.y + c.w) {
                    discard;
                }
                vec4 col = color;
                float coverage = 1.0;
                if (kind == 0u) {
                    // Rounded rectangle: uv is the position relative to the center, params its
                    // half size, corner radius and border width. d < 0 inside.
                    vec2 q = abs(uv) - (params.xy - params.z);
                    float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - params.z;
                    float aa = max(fwidth(d), 1e-4);
                    coverage = clamp(0.5 - d / aa, 0.0, 1.0);
                    if (params.w > 0.0) {
                        coverage *= clamp(0.5 + (d + params.w) / aa, 0.0, 1.0);
                    }
                } else if (kind == 1u) {
                    // Glyph: the atlas holds 0.5 + d / (2R); params.x = 2R * size / em turns it
                    // into a logical-pixel distance, positive inside the outline.
                    float s = texture(fontAtlas, vec3(uv, layer)).r;
                    float d = (s - 0.5) * params.x;
                    float aa = max(fwidth(d), 1e-4);
                    coverage = clamp(d / aa + 0.5, 0.0, 1.0);
                } else {
                    vec4 t = texture(sprites, vec3(uv, layer));
                    col.rgb *= spriteSrgb != 0 ? encodeSrgb(t.rgb) : t.rgb;
                    col.a *= t.a;
                }
                float a = col.a * coverage;
                outColor = vec4(col.rgb * a, a);
            }
            """;

    private final GpuDevice device;
    private final int program;
    private final int vao;
    private final int fontSampler;
    private final int spriteSampler;
    private final Arena arena = Arena.ofConfined();
    private long lastDraws;
    private long lastQuads;

    /** Creates the renderer; {@code ring} must be the one passed to {@link #draw} (its buffer). */
    public UiRenderer(GpuDevice device, FrameRing ring) {
        this.device = device;
        program = GLShaders.program(VS, FS);
        MemorySegment id = arena.allocate(4);
        glCreateVertexArrays(1, id);
        vao = id.get(ValueLayout.JAVA_INT, 0);
        glVertexArrayElementBuffer(vao, ring.glName());
        fontSampler = device.samplers().get(SamplerDesc.LINEAR_CLAMP);
        spriteSampler =
                device.samplers()
                        .get(
                                SamplerDesc.of(
                                        SamplerDesc.LINEAR,
                                        SamplerDesc.LINEAR,
                                        SamplerDesc.MIP_LINEAR,
                                        SamplerDesc.CLAMP));
    }

    /**
     * Draws {@code list} into the bound framebuffer ({@code viewportWidth × viewportHeight}
     * physical pixels, {@code displayScale} physical per logical pixel). Texture references in the
     * list are {@link TextureArrays} references into {@code arrays}. Allocates nothing.
     */
    public void draw(
            StateCache state,
            FrameRing ring,
            UiDrawList list,
            TextureArrays arrays,
            int viewportWidth,
            int viewportHeight,
            float displayScale) {
        lastDraws = 0;
        lastQuads = list.quadCount();
        if (list.indexCount() == 0 || viewportWidth <= 0 || viewportHeight <= 0) {
            return;
        }
        long vBytes = (long) list.vertexCount() * VERTEX_BYTES;
        long iBytes = (long) list.indexCount() * 4;
        long cBytes = (long) list.clipCount() * 16;
        long vAt = ring.allocate(vBytes, 256);
        long iAt = ring.allocate(iBytes, 256);
        long cAt = ring.allocate(cBytes, 256);
        if (vAt < 0 || iAt < 0 || cAt < 0) {
            return; // the ring is full this frame: skip the UI rather than stall
        }
        MemorySegment mem = ring.memory();
        MemorySegment.copy(
                list.vertices,
                0,
                mem,
                ValueLayout.JAVA_FLOAT_UNALIGNED,
                vAt,
                list.vertexCount() * UiDrawList.VERTEX_FLOATS);
        MemorySegment.copy(
                list.indices, 0, mem, ValueLayout.JAVA_INT_UNALIGNED, iAt, list.indexCount());
        MemorySegment.copy(
                list.clips, 0, mem, ValueLayout.JAVA_FLOAT_UNALIGNED, cAt, list.clipCount() * 4);

        state.apply(
                PipelineState.withBlend(
                        PipelineState.FULLSCREEN, PipelineState.BLEND_PREMULTIPLIED));
        state.useProgram(program);
        state.bindVertexArray(vao);
        state.bindStorage(4, ring.glName(), vAt, vBytes);
        state.bindStorage(5, ring.glName(), cAt, cBytes);
        glProgramUniform2f(
                program, LOC_SCREEN, viewportWidth / displayScale, viewportHeight / displayScale);
        glProgramUniform2f(program, LOC_VIEWPORT, viewportWidth, viewportHeight);
        glProgramUniform1f(program, LOC_SCALE, displayScale);
        // Bindings go through the state cache, which knows what the scene left on the units;
        // a private "last bound" would miss that (the first demo frames drew speckles).
        state.bindSampler(0, fontSampler);
        state.bindSampler(1, spriteSampler);
        int n = list.commandCount();
        int spriteSrgb = -1;
        for (int c = 0; c < n; c++) {
            int font = list.commandFontTexture(c);
            if (font != UiDrawList.NO_TEXTURE) {
                state.bindTexture(0, arrays.glName(font));
            }
            int sprite = list.commandSpriteTexture(c);
            if (sprite != UiDrawList.NO_TEXTURE) {
                state.bindTexture(1, arrays.glName(sprite));
                int srgb = GpuFormats.isSrgb(arrays.internalFormat(sprite)) ? 1 : 0;
                if (srgb != spriteSrgb) {
                    glProgramUniform1i(program, LOC_SPRITE_SRGB, srgb);
                    spriteSrgb = srgb;
                }
            }
            glDrawElements(
                    GL_TRIANGLES,
                    list.commandIndexCount(c),
                    GL_UNSIGNED_INT,
                    iAt + (long) list.commandFirstIndex(c) * 4);
            lastDraws++;
        }
    }

    /** Returns the draw calls the last {@link #draw} issued. */
    public long lastDraws() {
        return lastDraws;
    }

    /** Returns the quads the last {@link #draw} was given. */
    public long lastQuads() {
        return lastQuads;
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
