package dev.jeddy.sdl;

import dev.jeddy.core.memory.CStrings;
import dev.jeddy.sdl.ffi.SDL3;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * An owned SDL property group ({@code SDL_PropertiesID}), used to pass options to calls such as
 * {@code SDL_CreateWindowWithProperties} and {@code MIX_PlayTrack}.
 *
 * <p>Property names go through {@link CStrings#of(String)}, so each name is converted once. String
 * values are copied by SDL, so they live only for the duration of the call. Groups are cheap but
 * not free: create them during setup and reuse them in per-frame code (SDL properties are
 * thread-safe, and setting a number allocates nothing).
 *
 * <pre>{@code
 * try (SdlProperties props = SdlProperties.create()) {
 *     props.setNumber(MIX_PROP_PLAY_LOOPS_NUMBER, -1);
 *     MIX_PlayTrack(track, props.id());
 * }
 * }</pre>
 *
 * <p>Thread ownership: not thread-safe; one owner at a time.
 */
public final class SdlProperties implements AutoCloseable {
    private int id;

    private SdlProperties(int id) {
        this.id = id;
    }

    /** Creates an empty group. */
    public static SdlProperties create() {
        return new SdlProperties(Sdl.checkId(SDL3.SDL_CreateProperties(), "SDL_CreateProperties"));
    }

    /** Returns the {@code SDL_PropertiesID} to pass to SDL. */
    public int id() {
        if (id == 0) {
            throw new IllegalStateException("properties are closed");
        }
        return id;
    }

    /** Sets a number property. */
    public SdlProperties setNumber(String name, long value) {
        Sdl.check(
                SDL3.SDL_SetNumberProperty(id(), CStrings.of(name), value),
                "SDL_SetNumberProperty");
        return this;
    }

    /** Sets a float property. */
    public SdlProperties setFloat(String name, float value) {
        Sdl.check(
                SDL3.SDL_SetFloatProperty(id(), CStrings.of(name), value), "SDL_SetFloatProperty");
        return this;
    }

    /** Sets a boolean property. */
    public SdlProperties setBoolean(String name, boolean value) {
        Sdl.check(
                SDL3.SDL_SetBooleanProperty(id(), CStrings.of(name), value),
                "SDL_SetBooleanProperty");
        return this;
    }

    /** Sets a pointer property (SDL does not take ownership). */
    public SdlProperties setPointer(String name, MemorySegment value) {
        Sdl.check(
                SDL3.SDL_SetPointerProperty(id(), CStrings.of(name), value),
                "SDL_SetPointerProperty");
        return this;
    }

    /** Sets a string property; SDL copies the value. A {@code null} value clears the property. */
    public SdlProperties setString(String name, String value) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment v = value == null ? MemorySegment.NULL : arena.allocateFrom(value);
            Sdl.check(
                    SDL3.SDL_SetStringProperty(id(), CStrings.of(name), v),
                    "SDL_SetStringProperty");
        }
        return this;
    }

    /** Returns a number property, or {@code defaultValue} if it is missing. */
    public long getNumber(String name, long defaultValue) {
        return SDL3.SDL_GetNumberProperty(id(), CStrings.of(name), defaultValue);
    }

    /** Returns a float property, or {@code defaultValue} if it is missing. */
    public float getFloat(String name, float defaultValue) {
        return SDL3.SDL_GetFloatProperty(id(), CStrings.of(name), defaultValue);
    }

    /** Returns a boolean property, or {@code defaultValue} if it is missing. */
    public boolean getBoolean(String name, boolean defaultValue) {
        return SDL3.SDL_GetBooleanProperty(id(), CStrings.of(name), defaultValue);
    }

    /** Returns a pointer property, or {@code NULL} if it is missing. */
    public MemorySegment getPointer(String name) {
        return SDL3.SDL_GetPointerProperty(id(), CStrings.of(name), MemorySegment.NULL);
    }

    /** Returns a string property (allocates), or {@code null} if it is missing. */
    public String getString(String name) {
        return CStrings.read(
                SDL3.SDL_GetStringProperty(id(), CStrings.of(name), MemorySegment.NULL));
    }

    /** Returns {@code true} if the property exists. */
    public boolean has(String name) {
        return SDL3.SDL_HasProperty(id(), CStrings.of(name));
    }

    /**
     * Reads a number from any property group, for example the one returned by {@code
     * SDL_GetWindowProperties}, which SDL owns.
     */
    public static long getNumber(int props, String name, long defaultValue) {
        return SDL3.SDL_GetNumberProperty(props, CStrings.of(name), defaultValue);
    }

    /** Reads a pointer from any property group (see {@link #getNumber(int, String, long)}). */
    public static MemorySegment getPointer(int props, String name) {
        return SDL3.SDL_GetPointerProperty(props, CStrings.of(name), MemorySegment.NULL);
    }

    /** Destroys the group. Idempotent. */
    @Override
    public void close() {
        if (id != 0) {
            SDL3.SDL_DestroyProperties(id);
            id = 0;
        }
    }
}
