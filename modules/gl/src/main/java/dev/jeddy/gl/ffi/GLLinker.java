package dev.jeddy.gl.ffi;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Links the generated {@link GL46} commands (DESIGN §9.1). Every command's holder class calls
 * {@link #link} once, on first use, so the resolver must be installed (by {@code GLLoader.init})
 * while a context is current.
 *
 * <p>Critical linking ({@code Linker.Option.critical(true)}) applies to the allow-list in {@code
 * gl.properties} unless the system property {@code jeddy.gl.critical} is {@code false}. Dev builds
 * turn it off because the synchronous {@code KHR_debug} callback is an upcall from inside the GL
 * call, which must not happen during a critical downcall (DESIGN §4.4). The property is read once,
 * when this class initializes.
 *
 * <p>A command the driver does not export, or whose extension it lacks, links to a handle that
 * throws {@link UnsupportedOperationException} when called, so optional extensions never fail at
 * link time.
 *
 * <p>Thread ownership: the GL context thread.
 */
public final class GLLinker {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final boolean CRITICAL =
            !"false".equalsIgnoreCase(System.getProperty("jeddy.gl.critical", "true"));

    private static volatile Function<String, MemorySegment> resolver;
    private static volatile Predicate<String> extensions = name -> true;

    private GLLinker() {}

    /**
     * Installs the function resolver (for example {@code SDL_GL_GetProcAddress}). Call before the
     * first GL command, with the context current.
     */
    public static void setResolver(Function<String, MemorySegment> resolve) {
        resolver = resolve;
    }

    /**
     * Installs the test for supported extensions. Commands that only an extension provides link to
     * a throwing handle when the extension is missing: GLX returns a non-NULL stub for any name, so
     * the address alone does not tell.
     */
    public static void setExtensions(Predicate<String> supported) {
        extensions = supported;
    }

    /** Returns {@code true} if a resolver is installed. */
    public static boolean hasResolver() {
        return resolver != null;
    }

    /** Returns {@code true} if critical linking is enabled for allow-listed commands. */
    public static boolean criticalEnabled() {
        return CRITICAL;
    }

    /** Returns the address of a GL command, or {@code NULL} if the driver lacks it. */
    public static MemorySegment address(String name) {
        Function<String, MemorySegment> r = resolver;
        if (r == null) {
            throw new IllegalStateException(
                    "GL is not loaded: call GLLoader.init with a current context first");
        }
        MemorySegment address = r.apply(name);
        return address == null ? MemorySegment.NULL : address;
    }

    /** Called by the generated holders of extension commands. */
    public static MethodHandle link(
            String name, FunctionDescriptor descriptor, boolean critical, String extension) {
        if (!extensions.test(extension)) {
            return missing(name + " (" + extension + ")", descriptor.toMethodType());
        }
        return link(name, descriptor, critical);
    }

    /** Called by the generated holders: links {@code name} with {@code descriptor}. */
    public static MethodHandle link(String name, FunctionDescriptor descriptor, boolean critical) {
        MemorySegment address = address(name);
        if (address.address() == 0) {
            return missing(name, descriptor.toMethodType());
        }
        if (critical && CRITICAL) {
            return LINKER.downcallHandle(address, descriptor, Linker.Option.critical(true));
        }
        return LINKER.downcallHandle(address, descriptor);
    }

    private static MethodHandle missing(String name, MethodType type) {
        MethodHandle thrower =
                MethodHandles.throwException(
                        type.returnType(), UnsupportedOperationException.class);
        MethodHandle bound =
                MethodHandles.insertArguments(
                        thrower,
                        0,
                        new UnsupportedOperationException(
                                name + " is not available in this OpenGL driver"));
        return MethodHandles.dropArguments(bound, 0, type.parameterList());
    }

    /** Called by the generated methods to rethrow what {@code invokeExact} may throw. */
    public static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) {
            return r;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return new IllegalStateException(t);
    }
}
