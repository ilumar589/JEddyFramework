package dev.jeddy.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.core.concurrent.SpscIntRing;
import dev.jeddy.sdl.SdlEventType;
import dev.jeddy.sdl.ffi.SDL3;
import dev.jeddy.sdl.ffi.SDL_Event;
import dev.jeddy.sdl.ffi.SDL_KeyboardEvent;
import dev.jeddy.sdl.ffi.SDL_MouseButtonEvent;
import dev.jeddy.sdl.ffi.SDL_MouseMotionEvent;
import dev.jeddy.sdl.ffi.SDL_MouseWheelEvent;
import dev.jeddy.sdl.ffi.SDL_TextInputEvent;
import dev.jeddy.sdl.ffi.SDL_WindowEvent;
import dev.jeddy.testing.Allocations;
import dev.jeddy.window.MainThreadQueue;
import dev.jeddy.window.Platform;
import dev.jeddy.window.Window;
import dev.jeddy.window.WindowConfig;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

/**
 * Pumps synthetic SDL events (pushed with {@code SDL_PushEvent} under the dummy video driver)
 * through {@link EventPump} into {@link Input} (TASKS M2-04, M2-05).
 */
class EventPumpTest {
    private static final Arena ARENA = Arena.ofShared();

    private Window window;
    private SpscIntRing ring;
    private EventPump pump;
    private Input input;
    private final MemorySegment ev = ARENA.allocate(SDL_Event.layout());
    private final MemorySegment typed = ARENA.allocateFrom("hé€😀", StandardCharsets.UTF_8);

    @BeforeAll
    static void init() {
        Platform.init("Jeddy input tests", "0.1", "dev.jeddy.tests.input");
    }

    @AfterAll
    static void shutdown() {
        Platform.shutdown();
    }

    @BeforeEach
    void open() {
        window =
                Window.create(
                        new WindowConfig()
                                .openGl(false)
                                .hidden(true)
                                .size(800, 600)
                                .minimumSize(100, 100));
        ring = new SpscIntRing(4096 * EventPump.recordInts());
        pump = new EventPump(window, ring);
        input = new Input(ring, window.width(), window.height());
        drainAll();
    }

    @AfterEach
    void close() {
        pump.close();
        window.close();
    }

    /** Throws away whatever SDL queued while the window was created. */
    private void drainAll() {
        pump.pump();
        input.update();
    }

    private void push() {
        assertTrue(SDL3.SDL_PushEvent(ev), "SDL_PushEvent");
    }

    private void key(int scancode, boolean down, boolean repeat, int sdlMods) {
        ev.fill((byte) 0);
        SDL_KeyboardEvent.type(ev, down ? SdlEventType.KEY_DOWN : SdlEventType.KEY_UP);
        SDL_KeyboardEvent.windowID(ev, window.id());
        SDL_KeyboardEvent.scancode(ev, scancode);
        SDL_KeyboardEvent.mod(ev, (short) sdlMods);
        SDL_KeyboardEvent.down(ev, down);
        SDL_KeyboardEvent.repeat(ev, repeat);
        push();
    }

    private void motion(float x, float y, float dx, float dy) {
        ev.fill((byte) 0);
        SDL_MouseMotionEvent.type(ev, SdlEventType.MOUSE_MOTION);
        SDL_MouseMotionEvent.windowID(ev, window.id());
        SDL_MouseMotionEvent.x(ev, x);
        SDL_MouseMotionEvent.y(ev, y);
        SDL_MouseMotionEvent.xrel(ev, dx);
        SDL_MouseMotionEvent.yrel(ev, dy);
        push();
    }

    private void button(int button, boolean down, int clicks, float x, float y) {
        ev.fill((byte) 0);
        SDL_MouseButtonEvent.type(
                ev, down ? SdlEventType.MOUSE_BUTTON_DOWN : SdlEventType.MOUSE_BUTTON_UP);
        SDL_MouseButtonEvent.windowID(ev, window.id());
        SDL_MouseButtonEvent.button(ev, (byte) button);
        SDL_MouseButtonEvent.down(ev, down);
        SDL_MouseButtonEvent.clicks(ev, (byte) clicks);
        SDL_MouseButtonEvent.x(ev, x);
        SDL_MouseButtonEvent.y(ev, y);
        push();
    }

    private void wheel(float y, boolean flipped) {
        ev.fill((byte) 0);
        SDL_MouseWheelEvent.type(ev, SdlEventType.MOUSE_WHEEL);
        SDL_MouseWheelEvent.windowID(ev, window.id());
        SDL_MouseWheelEvent.y(ev, y);
        SDL_MouseWheelEvent.direction(ev, flipped ? SDL3.SDL_MOUSEWHEEL_FLIPPED() : 0);
        SDL_MouseWheelEvent.mouse_x(ev, 10);
        SDL_MouseWheelEvent.mouse_y(ev, 20);
        push();
    }

    private void text() {
        ev.fill((byte) 0);
        SDL_TextInputEvent.type(ev, SdlEventType.TEXT_INPUT);
        SDL_TextInputEvent.windowID(ev, window.id());
        SDL_TextInputEvent.text(ev, typed);
        push();
    }

    private void windowEvent(int type, int data1, int data2) {
        ev.fill((byte) 0);
        SDL_WindowEvent.type(ev, type);
        SDL_WindowEvent.windowID(ev, window.id());
        SDL_WindowEvent.data1(ev, data1);
        SDL_WindowEvent.data2(ev, data2);
        push();
    }

