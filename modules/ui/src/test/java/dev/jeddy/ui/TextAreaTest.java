package dev.jeddy.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.Test;

/**
 * The multi-line text area: lines, the caret by line, selection across lines, scrolling (M17-05).
 */
class TextAreaTest {
    private static final long AREA = 21;

    private final Ui ui = new Ui();
    private final Theme theme = Theme.dark();
    private final Widgets w = new Widgets(ui, theme);
    private final UiInput in = new UiInput();
    private final UiDrawList out = new UiDrawList(256, 16);
    private final TextBuffer text =
            new TextBuffer("dialogue ghost\nnode start\n  ghost: Hello\n  end");

    TextAreaTest() {
        in.width = 600;
        in.height = 400;
        in.dt = 1f / 60;
        ui.defaultFontSize = 10; // the metrics font: 5 px per char, 12 px lines
        theme.fontSize = 10;
        w.applyTheme();
    }

    /** The area at the top-left (panel padding 8, area padding 8, a gutter of one digit). */
    private void frame() {
        ui.beginFrame(in);
        w.panel().width(300).open();
        w.textArea(AREA, text, 60);
        ui.close();
        ui.endFrame(out);
        in.clearEvents();
    }

    private void click(float x, float y) {
        in.mouseX = x;
        in.mouseY = y;
        frame();
        in.click(x, y);
        frame();
    }

    private void key(int scancode, int mods) {
        in.key(scancode, mods, true, false);
        frame();
    }

    @Test
    void linesAreCountedAndIndexed() {
        assertEquals(4, text.lineCount());
        assertEquals(0, text.lineOf(5));
        assertEquals(1, text.lineOf(15));
        assertEquals(15, text.indexOfLine(1));
        assertEquals(15, text.lineStart(20));
        assertEquals(25, text.lineEnd(20));
        assertEquals(text.length(), text.indexOfLine(9));
        assertEquals(3, text.lineOf(text.length()));
    }

    @Test
    void clicksPlaceTheCaretOnALineAndKeysMoveByLine() {
        // Panel padding 8 + area padding 8 = 16; the gutter is 1 digit (5 px) + a gap of 8.
        float textX = 16 + 5 + 8;
        float lineHeight = ui.lineHeight(ui.defaultFont, 10);
        click(textX + 5 * 4 + 1, 16 + lineHeight * 1 + 2); // line 1, after "node"
        assertTrue(ui.focused(AREA));
        assertEquals(15 + 4, text.caret());
        key(UiKey.DOWN, 0);
        assertEquals(text.indexOfLine(2) + 4, text.caret(), "the column is kept");
        key(UiKey.END, 0);
        assertEquals(text.lineEnd(text.indexOfLine(2)), text.caret());
        key(UiKey.UP, 0);
        assertEquals(
                text.lineEnd(text.indexOfLine(1)),
                text.caret(),
                "End reset the column: Up clamps to the shorter line");
        key(UiKey.DOWN, 0);
        assertEquals(
                text.lineEnd(text.indexOfLine(2)),
                text.caret(),
                "the column from before the clamp is kept");
        key(UiKey.HOME, 0);
        assertEquals(text.indexOfLine(2), text.caret(), "Home: the start of the caret's line");
        key(UiKey.HOME, UiKey.MOD_CTRL);
        assertEquals(0, text.caret());
        key(UiKey.END, UiKey.MOD_CTRL);
        assertEquals(text.length(), text.caret());
        // Enter inserts a line, Tab two spaces; the gutter grows past 9 lines.
        key(UiKey.RETURN, 0);
        key(UiKey.TAB, 0);
        in.text('x');
        frame();
        assertEquals("dialogue ghost\nnode start\n  ghost: Hello\n  end\n  x", text.toString());
        assertEquals(5, text.lineCount());
        // A selection across lines: Shift+Up from the end.
        key(UiKey.UP, UiKey.MOD_SHIFT);
        assertTrue(text.hasSelection());
        assertTrue(text.selectionStart() < text.indexOfLine(4));
        key(UiKey.BACKSPACE, 0);
        assertEquals(4, text.lineCount());
        // Ctrl+A, Ctrl+X empties it; Ctrl+V brings it back.
        key(UiKey.A, UiKey.MOD_CTRL);
        key(UiKey.X, UiKey.MOD_CTRL);
        assertEquals(0, text.length());
        key(UiKey.V, UiKey.MOD_CTRL);
        assertEquals(4, text.lineCount());
    }

    @Test
    void theCaretStaysVisibleBySccrolling() {
        for (int i = 0; i < 30; i++) {
            text.moveTo(text.length(), false);
            text.insert("\nline " + i);
        }
        float textX = 16 + 10 + 8; // two digits now
        click(textX + 2, 16 + 2); // the first line
        assertEquals(0, text.caret());
        assertEquals(0, ui.scroll(AREA, 1));
        key(UiKey.END, UiKey.MOD_CTRL);
        frame();
        assertTrue(ui.scroll(AREA, 1) > 0, "scrolled down to the caret");
        key(UiKey.HOME, UiKey.MOD_CTRL);
        frame();
        assertEquals(0, ui.scroll(AREA, 1), 1e-3f);
        key(UiKey.PAGE_DOWN, 0);
        assertTrue(text.lineOf(text.caret()) > 1);
    }

    @Test
    void editingAllocatesNothing() {
        click(40, 20);
        int[] n = new int[1];
        Runnable body =
                () -> {
                    n[0]++;
                    if (n[0] % 3 == 0) {
                        in.text('x');
                    }
                    if (n[0] % 7 == 0) {
                        in.key(UiKey.BACKSPACE, 0, true, false);
                    }
                    if (n[0] % 11 == 0) {
                        in.key(n[0] % 2 == 0 ? UiKey.UP : UiKey.DOWN, UiKey.MOD_SHIFT, true, false);
                    }
                    frame();
                };
        for (int i = 0; i < 50; i++) {
            body.run();
        }
        Allocations.assertNoAllocation("text area frame", 20, 300, body);
    }
}
