package dev.jeddy.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import org.junit.jupiter.api.Test;

/** Smoke tests that prove the toolchain and FFM native access work on every CI platform. */
class JeddyRuntimeTest {

    @Test
    void runsOnRequiredJavaRelease() {
        assertTrue(Runtime.version().feature() >= Jeddy.REQUIRED_JAVA_FEATURE, "running on " + Runtime.version());
        assertDoesNotThrow(Jeddy::checkRuntime);
    }

    @Test
    void ffmDowncallToTheCRuntimeWorks() throws Throwable {
        Linker linker = Linker.nativeLinker();
        MemorySegment strlenAddress = linker.defaultLookup().find("strlen").orElseThrow();
        MethodHandle strlen =
                linker.downcallHandle(strlenAddress, FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment text = arena.allocateFrom("jeddy");
            assertEquals(5L, (long) strlen.invokeExact(text));
        }
    }
}