    @Test
    void keysReportEdgesByFrame() {
        key(Scancode.W, true, false, 0x0001); // with left shift held
        pump.pump();
        input.update();
        assertTrue(input.isKeyDown(Scancode.W));
        assertTrue(input.wasKeyPressed(Scancode.W));
        assertEquals(Modifiers.SHIFT, input.modifiers());
        assertEquals(1, input.keyEventCount());
        assertEquals(Scancode.W, input.keyEventScancode(0));
        assertTrue(input.keyEventDown(0));

        key(Scancode.W, true, true, 0); // OS repeat: no new press edge
        pump.pump();
        input.update();
        assertTrue(input.isKeyDown(Scancode.W));
        assertFalse(input.wasKeyPressed(Scancode.W));
        assertTrue(input.wasKeyPressedOrRepeated(Scancode.W));
        assertTrue(input.keyEventRepeat(0));

        // Pressed and released within one frame: both edges are visible.
        key(Scancode.SPACE, true, false, 0);
        key(Scancode.SPACE, false, false, 0);
        key(Scancode.W, false, false, 0);
        pump.pump();
        input.update();
        assertTrue(input.wasKeyPressed(Scancode.SPACE));
        assertTrue(input.wasKeyReleased(Scancode.SPACE));
        assertFalse(input.isKeyDown(Scancode.SPACE));
        assertTrue(input.wasKeyReleased(Scancode.W));

        input.update();
        assertFalse(input.wasKeyReleased(Scancode.W), "edges last one frame");
    }

    @Test
    void textIsDecodedToCodePoints() {
        text();
        pump.pump();
        input.update();
        StringBuilder sb = new StringBuilder();
        input.appendText(sb);
        assertEquals("hé€😀", sb.toString());
        assertEquals(4, input.textLength());
        assertEquals(0x1F600, input.textCodePoint(3));
    }

    @Test
    void mouseMotionButtonsAndWheel() {
        motion(100, 50, 3, -2);
        motion(110, 55, 10, 5);
        button(MouseButton.LEFT, true, 2, 110, 55);
        wheel(1, false);
        wheel(1, true);
        pump.pump();
        input.update();
        assertEquals(10f, input.mouseX());
        assertEquals(20f, input.mouseY(), "the wheel event carries the cursor position");
        assertEquals(13f, input.mouseDeltaX());
        assertEquals(3f, input.mouseDeltaY());
        assertTrue(input.isMouseDown(MouseButton.LEFT));
        assertTrue(input.wasMousePressed(MouseButton.LEFT));
        assertEquals(2, input.mouseClicks(MouseButton.LEFT));
        assertEquals(110f, input.mousePressX(MouseButton.LEFT));
        assertEquals(0f, input.wheelY(), "a flipped wheel event is normalized");

        button(MouseButton.LEFT, false, 2, 111, 56);
        pump.pump();
        input.update();
        assertTrue(input.wasMouseReleased(MouseButton.LEFT));
        assertFalse(input.isMouseDown(MouseButton.LEFT));
        assertEquals(0f, input.mouseDeltaX(), "deltas reset each frame");
    }

    @Test
    void windowEventsReachBothThreads() {
        windowEvent(SdlEventType.WINDOW_RESIZED, 1280, 720);
        windowEvent(SdlEventType.WINDOW_PIXEL_SIZE_CHANGED, 2560, 1440);
        pump.pump();
        assertEquals(1280, window.width(), "main thread state");
        assertEquals(2560, window.pixelWidth());
        int generation = input.windowGeneration();
        input.update();
        assertEquals(1280, input.windowWidth(), "game thread state");
        assertEquals(720, input.windowHeight());
        assertEquals(2560, input.pixelWidth());
        assertTrue(input.windowGeneration() != generation);

        key(Scancode.A, true, false, 0);
        button(MouseButton.RIGHT, true, 1, 1, 1);
        windowEvent(SdlEventType.WINDOW_FOCUS_LOST, 0, 0);
        pump.pump();
        input.update();
        assertFalse(input.isFocused());
        assertFalse(input.isKeyDown(Scancode.A), "focus loss releases held keys");
        assertFalse(input.isMouseDown(MouseButton.RIGHT));
    }

    @Test
    void quitIsReported() {
        ev.fill((byte) 0);
        SDL_Event.type(ev, SdlEventType.QUIT);
        push();
        pump.pump();
        assertTrue(pump.isQuitRequested());
        input.update();
        assertTrue(input.isQuitRequested());
        input.clearQuitRequest();
        assertFalse(input.isQuitRequested());
    }

    @Test
    void keyLabelsFollowTheLayout() {
        String label = pump.keyLabels().label(Scancode.A);
        assertFalse(label.isEmpty());
        assertEquals("", pump.keyLabels().label(0));
    }

    @Test
    void gamepadRequestsForEmptySlotsAreIgnored() {
        MainThreadQueue queue = new MainThreadQueue(16);
        queue.addHandler(pump);
        queue.post(EventPump.OP_RUMBLE, 0, 0xFFFF, 0xFFFF, 100, 0);
        queue.post(EventPump.OP_SET_LED, 3, 0xFF0000, 0, 0, 0);
        assertEquals(2, queue.drain());
        assertEquals(0, queue.unhandled());
    }

    @Test
    void pumpingMouseAndTypingAllocatesNothing() {
        // TASKS M2-04 AC: 0 B/frame while moving the mouse and typing.
        Allocations.assertNoAllocation(
                "EventPump.pump + Input.update",
                20,
                200,
                () -> {
                    motion(1, 2, 1, 1);
                    motion(2, 3, 1, 1);
                    key(Scancode.E, true, false, 0);
                    text();
                    key(Scancode.E, false, false, 0);
                    button(MouseButton.LEFT, true, 1, 2, 3);
                    button(MouseButton.LEFT, false, 1, 2, 3);
                    wheel(1, false);
                    pump.pump();
                    input.update();
                });
        assertEquals(0, pump.dropped());
    }
}
