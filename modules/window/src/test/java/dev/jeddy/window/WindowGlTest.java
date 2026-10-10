package dev.jeddy.window;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Needs a GL 4.6 driver: run with {@code -Pjeddy.gpuTests=true} (CI runs it under Xvfb and Mesa
 * llvmpipe).
 */
@Tag("gpu")
class WindowGlTest {
    @BeforeAll
    static void init() {
        Platform.init("Jeddy GL tests", "0.1", "dev.jeddy.tests.gl");
    }

    @AfterAll
    static void shutdown() {
        Platform.shutdown();
    }

    @Test
    void createsAGl46CoreContext() {
        try (Window window =
                Window.create(new WindowConfig().hidden(true).size(320, 240).vsync(VSync.OFF))) {
            assertTrue(window.hasGlContext());
            assertTrue(window.glProcAddress("glClear").address() != 0);
            assertTrue(window.glProcAddress("glMultiDrawElementsIndirectCount").address() != 0);
            assertEquals(VSync.OFF, window.vsync());
            window.makeCurrent();
            Allocations.assertNoAllocation("Window.swap", 5, 50, window::swap);
        }
    }
}
