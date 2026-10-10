package dev.jeddy.sdl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.sdl.ffi.SDL3;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicReference;

class SdlTest {
    private long sink;

    @BeforeAll
    static void load() {
        Sdl.loadNatives();
    }

    @Test
    void failedCallsBecomeExceptionsWithTheSdlError() {
        Sdl.clearError();
        // The video subsystem is not initialized in this test, so this fails and sets an error.
        MemorySegment name = SDL3.SDL_GetDisplayName(12345);
        SdlException e =
                assertThrows(SdlException.class, () -> Sdl.check(name, "SDL_GetDisplayName"));
        assertEquals("SDL_GetDisplayName", e.call());
        assertFalse(e.sdlError().isEmpty(), "SDL error text");
        assertTrue(e.getMessage().startsWith("SDL_GetDisplayName failed: "));
        assertThrows(SdlException.class, () -> Sdl.check(false, "x"));
        assertThrows(SdlException.class, () -> Sdl.checkId(0, "x"));
        assertEquals(7, Sdl.checkId(7, "x"));
    }

    @Test
    void checksAllocateNothingOnSuccess() {
        MemorySegment p = MemorySegment.ofAddress(64);
        Allocations.assertNoAllocation(
                "Sdl.check",
                () -> {
                    Sdl.check(true, "SDL_GL_SwapWindow");
                    sink += Sdl.check(p, "SDL_GetWindowFromID").address();
                    sink += Sdl.checkId(3, "SDL_GetPrimaryDisplay");
                });
    }

    @Test
    void downcallsAllocateNothing() {
        Allocations.assertNoAllocation("SDL_GetTicksNS", () -> sink += SDL3.SDL_GetTicksNS());
    }

    @Test
    void versionsAreReadable() {
        assertTrue(Sdl.version() >= 3_004_000, Sdl.versionString(Sdl.version()));
        assertEquals("3.4.18", Sdl.versionString(3_004_018));
        assertTrue(Sdl.revision() != null);
    }

    @Test
    void hintsRoundTrip() {
        assertTrue(Sdl.setHint("SDL_JEDDY_TEST_HINT", "on"));
        assertEquals("on", Sdl.hint("SDL_JEDDY_TEST_HINT"));
        Sdl.setHint("SDL_JEDDY_TEST_HINT", null);
        assertNull(Sdl.hint("SDL_JEDDY_TEST_HINT"));
    }

    @Test
    void propertiesRoundTrip() {
        try (SdlProperties props = SdlProperties.create()) {
            props.setNumber("n", 42)
                    .setFloat("f", 1.5f)
                    .setBoolean("b", true)
                    .setString("s", "jeddy")
                    .setPointer("p", MemorySegment.ofAddress(0x1000));
            assertEquals(42, props.getNumber("n", -1));
            assertEquals(1.5f, props.getFloat("f", 0));
            assertTrue(props.getBoolean("b", false));
            assertEquals("jeddy", props.getString("s"));
            assertEquals(0x1000, props.getPointer("p").address());
            assertTrue(props.has("s"));
            assertFalse(props.has("missing"));
            assertEquals(-1, props.getNumber("missing", -1));
            assertEquals(42, SdlProperties.getNumber(props.id(), "n", 0));
        }
        SdlProperties closed = SdlProperties.create();
        closed.close();
        closed.close(); // idempotent
        assertThrows(IllegalStateException.class, closed::id);
    }

    @Test
    void mainThreadAssertionsFailFastOnOtherThreads() throws Exception {
        SdlThread.clearMainThread();
        assertThrows(
                IllegalStateException.class, () -> SdlThread.assertMainThread("SDL_PollEvent"));
        SdlThread.markMainThread();
        try {
            SdlThread.assertMainThread("SDL_PollEvent");
            assertTrue(SdlThread.isMainThread());
            AtomicReference<Throwable> fromOther = new AtomicReference<>();
            Thread other =
                    new Thread(
                            () -> {
                                try {
                                    SdlThread.assertMainThread("SDL_SetCursor");
                                } catch (Throwable t) {
                                    fromOther.set(t);
                                }
                                try {
                                    SdlThread.markMainThread();
                                } catch (IllegalStateException expected) {
                                    return;
                                }
                                fromOther.set(new AssertionError("main thread moved"));
                            },
                            "game");
            other.start();
            other.join();
            Throwable t = fromOther.get();
            assertTrue(t instanceof IllegalStateException, String.valueOf(t));
            assertTrue(t.getMessage().contains("SDL_SetCursor"), t.getMessage());
            assertTrue(t.getMessage().contains("'game'"), t.getMessage());
            Allocations.assertNoAllocation(
                    "SdlThread.assertMainThread",
                    () -> SdlThread.assertMainThread("SDL_PollEvent"));
        } finally {
            SdlThread.clearMainThread();
        }
    }

    @Test
    void eventTypeConstantsMatchTheBindings() throws Exception {
        int checked = 0;
        for (Field f : SdlEventType.class.getFields()) {
            if (f.getType() == int.class && Modifier.isStatic(f.getModifiers())) {
                // The getters are inherited from package-private split classes (SDL3_1, ...).
                Method getter = SDL3.class.getMethod("SDL_EVENT_" + f.getName());
                getter.setAccessible(true);
                Object sdl = getter.invoke(null);
                assertEquals(sdl, f.getInt(null), f.getName());
                checked++;
            }
        }
        assertTrue(checked > 40, "checked " + checked);
        assertTrue(SdlEventType.isWindowEvent(SdlEventType.WINDOW_RESIZED));
        assertFalse(SdlEventType.isWindowEvent(SdlEventType.KEY_DOWN));
        assertTrue(SdlEventType.isDisplayEvent(SdlEventType.DISPLAY_ADDED));
    }

    @Test
    void idArraysAreCopiedAndFreed() {
        MemorySegment array = SDL3.SDL_malloc(3 * Integer.BYTES).reinterpret(3 * Integer.BYTES);
        array.setAtIndex(ValueLayout.JAVA_INT, 0, 11);
        array.setAtIndex(ValueLayout.JAVA_INT, 1, 22);
        array.setAtIndex(ValueLayout.JAVA_INT, 2, 33);
        int[] out = new int[2];
        assertEquals(2, Sdl.copyIdsAndFree(array, 3, out));
        assertEquals(11, out[0]);
        assertEquals(22, out[1]);
        assertEquals(0, Sdl.copyIdsAndFree(MemorySegment.NULL, 3, out));
    }
}
