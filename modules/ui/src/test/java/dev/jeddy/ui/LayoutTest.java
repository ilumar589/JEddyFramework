package dev.jeddy.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The six layout passes against hand-computed rectangles (TASKS M13-03). The built-in metrics font
 * makes every character {@code 0.5 × size} wide and a line {@code 1.2 × size} tall, so text at size
 * 10 is 5 px per char and 12 px per line.
 */
class LayoutTest {
    private static final float EPS = 1e-3f;

    private static Ui ui(float w, float h) {
        Ui ui = new Ui();
        ui.defaultFontSize = 10;
        UiInput in = new UiInput();
        in.width = w;
        in.height = h;
        ui.beginFrame(in);
        return ui;
    }

    private static void assertRect(Ui ui, int e, float x, float y, float w, float h) {
        assertEquals(x, ui.x(e), EPS, "x");
        assertEquals(y, ui.y(e), EPS, "y");
        assertEquals(w, ui.w(e), EPS, "w");
        assertEquals(h, ui.h(e), EPS, "h");
    }

    @Test
    void rootIsTheScreenAndFitBoxesWrapTheirChildren() {
        Ui ui = ui(800, 600);
        int panel = ui.box().padding(10).gap(5).open().index();
        int a = ui.box().size(20, 10).index();
        int b = ui.box().size(30, 10).index();
        ui.close();
        ui.layoutOnly();
        assertRect(ui, 0, 0, 0, 800, 600);
        assertRect(ui, panel, 0, 0, 10 + 20 + 5 + 30 + 10, 30);
        assertRect(ui, a, 10, 10, 20, 10);
        assertRect(ui, b, 35, 10, 30, 10);
        assertEquals(75, ui.contentWidth(panel), EPS);
    }

    @Test
    void columnsStackAndFitTheWidestChild() {
        Ui ui = ui(800, 600);
        int col = ui.box().column().padding(4).gap(2).open().index();
        int a = ui.box().size(20, 10).index();
        int b = ui.box().size(50, 10).index();
        ui.close();
        ui.layoutOnly();
        assertRect(ui, col, 0, 0, 58, 4 + 10 + 2 + 10 + 4);
        assertRect(ui, a, 4, 4, 20, 10);
        assertRect(ui, b, 4, 16, 50, 10);
    }

    @Test
    void growSharesTheFreeSpaceEquallyThenByWeight() {
        Ui ui = ui(300, 100);
        ui.box().width(300).height(20).open();
        int fixed = ui.box().size(60, 20).index();
        int g1 = ui.box().widthGrow().height(20).index();
        int g2 = ui.box().widthGrow().height(20).index();
        ui.close();
        ui.layoutOnly();
        assertRect(ui, fixed, 0, 0, 60, 20);
        assertRect(ui, g1, 60, 0, 120, 20);
        assertRect(ui, g2, 180, 0, 120, 20);

        ui = ui(300, 100);
        ui.box().width(300).height(20).open();
        int w1 = ui.box().widthGrow(1).height(20).index();
        int w2 = ui.box().widthGrow(2).height(20).index();
        ui.close();
        ui.layoutOnly();
        assertRect(ui, w1, 0, 0, 100, 20);
        assertRect(ui, w2, 100, 0, 200, 20);
    }

    @Test
    void growIsCappedByMaximumsAndStartsFromItsContent() {
        Ui ui = ui(300, 100);
        ui.box().width(300).height(20).open();
        int capped = ui.box().widthGrow().maxWidth(50).height(20).index();
        int rest = ui.box().widthGrow().height(20).index();
        ui.close();
        ui.layoutOnly();
        assertRect(ui, capped, 0, 0, 50, 20);
        assertRect(ui, rest, 50, 0, 250, 20);

        // Equal weights equalize from different contents: 80 and 20 inside 300 end at 150 each.
        ui = ui(300, 100);
        ui.box().width(300).height(20).open();
        int big = ui.box().widthGrow().height(20).open().index();
        ui.box().size(80, 20);
        ui.close();
        int small = ui.box().widthGrow().height(20).open().index();
        ui.box().size(20, 20);
        ui.close();
        ui.close();
        ui.layoutOnly();
        assertRect(ui, big, 0, 0, 150, 20);
        assertRect(ui, small, 150, 0, 150, 20);

        // Not enough space to equalize: the small one gets everything until it catches up.
        ui = ui(300, 100);
        ui.box().width(110).height(20).open();
        big = ui.box().widthGrow().height(20).open().index();
        ui.box().size(80, 20);
        ui.close();
        small = ui.box().widthGrow().height(20).open().index();
        ui.box().size(20, 20);
        ui.close();
        ui.close();
        ui.layoutOnly();
        assertRect(ui, big, 0, 0, 80, 20);
        assertRect(ui, small, 80, 0, 30, 20);
    }

