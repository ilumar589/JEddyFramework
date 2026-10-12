# Lesson 1 — Buffers in flight: how the CPU and the GPU share memory

*A lesson from the first run on real hardware (2026-10-11/12). It explains the model behind every
upload in the engine, derives the ring sizes, and walks through the three driver findings of that
day, including the method that found them. Read [05 OpenGL from Java](../05-opengl-from-java.md)
first; chapter [06](../06-renderer-architecture.md) describes the rings this lesson explains.*

## 1. The GPU is a second computer

Every `gl*` call in the engine returns in nanoseconds, long before the GPU has done anything. The
driver turns the call into a **command** and appends it to a stream; the GPU's command processor
reads that stream later, often a whole frame later. A frame on the laptop looks like this on a
timeline (numbers from the F3 overlay: main 8.8 ms, the GPU a little more):

```
CPU   |-- frame 100 ----|-- frame 101 ----|-- frame 102 ----|
GPU         |-- frame 100 -----|-- frame 101 -----|-- frame 102 --
                ^ the GPU is still reading frame 100's buffers while the CPU writes frame 101's
```

Two consequences drive everything below:

1. **A buffer the GPU is reading is "in flight".** If the CPU overwrites it, the GPU may read the
   new bytes, the old bytes, or half of each, depending on when the command processor gets there.
2. **Ordering is by the command stream, not by time.** A command issued after another is executed
   after it. A CPU write to memory has no position in that stream unless the API gives it one.

GL hides the first problem only when you let it: `glNamedBufferSubData` is *defined* to behave
as if it happened in stream order, so a conforming driver either waits for the GPU or copies your
bytes into a staging area and schedules the real write. That costs a stall or a copy, and it is
where drivers cut corners.

## 2. The three ways to change a buffer

The engine creates buffers with immutable storage (`glNamedBufferStorage`): size and capabilities
are fixed at creation and the driver can place the memory where it fits best. The flags are the
contract, in [`GpuBuffers.create`](../../../modules/render/src/main/java/dev/jeddy/render/gpu/GpuBuffers.java):

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

| Way | Call | Who copies | Position in the stream | Used for |
|---|---|---|---|---|
| CPU write | `glNamedBufferSubData` (needs `GL_DYNAMIC_STORAGE_BIT`) | the driver, now or later | as if now (the driver must make it so) | a buffer's first fill, fallbacks |
| Mapped write | store through a `GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT` mapping | the CPU, directly into memory the GPU reads | none: visible "eventually" (coherent), so you must know the GPU is not reading that region | the rings |
| GPU copy | `glCopyNamedBufferSubData` from a staging buffer | the GPU | a real command, ordered after everything before it | changing a buffer in flight |

The mapped write is the fast path: no API call per byte, no driver copy. Its price is that *you*
own the synchronization, which is what a ring buffer is for.

## 3. The ring: regions, fences and the three-frame rule

[`FrameRing`](../../../modules/render/src/main/java/dev/jeddy/render/gpu/FrameRing.java) is one
mapped buffer cut into `frames` regions (3 in `SceneRenderer`). Frame *n* writes only into region
*n mod 3*; before writing, it waits for the **fence** placed after frame *n − 3*'s commands:

```java
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

public void endFrame() {
    fences[frame] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
}
```

A fence is a command too: `glFenceSync` appends "signal this object" to the stream, so when the
CPU sees it signalled, every command before it has finished, including every read of that
region. The first `glClientWaitSync` has timeout 0: a probe. On llvmpipe it always succeeds
(the software GPU finishes before the next frame begins); on the laptop the command processor
can lag by more than three *empty* frames of the `RingsTest`, which is why that test now
tolerates rare stalls rather than asserting zero.

**Why three and not two.** With two regions, the CPU writing frame *n* would wait for frame
*n − 2*, which is the frame the GPU is most likely *just starting*: a stall nearly every frame
when the GPU is the bottleneck. With three, the GPU may be a full frame behind without the CPU
waiting, at the cost of one more frame of latency between input and pixels and one more region
of memory. The same trade-off is why swap chains default to triple buffering.

