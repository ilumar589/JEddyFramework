# 12. Fast Java: FFM, zero allocation and the JIT

Jeddy is a game engine written in Java that calls C libraries (SDL3, SDL3_mixer, OpenGL, zstd) and
promises a steady frame rate. Three things make that possible, and this chapter covers each of
them: how Java calls native code cheaply (the Foreign Function & Memory API, "FFM"), how the
engine runs without allocating a single byte per frame, and how it cooperates with the JIT
compiler and the garbage collector instead of fighting them. A last section covers the lock-free
structures that let threads talk without waiting.

You will learn:

- the **non-negotiables** in [CLAUDE.md](../../CLAUDE.md) and the reasoning behind each;
- **FFM** as Jeddy uses it: `Linker`, `FunctionDescriptor`, downcall handles in `static final`
  holders (jextract output and the OpenGL generator), `Linker.Option.critical` and its allow-list,
  arenas, `MemorySegment` and `ValueLayout`, and the `NativeLoader`;
- what **allocates** in Java, how the codebase avoids it, and how it **proves** it: the
  allocation assertion, soak tests, JMH with `-prof gc`, and the perf capture with its JIT log;
- the **JVM setup**: launcher flags, ZGC, method-handle customization (D69), the C2 string-literal
  finding (D70), and the AOT cache (D72);
- **Valhalla readiness**: the three layers of the math library;
- **lock-free concurrency**: `VarHandle` access modes, false sharing and padding, the SPSC ring,
  the Vyukov MPMC queue, and the triple buffer.

Where it lives:

| What | Where |
|---|---|
| FFM bindings | [`modules/sdl/src/ffi/java/dev/jeddy/sdl/ffi`](../../modules/sdl/src/ffi/java/dev/jeddy/sdl/ffi) (jextract, committed), [`build-logic/.../GlGenerator.java`](../../build-logic/src/main/java/dev/jeddy/gradle/GlGenerator.java), [`GlBindings.kt`](../../build-logic/src/main/kotlin/dev/jeddy/gradle/GlBindings.kt), [`bindings/gl/gl.properties`](../../bindings/gl/gl.properties), [`modules/gl/.../GLLinker.java`](../../modules/gl/src/main/java/dev/jeddy/gl/ffi/GLLinker.java), [`GLLoader.java`](../../modules/gl/src/main/java/dev/jeddy/gl/GLLoader.java) |
| Native memory and loading | [`core/memory`](../../modules/core/src/main/java/dev/jeddy/core/memory), [`core/natives/NativeLoader.java`](../../modules/core/src/main/java/dev/jeddy/core/natives/NativeLoader.java) |
| Proof of zero allocation | [`testing/.../Allocations.java`](../../testing/src/main/java/dev/jeddy/testing/Allocations.java), [`bench/`](../../bench), [`.github/scripts/jmh-check.py`](../../.github/scripts/jmh-check.py), [`samples/iso-demo`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso) (`PerfCapture`, `AllocProbe`, `JitLog`) |
| JVM flags and packaging | [`build-logic/.../Applications.kt`](../../build-logic/src/main/kotlin/dev/jeddy/gradle/Applications.kt), [`Packaging.kt`](../../build-logic/src/main/kotlin/dev/jeddy/gradle/Packaging.kt) |
| Lock-free structures | [`core/concurrent`](../../modules/core/src/main/java/dev/jeddy/core/concurrent) |
| Design | [DESIGN.md](../DESIGN.md) §4 (performance architecture), §6 (native layer), §8.1, §9.1, §13.3; [docs/perf/m10.md](../perf/m10.md) |

[01 Architecture](01-architecture.md) introduced the threads, the job system and the SoA data
layout. This chapter assumes them.

---

## The rules, and why they exist

[CLAUDE.md](../../CLAUDE.md) lists six non-negotiables. Here they are with the reason behind each.

1. **Performance first: 0 bytes allocated per frame on hot paths.** A frame at 60 FPS has 16.7 ms;
   at 144 FPS, 6.9 ms. Every allocation is cheap on its own (a pointer bump in a thread-local
   buffer), but it adds up. A game that allocates only 1 KiB per frame at 144 FPS allocates
   $`1\,\text{KiB} \times 144\,\text{s}^{-1} \times 3600\,\text{s} \approx 506\,\text{MiB}`$ per hour.
   That garbage has to be collected, and collection competes with the game for CPU and memory
   bandwidth even with a concurrent collector. If a frame allocates nothing, the GC has nothing to
   do: the 10-minute perf capture in [docs/perf/m10.md](../perf/m10.md) recorded "no collection at
   all in 11 minutes".
2. **Stable Java only.** No `--enable-preview`, no `jdk.incubator.*` (D4). Preview features change
   between releases; a game must run on the JDK it ships with for years. That rules out the Vector
   API (still incubating in JDK 27) and value classes (JEP 401, targeted as a preview in JDK 28),
   so the math library is written to *become* value classes later (below).
3. **FFM rules.** Downcall handles in `static final` holders, `critical` only for allow-listed
   short calls, never `Arena.ofAuto()`, no Java on the audio thread. Each rule exists because the
   alternative is either slow (non-constant handles), dangerous (a critical call that blocks or
   calls back into Java can stall or crash the JVM), or non-deterministic (memory released when the
   GC feels like it).
4. **SDL main thread = render thread.** A platform rule, covered in
   [01 Architecture](01-architecture.md).
5. **Module boundaries.** Covered in chapter 01.
6. **Prove it.** "Every hot-path change ships with an allocation test and, where relevant, a JMH
   benchmark." Performance claims that are not measured rot: one innocent `"" + x` in a per-frame
   method undoes them. The tools for proving are described in Part 2.

---

## Part 1: calling native code with FFM

### The pieces

