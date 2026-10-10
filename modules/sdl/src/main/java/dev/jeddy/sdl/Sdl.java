package dev.jeddy.sdl;

import dev.jeddy.core.memory.CStrings;
import dev.jeddy.core.natives.NativeLoader;
import dev.jeddy.sdl.ffi.SDL3;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Low-level SDL helpers shared by {@code window}, {@code input}, {@code audio} and {@code app}:
 * loading the library, turning failed calls into {@link SdlException}s, versions and hints.
 *
 * <p>The checks take the SDL function name as a constant string and do nothing else on success, so
 * they are free to use in per-frame code: {@code Sdl.check(SDL_GL_SwapWindow(w),
 * "SDL_GL_SwapWindow")}.
 *
 * <p>Thread ownership: thread-safe (SDL error state is per thread). Hints are cold-path calls.
 */
public final class Sdl {
    private Sdl() {}

    /** Loads the SDL3 shared library (idempotent). */
    public static void loadNatives() {
        NativeLoader.load("SDL3");
    }

    /** Loads SDL3 and SDL3_mixer (idempotent). */
    public static void loadMixerNatives() {
        NativeLoader.load("SDL3_mixer");
    }

    /** Returns the calling thread's {@code SDL_GetError()} text (allocates; cold path). */
    public static String error() {
        String text = CStrings.read(SDL3.SDL_GetError());
        return text == null ? "" : text;
    }

    /** Clears the calling thread's SDL error. */
    public static void clearError() {
        SDL3.SDL_ClearError();
    }

    /**
     * Throws if an SDL call that returns {@code bool} failed.
     *
     * @param ok the call's result
     * @param call the SDL function name (a constant)
     * @throws SdlException if {@code ok} is {@code false}
     */
    public static void check(boolean ok, String call) {
        if (!ok) {
            throw failure(call);
        }
    }

    /**
     * Throws if an SDL call that returns a pointer returned {@code NULL}.
     *
     * @return {@code pointer}
     * @throws SdlException if {@code pointer} is {@code NULL}
     */
    public static MemorySegment check(MemorySegment pointer, String call) {
        if (pointer.address() == 0) {
            throw failure(call);
        }
        return pointer;
    }

    /**
     * Throws if an SDL call that returns an ID (window, display, properties, device) returned 0.
     *
     * @return {@code id}
     * @throws SdlException if {@code id} is 0
     */
    public static int checkId(int id, String call) {
        if (id == 0) {
            throw failure(call);
        }
        return id;
    }

    /** Returns an exception for {@code call} with the current SDL error (cold path). */
    public static SdlException failure(String call) {
        return new SdlException(call, error());
    }

    /**
     * Returns the version of the loaded SDL library as {@code major * 1000000 + minor * 1000 +
     * micro}.
     */
    public static int version() {
        return SDL3.SDL_GetVersion();
    }

    /**
     * Returns the SDL version the bindings were generated from, encoded like {@link #version()}.
     */
    public static int bindingsVersion() {
        return SDL3.SDL_VERSION();
    }

    /** Formats an encoded SDL version as {@code major.minor.micro}. */
    public static String versionString(int version) {
        return (version / 1_000_000) + "." + (version / 1000 % 1000) + "." + (version % 1000);
    }

    /** Returns the source revision of the loaded SDL library. */
    public static String revision() {
        return CStrings.read(SDL3.SDL_GetRevision());
    }

    /**
     * Sets an SDL hint with normal priority. Call before the subsystem that reads it starts.
     *
     * @param name hint name, for example {@code SDL_VIDEO_DRIVER}
     * @param value hint value, or {@code null} to reset it to its default
     * @return {@code true} if the hint was set
     */
    public static boolean setHint(String name, String value) {
        if (value == null) {
            return SDL3.SDL_ResetHint(CStrings.of(name));
        }
        try (Arena arena = Arena.ofConfined()) {
            return SDL3.SDL_SetHint(CStrings.of(name), arena.allocateFrom(value));
        }
    }

    /** Returns the current value of an SDL hint, or {@code null} if it is not set. */
    public static String hint(String name) {
        return CStrings.read(SDL3.SDL_GetHint(CStrings.of(name)));
    }

    /** Frees memory that SDL allocated and handed to the caller ({@code SDL_free}). */
    public static void free(MemorySegment pointer) {
        if (pointer.address() != 0) {
            SDL3.SDL_free(pointer);
        }
    }

    /**
     * Copies a {@code count}-element array of 32-bit IDs that SDL returned (for example from {@code
     * SDL_GetDisplays}) into {@code out}, then frees the SDL array.
     *
     * @return the number of IDs copied, at most {@code out.length}
     */
    public static int copyIdsAndFree(MemorySegment array, int count, int[] out) {
        if (array.address() == 0) {
            return 0;
        }
        int n = Math.min(count, out.length);
        MemorySegment ids = array.reinterpret((long) count * Integer.BYTES);
        MemorySegment.copy(ids, ValueLayout.JAVA_INT, 0, out, 0, n);
        SDL3.SDL_free(array);
        return n;
    }
}
