package dev.jeddy.render.ui;

import static dev.jeddy.gl.ffi.GL46.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.format.FontData;
import dev.jeddy.asset.format.FontWriter;
import dev.jeddy.render.GpuTestContext;
import dev.jeddy.render.gpu.FrameRing;
import dev.jeddy.render.gpu.GpuDevice;
import dev.jeddy.render.material.TextureArrays;
import dev.jeddy.testing.Allocations;
import dev.jeddy.ui.Sprite;
import dev.jeddy.ui.Ui;
import dev.jeddy.ui.UiDrawList;
import dev.jeddy.ui.UiInput;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * The UI draw-list renderer on a real context (TASKS M13-06): rectangles, clips, glyphs, sprites.
 */
@Tag("gpu")
class UiRendererTest {
    private static final int SIZE = 128;
    private static final int ATLAS = 64;
    private static final Arena ARENA = Arena.ofShared();
    private static GpuTestContext context;
    private static GpuDevice device;
    private static FrameRing ring;
    private static TextureArrays arrays;
    private static UiRenderer renderer;
    private static MemorySegment pixels;
    private static int fontRef;
    private static int spriteRef;
    private static FontData font;

    @BeforeAll
    static void init() throws Exception {
        context = new GpuTestContext("ui renderer test", SIZE);
        device = new GpuDevice(context.caps());
        ring = new FrameRing(device, 1 << 20, 3, "ui ring");
        arrays = new TextureArrays(device);
        renderer = new UiRenderer(device, ring);
        pixels = ARENA.allocate((long) SIZE * SIZE * 4, 16);

        // A font atlas with one glyph: a disc of radius 20 texels at (32, 32), range 8.
        fontRef = arrays.allocate(GL_R8, ATLAS, ATLAS, 1);
        MemorySegment atlas = ARENA.allocate((long) ATLAS * ATLAS, 16);
        for (int y = 0; y < ATLAS; y++) {
            for (int x = 0; x < ATLAS; x++) {
                float d = 20 - (float) Math.hypot(x + 0.5 - 32, y + 0.5 - 32); // inside positive
                float v = Math.clamp(0.5f + d / 16, 0, 1);
                atlas.set(ValueLayout.JAVA_BYTE, (long) y * ATLAS + x, (byte) Math.round(v * 255));
            }
        }
        device.textures()
                .uploadLayer(
                        arrays.textureHandle(TextureArrays.bucket(fontRef)),
                        0,
                        TextureArrays.layer(fontRef),
                        atlas.address(),
                        ATLAS * ATLAS);
        // The glyph 'o' covers the whole atlas, 64 px at an em of 64, baseline at its bottom.
        font =
                FontData.read(
                        MemorySegment.ofArray(
                                new FontWriter()
                                        .metrics(64, 64, 0, 64, 8)
                                        .atlas(ATLAS, ATLAS)
                                        .glyph('o', 64, 0, 64, ATLAS, ATLAS, 0, 0)
                                        .toBytes()));

        // A 4×4 RGBA sprite: left half green, right half blue, opaque.
        spriteRef = arrays.allocate(GL_RGBA8, 4, 4, 1);
        MemorySegment sprite = ARENA.allocate(4 * 4 * 4, 16);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                long o = ((long) y * 4 + x) * 4;
                sprite.set(ValueLayout.JAVA_BYTE, o, (byte) 0);
                sprite.set(ValueLayout.JAVA_BYTE, o + 1, (byte) (x < 2 ? 255 : 0));
                sprite.set(ValueLayout.JAVA_BYTE, o + 2, (byte) (x < 2 ? 0 : 255));
                sprite.set(ValueLayout.JAVA_BYTE, o + 3, (byte) 255);
            }
        }
        device.textures()
                .uploadLayer(
                        arrays.textureHandle(TextureArrays.bucket(spriteRef)),
                        0,
                        TextureArrays.layer(spriteRef),
                        sprite.address(),
                        4 * 4 * 4);
    }

    @AfterAll
    static void shutdown() {
        renderer.close();
        arrays.close();
        ring.close();
        device.close();
        context.close();
        ARENA.close();
    }

    private static void draw(UiDrawList list, float scale) {
        device.state().bindFramebuffer(0);
        device.state().viewport(0, 0, SIZE, SIZE);
        glClearColor(0, 0, 0, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        ring.beginFrame();
        renderer.draw(device.state(), ring, list, arrays, SIZE, SIZE, scale);
        ring.endFrame();
        glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    }

    /** Channel {@code c} of the pixel at {@code (x, y)} with y down, 0..1. */
    private static float px(int x, int y, int c) {
        int row = SIZE - 1 - y;
        return (pixels.get(ValueLayout.JAVA_BYTE, ((long) row * SIZE + x) * 4 + c) & 0xFF) / 255f;
    }

    @Test
    void rectanglesCornersBordersAndClipsLandWhereTheListSaysInOneDraw() {
        UiDrawList list = new UiDrawList(64, 8);
        list.rect(16, 16, 32, 32, 8, 0, 0xFFFF0000, 0); // red, rounded
        int clip = list.addClip(64, 64, 24, 24);
        list.rect(64, 64, 48, 48, 0, 0, 0xFF00FF00, clip); // green, clipped to 24×24
        list.rect(16, 80, 32, 32, 0, 4, 0xFF0000FF, 0); // blue ring 4 px wide
        draw(list, 1);
        assertEquals(1, renderer.lastDraws());
        assertEquals(1, px(32, 32, 0), 0.02, "red inside");
        assertEquals(0, px(32, 32, 1), 0.02);
        assertEquals(0, px(17, 17, 0), 0.15, "the rounded corner is cut");
        assertEquals(0, px(8, 8, 0), 0.02, "outside");
        assertEquals(1, px(70, 70, 1), 0.02, "green inside its clip");
        assertEquals(0, px(100, 100, 1), 0.02, "green clipped away");
        assertEquals(1, px(18, 82, 2), 0.05, "the ring");
        assertEquals(0, px(32, 96, 2), 0.02, "the ring's hole");
    }

    @Test
    void premultipliedBlendingLightensByAlpha() {
        UiDrawList list = new UiDrawList(64, 8);
        list.rect(0, 0, 64, 64, 0, 0, 0x80FFFFFF, 0);
        draw(list, 1);
        assertEquals(0.5f, px(20, 20, 0), 0.03);
        assertEquals(0.5f, px(20, 20, 2), 0.03);
    }

    @Test
    void glyphsDecodeTheDistanceFieldWithAntialiasedEdges() {
        Ui ui = new Ui();
        int f = ui.addFont(font, fontRef);
        UiInput in = new UiInput();
        in.width = SIZE;
        in.height = SIZE;
        UiDrawList list = new UiDrawList(64, 8);
        ui.beginFrame(in);
        ui.box().padding(16).open();
        // At size 60 (scale 0.9375) the disc has radius 18.75 px around (46, 46): its right edge
        // is at x = 64.75, inside pixel 64 (center 64.5, distance +0.25 → coverage 0.75).
        ui.text("o").font(f).fontSize(60).color(0xFFFFFFFF);
        ui.close();
        ui.endFrame(list);
        assertEquals(1, list.quadCount());
        draw(list, 1);
        assertEquals(1, px(46, 46, 0), 0.02, "disc center");
        assertEquals(1, px(58, 46, 0), 0.05, "inside the radius");
        assertEquals(0, px(70, 46, 0), 0.05, "outside the radius");
        float edge = px(64, 46, 0);
        assertTrue(edge > 0.5f && edge < 0.95f, "antialiased edge: " + edge);
        assertEquals(0, px(8, 8, 0), 0.02);
    }

    @Test
    void spritesSampleTheirArrayLayerAndNineSlicesKeepCorners() {
        UiDrawList list = new UiDrawList(64, 8);
        list.image(0, 0, 32, 32, 0, 0, 1, 1, 0xFFFFFFFF, spriteRef, 0);
        draw(list, 1);
        assertEquals(1, px(8, 16, 1), 0.05, "left half green");
        assertEquals(0, px(8, 16, 2), 0.05);
        assertEquals(1, px(24, 16, 2), 0.05, "right half blue");
        assertEquals(0, px(24, 16, 1), 0.05);

        // Through the Ui: a nine-slice with 1-texel insets stretched to 64×32 keeps its corners
        // 1 px wide and stretches the middle texels (bilinear blends between them).
        Ui ui = new Ui();
        UiInput in = new UiInput();
        in.width = SIZE;
        in.height = SIZE;
        ui.beginFrame(in);
        ui.box().size(64, 32).sprite(Sprite.whole(spriteRef, 4, 4).nineSlice(1));
        ui.endFrame(list);
        assertEquals(9, list.quadCount());
        draw(list, 1);
        assertEquals(1, px(0, 16, 1), 0.05, "left corner column: texel 0, green");
        assertEquals(1, px(4, 16, 1), 0.05, "start of the middle: texel 1, green");
        assertEquals(1, px(60, 16, 2), 0.05, "end of the middle: texel 2, blue");
        assertEquals(1, px(63, 16, 2), 0.05, "right corner column: texel 3, blue");
        assertEquals(0, px(64, 16, 2), 0.05);
    }

    @Test
    void theDisplayScaleMapsLogicalPixelsToTheViewport() {
        UiDrawList list = new UiDrawList(64, 8);
        list.rect(8, 8, 16, 16, 0, 0, 0xFFFF0000, 0); // logical: physical 16..48 at 2×
        int clip = list.addClip(0, 0, 20, 64); // logical: cuts at physical x = 40
        list.rect(8, 40, 16, 16, 0, 0, 0xFF00FF00, clip);
        draw(list, 2);
        assertEquals(1, px(40, 24, 0), 0.02);
        assertEquals(0, px(12, 24, 0), 0.02);
        assertEquals(0, px(50, 24, 0), 0.02);
        assertEquals(1, px(36, 90, 1), 0.02, "green inside the scaled clip");
        assertEquals(0, px(44, 90, 1), 0.02, "green beyond the scaled clip");
    }

    @Test
    void drawingAllocatesNothing() {
        UiDrawList list = new UiDrawList(64, 8);
        for (int i = 0; i < 20; i++) {
            list.rect(i * 4, 0, 8, 8, 2, 0, 0xFFFFFFFF, 0);
            list.image(0, 20, 16, 16, 0, 0, 1, 1, 0xFFFFFFFF, spriteRef, 0);
            list.glyph(0, 40, 16, 16, 0, 0, 1, 1, 0xFFFFFFFF, fontRef, 4, 0);
        }
        Allocations.assertNoAllocation(
                "ui draw",
                10,
                200,
                () -> {
                    ring.beginFrame();
                    renderer.draw(device.state(), ring, list, arrays, SIZE, SIZE, 1);
                    ring.endFrame();
                });
    }
}
