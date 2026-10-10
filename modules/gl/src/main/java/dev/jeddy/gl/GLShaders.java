package dev.jeddy.gl;

import static dev.jeddy.gl.ffi.GL46.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/**
 * Compiling and linking GLSL programs, with the driver's info log in the exception on failure. The
 * shader system with includes, permutations and binary caching (TASKS M5-05) builds on it.
 *
 * <p>Thread ownership: the GL context thread; cold path.
 */
public final class GLShaders {
    private GLShaders() {}

    /**
     * Compiles one shader stage.
     *
     * @param type {@code GL_VERTEX_SHADER}, {@code GL_FRAGMENT_SHADER}, {@code
     *     GL_COMPUTE_SHADER}...
     * @param source GLSL source
     * @return the shader name
     * @throws GLException with the info log if compilation fails
     */
    public static int compile(int type, String source) {
        int shader = glCreateShader(type);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment text = arena.allocateFrom(source, StandardCharsets.UTF_8);
            MemorySegment strings = arena.allocateFrom(ValueLayout.ADDRESS, text);
            glShaderSource(shader, 1, strings, MemorySegment.NULL);
            glCompileShader(shader);
            MemorySegment status = arena.allocate(ValueLayout.JAVA_INT);
            glGetShaderiv(shader, GL_COMPILE_STATUS, status);
            if (status.get(ValueLayout.JAVA_INT, 0) == GL_FALSE) {
                String log = infoLog(shader, true, arena);
                glDeleteShader(shader);
                throw new GLException("shader compilation failed:\n" + log);
            }
        }
        return shader;
    }

    /**
     * Links a program from shader names (the shaders are detached and deleted afterwards).
     *
     * @throws GLException with the info log if linking fails
     */
    public static int link(int... shaders) {
        int program = glCreateProgram();
        for (int s : shaders) {
            glAttachShader(program, s);
        }
        glLinkProgram(program);
        for (int s : shaders) {
            glDetachShader(program, s);
            glDeleteShader(s);
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment status = arena.allocate(ValueLayout.JAVA_INT);
            glGetProgramiv(program, GL_LINK_STATUS, status);
            if (status.get(ValueLayout.JAVA_INT, 0) == GL_FALSE) {
                String log = infoLog(program, false, arena);
                glDeleteProgram(program);
                throw new GLException("program link failed:\n" + log);
            }
        }
        return program;
    }

    /** Compiles and links a vertex + fragment program. */
    public static int program(String vertexSource, String fragmentSource) {
        int vs = compile(GL_VERTEX_SHADER, vertexSource);
        int fs;
        try {
            fs = compile(GL_FRAGMENT_SHADER, fragmentSource);
        } catch (GLException e) {
            glDeleteShader(vs);
            throw e;
        }
        return link(vs, fs);
    }

    private static String infoLog(int object, boolean shader, Arena arena) {
        MemorySegment length = arena.allocate(ValueLayout.JAVA_INT);
        if (shader) {
            glGetShaderiv(object, GL_INFO_LOG_LENGTH, length);
        } else {
            glGetProgramiv(object, GL_INFO_LOG_LENGTH, length);
        }
        int n = Math.max(1, length.get(ValueLayout.JAVA_INT, 0));
        MemorySegment log = arena.allocate(n);
        if (shader) {
            glGetShaderInfoLog(object, n, MemorySegment.NULL, log);
        } else {
            glGetProgramInfoLog(object, n, MemorySegment.NULL, log);
        }
        return log.getString(0, StandardCharsets.UTF_8).strip();
    }
}
