# 12 — Fast Java: FFM, zero allocation and the JIT

*Jeddy is a game engine written in Java that calls C libraries (SDL3, SDL3_mixer, OpenGL, zstd)
and promises a steady frame rate. This chapter explains the three things that make that possible
and builds the code behind each of them from empty files: how Java calls C cheaply and safely
through the Foreign Function & Memory API, how the engine runs a frame without allocating a single
byte, and how it cooperates with the JIT compiler and the garbage collector instead of fighting
them. On the way it builds the proofs — the allocation assertion every hot path is tested with,
the benchmark harness and its nightly check, and the perf capture that measures the whole game —
and the lock-free queues that let threads hand work to each other without waiting. It ends with a
real perf report read line by line, and the catalogue of allocations that no line of source code
shows, each one found by the tools this chapter builds.*

**Before you start.** You need [Lesson 0](lessons/00-foundations.md): Java's number types and
overflow (§4), two's complement (§5), masks, shifts and rounding up to a power of two (§6, above
all §6.5–6.6), memory, the heap, object headers, native memory and alignment (§8), the CPU, caches,
cache lines, threads, `volatile` and compare-and-set (§9), frame budgets (§11) and the short
introduction to FFM (§12). [Lesson 3](lessons/03-data-layout.md) is the companion of this chapter:
it measures what an object costs in memory (§3) and teaches inlining, monomorphic and megamorphic
calls with its `ComponentsBench` (§13–14); this chapter summarizes that and adds the rest of the
JIT. [Lesson 1](lessons/01-buffers-in-flight.md) builds a lock-free bump allocator with a
compare-and-set loop (§11), which this chapter assumes. From the chapters: [01](01-architecture.md),
on threads, happens-before, the job system and the triple buffer, which this chapter builds on
without repeating; and [05](05-opengl-from-java.md), on OpenGL from Java, which builds the GL
binding generator, `GLLinker` and the debug callback that this chapter only summarizes. Chapter
[02](02-math-foundations.md), on the math module's three layers, measured escape analysis on
`Vec3f`; this chapter quotes that measurement.

**What you will build**

| Piece | File | What it does |
|---|---|---|
| A downcall, from nothing | [`bench.FfmBench`](../../bench/src/main/java/dev/jeddy/bench/FfmBench.java) | links C's `abs` and `memcpy` four ways and measures what each costs |
| Loading the libraries | [`core.natives.NativeLoader`](../../modules/core/src/main/java/dev/jeddy/core/natives/NativeLoader.java), [`Platform`](../../modules/core/src/main/java/dev/jeddy/core/natives/Platform.java) | finds SDL3, SDL3_mixer and zstd, checks their SHA-256 and loads them in dependency order |
| Native memory you own | [`core.memory.OffHeap`](../../modules/core/src/main/java/dev/jeddy/core/memory/OffHeap.java), [`MemoryStats`](../../modules/core/src/main/java/dev/jeddy/core/memory/MemoryStats.java), [`FrameAllocator`](../../modules/core/src/main/java/dev/jeddy/core/memory/FrameAllocator.java), [`CStrings`](../../modules/core/src/main/java/dev/jeddy/core/memory/CStrings.java) | blocks with a deterministic lifetime, per-frame scratch without `malloc`, C strings made once |
| Reading a C struct | [`input.EventPump`](../../modules/input/src/main/java/dev/jeddy/input/EventPump.java) | reads SDL's events and text without creating one object |
| The allocation assertion | [`testing.Allocations`](../../testing/src/main/java/dev/jeddy/testing/Allocations.java) | fails a test if code allocates once the JIT has compiled it |
| The SPSC ring | [`core.concurrent.SpscIntRing`](../../modules/core/src/main/java/dev/jeddy/core/concurrent/SpscIntRing.java), [`SpscLongRing`](../../modules/core/src/main/java/dev/jeddy/core/concurrent/SpscLongRing.java) | one producer, one consumer, whole records, hot fields padded onto their own cache lines |
| The MPMC queue | [`core.concurrent.MpmcIntQueue`](../../modules/core/src/main/java/dev/jeddy/core/concurrent/MpmcIntQueue.java), [`PaddedAtomicLong`](../../modules/core/src/main/java/dev/jeddy/core/concurrent/PaddedAtomicLong.java) | any number of producers and consumers, one sequence number per slot (Vyukov) |
| The benchmark harness | [`jeddy.jmh.gradle.kts`](../../build-logic/src/main/kotlin/jeddy.jmh.gradle.kts), [`JmhArguments.kt`](../../build-logic/src/main/kotlin/dev/jeddy/gradle/JmhArguments.kt), [`jmh-check.py`](../../.github/scripts/jmh-check.py) | runs JMH from Gradle; the nightly compares scores with a baseline and sweeps allocation |
| The perf capture | [`PerfCapture`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/PerfCapture.java), [`AllocProbe`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/AllocProbe.java), [`JitLog`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/JitLog.java) | measures the whole demo: frame times, allocation per thread and per system, GC, JIT warm-up |
| Launcher flags and the AOT cache | [`Applications.kt`](../../build-logic/src/main/kotlin/dev/jeddy/gradle/Applications.kt), [`Packaging.kt`](../../build-logic/src/main/kotlin/dev/jeddy/gradle/Packaging.kt) | the JVM flags every Jeddy program runs with; a training run that makes startup faster |

Design background: [DESIGN](../DESIGN.md) §4 (performance architecture, the rules of this
chapter), §5 (threads), §6 (the native layer), §8.1 (the math module's layers), §9.1 (the GL
bindings), §13.3 (the perf capture), §14 (build, CI and budgets); the measurements of milestone M10
in [docs/perf/m10.md](../perf/m10.md); the FFM numbers in [bench/README.md](../../bench/README.md).

**Contents**

