package dev.jeddy.window;

import static dev.jeddy.sdl.ffi.SDL3.*;

import dev.jeddy.sdl.Sdl;
import dev.jeddy.sdl.SdlThread;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;

/**
 * A pool of mouse cursors switched by {@code int} ID (DESIGN §12.1, TASKS M2-07). A CRPG registers
 * its color cursors once (move, attack, talk, loot, forbidden, spell targeting) and then switches
 * between them every frame for the price of one comparison, plus one SDL call when the cursor
 * actually changes.
 *
 * <p>ID {@link #DEFAULT} is the system arrow. The game thread switches cursors through the {@link
 * MainThreadQueue} ({@link WindowCommands#SET_CURSOR}).
 *
 * <p>Thread ownership: main thread only.
 */
public final class Cursors implements AutoCloseable {
    /** The system's default arrow. */
    public static final int DEFAULT = 0;

    /**
     * The {@code SDL_SYSTEM_CURSOR_*} shapes that {@link #registerSystem(SystemCursor)} accepts.
     */
    public enum SystemCursor {
        /** Default arrow. */
        DEFAULT,
        /** Text I-beam. */
        TEXT,
        /** Busy. */
        WAIT,
        /** Crosshair. */
        CROSSHAIR,
        /** Busy, but still interactive. */
        PROGRESS,
        /** Four-way move. */
        MOVE,
        /** Slashed circle. */
        NOT_ALLOWED,
        /** Pointing hand. */
        POINTER;

        int sdlId() {
            return switch (this) {
                case DEFAULT -> SDL_SYSTEM_CURSOR_DEFAULT();
                case TEXT -> SDL_SYSTEM_CURSOR_TEXT();
                case WAIT -> SDL_SYSTEM_CURSOR_WAIT();
                case CROSSHAIR -> SDL_SYSTEM_CURSOR_CROSSHAIR();
                case PROGRESS -> SDL_SYSTEM_CURSOR_PROGRESS();
                case MOVE -> SDL_SYSTEM_CURSOR_MOVE();
                case NOT_ALLOWED -> SDL_SYSTEM_CURSOR_NOT_ALLOWED();
                case POINTER -> SDL_SYSTEM_CURSOR_POINTER();
            };
        }
    }

    private MemorySegment[] cursors = new MemorySegment[8];
    private boolean[] owned = new boolean[8];
    private int count;
    private int current = -1;
    private boolean visible = true;

    /** Creates the pool with the system arrow as {@link #DEFAULT}. */
    public Cursors() {
        SdlThread.assertMainThread("Cursors");
        MemorySegment arrow = SDL_GetDefaultCursor();
        add(arrow.address() != 0 ? arrow : MemorySegment.NULL, false);
    }

    /**
     * Registers a color cursor (cold path).
     *
     * @param width width in pixels
     * @param height height in pixels
     * @param rgba {@code width * height * 4} bytes of straight-alpha RGBA8, top row first
     * @param hotX hot spot x in pixels from the left
     * @param hotY hot spot y in pixels from the top
     * @return the cursor ID
     */
    public int registerColor(int width, int height, byte[] rgba, int hotX, int hotY) {
        SdlThread.assertMainThread("SDL_CreateColorCursor");
        if (rgba.length < width * height * 4) {
            throw new IllegalArgumentException("cursor needs " + width * height * 4 + " bytes");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pixels = arena.allocateFrom(ValueLayout.JAVA_BYTE, rgba);
            MemorySegment surface =
                    Sdl.check(
                            SDL_CreateSurfaceFrom(
                                    width, height, SDL_PIXELFORMAT_RGBA32(), pixels, width * 4),
                            "SDL_CreateSurfaceFrom");
            try {
                MemorySegment cursor =
                        Sdl.check(
                                SDL_CreateColorCursor(surface, hotX, hotY),
                                "SDL_CreateColorCursor");
                return add(cursor, true);
            } finally {
                SDL_DestroySurface(surface);
            }
        }
    }

    /**
     * Registers one of the system cursors (cold path) and returns its ID. Where the video driver
     * has no system cursors the ID still works and switching to it keeps the current cursor.
     */
    public int registerSystem(SystemCursor shape) {
        SdlThread.assertMainThread("SDL_CreateSystemCursor");
        MemorySegment cursor = SDL_CreateSystemCursor(shape.sdlId());
        return add(cursor.address() != 0 ? cursor : MemorySegment.NULL, cursor.address() != 0);
    }

    private int add(MemorySegment cursor, boolean owns) {
        if (count == cursors.length) {
            cursors = Arrays.copyOf(cursors, count * 2);
            owned = Arrays.copyOf(owned, count * 2);
        }
        cursors[count] = cursor;
        owned[count] = owns;
        return count++;
    }

    /** Returns the number of registered cursors, including {@link #DEFAULT}. */
    public int count() {
        return count;
    }

    /**
     * Makes {@code id} the active cursor. Does nothing if it already is; allocates nothing.
     *
     * @throws IllegalArgumentException for an unknown ID
     */
    public void set(int id) {
        SdlThread.assertMainThread("SDL_SetCursor");
        if (id == current) {
            return;
        }
        if (id < 0 || id >= count) {
            throw new IllegalArgumentException("unknown cursor " + id);
        }
        MemorySegment cursor = cursors[id];
        if (cursor.address() != 0) {
            SDL_SetCursor(cursor);
        }
        current = id;
    }

    /** Returns the active cursor ID, or -1 before the first {@link #set(int)}. */
    public int current() {
        return current;
    }

    /** Shows or hides the cursor. */
    public void setVisible(boolean show) {
        SdlThread.assertMainThread("SDL_ShowCursor");
        if (show != visible) {
            if (show) {
                SDL_ShowCursor();
            } else {
                SDL_HideCursor();
            }
            visible = show;
        }
    }

    /** Returns whether the cursor is shown. */
    public boolean isVisible() {
        return visible;
    }

    /** Destroys every cursor the pool created. */
    @Override
    public void close() {
        SdlThread.assertMainThread("SDL_DestroyCursor");
        for (int i = 0; i < count; i++) {
            if (owned[i] && cursors[i].address() != 0) {
                SDL_DestroyCursor(cursors[i]);
            }
            cursors[i] = MemorySegment.NULL;
        }
        count = 0;
        current = -1;
    }
}