    @Test
    void percentIsOfTheParentsInnerSize() {
        Ui ui = ui(300, 100);
        ui.box().width(220).height(100).padding(10).open();
        int quarter = ui.box().widthPercent(0.25f).heightPercent(0.5f).index();
        ui.close();
        ui.layoutOnly();
        assertRect(ui, quarter, 10, 10, 50, 40);
    }

    @Test
    void crossAxisGrowFillsAndCrossAxisAlignmentOffsets() {
        Ui ui = ui(300, 100);
        ui.box().column().width(100).height(100).align(Ui.CENTER, Ui.END).open();
        int wide = ui.box().widthGrow().height(10).index();
        int narrow = ui.box().size(20, 10).index();
        ui.close();
        ui.layoutOnly();
        assertRect(ui, wide, 0, 80, 100, 10);
        assertRect(ui, narrow, 40, 90, 20, 10);
    }

    @Test
    void mainAxisAlignmentPlacesTheChildrenBlock() {
        Ui ui = ui(300, 100);
        ui.box().width(100).height(10).gap(10).align(Ui.END, Ui.START).open();
        int a = ui.box().size(20, 10).index();
        int b = ui.box().size(20, 10).index();
        ui.close();
        ui.layoutOnly();
        assertRect(ui, a, 50, 0, 20, 10);
        assertRect(ui, b, 80, 0, 20, 10);
    }

    @Test
    void textMeasuresWrapsAndShrinksToItsLongestWord() {
        Ui ui = ui(300, 100);
        int t = ui.text("aaaa bbbb").index();
        ui.layoutOnly();
        assertRect(ui, t, 0, 0, 45, 12);
        assertEquals(1, ui.lineCount(t));

        // A fixed 80 px sibling leaves 20 px: the text shrinks to its longest word and wraps.
        ui = ui(300, 100);
        ui.box().width(100).open();
        t = ui.text("aaaa bbbb").wrap().index();
        int f = ui.box().size(80, 10).index();
        ui.close();
        ui.layoutOnly();
        assertRect(ui, t, 0, 0, 20, 24);
        assertEquals(2, ui.lineCount(t));
        assertRect(ui, f, 20, 0, 80, 10);

        // Without wrap the text cannot shrink below its line and overflows instead.
        ui = ui(300, 100);
        ui.box().width(100).open();
        t = ui.text("aaaa bbbb").index();
        ui.box().size(80, 10);
        ui.close();
        ui.layoutOnly();
        assertEquals(45, ui.w(t), EPS);
    }

    @Test
    void wrappingBreaksAtSpacesAndInsideLongWords() {
        Ui ui = ui(300, 100);
        int t = ui.text("the quick brown fox").wrap().width(50).index();
        ui.layoutOnly();
        assertEquals(2, ui.lineCount(t));
        assertEquals(24, ui.h(t), EPS);

        ui = ui(300, 100);
        t = ui.text("abcdefghijkl").wrap().width(25).index();
        ui.layoutOnly();
        assertEquals(3, ui.lineCount(t)); // 5 chars per line
        assertEquals(36, ui.h(t), EPS);

        ui = ui(300, 100);
        t = ui.text("one\ntwo\n").index();
        ui.layoutOnly();
        assertEquals(3, ui.lineCount(t)); // the trailing newline opens an empty third line
        assertEquals(15, ui.w(t), EPS);

        ui = ui(300, 100);
        t = ui.text("").index();
        ui.layoutOnly();
        assertEquals(1, ui.lineCount(t));
        assertEquals(12, ui.h(t), EPS);
    }

