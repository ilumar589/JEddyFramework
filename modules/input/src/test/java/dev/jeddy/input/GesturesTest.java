package dev.jeddy.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** CRPG gestures and the gamepad virtual cursor (TASKS M2-07, M2-08). */
class GesturesTest {
    private FakeEvents ev;
    private CrpgGestures gestures;

    @BeforeEach
    void setUp() {
        ev = new FakeEvents();
        gestures = new CrpgGestures();
        ev.focus(true).move(640, 360);
        frame();
    }

    private void frame() {
        ev.frame();
        gestures.update(ev.input);
    }

    @Test
    void aShortPressIsAClick() {
        ev.button(MouseButton.LEFT, true, 1, 640, 360).move(642, 361);
        frame();
        assertFalse(gestures.clicked(MouseButton.LEFT));
        assertFalse(gestures.isDragging(MouseButton.LEFT), "2 px is below the threshold");
        ev.button(MouseButton.LEFT, false, 1, 642, 361);
        frame();
        assertTrue(gestures.clicked(MouseButton.LEFT));
        assertTrue(gestures.selectClicked());
        assertEquals(640f, gestures.clickX(MouseButton.LEFT));
        assertFalse(gestures.doubleClicked(MouseButton.LEFT));
        frame();
        assertFalse(gestures.clicked(MouseButton.LEFT), "a click lasts one frame");
    }

    @Test
    void doubleClicksUseTheOsClickCount() {
        ev.button(MouseButton.RIGHT, true, 1, 100, 100)
                .button(MouseButton.RIGHT, false, 1, 100, 100);
        frame();
        assertTrue(gestures.commandClicked());
        ev.button(MouseButton.RIGHT, true, 2, 100, 100)
                .button(MouseButton.RIGHT, false, 2, 100, 100);
        frame();
        assertTrue(gestures.doubleClicked(MouseButton.RIGHT));
    }

    @Test
    void draggingTheSelectButtonIsABoxSelection() {
        ev.button(MouseButton.LEFT, true, 1, 100, 100);
        frame();
        ev.move(300, 250);
        frame();
        assertTrue(gestures.isBoxSelecting());
        assertEquals(100f, gestures.boxMinX());
        assertEquals(300f, gestures.boxMaxX());
        assertEquals(250f, gestures.boxMaxY());
        ev.move(50, 40);
        frame();
        assertEquals(50f, gestures.boxMinX(), "the box follows in any direction");
        assertEquals(100f, gestures.boxMaxX());
        ev.button(MouseButton.LEFT, false, 1, 50, 40);
        frame();
        assertTrue(gestures.boxFinished());
        assertTrue(gestures.dragEnded(MouseButton.LEFT));
        assertFalse(gestures.clicked(MouseButton.LEFT), "a drag is not a click");
        assertFalse(gestures.isBoxSelecting());
    }

    @Test
    void edgesScrollOnlyWithFocusAndRampUp() {
        ev.move(0, 360);
        frame();
        assertEquals(-1f, gestures.edgeScrollX());
        assertEquals(0f, gestures.edgeScrollY());
        ev.move(1280 - 6, 715);
        frame();
        assertEquals(0.5f, gestures.edgeScrollX(), 1e-4f);
        assertTrue(gestures.edgeScrollY() > 0);
        ev.focus(false);
        frame();
        assertEquals(0f, gestures.edgeScrollX(), "no scrolling without focus");
        ev.focus(true);
        gestures.edgeScroll(false);
        frame();
        assertEquals(0f, gestures.edgeScrollX());
    }

    @Test
    void cameraRotateAndZoom() {
        ev.button(MouseButton.MIDDLE, true, 1, 640, 360);
        frame();
        ev.move(700, 360);
        frame();
        assertEquals(60f, gestures.rotateDelta());
        ev.wheel(-1);
        frame();
        assertEquals(-1f, gestures.zoomDelta());
        assertEquals(0f, gestures.rotateDelta());
    }

    @Test
    void virtualCursorDrivesGesturesFromAGamepad() {
        VirtualCursor cursor = new VirtualCursor().speed(1000);
        gestures.virtualCursor(cursor);
        cursor.warpTo(640, 360);
        ev.padAdded(0).padAxis(0, GamepadAxis.LEFT_X, 1f);
        ev.frame();
        cursor.update(ev.input, 0.1f);
        gestures.update(ev.input);
        assertTrue(cursor.isActive(ev.input));
        assertTrue(cursor.moved());
        assertEquals(740f, cursor.x(), 1f, "full tilt for 0.1 s at 1000 units/s");
        assertEquals(cursor.x(), gestures.x());

        ev.padAxis(0, GamepadAxis.LEFT_X, 0f).padButton(0, GamepadButton.SOUTH, true);
        ev.frame();
        cursor.update(ev.input, 0.1f);
        gestures.update(ev.input);
        ev.padButton(0, GamepadButton.SOUTH, false);
        ev.frame();
        cursor.update(ev.input, 0.1f);
        gestures.update(ev.input);
        assertTrue(gestures.clicked(MouseButton.LEFT), "South acts as the left button");
        assertEquals(cursor.x(), gestures.clickX(MouseButton.LEFT));

        ev.move(10, 10);
        ev.frame();
        cursor.update(ev.input, 0.1f);
        gestures.update(ev.input);
        assertFalse(cursor.isActive(ev.input), "the mouse takes over again");
        assertEquals(10f, gestures.x());
    }

    @Test
    void gesturesAllocateNothing() {
        VirtualCursor cursor = new VirtualCursor();
        gestures.virtualCursor(cursor);
        Allocations.assertNoAllocation(
                "CrpgGestures.update",
                () -> {
                    ev.button(MouseButton.LEFT, true, 1, 100, 100).move(200, 200).wheel(1);
                    ev.frame();
                    cursor.update(ev.input, 0.016f);
                    gestures.update(ev.input);
                    ev.button(MouseButton.LEFT, false, 1, 200, 200);
                    ev.frame();
                    gestures.update(ev.input);
                });
    }
}
