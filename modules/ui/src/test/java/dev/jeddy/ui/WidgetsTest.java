package dev.jeddy.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.Test;

/** The built-in widgets driven by scripted input (TASKS M13-04). */
class WidgetsTest {
    private static final long BUTTON = 1;
    private static final long CHECK = 2;
    private static final long SLIDER = 3;
    private static final long FIELD = 4;
    private static final long DROP = 5;
    private static final long TREE = 6;
    private static final long LEAF = 7;
    private static final long TABS = 8;
    private static final long WINDOW = 9;
    private static final long MENU = 10;
    private static final long ITEM = 11;
    private static final long DRAG = 12;

    private final Ui ui = new Ui();
    private final Theme theme = Theme.dark();
    private final Widgets w = new Widgets(ui, theme);
    private final UiInput in = new UiInput();
    private final UiDrawList out = new UiDrawList(256, 16);

    // Game-side state the widgets edit.
    private boolean checked;
    private float volume = 0.5f;
    private final TextBuffer name = new TextBuffer("abc");
    private int choice;
    private int tab;
    private int clicks;
    private float dragged = 10;
    private final CharSequence[] options = {"One", "Two", "Three"};

    WidgetsTest() {
        in.width = 600;
        in.height = 400;
        in.dt = 1f / 60;
        ui.defaultFontSize = 10; // the metrics font: 5 px per char
        theme.fontSize = 10;
        w.applyTheme();
    }

