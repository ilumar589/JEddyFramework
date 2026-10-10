package dev.jeddy.render;

import dev.jeddy.gl.GLCapabilities;
import dev.jeddy.gl.GLDebug;
import dev.jeddy.gl.GLLoader;
import dev.jeddy.window.Platform;
import dev.jeddy.window.VSync;
import dev.jeddy.window.Window;
import dev.jeddy.window.WindowConfig;

/**
 * A hidden window with a GL 4.6 debug context for {@code @Tag("gpu")} tests. CI runs them under
 * Xvfb with Mesa llvmpipe (DESIGN §9.7).
 */
public final class GpuTestContext implements AutoCloseable {
    private final Window window;
    private final GLCapabilities caps;

    /** Opens a {@code size}×{@code size} hidden window and loads GL. */
    public GpuTestContext(String name, int size) {
        System.setProperty("jeddy.gl.critical", "false"); // the debug callback needs it off
        Platform.init(name, "0.1", "dev.jeddy.tests." + name.replace(' ', '-'));
        window =
                Window.create(
                        new WindowConfig()
                                .hidden(true)
                                .size(size, size)
                                .minimumSize(16, 16)
                                .glDebug(true)
                                .vsync(VSync.OFF));
        caps = GLLoader.init(window::glProcAddress);
        GLDebug.enable(true);
    }

    /** Returns the window. */
    public Window window() {
        return window;
    }

    /** Returns the context capabilities. */
    public GLCapabilities caps() {
        return caps;
    }

    @Override
    public void close() {
        window.close();
        Platform.shutdown();
    }
}
