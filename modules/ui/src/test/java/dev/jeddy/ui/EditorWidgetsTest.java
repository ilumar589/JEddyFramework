package dev.jeddy.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.Test;

/**
 * The widgets added for the editor (TASKS M14-07): menu bar, splitter, number, vector and color
 * fields, list box, property rows, and the float formatting and parsing behind the fields.
 */
class EditorWidgetsTest {
    private static final long BAR = 1;
    private static final long FILE = 2;
    private static final long EDIT = 3;
    private static final long SAVE = 4;
    private static final long UNDO = 5;
    private static final long SPLIT = 6;
    private static final long NUMBER = 7;
    private static final long VECTOR = 8;
    private static final long COLOR = 9;
    private static final long LIST = 10;
    private static final long PROP_BUTTON = 11;

    private final Ui ui = new Ui();
    private final Theme theme = Theme.dark();
    private final Widgets w = new Widgets(ui, theme);
    private final UiInput in = new UiInput();
    private final UiDrawList out = new UiDrawList(256, 16);

    private int saves;
    private int undos;
    private float split = 200;
    private float number = 1.5f;
    private final float[] vector = {1, 2, 3};
    private final float[] color = {0.5f, 0.25f, 1};
    private int selected = 1;
    private final CharSequence[] items = {"alpha", "beta", "gamma"};

    EditorWidgetsTest() {
        in.width = 600;
        in.height = 400;
        in.dt = 1f / 60;
        ui.defaultFontSize = 10; // the metrics font: 5 px per char
        theme.fontSize = 10;
        w.applyTheme();
    }

    /**
     * Menu bar (28 tall) at the top; below it a panel 300 wide: a splitter row, a number field, a
     * vector field, a color field, a list box, a property row.
     */
    private void frame() {
        ui.beginFrame(in);
        ui.box().column().widthGrow().open();
        w.beginMenuBar(BAR);
        if (w.menu(FILE, "File")) {
            if (w.menuItem(SAVE, "Save", "Ctrl+S", true)) {
                saves++;
            }
            w.endMenu();
        }
        if (w.menu(EDIT, "Edit")) {
            if (w.menuItem(UNDO, "Undo", "Ctrl+Z", true)) {
                undos++;
            }
            w.endMenu();
        }
        w.endMenuBar();
        w.panel().width(300).open(); // y 28, padding 8: content from (8, 36)
        ui.box().row().widthGrow().height(40).open();
        ui.box().width(split).heightGrow().background(0xFF333333);
        split += w.splitter(SPLIT, true);
        ui.close();
        number = w.numberField(NUMBER, number, 0.1f, -100, 100, 2); // y 82, 28 tall
        w.vector3Field(VECTOR, vector, 0, 0.1f, 1); // y 116
        w.colorField(COLOR, color, 0, 10); // y 150
        selected = w.listBox(LIST, selected, items, items.length, 80); // y 184
        w.beginProperty("Label");
        w.button(PROP_BUTTON, "value"); // y 264
        w.endProperty();
        ui.close();
        ui.close();
        ui.endFrame(out);
        in.clearEvents();
    }

    /** Lets the double-click window pass, then clicks twice. */
    private void doubleClick(float x, float y) {
        for (int i = 0; i < 25; i++) {
            frame();
        }
        in.click(x, y);
        frame();
        in.click(x, y);
        frame();
        frame();
    }

    private void click(float x, float y) {
        in.mouseX = x;
        in.mouseY = y;
        frame();
        in.click(x, y);
        frame();
    }

    @Test
    void menusOpenOnClickSwitchOnHoverAndCloseOnChoice() {
        // The File button sits at x 4..~40 of the bar; click opens its popup under it.
        click(20, 14);
        frame();
        assertTrue(w.popupOpen(Ui.combine(FILE, 0x9099L)));
        // The popup's first item is right under the bar: clicking it fires and closes the menu.
        click(30, 28 + 2 + 4 + 12);
        assertEquals(1, saves);
        frame();
        assertFalse(w.popupOpen(Ui.combine(FILE, 0x9099L)));
        // Open File again, then hover Edit: the open menu follows the pointer.
        click(20, 14);
        frame();
        in.mouseX = 60;
        in.mouseY = 14;
        frame();
        frame();
        assertFalse(w.popupOpen(Ui.combine(FILE, 0x9099L)));
        assertTrue(w.popupOpen(Ui.combine(EDIT, 0x9099L)));
        // A press elsewhere closes it.
        click(300, 300);
        frame();
        assertFalse(w.popupOpen(Ui.combine(EDIT, 0x9099L)));
        assertEquals(0, undos);
    }

    @Test
    void splitterReportsTheDrag() {
        // The splitter is at x = 8 + 200 .. 213, y 36..76.
        in.mouseX = 210;
        in.mouseY = 50;
        frame();
        in.buttonsPressed = UiInput.LEFT;
        in.buttonsDown = UiInput.LEFT;
        frame();
        in.mouseX = 240;
        frame();
        assertEquals(230, split, 1e-4);
        in.mouseX = 230;
        frame();
        assertEquals(220, split, 1e-4);
        in.buttonsDown = 0;
        in.buttonsReleased = UiInput.LEFT;
        frame();
        in.mouseX = 300;
        frame();
        assertEquals(220, split, 1e-4, "no drag after the release");
    }