    /** A panel of one of everything at the top-left corner; widgets are 28 px tall, gap 6. */
    private void frame() {
        ui.beginFrame(in);
        w.panel().width(300).open(); // padding 8: content starts at (8, 8)
        if (w.button(BUTTON, "Click")) {
            clicks++;
        }
        checked = w.checkbox(CHECK, "Check", checked); // y 42
        volume = w.slider(SLIDER, volume, 0, 1); // y 76
        w.textField(FIELD, name); // y 110
        choice = w.dropdown(DROP, choice, options); // y 144
        if (w.treeNode(TREE, "Node")) { // y 178, 24 tall
            w.treeLeaf(LEAF, "Leaf", false); // y 202
            w.treeEnd();
        }
        tab = w.tabs(TABS, tab, options);
        dragged = w.dragValue(DRAG, dragged, 0.5f, 0, 100, 1);
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

    @Test
    void buttonsCountClicksAndCheckboxesToggle() {
        click(30, 20);
        assertEquals(1, clicks);
        frame();
        assertEquals(1, clicks);
        click(30, 55);
        assertTrue(checked);
        click(30, 55);
        assertFalse(checked);
    }

    @Test
    void slidersFollowTheDragAndTheArrowKeys() {
        in.mouseX = 20;
        in.mouseY = 90;
        frame();
        in.buttonsPressed = UiInput.LEFT;
        in.buttonsDown = UiInput.LEFT;
        frame(); // the press makes the slider active; the value follows the pointer next frame
        frame();
        // Track from x = 8 to 292 (284 wide), thumb travel 270 starting at 15: 20 is near 0.
        assertEquals(5f / 270, volume, 1e-3);
        in.mouseX = 150;
        frame();
        assertEquals(135f / 270, volume, 1e-3);
        in.buttonsDown = 0;
        in.buttonsReleased = UiInput.LEFT;
        frame();
        frame();
        in.key(UiKey.RIGHT, 0, true, false);
        frame();
        assertEquals(135f / 270 + 0.01f, volume, 1e-3);
        in.key(UiKey.RIGHT, 0, true, false);
        frame();
        assertTrue(ui.focused(SLIDER)); // arrows stay with the focused slider
    }

    @Test
    void textFieldsEditTheBufferFromKeysAndTypedText() {
        click(100, 124); // past the text: caret at the end
        assertTrue(ui.focused(FIELD));
        assertEquals(3, name.caret());
        in.text('d');
        in.text('e');
        frame();
        assertEquals("abcde", name.toString());
        in.key(UiKey.LEFT, 0, true, false);
        in.key(UiKey.LEFT, UiKey.MOD_SHIFT, true, false);
        frame();
        assertEquals("d", name.subSequence(name.selectionStart(), name.selectionEnd()));
        in.key(UiKey.BACKSPACE, 0, true, false);
        frame();
        assertEquals("abce", name.toString());
        in.key(UiKey.HOME, 0, true, false);
        in.key(UiKey.DELETE, 0, true, false);
        frame();
        assertEquals("bce", name.toString());
        in.key(UiKey.A, UiKey.MOD_CTRL, true, false);
        in.key(UiKey.C, UiKey.MOD_CTRL, true, false);
        in.key(UiKey.END, 0, true, false);
        in.key(UiKey.V, UiKey.MOD_CTRL, true, false);
        frame();
        assertEquals("bcebce", name.toString());
        in.key(UiKey.RETURN, 0, true, false);
        frame();
        assertTrue(w.submitted(FIELD));
        frame();
        assertFalse(w.submitted(FIELD));

        // A click inside the text places the caret at the nearest boundary (5 px per char).
        in.dt = 1; // not a double click
        frame();
        in.dt = 1f / 60;
        click(8 + 8 + 12, 124); // text starts at x = 16; 12 px in is between 'c' (10) and 'e'(15)
        assertEquals(2, name.caret());
        in.key(UiKey.RIGHT, UiKey.MOD_CTRL, true, false);
        frame();
        assertEquals(6, name.caret());
        // Escape blurs; typing then changes nothing.
        in.key(UiKey.ESCAPE, 0, true, false);
        frame();
        in.text('x');
        frame();
        assertEquals("bcebce", name.toString());
    }

    @Test
    void dropdownsOpenChooseAndCloseOnPressesOutside() {
        click(100, 158);
        assertTrue(w.popupOpen(DROP) || ui.stateI(ui.state(DROP), 0) != 0);
        // The popup hangs 2 px under the button (bottom at 172): option rows start at 176.
        in.mouseX = 100;
        in.mouseY = 176 + 28 + 14;
        frame();
        in.click(100, 176 + 28 + 14);
        frame();
        assertEquals(1, choice);
        assertEquals(0, ui.stateI(ui.state(DROP), 0));
        click(100, 158); // open again
        click(500, 300); // press outside closes without choosing
        assertEquals(1, choice);
        assertEquals(0, ui.stateI(ui.state(DROP), 0));
    }

    @Test
    void treeNodesToggleAndTabsSelect() {
        in.mouseX = 30;
        in.mouseY = 190;
        frame();
        frame();
        int before = ui.elementCount();
        click(30, 190);
        assertTrue(ui.elementCount() > before); // the leaf appeared
        click(30, 212);
        assertTrue(ui.clicked(LEAF));
        click(30, 190);
        assertEquals(before, ui.elementCount());

        // Tabs: the second tab sits after "One" (3 chars × 5 px + 16 padding = 31 px, gap 2).
        int tabsY = 178 + 24 + 6 + 10;
        click(8 + 31 + 2 + 10, tabsY);
        assertEquals(1, tab);
    }

    @Test
    void dragValuesFollowHorizontalDrags() {
        int y = 178 + 24 + 6 + 20 + 6 + 14; // below the tabs row (20 tall)
        in.mouseX = 30;
        in.mouseY = y;
        frame();
        in.buttonsPressed = UiInput.LEFT;
        in.buttonsDown = UiInput.LEFT;
        frame();
        assertTrue(ui.active(DRAG));
        in.mouseX = 50;
        frame();
        assertEquals(20, dragged, 1e-3); // 20 px × 0.5 per px
        in.mouseX = 300;
        frame();
        assertEquals(100, dragged, 1e-3); // clamped
    }

    @Test
    void windowsDragByTheirTitleBarAndPopupsCloseOutside() {
        Runnable f =
                () -> {
                    ui.beginFrame(in);
                    w.beginWindow(WINDOW, "Tools", 100, 100, 200, 150);
                    if (w.button(BUTTON, "Menu")) {
                        w.openPopup(MENU);
                    }
                    if (w.beginPopup(MENU, BUTTON, Ui.BOTTOM_LEFT, Ui.TOP_LEFT)) {
                        if (w.menuItem(MENU, ITEM, "Do it")) {
                            clicks++;
                        }
                        w.endPopup();
                    }
                    w.endWindow();
                    ui.endFrame(out);
                    in.clearEvents();
                };
        in.mouseX = 150;
        in.mouseY = 110;
        f.run();
        f.run();
        int r = ui.lastRecord(WINDOW);
        assertEquals(100, ui.recordX(r), 1e-3);
        in.buttonsPressed = UiInput.LEFT;
        in.buttonsDown = UiInput.LEFT;
        f.run();
        in.mouseX = 180;
        in.mouseY = 130;
        f.run();
        in.buttonsDown = 0;
        in.buttonsReleased = UiInput.LEFT;
        f.run();
        f.run();
        r = ui.lastRecord(WINDOW);
        assertEquals(130, ui.recordX(r), 1e-3);
        assertEquals(120, ui.recordY(r), 1e-3);

        // The menu button is at (138, 156) inside the window's content; open the popup.
        int b = ui.lastRecord(BUTTON);
        float bx = ui.recordX(b) + 10;
        float by = ui.recordY(b) + 10;
        in.mouseX = bx;
        in.mouseY = by;
        f.run();
        in.click(bx, by);
        f.run();
        assertTrue(w.popupOpen(MENU));
        f.run();
        int item = ui.lastRecord(ITEM);
        assertTrue(item >= 0);
        float ix = ui.recordX(item) + 10;
        float iy = ui.recordY(item) + 10;
        in.mouseX = ix;
        in.mouseY = iy;
        f.run();
        in.click(ix, iy);
        f.run();
        assertEquals(1, clicks);
        assertFalse(w.popupOpen(MENU));
        // Reopen, press elsewhere: closed, nothing clicked.
        in.mouseX = bx;
        in.mouseY = by;
        f.run();
        in.click(bx, by);
        f.run();
        assertTrue(w.popupOpen(MENU));
        in.mouseX = 500;
        in.mouseY = 350;
        f.run();
        in.click(500, 350);
        f.run();
        assertFalse(w.popupOpen(MENU));
        assertEquals(1, clicks);
    }

    @Test
    void aFrameOfWidgetsAllocatesNothing() {
        // Focus the text field with a selection and open the dropdown, then measure frames that
        // keep typing, dragging and hovering.
        click(100, 124);
        in.key(UiKey.A, UiKey.MOD_CTRL, true, false);
        frame();
        int[] n = new int[1];
        Runnable body =
                () -> {
                    n[0]++;
                    in.mouseX = 20 + n[0] % 100;
                    in.mouseY = 10 + n[0] % 200;
                    in.buttonsPressed = n[0] % 50 == 0 ? UiInput.LEFT : 0;
                    in.buttonsReleased = n[0] % 50 == 1 ? UiInput.LEFT : 0;
                    in.buttonsDown = n[0] % 50 == 0 ? UiInput.LEFT : 0;
                    if (n[0] % 10 == 0) {
                        in.text('x');
                    }
                    if (n[0] % 13 == 0) {
                        in.key(UiKey.BACKSPACE, 0, true, false);
                    }
                    ui.beginFrame(in);
                    w.beginWindow(WINDOW, "Tools", 320, 20, 200, 0);
                    w.label("inside");
                    w.progress(0.4f);
                    w.endWindow();
                    w.panel().width(300).open();
                    if (w.button(BUTTON, "Click")) {
                        clicks++;
                    }
                    w.tooltip(BUTTON, "Help");
                    checked = w.checkbox(CHECK, "Check", checked);
                    w.radio(LEAF, "Radio", checked);
                    volume = w.slider(SLIDER, volume, 0, 1);
                    w.textField(FIELD, name, "name");
                    choice = w.dropdown(DROP, choice, options);
                    if (w.treeNode(TREE, "Node", true)) {
                        w.treeLeaf(ITEM, "Leaf", true);
                        w.treeEnd();
                    }
                    tab = w.tabs(TABS, tab, options);
                    dragged = w.dragValue(DRAG, dragged, 0.5f, 0, 100, 1);
                    w.separator();
                    w.paragraph("some wrapping text in a paragraph of the panel");
                    ui.close();
                    ui.endFrame(out);
                    in.clearEvents();
                };
        for (int i = 0; i < 100; i++) {
            body.run();
        }
        Allocations.assertNoAllocation("widgets frame", 20, 500, body);
    }

    @Test
    void tooltipsAppearAfterTheDelay() {
        Runnable f =
                () -> {
                    ui.beginFrame(in);
                    w.button(BUTTON, "Hover me");
                    w.tooltip(BUTTON, "Help");
                    ui.endFrame(out);
                };
        in.mouseX = 20;
        in.mouseY = 10;
        f.run();
        f.run();
        int without = ui.elementCount();
        in.dt = 1;
        f.run();
        f.run();
        assertTrue(ui.elementCount() > without);
        in.mouseX = 400;
        f.run();
        f.run();
        assertEquals(without, ui.elementCount());
    }
}
