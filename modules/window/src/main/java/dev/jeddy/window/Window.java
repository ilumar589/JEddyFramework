package dev.jeddy.window;

import static dev.jeddy.sdl.ffi.SDL3.*;

import dev.jeddy.core.log.Log;
import dev.jeddy.core.log.Logger;
import dev.jeddy.core.memory.CStrings;
import dev.jeddy.sdl.Sdl;
import dev.jeddy.sdl.SdlEventType;
import dev.jeddy.sdl.SdlException;
import dev.jeddy.sdl.SdlThread;
import dev.jeddy.sdl.ffi.SDL_DisplayMode;
import dev.jeddy.sdl.ffi.SDL_Rect;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * An SDL window with an OpenGL 4.6 core context (DESIGN §12.1).
 *
 * <p>The window caches its size, pixel size, display scale and state flags. The event pump calls
 * {@link #onWindowEvent(int, int, int)} for this window's {@code SDL_EVENT_WINDOW_*} events, so the
 * getters are plain field reads. {@link #sizeGeneration()} increases whenever the pixel size or
 * scale changes, which tells the renderer to resize its targets.
 *
 * <p>Window coordinates and pixels differ on high-density displays: input and UI layout use window
 * coordinates ({@link #width()}, {@link #height()}), rendering uses pixels ({@link #pixelWidth()},
 * {@link #pixelHeight()}), and UI scaling uses {@link #displayScale()}.
 *
 * <p>Thread ownership: main thread only. Every method asserts it, except the cached getters.
 */
public final class Window implements AutoCloseable {
    private static final Logger LOG = Log.logger("window");

    private final Arena arena;
    private final MemorySegment handle;
    private final int id;
    private final MemorySegment intPair;
    private final MemorySegment displayMode;
    private final MemorySegment rect;
    private MemorySegment glContext = MemorySegment.NULL;

    private int width;
    private int height;
    private int pixelWidth;
    private int pixelHeight;
    private float displayScale;
    private int displayId;
    private int sizeGeneration;
    private boolean minimized;
    private boolean occluded;
    private boolean focused;
    private boolean closeRequested;
    private boolean fullscreen;
    private boolean textInput;
    private boolean relativeMouse;
    private boolean mouseGrab;
    private WindowMode mode = WindowMode.WINDOWED;
    private VSync vsync = VSync.OFF;

    private Window(Arena arena, MemorySegment handle) {
        this.arena = arena;
        this.handle = handle;
        this.id = SDL_GetWindowID(handle);
        this.intPair = arena.allocate(ValueLayout.JAVA_INT, 2);
        this.displayMode = arena.allocate(SDL_DisplayMode.layout());
        this.rect = arena.allocate(SDL_Rect.layout());
    }

    /**
     * Creates and shows a window, creates its GL context and makes it current.
     *
     * @throws SdlException if the window or a GL 4.6 core context cannot be created
     */
    public static Window create(WindowConfig config) {
        SdlThread.assertMainThread("Window.create");
        long flags = SDL_WINDOW_HIDDEN() | SDL_WINDOW_HIGH_PIXEL_DENSITY();
        if (config.resizable) {
            flags |= SDL_WINDOW_RESIZABLE();
        }
        if (config.openGl) {
            setGlAttributes(config);
            flags |= SDL_WINDOW_OPENGL();
        }
        Arena arena = Arena.ofConfined();
        Window window = null;
        try {
            MemorySegment handle =
                    Sdl.check(
                            SDL_CreateWindow(
                                    arena.allocateFrom(config.title),
                                    config.width,
                                    config.height,
                                    flags),
                            "SDL_CreateWindow");
            window = new Window(arena, handle);
            int display = Platform.displayId(config.displayIndex);
            int centered = SDL_WINDOWPOS_CENTERED_MASK() | display;
            SDL_SetWindowPosition(handle, centered, centered);
            SDL_SetWindowMinimumSize(handle, config.minWidth, config.minHeight);
            if (config.openGl) {
                window.createContext(config);
            }
            window.applyMode(
                    config.mode,
                    config.fullscreenWidth,
                    config.fullscreenHeight,
                    config.fullscreenRefreshRate);
            if (!config.hidden) {
                Sdl.check(SDL_ShowWindow(handle), "SDL_ShowWindow");
            }
            window.refresh();
            LOG.info(
                    "window {}x{} ({}x{} px, scale {}) on display {}, {}",
                    window.width,
                    window.height,
                    window.pixelWidth,
                    window.pixelHeight,
                    window.displayScale,
                    window.displayId,
                    window.mode);
            return window;
        } catch (RuntimeException e) {
            if (window != null) {
                window.close();
            } else {
                arena.close();
            }
            throw e;
        }
    }

    private static void setGlAttributes(WindowConfig config) {
        SDL_GL_ResetAttributes();
        int contextFlags = SDL_GL_CONTEXT_FORWARD_COMPATIBLE_FLAG();
        if (config.glDebug) {
            contextFlags |= SDL_GL_CONTEXT_DEBUG_FLAG();
        }
        glAttribute(SDL_GL_CONTEXT_MAJOR_VERSION(), 4);
        glAttribute(SDL_GL_CONTEXT_MINOR_VERSION(), 6);
        glAttribute(SDL_GL_CONTEXT_PROFILE_MASK(), SDL_GL_CONTEXT_PROFILE_CORE());
        glAttribute(SDL_GL_CONTEXT_FLAGS(), contextFlags);
        glAttribute(SDL_GL_DOUBLEBUFFER(), 1);
        glAttribute(SDL_GL_RED_SIZE(), 8);
        glAttribute(SDL_GL_GREEN_SIZE(), 8);
        glAttribute(SDL_GL_BLUE_SIZE(), 8);
        glAttribute(SDL_GL_ALPHA_SIZE(), 0); // no desktop compositing; alpha visuals are rarer
        glAttribute(SDL_GL_DEPTH_SIZE(), config.depthBits);
        glAttribute(SDL_GL_STENCIL_SIZE(), 0);
        glAttribute(SDL_GL_MULTISAMPLEBUFFERS(), 0);
        // The final pass writes sRGB-encoded values itself, so the default framebuffer is plain
        // RGB8: sRGB-capable visuals are missing on some X servers (DESIGN D23).
        glAttribute(SDL_GL_FRAMEBUFFER_SRGB_CAPABLE(), 0);
    }

    private static void glAttribute(int attribute, int value) {
        Sdl.check(SDL_GL_SetAttribute(attribute, value), "SDL_GL_SetAttribute");
    }

    private void createContext(WindowConfig config) {
        MemorySegment context = SDL_GL_CreateContext(handle);
        if (context.address() == 0) {
            throw new SdlException(
                    "SDL_GL_CreateContext",
                    Sdl.error() + " (Jeddy needs an OpenGL 4.6 core profile driver)");
        }
        glContext = context;
        Sdl.check(SDL_GL_MakeCurrent(handle, glContext), "SDL_GL_MakeCurrent");
        setVSync(config.vsync);
    }

    /** Returns {@code true} if this window has a GL context. */
    public boolean hasGlContext() {
        return glContext.address() != 0;
    }

    /** Makes this window's GL context current on the calling (main) thread. */
    public void makeCurrent() {
        SdlThread.assertMainThread("SDL_GL_MakeCurrent");
        Sdl.check(SDL_GL_MakeCurrent(handle, glContext), "SDL_GL_MakeCurrent");
    }

    /** Presents the back buffer. Allocates nothing. */
    public void swap() {
        SdlThread.assertMainThread("SDL_GL_SwapWindow");
        Sdl.check(SDL_GL_SwapWindow(handle), "SDL_GL_SwapWindow");
    }

    /**
     * Returns the address of a GL function for the GL loader, or {@code NULL} if the driver does
     * not export it. The context must be current. Cold path (allocates the name).
     */
    public MemorySegment glProcAddress(String name) {
        SdlThread.assertMainThread("SDL_GL_GetProcAddress");
        return SDL_GL_GetProcAddress(CStrings.of(name));
    }

    /**
     * Sets the swap interval and returns the mode in effect, which can differ: adaptive VSync falls
     * back to {@link VSync#ON} where the driver lacks it.
     */
    public VSync setVSync(VSync requested) {
        SdlThread.assertMainThread("SDL_GL_SetSwapInterval");
        int interval =
                switch (requested) {
                    case OFF -> 0;
                    case ON -> 1;
                    case ADAPTIVE -> -1;
                };
        if (SDL_GL_SetSwapInterval(interval)) {
            vsync = requested;
        } else if (requested == VSync.ADAPTIVE && SDL_GL_SetSwapInterval(1)) {
            LOG.info("adaptive vsync is not supported, using vsync on");
            vsync = VSync.ON;
        } else {
            LOG.warn("cannot set swap interval {}: {}", interval, Sdl.error());
        }
        return vsync;
    }

    /** Returns the swap mode in effect. */
    public VSync vsync() {
        return vsync;
    }

    /**
     * Switches between windowed, borderless fullscreen and exclusive fullscreen at the desktop
     * mode. Waits until the window system applied the change (up to SDL's sync timeout).
     */
    public void setMode(WindowMode newMode) {
        setMode(newMode, 0, 0, 0);
    }

    /**
     * Like {@link #setMode(WindowMode)}; for {@link WindowMode#EXCLUSIVE} uses the supported mode
     * closest to {@code width × height @ refreshRate} (0 = the desktop value).
     */
    public void setMode(WindowMode newMode, int width, int height, float refreshRate) {
        SdlThread.assertMainThread("SDL_SetWindowFullscreen");
        applyMode(newMode, width, height, refreshRate);
        SDL_SyncWindow(handle);
        refresh();
    }

    private void applyMode(WindowMode newMode, int width, int height, float refreshRate) {
        switch (newMode) {
            case WINDOWED -> {
                Sdl.check(SDL_SetWindowFullscreen(handle, false), "SDL_SetWindowFullscreen");
            }
            case BORDERLESS -> {
                Sdl.check(
                        SDL_SetWindowFullscreenMode(handle, MemorySegment.NULL),
                        "SDL_SetWindowFullscreenMode");
                Sdl.check(SDL_SetWindowFullscreen(handle, true), "SDL_SetWindowFullscreen");
            }
            case EXCLUSIVE -> {
                if (!selectExclusiveMode(width, height, refreshRate)) {
                    LOG.warn(
                            "no exclusive fullscreen mode near {}x{}@{} ({}); using borderless",
                            width,
                            height,
                            refreshRate,
                            Sdl.error());
                    applyMode(WindowMode.BORDERLESS, 0, 0, 0);
                    return;
                }
                Sdl.check(
                        SDL_SetWindowFullscreenMode(handle, displayMode),
                        "SDL_SetWindowFullscreenMode");
                Sdl.check(SDL_SetWindowFullscreen(handle, true), "SDL_SetWindowFullscreen");
            }
        }
        mode = newMode;
    }

    private boolean selectExclusiveMode(int width, int height, float refreshRate) {
        int display = SDL_GetDisplayForWindow(handle);
        MemorySegment desktop = SDL_GetDesktopDisplayMode(display);
        if (desktop.address() == 0) {
            return false;
        }
        desktop = desktop.reinterpret(SDL_DisplayMode.layout().byteSize());
        int w = width > 0 ? width : SDL_DisplayMode.w(desktop);
        int h = height > 0 ? height : SDL_DisplayMode.h(desktop);
        float hz = refreshRate > 0 ? refreshRate : SDL_DisplayMode.refresh_rate(desktop);
        return SDL_GetClosestFullscreenDisplayMode(display, w, h, hz, true, displayMode);
    }

    /** Returns the current window mode. */
    public WindowMode mode() {
        return mode;
    }

    /** Sets the title (cold path). */
    public void setTitle(String title) {
        SdlThread.assertMainThread("SDL_SetWindowTitle");
        try (Arena a = Arena.ofConfined()) {
            Sdl.check(SDL_SetWindowTitle(handle, a.allocateFrom(title)), "SDL_SetWindowTitle");
        }
    }

    /**
     * Sets the window icon from tightly packed RGBA8 pixels (cold path). Icons are cosmetic, so a
     * video driver without icon support is not an error.
     *
     * @param width icon width in pixels
     * @param height icon height in pixels
     * @param rgba {@code width * height * 4} bytes, row-major, top row first
     * @return {@code true} if the icon was set
     */
    public boolean setIcon(int width, int height, byte[] rgba) {
        SdlThread.assertMainThread("SDL_SetWindowIcon");
        if (rgba.length < width * height * 4) {
            throw new IllegalArgumentException("icon needs " + width * height * 4 + " bytes");
        }
        try (Arena a = Arena.ofConfined()) {
            MemorySegment pixels = a.allocateFrom(ValueLayout.JAVA_BYTE, rgba);
            MemorySegment surface =
                    Sdl.check(
                            SDL_CreateSurfaceFrom(
                                    width, height, SDL_PIXELFORMAT_RGBA32(), pixels, width * 4),
                            "SDL_CreateSurfaceFrom");
            try {
                boolean ok = SDL_SetWindowIcon(handle, surface);
                if (!ok) {
                    LOG.debug("window icon not set: {}", Sdl.error());
                }
                return ok;
            } finally {
                SDL_DestroySurface(surface);
            }
        }
    }

    /** Resizes the window (window coordinates) and waits for the window system. */
    public void setSize(int width, int height) {
        SdlThread.assertMainThread("SDL_SetWindowSize");
        Sdl.check(SDL_SetWindowSize(handle, width, height), "SDL_SetWindowSize");
        SDL_SyncWindow(handle);
        refresh();
    }

    /** Raises the window above others and asks for input focus. */
    public void raise() {
        SdlThread.assertMainThread("SDL_RaiseWindow");
        SDL_RaiseWindow(handle);
    }

    /**
     * Starts text input (IME and on-screen keyboards) for a UI text field. Text arrives as text
     * input events until {@link #stopTextInput()}. Allocates nothing.
     *
     * @param x left of the text field, window coordinates
     * @param y top of the text field
     * @param w width of the text field
     * @param h height of the text field
     * @param cursor caret offset from {@code x}, so IME candidate windows open next to it
     */
    public void startTextInput(int x, int y, int w, int h, int cursor) {
        SdlThread.assertMainThread("SDL_StartTextInput");
        SDL_Rect.x(rect, x);
        SDL_Rect.y(rect, y);
        SDL_Rect.w(rect, w);
        SDL_Rect.h(rect, h);
        SDL_SetTextInputArea(handle, rect, cursor);
        if (!textInput) {
            Sdl.check(SDL_StartTextInput(handle), "SDL_StartTextInput");
            textInput = true;
        }
    }

    /** Stops text input; key presses no longer produce text. */
    public void stopTextInput() {
        SdlThread.assertMainThread("SDL_StopTextInput");
        if (textInput) {
            SDL_StopTextInput(handle);
            textInput = false;
        }
    }

    /** Returns {@code true} between {@link #startTextInput} and {@link #stopTextInput()}. */
    public boolean isTextInputActive() {
        return textInput;
    }

    /**
     * Turns relative mouse mode on or off: the cursor is hidden and confined, and motion events
     * report unbounded deltas (camera rotation while a button is held). Logs a warning and stays
     * off where the video driver lacks it.
     */
    public void setRelativeMouseMode(boolean enabled) {
        SdlThread.assertMainThread("SDL_SetWindowRelativeMouseMode");
        if (enabled != relativeMouse) {
            if (SDL_SetWindowRelativeMouseMode(handle, enabled)) {
                relativeMouse = enabled;
            } else {
                LOG.warn("relative mouse mode unavailable: {}", Sdl.error());
            }
        }
    }

    /** Returns whether relative mouse mode is on. */
    public boolean isRelativeMouseMode() {
        return relativeMouse;
    }

    /**
     * Confines the cursor to the window (windowed edge scrolling). Exclusive fullscreen always
     * confines it.
     */
    public void setMouseGrab(boolean grab) {
        SdlThread.assertMainThread("SDL_SetWindowMouseGrab");
        if (grab != mouseGrab) {
            SDL_SetWindowMouseGrab(handle, grab);
            mouseGrab = grab;
        }
    }

    /** Returns whether the cursor is confined to the window. */
    public boolean isMouseGrabbed() {
        return mouseGrab;
    }

    /** Moves the cursor to a position in window coordinates. */
    public void warpMouse(float x, float y) {
        SdlThread.assertMainThread("SDL_WarpMouseInWindow");
        SDL_WarpMouseInWindow(handle, x, y);
    }

    /**
     * Updates the cached state from one of this window's events. Called by the event pump for
     * {@code SDL_EVENT_WINDOW_*} events whose window ID is {@link #id()}. Allocates nothing.
     *
     * @param type the {@code SDL_EventType}
     * @param data1 {@code SDL_WindowEvent.data1}
     * @param data2 {@code SDL_WindowEvent.data2}
     */
    public void onWindowEvent(int type, int data1, int data2) {
        switch (type) {
            case SdlEventType.WINDOW_RESIZED -> {
                width = data1;
                height = data2;
                sizeGeneration++;
            }
            case SdlEventType.WINDOW_PIXEL_SIZE_CHANGED -> {
                pixelWidth = data1;
                pixelHeight = data2;
                sizeGeneration++;
            }
            case SdlEventType.WINDOW_DISPLAY_SCALE_CHANGED -> {
                displayScale = SDL_GetWindowDisplayScale(handle);
                sizeGeneration++;
            }
            case SdlEventType.WINDOW_DISPLAY_CHANGED -> displayId = data1;
            case SdlEventType.WINDOW_MINIMIZED -> minimized = true;
            case SdlEventType.WINDOW_RESTORED, SdlEventType.WINDOW_MAXIMIZED -> minimized = false;
            case SdlEventType.WINDOW_OCCLUDED -> occluded = true;
            case SdlEventType.WINDOW_EXPOSED, SdlEventType.WINDOW_SHOWN -> occluded = false;
            case SdlEventType.WINDOW_FOCUS_GAINED -> focused = true;
            case SdlEventType.WINDOW_FOCUS_LOST -> focused = false;
            case SdlEventType.WINDOW_CLOSE_REQUESTED -> closeRequested = true;
            case SdlEventType.WINDOW_ENTER_FULLSCREEN -> fullscreen = true;
            case SdlEventType.WINDOW_LEAVE_FULLSCREEN -> fullscreen = false;
            default -> {}
        }
    }

    /** Re-reads every cached value from SDL. */
    public void refresh() {
        SdlThread.assertMainThread("Window.refresh");
        if (SDL_GetWindowSize(handle, intPair, intPair.asSlice(4))) {
            width = intPair.getAtIndex(ValueLayout.JAVA_INT, 0);
            height = intPair.getAtIndex(ValueLayout.JAVA_INT, 1);
        }
        if (SDL_GetWindowSizeInPixels(handle, intPair, intPair.asSlice(4))) {
            pixelWidth = intPair.getAtIndex(ValueLayout.JAVA_INT, 0);
            pixelHeight = intPair.getAtIndex(ValueLayout.JAVA_INT, 1);
        }
        displayScale = SDL_GetWindowDisplayScale(handle);
        displayId = SDL_GetDisplayForWindow(handle);
        long flags = SDL_GetWindowFlags(handle);
        minimized = (flags & SDL_WINDOW_MINIMIZED()) != 0;
        occluded = (flags & SDL_WINDOW_OCCLUDED()) != 0;
        focused = (flags & SDL_WINDOW_INPUT_FOCUS()) != 0;
        fullscreen = (flags & SDL_WINDOW_FULLSCREEN()) != 0;
        sizeGeneration++;
    }

    /** Returns the SDL window ID that window events carry. */
    public int id() {
        return id;
    }

    /** Returns the raw {@code SDL_Window*} for engine modules (cursor, text input). */
    public MemorySegment handle() {
        return handle;
    }

    /** Returns the width in window coordinates. */
    public int width() {
        return width;
    }

    /** Returns the height in window coordinates. */
    public int height() {
        return height;
    }

    /** Returns the drawable width in pixels. */
    public int pixelWidth() {
        return pixelWidth;
    }

    /** Returns the drawable height in pixels. */
    public int pixelHeight() {
        return pixelHeight;
    }

    /** Returns the content scale for UI sizing (1.0 = 100 %, 1.5 = 150 %). */
    public float displayScale() {
        return displayScale;
    }

    /** Returns the ID of the display the window is mostly on. */
    public int displayId() {
        return displayId;
    }

    /** Returns a counter that changes whenever the pixel size or display scale changes. */
    public int sizeGeneration() {
        return sizeGeneration;
    }

    /** Returns {@code true} while minimized; the app skips rendering then. */
    public boolean isMinimized() {
        return minimized;
    }

    /** Returns {@code true} while fully covered by other windows (if the OS reports it). */
    public boolean isOccluded() {
        return occluded;
    }

    /** Returns {@code true} while the window has keyboard focus. */
    public boolean isFocused() {
        return focused;
    }

    /** Returns {@code true} while the window is fullscreen (borderless or exclusive). */
    public boolean isFullscreen() {
        return fullscreen;
    }

    /** Returns {@code true} once the user asked to close the window. */
    public boolean isCloseRequested() {
        return closeRequested;
    }

    /** Clears the close request (for "are you sure?" dialogs). */
    public void clearCloseRequest() {
        closeRequested = false;
    }

    /** Returns {@code true} if rendering would be wasted (minimized or zero-sized). */
    public boolean shouldSkipRendering() {
        return minimized || pixelWidth <= 0 || pixelHeight <= 0;
    }

    /** Destroys the GL context and the window. Idempotent. */
    @Override
    public void close() {
        SdlThread.assertMainThread("SDL_DestroyWindow");
        if (!arena.scope().isAlive()) {
            return;
        }
        if (glContext.address() != 0) {
            SDL_GL_MakeCurrent(handle, MemorySegment.NULL);
            SDL_GL_DestroyContext(glContext);
            glContext = MemorySegment.NULL;
        }
        SDL_DestroyWindow(handle);
        arena.close();
    }
}
