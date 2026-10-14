# 05 — OpenGL from Java

*This chapter builds the layer between the engine and the GPU. It starts with what a GPU does,
stage by stage, when you ask it to draw one triangle, and what the driver and the hardware do at
each stage. Then it builds, from empty files, the code that lets Java talk to OpenGL: a generator
that turns the Khronos registry into a Java class with one method per GL command, the linker that
resolves those commands at run time, the loader, the window's GL context and the first program
that draws, `HelloTriangle`. Last come the pieces the renderer stands on: vertex arrays, textures,
samplers, framebuffers, the packed pipeline state and the cache that skips redundant GL calls,
the shader preprocessor and the program cache with its variants and binaries. It ends with the
tests that prove each piece, measured call costs and the decisions behind them.*

**Before you start.** You need [Lesson 0](lessons/00-foundations.md): hexadecimal and bits (§3–§6,
especially packing in §6.4 and rounding up to a multiple of a power of two in §6.6), how a `float` is stored
(§7), native memory and alignment (§8.3–§8.4), threads and `volatile` (§9.4–§9.5), what a GPU, a
driver and OpenGL are (§10), frames (§11) and the FFM API (§12). From the earlier chapters you need
vectors, dot and cross products and column-major matrices (chapter
[02](02-math-foundations.md), on math foundations), 2D and 3D rotations (chapter
[03](03-rotations-and-transforms.md)), and clip space, normalized device coordinates and
reversed-Z depth (chapter [04](04-cameras-and-projection.md), on cameras and projection): this
chapter uses them without deriving them again. [Lesson 1](lessons/01-buffers-in-flight.md) builds
the buffer table and the frame ring, and explains fences, coherent mappings and memory barriers;
this chapter summarizes it and links there. [Lesson 3](lessons/03-data-layout.md) builds the
generational handles every resource class here uses.

**What you will build**

| Piece | File | What it does |
|---|---|---|
| Generator settings | [`bindings/gl/gl.properties`](../../bindings/gl/gl.properties) | which API, version, extensions, critical commands and offset parameters to generate |
| The generator | [`GlGenerator`](../../build-logic/src/main/java/dev/jeddy/gradle/GlGenerator.java) | reads the Khronos registry `gl.xml` and writes `GL46.java` |
| The Gradle task | [`GlBindings.kt`](../../build-logic/src/main/kotlin/dev/jeddy/gradle/GlBindings.kt), [`jeddy.gl-bindings`](../../build-logic/src/main/kotlin/jeddy.gl-bindings.gradle.kts) | runs the generator at build time, cacheably |
| The linker | [`GLLinker`](../../modules/gl/src/main/java/dev/jeddy/gl/ffi/GLLinker.java) | turns a command name into a `MethodHandle`, critical or not, or a throwing stand-in |
| Loader and capabilities | [`GLLoader`](../../modules/gl/src/main/java/dev/jeddy/gl/GLLoader.java), [`GLCapabilities`](../../modules/gl/src/main/java/dev/jeddy/gl/GLCapabilities.java) | installs the resolver, checks for 4.6, reads limits and extensions |
| The context | [`Window`](../../modules/window/src/main/java/dev/jeddy/window/Window.java) | asks SDL for a 4.6 core context on the main thread |
| Shader helpers | [`GLShaders`](../../modules/gl/src/main/java/dev/jeddy/gl/GLShaders.java) | compile and link with the driver's log in the exception |
| The first program | [`HelloTriangle`](../../samples/hello-triangle/src/main/java/dev/jeddy/samples/triangle/HelloTriangle.java) | one spinning triangle with raw DSA calls |
| Debug output | [`GLDebug`](../../modules/gl/src/main/java/dev/jeddy/gl/GLDebug.java) | the `KHR_debug` callback as an upcall, labels, groups, error counters |
| The resource layer | [`GpuDevice`](../../modules/render/src/main/java/dev/jeddy/render/gpu/GpuDevice.java), [`UploadRing`](../../modules/render/src/main/java/dev/jeddy/render/gpu/UploadRing.java) | owns buffers, textures, samplers, framebuffers and the state cache; stages asset uploads |
| Textures | [`GpuTextures`](../../modules/render/src/main/java/dev/jeddy/render/gpu/GpuTextures.java), [`GpuFormats`](../../modules/render/src/main/java/dev/jeddy/render/gpu/GpuFormats.java) | immutable storage of five kinds, uploads per level and layer, KTX2 formats |
| Samplers | [`SamplerDesc`](../../modules/render/src/main/java/dev/jeddy/render/gpu/SamplerDesc.java), [`GpuSamplers`](../../modules/render/src/main/java/dev/jeddy/render/gpu/GpuSamplers.java) | a sampler described in an `int`, created once per description |
| Framebuffers | [`GpuFramebuffers`](../../modules/render/src/main/java/dev/jeddy/render/gpu/GpuFramebuffers.java) | render targets made of textures, checked for completeness |
| Pipeline state | [`PipelineState`](../../modules/render/src/main/java/dev/jeddy/render/gpu/PipelineState.java), [`StateCache`](../../modules/render/src/main/java/dev/jeddy/render/gpu/StateCache.java) | fixed-function switches packed in a `long`; only changed bits reach GL |
| Shaders | [`ShaderPreprocessor`](../../modules/asset/src/main/java/dev/jeddy/asset/shader/ShaderPreprocessor.java), [`ShaderFeatures`](../../modules/render/src/main/java/dev/jeddy/render/shader/ShaderFeatures.java), [`ProgramCache`](../../modules/render/src/main/java/dev/jeddy/render/shader/ProgramCache.java), [`ProgramBinaryCache`](../../modules/render/src/main/java/dev/jeddy/render/shader/ProgramBinaryCache.java), [`EngineShaders`](../../modules/render/src/main/java/dev/jeddy/render/shader/EngineShaders.java) | includes and defines, variants by feature mask, background compiles, binaries on disk |

The buffer table [`GpuBuffers`](../../modules/render/src/main/java/dev/jeddy/render/gpu/GpuBuffers.java)
and the frame ring [`FrameRing`](../../modules/render/src/main/java/dev/jeddy/render/gpu/FrameRing.java)
belong to this layer too; lesson 1 builds them.

**Contents**

- Part A — The ideas
  1. [What drawing means to a GPU](#1-what-drawing-means-to-a-gpu)
  2. [The pipeline, stage by stage](#2-the-pipeline-stage-by-stage)
  3. [OpenGL 4.6 core: a state machine with objects](#3-opengl-46-core-a-state-machine-with-objects)
  4. [The context and the main thread](#4-the-context-and-the-main-thread)
  5. [Calling GL from Java](#5-calling-gl-from-java)
  6. [Textures and sampling](#6-textures-and-sampling)
  7. [Getting data to shaders](#7-getting-data-to-shaders)
  8. [Shaders, programs and variants](#8-shaders-programs-and-variants)
- Part B — Building it
  9. [Step 1: say what to generate](#9-step-1-say-what-to-generate)
  10. [Step 2: the generator](#10-step-2-the-generator)
  11. [Step 3: GLLinker](#11-step-3-gllinker)
  12. [Step 4: GLLoader and GLCapabilities](#12-step-4-glloader-and-glcapabilities)
  13. [Step 5: the window and its context](#13-step-5-the-window-and-its-context)
  14. [Step 6: GLShaders](#14-step-6-glshaders)
  15. [Step 7: hello triangle](#15-step-7-hello-triangle)
  16. [Step 8: GLDebug](#16-step-8-gldebug)
  17. [Step 9: the resource layer](#17-step-9-the-resource-layer)
  18. [Step 10: vertex arrays](#18-step-10-vertex-arrays)
  19. [Step 11: textures](#19-step-11-textures)
  20. [Step 12: samplers](#20-step-12-samplers)
  21. [Step 13: framebuffers](#21-step-13-framebuffers)
  22. [Step 14: PipelineState](#22-step-14-pipelinestate)
  23. [Step 15: StateCache](#23-step-15-statecache)
  24. [Step 16: the shader preprocessor](#24-step-16-the-shader-preprocessor)
  25. [Step 17: ShaderFeatures and ProgramCache](#25-step-17-shaderfeatures-and-programcache)
  26. [Step 18: ProgramBinaryCache and EngineShaders](#26-step-18-programbinarycache-and-engineshaders)
- Part C — Using it and proving it
  27. [How the engine uses it](#27-how-the-engine-uses-it)
  28. [The tests, walked through](#28-the-tests-walked-through)
  29. [Measured numbers](#29-measured-numbers)
  30. [Things to run](#30-things-to-run)
  31. [Why it is built this way](#31-why-it-is-built-this-way)
  32. [Exercises](#32-exercises)
  33. [Glossary](#33-glossary)
  34. [Further reading](#34-further-reading)

---

# Part A — The ideas

## 1. What drawing means to a GPU

L00 §10 described the GPU as a processor that runs the same small program on a huge number of
inputs. Drawing is the job it was built for, and it does not work the way a painter does. You do
not hand the GPU triangles one at a time. You prepare four things and then give one order:

1. **Data in GPU memory**: the corners of the triangles (vertex positions, normals, texture
   coordinates), the order in which to join them (indices), images (textures), and parameters
   (where each object is, which material it uses).
2. **Programs** that process the data: a *vertex shader* that runs once per corner, and a
   *fragment shader* that runs once per covered pixel.
3. **Switches** for the fixed hardware between those programs: is the depth test on, which faces
   are culled, is blending on.
4. **A target**: the images the results go into (the window's back buffer, or textures of your
   own).

Then the order: "draw 36 indices as triangles". The driver records it into a command buffer and
returns at once (L00 §10.4–§10.5). Later the GPU executes it by pushing the data through a fixed
sequence of stages, the **graphics pipeline**. Some stages are programs you write; the others are
fixed-function hardware that you configure with the switches.

The rest of Part A explains each stage, what the hardware does in it and what can go wrong there
(§2), then the API that drives it all: OpenGL 4.6 and its objects (§3), the context and the thread
that owns it (§4), the way Java reaches the driver (§5), textures (§6), the ways data reaches
shaders (§7), and shader programs (§8). Part B then writes the code.

## 2. The pipeline, stage by stage

### 2.1 The map

```
 GPU memory                 the pipeline (one draw)                         GPU memory
 ──────────                 ───────────────────────                         ──────────

 index buffer ─┐
 vertex        ├─▶ [1 vertex fetch]           fixed: reads attributes, converts formats
 buffers ──────┘          │
                          ▼
 uniforms,      ─▶ [2 vertex shader]          YOUR CODE, once per vertex → gl_Position (clip space)
 storage buffers,         │
 textures                 ▼
                   [3 primitive assembly]     fixed: three vertices → one triangle
                          │
                          ▼
                   [4 clipping]               fixed: cut against the view volume
                          │
                          ▼
                   [5 divide by w, viewport]  fixed: clip → NDC → window pixels and depth
                          │
                          ▼
                   [6 face culling]           fixed: drop triangles facing away
                          │
                          ▼
                   [7 rasterization]          fixed: which pixel centers are covered → fragments
                          │
                          ▼
                   [8 interpolation]          fixed: vertex outputs → per-fragment inputs
                          │
                          ▼
 uniforms,      ─▶ [9 fragment shader]        YOUR CODE, once per fragment → color(s)
 storage buffers,         │
 textures                 ▼
                   [10 stencil, depth tests]  fixed: keep only the nearest surface
                          │                        ◀──── depth buffer (read, maybe write)
                          ▼
                   [11 blending]              fixed: mix with the color already there
                          │                        ◀──── color image (read, write)
                          ▼
                   [12 framebuffer write] ──────────────────────────────▶ color and depth images
```

Two programmable stages, ten fixed ones. (OpenGL also has optional tessellation and geometry
shader stages between 2 and 3; Jeddy uses neither.) A separate path, **compute shaders** (§2.15),
runs your code on arbitrary data without any of the fixed stages.

A word on vocabulary used below. A **vertex** is one corner with its attributes. A **primitive**
is what the vertices form: a triangle, a line or a point; Jeddy draws triangles everywhere except
the debug lines. A **fragment** is a candidate for one pixel: the triangle covers this pixel's
center, here is its depth and its interpolated inputs. It becomes a pixel's new color only if it
survives the tests of stages 10 and 11.

The examples below follow the chapter's own program, `HelloTriangle` (§15): three vertices,
positions in two floats and colors in three, a window of 960 × 540 pixels.

### 2.2 Before stage 1: the command processor

The GPU's **command processor** reads the command buffer the driver filled (L00 §10.4) in order.
A draw command names the vertex count, the index buffer, the primitive type, and implicitly
everything the driver has written into the command stream before it: which shaders, which
buffers at which binding points, which switches. The command processor sets up the fixed units
and starts the vertex work. Two consequences shape everything later:

- **Commands start in the order you issued them.** A draw issued after a copy sees the copy's
  result (lesson 1, §2.3 there).
- **State changes are commands too.** Changing the blend mode, binding another program or
  another texture costs the driver validation work on the CPU and costs the command processor
  some setup on the GPU. That is why §23 builds a cache that skips calls that change nothing, and
  why chapter [06](06-renderer-architecture.md) sorts draws so that a whole batch shares one
  setup.

For *indirect* draws the command processor reads the draw's parameters (counts, first index,
base instance) from a buffer instead of from the command itself. Chapter 06 uses that to draw
thousands of objects with one call; lesson 1 (§14–§15) shows how those parameter buffers are
written.

### 2.3 Stage 1: vertex fetch

**What it does.** For every vertex the draw needs, read its attributes from memory and convert
them to the types the vertex shader declares.

For a non-indexed draw such as `glDrawArrays(GL_TRIANGLES, 0, 3)` the vertices are simply
numbered 0, 1, 2. For an indexed draw such as `glDrawElements` the GPU first reads the **index
buffer**, a list of vertex numbers: `0, 1, 2, 0, 2, 3` draws a quad as two triangles from four
vertices. Then, for each vertex number $i$ and each attribute, the fetch unit computes an
address. With the separate attribute format introduced in GL 4.3 (§18) the formula is

$$\text{address} = \text{bufferStart} + \text{bindingOffset} + i \cdot \text{stride} + \text{relativeOffset}$$

where the **binding** (a buffer, a starting offset and a stride, the byte distance from one vertex
to the next) is shared by every attribute that reads from it, and the **relative offset** is the
attribute's position inside one vertex.

*Worked example.* `HelloTriangle` keeps five floats per vertex, $x, y, r, g, b$: a stride of
$5 \cdot 4 = 20$ bytes. The color attribute starts at byte 8 of each vertex. The color of vertex 2
is therefore read at byte $0 + 0 + 2 \cdot 20 + 8 = 48$ of the buffer.

The format says how to turn the bytes into numbers: three 32-bit floats, four 16-bit integers
mapped to $[-1, 1]$, two 16-bit half floats. §18.3 derives each conversion.

**What the hardware does.** Indices and attributes are read through the GPU's caches (on most
GPUs, the same memory path that textures use). The format conversion is done by fixed hardware,
so storing a normal in four 16-bit integers instead of three floats costs nothing at run time and
halves the bytes read. Indexed draws allow one more saving: GPUs keep a small **post-transform
vertex cache** of recently shaded vertices by index, so a vertex shared by several triangles of
the same draw is usually shaded once. A quad drawn with 4 vertices and 6 indices typically runs
the vertex shader 4 times, not 6.

**What can go wrong.** An attribute described with the wrong type or offset produces garbage
positions (the classic "exploding mesh"). An integer attribute declared as a float, or the
opposite, is undefined behaviour (§18.3). An index past the end of the vertex buffer reads
whatever memory is there or zeros, depending on whether the context was created with *robust
buffer access* (an option that makes out-of-range reads return zeros or values from inside the
buffer instead of reading other memory; Jeddy does not ask for it).

### 2.4 Stage 2: the vertex shader

**What it does.** Your program runs once per vertex. Its one obligation is to write the built-in
output `gl_Position`, the vertex's position in **clip space**: four numbers $(x_c, y_c, z_c, w_c)$
produced, usually, by multiplying the vertex's world position by the camera's view-projection
matrix (chapter [04](04-cameras-and-projection.md) derives that matrix and explains what the
fourth coordinate $w$ is for). It may also write any other outputs (a normal, texture
coordinates, a material index), which later stages interpolate for the fragment shader.

A vertex shader cannot see other vertices, cannot create or delete vertices, and receives two
built-in numbers that identify it: `gl_VertexID` (its index) and `gl_InstanceID` (which copy of
the mesh, when one draw draws many copies). Jeddy's shaders use `gl_BaseInstance +
gl_InstanceID` to find each object's record in a storage buffer (lesson 1, §14.1; chapter 06).

*Worked example.* At time 0 `HelloTriangle`'s vertex shader rotates by angle 0 (no change) and
divides $x$ by the window's aspect ratio $960 / 540 \approx 1.7778$. Vertex 0, at $(-0.6, -0.5)$,
leaves with `gl_Position` $= (-0.3375, -0.5, 0, 1)$.

**What the hardware does.** The GPU's shader cores run many vertices at once: groups of 32 or 64
vertices execute the same instruction together on different data (L00 §10.1). The results are
kept in fast on-chip memory, ready for primitive assembly. Reading a uniform buffer, a storage
buffer or a texture from a vertex shader goes through the same caches as in a fragment shader.

**What can go wrong.** Forgetting `gl_Position` leaves it undefined: nothing, or random
triangles. Writing positions in the wrong space (world instead of clip) usually shows nothing,
because almost everything ends up outside the clip volume of §2.6.

### 2.5 Stage 3: primitive assembly

**What it does.** Groups vertices into primitives, according to the mode the draw names. With
`GL_TRIANGLES` every three consecutive vertices (or indices) form one triangle: indices 0, 1, 2,
then 3, 4, 5, and so on. Other modes share vertices between neighbours: `GL_TRIANGLE_STRIP`
makes a triangle of each new vertex and the two before it (the particle renderer draws its
quads that way); `GL_LINES` takes pairs (the debug line renderer). The order of a triangle's
vertices matters later: it decides which side is its front (§2.8).

**What the hardware does.** It waits until the three vertices of a triangle have left the vertex
shader (or come out of the post-transform cache), then hands the triangle to the next fixed unit.
It is simple bookkeeping, but it is also where the GPU starts to work on *triangles* rather than
vertices, and where it can throw whole triangles away before any pixel work starts.

### 2.6 Stage 4: clipping

**What it does.** Keeps only the part of each triangle inside the **view volume**, the region of
clip space that can appear on screen. Jeddy calls `glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)`
at startup (§27.1), and with that setting a point is inside when

$$-w_c \le x_c \le w_c, \qquad -w_c \le y_c \le w_c, \qquad 0 \le z_c \le w_c .$$

These six inequalities are six planes. They are written with $w_c$ instead of after dividing by
it, and that is the point: the division comes in the next stage, and it must not happen first.
A vertex behind the camera has $w_c < 0$ (chapter 04: $w_c$ is the distance in front of the
camera), and dividing by a negative or nearly zero $w_c$ would flip the point to the other side of
the screen or throw it to infinity. Clipping first cuts such triangles at the near plane, so that
every vertex that reaches the division has $w_c > 0$.

**How a triangle edge is cut.** For one plane, write each vertex's signed distance $d$ to it, as a
number that is $\ge 0$ inside. For the plane $z_c \le w_c$ that number is $d = w_c - z_c$. Along an
edge from vertex $A$ to vertex $B$, every clip coordinate changes linearly:
$P(t) = A + t\,(B - A)$ for $t$ from 0 to 1, so $d$ changes linearly too:
$d(t) = d_A + t\,(d_B - d_A)$. If $d_A$ and $d_B$ have different signs, the edge crosses the plane
where $d(t) = 0$:

$$t = \frac{d_A}{d_A - d_B}.$$

The new vertex is $P(t)$, and every other vertex output (colors, texture coordinates) is cut at
the same $t$. Cutting a triangle against one plane can leave a four-sided polygon, which the
hardware splits into two triangles; against all six planes a triangle can become a polygon of up
to nine vertices.

*Worked example.* Jeddy's reversed-Z projection writes $z_c = n$ (the near distance) for every
vertex and $w_c = -z_{\text{view}}$ (chapter 04). Take $n = 0.1$, a vertex $A$ two units in front
of the camera with $(x_c, y_c, z_c, w_c) = (0.4,\ 0,\ 0.1,\ 2)$, and a vertex $B$ one unit
*behind* it with $(-0.2,\ 0,\ 0.1,\ -1)$. Then $d_A = 2 - 0.1 = 1.9$ and $d_B = -1 - 0.1 = -1.1$,
so $t = 1.9 / 3.0 \approx 0.6333$. The new vertex is

$$P = (0.4 - 0.6333 \cdot 0.6,\ \ 0,\ \ 0.1,\ \ 2 - 0.6333 \cdot 3) = (0.02,\ 0,\ 0.1,\ 0.1),$$

with $w_c = 0.1 = n$: exactly on the near plane, as it must be. After the division of §2.7 its
depth is $z_c / w_c = 1$, the reversed-Z value of the near plane.

**What the hardware does.** Real clipping (making new vertices) is expensive, so GPUs avoid most
of it. Triangles entirely outside one plane are dropped whole. Triangles that only cross the
left, right, top or bottom planes are usually *not* cut: the rasterizer works in a range of
window coordinates far larger than the screen (a **guard band**) and simply never generates the
pixels outside the viewport. The depth planes need no geometry either: hardware usually clips
depth per fragment, dropping fragments whose depth falls outside $[0, 1]$. The one cut that must
make new vertices is the one that keeps $w > 0$ for the division: the near plane. (Jeddy's
perspective projection is infinite: $z_c = n > 0$ for every vertex, so its far plane $0 \le z_c$
never removes anything; the orthographic projection has a real far plane.)

You can see the guard band at work in Jeddy's full-screen passes. Post-processing draws one
triangle with an empty vertex array, and
[`engine/fullscreen.vert`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/engine/fullscreen.vert)
makes its corners from `gl_VertexID` alone:

```glsl
vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
vUv = p;
gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
```

Vertex 0 gives $p = (0, 0)$, vertex 1 gives $((1 \ll 1) \mathbin{\&} 2,\ 1 \mathbin{\&} 2) = (2, 0)$
and vertex 2 gives $(0, 2)$ (L00 §6 for the bit operations). The clip positions are $(-1,-1)$,
$(3,-1)$ and $(-1,3)$: a triangle twice as wide and tall as the screen, whose right-angled corner
covers the whole viewport. Exactly half of its area lies outside (it is 8 square units of NDC
against the screen's 4); the guard band makes that free. One
triangle is used instead of two forming a quad because two triangles share a diagonal edge, and
§2.11 explains why pixels along a shared edge cost extra work.

### 2.7 Stage 5: the perspective divide and the viewport transform

**The perspective divide.** Each surviving vertex is divided by its $w_c$ to get **normalized
device coordinates** (NDC):

$$(x_n,\ y_n,\ z_n) = \left(\frac{x_c}{w_c},\ \frac{y_c}{w_c},\ \frac{z_c}{w_c}\right).$$

Inside the view volume $x_n$ and $y_n$ lie in $[-1, 1]$ and, with Jeddy's clip control, $z_n$ in
$[0, 1]$. This division is what makes far things small: chapter 04 builds the projection so that
$w_c$ grows with distance.

**The viewport transform.** `glViewport(x0, y0, W, H)` names a rectangle of the window in pixels.
NDC must map onto it: $x_n = -1$ to the left edge $x_0$, $x_n = +1$ to the right edge $x_0 + W$.
A straight-line map between two ranges is "shift to start at 0, scale, shift to the new start":

$$x_w = x_0 + \frac{(x_n + 1)\,W}{2}, \qquad y_w = y_0 + \frac{(y_n + 1)\,H}{2}.$$

Check the ends: $x_n = -1$ gives $x_0 + 0$, and $x_n = 1$ gives $x_0 + W$. Depth goes through
`glDepthRange`, which Jeddy leaves at its default $[0, 1]$; with the zero-to-one clip control the
window depth is $z_w = (f - n_r)\,z_n + n_r$ with $n_r = 0$, $f = 1$, so simply

$$z_w = z_n .$$

(With OpenGL's traditional $[-1, 1]$ clip depth it would be $(z_n + 1)/2$, which throws away
precision near 0; chapter 04 explains why reversed-Z wants the zero-to-one form.) `GL_LOWER_LEFT`
puts window $y = 0$ at the bottom, so $y_w$ grows upwards.

*Worked example.* A vertex shader writes $(x_c, y_c, z_c, w_c) = (1.0,\ 0.5,\ 1.6,\ 2.0)$. With
Jeddy's projection $z_c$ is the near distance, so this is a camera with its near plane at 1.6
looking at a point 2 units away. The divide gives $(0.5,\ 0.25,\ 0.8)$. All three are in range,
so the point is visible. In a 960 × 540 viewport at the origin,
$x_w = 1.5 \cdot 480 = 720$, $y_w = 1.25 \cdot 270 = 337.5$, and the value that will be compared
and stored in the depth buffer is $0.8$.

For `HelloTriangle` at angle 0 the three vertices land at window positions $(318, 135)$,
$(642, 135)$ and $(480, 459)$: vertex 0 has $x_n = -0.3375$, so $x_w = (1 - 0.3375) \cdot 480 = 318$.

**What the hardware does.** The divide and the scale are a few multiplications per vertex. Then
the window coordinates are converted to **fixed point**, integers counting fractions of a pixel,
because the rasterizer's coverage arithmetic must be exact (§2.9). The number of fractional bits
is `GL_SUBPIXEL_BITS`: the specification asks for at least 4, and desktop GPUs commonly use 8. With
8 bits a vertex at $x_w = 318.3$ is stored as $\mathrm{round}(318.3 \cdot 256) = 81485$, which is
$318.30078$ pixels: positions snap to a grid of $1/256$ pixel.

### 2.8 Stage 6: face culling

**What it does.** A closed mesh (a barrel, a character) has triangles facing the camera and
triangles facing away. The ones facing away are always hidden behind the others, so drawing them
is wasted work. **Face culling** drops them before any pixel is generated.

How does the GPU know which way a triangle faces? From the order of its vertices on the screen.
Meshes are modelled so that, seen from outside, each triangle's vertices go around
counter-clockwise. Seen from behind, the same three vertices go around clockwise. So the GPU
computes the triangle's **signed area** in window coordinates: positive for counter-clockwise,
negative for clockwise.

**The signed area, derived.** Take the triangle $a, b, c$ and the two edge vectors
$\mathbf{u} = b - a$ and $\mathbf{v} = c - a$. The 2D cross product
$u_x v_y - u_y v_x$ (the $z$ component of the 3D cross product of chapter 02 with $z = 0$) equals
the area of the parallelogram spanned by $\mathbf{u}$ and $\mathbf{v}$, with a sign: positive when
turning from $\mathbf{u}$ to $\mathbf{v}$ is counter-clockwise. The triangle is half the
parallelogram:

$$A = \tfrac{1}{2}\left[(b_x - a_x)(c_y - a_y) - (c_x - a_x)(b_y - a_y)\right].$$

(Expanding the brackets gives the "shoelace" form in the specification,
$\tfrac12 \sum_i (x_i y_{i+1} - x_{i+1} y_i)$ with indices wrapping around.)

*Worked example.* `HelloTriangle`'s window positions from §2.7: $a = (318, 135)$,
$b = (642, 135)$, $c = (480, 459)$. Then $\mathbf{u} = (324, 0)$, $\mathbf{v} = (162, 324)$ and
$A = \tfrac12 (324 \cdot 324 - 162 \cdot 0) = 52{,}488$ pixels². Positive: counter-clockwise,
front-facing. (This is also, roughly, the number of pixels the triangle covers.)

`glFrontFace` says which winding is the front (default `GL_CCW`); `glCullFace` says which side to
drop (`GL_BACK`, `GL_FRONT`); `glEnable(GL_CULL_FACE)` turns it on. Jeddy's opaque pipeline state
culls back faces (§22). Transparent surfaces and double-sided materials do not cull, because
their back is visible.

**What the hardware does.** The area is computed in the triangle **setup** unit, after the
viewport transform and before rasterization. Triangles with zero area (all three vertices on one
line, common after snapping tiny triangles to the subpixel grid) are dropped here too.

**What can go wrong.** A mesh exported with clockwise winding disappears, or shows only its
inside. A transform with a negative determinant (a mirrored object) flips every triangle's
winding on screen, so culling removes its front faces: DESIGN §9.2 lists mirrored instances as a
known limitation of the engine for exactly this reason.

### 2.9 Stage 7: rasterization

**What it does.** Decides which pixels a triangle covers and creates one fragment for each. The
rule is about **pixel centers**: the pixel whose lower-left corner is $(i, j)$ has its center at
$(i + 0.5,\ j + 0.5)$, and it is covered when its center lies inside the triangle.

**Inside, as arithmetic: edge functions.** For the directed edge from $a$ to $b$, define

$$E_{ab}(p) = (b_x - a_x)(p_y - a_y) - (b_y - a_y)(p_x - a_x).$$

This is the signed-area formula of §2.8 for the triangle $a, b, p$, times two. It is positive
when $p$ is to the left of the edge (seen walking from $a$ to $b$), zero on the edge, negative to
the right. For a counter-clockwise triangle the inside is to the left of all three edges, so

$$p \text{ is inside} \iff E_{ab}(p) \ge 0,\ \ E_{bc}(p) \ge 0,\ \ E_{ca}(p) \ge 0 .$$

*Worked example.* Take a small triangle $a = (1, 1)$, $b = (5, 1)$, $c = (1, 5)$; its doubled area
is $E_{ab}(c) = 4 \cdot 4 - 0 \cdot 0 = 16$. For the pixel center $p = (2.5, 1.5)$:

- $E_{ab}(p) = 4 \cdot 0.5 - 0 \cdot 1.5 = 2$,
- $E_{bc}(p) = (1 - 5)(1.5 - 1) - (5 - 1)(2.5 - 5) = -2 + 10 = 8$,
- $E_{ca}(p) = (1 - 1)(1.5 - 5) - (1 - 5)(2.5 - 1) = 0 + 6 = 6$.

All positive: covered. Doing this for every center in the bounding box finds 6 centers strictly
inside, $(1.5, 1.5)$, $(1.5, 2.5)$, $(1.5, 3.5)$, $(2.5, 1.5)$, $(2.5, 2.5)$, $(3.5, 1.5)$, and 4
centers exactly *on* the long edge $x + y = 6$: $(1.5, 4.5)$, $(2.5, 3.5)$, $(3.5, 2.5)$,
$(4.5, 1.5)$.

**Ties.** Who owns a center that lies exactly on an edge? If both triangles that share the edge
took it, the pixel would be drawn twice (visible with blending); if neither did, there would be a
crack. The OpenGL specification requires that exactly one of two triangles sharing an edge produce
the fragment, and leaves the precise rule to the implementation; hardware uses a fixed convention
such as Direct3D's "top-left rule" (a center on a top edge or a left edge belongs to the
triangle). The 4 centers above go to this triangle or to its neighbour across the long edge,
never to both.

**Why the hardware loves edge functions.** $E_{ab}$ is *linear* in $p$: moving one pixel to the
right changes it by the constant $-(b_y - a_y)$, moving one pixel up by $(b_x - a_x)$. So the
rasterizer evaluates three functions once per triangle and then only adds constants, which is
cheap, parallel and exact in fixed point. Real rasterizers first test whole tiles of pixels (say
8 × 8) against the edges to skip empty areas, then test the pixels of the tiles that are partly
covered.

**Quads.** Fragments are never produced alone: the rasterizer emits them in **2 × 2 blocks of
pixels** (quads). §2.11 explains why: the fragment shader needs neighbours to compute
derivatives.

**Scissor.** If `GL_SCISSOR_TEST` is enabled, fragments outside the rectangle given by
`glScissor` are dropped here. Jeddy's local shadows use it to confine each light's shadow pass to
its tile of the shadow atlas (chapter [07](07-lighting-and-shadows.md)).

**What can go wrong.** Two triangles that should share an edge but whose shared vertices differ in
the last bit (computed by two different expressions) can leave **cracks**: one-pixel gaps along
the edge, because the tie rule only works for identical edges. Very thin triangles cover few or
no centers and flicker as they move ("sparkles").

### 2.10 Stage 8: interpolating the vertex outputs

Every fragment needs the vertex shader's outputs at its own position: the color, the texture
coordinates, the normal. They are blends of the three vertices' values.

**Barycentric weights.** The edge functions of §2.9 already give the blend. For a point $p$
inside the triangle define

$$\lambda_a = \frac{E_{bc}(p)}{E_{ab}(c)}, \qquad \lambda_b = \frac{E_{ca}(p)}{E_{ab}(c)}, \qquad \lambda_c = \frac{E_{ab}(p)}{E_{ab}(c)} .$$

Each is the area of the small triangle opposite one vertex divided by the whole area, so each is
between 0 and 1, they add up to 1, and $\lambda_a a + \lambda_b b + \lambda_c c = p$. These are
the **barycentric coordinates** of $p$. In the example of §2.9, $p = (2.5, 1.5)$ has
$\lambda = (8/16,\ 6/16,\ 2/16) = (0.5,\ 0.375,\ 0.125)$; check:
$0.5 \cdot (1,1) + 0.375 \cdot (5,1) + 0.125 \cdot (1,5) = (2.5,\ 1.5)$.

**Why a plain blend is wrong.** The obvious interpolation is $a_p = \lambda_a a_a + \lambda_b a_b
+ \lambda_c a_c$ for an output $a$. That is right for a triangle facing the camera squarely, and
wrong for one seen at an angle, such as a floor: equal steps on screen are not equal steps on the
floor, because the far part is squeezed by the perspective divide. The fix follows from where the
divide happens.

**Perspective-correct interpolation, derived for one edge.** Take an edge from vertex 0 to vertex
1 and a point on it, $t$ of the way along *in 3D*: its clip coordinates are
$x(t) = (1-t)x_0 + t\,x_1$ and $w(t) = (1-t)w_0 + t\,w_1$, because clip coordinates are a matrix
times the 3D position, and a matrix keeps straight-line blends straight. Its screen position is
$s(t) = x(t) / w(t)$. We want the screen-space weight $\lambda$, defined by
$s(t) = (1-\lambda)\,s_0 + \lambda\,s_1$ with $s_0 = x_0/w_0$ and $s_1 = x_1/w_1$. Try

$$1 - \lambda = \frac{(1-t)\,w_0}{w(t)}, \qquad \lambda = \frac{t\,w_1}{w(t)} .$$

They add up to $\frac{(1-t)w_0 + t w_1}{w(t)} = 1$, and
$(1-\lambda)\frac{x_0}{w_0} + \lambda\frac{x_1}{w_1} = \frac{(1-t)x_0 + t x_1}{w(t)} = s(t)$.
So they are the screen weights. Divide each by its vertex's $w$:

$$\frac{1-\lambda}{w_0} = \frac{1-t}{w(t)}, \qquad \frac{\lambda}{w_1} = \frac{t}{w(t)}, \qquad \frac{1-\lambda}{w_0} + \frac{\lambda}{w_1} = \frac{1}{w(t)} .$$

The output we want is the 3D blend $a(t) = (1-t)\,a_0 + t\,a_1$. Multiply and divide by $w(t)$
and substitute:

$$a(t) = \frac{(1-\lambda)\dfrac{a_0}{w_0} + \lambda\dfrac{a_1}{w_1}}{(1-\lambda)\dfrac{1}{w_0} + \lambda\dfrac{1}{w_1}} .$$

In words: $a/w$ and $1/w$ *do* blend linearly on screen; divide the first by the second at each
fragment. With three vertices the same argument gives the formula of the OpenGL specification:

$$a = \frac{\lambda_a\, a_a / w_a + \lambda_b\, a_b / w_b + \lambda_c\, a_c / w_c}{\lambda_a / w_a + \lambda_b / w_b + \lambda_c / w_c} .$$

*Worked example.* A floor edge goes from a near vertex with $w_0 = 1$ and texture coordinate
$u_0 = 0$ to a far vertex with $w_1 = 4$ and $u_1 = 1$. At the middle of the edge *on screen*,
$\lambda = 0.5$. A plain blend would give $u = 0.5$. The correct value:

$$u = \frac{0.5 \cdot 0/1 + 0.5 \cdot 1/4}{0.5/1 + 0.5/4} = \frac{0.125}{0.625} = 0.2 .$$

The middle of the screen shows the texture 20 % of the way along: the near part of the floor
takes up more of the screen, as it should. (And $1/0.625 = 1.6$ is the $w$ of that point.)

**Flat outputs.** An output declared `flat` is not interpolated at all: every fragment receives
the value of one vertex, the **provoking vertex** (by default the last of the triangle). Integers
cannot be blended meaningfully, and values that are the same for a whole object need no blending,
so Jeddy declares its material index and instance flags (integers) and its camera fade (a float
that is constant per object) `flat` (`pbr/pbr.vert`, §8.2). There is also a `noperspective` qualifier for the plain screen
blend, used for screen-space effects.

**Depth is the exception.** The window depth $z_w$ is interpolated with the plain screen-space
blend, without the division. That is not an approximation: $z_w = z_c / w_c$ is itself a ratio
of two things that blend linearly, exactly like $a/w$ above, so it blends linearly on screen.
This is also why depth precision depends on $1/w$: chapter 04 builds reversed-Z on it.

**What the hardware does.** The setup unit computes, once per triangle, the plane equations of
$a/w$ and $1/w$ for every output. Per fragment the GPU evaluates them and divides; on many GPUs
this happens inside the fragment shader's first instructions, using the barycentric weights the
rasterizer provides.

### 2.11 Stage 9: the fragment shader

**What it does.** Your program runs once per fragment, with the interpolated inputs, and writes
one color per output (`layout(location = 0) out vec4 color;`). It can read textures and buffers,
compute lighting, and **discard** the fragment (an alpha-tested leaf, a dissolving roof: Jeddy's
`pbr/depth.frag` and `pbr/pbr.frag` do this). `HelloTriangle`'s fragment shader only re-encodes its
interpolated color (§15.2).

**Scale.** The triangle of §2.8 covers about 52,500 pixels. The vertex shader ran 3 times; the
fragment shader runs over fifty thousand times. At 1920 × 1080 the screen has 2,073,600 pixels,
and a scene with layers of overlapping surfaces shades several times that. That is why most of a
renderer's GPU time is fragment work, and why the depth tests of the next stage try to run
*before* this stage.

**Quads and derivatives.** Fragments run in 2 × 2 quads. Inside a quad, the GPU can subtract the
value computed here from the one computed by the pixel to the right: that is the **derivative**
$\partial v / \partial x$ (GLSL `dFdx`, and `dFdy` with the pixel above). Texture sampling uses
derivatives of the texture coordinates to choose a mip level (§6.3). For this to work every
pixel of the quad must run the shader, even pixels the triangle does not cover; those **helper
invocations** compute values for their neighbours and write nothing.

The price: a triangle that covers one pixel still occupies a whole quad, 4 invocations for 1
useful fragment, 75 % wasted. Along every edge some quads are partly covered, and two triangles
sharing an edge shade the quads on it twice. Small or thin triangles therefore cost far more per
pixel than large ones, which is why level-of-detail meshes exist (chapter 06) and why full-screen
passes use the single triangle of §2.6 instead of a two-triangle quad.

**What the hardware does.** Quads are packed into groups of 32 or 64 lanes that run in lockstep
on a shader core. A branch that goes different ways in one group runs both ways, with lanes
masked off. Texture reads are sent to the **texture units**, fixed hardware that computes
addresses, fetches texels through a cache and filters them (§6.4); while a group waits for its
texels, the core switches to another group. That switching is how GPUs hide memory latency, and
it only works when many groups are in flight.

**What can go wrong.** Reading a texture with implicit derivatives inside a branch that only some
pixels of a quad take gives undefined derivatives: wrong mip levels, seams. A `discard` (or a
write to `gl_FragDepth`) can disable early depth testing (§2.12), so expensive shaders behind
cheap ones are no longer skipped.

### 2.12 Stage 10: the stencil and depth tests

After the fragment shader the GPU decides whether the fragment may change the image. Two tests
run here, in this order.

**The stencil test** compares a small integer stored per pixel (the **stencil buffer**) with a
reference value, and can increment, replace or keep it depending on the outcome. Engines use it
for masks: portals, outlines, deferred-light volumes. Jeddy uses none: the window asks for zero
stencil bits (§13) and no render target has a stencil attachment. The engine draws its outlines
from a mask texture instead (chapter [08](08-effects-and-post.md)).

**The depth test** keeps only the nearest surface. The **depth buffer** stores, per pixel, the
depth $z_w$ of the nearest fragment drawn so far. A new fragment compares its own $z_w$ with the
stored value using the **depth function**; if the test fails the fragment is dropped, if it passes
and depth writes are on (`glDepthMask(true)`), its depth replaces the stored value.

With ordinary depth, near is 0 and far is 1, the buffer is cleared to 1 (as far as possible) and
the function is `GL_LESS` ("keep it if it is nearer"). Jeddy uses **reversed-Z** (chapter 04):
near is 1, far is 0. So the depth buffer is cleared to **0** (`glClearDepthf(0f)`, called by
`GpuDevice.useReversedZ`, §17.1) and the function is **`GL_GEQUAL`**: "keep it if its depth is at
least as large as what is there", which now means "at least as near". The depth buffers are
`GL_DEPTH_COMPONENT32F`, 32-bit floats; chapter 04 shows why reversed-Z with a float buffer keeps
the *relative* precision roughly constant over distance.

*Worked example.* A pixel's stored depth is 0.3 (a wall). A fragment of a character in front of
the wall arrives with $z_w = 0.8$. $0.8 \ge 0.3$, so it passes, its color is written and 0.8
replaces 0.3. A later fragment of the floor behind the character arrives with $z_w = 0.5$:
$0.5 \ge 0.8$ is false, so it is dropped, whatever its color.

**Early depth testing.** The test is defined to run after the fragment shader, but running it
*before* saves the whole shading cost of hidden fragments. GPUs therefore test early whenever the
result cannot depend on the shader: when the shader does not write `gl_FragDepth`, and when it
cannot discard a fragment that would have written depth. Many GPUs also keep a coarse
**hierarchical depth** (one conservative value per tile of pixels) and reject whole tiles of a
triangle at once. Jeddy's depth prepass (chapter 06) exploits this: it first draws only depth,
cheaply, then draws the expensive lit pass with `GL_GEQUAL` against the finished depth buffer and
no depth writes, so every hidden fragment fails early (D42 explains why `GEQUAL` and not `EQUAL`:
two different programs need not compute bit-identical depths).

**Polygon offset.** `glPolygonOffset(factor, units)` with `GL_POLYGON_OFFSET_FILL` enabled adds
$o = m \cdot \text{factor} + r \cdot \text{units}$ to every fragment's depth, where $m$ is the
triangle's steepest depth slope in window coordinates and $r$ the smallest resolvable depth
difference. Shadow maps use it to push casters slightly away so that a surface does not shadow
itself; Jeddy's shadow passes set $(-1.5, -2)$, negative because in reversed-Z "farther" means
smaller (chapter 07).

**What can go wrong.** Forgetting to clear depth to 0 under reversed-Z: everything fails
`GEQUAL` against stale values, or against a default clear of 1, and nothing is drawn. Two
surfaces at almost the same depth flicker as either wins (**z-fighting**). A depth function left
at `GL_LESS` by some other code draws only the farthest surfaces; §23's cache prevents that kind
of leftover.

### 2.13 Stage 11: blending

**What it does.** Combines the fragment's color with the color already in the image. With
blending off the new color simply replaces the old one. With blending on:

$$\mathbf{c}_{\text{out}} = \mathbf{c}_{\text{src}}\, f_{\text{src}} + \mathbf{c}_{\text{dst}}\, f_{\text{dst}}$$

where $\mathbf{c}_{\text{src}}$ is the fragment shader's output (the "source"),
$\mathbf{c}_{\text{dst}}$ the color already in the image (the "destination"), and the two factors
are chosen with `glBlendFunc(sfactor, dfactor)`. The same factors apply to the alpha channel.
`PipelineState` (§22) defines four combinations:

| Mode | `glBlendFunc` | Formula | Users in Jeddy |
|---|---|---|---|
| alpha | `SRC_ALPHA, ONE_MINUS_SRC_ALPHA` | $\mathbf{c}_s \alpha_s + \mathbf{c}_d (1 - \alpha_s)$ | transparent materials (`TRANSPARENT`), the debug overlay |
| premultiplied | `ONE, ONE_MINUS_SRC_ALPHA` | $\mathbf{c}_s + \mathbf{c}_d (1 - \alpha_s)$ | the UI and the GPU particles, whose colors are already multiplied by alpha |
| additive | `ONE, ONE` | $\mathbf{c}_s + \mathbf{c}_d$ | defined for light-like effects; nothing uses it yet |
| multiply | `DST_COLOR, ZERO` | $\mathbf{c}_s \cdot \mathbf{c}_d$ | defined for darkening decals; Jeddy's decals are applied inside the lit shader instead (chapter 08) |

*Worked examples.*

- **Alpha.** A red fragment $(1, 0, 0)$ with $\alpha_s = 0.25$ over a blue pixel $(0, 0, 1)$:
  $(1,0,0) \cdot 0.25 + (0,0,1) \cdot 0.75 = (0.25,\ 0,\ 0.75)$.
- **Premultiplied.** The same fragment premultiplied is $(0.25, 0, 0)$ with $\alpha = 0.25$:
  $(0.25, 0, 0) + (0,0,1) \cdot 0.75 = (0.25,\ 0,\ 0.75)$. The same result, but premultiplied
  colors also filter and blend correctly when *several* layers are combined first (exercise 13).
- **Additive.** A spark $(0.2, 0.1, 0)$ over $(0.5, 0.5, 0.5)$: $(0.7,\ 0.6,\ 0.5)$. Addition
  does not care about order, so additive effects need no sorting.
- **Multiply.** A decal $(0.5, 0.5, 1)$ over $(0.8, 0.6, 0.4)$: $(0.4,\ 0.3,\ 0.4)$.

**Order.** Alpha blending is not commutative: drawing B over A differs from A over B. So
transparent surfaces are sorted back to front (chapter 06's sort keys do it) and drawn after all
opaque ones, usually with depth testing on but depth writes off, so they hide nothing behind
them. **`glColorMask`** can switch off writes per channel; a depth-only pass switches off all
four (§22).

**What the hardware does.** Blending and the final write happen in the **render output units**
(ROPs). They read the destination from the color image, compute the formula, write the result:
a read-modify-write of memory for every blended fragment, which makes blending cost memory
bandwidth even when the shader is trivial. OpenGL guarantees that the result is as if fragments
were blended in the order their primitives were issued, so the hardware must keep fragments of
the same pixel in order even though it shades them in parallel.

### 2.14 Stage 12: the framebuffer and the swap

**What it does.** The surviving color is written into the **framebuffer**: the set of images the
draw renders into. A framebuffer has **color attachments** (zero for a depth-only pass, up to
`GL_MAX_COLOR_ATTACHMENTS`) and at most one depth (and stencil) attachment. There are two kinds:

- the **default framebuffer**, name 0, which belongs to the window and has the front and back
  buffers of §4.3;
- **framebuffer objects** you create, whose attachments are textures (§21). Rendering into a
  texture is how multi-pass rendering works: the depth prepass writes a depth texture, the lit
  pass writes a floating-point color texture, post-processing reads that as a texture and writes
  the window.

**Conversion on write.** The shader's output is a float. If the attachment stores 8-bit
normalized values (`GL_RGBA8`, and the window's back buffer), each channel is clamped to
$[0, 1]$, multiplied by $2^8 - 1 = 255$ and rounded to the nearest integer. `GLTest` (§28.1)
clears to $(0.1, 0.2, 0.3)$ and reads back red 26 and blue 77: $0.1 \cdot 255 = 25.5$ (a hair
more, since `0.1f` is slightly above 0.1, L00 §7.3) rounds to 26, and $0.3 \cdot 255 = 76.5$ to 77.
The test allows a difference of 1, because drivers may round halves differently. Floating-point
attachments (`GL_RGBA16F`) store the value as it is, values above 1 included: that is what makes
HDR lighting possible before tonemapping (chapter 08).

**sRGB.** Lighting is computed in **linear** values, proportional to light energy. Displays expect
**sRGB-encoded** values, which spend more of the 256 steps on dark tones, roughly
$v_{\text{encoded}} \approx v^{1/2.2}$. GL can encode automatically when writing to an sRGB
framebuffer, but Jeddy does not ask for one (D23: Xvfb, used in CI, offers no sRGB visuals, so
asking made window creation fail). The last post pass encodes in its shader instead, and
`HelloTriangle` does the same with an approximation (§15.2). Chapter 08 gives the exact curve.

**Memory.** Framebuffer images are large. At 1920 × 1080, an `RGBA8` image is
$1920 \cdot 1080 \cdot 4 = 8{,}294{,}400$ bytes (7.9 MiB), an `RGBA16F` image twice that (15.8 MiB),
and a 32-bit depth buffer 7.9 MiB. Every blended fragment reads and writes them; a full-screen
post pass reads and writes all of it. GPUs store these images in tiled layouts and usually
compress them losslessly in hardware to save bandwidth, invisibly to the API.

**The swap.** Drawing into the window goes to the hidden **back buffer**. When the frame is done,
`SDL_GL_SwapWindow` hands it over to be displayed and the next frame gets another buffer (§4.3).
Jeddy's window has no multisampling (`SDL_GL_MULTISAMPLEBUFFERS` 0, §13): its anti-aliasing is a
post pass (FXAA, chapter 08).

### 2.15 Beside the pipeline: compute shaders

A **compute shader** uses the same shader cores without any fixed stage: no vertices, no
rasterizer, no blending. You **dispatch** a grid of **work groups**, `glDispatchCompute(gx, gy,
gz)`, and each work group runs a fixed number of **invocations** declared in the shader
(`layout(local_size_x = 64) in;`). Each invocation knows its position (`gl_GlobalInvocationID`)
and reads and writes buffers and images directly. Invocations of one work group can share fast
on-chip memory (`shared` variables) and wait for each other (`barrier()`); different work groups
cannot.

To cover $n$ items with groups of 64 you dispatch $\lceil n / 64 \rceil$ groups, written
`(n + 63) / 64` in integer arithmetic (L00 §2.5); the extra invocations in the last group check
their index and do nothing. Jeddy's static-scene culling does exactly that (lesson 1, §15).

Compute writes go to general memory that the GPU does not keep coherent automatically, so a
later command that reads them needs a `glMemoryBarrier` naming how it will read them. Lesson 1
(§6.3 and §15) explains barriers with Jeddy's culling passes. Jeddy uses compute for light and
decal clustering, GPU culling of the static scene, particles, auto exposure and the BRDF lookup
table (chapters 06–08).

### 2.16 Where Jeddy touches each stage

| Stage | Jeddy code |
|---|---|
| Command processor | multi-draw-indirect from the frame ring (lesson 1 §14, chapter 06) |
| 1 Vertex fetch | `MeshRegistry`'s one vertex array for every mesh: positions, attributes, skin streams (§18) |
| 2 Vertex shader | `pbr/pbr.vert`, `pbr/depth.vert`; per-instance records from a storage buffer (`engine/instance.glsl`) |
| 3 Primitive assembly | `GL_TRIANGLES` everywhere; triangle strips for particles, lines for debug draw |
| 4–5 Clipping, divide, viewport | `GpuDevice.useReversedZ` (`glClipControl`), `StateCache.viewport` (§17.1, §23) |
| 6 Face culling | the cull bits of `PipelineState` (§22) |
| 7 Rasterization | polygon offset, wireframe and scissor bits of `PipelineState` |
| 8 Interpolation | `flat` outputs for integers (`pbr/pbr.vert`) |
| 9 Fragment shader | `pbr/pbr.frag`, `pbr/depth.frag`, the post shaders |
| 10 Depth test | `PipelineState` depth bits, reversed-Z `GL_GEQUAL`, the depth prepass |
| 11 Blending | `PipelineState` blend modes, applied by `StateCache` |
| 12 Framebuffer | `GpuFramebuffers` (§21), allocated per pass by the frame graph (chapter 06) |
| Compute | `ProgramCache.defineCompute` (§25): light clusters, `cull/static_cull.comp`, particles, exposure |

<details><summary>Check: a vertex shader writes <code>gl_Position = (0.2, -0.4, 0.5, 0.5)</code>. Is the vertex inside the view volume, and where does it land in a 1280 × 720 viewport at the origin?</summary>

Inside: $|0.2| \le 0.5$, $|-0.4| \le 0.5$ and $0 \le 0.5 \le 0.5$ (on the near plane). NDC:
$(0.4,\ -0.8,\ 1)$. Window: $x_w = 1.4 \cdot 640 = 896$, $y_w = 0.2 \cdot 360 = 72$, depth 1 (the
nearest possible value in reversed-Z).
</details>

---

## 3. OpenGL 4.6 core: a state machine with objects

### 3.1 Context, objects and names

OpenGL is an API for a **context** (L00 §10.5): one large bundle of state, such as the current
program, the bound vertex array, the blend mode and the viewport, plus the **objects** created in
it: buffers, textures, samplers, vertex arrays, framebuffers, programs, shaders, sync objects.
Objects are identified by **names**, plain unsigned integers (`GLuint`, an `int` in Java) that you
get from `glCreate*` and give back with `glDelete*`. Inside the driver a name is a key into a
table of the driver's own structures; the name is only a handle (lesson 3 builds the engine's own
version of the same idea, with a generation that makes stale handles fail).

Two properties of names matter later. A deleted name is **reused**: delete buffer 7, create a new
buffer, and it may well be 7 again (§23 and D50 deal with the consequence). And deleting an
object that commands in flight still use is legal: the name disappears at once, the memory when
the GPU is done with it (lesson 1, §7.4).

### 3.2 The core profile, and what each version added

OpenGL is more than thirty years old (1.0 appeared in 1992). Its early versions drew with a **fixed-function** pipeline: you sent
vertices one call at a time (`glBegin`, `glVertex3f`, `glEnd`), and lighting, matrices and fog
were built-in switches. Version 3.2 split the API into two **profiles**: the *compatibility*
profile keeps all of that, the **core profile** removes it. In core you must put vertices in
buffers and supply shaders; nothing else draws. Jeddy asks for 4.6 core and nothing older
(`GLLoader.REQUIRED_MAJOR` and `REQUIRED_MINOR`, §12). Here is what each version added that Jeddy
relies on:

| Feature | Core since | Used in Jeddy for |
|---|---|---|
| Uniform buffer objects | 3.1 | per-frame constants (`engine/frame.glsl`) |
| Sampler objects | 3.3 | `GpuSamplers` (§20) |
| Program binaries, `glProgramUniform*` | 4.1 | `ProgramBinaryCache` (§26); setting uniforms without binding the program |
| Immutable texture storage, base instance in draws | 4.2 | `GpuTextures` (§19); `baseInstance` in indirect commands |
| Compute shaders, storage buffers, multi-draw-indirect, `KHR_debug`, separate vertex formats, explicit uniform locations, `glCopyImageSubData` | 4.3 | culling and particles; instance and material data; batched draws; `GLDebug` (§16); `MeshRegistry`'s vertex array (§18); `layout(location = N) uniform`; growing texture arrays (§19.4) |
| Immutable buffer storage, persistent mapping | 4.4 | `GpuBuffers`, `FrameRing`, `UploadRing` (lesson 1, §4 for the storage flags, §3.2 for mapping) |
| Direct state access, `glClipControl` | 4.5 | every object Jeddy creates; reversed-Z |
| `glMultiDrawElementsIndirectCount`, `gl_BaseInstance` in GLSL, anisotropic filtering | 4.6 | `StaticScene`; finding instance records; `SamplerDesc` (§20) |

Two extensions beyond core are used when present: `GL_KHR_parallel_shader_compile` (§8.4) and
`GL_ARB_bindless_texture` (an optional path studied in ADR 0001). §9 lists them for the generator.

### 3.3 Bind-to-edit against direct state access

Classic OpenGL edits an object through a **binding point**. To fill a buffer you bind it to a
target and then call a function that acts on "whatever is bound to that target". This is
illustrative code, not code from the repository:

```java
// Illustrative, not the real code: classic bind-to-edit.
glGenBuffers(1, ids);
int vbo = ids.get(ValueLayout.JAVA_INT, 0);
glBindBuffer(GL_ARRAY_BUFFER, vbo);                        // changes global state
glBufferData(GL_ARRAY_BUFFER, size, data, GL_STATIC_DRAW); // acts on the bound buffer
```

Every edit changes global state as a side effect. Code that edits a buffer can break code that
expected another buffer to be bound, and nobody (a state cache, a reader) can tell an edit binding
from a binding meant for drawing. GL 4.5 added **direct state access** (DSA): functions that take
the object's name directly.

```java
// Illustrative: the DSA form Jeddy uses everywhere.
glCreateBuffers(1, ids);                   // creates the object immediately
int vbo = ids.get(ValueLayout.JAVA_INT, 0);
glNamedBufferStorage(vbo, size, data, 0);  // no binding involved
```

(`glGenBuffers` only reserves a name; the object is created on first bind. `glCreateBuffers`
creates the object at once, which DSA needs, since there is no bind.)

Jeddy follows one rule: **objects are created and edited only through DSA** (`glCreate*`,
`glNamed*`, `glTextureStorage*`, `glVertexArray*`, DESIGN §9.2), and binding happens only to
*use* an object for drawing or dispatching. Bindings then mean exactly one thing, which is what
makes the state cache of §23 possible. The few exceptions are binding points that have no DSA
form: the pixel-unpack buffer that `GpuTextures` binds around a texture upload from the upload
ring (§19.3), and the `GL_PARAMETER_BUFFER` that `StaticScene.submit` binds for
`glMultiDrawElementsIndirectCount`. Both bind, call, and unbind at once.

## 4. The context and the main thread

### 4.1 What "current" means

A context is a structure inside the user-mode driver (L00 §10.4): all the state of §3.1, plus the
command buffer it is filling. A context is **current** on at most one thread at a time, and a GL
call acts on the context current on the *calling* thread: the driver's entry points look it up in
a thread-local slot. A GL call on a thread with no current context does nothing, or crashes.

### 4.2 The SDL rule

SDL adds a stricter rule. On Windows and Linux, `SDL_PollEvent`, `SDL_GL_MakeCurrent` and
`SDL_GL_SwapWindow` must run on the thread that called `SDL_Init(SDL_INIT_VIDEO)`. Jeddy therefore
makes that thread, the **main thread**, the GL thread too (**D6**). Simulation runs on a separate
**game thread**, which hands the renderer a *render packet* per frame (DESIGN §5, chapter
[01](01-architecture.md) and chapter [06](06-renderer-architecture.md)); worker threads never call
GL but may write into mapped GPU memory the main thread prepared (lesson 1). The Javadoc of every
class in `gl` and `render` that calls GL states its **thread ownership**, for example "Thread
ownership: the GL context thread (main thread)".

`Platform.init` calls `SDL_Init(SDL_INIT_VIDEO() | SDL_INIT_EVENTS() | SDL_INIT_GAMEPAD())` and then
`SdlThread.markMainThread()`, which remembers the calling thread in a `volatile` field. Window
methods check it with `SdlThread.assertMainThread(name)`, which throws at once on another thread:
a wrong-thread GL call is a crash or a silent no-op, and failing loudly is far easier to debug.

*Why not a separate render thread?* Many engines render on a thread of their own. Under SDL's rule
that thread could not make its context current or swap, so GL work cannot leave the main thread;
the only choice is which *other* work does. Jeddy moves the simulation to its own thread and keeps
one owner for the window, the events and the context (D6).

### 4.3 Double buffering, the swap and vsync

The window has two color images: the **front buffer**, which the display shows, and the **back
buffer**, which you draw into. `SDL_GL_SwapWindow` (`Window.swap()`, §13) asks for the back buffer
to be shown. With a desktop compositor the image is handed to the compositor rather than copied;
either way the next frame draws into a buffer that is not on screen, so a half-drawn frame is
never visible.

**Vsync** decides *when* the new image appears. The display refreshes at a fixed rate, 60 times a
second for example, and scans the image out top to bottom. Changing the image in the middle of a
scan shows the top of one frame over the bottom of another (**tearing**). With vsync on (swap
interval 1) the swap waits for the vertical blank between two scans: no tearing, but a frame that
misses the blank waits a whole refresh. **Adaptive vsync** (interval −1) waits when the frame is
on time and swaps at once when it is late, accepting a tear instead of a stutter. Jeddy's
`Window.setVSync` asks for what the configuration says and falls back from adaptive to on when the
driver refuses −1 (§13).

The driver also limits how far the CPU can run ahead: when a few frames are already queued, the
swap (or another call) blocks until the GPU catches up (L00 §10.6). Lesson 1 builds the frame
ring's three regions on that fact.

## 5. Calling GL from Java

### 5.1 Why a loader is needed

Your program is not linked against the GPU driver. The system's GL library exports only old entry
points (on Windows, `opengl32.dll` exports OpenGL 1.1), and the driver provides everything else at
run time. To call `glNamedBufferStorage` you first ask the window system for the function's
**address**, a pointer to its machine code inside the driver. SDL wraps that as
`SDL_GL_GetProcAddress`, and it must be asked *after* the context exists and is current: on
Windows the addresses may depend on the context. A **loader** does this lookup for every command.

One more trap shapes the design. On Linux with GLX, `glXGetProcAddress` returns a non-null
address for *any* name that starts with `gl`, even an invented one: it hands out a stub that
dispatches later. So a non-null address does not prove the driver implements a command. For
commands that only an extension provides, the loader must check the extension list instead (D24,
§11).

### 5.2 One downcall, from Java to the driver

L00 §12 introduced the Foreign Function & Memory API. Calling one C function takes four pieces:

- a **`FunctionDescriptor`**, the C signature as value layouts:
  `FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT)` describes `void f(int, int)`,
  `FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_INT)` describes `long f(int, int)`;
- the function's address, a `MemorySegment`;
- `Linker.nativeLinker().downcallHandle(address, descriptor)`, which returns a
  **`MethodHandle`**: a Java object that, when invoked, calls the C function;
- the call itself, `handle.invokeExact(args...)`, whose argument and return types must match the
  handle's type exactly, so no conversions and no boxing happen.

What makes such a call fast is where the handle is kept. If it sits in a **`static final`** field
of a class that has finished initializing, the JIT compiler (L00 §9.1) treats it as a constant.
It can then look inside the handle, see which C function it calls, and compile the Java call site
into a direct native call with the arguments already in the right registers (DESIGN §4.4). A
handle in an ordinary field is an indirect call through an object the JIT cannot see through.

Generating one handle per GL command, about 670 of them, at startup would be slow, and many are
never used. The **holder-class idiom** solves both problems. Each command gets a tiny nested class
whose only member is the `static final` handle. The JVM initializes a class the first time code
uses it, exactly once, safely even if several threads race to it. So the handle is created on the
*first call* of the command, and commands an application never calls are never linked. You will
see the generated holders in §10.6.

**What a regular downcall does inside the JVM.** Java threads cooperate with the garbage collector:
at **safepoints** (L00 §8.2's pauses) the collector may need every thread to stop where it can
inspect its stack. Native code cannot be stopped that way, so before calling C the thread records
where its Java stack ends and switches its state to "in native"; the collector may then run while
the C function executes. On return the thread switches back and checks whether it must stop for a
safepoint first. For a C function that runs for nanoseconds, these transitions are most of the
cost: a trivial C function measured 16.5 ns per call through a regular downcall (M3-05,
[`bench/README.md`](../../bench/README.md), §29).

(The launchers also set `-Djava.lang.invoke.MethodHandle.CUSTOMIZE_THRESHOLD=-1`, D69: the JDK
otherwise compiles a hidden helper class for a handle after its 127th call from interpreted or
lightly compiled code, which for natives called a few times a second came minutes into the perf
run as 40–150 KB of garbage. The holders' handles are constants, so the optimizing compiler inlines them either way.)

### 5.3 Critical linking

`Linker.Option.critical(allowHeapAccess)` links a downcall *without* the transitions: the thread
stays in Java state for the whole call. The measured cost of the same trivial function drops from
16.5 ns to 3.6 ns, about 13 ns saved per call (§29). With `allowHeapAccess = true` the call may
even receive a heap array wrapped by `MemorySegment.ofArray(float[])`, because the collector
cannot move the array while no safepoint can happen.

The price follows from the same fact: while the call runs, the garbage collector cannot reach a
safepoint, so every other thread that needs one waits. A critical function must therefore be
very short, must never block, and must never call back into Java. That rules out draws (the
driver may wait for space in its queue), syncs and waits, buffer and texture uploads (they can
stall on memory in use), mapping, the swap, and anything else that can take long. What remains are
state setters, binds and uniform setters: the allow-list in §9 (DESIGN §4.4).

**The debug callback forbids it.** In a debug context the driver reports problems through a
callback (§16), and in synchronous mode it calls that callback *from inside* the GL call that
caused the problem: native code calling Java, an **upcall**. An upcall from inside a critical
downcall finds the thread in a state the JVM does not expect, and can crash the JVM. So dev builds
and GPU tests turn critical linking off with `-Djeddy.gl.critical=false` (**D12**), and
`GLDebug.enable` refuses to run while it is on.

### 5.4 Pointers that are really numbers

Many GL parameters are typed `const void *` in C but are not pointers at all. The `indirect`
argument of `glMultiDrawElementsIndirect` is a byte offset into the buffer bound to
`GL_DRAW_INDIRECT_BUFFER`; the `indices` argument of `glDrawElements` is an offset into the
element buffer; the `pixels` argument of a texture upload is an offset into the pixel-unpack
buffer when one is bound. Even real pointers often come as a raw address the caller already has
(`segment.address()`).

Passing such a number as a `MemorySegment` would mean `MemorySegment.ofAddress(offset)`, a new
object on every draw: allocation in the render loop, which CLAUDE.md forbids. So the generator
emits a second version of each such command that takes a **`long`** (§10.7). That `long` is a
buffer offset when a buffer is bound to the matching target, and otherwise a raw native address
(**D24**, **D29**). `GLsync`, the fence object of lesson 1, is a pointer to an opaque driver
structure; the generator maps it to a `long` as well, so creating and waiting on fences allocates
nothing.

## 6. Textures and sampling

### 6.1 Texels, coordinates and kinds of texture

A **texture** is one or more images the GPU can **sample**: read with filtering at fractional
positions. Its pixels are called **texels**. Shaders address a 2D texture with **normalized
coordinates** $(s, t)$ from 0 to 1 across the image, whatever its size; the texture unit converts
them to texel space by multiplying by the size: $u = s \cdot W$, $v = t \cdot H$. Texel $i$ covers
$u$ from $i$ to $i + 1$, so its center is at $u = i + 0.5$, just like pixel centers (§2.9).

Jeddy uses five kinds of texture (`GpuTextures`, §19):

| Kind | GL target | GLSL sampler | Addressed by | Jeddy uses it for |
|---|---|---|---|---|
| `KIND_2D` | `GL_TEXTURE_2D` | `sampler2D` | $(s, t)$ | render targets, lookup tables |
| `KIND_2D_ARRAY` | `GL_TEXTURE_2D_ARRAY` | `sampler2DArray` | $(s, t)$ and a layer number | material texture buckets, sun cascades |
| `KIND_CUBE` | `GL_TEXTURE_CUBE_MAP` | `samplerCube` | a 3D direction | environment lighting |
| `KIND_CUBE_ARRAY` | `GL_TEXTURE_CUBE_MAP_ARRAY` | `samplerCubeArray` | a direction and a layer | KTX2 cube arrays (no engine feature uses one yet) |
| `KIND_3D` | `GL_TEXTURE_3D` | `sampler3D` | $(s, t, r)$ | the color-grading lookup table |

An **array texture** holds $N$ layers of the same size, format and level count in one object. A
shader samples it with `texture(map, vec3(s, t, layer))`, where the layer is a plain number, not
normalized. The material system keeps every material's textures in array "buckets" of equal size
and format, so one binding serves many materials (D41, chapter 06, ADR 0001).

### 6.2 Mip levels: how many, and how much memory

A distant surface covers few pixels but its texture has many texels. Sampling only the texel under
each pixel center would skip most of them, and as the camera moves, *which* texels are skipped
changes every frame: the surface shimmers. The cure is to prepare smaller copies of the image in
advance: **mip levels**. Level 0 is the full image; each next level has half the width and half the
height (rounded down, never below 1), each texel the average of a 2 × 2 block of the level above.
The sampler then reads from the level whose texels are about the size of a pixel (§6.3).

**How many levels.** The chain stops at 1 × 1. Halving the larger side $m = \max(W, H)$ until it
reaches 1 takes $\lfloor \log_2 m \rfloor$ steps (L00 §2.3), so the full chain has

$$L = \lfloor \log_2 \max(W, H) \rfloor + 1$$

levels. A 1024 × 1024 texture has $10 + 1 = 11$ levels: 1024, 512, …, 2, 1. A 1920 × 1080 render
target also has 11: its level 10 is $\max(1, 1920 \gg 10) \times \max(1, 1080 \gg 10) = 1 \times 1$.
Level $l$ has size $\max(1, W \gg l) \times \max(1, H \gg l)$; the code computes exactly this.

**How much memory.** Each level has a quarter of the texels of the level above. The whole chain is
therefore at most

$$S = 1 + \tfrac14 + \tfrac1{16} + \dots$$

times the size of level 0. Multiply by $\tfrac14$: $\tfrac14 S = \tfrac14 + \tfrac1{16} + \dots$,
which is $S$ without its first term, so $S - \tfrac14 S = 1$, $\tfrac34 S = 1$ and $S = \tfrac43$.
Mipmaps add a third. A 1024 × 1024 `RGBA8` image is $1024 \cdot 1024 \cdot 4 = 4{,}194{,}304$ bytes
(4 MiB); with its 11 levels it is 5,592,404 bytes, 5.33 MiB.

### 6.3 Which level: derivatives and λ

The texture unit picks the level from how fast the texture coordinates change from one pixel to
the next. That is what the quads of §2.11 are for: inside a 2 × 2 quad the GPU knows
$\partial u/\partial x$ (how many texels one step to the right moves) and $\partial u/\partial y$
(one step up), and the same for $v$. The specification defines the **scale factor**

$$\rho = \max\left(\sqrt{\left(\frac{\partial u}{\partial x}\right)^2 + \left(\frac{\partial v}{\partial x}\right)^2},\ \sqrt{\left(\frac{\partial u}{\partial y}\right)^2 + \left(\frac{\partial v}{\partial y}\right)^2}\right),$$

the larger of the two distances, in texels, that one pixel step travels in the texture. The
**level of detail** is

$$\lambda = \log_2 \rho$$

(plus an optional bias). Why a logarithm: each level halves the texel density, so going down
$\lambda$ levels divides $\rho$ by $2^\lambda$, and at level $\lambda$ one pixel step moves
$\rho / 2^\lambda = 1$ texel.

*Worked example.* A 1024 × 1024 texture is drawn on a square 256 pixels wide, facing the camera.
One pixel step to the right moves $1024 / 256 = 4$ texels in $u$ and none in $v$; one step up moves
4 texels in $v$. So $\rho = 4$ and $\lambda = \log_2 4 = 2$: level 2, which is 256 × 256, one texel
per pixel. Seen at a slant, $\partial u / \partial x$ might be 4 while $\partial v / \partial y$ is
12; then $\rho = 12$ and $\lambda \approx 3.58$.

When $\lambda \le 0$ the texture is **magnified** (a texel covers more than a pixel) and the
magnification filter applies; otherwise it is **minified** and the minification filter, which
may use mip levels, applies.

### 6.4 Filtering: nearest, bilinear, trilinear, anisotropic

**Nearest** takes the one texel containing the sample point: blocky up close, but exact. Jeddy
reads screen-sized render targets that hold per-pixel data rather than colors (depth, for
example, in the style of a deferred renderer's "G-buffer") that way, one texel per pixel,
`SamplerDesc.NEAREST_CLAMP`.

**Bilinear** blends the four texels around the point. In one dimension first: the texel centers
are at $i + 0.5$, so the two centers around $u$ are those of texels $i_0 = \lfloor u - 0.5 \rfloor$
and $i_0 + 1$, and the weight of the second is the fractional part $\alpha = (u - 0.5) - i_0$:

$$\text{value} = (1 - \alpha)\,T[i_0] + \alpha\,T[i_0 + 1].$$

*Worked example.* A row of 4 texels holds $10, 20, 30, 40$ and is sampled at $s = 0.3$. Then
$u = 0.3 \cdot 4 = 1.2$, $u - 0.5 = 0.7$, $i_0 = 0$, $\alpha = 0.7$, and the value is
$0.3 \cdot 10 + 0.7 \cdot 20 = 17$. In two dimensions the same is done along $v$ with a weight
$\beta$, and the four texels get weights $(1-\alpha)(1-\beta)$, $\alpha(1-\beta)$,
$(1-\alpha)\beta$ and $\alpha\beta$. With texels $10, 20$ (lower row) and $30, 40$ (upper row),
$\alpha = 0.7$ and $\beta = 0.25$: $0.225 \cdot 10 + 0.525 \cdot 20 + 0.075 \cdot 30 + 0.175 \cdot 40
= 22$.

**Mipmapped filtering.** With `GL_LINEAR_MIPMAP_NEAREST` the unit rounds $\lambda$ to the nearest
level and filters bilinearly there. With **trilinear** filtering (`GL_LINEAR_MIPMAP_LINEAR`) it
filters bilinearly in the two levels around $\lambda$ and blends them by its fractional part: at
$\lambda = 2.3$, 70 % of level 2 and 30 % of level 3. That removes the visible line where the
level changes. It reads 8 texels per sample instead of 4.

**Anisotropic** filtering handles surfaces seen at a slant, where a pixel's footprint in the
texture is long and thin ($\rho$ was 12 one way and 4 the other in §6.3). Trilinear filtering
must pick the level for the long direction and blurs the short one. Anisotropic filtering picks
the level for the *short* direction and takes several samples along the long one, up to a maximum
(`GL_TEXTURE_MAX_ANISOTROPY`, core in 4.6; drivers commonly allow 16). Floors in an isometric game
are seen at a slant all the time, so material textures use it (§20).

**What the hardware does.** The texture units compute addresses, fetch texels through a texture
cache, decode compressed blocks (§6.7) and blend, all in fixed hardware, while the shader core
runs other work. Neighbouring pixels read neighbouring texels, so most fetches hit the cache;
textures are stored in tiled or swizzled layouts so that a 2D neighbourhood sits close in memory.
Filtering more (trilinear, anisotropic) costs more fetches; filtering from the right mip level
costs *fewer* cache misses than sampling a too-large level, which is why mipmaps save bandwidth as
well as shimmer.

### 6.5 Wrapping, borders and depth comparison

Coordinates outside $[0, 1]$ are mapped back by the **wrap mode**, chosen per axis: `REPEAT`
keeps the fractional part (tiling), `MIRRORED_REPEAT` tiles with every other copy flipped,
`CLAMP_TO_EDGE` clamps to the edge texels, and `CLAMP_TO_BORDER` returns a fixed **border color**,
$(0, 0, 0, 0)$ unless set otherwise. Bilinear filtering near an edge also reads the texel "beyond"
it, so the wrap mode matters even inside $[0, 1]$: a non-tiling image filtered with `REPEAT` blends
its left edge into its right.

**Depth comparison.** A shadow map is a depth texture. With
`GL_TEXTURE_COMPARE_MODE = GL_COMPARE_REF_TO_TEXTURE` the shader passes a reference depth along
with the coordinates, and the sampler returns 1 where `reference FUNC texel` holds and 0
elsewhere; with linear filtering the hardware compares several neighbouring texels and blends the
0s and 1s, a free 2 × 2 percentage-closer filter. Jeddy's comparison function is `GL_GEQUAL`
because of reversed-Z: a receiver is lit where it is at least as near to the light as the
stored occluder (chapter [07](07-lighting-and-shadows.md)). The shadow sampler uses
`CLAMP_TO_BORDER` with the default border depth 0, the farthest value, so outside the map every
comparison `reference ≥ 0` passes: lit.

### 6.6 Formats: normalized, floating point, sRGB

The **internal format** says how texels are stored. Jeddy uses three families
(`GpuFormats`, §19.1):

- **Normalized integers.** `GL_RGBA8` stores each channel in 8 bits, read as $v / 255$, so 0 to
  255 becomes 0.0 to 1.0. `GL_R8` and `GL_RG8` are the one- and two-channel versions.
- **Floating point.** `GL_RGBA16F` stores half floats (16 bits: 1 sign, 5 exponent and 10
  fraction bits, the design of L00 §7 at a smaller size), enough for HDR colors far above 1.
  `GL_R11F_G11F_B10F` and `GL_RGB9_E5` pack three small floats into 32 bits.
  `GL_DEPTH_COMPONENT32F` is the depth buffer.
- **sRGB.** `GL_SRGB8_ALPHA8` and the sRGB block formats store sRGB-encoded values (§2.14) and are
  **decoded to linear by the texture unit when sampled**, so shaders always see linear values. The
  exact decode is $c / 12.92$ for $c \le 0.04045$ and $\left(\frac{c + 0.055}{1.055}\right)^{2.4}$
  above: a stored mid-grey of $0.5$ decodes to $0.214$, much darker in light energy than it looks.
  Color textures (base color, emissive) are sRGB; data textures (normal maps, roughness and
  metalness) are linear, because their numbers are not colors.

### 6.7 Block compression

A 1024 × 1024 `RGBA8` texture is 4 MiB, and a game level can use hundreds of textures. GPUs read **block
compressed** (BCn) formats directly: the image is cut into 4 × 4-texel blocks, and every block is
stored in a fixed number of bytes, so the address of any block can be computed and the texture
unit decodes blocks as it reads them. The data stays compressed in memory *and* in the texture
cache, which saves bandwidth as well as space.

| Format | Bytes per block | Bits per texel | Channels | Jeddy uses it for |
|---|---|---|---|---|
| BC1 | 8 | 4 | RGB (1-bit alpha) | legacy content only (needs an extension) |
| BC4 | 8 | 4 | 1 | masks, single-channel maps |
| BC5 | 16 | 8 | 2 | normal maps (x and y; z is rebuilt) |
| BC6H | 16 | 8 | RGB half float | HDR images |
| BC7 | 16 | 8 | RGBA | color textures |

Compare 32 bits per texel for `RGBA8`: BC7 is a quarter of the size. The textures are compressed
offline by the cooker (**D8**, chapter [10](10-assets.md)); there is no compression at run time.

**Size of a level.** A level of $w \times h$ texels needs $\lceil w/4 \rceil \cdot \lceil h/4
\rceil$ blocks, written in integer arithmetic as `((w + 3) >> 2) * ((h + 3) >> 2)` (L00 §2.5 and
§6.3; `GpuTextures.levelBytes` is exactly this). A 1024 × 1024 BC7 level is $256 \cdot 256 = 65{,}536$
blocks of 16 bytes, 1 MiB. Levels smaller than a block still take a whole block: the 2 × 2 and
1 × 1 levels are one block each. The full chain of a 1024 × 1024 BC7 texture is 1,398,128 bytes.
(Those tiny levels caused two driver bugs on an Intel GPU: lesson 1, §17, and D101.)

**One block, decoded.** BC4 is the simplest. Its 8 bytes are two 8-bit endpoints $r_0$ and $r_1$
followed by sixteen 3-bit indices, one per texel ($16 \cdot 3 = 48$ bits, 6 bytes). When
$r_0 > r_1$ the block's palette has 8 values: $r_0$, $r_1$, and six evenly spaced values between
them, $\frac{(8-i)\,r_0 + (i-1)\,r_1}{7}$ for $i = 2, \dots, 7$. Each texel takes the palette entry
its index names. With $r_0 = 200$ and $r_1 = 10$ the palette is $200, 10, 172.86, 145.71, 118.57,
91.43, 64.29, 37.14$ (rounded when stored as 8-bit values); a block whose indices are all 0 decodes
to 200 everywhere. `GpuDeviceTest` uploads exactly such blocks (§28.2).

### 6.8 Units, samplers and immutable storage

**Texture units.** Shaders do not name textures; they name numbered **units**. A GLSL declaration
such as `layout(binding = 0) uniform sampler2DArray jBaseColorMap;` reads unit 0;
`glBindTextureUnit(unit, texture)` puts a texture on a unit. Jeddy binds through
`StateCache.bindTexture` (§23); the unit numbers are listed in §27.2.

**Sampler objects.** How a texture is *read* (filters, wrap modes, anisotropy, comparison) can be
stored in the texture itself, but since GL 3.3 it can live in a separate **sampler object** bound
to the same unit with `glBindSampler(unit, sampler)`; a bound sampler overrides the texture's own
settings. The same texture can then be read with different samplers, and one sampler serves many
textures. Jeddy describes samplers as packed `int`s and creates each once (§20).

**Immutable storage.** `glTextureStorage2D(texture, levels, format, width, height)` (and `3D`)
fixes the level count, format and size of a texture for its whole life, and allocates all levels
at once. The older `glTexImage2D` defined one level at a time and could redefine any level with a
different size or format later; the driver then had to check at every draw whether the levels
still fit together. A mutable texture whose mip levels were never all uploaded is **incomplete**,
and sampling it with a mipmapping filter returns black: a classic first bug. Immutable storage
makes that impossible and lets the driver lay the memory out once. Jeddy uses it for every texture
(§19).

## 7. Getting data to shaders

### 7.1 Three ways in

| Mechanism | GLSL | Set from Java with | Size | Use in Jeddy |
|---|---|---|---|---|
| Plain uniform | `layout(location = 0) uniform mat4 uViewProjection;` | `glProgramUniform*` | a few values | per-pass values: the view-projection in `pbr/depth.vert`, cull parameters in `cull/static_cull.comp` |
| Uniform buffer (UBO) | `layout(std140, binding = 0) uniform JeddyFrame { ... };` | `glBindBufferRange(GL_UNIFORM_BUFFER, ...)` | at least 16 KiB guaranteed (`maxUniformBlockSize`) | frame constants, sun cascades, SH9 irradiance |
| Shader storage buffer (SSBO) | `layout(std430, binding = 0) readonly buffer JeddyInstances { JeddyInstance jeddyInstances[]; };` | `glBindBufferRange(GL_SHADER_STORAGE_BUFFER, ...)` | at least 128 MiB guaranteed (`maxShaderStorageBlockSize`) | instances, materials, lights, palettes, particles, culling data |

- **Plain uniforms** belong to a program. Switching programs means setting them again, so they
  suit values that change per pass, not per object.
- **Uniform buffers** are blocks backed by a range of a buffer. Many programs can read the same
  range, so the per-frame constants are written once per frame into the frame ring and bound once
  at binding point 0. They are fast for data every invocation reads, but small. (The table's "SH9
  irradiance" is such data: nine spherical-harmonics coefficients, each an RGB value, that
  summarize the light arriving from every direction, chapter [07](07-lighting-and-shadows.md).)
- **Storage buffers** are large, can be written by shaders, and can end in an array without a
  size (`jeddyInstances[]`), whose length follows from the bound range. This is how a vertex
  shader reads "instance number $k$" from twenty thousand records.

### 7.2 What the hardware does with each

The details differ between vendors, and the general picture is this. Plain uniforms are stored by
the driver per program and loaded into the shader cores' constant storage when the program is
used. Uniform buffers are read through a path optimized for **uniform access**, the case where all
invocations of a group read the same address at the same time, which is how matrices and
per-frame values are read; on many GPUs such values end up in registers shared by the whole group.
Storage buffers go through the general memory path (the L1 and L2 caches of the GPU), which
handles every invocation reading a different address, writes, and **atomic** operations (a
read-modify-write no other invocation can interleave with, which the light clusters use to append
to lists). Reading a storage buffer is somewhat slower when the data is uniform, and much more
flexible otherwise.

### 7.3 The layout rules std140 and std430, derived

The CPU writes bytes; the shader reads typed fields. Both sides must agree on the byte offset of
every field. GLSL's default block layout (`shared` or `packed`) lets the driver choose offsets,
which you would then have to query field by field. The standard layouts fix them by rule:
**`std140`** for uniform blocks and **`std430`** for storage blocks (std430 is not allowed for
uniform blocks in GL 4.6). Every type has a **base alignment** (its offset must be a multiple of
it) and a size; a member's offset is the end of the previous member rounded up to the member's
base alignment (L00 §2.5). With $N = 4$ bytes, the size of a `float`, `int` or `uint`:

1. **Scalars** have base alignment $N = 4$ and size 4.
2. **Two-component vectors** (`vec2`, `uvec2`) have base alignment $2N = 8$ and size 8.
3. **Three- and four-component vectors** have base alignment $4N = 16$. A `vec3` is 12 bytes, so
   a scalar after it may fill the remaining 4 bytes; a `vec4` is 16.
4. **Arrays.** Each element is aligned like its type and the **stride** (the distance between
   elements) is the element size rounded up to its alignment. **In std140 only, the alignment
   and the stride of an array are also rounded up to 16**, the size of a `vec4`. So
   `float v[4]` has stride 16 in std140 and 4 in std430.
5. **Matrices** are stored as arrays of their columns (column-major, chapter 02): a `mat4` is four
   `vec4` columns at stride 16, 64 bytes, in both layouts.
6. **Structs** have the base alignment of their most-aligned member, **rounded up to 16 in std140
   only**, and their size is rounded up to a multiple of that alignment.

Why does std140 round arrays and structs to 16? It arrived with uniform buffers in GL 3.1 (2009),
when hardware read uniform memory in 16-byte `vec4` registers. std430 (GL 4.3) drops the rounding
for storage blocks, so an array of floats there is laid out exactly like a Java `float[]`.

### 7.4 Worked layouts

*Example 1.* The members `float a; vec3 b; float c[2];`:

| Member | std140 offset | std430 offset | Why |
|---|---|---|---|
| `a` | 0 | 0 | first member |
| `b` | 16 | 16 | `vec3` aligns to 16; 4 rounds up to 16 |
| `c[0]` | 32 | 28 | std140: arrays align to 16, and 28 rounds up to 32; std430: a float aligns to 4, 28 fits |
| `c[1]` | 48 | 32 | stride 16 in std140, 4 in std430 |

The members end at byte 64 in std140 and at byte 36 in std430.

*Example 2, the gap a `vec3` leaves.* `vec3 position; float radius;`: `position` at 0 (12 bytes),
`radius` at 12. Same in both layouts: a scalar after a `vec3` fills its last 4 bytes, so the
pair is one tidy 16-byte unit.

*Example 3, the `vec3` array trap.* `vec3 points[];` in a storage block: a `vec3` aligns to 16, so
the stride is 16, not 12, *even in std430*. Java code that writes tightly packed 12-byte points
puts point 1 at byte 12 while the shader reads it at byte 16, and every point after the first is
garbage. Writing `vec4` instead (and ignoring `w`) makes the layout obvious.

**What Jeddy does.** Jeddy sidesteps the traps by building its records from `vec4`, `uvec4` and
`mat4` members, for which both layouts agree and every offset is a multiple of 16:

- the **instance record** (`engine/instance.glsl`) is three `vec4` rows and one `uvec4`: 64 bytes;
- a **material row** (`engine/material.glsl`) is eight 16-byte members: 128 bytes
  (`MaterialTable.FLOATS = 32`);
- a **light** is four `vec4`s;
- the **frame block** (`engine/frame.glsl`) is five `mat4`s and a run of `vec4`/`uvec4`s:
  `SceneRenderer.FRAME_BYTES = 544` (§27.3 computes every offset).

Plain arrays of scalars or pairs appear only in std430 storage blocks (for example
`uvec2 jClusterRanges[]` and `uint jClusterLights[]`), where they are tightly packed like a Java
`int[]`.

**Integers among floats.** A material row is built on the CPU as 32 floats in a `float[]`, yet
some of its fields are integers (texture layers, flags), declared `uvec4` in GLSL. Java writes
them with `Float.intBitsToFloat(layer)`: a float whose 32 bits are the integer's bits (L00 §7).
GLSL reads the same 32 bits as an unsigned integer. Nothing is converted; the bits travel as they
are. (The frame block is written with typed stores instead: `JAVA_INT` for its `uvec4` fields.)

### 7.5 Binding points and offset alignment

`glBindBufferRange(target, index, buffer, offset, size)` attaches a range of a buffer to binding
point `index` of a target, and `layout(binding = index)` in GLSL reads it. Uniform-buffer
bindings, storage-buffer bindings and texture units are **three separate numbering spaces**:
uniform binding 0 and storage binding 0 are different slots. §27.2 lists Jeddy's.

The `offset` must be a multiple of a value the driver reports: `GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT`
for uniform buffers (256 on most desktop GPUs) and `GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT` for
storage buffers (often smaller). The reason is in the hardware: a binding becomes a small
descriptor holding the start address, and GPUs store those starts in aligned units. Jeddy reads
both values into `GLCapabilities` (§12) and allocates every bound range at a multiple of them: the
frame ring's `allocate` takes the alignment and rounds up with L00 §6.6's trick (lesson 1, §11).
For example `SceneRenderer` reserves its frame block with alignment
`Math.max(16, device.caps().uniformBufferOffsetAlignment())`.

## 8. Shaders, programs and variants

### 8.1 What compiling and linking do

A **shader** is one stage's GLSL source compiled by the driver; a **program** links stages
together (vertex + fragment, or one compute shader). Each GPU vendor ships its own GLSL compiler
inside its driver. Compiling parses the text, checks it, and turns it into an intermediate form;
**linking** matches the stages' interfaces (§8.2), assigns locations, removes outputs no later
stage reads, optimizes the whole program and generates the GPU's machine code. Much of the real
work happens at link time, and it can take milliseconds per program or far more for large
shaders. Errors come back as text, the **info log**, with line numbers in the driver's own format.

Some drivers also compile a program again later, at a draw, when they discover state the machine
code depends on (a vertex format, a blend mode on hardware that blends in the shader). That is one
more reason to keep state changes few (§23).

### 8.2 How stages connect

A vertex shader's `out` variables feed the fragment shader's `in` variables. They match by
**name** (`out vec3 vColor;` and `in vec3 vColor;` in `HelloTriangle`) or by **location**
(`layout(location = 2) out vec4 vTangent;` and `layout(location = 2) in vec4 vTangent;` in the PBR
shaders). Locations are safer: a renamed variable cannot silently disconnect.

The same `layout(location = N)` fixes the numbers Java uses on the other side: the attribute
numbers of vertex inputs (§18), and since GL 4.3 the locations of plain uniforms, so
`glProgramUniform1f(program, 0, angle)` needs no name lookup. `layout(binding = N)` (GLSL 4.20)
fixes the unit or binding point of samplers and blocks the same way. Without explicit numbers you
would have to ask the linker which number it picked, by string, after every link.

### 8.3 Variants and feature masks

One source file serves many materials: with a normal map or without, alpha-tested or not,
skinned or not. Branching on these at run time would cost every pixel; compiling each combination
separately costs nothing at run time. So each optional feature is an `#ifdef` in the shader and a
bit in a 64-bit **feature mask**: bit $i$ set means `#define NAME_i 1` before compiling. With $n$
features $2^n$ variants are *possible*, but only the masks materials actually use are ever
compiled, on first request (§25). A **variant** is one (source, mask) pair compiled into one
program.

GLSL has no `#include`, yet Jeddy's shaders share code (the vertex layout, instance records,
material rows, frame constants). A small **preprocessor** expands includes and inserts the
defines before the driver sees the text (§24). It lives in the `asset` module so that the offline
cooker can check shaders with exactly the same code (D34).

### 8.4 Compiling without stopping the frame

A program requested in the middle of a level (a new material appears) must not freeze the game
while the driver compiles it. The extension `GL_KHR_parallel_shader_compile` lets the driver
compile on its own threads: `glCompileShader` and `glLinkProgram` return at once, and
`glGetProgramiv(program, GL_COMPLETION_STATUS_KHR, ...)` answers "done yet?" without waiting.
Asking for the link status before that would block until the driver finishes. Jeddy's program
cache returns 0 while a program compiles, and callers skip that draw: the object appears a frame
or two later instead of the whole game stopping (§25).

### 8.5 Program binaries

Compiling dozens of variants at every start takes time. GL 4.1 can hand back a linked program as
an opaque, driver-specific blob (`glGetProgramBinary`) and accept it on the next run
(`glProgramBinary`), skipping compilation. The blob is only valid for the same driver, and a
driver may reject it after an update; the cache must detect both (§26, D73).

<details><summary>Check: what are the std140 and std430 offsets of <code>vec2 a; float b; vec3 c; float d[3];</code>?</summary>

`a`: 0 in both (8 bytes). `b`: 8 in both (a float aligns to 4). `c`: 16 in both (a `vec3` aligns
to 16; 12 rounds up to 16), ending at 28. `d`: std140 aligns arrays to 16, so `d[0]` is at 32,
`d[1]` at 48, `d[2]` at 64 (the members end at 80); std430 puts `d[0]` at 28, `d[1]` at 32,
`d[2]` at 36 (they end at 40).
</details>

---

# Part B — Building it

The order of the steps is the order a reader would write the code in: first the bindings, so
Java can call GL at all (§9–§12); then a context to call it in (§13); then the smallest program
that draws (§14–§15) and the debugging aid that explains its mistakes (§16); then, one class at a
time, the resource layer the renderer is built on (§17–§26).

## 9. Step 1: say what to generate

The Khronos Group publishes every OpenGL constant and command in one XML file, the **registry**,
`gl.xml`. Jeddy keeps a copy at [`bindings/gl/gl.xml`](../../bindings/gl/gl.xml), taken unchanged
at a pinned commit, and a settings file beside it,
[`bindings/gl/gl.properties`](../../bindings/gl/gl.properties). The settings are the first thing
to write, because they are the whole specification of what the generator must produce:

```properties
# OpenGL binding generator settings (DESIGN §9.1, TASKS M3-01).
# gl.xml is copied unchanged from https://github.com/KhronosGroup/OpenGL-Registry (xml/gl.xml,
# Apache-2.0) at this commit:
registry.commit=6af574a14089ccfee87efe230ebcdd8742859813

api=gl
profile=core
version=4.6
package=dev.jeddy.gl.ffi
class=GL46

# Beyond 4.6 core. BCn sRGB formats come from EXT_texture_sRGB; S3TC is only for legacy content.
extensions=GL_ARB_bindless_texture,\
  GL_KHR_parallel_shader_compile,\
  GL_EXT_texture_compression_s3tc,\
  GL_EXT_texture_sRGB

# Untyped "void *" parameters with these names also get a long overload: a buffer offset when a
# buffer is bound to the matching target (element array, indirect, pixel pack/unpack), otherwise a
# raw native address. Either way the call allocates no MemorySegment.
offset.params=indices,indirect,pointer,offset,data,pixels

# Linked with Linker.Option.critical(true) when -Djeddy.gl.critical is not false (DESIGN §4.4):
# state setters, binds and uniforms only. They never block, never call back into Java, and may
# take heap segments (MemorySegment.ofArray) for their small parameter blocks.
critical=glActiveTexture,\
  glBindBuffer,glBindBufferBase,glBindBufferRange,glBindFramebuffer,glBindImageTexture,\
  glBindProgramPipeline,glBindSampler,glBindTextureUnit,glBindVertexArray,\
  glBlendEquation,glBlendEquationSeparate,glBlendEquationi,glBlendFunc,glBlendFuncSeparate,glBlendFunci,\
  glClearColor,glClearDepthf,glClearStencil,glColorMask,glColorMaski,glCullFace,\
  glDepthFunc,glDepthMask,glDepthRangef,glDisable,glDisablei,glEnable,glEnablei,glFrontFace,\
  glLineWidth,glPatchParameteri,glPolygonMode,glPolygonOffset,glScissor,\
  glStencilFunc,glStencilFuncSeparate,glStencilMask,glStencilMaskSeparate,glStencilOp,glStencilOpSeparate,\
  glUseProgram,glViewport,\
  glUniform1f,glUniform2f,glUniform3f,glUniform4f,glUniform1i,glUniform2i,glUniform3i,glUniform4i,\
  glUniform1ui,glUniform1fv,glUniform2fv,glUniform3fv,glUniform4fv,glUniform1iv,\
  glUniformMatrix3fv,glUniformMatrix4fv,glUniformMatrix3x4fv,\
  glProgramUniform1f,glProgramUniform2f,glProgramUniform3f,glProgramUniform4f,\
  glProgramUniform1i,glProgramUniform1ui,glProgramUniform1fv,glProgramUniform4fv,\
  glProgramUniformMatrix4fv,glProgramUniformMatrix3x4fv
```

Key by key:

- **`registry.commit`** records where `gl.xml` came from; the generator writes it into the
  output's first line. A newer registry is a deliberate update, never an accident.
- **`api`, `profile`, `version`**: the OpenGL API (not OpenGL ES), the core profile, every feature
  up to 4.6 (§3.2).
- **`package`, `class`**: the output is one class, `dev.jeddy.gl.ffi.GL46`. The `ffi` package
  holds raw bindings and is exported only to the modules that need it, by name (a *qualified*
  export, CLAUDE.md's convention and D24): `render`, `app`, the samples and the editor tool. A game
  never calls raw GL.
- **`extensions`**: the four extensions beyond 4.6 core that Jeddy may use. Bindless textures and
  parallel shader compilation add commands; the two texture-compression extensions add only
  constants for the BC1–BC3 formats (§19.1).
- **`offset.params`**: parameter names that get the `long` overload of §5.4. §10.7 shows that the
  name alone is not enough.
- **`critical`**: the 70 commands linked critically (§5.3). Every one is a state setter, a bind
  or a uniform setter. Not one draws, waits, maps or uploads.

*Why generate our own bindings?* Three alternatives were available. A third-party Java binding
library is ruled out by the project's first principles: no third-party Java binding library and no
runtime dependencies (DESIGN §1, §3). jextract, which Jeddy uses for every other C library (D2),
binds functions by looking them up as exported symbols of a library, and most GL commands are not
exported symbols at all: they must be fetched through the window system at run time (§5.1). And a
hand-written binding of the hundred or so commands the engine uses would have to be kept in sync
by hand. A generator from the registry gives exactly the commands of 4.6 core, with Jeddy's own
type mapping, the critical allow-list and the `long` overloads (D2, D24). It is written in plain
Java in `build-logic`, so the same code runs inside Gradle and by hand (D24).

## 10. Step 2: the generator

### 10.1 The registry

Four parts of `gl.xml` matter. Each **enum** is a name and a value:

```xml
<enum value="0x0100" name="GL_DYNAMIC_STORAGE_BIT" group="BufferStorageMask"/>
<enum value="0xFFFFFFFFFFFFFFFF" name="GL_TIMEOUT_IGNORED" type="ull" comment="Tagged as uint64" group="SpecialNumbers"/>
```

Each **command** is a `<proto>` (return type and name) and its `<param>`s, written as C
declarations with the type names marked up as `<ptype>`:

```xml
<command>
    <proto>void <name>glNamedBufferStorage</name></proto>
    <param class="buffer"><ptype>GLuint</ptype> <name>buffer</name></param>
    <param kind="BufferSize"><ptype>GLsizeiptr</ptype> <name>size</name></param>
    <param len="size">const void *<name>data</name></param>
    <param group="BufferStorageMask"><ptype>GLbitfield</ptype> <name>flags</name></param>
</command>
```

Each **feature** (one per GL version) lists what that version `<require>`s, and from 3.2 on what
the core profile `<remove>`s:

```xml
<feature api="gl" name="GL_VERSION_4_6" number="4.6">
    <require comment="Reuse GL_ARB_indirect_parameters">
        <enum name="GL_PARAMETER_BUFFER"/>
        ...
<remove profile="core" comment="Compatibility-only GL 1.0 features removed from GL 3.2">
    <command name="glNewList"/>
    ...
```

And each **extension** lists the same for itself:

```xml
<extension name="GL_KHR_parallel_shader_compile" supported="gl|glcore|gles2">
    <require>
        <enum name="GL_MAX_SHADER_COMPILER_THREADS_KHR"/>
        <enum name="GL_COMPLETION_STATUS_KHR"/>
        <command name="glMaxShaderCompilerThreadsKHR"/>
    </require>
</extension>
```

So "OpenGL 4.6 core" is not written anywhere in the file. It is a computation: start from
nothing, walk the features from 1.0 to 4.6, add what each requires and drop what the core profile
removes. That computation is the generator's first job.

### 10.2 Settings and parsing

Create [`GlGenerator.java`](../../build-logic/src/main/java/dev/jeddy/gradle/GlGenerator.java) in
`build-logic/src/main/java/dev/jeddy/gradle`. Its entry point reads the settings, parses the XML,
selects, emits and writes:

```java
public static void generate(Path glXml, Path properties, Path outputDir) {
    try {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(properties)) {
            p.load(in);
        }
        Document doc = parse(glXml);
        String api = p.getProperty("api", "gl");
        String profile = p.getProperty("profile", "core");
        String version = p.getProperty("version", "4.6");
        String pkg = p.getProperty("package");
        String className = p.getProperty("class");
        Set<String> extensions = list(p.getProperty("extensions", ""));
        Set<String> critical = list(p.getProperty("critical", ""));
        Set<String> offsetParams = list(p.getProperty("offset.params", ""));

        Set<String> enums = new LinkedHashSet<>();
        Set<String> commands = new LinkedHashSet<>();
        selectFeatures(doc, api, profile, version, enums, commands);
        Set<String> coreCommands = new LinkedHashSet<>(commands);
        Map<String, String> commandExtension = new LinkedHashMap<>();
        selectExtensions(doc, api, profile, extensions, enums, commands, commandExtension);
        commandExtension.keySet().removeAll(coreCommands);

        Map<String, String> enumValues = enumValues(doc);
        Map<String, Command> parsed = commands(doc);
        for (String c : critical) {
            if (!commands.contains(c)) {
                throw new IllegalArgumentException("critical command not selected: " + c);
            }
        }
        String source =
                emit(
                        pkg,
                        className,
                        version,
                        extensions,
                        enums,
                        enumValues,
                        commands,
                        parsed,
                        critical,
                        offsetParams,
                        commandExtension,
                        p.getProperty("registry.commit", "?"));
        Path dir = outputDir.resolve(pkg.replace('.', '/'));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(className + ".java"), source, StandardCharsets.UTF_8);
    } catch (IOException e) {
        throw new UncheckedIOException(e);
    } catch (Exception e) {
        throw new IllegalStateException("GL binding generation failed: " + e.getMessage(), e);
    }
}
```

Points to notice, top to bottom:

- **`java.util.Properties`** reads the settings file; the `\` line continuations of the lists are
  part of the properties format. `list` splits a value on commas and whitespace:

  ```java
  private static Set<String> list(String csv) {
      Set<String> out = new LinkedHashSet<>();
      for (String s : csv.split("[,\\s]+")) {
          if (!s.isBlank()) {
              out.add(s.trim());
          }
      }
      return out;
  }
  ```

- **`LinkedHashSet` and `LinkedHashMap`** keep insertion order. The generator must be a pure
  function of its inputs, producing byte-identical output every time, so Gradle can cache it
  (§10.9); hash-ordered collections would make the order depend on hash codes.
- **`coreCommands` and `removeAll`**: a command that is both core and in a listed extension (an
  extension later promoted into core) is linked as a core command, never gated on the extension.
- **The critical check** fails the build if the allow-list names a command that is not part of the
  selection, which catches typos in the properties file.
- **Exceptions.** A generator error stops the build with a message; nothing is silently skipped.

The XML parser is the JDK's own, hardened:

```java
private static Document parse(Path xml) throws Exception {
    DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
    f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    f.setExpandEntityReferences(false);
    DocumentBuilder b = f.newDocumentBuilder();
    try (InputStream in = Files.newInputStream(xml)) {
        return b.parse(in);
    }
}
```

Disallowing a `DOCTYPE` and entity expansion is standard hygiene for any XML input: it shuts out
attacks that make a parser read local files or expand entities exponentially. The registry does
not need either feature.

### 10.3 Choosing the enums and commands

```java
private static void selectFeatures(
        Document doc,
        String api,
        String profile,
        String version,
        Set<String> enums,
        Set<String> commands) {
    int[] max = versionOf(version);
    List<Element> features = new ArrayList<>();
    NodeList all = doc.getDocumentElement().getElementsByTagName("feature");
    for (int i = 0; i < all.getLength(); i++) {
        Element f = (Element) all.item(i);
        if (f.getAttribute("api").equals(api)
                && compare(versionOf(f.getAttribute("number")), max) <= 0) {
            features.add(f);
        }
    }
    features.sort((x, y) -> compare(versionOf(x.getAttribute("number")), versionOf(y.getAttribute("number"))));
    for (Element f : features) {
        for (Element req : children(f, "require")) {
            if (applies(req, api, profile)) {
                add(req, enums, commands);
            }
        }
        for (Element rem : children(f, "remove")) {
            if (applies(rem, api, profile)) {
                for (Element e : children(rem, "enum")) {
                    enums.remove(e.getAttribute("name"));
                }
                for (Element c : children(rem, "command")) {
                    commands.remove(c.getAttribute("name"));
                }
            }
        }
    }
}
```

- **Versions as pairs.** `versionOf("4.6")` splits on the dot into `{4, 6}` and `compare` orders
  pairs by major, then minor. Comparing the strings would put `"4.10"` before `"4.6"`; comparing
  them as decimals would make 4.10 equal to 4.1.
- **Sorted, then applied in order.** A `<remove>` in 3.2 must see what 1.0 to 3.1 added, so the
  features are sorted by version before the walk.
- **`applies`** keeps a `<require>` or `<remove>` block when its `api` and `profile` attributes are
  absent or match:

  ```java
  private static boolean applies(Element block, String api, String profile) {
      String a = block.getAttribute("api");
      String p = block.getAttribute("profile");
      return (a.isEmpty() || a.equals(api)) && (p.isEmpty() || p.equals(profile));
  }
  ```

  A `<remove profile="core">` applies to us; a `<require profile="compatibility">` does not.
- `children(parent, tag)` returns the direct child elements with that tag (not every descendant,
  which `getElementsByTagName` would).

`selectExtensions` does the same for the listed extensions, records which extension provides
each command, and fails on a name it cannot find:

```java
for (Element req : children(ext, "require")) {
    if (applies(req, api, profile)) {
        add(req, enums, commands);
        for (Element c : children(req, "command")) {
            commandExtension.putIfAbsent(c.getAttribute("name"), name);
        }
    }
}
...
Set<String> missing = new LinkedHashSet<>(wanted);
missing.removeAll(found);
if (!missing.isEmpty()) {
    throw new IllegalArgumentException("unknown extensions: " + missing);
}
```

### 10.4 From C types to Java types

Every parameter and return type must become a Java type that FFM can pass. The table is built
once, in a static initializer:

```java
private static final Map<String, String> SCALARS = new LinkedHashMap<>();

static {
    for (String t : new String[] {"GLenum", "GLbitfield", "GLuint", "GLint", "GLsizei"}) {
        SCALARS.put(t, "int");
    }
    SCALARS.put("GLboolean", "boolean");
    for (String t : new String[] {"GLbyte", "GLubyte", "GLchar"}) {
        SCALARS.put(t, "byte");
    }
    SCALARS.put("GLshort", "short");
    SCALARS.put("GLushort", "short");
    SCALARS.put("GLfloat", "float");
    SCALARS.put("GLclampf", "float");
    SCALARS.put("GLdouble", "double");
    SCALARS.put("GLclampd", "double");
    for (String t :
            new String[] {
                "GLint64", "GLuint64", "GLint64EXT", "GLuint64EXT", "GLintptr", "GLsizeiptr", "GLsync"
            }) {
        SCALARS.put(t, "long");
    }
    SCALARS.put("GLDEBUGPROC", "MemorySegment");
}
```

| C type in `gl.xml` | Java type | Note |
|---|---|---|
| `GLenum`, `GLbitfield`, `GLuint`, `GLint`, `GLsizei` | `int` | unsigned values reuse the same 32 bits (L00 §5) |
| `GLboolean` | `boolean` | one byte in C, `JAVA_BOOLEAN` in FFM |
| `GLfloat`, `GLclampf` | `float` | |
| `GLint64`, `GLuint64`, `GLintptr`, `GLsizeiptr` | `long` | 64-bit sizes and offsets |
| `GLsync` | `long` | a pointer kept as a raw address, so no `MemorySegment` is created (D24) |
| `GLDEBUGPROC` | `MemorySegment` | the debug callback's function pointer (§16) |
| any `T *` | `MemorySegment` | plus a `long` overload for offset-style `void *` (§10.7) |

`GLsync` deserves a look. The registry declares it as `typedef struct __GLsync *GLsync`, a
pointer, but parameters name it as a type (`<ptype>GLsync</ptype> <name>sync</name>`) without a
`*`. The table maps that type name to `long`, and FFM passes a `JAVA_LONG` exactly like a 64-bit
pointer on x64.

`javaType` reads one declaration:

```java
private static String javaType(Element decl, String command, boolean isReturn) {
    String full = decl.getTextContent();
    String name = text(children(decl, "name").get(0));
    String withoutName = full.substring(0, full.lastIndexOf(name));
    int stars = withoutName.length() - withoutName.replace("*", "").length();
    List<Element> ptypes = children(decl, "ptype");
    String ptype = ptypes.isEmpty() ? "void" : text(ptypes.get(0));
    if (stars > 0) {
        return "MemorySegment";
    }
    if (ptype.equals("void")) {
        if (!isReturn) {
            throw new IllegalArgumentException(command + ": void parameter");
        }
        return "void";
    }
    String t = SCALARS.get(ptype);
    if (t == null) {
        return "?" + ptype; // reported if the command is selected
    }
    return t;
}
```

- `getTextContent()` gives the declaration as plain C, for example `const void *data`.
- **Counting stars.** Everything before the name is the type; the number of `*` in it is the
  pointer depth, found by comparing the length with and without stars. Any pointer at all, `T *` or
  `T **`, becomes a `MemorySegment`.
- **No `<ptype>`** means the type is the C keyword `void` (`const void *data` has no `<ptype>`
  element). A `void` *parameter* would be a registry error, so it throws.
- **Unknown types** come back as `"?" + type`, and `emit` reports them only for commands that were
  actually selected: the registry has exotic types (in vendor extensions) that a 4.6 core binding
  never needs.

Two more helpers finish the mapping. Parameter names found in the `KEYWORDS` set get an underscore
(`safeName`): the set holds every Java keyword plus `string`, so the generated `glShaderSource`
takes `MemorySegment string_`. And `layout` turns a Java type into the FFM layout constant written into
the descriptor: `int` → `JAVA_INT`, `long` → `JAVA_LONG`, `MemorySegment` → `ADDRESS`, and so on.

### 10.5 Emitting the constants

`enumValues` collects every `<enum>` that has a `value` (the `<enum name="..."/>` entries inside
`<require>` blocks are only references and are skipped) and whose `api` attribute is empty or
`gl` (one name, `GL_ACTIVE_PROGRAM_EXT`, has a different value in OpenGL ES, tagged
`api="gles2"`, which must not replace the desktop one). It tags each as an `int` (`"I:"`) or, for the registry's `type="ull"`
(unsigned 64-bit) entries, a `long` (`"L:"`):

```java
String api = e.getAttribute("api");
if (!api.isEmpty() && !api.equals("gl")) {
    continue;
}
String type = e.getAttribute("type");
out.put(e.getAttribute("name"), (type.equals("ull") ? "L:" : "I:") + e.getAttribute("value"));
```

`emit` writes the selected ones sorted by name (a `TreeMap`, again for stable output), each as a
`public static final` field, through `constant`:

```java
private static String constant(String literal, boolean isLong) {
    String v = literal.trim();
    if (v.startsWith("0x") || v.startsWith("0X")) {
        String hex = v.substring(2);
        if (isLong) {
            return "0x" + hex.toUpperCase(Locale.ROOT) + "L";
        }
        long value = Long.parseLong(hex, 16);
        if (value > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("int enum out of range: " + literal);
        }
        return "0x" + hex.toUpperCase(Locale.ROOT);
    }
    if (v.matches("-?\\d+")) {
        return isLong ? v + "L" : v;
    }
    // Rare symbolic values such as "~0" or "((GLuint)-1)".
    if (v.equals("~0") || v.replace(" ", "").equals("((GLuint)-1)")) {
        return isLong ? "-1L" : "-1";
    }
    throw new IllegalArgumentException("unsupported enum value " + literal);
}
```

- A hex literal that fits in 32 bits is written as a Java `int` hex literal. Java accepts
  `0xFFFFFFFF` as an `int` (it is −1, L00 §5.3), so unsigned GL values keep their bits.
- A value above 32 bits marked as `int` is a registry or settings error: it throws rather than
  truncating.
- `~0` ("all bits set") and `((GLuint)-1)` both mean all ones, which is `-1` (L00 §5.3).

The result: 1,384 constants, of which exactly one is a `long`:
`public static final long GL_TIMEOUT_IGNORED = 0xFFFFFFFFFFFFFFFFL;` (the "wait forever" timeout of
`glClientWaitSync`, all 64 bits set). Constants are `static final` primitives, so `javac` copies
their values into every class that uses them; using a constant costs nothing at run time.

### 10.6 Emitting one command

`emitCommand` writes three things per command: a Javadoc comment with the original C declaration,
the public method, and the holder class. The method:

```java
sb.append("    public static ").append(ret).append(' ').append(name).append('(');
for (int i = 0; i < types.size(); i++) {
    if (i > 0) {
        sb.append(", ");
    }
    sb.append(types.get(i)).append(' ').append(c.paramNames.get(i));
}
sb.append(") {\n        try {\n            ");
if (!ret.equals("void")) {
    sb.append("return (").append(ret).append(") ");
}
sb.append(holder).append(".MH.invokeExact(");
sb.append(String.join(", ", c.paramNames));
sb.append(");\n        } catch (Throwable t) {\n");
sb.append("            throw GLLinker.rethrow(t);\n        }\n    }\n\n");
```

and the holder:

```java
sb.append("    private static final class ").append(holder).append(" {\n");
sb.append("        static final MethodHandle MH =\n                GLLinker.link(\"")
        .append(name)
        .append("\", ");
if (ret.equals("void")) {
    sb.append("FunctionDescriptor.ofVoid(");
} else {
    sb.append("FunctionDescriptor.of(").append(layout(ret));
    if (!types.isEmpty()) {
        sb.append(", ");
    }
}
List<String> layouts = new ArrayList<>();
for (String t : types) {
    layouts.add(layout(t));
}
sb.append(String.join(", ", layouts)).append("), ").append(critical);
if (extension != null) {
    sb.append(", \"").append(extension).append('"');
}
sb.append(");\n");
sb.append("    }\n\n");
```

Here is what comes out for the renderer's main draw call, exactly as generated:

```java
/**
 * {@code void glMultiDrawElementsIndirect(GLenum mode, GLenum type, const void *indirect, GLsizei drawcount, GLsizei stride)}
 */
public static void glMultiDrawElementsIndirect(int mode, int type, MemorySegment indirect, int drawcount, int stride) {
    try {
        glMultiDrawElementsIndirect.MH.invokeExact(mode, type, indirect, drawcount, stride);
    } catch (Throwable t) {
        throw GLLinker.rethrow(t);
    }
}

private static final class glMultiDrawElementsIndirect {
    static final MethodHandle MH =
            GLLinker.link("glMultiDrawElementsIndirect", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT), false);
}
```

Read it from the bottom up:

- **The holder class** is named after the command, so the method and its holder sit side by side.
  Its only member is the `static final MethodHandle`. It is initialized on the command's first
  call (§5.2), and its initializer is the `GLLinker.link` call (§11).
- **The descriptor** is built from the layouts of §10.4: `void` return, two `int`s, a pointer, two
  `int`s.
- **The boolean** after the descriptor says whether the command is on the critical allow-list.
- **The method** calls `MH.invokeExact` with exactly the declared Java types, so the call matches
  the handle's type without conversion.
- **`try`/`catch (Throwable)`**: `invokeExact` is declared to throw `Throwable`, a checked
  exception. `GLLinker.rethrow` (§11) turns it into an unchecked one, so callers see a plain Java
  method.
- **Return values.** A non-void command casts the result, which `invokeExact` also requires:

  ```java
  public static long glFenceSync(int condition, int flags) {
      try {
          return (long) glFenceSync.MH.invokeExact(condition, flags);
      } catch (Throwable t) {
          throw GLLinker.rethrow(t);
      }
  }
  ```

  `glFenceSync` returns a `GLsync`, so a `long` (§10.4): a fence costs no object.
  `glGetString` returns a `const GLubyte *` and is generated as `MemorySegment`, with a
  zero-length segment that §12 reinterprets.

Commands on the allow-list say so in their Javadoc and pass `true`:

```java
/**
 * {@code void glUseProgram(GLuint program)} (critical)
 */
public static void glUseProgram(int program) {
    try {
        glUseProgram.MH.invokeExact(program);
    } catch (Throwable t) {
        throw GLLinker.rethrow(t);
    }
}

private static final class glUseProgram {
    static final MethodHandle MH =
            GLLinker.link("glUseProgram", FunctionDescriptor.ofVoid(JAVA_INT), true);
}
```

Commands that only an extension provides pass the extension's name as a fourth argument, which
selects the gated `link` overload of §11:

```java
private static final class glMaxShaderCompilerThreadsKHR {
    static final MethodHandle MH =
            GLLinker.link("glMaxShaderCompilerThreadsKHR", FunctionDescriptor.ofVoid(JAVA_INT), false, "GL_KHR_parallel_shader_compile");
}
```

*Why one holder per command, and not one table of handles?* A table (`MethodHandle[] HANDLES`,
filled at startup) would link all 674 commands at once, slow down every start, and fail at startup
on any driver missing an optional command. Its elements would also not be constants: the JIT
trusts `static final` fields, not the contents of arrays. The holder costs one tiny class per
command and makes each handle a constant that is linked only if used (DESIGN §9.1, measured in
M3-05 to cost nothing over a hand-held handle, §29).

### 10.7 The offset overload

After each command, `emit` checks whether it deserves a second version with `long` offsets:

```java
emitCommand(sb, c, c.paramTypes, name, "", isCritical, extension);
// Offset overload: untyped void* parameters with conventional names (a buffer offset
// when a buffer is bound to the matching target, otherwise a raw address).
List<String> offsetTypes = new ArrayList<>(c.paramTypes);
boolean anyOffset = false;
for (int i = 0; i < c.paramNames.size(); i++) {
    String cDecl = c.paramC.get(i);
    if (offsetParams.contains(c.paramNames.get(i))
            && (cDecl.startsWith("const void *") || cDecl.startsWith("void *"))
            && cDecl.indexOf('*') == cDecl.lastIndexOf('*')) {
        offsetTypes.set(i, "long");
        anyOffset = true;
    }
}
if (anyOffset) {
    emitCommand(sb, c, offsetTypes, name, "$offset", isCritical, extension);
}
```

A parameter becomes a `long` only when three conditions hold:

1. its **name** is in `offset.params`;
2. its C declaration starts with **`const void *` or `void *`**: an untyped pointer;
3. it has **exactly one `*`** (`indexOf` and `lastIndexOf` find the same one): a pointer to data,
   not a pointer to pointers.

The name alone would be wrong. `glGetIntegerv(GLenum pname, GLint *data)` has a parameter named
`data`, but it is a typed out-pointer that receives results; it gets no overload.
`glNamedBufferSubData(GLuint buffer, GLintptr offset, GLsizeiptr size, const void *data)` has a
parameter named `offset` that is already a `long` (`GLintptr`); only `data` gets the overload.

The overload's holder gets the suffix `$offset` (legal in a Java class name) and the same address
with `JAVA_LONG` instead of `ADDRESS` in the descriptor:

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
}
```

Why this is legal: on x64, a pointer and a 64-bit integer travel in the same register, so the C
function receives the same bits either way; FFM only needs to be told which Java type to accept.
Java picks the overload at compile time: `glDrawElements(GL_TRIANGLES, 3, GL_UNSIGNED_SHORT, 0L)`
can only match the `long` version (an `int` or `long` argument can never become a
`MemorySegment`), and `MemorySegment.NULL` only the other.

### 10.8 What comes out

Run the generator and open `modules/gl/build/generated/sources/gl/java/main/dev/jeddy/gl/ffi/GL46.java`.
At the registry commit and settings above it is 13,121 lines: 1,384 constants and 674 commands, 58
of which also have an offset overload, so 732 methods and 732 holder classes. 70 commands are
critical and 17 are gated on an extension (16 from `GL_ARB_bindless_texture`, one from
`GL_KHR_parallel_shader_compile`). These numbers were counted on the generator's output for this
chapter; they change only when `gl.xml` or `gl.properties` does.

### 10.9 The Gradle task and plugin

The Gradle side is two small files. [`GlBindings.kt`](../../build-logic/src/main/kotlin/dev/jeddy/gradle/GlBindings.kt)
defines the task type:

```kotlin
@CacheableTask
abstract class GenerateGlBindings : DefaultTask() {
    /** `bindings/gl/gl.xml`. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val registry: RegularFileProperty

    /** `bindings/gl/gl.properties`. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val settings: RegularFileProperty

    /** Source root the generated package is written into. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        GlGenerator.generate(registry.get().asFile.toPath(), settings.get().asFile.toPath(), out.toPath())
    }
}
```

- **`@InputFile` and `@OutputDirectory`** tell Gradle what the task reads and writes. When neither
  input changed (nor the generator's own code in `build-logic`), the task is up to date and does
  not run.
- **`@CacheableTask`** with **`PathSensitivity.NONE`** lets Gradle reuse the output from its build
  cache, keyed on the inputs' *contents* only, not on where the checkout lives. This is safe
  because the generator is deterministic (§10.2).
- **`deleteRecursively()`** first empties the output directory, so a class renamed in the
  settings does not leave a stale file behind.

The convention plugin
[`jeddy.gl-bindings`](../../build-logic/src/main/kotlin/jeddy.gl-bindings.gradle.kts), applied by
the `gl` module's build script, registers the task and adds its output to the `main` source set:

```kotlin
val bindingsDir = rootProject.layout.projectDirectory.dir("bindings/gl")

val generateGlBindings = tasks.register<GenerateGlBindings>("generateGlBindings") {
    group = "jeddy"
    description = "Generates the OpenGL bindings from bindings/gl/gl.xml."
    registry = bindingsDir.file("gl.xml")
    settings = bindingsDir.file("gl.properties")
    outputDir = layout.buildDirectory.dir("generated/sources/gl/java/main")
}

extensions.getByType<SourceSetContainer>().named("main") {
    java.srcDir(generateGlBindings)
}
```

Passing the *task* to `srcDir` (rather than a path) also tells Gradle that compiling the module
depends on running the task first. The output is written at build time and never committed
(DESIGN §9.1): the registry is pinned and the generator deterministic, so every machine gets the
same `GL46.java`. Because the generator is plain Java with no dependency beyond the JDK, it also
runs by hand with the JDK's single-file launcher (§30):
`java build-logic/src/main/java/dev/jeddy/gradle/GlGenerator.java bindings/gl/gl.xml bindings/gl/gl.properties <output dir>`.

## 11. Step 3: GLLinker

Every holder's initializer calls [`GLLinker`](../../modules/gl/src/main/java/dev/jeddy/gl/ffi/GLLinker.java),
the one place where GL addresses become method handles. It lives in the `ffi` package next to the
generated class. Its fields:

```java
public final class GLLinker {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final boolean CRITICAL =
            !"false".equalsIgnoreCase(System.getProperty("jeddy.gl.critical", "true"));

    private static volatile Function<String, MemorySegment> resolver;
    private static volatile Predicate<String> extensions = name -> true;

    private GLLinker() {}
```

- **`LINKER`**: the platform's native linker, fetched once.
- **`CRITICAL`**: the global switch of §5.3, read **once**, when `GLLinker` initializes. Critical
  linking is on unless the property is `false` (in any letter case). Because it is read at class
  initialization, the property must be set *before* anything touches a GL command. `JeddyApp`
  sets it when `-Djeddy.gl.debug=true` is given, before the window exists (§15.3), and the GPU
  tests set it first thing (§28). Setting it later has no effect.
- **`resolver`**: the function that turns a command name into an address (in practice
  `Window.glProcAddress`, §13). **`extensions`**: the test "does this context have extension X?",
  installed by `GLLoader` (§12); until then it says yes to everything.
- **Why `volatile`.** The resolver is installed by one call and read by every holder initializer
  that runs afterwards. A holder may initialize on any thread that first calls its command, and in
  tests the thread that loads GL is not always the one that calls it first. `volatile` makes the
  installed value visible to every later reader (L00 §9.5); it costs one ordinary read per command
  per run.

Installing them:

```java
public static void setResolver(Function<String, MemorySegment> resolve) {
    resolver = resolve;
}

public static void setExtensions(Predicate<String> supported) {
    extensions = supported;
}

public static boolean hasResolver() {
    return resolver != null;
}

public static boolean criticalEnabled() {
    return CRITICAL;
}
```

Looking up an address:

```java
public static MemorySegment address(String name) {
    Function<String, MemorySegment> r = resolver;
    if (r == null) {
        throw new IllegalStateException(
                "GL is not loaded: call GLLoader.init with a current context first");
    }
    MemorySegment address = r.apply(name);
    return address == null ? MemorySegment.NULL : address;
}
```

The field is read once into a local `r`, so the check and the use see the same value. A `null`
from the resolver is normalized to `MemorySegment.NULL`, so callers only test `address() == 0`.

Linking, in two overloads:

```java
/** Called by the generated holders of extension commands. */
public static MethodHandle link(
        String name, FunctionDescriptor descriptor, boolean critical, String extension) {
    if (!extensions.test(extension)) {
        return missing(name + " (" + extension + ")", descriptor.toMethodType());
    }
    return link(name, descriptor, critical);
}

/** Called by the generated holders: links {@code name} with {@code descriptor}. */
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

- **Extension commands** are checked against the extension list *first*, because on GLX the
  address would be non-null anyway (§5.1, D24). `GLTest.missingCommandsThrowOnlyWhenCalled`
  (§28.1) links a real bindless command against an invented extension name and checks that the
  result throws.
- **A missing command** (null address) does not fail here. It links to a stand-in that throws
  `UnsupportedOperationException` *when called*. Optional features (bindless textures on a driver
  without them) therefore never break startup; code asks `GLCapabilities.has` before using them.
- **Critical** linking needs both the allow-list's `true` and the global switch.
  `Linker.Option.critical(true)` also allows heap segments (§5.3).

The stand-in is built from method-handle combinators, so it has exactly the command's type:

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

Step by step: `throwException(R, UOE.class)` is a handle of type `(UnsupportedOperationException)R`
that throws its argument. `insertArguments` fixes that argument to one exception object, giving
`()R`. `dropArguments` adds the command's parameter types in front and ignores them, giving exactly
`(params)R`, the type `invokeExact` in the generated method expects. (The exception is created
once, at link time, so its stack trace points at the link, and the message names the command.)

Finally the rethrow helper the generated methods call:

```java
public static RuntimeException rethrow(Throwable t) {
    if (t instanceof RuntimeException r) {
        return r;
    }
    if (t instanceof Error e) {
        throw e;
    }
    return new IllegalStateException(t);
}
```

It *returns* a `RuntimeException` so the generated code can write `throw GLLinker.rethrow(t);`,
which tells the compiler the method ends there. Runtime exceptions come back unchanged, errors are
thrown as they are, and anything checked is wrapped.

**What can go wrong: calling GL before loading it.** A holder whose initializer throws is broken
for the rest of the JVM's life. Calling, say, `glFinish()` before `GLLoader.init` makes `address`
throw `IllegalStateException` inside the holder's static initializer; the JVM reports that as
`ExceptionInInitializerError`, marks the class as failed, and every later call of `glFinish`, even
after GL is loaded, throws `NoClassDefFoundError: Could not initialize class
dev.jeddy.gl.ffi.GL46$glFinish`. (This was checked for this chapter with a scratch program that
called the generated `glFinish` twice before installing a resolver and once after.) The rule is
simple: load GL first, on the main thread, before anything else touches `GL46` (§27.1). Exercise
17 asks you to reproduce it.

## 12. Step 4: GLLoader and GLCapabilities

[`GLLoader`](../../modules/gl/src/main/java/dev/jeddy/gl/GLLoader.java) is what applications call:
install the resolver, read what the context offers, refuse anything older than 4.6.

```java
public final class GLLoader {
    private static final Logger LOG = Log.logger("gl");

    /** The oldest context Jeddy runs on. */
    public static final int REQUIRED_MAJOR = 4;

    /** The oldest context Jeddy runs on. */
    public static final int REQUIRED_MINOR = 6;

    private static volatile GLCapabilities capabilities;

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
```

- **The order matters.** The resolver goes in first, because reading the capabilities already
  calls GL. The extension test goes in *after* the capabilities are read, because it is
  `caps::has`. The commands `readCapabilities` uses are all core, so they link without consulting
  the test.
- **A clear refusal.** A driver offering 4.5 or less gets an exception naming what it does offer,
  instead of a crash at the first 4.6 call.
- **One log line** names the version, renderer, vendor, GLSL version and whether critical linking
  is on: the first thing to read in a bug report from someone else's GPU.
- **The method reference** `ctx.window()::glProcAddress` (§15.4) turns `Window.glProcAddress` into
  the `Function<String, MemorySegment>` this method wants.

Two small accessors let other code find the capabilities later (`capabilities()`, which throws if
`init` has not run, and `isLoaded()`), and `hasCommand(name)` reports whether the resolver returns
an address, with a Javadoc warning that on GLX every name does.

`readCapabilities` asks GL its questions:

```java
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
```

- **A confined arena** (L00 §12) for the 8 bytes of scratch: this runs once, at startup, a cold
  path where allocation is fine (DESIGN §4.3). The scratch is a `JAVA_LONG` because one query
  returns 64 bits.
- **Out-parameters.** `glGetIntegerv(pname, data)` writes its answer into memory you pass, so the
  answer is read back from the scratch. `getInt` zeroes the scratch first: if the query fails
  (an unknown enum on an old driver), GL leaves the memory alone and the answer is 0 rather than
  the previous query's value.
- **`glGetStringi(GL_EXTENSIONS, e)`** lists extensions one by one; core profiles no longer return
  them as one long string.
- **`GL_MAX_SHADER_STORAGE_BLOCK_SIZE` is 64-bit** (`glGetInteger64v`): it can exceed 2 GiB.
- **Strings.** `glGetString` returns a pointer to a NUL-terminated C string as a zero-length
  segment (FFM cannot know its length). `reinterpret(Long.MAX_VALUE)` declares it unbounded so
  that `getString` can read up to the NUL; this is a restricted method (a wrong use could read
  past the string), which is why the `gl` module has native access.
- **`glGetError()`** after the anisotropy query: the enum became core only in 4.6, and a driver
  reporting less would set an error flag that would otherwise surface in an unrelated later check.

[`GLCapabilities`](../../modules/gl/src/main/java/dev/jeddy/gl/GLCapabilities.java) is an
immutable **record** of those twenty values (a Java record is a data class whose fields are set
once, by its constructor):
version, vendor, renderer, GLSL version, the driver family, whether the context is a debug
context, the extension set, and the limits the engine needs (`maxTextureSize`,
`maxArrayTextureLayers`, `maxUniformBlockSize`, `maxShaderStorageBlockSize`, the two buffer offset
alignments of §7.5, `maxColorAttachments`, `maxSamples`, `maxVertexAttribs`,
`maxComputeWorkGroupInvocations`, `maxAnisotropy`). Its own code is short:

```java
/** Copies the extension set. */
public GLCapabilities {
    extensions = Set.copyOf(extensions);
}

/** Returns {@code true} if the context reports {@code extension}. */
public boolean has(String extension) {
    return extensions.contains(extension);
}

/** Returns {@code true} if the context version is at least {@code major.minor}. */
public boolean atLeast(int major, int minor) {
    return this.major > major || (this.major == major && this.minor >= minor);
}
```

- The **compact constructor** replaces the caller's set with an unmodifiable copy, so the record
  is truly immutable and safe to share between threads (its Javadoc: "Thread ownership:
  immutable").
- **`atLeast`** compares versions as pairs, like the generator: 4.10 would count as newer than
  4.6.

And the driver family, for workarounds:

```java
static Driver classify(String vendor, String renderer, String version) {
    String v = vendor.toLowerCase(java.util.Locale.ROOT);
    String r = renderer.toLowerCase(java.util.Locale.ROOT);
    if (r.contains("llvmpipe") || r.contains("softpipe") || r.contains("swrast")) {
        return Driver.SOFTWARE;
    }
    if (version.contains("Mesa") || v.contains("mesa") || v.contains("x.org")) {
        return Driver.MESA;
    }
    if (v.contains("nvidia")) {
        return Driver.NVIDIA;
    }
    if (v.contains("intel")) {
        return Driver.INTEL;
    }
    // "ATI Technologies Inc." as a word, not a substring: "corporation" contains "ati".
    if (v.contains("amd") || v.equals("ati") || v.startsWith("ati ")) {
        return Driver.AMD;
    }
    return Driver.OTHER;
}
```

The order of the checks carries meaning. Software rasterizers come first because Mesa's llvmpipe
also says "Mesa". Mesa's hardware drivers come next because they report the hardware maker as the
vendor (an AMD card under Mesa's radeonsi driver reports "AMD"; older Mesa versions said
"X.Org") while their version string says "Mesa", and workarounds belong to the driver, not the
chip. Intel is tested before AMD, and AMD's old name is matched as a *word*, because short
fragments hide inside longer words: *corpor**ati**on* contains `ati`, so a test for the substring
`ati` would call "Intel Corporation" (and Microsoft's "Microsoft Corporation") an AMD driver.
`"ati"` alone or `"ati "` at the start ("ATI Technologies Inc.") is the company name; `amd`
appears inside no common English word. That bug was real: until commit ab48d28 the AMD test was
`v.contains("ati") || v.contains("amd")` and ran before the Intel test; exercise 18 retraces it,
and `GLCapabilitiesTest` (§28.1) now pins the cases. `Locale.ROOT` makes `toLowerCase` independent
of the user's language settings (in a Turkish locale, `"I".toLowerCase()` is a dotless ı, and
"INTEL" would never match). The renderer uses the family for one warning so far: on Windows with
an Intel GPU it logs how to make a laptop use its discrete GPU instead (§27.1).

## 13. Step 5: the window and its context

[`Window.create`](../../modules/window/src/main/java/dev/jeddy/window/Window.java) sets the
context attributes before it creates the window (SDL needs them to pick a pixel format), then
creates the context and makes it current. The attributes:

```java
private static void setGlAttributes(WindowConfig config) {
    SDL_GL_ResetAttributes();
    int contextFlags = SDL_GL_CONTEXT_FORWARD_COMPATIBLE_FLAG();
    if (config.glDebug) {
        contextFlags |= SDL_GL_CONTEXT_DEBUG_FLAG();
    }
    glAttribute(SDL_GL_CONTEXT_MAJOR_VERSION(), 4);
    glAttribute(SDL_GL_CONTEXT_MINOR_VERSION(), 6);
    glAttribute(SDL_GL_CONTEXT_PROFILE_MASK(), SDL_GL_CONTEXT_PROFILE_CORE());
    glAttribute(SDL_GL_CONTEXT_FLAGS(), contextFlags);
    glAttribute(SDL_GL_DOUBLEBUFFER(), 1);
    glAttribute(SDL_GL_RED_SIZE(), 8);
    glAttribute(SDL_GL_GREEN_SIZE(), 8);
    glAttribute(SDL_GL_BLUE_SIZE(), 8);
    glAttribute(SDL_GL_ALPHA_SIZE(), 0); // no desktop compositing; alpha visuals are rarer
    glAttribute(SDL_GL_DEPTH_SIZE(), config.depthBits);
    glAttribute(SDL_GL_STENCIL_SIZE(), 0);
    glAttribute(SDL_GL_MULTISAMPLEBUFFERS(), 0);
    // The final pass writes sRGB-encoded values itself, so the default framebuffer is plain
    // RGB8: sRGB-capable visuals are missing on some X servers (DESIGN D23).
    glAttribute(SDL_GL_FRAMEBUFFER_SRGB_CAPABLE(), 0);
}

private static void glAttribute(int attribute, int value) {
    Sdl.check(SDL_GL_SetAttribute(attribute, value), "SDL_GL_SetAttribute");
}
```

- **`SDL_GL_ResetAttributes()`** first, so nothing left over from an earlier window leaks in.
- **4.6 core, forward-compatible.** Forward-compatible removes even the deprecated features a core
  context might still accept: anything old fails at once instead of working on one driver only.
- **The debug flag** only when asked (`WindowConfig.glDebug`). A debug context reports errors and
  warnings through `KHR_debug` (§16); it may be slower, so it is off by default.
- **Double buffering** (§4.3).
- **8-bit red, green and blue, no alpha.** The window's alpha channel would only matter for
  blending the window itself with the desktop, which a game does not do, and visuals with alpha
  are offered less often.
- **Depth bits from the configuration**, which defaults to 0: the engine renders into depth
  *textures* of its own (§21) and never needs a depth buffer in the window (D23 records "no
  alpha, no depth").
- **No stencil, no multisampling** (§2.12, §2.14).
- **No sRGB framebuffer (D23).** GL could encode linear values to sRGB automatically on write
  (§2.14), but Xvfb, the virtual X server CI runs the GPU tests in, offers no sRGB-capable
  visuals, so requesting one made window creation fail there. The last post pass encodes in its
  shader instead, which behaves identically on every driver.
- **`Sdl.check`** turns SDL's `false` return into an exception carrying `SDL_GetError()`'s text.

Then the context:

```java
private void createContext(WindowConfig config) {
    MemorySegment context = SDL_GL_CreateContext(handle);
    if (context.address() == 0) {
        throw new SdlException(
                "SDL_GL_CreateContext",
                Sdl.error() + " (Jeddy needs an OpenGL 4.6 core profile driver)");
    }
    glContext = context;
    Sdl.check(SDL_GL_MakeCurrent(handle, glContext), "SDL_GL_MakeCurrent");
    setVSync(config.vsync);
}
```

`SDL_GL_CreateContext` fails on a driver without 4.6 core, and the message says what is missing.
`SDL_GL_MakeCurrent` binds the context to this thread (§4.1): from now on GL calls on the main
thread go to it. The swap interval can only be set with a current context, hence `setVSync` last.

The three methods GL code uses afterwards:

```java
/** Presents the back buffer. Allocates nothing. */
public void swap() {
    SdlThread.assertMainThread("SDL_GL_SwapWindow");
    Sdl.check(SDL_GL_SwapWindow(handle), "SDL_GL_SwapWindow");
}

public MemorySegment glProcAddress(String name) {
    SdlThread.assertMainThread("SDL_GL_GetProcAddress");
    return SDL_GL_GetProcAddress(CStrings.of(name));
}

public VSync setVSync(VSync requested) {
    SdlThread.assertMainThread("SDL_GL_SetSwapInterval");
    int interval =
            switch (requested) {
                case OFF -> 0;
                case ON -> 1;
                case ADAPTIVE -> -1;
            };
    if (SDL_GL_SetSwapInterval(interval)) {
        vsync = requested;
    } else if (requested == VSync.ADAPTIVE && SDL_GL_SetSwapInterval(1)) {
        LOG.info("adaptive vsync is not supported, using vsync on");
        vsync = VSync.ON;
    } else {
        LOG.warn("cannot set swap interval {}: {}", interval, Sdl.error());
    }
    return vsync;
}
```

- Every method checks the thread first (§4.2).
- **`glProcAddress`** is the resolver `GLLoader` installs. `CStrings.of(name)` caches the UTF-8
  C string of each name for the rest of the run, so resolving the same name twice does not
  allocate twice; each holder resolves its name once anyway.
- **`setVSync`** returns what is actually in effect: adaptive (−1) falls back to on (1) where the
  driver refuses it (§4.3), and the main loop uses the result to decide whether the swap paces the
  frames (§15.6).

## 14. Step 6: GLShaders

[`GLShaders`](../../modules/gl/src/main/java/dev/jeddy/gl/GLShaders.java) is the plain way to
build a program from two source strings, with the driver's log in the exception. The samples, the
tests and the debug renderers use it; the engine's materials use the program cache of §25.

```java
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
```

- `glShaderSource(GLuint shader, GLsizei count, const GLchar *const *string, const GLint
  *length)` takes an **array of pointers** to strings (a shader may be given in pieces). So the
  code puts the text in native memory (`allocateFrom(String)` writes UTF-8 bytes and a terminating
  NUL), then allocates a one-element array of `ADDRESS` holding the text's address: that is the
  `const GLchar *const *`.
- **`length = NULL`** means "every string is NUL-terminated".
- **`GL_COMPILE_STATUS`** is read through an out-parameter, as in §12.
- **On failure** the driver's info log goes into the exception, and the shader is deleted first
  so nothing leaks.
- **A confined arena** frees the native copies when the method ends: the driver copies the source
  during `glShaderSource`, so the Java side need not keep it. Shader compilation is a cold path;
  allocation is fine here.

Linking:

```java
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
```

After linking, the program holds the compiled code; the shader objects are no longer needed, so
they are detached and deleted at once (a shader still attached to a program is only *marked* for
deletion). The varargs `int...` would allocate an array per call, which the hot-path rules forbid;
this is a cold path. `program(vertexSource, fragmentSource)` compiles both stages and links them,
deleting the vertex shader if the fragment shader fails to compile.

`infoLog` asks for the log's length, allocates that many bytes (at least 1) and reads it:

```java
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
```

## 15. Step 7: hello triangle

### 15.1 The plan

[`HelloTriangle`](../../samples/hello-triangle/src/main/java/dev/jeddy/samples/triangle/HelloTriangle.java)
is the smallest Jeddy program that draws: one spinning triangle, raw GL calls, no renderer. It was
the first visible milestone (M3-04) and stays the acceptance check for the window, the loader and
the bindings on each driver: CI runs it for three seconds on every push to `main` and every pull
request (§30). It needs:

1. two shaders (a vertex and a fragment stage);
2. a buffer holding three vertices;
3. a vertex array saying how the buffer's bytes become shader inputs;
4. per frame: set the viewport, clear, select the program, set two uniforms, draw three vertices.

The class implements two interfaces of the `app` module: `FrameRenderer`, whose `init`, `render`
and `shutdown` run on the main thread with the context current, and `Game`, whose methods run on
the game thread. Line numbers in the headings refer to the file.

### 15.2 The shaders (lines 40–62)

The vertex shader:

```glsl
#version 460 core
layout(location = 0) in vec2 position;
layout(location = 1) in vec3 color;
layout(location = 0) uniform float angle;
layout(location = 1) uniform float aspect;
out vec3 vColor;
void main() {
    float c = cos(angle), s = sin(angle);
    vec2 p = mat2(c, s, -s, c) * position;
    gl_Position = vec4(p.x / aspect, p.y, 0.0, 1.0);
    vColor = color;
}
```

- **`#version 460 core`** selects GLSL 4.60. It must be the first statement; every Jeddy shader
  starts with it.
- **`in` variables** are vertex attributes, filled by the vertex fetch of §2.3.
  `layout(location = 0)` fixes the attribute number the Java side describes (§15.4).
- **`uniform`s** stay the same for the whole draw. `layout(location = 0)` again fixes the number,
  so Java sets it by number (§8.2).
- **`mat2(c, s, -s, c)`**: GLSL matrix constructors take *columns* (column-major, chapter 02). The
  first column is $(c, s)$, the second $(-s, c)$, so the product is
  $(c\,x - s\,y,\ \ s\,x + c\,y)$: a counter-clockwise rotation by `angle` radians (chapter
  [03](03-rotations-and-transforms.md)). At angle $\pi/2$, $c = 0$ and $s = 1$, and the top vertex
  $(0, 0.7)$ moves to $(-0.7, 0)$: a quarter turn to the left.
- **`gl_Position`** is the clip-space output (§2.4). With $w = 1$ the perspective divide changes
  nothing, so these are NDC directly. The sample keeps GL's default clip control, in which $z$
  from $-1$ to $1$ is the visible range, so $z = 0$ is in the middle (and there is no depth buffer
  anyway). Dividing $x$ by the aspect ratio keeps the triangle from stretching:
  NDC spans $[-1, 1]$ on both axes whatever the window's shape, so on a 960 × 540 window one unit of
  $x$ is 480 pixels and one unit of $y$ only 270.
- **`out vec3 vColor`** is interpolated across the triangle (§2.10) and arrives in the fragment
  shader's `in vec3 vColor`, matched by name (§8.2).

The fragment shader:

```glsl
#version 460 core
in vec3 vColor;
layout(location = 0) out vec4 color;
void main() { color = vec4(pow(vColor, vec3(1.0 / 2.2)), 1.0); }
```

It writes to output location 0, which goes to the window's back buffer. `pow(x, 1/2.2)` is an
approximate sRGB encoding (§2.14): the vertex colors are linear, the window expects sRGB, and the
default framebuffer does not encode by itself (D23). The red vertex $(1, 0.2, 0.1)$ is written as
$(1,\ 0.2^{1/2.2},\ 0.1^{1/2.2}) = (1,\ 0.4812,\ 0.3511)$, stored as the bytes
$(255, 123, 90)$. The exact sRGB curve differs slightly ($0.2$ encodes to $0.4845$ exactly), and
much more near black ($0.01$ encodes to $0.0999$ exactly but $0.1233$ with the power); the real post
pass uses the exact one (chapter [08](08-effects-and-post.md)).

### 15.3 `main` (lines 81–97)

```java
public static void main(String[] args) {
    if (DEBUG) {
        System.setProperty("jeddy.gl.critical", "false");
    }
    HelloTriangle sample = new HelloTriangle();
    AppConfig config =
            new AppConfig()
                    .app("Jeddy hello triangle", "0.1.0", "dev.jeddy.samples.triangle")
                    .window(
                            new WindowConfig()
                                    .title("Jeddy – hello triangle")
                                    .size(960, 540)
                                    .glDebug(DEBUG)
                                    .vsync(VSync.ADAPTIVE))
                    .renderer(sample);
    System.exit(JeddyApp.run(config, sample));
}
```

`DEBUG` is `Boolean.getBoolean("jeddy.gl.debug")`. In debug mode critical linking must be off
*before* `GLLinker` initializes (§11). `JeddyApp.runMain` does the same thing itself when the
property is set, before it creates the window, so under `JeddyApp` the line is redundant; it keeps
the sample correct even if its startup changes.

Note that a `-D` on the `./gradlew` command line sets a property of Gradle, not of the program.
To try the debug mode, pass it through the `java` launcher's environment variable, which the run
task inherits: `JDK_JAVA_OPTIONS=-Djeddy.gl.debug=true ./gradlew :hello-triangle:run` (CI passes
its quit-after settings the same way). Adding `jvmArgs(...)` to the run task would look simpler,
but in Gradle 9 it replaces the application's default JVM arguments, the launcher flags, instead
of adding to them (DESIGN §14).

`JeddyApp.run` creates the window and the context on the main thread, starts the game thread,
and calls the `FrameRenderer` methods on the main thread. The sample is both the renderer and the
`Game`: its `update` only checks for Escape and for the quit timer
(`JEDDY_SAMPLE_QUIT_AFTER` seconds, used by CI). The timer reads the field `start`, which the main
thread writes in `init`; that is why `start` is `volatile` (L00 §9.5).

### 15.4 `init`: program, buffer and vertex array (lines 100–127)

```java
GLCapabilities caps = GLLoader.init(ctx.window()::glProcAddress);
if (DEBUG) {
    GLDebug.enable(true);
}
program = GLShaders.program(VERTEX, FRAGMENT);
```

Load GL (§12), optionally install the debug callback (§16), then compile both stages and link
them (§14). The order is forced: nothing may touch a GL command before `GLLoader.init` (§11).

```java
float[] vertices = {
    -0.6f, -0.5f, 1f, 0.2f, 0.1f,
    0.6f, -0.5f, 0.1f, 1f, 0.2f,
    0f, 0.7f, 0.2f, 0.3f, 1f
};
MemorySegment id = arena.allocate(ValueLayout.JAVA_INT);
glCreateBuffers(1, id);
vbo = id.get(ValueLayout.JAVA_INT, 0);
glNamedBufferStorage(
        vbo, vertices.length * 4L, arena.allocateFrom(ValueLayout.JAVA_FLOAT, vertices), 0);
```

- Each vertex is five floats: $x, y$, then $r, g, b$, all in one array (**interleaved**).
- `glCreateBuffers(n, ids)` writes `n` new names into native memory, so the sample allocates one
  `int` in its arena and reads the name back.
- `glNamedBufferStorage` gives the buffer its size, $15 \cdot 4 = 60$ bytes (`4L` makes the
  multiplication `long`, the type of `GLsizeiptr`, L00 §4.3), and its contents, once and for all.
  Flags `0` means the CPU will never change it again, so the driver may put it in the fastest GPU
  memory (lesson 1, §4). The data is copied during the call, so the native copy could be freed
  right after.

```java
glCreateVertexArrays(1, id);
vao = id.get(ValueLayout.JAVA_INT, 0);
glVertexArrayVertexBuffer(vao, 0, vbo, 0, 5 * 4);
glEnableVertexArrayAttrib(vao, 0);
glVertexArrayAttribFormat(vao, 0, 2, GL_FLOAT, false, 0);
glVertexArrayAttribBinding(vao, 0, 0);
glEnableVertexArrayAttrib(vao, 1);
glVertexArrayAttribFormat(vao, 1, 3, GL_FLOAT, false, 2 * 4);
glVertexArrayAttribBinding(vao, 1, 0);
```

The vertex array tells the fetch unit where attributes 0 and 1 come from (§18 explains the format
and binding split in full). Binding slot 0 is the buffer `vbo`, starting at byte 0, one vertex
every 20 bytes. Attribute 0 (`position`) is 2 floats at byte 0 of each vertex; attribute 1
(`color`) is 3 floats at byte 8; both read from slot 0. `false` means "not normalized" (§18.3). In
memory:

```
byte:   0    4    8    12   16 | 20   24   28   32   36 | 40 ...
       [ x0 | y0 | r0 | g0 | b0 | x1 | y1 | r1 | g1 | b1 | x2 ...
        attr 0    attr 1         attr 0    attr 1
       |<------ stride 20 ----->|
```

### 15.5 `render`: one frame (lines 130–140)

```java
public void render(MainContext ctx, int index, boolean fresh) {
    int w = ctx.window().pixelWidth();
    int h = ctx.window().pixelHeight();
    glViewport(0, 0, w, h);
    glClearNamedFramebufferfv(0, GL_COLOR, 0, clearColor);
    glUseProgram(program);
    glProgramUniform1f(program, 0, (System.nanoTime() - start) * 1e-9f);
    glProgramUniform1f(program, 1, (float) w / Math.max(1, h));
    glBindVertexArray(vao);
    glDrawArrays(GL_TRIANGLES, 0, 3);
}
```

- **`glViewport`** maps NDC onto the window in *pixels* (§2.7). `pixelWidth` differs from the
  window's size on high-density displays, where one window unit is several pixels.
- **`glClearNamedFramebufferfv(0, GL_COLOR, 0, clearColor)`** clears draw buffer 0 of framebuffer
  0, which for the window is its back buffer, to a dark blue. `clearColor` is a 4-float segment
  allocated once, in a field, so the call allocates nothing per frame. (Clears obey the color mask
  and the scissor test: a mask left off by other code would make this clear do nothing.)
- **`glUseProgram`** selects the shaders. **`glProgramUniform1f(program, location, value)`** sets
  uniform `location` of a given program, the DSA way, with no need to have it bound. Location 0 is
  `angle`: seconds since `init`, so the triangle turns one radian per second. Location 1 is
  `aspect`; `Math.max(1, h)` avoids dividing by zero for a zero-height window.
- **`glBindVertexArray`** selects the vertex layout; **`glDrawArrays(GL_TRIANGLES, 0, 3)`** draws
  vertices 0, 1 and 2 as one triangle.

Seven GL calls per frame. `glUseProgram`, `glProgramUniform1f`, `glBindVertexArray` and
`glViewport` are on the critical allow-list; the clear and the draw are not.

### 15.6 Who swaps

Nobody in the sample. `JeddyApp`'s main loop
([`JeddyApp.java`](../../modules/app/src/main/java/dev/jeddy/app/JeddyApp.java)) swaps right after
the renderer returns:

```java
while (running) {
    main.pump.pump();
    main.queue.drain();
    boolean fresh = frames.acquire();
    int wantedCap;
    if (window.shouldSkipRendering()) {
        wantedCap = config.backgroundFps;
    } else {
        if (config.renderer != null) {
            config.renderer.render(main, frames.readIndex(), fresh);
        }
        if (window.hasGlContext()) {
            window.swap();
        }
        boolean pacedBySwap = window.hasGlContext() && window.vsync() != VSync.OFF;
        wantedCap = pacedBySwap ? 0 : config.maxFps;
    }
    if (wantedCap != cap) {
        limiter.setTargetFps(wantedCap);
        cap = wantedCap;
    }
    limiter.waitForNextFrame();
    main.frame++;
    if (main.frame == config.quitAfterFrames) {
        requestStop();
    }
    if (config.observer != null) {
        config.observer.onMainFrame(main);
    }
}
```

Per frame the main thread pumps SDL events, drains the game thread's command queue (DESIGN §5),
takes the newest frame the game thread published, renders it and swaps. When vsync is on, the swap
paces the loop (§4.3), so the software frame limiter is switched off (`wantedCap = 0`); with vsync
off the limiter caps the rate at `maxFps`. A minimized or hidden window skips rendering and runs at
the low background rate. Chapter [01](01-architecture.md) explains the rest of this loop.

### 15.7 `shutdown` (lines 143–150)

```java
public void shutdown(MainContext ctx) {
    MemorySegment id = arena.allocate(ValueLayout.JAVA_INT);
    id.set(ValueLayout.JAVA_INT, 0, vbo);
    glDeleteBuffers(1, id);
    id.set(ValueLayout.JAVA_INT, 0, vao);
    glDeleteVertexArrays(1, id);
    glDeleteProgram(program);
}
```

`glDelete*` take arrays of names in C, so each name goes through native memory. This runs on the
main thread, before `JeddyApp` closes the window and with it the context; after that, no GL call
could reach the objects anyway.

### 15.8 One frame, followed through the pipeline

Put Part A and the code side by side for one frame at time 0 in a 960 × 540 window:

1. **Command processor**: seven commands, ending with a draw of 3 vertices.
2. **Vertex fetch**: vertices 0, 1, 2; position at bytes $20i$, color at bytes $20i + 8$.
3. **Vertex shader**, 3 invocations: positions $(-0.3375, -0.5)$, $(0.3375, -0.5)$, $(0, 0.7)$
   with $z = 0$, $w = 1$; colors passed through.
4. **Clipping**: all three inside, nothing cut.
5. **Divide and viewport**: $w = 1$; window positions $(318, 135)$, $(642, 135)$, $(480, 459)$.
6. **Culling**: off (the default state; the sample never enables it). The signed area is
   $+52{,}488$ anyway: front-facing.
7. **Rasterization**: about 52,500 pixel centers covered, each a fragment.
8. **Interpolation**: $w = 1$ at every vertex, so perspective correction changes nothing. The
   center of the triangle gets the average color, $(0.4333, 0.5, 0.4333)$.
9. **Fragment shader**, about 52,500 invocations: the center pixel writes
   $(0.6838, 0.7297, 0.6838)$, stored as $(174, 186, 174)$.
10. **Depth test**: none; the window has no depth buffer.
11. **Blending**: off; each fragment replaces the clear color.
12. **Framebuffer**: the back buffer, shown by the swap.

### 15.9 What the sample leaves out

It has no depth buffer, no textures, no state cache, no batching, and it calls GL directly. The
rest of Part B shows how Jeddy's resource layer generalizes each piece, and chapter 06 how the
renderer draws twenty thousand objects with a handful of calls. `GLTest.helloTriangleRendersWithDsa`
(§28.1) is a similar triangle (indexed, one flat color) as a GPU test that reads the pixels back.

## 16. Step 8: GLDebug

A wrong GL call does not throw. It sets an error flag, which you would have to poll with
`glGetError` after every call, and is usually ignored (L00 §10.5). The `KHR_debug` mechanism
(core since 4.3) is better: the driver reports errors, undefined behaviour, performance warnings
and portability problems as text messages, through a callback function you give it.
[`GLDebug`](../../modules/gl/src/main/java/dev/jeddy/gl/GLDebug.java) installs that callback and
adds object labels and debug groups. Its fields:

```java
public final class GLDebug {
    private static final Logger LOG = Log.logger("gl");
    private static final AtomicLong ERRORS = new AtomicLong();
    private static final AtomicLong WARNINGS = new AtomicLong();
    private static MemorySegment callback;
    private static volatile boolean groupsEnabled;
```

The counters are `AtomicLong`s because the Javadoc promises they are thread-safe: a test thread
may read them while the driver calls back on the GL thread. `callback` is the native function
pointer, created once; `groupsEnabled` switches debug groups on (below).

### 16.1 Installing the callback

```java
public static synchronized boolean enable(boolean synchronous) {
    if (GLLinker.criticalEnabled()) {
        throw new IllegalStateException(
                "KHR_debug needs -Djeddy.gl.critical=false (DESIGN §4.4)");
    }
    if (callback == null) {
        try {
            MethodHandle target =
                    MethodHandles.lookup()
                            .findStatic(
                                    GLDebug.class,
                                    "onMessage",
                                    MethodType.methodType(
                                            void.class,
                                            int.class,
                                            int.class,
                                            int.class,
                                            int.class,
                                            int.class,
                                            MemorySegment.class,
                                            MemorySegment.class));
            callback =
                    Linker.nativeLinker()
                            .upcallStub(
                                    target,
                                    FunctionDescriptor.ofVoid(
                                            ValueLayout.JAVA_INT,
                                            ValueLayout.JAVA_INT,
                                            ValueLayout.JAVA_INT,
                                            ValueLayout.JAVA_INT,
                                            ValueLayout.JAVA_INT,
                                            ValueLayout.ADDRESS,
                                            ValueLayout.ADDRESS),
                                    Arena.global());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
    glEnable(GL_DEBUG_OUTPUT);
    if (synchronous) {
        glEnable(GL_DEBUG_OUTPUT_SYNCHRONOUS);
    } else {
        glDisable(GL_DEBUG_OUTPUT_SYNCHRONOUS);
    }
    glDebugMessageCallback(callback, MemorySegment.NULL);
    // Notifications (buffer placement and the like) are noise in normal runs.
    glDebugMessageControl(
            GL_DONT_CARE,
            GL_DONT_CARE,
            GL_DEBUG_SEVERITY_NOTIFICATION,
            0,
            MemorySegment.NULL,
            false);
    groupsEnabled = true;
    return GLLoader.isLoaded() && GLLoader.capabilities().debugContext();
}
```

- **It refuses while critical linking is on** (§5.3, D12): an upcall from inside a critical
  downcall can crash the JVM. Failing here, with a message naming the switch, is better than a
  crash at the first GL error.
- **An upcall stub.** The driver expects a C function pointer with the signature
  `void callback(GLenum source, GLenum type, GLuint id, GLenum severity, GLsizei length, const
  GLchar *message, const void *userParam)`: five integers and two pointers.
  `Linker.upcallStub(target, descriptor, arena)` creates a small piece of native code with exactly
  that signature which, when C calls it, enters Java and invokes `target`, the static method
  `onMessage` found by `findStatic`. It is the mirror image of a downcall (§5.2).
- **`Arena.global()`**: the stub must live as long as the driver may call it, which is until the
  process ends, so it is allocated in the arena that is never closed (never `Arena.ofAuto()`,
  CLAUDE.md rule 3). It is created **once** (`callback == null`) and `enable` is `synchronized`, so
  two threads cannot race to create two stubs.
- **Synchronous mode** (`GL_DEBUG_OUTPUT_SYNCHRONOUS`) makes the driver call back *inside* the GL
  call that caused the message, on the same thread. That is what makes the log useful: the
  callback can record a Java stack trace, and the trace ends in the offending call. Without it the
  driver may report later, from another thread.
- **`glDebugMessageControl(..., NOTIFICATION, ..., false)`** mutes the lowest severity: drivers
  report things like "buffer placed in video memory" for every buffer.
- **The return value** says whether the context really is a debug context (§13). A non-debug
  context may accept the callback and stay silent.

`disable()` removes the callback and turns the output and groups off.

### 16.2 The callback

```java
@SuppressWarnings("unused") // called from native code
private static void onMessage(
        int source,
        int type,
        int id,
        int severity,
        int length,
        MemorySegment message,
        MemorySegment userParam) {
    try {
        LogLevel level;
        if (severity == GL_DEBUG_SEVERITY_HIGH || type == GL_DEBUG_TYPE_ERROR) {
            level = LogLevel.ERROR;
            ERRORS.incrementAndGet();
        } else if (severity == GL_DEBUG_SEVERITY_MEDIUM || severity == GL_DEBUG_SEVERITY_LOW) {
            level = LogLevel.WARN;
            WARNINGS.incrementAndGet();
        } else {
            level = LogLevel.DEBUG;
        }
        if (!LOG.isEnabled(level)) {
            return;
        }
        // length excludes the terminating NUL, which the spec guarantees.
        long bytes = length >= 0 ? length + 1L : Long.MAX_VALUE;
        String text = message.reinterpret(bytes).getString(0, StandardCharsets.UTF_8);
        Throwable where =
                level == LogLevel.ERROR ? new Throwable("GL call that raised it") : null;
        LOG.log(
                level,
                where,
                "GL {} {} #{}: {}",
                sourceName(source),
                typeName(type),
                id,
                text.strip());
    } catch (Throwable t) {
        // Swallow: nothing may propagate into the driver.
    }
}
```

- **Severity to log level.** An error type or a high severity is an error, medium and low are
  warnings, the rest debug. The **counters move before the log-level check**, so tests can count
  errors even when logging is quiet.
- **The message** arrives as a zero-length segment (§12); its length excludes the NUL, so
  `length + 1` bytes are declared and `getString` reads up to the NUL.
- **`new Throwable("GL call that raised it")`** is never thrown. It only captures the current
  stack, which in synchronous mode runs through the driver back to the Java line that called GL,
  and the logger prints it with the message.
- **Nothing may escape.** An exception thrown out of an upcall cannot be passed through C code,
  and the JVM terminates. The whole body sits in `try`/`catch (Throwable)`.
- `sourceName` and `typeName` turn the enums into short words (`api`, `shader`, `error`,
  `performance`, ...) for the log line.

This callback allocates (a `String`, sometimes a `Throwable`), which is acceptable: it runs only
when something is wrong, in a debug run.

### 16.3 Labels, groups and counters

```java
public static long errorCount() {
    return ERRORS.get();
}

public static long warningCount() {
    return WARNINGS.get();
}

public static void label(int identifier, int name, String label) {
    try (Arena arena = Arena.ofConfined()) {
        MemorySegment text = arena.allocateFrom(label, StandardCharsets.UTF_8);
        glObjectLabel(identifier, name, (int) text.byteSize() - 1, text);
    }
}

public static void pushGroup(String name) {
    if (groupsEnabled) {
        glPushDebugGroup(GL_DEBUG_SOURCE_APPLICATION, 0, -1, CStrings.of(name));
    }
}

public static void popGroup() {
    if (groupsEnabled) {
        glPopDebugGroup();
    }
}
```

- **Labels** name an object: `GLDebug.label(GL_BUFFER, name, "mesh positions")`. Debug messages
  then say "mesh positions" instead of "buffer 23", and frame debuggers such as
  [RenderDoc](https://renderdoc.org/) and Nsight show the name. Every Jeddy resource gets a label
  when it is created. The length passed is the UTF-8 byte count without the terminating NUL. A
  label is set once per object, a cold path, so a confined arena per call is fine.
- **Groups** bracket a range of commands with a name; the frame graph (chapter 06) wraps each pass
  in a group named after it, so a capture reads as "shadows", "opaque", "post". `pushGroup` runs
  every frame, so it must not allocate: `CStrings.of` keeps one native copy of each name forever,
  and a fixed set of pass names costs nothing after the first frame. The length −1 means
  "NUL-terminated". Groups are no-ops unless debug output was enabled.
- **Counters** (`errorCount()` for errors and high-severity messages, `warningCount()` for medium
  and low ones) let tests assert that a whole scenario produced no GL error: almost every GPU test
  starts with `long errors = GLDebug.errorCount();` and ends with
  `assertEquals(errors, GLDebug.errorCount());` (§28).

## 17. Step 9: the resource layer

### 17.1 GpuDevice

Everything the renderer creates on the GPU goes through one object per context,
[`GpuDevice`](../../modules/render/src/main/java/dev/jeddy/render/gpu/GpuDevice.java):

```java
public final class GpuDevice implements AutoCloseable {
    private final GLCapabilities caps;
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment scratch = arena.allocate(256, 16);
    private final GpuBuffers buffers = new GpuBuffers(this);
    private final GpuTextures textures = new GpuTextures(this);
    private final GpuSamplers samplers = new GpuSamplers(this);
    private final GpuFramebuffers framebuffers = new GpuFramebuffers(this);
    private final StateCache state = new StateCache();

    /** Creates the device for the current context. */
    public GpuDevice(GLCapabilities caps) {
        this.caps = caps;
    }
```

- **One table per kind of object**: buffers, textures, samplers, framebuffers (§17.2, §19–§21),
  each reachable by an accessor (`device.buffers()`, ...), plus the state cache (§23). The buffer,
  texture and framebuffer tables hand out generational `int` handles (lesson 3, §6–§7) and keep the
  GL name, sizes and flags in parallel arrays at the handle's index (DESIGN §4.1); a handle to a
  destroyed resource fails with "stale ... handle" instead of reaching a GL name that may have been
  reused (§3.1). Samplers are the exception: they are never destroyed one by one, so `GpuSamplers`
  returns the GL sampler name itself, cached by description (§20).
- **`scratch`**: 256 bytes (64 `int`s), 16-aligned, for GL out-parameters such as the name
  `glCreateBuffers` writes. Every create call reuses it instead of allocating a native `int`.
  Main thread only, like the whole class.
- **Field order is construction order.** The arena exists before the scratch is carved from it;
  the tables receive `this` but only store it. The `StateCache` constructor issues GL calls (§23),
  so a `GpuDevice` may only be created once GL is loaded (§27.1).

Two methods complete it. Reversed-Z needs a one-time switch:

```java
public void useReversedZ() {
    dev.jeddy.gl.ffi.GL46.glClipControl(
            dev.jeddy.gl.ffi.GL46.GL_LOWER_LEFT, dev.jeddy.gl.ffi.GL46.GL_ZERO_TO_ONE);
    dev.jeddy.gl.ffi.GL46.glClearDepthf(0f);
}
```

`glClipControl` sets the clip volume of §2.6 and the depth map of §2.7, and `glClearDepthf(0f)`
makes depth clears write 0, the far value under reversed-Z (§2.12). And `close` deletes everything
in dependency order (framebuffers before the textures they point at), then frees the scratch:

```java
@Override
public void close() {
    framebuffers.destroyAll();
    textures.destroyAll();
    buffers.destroyAll();
    samplers.destroyAll();
    arena.close();
}
```

### 17.2 Buffers and the frame ring

Lesson 1 builds these from empty files, so this is a summary; read
[lesson 1](lessons/01-buffers-in-flight.md) for the code and the reasoning.

- **A buffer has no type of its own.** It is a block of GPU-visible bytes, and what it means depends
  on where it is bound: a vertex or index buffer (§18), a uniform or storage buffer (§7), a
  draw-indirect buffer (draw parameters), a pixel-unpack buffer (a texture upload's source, §19.3),
  a parameter buffer (draw counts). One Jeddy buffer, the frame ring, is most of these at once, in
  different ranges.
- **[`GpuBuffers`](../../modules/render/src/main/java/dev/jeddy/render/gpu/GpuBuffers.java)**
  (lesson 1, §7) creates buffers with **immutable storage** (`glNamedBufferStorage`: size and
  allowed uses fixed for life) and two engine flags. `DYNAMIC` adds `GL_DYNAMIC_STORAGE_BIT`, so
  `update` may write the buffer with `glNamedBufferSubData` (the mesh mega buffers and the material
  table). `MAPPED` adds `GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT` and maps the
  whole buffer once, for life (the rings). Flags `0` mean only the GPU ever changes the contents
  (`StaticScene`'s culling tables, `HelloTriangle`'s vertices). `update` takes a raw `long` address
  and calls the `long` overload of §10.7, so it allocates nothing.
- **Persistent coherent mappings** (lesson 1, §3, §6.2) are written with plain memory stores and
  **never read**: the memory is write-combined, and reads are uncached and very slow. Coherent
  means writes become visible to commands issued after them, with no flush call.
- **[`FrameRing`](../../modules/render/src/main/java/dev/jeddy/render/gpu/FrameRing.java)**
  (lesson 1, §8–§13) is one mapped buffer cut into 3 regions, one per frame in flight. Frame $n$
  writes region $n \bmod 3$ only, fences it at `endFrame`, and `beginFrame` polls that region's
  fence three frames later, almost never waiting. `allocate(bytes, alignment)` is a lock-free bump
  allocator (a compare-and-set on a `VarHandle`) that job workers call concurrently, and it aligns
  with `(c + a - 1) & -a` (L00 §6.6) so ranges can be bound (§7.5): with $c = 1000$ and $a = 256$,
  $1255 \mathbin{\&} {-256} = 1024$. The constructor rounds each region to a multiple of
  `max(256, uniformBufferOffsetAlignment)`, so region starts are aligned too, and `allocate` returns
  an offset into the *whole* buffer, the same number for writing into `memory()` and for binding.
  `stalls()` counts the rare frames where `beginFrame` had to wait.
- **Changing a buffer the GPU has already read** goes through `FrameRing.upload`: stage the bytes
  in this frame's region, then `glCopyNamedBufferSubData` into the target. The copy is a command,
  ordered after the frames that read the old bytes. A CPU write with `glNamedBufferSubData` into a
  buffer in flight was silently dropped by an Intel driver (**D103**, lesson 1, §17.3).

### 17.3 The upload ring

Assets arrive while the game runs: a level streams in meshes and textures. Their bytes must reach
GPU buffers and textures without stalling a frame and without a frame spending all its time on
uploads. [`UploadRing`](../../modules/render/src/main/java/dev/jeddy/render/gpu/UploadRing.java)
is a second mapped ring for that. Unlike the frame ring, its allocations are of very different
sizes and outlive their frame until the GPU has copied them, so it is a **circular stream** with a
moving head and a fence per frame that used it (lesson 1, §8.2 lists it as the alternative for
exactly this case).

```java
public final class UploadRing implements AutoCloseable {
    private static final int MAX_FRAMES = 16;

    private final GpuDevice device;
    private final int buffer;
    private final int glName;
    private final MemorySegment memory;
    private final long capacity;
    private long head; // oldest byte still in use (logical)
    private long tail; // next free byte (logical)
    private final long[] frameEnd = new long[MAX_FRAMES];
    private final long[] frameFence = new long[MAX_FRAMES];
    private int pendingFirst;
    private int pendingCount;
    private long budget;
    private long usedThisFrame;
    private long uploadedBytes;

    public UploadRing(GpuDevice device, long capacity, long budgetPerFrame) {
        this.device = device;
        this.capacity = capacity;
        this.budget = budgetPerFrame;
        buffer = device.buffers().create(capacity, GpuBuffers.MAPPED, "upload ring");
        glName = device.buffers().glName(buffer);
        memory = device.buffers().mapping(buffer);
    }
```

- **Logical offsets.** `head` and `tail` only ever grow (a `long` will not overflow in any
  realistic run). The physical position in the buffer is the logical one modulo the capacity.
  Bytes in use are `[head, tail)` logically, so "how full" is simply `tail - head`.
- **A queue of pending frames**, at most 16, in two arrays used as a circular queue
  (`pendingFirst`, `pendingCount`): for each frame that reserved something, where its bytes end
  (`frameEnd`) and the fence placed after its copies (`frameFence`).
- **A budget per frame.** The engine creates it with 64 MiB of capacity and a 32 MiB budget
  (`WorldRenderer.init`, §27.1).

Reserving space:

```java
public long reserve(long bytes, long alignment) {
    if (bytes > capacity) {
        throw new IllegalArgumentException(bytes + " bytes exceed the upload ring");
    }
    if (usedThisFrame > 0 && usedThisFrame + bytes > budget) {
        return -1;
    }
    long start = (tail + alignment - 1) & -alignment;
    long physical = start % capacity;
    if (physical + bytes > capacity) {
        start += capacity - physical; // skip to the start of the buffer
    }
    if (start + bytes - head > capacity) {
        return -1;
    }
    tail = start + bytes;
    usedThisFrame += bytes;
    uploadedBytes += bytes;
    return start % capacity;
}
```

1. **The budget.** Once the frame has reserved something, a reservation that would pass the budget
   is refused (−1: try next frame). The frame's *first* reservation is always allowed, however
   large, so an asset bigger than the budget still makes progress.
2. **Align** the tail (L00 §6.6).
3. **No wrapping inside an allocation.** If the bytes would run past the physical end, skip to the
   start of the buffer; the skipped bytes stay counted as used until the frame is reclaimed.
4. **Full?** The new range must not reach the oldest byte still in use: `start + bytes - head`
   bytes would be in flight, at most `capacity`.
5. Return the **physical** offset, the same number for writing into `memory()` and for the GPU copy.

*Worked example* with the test's ring (capacity 4,096 bytes): `tail` = 3,900, `head` = 1,000, a
request of 512 bytes at alignment 16. `start` = $(3900 + 15) \mathbin{\&} {-16} = 3904$; physical
3,904, and $3904 + 512 = 4416 > 4096$, so `start` skips by $4096 - 3904 = 192$ to 4,096. In flight
would be $4096 + 512 - 1000 = 3608 \le 4096$: granted. `tail` becomes 4,608 and the offset returned
is $4096 \bmod 4096 = 0$, the start of the buffer. Had `head` still been 0, $4608 - 0 > 4096$ and
the answer would be −1. The check is conservative: the skipped bytes count as in flight until the
frames before them are reclaimed, and exercise 20 finds a request it refuses even when the ring is
empty. (Aligning the logical offset aligns the physical one too only because the
capacity is a multiple of the alignment; 64 MiB is a multiple of every alignment the engine uses.)

The copies out of the ring are GPU commands:

```java
public void copyToBuffer(long ringOffset, int dstBufferName, long dstOffset, long bytes) {
    glCopyNamedBufferSubData(glName, dstBufferName, ringOffset, dstOffset, bytes);
}

public void copyToTexture(int textureHandle, int level, long ringOffset, int bytes) {
    device.textures().uploadLevelFromBuffer(textureHandle, level, glName, ringOffset, bytes);
}
```

`copyToTexture` (and `copyToTextureLayer`) uploads a texture level whose "pointer" is an offset
into the ring, bound as the pixel-unpack buffer: §19.3 shows the binding, and the `long` overload
of §10.7 is what lets the offset pass without a segment.

Ending and starting frames:

```java
public void endFrame() {
    if (usedThisFrame == 0) {
        return;
    }
    if (pendingCount == MAX_FRAMES) { // the GPU is far behind: wait for the oldest
        glClientWaitSync(frameFence[pendingFirst], GL_SYNC_FLUSH_COMMANDS_BIT, 5_000_000_000L);
        beginFrameReclaimOnly();
    }
    int slot = (pendingFirst + pendingCount) % MAX_FRAMES;
    frameEnd[slot] = tail;
    frameFence[slot] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    pendingCount++;
}

public void beginFrame() {
    while (pendingCount > 0) {
        long fence = frameFence[pendingFirst];
        int status = glClientWaitSync(fence, 0, 0);
        if (status == GL_TIMEOUT_EXPIRED) {
            break;
        }
        glDeleteSync(fence);
        head = frameEnd[pendingFirst];
        pendingFirst = (pendingFirst + 1) % MAX_FRAMES;
        pendingCount--;
    }
    usedThisFrame = 0;
}
```

- **`endFrame`** fences only frames that copied something, after all their copies (the fence's
  position is what matters, lesson 1, §12). If 16 frames are already pending, it waits (up to five
  seconds, flushing so the fence is submitted, lesson 1, §6.1) for the oldest and reclaims it;
  the code assumes that wait succeeds. `beginFrameReclaimOnly` reclaims without resetting this
  frame's byte count.
- **`beginFrame`** polls (timeout 0) the pending fences **oldest first** and stops at the first that
  has not signalled: space comes back in order, as `head` advances to the end of each finished
  frame's bytes. Then the frame's budget resets.

`close` waits for every pending fence and destroys the buffer. `MeshRegistry` uploads through the
ring (chapter 06); `RingsTest` checks it (§28.3).

## 18. Step 10: vertex arrays

### 18.1 Formats and binding slots

Since GL 4.3 a **vertex array object** (VAO) answers two separate questions (§2.3):

- **Attribute format.** For attribute location $i$: how many components, of which type,
  normalized or not, at which byte offset *inside one vertex*
  (`glVertexArrayAttribFormat(vao, i, size, type, normalized, relativeOffset)`), and which binding
  slot feeds it (`glVertexArrayAttribBinding(vao, i, slot)`). `glEnableVertexArrayAttrib(vao, i)`
  turns the attribute on.
- **Binding slot.** Which buffer, from which byte, with which stride
  (`glVertexArrayVertexBuffer(vao, slot, buffer, offset, stride)`).

Several attributes can share one slot (interleaved data, like `HelloTriangle`), or each stream can
have its own. Swapping a buffer then touches only the slot, never the formats. (The older
`glVertexAttribPointer` set both at once, from whatever buffer was bound to `GL_ARRAY_BUFFER`:
bind-to-edit again, §3.3.) The VAO also remembers the **element (index) buffer**,
`glVertexArrayElementBuffer(vao, buffer)`, so binding the VAO binds the indices too.

### 18.2 The mesh vertex array

Every mesh in Jeddy shares one vertex array, created once in the
[`MeshRegistry`](../../modules/render/src/main/java/dev/jeddy/render/mesh/MeshRegistry.java)
constructor:

```java
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
```

and `bindBuffers` attaches the mega buffers (one buffer per stream for every mesh, sub-allocated,
chapter 06):

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
```

The skin stream's attributes 4 and 5 are described up front but **enabled only in
`createSkins`**, when the first skinned mesh arrives and the skin buffer exists (D80): an enabled
attribute whose binding slot has no buffer would read from no buffer at all, which is undefined. The layout per vertex:

| Binding slot | Stride | Location | Bytes | GL format | GLSL input |
|---|---|---|---|---|---|
| 0 `BINDING_POSITIONS` | 12 | 0 | 0–11 | 3 × `GL_FLOAT` | `vec3 jeddyPosition` |
| 1 `BINDING_ATTRIBUTES` | 16 | 1 | 0–7 | 4 × `GL_SHORT`, normalized | `vec4 jeddyQTangent` |
| | | 2 | 8–11 | 2 × `GL_HALF_FLOAT` | `vec2 jeddyUv0` |
| | | 3 | 12–15 | 2 × `GL_HALF_FLOAT` | `vec2 jeddyUv1` |
| 2 `BINDING_SKIN` | 16 | 4 | 0–7 | 4 × `GL_UNSIGNED_SHORT`, **integer** (`IFormat`) | `uvec4 jeddyJoints` |
| | | 5 | 8–15 | 4 × `GL_UNSIGNED_SHORT`, normalized | `vec4 jeddyWeights` |

The GLSL side is in
[`engine/vertex.glsl`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/engine/vertex.glsl)
and
[`engine/skinning.glsl`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/engine/skinning.glsl):

```glsl
layout(location = 0) in vec3 jeddyPosition;
layout(location = 1) in vec4 jeddyQTangent; // snorm16 x4, normalized by the vertex fetch
layout(location = 2) in vec2 jeddyUv0;
layout(location = 3) in vec2 jeddyUv1;
// ... in skinning.glsl:
layout(location = 4) in uvec4 jeddyJoints;
layout(location = 5) in vec4 jeddyWeights;
```

### 18.3 Normalized, integer and half-float attributes

The fetch unit converts stored integers in one of three ways, chosen by the format call:

- **Normalized signed** (`GL_SHORT` with `normalized = true`, "snorm16"). A $b$-bit signed value
  $c$ becomes $\max\!\left(c / (2^{b-1} - 1),\ -1\right)$; for 16 bits, $c / 32767$. The largest
  value, 32,767, maps to exactly 1, and both −32,767 and −32,768 map to −1 (the `max` clamps the
  extra negative value), so 0 maps to exactly 0. The QTangent quaternion (chapter
  [03](03-rotations-and-transforms.md) for quaternions, D27 for the format) is stored this way:
  23,170 arrives as $23170 / 32767 \approx 0.70711$, close to $\sqrt{1/2}$. The identity tangent
  frame is the quaternion $(0, 0, 0, 1)$, stored as $(0, 0, 0, 32767)$;
  `jeddyTangentFrame` normalizes it (quantization makes the length slightly off 1) and rotates
  $(0, 0, 1)$ and $(1, 0, 0)$ by it, giving the normal $(0, 0, 1)$ and the tangent $(1, 0, 0)$.
- **Normalized unsigned** (`GL_UNSIGNED_SHORT`, `normalized = true`, "unorm16"): $c / (2^b - 1)$,
  here $c / 65535$. A skin weight of one half is stored as $\mathrm{round}(0.5 \cdot 65535) = 32768$
  and arrives as $0.500008$. Weights $(0.5, 0.25, 0.25, 0)$ are stored as
  $(32768, 16384, 16384, 0)$, which add up to 65,536, so the fetched weights sum to $1.0000153$,
  not 1. That is why `jeddySkin` divides by the sum of the weights.
- **Integer** (`glVertexArrayAttribIFormat`): the value stays an integer and must be read by an
  integer input (`uvec4`). Joint indices are indices into the palette, so 3 must stay 3, not become
  `3.0` or $3 / 65535$. Describing an integer input with `glVertexArrayAttribFormat` (or the
  opposite) is a classic mistake; the specification calls the result undefined.

**Half floats** (`GL_HALF_FLOAT`, not normalized) are 16-bit floats (§6.6), converted to `float`
by the hardware. With 10 fraction bits, neighbouring values between 0.5 and 1 are
$2^{-11} \approx 0.000488$ apart: on a 1024-texel texture that is half a texel, on a 2048-texel one
a whole texel. About three decimal digits, enough for texture coordinates in $[0, 1]$ on the
texture sizes the engine uses.

### 18.4 Why separate streams and one array

Depth-only passes (the prepass and the shadow maps) read only location 0, except alpha-tested
variants (which also need the UVs) and skinned ones. Attributes a compiled shader does not use are
inactive, and in practice drivers do not fetch them. With positions in their own stream, a depth pass
therefore fetches 12 bytes per vertex instead of 28 (D27), and every cache line it loads holds
nothing but positions: for a mesh of 10,000 vertices, 120,000 bytes instead of 280,000.

And because one VAO with fixed buffers describes *every* mesh, drawing a different mesh changes
only numbers in the draw command (the first index and the base vertex), never a binding. That is
the precondition for multi-draw-indirect in chapter 06: one bind of this VAO, then one call that
draws hundreds of different meshes.

## 19. Step 11: textures

### 19.1 Formats: GpuFormats

Textures arrive from the cooker as KTX2 files, which name their format with a Vulkan enumeration
(`vkFormat`). [`GpuFormats`](../../modules/render/src/main/java/dev/jeddy/render/gpu/GpuFormats.java)
maps those to GL internal formats and answers the questions uploads need. Condensed (one line per
case; the file lists every format):

```java
public static int internalFormat(int vkFormat) {
    return switch (vkFormat) {
        case Ktx2.VK_FORMAT_R8_UNORM -> GL_R8;
        case Ktx2.VK_FORMAT_R8G8B8A8_SRGB -> GL_SRGB8_ALPHA8;
        case Ktx2.VK_FORMAT_R16G16B16A16_SFLOAT -> GL_RGBA16F;
        case Ktx2.VK_FORMAT_BC4_UNORM_BLOCK -> GL_COMPRESSED_RED_RGTC1;
        case Ktx2.VK_FORMAT_BC5_UNORM_BLOCK -> GL_COMPRESSED_RG_RGTC2;
        case Ktx2.VK_FORMAT_BC7_UNORM_BLOCK -> GL_COMPRESSED_RGBA_BPTC_UNORM;
        case Ktx2.VK_FORMAT_BC7_SRGB_BLOCK -> GL_COMPRESSED_SRGB_ALPHA_BPTC_UNORM;
        // ... 17 more: RG8, RGBA8, R16F, RG16F, RGBA32F, B10G11R11, E5B9G9R9, BC1, BC3, BC4/BC5
        // signed, BC6H
        default -> 0;
    };
}
```

`0` means "not supported". The other helpers:

```java
/** Returns {@code true} for block-compressed internal formats. */
public static boolean isCompressed(int internalFormat) {
    return transferFormat(internalFormat) == 0 && internalFormat != 0;
}

/** Bytes of one 4x4 block of a compressed format (8 for BC1 and BC4, 16 for the rest). */
public static int blockBytes(int internalFormat) {
    return switch (internalFormat) {
        case GL_COMPRESSED_RGB_S3TC_DXT1_EXT,
                GL_COMPRESSED_SRGB_S3TC_DXT1_EXT,
                GL_COMPRESSED_RGBA_S3TC_DXT1_EXT,
                GL_COMPRESSED_SRGB_ALPHA_S3TC_DXT1_EXT,
                GL_COMPRESSED_RED_RGTC1,
                GL_COMPRESSED_SIGNED_RED_RGTC1 ->
                8;
        default -> 16;
    };
}
```

- **`transferFormat` and `transferType`** give, for an uncompressed format, the *pixel transfer*
  description an upload needs: `GL_RGBA8` is uploaded as `GL_RGBA` + `GL_UNSIGNED_BYTE`,
  `GL_RGBA16F` as `GL_RGBA` + `GL_HALF_FLOAT`. Compressed formats have none: their uploads pass
  raw blocks.
- **`isCompressed`** is defined as "has no transfer format": every format the engine supports is
  either in the transfer table or compressed. It is a little indirect, and it stays correct only as
  long as every new uncompressed format is added to the transfer table.
- **`blockBytes`**: 8 for BC1 and BC4, 16 for the rest (§6.7).
- **`requiredExtension`** names the extension a BC1/BC3 format needs (`GL_EXT_texture_compression_s3tc`,
  or `GL_EXT_texture_sRGB` for their sRGB variants); everything else is core. `isSrgb` lists the
  sRGB formats.

### 19.2 Creating storage

[`GpuTextures`](../../modules/render/src/main/java/dev/jeddy/render/gpu/GpuTextures.java) keeps,
per handle, the GL name, internal format, kind, size, layer and level counts in parallel arrays,
plus four arrays for the *tail mirror* of §19.4. The fields:

```java
private final GpuDevice device;
private final HandlePool handles = new HandlePool(64, Handles.MAX_INDEX + 1);
private int[] names = new int[64];
private int[] formats = new int[64];
private int[] kinds = new int[64];
private int[] widths = new int[64];
private int[] heights = new int[64];
private int[] layers = new int[64];
private int[] levels = new int[64];
// The tail mirrors (class comment): level-major, one run of `layers × levelBytes` per tail
// level, in a confined arena per texture; null for textures without a tail.
private MemorySegment[] tails = new MemorySegment[64];
private Arena[] tailArenas = new Arena[64];
private int[] tailFirst = new int[64]; // the first tail level; the level count when none
private boolean[] tailStale = new boolean[64]; // an upload from a PBO bypassed the mirror

/** Every address, so a copy out of raw client memory needs no segment per upload. */
private static final MemorySegment ALL = MemorySegment.NULL.reinterpret(Long.MAX_VALUE);
```

`ALL` is a trick worth knowing. `MemorySegment.NULL` starts at address 0; reinterpreted to the
largest size, it covers every address. `MemorySegment.copy(ALL, address, target, offset, bytes)`
then copies from a raw address the caller holds as a `long`, without creating a segment for it.
(`reinterpret` is restricted for exactly this reason: it lets Java read any address.)

`create` in three stages. First, the target and the storage:

```java
public int create(
        int kind,
        int internalFormat,
        int width,
        int height,
        int layerCount,
        int levelCount,
        String label) {
    String ext = GpuFormats.requiredExtension(internalFormat);
    if (ext != null && !device.caps().has(ext)) {
        throw new UnsupportedOperationException(label + " needs " + ext);
    }
    int target =
            switch (kind) {
                case KIND_2D -> GL_TEXTURE_2D;
                case KIND_2D_ARRAY -> GL_TEXTURE_2D_ARRAY;
                case KIND_CUBE -> GL_TEXTURE_CUBE_MAP;
                case KIND_CUBE_ARRAY -> GL_TEXTURE_CUBE_MAP_ARRAY;
                case KIND_3D -> GL_TEXTURE_3D;
                default -> throw new IllegalArgumentException("texture kind " + kind);
            };
    MemorySegment id = device.scratch();
    glCreateTextures(target, 1, id);
    int name = id.get(ValueLayout.JAVA_INT, 0);
    switch (kind) {
        case KIND_2D, KIND_CUBE ->
                glTextureStorage2D(name, levelCount, internalFormat, width, height);
        case KIND_2D_ARRAY, KIND_3D ->
                glTextureStorage3D(name, levelCount, internalFormat, width, height, layerCount);
        default ->
                glTextureStorage3D(
                        name, levelCount, internalFormat, width, height, layerCount * 6);
    }
    glTextureParameteri(name, GL_TEXTURE_MAX_LEVEL, levelCount - 1);
    if (label != null) {
        GLDebug.label(GL_TEXTURE, name, label);
    }
```

- **The extension check** turns "this driver cannot store BC1" into a clear exception at creation.
- **`glCreateTextures(target, ...)`**: unlike buffers, a texture's target is fixed when it is
  created, so the kind is decided here.
- **Storage** (§6.8). A cube map is "2D" storage (GL allocates its six faces); a 2D array and a 3D
  texture have a third dimension (layers, or depth); a cube array (the `default`) has
  `layerCount * 6` layer-faces.
- **`GL_TEXTURE_MAX_LEVEL`** is set to the last level. The specification already limits an
  immutable texture to its allocated levels; setting it explicitly costs one call at creation and
  states the intent.

Second, record it under a handle (`ensure(i)` grows the parallel arrays by doubling, as in lesson
1 §7.3):

```java
    int h = handles.allocate();
    int i = Handles.index(h);
    ensure(i);
    names[i] = name;
    formats[i] = internalFormat;
    kinds[i] = kind;
    widths[i] = width;
    heights[i] = height;
    layers[i] = layerCount;
    levels[i] = levelCount;
    tailFirst[i] = levelCount;
    tailStale[i] = false;
```

Third, for block-compressed 2D arrays only, set up the tail mirror (§19.4):

```java
    if (kind == KIND_2D_ARRAY && GpuFormats.isCompressed(internalFormat)) {
        for (int l = 0; l < levelCount; l++) {
            if ((width >> l) < 4 || (height >> l) < 4) {
                tailFirst[i] = l;
                break;
            }
        }
        if (tailFirst[i] < levelCount) {
            long bytes = tailOffset(i, levelCount, 0); // one past the last tail level
            tailArenas[i] = Arena.ofConfined();
            tails[i] = tailArenas[i].allocate(bytes, 16);
        }
    }
    return h;
}
```

The **tail** is the run of levels smaller than one block in either direction. For a
1024 × 1024 array, level 9 is $1024 \gg 9 = 2$ texels wide, so `tailFirst` is 9 and the tail is
levels 9 (2 × 2) and 10 (1 × 1).

`create2D(format, w, h, levels, label)` is a shortcut for `KIND_2D`, and `createFor(Ktx2, label)`
picks the kind from a KTX2 header (a depth above 1 is 3D; cube and array flags choose among the
other four).

### 19.3 Uploading a level or a layer

The size of a compressed level, per layer, is whole blocks (§6.7):

```java
private static int levelBytes(int format, int w, int h) {
    return ((w + 3) >> 2) * ((h + 3) >> 2) * GpuFormats.blockBytes(format);
}
```

`uploadLevel` uploads every layer (and face) of one level from native memory at `address`:

```java
public void uploadLevel(int handle, int level, long address, int bytes) {
    int i = check(handle);
    if (tails[i] != null && level >= tailFirst[i]) {
        mirror(i, level, 0, layers[i], address, bytes);
    }
    level(i, level, address, bytes);
}

private void level(int i, int level, long address, int bytes) {
    int w = Math.max(1, widths[i] >> level);
    int hgt = Math.max(1, heights[i] >> level);
    int fmt = formats[i];
    boolean compressed = GpuFormats.isCompressed(fmt);
    int name = names[i];
    if (kinds[i] == KIND_2D) {
        if (compressed) {
            glCompressedTextureSubImage2D(name, level, 0, 0, w, hgt, fmt, bytes, address);
        } else {
            glTextureSubImage2D(
                    name,
                    level,
                    0,
                    0,
                    w,
                    hgt,
                    GpuFormats.transferFormat(fmt),
                    GpuFormats.transferType(fmt),
                    address);
        }
        return;
    }
    int depth =
            switch (kinds[i]) {
                case KIND_2D_ARRAY -> layers[i];
                case KIND_CUBE -> 6;
                case KIND_3D -> Math.max(1, layers[i] >> level);
                default -> layers[i] * 6;
            };
    if (compressed) {
        glCompressedTextureSubImage3D(name, level, 0, 0, 0, w, hgt, depth, fmt, bytes, address);
    } else {
        glTextureSubImage3D(
                name,
                level,
                0,
                0,
                0,
                w,
                hgt,
                depth,
                GpuFormats.transferFormat(fmt),
                GpuFormats.transferType(fmt),
                address);
    }
}
```

- **The level's size** is `max(1, size >> level)` (§6.2).
- **Compressed or not** picks the GL call: compressed uploads pass the format and the byte count
  of raw blocks; plain uploads pass a transfer format and type that the driver converts from.
- **The third dimension** depends on the kind: all layers of an array, the six faces of a cube,
  a depth that halves per level for a 3D texture, layer-faces for a cube array. KTX2 stores a
  level's data layer-major then face, which is the order GL expects for these uploads.
- **`address` is a `long`**: the `long` overload of §10.7. The value is a raw native address, or,
  when a pixel-unpack buffer is bound, an offset into it:

```java
void uploadLevelFromBuffer(int handle, int level, int buffer, long offset, int bytes) {
    int i = check(handle);
    if (level >= tailFirst[i]) {
        tailStale[i] = true;
    }
    glBindBuffer(GL_PIXEL_UNPACK_BUFFER, buffer);
    try {
        level(i, level, offset, bytes);
    } finally {
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
    }
}
```

This is one of the two bindings in the engine with no DSA form (§3.3): bind the upload ring as the
pixel-unpack buffer, upload with an offset, unbind in a `finally` so that a later upload from
client memory is never misread as an offset into the ring. The driver can then copy the texels
from the ring on the GPU, without the CPU touching the bytes again.

`uploadLayer` and `uploadLayerFromBuffer` do the same for one layer of a 2D array (a depth of 1
at z offset `layer`), which is how material textures arrive one by one into their bucket.
`GpuDeviceTest.compressedAndPlainTexturesUploadPerLevel` (§28.2) uploads four BC4 blocks whose
endpoints are 200 and 10 and whose indices are all 0, and checks that all 64 texels read back as
200 (§6.7's decode).

### 19.4 Growing an array: copyLayers and the tail mirror

The material buckets are 2D arrays that grow: when one is full, a bigger array is created and the
used layers are copied into it on the GPU, level by level, with `glCopyImageSubData` (GL 4.3).
On an Intel driver that copy fails for the levels smaller than a block, and so does reading those
levels back (D101; lesson 1 §17.1–§17.2 tells the story). So the class keeps a **CPU mirror** of
the tail levels, filled by every upload from client memory:

```java
private void mirror(int i, int level, int layer, int count, long address, int bytes) {
    int expected =
            count
                    * levelBytes(
                            formats[i],
                            Math.max(1, widths[i] >> level),
                            Math.max(1, heights[i] >> level));
    if (bytes != expected) {
        throw new IllegalArgumentException(
                bytes + " bytes for level " + level + ", expected " + expected);
    }
    MemorySegment.copy(ALL, address, tails[i], tailOffset(i, level, layer), bytes);
}
```

The mirror's layout is **level-major**: all layers of the first tail level, then all layers of the
next. `tailOffset(i, level, layer)` adds the sizes of the earlier tail levels and then `layer`
times this level's size; asked for `levelCount` (one past the last level) it gives the mirror's
total size, which `create` used. For a 1024 × 1024 BC7 array with 64 layers the tail is two levels of
one 16-byte block per layer: $2 \cdot 64 \cdot 16 = 2{,}048$ bytes, against megabytes for the texture.

`copyLayers(from, to, count)` checks that both textures are 2D arrays of one description, then per
level either copies on the GPU or, for tail levels, re-uploads from the mirror:

```java
for (int l = 0; l < levels[a]; l++) {
    int w = Math.max(1, widths[a] >> l);
    int h = Math.max(1, heights[a] >> l);
    if (l >= tailFirst[a]) {
        copyTailLevel(a, b, l, w, h, count);
        continue;
    }
    glCopyImageSubData(
            names[a],
            GL_TEXTURE_2D_ARRAY,
            l,
            0,
            0,
            0,
            names[b],
            GL_TEXTURE_2D_ARRAY,
            l,
            0,
            0,
            0,
            w,
            h,
            count);
}
```

`copyTailLevel` copies the mirror along to the new texture's mirror and uploads from it, through
`tails[b].address() + to`, a raw address again. One case cannot use the mirror: a texture uploaded
from the upload ring, because the ring is mapped write-only and its bytes cannot be read back on
the CPU (`tailStale`). That texture falls back to `glGetCompressedTextureSubImage` and a re-upload,
the path the Intel driver rejects; the engine therefore uploads material layers from client
memory, never through the ring (D101).

### 19.5 The rest of the class

`destroy(handle)` tells the state cache to forget the texture's name (§23, D50), deletes it,
closes its mirror arena, and frees the handle. The accessors that take a handle (`glName`, `format`,
`width`, `height`, `levels`, `layers`, `kind`) all go through `check`, which throws on a stale
handle; `count()` takes none and returns the number of live textures.

## 20. Step 12: samplers

[`SamplerDesc`](../../modules/render/src/main/java/dev/jeddy/render/gpu/SamplerDesc.java) packs
everything §6.4–§6.5 described into the bits of one `int` (L00 §6.4):

| Bits | Field | Values |
|---|---|---|
| 0 | magnification filter | `NEAREST` 0, `LINEAR` 1 |
| 1 | minification filter | `NEAREST` 0, `LINEAR` 1 |
| 2–3 | mip mode | `MIP_NONE` 0, `MIP_NEAREST` 1, `MIP_LINEAR` 2 |
| 4–5, 6–7, 8–9 | wrap S, T, R | `REPEAT` 0, `CLAMP` 1, `MIRROR` 2, `BORDER` 3 |
| 10–14 | anisotropy | 0–16 (0 or 1 = off) |
| 15 | depth comparison | 1 = shadow-map compare with `GL_GEQUAL` |

```java
private static final int MAG = 0; // 1 bit
private static final int MIN = 1; // 1 bit
private static final int MIP = 2; // 2 bits
private static final int WRAP_S = 4; // 2 bits
private static final int WRAP_T = 6;
private static final int WRAP_R = 8;
private static final int ANISO = 10; // 5 bits
private static final int COMPARE = 15; // 1 bit: depth comparison (shadow maps), GEQUAL

public static final int TRILINEAR_REPEAT = of(LINEAR, LINEAR, MIP_LINEAR, REPEAT);
public static final int LINEAR_CLAMP = of(LINEAR, LINEAR, MIP_NONE, CLAMP);
public static final int NEAREST_CLAMP = of(NEAREST, NEAREST, MIP_NONE, CLAMP);
public static final int SHADOW = of(LINEAR, LINEAR, MIP_NONE, BORDER) | (1 << COMPARE);

public static int of(int mag, int min, int mip, int wrap) {
    return (mag << MAG)
            | (min << MIN)
            | (mip << MIP)
            | (wrap << WRAP_S)
            | (wrap << WRAP_T)
            | (wrap << WRAP_R);
}

public static int withAnisotropy(int desc, int anisotropy) {
    int a = Math.max(0, Math.min(16, anisotropy));
    return (desc & ~(31 << ANISO)) | (a << ANISO);
}
```

The presets, computed by hand:

- `TRILINEAR_REPEAT` $= 1 + 1 \cdot 2 + 2 \cdot 4 + 0 = 11 =$ `0xB` (wrap `REPEAT` is 0 on every axis);
- `LINEAR_CLAMP` $= 1 + 2 + 0 + (1 \ll 4) + (1 \ll 6) + (1 \ll 8) = 3 + 16 + 64 + 256 = 339 =$ `0x153`;
- `NEAREST_CLAMP` $= 16 + 64 + 256 = 336 =$ `0x150`;
- `SHADOW` $= 3 + (3 \ll 4) + (3 \ll 6) + (3 \ll 8) + (1 \ll 15) = 3 + 48 + 192 + 768 + 32768 = 33{,}779 =$ `0x83F3`.

`withAnisotropy` clears the 5-bit field (`31 << ANISO`, five ones) and writes the clamped value:
`withAnisotropy(TRILINEAR_REPEAT, 8)` is $11 + 8 \cdot 1024 = 8203 =$ `0x200B`. Five bits hold 0 to
31, so 16 fits. The package-private getters shift and mask the fields back out
(`(d >>> MIP) & 3`, `(d >>> (WRAP_S + 2 * axis)) & 3`, and so on).

*Why pack a sampler into an `int`?* It is a free hash key, it can be stored in fields and arrays
and passed around without objects, and comparing two descriptions is one integer comparison. An
object per description would need `equals` and `hashCode` and allocation to look one up.

[`GpuSamplers`](../../modules/render/src/main/java/dev/jeddy/render/gpu/GpuSamplers.java) creates
the GL sampler for a description the first time it is asked, and returns the same one afterwards:

```java
private static final int[] WRAP = {
    GL_REPEAT, GL_CLAMP_TO_EDGE, GL_MIRRORED_REPEAT, GL_CLAMP_TO_BORDER
};

private final GpuDevice device;
private final IntIntMap byDesc = new IntIntMap();

public int get(int desc) {
    int name = byDesc.get(desc);
    if (name > 0) {
        return name;
    }
    MemorySegment id = device.scratch();
    glCreateSamplers(1, id);
    name = id.get(ValueLayout.JAVA_INT, 0);
    int mip = SamplerDesc.mip(desc);
    boolean minLinear = SamplerDesc.min(desc) == SamplerDesc.LINEAR;
    int min =
            switch (mip) {
                case SamplerDesc.MIP_NEAREST ->
                        minLinear ? GL_LINEAR_MIPMAP_NEAREST : GL_NEAREST_MIPMAP_NEAREST;
                case SamplerDesc.MIP_LINEAR ->
                        minLinear ? GL_LINEAR_MIPMAP_LINEAR : GL_NEAREST_MIPMAP_LINEAR;
                default -> minLinear ? GL_LINEAR : GL_NEAREST;
            };
    glSamplerParameteri(name, GL_TEXTURE_MIN_FILTER, min);
    glSamplerParameteri(
            name,
            GL_TEXTURE_MAG_FILTER,
            SamplerDesc.mag(desc) == SamplerDesc.LINEAR ? GL_LINEAR : GL_NEAREST);
    glSamplerParameteri(name, GL_TEXTURE_WRAP_S, WRAP[SamplerDesc.wrap(desc, 0)]);
    glSamplerParameteri(name, GL_TEXTURE_WRAP_T, WRAP[SamplerDesc.wrap(desc, 1)]);
    glSamplerParameteri(name, GL_TEXTURE_WRAP_R, WRAP[SamplerDesc.wrap(desc, 2)]);
    int aniso = SamplerDesc.anisotropy(desc);
    if (aniso > 1) {
        glSamplerParameterf(
                name,
                GL_TEXTURE_MAX_ANISOTROPY,
                Math.min(aniso, device.caps().maxAnisotropy()));
    }
    if (SamplerDesc.compare(desc)) {
        glSamplerParameteri(name, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE);
        glSamplerParameteri(name, GL_TEXTURE_COMPARE_FUNC, GL_GEQUAL);
    }
    byDesc.put(desc, name);
    return name;
}
```

- **`IntIntMap`** is the engine's primitive hash map from `int` to `int` (no boxing, DESIGN
  §4.2). It returns −1 for a missing key, and GL never hands out sampler name 0, so `name > 0`
  means "found". A lookup of an existing sampler allocates nothing.
- **The minification filter** combines the texel filter and the mip mode into GL's single enum
  (`GL_LINEAR_MIPMAP_LINEAR` is trilinear, §6.4).
- **`WRAP[...]`**: the 2-bit wrap value indexes a table of GL enums, one lookup per axis.
- **Anisotropy** is clamped to what the driver reports (`maxAnisotropy`, §12) and only set above
  1.
- **Comparison** switches the sampler into shadow-map mode with `GL_GEQUAL` (§6.5, reversed-Z).

A sampler is bound with `StateCache.bindSampler(unit, name)` (§23), next to the texture on the
same unit. `destroyAll` walks the map's slots and deletes every sampler when the device closes.

## 21. Step 13: framebuffers

A **framebuffer object** (FBO) is a set of attachments to render into (§2.14).
[`GpuFramebuffers.create`](../../modules/render/src/main/java/dev/jeddy/render/gpu/GpuFramebuffers.java)
builds one from texture handles:

```java
public int create(int[] colorTextures, int depthTexture, String label) {
    MemorySegment ids = device.scratch();
    glCreateFramebuffers(1, ids);
    int name = ids.get(ValueLayout.JAVA_INT, 0);
    GpuTextures textures = device.textures();
    for (int c = 0; c < colorTextures.length; c++) {
        glNamedFramebufferTexture(
                name, GL_COLOR_ATTACHMENT0 + c, textures.glName(colorTextures[c]), 0);
        ids.setAtIndex(ValueLayout.JAVA_INT, c, GL_COLOR_ATTACHMENT0 + c);
    }
    if (colorTextures.length == 0) {
        glNamedFramebufferDrawBuffer(name, GL_NONE);
        glNamedFramebufferReadBuffer(name, GL_NONE);
    } else {
        glNamedFramebufferDrawBuffers(name, colorTextures.length, ids);
    }
    if (depthTexture != Handles.NULL) {
        glNamedFramebufferTexture(name, GL_DEPTH_ATTACHMENT, textures.glName(depthTexture), 0);
    }
    int status = glCheckNamedFramebufferStatus(name, GL_FRAMEBUFFER);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        ids.set(ValueLayout.JAVA_INT, 0, name);
        glDeleteFramebuffers(1, ids);
        throw new IllegalStateException(
                label + " is incomplete: 0x" + Integer.toHexString(status));
    }
    if (label != null) {
        GLDebug.label(GL_FRAMEBUFFER, name, label);
    }
    int h = handles.allocate();
    int i = Handles.index(h);
    if (i >= names.length) {
        names = Arrays.copyOf(names, Math.max(i + 1, names.length * 2));
    }
    names[i] = name;
    return h;
}
```

- **Attachments.** `glNamedFramebufferTexture(fb, attachment, texture, level)` attaches mip level
  0 of each texture: colors at `GL_COLOR_ATTACHMENT0 + c` (the enums are consecutive), depth at
  `GL_DEPTH_ATTACHMENT`.
- **Draw buffers.** A fragment shader's `layout(location = k) out` goes to the attachment named in
  entry $k$ of the framebuffer's draw-buffer list. The loop writes that list into the scratch
  segment as it goes (entry $c$ is `GL_COLOR_ATTACHMENT0 + c`; the name read from slot 0 earlier is
  no longer needed) and `glNamedFramebufferDrawBuffers` installs it. A depth-only framebuffer
  (shadow maps, the prepass) has no color, so its draw and read buffers are set to `GL_NONE`:
  nothing tries to write or read color.
- **Completeness.** GL refuses to render into a framebuffer whose attachments do not work
  together, and reports why from `glCheckNamedFramebufferStatus`. The common causes: an attachment
  whose format cannot be rendered at that attachment point (a depth texture at a color attachment
  is `GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT`), no attachment at all
  (`GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT`), attachments with different sample counts, or a
  format combination the driver does not support (`GL_FRAMEBUFFER_UNSUPPORTED`). Attachments of *different sizes* are allowed in GL 4.6: rendering
  is silently limited to the area all of them cover, a reason to create a pass's targets together.
  Checking right after creation turns a mysterious black screen into an exception with the status
  code; the half-built framebuffer is deleted first, so nothing leaks.

`createDepthLayer(texture, layer, label)` and `createColorLayer(texture, layer, label)` attach a
single layer of an array texture (`glNamedFramebufferTextureLayer`): that is how each sun cascade
renders into its layer of the cascade array, and how a texture blit writes into one layer of a
bucket. `createDepthLayer` with `layer < 0` attaches a whole 2D depth texture. `destroy` makes the
state cache forget the name (§23) before deleting it; textures are not deleted with their
framebuffer.

Framebuffer 0 is the window's default framebuffer (§2.14); the frame graph calls it `BACKBUFFER`.
In the renderer nobody creates framebuffers by hand: the frame graph creates one per pass when it
compiles (chapter 06).

## 22. Step 14: PipelineState

Culling, the depth test and its function, depth writes, blending, the color mask, polygon offset,
wireframe and scissor are **switches** of the fixed stages (§2.8–§2.13), not programs. Every pass
needs a particular combination of them. [`PipelineState`](../../modules/render/src/main/java/dev/jeddy/render/gpu/PipelineState.java)
describes one combination as the bits of one `long`:

| Bits | Field | Values |
|---|---|---|
| 0–1 | cull mode | `CULL_NONE` 0, `CULL_BACK` 1, `CULL_FRONT` 2 |
| 2 | depth test | on/off |
| 3 | depth write | on/off |
| 4–6 | depth function | index $i$ means `GL_NEVER + i`: `DEPTH_EQUAL` 2, `DEPTH_GEQUAL` 6, `DEPTH_ALWAYS` 7 |
| 7–9 | blend mode | `BLEND_OPAQUE` 0, `BLEND_ALPHA` 1, `BLEND_PREMULTIPLIED` 2, `BLEND_ADDITIVE` 3, `BLEND_MULTIPLY` 4 |
| 10–13 | color write mask | bit 10 red … bit 13 alpha |
| 14 | polygon offset | on/off (shadow casters) |
| 15 | wireframe | on/off (debug) |
| 16 | scissor test | on/off |

```java
static final int CULL_SHIFT = 0; // 2 bits
static final long DEPTH_TEST = 1L << 2;
static final long DEPTH_WRITE = 1L << 3;
static final int DEPTH_FUNC_SHIFT = 4; // 3 bits
static final int BLEND_SHIFT = 7; // 3 bits
static final int COLOR_MASK_SHIFT = 10; // 4 bits (r g b a)
static final long POLYGON_OFFSET = 1L << 14;
static final long WIREFRAME = 1L << 15;
static final long SCISSOR = 1L << 16;
```

Single-bit switches are stored as masks (`1L << 2`), multi-bit fields as shifts. The constants are
package-private: only `StateCache` (same package) needs to take a state apart; everyone else builds
states from the presets and the `with*` methods.

**The depth-function trick.** GL's eight comparison enums are consecutive: `GL_NEVER` is `0x0200`,
then `GL_LESS`, `GL_EQUAL`, `GL_LEQUAL`, `GL_GREATER`, `GL_NOTEQUAL`, `GL_GEQUAL` `0x0206` and
`GL_ALWAYS` `0x0207`. So three bits holding $i$ are enough, and the GL value is `GL_NEVER + i`.

The presets:

```java
/** Opaque geometry: back-face culling, reversed-Z depth test and write, all colors. */
public static final long OPAQUE =
        ((long) CULL_BACK << CULL_SHIFT)
                | DEPTH_TEST
                | DEPTH_WRITE
                | ((long) DEPTH_GEQUAL << DEPTH_FUNC_SHIFT)
                | (0xFL << COLOR_MASK_SHIFT);

/** Depth prepass and shadow casters: no color writes. */
public static final long DEPTH_ONLY = OPAQUE & ~(0xFL << COLOR_MASK_SHIFT);

/** Transparent geometry: alpha blending, depth test without writes, no culling. */
public static final long TRANSPARENT =
        withBlend((OPAQUE & ~DEPTH_WRITE) & ~(3L << CULL_SHIFT), BLEND_ALPHA);

/** Full-screen passes: no depth, no culling, all colors. */
public static final long FULLSCREEN = 0xFL << COLOR_MASK_SHIFT;

/** The GL defaults (what {@link StateCache} assumes after {@link StateCache#invalidate}). */
public static final long GL_DEFAULT =
        ((long) 1 << DEPTH_FUNC_SHIFT) /* GL_LESS */ | DEPTH_WRITE | (0xFL << COLOR_MASK_SHIFT);
```

The values, computed by hand:

$$\texttt{OPAQUE} = \underbrace{1}_{\text{cull back}} + \underbrace{4}_{\text{depth test}} + \underbrace{8}_{\text{depth write}} + \underbrace{6 \cdot 16}_{\text{GEQUAL}} + \underbrace{15 \cdot 1024}_{\text{RGBA}} = 15469 = \texttt{0x3C6D}$$

- `DEPTH_ONLY` clears the four color bits: `0x3C6D & ~0x3C00` $=$ `0x006D`.
- `TRANSPARENT` clears depth write (8) and the cull field (1), then sets alpha blending
  ($1 \cdot 128$): $\texttt{0x3C6D} - 8 - 1 + 128 = \texttt{0x3CE4}$.
- `FULLSCREEN` is color writes only: `0x3C00`.
- `GL_DEFAULT` describes GL's own starting state: no culling, no depth test, depth writes on,
  depth function `GL_LESS` (index 1), no blending, all colors: $16 + 8 + 15360 =$ `0x3C18`.

The `with*` methods replace one field and keep the rest, with the clear-then-set pattern of L00
§6.2:

```java
public static long withBlend(long state, int mode) {
    return (state & ~(7L << BLEND_SHIFT)) | ((long) mode << BLEND_SHIFT);
}

public static long withDepth(long state, boolean test, boolean write, int function) {
    long s = state & ~(DEPTH_TEST | DEPTH_WRITE | (7L << DEPTH_FUNC_SHIFT));
    return s
            | (test ? DEPTH_TEST : 0)
            | (write ? DEPTH_WRITE : 0)
            | ((long) function << DEPTH_FUNC_SHIFT);
}
```

`withCull`, `withDepthWrite`, `withColorMask`, `withPolygonOffset`, `withWireframe` and
`withScissor` follow the same pattern. The `(long)` casts matter: `mode << BLEND_SHIFT` on an `int`
is fine at bit 7, but the habit keeps every field correct if one moves past bit 31.
`withBlend(TRANSPARENT, BLEND_ADDITIVE)` is `0x3DE4`; the debug renderer uses
`withDepth(TRANSPARENT, false, false, DEPTH_ALWAYS)` so that an editor gizmo's lines show through
walls.

*Why a `long` and not an object?* A combination is a value: comparing two is one `==`, the
difference between two is one XOR (§23), and it can sit in a field, an array or a sort key without
allocation. Seventeen bits are used, so a `long` leaves room for more switches (stencil, if it ever
comes, or a front-face bit for the mirrored instances of §2.8). An object with nine fields would
need `equals`, a per-field comparison in the cache, and care not to allocate one per draw.

## 23. Step 15: StateCache

[`StateCache`](../../modules/render/src/main/java/dev/jeddy/render/gpu/StateCache.java) remembers
what GL currently has bound and enabled, and issues a call only when something changes (TASKS
M5-03, DESIGN §9.2). Its fields:

```java
private static final int UNITS = 32;
private static final int BINDINGS = 32;

private long state;
private int program;
private int vertexArray;
private int drawFramebuffer;
private final int[] textures = new int[UNITS];
private final int[] samplers = new int[UNITS];
private final int[] ssbo = new int[BINDINGS];
private final long[] ssboOffset = new long[BINDINGS];
private final long[] ssboSize = new long[BINDINGS];
private final int[] ubo = new int[BINDINGS];
private final long[] uboOffset = new long[BINDINGS];
private final long[] uboSize = new long[BINDINGS];
private int drawIndirect;
private int viewportX = -1, viewportY, viewportW, viewportH;
private long issued;
```

One field per piece of state the renderer changes: the pipeline-state bits, the program, the vertex
array, the framebuffer, 32 texture units and their samplers, 32 storage and 32 uniform binding
points with their ranges, the indirect buffer and the viewport. `issued` counts the GL calls this
cache actually made.

### 23.1 Starting from a known state

```java
public StateCache() {
    invalidate();
}

public void invalidate() {
    state = -1; // forces a full apply
    apply(PipelineState.GL_DEFAULT);
    program = -1;
    vertexArray = -1;
    drawFramebuffer = -1;
    Arrays.fill(textures, -1);
    Arrays.fill(samplers, -1);
    Arrays.fill(ssbo, -1);
    Arrays.fill(ubo, -1);
    drawIndirect = -1;
    viewportX = -1;
}
```

A cache is only useful if it never believes something false. `invalidate` therefore does two
things. For the bindings it **forgets**: −1 is never a GL name, so the next `useProgram`, bind or
`viewport` call always reaches GL. For the pipeline state it **re-applies GL's defaults**:
`state = -1` (all 64 bits set, a value no real state has) makes `apply` take its "full" path and
issue every switch, nine calls, leaving GL in the state `GL_DEFAULT` describes. The constructor
calls it, which is why creating a `GpuDevice` already talks to GL (§17.1).

### 23.2 Applying a pipeline state

```java
public void apply(long newState) {
    long old = state;
    long diff = old ^ newState;
    if (diff == 0) {
        return;
    }
    boolean full = old == -1;
    if (full || (diff & (3L << PipelineState.CULL_SHIFT)) != 0) {
        int cull = (int) ((newState >>> PipelineState.CULL_SHIFT) & 3);
        if (cull == PipelineState.CULL_NONE) {
            glDisable(GL_CULL_FACE);
            issued++;
        } else {
            glEnable(GL_CULL_FACE);
            glCullFace(cull == PipelineState.CULL_BACK ? GL_BACK : GL_FRONT);
            issued += 2;
        }
    }
    if (full || (diff & PipelineState.DEPTH_TEST) != 0) {
        toggle(GL_DEPTH_TEST, (newState & PipelineState.DEPTH_TEST) != 0);
    }
    if (full || (diff & PipelineState.DEPTH_WRITE) != 0) {
        glDepthMask((newState & PipelineState.DEPTH_WRITE) != 0);
        issued++;
    }
    if (full || (diff & (7L << PipelineState.DEPTH_FUNC_SHIFT)) != 0) {
        glDepthFunc(GL_NEVER + (int) ((newState >>> PipelineState.DEPTH_FUNC_SHIFT) & 7));
        issued++;
    }
```

**The XOR.** `old ^ newState` has a 1 exactly where the two states differ (L00 §6.1). If it is 0,
nothing changed and the method returns at once: the common case, one XOR and one comparison. Each
field is then tested with its mask, and only fields with a 1 in `diff` cost GL calls. Each field
is read back with the shift-and-mask of L00 §6.4 (`>>>` so the sign bit never smears in).

The rest of the method follows the same pattern:

```java
    if (full || (diff & (7L << PipelineState.BLEND_SHIFT)) != 0) {
        int blend = (int) ((newState >>> PipelineState.BLEND_SHIFT) & 7);
        if (blend == PipelineState.BLEND_OPAQUE) {
            glDisable(GL_BLEND);
            issued++;
        } else {
            glEnable(GL_BLEND);
            switch (blend) {
                case PipelineState.BLEND_ALPHA ->
                        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
                case PipelineState.BLEND_PREMULTIPLIED ->
                        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
                case PipelineState.BLEND_ADDITIVE -> glBlendFunc(GL_ONE, GL_ONE);
                default -> glBlendFunc(GL_DST_COLOR, GL_ZERO);
            }
            issued += 2;
        }
    }
    if (full || (diff & (0xFL << PipelineState.COLOR_MASK_SHIFT)) != 0) {
        int m = (int) ((newState >>> PipelineState.COLOR_MASK_SHIFT) & 0xF);
        glColorMask((m & 1) != 0, (m & 2) != 0, (m & 4) != 0, (m & 8) != 0);
        issued++;
    }
    if (full || (diff & PipelineState.POLYGON_OFFSET) != 0) {
        toggle(GL_POLYGON_OFFSET_FILL, (newState & PipelineState.POLYGON_OFFSET) != 0);
    }
    if (full || (diff & PipelineState.WIREFRAME) != 0) {
        glPolygonMode(
                GL_FRONT_AND_BACK,
                (newState & PipelineState.WIREFRAME) != 0 ? GL_LINE : GL_FILL);
        issued++;
    }
    if (full || (diff & PipelineState.SCISSOR) != 0) {
        toggle(GL_SCISSOR_TEST, (newState & PipelineState.SCISSOR) != 0);
    }
    state = newState;
}

private void toggle(int cap, boolean on) {
    if (on) {
        glEnable(cap);
    } else {
        glDisable(cap);
    }
    issued++;
}
```

The blend mode maps to the four `glBlendFunc` pairs of §2.13. Note what the state does *not*
hold: the polygon offset *amounts* and the scissor *rectangle* are set directly by the passes that
enable them (`glPolygonOffset(-1.5f, -2f)` in the shadow passes, `glScissor` per atlas tile), and
are not cached.

*Worked example.* From `OPAQUE` to `TRANSPARENT`:
$\texttt{0x3C6D} \oplus \texttt{0x3CE4} = \texttt{0x0089}$, binary `1000 1001`: bits 0 (cull), 3
(depth write) and 7 (blend). `apply` issues `glDisable(GL_CULL_FACE)` (the new cull mode is none,
one call), `glDepthMask(false)` (one), `glEnable(GL_BLEND)` and `glBlendFunc` (two): four calls
instead of re-sending all nine switches. Going back costs `glEnable` and `glCullFace`,
`glDepthMask(true)` and `glDisable(GL_BLEND)`: four again.

### 23.3 Bindings

Every binding method compares with the remembered value first:

```java
public void useProgram(int name) {
    if (program != name) {
        glUseProgram(name);
        program = name;
        issued++;
    }
}

public void bindTexture(int unit, int name) {
    if (textures[unit] != name) {
        glBindTextureUnit(unit, name);
        textures[unit] = name;
        issued++;
    }
}

public void bindStorage(int binding, int buffer, long offset, long size) {
    if (ssbo[binding] != buffer || ssboOffset[binding] != offset || ssboSize[binding] != size) {
        glBindBufferRange(GL_SHADER_STORAGE_BUFFER, binding, buffer, offset, size);
        ssbo[binding] = buffer;
        ssboOffset[binding] = offset;
        ssboSize[binding] = size;
        issued++;
    }
}
```

`bindVertexArray`, `bindFramebuffer` (`GL_FRAMEBUFFER`, both draw and read), `bindSampler`,
`bindUniform` (the same as `bindStorage` for `GL_UNIFORM_BUFFER`), `bindDrawIndirect`
(`GL_DRAW_INDIRECT_BUFFER`) and `viewport` (four numbers) are the same shape. A storage or uniform
binding is a *range*, so all three numbers are compared: binding the same buffer at a new offset
(next frame's region of the ring) is a change.

### 23.4 Deleting forgets (D50)

```java
public void forgetTexture(int name) {
    for (int u = 0; u < UNITS; u++) {
        if (textures[u] == name) {
            textures[u] = -1;
        }
    }
}
```

When an object is deleted, GL unbinds it everywhere and may give its name to the next new object
(§3.1). Without this, the cache could believe "texture 7 is on unit 3" and skip binding a
brand-new texture that happens to be named 7 as well: the old picture would vanish and nothing
would replace it. `GpuTextures.destroy`, `GpuBuffers.destroy` and `GpuFramebuffers.destroy` call
`forgetTexture`, `forgetBuffer` (storage, uniform and indirect bindings) and `forgetFramebuffer`
before deleting (**D50**).

### 23.5 The rules, and why it pays

- **Everything goes through it.** Code that calls GL's state functions directly must call
  `invalidate()` afterwards (the environment's BRDF lookup-table pass, which binds its compute
  program directly, does). Code that sets state
  the cache does not track (the polygon offset amounts, the scissor rectangle) is free to.
- **Deleting forgets** (above).
- **`issuedCalls()`** counts the calls actually made, for tests and the profiler overlay.

Why bother? A GL call through FFM costs a few nanoseconds of Java (§29), but the driver's work
behind a state change can be much larger: validating the new combination, updating its internal
records, sometimes recompiling a shader variant for it (§8.1). The renderer submits per batch and
per pass, not per object, and with the cache the number of state calls a frame makes grows with
the number of batches and passes, not with the number of objects. The methods allocate nothing,
which `GpuDeviceTest` proves (§28.2).

## 24. Step 16: the shader preprocessor

GLSL has no `#include`, and Jeddy's shaders share a lot: the vertex layout, the instance records,
the material rows, the frame constants. [`ShaderPreprocessor`](../../modules/asset/src/main/java/dev/jeddy/asset/shader/ShaderPreprocessor.java)
expands includes, honours `#pragma once`, inserts feature defines, and writes `#line` directives
so that the driver's error messages still point at the right file and line. It lives in the
`asset` module, not `render`, so that the offline cooker can check shaders with exactly the same
code without depending on the renderer (**D34**).

Where the text comes from is a one-method interface,
[`ShaderSources`](../../modules/asset/src/main/java/dev/jeddy/asset/shader/ShaderSources.java):

```java
@FunctionalInterface
public interface ShaderSources {
    String load(String path) throws IOException;

    static ShaderSources directory(Path root) {
        return path -> Files.readString(root.resolve(path), StandardCharsets.UTF_8);
    }
    // ... and classpath(anchor, root), with the module-path caveat of §26.2
}
```

A directory (development and hot reload), a jar's resources, a map in a test: anything that turns
a path into text.

### 24.1 The result and its annotation

```java
public record Result(String source, List<String> files) {
    /** Rewrites {@code "0(12) : error"}-style file numbers in a compiler log to file names. */
    public String annotate(String log) {
        StringBuilder out = new StringBuilder(log.length() + 64);
        for (String line : log.split("\n", -1)) {
            out.append(line);
            int n = leadingNumber(line);
            if (n >= 0 && n < files.size()) {
                out.append("   <- ").append(files.get(n));
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static int leadingNumber(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        int start = i;
        while (i < line.length() && Character.isDigit(line.charAt(i))) {
            i++;
        }
        if (i == start
                || i >= line.length()
                || (line.charAt(i) != '(' && line.charAt(i) != ':')) {
            return -1;
        }
        return Integer.parseInt(line.substring(start, i));
    }
}
```

The expanded `source` and the list of `files` that went into it. GLSL's directive `#line N F`
means "the next line is line $N$ of source string $F$", and drivers print that pair in their
messages: NVIDIA as `0(12) : error ...`, Mesa as `0:12(3): error: ...`. The preprocessor uses $F$
as an index into `files`, and `annotate` appends the file name to every log line that starts with
a number followed by `(` or `:`. A log line that starts with a word, such as `ERROR: 1:2: ...`,
is left as it is.

### 24.2 Expanding a file

```java
public Result process(String path, List<String> defines) throws IOException {
    List<String> files = new ArrayList<>();
    StringBuilder out = new StringBuilder(4096);
    Set<String> once = new HashSet<>();
    List<String> stack = new ArrayList<>();
    expand(path, defines, true, out, files, once, stack);
    return new Result(out.toString(), List.copyOf(files));
}
```

`expand` works recursively; `stack` holds the files currently being expanded (for cycle
detection) and `once` the files that declared `#pragma once`. Its start:

```java
private void expand(
        String path,
        List<String> defines,
        boolean main,
        StringBuilder out,
        List<String> files,
        Set<String> once,
        List<String> stack)
        throws IOException {
    if (stack.contains(path)) {
        throw new ShaderException(
                "#include cycle: " + String.join(" -> ", stack) + " -> " + path);
    }
    if (stack.size() > MAX_DEPTH) {
        throw new ShaderException("#include nesting deeper than " + MAX_DEPTH + " at " + path);
    }
    if (once.contains(path)) {
        return;
    }
    String text = sources.load(path);
    int fileIndex = files.size();
    files.add(path);
    stack.add(path);
    if (!main) {
        out.append("#line 1 ").append(fileIndex).append('\n'); // errors point into this file
    }
    String[] lines = text.split("\r?\n", -1);
    boolean versionSeen = !main;
```

- **A cycle** (a includes b includes a) is reported with the whole chain, `main.vert -> a.glsl ->
  b.glsl -> a.glsl`, instead of overflowing the stack. `MAX_DEPTH` (32) catches absurd nesting.
- **`#pragma once`**: a file already expanded with the pragma expands to nothing the second time.
- **The file's number** is its index in `files`; an included file starts with `#line 1 <index>`,
  so its first line is reported as line 1 of that file.
- **`split("\r?\n", -1)`** accepts Windows and Unix line ends, and `-1` keeps trailing empty
  lines, so line numbers stay exact.
- Included files never contain `#version`, so for them `versionSeen` starts as true.

The loop over lines has four cases:

```java
    for (int i = 0; i < lines.length; i++) {
        String line = lines[i];
        String t = line.strip();
        if (t.startsWith("#version")) {
            if (!main) {
                throw new ShaderException(
                        path + ":" + (i + 1) + ": #version in an included file");
            }
            out.append(line).append('\n');
            for (String d : defines) {
                out.append("#define ").append(d.indexOf(' ') < 0 ? d + " 1" : d).append('\n');
            }
            out.append("#line ").append(i + 2).append(' ').append(fileIndex).append('\n');
            versionSeen = true;
        } else if (t.startsWith("#pragma") && t.substring(7).strip().equals("once")) {
            once.add(path);
            out.append('\n');
        } else if (t.startsWith("#include")) {
            String rest = t.substring(8).strip();
            if (rest.length() < 2 || rest.charAt(0) != '"' || rest.indexOf('"', 1) < 0) {
                throw new ShaderException(
                        path + ":" + (i + 1) + ": expected #include \"file\"");
            }
            String target = resolve(path, rest.substring(1, rest.indexOf('"', 1)));
            expand(target, defines, false, out, files, once, stack);
            out.append("#line ").append(i + 2).append(' ').append(fileIndex).append('\n');
        } else {
            if (!versionSeen && !t.isEmpty() && !t.startsWith("//")) {
                throw new ShaderException(path + ": the first statement must be #version");
            }
            out.append(line).append('\n');
        }
    }
    stack.remove(stack.size() - 1);
}
```

- **`#version`** must come first in GLSL, so the defines go right *after* it. A define `"SKINNED"`
  becomes `#define SKINNED 1`; one with a value (`"LIGHTS 64"`) is written as given. Then
  `#line (i + 2) fileIndex`: line $i$ (counting from 0) is the `#version`, which is line $i + 1$
  counting from 1, so the next line of the file is line $i + 2$. The inserted defines do not shift
  the numbers the driver reports.
- **`#pragma once`** is replaced by an empty line, so the line count stays right.
- **`#include "file"`** expands the target recursively, then writes `#line (i + 2) fileIndex`
  to return to the including file's numbering after the include line.
- **Anything else** is copied. Before the `#version` only blank lines and `//` comments are
  allowed.

`resolve` turns an include path into a root-relative path: a leading `/` means "from the shader
root", anything else is relative to the including file's directory, with `.` and `..` handled and
`..` above the root rejected (`#include escapes the shader root`).

*Worked example*, from `ShaderPreprocessorTest`. The files:

```
pbr/lit.frag:      #version 460 core
                   #include "../common/math.glsl"
                   #include "brdf.glsl"
                   void main() {}
pbr/brdf.glsl:     #pragma once
                   #include "/common/math.glsl"
                   float D() { return PI; }
common/math.glsl:  #pragma once
                   const float PI = 3.14159;
```

processed with the defines `NORMAL_MAP` and `LIGHTS 64` give (run for this chapter; the blank
lines are the replaced pragmas and the files' final newlines):

```glsl
#version 460 core
#define NORMAL_MAP 1
#define LIGHTS 64
#line 2 0
#line 1 1

const float PI = 3.14159;

#line 3 0
#line 1 2

#line 3 2
float D() { return PI; }

#line 4 0
void main() {}

```

with `files` = `[pbr/lit.frag, common/math.glsl, pbr/brdf.glsl]`. `math.glsl` appears once,
although both other files include it. A driver error `2(3) : error ...` is annotated
`<- pbr/brdf.glsl`: line 3 of `brdf.glsl`, the line with `D()`.

The `#line 1 <index>` at the start of an included file was added after a real bug: without it, an
error inside an included file was reported in the *including* file's numbering, so the message
named the wrong file at the wrong line. It was found while this guide was being written, and
`ShaderPreprocessorTest` now checks the directive (exercise 6 lets you see the bug).

Here is the start of [`pbr/pbr.vert`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/pbr/pbr.vert),
which you will meet again in chapter 06:

```glsl
#version 460 core
// Forward+ PBR vertex stage (TASKS M7-01): world position, tangent frame and UVs.
#include "/engine/vertex.glsl"
#include "/engine/instance.glsl"
#ifdef SKINNED
#include "/engine/skinning.glsl"
#endif
#include "/engine/frame.glsl"
```

Note that the preprocessor expands `#include` lines without looking at `#ifdef`: the skinning file
is always pasted in, and the GLSL compiler's own preprocessor then drops it when `SKINNED` is not
defined. `skinning.glsl` includes `vertex.glsl` again, and `#pragma once` makes that second include
empty. The order of the includes therefore matters: had `pbr.vert` included `skinning.glsl` (inside
its `#ifdef`) *before* `vertex.glsl`, the vertex layout would have been pasted inside the `#ifdef`,
the later unconditional include would have expanded to nothing, and every variant without
`SKINNED` would have lost its vertex inputs. Include the shared files first, the optional ones
after.

## 25. Step 17: ShaderFeatures and ProgramCache

### 25.1 ShaderFeatures

[`ShaderFeatures`](../../modules/render/src/main/java/dev/jeddy/render/shader/ShaderFeatures.java)
names the bits of a feature mask (§8.3):

```java
public final class ShaderFeatures {
    private final String[] names;

    public ShaderFeatures(String... names) {
        if (names.length > 64) {
            throw new IllegalArgumentException("at most 64 features");
        }
        this.names = names.clone();
    }

    public static final ShaderFeatures NONE = new ShaderFeatures();

    public long bit(String name) {
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(name)) {
                return 1L << i;
            }
        }
        throw new IllegalArgumentException("unknown shader feature " + name);
    }

    public List<String> defines(long mask) {
        List<String> out = new ArrayList<>(Long.bitCount(mask));
        for (int i = 0; i < names.length; i++) {
            if ((mask & (1L << i)) != 0) {
                out.add(names[i]);
            }
        }
        if ((mask & ~validMask()) != 0) {
            throw new IllegalArgumentException(
                    "mask has undefined feature bits: " + Long.toHexString(mask));
        }
        return out;
    }

    private long validMask() {
        return names.length == 64 ? -1L : (1L << names.length) - 1;
    }
}
```

- **`names.clone()`**: the class is immutable, so it copies the caller's array.
- **`1L << i`**, a `long` shift: `1 << i` would be an `int` shift and wrap at 32 (L00 §6.3).
- **`validMask`**: $n$ names allow the low $n$ bits, $(1 \ll n) - 1$ (L00 §6.5). For $n = 64$ the
  shift distance would wrap to 0 (a `long` shift uses only 6 bits), so that case is all ones,
  `-1L`. A mask with a bit no name covers is a bug in the caller and throws.

`ShaderFeaturesTest` shows it: with `new ShaderFeatures("NORMAL_MAP", "ALPHA_TEST", "SKINNED")`,
`defines(0b101)` is `[NORMAL_MAP, SKINNED]`, `bit("ALPHA_TEST")` is 2, and `defines(0b1000)` throws.
The material system's own feature table (`MaterialSystem.features()`) is in chapter 06. Building
the list allocates, which is fine: it runs once per variant, when it is first compiled.

### 25.2 ProgramCache: definitions and records

[`ProgramCache`](../../modules/render/src/main/java/dev/jeddy/render/shader/ProgramCache.java)
creates each (definition, mask) program on first request and never blocks a frame on a compile.

```java
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
```

- A **definition** is a name, the shader paths (vertex and fragment, or compute) and the
  `ShaderFeatures` that give its mask bits a meaning. Definitions are few and made at startup, so
  they are records in a list.
- Per definition, a **`LongIntMap`** (a primitive map from `long` to `int`, missing value −1) maps
  a mask to a **program record**.
- The records are **parallel arrays** (structure of arrays, lesson 3): the GL program name, its
  state (`COMPILING`, `READY`, `FAILED`), the shader names until linking finishes, the binary-cache
  key, a label for logs, and the preprocessed sources (kept to annotate errors).
- `scratch` is 16 bytes of native memory for `glGetProgramiv` answers, so polling allocates
  nothing.

The constructor decides how to compile:

```java
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
```

With the extension (and without `-Djeddy.shaders.sync=true`, a switch for debugging), compiles
run in the background (§8.4). `glMaxShaderCompilerThreadsKHR(0xFFFFFFFF)` is the extension's way of
saying "use as many threads as the driver itself thinks best". The command is extension-gated (§10.6), and only called when
the extension is present.

`define(name, vertexPath, fragmentPath, features)` and `defineCompute(name, computePath,
features)` append a definition and an empty map and return the definition's index. Nothing
compiles yet.

### 25.3 get: the per-frame lookup

```java
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

public int getBlocking(int definition, long mask) {
    int p;
    while ((p = get(definition, mask)) == 0) {
        Thread.onSpinWait();
    }
    return p;
}
```

- **First request** of a mask creates the record and starts compiling (§25.4).
- **While compiling**, each `get` polls once, without waiting, and returns **0**. Callers treat 0
  as "skip this draw": the material system's binders return `false` for a batch whose program is
  0, and the batch is drawn a frame or two later (chapter 06).
- **A failed variant** throws a `ShaderException` naming it; the driver's log has already been
  written (§25.5).
- **`getBlocking`** spins until the program is ready, for loading screens and tests.
- **No allocation** once the variant exists: a primitive map lookup and array reads.
  `ProgramCacheTest` asserts it (§28.4).

### 25.4 create: preprocess, compile, link

```java
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
```

The label (`"pbr[5]"`: definition name and mask in hex) names the program in logs and debuggers.
Then a record is allocated (the arrays double when full, `count` is the next free index), the
program created and labelled, and the binary cache tried first:

```java
    int r = count++;
    // ... grow the six record arrays by doubling when r reaches their length
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
```

On a cache hit the program is ready at once, without compiling (§26.1). Otherwise:

```java
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
```

- **Compile and link are issued back to back**, without checking the compile status in between:
  checking would wait for the compile to finish. With the extension, all of it runs on the
  driver's threads.
- **`GL_PROGRAM_BINARY_RETRIEVABLE_HINT`**, set before linking, tells the driver the binary will
  be asked for, so it keeps what `glGetProgramBinary` needs.
- **Without parallel compilation** `poll` runs at once and, because the link is then finished,
  completes the record synchronously.

`compile` is `GLShaders.compile` without the status check: create, `glShaderSource` with the
pointer-array dance of §14 (a confined arena, because `glShaderSource` copies the text), and
`glCompileShader`.

### 25.5 poll: finishing a program

```java
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
```

- **`GL_COMPLETION_STATUS_KHR`** never waits (§8.4). Only when it says "done" is the link status
  read, which would otherwise block.
- **A failure** logs every stage's info log, with file names added by `annotate` (§24.1), plus
  the program's own log, and marks the record `FAILED`. A compile error surfaces as a link failure
  here, because compile and link were issued together.
- **Success** stores the binary for the next run (§26.1).
- **Either way** the shaders are detached and deleted (the program keeps its code, §14) and the
  sources released.

`close` deletes every program (and any shaders still attached) and frees the scratch.

## 26. Step 18: ProgramBinaryCache and EngineShaders

### 26.1 ProgramBinaryCache

[`ProgramBinaryCache`](../../modules/render/src/main/java/dev/jeddy/render/shader/ProgramBinaryCache.java)
keeps linked programs on disk (§8.5):

```java
private static final int MAGIC = 0x4E49424A; // "JBIN"

private final Path dir;
private final String driver;
private final boolean supported;

public ProgramBinaryCache(Path dir, GLCapabilities caps) {
    this.dir = dir;
    this.driver = caps.vendor() + "\n" + caps.renderer() + "\n" + caps.version();
    try (Arena a = Arena.ofConfined()) {
        MemorySegment n = a.allocate(ValueLayout.JAVA_INT);
        glGetIntegerv(GL_NUM_PROGRAM_BINARY_FORMATS, n);
        supported = n.get(ValueLayout.JAVA_INT, 0) > 0;
    }
}
```

- **The driver identity** is vendor, renderer and version string together. A binary is only
  valid for the driver that made it.
- **`supported`**: a driver may offer no binary format at all; then every method does nothing and
  the engine compiles as usual. (Recent Mesa llvmpipe does offer one, so CI caches too, D73.)
- **`MAGIC`** is the file's first four bytes. Written little-endian, `0x4E49424A` is the bytes
  `4A 42 49 4E`, the ASCII letters `J B I N` (L00 §8.1, lesson 4 for byte order).

The key:

```java
public String key(String... stageSources) {
    try {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update(driver.getBytes(StandardCharsets.UTF_8));
        for (String s : stageSources) {
            sha.update((byte) 0);
            sha.update(s.getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(sha.digest());
    } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException(e);
    }
}
```

A SHA-256 hash of the driver identity and the *preprocessed* sources, as 64 hex characters, used
as the file name. A driver update or a shader edit (in the file itself or in anything it includes,
or a different feature mask) gives a different key, so a stale binary is simply never found. The
zero byte before each stage keeps `("ab", "c")` and `("a", "bc")` from hashing the same.

Loading and storing:

```java
public boolean load(String key, int program) {
    if (!supported) {
        return false;
    }
    Path file = dir.resolve(key + ".bin");
    if (!Files.isRegularFile(file)) {
        return false;
    }
    try (Arena a = Arena.ofConfined()) {
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.length < 8 || b.getInt(0) != MAGIC) {
            Files.deleteIfExists(file);
            return false;
        }
        int format = b.getInt(4);
        MemorySegment data = a.allocate(bytes.length - 8L);
        MemorySegment.copy(bytes, 8, data, ValueLayout.JAVA_BYTE, 0, bytes.length - 8);
        glProgramBinary(program, format, data, (int) data.byteSize());
        MemorySegment status = a.allocate(ValueLayout.JAVA_INT);
        glGetProgramiv(program, GL_LINK_STATUS, status);
        if (status.get(ValueLayout.JAVA_INT, 0) == GL_TRUE) {
            return true;
        }
        Files.deleteIfExists(file); // the driver changed its mind: recompile
        return false;
    } catch (IOException e) {
        LOG.warn("cannot read program binary " + file, e);
        return false;
    }
}
```

The file is 4 bytes of magic, 4 bytes of the driver's binary **format** enum, then the blob. A
file with the wrong magic is deleted. A blob the driver rejects (its link status stays false) is
deleted too, and `ProgramCache` falls back to compiling. `store` is the mirror image: it asks for
`GL_PROGRAM_BINARY_LENGTH`, fetches the blob and its format with `glGetProgramBinary`, writes magic,
format and blob to a temporary file in the cache directory, and moves it over `key.bin`. Writing
to a temporary name first means a crash halfway never leaves a truncated file under the real name.

`ProgramCache` uses the cache when one is passed to its constructor;
`ProgramCacheTest.binariesSkipCompilationOnTheNextRun` shows a second cache loading the binary with
zero compiles (§28.4). `WorldRenderer.init` creates one from `WorldRenderer.Config.shaderCache`
(**D73**: `-Djeddy.shader.cache`, else the per-user cache directory: `%LOCALAPPDATA%\Jeddy\shaders`
on Windows, `$XDG_CACHE_HOME/jeddy/shaders` or `~/.cache/jeddy/shaders` on Linux; an empty value
turns it off) and leaves it out when the driver offers no binary format. Recent Mesa llvmpipe has
a binary format, so on the second run of the iso demo the log still says "program binaries cached
in ..." and no program compiles; loading should therefore be faster, by however long the
compiles took (not measured).

### 26.2 EngineShaders

The engine's own shader files ship as resources of the `render` module, under
`/dev/jeddy/render/shaders`. [`EngineShaders`](../../modules/render/src/main/java/dev/jeddy/render/shader/EngineShaders.java)
serves them:

```java
public static final String ROOT = "/dev/jeddy/render/shaders";

public static ShaderSources sources() {
    return EngineShaders::load;
}

private static String load(String path) throws IOException {
    String name = ROOT + "/" + path;
    try (InputStream in = EngineShaders.class.getResourceAsStream(name)) {
        if (in == null) {
            throw new NoSuchFileException(name);
        }
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
}
```

Why a class in `render` and not the generic `ShaderSources.classpath(anchor, root)` from `asset`?
On the module path, a module's resource directories are **encapsulated packages**: only code in
`dev.jeddy.render` may open them, and `Class.getResourceAsStream` called from `dev.jeddy.asset`
finds nothing. Tests ran on the class path, where this restriction does not exist, and hid the
problem until the first modular application ran (**D64**). The method reference
`EngineShaders::load` is a non-capturing lambda, a singleton, so `sources()` allocates nothing.

# Part C — Using it and proving it

## 27. How the engine uses it

### 27.1 Startup order

`HelloTriangle` loads GL itself. The engine's renderer,
[`WorldRenderer`](../../modules/app/src/main/java/dev/jeddy/app/world/WorldRenderer.java), does
it in its `init`, which `JeddyApp` calls on the main thread right after creating the window
(condensed: the logging lines are shortened):

```java
public void init(MainContext ctx) {
    caps = GLLoader.init(ctx.window()::glProcAddress);
    LOG.info("GL " + caps.version() + " on " + caps.renderer());
    if (caps.driver() == GLCapabilities.Driver.INTEL
            && System.getProperty("os.name", "").startsWith("Windows")) {
        // A Java program cannot export NvOptimusEnablement, so a laptop with a discrete GPU
        // runs it on the integrated one until the user says otherwise (docs/running.md).
        LOG.warn("running on the Intel GPU; ...");
    }
    if (JeddyApp.GL_DEBUG) {
        boolean debugContext = GLDebug.enable(true);
        // ... log whether the context really is a debug context
    }
    device = new GpuDevice(caps);
    device.useReversedZ();
    upload = new UploadRing(device, 64L << 20, 32L << 20);
    registry = new MeshRegistry(device, upload, config.meshVertices, config.meshIndices);
    programs = new ProgramCache(EngineShaders.sources(), caps, binaryCache());
    renderer = new SceneRenderer(device, programs, registry, ctx.jobs(), config.scene);
    // ... asset managers, debug draw, the overlay ring (4 MiB per frame), the UI ring
}
```

The order follows from the chapter:

1. **`GLLoader.init`** first, because nothing may touch a GL command before it (§11).
2. **The driver family** for the one workaround so far: a laptop that runs Java on its integrated
   Intel GPU gets a hint on how to move it to the discrete one.
3. **`GLDebug.enable`** only with `-Djeddy.gl.debug=true`, in which case `JeddyApp` has already
   asked for a debug context and turned critical linking off (§15.3).
4. **`GpuDevice`**, whose state cache resets GL's fixed-function state (§23.1), then
   **reversed-Z** (§17.1).
5. **The upload ring**, 64 MiB with a 32 MiB budget per frame (§17.3), and the **mesh registry**,
   whose constructor creates the one vertex array of §18.2.
6. **The program cache** with the engine's shaders and, when the driver supports it, the binary
   cache (§25–§26).
7. **The scene renderer**, which creates the frame ring and everything chapter 06 describes.

### 27.2 Jeddy's binding points

Collected from the shaders and the Java constants that bind them. Compute passes and the debug
renderers reuse low storage numbers for their own data while they run (`static_cull.comp`, for
example, uses storage bindings 0 and 2–6 for its own buffers).

**Uniform buffers**

| Binding | GLSL block | Java constant | Content |
|---|---|---|---|
| 0 | `JeddyFrame` (`engine/frame.glsl`) | `SceneRenderer.FRAME_BINDING` | per-frame constants, 544 B (§27.3) |
| 1 | `JeddySunShadows` (`engine/sun_shadows.glsl`) | `SunShadows.BINDING` | sun cascade matrices |
| 2 | `JeddyIrradiance` (`engine/environment.glsl`) | `Environment.SH_BINDING` | SH9 irradiance |

**Shader storage buffers**

| Binding | GLSL block | Java constant | Content |
|---|---|---|---|
| 0 | `JeddyInstances` (`engine/instance.glsl`) | `DrawBuilder.INSTANCE_BINDING` | 64-byte instance records |
| 1 | `JeddyMaterials` (`engine/material.glsl`) | `DrawBuilder.MATERIAL_BINDING` | 128-byte material rows |
| 2 | `JeddyLights` (`engine/lights.glsl`) | `LightClusters.LIGHTS_BINDING` | point and spot lights |
| 3 | `JeddyClusterGrid` | `LightClusters.RANGES_BINDING` | per-cluster ranges |
| 4 | `JeddyClusterItems` | `LightClusters.ITEMS_BINDING` | cluster light and decal lists |
| 5 | `JeddyExposure` (`post/exposure.glsl`) | `PostProcess.EXPOSURE_BINDING` | auto-exposure state |
| 6 | `JeddyDecals` (`engine/decals.glsl`) | `LightClusters.DECALS_BINDING` | decals |
| 7 | `JeddyClusterBounds` | `LightClusters.BOUNDS_BINDING` | cluster bounding boxes |
| 8 | `JeddyShadowViews` (`engine/local_shadows.glsl`) | `LocalShadows.BINDING` | spot and point shadow views |
| 9 | `JeddyPalettes` (`engine/skinning.glsl`) | `SceneRenderer.PALETTE_BINDING` | skinning matrices |
| 12, 13, 14 | `JeddyParticles`, `JeddyParticleLists`, `JeddyEmitters` | `GpuParticles.PARTICLES_BINDING`, `LISTS_BINDING`, `EMITTERS_BINDING` | GPU particles |

**Texture units**

| Unit | GLSL sampler | Java constant | Content |
|---|---|---|---|
| 0–4 | `jBaseColorMap`, `jNormalMap`, `jOrmMap`, `jEmissiveMap`, `jOcclusionMap` (`sampler2DArray`) | `MaterialSystem` texture slots (slot = unit) | material texture buckets |
| 5 | `jEnvironmentMap` | `Environment.CUBE_UNIT` | prefiltered environment cube |
| 6 | `jBrdfLut` | `Environment.LUT_UNIT` | BRDF lookup table |
| 7 | `jSunShadowMap` (`sampler2DArrayShadow`) | `SunShadows.UNIT` | sun cascades |
| 8 | `jShadowAtlas` (`sampler2DShadow`) | `LocalShadows.UNIT` | spot and point shadow atlas |
| 9 | `jAmbientOcclusion` | `Gtao.UNIT` | GTAO result |
| 10 | `jDecalTextures` | `DecalTextures.UNIT` | decal texture array |
| 11 | `uSceneDepth` (`pbr/outline_mask.frag`) | `Outlines.SCENE_DEPTH_UNIT` | scene depth for outlines |
| 12 | `jFogOfWar` | `FogOfWar.UNIT` | fog-of-war grid |

Remember that the three tables are separate numbering spaces (§7.5): uniform binding 0, storage
binding 0 and texture unit 0 are three different slots. DESIGN §9.3 has the same table in short
form. What each lighting binding holds is chapter [07](07-lighting-and-shadows.md); the post
bindings are chapter [08](08-effects-and-post.md).

### 27.3 The frame block in std140

[`engine/frame.glsl`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/engine/frame.glsl)
declares the per-frame constants:

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

Apply the rules of §7.3. A `mat4` is 64 bytes aligned to 16; a `vec4` or `uvec4` 16 bytes aligned
to 16; an array of `vec4` has stride 16 in std140 too. Every member therefore starts where the
previous one ended:

| Member | Offset | Size | Java writer (`SceneRenderer.writeFrameConstants`) |
|---|---|---|---|
| `jView` | 0 | 64 | `putMatrix(m, o, p.view)` |
| `jProjection` | 64 | 64 | `putMatrix(m, o + 64, p.projection)` |
| `jViewProjection` | 128 | 64 | `putMatrix(m, o + 128, ...)` |
| `jInverseViewProjection` | 192 | 64 | `putMatrix(m, o + 192, ...)` |
| `jInverseProjection` | 256 | 64 | `putMatrix(m, o + 256, scratch)` |
| `jEye` | 320 | 16 | `putVec4(m, o + 320, ...)` |
| `jViewport` | 336 | 16 | `putVec4(m, o + 336, ...)` |
| `jClusterZ` | 352 | 16 | `putVec4(m, o + 352, ...)` |
| `jClusterDims` | 368 | 16 | four `JAVA_INT` stores at `o + 368 ... o + 380` |
| `jSunDirection` | 384 | 16 | `putVec4(m, o + 384, ...)` |
| `jSunColor` | 400 | 16 | `putVec4(m, o + 400, ...)` |
| `jEnvironment` | 416 | 16 | `putVec4(m, o + 416, ...)` |
| `jCounts` | 432 | 16 | four `JAVA_INT` stores at `o + 432 ... o + 444` |
| `jReveal[0..3]` | 448 | 64 | `o + 448 + i * 16` |
| `jFogBounds` | 512 | 16 | `putVec4(m, o + 512, ...)` |
| `jFogParams` | 528 | 16 | `putVec4(m, o + 528, ...)` |

$5 \cdot 64 + 8 \cdot 16 + 64 + 2 \cdot 16 = 320 + 128 + 64 + 32 = 544$ bytes:
`SceneRenderer.FRAME_BYTES`. The matrices are column-major `float[16]`s already (chapter 02,
D21), so `putMatrix` is one `MemorySegment.copy` of 16 floats. The block is allocated in the frame
ring at an alignment of `max(16, uniformBufferOffsetAlignment)` (§7.5), written once per frame and
bound with `state.bindUniform(FRAME_BINDING, ring.glName(), frameOffset, FRAME_BYTES)` for every
pass that needs it; the state cache skips the repeats (§23.3).

## 28. The tests, walked through

GPU tests are tagged `@Tag("gpu")` and run with `-Pjeddy.gpuTests=true` (or `=only`). They need a
GL 4.6 driver; CI runs them on Linux without a GPU, under `xvfb-run -a` with Mesa llvmpipe and
`MESA_GL_VERSION_OVERRIDE=4.6` (DESIGN §9.7: llvmpipe implements 4.5 and most of 4.6, and the
override lets the 4.6 context be created). Every GPU test turns critical linking off first and
enables the debug callback, through
[`GpuTestContext`](../../modules/render/src/test/java/dev/jeddy/render/GpuTestContext.java) in the
render module or by hand in `GLTest`, and most of them check at the end that the callback saw no
new error.

### 28.1 GLTest

[`GLTest`](../../modules/gl/src/test/java/dev/jeddy/gl/GLTest.java) proves the bindings, the
loader and the debug output (M3-02 to M3-04). Its setup is §11–§16 in a few lines:

```java
@BeforeAll
static void init() {
    System.setProperty("jeddy.gl.critical", "false"); // the debug callback needs it off
    Platform.init("Jeddy GL tests", "0.1", "dev.jeddy.tests.gl");
    window =
            Window.create(
                    new WindowConfig()
                            .hidden(true)
                            .size(SIZE, SIZE)
                            .minimumSize(16, 16)
                            .glDebug(true)
                            .vsync(VSync.OFF));
    caps = GLLoader.init(window::glProcAddress);
    GLDebug.enable(true);
}
```

- **`capabilitiesDescribeTheContext`**: at least 4.6, a non-empty renderer, more than 50
  extensions, `GL_ARB_direct_state_access` present, `maxTextureSize` at least 8192, a positive
  uniform-buffer alignment, a storage block size of at least $2^{24}$ bytes, and an address for
  `glMultiDrawElementsIndirectCount`.
- **`debugOutputReportsErrorsWithoutThrowing`**: `glEnable(0x7FFF_FFFF)` is not a capability, so
  GL raises `GL_INVALID_ENUM`. The test checks that `glGetError` reports it *and* that the
  synchronous callback counted it (`errorCount()` moved), without any exception reaching the
  test. Then it pushes and pops a debug group.
- **`missingCommandsThrowOnlyWhenCalled`**: links `glMakeTextureHandleResidentARB` against an
  extension named `GL_JEDDY_not_an_extension`. Linking succeeds; invoking throws
  `UnsupportedOperationException` (§11). And `GLLinker.rethrow` wraps a checked exception in an
  `IllegalStateException`.
- **`helloTriangleRendersWithDsa`**: a triangle drawn with DSA calls only, then read back.

Next to it, [`GLCapabilitiesTest`](../../modules/gl/src/test/java/dev/jeddy/gl/GLCapabilitiesTest.java)
needs no GPU: `vendorsAreClassifiedByWordNotBySubstring` calls `GLCapabilities.classify` with
vendor, renderer and version strings and checks the driver family of each (§12, exercise 18):
"Intel" and "Intel Corporation" are Intel, "Microsoft Corporation" is other (not AMD), "ATI
Technologies Inc." and "AMD" are AMD, "NVIDIA Corporation" is NVIDIA, an AMD vendor with a Mesa
version string is Mesa, and an llvmpipe renderer is software.

Back to `helloTriangleRendersWithDsa`, in detail. It builds a vertex buffer of three 2D positions
$(-0.8, -0.8)$, $(0.8, -0.8)$, $(0, 0.8)$, a vertex array with one attribute (stride 8), a label
on the buffer, and an **index buffer** of three `short`s attached with `glVertexArrayElementBuffer`.
It clears the 64 × 64 window to $(0.1, 0.2, 0.3)$ and draws with the offset overload:

```java
glDrawElements(GL_TRIANGLES, 3, GL_UNSIGNED_SHORT, 0L); // offset overload
assertEquals(GL_NO_ERROR, glGetError());

MemorySegment pixel = arena.allocate(4);
glReadPixels(SIZE / 2, SIZE / 2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
assertEquals(255, Byte.toUnsignedInt(pixel.get(ValueLayout.JAVA_BYTE, 0)), "red");
assertEquals(128, Byte.toUnsignedInt(pixel.get(ValueLayout.JAVA_BYTE, 1)), 2, "green");
glReadPixels(1, SIZE - 2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
assertEquals(26, Byte.toUnsignedInt(pixel.get(ValueLayout.JAVA_BYTE, 0)), 1, "clear");
assertEquals(77, Byte.toUnsignedInt(pixel.get(ValueLayout.JAVA_BYTE, 2)), 1, "clear");
```

- `0L` is the offset into the element buffer: the indices start at byte 0 (§10.7).
- **The center pixel** $(32, 32)$ has its center at NDC $(0.5/32,\ 0.5/32) \approx (0.016, 0.016)$,
  inside the triangle, so it has the fragment shader's color $(1, 0.5, 0)$: red 255, green
  $0.5 \cdot 255 = 127.5$, which rounds to 128 or 127 depending on the driver's tie rule, hence the
  tolerance of 2 (§2.14).
- **The corner pixel** $(1, 62)$ has its center at NDC
  $(1.5/32 - 1,\ 62.5/32 - 1) \approx (-0.953,\ 0.953)$, far outside the triangle: it keeps the
  clear color, red $\mathrm{round}(25.5) = 26$ and blue $\mathrm{round}(76.5) = 77$.
- `Byte.toUnsignedInt` undoes Java's signed bytes (L00 §5.4): 255 is stored as the byte −1.

Then the allocation proof:

```java
// Per-frame calls allocate nothing (offset overload, no MemorySegment boxing).
Allocations.assertNoAllocation(
        "draw",
        10,
        500,
        () -> {
            glUseProgram(program);
            glBindVertexArray(vao);
            glDrawElements(GL_TRIANGLES, 3, GL_UNSIGNED_SHORT, 0L);
        });
```

`assertNoAllocation(what, maxRounds, iterationsPerRound, body)` runs the body in rounds until the
JIT has compiled it and a round allocates zero bytes (L00 §8.2). A program, a bind and a draw
through the generated bindings allocate nothing. The test then swaps, checks that the debug
callback saw no error, and deletes everything.

### 28.2 GpuDeviceTest

[`GpuDeviceTest`](../../modules/render/src/test/java/dev/jeddy/render/gpu/GpuDeviceTest.java)
covers the resource layer and the state cache (M5-03):

- **`buffersMapUpdateAndReadBack`**: a 256-byte `MAPPED` buffer is written through its mapping
  (`i * 3` in each of 64 ints) and read back with `glGetNamedBufferSubData` through the `long`
  overload (`back.address()`): element 63 is 189. A 64-byte `DYNAMIC` buffer is updated at offset
  16 with four floats and element 2 of the read-back is 3.0. An update at offset 60 of 16 bytes
  would end at 76, past 64, and throws `IndexOutOfBoundsException` before reaching GL. After
  `destroy`, `glName` of the old handle throws: a stale handle (§17.1).
- **`compressedAndPlainTexturesUploadPerLevel`**: an 8 × 8 BC4 texture is four blocks; each block
  gets endpoints 200 and 10 and all-zero indices, and all 64 texels read back as 200 (§6.7). Then
  level 1 of a 4 × 4 `RGBA8` texture with 3 levels (2 × 2 texels, 16 bytes of 77s) is uploaded and
  read back; a cube with six 8 × 8 `RGBA16F` faces and a three-layer BC7 array are created and
  uploaded; `GpuFormats` maps `VK_FORMAT_BC7_SRGB_BLOCK` to the sRGB BPTC format, an unknown
  format to 0, and calls BC5 compressed. No debug errors.
- **`samplersAreCachedByDescription`**: the same description returns the same sampler; adding
  anisotropy gives another; `SHADOW`'s comparison function reads back as `GL_GEQUAL`; the
  anisotropic sampler's `GL_TEXTURE_MAX_ANISOTROPY` is above 1.
- **`framebuffersRenderOffscreen`**: a 32 × 32 color texture and a depth texture make a complete
  framebuffer; clearing it to green and reading the texture gives red 0 and green 255. A
  framebuffer whose *color* attachment is the depth texture is incomplete, and `create` throws
  `IllegalStateException` (§21).
- **`stateCacheSkipsRedundantCalls`**, the state cache's specification:

  ```java
  state.invalidate();
  state.apply(PipelineState.OPAQUE);
  long before = state.issuedCalls();
  state.apply(PipelineState.OPAQUE);
  assertEquals(before, state.issuedCalls(), "no calls for the same state");
  MemorySegment out = ARENA.allocate(16);
  glGetIntegerv(GL_DEPTH_FUNC, out);
  assertEquals(GL_GEQUAL, out.get(ValueLayout.JAVA_INT, 0));
  assertTrue(glIsEnabled(GL_CULL_FACE));
  state.apply(PipelineState.TRANSPARENT);
  assertTrue(glIsEnabled(GL_BLEND));
  assertTrue(!glIsEnabled(GL_CULL_FACE));
  glGetIntegerv(GL_DEPTH_WRITEMASK, out);
  assertEquals(0, out.get(ValueLayout.JAVA_INT, 0));
  long t0 = state.issuedCalls();
  state.apply(
          PipelineState.withBlend(PipelineState.TRANSPARENT, PipelineState.BLEND_ADDITIVE));
  assertEquals(t0 + 2, state.issuedCalls(), "only the blend function");
  state.apply(PipelineState.DEPTH_ONLY);
  glGetBooleanv(GL_COLOR_WRITEMASK, out);
  assertEquals(0, out.get(ValueLayout.JAVA_BYTE, 0));
  ```

  Applying the same state twice costs nothing; GL really holds what the cache says (asked back
  with `glGetIntegerv` and `glIsEnabled`); changing only the blend mode costs exactly the two
  calls of §23.2 (`glEnable(GL_BLEND)` and `glBlendFunc`: the XOR is `0x100`, the blend field
  only). Then an allocation check over `apply`, `bindTexture` and `bindSampler` in a loop that
  changes state every iteration, and a final check that rebinding the same texture and sampler
  issues no call.

### 28.3 RingsTest

[`RingsTest`](../../modules/render/src/test/java/dev/jeddy/render/gpu/RingsTest.java) has two
tests. `frameRingHandsOutDisjointRangesPerFrame` is walked through in lesson 1 (§16 there):
aligned, disjoint ranges in region $f \bmod 3$, −1 for a request larger than a region, four
threads allocating 1,000 blocks each without overlap, zero allocation per frame, and at most 1,000
stalls (not zero: a real GPU can lag three microsecond-long empty frames, lesson 1 §16).

`uploadRingCopiesWithinBudgetAndReclaims` checks §17.3 with a 4,096-byte ring and a budget of
1,024 bytes:

- **Budget.** Two reservations of 512 bytes succeed; a third of 16 bytes would bring the frame to
  1,040 bytes and returns −1 ("budget spent").
- **A buffer copy.** The first 512 bytes hold the ints 1 to 128, copied with `copyToBuffer` to
  offset 1,024 of a target buffer; after `endFrame` the read-back's element 127 is 128.
- **A texture upload through the pixel-unpack buffer.** A 4 × 4 BC4 texture is one 8-byte block;
  the test writes red0 = 99, red1 = 0 and all-zero indices into the ring, calls
  `copyToTexture`, and reads texel 5 back as 99.
- **Reclaiming.** Fifty frames each reserve 1,024 bytes of the 4,096 (with `glFinish` after each,
  so every fence has signalled by the next `beginFrame`): every reservation succeeds, after the
  loop the bytes in flight are at most the capacity, and at least $50 \cdot 1024$ bytes went
  through.

### 28.4 The shader tests

- [`ShaderPreprocessorTest`](../../modules/asset/src/test/java/dev/jeddy/asset/shader/ShaderPreprocessorTest.java)
  needs no GPU. `expandsIncludesOnceAndInjectsDefines` is the worked example of §24.2: the
  defines right after `#version`, `#line 2 0`, `math.glsl` once, the `files` list, `#line 3 0`
  after the first include, `#line 1 1` and `#line 1 2` at the start of each included file, and
  `annotate` naming `pbr/brdf.glsl` for file number 2. `reportsCyclesMissingFilesAndBadVersions`
  checks the cycle message (`a.glsl -> b.glsl -> a.glsl`), a file without `#version`, a `#version`
  in an included file, an include that escapes the root, and a missing file
  (`NoSuchFileException`).
- [`ShaderFeaturesTest`](../../modules/render/src/test/java/dev/jeddy/render/shader/ShaderFeaturesTest.java)
  needs no GPU either (§25.1).
- [`ProgramCacheTest`](../../modules/render/src/test/java/dev/jeddy/render/shader/ProgramCacheTest.java)
  serves its shaders from a `Map`. `variantsCompileOnceAndFailuresCarryTheLog` compiles a triangle
  program in two variants (mask 0 green, mask 1 `RED`): two different programs, two compiles,
  and asking again returns the same program. It builds a compute program (`fill.comp`), checks
  that looking up an existing variant allocates nothing, and that a broken fragment shader
  (`undefined_call()`) ends in a `ShaderException` whose message names the program, with no GL
  error left behind. `binariesSkipCompilationOnTheNextRun` builds a variant with a binary cache in
  a temporary directory: one compile and one `.bin` file. A second `ProgramCache` over the same
  directory gets the program with one binary load and **zero compiles**. (If the driver offers no
  binary format, the test stops after the first half.)

## 29. Measured numbers

The FFM call costs were measured for M3-05 and recorded in
[`bench/README.md`](../../bench/README.md) (a local run on JDK 27, x64 Linux, Mesa llvmpipe; a
single-thread loop, not JMH; CI posts JMH numbers from
[`GlBench`](../../bench/src/main/java/dev/jeddy/bench/GlBench.java) and `FfmBench`):

| Call | ns per call |
|---|---|
| `abs` (a trivial C function), regular / critical | 16.5 / 3.6 |
| 64-byte block, off-heap regular / off-heap critical / heap critical / copy then regular | 13.9 / 10.1 / 10.5 / 18.9 |
| `glUniform4f` regular / critical / generated `GL46` | 37.0 / 33.2 / 33.5 |
| `glUniformMatrix4fv` off-heap regular / heap critical / copy then regular | 37.6 / 27.7 / 36.1 |
| `glNamedBufferSubData` 64 B | 139 |

What they say (DESIGN §4.4):

- **Critical linking saves about 13 ns per call**: $16.5 - 3.6 = 12.9$ ns on the trivial function.
  Under llvmpipe the driver's own work dominates `glUniform*` (33 ns critical against 37 regular),
  so on hardware drivers, which do less per call, the relative gain is larger.
- **Heap access makes `float[]` parameter blocks as cheap as off-heap ones** (27.7 ns for a matrix
  from a heap array through a critical call, against 36.1 ns for copying it off-heap first).
- **The generated wrappers add nothing**: 33.5 ns for `GL46.glUniform4f` against 33.2 ns for the
  hand-held critical handle. The holder idiom works as intended (§10.6).
- **Uploads are not cheap calls**: a 64-byte `glNamedBufferSubData` costs 139 ns on llvmpipe, and
  it is not on the critical list because it can stall.

A sense of scale, as arithmetic and not a measurement: a frame that makes 1,000 calls of the
allow-listed kinds saves about $1000 \cdot 13 = 13{,}000$ ns $= 13$ µs with critical linking,
under 0.1 % of a 16.7 ms frame. The bigger saving is the one the state cache makes by not calling
at all (§23).

The zero-allocation claims are proven rather than measured: GL calls with the offset overload
(§28.1), the state cache (§28.2), a program lookup (§28.4) and a frame of the ring (§28.3) each
pass `Allocations.assertNoAllocation`.

## 30. Things to run

GPU tests and samples need a GL 4.6 driver. On Linux without a GPU, prefix the commands with
`xvfb-run -a` and set `MESA_GL_VERSION_OVERRIDE=4.6`, as CI does. On Windows use `gradlew.bat`.

Generate the bindings and look at them (the output is
`modules/gl/build/generated/sources/gl/java/main/dev/jeddy/gl/ffi/GL46.java`):

```bash
./gradlew :gl:generateGlBindings
```

Run the generator by hand, with the JDK's single-file launcher, into a directory of your choice:

```bash
java build-logic/src/main/java/dev/jeddy/gradle/GlGenerator.java bindings/gl/gl.xml bindings/gl/gl.properties build/gl-by-hand
```

Run the triangle; quit after 3 seconds as CI does; then with the driver's debug output:

```bash
./gradlew :hello-triangle:run
JEDDY_SAMPLE_QUIT_AFTER=3 ./gradlew :hello-triangle:run
JDK_JAVA_OPTIONS=-Djeddy.gl.debug=true ./gradlew :hello-triangle:run
```

(In PowerShell set the variable first: `$env:JDK_JAVA_OPTIONS="-Djeddy.gl.debug=true"`.)

The tests of this chapter:

```bash
./gradlew :gl:test -Pjeddy.gpuTests=true --tests 'dev.jeddy.gl.GLTest'
./gradlew :gl:test --tests 'dev.jeddy.gl.GLCapabilitiesTest'
./gradlew :render:test -Pjeddy.gpuTests=true --tests 'dev.jeddy.render.gpu.*'
./gradlew :render:test -Pjeddy.gpuTests=true --tests 'dev.jeddy.render.shader.*'
./gradlew :asset:test --tests 'dev.jeddy.asset.shader.ShaderPreprocessorTest'
```

The call-cost benchmark (critical against regular, heap against off-heap):

```bash
./gradlew :bench:jmh -Pjmh.includes=GlBench
```

And the iso demo with the driver's messages in the log, the first thing to try when something
renders wrong on a new GPU ([running.md](../running.md)):

```bash
./gradlew :iso-demo:run "-Pjeddy.jvmArgs=-Djeddy.gl.debug=true"
```

## 31. Why it is built this way

| Choice | Instead of | Why | Decision |
|---|---|---|---|
| Own generator from `gl.xml`, run at build time | a third-party binding library; jextract; committed output | no runtime dependencies; most GL commands are not exported symbols; exact control of types, critical list and overloads; the pinned registry fixes the API | D2, D24, DESIGN §9.1 |
| A `static final` handle per command in a holder class, linked lazily | one table of handles | JIT constants, fast startup, missing commands fail only when called | DESIGN §9.1, §4.4, M3-05 |
| Critical linking for an allow-list, off in dev builds | all regular, or all critical | 13 ns saved on short calls; safety for blocking calls and for the debug upcall | DESIGN §4.4, D12 |
| `long` overloads, `GLsync` as `long` | `MemorySegment` everywhere | zero allocation per draw, upload and fence | D24, D29 |
| Extension commands gated on the extension string | trusting the address | GLX returns a stub for any name | D24 |
| GL on the main thread only | a separate render thread | SDL's main-thread rules; one owner for window, events and context | D6 |
| DSA only, binding only to use | bind-to-edit | no hidden state, a reliable state cache | DESIGN §9.2 |
| Handles and SoA arrays for GL objects | a wrapper object per buffer or texture | stale-handle detection, no object graph for the GC | DESIGN §4.1 |
| The state cache forgets deleted names | trusting cached names | GL reuses names | D50 |
| Pipeline state in a `long`, sampler description in an `int` | objects | one-instruction comparison and difference, free hash keys, no allocation | TASKS M5-03 |
| No sRGB default framebuffer | `GL_FRAMEBUFFER_SRGB` | Xvfb has no sRGB visuals; same output on every driver | D23 |
| A separate position stream, 16-byte attributes, one vertex array | one interleaved stream per mesh | depth passes fetch 12 B instead of 28 per vertex; multi-draw-indirect needs one vertex array | D27, D80 |
| The preprocessor in `asset` | in `render` | the cooker checks shaders with the same code | D34 |
| Engine shaders served by a class in `render` | `ShaderSources.classpath` from `asset` | resource directories are encapsulated on the module path | D64 |
| Program binaries cached per user, keyed by driver and source | compiling at every start | faster starts; a stale binary is never found | D73 |
| A CPU mirror of the tail of compressed arrays | GPU copy or read-back of those levels | an Intel driver rejects both | D101 |
| Buffers in flight changed through the frame ring and a GPU copy | `glNamedBufferSubData` | an Intel driver silently dropped such writes | D103 |

## 32. Exercises

Each has a hint and a full solution; try before you open them. Exercises 1 and 8 change the sample
(run it with `./gradlew :hello-triangle:run`); 2 reads the generated code; 3–7 add GPU tests (run
with `-Pjeddy.gpuTests=true`); 9–16 are pencil-and-paper; 17–20 read the code closely.

**1. An indexed quad (§2.3, §15).** Change `HelloTriangle` to draw a square: four vertices, an index
buffer `{0, 1, 2, 0, 2, 3}` attached with `glVertexArrayElementBuffer`, and
`glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, 0L)`. How many times does the vertex shader
run per frame?

<details><summary>Hint</summary>

`GLTest.helloTriangleRendersWithDsa` builds an index buffer from a `short[]` with
`glNamedBufferStorage` and draws with the `long` overload. Writing `0L` makes the choice explicit;
a plain `0` would also select the `long` overload (Java widens `int` to `long`, and an `int` can
never become a `MemorySegment`). The `MemorySegment` overload would need `MemorySegment.NULL` and
is the one to avoid in a frame loop.
</details>

<details><summary>Solution</summary>

In `init`, replace the vertices and add the index buffer (a new field `ibo`):

```java
float[] vertices = {
    -0.5f, -0.5f, 1f, 0.2f, 0.1f,
    0.5f, -0.5f, 0.1f, 1f, 0.2f,
    0.5f, 0.5f, 0.2f, 0.3f, 1f,
    -0.5f, 0.5f, 1f, 1f, 1f
};
short[] indices = {0, 1, 2, 0, 2, 3};
MemorySegment id = arena.allocate(ValueLayout.JAVA_INT);
glCreateBuffers(1, id);
vbo = id.get(ValueLayout.JAVA_INT, 0);
glNamedBufferStorage(
        vbo, vertices.length * 4L, arena.allocateFrom(ValueLayout.JAVA_FLOAT, vertices), 0);
glCreateBuffers(1, id);
ibo = id.get(ValueLayout.JAVA_INT, 0);
glNamedBufferStorage(
        ibo, indices.length * 2L, arena.allocateFrom(ValueLayout.JAVA_SHORT, indices), 0);
// ... the vertex array exactly as before, then:
glVertexArrayElementBuffer(vao, ibo);
```

In `render`, replace the draw with `glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, 0L);`, and
in `shutdown` delete `ibo` too (`glDeleteBuffers(2, ...)` with both names, or two calls). Both
triangles are counter-clockwise: $(0, 1, 2)$ goes from the lower left to the lower right to the
upper right, and $(0, 2, 3)$ from the lower left to the upper right to the upper left, which
matters as soon as culling is on.

The vertex shader runs for 6 indices, but vertices 0 and 2 are shared; with the post-transform
cache of §2.3 a GPU typically shades 4 vertices, not 6. (OpenGL does not promise either number:
the cache is a hardware optimization, not part of the specification.)
</details>

**2. Read the generator's output (§10.6–§10.7).** Generate the bindings, open `GL46.java` and find
both overloads of `glDrawElements` and their holders. Which part of the `FunctionDescriptor`
differs? Then explain why `glNamedBufferSubData` has a `long` overload for `data` but none for
`offset`, and why `glGetIntegerv`, whose last parameter is also named `data`, has no `long`
overload at all.

<details><summary>Hint</summary>

Look at the condition in `GlGenerator.emit`: the parameter name must be in `offset.params` *and*
its C declaration must start with `const void *` or `void *` with a single `*`. The registry
declares `glGetIntegerv`'s parameter as `GLint *data`.
</details>

<details><summary>Solution</summary>

`glDrawElements(int mode, int count, int type, MemorySegment indices)` has the holder
`glDrawElements` with `FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS)`;
`glDrawElements(int mode, int count, int type, long indices)` has the holder
`glDrawElements$offset` with `FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG)`.
Only the last layout differs; both link the same address.

In `glNamedBufferSubData(GLuint buffer, GLintptr offset, GLsizeiptr size, const void *data)`,
`offset` is named in `offset.params` but its C type is `GLintptr`, not a `void *`: it is already a
`long` in the ordinary method. `data` passes all three conditions. In `glGetIntegerv(GLenum pname,
GLint *data)` the name matches but the declaration is `GLint *data`, a typed pointer, so the
second condition fails: it is an out-parameter that receives results, never an offset.
</details>

**3. Predict the state cache (§22–§23).** How many GL calls does `StateCache.apply` issue going
from `PipelineState.OPAQUE` to `DEPTH_ONLY`? From `DEPTH_ONLY` to `TRANSPARENT`? Add assertions on
`issuedCalls()` to `GpuDeviceTest` and run it.

<details><summary>Hint</summary>

XOR the values: $\texttt{0x3C6D} \oplus \texttt{0x006D}$ and $\texttt{0x006D} \oplus
\texttt{0x3CE4}$, then count the fields with a 1 and the calls each field costs.
</details>

<details><summary>Solution</summary>

$\texttt{0x3C6D} \oplus \texttt{0x006D} = \texttt{0x3C00}$: the color mask only, one `glColorMask`.
$\texttt{0x006D} \oplus \texttt{0x3CE4} = \texttt{0x3C89}$, bits 0, 3, 7 and 10–13: cull (the new
mode is none, so one `glDisable`), depth write (one `glDepthMask`), blend (`glEnable` and
`glBlendFunc`, two) and color mask (one `glColorMask`): five calls. In
`stateCacheSkipsRedundantCalls`, or a new test:

```java
state.apply(PipelineState.OPAQUE);
long a = state.issuedCalls();
state.apply(PipelineState.DEPTH_ONLY);
assertEquals(a + 1, state.issuedCalls(), "color mask only");
state.apply(PipelineState.TRANSPARENT);
assertEquals(a + 1 + 5, state.issuedCalls(), "cull, depth write, blend (2), color mask");
```
</details>

**4. A new preset (§22).** Add `PipelineState.ADDITIVE`: depth test `GL_GEQUAL` without depth
writes, no culling, `BLEND_ADDITIVE`. Test in `GpuDeviceTest` that after `apply(ADDITIVE)`,
`glGetIntegerv(GL_BLEND_SRC_RGB)` and `glGetIntegerv(GL_BLEND_DST_RGB)` both return `GL_ONE` and
`GL_DEPTH_WRITEMASK` reads 0. What is its value in hex?

<details><summary>Hint</summary>

Build it from `TRANSPARENT` with `withBlend`. The existing test already reads `GL_DEPTH_WRITEMASK`
into a 16-byte segment; copy that pattern. Mind the order of static initializers.
</details>

<details><summary>Solution</summary>

In `PipelineState`, *after* `TRANSPARENT` (static fields initialize in textual order, and
`TRANSPARENT` is computed by a method call, so it is not a compile-time constant):

```java
/** Additive effects: depth test without writes, no culling, src + dst. */
public static final long ADDITIVE = withBlend(TRANSPARENT, BLEND_ADDITIVE);
```

Its value is `0x3CE4` with the blend field changed from 1 to 3: $\texttt{0x3CE4} + 2 \cdot 128 =
\texttt{0x3DE4}$. The test:

```java
@Test
void additivePresetBlendsOneOne() {
    StateCache state = device.state();
    state.apply(PipelineState.ADDITIVE);
    MemorySegment out = ARENA.allocate(16);
    glGetIntegerv(GL_BLEND_SRC_RGB, out);
    assertEquals(GL_ONE, out.get(ValueLayout.JAVA_INT, 0));
    glGetIntegerv(GL_BLEND_DST_RGB, out);
    assertEquals(GL_ONE, out.get(ValueLayout.JAVA_INT, 0));
    glGetIntegerv(GL_DEPTH_WRITEMASK, out);
    assertEquals(0, out.get(ValueLayout.JAVA_INT, 0));
    assertTrue(glIsEnabled(GL_BLEND));
    state.apply(PipelineState.OPAQUE);
}
```

The last line leaves the shared device in a known state for the other tests. (A real change to
`PipelineState` would also need its Javadoc and, by CLAUDE.md's rules, a note in DESIGN if it
departs from it.)
</details>

**5. See std140 with your own eyes (§7.3).** Write a GPU test with a compute shader that reads
`layout(std140, binding = 0) uniform U { float v[4]; };` and copies `v[i]` into
`layout(std430, binding = 1) buffer O { float o[]; };`. Fill the uniform buffer from Java with 16
floats `0, 1, 2, ..., 15` and predict which four values arrive. Then read the input as a std430
storage block instead.

<details><summary>Hint</summary>

In std140 each array element is padded to 16 bytes. Use `GLShaders.compile(GL_COMPUTE_SHADER,
...)` and `GLShaders.link`, `glDispatchCompute(1, 1, 1)`, then
`glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT)` before `glGetNamedBufferSubData` (lesson 1 §6.3).
You bind state directly, so tell the state cache afterwards.
</details>

<details><summary>Solution</summary>

`v[i]` is at byte $16i$, which is float number $4i$: the values 0, 4, 8, 12 arrive. In
`GpuDeviceTest` (it has `device` and `ARENA`):

```java
@Test
void std140ArraysHaveAStrideOf16() {
    long errors = GLDebug.errorCount();
    String source =
            """
            #version 460 core
            layout(local_size_x = 4) in;
            layout(std140, binding = 0) uniform U { float v[4]; };
            layout(std430, binding = 1) buffer O { float o[]; };
            void main() { o[gl_LocalInvocationID.x] = v[gl_LocalInvocationID.x]; }
            """;
    int program = GLShaders.link(GLShaders.compile(GL_COMPUTE_SHADER, source));
    float[] values = new float[16];
    for (int i = 0; i < 16; i++) {
        values[i] = i;
    }
    GpuBuffers b = device.buffers();
    int in = b.create(ARENA.allocateFrom(ValueLayout.JAVA_FLOAT, values), 0, "std140 input");
    int out = b.create(16, 0, "std430 output");
    glBindBufferRange(GL_UNIFORM_BUFFER, 0, b.glName(in), 0, 64);
    glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 1, b.glName(out), 0, 16);
    glUseProgram(program);
    glDispatchCompute(1, 1, 1);
    glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
    MemorySegment back = ARENA.allocate(16);
    glGetNamedBufferSubData(b.glName(out), 0, 16, back.address());
    for (int i = 0; i < 4; i++) {
        assertEquals(4f * i, back.getAtIndex(ValueLayout.JAVA_FLOAT, i));
    }
    device.state().invalidate(); // program and bindings were changed behind the cache
    glDeleteProgram(program);
    b.destroy(in);
    b.destroy(out);
    assertEquals(errors, GLDebug.errorCount());
}
```

(It needs `import dev.jeddy.gl.GLShaders;`.) A buffer created with flags 0 can still be read back
with `glGetNamedBufferSubData`; the flags restrict CPU *writes* and mapping. For the second part,
declare `layout(std430, binding = 0) readonly buffer U { float v[]; };` and bind the input with
`glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 0, ...)`: the stride becomes 4 and 0, 1, 2, 3 arrive.
(A *uniform* block cannot be std430 in GL 4.6.)
</details>

**6. Error messages that point at files (§24).** Write a GPU test that preprocesses (with
`ShaderPreprocessor`) a fragment shader whose *included* file contains an error on its third line,
compiles the result with `GLShaders.compile` (its `GLException` message contains the driver's info
log), and asserts that `Result.annotate(message)` names the included file and line 3. Then delete
the `#line 1` that `ShaderPreprocessor.expand` writes at the start of an included file, predict
what the test reports now, and run it.

<details><summary>Hint</summary>

`ProgramCacheTest` builds `ShaderSources` from a `Map` and already has `broken.frag`; put the
broken line in an included file instead. (`ProgramCache` only logs the annotated output; the
`ShaderException` it throws carries just the program name, so call the preprocessor yourself.)
The driver's message format varies (Mesa prints `0:12(3): error`, NVIDIA `0(12) : error`), and
`annotate` handles a number followed by `(` or `:`.
</details>

<details><summary>Solution</summary>

In `ProgramCacheTest` (it has a `GpuTestContext`):

```java
@Test
void errorsInIncludedFilesNameTheFile() throws Exception {
    Map<String, String> files =
            Map.of(
                    "main.frag",
                    "#version 460 core\n"
                            + "#include \"lib.glsl\"\n"
                            + "layout(location = 0) out vec4 c;\n"
                            + "void main() { c = f(); }\n",
                    "lib.glsl",
                    "#pragma once\n" + "vec4 f() {\n" + "    return undefined_value;\n" + "}\n");
    ShaderSources sources = files::get;
    ShaderPreprocessor.Result r = new ShaderPreprocessor(sources).process("main.frag", List.of());
    GLException e =
            assertThrows(
                    GLException.class,
                    () -> GLShaders.compile(GL_FRAGMENT_SHADER, r.source()));
    String annotated = r.annotate(e.getMessage());
    assertTrue(annotated.contains("<- lib.glsl"), annotated);
    assertTrue(annotated.matches("(?s).*1[:(]3\\D.*"), annotated);
}
```

(Imports: `ShaderPreprocessor`, `GLException`, `GLShaders`, `java.util.List`.) The expanded source
has `#line 1 1` before `lib.glsl`, so `return undefined_value;` is line 3 of source string 1, and
the driver reports `1:3(...)` (Mesa) or `1(3)` (NVIDIA); `annotate` appends `<- lib.glsl`. (On a
driver whose log lines begin with a word, such as `ERROR: 1:3: ...`, `annotate` adds nothing, so
the first assertion fails there; §24.1.)

Without the `#line 1 <fileIndex>`, the numbering after `#version` is `#line 2 0`: the blank line
left by `#pragma once` is line 2 of file 0, `vec4 f() {` line 3 and the faulty line **line 4 of
file 0**. The driver now reports `0:4`, and `annotate` names `main.frag`, whose line 4 is the
innocent `void main()`: the wrong file at the wrong line. That was a real bug, found while this
guide was first written; `ShaderPreprocessorTest` checks the directive since then.
</details>

**7. A sampler field (§20).** Add a level-of-detail bias to `SamplerDesc`, 4 signed bits at
16–19 in steps of 0.5, and apply it in `GpuSamplers.get` with
`glSamplerParameterf(name, GL_TEXTURE_LOD_BIAS, bias)`. Test that two descriptions differing only
in bias return different sampler names and that `glGetSamplerParameterfv` reads the bias back.

<details><summary>Hint</summary>

Keep the presets unchanged (bias 0). Sign-extend a 4-bit field at bits 16–19 of an `int` with
`(d << 12) >> 28`: the left shift puts bit 19 at bit 31, and the arithmetic right shift copies it
down (L00 §6.3).
</details>

<details><summary>Solution</summary>

In `SamplerDesc`:

```java
private static final int LOD_BIAS = 16; // 4 bits, signed, steps of 0.5: -4.0 .. +3.5

/** Returns {@code desc} with a level-of-detail bias (rounded to 0.5, clamped to -4 .. 3.5). */
public static int withLodBias(int desc, float bias) {
    int steps = Math.max(-8, Math.min(7, Math.round(bias * 2)));
    return (desc & ~(15 << LOD_BIAS)) | ((steps & 15) << LOD_BIAS);
}

static float lodBias(int d) {
    return ((d << 12) >> 28) * 0.5f;
}
```

`steps & 15` keeps the low four bits of a negative number (two's complement, L00 §5): −2 is stored
as `1110`. In `GpuSamplers.get`, after the anisotropy:

```java
float bias = SamplerDesc.lodBias(desc);
if (bias != 0) {
    glSamplerParameterf(name, GL_TEXTURE_LOD_BIAS, bias);
}
```

The test, in `GpuDeviceTest`:

```java
@Test
void lodBiasIsPartOfTheDescription() {
    GpuSamplers samplers = device.samplers();
    int plain = samplers.get(SamplerDesc.TRILINEAR_REPEAT);
    int biased = samplers.get(SamplerDesc.withLodBias(SamplerDesc.TRILINEAR_REPEAT, -1f));
    assertNotEquals(plain, biased);
    MemorySegment out = ARENA.allocate(4);
    glGetSamplerParameterfv(biased, GL_TEXTURE_LOD_BIAS, out);
    assertEquals(-1f, out.get(ValueLayout.JAVA_FLOAT, 0));
}
```

−1 is two steps of −0.5: field value `1110`, and `lodBias` returns $-2 \cdot 0.5 = -1$. A negative
bias sharpens by choosing a more detailed (lower-numbered) mip level (§6.3).
</details>

**8. HelloTriangle on the resource layer (§17, §23).** Rewrite `HelloTriangle.init`, `render` and
`shutdown` with a `GpuDevice`: `buffers().create`, `state().useProgram`,
`state().bindVertexArray`, `state().viewport`. Count `state().issuedCalls()` over two frames and
explain the numbers.

<details><summary>Hint</summary>

`GpuDevice` needs the `GLCapabilities` that `GLLoader.init` returns. The clear, the uniforms and
the draw still reach GL every frame, but they bypass the cache, which counts only the calls it
makes itself. Remember what the `StateCache` constructor does, and to `close()` the device.
</details>

<details><summary>Solution</summary>

```java
private GpuDevice device;
private int vertexBuffer; // a GpuBuffers handle

@Override
public void init(MainContext ctx) {
    GLCapabilities caps = GLLoader.init(ctx.window()::glProcAddress);
    device = new GpuDevice(caps);
    program = GLShaders.program(VERTEX, FRAGMENT);
    float[] vertices = { /* as before */ };
    vertexBuffer =
            device.buffers()
                    .create(
                            arena.allocateFrom(ValueLayout.JAVA_FLOAT, vertices),
                            0,
                            "triangle vertices");
    MemorySegment id = arena.allocate(ValueLayout.JAVA_INT);
    glCreateVertexArrays(1, id);
    vao = id.get(ValueLayout.JAVA_INT, 0);
    glVertexArrayVertexBuffer(vao, 0, device.buffers().glName(vertexBuffer), 0, 5 * 4);
    // ... the four attribute calls as before
    start = System.nanoTime();
}

@Override
public void render(MainContext ctx, int index, boolean fresh) {
    StateCache state = device.state();
    int w = ctx.window().pixelWidth();
    int h = ctx.window().pixelHeight();
    state.viewport(0, 0, w, h);
    glClearNamedFramebufferfv(0, GL_COLOR, 0, clearColor);
    state.useProgram(program);
    glProgramUniform1f(program, 0, (System.nanoTime() - start) * 1e-9f);
    glProgramUniform1f(program, 1, (float) w / Math.max(1, h));
    state.bindVertexArray(vao);
    glDrawArrays(GL_TRIANGLES, 0, 3);
}

@Override
public void shutdown(MainContext ctx) {
    MemorySegment id = arena.allocate(ValueLayout.JAVA_INT);
    id.set(ValueLayout.JAVA_INT, 0, vao);
    glDeleteVertexArrays(1, id);
    glDeleteProgram(program);
    device.close(); // deletes the vertex buffer and frees the scratch
}
```

The counts: creating the device already issues 9 calls, because the `StateCache` constructor
calls `invalidate`, which applies `GL_DEFAULT` with every switch (§23.1). Frame 1 adds 3
(viewport, program, vertex array). Frame 2 adds 0: the cache already holds all three, so nothing
is re-sent, unless the window was resized between the frames (then 1, the viewport).
</details>

**9. Clip, divide, viewport (§2.6–§2.7).** Two vertices leave the vertex shader with
$A = (0.3,\ -0.2,\ 0.1,\ 0.5)$ and $B = (-0.6,\ 0.3,\ 0.1,\ 0.5)$, with Jeddy's clip control. Which
is inside the view volume? Where does the inside one land in a 1600 × 900 viewport at the origin,
with which depth, and, with Jeddy's projection ($z_c = n$), how far from the camera is it in units
of the near distance $n$?

<details><summary>Hint</summary>

Test $-w \le x \le w$, $-w \le y \le w$, $0 \le z \le w$ before dividing. For the distance,
remember $w_c$ is the distance and $z_c = n$.
</details>

<details><summary>Solution</summary>

$A$: $|0.3| \le 0.5$, $|-0.2| \le 0.5$, $0 \le 0.1 \le 0.5$: inside. $B$: $x = -0.6 < -0.5$, so it
is outside the left plane (its signed distance $w + x = -0.1$ is negative). $A$'s NDC:
$(0.6,\ -0.4,\ 0.2)$. Window: $x_w = 1.6 \cdot 800 = 1280$, $y_w = 0.6 \cdot 450 = 270$, depth 0.2.
Since $z_c = n = 0.1$ and $w_c = 0.5$, the vertex is at distance $0.5 = 5n$; and indeed the
reversed-Z depth is $n / d = 1/5 = 0.2$.
</details>

**10. Clip an edge (§2.6).** With $n = 0.5$, an edge goes from $A = (1,\ 0,\ 0.5,\ 4)$ (4 units in
front of the camera) to $B = (-1,\ 0,\ 0.5,\ -2)$ (2 units behind). The vertex color is 0 at $A$
and 1 at $B$. Find the new vertex the near plane creates, its NDC $x$, its depth and its color.

<details><summary>Hint</summary>

The near plane is $z \le w$; use $d = w - z$ at both ends and $t = d_A / (d_A - d_B)$.
</details>

<details><summary>Solution</summary>

$d_A = 4 - 0.5 = 3.5$, $d_B = -2 - 0.5 = -2.5$, $t = 3.5 / 6 \approx 0.5833$. The new vertex:
$x = 1 + 0.5833 \cdot (-2) \approx -0.1667$, $z = 0.5$, $w = 4 + 0.5833 \cdot (-6) = 0.5$: on the
near plane ($w = n$). NDC $x = -0.1667 / 0.5 \approx -0.333$, depth $0.5 / 0.5 = 1$ (the near value
in reversed-Z), color $0 + 0.5833 \cdot 1 \approx 0.583$. Clipping interpolates every output
linearly in clip space, at the same $t$.
</details>

**11. Perspective-correct interpolation (§2.10).** An edge has $w_0 = 2$, $u_0 = 0$ and $w_1 = 6$,
$u_1 = 1$. What is $u$ at the middle of the edge on screen ($\lambda = 0.5$), and at which screen
weight $\lambda$ does the middle of the edge in 3D ($u = 0.5$) appear?

<details><summary>Hint</summary>

Blend $u/w$ and $1/w$ linearly, then divide. For the second part, set the formula equal to 0.5
and solve for $\lambda$.
</details>

<details><summary>Solution</summary>

At $\lambda = 0.5$: $u = \dfrac{0.5 \cdot 0/2 + 0.5 \cdot 1/6}{0.5/2 + 0.5/6} = \dfrac{1/12}{4/12}
= 0.25$ (and $w = 3$ there). For $u = 0.5$:
$\dfrac{\lambda/6}{(1-\lambda)/2 + \lambda/6} = 0.5$. Multiply out:
$\lambda/6 = (1-\lambda)/4 + \lambda/12$; times 12: $2\lambda = 3 - 3\lambda + \lambda$, so
$4\lambda = 3$ and $\lambda = 0.75$. The middle of the edge in 3D appears three quarters of the way
along it on screen: the near half fills 75 % of the screen length.
</details>

**12. Count the covered pixels (§2.9–§2.10).** A triangle has window vertices $a = (0, 0)$,
$b = (6, 0)$, $c = (0, 4)$. Which pixel centers does it cover, and what are the barycentric
coordinates of the center $(1.5, 1.5)$?

<details><summary>Hint</summary>

Inside means $x \ge 0$, $y \ge 0$ and below the long edge, $x/6 + y/4 \le 1$, that is
$2x + 3y \le 12$. Check whether any center lies exactly on an edge. For the weights, use the edge
functions and $E_{ab}(c) = 24$.
</details>

<details><summary>Solution</summary>

Centers have coordinates $i + 0.5$. Row $y = 0.5$: $2x \le 10.5$, so $x = 0.5 \dots 4.5$, 5 centers.
Row $y = 1.5$: $2x \le 7.5$, $x \le 3.75$, 4 centers. Row $y = 2.5$: $x \le 2.25$, 2 centers. Row
$y = 3.5$: $x \le 0.75$, 1 center. Total 12, the same as the area $\tfrac12 \cdot 6 \cdot 4 = 12$.
No center lies on the long edge: $2(i + 0.5) + 3(j + 0.5) = 2i + 3j + 2.5$ is never the whole
number 12, so no tie rule is needed.

For $p = (1.5, 1.5)$: $E_{bc}(p) = (0-6)(1.5-0) - (4-0)(1.5-6) = -9 + 18 = 9$,
$E_{ca}(p) = (0-0)(1.5-4) - (0-4)(1.5-0) = 6$, $E_{ab}(p) = 6 \cdot 1.5 - 0 = 9$. So
$\lambda = (9/24,\ 6/24,\ 9/24) = (0.375,\ 0.25,\ 0.375)$, which add up to 1, and
$0.375 \cdot (0,0) + 0.25 \cdot (6,0) + 0.375 \cdot (0,4) = (1.5,\ 1.5)$.
</details>

**13. Why premultiplied alpha (§2.13).** A texture has an opaque red texel $(1, 0, 0)$ with
$\alpha = 1$ next to a fully transparent texel whose color happens to be green, $(0, 1, 0)$ with
$\alpha = 0$. Bilinear filtering samples exactly halfway. Compute the result drawn over black with
straight alpha blending (`SRC_ALPHA, ONE_MINUS_SRC_ALPHA`), and with the texture stored
premultiplied and blended with `ONE, ONE_MINUS_SRC_ALPHA`.

<details><summary>Hint</summary>

Filtering averages every channel, alpha included. Premultiplied means each color is stored already
multiplied by its alpha.
</details>

<details><summary>Solution</summary>

Straight: the filtered texel is $(0.5, 0.5, 0)$ with $\alpha = 0.5$. Over black:
$(0.5, 0.5, 0) \cdot 0.5 + (0,0,0) \cdot 0.5 = (0.25,\ 0.25,\ 0)$, a muddy olive: the invisible
texel's green leaked into the edge. Premultiplied: the transparent texel is stored as
$(0, 0, 0)$ with $\alpha = 0$ (green times 0), so the filtered texel is $(0.5, 0, 0)$ with
$\alpha = 0.5$, and $(0.5, 0, 0) + (0,0,0) \cdot 0.5 = (0.5,\ 0,\ 0)$: half-covered red, as it
should be. This is why the UI and the particles, whose textures are filtered, use premultiplied
blending.
</details>

**14. Mip levels and λ (§6.2–§6.4).** A render target is 2048 × 512 `RGBA16F`. How many mip levels
does a full chain have, what are the last three level sizes, and how many bytes does the chain
take? A surface samples it with $\partial u/\partial x = 6$, $\partial v/\partial x = 0$,
$\partial u/\partial y = 0$, $\partial v/\partial y = 1.5$ texels per pixel: what is $\lambda$ with
trilinear filtering, and roughly which level would 4× anisotropic filtering use?

<details><summary>Hint</summary>

$L = \lfloor \log_2 \max(W, H) \rfloor + 1$; `RGBA16F` is 8 bytes per texel. Anisotropic filtering
takes $N$ samples along the long axis and chooses the level for $\rho_{\max} / N$.
</details>

<details><summary>Solution</summary>

$L = \lfloor \log_2 2048 \rfloor + 1 = 12$. The last three levels are $\max(1, 2048 \gg 9) \times
\max(1, 512 \gg 9) = 4 \times 1$, then $2 \times 1$, then $1 \times 1$ (the height stops halving at
1). Level 0 is $2048 \cdot 512 \cdot 8 = 8{,}388{,}608$ bytes; the whole chain is 11,184,824 bytes,
almost exactly $\tfrac43$ of level 0 (the $\tfrac43$ bound is reached only when both sides keep
halving). $\rho = \max(6, 1.5) = 6$, so $\lambda = \log_2 6 \approx 2.585$: trilinear filtering
blends level 2 (41.5 %) and level 3 (58.5 %), blurring the $v$ direction, which only needed level
0.585. With 4× anisotropy ($6 / 1.5 = 4$ fits exactly), the unit takes 4 samples along $u$ and uses
$\lambda = \log_2 (6/4) \approx 0.585$: sharp in both directions.
</details>

**15. Block-compressed sizes (§6.7).** How many bytes does a 4096 × 4096 BC5 normal map take with
its full mip chain? Compare with uncompressed `RG8`.

<details><summary>Hint</summary>

BC5 is 16 bytes per 4 × 4 block. Count blocks per level with $\lceil w/4 \rceil \cdot
\lceil h/4 \rceil$; the 2 × 2 and 1 × 1 levels are one block each.
</details>

<details><summary>Solution</summary>

Level 0 is $1024 \cdot 1024$ blocks $\cdot\ 16 = 16{,}777{,}216$ bytes (16 MiB). The 13 levels
(4096 down to 1) add up to 22,369,648 bytes, about 21.3 MiB. `RG8` is 2 bytes per texel:
$4096^2 \cdot 2 = 33{,}554{,}432$ bytes (32 MiB) for level 0 alone, about 42.7 MiB with mips. BC5
is half the size of `RG8` (8 bits per texel against 16), and a quarter of `RGBA8`.
</details>

**16. The `vec3` trap (§7.4).** Give the std430 offsets and the array stride of
`struct Sphere { vec3 center; float radius; }; Sphere spheres[];`, of `vec3 points[];` and of
`struct Pair { float a; vec3 b; }; Pair pairs[];`. At which byte does Java write `points[5]`?

<details><summary>Hint</summary>

A `vec3` aligns to 16 in both layouts; a struct's size rounds up to its alignment, which in std430
is its largest member's.
</details>

<details><summary>Solution</summary>

`Sphere`: `center` at 0 (12 bytes), `radius` at 12; size 16, alignment 16, stride 16.
`points[]`: element alignment 16, size 12 rounded up to 16: stride 16, so `points[5]` starts at
byte 80, not 60. `Pair`: `a` at 0, `b` at 16 (4 rounds up to 16), ending at 28; the struct's
alignment is 16 (from the `vec3`), so its size rounds up to 32: stride 32, with 16 bytes of padding
per element. Reordering the members as `vec3 b; float a;` brings it back to 16.
</details>

**17. Calling GL too early (§11).** Predict what happens if a program calls `glFinish()` before
`GLLoader.init`, catches what is thrown, then calls `GLLoader.init` successfully and calls
`glFinish()` again. Why, and why does `glFlush()` still work afterwards?

<details><summary>Hint</summary>

The handle is created in the holder class's static initializer. What does the JVM do with a class
whose static initializer threw?
</details>

<details><summary>Solution</summary>

The first call initializes the holder `GL46$glFinish`; its initializer calls `GLLinker.link`,
whose `address` throws `IllegalStateException` ("GL is not loaded"). The JVM wraps it in an
`ExceptionInInitializerError` and marks the class as failed. Every later use of the class, even
after the resolver is installed, throws `NoClassDefFoundError: Could not initialize class
dev.jeddy.gl.ffi.GL46$glFinish`, for the rest of the JVM's life. `glFlush` has its own holder,
never touched before `init`, so it links normally. (Checked for this chapter with a scratch
program against the generated class: the first call printed `ExceptionInInitializerError`, every
later one `NoClassDefFoundError`.) The lesson: `GLLoader.init` comes first, before anything
touches `GL46`, which is exactly what `HelloTriangle.init` and `WorldRenderer.init` do.
</details>

**18. A vendor string (§12).** Until commit ab48d28 the AMD test in `GLCapabilities.classify` was
`v.contains("ati") || v.contains("amd")`, and it ran before the Intel test. Which vendor strings did
it misclassify, and why? Why does the current code not, which test checks it, and what does it
still miss?

<details><summary>Hint</summary>

Look for the letters `ati` inside an ordinary English word that appears in company names. Then run
the strings through the current order of checks, and read `GLCapabilitiesTest`.
</details>

<details><summary>Solution</summary>

`"Intel Corporation"` lower-cases to `intel corporation`, and *corpor**ati**on* contains `ati`, so
the old AMD test, which ran before the Intel test, returned `AMD`. `"Microsoft Corporation"`
(Windows' GL 1.1 software renderer, `GDI Generic`) was called AMD for the same reason.
`"NVIDIA Corporation"` escaped only because the NVIDIA test came first.

The current code tests `intel` before AMD, so "Intel Corporation" is `INTEL`, and it matches the
old ATI name as a word: the vendor must be exactly `ati` or start with `ati ` (as "ATI Technologies
Inc." does), or contain `amd`. "microsoft corporation" passes none of the tests and is `OTHER`.
`GLCapabilitiesTest.vendorsAreClassifiedByWordNotBySubstring` (no GPU needed) asserts the cases:
"Intel" and "Intel Corporation" are `INTEL`; "Microsoft Corporation" with renderer "GDI Generic" is
`OTHER`; "ATI Technologies Inc." and "AMD" are `AMD`; "NVIDIA Corporation" is `NVIDIA`; vendor
"AMD" with a version string containing "Mesa" is `MESA`; renderer "llvmpipe ..." is `SOFTWARE`.

What it still misses: a vendor string spelled out in full, `"Advanced Micro Devices, Inc."`,
contains neither `amd` nor a leading `ati`, so it falls through to `OTHER`. Adding
`v.contains("advanced micro devices")` to the AMD test (and a line to the test) would cover it.
</details>

**19. Critical or not (§5.3, §9).** For each command, say whether it is on the critical allow-list
and why it may or may not be: `glBindTextureUnit`, `glClientWaitSync`, `glNamedBufferSubData`,
`glDrawArrays`, `glProgramUniformMatrix4fv`, `glMapNamedBufferRange`, `glDepthFunc`, `glFenceSync`.

<details><summary>Hint</summary>

Critical calls must be very short, never block and never call back into Java. Then check
`gl.properties`.
</details>

<details><summary>Solution</summary>

On the list: `glBindTextureUnit` (a bind), `glProgramUniformMatrix4fv` (a uniform setter; with heap
access it can even take a `float[]` directly) and `glDepthFunc` (a state setter). Not on the list:
`glClientWaitSync` (it waits for the GPU, by design), `glNamedBufferSubData` (an upload, which can
stall on a buffer in use and copies data), `glDrawArrays` (a draw; the driver may block when its
queue is full, and in debug mode any call may call back), `glMapNamedBufferRange` (mapping can wait
and allocate) and `glFenceSync` (sync objects are kept off the list with the other sync calls). The
allow-list holds only commands that cannot take long, because a critical call delays every other
thread's safepoint while it runs.
</details>

**20. An empty ring that says "full" (§17.3).** An `UploadRing` of 4,096 bytes is completely idle:
every frame has been reclaimed, so `head == tail == 1768`. The next frame asks for 3,000 bytes at
alignment 16. Follow `reserve`. Will the request ever succeed if every frame asks for the same?
What would fix it?

<details><summary>Hint</summary>

Compute `start`, the physical offset, the skip, and `start + bytes - head`. Then notice which
fields change when `reserve` returns −1.
</details>

<details><summary>Solution</summary>

`start` $= (1768 + 15) \mathbin{\&} {-16} = 1776$; physical 1,776; $1776 + 3000 = 4776 > 4096$, so
`start` skips to 4,096. Then $4096 + 3000 - 1768 = 5328 > 4096$: refused, −1. Nothing changed
(`tail` moves only on success), so the next frame computes the same and is refused again, forever,
although all 4,096 bytes are free. In general, an idle ring refuses a request larger than both the
space left after the aligned start before the physical end ($4096 - 1776 = 2320$) and the physical
position of `head` ($1768$): after the skip, $(4096 - 1776) + (1776 - 1768) + \text{bytes} \le 4096$
needs $\text{bytes} \le 1768$. Here that means anything larger than $\max(2320, 1768) = 2320$
bytes. The cause is that the logical position keeps its place even when nothing is
in flight. A fix: when `head == tail`, move both to the next multiple of the capacity (physical
offset 0) before reserving; then any request up to the capacity fits. With the engine's 64 MiB ring
and its uploads, which are meshes far smaller than half of it, the case has not occurred; this
exercise was found while writing the chapter, and the code is unchanged.
</details>

## 33. Glossary

- **Allow-list (critical):** the commands in `gl.properties` linked with
  `Linker.Option.critical(true)`: state setters, binds, uniform setters (§9).
- **Anisotropic filtering:** several samples along the long axis of a stretched pixel footprint
  (§6.4).
- **Array texture, layer:** one texture holding $N$ same-sized images; one of them (§6.1).
- **Barycentric coordinates:** three weights that blend a triangle's vertices to reach a point
  (§2.10).
- **Bilinear, trilinear filtering:** blending the 4 nearest texels; and blending two mip levels
  as well (§6.4).
- **Binding point, binding slot:** a numbered place a buffer range, texture or sampler is attached
  for shaders to read; a vertex array's buffer-with-stride entry (§7.5, §18.1).
- **Block compression (BCn):** fixed-size encodings of 4 × 4-texel blocks the GPU decodes as it
  reads (§6.7).
- **Clip space, clipping:** the 4D coordinates a vertex shader writes; cutting triangles to the
  view volume (§2.4, §2.6).
- **Command processor:** the GPU unit that reads the command stream in order (§2.2).
- **Compute shader, work group, dispatch:** a shader with no fixed stages; a group of invocations
  that can share memory; the command that starts a grid of groups (§2.15).
- **Context, current:** all of GL's state and objects; bound to one thread at a time (§3.1, §4.1).
- **Core profile, forward-compatible:** GL without the fixed-function pipeline; without deprecated
  features either (§3.2, §13).
- **Critical downcall:** a native call without the thread-state transitions; faster, but it
  blocks safepoints (§5.3).
- **Depth test, depth function, reversed-Z:** keeping the nearest fragment; the comparison used;
  near = 1 and far = 0 with `GL_GEQUAL` (§2.12).
- **Derivative (`dFdx`, `dFdy`):** the difference of a value between neighbouring pixels of a quad
  (§2.11).
- **Direct state access (DSA):** GL functions that take the object's name instead of acting on a
  bound object (§3.3).
- **Downcall, upcall, upcall stub:** Java calling C; C calling Java; the native function pointer
  that makes an upcall possible (§5.2, §16.1).
- **Draw buffers:** the mapping from fragment outputs to framebuffer attachments (§21).
- **Early depth test, hierarchical depth:** testing depth before the fragment shader; rejecting
  whole tiles at once (§2.12).
- **Edge function:** a signed-area test telling which side of an edge a point is on (§2.9).
- **Face culling, winding, signed area:** dropping triangles that face away; the order of vertices
  on screen; its sign (§2.8).
- **Feature mask, variant:** a 64-bit mask of `#define`s; one compiled program per (source, mask)
  (§8.3).
- **Fragment, fragment shader:** a candidate pixel produced by rasterization; the program that
  colors it (§2.9, §2.11).
- **Framebuffer, attachment, completeness:** the images a draw renders into; one of them; GL's
  check that they work together (§2.14, §21).
- **Guard band:** a coordinate range larger than the screen in which the rasterizer works, so most
  triangles need no geometric clipping (§2.6).
- **Helper invocation, quad:** a fragment-shader run for an uncovered pixel of a 2 × 2 quad, so
  derivatives work (§2.11).
- **Holder class:** a nested class whose only field is a `static final MethodHandle`, linked on
  first use (§5.2, §10.6).
- **Immutable storage:** a buffer or texture whose size, format and levels are fixed at creation
  (§6.8, §17.2).
- **Info log:** the driver's text output for a compile or a link (§8.1).
- **Interleaved, separate streams:** all attributes of a vertex together; one buffer per attribute
  group (§15.4, §18).
- **Loader, resolver:** the code that finds GL command addresses at run time; the function it
  calls (§5.1, §11).
- **Mip level, level of detail (λ):** a halved copy of a texture; the base-2 logarithm of texels
  per pixel that chooses one (§6.2, §6.3).
- **NDC, viewport transform, window coordinates:** clip space divided by $w$; the map from NDC to
  pixels; the result (§2.7).
- **Normalized integer (snorm, unorm):** integers read as fractions in $[-1, 1]$ or $[0, 1]$
  (§6.6, §18.3).
- **Offset overload:** a generated version of a command taking a `long` buffer offset or address
  instead of a `MemorySegment` (§5.4, §10.7).
- **Perspective-correct interpolation:** blending $a/w$ and $1/w$ on screen, then dividing (§2.10).
- **Pipeline state:** the fixed-function switches packed in a `long` (§22).
- **Pixel-unpack buffer:** a buffer bound so that a texture upload reads from it at an offset
  (§19.3).
- **Primitive assembly:** grouping vertices into triangles, lines or points (§2.5).
- **Program, shader, linking:** stages linked together; one stage's compiled code; matching the
  stages and generating machine code (§8.1).
- **Program binary:** a driver-specific blob of a linked program, reloadable on the next run
  (§8.5, §26.1).
- **Provoking vertex, `flat`:** the vertex whose value a non-interpolated output takes (§2.10).
- **Rasterization, pixel center, tie rule:** finding covered pixels; the point tested,
  $(i + 0.5, j + 0.5)$; who owns a center on a shared edge (§2.9).
- **Registry (`gl.xml`):** Khronos's machine-readable list of every GL enum and command (§10.1).
- **ROP (render output unit):** the hardware that blends and writes fragments (§2.13).
- **Safepoint:** a point where a Java thread can stop for the garbage collector (§5.2).
- **Sampler object:** filtering, wrapping and comparison settings bound to a texture unit (§6.8,
  §20).
- **Scissor test:** dropping fragments outside a rectangle (§2.9).
- **sRGB:** the display encoding of color; textures in sRGB formats are decoded to linear when
  sampled (§2.14, §6.6).
- **State cache:** remembered GL state, so unchanged state is not sent again (§23).
- **std140, std430, base alignment, stride:** fixed memory layouts for uniform and storage blocks;
  the alignment a member's offset must have; the distance between array elements (§7.3).
- **Stencil test:** a per-pixel integer test Jeddy does not use (§2.12).
- **Storage buffer (SSBO), uniform buffer (UBO), uniform:** large writable shader memory; small
  read-only blocks fast for uniform access; per-program values (§7.1).
- **Swap, vsync, adaptive vsync, tearing:** showing the back buffer; waiting for the display's
  blank; swapping late frames at once; a visible seam between two frames (§4.3).
- **Tail (of a compressed texture):** the mip levels smaller than one block (§19.4).
- **Texel, texture unit:** a texture's pixel; a numbered slot shaders read textures from (§6.1,
  §6.8).
- **Vertex array object (VAO), attribute format:** the description of where vertex attributes come
  from; one attribute's type, size and offset (§18).
- **Vertex fetch, post-transform cache:** reading attributes from buffers; reusing shaded vertices
  by index (§2.3).

## 34. Further reading

- *LearnOpenGL* by Joey de Vries, the "Getting started" and "Advanced OpenGL" chapters:
  [Hello Triangle](https://learnopengl.com/Getting-started/Hello-Triangle),
  [Shaders](https://learnopengl.com/Getting-started/Shaders),
  [Textures](https://learnopengl.com/Getting-started/Textures),
  [Depth testing](https://learnopengl.com/Advanced-OpenGL/Depth-testing),
  [Blending](https://learnopengl.com/Advanced-OpenGL/Blending),
  [Framebuffers](https://learnopengl.com/Advanced-OpenGL/Framebuffers) and
  [Advanced GLSL](https://learnopengl.com/Advanced-OpenGL/Advanced-GLSL) (uniform blocks and
  std140). LearnOpenGL uses bind-to-edit; translating its examples to DSA is good practice.
- *The OpenGL 4.6 Core Profile Specification*, Khronos:
  [glspec46.core.pdf](https://registry.khronos.org/OpenGL/specs/gl/glspec46.core.pdf). The
  chapters on buffer objects, vertex specification and drawing commands, rasterization and
  per-fragment operations match Part A of this chapter; the section "Standard Uniform Block
  Layout" holds the exact std140 and std430 rules, and "Texture Minification" the level-of-detail
  formulas.
- *The OpenGL Shading Language 4.60*, Khronos:
  [GLSLangSpec.4.60.pdf](https://registry.khronos.org/OpenGL/specs/gl/GLSLangSpec.4.60.pdf), for
  qualifiers (`layout`, `flat`, `binding`), `#line`, and built-in variables such as
  `gl_BaseInstance`.
- The Khronos OpenGL wiki pages on
  [Interface Block (GLSL)](https://www.khronos.org/opengl/wiki/Interface_Block_(GLSL)),
  [Buffer Object Streaming](https://www.khronos.org/opengl/wiki/Buffer_Object_Streaming) and
  [Debug Output](https://www.khronos.org/opengl/wiki/Debug_Output).
- [JEP 454: Foreign Function & Memory API](https://openjdk.org/jeps/454).
- *Real-Time Rendering*, 4th edition (Akenine-Möller, Haines, Hoffman, Pesce, Iwanicki, Hillaire),
  chapter 2 (the graphics rendering pipeline), chapter 6 (texturing) and chapter 23 (graphics
  hardware).
- Fabian Giesen, *A trip through the Graphics Pipeline 2011*, a series of blog posts on what the
  hardware does at each stage, from the command processor to the ROPs.

---

*Code this chapter builds or quotes: `bindings/gl/gl.properties`, `GlGenerator`, `GlBindings.kt`,
`jeddy.gl-bindings`, the generated `GL46`, `GLLinker`, `GLLoader`, `GLCapabilities`, `GLShaders`,
`GLDebug`, `Window`, `HelloTriangle`, `JeddyApp`, `GpuDevice`, `UploadRing`, `MeshRegistry`,
`GpuTextures`, `GpuFormats`, `SamplerDesc`, `GpuSamplers`, `GpuFramebuffers`, `PipelineState`,
`StateCache`, `ShaderSources`, `ShaderPreprocessor`, `ShaderFeatures`, `ProgramCache`,
`ProgramBinaryCache`, `EngineShaders`, `WorldRenderer.init`, the shaders `engine/fullscreen.vert`,
`engine/vertex.glsl`, `engine/skinning.glsl`, `engine/instance.glsl`, `engine/material.glsl`,
`engine/frame.glsl`, `pbr/pbr.vert`, and the tests `GLTest`, `GLCapabilitiesTest`, `GpuDeviceTest`, `RingsTest`,
`ShaderPreprocessorTest`, `ShaderFeaturesTest`, `ProgramCacheTest`, with `GlBench`. Decisions: D2,
D6, D8, D12, D21, D23, D24, D27, D29, D34, D41, D42, D50, D64, D69, D73, D80, D101, D103 in
[DESIGN §17](../DESIGN.md), and DESIGN §4.4, §5, §9.1–§9.3, §9.7. Related: lessons
[0](lessons/00-foundations.md) (bits, floats, memory, the GPU, FFM),
[1](lessons/01-buffers-in-flight.md) (`GpuBuffers`, `FrameRing`, fences and barriers) and
[3](lessons/03-data-layout.md) (handles); chapters [04](04-cameras-and-projection.md) (clip space
and reversed-Z), [06](06-renderer-architecture.md) (the renderer built on this layer),
[07](07-lighting-and-shadows.md), [08](08-effects-and-post.md) and
[12](12-java-performance.md) (what the JIT does with the holders).*

[← 04 Cameras and projection](04-cameras-and-projection.md) · [Index](README.md) · [06 The renderer →](06-renderer-architecture.md)
