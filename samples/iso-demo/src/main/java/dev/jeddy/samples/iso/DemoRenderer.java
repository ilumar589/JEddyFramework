package dev.jeddy.samples.iso;

import static dev.jeddy.gl.ffi.GL46.GL_RGBA;
import static dev.jeddy.gl.ffi.GL46.GL_UNSIGNED_BYTE;
import static dev.jeddy.gl.ffi.GL46.glFinish;
import static dev.jeddy.gl.ffi.GL46.glReadPixels;

import dev.jeddy.app.FrameRenderer;
import dev.jeddy.app.MainContext;
import dev.jeddy.app.world.WorldRenderer;
import dev.jeddy.asset.AssetManager;
import dev.jeddy.asset.AssetType;
import dev.jeddy.asset.Vfs;
import dev.jeddy.audio.Audio;
import dev.jeddy.audio.AudioConfig;
import dev.jeddy.core.log.Log;
import dev.jeddy.core.log.Logger;
import dev.jeddy.core.profile.FrameStats;
import dev.jeddy.render.RenderPacket;
import dev.jeddy.render.RenderPackets;
import dev.jeddy.render.SceneRenderer;
import dev.jeddy.render.debug.DebugDraw;
import dev.jeddy.render.debug.Overlay;
import dev.jeddy.render.debug.ProfilerOverlay;
import dev.jeddy.render.material.GpuTexture;
import dev.jeddy.render.mesh.GpuMesh;
import dev.jeddy.render.mesh.MeshAssets;
import dev.jeddy.render.post.PostProcess;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;

/**
 * The iso demo's main thread: the {@link WorldRenderer} plus the HUD, the profiler overlay,
 * screenshots and the main-thread half of the perf capture. Also opens and closes the audio device,
 * which SDL_mixer wants on the main thread.
 *
 * <p>Thread ownership: the main thread ({@link FrameRenderer} callbacks); {@link #audio()} is read
 * by the game thread after {@link #init} (which happens before the game thread starts).
 */
final class DemoRenderer implements FrameRenderer, WorldRenderer.Hud {
    private static final Logger LOG = Log.logger("iso-demo");

    /** Auto exposure that keeps a crypt dark: a low key, and it brightens at most 2^5. */
    private static final PostProcess.AutoExposure CRYPT_EXPOSURE =
            new PostProcess.AutoExposure(-5f, 10f, 0.4f, 0.95f, 0.1f, 2f, 1f);

    private final DemoOptions options;
    private final DemoHud hud;
    private final PerfCapture perf;
    private final Vfs vfs;
    private final WorldRenderer world;
    private AssetManager headlessAssets;
    private AssetType<GpuMesh> headlessMeshes;
    private DebugDraw headlessDebug;
    private final FrameStats mainStats = new FrameStats(120);
    private volatile double mainMs;
    private final FrameStats gameStats = new FrameStats(120);
    private ProfilerOverlay profiler;
    private Audio audio;
    private String gpu = "unknown";
    private int screenshots;
    private volatile boolean firstFrameShown;

    DemoRenderer(
            Vfs vfs, RenderPackets packets, DemoOptions options, DemoHud hud, PerfCapture perf) {
        this.options = options;
        this.hud = hud;
        this.perf = perf;
        this.vfs = vfs;
        this.world =
                new WorldRenderer(
                        vfs,
                        packets,
                        new WorldRenderer.Config()
                                .scene(
                                        SceneRenderer.Config.DEFAULT.withPost(
                                                PostProcess.Config.DEFAULT.withAutoExposure(
                                                        CRYPT_EXPOSURE)))
                                .environment("env/sky.ktx2")
                                .hud(this, 16384));
    }

    /** Returns the virtual file system of cooked assets (the editor's asset browser). */
    Vfs vfs() {
        return vfs;
    }

    /** The asset manager (the game thread owns it). */
    AssetManager assets() {
        return options.headless ? headlessAssets : world.assets();
    }

    /** The mesh asset type: uploaded meshes, or CPU-only ones when headless. */
    AssetType<GpuMesh> meshes() {
        return options.headless ? headlessMeshes : world.meshes();
    }

    /** The texture asset type for UI atlases, or {@code null} when headless. */
    AssetType<GpuTexture> textures() {
        return options.headless ? null : world.materials().textures();
    }

