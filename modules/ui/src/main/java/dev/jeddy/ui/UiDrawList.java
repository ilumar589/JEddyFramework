package dev.jeddy.ui;

import java.util.Arrays;

/**
 * What a frame of UI looks like, as plain arrays the renderer uploads (TASKS M13-03, DESIGN §18,
 * D84): quads as four vertices and six indices, a table of clip rectangles, and commands that group
 * consecutive quads sharing the same textures.
 *
 * <p>Each vertex is {@link #VERTEX_FLOATS} floats:
 *
 * <pre>
 * 0 x, 1 y        window position (logical pixels, y down)
 * 2 u, 3 v        atlas coordinates (text, images) or the position relative to the rectangle's
 *                 center in pixels (rectangles)
 * 4 color         0xAARRGGBB as float bits (sRGB, straight alpha)
 * 5 misc          kind (bits 0-3) | clip index (bits 4-15) | texture layer (bits 16-31) as bits
 * 6-9 p0..p3      RECT: half width, half height, corner radius, border width (0 = filled)
 *                 TEXT: the screen-space distance range (pixels per unit of distance)
 * </pre>
 *
 * Texture references are opaque to this module: the renderer owns their meaning. Here a reference
 * is split into a <em>texture</em> ({@code ref >>> 16}, which starts a new command when it changes)
 * and a <em>layer</em> ({@code ref & 0xFFFF}, stored per vertex). {@link #NO_TEXTURE} draws
 * untextured.
 *
 * <p>Thread ownership: written by the game thread inside {@link Ui#endFrame}, read by the render
 * thread while the packet that holds it is being drawn. Allocates nothing unless it outgrows its
 * capacity.
 */
public final class UiDrawList {
    /** Floats per vertex. */
    public static final int VERTEX_FLOATS = 10;

    /** Vertex kind: a filled or bordered rounded rectangle. */
    public static final int KIND_RECT = 0;

    /** Vertex kind: a signed-distance-field glyph. */
    public static final int KIND_TEXT = 1;

    /** Vertex kind: a textured quad. */
    public static final int KIND_IMAGE = 2;

    /** The texture reference of an untextured quad. */
    public static final int NO_TEXTURE = -1;

    /** Ints per command: first index, index count, font texture, sprite texture. */
    public static final int COMMAND_INTS = 4;

    /** Vertices, {@link #VERTEX_FLOATS} per vertex. */
    public float[] vertices;

    /** Indices into {@link #vertices}. */
    public int[] indices;

    /** Clip rectangles: x, y, width, height per entry; entry 0 is "no clip". */
    public float[] clips;

    /** Commands, {@link #COMMAND_INTS} ints each. */
    public int[] commands;

    private int vertexCount;
    private int indexCount;
    private int clipCount;
    private int commandCount;

    /** Creates a list with room for {@code quads} quads, {@code clips} clip rectangles. */
    public UiDrawList(int quads, int clips) {
        vertices = new float[Math.max(4, quads) * 4 * VERTEX_FLOATS];
        indices = new int[Math.max(4, quads) * 6];
        this.clips = new float[Math.max(1, clips) * 4];
        commands = new int[16 * COMMAND_INTS];
        reset();
    }

    /** Empties the list. */
    public void reset() {
        vertexCount = 0;
        indexCount = 0;
        clipCount = 1;
        clips[0] = 0;
        clips[1] = 0;
        clips[2] = Float.MAX_VALUE;
        clips[3] = Float.MAX_VALUE;
        commandCount = 0;
    }

    /** Returns the number of vertices. */
    public int vertexCount() {
        return vertexCount;
    }

    /** Returns the number of indices. */
    public int indexCount() {
        return indexCount;
    }

    /** Returns the number of clip rectangles (at least 1). */
    public int clipCount() {
        return clipCount;
    }

    /** Returns the number of commands. */
    public int commandCount() {
        return commandCount;
    }

    /** Returns the number of quads. */
    public int quadCount() {
        return vertexCount / 4;
    }

    /** Returns command {@code c}'s first index. */
    public int commandFirstIndex(int c) {
        return commands[c * COMMAND_INTS];
    }

    /** Returns command {@code c}'s index count. */
    public int commandIndexCount(int c) {
        return commands[c * COMMAND_INTS + 1];
    }

    /** Returns command {@code c}'s font texture ({@code ref >>> 16}), or {@link #NO_TEXTURE}. */
    public int commandFontTexture(int c) {
        return commands[c * COMMAND_INTS + 2];
    }

    /** Returns command {@code c}'s sprite texture ({@code ref >>> 16}), or {@link #NO_TEXTURE}. */
    public int commandSpriteTexture(int c) {
        return commands[c * COMMAND_INTS + 3];
    }

    /** Adds a clip rectangle and returns its index (fits 12 bits; beyond that, clip 0). */
    public int addClip(float x, float y, float w, float h) {
        if (clipCount >= 4096) {
            return 0;
        }
        if (clipCount * 4 == clips.length) {
            clips = Arrays.copyOf(clips, clips.length * 2);
        }
        int c = clipCount++;
        clips[c * 4] = x;
        clips[c * 4 + 1] = y;
        clips[c * 4 + 2] = w;
        clips[c * 4 + 3] = h;
        return c;
    }

