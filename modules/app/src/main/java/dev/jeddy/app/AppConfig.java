package dev.jeddy.app;

import dev.jeddy.window.WindowConfig;

import java.nio.file.Path;

/**
 * Settings for {@link JeddyApp#run(AppConfig, Game)}: identity, window, loop rates and hooks.
 * Chainable setters; defaults suit a desktop CRPG.
 *
 * <p>Thread ownership: fill it in on the launching thread before {@code run}.
 */
public final class AppConfig {
    String name = "Jeddy";
    String version = "0.1.0";
    String identifier = "dev.jeddy.app";
    WindowConfig window = new WindowConfig();
    double tickRate = 30;
    int maxTicksPerFrame = 4;
    int maxFps = 240;
    int backgroundFps = 20;
    int inputRingRecords = 8192;
    int commandQueueRecords = 1024;
    int workerThreads = -1;
    boolean crashDialog = true;
    long quitAfterFrames = Long.getLong("jeddy.app.quitAfterFrames", 0);
    Path settingsFile;
    FrameRenderer renderer;
    MainFrameObserver observer;

    /** Creates a config with the defaults. */
    public AppConfig() {}

    /** Application name, version and reverse-domain identifier (SDL app metadata, logs). */
    public AppConfig app(String name, String version, String identifier) {
        this.name = name;
        this.version = version;
        this.identifier = identifier;
        return this;
    }

    /** Window settings. */
    public AppConfig window(WindowConfig window) {
        this.window = window;
        return this;
    }

    /** Simulation ticks per second (default 30, DESIGN §5). */
    public AppConfig tickRate(double ticksPerSecond) {
        this.tickRate = ticksPerSecond;
        return this;
    }

    /** Most ticks simulated in one frame after a stall (default 4). */
    public AppConfig maxTicksPerFrame(int maxTicks) {
        this.maxTicksPerFrame = maxTicks;
        return this;
    }

    /**
     * Frame cap when VSync is off or there is no GL context (default 240; 0 = unlimited), and the
     * rate while minimized (default 20).
     */
    public AppConfig frameCaps(int maxFps, int backgroundFps) {
        this.maxFps = maxFps;
        this.backgroundFps = backgroundFps;
        return this;
    }

    /** Capacity of the input ring and the main-thread command queue, in records. */
    public AppConfig queues(int inputRecords, int commandRecords) {
        this.inputRingRecords = inputRecords;
        this.commandQueueRecords = commandRecords;
        return this;
    }

    /** Job system worker threads (default -1: {@code JobSystem.defaultWorkerCount()}). */
    public AppConfig workerThreads(int workers) {
        this.workerThreads = workers;
        return this;
    }

    /** Show an SDL message box when a thread crashes (default {@code true}; tests turn it off). */
    public AppConfig crashDialog(boolean show) {
        this.crashDialog = show;
        return this;
    }

    /**
     * Quits after this many main-thread frames; 0 (the default) runs until the game quits. The
     * default comes from {@code -Djeddy.app.quitAfterFrames=N}, so smoke tests and CI can run a
     * packaged game for a few frames without code changes.
     */
    public AppConfig quitAfterFrames(long frames) {
        this.quitAfterFrames = frames;
        return this;
    }

    /** User settings file, loaded at startup if it exists ({@link AppContext#settings()}). */
    public AppConfig settingsFile(Path file) {
        this.settingsFile = file;
        return this;
    }

    /** Main-thread renderer (none in a headless or v0 app). */
    public AppConfig renderer(FrameRenderer renderer) {
        this.renderer = renderer;
        return this;
    }

    /** Diagnostics hook called at the end of every main-thread frame (tests, profilers). */
    public AppConfig observer(MainFrameObserver observer) {
        this.observer = observer;
        return this;
    }
}
