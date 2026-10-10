package dev.jeddy.window;

/**
 * Settings for {@link Window#create(WindowConfig)}. A plain mutable holder with chainable setters;
 * the defaults suit a 1080p CRPG window.
 *
 * <p>Thread ownership: not thread-safe; fill it in, then pass it to the main thread.
 */
public final class WindowConfig {
    String title = "Jeddy";
    int width = 1600;
    int height = 900;
    int minWidth = 800;
    int minHeight = 450;
    int displayIndex;
    WindowMode mode = WindowMode.WINDOWED;
    int fullscreenWidth;
    int fullscreenHeight;
    float fullscreenRefreshRate;
    VSync vsync = VSync.ON;
    boolean resizable = true;
    boolean hidden;
    boolean openGl = true;
    boolean glDebug;
    int depthBits;

    /** Creates a config with the defaults. */
    public WindowConfig() {}

    /** Window title (default {@code "Jeddy"}). */
    public WindowConfig title(String title) {
        this.title = title;
        return this;
    }

    /** Windowed size in window coordinates (default 1600 × 900). */
    public WindowConfig size(int width, int height) {
        this.width = width;
        this.height = height;
        return this;
    }

    /** Smallest size the user can resize the window to (default 800 × 450). */
    public WindowConfig minimumSize(int width, int height) {
        this.minWidth = width;
        this.minHeight = height;
        return this;
    }

    /** Display to open on, as an index into {@link Platform#displays()} (default 0 = primary). */
    public WindowConfig display(int index) {
        this.displayIndex = index;
        return this;
    }

    /** Initial window mode (default {@link WindowMode#WINDOWED}). */
    public WindowConfig mode(WindowMode mode) {
        this.mode = mode;
        return this;
    }

    /**
     * Resolution and refresh rate for {@link WindowMode#EXCLUSIVE}. 0 means the desktop value; the
     * closest supported mode is used.
     */
    public WindowConfig fullscreenMode(int width, int height, float refreshRate) {
        this.fullscreenWidth = width;
        this.fullscreenHeight = height;
        this.fullscreenRefreshRate = refreshRate;
        return this;
    }

    /** Swap interval (default {@link VSync#ON}). */
    public WindowConfig vsync(VSync vsync) {
        this.vsync = vsync;
        return this;
    }

    /** Whether the user can resize the window (default {@code true}). */
    public WindowConfig resizable(boolean resizable) {
        this.resizable = resizable;
        return this;
    }

    /** Keep the window hidden after creation (default {@code false}); useful in tests. */
    public WindowConfig hidden(boolean hidden) {
        this.hidden = hidden;
        return this;
    }

    /**
     * Create an OpenGL 4.6 core context (default {@code true}). Headless tests turn it off, since
     * SDL's dummy video driver has no GL.
     */
    public WindowConfig openGl(boolean openGl) {
        this.openGl = openGl;
        return this;
    }

    /** Request a debug context for {@code KHR_debug} output (default {@code false}; dev builds). */
    public WindowConfig glDebug(boolean glDebug) {
        this.glDebug = glDebug;
        return this;
    }

    /**
     * Depth bits of the default framebuffer (default 0: the renderer draws into its own reversed-Z
     * targets and only blits to the window).
     */
    public WindowConfig depthBits(int depthBits) {
        this.depthBits = depthBits;
        return this;
    }
}
