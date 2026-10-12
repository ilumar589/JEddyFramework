# Lesson 1 — Build a frame ring: how the CPU hands the GPU new data every frame

*Every frame, the engine writes tens of kilobytes the GPU must read: where each object is, which
material it uses, the lights, the user interface. This lesson builds, from an empty file, the two
classes that make that safe and fast — `GpuBuffers`, the engine's table of GPU buffers, and
`FrameRing`, the per-frame memory every renderer writes into — and then the uses that depend on
them. It ends with the day the engine first ran on a real Intel GPU, the three driver bugs found
that day, and the testing method that found them.*

**Before you start.** You need [Lesson 0](00-foundations.md): bit operations and masks (§6), memory
and native memory (§8), threads, `volatile` and compare-and-set (§9.4–9.5), and what a GPU, a
driver and OpenGL are (§10–11). No graphics knowledge beyond that is assumed.

**What you will build**

| Piece | File | What it does |
|---|---|---|
| The buffer table | [`GpuBuffers`](../../../modules/render/src/main/java/dev/jeddy/render/gpu/GpuBuffers.java) | creates, maps, writes and destroys GL buffers behind `int` handles |
| The frame ring | [`FrameRing`](../../../modules/render/src/main/java/dev/jeddy/render/gpu/FrameRing.java) | one mapped buffer cut into three regions, one per frame in flight, with a fence each and a lock-free allocator |
| The safe update | `FrameRing.upload` | changes a buffer the GPU is reading, through a GPU-side copy |
| Its users | `DrawBuilder`, `MaterialTable.flush`, `LightClusters`, `StaticScene` | instance records, indirect draws, material rows, a counter reset, GPU culling |

**Contents**

