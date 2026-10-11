package dev.jeddy.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Hover, press, click, drag and drop, focus and wheel scrolling resolved from the previous frame's
 * rectangles (TASKS M13-03, M13-05).
 */
class InteractionTest {
    private static final long A = 1;
    private static final long B = 2;
    private static final long C = 3;
    private static final long LIST = 4;
    private static final long POPUP = 5;

    private final Ui ui = new Ui();
    private final UiInput in = new UiInput();
    private final UiDrawList out = new UiDrawList(64, 8);

    InteractionTest() {
        in.width = 400;
        in.height = 300;
        in.dt = 1f / 60;
    }

    /** A frame with two buttons (A at 10,10 100×30; B at 10,60 100×30) and a scroll list. */
    private void frame() {
        ui.beginFrame(in);
        ui.box().column().padding(10).gap(20).open();
        ui.box(A).size(100, 30).background(0xFF333333).focusable().activateWithKeys();
        ui.box(B).size(100, 30).background(0xFF333333).focusable();
        ui.box(LIST).size(100, 50).scroll(false, true).background(0xFF222222).column().open();
        ui.box().size(80, 300);
        ui.close();
        ui.close();
        ui.endFrame(out);
        in.clearEvents();
    }

    @Test
    void hoverAndClickUseLastFramesRectangles() {
        in.mouseX = 50;
        in.mouseY = 20;
        frame(); // nothing recorded yet
        assertFalse(ui.hovered(A));
        assertFalse(ui.wantsMouse());
        frame();
        assertTrue(ui.hovered(A));
        assertFalse(ui.hovered(B));
        assertTrue(ui.wantsMouse());

        in.click(50, 20);
        frame();
        assertTrue(ui.clicked(A));
        assertFalse(ui.active(A));
        assertFalse(ui.clicked(B));
        frame();
        assertFalse(ui.clicked(A)); // clicks are edges

        // Press on A, release elsewhere: no click.
        in.buttonsPressed = UiInput.LEFT;
        in.buttonsDown = UiInput.LEFT;
        frame();
        assertTrue(ui.active(A));
        in.mouseY = 75;
        frame();
        assertTrue(ui.active(A)); // still active while held, even off the element
        in.buttonsDown = 0;
        in.buttonsReleased = UiInput.LEFT;
        frame();
        assertFalse(ui.clicked(A));
        assertFalse(ui.active(A));

        // Empty space: no hover, the game keeps the mouse.
        in.mouseX = 300;
        in.mouseY = 200;
        frame();
        frame();
        assertFalse(ui.wantsMouse());
        assertFalse(ui.hovered(A));
    }

    @Test
    void doubleClickCountsCloseClicks() {
        in.mouseX = 50;
        in.mouseY = 20;
        frame();
        in.click(50, 20);
        frame();
        assertTrue(ui.clicked(A));
        assertFalse(ui.doubleClicked(A));
        in.click(50, 20);
        frame();
        assertTrue(ui.doubleClicked(A));
        in.dt = 1;
        in.click(50, 20);
        frame();
        assertFalse(ui.doubleClicked(A));
    }

    @Test
    void theTopmostElementUnderThePointerWins() {
        in.mouseX = 50;
        in.mouseY = 50;
        for (int i = 0; i < 2; i++) {
            ui.beginFrame(in);
            ui.box(A).size(100, 100).background(0xFF111111).open();
            ui.box(B).size(100, 100).background(0xFF222222).floating().offset(0, 0);
            ui.close();
            ui.box(C).size(100, 100).background(0xFF333333).floatingOnScreen().offset(90, 90);
            ui.endFrame(out);
        }
        assertTrue(ui.hovered(B)); // floating B is drawn over A
        in.mouseX = 95;
        in.mouseY = 95;
        ui.beginFrame(in);
        ui.endFrame(out);
        assertTrue(ui.hovered(C)); // C is declared later, so it is on top of B
    }