    @Test
    void numberFieldDragsAndTakesTypedValues() {
        // Drag: 20 pixels right at step 0.1 adds 2.
        in.mouseX = 100;
        in.mouseY = 96;
        frame();
        in.buttonsPressed = UiInput.LEFT;
        in.buttonsDown = UiInput.LEFT;
        frame();
        in.mouseX = 120;
        frame();
        assertEquals(3.5f, number, 1e-4);
        in.buttonsDown = 0;
        in.buttonsReleased = UiInput.LEFT;
        frame();
        // Double click: the field becomes a text field holding "3.50", selected.
        doubleClick(120, 96);
        assertTrue(ui.focused(Ui.combine(Ui.combine(0, NUMBER), 0xF1E1DL)));
        in.text('-');
        in.text('7');
        in.text('.');
        in.text('2');
        in.text('5');
        frame();
        in.key(UiKey.RETURN, 0, true, false);
        frame();
        assertEquals(-7.25f, number, 1e-4);
        frame();
        assertFalse(ui.focused(Ui.combine(Ui.combine(0, NUMBER), 0xF1E1DL)));
        // Escape cancels an edit.
        doubleClick(120, 96);
        in.text('9');
        frame();
        in.key(UiKey.ESCAPE, 0, true, false);
        frame();
        assertEquals(-7.25f, number, 1e-4);
        // Nonsense typed in is ignored on commit.
        doubleClick(120, 96);
        in.text('x');
        frame();
        in.key(UiKey.RETURN, 0, true, false);
        frame();
        assertEquals(-7.25f, number, 1e-4);
    }

    @Test
    void vectorAndColorFieldsEditTheirComponents() {
        // The vector row: label X (5 px) then a field; drag the first field.
        in.mouseX = 40;
        in.mouseY = 130;
        frame();
        in.buttonsPressed = UiInput.LEFT;
        in.buttonsDown = UiInput.LEFT;
        frame();
        in.mouseX = 50;
        frame();
        assertEquals(2, vector[0], 1e-4);
        assertEquals(2, vector[1], 1e-4, "the other components are untouched");
        in.buttonsDown = 0;
        in.buttonsReleased = UiInput.LEFT;
        frame();
        // The color row: a 28 px swatch, then R G B fields; drag the green one.
        in.mouseX = 8 + 28 + 4 + 90;
        in.mouseY = 164;
        frame();
        in.buttonsPressed = UiInput.LEFT;
        in.buttonsDown = UiInput.LEFT;
        frame();
        in.mouseX += 100;
        frame();
        in.buttonsDown = 0;
        in.buttonsReleased = UiInput.LEFT;
        frame();
        assertEquals(0.5f, color[0], 1e-4, "red untouched");
        assertEquals(0.25f + 100 * 0.005f, color[1], 1e-3, "green dragged");
    }

    @Test
    void listBoxSelectsOnClickAndPropertyRowsLayOut() {
        // Rows are 22 tall with a 1 px gap from y = 184 + 2.
        click(100, 184 + 2 + 11);
        assertEquals(0, selected);
        click(100, 184 + 2 + 23 + 23 + 11);
        assertEquals(2, selected);
        // The property label column is theme.labelWidth wide: the value starts after it.
        frame();
        int r = ui.lastRecord(PROP_BUTTON);
        assertTrue(r >= 0);
        assertEquals(
                8 + theme.labelWidth + theme.gap, ui.recordX(r), 1e-3, "after the label column");
    }

    @Test
    void floatFormattingAndParsingRoundTrip() {
        TextBuffer b = new TextBuffer(32);
        Widgets.appendFloat(b, 3.14159f, 2);
        assertEquals("3.14", b.toString());
        b.clear();
        Widgets.appendFloat(b, -0.5f, 1);
        assertEquals("-0.5", b.toString());
        b.clear();
        Widgets.appendFloat(b, 1234, 0);
        assertEquals("1234", b.toString());
        b.clear();
        Widgets.appendFloat(b, 0.999f, 2);
        assertEquals("1.00", b.toString());
        assertEquals(-12.5f, Widgets.parseFloat("-12.5"), 1e-6);
        assertEquals(0.75f, Widgets.parseFloat(".75"), 1e-6);
        assertEquals(300f, Widgets.parseFloat("3e2"), 1e-6);
        assertEquals(7f, Widgets.parseFloat(" 7 "), 1e-6);
        assertTrue(Float.isNaN(Widgets.parseFloat("abc")));
        assertTrue(Float.isNaN(Widgets.parseFloat("1.2.3")));
        assertTrue(Float.isNaN(Widgets.parseFloat("")));
        Allocations.assertNoAllocation(
                "format and parse",
                () -> {
                    for (int i = 0; i < 100; i++) {
                        b.clear();
                        Widgets.appendFloat(b, i * 1.25f, 3);
                        Widgets.parseFloat(b);
                    }
                });
    }

    @Test
    void idleFramesAllocateNothing() {
        for (int i = 0; i < 3; i++) {
            frame();
        }
        Allocations.assertNoAllocation(
                "editor widgets frame",
                () -> {
                    for (int i = 0; i < 10; i++) {
                        frame();
                    }
                });
    }
}
