package dev.jeddy.editortool;

import static dev.jeddy.gl.ffi.GL46.GL_RGBA;
import static dev.jeddy.gl.ffi.GL46.GL_UNSIGNED_BYTE;
import static dev.jeddy.gl.ffi.GL46.glFinish;
import static dev.jeddy.gl.ffi.GL46.glReadPixels;

import dev.jeddy.app.FrameRenderer;
import dev.jeddy.app.MainContext;
import dev.jeddy.app.world.WorldRenderer;
import dev.jeddy.asset.Vfs;
import dev.jeddy.core.log.Log;
import dev.jeddy.core.log.Logger;
import dev.jeddy.render.RenderPackets;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;

/**
 * The main-thread side of the tool: a {@link WorldRenderer} drawing the editor's packets (scene,
 * debug lines, UI), and a PNG screenshot of the last frame for smoke runs.
 *
 * <p>Thread ownership: the main thread; {@link #world()} is read by the game thread after {@link
 * #init}.
 */
final class EditorRenderer implements FrameRenderer {
    private static final Logger LOG = Log.logger("editor-tool");

    private final WorldRenderer world;
    private volatile boolean screenshotRequested;
    private final Path screenshot;

    EditorRenderer(Vfs vfs, RenderPackets packets, String environment, Path screenshot) {
        WorldRenderer.Config config = new WorldRenderer.Config().hud((o, p, i, w, h) -> {}, 1024);
        if (environment != null && !environment.isEmpty()) {
            config.environment(environment);
        }
        world = new WorldRenderer(vfs, packets, config);
        this.screenshot = screenshot;
    }

    WorldRenderer world() {
        return world;
    }

    /** Asks for the screenshot file given at construction to be written after the next frame. */
    void requestScreenshot() {
        screenshotRequested = true;
    }

    @Override
    public void init(MainContext ctx) {
        world.init(ctx);
    }

    @Override
    public void render(MainContext ctx, int index, boolean fresh) {
        world.render(ctx, index, fresh);
        if (screenshotRequested && fresh && screenshot != null) {
            screenshotRequested = false;
            capture(ctx);
        }
    }

    private void capture(MainContext ctx) {
        int w = ctx.window().pixelWidth();
        int h = ctx.window().pixelHeight();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pixels = arena.allocate((long) w * h * 4);
            glFinish();
            glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            Png.write(screenshot, pixels, w, h);
            LOG.info("screenshot " + screenshot.toAbsolutePath());
        } catch (IOException e) {
            LOG.warn("screenshot failed", e);
        }
    }

    @Override
    public void shutdown(MainContext ctx) {
        world.shutdown(ctx);
    }
}
