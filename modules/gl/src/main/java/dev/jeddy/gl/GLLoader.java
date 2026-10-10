package dev.jeddy.gl;

import static dev.jeddy.gl.ffi.GL46.*;

import dev.jeddy.core.log.Log;
import dev.jeddy.core.log.Logger;
import dev.jeddy.gl.ffi.GLLinker;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * Loads OpenGL for the current context and reads its {@link GLCapabilities} (DESIGN §9.1, TASKS
 * M3-02).
 *
 * <pre>{@code
 * GLCapabilities caps = GLLoader.init(window::glProcAddress); // context current, main thread
 * }</pre>
 *
 * <p>Thread ownership: the GL context thread (the main thread).
 */
public final class GLLoader {
    private static final Logger LOG = Log.logger("gl");

    /** The oldest context Jeddy runs on. */
    public static final int REQUIRED_MAJOR = 4;

    /** The oldest context Jeddy runs on. */
    public static final int REQUIRED_MINOR = 6;

    private static volatile GLCapabilities capabilities;

    private GLLoader() {}

    /**
     * Installs {@code procAddress} as the GL function resolver and reads the capabilities.
     *
     * @param procAddress resolves a GL command name to its address ({@code NULL} if missing), for
     *     example {@code Window::glProcAddress}
     * @return the capabilities of the current context
     * @throws GLException if the context is older than OpenGL 4.6
     */
    public static GLCapabilities init(Function<String, MemorySegment> procAddress) {
        GLLinker.setResolver(procAddress);
        GLCapabilities caps = readCapabilities();
        if (!caps.atLeast(REQUIRED_MAJOR, REQUIRED_MINOR)) {
            throw new GLException(
                    "OpenGL "
                            + REQUIRED_MAJOR
                            + "."
                            + REQUIRED_MINOR
                            + " is required, the driver offers "
                            + caps.version()
                            + " ("
                            + caps.renderer()
                            + ")");
        }
        capabilities = caps;
        GLLinker.setExtensions(caps::has);
        LOG.info(
                "OpenGL {} on {} ({}), GLSL {}, {} extensions, critical linking {}",
                caps.version(),
                caps.renderer(),
                caps.vendor(),
                caps.glslVersion(),
                caps.extensions().size(),
                GLLinker.criticalEnabled() ? "on" : "off");
        return caps;
    }

    /** Returns the capabilities from {@link #init}. */
    public static GLCapabilities capabilities() {
        GLCapabilities caps = capabilities;
        if (caps == null) {
            throw new IllegalStateException("GLLoader.init has not run");
        }
        return caps;
    }

    /** Returns {@code true} once {@link #init} succeeded. */
    public static boolean isLoaded() {
        return capabilities != null;
    }

    /**
     * Returns {@code true} if the resolver returns an address for {@code command}. On GLX every
     * {@code gl*} name resolves to a stub, so check extensions with {@link
     * GLCapabilities#has(String)} instead.
     */
    public static boolean hasCommand(String command) {
        return GLLinker.address(command).address() != 0;
    }

    private static GLCapabilities readCapabilities() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment i = arena.allocate(ValueLayout.JAVA_LONG);
            int major = getInt(GL_MAJOR_VERSION, i);
            int minor = getInt(GL_MINOR_VERSION, i);
            String version = string(glGetString(GL_VERSION));
            String vendor = string(glGetString(GL_VENDOR));
            String renderer = string(glGetString(GL_RENDERER));
            String glsl = string(glGetString(GL_SHADING_LANGUAGE_VERSION));
            int count = getInt(GL_NUM_EXTENSIONS, i);
            Set<String> extensions = new HashSet<>(count * 2);
            for (int e = 0; e < count; e++) {
                extensions.add(string(glGetStringi(GL_EXTENSIONS, e)));
            }
            boolean debug = (getInt(GL_CONTEXT_FLAGS, i) & GL_CONTEXT_FLAG_DEBUG_BIT) != 0;
            glGetInteger64v(GL_MAX_SHADER_STORAGE_BLOCK_SIZE, i);
            long maxSsbo = i.get(ValueLayout.JAVA_LONG, 0);
            glGetFloatv(GL_MAX_TEXTURE_MAX_ANISOTROPY, i);
            float aniso = i.get(ValueLayout.JAVA_FLOAT, 0);
            glGetError(); // anisotropy enum is core 4.6; clear any error from older drivers
            return new GLCapabilities(
                    major,
                    minor,
                    version,
                    vendor,
                    renderer,
                    glsl,
                    GLCapabilities.classify(vendor, renderer, version),
                    debug,
                    extensions,
                    getInt(GL_MAX_TEXTURE_SIZE, i),
                    getInt(GL_MAX_ARRAY_TEXTURE_LAYERS, i),
                    getInt(GL_MAX_UNIFORM_BLOCK_SIZE, i),
                    maxSsbo,
                    getInt(GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT, i),
                    getInt(GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT, i),
                    getInt(GL_MAX_COLOR_ATTACHMENTS, i),
                    getInt(GL_MAX_SAMPLES, i),
                    getInt(GL_MAX_VERTEX_ATTRIBS, i),
                    getInt(GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS, i),
                    aniso);
        }
    }

    private static int getInt(int pname, MemorySegment scratch) {
        scratch.set(ValueLayout.JAVA_INT, 0, 0);
        glGetIntegerv(pname, scratch);
        return scratch.get(ValueLayout.JAVA_INT, 0);
    }

    static String string(MemorySegment cString) {
        if (cString.address() == 0) {
            return "";
        }
        return cString.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_8);
    }
}
