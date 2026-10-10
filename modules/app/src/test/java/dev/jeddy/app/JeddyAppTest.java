package dev.jeddy.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.input.Scancode;
import dev.jeddy.testing.Allocations;
import dev.jeddy.window.WindowCommands;
import dev.jeddy.window.WindowConfig;

import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The app loop under SDL's dummy video driver (TASKS M2-09). The AC asks for 10 minutes at 0 B per
 * frame on both threads; CI runs {@code jeddy.soakSeconds} (default 4) seconds, measured after a
 * warm-up, and a local run can pass {@code -Djeddy.soakSeconds=600}.
 */
class JeddyAppTest {
    private static AppConfig headless() {
        return new AppConfig()
                .app("Jeddy app test", "0.1", "dev.jeddy.tests.app")
                .window(
                        new WindowConfig()
                                .openGl(false)
                                .hidden(true)
                                .size(640, 360)
                                .minimumSize(100, 100))
                .frameCaps(500, 20)
                .workerThreads(2)
                .crashDialog(false);
    }

    /** A game that ticks, polls its actions and quits after a while. */
    static class CountingGame implements Game {
        final long runNanos;
        long ticks;
        long frames;
        long start;
        int pause;
        float alphaMin = 1;
        float alphaMax;

        CountingGame(long runNanos) {
            this.runNanos = runNanos;
        }

        @Override
        public void init(AppContext ctx) {
            int world = ctx.actions().context("world", 10, false);
            pause = ctx.actions().button("pause");
            ctx.actions().bind(world, pause, "Key:SPACE");
            ctx.actions().setActive(world, true);
            start = System.nanoTime();
        }

        @Override
        public void fixedUpdate(AppContext ctx, float dt) {
            ticks++;
        }

        @Override
        public void update(AppContext ctx, float dt, float alpha) {
            frames++;
            alphaMin = Math.min(alphaMin, alpha);
            alphaMax = Math.max(alphaMax, alpha);
            if (ctx.actions().wasPressed(pause)) {
                ctx.simulation().setPaused(!ctx.simulation().isPaused());
            }
            if (ctx.input().isKeyDown(Scancode.ESCAPE)) {
                ctx.quit();
            }
            if (frames % 50 == 0) {
                WindowCommands.postSetCursor(ctx.mainThread(), 0); // a main-thread request
            }
            if (System.nanoTime() - start > runNanos) {
                ctx.quit();
            }
        }
    }

    @Test
    void runsTicksAtThirtyHertzAndQuitsCleanly() {
        CountingGame game = new CountingGame(1_000_000_000L);
        AtomicLong mainFrames = new AtomicLong();
        int code = JeddyApp.run(headless().observer(ctx -> mainFrames.set(ctx.frame())), game);
        assertEquals(0, code, "exit code (the crash is logged to stdout)");
        assertTrue(game.ticks >= 25 && game.ticks <= 35, "30 Hz for ~1 s: " + game.ticks);
        // Variable-rate frames outpace ticks; loaded CI runners (Windows) manage ~100/s here.
        assertTrue(game.frames > 30, "frames: " + game.frames);
        assertTrue(mainFrames.get() >= game.frames, "main " + mainFrames + " game " + game.frames);
        assertTrue(game.alphaMax > 0.5f && game.alphaMin < 0.5f, "alpha spans the tick");
    }

    @Test
    void quitsAfterTheConfiguredNumberOfFrames() {
        CountingGame game = new CountingGame(Long.MAX_VALUE); // never quits by itself
        AtomicLong mainFrames = new AtomicLong();
        int code =
                JeddyApp.run(
                        headless().quitAfterFrames(12).observer(ctx -> mainFrames.set(ctx.frame())),
                        game);
        assertEquals(0, code);
        assertEquals(12, mainFrames.get());
    }

    @Test
    void aCrashOnTheGameThreadStopsTheAppWithExitCode1() {
        Game crashing =
                new Game() {
                    int frames;

                    @Override
                    public void fixedUpdate(AppContext ctx, float dt) {}

                    @Override
                    public void update(AppContext ctx, float dt, float alpha) {
                        if (++frames == 10) {
                            throw new IllegalStateException("boom");
                        }
                    }
                };
        assertEquals(1, JeddyApp.run(headless(), crashing));
    }