    @Test
    void clippedChildrenAreNotHitOutsideTheirParent() {
        in.mouseX = 50;
        in.mouseY = 200;
        for (int i = 0; i < 2; i++) {
            ui.beginFrame(in);
            ui.box(A).size(100, 100).clip().open();
            ui.box(B).size(100, 300).background(0xFF222222);
            ui.close();
            ui.endFrame(out);
        }
        assertFalse(ui.hovered(B));
        assertFalse(ui.wantsMouse());
        in.mouseY = 50;
        ui.beginFrame(in);
        ui.endFrame(out);
        assertTrue(ui.hovered(B));
    }

    @Test
    void dragAndDropCarriesAPayload() {
        Runnable frame =
                () -> {
                    ui.beginFrame(in);
                    ui.box(A).size(50, 50).background(0xFF111111).dragSource(7, 99);
                    ui.box(B)
                            .size(50, 50)
                            .background(0xFF222222)
                            .dropTarget()
                            .floatingOnScreen()
                            .offset(200, 0);
                    ui.endFrame(out);
                    in.clearEvents();
                };
        in.mouseX = 25;
        in.mouseY = 25;
        frame.run();
        in.buttonsPressed = UiInput.LEFT;
        in.buttonsDown = UiInput.LEFT;
        frame.run();
        assertTrue(ui.active(A));
        assertFalse(ui.dragging());
        in.mouseX = 27; // under the threshold
        frame.run();
        assertFalse(ui.dragging());
        in.mouseX = 40;
        frame.run();
        assertTrue(ui.dragging());
        assertEquals(A, ui.dragSource());
        assertEquals(7, ui.dragKind());
        assertEquals(99, ui.dragValue());
        assertFalse(ui.dropHover(B));
        in.mouseX = 225;
        frame.run();
        assertTrue(ui.dropHover(B));
        assertFalse(ui.dropped(B));
        in.buttonsDown = 0;
        in.buttonsReleased = UiInput.LEFT;
        frame.run();
        assertTrue(ui.dropped(B));
        assertFalse(ui.dragging());
        assertFalse(ui.clicked(A));
        frame.run();
        assertFalse(ui.dropped(B));
    }

    @Test
    void focusMovesWithTabAndArrowsAndActivatesWithEnter() {
        frame();
        assertFalse(ui.wantsKeyboard());
        in.key(UiKey.TAB, 0, true, false);
        frame();
        assertTrue(ui.focused(A));
        assertTrue(ui.showFocus());
        assertTrue(ui.wantsKeyboard());
        in.key(UiKey.TAB, 0, true, false);
        frame();
        assertTrue(ui.focused(B));
        in.key(UiKey.TAB, 0, true, false);
        frame();
        assertTrue(ui.focused(A)); // wraps around (the list is not focusable)
        in.key(UiKey.TAB, UiKey.MOD_SHIFT, true, false);
        frame();
        assertTrue(ui.focused(B));
        in.key(UiKey.UP, 0, true, false);
        frame();
        assertTrue(ui.focused(A));
        in.key(UiKey.RETURN, 0, true, false);
        frame();
        assertTrue(ui.clicked(A));
        in.key(UiKey.ESCAPE, 0, true, false);
        frame();
        assertFalse(ui.wantsKeyboard());

        // A press on a focusable element focuses it; on empty space, blurs.
        in.mouseX = 50;
        in.mouseY = 75;
        frame();
        in.click(50, 75);
        frame();
        assertTrue(ui.focused(B));
        assertFalse(ui.showFocus());
        in.mouseX = 300;
        in.mouseY = 250;
        frame();
        in.click(300, 250);
        frame();
        assertFalse(ui.focused(B));
    }

