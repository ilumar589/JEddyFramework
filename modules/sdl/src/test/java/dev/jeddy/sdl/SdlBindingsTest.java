package dev.jeddy.sdl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.sdl.ffi.SDL3;
import dev.jeddy.sdl.ffi.SDL_AudioSpec;
import dev.jeddy.sdl.ffi.SDL_DisplayMode;
import dev.jeddy.sdl.ffi.SDL_Event;
import dev.jeddy.sdl.ffi.SDL_KeyboardEvent;
import dev.jeddy.sdl.ffi.SDL_MouseMotionEvent;
import dev.jeddy.sdl.ffi.SDL_Rect;
import dev.jeddy.sdl.mixer.ffi.MIX_Point3D;
import dev.jeddy.sdl.mixer.ffi.SDL3Mixer;
import dev.jeddy.testing.GeneratedBindings;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The portability check for the generated bindings (TASKS M2-01, DESIGN §6.4, D22). CI runs it on
 * Windows and Linux against the shipped natives: every generated class and every per-function
 * holder is initialized, so a missing symbol, a bad function descriptor or a layout that does not
 * fit the platform ABI fails here rather than at the first call in a game.
 */
class SdlBindingsTest {
    @BeforeAll
    static void load() {
        Sdl.loadMixerNatives();
    }

    @Test
    void everySdlBindingResolves() {
        int initialized =
                GeneratedBindings.initializeAll(SdlBindingsTest.class, "dev.jeddy.sdl.ffi");
        assertTrue(initialized > 400, "only " + initialized + " SDL classes initialized");
    }

    @Test
    void everyMixerBindingResolves() {
        int initialized =
                GeneratedBindings.initializeAll(SdlBindingsTest.class, "dev.jeddy.sdl.mixer.ffi");
        assertTrue(initialized > 100, "only " + initialized + " mixer classes initialized");
    }

    @Test
    void loadedLibrariesMatchTheHeaders() {
        assertEquals(
                Sdl.versionString(Sdl.bindingsVersion()),
                Sdl.versionString(Sdl.version()),
                "SDL3 headers in bindings/ and the shipped library differ");
        assertEquals(SDL3Mixer.SDL_MIXER_VERSION(), SDL3Mixer.MIX_Version(), "SDL3_mixer version");
    }

    @Test
    void layoutsMatchTheSdlAbi() {
        assertEquals(128, SDL_Event.layout().byteSize(), "SDL_Event is a 128-byte union");
        assertEquals(12, SDL_AudioSpec.layout().byteSize());
        assertEquals(16, SDL_Rect.layout().byteSize());
        assertEquals(12, MIX_Point3D.layout().byteSize());
        assertEquals(40, SDL_DisplayMode.layout().byteSize());
        // Field offsets the event pump reads (DESIGN §12.2).
        assertEquals(0, SDL_Event.type$offset());
        assertEquals(8, SDL_KeyboardEvent.timestamp$offset());
        assertEquals(24, SDL_KeyboardEvent.scancode$offset());
        assertEquals(28, SDL_KeyboardEvent.key$offset());
        assertEquals(24, SDL_MouseMotionEvent.state$offset());
        assertEquals(28, SDL_MouseMotionEvent.x$offset());
        assertEquals(8, SDL3.C_LONG_LONG.byteSize());
    }
}
