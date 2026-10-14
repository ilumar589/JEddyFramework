# 06 — The renderer: from render packets to draw calls

*A level of an isometric role-playing game holds tens of thousands of things to draw: floor tiles,
walls, pillars, barrels, characters and their shadows. Drawing each one with its own OpenGL calls
would spend the whole frame in the driver. This chapter builds, from empty files, the part of the
engine that turns the game thread's description of a frame — the render packet — into a handful of
GPU commands per pass: every mesh in three shared "mega buffers", one 64-byte record per object,
draws sorted by a 64-bit key with a radix sort, batches drawn with multi-draw-indirect, materials
that reach their textures without rebinding, a frame graph that orders the passes and places the
memory barriers, and compute shaders that cull the static part of the level on the GPU. It ends
with the tests that prove it — 20,000 objects become two draw calls and a frame allocates nothing —
and the numbers measured so far.*

**Before you start.** You need [Lesson 0](lessons/00-foundations.md): the notation (§2, in
particular rounding up in §2.5 and big-O in §2.8), hexadecimal (§3), two's complement (§5), bit
operations, shifts and packing (§6), how a `float` is stored (§7), memory, native memory and
alignment (§8), caches, threads and compare-and-set (§9), and what a GPU, a driver, the command
processor and OpenGL are (§10–11). You need [Lesson 1](lessons/01-buffers-in-flight.md), which
builds the frame ring this chapter writes every frame into and explains fences, coherent mapping and
memory barriers; this chapter summarizes it and does not rebuild it. [Lesson 3](lessons/03-data-layout.md)
explains handles (§6) and why the engine keeps data in one array per field (§4); both appear on
every page here. From the chapters: [01](01-architecture.md), on the render packet's triple buffer
and the interpolation between simulation ticks; [04](04-cameras-and-projection.md), on the
projection matrix, reversed-Z depth and how the frustum planes come out of the view-projection
matrix; and [05](05-opengl-from-java.md), on the GPU pipeline, buffers, vertex arrays, textures,
shaders, program variants and the state cache. The little vector math this chapter needs — the dot
product, a transform as three rows, a plane — is explained again in §2, so you can read it with L00
alone.

**What you will build**

| Piece | File | What it does |
|---|---|---|
| The render packet | [`RenderPacket`](../../modules/render/src/main/java/dev/jeddy/render/RenderPacket.java), [`RenderPackets`](../../modules/render/src/main/java/dev/jeddy/render/RenderPackets.java) | the frame as plain arrays: instances, camera, lights; three packets for the triple buffer |
| The range allocator | [`core.memory.RangeAllocator`](../../modules/core/src/main/java/dev/jeddy/core/memory/RangeAllocator.java) | hands out ranges of a space (vertices, indices, table rows) from a free list, first fit, merging freed neighbours |
| The mesh registry | [`MeshRegistry`](../../modules/render/src/main/java/dev/jeddy/render/mesh/MeshRegistry.java) | every mesh in three mega buffers behind one vertex array; per-mesh tables of base vertices and index ranges |
| CPU culling | [`Culling`](../../modules/render/src/main/java/dev/jeddy/render/draw/Culling.java) | which instances' bounding spheres touch the view, in parallel |
| The material table | [`MaterialTable`](../../modules/render/src/main/java/dev/jeddy/render/draw/MaterialTable.java) | one 128-byte row per material in a storage buffer; program slots that batches are keyed by |
| The draw builder | [`DrawBuilder`](../../modules/render/src/main/java/dev/jeddy/render/draw/DrawBuilder.java) | visible instances → items, LOD, 64-bit sort keys, sorted records, indirect commands, batches, multi-draws |
| The radix sorts | [`core.collections.LongSort`](../../modules/core/src/main/java/dev/jeddy/core/collections/LongSort.java), [`core.job.ParallelSort`](../../modules/core/src/main/java/dev/jeddy/core/job/ParallelSort.java) | sort 64-bit keys with an `int` payload, sequentially or on the job system |
| The shader side | [`engine/instance.glsl`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/engine/instance.glsl), [`engine/material.glsl`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/engine/material.glsl), [`engine/frame.glsl`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/engine/frame.glsl) | how shaders find their record, their material and the frame's constants |
| The material system | [`MaterialSystem`](../../modules/render/src/main/java/dev/jeddy/render/material/MaterialSystem.java) | material rows from `.jmat` data, feature masks, texture sets, the binders each pass uses |
| Texture arrays | [`TextureArrays`](../../modules/render/src/main/java/dev/jeddy/render/material/TextureArrays.java) | material textures as layers of array textures, one bucket per format and size |
| The frame graph | [`FrameGraph`](../../modules/render/src/main/java/dev/jeddy/render/graph/FrameGraph.java), [`PassContext`](../../modules/render/src/main/java/dev/jeddy/render/graph/PassContext.java), [`PassExecutor`](../../modules/render/src/main/java/dev/jeddy/render/graph/PassExecutor.java), [`GpuTimers`](../../modules/render/src/main/java/dev/jeddy/render/graph/GpuTimers.java) | passes declared once; culling, validation, targets, clears and barriers derived; GPU time per pass |
| GPU culling | [`StaticScene`](../../modules/render/src/main/java/dev/jeddy/render/draw/StaticScene.java), [`cull/static_cull.comp`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/cull/static_cull.comp), [`cull/static_compact.comp`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/cull/static_compact.comp) | static instances kept on the GPU; compute shaders cull them, pick their LOD and pack the draws |
| The frame | [`SceneRenderer`](../../modules/render/src/main/java/dev/jeddy/render/SceneRenderer.java), [`app.world.WorldRenderer`](../../modules/app/src/main/java/dev/jeddy/app/world/WorldRenderer.java) | everything above wired into one frame, and the whole stack created in order |

Design background: [DESIGN](../DESIGN.md) §9.2 (render architecture) and §9.3 (passes). Lighting,
PBR and shadows are chapter [07](07-lighting-and-shadows.md); post-processing, outlines, decals and
particles are chapter [08](08-effects-and-post.md); skinning is chapter
[09](09-animation-and-skinning.md); the cooker that produces meshes and textures is chapter
[10](10-assets.md).

**Contents**