    /**
     * Waits until the JIT has compiled nothing for {@code quietMillis}. A thread that requests a C2
     * compilation resolves every string literal of the method's class on the spot (HotSpot's {@code
     * CompileBroker::compile_method}), which allocates a few hundred bytes once per class, so the
     * steady-state measurement starts after the hot methods have been compiled.
     */
    private static void awaitJitQuiet(long quietMillis, long maxMillis)
            throws InterruptedException {
        var jit = ManagementFactory.getCompilationMXBean();
        long deadline = System.currentTimeMillis() + maxMillis;
        long last = jit.getTotalCompilationTime();
        long quietSince = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            long now = jit.getTotalCompilationTime();
            if (now != last) {
                last = now;
                quietSince = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - quietSince >= quietMillis) {
                return;
            }
        }
    }

    @Test
    void bothThreadsAllocateNothingPerFrame() throws Exception {
        long soakMillis = Long.getLong("jeddy.soakSeconds", 4) * 1000;
        long warmupMillis = Long.getLong("jeddy.soakWarmupSeconds", 2) * 1000;
        AtomicReference<Thread> gameThread = new AtomicReference<>();
        AtomicReference<AppContext> context = new AtomicReference<>();
        AtomicLong gameFrames = new AtomicLong();
        AtomicLong mainFrames = new AtomicLong();
        Thread mainThread = Thread.currentThread();
        int windows = 4;
        long windowMillis = Math.max(250, soakMillis / windows);
        long[][] bytes = new long[2][windows + 1]; // [game, main][sample]
        long[][] frames = new long[2][windows + 1];
        CountingGame game =
                new CountingGame(Long.MAX_VALUE) {
                    @Override
                    public void init(AppContext ctx) {
                        super.init(ctx);
                        gameThread.set(Thread.currentThread());
                        context.set(ctx);
                    }

                    @Override
                    public void update(AppContext ctx, float dt, float alpha) {
                        super.update(ctx, dt, alpha);
                        gameFrames.lazySet(frames);
                    }
                };
        // A monitor thread samples both threads' allocation counters, so no measuring code runs
        // inside the loops: a warm-up of at least 30k frames per thread for the JIT, then the soak.
        Thread monitor =
                new Thread(
                        () -> {
                            try {
                                Thread.sleep(warmupMillis);
                                long deadline = System.currentTimeMillis() + 30_000;
                                while ((gameFrames.get() < 30_000 || mainFrames.get() < 30_000)
                                        && System.currentTimeMillis() < deadline) {
                                    Thread.sleep(50);
                                }
                                awaitJitQuiet(1500, 30_000);
                                for (int w = 0; w <= windows; w++) {
                                    if (w > 0) {
                                        Thread.sleep(windowMillis);
                                    }
                                    bytes[0][w] =
                                            Allocations.threadAllocatedBytes(gameThread.get());
                                    bytes[1][w] = Allocations.threadAllocatedBytes(mainThread);
                                    frames[0][w] = gameFrames.get();
                                    frames[1][w] = mainFrames.get();
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } finally {
                                context.get().quit();
                            }
                        },
                        "allocation-monitor");
        monitor.start();
        // Uncapped, so every per-frame method reaches C2 during the warm-up (see awaitJitQuiet).
        int code =
                JeddyApp.run(
                        headless()
                                .frameCaps(0, 20)
                                .observer(ctx -> mainFrames.lazySet(ctx.frame())),
                        game);
        monitor.join();
        assertEquals(0, code, "exit code (the crash is logged to stdout)");
        // Steady state means no window allocates. A single late JIT event (a C2 recompile that
        // resolves string constants, DESIGN §4.7) may allocate a few hundred bytes once; anything
        // per frame shows up in every window.
        String[] names = {"game", "main"};
        for (int t = 0; t < 2; t++) {
            long measured = frames[t][windows] - frames[t][0];
            assertTrue(measured > 100, names[t] + " thread frames: " + measured);
            int allocatingWindows = 0;
            StringBuilder perWindow = new StringBuilder();
            for (int w = 1; w <= windows; w++) {
                long b = bytes[t][w] - bytes[t][w - 1];
                long f = frames[t][w] - frames[t][w - 1];
                perWindow.append(' ').append(b).append(" B/").append(f).append(" frames");
                if (b != 0) {
                    allocatingWindows++;
                }
            }
            assertTrue(
                    allocatingWindows <= 1 && bytes[t][windows] - bytes[t][0] < 2048,
                    names[t]
                            + " thread allocated in "
                            + allocatingWindows
                            + " windows:"
                            + perWindow);
        }
    }
}