    @Test
    void numbersFormatWithoutStrings() {
        Ui ui = ui(300, 100);
        ui.text()
                .append(-12)
                .append(' ')
                .append(3.14159f, 2)
                .append(' ')
                .append(0.5f, 0)
                .append(' ')
                .append(7L)
                .append(' ')
                .append(-0.004f, 2);
        ui.layoutOnly();
        assertEquals("-12 3.14 1 7 -0.00", new String(ui.chars, 0, ui.charCount()));
    }

    @Test
    void floatingElementsAttachToAnchorsAndStayOnScreen() {
        Ui ui = ui(300, 200);
        int panel = ui.box().open().index();
        ui.box().size(100, 50);
        int tip =
                ui.box()
                        .size(40, 20)
                        .floating()
                        .anchor(Ui.BOTTOM_RIGHT, Ui.TOP_LEFT)
                        .offset(5, 5)
                        .index();
        int pop = ui.box().size(60, 60).floating().anchor(Ui.BOTTOM_LEFT, Ui.TOP_LEFT).index();
        int centered =
                ui.box().size(20, 20).floatingOnScreen().anchor(Ui.MIDDLE, Ui.MIDDLE).index();
        ui.close();
        ui.layoutOnly();
        assertRect(ui, panel, 0, 0, 100, 50);
        assertRect(ui, tip, 105, 55, 40, 20);
        assertRect(ui, pop, 0, 50, 60, 60);
        assertRect(ui, centered, 140, 90, 20, 20);

        // Floating children take no part in their parent's fit size (the panel is 100 × 50).
        assertEquals(100, ui.contentWidth(panel), EPS);

        // Clamped to the screen: a tooltip past the right edge slides back in.
        ui = ui(300, 200);
        ui.box().size(300, 50).open();
        tip = ui.box().size(40, 20).floating().anchor(Ui.BOTTOM_RIGHT, Ui.TOP_LEFT).index();
        ui.close();
        ui.layoutOnly();
        assertRect(ui, tip, 260, 50, 40, 20);
    }

    @Test
    void scrollBoxesKeepTheirContentAndClampTheOffset() {
        long list = 42;
        Ui ui = ui(300, 200);
        ui.setScroll(list, 1, 1000);
        int box = ui.box(list).width(100).height(50).scroll(false, true).column().open().index();
        int tall = ui.box().width(50).height(200).index();
        ui.close();
        ui.layoutOnly();
        assertRect(ui, box, 0, 0, 100, 50);
        assertEquals(200, ui.contentHeight(box), EPS);
        assertEquals(150, ui.scroll(list, 1), EPS);
        assertRect(ui, tall, 0, -150, 50, 200);

        // A scroll box never shrinks its children along the scroll axis.
        assertEquals(200, ui.h(tall), EPS);
    }

    @Test
    void unbalancedDeclarationsAreErrors() {
        Ui ui = ui(300, 200);
        ui.box().open();
        assertThrows(IllegalStateException.class, () -> ui.endFrame(new UiDrawList(16, 4)));
        ui.close();
        assertThrows(IllegalStateException.class, ui::close);
        assertThrows(IllegalStateException.class, () -> ui.text().open());
        ui.pushId(3);
        assertThrows(IllegalStateException.class, () -> ui.endFrame(new UiDrawList(16, 4)));
        ui.popId();
        assertThrows(IllegalStateException.class, ui::popId);
    }

    @Test
    void idScopesMakeListItemsDistinct() {
        Ui ui = ui(300, 200);
        long item = 7;
        ui.pushId(0);
        long a = ui.resolve(item);
        ui.popId();
        ui.pushId(1);
        long b = ui.resolve(item);
        ui.pushId(1);
        long c = ui.resolve(item);
        ui.popId();
        ui.popId();
        assertEquals(item, ui.resolve(item));
        assertTrue(a != b && b != c && a != c && a != item);
    }

    @Test
    void capacityGrowsOnTheColdPath() {
        Ui ui = new Ui(new UiConfig(16, 256, 16, 16, 1));
        UiInput in = new UiInput();
        in.width = 100;
        in.height = 100;
        ui.beginFrame(in);
        ui.box().column().open();
        for (int i = 0; i < 100; i++) {
            ui.text("a long line of text that needs more characters than the buffer has")
                    .wrap()
                    .width(60);
        }
        ui.close();
        ui.endFrame(new UiDrawList(16, 4));
        assertEquals(102, ui.elementCount());
    }
}
