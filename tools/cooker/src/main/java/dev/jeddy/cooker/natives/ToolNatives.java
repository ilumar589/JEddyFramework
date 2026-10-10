package dev.jeddy.cooker.natives;

import dev.jeddy.core.natives.NativeLoader;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Loads the cooker's native libraries (libktx, meshoptimizer, the MikkTSpace shim) from the natives
 * jar before any binding is used (DESIGN §6.3), and binds the C library's {@code free} for memory
 * that libktx hands back.
 *
 * <p>Thread ownership: thread-safe.
 */
public final class ToolNatives {
    private ToolNatives() {}

    private static volatile boolean loaded;

    /** Loads the libraries once. */
    public static void load() {
        if (!loaded) {
            synchronized (ToolNatives.class) {
                if (!loaded) {
                    NativeLoader.load("ktx", "meshoptimizer", "jeddy_tools_native");
                    loaded = true;
                }
            }
        }
    }

    private static final class Free {
        static final MethodHandle FREE =
                Linker.nativeLinker()
                        .downcallHandle(
                                Linker.nativeLinker().defaultLookup().findOrThrow("free"),
                                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
    }

    /** Frees memory allocated by a native library with {@code malloc}. */
    public static void free(MemorySegment pointer) {
        try {
            Free.FREE.invokeExact(pointer);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }
}
