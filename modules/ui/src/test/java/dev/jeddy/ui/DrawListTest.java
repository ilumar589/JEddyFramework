package dev.jeddy.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.format.FontData;
import dev.jeddy.asset.format.FontWriter;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;

/** Draw-list emission (TASKS M13-03): quads, commands, clips, glyphs; and the per-frame budget. */
class DrawListTest {
    /** A font whose atlas is loaded: 'a' is a 20×30 glyph at (4, 6) of a 64×64 atlas. */
    private static FontData atlasFont() throws Exception {
        byte[] bytes =
                new FontWriter()
                        .metrics(40, 32, 8, 48, 4)
                        .atlas(64, 64)
                        .glyph('a', 24, 2, 28, 20, 30, 4, 6)
                        .glyph(' ', 10, 0, 0, 0, 0, 0, 0)
                        .toBytes();
        return FontData.read(MemorySegment.ofArray(bytes));
    }

    private static int misc(UiDrawList l, int vertex) {
        return Float.floatToRawIntBits(l.vertices[vertex * UiDrawList.VERTEX_FLOATS + 5]);
    }

    @Test
    void rectanglesBordersAndSpritesBecomeQuadsInOneCommand() {
        Ui ui = new Ui();
        UiInput in = new UiInput();
        in.width = 200;
        in.height = 100;
        UiDrawList out = new UiDrawList(16, 4);
        ui.beginFrame(in);
        ui.box().size(50, 20).background(0xFF102030).border(0xFFFFFFFF, 2).radius(4).open();
        ui.box().size(10, 10).background(0); // transparent: nothing drawn
        ui.close();
        ui.box().size(30, 30).sprite(Sprite.whole(0x00020005, 16, 16));
        ui.endFrame(out);
        assertEquals(3, out.quadCount());
        assertEquals(1, out.commandCount()); // the command adopts the sprite texture it lacked
        assertEquals(18, out.commandIndexCount(0));
        assertEquals(UiDrawList.NO_TEXTURE, out.commandFontTexture(0));
        assertEquals(2, out.commandSpriteTexture(0));
        assertEquals(UiDrawList.KIND_RECT, misc(out, 0) & 0xF);
        assertEquals(UiDrawList.KIND_IMAGE, misc(out, 8) & 0xF);
        assertEquals(5, misc(out, 8) >>> 16); // the layer travels per vertex
        // Border vertices carry the border width in p3 and the radius in p2.
        int v = 4 * UiDrawList.VERTEX_FLOATS;
        assertEquals(4, out.vertices[v + 8]);
        assertEquals(2, out.vertices[v + 9]);
        assertEquals(25, out.vertices[v + 6]); // half width
    }

    @Test
    void clipsAreIndexedPerVertexAndIntersectWithEnclosingClips() {
        Ui ui = new Ui();
        UiInput in = new UiInput();
        in.width = 200;
        in.height = 100;
        UiDrawList out = new UiDrawList(16, 4);
        ui.beginFrame(in);
        ui.box().size(100, 50).clip().background(0xFF111111).open();
        ui.box().size(300, 300).background(0xFF222222).clip().padding(0).open();
        ui.box().size(10, 10).background(0xFF333333);
        ui.close();
        ui.close();
        ui.endFrame(out);
        assertEquals(3, out.quadCount());
        assertEquals(3, out.clipCount()); // "none" plus two
        assertEquals(0, misc(out, 0) >>> 4 & 0xFFF);
        assertEquals(1, misc(out, 4) >>> 4 & 0xFFF);
        assertEquals(2, misc(out, 8) >>> 4 & 0xFFF);
        // The inner clip is the intersection: 300×300 at the origin inside 100×50 is 100×50.
        assertEquals(100, out.clips[2 * 4 + 2]);
        assertEquals(50, out.clips[2 * 4 + 3]);
    }

    @Test
    void nineSliceSpritesEmitNineQuadsWithFixedCorners() {
        Ui ui = new Ui();
        UiInput in = new UiInput();
        in.width = 200;
        in.height = 100;
        UiDrawList out = new UiDrawList(16, 4);
        ui.beginFrame(in);
        ui.box().size(100, 60).sprite(Sprite.whole(0, 32, 32).nineSlice(8));
        ui.endFrame(out);
        assertEquals(9, out.quadCount());
        // Quad 0 is the top-left corner: 8×8 at the box origin, sampling the first quarter.
        float[] vtx = out.vertices;
        int s = UiDrawList.VERTEX_FLOATS;
        assertEquals(8, vtx[2 * s]); // x of the corner's bottom-right vertex
        assertEquals(8, vtx[2 * s + 1]);
        assertEquals(0.25f, vtx[2 * s + 2], 1e-6);
        // Quad 4 is the center: from (8, 8) to (92, 52).
        assertEquals(8, vtx[16 * s]);
        assertEquals(92, vtx[18 * s]);
        assertEquals(52, vtx[18 * s + 1]);
    }

