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
import dev.jeddy.render.mesh.GpuMesh;
import dev.jeddy.render.mesh.MeshAssets;
import dev.jeddy.render.post.PostProcess;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
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

    private static final int TEXT = 0xFFF2E6C8;
    private static final int DIM = 0xFFB8AA90;
    private static final int ACCENT = 0xFFFFB04A;
    private static final int PANEL = 0xA0101018;
    private static final String HELP =
            "LMB SELECT/MOVE  RMB MOVE  DRAG BOX  1-6 TAB  G FORMATION  F FIREBALL  T RANGE"
                    + "  SPACE PAUSE  F3 PROFILER";

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
    private final FrameStats gameStats = new FrameStats(120);
    private ProfilerOverlay profiler;
    private Audio audio;
    private String gpu = "unknown";
    private int screenshots;

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

    /** The asset manager (the game thread owns it). */
    AssetManager assets() {
        return options.headless ? headlessAssets : world.assets();
    }

    /** The mesh asset type: uploaded meshes, or CPU-only ones when headless. */
    AssetType<GpuMesh> meshes() {
        return options.headless ? headlessMeshes : world.meshes();
    }

    /** Debug lines the game thread draws into. */
    DebugDraw debugDraw() {
        return options.headless ? headlessDebug : world.debugDraw();
    }

    /** The audio system, or {@code null} without a device (or with {@code --no-audio}). */
    Audio audio() {
        return audio;
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
        float s = 2 * Math.max(1, hud.pixelScale[i]);
        float line = 10 * s;
        if (hud.profiler[i]) {
            profiler.queue(
                    mainStats,
                    gameStats,
                    world.scene().graph(),
                    world.scene().draws().commandCount());
        }
        if (hud.loading[i]) {
            float x = width * 0.5f - 60 * s;
            float y = height * 0.5f - line;
            o.rect(x - 8 * s, y - 6 * s, 140 * s, 2 * line + 8 * s, PANEL);
            float cx = o.text(x, y, s, TEXT, "LOADING CRYPT ");
            cx = o.number(cx, y, s, ACCENT, hud.loadProgress[i] * 100, 0);
            o.text(cx, y, s, ACCENT, "%");
            o.rect(x, y + line, 120 * s * hud.loadProgress[i], 4 * s, ACCENT);
            return;
        }
        // Status panel, top right.
        float pw = 40 * 6 * s * 0.5f;
        float x = width - pw - 12 * s;
        float y = 8 * s;
        o.rect(x - 6 * s, y - 4 * s, pw + 10 * s, 7 * line * 0.5f + 8 * s, PANEL);
        float t = s * 0.5f;
        float l = line * 0.5f;
        float cx = o.text(x, y, t, DIM, "PARTY SELECTED ");
        cx = o.number(cx, y, t, TEXT, hud.selected[i], 0);
        o.text(cx, y, t, DIM, "/6");
        y += l;
        cx = o.text(x, y, t, DIM, "SKELETONS ");
        cx = o.number(cx, y, t, TEXT, hud.enemiesAlive[i], 0);
        cx = o.text(cx, y, t, DIM, " SEEN ");
        o.number(cx, y, t, TEXT, hud.enemiesVisible[i], 0);
        y += l;
        cx = o.text(x, y, t, DIM, "FORMATION ");
        o.text(cx, y, t, TEXT, Party.FORMATION_NAMES[hud.formation[i]]);
        y += l;
        cx = o.text(x, y, t, DIM, "INSTANCES ");
        cx = o.number(cx, y, t, TEXT, hud.instances[i], 0);
        cx = o.text(cx, y, t, DIM, " LIGHTS ");
        o.number(cx, y, t, TEXT, hud.lights[i], 0);
        y += l;
        double ms = mainStats.averageMs();
        cx = o.text(x, y, t, DIM, "FPS ");
        cx = o.number(cx, y, t, TEXT, ms > 0 ? 1000 / ms : 0, 0);
        cx = o.text(cx, y, t, DIM, " MAIN ");
        cx = o.number(cx, y, t, TEXT, ms, 1);
        cx = o.text(cx, y, t, DIM, " GAME ");
        o.number(cx, y, t, TEXT, gameStats.averageMs(), 1);
        y += l;
        if (hud.perf[i]) {
            cx = o.text(x, y, t, ACCENT, hud.measuring[i] ? "PERF MEASURING " : "PERF WARM-UP ");
            o.number(cx, y, t, ACCENT, Math.max(0, hud.perfRemaining[i]), 0);
        }

        // Centre messages.
        float cy = 12 * s;
        if (hud.paused[i]) {
            o.text(width * 0.5f - 3 * 6 * s, cy, s, ACCENT, "PAUSED");
            cy += line;
        }
        if (hud.targeting[i]) {
            o.text(
                    width * 0.5f - 22 * 6 * t,
                    cy,
                    t,
                    ACCENT,
                    "FIREBALL: CLICK TO CAST, RIGHT CLICK CANCELS");
            cy += l;
        }
        if (hud.turnBased[i]) {
            float mx = width * 0.5f - 11 * 6 * t;
            if (hud.moveCost[i] >= 0) {
                cx = o.text(mx, cy, t, TEXT, "MOVE COST ");
                cx = o.number(cx, cy, t, ACCENT, hud.moveCost[i], 1);
                cx = o.text(cx, cy, t, TEXT, " / ");
                cx = o.number(cx, cy, t, TEXT, hud.moveBudget[i], 1);
                o.text(cx, cy, t, TEXT, " M");
            } else {
                o.text(mx, cy, t, DIM, "OUT OF RANGE");
            }
        }

        // Box selection.
        if (hud.box[i]) {
            float ps = hud.pixelScale[i];
            float x0 = hud.boxRect[i * 4] * ps;
            float y0 = hud.boxRect[i * 4 + 1] * ps;
            float x1 = hud.boxRect[i * 4 + 2] * ps;
            float y1 = hud.boxRect[i * 4 + 3] * ps;
            o.rect(x0, y0, x1 - x0, y1 - y0, 0x2050FF80);
            o.rect(x0, y0, x1 - x0, 1, 0xFF50FF80);
            o.rect(x0, y1, x1 - x0, 1, 0xFF50FF80);
            o.rect(x0, y0, 1, y1 - y0, 0xFF50FF80);
            o.rect(x1, y0, 1, y1 - y0, 0xFF50FF80);
        }

        // Help, bottom left.
        o.text(10 * s, height - 10 * s, t, DIM, HELP);
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
