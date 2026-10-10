package dev.jeddy.bench;

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
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.TimeUnit;

/**
 * FFM call overhead without a GPU (TASKS M3-05, DESIGN §4.4): regular vs critical downcalls, and
 * passing small parameter blocks from heap arrays (critical with heap access) vs off-heap memory.
 * Uses C library functions, so it runs everywhere.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class FfmBench {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIBC = LINKER.defaultLookup();
    private static final FunctionDescriptor ABS_DESC =
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
    // memcpy returns its destination; declared as a long so the call allocates nothing.
    private static final FunctionDescriptor MEMCPY_DESC =
            FunctionDescriptor.of(
                    ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_LONG);

    private static final MethodHandle ABS =
            LINKER.downcallHandle(LIBC.findOrThrow("abs"), ABS_DESC);
    private static final MethodHandle ABS_CRITICAL =
            LINKER.downcallHandle(LIBC.findOrThrow("abs"), ABS_DESC, Linker.Option.critical(false));
    private static final MethodHandle MEMCPY =
            LINKER.downcallHandle(LIBC.findOrThrow("memcpy"), MEMCPY_DESC);
    private static final MethodHandle MEMCPY_CRITICAL =
            LINKER.downcallHandle(
                    LIBC.findOrThrow("memcpy"), MEMCPY_DESC, Linker.Option.critical(true));

    private int value = -42;
    private final float[] matrix = new float[16];
    private final float[] target = new float[16];
    private MemorySegment heapMatrix;
    private MemorySegment heapTarget;
    private Arena arena;
    private MemorySegment offMatrix;
    private MemorySegment offTarget;

    /** Allocates the parameter blocks once (heap wrappers are cached, DESIGN §4.4). */
    @Setup(Level.Trial)
    public void setup() {
        heapMatrix = MemorySegment.ofArray(matrix);
        heapTarget = MemorySegment.ofArray(target);
        arena = Arena.ofConfined();
        offMatrix = arena.allocate(64, 16);
        offTarget = arena.allocate(64, 16);
    }

    /** Frees the off-heap blocks. */
    @TearDown(Level.Trial)
    public void tearDown() {
        arena.close();
    }

    /** A regular downcall to a trivial function: the FFM transition cost. */
    @Benchmark
    public int absRegular() throws Throwable {
        return (int) ABS.invokeExact(value);
    }

    /** The same call linked critical: no thread-state transition. */
    @Benchmark
    public int absCritical() throws Throwable {
        return (int) ABS_CRITICAL.invokeExact(value);
    }

    /** A 64-byte block (a mat4) from off-heap memory, regular call. */
    @Benchmark
    public long mat4OffHeapRegular() throws Throwable {
        return (long) MEMCPY.invokeExact(offTarget, offMatrix, 64L);
    }

    /** A 64-byte block from off-heap memory, critical call. */
    @Benchmark
    public long mat4OffHeapCritical() throws Throwable {
        return (long) MEMCPY_CRITICAL.invokeExact(offTarget, offMatrix, 64L);
    }

    /** A 64-byte block straight from a {@code float[]}: critical with heap access. */
    @Benchmark
    public long mat4HeapCritical() throws Throwable {
        return (long) MEMCPY_CRITICAL.invokeExact(heapTarget, heapMatrix, 64L);
    }

    /** The alternative to heap access: copy the array off-heap first, then a regular call. */
    @Benchmark
    public long mat4CopyThenRegular() throws Throwable {
        MemorySegment.copy(matrix, 0, offMatrix, ValueLayout.JAVA_FLOAT, 0, 16);
        return (long) MEMCPY.invokeExact(offTarget, offMatrix, 64L);
    }
}
