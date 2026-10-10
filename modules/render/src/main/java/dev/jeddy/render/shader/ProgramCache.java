package dev.jeddy.render.shader;

import static dev.jeddy.gl.ffi.GL46.*;

import dev.jeddy.asset.shader.ShaderException;
import dev.jeddy.asset.shader.ShaderPreprocessor;
import dev.jeddy.asset.shader.ShaderSources;
import dev.jeddy.core.collections.LongIntMap;
import dev.jeddy.core.log.Log;
import dev.jeddy.core.log.Logger;
import dev.jeddy.gl.GLCapabilities;
import dev.jeddy.gl.GLDebug;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Programs by (definition, permutation mask), compiled on first request (TASKS M5-05, DESIGN §9.2).
 * With {@code KHR_parallel_shader_compile} the driver compiles in the background and {@link #get}
 * returns 0 until the program is ready, so a frame never blocks on a compile; without it compiles
 * are synchronous. Linked programs are stored in a {@link ProgramBinaryCache} when one is given, so
 * the next start skips compilation.
 *
 * <p>Thread ownership: the GL thread.
 */
public final class ProgramCache implements AutoCloseable {
    private static final Logger LOG = Log.logger("render.shader");
    private static final int COMPILING = 1;
    private static final int READY = 2;
    private static final int FAILED = 3;

    /** A program definition: shader paths and the meaning of the mask bits. */
    private record Definition(
            String name, String vertex, String fragment, String compute, ShaderFeatures features) {}

    private final ShaderPreprocessor preprocessor;
    private final ProgramBinaryCache binaries;
    private final boolean parallel;
    private final List<Definition> definitions = new ArrayList<>();
    private final List<LongIntMap> variants = new ArrayList<>(); // mask → record index
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment scratch = arena.allocate(16);

    // Program records (SoA).
    private int count;
    private int[] programs = new int[16];
    private int[] states = new int[16];
    private int[][] shaders = new int[16][];
    private String[] keys = new String[16];
    private String[] labels = new String[16];
    private ShaderPreprocessor.Result[][] sources = new ShaderPreprocessor.Result[16][];
    private long binaryLoads;
    private long compiles;

    /**
     * Creates a cache.
     *
     * @param binaries the on-disk binary cache, or {@code null}
     */
    public ProgramCache(ShaderSources sources, GLCapabilities caps, ProgramBinaryCache binaries) {
        this.preprocessor = new ShaderPreprocessor(sources);
        this.binaries = binaries;
        this.parallel =
                caps.has("GL_KHR_parallel_shader_compile")
                        && !Boolean.getBoolean("jeddy.shaders.sync");
        if (parallel) {
            glMaxShaderCompilerThreadsKHR(0xFFFFFFFF); // let the driver choose
        }
    }

    /** Defines a graphics program; returns its id for {@link #get}. */
    public int define(
            String name, String vertexPath, String fragmentPath, ShaderFeatures features) {
        definitions.add(new Definition(name, vertexPath, fragmentPath, null, features));
        variants.add(new LongIntMap(8, -1));
        return definitions.size() - 1;
    }

    /** Defines a compute program; returns its id for {@link #get}. */
    public int defineCompute(String name, String computePath, ShaderFeatures features) {
        definitions.add(new Definition(name, null, null, computePath, features));
        variants.add(new LongIntMap(8, -1));
        return definitions.size() - 1;
    }

    /** Returns {@code true} if programs compile in the background. */
    public boolean isParallel() {
        return parallel;
    }

    /**
     * Returns the GL program for {@code definition} with {@code mask}, or 0 while it compiles.
     * Starts the compile on first request; allocates nothing once the variant exists.
     *
     * @throws ShaderException if the variant failed to compile or link
     */
    public int get(int definition, long mask) {
        LongIntMap map = variants.get(definition);
        int r = map.get(mask);
        if (r < 0) {
            r = create(definition, mask);
            map.put(mask, r);
        }
        if (states[r] == COMPILING) {
            poll(r);
        }
        return switch (states[r]) {
            case READY -> programs[r];
            case FAILED -> throw new ShaderException(labels[r] + " failed to build (see log)");
            default -> 0;
        };
    }

    /** Like {@link #get} but waits for the compile to finish. */
    public int getBlocking(int definition, long mask) {
        int p;
        while ((p = get(definition, mask)) == 0) {
            Thread.onSpinWait();
        }
        return p;
    }

    /** Returns the number of programs loaded from the binary cache. */
    public long binaryLoads() {
        return binaryLoads;
    }

    /** Returns the number of programs compiled from source. */
    public long compiles() {
        return compiles;
    }

    private int create(int definition, long mask) {
        Definition d = definitions.get(definition);
        String label = d.name + "[" + Long.toHexString(mask) + "]";
        List<String> defines = d.features.defines(mask);
        ShaderPreprocessor.Result[] stages;
        try {
            stages =
                    d.compute != null
                            ? new ShaderPreprocessor.Result[] {
                                preprocessor.process(d.compute, defines)
                            }
                            : new ShaderPreprocessor.Result[] {
                                preprocessor.process(d.vertex, defines),
                                preprocessor.process(d.fragment, defines)
                            };
        } catch (IOException e) {
            throw new UncheckedIOException(label + ": " + e.getMessage(), e);
        }
        int r = count++;
        if (r == programs.length) {
            int cap = r * 2;
            programs = Arrays.copyOf(programs, cap);
            states = Arrays.copyOf(states, cap);
            shaders = Arrays.copyOf(shaders, cap);
            keys = Arrays.copyOf(keys, cap);
            labels = Arrays.copyOf(labels, cap);
            sources = Arrays.copyOf(sources, cap);
        }
        labels[r] = label;
        sources[r] = stages;
        int program = glCreateProgram();
        programs[r] = program;
        GLDebug.label(GL_PROGRAM, program, label);
        if (binaries != null) {
            String[] texts = new String[stages.length];
            for (int i = 0; i < stages.length; i++) {
                texts[i] = stages[i].source();
            }
            keys[r] = binaries.key(texts);
            if (binaries.load(keys[r], program)) {
                states[r] = READY;
                binaryLoads++;
                return r;
            }
        }
        int[] ids = new int[stages.length];
        int[] types =
                d.compute != null
                        ? new int[] {GL_COMPUTE_SHADER}
                        : new int[] {GL_VERTEX_SHADER, GL_FRAGMENT_SHADER};
        for (int i = 0; i < stages.length; i++) {
            ids[i] = compile(types[i], stages[i].source());
            glAttachShader(program, ids[i]);
        }
        if (binaries != null) {
            glProgramParameteri(program, GL_PROGRAM_BINARY_RETRIEVABLE_HINT, GL_TRUE);
        }
        glLinkProgram(program);
        shaders[r] = ids;
        states[r] = COMPILING;
        compiles++;
        if (!parallel) {
            poll(r);
        }
        return r;
    }

    private int compile(int type, String source) {
        int shader = glCreateShader(type);
        byte[] utf8 = source.getBytes(StandardCharsets.UTF_8);
        try (Arena a = Arena.ofConfined()) { // glShaderSource copies the text
            MemorySegment text = a.allocate(utf8.length + 1L);
            MemorySegment.copy(utf8, 0, text, ValueLayout.JAVA_BYTE, 0, utf8.length);
            MemorySegment pointers = a.allocate(ValueLayout.ADDRESS);
            pointers.set(ValueLayout.ADDRESS, 0, text);
            glShaderSource(shader, 1, pointers, MemorySegment.NULL);
        }
        glCompileShader(shader);
        return shader;
    }

    /** Checks a compiling program; finishes it when the driver is done. */
    private void poll(int r) {
        int program = programs[r];
        if (parallel) {
            glGetProgramiv(program, GL_COMPLETION_STATUS_KHR, scratch);
            if (scratch.get(ValueLayout.JAVA_INT, 0) == GL_FALSE) {
                return;
            }
        }
        glGetProgramiv(program, GL_LINK_STATUS, scratch);
        boolean linked = scratch.get(ValueLayout.JAVA_INT, 0) == GL_TRUE;
        if (!linked) {
            StringBuilder log = new StringBuilder(labels[r]).append(" failed:\n");
            int[] ids = shaders[r];
            for (int i = 0; i < ids.length; i++) {
                String shaderLog = infoLog(ids[i], true);
                if (!shaderLog.isBlank()) {
                    log.append(sources[r][i].annotate(shaderLog));
                }
            }
            log.append(infoLog(program, false));
            states[r] = FAILED;
            LOG.error(log.toString());
        } else {
            states[r] = READY;
            if (binaries != null) {
                binaries.store(keys[r], program);
            }
        }
        for (int id : shaders[r]) {
            glDetachShader(program, id);
            glDeleteShader(id);
        }
        shaders[r] = null;
        sources[r] = null;
    }

    private String infoLog(int object, boolean shader) {
        if (shader) {
            glGetShaderiv(object, GL_INFO_LOG_LENGTH, scratch);
        } else {
            glGetProgramiv(object, GL_INFO_LOG_LENGTH, scratch);
        }
        int length = scratch.get(ValueLayout.JAVA_INT, 0);
        if (length <= 1) {
            return "";
        }
        try (Arena a = Arena.ofConfined()) {
            MemorySegment buf = a.allocate(length);
            if (shader) {
                glGetShaderInfoLog(object, length, MemorySegment.NULL, buf);
            } else {
                glGetProgramInfoLog(object, length, MemorySegment.NULL, buf);
            }
            return buf.getString(0);
        }
    }

    /** Deletes every program. */
    @Override
    public void close() {
        for (int r = 0; r < count; r++) {
            if (shaders[r] != null) {
                for (int id : shaders[r]) {
                    glDeleteShader(id);
                }
            }
            glDeleteProgram(programs[r]);
        }
        count = 0;
        arena.close();
    }
}
