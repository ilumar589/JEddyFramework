package dev.jeddy.window;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

/** The game-to-main command queue, window commands and cursors (TASKS M2-07, M2-10). */
class MainThreadQueueTest {
    @BeforeAll
    static void init() {
        Platform.init("Jeddy queue tests", "0.1", "dev.jeddy.tests.queue");
    }

    @AfterAll
    static void shutdown() {
        Platform.shutdown();
    }

    private static Window headless() {
        return Window.create(
                new WindowConfig().openGl(false).hidden(true).size(640, 360).minimumSize(100, 100));
    }

    private long sum;

    @Test
    void requestsFromTheGameThreadRunOnTheMainThread() throws Exception {
        MainThreadQueue queue = new MainThreadQueue(64);
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        queue.addHandler(
                (op, a, b, c, d, e) -> {
                    if (op != 200) {
                        return false;
                    }
                    ranOn.set(Thread.currentThread());
                    sum += a + b + c + d + e;
                    return true;
                });
        Thread game = new Thread(() -> queue.post(200, 1, 2, 3, 4, 5), "game");
        game.start();
        game.join();
        assertEquals(1, queue.pending());
        assertEquals(1, queue.drain());
        assertEquals(Thread.currentThread(), ranOn.get());
        assertEquals(15, sum);

        queue.post(999);
        queue.drain();
        assertEquals(1, queue.unhandled());

        Thread other = new Thread(() -> assertThrows(IllegalStateException.class, queue::drain));
        other.start();
        other.join();
    }

    @Test
    void fullQueueDropsAndCounts() {
        MainThreadQueue queue = new MainThreadQueue(2);
        int accepted = 0;
        for (int i = 0; i < 10; i++) {
            accepted += queue.post(1, i) ? 1 : 0;
        }
        assertTrue(accepted >= 2 && accepted < 10, "accepted " + accepted);
        assertEquals(10 - accepted, queue.dropped());
    }

    @Test
    void postingAndDrainingAllocateNothing() {
        MainThreadQueue queue = new MainThreadQueue(64);
        queue.addHandler(
                (op, a, b, c, d, e) -> {
                    sum += a;
                    return true;
                });
        Allocations.assertNoAllocation(
                "MainThreadQueue post + drain",
                () -> {
                    queue.post(300, 1, 2, 3, 4, 5);
                    queue.post(301, 7);
                    queue.drain();
                });
    }

    @Test
    void windowCommandsDriveTheWindow() {
        try (Window window = headless();
                Cursors cursors = new Cursors()) {
            MainThreadQueue queue = new MainThreadQueue(64);
            queue.addHandler(new WindowCommands(window, cursors));
            int crosshair = cursors.registerSystem(Cursors.SystemCursor.CROSSHAIR);

            WindowCommands.postStartTextInput(queue, 10, 20, 200, 30, 5);
            WindowCommands.postSetCursor(queue, crosshair);
            WindowCommands.postShowCursor(queue, false);
            WindowCommands.postMouseGrab(queue, true);
            WindowCommands.postWarpMouse(queue, 100.5f, 50f);
            assertEquals(5, queue.drain());
            assertEquals(0, queue.unhandled());
            assertTrue(window.isTextInputActive());
            assertEquals(crosshair, cursors.current());
            assertFalse(cursors.isVisible());
            assertTrue(window.isMouseGrabbed());

            WindowCommands.postStopTextInput(queue);
            WindowCommands.postSetCursor(queue, Cursors.DEFAULT);
            WindowCommands.postSetCursor(queue, 999); // unknown IDs are ignored
            WindowCommands.postShowCursor(queue, true);
            WindowCommands.postWindowMode(queue, WindowMode.BORDERLESS, 0, 0, 0);
            WindowCommands.postVSync(queue, VSync.OFF);
            WindowCommands.postRelativeMouse(queue, false);
            assertEquals(7, queue.drain());
            assertFalse(window.isTextInputActive());
            assertEquals(Cursors.DEFAULT, cursors.current());
            assertTrue(cursors.isVisible());
            assertEquals(WindowMode.BORDERLESS, window.mode());
        }
    }

    @Test
    void colorCursorsAreSwitchedById() {
        try (Cursors cursors = new Cursors()) {
            byte[] rgba = new byte[32 * 32 * 4];
            for (int i = 3; i < rgba.length; i += 4) {
                rgba[i] = (byte) 0xFF;
            }
            int attack = cursors.registerColor(32, 32, rgba, 2, 2);
            int talk = cursors.registerColor(32, 32, rgba, 16, 16);
            assertEquals(3, cursors.count());
            cursors.set(attack);
            assertEquals(attack, cursors.current());
            Allocations.assertNoAllocation(
                    "Cursors.set",
                    () -> {
                        cursors.set(talk);
                        cursors.set(attack);
                    });
            assertThrows(IllegalArgumentException.class, () -> cursors.set(42));
        }
    }
}
