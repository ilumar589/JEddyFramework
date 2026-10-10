package dev.jeddy.core.natives;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;

/** Needs the native libraries: the natives jar on the test classpath, or -Djeddy.natives.dir. */
class NativeLoaderTest {

    @Test
    void platformIsDetected() {
        Platform p = Platform.current();
        assertEquals(
                System.getProperty("os.name").toLowerCase().startsWith("windows"), p.isWindows());
        assertTrue(p.id().endsWith("-x64"));
    }

    @Test
    void loadsAndCallsZstd() throws Throwable {
        NativeLoader.load("zstd");
        assertTrue(NativeLoader.isLoaded("zstd"));
        assertFalse(NativeLoader.isLoaded("ktx"));
        assertTrue(Files.isDirectory(NativeLoader.directory()));
        MemorySegment fn = SymbolLookup.loaderLookup().find("ZSTD_versionNumber").orElseThrow();
        MethodHandle version =
                Linker.nativeLinker()
                        .downcallHandle(fn, FunctionDescriptor.of(ValueLayout.JAVA_INT));
        assertEquals(10507, (int) version.invokeExact());
    }

    @Test
    void loadsDependenciesFirst() {
        NativeLoader.load("SDL3_mixer"); // depends on SDL3
        assertTrue(NativeLoader.isLoaded("SDL3"));
        assertTrue(NativeLoader.isLoaded("SDL3_mixer"));
        assertTrue(SymbolLookup.loaderLookup().find("MIX_Version").isPresent());
    }

    @Test
    void unknownLibraryIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> NativeLoader.load("nope"));
        assertTrue(NativeLoader.libraries().size() >= 3);
    }
}
