package dev.jeddy.window;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.sdl.SdlEventType;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless window tests. CI sets {@code SDL_VIDEO_DRIVER=dummy}, which supports windows but not
 * OpenGL; GL contexts are covered by {@link WindowGlTest} (tag {@code gpu}).
 */
class WindowTest {
    @BeforeAll
    static void init() {
        Platform.init("Jeddy tests", "0.1", "dev.jeddy.tests");
    }

    @AfterAll
    static void shutdown() {
        Platform.shutdown();
    }

    private static Window headless(int w, int h) {
        return Window.create(
                new WindowConfig().openGl(false).hidden(true).size(w, h).minimumSize(160, 100));
    }

    @Test
    void displaysAreEnumerated() {
        List<DisplayInfo> displays = Platform.displays();
        assertFalse(displays.isEmpty());
        DisplayInfo primary = displays.getFirst();
        assertTrue(primary.primary());
        assertEquals(Platform.primaryDisplay(), primary.id());
        assertTrue(primary.width() > 0 && primary.height() > 0, primary.toString());
        assertTrue(primary.contentScale() > 0);
        assertNotNull(Platform.videoDriver());
    }

    @Test
    void windowReportsItsSize() {
        try (Window window = headless(640, 360)) {
            assertEquals(640, window.width());
            assertEquals(360, window.height());
            assertTrue(window.pixelWidth() >= 640);
            assertTrue(window.displayScale() > 0);
            assertFalse(window.hasGlContext());
            assertTrue(window.id() != 0);
            assertEquals(WindowMode.WINDOWED, window.mode());
            assertFalse(window.isCloseRequested());
        }
    }

    @Test
    void resizingUpdatesTheCachedSize() {
        try (Window window = headless(640, 360)) {
            int generation = window.sizeGeneration();
            window.setSize(1024, 576);
            assertEquals(1024, window.width());
            assertEquals(576, window.height());
            assertTrue(window.sizeGeneration() != generation);
        }
    }

    @Test
    void windowEventsUpdateStateWithoutAllocating() {
        try (Window window = headless(640, 360)) {
            int generation = window.sizeGeneration();
            window.onWindowEvent(SdlEventType.WINDOW_RESIZED, 800, 450);
            window.onWindowEvent(SdlEventType.WINDOW_PIXEL_SIZE_CHANGED, 1600, 900);
            assertEquals(800, window.width());
            assertEquals(1600, window.pixelWidth());
            assertEquals(900, window.pixelHeight());
            assertEquals(generation + 2, window.sizeGeneration());

            window.onWindowEvent(SdlEventType.WINDOW_MINIMIZED, 0, 0);
            assertTrue(window.isMinimized());
            assertTrue(window.shouldSkipRendering());
            window.onWindowEvent(SdlEventType.WINDOW_RESTORED, 0, 0);
            assertFalse(window.isMinimized());
            assertFalse(window.shouldSkipRendering());

            window.onWindowEvent(SdlEventType.WINDOW_CLOSE_REQUESTED, 0, 0);
            assertTrue(window.isCloseRequested());
            window.clearCloseRequest();
            assertFalse(window.isCloseRequested());

            window.onWindowEvent(SdlEventType.WINDOW_FOCUS_GAINED, 0, 0);
            assertTrue(window.isFocused());
            window.onWindowEvent(SdlEventType.WINDOW_FOCUS_LOST, 0, 0);
            assertFalse(window.isFocused());

            Allocations.assertNoAllocation(
                    "Window.onWindowEvent",
                    () -> {
                        window.onWindowEvent(SdlEventType.WINDOW_RESIZED, 800, 450);
                        window.onWindowEvent(SdlEventType.WINDOW_FOCUS_GAINED, 0, 0);
                        window.onWindowEvent(SdlEventType.WINDOW_MOVED, 10, 10);
                    });
        }
    }

    @Test
    void fullscreenModesCanBeToggled() {
        try (Window window = headless(640, 360)) {
            window.setMode(WindowMode.BORDERLESS);
            assertEquals(WindowMode.BORDERLESS, window.mode());
            window.setMode(WindowMode.EXCLUSIVE);
            // Falls back to borderless where the display lists no modes (SDL's dummy driver).
            boolean hasModes = !Platform.displays().getFirst().fullscreenModes().isEmpty();
            assertEquals(hasModes ? WindowMode.EXCLUSIVE : WindowMode.BORDERLESS, window.mode());
            window.setMode(WindowMode.WINDOWED);
            assertEquals(WindowMode.WINDOWED, window.mode());
            assertFalse(window.isFullscreen());
        }
    }

    @Test
    void iconAndTitleCanBeSet() {
        try (Window window = headless(320, 200)) {
            window.setTitle("Jeddy – Ünïcode title");
            byte[] icon = new byte[16 * 16 * 4];
            java.util.Arrays.fill(icon, (byte) 0x7f);
            window.setIcon(16, 16, icon); // the dummy driver has no icons; must not throw
            assertThrows(IllegalArgumentException.class, () -> window.setIcon(32, 32, icon));
        }
    }

    @Test
    void mainThreadOnlyCallsFailElsewhere() throws Exception {
        try (Window window = headless(320, 200)) {
            AtomicReference<Throwable> error = new AtomicReference<>();
            Thread game =
                    new Thread(
                            () -> {
                                try {
                                    window.setSize(100, 100);
                                } catch (Throwable t) {
                                    error.set(t);
                                }
                            },
                            "game");
            game.start();
            game.join();
            assertTrue(error.get() instanceof IllegalStateException, String.valueOf(error.get()));
            assertEquals(320, window.width());
        }
    }

    @Test
    void frameLimiterHoldsTheRate() {
        FrameLimiter limiter = new FrameLimiter(200);
        assertEquals(5_000_000, limiter.periodNanos());
        long start = System.nanoTime();
        for (int i = 0; i < 21; i++) {
            limiter.waitForNextFrame();
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(
                elapsedMs >= 95 && elapsedMs < 400,
                "20 frames at 200 FPS took " + elapsedMs + " ms");
        limiter.setTargetFps(0);
        assertEquals(0, limiter.waitForNextFrame());
    }
}