The Foreign Function & Memory API (`java.lang.foreign`, final since JDK 22,
[JEP 454](https://openjdk.org/jeps/454)) replaces JNI. You describe a C function's signature in
Java, ask the platform's `Linker` for a `MethodHandle`, and call it. No C glue code is compiled.

The smallest real example in the repository is the FFM benchmark,
[`bench/src/main/java/dev/jeddy/bench/FfmBench.java`](../../bench/src/main/java/dev/jeddy/bench/FfmBench.java),
which calls C library functions so it runs everywhere:

```java
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
    // ...

    /** A regular downcall to a trivial function: the FFM transition cost. */
    @Benchmark
    public int absRegular() throws Throwable {
        return (int) ABS.invokeExact(value);
    }
```

Piece by piece:

| Piece | Role | Here |
|---|---|---|
| `Linker.nativeLinker()` | knows the platform's C calling convention (System V on Linux, Microsoft x64 on Windows) | one per process |
| `SymbolLookup` | finds a function's address by name | `defaultLookup()` for libc; Jeddy's own libraries use `SymbolLookup.loaderLookup()` (below) |
| `FunctionDescriptor` | the C signature as memory layouts | `int abs(int)` is `of(JAVA_INT, JAVA_INT)` |
| `ValueLayout` | size, alignment and byte order of one C value | `JAVA_INT` (int32), `JAVA_LONG` (int64), `JAVA_FLOAT`, `ADDRESS` (pointer) |
| `downcallHandle(address, descriptor, options...)` | a `MethodHandle` that calls the C function | `ABS`, `ABS_CRITICAL` |
| `invokeExact` | the call | `(int) ABS.invokeExact(value)` |

`invokeExact` is *signature-polymorphic*: although it is declared with `Object...`, javac compiles
each call site with the exact static types used there (`(I)I` above), so there is no varargs array
and no boxing. The types must match the handle exactly, including the cast on the return value.
`(long) ABS.invokeExact(value)` compiles but throws `WrongMethodTypeException` at run time.

Notice the comment on `MEMCPY_DESC`. A C function that returns a pointer would normally be
described with an `ADDRESS` return layout, and every call would then create a new `MemorySegment`
object to wrap the returned address. Declaring the return as `JAVA_LONG` gets the raw address as
a primitive. Jeddy's GL generator applies the same idea to `GLsync` (below).

### Downcall handles in `static final` holders

A `MethodHandle` call is only fast if the JIT can see *which* handle is being called. C2 treats a
`static final` field as a constant once its class is initialized, so `ABS.invokeExact(...)`
compiles down to roughly what a C compiler would emit for a direct call: marshal the arguments,
switch thread state, call, switch back. A handle in an instance field or a `HashMap` is not a
constant, and the call goes through slower generic paths.

The **holder idiom** adds laziness: each native function gets its own tiny nested class with a
`static final` handle. The class (and the handle) is created the first time the function is
called, because the JVM initializes a class on first use, exactly once, thread-safely. A binding
with 1000 functions therefore links only the ones a program actually calls.

### jextract output

C libraries are bound with jextract, run by hand (`./gradlew :sdl:regenerateBindings`), and the
output is committed under `src/ffi/java` (D2, DESIGN §6.4). Here is `SDL_PollEvent` from
[`modules/sdl/src/ffi/java/dev/jeddy/sdl/ffi/SDL3.java`](../../modules/sdl/src/ffi/java/dev/jeddy/sdl/ffi/SDL3.java):

```java
    private static class SDL_PollEvent {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(
            SDL3.C_BOOL,
            SDL3.C_POINTER
        );

        public static final MemorySegment ADDR = SDL3.findOrThrow("SDL_PollEvent");

        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    // ...

    public static boolean SDL_PollEvent(MemorySegment event) {
        var mh$ = SDL_PollEvent.HANDLE;
        try {
            if (TRACE_DOWNCALLS) {
                traceDowncall("SDL_PollEvent", event);
            }
            return (boolean)mh$.invokeExact(event);
        } catch (Throwable ex$) {
           throw new AssertionError("should not reach here", ex$);
        }
    }
```

The shared preamble lives in
[`SDL3_1.java`](../../modules/sdl/src/ffi/java/dev/jeddy/sdl/ffi/SDL3_1.java) (jextract splits big
header classes):

```java
    static final Arena LIBRARY_ARENA = Arena.global();
    static final boolean TRACE_DOWNCALLS = Boolean.getBoolean("jextract.trace.downcalls");

    static void traceDowncall(String name, Object... args) {
         String traceArgs = Arrays.stream(args)
                       .map(Object::toString)
                       .collect(Collectors.joining(", "));
         System.out.printf("%s(%s)\n", name, traceArgs);
    }
    // ...
    static final SymbolLookup SYMBOL_LOOKUP = SymbolLookup.loaderLookup()
            .or(Linker.nativeLinker().defaultLookup());

    public static final ValueLayout.OfBoolean C_BOOL = ValueLayout.JAVA_BOOLEAN;
    // ...
    public static final ValueLayout.OfLong C_LONG_LONG = ValueLayout.JAVA_LONG;
```

Three lessons hide in these few lines:

- `traceDowncall` uses varargs, a stream and `printf`: everything the hot-path rules forbid. It is
  harmless because it sits behind `TRACE_DOWNCALLS`, a `static final boolean` that is false unless
  you pass `-Djextract.trace.downcalls=true`. The varargs array is only created inside the branch,
  and C2 removes the dead branch entirely.
- `LIBRARY_ARENA` is `Arena.global()`. Raw jextract output uses `Arena.ofAuto()` here; the
  [`BindingsPostProcessor`](../../build-logic/src/main/java/dev/jeddy/gradle/BindingsPostProcessor.java)
  rewrites it (D22), because engine code never uses GC-released memory.
- `C_LONG_LONG` for 64-bit integers. jextract on Linux maps `int64_t` and `size_t` to C `long`,
  which is 32 bits on Windows. The post-processor rewrites `C_LONG` to `C_LONG_LONG` and fails
  the build on platform-dependent types (`long`, `wchar_t`, `va_list`), so one generated tree
  works on both x64 ABIs (D22).

### The OpenGL generator

OpenGL has around a thousand commands and many extensions, and its function pointers come from
the driver at run time (`SDL_GL_GetProcAddress`), not from a symbol table. Jeddy generates its
own binding from the Khronos registry instead of using jextract (D2, D24). The Gradle task
[`GenerateGlBindings`](../../build-logic/src/main/kotlin/dev/jeddy/gradle/GlBindings.kt) is a thin,
cacheable wrapper:

```kotlin
@CacheableTask
abstract class GenerateGlBindings : DefaultTask() {
    // ... registry (gl.xml), settings (gl.properties), outputDir
    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        GlGenerator.generate(registry.get().asFile.toPath(), settings.get().asFile.toPath(), out.toPath())
    }
}
```

[`GlGenerator`](../../build-logic/src/main/java/dev/jeddy/gradle/GlGenerator.java) (plain Java in
`build-logic`, so it also runs by hand) selects the GL 4.6 core commands plus the extensions listed
in [`gl.properties`](../../bindings/gl/gl.properties), maps C types to Java (`GLenum`, `GLuint`,
`GLsizei` → `int`; `GLboolean` → `boolean`; `GLsizeiptr`, `GLintptr`, `GLuint64`, `GLsync` →
`long`; any pointer → `MemorySegment`) and emits one method plus one holder per command. The
emitted holder is built here:

```java
        sb.append("    private static final class ").append(holder).append(" {\n");
        sb.append("        static final MethodHandle MH =\n                GLLinker.link(\"")
                .append(name)
                .append("\", ");
        if (ret.equals("void")) {
            sb.append("FunctionDescriptor.ofVoid(");
        } else {
            sb.append("FunctionDescriptor.of(").append(layout(ret));
            // ...
        }
        // ... parameter layouts
        sb.append(String.join(", ", layouts)).append("), ").append(critical);
```

and the output (generated at build time into
`modules/gl/build/generated/sources/gl/java/main/dev/jeddy/gl/ffi/GL46.java`, never committed or
edited) looks like this for `glUniform4f`:

```java
    /**
     * {@code void glUniform4f(GLint location, GLfloat v0, GLfloat v1, GLfloat v2, GLfloat v3)} (critical)
     */
    public static void glUniform4f(int location, float v0, float v1, float v2, float v3) {
        try {
            glUniform4f.MH.invokeExact(location, v0, v1, v2, v3);
        } catch (Throwable t) {
            throw GLLinker.rethrow(t);
        }
    }

    private static final class glUniform4f {
        static final MethodHandle MH =
                GLLinker.link("glUniform4f", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT), true);
    }
```

The last argument, `true`, marks it as critical (next section). Benchmarks showed the generated
wrapper costs the same as calling a raw handle (M3-05, DESIGN §9.1).

Two type-mapping decisions keep per-frame GL calls allocation-free (D24, D29):

- **`GLsync` is a `long`.** `glFenceSync` returns a pointer-sized handle that is only ever passed
  back to GL. Mapped as `MemorySegment`, every fence would allocate a wrapper; as a `long` it is
  stored in a `long[] fences` array in
  [`FrameRing`](../../modules/render/src/main/java/dev/jeddy/render/gpu/FrameRing.java).
- **Offset overloads.** Parameters such as `indirect`, `indices`, `offset`, `data` and `pixels` are
  declared `const void *` but are usually a byte offset into a bound buffer. The generator emits a
  second overload with a `long` (holder name suffix `$offset`):

  ```java
      public static void glMultiDrawElementsIndirect(int mode, int type, MemorySegment indirect, int drawcount, int stride) {
      // ...
      public static void glMultiDrawElementsIndirect(int mode, int type, long indirect, int drawcount, int stride) {
  ```

  The `long` is a buffer offset when a buffer is bound to the matching target and a raw address
  otherwise, so neither case needs a segment.

### Linking against a driver

The generated holders call
[`GLLinker.link`](../../modules/gl/src/main/java/dev/jeddy/gl/ffi/GLLinker.java):

```java
    private static final Linker LINKER = Linker.nativeLinker();
    private static final boolean CRITICAL =
            !"false".equalsIgnoreCase(System.getProperty("jeddy.gl.critical", "true"));
    // ...

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
```

`address(name)` asks the resolver that
[`GLLoader.init`](../../modules/gl/src/main/java/dev/jeddy/gl/GLLoader.java) installed, which is
`Window::glProcAddress`, which calls `SDL_GL_GetProcAddress`. Because holders link lazily, GL
must be loaded (with the context current, on the main thread) before the first GL command runs:

```java
    public static GLCapabilities init(Function<String, MemorySegment> procAddress) {
        GLLinker.setResolver(procAddress);
        GLCapabilities caps = readCapabilities();
        // ... version check, then:
        GLLinker.setExtensions(caps::has);
        // ...
    }
```

A command the driver does not export links to a handle that throws `UnsupportedOperationException`
when called (built from `MethodHandles.throwException`, `insertArguments` and `dropArguments`), so
optional extensions never fail at startup. Extension commands are gated on the extension string,
because GLX returns a non-null stub for *any* `gl*` name (D24).

### Prelinking

Lazy holders have a cost: the first call creates the handle, which loads classes and allocates.
If that first call happens in the middle of the game (the first positional sound, the first voice
that finishes), it shows up as a one-time allocation in a frame. Perf fix F4 in
[docs/perf/m10.md](../perf/m10.md) found exactly that, and
[`Audio`](../../modules/audio/src/main/java/dev/jeddy/audio/Audio.java) now prelinks the handles
its per-frame paths use while the audio system opens (D65):

```java
    private void prelink() {
        MethodHandle[] handles = {
            MIX_SetTrack3DPosition$handle(),
            MIX_TrackPaused$handle(),
            MIX_TrackPlaying$handle(),
            MIX_SetTrackGain$handle(),
            // ...
            SDL3.SDL_SetNumberProperty$handle()
        };
        // ... sanity checks, then exercise the accessors once
        writePoint(0, 0, 0);
        MIX_TrackPlaying(tracks[0]);
        MIX_TrackPaused(tracks[0]);
    }
```

Calling `X$handle()` forces the holder class to initialize. The array itself only feeds a sanity
check; what matters is the side effect.

### Critical downcalls

When Java calls native code normally, the thread changes state from "in Java" to "in native".
While in native, the thread does not hold up safepoints: the GC can run, other threads can
deoptimize code, and the native function may block as long as it likes. On return the thread
checks whether a safepoint is in progress before touching Java objects again. That bookkeeping is
the transition cost.

`Linker.Option.critical(boolean allowHeapAccess)` declares a function **critical**: extremely
short and never calling back into Java. The linker can then skip the state transition. Two
consequences follow:

- While a critical call runs, the GC cannot reach a safepoint. A critical call that blocks (waits
  on a fence, a lock in the driver, a buffer still in use by the GPU) therefore delays every other
  thread that is waiting for a safepoint. A critical function that calls back into Java (an
  upcall) can crash the JVM.
- With `allowHeapAccess = true`, the call may receive **heap segments**, for example
  `MemorySegment.ofArray(float[])`, so a small parameter block needs no copy to native memory.
  Regular downcalls reject heap segments.

### Measured cost

DESIGN §4.4 and [`bench/README.md`](../../bench/README.md) record the M3-05 measurements
(hand-timed loop, JDK 27, Linux, Mesa llvmpipe):

| Call | ns |
|---|---|
| `abs` regular / critical | 16.5 / 3.6 |
| 64-byte block: off-heap regular / off-heap critical / heap critical / copy then regular | 13.9 / 10.1 / 10.5 / 18.9 |
| `glUniform4f` regular / critical / generated `GL46` | 37.0 / 33.2 / 33.5 |

The committed nightly JMH baseline
([`bench/baseline/linux-x64.json`](../../bench/baseline/linux-x64.json), a GitHub runner) shows a
smaller gap for `abs` (3.6 ns regular, 2.7 ns critical), so the exact saving depends on the
machine. The direction is consistent. 13 ns sounds like nothing, but a frame with 5 000 state
changes and uniform uploads pays 65 µs for it, and those calls are pure overhead.

### The allow-list

Critical is applied only to a curated list in [`gl.properties`](../../bindings/gl/gl.properties):

```properties
# Linked with Linker.Option.critical(true) when -Djeddy.gl.critical is not false (DESIGN §4.4):
# state setters, binds and uniforms only. They never block, never call back into Java, and may
# take heap segments (MemorySegment.ofArray) for their small parameter blocks.
critical=glActiveTexture,\
  glBindBuffer,glBindBufferBase,glBindBufferRange,glBindFramebuffer,glBindImageTexture,\
  # ... blend, clear, depth, stencil, viewport and scissor state ...
  glUseProgram,glViewport,\
  glUniform1f,glUniform2f,glUniform3f,glUniform4f,glUniform1i,glUniform2i,glUniform3i,glUniform4i,\
  # ... more glUniform* and glProgramUniform* ...
```

(The `# ...` lines are cuts for this guide, not part of the file.) Everything that can block is
excluded: draws, `glClientWaitSync`, buffer maps and data uploads (which can stall on a buffer
the GPU is still reading), swap, event polling. The generator refuses a name that is not a
selected command ("critical command not selected").

### Critical and GL debugging

For development, a window can ask for a debug GL context (`WindowConfig.glDebug(true)`), and
[`GLDebug`](../../modules/gl/src/main/java/dev/jeddy/gl/GLDebug.java) then installs a synchronous
`KHR_debug` callback: when a GL call raises a message, the driver calls a Java upcall *from inside
that GL call* to log it with a Java stack trace. An upcall from inside a critical call is exactly
what can crash the JVM. So debug runs set `jeddy.gl.critical=false` (D12), and `GLDebug.enable`
throws if critical linking is on. The render module's GPU tests (`GpuTestContext`) and the
hello-triangle sample's debug mode set the property before anything links. `GLLinker` reads it
once into the `static final boolean CRITICAL`, so release builds pay nothing for the switch.

This has a design consequence. Because a debug run has no critical linking, it also cannot pass
heap segments to GL, so the renderer does **not** rely on them. Small parameter blocks are copied
into a preallocated off-heap segment instead, for example in
[`DebugRenderer`](../../modules/render/src/main/java/dev/jeddy/render/debug/DebugRenderer.java):

```java
    private final MemorySegment matrix = arena.allocate(64, 16);
    // ...
        MemorySegment.copy(packet.viewProjection, 0, matrix, ValueLayout.JAVA_FLOAT, 0, 16);
        // ...
        glProgramUniformMatrix4fv(program, 0, 1, false, matrix);
```

The copy of 64 bytes costs a few nanoseconds and works the same with and without critical
linking.

### Arenas decide lifetime

Native memory in FFM is allocated from an `Arena`, and the arena decides when it is freed:

| Arena | Freed | Accessible from | Jeddy uses it for |
|---|---|---|---|
| `Arena.global()` | never | any thread | jextract's string constants (`LIBRARY_ARENA`), interned C strings (`CStrings`) |
| `Arena.ofShared()` | on `close()`, from any thread | any thread | long-lived blocks owned by a subsystem: `OffHeap`, zstd contexts, paks, staging rings |
| `Arena.ofConfined()` | on `close()`, by the owner thread | the creating thread only | cold-path temporaries in try-with-resources (`GLLoader.readCapabilities`); per-object scratch on the main thread (`SceneRenderer`, `DebugRenderer`, `EventPump`) |
| `Arena.ofAuto()` | when the GC notices the arena is unreachable | any thread | **never** in engine code |

Why never `ofAuto`? Its memory is released by the garbage collector, at a time nobody controls,
and only if the GC runs at all. In an engine that allocates nothing per frame, the GC may not run
for an hour, so "automatic" memory would effectively leak. DESIGN §4.3 states the companion rule:
`Arena.ofConfined()` only in cold paths, because every allocation from it is a `malloc`, and
opening and closing one per frame would mean `malloc`/`free` per frame.

[`OffHeap`](../../modules/core/src/main/java/dev/jeddy/core/memory/OffHeap.java) wraps the shared
case and counts every block in `MemoryStats` by category:

```java
    private OffHeap(long bytes, long alignment, MemoryCategory category) {
        this.arena = Arena.ofShared();
        this.segment = arena.allocate(bytes, alignment);
        this.category = category;
        MemoryStats.allocated(category, bytes);
    }
```

### Segments and layouts

A `MemorySegment` is a bounds-checked view of memory (native or heap). You read and write it with
a `ValueLayout` and a byte offset. The layouts and offsets live in `static final` fields so the JIT
can fold them. [`DrawBuilder`](../../modules/render/src/main/java/dev/jeddy/render/draw/DrawBuilder.java)
writes 64-byte instance records into persistently mapped GPU memory this way:

```java
    private static final ValueLayout.OfFloat F32 =
            ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.nativeOrder());
    private static final ValueLayout.OfInt I32 =
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.nativeOrder());
    // ...
            m.set(F32, o + 4L * j, x + (curr[w + j] - x) * a);
            // ...
            m.set(I32, o + 48, itemMaterial[item]);
```

The `JAVA_*` layouts already use the platform's byte order; `withOrder(ByteOrder.nativeOrder())`
just states it, since the GPU reads the bytes in that order.

### Reading C structs

The event pump shows the full pattern for reading C structs without allocating. From
[`EventPump`](../../modules/input/src/main/java/dev/jeddy/input/EventPump.java):

```java
    private static final long TYPE = SDL_CommonEvent.type$offset();
    private static final long TIMESTAMP = SDL_CommonEvent.timestamp$offset();
    private static final long KEY_SCANCODE = SDL_KeyboardEvent.scancode$offset();
    // ... one constant per field the pump reads

    /** All of memory, for reading text that SDL owns without creating a segment per event. */
    private static final MemorySegment RAW = MemorySegment.NULL.reinterpret(Long.MAX_VALUE);

    // ... (in the constructor)
        this.arena = Arena.ofConfined();
        this.event = arena.allocate(SDL_Event.layout());

    public int pump() {
        SdlThread.assertMainThread("SDL_PollEvent");
        MemorySegment e = event;
        int count = 0;
        while (SDL_PollEvent(e)) {
            count++;
            dispatch(e);
        }
        polled += count;
        return count;
    }

    private void dispatch(MemorySegment e) {
        int type = e.get(ValueLayout.JAVA_INT, TYPE);
        long time = e.get(ValueLayout.JAVA_LONG, TIMESTAMP);
        // ...
```

- One `SDL_Event` (128 bytes) is allocated once, in a confined arena owned by the main thread,
  and SDL overwrites it on every poll.
- Field offsets come from jextract's `$offset()` accessors and are stored in `static final long`s.
- Text input arrives as a `char *` that SDL owns. Wrapping it in a segment per event would
  allocate, so the pump reads bytes through `RAW`, one segment covering all of memory created
  once, at the raw address: `RAW.get(ValueLayout.JAVA_BYTE, p)`. `reinterpret` is a *restricted*
  method (it can create a segment over memory you do not own), which is why native access must be
  enabled for the module.

### Frame scratch

[`FrameAllocator`](../../modules/core/src/main/java/dev/jeddy/core/memory/FrameAllocator.java) is
a bump allocator over one `OffHeap` block, reset once per frame. It offers two APIs, and its
Javadoc says when to use which:

```java
 *   <li>{@link #allocateOffset(long, long)} returns an offset into {@link #segment()}. Reading and
 *       writing through {@code segment()} at that offset allocates nothing; use this in hot paths.
 *   <li>As a {@link SegmentAllocator} ({@code allocate}, {@code allocateFrom}): convenient for FFM
 *       calls, but each call returns a new slice object, which the JIT usually but not always
 *       removes.
```

```java
    public long allocateOffset(long byteSize, long alignment) {
        // ... power-of-two check
        long start = (offset + alignment - 1) & -alignment;
        long end = start + byteSize;
        if (end > segment.byteSize()) {
            throw new IllegalStateException(/* ... */);
        }
        offset = end;
        // ...
        return start;
    }
```

`(offset + alignment - 1) & -alignment` rounds up to a multiple of a power-of-two alignment. For
`offset = 3`, `alignment = 8`: $`(3 + 7) \mathbin{\&} \lnot 7 = 10 \mathbin{\&} \ldots11000_2 = 8`$
(in two's complement $-8 = \lnot 7$, all ones except the low three bits).
Running out of space throws instead of growing, so frame budgets stay deterministic.

Constant C strings (SDL hint names, mixer property names, GL command names) go through
[`CStrings.of`](../../modules/core/src/main/java/dev/jeddy/core/memory/CStrings.java), which
converts each distinct string once into global memory and returns the same segment afterwards.

### Loading the libraries: `NativeLoader`

`SymbolLookup.loaderLookup()` (what jextract code uses) only sees libraries that were loaded with
`System.load` or `System.loadLibrary`. So something must load SDL3, SDL3_mixer and zstd first, in
dependency order, from a trusted file.
[`NativeLoader`](../../modules/core/src/main/java/dev/jeddy/core/natives/NativeLoader.java)
(DESIGN §6.3) looks in three places: the directory in `-Djeddy.natives.dir`, then
`lib/jeddy/natives` inside the running Java image (a packaged game, D71), then the
`natives/<platform>/natives.json` resource of a `jeddy-natives-<platform>` jar, whose libraries it
extracts once into a per-user cache: write to a temp file, verify SHA-256, rename atomically.
Every library's hash is checked when the manifest is resolved (`verify`, for a directory as well
as for extracted files), before anything loads. Then the libraries load:

```java
        for (Library l : manifest) {
            if (wanted.contains(l.name()) && !LOADED.contains(l.name())) {
                Path p = directory.resolve(l.file());
                System.load(p.toAbsolutePath().toString());
                LOADED.add(l.name());
            }
        }
```

The loop follows the manifest order, so dependencies (SDL3) load before dependents (SDL3_mixer),
which matters on Windows where a DLL's dependencies are resolved by module name from the libraries
already loaded.

### Native access

`downcallHandle`, `reinterpret`, `System.load` and friends are **restricted methods**: they can
crash the JVM if misused, so the JDK wants you to opt in per module with
`--enable-native-access=<modules>`. Without it the JDK prints a warning on first use. Jeddy's
launchers pass the list of engine modules that use FFM (next part), tests on the class path get
`--enable-native-access=ALL-UNNAMED` (`jeddy.java-base.gradle.kts`), and javac's `restricted` lint
is turned off only in modules that apply `jeddy.native-library` or `jeddy.ffm-bindings` (D17).
Pure-Java modules (`math`, `physics`, `scene`) keep the lint with `-Werror`, so native code there
fails the build.

### Try it

```
./gradlew :bench:jmh -Pjmh.includes=FfmBench
# needs a GL 4.6 context
./gradlew :bench:jmh -Pjmh.includes=GlBench
# SdlBindingsTest: initializes every generated holder
./gradlew :sdl:test
```

---

## Part 2: zero allocation per frame

### What allocates in Java

The JVM allocates objects with a pointer bump in a thread-local allocation buffer (TLAB), so a
single allocation is very cheap. The cost comes later, as GC work, and in where the object lands
in memory. "Zero allocation per frame" means no `new` executes in steady state on the game thread,
the main thread or the workers (DESIGN §4.2). Many Java constructs allocate without a visible
`new`:

| Construct | What allocates | Jeddy's alternative | Example in the code |
|---|---|---|---|
| Boxing (`Map<Integer, Integer>`, `List<Float>`) | `Integer.valueOf` creates an object outside its cache of -128..127 | primitive collections | [`IntIntMap`](../../modules/core/src/main/java/dev/jeddy/core/collections/IntIntMap.java), `IntList`, `LongIntMap` |
| `for (X x : list)` over a collection | an `Iterator` | index loops; for-each over **arrays** is fine (it compiles to an index loop) | `IntIntMap.firstSlot()`/`nextSlot(s)` |
| Capturing lambda or bound method reference (`this::f`) | a new object each time the expression is evaluated | evaluate once into a `final` field | `private final JobKernel kernel = this::updateRange;` |
| Varargs (`f(int... a)`) | an array per call | fixed-arity overloads; varargs only in cold code | `MainThreadQueue.post(opcode, a)`; `JobGraph.add` (build time) vs `run` |
| String concatenation, `String.format` | strings and builders | ints in records; messages in cold methods | `SdlThread.wrongThread` |
| Streams, `Optional` | pipeline objects | loops | |
| Exceptions for control flow | the exception and its stack trace | return codes | `SpscIntRing.offer` returns `false` when full |
| Native calls that return pointers | a `MemorySegment` per call | `long` returns (`GLsync`), `JAVA_LONG` descriptors | D24, `FfmBench.MEMCPY_DESC` |
| `MemorySegment.ofArray`, `asSlice`, `SegmentAllocator.allocate` | wrapper objects | wrappers created once; offsets into one segment | `FrameAllocator.allocateOffset`, `EventPump.RAW` |
| Lazy class initialization, handle linking | classes, method handles | link before the first frame | `Audio.prelink` (D65) |
| Arrays that grow mid-game | a new, larger array | reserve the peak up front | `RenderPacket.reserve` |
| Logging | varargs array, boxed arguments, formatting | log in cold paths; guard with `isEnabled` | [`Logger`](../../modules/core/src/main/java/dev/jeddy/core/log/Logger.java) |
| A record or object per element | one object per item | SoA arrays; scratch fields | `World.point`, `MVec3f` |

The `Logger` Javadoc spells out its own rule: "Logging is for cold paths: arguments are boxed and
the message is formatted on the calling thread. In code that runs every frame, guard with
`isEnabled(LogLevel)`."

### Escape analysis, and why the code does not rely on it

C2 can remove an allocation when it proves the object never leaves the compiled method (after
inlining): it keeps the fields in registers instead ("scalar replacement"). That is why a
temporary `new Vec3f(...)` inside a small hot method often costs nothing. But it is fragile:

- the interpreter and C1 never do it, so warm-up frames always allocate;
- it fails if the object is stored into a field or array, passed to a method that is not inlined
  (too big, or a megamorphic call), or merged with another allocation at a control-flow join;
- a harmless-looking refactoring can change any of those.

DESIGN §4.2 therefore says: systems own pre-allocated mutable scratch "so they don't rely on
escape analysis". The `FrameAllocator` Javadoc above ("which the JIT usually but not always
removes") is the same caution.

### Patterns you will see everywhere

**Scratch fields.** [`World`](../../modules/app/src/main/java/dev/jeddy/app/world/World.java)
keeps `private final float[] point = new float[3];` and reuses it to transform each renderable's
bounding-sphere center during `extract`. The transform store's level kernel keeps one 12-float
matrix per thread slot (`scratch[threadIndex]`).

**Kernels stored once.** A method reference that captures `this` is an object. Evaluated inside
`update`, it would allocate every frame (and since it is passed to the job system, which stores it
in a job slot, escape analysis cannot remove it). Evaluated once in a field initializer, it is
free. Five such fields, collected from three files (the trailing comments name the class and are
not in the source):

```java
    private final JobKernel histogramKernel = this::histogramChunk;   // ParallelSort
    private final JobKernel scatterKernel = this::scatterChunk;
    private final JobKernel itemKernel = this::buildItems;            // DrawBuilder
    private final JobKernel recordKernel = this::writeRecords;
    private final JobKernel kernel = this::updateRange;               // Animations
```

The same holds for any functional object. `DemoGame.init` stores
`debugSink = renderer.debugDraw()::line;` once and passes the field every frame. A *non-capturing*
lambda (one that uses no local variables and no `this`) is a cached singleton and is fine anywhere.

**Per-call inputs in fields.** Because the kernel object is shared, its inputs go into fields
before dispatch (`this.dt = dt;` in `Animations.update`, `kernel.levelBase = start;` in
`TransformStore.update`). The job system's queue publication orders those plain writes before the
workers read them.

**Primitive maps.** `IntIntMap.get` is open addressing over two `int[]` arrays:

```java
    public int get(int key) {
        if (key == 0) {
            return hasZeroKey ? zeroValue : missing;
        }
        int pos = Hashing.mix(key) & mask;
        int k;
        while ((k = keys[pos]) != 0) {
            if (k == key) {
                return values[pos];
            }
            pos = (pos + 1) & mask;
        }
        return missing;
    }
```

No `Integer`, no `Entry` objects, no pointer chasing. Absent keys return a "missing value" chosen
at construction instead of `null`. The nightly baseline has 4 096 lookups at 7.9 µs for `IntIntMap`
against 26.3 µs for `HashMap<Integer, Integer>` (`CollectionsBench`).

**Cold paths kept out of hot methods.**
[`SdlThread.assertMainThread`](../../modules/sdl/src/main/java/dev/jeddy/sdl/SdlThread.java) is one
comparison; the message building lives in a separate method that only runs on failure:

```java
    public static void assertMainThread(String call) {
        if (Thread.currentThread() != mainThread) {
            throw wrongThread(call);
        }
    }

    private static IllegalStateException wrongThread(String call) {
        Thread marked = mainThread;
        return new IllegalStateException(
                call
                        + " must run on the SDL main thread ("
                        // ...
                        + "'. Send it through the main-thread command queue (DESIGN §5).");
    }
```

This keeps the hot method tiny (C2 inlines small methods eagerly) and keeps the string
concatenation out of it.

**Reserve the peak.** Arrays that can grow (`RenderPacket`'s instances, lights, decals, emitters,
debug lines, palettes) grow only past their capacity. Games call `RenderPacket.reserve(...)` once
with their known peak, as `DemoGame.init` does, so growth never happens mid-game (fix F5).

### Proving it, 1: `Allocations.assertNoAllocation`

The shared test helper lives in the `:testing` project, which every module's tests get (D18).
Here it is almost in full,
[`testing/src/main/java/dev/jeddy/testing/Allocations.java`](../../testing/src/main/java/dev/jeddy/testing/Allocations.java):

```java
public final class Allocations {
    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    /** Asserts that {@code body} allocates nothing, with default warm-up (25 rounds of 4000). */
    public static void assertNoAllocation(String what, Runnable body) {
        assertNoAllocation(what, 25, 4_000, body);
    }

    public static void assertNoAllocation(
            String what, int maxRounds, int iterationsPerRound, Runnable body) {
        long best = Long.MAX_VALUE;
        for (int round = 0; round < maxRounds; round++) {
            for (int i = 0; i < iterationsPerRound; i++) {
                body.run(); // warm-up, lets the JIT compile
            }
            long before = THREADS.getCurrentThreadAllocatedBytes();
            for (int i = 0; i < iterationsPerRound; i++) {
                body.run();
            }
            long allocated = THREADS.getCurrentThreadAllocatedBytes() - before;
            best = Math.min(best, allocated);
            if (allocated == 0) {
                return;
            }
            if (round >= 2) {
                try {
                    Thread.sleep(20); // give the background compiler time to install C2 code
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        Assertions.fail(
                what
                        + " allocates: at best "
                        + best
                        + " bytes per "
                        + iterationsPerRound
                        + " calls ("
                        + (best / (double) iterationsPerRound)
                        + " bytes/call)");
    }
    // ...
}
```

How it works:

- `getCurrentThreadAllocatedBytes()` (the HotSpot extension of `ThreadMXBean`) reads a per-thread
  counter of allocated bytes. The JDK documents it as an approximation; in HotSpot it includes the
  used part of the current TLAB, so two reads with nothing allocated in between differ by exactly
  zero.
- The code under test first runs interpreted, then C1-compiled, then C2-compiled, and only C2 does
  escape analysis. So the helper runs **rounds**: warm up, measure, and pass as soon as one round
  measures zero. Compilation happens on background threads, so from the third round it sleeps
  20 ms to let C2 install its code.
- It fails only if no round in 25 is clean, reporting the smallest amount seen. A real per-call
  allocation shows up in every round; one-time effects (class loading, a JIT event) do not.
- It measures **the calling thread only**. For `JobSystem.parallelFor` that means the dispatch
  path; worker allocations need the tools below.

A typical use, from
[`TransformStoreTest`](../../modules/scene/src/test/java/dev/jeddy/scene/TransformStoreTest.java):

```java
        Allocations.assertNoAllocation(
                "transform update",
                10,
                200,
                () -> {
                    for (int i = 0; i < 64; i++) {
                        t.setPosition(e[(i * 157) % e.length], i, 0, 0);
                    }
                    t.update(jobs);
                });
```

The lambda captures `t`, `e` and `jobs`, so it is an object. That is fine: it is created once,
before measuring, and calling `body.run()` allocates nothing. More than fifty test classes across
the modules use the helper.

### Proving it, 2: soak tests of the whole loop

Unit tests prove pieces. [`JeddyAppTest.bothThreadsAllocateNothingPerFrame`](../../modules/app/src/test/java/dev/jeddy/app/JeddyAppTest.java)
runs the real two-thread app loop under SDL's dummy driver and measures both threads **from a
third thread**, so no measuring code sits inside the loops (D66 explains why that matters):

```java
                                Thread.sleep(warmupMillis);
                                long deadline = System.currentTimeMillis() + 30_000;
                                while ((gameFrames.get() < 30_000 || mainFrames.get() < 30_000)
                                        && System.currentTimeMillis() < deadline) {
                                    Thread.sleep(50);
                                }
                                awaitJitQuiet(1500, 30_000);
                                for (int w = 0; w <= windows; w++) {
                                    if (w > 0) {
                                        Thread.sleep(windowMillis);
                                    }
                                    bytes[0][w] =
                                            Allocations.threadAllocatedBytes(gameThread.get());
                                    bytes[1][w] = Allocations.threadAllocatedBytes(mainThread);
                                    // ...
                                }
```

It runs uncapped until both threads have done at least 30 000 frames (or 30 s have passed), waits until the JIT has
compiled nothing for 1.5 s (`CompilationMXBean.getTotalCompilationTime` stops changing), then
samples both counters over four windows. The assertion allows at most one allocating window and
under 2 KiB in total: an allocation per frame shows up in every window, a one-time JIT event in at
most one. Why a JIT event allocates at all is the D70 story below.

### Proving it, 3: JMH with `-prof gc`

[JMH](https://github.com/openjdk/jmh) measures how fast code runs; its `gc` profiler also reports
`gc.alloc.rate.norm`, bytes allocated per operation. Benchmarks live in
[`bench/`](../../bench/README.md) and run through a hand-wired Gradle task (D14):

```
./gradlew :bench:jmh -Pjmh.includes=MathBench
./gradlew :bench:jmh -Pjmh.includes=JobBench "-Pjmh.args=-prof gc"
```

The nightly workflow runs every benchmark with `-prof gc` and checks the result with
[`.github/scripts/jmh-check.py`](../../.github/scripts/jmh-check.py) (D77). It compares scores
with the committed baseline (25 % threshold, because shared runners are noisy) and sweeps
allocation on a list of hot-path benchmarks:

```python
ZERO_ALLOC = [
    r"AudioBench\.", r"DrawBench\.cull", r"JobBench\.", r"MathBench\.", r"PhysicsBench\.",
    r"TransformBench\.", r"FfmBench\.(abs|mat4)", r"CollectionsBench\.(intIntMapGet|radixSort16k)",
    r"AssetBench\.(pakFind|vfsSize)", r"BaselineBench\.", r"AnimationBench\.",
]
NOISE = 16.0  # B/op
BACKGROUND = 64.0  # B per ms of operation time
```

Why a tolerance at all? `gc.alloc.rate.norm` sums the allocation of **every thread** in the forked
JVM, including JMH's own and the JVM's background threads, and divides by operations. Long
operations therefore pick up a background rate (the first nightly saw 3–29 B per ms of operation
time). A hot path fails above

```math
\text{allowed} = 16\ \text{B} + 64\ \tfrac{\text{B}}{\text{ms}} \times t_{\text{op}}
```

where $t_{\text{op}}$ is the time of one operation in ms. For `TransformBench.allDirty` at about
1.8 ms per operation that is $16 + 64 \times 1.8 \approx 131$ B/op, far below what one object per
transform (100 000 transforms) would allocate. Exact per-thread checks remain the job of
`Allocations.assertNoAllocation`.

### Proving it, 4: the perf capture

The final judge is the game itself.
[`PerfCapture`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/PerfCapture.java)
(DESIGN §13.3, M10-05) runs the iso demo's scripted perf scene (300 agents, fireballs, fog of war,
16k extra props, 256 lights) and, on every recorded frame, reads the allocation counters of the
game thread, the main thread and every job worker:

```java
    void recordGame(long nanos, int frameInstances, int frameLights, long animation) {
        if (gameFrames < capacity) {
            gameNanos[gameFrames] = nanos;
            animationNanos[gameFrames] = animation;
            gameAllocated[gameFrames] = currentThreadAllocated();
            workerAllocated[gameFrames] = workerSum();
            gameStamp[gameFrames] = System.nanoTime();
            gameFrames++;
        }
        // ...
    }

    /**
     * The JDK's thread bean, looked up once: {@code ManagementFactory.getThreadMXBean()} itself
     * allocates about 1 KB per call.
     */
    private static final com.sun.management.ThreadMXBean THREADS = /* ... */;
```

Rules that came from mistakes (D66, fix F9): the counters are read on the frame's *regular* path
every frame, not in a branch taken occasionally (the first run of a rarely taken branch
deoptimizes compiled code and allocates), and the thread bean is looked up once (each
`getThreadMXBean()` call allocates about 1 KB). Steady state is judged on the **second half** of
the measured window, after a warm-up in seconds, frames (default 600) and gameplay events.

```
./gradlew :iso-demo:run --args="--headless --perf 120 --warmup 30 --alloc-probe --fail-on alloc,flags"
```

That is essentially CI's command (CI also passes `--report build/perf`, the default, and JVM logging flags). `--fail-on` turns failed budgets into exit
code 4; the keys are `game`, `animation`, `main`, `gpu`, `alloc`, `flags`, `gc` and `load`. The
report goes to `build/perf/perf.json` and `perf.md`.

### Which system allocated?

With `--alloc-probe`,
[`AllocProbe`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/AllocProbe.java) reads the
game thread's counter between systems (the `a.mark(...)` calls you saw in `DemoGame.fixedUpdate`
in chapter 01) and charges the difference to the system that just ran:

```java
    void mark(int system) {
        if (!enabled || bean == null) {
            return;
        }
        long now = bean.getCurrentThreadAllocatedBytes();
        if (last != 0) {
            long delta = now - last;
            bytes[system] += delta;
            if (delta != 0 && events < MAX_EVENTS) {
                eventFrame[events] = frame;
                eventSystem[events] = system;
                eventBytes[events] = delta;
                events++;
            }
        }
        last = now;
        if (system == HUD) {
            frame++; // the frame's last mark
        }
    }
```

The report then gets a "Game thread allocation by system" table with one row per system that
allocated (the names come from `AllocProbe.NAMES`, such as `tick: crowd.update` or
`extract: world`), and a list of the second half's allocating frames by system.

### The D70 finding

CI kept failing on about 1.3 KB allocated once by the main thread, roughly 80 s into the window, in
a different place on every run (fix F10). The investigation (JFR with `-XX:-UseTLAB`, so every
allocation is an event) found that this was not engine code at all. In HotSpot, when a method gets
hot enough for a C2 compile, the thread that crosses the threshold calls
`CompileBroker::compile_method`, which resolves every not-yet-resolved string literal of the
method's class *on that thread* (`resolve_string_constants`). Resolving a literal creates the
`String`. So the first C2 compile of each class allocates a few hundred bytes to a few KB, once,
on whichever thread triggered it (D70, DESIGN §4.7). C1-only runs show nothing.

Methods called once per frame reach C2 after about 5 000 calls, which at 60 FPS is 80–90 s into a
run; rarely called classes compile minutes later. No fixed warm-up avoids that tail, and an AOT
cache did not either. The fix is in the measurement:
[`JitLog`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/JitLog.java) turns on
unified logging of `jit+compilation` into a file at run time through the `DiagnosticCommand` MBean
(`jcmd VM.log`), and the report attributes a second-half allocating frame to the JIT when a C2
compile started inside the frame or shortly after it:

```java
    static Compile explain(List<Compile> compiles, long fromMillis, long toMillis) {
        Compile any = null;
        for (Compile c : compiles) {
            if (c.uptimeMillis() < fromMillis - SLACK_BEFORE_MILLIS
                    || c.uptimeMillis() > toMillis + QUEUE_SLACK_MILLIS) {
                continue;
            }
            if (c.firstC2OfClass()) {
                return c;
            }
            if (any == null) {
                any = c;
            }
        }
        return any;
    }

    /** Clock slack between the frame stamps and the log's uptime decorations. */
    static final long SLACK_BEFORE_MILLIS = 2;

    /** How long a requested C2 compile may wait in the queue before it starts and is logged. */
    static final long QUEUE_SLACK_MILLIS = 500;
```

So a frame between JVM uptimes $t_0$ and $t_1$ that allocated is "JIT warm-up" if a C2 compile
started in $`[t_0 - 2\ \text{ms},\ t_1 + 500\ \text{ms}]`$ (the allocation happens at the *request*,
which precedes the logged start by the compile queue's wait), preferring a class's first C2
compile. Every other allocation still fails CI. At a capped 60 FPS in a real game, these one-time
allocations happen during the first minutes of play.

### Hidden allocations

The M10 fix list ([docs/perf/m10.md](../perf/m10.md)) is a catalogue of allocations the source
code does not show. Each was found by the tools above:

| Fix | Symptom | Cause | Fix |
|---|---|---|---|
| F2 | about 1 MB/s with nothing to load | idle asset IO virtual threads parked with a 50 ms timeout; each remount allocated a stack chunk | park untimed; `schedule()` unparks (D65) |
| F3 | 40–150 KB of garbage, minutes into a run | method-handle customization (below) | `CUSTOMIZE_THRESHOLD=-1` (D69) |
| F4 | allocation at the first positional sound | lazy jextract holders linking mid-frame | `Audio.prelink` (D65) |
| F5 | packet arrays growing mid-game | a fireball's light on top of 256 others | `RenderPacket.reserve` |
| F9 | the capture measured itself | `getThreadMXBean()` per call; rarely taken snapshot branches deoptimizing | bean looked up once; counters on the regular path (D66) |
| F10 | 1.3 KB once, at random | C2 compile requests resolving string literals | JIT log attribution (D70) |
| F11 | 96 KB and 32 KB in `Audio.update`, 7–9 minutes into the nightly | the run had silently lost its launcher flags | flags through `jvmArgumentProviders`; the capture checks them |

F11 is worth telling in full, because it involves every tool. The nightly perf capture found
96 KB and 32 KB allocated in `Audio.update` 7–9 minutes into the run: customized `LambdaForm`s
being spun. Three tools found it together: the allocation probe's per-system events, the late
class-load log (`-Xlog:class+load=info:file=...:uptime`, summarized by
[`.github/scripts/late-classes.py`](../../.github/scripts/late-classes.py), which lists the
classes loaded in the second half of the window, "late linkage"), and JFR allocation stacks taken
with tiny TLABs ([`.github/scripts/alloc-stacks.py`](../../.github/scripts/alloc-stacks.py), on
request in the nightly). The cause: the perf runs passed extra JVM options with `JavaExec.jvmArgs(...)`,
which *adds* to the run task's `jvmArguments`, and since the application's default flags are only
that property's convention, adding anything dropped them (DESIGN §14 warns about this). The runs
used G1 and had method-handle customization on. Two fixes: extra flags now go through
`jvmArgumentProviders` (see [`samples/iso-demo/build.gradle.kts`](../../samples/iso-demo/build.gradle.kts)),
and the perf capture checks that the launcher flags are present, failing CI under the `flags` key:

```java
    static String missingLauncherFlags() {
        List<String> missing = new ArrayList<>();
        boolean zgc = false;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            zgc |= bean.getName().startsWith("ZGC");
        }
        if (!zgc) {
            missing.add("-XX:+UseZGC");
        }
        if (!"-1".equals(System.getProperty("java.lang.invoke.MethodHandle.CUSTOMIZE_THRESHOLD"))) {
            missing.add("-Djava.lang.invoke.MethodHandle.CUSTOMIZE_THRESHOLD=-1");
        }
        // ... and --enable-native-access=...dev.jeddy.core...
        return String.join(", ", missing);
    }
```

### Try it

```
# dispatchDoesNotAllocate
./gradlew :core:test --tests 'dev.jeddy.core.job.JobSystemTest'
# the two-thread soak
./gradlew :app:test --tests 'dev.jeddy.app.JeddyAppTest'
./gradlew :iso-demo:run --args="--headless --perf 300 --warmup 60 --alloc-probe"
```

The soak test reads `jeddy.soakSeconds` (default 4) and `jeddy.soakWarmupSeconds` (default 2)
from the *test* JVM's system properties. Gradle does not pass `-D` options from its command line to
test JVMs, so the build forwards a Gradle property instead: `./gradlew :app:test
-Pjeddy.soakSeconds=600` runs the 10-minute soak (the `Test` task setup in
`build-logic/src/main/kotlin/jeddy.java-base.gradle.kts`). From an IDE, pass
`-Djeddy.soakSeconds=600` to the test run directly.

---

## Part 3: the JVM around the code

### Launcher flags

Every Jeddy program, in the repository and in game projects, runs with the same flags, defined
once in [`Applications.kt`](../../build-logic/src/main/kotlin/dev/jeddy/gradle/Applications.kt):

```kotlin
/** Every Jeddy module that calls restricted FFM methods (DESIGN §6.3). Unknown names only warn. */
val JEDDY_NATIVE_ACCESS_MODULES: List<String> = listOf(
    "dev.jeddy.core",
    "dev.jeddy.compress",
    "dev.jeddy.sdl",
    "dev.jeddy.gl",
    "dev.jeddy.window",
    "dev.jeddy.input",
    "dev.jeddy.asset",
    "dev.jeddy.audio",
    "dev.jeddy.render",
    "dev.jeddy.cooker",
)

/** The JVM flags every Jeddy program runs with (DESIGN §4.6 and §6.3). */
val JEDDY_JVM_ARGUMENTS: List<String> = listOf(
    "--enable-native-access=" + JEDDY_NATIVE_ACCESS_MODULES.joinToString(","),
    "-XX:+UseZGC",
    "-XX:+AlwaysPreTouch",
    // No method-handle customization: it compiles a hidden class (tens of KB of garbage) when a
    // downcall handle reaches its 127th call from interpreted or C1 code, which for rarely
    // used natives can be minutes into a game. C2 inlines our constant handles anyway (D69).
    "-Djava.lang.invoke.MethodHandle.CUSTOMIZE_THRESHOLD=-1",
)
```

The same list goes into `application.applicationDefaultJvmArgs` (for `run`), the image's
generated launchers and `jpackage` (D71, D75).

- **`--enable-native-access=...`**: opts the engine modules into restricted FFM methods (Part 1).
- **`-XX:+UseZGC`**: ZGC (generational in current JDKs) does nearly all its work concurrently
  and keeps pauses well under a millisecond, which matches the "GC pauses < 1 ms" budget in
  DESIGN §14. With zero steady-state allocation it rarely has anything to do. G1, the JDK default,
  is the supported alternative.
- **`-XX:+AlwaysPreTouch`**: touches every page of the heap at startup, so the OS never has to
  fault in a fresh page in the middle of a frame. A fixed heap (`-Xms` equal to `-Xmx`) goes well
  with it, but the shared flag list leaves the heap size to each game (DESIGN §4.6).
- **`CUSTOMIZE_THRESHOLD=-1`** (D69): `MethodHandle` invocation runs through internal
  `LambdaForm`s. When a handle is called from interpreted or C1 code, the JDK counts calls and, at
  the 127th, *customizes* it: it generates and loads a hidden class specialized for that handle.
  For natives called a few times a second (audio 3D positions) that happened minutes into a game,
  as 40–150 KB of garbage (fix F3). Jeddy's downcall handles are `static final`, so C2 inlines them
  either way; customization buys nothing and the flag turns it off. An unknown system property is
  ignored, so a future JDK that drops it costs nothing.

Note that unit tests do not run with these flags (they use the Gradle test JVM with
`--enable-native-access=ALL-UNNAMED` and a 1 GB heap); the perf capture is where the real
configuration is measured, and its `flags` check makes sure it *is* the real configuration.

### JIT-friendly code

DESIGN §4.6 lists the habits; here is what each buys:

- **`final` classes and small hot methods.** C2 inlines small methods (roughly, under 35 bytes of
  bytecode always, larger ones when hot) and inlining is what enables everything else: constant
  folding, escape analysis, bounds-check elimination across calls. Nearly every class in the
  engine is `final`; cold work (exception messages, growth) lives in separate methods.
- **No megamorphic calls in inner loops.** A call site that sees one or two receiver types is
  inlined; three or more becomes a virtual call. The job system calls `kernel.run(...)` once per
  *chunk*, and the loop over elements inside the kernel is plain code: the virtual dispatch is paid
  once per chunk (512 items in the transform store), not per item. Where a loop must handle several kinds, switch on an `int`
  kind instead.
- **Counted loops over SoA arrays.** `for (int i = start; i < end; i++)` over `float[]` lets C2
  hoist bounds checks out of the loop and, for simple bodies, vectorize (superword).
  [`Vec3Ops`](../../modules/math/src/main/java/dev/jeddy/math/Vec3Ops.java) says it plainly:
  "Simple counted loops so C2 can vectorize them." The M1-12 acceptance criterion asks for that
  vectorization to be confirmed with JMH's `-prof perfasm`, and `bench/README.md` names it as the
  way to check.
- **Branch-free inner loops.**
  [`CullOps.spheres`](../../modules/math/src/main/java/dev/jeddy/math/CullOps.java) hoists the 24
  plane values into locals, combines the six plane tests with the non-short-circuit `&`, and
  compacts the output without a branch:

  ```java
          for (int i = start; i < end; i++) {
              float x = cx[i];
              float y = cy[i];
              float z = cz[i];
              float nr = -r[i];
              boolean visible =
                      p0a * x + p0b * y + p0c * z + p0d >= nr
                              & p1a * x + p1b * y + p1c * z + p1d >= nr
                              & p2a * x + p2b * y + p2c * z + p2d >= nr
                              & p3a * x + p3b * y + p3c * z + p3d >= nr
                              & p4a * x + p4b * y + p4c * z + p4d >= nr
                              & p5a * x + p5b * y + p5c * z + p5d >= nr;
              out[n] = i;
              n += visible ? 1 : 0; // branch-free compaction
          }
  ```

  It always writes `i`, then advances the output cursor only if visible. A mispredicted branch
  costs 15–20 cycles; with half the objects visible in random order, branches would mispredict
  constantly.
- **Avoid `Math.fma` in kernels**: without hardware FMA it falls back to a very slow software path.
  `Float.floatToFloat16` and friends are intrinsics and are fine.
- **Determinism**: Java floating point is strict on every platform since JDK 17; gameplay code that
  must match bit for bit across machines uses `StrictMath` for transcendentals.

### Startup: the AOT cache

A Java program starts by loading, verifying and linking classes, then runs interpreted until the
JIT catches up. The AOT cache (JEPs 483, 514 and 515; [JEP 483](https://openjdk.org/jeps/483) is
the first) records a training run's loaded and linked classes, and method profiles, into a file
that later runs map in. Jeddy's packaging (D72,
[`Packaging.kt`](../../build-logic/src/main/kotlin/dev/jeddy/gradle/Packaging.kt)) runs the
finished image once with `-XX:AOTCacheOutput=aot/<name>.aot`, and the generated launchers add
`-XX:AOTCache=...` when that file exists:

```kotlin
        // The same flags for the training run and the launchers: an AOT cache only loads when
        // they match (the native-access list is checked).
        val flags = imageFlags(jvmArguments.get(), File(out, "runtime"))
```

A cache trained with different flags is rejected, so the training run uses exactly the
launchers' flags, with the `--enable-native-access` list filtered to the modules actually in the
`jlink` runtime. The iso demo trains headless (`--headless --quit-after 4 --no-audio`), so any
build machine can produce the cache without a GPU. Measured (DESIGN §4.6): the headless first
frame takes 0.21 s with the cache against 0.37 s without on a GitHub Linux runner, and 0.40 s
against 0.72 s on Windows.

```
# image with launchers and the AOT cache
./gradlew :iso-demo:distImage
./gradlew :iso-demo:distImage -Pjeddy.aot=false
```

---

## Part 4: Valhalla readiness

Project Valhalla's value classes ([JEP 401](https://openjdk.org/jeps/401)) will let the JVM store
small immutable objects without identity, flattened into arrays and fields like primitives. That
would make `Vec3f` as cheap as three floats. In JDK 27 it is not final, and rule 2 forbids preview
features, so the math library is written in **three layers** (DESIGN §8.1) that are useful today
and ready for the switch:

1. **Value types**: `record Vec2f/Vec3f/Vec4f/Quatf/Mat3f/Mat4f/Aabb3f/Ray3f/Planef/Spheref/Capsulef`.
   Immutable, `final`, no identity-sensitive operations (no `synchronized` on them, no `==`
   comparisons, no identity hash codes) and no interfaces that need identity. When value classes
   are final, each becomes a `value record` "with a one-word change". They are for APIs,
   configuration and gameplay code that is not per element.
2. **Mutable scratch types**: `MVec3f`, `MQuatf`, `MMat4f`, `final` classes whose methods modify
   `this` and return it. They are for per-frame code that must not allocate and must not rely on
   escape analysis: cameras, controllers, builders. Keep them as fields.
3. **Bulk kernels**: `Vec3Ops`, `QuatOps`, `Mat4Ops`, `AffineOps`, `CullOps`: static methods over
   `float[]` plus offsets (SoA). Every per-entity hot path uses these.

[`Vec3f`](../../modules/math/src/main/java/dev/jeddy/math/Vec3f.java) and
[`MVec3f`](../../modules/math/src/main/java/dev/jeddy/math/MVec3f.java) side by side:

```java
public record Vec3f(float x, float y, float z) {
    // ...
    /** Returns {@code this + o}. */
    public Vec3f add(Vec3f o) {
        return new Vec3f(x + o.x, y + o.y, z + o.z);
    }
```

```java
public final class MVec3f {
    /** X component. */
    public float x;
    // ...
    /** Adds {@code o}. */
    public MVec3f add(MVec3f o) {
        return set(x + o.x, y + o.y, z + o.z);
    }
```

and the kernel layer, for whole arrays at once,
[`AffineOps.composeTRS`](../../modules/math/src/main/java/dev/jeddy/math/AffineOps.java):

```java
    public static void composeTRS(
            float[] px,
            float[] py,
            float[] pz,
            float[] qx,
            float[] qy,
            float[] qz,
            float[] qw,
            float[] sx,
            float[] sy,
            float[] sz,
            int start,
            int end,
            float[] out) {
```

Once value records exist, layer 1 should become about as cheap as layer 2 for many uses (the JVM
can then keep the components in registers or flatten them into arrays without proving anything
about identity). Layer 3 stays either way, because SoA arrays beat any per-object layout for bulk
loops.

---

## Part 5: lock-free concurrency

### The memory model in brief

Two threads that share memory need *happens-before* edges, or one may never see the other's
writes, or see them in a different order. Locks create those edges, but locking means waiting.
Jeddy's handoffs are lock-free and use `VarHandle` access modes (or the `Atomic*` classes, which
are built on them) to create exactly the edges they need:

| Mode | Example | Guarantee |
|---|---|---|
| plain | `x = 1` | none across threads |
| opaque | `getOpaque` | the read is atomic and eventually sees new values; no ordering with other variables |
| release | `setRelease` | every read and write before it is visible to a thread that reads the value with acquire |
| acquire | `getAcquire` | no read or write after it moves before it |
| volatile | `getVolatile`, `setVolatile`, `volatile` fields | acquire plus release, plus one total order of all volatile accesses |
| atomic read-modify-write | `getAndAdd`, `compareAndSet`, `getAndSet` | atomic, with volatile semantics; `weakCompareAndSet` may fail spuriously |

The common pattern is **publish with release, consume with acquire**: the producer writes the
data with plain stores, then writes an index with `setRelease`; the consumer reads the index with
`getAcquire`, then reads the data with plain loads. On x86 acquire and release are free at the
CPU level (they only restrict compiler reordering), which is why they are preferred over volatile
where they suffice.

### False sharing and padding

CPUs move memory between cores in 64-byte cache lines. If the producer's `tail` and the
consumer's `head` sit in the same line, every write by one core invalidates the other core's copy,
even though they never touch the same variable. This **false sharing** can make a queue several
times slower. The fix is to put each hot field on its own line.

The JDK's `@Contended` annotation does that, but it is internal and ignored for application
classes without an extra JVM flag. Jeddy pads by hand, using the class hierarchy. From
[`SpscIntRing`](../../modules/core/src/main/java/dev/jeddy/core/concurrent/SpscIntRing.java):

```java
// Padding through inheritance keeps the producer's and consumer's hot fields on separate cache
// lines (the JVM lays out superclass fields first and never reorders across classes).
abstract class SpscIntPad0 {
    long p00, p01, p02, p03, p04, p05, p06, p07;
}

abstract class SpscIntConsumer extends SpscIntPad0 {
    long head; // next slot to read; written by the consumer only
    long tailCache; // consumer's last view of tail
}

abstract class SpscIntPad1 extends SpscIntConsumer {
    long p10, p11, p12, p13, p14, p15, p16, p17;
}

abstract class SpscIntProducer extends SpscIntPad1 {
    long tail; // next slot to write; written by the producer only
    long headCache; // producer's last view of head
}

abstract class SpscIntPad2 extends SpscIntProducer {
    long p20, p21, p22, p23, p24, p25, p26, p27;
}
```

Eight `long`s are 64 bytes, so the object looks like this:

```
| header | p00..p07 (64 B) | head, tailCache | p10..p17 (64 B) | tail, headCache | p20..p27 (64 B) | buffer, mask |
           ^ keeps the line     ^ consumer's        ^ separates       ^ producer's        ^ keeps the line
             before clear         line                the two           line                after clear
```

The rule the code relies on is in its comment: the JVM lays out superclass fields first. The pads
are all `long`s, so they leave no gaps that a later field could be packed into, and the pads stay
between the hot fields. [`MpmcIntQueue`](../../modules/core/src/main/java/dev/jeddy/core/concurrent/MpmcIntQueue.java)
does the same for `enqueuePos` and `dequeuePos`, and
[`PaddedAtomicLong`](../../modules/core/src/main/java/dev/jeddy/core/concurrent/PaddedAtomicLong.java)
puts a single counter alone on its line.

### The SPSC ring

A single-producer, single-consumer ring is the simplest lock-free queue: the producer owns `tail`,
the consumer owns `head`, both only increase, and the slot is `index & mask` in a power-of-two
array. The input events (main → game) and the main-thread command queue (game → main) both use
`SpscIntRing`. Writing a record of `n` ints:

```java
    public boolean offer(int[] src, int offset, int n) {
        if (n > buffer.length) {
            throw new IllegalArgumentException("record larger than the ring: " + n);
        }
        long t = tail;
        long wrap = t + n - buffer.length;
        if (headCache < wrap) {
            headCache = (long) HEAD.getAcquire(this);
            if (headCache < wrap) {
                return false;
            }
        }
        for (int i = 0; i < n; i++) {
            buffer[(int) (t + i) & mask] = src[offset + i];
        }
        TAIL.setRelease(this, t + n);
        return true;
    }
```

and reading it:

```java
    public boolean poll(int[] dst, int offset, int n) {
        long h = head;
        if (tailCache < h + n) {
            tailCache = (long) TAIL.getAcquire(this);
            if (tailCache < h + n) {
                return false;
            }
        }
        for (int i = 0; i < n; i++) {
            dst[offset + i] = buffer[(int) (h + i) & mask];
        }
        HEAD.setRelease(this, h + n);
        return true;
    }
```

- The record's ints are written with plain stores, then `tail` is published with `setRelease`.
  The consumer reads `tail` with `getAcquire` before reading the ints, so it either sees the whole
  record or none of it.
- `headCache` and `tailCache` are each side's last known view of the other side's index. The
  producer only reads the consumer's `head` (a cache line owned by the other core) when its cached
  value says the ring might be full. Most calls touch no shared line at all.
- Numeric example of the full check: capacity 8, `tail = 10`, `head = 3`, a record of `n = 6`.
  Seven ints are in use and one is free. `wrap = 10 + 6 - 8 = 8`; `head = 3 < 8`, so the ring is
  full for this record and `offer` returns `false` without writing anything.
- `head` and `tail` are `long`s that never wrap in practice ($2^{63}$ ints at a billion per second
  is about 292 years), so "full" and "empty" are simple subtractions.

### The MPMC queue

The job system's ready queue and free-slot list have many producers and many consumers.
[`MpmcIntQueue`](../../modules/core/src/main/java/dev/jeddy/core/concurrent/MpmcIntQueue.java)
implements Dmitry Vyukov's bounded MPMC queue: one sequence number per slot, and compare-and-set
on the positions.

```java
    public boolean offer(int value) {
        // ... negative values rejected (EMPTY is -1)
        long pos = (long) ENQUEUE.getOpaque(this);
        while (true) {
            int index = (int) pos & mask;
            long seq = (long) SEQ.getAcquire(sequence, index);
            long dif = seq - pos;
            if (dif == 0) {
                if (ENQUEUE.weakCompareAndSet(this, pos, pos + 1)) {
                    buffer[index] = value;
                    SEQ.setRelease(sequence, index, pos + 1);
                    return true;
                }
                pos = (long) ENQUEUE.getOpaque(this);
            } else if (dif < 0) {
                return false;
            } else {
                pos = (long) ENQUEUE.getOpaque(this);
            }
        }
    }
```

Slot `i` starts with sequence `i`. A producer at position `pos` may use the slot when its sequence
equals `pos` (free for this lap); it claims the position with a CAS, writes the value, and sets
the sequence to `pos + 1`, which tells consumers "filled". A consumer at position `pos` waits for
sequence `pos + 1`, claims with a CAS, reads, and sets the sequence to `pos + capacity`, which
makes the slot free for the producer one lap later. A sequence *behind* the position
(`dif < 0`) means full (for `offer`) or empty (for `poll`). Two threads never write the same slot
in the same lap, and the per-slot sequence carries the release/acquire edge for the value.

### The triple buffer, again

[01 Architecture](01-architecture.md#10-step-1-the-triple-buffer-handoff) walked through `TripleBuffer`. In
terms of this section: the producer's plain writes to the packet are ordered before its
`getAndSet` on `latest` (a volatile read-modify-write), and the consumer's `getAndSet` is ordered
before its plain reads of the packet. One atomic exchange per frame on each side is the entire
synchronization cost of handing 20 000 instances from the game thread to the main thread.

### Waiting without burning cores

When there is nothing to do, a thread can spin (lowest latency, burns a core), yield (gives the
core to another thread), or park (sleeps; wake-up costs tens of microseconds).
[`SpinParkWaiter`](../../modules/core/src/main/java/dev/jeddy/core/concurrent/SpinParkWaiter.java)
does all three in sequence, with the counter kept by the caller so the waiter itself is stateless
and shareable:

```java
    public int idle(int counter) {
        if (counter < spins) {
            Thread.onSpinWait();
        } else if (counter < spins + yields) {
            Thread.yield();
        } else {
            LockSupport.parkNanos(parkNanos);
        }
        return counter == Integer.MAX_VALUE ? counter : counter + 1;
    }
```

`Thread.onSpinWait()` compiles to the CPU's spin-loop hint (`PAUSE` on x86), which saves power
and frees resources for the core's sibling hyper-thread. The game thread waits for the main thread
with `SpinParkWaiter(100, 20, 50_000)`; the job system's waiter is `(256, 32, 50_000)`, and an idle
worker that reaches the park phase parks for up to 2 ms until a submit unparks it.

### Writing GPU memory from workers

One more ordering rule appears at the boundary with the GPU (DESIGN §5). Workers write instance
records straight into persistently mapped buffers, which are *write-combined*: the CPU buffers the
stores and sends them to the GPU in bursts, and such memory must never be read back (reads are
uncached and very slow). Write-combined stores are weakly ordered; a plain `setRelease` does not
flush them. The job system publishes chunk completion with an atomic decrement
(`REMAINING.getAndAdd(j, -1)`), which is a locked read-modify-write and therefore a full fence,
and the main thread waits on the frame's GL fence (`glClientWaitSync`) before it hands that region
to the workers again.

### Try it

```
./gradlew :core:test --tests 'dev.jeddy.core.concurrent.ConcurrencyTest'
./gradlew :core:test --tests 'dev.jeddy.core.concurrent.TripleBufferTest'
```

`ConcurrencyTest` streams 300 000 eight-int records through an `SpscIntRing` between two threads
and checks that none is torn, has three producers and three consumers pass 600 000 values through
an `MpmcIntQueue` and checks that the count and the sum match, and proves the rings allocate
nothing. (The M1-06
acceptance asked for stress runs of 10⁸ operations; the unit tests are a smaller version that runs
on every build.)

---

## Exercises

1. **See an allocation fail.** In a scratch test in `:core`, run
   `Allocations.assertNoAllocation("boxed", () -> sink += boxed.get(key))` over a
   `HashMap<Integer, Integer>` with a key of 1 000 000, then the same with an `IntIntMap`. Read the
   failure message. Then try a key of 100.
   <details><summary>Hint</summary><code>Integer.valueOf</code> caches -128..127, so the small key
   may pass even with the boxed map. C2's escape analysis can sometimes remove the box too; the
   test tells you what actually happens.</details>

2. **Capturing lambdas.** Copy `JobSystemTest.dispatchDoesNotAllocate` and move the kernel lambda
   *inside* the measured `Runnable`, so it is created per call. Make it capture a local variable.
   Predict the result, then run it.
   <details><summary>Hint</summary>The kernel is stored in a job slot, so it escapes and escape
   analysis cannot remove it. A lambda that captures nothing would be a cached singleton and
   pass.</details>

3. **Pointer returns cost an object.** Add a benchmark to `FfmBench` that links `memcpy` with an
   `ADDRESS` return layout and run both variants with `-prof gc`. Compare `gc.alloc.rate.norm`.
   <details><summary>Hint</summary><code>./gradlew :bench:jmh -Pjmh.includes=FfmBench
   "-Pjmh.args=-prof gc"</code>. The returned <code>MemorySegment</code> is an object per call
   unless the JIT removes it; that is why the existing descriptor returns
   <code>JAVA_LONG</code>.</details>

4. **Find the allocating system.** Put a deliberate `String s = "frame " + ctx.frame();` (used
   somewhere so it is not dead code) into `DemoGame.update` after the camera update, and run
   `./gradlew :iso-demo:run --args="--headless --perf 60 --warmup 20 --alloc-probe"`. Find it in
   the report, then remove it.
   <details><summary>Hint</summary>The probe charges bytes to the system whose
   <code>mark</code> comes next after the allocation. Look for the "update: camera" or "update:
   audio" row in the "Game thread allocation by system" table.</details>

5. **Measure false sharing.** Write a JMH benchmark with `@Group` and two `@GroupThreads(1)`
   methods that each increment their own counter: once with two adjacent elements of an
   `AtomicLongArray`, once with two `PaddedAtomicLong`s. Compare throughput.
   <details><summary>Hint</summary>Adjacent <code>long</code>s share a 64-byte cache line. Use
   <code>@State(Scope.Group)</code> for the shared counters.</details>

6. **Why off-heap uniforms.** Change `DebugRenderer.draw` to pass
   `MemorySegment.ofArray(packet.viewProjection)` to `glProgramUniformMatrix4fv` instead of the
   copied `matrix` segment. Run the iso demo, select the party and press T, so the turn-based
   overlay draws debug lines (`./gradlew :iso-demo:run`). Then run it again with
   `-Pjeddy.jvmArgs=-Djeddy.gl.critical=false`. Explain the difference, then revert.
   <details><summary>Hint</summary><code>glProgramUniformMatrix4fv</code> is on the critical
   allow-list, and only downcalls linked critical with heap access accept heap segments. The demo
   links critically by default; with the property set to <code>false</code> the call is a
   regular downcall and rejects the heap segment. The render module's GPU tests could not show
   the first case: <code>GpuTestContext</code> sets <code>jeddy.gl.critical=false</code>
   because it enables the GL debug callback. Note also that <code>ofArray</code> creates a
   wrapper object per call unless it is cached.</details>

7. **An allocation-free logger path (hard).** Add overloads such as
   `Logger.debug(String message, int a)` and `debug(String, int, int)` that check `isEnabled`
   before doing anything, so that a disabled debug log in a hot path costs one comparison and no
   varargs array. Prove it with `Allocations.assertNoAllocation` while the level is above DEBUG.
   <details><summary>Hint</summary>Fixed-arity overloads avoid the array at the call site; the
   boxing can happen inside, after the level check, because that path is cold. Check how
   <code>Logger.log</code> formats <code>{}</code> placeholders today.</details>

8. **A padded SPSC long ring benchmark (hard).** `SpscLongRing` exists in `core/concurrent`.
   Write a JMH benchmark that streams 10⁷ longs from one thread to another through it, then
   temporarily remove `SpscLongPad1` from its hierarchy (make `SpscLongProducer` extend
   `SpscLongConsumer` directly) and measure again.
   <details><summary>Hint</summary>Use two threads in a JMH group, or a plain timed test with
   <code>System.nanoTime</code>. Keep the change in a scratch branch; the padding is
   load-bearing.</details>

---

## Further reading

- [JEP 454: Foreign Function & Memory API](https://openjdk.org/jeps/454), and the
  `java.lang.foreign` package Javadoc (`Linker`, `Linker.Option.critical`, `Arena`,
  `MemorySegment`).
- [JEP 401: Value Classes and Objects](https://openjdk.org/jeps/401) (Project Valhalla).
- [JEP 483: Ahead-of-Time Class Loading & Linking](https://openjdk.org/jeps/483), and its
  follow-ups JEP 514 and JEP 515.
- The jextract project (openjdk/jextract) and its guide.
- [JMH](https://github.com/openjdk/jmh) and its samples, especially those on dead-code
  elimination, constant folding and profilers.
- Aleksey Shipilёv, "JVM Anatomy Quarks" (short articles on TLABs, escape analysis, scalar
  replacement, safepoints and more).
- Scott Oaks, *Java Performance* (2nd edition), and Benjamin Evans, James Gough and Chris
  Newland, *Optimizing Java*.
- Brian Goetz et al., *Java Concurrency in Practice*, and Doug Lea, "Using JDK 9 Memory Order
  Modes".
- Dmitry Vyukov, "Bounded MPMC queue" (1024cores).
- Martin Thompson et al., the LMAX Disruptor technical paper (padding, cached sequences, mechanical
  sympathy).
- Ulrich Drepper, "What Every Programmer Should Know About Memory" (cache lines, false sharing).
- In this repository: [DESIGN.md](../DESIGN.md) §4, §6, §8.1, §9.1, §13.3, decisions D12, D14,
  D17, D18, D22, D24, D29, D65, D66, D69, D70, D72, D77; [docs/perf/m10.md](../perf/m10.md);
  [bench/README.md](../../bench/README.md).

---

[← 11 Physics, navigation and crowds](11-physics-navigation-crowds.md) · [Index](README.md) · [13 The user interface →](13-ui.md)