    /** The main thread's average frame time in milliseconds (any thread; the UI shows it). */
    double mainMs() {
        return mainMs;
    }

    /** Debug lines the game thread draws into. */
    DebugDraw debugDraw() {
        return options.headless ? headlessDebug : world.debugDraw();
    }

    /** The audio system, or {@code null} without a device (or with {@code --no-audio}). */
    Audio audio() {
        return audio;
    }

    /** Whether a frame was presented (startup measurement). Any thread. */
    boolean firstFrameShown() {
        return firstFrameShown;
    }

    /** Logs the time from JVM start to the first frame (cold start, DESIGN §14), once. */
    private void firstFrame() {
        long jvmStart = ManagementFactory.getRuntimeMXBean().getStartTime();
        long ms = System.currentTimeMillis() - jvmStart;
        LOG.info("startup: first frame " + ms + " ms after JVM start");
        System.out.println("startup: first frame " + ms + " ms after JVM start");
        firstFrameShown = true;
    }

    /** The GL renderer string, for reports. */
    String gpu() {
        return gpu;
    }

    @Override
    public void init(MainContext ctx) {
        perf.setMainThread(Thread.currentThread().threadId());
        if (options.headless) {
            // No GPU: the game thread runs on CPU-only meshes; nothing is drawn.
            headlessAssets = new AssetManager(vfs, ctx.jobs());
            headlessMeshes = MeshAssets.registerHeadless(headlessAssets);
            headlessDebug = new DebugDraw(ctx.jobs().threadSlots(), 1 << 12);
            gpu = "none (headless)";
            openAudio();
            return;
        }
        world.init(ctx);
        gpu = world.caps().renderer() + " (GL " + world.caps().version() + ")";
        profiler = new ProfilerOverlay(world.overlay());
        profiler.setScale(ctx.window().pixelHeight() < 800 ? 1 : 1.5f);
        openAudio();
    }

    private void openAudio() {
        if (options.audio) {
            try {
                audio = Audio.open(new AudioConfig().voices(32, 128));
            } catch (RuntimeException | LinkageError e) {
                LOG.warn("audio disabled: " + e);
                audio = null;
            }
        }
    }

    @Override
    public void render(MainContext ctx, int index, boolean fresh) {
        if (!firstFrameShown) {
            if (!options.headless) {
                world.render(ctx, index, fresh);
            }
            firstFrame();
            return;
        }
        if (options.headless) {
            if (fresh) {
                gameStats.record(hud.gameNanos[index]);
                if (hud.measuring[index]) {
                    perf.recordMainAllocation();
                }
            }
            return;
        }
        world.render(ctx, index, fresh);
        long nanos = world.lastFrameNanos();
        mainStats.record(nanos);
        mainMs = mainStats.averageMs();
        if (fresh) {
            gameStats.record(hud.gameNanos[index]);
        }
        if (fresh && hud.measuring[index]) {
            perf.recordMain(nanos, world.scene().graph());
            perf.recordMainAllocation();
        }
        if (fresh && hud.screenshot[index]) {
            capture(ctx);
        }
    }

    private void capture(MainContext ctx) {
        int w = ctx.window().pixelWidth();
        int h = ctx.window().pixelHeight();
        Path file =
                options.screenshot != null && screenshots == 0
                        ? options.screenshot
                        : Path.of("build", "screenshots", "iso-demo-" + screenshots + ".png");
        screenshots++;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pixels = arena.allocate((long) w * h * 4);
            glFinish();
            glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            Screenshot.write(file, pixels, w, h);
            LOG.info("screenshot " + file.toAbsolutePath());
        } catch (IOException e) {
            LOG.warn("screenshot failed", e);
        }
    }

    @Override
    public void draw(Overlay o, RenderPacket p, int i, int width, int height) {
        // The HUD moved to the UI module (M13); the overlay keeps the profiler, which works
        // before any asset is loaded (D31).
        if (hud.profiler[i]) {
            profiler.queue(
                    mainStats,
                    gameStats,
                    world.scene().graph(),
                    world.scene().draws().commandCount());
        }
    }

    @Override
    public void shutdown(MainContext ctx) {
        if (options.headless) {
            if (headlessAssets != null) {
                headlessAssets.close();
            }
        } else {
            world.shutdown(ctx);
        }
        if (audio != null) {
            audio.close();
        }
    }
}
