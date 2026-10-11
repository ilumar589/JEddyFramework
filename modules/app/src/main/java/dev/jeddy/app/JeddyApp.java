package dev.jeddy.app;

import dev.jeddy.core.concurrent.SpinParkWaiter;
import dev.jeddy.core.concurrent.SpscIntRing;
import dev.jeddy.core.concurrent.TripleBuffer;
import dev.jeddy.core.config.Settings;
import dev.jeddy.core.job.JobSystem;
import dev.jeddy.core.log.Log;
import dev.jeddy.core.log.Logger;
import dev.jeddy.core.time.FixedStepper;
import dev.jeddy.input.EventPump;
import dev.jeddy.input.Input;
import dev.jeddy.window.Cursors;
import dev.jeddy.window.FrameLimiter;
import dev.jeddy.window.MainThreadQueue;
import dev.jeddy.window.Platform;
import dev.jeddy.window.VSync;
import dev.jeddy.window.Window;
import dev.jeddy.window.WindowCommands;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;

/**
 * Runs a game: SDL and the window on the calling (main) thread, simulation on a game thread (DESIGN
 * §5, §13.2, TASKS M2-09).
 *
 * <pre>
 * main thread  [pump events → ring][drain requests][acquire frame N-1][render][swap][limit]
 * game thread  [input, actions, gestures][fixed ticks @ 30 Hz][update][extract frame N][publish]
 * </pre>
 *
 * The threads exchange frames through a {@link TripleBuffer}; the game thread stays at most one
 * frame ahead, so it runs at the main thread's rate. Neither loop allocates in steady state.
 *
 * <p>Shutdown is clean and in reverse order: a quit request (window closed, {@link
 * AppContext#quit()}) stops the game thread after its frame, then the main thread tears down the
 * renderer, window and SDL. An exception on either thread stops both, is logged with its stack
 * trace, and is shown in an SDL message box ({@link AppConfig#crashDialog(boolean)}).
 *
 * <pre>{@code
 * public static void main(String[] args) {
 *     System.exit(JeddyApp.run(new AppConfig().app("Iso", "1.0", "dev.jeddy.iso"), new IsoGame()));
 * }
 * }</pre>
 *
 * <p>Thread ownership: {@link #run} is called from the thread that becomes the SDL main thread.
 */
public final class JeddyApp {
    private static final Logger LOG = Log.logger("app");
    private static final SpinParkWaiter WAITER = new SpinParkWaiter(100, 20, 50_000);

    private final AppConfig config;
    private final Game game;
    private final TripleBuffer frames = new TripleBuffer();
    private volatile boolean running = true;
    private volatile Throwable failure;
    private volatile String failedThread;

    private JeddyApp(AppConfig config, Game game) {
        this.config = config;
        this.game = game;
    }

    /**
     * Runs the game until it quits. Returns 0 after a clean exit, 1 after a crash.
     *
     * @param config identity, window and loop settings
     * @param game the game callbacks
     */
    public static int run(AppConfig config, Game game) {
        return new JeddyApp(config, game).runMain();
    }

    /** Stops both loops after their current frame. Any thread. */
    void requestStop() {
        running = false;
    }

    private void fail(String thread, Throwable t) {
        if (failure == null) {
            failedThread = thread;
            failure = t;
        }
        running = false;
    }

    private int runMain() {
        Window window = null;
        Cursors cursors = null;
        EventPump pump = null;
        JobSystem jobs = null;
        Thread gameThread = null;
        MainContext main = null;
        boolean rendererStarted = false;
        try {
            Platform.init(config.name, config.version, config.identifier);
            window = Window.create(config.window);
            cursors = new Cursors();
            SpscIntRing ring = new SpscIntRing(ringCapacity(config.inputRingRecords));
            pump = new EventPump(window, ring);
            MainThreadQueue queue = new MainThreadQueue(config.commandQueueRecords);
            queue.addHandler(new WindowCommands(window, cursors));
            queue.addHandler(pump);
            jobs =
                    new JobSystem(
                            config.workerThreads >= 0
                                    ? config.workerThreads
                                    : JobSystem.defaultWorkerCount());
            Settings settings = loadSettings();
            Input input = new Input(ring, window.width(), window.height());
            FixedStepper stepper = new FixedStepper(config.tickRate, config.maxTicksPerFrame);
            AppContext ctx =
                    new AppContext(
                            this,
                            input,
                            queue,
                            window.clipboard(),
                            jobs,
                            stepper,
                            pump.keyLabels(),
                            settings,
                            config.settingsFile);
            main = new MainContext(window, cursors, pump, queue, jobs);
            if (config.renderer != null) {
                config.renderer.init(main);
                rendererStarted = true;
            }
            gameThread = new Thread(() -> runGame(ctx), "jeddy-game");
            gameThread.setUncaughtExceptionHandler((t, e) -> fail(t.getName(), e));
            gameThread.start();
            mainLoop(main);
        } catch (Throwable t) {
            fail(Thread.currentThread().getName(), t);
        } finally {
            running = false;
            joinQuietly(gameThread);
            if (rendererStarted) {
                try {
                    config.renderer.shutdown(main);
                } catch (Throwable t) {
                    fail(Thread.currentThread().getName(), t);
                }
            }
            closeQuietly(pump);
            closeQuietly(cursors);
            closeQuietly(window);
            closeQuietly(jobs);
            if (failure != null) {
                reportCrash();
            }
            Platform.shutdown();
            Log.flush();
        }
        return failure == null ? 0 : 1;
    }