    @Test
    void theWheelScrollsTheBoxUnderThePointer() {
        in.mouseX = 50;
        in.mouseY = 130;
        frame();
        frame();
        in.wheelY = -1;
        frame();
        assertEquals(40, ui.scroll(LIST, 1), 1e-3);
        in.wheelY = -10;
        frame();
        assertEquals(250, ui.scroll(LIST, 1), 1e-3); // clamped to content - size
        in.wheelY = 20;
        frame();
        assertEquals(0, ui.scroll(LIST, 1), 1e-3);

        // The scrollbar: a press below the thumb pages down; dragging the thumb tracks it.
        in.mouseX = 105;
        in.mouseY = 155;
        frame();
        in.click(105, 155);
        frame();
        assertEquals(50, ui.scroll(LIST, 1), 1e-3);
        frame();
        in.mouseY = 118;
        frame();
        in.buttonsPressed = UiInput.LEFT;
        in.buttonsDown = UiInput.LEFT;
        frame();
        assertTrue(ui.wantsMouse());
        in.mouseY = 135; // 17 px further along the 34 px the thumb can travel: +0.5 of 250
        frame();
        assertEquals(175, ui.scroll(LIST, 1), 1e-3);
        in.buttonsDown = 0;
        in.buttonsReleased = UiInput.LEFT;
        frame();
        frame();
        assertEquals(175, ui.scroll(LIST, 1), 1e-3);
    }

    @Test
    void popupsLearnWhereAPressLanded() {
        Runnable frame =
                () -> {
                    ui.beginFrame(in);
                    ui.box(A).size(50, 50).background(0xFF111111).open();
                    ui.box(POPUP)
                            .size(80, 80)
                            .background(0xFF222222)
                            .floating()
                            .anchor(Ui.BOTTOM_LEFT, Ui.TOP_LEFT)
                            .open();
                    ui.box(B).size(20, 20).background(0xFF333333);
                    ui.close();
                    ui.close();
                    ui.endFrame(out);
                    in.clearEvents();
                };
        in.mouseX = 10;
        in.mouseY = 60;
        frame.run();
        in.click(10, 60); // on B, inside the popup
        frame.run();
        assertTrue(ui.pressedThisFrame());
        assertEquals(POPUP, ui.pressedFloating());
        assertEquals(B, ui.pressedId());
        in.click(25, 25); // on A, outside the popup
        frame.run();
        assertEquals(0, ui.pressedFloating());
        assertEquals(A, ui.pressedId());
        in.click(300, 250); // empty
        frame.run();
        assertTrue(ui.pressedThisFrame());
        assertEquals(0, ui.pressedId());
    }

    @Test
    void hoverTimeAccumulatesWhileThePointerRests() {
        in.mouseX = 50;
        in.mouseY = 20;
        frame();
        frame();
        frame();
        assertEquals(1f / 60, ui.hoverTime(), 1e-4);
        in.mouseY = 75;
        frame();
        assertEquals(0, ui.hoverTime(), 1e-6);
    }

    @Test
    void stateSlotsPersistAndAreRecycled() {
        Ui small = new Ui(new UiConfig(16, 256, 16, 16, 1));
        small.beginFrame(in);
        int slot = small.state(A);
        small.setStateF(slot, 2, 0.5f);
        small.setStateI(slot, 1, 9);
        small.endFrame(out);
        small.beginFrame(in);
        assertEquals(slot, small.state(A));
        assertEquals(0.5f, small.stateF(slot, 2));
        assertEquals(9, small.stateI(slot, 1));
        small.endFrame(out);
        // 16 slots: A plus 15 others fill the store; three frames later A is stale and recycled.
        for (int i = 0; i < 15; i++) {
            small.state(100 + i);
        }
        small.beginFrame(in);
        small.endFrame(out);
        small.beginFrame(in);
        small.endFrame(out);
        small.beginFrame(in);
        int reused = small.state(200);
        assertEquals(slot, reused);
        assertFalse(small.hasState(A));
        assertEquals(0, small.stateI(reused, 1));
    }
}