**Sizing it.** The region is what one frame allocates: instance records (64 B each: three
`vec4` rows of the transform and a `uvec4`), indirect commands (20 B), lights (64 B), the UI's
vertices (40 B each), constants. The demo's 1041 renderables are about 65 KB of instances; the
default region is 32 MiB (`32L << 20`), rounded
up to the uniform-buffer offset alignment because every allocation inside must satisfy it:

```java
long alignment = Math.max(256, device.caps().uniformBufferOffsetAlignment());
this.regionSize = (bytesPerFrame + alignment - 1) / alignment * alignment;
buffer = buffers.create(regionSize * frames, GpuBuffers.MAPPED, label);
```

Three regions of 32 MiB are 96 MiB of mapped memory. When a frame runs out, `allocate` returns
−1 and the caller skips the work for that frame (the UI, say) rather than stalling: a hitch is
worse than a missing frame of UI.

**Exercise 1.** The ring's `allocate` is lock-free (a compare-and-set on `cursor`) so the job
system's workers can allocate instance records in parallel. Work out what `(c + alignment − 1)
& −alignment` does for `c = 300, alignment = 256`, then for `alignment = 16`. Why must
`alignment` be a power of two?

## 4. Writing a buffer the GPU is reading: the copy

The material table is not per-frame data: one storage buffer, bound at binding 1, holds every
material's 128-byte row for the game's whole life, and the GPU reads it in every frame. New rows
arrive while it is in flight (a texture streams in, a material is created). Two of the three
ways are wrong for it:

- a **mapped write** has no stream position: the GPU could read the row half-written, and the
  buffer would need to be mapped persistently, which pins it to CPU-visible memory for the whole
  game;
- a **CPU write** (`glNamedBufferSubData`) is correct *by the specification* — and this is the
  one the engine used, and the one Intel's driver dropped (§6).

The **GPU copy** is correct by construction: the bytes go into this frame's ring region (which
no GPU work is reading, by the fence), and a copy command is appended to the stream, so it runs
after the frames still reading the table and before this frame's draws.
[`FrameRing.upload`](../../../modules/render/src/main/java/dev/jeddy/render/gpu/FrameRing.java):

```java
public boolean upload(int dstGlName, long dstOffset, MemorySegment src, long srcOffset, long bytes) {
    long at = allocate(bytes, 16);
    if (at < 0) {
        return false;
    }
    MemorySegment.copy(src, srcOffset, memory, at, bytes);
    glCopyNamedBufferSubData(glName, dstGlName, at, dstOffset, bytes);
    return true;
}
```

and [`MaterialTable.flush`](../../../modules/render/src/main/java/dev/jeddy/render/draw/MaterialTable.java)
uses it for the dirty range, keeping the CPU write for a ring that is full:

```java
if (ring == null || !ring.upload(glBuffer(), from, cpu, from, length)) {
    device.buffers().update(buffer, from, cpu.address() + from, length);
}
```

Is the copy ordered against the *mapped write* that filled the staging region? Yes, by a
different mechanism: `GL_MAP_COHERENT_BIT` promises that CPU stores are visible to GPU commands
issued afterwards, and the copy is issued after the store. (Without the coherent bit you would
call `glFlushMappedNamedBufferRange`, and for the other direction, the GPU writing memory the
CPU reads, `glMemoryBarrier(GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT)` plus a fence.)

**Exercise 2.** The light clusters reset their item counter every frame. Before D103 it was a
`glNamedBufferSubData` of 8 bytes. Explain, with the timeline of §1, what the compute shader
could read on a driver that applied the write immediately instead of in stream order, and why
the symptom would be lights flickering rather than missing.

## 5. Barriers: ordering inside the GPU

Fences order the CPU against the GPU. Inside the GPU, commands are issued in order but their
*memory effects* are not automatically visible to later commands that read through a different
path (a shader storage write, then an indirect-draw read of the same buffer). `glMemoryBarrier`
is the explicit promise. The static scene's GPU culling writes commands with a compute shader and
then draws from them:

```java
glMemoryBarrier(GL_COMMAND_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
```

The bits name the *consumer*: "make shader storage writes visible to indirect command reads and
to later shader storage reads". `FrameGraph` carries the bits per pass so a pass declares what
the next one reads. Forgetting a barrier is the GPU-side equivalent of a data race: it works on
one driver (which inserts the barrier itself) and tears on another.

## 6. The day on real hardware: three findings

The engine had only ever run on Mesa's llvmpipe (CI) and in a container. The owner's laptop has
an Intel GPU with driver 32.0.101.8331, and the demo showed **black, mirror-smooth characters on
a correct floor, and no HUD**. Everything below was found with the GL debug output on
(`-Djeddy.gl.debug=true`, which turns on `GLDebug`) and the tests.

**Finding 1 — sub-block mip levels cannot be copied.** Growing a material texture array copies
the used layers into a bigger array with `glCopyImageSubData`, level by level. The 2×2 and 1×1
levels of a BC7 texture are smaller than a 4×4 block; the specification allows a region that
reaches the image edge, Intel returns `GL_INVALID_VALUE`. Every texture that arrived after a
growth lost its tail. First fix: read those levels back and re-upload them.

**Finding 2 — nor read back.** `glGetCompressedTextureSubImage` on the same levels returns
`GL_INVALID_OPERATION`. The same validation bug, a second entry point. The fix that cannot
depend on the driver: keep the bytes of those levels on the CPU when they are uploaded (16
bytes per level per layer) and upload them again into the bigger array (D101). The upload path
works, since every texture uses it.

**Finding 3 — the dropped write.** With both fixed, the characters were still black. The GPU
tests on the laptop said what and in which order: every *textured* material shaded correctly,
every *factor-only* material drawn after a texture had been uploaded was black (base colour 0,
roughness 0: pure Fresnel at 0.04, which is why the furnace test read 0.0399). That is a
material **row** reading as zeros. The [bisect test](../../../modules/render/src/test/java/dev/jeddy/render/pbr/FeatureBisectTest.java)
then separated three things a frame could leave broken for something created afterwards: a new
shader variant (fine), a new texture (fine), a new material row (black). The row goes through
`glNamedBufferSubData` into a buffer the GPU has already read, and this driver silently drops
it. D103 moved every such write onto the ring and a GPU copy (§4); the demo rendered correctly
on the next run.

**The method is the lesson.** None of this was visible in the log once the two errors were
fixed; the driver was silent. What found the third bug was a test that renders *one* known thing
under *one* changed condition at a time and prints a table — a bisection over the engine's own
behaviour, run on the machine that fails and read on the machine that cannot reproduce it. When
a renderer misbehaves on hardware you do not have, write the table first.

**Exercise 3.** The bisect's first version rendered a fresh renderer per cell and passed
everywhere on the laptop. Why could it not have found finding 3, and what single change to the
scenario exposed it? (Answer in the test's Javadoc.)

**Exercise 4.** The material buffer is now bound whole (`bufferBytes()`) instead of the rows in
use. Suppose a driver resolved a `glBindBufferRange` to a fixed extent at bind time and kept it
until the *buffer name* changed. Which of the bisect's rows would that bug alone have failed,
and which would it have passed?

## 7. What to carry forward

- Every byte the GPU reads is either per-frame (ring region, fenced) or long-lived (its own
  buffer). Change a long-lived buffer through a GPU copy once any frame has read it.
- Fences order CPU against GPU; barriers order GPU against GPU; coherent mappings order CPU
  stores against later commands. Each is a different promise.
- Three frames in flight is the balance between stalls and latency; the ring's `stalls()` is the
  number to watch, and the perf capture reports it.
- A driver's conformance is a hypothesis. Keep the tests that render a known picture, and run
  them on every GPU you can reach (running.md keeps the table of what each one taught).

*Code this lesson quotes: `GpuBuffers`, `FrameRing`, `MaterialTable`, `StaticScene`,
`FeatureBisectTest`. Decisions: D29, D101, D102, D103 in [DESIGN §17](../../DESIGN.md).*