    private Settings loadSettings() {
        if (config.settingsFile != null && Files.isRegularFile(config.settingsFile)) {
            try {
                return Settings.load(config.settingsFile);
            } catch (IOException | IllegalArgumentException e) {
                LOG.warn("ignoring unreadable settings " + config.settingsFile, e);
            }
        }
        return new Settings();
    }

    private void mainLoop(MainContext main) {
        Window window = main.window;
        FrameLimiter limiter = new FrameLimiter();
        int cap = -1;
        while (running) {
            main.pump.pump();
            main.queue.drain();
            boolean fresh = frames.acquire();
            int wantedCap;
            if (window.shouldSkipRendering()) {
                wantedCap = config.backgroundFps;
            } else {
                if (config.renderer != null) {
                    config.renderer.render(main, frames.readIndex(), fresh);
                }
                if (window.hasGlContext()) {
                    window.swap();
                }
                boolean pacedBySwap = window.hasGlContext() && window.vsync() != VSync.OFF;
                wantedCap = pacedBySwap ? 0 : config.maxFps;
            }
            if (wantedCap != cap) {
                limiter.setTargetFps(wantedCap);
                cap = wantedCap;
            }
            limiter.waitForNextFrame();
            main.frame++;
            if (main.frame == config.quitAfterFrames) {
                requestStop();
            }
            if (config.observer != null) {
                config.observer.onMainFrame(main);
            }
        }
    }

    private void runGame(AppContext ctx) {
        try {
            game.init(ctx);
            ctx.actions.update(ctx.input); // builds binding indexes before the first frame
            long last = System.nanoTime();
            ctx.stepper.reset(last);
            float dt = ctx.stepper.stepSeconds();
            while (running) {
                int idle = 0;
                while (frames.hasUnconsumed() && running) {
                    idle = WAITER.idle(idle);
                }
                if (!running) {
                    break;
                }
                long now = System.nanoTime();
                ctx.frameNanos = now - last;
                last = now;
                ctx.frame++;
                ctx.input.update();
                ctx.actions.update(ctx.input);
                ctx.virtualCursor.update(ctx.input, ctx.frameNanos * 1e-9f);
                ctx.gestures.update(ctx.input);
                if (ctx.input.isQuitRequested() || ctx.input.isCloseRequested()) {
                    if (game.onQuitRequested(ctx)) {
                        requestStop();
                    }
                    ctx.input.clearQuitRequest();
                }
                int ticks = ctx.stepper.advance(now);
                for (int i = 0; i < ticks; i++) {
                    game.fixedUpdate(ctx, dt);
                }
                game.update(ctx, ctx.frameNanos * 1e-9f, ctx.stepper.alpha());
                game.extract(ctx, frames.writeIndex());
                frames.publish();
            }
        } catch (Throwable t) {
            fail(Thread.currentThread().getName(), t);
        } finally {
            try {
                game.shutdown(ctx);
            } catch (Throwable t) {
                fail(Thread.currentThread().getName(), t);
            }
        }
    }

    private void reportCrash() {
        Throwable t = failure;
        LOG.error("crash on thread " + failedThread, t);
        Log.flush();
        if (config.crashDialog) {
            StringWriter trace = new StringWriter();
            t.printStackTrace(new PrintWriter(trace));
            String text = trace.toString();
            if (text.length() > 3000) {
                text = text.substring(0, 3000) + "\n...";
            }
            Platform.showErrorMessage(
                    config.name + " crashed",
                    "The " + failedThread + " thread stopped with an error:\n\n" + text);
        }
    }

    /** Returns the crash that ended the last run on this instance, or {@code null}. */
    Throwable failure() {
        return failure;
    }

    private static int ringCapacity(int records) {
        int ints = Math.max(2, records * EventPump.recordInts());
        return Integer.highestOneBit(ints - 1) << 1;
    }

    private void joinQuietly(Thread t) {
        if (t == null) {
            return;
        }
        try {
            t.join(10_000);
            if (t.isAlive()) {
                LOG.error("game thread did not stop within 10 s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeQuietly(AutoCloseable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception e) {
            LOG.warn("error while shutting down", e);
        }
    }
}