- Part A — The ideas
  1. [The rules, and the budget they protect](#1-the-rules-and-the-budget-they-protect)
  2. [How the JVM runs your code](#2-how-the-jvm-runs-your-code)
  3. [Where objects come from: TLABs, the counter and the collector](#3-where-objects-come-from-tlabs-the-counter-and-the-collector)
  4. [Calling C: the Foreign Function & Memory API in depth](#4-calling-c-the-foreign-function--memory-api-in-depth)
  5. [Method handles inside the JVM: customization](#5-method-handles-inside-the-jvm-customization)
  6. [Measuring without fooling yourself](#6-measuring-without-fooling-yourself)
  7. [Lock-free handoffs: memory order on real hardware](#7-lock-free-handoffs-memory-order-on-real-hardware)
- Part B — Building it
  8. [Step 1: a downcall from an empty file](#8-step-1-a-downcall-from-an-empty-file)
  9. [Step 2: loading the libraries](#9-step-2-loading-the-libraries)
  10. [Step 3: native memory you own](#10-step-3-native-memory-you-own)
  11. [Step 4: reading a C struct without allocating](#11-step-4-reading-a-c-struct-without-allocating)
  12. [Step 5: the allocation assertion](#12-step-5-the-allocation-assertion)
  13. [Step 6: the single-producer, single-consumer ring](#13-step-6-the-single-producer-single-consumer-ring)
  14. [Step 7: the multi-producer, multi-consumer queue](#14-step-7-the-multi-producer-multi-consumer-queue)
  15. [Step 8: a padded counter and the long ring](#15-step-8-a-padded-counter-and-the-long-ring)
  16. [Step 9: the benchmark harness and the nightly check](#16-step-9-the-benchmark-harness-and-the-nightly-check)
  17. [Step 10: the perf capture](#17-step-10-the-perf-capture)
  18. [Step 11: launcher flags and the AOT cache](#18-step-11-launcher-flags-and-the-aot-cache)
- Part C — Using it and proving it
  19. [Zero allocation across the engine: the patterns](#19-zero-allocation-across-the-engine-the-patterns)
  20. [Bindings in practice: jextract, the GL generator and prelinking](#20-bindings-in-practice-jextract-the-gl-generator-and-prelinking)
  21. [Critical linking in practice](#21-critical-linking-in-practice)
  22. [Code the JIT likes](#22-code-the-jit-likes)
  23. [Valhalla readiness](#23-valhalla-readiness)
  24. [The tests, walked through](#24-the-tests-walked-through)
  25. [The perf capture in practice](#25-the-perf-capture-in-practice)
  26. [Things to run](#26-things-to-run)
- [Exercises](#exercises)
- [Glossary](#glossary)
- [Further reading](#further-reading)

---

# Part A — The ideas

## 1. The rules, and the budget they protect

### 1.1 Six rules

[CLAUDE.md](../../CLAUDE.md) lists six **non-negotiables** for every line of engine code. Each one
exists because the alternative was measured, or is known, to cost frames. Here they are with the
reason behind each and the section of this chapter that builds the machinery for it.

1. **Performance first: 0 bytes allocated per frame on hot paths.** The game loop, the render
   loop, job kernels and the event pump create no Java objects once the game is running. §1.2
   computes what a little garbage costs; §3 explains where objects come from; §12, §16 and §17
   build the three tools that prove the rule holds; §19 collects the patterns that keep it.
2. **Stable Java only.** No `--enable-preview`, no `jdk.incubator.*` (decision **D4**, DESIGN §1).
   A preview feature can change or disappear in the next release, and a game must keep running on
   the JDK it ships with. That rules out the Vector API (still incubating in JDK 27, DESIGN §2) and
   value classes (JEP 401, targeted as a preview in JDK 28), so the math library is written to
   *become* value classes later (§23).
3. **FFM rules.** Downcall handles live in `static final` holders (§2.5, §4.4), critical linking
   only for an allow-list of extremely short calls (§4.5, §21), never `Arena.ofAuto()` (§4.8), and
   no Java code on the audio thread (§4.6). Each rule exists because the alternative is slow (a
   handle the JIT cannot see through), dangerous (a critical call that blocks or calls back into
   Java stalls or crashes the JVM) or non-deterministic (memory freed whenever the collector gets
   round to it).
4. **The SDL main thread is the render thread.** A platform rule;
   [chapter 01, on threads](01-architecture.md#3-threads-who-may-call-what), explains it.
5. **Module boundaries.** [Chapter 01, on modules](01-architecture.md#2-modules-who-may-know-whom),
   explains them, including the rule that runtime modules have no third-party dependencies. This
   chapter adds one consequence: only the modules that need native access get it (§9.4).
6. **Prove it.** "Every hot-path change ships with an allocation test and, where relevant, a JMH
   benchmark." A performance claim nobody measures decays: one innocent `"" + x` in a per-frame
   method undoes it, and the source code does not show the cost. §6 explains how to measure
   without fooling yourself; Part B builds the tools; §25 lists what they found.

### 1.2 What a little garbage costs

Allocating one object is cheap: on HotSpot it is a pointer increment in memory the thread owns
(§3.1), a few instructions. The cost comes later and elsewhere, as work for the **garbage
collector** (the GC, L00 §8.2), which must find the objects nothing refers to any more and reuse
their memory.

How much garbage is "a little"? Take a game that allocates a single kibibyte per frame — one short
string and a few boxed numbers would do it. At $f$ frames per second for $T$ seconds it allocates

$$B = b \cdot f \cdot T$$

bytes, where $b$ is the bytes per frame. With $b = 1024$, $f = 144$ (a fast monitor) and
$T = 3600$ (an hour of play):

$$B = 1024 \cdot 144 \cdot 3600 = 530{,}841{,}600 \text{ bytes} = \frac{530{,}841{,}600}{2^{20}} \text{ MiB} = 506.25 \text{ MiB}.$$

At 60 frames per second it is $1024 \cdot 60 \cdot 3600 = 221{,}184{,}000$ bytes, about 211 MiB
an hour. Every one of those bytes has to be found dead and reclaimed. Even a **concurrent**
collector, one that does its work on its own threads while the game keeps running (§3.3), takes CPU
time from the job workers, competes with the game for memory bandwidth and evicts the game's data
from the caches (L00 §9.2). On a two-core laptop there is no idle core for it to use.

If a frame allocates nothing, the collector has nothing to do. That is not a figure of speech: the
ten-minute headless capture of milestone M10 ([docs/perf/m10.md](../perf/m10.md)) recorded "no
collection at all in 11 minutes", and the two-minute capture run for this chapter (§25.1) logged
one collection cycle, 2.8 seconds after the JVM started, while the level was loading — and none in
the remaining 150 seconds.

---

## 2. How the JVM runs your code

Everything in this chapter depends on what the JVM does with a method between the first time it
runs and the thousandth. This section follows a method through that life.

### 2.1 Bytecode, the interpreter and two compilers

`javac` does not produce machine code. It produces **bytecode**: instructions for an imaginary
stack machine, stored in the `.class` file. `javap -c` prints them. For

```java
static int hot(int x) {
    return x * 31 + 7;
}
```

it prints (JDK 27):

```
0: iload_0          push x
1: bipush 31        push 31
3: imul             pop two, push x * 31
4: bipush 7         push 7
6: iadd             pop two, push the sum
7: ireturn          return the top of the stack
```

The numbers on the left are byte offsets: this method is **8 bytes of bytecode**. That size matters
later, because the JIT decides what to inline by counting bytecode bytes (§2.4).

The JVM (HotSpot, in the JDK the engine uses) runs bytecode in three ways:

- the **interpreter** reads one bytecode at a time and does what it says. It starts at once, but
  every instruction costs a lookup and a jump, and values travel through memory rather than staying
  in registers;
- **C1**, the client compiler, turns a method into machine code quickly, with simple
  optimizations, and can insert **profiling** code that counts how often each branch is taken and
  which classes reach each call;
- **C2**, the server compiler, takes much longer and optimizes aggressively, using C1's profile to
  **speculate** (§2.3): it compiles the common case and leaves a trap door for the rest.

Compilation happens on background **compiler threads** (two on the 2-core container used for this
chapter: `-XX:+PrintFlagsFinal` reports `CICompilerCount = 2`). The thread that ran the method keeps
running it in the old form until the new code is installed; the next call uses the new code. This
is why "just-in-time" (**JIT**) compilation shows up as a gradual speed-up, not a pause.

### 2.2 Tiers and thresholds

HotSpot numbers the forms a method can run in, its **tiers**:

| Tier | Runs as | Typical use |
|---|---|---|
| 0 | the interpreter | every method at first |
| 1 | C1, no profiling | trivial methods (getters) that C2 could not improve |
| 2 | C1, light profiling | a stop-gap when C2 is busy |
| 3 | C1, full profiling | the normal second step |
| 4 | C2 | hot methods, for good |

The usual path is 0 → 3 → 4. Each method has two counters, $i$ (how often it was invoked) and $b$
(how often a loop inside it jumped back, a **back-edge**). The policy (simplified; HotSpot scales
the thresholds up when the compile queue is long) moves a method on when

$$\text{0} \to \text{3}: \quad i \ge 200 \quad\text{or}\quad (i \ge 100 \text{ and } i + b \ge 2000)$$

$$\text{3} \to \text{4}: \quad i \ge 5000 \quad\text{or}\quad (i \ge 600 \text{ and } i + b \ge 15000)$$

These are the JDK 27 defaults, printed by `java -XX:+PrintFlagsFinal -version`:
`Tier3InvocationThreshold` 200, `Tier3MinInvocationThreshold` 100, `Tier3CompileThreshold` 2000,
`Tier4InvocationThreshold` 5000, `Tier4MinInvocationThreshold` 600, `Tier4CompileThreshold` 15000.

**Worked example: a method called once per frame.** At 60 frames per second it reaches tier 3
after about $200 / 60 = 3.3$ seconds and tier 4 after about $5000 / 60 \approx 83$ seconds. The
perf capture of §25.1 shows exactly this. Its JIT log (§6.4) has these lines for
`PerfCapture.recordMainAllocation`, which runs once per main-thread frame from the moment the
measuring window opens, 33.3 seconds into the run:

```
[37521ms]  4294   3   dev.jeddy.samples.iso.PerfCapture::recordMainAllocation (46 bytes)
[119182ms] 4475   4   dev.jeddy.samples.iso.PerfCapture::recordMainAllocation (46 bytes)
[119184ms] 4294   3   dev.jeddy.samples.iso.PerfCapture::recordMainAllocation (46 bytes)   made not entrant: not used
```

Tier 3 came $37.5 - 33.3 = 4.2$ s after the first call, about 255 calls; tier 4 came
$119.2 - 33.3 = 85.9$ s after it, about $85.9 \times 60 \approx 5{,}155$ calls: the threshold of
5000 plus the time the request waited in the compile queue. The third line is the tier-3 code being
thrown away once the tier-4 code is installed ("made not entrant": no new call may enter it).
Remember the 83 seconds: it is the reason the perf capture cannot simply wait out the JIT (§25.2).

A method that is called rarely but contains a long loop is compiled while the loop runs: the
back-edge counter triggers an **on-stack replacement** (OSR) compile, and the running loop jumps
from the interpreter into the compiled code in the middle of the method. JIT logs mark OSR compiles
with `%`.

### 2.3 Profiles, speculation and deoptimization

C2 compiles what the profile says happens, not everything that could happen. If a branch was never
taken in the 5,000 profiled calls, C2 may compile it as an **uncommon trap**: instead of the
branch's code, a jump into the JVM. If a call site only ever saw one class, C2 inlines that class's
method behind a one-instruction class check (§2.4) and traps on any other class. If no subclass of a
class is loaded, C2 treats its methods as final and records the assumption, so loading such a
subclass later invalidates the code.

When a trap is hit, the JVM **deoptimizes**:

1. it marks the compiled method "not entrant", so no new call enters it;
2. it rebuilds the compiled frame as interpreter frames — including, on the heap, any object that
   escape analysis (§2.6) had dissolved into registers, because the interpreter needs real objects;
3. it resumes the method in the interpreter, where it profiles again and is later recompiled with
   the new knowledge.

A tiny program run for this chapter (`CustFinal`, §5.2) shows all of it in its JIT log
(`-Xlog:jit+compilation=debug`, JDK 27+35, the 2-core container):

```
[162ms]  474       3       CustFinal::call (8 bytes)
[167ms]  489       4       CustFinal::call (8 bytes)
[168ms]  474       3       CustFinal::call (8 bytes)   made not entrant: not used
[181ms]  491 %     3       CustFinal::main @ 11 (82 bytes)
[193ms]  493 %     4       CustFinal::main @ 11 (82 bytes)
[196ms]  491 %     3       CustFinal::main @ 11 (82 bytes)   made not entrant: OSR invalidation of lower level
[201ms]  493 %     4       CustFinal::main @ 11 (82 bytes)   made not entrant: uncommon trap
```

The `%` lines are the OSR compiles of `main`'s loop, which starts at bytecode 11. The last line is
a deoptimization. While the loop was being profiled it had never ended, so C2 compiled its exit test
as an uncommon trap; when the loop finally ended, the trap fired. Running again with
`-Xlog:deoptimization=debug` names it: `trap_bci=13 ... unstable_if`, and bytecode 13 of `main` is
the loop's exit test `if_icmpgt`. (Timestamps and compile ids differ from run to run.)

**Why this matters for zero allocation.** A deoptimized method runs in the interpreter until it is
recompiled, and the interpreter performs every allocation that C2 had removed. A rarely taken
branch in a per-frame method is therefore a small time bomb: the first time it runs, minutes into
the game, the frame allocates. The perf capture's own measurement code once did exactly that (fix
F9, decision **D66**, §17.2): its snapshot branches ran only occasionally, and their first run
deoptimized the code being measured.

### 2.4 Inlining

[Lesson 3](lessons/03-data-layout.md#13-why-the-view-costs-nothing-what-the-jit-does) §13 teaches
inlining in full; here is what this chapter needs from it. **Inlining** copies a called method's
body into the caller. It removes the call, and more importantly it lets the compiler optimize caller
and callee as one piece: keep values in registers across what used to be a call, fold constants,
remove bounds checks, and see that an object never leaves (§2.6). Every other optimization depends
on it.

C2 inlines a call when it knows the target and the target is small enough. The JDK 27 limits
(printed by `-XX:+PrintFlagsFinal`):

| Flag | Value | Meaning |
|---|---|---|
| `MaxInlineSize` | 35 | bytes of bytecode up to which a method is inlined even at a rarely executed call site |
| `FreqInlineSize` | 325 | bytes of bytecode up to which a method is inlined at a hot call site |
| `InlineSmallCode` | 2500 | a callee that already has compiled code larger than this many bytes of machine code is not inlined |
| `MaxInlineLevel` | 15 | how deep inlining may nest |

So `hot` above (8 bytes) is always inlined. `SdlThread.assertMainThread` (§19.5) is 15 bytes and is
inlined everywhere. `SpscIntRing.offer(int[], int, int)` (§13) is 128 bytes: inlined at hot call
sites only. Chapter [04](04-cameras-and-projection.md), on the sphere test, counts its 58 bytes the
same way.

The JIT knows the target when the method is `static`, `private` or `final`, or belongs to a `final`
class; or when the profile shows one receiver class at the call site (**monomorphic**) or two
(**bimorphic**), which C2 inlines behind a class check (and deoptimizes if a third class arrives).
With three or more classes (**megamorphic**) it emits a real virtual call through the class's
method table, and nothing is optimized across it. L03 §14 measured the difference on 10,000
entities: 142 µs per tick with a megamorphic `update()` against 4.8 µs for the same work over
primitive arrays.

### 2.5 Constants: why `static final` matters

C2 treats a `static final` field of an initialized class as a **constant**: it reads the value at
compile time and compiles as if the value were written into the code. For a number that allows
constant folding. For an object — a `MethodHandle`, a `VarHandle`, a `ValueLayout` — it allows
much more: C2 can look *inside* the object at compile time. A downcall handle in a `static final`
field is a handle whose target C function, signature and options C2 knows, so the Java call compiles
to roughly what a C compiler emits for a direct call (§4.4).

Instance `final` fields do not get this treatment in general: reflection and other low-level means
can change them after construction, so HotSpot does not trust them (the switch
`-XX:+TrustFinalNonStaticFields` exists only as an experimental flag and is off). A handle in an
instance field, an array or a map is just an object to C2, and calling it goes through the generic
method-handle machinery (§5).

**The holder idiom** combines a constant with laziness. Each C function gets its own tiny nested
class whose only member is the `static final` handle. The JVM initializes a class the first time
code uses it, exactly once, even if several threads race to it (the class initialization lock, Java
Language Specification §12.4.2). So the handle is created at the function's first call, functions
a program never calls are never linked, and after initialization the handle is a compile-time
constant. jextract emits holders for every C function (§20.1), and the GL generator emits one per GL
command ([chapter 05, on one downcall from Java to the
driver](05-opengl-from-java.md#52-one-downcall-from-java-to-the-driver)). The JDK uses the same
idiom itself: `Arena.global()` returns `Holder.GLOBAL` from a class declared inside the method.

The idiom has one cost: the first call creates the handle, which loads classes and allocates. §20.3
shows what happens when that first call comes in the middle of a game.

### 2.6 Escape analysis and scalar replacement

An object **escapes** a method when something outside the method can see it: it is stored into a
field or an array element, passed to a method that is not inlined, returned, or thrown. After
inlining, C2 runs **escape analysis** on every allocation in the compiled code. An object that
provably does not escape needs no heap memory at all: C2 keeps its fields in registers or on the
stack and deletes the allocation. That is **scalar replacement**.

It is powerful, and it is the reason a temporary `new Vec3f(...)` in a small hot method often costs
nothing. It is also fragile:

- **only C2 does it.** The interpreter and C1 allocate every object, so the first thousands of calls
  always allocate (§2.2);
- **it needs inlining.** An object passed to a call that is too big (§2.4) or megamorphic escapes —
  and whether a JDK method's calls are inlined depends on a profile shared by every caller in the
  process (exercise 1 shows `HashMap.get` boxing or not depending on how the program was launched);
- **it gives up at merges and in large methods.** An object that is one of two allocations merging
  at a join, or that lives in a method too large to analyse fully, may be kept;
- **any refactoring can change all of the above**, and nothing in the source says which case you are
  in.

Measured, on the 2-core container (JDK 27+35):

| Code | Interpreter, C1 | After C2 | Source |
|---|---|---|---|
| `Vec3f.cross` (returns a new `Vec3f`) | 24 B per call | 0 | [chapter 02, on the math module's layers](02-math-foundations.md#14-step-1-the-module-and-its-three-layers) |
| `Mat4f.mul` (three temporary `float[16]` and a result) | 312 B per call | 160 | the same |
| `HashMap<Integer, Integer>.get(1_000_000)` (boxes the key) | 16 B per call | 0 when compiled with `javac`; 16 when run through the source launcher | measured for this chapter, exercise 1 |
| a downcall returning a pointer, only `.address()` used | 32 B per call | 0 | measured for this chapter, §4.10 |
| the same call, the returned segment stored in a field | 32 B per call | 32 | the same |

The pattern is clear: C2 removes the objects that obviously stay local, removes some of the
others, and can do nothing about an object that escapes. DESIGN §4.2 draws the conclusion the
engine lives by: systems own pre-allocated scratch objects "so they don't rely on escape analysis".
Code that would pass the allocation assertion only thanks to escape analysis is written
differently (§19).

---

## 3. Where objects come from: TLABs, the counter and the collector

### 3.1 Allocating an object

The heap's youngest region (**eden**) is shared by all threads. If every `new` had to coordinate
with the other threads to claim memory, allocation would need an atomic instruction each time. So
each thread owns a **thread-local allocation buffer** (**TLAB**): a chunk of eden that only it
allocates from. Allocating an object of $s$ bytes is then:

```
if (top + s <= end) {      // room left in my TLAB?
    obj = top;
    top = top + s;         // the "pointer bump"
    write obj's header, zero its fields
} else {
    slow path: retire this TLAB, take a new chunk of eden (or allocate a very large
    object directly in shared memory), then allocate
}
```

No other thread is involved, so the common case is a handful of instructions. TLAB sizes adapt to
how fast each thread allocates (`-XX:+ResizeTLAB`, on by default) and never go below
`MinTLABSize`, 2,048 bytes.

How big is an object? L00 §8.2 and L03 §3 give the rule: on JDK 27 an object has an 8-byte header
(**compact object headers** are the default in JDK 27, JEP 534 per DESIGN §2), then its fields, and
the total is rounded up to a multiple of 8; an array has the header, a 4-byte length, then its
elements. A program run for this chapter measured the bytes each construct allocates under `-Xint`
(the interpreter only, so no escape analysis), with the thread allocation counter of §3.2:

| Construct | Bytes (JDK 27 default) | Bytes with `-XX:-UseCompactObjectHeaders` |
|---|---|---|
| `new Object()` | 8 | 16 |
| `Integer.valueOf(100)` (cached, −128 … 127) | 0 | 0 |
| `Integer.valueOf(1_000_000)` | 16 | 16 |
| `new int[0]` | 16 | 16 |
| `new int[1]` | 16 | 24 |
| `new float[16]` | 80 | 80 |
| `record V3(float x, float y, float z)` | 24 | 24 |
| a lambda that captures nothing | 0 | 0 |
| a lambda that captures one local `int` | 16 | 16 |
| a varargs call `sum(1, 2, 3)` | 24 | 32 |
| `MemorySegment.ofArray(float[16])` | 72 | 72 |
| `segment.asSlice(0, 16)` | 32 | 40 |
| `"x" + field` (a one-digit `int`) | 48 | 48 |

Check two by hand. `Integer` with compact headers: 8 (header) + 4 (the `int`) = 12, rounded up
to 16. A `float[16]`: 8 + 4 (length) + 64 (sixteen floats) = 76, rounded up to 80. The varargs
call allocates an `int[3]`: 8 + 4 + 12 = 24. The program is exercise 2; run it and compare.

### 3.2 The per-thread allocation counter

HotSpot keeps, for every thread, the number of bytes it has allocated. The counter is updated when
a TLAB is retired; reading it through
`com.sun.management.ThreadMXBean.getCurrentThreadAllocatedBytes()` adds the used part of the current
TLAB, so the value is exact at the moment of reading. Three facts make it the engine's measuring
stick, each measured for this chapter:

- **Two reads in a row differ by exactly 0.** Nothing is allocated between them, and reading does not
  allocate.
- **Reading another thread's counter, `getThreadAllocatedBytes(threadId)`, allocates nothing either**
  (0.0 bytes per call over 1,000 calls). A monitor thread can therefore sample the game and main
  threads without disturbing them (§24.6).
- **Getting the bean is not free.** `ManagementFactory.getThreadMXBean()` allocated 728 bytes per
  call on JDK 27+35 here (decision D66 recorded "about 1 KB"). Every class in this chapter looks it
  up once, into a `static final` field.

The JDK documents the value as "an approximation", because a JVM may record an allocation's size
some time after it happens. In HotSpot the TLAB term makes it exact for the current thread; for
another thread it is read without synchronization, so it can lag by an allocation in progress.
That is precise enough to tell "zero" from "not zero", which is the only question the engine asks.

### 3.3 What the collector does

Most objects die young: the string built for one log line, the iterator of one loop. A
**generational** collector exploits that: it collects the young region often and cheaply, and the
old region rarely. Jeddy's launchers select **ZGC** (`-XX:+UseZGC`, §18.1), which in JDK 27 is
always generational. ZGC does nearly all its work on its own threads while the program runs
(concurrently). It stops the program only for short **pauses** — "Mark Start", "Mark End" and
"Relocate Start" — in which every thread must stop at a **safepoint** (§4.5). A young collection has
all three; in a major collection the old generation shares the young one's "Mark Start" and adds its
own "Mark End" and "Relocate Start", five pauses in all. The capture of §25.1 logged one major
collection with `-Xlog:gc*`:

```
[2.792s][info][gc          ] GC(0) Major Collection (Warmup)
[2.793s][info][gc,phases   ] GC(0) Y: Pause Mark Start (Major) 0.017ms
[2.859s][info][gc,phases   ] GC(0) Y: Pause Mark End 0.015ms
[2.876s][info][gc,phases   ] GC(0) Y: Pause Relocate Start 0.015ms
[2.907s][info][gc,phases   ] GC(0) O: Pause Mark End 0.013ms
[2.931s][info][gc,phases   ] GC(0) O: Pause Relocate Start 0.011ms
[2.934s][info][gc          ] GC(0) Major Collection (Warmup) 138M(9%)->138M(9%) 0.142s
```

Pauses of 11 to 17 microseconds, well under the DESIGN §14 budget of 1 ms; and that one cycle, a
"warmup" collection while the level loaded, was the only one in the 153-second run. With zero
allocation per frame, the collector simply never runs during play. Two costs remain even when it
does run concurrently: its threads take cores from the job workers, and if the game allocates faster
than a cycle can reclaim, threads that allocate are stalled until memory is free. G1, the JDK's
default collector, is the supported alternative (DESIGN §4.6); its pauses are longer, which zero
allocation makes irrelevant too.

### 3.4 What allocates without `new`

"Zero allocation per frame" means no object is created on the game thread, the main thread or the
job workers in steady state (DESIGN §4.2). Many Java constructs allocate without a visible `new`.
The table lists them with the engine's alternative and an example in the code; the bytes come from
§3.1's measurements.

| Construct | What it allocates | Jeddy's alternative | Example in the code |
|---|---|---|---|
| Boxing (`Map<Integer, Integer>`, `List<Float>`) | an `Integer` (16 B) per value outside −128 … 127 | primitive collections | [`IntIntMap`](../../modules/core/src/main/java/dev/jeddy/core/collections/IntIntMap.java), `IntList`, `LongIntMap` |
| `for (X x : list)` over a collection | an `Iterator` | index loops; for-each over an **array** is fine (it compiles to an index loop) | `IntIntMap.firstSlot()` / `nextSlot(s)` |
| A capturing lambda or bound method reference (`this::f`) | an object (16 B and up) each time the expression is evaluated | evaluate once into a `final` field | `private final JobKernel kernel = this::updateRange;` (§19.2) |
| Varargs (`f(int... a)`) | an array per call (24 B for three `int`s) | fixed-arity overloads; varargs only in cold code | `MainThreadQueue.post(opcode, a)` and its five-argument overload; `JobGraph.add(kernel, count, grain, int... dependsOn)` is varargs because it runs once when a graph is built, and `JobGraph.run(jobs)`, called every frame, takes none |
| String concatenation, `String.format` | a `String` and its bytes (48 B for `"x" + 7`) | numbers in records; messages built in cold methods | `SdlThread.wrongThread` (§19.5) |
| Streams, `Optional` | pipeline objects | loops | |
| Exceptions for control flow | the exception and its stack trace | return codes | `SpscIntRing.offer` returns `false` when full |
| Native calls that return pointers | a 32-byte `MemorySegment` per call unless escape analysis removes it | `long` returns (`GLsync`), `JAVA_LONG` descriptors | D24, `FfmBench.MEMCPY_DESC` (§8) |
| `MemorySegment.ofArray`, `asSlice`, `SegmentAllocator.allocate` | a wrapper (72 B, 32 B) | wrappers created once; offsets into one segment | `FrameAllocator.allocateOffset` (§10.3), `EventPump.RAW` (§11) |
| Lazy class initialization, handle linking | classes, method handles (134 KB for one downcall's first call, §5.2) | link before the first frame | `Audio.prelink` (D65, §20.3) |
| Arrays that grow mid-game | a new, larger array | reserve the peak up front | `RenderPacket.reserve` (§19.6) |
| Logging | a varargs array, boxed arguments, the formatted message | log in cold paths; guard with `isEnabled` | [`Logger`](../../modules/core/src/main/java/dev/jeddy/core/log/Logger.java) |
| A record or object per element | one object per item | struct-of-arrays columns (L03 §4); scratch fields | `World.point`, `MVec3f` (§19.1) |

The `Logger` Javadoc spells out its own rule: "Logging is for cold paths: arguments are boxed and
the message is formatted on the calling thread. In code that runs every frame, guard with
`isEnabled(LogLevel)`." Exercise 9 asks you to make a disabled debug log free.

---

## 4. Calling C: the Foreign Function & Memory API in depth

L00 §12 introduced the **Foreign Function & Memory API** (FFM, package `java.lang.foreign`, final
since JDK 22, [JEP 454](https://openjdk.org/jeps/454)): describe a C function's signature in Java,
ask the platform's linker for a `MethodHandle`, call it. No C glue code is written or compiled,
which is why Jeddy has no JNI code at all (DESIGN §1, goal 3). This section takes the API apart:
what a C call is at the machine level, what each piece of the API describes, and what the JVM does
when you call through it.

### 4.1 What a C call is: the calling convention

A compiled C function is machine code that expects its arguments in particular places and leaves
its result in a particular place. The rules for those places are the platform's **calling
convention**, part of its **ABI** (application binary interface). Jeddy targets two:

| | Linux x64 (System V AMD64 ABI) | Windows x64 (Microsoft x64) |
|---|---|---|
| Integer and pointer arguments | `rdi`, `rsi`, `rdx`, `rcx`, `r8`, `r9`, then the stack | the first four parameters by *position* go to `rcx`, `rdx`, `r8`, `r9` |
| Floating-point arguments | `xmm0` … `xmm7`, then the stack | the first four parameters by position go to `xmm0` … `xmm3` |
| Further arguments | the stack | the stack, above 32 bytes of "shadow space" the caller reserves |
| Return value | `rax` (integers, pointers) or `xmm0` (floats) | the same |
| Size of C `long` | 64 bits (the "LP64" model) | 32 bits (the "LLP64" model) |

Two worked examples. `int abs(int)` on Linux takes its argument in `edi` (the low 32 bits of `rdi`)
and returns in `eax`. `void glUniform4f(GLint location, GLfloat v0, GLfloat v1, GLfloat v2, GLfloat
v3)`: on Linux `location` goes to `edi` and the four floats to `xmm0`–`xmm3`, counted separately
from the integers; on Windows parameters are counted by position, so `location` (position 1) goes to
`ecx`, `v0`, `v1`, `v2` (positions 2–4) to `xmm1`–`xmm3`, and `v3` (position 5) onto the stack. Same
C function, different machine code to call it.

The JIT-compiled Java code has its own register conventions, which match neither. So every call
from Java into C needs a small piece of machine code that moves the arguments from where Java has
them to where C wants them, makes the call and moves the result back. The **linker**
(`Linker.nativeLinker()`, one per platform) knows the platform's convention and generates that code.

### 4.2 Describing C types: layouts and descriptors

A **`ValueLayout`** describes one C value: its Java carrier type, its size, its **alignment** (the
power of two its address must be a multiple of, L00 §8.4) and its byte order:

| Layout | Carrier | Size, alignment | C types it describes |
|---|---|---|---|
| `JAVA_BOOLEAN` | `boolean` | 1, 1 | C99 `bool` |
| `JAVA_BYTE` | `byte` | 1, 1 | `char`, `int8_t`, `uint8_t` |
| `JAVA_SHORT` | `short` | 2, 2 | `int16_t`, `uint16_t` |
| `JAVA_INT` | `int` | 4, 4 | `int`, `int32_t`, `uint32_t`, `GLenum`, `GLuint` |
| `JAVA_LONG` | `long` | 8, 8 | `long long`, `int64_t`, `uint64_t`, `size_t` (x64) |
| `JAVA_FLOAT`, `JAVA_DOUBLE` | `float`, `double` | 4, 4; 8, 8 | `float`, `double` |
| `ADDRESS` | `MemorySegment` | 8, 8 on x64 | any pointer |
| `JAVA_INT_UNALIGNED` and friends | as above | alignment 1 | the same values at any offset |

All of them use the platform's byte order, which on both of Jeddy's targets is little-endian (L00
§8.1); `ValueLayout.JAVA_INT.order()` prints `LITTLE_ENDIAN`. Java has no unsigned integers, so an
unsigned C type is carried in the signed Java type of the same size, and code reads it with a mask
(L00 §5.4).

**C structs** are described by a `StructLayout`, a list of member layouts with explicit padding.
SDL's text input event is a good example (from the jextract output
[`SDL_TextInputEvent.java`](../../modules/sdl/src/ffi/java/dev/jeddy/sdl/ffi/SDL_TextInputEvent.java)):

```java
    private static final GroupLayout $LAYOUT = MemoryLayout.structLayout(
        SDL3.C_INT.withName("type"),
        SDL3.C_INT.withName("reserved"),
        SDL3.C_LONG_LONG.withName("timestamp"),
        SDL3.C_INT.withName("windowID"),
        MemoryLayout.paddingLayout(4),
        SDL3.C_POINTER.withName("text")
    ).withName("SDL_TextInputEvent");
```

Work out the offsets with the alignment rule — each member starts at the next offset that is a
multiple of its alignment:

| Member | Size | Alignment | Next free offset | Starts at |
|---|---|---|---|---|
| `type` | 4 | 4 | 0 | 0 |
| `reserved` | 4 | 4 | 4 | 4 |
| `timestamp` | 8 | 8 | 8 | 8 (already a multiple of 8) |
| `windowID` | 4 | 4 | 16 | 16 |
| `text` | 8 | 8 | 20 | **24**: 20 is not a multiple of 8, so 4 bytes of padding come first |

The struct is 32 bytes, aligned to 8. A C compiler inserts the padding silently; FFM insists that
it be written down, which is why jextract emits `paddingLayout(4)`. Leave it out and the layout
cannot even be built: `MemoryLayout.structLayout(JAVA_INT, JAVA_LONG)` throws
`IllegalArgumentException: Invalid alignment constraint for member layout` (checked for this
chapter). The jextract classes also give every field's offset as a constant,
`SDL_TextInputEvent.text$offset()` = 24, which §11 uses.

**A `FunctionDescriptor`** is a C signature written as layouts: `FunctionDescriptor.of(JAVA_INT,
JAVA_INT)` is `int f(int)` (the return layout comes first), `FunctionDescriptor.ofVoid(JAVA_INT,
JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT)` is `glUniform4f`.

**The `long` trap.** C's `long` is 64 bits on Linux and 32 on Windows (§4.1). jextract run on
Linux maps `int64_t`, `uint64_t` and `size_t` to its `C_LONG` layout, which on Windows would be the
wrong size; the generated initializer even throws there. Jeddy generates its bindings once, on
Linux, and makes them portable with a post-processor that rewrites `C_LONG` to `C_LONG_LONG` and
fails the build when a bound declaration uses a type whose size differs between the two ABIs
(`long`, `wchar_t`, `va_list`, `long double`), decision **D22** (§20.1).

### 4.3 Finding functions: symbol lookups

A downcall needs the function's **address**, the location of its first instruction in memory.
A `SymbolLookup` finds addresses by name:

- `Linker.nativeLinker().defaultLookup()` finds the functions of the C standard library: `abs`,
  `memcpy`, `strlen`. `FfmBench` uses it so it runs on any machine (§8).
- `SymbolLookup.loaderLookup()` finds the functions of every library that classes of the same class
  loader loaded with `System.load` or `System.loadLibrary`. jextract's generated code uses it (its
  `SYMBOL_LOOKUP`, §20.1), which is why `NativeLoader` must load SDL3, SDL3_mixer and zstd before
  any binding is used (§9).
- `SymbolLookup.libraryLookup(path, arena)` opens a library by itself. Jeddy does not use it: loading
  through `NativeLoader` lets the engine verify each file's hash first and control the load order.
- OpenGL is different again: its functions come from the driver at run time through
  `SDL_GL_GetProcAddress`, so the GL bindings have their own resolver ([chapter 05, on why a loader
  is needed](05-opengl-from-java.md#51-why-a-loader-is-needed)).

`findOrThrow(name)` returns the address as a `MemorySegment` of size 0 whose `address()` is the
function's location.

### 4.4 Downcall handles: what the linker builds

`Linker.downcallHandle(address, descriptor, options...)` returns a `MethodHandle` whose type comes
from the descriptor: `(int)int` for `abs`. Behind it, the linker builds two things once per distinct
signature and option set:

- a **downcall stub**, machine code in the JVM's **code cache** (the region of memory, outside the
  heap, where the JVM keeps all the machine code it generates: compiled methods, interpreter
  templates and stubs like this one) that moves the arguments into the ABI's places (§4.1), makes
  the call and handles the thread state (below);
- a **LambdaForm** (§5.1), a small Java-level program that prepares the arguments: for each
  `ADDRESS` argument it checks that the segment is a native segment (or a heap segment the options
  allow, §4.5), takes its address, and keeps the segment's arena alive until the call returns, so
  that a shared arena cannot be closed under a running call (closing it then throws
  `IllegalStateException`, §4.8). In the allocation stacks of §25.2 this check appears by name, as
  `jdk.internal.foreign.abi.SharedUtils.checkNative`.

The call itself is `handle.invokeExact(args...)`. `invokeExact` is **signature-polymorphic**:
although it is declared as `Object invokeExact(Object... args)`, `javac` compiles each call site
with the exact static types written there — `(int) ABS.invokeExact(value)` becomes a call of type
`(int)int` — so there is no varargs array and no boxing. The price is strictness: the types must
match the handle's type exactly, cast on the result included. `long x = (long) ABS.invokeExact(-5)`
compiles, and fails at run time with `WrongMethodTypeException: handle's method type (int)int but
found (int)long` (checked for this chapter).

**What a regular downcall does inside the JVM.** Java threads cooperate with the garbage collector
at safepoints (§4.5). C code cannot be stopped at a safepoint, so the stub of a regular downcall
does this around the C call:

1. records where the thread's Java stack ends (the "last Java frame"), so the collector can find the
   thread's references while it is in C;
2. switches the thread's state from *in Java* to *in native*: from now on the JVM does not wait for
   this thread at a safepoint;
3. moves the arguments into place and calls the C function;
4. switches to *in native, returning*, makes sure that switch is visible to other threads (a memory
   fence), and checks whether a safepoint or a per-thread operation is in progress; if one is, the
   thread waits here until it ends;
5. switches back to *in Java*.

For a C function that runs for nanoseconds these steps are most of the cost. Measured with a
hand-timed loop for milestone M3-05 (JDK 27, Linux, recorded in
[bench/README.md](../../bench/README.md)): 16.5 ns per call of `abs` through a regular downcall,
3.6 ns with the transitions skipped (§4.5). The committed JMH baseline of a GitHub runner
([bench/baseline/linux-x64.json](../../bench/baseline/linux-x64.json)) records 3.6 ns and 2.7 ns
for the same two calls: the size of the gap depends on the machine, its direction does not.

**Why the `static final` field matters here** (§2.5). If the handle is a constant, C2 inlines
through `invokeExact`, the handle's LambdaForm and the argument checks down to a direct call of the
stub, with the checks folded wherever their outcome is known at compile time. If the handle is not a
constant, every call goes through the generic method-handle path of §5, which is slower and, until
the launcher flag of §5.2, allocated.

### 4.5 Safepoints, and what a critical downcall skips

A **safepoint** is a point in a thread's execution where the JVM knows exactly where every object
reference is (in which registers and stack slots), so the collector can scan and move objects
safely. The interpreter reaches one between any two bytecodes; compiled code checks for a pending
safepoint at method returns and on loop back-edges (a **safepoint poll**, one load and a branch). A
**global safepoint** stops every Java thread: ZGC's pauses (§3.3), installing a deoptimization that
affects many methods, redefining classes. A **handshake** stops one thread at a time. Both wait only
for threads *in Java*: a thread *in native* (step 2 above) is already safe, because C code cannot
touch Java objects. That is why a regular downcall may block for a second without holding up the
collector.

`Linker.Option.critical(boolean allowHeapAccess)` marks a function as **critical**. The JDK's
Javadoc defines it: "A critical function is a function that has an extremely short running time in
all cases (similar to calling an empty function), and does not call back into Java (e.g. using an
upcall stub)." The linker may then skip steps 1, 2, 4 and 5: the thread stays *in Java* for the
whole C call. Three consequences follow, and the Javadoc warns that using the option for a
non-critical function "is likely to have adverse effects, such as loss of performance or JVM
crashes":

- **No safepoint can complete while a critical call runs.** Every thread that reaches the safepoint
  waits for the critical call to return. A ZGC pause of 15 µs (§3.3) waits behind a critical call
  that blocks for 5 ms — inside the driver on a lock, or on a buffer the GPU still reads — and
  becomes a 5 ms pause for *every* thread. The time threads spend reaching a safepoint is called
  **time to safepoint**; a critical call adds its whole duration to it.
- **A critical function must never call back into Java.** An upcall stub (§4.6) expects to find
  the thread *in native* and switch it to *in Java*. Inside a critical call the thread is already
  in Java, and the JVM's bookkeeping breaks; the JVM can crash. This is why the GL debug callback
  and critical linking cannot coexist (§21.3).
- **With `allowHeapAccess = true`, heap memory can be passed.** Because no safepoint can happen
  during the call, the collector cannot move a Java array while C reads it, so the linker can pass
  the array's own address: `MemorySegment.ofArray(float[])` becomes a valid argument. A regular
  downcall, or a critical one with `false`, rejects heap segments: `IllegalArgumentException: Heap
  segment not allowed: MemorySegment{ kind: heap, ... }` (checked for this chapter with `memcpy`).
  The M3-05 measurement priced the alternative: a 4×4 matrix (`glUniformMatrix4fv`, 64 bytes) from a
  heap array through a critical call cost 27.7 ns, against 36.1 ns for copying it into native memory
  first and calling regularly.

Jeddy applies `critical(true)` only to an allow-list of GL state setters, binds and uniform setters
(DESIGN §4.4, `bindings/gl/gl.properties`); §21 shows the list, the numbers and the switch that
turns critical linking off in debug runs (decision **D12**).

### 4.6 Upcalls: C calling Java

The mirror image of a downcall is an **upcall**: C code calling a Java method through a C function
pointer. `Linker.upcallStub(target, descriptor, arena)` takes a `MethodHandle` to a static Java
method and returns a `MemorySegment` whose address is a newly generated native function with the C
signature of `descriptor`. When C calls that address, the stub:

1. if the calling operating-system thread is unknown to the JVM, **attaches** it, creating a Java
   `Thread` object for it (an allocation, on a thread the engine does not control);
2. switches the thread from *in native* to *in Java* — and if a safepoint is in progress, **waits
   for it to end** before running any Java code;
3. calls the target method;
4. switches back to *in native* and returns to C.

An exception cannot travel through C frames. If the target method throws, the JVM terminates the
process, so every upcall target catches everything.

Jeddy has exactly one upcall: the OpenGL debug callback, which the driver calls from inside a GL
call on the main thread, created once in `Arena.global()` because the driver may call it until the
process ends. [Chapter 05, on `GLDebug`](05-opengl-from-java.md#16-step-8-gldebug), builds it.

**No Java on the audio thread.** SDL mixes audio on its own real-time thread and wants each buffer
within a few milliseconds. If that thread ran Java through an upcall, step 2 could make it wait for
a GC pause or a deoptimization, and a late buffer is an audible click; its first call would also
attach the thread. So the engine mixes in native code (SDL3_mixer, decision **D7**) and installs no
Java callback that SDL could call from its own threads — no log callback (**D12**), no audio stream
callbacks, no event watchers (DESIGN §4.4 and §6.4). [Chapter 01, on why there is no Java on the
audio thread](01-architecture.md#34-why-no-java-on-the-audio-thread), tells the same story from the
thread model's side.

### 4.7 Memory segments: what every access checks

A **`MemorySegment`** is a view of a range of memory: an address, a size, and a **scope** (the
lifetime of the arena it came from). Native segments point outside the Java heap; **heap segments**
(`MemorySegment.ofArray`) view a Java array. You read and write with a value layout and a byte
offset: `s.get(ValueLayout.JAVA_INT, 8)`, `s.set(ValueLayout.JAVA_FLOAT, 4, 1.5f)`.

Every access is checked four ways. Each check was triggered for this chapter, and here is what it
says:

| Check | Fails with |
|---|---|
| **Spatial**: offset and size inside the segment | `IndexOutOfBoundsException: Out of bound access on segment ... byteSize: 0 }` (a zero-length segment, §4.9) |
| **Temporal**: the segment's arena still open | `IllegalStateException: Already closed` |
| **Confinement**: a confined segment used only by its owner thread | `WrongThreadException: Attempted access outside owning thread` |
| **Alignment**: an aligned layout at an aligned address | `IllegalArgumentException: Target offset 2 is incompatible with alignment constraint 4 (of i4) ...` |

The checks cost a comparison or two, and C2 hoists them out of loops or removes them when the
segment and the offsets are known. Layouts and offsets therefore live in `static final` fields
(DESIGN §4.3): constants the JIT can fold. The alignment check is why
[`DrawBuilder`](../../modules/render/src/main/java/dev/jeddy/render/draw/DrawBuilder.java), which
writes 64-byte instance records into GPU memory, uses unaligned layouts:

```java
    private static final ValueLayout.OfFloat F32 =
            ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.nativeOrder());
    private static final ValueLayout.OfInt I32 =
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.nativeOrder());
```

Its offsets are always multiples of 4, but the unaligned layout states that the code does not rely
on it, and x64 reads unaligned values at full speed. `withOrder(ByteOrder.nativeOrder())` changes
nothing (the `JAVA_*` layouts are already in native order); it states that the GPU reads these
bytes in the CPU's order.

### 4.8 Arenas decide lifetime

Native memory is allocated from an **`Arena`**, and the arena decides when it is freed. Memory
from every arena is zeroed when it is allocated.

| Arena | Freed | Usable from | Jeddy uses it for |
|---|---|---|---|
| `Arena.global()` | never | any thread | C strings made once (`CStrings`, §10.4), jextract's constants, the GL debug upcall stub |
| `Arena.ofShared()` | at `close()`, from any thread | any thread | long-lived memory a subsystem owns: pak mappings, the asset staging ring, zstd contexts, audio data, texture levels being loaded, `OffHeap` blocks (§10.1) |
| `Arena.ofConfined()` | at `close()`, by its owner thread | its owner thread only | per-object scratch on the main thread (`SceneRenderer`, `DebugRenderer`, `GpuDevice`, `EventPump`); short-lived temporaries in try-with-resources on cold paths (`GLLoader.readCapabilities`, `GLDebug.label`) |
| `Arena.ofAuto()` | when the collector finds the arena unreachable | any thread | **never** in engine code (CLAUDE.md, rule 3) |

**Why never `ofAuto`?** Its memory is released by the garbage collector, at a time nobody controls,
and only if the collector runs at all. In an engine that allocates nothing per frame the collector
may not run for an hour (§3.3), so "automatic" memory is effectively leaked. And memory handed to
the GPU or a C library must stay valid exactly as long as they use it, which only code can know.
jextract's output contains an `Arena.ofAuto()`, which the post-processor rewrites (§20.1).

**Why `ofConfined` only on cold paths?** Each `allocate` from an arena is a call to the C
allocator (`malloc`) plus zeroing, and `close` frees it all. Opening and closing an arena per frame
would mean `malloc` and `free` per frame (DESIGN §4.3). Per-frame scratch therefore lives in memory
allocated once and reused: a field holding a segment allocated in the constructor, or a bump
allocator over one block (§10.3).

**Closing has rules.** Closing an arena twice throws `IllegalStateException: Already closed`. The
`Arena.close` Javadoc calls the exception a "deliberate design choice": the operation is not
idempotent because a "failure to close an arena might reveal a bug in the underlying application
logic" — the second close is reported, not ignored, so that the program learns something is wrong
with its ownership. A wrapper that may be closed from two places therefore keeps its own flag
(§10.1). Closing a shared arena while a downcall is using one of
its segments throws too (§4.4). And closing a shared arena must make sure that no other thread is in
the middle of reading its memory, which the JVM does with a handshake with the other threads (§4.5):
cheap enough at shutdown or level unload, not something to do per frame.

### 4.9 Zero-length segments, `reinterpret` and restricted methods

When C returns a pointer, the JVM knows the address but not how many bytes are behind it. FFM
represents such a pointer as a segment of size 0, on which every access fails the spatial check
(§4.7). To read through it, the code declares the size with **`reinterpret(size)`**, which returns a
segment with the same address and the given size. Nothing checks that the size is true, so
`reinterpret` can produce a segment over memory the program does not own, and reading or writing
it can crash the JVM. Methods like that are **restricted**. Every `reinterpret` in the engine's
own code (ten, in eight files; jextract's generated accessors are the other route, below) declares a
size it knows from the C API:

- [`GpuBuffers`](../../modules/render/src/main/java/dev/jeddy/render/gpu/GpuBuffers.java) maps a
  persistent GL buffer and declares the mapping's size,
  `glMapNamedBufferRange(...).reinterpret(size)` ([Lesson 1](lessons/01-buffers-in-flight.md) §7.3
  builds it);
- [`GLLoader.string`](../../modules/gl/src/main/java/dev/jeddy/gl/GLLoader.java) and
  [`CStrings.read`](../../modules/core/src/main/java/dev/jeddy/core/memory/CStrings.java) declare a
  C string as `Long.MAX_VALUE` bytes long and read up to its terminating zero byte (`getString`
  stops there; the size only has to be large enough);
- [`GLDebug`](../../modules/gl/src/main/java/dev/jeddy/gl/GLDebug.java)'s callback declares the
  driver's message as `length + 1` bytes when the driver gave a length (the extra byte is the
  terminating zero the KHR_debug specification guarantees), and `Long.MAX_VALUE` when it did not;
- [`window.Platform`](../../modules/window/src/main/java/dev/jeddy/window/Platform.java) declares
  the array `SDL_GetFullscreenDisplayModes` returns as `n` pointers (`n` comes back through the
  function's count parameter), then each `SDL_DisplayMode` it points to as the struct's layout size;
  [`Window`](../../modules/window/src/main/java/dev/jeddy/window/Window.java) does the same for
  `SDL_GetDesktopDisplayMode`'s result;
- [`Sdl.copyIdsAndFree`](../../modules/sdl/src/main/java/dev/jeddy/sdl/Sdl.java) declares an array
  SDL returns as `count` `int`s, copies them out and frees it (the display ID list of `SDL_GetDisplays`);
- [`EventPump`](../../modules/input/src/main/java/dev/jeddy/input/EventPump.java) and
  [`GpuTextures`](../../modules/render/src/main/java/dev/jeddy/render/gpu/GpuTextures.java) each
  make one segment over all of memory, `MemorySegment.NULL.reinterpret(Long.MAX_VALUE)`, once, in a
  `static final` field: the pump reads text SDL owns through it without a segment per event (§11),
  and the texture code copies pixels out of raw client memory through it without a segment per
  upload. The bounds check is then the whole address space — these two trade the spatial check for
  zero allocation, and the comments on both fields say so.

jextract takes another route to the same end: its pointer layout is
`ADDRESS.withTargetLayout(MemoryLayout.sequenceLayout(Long.MAX_VALUE, JAVA_BYTE))`, an address
layout that says what it points to, so pointers it returns arrive already sized. `withTargetLayout`
is restricted for the same reason.

The **restricted methods** an engine meets are `Linker.downcallHandle`, `Linker.upcallStub`,
`MemorySegment.reinterpret`, `AddressLayout.withTargetLayout`, `SymbolLookup.libraryLookup`, and
`System.load` and `System.loadLibrary` (restricted since JDK 24). The JDK lets a module call them
only when native access is enabled for it, on the command line with
`--enable-native-access=<module names>` (or `ALL-UNNAMED` for code on the class path). Without that,
JDK 27 still allows the call but prints, on first use:

```
WARNING: A restricted method in java.lang.foreign.Linker has been called
WARNING: java.lang.foreign.Linker::downcallHandle has been called by Heap in an unnamed module (...)
WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module
WARNING: Restricted methods will be blocked in a future release unless native access is enabled
```

Jeddy passes the flag for exactly the modules that use FFM (§18.1) and switches off javac's
`restricted` warning only in those modules (§9.4).

### 4.10 What a downcall allocates

Put together, the rules for a downcall that allocates nothing per call are:

- **Primitive arguments and results allocate nothing.** `memcpy` linked with a `JAVA_LONG` return
  measured 0 bytes per call from the first measured round onwards (20,000 calls per round, after
  20,000 warm-up calls).
- **A pointer result is a new `MemorySegment` per call.** The same `memcpy` linked with an `ADDRESS`
  return measured 32 bytes per call until C2 compiled the caller and 0 afterwards when the caller
  only took `.address()` of the result (C2's escape analysis removed the 32-byte segment, §2.6), and
  32 bytes per call in every round when the caller kept the result in a field. The first measured
  round shows a fraction of 32 — 20.8 in the run quoted in exercise 4, between 14.8 and 32 in five
  reruns — because the compile lands on a background thread partway through it, and the round's
  number is 32 bytes times the share of its calls made before the compiled code was installed; one
  rerun, with the source launcher, stayed at 32 for eight rounds before dropping to 0. Declaring the
  return as `JAVA_LONG` gets the raw address as a number and never creates the object; that is why
  `FfmBench` declares `memcpy` that way (§8), and why the GL generator maps `GLsync` to `long` and
  emits `long` overloads for pointer parameters that carry offsets (decisions **D24**, **D29**;
  [chapter 05, on pointers that are really
  numbers](05-opengl-from-java.md#54-pointers-that-are-really-numbers)).
- **Native segment arguments allocate nothing** when the segment already exists. A heap segment
  argument needs a wrapper, `MemorySegment.ofArray`, which allocated 72 bytes per call in §3.1's
  measurement; DESIGN §4.4 therefore says to create such wrappers once and keep them.
- **The first call is expensive.** Linking happens in the holder's class initializer (§2.5), and the
  first call of a downcall handle links its invoker. A program run for this chapter measured 134,336
  bytes allocated by the first `invokeExact` of a freshly linked `abs` handle (§5.2). That must
  happen before the first frame, not in it (§20.3).
- **The 128th call can be expensive too**, unless customization is off. That is the next section.

---

## 5. Method handles inside the JVM: customization

### 5.1 How a method-handle call runs

A `MethodHandle` is an object, and calling it is not a plain Java call. Each handle has a
**LambdaForm**: a tiny program, in an intermediate form the JDK understands, that says what the
handle does (convert these arguments, call that target, convert the result). The JDK has an
interpreter for LambdaForms, but by default it is not used: `MethodHandleStatics.java` line 78 reads
`java.lang.invoke.MethodHandle.COMPILE_THRESHOLD` with the default `"0"`, and with a threshold of 0
`LambdaForm.prepare()` (`LambdaForm.java` line 765) compiles the form to bytecode *before its first
invocation*. A form is interpreted only when it is marked for interpretation (`forceInterpretation`,
line 883, which `java.lang.invoke` uses while it bootstraps itself) or when a run sets a non-zero
threshold: then `interpretWithArguments` (line 923) counts invocations and compiles the form when
the count reaches the threshold. The bytecode goes into **hidden classes** the JVM defines at run
time; their names look like `java.lang.invoke.LambdaForm$MH/0x000000005c049c00`. One LambdaForm is
shared by every handle of the same shape.

An `invokeExact` call site is linked, on its first execution, to an **invoker**: a LambdaForm that
checks the handle's type and then runs the handle's own LambdaForm. Two cases follow:

- **The handle is a constant to the compiler** (a `static final` field, §2.5) and the call site is
  compiled by C2. C2 inlines through the invoker and the handle's LambdaForm into the target. This
  is the fast path the engine is built for.
- **The handle is not a constant**: the call site runs in the interpreter or in C1, or the handle
  sits in a field C2 does not trust. Every call then runs the *shared* LambdaForm code, which cannot
  know anything about this particular handle.

For the second case the JDK has an optimization called **customization**: after a handle has been
called a number of times this way, the JDK generates a LambdaForm specialized to that one handle,
with the handle's own fields baked in as constants, and compiles it into a new hidden class.
Reading the JDK 27 bytecode (`javap -c -p` on `java.lang.invoke.Invokers` and `MethodHandle`) shows
the mechanism:

- the invoker calls `Invokers.checkCustomized(mh)`, which returns at once if
  `MethodHandleImpl.isCompileConstant(mh)` is true. That method is a compiler **intrinsic**: a
  method the JIT recognizes by name and replaces with code of its own instead of compiling its
  body (`Math.sqrt` becoming one instruction is the classic case). Here C2 folds it to `true` for a
  constant handle, so the check vanishes from compiled code. The interpreter runs the real body,
  which returns `false`;
- otherwise it calls `mh.maybeCustomize()`, which keeps a `byte` counter in the handle: below the
  threshold it adds one; at the threshold it calls `customize()`;
- the threshold is `MethodHandleStatics.CUSTOMIZE_THRESHOLD`, read from the system property
  `java.lang.invoke.MethodHandle.CUSTOMIZE_THRESHOLD`, default **127**, valid range −1 to 127;
- when the threshold is negative, the invoker LambdaForm is built without the check at all, so
  **−1 turns customization off**.

### 5.2 What customization costs, measured

A single-file program shows it. It links `abs` once into a field that is *not* final, so the handle
is never a compile-time constant, and reads the thread's allocation counter around each call (§3.2):

```java
import java.lang.foreign.*;
import java.lang.invoke.*;

public class Cust {
    static final com.sun.management.ThreadMXBean T =
            (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
    static MethodHandle abs; // not final: never a compile-time constant

    public static void main(String[] args) throws Throwable {
        Linker linker = Linker.nativeLinker();
        abs = linker.downcallHandle(linker.defaultLookup().findOrThrow("abs"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        int sink = 0;
        for (int i = 1; i <= 140; i++) {
            long before = T.getCurrentThreadAllocatedBytes();
            sink += (int) abs.invokeExact(-i);
            long bytes = T.getCurrentThreadAllocatedBytes() - before;
            if (bytes != 0) {
                System.out.println("call " + i + ": " + bytes + " B");
            }
        }
        System.out.println(sink);
    }
}
```

Compiled with `javac Cust.java` and run with `java --enable-native-access=ALL-UNNAMED Cust` (JDK
27+35, the 2-core container; `-Xint` gives the same output):

```
call 1: 134336 B
call 128: 63432 B
9870
```

The *calls* are what matter here, not the last digits of the bytes. The byte counts move a little
with anything that ran before: running the source file directly (`java ... Cust.java`, which
compiles it in-process first) printed 132,656 and 63,432 in this container and 132,648 and 63,512 on
another machine, and in a few runs of the compiled form (of this program and of `CustFinal` below)
the first call printed 134,392 instead. What the first call allocates depends on which classes and
strings earlier code already created. Calls 1 and 128 never move.

and with `-Djava.lang.invoke.MethodHandle.CUSTOMIZE_THRESHOLD=-1` added:

```
call 1: 132464 B
9870
```

The first call links the invoker (§4.10). The 128th call customizes: the counter went from 0 to 127
over the first 127 calls, and the 128th found it at the threshold. With `-Xlog:class+load=info`, two
new `java.lang.invoke.LambdaForm$MH/...` hidden classes appear between the lines `call 1` and
`call 128`. (Decision D69 says "its 127th call"; counted exactly, it is the call after the 127th.)

**A `static final` handle is not exempt.** The same program with the handle in a `static final`
field, called through a small static method, prints the same `call 128: 63432 B` under `-Xint`,
and also without it: in the first 128 calls the method holding the call site is still interpreted,
and the interpreter does not treat the handle as a constant. The constant only helps once C2 has
compiled the call site.

**Why it hit the game.** A downcall that runs a few times per second — setting a sound's 3D
position — sits in a method that stays in the interpreter or C1 for minutes, because it is called
too rarely to reach C2 (§2.2). In the M10 perf capture those handles crossed the threshold minutes
into the run and spun hidden classes in the middle of frames: 40 to 150 KB of garbage (fix F3 in
[docs/perf/m10.md](../perf/m10.md)). The fix is one launcher flag, decision **D69**:

```
-Djava.lang.invoke.MethodHandle.CUSTOMIZE_THRESHOLD=-1
```

It is safe because Jeddy's downcall handles are all `static final`: once their callers are hot, C2
inlines them as constants whether or not they were customized, and until then the uncustomized
path is merely a little slower for a call that runs a few times a second. And a JDK that one day
drops the property ignores an unknown system property, so the flag can never break a launch. The
perf capture checks that the flag is set (§17.4); a run that silently lost it is the story of fix
F11 (§25.3).

The `static final` variant of this program, run for 200,000 calls with the JIT on, prints more
lines — in the run quoted here, two:

```
call 1: 134336 B
call 128: 63432 B
call 5631: 2728 B
call 7680: 1096 B
```

Unlike calls 1 and 128, these move between runs: in six runs here the 2,728-byte line came at call
5,631 or 7,679, the 1,096-byte line at 7,680, 9,728, 11,725 or even 77,780, and other runs printed a
third or fourth line of 800 to 904 bytes. Each is a compile request, and when a request comes
depends on when the method's counters cross a threshold and on what the compiler threads were doing
(§2.2). These late allocations come from the JIT itself, and they are the subject of decision D70
(§25.2), which reruns this program with every allocation recorded.

---

## 6. Measuring without fooling yourself

### 6.1 Five questions, five tools

"Zero allocation per frame" and "fast" are claims, and CLAUDE.md rule 6 asks for each claim to be
proved. Different claims need different instruments, and using the wrong one gives a confident
wrong answer. The engine uses five:

| Question | Tool | What it measures | Built in |
|---|---|---|---|
| Does this method allocate once the JIT has compiled it? | `Allocations.assertNoAllocation` | the calling thread's exact byte counter, in rounds until compiled | §12 |
| Does the whole two-thread loop allocate in steady state? | the soak test `JeddyAppTest.bothThreadsAllocateNothingPerFrame` | the game and main threads' counters, sampled by a third thread | §24.6 |
| How fast is it, and does it allocate per operation? | JMH with `-prof gc` | time per operation; bytes per operation summed over all threads | §16 |
| Does the real game hold its budgets, and if not, where? | the perf capture (`--perf`, `--alloc-probe`) | frame times, bytes per thread and per system, GC, the JIT's compiles | §17 |
| Which line of code allocated? | JFR allocation events, the class-load log | stack traces of allocations; classes loaded late | §6.4, §25 |

The first is exact but sees only one thread and one piece of code. The last sees everything but
needs the whole game. They are used together: the perf capture says *that* frame 5,741 allocated
and *which system* did it, the JIT log says *why*, and a unit test with the assertion keeps the fix
from regressing.

### 6.2 What a benchmark harness must defeat

Timing a piece of Java code with two calls to `System.nanoTime()` around a loop measures something,
but rarely what you meant. Four things get in the way, and a harness such as
[JMH](https://github.com/openjdk/jmh) (the Java Microbenchmark Harness, written by the JDK's own
performance engineers) exists to defeat each one:

- **Warm-up.** The first thousands of calls run in the interpreter and C1 (§2.2). JMH runs
  **warm-up iterations** whose results it throws away, then **measurement iterations**.
  `FfmBench` asks for `@Warmup(iterations = 3, time = 1)` and `@Measurement(iterations = 5, time =
  1)`: three seconds of warm-up, five one-second measurements.
- **Dead code elimination.** If the result of a computation is never used, C2 deletes the
  computation, and the benchmark times an empty loop. JMH passes every benchmark method's return
  value to a **blackhole**, an object that consumes values in a way the JIT cannot see through.
  That is why every method in `FfmBench` returns what it computed.
- **Constant folding.** If the inputs are constants, C2 computes the answer at compile time. Inputs
  must come from a **state** object's ordinary fields: `FfmBench` passes `value`, a non-final field
  initialized to −42, not the literal `-42`.
- **Interference between benchmarks.** Profiles from one benchmark (a call site that saw class A)
  shape how C2 compiles the next one. JMH runs each benchmark in a fresh JVM, a **fork**
  (`@Fork(1)`), and starts that JVM with the same JVM options as the one that launched it.

What is left is noise: other processes, frequency scaling, a shared cloud machine. JMH reports a
confidence interval, the nightly check allows a 25 % threshold (§16.3), and numbers from different
machines or different harnesses are not compared with each other. The FFM numbers of this chapter
show why: the hand-timed loop of M3-05 measured 16.5 ns for a regular `abs` downcall, JMH on a
GitHub runner 3.6 ns (§4.4). Both are right about their own machine and loop; only the *difference*
between regular and critical, measured in the same run, means something.

### 6.3 Reading a JMH result

A run of `./gradlew :bench:jmh -Pjmh.includes=CollectionsBench` ends with a table like this (the
scores are those of the committed nightly baseline of a GitHub runner, which keeps no error column;
your run prints its own numbers in both places):

```
Benchmark                        Mode  Cnt      Score   Error  Units
CollectionsBench.hashMapGet      avgt    5  26261.505 ±  ...   ns/op
CollectionsBench.intIntMapGet    avgt    5   7850.265 ±  ...   ns/op
```

- **Mode `avgt`** is `Mode.AverageTime`: the score is the average time of one call of the
  benchmark method, an **operation**.
- **`Cnt`** is the number of measurement iterations the score is computed from.
- **`Error`** is half the width of the 99.9 % confidence interval of the score across those
  iterations. Two scores whose intervals overlap are not reliably different.
- **What is one operation?** Read the benchmark, not the table. Here one operation is 4,096 lookups
  (the loop over `lookups` in
  [`CollectionsBench`](../../bench/src/main/java/dev/jeddy/bench/CollectionsBench.java), `KEYS =
  4096`). So `IntIntMap.get` costs $7850.265 / 4096 = 1.92$ ns per lookup and `HashMap<Integer,
  Integer>.get` $26261.505 / 4096 = 6.41$ ns, 3.35 times as much. §19.4 explains the difference: the
  primitive map reads two `int[]` arrays, the boxed one follows references to `Integer` objects and
  to its entry objects.

With `-prof gc`, JMH adds **secondary metrics** per benchmark, of which one matters here:
`gc.alloc.rate.norm`, the bytes allocated per operation. It is computed from the allocation
counters of *every* thread in the fork (JMH's own threads and the JVM's background threads
included) divided by the number of operations, so a long operation picks up some bytes it did not
allocate. §16.3 shows how the nightly check allows for that, and why exact per-thread proof is the
allocation assertion's job.

JMH has other profilers. `-prof perfasm` (Linux, needs the `perf` tool and the `hsdis`
disassembler plugin) prints the hottest machine code of the benchmark; it is how milestone M1-12
asked for the vectorization of the math kernels to be confirmed
([bench/README.md](../../bench/README.md)).

### 6.4 JFR and the JVM's own log

**JFR**, the JDK Flight Recorder, records events from inside the JVM into a file with little
overhead: `-XX:StartFlightRecording:filename=rec.jfr,...` starts it, `jfr print --events <names>
rec.jfr` prints it. Three of its events concern allocation:

- `jdk.ObjectAllocationInNewTLAB`: an allocation that needed a new TLAB (§3.1), with the object's
  class, size and stack trace;
- `jdk.ObjectAllocationOutsideTLAB`: an allocation too large for a TLAB, made directly in shared
  memory;
- `jdk.ObjectAllocationSample`: a throttled sample of allocations, cheap enough to leave on.

The first two fire only when a TLAB is refilled, so with normal TLABs of kilobytes they show one
allocation out of hundreds. To see *every* allocation you make TLABs tiny — the nightly does that on
request with `-XX:MinTLABSize=256 -XX:TLABSize=256 -XX:-ResizeTLAB` and the event settings in
[`.github/jfr/alloc.jfc`](../../.github/jfr/alloc.jfc) — or switch them off with `-XX:-UseTLAB`, so
that every allocation takes the slow path and becomes an "outside TLAB" event. That is slow, and it
is exactly what found the mystery of decision D70 (§25.2).

**Unified logging** is the JVM's own diagnostic log:
`-Xlog:<tags>=<level>:file=<path>:<decorators>`. This chapter uses four tag sets:

| Option | What it logs | Used in |
|---|---|---|
| `-Xlog:jit+compilation=debug` | every compile: tier, method, OSR, "made not entrant" | §2.2, §2.3, the perf capture's JIT log (§17.3) |
| `-Xlog:gc*` | every collection with its phases and pauses | §3.3, the CI perf jobs |
| `-Xlog:class+load=info` | every class loaded, with its source | late linkage in the perf capture (§25.4) |
| `-Xlog:deoptimization=debug` | every deoptimization with its reason | §2.3 |

The `uptimemillis` (or `uptime`) decorator stamps each line with the time since the JVM started,
which is how the perf capture lines up a log line with a frame. Logging can also be switched on in a
running JVM with `jcmd <pid> VM.log`, and a Java program can do the same to itself through the
`DiagnosticCommand` management bean — which is what `JitLog` does (§17.3).

---

## 7. Lock-free handoffs: memory order on real hardware

The engine's threads hand each other work through three structures: the triple buffer of frames
([chapter 01, on the triple buffer
handoff](01-architecture.md#10-step-1-the-triple-buffer-handoff)), single-producer rings of `int`
records (§13) and multi-producer queues of job and asset handles (§14). None of them takes a lock.
This section gives the ideas they rest on; Part B builds the two queues.

### 7.1 Happens-before, in one paragraph

[Chapter 01, on happens-before](01-architecture.md#62-happens-before), defines the relation that
Java's memory model is stated in: if action X *happens-before* action Y, then Y sees everything X
wrote. Program order inside one thread, a write to a `volatile` variable followed by a read that
sees it, and transitivity are enough for every handoff in the engine: the producer writes its data
with plain stores, then publishes with one synchronizing write; the consumer reads that write, then
reads the data with plain loads. Everything before the publication happens-before everything after
the consumption. The question this section answers is which kind of synchronizing write and read to
use, and what each costs on the CPU.

### 7.2 Access modes, and what they compile to

A **`VarHandle`** (L00 §9.5; [chapter 01, on the fields of a
job](01-architecture.md#124-the-fields-of-a-job), shows one) gives a field or an array element
several **access modes**, from weakest to strongest:

| Mode | Methods | Guarantee | x86-64 code |
|---|---|---|---|
| plain | ordinary `x = 1`, `x` | none between threads: the compiler may keep the value in a register, reorder it, or hoist it out of a loop | `mov` |
| opaque | `getOpaque`, `setOpaque` | the access really happens each time, atomically, and accesses to *this* variable are seen in one order; nothing about other variables | `mov` |
| acquire / release | `getAcquire`, `setRelease` | a release write is ordered after every earlier read and write of the writing thread; an acquire read is ordered before every later one of the reading thread. Together: everything before the release is visible after an acquire that sees its value | `mov` |
| volatile | `getVolatile`, `setVolatile`, `volatile` fields | acquire and release, plus one total order of all volatile accesses | `mov` for reads; a write is followed by a full fence (HotSpot emits a `lock`-prefixed add of zero to a stack slot) |
| atomic read-modify-write | `compareAndSet`, `getAndAdd`, `getAndSet`, `weakCompareAndSet` | atomic, and as strong as volatile | `lock cmpxchg`, `lock xadd`, `xchg` |

Why is almost everything a plain `mov` on x86-64? Because x86 processors already keep most orders
on their own (the model is called **TSO**, total store order): a core never reorders two loads,
never reorders two stores, and never moves a store before an earlier load. The one reordering it
does is to let a load go ahead of an *earlier* store to a different address, because stores wait in
the core's **store buffer** before they reach the cache. Acquire and release forbid nothing x86
does, so they cost nothing at the CPU — they only stop the *compiler* from reordering. A volatile
write must also stop that one store-then-load reordering, which needs a fence instruction draining
the store buffer: tens of cycles. (Other processors, ARM for example, need real barrier instructions
for acquire and release too; Jeddy targets x64 only, DESIGN §1.)

That gives the engine's standard pattern, **publish with release, consume with acquire**:

```
producer                                     consumer
  write the data (plain stores)
  INDEX.setRelease(this, newIndex)  ───────▶  i = (long) INDEX.getAcquire(this)
                                              read the data (plain loads) up to i
```

The ring of §13 is exactly this. The opaque mode has a smaller job: in a loop that waits for a
value to change, a plain read may be hoisted out of the loop by C2 and the loop never ends; an
opaque read is performed on every iteration. The queue of §14 reads its positions with `getOpaque`
because a compare-and-set validates them anyway.

### 7.3 Cache coherence and false sharing

Each core has its own L1 and L2 caches (L00 §9.2). The hardware keeps the copies of one cache line
consistent with a **coherence protocol**: many cores may hold a line for reading, but a core that
writes must first own the line exclusively, which invalidates every other core's copy. The next
read by another core misses and fetches the line from the writer's cache, a transfer that takes
tens of nanoseconds.

That is fine when two threads share data on purpose. It is a pure loss when they don't: a
producer's `tail` and a consumer's `head` are separate variables, but if they sit in the same
64-byte line, every write by one core invalidates the other core's copy of a variable it never
touched. The line ping-pongs between the cores. This is **false sharing**, and the fix is to keep
each variable that one thread writes often on a line of its own.

A two-thread program run for this chapter shows its size on the 2-core container: two threads each
incremented their own element of one `AtomicLongArray` 50 million times. With the two elements
adjacent (8 bytes apart, the same line) an increment took 41–45 ns; with them 64, 128 or 256 bytes
apart, 21–23 ns. On a desktop with more cores the gap is usually larger; exercise 8 measures it on
your machine with JMH.

The JDK has an annotation for this, `@Contended`, which pads a field with 128 bytes on each side
(`-XX:ContendedPaddingWidth`, 128 by default — wider than a line because some processors fetch
lines in pairs). But it lives in the internal package `jdk.internal.vm.annotation`, and outside the
JDK's own classes it is ignored unless the JVM runs with `-XX:-RestrictContended`. Jeddy pads by
hand with the class hierarchy instead (§13.1), and measures the result (§13.2).

### 7.4 Waiting without burning a core

A lock-free structure never blocks, but its users sometimes have nothing to do: a worker with an
empty queue, the game thread waiting for the main thread to take a frame. They can **spin**
(re-check in a tight loop: the fastest reaction, but a core is busy doing nothing), **yield** (give
the core to another runnable thread) or **park** (sleep in the operating system until woken: no CPU
used, but waking costs microseconds, L00 §9.4).
[`SpinParkWaiter`](../../modules/core/src/main/java/dev/jeddy/core/concurrent/SpinParkWaiter.java)
does all three in turn, with a counter the caller keeps. [Chapter 01, on the workers and sleeping
and waking](01-architecture.md#137-the-workers-and-sleeping-and-waking), builds it with the job
system that uses it. Two of its parameter sets are worth knowing: the game thread waits for the main
thread with `new SpinParkWaiter(100, 20, 50_000)` (100 spins, 20 yields, then parks of 50 µs) and
the job system with `(256, 32, 50_000)`, whose idle workers use only its spin and yield phases and
then park for up to 2 ms (`LockSupport.parkNanos(this, 2_000_000)` in `JobSystem.workerLoop`) until
a submit unparks them. The spin phase calls `Thread.onSpinWait()`, which the JIT compiles to the x86
`pause` instruction: it tells the core that this is a spin loop, which saves power and gives the
core's resources to its other hardware thread.

**The triple buffer, in these terms.** [Chapter 01, on the triple buffer
handoff](01-architecture.md#10-step-1-the-triple-buffer-handoff), builds `TripleBuffer`; §7.2 says
why it is correct. The game thread's plain writes into the packet are ordered before its
`latest.getAndSet(writeIndex | FRESH)` in `publish` (a volatile read-modify-write, the strongest
mode); the main thread's `latest.getAndSet(readIndex)` in `acquire` is ordered before its plain
reads of the packet. The release half of the first and the acquire half of the second make the
happens-before edge, so the consumer sees every field of the frame. One atomic exchange per side per
frame is the entire synchronization cost of handing the 20,000 visible instances of DESIGN §14's
main-thread budget from the game thread to the main thread.

One more ordering rule belongs to the boundary with the GPU: job workers write instance records
into **write-combined** memory, whose stores are buffered and weakly ordered, and a release write
does not order them. Workers publish the end of their work with an atomic read-modify-write instead:
`JobSystem` counts a job's finished chunks down with `REMAINING.getAndAdd(j, -1)`, a
`lock`-prefixed instruction and therefore a full fence, which drains the write-combining buffers
before the count becomes visible. The other half of the rule is on the GPU side: the main thread
waits on the frame's GL fence with `glClientWaitSync` (`FrameRing`) before it hands that region of
the mapped buffer to the workers again, so a region is never written while the GPU still reads it.
[Chapter 01, on workers writing GPU memory](01-architecture.md#93-workers-writing-gpu-memory),
explains the four conditions that make that safe.

---

# Part B — Building it

## 8. Step 1: a downcall from an empty file

The smallest complete FFM program in the repository is the benchmark that priced every FFM decision
of the engine, [`FfmBench`](../../bench/src/main/java/dev/jeddy/bench/FfmBench.java). It calls
functions of the C standard library, so it runs on any machine without SDL or a GPU. Build it in
`bench/src/main/java/dev/jeddy/bench/FfmBench.java`.

### 8.1 The class and its JMH annotations

```java
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
```

- **`@State(Scope.Thread)`**: the class itself holds the benchmark's state (its fields), one instance
  per benchmark thread.
- **`@BenchmarkMode(Mode.AverageTime)`** with **`@OutputTimeUnit(NANOSECONDS)`**: report the average
  time of one call, in nanoseconds (§6.3).
- **`@Warmup`**, **`@Measurement`**, **`@Fork(1)`**: three one-second warm-up iterations, five
  one-second measured iterations, in one fresh JVM (§6.2).

### 8.2 The constants: linker, lookup, descriptors, handles

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
    private static final MethodHandle MEMCPY =
            LINKER.downcallHandle(LIBC.findOrThrow("memcpy"), MEMCPY_DESC);
    private static final MethodHandle MEMCPY_CRITICAL =
            LINKER.downcallHandle(
                    LIBC.findOrThrow("memcpy"), MEMCPY_DESC, Linker.Option.critical(true));
```

Line by line:

- **`Linker.nativeLinker()`** is the linker for the platform the JVM runs on (§4.1): System V on
  Linux, Microsoft x64 on Windows. One per process; asking again returns the same object.
- **`defaultLookup()`** finds the C library's functions (§4.3). `findOrThrow("abs")` returns the
  address of `abs` as a zero-length segment, or throws `NoSuchElementException` if there is none.
- **`ABS_DESC`** is `int abs(int)`: return layout first, then the parameters (§4.2).
- **`MEMCPY_DESC`** is `void *memcpy(void *dest, const void *src, size_t n)`, with one deliberate
  lie, explained by the comment: `memcpy` returns a pointer (its `dest` argument), and an `ADDRESS`
  return would create a `MemorySegment` per call (§4.10). The benchmark never looks at the result,
  so it declares the return as `JAVA_LONG`: the same 64 bits in `rax`, received as a primitive. On
  x64 a pointer and a 64-bit integer travel in the same register, so the C function cannot tell the
  difference. `size_t` is 64 bits on both of Jeddy's platforms, hence `JAVA_LONG` for `n`.
- **Four handles, all `static final`** (§2.5): a regular and a critical version of each function.
  `abs` takes no pointers, so its critical version passes `critical(false)`: heap access would be
  meaningless. `memcpy`'s critical version passes `critical(true)`, so that it can be given heap
  segments (§4.5).

Is `abs` allowed to be critical? It runs for a few instructions, never blocks and never calls back
into Java: it fits the Javadoc's definition exactly. So does a 64-byte `memcpy`. (A `memcpy` of a
megabyte would not.)

### 8.3 The state: fields, setup and teardown

```java
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
```

- **`value`** is an ordinary field, not a constant, so C2 cannot compute `abs(-42)` at compile
  time (§6.2).
- **`matrix` and `target`** are a 4×4 matrix's worth of floats, 64 bytes: the typical parameter block
  of a uniform upload.
- **The heap wrappers are made once**, in `setup`. `MemorySegment.ofArray` allocates 72 bytes per
  call (§3.1); creating it inside the measured method would measure the wrapper too. DESIGN §4.4
  makes this the rule for engine code.
- **`@Setup(Level.Trial)`** runs once per fork, before the warm-up, on the benchmark thread; that is
  why a *confined* arena works here (§4.7). `allocate(64, 16)` asks for 64 bytes aligned to 16.
- **`@TearDown`** closes the arena, freeing both blocks, after the last iteration.

### 8.4 The six measurements

```java
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
```

- **`throws Throwable`**: `invokeExact` is declared to throw `Throwable`, so a caller must catch it
  or declare it. Engine code wraps the call in a method that catches and rethrows an unchecked
  exception (jextract's and the GL generator's output both do, §20).
- **The casts are part of the call's type** (§4.4): `(int)` makes the call site `(int)int`, matching
  `ABS`; `(long)` with the `64L` literal makes it `(MemorySegment, MemorySegment, long)long`,
  matching `MEMCPY_DESC`. Writing `64` instead of `64L` would make the third parameter an `int` and
  throw `WrongMethodTypeException`.
- **Every method returns its result**, which JMH consumes (§6.2).
- **`mat4CopyThenRegular`** is what engine code must do when critical linking is off: copy the
  array into native memory with the bulk `MemorySegment.copy` (an intrinsic that compiles to a block
  copy), then call regularly. The renderer does exactly this for its matrices (§21.3).

### 8.5 What it measured

Two sets of numbers exist, from two harnesses on two machines (§6.2):

| Benchmark | M3-05 hand-timed loop, JDK 27, Linux ([bench/README.md](../../bench/README.md)) | JMH nightly baseline, GitHub Linux runner ([linux-x64.json](../../bench/baseline/linux-x64.json)) |
|---|---|---|
| `absRegular` | 16.5 ns | 3.62 ns |
| `absCritical` | 3.6 ns | 2.73 ns |
| `mat4OffHeapRegular` | 13.9 ns | 6.91 ns |
| `mat4OffHeapCritical` | 10.1 ns | 5.14 ns |
| `mat4HeapCritical` | 10.5 ns | 5.49 ns |
| `mat4CopyThenRegular` | 18.9 ns | 15.78 ns |

Within each column the story is the same:

- **Critical is cheaper** than regular, by $16.5 - 3.6 = 12.9$ ns in the first column and
  $3.62 - 2.73 = 0.89$ ns in the second. The thread-state transition of §4.4 is what was saved; how
  much it costs depends on the processor (the fence in step 4 is the expensive part).
- **Heap access costs about the same as off-heap** under critical linking: 10.5 against 10.1 ns,
  5.49 against 5.14 ns. Passing the array's address directly is as good as passing native memory.
- **Copying first is the most expensive way** to pass an array: 18.9 and 15.78 ns, because it adds
  a 64-byte copy to a regular call.

DESIGN §4.4 drew the conclusion for the GL bindings: an allow-list of critical commands, heap
segments permitted for them (§21). Note what the benchmark does *not* measure: the cost of the C
function itself. For `glUniform4f` on the Mesa software driver the driver's own work dominated
(37.0 ns regular against 33.2 ns critical, [chapter 05, on measured
numbers](05-opengl-from-java.md#29-measured-numbers)).

---

## 9. Step 2: loading the libraries

`SymbolLookup.loaderLookup()`, which jextract's code uses (§4.3), only sees libraries loaded with
`System.load`. So before any SDL or zstd call, something must load SDL3, SDL3_mixer and zstd — in
the right order, from files the engine trusts. That is
[`NativeLoader`](../../modules/core/src/main/java/dev/jeddy/core/natives/NativeLoader.java)
(DESIGN §6.3).

### 9.1 The manifest

Jeddy builds its native libraries itself (`natives/build.py`, a CMake superbuild with pinned
versions, DESIGN §6.2) and ships them with a manifest, `natives.json`. A Linux manifest written by
`natives/build.py` looks like this (condensed: hashes shortened, three tools-only libraries left
out):

```json
{
  "platform": "linux-x64",
  "natives_version": 1,
  "libraries": [
    { "name": "SDL3", "file": "libSDL3.so.0", "sha256": "35f1bb7d…", "runtime": true,
      "depends": [], "version": "release-3.4.18" },
    { "name": "SDL3_mixer", "file": "libSDL3_mixer.so.0", "sha256": "c36056ab…", "runtime": true,
      "depends": ["SDL3"], "version": "release-3.2.4" },
    { "name": "zstd", "file": "libzstd.so.1", "sha256": "41207fcd…", "runtime": true,
      "depends": [], "version": "v1.5.7" }
  ]
}
```

Each library has a name, a file, the SHA-256 hash of the file, whether games need it at run time
(`ktx`, `meshoptimizer` and the cooker's shim are `"runtime": false`: tools only), and the libraries
it depends on. The list is in **dependency order**: SDL3 comes before SDL3_mixer, which needs it.

### 9.2 Which platform am I on?

[`Platform`](../../modules/core/src/main/java/dev/jeddy/core/natives/Platform.java) is an enum of
the two supported platforms, with the identifier used in manifests and folder names:

```java
public enum Platform {
    /** 64-bit Linux. */
    LINUX_X64("linux-x64"),
    /** 64-bit Windows. */
    WINDOWS_X64("windows-x64");

    private static final Platform CURRENT = detect();
    // ... the id field, id(), isWindows()

    private static Platform detect() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!arch.equals("amd64") && !arch.equals("x86_64")) {
            return null;
        }
        if (os.startsWith("windows")) {
            return WINDOWS_X64;
        }
        if (os.startsWith("linux")) {
            return LINUX_X64;
        }
        return null;
    }
```

`detect` runs once, in the enum's static initializer. An unsupported machine gets `null`, and
`current()` (not shown) throws a clear `UnsupportedOperationException` only when someone asks — so
pure-Java tools that never load natives still start on, say, an ARM laptop. `Locale.ROOT` keeps
`toLowerCase` from producing a dotless `ı` on a Turkish system.

### 9.3 `NativeLoader`

The class keeps what it resolved in three static fields:

```java
public final class NativeLoader {
    /** One library from the manifest. */
    public record Library(
            String name, String file, String sha256, boolean runtime, List<String> depends) {}

    private static List<Library> manifest;
    private static Path directory;
    private static final Set<String> LOADED = new HashSet<>();
```

Every public method is `static synchronized`: loading happens at startup, possibly from several
threads (tests), and must happen once. The lock also makes the plain fields safe to read and write.

**Loading, in manifest order:**

```java
    /** Loads the named libraries and everything they depend on. Idempotent. */
    public static synchronized void load(String... names) {
        resolve();
        Map<String, Library> byName = new LinkedHashMap<>();
        for (Library l : manifest) {
            byName.put(l.name(), l);
        }
        Set<String> wanted = new HashSet<>();
        for (String n : names) {
            addWithDependencies(n, byName, wanted);
        }
        for (Library l : manifest) {
            if (wanted.contains(l.name()) && !LOADED.contains(l.name())) {
                Path p = directory.resolve(l.file());
                System.load(p.toAbsolutePath().toString());
                LOADED.add(l.name());
            }
        }
    }

    // ... loadRuntime, isLoaded, libraries, directory

    private static void addWithDependencies(
            String name, Map<String, Library> byName, Set<String> out) {
        Library l = byName.get(name);
        if (l == null) {
            throw new IllegalArgumentException(
                    "native library '" + name + "' is not in the manifest " + byName.keySet());
        }
        if (out.add(name)) {
            for (String d : l.depends()) {
                addWithDependencies(d, byName, out);
            }
        }
    }
```

- **`resolve()`** finds the manifest and the directory and verifies every file (below).
- **`addWithDependencies`** collects the requested libraries and, recursively, what they depend on.
  `out.add(name)` returns `false` for a library already collected, which stops the recursion on
  shared dependencies. Asking for `SDL3_mixer` collects `{SDL3_mixer, SDL3}`.
- **The loading loop walks the manifest, not the set.** Sets have no useful order; the manifest
  lists dependencies first, so SDL3 is loaded before SDL3_mixer. That matters on Windows, where a
  DLL's dependencies are resolved by module name from the libraries already loaded in the process
  (DESIGN §6.3).
- **`System.load(absolutePath)`** maps the library into the process and makes its symbols visible
  to `loaderLookup()`. It is a restricted method (§4.9), which is one reason `dev.jeddy.core` is in
  the native-access list (§18.1).
- **`LOADED`** makes `load` idempotent: `JeddyApp` and a test can both ask for SDL3.

`loadRuntime()` loads every library marked `"runtime": true`; it builds a list of names and calls
`load`. Startup is a cold path, so the `LinkedHashMap`, the sets and the varargs array are fine
here.

**Finding the libraries.** `resolve()` looks in three places, first match wins:

```java
    private static void resolve() {
        if (manifest != null) {
            return;
        }
        Platform platform = Platform.current();
        String dirProperty = System.getProperty("jeddy.natives.dir");
        try {
            Path image = Path.of(System.getProperty("java.home"), "lib", "jeddy", "natives");
            if ((dirProperty == null || dirProperty.isBlank())
                    && Files.isRegularFile(image.resolve("natives.json"))) {
                dirProperty = image.toString();
            }
            if (dirProperty != null && !dirProperty.isBlank()) {
                Path dir = Path.of(dirProperty).toAbsolutePath();
                Path json = dir.resolve("natives.json");
                if (!Files.isRegularFile(json)) {
                    throw new IllegalStateException(
                            "jeddy.natives.dir=" + dir + " has no natives.json");
                }
                List<Library> libs = parse(Files.readAllBytes(json), platform);
                for (Library l : libs) {
                    verify(dir.resolve(l.file()), l);
                }
                manifest = libs;
                directory = dir;
                return;
            }
            String base = "natives/" + platform.id() + "/";
            URL url = findResource(base + "natives.json");
            // ... a clear error if there is none
            byte[] json;
            try (InputStream in = url.openStream()) {
                json = in.readAllBytes();
            }
            List<Library> libs = parse(json, platform);
            Path dir = cacheRoot().resolve(hex(sha256(json)).substring(0, 16));
            Files.createDirectories(dir);
            for (Library l : libs) {
                extract(base + l.file(), dir, l);
            }
            manifest = libs;
            directory = dir;
        } catch (IOException e) {
            throw new UncheckedIOException("could not prepare native libraries", e);
        }
    }
```

1. **`-Djeddy.natives.dir=<dir>`**, a directory with its own `natives.json`, as a local native build
   writes it (`natives/build.py`, CLAUDE.md "Commands"). Used in place.
2. **`<java.home>/lib/jeddy/natives`**, inside the running Java runtime. A packaged game's `jlink`
   image carries its natives there (decision **D71**, §18.2), so a shipped game needs no property
   and extracts nothing.
3. **A `natives/<platform>/natives.json` resource** from the `jeddy-natives-<platform>` jar on the
   class or module path (development with Gradle). Jar entries cannot be loaded by the operating
   system, so each library is extracted once into a per-user cache: the directory in
   `-Djeddy.natives.cache` when that property is set (`cacheRoot`, checked first), else
   `%LOCALAPPDATA%\Jeddy\natives` on Windows, `$XDG_CACHE_HOME/jeddy/natives` (or
   `~/.cache/jeddy/natives`) on Linux, in a subfolder named after the first 16 hex digits of the
   manifest's own SHA-256. A new natives
   release has a new manifest and therefore a new folder; an old game's libraries are never
   overwritten.

In the first two cases every library's hash is verified before anything is loaded. In the third,
`extract` verifies as it goes:

```java
    private static void extract(String resource, Path dir, Library lib) throws IOException {
        Path target = dir.resolve(lib.file());
        if (Files.isRegularFile(target) && hex(sha256(target)).equals(lib.sha256())) {
            return;
        }
        // ... find the resource, or throw
        Path tmp = Files.createTempFile(dir, lib.file(), ".tmp");
        try {
            try (InputStream in = url.openStream()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            verify(tmp, lib);
            try {
                Files.move(
                        tmp,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
```

- **Already there and correct?** Then nothing to do: the second start of a game hashes the files
  and loads them.
- **Write to a temporary file, verify, then rename.** Two programs starting at the same moment (a
  game and the editor) could otherwise both write `libSDL3.so.0`, and one could load a half-written
  file. A rename within one directory is atomic: the target is either the old complete file or the
  new complete file. Where the file system cannot rename atomically, a plain move is the fallback.
- **`finally` deletes the temporary file** if anything failed; after a successful move it no longer
  exists and `deleteIfExists` does nothing.

`verify` computes the file's SHA-256 (64 KiB at a time) and throws `IllegalStateException:
SHA-256 mismatch for <file>` if it differs from the manifest. Loading a library runs its code in the
game's process; a corrupted download or a replaced file must stop the game rather than run.

### 9.4 Native access, at run time and at compile time

**At run time** the JDK wants each module that calls restricted methods named (§4.9). The engine's
launchers list exactly the modules that use FFM — `dev.jeddy.core` (it calls `System.load` and
`reinterpret`), `compress`, `sdl`, `gl`, `window`, `input`, `asset`, `audio`, `render` and the
cooker — in `JEDDY_NATIVE_ACCESS_MODULES` (§18.1). Tests run on the class path, so the shared build
convention passes `--enable-native-access=ALL-UNNAMED` to every test JVM
([`jeddy.java-base.gradle.kts`](../../build-logic/src/main/kotlin/jeddy.java-base.gradle.kts),
DESIGN §6.3).

**At compile time** `javac` warns about calls to restricted methods (its `restricted` lint), and
Jeddy compiles with `-Werror`, so such a warning fails the build. The warning is switched off only
in modules that apply `jeddy.native-library` or `jeddy.ffm-bindings` (the jextract modules; that
plugin applies `jeddy.native-library` itself), decision **D17**. The switch is in
[`jeddy.native-library.gradle.kts`](../../build-logic/src/main/kotlin/jeddy.native-library.gradle.kts):

```kotlin
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.replaceAll { arg -> if (arg.startsWith("-Xlint:")) "$arg,-restricted" else arg }
}
```

It rewrites the `-Xlint:all,-module,-processing` argument of the base convention into
`-Xlint:all,-module,-processing,-restricted`. Pure-Java modules (`math`, `physics`, `scene`, `ui`,
`rules` and the others that apply `jeddy.java-library`) keep the warning, so an accidental native
call there fails the build. (`app` and `editor` also apply `jeddy.native-library`, not because they
call restricted methods but because their tests load the platform's natives; the plugin adds those
to the test runtime path.)

---

## 10. Step 3: native memory you own

[`core.memory`](../../modules/core/src/main/java/dev/jeddy/core/memory) holds four small classes for
native memory with a lifetime the engine controls. Most subsystems open their own arenas directly
(§4.8); these classes are the shared building blocks DESIGN §4.3 describes.

### 10.1 `OffHeap`: a block with an owner

```java
public final class OffHeap implements AutoCloseable {
    private final Arena arena;
    private final MemorySegment segment;
    private final MemoryCategory category;
    private boolean closed;

    private OffHeap(long bytes, long alignment, MemoryCategory category) {
        this.arena = Arena.ofShared();
        this.segment = arena.allocate(bytes, alignment);
        this.category = category;
        MemoryStats.allocated(category, bytes);
    }

    public static OffHeap allocate(long bytes, long alignment, MemoryCategory category) {
        if (bytes <= 0) {
            throw new IllegalArgumentException("bytes <= 0: " + bytes);
        }
        return new OffHeap(bytes, alignment, category);
    }

    /** Returns the block's memory. Invalid after {@link #close()}. */
    public MemorySegment segment() {
        return segment;
    }

    /** Frees the block. Idempotent. */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            MemoryStats.released(category, segment.byteSize());
            arena.close();
        }
    }
}
```

(Condensed: the class Javadoc, the Javadoc of `allocate` — "Allocates a zeroed block" and its
parameters — and `byteSize()`, which returns `segment.byteSize()`, are left out.)

- **One shared arena per block.** A shared arena can be closed from any thread (§4.8), so a block
  allocated on a loading thread can be freed on the main thread. Giving each block its own arena
  means each can be freed on its own, at a moment the owner chooses: the deterministic lifetime that
  `Arena.ofAuto()` would not give.
- **The memory is zeroed**, as all arena memory is (§4.8); the Javadoc of `allocate` says so.
- **`closed`** makes `close` idempotent. `Arena.close` is deliberately not (§4.8): a second call
  throws. A block owned by an object that may be closed from two places (a normal shutdown and an
  error path) needs the flag. It is a plain field: the Javadoc's thread ownership says "`close()`
  must not race with use", so no two threads close the same block at the same time.
- **`MemoryStats`** counts the block in a category.
- **`AutoCloseable`**, so a test or a loader can use try-with-resources.

### 10.2 `MemoryStats`: lock-free accounting

```java
public final class MemoryStats {
    private static final int N = MemoryCategory.values().length;
    private static final AtomicLongArray CURRENT = new AtomicLongArray(N);
    private static final AtomicLongArray PEAK = new AtomicLongArray(N);

    private MemoryStats() {}

    /** Records {@code bytes} allocated in {@code category}. */
    public static void allocated(MemoryCategory category, long bytes) {
        int i = category.ordinal();
        long now = CURRENT.addAndGet(i, bytes);
        long peak;
        while (now > (peak = PEAK.get(i))) {
            if (PEAK.compareAndSet(i, peak, now)) {
                break;
            }
        }
    }

    /** Records {@code bytes} released in {@code category}. */
    public static void released(MemoryCategory category, long bytes) {
        CURRENT.addAndGet(category.ordinal(), -bytes);
    }
```

The categories are an enum,
[`MemoryCategory`](../../modules/core/src/main/java/dev/jeddy/core/memory/MemoryCategory.java):
`TRANSIENT`, `RENDER`, `AUDIO`, `ASSET`, `PHYSICS`, `NATIVE`, `OTHER`. One `AtomicLongArray` slot
per category holds the current bytes, another the peak.

- **`addAndGet`** adds atomically and returns the new total: several threads can allocate at once.
- **The peak needs a compare-and-set loop.** "If the new total is above the peak, raise the peak"
  is a read and a write; another thread could raise the peak between them. The loop re-reads the
  peak each time: if it is already at least `now` (someone else raised it higher), it stops; if not,
  it tries to replace exactly the value it read, and retries if that failed. The same lock-free
  pattern as L01 §11.2's allocator.
- **`summary()`** (not shown) builds a one-line string per category and allocates: it is for logs and
  overlays, and its Javadoc says so.

### 10.3 `FrameAllocator`: per-frame scratch without `malloc`

A frame sometimes needs native temporaries: a struct to pass to C, a small array of handles. A
confined arena per frame would `malloc` and `free` every frame (§4.8). A **bump allocator** over
one block allocated once costs an addition per request and a single store to reset — the same idea
as [Lesson 1](lessons/01-buffers-in-flight.md#11-step-5-allocate--a-lock-free-bump-allocator)'s
ring, without its atomics, because each thread gets its own.

```java
public final class FrameAllocator implements SegmentAllocator, AutoCloseable {
    private final OffHeap block;
    private final MemorySegment segment;
    private long offset;
    private long highWater;

    /**
     * Creates an allocator.
     *
     * @param capacityBytes size of the block
     */
    public FrameAllocator(long capacityBytes) {
        block = OffHeap.allocate(capacityBytes, 64, MemoryCategory.TRANSIENT);
        segment = block.segment();
    }

    /** Returns the whole block; offsets from {@link #allocateOffset} index into it. */
    public MemorySegment segment() {
        return segment;
    }
```

The block is aligned to 64, a cache line, so offsets that are multiples of 64 start lines. `offset`
is the next free byte; `highWater` the most ever used in one frame, for sizing.

**The allocation, as an offset:**

```java
    public long allocateOffset(long byteSize, long alignment) {
        if (alignment <= 0 || (alignment & (alignment - 1)) != 0) {
            throw new IllegalArgumentException("alignment must be a power of two: " + alignment);
        }
        long start = (offset + alignment - 1) & -alignment;
        long end = start + byteSize;
        if (end > segment.byteSize()) {
            throw new IllegalStateException(
                    "FrameAllocator out of space: need "
                            + end
                            + " of "
                            + segment.byteSize()
                            + " bytes; raise its capacity");
        }
        offset = end;
        if (end > highWater) {
            highWater = end;
        }
        return start;
    }
```

- **The power-of-two check** is L00 §6.5's test: `(a & (a - 1)) == 0` exactly for powers of two.
  The rounding below only works for those.
- **`(offset + alignment - 1) & -alignment`** rounds the offset up to a multiple of the alignment,
  L00 §6.6's formula. With `offset = 3` and `alignment = 8`: $3 + 7 = 10$, and $-8$ in two's
  complement is `…11111000` (L00 §5.3), so `10 & -8` clears the low three bits of `1010₂` and gives
  `1000₂` = 8.
- **Out of space throws instead of growing.** Growing would allocate in the middle of a frame and
  move memory that earlier requests in the same frame still point into. A fixed budget, sized from
  `highWaterMark()`, keeps frames deterministic; the message says what to do.
- **The error message concatenates strings**, which allocates — but only on the failure path, which
  ends the frame anyway.

`UtilitiesTest.frameAllocatorBumpsAlignsAndResets` walks it with a 1,024-byte allocator:
`allocateOffset(3, 1)` returns 0 (offset becomes 3); `allocateOffset(8, 8)` rounds 3 up to 8 and
returns 8 (offset 16); `allocate(ValueLayout.JAVA_INT, 4)` asks for 16 bytes at alignment 4 and gets
bytes 16 to 32; `allocateOffset(2000, 1)` throws; after `reset()`, `used()` is 0 and
`highWaterMark()` is 32.

**Two APIs.** The Javadoc tells you which to use:

```java
 *   <li>{@link #allocateOffset(long, long)} returns an offset into {@link #segment()}. Reading and
 *       writing through {@code segment()} at that offset allocates nothing; use this in hot paths.
 *   <li>As a {@link SegmentAllocator} ({@code allocate}, {@code allocateFrom}): convenient for FFM
 *       calls, but each call returns a new slice object, which the JIT usually but not always
 *       removes.
```

```java
    @Override
    public MemorySegment allocate(long byteSize, long byteAlignment) {
        long start = allocateOffset(byteSize, byteAlignment);
        return segment.asSlice(start, byteSize);
    }
```

Implementing `SegmentAllocator` brings all of FFM's conveniences (`allocateFrom("text")`,
`allocate(JAVA_INT, 4)`), and each returns a slice: a new `MemorySegment` object of 32 bytes
(§3.1). "Usually but not always removes" is §2.6 in five words. Hot paths use the offset API.

The rest is one line each: `reset()` sets `offset = 0`; `used()` returns `offset`;
`highWaterMark()` and `capacity()` report sizes; `close()` closes the block. The allocator is not
thread-safe: one per thread, for example one per job-system thread slot through `PerWorker`
([chapter 01, on per-thread
scratch](01-architecture.md#142-per-thread-scratch-perworker-and-objectpool)).

(Today only the tests create a `FrameAllocator`. The renderer's per-frame native scratch is
preallocated per object instead — `DebugRenderer`'s 64-byte matrix block, §21.3 — and per-frame GPU
data goes into the frame ring of [Lesson 1](lessons/01-buffers-in-flight.md).)

### 10.4 `CStrings`: C strings made once

C functions take strings as NUL-terminated byte arrays in native memory. SDL's hints and
properties are named by strings (`"SDL_RENDER_VSYNC"`), GL commands are looked up by name, and debug
groups are named every frame. Converting a Java `String` each time would allocate native memory per
call. [`CStrings`](../../modules/core/src/main/java/dev/jeddy/core/memory/CStrings.java) converts
each distinct string once:

```java
public final class CStrings {
    private static final ConcurrentHashMap<String, MemorySegment> CACHE = new ConcurrentHashMap<>();

    private CStrings() {}

    /** Returns the cached C string for {@code s}, creating it on first use (lives forever). */
    public static MemorySegment of(String s) {
        MemorySegment cached = CACHE.get(s);
        if (cached != null) {
            return cached;
        }
        return CACHE.computeIfAbsent(
                s, k -> Arena.global().allocateFrom(k, StandardCharsets.UTF_8));
    }

    /**
     * Reads a NUL-terminated UTF-8 string at a native address (allocates the {@code String}).
     * Returns {@code null} for a null pointer.
     */
    public static String read(MemorySegment pointer) {
        if (pointer == null || pointer.equals(MemorySegment.NULL)) {
            return null;
        }
        return pointer.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_8);
    }
}
```

- **`Arena.global()`**: the strings live until the process ends, by design. The Javadoc's rule,
  "Only use it for a bounded set of strings", follows: a cache of arbitrary strings would grow
  forever.
- **`get` first, then `computeIfAbsent`.** `get` on a `ConcurrentHashMap` is a lock-free read; the
  common case, a string seen before, never touches the slower path. `computeIfAbsent` then makes
  sure that two threads asking for a new string at the same moment get the same segment. A `String`
  caches its hash code, so a hit costs a hash lookup and an `equals`, and allocates nothing.
- **`allocateFrom(k, UTF_8)`** encodes the string and appends the terminating zero byte: "jeddy"
  becomes a 6-byte segment (`UtilitiesTest.segmentCopiesAndCStrings` checks `byteSize()` is 6).
- **The lambda captures nothing** (it uses only its parameter `k`), so it is a cached singleton and
  allocates nothing (§3.1).
- **`read`** goes the other way, for strings C returns: a zero-length pointer (§4.9) declared
  `Long.MAX_VALUE` long, read up to the zero byte. It allocates the `String`, and says so.

Users: `Window.glProcAddress` (`SDL_GL_GetProcAddress(CStrings.of(name))`), `Sdl` and
`SdlProperties` for hints and properties, and `GLDebug.pushGroup`, which names a debug group every
frame ([chapter 05, on labels, groups and
counters](05-opengl-from-java.md#163-labels-groups-and-counters)). A fixed set of pass names costs
nothing after the first frame.

([`Segments`](../../modules/core/src/main/java/dev/jeddy/core/memory/Segments.java), the fourth
class, wraps the bulk `MemorySegment.copy` between primitive arrays and native memory in named
overloads, with unaligned native-order layouts so any offset works, and `alignUp`, the same round-up
as `allocateOffset`.)

---

## 11. Step 4: reading a C struct without allocating

The main thread asks SDL for every pending input event once per frame. With a mouse moving and keys
held, that is dozens of events.
[`EventPump`](../../modules/input/src/main/java/dev/jeddy/input/EventPump.java) reads them all
without creating one object; [chapter 01, on the input
ring](01-architecture.md#165-the-input-ring-the-same-idea-in-the-other-direction), shows how it then
sends each one to the game thread. Here is the reading half.

**Offsets as constants.** jextract generated a class per SDL struct, with each field's offset
(§4.2). The pump copies the ones it needs into `static final long`s, constants the JIT folds:

```java
    private static final long TYPE = SDL_CommonEvent.type$offset();
    private static final long TIMESTAMP = SDL_CommonEvent.timestamp$offset();
    private static final long KEY_SCANCODE = SDL_KeyboardEvent.scancode$offset();
    // ... one constant per field the pump reads
    private static final long TEXT = SDL_TextInputEvent.text$offset();
    // ...

    /** All of memory, for reading text that SDL owns without creating a segment per event. */
    private static final MemorySegment RAW = MemorySegment.NULL.reinterpret(Long.MAX_VALUE);
```

`SDL_CommonEvent` is `{ Uint32 type; Uint32 reserved; Uint64 timestamp; }`, so `TYPE` is 0 and
`TIMESTAMP` is 8 (the `reserved` field fills bytes 4–7, keeping the 8-byte timestamp aligned, §4.2).
`TEXT` is 24, as worked out in §4.2.

**One event, allocated once.** The constructor runs on the main thread:

```java
        this.arena = Arena.ofConfined();
        this.event = arena.allocate(SDL_Event.layout());
```

`SDL_Event` is a C union of every event struct, padded to 128 bytes (its `padding` member is
`Uint8 padding[128]`). The pump allocates one, in a confined arena owned by the main thread (it is
the only thread that may poll, §4.7), and SDL overwrites it on every poll.

**The poll loop:**

```java
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
        switch (type) {
            // ...
            case SdlEventType.TEXT_INPUT ->
                    emitText(InputRecord.TEXT, time, e.get(ValueLayout.JAVA_LONG, TEXT));
            // ...
        }
    }
```

- **`assertMainThread`** is one comparison (§19.5); polling elsewhere is the bug it exists to catch.
- **`MemorySegment e = event;`** copies the field into a local: the loop then reads a register, not
  a field that a call could change (the JIT cannot assume `SDL_PollEvent` leaves `this.event`
  alone).
- **`SDL_PollEvent(e)`** is the jextract wrapper (§20.1): a regular downcall (jextract emits no
  critical ones, DESIGN §6.4, and polling talks to the operating system's window system, which can
  take a while). It fills the 128 bytes and returns `true` while events remain.
- **`e.get(ValueLayout.JAVA_INT, TYPE)`** reads 4 bytes at offset 0; no object is created by a read.
- **The text pointer is read as a number.** `SDL_TextInputEvent.text` is a `const char *`. Reading
  it with `ValueLayout.ADDRESS` would create a `MemorySegment` per event (§4.10); reading it with
  `JAVA_LONG` gets the same 8 bytes as a `long` address.

**Reading bytes SDL owns.** The text is UTF-8, a variable-length encoding of Unicode: one byte for
ASCII, two to four for everything else. `emitText` decodes it one byte at a time through `RAW`, the
segment over all of memory, at the address it was given:

```java
    private void emitText(int kind, long time, long address) {
        if (address == 0) {
            return;
        }
        long p = address;
        while (true) {
            int b0 = RAW.get(ValueLayout.JAVA_BYTE, p) & 0xFF;
            if (b0 == 0) {
                return;
            }
            int cp;
            int extra;
            if (b0 < 0x80) {
                cp = b0;
                extra = 0;
            } else if (b0 >= 0xF0) {
                cp = b0 & 0x07;
                extra = 3;
            } else if (b0 >= 0xE0) {
                cp = b0 & 0x0F;
                extra = 2;
            } else if (b0 >= 0xC0) {
                cp = b0 & 0x1F;
                extra = 1;
            } else {
                cp = 0xFFFD; // stray continuation byte
                extra = 0;
            }
            p++;
            for (int i = 0; i < extra; i++) {
                int b = RAW.get(ValueLayout.JAVA_BYTE, p) & 0xFF;
                if ((b & 0xC0) != 0x80) {
                    cp = 0xFFFD; // truncated sequence; resume at this byte
                    extra = 0;
                    break;
                }
                cp = (cp << 6) | (b & 0x3F);
                p++;
            }
            emit(kind, time, cp, 0, 0, 0, 0);
        }
    }
```

- **`& 0xFF`** undoes the sign extension of a Java `byte` (L00 §5.4).
- **The lead byte says how many bytes follow**: `0xxxxxxx` none, `110xxxxx` one, `1110xxxx` two,
  `11110xxx` three. The comparisons test those patterns from the top (a byte ≥ `0xF0` starts with
  `1111`), and the masks keep the payload bits: 3, 4 or 5 of them.
- **Each continuation byte is `10xxxxxx`** and adds six bits: `(cp << 6) | (b & 0x3F)`. A byte that
  is not a continuation, or a continuation byte where a lead byte should be, becomes U+FFFD, the
  Unicode replacement character.
- **Worked example: "é"** is `C3 A9`. `0xC3` ≥ `0xC0`, so `cp = 0xC3 & 0x1F = 0x03` and one byte
  follows; `0xA9 & 0xC0` is `0x80`, a continuation; `cp = (0x03 << 6) | (0xA9 & 0x3F) = 0xC0 | 0x29
  = 0xE9` = 233, which is U+00E9, "é".
- **No segment per event.** `RAW` was created once; each read is an address and a byte. The address
  came from SDL, during the event's dispatch, and the loop stops at the terminating zero: it reads
  exactly the bytes SDL handed over, while they are valid. `reinterpret` makes this possible and
  also makes it the code's responsibility to be right, which is why it is restricted (§4.9) and why
  `dev.jeddy.input` is in the native-access list.

Each code point travels to the game thread as one 8-`int` record.
`EventPumpTest.pumpingMouseAndTypingAllocatesNothing` (in `modules/input`) pushes mouse motion,
keys, text, buttons and a wheel event into SDL, then runs `pump()` and the game side's
`Input.update()` under `Allocations.assertNoAllocation` (the acceptance criterion of task M2-04: 0 B
per frame while moving the mouse and typing).

---

## 12. Step 5: the allocation assertion

Every hot-path change ships with a test that it allocates nothing (CLAUDE.md, rule 6). This step
builds the helper those tests call,
[`Allocations`](../../testing/src/main/java/dev/jeddy/testing/Allocations.java).

### 12.1 Where it lives

The helper is in a small Gradle project of its own, `:testing` (decision **D18**). It is a plain
library on the class path, not a module, and the shared build convention adds it to every module's
test dependencies:

```kotlin
    if (project.path != ":testing") {
        // Shared test helpers, e.g. Allocations.assertNoAllocation (DESIGN §4.7).
        "testImplementation"(project(":testing"))
    }
```

(from [`jeddy.java-base.gradle.kts`](../../build-logic/src/main/kotlin/jeddy.java-base.gradle.kts)).
It also holds `GeneratedBindings` (§20.1) and `Fixtures`.

D18 records the choice; the shapes it passed over follow from the rules. The helper could not live
in `core`'s main sources: it calls JUnit's `Assertions.fail`, and a runtime module may have no
third-party dependency (rule 5, §1.1) and should not ship test code. A copy in each module's tests
would drift — 78 test classes in 20 modules call it. And a JPMS module would buy nothing: every
module's tests run as whitebox tests on the class path (no `module-info` under `src/test`, which is
also why the test JVM gets `--enable-native-access=ALL-UNNAMED`, §9.4), so a `module-info` for
`:testing` would only add `requires` clauses for JUnit. A plain class-path library that every test
configuration gets is the smallest thing that works.

### 12.2 The class

```java
public final class Allocations {
    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    private Allocations() {}

    /** Asserts that {@code body} allocates nothing, with default warm-up (25 rounds of 4000). */
    public static void assertNoAllocation(String what, Runnable body) {
        assertNoAllocation(what, 25, 4_000, body);
    }
```

- **`com.sun.management.ThreadMXBean`** is HotSpot's extension of the standard
  `java.lang.management.ThreadMXBean`; only the extension has the allocation counters (§3.2). The
  cast is safe on every JDK this engine runs on.
- **Looked up once**, into a `static final` field: the lookup allocates 728 bytes (§3.2).
- **The short form** uses 25 rounds of 4,000 calls, enough for nearly every body.

### 12.3 The measurement

```java
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
```

How each part follows from §2 and §3:

- **A round is a warm-up half and a measured half.** The first half gives the JIT calls to count
  (§2.2); the second half is measured with two reads of the exact per-thread counter (§3.2).
- **Pass as soon as one round measures exactly zero.** Interpreted and C1-compiled code allocate
  what C2 would remove (§2.6), so early rounds may allocate even for code that is fine. Code that
  allocates per call allocates in *every* round; a round of 4,000 calls with zero bytes proves that,
  in that round, no call allocated.
- **Sleep 20 ms from the third round on.** Compilation runs on background threads (§2.1). If two
  rounds were not enough, the method is probably queued or being compiled; sleeping gives the
  compiler the CPU (on a 2-core machine the test thread competes with it) and time to install the
  code.
- **Fail only if no round in `maxRounds` is clean,** with the *smallest* amount seen. A one-time
  effect — a class loaded, a JIT event (§25.2) — makes one round dirty, not all of them; a per-call
  allocation shows up as a steady `best`. The message gives bytes per call, which usually names the
  culprit: 16 is a boxed number or a small lambda, 24 a three-element varargs array (§3.1).
- **Why rounds and not one long warm-up?** How long the JIT needs depends on the machine. A fixed
  warm-up long enough for a slow CI runner wastes time on a fast laptop, and one tuned for the
  laptop makes the test flaky on the runner. Rounds stop as soon as the answer is known.
- **`InterruptedException`** restores the interrupt flag and stops trying (the test then fails with
  what it has), the standard way to respect an interrupt in code that cannot throw it.

### 12.4 Two helpers for monitors

```java
    /** Returns the bytes the calling thread has allocated so far (for custom measurements). */
    public static long threadAllocatedBytes() {
        return THREADS.getCurrentThreadAllocatedBytes();
    }

    /**
     * Returns the bytes another thread has allocated so far. Sampling from a separate monitor
     * thread keeps the measurement code out of the loop being measured (no JIT effects there).
     */
    public static long threadAllocatedBytes(Thread thread) {
        return THREADS.getThreadAllocatedBytes(thread.threadId());
    }
```

The second is what the whole-loop soak test uses (§24.6): a third thread reads the game and main
threads' counters, so no measuring code runs inside the loops being measured.

### 12.5 What it proves, and what it does not

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

The lambda captures `t`, `e` and `jobs`, so it is an object — created once, before the helper is
called. Calling `body.run()` allocates nothing: the call site in the helper sees one class per test,
so it is monomorphic (§2.4). A transform update is heavier than a map lookup, so this test asks for
fewer, smaller rounds (10 of 200).

Three limits, each of which has bitten someone:

- **It measures the calling thread only.** `JobSystemTest.dispatchDoesNotAllocate` proves that
  *dispatching* a `parallelFor` allocates nothing; what the workers allocate inside the kernel is
  invisible to it. The soak test and the perf capture read the workers' counters.
- **It proves "allocates nothing once compiled", not "never allocates".** Code that is clean only
  thanks to escape analysis passes. Measured for this chapter: `HashMap<Integer, Integer>.get` with
  a key of 1,000,000 under exactly this algorithm printed rounds of `64000 64000 64000 0` — 16 bytes
  per call (one `Integer`) for three rounds, then nothing, and the test passed. With
  `-XX:-DoEscapeAnalysis`, or C1 only (`-XX:TieredStopAtLevel=1`), it allocated 64,000 bytes in all
  25 rounds and failed. And it failed the same way, with the JIT fully on, when the program was run
  through the source launcher (`java Boxed.java`) instead of compiled first: the launcher's
  in-process `javac` had polluted `HashMap.getNode`'s profile, C2 left one `equals` call on a path
  our calls never take, and the box escaped into it. `IntIntMap.get` passed in round 0, interpreted.
  Engine code aims for the second kind (§19); exercise 1 repeats both experiments and reads the
  inlining log.
- **It tests the shape you wrote, not the shape the game has.** In the game, the same method may be
  called from a bigger method where it is not inlined, and escape analysis then fails. That is
  another reason to aim for code that allocates nothing even uncompiled.

78 test classes in 20 modules call it (counted on 2026-10-14).

---

## 13. Step 6: the single-producer, single-consumer ring

### 13.1 The problem, and the shape of the answer

The main thread turns every SDL event into a record of 8 `int`s for the game thread; the game
thread turns every request it cannot make itself (a cursor change, a window mode) into a record of 6
`int`s for the main thread ([chapter 01, on the main-thread command
queue](01-architecture.md#16-step-7-the-main-thread-command-queue)). Each channel has exactly one
writer and one reader. They need:

- no lock: neither thread may wait for the other in the middle of a frame;
- no allocation: hundreds of records a second, forever;
- whole records: a reader must never see five of a record's eight `int`s.

**The shape.** An `int[]` whose length is a power of two, and two counters that only ever grow:
`tail`, the number of `int`s ever written (only the producer changes it), and `head`, the number
ever read (only the consumer changes it). The slot of counter value $c$ is
$c \bmod \text{capacity}$, computed as `c & mask` with `mask = capacity - 1` (L00 §6.5). At any
moment

$$\text{used} = \text{tail} - \text{head}, \qquad \text{free} = \text{capacity} - \text{used}.$$

**Alternatives, and why not.**

| Alternative | Why not |
|---|---|
| `ArrayBlockingQueue<Integer>` | a lock per operation, and a boxed `Integer` per value |
| `ConcurrentLinkedQueue<int[]>` | a node object per record |
| one shared `AtomicInteger size` | both threads write the same variable on every record: the cache line ping-pongs (§7.3) |
| counters that wrap at the capacity | "full" and "empty" both look like `head == tail`; you need a spare slot or a third counter |

With counters that grow forever and one writer each, every question is a subtraction and every
variable has one owner.

### 13.2 The fields, padded

Start
[`SpscIntRing.java`](../../modules/core/src/main/java/dev/jeddy/core/concurrent/SpscIntRing.java) in
`dev.jeddy.core.concurrent`, before the class itself:

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

Why five classes for four fields? Because of false sharing (§7.3). The consumer writes `head` on
every poll and the producer writes `tail` on every offer; if they shared a cache line, each write
would steal the line from the other core. The fix is to put 64 bytes (eight `long`s) of unused
fields between them. But the JVM is free to order the fields *within* one class as it likes — it
sorts them by size to avoid gaps — so eight padding fields declared next to `head` in the same class
could end up anywhere. Fields of *different* classes in a hierarchy are laid out superclass first,
so a chain of classes forces the order: pad, consumer fields, pad, producer fields, pad. The classes
are `abstract` and package-private: nothing else should ever extend or name them.

Does it work? A program run for this chapter read every field's offset with the JDK's internal
`Unsafe.objectFieldOffset` (`--add-exports java.base/jdk.internal.misc=ALL-UNNAMED`; the program is
exercise 15). On JDK 27 with its default compact object headers:

```
offset  field
   0    object header (8 bytes)
   8    p00 ... p07            64 bytes of padding
  72    head                   ┐ the consumer's line
  80    tailCache              ┘
  88    p10 ... p17            64 bytes of padding
 152    tail                   ┐ the producer's line
 160    headCache              ┘
 168    p20 ... p27            64 bytes of padding
 232    mask                   read-only after construction
 236    buffer (a reference)   read-only after construction
 240    end of the object
```

`head` and `tail` are 80 bytes apart, so they can never share a 64-byte line, wherever the object
starts. Each thread's counter shares its line only with its own cache field. The 64 bytes before
`head` keep the previous object in memory (and this object's header) off the consumer's line, and
the 64 bytes after `headCache` keep the read-only fields and the next object off the producer's.

One detail of the measurement corrects the comment at the top of the file. With the old 12-byte
headers (`-XX:-UseCompactObjectHeaders`) the first `long` cannot start before offset 16, leaving a
4-byte hole at offset 12, and the JVM put `mask` — a field of the *subclass* — into that hole.
So the JVM does place subclass fields before superclass fields when a superclass leaves a gap. The
padding still works, because the pads are all `long`s and leave no gap *between* themselves for
anything to fall into; the comment's "never reorders across classes" is simply stronger than what
the JVM promises.

### 13.3 The class, the handles and the constructor

```java
public final class SpscIntRing extends SpscIntPad2 {
    private static final VarHandle HEAD;
    private static final VarHandle TAIL;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            HEAD = lookup.findVarHandle(SpscIntConsumer.class, "head", long.class);
            TAIL = lookup.findVarHandle(SpscIntProducer.class, "tail", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final int[] buffer;
    private final int mask;

    /**
     * Creates a ring.
     *
     * @param capacity number of ints; must be a power of two
     */
    public SpscIntRing(int capacity) {
        if (capacity < 2 || Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("capacity must be a power of two >= 2: " + capacity);
        }
        buffer = new int[capacity];
        mask = capacity - 1;
    }
```

- **Two `VarHandle`s in `static final` fields** (§2.5, §7.2): access modes on the plain `long`
  fields, compiled to plain instructions. `findVarHandle` is given the class that *declares* the
  field (`SpscIntConsumer` for `head`); the lookup comes from `SpscIntRing`, in the same package,
  so it may see the package-private fields.
- **The `try`/`catch`** exists because `findVarHandle` is declared to throw; the fields are right
  there, so it never does. If it ever did, `ExceptionInInitializerError` makes the class unusable
  with a clear cause.
- **`Integer.bitCount(capacity) != 1`**: a power of two has exactly one bit set. (L00 §6.5's
  `(x & (x - 1)) == 0` is the other common test.) At least 2, so `mask` is never 0.
- **`buffer` and `mask` are `final`**: written once, in the constructor, and safely visible to both
  threads after the ring is handed to them (final-field semantics, Java Language Specification
  §17.5).

### 13.4 `offer`: one value

```java
    /** Producer: appends one value. Returns {@code false} if the ring is full. */
    public boolean offer(int value) {
        long t = tail;
        if (headCache <= t - buffer.length) {
            headCache = (long) HEAD.getAcquire(this);
            if (headCache <= t - buffer.length) {
                return false;
            }
        }
        buffer[(int) t & mask] = value;
        TAIL.setRelease(this, t + 1);
        return true;
    }
```

- **`long t = tail;`** — a plain read. Only this thread writes `tail`, so it always sees its own
  latest value.
- **The full test.** The ring is full when $\text{tail} - \text{head} \ge \text{capacity}$, that is
  when $\text{head} \le \text{tail} - \text{capacity}$, which is the condition written.
- **First with the cache.** `headCache` is the producer's last known value of `head`. The consumer
  only ever *increases* `head`, so the real `head` is at least `headCache`: if the ring has room
  according to the cache, it really has room. Only when the cache says "full" does the producer read
  the real `head`, the consumer's variable, on the consumer's cache line. Most offers therefore
  touch no line the other thread writes. (The same trick, "cached sequences", is at the heart of the
  LMAX Disruptor, listed in Further reading.)
- **`HEAD.getAcquire(this)`** — the producer is about to overwrite slots the consumer has finished
  reading. The consumer's reads came before its release write of `head` (§13.6); the acquire read
  orders them before the producer's next writes, so no slot is overwritten while it is still being
  read.
- **Write, then publish.** The value goes into the slot with a plain store; then `tail` advances with
  a **release** write, which orders the store before it (§7.2). On x86 both are plain `mov`s.

Worked example, capacity 4: `tail = 6`, `headCache = 2`. Test: $2 \le 6 - 4 = 2$, true: the cache
says full. Reload: the consumer has moved on, `head = 3`. Test: $3 \le 2$, false: room. The value
goes to slot $6 \mathbin{\&} 3 = 2$, and `tail` becomes 7.

### 13.5 `offer`: a whole record

```java
    /**
     * Producer: appends {@code n} values as one record. Returns {@code false}, writing nothing, if
     * they don't all fit.
     */
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

- **A record larger than the ring could never fit**, and a caller retrying in a loop would spin
  forever, so that is an error rather than `false`.
- **The fit test.** $n$ more values fit when $(\text{tail} - \text{head}) + n \le \text{capacity}$.
  The negation, "does not fit", rearranges to $\text{head} < \text{tail} + n - \text{capacity}$, and
  `wrap` is the right-hand side: the oldest counter value that must already have been read.
- **All or nothing.** The `n` values are written with plain stores, and only then does `tail` move
  by `n` in one release write. The consumer sees either the old `tail` (none of the record) or the
  new one (all of it): never half a record.
- **`(int) (t + i) & mask`.** A cast binds tighter than `&`, so this is `((int) (t + i)) & mask`:
  the cast keeps the low 32 bits of the `long`, and `& mask` keeps the low bits of those. Since the
  mask has fewer than 32 bits, the result is the same as `(t + i) & mask` computed in `long`.

Two worked examples, capacity 8, a record of $n = 6$, `tail = 10`:

- `head = 3`: seven `int`s are in use and one is free. `wrap` $= 10 + 6 - 8 = 8$; $3 < 8$, so the
  record does not fit, and `offer` returns `false` without writing anything.
- `head = 8`: two are in use and six are free. `wrap` is still 8; $8 < 8$ is false, so the record
  fits. Its values go to the slots of counters 10 to 15, masked with 7: slots 2, 3, 4, 5, 6 and 7;
  `tail` becomes 16.

### 13.6 `poll`: a whole record

```java
    /**
     * Consumer: removes {@code n} values into {@code dst[offset..]}. Returns {@code false}, reading
     * nothing, if fewer than {@code n} are available.
     */
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

The mirror image. A record is available when $\text{tail} \ge \text{head} + n$; the consumer checks
its cached `tail` first and reads the producer's real `tail` with an **acquire** read only when the
cache says "not enough". That acquire read is the other half of the producer's release write, and
together they form the happens-before edge of §7.1:

```
producer                                   consumer
  buffer[...] = record (plain stores)
  TAIL.setRelease(t + n)  ─────────────▶   TAIL.getAcquire() sees t + n
                                           dst[...] = buffer[...] (plain loads: the whole record)
                                           HEAD.setRelease(h + n)  ─────▶  producer's getAcquire(head)
```

Records are always polled with the same `n` they were offered with: the queue and the input ring
both use fixed record sizes, so the counters always move in whole records.

### 13.7 `drain` and `size`

```java
    /**
     * Consumer: removes up to {@code max} values into {@code dst[offset..]}. Returns how many were
     * removed.
     */
    public int drain(int[] dst, int offset, int max) {
        long h = head;
        long t = (long) TAIL.getAcquire(this);
        tailCache = t;
        int n = (int) Math.min(max, t - h);
        for (int i = 0; i < n; i++) {
            dst[offset + i] = buffer[(int) (h + i) & mask];
        }
        if (n > 0) {
            HEAD.setRelease(this, h + n);
        }
        return n;
    }
```

and, earlier in the file, right after `capacity()`:

```java
    /** Returns an estimate of the number of ints waiting. */
    public int size() {
        long t = (long) TAIL.getAcquire(this);
        long h = (long) HEAD.getAcquire(this);
        return (int) Math.max(0, Math.min(buffer.length, t - h));
    }
```

- **`drain`** takes everything available up to `max`, for streams of single values. It always reads
  the real `tail` (it wants everything there is) and refreshes the cache while at it. **`if (n >
  0)`** skips the release write when nothing was taken: writing `head` would pull its cache line to
  this core for nothing, and the producer would then miss on it.
- **`size`** may be called from either thread, so it reads both counters with acquire. The two reads
  happen at different moments, so the answer is an estimate; if `head` moved past the `tail` read
  first, $t - h$ could even be negative, hence the clamp to $[0, \text{capacity}]$.

### 13.8 Why the counters never wrap, and why it is correct

`head` and `tail` are `long`s that only grow. At a billion `int`s per second, far more than the
engine ever sends, they reach $2^{63} \approx 9.22 \times 10^{18}$ after $9.22 \times 10^{9}$
seconds, about 292 years. So "full" and "empty" are plain subtractions that never meet an overflow
(L00 §4.3).

The correctness argument has four parts:

1. **One writer per counter.** Only the producer writes `tail` and `headCache`, only the consumer
   `head` and `tailCache`. No variable is written by two threads, so no compare-and-set is needed.
2. **The producer never overwrites an unread slot.** It writes slots
   $[\text{tail}, \text{tail} + n)$ only after seeing
   $\text{head} \ge \text{tail} + n - \text{capacity}$, so those slots were last used for counter
   values below `head`: already read.
3. **The consumer never reads an unwritten slot.** It reads $[\text{head}, \text{head} + n)$ only
   after seeing $\text{tail} \ge \text{head} + n$ through an acquire read, and every write below
   that `tail` happened-before the release write that set it.
4. **Records are whole**, because `tail` moves by a record only after all of its values are written.

One cost remains that padding cannot remove: when the ring is nearly empty, the producer writes the
slots right next to those the consumer reads, in the same cache lines of `buffer`. Records of 6 or 8
`int`s (24 or 32 bytes) at a time amortize that over many values.

`ConcurrencyTest` checks all of it (§24.1). The ring's users are the input ring, created in
`JeddyApp` (main → game, 8-`int` records), and `MainThreadQueue` (game → main, 6-`int` records),
both in [chapter 01, on the main-thread command
queue](01-architecture.md#16-step-7-the-main-thread-command-queue).

---

## 14. Step 7: the multi-producer, multi-consumer queue

### 14.1 The problem

The job system's queue of ready jobs has many writers (any thread that submits work: the game
thread, the main thread, a worker inside a job) and many readers (every worker, and any thread
helping while it waits). The asset manager hands slots from its IO threads to its decode jobs to the
main thread's upload step the same way (decision **D26**). The SPSC ring's trick — one writer per
counter — no longer works.

| Alternative | Why not |
|---|---|
| `ConcurrentLinkedQueue<Integer>` | a node and a box per offer |
| `ArrayBlockingQueue<Integer>` | a lock, and boxes |
| a CAS on a shared `tail`, then write the slot | a consumer can see `tail` advanced and read the slot *before* the winning producer has written it: the position says "filled" too early |

The third row is the real problem: claiming a position and filling the slot are two steps. Dmitry
Vyukov's bounded MPMC queue (1024cores, listed in Further reading) solves it with a **sequence
number per slot** that says, for each slot, whose turn it is.

### 14.2 The fields

[`MpmcIntQueue`](../../modules/core/src/main/java/dev/jeddy/core/concurrent/MpmcIntQueue.java) pads
its two positions exactly like the ring (§13.2) — measured offsets with compact headers:
`enqueuePos` at 72, `dequeuePos` at 144, 72 bytes apart, 64 bytes of padding around each:

```java
abstract class MpmcPad0 {
    long p00, p01, p02, p03, p04, p05, p06, p07;
}

abstract class MpmcEnqueue extends MpmcPad0 {
    long enqueuePos;
}

abstract class MpmcPad1 extends MpmcEnqueue {
    long p10, p11, p12, p13, p14, p15, p16, p17;
}

abstract class MpmcDequeue extends MpmcPad1 {
    long dequeuePos;
}

abstract class MpmcPad2 extends MpmcDequeue {
    long p20, p21, p22, p23, p24, p25, p26, p27;
}

public final class MpmcIntQueue extends MpmcPad2 {
    /** Returned by {@link #poll()} when the queue is empty. */
    public static final int EMPTY = -1;

    private static final VarHandle ENQUEUE;
    private static final VarHandle DEQUEUE;
    private static final VarHandle SEQ = MethodHandles.arrayElementVarHandle(long[].class);

    // static block: ENQUEUE and DEQUEUE found with findVarHandle, as in §13.3

    private final int[] buffer;
    private final long[] sequence;
    private final int mask;

    public MpmcIntQueue(int capacity) {
        if (capacity < 2 || Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("capacity must be a power of two >= 2: " + capacity);
        }
        buffer = new int[capacity];
        sequence = new long[capacity];
        for (int i = 0; i < capacity; i++) {
            sequence[i] = i;
        }
        mask = capacity - 1;
        VarHandle.releaseFence();
    }
```

(The class's and the constructor's Javadoc are left out; the class Javadoc names the algorithm,
"Dmitry Vyukov's algorithm: one sequence number per slot, CAS on the positions".)

- **`EMPTY = -1`** is what `poll` returns when there is nothing; therefore the queue only accepts
  values ≥ 0 (job handles and slot numbers are never negative).
- **`SEQ`** is a `VarHandle` on the *elements* of any `long[]`: `SEQ.getAcquire(sequence, index)` is
  an acquire read of `sequence[index]`.
- **`sequence[i] = i`**: slot $i$ starts free for the producer whose position will be $i$ (§14.3).
- **`VarHandle.releaseFence()`** orders all the constructor's writes before anything that follows,
  including the publication of the queue to other threads. Final-field semantics (JLS §17.5) already
  guarantee that a thread which sees the queue through any reference sees the arrays as the
  constructor left them, so the fence is belt and braces.

### 14.3 The protocol

Number every offer and every poll by its position: the $k$-th offer ever made has position $k$,
and so does the $k$-th poll. Position $p$ uses slot $p \mathbin{\&} \text{mask}$. With capacity $C$,
slot $s$ is used by positions $s, s + C, s + 2C, \dots$ — one per lap. The slot's sequence number
says which step of which lap it is waiting for:

| `sequence[s]` | Meaning |
|---|---|
| $p$ | free: waiting for the producer with position $p$ |
| $p + 1$ | filled by the producer with position $p$: waiting for the consumer with position $p$ |
| $p + C$ | read by consumer $p$: free for the producer one lap later, position $p + C$ |

### 14.4 `offer`

```java
    /** Appends {@code value} (must be &gt;= 0). Returns {@code false} if the queue is full. */
    public boolean offer(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("negative value " + value);
        }
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

- **Read the next position** with an opaque read (§7.2): fresh every time, no ordering needed,
  because the compare-and-set below validates it.
- **Ask the slot.** `dif = seq - pos` compares the slot's sequence with the position:
  - **`dif == 0`**: the slot is free for exactly this position. Try to **claim** the position with a
    compare-and-set, `enqueuePos: pos → pos + 1`. Only one producer can win it. The winner writes the
    value with a plain store, then publishes it with a **release** write of the sequence, `pos + 1`:
    "filled, for consumer `pos`". A producer that lost the race re-reads the position and tries again.
  - **`dif < 0`**: the slot still holds the value of the previous lap, not yet read: the queue is
    **full**. Return `false`.
  - **`dif > 0`**: another producer already claimed this position and filled the slot. This
    producer's `pos` is stale; re-read it.
- **`weakCompareAndSet`** may fail even when the value matched (on some processors a "spurious"
  failure). In a retry loop that costs nothing, and on x86-64 it is the same `lock cmpxchg` as the
  strong version.

### 14.5 `poll`

```java
    /** Removes and returns the oldest value, or {@link #EMPTY}. */
    public int poll() {
        long pos = (long) DEQUEUE.getOpaque(this);
        while (true) {
            int index = (int) pos & mask;
            long seq = (long) SEQ.getAcquire(sequence, index);
            long dif = seq - (pos + 1);
            if (dif == 0) {
                if (DEQUEUE.weakCompareAndSet(this, pos, pos + 1)) {
                    int value = buffer[index];
                    SEQ.setRelease(sequence, index, pos + mask + 1);
                    return value;
                }
                pos = (long) DEQUEUE.getOpaque(this);
            } else if (dif < 0) {
                return EMPTY;
            } else {
                pos = (long) DEQUEUE.getOpaque(this);
            }
        }
    }
```

The same three cases, one step later. A consumer at position `pos` wants the slot filled for it,
sequence `pos + 1`. If it is, it claims the position with a compare-and-set, reads the value, and
frees the slot for the producer one lap later by writing `pos + mask + 1` = `pos + C`. A sequence
*behind* that (`dif < 0`) means the producer for this position has not filled the slot yet: the
queue is **empty**.

### 14.6 A walk-through

Capacity 4 (`mask = 3`). Start: `sequence = [0, 1, 2, 3]`, both positions 0.

| Step | Position read | Slot | `seq` | `dif` | Result | `sequence` after | `enq`, `deq` |
|---|---|---|---|---|---|---|---|
| `offer(7)` | 0 | 0 | 0 | 0 | claim, write 7 | [1, 1, 2, 3] | 1, 0 |
| `offer(8)` | 1 | 1 | 1 | 0 | claim, write 8 | [1, 2, 2, 3] | 2, 0 |
| `poll()` | 0 | 0 | 1 | $1 - 1 = 0$ | claim, return 7 | [4, 2, 2, 3] | 2, 1 |
| `offer(9)`, `offer(10)` | 2, 3 | 2, 3 | 2, 3 | 0 | claim, write | [4, 2, 3, 4] | 4, 1 |
| `offer(11)` | 4 | 0 | 4 | 0 | slot 0's second lap: write 11 | [5, 2, 3, 4] | 5, 1 |
| `offer(12)` | 5 | 1 | 2 | $2 - 5 = -3$ | **full**: slot 1 still holds 8 | unchanged | 5, 1 |
| `poll()` | 1 | 1 | 2 | $2 - 2 = 0$ | return 8 | [5, 5, 3, 4] | 5, 2 |

And empty: once every value is read, `deq` equals `enq`, say both are $p$; slot $p \mathbin{\&} 3$
then holds sequence $p$ (free for producer $p$), and a poll computes
$\text{dif} = p - (p + 1) = -1$: `EMPTY`.

### 14.7 Why it is correct, and what it costs

- **Each position goes to one thread.** The compare-and-set on `enqueuePos` hands each producer
  position to exactly one producer, and likewise for consumers. Two threads never write the same
  slot in the same lap.
- **The value travels with a release/acquire edge.** The producer's plain store of the value comes
  before its release write of `seq = pos + 1`; the consumer acquires that `seq` before its plain
  read of the value. The consumer's read comes before its release write of `seq = pos + C`, which
  the next lap's producer acquires before overwriting. Each slot's sequence number is a tiny SPSC
  handoff of its own.
- **The contended point is the position.** All producers compare-and-set the same `enqueuePos`, so
  its cache line moves between their cores; the padding keeps that line away from the consumers'
  one.
- **Neighbouring slots share lines.** Eight sequence numbers fit in a 64-byte line, sixteen values in
  another, so producers and consumers working on nearby slots touch the same lines. For job handles,
  a few per frame, that is irrelevant.
- **It is not strictly lock-free.** A producer that won its compare-and-set and is then paused by
  the operating system before its release write leaves a slot claimed but not filled. Consumers that
  reach that slot see `dif < 0` and report `EMPTY`, even if later slots are filled, until the
  producer resumes. In the job system this delays work but never loses it: a submitter that waits
  for its job runs its chunks itself ([chapter 01, on help while
  waiting](01-architecture.md#136-isdone-and-wait-help-while-waiting)).
- **`size()` and `isEmpty()`** read the two positions with opaque reads and subtract: an estimate,
  clamped to $[0, C]$, used by a worker deciding whether to go to sleep.

The queue's users: the job system's `freeSlots` and `ready` queues ([chapter 01, on the job system's
fields](01-architecture.md#125-the-systems-fields-and-constructor)) and the asset manager's six
queues between its loading stages (D26).

---

## 15. Step 8: a padded counter and the long ring

### 15.1 `PaddedAtomicLong`

Sometimes the shared thing is a single counter that several threads update often — the number of
items processed, a frame statistic. An `AtomicLong` works, but it is a 16-byte object (§3.1) that
the allocator may place in the same line as some other hot object.
[`PaddedAtomicLong`](../../modules/core/src/main/java/dev/jeddy/core/concurrent/PaddedAtomicLong.java)
puts one `long` alone on its line, with the same three-class trick:

```java
abstract class PaddedLongPad0 {
    long p00, p01, p02, p03, p04, p05, p06, p07;
}

abstract class PaddedLongValue extends PaddedLongPad0 {
    long value;
}

abstract class PaddedLongPad1 extends PaddedLongValue {
    long p10, p11, p12, p13, p14, p15, p16, p17;
}

public final class PaddedAtomicLong extends PaddedLongPad1 {
    private static final VarHandle VALUE;
    // static block: VALUE = findVarHandle(PaddedLongValue.class, "value", long.class)

    /** Creates a counter starting at 0. */
    public PaddedAtomicLong() {}

    /** Creates a counter. */
    public PaddedAtomicLong(long initial) {
        VALUE.setVolatile(this, initial);
    }

    /** Volatile read. */
    public long get() {
        return (long) VALUE.getVolatile(this);
    }

    /** Acquire read. */
    public long getAcquire() {
        return (long) VALUE.getAcquire(this);
    }

    /** Volatile write. */
    public void set(long v) {
        VALUE.setVolatile(this, v);
    }

    /** Release write. */
    public void setRelease(long v) {
        VALUE.setRelease(this, v);
    }

    /** Atomically adds {@code delta}; returns the previous value. */
    public long getAndAdd(long delta) {
        return (long) VALUE.getAndAdd(this, delta);
    }

    /** Atomically adds {@code delta}; returns the new value. */
    public long addAndGet(long delta) {
        return (long) VALUE.getAndAdd(this, delta) + delta;
    }

    /** Atomically increments; returns the new value. */
    public long incrementAndGet() {
        return addAndGet(1);
    }

    /** Atomically sets to {@code update} if the value is {@code expected}. */
    public boolean compareAndSet(long expected, long update) {
        return VALUE.compareAndSet(this, expected, update);
    }
}
```

(The class Javadoc is left out.) Measured offsets (compact headers): `value` at 72, with 64 bytes of
padding on each side (8–72 and 80–144). Each method is one access mode of §7.2. `addAndGet` shows a
small idiom: the hardware's atomic add (`lock xadd`) returns the *old* value, so the new one is the
old one plus `delta`, computed afterwards in a register — still one atomic instruction. The
constructor uses `setVolatile` so that the initial value is published like any later write.

Today only `ConcurrencyTest.paddedAtomicLong` uses it; it is the ready-made answer when profiling
shows a contended counter (DESIGN §4.5: "Hot counters are manually padded to avoid false sharing").

### 15.2 `SpscLongRing`

[`SpscLongRing`](../../modules/core/src/main/java/dev/jeddy/core/concurrent/SpscLongRing.java) is
§13's ring with `long` elements: the same padding classes (renamed), the same `offer`, `poll`,
`drain` and `size`, line for line, with `long[]` buffers. Its Javadoc names "profiler records and
timestamps" as its use, but the profiler keeps per-thread buffers of its own, and today only
`ConcurrencyTest` uses the long ring. Exercise 11 benchmarks it with and without its padding.

---

## 16. Step 9: the benchmark harness and the nightly check

### 16.1 JMH, wired by hand

Gradle has third-party plugins that run JMH. Jeddy uses none: when the build moved to Gradle 9, a
plugin that broke with it would have blocked the build, so the `bench` project wires JMH by hand in
about thirty lines (decision **D14**). The convention plugin is
[`jeddy.jmh.gradle.kts`](../../build-logic/src/main/kotlin/jeddy.jmh.gradle.kts):

```kotlin
plugins {
    java
    id("jeddy.java-base")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "implementation"(catalog.findLibrary("jmh-core").get())
    "annotationProcessor"(catalog.findLibrary("jmh-annprocess").get())
    // FFM and GL benchmarks load the native libraries of the platform running the build.
    "runtimeOnly"(project(":natives-" + dev.jeddy.gradle.JeddyPlatforms.current()))
}

// JMH-generated sources are not ours to keep warning-free, and FFM benchmarks call restricted
// methods by design.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.removeAll { it == "-Werror" }
    options.compilerArgs.add("-Xlint:-restricted")
}

tasks.register<JavaExec>("jmh") {
    group = "benchmark"
    description = "Runs JMH benchmarks. Filter with -Pjmh.includes=<regex>, pass extra options with -Pjmh.args."
    classpath = project.extensions.getByType<SourceSetContainer>().getByName("main").runtimeClasspath
    mainClass = "org.openjdk.jmh.Main"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    outputs.upToDateWhen { false }

    val jmhArguments = project.objects.newInstance<JmhArguments>()
    jmhArguments.includes = project.providers.gradleProperty("jmh.includes").orElse(".*")
    jmhArguments.extraArgs = project.providers.gradleProperty("jmh.args").orElse("")
    jmhArguments.resultsFile = project.layout.buildDirectory.file("reports/jmh/results.json")
    argumentProviders.add(jmhArguments)
}
```

- **`jmh-annprocess`** is an annotation processor: at compile time it reads every `@Benchmark` and
  generates the harness classes that run it in a measured loop, with blackholes and timers. That is
  why the generated sources exist, and why `-Werror` is removed for this project only.
- **The natives** go on the runtime class path, so `GlBench`, `ZstdBench` and the others can load
  SDL and zstd; `FfmBench` needs only the C library.
- **The `jmh` task** runs `org.openjdk.jmh.Main` with the benchmarks on the class path, as code on
  the class path needs: `--enable-native-access=ALL-UNNAMED` (§9.4). JMH starts each fork with the
  JVM options of this launching JVM (§6.2), which here are only that one: benchmarks run on the
  JDK's default collector (G1), not with the launcher flags of §18.1. That is fine for timing a
  method in isolation; the perf capture is where the real configuration is measured (§17).
- **`outputs.upToDateWhen { false }`**: a benchmark run is never "up to date"; Gradle must not skip it
  because nothing changed.

### 16.2 The command line

The task's program arguments come from a small class,
[`JmhArguments`](../../build-logic/src/main/kotlin/dev/jeddy/gradle/JmhArguments.kt):

```kotlin
/**
 * Command line for `org.openjdk.jmh.Main`: include regex, extra options, JSON result file.
 * A class instead of a lambda so the configuration cache never captures the script object.
 */
abstract class JmhArguments : CommandLineArgumentProvider {
    @get:Input
    abstract val includes: Property<String>

    @get:Input
    abstract val extraArgs: Property<String>

    @get:OutputFile
    abstract val resultsFile: RegularFileProperty

    override fun asArguments(): Iterable<String> {
        val out = resultsFile.get().asFile
        out.parentFile.mkdirs()
        return listOf(includes.get()) +
            extraArgs.get().split(' ').filter { it.isNotBlank() } +
            listOf("-rf", "json", "-rff", out.absolutePath)
    }
}
```

The include pattern (a regular expression over benchmark names, `.*` by default), any extra JMH
options split on spaces, and `-rf json -rff <file>`: write the results as JSON to
`bench/build/reports/jmh/results.json`. The Javadoc's reason for a class rather than a lambda:
Gradle's **configuration cache** serializes the task's configuration between builds, and a lambda
written in a build script would capture the script object, which cannot be serialized.

```
./gradlew :bench:jmh -Pjmh.includes=FfmBench
./gradlew :bench:jmh -Pjmh.includes=JobBench "-Pjmh.args=-prof gc"
./gradlew :bench:jmh -Pjmh.includes=JobBench "-Pjmh.args=-f 1 -wi 2 -i 3"
```

The third line overrides the benchmark's annotations: one fork, two warm-up iterations, three
measured ones — a quick look rather than a measurement.

### 16.3 The nightly check

Every night, CI runs every benchmark with `-prof gc` on a GitHub Linux runner and passes the results
to [`.github/scripts/jmh-check.py`](../../.github/scripts/jmh-check.py) (decision **D77**). The
script does two checks.

**Regressions.** Each benchmark's score is compared with the committed baseline,
[`bench/baseline/linux-x64.json`](../../bench/baseline/linux-x64.json):

```python
        limit = base.get("thresholds", {}).get(k, default)
        change = (score - b["score"]) / b["score"]
        worse = change if lower_is_better(unit) else -change
        line = f"{k}: {score:.4g} {unit} vs {b['score']:.4g} ({change:+.0%})"
        if worse > limit:
            failures.append("regression " + line)
        elif abs(change) > limit:
            notes.append("improved " + line)
```

`change` is the relative difference; for a time per operation (`ns/op`) a higher number is worse,
for a throughput (`ops/s`) a lower one is, and `worse` folds both into "positive means slower". The
default threshold is 25 %: shared CI runners are noisy, and a tighter limit would fail on noise. A
benchmark that got much *faster* is reported too — it usually means the baseline is out of date, or
that the benchmark stopped measuring what it should (§6.2). The baseline itself is written by a
manual run with `--write-baseline`.

**The allocation sweep.** A list of hot-path benchmarks must allocate nothing:

```python
# Hot paths: zero allocation per operation (DESIGN §4.2).
ZERO_ALLOC = [
    r"AudioBench\.", r"DrawBench\.cull", r"JobBench\.", r"MathBench\.", r"PhysicsBench\.",
    r"TransformBench\.", r"FfmBench\.(abs|mat4)", r"CollectionsBench\.(intIntMapGet|radixSort16k)",
    r"AssetBench\.(pakFind|vfsSize)", r"BaselineBench\.", r"AnimationBench\.",
]
NOISE = 16.0  # B/op
BACKGROUND = 64.0  # B per ms of operation time
```

Why not simply "0 B/op"? Because `gc.alloc.rate.norm` divides the allocation of *every thread* in
the fork by the number of operations (§6.3). JMH's own threads and the JVM's background threads
allocate at some rate $r$ bytes per millisecond whatever the benchmark does, so a benchmark whose
operation takes $t_{\text{op}}$ milliseconds is charged about $r \cdot t_{\text{op}}$ bytes per
operation it never allocated. The first nightly measured $r$ between 3 and 29 B per ms, constant
within each fork. So a hot path fails when

$$\text{bytes per op} > 16\ \text{B} + 64\ \tfrac{\text{B}}{\text{ms}} \cdot t_{\text{op}},$$

with 16 bytes for rounding noise and 64 B/ms, more than twice the worst rate seen, for the
background. `op_millis` converts the score to milliseconds per operation from whatever unit JMH
used:

```python
def op_millis(r):
    """Time of one operation in ms, from the primary score (average time or throughput)."""
    score, unit = r["primaryMetric"]["score"], r["primaryMetric"]["scoreUnit"]
    per_op = {"ns/op": 1e-6, "us/op": 1e-3, "ms/op": 1.0, "s/op": 1e3}
    ops_per_ms = {"ops/ns": 1e6, "ops/us": 1e3, "ops/ms": 1.0, "ops/s": 1e-3}
    if unit in per_op:
        return score * per_op[unit]
    if unit in ops_per_ms and score > 0:
        return 1.0 / (score * ops_per_ms[unit])
    return 0.0
```

**Two worked examples.**

- `TransformBench.allDirty[parallel=false]`, baseline 1,822 µs per operation:
  $t_{\text{op}} = 1.822$ ms, so the allowance is $16 + 64 \times 1.822 = 132.6$ B/op. The benchmark
  updates 100,000 transforms; one object per transform would be at least
  $16 \times 100{,}000 = 1{,}600{,}000$ B/op, four orders of magnitude over. The tolerance hides
  background noise, not real allocation.
- `FfmBench.absRegular`, 3.62 ns: $t_{\text{op}} = 3.62 \times 10^{-6}$ ms, so the allowance is
  16.0002 B/op, effectively the noise floor. A downcall that kept one `MemorySegment` per call
  (32 B/op, §4.10) would fail.

Hot paths above the allowance fail the nightly; between 16 bytes and the allowance they are noted as
"background allocation"; other benchmarks are reported but allowed to allocate (`hashMapGet` boxes,
by design). The exact, per-thread proof stays the job of `Allocations.assertNoAllocation`, as the
script's own docstring says. (The docstring calls them "the @NoAllocation tests"; there is no such
annotation in the repository, the tests simply call the helper.)

---

## 17. Step 10: the perf capture

Unit tests prove pieces; JMH times them in isolation. The final judge is the game. The **perf
capture** (DESIGN §13.3, task M10-05) runs the iso demo's scripted performance scene — 300 agents
pathing, avoiding and fighting, fog of war, a fireball every 1.5 seconds, 16,000 extra static props
and 256 point lights, an orbiting camera — and measures it frame by frame against the budgets of
DESIGN §14. Three classes in
[`samples/iso-demo`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso) do the measuring.

### 17.1 The run

```
./gradlew :iso-demo:run --args="--headless --perf 120 --warmup 30 --alloc-probe --report build/perf --fail-on alloc,flags"
```

That is CI's command on every push (it also adds GC and class-load logs through `-Pjeddy.jvmArgs`).
The options, from
[`DemoOptions`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/DemoOptions.java):

| Option | Meaning |
|---|---|
| `--perf SECONDS` | play the scripted scene, measure for this long, write the report, quit |
| `--warmup SECONDS` | time after loading before measuring (default 10) |
| `--warmup-frames N` | also wait this many frames (default 600) |
| `--alloc-probe` | attribute the game thread's allocation to systems (§17.5) |
| `--report DIR` | where `perf.json` and `perf.md` go (default `build/perf`) |
| `--fail-on KEYS` | exit with code 4 if one of these budgets fails |
| `--headless` | no window, no GPU: CPU-only meshes, the game thread alone, paced like a 60 Hz display |

The measured window opens only when three warm-up conditions all hold, from `DemoGame.timers`:

```java
            // Warm up by time, frames and gameplay (the first fireballs and their sounds link
            // code paths that then stay linked).
            if (!measuring
                    && since >= options.warmupSeconds
                    && framesSinceReady >= options.warmupFrames
                    && spells.casts >= 4
                    && measureStart == 0) {
                LOG.info("perf: measuring for " + (long) options.perfSeconds + " s");
                measuring = true;
                measureStart = time;
                perf.instances = instanceCount;
                perf.begin();
                if (options.allocProbe) {
                    probe.start();
                }
            } else if (measuring && time - measureStart >= options.perfSeconds) {
                // ... close the window (perf.end()), hand over the probe's results, quit
            }
```

Time alone is not enough: a slow machine may have run few frames, and code that runs only when a
fireball explodes is linked (§2.5) at the first explosion. So the window waits for seconds, frames
*and* four fireballs (decision **D66**).

### 17.2 `PerfCapture`: recording a frame without disturbing it

[`PerfCapture`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/PerfCapture.java) keeps
one primitive array per measured quantity, sized once: `IsoDemo` creates it with a capacity of
$2^{18} = 262{,}144$ frames for a perf run (a little over 72 minutes at 60 frames per second), and 1
otherwise. Among them:

```java
    // Game thread.
    private final long[] gameNanos;
    private final long[] animationNanos;
    private final long[] uiNanos;
    private final long[] behaviourNanos;
    private int gameFrames;
    private final long[] gameAllocated;
    private final long[] workerAllocated;
    private final long[] gameStamp;
    private long[] workerIds = new long[0];
```

**Opening the window** is a cold path, and allocates freely:

```java
    void begin() {
        List<Long> workers = new ArrayList<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().startsWith("jeddy-worker")) {
                workers.add(t.threadId());
            }
        }
        workerIds = new long[workers.size()];
        for (int i = 0; i < workerIds.length; i++) {
            workerIds[i] = workers.get(i);
        }
        long[] gc = gc();
        // ... the GC counters at the start of the window
        workerSum(); // links the counter calls before the first recorded frame
        currentThreadAllocated();
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
        beginEpochMillis = System.currentTimeMillis();
        startNanos = System.nanoTime();
        uptimeOriginNanos = startNanos - uptime * 1_000_000L;
    }
```

- **The job workers' thread ids** are collected once, by name (`jeddy-worker-0`, …), into a `long[]`.
- **`workerSum(); currentThreadAllocated();`** are called once and their results thrown away: the
  first call of a method links what it uses (§2.5), and that must happen before the first recorded
  frame, not in it.
- **`uptimeOriginNanos`** relates the two clocks of the report. Frames are stamped with
  `System.nanoTime()`; the JIT log is stamped with the JVM's uptime in milliseconds. Subtracting the
  uptime from "now" gives the `nanoTime` value at which the JVM started, so any stamp converts to
  uptime: $\text{uptime ms} = (\text{stamp} - \text{origin}) / 10^6$ (`uptimeMillis`).

**Recording a game frame** runs once per frame, at the end of `extract`, and allocates nothing. Here
are `recordGame` and the three helpers it uses; in the file `workerSum` comes right after `begin`,
and `THREADS` and `currentThreadAllocated` after `recordMain`:

```java
    void recordGame(
            long nanos,
            int frameInstances,
            int frameLights,
            long animation,
            long ui,
            long behaviours) {
        if (gameFrames < capacity) {
            gameNanos[gameFrames] = nanos;
            animationNanos[gameFrames] = animation;
            uiNanos[gameFrames] = ui;
            behaviourNanos[gameFrames] = behaviours;
            gameAllocated[gameFrames] = currentThreadAllocated();
            workerAllocated[gameFrames] = workerSum();
            gameStamp[gameFrames] = System.nanoTime();
            gameFrames++;
        }
        instanceSum += frameInstances;
        instanceFrames++;
        lights = Math.max(lights, frameLights);
    }

    // ...

    /** Bytes allocated so far by every job worker (one id at a time: no garbage). */
    private long workerSum() {
        long sum = 0;
        for (long id : workerIds) {
            sum += THREADS == null ? 0 : Math.max(0, THREADS.getThreadAllocatedBytes(id));
        }
        return sum;
    }

    // ...

    /**
     * The JDK's thread bean, looked up once: {@code ManagementFactory.getThreadMXBean()} itself
     * allocates about 1 KB per call.
     */
    private static final com.sun.management.ThreadMXBean THREADS =
            ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean b
                    ? b
                    : null;

    /** The calling thread's allocated bytes; reading it allocates nothing. */
    static long currentThreadAllocated() {
        return THREADS == null ? -1 : THREADS.getCurrentThreadAllocatedBytes();
    }
```

- **The counters are read on every recorded frame, on the frame's regular path.** An earlier version
  took snapshots only every so often, in a branch. That branch ran rarely, so C2 compiled it as an
  uncommon trap (§2.3), and its first run in the window deoptimized the frame code — which then
  allocated in the interpreter. The capture measured itself (fix F9, decision **D66**). A branch
  taken every frame is in the profile and stays compiled.
- **The counters are cumulative**, so the array holds totals; a frame's allocation is the difference
  of two neighbouring entries (§17.3). No arithmetic in the frame.
- **`workerSum` reads one thread at a time.** The bean has a bulk method,
  `getThreadAllocatedBytes(long[])`, that returns a new `long[]`: an allocation per frame. One id at
  a time returns a primitive (0 bytes per call, §3.2). `Math.max(0, …)` turns the −1 of a thread
  that has ended into 0.
- **`THREADS`** is looked up once (§3.2 measured 728 bytes per lookup; the comment's "about 1 KB" is
  D66's figure) with a pattern-matching `instanceof`: on a JVM without the HotSpot extension it is
  `null`, and the capture reports −1 instead of crashing.
- **Past the capacity**, frames are no longer stored (no growth, no allocation), but the instance
  and light statistics keep counting.

The main thread has its own pair, `recordMainAllocation()` and `recordMain(nanos, graph)` (frame
CPU time and GPU time per pass from the frame graph's timers). Both threads must agree on which
frames count; the `measuring` flag travels with each frame's HUD slot from the game thread to the
main thread (`DemoHud.measuring[index]`, carried by the triple buffer), so the main thread records
exactly the frames the game thread produced inside the window. `end()`, the cold closing call, reads
the GC counters again.

### 17.3 `write`: from arrays to a verdict

After both threads have stopped, `write(dir, gpuName, jvm)` turns the arrays into `perf.json` and
`perf.md` and returns the keys of the failed budgets. It is a cold path (string building, sorting,
JSON); its interesting parts are the allocation analysis.

**Halves.** Allocation is judged on the *second half* of the window (D66): the first half absorbs
one-time effects the JIT does on its own schedule.

```java
    /** Bytes over all {@code n} recorded frames and over the second half of them. */
    private static long[] halves(long[] counter, int n) {
        if (n < 2) {
            return new long[2];
        }
        return new long[] {counter[n - 1] - counter[0], counter[n - 1] - counter[n / 2]};
    }
```

With cumulative counters, the bytes between frame $a$ and frame $b$ are `counter[b] - counter[a]`.
In the run of §25.1, for the game thread, that gave 3,512 bytes over all 7,162 frames and 1,840 in
the second half.

**Explaining each allocating frame.** For every frame of the second half whose game or worker
counters moved, the report asks the JIT log (§17.6) whether a C2 compile explains it:

```java
        for (int f = gameFrames / 2 + 1; f < gameFrames; f++) {
            long g = gameAllocated[f] - gameAllocated[f - 1];
            long w = workerAllocated[f] - workerAllocated[f - 1];
            if (g == 0 && w == 0) {
                continue;
            }
            JitLog.Compile c =
                    JitLog.explain(
                            compiles, uptimeMillis(gameStamp[f - 1]), uptimeMillis(gameStamp[f]));
            String what = fmt("game frame %d: game %d B, workers %d B", f, g, w);
            if (c != null) {
                jitFrames++;
                jitBytes += g + w;
                jitEvents.append(jitNote(what, c));
            } else {
                unexplained += g + w;
                if (++allocatingFrames <= 8) {
                    events.append(what).append("; ");
                }
            }
        }
```

The frame's time interval is from the previous frame's stamp to its own, converted to uptime. An
allocation the JIT explains is reported as "JIT warm-up"; anything else is **unexplained** and fails
the `alloc` budget, which passes only when `unexplained == 0`. The main thread's frames go through
the same loop. The first eight unexplained frames are listed by number.

**Budgets.** Each row is a key, a name, a target, the measured text and pass/fail; the keys are
`game`, `animation`, `ui`, `behaviours`, `main`, `gpu`, `alloc`, `flags`, `gc` and `load`. Timing
budgets use the 99th percentile of the frame times, from the `Stats` record:

```java
            Arrays.sort(ms);
            return new Stats(
                    sum / ms.length,
                    ms[(int) (ms.length * 0.5)],
                    ms[Math.min(ms.length - 1, (int) (ms.length * 0.95))],
                    ms[Math.min(ms.length - 1, (int) (ms.length * 0.99))],
                    ms[ms.length - 1]);
```

For 7,162 frames the p99 is element $\lfloor 7162 \times 0.99 \rfloor = 7090$ of the sorted times
(counting from 0): 71 frames are slower. A p99 budget allows a hitch now and then and catches a
system that is slow every hundredth frame. `IsoDemo` prints the Markdown, and exits with code 4 if a
failed key is in `--fail-on`.

### 17.4 Checking the JVM it runs on

A measurement of the wrong configuration is worse than none: it looks like evidence.

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
        // The launch option, not Module.isNativeAccessEnabled: without the option the JDK enables
        // native access on first use, with a warning.
        boolean nativeAccess = false;
        for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            nativeAccess |=
                    arg.startsWith("--enable-native-access=") && arg.contains("dev.jeddy.core");
        }
        if (!nativeAccess) {
            missing.add("--enable-native-access=dev.jeddy.core,...");
        }
        return String.join(", ", missing);
    }
```

- **The collector** is recognized by its management beans' names (`ZGC Major Cycles`, `ZGC Minor
  Pauses` and so on).
- **Customization** is a system property, readable directly.
- **Native access** is read from the JVM's command-line arguments, not from the module: as the
  comment explains, a module without the option has native access switched on at its first
  restricted call (with the warning of §4.9), so asking the module at report time would say "yes".

A non-empty result fails the `flags` budget. It exists because of fix F11 (§25.3): the CI captures
once ran for days without the launcher flags, on G1 with customization on, and measured the wrong
thing.

### 17.5 `AllocProbe`: which system allocated?

The capture says *that* a frame allocated. With `--alloc-probe`,
[`AllocProbe`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/AllocProbe.java) says
*which system* did, by reading the game thread's counter between the systems of a frame:

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

The game calls `mark` after each system, naming the system that just ran. From
`DemoGame.fixedUpdate`:

```java
        AllocProbe a = probe;
        a.mark(AllocProbe.START);
        world.beginTick();
        a.mark(AllocProbe.BEGIN_TICK);
        // ...
        level.crowd.update(dt);
        a.mark(AllocProbe.CROWD);
        sync.apply(dt);
        a.mark(AllocProbe.SYNC);
        // ...
        world.updateTransforms(ctx.jobs());
        a.mark(AllocProbe.TRANSFORMS);
```

- **The bytes since the previous mark are charged to the system named in this mark**: the counter
  moved while that system ran. `START` marks the beginning of a section without charging anything
  meaningful to it.
- **Totals per system** go into `bytes[]`; **individual allocations** (frame, system, bytes) go into
  three parallel arrays, up to `MAX_EVENTS` = 256. No objects: the probe follows the rules it
  checks.
- **`last != 0`** skips the very first mark after `start()`, which has no previous reading.
- **Frames** are counted at the `HUD` mark in `extract`. The `UI` system has two marks: one at the
  end of `update` (after `fillUi`, `demoUi.declare` and `editor.declare`), which comes before `HUD`
  and so carries the frame's own number — that is where §25.1's `Editor::declare` allocation at
  frame 5741 sits, listed under the capture's frame 5741 — and one after `demoUi.extract`, the only
  mark that comes *after* `HUD`, whose bytes are listed under the next frame's number. The
  per-system totals are unaffected.
- **When it is off**, `mark` is one field read and a branch: cheap enough to leave in the game.

The report gets a table "Game thread allocation by system", one row per system whose total is not
zero, named from `AllocProbe.NAMES` (`tick: crowd.update`, `extract: world`, `ui`, …), and a list of
the second half's allocations by frame and system. §25.1 shows both from a real run.

### 17.6 `JitLog`: the compiler's diary

The last piece tells JIT warm-up from engine allocation (decision **D70**, §25.2).
[`JitLog`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/JitLog.java) turns on the
JVM's compilation log at run time and reads it back.

**Starting the log** without a command-line flag:

```java
    static boolean start(Path file) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.deleteIfExists(file);
            ManagementFactory.getPlatformMBeanServer()
                    .invoke(
                            new ObjectName("com.sun.management:type=DiagnosticCommand"),
                            "vmLog",
                            new Object[] {
                                new String[] {
                                    "output=" + file.toAbsolutePath(),
                                    "what=jit+compilation=debug",
                                    "decorators=uptimemillis"
                                }
                            },
                            new String[] {String[].class.getName()});
            return true;
        } catch (Exception | LinkageError e) {
            System.err.println("perf: no JIT log (" + e + "); allocation is judged strictly");
            return false;
        }
    }
```

The JDK's `jcmd <pid> VM.log ...` command is also available to a program as an operation of the
`DiagnosticCommand` management bean, `vmLog`, which takes the same arguments as strings. This call
is the equivalent of starting the JVM with `-Xlog:jit+compilation=debug:file=<file>:uptimemillis`
(§6.4), but it needs no flag on the command line, so `./gradlew :iso-demo:run` and a packaged game
behave the same. If the bean is missing (another JVM), the capture says so and judges allocation
strictly: every allocating frame fails.

**Reading it back.** Each line looks like this (from the run of §25.1):

```
[119182ms] 4475       4       dev.jeddy.samples.iso.PerfCapture::recordMainAllocation (46 bytes)
```

```java
    record Compile(long uptimeMillis, int tier, String method, boolean firstC2OfClass) {}

    private static final Pattern LINE =
            Pattern.compile("^\\[(\\d+)ms\\]\\s+\\d+\\s+([%sbn!]*)\\s*([0-4])\\s+(\\S+)");

    // ... the private constructor and start(), above

    /** Reads the C2 (tier 4) compilations of {@code file}, in log order. */
    static List<Compile> read(Path file) throws IOException {
        List<Compile> out = new ArrayList<>();
        Set<String> compiledClasses = new HashSet<>();
        for (String line : Files.readAllLines(file)) {
            if (line.contains("made not entrant") || line.contains("made zombie")) {
                continue;
            }
            Matcher m = LINE.matcher(line);
            if (!m.find()) {
                continue;
            }
            int tier = Integer.parseInt(m.group(3));
            if (tier != 4) {
                continue;
            }
            String method = m.group(4);
            int sep = method.indexOf("::");
            String owner = sep < 0 ? method : method.substring(0, sep);
            out.add(
                    new Compile(
                            Long.parseLong(m.group(1)), tier, method, compiledClasses.add(owner)));
        }
        return out;
    }
```

- **The pattern**: `\[(\d+)ms\]` the uptime decoration (group 1); `\d+` the compile id; `[%sbn!]*` the
  attribute characters (`%` an OSR compile, `s` a synchronized method, `b` a blocking compile, `n` a
  native wrapper, `!` a method with exception handlers); `([0-4])` the tier (group 3); `(\S+)` the
  method, `Class::name` (group 4).
- **Only tier 4** matters: C2 requests are what allocate (§25.2). Lines that retire old code ("made
  not entrant") are skipped.
- **`firstC2OfClass`**: `Set.add` returns `true` the first time an owner class is seen, so the flag
  marks each class's first C2 compile — the one that resolves the class's string literals.

**Explaining a frame:**

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

A frame between uptimes $t_0$ and $t_1$ that allocated counts as JIT warm-up if a C2 compile was
logged in

$$[\,t_0 - 2\ \text{ms},\ t_1 + 500\ \text{ms}\,].$$

Why that interval: the allocation happens when a thread *requests* the compile, during the frame;
the log line is written when a compiler thread *starts* it, after the request has waited in the
queue. So the logged time is later than the allocation, by up to the queue's wait (500 ms is
generous). The 2 ms before the frame cover the rounding between the two clocks (frame stamps
converted from nanoseconds, log stamps in whole milliseconds). Among the candidates, a class's first
C2 compile is preferred: that is the one that allocates. Every frame the log cannot explain still
fails.

---

## 18. Step 11: launcher flags and the AOT cache

### 18.1 One list of flags, everywhere

Every Jeddy program — the samples, the cooker, a packaged game, a game project built with the
`dev.jeddy.game` plugin — runs with the same JVM flags, defined once in
[`Applications.kt`](../../build-logic/src/main/kotlin/dev/jeddy/gradle/Applications.kt):

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

- **`--enable-native-access=…`**: the modules that call restricted methods (§4.9, §9.4). "Unknown
  names only warn": a program that does not contain, say, the cooker module gets a warning, not an
  error — and packaged images filter the list (§18.2).
- **`-XX:+UseZGC`**: the concurrent collector of §3.3, whose pauses stayed under 20 µs in §25.1. With
  zero allocation per frame it rarely has anything to do. G1, the JDK default, is the supported
  alternative.
- **`-XX:+AlwaysPreTouch`**: the operating system gives a process memory lazily: reserving the heap
  costs nothing, and each 4 KiB page is supplied, zeroed, the first time the program touches it (a
  **page fault**, microseconds each). Pre-touching writes every page of the heap at startup, so no
  frame ever pays for a fresh page. The price is a slower start and the whole heap committed at
  once. A fixed heap (`-Xms` equal to `-Xmx`) goes well with it, but heap sizes are each game's
  choice, so the shared list sets none (DESIGN §4.6).
- **`CUSTOMIZE_THRESHOLD=-1`**: §5.

`configureJeddyApplication` puts the list into the `application` plugin's
`applicationDefaultJvmArgs` (used by `./gradlew run`), into the generated launchers of the image and
into `jpackage` (decisions **D71**, **D75**):

```kotlin
fun Project.configureJeddyApplication(natives: Any) {
    val app = extensions.getByType<JavaApplication>()
    app.applicationDefaultJvmArgs = JEDDY_JVM_ARGUMENTS
    // ... natives, the packaging extension and the jlinkRuntime, distImage, distImageZip and
    // jpackage tasks
```

**The trap that fix F11 fell into.** Gradle's `JavaExec.jvmArgs(...)` *adds* to the task's
`jvmArguments` property. The application's default flags are only that property's *convention*, its
value when nobody sets one, so adding anything replaces them all. The iso demo's build script
therefore passes extra flags another way
([`samples/iso-demo/build.gradle.kts`](../../samples/iso-demo/build.gradle.kts)):

```kotlin
tasks.named<JavaExec>("run") {
    dependsOn("packAssets")
    // A local, so the provider lambda captures no script object (configuration cache).
    val extra = providers.gradleProperty("jeddy.jvmArgs")
        .map { args -> args.split(' ').filter(String::isNotBlank) }
        .orElse(emptyList())
    jvmArgumentProviders.add(CommandLineArgumentProvider { extra.get() })
}
```

An argument provider contributes arguments *in addition to* `jvmArguments` without touching it
(DESIGN §14 states the rule). `-Pjeddy.jvmArgs="-Xlog:gc*:file=build/perf/gc.log"` now adds a GC log
and keeps the launcher flags.

**Where the flags are not used.** Unit tests run in Gradle's test JVM with
`--enable-native-access=ALL-UNNAMED` and a 1 GB heap (`jeddy.java-base`), and JMH forks inherit the
`jmh` task's options (§16.1). Neither runs ZGC or disables customization. The perf capture is where
the real configuration is measured, and its `flags` check (§17.4) makes sure it *is* the real
configuration.

### 18.2 Startup, and the AOT cache

A Java program starts slowly for reasons this chapter has met: it loads, verifies and links its
classes one by one, runs them in the interpreter, profiles them and compiles them (§2). For a game
that is a second or so of black screen, and DESIGN §14 budgets 1.5 seconds from launch to the first
frame.

The JDK has long shipped a **class data sharing** (CDS) archive of its own classes, already parsed
and laid out, mapped into memory at startup. Project **Leyden** extends the idea into an **AOT
cache** (ahead of time) for the application:

- [JEP 483](https://openjdk.org/jeps/483) (JDK 24): the cache holds the application's classes,
  **loaded and linked** as they were in a training run;
- JEP 514 (JDK 25): one step, `-XX:AOTCacheOutput=<file>`: run the program once as training, and
  the JVM writes the cache when it exits;
- JEP 515 (JDK 25): the cache also holds **method profiles** from the training run, so the JIT can
  compile hot methods sooner;
- JEP 516 (JDK 26): cached heap objects work with every collector, ZGC included (DESIGN §4.6).

A later run with `-XX:AOTCache=<file>` maps the cache and skips that work.

**Training at packaging time** (decision **D72**). `distImage` (in
[`Packaging.kt`](../../build-logic/src/main/kotlin/dev/jeddy/gradle/Packaging.kt)) assembles the
image — the `jlink` runtime, the content directories — and then runs the finished image once to
train:

```kotlin
        // The same flags for the training run and the launchers: an AOT cache only loads when
        // they match (the native-access list is checked).
        val flags = imageFlags(jvmArguments.get(), File(out, "runtime"))
        // ... write bin/<name> and bin/<name>.bat
        if (train.get() && trainingArguments.get().isNotEmpty()) {
            val java = File(out, "runtime/bin/" + exe("java"))
            val cache = File(out, aotFile)
            cache.parentFile.mkdirs()
            execOperations.exec {
                executable = java.absolutePath
                workingDir = out
                environment(trainingEnvironment.get())
                args(flags)
                args(trainingJvmArguments.get())
                args("-XX:AOTCacheOutput=" + cache.absolutePath, "-Djeddy.app.dir=" + out.absolutePath)
                args("-m", target)
                args(trainingArguments.get())
            }
            if (!cache.isFile) {
                throw GradleException("the AOT training run wrote no cache")
            }
        }
```

The generated Linux launcher uses the cache when it exists:

```kotlin
                append("AOT=\"\"\n")
                append("if [ -f \"\$DIR/$aotFile\" ]; then AOT=\"-XX:AOTCache=\$DIR/$aotFile\"; fi\n")
                append("exec \"\$DIR/runtime/bin/java\" ")
                append(flags.joinToString(" ") { shellQuote(it) })
                append(" \${AOT:+\"\$AOT\"} \"-Djeddy.app.dir=\$DIR\" -m $target \"\$@\"\n")
```

(`${AOT:+"$AOT"}` expands to the option only when `AOT` is set; the `.bat` launcher does the same
with `if exist`.)

**Why exactly the same flags.** The JVM checks that the cache matches the run; a cache trained with
different flags is not used. Checked for this chapter with a small program and JDK 27+35: a cache
trained without `--enable-native-access`, used with it, gives

```
[error][aot] Mismatched values for property jdk.module.enable.native.access: ALL-UNNAMED specified during runtime but not during dump time
...
[error][aot] Unable to map shared spaces
```

and a cache trained with the default G1, used with `-XX:+UseZGC`, gives `Unable to use AOT cache`.
In both cases the program then runs normally *without* the cache — silently slower — unless
`-XX:AOTMode=on` is given, which makes the JVM refuse to start. So the training run gets the
launchers' own `flags`, and both get them through one function:

```kotlin
internal fun imageFlags(flags: List<String>, runtime: File): List<String> {
    val release = File(runtime, "release")
    if (!release.isFile) {
        return flags
    }
    val modules = release.readLines()
        .firstOrNull { it.startsWith("MODULES=") }
        ?.removePrefix("MODULES=")?.trim('"')?.split(' ')?.toSet()
        ?: return flags
    return flags.mapNotNull { flag ->
        if (flag.startsWith("--enable-native-access=")) {
            val kept = flag.removePrefix("--enable-native-access=").split(',').filter { it in modules }
            if (kept.isEmpty()) null else "--enable-native-access=" + kept.joinToString(",")
        } else {
            flag
        }
    }
}
```

A `jlink` runtime has a `release` file whose `MODULES="java.base dev.jeddy.core …"` line lists the
modules it contains. `imageFlags` keeps only those in the native-access list, so the image's
launcher prints no "unknown module" warnings, and the training run and the launchers carry the
identical list.

**Training without a GPU.** The iso demo trains headless, so any build machine can package it
([`samples/iso-demo/build.gradle.kts`](../../samples/iso-demo/build.gradle.kts)):

```kotlin
jeddyPackaging {
    name = "iso-demo"
    appContent.from(tasks.named<dev.jeddy.gradle.PackAssets>("packAssets").flatMap { it.pakDir })
    // The training run loads the crypt and plays a few seconds without a window, so it runs on
    // build machines; renderer classes outside it still load normally.
    trainingArguments = listOf("--headless", "--quit-after", "4", "--no-audio")
    trainingEnvironment = mapOf("SDL_VIDEO_DRIVER" to "dummy", "SDL_AUDIO_DRIVER" to "dummy")
}
```

**Measured** (DESIGN §4.6, D72): the iso demo's headless first frame took 0.21 s with the cache
against 0.37 s without on a GitHub Linux runner, 0.40 against 0.72 s on a Windows runner, 0.45
against 0.75–0.95 s in a 2-vCPU container, and 1.2 against 1.67 s with GL on Mesa llvmpipe.
`./gradlew :iso-demo:distImage` builds the image with its cache; `-Pjeddy.aot=false` skips the
training.

What the cache does *not* do is remove the JIT's late allocations: decision D70 records that an AOT
cache did not change them (§25.2).

---

# Part C — Using it and proving it

## 19. Zero allocation across the engine: the patterns

The same handful of patterns keeps every per-frame path of the engine at zero bytes. Each answers a
row of §3.4's table.

### 19.1 Scratch fields

A computation that needs a temporary vector or matrix keeps one in a field, allocated once, instead
of creating it per call and hoping escape analysis removes it (§2.6). In
[`World`](../../modules/app/src/main/java/dev/jeddy/app/world/World.java):

```java
    private final float[] point = new float[3];
```

`World.extract` transforms a renderable's bounding-sphere center into world space with
`AffineOps.transformPoint(..., point, 0)` and hands the three numbers to `packet.setBounds`; one
three-element array serves every renderable of every frame. The math module's mutable types
(`MVec3f`, `MQuatf`, `MMat4f`, §23) exist for the same purpose. Scratch shared by job workers is per
thread: the transform store's level kernel keeps one 12-float matrix per thread slot and picks its
own with `scratch[threadIndex]` ([chapter 01, on per-thread
scratch](01-architecture.md#142-per-thread-scratch-perworker-and-objectpool)).

### 19.2 Kernels stored once

A job-system kernel ([chapter 01, on the
kernel](01-architecture.md#121-the-kernel-what-a-piece-of-work-is)) is an object. A method reference
that captures `this` creates a new one each time the expression `this::updateRange` is evaluated: 16
bytes (§3.1). Evaluated inside `update`, it would allocate every frame — and escape analysis cannot
help, because the job system stores the kernel in a job slot, which is an escape (§2.6). Measured
for this chapter with the real `JobSystem`: a `parallelFor` whose kernel lambda is created per call
failed `assertNoAllocation`'s algorithm with 16 bytes per call in all 25 rounds; the same kernel
stored once passed in the first round (exercise 3).

So every system evaluates its kernels once, in field initializers. All of them, from the source:

```java
    private final JobKernel histogramKernel = this::histogramChunk;   // core: ParallelSort
    private final JobKernel scatterKernel = this::scatterChunk;
    private final JobKernel itemKernel = this::buildItems;            // render: DrawBuilder
    private final JobKernel recordKernel = this::writeRecords;
    private final JobKernel kernel = this::cullRange;                 // render: Culling
    private final JobKernel kernel = this::updateRange;               // animation: Animations
    private final JobKernel decodeKernel = this::decodeChunk;         // asset: AssetManager
    private final JobKernel steerKernel = this::steerRange;           // physics: Crowd, and five more
    private final JobKernel kernel = this::scanRange;                 // physics: Visibility
    private final JobKernel rangesKernel = this::rangesChunk;         // physics: UniformGrid
```

(Ten of the nineteen such fields in the engine's main code, collected from eight files; the trailing
comments name the class and are not in the source. The others are `Crowd`'s five more kernels,
`UniformGrid`'s `scatterKernel`, and one each in `RayBatch`, `TileCache` and `NavPathBatch`.)

The same holds for any functional object. `DemoGame.init` stores `debugSink =
renderer.debugDraw()::line;` once and passes the field every frame. A lambda that captures *nothing*
— no local variable, no `this` — is a different case: the JVM creates it once and returns the same
object every time (0 bytes, §3.1), so it is fine anywhere.

There is a second reason the kernel is called per *chunk* of items (512 in the transform store)
rather than per item: the call `j.kernel.run(start, end, threadIndex)` inside the job system sees
many kernel classes — every `this::method` above is a class of its own — so it is megamorphic (§2.4)
and is never inlined. Paid once per chunk, that virtual call is noise; the loop over the chunk's
items is inside the kernel method, compiled on its own with everything inlined.

### 19.3 Per-call inputs in fields

Because the kernel object is shared and long-lived, it cannot take per-call arguments. Inputs go
into fields before the dispatch: `this.dt = dt;` in `Animations.update`, `kernel.levelBase = start;`
in `TransformStore.update`. The workers see those plain writes because the job system publishes the
job with a synchronizing write after them, and each worker reads the job's state before it calls the
kernel (happens-before, §7.1; [chapter 01, on the
kernel](01-architecture.md#121-the-kernel-what-a-piece-of-work-is) has the argument).

### 19.4 Primitive collections

`Map<Integer, Integer>` boxes every key outside −128 … 127 (16 bytes, §3.1) and allocates an entry
object per mapping.
[`IntIntMap`](../../modules/core/src/main/java/dev/jeddy/core/collections/IntIntMap.java) is open
addressing over two `int[]` arrays:

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

- **Key 0 marks an empty slot**, so the key 0 itself is stored apart, in `hasZeroKey` and
  `zeroValue`.
- **`Hashing.mix(key) & mask`** scrambles the key's bits (so keys that differ only in their high bits
  land in different slots) and keeps the low bits as the slot, in a power-of-two table (L00 §6.5).
- **Linear probing**: on a collision, try the next slot, wrapping with the mask, until the key or an
  empty slot is found.
- **No `null`**: an absent key returns the "missing" value chosen at construction (−1 in the
  benchmark).

No `Integer`, no entry objects, and the probes walk neighbouring array elements, which share cache
lines (L00 §9.3). §6.3 measured it: 1.92 ns per lookup against 6.41 ns for `HashMap<Integer,
Integer>`.

### 19.5 Cold paths out of hot methods

[`SdlThread.assertMainThread`](../../modules/sdl/src/main/java/dev/jeddy/sdl/SdlThread.java) runs at
the top of every main-thread-only SDL wrapper:

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
                        + (marked == null
                                ? "SDL video is not initialized"
                                : "'" + marked.getName() + "'")
                        + "), called from '"
                        + Thread.currentThread().getName()
                        + "'. Send it through the main-thread command queue (DESIGN §5).");
    }
```

The hot method is 15 bytes of bytecode (`javap -c`), below `MaxInlineSize` (§2.4), so it is inlined
into every caller as one comparison and a branch never taken. The message — string concatenation,
which allocates — lives in a separate method that runs only on failure. `wrongThread` *returns* the
exception and the caller throws it, so the compiler knows the hot method ends there. Had the message
been built inline, the method would be several times larger, might not be inlined everywhere, and a
careless edit could build the string before the check.

### 19.6 Reserve the peak

Arrays that can grow — the render packet's instances, lights, decals, particle emitters, debug lines
and skinning palettes — grow only when a frame needs more than their capacity, and growing allocates
a new array mid-game. `RenderPacket.reserve` grows them all up front; a game that knows its peak
calls it once. `DemoGame.init` does, for each of the three packets:

```java
        // Size the packets for the peak up front, so nothing grows mid-game (DESIGN §4.2).
        for (int i = 0; i < DemoHud.FRAMES; i++) {
            packets.packet(i)
                    .reserve(
                            4096 + characters * 12 + options.instanceCount(),
                            options.lightCount() + 64 + Spells.MAX,
                            64,
                            8 + Spells.MAX * 3,
                            16_384,
                            characters * JOINTS);
        }
```

Fix F5 of M10 was a fireball's light added on top of 256 others, growing the light array in the
middle of the perf run; `Spells.MAX` extra lights now cover every fireball that can be alive at
once.

### 19.7 Logging

`Logger.debug(String message, Object... args)` checks the level *inside*, after the call: by then
the caller has already built the varargs array and boxed every primitive argument. The `Logger`
Javadoc asks for `isEnabled(LogLevel)` around any log call in code that runs every frame. Exercise 9
removes the need for the guard.

---

## 20. Bindings in practice: jextract, the GL generator and prelinking

### 20.1 jextract's output

The C libraries (SDL3, SDL3_mixer, zstd, and libktx and meshoptimizer for the cooker) are bound with
**jextract**, the JDK project's binding generator, which reads C headers and writes Java (decision
**D2**). It is run by hand, `./gradlew :sdl:regenerateBindings`, against curated include lists of
only the symbols the engine uses, and its output is committed under `src/ffi/java` (DESIGN §6.4): a
normal build needs neither jextract nor a C compiler. Here is one function from
[`SDL3.java`](../../modules/sdl/src/ffi/java/dev/jeddy/sdl/ffi/SDL3.java), condensed to the holder
and the wrapper (jextract also generates `$descriptor()`, `$handle()` and `$address()` accessors):

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

The holder idiom (§2.5): a nested class per function, three `static final` fields — descriptor,
address, handle — initialized the first time `SDL_PollEvent` is called. The wrapper turns
`invokeExact`'s `Throwable` into an `AssertionError`. The shared preamble lives in
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

    static MemorySegment findOrThrow(String symbol) {
        return SYMBOL_LOOKUP.find(symbol)
            .orElseThrow(() -> new UnsatisfiedLinkError("unresolved symbol: " + symbol));
    }
    // ...
    static final SymbolLookup SYMBOL_LOOKUP = SymbolLookup.loaderLookup()
            .or(Linker.nativeLinker().defaultLookup());

    public static final ValueLayout.OfBoolean C_BOOL = ValueLayout.JAVA_BOOLEAN;
    // ...
    public static final ValueLayout.OfLong C_LONG_LONG = ValueLayout.JAVA_LONG;
    // ...
    public static final AddressLayout C_POINTER = ValueLayout.ADDRESS
            .withTargetLayout(MemoryLayout.sequenceLayout(java.lang.Long.MAX_VALUE, JAVA_BYTE));
```

Four lessons hide in these lines:

- **`traceDowncall` uses varargs, a stream and `printf`**: everything the hot-path rules forbid. It
  is harmless because the call sits behind `TRACE_DOWNCALLS`, a `static final boolean` that is
  `false` unless the JVM is started with `-Djextract.trace.downcalls=true`. C2 treats it as the
  constant `false` (§2.5) and deletes the branch; the varargs array is created only inside the
  branch, so it is never created.
- **`LIBRARY_ARENA` is `Arena.global()`.** Raw jextract output uses `Arena.ofAuto()` here; the
  post-processor rewrites it (below), because engine code never uses memory the collector frees
  (§4.8).
- **`SYMBOL_LOOKUP`** looks in the libraries `NativeLoader` loaded first (§4.3), then in the C library.
- **`C_LONG_LONG` for 64-bit integers**, and **`C_POINTER` with a target layout**, so returned
  pointers arrive sized (§4.9).

**The post-processor**
([`BindingsPostProcessor`](../../build-logic/src/main/java/dev/jeddy/gradle/BindingsPostProcessor.java),
decision **D22**) runs as the last step of every regeneration. Its core:

```java
            // Line endings are normalized so Windows and Linux runs produce identical files.
            String out = text.replace("\r\n", "\n").replace("Arena.ofAuto()", "Arena.global()");
            out = C_LONG_DEFINITION.matcher(out).replaceAll("");
            out = C_LONG_USE.matcher(out).replaceAll("C_LONG_LONG");
            out = ELEMENT_ACCESS.matcher(out).replaceAll("$1\\$ELEM_HANDLE.$2(struct, $1\\$OFFSET, ");
```

It removes jextract's `C_LONG` (and `C_LONG_DOUBLE`) definitions and rewrites every use to
`C_LONG_LONG`, making one generated tree correct on both ABIs (§4.2). Before that, it scans every
bound C declaration in the Javadoc snippets for `long`, `wchar_t` and `va_list` (and `long double`)
and fails the regeneration if any appear, because for those types the rewrite would be wrong. The
last line repairs a jextract 22 bug in accessors of array fields inside structs, which wrote at the
start of the struct instead of at the field (DESIGN §6.4).

**Proving the bindings on both platforms.** `SdlBindingsTest` and its siblings call
[`GeneratedBindings.initializeAll`](../../testing/src/main/java/dev/jeddy/testing/GeneratedBindings.java),
which loads and initializes every class in a generated package, every per-function holder included.
Initializing a holder resolves its symbol and links its handle, so a missing symbol, a bad
descriptor or a layout the platform's ABI rejects fails the test on the CI runner of that platform,
instead of the first call in a game.

### 20.2 The GL bindings

OpenGL's roughly 670 commands come from the driver at run time, not from a library's symbol table,
so the engine generates its own binding from the Khronos registry. [Chapter 05, on the
generator](05-opengl-from-java.md#10-step-2-the-generator), builds it in full; what matters for this
chapter:

- **One method and one holder class per command**, exactly the holder idiom of §2.5. The holder's
  initializer calls `GLLinker.link(name, descriptor, critical)` ([chapter 05, on
  `GLLinker`](05-opengl-from-java.md#11-step-3-gllinker)), so a command is linked on its first call,
  and the handle is a constant afterwards. M3-05 measured the generated wrapper at 33.5 ns against
  33.2 ns for a hand-held critical handle (`glUniform4f` on llvmpipe): it adds nothing.
- **Critical linking per command**, from the allow-list in `gl.properties` and a global switch (§21).
- **Allocation-free types**: `GLsync` is a `long`, and pointer parameters that carry buffer offsets get
  `long` overloads (D24, D29), so no draw, upload or fence creates a `MemorySegment` (§4.10).
- **A missing command links to a throwing stand-in**, so an optional extension never breaks startup.

**The type map.** The generator's `SCALARS` table in
[`GlGenerator`](../../build-logic/src/main/java/dev/jeddy/gradle/GlGenerator.java) maps every GL
typedef to a Java primitive, and `javaType` maps anything declared with a `*` to `MemorySegment`:

| C type | Java |
|---|---|
| `GLenum`, `GLbitfield`, `GLuint`, `GLint`, `GLsizei` | `int` |
| `GLboolean` | `boolean` |
| `GLbyte`, `GLubyte`, `GLchar` | `byte` |
| `GLshort`, `GLushort` | `short` |
| `GLfloat`, `GLclampf` | `float` |
| `GLdouble`, `GLclampd` | `double` |
| `GLint64`, `GLuint64`, `GLint64EXT`, `GLuint64EXT`, `GLintptr`, `GLsizeiptr`, `GLsync` | `long` |
| `GLDEBUGPROC` (the debug callback) and any pointer | `MemorySegment` |

Two of the `long`s are the allocation decisions. `GLsync` is a pointer-sized handle that GL only
ever hands back to GL, so as a `long` it sits in `FrameRing`'s `long[] fences` instead of costing a
`MemorySegment` per fence (D24). And the parameters named in `gl.properties`'
`offset.params=indices,indirect,pointer,offset,data,pixels`, when declared `const void *` or
`void *`, are usually a byte offset into a bound buffer, so the generator emits a second method
whose holder carries the suffix `$offset` (D29). For `glMultiDrawElementsIndirect` the generated
`GL46` has, next to the `MemorySegment indirect` form:

```java
    public static void glMultiDrawElementsIndirect(int mode, int type, long indirect, int drawcount, int stride) {
        try {
            glMultiDrawElementsIndirect$offset.MH.invokeExact(mode, type, indirect, drawcount, stride);
        } catch (Throwable t) {
            throw GLLinker.rethrow(t);
        }
    }

    private static final class glMultiDrawElementsIndirect$offset {
        static final MethodHandle MH =
                GLLinker.link("glMultiDrawElementsIndirect", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT), false);
```

The `long` is a buffer offset when a buffer is bound to the matching target and a raw address
otherwise; either way the call passes a number and creates nothing.

**Linking against a driver.** `GLLinker.link` is the holders' one entry point
([`GLLinker`](../../modules/gl/src/main/java/dev/jeddy/gl/ffi/GLLinker.java)):

```java
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

`address(name)` asks the resolver that `GLLoader.init` installed — `Window::glProcAddress`, which
calls `SDL_GL_GetProcAddress` — so GL must be loaded, with the context current, on the main thread,
before the first command runs (`WorldRenderer` does it with
`GLLoader.init(ctx.window()::glProcAddress)`):

```java
    public static GLCapabilities init(Function<String, MemorySegment> procAddress) {
        GLLinker.setResolver(procAddress);
        GLCapabilities caps = readCapabilities();
        // ... the GL 4.6 version check, then:
        capabilities = caps;
        GLLinker.setExtensions(caps::has);
        // ...
        return caps;
    }
```

A command the driver does not export gets a handle of the right type that throws when called,
built from three method-handle combinators:

```java
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
```

`throwException` makes a handle of type `(Throwable)R` that throws its argument; `insertArguments`
binds the exception, leaving `()R`; `dropArguments` prepends the command's parameters so the handle
has exactly the type the holder expects. Startup never fails over an optional extension; the first
*call* does, with the command's name in the message. Extension commands are gated on the extension
string through `setExtensions` because GLX returns a non-null stub for *any* `gl*` name (D24).

### 20.3 Prelinking

The holder idiom's one cost (§2.5) is the first call: it resolves the symbol, links the handle and
its invoker, loads classes — 134,336 bytes for one handle's first `invokeExact` in §5.2. If that
first call happens in the middle of the game, the frame allocates. Fix F4 of M10 found exactly that:
the first positional sound and the first voice that finished playing linked SDL_mixer handles
mid-frame. [`Audio`](../../modules/audio/src/main/java/dev/jeddy/audio/Audio.java) now **prelinks**
the handles its per-frame paths use while the audio system opens (decision **D65**):

```java
    private void prelink() {
        MethodHandle[] handles = {
            MIX_SetTrack3DPosition$handle(),
            MIX_TrackPaused$handle(),
            MIX_TrackPlaying$handle(),
            MIX_SetTrackGain$handle(),
            MIX_StopTrack$handle(),
            MIX_TagTrack$handle(),
            MIX_UntagTrack$handle(),
            MIX_SetTrackAudio$handle(),
            MIX_PlayTrack$handle(),
            MIX_LockMixer$handle(),
            MIX_UnlockMixer$handle(),
            MIX_PauseTag$handle(),
            MIX_ResumeTag$handle(),
            MIX_TrackMSToFrames$handle(),
            MIX_AudioFramesToMS$handle(),
            MIX_SetMixerGain$handle(),
            SDL3.SDL_SetNumberProperty$handle()
        };
        if (handles.length == 0
                || MIX_PROP_PLAY_LOOPS_NUMBER() == null
                || MIX_PROP_PLAY_START_MILLISECOND_NUMBER() == null
                || MIX_PROP_PLAY_FADE_IN_MILLISECONDS_NUMBER() == null) {
            throw new IllegalStateException("SDL_mixer bindings missing");
        }
        writePoint(0, 0, 0);
        MIX_TrackPlaying(tracks[0]);
        MIX_TrackPaused(tracks[0]);
    }
```

- **`X$handle()`** returns the holder's `HANDLE`, which forces the holder class to initialize: that
  is the linking. The array only gives the calls a place to go; what matters is the side effect.
- **The `if`** reads three property-name constants (forcing their initialization too) and keeps the
  array from looking unused. It can only fail if the bindings are broken.
- **The last three lines call a few accessors once for real**, so the invokers of §4.10 are linked too
  and the first in-game call finds everything ready.

D65 moved the other one-time linkages out of frames as well. The asset system's IO threads are
**virtual threads**: threads the JDK schedules itself, in Java, on a small pool of operating-system
threads, so that many can wait on files cheaply (the manager starts two by default). A virtual
thread that blocks is **unmounted**
(its stack is copied into a heap object, a "stack chunk", and the carrier thread moves on) and
**re-mounted** when it wakes. Its idle threads used to park with a 50 ms timeout, so each was
re-mounted 20 times a second, and every re-mount allocated a stack chunk: about 1 MB/s with nothing
to load (fix F2). They now park without a timeout and `schedule()` unparks them. And the render
packets are reserved up front (§19.6).

---

## 21. Critical linking in practice

### 21.1 The allow-list

Critical linking is applied only to the commands named in
[`bindings/gl/gl.properties`](../../bindings/gl/gl.properties), whose comment states the criteria
(DESIGN §4.4):

```properties
# Linked with Linker.Option.critical(true) when -Djeddy.gl.critical is not false (DESIGN §4.4):
# state setters, binds and uniforms only. They never block, never call back into Java, and may
# take heap segments (MemorySegment.ofArray) for their small parameter blocks.
critical=glActiveTexture,\
  glBindBuffer,glBindBufferBase,glBindBufferRange,glBindFramebuffer,glBindImageTexture,\
  # ... binds, blend, clear, depth, stencil, viewport, scissor and polygon state ...
  glUseProgram,glViewport,\
  glUniform1f,glUniform2f,glUniform3f,glUniform4f,glUniform1i,glUniform2i,glUniform3i,glUniform4i,\
  # ... the other glUniform* and glProgramUniform* setters
```

(The `# ...` lines are cuts for this chapter; [chapter 05, on saying what to
generate](05-opengl-from-java.md#9-step-1-say-what-to-generate) shows the whole list.) Everything
that can block is left out: draws (the driver may wait for room in its command queue),
`glClientWaitSync` and other syncs, buffer maps and data uploads (they may stall on a buffer the GPU
still reads), the swap, event polling. The generator refuses a name that is not a selected command.

### 21.2 What it saves

From [bench/README.md](../../bench/README.md) (M3-05, hand-timed, Mesa llvmpipe) and the JMH nightly
baseline (`GlBench`, a GitHub runner, also llvmpipe):

| Call | M3-05 | JMH baseline |
|---|---|---|
| `glUniform4f` regular / critical / generated `GL46` | 37.0 / 33.2 / 33.5 ns | 19.03 / 17.71 / 17.50 ns |
| `glUniformMatrix4fv` off-heap regular / heap critical / copy then regular | 37.6 / 27.7 / 36.1 ns | 21.30 / 18.50 / 24.52 ns |
| `glNamedBufferSubData`, 64 bytes (not critical: it can stall) | 139 ns | 59.58 ns |

Under llvmpipe the driver's own work dominates each uniform call, so the saving is a few
nanoseconds; on a hardware driver that does less per call, the transition is a larger share. As
arithmetic, not a measurement: at the 13 ns of §8.5's first column, a frame that makes 1,000
allow-listed calls saves 13 µs, and one with 5,000 state changes and uniform uploads 65 µs — under
half a percent of a 16.7 ms frame. The larger saving is not calling at all, which the state cache
does ([chapter 05, on the state cache](05-opengl-from-java.md#23-step-15-statecache)).

### 21.3 Critical linking and the debug callback

In a debug run the driver reports problems through the `KHR_debug` callback, an upcall (§4.6) made
*from inside* the GL call that raised the problem — and an upcall from inside a critical downcall
can crash the JVM (§4.5). So debug runs turn critical linking off (decision **D12**). `GLLinker`
reads the switch once, into `static final boolean CRITICAL`, when the class initializes ([chapter
05, on `GLLinker`](05-opengl-from-java.md#11-step-3-gllinker)), so release builds pay nothing for
it; it must be set before any GL command links. `JeddyApp` does that when started with
`-Djeddy.gl.debug=true`:

```java
            if (GL_DEBUG) {
                // A debug context, and the KHR_debug callback the renderer installs needs the
                // non-critical downcalls (DESIGN §4.4): decided here, before any GL class loads.
                config.window.glDebug(true);
                System.setProperty("jeddy.gl.critical", "false");
            }
```

The GPU tests (`GpuTestContext`, `GLTest`) and the hello-triangle sample's debug mode set the same
property first thing, and `GLDebug.enable` throws if critical linking is on.

**A design consequence.** A debug run has no critical linking, so it cannot pass heap segments to GL
either: a regular downcall rejects them (§4.5). If the renderer relied on heap segments, its debug
runs would throw `IllegalArgumentException: Heap segment not allowed` on the first uniform upload.
So it does not. Small parameter blocks are copied into native memory allocated once, as in
[`DebugRenderer`](../../modules/render/src/main/java/dev/jeddy/render/debug/DebugRenderer.java):

```java
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment matrix = arena.allocate(64, 16);
    // ... in draw(), every frame:
        MemorySegment.copy(packet.viewProjection, 0, matrix, ValueLayout.JAVA_FLOAT, 0, 16);
        // ...
        glProgramUniformMatrix4fv(program, 0, 1, false, matrix);
```

A 64-byte copy into a segment owned by the main thread: by M3-05's numbers about 8 ns more than
passing the array directly (36.1 against 27.7 ns), and it works the same with and without critical
linking. Exercise 10 shows what happens if you pass the heap array instead.

---

## 22. Code the JIT likes

DESIGN §4.6 lists the habits that let C2 do its best work. Each follows from §2.

- **`final` classes and small hot methods.** Nearly every class in the engine is `final`, so every
  call to it has one target and can be inlined without a type check (§2.4). Hot methods stay small —
  under `FreqInlineSize`, 325 bytes of bytecode — by moving cold work (error messages, growth) into
  separate methods (§19.5). Inlining is what enables everything else: constant folding, escape
  analysis, bounds-check elimination across what used to be calls.
- **No megamorphic calls in inner loops.** A call site that sees three or more classes is a real
  virtual call (§2.4). The job system calls a kernel once per *chunk* (§19.2); a loop that must
  handle several kinds of things switches on an `int` kind instead of calling through an interface.
- **Counted loops over struct-of-arrays data.** `for (int i = start; i < end; i++)` over `float[]`
  columns (L03 §4) lets C2 prove every index is in range and remove the bounds checks, and, for
  simple bodies, process several elements per instruction (**superword** vectorization,
  `UseSuperWord` is on by default).
  [`Vec3Ops`](../../modules/math/src/main/java/dev/jeddy/math/Vec3Ops.java) says so in its Javadoc:
  "Simple counted loops so C2 can vectorize them." Whether it did is checked with JMH's `-prof
  perfasm` (§6.3), as task M1-12 asked.
- **Branch-free inner loops.** A branch the CPU guesses wrong costs 15–20 cycles while it throws
  away the work it started on the wrong path.
  [`CullOps.spheres`](../../modules/math/src/main/java/dev/jeddy/math/CullOps.java) tests 10,000
  bounding spheres against six frustum planes, and whether a sphere is visible is close to random
  from one element to the next:

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

The 24 plane coefficients were read into locals before the loop, so they live in registers. The six
tests are combined with the non-short-circuit `&`, which evaluates all six and has no branches,
where `&&` would branch after each. And the output is **compacted without a branch**: the loop
always writes `i` at position `n`, then advances `n` only if visible (`visible ? 1 : 0` compiles to
a conditional move or a set-on-condition, not a jump). Walk it with spheres 0 visible, 1 not, 2
visible: `out[0] = 0`, `n = 1`; `out[1] = 1`, `n` stays 1; `out[1] = 2` overwrites it, `n = 2`. The
output holds 0 and 2. (Chapter [04](04-cameras-and-projection.md), on the frustum, derives the plane
test.) - **Avoid `Math.fma` in kernels.** On a processor with fused multiply-add instructions it is
one instruction. Without them, JDK 27's fallback for `Math.fma(float, float, float)` computes the
result with `BigDecimal` objects (`javap -c java.lang.Math` shows the `new java/math/BigDecimal`):
very slow, and allocating. `Float.floatToFloat16` and its inverse, by contrast, are intrinsics. -
**Determinism.** Since JDK 17 all Java floating point is strict on every platform (JEP 306), so the
same `float` arithmetic gives the same bits everywhere. `Math.sin` and friends may still differ in
the last bit between JVMs and processors; gameplay code that must match bit for bit across machines
uses `StrictMath`.

---

## 23. Valhalla readiness

Rule 2 forbids preview features (§1.1), and value classes ([JEP 401](https://openjdk.org/jeps/401),
Project **Valhalla**) are targeted as a preview in JDK 28 (DESIGN §2). A value class has no identity
— no `==` by address, no lock, no identity hash code — so the JVM may store its instances flattened,
inside an array or another object, like a group of primitives, and never allocate them on the heap.
That would make a `Vec3f` as cheap as three `float`s, everywhere, without relying on escape
analysis.

[Chapter 02, on the math module's three
layers](02-math-foundations.md#14-step-1-the-module-and-its-three-layers), builds the design that
waits for it (DESIGN §8.1):

| Layer | Types | For | Allocation today |
|---|---|---|---|
| 1. Value types | records `Vec2f`, `Vec3f`, `Vec4f`, `Quatf`, `Mat3f`, `Mat4f`, `Aabb3f`, `Ray3f`, `Planef`, `Spheref`, `Capsulef` | APIs, configuration, gameplay code that is not per element | a new object per operation, unless escape analysis removes it |
| 2. Mutable scratch | `MVec3f`, `MQuatf`, `MMat4f` | per-frame code working on one thing at a time: cameras, controllers | none: methods modify `this` and return it; kept in fields (§19.1) |
| 3. Bulk kernels | `Vec3Ops`, `QuatOps`, `Mat4Ops`, `AffineOps`, `CullOps` | every per-entity hot path | none: static methods over `float[]` and offsets |

The same operation in the three layers, from
[`Vec3f`](../../modules/math/src/main/java/dev/jeddy/math/Vec3f.java),
[`MVec3f`](../../modules/math/src/main/java/dev/jeddy/math/MVec3f.java) and
[`AffineOps`](../../modules/math/src/main/java/dev/jeddy/math/AffineOps.java). Layer 1 returns a
new object:

```java
public record Vec3f(float x, float y, float z) {
    // ...
    /** Returns {@code this + o}. */
    public Vec3f add(Vec3f o) {
        return new Vec3f(x + o.x, y + o.y, z + o.z);
    }
```

Layer 2 changes `this` and returns it, so a chain of operations on a scratch field allocates
nothing:

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

Layer 3 takes whole columns and writes whole columns; this one composes the 12-float transforms
of items `start` to `end` from ten struct-of-arrays inputs (it calls the scalar `composeTRS`
overload above it once per item):

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

Layer 1 is written so that it can become value classes "with a one-word change", `record` →
`value record`: immutable, `final`, no identity-sensitive operations, no interfaces that need
identity. When that happens, layer 1 becomes about as cheap as layer 2 for many uses: the JVM may
keep a `Vec3f`'s three floats in registers, or flatten them into an array, without first proving
anything about identity or escape. Layer 3 stays either way: for a loop over 10,000 entities, one
array per field beats any per-object layout, flattened or not (L03 §4).

---

## 24. The tests, walked through

### 24.1 `ConcurrencyTest`

[`ConcurrencyTest`](../../modules/core/src/test/java/dev/jeddy/core/concurrent/ConcurrencyTest.java)
tests the queues of §13–15 with real threads:

- **`spscRingKeepsRecordsIntact`** streams 300,000 records of 8 `int`s through a 256-`int` ring
  between a producer thread and the test thread. Record $i$ holds $8i, 8i + 1, \dots, 8i + 7$; the
  consumer checks every value and throws on the first wrong one. A torn record (§13.5) or a record
  delivered twice or out of order would fail. The ring holds 32 records, so both sides spend most
  of the run waiting for each other (`Thread.onSpinWait()` in a loop), which is exactly when the
  full and empty checks are exercised.
- **`spscRingFullAndDrain`** works through the edges by hand with a 4-`int` ring: `offer(1)` and a
  3-value record fill it, so `offer(5)` and a 1-value record both return `false`; `drain` returns
  all 4 values in order; `new SpscIntRing(3)` throws (not a power of two). It also sends
  `Long.MIN_VALUE` through an `SpscLongRing`.
- **`mpmcQueueDeliversEveryValueOnce`** has three producers offer $200{,}000$ values each through a
  1,024-slot `MpmcIntQueue` — producer $p$ offers $200{,}000p + i$, so together the values
  $0, 1, \dots, 599{,}999$ — while three consumers poll. At the end the count must be $600{,}000$
  and the sum must be
  $0 + 1 + \dots + 599{,}999 = \frac{600{,}000 \times 599{,}999}{2} = 179{,}999{,}700{,}000$ (L00
  §2.2). A value lost, duplicated or corrupted changes the count or the sum. Then the queue must be
  empty.
- **`paddedAtomicLong`** checks the arithmetic of §15.1: from 5, `incrementAndGet` gives 6,
  `getAndAdd(4)` returns 6 and leaves 10, `compareAndSet(10, 1)` succeeds.
- **`ringsDoNotAllocate`** runs an 8-`int` offer and poll on an SPSC ring and an offer and poll on
  an MPMC queue under `Allocations.assertNoAllocation`.

Task M1-06's acceptance criterion asked for stress runs of $10^8$ operations and a JMH throughput
benchmark. The unit tests are a smaller version that runs on every build (2.4 million `int`s through
the ring, 600,000 values through the queue), and `bench/` has no queue benchmark; exercise 11 writes
one.

### 24.2 `TripleBufferTest` and `JobSystemTest`

`TripleBufferTest` (three tests: distinct indices, whole frames in order across two threads, no
allocation) is walked through in [chapter 01, with the triple
buffer](01-architecture.md#10-step-1-the-triple-buffer-handoff). From
[`JobSystemTest`](../../modules/core/src/test/java/dev/jeddy/core/job/JobSystemTest.java), the
allocation test of the job system's dispatch:

```java
    void dispatchDoesNotAllocate() {
        for (int i = 0; i < data.length; i++) {
            data[i] = i;
        }
        JobKernel sum =
                (start, end, t) -> {
                    long s = 0;
                    for (int i = start; i < end; i++) {
                        s += data[i];
                    }
                    partial[t] += s;
                };
        Allocations.assertNoAllocation(
                "JobSystem.parallelFor", 25, 500, () -> jobs.parallelFor(sum, data.length, 256));
    }
```

`data` has 4,096 elements, so a grain of 256 makes 16 chunks for the test's job system of three
workers. The kernel captures `this` (it reads the fields `data` and `partial`), so it is an object —
created once, *outside* the measured body, which is the point of §19.2. Each thread adds into its
own `partial[t]`, so no two threads write the same element. As §12.5 says, the assertion sees only
the test thread: it proves that submitting, helping and waiting allocate nothing; the workers' side
is covered by the soak test below.

### 24.3 The memory and input tests

`UtilitiesTest.frameAllocatorBumpsAlignsAndResets` and `segmentCopiesAndCStrings` were walked
through in §10.3 and §10.4. `EventPumpTest.pumpingMouseAndTypingAllocatesNothing` is §11's proof.
`SdlBindingsTest` initializes every generated SDL holder (§20.1); CI runs it on Windows and Linux.

### 24.4 The whole-loop soak

Unit tests prove pieces.
[`JeddyAppTest.bothThreadsAllocateNothingPerFrame`](../../modules/app/src/test/java/dev/jeddy/app/JeddyAppTest.java)
runs the real two-thread app loop of [chapter 01, on the two
loops](01-architecture.md#15-step-6-the-two-loops) — main thread and game thread, job workers, the
input ring, the command queue, the triple buffer — under SDL's dummy video driver, and measures both
threads **from a third thread**, so that no measuring code runs inside the loops (D66: measuring
code inside a loop can change how it is compiled, §17.2). The monitor:

```java
        Thread monitor =
                new Thread(
                        () -> {
                            try {
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
                                    frames[0][w] = gameFrames.get();
                                    frames[1][w] = mainFrames.get();
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } finally {
                                context.get().quit();
                            }
                        },
                        "allocation-monitor");
```

1. **Warm up**: sleep `jeddy.soakWarmupSeconds` (default 2 s), then wait until both threads have
   run at least 30,000 frames (or 30 s have passed). The app runs **uncapped** (`frameCaps(0, 20)`),
   thousands of frames a second, so every per-frame method passes C2's threshold of 5,000 calls
   (§2.2) during the warm-up instead of 83 seconds in.
2. **Wait for the JIT to go quiet**, which catches the C2 compiles of §25.2 before measuring:

   ```java
    private static void awaitJitQuiet(long quietMillis, long maxMillis)
            throws InterruptedException {
        var jit = ManagementFactory.getCompilationMXBean();
        long deadline = System.currentTimeMillis() + maxMillis;
        long last = jit.getTotalCompilationTime();
        long quietSince = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            long now = jit.getTotalCompilationTime();
            if (now != last) {
                last = now;
                quietSince = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - quietSince >= quietMillis) {
                return;
            }
        }
    }
   ```

   `getTotalCompilationTime()` is the JIT's accumulated compile time in milliseconds; while it keeps
   changing, something is being compiled. Quiet for 1.5 s (or 30 s at most) means warm.
3. **Sample four windows**: five readings of both threads' allocation counters (§12.4) and frame
   counts, `windowMillis` apart: $\max(250, \text{soak} / 4)$ ms, so 1,000 ms each for the default
   soak of 4 s. The loops publish their frame counts with `lazySet`, a release write (§7.2): cheap,
   and enough for a monitor that only needs a recent value.

Then, for each thread:

```java
            assertTrue(
                    allocatingWindows <= 1 && bytes[t][windows] - bytes[t][0] < 2048,
                    names[t]
                            + " thread allocated in "
                            + allocatingWindows
                            + " windows:"
                            + perWindow);
```

At most one window may allocate, and under 2 KiB in total. A per-frame allocation shows up in every
window (each window has thousands of frames); a single late JIT event (§25.2) in at most one. DESIGN
§4.7 records that this tolerance has been needed once: 296 bytes in 6 million frames, on a Windows
runner.

The soak reads `jeddy.soakSeconds` and `jeddy.soakWarmupSeconds` from the *test* JVM's system
properties. Gradle does not pass `-D` options from its command line to test JVMs, so the build
forwards a Gradle property for the first: `./gradlew :app:test -Pjeddy.soakSeconds=600` runs a
ten-minute soak (`jeddy.java-base.gradle.kts`). The warm-up has no such forwarding; set it with
`-Djeddy.soakWarmupSeconds=…` on a test run from an IDE.

---

## 25. The perf capture in practice

### 25.1 A real report, read line by line

For this chapter the capture was run as CI runs it, headless for 120 seconds after a 30-second
warm-up, with the probe, on the 2-core container (JDK 27+35, 2026-10-14), with GC and class-load
logs. Here is its `perf.md`, unedited apart from line breaks in the last paragraph:

```
# Iso demo perf run

120 s measured, 7162 game frames, 0 main frames; GPU: none (headless); JVM: 27+35 OpenJDK 64-Bit Server VM.

| Budget | Target | Measured | Pass |
|---|---|---|---|
| Game thread per frame (300 agents) | <= 8 ms | avg 2.39 / p99 7.36 / max 81.48 ms | yes |
| Animation update (300 characters, those in sight evaluated on jobs) | <= 1 ms | avg 0.35 / p99 2.09 / max 12.12 ms | **no** |
| UI build, layout and draw-list emission on the game thread | <= 0.5 ms | avg 0.14 / p99 0.53 / max 33.95 ms | **no** |
| Component behaviours per tick (300 skeletons think, see on jobs, show; the fireballs fly) | <= 1 ms | avg 0.10 / p99 0.45 / max 6.09 ms | yes |
| Main thread CPU (17369 instances per frame) | <= 5 ms | not measured (headless) | n/a |
| GPU (256 lights) | <= 12 ms | not measured (headless) | n/a |
| Steady-state allocation (game, main, workers) | 0 B/frame | 0 B in the second half, 0 game and 0 main frames allocating; JIT warm-up: 8280 B in 3 frames at C2 compiles (2.08 B/frame over all 7162 frames, 14904 B) | yes |
| Launcher flags (ZGC, native access, no MH customization) | all set | all set | yes |
| GC pauses | < 1 ms | none | yes |
| Area load (crypt + characters) | <= 3 s | 855 ms | yes |

## Allocation by thread

| Thread | Bytes | Second half |
|---|---|---|
| jeddy-game | 3512 | 1840 |
| jeddy-worker-* | 0 | 0 |
| main | 11392 | 6440 |

## GPU passes (average ms)

| Pass | ms |
|---|---|

JIT warm-up in the second half (a C2 compile request resolves its class's string literals on the
requesting thread, D70): game frame 5741: game 1840 B, workers 0 B at 129.6 s, C2 compile of
dev.jeddy.editor.Editor::declare (its class's first); main frame 5119 of 7162: 6128 B at 119.2 s,
C2 compile of dev.jeddy.samples.iso.PerfCapture::recordMainAllocation (its class's first); main
frame 5676 of 7162: 312 B at 128.5 s, C2 compile of dev.jeddy.samples.iso.DemoRenderer::render (its
class's first);

## Game thread allocation by system

| System | Bytes |
|---|---|
| tick: party.tick | 80 |
| tick: transforms | 872 |
| update: input | 192 |
| update: audio | 528 |
| ui | 1840 |

Game-thread allocations in the second half by system: frame 5741 of 7162: ui 1840 B;
```

Read it from the top:

- **7,162 game frames in 120 s** is 59.7 frames per second: headless runs are paced like a 60 Hz
  display (§17.1). "0 main frames" counts *rendered* frames; headless, the main thread still records
  its allocation on every new frame, 7,162 times.
- **The game thread** passes: a p99 of 7.36 ms against the 8 ms budget. The 81 ms maximum is one
  frame in which the container's scheduler gave the core to something else; that is what the p99 is
  for (§17.3).
- **Animation and UI fail here, and that is information, not a bug.** On a 2-vCPU container the job
  workers that evaluate animation compete with the game thread and with each other for two cores;
  the DESIGN §14 budgets are set for an 8-core desktop. CI's two-minute job therefore fails only on
  `alloc,flags`, and the nightly ten-minute job on a GitHub runner on `alloc,game,animation,flags`.
- **Allocation passes**: zero unexplained bytes in the second half. Three frames allocated, 8,280
  bytes together, and each one coincides with the *first* C2 compile of a class (§25.2). One of them
  is the measuring tool itself: `PerfCapture.recordMainAllocation` reached C2 at 119.2 s, and the
  request resolved the string literals of `PerfCapture` — all the report's text — on the main
  thread: 6,128 bytes. §2.2 computed when that compile would come.
- **The workers allocated nothing**, in either half.
- **The probe** charges the game thread's bytes over the whole window to systems; in the second half
  only the UI allocated, 1,840 bytes in frame 5,741, the same bytes as the JIT line for
  `Editor::declare` (the UI system declares the editor's panels).
- **Flags, GC and load** pass. The GC log (`-Xlog:gc*`) shows why "none": the run's only collection
  was at 2.8 s, during loading (§3.3), and nothing in the 150 seconds after. The class-load log
  shows 3,530 classes loaded in the run and none in the second half of the window (§25.4).

### 25.2 The D70 finding: the JIT allocates

During M10, CI kept failing on about 1.3 KB allocated once by the main thread, roughly 80 seconds
into the window, in a different place on every run (fix F10). No engine code allocated there. A JFR
recording with TLABs switched off (`-XX:-UseTLAB`, so every allocation is an event, §6.4) found the
cause in HotSpot itself: when a method's counters cross the C2 threshold (§2.2), the thread that was
running it *requests* the compile, and HotSpot's `CompileBroker::compile_method`, on that thread,
first resolves every not-yet-resolved string literal of the method's class
(`resolve_string_constants`). Resolving a literal creates its `String`. So **the first C2 compile of
each class allocates a few hundred bytes to a few KB, once, on whichever thread triggered it**
(decision **D70**, DESIGN §4.7). C1-only runs show nothing, because only C2 requests do this.

**Reproduced for this chapter.** The `CustFinal` program of §5.2 — a `static final` downcall handle
called 200,000 times in a loop, printing every allocation — shows late allocations with the JIT on;
two in the run quoted here (§5.2 lists how the call numbers moved in other runs, and that some runs
printed a third or fourth line):

```
call 1: 134336 B
call 128: 63432 B
call 5631: 2728 B
call 7680: 1096 B
```

With C2 switched off (`-XX:TieredStopAtLevel=1`), only the first two lines remain:

```
call 1: 134336 B
call 128: 63432 B
```

Rerun with every allocation recorded:

```
java -XX:-UseTLAB "-XX:StartFlightRecording:filename=a.jfr,+jdk.ObjectAllocationOutsideTLAB#enabled=true,+jdk.ObjectAllocationOutsideTLAB#stackTrace=true" \
     --enable-native-access=ALL-UNNAMED CustFinal 200000
jfr print --events jdk.ObjectAllocationOutsideTLAB --stack-depth 12 a.jfr
```

the late allocations (at calls 9,727 and 12,800 in that slower run) are, grouped by time and top
stack frame:

| Bytes | Objects | Top frame of the allocating stack |
|---|---|---|
| 2,680 | 35 `String` + 35 `byte[]` | `jdk.internal.foreign.AbstractMemorySegmentImpl.equals(Object)` line 550 |
| 1,096 | 16 `String` + 16 `byte[]` | `jdk.internal.foreign.abi.SharedUtils.checkNative(MemorySegment)` line 318 |

The shape is unmistakable: pairs of a `String` and its `byte[]`, the two objects of one string
literal, allocated "inside" methods that do nothing with strings at those lines. The program's own
counter agrees with the recorder in that run: it printed `call 7679: 2680 B` and `call 10752: 1096
B`, exactly the sums of the events, because with TLABs off every allocation takes the slow path that
the counter and the event both see. The 2,728 bytes of the run without the recorder are 48 bytes
more: one more `String` plus one more `byte[]`. `javap -v` counts 36 string constants in
`AbstractMemorySegmentImpl`; the plain run resolved all 36 at that compile, the recorded run 35,
because in the recorded run one of them had already been used, and so resolved, before the compile:
the recorder's Java side runs code before and during `main` that the plain run does not (the same
2,680 came back with the recorder on and TLABs on, and 2,728 with TLABs off and no recorder, so it
is the recorder, not the TLABs). Note what that implies: the cost of a class's first C2 compile is
the number of its literals *still* unresolved at that moment, which is why it differs from run to
run. Those two JDK methods run on every downcall (the argument checks of §4.4), and the JIT log of
the program (a run with TLABs on, the one that printed calls 5,631 and 7,680) shows both reaching C2
within two milliseconds of its own `call`:

```
[166ms]  488       4       jdk.internal.foreign.AbstractMemorySegmentImpl::equals (41 bytes)
[167ms]  489       4       CustFinal::call (8 bytes)
[168ms]  490       4       jdk.internal.foreign.abi.SharedUtils::checkNative (38 bytes)
```

When their counters crossed the threshold, the requests resolved the string literals of their
classes on the requesting thread — 35 for `AbstractMemorySegmentImpl`, whose error messages are
many, and 16 for `SharedUtils`. Exercise 6 repeats the experiment.

**Why no warm-up can avoid it.** A method called once per frame reaches C2 after about 5,000 calls,
83 seconds at 60 frames per second (§2.2); a class whose methods run less often compiles minutes
later. There is no fixed warm-up after which this is over, and an AOT cache did not change it (D70).
So the fix is in the measurement: `JitLog` records the compiles (§17.6), and the report attributes
an allocating frame to the JIT only when a C2 compile was logged in or just after it, preferring a
class's first. Every other allocation still fails CI. The soak test handles the same effect by
waiting for the JIT to go quiet and tolerating one window (§24.4). In a shipped game at a capped 60
FPS these one-off allocations happen in the first minutes of play; they are a few kilobytes, and the
collector does not even run for them.

Chapter [16](16-saves.md), on saves, meets the same effect: the first few saves of a run allocate a
few hundred bytes each in the capture, until the save path is compiled.

### 25.3 Hidden allocations

The M10 fix list ([docs/perf/m10.md](../perf/m10.md)) is a catalogue of allocations that no line of
source code shows. Each was found by the tools of this chapter:

| Fix | Symptom | Cause | Fix |
|---|---|---|---|
| F2 | about 1 MB/s allocated with nothing to load | idle asset IO virtual threads parked with a 50 ms timeout; each wake-up re-mounted the thread and allocated a stack chunk | park without a timeout; `schedule()` unparks (D65) |
| F3 | 40–150 KB of garbage, minutes into a run | method-handle customization at a rarely used downcall's 128th call (§5) | `CUSTOMIZE_THRESHOLD=-1` (D69) |
| F4 | allocation at the first positional sound | lazy jextract holders linking mid-frame (§20.3) | `Audio.prelink` (D65) |
| F5 | render packet arrays growing mid-game | a fireball's light on top of 256 others | `RenderPacket.reserve` (§19.6) |
| F6 | the crowd's neighbour list growing | a new peak density can come at any time | sized to the crowd; `QueryContext(expectedBodies)` |
| F9 | the capture measured itself | `getThreadMXBean()` per call; rarely taken snapshot branches deoptimizing (§17.2) | bean looked up once; counters on the regular path (D66) |
| F10 | 1.3 KB once, at a random place | C2 compile requests resolving string literals (§25.2) | the JIT log attribution (D70) |
| F11 | 96 KB and 32 KB in `Audio.update`, 7–9 minutes into the nightly | the run had silently lost its launcher flags | flags through `jvmArgumentProviders`; the capture checks them (§17.4) |

**F11, in full**, because it involved every tool. The nightly capture found 96 KB and 32 KB
allocated in `Audio.update` 7 to 9 minutes into the run. Three tools found it together
([docs/perf/m10.md](../perf/m10.md)): the allocation probe's per-system events, the late class-load
log (`-Xlog:class+load=info`, summarized by `late-classes.py`, §25.4), and JFR allocation stacks
taken with tiny TLABs (`alloc-stacks.py`, on request in the nightly). What they showed was
customized `LambdaForm`s being spun in the middle of `Audio.update`: method-handle customization
(§5), which the launcher flags switch off. So the flags were not there. The cause: the perf runs
passed extra JVM options with `JavaExec.jvmArgs(...)`, which *adds* to the run task's `jvmArguments`
— and since the application's default flags are only that property's convention, adding anything
dropped them (§18.1). The runs had been measuring G1 with customization on. The two fixes: extra
flags now go through `jvmArgumentProviders` (the iso demo's build script, §18.1), and the capture
checks that the launcher flags are present and fails the `flags` budget when they are not (§17.4).

### 25.4 Late class loading

A class loaded in the steady state of a game is a symptom: something is being linked for the first
time — a spun method-handle form, a holder initialized on first use, a lambda's class — and that
almost always allocates. [`.github/scripts/late-classes.py`](../../.github/scripts/late-classes.py)
reads the window's uptimes from `perf.json` and a class-load log written with
`-Xlog:class+load=info:file=<log>:uptime`, and lists every class loaded in the second half:

```
$ python3 .github/scripts/late-classes.py build/perf/perf.json build/perf/classload.log
0 classes loaded in the second half (93-153 s uptime)
```

That is the run of §25.1: the window spans uptimes 33 to 153 seconds, its second half 93 to 153, and
none of the run's 3,530 classes was loaded there. CI posts the result as an annotation on every run.

---

## 26. Things to run

The engine's tests and benchmarks (on Windows, `gradlew.bat`):

```
# the queues, the triple buffer, the job system's dispatch, the memory classes
./gradlew :core:test --tests 'dev.jeddy.core.concurrent.*'
./gradlew :core:test --tests 'dev.jeddy.core.job.JobSystemTest'
./gradlew :core:test --tests 'dev.jeddy.core.UtilitiesTest'
# the two-thread soak, 4 s by default, ten minutes on request
./gradlew :app:test --tests 'dev.jeddy.app.JeddyAppTest'
./gradlew :app:test --tests 'dev.jeddy.app.JeddyAppTest' -Pjeddy.soakSeconds=600
# event pump without allocation; every SDL holder linked
./gradlew :input:test --tests 'dev.jeddy.input.EventPumpTest'
./gradlew :sdl:test
# FFM costs; primitive against boxed collections with allocation per operation
./gradlew :bench:jmh -Pjmh.includes=FfmBench
./gradlew :bench:jmh -Pjmh.includes=CollectionsBench "-Pjmh.args=-prof gc"
# GL call costs (needs a GL 4.6 context; on Linux without a GPU: xvfb-run -a and MESA_GL_VERSION_OVERRIDE=4.6)
./gradlew :bench:jmh -Pjmh.includes=GlBench
```

The perf capture, as CI runs it, and as the nightly runs it with allocation stacks:

```
./gradlew :iso-demo:run --args="--headless --perf 120 --warmup 30 --alloc-probe --report build/perf --fail-on alloc,flags"
./gradlew :iso-demo:run "-Pjeddy.jvmArgs=-Xlog:gc*:file=build/perf/gc.log -Xlog:class+load=info:file=build/perf/classload.log:uptime -XX:MinTLABSize=256 -XX:TLABSize=256 -XX:-ResizeTLAB -XX:StartFlightRecording=filename=build/perf/alloc.jfr,settings=.github/jfr/alloc.jfc" \
    --args="--headless --perf 600 --warmup 60 --alloc-probe --report build/perf --fail-on alloc,game,animation,flags"
python3 .github/scripts/late-classes.py samples/iso-demo/build/perf/perf.json samples/iso-demo/build/perf/classload.log
python3 .github/scripts/alloc-stacks.py samples/iso-demo/build/perf/perf.json samples/iso-demo/build/perf/alloc.jfr
```

(The paths in `-Pjeddy.jvmArgs` are relative to `samples/iso-demo`, the run task's working
directory; the settings file path may need to be absolute.) The packaged image with its AOT cache:

```
./gradlew :iso-demo:distImage
./gradlew :iso-demo:distImage -Pjeddy.aot=false
```

The JVM's own view of a running program, from another terminal (`jps` lists the process ids):

```
jcmd <pid> VM.log output=jit.log what=jit+compilation=debug decorators=uptimemillis
jcmd <pid> VM.flags
java -XX:+PrintFlagsFinal -version | grep -E "Tier4InvocationThreshold|FreqInlineSize|MaxInlineSize|UseCompactObjectHeaders"
```

And the single-file experiments of this chapter, all in the exercises: the object sizes (exercise
2), boxing under the assertion (1), capturing kernels (3), pointer returns (4), customization (5),
the JIT's own allocation (6) and the padded layout (15).

---

## Exercises

Each exercise has a hint and a full solution; try before you open them. Exercises 1–6 are
single-file Java programs you run with the JDK alone (with the engine's compiled classes on the
class path where they need them: `./gradlew :core:classes` puts them in
`modules/core/build/classes/java/main`). Exercises 7–11 change the engine or add a benchmark; revert
them afterwards. Exercises 12–17 are pencil and paper, or nearly.

**1. Boxing under the assertion (§2.6, §12.5).** Run `Allocations.assertNoAllocation`'s algorithm
on `HashMap<Integer, Integer>.get(key)` and on `IntIntMap.get(key)`, with a key of 1,000,000 and
with a key of 100. Predict each result first. Then run the boxed map again with
`-XX:-DoEscapeAnalysis` and with `-XX:TieredStopAtLevel=1`.

<details><summary>Hint</summary>

`Integer.valueOf` caches −128 … 127 (§3.1). A key of 1,000,000 is boxed into a new 16-byte
`Integer` per call — unless C2 inlines `HashMap.get` and removes the box (§2.6). The assertion
passes as soon as one round is clean.
</details>

<details><summary>Solution</summary>

A program with the same algorithm as `Allocations`, printing every round:

```java
import dev.jeddy.core.collections.IntIntMap;
import java.util.HashMap;

public class Boxed {
    static final com.sun.management.ThreadMXBean T =
            (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
    static long sink;

    // Same algorithm as dev.jeddy.testing.Allocations.assertNoAllocation, printing every round.
    static void rounds(String what, Runnable body) throws InterruptedException {
        StringBuilder sb = new StringBuilder();
        long best = Long.MAX_VALUE;
        for (int round = 0; round < 25; round++) {
            for (int i = 0; i < 4000; i++) body.run();
            long before = T.getCurrentThreadAllocatedBytes();
            for (int i = 0; i < 4000; i++) body.run();
            long allocated = T.getCurrentThreadAllocatedBytes() - before;
            best = Math.min(best, allocated);
            sb.append(' ').append(allocated);
            if (allocated == 0) break;
            if (round >= 2) Thread.sleep(20);
        }
        System.out.println(what + ": rounds" + sb + (best == 0 ? "  -> PASS" : "  -> FAIL, best " + best / 4000.0 + " B/call"));
    }

    public static void main(String[] a) throws Exception {
        int key = Integer.parseInt(a[0]);
        HashMap<Integer, Integer> boxed = new HashMap<>();
        IntIntMap prim = new IntIntMap(16, -1);
        boxed.put(key, 42);
        prim.put(key, 42);
        if (a[1].equals("boxed")) rounds("HashMap<Integer,Integer>.get(" + key + ")", () -> sink += boxed.get(key));
        else rounds("IntIntMap.get(" + key + ")", () -> sink += prim.get(key));
    }
}
```

Compile it first, then run the class (the reason is below):

```
javac -cp modules/core/build/classes/java/main -d /tmp/boxed Boxed.java
java -cp modules/core/build/classes/java/main:/tmp/boxed Boxed 1000000 boxed
```

and so on. Measured for this chapter (JDK 27+35, the 2-core container; three runs each, the boxed
map took three or four allocating rounds):

```
HashMap<Integer,Integer>.get(1000000): rounds 64000 64000 64000 0  -> PASS
IntIntMap.get(1000000): rounds 0  -> PASS
HashMap<Integer,Integer>.get(100): rounds 0  -> PASS
IntIntMap.get(100): rounds 0  -> PASS
```

and the boxed map with 1,000,000 under `-XX:-DoEscapeAnalysis` or `-XX:TieredStopAtLevel=1`:

```
HashMap<Integer,Integer>.get(1000000): rounds 64000 64000 ... (25 rounds) -> FAIL, best 16.0 B/call
```

The surprise is the first line: the boxed lookup *passes*. For three rounds it allocates 64,000
bytes per 4,000 calls, 16 bytes per call — one `Integer` — while the code runs in the interpreter
and C1. Then C2 inlines `HashMap.get`, sees that the box never leaves the compiled code and removes
it. Without escape analysis, or without C2, it never stops allocating. The primitive map passes in
round 0: it allocates nothing even interpreted. The key 100 passes everywhere because
`Integer.valueOf(100)` returns a cached object. This is §12.5's limit in action: the assertion
proves "nothing once C2 has compiled this shape". The engine uses primitive collections so that it
never depends on that.

**Why compile first.** Run the same source with the source launcher instead —
`java -cp modules/core/build/classes/java/main Boxed.java 1000000 boxed` — and the boxed lookup
**fails all 25 rounds** at 16 bytes per call (three runs of three, here and on another machine).
Same JDK, same code, opposite verdict. `-XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining` shows the
difference. In the compiled run, C2's tree for the lambda ends in:

```
@ 8   java.util.HashMap::get (19 bytes)   inline (hot)
  @ 2   java.util.HashMap::getNode (150 bytes)   inline (hot)
    @ 23   java.util.HashMap::hash (20 bytes)   inline (hot)
      @ 9   java.lang.Integer::hashCode (8 bytes)   inline (hot)
    @ 63   java.lang.Integer::equals (31 bytes)   inline (hot)
```

Everything the box touches is inlined, so it never escapes. In the source-launcher run the same tree
has one more line:

```
    @ 63   java.lang.Integer::equals (31 bytes)   inline (hot)
    @ 128   java.lang.Integer::equals (31 bytes)   failed to inline: low call site frequency
```

Bytecode 128 of `getNode` is the `equals` inside its loop over a bucket's chain, reached only when
two keys share a bucket. Our map has one key, so our calls never go there — but C2 does not compile
*our* `getNode`, it compiles `HashMap.getNode` with the **profile the whole JVM collected for it**
(§2.3), and the source launcher had just run `javac` in this same JVM, whose thousands of `HashMap`
lookups with colliding keys took that loop often enough that the profile marks the path as taken,
but rarely enough that C2 judges the call not worth inlining. A real call stays at bytecode 128, the
box is one of its arguments, so it escapes (§2.6), and the allocation stays even though that path
never runs. In the compiled run the profile C2 saw had never taken that path — the tree has no line
for bytecode 128 at all, because C2 cut the untaken path with an uncommon trap (§2.3) — and the box
is removed.
This is **profile pollution**: a shared JDK method's profile reflects every caller in the process,
so whether escape analysis saves you depends on what else the program did. One more reason the
engine does not rely on it (§19.4); any "passes on JDK 27" claim about boxing is a claim about one
launch, one profile.
</details>

**2. What objects cost (§3.1).** Write a program that measures, under `-Xint`, the bytes each
construct of §3.1's table allocates, and run it with and without `-XX:-UseCompactObjectHeaders`.
Explain by hand the two rows whose numbers change for arrays: `new int[1]` (16 against 24) and the
varargs call `sum(1, 2, 3)` (24 against 32).

<details><summary>Hint</summary>

Run each construct 1,000 times between two reads of the thread's counter and divide. Store results
in a `static` field so nothing is optimized away (under `-Xint` nothing is anyway). An array is a
header, a 4-byte length, then the elements, rounded up to 8. The legacy header is 12 bytes, the
compact one 8.
</details>

<details><summary>Solution</summary>

```java
import java.lang.foreign.*;
import java.util.function.IntSupplier;

public class Sizes {
    static final com.sun.management.ThreadMXBean T =
            (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
    record V3(float x, float y, float z) {}
    static Object sink;
    static int field = 7;

    static long measure(String what, Runnable r) {
        r.run(); r.run(); // link first
        long best = Long.MAX_VALUE;
        for (int k = 0; k < 5; k++) {
            long before = T.getCurrentThreadAllocatedBytes();
            for (int i = 0; i < 1000; i++) r.run();
            best = Math.min(best, T.getCurrentThreadAllocatedBytes() - before);
        }
        System.out.printf("%-40s %6.1f B per call%n", what, best / 1000.0);
        return best;
    }

    static int sum(int... xs) { int s = 0; for (int x : xs) s += x; return s; }

    public static void main(String[] a) {
        long t0 = T.getCurrentThreadAllocatedBytes();
        long t1 = T.getCurrentThreadAllocatedBytes();
        System.out.println("two reads in a row differ by " + (t1 - t0));
        float[] arr = new float[16];
        MemorySegment seg = Arena.global().allocate(64);
        int local = 5;
        measure("new Object()", () -> sink = new Object());
        measure("Integer.valueOf(100) (cached)", () -> sink = Integer.valueOf(100));
        measure("Integer.valueOf(1_000_000)", () -> sink = Integer.valueOf(1_000_000));
        measure("new int[0]", () -> sink = new int[0]);
        measure("new int[1]", () -> sink = new int[1]);
        measure("new float[16]", () -> sink = new float[16]);
        measure("record V3(float, float, float)", () -> sink = new V3(1, 2, 3));
        measure("non-capturing lambda", () -> sink = (IntSupplier) () -> 42);
        measure("lambda capturing a local", () -> sink = (IntSupplier) () -> local);
        measure("varargs sum(1, 2, 3)", () -> field += sum(1, 2, 3));
        measure("MemorySegment.ofArray(float[16])", () -> sink = MemorySegment.ofArray(arr));
        measure("segment.asSlice(0, 16)", () -> sink = seg.asSlice(0, 16));
        measure("\"x\" + field", () -> sink = "x" + field);
    }
}
```

`java -Xint Sizes.java` and `java -Xint -XX:-UseCompactObjectHeaders Sizes.java` print the two
columns of §3.1's table. By hand:

- `new int[1]`, compact: 8 (header) + 4 (length) + 4 (one `int`) = 16, already a multiple of 8.
  Legacy: 12 + 4 + 4 = 20, rounded up to **24**. (`new int[0]` is 16 either way: 8 + 4 = 12 → 16,
  and 12 + 4 = 16.)
- `sum(1, 2, 3)` allocates the varargs array `int[3]`: compact 8 + 4 + 12 = 24; legacy 12 + 4 + 12 =
  28, rounded up to **32**.

Compact headers save 4 or 8 bytes on small objects, which adds up: it is one of the reasons the JDK
made them the default.
</details>

**3. A kernel per call (§19.2).** Copy the idea of `JobSystemTest.dispatchDoesNotAllocate` into a
program, once with the kernel stored in a local before measuring and once with the kernel lambda
written *inside* the measured body, capturing a local variable. Predict the bytes per call, then
run.

<details><summary>Hint</summary>

The job system stores the kernel in a job slot (`j.kernel = kernel`): it escapes, so escape analysis
cannot remove it. A lambda object holds its captured values in fields.
</details>

<details><summary>Solution</summary>

```java
import dev.jeddy.core.job.JobKernel;
import dev.jeddy.core.job.JobSystem;

public class Lam {
    static final com.sun.management.ThreadMXBean T =
            (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
    final long[] data = new long[4096];
    final long[] partial = new long[64];

    static void rounds(String what, Runnable body) throws InterruptedException {
        StringBuilder sb = new StringBuilder();
        long best = Long.MAX_VALUE;
        for (int round = 0; round < 25; round++) {
            for (int i = 0; i < 500; i++) body.run();
            long before = T.getCurrentThreadAllocatedBytes();
            for (int i = 0; i < 500; i++) body.run();
            long allocated = T.getCurrentThreadAllocatedBytes() - before;
            best = Math.min(best, allocated);
            sb.append(' ').append(allocated);
            if (allocated == 0) break;
            if (round >= 2) Thread.sleep(20);
        }
        System.out.println(what + ":" + sb + (best == 0 ? "  -> PASS" : "  -> FAIL, best " + best / 500.0 + " B/call"));
    }

    void run(String mode) throws Exception {
        try (JobSystem jobs = new JobSystem(1)) {
            JobKernel sum = (start, end, t) -> {
                long s = 0;
                for (int i = start; i < end; i++) s += data[i];
                partial[t] += s;
            };
            switch (mode) {
                case "field" -> rounds("kernel created once", () -> jobs.parallelFor(sum, data.length, 256));
                case "inside" -> rounds("kernel created per call", () -> {
                    int bias = 1;
                    jobs.parallelFor((start, end, t) -> {
                        long s = 0;
                        for (int i = start; i < end; i++) s += data[i] + bias;
                        partial[t] += s;
                    }, data.length, 256);
                });
            }
        }
    }

    public static void main(String[] a) throws Exception {
        new Lam().run(a[0]);
    }
}
```

Measured (`java -cp modules/core/build/classes/java/main Lam.java field`, then `inside`):

```
kernel created once: 0  -> PASS
kernel created per call: 8000 8000 8000 ... (25 rounds) -> FAIL, best 16.0 B/call
```

16 bytes per call: the lambda object, 8 bytes of header plus its captured `this` (a 4-byte
reference, for `data` and `partial`) and `bias` (a 4-byte `int`). Every round allocates, also after
C2, because the object escapes into the job slot. A lambda that captured nothing would be a cached
singleton and pass — but a kernel that reads the system's arrays captures `this`, which is why
systems keep theirs in `final` fields.
</details>

**4. A pointer result costs an object (§4.10).** Add two benchmarks to `FfmBench` that link `memcpy`
with an `ADDRESS` return: one returns the `MemorySegment` result, the other returns only its
`.address()`. Run `./gradlew :bench:jmh -Pjmh.includes=FfmBench "-Pjmh.args=-prof gc"` and compare
`gc.alloc.rate.norm` with `mat4OffHeapRegular`. Predict first.

<details><summary>Hint</summary>

A returned segment is a 32-byte object. JMH hands a benchmark's return value to its blackhole, which
counts as an escape. A segment whose only use is `.address()` stays inside compiled code.
</details>

<details><summary>Solution</summary>

Next to `MEMCPY_DESC`:

```java
    private static final FunctionDescriptor MEMCPY_PTR_DESC =
            FunctionDescriptor.of(
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG);
    private static final MethodHandle MEMCPY_PTR =
            LINKER.downcallHandle(LIBC.findOrThrow("memcpy"), MEMCPY_PTR_DESC);
```

and two methods:

```java
    /** memcpy linked with a pointer result, returned to JMH: the segment escapes. */
    @Benchmark
    public MemorySegment pointerReturnKept() throws Throwable {
        return (MemorySegment) MEMCPY_PTR.invokeExact(offTarget, offMatrix, 64L);
    }

    /** The same call; only the address of the result is used. */
    @Benchmark
    public long pointerReturnAddress() throws Throwable {
        return ((MemorySegment) MEMCPY_PTR.invokeExact(offTarget, offMatrix, 64L)).address();
    }
```

What to expect (a prediction from a measurement outside JMH; check it against your run): about 32
B/op for `pointerReturnKept`, about 0 for `pointerReturnAddress` and for `mat4OffHeapRegular`. The
measurement behind the prediction is a plain program run for this chapter, which called the same
`memcpy` handle in rounds of 20,000 calls after 20,000 warm-up calls:

```
JAVA_LONG return       B/call per round: 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0
ADDRESS return, used   B/call per round: 20.8 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0
ADDRESS return, kept   B/call per round: 32.0 32.0 32.0 32.0 32.0 32.0 32.0 32.0 32.0 32.0 32.0 32.0
```

("kept" stored the result in a `static` field.) The `ADDRESS` result costs a segment per call
whenever it escapes, and before C2 even when it does not; the `JAVA_LONG` declaration never creates
one. The 20.8 of the first "used" round is 32 bytes times the share of that round's 20,000 calls
made before C2's code for the caller was installed; reruns gave 14.8 to 32 there, and one run with
the source launcher stayed at 32 for eight rounds (§4.10). Under JMH, whose warm-up iterations come
first, expect the steady-state value. Note that the nightly sweep would not catch the new
benchmarks: `ZERO_ALLOC` matches only `FfmBench\.(abs|mat4)` (§16.3).
</details>

**5. Watch customization happen (§5).** Run the `Cust` program of §5.2. Then run it with
`-Xlog:class+load=info` and find the classes loaded at the 128th call; with
`-Djava.lang.invoke.MethodHandle.CUSTOMIZE_THRESHOLD=10`; and with the threshold `0`. Explain each
output.

<details><summary>Hint</summary>

The handle keeps a `byte` counter; while it is below the threshold, each call adds one; the call
that finds it at the threshold customizes (§5.1). Customized forms are hidden classes named
`java.lang.invoke.LambdaForm$MH/...`.
</details>

<details><summary>Solution</summary>

Measured for this chapter (JDK 27+35):

| Run | Output |
|---|---|
| default | `call 1: 134336 B`, `call 128: 63432 B` |
| `-Djava.lang.invoke.MethodHandle.CUSTOMIZE_THRESHOLD=-1` | `call 1: 132464 B` only |
| threshold 10 | `call 1: 134336 B`, `call 11: 63432 B` |
| threshold 0 | `call 1: 228680 B` only |

With the class-load log, two `java.lang.invoke.LambdaForm$MH/0x...` classes (`source:
__JVM_LookupDefineClass__`) appear between the `call 1` and `call 128` lines. The counter starts at
0; with threshold $k$, calls 1 to $k$ raise it to $k$, and call $k + 1$ finds it at the threshold
and customizes: call 128 for the default 127, call 11 for 10. With threshold 0 the very first call
customizes, so its cost is linking plus customizing in one (228,680 bytes). With −1 the invoker is
built without the check at all, and no later call allocates.
</details>

**6. The JIT allocates (§25.2).** Write `CustFinal`: a `static final` handle to `abs`, called
through a small static method `call(int)` in a loop of `n` iterations, printing the iteration and
the bytes whenever the thread's counter moved. Run it with `n` = 200,000 normally, with
`-XX:TieredStopAtLevel=1`, with `-Xlog:jit+compilation=debug:file=jit.log:uptimemillis`, and finally
with TLABs off and JFR recording every allocation with its stack. Which allocations remain with C1
only? Which JIT log lines line up with the late ones? What do the JFR stacks show?

<details><summary>Hint</summary>

§25.2 shows the outputs. With `-XX:-UseTLAB`, every allocation is a
`jdk.ObjectAllocationOutsideTLAB` event; enable it with its stack trace in
`-XX:StartFlightRecording`, then group the events printed by `jfr print` by time and by the top
frame of their stacks.
</details>

<details><summary>Solution</summary>

```java
import java.lang.foreign.*;
import java.lang.invoke.*;

public class CustFinal {
    static final com.sun.management.ThreadMXBean T =
            (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
    static final MethodHandle ABS = Linker.nativeLinker().downcallHandle(
            Linker.nativeLinker().defaultLookup().findOrThrow("abs"),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

    static int call(int x) throws Throwable {
        return (int) ABS.invokeExact(x);
    }

    public static void main(String[] args) throws Throwable {
        int sink = 0;
        int n = Integer.parseInt(args[0]);
        for (int i = 1; i <= n; i++) {
            long before = T.getCurrentThreadAllocatedBytes();
            sink += call(-i);
            long bytes = T.getCurrentThreadAllocatedBytes() - before;
            if (bytes != 0) {
                System.out.println("call " + i + ": " + bytes + " B");
            }
        }
        System.out.println(sink);
    }
}
```

Compiled with `javac`, run with `java --enable-native-access=ALL-UNNAMED CustFinal 200000`. The
default run prints `call 1`, `call 128`, and late lines: two in most runs (calls 5,631 and 7,680 in
one run; the call numbers move between runs, and some runs print a third or fourth line of 800 to
904 bytes, §5.2). With `-XX:TieredStopAtLevel=1` only `call 1` and `call 128` remain: the
late ones need C2. The JIT log of that run has, within two milliseconds:

```
[166ms]  488       4       jdk.internal.foreign.AbstractMemorySegmentImpl::equals (41 bytes)
[167ms]  489       4       CustFinal::call (8 bytes)
[168ms]  490       4       jdk.internal.foreign.abi.SharedUtils::checkNative (38 bytes)
```

C2 compiles of two JDK methods that run on every downcall, next to the program's own. With

```
java -XX:-UseTLAB "-XX:StartFlightRecording:filename=a.jfr,+jdk.ObjectAllocationOutsideTLAB#enabled=true,+jdk.ObjectAllocationOutsideTLAB#stackTrace=true" --enable-native-access=ALL-UNNAMED CustFinal 200000
jfr print --events jdk.ObjectAllocationOutsideTLAB --stack-depth 12 a.jfr > alloc.txt
```

this short script groups the `main` thread's events that have `CustFinal.call` in their stack:

```python
import re, collections
groups = collections.OrderedDict()
for e in open('alloc.txt').read().split('jdk.ObjectAllocationOutsideTLAB {')[1:]:
    if '"main"' not in e or 'stackTrace = [' not in e:
        continue
    frames = [l.strip() for l in e.split('stackTrace = [')[1].splitlines()
              if l.strip() and l.strip() not in (']', '}')]
    if not any('CustFinal.call' in f for f in frames):
        continue
    key = (re.search(r'startTime = (\S+)', e).group(1), frames[0])
    g = groups.setdefault(key, [0, collections.Counter()])
    g[0] += int(re.search(r'allocationSize = (\d+)', e).group(1))
    g[1][re.search(r'objectClass = (\S+)', e).group(1)] += 1
for k, v in list(groups.items())[-12:]:
    print(k, v[0], dict(v[1]))
```

Its last lines, in the run made for this chapter:

```
('07:41:31.641', 'jdk.internal.foreign.AbstractMemorySegmentImpl.equals(Object) line: 550') 2680 {'java.lang.String': 35, 'byte[]': 35}
('07:41:31.642', 'jdk.internal.foreign.abi.SharedUtils.checkNative(MemorySegment) line: 318') 1096 {'java.lang.String': 16, 'byte[]': 16}
```

(The lines just before them are the customization at call 128: `MethodHandle.customize()`,
`InvokerBytecodeGenerator.generateCustomizedCode`.) Pairs of `String` and `byte[]` are string
literals being created: the requests for C2 compiles of two JDK methods on the downcall's path
resolved the literals of their classes, 35 and 16 of them, on this thread — D70's mechanism, in a
30-line program. The program's own lines in that run read `call 7679: 2680 B` and `call 10752: 1096
B`, the same totals as the events; the 2,728 of the plain run is one literal more, resolved earlier
by the recorder's startup in the recorded run (§25.2).
</details>

**7. Find the allocating system (§17.5).** In `DemoGame.update`, right after
`updateCamera(ctx, in, dt);`, add `lastLabel = "frame " + ctx.frame();` with a new field
`private String lastLabel;`. Run `./gradlew :iso-demo:run --args="--headless --perf 60 --warmup 20
--alloc-probe --fail-on alloc"`. Predict what the report says and the exit code, find the system,
then remove the line.

<details><summary>Hint</summary>

Storing the string in a field makes it escape, so C2 cannot remove it. The probe charges the bytes
allocated since the previous mark to the system named in the next mark.
</details>

<details><summary>Solution</summary>

The next mark after the camera update is `probe.mark(AllocProbe.CAMERA)`, so the probe's table gets
a row **`update: camera`**, and the second-half event list names it in frame after frame. The
`alloc` budget fails — every frame of the second half allocates and no C2 compile explains them —
the report lists the first eight allocating frames, and since `alloc` is in `--fail-on`, the demo
exits with code 4. A prediction for the size: a `String` (24 bytes) plus its `byte[]` holding "frame
" and a 4-digit number, 10 bytes (8 + 4 + 10 = 22, rounded to 24), about 48 bytes per frame; the
report's per-frame number will tell you the truth. Remove the line and the field, and the report is
clean again.
</details>

**8. Measure false sharing (§7.3).** Write a JMH benchmark in which two threads each increment their
own counter: once two adjacent elements of an `AtomicLongArray`, once two `PaddedAtomicLong`s.
Compare.

<details><summary>Hint</summary>

JMH runs several methods at the same time in one **group**: annotate each with `@Group("name")` and
`@GroupThreads(1)`, and share the counters through a `@State(Scope.Group)` object.
</details>

<details><summary>Solution</summary>

`bench/src/main/java/dev/jeddy/bench/FalseSharingBench.java`:

```java
package dev.jeddy.bench;

import dev.jeddy.core.concurrent.PaddedAtomicLong;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongArray;

/** Two threads, two counters: in one cache line, or each alone on its own (§7.3). */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class FalseSharingBench {
    /** Two counters 8 bytes apart: the same cache line. */
    @State(Scope.Group)
    public static class Adjacent {
        final AtomicLongArray counters = new AtomicLongArray(2);
    }

    /** Two counters, each padded onto a line of its own. */
    @State(Scope.Group)
    public static class Padded {
        final PaddedAtomicLong a = new PaddedAtomicLong();
        final PaddedAtomicLong b = new PaddedAtomicLong();
    }

    @Benchmark
    @Group("adjacent")
    @GroupThreads(1)
    public long adjacentA(Adjacent s) {
        return s.counters.incrementAndGet(0);
    }

    @Benchmark
    @Group("adjacent")
    @GroupThreads(1)
    public long adjacentB(Adjacent s) {
        return s.counters.incrementAndGet(1);
    }

    @Benchmark
    @Group("padded")
    @GroupThreads(1)
    public long paddedA(Padded s) {
        return s.a.incrementAndGet();
    }

    @Benchmark
    @Group("padded")
    @GroupThreads(1)
    public long paddedB(Padded s) {
        return s.b.incrementAndGet();
    }
}
```

Run `./gradlew :bench:jmh -Pjmh.includes=FalseSharingBench`. JMH reports each group and each of its
methods. Expect the `adjacent` group to be clearly slower per increment than `padded`: each
`incrementAndGet` there must first take the line back from the other core. How much slower depends
on the processor; the plain two-thread program of §7.3 measured about twice as slow on the 2-core
container, and machines with more distant cores usually show a larger factor. (The bench project's
build already depends on `:core`, so `PaddedAtomicLong` is on its class path.)
</details>

**9. A disabled debug log that costs nothing (§19.7) (hard).** Add overloads
`Logger.debug(String message, int a)` and `debug(String message, int a, int b)` that check
`isEnabled` before doing anything, and prove with `Allocations.assertNoAllocation` that a disabled
`log.debug("frame {} of {}", frame, 7)` allocates nothing. Then ask: does the *existing* varargs
`debug` pass the same assertion?

<details><summary>Hint</summary>

Java picks an overload without boxing or varargs before one that needs them, so `debug(String, int,
int)` wins for two `int` arguments. Box inside, after the level check. For the second question,
think about what C2 can prove about a varargs array whose only user returns at once (§2.6).
</details>

<details><summary>Solution</summary>

In `Logger`, before the existing `debug`:

```java
    /** Logs at DEBUG with one {@code int}; nothing is boxed while DEBUG is off. */
    public void debug(String message, int a) {
        if (isEnabled(LogLevel.DEBUG)) {
            log(LogLevel.DEBUG, null, message, a);
        }
    }

    /** Logs at DEBUG with two {@code int}s; nothing is boxed while DEBUG is off. */
    public void debug(String message, int a, int b) {
        if (isEnabled(LogLevel.DEBUG)) {
            log(LogLevel.DEBUG, null, message, a, b);
        }
    }
```

The test, in `UtilitiesTest` or a scratch test of `:core`:

```java
    @Test
    void disabledDebugAllocatesNothing() {
        LogLevel before = Log.threshold();
        Log.setThreshold(LogLevel.INFO);
        try {
            Logger log = Log.logger("test");
            int[] frame = {1000};
            Allocations.assertNoAllocation(
                    "disabled debug", () -> log.debug("frame {} of {}", frame[0]++, 7));
        } finally {
            Log.setThreshold(before);
        }
    }
```

It passes in round 0. The second question has a surprising answer, measured for this chapter: the
old varargs `debug` *also* passes, after three rounds of 160,000 bytes (40 bytes per call: the
`Object[2]`, 24 bytes, and one boxed `Integer`, 16). Once C2 inlines `debug`, `log` and `isEnabled`,
it sees that the array is never used when the level is off and removes it together with the box.
Under `-Xint`, with `-XX:-DoEscapeAnalysis`, or at a call site that is not inlined, it allocates 40
bytes per call; the overload allocates nothing in all of those cases. That is the difference between
"allocates nothing once compiled" and "allocates nothing" (§12.5), and why the engine's hot paths
are written for the second.
</details>

**10. Why the renderer copies its matrices (§21.3).** In `DebugRenderer.draw`, pass
`MemorySegment.ofArray(packet.viewProjection)` to `glProgramUniformMatrix4fv` instead of the copied
`matrix` segment. Run the iso demo, select the party and press T so the turn-based overlay draws
debug lines (`./gradlew :iso-demo:run`). Then run it again with
`"-Pjeddy.jvmArgs=-Djeddy.gl.debug=true"`. Explain both runs, then revert.

<details><summary>Hint</summary>

`glProgramUniformMatrix4fv` is on the critical allow-list, and only a downcall linked
`critical(true)` accepts a heap segment (§4.5). What does `-Djeddy.gl.debug=true` do to critical
linking?
</details>

<details><summary>Solution</summary>

The first run works: the demo links the command critically with heap access, so the heap segment is
accepted. (It also allocates a 72-byte wrapper per call, §3.1, unless escape analysis removes it —
one more reason to avoid it.) The second run turns on a debug context, and `JeddyApp` sets
`jeddy.gl.critical=false` before anything links, so the KHR_debug callback can run (§21.3). The
command is now a regular downcall, and the first debug-line draw throws `IllegalArgumentException:
Heap segment not allowed: MemorySegment{ kind: heap, ... }` (the message checked for this chapter
with `memcpy`, §4.5). The GPU tests could not have shown the first case: `GpuTestContext` sets
`jeddy.gl.critical=false` too. Copying 64 bytes into native memory works in both modes.
</details>

**11. A queue benchmark, with and without its padding (§15.2, §24.1) (hard).** Write a JMH benchmark
that streams `long`s from one thread to another through an `SpscLongRing`. Then temporarily remove
`SpscLongPad1` from the ring's hierarchy (make `SpscLongProducer` extend `SpscLongConsumer`) and
measure again.

<details><summary>Hint</summary>

Use a group with one producer method and one consumer method, and put the ring in a
`@State(Scope.Group)`; the benchmark class itself can be that state. Count operations per
microsecond (`Mode.Throughput`). A full or empty ring makes an operation return `false`, which still
counts.
</details>

<details><summary>Solution</summary>

```java
package dev.jeddy.bench;

import dev.jeddy.core.concurrent.SpscLongRing;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/** One producer and one consumer streaming longs through an {@code SpscLongRing} (§13, §15.2). */
@State(Scope.Group)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class SpscRingBench {
    private final SpscLongRing ring = new SpscLongRing(1024);

    /** The consumer's destination, its own. */
    @State(Scope.Thread)
    public static class Out {
        final long[] value = new long[1];
    }

    @Benchmark
    @Group("ring")
    @GroupThreads(1)
    public boolean offer() {
        return ring.offer(42L);
    }

    @Benchmark
    @Group("ring")
    @GroupThreads(1)
    public boolean poll(Out out) {
        return ring.poll(out.value, 0, 1);
    }
}
```

Run `./gradlew :bench:jmh -Pjmh.includes=SpscRingBench`, edit `SpscLongRing.java` so that
`abstract class SpscLongProducer extends SpscLongConsumer`, and run again. Without the middle pad,
`head` and `tail` sit 16 bytes apart (exercise 15 prints it), on one line in nearly every placement,
so every offer and every poll steals the line from the other core. Expect the throughput to drop; by
how much is for your machine to say. A refinement: count only *successful* operations by returning
them through JMH's `@AuxCounters`, so that a spinning thread does not inflate the score. Keep the
change on a scratch branch: the padding is load-bearing.
</details>

**12. The ring's arithmetic (§13).** A ring of capacity 16 carries records of 6 `int`s. (a) How many
records fit at once? (b) With `tail = 40` and `head = 30`, can a record be offered, and if so into
which slots? (c) After it, can another? (d) `MainThreadQueue` asks for 1,024 records of 6 `int`s and
rounds the capacity with `Integer.highestOneBit(ints - 1) << 1`. What capacity does it get, and how
many records does it really hold?

<details><summary>Hint</summary>

Work in counters, not slots: the ring is full for a record of `n` when `head < tail + n - capacity`
(§13.5), and a counter's slot is `counter & (capacity - 1)`. For (d), `highestOneBit` keeps only the
top set bit of its argument.
</details>

<details><summary>Solution</summary>

(a) Two: 12 `int`s; a third needs 18 > 16. (b) 10 are in use, 6 free: `wrap` $= 40 + 6 - 16 = 30$,
and $30 < 30$ is false, so it fits, into the slots of counters 40 to 45 masked with 15: slots 8 to
13; `tail` becomes 46. (c) Now 16 are in use: `wrap` $= 46 + 6 - 16 = 36$, $30 < 36$, full; `offer`
returns `false` and writes nothing. (d) $1024 \times 6 = 6144$ `int`s; `highestOneBit(6143)` is
4,096 and shifting left gives 8,192, the next power of two above 6,144 ([chapter 01, on
`MainThreadQueue`](01-architecture.md#163-mainthreadqueue)). It holds
$\lfloor 8192 / 6 \rfloor = 1365$ records.
</details>

**13. A paused producer (§14.7).** An `MpmcIntQueue` of capacity 2 is empty, both positions 0.
Producers P and Q offer at the same moment; P wins position 0 and is then paused by the operating
system *after* its compare-and-set and *before* its release write; Q takes position 1 and completes.
A consumer C now polls. Trace the sequence numbers and say what C gets. What happens when P resumes?

<details><summary>Hint</summary>

A consumer at position `pos` needs the slot's sequence to be `pos + 1` (§14.3), and the sequence is
what the producer's *release write* sets, after the value. Ask which slot C looks at first, and what
its sequence still says while P is paused.
</details>

<details><summary>Solution</summary>

Start: `sequence = [0, 1]`. P: `pos = 0`, slot 0, `seq = 0`, `dif = 0`, CAS 0 → 1 succeeds, writes
`buffer[0]`, then is paused: `sequence[0]` is still 0. Q: it may first read `pos = 0` and see its
CAS fail, re-reads `pos = 1`: slot 1, `seq = 1`, `dif = 0`, CAS 1 → 2, writes `buffer[1]`, release
`sequence[1] = 2`. C: `pos = 0`, slot 0, `seq = 0`, `dif = 0 - (0 + 1) = -1`: **`EMPTY`** — although
Q's value sits published in slot 1. When P resumes it writes `sequence[0] = 1`; C's next poll gets
P's value (and frees slot 0 with `sequence[0] = 0 + 1 + 1 = 2`), the one after gets Q's. Nothing is
lost or reordered; the queue was only blind for as long as P was paused. That is why it is called
bounded and lock-free in practice but not in the strict sense.
</details>

**14. The nightly's allowance (§16.3).** `JobBench.sequential1M` takes 869.8 µs per operation in the
baseline. (a) What is its allocation allowance? (b) If `gc.alloc.rate.norm` reports 40 B/op, what
does the script do? (c) `PhysicsBench.paths1000Parallel` takes 20.47 ms per operation. What is *its*
allowance, and what does that mean for a real allocation of 1,000 bytes per operation?

<details><summary>Hint</summary>

The allowance is $16 + 64 \, t_{\text{op}}$ with $t_{\text{op}}$ in milliseconds (§16.3). Convert
the baseline's microseconds first. Between the noise floor and the allowance the script notes, above
it it fails.
</details>

<details><summary>Solution</summary>

(a) $16 + 64 \times 0.8698 = 71.7$ B/op. (b) 40 is above the 16-byte noise and below the allowance:
the script prints a "background allocation" note and does not fail. (c)
$16 + 64 \times 20.47 = 1326$ B/op. A real 1,000 bytes per operation would pass the sweep. The sweep
catches allocation in short operations; for long ones it is deliberately blunt, and the per-thread
`Allocations.assertNoAllocation` tests (the physics module has twelve test classes that call it) are
what catch it.
</details>

**15. Print the padded layout (§13.2).** Write a program that prints every instance field of a class
and its superclasses with its offset, sorted, and run it on `SpscIntRing`, `MpmcIntQueue` and
`PaddedAtomicLong`, with and without `-XX:-UseCompactObjectHeaders`. Then copy `SpscIntRing.java`
into a scratch directory, make `SpscIntProducer` extend `SpscIntConsumer`, compile and print again.

<details><summary>Hint</summary>

`jdk.internal.misc.Unsafe.getUnsafe().objectFieldOffset(Field)` returns a field's offset; it needs
`--add-exports java.base/jdk.internal.misc=ALL-UNNAMED` at compile time and at run time.
</details>

<details><summary>Solution</summary>

```java
import java.lang.reflect.*;
import java.util.*;
import jdk.internal.misc.Unsafe;

public class F {
    public static void main(String[] a) throws Exception {
        Unsafe u = Unsafe.getUnsafe();
        for (String cn : a) {
            Class<?> c = Class.forName(cn);
            List<String[]> rows = new ArrayList<>();
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    rows.add(new String[] {String.format("%4d", u.objectFieldOffset(f)),
                            f.getType().getSimpleName(), k.getSimpleName() + "." + f.getName()});
                }
            }
            rows.sort(Comparator.comparing(r -> r[0]));
            System.out.println("== " + c.getSimpleName());
            for (String[] r : rows) System.out.println(r[0] + " " + r[1] + " " + r[2]);
        }
    }
}
```

`javac --add-exports java.base/jdk.internal.misc=ALL-UNNAMED F.java`, then `java --add-exports
java.base/jdk.internal.misc=ALL-UNNAMED -cp modules/core/build/classes/java/main:. F
dev.jeddy.core.concurrent.SpscIntRing dev.jeddy.core.concurrent.MpmcIntQueue
dev.jeddy.core.concurrent.PaddedAtomicLong`. The default output is §13.2's table (`head` 72, `tail`
152), `MpmcIntQueue`'s positions at 72 and 144, and `PaddedAtomicLong.value` at 72. With
`-XX:-UseCompactObjectHeaders` the pads and counters all move 8 bytes up (`head` 80, `tail` 160),
`mask` drops into the hole at offset 12 (§13.2), and `buffer` follows the last pad at 240. Without
the middle pad (measured for this chapter):

```
  72 long SpscIntConsumer.head
  80 long SpscIntConsumer.tailCache
  88 long SpscIntProducer.tail
  96 long SpscIntProducer.headCache
```

`head` and `tail` 16 bytes apart: the same 64-byte line unless the object happens to start where a
line boundary falls between offsets 80 and 88. That is the false sharing the padding exists to
prevent.
</details>

**16. Lay out a struct (§4.2).** Write the FFM layout of
`struct S { uint8_t kind; float x; uint8_t flags; double t; int32_t id; };` with every padding
explicit. What are the offsets and the size? What happens if you leave out the padding at the end
and make a `sequenceLayout(2, S)`?

<details><summary>Hint</summary>

Each member starts at the next multiple of its own alignment (§4.2), and a struct's alignment is its
largest member's. The end padding exists so that element 2 of an array starts aligned; FFM checks
exactly that when it builds a sequence.
</details>

<details><summary>Solution</summary>

`kind` at 0; `x` needs 4-alignment, so 3 bytes of padding and `x` at 4; `flags` at 8; `t` needs
8-alignment, so 7 bytes of padding and `t` at 16; `id` at 24. That makes 28 bytes, and the struct's
alignment is its largest member's, 8, so a C compiler pads it to **32**, so that in an array every
element's `t` stays aligned.

```java
StructLayout s = MemoryLayout.structLayout(
        JAVA_BYTE.withName("kind"), MemoryLayout.paddingLayout(3), JAVA_FLOAT.withName("x"),
        JAVA_BYTE.withName("flags"), MemoryLayout.paddingLayout(7), JAVA_DOUBLE.withName("t"),
        JAVA_INT.withName("id"), MemoryLayout.paddingLayout(4));
```

Checked for this chapter: with the final `paddingLayout(4)` the size is 32 and `sequenceLayout(2,
s)` is 64 bytes. Without it the struct is 28 bytes, and `sequenceLayout(2, s)` throws
`IllegalArgumentException: Element layout size is not multiple of alignment`. Leave out one of the
inner paddings instead, and `structLayout` itself throws (§4.2). jextract writes all of them for
you.
</details>

**17. An AOT cache with the wrong flags (§18.2).** Package any small program as a jar, train an AOT
cache with `java -XX:AOTCacheOutput=app.aot -cp app.jar Main`, then run it with
`-XX:AOTCache=app.aot` three ways: with the same flags, with `--enable-native-access=ALL-UNNAMED`
added, and with that flag plus `-XX:AOTMode=on`. Add `-Xlog:aot` to the first. What happens each
time?

<details><summary>Hint</summary>

A cache records the flags it was trained with and the JVM compares them at startup (§18.2). The
default `AOTMode` is `auto`: a cache that does not fit is skipped, not fatal. `on` makes it fatal.
</details>

<details><summary>Solution</summary>

Same flags: the log says `Opened AOT cache app.aot.` and the program runs. With the extra
native-access flag the JVM prints

```
[error][aot] Mismatched values for property jdk.module.enable.native.access: ALL-UNNAMED specified during runtime but not during dump time
...
[error][aot] Unable to map shared spaces
```

and then runs the program normally, without the cache. With `-XX:AOTMode=on` it refuses to start:
`Error occurred during initialization of VM` / `Unable to use AOT cache.` (all checked for this
chapter on JDK 27+35). A cache trained with G1 and used with `-XX:+UseZGC` is rejected the same way.
This is why `distImage` trains with exactly the launchers' flags, through the same `imageFlags`
(§18.2), and why the failure is dangerous: without `AOTMode=on`, a mismatched cache does not fail,
it just makes startup slow again.
</details>

---

## Glossary

- **ABI, calling convention:** the platform's rules for where a C function finds its arguments and
  leaves its result; System V on Linux, Microsoft x64 on Windows (§4.1).
- **Access mode:** how a `VarHandle` reads or writes a variable: plain, opaque, acquire/release,
  volatile, atomic read-modify-write (§7.2).
- **Acquire, release:** a read that orders every later access of its thread after it; a write that
  orders every earlier access before it. Paired, they hand data from one thread to another (§7.2).
- **AOT cache:** a file from a training run holding loaded and linked classes and method profiles,
  mapped by later runs to start faster (Project Leyden; JEP 483, 514, 515, 516) (§18.2).
- **Arena:** the owner of native memory, deciding when it is freed: global, shared, confined or
  automatic (§4.8).
- **Blackhole:** JMH's sink for benchmark results, which keeps the JIT from deleting the work (§6.2).
- **CDS (class data sharing):** the JDK's archive of pre-parsed classes, the basis of the AOT cache
  (§18.2).
- **Code cache:** the memory outside the heap where the JVM keeps the machine code it generates:
  compiled methods, interpreter templates, downcall stubs (§4.4).
- **Coherence protocol:** the hardware rules that keep the cores' cached copies of a line consistent;
  a writer must own the line exclusively (§7.3).
- **Critical downcall:** a downcall linked with `Linker.Option.critical`, skipping the thread-state
  transition; only for functions that are extremely short and never call back into Java (§4.5).
- **Customization:** the JDK compiling a `LambdaForm` specialized to one method handle after it has
  been called often without being a compile-time constant (§5.1).
- **Deoptimization, uncommon trap:** abandoning compiled code whose assumption failed and resuming in
  the interpreter; the jump into the JVM that triggers it (§2.3).
- **Downcall, downcall stub:** a call from Java into C through a `MethodHandle`; the generated machine
  code that performs it (§4.4).
- **Escape analysis, scalar replacement:** C2's proof that an object never leaves compiled code, and
  the removal of its allocation (§2.6).
- **False sharing:** two threads writing different variables that share a cache line, making the line
  move between cores (§7.3).
- **Fork (JMH):** a fresh JVM in which a benchmark runs (§6.2).
- **Hidden class:** a class defined at run time with no name a program can refer to, used for
  `LambdaForm`s and lambdas (§5.1).
- **Holder idiom:** a nested class per native function whose `static final` handle is created on
  first use (§2.5).
- **Inlining:** copying a called method's body into the caller (§2.4).
- **Intrinsic:** a method the JIT recognizes by name and replaces with its own code instead of
  compiling the body (`Math.sqrt`, `MethodHandleImpl.isCompileConstant`) (§5.1).
- **JFR:** the JDK Flight Recorder, the JVM's built-in event recorder (§6.4).
- **JIT, C1, C2, tier:** the JVM's compilers from bytecode to machine code, and the five forms a method
  can run in (§2.1, §2.2).
- **JMH:** the Java Microbenchmark Harness (§6.2).
- **LambdaForm:** the JDK's intermediate form of what a method handle does (§5.1).
- **Lock-free:** threads coordinate through atomic operations and never wait on a lock (§13, §14).
- **Monomorphic, bimorphic, megamorphic:** a call site that has seen one, two, or three or more
  receiver classes (§2.4).
- **MPMC, SPSC:** multi-producer multi-consumer; single-producer single-consumer (§13, §14).
- **OSR (on-stack replacement):** compiling a running loop and jumping into the compiled code in the
  middle of a method (§2.2).
- **Padding (fields):** unused fields placed to keep hot variables on separate cache lines (§13.2).
- **Page fault, pre-touch:** the operating system supplying a page on first touch; touching the whole
  heap at startup so no frame pays for it (§18.1).
- **Perf capture:** the iso demo's scripted measurement of the whole game (§17).
- **Profile pollution:** a shared method's profile reflecting every caller in the process, so that
  the JIT's decisions for one caller depend on what others did (exercise 1).
- **Restricted method:** an FFM or JNI method that can crash the JVM if misused, allowed only for
  modules with native access (§4.9).
- **Safepoint, handshake, time to safepoint:** a point where the JVM knows every reference in a
  thread; stopping all threads or one; the time threads take to get there (§4.5).
- **Segment (`MemorySegment`), zero-length segment:** a bounds-checked view of memory; a pointer whose
  size is unknown (§4.7, §4.9).
- **Signature-polymorphic:** `invokeExact`'s property of taking the exact static types of each call
  site (§4.4).
- **Store buffer, TSO:** the queue in which a core's stores wait for the cache; x86's ordering model,
  in which only a later load may pass an earlier store (§7.2).
- **Superword:** C2's vectorization of simple loops (§22).
- **TLAB:** a thread-local allocation buffer, a chunk of eden only one thread allocates from (§3.1).
- **Upcall, upcall stub:** a call from C into Java; the native function generated for it (§4.6).
- **Value class (Valhalla):** a class without identity whose instances the JVM may store flattened
  (§23).
- **`VarHandle`:** a typed handle to a field or array element with access modes (§7.2).
- **Virtual thread:** a thread the JDK schedules itself on a pool of operating-system threads,
  unmounted when it blocks and re-mounted when it wakes (§20.3).
- **ZGC:** the JDK's concurrent, generational collector with sub-millisecond pauses (§3.3).

---

## Further reading

- [JEP 454: Foreign Function & Memory API](https://openjdk.org/jeps/454), and the `java.lang.foreign`
  package Javadoc (`Linker`, `Linker.Option.critical`, `Arena`, `MemorySegment`, `SymbolLookup`).
- [JEP 401: Value Classes and Objects](https://openjdk.org/jeps/401) (Project Valhalla).
- [JEP 483: Ahead-of-Time Class Loading & Linking](https://openjdk.org/jeps/483), and its follow-ups
  JEP 514, JEP 515 and JEP 516 (Project Leyden).
- The jextract project (openjdk/jextract) and its guide.
- [JMH](https://github.com/openjdk/jmh) and its samples, especially those on dead-code elimination,
  constant folding, groups and profilers.
- Aleksey Shipilёv, "JVM Anatomy Quarks": short articles on TLABs, escape analysis, scalar
  replacement, safepoints, compact headers and more.
- Scott Oaks, *Java Performance* (2nd edition), and Benjamin Evans, James Gough and Chris Newland,
  *Optimizing Java*.
- Brian Goetz et al., *Java Concurrency in Practice*, and Doug Lea, "Using JDK 9 Memory Order Modes".
- Dmitry Vyukov, "Bounded MPMC queue" (1024cores).
- Martin Thompson et al., the LMAX Disruptor technical paper (padding, cached sequences, mechanical
  sympathy).
- Ulrich Drepper, "What Every Programmer Should Know About Memory" (cache lines, coherence, false
  sharing).
- In this repository: [DESIGN.md](../DESIGN.md) §4, §6, §8.1, §9.1, §13.3, §14;
  [docs/perf/m10.md](../perf/m10.md); [bench/README.md](../../bench/README.md).

---

*Code this chapter builds or quotes: `bench.FfmBench`, `CollectionsBench`;
`core.natives.NativeLoader`, `Platform`; `core.memory.OffHeap`, `MemoryStats`, `MemoryCategory`,
`FrameAllocator`, `CStrings`, `Segments`; `core.concurrent.SpscIntRing`, `SpscLongRing`,
`MpmcIntQueue`, `PaddedAtomicLong`, `SpinParkWaiter`; `core.collections.IntIntMap`;
`core.log.Logger`; `testing.Allocations`, `GeneratedBindings`; `input.EventPump`; `sdl.SdlThread`;
the jextract output `SDL3`, `SDL3_1`, `SDL_TextInputEvent`; `BindingsPostProcessor`;
`audio.Audio.prelink`; `render.draw.DrawBuilder`, `debug.DebugRenderer`, `RenderPacket.reserve`;
`app.world.World`, `JeddyApp`; `math.CullOps`, `Vec3Ops`, `Vec3f`, `MVec3f`, `AffineOps`;
`gl.ffi.GLLinker`, `GLLoader`, the generated `GL46`; `core.job.JobSystem`; the iso demo's `PerfCapture`,
`AllocProbe`, `JitLog`, `DemoGame`, `IsoDemo`; the build's `jeddy.jmh.gradle.kts`,
`JmhArguments.kt`, `jeddy.java-base.gradle.kts`, `jeddy.native-library.gradle.kts`,
`Applications.kt`, `Packaging.kt`, the iso demo's `build.gradle.kts`, `bindings/gl/gl.properties`;
the scripts `jmh-check.py` and `late-classes.py`; the tests `ConcurrencyTest`, `TripleBufferTest`,
`JobSystemTest`, `UtilitiesTest`, `TransformStoreTest`, `EventPumpTest`, `JeddyAppTest`. Decisions:
D2, D4, D7, D12, D14, D17, D18, D22, D24, D26, D29, D65, D66, D69, D70, D71, D72, D75, D77 in
[DESIGN §17](../DESIGN.md). Lessons: [L00](lessons/00-foundations.md) (numbers, bits, memory,
caches, threads, FFM), [L01](lessons/01-buffers-in-flight.md) (the lock-free frame ring),
[L03](lessons/03-data-layout.md) (object layout, inlining, `ComponentsBench`). Chapters:
[01](01-architecture.md) (threads, happens-before, the job system, the triple buffer, the command
queue), [02](02-math-foundations.md) (the math module's three layers), [05](05-opengl-from-java.md)
(the GL generator, `GLLinker`, `GLDebug`), [16](16-saves.md) (JIT warm-up in the save path).*

[← 11 Physics, navigation and crowds](11-physics-navigation-crowds.md) · [Index](README.md) · [13 The user interface →](13-ui.md)