    /**
     * Adds a filled rounded rectangle ({@code border} 0) or a border ring ({@code border > 0}: the
     * ring is {@code border} pixels wide inside the rectangle).
     */
    public void rect(
            float x, float y, float w, float h, float radius, float border, int color, int clip) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) {
            return;
        }
        need(NO_TEXTURE, NO_TEXTURE);
        float hw = w * 0.5f;
        float hh = h * 0.5f;
        int misc = KIND_RECT | clip << 4;
        int v = reserve();
        vertex(v, x, y, -hw, -hh, color, misc, hw, hh, radius, border);
        vertex(v + 1, x + w, y, hw, -hh, color, misc, hw, hh, radius, border);
        vertex(v + 2, x + w, y + h, hw, hh, color, misc, hw, hh, radius, border);
        vertex(v + 3, x, y + h, -hw, hh, color, misc, hw, hh, radius, border);
    }

    /**
     * Adds a glyph quad sampling the font atlas {@code texture} at {@code [u0, u1] × [v0, v1]};
     * {@code pxRange} is the screen-space distance range for antialiasing.
     */
    public void glyph(
            float x,
            float y,
            float w,
            float h,
            float u0,
            float v0,
            float u1,
            float v1,
            int color,
            int texture,
            float pxRange,
            int clip) {
        need(texture >>> 16, NO_TEXTURE);
        int misc = KIND_TEXT | clip << 4 | (texture & 0xFFFF) << 16;
        int v = reserve();
        vertex(v, x, y, u0, v0, color, misc, pxRange, 0, 0, 0);
        vertex(v + 1, x + w, y, u1, v0, color, misc, pxRange, 0, 0, 0);
        vertex(v + 2, x + w, y + h, u1, v1, color, misc, pxRange, 0, 0, 0);
        vertex(v + 3, x, y + h, u0, v1, color, misc, pxRange, 0, 0, 0);
    }

    /** Adds a textured quad tinted by {@code color} (white for no tint). */
    public void image(
            float x,
            float y,
            float w,
            float h,
            float u0,
            float v0,
            float u1,
            float v1,
            int color,
            int texture,
            int clip) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) {
            return;
        }
        need(NO_TEXTURE, texture >>> 16);
        int misc = KIND_IMAGE | clip << 4 | (texture & 0xFFFF) << 16;
        int v = reserve();
        vertex(v, x, y, u0, v0, color, misc, 0, 0, 0, 0);
        vertex(v + 1, x + w, y, u1, v0, color, misc, 0, 0, 0, 0);
        vertex(v + 2, x + w, y + h, u1, v1, color, misc, 0, 0, 0, 0);
        vertex(v + 3, x, y + h, u0, v1, color, misc, 0, 0, 0, 0);
    }

    /**
     * Makes sure the running command can draw a quad that needs {@code font} and {@code sprite}
     * ({@link #NO_TEXTURE} when not needed): a command adopts a texture it has no value for yet,
     * and a new command starts when one differs.
     */
    private void need(int font, int sprite) {
        if (commandCount > 0) {
            int o = (commandCount - 1) * COMMAND_INTS;
            boolean fontOk =
                    font == NO_TEXTURE || commands[o + 2] == NO_TEXTURE || commands[o + 2] == font;
            boolean spriteOk =
                    sprite == NO_TEXTURE
                            || commands[o + 3] == NO_TEXTURE
                            || commands[o + 3] == sprite;
            if (fontOk && spriteOk) {
                if (font != NO_TEXTURE) {
                    commands[o + 2] = font;
                }
                if (sprite != NO_TEXTURE) {
                    commands[o + 3] = sprite;
                }
                return;
            }
        }
        if (commandCount * COMMAND_INTS == commands.length) {
            commands = Arrays.copyOf(commands, commands.length * 2);
        }
        int o = commandCount++ * COMMAND_INTS;
        commands[o] = indexCount;
        commands[o + 1] = 0;
        commands[o + 2] = font;
        commands[o + 3] = sprite;
    }

    private int reserve() {
        if ((vertexCount + 4) * VERTEX_FLOATS > vertices.length) {
            vertices = Arrays.copyOf(vertices, vertices.length * 2);
        }
        if (indexCount + 6 > indices.length) {
            indices = Arrays.copyOf(indices, indices.length * 2);
        }
        int v = vertexCount;
        vertexCount += 4;
        int i = indexCount;
        indices[i] = v;
        indices[i + 1] = v + 1;
        indices[i + 2] = v + 2;
        indices[i + 3] = v;
        indices[i + 4] = v + 2;
        indices[i + 5] = v + 3;
        indexCount += 6;
        commands[(commandCount - 1) * COMMAND_INTS + 1] += 6;
        return v;
    }

    private void vertex(
            int v,
            float x,
            float y,
            float u,
            float vv,
            int color,
            int misc,
            float p0,
            float p1,
            float p2,
            float p3) {
        int o = v * VERTEX_FLOATS;
        float[] a = vertices;
        a[o] = x;
        a[o + 1] = y;
        a[o + 2] = u;
        a[o + 3] = vv;
        a[o + 4] = Float.intBitsToFloat(color);
        a[o + 5] = Float.intBitsToFloat(misc);
        a[o + 6] = p0;
        a[o + 7] = p1;
        a[o + 8] = p2;
        a[o + 9] = p3;
    }
}