    @Test
    void glyphsAreQuadsAtTheirBearingsWithTheScreenSpaceRange() throws Exception {
        Ui ui = new Ui();
        int f = ui.addFont(atlasFont(), 0x00010003);
        assertEquals(1, f);
        assertEquals(1, ui.defaultFont);
        UiInput in = new UiInput();
        in.width = 200;
        in.height = 100;
        UiDrawList out = new UiDrawList(16, 4);
        ui.beginFrame(in);
        ui.box().padding(10).open();
        ui.text("a a").fontSize(20).color(0xFF00FF00); // scale 0.5
        ui.close();
        ui.endFrame(out);
        assertEquals(2, out.quadCount()); // the space has no bitmap
        assertEquals(1, out.commandCount());
        assertEquals(1, out.commandFontTexture(0));
        int s = UiDrawList.VERTEX_FLOATS;
        float[] v = out.vertices;
        // Pen at x = 10; glyph left = pen + bearingX * 0.5 = 11; top = baseline - 14 = 10 + 16 -
        // 14.
        assertEquals(11, v[0], 1e-4);
        assertEquals(12, v[1], 1e-4);
        assertEquals(21, v[2 * s], 1e-4); // 20 wide × 0.5
        assertEquals(27, v[2 * s + 1], 1e-4); // 30 tall × 0.5
        assertEquals(4f / 64, v[2], 1e-6);
        assertEquals(6f / 64, v[3], 1e-6);
        assertEquals(24f / 64, v[2 * s + 2], 1e-6);
        assertEquals(4, v[6], 1e-6); // pxRange = 2 × range 4 × scale 0.5
        assertEquals(UiDrawList.KIND_TEXT, misc(out, 0) & 0xF);
        assertEquals(3, misc(out, 0) >>> 16);
        // Second 'a': pen advanced by (24 + 10) × 0.5 = 17.
        assertEquals(28, v[4 * s], 1e-4);
        assertEquals(0xFF00FF00, Float.floatToRawIntBits(v[4]));
    }

    @Test
    void textWithoutAnAtlasMeasuresButDrawsNothing() {
        Ui ui = new Ui();
        UiInput in = new UiInput();
        in.width = 200;
        in.height = 100;
        UiDrawList out = new UiDrawList(16, 4);
        ui.beginFrame(in);
        int t = ui.text("hello").index();
        ui.endFrame(out);
        assertEquals(0, out.quadCount());
        assertTrue(ui.w(t) > 0);
    }

    @Test
    void aFrameOfTwoThousandElementsAllocatesNothing() throws Exception {
        Ui ui = new Ui();
        ui.addFont(atlasFont(), 1);
        UiInput in = new UiInput();
        in.width = 1280;
        in.height = 720;
        in.dt = 1f / 60;
        UiDrawList out = new UiDrawList(4096, 64);
        long list = 1;
        long row = 2;
        long name = 3;
        long tip = 4;
        int[] frame = new int[1];
        Runnable body =
                () -> {
                    frame[0]++;
                    in.mouseX = 100 + frame[0] % 50;
                    in.mouseY = 100 + frame[0] % 30;
                    in.buttonsPressed = frame[0] % 7 == 0 ? UiInput.LEFT : 0;
                    in.buttonsReleased = frame[0] % 7 == 3 ? UiInput.LEFT : 0;
                    in.buttonsDown = frame[0] % 7 < 3 ? UiInput.LEFT : 0;
                    in.wheelY = frame[0] % 11 == 0 ? -1 : 0;
                    ui.beginFrame(in);
                    ui.box(list)
                            .width(400)
                            .heightGrow()
                            .column()
                            .padding(8)
                            .gap(4)
                            .background(0xF0202228)
                            .scroll(false, true)
                            .open();
                    for (int i = 0; i < 400; i++) {
                        ui.pushId(i);
                        ui.box(row)
                                .widthGrow()
                                .height(24)
                                .padding(4)
                                .gap(6)
                                .background(0xFF34373F)
                                .focusable()
                                .open();
                        ui.text(name).append("Item ").append(i).append(' ').append(i * 0.25f, 2);
                        ui.box().widthGrow();
                        ui.text().append("aaa bbb ccc ddd eee").wrap().widthFit(0, 60);
                        ui.box().size(16, 16).background(0xFF4C8DFF).dragSource(1, i);
                        if (ui.hovered(row)) {
                            ui.box(tip).floating().padding(4).background(0xF8101114).z(10).open();
                            ui.text("tooltip");
                            ui.close();
                        }
                        ui.close();
                        ui.popId();
                    }
                    ui.close();
                    ui.endFrame(out);
                    in.clearEvents();
                };
        for (int i = 0; i < 20; i++) {
            body.run();
        }
        assertTrue(ui.elementCount() >= 2000, "elements: " + ui.elementCount());
        Allocations.assertNoAllocation("ui frame", 20, 200, body);
    }
}
