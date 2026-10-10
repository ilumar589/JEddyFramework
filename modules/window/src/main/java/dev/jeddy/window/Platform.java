package dev.jeddy.window;

import static dev.jeddy.sdl.ffi.SDL3.*;

import dev.jeddy.core.log.Log;
import dev.jeddy.core.log.Logger;
import dev.jeddy.core.memory.CStrings;
import dev.jeddy.sdl.Sdl;
import dev.jeddy.sdl.SdlThread;
import dev.jeddy.sdl.ffi.SDL_DisplayMode;
import dev.jeddy.sdl.ffi.SDL_Rect;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * SDL platform lifetime: video, events and gamepad subsystems, displays and message boxes (DESIGN
 * §12.1).
 *
 * <p>{@link #init(String, String, String)} must run on the thread that will own all windows, GL
 * contexts and event polling: the SDL main thread (§5). On Windows and Linux any thread can be that
 * thread, but it must stay the same for the whole run, so the launcher calls this first thing from
 * {@code main}.
 *
 * <p>Thread ownership: main thread, except {@link #showErrorMessage(String, String)}, which works
 * from any thread and before {@code init}.
 */
public final class Platform {
    private static final Logger LOG = Log.logger("window");

    private static boolean initialized;

    private Platform() {}

    /**
     * Loads SDL, initializes the video, events and gamepad subsystems and marks the calling thread
     * as the SDL main thread.
     *
     * @param appName application name (used by the OS for audio streams, task switchers, ...)
     * @param appVersion application version string
     * @param appId reverse-domain identifier, for example {@code dev.jeddy.isodemo}
     * @throws dev.jeddy.sdl.SdlException if SDL cannot initialize (no display, missing driver)
     */
    public static synchronized void init(String appName, String appVersion, String appId) {
        if (initialized) {
            SdlThread.assertMainThread("Platform.init");
            return;
        }
        Sdl.loadNatives();
        try (Arena arena = Arena.ofConfined()) {
            SDL_SetAppMetadata(
                    arena.allocateFrom(appName),
                    arena.allocateFrom(appVersion),
                    arena.allocateFrom(appId));
        }
        Sdl.check(SDL_Init(SDL_INIT_VIDEO() | SDL_INIT_EVENTS() | SDL_INIT_GAMEPAD()), "SDL_Init");
        SdlThread.markMainThread();
        initialized = true;
        LOG.info(
                "SDL {} ({}), video driver {}",
                Sdl.versionString(Sdl.version()),
                Sdl.revision(),
                videoDriver());
    }

    /** Returns {@code true} between {@link #init} and {@link #shutdown()}. */
    public static synchronized boolean isInitialized() {
        return initialized;
    }

    /**
     * Returns the active SDL video driver, for example {@code windows}, {@code x11}, {@code
     * wayland}.
     */
    public static String videoDriver() {
        return CStrings.read(SDL_GetCurrentVideoDriver());
    }

    /** Shuts SDL down. Destroy all windows first. */
    public static synchronized void shutdown() {
        if (!initialized) {
            return;
        }
        SdlThread.assertMainThread("Platform.shutdown");
        SDL_Quit();
        SdlThread.clearMainThread();
        initialized = false;
    }

    /** Returns the primary display's SDL ID. */
    public static int primaryDisplay() {
        SdlThread.assertMainThread("SDL_GetPrimaryDisplay");
        return Sdl.checkId(SDL_GetPrimaryDisplay(), "SDL_GetPrimaryDisplay");
    }

    /** Returns the connected displays, primary first (cold path: allocates). */
    public static List<DisplayInfo> displays() {
        SdlThread.assertMainThread("SDL_GetDisplays");
        int primary = SDL_GetPrimaryDisplay();
        List<DisplayInfo> result = new ArrayList<>();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment count = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment ids = Sdl.check(SDL_GetDisplays(count), "SDL_GetDisplays");
            int n = count.get(ValueLayout.JAVA_INT, 0);
            int[] idArray = new int[n];
            Sdl.copyIdsAndFree(ids, n, idArray);
            MemorySegment rect = arena.allocate(SDL_Rect.layout());
            MemorySegment usable = arena.allocate(SDL_Rect.layout());
            for (int id : idArray) {
                Sdl.check(SDL_GetDisplayBounds(id, rect), "SDL_GetDisplayBounds");
                if (!SDL_GetDisplayUsableBounds(id, usable)) {
                    usable.copyFrom(rect);
                }
                MemorySegment desktop = SDL_GetDesktopDisplayMode(id);
                DisplayInfo info =
                        new DisplayInfo(
                                id,
                                CStrings.read(SDL_GetDisplayName(id)),
                                id == primary,
                                SDL_Rect.x(rect),
                                SDL_Rect.y(rect),
                                SDL_Rect.w(rect),
                                SDL_Rect.h(rect),
                                SDL_Rect.w(usable),
                                SDL_Rect.h(usable),
                                SDL_GetDisplayContentScale(id),
                                desktop.address() == 0 ? null : readMode(desktop),
                                fullscreenModes(id, count));
                if (info.primary()) {
                    result.addFirst(info);
                } else {
                    result.add(info);
                }
            }
        }
        return result;
    }

    /**
     * Returns the display ID at {@code index} in {@link #displays()} order, or the primary display.
     */
    static int displayId(int index) {
        List<DisplayInfo> all = displays();
        return index >= 0 && index < all.size() ? all.get(index).id() : primaryDisplay();
    }

    private static List<DisplayMode> fullscreenModes(int display, MemorySegment count) {
        MemorySegment modes = SDL_GetFullscreenDisplayModes(display, count);
        if (modes.address() == 0) {
            return List.of();
        }
        int n = count.get(ValueLayout.JAVA_INT, 0);
        List<DisplayMode> result = new ArrayList<>(n);
        MemorySegment array = modes.reinterpret((long) n * ValueLayout.ADDRESS.byteSize());
        for (int i = 0; i < n; i++) {
            result.add(readMode(array.getAtIndex(ValueLayout.ADDRESS, i)));
        }
        Sdl.free(modes);
        return result;
    }

    static DisplayMode readMode(MemorySegment mode) {
        MemorySegment m = mode.reinterpret(SDL_DisplayMode.layout().byteSize());
        return new DisplayMode(
                SDL_DisplayMode.w(m),
                SDL_DisplayMode.h(m),
                SDL_DisplayMode.pixel_density(m),
                SDL_DisplayMode.refresh_rate(m));
    }

    /**
     * Shows a modal error dialog (crash reports). Works from any thread and without {@link #init};
     * returns {@code false} if no dialog could be shown (headless systems).
     */
    public static boolean showErrorMessage(String title, String message) {
        try {
            Sdl.loadNatives();
            try (Arena arena = Arena.ofConfined()) {
                return SDL_ShowSimpleMessageBox(
                        SDL_MESSAGEBOX_ERROR(),
                        arena.allocateFrom(title),
                        arena.allocateFrom(message),
                        MemorySegment.NULL);
            }
        } catch (RuntimeException | LinkageError e) {
            LOG.error("could not show the error dialog", e);
            return false;
        }
    }
}