- Part A — The ideas
  1. [The problem: many objects, few draw calls](#1-the-problem-many-objects-few-draw-calls)
  2. [The vector math this chapter uses](#2-the-vector-math-this-chapter-uses)
  3. [Drawing from shared buffers](#3-drawing-from-shared-buffers)
  4. [Per-instance data in storage buffers](#4-per-instance-data-in-storage-buffers)
  5. [Sorting draws: keys and radix sort](#5-sorting-draws-keys-and-radix-sort)
  6. [Level of detail from projected error](#6-level-of-detail-from-projected-error)
  7. [Materials without rebinding](#7-materials-without-rebinding)
  8. [The frame graph idea](#8-the-frame-graph-idea)
  9. [GPU-driven rendering](#9-gpu-driven-rendering)
- Part B — Building it
  10. [Step 1: the render packet](#10-step-1-the-render-packet)
  11. [Step 2: the range allocator](#11-step-2-the-range-allocator)
  12. [Step 3: the mesh registry](#12-step-3-the-mesh-registry)
  13. [Step 4: culling on the CPU](#13-step-4-culling-on-the-cpu)
  14. [Step 5: the material table and program slots](#14-step-5-the-material-table-and-program-slots)
  15. [Step 6: the draw builder's fields](#15-step-6-the-draw-builders-fields)
  16. [Step 7: build, counting the items](#16-step-7-build-counting-the-items)
  17. [Step 8: the item kernel, from instance to sort key](#17-step-8-the-item-kernel-from-instance-to-sort-key)
  18. [Step 9: the radix sort](#18-step-9-the-radix-sort)
  19. [Step 10: build, from sorted items to commands](#19-step-10-build-from-sorted-items-to-commands)
  20. [Step 11: submit and the batch binders](#20-step-11-submit-and-the-batch-binders)
  21. [Step 12: the shader side of a record](#21-step-12-the-shader-side-of-a-record)
  22. [Step 13: the material system](#22-step-13-the-material-system)
  23. [Step 14: texture arrays](#23-step-14-texture-arrays)
  24. [Step 15: the frame graph](#24-step-15-the-frame-graph)
  25. [Step 16: GPU timers](#25-step-16-gpu-timers)
  26. [Step 17: the static scene on the GPU](#26-step-17-the-static-scene-on-the-gpu)
  27. [Step 18: the scene renderer](#27-step-18-the-scene-renderer)
- Part C — Using it and proving it
  28. [WorldRenderer: the whole stack](#28-worldrenderer-the-whole-stack)
  29. [One frame on the main thread](#29-one-frame-on-the-main-thread)
  30. [Skinned instances](#30-skinned-instances)
  31. [Debug drawing and overlays](#31-debug-drawing-and-overlays)
  32. [The tests, walked through](#32-the-tests-walked-through)
  33. [Things to run](#33-things-to-run)
  34. [The numbers](#34-the-numbers)
  35. [Why it is built this way](#35-why-it-is-built-this-way)
  36. [Further reading](#36-further-reading)
  37. [Exercises](#37-exercises)
  38. [Glossary](#38-glossary)

---

# Part A — The ideas

## 1. The problem: many objects, few draw calls

### 1.1 What one draw costs

The textbook way to draw a scene is a loop that draws each object on its own:

```java
// Illustrative, not the real code: one draw per object.
for (Obj o : visible) {
    glUseProgram(o.program);
    glBindVertexArray(o.vao);
    glBindTextureUnit(0, o.albedo);
    glProgramUniformMatrix4fv(o.program, 0, 1, false, o.modelMatrix);
    glDrawElements(GL_TRIANGLES, o.indexCount, GL_UNSIGNED_INT, 0L);
}
```

Every `gl…` line is a call from Java into the driver (L00 §12), and every call costs the CPU
something:

- **The crossing.** A call from Java into C through FFM costs a few to tens of nanoseconds (L00
  §11). That is the cheap part.
- **The driver's work.** The user-mode driver (L00 §10.4) checks that the call is legal, records the
  new state, and — at the draw — checks that everything bound fits together (the program's inputs
  against the vertex array, its samplers against the bound textures, its outputs against the
  framebuffer), translates it all into the hardware's own state, and writes a draw packet into the
  command buffer. L00 §11 puts one recorded GL call at roughly 0.1 to 1 µs.

An isometric level has walls, floor tiles, props, characters and the shadow casters of each light:
easily 20,000 objects in view. Five calls each is 100,000 calls a frame. With L00's range that is
between $100{,}000 \times 0.1\ \mu\text{s} = 10$ ms and $100{,}000 \times 1\ \mu\text{s} = 100$ ms of
main-thread time — an estimate, not a measurement, but even the low end eats most of a 16.7 ms frame
(L00 §11) before the GPU has drawn anything.

The GPU pays too. Every draw is a separate job for its **command processor** (L00 §10.4), and a
change of program or textures between two draws can force the GPU to finish the earlier work before
the later one starts. Thousands of tiny draws keep the front of the GPU busy while its thousands of
shader cores wait.

### 1.2 Four moves that remove the per-object cost

Modern OpenGL removes nearly all of that cost with four ideas, known together as "Approaching Zero
Driver Overhead" ([§36](#36-further-reading)). Jeddy uses all four:

1. **All meshes in a few big buffers with one vertex layout.** Then no draw needs a different vertex
   array: a draw differs from another only by numbers — where its indices start, which vertex they
   count from, how many copies to draw. These shared buffers are the **mega buffers** (§3.2, §12).
2. **Per-object data in storage buffers.** The transform, material index and flags of every object
   go into one array in GPU memory, one 64-byte **instance record** per object; every material's
   numbers go into another. A shader finds its object's record from counters the GPU provides, so
   nothing has to be set between two objects (§3.3, §4).
3. **The draw parameters themselves in a buffer.** Instead of calling `glDrawElements` per object,
   the CPU writes a small 20-byte struct per draw into GPU memory, and **one** call,
   `glMultiDrawElementsIndirect`, tells the GPU: "run all the draws described there" (§3.4, §3.5).
   This is **multi-draw-indirect** (MDI).
4. **Sort the work** so that draws needing the same program and textures sit next to each other,
   then issue one multi-draw per such group, a **batch** (§5).

The acceptance test of this design (TASKS M5-08) is
[`DrawBuilderTest`](../../modules/render/src/test/java/dev/jeddy/render/draw/DrawBuilderTest.java):
20,000 instances of a mesh with two submeshes, in two materials, become **2**
`glMultiDrawElementsIndirect` calls, and a frame allocates zero bytes (§32.4).

### 1.3 The pipeline at a glance

```
 game thread           main thread, with job workers                               GPU
 ───────────           ─────────────────────────────                               ───
 RenderPacket ──────▶  Culling       bounding sphere vs frustum   (workers)
 (SoA arrays,  triple  DrawBuilder   items, LOD, 64-bit sort keys (workers)
  §10)         buffer  ParallelSort  radix sort of the keys       (workers)
                       DrawBuilder   instance records and
                                     indirect commands, written
                                     into the FrameRing           (workers)
                       submit        per batch: bind program and
                                     textures, then one
                                     glMultiDrawElementsIndirect ─────▶ command processor reads
                                                                        each 20-byte command;
                                                                        vertex shader reads record
                                                                        gl_BaseInstance + gl_InstanceID
```

Part A explains each box from first principles. Part B builds them in the order a reader would
write them, then the frame graph that orders the passes, the GPU-driven path for the static scene,
and the `SceneRenderer` that ties it all together.

## 2. The vector math this chapter uses

The renderer needs only four small pieces of vector math. Here they are, with numbers, so that the
code in Part B has no surprises.

### 2.1 Vectors and the dot product

A **vector** here is three numbers $(x, y, z)$: a position in space, or a direction. Jeddy's world is
right-handed with $y$ up (CLAUDE.md conventions). The **dot product** of two vectors multiplies them
component by component and adds:

$$\mathbf{a} \cdot \mathbf{b} = a_x b_x + a_y b_y + a_z b_z$$

For $\mathbf{a} = (3, 4, 0)$ and $\mathbf{b} = (1, 0, 0)$: $3 \cdot 1 + 4 \cdot 0 + 0 \cdot 0 = 3$.
Two facts make it useful:

- **Length.** $\mathbf{a} \cdot \mathbf{a} = a_x^2 + a_y^2 + a_z^2$ is the squared length
  (Pythagoras, twice), so the **length** is $|\mathbf{a}| = \sqrt{\mathbf{a} \cdot \mathbf{a}}$. For
  $(3, 4, 0)$: $\sqrt{9 + 16} = 5$.
- **Projection.** When $\mathbf{n}$ has length 1 (a **unit** vector), $\mathbf{a} \cdot \mathbf{n}$
  is how far $\mathbf{a}$ reaches in the direction of $\mathbf{n}$. Above, $(3, 4, 0)$ reaches 3
  along the $x$ axis.

The same operation works on four numbers:
$(a_0, a_1, a_2, a_3) \cdot (b_0, b_1, b_2, b_3) = a_0 b_0 + a_1 b_1 + a_2 b_2 + a_3 b_3$. GLSL has it
built in as `dot`.

### 2.2 A placement as three rows: the 3×4 affine transform

A mesh is modelled around its own origin, in **mesh space**. To place an instance of it in the
world, each mesh-space point $\mathbf{p}$ is turned into a world point $\mathbf{p}'$ by

$$\mathbf{p}' = R\,\mathbf{p} + \mathbf{t}$$

where $R$ is a 3×3 grid of numbers that rotates and scales, and $\mathbf{t}$ is the translation (where
the mesh's origin lands). Written out, component $i$ of the result is

$$p'_i = r_{i0}\,p_x + r_{i1}\,p_y + r_{i2}\,p_z + t_i = (r_{i0}, r_{i1}, r_{i2}, t_i) \cdot (p_x, p_y, p_z, 1)$$

So if each row of $R$ is stored with its translation as a fourth number, every world coordinate is
one 4-number dot product of a stored row with $(p_x, p_y, p_z, 1)$. Twelve numbers describe the whole
placement: the **row-major 3×4 affine transform** the engine uses for every instance (DESIGN §8.2;
D21). A full 4×4 matrix would add a fourth row that is always $(0, 0, 0, 1)$ for such transforms, so
the engine does not store it.

In a `float[]`, an instance's 12 numbers sit at offsets 0 to 11:

```
offset:  0    1    2    3  |  4    5    6    7  |  8    9   10   11
         r00  r01  r02  tx |  r10  r11  r12  ty |  r20  r21  r22  tz
```

The translation is at offsets **3, 7 and 11**; the code reads the instance's position from exactly
those (§17).

**A worked example.** Turn a mesh a quarter turn about the $y$ axis and move it to $(10, 0, 5)$. A
quarter turn about $y$ (right-handed) sends the $x$ axis to $-z$ and the $z$ axis to $+x$, so

```
row 0:  0  0  1 | 10
row 1:  0  1  0 |  0
row 2: -1  0  0 |  5
```

The mesh-space point $(1, 0, 0)$ becomes $x' = (0, 0, 1, 10) \cdot (1, 0, 0, 1) = 10$,
$y' = (0, 1, 0, 0) \cdot (1, 0, 0, 1) = 0$, $z' = (-1, 0, 0, 5) \cdot (1, 0, 0, 1) = 4$: the point
$(10, 0, 4)$, one unit "behind" the new origin along $-z$, as a quarter turn should put it.

### 2.3 How big an instance is: the length of a column

The mesh's own $x$ axis is the direction $(1, 0, 0)$. $R$ sends it to
$(r_{00}, r_{10}, r_{20})$ — the **first column** of $R$. A rotation never changes lengths, so the
length of that column is the instance's **scale along its $x$ axis**:

$$s = \sqrt{r_{00}^2 + r_{10}^2 + r_{20}^2}$$

In the array the first column is at offsets **0, 4 and 8**. For the quarter turn above,
$s = \sqrt{0^2 + 0^2 + (-1)^2} = 1$; the same turn with everything doubled has the column
$(0, 0, -2)$ and $s = 2$. The level-of-detail choice uses $s$ (§6): a prop scaled up twice looks
twice as big, so its simplifications show twice as much. For a non-uniform scale this measures only
the $x$ direction — an approximation the engine accepts.

### 2.4 Planes and signed distance

A **plane** is a flat surface without edges. Pick a unit vector $\mathbf{n} = (a, b, c)$
perpendicular to it (its **normal**) and a number $d$; the plane is every point $\mathbf{x}$ with

$$\mathbf{n} \cdot \mathbf{x} + d = a\,x + b\,y + c\,z + d = 0$$

For any point, $s(\mathbf{x}) = \mathbf{n} \cdot \mathbf{x} + d$ is its **signed distance** to the
plane: zero on it, positive on the side $\mathbf{n}$ points to, negative on the other side, and its
size is the distance in world units (this is the projection fact of §2.1, which needs $|\mathbf{n}|
= 1$). Example: the plane $x = -10$ with normal $(1, 0, 0)$ is $(a, b, c, d) = (1, 0, 0, 10)$, and
the point $(-12, 3, 7)$ has $s = -12 + 10 = -2$: two units on the side away from the normal.

The camera sees a truncated pyramid, the **view frustum**, bounded by six planes (left, right,
bottom, top, near, far) whose normals all point inwards. A point is in view when all six signed
distances are $\ge 0$. Chapter [04](04-cameras-and-projection.md), on frustum culling, derives the
six planes from the view-projection matrix; the engine's
[`CullOps.extractPlanes`](../../modules/math/src/main/java/dev/jeddy/math/CullOps.java) does it and
divides each plane by the length of its $(a, b, c)$ so that the normal is a unit vector:

```java
float len = (float) Math.sqrt(a * a + b * b + c * c);
if (len > 1e-12f) {
    planes[i * 4] = a / len;
    planes[i * 4 + 1] = b / len;
    planes[i * 4 + 2] = c / len;
    planes[i * 4 + 3] = d / len;
```

One plane is special. Jeddy's perspective projection is **infinite**: it has no far plane (chapter
[04](04-cameras-and-projection.md), on reversed-Z). For it, the "far plane" row of the matrix is
$(0, 0, 0, 	ext{near})$, its $(a, b, c)$ has length 0, and `extractPlanes` replaces it with
$(0, 0, 0, 1)$, whose signed distance is 1 for every point — every sphere passes it. Only an
orthographic view has a real far plane.

Each object carries a **bounding sphere**: a center $\mathbf{c}$ and radius $r$ with the whole object
inside. The sphere is completely on the outside of a plane exactly when even its point farthest
towards the inside is still outside, that is when the center is more than $r$ beyond the plane:

$$s(\mathbf{c}) = a\,c_x + b\,c_y + c\,c_z + d < -r$$

The object is **culled** (skipped) if that holds for any of the six planes; this is
`CullOps.sphereVisible` and, on the GPU, the loop in `static_cull.comp` (§26). With the plane
$(1, 0, 0, 10)$ again: a sphere at $x = -10.5$ with radius 0.75 has $s = -0.5$, which is not below
$-0.75$, so it is kept — it pokes 0.25 units into the view.

The test is **conservative**: near a corner of the frustum, a sphere can be outside the view yet on
the inner side of each of the six planes taken one at a time, and it is kept. That costs a wasted
draw, never a missing object.

<details><summary>Check: for the plane (0, 1, 0, −2), is a sphere at (5, 1, 0) of radius 0.5 culled? And of radius 1.5?</summary>

$s = 0 \cdot 5 + 1 \cdot 1 + 0 \cdot 0 - 2 = -1$. With radius 0.5: $-1 < -0.5$, so it is culled
(entirely below $y = 2$, on the outside). With radius 1.5: $-1 \ge -1.5$, kept — its top reaches
$y = 2.5$.
</details>

## 3. Drawing from shared buffers

### 3.1 Indexed drawing

A mesh is a list of **vertices** (each a position plus attributes such as the normal and texture
coordinates) and a list of triangles. Neighbouring triangles share corners, so a triangle is stored as
three **indices**: numbers of vertices in the list. A square made of two triangles needs 4 vertices
and 6 indices, `0 1 2  0 2 3`, instead of 6 full vertices.

When the GPU draws indexed triangles, its **vertex fetch** stage reads each index from the **element
buffer** (the index buffer), then reads that vertex's attributes from the vertex buffers: attribute
data for vertex $v$ sits at $v \times \text{stride}$ in its stream (chapter
[05](05-opengl-from-java.md), on vertex arrays). The vertex shader runs on the fetched data. GPUs
keep the results of recently shaded vertices in a small cache, so an index that repeats soon is not
shaded twice — which is why the cooker reorders indices for that cache (chapter [10](10-assets.md)).

### 3.2 Base vertex and first index

Now put many meshes into the same buffers. Mesh A's vertices take slots 0–3 of the vertex buffers,
mesh B's slots 4–7. The cooker wrote B's indices relative to B's own first vertex (`0 1 2 0 2 3`),
because it did not know where B would end up. Rewriting every index when a mesh is loaded would cost
time and make the indices depend on the placement. Instead the draw call says where B starts:

```c
void glDrawElementsBaseVertex(GLenum mode, GLsizei count, GLenum type,
                              const void *indices, GLint basevertex);
```

"Read `count` indices of `type` starting at byte offset `indices` of the element buffer, **add
`basevertex` to each index value**, fetch those vertices, draw `mode` primitives." The byte offset is
the index of the first index to read, the **first index**, times 4 for 32-bit indices.

In the test of §32.3, the second quad lands at base vertex 4 and its first index is 9 (§12.8 traces
why). Its LOD-0 index list is `0 1 2 0 2 3`, so index value 2 fetches vertex slot $4 + 2 = 6$, and the
draw reads 6 indices starting at byte $4 \times 9 = 36$.

### 3.3 Instancing: `gl_InstanceID` and `gl_BaseInstance`

**Instancing** draws the same index range many times in one draw. The vertex shader can tell the
copies apart with two built-in inputs:

- `gl_InstanceID` counts the copies: $0, 1, \dots, \text{instanceCount} - 1$;
- `gl_BaseInstance` is a number the draw carries along unchanged, its **base instance** (GLSL 4.60
  made it a core built-in).

`gl_InstanceID` does **not** include the base instance: the first copy is always 0. So if a draw's
copies should use records 120, 121 and 122 of an array, the draw carries base instance 120 and the
shader reads record `gl_BaseInstance + gl_InstanceID`. That one line is how every Jeddy vertex shader
finds its object (§21).

### 3.4 The indirect command

Put §3.2 and §3.3 together: "draw `count` indices from `firstIndex`, added to `baseVertex`,
`instanceCount` times, and tell the shader the base instance is `baseInstance`". Those are five
numbers, and OpenGL defines a struct for them, `DrawElementsIndirectCommand`. Each field is 4 bytes,
20 bytes in total (`DrawBuilder.COMMAND_BYTES`):

| Offset | Field | Type | What `DrawBuilder` writes |
|---|---|---|---|
| 0 | `count` | uint | `meshes.indexCount(mesh, lod, submesh)` |
| 4 | `instanceCount` | uint | how many items share this draw (§19) |
| 8 | `firstIndex` | uint | `meshes.firstIndex(mesh, lod, submesh)`, counted in **indices**, not bytes |
| 12 | `baseVertex` | int | `meshes.baseVertex(mesh, submesh)` (signed: it may be negative in general) |
| 16 | `baseInstance` | uint | the sorted position of the draw's first record (§19) |

**A worked example.** A group of 3 objects whose records start at position 120 in the record array,
drawing a submesh whose chosen detail level has 36 indices starting at index 5000, with base vertex
1200, becomes the command $(36, 3, 5000, 1200, 120)$. For the second copy, `gl_InstanceID` is 1 and
the shader reads record 121. An index value of 7 in the index buffer fetches vertex slot
$1200 + 7 = 1207$ of every vertex stream.

### 3.5 Multi-draw-indirect: what the driver and the GPU do

```c
void glMultiDrawElementsIndirect(GLenum mode, GLenum type, const void *indirect,
                                 GLsizei drawcount, GLsizei stride);
```

reads `drawcount` commands from the buffer bound to `GL_DRAW_INDIRECT_BUFFER`, the first at byte
offset `indirect`, each `stride` bytes after the previous one, and runs each as if it were a separate
instanced, based draw. From Java the `indirect` parameter is a buffer offset, passed through the
`long` overload the GL generator adds for such parameters (L00 §12, D24).

**What the driver does.** It validates the bound state **once** for the whole call — program, vertex
array, framebuffer, the indirect buffer binding — and records one small packet into the command buffer
that points at the commands' address in GPU memory, with the count and stride. It does not read the
commands: they are data for the GPU. This is why one call costs about the same whether it carries 2
commands or 20,000.

**What the GPU does.** The command processor reaches that packet, fetches the 20-byte commands from
memory one after another, and for each one programs the vertex fetch (first index, count, base vertex)
and the instance range (instance count, base instance), and launches the draw. A command whose
instance count is 0 draws nothing. Hardware differs in how much of this the command processor does
natively; a driver may also emulate parts of it, and on Mesa's llvmpipe (CI) the whole "GPU" is
software, so the loop runs on the CPU inside the call.

**What it needs to be correct.**

- The commands must be complete before the GPU reads them. Jeddy's CPU-built commands are written
  into the persistently mapped, coherent frame ring before the draw call is issued, which makes them
  visible to it ([L01](lessons/01-buffers-in-flight.md) §6.2). Commands written by a compute shader
  need a memory barrier with `GL_COMMAND_BARRIER_BIT` before the draw, because the command processor
  reads them through its own path, not through the caches the shaders wrote (§9.4, L01 §6.3).
- The memory must not be rewritten while the GPU may still read it: the frame ring's fences guarantee
  that for CPU writes (§4.3).
- `indirect` and `stride` must be multiples of 4, or GL rejects the call with an error.
- The driver does not check the contents. A wrong `firstIndex` or `count` makes the GPU read outside
  the index buffer: the result is undefined, from garbage triangles to a GPU fault, and nothing
  reports it. That is one reason the counts come from the mesh registry's tables, never from
  arithmetic in the draw code.

## 4. Per-instance data in storage buffers

### 4.1 The 64-byte record and the std430 rules

A **shader storage buffer** (SSBO) is a buffer a shader reads (and may write) as an array of structs;
chapter [05](05-opengl-from-java.md) introduces them next to uniform buffers. The struct's bytes must
be laid out the same in Java and in GLSL, and GLSL's `std430` layout fixes the rules: a `vec4`
(four floats) or `uvec4` (four unsigned ints) takes 16 bytes and starts at a multiple of 16; a struct
starts at a multiple of its largest member's alignment; an array's elements are spaced by the struct
size rounded up to that alignment.

Jeddy's instance record is three `vec4` rows and one `uvec4`:

```glsl
struct JeddyInstance {
    vec4 row0;
    vec4 row1;
    vec4 row2;
    uvec4 data; // x material, y flags, z entity, w camera fade (float bits)
};
```

Four 16-byte members, no gaps, 64 bytes, array stride 64 (`DrawBuilder.INSTANCE_BYTES = 64`):

| Bytes | GLSL | Content |
|---|---|---|
| 0–15 | `row0` | $r_{00}\ r_{01}\ r_{02}\ t_x$ (interpolated between ticks, §19.3) |
| 16–31 | `row1` | $r_{10}\ r_{11}\ r_{12}\ t_y$ |
| 32–47 | `row2` | $r_{20}\ r_{21}\ r_{22}\ t_z$ |
| 48–51 | `data.x` | material index (a row of the material table, §14) |
| 52–55 | `data.y` | instance flags, including the skinning palette bits (§10.2) |
| 56–59 | `data.z` | entity (picking, debugging) |
| 60–63 | `data.w` | camera fade, stored as the bits of a `float` |

The rows are exactly the 3×4 transform of §2.2, so the Java side copies 12 floats and converts
nothing (D21). The last member holds three integers and a float in one `uvec4`: a `float` is just 32
bits (L00 §7.2), so Java writes it as a float and GLSL reads those bits as a `uint` and turns them
back with `uintBitsToFloat`. A `vec3` would not have saved space: in `std430` a `vec3` still starts on
a 16-byte boundary, so the fourth float of each row is free for the translation anyway.

A 3×4 record also saves 16 bytes per instance over a full `mat4`. For the 40,000 items of the test in
§32.4 that is $40{,}000 \times 64 = 2{,}560{,}000$ bytes (about 2.44 MiB) of records per frame instead
of $40{,}000 \times 80 = 3{,}200{,}000$.

**What the GPU does with it.** Binding a range of a buffer to storage binding point 0 gives the shader
a descriptor holding the range's start address and size. Each vertex shader invocation computes
`start + 64 × (gl_BaseInstance + gl_InstanceID)` and loads 64 bytes through the GPU's cache hierarchy;
every vertex of the same instance loads the same record, so after the first vertex it comes from a
cache. Reading past the bound range is undefined (or returns zeros on drivers with robust buffer
access).

### 4.2 Pulling records instead of instanced attributes

OpenGL has an older way to feed per-instance data: **instanced vertex attributes**. A vertex buffer
binding with a divisor of 1 advances once per instance instead of once per vertex, so the
per-instance data arrives as ordinary vertex inputs. Jeddy does not use it. Its records are
**pulled** from a storage buffer by index instead, because:

- a record can be any struct, not just what vertex attribute formats can express, and the vertex
  shader passes the material index on to the fragment shader as a `flat` value;
- every pass reads the same records with the same code — the depth prepass, the lit passes, the
  shadow and outline passes — and the GPU-culling compute shader writes records in the very same
  layout (§26), which a vertex-attribute layout would make awkward;
- DESIGN §9.2 records the choice — per-instance and per-material data in storage buffers — without
  giving reasons; the two above are this chapter's reading of it.

The records are written per **item** — one per (instance, submesh) pair — in sorted order, so every
command's `baseInstance` addresses a contiguous run of records (D30). The alternative, one record per
instance plus a second array of indices into it, would add an indirection to every vertex. The cost of
the choice: a mesh with three submeshes writes its record three times.

### 4.3 Where the records live: the frame ring in one page

The records and commands change every frame, the GPU reads them while the CPU prepares the next
frame, and job workers write them in parallel. [Lesson 1](lessons/01-buffers-in-flight.md) builds the
two classes that make this safe; here is what this chapter relies on.

- **[`GpuDevice`](../../modules/render/src/main/java/dev/jeddy/render/gpu/GpuDevice.java)** is the
  resource layer of chapter [05](05-opengl-from-java.md): `buffers()`, `textures()`, `samplers()` and
  `framebuffers()` behind generational handles, plus the `StateCache` that skips redundant binds.
  `useReversedZ()` calls `glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)` and `glClearDepthf(0)` once
  after context creation (chapter [04](04-cameras-and-projection.md), on reversed-Z). Every class in
  this chapter receives the device.
- **[`FrameRing`](../../modules/render/src/main/java/dev/jeddy/render/gpu/FrameRing.java)** (built in
  L01 §9–12) is one persistently and coherently mapped buffer cut into 3 equal regions, one per frame
  in flight. `beginFrame` waits — only if it must — for the fence placed after the frame that last used
  the next region, then resets a bump allocator; `allocate(bytes, alignment)` hands out aligned ranges
  lock-free (L01 §11), so workers can reserve and write without GL calls; `endFrame` fences the region.
  `SceneRenderer.Config.DEFAULT` gives each region 32 MiB (`ringBytesPerFrame = 32L << 20`), so the
  scene ring maps 96 MiB. `stalls()` counts the times the CPU had to wait.
- **[`UploadRing`](../../modules/render/src/main/java/dev/jeddy/render/gpu/UploadRing.java)** is the
  staging ring for assets on their way to their final buffer or texture, reclaimed in order by fences,
  with a per-frame byte budget. `WorldRenderer` creates it with 64 MiB and a 32 MiB budget.
- **[D103](../DESIGN.md)**: a long-lived buffer the GPU has already read (the material table, §14) is
  changed by staging the bytes in the frame ring and issuing a GPU-side copy (`FrameRing.upload`, L01
  §13), never with `glNamedBufferSubData` from the CPU — the path one Intel driver silently dropped
  (L01 §17).

Frame $N$ writes region $N \bmod 3$ (L00 §2.4):

```
time ──────────────────────────────────────────────────────────────────────────▶
CPU   | frame N: wait fence(N-3), write region N%3 | frame N+1: wait fence(N-2), write ... |
GPU         | ... executes frame N-1, reads region (N-1)%3 | executes frame N, reads N%3 |
```

The CPU waits only if it gets three whole frames ahead of the GPU (L01 §8.3 explains why three).

## 5. Sorting draws: keys and radix sort

### 5.1 Why sort

After culling, the visible objects come in whatever order the game wrote them. Sorting them serves
four goals at once:

1. **Batches.** Every draw in one multi-draw call shares the bound program and textures. Objects that
   need the same ones must sit next to each other, or the call has to be split.
2. **Runs.** Objects that draw the same submesh at the same detail level can share one indirect
   command with `instanceCount` greater than 1 — but only if their records are adjacent (§3.3). Sorting
   by mesh, LOD and submesh makes them adjacent.
3. **Front to back for opaque objects.** After the rasterizer finds the pixels a triangle covers, the
   GPU can compare each fragment's depth with the depth buffer *before* running the fragment shader
   (**early depth testing**) and drop the fragments that are hidden. Drawing near objects first means
   far fragments fail that test and their shaders never run. (Jeddy's depth prepass, §27, gets the
   most out of this.)
4. **Back to front for translucent objects.** Blending mixes a fragment's color into what is already
   there: $c = \alpha\,c_{\text{src}} + (1 - \alpha)\,c_{\text{dst}}$. That is order-dependent. Over a
   black background, a far red glass ($c = (1, 0, 0)$, $\alpha = 0.5$) then a near blue one
   ($(0, 0, 1)$, $\alpha = 0.5$) give $(0.5, 0, 0)$ and then $0.5 \cdot (0, 0, 1) + 0.5 \cdot (0.5, 0,
   0) = (0.25, 0, 0.5)$: mostly blue, as you would see it. In the wrong order the result is
   $(0.5, 0, 0.25)$: mostly red. So translucent objects must be drawn farthest first.

### 5.2 A sort key is a packed number

The engine does not sort objects with a comparator that looks at several fields. It packs every
field that matters into one unsigned 64-bit number, the **sort key**, so that ordering the keys as
plain numbers orders the items by all fields at once (the packing of L00 §6.4, with a `long`). The
most important field goes in the highest bits: two keys that differ there compare by that field,
whatever the lower bits say.

Opaque items (DESIGN §9.2):

| Bits | Width | Field | Why here |
|---|---|---|---|
| 60–63 | 4 | pass, 0 = opaque | all opaque items before all translucent ones |
| 48–59 | 12 | program slot (§14) | one program and texture set per batch |
| 28–47 | 20 | mesh (handle index) | the same mesh together |
| 24–27 | 4 | LOD | the same index range together |
| 16–23 | 8 | submesh | the same submesh together |
| 0–15 | 16 | depth, near first: $\min(65535,\ \lfloor 64 d \rfloor)$ | front to back within a run |

Translucent items:

| Bits | Width | Field | Why here |
|---|---|---|---|
| 60–63 | 4 | pass, 1 = translucent | after every opaque item |
| 28–59 | 32 | depth, far first: $\text{0xFFFFFFFF} - \min(\text{0xFFFFFFFF},\ \lfloor 4096 d \rfloor)$ | back to front comes before everything else |
| 16–27 | 12 | program slot | |
| 8–15 | 8 | submesh | |
| 0–7 | 8 | zero | |

Here $d$ is the distance from the camera to the instance. Every field starts on a 4-bit boundary, so
a key reads in hexadecimal (L00 §3.3) one group of digits per field. An opaque item with program slot
3, mesh index 17 (0x11), LOD 1, submesh 2, at distance 12.5 has depth $12.5 \cdot 64 = 800 =
\text{0x320}$:

```
pass slot  mesh   LOD sub  depth
 0   003   00011   1   02  0320      -> key 0x0003000111020320
```

The same submesh as a translucent item: $12.5 \cdot 4096 = 51200 = \text{0xC800}$, so the depth field
is $\text{0xFFFFFFFF} - \text{0xC800} = \text{0xFFFF37FF}$:

```
pass depth     slot sub  zero
 1   FFFF37FF  003  02   00          -> key 0x1FFFF37FF0030200
```

Subtracting from the largest 32-bit value reverses the order: a farther translucent item gets a
*smaller* depth field and so sorts first.

**Resolution and range.** The opaque depth moves in steps of $1/64$ of a world unit and reaches the
top of its 16 bits at $65535 / 64 \approx 1024$ units; beyond that every item gets 65535, which only
weakens the front-to-back order, never correctness. The translucent depth moves in steps of $1/4096$
and covers $\text{0xFFFFFFFF} / 4096 \approx 1{,}048{,}576$ units. The field widths are also limits:
4096 program slots (`MaterialTable` refuses more, §14), 16 LODs, 256 submeshes and $2^{20}$ mesh
indices.

Why does the translucent key hold no mesh or LOD? Translucent items never share a command (§19.2), so
there is nothing to group; the depth must come first, and what is left of 64 bits holds the slot so
that consecutive items with the same slot can still share a batch.

### 5.3 Counting sort on one byte

A **radix sort** sorts numbers by their digits without comparing numbers with each other. Take one
byte of the key — 256 possible values — and sort by it with a **counting sort**:

1. **Histogram.** Count how many keys have each byte value $0 \dots 255$.
2. **Offsets.** Running sums of the counts give, for each byte value, the position where its first key
   goes: value 0 starts at 0, value 1 starts after all the zeros, and so on.
3. **Scatter.** Walk the keys **in their current order** and put each at its byte value's position,
   then move that position on by one.

Because step 3 walks the keys in order, keys with the same byte keep their relative order: the sort is
**stable**. Everything else rests on that.

Worked example with 16-bit keys and their item numbers as payload: keys `0x0203 0x0101 0x0102 0x0201`
for items 0, 1, 2, 3. Sort by the **low** byte:

```
low bytes:   03  01  02  01
histogram:   0x01 → 2, 0x02 → 1, 0x03 → 1
offsets:     0x01 → 0, 0x02 → 2, 0x03 → 3
scatter:     0x0203 → slot 3;  0x0101 → slot 0;  0x0102 → slot 2;  0x0201 → slot 1
result:      0x0101 0x0201 0x0102 0x0203      items 1 3 2 0
```

### 5.4 Eight passes, least significant byte first

Now sort the result by the **high** byte, again stably:

```
high bytes:  01  02  01  02
offsets:     0x01 → 0, 0x02 → 2
result:      0x0101 0x0102 0x0201 0x0203      items 1 2 3 0
```

The keys are in order. Why it works: after the first pass, the keys are ordered by their low byte.
The second pass orders them by the high byte, and among keys with the **same** high byte (0x0101 and
0x0102) it keeps the order of the first pass — which was by the low byte. So the result is ordered by
high byte, then low byte: by the whole key. With eight passes over a 64-bit key, the same argument
repeats: after pass $k$ the keys are ordered by bytes $k, k-1, \dots, 0$. This is a
**least-significant-digit (LSD)** radix sort. It sorts the keys as **unsigned** numbers, because
the byte values $0 \dots 255$ are taken without a sign (L00 §5.4); the engine's keys never use bit
63, so signed and unsigned order agree for them anyway.

Two more ideas complete it:

- **The payload.** Each key carries an `int` along — the item's number. After the sort, the payload
  array says which item sits at each sorted position: `order[k]` is the item at position $k$.
- **Skipping passes.** If every key has the same value in byte $k$, sorting by that byte would leave
  the order unchanged, so that pass is skipped. Draw keys have many such bytes: in the test of §32.4
  only bytes 0 and 1 (the depth), byte 2 (the submesh) and byte 6 (the program slot) differ between
  keys, so 4 of the 8 passes run.

### 5.5 Cost, and why not `Arrays.sort`

Each pass reads every key at most twice (to count, then to scatter), so sorting $n$ keys with $P \le 8$ passes
costs work proportional to $P \cdot n$: $O(n)$ for keys of fixed size (L00 §2.8). A sort that compares
keys needs about $n \log_2 n$ comparisons: for $n = 40{,}000$, $\log_2 n \approx 15.3$, so around
600,000 comparisons against $4 \times 40{,}000 = 160{,}000$ scatter moves for the four passes above.
That is a count of operations, not a timing; `CollectionsBench` measures the two sorts against each
other ([§33](#33-things-to-run)).

The other reasons are as strong:

- `Arrays.sort(long[])` cannot carry a payload, and sorting objects (or `Long`s) with a comparator
  allocates and calls a method per comparison — banned in a frame (DESIGN §4.2).
- A radix sort splits cleanly between threads: each chunk of keys builds its own histogram, the
  histograms combine into offsets, and the chunks scatter in parallel (§18.2).
- It needs one scratch array of keys and one of payloads, allocated once and reused.

## 6. Level of detail from projected error

The cooker gives most meshes several **levels of detail** (LODs): LOD 0 is the full mesh, and each
further LOD is a simplified index list over the same vertices with fewer triangles. Each LOD records
its **geometric error** $e$ in mesh units: how far its surface may deviate from the full mesh (chapter
[10](10-assets.md)). LOD 0 has error 0. A far object can use a coarse LOD, because its error covers
less than a pixel on screen.

### 6.1 Pixels per world unit, derived

Take a perspective camera with vertical field of view $\theta$ (the full angle from the bottom to the
top of the view) and a viewport $H$ pixels high. At distance $z$ in front of the camera, the top half of
the view rises at angle $\theta/2$, so it covers $z \tan(\theta/2)$ world units, and the whole view
covers

$$\text{height at } z = 2\,z \tan(\theta / 2) \text{ world units}$$

Those units are spread over $H$ pixels, so one world unit at distance $z$ covers

$$\frac{H}{2\,z\tan(\theta/2)} = \frac{P}{z} \text{ pixels}, \qquad P = \frac{H}{2\tan(\theta/2)}$$

$P$ is "pixels per world unit at distance 1". An error of $e$ mesh units on an instance with scale $s$
(§2.3) is $e\,s$ world units, so on screen it spans about

$$e \cdot k \text{ pixels}, \qquad k = \frac{P\,s}{d}$$

The code uses the straight-line distance $d$ from the eye to the instance's origin instead of the
depth $z$ along the view direction. Off the centre of the view $d$ is a little larger than $z$, so the
error is slightly underestimated; at the top edge of a 30° view $z = d\cos 15° \approx 0.966\,d$, about
3 % — an approximation accepted for the cost of one square root.

The renderer reads $P$ straight from the projection matrix. Chapter
[04](04-cameras-and-projection.md) builds the reversed-Z perspective matrix, whose element 5 (column
1, row 1) is $f = 1/\tan(\theta/2)$ — in `Mat4Ops.perspectiveReversedZ`, `out[o + 5] = f`. So
$P = H / (2\tan(\theta/2)) = f \cdot H / 2$, which is the one line in `SceneRenderer`:

```java
private float lodPixels(RenderPacket p) {
    // Pixels per world unit: orthographic from the projection's y scale; perspective at
    // distance 1.
    return p.projection[5] * height * 0.5f;
}
```

### 6.2 The orthographic case

An orthographic view (chapter [04](04-cameras-and-projection.md)) shows the same world height
$\text{top} - \text{bottom}$ at every distance, so there is no division by distance: $P =
H / (\text{top} - \text{bottom})$ pixels per unit and $k = P\,s$. Its matrix element 5 is
$2 / (\text{top} - \text{bottom})$ (`out[o + 5] = 2 * tb` in `Mat4Ops.orthoReversedZ`), so the same
line computes $P = 2/(\text{top} - \text{bottom}) \cdot H/2 = H/(\text{top} - \text{bottom})$. The
packet's `orthographic` flag tells the draw builder which formula for $k$ to use.

### 6.3 Choosing the LOD, with numbers

Errors grow with the LOD number. The rule: take the **coarsest** LOD whose projected error stays within
a threshold $\tau$ (1 pixel in the main view), checking LODs 1, 2, … and stopping at the first that
is too coarse (§17.4 shows the code).

A 1080-pixel-high view with a 30° field of view: $P = 1080 / (2 \tan 15°) \approx 2015.3$ pixels per
unit at distance 1. An unscaled prop ($s = 1$) 40 units away has $k = 2015.3 / 40 \approx 50.38$. If
its LOD 1 has error 0.01, that is $0.01 \times 50.38 \approx 0.50$ pixels: accepted. If LOD 2 has error
0.05: $2.52$ pixels, rejected. The prop draws at LOD 1.

The test of §32.4 uses an orthographic view 200 units high on a 128-pixel target: $P = 128 / 200 =
0.64$ pixels per unit. Its mesh's LOD 1 has error 0.5, which projects to $0.5 \times 0.64 = 0.32$
pixels $\le 1$: every item draws at LOD 1.

## 7. Materials without rebinding

### 7.1 Texture units, and why a rebind splits a multi-draw

A fragment shader samples textures through numbered **texture units** (chapter
[05](05-opengl-from-java.md), on textures and samplers). Binding texture $T$ to unit 0 makes the
driver write a **descriptor** for $T$ — its address, size, format and number of mip levels — into the
table the GPU reads when a shader samples unit 0. All draws of one multi-draw call share every
binding: nothing can be changed between two of its commands. So if each material had its own
textures, every change of material would have to end the call, and the renderer would be back to one
call per material.

A material's *numbers* (colors, roughness, which texture layer) are easy: they go in a row of a
storage buffer, and each instance record names its row (§14). The question is how one batch reaches
*different textures* without a rebind.

### 7.2 Texture arrays or bindless (ADR 0001)

GL 4.6 offers two answers ([ADR 0001](../adr/0001-texture-binding.md)):

1. **Texture arrays.** A `GL_TEXTURE_2D_ARRAY` is one texture object holding many images of the same
   format, size and mip count, called **layers**. The shader declares a `sampler2DArray` and samples
   with a third coordinate, the layer: `texture(jBaseColorMap, vec3(uv, float(layer)))`. The hardware
   rounds that coordinate to the nearest whole layer and samples only that image; filtering never
   mixes layers. Jeddy groups textures into one array per (format, width, height, levels), a
   **bucket**; a material row stores each texture's layer, and the batch binds the buckets once.
2. **`ARB_bindless_texture`.** Every texture gets a 64-bit handle that is made **resident** once; the
   material row stores handles and the shader turns them into samplers. Nothing is bound at all. But
   it is an extension, missing on Intel's Windows driver for many generations and on Mesa llvmpipe,
   which CI uses.

Jeddy uses texture arrays as the baseline everywhere (D41). The ADR's measurement on llvmpipe — 256
materials with one 128² texture each, a `glFinish` per frame — is 3525 µs per frame rebinding per draw
against 2613 µs with a texture array; bindless is unsupported there. The rows for NVIDIA, AMD and
Intel are still pending, and the ADR stays *Proposed* until they are measured (TASKS M5-11). If
bindless wins clearly on hardware, it becomes an optional fast path behind the same material row.

The arrays have a consequence for batching: draws in one batch must need the **same buckets**. Which
bucket each of a material's five texture slots uses is its **texture set**, and the texture set is
part of the material's program slot (D41, §14.3, §22.4).

## 8. The frame graph idea

### 8.1 A frame is a list of passes

A frame is drawn in **passes**: light culling, GPU culling, shadow maps, a depth prepass, ambient
occlusion, the lit opaque pass, the sky, transparency, particles, outlines, bloom, exposure, tone
mapping and anti-aliasing (§27.3 lists them). Each pass reads some images and buffers and writes
others. Written by hand, the code would have to create every render target at the right size,
recreate them all when the window is resized, bind the right framebuffer before each pass, clear what
needs clearing, and place `glMemoryBarrier` calls wherever a compute shader's writes are read later.
Forget one barrier and the frame flickers — on one vendor's GPU only (L01 §15).

[`FrameGraph`](../../modules/render/src/main/java/dev/jeddy/render/graph/FrameGraph.java) (v1, D32)
makes this declarative. Each pass is declared once, at startup, with the resources it reads and
writes and how; `compile(w, h)` derives everything else at startup and on resize; `execute(frame)`
runs the result every frame and allocates nothing.

### 8.2 Which passes are needed: liveness, worked backwards

A pass is **live** — worth running — if

- it has a declared side effect, or
- it writes something the outside world can see: the window's backbuffer, or an **imported** resource
  (a buffer or texture owned outside the graph, such as the shadow atlas), or
- it writes a **transient** resource (a texture the graph owns) that a later live pass reads.

The last rule refers to *later* passes, so the graph decides from the last pass backwards: by the
time it reaches a pass, it already knows everything that later live passes need. Take the four passes
of the graph's own test (§32.5):

```
pass 0  "fill args"  compute   writes args (imported)
pass 1  "scene"      graphics  reads args as indirect arguments; writes color; depth target depth
pass 2  "unused"     graphics  writes half
pass 3  "post"       graphics  reads color as a texture; writes the backbuffer

backwards:
  pass 3 writes the backbuffer               -> live;  needs color
  pass 2 writes half, nobody needs it         -> culled
  pass 1 writes color, which is needed        -> live;  needs args and depth
  pass 0 writes args, an imported resource    -> live
```

Three passes are live and "unused" never runs.

### 8.3 Which barriers are needed: from declared reads

L01 §6.3 explained the rule. OpenGL orders most work between commands itself: a draw after a draw, a
copy before a draw, rendering into a texture and then sampling it in a later pass. What it does not
order are writes shaders make through storage buffers and images — **incoherent** writes. A later
command is only guaranteed to see them after `glMemoryBarrier` with a bit that names **how** it reads
them.

The graph knows both halves: which pass last wrote each resource, whether that pass was a compute
pass, and how each later pass reads it. So when a pass reads a resource last written by a compute
pass, the graph gives it the matching bit:

| Declared read | Barrier bit | Who reads |
|---|---|---|
| `readsTexture` | `GL_TEXTURE_FETCH_BARRIER_BIT` | shaders sampling a texture |
| `readsStorage` | `GL_SHADER_STORAGE_BARRIER_BIT` | shaders reading a storage buffer |
| `readsIndirect` | `GL_COMMAND_BARRIER_BIT` | the command processor reading draw or dispatch arguments |
| `readsImage` | `GL_SHADER_IMAGE_ACCESS_BARRIER_BIT` | shaders doing image loads |

In the test above, "fill args" is a compute pass and "scene" reads `args` with `readsIndirect`, so the
graph issues `glMemoryBarrier(GL_COMMAND_BARRIER_BIT)` before "scene" every frame.

Three limits of this v1 rule are worth knowing:

- Only **compute** writers trigger barriers: a graphics pass that wrote a storage buffer or image from
  its shaders would get none, although those writes are just as incoherent.
- A graphics pass that **renders into** a resource a compute pass wrote clears the "written by compute"
  mark without any barrier (§24.5), although GL needs `GL_FRAMEBUFFER_BARRIER_BIT` before rendering into
  an attachment that shaders wrote incoherently.
- A resource read by several later passes gets a barrier before each of them — the second is redundant,
  because a barrier covers every later command, but it is harmless.

No pass in the engine meets the first two cases today.

## 9. GPU-driven rendering

Most of a level never moves: floors, walls, pillars, props. Yet the CPU path culls them, sorts them
and writes their records again every frame. The **GPU-driven** path keeps them on the GPU and lets
compute shaders do that per-frame work (§26). It needs three GPU features.

### 9.1 Compute shaders, workgroups and invocations

A **compute shader** (L00 §10.2) runs a grid of **invocations** with no rasterization and no
framebuffer: it reads and writes buffers. Invocations come in **workgroups**, whose size the shader
declares:

```glsl
layout(local_size_x = 64) in;
```

`glDispatchCompute(g, 1, 1)` launches $g$ workgroups of 64 invocations, and each invocation finds its
number in `gl_GlobalInvocationID.x` $=$ workgroup $\times 64 +$ position in the workgroup. To give
each of $n$ items its own invocation, the CPU launches $\lceil n/64 \rceil$ groups — `(n + 63) / 64`,
the round-up of L00 §2.5 — and the extra invocations of the last group return at once. For $n =
20{,}000$: 313 groups, 20,032 invocations, 32 of them idle.

The GPU spreads workgroups over its compute units; within one, invocations execute in lockstep groups
of 32 or 64 (L00 §10.1). Nothing orders one workgroup against another: invocation 5 may run after
invocation 20,000.

### 9.2 `atomicAdd`: counting and reserving at once

Several invocations may want to append to the same list at once. If each read a counter, added one
and wrote it back, two of them could read the same value and overwrite each other's entry — the race
of L00 §9.5. GLSL's

```glsl
uint old = atomicAdd(counter, 1u);
```

adds 1 to a value in a buffer **indivisibly** and returns the value it had before. Every caller gets a
different `old`: three invocations hitting a counter that holds 0 receive 0, 1 and 2 in some order,
and the counter ends at 3. So one operation both **counts** (the final value) and **reserves** a
unique slot (the returned value). It is the GPU's version of the compare-and-set loop of L01 §11, done
by the memory system in one step. The order in which invocations get their slots changes from frame
to frame; the static scene only uses it for opaque draws, where order inside a run does not matter.

### 9.3 Draws whose count the GPU decides

The culling shader decides on the GPU how many objects are visible. The CPU could read that number
back, but reading back waits for the GPU — a stall of a whole frame or more. GL 4.6 instead has

```c
void glMultiDrawElementsIndirectCount(GLenum mode, GLenum type, const void *indirect,
                                      GLintptr drawcount, GLsizei maxdrawcount, GLsizei stride);
```

It is `glMultiDrawElementsIndirect` (§3.5), except that the number of commands is read by the GPU
from the buffer bound to `GL_PARAMETER_BUFFER`, at byte offset `drawcount`, and never exceeds
`maxdrawcount`. The CPU says "up to this many, the real count is over there"; the command processor
reads the count and runs that many commands. The CPU never learns how many objects are visible, and
never needs to.

### 9.4 Ordering the steps: barriers

Per view, the static scene does four things in the command stream, then the next frame starts over:

```
reset    copy the command template, clear the per-batch counts      (buffer commands)
cull     compute: test each item, atomicAdd on its command's
         instanceCount, write its record                            (shader writes)
compact  compute: read the instance counts, copy non-empty commands
         into a packed area, count them per batch                   (shader writes)
draw     glMultiDrawElementsIndirectCount per batch: the command
         processor reads packed commands and counts; vertex
         shaders read the records                                   (reads)
reset    the next frame's copy and clear overwrite the commands     (buffer commands)
```

Two facts from L01 §6.3 decide where the barriers go:

- **A barrier covers every command issued after it**, not only the next one, as long as the later
  command reads through a path the bits name.
- **The bits name the reader**: storage reads, the command processor, buffer copies and clears.

So, reading each step as "who reads, later, what the shaders before me wrote":

| After | Barrier bits | Because |
|---|---|---|
| cull | `GL_SHADER_STORAGE_BARRIER_BIT` | compact reads the instance counts the atomics wrote, as a storage buffer — and so, later, do the draws' vertex shaders read the records cull wrote. One barrier serves both, because it covers every later command. |
| compact | `GL_COMMAND_BARRIER_BIT` | the draws' command processor reads the packed commands (through `GL_DRAW_INDIRECT_BUFFER`) and the counts (through `GL_PARAMETER_BUFFER`) |
| | `GL_SHADER_STORAGE_BARRIER_BIT` | later shaders that read this view's buffers as storage |
| | `GL_BUFFER_UPDATE_BARRIER_BIT` | the next cull of this view resets the commands with a buffer copy and a clear. Those overwrite memory the shaders wrote, and GL orders a copy or clear after earlier shader writes to the same memory only when this bit was issued in between. |

No barrier is needed *before* the cull: the reset is a copy and a clear, buffer commands that GL
orders against the later shader reads by itself. Forgetting one of these barriers is the GPU's data
race: many drivers happen to finish one dispatch before starting the next, so the bug hides, until
another driver overlaps them and the draws read half-written commands (L01 §15).

# Part B — Building it

## 10. Step 1: the render packet

### 10.1 What the main thread needs

The game thread simulates; the main thread draws (DESIGN §5). They meet in a **render packet**: a
plain description of one frame that the game thread fills during its `extract` step and the main thread
reads to draw. Three packets rotate through a triple buffer so the two threads never touch the same
one, and the only synchronization is an atomic swap of an index (chapter
[01](01-architecture.md), on the triple buffer handoff). Everything in a packet is preallocated and
stored as **struct of arrays** — one primitive array per field (L03 §4) — so filling it allocates
nothing and the draw code reads it with sequential loops.

Create `RenderPacket.java` in `dev.jeddy.render`. Its thread ownership, stated in its Javadoc: the
game thread writes a packet between acquiring its write index and publishing it; the main thread
reads it after acquiring the read index; never both at once.

### 10.2 The instance arrays and their flags

```java
public final class RenderPacket {
    /** Material index meaning "each submesh draws with its mesh's own material". */
    public static final int MESH_MATERIALS = -1;

    /** Instance flag: never moves (render may keep its data on the GPU). */
    public static final int FLAG_STATIC = 1;

    /** Instance flag: casts shadows. */
    public static final int FLAG_CASTS_SHADOW = 2;

    /** Instance flag: drawn in the transparent pass. */
    public static final int FLAG_TRANSLUCENT = 4;

    /** Instance flag: hovered or selected (outline pass). */
    public static final int FLAG_HIGHLIGHT = 8;

    /** Instance flag: decals do not project onto it (characters, props standing in AoE areas). */
    public static final int FLAG_NO_DECALS = 16;
```

`FLAG_FADE_OCCLUDER = 32` follows, then two shifts for fields packed into the same `int`. In bits:

| Bits | Name | Meaning |
|---|---|---|
| 0 | `FLAG_STATIC` | never moves; may live on the GPU (§26) |
| 1 | `FLAG_CASTS_SHADOW` | drawn into shadow maps |
| 2 | `FLAG_TRANSLUCENT` | drawn in the transparent pass, sorted back to front |
| 3 | `FLAG_HIGHLIGHT` | outlined (hover, selection) |
| 4 | `FLAG_NO_DECALS` | decals do not project onto it |
| 5 | `FLAG_FADE_OCCLUDER` | dissolves between the camera and reveal points |
| 8–11 | `HIGHLIGHT_SHIFT` | outline style, 0 = none |
| 12–31 | `PALETTE_SHIFT` | skinning palette base + 1, 0 = not skinned |

Each flag is a power of two so that it owns one bit (L00 §6.2). The flags travel unchanged into the
instance record's `data.y` (§4.1), so the shaders test the same bits.

The instance arrays, with the parallel layout of L03 §4 — instance $i$'s mesh is `mesh[i]`, its
transform `currWorld[12 i .. 12 i + 11]`:

```java
    private int capacity;
    private int count;

    /** Previous tick's world transforms, 12 floats per instance (row-major 3×4). */
    public float[] prevWorld;

    /** Current tick's world transforms, 12 floats per instance. */
    public float[] currWorld;

    /** Mesh handle per instance. */
    public int[] mesh;

    /** Material index per instance, or {@link #MESH_MATERIALS}. */
    public int[] material;

    /** {@code FLAG_*} bits per instance. */
    public int[] flags;

    /** The entity each instance came from (picking, debugging). */
    public int[] entity;

    /** Bounding sphere per instance in world space: x, y, z, radius (4 floats). */
    public float[] bounds;

    /**
     * Camera fade per instance, 0 (solid) to 1 (gone), drawn as a screen-door dither: roofs while
     * the party is inside, objects the game wants out of the way.
     */
    public float[] fade;

    /** Interpolation factor between {@link #prevWorld} and {@link #currWorld}, in [0, 1]. */
    public float alpha;
```

- **Two transforms per instance.** The simulation runs at a fixed 30 ticks a second (D13) and the
  screen faster, so every frame falls between two ticks. The packet carries the transform at the
  previous tick and at the current one, and `alpha` says how far between them the frame is; the draw
  builder blends them as it writes the records (§19.3; chapter [01](01-architecture.md), on
  interpolation).
- **Row-major 3×4**, the layout of §2.2 and of the GPU record, so transforms are copied, never
  converted (DESIGN §8.2, D21).
- **`mesh` is a handle** into the mesh registry (§12; handles are L03 §6), and `material` an index into
  the material table (§14), or `MESH_MATERIALS` to use each submesh's default material.
- **`public` arrays.** Unusual in Java, deliberate here: the game thread's extraction jobs and the
  renderer's kernels index them directly in tight loops.

The camera arrives as plain matrices and planes — `view`, `projection`, `viewProjection`,
`inverseViewProjection` (column-major `float[16]`, chapter [04](04-cameras-and-projection.md)),
`frustumPlanes` (24 floats for `CullOps`), `eye` — set together by `setCamera`. Beside them the packet
holds what other chapters draw: lights already in their GPU layout of 16 floats each (D44, chapter
[07](07-lighting-and-shadows.md)), decals, particle emitters, reveal points and the fog-of-war grid
(chapter [08](08-effects-and-post.md)), skinning palettes (§30, chapter
[09](09-animation-and-skinning.md)), debug lines (§31) and the user interface's draw list (chapter
[13](13-ui.md)). And one counter that matters for §26:

```java
    /** Bumped by the game whenever static geometry changes (invalidates cached shadow maps). */
    public int staticVersion;
```

### 10.3 Allocating and adding

```java
    /** Creates a packet for {@code capacity} instances (grows on demand). */
    public RenderPacket(int capacity) {
        allocate(Math.max(16, capacity));
    }

    private void allocate(int cap) {
        capacity = cap;
        prevWorld = prevWorld == null ? new float[cap * 12] : Arrays.copyOf(prevWorld, cap * 12);
        currWorld = currWorld == null ? new float[cap * 12] : Arrays.copyOf(currWorld, cap * 12);
        mesh = mesh == null ? new int[cap] : Arrays.copyOf(mesh, cap);
        material = material == null ? new int[cap] : Arrays.copyOf(material, cap);
        flags = flags == null ? new int[cap] : Arrays.copyOf(flags, cap);
        entity = entity == null ? new int[cap] : Arrays.copyOf(entity, cap);
        bounds = bounds == null ? new float[cap * 4] : Arrays.copyOf(bounds, cap * 4);
        fade = fade == null ? new float[cap] : Arrays.copyOf(fade, cap);
    }
```

One method serves both creation (every array still `null`) and growth (`Arrays.copyOf` keeps the
contents). Growth allocates, which is fine: a game that knows its peak calls `reserve` up front, and
otherwise the packet stops growing after the first busy frames.

```java
    public int add(
            float[] prev,
            int prevOffset,
            float[] curr,
            int currOffset,
            int meshHandle,
            int materialIndex,
            int instanceFlags,
            int entityHandle) {
        if (count == capacity) {
            allocate(capacity * 2);
        }
        int i = count++;
        System.arraycopy(prev, prevOffset, prevWorld, i * 12, 12);
        System.arraycopy(curr, currOffset, currWorld, i * 12, 12);
        mesh[i] = meshHandle;
        material[i] = materialIndex;
        flags[i] = instanceFlags;
        entity[i] = entityHandle;
        fade[i] = 0;
        return i;
    }
```

- **Doubling** when full: the total copying stays proportional to the final size (L01 §7.3 makes the
  same argument).
- **Offsets, not arrays.** The caller passes a big array of transforms and where this instance's 12
  floats start, so it never creates a small array per instance.
- **`fade[i] = 0`.** The other fields are all written by the caller; the fade is optional
  (`setFade`), so it is reset here or the instance would inherit the fade of whatever used index $i$
  last frame.
- **Bounds are separate** (`setBounds(i, x, y, z, radius)`), because the caller often computes the
  world sphere after placing the instance.

`reset()` empties the packet (count, lights, decals, debug lines, palettes, the UI list) without
freeing anything, before the game thread fills it again.

### 10.4 Two fields in the high bits: highlight and palette

The skinning palette (chapter [09](09-animation-and-skinning.md)) is stored in the top 20 bits of the
instance's flags, so the 64-byte record needs no extra field (D80):

```java
    public void setPalette(int i, int base) {
        int f = flags[i] & ((1 << PALETTE_SHIFT) - 1);
        if (base >= 0) {
            f = (f & ~FLAG_STATIC) | (base + 1) << PALETTE_SHIFT;
        }
        flags[i] = f;
    }

    /** Returns the palette base of instance {@code i}, or -1 if it does not skin. */
    public int palette(int i) {
        return (flags[i] >>> PALETTE_SHIFT) - 1;
    }
```

- `(1 << PALETTE_SHIFT) - 1` is `0xFFF`, twelve ones: the mask keeps bits 0–11 (the flags and the
  highlight style) and clears the old palette (L00 §6.5).
- **Base + 1**, so that 0 can mean "not skinned". The 20 bits hold up to $2^{20} - 1$
  (`MAX_PALETTE_MATRICES`).
- **`& ~FLAG_STATIC`**: an animated instance moves every frame, so it can never be static (§26).
- `palette` shifts the field down with `>>>` (L00 §6.3), which fills with zeros, and subtracts the 1
  again: −1 for "none".

`setHighlight` packs the 4-bit outline style into bits 8–11 the same way and sets or clears
`FLAG_HIGHLIGHT` with it.

### 10.5 Three packets

[`RenderPackets`](../../modules/render/src/main/java/dev/jeddy/render/RenderPackets.java) is the
whole of the triple buffer's storage:

```java
public final class RenderPackets {
    private final RenderPacket[] packets = new RenderPacket[3];

    /** Creates three packets for {@code capacity} instances each. */
    public RenderPackets(int capacity) {
        for (int i = 0; i < 3; i++) {
            packets[i] = new RenderPacket(capacity);
        }
    }

    /** Returns the packet at a triple-buffer index (0 to 2). */
    public RenderPacket packet(int index) {
        return packets[index];
    }
}
```

The game thread fills `packet(writeIndex)` in `Game.extract`; the main thread draws
`packet(readIndex)`. Which index is which is the business of the app's triple buffer (chapter
[01](01-architecture.md)); the packets themselves know nothing about threads.

## 11. Step 2: the range allocator

### 11.1 The problem

The mega buffers (§12) hold every mesh. Meshes arrive and leave while the game runs, each needing a
different number of vertex slots and index slots. The registry needs to hand out a range of the
**vertex space** (slots $0 \dots$ capacity) for each new mesh, take it back when the mesh is unloaded,
and reuse the hole later. GPU memory cannot be allocated and freed per mesh cheaply — every buffer is a
driver object — so the engine allocates a few large buffers and manages the space inside them itself.

[`RangeAllocator`](../../modules/core/src/main/java/dev/jeddy/core/memory/RangeAllocator.java) in
`core` does that for any space of numbered units — vertex slots, index slots, rows of a table. It
keeps a **free list**: the free ranges, sorted by offset. To allocate it takes the **first** free range
that fits (**first fit**); to free it puts the range back and **merges** it with free neighbours, so
that freeing everything restores one big range.

*Why not something else?* A **bump allocator** (L01 §8.2) cannot free single ranges. A **buddy
allocator** rounds every request up to a power of two, wasting up to half of each range — expensive for
vertex data. **Best fit** (the smallest range that fits) must scan the whole list on every
allocation. First fit over a sorted list is the simplest design that reuses holes and keeps them
merged; DESIGN §9.2 asks for "a free list", and meshes are added while loading, not per frame, so a
scan over a short list is cheap enough.

### 11.2 Fields

```java
public final class RangeAllocator {
    private long capacity;
    private long[] offsets = new long[16];
    private long[] sizes = new long[16];
    private int count;
    private long free;

    /** Creates an allocator over {@code [0, capacity)}, all free. */
    public RangeAllocator(long capacity) {
        this.capacity = capacity;
        if (capacity > 0) {
            offsets[0] = 0;
            sizes[0] = capacity;
            count = 1;
        }
        free = capacity;
    }
```

- The free list is two parallel `long` arrays (L03 §4): range $i$ is
  $[\text{offsets}[i], \text{offsets}[i] + \text{sizes}[i])$, and the ranges are kept sorted by offset
  and never touch (touching ranges are merged).
- `long`, because the same class also manages byte spaces that may pass 2 GiB (L00 §4.3).
- `free` is the total free size, kept up to date for statistics and tests.
- At the start the whole space is one free range. Thread ownership: one owner thread; not
  thread-safe.

### 11.3 `allocate`: first fit with alignment

```java
    public long allocate(long size, long alignment) {
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive: " + size);
        }
        for (int i = 0; i < count; i++) {
            long start = offsets[i];
            long aligned = (start + alignment - 1) & -alignment;
            long pad = aligned - start;
            if (sizes[i] < size + pad) {
                continue;
            }
            long end = start + sizes[i];
            long tailStart = aligned + size;
            // Replace range i by the padding before and the remainder after.
            if (pad > 0 && tailStart < end) {
                sizes[i] = pad;
                insert(i + 1, tailStart, end - tailStart);
            } else if (pad > 0) {
                sizes[i] = pad;
            } else if (tailStart < end) {
                offsets[i] = tailStart;
                sizes[i] = end - tailStart;
            } else {
                remove(i);
            }
            free -= size;
            return aligned;
        }
        return -1;
    }
```

- **Alignment** with the power-of-two round-up of L00 §6.6. The mesh registry always asks for
  alignment 1 (any slot), so `pad` is 0 there; other users need more.
- **The fit test** counts the padding: the range must hold the skipped units plus the request.
- **Four cases** for what is left of range $i$ around the allocated piece
  $[\text{aligned}, \text{tailStart})$: padding and a tail (the range splits in two, so a new entry
  is inserted after it), only padding, only a tail (the range shrinks from the front), or nothing (the
  range is removed). Because the pieces stay in the same place in the list, it stays sorted.
- **−1** when nothing fits: the caller decides what to do (the registry grows the space, §12.6).

Worked example: free list $[0, 100)$, allocate 30 at alignment 16. Range 0: `aligned` 0, `pad` 0,
`tailStart` 30 → third case: the list becomes $[30, 100)$, result 0. Then allocate 10 at alignment 16:
`start` 30, `aligned` $= (30 + 15)\ \&\ {-16} = 32$, `pad` 2, `tailStart` 42 → first case: the list
becomes $[30, 32), [42, 100)$, result 32. The two units 30–31 stay free for a later request with
alignment 1.

### 11.4 `free`: put back and merge

```java
    public void free(long offset, long size) {
        if (offset < 0 || size <= 0 || offset + size > capacity) {
            throw new IllegalArgumentException("range outside the space: " + offset + "+" + size);
        }
        int i = Arrays.binarySearch(offsets, 0, count, offset);
        if (i >= 0) {
            throw new IllegalStateException("double free at " + offset);
        }
        i = -i - 1; // insertion point
        if (i > 0 && offsets[i - 1] + sizes[i - 1] > offset
                || i < count && offset + size > offsets[i]) {
            throw new IllegalStateException("free overlaps a free range at " + offset);
        }
        boolean mergePrev = i > 0 && offsets[i - 1] + sizes[i - 1] == offset;
        boolean mergeNext = i < count && offset + size == offsets[i];
        if (mergePrev && mergeNext) {
            sizes[i - 1] += size + sizes[i];
            remove(i);
        } else if (mergePrev) {
            sizes[i - 1] += size;
        } else if (mergeNext) {
            offsets[i] = offset;
            sizes[i] += size;
        } else {
            insert(i, offset, size);
        }
        free += size;
    }
```

- **Binary search** (L00 §2.8) on the sorted offsets finds where the range belongs in $O(\log n)$.
  `Arrays.binarySearch` returns the index if the offset is already a free range's start — which can
  only mean the range was already freed: a **double free**, reported at once. Otherwise it returns
  $-(\text{insertion point}) - 1$, and `-i - 1` recovers the insertion point.
- **Overlap check.** If the free range before it reaches past `offset`, or the range being freed
  reaches into the next free range, part of it was already free: another misuse, reported.
- **Merging, four cases.** It touches the previous free range, the next one, both (three ranges
  become one, and the next entry is removed), or neither (a new entry is inserted). Merging is what
  makes `RangeAllocatorTest` end with a single free range after freeing everything (§32.2).

### 11.5 `grow`, and the two helpers

```java
    public void grow(long newCapacity) {
        if (newCapacity <= capacity) {
            return;
        }
        long added = newCapacity - capacity;
        long old = capacity;
        capacity = newCapacity;
        if (count > 0 && offsets[count - 1] + sizes[count - 1] == old) {
            sizes[count - 1] += added;
        } else {
            insert(count, old, added);
        }
        free += added;
    }
```

After the owner has made its storage bigger, the new units $[\text{old}, \text{new})$ become free: they
extend the last free range if it reached the old end, or become a new range at the end of the list.

`insert(at, offset, size)` and `remove(at)` shift the tail of both arrays by one with
`System.arraycopy`; `insert` doubles the arrays when they are full (the only allocation, and only
when the list grows past its largest size so far).

## 12. Step 3: the mesh registry

### 12.1 What a cooked mesh contains

The offline cooker turns glTF meshes into `.jmesh` files (chapter [10](10-assets.md); byte layout in
[`docs/formats/jmesh.md`](../formats/jmesh.md)), which
[`MeshData`](../../modules/asset/src/main/java/dev/jeddy/asset/format/MeshData.java) reads in place.
What the renderer needs from one:

- **Vertex streams**, separate (D27): positions (`POSITION_STRIDE` = 12 bytes, three `f32`),
  attributes (`ATTRIBUTE_STRIDE` = 16 bytes: a QTangent — the normal and tangent as a quaternion — in
  four `snorm16`, then two half-float UV sets) and, for skinned meshes, the skin stream (`SKIN_STRIDE`
  = 16 bytes: four `u16` joint numbers and four `unorm16` weights). Chapter
  [05](05-opengl-from-java.md), on vertex arrays, shows how the GPU decodes them.
- **Indices**: `u32`, *relative to their submesh's base vertex* (D27).
- **Submeshes**: one per material slot, each with a base vertex and a vertex count. A submesh is one
  draw.
- **LODs**: LOD 0 is the full mesh; each further LOD is a simplified index list over the same
  vertices, with its geometric error (§6). The LOD table holds, LOD-major, a (first index, index
  count) pair per (LOD, submesh).
- **Bounds**: a box and a sphere, and the default material of each slot.

The reader validates every index against its submesh's vertex count on a worker, so a corrupt file
never reaches the GPU (§3.5 explains why the GPU would not notice).

### 12.2 One vertex space, three buffers

[`MeshRegistry`](../../modules/render/src/main/java/dev/jeddy/render/mesh/MeshRegistry.java) owns
the **mega buffers**: positions, attributes and indices, plus a skin buffer created with the first
skinned mesh (D80). The vertex streams share one **vertex space**: vertex slot $v$ is at byte $12v$ of
the positions buffer, $16v$ of the attributes and $16v$ of the skins. A mesh owns a range of slots in
all streams at once, and one range of the index space:

```
vertex space          0         4         8                          capacity
                      [ mesh A  | mesh B  |   free ...                ]
  positions  (12 B per slot), attributes (16 B), skins (16 B, if any): same slots

index space           0                  9                  18
                      [ A: LOD0 6 | LOD1 3 | B: LOD0 6 | LOD1 3 | free ... ]
```

The fields:

```java
public final class MeshRegistry implements AutoCloseable {
    /** Vertex buffer binding of the position stream. */
    public static final int BINDING_POSITIONS = 0;

    /** Vertex buffer binding of the attribute stream. */
    public static final int BINDING_ATTRIBUTES = 1;

    /** Vertex buffer binding of the skin stream (attributes 4 and 5). */
    public static final int BINDING_SKIN = 2;

    private final GpuDevice device;
    private final UploadRing ring;
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment idScratch = arena.allocate(16);
    private final int vao;
    private int positions;
    private int attributes;
    private int indices;
    private int skins; // 0 until the first skinned mesh
    private final RangeAllocator vertexSpace;
    private final RangeAllocator indexSpace;
    private final RangeAllocator submeshSpace = new RangeAllocator(1024);
    private final RangeAllocator drawSpace = new RangeAllocator(4096);
    private final RangeAllocator lodSpace = new RangeAllocator(1024);
    private final HandlePool handles = new HandlePool(256, Handles.MAX_INDEX + 1);

    // Per mesh.
    private int[] vertexOffset = new int[256];
    private int[] vertexCount = new int[256];
    private int[] indexOffset = new int[256];
    private int[] indexCount = new int[256];
    private int[] submeshCount = new int[256];
    private int[] lodCount = new int[256];
    private int[] submeshBase = new int[256];
    private int[] drawBase = new int[256];
    private int[] lodBase = new int[256];
    private float[] spheres = new float[256 * 4];
    private boolean[] skinned = new boolean[256];

    // Flat tables.
    private int[] submeshBaseVertex = new int[1024]; // absolute
    private int[] submeshMaterial = new int[1024];
    private int[] submeshMaterialIndex = new int[1024]; // material table index per submesh
    private int[] drawFirstIndex = new int[4096]; // absolute
    private int[] drawIndexCount = new int[4096];
    private float[] lodError = new float[1024];

    private long uploadedBytes;
```

Three groups:

- **GPU storage**: one vertex array (`vao`) and the buffer handles of the streams. `skins` is 0 until a
  skinned mesh arrives, so scenes without animation never pay for 16 bytes per vertex slot.
- **Five range allocators.** Two manage GPU space (vertex slots, index slots). The other three manage
  rows of the CPU-side **flat tables**: a mesh with 2 submeshes and 3 LODs needs 2 rows of the
  submesh table, $2 \times 3 = 6$ rows of the draw table and 3 rows of the LOD table, and those rows
  are reused when meshes go, exactly like GPU space.
- **SoA per mesh and per table row** (L03 §4). A mesh handle's slot index (L03 §6) selects the per-mesh
  row; `submeshBase[m]`, `drawBase[m]` and `lodBase[m]` say where its rows of the flat tables start.

Thread ownership: the GL thread. The draw builder's workers read the query methods (§12.7) while no
mesh is added or removed.

### 12.3 The constructor: buffers and the one vertex array

```java
    public MeshRegistry(GpuDevice device, UploadRing ring, int vertices, int indexCapacity) {
        this.device = device;
        this.ring = ring;
        vertexSpace = new RangeAllocator(vertices);
        indexSpace = new RangeAllocator(indexCapacity);
        GpuBuffers b = device.buffers();
        positions =
                b.create(
                        (long) vertices * MeshData.POSITION_STRIDE,
                        GpuBuffers.DYNAMIC,
                        "mesh positions");
        attributes =
                b.create(
                        (long) vertices * MeshData.ATTRIBUTE_STRIDE,
                        GpuBuffers.DYNAMIC,
                        "mesh attributes");
        indices = b.create(4L * indexCapacity, GpuBuffers.DYNAMIC, "mesh indices");
```

`GpuBuffers.DYNAMIC` asks for `GL_DYNAMIC_STORAGE_BIT` (L01 §4, §7.3), which the fallback upload of
§12.5 needs. The `(long)` casts come before the multiplications (L00 §4.3).

```java
        MemorySegment id = idScratch;
        glCreateVertexArrays(1, id);
        vao = id.get(ValueLayout.JAVA_INT, 0);
        GLDebug.label(GL_VERTEX_ARRAY, vao, "mesh vertex array");
        glEnableVertexArrayAttrib(vao, 0);
        glVertexArrayAttribFormat(vao, 0, 3, GL_FLOAT, false, 0);
        glVertexArrayAttribBinding(vao, 0, BINDING_POSITIONS);
        glEnableVertexArrayAttrib(vao, 1);
        glVertexArrayAttribFormat(vao, 1, 4, GL_SHORT, true, 0);
        glVertexArrayAttribBinding(vao, 1, BINDING_ATTRIBUTES);
        glEnableVertexArrayAttrib(vao, 2);
        glVertexArrayAttribFormat(vao, 2, 2, GL_HALF_FLOAT, false, 8);
        glVertexArrayAttribBinding(vao, 2, BINDING_ATTRIBUTES);
        glEnableVertexArrayAttrib(vao, 3);
        glVertexArrayAttribFormat(vao, 3, 2, GL_HALF_FLOAT, false, 12);
        glVertexArrayAttribBinding(vao, 3, BINDING_ATTRIBUTES);
        // Skin stream: integer joints and normalized weights. Enabled only once the buffer exists.
        glVertexArrayAttribIFormat(vao, 4, 4, GL_UNSIGNED_SHORT, 0);
        glVertexArrayAttribBinding(vao, 4, BINDING_SKIN);
        glVertexArrayAttribFormat(vao, 5, 4, GL_UNSIGNED_SHORT, true, 8);
        glVertexArrayAttribBinding(vao, 5, BINDING_SKIN);
        bindBuffers();
    }
```

This is chapter [05](05-opengl-from-java.md)'s vertex array, described once for every mesh: attribute
0 is the position (three floats at offset 0 of binding 0); attributes 1–3 come from the 16-byte
attribute stream at offsets 0 (the QTangent, four signed shorts normalized to $[-1, 1]$), 8 and 12
(the two UV sets, two half floats each); attributes 4 and 5 are the skin stream's joints (integer,
`IFormat`, so the shader gets whole numbers) and weights (normalized to $[0, 1]$). The skin attributes
are described now but enabled only when the skin buffer exists.

```java
    private void bindBuffers() {
        GpuBuffers b = device.buffers();
        glVertexArrayVertexBuffer(
                vao, BINDING_POSITIONS, b.glName(positions), 0, MeshData.POSITION_STRIDE);
        glVertexArrayVertexBuffer(
                vao, BINDING_ATTRIBUTES, b.glName(attributes), 0, MeshData.ATTRIBUTE_STRIDE);
        glVertexArrayElementBuffer(vao, b.glName(indices));
        if (skins != 0) {
            glVertexArrayVertexBuffer(vao, BINDING_SKIN, b.glName(skins), 0, MeshData.SKIN_STRIDE);
        }
    }

    private void createSkins() {
        skins =
                device.buffers()
                        .create(
                                vertexSpace.capacity() * MeshData.SKIN_STRIDE,
                                GpuBuffers.DYNAMIC,
                                "mesh skins");
        glEnableVertexArrayAttrib(vao, 4);
        glEnableVertexArrayAttrib(vao, 5);
        bindBuffers();
    }
```

`bindBuffers` points the vertex array's three bindings and its element buffer at the current buffers;
it runs again whenever a buffer is replaced (§12.6). Every stream starts at offset 0 with its own
stride, which is what makes "slot $v$" mean the same vertex in every stream. The skin buffer covers the
whole vertex space, so the slots of unskinned meshes hold whatever the buffer holds — only `SKINNED`
programs read them (D80).

### 12.4 `add`: reserve, upload, record

```java
    public int add(MeshData data) {
        int vc = data.vertexCount();
        int ic = data.indexCount();
        int sc = data.submeshCount();
        int lc = data.lodCount();
        long vOff = allocate(vertexSpace, Math.max(1, vc), true);
        long iOff = allocate(indexSpace, Math.max(1, ic), false);
        int sBase = (int) allocateTable(submeshSpace, Math.max(1, sc), 0);
        int dBase = (int) allocateTable(drawSpace, Math.max(1, sc * lc), 1);
        int lBase = (int) allocateTable(lodSpace, lc, 2);
        GpuBuffers b = device.buffers();
        upload(b.glName(positions), vOff * MeshData.POSITION_STRIDE, data.positions());
        upload(b.glName(attributes), vOff * MeshData.ATTRIBUTE_STRIDE, data.attributes());
        upload(b.glName(indices), iOff * 4, data.indices());
        if (data.hasSkin()) {
            if (skins == 0) {
                createSkins();
            }
            upload(b.glName(skins), vOff * MeshData.SKIN_STRIDE, data.skin());
        }
```

- **Reserve first.** Vertex slots, index slots and the three table ranges. `Math.max(1, …)` because
  `RangeAllocator.allocate` rejects size 0 and an empty mesh still needs a valid handle. The `true`
  and `false` of `allocate`, and the table numbers 0–2 of `allocateTable`, say which buffers or
  tables to grow if the space is full (§12.6, §12.7).
- **Upload** each stream at its slot's byte offset: slot × stride, and index slot × 4.

```java
        int h = handles.allocate();
        int m = Handles.index(h);
        ensureMeshes(m);
        vertexOffset[m] = (int) vOff;
        vertexCount[m] = vc;
        indexOffset[m] = (int) iOff;
        indexCount[m] = ic;
        submeshCount[m] = sc;
        lodCount[m] = lc;
        submeshBase[m] = sBase;
        drawBase[m] = dBase;
        lodBase[m] = lBase;
        for (int i = 0; i < 4; i++) {
            spheres[m * 4 + i] = data.sphere(i);
        }
        skinned[m] = data.hasSkin();
        for (int s = 0; s < sc; s++) {
            submeshBaseVertex[sBase + s] = (int) vOff + data.submeshBaseVertex(s);
            submeshMaterial[sBase + s] = data.submeshMaterial(s);
            submeshMaterialIndex[sBase + s] = 0;
        }
        for (int l = 0; l < lc; l++) {
            lodError[lBase + l] = data.lodError(l);
            for (int s = 0; s < sc; s++) {
                drawFirstIndex[dBase + l * sc + s] = (int) iOff + data.lodFirstIndex(l, s);
                drawIndexCount[dBase + l * sc + s] = data.lodIndexCount(l, s);
            }
        }
        return h;
    }
```

The key idea is in the two loops: the tables store **absolute** numbers. A submesh's base vertex in
the file is relative to the mesh (where its vertices start inside the mesh); adding the mesh's slot
`vOff` makes it a slot of the mega buffer. A LOD's first index in the file is relative to the mesh's
own index list; adding `iOff` makes it an index of the mega index buffer. Those absolute numbers are
exactly the `baseVertex` and `firstIndex` fields of an indirect command (§3.4), so building a command
is three table lookups and no arithmetic. The draw table is LOD-major — row `l * sc + s` — like the
file. `submeshMaterialIndex` starts at 0, the default material, until the mesh's own materials load
(§12.9).

### 12.5 `upload`: through the staging ring, or directly

```java
    private void upload(int buffer, long dstOffset, MemorySegment src) {
        long n = src.byteSize();
        if (n == 0) {
            return;
        }
        uploadedBytes += n;
        if (ring != null) {
            long off = ring.reserve(n, 16);
            if (off >= 0) {
                MemorySegment.copy(src, 0, ring.memory(), off, n);
                ring.copyToBuffer(off, buffer, dstOffset, n);
                return;
            }
        }
        glNamedBufferSubData(buffer, dstOffset, n, src.address()); // the driver copies now
    }
```

The normal path is the GPU copy of L01 §5: reserve 16-aligned space in the `UploadRing`, copy the
stream into that mapped memory, then `copyToBuffer` issues `glCopyNamedBufferSubData` from the ring
into the mega buffer — a command with its place in the stream, after every earlier draw. If there is
no ring (tests, tools), or it is full or over this frame's byte budget, the fallback is
`glNamedBufferSubData` straight from the source's native address (the `long` overload of D29): the
driver copies the bytes during the call. That is why the mega buffers are created `DYNAMIC`. (A third
case has no fallback: a single stream larger than the whole upload ring makes `UploadRing.reserve`
throw an `IllegalArgumentException` — 64 MiB in `WorldRenderer`.) Note
what this fallback is: a CPU write into a buffer earlier frames have read — the kind of write D103
replaced for the material table after one driver dropped it (L01 §17.3). A mesh uploaded mid-game
while the upload ring is out of room still takes this path.

### 12.6 Growth

When a range allocation fails, the space grows:

```java
    private long allocate(RangeAllocator space, long n, boolean vertices) {
        long off = space.allocate(n, 1);
        while (off < 0) {
            long old = space.capacity();
            long cap = Math.max(old * 2, old + n);
            if (vertices) {
                positions =
                        regrow(
                                positions,
                                old * MeshData.POSITION_STRIDE,
                                cap * MeshData.POSITION_STRIDE,
                                "mesh positions");
                attributes =
                        regrow(
                                attributes,
                                old * MeshData.ATTRIBUTE_STRIDE,
                                cap * MeshData.ATTRIBUTE_STRIDE,
                                "mesh attributes");
                if (skins != 0) {
                    skins =
                            regrow(
                                    skins,
                                    old * MeshData.SKIN_STRIDE,
                                    cap * MeshData.SKIN_STRIDE,
                                    "mesh skins");
                }
            } else {
                indices = regrow(indices, old * 4, cap * 4, "mesh indices");
            }
            space.grow(cap);
            bindBuffers();
            off = space.allocate(n, 1);
        }
        return off;
    }

    private int regrow(int buffer, long oldBytes, long newBytes, String label) {
        GpuBuffers b = device.buffers();
        int bigger = b.create(newBytes, GpuBuffers.DYNAMIC, label);
        glCopyNamedBufferSubData(b.glName(buffer), b.glName(bigger), 0, 0, oldBytes);
        b.destroy(buffer); // GL defers the delete until pending draws are done
        return bigger;
    }
```

- **The new size** is double the old, or the old plus the request if that is bigger: doubling keeps the
  total copying proportional to the final size; the second term guarantees one round is enough even
  for a mesh bigger than everything so far. The loop is there in case fragmentation still leaves no
  hole big enough.
- **All vertex streams grow together**, because they share the vertex space: slot $v$ must exist in
  every stream.
- **`regrow`** creates a bigger buffer, copies the old contents **on the GPU** (a command after every
  upload already issued into the old buffer, so none is lost), and destroys the old one. Frames in
  flight may still draw from the old buffer; GL defines deletion as "the name goes away now, the memory
  when no pending command uses it" (L01 §7.4).
- **`bindBuffers`** re-points the vertex array. Draws already issued keep the binding they were issued
  with; GL state changes affect only later commands.

### 12.7 The tables, the queries, removal

`allocateTable` grows the three CPU tables the same way — `Arrays.copyOf` of every array of the table
(`table` 0: the submesh arrays; 1: the draw arrays; 2: `lodError`) — and never touches the GPU.

The queries are what the draw builder and the static scene call per item, so they are one or two array
reads each:

```java
    public int baseVertex(int mesh, int submesh) {
        return submeshBaseVertex[submeshBase[Handles.index(mesh)] + submesh];
    }

    // ... materialSlot, setSlotMaterial and submeshMaterialIndex (described below)

    public int firstIndex(int mesh, int lod, int submesh) {
        int m = Handles.index(mesh);
        return drawFirstIndex[drawBase[m] + lod * submeshCount[m] + submesh];
    }

    public int indexCount(int mesh, int lod, int submesh) {
        int m = Handles.index(mesh);
        return drawIndexCount[drawBase[m] + lod * submeshCount[m] + submesh];
    }

    public float lodError(int mesh, int lod) {
        return lodError[lodBase[Handles.index(mesh)] + lod];
    }
```

(Javadoc trimmed, and three methods between the first two skipped.) They take the handle's slot index
without checking the generation: the callers
check `isValid(mesh)` once per instance (§16), and a check per call would sit in the innermost loops.
`submeshCount`, `lodCount`, `isSkinned`, `sphere`, `materialSlot` and `submeshMaterialIndex` are the
same shape. `setSlotMaterial(mesh, slot, materialIndex)` writes `submeshMaterialIndex` for every
submesh in material slot `slot`; it is how a mesh's own materials take effect (§12.9).

```java
    public void remove(int mesh) {
        int m = check(mesh);
        vertexSpace.free(vertexOffset[m], Math.max(1, vertexCount[m]));
        indexSpace.free(indexOffset[m], Math.max(1, indexCount[m]));
        submeshSpace.free(submeshBase[m], Math.max(1, submeshCount[m]));
        drawSpace.free(drawBase[m], Math.max(1, submeshCount[m] * lodCount[m]));
        lodSpace.free(lodBase[m], lodCount[m]);
        handles.free(mesh);
    }
```

Removal frees the five ranges with the same sizes `add` reserved (the `Math.max(1, …)` must match)
and frees the handle, which bumps its generation (L03 §7). The GPU may still be drawing the mesh this
frame; the ranges are only reused by a later `add`, whose upload is a copy command ordered after those
draws. `close` deletes the vertex array and the buffers and closes the arena.

### 12.8 The registry's test, traced

`MeshRegistryTest` (§32.3) creates a registry with room for 6 vertices and 8 indices and adds two
quads. Each quad has 4 vertices and 9 indices: 6 for LOD 0 (two triangles) and 3 for LOD 1 (one).

```
add(left):   vertices: allocate 4 of [0, 6)                  -> slots 0-3, free [4, 6)
             indices:  allocate 9 of [0, 8)  fails
                       grow to max(2*8, 8+9) = 17            -> [0, 17), allocate -> 0, free [9, 17)
add(right):  vertices: allocate 4 of [4, 6)  fails
                       grow to max(2*6, 6+4) = 12            -> [4, 12), allocate -> 4, free [8, 12)
             indices:  allocate 9 of [9, 17) fails
                       grow to max(2*17, 17+9) = 34          -> [9, 34), allocate -> 9, free [18, 34)
```

So the right quad has base vertex 4 (the test asserts `baseVertex(right) - baseVertex(left) == 4`),
LOD 0 first index 9 and LOD 1 first index $9 + 6 = 15$ (it asserts `firstIndex(right, 1, 0) ==
firstIndex(right, 0, 0) + 6`). Then the test removes the left quad, which frees slots 0–3 — a hole
before the free range $[8, 12)$ — and adds a third quad: first fit takes the hole, base vertex 0, which
the test asserts as "the freed range is reused".

### 12.9 Why mega buffers, and where meshes come from

One vertex array and three fixed buffers for every mesh means no draw in a pass needs a different
binding: a draw becomes the five integers of an indirect command, which is what makes multi-draw
possible (DESIGN §9.2). Positions in a stream of their own let depth-only passes — the prepass, the
shadow maps — fetch 12 bytes per vertex instead of 28 (D27).

[`MeshLoader`](../../modules/render/src/main/java/dev/jeddy/render/mesh/MeshLoader.java) connects the
registry to the asset system: decoding and validation run on a worker, `add` on the main thread within
the asset upload budget. [`MeshAssets`](../../modules/render/src/main/java/dev/jeddy/render/mesh/MeshAssets.java)
does the same for the app and also remembers meshes whose materials are not loaded yet; its `update`,
once per frame on the main thread, calls `setSlotMaterial` for each slot whose material is ready, which
is what instances with `RenderPacket.MESH_MATERIALS` draw with.

## 13. Step 4: culling on the CPU

[`Culling`](../../modules/render/src/main/java/dev/jeddy/render/draw/Culling.java) applies the sphere
test of §2.4 to every instance of a packet and writes the indices of the visible ones. Chapter
[04](04-cameras-and-projection.md), on frustum culling, derives the planes and the batch kernel in
`CullOps`; here is the renderer's class.

```java
public final class Culling {
    private static final int PARALLEL_FROM = 4096;
    private static final int GRAIN = 1024;

    private final JobSystem jobs;
    private byte[] visibleFlags = new byte[1024];
    private RenderPacket packet;
    private float[] planes;
    private int requiredFlags;
    private int excludedFlags;
    private float sphereX;
    private float sphereY;
    private float sphereZ;
    private float sphereRadius = -1;
    private final JobKernel kernel = this::cullRange;
```

- **Per-call inputs live in fields** (`packet`, `planes`, the flags, the sphere) so the kernel —
  `cullRange`, run on workers — can read them. A lambda that captured them would be a new object every
  call (DESIGN §4.2 bans capturing lambdas in hot paths); `this::cullRange` is created once, when the
  object is, and stored.
- **One byte per instance** of visibility, so workers write disjoint bytes and never need to
  coordinate.

The general form takes required and excluded flags and an optional sphere; the shorter overloads fill
in zeros and −1:

```java
    public int cull(
            RenderPacket p,
            float[] frustumPlanes,
            int requiredFlags,
            int excludedFlags,
            float x,
            float y,
            float z,
            float radius,
            int[] out) {
        this.excludedFlags = excludedFlags;
        int n = p.count();
        if (visibleFlags.length < n) {
            visibleFlags = new byte[Math.max(n, visibleFlags.length * 2)];
        }
        packet = p;
        planes = frustumPlanes;
        this.requiredFlags = requiredFlags;
        sphereX = x;
        sphereY = y;
        sphereZ = z;
        sphereRadius = radius;
        if (jobs != null && n >= PARALLEL_FROM) {
            jobs.parallelFor(kernel, n, GRAIN);
        } else {
            cullRange(0, n, 0);
        }
        packet = null;
        planes = null;
        byte[] f = visibleFlags;
        int visible = 0;
        for (int i = 0; i < n; i++) {
            if (f[i] != 0) {
                out[visible++] = i;
            }
        }
        return visible;
    }
```

- **In parallel only from 4096 instances**, in chunks of 1024: below that, waking workers costs more
  than it saves (L03 §15 derives the rule).
- **`packet = null; planes = null`** after the work: the culler does not keep the packet alive, and a
  bug that used the fields outside a call would fail at once.
- **Compaction is sequential**, one pass over the bytes. Doing it on the main thread keeps the output in
  packet order, so a frame's draws are the same whatever the thread timing — deterministic output makes
  bugs reproducible.

The kernel:

```java
    private void cullRange(int start, int end, int thread) {
        float[] pl = planes;
        float[] b = packet.bounds;
        int[] flags = packet.flags;
        int required = requiredFlags;
        int excluded = excludedFlags;
        float r = sphereRadius;
        byte[] f = visibleFlags;
        for (int i = start; i < end; i++) {
            int o = i * 4;
            boolean v =
                    (flags[i] & required) == required
                            && (flags[i] & excluded) == 0
                            && CullOps.sphereVisible(pl, b[o], b[o + 1], b[o + 2], b[o + 3]);
            if (v && r >= 0) {
                float dx = b[o] - sphereX;
                float dy = b[o + 1] - sphereY;
                float dz = b[o + 2] - sphereZ;
                float reach = r + b[o + 3];
                v = dx * dx + dy * dy + dz * dz <= reach * reach;
            }
            f[i] = v ? (byte) 1 : 0;
        }
    }
```

- **Fields copied into locals** before the loop, so the JIT keeps them in registers instead of
  reloading them from the object each iteration.
- **Flags first**: `(flags & required) == required` means "has every required bit" (shadow views
  require `FLAG_CASTS_SHADOW`); `(flags & excluded) == 0` means "has none of the excluded bits" (with
  GPU culling, the main view excludes `FLAG_STATIC`, §27.5). Both are cheaper than the plane test, and
  `&&` skips it when they fail.
- **The light sphere.** A point or spot light's shadow view only needs objects within its range. Two
  spheres overlap when the distance between their centers is at most the sum of their radii; comparing
  squared distances avoids a square root: $d_x^2 + d_y^2 + d_z^2 \le (r + R)^2$.

## 14. Step 5: the material table and program slots

### 14.1 Rows in a storage buffer

A batch binds one program and one set of textures, yet its instances may use hundreds of different
materials. So a material's *numbers* live in a storage buffer, one row per material, and the instance
record's `data.x` names the row (§4.1).
[`MaterialTable`](../../modules/render/src/main/java/dev/jeddy/render/draw/MaterialTable.java) keeps
them:

```java
public final class MaterialTable implements AutoCloseable {
    /** Floats per material row. */
    public static final int FLOATS = 32;

    /**
     * Variant bit of skinned draws (engine feature bit 15, {@code SKINNED} in the shaders): every
     * program definition that draws skinned meshes honors it.
     */
    public static final long SKINNED = 1L << 15;

    private final GpuDevice device;
    private final ProgramCache programs;
    private final Arena arena = Arena.ofConfined();
    private MemorySegment cpu;
    private int buffer;
    private int capacity;
    private int count;
    private int[] slotOfMaterial;
    private int dirtyFrom = Integer.MAX_VALUE;
    private int dirtyTo = -1;
    private int slotVersion;

    // Program slots: (definition, mask, texture set) triples.
    private int slotCount;
    private int[] slotDefinition = new int[16];
    private long[] slotMask = new long[16];
    private int[] slotTextureSet = new int[16];
    private int[] skinnedVariant = filled(16); // -1 until prepared
```

A row is `FLOATS = 32` floats, 128 bytes (D41). The rows live in two places: a **CPU copy** in native
memory (`cpu`), which is the truth, and the GPU buffer, which follows it. `set(material, params)`
writes the CPU copy and widens a **dirty range** `[dirtyFrom, dirtyTo]`; once per frame,
`flush(ring)` sends that range to the GPU. L01 §14.3 builds `set`, `flush` and `grow` line by line;
in short:

```java
    public void flush(FrameRing ring) {
        if (dirtyTo < 0) {
            return;
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

The upload goes through the frame ring and a GPU-side copy (D103): the rows are staged in the ring and
`glCopyNamedBufferSubData` copies them, a command ordered after every earlier frame that read the
table. It used to be a CPU write with `glNamedBufferSubData`, and the first Intel Windows driver the
engine ran on silently dropped those writes once the GPU had read the buffer, so every material loaded
after the first frame read as zeros: black, mirror-smooth characters on a correct floor (L01 §17).

Two sizes, and which one is bound:

```java
    /** Returns the bytes in use. */
    public long byteSize() {
        return (long) Math.max(1, count) * FLOATS * 4;
    }

    /**
     * Returns the buffer's size: what to bind, so the bound range does not change as rows are added
     * (a driver that resolves a range at bind time would keep reading the old extent).
     */
    public long bufferBytes() {
        return cpu.byteSize();
    }
```

The draw code binds `bufferBytes()`, the **whole buffer**, so the bound range never changes as
materials arrive (D103; L01 exercise 5).

### 14.2 The row's layout

The row follows the GLSL struct of
[`engine/material.glsl`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/engine/material.glsl),
eight 16-byte members, so a `std430` array of them has a stride of 128 (§4.1):

| Floats | GLSL member | Content |
|---|---|---|
| 0–3 | `vec4 baseColor` | linear RGBA factor |
| 4–7 | `vec4 emissive` | RGB factor, strength |
| 8–11 | `vec4 pbr` | metallic, roughness, normal scale, occlusion strength |
| 12–15 | `vec4 uvScaleOffset` | UV transform: scale xy, offset xy |
| 16–19 | `vec4 misc` | x UV rotation, y alpha cutoff |
| 20–23 | `uvec4 layers` | texture array layer of base color, normal, ORM, emissive |
| 24–27 | `uvec4 layers2` | x occlusion layer, y one bit per texture slot that uses UV1, z material flags |
| 28–31 | `vec4 reserved` | |

The integer members are written as `Float.intBitsToFloat(n)` into the `float[]` row, so the `uvec4`
reads the integer back (§22.3).

### 14.3 Program slots

Each material also belongs to a **program slot**: a (program definition, feature mask, texture set)
triple. The **definition** is a shader program from the `ProgramCache` (chapter
[05](05-opengl-from-java.md), on shaders and variants); the **feature mask** picks which variant of it
— which `#define`s are on (§22.1); the **texture set** says which texture-array bucket each texture
slot binds (§7.2, §22.4). Materials that share a slot share a program and texture bindings and differ
only in their row, so the **slot is what sort keys and batches use** (§5.2).

```java
    private int slot(int definition, long mask, int textureSet) {
        for (int s = 0; s < slotCount; s++) {
            if (slotDefinition[s] == definition
                    && slotMask[s] == mask
                    && slotTextureSet[s] == textureSet) {
                return s;
            }
        }
        if (slotCount == 4096) {
            throw new IllegalStateException("more than 4096 program variants in use");
        }
        if (slotCount == slotDefinition.length) {
            slotDefinition = Arrays.copyOf(slotDefinition, slotCount * 2);
            slotMask = Arrays.copyOf(slotMask, slotCount * 2);
            slotTextureSet = Arrays.copyOf(slotTextureSet, slotCount * 2);
            skinnedVariant = Arrays.copyOf(skinnedVariant, slotCount * 2);
            Arrays.fill(skinnedVariant, slotCount, skinnedVariant.length, -1);
        }
        slotDefinition[slotCount] = definition;
        slotMask[slotCount] = mask;
        slotTextureSet[slotCount] = textureSet;
        return slotCount++;
    }
```

Find the triple, or append it. The search is linear because it runs only when a material is added or
changes; draws use the slot number. **4096** is $2^{12}$, the width of the slot field in the sort key:
a 4097th slot could not be told apart from slot 0 in a key, so it is refused. New skinned-variant
entries start at −1 ("not prepared").

```java
    public int add(int definition, long mask, int textureSet, float[] params) {
        if (count == capacity) {
            grow();
        }
        int m = count++;
        slotOfMaterial[m] = slot(definition, mask, textureSet);
        set(m, params);
        return m;
    }

    /** Changes the program variant or texture set of a material (textures arrived, settings). */
    public void setSlot(int material, int definition, long mask, int textureSet) {
        int s = slot(definition, mask, textureSet);
        if (slotOfMaterial[material] != s) {
            slotOfMaterial[material] = s;
            slotVersion++;
        }
    }
```

`setSlot` moves a material to another slot — when a texture arrives, its variant changes (§22.3). Any
real move increments **`slotVersion`**, a counter that code keeping draws on the GPU watches: the
static scene groups its instances by slot and rebuilds when the counter changes (§26.3).
`programSlot(material)` returns `slotOfMaterial[material]`; `slotMask`, `slotDefinition` and
`slotTextureSet` read a slot's triple; and

```java
    public int glProgram(int slot) {
        return programs.get(slotDefinition[slot], slotMask[slot]);
    }
```

asks the `ProgramCache` for the slot's GL program, which returns **0 while the variant is still
compiling** (compiles run in the background where the driver supports it). Draw code skips a batch whose
program is 0 for this frame instead of waiting.

### 14.4 Skinned variants

A skinned mesh needs its material's program with the `SKINNED` define (bit 15), which reads the skin
stream and the palette (§30). The variant is another slot:

```java
    public int prepareSkinned(int slot) {
        int v = skinnedVariant[slot];
        if (v >= 0) {
            return v;
        }
        long mask = slotMask[slot];
        v =
                (mask & SKINNED) != 0
                        ? slot
                        : slot(slotDefinition[slot], mask | SKINNED, slotTextureSet[slot]);
        skinnedVariant[slot] = v;
        skinnedVariant[v] = v;
        return v;
    }

    public int skinnedSlot(int slot) {
        int v = skinnedVariant[slot];
        return v < 0 ? slot : v;
    }
```

`prepareSkinned` may create a slot, which grows arrays and is not thread-safe, so it runs on the render
thread before the draw builder's workers start (§16). The workers then call `skinnedSlot`, a single
array read. `skinnedVariant[v] = v` records that the variant is its own skinned variant, so asking again
for it returns at once.

## 15. Step 6: the draw builder's fields

[`DrawBuilder`](../../modules/render/src/main/java/dev/jeddy/render/draw/DrawBuilder.java) turns the
indices of visible instances into sorted records, indirect commands and batches (§1.3). Its Javadoc
lists the steps; §16 to §20 build each one. First the constants and fields.

```java
public final class DrawBuilder {
    /** Bytes per instance record (std430: three {@code vec4} rows, one {@code uvec4}). */
    public static final int INSTANCE_BYTES = 64;

    /** Bytes per indirect command. */
    public static final int COMMAND_BYTES = 20;

    /** Storage binding of the instance records. */
    public static final int INSTANCE_BINDING = 0;

    /** Storage binding of the material table. */
    public static final int MATERIAL_BINDING = 1;

    private static final ValueLayout.OfFloat F32 =
            ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.nativeOrder());
    private static final ValueLayout.OfInt I32 =
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.nativeOrder());
    private static final int PASS_OPAQUE = 0;
    private static final int PASS_TRANSLUCENT = 1;
    private static final int GRAIN = 512;
    private static final int PARALLEL_FROM = 2048;
```

- **The bindings** 0 and 1 are the `binding = 0` of `JeddyInstances` and `binding = 1` of
  `JeddyMaterials` in the shaders (§21), shared by every pass (DESIGN §9.3 lists all bindings).
- **The value layouts** describe how a `float` and an `int` are stored when the builder writes them into
  the mapped ring. The `_UNALIGNED` variants skip `MemorySegment.set`'s alignment check (every offset
  written here is a multiple of 4 anyway, so it would never fire); the byte order is the machine's own —
  little-endian on x64 (L00 §8.1) — which is what the GPU on the same machine reads. `nativeOrder()` is
  already the default for these layouts; writing it out documents the intent. They are `static final`,
  so the JIT treats them as constants (DESIGN §4.3).
- **`GRAIN` and `PARALLEL_FROM`**: with at least 2048 visible instances the builder splits its loops
  between workers in chunks of 512; below that it runs on the calling thread (L03 §15).

```java
    private final MeshRegistry meshes;
    private final MaterialTable materials;
    private final JobSystem jobs;
    private final ParallelSort sorter;

    // Items (one per visible instance and submesh).
    private int itemCount;
    private long[] keys = new long[0];
    private int[] order = new int[0]; // sorted item → item index
    private int[] itemInstance = new int[0];
    private int[] itemMaterial = new int[0];
    private int[] itemFirst = new int[0]; // first item of each visible instance (prefix sum)
    private byte[] itemLod = new byte[0];

    // Batches.
    private int batchCount;
    private int[] batchSlot = new int[16];
    private int[] batchPass = new int[16];
    private long[] batchOffset = new long[16];
    private int[] batchCommands = new int[16];
    private int commandCount;
    private int[] runStart = new int[0];
    private long[] scratchKeys = new long[0]; // sequential sort only
    private int[] scratchOrder = new int[0];
    private final int[] histogram = new int[2048];
```

An **item** is one (visible instance, submesh) pair: one record, one sort key. The item arrays are
SoA again, indexed by item number: its key, the visible-instance number it came from, its material, its
LOD (a `byte`: LODs are 0–15). `order` receives the radix sort's payload (§5.4). The batch arrays
describe each multi-draw: its pass, program slot, ring offset of its first command and number of
commands. `scratchKeys`, `scratchOrder` and `histogram` are the sequential sort's scratch (§18.1); the
parallel sort keeps its own.

```java
    // Per-frame inputs for the kernels.
    private RenderPacket packet;
    private int[] visible;
    private int visibleCount;
    private MemorySegment memory;
    private long instanceBase;
    private float pixelsPerUnit = 1000;
    private boolean orthographic;
    private float thresholdPixels = 1;
    private long instanceOffset;
    private long instanceBytes;

    private final JobKernel itemKernel = this::buildItems;
    private final JobKernel recordKernel = this::writeRecords;
```

The per-frame inputs are fields for the same reason as in `Culling` (§13): the two kernels run on
workers and read them, and the method references are created once and kept in `final` fields, so
passing them to `parallelFor` allocates nothing.

```java
    /** Creates a builder; {@code jobs} may be {@code null} to build on the calling thread. */
    public DrawBuilder(MeshRegistry meshes, MaterialTable materials, JobSystem jobs) {
        this.meshes = meshes;
        this.materials = materials;
        this.jobs = jobs;
        this.sorter = jobs == null ? null : new ParallelSort(jobs);
    }

    public void setLod(float pixels, boolean ortho, float threshold) {
        pixelsPerUnit = pixels;
        orthographic = ortho;
        thresholdPixels = threshold;
    }
```

`setLod` takes the $P$ of §6 (`pixels`), which formula to use, and the threshold $\tau$ in pixels. The
renderer calls it every frame before `build` (§27.4); several builders exist — one for the main view,
others for shadow and outline views (§27.4) — each with its own settings. Thread ownership: the render
(main) thread; `build` borrows job workers.

## 16. Step 7: build, counting the items

`build(packet, visibleIndices, visibleCount, ring)` starts on the calling (main) thread:

```java
    public boolean build(RenderPacket p, int[] visibleIndices, int visibleCount, FrameRing ring) {
        packet = p;
        visible = visibleIndices;
        this.visibleCount = visibleCount;
        batchCount = 0;
        commandCount = 0;
        // Item offsets: each visible instance has submeshCount items.
        if (itemFirst.length < visibleCount + 1) {
            itemFirst = new int[Math.max(visibleCount + 1, itemFirst.length * 2)];
        }
        int n = 0;
        for (int v = 0; v < visibleCount; v++) {
            itemFirst[v] = n;
            int i = visibleIndices[v];
            int mesh = p.mesh[i];
            if (meshes.isValid(mesh)) {
                int submeshes = meshes.submeshCount(mesh);
                if ((p.flags[i] >>> RenderPacket.PALETTE_SHIFT) != 0 && meshes.isSkinned(mesh)) {
                    prepareSkinned(p.material[i], mesh, submeshes);
                }
                n += submeshes;
            }
        }
        itemFirst[visibleCount] = n;
        itemCount = n;
        ensureItems(n);
        if (n == 0) {
            return true;
        }
```

**A prefix sum.** Each visible instance contributes one item per submesh, and `itemFirst[v]` is the
number of items of all instances before `v` — a running total, or **prefix sum**. Instance $v$'s items
are then `itemFirst[v] .. itemFirst[v + 1] - 1`, which is why the array has one extra entry at the end.
Three visible instances with 2, 0 (a mesh that is not loaded) and 3 submeshes give `itemFirst = [0, 2,
2, 5]`: the first owns items 0–1, the second none, the third 2–4. With those numbers fixed in advance,
workers can later write the items of different instances without coordinating: each knows exactly
where its items go.

**Skinned slots, here.** An instance with palette bits whose mesh has a skin stream will draw with its
material slot's `SKINNED` variant. `prepareSkinned` may create that slot, which is not thread-safe
(§14.4), so it runs here on the main thread, before any worker:

```java
    private void prepareSkinned(int instanceMaterial, int mesh, int submeshes) {
        for (int s = 0; s < submeshes; s++) {
            int material =
                    instanceMaterial >= 0 ? instanceMaterial : meshes.submeshMaterialIndex(mesh, s);
            materials.prepareSkinned(materials.programSlot(material));
        }
    }
```

`instanceMaterial >= 0 ? … : …` is the `MESH_MATERIALS` rule: −1 means each submesh uses its mesh's
own material for its slot (§12.7).

`ensureItems(n)` grows the item arrays when this frame has more items than any before — the only
allocation, and only while the scene grows:

```java
    private void ensureItems(int n) {
        if (keys.length >= n) {
            return;
        }
        int cap = Math.max(n, keys.length * 2);
        keys = new long[cap];
        order = new int[cap];
        if (sorter == null) {
            scratchKeys = new long[cap];
            scratchOrder = new int[cap];
        }
        itemInstance = new int[cap];
        itemMaterial = new int[cap];
        itemLod = new byte[cap];
        runStart = new int[cap + 1];
    }
```

`new` instead of `Arrays.copyOf`, because every item array is rewritten from scratch each frame:
nothing in them needs to survive.

## 17. Step 8: the item kernel, from instance to sort key

### 17.1 Running it

```java
        run(itemKernel, visibleCount);
```

```java
    private void run(JobKernel kernel, int count) {
        if (jobs != null && count >= PARALLEL_FROM) {
            jobs.parallelFor(kernel, count, GRAIN);
        } else {
            kernel.run(0, count, 0);
        }
    }
```

`parallelFor(kernel, count, grain)` splits $[0, \text{count})$ into chunks of `grain`, lets the
workers and the calling thread claim chunks with an atomic counter (DESIGN §4.5), and returns only when
every chunk is done. A `JobKernel` is any method `(start, end, thread)`.

### 17.2 Distance and scale

The kernel walks visible instances $[\text{start}, \text{end})$:

```java
    private void buildItems(int start, int end, int thread) {
        RenderPacket p = packet;
        float[] eye = p.eye;
        float[] world = p.currWorld;
        for (int v = start; v < end; v++) {
            int i = visible[v];
            int mesh = p.mesh[i];
            int first = itemFirst[v];
            int submeshes = itemFirst[v + 1] - first;
            if (submeshes == 0) {
                continue;
            }
            int w = i * 12;
            float dx = world[w + 3] - eye[0];
            float dy = world[w + 7] - eye[1];
            float dz = world[w + 11] - eye[2];
            float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            float scale =
                    (float)
                            Math.sqrt(
                                    world[w] * world[w]
                                            + world[w + 4] * world[w + 4]
                                            + world[w + 8] * world[w + 8]);
            int lod = selectLod(mesh, scale, distance);
```

- `submeshes` comes from the prefix sum; 0 means the mesh was not valid, and the instance is skipped.
- **Distance**: the translation is at offsets 3, 7 and 11 of the 12 floats (§2.2); `distance` is the
  length (§2.1) of the vector from the eye to it.
- **Scale**: the length of the first column, at offsets 0, 4 and 8 (§2.3).
- Both use the **current** tick's transform, not the interpolated one: keys and LODs only need to be
  roughly right. The kernel reads only 6 of the 12 floats (the translation and the first column);
  interpolating them would mean reading the previous transform too and 6 more multiply-adds per
  instance.

### 17.3 The key

```java
            boolean translucentInstance = (p.flags[i] & RenderPacket.FLAG_TRANSLUCENT) != 0;
            int depth = (int) Math.min(65535f, distance * 64f);
            long meshBits = (long) (Handles.index(mesh) & 0xFFFFF) << 28 | (long) lod << 24;
            int instanceMaterial = p.material[i];
            boolean skinned =
                    (p.flags[i] >>> RenderPacket.PALETTE_SHIFT) != 0 && meshes.isSkinned(mesh);
            for (int s = 0; s < submeshes; s++) {
                int item = first + s;
                int material =
                        instanceMaterial >= 0
                                ? instanceMaterial
                                : meshes.submeshMaterialIndex(mesh, s);
                int slot = materials.programSlot(material);
                if (skinned) {
                    slot = materials.skinnedSlot(slot);
                }
                // Blended materials draw in the translucent pass whatever the instance says.
                boolean translucent =
                        translucentInstance
                                || (materials.slotMask(slot) & MaterialData.FEATURE_ALPHA_BLEND)
                                        != 0;
                itemInstance[item] = v;
                itemMaterial[item] = material;
                itemLod[item] = (byte) lod;
```

- **Per instance, once**: the opaque depth field (§5.2: $\min(65535, 64 d)$, cast to `int`, which drops
  the fraction), and `meshBits`, the mesh and LOD fields already shifted into place. `& 0xFFFFF` keeps
  the low 20 bits of the handle's slot index (handles have 22, L03 §6; the key has room for 20).
  `(long)` before the shift: shifting an `int` left by 28 would push bits out of 32 (L00 §6.3).
- **Per submesh**: the material (the instance's, or the mesh slot's), its program slot, the skinned
  variant if needed, and whether the item is translucent: the instance's flag **or** a material whose
  feature mask has `FEATURE_ALPHA_BLEND` (§22.1). A glass material is translucent wherever it is used.
- The item's instance, material and LOD go into the item arrays, at the item's own index: no two
  workers ever write the same index.

```java
                long key;
                if (translucent) {
                    // pass | depth, far first (32) | program slot (12) | submesh (8) | 0
                    long backToFront =
                            0xFFFFFFFFL - Math.min(0xFFFFFFFFL, (long) (distance * 4096f));
                    key =
                            (long) PASS_TRANSLUCENT << 60
                                    | backToFront << 28
                                    | (long) slot << 16
                                    | (long) s << 8;
                } else {
                    // pass | program slot (12) | mesh (20) | LOD (4) | submesh (8) | depth, near
                    // first (16)
                    key =
                            (long) PASS_OPAQUE << 60
                                    | (long) slot << 48
                                    | meshBits
                                    | (long) s << 16
                                    | depth;
                }
                keys[item] = key;
            }
        }
    }
```

This is §5.2 in code. `0xFFFFFFFFL` is a `long` literal with 32 ones (L00 §3.3); `Math.min` clamps
before the subtraction so the depth field can never go negative. Every part is a `long` before it is
shifted. `depth` is a non-negative `int`, widened without sign trouble.

**A key from the test of §32.4.** The grid instance in column 150, row 50 sits at $(50.5, 1, 0)$; the
eye is at $(0, 0, 5)$, so $d = \sqrt{50.5^2 + 1^2 + 5^2} \approx 50.757$ and the depth field is
$\lfloor 64 \times 50.757 \rfloor = 3248 = \text{0xCB0}$. It is green (program slot 1), mesh slot index
0, LOD 1, and its first submesh gives `0x0001000001000CB0`; its second, `0x0001000001010CB0`.

### 17.4 `selectLod`

```java
    private int selectLod(int mesh, float scale, float distance) {
        int lods = meshes.lodCount(mesh);
        float k =
                orthographic
                        ? pixelsPerUnit * scale
                        : pixelsPerUnit * scale / Math.max(distance, 1e-3f);
        int lod = 0;
        for (int l = 1; l < lods; l++) {
            if (meshes.lodError(mesh, l) * k <= thresholdPixels) {
                lod = l;
            } else {
                break;
            }
        }
        return lod;
    }
```

The formula of §6: $k = P s / d$ (or $P s$ for orthographic views), then the coarsest LOD whose error
times $k$ is at most $\tau$. `Math.max(distance, 1e-3f)` keeps a camera standing exactly on an
instance's origin from dividing by zero. The loop **stops** at the first LOD that is too coarse:
errors grow with the LOD number, so no later one could pass. LOD 0 (error 0) is always acceptable.

## 18. Step 9: the radix sort

Back in `build`, after the item kernel:

```java
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        if (sorter != null) {
            sorter.sort(keys, order, n);
        } else {
            LongSort.radixSort(keys, order, n, scratchKeys, scratchOrder, histogram);
        }
```

The payload starts as the identity (item $i$ at position $i$); after the sort, `order[k]` is the item
at sorted position $k$, and `keys` is sorted in place.

### 18.1 The sequential sort

[`LongSort.radixSort`](../../modules/core/src/main/java/dev/jeddy/core/collections/LongSort.java) is
§5.3–5.4 in code. Its Javadoc states it is stable, $O(n)$, compares keys as unsigned numbers, and
allocates nothing — the caller provides the scratch.

```java
    public static void radixSort(
            long[] keys,
            int[] values,
            int count,
            long[] scratchKeys,
            int[] scratchValues,
            int[] histogram) {
        if (count < 2) {
            return;
        }
        if (scratchKeys.length < count || scratchValues.length < count || histogram.length < 2048) {
            throw new IllegalArgumentException("scratch arrays too small");
        }
        java.util.Arrays.fill(histogram, 0, 2048, 0);
        for (int i = 0; i < count; i++) {
            long k = keys[i];
            for (int pass = 0; pass < 8; pass++) {
                histogram[(pass << 8) | (int) ((k >>> (pass << 3)) & 0xFF)]++;
            }
        }
```

**All eight histograms in one sweep.** The histogram array has $8 \times 256 = 2048$ counters: pass
$p$'s counters are entries $256p \dots 256p + 255$. For each key and each pass, `pass << 3` is $8p$, the
bit position of byte $p$; `(k >>> 8p) & 0xFF` extracts that byte (L00 §6.2–6.3); `(pass << 8) | byte`
is $256p + \text{byte}$ (the OR acts as an addition because the two parts share no bits). Reading every
key once for all eight passes saves seven sweeps over memory.

```java
        long[] srcK = keys;
        int[] srcV = values;
        long[] dstK = scratchKeys;
        int[] dstV = scratchValues;
        for (int pass = 0; pass < 8; pass++) {
            int base = pass << 8;
            if (histogram[base | (int) ((keys[0] >>> (pass << 3)) & 0xFF)] == count) {
                continue; // every key has the same byte here
            }
            int sum = 0;
            for (int b = 0; b < 256; b++) {
                int c = histogram[base | b];
                histogram[base | b] = sum;
                sum += c;
            }
            int shift = pass << 3;
            for (int i = 0; i < count; i++) {
                long k = srcK[i];
                int slot = histogram[base | (int) ((k >>> shift) & 0xFF)]++;
                dstK[slot] = k;
                dstV[slot] = srcV[i];
            }
            long[] tk = srcK;
            srcK = dstK;
            dstK = tk;
            int[] tv = srcV;
            srcV = dstV;
            dstV = tv;
        }
        if (srcK != keys) {
            System.arraycopy(srcK, 0, keys, 0, count);
            System.arraycopy(srcV, 0, values, 0, count);
        }
    }
```

- **The skip test** looks at the bucket of the first key's byte: if it holds all `count` keys, every
  key has that byte, and the pass is skipped (§5.4).
- **Offsets in place.** The loop over 256 buckets replaces each count by the sum of the counts before
  it — the bucket's first output position.
- **The scatter** reads each key's bucket position and increments it (`++` after the read), so the
  next key with the same byte lands right after: stable.
- **Ping-pong.** Each pass reads from one pair of arrays and writes to the other, then the roles swap,
  so no pass copies back. If an odd number of passes ran, the result sits in the scratch arrays and
  one final copy brings it home.

### 18.2 The parallel sort

[`ParallelSort`](../../modules/core/src/main/java/dev/jeddy/core/job/ParallelSort.java) gives the
same result on the job system:

```java
public final class ParallelSort {
    private static final int SEQUENTIAL_BELOW = 16_384;
    private static final int CHUNKS = 32;

    private final JobSystem jobs;
    private long[] scratchKeys = new long[0];
    private int[] scratchValues = new int[0];
    private final int[] histogram = new int[2048];
    private final int[] chunkHistograms = new int[CHUNKS * 256];

    // Kernel state for the current pass.
    private long[] srcK;
    private int[] srcV;
    private long[] dstK;
    private int[] dstV;
    private int count;
    private int chunkSize;
    private int shift;

    private final JobKernel histogramKernel = this::histogramChunk;
    private final JobKernel scatterKernel = this::scatterChunk;
```

The keys are cut into 32 **chunks**; each chunk has its own 256 counters in `chunkHistograms` (chunk
$c$'s are entries $256c \dots 256c + 255$), so workers counting different chunks never write the same
counter.

```java
    public void sort(long[] keys, int[] values, int count) {
        if (count < 2) {
            return;
        }
        if (scratchKeys.length < count) {
            scratchKeys = new long[Math.max(count, scratchKeys.length * 2)];
            scratchValues = new int[scratchKeys.length];
        }
        if (count < SEQUENTIAL_BELOW || jobs.workerCount() == 0) {
            LongSort.radixSort(keys, values, count, scratchKeys, scratchValues, histogram);
            return;
        }
        this.count = count;
        this.chunkSize = (count + CHUNKS - 1) / CHUNKS;
        // Which passes vary? One sequential sweep over the keys (cheap next to the scatters).
        long first = keys[0];
        long varying = 0;
        for (int i = 1; i < count; i++) {
            varying |= keys[i] ^ first;
        }
```

- **Below 16,384 keys** (or with no workers) the sequential sort is faster than waking threads.
- **`chunkSize`** is $\lceil n / 32 \rceil$ (L00 §2.5); the last chunk may be shorter.
- **Which bytes vary**, found differently: `keys[i] ^ first` has a 1 wherever key $i$ differs from the
  first key (XOR, L00 §6.1); OR-ing them all gives a mask of every bit that differs anywhere. A byte of
  the mask that is zero is a pass to skip.

```java
        srcK = keys;
        srcV = values;
        dstK = scratchKeys;
        dstV = scratchValues;
        for (int pass = 0; pass < 8; pass++) {
            shift = pass << 3;
            if (((varying >>> shift) & 0xFF) == 0) {
                continue;
            }
            Arrays.fill(chunkHistograms, 0);
            jobs.parallelFor(histogramKernel, CHUNKS, 1);
            // Offsets: bucket-major, chunk-minor, so equal keys keep their order (stable).
            int sum = 0;
            for (int b = 0; b < 256; b++) {
                for (int c = 0; c < CHUNKS; c++) {
                    int n = chunkHistograms[c * 256 + b];
                    chunkHistograms[c * 256 + b] = sum;
                    sum += n;
                }
            }
            jobs.parallelFor(scatterKernel, CHUNKS, 1);
            long[] tk = srcK;
            srcK = dstK;
            dstK = tk;
            int[] tv = srcV;
            srcV = dstV;
            dstV = tv;
        }
        if (srcK != keys) {
            System.arraycopy(srcK, 0, keys, 0, count);
            System.arraycopy(srcV, 0, values, 0, count);
        }
        srcK = null;
        srcV = null;
        dstK = null;
        dstV = null;
    }
```

The ping-pong swap and the final copy are those of §18.1. The four array fields are set back to `null`
at the end so the sorter does not keep the caller's arrays reachable between calls. The heart is the
offset loop: for each byte value $b$ in order, then for each chunk $c$ in order, chunk $c$'s keys with
byte $b$ get the next positions. All keys with byte 0 come first (from chunk 0, then chunk 1, …), then
all with byte 1, and so on. Inside one byte value, chunk 0's keys precede chunk 1's, which preceded
them in the input too: **stable**, exactly like the sequential sort. The kernels are the sequential
loops applied to one chunk each:

```java
    private void scatterChunk(int start, int end, int thread) {
        long[] k = srcK;
        int[] v = srcV;
        long[] dk = dstK;
        int[] dv = dstV;
        int s = shift;
        for (int c = start; c < end; c++) {
            int from = c * chunkSize;
            int to = Math.min(count, from + chunkSize);
            int base = c * 256;
            for (int i = from; i < to; i++) {
                long key = k[i];
                int slot = chunkHistograms[base + (int) ((key >>> s) & 0xFF)]++;
                dk[slot] = key;
                dv[slot] = v[i];
            }
        }
    }
```

Every chunk writes its keys to positions no other chunk owns, so the scatter needs no synchronization;
`parallelFor` returning is the point where all writes are done. `ParallelSortTest` checks that the
result equals the sequential sort's, keys and payload, for sizes 0 to 100,003 and allocates nothing
(§32.2).

## 19. Step 10: build, from sorted items to commands

### 19.1 Runs

After sorting, equal draws are adjacent. A **run** is a maximal sequence of consecutive items that can
share one indirect command:

```java
        // Runs of identical draws (same key above the depth bits) become commands.
        int runs = 0;
        for (int i = 0; i < n; i++) {
            if (i == 0 || !sameDraw(keys[i - 1], keys[i])) {
                runStart[runs++] = i;
            }
        }
        runStart[runs] = n;
```

```java
    /** Opaque items merge when everything above the depth bits matches; translucent never do. */
    private static boolean sameDraw(long a, long b) {
        return (a >>> 60) == PASS_OPAQUE && (b >>> 60) == PASS_OPAQUE && (a >>> 16) == (b >>> 16);
    }
```

Two opaque items can share a draw when their keys agree above the 16 depth bits — same pass, slot,
mesh, LOD and submesh. `a >>> 16` drops the depth (L00 §6.3). `runStart[r]` is the sorted position
where run $r$ starts, with a sentinel `runStart[runs] = n` so that run $r$'s length is always
`runStart[r + 1] - runStart[r]`.

### 19.2 Why translucent items never merge

Merging two translucent items into one instanced draw would draw them in instance order, inside one
command — but the sort placed other items *between* them by depth, and those must be blended in
between. A merged draw could not keep that order, so `sameDraw` refuses whenever either key is
translucent. Each translucent item is its own command; consecutive commands with the same slot still
share a batch (§19.4).

### 19.3 Reserving ring space and writing the records

```java
        instanceBytes = (long) n * INSTANCE_BYTES;
        instanceOffset = ring.allocate(instanceBytes, 256);
        long commandOffset = ring.allocate((long) runs * COMMAND_BYTES, 16);
        if (instanceOffset < 0 || commandOffset < 0) {
            itemCount = 0;
            return false;
        }
        memory = ring.memory();
        instanceBase = instanceOffset;
        run(recordKernel, n);
```

Both blocks come from this frame's ring region in one call each, on the main thread (L01 §14.1 follows
these bytes in detail). The records' offset is **256-aligned** because it will be bound as a storage
buffer range, and GL requires such offsets to be multiples of `GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT`,
which the GL specification caps at 256 — so 256 works on every driver. The commands only need the
4-byte alignment of an indirect offset; 16 is generous. If the region is full, the builder records zero
items and returns `false`: nothing of this view is drawn this frame, which is better than a stall or an
exception mid-frame.

Then the workers write one record per item, **in sorted order**:

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

- **`k` is the sorted position**, so record $k$ is written at `instanceBase + 64 k`: the records come
  out in the order of the sorted keys, which is what lets a command's `baseInstance` address a
  contiguous run (D30). `order[k]` finds the item, `itemInstance[item]` its visible-instance number,
  and `visible[...]` the packet index.
- **Interpolation.** Each of the 12 floats is blended between the two ticks, $x + (x' - x)\,\alpha$
  (§10.2). For the rotation part that is not exactly a rotation in between, but the two ticks are
  1/30 s apart (D13), so the difference is invisible; the game thread does the careful work for large
  turns (chapter [03](03-rotations-and-transforms.md)).
- **Every value is computed in registers and stored once**, in increasing addresses, never read back:
  the mapped ring is write-combined memory (L01 §3.3).

**The threading rules** of DESIGN §5 are all satisfied here: the main thread waited on the region's
fence in `beginFrame` before any worker writes; the workers write disjoint ranges of records; they
never read the mapping; and `parallelFor` returns only after every chunk is done — its completion
counter is a locked atomic operation, a full memory fence, so the main thread sees all the records
before it issues the draws.

### 19.4 Commands and batches

One sequential pass turns runs into commands and groups commands into **batches** — consecutive
commands with the same pass and program slot:

```java
        // Commands and batches (sequential: one pass over the runs).
        long c = commandOffset;
        for (int r = 0; r < runs; r++) {
            int first = runStart[r];
            int item = order[first];
            long key = keys[first];
            int v = instanceOfItem(item);
            int instance = visible[v];
            int mesh = packet.mesh[instance];
            int submesh = item - itemFirst[v];
            int lod = itemLod[item];
            memory.set(I32, c, meshes.indexCount(mesh, lod, submesh));
            memory.set(I32, c + 4, runStart[r + 1] - first);
            memory.set(I32, c + 8, meshes.firstIndex(mesh, lod, submesh));
            memory.set(I32, c + 12, meshes.baseVertex(mesh, submesh));
            memory.set(I32, c + 16, first);
            int pass = (int) (key >>> 60);
            int slot = slotOf(key);
            if (batchCount == 0
                    || batchPass[batchCount - 1] != pass
                    || batchSlot[batchCount - 1] != slot) {
                ensureBatches(batchCount + 1);
                batchPass[batchCount] = pass;
                batchSlot[batchCount] = slot;
                batchOffset[batchCount] = c;
                batchCommands[batchCount] = 0;
                batchCount++;
            }
            batchCommands[batchCount - 1]++;
            c += COMMAND_BYTES;
        }
        commandCount = runs;
        memory = null;
        packet = null;
        visible = null;
        return true;
    }
```

- **The run's first item** stands for the whole run: every item of a run has the same mesh, LOD and
  submesh. `item - itemFirst[v]` recovers which submesh of its instance the item is (the prefix sum of
  §16 again; `instanceOfItem(item)` is just `itemInstance[item]`).
- **The five fields** of §3.4, written at offsets 0, 4, 8, 12, 16 of the command: the count and first
  index of that LOD of that submesh, the run length as `instanceCount`, the submesh's absolute base
  vertex, and `first` — the run's first sorted position — as `baseInstance`.
- **A new batch** starts whenever the pass or the slot changes; otherwise the command joins the current
  batch. `batchOffset` is the ring offset of the batch's first command, which becomes the `indirect`
  argument of its multi-draw (§20). `ensureBatches` doubles the batch arrays when needed.
- **`slotOf`** reads the slot from wherever the key's layout put it:

```java
    private static int slotOf(long key) {
        return (int)
                ((key >>> 60) == PASS_TRANSLUCENT ? (key >>> 16) & 0xFFF : (key >>> 48) & 0xFFF);
    }
```

- **Clearing the per-frame fields** at the end, as in `Culling`: the builder does not hold on to the
  packet or the mapping.

Because the slot is the highest field after the pass, every opaque slot forms exactly **one** batch.
Translucent batches split whenever two depth-adjacent items use different slots: that is the price of
correct blending (exercise 4).

**The test's numbers** (§32.4): 40,000 items, all opaque, all LOD 1; their keys differ in slot (2
values) and submesh (2 values) above the depth bits, so there are **4 runs** of 10,000 items: 4
commands with `instanceCount` 10,000. Two slots give **2 batches** of 2 commands each.

## 20. Step 11: submit and the batch binders

### 20.1 `submit`

The same built draws are submitted by several passes, each with its own program and state: the depth
prepass, the lit opaque pass, the transparent pass. So `submit` takes a **binder** that decides what to
bind for each batch, or to skip it:

```java
    public void submit(StateCache state, FrameRing ring, BatchBinder binder) {
        if (itemCount == 0) {
            return;
        }
        state.bindVertexArray(meshes.vertexArray());
        state.bindStorage(INSTANCE_BINDING, ring.glName(), instanceOffset, instanceBytes);
        state.bindStorage(MATERIAL_BINDING, materials.glBuffer(), 0, materials.bufferBytes());
        state.bindDrawIndirect(ring.glName());
        for (int b = 0; b < batchCount; b++) {
            if (!binder.bind(state, batchSlot[b], batchPass[b] == PASS_TRANSLUCENT)) {
                continue;
            }
            glMultiDrawElementsIndirect(
                    GL_TRIANGLES, GL_UNSIGNED_INT, batchOffset[b], batchCommands[b], COMMAND_BYTES);
        }
    }
```

- **One vertex array** for every mesh (§12.3), bound once.
- **The frame ring is bound twice, in two roles.** The records' range is storage binding 0; the
  **whole** ring is the `GL_DRAW_INDIRECT_BUFFER`, so `batchOffset[b]` — an offset into the ring — is
  directly the `indirect` argument (passed through the `long` overload, D24). Because the storage range
  starts at `instanceOffset`, `jeddyInstances[0]` in the shader is this build's first record, and a
  `baseInstance` (a sorted position) indexes from there.
- **The material buffer is bound whole** (`bufferBytes()`, §14.1).
- **One multi-draw per batch**, with the batch's command count and an explicit stride of 20.
- The `StateCache` (chapter [05](05-opengl-from-java.md)) skips every bind that is already in place,
  so a second pass submitting the same draws re-issues almost nothing but the multi-draws.

### 20.2 `BatchBinder`

```java
    @FunctionalInterface
    public interface BatchBinder {
        /**
         * Prepares batch drawing for program {@code slot} of the material table.
         *
         * @param translucent whether the batch is in the translucent pass
         * @return {@code false} to skip the batch
         */
        boolean bind(StateCache state, int slot, boolean translucent);
    }
```

The builder's own default (used by `submit(state, ring)` and the test) binds the slot's program with
`PipelineState.OPAQUE` or `TRANSPARENT`, and skips a batch whose program is still compiling:

```java
    private boolean bindDefault(StateCache state, int slot, boolean translucent) {
        int program = materials.glProgram(slot);
        if (program == 0) {
            return false;
        }
        state.apply(translucent ? PipelineState.TRANSPARENT : PipelineState.OPAQUE);
        state.useProgram(program);
        return true;
    }
```

`MaterialSystem` provides the renderer's binders (§22.6): `litOpaque`, `litOpaqueNoPrepass`,
`litTranslucent` and `depthOnly`, each created once as a field, so passing one to `submit` allocates
nothing. A binder that returns `false` for translucent batches (`litOpaque`, `depthOnly`) makes the same
built draws serve the opaque passes; one that returns `false` for opaque batches (`litTranslucent`)
serves the transparent pass.

## 21. Step 12: the shader side of a record

### 21.1 Finding the record

Every vertex shader that draws meshes includes
[`engine/instance.glsl`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/engine/instance.glsl):

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

vec3 jeddyTransformPoint(JeddyInstance i, vec3 p) {
    vec4 v = vec4(p, 1.0);
    return vec3(dot(i.row0, v), dot(i.row1, v), dot(i.row2, v));
}

vec3 jeddyTransformDirection(JeddyInstance i, vec3 d) {
    return vec3(dot(i.row0.xyz, d), dot(i.row1.xyz, d), dot(i.row2.xyz, d));
}
```

- The struct is §4.1's 64 bytes; `binding = 0` is `DrawBuilder.INSTANCE_BINDING`; `readonly` lets the
  compiler treat the buffer as never written by this shader. `jeddyInstances[]` is an array without a
  size: its length is whatever range is bound.
- `jeddyInstance()` is §3.3's line.
- `jeddyTransformPoint` is §2.2: each world coordinate is a dot product of a row with
  $(p_x, p_y, p_z, 1)$.
- `jeddyTransformDirection` uses only the 3×3 part, with no translation — a direction (a tangent, say)
  is not moved by a translation, which is the same as using $(d_x, d_y, d_z, 0)$.

The lit vertex shader
[`pbr/pbr.vert`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/pbr/pbr.vert)
uses them like this (the `#ifdef SKINNED` block removed; §30 has it):

```glsl
void main() {
    JeddyInstance inst = jeddyInstance();
    vec3 n;
    vec3 t;
    float handedness;
    jeddyTangentFrame(n, t, handedness);
    vec3 position = jeddyPosition;
    // ... #ifdef SKINNED: skin position, n and t (§30)
    vWorld = jeddyTransformPoint(inst, position);
    float mirror = jeddyMirrored(inst) ? -1.0 : 1.0;
    vNormal = jeddyTransformNormal(inst, n);
    vTangent = vec4(normalize(jeddyTransformDirection(inst, t)), handedness * mirror);
    vUv = vec4(jeddyUv0, jeddyUv1);
    vMaterial = inst.data.x;
    vFlags = inst.data.y;
    vFade = uintBitsToFloat(inst.data.w);
    gl_Position = jViewProjection * vec4(vWorld, 1.0);
}
```

`jeddyTangentFrame` (in `engine/vertex.glsl`) decodes the vertex's QTangent into its mesh-space normal
`n`, tangent `t` and the bitangent's sign `handedness`; `jeddyPosition` is attribute 0, the position
stream. `vMaterial` is declared `flat out uint`: a `flat` output is not interpolated across the triangle — every
fragment receives the provoking vertex's value — which is the only sensible thing for an integer index.
The fragment shader then reads the material row with it (§21.3). `jViewProjection` comes from the frame
constants (§27.6). The depth-only shader `pbr/depth.vert` does the same with a `uViewProjection` uniform
and passes the material only when alpha testing needs it.

### 21.2 Normals and mirrored instances

A surface's **normal** is a direction perpendicular to it; lighting needs it (chapter
[07](07-lighting-and-shadows.md)). Transforming a normal like a tangent goes wrong under non-uniform
scale. The fix needs the **cross product** of two vectors,

$$\mathbf{a} \times \mathbf{b} = (a_y b_z - a_z b_y,\ a_z b_x - a_x b_z,\ a_x b_y - a_y b_x),$$

a vector perpendicular to both (chapter [02](02-math-foundations.md) derives it):

```glsl
// The 3x3 part of the instance transform (columns).
mat3 jeddyLinear(JeddyInstance i) {
    return transpose(mat3(i.row0.xyz, i.row1.xyz, i.row2.xyz));
}

// True if the transform mirrors (negative determinant): triangles flip their winding.
bool jeddyMirrored(JeddyInstance i) {
    mat3 m = jeddyLinear(i);
    return dot(m[0], cross(m[1], m[2])) < 0.0;
}

// Transforms a normal by the inverse transpose (the cofactor matrix up to scale), so non-uniform
// scale keeps normals perpendicular; mirrored transforms keep their outward direction.
vec3 jeddyTransformNormal(JeddyInstance i, vec3 n) {
    mat3 m = jeddyLinear(i);
    mat3 cofactor = mat3(cross(m[1], m[2]), cross(m[2], m[0]), cross(m[0], m[1]));
    float det = dot(m[0], cross(m[1], m[2]));
    return normalize(cofactor * n) * (det < 0.0 ? -1.0 : 1.0);
}
```

- **`jeddyLinear`.** GLSL's `mat3(a, b, c)` takes *columns*; the record stores *rows*. Building the
  matrix from the rows gives the transpose, and `transpose` turns it back: `m[0]`, `m[1]`, `m[2]` are
  the columns of $R$ — the images of the mesh's $x$, $y$, $z$ axes (§2.3).
- **Why normals need another matrix.** Take a slope with normal $\mathbf{n} = (1, 1, 0)$ and a tangent
  along it, $\mathbf{t} = (1, -1, 0)$ ($\mathbf{n} \cdot \mathbf{t} = 0$). Stretch $x$ by 2: $R$ is
  diagonal $(2, 1, 1)$. The tangent becomes $R\mathbf{t} = (2, -1, 0)$. Transforming the normal the same
  way gives $(2, 1, 0)$, and $(2, 1, 0) \cdot (2, -1, 0) = 3 \ne 0$: no longer perpendicular. The
  correct matrix for normals is the **inverse transpose** $(R^{-1})^{\mathsf T}$, because for any
  tangent, $\big((R^{-1})^{\mathsf T}\mathbf{n}\big) \cdot (R\mathbf{t}) = \mathbf{n}^{\mathsf T}
  R^{-1} R\,\mathbf{t} = \mathbf{n} \cdot \mathbf{t} = 0$.
- **The cofactor matrix** is $(R^{-1})^{\mathsf T}$ times the determinant $\det R$, and its columns are
  the cross products of the columns of $R$ as written — no division needed, and the scale does not
  matter because the result is normalized. For the stretch: the columns of $R$ are $(2, 0, 0)$,
  $(0, 1, 0)$, $(0, 0, 1)$; their cross products give the columns $(1, 0, 0)$, $(0, 2, 0)$, $(0, 0, 2)$;
  applied to $(1, 1, 0)$: $(1, 2, 0)$, and $(1, 2, 0) \cdot (2, -1, 0) = 0$. ✓
- **Mirroring.** The determinant $\det R = \mathbf{m}_0 \cdot (\mathbf{m}_1 \times \mathbf{m}_2)$ is
  negative when the transform mirrors (a scale of −1 on one axis). Then the cofactor normal points
  inwards, so it is flipped. A mirrored instance also flips its triangles' winding, which culled
  pipelines would then cull wrongly; DESIGN §9.2 lists that as a known limitation (a sort-key bit for
  mirrored batches is the planned fix).

### 21.3 The material row in the fragment shader

[`engine/material.glsl`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/engine/material.glsl)
declares the row struct of §14.2 at `binding = 1` and the five texture arrays on units 0–4:

```glsl
layout(std430, binding = 1) readonly buffer JeddyMaterials {
    JeddyMaterial jMaterials[];
};

layout(binding = 0) uniform sampler2DArray jBaseColorMap;
layout(binding = 1) uniform sampler2DArray jNormalMap;
layout(binding = 2) uniform sampler2DArray jOrmMap;
layout(binding = 3) uniform sampler2DArray jEmissiveMap;
layout(binding = 4) uniform sampler2DArray jOcclusionMap;
```

and the fragment shader `pbr/pbr.frag` starts with:

```glsl
    JeddyMaterial m = jMaterials[vMaterial];
    vec4 base = m.baseColor;
#ifdef BASE_COLOR_MAP
    base *= texture(jBaseColorMap, vec3(jeddySlotUv(m, 0u, vUv), float(m.layers.x)));
#endif
```

The row comes from the material index the vertex shader passed on; the texture is sampled at the layer
the row names (§7.2), with UV set 0 or 1 as the row's `layers2.y` bit for slot 0 says
(`jeddySlotUv`). `#ifdef BASE_COLOR_MAP` is a **feature define**: only the program variant compiled for
materials that have a base-color texture contains the sampling line (§22.1).

## 22. Step 13: the material system

[`MaterialSystem`](../../modules/render/src/main/java/dev/jeddy/render/material/MaterialSystem.java)
sits on top of the material table. It turns a material's description (a `.jmat` read as
`MaterialData`, chapter [10](10-assets.md)) into a table row, decides which program variant and which
texture buckets the material draws with, and provides the binders every pass uses. Thread ownership:
the GL thread.

### 22.1 Feature masks become `#define`s

A shader **program definition** in the `ProgramCache` (chapter [05](05-opengl-from-java.md)) can be
compiled in many **variants**. Each variant is chosen by a 64-bit **feature mask**: bit $i$ set means
"compile with `#define NAME_i`", where the names come from a
[`ShaderFeatures`](../../modules/render/src/main/java/dev/jeddy/render/shader/ShaderFeatures.java)
table. The shader then contains only the code its material needs — `#ifdef BASE_COLOR_MAP` in §21.3.

The PBR and depth programs share one table:

```java
    public static ShaderFeatures features() {
        String[] names = new String[37];
        for (int i = 0; i < 16; i++) {
            names[i] =
                    i < MaterialData.FEATURE_NAMES.size()
                            ? MaterialData.FEATURE_NAMES.get(i)
                            : "J_RESERVED_" + i;
        }
        names[15] = "SKINNED"; // MaterialTable.SKINNED
        for (int i = 16; i < 32; i++) {
            names[i] = "MATERIAL_CUSTOM_" + i;
        }
        names[32] = "SUN_SHADOWS";
        names[33] = "LOCAL_SHADOWS";
        names[34] = "SCREEN_AO";
        names[35] = "DECALS";
        names[36] = "FOG_OF_WAR";
        return new ShaderFeatures(names);
    }
```

| Bits | Defines | Set by |
|---|---|---|
| 0–4 | `BASE_COLOR_MAP`, `NORMAL_MAP`, `ORM_MAP`, `EMISSIVE_MAP`, `OCCLUSION_MAP` | the texture has *arrived* (§22.3) |
| 5–8 | `ALPHA_MASK`, `ALPHA_BLEND`, `DOUBLE_SIDED`, `UV_TRANSFORM` | the `.jmat` |
| 9 | `UV1` | an arrived texture samples the second UV set |
| 10–14 | `J_RESERVED_10` .. `J_RESERVED_14` | unused |
| 15 | `SKINNED` | `MaterialTable.SKINNED`, skinned variants (§14.4) |
| 16–31 | `MATERIAL_CUSTOM_16` .. `MATERIAL_CUSTOM_31` | game-defined `.jmat` bits |
| 32 | `SUN_SHADOWS` | renderer-wide (`MaterialSystem.SUN_SHADOWS`) |
| 33 | `LOCAL_SHADOWS` | renderer-wide |
| 34 | `SCREEN_AO` | renderer-wide |
| 35 | `DECALS` | renderer-wide |
| 36 | `FOG_OF_WAR` | renderer-wide |

Bits 0–9 are `MaterialData.FEATURE_NAMES`, ten names. The renderer-wide bits are `long` constants
(`1L << 32` and up) that every lit variant includes when the feature is on: the `SceneRenderer`
constructor sets them from its configuration (§27.2), and `setGlobalFeatures` re-slots every material
when they change.

### 22.2 Fields and constructor

```java
public final class MaterialSystem implements AutoCloseable {
    public static final int TEXTURE_SLOTS = 5;

    /** Global feature: sun shadows. */
    public static final long SUN_SHADOWS = 1L << 32;
    // ... LOCAL_SHADOWS 1L << 33, SCREEN_AO 1L << 34, DECALS 1L << 35, FOG_OF_WAR 1L << 36

    /** Feature bits the depth-only variants care about. */
    private static final long DEPTH_FEATURES =
            MaterialData.FEATURE_ALPHA_MASK
                    | MaterialData.FEATURE_BASE_COLOR_MAP
                    | MaterialData.FEATURE_UV_TRANSFORM
                    | MaterialData.FEATURE_UV1
                    | MaterialTable.SKINNED;

    private static final int[] FEATURE_OF_SLOT = {
        MaterialData.FEATURE_BASE_COLOR_MAP,
        MaterialData.FEATURE_NORMAL_MAP,
        MaterialData.FEATURE_ORM_MAP,
        MaterialData.FEATURE_EMISSIVE_MAP,
        MaterialData.FEATURE_OCCLUSION_MAP
    };

    private final GpuDevice device;
    private final ProgramCache programs;
    private final MaterialTable table;
    private final TextureArrays arrays;
    private final int pbrProgram;
    private final int depthProgram;
    private final int sampler;
    private long globalFeatures;

    // Per material: feature mask without texture bits, texture refs, uv1 bits, flags.
    private int count;
    private int[] freeList = new int[16];
    private int freeCount;
    private boolean[] live = new boolean[64];
    private long[] baseFeatures = new long[64];
    private int[] textureRefs = new int[64 * TEXTURE_SLOTS];
    private float[] rows = new float[64 * MaterialTable.FLOATS];
    private final float[] row = new float[MaterialTable.FLOATS];

    // Texture sets: bucket per slot (-1 none).
    private int setCount = 1;
    private int[] setBuckets = new int[16 * TEXTURE_SLOTS];
    private final int[] lookup = new int[TEXTURE_SLOTS];
    private int boundSet = -1;
    private int boundGeneration = -1;
```

(The other global-feature constants condensed into a comment.)

- **`DEPTH_FEATURES`**: a depth-only program cares only whether the surface is alpha-tested (it must
  sample the base color's alpha), how it reaches its UVs, and whether it is skinned. Masking a slot's
  mask with these bits makes many lit variants share one depth variant.
- **`FEATURE_OF_SLOT`**: texture slot $s$ ↔ its feature bit.
- **Per material**: its base features (the `.jmat` bits without the texture bits), its five texture
  references (§23.1), and a copy of its row (`rows`) — the system keeps a CPU copy because changing one
  texture rewrites the whole row. `row` is a scratch array for handing one row to the table.
- **Texture sets**: `setBuckets[5 set + s]` is the bucket set `set` binds on unit `s`, or −1. `setCount`
  starts at 1 because `setBuckets` is filled with −1 in the constructor: set 0 is "no textures".

The constructor creates the table and the arrays, defines the two programs —

```java
        this.pbrProgram = programs.define("pbr", "pbr/pbr.vert", "pbr/pbr.frag", features);
        this.depthProgram = programs.define("depth", "pbr/depth.vert", "pbr/depth.frag", features);
```

— gets one trilinear, repeating sampler with up to 8× anisotropic filtering (fewer if the driver allows
less), builds the binders of §22.6, and adds material 0, the default: white, rough, non-metallic.

### 22.3 Writing a material, and textures that arrive later

```java
    private void write(int m, MaterialData data, boolean append) {
        long features = data.featureMask() & 0xFFFF_FFFFL;
        for (int bit : FEATURE_OF_SLOT) {
            features &= ~bit;
        }
        features &= ~MaterialData.FEATURE_UV1;
        baseFeatures[m] = features;
        Arrays.fill(textureRefs, m * TEXTURE_SLOTS, (m + 1) * TEXTURE_SLOTS, TextureArrays.NONE);
        int o = m * MaterialTable.FLOATS;
        Arrays.fill(rows, o, o + MaterialTable.FLOATS, 0f);
        for (int i = 0; i < 4; i++) {
            rows[o + i] = data.baseColor(i);
        }
        rows[o + 4] = data.emissive(0);
        rows[o + 5] = data.emissive(1);
        rows[o + 6] = data.emissive(2);
        rows[o + 7] = data.emissiveStrength();
        rows[o + 8] = data.metallic();
        rows[o + 9] = data.roughness();
        rows[o + 10] = data.normalScale();
        rows[o + 11] = data.occlusionStrength();
        for (int i = 0; i < 4; i++) {
            rows[o + 12 + i] = data.uvTransform(i);
        }
        rows[o + 16] = data.uvRotation();
        rows[o + 17] = data.alphaCutoff();
        rows[o + 26] = Float.intBitsToFloat(data.flags());
        System.arraycopy(rows, o, row, 0, MaterialTable.FLOATS);
        if (append) {
            int index = table.add(pbrProgram, features | globalFeatures, 0, row);
            if (index != m) {
                throw new IllegalStateException("material table out of step");
            }
        } else {
            table.set(m, row);
            reslot(m);
        }
    }
```

- **Base features**: the `.jmat`'s low 32 bits (`& 0xFFFF_FFFFL` keeps them and clears any higher
  bits, which belong to the renderer) **minus** the texture bits and `UV1`. A material's texture bits
  are turned on only when its textures have actually **arrived** — until its normal map is loaded, the
  material draws with the variant without `NORMAL_MAP`, which needs no normal texture bound.
- **The row** follows §14.2, float by float. `flags` is an `int` stored with `Float.intBitsToFloat`:
  the float array carries the 32 bits unchanged, and the shader's `uvec4` reads them back as an
  integer (L00 §7.2).
- A new material is appended to the table with texture set 0; an existing one (`update`, a hot-reloaded
  `.jmat`) is overwritten and re-slotted. The check `index != m` guards the invariant that the system's
  index and the table's index are the same number.

`add` reuses an index from a free list (filled by `remove`) before appending; `update` keeps a
material's index and textures while replacing its factors.

When a texture arrives (the asset loader calls this once the texture is uploaded):

```java
    public void setTexture(int material, int slot, int ref, boolean uv1) {
        int o = material * MaterialTable.FLOATS;
        textureRefs[material * TEXTURE_SLOTS + slot] = ref;
        int layer = ref == TextureArrays.NONE ? 0 : TextureArrays.layer(ref);
        // layers (uvec4) at floats 20..23, layers2.x at 24, uv1 bits at 25.
        int at = slot < 4 ? o + 20 + slot : o + 24;
        rows[at] = Float.intBitsToFloat(layer);
        int uvBits = Float.floatToRawIntBits(rows[o + 25]);
        uvBits = uv1 ? uvBits | 1 << slot : uvBits & ~(1 << slot);
        rows[o + 25] = Float.intBitsToFloat(uvBits);
        System.arraycopy(rows, o, row, 0, MaterialTable.FLOATS);
        table.set(material, row);
        reslot(material);
    }
```

The layer goes into `layers.x .. layers.w` for slots 0–3 and `layers2.x` for slot 4; the slot's UV-set
bit is set or cleared in `layers2.y` (L00 §6.2) — read back with `floatToRawIntBits`, changed, written
with `intBitsToFloat`. Then the row is rewritten and the material re-slotted:

```java
    public long features(int material) {
        long f = baseFeatures[material];
        int uvBits = Float.floatToRawIntBits(rows[material * MaterialTable.FLOATS + 25]);
        for (int s = 0; s < TEXTURE_SLOTS; s++) {
            if (textureRefs[material * TEXTURE_SLOTS + s] != TextureArrays.NONE) {
                f |= FEATURE_OF_SLOT[s];
                if ((uvBits & 1 << s) != 0) {
                    f |= MaterialData.FEATURE_UV1;
                }
            }
        }
        return f;
    }

    private void reslot(int m) {
        for (int s = 0; s < TEXTURE_SLOTS; s++) {
            int ref = textureRefs[m * TEXTURE_SLOTS + s];
            lookup[s] = ref == TextureArrays.NONE ? -1 : TextureArrays.bucket(ref);
        }
        table.setSlot(m, pbrProgram, features(m) | globalFeatures, textureSet(lookup));
    }
```

`features` adds the bit of every texture present, and `UV1` if any present texture uses the second UV
set. `reslot` collects the bucket of each slot, finds the texture set for those five buckets, and moves
the material to the slot (definition, features, set) — which bumps the table's `slotVersion` when the
slot really changes (§14.3).

### 22.4 Texture sets

```java
    private int textureSet(int[] buckets) {
        outer:
        for (int set = 0; set < setCount; set++) {
            for (int s = 0; s < TEXTURE_SLOTS; s++) {
                if (setBuckets[set * TEXTURE_SLOTS + s] != buckets[s]) {
                    continue outer;
                }
            }
            return set;
        }
        if ((setCount + 1) * TEXTURE_SLOTS > setBuckets.length) {
            int old = setBuckets.length;
            setBuckets = Arrays.copyOf(setBuckets, old * 2);
            Arrays.fill(setBuckets, old, setBuckets.length, -1);
        }
        System.arraycopy(buckets, 0, setBuckets, setCount * TEXTURE_SLOTS, TEXTURE_SLOTS);
        return setCount++;
    }
```

Find the five-bucket combination, or append it. `continue outer` jumps to the next set as soon as one
slot differs. Because the set is part of the program slot (D41), all draws of a batch need the same
buckets, which are bound once before the batch's multi-draw. When a texture arrives in a different
bucket than its material's other textures, the material simply moves to another slot.

### 22.5 Binding a set

```java
    public void invalidateBindings() {
        boundSet = -1;
    }

    private void bindSet(StateCache state, int set) {
        if (set == boundSet && boundGeneration == arrays.generation()) {
            return;
        }
        for (int s = 0; s < TEXTURE_SLOTS; s++) {
            int bucket = setBuckets[set * TEXTURE_SLOTS + s];
            if (bucket >= 0) {
                state.bindTexture(s, arrays.glName(bucket));
                state.bindSampler(s, sampler);
            }
        }
        boundSet = set;
        boundGeneration = arrays.generation();
    }
```

Binding a set binds each used bucket's array texture and the sampler on units 0–4. The system remembers
the set it bound last and skips rebinding it — unless a bucket was replaced by growth since
(`arrays.generation()` changed, §23.4), or other code used units 0–4 in between (`invalidateBindings`,
which the renderer calls before the lit passes, §27). Slots whose bucket is −1 are not bound: the
variant for that material has no sampling code for them.

### 22.6 The binders

```java
    private static long cullFor(long state, long mask) {
        return (mask & MaterialData.FEATURE_DOUBLE_SIDED) != 0
                ? PipelineState.withCull(state, PipelineState.CULL_NONE)
                : state;
    }
```

```java
    private boolean bindLit(StateCache state, int slot, long pipeline) {
        int program = table.glProgram(slot);
        if (program == 0) {
            return false;
        }
        long mask = table.slotMask(slot);
        state.apply(cullFor(pipeline, mask));
        state.useProgram(program);
        bindSet(state, table.slotTextureSet(slot));
        return true;
    }
```

A lit batch: the slot's program (skip the batch while it compiles), the pipeline state with culling
turned off for double-sided materials, and the slot's texture set. The public binders are fields built
once in the constructor:

```java
        this.litOpaque =
                (state, slot, translucent) ->
                        !translucent
                                && bindLit(
                                        state,
                                        slot,
                                        PipelineState.withDepthWrite(PipelineState.OPAQUE, false));
        this.litOpaqueNoPrepass =
                (state, slot, translucent) ->
                        !translucent && bindLit(state, slot, PipelineState.OPAQUE);
        // Back faces of blended surfaces are culled unless the material is double-sided (unsorted
        // back and front faces of one mesh would blend in arbitrary order).
        this.litTranslucent =
                (state, slot, translucent) ->
                        translucent
                                && bindLit(
                                        state,
                                        slot,
                                        PipelineState.withCull(
                                                PipelineState.TRANSPARENT,
                                                PipelineState.CULL_BACK));
```

- **`litOpaque`** runs after the depth prepass: depth test `GL_GEQUAL` (the reversed-Z test, chapter
  [04](04-cameras-and-projection.md)) **without** depth writes. Not `GL_EQUAL`: the prepass and the lit
  pass are two different programs, and without the `invariant` qualifier nothing guarantees they
  compute bit-identical depths; `EQUAL` would turn the last-bit differences into holes (D42).
  `GEQUAL` against the prepass's depth passes exactly the nearest surface, and still rejects everything
  behind it early (§5.1).
- **`litTranslucent`** blends (`PipelineState.TRANSPARENT`: alpha blending, depth test without writes)
  and culls back faces, as the comment explains.
- **`depthOnly`** (not shown in full) skips translucent batches, takes the depth program variant
  `mask & DEPTH_FEATURES`, applies `DEPTH_ONLY` (or a pipeline override set with `configureDepth`, used by
  the shadow passes), calls a callback with the program of **every** batch it accepts so the caller can
  set that program's view-projection uniform, and binds textures only for alpha-tested materials.
- `depthBinder(definition, uniforms, pipeline)` builds a binder like `depthOnly` for another program
  (masks, outlines, custom depth-like passes); its Javadoc asks callers to create it once.

The lambdas capture `this`, so each is an object — created once, in the constructor, and kept in a
`final` field. Passing it to `submit` every frame allocates nothing.

## 23. Step 14: texture arrays

[`TextureArrays`](../../modules/render/src/main/java/dev/jeddy/render/material/TextureArrays.java)
manages the buckets of §7.2. Thread ownership: the GL thread.

### 23.1 References

```java
    /** No texture. */
    public static final int NONE = -1;

    private static final int INITIAL_LAYERS = 4;

    // ... the fields and the constructor (§23.2)

    /** Returns the bucket of a reference. */
    public static int bucket(int ref) {
        return ref >>> 16;
    }

    /** Returns the layer of a reference. */
    public static int layer(int ref) {
        return ref & 0xFFFF;
    }
```

A texture in an array is a **reference**: bucket in the high 16 bits, layer in the low 16 (L00 §6.4).
Bucket 3, layer 7 is `3 << 16 | 7` = `0x00030007` = 196,615. `NONE` is −1, all ones (L00 §5.3) —
callers check for it before unpacking, because `bucket(-1)` would be `0xFFFF`.

### 23.2 Fields

```java
    private final GpuDevice device;
    private final int maxLayers;
    private int bucketCount;
    private int[] format = new int[8];
    private int[] width = new int[8];
    private int[] height = new int[8];
    private int[] levels = new int[8];
    private int[] texture = new int[8];
    private int[] capacity = new int[8];
    private int[] used = new int[8];
    private int[][] freeLayers = new int[8][];
    private int[] freeCount = new int[8];
    private int generation;
```

SoA per bucket: its description (GL internal format, width, height, mip levels), its array texture's
handle, how many layers it has (`capacity`) and has handed out (`used`), and a stack of freed layers.
`maxLayers` is `Math.max(64, device.caps().maxArrayTextureLayers())`, the driver's limit on layers per
array (GL 4.6 requires at least 2048).

### 23.3 `allocate`

```java
    public int allocate(int internalFormat, int w, int h, int levelCount) {
        int b = find(internalFormat, w, h, levelCount);
        if (b < 0) {
            b = createBucket(internalFormat, w, h, levelCount);
        }
        int layer;
        if (freeCount[b] > 0) {
            layer = freeLayers[b][--freeCount[b]];
        } else {
            if (used[b] == capacity[b]) {
                grow(b);
            }
            layer = used[b]++;
        }
        return b << 16 | layer;
    }
```

Find a bucket with exactly this description that still has room (`find` also accepts a bucket with a
free layer or with `used < maxLayers`), or create one with 4 layers; take a freed layer if there is one,
else the next new layer, growing the bucket when it is full. Create:

```java
        texture[b] =
                device.textures()
                        .create(
                                GpuTextures.KIND_2D_ARRAY,
                                internalFormat,
                                w,
                                h,
                                INITIAL_LAYERS,
                                levelCount,
                                "texture bucket " + b + " " + w + "x" + h);
```

An array texture's storage is fixed when it is created (immutable storage, like buffers in L01 §4):
format, size, mip levels and layer count. Each layer has its own full chain of mip levels.

### 23.4 Growth, and a driver's limits

```java
    private void grow(int b) {
        int cap = Math.min(maxLayers, capacity[b] * 2);
        if (cap == capacity[b]) {
            throw new IllegalStateException("texture bucket " + b + " is at the layer limit");
        }
        int bigger =
                device.textures()
                        .create(
                                GpuTextures.KIND_2D_ARRAY,
                                format[b],
                                width[b],
                                height[b],
                                cap,
                                levels[b],
                                "texture bucket " + b + " " + width[b] + "x" + height[b]);
        device.textures().copyLayers(texture[b], bigger, used[b]);
        device.textures().destroy(texture[b]);
        texture[b] = bigger;
        capacity[b] = cap;
        generation++;
    }
```

Because the storage is immutable, growing means a new, bigger array: double the layers (up to the
limit), copy the used layers, delete the old texture (GL keeps it alive until pending draws finish),
and increment **`generation`** so cached bindings (§22.5) notice the bucket's GL name changed.

`GpuTextures.copyLayers` copies on the GPU with `glCopyImageSubData`, **except** the mip levels smaller
than one 4×4 compression block (2×2 and 1×1): one Intel Windows driver rejects both copying and reading
back those levels, so `GpuTextures` keeps a CPU copy of them when a layer is uploaded — 16 bytes per
level per layer — and uploads them again into the bigger array (D101; L01 §17.1–17.2 tells the story).

`free(ref)` pushes the layer onto its bucket's free stack (growing the stack if needed); the next
`allocate` of that description reuses it.

### 23.5 Where the textures come from

[`TextureLoader`](../../modules/render/src/main/java/dev/jeddy/render/material/TextureLoader.java)
decodes a `.ktx2` on a worker and uploads it on the main thread:

```java
            boolean layer = !k.isCubeMap() && !k.isArray() && k.depth() <= 1;
            if (layer) {
                t.arrayRef = arrays.allocate(format, k.width(), k.height(), k.levels());
                int texture = arrays.textureHandle(TextureArrays.bucket(t.arrayRef));
                for (int l = 0; l < k.levels(); l++) {
                    MemorySegment level = level(t, l);
                    device.textures()
                            .uploadLayer(
                                    texture,
                                    l,
                                    TextureArrays.layer(t.arrayRef),
                                    level.address(),
                                    (int) level.byteSize());
                }
            } else {
                t.handle = device.textures().createFor(k, "texture");
                // ... one upload per level into a texture of its own
            }
```

A plain 2D texture becomes a layer of a bucket, uploaded level by level from client memory (which lets
`GpuTextures` keep the small-level mirror of D101). Cube maps, arrays and 3D textures (environment maps,
lookup tables) get a texture of their own (D41). The bucket and layer reach the material through
`MaterialSystem.setTexture` (§22.3), called by `MaterialAssets` when the texture is ready.

## 24. Step 15: the frame graph

### 24.1 The class and its constants

```java
public final class FrameGraph implements AutoCloseable {
    /** The window's default framebuffer (color). Writing it makes a pass live. */
    public static final int BACKBUFFER = 0;

    private static final int KIND_BACKBUFFER = 0;
    private static final int KIND_TRANSIENT = 1;
    private static final int KIND_IMPORTED_TEXTURE = 2;
    private static final int KIND_IMPORTED_BUFFER = 3;

    /** How a pass reads a resource; decides the barrier after a compute write. */
    private static final int READ_TEXTURE = GL_TEXTURE_FETCH_BARRIER_BIT;

    private static final int READ_STORAGE = GL_SHADER_STORAGE_BARRIER_BIT;
    private static final int READ_INDIRECT = GL_COMMAND_BARRIER_BIT;
    private static final int READ_IMAGE = GL_SHADER_IMAGE_ACCESS_BARRIER_BIT;
```

Resources and passes are numbered with `int`s. The four kinds of resource are the backbuffer (always
resource 0), transient textures, and imported textures and buffers (§8.2). The trick in the read
constants: each **is** the barrier bit for that kind of read (§8.3), so computing a pass's barrier is an
OR of the usages it declared.

```java
    private final GpuDevice device;
    private final PassContext context = new PassContext(this);
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment clearValue = arena.allocate(16, 16);

    // Resources.
    private final List<String> resourceNames = new ArrayList<>();
    private final List<int[]> resourceDesc = new ArrayList<>(); // kind, format
    private final List<float[]> resourceScale = new ArrayList<>();
    private int[] glNames = new int[8];
    private int[] textureHandles = new int[8];

    // Passes.
    private final List<Pass> passes = new ArrayList<>();
    private Pass[] live = new Pass[0];
    private GpuTimers timers;
    private int width;
    private int height;
    private boolean compiled;
    private long barriers;
```

The declarations live in `java.util` lists: they are built once at startup, where convenience wins.
What a frame touches is arrays: `live` (the compiled pass order), `glNames` and `textureHandles` (per
resource). One `PassContext` and one 16-byte `clearValue` segment are reused every frame. Thread
ownership: the GL thread.

### 24.2 Declaring resources

```java
    public FrameGraph(GpuDevice device) {
        this.device = device;
        resourceNames.add("backbuffer");
        resourceDesc.add(new int[] {KIND_BACKBUFFER, 0});
        resourceScale.add(new float[] {1});
    }

    public int texture(String name, int internalFormat, float scale) {
        return resource(name, KIND_TRANSIENT, internalFormat, scale);
    }

    public int importedTexture(String name) {
        return resource(name, KIND_IMPORTED_TEXTURE, 0, 1);
    }

    public int importedBuffer(String name) {
        return resource(name, KIND_IMPORTED_BUFFER, 0, 1);
    }
```

(Javadoc trimmed.) A **transient** texture is owned by the graph, with a GL internal format and a
`scale` relative to the frame (0.5 for half resolution). An **imported** resource is owned elsewhere —
the shadow atlas, the light clusters' buffer — and its GL name is given with `setTexture(resource,
glName)` or `setBuffer(resource, glName)`. Each declaration appends to the lists, marks the graph as
needing a compile, and returns the resource's number.

### 24.3 Declaring passes

`addPass(name, executor)` and `addComputePass(name, executor)` append a `Pass` and return it, so its
usage can be declared by chaining:

```java
    public final class Pass {
        final String name;
        final boolean compute;
        final PassExecutor executor;
        final List<int[]> reads = new ArrayList<>(); // resource, usage
        final List<Integer> writes = new ArrayList<>();
        final List<Integer> colorTargets = new ArrayList<>();
        final List<float[]> colorClears = new ArrayList<>();
        int depthTarget = -1;
        float depthClear = Float.NaN;
        boolean sideEffect;
        final int index;
        // Compiled.
        int framebuffer; // GL name; 0 = default framebuffer
        int framebufferHandle = Handles.NULL;
        int barrierBits;
        boolean clears;
```

| Method | Records | Meaning |
|---|---|---|
| `color(r)` | color target, write | render into `r` at the next attachment, keeping its contents |
| `color(r, red, green, blue, alpha)` | color target with a clear, write | clear `r` to that color first |
| `depth(r, clear)` | depth target, write | depth attachment, cleared to `clear` unless NaN |
| `depthReadOnly(r)` | depth target, read with usage 0 | test against `r` without writing or clearing it |
| `readsTexture(r)` | read, `READ_TEXTURE` | sample `r` |
| `readsStorage(r)` | read, `READ_STORAGE` | read `r` as a storage buffer |
| `readsIndirect(r)` | read, `READ_INDIRECT` | read `r` as draw or dispatch arguments |
| `readsImage(r)` | read, `READ_IMAGE` | image loads from `r` |
| `writes(r)` | write | shader writes into `r` (storage, images) |
| `sideEffect()` | flag | keep the pass even if nobody reads its outputs |

`NaN` (L00 §7.3) is the "no clear" marker for depth, because any real number is a valid clear value.
`depthReadOnly` reads with usage 0: depth is written by graphics passes, which never need a barrier
(§8.3).

The graph's test (§32.5) declares a compute pass that writes indirect draw arguments, a pass that draws
with them, an unused pass and a post pass that writes the window:

```java
            int depth = graph.texture("depth", GL_DEPTH_COMPONENT32F, 1f);
            int color = graph.texture("color", GL_RGBA8, 1f);
            int half = graph.texture("half", GL_RGBA8, 0.5f);
            int args = graph.importedBuffer("args");
            graph.setBuffer(args, device.buffers().glName(argsBuffer));
            graph.addComputePass(
                            "fill args",
                            ctx -> {
                                ctx.state().useProgram(fill);
                                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 4, ctx.buffer(args));
                                glDispatchCompute(1, 1, 1);
                            })
                    .writes(args);
            graph.addPass(
                            "scene",
                            ctx -> {
                                // ... apply state, program, vertex array
                                ctx.state().bindDrawIndirect(ctx.buffer(args));
                                glDrawArraysIndirect(GL_TRIANGLES, 0L);
                            })
                    .readsIndirect(args)
                    .color(color, 0f, 0f, 1f, 1f)
                    .depth(depth, 0f);
            graph.addPass(
                            "unused",
                            ctx -> {
                                throw new AssertionError("culled passes never run");
                            })
                    .color(half, 1, 1, 1, 1);
            graph.addPass(
                            "post",
                            ctx -> {
                                // ... sample `color`, draw a full-screen triangle
                            })
                    .readsTexture(color)
                    .color(FrameGraph.BACKBUFFER);
            graph.compile(SIZE, SIZE);
```

### 24.4 `compile`, part 1: liveness

```java
    public void compile(int w, int h) {
        releaseTargets();
        width = w;
        height = h;
        int n = passes.size();
        // Liveness, back to front.
        boolean[] isLive = new boolean[n];
        boolean[] needed = new boolean[resourceNames.size()];
        for (int i = n - 1; i >= 0; i--) {
            Pass p = passes.get(i);
            boolean keep = p.sideEffect;
            for (int r : p.writes) {
                int kind = resourceDesc.get(r)[0];
                keep |= needed[r] || kind != KIND_TRANSIENT;
            }
            if (!keep) {
                continue;
            }
            isLive[i] = true;
            for (int[] read : p.reads) {
                needed[read[0]] = true;
            }
            if (p.depthTarget >= 0) {
                needed[p.depthTarget] = true;
            }
        }
```

§8.2 in code. `releaseTargets()` first deletes the textures and framebuffers of a previous compile (a
resize). Then, from the last pass to the first: a pass is kept if it has a side effect or any of its
writes is needed by a later live pass or is not transient (the backbuffer and imported resources are
always "seen"). A kept pass marks everything it reads, and its depth target, as needed. `compile`
allocates freely: it runs at startup and on resize, never per frame.

### 24.5 `compile`, part 2: validation and barriers

```java
        // Validation and barriers, front to back.
        boolean[] written = new boolean[resourceNames.size()];
        int[] pendingCompute = new int[resourceNames.size()]; // 1 if last written by compute
        List<Pass> order = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (!isLive[i]) {
                continue;
            }
            Pass p = passes.get(i);
            int bits = 0;
            for (int[] read : p.reads) {
                int r = read[0];
                if (resourceDesc.get(r)[0] == KIND_TRANSIENT && !written[r]) {
                    throw new IllegalStateException(
                            "pass '"
                                    + p.name
                                    + "' reads '"
                                    + resourceNames.get(r)
                                    + "' before any pass writes it");
                }
                if (pendingCompute[r] != 0) {
                    bits |= read[1];
                }
            }
            p.barrierBits = bits;
            for (int r : p.writes) {
                written[r] = true;
                pendingCompute[r] = p.compute ? 1 : 0;
            }
            order.add(p);
        }
```

Now from the first live pass forwards, tracking for each resource whether a live pass has written it yet
and whether its **last** writer was a compute pass:

- **Reading a transient nobody wrote** is a bug in the declarations — the pass would read garbage — so
  `compile` throws with both names (`FrameGraphTest.readingBeforeWritingIsAnError`). Imported resources
  are exempt: their contents come from outside.
- **A read of something a compute pass wrote last** adds that read's barrier bit (§8.3).
- **A write** updates both trackers: a write by a graphics pass clears the "pending compute" mark. The
  code *assumes* that a graphics pass needs no barrier against an earlier compute write. GL does not
  guarantee it: rendering into an attachment that shaders wrote with image stores needs
  `GL_FRAMEBUFFER_BARRIER_BIT` first, and a graphics pass's own storage or image writes are incoherent
  too. No current pass meets the case — in `SceneRenderer`, `GpuParticles` and `PostProcess` no graphics
  pass writes what a compute pass wrote — so v1 lives with it (§8.3 lists it among the limits).

### 24.6 `compile`, part 3: transient textures

```java
        // Transient textures.
        for (int r = 1; r < resourceNames.size(); r++) {
            int[] desc = resourceDesc.get(r);
            if (desc[0] != KIND_TRANSIENT || !needed[r] && !written[r]) {
                continue;
            }
            float scale = resourceScale.get(r)[0];
            int tw = Math.max(1, Math.round(w * scale));
            int th = Math.max(1, Math.round(h * scale));
            int t =
                    device.textures()
                            .create(
                                    GpuTextures.KIND_2D,
                                    desc[1],
                                    tw,
                                    th,
                                    1,
                                    1,
                                    resourceNames.get(r));
            textureHandles[r] = t;
            glNames[r] = device.textures().glName(t);
        }
```

One 2D texture with one mip level per transient that a live pass reads or writes, sized
$\text{round}(w \times \text{scale}) \times \text{round}(h \times \text{scale})$, at least 1×1. A
half-resolution target of a 1920×1080 frame is 960×540. There is **no aliasing** yet: two transients
whose lifetimes never overlap could share one texture's memory, but v1 gives each its own (D32). (The
class Javadoc still says "pooled by descriptor"; the code creates one texture per transient.)

### 24.7 `compile`, part 4: framebuffers, clears, timers

```java
        // Framebuffers.
        for (Pass p : order) {
            if (p.compute || p.colorTargets.isEmpty() && p.depthTarget < 0) {
                continue;
            }
            if (p.colorTargets.contains(BACKBUFFER)) {
                if (p.colorTargets.size() != 1 || p.depthTarget >= 0) {
                    throw new IllegalStateException(
                            "pass '" + p.name + "' mixes the backbuffer with other attachments");
                }
                p.framebuffer = 0;
                continue;
            }
            int[] colors = new int[p.colorTargets.size()];
            for (int c = 0; c < colors.length; c++) {
                colors[c] = targetTexture(p.colorTargets.get(c));
            }
            int depth = p.depthTarget >= 0 ? targetTexture(p.depthTarget) : Handles.NULL;
            p.framebufferHandle = device.framebuffers().create(colors, depth, p.name);
            p.framebuffer = device.framebuffers().glName(p.framebufferHandle);
        }
        for (Pass p : order) {
            p.clears = !Float.isNaN(p.depthClear) && p.depthTarget >= 0;
            for (float[] c : p.colorClears) {
                p.clears |= c != null;
            }
        }
        live = order.toArray(new Pass[0]);
        if (timers != null) {
            timers.close();
        }
        timers = new GpuTimers(Math.max(1, passes.size()));
        compiled = true;
    }
```

- Each graphics pass with attachments gets a **framebuffer** made of its color targets and depth target
  (`targetTexture` refuses an imported texture as an attachment in v1). A pass that writes the
  **backbuffer** uses the window's framebuffer, name 0, and may not combine it with other attachments:
  GL cannot mix the window's own images with textures in one framebuffer.
- **`clears`** pre-computes whether the pass clears anything, so `execute` can skip the clear setup
  otherwise.
- **`live`** becomes an array, and the **timers** get one scope per declared pass (§25).

### 24.8 `execute`

```java
    public void execute(long frame) {
        if (!compiled) {
            throw new IllegalStateException("compile the frame graph first");
        }
        context.width = width;
        context.height = height;
        context.frame = frame;
        timers.beginFrame();
        var state = device.state();
        for (Pass p : live) {
            timers.begin(p.index);
            if (p.barrierBits != 0) {
                glMemoryBarrier(p.barrierBits);
                barriers++;
            }
            if (!p.compute && (!p.colorTargets.isEmpty() || p.depthTarget >= 0)) {
                state.bindFramebuffer(p.framebuffer);
                float scale =
                        p.colorTargets.isEmpty()
                                ? scaleOf(p.depthTarget)
                                : scaleOf(p.colorTargets.get(0));
                state.viewport(
                        0,
                        0,
                        Math.max(1, Math.round(width * scale)),
                        Math.max(1, Math.round(height * scale)));
                if (p.clears) {
                    // Clears obey the color mask, depth mask and scissor test: open them up.
                    long s = state.state();
                    s = PipelineState.withColorMask(s, 0xF);
                    s = PipelineState.withScissor(s, false);
                    s = PipelineState.withDepthWrite(s, true);
                    state.apply(s);
                }
                for (int c = 0; c < p.colorClears.size(); c++) {
                    float[] rgba = p.colorClears.get(c);
                    if (rgba != null) {
                        MemorySegment.copy(rgba, 0, clearValue, ValueLayout.JAVA_FLOAT, 0, 4);
                        glClearNamedFramebufferfv(p.framebuffer, GL_COLOR, c, clearValue);
                    }
                }
                if (p.depthTarget >= 0 && !Float.isNaN(p.depthClear)) {
                    clearValue.set(ValueLayout.JAVA_FLOAT, 0, p.depthClear);
                    glClearNamedFramebufferfv(p.framebuffer, GL_DEPTH, 0, clearValue);
                }
            }
            GLDebug.pushGroup(p.name);
            p.executor.execute(context);
            GLDebug.popGroup();
            timers.end(p.index);
        }
    }
```

For each live pass, in declaration order:

1. **A GPU timestamp** marks its start (§25).
2. **Its barrier**, if `compile` gave it one. The barrier comes *before* the reading pass, which places it
   after every earlier command — including the compute pass that wrote.
3. **For a graphics pass with attachments**: bind its framebuffer and set the viewport to its target's
   scale. **Clears respect the color write mask, the depth write mask and the scissor test** — a pass
   after a depth-only pass would otherwise silently fail to clear its color target — so the clear first
   opens all three. Then `glClearNamedFramebufferfv` clears each color attachment with a clear color and
   the depth attachment. (The GPU fills the attachment with the value; many GPUs make this cheap by
   marking blocks of the image as cleared instead of writing every pixel.)
4. **A debug group** with the pass's name, so GPU debuggers such as RenderDoc show each pass as a named
   node.
5. **The pass's own work**, through its executor.

Nothing here allocates: `for (Pass p : live)` over an array uses no iterator, the clear value goes
through the one preallocated segment, and the `List` calls on the pass's declarations read objects that
already exist. `barriers()` counts the barriers issued, which the tests check.

### 24.9 The pass's view: `PassExecutor` and `PassContext`

```java
@FunctionalInterface
public interface PassExecutor {
    /** Records the pass's GL work. The graph has bound its framebuffer and viewport. */
    void execute(PassContext context);
}
```

A [`PassExecutor`](../../modules/render/src/main/java/dev/jeddy/render/graph/PassExecutor.java) is
registered once and called every frame the pass is live, so it must not allocate.
[`PassContext`](../../modules/render/src/main/java/dev/jeddy/render/graph/PassContext.java) gives it the
device, the state cache, the frame's width, height and number, and `texture(resource)` /
`buffer(resource)` to look up GL names — which change on every resize, so a pass asks the context
instead of caching them.

## 25. Step 16: GPU timers

How long did each pass take **on the GPU**? The CPU cannot time it: the calls return long before the
GPU runs them (L00 §10.5). The GPU can — with a **timestamp query**:
`glQueryCounter(query, GL_TIMESTAMP)` records the GPU's clock into a query object when the GPU has
completed all commands issued before it. Two timestamps around a pass bracket its GPU time.

Reading a result too early would make the CPU wait for the GPU, a stall.
[`GpuTimers`](../../modules/render/src/main/java/dev/jeddy/render/graph/GpuTimers.java) therefore keeps
a ring of queries and reads them a few frames late (DESIGN §9.6):

```java
public final class GpuTimers implements AutoCloseable {
    private static final int FRAMES = 4;
    private static final int LATENCY = 3;

    private final int scopes;
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment names;
    private final MemorySegment result = arena.allocate(ValueLayout.JAVA_LONG);
    private final boolean[] issued;
    private final double[] smoothedNanos;
    private long frame;

    public GpuTimers(int scopes) {
        this.scopes = scopes;
        int n = scopes * 2 * FRAMES;
        names = arena.allocate(ValueLayout.JAVA_INT, n);
        glCreateQueries(GL_TIMESTAMP, n, names);
        issued = new boolean[n];
        smoothedNanos = new double[scopes];
    }
```

Two queries (begin, end) per scope per frame, for 4 frames: `index(slot, scope, which)` is
$(\text{slot} \times \text{scopes} + \text{scope}) \times 2 + \text{which}$. A frame stamps into slot
$\text{frame} \bmod 4$:

```java
    private void stamp(int s, int which) {
        int i = index((int) (frame % FRAMES), s, which);
        glQueryCounter(name(i), GL_TIMESTAMP);
        issued[i] = true;
    }
```

and `beginFrame` harvests the slot written 3 frames earlier:

```java
    public void beginFrame() {
        frame++;
        if (frame <= LATENCY) {
            return;
        }
        int slot = (int) ((frame - LATENCY) % FRAMES);
        for (int s = 0; s < scopes; s++) {
            int begin = index(slot, s, 0);
            int end = index(slot, s, 1);
            if (!issued[begin] || !issued[end]) {
                continue;
            }
            glGetQueryObjectui64v(name(end), GL_QUERY_RESULT_AVAILABLE, result);
            if (result.get(ValueLayout.JAVA_LONG, 0) == 0) {
                continue; // not there yet: skip rather than stall
            }
            glGetQueryObjectui64v(name(end), GL_QUERY_RESULT, result);
            long t1 = result.get(ValueLayout.JAVA_LONG, 0);
            glGetQueryObjectui64v(name(begin), GL_QUERY_RESULT, result);
            long t0 = result.get(ValueLayout.JAVA_LONG, 0);
            double ns = Math.max(0, t1 - t0);
            smoothedNanos[s] = smoothedNanos[s] == 0 ? ns : smoothedNanos[s] * 0.9 + ns * 0.1;
            issued[begin] = false;
            issued[end] = false;
        }
    }
```

- **Three frames late**, the GPU has almost always finished; `GL_QUERY_RESULT_AVAILABLE` checks without
  waiting, and a result that is not there yet is skipped for this frame rather than waited for. (The
  next frame stamps over that slot, so a skipped sample is simply lost.)
- **Only scopes stamped** in that frame are read: a pass that was not live leaves `issued` false.
- **Smoothing**: the first sample is taken as is; after that $t \leftarrow 0.9\,t + 0.1\,t_{\text{new}}$,
  an exponential moving average that steadies the profiler's numbers.

`FrameGraph.passMillis(i)` returns `timers.millis(i)`, which the profiler overlay shows (§31).

## 26. Step 17: the static scene on the GPU

[`StaticScene`](../../modules/render/src/main/java/dev/jeddy/render/draw/StaticScene.java) (TASKS
M7-11, D49) keeps the instances that never move on the GPU and lets the compute shaders of §9 cull them,
choose their LOD and build their draws, for the main view and for each sun shadow cascade. Thread
ownership: the render thread.

### 26.1 The contract

The GPU can only keep data that stays valid, so the game promises something (D49): **while
`RenderPacket.staticVersion` stays the same, the instances flagged `FLAG_STATIC` keep their packet
index, their transform, their mesh and their material.** Only their flags and camera fade may change,
and those are copied every frame. `World.extract` in the `app` module keeps the promise: it writes
static renderables first, in a stable order, and increments its version whenever that set changes.

Three more rules complete it:

- A material moving to another program slot (§14.3, `slotVersion`) also triggers a rebuild, because the
  scene groups its draws by slot.
- Static instances with a **blended** material stay on the CPU path, because they need sorting back to
  front every frame (§5.1). The scene lists them in `cpuInstances()`.
- Skinned instances are never static (`setPalette` clears the flag, §10.4). Outlines cull highlighted
  instances themselves, static ones included (D49).

### 26.2 What lives on the GPU

| Buffer | Record | Content |
|---|---|---|
| items | 96 B per (instance, submesh) | world transform (3 rows), world bounding sphere, ids (packet index, bin, material, entity), scale for LOD (length of the first column, §2.3) |
| bins | 48 B per bin | LOD count, item count, first record, first command; 8 LOD errors |
| command template | 20 B per command | `count`, 0 instances, `firstIndex`, `baseVertex`, `baseInstance` |
| batch table | 4 B per command, then 4 B per batch | command → batch; each batch's first packed position |
| per view (5): commands | 20 B per command, twice, then 4 B per batch | the commands, the packed commands, the per-batch counts |
| per view (5): records | 64 B per record | instance records, the layout of §4.1 |

A **bin** is one (program slot, mesh, submesh) combination: every item in a bin draws the same submesh
with the same program, so a bin needs one indirect command **per LOD**. Each (bin, LOD) command gets room
for **every** item of the bin, so a cull invocation can write its record at once without first counting
how many items chose that LOD — that would need another pass on the GPU (a prefix sum) between culling
and writing. The price is memory: a bin of $m$ items with $L$ LODs reserves $m L$ records.

A **batch** is one program slot: the consecutive commands of all its bins. There are five views:
`VIEW_MAIN` (0) and `VIEW_CASCADES + c` (1 to 4) for the sun cascades. Each view has its own commands
and records, because each culls against its own planes:

```
per-view commands buffer (uint words):
 [ commands: 5 x commandCount | packed: 5 x commandCount | counts: batchCount ]
 ^ 0                          ^ packedOffsetBytes         ^ countsOffsetBytes
```

These buffers are written only by GPU commands — copies, clears and compute shaders — never by the CPU,
so unlike the frame ring they need no region per frame in flight and no fence: the CPU never writes
memory a frame in flight reads, and the next frame's writes are commands queued behind this frame's
draws (L01 §2.3).

### 26.3 Fields and the per-frame update

```java
    public static final int VIEW_MAIN = 0;
    public static final int VIEW_CASCADES = 1;
    public static final int VIEWS = 5;

    private static final int ITEM_BYTES = 96;
    private static final int BIN_BYTES = 48;
    private static final int COMMAND_BYTES = 20;
    private static final int MAX_LODS = 8;
    private static final int GROUP = 64;
```

(Javadoc trimmed.) `GROUP` is the workgroup size of both compute shaders (§9.1). `MAX_LODS` is 8 because
a bin stores its LOD errors in two `vec4`s (§26.6). The other fields hold the two compute programs'
definitions, a 96-byte segment for the planes, the version numbers the scene was built for, the counts
and the buffer handles of §26.2, and three per-frame values — the ring offsets of this frame's flags and
fades and their size.

```java
    public void update(RenderPacket p, FrameRing frameRing) {
        ring = frameRing;
        if (!built
                || p.staticVersion != builtVersion
                || materials.slotVersion() != builtSlotVersion) {
            rebuild(p);
        }
        flagsOffset = -1;
        if (!active()) {
            return;
        }
        int n = p.count();
        frameBytes = Math.max(16, (long) n * 4);
        int align = device.caps().shaderStorageBufferOffsetAlignment();
        flagsOffset = frameRing.allocate(frameBytes, align);
        fadesOffset = frameRing.allocate(frameBytes, align);
        if (flagsOffset < 0 || fadesOffset < 0) {
            flagsOffset = -1;
            return;
        }
        MemorySegment m = frameRing.memory();
        MemorySegment.copy(p.flags, 0, m, ValueLayout.JAVA_INT, flagsOffset, n);
        MemorySegment.copy(p.fade, 0, m, ValueLayout.JAVA_FLOAT, fadesOffset, n);
    }
```

The main thread's whole per-frame cost for the static scene: compare two version numbers, then **two
bulk copies** — every instance's flags and fade into the ring, where the cull shader reads them by packet
index. The copies are sequential writes into write-combined memory, the cheapest kind (L01 §3.3).
`active()` is `built && itemCount > 0`. `flagsOffset = -1` is the "not usable this frame" marker that
`cull` and `submit` check.

### 26.4 `rebuild`, part 1: classifying and binning

The rebuild is the cold path: it runs when the static set changes, allocates freely, and builds GPU
buffers. Shown in stages.

```java
    private void rebuild(RenderPacket p) {
        release();
        built = true;
        builtVersion = p.staticVersion;
        builtSlotVersion = materials.slotVersion();
        int n = p.count();
        // Classify: GPU items or CPU-path instances.
        int[] gpu = new int[n];
        int gpuCount = 0;
        int[] cpu = new int[16];
        int cpus = 0;
        int statics = 0;
        Map<Long, Integer> binOfKey = new HashMap<>();
        long[] binKeys = new long[64];
        int[] binMesh = new int[64];
        int bins = 0;
        int items = 0;
        for (int i = 0; i < n; i++) {
            if ((p.flags[i] & RenderPacket.FLAG_STATIC) == 0) {
                continue;
            }
            statics++;
            int mesh = p.mesh[i];
            boolean blended = (p.flags[i] & RenderPacket.FLAG_TRANSLUCENT) != 0;
            if (meshes.isValid(mesh)) {
                for (int s = 0; s < meshes.submeshCount(mesh) && !blended; s++) {
                    long mask = materials.slotMask(materials.programSlot(materialOf(p, i, s)));
                    blended = (mask & MaterialData.FEATURE_ALPHA_BLEND) != 0;
                }
            }
            if (blended || !meshes.isValid(mesh)) {
                if (cpus == cpu.length) {
                    cpu = Arrays.copyOf(cpu, cpus * 2);
                }
                cpu[cpus++] = i;
                continue;
            }
            gpu[gpuCount++] = i;
            for (int s = 0; s < meshes.submeshCount(mesh); s++) {
                long key = binKey(p, i, s);
                if (!binOfKey.containsKey(key)) {
                    if (bins == binKeys.length) {
                        binKeys = Arrays.copyOf(binKeys, bins * 2);
                        binMesh = Arrays.copyOf(binMesh, bins * 2);
                    }
                    binKeys[bins] = key;
                    binMesh[bins] = mesh;
                    binOfKey.put(key, bins++);
                }
                items++;
            }
        }
```

- `release()` deletes the buffers of the previous build.
- **Classification.** A static instance goes to the CPU path if it is translucent or any of its
  submeshes' materials blends — or if its mesh is not loaded (the CPU path will skip it). Every other
  static instance becomes GPU **items**, one per submesh.
- **Bins** are found with a `HashMap<Long, Integer>` from bin key to bin number. Boxing and a hash map
  are banned per frame but fine here: this runs only when the level's static content changes.

```java
    /** Bin key: program slot (bits 40+), mesh handle index (8..39), submesh (0..7). */
    private long binKey(RenderPacket p, int i, int submesh) {
        int slot = materials.programSlot(materialOf(p, i, submesh));
        int mesh = p.mesh[i];
        return (long) slot << 40 | (long) Handles.index(mesh) << 8 | submesh;
    }
```

A packed key again (L00 §6.4), with the slot in the highest bits so that sorting bins by key makes each
slot's bins contiguous.

### 26.5 `rebuild`, part 2: commands, records, batches

```java
        // Bins in key order (slot first), so each slot's commands are contiguous.
        long[] sorted = Arrays.copyOf(binKeys, bins);
        Arrays.sort(sorted);
        int[] binIndex = new int[bins]; // original bin id -> sorted position
        int[] sortedMesh = new int[bins];
        for (int b = 0; b < bins; b++) {
            int original = binOfKey.get(sorted[b]);
            binIndex[original] = b;
            sortedMesh[b] = binMesh[original];
        }
        int[] binCount = new int[bins];
        int[] itemBin = new int[items];
        int k = 0;
        for (int g = 0; g < gpuCount; g++) {
            int i = gpu[g];
            for (int s = 0; s < meshes.submeshCount(p.mesh[i]); s++) {
                int b = binIndex[binOfKey.get(binKey(p, i, s))];
                itemBin[k++] = b;
                binCount[b]++;
            }
        }
```

The bins are renumbered in sorted order (`binIndex` maps a bin's first-seen number to its sorted
position), then every item learns its bin and every bin its item count. `Arrays.sort` on a `long[]` is
fine here: no payload is needed, and this is the cold path.

```java
        // Commands and records: per bin, one command per LOD and count records per LOD.
        int[] binLods = new int[bins];
        int[] binFirstCommand = new int[bins];
        int[] binFirstRecord = new int[bins];
        int commands = 0;
        int records = 0;
        int batches = 0;
        int[] slots = new int[bins];
        int[] firsts = new int[bins];
        int[] sizes = new int[bins];
        int[] commandBatch = new int[bins * MAX_LODS];
        for (int b = 0; b < bins; b++) {
            int mesh = sortedMesh[b];
            int lods = Math.min(MAX_LODS, meshes.lodCount(mesh));
            int slot = (int) (sorted[b] >>> 40);
            if (batches == 0 || slots[batches - 1] != slot) {
                slots[batches] = slot;
                firsts[batches] = commands;
                batches++;
            }
            binLods[b] = lods;
            binFirstCommand[b] = commands;
            binFirstRecord[b] = records;
            for (int l = 0; l < lods; l++) {
                commandBatch[commands + l] = batches - 1;
            }
            sizes[batches - 1] += lods;
            commands += lods;
            records += lods * binCount[b];
        }
```

One pass over the sorted bins hands out, in order: each bin's commands (one per LOD, at most 8), its
records (room for every item at every LOD), and its batch — a new batch whenever the slot changes, as in
the CPU builder (§19.4). `firsts[batch]` is the batch's first command, which is also where its packed
commands start in the packed area (a batch can never have more non-empty commands than commands).

**A worked example**, from `StaticSceneTest` (§32.6): a ground plane and an 81-sphere grid, all static,
all one material, each mesh with one LOD. Two bins (sphere and ground, same slot), so 2 commands, 82
records, 1 batch. And the 20,000-quad test: one bin, a quad with 2 LODs, so 2 commands and
$2 \times 20{,}000 = 40{,}000$ records — $40{,}000 \times 64 = 2{,}560{,}000$ bytes per view, five views
12.8 MB, plus $20{,}000 \times 96 = 1{,}920{,}000$ bytes of items.

### 26.6 `rebuild`, part 3: the buffers

```java
        try (Arena a = Arena.ofConfined()) {
            // Items.
            MemorySegment it = a.allocate((long) items * ITEM_BYTES, 16);
            k = 0;
            for (int g = 0; g < gpuCount; g++) {
                int i = gpu[g];
                int mesh = p.mesh[i];
                int w = i * 12;
                float[] world = p.currWorld;
                float scale =
                        (float)
                                Math.sqrt(
                                        world[w] * world[w]
                                                + world[w + 4] * world[w + 4]
                                                + world[w + 8] * world[w + 8]);
                for (int s = 0; s < meshes.submeshCount(mesh); s++) {
                    long o = (long) k * ITEM_BYTES;
                    MemorySegment.copy(world, w, it, ValueLayout.JAVA_FLOAT, o, 12);
                    MemorySegment.copy(p.bounds, i * 4, it, ValueLayout.JAVA_FLOAT, o + 48, 4);
                    it.set(ValueLayout.JAVA_INT, o + 64, i);
                    it.set(ValueLayout.JAVA_INT, o + 68, itemBin[k]);
                    it.set(ValueLayout.JAVA_INT, o + 72, materialOf(p, i, s));
                    it.set(ValueLayout.JAVA_INT, o + 76, p.entity[i]);
                    it.set(ValueLayout.JAVA_FLOAT, o + 80, scale);
                    k++;
                }
            }
            itemBuffer = device.buffers().create(it, 0, "static items");
```

Each item: the 12 floats of the transform (bytes 0–47), the world sphere (48–63), the four ids
(64–79: packet index, bin, material, entity), the scale (80), and 12 unused bytes to make 96, a multiple
of 16 as `std430` requires for the shader's struct. The data is assembled in a **confined arena** — a
temporary native allocation freed when the `try` block ends (L00 §12) — and handed to
`buffers().create(data, 0, …)`: a buffer created **with its contents and no flags**, so the CPU can never
change it again and the driver may put it in the GPU's own memory (L01 §4).

The bins buffer gets, per bin, its LOD count, item count, first record and first command, and 8 LOD
errors (`Float.MAX_VALUE` beyond the mesh's LODs, so no item ever picks them). The command template
gets, per (bin, LOD):

```java
                for (int l = 0; l < binLods[b]; l++) {
                    long c = (long) (binFirstCommand[b] + l) * COMMAND_BYTES;
                    tpl.set(ValueLayout.JAVA_INT, c, meshes.indexCount(mesh, l, submesh));
                    tpl.set(ValueLayout.JAVA_INT, c + 4, 0);
                    tpl.set(ValueLayout.JAVA_INT, c + 8, meshes.firstIndex(mesh, l, submesh));
                    tpl.set(ValueLayout.JAVA_INT, c + 12, meshes.baseVertex(mesh, submesh));
                    tpl.set(ValueLayout.JAVA_INT, c + 16, binFirstRecord[b] + l * binCount[b]);
                }
```

The same five fields as the CPU builder's (§19.4), with `instanceCount` 0 — the cull shader counts it up
— and `baseInstance` pointing at the room reserved for this LOD: the bin's first record plus $l$ times
its item count. The batch table holds `commandBatch` for every command, then `firsts` for every batch.
Finally each of the five views gets its commands buffer
($2 \times 20 \times \text{commands} + 4 \times \text{batches}$ bytes) and its records buffer
($64 \times \text{records}$ bytes), both with no flags and no data: their contents are produced by the
GPU.

### 26.7 `cull`: reset, cull, compact

```java
    public void cull(
            StateCache state,
            int view,
            float[] frustumPlanes,
            float[] eye,
            float lodPixels,
            boolean orthographic,
            int requiredFlags) {
        if (!active() || flagsOffset < 0) {
            return;
        }
        int cull = programs.getBlocking(cullProgram, 0);
        int compact = programs.getBlocking(compactProgram, 0);
        int commands = device.buffers().glName(viewCommands[view]);
        // Reset the counts: the template has no instances; batches have no packed commands.
        glCopyNamedBufferSubData(
                device.buffers().glName(templateBuffer), commands, 0, 0, commandsBytes);
        glClearNamedBufferSubData(
                commands,
                GL_R32UI,
                countsOffsetBytes,
                (long) batchCount * 4,
                GL_RED_INTEGER,
                GL_UNSIGNED_INT,
                MemorySegment.NULL);
```

- `getBlocking` compiles the two compute programs the first time (they have no variants) and returns
  them immediately afterwards.
- **The reset** is two GPU commands: copy the template over this view's commands (every
  `instanceCount` back to 0), and clear the per-batch counts to 0. `glClearNamedBufferSubData` with
  `GL_R32UI` treats the range as 32-bit unsigned integers; a `NULL` data pointer means "clear to zero".
  Both are commands in the stream: they happen after the previous frame's draws read the old values and
  before this frame's shaders run.

```java
        state.useProgram(cull);
        MemorySegment.copy(frustumPlanes, 0, planes, ValueLayout.JAVA_FLOAT, 0, 24);
        glProgramUniform4fv(cull, 0, 6, planes);
        glProgramUniform4f(cull, 6, eye[0], eye[1], eye[2], lodPixels);
        glProgramUniform4ui(
                cull, 7, itemCount, orthographic ? 1 : 0, requiredFlags, Float.floatToIntBits(1f));
        state.bindStorage(
                DrawBuilder.INSTANCE_BINDING,
                device.buffers().glName(viewRecords[view]),
                0,
                (long) recordCount * DrawBuilder.INSTANCE_BYTES);
        state.bindStorage(2, device.buffers().glName(itemBuffer), 0, (long) itemCount * ITEM_BYTES);
        state.bindStorage(
                3, device.buffers().glName(binBuffer), 0, device.buffers().size(binBuffer));
        state.bindStorage(4, commands, 0, device.buffers().size(viewCommands[view]));
        state.bindStorage(5, ring.glName(), flagsOffset, frameBytes);
        state.bindStorage(6, ring.glName(), fadesOffset, frameBytes);
        glDispatchCompute((itemCount + GROUP - 1) / GROUP, 1, 1);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
```

- **Uniforms**: the six planes as six `vec4`s at locations 0–5 (copied through the preallocated
  `planes` segment, since the call takes native memory); the eye and $P$ in one `vec4` at location 6;
  and four unsigned integers at location 7 — the item count, whether the view is orthographic, the
  required flags (0 for the main view, `FLAG_CASTS_SHADOW` for the cascades), and the LOD threshold
  1.0 passed as its float bits (`Float.floatToIntBits`, L00 §7.2), since a `uvec4` can only carry
  integers.
- **Bindings**: the view's records at 0 (the same binding the vertex shaders read), the items, the bins,
  the view's commands, and this frame's flags and fades from the ring.
- **One invocation per item**, in $\lceil \text{items} / 64 \rceil$ workgroups (§9.1), then the first
  barrier (§9.4).

```java
        state.useProgram(compact);
        glProgramUniform4ui(
                compact,
                0,
                commandCount,
                (int) (packedOffsetBytes / 4),
                (int) (countsOffsetBytes / 4),
                commandCount);
        state.bindStorage(
                3, device.buffers().glName(batchBuffer), 0, device.buffers().size(batchBuffer));
        glDispatchCompute((commandCount + GROUP - 1) / GROUP, 1, 1);
        // Command: the draws read these commands and counts as indirect parameters. Storage: later
        // shaders that read this view's buffers as storage. Buffer update: the next cull of this
        // view resets the commands with a copy and a clear, which GL orders after shader writes
        // only with this bit.
        glMemoryBarrier(
                GL_COMMAND_BARRIER_BIT
                        | GL_SHADER_STORAGE_BARRIER_BIT
                        | GL_BUFFER_UPDATE_BARRIER_BIT);
    }
```

The compact shader gets the command count, the packed area's and the counts' positions **in 32-bit
words** (byte offsets divided by 4, because the shader indexes a `uint` array), and where the batch
table's per-batch part starts (after one word per command — the same number, `commandCount`). Binding 3
now holds the batch table instead of the bins. One invocation per command; then the second barrier,
whose three bits §9.4 explained one by one.

### 26.8 The cull shader

[`cull/static_cull.comp`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/cull/static_cull.comp)
declares the structs of §26.2:

```glsl
layout(local_size_x = 64) in;

struct Item {
    vec4 rows[3]; // world transform (row-major 3x4)
    vec4 bounds;  // world bounding sphere
    uvec4 ids;    // x packet index, y bin, z material, w entity
    vec4 extra;   // x scale along the instance's X axis (length of the first column, for LOD)
};

struct Bin {
    uvec4 info;    // x LOD count, y items, z first record, w first command
    vec4 errors[2]; // LOD errors 0..7
};
```

and the buffers at the bindings `cull` used (records `writeonly` at 0, items, bins, commands, flags and
fades), and the uniforms at locations 0, 6 and 7. Its `main`:

```glsl
void main() {
    uint i = gl_GlobalInvocationID.x;
    if (i >= uParams.x) {
        return;
    }
    Item it = items[i];
    uint flags = frameFlags[it.ids.x];
    if ((flags & uParams.z) != uParams.z) {
        return;
    }
    vec4 b = it.bounds;
    for (int p = 0; p < 6; p++) {
        if (dot(uPlanes[p].xyz, b.xyz) + uPlanes[p].w < -b.w) {
            return;
        }
    }
```

- The extra invocations of the last workgroup leave at once (§9.1).
- **This frame's flags**, looked up by packet index (`ids.x`): the required-flags test is
  `(flags & required) == required`, as on the CPU (§13). This is why flags may change without a rebuild.
- **The sphere test of §2.4**, literally: signed distance of the center below minus the radius means
  outside, and the invocation leaves.

```glsl
    Bin bin = bins[it.ids.y];
    vec3 t = vec3(it.rows[0].w, it.rows[1].w, it.rows[2].w);
    float distance = length(t - uEye.xyz);
    float k = uParams.y != 0u ? uEye.w * it.extra.x : uEye.w * it.extra.x / max(distance, 1e-3);
    float threshold = uintBitsToFloat(uParams.w);
    uint lod = 0u;
    for (uint l = 1u; l < bin.info.x; l++) {
        if (bin.errors[l >> 2u][l & 3u] * k <= threshold) {
            lod = l;
        } else {
            break;
        }
    }
```

`DrawBuilder.selectLod` (§17.4) translated: the translation is the fourth component (`.w`) of each row,
$k = P s / d$ or $P s$, and the coarsest acceptable LOD. `bin.errors[l >> 2u][l & 3u]` addresses error
$l$ in two `vec4`s: `l >> 2` is $\lfloor l/4 \rfloor$ (which `vec4`), `l & 3` is $l \bmod 4$ (which
component) — the power-of-two tricks of L00 §6.5. LOD 5 is `errors[1][1]`.

```glsl
    uint command = bin.info.w + lod;
    uint slot = atomicAdd(commands[command * 5u + 1u], 1u);
    uint r = bin.info.z + lod * bin.info.y + slot;
    records[r].rows[0] = it.rows[0];
    records[r].rows[1] = it.rows[1];
    records[r].rows[2] = it.rows[2];
    records[r].data = uvec4(it.ids.z, flags, it.ids.w, floatBitsToUint(frameFades[it.ids.x]));
}
```

The key move. The item's command is the bin's first command plus the LOD. `commands` is a `uint` array,
so command $c$'s `instanceCount` (its second field) is word $5c + 1$. `atomicAdd` on it both **counts**
the instance and gives it a **unique slot** (§9.2). The record goes to the bin's first record, plus
$\text{lod} \times \text{items in bin}$ (that LOD's room), plus the slot — which is exactly
`baseInstance + slot`, where the vertex shader will look for instance number `slot` of that command.
The record has the 64-byte layout of §4.1, with this frame's flags and fade, so **the same vertex shaders
draw both paths**.

A worked example: a bin with 50 items and 2 LODs, first record 1000, first command 7. An item that picks
LOD 1 increments word $5 \times 8 + 1 = 41$ (command 8's `instanceCount`); if it is the fourth item to get
there, `atomicAdd` returns 3 and the record goes to $1000 + 1 \times 50 + 3 = 1053$. Command 8's
`baseInstance` in the template is $1000 + 1 \times 50 = 1050$, so the draw's fourth copy
(`gl_InstanceID` 3) reads record 1053.

### 26.9 The compact shader

Many commands end up with zero instances — LOD 2 of a bin seen up close, or bins entirely outside the
view. A multi-draw would skip them cheaply, but not for free, so
[`cull/static_compact.comp`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/cull/static_compact.comp)
packs each batch's non-empty commands together and counts them:

```glsl
layout(std430, binding = 3) readonly buffer Batches {
    uint batchTable[]; // command -> batch for every command, then each batch's first packed slot
};
layout(std430, binding = 4) buffer Commands {
    uint commands[]; // commands, packed commands, per-batch counts
};

layout(location = 0) uniform uvec4 uParams; // x commands, y packed offset, z counts offset, w batch table offset

void main() {
    uint c = gl_GlobalInvocationID.x;
    if (c >= uParams.x || commands[c * 5u + 1u] == 0u) {
        return;
    }
    uint batch = batchTable[c];
    uint at = batchTable[uParams.w + batch] + atomicAdd(commands[uParams.z + batch], 1u);
    uint dst = uParams.y + at * 5u;
    for (uint k = 0u; k < 5u; k++) {
        commands[dst + k] = commands[c * 5u + k];
    }
}
```

One invocation per command. An empty command (`instanceCount` 0) leaves. A non-empty one finds its
batch, takes the next position in that batch's packed run with `atomicAdd` on the batch's count — the
count and the reservation again — and copies its five words there. Example: a batch whose first packed
position is 4 owns commands with instance counts 5, 0 and 2. Two invocations survive; they receive
positions 4 and 5 (in either order), and the batch's count ends at 2.

### 26.10 `submit`: drawing what the GPU decided

```java
    public void submit(StateCache state, int view, DrawBuilder.BatchBinder binder) {
        if (!active() || flagsOffset < 0) {
            return;
        }
        int commands = device.buffers().glName(viewCommands[view]);
        state.bindVertexArray(meshes.vertexArray());
        state.bindStorage(
                DrawBuilder.INSTANCE_BINDING,
                device.buffers().glName(viewRecords[view]),
                0,
                (long) recordCount * DrawBuilder.INSTANCE_BYTES);
        state.bindStorage(
                DrawBuilder.MATERIAL_BINDING, materials.glBuffer(), 0, materials.bufferBytes());
        state.bindDrawIndirect(commands);
        glBindBuffer(GL_PARAMETER_BUFFER, commands);
        for (int b = 0; b < batchCount; b++) {
            if (!binder.bind(state, batchSlot[b], false)) {
                continue;
            }
            glMultiDrawElementsIndirectCount(
                    GL_TRIANGLES,
                    GL_UNSIGNED_INT,
                    packedOffsetBytes + (long) batchFirst[b] * COMMAND_BYTES,
                    countsOffsetBytes + b * 4L,
                    batchSize[b],
                    COMMAND_BYTES);
        }
        glBindBuffer(GL_PARAMETER_BUFFER, 0);
    }
```

The same vertex array, the view's records at binding 0, the material table at 1 — and the view's
commands buffer bound **twice**: as the draw-indirect buffer (the packed commands) and as the
**parameter buffer** (the counts). Per batch, `glMultiDrawElementsIndirectCount` (§9.3) gets the byte
offset of the batch's packed run, the byte offset of its count, and its total number of commands as the
maximum. The binder is called with `translucent = false`: blended statics never reach the GPU path
(§26.1). The parameter buffer is unbound afterwards, so no later code finds it bound by accident.

`readInstanceCounts(view)` reads every command's `instanceCount` back with `glGetNamedBufferSubData`. It
waits for the GPU and allocates, which is why its Javadoc reserves it for tests and debug views (§32.6).

### 26.11 Where it plugs in

- The **main view** is culled in the frame graph's "static cull" compute pass (§27.2); the depth prepass
  and the opaque pass call `staticScene.submit(…, VIEW_MAIN, …)` right after `draws.submit(…)` with the
  same binders (§27.7).
- The **sun cascades** are culled and drawn inside the "sun shadows" pass: `SunShadows` calls `cull`
  with view `VIEW_CASCADES + c` and required flags `FLAG_CASTS_SHADOW`, then `submit` with the depth
  binder.
- The CPU path's main view **excludes** `FLAG_STATIC` instances and adds back the blended statics from
  `cpuInstances()` (§27.5).

## 27. Step 18: the scene renderer

[`SceneRenderer`](../../modules/render/src/main/java/dev/jeddy/render/SceneRenderer.java) creates the
subsystems, declares the frame graph once, and draws one packet per `render` call. Thread ownership:
the render (main) thread; `render` borrows job workers for culling and draw building.

### 27.1 Configuration

```java
    public record Config(
            long ringBytesPerFrame,
            int maxMaterials,
            SunShadows.Config sunShadows,
            LocalShadows.Config localShadows,
            Gtao.Config ambientOcclusion,
            int decalTextureSize,
            boolean outlines,
            int particleCapacity,
            boolean fogOfWar,
            boolean gpuCulling,
            PostProcess.Config post) {
        /**
         * 32 MiB per frame, 256 materials, shadows, AO, 256² decal textures, outlines, 64k
         * particles, fog of war, GPU culling of static instances, default post.
         */
        public static final Config DEFAULT =
                new Config(
                        32L << 20,
                        256,
                        SunShadows.Config.DEFAULT,
                        LocalShadows.Config.DEFAULT,
                        Gtao.Config.DEFAULT,
                        256,
                        true,
                        1 << 16,
                        true,
                        true,
                        PostProcess.Config.DEFAULT);
```

A record of settings: a `null` sub-config or a size of 0 turns a feature off. `with…` methods return
copies with one field changed (`Config.DEFAULT.withLocalShadows(null)`), which is how tests build
renderers with only the features they test. `32L << 20` is 32 MiB (L00 §4.1).

### 27.2 The constructor

The subsystems come first (condensed: the same pattern repeats for every optional feature):

```java
        this.materials = new MaterialSystem(device, programs, config.maxMaterials());
        this.environment = new Environment(device, programs);
        this.clusters = new LightClusters(device, programs);
        this.ring = new FrameRing(device, config.ringBytesPerFrame(), 3, "scene ring");
        this.culling = new Culling(jobs);
        this.draws = new DrawBuilder(meshes, materials.table(), jobs);
        this.staticScene =
                config.gpuCulling()
                        ? new StaticScene(device, programs, meshes, materials.table())
                        : null;
        this.sunShadows =
                config.sunShadows() == null
                        ? null
                        : new SunShadows(device, meshes, materials, jobs, config.sunShadows());
        if (sunShadows != null) {
            materials.setGlobalFeatures(materials.globalFeatures() | MaterialSystem.SUN_SHADOWS);
            sunShadows.setStaticScene(staticScene);
        }
        // ... local shadows (LOCAL_SHADOWS), an empty vertex array, GTAO (SCREEN_AO), the sky
        // program, post-processing, decal textures (DECALS), outlines, fog of war (FOG_OF_WAR),
        // particles
```

Each enabled feature adds its global bit (§22.1), so every lit variant contains, say, the sun-shadow
code exactly when sun shadows exist. The scene ring is the frame ring of §4.3: 3 regions of
`ringBytesPerFrame`. An **empty vertex array** (one with no attributes) is created for the passes that
draw full-screen triangles from `gl_VertexID` alone (§27.7).

Then the frame graph, declared once:

```java
        graph = new FrameGraph(device);
        hdr = graph.texture("hdr", GL_RGBA16F, 1f);
        depth = graph.texture("depth", GL_DEPTH_COMPONENT32F, 1f);
        clusterItems = graph.importedBuffer("cluster lights");
        sunCascades = graph.importedTexture("sun cascades");
        shadowAtlas = graph.importedTexture("shadow atlas");
        if (sunShadows != null) {
            graph.setTexture(sunCascades, device.textures().glName(sunShadows.texture()));
        }
        if (localShadows != null) {
            graph.setTexture(shadowAtlas, device.textures().glName(localShadows.texture()));
        }
        graph.addComputePass("light cull", this::lightCull).writes(clusterItems);
        if (staticScene != null) {
            int scene = graph.importedBuffer("static scene");
            graph.addComputePass("static cull", this::staticCull).writes(scene);
        }
        graph.addPass("sun shadows", this::sunShadowPass).writes(sunCascades);
        graph.addPass("local shadows", this::localShadowPass).writes(shadowAtlas);
        graph.addPass("depth prepass", this::depthPrepass).depth(depth, 0f);
        aoRaw = graph.texture("ao raw", GL_R8, 0.5f);
        ao = graph.texture("ao", GL_R8, 0.5f);
        if (gtao != null) {
            graph.addPass("ao", this::aoPass).readsTexture(depth).color(aoRaw);
            graph.addPass("ao blur", this::aoBlurPass)
                    .readsTexture(aoRaw)
                    .readsTexture(depth)
                    .color(ao);
        }
        FrameGraph.Pass opaque =
                graph.addPass("opaque", this::opaque)
                        .color(hdr, 0, 0, 0, 1)
                        .depthReadOnly(depth)
                        .readsStorage(clusterItems)
                        .readsTexture(sunCascades)
                        .readsTexture(shadowAtlas);
        if (gtao != null) {
            opaque.readsTexture(ao);
        }
        graph.addPass("sky", this::sky).color(hdr).depthReadOnly(depth);
        FrameGraph.Pass transparent =
                graph.addPass("transparent", this::transparent)
                        .color(hdr)
                        .depthReadOnly(depth)
                        .readsStorage(clusterItems)
                        .readsTexture(sunCascades)
                        .readsTexture(shadowAtlas);
        if (gtao != null) {
            transparent.readsTexture(ao);
        }
        if (particles != null) {
            particles.declare(graph, hdr, depth);
        }
        int overlay = outlines == null ? -1 : outlines.declare(graph, depth);
        post.declare(graph, hdr, overlay);
```

Read it with §8 and §24 in mind:

- **Two full-size transients**: `hdr`, the scene's color in 16-bit floats per channel (high dynamic
  range, chapter [08](08-effects-and-post.md)), and `depth`, 32-bit float depth for reversed-Z (chapter
  [04](04-cameras-and-projection.md)).
- **Three imported resources** owned by subsystems: the light clusters' buffer, the sun cascades and the
  shadow atlas.
- **"light cull"** is a compute pass that writes `clusterItems`; "opaque" and "transparent" read it with
  `readsStorage`, so `compile` gives each of them `GL_SHADER_STORAGE_BARRIER_BIT` (§24.5).
- **"static cull"** writes an imported buffer named "static scene" whose GL name is never set: it is a
  token that keeps the pass live (imported writes always count, §24.4). No pass declares reading it, so
  the graph adds no barrier for it; `StaticScene.cull` issues its own (§26.7).
- **The shadow passes** write their imported textures as render targets inside their own framebuffers —
  graphics writes, which GL orders by itself, so their readers need no barrier.
- **The depth prepass** owns `depth` and clears it to 0, the far plane in reversed-Z.
- **"opaque"** clears `hdr` to black and uses `depth` read-only; "sky" and "transparent" draw over
  `hdr`, testing against the same depth.
- Particles, outlines and post-processing declare their own passes and transients.

### 27.3 The passes, in order

With the default configuration:

| # | Pass | Kind | Declared I/O | Does | Covered in |
|---|---|---|---|---|---|
| 1 | light cull | compute | writes cluster lights | assigns lights and decals to view-space clusters | [07](07-lighting-and-shadows.md) |
| 2 | static cull | compute | writes static scene | GPU culling of static instances for the main view | §26 |
| 3 | sun shadows | graphics (own framebuffers) | writes sun cascades | cascaded shadow maps; culls and draws the static scene's cascade views | [07](07-lighting-and-shadows.md) |
| 4 | local shadows | graphics (own framebuffers) | writes shadow atlas | spot and point light shadows | [07](07-lighting-and-shadows.md) |
| 5 | depth prepass | graphics | depth (cleared to 0) | opaque depth with the depth programs | §27.7 |
| 6 | ao, ao blur | graphics | read depth, write half-resolution `GL_R8` | ambient occlusion (GTAO) | [07](07-lighting-and-shadows.md) |
| 7 | opaque | graphics | hdr (cleared to black), depth read-only, reads clusters, shadows, AO | lit PBR, `GL_GEQUAL` without depth writes (D42) | §27.7, [07](07-lighting-and-shadows.md) |
| 8 | sky | graphics | hdr, depth read-only | the environment where nothing was drawn | §27.7 |
| 9 | transparent | graphics | hdr, depth read-only, reads clusters, shadows, AO | sorted translucent batches | §19.2 |
| 10 | particle emit, simulate, args; particles | compute ×3, graphics | particle pool and lists; hdr | GPU particles | [08](08-effects-and-post.md) |
| 11 | outline mask, outline | graphics | mask, overlay | selection outlines | [08](08-effects-and-post.md) |
| 12 | bloom down 0–5, bloom up 4–0, exposure histogram, exposure average, tonemap, fxaa | graphics and compute | hdr to `BACKBUFFER` | the post chain to the window | [08](08-effects-and-post.md) |

### 27.4 `resize` and `render`

```java
    public void resize(int w, int h) {
        width = w;
        height = h;
        clusters.resize(w, h);
        graph.compile(w, h);
    }
```

A resize recompiles the graph (new transient sizes, framebuffers). `render` throws if it was never
called.

```java
    public void render(RenderPacket p) {
        if (width == 0) {
            throw new IllegalStateException("call resize first");
        }
        long start = System.nanoTime();
        packet = p;
        frameNumber++;
        ring.beginFrame();
        uploadPalettes(p);
        if (visible.length < p.count()) {
            visible = new int[Math.max(p.count(), visible.length * 2)];
        }
        if (staticScene != null) {
            staticScene.update(p, ring);
        }
        int n = cullMainView(p);
        float lod = lodPixels(p);
        draws.setLod(lod, p.orthographic, 1f);
        draws.build(p, visible, n, ring);
        if (outlines != null) {
            outlines.collect(p, ring, lod);
        }
        float elapsed = Float.isNaN(lastTime) ? 0f : Math.clamp(p.time - lastTime, 0f, 1f);
        lastTime = p.time;
        if (particles != null) {
            particles.collect(p, ring, elapsed);
        }
        if (sunShadows != null) {
            sunShadows.update(p, ring, lod);
        }
        materials.flush(ring);
        environment.flush(ring);
        if (fogOfWar != null) {
            fogOfWar.update(p);
        }
        clusters.upload(p, ring);
        if (localShadows != null) {
            localShadows.update(p, ring, clusters.lightsOffset(), lod);
        }
        frameOffset = writeFrameConstants(p);
        post.prepare(ring, p.exposure, elapsed);
        StateCache state = device.state();
        state.bindUniform(FRAME_BINDING, ring.glName(), frameOffset, FRAME_BYTES);
        materials.invalidateBindings();
        prepareNanos = System.nanoTime() - start;
        graph.execute(frameNumber);
        ring.endFrame();
        packet = null;
    }
```

1. **Wait for the ring region** (`beginFrame`); almost always it is free (§4.3).
2. **Palettes** of skinned instances into the ring, bound at storage binding 9 for every pass (§27.5).
3. **The static scene**: rebuild if the packet's `staticVersion` or the material slots changed, then copy
   this frame's flags and fades (§26.3).
4. **Cull and build the main view** on the workers: every instance on the CPU path, or, with the GPU path
   active, only dynamic instances and blended statics (§27.5). The LOD threshold is 1 pixel.
5. **Other views**: outlines, sun cascades and local shadow views build their own draws with their own
   `DrawBuilder`s (and `Culling` for the shadow views) into the same ring; particles copy this frame's
   spawns. `elapsed` is the time since the last frame, clamped to $[0, 1]$ s so a pause in a debugger
   does not make particles jump (and 0 on the first frame, when `lastTime` is still NaN).
6. **Uploads**: material rows (§14.1) and the environment's coefficients through the ring (D103), fog of
   war, lights; the frame constants last, because they include counts the uploads produce (§27.6).
7. **Bind the frame constants** at uniform binding 0 and forget the cached texture bindings (other code
   may have used units 0–4 since last frame).
8. **Execute the graph**: every live pass records its GL work. Nothing waits for the GPU.
9. **Fence the region** (`endFrame`).

`prepareNanos()` reports steps 1–7: the CPU preparation that GPU-driven culling reduces (§34).

### 27.5 Palettes, the main view and the LOD scale

```java
    private void uploadPalettes(RenderPacket p) {
        int n = p.paletteCount;
        if (n == 0) {
            return;
        }
        long bytes = (long) n * RenderPacket.PALETTE_FLOATS * 4;
        long o = ring.allocate(bytes, device.caps().shaderStorageBufferOffsetAlignment());
        if (o < 0) {
            throw new IllegalStateException("scene ring is full; raise Config.ringBytesPerFrame");
        }
        MemorySegment.copy(
                p.palettes,
                0,
                ring.memory(),
                ValueLayout.JAVA_FLOAT,
                o,
                n * RenderPacket.PALETTE_FLOATS);
        device.state().bindStorage(PALETTE_BINDING, ring.glName(), o, bytes);
    }
```

All palettes in one bulk copy (12 floats per joint matrix, §30), aligned for a storage binding. A full
ring here is a configuration error, so it throws with the fix in the message — unlike the draw builder,
which degrades by drawing nothing.

```java
    private int cullMainView(RenderPacket p) {
        boolean gpu = staticScene != null && staticScene.active();
        int n =
                culling.cull(
                        p,
                        p.frustumPlanes,
                        0,
                        gpu ? RenderPacket.FLAG_STATIC : 0,
                        0,
                        0,
                        0,
                        -1,
                        visible);
        if (gpu) {
            int[] cpu = staticScene.cpuInstances();
            float[] b = p.bounds;
            for (int k = 0; k < staticScene.cpuCount(); k++) {
                int i = cpu[k];
                int o = i * 4;
                if (CullOps.sphereVisible(p.frustumPlanes, b[o], b[o + 1], b[o + 2], b[o + 3])) {
                    visible[n++] = i;
                }
            }
        }
        return n;
    }
```

With the GPU path active, the CPU culls everything **except** static instances (excluded flags,
§13) and then appends the blended statics that are in view. Without it, the CPU culls everything.
`lodPixels` is the one line of §6.1.

### 27.6 The frame constants

Everything a shader needs about the frame as a whole — the camera's matrices, the eye, the viewport,
the clustering parameters, the sun, the environment, counts, reveal points and fog — goes into one
**uniform buffer** block, written once per frame into the ring and bound at uniform binding 0
(`FRAME_BINDING`). It mirrors
[`engine/frame.glsl`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/engine/frame.glsl):

```glsl
layout(std140, binding = 0) uniform JeddyFrame {
    mat4 jView;
    mat4 jProjection;
    mat4 jViewProjection;
    mat4 jInverseViewProjection;
    mat4 jInverseProjection;
    vec4 jEye;          // xyz camera position (world), w time in seconds
    vec4 jViewport;     // width, height, 1 / width, 1 / height (pixels)
    vec4 jClusterZ;     // near, far, slice scale, slice bias (view-space depth slicing)
    uvec4 jClusterDims; // clusters along x, y, z; tile size in pixels
    vec4 jSunDirection; // xyz unit direction the sunlight travels, w 0 (cascades: jSunShadowParams.w)
    vec4 jSunColor;     // rgb linear color times illuminance, w 1 when the sun is on
    vec4 jEnvironment;  // x specular scale, y diffuse scale, z prefiltered mip count, w exposure
    uvec4 jCounts;      // x lights, y decals, z frame number, w flags (bit 0 orthographic)
    vec4 jReveal[4];    // camera-fade reveal points: xyz world position, w radius (m)
    vec4 jFogBounds;    // fog-of-war grid: min x, min z, 1 / size x, 1 / size z
    vec4 jFogParams;    // x explored brightness, y explored saturation, z on, w reveal count
};
```

**Why a uniform buffer**: every invocation of every shader reads the same few hundred bytes. Uniform
blocks use the older `std140` layout, and many GPUs serve them through a path built for "everyone reads
the same address". In `std140` a `mat4` is four `vec4` columns, 64 bytes; a `vec4` or `uvec4` is 16
bytes on a 16-byte boundary; an array of `vec4` has a stride of 16. Every member here is one of those,
so the offsets are simply running sums of 64 and 16 — computed, they end at **544 bytes**
(`FRAME_BYTES = 544`):

| Offset | GLSL member | Written from |
|---|---|---|
| 0 | `mat4 jView` | `p.view` |
| 64 | `mat4 jProjection` | `p.projection` |
| 128 | `mat4 jViewProjection` | `p.viewProjection` |
| 192 | `mat4 jInverseViewProjection` | `p.inverseViewProjection` |
| 256 | `mat4 jInverseProjection` | `Mat4Ops.invert(p.projection, …)` |
| 320 | `vec4 jEye` | eye xyz, `p.time` |
| 336 | `vec4 jViewport` | width, height, 1/width, 1/height |
| 352 | `vec4 jClusterZ` | near, far, slice scale, slice bias |
| 368 | `uvec4 jClusterDims` | clusters in x and y, `LightClusters.SLICES` (24), `LightClusters.TILE` (64) |
| 384 | `vec4 jSunDirection` | unit sun direction; w written as 0 |
| 400 | `vec4 jSunColor` | sun color × illuminance; w 1 if the sun is on |
| 416 | `vec4 jEnvironment` | specular scale, diffuse scale, prefiltered mip count, exposure |
| 432 | `uvec4 jCounts` | light count, decal count, frame number, flags (bit 0 orthographic) |
| 448 | `vec4 jReveal[4]` | up to 4 camera-fade reveal points (unused ones $(0, 0, 0, 1)$) |
| 512 | `vec4 jFogBounds` | fog grid min x, min z, 1/size x, 1/size z |
| 528 | `vec4 jFogParams` | explored brightness, explored saturation, on, reveal count |

The writer, in stages:

```java
    private long writeFrameConstants(RenderPacket p) {
        long o =
                ring.allocate(
                        FRAME_BYTES, Math.max(16, device.caps().uniformBufferOffsetAlignment()));
        if (o < 0) {
            throw new IllegalStateException("scene ring is full; raise Config.ringBytesPerFrame");
        }
        MemorySegment m = ring.memory();
        putMatrix(m, o, p.view);
        putMatrix(m, o + 64, p.projection);
        putMatrix(m, o + 128, p.viewProjection);
        putMatrix(m, o + 192, p.inverseViewProjection);
        Mat4Ops.invert(p.projection, 0, scratch, 0);
        putMatrix(m, o + 256, scratch);
        putVec4(m, o + 320, p.eye[0], p.eye[1], p.eye[2], p.time);
        putVec4(m, o + 336, width, height, 1f / width, 1f / height);
```

The block is allocated at the driver's uniform-buffer offset alignment (L01 §9.3). The packet's matrices
are column-major `float[16]` already — the order GLSL's `mat4` expects — so `putMatrix` is one bulk
`MemorySegment.copy`. The inverse projection is computed into a preallocated `scratch` array. (`putVec4`
writes four floats.)

```java
        float near = Math.max(1e-3f, p.near);
        float far = Math.max(near * 2, p.far);
        float logRatio = (float) Math.log(far / near);
        float scale = LightClusters.SLICES / logRatio;
        float bias = (float) (-LightClusters.SLICES * Math.log(near) / logRatio);
        putVec4(m, o + 352, near, far, scale, bias);
```

**The cluster slices.** Chapter [07](07-lighting-and-shadows.md) cuts the view into clusters for light
assignment, with $S = 24$ slices in depth spaced **logarithmically**: near slices thin, far slices thick,
so every slice covers the same *ratio* of distances. The slice of a view depth $z$ between near $n$ and
far $f$ is

$$\text{slice}(z) = S\,\frac{\ln(z/n)}{\ln(f/n)}$$

which is 0 at $z = n$ and $S$ at $z = f$. Since $\ln(z/n) = \ln z - \ln n$,

$$\text{slice}(z) = \ln z \cdot \underbrace{\frac{S}{\ln(f/n)}}_{\text{scale}}\ \underbrace{-\ \frac{S \ln n}{\ln(f/n)}}_{\text{bias}}$$

so the shader computes it with one logarithm, one multiply and one add, and the CPU precomputes the two
constants. With the packet defaults $n = 0.1$ and $f = 200$: $\ln 2000 \approx 7.6009$, scale $\approx
3.1575$, bias $\approx 7.2705$. A fragment 10 units away: $\ln 10 \times 3.1575 + 7.2705 \approx 14.54$,
slice 14 — the same as $24 \ln(100) / \ln(2000)$. The `Math.max` calls keep $n > 0$ and $f > n$, so the
logarithms are defined.

The rest: the cluster grid's size, the sun's direction normalized (a zero vector stays zero instead of
dividing by 0), its color with a "sun on" flag, the environment scales and the prefiltered mip count,
the counts, up to four reveal points (unused ones written as $(0, 0, 0, 1)$), and the fog grid's bounds
and parameters, with $1/\text{size}$ guarded the same way.

### 27.7 The passes' executors

Each pass's work is a private method, registered as a method reference (created once in the
constructor). The ones this chapter is about:

```java
    private void staticCull(PassContext ctx) {
        StateCache state = ctx.state();
        state.bindUniform(FRAME_BINDING, ring.glName(), frameOffset, FRAME_BYTES);
        staticScene.cull(
                state,
                StaticScene.VIEW_MAIN,
                packet.frustumPlanes,
                packet.eye,
                lodPixels(packet),
                packet.orthographic,
                0);
    }

    private final MaterialSystem.DepthUniforms mainViewProjection =
            program -> {
                glProgramUniformMatrix4fv(program, 0, 1, false, matrix);
                glProgramUniform1i(program, 1, 1); // camera fade, like the lit pass
            };

    private void depthPrepass(PassContext ctx) {
        ctx.state().bindUniform(FRAME_BINDING, ring.glName(), frameOffset, FRAME_BYTES);
        MemorySegment.copy(packet.viewProjection, 0, matrix, ValueLayout.JAVA_FLOAT, 0, 16);
        materials.configureDepth(mainViewProjection, 0);
        draws.submit(ctx.state(), ring, materials.depthOnly);
        if (staticScene != null) {
            staticScene.submit(ctx.state(), StaticScene.VIEW_MAIN, materials.depthOnly);
        }
    }

    private void opaque(PassContext ctx) {
        bindLighting(ctx);
        draws.submit(ctx.state(), ring, materials.litOpaque);
        if (staticScene != null) {
            staticScene.submit(ctx.state(), StaticScene.VIEW_MAIN, materials.litOpaque);
        }
    }

    private void transparent(PassContext ctx) {
        bindLighting(ctx);
        draws.submit(ctx.state(), ring, materials.litTranslucent);
    }
```

- **Rebinding the frame constants** in each pass: a pass then does not depend on what earlier passes
  left bound, and the state cache turns a rebind of the same range into no GL call at all.
- **The depth prepass** copies the view-projection into a native 64-byte segment and installs a callback
  that `depthOnly` calls for every batch it accepts; it sets the matrix on that batch's program —
  uniform location 0 — together with
  uniform 1 = 1, which turns on the camera-fade dither so the prepass drops the same pixels as the lit
  pass (D48). The callback is a field, created once.
- **The same built draws** are submitted three times: depth-only, lit opaque, lit translucent — the
  binders choose which batches each pass draws (§20.2). The static scene follows each opaque
  submission.
- **`bindLighting`** binds what the lit passes read: the frame constants, AO, decal textures, fog of
  war, environment, light clusters and shadows — then `invalidateBindings`, because some of those use
  texture units the material sets also use.

The sky draws one triangle that covers the screen:

```java
    private void sky(PassContext ctx) {
        int program = programs.get(skyProgram, 0);
        if (program == 0) {
            return;
        }
        StateCache state = ctx.state();
        state.apply(
                PipelineState.withDepth(
                        PipelineState.FULLSCREEN, true, false, PipelineState.DEPTH_GEQUAL));
        state.useProgram(program);
        state.bindUniform(FRAME_BINDING, ring.glName(), frameOffset, FRAME_BYTES);
        environment.bind(state);
        state.bindVertexArray(emptyVao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }
```

with the vertex shader
[`engine/fullscreen.vert`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/engine/fullscreen.vert):

```glsl
void main() {
    vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    vUv = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
```

For vertices 0, 1, 2: `(id << 1) & 2` is 0, 2, 0 and `id & 2` is 0, 0, 2 (L00 §6), so $p$ is $(0, 0)$,
$(2, 0)$, $(0, 2)$, and the clip positions $2p - 1$ are $(-1, -1)$, $(3, -1)$, $(-1, 3)$: a triangle
twice the size of the screen whose visible part is exactly the screen, with `vUv` running from 0 to 1
across it. No vertex buffer is needed — the empty vertex array — and there is no diagonal seam where two
triangles of a quad would meet (GPUs shade pixels in 2×2 groups, so pixels along a shared edge get shaded
twice). Its depth is $z/w = 0$, which in reversed-Z is the far plane; with the test `GL_GEQUAL` and no
depth writes, the sky passes exactly where the depth buffer still holds its cleared 0 — where nothing was
drawn.

`close` releases everything in reverse dependency order: the graph, post, the optional features, the
empty vertex array, the ring, the shadows, the clusters, the environment, the materials, the arena.

# Part C — Using it and proving it

## 28. WorldRenderer: the whole stack

[`WorldRenderer`](../../modules/app/src/main/java/dev/jeddy/app/world/WorldRenderer.java), in the `app`
module, is the `FrameRenderer` that a game built on `World` uses (TASKS M10-02, DESIGN §13.2). Its
`init`, on the main thread once the window exists, creates everything in dependency order (two logging
branches — a warning on Intel GPUs under Windows, and switching on the GL debug output — condensed into
a comment):

```java
    public void init(MainContext ctx) {
        caps = GLLoader.init(ctx.window()::glProcAddress);
        LOG.info("GL " + caps.version() + " on " + caps.renderer());
        // ... Intel-on-Windows warning; GLDebug.enable when -Djeddy.gl.debug=true
        device = new GpuDevice(caps);
        device.useReversedZ();
        upload = new UploadRing(device, 64L << 20, 32L << 20);
        registry = new MeshRegistry(device, upload, config.meshVertices, config.meshIndices);
        programs = new ProgramCache(EngineShaders.sources(), caps, binaryCache());
        renderer = new SceneRenderer(device, programs, registry, ctx.jobs(), config.scene);
        assets = new AssetManager(vfs, ctx.jobs());
        materials = new MaterialAssets(assets, device, renderer.materials());
        meshes = new MeshAssets(assets, registry, materials);
        debug = new DebugDraw(ctx.jobs().threadSlots(), config.debugLinesPerThread);
        overlayRing = new FrameRing(device, 4L << 20, 3, "overlay ring");
        lines = new DebugRenderer(device);
        overlay = new Overlay(device, config.overlayGlyphs);
        uiRing = new FrameRing(device, config.uiRingBytes, 3, "ui ring");
        uiRenderer = new UiRenderer(device, uiRing);
        if (config.environment != null) {
            environment = materials.textures().load(config.environment);
        }
    }
```

The order follows the dependencies: the GL functions, then the device, then the staging ring the mesh
registry uploads through, the program cache (with the on-disk binary cache of D73), the scene renderer
(which creates the material system), the asset manager and the asset types that feed the registry and
the materials, then the debug tools and the user interface, each with a frame ring of its own. The
defaults: room for $2^{21}$ vertices and $2^{22}$ indices in the mega buffers (they grow, §12.6), a 4 ms
per-frame time budget for asset uploads, 16,384 debug lines per thread, 8192 overlay glyphs, and
`SceneRenderer.Config.DEFAULT`.

Its `render`, once per frame:

```java
    public void render(MainContext ctx, int index, boolean fresh) {
        long start = System.nanoTime();
        int w = ctx.window().pixelWidth();
        int h = ctx.window().pixelHeight();
        if (w != width || h != height) {
            width = w;
            height = h;
            if (w > 0 && h > 0) {
                renderer.resize(w, h);
            }
        }
        upload.beginFrame();
        assets.pumpUploads(config.uploadBudgetNanos);
        materials.update();
        meshes.update();
        upload.endFrame();
        // ... apply the environment cube once it has loaded
        RenderPacket p = packets.packet(index);
        if (w > 0 && h > 0) {
            renderer.render(p);
            // The UI goes over the finished display image, before the debug overlay (DESIGN §18).
            uiRing.beginFrame();
            uiRenderer.draw(
                    device.state(),
                    uiRing,
                    p.ui,
                    renderer.materials().arrays(),
                    w,
                    h,
                    ctx.window().displayScale());
            uiRing.endFrame();
            overlayRing.beginFrame();
            lines.draw(p, device.state(), overlayRing);
            if (config.hud != null) {
                config.hud.draw(overlay, p, index, w, h);
            }
            overlay.draw(device.state(), overlayRing, w, h);
            overlayRing.endFrame();
        }
        lastFrameNanos = System.nanoTime() - start;
    }
```

- **A resize** (the window's size in pixels changed) recompiles the frame graph; a minimized window
  (0×0) draws nothing.
- **Asset uploads** run between `beginFrame` and `endFrame` of the upload ring, within a time budget, and
  then the material and mesh asset types wire what arrived (§12.9, §22.3).
- **`index`** is the triple-buffer slot of the newest packet the game thread published (chapter
  [01](01-architecture.md)); `JeddyApp` swaps the window after `render` returns.
- **After the scene**: the user interface (chapter [13](13-ui.md)), the debug lines and the HUD overlay,
  each in its own ring.

`shutdown` closes everything in reverse order, ending with the device.

## 29. One frame on the main thread

```
JeddyApp main loop: pump SDL events, drain the game's command queue, acquire the newest packet
│
└─ WorldRenderer.render(ctx, index, fresh)
   ├─ pixel size changed?  → SceneRenderer.resize: LightClusters.resize, FrameGraph.compile
   ├─ UploadRing.beginFrame · AssetManager.pumpUploads(budget) · MaterialAssets.update
   │  · MeshAssets.update · UploadRing.endFrame
   ├─ SceneRenderer.render(packet)
   │  │  ┌──────────── CPU preparation, measured by prepareNanos ─────────────────────────┐
   │  ├─ │ FrameRing.beginFrame: fence of frame N-3                                       │
   │  ├─ │ uploadPalettes: storage binding 9                                              │
   │  ├─ │ StaticScene.update: rebuild if staticVersion changed; copy flags and fades     │
   │  ├─ │ cullMainView: Culling.cull on workers                                          │
   │  ├─ │ DrawBuilder.build on workers: items and keys, radix sort, records, commands    │
   │  ├─ │ Outlines.collect, GpuParticles.collect, SunShadows.update                      │
   │  ├─ │ MaterialSystem.flush, Environment.flush, FogOfWar.update,                      │
   │  │  │ LightClusters.upload, LocalShadows.update                                      │
   │  ├─ │ writeFrameConstants: uniform binding 0 · PostProcess.prepare                   │
   │  │  └────────────────────────────────────────────────────────────────────────────────┘
   │  ├─ FrameGraph.execute — GPU commands recorded by the live passes:
   │  │    light cull (compute) → static cull (compute) → sun shadows → local shadows
   │  │    → depth prepass → ao, ao blur → opaque → sky → transparent
   │  │    → particles (3 compute + draw) → outline mask, outline
   │  │    → bloom, exposure, tonemap, fxaa: to the window
   │  └─ FrameRing.endFrame: fence
   ├─ ui ring: UiRenderer.draw
   └─ overlay ring: DebugRenderer.draw, HUD, Overlay.draw
Window.swap: SDL_GL_SwapWindow
```

Everything above the graph's execution is CPU work, much of it on the job workers; everything inside it
is GL calls recording commands. Nothing waits for the GPU except a ring's `beginFrame` when the GPU is
three frames behind (§4.3), and the swap when the driver's queue is full (L00 §10.6).

## 30. Skinned instances

Animated characters change the path in three small places (D80; chapter
[09](09-animation-and-skinning.md) has the whole story):

- **The packet** carries skinning **palettes** — 12 floats per joint matrix, appended with `addPalette`
  — and each skinned instance stores its palette base + 1 in flag bits 12–31 (`setPalette`, §10.4,
  which also clears `FLAG_STATIC`). `SceneRenderer.uploadPalettes` copies all palettes into the ring
  and binds them at storage binding 9 (`PALETTE_BINDING`) for every pass (§27.5).
- **Skinned meshes draw with the `SKINNED` variant** (feature bit 15) of their material's program slot.
  `DrawBuilder.build` prepares those slots on the main thread before the workers run (§16), and the
  item kernel picks `materials.skinnedSlot(slot)` (§17.3). The variant is its own slot, so skinned and
  unskinned draws never share a batch.
- **In the shaders**, `#ifdef SKINNED` includes
  [`engine/skinning.glsl`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/engine/skinning.glsl):

  ```glsl
  layout(location = 4) in uvec4 jeddyJoints;
  layout(location = 5) in vec4 jeddyWeights;

  layout(std430, binding = 9) readonly buffer JeddyPalettes {
      vec4 jeddyPaletteRows[];
  };
  ```

  `jeddySkin(inst.data.y)` reads the palette base from the instance flags (`>> 12u`, the shift of §10.4;
  0 means "no palette", which gives the identity), blends the four palette matrices named by the vertex's
  joints with its weights — the skin stream, attributes 4 and 5 of the vertex array (§12.3) — and
  `pbr.vert` applies the result before the instance transform:

  ```glsl
  #ifdef SKINNED
      JeddySkin skin = jeddySkin(inst.data.y);
      position = jeddySkinPoint(skin, position);
      n = jeddySkinDirection(skin, n);
      t = jeddySkinDirection(skin, t);
  #endif
  ```

The 64-byte record and the static scene stay unchanged: the palette reaches the shader through flag
bits the record already carries, and skinned instances are never static.

## 31. Debug drawing and overlays

- **[`DebugDraw`](../../modules/render/src/main/java/dev/jeddy/render/debug/DebugDraw.java)** gives every
  thread its own line buffer, indexed by the job system's thread index, so physics, navigation and
  gameplay can draw lines and shapes without locks; the game thread merges them into
  `packet.debugLines` at extract time (DESIGN §9.5). A full buffer drops lines and counts them.
- **[`DebugRenderer`](../../modules/render/src/main/java/dev/jeddy/render/debug/DebugRenderer.java)**
  draws those lines after the scene. It writes two 16-byte vertices per line (position, plus the packed
  color stored as the bits of the fourth float) into the overlay ring and draws `GL_LINES` with an empty
  vertex array; the vertex shader fetches its vertex from a storage buffer by `gl_VertexID` — the same
  "pull" idea as the instance records (§4.2). Lines before `debugOverlayStart` are meant to be
  depth-tested against the scene and the rest (an editor's gizmo) drawn on top — but the lines are drawn
  into the window's framebuffer, which has no depth buffer (D23), so in practice every line shows through
  walls; chapter [08](08-effects-and-post.md), on debug drawing, explains the gap.
- **[`Overlay`](../../modules/render/src/main/java/dev/jeddy/render/debug/Overlay.java)** draws screen
  text and rectangles as **one instanced draw**: each glyph is a quad whose record sits in a storage
  buffer, and the fragment shader tests the glyph's bit in a 5×7 font table that is itself a storage
  buffer — no font texture and no asset dependency, so it works before anything is loaded (D31).
- **[`ProfilerOverlay`](../../modules/render/src/main/java/dev/jeddy/render/debug/ProfilerOverlay.java)**
  shows the main and game threads' frame times and the GPU time of every live frame-graph pass
  (`FrameGraph.passMillis`, §25) as numbers and bars against a frame budget. In the demo, F3 toggles it.

## 32. The tests, walked through

### 32.1 How the GPU tests run

Tests that need a GL context are tagged `@Tag("gpu")`. `./gradlew test` skips them;
`-Pjeddy.gpuTests=true` adds them and `=only` runs only them (DESIGN §9.7). CI runs them under Xvfb on
Mesa **llvmpipe**, a software implementation of GL: it catches wrong API use, wrong layouts and wrong
results, but not performance or a particular vendor's bugs — those need real GPUs (L01 §17).
`DrawBuilderTest`, `MeshRegistryTest` and `FrameGraphTest`'s compute-indirect test also record the
driver's error count at the start (`GLDebug.errorCount()`) and assert it has not grown at the end, so a GL
error anywhere in them fails the test. `StaticSceneTest` does not check it, and in `SceneRendererTest`
only `whiteFurnaceReflectsTheEnvironment` does.

### 32.2 The allocator and the sorts (no GPU)

- **`RangeAllocatorTest.randomChurnNeverOverlapsAndCoalescesBack`**: 20,000 random steps on a space of
  100,000 — two in three allocate (sizes 1 to 500, alignments 1 to 16), one in three frees a random
  live range — checked against a `BitSet` of used units:
  no allocation ever overlaps a used unit, every result is aligned, and `free()` always equals the space
  minus the used units. At the end everything is freed and the free list must be **one** range of
  100,000 — the merging of §11.4 at work.
- **`RangeAllocatorTest.growsAndDetectsMisuse`**: a full space returns −1, `grow` makes room exactly at
  the old end (§11.5), and a double free, an overlapping free and a free outside the space all throw.
- **`CollectionsTest.radixSortIsStableAndUnsigned`**: 20,000 keys with many duplicates and some negative
  (`r.nextLong()`), sorted by `LongSort` and by a stable `List.sort` with `Long.compareUnsigned`: keys
  and payloads must match — unsigned order and stability (§5.3–5.4). **`radixSortSkipsUniformBytes`**
  checks small cases and an array of equal keys.
- **`ParallelSortTest.matchesTheSequentialSortAndIsStable`**: sizes 0, 1 and 100 (below the threshold
  of 16,384, so the sequential path) and 20,000 and 100,003 (the parallel path, on 3 workers); keys with
  few distinct high bytes, random low bits, duplicates and the sign bit set on half of them. The parallel result must equal the sequential one, keys and payloads, and be in
  unsigned order; then 50,000 keys are sorted repeatedly under `Allocations.assertNoAllocation`.

These need no GPU and run on any machine; when this chapter was written, the 18 tests of these three
classes passed.

### 32.3 `MeshRegistryTest`

`meshesShareTheMegaBuffersAndDrawByBaseVertex` builds the registry with 6 vertex and 8 index slots and
adds two quads, which forces both spaces to grow (§12.8). It asserts the base vertices 4 apart, two
LODs, LOD 1's 3 indices starting 6 after LOD 0's, and the bounding sphere. Then it draws with plain
`glDrawElementsBaseVertex` — the left quad at LOD 0 and the right quad at LOD 1, a single triangle —

```java
            glDrawElementsBaseVertex(
                    GL_TRIANGLES,
                    registry.indexCount(left, 0, 0),
                    GL_UNSIGNED_INT,
                    4L * registry.firstIndex(left, 0, 0),
                    registry.baseVertex(left, 0));
```

and reads pixels back: yellow in the left quad, yellow in the right quad's lower-right triangle, and
black in its upper-left half, which LOD 1 does not cover. Finally the left quad is removed and a new one
lands at base vertex 0: the freed range is reused.

### 32.4 `DrawBuilderTest`: 20,000 instances, two draws

[`DrawBuilderTest`](../../modules/render/src/test/java/dev/jeddy/render/draw/DrawBuilderTest.java)
builds `splitQuad`, a unit quad with two submeshes (left and right half) and two LODs (LOD 1 has error
0.5 and one triangle per half), and a program `flat` whose fragment shader reads the material row and,
in its `HALF` variant, halves the color:

```glsl
#version 460 core
layout(std430, binding = 1) readonly buffer Materials { vec4 rows[]; };
layout(location = 0) flat in uint material;
layout(location = 0) out vec4 color;
void main() {
    color = rows[material * 8u];
#ifdef HALF
    color.rgb *= 0.5;
#endif
}
```

`rows[material * 8u]` because a material row is 8 `vec4`s (§14.2); the first is the base color. The red
material uses mask 0 and the green one mask 1 (`HALF`), so they get program slots 0 and 1. The packet
holds a 200 × 100 grid of instances — columns 0–99 red, 100–199 green — in an orthographic view
$[-100, 100]^2$ on a 128-pixel target, plus 100 instances far outside it. Follow the numbers:

1. `Culling` keeps the **20,000** grid instances (the test asserts it).
2. Each has 2 submeshes: **40,000 items**.
3. LOD: orthographic, $P = 128 / 200 = 0.64$ pixels per unit, so LOD 1 projects to
   $0.5 \times 0.64 = 0.32$ pixels $\le 1$: every item is LOD 1 (§6.3).
4. The keys differ above the depth bits only in slot (2 values) and submesh (2 values): **4 runs** of
   10,000 items, so 4 commands with `instanceCount` 10,000 (§19.4). Only bytes 0, 1, 2 and 6 of the keys
   vary, so the radix sort runs 4 of its 8 passes (§5.4).
5. Two slots: **2 batches**, two `glMultiDrawElementsIndirect` calls of 2 commands each.

The test checks exactly that, then reads a pixel on each side:

```java
            assertEquals(40_000, builder.itemCount(), "20k visible instances x 2 submeshes");
            assertEquals(2, builder.batchCount(), "one multi-draw per program variant");
            assertTrue(
                    builder.commandCount() <= 4,
                    "runs per mesh, LOD, submesh: " + builder.commandCount());
            assertEquals(20_000, culling.cull(packet, visible));
```

Red reads 255 on the left; green reads $128 \pm 1$ on the right, because $0.5 \times 255 = 127.5$ is
rounded to an 8-bit value. Then it prints the cull + build time of 20 frames and proves the whole frame
— cull, build, submit — allocates nothing:

```java
            Allocations.assertNoAllocation("cull, build, submit", 10, 20, frame);
```

### 32.5 `FrameGraphTest`

`computeIndirectSceneAndPostRunWithBarriersAndTimers` declares the four passes of §24.3 — a compute pass
that writes indirect arguments `(3, 1, 0, 0)` (draw 3 vertices once), a scene pass that draws a red
triangle with `glDrawArraysIndirect` over a blue clear, an unused pass, and a post pass that copies the
scene to the window — and asserts:

- **3 live passes**, and pass 2 ("unused") not live — the liveness of §8.2; its executor throws if it
  ever runs;
- after 12 frames, the triangle's red, the cleared blue, a debug line and an overlay rectangle are on
  screen, read back as pixels;
- `graph.barriers() >= 12`: one `GL_COMMAND_BARRIER_BIT` per frame before the scene pass (§8.3);
- the GPU timers returned results (their sum is above 0, §25);
- a frame — graph, debug lines, overlay and the profiler overlay — allocates nothing.

`readingBeforeWritingIsAnError` declares a pass that samples a transient before the pass that writes it,
and checks that `compile` throws with "before any pass writes it" (§24.5).

### 32.6 `StaticSceneTest`

[`StaticSceneTest`](../../modules/render/src/test/java/dev/jeddy/render/cull/StaticSceneTest.java) runs a
full `SceneRenderer` (without local shadows, AO, particles and most post) on a 96-pixel target:

- **`gpuCulledStaticInstancesLookLikeTheCpuPath`**: a ground and a 9×9 grid of spheres are rendered once
  as dynamic instances (the reference) and once as static ones. The images must agree within $2 \times
  10^{-3}$ per channel, shadows included. The scene reports 82 static instances, 82 items and 2 commands
  (§26.5). Then the decisive check: the sum of `readInstanceCounts(VIEW_MAIN)` — what the GPU culled as
  visible — equals the number of spheres `CullOps.sphereVisible` accepts on the CPU, and is below 82.
  The first cascade view has casters too.
- **`farInstancesUseCoarserLods`**: a 0.5 m quad whose LOD 1 has error 0.05, once 3 units in front of
  the camera and once 43. With a 40° field of view on 96 pixels, $P = 96 / (2 \tan 20°) \approx 131.9$.
  Near: $k = 131.9 / 3 \approx 44.0$, LOD 1 would be off by $0.05 \times 44.0 \approx 2.2$ pixels, so LOD
  0. Far: $k \approx 3.07$, $0.05 \times 3.07 \approx 0.15$ pixels, so LOD 1. The test asserts one instance
  in each LOD's command (§26.8).
- **`blendedStaticInstancesStayOnTheCpuPath`**: a glass sphere added to the static grid ends up in
  `cpuInstances()`, and the scene counts 83 statics.
- **`flagsAndFadeFollowThePacketWithoutRebuilding`**: fading out every sphere changes the image but not
  the command count — no rebuild (§26.3).
- **`rebuildsWhenTheStaticVersionChanges`**: a new version with only the ground leaves 1 item.
- **`staticFramesAllocateNothing`**: a frame with static and dynamic instances allocates nothing.
- **`gpuCullingCutsMainThreadCpuTimeOnTwentyThousandInstances`**: 20,000 static quads, rendered with the
  GPU path and again with the static flag cleared; the median `prepareNanos` must drop by at least 30 %
  (the M7-11 acceptance criterion). It prints both, and the main thread's CPU time including the
  driver.

### 32.7 `SceneRendererTest` and `RenderPacketTest`

`SceneRendererTest.baseColorTexturesComeFromArrayLayers` allocates a 4×4 `GL_RGBA8` layer, fills it with
green, gives it to a material as its base-color texture, checks that the material gained
`FEATURE_BASE_COLOR_MAP`, renders a sphere and finds it green — the whole chain of §22–23. Then a second
4×4 texture lands in the **same bucket** (and so the same texture set). `framesAllocateNothing` renders 9
spheres and 64 point lights and proves a whole renderer frame allocates nothing.

`RenderPacketTest` covers the packet itself: interpolation between ticks, palettes in the high flag bits
(and that skinned instances lose `FLAG_STATIC`), a game thread publishing 20,000 packets through the
triple buffer while the main thread reads the newest ones and checks that none of them mixes two frames,
and that filling a packet allocates nothing.

## 33. Things to run

```bash
# CPU tests (no GPU needed)
./gradlew :core:test --tests 'dev.jeddy.core.memory.RangeAllocatorTest'
./gradlew :core:test --tests 'dev.jeddy.core.job.ParallelSortTest'
./gradlew :core:test --tests 'dev.jeddy.core.collections.CollectionsTest'
./gradlew :render:test --tests 'dev.jeddy.render.RenderPacketTest'

# GPU tests
./gradlew :render:test -Pjeddy.gpuTests=true --tests 'dev.jeddy.render.mesh.MeshRegistryTest'
./gradlew :render:test -Pjeddy.gpuTests=true --tests 'dev.jeddy.render.draw.DrawBuilderTest'
./gradlew :render:test -Pjeddy.gpuTests=true --tests 'dev.jeddy.render.graph.FrameGraphTest'
./gradlew :render:test -Pjeddy.gpuTests=true --tests 'dev.jeddy.render.cull.StaticSceneTest'
./gradlew :render:test -Pjeddy.gpuTests=true --tests 'dev.jeddy.render.pbr.SceneRendererTest'

# Benchmarks
./gradlew :bench:jmh -Pjmh.includes=DrawBench
./gradlew :bench:jmh -Pjmh.includes=CollectionsBench
./gradlew :bench:jmh -Pjmh.includes=TextureBindingBench

# The demo, with the driver's messages in the log; F3 shows the GPU time of every pass
./gradlew :iso-demo:run "-Pjeddy.jvmArgs=-Djeddy.gl.debug=true"
```

On Windows use `gradlew.bat` and double quotes ([docs/running.md](../running.md)). `DrawBuilderTest`
and `StaticSceneTest` print their timings on the console. `CollectionsBench.radixSort16k` against
`arraysSort16k` compares the radix sort with `Arrays.sort` on 16,384 keys (§5.5). Under a GPU debugger
such as RenderDoc, a captured frame shows every frame-graph pass as a named group (§24.8) and every
multi-draw with its commands.

## 34. The numbers

Measured so far, with their sources:

| What | Result | Where measured | Source |
|---|---|---|---|
| Cull + build for 20k instances (`DrawBuilderTest`) | ≈ 1.7–2.6 ms | 2-core sandbox | TASKS M5-08 |
| `DrawBench.cullAndBuild`, 20k instances | 1.7 ms (warm) | 2-core sandbox, llvmpipe | `bench/README.md` |
| `DrawBench.cullBuildSubmit`, 20k instances | 8.6 ms (llvmpipe does the vertex work on the calling thread) | 2-core sandbox, llvmpipe | `bench/README.md` |
| Frame preparation, 20k static instances: CPU path → GPU path | 2.2 ms → 0.33 ms (85 % less) | `StaticSceneTest`, llvmpipe | TASKS M7-11 |
| `DrawBench.gpuPrepare` vs `cullAndBuild` | 9 µs vs 2.1 ms | llvmpipe | TASKS M7-11 |
| 256 materials: rebind per draw / texture array / bindless | 3525 / 2613 µs / unsupported | Mesa llvmpipe 25.2 | ADR 0001 |

And the targets they are measured against: the M5-08 acceptance criterion is ≤ 2 ms of main-thread
submission for 20k instances with 0 B per frame; DESIGN's budget for the main thread's CPU with 20k
visible instances is ≤ 5 ms on the reference PC (an 8-core desktop CPU with a GTX 1660 / RX 6600 class
GPU at 1080p). The 2 ms criterion still has to be confirmed on that PC (M10).

Two caveats. On llvmpipe the "GPU" compute runs on the CPU **inside** the GL calls, so the GPU path's
saving is measured on the renderer's own preparation (`prepareNanos`), and the GPU side of GPU culling
must still be confirmed on real hardware (D49, M10). And the texture-binding rows for NVIDIA, AMD and
Intel are still empty, which is why ADR 0001 is still *Proposed* (TASKS M5-11).

## 35. Why it is built this way

| Choice | Instead of | Why | Decision |
|---|---|---|---|
| Mega buffers, one vertex array | a vertex array and buffers per mesh | draws differ only in numbers, which makes multi-draw possible | DESIGN §9.2 |
| Positions in their own stream | one interleaved stream | depth-only passes fetch 12 bytes per vertex | D27 |
| First-fit free list over a sorted array, merging neighbours | a buddy allocator; best fit; a bump allocator | no power-of-two waste, reuse of holes, simple; allocation is a load-time cost | DESIGN §9.2 (a free list) |
| Records per item, in sorted order | records per instance plus an index buffer | each command's `baseInstance` addresses a contiguous run; no extra indirection in the vertex shader | D30 |
| 64-bit packed keys, LSD radix sort, parallel | comparator sorts of objects | $O(n)$, stable, parallel, allocation-free; one `long` compare orders by every field | DESIGN §9.2 |
| Instance data pulled from a storage buffer | instanced vertex attributes (divisors) | any record layout; the same records for every pass and for GPU culling | DESIGN §9.2 |
| Opaque runs merge, translucent items never do | merging translucent runs | blending needs exact back-to-front order | DESIGN §9.2 |
| Texture arrays bucketed by (format, size, levels) | bindless handles | core GL on every driver and in CI; bindless left as an optional fast path pending measurements | D41, ADR 0001 |
| Texture set inside the program slot | binding textures per material | draws of a batch share bindings; a texture arriving later moves its material | D41 |
| Material buffer bound whole, changed through the ring | `glNamedBufferSubData` and a range of the rows in use | correct on the driver that dropped CPU writes into a buffer in flight | D103 |
| Frame graph compiled per resize, one texture per transient | hand-ordered passes; transient aliasing | automatic targets, clears and barriers; zero allocation per frame; aliasing waits for memory pressure | D32 |
| GPU culling for statics only, with a version contract | everything GPU-driven | statics are most instances; dynamic ones change every frame anyway and blended ones need sorting | D49 |
| Room for every item at every LOD in the static scene | a prefix-sum pass to size each command | one dispatch writes records directly; costs memory (§26.2) | (the code's design; no decision record) |
| `GL_GEQUAL` after the prepass | `GL_EQUAL` | no invariance guarantee between two programs | D42 |
| GPU timers read 3 frames late | reading at once | never stalls the CPU | DESIGN §9.6 |

## 36. Further reading

- Cass Everitt, Graham Sellers, John McDonald and Tim Foley, "Approaching Zero Driver Overhead in
  OpenGL", GDC 2014: the source of the mega-buffer, persistent-mapping and multi-draw-indirect ideas
  used here.
- *Real-Time Rendering*, 4th edition (Akenine-Möller et al.), chapter 18 "Pipeline Optimization"
  (finding the bottleneck, batching, reducing state changes) and chapter 19 "Acceleration
  Algorithms" (culling, level of detail).
- Christer Ericson, "Order your graphics draw calls around!", realtimecollisiondetection.net blog
  (2008): sort keys as packed integers.
- Yuriy O'Donnell, "FrameGraph: Extensible Rendering Architecture in Frostbite", GDC 2017: the
  frame graph idea, including the transient aliasing Jeddy's v1 does not do yet.
- Ulrich Haar and Sebastian Aaltonen, "GPU-Driven Rendering Pipelines", SIGGRAPH 2015 Advances in
  Real-Time Rendering course: GPU culling and indirect draws at scale.
- *The OpenGL 4.6 Core Profile Specification*,
  [glspec46.core.pdf](https://registry.khronos.org/OpenGL/specs/gl/glspec46.core.pdf): indirect
  commands and `MultiDrawElementsIndirectCount` in the drawing-commands chapter; memory barriers in
  the shader memory access section.
- LearnOpenGL, [Instancing](https://learnopengl.com/Advanced-OpenGL/Instancing) (the attribute-divisor
  approach Jeddy replaces with storage buffers) and
  [Advanced Data](https://learnopengl.com/Advanced-OpenGL/Advanced-Data).
- Jason Gregory, *Game Engine Architecture*, the rendering engine chapter.

## 37. Exercises

Each has a hint and a full solution; try before you open them. Exercises 2–5 change
`DrawBuilderTest`, 6 `FrameGraphTest` and 8 `StaticSceneTest`; run them with the commands of §33 and
undo the changes afterwards.

**1. Keys by hand.** Write the 64-bit key, in hexadecimal, of an opaque item with program slot 5, mesh
index 300, LOD 2, submesh 1 at distance 3.0, and of the same submesh as a translucent item (§5.2).

<details><summary>Hint</summary>

Opaque depth: $3 \times 64 = 192$. Mesh 300 in hex is `0x12C`. Write the fields in their digit groups:
1 | 3 | 5 | 1 | 2 | 4 hex digits for pass, slot, mesh, LOD, submesh, depth.
</details>
<details><summary>Solution</summary>

Opaque: pass 0, slot `005`, mesh `0012C`, LOD `2`, submesh `01`, depth $192 = $ `00C0`:
`0x00050012C20100C0`. Translucent: $3 \times 4096 = 12288 = $ `0x3000`, so the depth field is
$\text{0xFFFFFFFF} - \text{0x3000} = $ `0xFFFFCFFF`; pass `1`, depth `FFFFCFFF`, slot `005`, submesh
`01`, zero `00`: `0x1FFFFCFFF0050100`.
</details>

**2. One program, one batch.** In `DrawBuilderTest`, give the green material mask 0 like the red one.
Predict `programSlots()`, `batchCount()`, `commandCount()` and the green pixel, update the assertions,
and run the test.

<details><summary>Hint</summary>

Same definition, same mask, same texture set: the same program slot
(§14.3). What is then left in the key above the depth to tell items apart?
</details>
<details><summary>Solution</summary>

Both materials now map to slot 0, so `programSlots()` is 1 and there is **1 batch**. Above the depth
bits, the keys differ only in the submesh: **2 runs** of 20,000 items each, so 2 commands with
`instanceCount` 20,000. Green is no longer halved, so its pixel reads 255. The changes:

```java
int green = materials.add(flat, 0, row);            // was mask 1
assertEquals(1, materials.programSlots());          // was 2
for (int slot = 0; slot < 1; slot++) {              // was slot < 2
// ...
assertEquals(1, builder.batchCount(), "one multi-draw for one program");
assertEquals(2, builder.commandCount(), "one run per submesh");
// ...
assertEquals(255, Byte.toUnsignedInt(px.get(ValueLayout.JAVA_BYTE, 1)), "full green");
```

The two materials still have different rows (red and green), which is the point of §14: one batch, two
colors, chosen per instance by `data.x`.
</details>

**3. Reorder the key and watch batches split.** Change the opaque key to pass (4) | submesh (8) |
program slot (12) | mesh (20) | LOD (4) | depth (16), adapt `slotOf`, and predict `batchCount()` in
`DrawBuilderTest`. Then restore the original layout.

<details><summary>Hint</summary>

The slot moves to bits 40–51. Write the sorted order of the four
(submesh, slot) combinations and count how often the slot changes from one run to the next.
</details>
<details><summary>Solution</summary>

In `buildItems`:

```java
key =
        (long) PASS_OPAQUE << 60
                | (long) s << 52
                | (long) slot << 40
                | (long) (Handles.index(mesh) & 0xFFFFF) << 20
                | (long) lod << 16
                | depth;
```

and in `slotOf`, `(key >>> 40) & 0xFFF` for opaque keys. `sameDraw` still works, because the depth is
still the low 16 bits. The sorted runs are now (submesh 0, slot 0), (submesh 0, slot 1), (submesh 1,
slot 0), (submesh 1, slot 1): the slot changes at every run, so there are **4 batches** of one command
each, and `assertEquals(2, builder.batchCount())` fails with 4. That is why the slot sits directly below
the pass: every opaque slot then forms exactly one batch (§19.4).
</details>

**4. Translucency costs commands.** Set `FLAG_TRANSLUCENT` on every instance of the left (red) half in
`DrawBuilderTest`'s grid and print `commandCount()` and `batchCount()`. Explain the numbers.

<details><summary>Hint</summary>

`sameDraw` refuses translucent keys (§19.2). In which order do the
passes sort?
</details>
<details><summary>Solution</summary>

In the grid loop, pass `x < 100 ? RenderPacket.FLAG_TRANSLUCENT : 0` as the instance flags, and print the
two counts after the first frame (the `commandCount() <= 4` assertion now fails, so replace it with the
print). Result: **20,002 commands** and **2 batches**. The opaque pass (0) sorts first: the 10,000 green
instances give 2 runs (one per submesh) in one batch of slot 1. Then the translucent pass (1): 10,000
red instances × 2 submeshes = 20,000 items, none merging, so 20,000 commands — all slot 0, so they still
form one batch. The commands take $20{,}002 \times 20 = 400{,}040$ bytes of the ring instead of 80.
Interleave red and green translucent instances (a checkerboard) and the slot changes between
depth-adjacent items almost every time, so the batch count grows towards the item count as well.
</details>

**5. See the LOD in the commands.** Add a package-private accessor to `DrawBuilder` for the ring offset
of the last build's commands. In `DrawBuilderTest`, read the first command back after a frame and check
that its `count` is 3 (LOD 1); then call `setLod(SIZE / 200f, true, 0.1f)`, run a frame, and check that it
becomes 6 (LOD 0).

<details><summary>Hint</summary>

Never read the mapped memory itself: it is write-combined and mapped
write-only (L01 §3.3). Read through GL after `glFinish()`. `splitQuad`'s LOD 0 has 6 indices per
submesh and LOD 1 has 3; with threshold 0.1, LOD 1's 0.32 pixels are too many.
</details>
<details><summary>Solution</summary>

In `DrawBuilder`, keep the offset in a field:

```java
private long commandOffset;

/** Ring offset of the last build's commands (tests). */
long commandOffset() {
    return commandOffset;
}
```

and in `build` assign it (`commandOffset = ring.allocate((long) runs * COMMAND_BYTES, 16);` instead of
declaring a local, and `long c = commandOffset;` keeps working). In the test, after the first
`frame.run()`:

```java
glFinish();
MemorySegment cmd = ARENA.allocate(20);
glGetNamedBufferSubData(ring.glName(), builder.commandOffset(), 20, cmd);
assertEquals(3, cmd.get(ValueLayout.JAVA_INT, 0), "LOD 1: 3 indices");
builder.setLod(SIZE / 200f, true, 0.1f);
frame.run();
glFinish();
glGetNamedBufferSubData(ring.glName(), builder.commandOffset(), 20, cmd);
assertEquals(6, cmd.get(ValueLayout.JAVA_INT, 0), "LOD 0: 6 indices");
builder.setLod(SIZE / 200f, true, 1f); // restore for the rest of the test
```

The first command is (slot 0, submesh 0): its `count` is the index count of submesh 0 at the chosen LOD.
`glGetNamedBufferSubData` is a GL command, so it returns the bytes the GPU sees, after the earlier
commands.
</details>

**6. Frame graph liveness and barriers.** Add a test to `FrameGraphTest`: a graphics pass that writes
nothing is culled, the same pass with `.sideEffect()` stays live, and a compute pass that writes an
imported buffer read by a later graphics pass with `readsStorage` causes exactly one barrier per frame.

<details><summary>Hint</summary>

Use `graph.isLive(i)`, `graph.livePassCount()` and `graph.barriers()`.
The reading pass must itself be live: give it `.color(FrameGraph.BACKBUFFER)`; a pass that only reads is
culled like any other (§8.2).
</details>
<details><summary>Solution</summary>

```java
@Test
void sideEffectsKeepPassesAndComputeWritesGetBarriers() {
    int buffer = device.buffers().create(16, GpuBuffers.DYNAMIC, "storage");
    try (FrameGraph graph = new FrameGraph(device)) {
        int data = graph.importedBuffer("data");
        graph.setBuffer(data, device.buffers().glName(buffer));
        graph.addPass("writes nothing", ctx -> {});                         // 0
        graph.addPass("side effect", ctx -> {}).sideEffect();               // 1
        graph.addComputePass("compute", ctx -> {}).writes(data);            // 2
        graph.addPass("reader", ctx -> {})                                  // 3
                .readsStorage(data)
                .color(FrameGraph.BACKBUFFER);
        graph.compile(16, 16);
        assertFalse(graph.isLive(0));
        assertTrue(graph.isLive(1));
        assertEquals(3, graph.livePassCount());
        long before = graph.barriers();
        for (int i = 0; i < 5; i++) {
            graph.execute(i);
        }
        assertEquals(before + 5, graph.barriers(), "one storage barrier per frame");
    } finally {
        device.buffers().destroy(buffer);
    }
}
```

Pass 0 has no side effect and no writes, so nothing keeps it. Pass 1 is kept by its flag. Pass 2 writes
an imported resource (always live). Pass 3 writes the backbuffer; it reads `data`, last written by a
compute pass, so `compile` gives it `GL_SHADER_STORAGE_BARRIER_BIT`, issued once per `execute`.
</details>

**7. A batch debug view.** Write a `DrawBuilder.BatchBinder` that draws every opaque batch with one
flat-color program whose color depends on the program slot, set with `glProgramUniform4f`. Use it in
`DrawBuilderTest` instead of the default binder and assert that the two halves get different colors.

<details><summary>Hint</summary>

Define the program once with `programs.define("slot color", "flat.vert",
"slot.frag", ShaderFeatures.NONE)` and add `slot.frag` to the test's `ShaderSources`. Location 0 is
`flat.vert`'s view-projection uniform, so give the color location 1, and set location 0 of the new
program once, as the test does for the material programs. Create the binder once, before the frame
loop, so frames stay allocation-free; `MaterialSystem.depthBinder` is a model.
</details>
<details><summary>Solution</summary>

The fragment shader, added to the test's `switch` as `case "slot.frag" -> SLOT_FRAG;`:

```glsl
#version 460 core
layout(location = 1) uniform vec4 slotColor;
layout(location = 0) flat in uint material;
layout(location = 0) out vec4 color;
void main() { color = slotColor; }
```

In the test, after the material programs are ready:

```java
int slotColor = programs.define("slot color", "flat.vert", "slot.frag", ShaderFeatures.NONE);
int debugProgram = programs.getBlocking(slotColor, 0);
glProgramUniformMatrix4fv(
        debugProgram, 0, 1, false, ARENA.allocateFrom(ValueLayout.JAVA_FLOAT, vp));
DrawBuilder.BatchBinder debug =
        (state, slot, translucent) -> {
            if (translucent) {
                return false;
            }
            float h = (slot * 0.618f) % 1f; // a different hue per slot
            state.apply(PipelineState.OPAQUE);
            state.useProgram(debugProgram);
            glProgramUniform4f(debugProgram, 1, h, 1 - h, 0.5f, 1);
            return true;
        };
```

(`vp` is the orthographic matrix the test already builds.) In the frame, call
`builder.submit(device.state(), ring, debug)`. Slot 0 gets $(0, 1, 0.5)$ and slot 1 $(0.618, 0.382,
0.5)$, so the red channel reads 0 on the left and about 158 on the right: assert they differ. The lambda
captures `debugProgram`, so it is one object, created once — reused every frame, it allocates nothing.
</details>

**8. Test the GPU culler.** In `StaticSceneTest`, place a row of static spheres that crosses the side
planes of the view and assert that the GPU's visible count equals what `CullOps.sphereVisible` accepts.
Then clear `FLAG_CASTS_SHADOW` on the spheres and check that the cascade's counts follow without a
rebuild.

<details><summary>Hint</summary>

`gpuCulledStaticInstancesLookLikeTheCpuPath` does the first half for a
grid. The main view culls with `requiredFlags = 0`, but `SunShadows` culls the cascade views with
`FLAG_CASTS_SHADOW`; flags are copied every frame (D49), so changing them needs no rebuild:
`commandCount()` stays the same.
</details>
<details><summary>Solution</summary>

```java
@Test
void aRowAcrossTheViewAndShadowFlagsFollowTheCpu() {
    int flags = RenderPacket.FLAG_STATIC | RenderPacket.FLAG_CASTS_SHADOW;
    add(ground, 0, -0.2f, 0, white, flags, 8.5f);
    for (int x = -12; x <= 12; x++) {
        add(sphere, x, 0, 0, white, flags, 0.2f);
    }
    render(pixels);
    int drawn = 0;
    for (int c : scene.readInstanceCounts(StaticScene.VIEW_MAIN)) {
        drawn += c;
    }
    int expected = 0;
    for (int i = 0; i < packet.count(); i++) {
        int o = i * 4;
        float[] b = packet.bounds;
        if (CullOps.sphereVisible(packet.frustumPlanes, b[o], b[o + 1], b[o + 2], b[o + 3])) {
            expected++;
        }
    }
    assertEquals(expected, drawn, "GPU and CPU agree");
    assertTrue(drawn < packet.count(), "the row leaves the view");

    int commands = scene.commandCount();
    int before = 0;
    for (int c : scene.readInstanceCounts(StaticScene.VIEW_CASCADES)) {
        before += c;
    }
    for (int i = 1; i < packet.count(); i++) {
        packet.flags[i] &= ~RenderPacket.FLAG_CASTS_SHADOW;
    }
    render(pixels);
    int after = 0;
    for (int c : scene.readInstanceCounts(StaticScene.VIEW_CASCADES)) {
        after += c;
    }
    assertEquals(commands, scene.commandCount(), "no rebuild");
    assertTrue(after <= 1 && after <= before, "only the ground may still cast: " + after);
}
```

The camera of `reset()` is at $(0, 6, 6)$ with a 40° field of view, so at the origin the view is only
about 6 units wide and a row from $x = -12$ to $12$ crosses both side planes. Index 0 is the ground, so
the flag loop starts at 1. After clearing the flag, the first cascade (view `VIEW_CASCADES`) can count
at most the ground.
</details>

**9. The range allocator by hand.** On `new RangeAllocator(64)`, run `allocate(10, 1)`,
`allocate(20, 8)`, `allocate(5, 1)`, `free(16, 20)`, `free(0, 10)`, `free(10, 5)`. Write the result of
each allocation and the free list after every step (§11).

<details><summary>Hint</summary>

The second allocation starts at a free range beginning at 10; round 10 up
to a multiple of 8. The third fits into the padding the second left behind.
</details>
<details><summary>Solution</summary>

| Step | Result | Free list after |
|---|---|---|
| `allocate(10, 1)` | 0 | $[10, 64)$ |
| `allocate(20, 8)` | 16 (aligned from 10, pad 6) | $[10, 16)$, $[36, 64)$ |
| `allocate(5, 1)` | 10 (first fit: the 6-unit pad) | $[15, 16)$, $[36, 64)$ |
| `free(16, 20)` | — | $[15, 64)$: touches both neighbours, three ranges merge |
| `free(0, 10)` | — | $[0, 10)$, $[15, 64)$ |
| `free(10, 5)` | — | $[0, 64)$: merges both again |

`free()` goes 54, 34, 29, 49, 59, 64 over the six steps. (These results were checked by running the
steps against the real `RangeAllocator`; do the same in a scratch test.)
</details>

**10. A radix sort by hand.** Sort the 16-bit keys `0x0302 0x0103 0x0202 0x0101` (payload 0, 1, 2, 3) with
the LSD radix sort of §5.3–5.4. Give the histogram, offsets and result of both passes. How many of the 8
passes would `LongSort` run for these keys as `long`s?

<details><summary>Hint</summary>

Pass 0 sorts by the low byte (02, 03, 02, 01), pass 1 by the high byte,
each scattering in the current order.
</details>
<details><summary>Solution</summary>

Pass 0: counts `01`→1, `02`→2, `03`→1; offsets `01`→0, `02`→1, `03`→3. Scatter in order: `0x0302`→1,
`0x0103`→3, `0x0202`→2, `0x0101`→0. Result `0x0101 0x0302 0x0202 0x0103`, payload 3 0 2 1.

Pass 1: high bytes 01 03 02 01: counts `01`→2, `02`→1, `03`→1; offsets 0, 2, 3. Scatter:
`0x0101`→0, `0x0302`→3, `0x0202`→2, `0x0103`→1. Result `0x0101 0x0103 0x0202 0x0302`, payload 3 1 2 0:
sorted, and the two keys with high byte 01 kept the order pass 0 gave them. As `long`s, bytes 2–7 are 0
in every key, so `LongSort` runs **2** passes and skips 6.
</details>

**11. LOD by hand.** A 1440-pixel-high view with a 35° field of view looks at a prop scaled by 2 (§2.3),
60 units away. Its LODs have errors 0, 0.01, 0.04 and 0.2. Which LOD does `selectLod` choose with a
1-pixel threshold? And in an orthographic view showing 40 units vertically on the same 1440 pixels?

<details><summary>Hint</summary>

$P = H / (2 \tan(\theta/2))$, $k = P s / d$ (§6.1); orthographic
$P = H / (\text{top} - \text{bottom})$, $k = P s$.
</details>
<details><summary>Solution</summary>

Perspective: $P = 1440 / (2 \tan 17.5°) \approx 2283.5$, $k = 2283.5 \times 2 / 60 \approx 76.1$. LOD 1:
$0.01 \times 76.1 \approx 0.76$ px, accepted; LOD 2: $0.04 \times 76.1 \approx 3.04$ px, rejected; the
loop stops. **LOD 1.** Orthographic: $P = 1440 / 40 = 36$, $k = 72$: LOD 1 $0.72$ px accepted, LOD 2
$2.88$ px rejected: **LOD 1** again.
</details>

**12. One indirect command, followed through.** A command reads $(54, 4, 9000, 2500, 300)$. Which records
do its instances read, which byte of the index buffer does it start at, and which vertex slot does an
index value of 17 fetch?

<details><summary>Hint</summary>

§3.4: the fields are count, instanceCount, firstIndex, baseVertex,
baseInstance; indices are 4 bytes.
</details>
<details><summary>Solution</summary>

4 instances, `gl_InstanceID` 0–3, read records $300 + 0 \dots 300 + 3$ = 300 to 303. It reads 54 indices
starting at index 9000, which is byte $4 \times 9000 = 36{,}000$ of the element buffer. An index value of
17 fetches vertex slot $2500 + 17 = 2517$ of every stream.
</details>

**13. A field in the wrong place.** In the frame constants (§27.6), at which byte offset is `jSunColor.w`
(the "sun on" flag)? Now suppose someone inserted a `float jTime;` right after `jEye` in
`engine/frame.glsl`. Where would `jViewport` start, how big would the block become, and what would break?

<details><summary>Hint</summary>

In `std140` a `vec4` starts on a multiple of 16.
</details>
<details><summary>Solution</summary>

`jSunColor` starts at 400, so its `w` is at $400 + 12 = 412$. With a `float` at 336, the next `vec4` cannot
start at 340: it moves to 352, after 12 bytes of padding, and every later member moves by 16 bytes; the
block becomes 560. `writeFrameConstants` would still write `jViewport` at 336 and `FRAME_BYTES` would
still say 544, so every member from `jViewport` on would read the wrong numbers and the last 16 bytes
would fall outside the bound range. That is why the time rides in `jEye.w`: four numbers per 16-byte
slot, no padding.
</details>

**14. The static scene's memory.** A level's static scene has three bins: walls (slot 0, 2,000 items,
3 LODs), floor tiles (slot 0, 5,000 items, 1 LOD) and pillars (slot 1, 500 items, 4 LODs). How many
commands, batches and records does it have, and how many bytes of records, items and per-view commands?

<details><summary>Hint</summary>

One command per (bin, LOD); room for every item at every LOD (§26.2);
items are 96 bytes, records 64, a view's commands buffer $2 \times 20 \times \text{commands} + 4 \times
\text{batches}$.
</details>
<details><summary>Solution</summary>

Commands: $3 + 1 + 4 = 8$. Batches: 2 (slot 0 and slot 1). Records: $2000 \times 3 + 5000 \times 1 + 500
\times 4 = 13{,}000$, so $13{,}000 \times 64 = 832{,}000$ bytes per view and $4{,}160{,}000$ for the five
views. Items: $7{,}500 \times 96 = 720{,}000$ bytes. Each view's commands buffer: $2 \times 20 \times 8 +
4 \times 2 = 328$ bytes. The records dominate; that is the price of skipping a prefix-sum pass.
</details>

**15. A missing barrier.** Suppose the barrier after the cull dispatch in `StaticScene.cull` were removed
(the one after compact stays). What could go wrong, on which drivers would you see it, and on which not?

<details><summary>Hint</summary>

Who reads, right after the cull, what the cull's atomics wrote
(§9.4)?
</details>
<details><summary>Solution</summary>

The compact pass reads each command's `instanceCount`, written by the cull's atomics. Without a barrier
between them, compact may run while cull invocations are still incrementing — or read stale cached
values — and see 0 for a command that is about to be non-empty (it is skipped: those objects vanish for a
frame) or copy a count that is still too small (some instances are not drawn). The result changes from
frame to frame: flickering geometry. On a driver that finishes one dispatch before starting the next, or
on llvmpipe, which runs them one after the other on the CPU, nothing shows; on a GPU and driver that
overlap the two dispatches, it does (L01 §15). The vertex shaders' reads of the records would still be
covered, by the storage bit of the barrier after compact.
</details>

**16. Slices of the light clusters.** With the packet defaults ($n = 0.1$, $f = 200$, $S = 24$), which
slice holds a fragment 1 unit away? 100 units? And what are the slices of $z = n$ and $z = f$?

<details><summary>Hint</summary>

$\text{slice}(z) = S \ln(z/n) / \ln(f/n)$, and $\ln 2000 \approx 7.6009$
(§27.6).
</details>
<details><summary>Solution</summary>

$z = 1$: $24 \ln 10 / 7.6009 \approx 24 \times 2.3026 / 7.6009 \approx 7.27$, slice 7. $z = 100$:
$24 \ln 1000 / 7.6009 \approx 21.81$, slice 21. $z = n$: $\ln 1 = 0$, slice 0; $z = f$: exactly 24, the
end of the last slice. Between 1 and 100 units — a factor of 100 — lie 14.5 slices; between 0.1 and 1 —
a factor of 10 — lie 7.27: equal ratios, equal numbers of slices.
</details>

**17. The sky's triangle.** Compute `p` and `gl_Position` for `gl_VertexID` 0, 1 and 2 in
`engine/fullscreen.vert`, and explain why the sky, drawn after the opaque pass, does not cover the
objects.

<details><summary>Hint</summary>

`(id << 1) & 2` and `id & 2` (L00 §6); then the reversed-Z depth of
$z = 0$ and the test `GL_GEQUAL`.
</details>
<details><summary>Solution</summary>

id 0: `(0 << 1) & 2 = 0`, `0 & 2 = 0` → $p = (0, 0)$, clip $(-1, -1)$. id 1: `2 & 2 = 2`, `1 & 2 = 0` →
$(2, 0)$, clip $(3, -1)$. id 2: `4 & 2 = 0`, `2 & 2 = 2` → $(0, 2)$, clip $(-1, 3)$. The triangle covers the
screen's square. Every sky fragment has depth $0/1 = 0$, the far plane in reversed-Z. The test `GL_GEQUAL`
passes where $0 \ge \text{stored depth}$, that is only where the depth buffer still holds its clear value
0 — where no object was drawn. Over an object the stored depth is greater than 0 and the sky fragment
fails; depth writes are off, so the sky leaves the depth buffer as it was.
</details>

## 38. Glossary

- **Affine transform (3×4):** rotation, scale and translation as three rows of four numbers; each world
  coordinate is a dot product of a row with $(x, y, z, 1)$ (§2.2).
- **`atomicAdd`:** a GPU operation that adds to a value in memory indivisibly and returns the old value:
  a count and a unique slot at once (§9.2).
- **Barrier (`glMemoryBarrier`):** makes earlier shader writes visible to later commands that read through
  the named paths; covers every later command (§9.4, L01 §6.3).
- **Base instance, base vertex, first index:** the numbers of an indirect command that say which records,
  which vertex slots and which indices a draw uses (§3.2–3.4).
- **Batch:** consecutive commands with the same pass and program slot, issued as one multi-draw (§19.4).
- **Batch binder:** a callback that binds the program, state and textures for each batch, or skips it
  (§20.2).
- **Bin:** in the static scene, all items with the same (program slot, mesh, submesh); one command per
  LOD (§26.2).
- **Bindless texture:** a texture reached through a 64-bit handle instead of a binding; an extension Jeddy
  does not depend on (§7.2).
- **Bounding sphere:** a sphere that contains an object; culling tests it against the frustum (§2.4).
- **Bucket:** one texture array holding all material textures of one (format, width, height, levels)
  (§7.2, §23).
- **Compaction:** packing the non-empty entries of a list together (§13, §26.9).
- **Compute shader, workgroup, invocation:** a GPU program run over a grid; groups of invocations
  scheduled together; one run of the program (§9.1).
- **Counting sort:** sorting by a small digit with a histogram, offsets and a stable scatter (§5.3).
- **Culling:** skipping objects that cannot be seen (§2.4, §13, §26).
- **Depth prepass:** a pass that writes only depth, so the lit pass shades each pixel once (§27.7).
- **Dirty range:** the rows changed since the last upload (§14.1).
- **Early depth test:** the GPU's depth comparison before the fragment shader runs (§5.1).
- **Feature mask, variant:** the bits that choose a shader's `#define`s; one compiled program per mask
  (§22.1).
- **First fit, free list:** taking the first free range that is big enough, from a list of free ranges
  sorted by offset (§11).
- **Frame constants:** the per-frame uniform block of `engine/frame.glsl` (§27.6).
- **Frame graph:** passes declared with their resources, compiled into an order with targets, clears and
  barriers (§8, §24).
- **Frustum, frustum planes:** the region the camera sees, bounded by six planes (§2.4).
- **`gl_InstanceID`, `gl_BaseInstance`:** the copy number within an instanced draw, and the draw's base
  instance (§3.3).
- **Imported, transient (resource):** owned outside the graph, or created by it (§8.2, §24.2).
- **Indirect command:** the 20-byte `DrawElementsIndirectCommand` a GPU reads from a buffer (§3.4).
- **Instance record:** the 64 bytes per item in the frame ring: transform rows, material, flags, entity,
  fade (§4.1).
- **Instancing:** drawing the same index range several times in one draw (§3.3).
- **Item:** one (visible instance, submesh) pair: one record, one sort key (§15).
- **Level of detail (LOD), geometric error:** simplified versions of a mesh; how far each deviates, in
  mesh units (§6).
- **Liveness:** whether a pass's results are needed; worked out from the last pass backwards (§8.2).
- **LSD radix sort:** sorting by each byte from the least significant up, stably (§5.4).
- **Mega buffer:** one big buffer holding every mesh's vertices or indices (§12).
- **Multi-draw-indirect (MDI):** one call that runs many indirect commands from a buffer (§3.5);
  **indirect count** variant: the GPU reads how many (§9.3).
- **Parameter buffer:** the buffer `glMultiDrawElementsIndirectCount` reads its count from (§9.3).
- **Payload:** the value carried along with each key in a sort (§5.4).
- **Prefix sum:** a running total; tells each element where its output starts (§16).
- **Program slot:** a (program definition, feature mask, texture set) triple; what batches are keyed by
  (§14.3).
- **Render packet:** the frame's description, written by the game thread (§10).
- **Run:** consecutive sorted items that share one indirect command (§19.1).
- **Signed distance:** $\mathbf{n} \cdot \mathbf{x} + d$ for a unit normal: how far a point is from a
  plane, and on which side (§2.4).
- **Sort key:** an unsigned 64-bit number packing every field that orders draws (§5.2).
- **Stable (sort):** equal keys keep their input order (§5.3).
- **`std140`, `std430`:** GLSL's layout rules for uniform and storage blocks (§4.1, §27.6).
- **Storage buffer (SSBO), uniform buffer (UBO):** buffers shaders read as arrays of structs; the uniform
  kind is for small data every invocation reads (§4.1, §27.6).
- **Texture array, layer:** one texture holding many same-sized images; one of them (§7.2).
- **Texture set:** which bucket each of a material's five texture units binds (§22.4).
- **Timestamp query:** the GPU's clock recorded when earlier commands are done (§25).
- **Vertex space:** the slot numbers shared by all vertex streams of the mega buffers (§12.2).

---

*Code this chapter builds or quotes: `RenderPacket`, `RenderPackets`, `core.memory.RangeAllocator`,
`MeshRegistry`, `MeshLoader`, `MeshAssets`, `Culling`, `CullOps`, `MaterialTable`, `DrawBuilder`,
`core.collections.LongSort`, `core.job.ParallelSort`, `MaterialSystem`, `TextureArrays`, `TextureLoader`,
`FrameGraph`, `PassContext`, `PassExecutor`, `GpuTimers`, `StaticScene`, `SceneRenderer`,
`app.world.WorldRenderer`; the shaders `engine/instance.glsl`, `engine/material.glsl`,
`engine/frame.glsl`, `engine/fullscreen.vert`, `engine/skinning.glsl`, `pbr/pbr.vert`, `pbr/pbr.frag`,
`cull/static_cull.comp`, `cull/static_compact.comp`; the tests `RangeAllocatorTest`, `CollectionsTest`,
`ParallelSortTest`, `RenderPacketTest`, `MeshRegistryTest`, `DrawBuilderTest`, `FrameGraphTest`,
`StaticSceneTest`, `SceneRendererTest`. Decisions: D13, D21, D24, D27, D29, D30, D31, D32, D41, D42,
D44, D48, D49, D73, D80, D101, D103 in [DESIGN §17](../DESIGN.md), and [ADR 0001](../adr/0001-texture-binding.md).
Lessons: [L00](lessons/00-foundations.md) (the foundations), [L01](lessons/01-buffers-in-flight.md) (the
frame ring this renderer writes into), [L03](lessons/03-data-layout.md) (handles and struct of arrays).
Chapters: [01](01-architecture.md) (the packet's triple buffer), [04](04-cameras-and-projection.md)
(projection, reversed-Z, frustum planes), [05](05-opengl-from-java.md) (the GL building blocks),
[07](07-lighting-and-shadows.md) (what the lit passes compute), [08](08-effects-and-post.md) (post,
particles, outlines), [09](09-animation-and-skinning.md) (skinning), [10](10-assets.md) (where meshes and
textures come from).*

[← 05 OpenGL from Java](05-opengl-from-java.md) · [Index](README.md) · [07 Lighting and shadows →](07-lighting-and-shadows.md)
