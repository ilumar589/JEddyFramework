package dev.jeddy.bench;

import static dev.jeddy.gl.ffi.GL46.*;

import dev.jeddy.gl.GLLoader;
import dev.jeddy.gl.GLShaders;
import dev.jeddy.gl.ffi.GLLinker;
import dev.jeddy.window.Platform;
import dev.jeddy.window.VSync;
import dev.jeddy.window.Window;
import dev.jeddy.window.WindowConfig;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.TimeUnit;

/**
 * GL call overhead through FFM (TASKS M3-05, DESIGN §4.4): {@code glUniform*} regular vs critical,
 * matrix uploads from heap arrays vs off-heap memory, and small {@code glNamedBufferSubData}
 * updates. Needs a GL 4.6 context: CI runs it under Xvfb with Mesa llvmpipe, where the driver's own
 * cost is larger than on hardware, so compare variants rather than absolute numbers.
 *
 * <p>The context is created on the benchmark thread in {@code @Setup}, which becomes the SDL main
 * thread for the trial.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class GlBench {
    private static final String VS =
            """
            #version 460 core
            layout(location = 0) uniform vec4 tint;
            layout(location = 1) uniform mat4 model;
            void main() { gl_Position = model * tint; }
            """;
    private static final String FS =
            """
            #version 460 core
            layout(location = 0) out vec4 color;
            void main() { color = vec4(1.0); }
            """;

    /** Handles linked after the context exists; static final so the JIT folds them. */
    static final class Handles {
        private static final Linker LINKER = Linker.nativeLinker();
        static final FunctionDescriptor U4F =
                FunctionDescriptor.ofVoid(
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_FLOAT);
        static final FunctionDescriptor UMAT4 =
                FunctionDescriptor.ofVoid(
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_BOOLEAN,
                        ValueLayout.ADDRESS);
        static final MethodHandle UNIFORM4F =
                LINKER.downcallHandle(GLLinker.address("glUniform4f"), U4F);
        static final MethodHandle UNIFORM4F_CRITICAL =
                LINKER.downcallHandle(
                        GLLinker.address("glUniform4f"), U4F, Linker.Option.critical(false));
        static final MethodHandle UNIFORM_MATRIX4 =
                LINKER.downcallHandle(GLLinker.address("glUniformMatrix4fv"), UMAT4);
        static final MethodHandle UNIFORM_MATRIX4_CRITICAL =
                LINKER.downcallHandle(
                        GLLinker.address("glUniformMatrix4fv"),
                        UMAT4,
                        Linker.Option.critical(true));
    }

    private Window window;
    private int program;
    private int buffer;
    private float x;
    private final float[] matrix = new float[16];
    private MemorySegment heapMatrix;
    private Arena arena;
    private MemorySegment offMatrix;

    /** Creates a hidden window and GL context on this thread. */
    @Setup(Level.Trial)
    public void setup() {
        Platform.init("Jeddy GL bench", "0.1", "dev.jeddy.bench.gl");
        window =
                Window.create(
                        new WindowConfig()
                                .hidden(true)
                                .size(64, 64)
                                .minimumSize(16, 16)
                                .vsync(VSync.OFF));
        GLLoader.init(window::glProcAddress);
        program = GLShaders.program(VS, FS);
        glUseProgram(program);
        arena = Arena.ofConfined();
        heapMatrix = MemorySegment.ofArray(matrix);
        offMatrix = arena.allocate(64, 16);
        for (int i = 0; i < 16; i += 5) {
            matrix[i] = 1;
            offMatrix.setAtIndex(ValueLayout.JAVA_FLOAT, i, 1);
        }
        MemorySegment id = arena.allocate(ValueLayout.JAVA_INT);
        glCreateBuffers(1, id);
        buffer = id.get(ValueLayout.JAVA_INT, 0);
        glNamedBufferStorage(buffer, 1 << 16, MemorySegment.NULL, GL_DYNAMIC_STORAGE_BIT);
    }

    /** Destroys the context. */
    @TearDown(Level.Trial)
    public void tearDown() {
        glFinish();
        glDeleteProgram(program);
        window.close();
        Platform.shutdown();
        arena.close();
    }

    /** {@code glUniform4f}, regular downcall. */
    @Benchmark
    public void uniform4fRegular() throws Throwable {
        x += 1e-3f;
        Handles.UNIFORM4F.invokeExact(0, x, 1f, 2f, 3f);
    }

    /** {@code glUniform4f}, critical downcall. */
    @Benchmark
    public void uniform4fCritical() throws Throwable {
        x += 1e-3f;
        Handles.UNIFORM4F_CRITICAL.invokeExact(0, x, 1f, 2f, 3f);
    }

    /** {@code glUniformMatrix4fv} from off-heap memory, regular downcall. */
    @Benchmark
    public void uniformMatrixOffHeapRegular() throws Throwable {
        Handles.UNIFORM_MATRIX4.invokeExact(1, 1, false, offMatrix);
    }

    /** {@code glUniformMatrix4fv} from a {@code float[]}, critical with heap access. */
    @Benchmark
    public void uniformMatrixHeapCritical() throws Throwable {
        Handles.UNIFORM_MATRIX4_CRITICAL.invokeExact(1, 1, false, heapMatrix);
    }

    /** {@code glUniformMatrix4fv} after copying the {@code float[]} off-heap, regular downcall. */
    @Benchmark
    public void uniformMatrixCopyThenRegular() throws Throwable {
        MemorySegment.copy(matrix, 0, offMatrix, ValueLayout.JAVA_FLOAT, 0, 16);
        Handles.UNIFORM_MATRIX4.invokeExact(1, 1, false, offMatrix);
    }

    /** A 64-byte {@code glNamedBufferSubData} (never critical: it can stall on a busy buffer). */
    @Benchmark
    public void namedBufferSubData64() {
        glNamedBufferSubData(buffer, 0, 64, offMatrix);
    }

    /** The generated binding for {@code glUniform4f}, as engine code calls it. */
    @Benchmark
    public void uniform4fGenerated() {
        x += 1e-3f;
        glUniform4f(0, x, 1f, 2f, 3f);
    }
}
