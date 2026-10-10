package dev.jeddy.gl;

import static dev.jeddy.gl.ffi.GL46.*;

import dev.jeddy.core.log.Log;
import dev.jeddy.core.log.LogLevel;
import dev.jeddy.core.log.Logger;
import dev.jeddy.core.memory.CStrings;
import dev.jeddy.gl.ffi.GLLinker;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code KHR_debug} output, object labels and debug groups (DESIGN §9.1, TASKS M3-03).
 *
 * <p>{@link #enable(boolean)} installs one debug callback, an upcall stub created once for the
 * whole run. In synchronous mode the driver calls it inside the GL call that raised the message, so
 * the logged Java stack trace points at the offending call. That is also why critical linking must
 * be off while it is enabled ({@code -Djeddy.gl.critical=false}, DESIGN §4.4): an upcall from a
 * critical downcall can crash the JVM. {@link #enable} refuses to run otherwise.
 *
 * <p>Labels and debug groups show up in RenderDoc and Nsight captures. {@link #pushGroup(String)}
 * interns its name with {@link CStrings#of(String)}, so a fixed set of group names costs nothing
 * per frame.
 *
 * <p>Thread ownership: the GL context thread; the counters are thread-safe.
 */
public final class GLDebug {
    private static final Logger LOG = Log.logger("gl");
    private static final AtomicLong ERRORS = new AtomicLong();
    private static final AtomicLong WARNINGS = new AtomicLong();
    private static MemorySegment callback;
    private static volatile boolean groupsEnabled;

    private GLDebug() {}

    /**
     * Enables debug output and installs the callback (once).
     *
     * @param synchronous report inside the offending call, with a Java stack trace (dev builds)
     * @return {@code false} if the context is not a debug context (output may then be empty)
     * @throws IllegalStateException if critical linking is on, which a callback cannot coexist with
     */
    public static synchronized boolean enable(boolean synchronous) {
        if (GLLinker.criticalEnabled()) {
            throw new IllegalStateException(
                    "KHR_debug needs -Djeddy.gl.critical=false (DESIGN §4.4)");
        }
        if (callback == null) {
            try {
                MethodHandle target =
                        MethodHandles.lookup()
                                .findStatic(
                                        GLDebug.class,
                                        "onMessage",
                                        MethodType.methodType(
                                                void.class,
                                                int.class,
                                                int.class,
                                                int.class,
                                                int.class,
                                                int.class,
                                                MemorySegment.class,
                                                MemorySegment.class));
                callback =
                        Linker.nativeLinker()
                                .upcallStub(
                                        target,
                                        FunctionDescriptor.ofVoid(
                                                ValueLayout.JAVA_INT,
                                                ValueLayout.JAVA_INT,
                                                ValueLayout.JAVA_INT,
                                                ValueLayout.JAVA_INT,
                                                ValueLayout.JAVA_INT,
                                                ValueLayout.ADDRESS,
                                                ValueLayout.ADDRESS),
                                        Arena.global());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        glEnable(GL_DEBUG_OUTPUT);
        if (synchronous) {
            glEnable(GL_DEBUG_OUTPUT_SYNCHRONOUS);
        } else {
            glDisable(GL_DEBUG_OUTPUT_SYNCHRONOUS);
        }
        glDebugMessageCallback(callback, MemorySegment.NULL);
        // Notifications (buffer placement and the like) are noise in normal runs.
        glDebugMessageControl(
                GL_DONT_CARE,
                GL_DONT_CARE,
                GL_DEBUG_SEVERITY_NOTIFICATION,
                0,
                MemorySegment.NULL,
                false);
        groupsEnabled = true;
        return GLLoader.isLoaded() && GLLoader.capabilities().debugContext();
    }

    /** Stops debug output. */
    public static synchronized void disable() {
        glDebugMessageCallback(MemorySegment.NULL, MemorySegment.NULL);
        glDisable(GL_DEBUG_OUTPUT);
        groupsEnabled = false;
    }

    /** Returns the number of high-severity messages and errors reported so far. */
    public static long errorCount() {
        return ERRORS.get();
    }

    /** Returns the number of medium- and low-severity messages reported so far. */
    public static long warningCount() {
        return WARNINGS.get();
    }

    /**
     * Names a GL object for debuggers (cold path).
     *
     * @param identifier {@code GL_BUFFER}, {@code GL_TEXTURE}, {@code GL_PROGRAM}, ...
     * @param name the object name
     * @param label the label
     */
    public static void label(int identifier, int name, String label) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment text = arena.allocateFrom(label, StandardCharsets.UTF_8);
            glObjectLabel(identifier, name, (int) text.byteSize() - 1, text);
        }
    }

    /** Opens a named debug group (a pass in a frame capture). No-op unless enabled. */
    public static void pushGroup(String name) {
        if (groupsEnabled) {
            glPushDebugGroup(GL_DEBUG_SOURCE_APPLICATION, 0, -1, CStrings.of(name));
        }
    }

    /** Closes the innermost debug group. */
    public static void popGroup() {
        if (groupsEnabled) {
            glPopDebugGroup();
        }
    }

    /** The debug callback. Must never throw: an exception escaping an upcall ends the JVM. */
    @SuppressWarnings("unused") // called from native code
    private static void onMessage(
            int source,
            int type,
            int id,
            int severity,
            int length,
            MemorySegment message,
            MemorySegment userParam) {
        try {
            LogLevel level;
            if (severity == GL_DEBUG_SEVERITY_HIGH || type == GL_DEBUG_TYPE_ERROR) {
                level = LogLevel.ERROR;
                ERRORS.incrementAndGet();
            } else if (severity == GL_DEBUG_SEVERITY_MEDIUM || severity == GL_DEBUG_SEVERITY_LOW) {
                level = LogLevel.WARN;
                WARNINGS.incrementAndGet();
            } else {
                level = LogLevel.DEBUG;
            }
            if (!LOG.isEnabled(level)) {
                return;
            }
            // length excludes the terminating NUL, which the spec guarantees.
            long bytes = length >= 0 ? length + 1L : Long.MAX_VALUE;
            String text = message.reinterpret(bytes).getString(0, StandardCharsets.UTF_8);
            Throwable where =
                    level == LogLevel.ERROR ? new Throwable("GL call that raised it") : null;
            LOG.log(
                    level,
                    where,
                    "GL {} {} #{}: {}",
                    sourceName(source),
                    typeName(type),
                    id,
                    text.strip());
        } catch (Throwable t) {
            // Swallow: nothing may propagate into the driver.
        }
    }

    private static String sourceName(int source) {
        return switch (source) {
            case GL_DEBUG_SOURCE_API -> "api";
            case GL_DEBUG_SOURCE_WINDOW_SYSTEM -> "window";
            case GL_DEBUG_SOURCE_SHADER_COMPILER -> "shader";
            case GL_DEBUG_SOURCE_THIRD_PARTY -> "third-party";
            case GL_DEBUG_SOURCE_APPLICATION -> "app";
            default -> "other";
        };
    }

    private static String typeName(int type) {
        return switch (type) {
            case GL_DEBUG_TYPE_ERROR -> "error";
            case GL_DEBUG_TYPE_DEPRECATED_BEHAVIOR -> "deprecated";
            case GL_DEBUG_TYPE_UNDEFINED_BEHAVIOR -> "undefined";
            case GL_DEBUG_TYPE_PORTABILITY -> "portability";
            case GL_DEBUG_TYPE_PERFORMANCE -> "performance";
            case GL_DEBUG_TYPE_MARKER -> "marker";
            default -> "other";
        };
    }
}