- Part A — The ideas
  1. [The problem](#1-the-problem)
  2. [Two processors, one timeline](#2-two-processors-one-timeline)
  3. [Where the bytes live: GPU memory, mapping and write-combining](#3-where-the-bytes-live-gpu-memory-mapping-and-write-combining)
  4. [OpenGL buffer objects and their storage flags](#4-opengl-buffer-objects-and-their-storage-flags)
  5. [Three ways to change a buffer](#5-three-ways-to-change-a-buffer)
  6. [Waiting for the GPU: fences, coherence and barriers](#6-waiting-for-the-gpu-fences-coherence-and-barriers)
- Part B — Building it
  7. [Step 1: the buffer table](#7-step-1-the-buffer-table)
  8. [Step 2: designing the ring](#8-step-2-designing-the-ring)
  9. [Step 3: the ring's fields and constructor](#9-step-3-the-rings-fields-and-constructor)
  10. [Step 4: beginFrame — wait only if you must](#10-step-4-beginframe--wait-only-if-you-must)
  11. [Step 5: allocate — a lock-free bump allocator](#11-step-5-allocate--a-lock-free-bump-allocator)
  12. [Step 6: endFrame, the accessors and close](#12-step-6-endframe-the-accessors-and-close)
  13. [Step 7: upload — changing a buffer the GPU is reading](#13-step-7-upload--changing-a-buffer-the-gpu-is-reading)
- Part C — Using it and proving it
  14. [Who writes into the ring](#14-who-writes-into-the-ring)
  15. [Ordering inside the GPU: barriers in GPU culling](#15-ordering-inside-the-gpu-barriers-in-gpu-culling)
  16. [Testing the ring](#16-testing-the-ring)
  17. [The day on real hardware](#17-the-day-on-real-hardware)
  18. [Exercises](#18-exercises)
  19. [What to carry forward, and a glossary](#19-what-to-carry-forward-and-a-glossary)

---

# Part A — The ideas

## 1. The problem

The GPU draws what its **shaders** (L00 §10.2) read from GPU memory. Some of that memory is
filled once and rarely changes: the vertices of the meshes, the textures. Some must change every
frame, because the world moved:

- **Instance records.** For every object drawn, the vertex shader needs its **transform** — the
  numbers that place, rotate and scale the mesh in the world, here 12 `float`s — plus which
  material to use and a few flags. The engine packs these into a **64-byte record** per object.
  The demo's crypt draws about 1,041 objects, so about 65 KiB of records per frame.
- **Draw commands.** The engine does not issue one call per object; it writes a list of 20-byte
  **indirect commands** ("draw this mesh, this many instances, starting at that record") into GPU
  memory and asks the GPU to execute the whole list (chapter [06](../06-renderer-architecture.md)).
- **Lights, decals, particles' parameters, the user interface's vertices, per-frame constants**
  (the camera, the time).

Three requirements make this harder than "copy some bytes":

1. **The GPU is still reading last frame's data** when the CPU writes this frame's (§2). Writing
   over it would corrupt what is on screen.
2. **Several threads write at once.** The renderer splits the 64-byte records between the job
   system's worker threads (L00 §9.4), and only the main thread may call OpenGL (L00 §10.5). So
   the workers need plain memory they can write into, without any GL call.
3. **No allocation and no waiting in a normal frame** (L00 §8.2, §11).

The answer is a **ring buffer** of GPU-visible memory with one region per frame in flight. Before
building it we need four ideas: what "in flight" means (§2), where such memory lives (§3), how GL
creates it (§4–5), and how the CPU and GPU agree on who is done (§6).

## 2. Two processors, one timeline

### 2.1 Calls are orders, not actions

L00 §10.4 explained that a GL call records a command and returns; the GPU executes it later. Think
of a restaurant. The waiter (the CPU) writes orders on tickets and pins them to the rail (the
**command stream**). The cook (the GPU) takes tickets from the rail in order and cooks them. The
waiter does not wait for the dish; they go and take the next table's order. The rail is a queue,
and the cook is often a whole ticket behind.

Measured on the owner's laptop, the main thread's work for a frame takes about 8.8 ms and the
GPU's a little more. The two overlap like this:

```
time ─────────────────────────────────────────────────────────────────────────▶
CPU   |-- writes frame 100 --|-- writes frame 101 --|-- writes frame 102 --|
GPU          |------ draws frame 100 ------|------ draws frame 101 ------|-----
                       ▲
                       while the GPU reads frame 100's data, the CPU is writing frame 101's
```

### 2.2 "In flight"

A frame is **in flight** from the moment the CPU has submitted its commands until the GPU has
finished executing them. Any memory those commands read is "in flight" too. If the CPU overwrites
such memory, the GPU may read the old bytes, the new bytes, or a mixture, depending on exactly
when its command processor gets there: a flicker, a mesh in the wrong place for one frame, or a
black material. Worse, it may work on one machine and not on another, because the timing differs.

### 2.3 Ordering is by position in the stream, not by time

The GPU promises one thing about order: **commands take effect in the order they were issued**.
A draw issued after a copy sees the copy's result. But a plain CPU store into memory — `x = 5`
through a pointer — is not a command. It has no position in the stream. It happens *now*, in CPU
time, which has no fixed relation to where the GPU is in its queue. Every technique in this lesson
is a way of giving CPU writes a safe place relative to the stream:

- write only memory the stream has *finished* with (the ring and its fences, §6.1, §8);
- or turn the write into a *command* (the GPU-side copy, §5, §13).

<details><summary>Check: the CPU issues "draw object A using record 0", then writes new numbers into record 0, then issues "draw object B using record 0". Which numbers does the GPU use for A?</summary>

Unknown. The draw of A was only recorded; by the time the GPU executes it, the CPU may already
have written the new numbers. The CPU write is not ordered against the commands. (Both draws may
well see the new numbers, which is the bug the ring prevents.)
</details>

## 3. Where the bytes live: GPU memory, mapping and write-combining

### 3.1 Memory the GPU can read

On a **discrete** GPU (L00 §10.1) there are two places a buffer can live:

- in the card's **VRAM**: fastest for the GPU, but the CPU reaches it only across the PCIe bus,
  either through a window the card exposes (historically 256 MiB of it; all of it with
  "Resizable BAR") or by asking the GPU to copy;
- in **system RAM** that the driver has **pinned** (locked so the operating system will never move
  it or swap it to disk) and made readable by the GPU across the bus.

On an **integrated** GPU (the owner's laptop) there is only system RAM, shared by both.

The driver decides where each buffer goes, from the flags you give it (§4). You never see a VRAM
address.

### 3.2 Mapping

To let the CPU write GPU-visible memory directly, the driver can **map** a buffer: it returns a
CPU address that refers to the buffer's bytes (or to memory the driver will make the GPU see). Writes
through that address go straight to where the GPU will read them, with no API call per write.
Java sees the mapping as a `MemorySegment` (L00 §8.3) and writes into it with `set`.

A **persistent** mapping stays valid while the GPU uses the buffer. Older GL required you to
unmap before drawing; persistent mapping (GL 4.4) removed that, so the engine maps each ring once,
at creation, for its whole life.

### 3.3 Write-combining: why you must never read a mapping

Memory the GPU reads across a bus is usually set up as **write-combined** for the CPU. That means:

- **Writes bypass the CPU's caches.** The core gathers stores in a few small write-combining
  buffers (one cache line each) and sends whole lines across the bus when a buffer fills. Writing
  memory sequentially, in big runs, is fast.
- **Reads are uncached.** Each read goes all the way to the memory and back, no prefetching, no
  line reuse: hundreds of nanoseconds per read, tens of times slower than normal memory.

So the rule for a mapped ring, written in `FrameRing`'s Javadoc: **write it, in order if you can,
and never read it back.** Code that does `+=` on a mapped value reads it first; code that builds a
record in the mapping and then fixes a field by reading its neighbour reads it. Build values in
registers or normal memory, then store them.

## 4. OpenGL buffer objects and their storage flags

A **buffer object** is GL's name for a block of GPU-visible bytes. Two calls create one:

```c
void glCreateBuffers(GLsizei n, GLuint *buffers);           // writes n new names
void glNamedBufferStorage(GLuint buffer, GLsizeiptr size,   // gives buffer `buffer`
                          const void *data,                 // `size` bytes, filled from `data`
                          GLbitfield flags);                // (or undefined if NULL) with these rules
```

`glNamedBufferStorage` creates **immutable storage**: once called, the buffer's size and flags
can never change. That is a promise from you to the driver, and it lets the driver choose the best
memory up front. (The older `glBufferData` makes **mutable** storage that can be resized or
replaced at any time, with a usage *hint* like `GL_STREAM_DRAW` the driver must guess from. The
engine never uses it: the driver has to plan for every possibility, and mutable storage cannot be
mapped persistently.)

The `flags` say what you will do with the buffer. The engine uses four:

| Flag | Allows | Without it |
|---|---|---|
| `GL_DYNAMIC_STORAGE_BIT` | `glNamedBufferSubData`: the CPU hands the driver bytes to put in the buffer | only the GPU (copies, shader writes) or a mapping can change the contents |
| `GL_MAP_WRITE_BIT` | mapping the buffer for writing | no CPU pointer to it |
| `GL_MAP_PERSISTENT_BIT` | keeping it mapped while the GPU uses it | must unmap before any draw that reads it |
| `GL_MAP_COHERENT_BIT` | CPU writes become visible to any command issued *after* the write, with no extra call | you must tell GL which bytes you wrote (`glFlushMappedNamedBufferRange`) before the commands that read them |

And one call maps it:

```c
void *glMapNamedBufferRange(GLuint buffer, GLintptr offset, GLsizeiptr length, GLbitfield access);
```

`access` repeats the mapping bits (`GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT |
GL_MAP_COHERENT_BIT`); it must be a subset of the storage flags.

Each flag you leave out is freedom you give the driver. A buffer with no flags at all (`0`) can only
be filled at creation or by the GPU, so the driver can put it in VRAM and forget the CPU exists:
that is what the engine does for buffers filled once from data at creation (the static scene's
culling tables) and for buffers only shaders write (the light clusters' bounds, the particles).

## 5. Three ways to change a buffer

Putting §2–4 together, there are exactly three ways to get new bytes into a buffer, and they
differ in *who copies* and *where the change sits relative to the command stream*:

| Way | How | Who copies | When it takes effect, relative to the stream | Engine's use |
|---|---|---|---|---|
| **CPU write through the driver** | `glNamedBufferSubData(buffer, offset, size, data)` (needs `GL_DYNAMIC_STORAGE_BIT`) | the driver, now or later | *as if* at this point in the stream: the driver must make earlier commands see the old bytes and later ones the new | a buffer's first fill, and a fallback |
| **Mapped write** | store through a persistent mapping | the CPU, directly | **nowhere**: it happens in CPU time. Coherence (§6.2) makes it visible to commands issued after it, but nothing protects commands issued *before* it that have not run yet | the rings, into regions no command in flight reads |
| **GPU copy** | `glCopyNamedBufferSubData(src, dst, srcOffset, dstOffset, size)` | the GPU | a real command, at its place in the stream | changing a buffer that frames in flight still read (§13) |

The first row deserves a closer look, because the engine trusted it and a driver broke it (§17).
The specification says `glNamedBufferSubData` behaves as if the write happened at that point in
the stream. If earlier commands that read the buffer have not executed yet, a conforming driver
must either **wait** for them (a stall: the CPU sits idle) or **copy** your bytes into a staging
area and schedule the real write in order (more memory traffic). Both cost something, and the
cleverness needed to make it cheap is exactly where drivers have bugs.

The second row is the fast path — no call per write, no driver copy, any thread can write — and
its price is that **you** are responsible for not writing memory the GPU might still read. The
whole design of the ring follows from that price.

## 6. Waiting for the GPU: fences, coherence and barriers

Three different promises are involved, between three different pairs of parties. Keeping them
apart is half of understanding GPU synchronization.

### 6.1 Fences: the CPU waits for the GPU

A **fence** (GL calls it a *sync object*) is a marker you put into the command stream. The GPU
**signals** it when it reaches the marker, which means every command before it has finished.

```c
GLsync glFenceSync(GLenum condition, GLbitfield flags);    // condition: GL_SYNC_GPU_COMMANDS_COMPLETE; flags: 0
GLenum glClientWaitSync(GLsync sync, GLbitfield flags, GLuint64 timeoutNanos);
void   glDeleteSync(GLsync sync);
```

`glClientWaitSync` blocks the calling CPU thread until the fence is signalled or the timeout
expires, and returns one of:

- `GL_ALREADY_SIGNALED`: it was done before you asked;
- `GL_CONDITION_SATISFIED`: it became done while you waited;
- `GL_TIMEOUT_EXPIRED`: still not done when the timeout ran out;
- `GL_WAIT_FAILED`: an error.

A timeout of 0 makes it a **poll**: "is it done yet?" with no waiting at all.

The `flags` argument has one bit, `GL_SYNC_FLUSH_COMMANDS_BIT`, and it prevents a deadlock that
surprises everyone once. The driver collects commands in a command buffer and **submits** it to
the GPU only from time to time (when it fills, at a swap, or when asked). A fence still sitting in
an unsubmitted command buffer will never be signalled, because the GPU has never seen it. Waiting
for it with a long timeout would wait forever. The flush bit tells the driver "submit what you have
first, then wait".

In the engine, the ring places a fence after each frame's commands and waits on it three frames
later (§10).

### 6.2 Coherence: CPU writes become visible to later commands

Even when the CPU writes memory the GPU will read later, the bytes might still be sitting in the
CPU's write-combining buffers (§3.3), or in some cache the GPU does not look at. A **coherent**
mapping (`GL_MAP_COHERENT_BIT`) promises: **data the CPU wrote before issuing a command is visible
to that command**. The driver makes it so — for example by draining the write buffers whenever
it submits commands. Without the coherent bit you would call `glFlushMappedNamedBufferRange` over
the bytes you wrote (having mapped with `GL_MAP_FLUSH_EXPLICIT_BIT`), before issuing the commands
that read them.

Coherence is about *visibility*, not about *who is done*. It does nothing for commands issued
*before* your write that the GPU has not run yet; only fences protect those.

(For the opposite direction — a shader writes a mapped buffer and the CPU reads it — the rule is a
`glMemoryBarrier(GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT)` if the mapping is not coherent, then a fence,
then the read. The engine's rings only go CPU → GPU.)

### 6.3 Barriers: one GPU command waits for another's memory writes

Inside the GPU, commands *start* in order, but they overlap: the next draw may begin before the
previous one has finished, and the GPU has many caches that are not automatically kept in agreement
with each other. Most of the time the driver tracks what reads what and inserts the necessary waits
itself. It does **not** do that for writes shaders make to general memory (storage buffers, images
and atomic counters, written from any shader stage; the engine's are compute shaders): those are
called *incoherent* writes, and you must announce the dependency yourself:

```c
void glMemoryBarrier(GLbitfield barriers);
```

The bits name **how the later commands will read** the written data:

- `GL_SHADER_STORAGE_BARRIER_BIT`: later shaders reading storage buffers see the writes;
- `GL_COMMAND_BARRIER_BIT`: later *indirect* draws and dispatches, whose parameters the GPU's
  command processor reads from a buffer, see the writes;
- `GL_BUFFER_UPDATE_BARRIER_BIT`: later buffer copies, clears and `glNamedBufferSubData` see the
  writes — and, the part that is easy to forget, a copy or clear that *overwrites* memory a shader
  wrote waits for that write to finish;
- several more for textures, vertex fetching, uniform buffers and so on.

A barrier applies to **every command issued after it**, not only to the next one: a draw ten
commands later still sees the writes, as long as it reads them through a path named in the bits.

What happens in the hardware: the driver inserts commands that make the GPU wait until the earlier
shader work has finished writing, then flush the caches that hold those writes and invalidate the
caches the named readers use. That is why you name the reader: flushing everything would be
correct but slower. §15 shows the engine's barriers.

### 6.4 The summary table

| Mechanism | Who waits | For what | Engine's use |
|---|---|---|---|
| Fence + `glClientWaitSync` | the CPU | the GPU to finish every command before the fence | the ring reuses a region only after its frame's fence (§10) |
| Coherent mapping | nobody waits | CPU writes become visible to commands issued after them | staging bytes in the ring for a copy issued right after (§13) |
| `glMemoryBarrier` | later GPU commands | earlier shader writes to be finished and visible | culling with compute, then drawing from its output (§15) |

<details><summary>Check: a compute shader writes indirect draw commands into a buffer, and the next call draws from that buffer. Which mechanism is needed, and with which bit?</summary>

A memory barrier between them, with `GL_COMMAND_BARRIER_BIT`: the draw's parameters are read by
the command processor, which is the "command" reader. A fence would also make it correct but would
stall the CPU for no reason; coherence is about CPU writes and does not apply.
</details>

---

# Part B — Building it

## 7. Step 1: the buffer table

### 7.1 What we need to remember about a buffer

GL already names a buffer with an `int`. Why wrap it at all? Because the engine needs four more
things the name does not carry:

- its **size**, to check every write against it;
- the **flags** it was created with, to know what it may do;
- its **mapping**, if it has one, so that writers can reach it;
- protection against **stale names**: when you delete GL buffer 7, the driver will happily give
  the number 7 to the next buffer you create. Code still holding "7" from before would then
  silently write into someone else's buffer.

The engine's answer, used everywhere in it, is a **handle**: an `int` that packs a slot index
together with a *generation* counter (lesson 3 builds handles in detail; L00 §6.4 showed the
packing idea). When a slot is freed its generation goes up, so an old handle to that slot no longer
matches and is rejected instead of reaching the new occupant. The per-buffer data lives in
**parallel arrays** indexed by the handle's slot index — one array per field, the
"struct of arrays" layout lesson 3 explains — instead of one Java object per buffer.

*Why not a `GpuBuffer` class with four fields?* For a few dozen buffers, an object each would
be perfectly fast. The engine uses handles here for the same reasons it uses them everywhere:
an `int` can be stored in other arrays and in render packets without references, it can be
validated, and the whole engine then has one idea of identity rather than two.

### 7.2 The class and its fields

Start a file `GpuBuffers.java` in `dev.jeddy.render.gpu`:

```java
public final class GpuBuffers {
    /** Flag: {@link #update} may write the buffer ({@code GL_DYNAMIC_STORAGE_BIT}). */
    public static final int DYNAMIC = 1;

    /** Flag: persistently and coherently mapped for writing; {@link #mapping} returns the memory.
     *  Writers must not read it back (write-combined). */
    public static final int MAPPED = 2;

    private final GpuDevice device;
    private final HandlePool handles = new HandlePool(64, Handles.MAX_INDEX + 1);
    private int[] names = new int[64];
    private long[] sizes = new long[64];
    private int[] flags = new int[64];
    private MemorySegment[] mappings = new MemorySegment[64];

    GpuBuffers(GpuDevice device) {
        this.device = device;
    }
```

Line by line:

- **The engine's own flags.** Callers say what they intend — "I will write it from the CPU"
  (`DYNAMIC`), "I want a pointer to it" (`MAPPED`) — and the table translates that into GL's bits.
  The values are powers of two so they combine with `|` (L00 §6.2). Only these two exist because
  only these two patterns are used; a flag nobody needs is a combination nobody tests.
- **`HandlePool`** (in `core.handle`) hands out handles and validates them. `64` is the initial
  number of slots; `Handles.MAX_INDEX + 1` the most it may ever have.
- **Four parallel arrays**, one per field, all indexed by the slot index. `mappings` is the only
  array of objects: a `MemorySegment` per mapped buffer, `MemorySegment.NULL` otherwise.
- **The constructor is package-private**: only `GpuDevice` creates the table (one per GL
  context), and everything else reaches it through `device.buffers()`. The device also owns the
  **state cache**, which remembers what is bound so redundant binds are skipped (chapter 05).

The class's Javadoc states its **thread ownership**, as every public type in the engine must:
the GL context's thread, which is the main thread (L00 §10.5).

### 7.3 Creating a buffer

Two public overloads cover "empty" and "filled from native memory", and both lead to one private
method:

```java
/** Creates a buffer of {@code size} bytes, zero-filled or with undefined content. */
public int create(long size, int bufferFlags, String label) {
    return create(size, MemorySegment.NULL, bufferFlags, label);
}

/** Creates a buffer holding {@code data} (native memory). */
public int create(MemorySegment data, int bufferFlags, String label) {
    return create(data.byteSize(), data, bufferFlags, label);
}
```

The `label` is a human name ("materials", "scene ring") that debug tools will show. Now the
private method, in five steps.

**Step a — ask GL for a name.**

```java
private int create(long size, MemorySegment data, int bufferFlags, String label) {
    if (size <= 0) {
        throw new IllegalArgumentException("buffer size must be positive: " + size);
    }
    MemorySegment id = device.scratch();
    glCreateBuffers(1, id);
    int name = id.get(ValueLayout.JAVA_INT, 0);
```

`glCreateBuffers(GLsizei n, GLuint *buffers)` is a C function with an **out-parameter**: you pass
a pointer to memory and it writes the new names there. From Java that pointer must be native memory
(L00 §8.3). Allocating a fresh segment for every call would be wasteful, so the device keeps one
small **scratch** segment for exactly this kind of call and the code reads the `int` back out of it.
(The size check comes first because GL rejects a zero size with an error that would only surface
later in the debug log.)

**Step b — translate the flags and create the storage.**

```java
    int storage = 0;
    if ((bufferFlags & DYNAMIC) != 0) {
        storage |= GL_DYNAMIC_STORAGE_BIT;
    }
    if ((bufferFlags & MAPPED) != 0) {
        storage |= GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT;
    }
    glNamedBufferStorage(name, size, data, storage);
```

This is §4's table in code. `MAPPED` sets all three mapping bits because the engine only ever
wants one kind of mapping: written by the CPU, kept for life, visible to later commands without
extra calls. `data` may be `MemorySegment.NULL`, which the binding passes to C as a null pointer:
"no initial contents".

**Step c — map it, if asked.**

```java
    MemorySegment mapping = MemorySegment.NULL;
    if ((bufferFlags & MAPPED) != 0) {
        mapping =
                glMapNamedBufferRange(
                                name,
                                0,
                                size,
                                GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT)
                        .reinterpret(size);
        if (mapping.address() == 0) {
            glDeleteBuffers(1, id);
            throw new IllegalStateException("glMapNamedBufferRange failed for " + label);
        }
    }
```

- The whole buffer is mapped, once (`offset 0, length size`).
- **`reinterpret(size)`**: a pointer returned from C arrives in Java as a segment of length 0,
  because FFM cannot know how many bytes are behind it. `reinterpret` declares the real size, so
  later `set` calls are bounds-checked against it. It is a *restricted* method (a wrong size
  would let Java write outside the buffer), which is why the engine's modules are granted native
  access.
- **Failure.** A null pointer means the driver could not map (out of mappable memory, for
  example). The buffer is deleted again before throwing — `id` still holds its name — so a
  failed creation leaks nothing.

**Step d — give it a name debug tools can show.**

```java
    if (label != null) {
        GLDebug.label(GL_BUFFER, name, label);
    }
```

`GLDebug.label` calls `glObjectLabel`, after which the driver's debug messages and GPU debuggers
such as RenderDoc say "materials" instead of "buffer 23". On the day of §17 this is what made the
driver's messages readable.

**Step e — record it under a handle.**

```java
    int h = handles.allocate();
    int i = Handles.index(h);
    if (i >= names.length) {
        int cap = Math.max(i + 1, names.length * 2);
        names = Arrays.copyOf(names, cap);
        sizes = Arrays.copyOf(sizes, cap);
        flags = Arrays.copyOf(flags, cap);
        mappings = Arrays.copyOf(mappings, cap);
    }
    names[i] = name;
    sizes[i] = size;
    flags[i] = bufferFlags;
    mappings[i] = mapping;
    return h;
}
```

The arrays grow by doubling when a new slot index passes their end. Doubling means the total work
of all the copies stays proportional to the number of buffers (each element is copied about once on
average), and it allocates — which is fine, because buffers are created while loading, never per
frame.

### 7.4 Destroying a buffer

```java
/** Deletes the buffer (unmapping it first). */
public void destroy(int handle) {
    int i = check(handle);
    if (mappings[i].address() != 0) {
        glUnmapNamedBuffer(names[i]);
    }
    device.state().forgetBuffer(names[i]);
    MemorySegment id = device.scratch();
    id.set(ValueLayout.JAVA_INT, 0, names[i]);
    glDeleteBuffers(1, id);
    mappings[i] = null;
    names[i] = 0;
    handles.free(handle);
}
```

- **Unmap first**: deleting a mapped buffer is legal, but unmapping says explicitly that no CPU
  pointer to it survives.
- **`forgetBuffer`**: the state cache may remember "buffer 23 is bound to slot 1". Once 23 is
  deleted and the driver reuses the number, the cache would wrongly skip binding the *new* 23.
  Forgetting it keeps the cache honest.
- **`glDeleteBuffers` takes an array** in C, so the name goes through the scratch segment again.
- **What if frames in flight still read it?** GL defines deletion as "the name goes away now,
  the memory goes away when no pending command uses it". The driver keeps the storage alive until
  the GPU is done. `MaterialTable.grow` (§14.3) relies on this.
- Finally the handle is freed, which bumps the slot's generation: any copy of the old handle now
  fails `check`.

### 7.5 Reading and writing through a handle

```java
public int glName(int handle) {
    return names[check(handle)];
}

public long size(int handle) {
    return sizes[check(handle)];
}

/** Returns the persistent write mapping ({@link #MAPPED} buffers), else {@code NULL}. */
public MemorySegment mapping(int handle) {
    return mappings[check(handle)];
}

public void update(int handle, long offset, long address, long length) {
    int i = check(handle);
    if (offset < 0 || length < 0 || offset + length > sizes[i]) {
        throw new IndexOutOfBoundsException("update outside the buffer");
    }
    glNamedBufferSubData(names[i], offset, length, address);
}

public void update(int handle, long offset, MemorySegment data) {
    update(handle, offset, data.address(), data.byteSize());
}

private int check(int handle) {
    if (!handles.isValid(handle)) {
        throw new IllegalArgumentException("stale buffer handle " + Handles.toString(handle));
    }
    return Handles.index(handle);
}
```

- **`check`** is the stale-handle guard: a freed or never-allocated handle throws at once, with
  its index and generation in the message, instead of touching the wrong buffer.
- **`update`** is the first way of §5, the CPU write through the driver. It checks the range
  itself, because GL's own response to an out-of-range write is an error flag that is easy to
  miss. It takes a raw `long` **address**, not a `MemorySegment`: the generated GL bindings offer a
  `long` overload of data parameters like this one (D29), so a caller who has an address does not need to
  create a segment object for it — `update` runs on frames, and must not allocate.
- Its Javadoc in the repository carries a warning added after §17: *for a buffer the GPU has
  already read, prefer `FrameRing.upload`*. You will see why.

<details><summary>Check: a system keeps a buffer handle in a field, the buffer is destroyed, and a new buffer is created that receives the same GL name. What happens when the system calls <code>update</code> with its old handle?</summary>

`check` fails: the slot's generation was bumped by `destroy`, so the old handle's generation does
not match, and `update` throws "stale buffer handle". Without handles, the old GL name would have
written into the new buffer silently.
</details>

## 8. Step 2: designing the ring

### 8.1 Requirements

From §1–6, the per-frame memory must:

1. be writable by **any thread**, without GL calls → a persistent mapping;
2. never be written while a command **in flight** reads it → a fence per piece of memory a frame
   uses, and a wait on it (rarely) before reuse;
3. need **no allocation and normally no waiting** → memory reused frame after frame;
4. hand out **aligned offsets**, because GL requires buffer bindings to start at multiples of
   an alignment the driver reports (§9.3).

### 8.2 Alternatives

| Design | Why not |
|---|---|
| One buffer, rewritten each frame with `glNamedBufferSubData` | Every write is a driver call on the main thread (fails requirement 1), and the driver must copy or stall to protect the frame in flight (§5) |
| A new buffer every frame | Allocation in the driver every frame, a new name to bind every frame, and deleting old ones |
| `frames` separate mapped buffers, one per frame | Works. But it is the same memory split into objects: one name per frame to bind and track. One buffer with regions is simpler for the same behaviour |
| One circular stream with a moving head and a fence per chunk | Needed when the amount per frame varies wildly (the asset `UploadRing` works this way). Per-frame data is naturally "one frame's worth", so fixed regions are simpler |
| **One buffer, `frames` equal regions, one fence per region, a bump allocator inside** | **Chosen** |

A **bump allocator** is the simplest allocator there is: a cursor that starts at 0 and moves
forward by each request's size. It never frees individual pieces; it frees everything at once by
moving the cursor back to 0. That is exactly the lifetime of per-frame data: all of it dies
together when the frame's region is reused.

### 8.3 How many regions: why three

Frame $n$ writes region $n \bmod R$ (L00 §2.4) and must first wait for the fence placed after
frame $n - R$, the last frame that used that region. So with $R$ regions, up to $R - 1$ earlier
frames may be in flight while the CPU writes frame $n$ without waiting.

How many frames are typically in flight? When the GPU is the slower side, one frame is being
executed and the next is queued behind it in the driver:

```
                           CPU writes frame n into region n mod R
GPU executing: n-2         queued: n-1
```

- With **$R = 2$**, frame $n$ needs frame $n - 2$ finished. In the picture above it is still
  executing, so the CPU waits — and while it waits it cannot prepare the next frame, so the GPU
  will find its queue nearly empty when it finishes. CPU and GPU end up taking turns, and any
  hiccup on either side (a slow tick, a long frame) is passed straight to the other.
- With **$R = 3$**, frame $n$ needs frame $n - 3$ finished, which in the picture is long done.
  The CPU writes freely; there is a frame of slack on each side.
- With **$R = 4$** or more, the CPU could run even further ahead. But every extra frame in flight
  is also an extra frame between reading the player's input and showing its result (**latency**),
  and one more region of memory. The driver itself usually queues at most two or three frames, so
  more regions would sit unused.

Three is the same balance swap chains strike with "triple buffering". The ring counts the times it
did have to wait (`stalls()`) and how long (`stallNanos()`); `RingsTest` checks the count, and
exercise 6 prints it from the demo.

### 8.4 How big

A region must hold everything one frame allocates. The scene's ring adds up instance records (64
bytes each), indirect commands (20 bytes each), lights, decals, per-frame constants and staged
uploads. The demo's ~1,041 instances are about 65 KiB; the default region is generous at 32 MiB
(`32L << 20` in `SceneRenderer.Config.DEFAULT`), so three regions are 96 MiB of mapped memory.
The user interface and the debug overlay have their own rings of 4 MiB per frame.

When a frame does run out, the allocator returns −1 and the caller skips that work for the frame:
a missing frame of user interface is better than a stall or an exception in the middle of drawing.

## 9. Step 3: the ring's fields and constructor

Create `FrameRing.java` beside `GpuBuffers`:

```java
public final class FrameRing implements AutoCloseable {
    private static final VarHandle CURSOR;

    static {
        try {
            CURSOR = MethodHandles.lookup().findVarHandle(FrameRing.class, "cursor", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final GpuBuffers buffers;
    private final int buffer;
    private final int glName;
    private final MemorySegment memory;
    private final long regionSize;
    private final int frames;
    private final long[] fences;
    private int frame = -1;
    private long regionBase;
    private volatile long cursor;
    private long stalls;
    private long stallNanos;
```

### 9.1 The fields, one by one

- **`buffer`, `glName`, `memory`**: the buffer's handle in the table, its GL name (bound by every
  user of the ring) and its mapping, looked up once and kept, so a frame never asks the table.
- **`regionSize`, `frames`**: the size of one region and how many there are.
- **`fences`**: one per region. A GL sync object is a C pointer; the generated bindings return it
  as a plain `long` address rather than a `MemorySegment`, so storing and passing fences allocates
  nothing (D29). `0` means "no fence".
- **`frame`**: the current region's index. It starts at −1 so the first `beginFrame` moves it to 0.
- **`regionBase`**: the byte offset of the current region inside the buffer, `frame * regionSize`.
- **`cursor`**: the bump allocator's position *within* the current region. It is the one field
  several threads change at once, so it is **`volatile`** (L00 §9.5) and changed only through
  compare-and-set.
- **`stalls`, `stallNanos`**: how often and how long `beginFrame` had to wait for the GPU.

### 9.2 Why a `VarHandle` for the cursor

Compare-and-set on a field needs either an `AtomicLong` object or a **`VarHandle`**, a typed
reference to a field that offers atomic operations on it in place. `AtomicLong` would put the
counter in a separate object: one more allocation at construction and one more pointer to follow on
every allocation. The `VarHandle` works on `cursor` itself. It is looked up once, in the static
initializer, and kept in a `static final` field, which the JIT treats as a constant and compiles
down to the CPU's own compare-and-swap instruction (`lock cmpxchg` on x86). The `try`/`catch` only
exists because the lookup is declared to throw; the field is right there, so it never does.

### 9.3 The constructor

```java
public FrameRing(GpuDevice device, long bytesPerFrame, int frames, String label) {
    this.buffers = device.buffers();
    this.frames = frames;
    long alignment = Math.max(256, device.caps().uniformBufferOffsetAlignment());
    this.regionSize = (bytesPerFrame + alignment - 1) / alignment * alignment;
    buffer = buffers.create(regionSize * frames, GpuBuffers.MAPPED, label);
    glName = buffers.glName(buffer);
    memory = buffers.mapping(buffer);
    fences = new long[frames];
}
```

**The alignment.** Users of the ring bind ranges of it to shader binding points: "storage buffer
binding 0 is bytes 1,048,576 to 1,115,200 of the ring". GL requires the start of such a range to be
a multiple of a value the driver reports: `GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT` for uniform buffers
(256 on most desktop GPUs), `GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT` for storage buffers (often
smaller). The reason is in the hardware: a buffer binding becomes a small descriptor holding the
start address, and GPUs store and fetch those starts in aligned units. Each allocation is aligned
by the allocator (§11), but the *regions* must start aligned too, or "aligned within the region"
would not mean aligned within the buffer. So the region size is rounded up to the alignment with
§2.5's formula of L00, using at least 256 so the layout is the same on every driver.

Worked example: `bytesPerFrame = 32L << 20` = 33,554,432 is already a multiple of 256, so the
region stays 33,554,432 and the buffer is $3 \times 33{,}554{,}432 = 100{,}663{,}296$ bytes. A
request of 1,000,000 bytes would become $\lceil 1{,}000{,}000 / 256 \rceil \cdot 256 =
3907 \cdot 256 = 1{,}000{,}192$.

Then one mapped buffer for all regions, its name, its mapping, and an array of fences, all zero.

## 10. Step 4: beginFrame — wait only if you must

```java
/**
 * Starts a frame: waits until the GPU has finished with this frame's region (normally it has),
 * then resets the region's allocator. Allocates nothing.
 */
public void beginFrame() {
    frame = (frame + 1) % frames;
    long fence = fences[frame];
    if (fence != 0) {
        int status = glClientWaitSync(fence, 0, 0);
        if (status == GL_TIMEOUT_EXPIRED) {
            long t0 = System.nanoTime();
            stalls++;
            do {
                status = glClientWaitSync(fence, GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000_000L);
            } while (status == GL_TIMEOUT_EXPIRED);
            stallNanos += System.nanoTime() - t0;
        }
        glDeleteSync(fence);
        fences[frame] = 0;
    }
    regionBase = frame * regionSize;
    CURSOR.setVolatile(this, 0L);
}
```

Line by line:

1. **Next region**: 0, 1, 2, 0, 1, 2, … (L00 §2.4).
2. **Is there a fence?** The first `frames` frames find none: nothing has used the region yet.
3. **Poll first** (`flags 0, timeout 0`). In the normal case the GPU finished this region's frame
   long ago and the answer is `GL_ALREADY_SIGNALED`: no waiting, and no flush either — a flush
   submits the driver's pending commands early, which is not free.
4. **Only if it is not done**: count a stall, then wait with `GL_SYNC_FLUSH_COMMANDS_BIT` (so the
   fence is guaranteed to have been submitted, §6.1) and a one-second timeout, **in a loop**. The
   loop exists because a timeout is not an error: a GPU can legitimately be slow (a driver
   recompiling a shader, the machine under load). A wait that gave up after one second and wrote
   into the region anyway would corrupt the frame in flight; the loop keeps waiting. Any other
   status (`ALREADY_SIGNALED`, `CONDITION_SATISFIED`, or `WAIT_FAILED`) ends the loop.
5. **Measure the stall** with `System.nanoTime()` (a clock read, no allocation).
6. **Delete the fence**: a sync object is a driver resource; each frame makes a new one (§12), so
   the old one must go.
7. **Reset the allocator**: the region starts at `frame * regionSize`, and the cursor goes back to
   0. `setVolatile` publishes the reset to any thread that reads the cursor afterwards.

When is the CPU allowed to call this? Only on the main thread, and only when no worker is still
allocating from the previous frame — the job system finishes the frame's work before the renderer
moves on. That ordering is part of the class's documented thread ownership, and it is what makes
the plain (non-atomic) fields `frame` and `regionBase` safe.

## 11. Step 5: allocate — a lock-free bump allocator

```java
/**
 * Reserves {@code bytes} at {@code alignment} (a power of two) in this frame's region. Returns
 * the offset in the buffer (for binding and indirect offsets; the same offset into {@link
 * #memory()}), or -1 if the region is full. Lock-free.
 */
public long allocate(long bytes, long alignment) {
    while (true) {
        long c = cursor;
        long start = (c + alignment - 1) & -alignment;
        long end = start + bytes;
        if (end > regionSize) {
            return -1;
        }
        if (CURSOR.compareAndSet(this, c, end)) {
            return regionBase + start;
        }
    }
}
```

### 11.1 The arithmetic

`(c + alignment - 1) & -alignment` rounds the cursor up to the next multiple of `alignment`; L00
§6.6 derived why. It needs `alignment` to be a power of two, which the Javadoc requires.

Worked example. The cursor is at 300 and a caller asks for 1,000 bytes at alignment 256:
`start = (300 + 255) & -256 = 555 & ...1111 0000 0000 = 512`, `end = 1512`. The 212 bytes from
300 to 511 are **padding**, skipped and never used. The same request at alignment 16 would get
`start = (300 + 15) & -16 = 304`: only 4 bytes of padding.

The returned value is `regionBase + start`, an offset into the **whole buffer**. That one number
works twice: as the offset to bind (`glBindBufferRange(..., ring, offset, size)`) and as the offset
into `memory()` to write at.

### 11.2 Why a compare-and-set loop

Several worker threads call `allocate` at the same time. Suppose two threads both read `cursor =
300` and both compute `end = 1512`. If each then simply wrote `cursor = 1512`, both would get
`start = 512` — the same bytes — and overwrite each other's records.

`compareAndSet(this, c, end)` makes the update conditional: *set the cursor to `end` only if it
still holds `c`*. Only one of the two threads can succeed; the CPU guarantees it. The loser sees
`false`, goes around the loop, reads the new cursor (1512), and allocates after it:

```
thread A: c = 300, start = 512, end = 1512, CAS(300 → 1512) succeeds → returns base + 512
thread B: c = 300, start = 512, end = 1512, CAS(300 → 1512) fails (cursor is 1512 now)
thread B: c = 1512, start = 1536, end = 2536, CAS(1512 → 2536) succeeds → returns base + 1536
```

Why the ranges can never overlap: every successful CAS moves the cursor from the value its thread
read to the end of its own range, and the next successful CAS must have read that new value, so its
range starts at or after it. The successful allocations form a chain, each starting where the
previous ended (plus padding).

This is **lock-free**: no thread ever waits for another to release a lock; a thread that loses a
race simply retries, and *some* thread always makes progress. A `synchronized` method would also be
correct, but a thread holding the lock could be paused by the operating system and stall every
other worker behind it.

### 11.3 Two details

- **A full region does not move the cursor.** The size check returns −1 *before* the CAS, so a
  request too big to fit does not use up space; a smaller request afterwards may still fit.
- **No ABA problem.** The classic trap of CAS loops is a value that changes from A to B and back to
  A between your read and your CAS, so the CAS succeeds when it should not. Within a frame the
  cursor only ever grows; it goes back to 0 only in `beginFrame`, which never runs while workers
  allocate.

## 12. Step 6: endFrame, the accessors and close

```java
/** Ends the frame: fences the region after the commands that read it. */
public void endFrame() {
    fences[frame] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
}
```

One line, and its *position* is the whole point: the renderer calls `endFrame` after it has issued
every command that reads this frame's region. The fence therefore sits behind all of them in the
stream, and "the fence is signalled" means "every reader of this region is done". Three frames
later, `beginFrame` checks exactly that.

The accessors expose what users need, and nothing else (condensed here to one line each; the file
gives each its Javadoc):

```java
public MemorySegment memory() { return memory; }      // the whole mapping (write only)
public int glName() { return glName; }                // to bind ranges of it
public long used() { return cursor; }                 // bytes allocated this frame, padding included
public long regionSize() { return regionSize; }
public long stalls() { return stalls; }
public long stallNanos() { return stallNanos; }
```

And `close`, for shutdown:

```java
@Override
public void close() {
    for (int i = 0; i < frames; i++) {
        if (fences[i] != 0) {
            glClientWaitSync(fences[i], GL_SYNC_FLUSH_COMMANDS_BIT, 5_000_000_000L);
            glDeleteSync(fences[i]);
            fences[i] = 0;
        }
    }
    buffers.destroy(buffer);
}
```

It waits (up to five seconds each) for every frame still in flight, then destroys the buffer. GL
would keep the storage alive for pending commands anyway (§7.4); waiting makes shutdown orderly and
lets the fences be deleted. `FrameRing` is `AutoCloseable`, so tests use it in try-with-resources.

## 13. Step 7: upload — changing a buffer the GPU is reading

Everything so far handles **per-frame** data: written once, read by one frame, forgotten. Some
buffers live for the whole game and are read by *every* frame, yet change now and then. The
material table is the main one: a storage buffer with one 128-byte row per material (32 floats:
colors, roughness, texture layers), read by every draw. A new row arrives whenever a material is
loaded or a texture finishes streaming in — while three frames that read the table are in flight.

Go back to §5's three ways:

- A **mapped write** into the table has no place in the stream: a frame in flight could read a row
  half-written. And the table would need to be persistently mapped for life, which pins it in
  CPU-visible memory.
- A **CPU write through the driver** (`glNamedBufferSubData`) is correct *by the specification*.
  The engine used it. It is also the path one real driver got wrong (§17).
- A **GPU copy** is correct *by construction*. Put the new bytes somewhere no frame in flight reads
  (this frame's ring region!), then issue a copy command. The copy has a place in the stream: after
  every command of the earlier frames, which still see the old row, and before this frame's draws,
  which see the new one.

That is `upload`:

```java
public boolean upload(
        int dstGlName, long dstOffset, MemorySegment src, long srcOffset, long bytes) {
    long at = allocate(bytes, 16);
    if (at < 0) {
        return false;
    }
    MemorySegment.copy(src, srcOffset, memory, at, bytes);
    glCopyNamedBufferSubData(glName, dstGlName, at, dstOffset, bytes);
    return true;
}
```

1. **Reserve staging space** in this frame's region. GL does not require any alignment for buffer
   copies; 16 keeps the source on a boundary that copies quickly.
2. **Stage the bytes** with `MemorySegment.copy`: a sequential write into the mapping, the shape
   write-combined memory likes (§3.3).
3. **Issue the copy**: `glCopyNamedBufferSubData(readBuffer, writeBuffer, readOffset, writeOffset,
   size)`, from the ring to the destination.
4. **Report a full region** with `false`, so the caller can decide (the material table falls back
   to the CPU write; §14.3).

Three promises from §6 combine to make this correct, and it is worth checking each:

| Hazard | What prevents it |
|---|---|
| The staging bytes are overwritten while the copy has not run yet | They live in this frame's region, which is not reused until this frame's fence signals — and the fence comes after the copy |
| The copy reads staging bytes the CPU wrote but the GPU cannot see yet | The mapping is coherent and the CPU wrote them *before issuing* the copy (§6.2) |
| An earlier frame's draw reads the destination after the copy changed it | The copy is a command; GL orders it after earlier commands that read the destination (§2.3) |
| This frame's draw reads the destination before the copy finished | Buffer copies are not "incoherent shader writes"; GL orders them against later reads automatically, no barrier needed (§6.3) |
| The copy overwrites memory a *shader* wrote earlier (not the material table, which only copies write; the light counter of §14.4) | A `glMemoryBarrier` with `GL_BUFFER_UPDATE_BARRIER_BIT` between that shader and the copy (§6.3) |

This is decision **D103**, taken on the day of §17.

# Part C — Using it and proving it

## 14. Who writes into the ring

### 14.1 Instance records, from many threads

[`DrawBuilder`](../../../modules/render/src/main/java/dev/jeddy/render/draw/DrawBuilder.java)
turns the visible objects of a frame into records and draw commands (chapter 06 explains the
sorting and batching; here we follow the bytes). Once it knows how many records (`n`) and how many
commands (`runs`) it needs, it reserves both in one go on the main thread:

```java
instanceBytes = (long) n * INSTANCE_BYTES;                        // 64 bytes per record
instanceOffset = ring.allocate(instanceBytes, 256);
long commandOffset = ring.allocate((long) runs * COMMAND_BYTES, 16); // 20 bytes per command
if (instanceOffset < 0 || commandOffset < 0) {
    itemCount = 0;
    return false;                                                  // region full: draw nothing this frame
}
```

Then the job system splits the records between workers. Each worker gets a range `[start, end)`
of record numbers and writes record `k` at `instanceBase + k * 64`. The ranges are disjoint, so no
further coordination is needed — the CAS loop of §11 was paid once, for the whole block:

```java
private void writeRecords(int start, int end, int thread) {
    RenderPacket p = packet;
    MemorySegment m = memory;
    float[] prev = p.prevWorld;
    float[] curr = p.currWorld;
    float a = p.alpha;
    for (int k = start; k < end; k++) {
        int item = order[k];
        int instance = visible[itemInstance[item]];
        int w = instance * 12;
        long o = instanceBase + (long) k * INSTANCE_BYTES;
        for (int j = 0; j < 12; j++) {
            float x = prev[w + j];
            m.set(F32, o + 4L * j, x + (curr[w + j] - x) * a);
        }
        m.set(I32, o + 48, itemMaterial[item]);
        m.set(I32, o + 52, p.flags[instance]);
        m.set(I32, o + 56, p.entity[instance]);
        m.set(F32, o + 60, p.fade[instance]);
    }
}
```

- **The transform** is 12 floats: a 3×4 matrix, row by row (chapter 02). The simulation runs at 30
  ticks a second and the screen faster, so each float is **interpolated** between the last two
  ticks: $x + (x_{\text{curr}} - x) \cdot a$, where $a$ (the packet's `alpha`, from 0 to 1) says
  how far the frame is between them. At $a = 0$ it is the previous value, at $a = 1$ the current.
- **The record's layout**, 64 bytes:

  ```
  offset  0 ..47   row0 (4 floats), row1, row2      the 3×4 transform
  offset 48        material index (int)
  offset 52        flags (int)
  offset 56        entity (int, for picking)
  offset 60        camera fade (float)
  ```

  Every value is computed in registers and stored once, in order: no reads of the mapping (§3.3).
- **The shader side** declares the same 64 bytes as a struct and finds its record by number:

  ```glsl
  struct JeddyInstance {
      vec4 row0;
      vec4 row1;
      vec4 row2;
      uvec4 data; // x material, y flags, z entity, w camera fade (float bits)
  };
  layout(std430, binding = 0) readonly buffer JeddyInstances {
      JeddyInstance jeddyInstances[];
  };
  JeddyInstance jeddyInstance() {
      return jeddyInstances[gl_BaseInstance + gl_InstanceID];
  }
  ```

  `std430` is a layout rule set that makes a `vec4` 16 bytes with no gaps, so the GLSL struct is
  exactly the 64 bytes Java wrote. The fade is written as a float and read as `uint` bits (the
  shader converts it back with `uintBitsToFloat`).
- **The commands**, five `int`s each (`count`, `instanceCount`, `firstIndex`, `baseVertex`,
  `baseInstance`), are GL's `DrawElementsIndirectCommand`. `baseInstance` is the number of the
  first record of the run, which is how `gl_BaseInstance + gl_InstanceID` lands on the right record.

Finally, on the main thread, `submit` binds the ring's ranges and draws:

```java
state.bindStorage(INSTANCE_BINDING, ring.glName(), instanceOffset, instanceBytes);
state.bindStorage(MATERIAL_BINDING, materials.glBuffer(), 0, materials.bufferBytes());
state.bindDrawIndirect(ring.glName());
...
glMultiDrawElementsIndirect(
        GL_TRIANGLES, GL_UNSIGNED_INT, batchOffset[b], batchCommands[b], COMMAND_BYTES);
```

`instanceOffset` is a multiple of 256 because it was allocated at that alignment, which satisfies
the storage-buffer binding rule on every driver. The draw reads its commands at `batchOffset[b]`,
an offset into the ring (bound as the indirect buffer).

### 14.2 What happens at the frame's edges

The renderer's frame is: `ring.beginFrame()` → workers write → main thread issues commands →
`ring.endFrame()` → swap. Nothing in between waits for the GPU, allocates, or calls GL from a
worker. That is the whole contract.

### 14.3 The material table: a long-lived buffer changed in flight

[`MaterialTable`](../../../modules/render/src/main/java/dev/jeddy/render/draw/MaterialTable.java)
keeps the rows in two places: a **CPU copy** in native memory, which is the truth, and the GPU
buffer, which follows it.

```java
public static final int FLOATS = 32;           // floats per row: 128 bytes

private MemorySegment cpu;                      // every row, in native memory
private int buffer;                             // the GPU copy (GpuBuffers.DYNAMIC)
private int dirtyFrom = Integer.MAX_VALUE;      // the lowest changed row, or MAX when clean
private int dirtyTo = -1;                       // the highest changed row, or -1 when clean
```

Changing a row writes the CPU copy and widens the **dirty range**:

```java
/** Replaces a material's row. */
public void set(int material, float[] params) {
    MemorySegment.copy(
            params, 0, cpu, ValueLayout.JAVA_FLOAT, (long) material * FLOATS * 4, FLOATS);
    dirtyFrom = Math.min(dirtyFrom, material);
    dirtyTo = Math.max(dirtyTo, material);
}
```

Once per frame, before drawing, the renderer flushes:

```java
public void flush(FrameRing ring) {
    if (dirtyTo < 0) {
        return;                                                 // nothing changed: no work at all
    }
    long from = (long) dirtyFrom * FLOATS * 4;
    long length = (long) (dirtyTo - dirtyFrom + 1) * FLOATS * 4;
    if (ring == null || !ring.upload(glBuffer(), from, cpu, from, length)) {
        device.buffers().update(buffer, from, cpu.address() + from, length);
    }
    dirtyFrom = Integer.MAX_VALUE;
    dirtyTo = -1;
}
```

- **One range, one copy.** Ten materials loaded in one frame become one `upload` of the rows from
  the lowest to the highest changed one. The price is re-sending unchanged rows in between; the
  gain is one command instead of ten. Rows are 128 bytes, so even a range of a thousand is 128,000
  bytes (125 KiB).
- **The fallback.** No ring (setup, tests, where no frame has read the buffer yet) or a full region
  → the CPU write. The bytes stay correct either way; only the path differs.
- **The `(long)` casts** come before the multiplications, so a large row number cannot overflow
  `int` (L00 §4.3).

When the table runs out of rows it grows, and it must replace the GPU buffer:

```java
private void grow() {
    int cap = capacity * 2;
    MemorySegment bigger = arena.allocate((long) cap * FLOATS * 4, 16);
    bigger.copyFrom(cpu);
    cpu = bigger;
    device.buffers().destroy(buffer);
    buffer = device.buffers().create(cpu.byteSize(), GpuBuffers.DYNAMIC, "materials");
    slotOfMaterial = Arrays.copyOf(slotOfMaterial, cap);
    capacity = cap;
    dirtyFrom = 0;
    dirtyTo = count - 1;
}
```

Destroying a buffer that frames in flight still read is safe because GL keeps its storage until
they finish (§7.4). The new buffer is empty, so every row is marked dirty and the next flush sends
the whole table. Its first fill could use the CPU write safely (no frame has read it); going
through `flush(ring)` like every other change keeps one path. (The arena here is a confined arena
owned by the table; the old CPU copy is freed with it at close — growth is rare and happens while
loading.)

One more detail, which exercise 5 explains: `submit` binds the material buffer **whole**
(`materials.bufferBytes()`, the buffer's size), not just the rows in use, so the bound range never
changes as rows are added.

### 14.4 A counter the GPU increments

The light clusters (chapter 07) sort lights into a 3D grid on the GPU. A compute shader appends to
a list with an atomic counter at the start of a buffer, and each frame the counter must go back
to zero before the compute pass runs. Before D103 this was a `glNamedBufferSubData` of the list's
16-byte header. Now:

```java
header.set(ValueLayout.JAVA_INT, 0, 0);            // the counter
header.set(ValueLayout.JAVA_INT, 4, capacity);     // the list's capacity
// The item counter resets on the GPU, ordered after last frame's cull (D103).
if (!ring.upload(device.buffers().glName(itemsBuffer), 0, header, 0, header.byteSize())) {
    device.buffers().update(itemsBuffer, 0, header);
}
```

The reset must land *after* the previous frame's compute pass finished counting and *before* this
frame's starts. Only a command in the stream sits exactly there. And because the counter was last
written by shader atomics — incoherent writes (§6.3) — GL also asks for a barrier with
`GL_BUFFER_UPDATE_BARRIER_BIT` between that pass and the copy, or the copy may overwrite the counter
while the atomics are still landing. The cull pass ends with that barrier:

```java
glDispatchCompute(groups, 1, 1);
// Storage: the shading pass reads the lists. Buffer update: next frame's counter reset is
// a buffer copy over memory these atomics wrote, which GL orders only after this bit.
glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT);
```

(The buffer-update bit was added when this lesson was reviewed against the specification. It had
always worked without it, because a whole frame of other commands separates the two, but the
specification does not promise that.)

## 15. Ordering inside the GPU: barriers in GPU culling

The static parts of a level (walls, floors, props that never move) are culled on the GPU by
[`StaticScene`](../../../modules/render/src/main/java/dev/jeddy/render/draw/StaticScene.java):
a compute pass tests every object against the camera and writes the visible ones' records and
counts; a second compute pass packs the non-empty draw commands; then the draws read those
commands. The code, with the passes' setup left out:

```java
// Reset the counts: the template has no instances; batches have no packed commands.
glCopyNamedBufferSubData(device.buffers().glName(templateBuffer), commands, 0, 0, commandsBytes);
glClearNamedBufferSubData(commands, GL_R32UI, countsOffsetBytes, (long) batchCount * 4,
        GL_RED_INTEGER, GL_UNSIGNED_INT, MemorySegment.NULL);
...
glDispatchCompute((itemCount + GROUP - 1) / GROUP, 1, 1);       // pass 1: cull
glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
...
glDispatchCompute((commandCount + GROUP - 1) / GROUP, 1, 1);    // pass 2: compact
glMemoryBarrier(
        GL_COMMAND_BARRIER_BIT
                | GL_SHADER_STORAGE_BARRIER_BIT
                | GL_BUFFER_UPDATE_BARRIER_BIT);
// later: glMultiDrawElementsIndirectCount(...) reads the commands and their count
```

Read each barrier as "who reads, later, what the shaders before me wrote":

- **After pass 1**, the shader-storage bit. Pass 2 reads pass 1's counts as a storage buffer — and
  so, much later, do the vertex shaders of the draws, which read the instance records pass 1 wrote.
  One barrier covers both, because a barrier applies to every command after it (§6.3).
- **After pass 2**, three bits. The command bit, because the GPU's **command processor** fetches
  the draw parameters and the number of draws from the buffer (`glMultiDrawElementsIndirectCount`,
  GL 4.6). The shader-storage bit, for any later shader that reads pass 2's output as a storage
  buffer. And the buffer-update bit, because the *next* time this view is culled, the copy and the
  clear at the top overwrite what these passes wrote (§14.4 has the same pattern; this bit was also
  added by the lesson's review).
- `(itemCount + GROUP - 1) / GROUP` is §2.5 of L00 again: enough groups of `GROUP` invocations to
  cover every item, rounding up.

Notice the counts are reset with `glCopyNamedBufferSubData` and `glClearNamedBufferSubData`:
commands, in the stream, not CPU writes. The same principle as §13.

**Forgetting a barrier** is the GPU's data race. Many drivers insert a full flush between compute
dispatches anyway, so the bug hides; another driver, or the same one after an update, overlaps the
passes and the draws read half-written commands — flickering geometry that appears on one machine
only. The engine's frame graph (chapter 06) carries the barrier bits per pass so a pass declares
what the next one reads.

## 16. Testing the ring

[`RingsTest`](../../../modules/render/src/test/java/dev/jeddy/render/gpu/RingsTest.java) runs
against a real GL context (tagged `gpu`: `./gradlew :render:test "-Pjeddy.gpuTests=true"`). Read
it as the specification of §9–12:

```java
try (FrameRing ring = new FrameRing(device, 64 << 10, 3, "frame ring")) {
    for (int f = 0; f < 10; f++) {
        ring.beginFrame();
        long a = ring.allocate(1000, 256);
        long b = ring.allocate(1000, 256);
        assertEquals(0, a % 256);                              // aligned
        assertTrue(b >= a + 1000);                             // disjoint
        assertEquals((f % 3) * ring.regionSize(), a);          // region f mod 3, first byte
        ring.memory().set(ValueLayout.JAVA_INT, b, f);         // writable
        ring.endFrame();
    }
    ring.beginFrame();
    assertEquals(-1, ring.allocate(ring.regionSize() + 1, 16)); // too big: -1, no exception
```

Then four threads allocate 1,000 blocks of 16 bytes each at the same time, and the test sorts all
4,000 offsets and checks each is at least 16 past the previous one: no two threads ever received
overlapping memory (§11.2).

```java
Allocations.assertNoAllocation(
        "frame ring",
        () -> {
            ring.beginFrame();
            ring.allocate(64, 64);
            ring.endFrame();
        });
```

proves a frame of the ring allocates zero bytes once compiled (L00 §8.2). And the last assertion
encodes something learnt on real hardware:

```java
// llvmpipe signals a fence as soon as it is placed; a real GPU's command processor can
// lag the CPU by more than three microsecond-long empty frames (one stall in 200k
// frames on an Intel laptop), so stalls are only required to stay rare.
assertTrue(ring.stalls() <= 1000, "stalls: " + ring.stalls());
```

The test's "frames" are empty and take microseconds, so the CPU laps the GPU. **llvmpipe**, the
software GPU of Mesa that CI uses, executes everything immediately and never stalls. The first
version of the test asserted zero stalls and failed on the laptop once in about 200,000 frames —
not a bug in the ring, but a wrong assumption in the test about how far a real GPU can lag.

## 17. The day on real hardware

Until 2026-10-11 the engine had only run on llvmpipe (in CI and in a container). The owner's
laptop has an Intel GPU with Windows driver 32.0.101.8331. The demo came up with **black,
mirror-smooth characters on a correct floor, and no user interface**. Three driver problems were
behind it. The tools were the GL debug output (`-Djeddy.gl.debug=true`, which makes the driver
report errors and warnings as messages, with the labels of §7.3 in them) and the GPU tests run on
the laptop.

### 17.1 Finding 1 — tiny mip levels cannot be copied

Some background. A texture has **mip levels**: the full image, then copies at half the width and
height, a quarter, and so on down to 1×1, so distant surfaces sample a small copy (chapter 10).
Textures are stored **block-compressed** (BC7): the image is cut into 4×4-pixel blocks of 16 bytes
each. The 2×2 and 1×1 levels are smaller than one block; they are stored as one (partly unused)
block each.

The engine keeps material textures in **texture arrays** (many same-sized images in one texture,
one per **layer**), and when an array is full it grows: it creates a bigger array and copies the
used layers into it on the GPU with `glCopyImageSubData`, level by level. On the Intel driver the
copy of the 2×2 and 1×1 levels failed with `GL_INVALID_VALUE`. The specification allows a copy
region smaller than a block when it reaches the edge of the image; this driver's validation does
not. The grown array was therefore missing the smallest levels of the layers it copied, and the
symptom recorded in D101 was black textures from then on.

First fix: read those levels back to the CPU and upload them into the new array.

### 17.2 Finding 2 — nor read back

`glGetCompressedTextureSubImage` on the same levels returned `GL_INVALID_OPERATION`: the same
validation bug in a second entry point. The fix that cannot depend on the driver (D101): keep the
bytes of those two levels on the CPU when a layer is uploaded — 16 bytes per level per layer — and
upload them again into the bigger array. The upload path works on every driver, because every
texture uses it.

### 17.3 Finding 3 — the dropped write

With both fixed, the characters were *still* black. The debug output was now silent: no errors.

The GPU tests on the laptop gave the first real clue. Every **textured** material shaded
correctly; every **factor-only** material (a material defined only by numbers, no textures) drawn
after a texture had been uploaded came out black — base color 0 and roughness 0, which renders as
pure reflection at 4 %: the lighting test read 0.0399. A material with every value zero is a
material **row** that reads as zeros.

To find which change broke the row, a new test was written:
[`FeatureBisectTest`](../../../modules/render/src/test/java/dev/jeddy/render/pbr/FeatureBisectTest.java).
Its idea is to render **one known thing** — a white sphere lit by the sun — under **one changed
condition at a time**, and print the whole table, pass or fail:

- `everyConfigurationLightsTheSphere`: the default renderer, then each feature switched off one at a
  time (sun shadows, local shadows, ambient occlusion, decals, outlines, particles, fog of war, GPU
  culling, post-processing), and all of them off, each with a factor-only, a textured and a skinned
  textured material. On the laptop every cell passed.
- `drawOrderDoesNotChangeTheShading`: a fresh renderer per row, but **two frames** on it, with
  something created between them. Its last three rows separate the three things that can be new in
  a second frame — a compiled shader variant, a texture, a material row:

  ```
  variant compiled before frame 1, new row+texture after
  row+texture before frame 1, variant compiled after
  new factor-only row after frame 1 (no new variant)
  ```

The rows that failed were exactly those in which a material row was added after a frame had been
drawn; a new shader variant or a new texture on its own was fine. The row reached the GPU
through `glNamedBufferSubData` into a buffer that frames in flight had read — and this driver
silently dropped that write. The fix is §13: every such change goes through the ring and a GPU
copy (D103), along with the light counter (§14.4), the environment lighting coefficients and the
exposure flag. The demo rendered correctly on the next run.

### 17.4 The method is the lesson

None of finding 3 was visible in a log. What found it was a test that renders a known picture,
changes **one** thing per row, and prints the **table** rather than stopping at the first failure,
run on the machine that fails and read on the machine that cannot reproduce it. It is a bisection —
halving the space of suspects — over the engine's own behaviour. When a renderer misbehaves on
hardware you do not have, write the table first. [running.md](../../running.md) keeps the list of
drivers seen so far and what each taught.

## 18. Exercises

Each has a hint and a full solution; try before you open them.

**1. Alignment arithmetic.** Compute `(c + alignment - 1) & -alignment` for `c = 300` with
`alignment` 256, 64 and 16. Then explain what goes wrong with `alignment = 24`.

<details><summary>Hint</summary>Write −alignment in binary (L00 §5.3) and look at its low bits.</details>
<details><summary>Solution</summary>

256: `555 & ~255` = 512. 64: `363 & ~63` = 320. 16: `315 & ~15` = 304. For 24, $-24$ is
`...1111 1110 1000`: its low bits are not "all zeros then all ones above", so AND-ing clears bit 4
and bits 0–2 but keeps bit 3. `(300 + 23) & -24` = `323 & ...11101000` = 320, which is not a
multiple of 24 (320 / 24 = 13.3). The trick needs a power of two because only then is $-a$ a mask
of the form `1…10…0`.
</details>

**2. Ring sizing.** A renderer draws 50,000 objects, with on average 2.5 draw commands per 1,000
objects, 2,000 lights at 64 bytes, and 2 MiB of other data. What is the smallest
`bytesPerFrame`, ignoring padding, and how much mapped memory do three regions take?

<details><summary>Solution</summary>

Records: $50{,}000 \times 64 = 3{,}200{,}000$. Commands: 125 × 20 = 2,500. Lights: 128,000.
Other: 2,097,152. Total ≈ 5,427,652 bytes (≈ 5.2 MiB). Rounded up to 256: 5,427,712. Three regions:
≈ 16.3 MB. The default 32 MiB leaves room for growth and padding.
</details>

**3. The counter before D103.** The light-cluster counter used to be reset with a 16-byte
`glNamedBufferSubData`. Using §2's timeline, describe what the compute shader of frame $n$ could
see on a driver that applied the write immediately (in CPU time) instead of in stream order, and
why the symptom would be lights *flickering* rather than missing.

<details><summary>Solution</summary>

The CPU resets the counter while preparing frame $n$; the GPU may still be running frame $n - 1$'s
cluster pass, which is incrementing the same counter. An immediate write can zero it in the middle
of that pass: the appends after the reset start again from the beginning of the list and overwrite
entries the same pass wrote a moment earlier, so clusters that pointed at those entries now list
the wrong lights; frame $n$'s own pass then starts from wherever the counter had climbed to, not
from zero. Which clusters are hit depends on exactly when the write landed relative to the GPU's
work, and that changes from frame to frame: a light is wrong in one frame and right in the next,
which the eye sees as flicker. (The Intel driver's actual bug was the other failure: it dropped the
write, so the counter was never reset and grew until the list was full.)
</details>

**4. The bisect's first version.** `FeatureBisectTest`'s first version (only
`everyConfigurationLightsTheSphere`) made one renderer per configuration, created all three
materials, and rendered two frames; it passed everywhere on the laptop. Why could it not find
finding 3, and what single change to the scenario exposed it?

<details><summary>Solution</summary>

Every material row was written before the first frame, into a buffer no frame had read yet, and the
driver handles that write correctly; the second frame added nothing new. The bug needed a write into a buffer
*already read by an earlier frame*. Rendering two frames on the same renderer and creating the
material between them (the "new factor-only row after frame 1" scenario) exposed it. The test's
Javadoc tells the same story.
</details>

**5. A bound range that goes stale.** The material buffer is now bound whole (`bufferBytes()`)
rather than the rows in use (`byteSize()`). Suppose a driver resolved a `glBindBufferRange` to a
fixed range when it was bound and kept that until the *buffer name* changed. Which rows of the
draw-order table would that bug alone fail, and which would it pass?

<details><summary>Solution</summary>

Binding only the rows in use means the bound size grows when rows are added. A driver that kept
the first frame's size would let new rows fall outside the bound range: any scenario that adds a
row after frame 1 and draws it in frame 2 would read outside the range (zeros or undefined) and
fail. In the table that is three rows: "factor-only, then textured rgba8 (next frame)" (the
textured material is created after frame 1), "variant compiled before frame 1, new row+texture
after" and "new factor-only row after frame 1". Scenarios whose rows all exist before frame 1 would
pass. Binding the whole buffer makes
the range constant, so that class of bug cannot happen regardless of the driver.
</details>

**6. Two regions.** Change the scene ring to 2 regions (`new FrameRing(..., 2, ...)` in
`SceneRenderer`), add a line that logs `ring.stalls()` every few seconds (nothing in the engine
prints it today), run the demo, and compare with 3 regions. Then explain the result with §8.3.

<details><summary>Solution</summary>

What to expect (measure it; this is a prediction, not a recorded number): when the GPU is the
slower side, stalls appear on a large share of frames and frame times get less even, because the
CPU must wait for frame $n - 2$, which the GPU is often still executing. When the CPU is the slower
side, the GPU is idle by the time the CPU comes round, and two regions behave like three. On
llvmpipe (CI, containers) nothing changes, because the software GPU finishes each frame before the
next begins.
</details>

**7. Build it yourself.** In a scratch test in `modules/render/src/test`, write a 60-line
`MiniRing` with the same API (`beginFrame`, `allocate`, `endFrame`) over a plain
`Arena.ofShared()` segment instead of a GL buffer, with the fences replaced by an `AtomicLong`
"frames the fake GPU has finished". Write a test where a "GPU" thread finishes frames slowly and
check that `beginFrame` blocks exactly when it should.

<details><summary>Hint</summary>Number frames 0, 1, 2, … Frame $n$ uses region $n \bmod R$, whose previous
user was frame $n - R$, so `beginFrame` for frame $n$ must wait until the fake GPU has finished
frame $n - R$.</details>
<details><summary>Solution</summary>

```java
final class MiniRing {
    private final MemorySegment memory;
    private final long regionSize;
    private final int frames;
    private final AtomicLong finished = new AtomicLong(-1); // the last frame the "GPU" finished
    private final AtomicLong cursor = new AtomicLong();
    private long frameNumber = -1;
    private long regionBase;

    MiniRing(Arena arena, long regionSize, int frames) {
        this.regionSize = regionSize;
        this.frames = frames;
        this.memory = arena.allocate(regionSize * frames, 256);
    }

    void beginFrame() {
        frameNumber++;
        long previousUser = frameNumber - frames;      // the fence we would wait on
        while (finished.get() < previousUser) {
            Thread.onSpinWait();                       // the stall
        }
        regionBase = (frameNumber % frames) * regionSize;
        cursor.set(0);
    }

    long allocate(long bytes, long alignment) {
        while (true) {
            long c = cursor.get();
            long start = (c + alignment - 1) & -alignment;
            long end = start + bytes;
            if (end > regionSize) {
                return -1;
            }
            if (cursor.compareAndSet(c, end)) {
                return regionBase + start;
            }
        }
    }

    void gpuFinished(long frame) {                     // what a signalled fence means
        finished.set(frame);
    }
}
```

The test: call `beginFrame` three times on a fresh ring with 3 frames (it must return at once:
`previousUser` is negative). Start a thread that calls `beginFrame` a fourth time and sets an
`AtomicBoolean` when it returns; sleep 50 ms and assert it has *not* returned; call
`gpuFinished(0)`; join the thread and assert it returned. `endFrame` is not needed here, because the
fake GPU reports frames by number instead of through fence objects. (`AtomicLong` is fine in a
scratch test; §9.2 explains why the real ring uses a `VarHandle`.)
</details>

## 19. What to carry forward, and a glossary

- Every byte the GPU reads is either **per-frame** (in a ring region, guarded by a fence) or
  **long-lived** (its own buffer). Once any frame has read a long-lived buffer, change it with a
  **GPU copy**, never a CPU write.
- **Fences** order the CPU against the GPU; **barriers** order GPU work against GPU work;
  **coherent mappings** make CPU writes visible to commands issued after them. Three different
  promises.
- Three frames in flight balance stalls against latency; the ring's `stalls()` is the number to
  watch.
- A driver's conformance is a hypothesis. Keep tests that render a known picture and print a table,
  and run them on every GPU you can reach.

**Glossary**

- **Bump allocator:** a cursor that moves forward by each request and is reset all at once (§8.2).
- **Coherent mapping:** CPU writes visible to later-issued commands without explicit flushes (§6.2).
- **Command stream, command processor:** the queue of recorded GPU commands; the GPU unit that
  executes them in order (§2).
- **Fence (sync object):** a marker in the stream the GPU signals when it gets there (§6.1).
- **Immutable storage:** a buffer whose size and flags are fixed at creation
  (`glNamedBufferStorage`, §4).
- **In flight:** submitted to the GPU and not yet finished (§2.2).
- **Indirect command:** draw parameters read by the GPU from a buffer (§14.1).
- **Instance record:** the 64 bytes per drawn object in the ring (§14.1).
- **Lock-free:** threads coordinate through atomic operations, never by waiting on a lock (§11.2).
- **Mapping, persistent mapping:** a CPU pointer to a buffer's memory; one that stays valid while
  the GPU uses the buffer (§3.2).
- **Memory barrier (`glMemoryBarrier`):** makes earlier shader writes visible to later commands
  reading through the named paths (§6.3).
- **Mip level:** a smaller copy of a texture for distant sampling (§17.1).
- **Pinned memory:** system memory the OS will not move, so a device can access it (§3.1).
- **Region:** one frame's share of the ring (§8).
- **Stall:** the CPU waiting for the GPU (§10).
- **Write-combined memory:** memory whose writes are gathered into bursts and whose reads are
  uncached and slow (§3.3).

*Code this lesson builds or quotes: `GpuBuffers`, `FrameRing`, `DrawBuilder`, `MaterialTable`,
`LightClusters`, `StaticScene`, `RingsTest`, `FeatureBisectTest`, `engine/instance.glsl`. Decisions:
D29, D101, D103 in [DESIGN §17](../../DESIGN.md). Chapters: [05](../05-opengl-from-java.md) (GL from
Java), [06](../06-renderer-architecture.md) (the renderer that uses the ring).*
