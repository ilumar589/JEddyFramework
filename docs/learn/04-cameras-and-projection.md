# 04 — Cameras, projection, depth and culling

*A mesh vertex starts as three floats in a file and ends as a pixel on your screen with a depth
value next to it. This chapter follows that journey and builds, from empty files, every piece of
the engine that moves it: the view matrix and the two projections, the isometric camera that
orbits the party, the GPU state that makes the depth buffer precise (reversed-Z, with numbers for
every format), the frustum planes that let the engine skip what the camera cannot see, the
controller that turns input into smooth camera motion, picking from the mouse back into the world,
and the choice of a level of detail from its error in pixels. It ends with the tests that prove
each piece and the numbers measured on them.*

**Before you start.** You need [Lesson 0](lessons/00-foundations.md): the notation of §2 (powers,
scientific notation, intervals), how a `float` is stored (§7, the whole depth-precision section
rests on it), the zero-allocation rule (§8.2), threads (§9.4), what a GPU, a shader, the rasterizer
and the driver are (§10) and frames and ticks (§11). From the chapters before this one you need
[02 Math foundations](02-math-foundations.md) (vectors, length, the dot and cross products,
matrices as maps, matrix × vector, homogeneous coordinates, the column-major `Mat4` and the
row-major 3×4 affine transform) and [03 Rotations and transforms](03-rotations-and-transforms.md)
(rotations with sine and cosine, interpolating transforms between ticks). §2 below recaps the few
facts this chapter leans on hardest, so you can check you have them. [Lesson 1](lessons/01-buffers-in-flight.md)
explains the frame ring the per-frame camera constants are written into, and
[Lesson 3](lessons/03-data-layout.md) the struct-of-arrays layout the culling kernels read.

**What you will build**

| Piece | File | What it does |
|---|---|---|
| Matrix kernels | [`Mat4Ops`](../../modules/math/src/main/java/dev/jeddy/math/Mat4Ops.java), the record [`Mat4f`](../../modules/math/src/main/java/dev/jeddy/math/Mat4f.java) | multiply, project, invert; build the look-at view matrix and the reversed-Z perspective and orthographic projections |
| The camera | [`IsoCamera`](../../modules/math/src/main/java/dev/jeddy/math/IsoCamera.java) | orbits a target at a pitch, yaw and distance; writes every matrix a frame needs; casts rays for picking |
| Reversed-Z on the GPU | [`GpuDevice.useReversedZ`](../../modules/render/src/main/java/dev/jeddy/render/gpu/GpuDevice.java), [`PipelineState`](../../modules/render/src/main/java/dev/jeddy/render/gpu/PipelineState.java), [`StateCache`](../../modules/render/src/main/java/dev/jeddy/render/gpu/StateCache.java), [`FrameGraph`](../../modules/render/src/main/java/dev/jeddy/render/graph/FrameGraph.java) | clip control, a float depth buffer cleared to 0, a "greater or equal" depth test |
| Culling kernels | [`CullOps`](../../modules/math/src/main/java/dev/jeddy/math/CullOps.java), [`Intersect`](../../modules/math/src/main/java/dev/jeddy/math/Intersect.java) | six planes out of a matrix; sphere and box tests; a branch-free batch kernel; ray–plane intersection |
| Renderer culling | [`Culling`](../../modules/render/src/main/java/dev/jeddy/render/draw/Culling.java), [`SceneRenderer`](../../modules/render/src/main/java/dev/jeddy/render/SceneRenderer.java), [`cull/static_cull.comp`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/cull/static_cull.comp) | culls a render packet on the job workers, deterministically; the GPU path for static instances |
| Camera behaviour | [`IsoCameraController`](../../modules/render/src/main/java/dev/jeddy/render/camera/IsoCameraController.java) | view-relative and edge panning, drag panning, frame-rate independent smoothing, zoom, follow, level bounds |
| LOD selection | [`DrawBuilder`](../../modules/render/src/main/java/dev/jeddy/render/draw/DrawBuilder.java) | picks the coarsest level of detail whose error stays under a pixel |
| Shaders | [`engine/frame.glsl`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/engine/frame.glsl), [`engine/instance.glsl`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/engine/instance.glsl), [`pbr/pbr.vert`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/pbr/pbr.vert), [`engine/fullscreen.vert`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/engine/fullscreen.vert), [`pbr/sky.frag`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/pbr/sky.frag) | apply the matrices per vertex; rebuild positions from depth |
| Tests | [`MatrixAndQuatTest`](../../modules/math/src/test/java/dev/jeddy/math/MatrixAndQuatTest.java), [`IsoCameraControllerTest`](../../modules/render/src/test/java/dev/jeddy/render/camera/IsoCameraControllerTest.java), [`DrawBuilderTest`](../../modules/render/src/test/java/dev/jeddy/render/draw/DrawBuilderTest.java), [`StaticSceneTest`](../../modules/render/src/test/java/dev/jeddy/render/cull/StaticSceneTest.java), [`GpuDeviceTest`](../../modules/render/src/test/java/dev/jeddy/render/gpu/GpuDeviceTest.java) | prove the numbers derived here |

DESIGN [§8.2](../DESIGN.md#82-conventions) (math conventions) and
[§9.4](../DESIGN.md#94-isometric-camera) (isometric camera) are the design references.

**Contents**

- Part A — The ideas
  1. [The journey of a vertex](#1-the-journey-of-a-vertex)
  2. [The tools: angles, vectors and bases](#2-the-tools-angles-vectors-and-bases)
  3. [The view matrix](#3-the-view-matrix)
  4. [Perspective projection](#4-perspective-projection)
  5. [What the GPU does with clip coordinates](#5-what-the-gpu-does-with-clip-coordinates)
  6. [Orthographic projection](#6-orthographic-projection)
  7. [Depth precision, with numbers](#7-depth-precision-with-numbers)
  8. [Reversed-Z in practice](#8-reversed-z-in-practice)
  9. [The isometric camera](#9-the-isometric-camera)
  10. [The frustum and its planes](#10-the-frustum-and-its-planes)
  11. [Going backwards: picking](#11-going-backwards-picking)
  12. [Level of detail by projected error](#12-level-of-detail-by-projected-error)
  13. [Interpolation between ticks](#13-interpolation-between-ticks)
- Part B — Building it
  14. [Step 1: the matrix kernels and the multiply](#14-step-1-the-matrix-kernels-and-the-multiply)
  15. [Step 2: lookAt](#15-step-2-lookat)
  16. [Step 3: the two projections](#16-step-3-the-two-projections)
  17. [Step 4: project and invert](#17-step-4-project-and-invert)
  18. [Step 5: the camera, IsoCamera](#18-step-5-the-camera-isocamera)
  19. [Step 6: switching the GPU to reversed-Z](#19-step-6-switching-the-gpu-to-reversed-z)
  20. [Step 7: the culling kernels, CullOps](#20-step-7-the-culling-kernels-cullops)
  21. [Step 8: culling a render packet, Culling](#21-step-8-culling-a-render-packet-culling)
  22. [Step 9: the controller, IsoCameraController](#22-step-9-the-controller-isocameracontroller)
  23. [Step 10: choosing the level of detail](#23-step-10-choosing-the-level-of-detail)
- Part C — Using it and proving it
  24. [One frame, end to end](#24-one-frame-end-to-end)
  25. [GPU culling of static instances](#25-gpu-culling-of-static-instances)
  26. [Picking and selection in the demo](#26-picking-and-selection-in-the-demo)
  27. [The camera in the demo](#27-the-camera-in-the-demo)
  28. [The tests, walked through](#28-the-tests-walked-through)
  29. [Things to run, and the numbers measured](#29-things-to-run-and-the-numbers-measured)
- [Exercises](#exercises)
- [Glossary](#glossary)
- [Further reading](#further-reading)

---

# Part A — The ideas

## 1. The journey of a vertex

### 1.1 Six spaces

A **coordinate space** is a choice of origin and axes against which three numbers mean a position.
"(2, 0, 0)" means nothing until you say *relative to what*. Every point the renderer draws passes
through six such spaces, and each arrow below is one transformation:

```
 model space ──(instance 3×4, vertex shader)──▶ world space ──(view matrix V)──▶ view space
                                                                                     │
                                                                       (projection P, │
                                                                        vertex shader)│
                                                                                     ▼
 window space ◀──(viewport transform)── NDC ◀──(clipping, then divide by w)── clip space
 pixels + depth      fixed function                 fixed function
```

| Space | What the numbers mean | Who gets you there in Jeddy |
|---|---|---|
| Model | Vertex positions as authored, relative to the mesh's own origin | The `.jmesh` position stream |
| World | One shared frame for the whole level: right-handed, +Y up, metres | Vertex shader: `jeddyTransformPoint` with the instance's 3×4 rows (written by `DrawBuilder`, interpolated between ticks, or by the GPU culling pass for static instances) |
| View | The camera sits at the origin, +X is screen right, +Y is screen up, and it looks down **−Z** | The view matrix $V$, built in Java by `Mat4Ops.lookAt` (§3) |
| Clip | Four numbers $(x_c, y_c, z_c, w_c)$, before the divide | The projection matrix $P$, built in Java (§4, §6). Java multiplies $`P\,V`$ once per frame; the shader applies the product |
| NDC | **Normalized device coordinates**: $x_n, y_n \in [-1, 1]$, $z_n \in [0, 1]$ | GPU fixed function: clipping, then division by $w_c$ (§5) |
| Window | Pixels from the lower-left corner, plus the depth value written to the depth buffer | GPU fixed function: the viewport transform (`glViewport`, set per pass by the frame graph) |

"Fixed function" means hardware that is not programmable: you configure it with GL state, but no
shader of yours runs there. The first two arrows are your code; the last two are the GPU's.

### 1.2 Who does each step

The vertex shader of the lit pass shows the split. It applies the instance transform itself,
keeps the world position (lighting needs it), and then applies view and projection in one matrix
([`pbr/pbr.vert`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/pbr/pbr.vert),
condensed: the skinning and tangent lines are left out):

```glsl
void main() {
    JeddyInstance inst = jeddyInstance();
    // ...
    vec3 position = jeddyPosition;
    // ...
    vWorld = jeddyTransformPoint(inst, position);
    // ...
    gl_Position = jViewProjection * vec4(vWorld, 1.0);
}
```

`gl_Position` is the vertex shader's one required output: the vertex's **clip-space** position.
Everything after that line is the GPU's work (§5). `jViewProjection` is the product $`P\,V`$, read from
the per-frame constants block (§24.4).

The instance transform is three dot products, one per row of the row-major 3×4 matrix
([`engine/instance.glsl`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/engine/instance.glsl)):

```glsl
struct JeddyInstance {
    vec4 row0;
    vec4 row1;
    vec4 row2;
    uvec4 data; // x material, y flags, z entity, w camera fade (float bits)
};
// ...
vec3 jeddyTransformPoint(JeddyInstance i, vec3 p) {
    vec4 v = vec4(p, 1.0);
    return vec3(dot(i.row0, v), dot(i.row1, v), dot(i.row2, v));
}
```

Each `row` holds one row of the affine transform, $(m_{r0}, m_{r1}, m_{r2}, t_r)$: three numbers of
the rotation-and-scale part and one of the translation. Dotting it with $(p_x, p_y, p_z, 1)$ gives
$m_{r0}p_x + m_{r1}p_y + m_{r2}p_z + t_r$, which is row $r$ of "rotate and scale, then translate"
(chapter [02](02-math-foundations.md), on affine transforms). Four `vec4`s are 64 bytes, the
**instance record** that `DrawBuilder` writes into the frame ring
([Lesson 1](lessons/01-buffers-in-flight.md) §14.1).

The depth prepass shader, [`pbr/depth.vert`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/pbr/depth.vert),
does the same but takes the matrix as a plain uniform at location 0, because the same program also
draws shadow casters from each light's point of view, each with its own matrix:

```glsl
layout(location = 0) uniform mat4 uViewProjection;
// ...
    gl_Position = uViewProjection * vec4(world, 1.0);
```

### 1.3 Why the shader applies world and view-projection separately

Many tutorials give each object one **model-view-projection** matrix (MVP), $`P\,V\,M`$, computed on
the CPU, so the vertex shader does a single 4×4 multiply. Jeddy does not. The alternatives:

- **One MVP per instance, built on the CPU.** It changes whenever the camera moves, which is every
  frame, so every instance (static walls included) would need a fresh 4×4 product per frame:
  20,000 instances means 20,000 matrix products on the CPU before anything is drawn. And the shader
  would no longer know the world position, which lighting, shadows, decals and fog of war all need.
- **One 4×4 model matrix per instance, plus the view-projection.** It works, but a 4×4 is 64 bytes,
  the whole instance record, and leaves no room for the material, flags, entity and fade.
- **Chosen: a 3×4 world transform per instance and one view-projection per frame.** The fourth row
  of an affine matrix is always $(0, 0, 0, 1)$, so storing it is waste: three rows are 48 bytes,
  which leaves 16 for the four data words. Instances are stored as 3×4 rows on the CPU too, so world
  transforms are copied into the record, never converted (D21, DESIGN §8.2). The extra cost is one
  more matrix-vector product per vertex, nothing next to the fragment work.

`Mat4Ops.mulAffine` exists for the cases where you do want $`(P\,V) \times M`$ on the CPU (§14).

### 1.4 Conventions: column vectors, column-major storage

Jeddy writes vectors as columns and puts matrices on the left:

```math
\mathbf{p}_{clip} = P\,V\,M\,\mathbf{p}
```

The rightmost matrix applies first: $M$ (model to world), then $V$ (world to view), then $P$ (view
to clip). `Mat4Ops.mul(a, ao, b, bo, out, o)` computes $a \cdot b$, so "$b$ first, then $a$". That is
why `IsoCamera` computes the view-projection as `Mat4Ops.mul(projection, 0, view, 0,
viewProjection, 0)`: view first, then projection.

A `Mat4` is 16 floats stored **column-major**, as OpenGL expects: element (row $r$, column $c$) is at
index `c * 4 + r`. Keep this table in mind; every index in this chapter refers to it:

```
              col 0   col 1   col 2   col 3
   row 0  ->  m[0]    m[4]    m[8]    m[12]
   row 1  ->  m[1]    m[5]    m[9]    m[13]
   row 2  ->  m[2]    m[6]    m[10]   m[14]
   row 3  ->  m[3]    m[7]    m[11]   m[15]
```

So the translation of an affine 4×4 is in `m[12..14]`, and row $r$ of a matrix is
`(m[r], m[4 + r], m[8 + r], m[12 + r])`. The math module's `Mat4f` record names its components
`mCR` (column, then row) for the same reason: `m30` is column 3, row 0, the translation's $x$.
Chapter [02](02-math-foundations.md) explains both storage orders and why the engine keeps two
(D21).

<details><summary>Check: in a column-major array, which index holds row 3, column 2? And what does <code>Mat4Ops.mul(p, 0, v, 0, out, 0)</code> apply first?</summary>

Index `2 * 4 + 3 = 11`. It applies `v` first, then `p`: the result is $p \cdot v$.
</details>

## 2. The tools: angles, vectors and bases

Chapters [02](02-math-foundations.md) and [03](03-rotations-and-transforms.md) teach these in
depth. This section collects the five facts the camera math uses on every page, with the numbers
you will meet again.

### 2.1 Angles, radians, sine, cosine and tangent

An angle can be measured in **degrees** (a full turn is 360) or in **radians** (a full turn is
$2\pi \approx 6.2832$, the length of the unit circle's rim). To convert, multiply by $\pi/180$:
30° is $\pi/6 \approx 0.5236$ rad. Java's `Math.sin`, `Math.cos` and `Math.tan` take radians, and so
does every angle field in the engine (`MathF.toRadians(30)` converts once, at configuration time).

Take a **right triangle** (one angle of 90°) and one of its other angles, $\alpha$. Name the sides
relative to $\alpha$: the **hypotenuse** is the long side opposite the right angle, the
**opposite** side faces $\alpha$, the **adjacent** side touches it. Three ratios depend only on
$\alpha$, not on the triangle's size:

```
                 *
               / |
  hypotenuse /   |  opposite        sin α = opposite / hypotenuse
           /     |                  cos α = adjacent / hypotenuse
         / α     |                  tan α = opposite / adjacent = sin α / cos α
        *--------*
         adjacent
```

The camera uses two consequences. Going a distance $D$ along a direction tilted down by $\theta$ moves
you $D\cos\theta$ horizontally and $D\sin\theta$ vertically. And at distance $s$ in front of you, a
half-angle $\alpha$ of view covers a half-height of $s\tan\alpha$. Numbers you will see again:
$\tan 15° = 2 - \sqrt 3 \approx 0.26795$, so $1/\tan 15° = 2 + \sqrt 3 \approx 3.7321$;
$\sin 55° \approx 0.81915$ and $\cos 55° \approx 0.57358$.

### 2.2 The dot product: "how far along"

For two vectors $\mathbf{a} = (a_x, a_y, a_z)$ and $\mathbf{b}$,

```math
\mathbf{a}\cdot\mathbf{b} = a_x b_x + a_y b_y + a_z b_z = \lVert\mathbf{a}\rVert\,\lVert\mathbf{b}\rVert\cos\theta
```

where $\lVert\mathbf{a}\rVert = \sqrt{\mathbf{a}\cdot\mathbf{a}}$ is the length and $\theta$ the angle
between them. The use that matters most here: if $\mathbf{u}$ has length 1 (a **unit vector**),
then $\mathbf{p}\cdot\mathbf{u}$ is **how far $\mathbf{p}$ reaches along $\mathbf{u}$**: positive in
front, negative behind, zero when perpendicular. A view matrix is three such questions (§3.2); a
plane test is one (§10.1).

### 2.3 The cross product: "perpendicular to both"

```math
\mathbf{a}\times\mathbf{b} = (a_y b_z - a_z b_y,\; a_z b_x - a_x b_z,\; a_x b_y - a_y b_x)
```

The result is perpendicular to both $\mathbf{a}$ and $\mathbf{b}$, its length is
$\lVert\mathbf{a}\rVert\lVert\mathbf{b}\rVert\sin\theta$ (so it is **zero when they are parallel**),
and its direction follows the **right-hand rule**: point the fingers of your right hand along
$\mathbf{a}$, curl them towards $\mathbf{b}$, and the thumb gives $\mathbf{a}\times\mathbf{b}$.
Swapping the order flips the sign: $`\mathbf{b}\times\mathbf{a} = -\,\mathbf{a}\times\mathbf{b}`$.
Check with the axes:
$`(1, 0, 0) \times (0, 1, 0) = (0 \cdot 0 - 0 \cdot 1,\; 0 \cdot 0 - 1 \cdot 0,\; 1 \cdot 1 - 0 \cdot 0) = (0, 0, 1)`$:
$X \times Y = Z$, the definition of a **right-handed** system.

### 2.4 Orthonormal bases: the inverse is the transpose

Three unit vectors that are mutually perpendicular form an **orthonormal basis**. Put them as the
columns of a 3×3 matrix $R$. The product $R^T R$ (the **transpose** $R^T$ has the columns as rows)
has, at row $i$ and column $j$, the dot product of column $i$ with column $j$: 1 when $i = j$ (unit
length), 0 otherwise (perpendicular). So $R^T R = I$, the identity, which means

```math
R^{-1} = R^T
```

Inverting a rotation costs nothing: you read it sideways. This is the fact that makes the view
matrix cheap (§3.2). Chapter [02](02-math-foundations.md) proves it in general, in its section on
inverting transforms.

<details><summary>Check: what is <code>(0, 0, -1) × (0, 1, 0)</code>, and why does it matter for a camera looking down −Z?</summary>

$`(0 \cdot 0 - (-1) \cdot 1,\; (-1) \cdot 0 - 0 \cdot 0,\; 0 \cdot 1 - 0 \cdot 0) = (1, 0, 0)`$. "Forward
× up" is +X, screen right: the look-at construction in §3.3 uses exactly this product to find the
camera's right axis.
</details>

## 3. The view matrix

### 3.1 The camera is just an object

Think of the camera as an object placed in the world, with a position $\mathbf{e}$ (the **eye**) and
three axes: right $\mathbf{r}$, up $\mathbf{u}$, and back $\mathbf{b}$. "Back" because in a
right-handed system with +X right and +Y up, +Z points out of the screen, towards you (§2.3), so the
camera looks along $-\mathbf{b}$. Call the viewing direction **forward**, $\mathbf{f} = -\mathbf{b}$.

The camera's own transform, from camera space to world space, is a rotation whose columns are its
axes, followed by a translation to the eye:

```math
C = \begin{pmatrix} r_x & u_x & -f_x & e_x \\ r_y & u_y & -f_y & e_y \\ r_z & u_z & -f_z & e_z \\ 0 & 0 & 0 & 1 \end{pmatrix}
```

Check it on two points. Camera-space $(1, 0, 0)$, one unit to the camera's right, goes to
$\mathbf{e} + \mathbf{r}$: one unit along the right axis, starting at the eye. Camera-space
$(0, 0, -1)$ goes to $\mathbf{e} + \mathbf{f}$: one unit in front of the eye.

### 3.2 Inverting a rigid transform

The **view matrix** does the opposite: it takes world points into the camera's frame, so
$V = C^{-1}$. $C$ is "rotate by $R$ (the 3×3 with columns $\mathbf{r}, \mathbf{u}, -\mathbf{f}$),
then translate by $\mathbf{e}$": $`C\mathbf{p} = R\,\mathbf{p} + \mathbf{e}`$. Undo the steps in
reverse order: subtract $\mathbf{e}$, then rotate back by $R^{-1} = R^T$ (§2.4):

```math
V\mathbf{p} = R^T(\mathbf{p} - \mathbf{e}) = R^T\mathbf{p} - R^T\mathbf{e}
```

$R^T$ has the axes as **rows**, and $R^T\mathbf{e}$ is the three dot products
$`(\mathbf{r}\cdot\mathbf{e},\; \mathbf{u}\cdot\mathbf{e},\; -\mathbf{f}\cdot\mathbf{e})`$. As a 4×4:

```math
V = \begin{pmatrix} r_x & r_y & r_z & -\mathbf{r}\cdot\mathbf{e} \\ u_x & u_y & u_z & -\mathbf{u}\cdot\mathbf{e} \\ -f_x & -f_y & -f_z & \mathbf{f}\cdot\mathbf{e} \\ 0 & 0 & 0 & 1 \end{pmatrix}
```

Read the rows as the dot-product questions of §2.2, asked about $\mathbf{p} - \mathbf{e}$, the
arrow from the eye to the point: "how far along $\mathbf{r}$?" (view $x$), "how far along
$\mathbf{u}$?" (view $y$), "how far along $-\mathbf{f}$?" (view $z$, negative in front of the camera).
A general 4×4 inverse costs about a hundred multiplications (§17); this one is a transpose and three
dot products.

### 3.3 Building the basis from eye, target and up

A **look-at** camera is given an eye $\mathbf{e}$, a target $\mathbf{t}$ and a rough "up" hint
$\mathbf{w}$ (usually world +Y). It builds the basis in three steps:

1. $\mathbf{f} = \dfrac{\mathbf{t} - \mathbf{e}}{\lVert \mathbf{t} - \mathbf{e} \rVert}$, the unit viewing direction.
2. $\mathbf{r} = \dfrac{\mathbf{f} \times \mathbf{w}}{\lVert \mathbf{f} \times \mathbf{w} \rVert}$. The
   cross product is perpendicular to both, so it is horizontal when $\mathbf{w}$ is vertical, and by
   the right-hand rule "forward × up" points right (§2.4's check). It must be normalized because
   $\mathbf{f}$ and $\mathbf{w}$ are not perpendicular unless the camera looks horizontally: its length
   is $\sin$ of the angle between them.
3. $\mathbf{u} = \mathbf{r} \times \mathbf{f}$, the true up, perpendicular to both. It is already unit
   length: $\mathbf{r} \perp \mathbf{f}$, so $\sin 90° = 1$ and $\lVert\mathbf{u}\rVert = 1 \cdot 1 \cdot 1$.

`Mat4Ops.lookAt` is exactly this, written out on scalars (§15).

### 3.4 A worked example

Put the eye at $\mathbf{e} = (0, 6, 8)$ looking at the origin. The distance is
$\sqrt{6^2 + 8^2} = 10$ and the camera is tilted down by $\arctan(6/8) \approx 36.87°$. This camera is
the chapter's running example.

- $\mathbf{f} = (0, -6, -8)/10 = (0, -0.6, -0.8)$
- $`\mathbf{f} \times (0,1,0) = (f_y \cdot 0 - f_z \cdot 1,\; f_z \cdot 0 - f_x \cdot 0,\; f_x \cdot 1 - f_y \cdot 0) = (0.8, 0, 0)`$, normalized $\mathbf{r} = (1, 0, 0)$
- $`\mathbf{u} = \mathbf{r} \times \mathbf{f} = (0 \cdot (-0.8) - 0 \cdot (-0.6),\; 0 \cdot 0 - 1 \cdot (-0.8),\; 1 \cdot (-0.6) - 0 \cdot 0) = (0, 0.8, -0.6)`$
- translations: $-\mathbf{r}\cdot\mathbf{e} = 0$, $-\mathbf{u}\cdot\mathbf{e} = -(4.8 - 4.8) = 0$, $\mathbf{f}\cdot\mathbf{e} = -3.6 - 6.4 = -10$

```math
V = \begin{pmatrix} 1 & 0 & 0 & 0 \\ 0 & 0.8 & -0.6 & 0 \\ 0 & 0.6 & 0.8 & -10 \\ 0 & 0 & 0 & 1 \end{pmatrix}
```

Check: the target $(0,0,0)$ goes to $(0, 0, -10)$, ten metres straight ahead. The eye goes to
$`(0,\; 0.8 \cdot 6 - 0.6 \cdot 8,\; 0.6 \cdot 6 + 0.8 \cdot 8 - 10) = (0, 0, 0)`$. A point 2 m to the
right of the target, $(2,0,0)$, goes to $(2, 0, -10)$. A point 5 m further away on the ground,
$(0,0,-5)$, goes to $`(0,\; 0.8 \cdot 0 - 0.6 \cdot (-5),\; 0.8 \cdot (-5) - 10) = (0, 3, -14)`$: higher
on the screen and further from the eye. §4.7 projects these two points to pixels. (These are the
values the real `Mat4Ops.lookAt` writes; `IsoCamera` with yaw 0, pitch $\arctan(6/8)$ and distance
10 builds the same matrix, exercise 1.)

### 3.5 When look-at breaks

If $\mathbf{f}$ is parallel to the up hint (a camera looking straight down),
$\mathbf{f} \times \mathbf{w} = \mathbf{0}$, the normalization divides zero by zero and the matrix
fills with NaN (L00 §7.3).
`Mat4Ops.lookAt(0, 10, 0, 0, 0, 0, 0, 1, 0, out, 0)` writes NaN into rows 0 and 1, and everything
drawn with it disappears.

Floating point adds a subtler trap just before that point. `MathF.toRadians(90)` is the float
1.5707964, a hair *more* than $\pi/2$, whose cosine is $-4.37 \times 10^{-8}$, not 0. An `IsoCamera`
with that pitch does not produce NaN: the cross product is tiny but not zero and normalizes fine.
But its sign has flipped: with yaw 0, the right axis comes out as $(-1, 0, 0)$ where a pitch of 89.9°
gives $(1, 0, 0)$ (with the default yaw of 45° the two are $(-0.707, 0, 0.707)$ and
$(0.707, 0, -0.707)$). The picture turns 180° as the pitch crosses 90° (exercise 9).

The isometric camera never gets near either case: its pitch is a fixed tilt well below 90° (55° by
default, 52° in the demo). A top-down camera would need a different up hint, for example the yaw
direction on the ground.

## 4. Perspective projection

### 4.1 Similar triangles

Take a point in view space $(x_v, y_v, z_v)$ in front of the camera, so $`z_v < 0`$, and call its
distance along the view axis $s = -z_v$ (the **view depth**). A pinhole camera projects it onto an
**image plane** at distance 1 along the axis. Draw the side view:

```
        y
        ^                          * (y_v, at depth s)
        |                       .  |
        |                    .     |
        |           y'    .        |  y_v
        |           *  .           |
        |        .  |              |
  eye   *-----------+--------------+------->  -z (view direction)
        0           1              s
```

Two triangles share the angle at the eye: the small one with sides 1 and $y'$, the big one with sides
$s$ and $y_v$. Both have a right angle, so all their angles are equal: they are **similar**, and
similar triangles have proportional sides. So $y'/1 = y_v/s$:

```math
y' = \frac{y_v}{s}
```

Everything in perspective follows from this one division. A point twice as far away lands half as far
from the center.

### 4.2 Field of view and aspect ratio

The vertical **field of view** $\varphi$ (`fovY`) says how much of the image plane is visible: at
distance 1 the visible half-height is $\tan(\varphi/2)$ (§2.1, with the half-angle $\varphi/2$
opposite the half-height). Dividing by it maps the visible range to $[-1, 1]$:

```math
y_n = \frac{y_v}{s \tan(\varphi/2)} = \frac{f\,y_v}{s}, \qquad f = \frac{1}{\tan(\varphi/2)}
```

$f$ is sometimes called the **focal length** in these units. For $\varphi = 30°$,
$f = 1/\tan 15° = 2 + \sqrt 3 \approx 3.7321$.

The screen is wider than tall by the **aspect ratio** $a = \text{width}/\text{height}$ (16/9 for
1920×1080). The visible half-width at distance 1 is $a \tan(\varphi/2)$, so

```math
x_n = \frac{(f/a)\, x_v}{s}
```

With $a = 16/9$: $f/a \approx 2.0993$. A point at the right edge of the screen has $x_n = 1$.

### 4.3 Homogeneous coordinates do the division

A matrix can only produce sums of multiples of $(x_v, y_v, z_v, 1)$; it cannot divide. The trick is to
output four numbers and let the GPU divide the first three by the fourth after the vertex shader.
That fourth number is $w$, and the projection matrix puts $s = -z_v$ into it with the row
$(0, 0, -1, 0)$:

```math
\begin{pmatrix} x_c \\ y_c \\ z_c \\ w_c \end{pmatrix} =
\begin{pmatrix} f/a & 0 & 0 & 0 \\ 0 & f & 0 & 0 \\ 0 & 0 & A & B \\ 0 & 0 & -1 & 0 \end{pmatrix}
\begin{pmatrix} x_v \\ y_v \\ z_v \\ 1 \end{pmatrix},
\qquad x_n = \frac{x_c}{w_c},\; y_n = \frac{y_c}{w_c},\; z_n = \frac{z_c}{w_c}
```

$(x_c, y_c, z_c, w_c)$ are the **clip coordinates**: four-dimensional **homogeneous** coordinates,
where $(x, y, z, w)$ and $(kx, ky, kz, kw)$ name the same 3D point for any $k \ne 0$
(chapter [02](02-math-foundations.md), on homogeneous coordinates). Rows 0, 1 and 3 reproduce §4.2
exactly: $x_c/w_c = (f/a)x_v/s$. The GPU clips in this four-dimensional space first and divides
afterwards; §5.1 explains why the order matters.

### 4.4 Where depth comes from

Row 2, $(0, 0, A, B)$, is still free. Whatever we choose, $z_c = A z_v + B$, and after the divide

```math
z_n = \frac{A z_v + B}{-z_v} = \frac{-A s + B}{s} = -A + \frac{B}{s}
```

So NDC depth is always "a constant plus a constant over the distance". It is a **hyperbola** in $s$,
not a straight line. This is not a design choice: it follows from the matrix being linear and the
divide being by $s$. It has a benefit, proved in §5.4: $z_n$ then changes linearly across a triangle
on the screen, which lets the hardware interpolate and compress depth cheaply.

### 4.5 Choosing the depth row: reversed and infinite

We pick $A$ and $B$ by saying where the **near plane** ($s = n$, the closest distance drawn) and the
**far plane** ($s = F$) go. The textbook choice, **standard Z**, maps near to 0 and far to 1:

```math
-A + \frac{B}{n} = 0, \quad -A + \frac{B}{F} = 1 \quad\Rightarrow\quad B = -\frac{nF}{F-n},\; A = -\frac{F}{F-n},\quad z_n = \frac{F}{F-n}\Bigl(1 - \frac{n}{s}\Bigr)
```

(Subtract the two equations to get $B(1/n - 1/F) = -1$, so $B = -nF/(F - n)$; then $A = B/n$.)

Jeddy does the reverse, **reversed-Z**: near to 1 and far to 0. §7 shows why with numbers:

```math
-A + \frac{B}{n} = 1, \quad -A + \frac{B}{F} = 0 \quad\Rightarrow\quad B = \frac{nF}{F-n},\; A = \frac{n}{F-n}
```

Now let the far plane go to infinity. $A = n/(F - n) \to 0$ and $B = n/(1 - n/F) \to n$, and the depth
row becomes $(0, 0, 0, n)$:

```math
z_n = \frac{n}{s}
```

With the camera's near plane $n = 0.5$: depth 1 at 0.5 m, 0.5 at 1 m, 0.05 at 10 m, $5 \times 10^{-7}$
at a thousand kilometres, approaching 0 at infinity. Nothing is ever clipped for being too far away.

### 4.6 The whole matrix, and its inverse

The **infinite reversed-Z perspective projection** is

```math
P = \begin{pmatrix} f/a & 0 & 0 & 0 \\ 0 & f & 0 & 0 \\ 0 & 0 & 0 & n \\ 0 & 0 & -1 & 0 \end{pmatrix}
```

In column-major storage (§1.4) its four non-zero entries are index 0 (row 0, col 0) $= f/a$, index 5
(row 1, col 1) $= f$, index 11 (row 3, col 2) $= -1$, the entry that copies $-z_v$ into $w$, and index
14 (row 2, col 3) $= n$. `Mat4Ops.perspectiveReversedZ` writes exactly those four (§16).

Picking (§11) needs to run it backwards, so invert it by hand. From $x_c = (f/a)x_v$,
$y_c = f y_v$, $`z_c = n\,w_v`$ and $w_c = -z_v$ (where $w_v$ is the view-space point's fourth
coordinate, 1 for an ordinary point):

```math
x_v = \frac{a}{f}x_c,\quad y_v = \frac{y_c}{f},\quad z_v = -w_c,\quad w_v = \frac{z_c}{n}
\qquad\Longrightarrow\qquad
P^{-1} = \begin{pmatrix} a/f & 0 & 0 & 0 \\ 0 & 1/f & 0 & 0 \\ 0 & 0 & 0 & -1 \\ 0 & 0 & 1/n & 0 \end{pmatrix}
```

With $a = 16/9$, $f = 3.7321$, $n = 0.5$: $a/f = 0.47635$, $1/f = 0.26795$, $1/n = 2$; these are the
numbers `Mat4Ops.invert` produces from the real matrix. Its **determinant** (the number that is zero
exactly when a matrix has no inverse, chapter [02](02-math-foundations.md)) is $f^2 n/a = 3.917$, not
zero, so the inverse always exists.

### 4.7 Worked example: from world to pixel

Use the `IsoCamera` defaults, $\varphi = 30°$ and $n = 0.5$, with a 1920×1080 window ($a = 16/9$):
$f \approx 3.7321$ and $f/a \approx 2.0993$. Take the example eye of §3.4 and the point $(2, 0, 0)$,
which was $(2, 0, -10)$ in view space. The window transform in the last two rows is §5.3:

| Step | Value |
|---|---|
| clip | $`(2.0993 \cdot 2,\; 0,\; 0.5,\; 10) = (4.1986, 0, 0.5, 10)`$ |
| NDC | $`(0.41986,\; 0,\; 0.05)`$ |
| window $x$ | $(0.41986 + 1)/2 \cdot 1920 \approx 1363.1$ |
| window $y$ | $(0 + 1)/2 \cdot 1080 = 540$ (from the bottom) |
| depth | $0.05 = n/s = 0.5/10$ |

And $(0, 0, -5)$, view $(0, 3, -14)$: clip $`(0,\; 3.7321 \cdot 3,\; 0.5,\; 14) = (0, 11.196, 0.5, 14)`$,
NDC $(0, 0.7997, 0.0357)$, window $y = 1.7997/2 \cdot 1080 \approx 971.9$ from the bottom, which is 108
pixels below the top edge. Five metres of floor behind the target move the point from the center
almost to the top of the screen: a camera tilted only 37° sees the floor at a grazing angle, so floor
depth is squeezed into little screen height. (`Mat4Ops.project` on the real matrices gives
$x_n = 0.419856$ and $y_n = 0.799725$.)

<details><summary>Check: with the same camera, where does a point at view depth 20 m on the view axis land, and what depth is stored?</summary>

On the axis $x_v = y_v = 0$, so NDC $(0, 0)$: the center of the screen, pixel (960, 540). Depth
$n/s = 0.5/20 = 0.025$.
</details>

## 5. What the GPU does with clip coordinates

Once the vertex shader has written `gl_Position`, fixed-function hardware takes over. L00 §10.2
named the rasterizer; here is everything between the vertex shader and the pixel, in order:

```
 vertex shader ──▶ primitive assembly ──▶ clipping ──▶ divide by w ──▶ viewport transform
 (gl_Position)     (3 vertices → 1 triangle)                          (x_w, y_w, z_w)
                                                                            │
                                                                            ▼
 fragment shader ◀── early depth test ◀── rasterizer ◀────────────── face culling
       │                                  (which pixels; interpolate z and the outputs)
       ▼
 late depth test, depth write, color write  (the "output merger" or ROP units)
```

### 5.1 Clipping, and why it comes before the divide

The **clip volume** is the set of clip coordinates that can appear on screen. With the convention
Jeddy selects (§5.7) it is

```math
-w_c \le x_c \le w_c, \qquad -w_c \le y_c \le w_c, \qquad 0 \le z_c \le w_c
```

which, for $`w_c > 0`$, is the same as $x_n, y_n \in [-1, 1]$ and $z_n \in [0, 1]$ after the divide. With
the infinite reversed projection, $z_c = n$ and $w_c = s$, so the last pair reads $0 \le n \le s$: "at
least the near distance in front". There is no upper bound on $s$: that is the infinite far plane.

Why test before dividing? Because a point **behind** the camera has $`s < 0`$, so $`w_c < 0`$, and the
divide flips its signs. Take the running example camera (eye $(0, 6, 8)$) and the world point
$(0, 6, 12)$, 4 m behind and level with the eye:

| Step | Value |
|---|---|
| view | $`V(0, 6, 12, 1) = (0,\; 4.8 - 7.2,\; 3.6 + 9.6 - 10) = (0, -2.4, 3.2)`$: below the eye, behind it ($`z_v > 0`$) |
| clip | $`(0,\; 3.7321 \cdot (-2.4),\; 0.5,\; -3.2) = (0, -8.957, 0.5, -3.2)`$ |
| divided anyway | $`(0,\; 2.799,\; -0.156)`$: *above* the screen center, and with a negative depth |

The point is below the eye, yet the divide puts it above the top of the screen. A triangle with one
vertex there and two in front of the camera would be drawn as a huge, wrong triangle across the view.
Clipping prevents it: the clip test fails ($-w_c = 3.2$ is not $\le w_c = -3.2$), and for a triangle
that crosses the boundary the hardware cuts it along the plane $z_c = w_c$ (here: $s = n$), creating
new vertices where its edges cross. The new vertices can be computed by plain linear interpolation of
clip coordinates, because clip coordinates are a linear function of the vertex positions; after the
divide that would no longer be true. A triangle cut by one plane becomes one or two triangles.

What the hardware really does: clipping is expensive, so GPUs avoid most of it. Triangles that only
cross the left, right, top or bottom planes are usually not clipped at all: the rasterizer works in a
much larger **guard band** around the screen and simply produces no pixels outside the viewport.
Triangles that cross the near plane, which is at $w_c = n$ (with $w_c$ falling to 0 at the eye and
negative behind it), are the case most hardware still clips. (Some GPUs rasterize directly in
homogeneous coordinates without clipping at all and reject the near side per pixel by its depth.) You
never see any of this; you only see that geometry behind the camera never appears. CPU code that projects points itself must make the same check, or it draws mirrored ghosts:
the box selection of §11.5 skips any point with $w_c \le 0$.

### 5.2 The perspective divide

For every vertex that survives (and every vertex clipping created):

```math
x_n = \frac{x_c}{w_c}, \qquad y_n = \frac{y_c}{w_c}, \qquad z_n = \frac{z_c}{w_c}
```

These are the **normalized device coordinates**. Hardware usually computes $1/w_c$ once and multiplies
three times, because a division costs several multiplications; `Mat4Ops.project` does the same on
the CPU (§17). The hardware also keeps $1/w_c$ for the rasterizer (§5.4).

### 5.3 The viewport transform

The **viewport** is the rectangle of the render target being drawn into, $(x_0, y_0, W, H)$ in
pixels, set with `glViewport`. The GPU maps NDC to it:

```math
x_w = x_0 + \frac{x_n + 1}{2} W, \qquad y_w = y_0 + \frac{y_n + 1}{2} H, \qquad z_w = z_n
```

$x_n = -1$ lands on the left edge and $+1$ on the right; the $+1$ and the halving turn $[-1, 1]$ into
$[0, 1]$. The last formula holds because Jeddy selects the zero-to-one depth convention (§5.7); with
GL's default it would be $z_w = (z_n + 1)/2$. $z_w$ is the **window depth**, the number the depth test
compares and the depth buffer stores.

The origin is the **lower-left** corner, OpenGL's convention, which Jeddy keeps with
`GL_LOWER_LEFT`. Mouse coordinates from SDL start at the top-left, so picking code flips $y$ (§11.1).
NDC does not know about resolution: `FrameGraph` sets each pass's viewport to the size of its target,
the framebuffer size times the target's scale (§19.4), so the half-resolution ambient-occlusion passes
and the full-resolution passes share the same projection matrices.

### 5.4 Rasterization: why depth can be interpolated linearly

The **rasterizer** decides which pixels a triangle covers (those whose center lies inside it in
window coordinates) and produces one **fragment** per covered pixel, carrying the vertex shader's
outputs interpolated to that pixel. Before that, **face culling** may throw the triangle away: the
order in which its three window-space vertices go around (clockwise or counter-clockwise, the
**winding**) tells front from back, and `PipelineState.OPAQUE` culls back faces (§19.2).

How does the rasterizer get each pixel's depth? Not by dividing per pixel. Take a triangle lying in a
plane in view space, $\alpha x_v + \beta y_v + \gamma s = \delta$ (any plane that does not pass
through the eye can be written so with $\delta \ne 0$). Divide both sides by $s$:

```math
\alpha\frac{x_v}{s} + \beta\frac{y_v}{s} + \gamma = \frac{\delta}{s}
```

and use §4.2 backwards, $`x_v/s = (a/f)\,x_n`$ and $y_v/s = y_n/f$:

```math
\frac{1}{s} = \frac{\alpha (a/f)}{\delta}\,x_n + \frac{\beta/f}{\delta}\,y_n + \frac{\gamma}{\delta}
```

So **$1/s$ is a linear function of the screen position** across the triangle: three constants times
$(x_n, y_n, 1)$. Our depth $z_n = n/s$ is $n$ times it, so depth is linear on the screen too, and so is
the standard mapping $-A + B/s$. The hardware evaluates a plane equation $z = c_0 + c_1 x_w + c_2 y_w$
per triangle and steps it from pixel to pixel with additions. That is the benefit §4.4 promised, and
it is why depth buffers can be **compressed**: a block of pixels covered by one triangle is fully
described by three numbers.

Other outputs (texture coordinates, normals, the world position) are *not* linear on the screen; they
are linear in 3D. The same argument shows that $\text{value}/s$ is linear on the screen, so the
hardware interpolates $\text{value} \cdot (1/w_c)$ and $1/w_c$ linearly and divides the two per
fragment. This is **perspective-correct interpolation**, and the reason the divide unit keeps $1/w_c$.

### 5.5 The depth test

For each fragment the **depth test** compares the fragment's window depth with the value already
stored at that pixel, using the **depth function** set by `glDepthFunc`. If the comparison passes,
the fragment survives and, if depth writes are on (`glDepthMask(true)`), its depth replaces the
stored one; if it fails, the fragment is thrown away. With standard Z the function is "less": nearer
means smaller. With reversed-Z nearer means *larger*, so Jeddy uses "greater or equal", `GL_GEQUAL`
(§8.2 explains the "or equal").

Logically the test happens after the fragment shader. In practice the hardware runs it **early**,
before the shader, whenever the result cannot change: the shader does not write `gl_FragDepth`, and a
`discard` cannot affect what is written (for example because depth writes are off). An early-failing
fragment costs no shading at all. Jeddy builds its frame around that (DESIGN §9.3): a **depth
prepass** draws all opaque geometry with positions only and no color, filling the depth buffer; the
lit pass then draws the same geometry again with `GL_GEQUAL` and depth writes off, so the expensive
lighting shader runs only for the front-most surface of each pixel.

### 5.6 The depth buffer in memory

The depth buffer is a texture: one value per pixel. Jeddy's scene depth target is
`GL_DEPTH_COMPONENT32F`, a 32-bit IEEE float per pixel (L00 §7.2). At 1920×1080 that is
$1920 \times 1080 \times 4 = 8{,}294{,}400$ bytes, about 7.9 MiB, the same size as the common
`GL_DEPTH24_STENCIL8` format (24 bits of integer depth plus 8 bits of **stencil**, a per-pixel counter
the engine does not use). Choosing float costs no memory.

The GPU does not store it as a plain row-by-row array. Typical hardware (the details are each
vendor's, and the driver manages them without being asked):

- stores it in **tiles** of a few pixels square, so a triangle's pixels are close in memory;
- **compresses** tiles covered by one or two triangles as their plane equations (§5.4), which cuts
  memory traffic, the real cost of depth testing;
- keeps a **hierarchical Z** summary, the nearest and farthest depth of each tile, so the rasterizer
  can reject a whole tile of fragments of a hidden triangle at once, before any per-pixel test;
- performs **fast clears**: clearing marks every tile "cleared to value $c$" in a small metadata table
  instead of writing 8 MB.

The engine helps these features by clearing depth at the start of each frame (a fast clear), by not
writing `gl_FragDepth` (which would disable early tests), and by keeping one comparison direction all
frame: on some GPUs the hierarchical Z summary serves one direction only, and changing between "less"
and "greater" functions mid-frame turns it off until the next clear.

**Synchronization.** Depth writes go through the fixed-function output path, which GL orders by
itself: every draw sees the depth written by the draws before it, and a later pass that *samples* the
depth texture (ambient occlusion reads it, §24.5) sees the finished values without any
`glMemoryBarrier`. Barriers are only for shader writes to general memory
([Lesson 1](lessons/01-buffers-in-flight.md) §6.3). The one thing that is undefined is a **feedback
loop**: sampling a texture in the same pass that has it attached for writing. The frame graph keeps
the two apart: a pass declares the depth either as an attachment (`depth(...)`,
`depthReadOnly(...)`) or as a texture it reads (`readsTexture(...)`).

### 5.7 Clip-space conventions and glClipControl

OpenGL by default clips to $-w_c \le z_c \le w_c$, so NDC depth is in $[-1, 1]$, and maps it to window
depth with $z_w = (z_n + 1)/2$. Direct3D and Vulkan clip to $0 \le z_c \le w_c$ and store $z_n$
directly. OpenGL 4.5 made the choice a setting:

```c
void glClipControl(GLenum origin,   // GL_LOWER_LEFT or GL_UPPER_LEFT: which way window y grows
                   GLenum depth);   // GL_NEGATIVE_ONE_TO_ONE (default) or GL_ZERO_TO_ONE
```

What the driver does with it: it changes two pieces of state the hardware already supports, the
$z$ clip test and the $z$ scale and offset of the viewport transform. It is not per-draw work; setting
it once per context is enough. Jeddy calls `glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)` right after
creating the context (§19.1). From then on the clip volume is §5.1's, and window depth equals NDC
depth. Remember the inequality $0 \le z_c \le w_c$: frustum-plane extraction is built on it (§10.2).
§7.7 shows, with numbers, why this switch matters so much for reversed-Z.

<details><summary>Check: the viewport is 960×540 (a half-resolution pass). Where does NDC (0.41986, 0) land?</summary>

$x_w = 1.41986/2 \cdot 960 \approx 681.5$, $y_w = 0.5 \cdot 540 = 270$: exactly half of the
full-resolution pixel (1363.1, 540). The same matrices work for both because NDC has no resolution.
</details>

## 6. Orthographic projection

An **orthographic** camera has no perspective: parallel lines stay parallel and things do not shrink
with distance. There is no divide ($w_c = 1$); the projection only scales and offsets a box,
$x_v \in [l, r]$, $y_v \in [b, t]$ and a depth range from $n$ to $F$ in front of the camera, onto the
clip volume.

Derive the $x$ row. We want a straight line through $(l, -1)$ and $(r, 1)$: $x_n = k x_v + m$ with
$k l + m = -1$ and $k r + m = 1$. Subtracting gives $k = 2/(r - l)$; adding gives
$m = -(r + l)/(r - l)$. The same for $y$. For depth, reversed again, $z_v = -n$ must give 1 and
$z_v = -F$ must give 0: $z_n = (z_v + F)/(F - n)$. Check: $z_v = -n$ gives $(F - n)/(F - n) = 1$, and
$z_v = -F$ gives 0. So

```math
x_n = \frac{2 x_v}{r - l} - \frac{r + l}{r - l}, \qquad
y_n = \frac{2 y_v}{t - b} - \frac{t + b}{t - b}, \qquad
z_n = \frac{z_v}{F - n} + \frac{F}{F - n}
```

and the matrix has $2/(r - l)$ at index 0, $2/(t - b)$ at index 5, $1/(F - n)$ at index 10 (row 2,
col 2), the three offsets at indices 12, 13, 14 (column 3), and 1 at index 15 so that $w_c = 1$.
Depth is reversed but, unlike the perspective case, **linear** in distance.

`IsoCamera` in orthographic mode uses a box centered on the view axis, $h = $ `orthoHeight / 2`
and $w = h \cdot a$, so $l = -w$, $r = w$, $b = -h$, $t = h$ and the offsets are zero. With the defaults
(height 20, far 500, near 0.5) at 16:9: $2/(r - l) = 1/17.778 = 0.05625$, $2/(t - b) = 0.1$,
$1/(F - n) = 1/499.5 = 0.002002$ and $F/(F - n) = 1.001$.

Orthographic projections show up twice in Jeddy: as the optional mode of the isometric camera, and
for the sun's shadow cascades (`SunShadows` calls `Mat4Ops.orthoReversedZ`; chapter
[07](07-lighting-and-shadows.md) covers them).

## 7. Depth precision, with numbers

### 7.1 What "precision" means for a depth buffer

The depth test compares, per pixel, the depth of the incoming fragment with the depth already
stored. If two surfaces are a millimetre apart but get the *same* stored value, the test cannot tell
which is in front, and from one frame (or one pixel) to the next either may win: they flicker through
each other. This is **z-fighting**. So the question is: at view depth $s$, how far apart must two
surfaces be to get different stored values? Call that distance the **depth resolution** $\Delta s$.

Two things decide it: how fast the stored value $d(s)$ changes with distance, and how finely the
storage format can tell values apart near $d$. Let the format's smallest step near $d$ be $\Delta d$.

How fast $d$ changes is its **slope** (its **derivative**, written $d'(s)$): how much $d$ changes per
metre, at that distance. For the curves in this chapter you can work it out with algebra. For
$d(s) = c/s$, step from $s$ to $s + h$:

```math
\frac{d(s + h) - d(s)}{h} = \frac{1}{h}\Bigl(\frac{c}{s + h} - \frac{c}{s}\Bigr) = \frac{1}{h}\cdot\frac{cs - c(s + h)}{s(s + h)} = \frac{-c}{s(s + h)}
```

and as the step $h$ shrinks towards 0 this becomes $d'(s) = -c/s^2$. Numbers: for $c = 0.5$ at
$s = 20$, $d(20) = 0.025$ and $d(20.001) = 0.0249988$; the difference divided by 0.001 is $-0.00125$,
and $-0.5/20^2 = -0.00125$.

If the curve changes by $\lvert d'(s) \rvert$ per metre, it changes by one storage step $\Delta d$ over
$\Delta d / \lvert d'(s) \rvert$ metres:

```math
\Delta s \approx \frac{\Delta d}{\lvert d'(s) \rvert}
```

(The "$\approx$" is because the slope changes a little over the step; for steps of micrometres it is
exact to many digits.)

### 7.2 The two storage formats

- **24-bit integer** (`GL_DEPTH_COMPONENT24`, the classic format): stores $d \in [0, 1]$ as the whole
  number $\text{round}(d \cdot (2^{24} - 1))$. The step is the same everywhere:
  $\Delta d = 1/(2^{24} - 1) \approx 5.96 \times 10^{-8}$.
- **32-bit float** (`GL_DEPTH_COMPONENT32F`): stores $d$ as a `float`. L00 §7.3 showed that float
  spacing is *relative*: with 23 fraction bits, each range $[2^k, 2^{k+1})$ holds exactly $2^{23}$
  evenly spaced floats, so the step
  near $d$ is $\Delta d = 2^{k - 23}$ where $`2^k \le d < 2^{k + 1}`$. Java returns it as
  `Math.ulp(float)` (the "unit in the last place"):

| $d$ | range | `Math.ulp((float) d)` |
|---|---|---|
| 0.9 | $[0.5, 1)$ | $2^{-24} \approx 5.96 \times 10^{-8}$ |
| 0.5 | $[0.5, 1)$ | $2^{-24} \approx 5.96 \times 10^{-8}$ |
| 0.1 | $[0.0625, 0.125)$ | $2^{-27} \approx 7.45 \times 10^{-9}$ |
| 0.025 | $[0.015625, 0.03125)$ | $2^{-29} \approx 1.86 \times 10^{-9}$ |
| 0.001 | $[2^{-10}, 2^{-9})$ | $2^{-33} \approx 1.16 \times 10^{-10}$ |

Near 1, a float is no finer than a 24-bit integer: both have steps of about $6 \times 10^{-8}$. Near 0 a
float is enormously finer. Everything below follows from these two facts.

### 7.3 Standard Z puts its values where the precision is not

Standard depth (near to 0, far to 1, finite far $F$, §4.5) is

```math
d(s) = \frac{F}{F - n}\Bigl(1 - \frac{n}{s}\Bigr), \qquad d'(s) = \frac{F}{F - n}\cdot\frac{n}{s^2} \approx \frac{n}{s^2}
```

(the slope by §7.1 with $c = Fn/(F - n)$, sign dropped). The curve rises steeply near the camera and is
almost flat further away. With $n = 0.5$ and $F = 500$:

| $s$ (m) | 1 | 2 | 5 | 10 | 20 | 100 | 400 |
|---|---|---|---|---|---|---|---|
| $d(s)$ | 0.5005 | 0.75075 | 0.9009 | 0.95095 | 0.97598 | 0.996 | 0.99975 |

Three quarters of the range is spent on the first two metres; everything from 20 m to 500 m shares
the last 2.4%. For both formats the step there is about $2^{-24}$ (for float because $d \ge 0.5$), so

```math
\Delta s \approx \frac{2^{-24}}{n/s^2} = \frac{2^{-24}\,s^2}{n}
```

The resolution gets worse with the **square** of the distance, and a float buffer does not help at
all. It is worse than that: of all the float values in $[0, 1)$, 99.6% lie below
$`d(2\ \text{m}) = 0.75075`$, so they all describe the first two metres in front of the camera.
(Counting is easy because non-negative floats sort in the same order as their bit patterns read as
`int`s. With the layout of L00 §7.2, the sign bit is 0, the exponent sits above the fraction, and a
bigger exponent always means a bigger number; within one exponent, a bigger fraction means a bigger
number. So comparing the 31 bits as an integer compares the floats, and consecutive floats have
consecutive bit patterns. `Float.floatToRawIntBits` of $d(2) = 0.7507508$ is 1,061,171,508 and of
`1.0f` is 1,065,353,216, so that many floats lie below each.) The standard mapping puts the interesting depths
near 1, in the sparse part of the float range, and leaves the dense part unused.

### 7.4 Reversed Z with a float: the two non-linearities cancel

Flip the mapping to $d(s) = n/s$ (§4.5). Now distant points get small depth values, exactly where
floats are dense. The float step near $d$ is between $2^{-24}d$ and $2^{-23}d$ (relative precision),
and the slope is $n/s^2$, so

```math
\Delta s \approx \frac{2^{-23}\, (n/s)}{n/s^2} = 2^{-23}\, s
```

The near plane $n$ cancels out, and the resolution is a constant *fraction* of the distance, between
$2^{-24} \approx 6 \times 10^{-8}$ and $2^{-23} \approx 1.2 \times 10^{-7}$ of it. Two crowdings cancel:
the hyperbola crowds distant points towards $d = 0$, and the float format crowds its values towards 0
by the same law.

Reversing alone does nothing for an *integer* buffer. Its step is constant, so

```math
\Delta s \approx \frac{2^{-24}}{n/s^2} = \frac{2^{-24}\,s^2}{n}
```

exactly the standard result. The gain comes from the pair: the reversed mapping *and* a float
buffer.

### 7.5 The four combinations at real distances

Here is $\Delta s$ for all four combinations, with $n = 0.5$ (the camera's near plane) and, for
standard Z, $F = 500$. Each entry is `Math.ulp` (or the 24-bit step) divided by the exact slope; the
program of exercise 2 prints this table:

| View depth $s$ | Standard, 24-bit | Reversed, 24-bit | Standard, float32 | **Reversed, float32** |
|---|---|---|---|---|
| 1 m | 0.12 µm | 0.12 µm | 0.12 µm | 0.12 µm |
| 5 m | 3.0 µm | 3.0 µm | 3.0 µm | **0.37 µm** |
| 10 m | 12 µm | 12 µm | 12 µm | **0.75 µm** |
| 20 m | 48 µm | 48 µm | 48 µm | **1.5 µm** |
| 50 m | 0.30 mm | 0.30 mm | 0.30 mm | **4.7 µm** |
| 100 m | 1.2 mm | 1.2 mm | 1.2 mm | **9.3 µm** |
| 400 m | 19 mm | 19 mm | 19 mm | **37 µm** |

(µm is a micrometre, a thousandth of a millimetre.) Three columns are the same quadratic law; the
fourth grows only linearly, and at 400 m it is 500 times finer. At 1 m all four agree: there
$d$ is 0.5 for both mappings, where both formats have the same $2^{-24}$ step.

Where does the isometric camera look? The default camera (distance 20, pitch 55°, 30° field of view)
sees the ground at view depths from 16.8 m (bottom edge of the screen) to 24.6 m (top edge); the
demo's camera (22 m, pitch 52°) from 18.2 m to 27.8 m (§9.2 derives these). Right in the 20 m row.

The near plane matters for three columns and not the fourth. With $n = 0.05$ (a near plane ten times
closer, as a camera that can push into walls might want):

| View depth $s$ | Standard, 24-bit or float32 | Reversed, float32 |
|---|---|---|
| 20 m | 0.48 mm | 1.9 µm |
| 100 m | 12 mm | 12 µm |

The standard column is ten times worse ($\Delta s \propto 1/n$); the reversed column stays within a
factor of two, because its relative precision is between $2^{-24}$ and $2^{-23}$ whatever the near
plane.

### 7.6 Counting values per doubling of distance

Another way to see it: count how many different stored values describe each doubling of distance.
For reversed float, a doubling from $s$ to $2s$ maps to the depth interval $`[n/(2s),\; n/s]`$, whose
ends differ by a factor of two, and *any* such interval holds exactly $2^{23} = 8{,}388{,}608$ floats.
(If it is not aligned to a power of two, it straddles two ranges $[2^k, 2^{k+1})$ and
$[2^{k+1}, 2^{k+2})$; the part in the lower range and the part in the upper one, whose step is twice
as big, add up to exactly one range's worth.) Counted from the float bit patterns and the 24-bit
codes, $\text{round}(d \cdot (2^{24} - 1))$ ($n = 0.5$, $F = 500$):

| Distance range | Standard, float32 | Standard, 24-bit | Reversed, 24-bit | Reversed, float32 |
|---|---|---|---|---|
| 1–2 m | 4,198,503 | 4,198,502 | 4,194,304 | 8,388,608 |
| 10–20 m | 419,850 | 419,850 | 419,431 | 8,388,608 |
| 20–40 m | 209,925 | 209,925 | 209,715 | 8,388,608 |
| 100–200 m | 41,985 | 41,985 | 41,943 | 8,388,608 |
| 200–400 m | 20,992 | 20,992 | 20,971 | 8,388,608 |

Every doubling of distance halves the budget of the first three columns. The last one gives every
doubling, from the near plane to the edge of the float range, the same 8.4 million values.

### 7.7 Why glClipControl matters: the default convention throws it away

Without `glClipControl`, the clip volume's depth is $[-1, 1]$. A reversed projection designed for it
would put near at $z_n = 1$ and far at $-1$: $z_n = 2n/s - 1$. Distant points then sit just above
$-1$, and floats near $-1$ are spaced $2^{-24}$ apart (magnitudes in $[0.5, 1)$). The hardware's
$z_w = (z_n + 1)/2$ then turns that into a number near 0, but the low bits are already gone: the
stored values can only differ in steps of $2^{-24}/2 = 2^{-25}$. So, for $s \ge 4n = 2$ m (where
$z_n \le -0.5$),

```math
\Delta s \approx \frac{2^{-25}\, s^2}{n}
```

back to the quadratic law, just twice as good as standard:

| View depth $s$ | Reversed float32, `GL_ZERO_TO_ONE` | Reversed float32, default $[-1, 1]$ clip |
|---|---|---|
| 5 m | 0.37 µm | 1.5 µm |
| 20 m | 1.5 µm | 24 µm |
| 100 m | 9.3 µm | 0.60 mm |
| 400 m | 37 µm | 9.5 mm |

Most of the benefit disappears. With `GL_ZERO_TO_ONE` the value $n/s$ is stored as computed. This is
one of the two motivations the `ARB_clip_control` extension gives for itself (the other is matching
Direct3D's conventions).

### 7.8 What it means for this engine

Be honest about it: at 20 m even the textbook combination resolves 0.05 mm, which is plenty for most
geometry in a CRPG. The gains show up

- at distance, and with a small near plane (§7.5);
- with nearly coplanar surfaces: a rug on a floor, a decal mesh, overlapping level pieces;
- in shadow maps: the local lights' shadow views are perspective projections too
  (`LocalShadows` calls `Mat4Ops.perspectiveReversedZ` with its own near plane), and every shadow
  comparison is a depth comparison;
- with the infinite far plane: standard Z with $F = \infty$ is possible ($d = 1 - n/s$), but it piles
  all distant geometry against 1, where steps are coarsest; reversed float handles infinity for free.

The table is a best case: the vertex shader's float arithmetic and the rasterizer's interpolation add
a few units of rounding on top, so read the numbers as lower bounds. The infinite reversed matrix also
keeps that arithmetic clean: row 2 of $P$ is $(0, 0, 0, n)$, and the view matrix's last row is exactly
$(0, 0, 0, 1)$, so row 2 of $`P\,V`$ is exactly $(0, 0, 0, n)$ and $z_c = n$ for every vertex with no
rounding at all. The only rounding in $z_n = n/w_c$ is in $w_c$ and the one division.

And it costs nothing at run time: the same number of instructions, and `GL_DEPTH_COMPONENT32F` is the
same 4 bytes per pixel as the common 24-bit depth plus 8-bit stencil format (§5.6). So Jeddy uses
reversed-Z for every depth buffer: the scene's, the sun cascades' and the local shadow atlas, all
`GL_DEPTH_COMPONENT32F`.

<details><summary>Check: a rug is modelled 2 mm above the floor. At what view depth would a standard-Z 24-bit buffer with n = 0.5 start to z-fight it? And reversed float?</summary>

Standard: $2^{-24} s^2 / n = 0.002$ gives $s^2 = 0.002 \cdot 0.5 \cdot 2^{24} = 16{,}777$, $s \approx 130$ m.
Reversed float: $\Delta s \le 2^{-23} s = 0.002$ gives $s \ge 16{,}777$ m, about 17 km. (Real
z-fighting starts a few times earlier, because of the arithmetic rounding mentioned above.)
</details>

## 8. Reversed-Z in practice

### 8.1 Three pieces of state, plus clip control

Reversed-Z is not a matrix alone. Four things must agree, and Jeddy sets all of them (§19 builds each):

1. **Clip control**: `glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)` (§5.7, §7.7).
2. **Depth format: float.** The scene depth target is a frame-graph texture
   `graph.texture("depth", GL_DEPTH_COMPONENT32F, 1f)` in `SceneRenderer`. (The window's own
   framebuffer has no depth buffer at all, D23: the scene renders into frame-graph textures and only
   the final post pass writes the window.)
3. **Clear value: 0, the far end.** The depth prepass clears to 0, and so do the shadow maps.
   Clearing to 1, the habit from standard Z, would mean "everything is at the near plane" and nothing
   could pass.
4. **Depth test: greater wins.** Closer means larger now, so the function is `GL_GEQUAL`, and the
   shadow-map comparison sampler uses `GL_GEQUAL` too.

### 8.2 Why GEQUAL, and not GREATER or EQUAL

For a single pass, `GL_GREATER` would do: a fragment wins only if it is strictly nearer. Jeddy uses
`GEQUAL` everywhere because of the depth prepass (§5.5). After the prepass, the lit opaque pass draws
the *same* triangles again with depth writes off (`MaterialSystem.litOpaque` applies
`PipelineState.withDepthWrite(PipelineState.OPAQUE, false)`). A fragment of the visible surface must
pass, and its depth is the one the prepass stored. `GREATER` would reject it. `EQUAL` looks like the
exact test, but the prepass and the lit pass are two separately compiled programs: nothing forces them
to compute bit-identical positions (GLSL's `invariant` qualifier would, at some cost), and positions
that differ in the last bit would fail `EQUAL` and leave holes. `GEQUAL` passes the equal fragment and
tolerates a result that comes out a hair nearer (D42). (A result a hair *farther* would still fail:
`GEQUAL` removes half of the risk, not all of it. What would remove all of it, the `invariant`
qualifier on `gl_Position` in both programs, is not used.)

### 8.3 The sky at depth 0

The sky uses the far end directly. It is drawn as one triangle that covers the whole screen, from
[`engine/fullscreen.vert`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/engine/fullscreen.vert),
whose vertices all have $z_c = 0$ and $w_c = 1$, so depth 0:

```glsl
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
```

The sky pass tests `GEQUAL` without writing. A fragment at depth 0 passes only where the buffer still
holds the clear value 0, that is, where no geometry was drawn. One triangle, and the sky is shaded only
where it is visible. (§19.5 walks through the shader.)

### 8.4 What the infinite far plane changes

With $F = \infty$ nothing is clipped for distance, and the far frustum plane disappears: §10.3 shows
it degenerate to "accept everything". Two consequences to remember:

- `RenderPacket.far` is not a clipping distance. Its Javadoc says what it is: "Distance up to which
  lights are clustered and shadows cascade". The demo sets it to `camera.distance * 2 + 60`.
- Depth exactly 0 means "at infinity", which is not a finite point: unprojecting it divides by zero
  (§11.3). Shaders that turn depth back into a position clamp it first: the sky unprojects at depth
  `0.0001`, ambient occlusion and particles use `max(depth, 1e-7)` (§24.5).

### 8.5 What goes wrong when one piece is missing

| Mistake | What you see |
|---|---|
| Depth cleared to 1 | Nothing drawn: no fragment's depth is $\ge 1$ except at the near plane |
| Depth function left at GL's default `GL_LESS`, buffer cleared to 0 | Nothing drawn: no depth is $`< 0`$ |
| `glClipControl` forgotten | It *looks* right, so it is the dangerous one: clipping still happens at $s = n$ and the sky still draws behind everything, but window depth $(n/s + 1)/2$ lands in $(0.5, 1]$, where float steps are $2^{-24}$: $\Delta s \approx 2^{-23} s^2/n$, worse than standard Z (compare §7.7) |
| Shadow sampler compares `GL_LEQUAL` | Lit and shadowed regions swap |
| Polygon offset with standard-Z signs | Shadow acne gets worse instead of better: in reversed-Z, pushing a caster *away* from the light means *lower* depth, so `SunShadows` uses `glPolygonOffset(-1.5f, -2f)` |
| Code that assumes "depth 1 is the sky" | Wrong pixels: here the sky is depth 0 |

## 9. The isometric camera

### 9.1 Orbit parameters

A CRPG camera is not a free-flying camera. It orbits a point on the ground (the **target**) at a
fixed tilt (**pitch**), can turn around it (**yaw**) and zoom in and out (**distance**). That is
`IsoCamera`'s model:

```
  Side view                                   Top view (y towards you)

          eye *                                          -Z
             /|                                           ^   (yaw 0 looks this way)
            / |                                           |
   distance/  | distance * sin(pitch)        -X <---------T---------> +X
          /   |                                           |\
         /    |                                           | \  yaw, from +Z towards +X
  target*-----+                                           |  \
        distance * cos(pitch)                             |   E  eye, at distance * cos(pitch)
                                                          v
                                                         +Z
```

With target $\mathbf{T}$, yaw $\psi$, pitch $\theta$ and distance $D$, derive the eye from §2.1. The
eye is $D$ away along a line tilted up by $\theta$, so it is $D\cos\theta$ away horizontally and
$D\sin\theta$ above the target. The horizontal direction from the target to the eye is
$(\sin\psi, 0, \cos\psi)$: $(0, 0, 1)$ at $\psi = 0$ (the eye on the +Z side), turning towards +X as
$\psi$ grows. So

```math
\mathbf{e} = \mathbf{T} + D\,(\sin\psi\cos\theta,\; \sin\theta,\; \cos\psi\cos\theta)
```

and the view direction is the opposite unit vector,
$`\mathbf{f} = (-\sin\psi\cos\theta,\; -\sin\theta,\; -\cos\psi\cos\theta)`$. At $\psi = 0$ the camera
looks towards −Z. The running example of §3.4 is this camera with $\psi = 0$,
$\theta = \arctan(6/8)$ and $D = 10$: $`\mathbf{e} = (0,\; 10 \cdot 0.6,\; 10 \cdot 0.8) = (0, 6, 8)`$.
The defaults:

| Field | Default | Meaning |
|---|---|---|
| `yaw` | $\pi/4$ (45°) | Rotation around +Y; 0 looks towards −Z |
| `pitch` | 55° | Downward tilt; $\pi/2$ would look straight down |
| `distance` | 20 | Eye to target |
| `fovY` | 30° | Vertical field of view (perspective) |
| `near` | 0.5 | Near plane |
| `orthographic` | `false` | Orthographic instead of perspective |
| `orthoHeight`, `orthoFar` | 20, 500 | Visible height and far plane in orthographic mode |

With them the eye sits at
$`\mathbf{T} + 20\,(0.7071 \cdot 0.5736,\; 0.8192,\; 0.7071 \cdot 0.5736) = \mathbf{T} + (8.112, 16.383, 8.112)`$,
which is what `IsoCamera.eye()` returns for a target at the origin.

### 9.2 Why a narrow field of view

"Isometric" in the strict sense is an orthographic projection with yaw 45° and pitch
$\arctan(1/\sqrt 2) \approx 35.26°$: the three axes then appear equally foreshortened. Jeddy's design
instead defaults to a perspective camera with a narrow field of view (DESIGN §9.4: about 30–35°, with
orthographic as an option), which keeps most of the clean look while still giving depth cues. The
default pitch of 55° is steeper than true isometric, closer to looking down on the party.

How much does the field of view matter? Compare the scale of objects at the top and bottom edges of
the screen. At the top edge the camera's ray leaves the eye at $\theta - \varphi/2$ below the
horizontal, at the bottom edge at $\theta + \varphi/2$. A ray tilted down by $\beta$ from height
$h = D\sin\theta$ reaches the ground after $h/\sin\beta$ (the hypotenuse of a triangle with opposite
side $h$), and that point's view depth is the ray length times $\cos(\varphi/2)$, the cosine of the
angle between the ray and the view axis:

```math
s_{top} = \frac{D\sin\theta\cos(\varphi/2)}{\sin(\theta - \varphi/2)}, \qquad
s_{bottom} = \frac{D\sin\theta\cos(\varphi/2)}{\sin(\theta + \varphi/2)}
```

Things shrink in proportion to $1/s$ (§4.1), so an object at the top edge appears
$s_{bottom}/s_{top}$ times the size of the same object at the bottom. With $D = 20$ and pitch 55°:

| Field of view | View depth at top edge / bottom edge | Top object appears |
|---|---|---|
| 30° | 24.6 / 16.8 = 1.46 | 68% of the size |
| 60° | 33.6 / 14.2 = 2.36 | 42% of the size |

($D$ cancels in the ratio, which depends only on pitch and field of view.) A 60° camera exaggerates the
perspective; a 30° camera keeps characters at the top of the screen readable. The demo's camera
(distance 22, pitch 52°) sees the ground from 18.2 m to 27.8 m.

The price of a narrow field of view is that the camera must stand further back to frame the same
area, which is fine with reversed-Z, since precision follows relative distance (§7.4).

### 9.3 Smoothing that does not depend on the frame rate

`IsoCamera` is pure math: you set its fields and it computes matrices. The behaviour players expect
lives in `IsoCameraController` (§22), and its first idea is that zoom, rotation and follow do not jump
to their goal $g$; they glide.

The model is "move towards the goal at a speed proportional to the remaining gap": with a **time
constant** $\tau$ (`smoothing`, 0.12 s), the speed is $(g - x)/\tau$. A gap that shrinks at a rate
proportional to itself shrinks by the same *factor* in every equal slice of time: in time $t$ it is
multiplied by $e^{-t/\tau}$, where $e \approx 2.71828$ is the base of the **exponential** function,
`Math.exp`. (The exponential is the function whose rate of change equals its own value; that is
exactly "speed proportional to the gap".) Over one frame of length $\Delta t$:

```math
g - x(t + \Delta t) = (g - x(t))\, e^{-\Delta t/\tau}
\quad\Longleftrightarrow\quad
x \leftarrow x + (g - x)\,k, \quad k = 1 - e^{-\Delta t/\tau}
```

(The right form is the left solved for the new $x$: subtract both sides from $g$.) At 60 FPS,
$\Delta t = 1/60$ s and $k = 1 - e^{-0.1389} \approx 0.130$: each frame covers 13% of the remaining gap.

Why the exponential and not a fixed fraction? Because it is the exact solution, two frames of
$\Delta t/2$ give the same result as one frame of $\Delta t$: $e^{-a}e^{-b} = e^{-(a + b)}$, the power
rule of L00 §2.3. The naive `x += (g - x) * 0.1` moves 10% *per frame*, so a 144 Hz screen makes the
camera glide 2.4 times faster than a 60 Hz one. Exercise 8 turns the claim into a test.

How long does a glide take? After $3\tau = 0.36$ s the gap is $`e^{-3} \approx 5\%`$ of what it was: the
camera has covered 95% of the way.

### 9.4 Multiplicative zoom

`zoom(steps)` multiplies the goal distance by $1.15^{-\text{steps}}$ and clamps it to
`[minDistance, maxDistance]` (6 and 60 by default). Every wheel notch changes the view by the same
*percentage* (15%), which feels uniform whether you are close or far; adding a fixed number of metres
would crawl when far away and lurch when close. Three notches in from 20 give
$20/1.15^3 \approx 13.15$, which is what `zoomAndRotationAreSmoothedAndClamped` asserts once the
smoothing settles.

### 9.5 Panning relative to the view

"Forward" for the player means "away from the camera, along the ground", whatever the yaw. Drop the
$y$ component of the camera's forward vector $(-\sin\psi\cos\theta, -\sin\theta, -\cos\psi\cos\theta)$
and normalize (divide by $\cos\theta$): the **ground forward** is $(-\sin\psi, 0, -\cos\psi)$. Right is
forward × up (§2.3):

```math
(-\sin\psi, 0, -\cos\psi) \times (0, 1, 0) = (0 \cdot 0 - (-\cos\psi) \cdot 1,\; (-\cos\psi) \cdot 0 - (-\sin\psi) \cdot 0,\; (-\sin\psi) \cdot 1 - 0 \cdot 0) = (\cos\psi, 0, -\sin\psi)
```

A pan input (right, forward), each in $[-1, 1]$, moves the target by
$\text{right}\cdot(\cos\psi, 0, -\sin\psi) + \text{forward}\cdot(-\sin\psi, 0, -\cos\psi)$ times a speed:

```math
\Delta x = (\text{right}\cos\psi - \text{forward}\sin\psi)\cdot v\,\Delta t, \qquad
\Delta z = (-\text{right}\sin\psi - \text{forward}\cos\psi)\cdot v\,\Delta t
```

The speed $v$ is `panSpeed` (18 units per second) times `distance / 20`, so the ground scrolls at the
same speed *on screen* at every zoom level: at twice the distance, a metre looks half as big, so the
camera moves twice as many metres. Diagonal input, (1, 1), has length $\sqrt 2$ and would move 41%
faster than straight input; the controller divides by the input's length when it exceeds 1.

**Edge panning** turns a cursor position into pan input: inside the outer 1% of the window
(`edgeMargin`), the camera pans towards that edge. Screen $y$ grows downwards, so the top edge means
"forward".

### 9.6 Drag panning: keeping the ground under the cursor

Grab the floor with the mouse and drag: the point you grabbed should stay exactly under the cursor.
The controller remembers the grabbed ground point (the **anchor**) and, on every move, shifts the
target by how far the ground point under the cursor drifted: $\Delta = \text{anchor} - \text{hit}$.

Why is this exact and not just approximate? Translating the camera by a horizontal vector $\Delta$
translates every viewing ray by $\Delta$ and changes nothing else: the rotation and the projection stay
the same. So the ray through the same cursor position, which hit the ground at $\text{hit}$, now hits
it at $\text{hit} + \Delta = \text{anchor}$. One step puts the anchor back under the cursor, with no
iteration. `dragKeepsTheGrabbedGroundPointUnderTheCursor` checks it to $10^{-3}$. Finding "the ground
point under the cursor" is picking against a plane, §11.4.

## 10. The frustum and its planes

The camera sees a truncated pyramid, the **view frustum**: four side planes through the eye, the near
plane in front, and (with a finite far plane) a far plane. Anything entirely outside it cannot appear
on screen, so there is no point sending it to the GPU. With thousands of props and characters per
level, testing a bounding sphere per object against the frustum on the CPU is much cheaper than
drawing everything. That test is **frustum culling**.

### 10.1 Planes as four numbers

A **plane** is the set of points $\mathbf{p}$ with $\mathbf{n}\cdot\mathbf{p} + d = 0$, where
$\mathbf{n}$ is perpendicular to the plane (its **normal**) and $d$ shifts it from the origin. Write it
as four numbers $(a, b, c, d)$ with $\mathbf{n} = (a, b, c)$. For a point not on the plane,
$a x + b y + c z + d$ is positive on one side (the side $\mathbf{n}$ points to) and negative on the
other. If $\mathbf{n}$ has unit length, the value is the **signed distance** from the plane in world
units (§2.2: it is "how far along $\mathbf{n}$" measured from the plane). Normalizing a plane means
dividing all four numbers by $\lVert\mathbf{n}\rVert$, which keeps the same plane and makes the value a
distance. Chapter [02](02-math-foundations.md) has the details, in its section on planes, spheres,
boxes and rays.

### 10.2 Planes from the view-projection matrix

How do we find the frustum's planes? We could compute its eight corners and build planes from triples
of them, but there is a much shorter way, due to Gribb and Hartmann. Let
$`\mathbf{m}_0 \ldots \mathbf{m}_3`$ be the **rows** of the view-projection matrix $`P\,V`$, each a
4-vector, and $\mathbf{p} = (x, y, z, 1)$ a world point. Matrix times vector is one dot product per
row, so the clip coordinates are

```math
x_c = \mathbf{m}_0\cdot\mathbf{p},\quad y_c = \mathbf{m}_1\cdot\mathbf{p},\quad z_c = \mathbf{m}_2\cdot\mathbf{p},\quad w_c = \mathbf{m}_3\cdot\mathbf{p}
```

A point is inside the frustum exactly when it is inside the clip volume (§5.1). Rewrite each clip
inequality so that the right-hand side is 0, and use
$\mathbf{a}\cdot\mathbf{p} + \mathbf{b}\cdot\mathbf{p} = (\mathbf{a} + \mathbf{b})\cdot\mathbf{p}$:

| Clip test | Rewritten | Plane $(a, b, c, d)$ | Name in `CullOps` |
|---|---|---|---|
| $x_c \ge -w_c$ | $`(\mathbf{m}_3 + \mathbf{m}_0)\cdot\mathbf{p} \ge 0`$ | $`\mathbf{m}_3 + \mathbf{m}_0`$ | left |
| $x_c \le w_c$ | $`(\mathbf{m}_3 - \mathbf{m}_0)\cdot\mathbf{p} \ge 0`$ | $`\mathbf{m}_3 - \mathbf{m}_0`$ | right |
| $y_c \ge -w_c$ | $`(\mathbf{m}_3 + \mathbf{m}_1)\cdot\mathbf{p} \ge 0`$ | $`\mathbf{m}_3 + \mathbf{m}_1`$ | bottom |
| $y_c \le w_c$ | $`(\mathbf{m}_3 - \mathbf{m}_1)\cdot\mathbf{p} \ge 0`$ | $`\mathbf{m}_3 - \mathbf{m}_1`$ | top |
| $z_c \le w_c$ | $`(\mathbf{m}_3 - \mathbf{m}_2)\cdot\mathbf{p} \ge 0`$ | $`\mathbf{m}_3 - \mathbf{m}_2`$ | near (reversed-Z) |
| $z_c \ge 0$ | $`\mathbf{m}_2\cdot\mathbf{p} \ge 0`$ | $`\mathbf{m}_2`$ | far (reversed-Z) |

Each row is linear in $\mathbf{p}$, so each is a world-space plane, with "inside" on the positive side.
No divide is needed, so the trick works for points behind the camera too (it never forms $x_n$). The
last two rows depend on the clip convention: with OpenGL's default $-w_c \le z_c \le w_c$ the far
plane of a reversed projection would be $`\mathbf{m}_3 + \mathbf{m}_2`$. Jeddy uses $[0, w_c]$
(§5.7), and in reversed-Z, $z_c = w_c$ is the *near* plane.

### 10.3 The degenerate far plane and the near plane, derived

For the infinite perspective, row 2 of $P$ is $(0, 0, 0, n)$, so row 2 of $`P\,V`$ is $n$ times row 3 of
$V$, which is $(0, 0, 0, 1)$: $`\mathbf{m}_2 = (0, 0, 0, n)`$. The "far plane" says
$0x + 0y + 0z + n \ge 0$: true everywhere. It has no normal to normalize. `CullOps.extractPlanes`
detects the zero normal and stores `(0, 0, 0, 1)`, a plane whose "distance" is 1 for every point, so it
accepts everything. Its Javadoc says so: "The far plane of an infinite reversed-Z projection is
degenerate".

The near plane is just as instructive. Row 3 of $P$ is $(0, 0, -1, 0)$, so
$`\mathbf{m}_3 = -(\text{row 2 of } V) = (\mathbf{f}, -\mathbf{f}\cdot\mathbf{e})`$ (§3.2: row 2 of $V$
is $(-\mathbf{f}, \mathbf{f}\cdot\mathbf{e})$). Then

```math
\mathbf{m}_3 - \mathbf{m}_2 = (\mathbf{f},\; -\mathbf{f}\cdot\mathbf{e} - n)
\quad\Longrightarrow\quad
\mathbf{f}\cdot\mathbf{p} - \mathbf{f}\cdot\mathbf{e} - n \ge 0
\quad\Longleftrightarrow\quad
\mathbf{f}\cdot(\mathbf{p} - \mathbf{e}) \ge n
```

"At least $n$ in front of the eye along the view direction." The normal $\mathbf{f}$ is already unit
length, so the plane comes out normalized.

### 10.4 A worked example

For the running example camera (eye $(0,6,8)$, target the origin, 30°, 16:9, $n = 0.5$), the real
`IsoCamera` produces:

| Plane | $(a, b, c)$ | $d$ |
|---|---|---|
| left | $(0.9028, -0.2580, -0.3440)$ | 4.3005 |
| right | $(-0.9028, -0.2580, -0.3440)$ | 4.3005 |
| bottom | $(0, 0.6174, -0.7866)$ | 2.5882 |
| top | $(0, -0.9280, 0.3725)$ | 2.5882 |
| near | $(0, -0.6, -0.8)$ | 9.5 |
| far | $(0, 0, 0)$ | 1 |

Each $d$ is the signed distance of the origin, the target, from that plane. Check them with geometry:

- **Near**: the normal is $\mathbf{f} = (0, -0.6, -0.8)$ and the target is 10 m along the view axis, so
  it is $10 - 0.5 = 9.5$ m beyond the near plane.
- **Left and right**: the side planes pass through the eye at the horizontal half-angle $h$ of the
  view, with $\tan h = a\tan 15° = 1.7778 \cdot 0.26795 = 0.47636$, so $h = 25.47°$. A point on the axis
  10 m out is $10\sin h = 10 \cdot 0.43005 = 4.3005$ m from each. The left plane's inward normal is
  $`\cos h\,\mathbf{r} + \sin h\,\mathbf{f} = 0.9028\,(1, 0, 0) + 0.4300\,(0, -0.6, -0.8) = (0.9028, -0.2580, -0.3440)`$,
  the table's row.
- **Top and bottom**: the vertical half-angle is 15°, so $10\sin 15° = 2.5882$.

### 10.5 The sphere test, and why it is conservative

Each object carries a **bounding sphere**, a center $\mathbf{c}$ and radius $r$ that enclose it. The
sphere is entirely outside a plane if its center is further than $r$ on the negative side:
$`\mathbf{n}\cdot\mathbf{c} + d < -r`$. If that happens for any of the six planes, the sphere, and the
object in it, is invisible.

Example: a sphere at $(6, 0, 0)$ with radius 1, against the example camera. Its distance to the right
plane is $`-0.9028 \cdot 6 + 4.3005 = -1.116 < -1`$: culled. Projecting its center confirms it: view
$(6, 0, -10)$ gives $x_n = 2.0993 \cdot 6/10 = 1.26$, off the right edge. With radius 1.5 the same
sphere reaches back inside ($-1.116 \ge -1.5$) and is kept.

The test is **conservative**: near a frustum corner a sphere can be outside the frustum and still not
be entirely behind any single plane:

```
                :  left plane, extended past the corner
          .----.:
         /     :\        The sphere crosses the left plane's extension (above the corner)
        |   o  : |       and touches the top plane's extension (left of the corner).
         \     :/        No single plane has it entirely behind, so it is kept,
   .......'----'+------  yet no part of it is inside.   <- top plane
                |
                |   frustum interior (right of the left plane, below the top plane)
                |
```

It never rejects a visible object, which is what matters; a few extra draws in the corners cost
little. An exact test would need distances to the frustum's edges and corners, several times the work
for every object, to save a handful of draws.

### 10.6 The box test: the positive vertex

Some bounds are **axis-aligned boxes** (sides parallel to the axes, given by a minimum and maximum
corner). A box is entirely outside a plane when even its corner furthest along the normal is outside.
Which corner is that? $\mathbf{n}\cdot\mathbf{p} = a x + b y + c z$ grows with $x$ when $a \ge 0$, so the
best $x$ is $x_{max}$ if $a \ge 0$ and $x_{min}$ otherwise; the same per axis. That corner is the
**positive vertex**, found with three comparisons instead of testing all eight corners.
`CullOps.aabbVisible` does exactly this (§20.3). Like the sphere test it is conservative.

## 11. Going backwards: picking

**Picking** answers "what is under the mouse?" It runs the pipeline backwards: from a pixel to a ray
in the world, then from the ray to the first thing it hits.

### 11.1 Window to NDC

Start with the cursor at window coordinates $(p_x, p_y)$, origin top-left, $y$ down, in a window of
size $W \times H$. Invert the viewport transform of §5.3 and flip $y$:

```math
x_n = \frac{2 p_x}{W} - 1, \qquad y_n = 1 - \frac{2 p_y}{H}
```

Check: $p_y = 0$ (the top row) gives $y_n = 1$, the top of NDC. That is the expression in
`DemoGame.handleInput`: `g.x() / ww * 2 - 1, 1 - g.y() / wh * 2`. The cursor is in window coordinates
and so is the size it is divided by (`in.windowWidth()`); on a high-DPI display these differ from
framebuffer pixels, but the ratio is the same.

### 11.2 NDC to a ray: unprojection

A cursor position is not a point but a whole line of points, one per depth. To get the line,
**unproject** two points on it: multiply $(x_n, y_n, z_n, 1)$ by the inverse view-projection matrix and
divide by the fourth coordinate:

```math
\mathbf{q}_h = (P\,V)^{-1} \begin{pmatrix} x_n \\ y_n \\ z_n \\ 1 \end{pmatrix}, \qquad
\mathbf{q} = \frac{(q_{h,x},\, q_{h,y},\, q_{h,z})}{q_{h,w}}
```

Why does this work? $(x_n, y_n, z_n, 1)$ is the clip-space point $(x_c, y_c, z_c, w_c)$ divided by
$w_c$, and homogeneous coordinates do not care about a common factor (§4.3). The inverse maps it to the
world point times some factor, and dividing by the fourth coordinate removes the factor. So the divide
undoes the perspective divide.

`IsoCamera.screenRay` unprojects depth 1 (the near plane) and depth 0.5, takes the first as the ray's
**origin** and the normalized difference as its **direction** (§18.4).

### 11.3 Why depths 1 and 0.5, and not 0

Depth 0 would be the natural "far end" to unproject, but use §4.6's inverse: the view-space point for
clip $(x, y, z, w)$ is $`(x\,a/f,\; y/f,\; -w,\; z/n)`$. At depth $z = 0$ its fourth coordinate is 0:
the point is at infinity, and dividing by it gives infinities and NaN (`Mat4Ops.project` with the
example camera's inverse returns `(NaN, -Infinity, -Infinity)`). Depth 0.5 lies at
$s = n/0.5 = 2n = 1$ m, comfortably finite. For the example camera the two points are
$(0, 5.7, 7.6)$, half a metre in front of the eye, and $(0, 5.4, 7.2)$, one metre in front.

For the orthographic camera the same code works: both unprojected points share the same offset from
the view axis, so the direction is the camera's forward vector for every pixel, and only the origin
moves across the screen.

### 11.4 Hitting a plane

A **ray** is the set of points $`\mathbf{o} + t\,\mathbf{d}`$ for $t \ge 0$: start at the origin
$\mathbf{o}$, go $t$ units along the unit direction $\mathbf{d}$. To hit the plane
$\mathbf{n}\cdot\mathbf{p} + d_0 = 0$, substitute and solve for $t$:

```math
\mathbf{n}\cdot(\mathbf{o} + t\,\mathbf{d}) + d_0 = 0
\quad\Longrightarrow\quad
t = -\frac{\mathbf{n}\cdot\mathbf{o} + d_0}{\mathbf{n}\cdot\mathbf{d}}
```

If $\mathbf{n}\cdot\mathbf{d} = 0$ the ray runs parallel to the plane and never hits it; if $`t < 0`$ the
plane is behind the ray's start. `Intersect.rayPlane` and `Planef.intersect(Ray3f)` are this formula.
For the horizontal ground $y = y_g$ (normal $(0, 1, 0)$, $d_0 = -y_g$) it simplifies to
$t = (y_g - o_y)/d_y$, which is what the controller's `groundPoint` computes (§22.5).

**Worked example.** Take the example camera and the pixel where $(2, 0, 0)$ appeared, NDC
$(0.4199, 0)$. Unprojecting gives the origin $(0.1, 5.7, 7.6)$ on the near plane and the direction
$(0.1961, -0.5883, -0.7845)$. To hit the ground $y = 0$, solve $`5.7 - 0.5883\,t = 0`$: $t = 9.688$,
and the hit point is $`(0.1 + 0.1961 \cdot 9.688,\; 0,\; 7.6 - 0.7845 \cdot 9.688) = (2.0, 0, 0.0)`$.
The round trip closes: world to pixel (§4.7) and back.

### 11.5 The forward direction: projecting a point yourself

Box selection is the opposite problem: given a rectangle on screen, which party members are inside
it? The code projects each member's position itself: four dot products with the rows of the
view-projection matrix, the check $`w_c > 0`$ that the GPU's clipping would have done (a point behind the
camera would otherwise project to a mirrored position, §5.1), the divide, the viewport transform and
the $y$ flip into top-left window coordinates. §26.3 shows `Party.selectBox`.

### 11.6 World positions from the depth buffer

Screen-space passes (ambient occlusion, soft particles, the sky) only have a pixel and the depth
buffer, not the original geometry. They rebuild positions with the same unprojection: texture
coordinates $(u, v) \in [0, 1]^2$ give NDC $(2u - 1, 2v - 1)$, and because of `GL_ZERO_TO_ONE` the stored
depth *is* NDC $z$, with no remapping.

Turning a stored depth into a distance is even simpler: $d = n/s$ inverts to

```math
s = \frac{n}{d}
```

With $n = 0.5$, depth 0.05 is 10 m. It also shows what the clamps of §8.4 mean: depth `0.0001` is
5,000 m and `1e-7` is 5,000,000 m. Very far, but finite.

## 12. Level of detail by projected error

### 12.1 The idea

A mesh comes with several **levels of detail** (LODs): LOD 0 is the full mesh, higher LODs are
simplified versions with fewer triangles. The cooker records each LOD's **geometric error**
$\varepsilon_l$ in mesh units: roughly how far the simplified surface strays from the original. It
comes from meshoptimizer's simplifier, whose relative error the cooker multiplies by the mesh's scale
(`meshopt_simplifyScale`) to get mesh units (chapter [10](10-assets.md)). A simplification is
invisible when its error covers less than about one pixel on screen. So the question is: how many
pixels does a length $\ell$ cover?

### 12.2 Pixels per world unit

In perspective, a length $\ell$ perpendicular to the view axis at view depth $s$ covers $f\ell/s$ in
NDC (from $y_n = f y_v/s$, §4.2). NDC spans 2 units over the $H$ pixels of the viewport, so

```math
\text{pixels} = \frac{H}{2}\cdot\frac{f\,\ell}{s} = \ell\cdot\frac{K}{s}, \qquad K = \frac{H f}{2} = \frac{H}{2\tan(\varphi/2)}
```

$K$ is "pixels per world unit at distance 1". In orthographic mode the NDC size of $\ell$ is $2\ell/h_o$
for a visible height $h_o$, so $\text{pixels} = \ell \cdot H/h_o$, independent of distance.

Both cases are the projection's $y$ scale times half the viewport height: the matrix entry at index 5
(row 1, column 1) is $f$ for the perspective matrix and $2/(t - b) = 2/h_o$ for the orthographic one.
`SceneRenderer.lodPixels` computes exactly `projection[5] * height * 0.5f` (§23.1), which works for
either projection without asking which one it is.

For a 1080-pixel-high viewport and a 30° field of view, $K = 1080/(2\tan 15°) \approx 2015$. At the
demo's distance of 22 m that is $2015/22 \approx 91.6$ pixels per metre, so a LOD whose error is 1 cm
projects to 0.92 pixels: just acceptable. In the default orthographic mode ($h_o = 20$) it is
$1080/20 = 54$ pixels per metre everywhere.

### 12.3 The selection rule

The rule is "use the coarsest LOD whose projected error is at most $T$ pixels", with $T = 1$. With
instance scale $\sigma$ (a mesh scaled up twice has twice the error in world units) and distance $s$,
LOD $l$ is acceptable when

```math
\varepsilon_l \cdot \sigma \cdot \frac{K}{s} \le T \quad\text{(perspective)}, \qquad \varepsilon_l \cdot \sigma \cdot K \le T \quad\text{(orthographic)}
```

Errors grow with the LOD index, so a loop from LOD 1 upwards can stop at the first LOD that is too
coarse. Solving the perspective inequality for $s$ gives the distance at which a LOD becomes usable:
$`s \ge \varepsilon_l\,\sigma\,K/T`$. A LOD with a 2 cm error at 1080p and 30° switches in at
$0.02 \cdot 2015 \approx 40$ m.

### 12.4 Distance against view depth

The formula wants view depth $s$; the code (§23.2) uses the straight-line distance from the eye to the
instance's origin, which is never smaller. For a point seen at angle $\alpha$ off the view axis the
view depth is the distance times $\cos\alpha$, so the two differ by the fraction $1 - \cos\alpha$. With a
30° vertical field of view on a 16:9 screen: 3.4% at the top and bottom edges ($\alpha = 15°$), 9.7% at
the left and right edges ($\alpha = 25.5°$), 12.3% in the corners ($\alpha = 28.7°$). Off-axis objects
therefore get a slightly smaller projected error and may switch LOD slightly earlier. And a
non-uniformly scaled instance is judged by its scale along its local X axis only. Both shortcuts are
cheap and good enough for choosing between LODs whose errors differ by large factors.

## 13. Interpolation between ticks

The game simulates at a fixed 30 Hz and renders as fast as the display allows (DESIGN §5; L00 §11;
chapter [01](01-architecture.md) describes the loop). Without interpolation, moving characters would
visibly step 30 times per second on a 60 or 144 Hz screen. `FixedStepper` keeps the time not yet
simulated in an accumulator, and its `alpha()` is the fraction of the next tick that has already
elapsed, in $[0, 1)$
([`FixedStepper`](../../modules/core/src/main/java/dev/jeddy/core/time/FixedStepper.java)):

```java
    /** Returns the fraction of the next tick already elapsed, in {@code [0, 1)}. */
    public float alpha() {
        return (float) accumulator / stepNanos;
    }
```

The game copies it into the render packet (`p.alpha = ctx.simulation().alpha()`). The packet carries
two transforms per instance, `prevWorld` (the previous tick) and `currWorld` (the latest tick), and
the renderer blends them on all 12 floats while it writes the instance records (D30; §24.3 shows the
loop in `DrawBuilder.writeRecords`):

```math
X = X_{prev} + \alpha\,(X_{curr} - X_{prev})
```

If real time is $\alpha$ of a tick past the latest tick, this shows the world exactly one tick
(33 ms) in the past, smoothly. Blending rotation matrices element by element is not a true rotation
blend (`AffineOps.lerp` says so): halfway between two rotations that differ by an angle $\Delta$, a
rotated vector shrinks to $\cos(\Delta/2)$ of its length (chapter [03](03-rotations-and-transforms.md)
derives this in its section on interpolating transforms). A character turning at `AgentSync`'s
default 10 rad/s turns $\Delta = 1/3$ rad (19°) per tick, so the worst case is
$`\cos(1/6\ \text{rad}) = 0.986`$, a 1.4% squash for one frame while it turns at full speed; an object
spinning once per second ($\Delta = 12°$ per tick) gets $\cos 6° = 0.9945$, 0.5%. Invisible, and much
cheaper than decomposing into quaternions.

What this means for the camera and for culling:

- **The camera is not interpolated.** It is updated once per rendered frame in `Game.update` with the
  real frame time `dt`, so it is already "now". Only simulated objects lag by a tick. When the demo
  follows the party, the follow point is the crowd's current position while the party is drawn
  interpolated; the 0.15 s smoothing hides the difference.
- **Static instances** carry the same transform twice (`World.extract` passes the world matrix as both
  previous and current), and GPU-culled static instances are never interpolated at all.
- **Bounds are not interpolated.** `World.extract` computes each bounding sphere from the current
  tick's transform, while the instance is drawn up to one tick of motion behind it. At 5 m/s that is
  $5/30 \approx 0.17$ m. An object leaving the screen edge could in principle be culled a fraction of a
  frame early; exercise 6 makes the test conservative.
- **Animation poses** advance per frame, not per tick, so they need no interpolation (DESIGN §13.4).

---

# Part B — Building it

Part A derived every formula. Part B writes the code, in the order you would write it from empty
files: first the matrix kernels (§14–17), then the camera that uses them (§18), the GPU state that
reversed-Z needs (§19), the culling kernels and the renderer's culling pass (§20–21), the controller
(§22) and LOD selection (§23). Every block is the repository's code; open the file next to the text.

## 14. Step 1: the matrix kernels and the multiply

### 14.1 Why static methods over float arrays

The math module has three layers (DESIGN §8.1): immutable records such as `Mat4f` for APIs and
gameplay code, mutable scratch types such as `MMat4f`, and **bulk kernels**: static methods over
`float[]` plus an offset. Everything that runs every frame uses the kernels, because they allocate
nothing (L00 §8.2), work on matrices stored anywhere inside a larger array (the offset), and leave the
caller free to keep its matrices in whatever arrays it likes: `IsoCamera` keeps five arrays as public
fields, the render packet keeps copies of them, the shadow code keeps one per cascade.

Start a file `Mat4Ops.java` in `dev.jeddy.math`
([`Mat4Ops.java`](../../modules/math/src/main/java/dev/jeddy/math/Mat4Ops.java)):

```java
/**
 * 4×4 matrix kernels over {@code float[]} with offsets, column-major (element {@code [c * 4 + r]}).
 * Allocation-free; inputs and output may not overlap unless stated (DESIGN §8.1).
 *
 * <p>Thread ownership: stateless, callable from any thread.
 */
public final class Mat4Ops {
    private Mat4Ops() {}

    /** Writes the identity matrix. */
    public static void identity(float[] out, int o) {
        for (int i = 0; i < 16; i++) {
            out[o + i] = (i % 5 == 0) ? 1 : 0;
        }
    }
```

- **`final` class, private constructor**: a holder of static methods, never instantiated.
- **"Thread ownership: stateless"**: every engine type's Javadoc says which thread may call it
  (CLAUDE.md). A class with no fields cannot race, so any thread may.
- **`i % 5 == 0`**: the diagonal of a column-major 4×4 is at indices 0, 5, 10 and 15 (§1.4: row $r$,
  column $r$ is at $5r$), which are exactly the multiples of 5 between 0 and 15.

### 14.2 The multiply

```java
    /** {@code out = a * b}. {@code out} must not overlap {@code a} or {@code b}. */
    public static void mul(float[] a, int ao, float[] b, int bo, float[] out, int o) {
        for (int c = 0; c < 4; c++) {
            float b0 = b[bo + c * 4];
            float b1 = b[bo + c * 4 + 1];
            float b2 = b[bo + c * 4 + 2];
            float b3 = b[bo + c * 4 + 3];
            out[o + c * 4] = a[ao] * b0 + a[ao + 4] * b1 + a[ao + 8] * b2 + a[ao + 12] * b3;
            out[o + c * 4 + 1] = a[ao + 1] * b0 + a[ao + 5] * b1 + a[ao + 9] * b2 + a[ao + 13] * b3;
            out[o + c * 4 + 2] =
                    a[ao + 2] * b0 + a[ao + 6] * b1 + a[ao + 10] * b2 + a[ao + 14] * b3;
            out[o + c * 4 + 3] =
                    a[ao + 3] * b0 + a[ao + 7] * b1 + a[ao + 11] * b2 + a[ao + 15] * b3;
        }
    }
```

Column $c$ of $a \cdot b$ is $a$ times column $c$ of $b$ (chapter [02](02-math-foundations.md), on
matrix products). The loop does exactly that, one column per iteration:

- **The four `b` loads** read column $c$ of $b$ (indices `c * 4` to `c * 4 + 3`, §1.4) into locals,
  once, instead of four times inside the sums.
- **Each output line** is row $r$ of $a$, `(a[r], a[4 + r], a[8 + r], a[12 + r])`, dotted with that
  column. Unrolling the rows by hand gives the JIT four independent sums it can schedule side by side.
- **Why `out` must not overlap `a`**: iteration 0 writes column 0 of `out`; if that is `a`'s column 0,
  iterations 1–3 read the overwritten values. (Overlapping `b` exactly would happen to work, since each
  column of `b` is loaded before the same column of `out` is written, but the contract forbids it so
  the loop order can change; exercise 10.)

`IsoCamera` calls `Mat4Ops.mul(projection, 0, view, 0, viewProjection, 0)`: $P \cdot V$, view first.

The record `Mat4f` offers the same operations as methods that return new values, for code where
clarity beats speed. Its `mul` shows the price: three temporary arrays and a new record per call.

```java
    /** Returns {@code this * o}. */
    public Mat4f mul(Mat4f o) {
        float[] a = new float[16];
        float[] b = new float[16];
        float[] r = new float[16];
        toArray(a, 0);
        o.toArray(b, 0);
        Mat4Ops.mul(a, 0, b, 0, r, 0);
        return fromArray(r, 0);
    }
```

The rest of `Mat4Ops`'s general operations, `mulAffine` (a 4×4 times a row-major 3×4 treated as a 4×4
with last row $(0, 0, 0, 1)$), `transpose` and `determinant`, are chapter [02](02-math-foundations.md)'s
subject. This chapter needs the camera-specific ones.

## 15. Step 2: lookAt

The look-at construction of §3.3, written out on scalars so it allocates nothing and needs no vector
objects:

```java
    /** Writes a right-handed look-at view matrix. */
    public static void lookAt(
            float ex,
            float ey,
            float ez,
            float tx,
            float ty,
            float tz,
            float ux,
            float uy,
            float uz,
            float[] out,
            int o) {
        float fx = tx - ex;
        float fy = ty - ey;
        float fz = tz - ez;
        float fl = (float) (1 / Math.sqrt(fx * fx + fy * fy + fz * fz));
        fx *= fl;
        fy *= fl;
        fz *= fl;
```

The parameters are the eye $\mathbf{e}$, the target $\mathbf{t}$ and the up hint $\mathbf{w}$, named
`(ux, uy, uz)`. The first block is step 1 of §3.3:
$\mathbf{f} = (\mathbf{t} - \mathbf{e})/\lVert \mathbf{t} - \mathbf{e} \rVert$.

- `Math.sqrt` takes and returns a `double`, so `fx * fx + fy * fy + fz * fz` is widened, the reciprocal
  `1 / ...` is computed in double, and one cast brings it back to `float`.
- Computing the reciprocal once and multiplying three times replaces three divisions with one; a
  division costs several times a multiplication.

```java
        float rx = fy * uz - fz * uy;
        float ry = fz * ux - fx * uz;
        float rz = fx * uy - fy * ux;
        float rl = (float) (1 / Math.sqrt(rx * rx + ry * ry + rz * rz));
        rx *= rl;
        ry *= rl;
        rz *= rl;
        float vx = ry * fz - rz * fy;
        float vy = rz * fx - rx * fz;
        float vz = rx * fy - ry * fx;
```

The `r` lines are $\mathbf{f}\times\mathbf{w}$ written out component by component (§2.3's formula with
$\mathbf{a} = \mathbf{f}$, $\mathbf{b} = \mathbf{w}$), then normalized: step 2. The `v` lines are the
true up, $\mathbf{u} = \mathbf{r}\times\mathbf{f}$: step 3, with no normalization because it is
already unit length. (The code names the true up `v` because `u` is taken by the hint's parameters.)
If $\mathbf{f}$ is parallel to the hint, `rx * rx + ry * ry + rz * rz` is 0, `rl` is infinity, and
`0 * infinity` is NaN: §3.5's failure.

```java
        out[o] = rx;
        out[o + 1] = vx;
        out[o + 2] = -fx;
        out[o + 3] = 0;
        out[o + 4] = ry;
        out[o + 5] = vy;
        out[o + 6] = -fy;
        out[o + 7] = 0;
        out[o + 8] = rz;
        out[o + 9] = vz;
        out[o + 10] = -fz;
        out[o + 11] = 0;
        out[o + 12] = -(rx * ex + ry * ey + rz * ez);
        out[o + 13] = -(vx * ex + vy * ey + vz * ez);
        out[o + 14] = fx * ex + fy * ey + fz * ez;
        out[o + 15] = 1;
    }
```

Compare with $V$ of §3.2 and the storage table. `out[o]`, `out[o + 1]`, `out[o + 2]`, `out[o + 3]` are
column 0: rows 0, 1, 2 start with $r_x$, $u_x$ and $-f_x$, and row 3 with 0. Columns 1 and 2 hold the
$y$ and $z$ components the same way. Column 3 (indices 12–14) holds the three dot products
$-\mathbf{r}\cdot\mathbf{e}$, $-\mathbf{u}\cdot\mathbf{e}$ and $\mathbf{f}\cdot\mathbf{e}$. Every one
of the 16 entries is written, so `out` may hold anything before the call.

`Mat4f.lookAt` is the same construction with vector records, `f.cross(up).normalize()` and so on, and
the test `lookAtPutsEyeAtOriginLookingDownMinusZ` checks that the two agree (§28.1).

## 16. Step 3: the two projections

### 16.1 perspectiveReversedZ

```java
    /** Writes an infinite reversed-Z perspective projection (see {@link Mat4f}). */
    public static void perspectiveReversedZ(
            float fovY, float aspect, float near, float[] out, int o) {
        float f = (float) (1.0 / Math.tan(fovY * 0.5));
        for (int i = 0; i < 16; i++) {
            out[o + i] = 0;
        }
        out[o] = f / aspect;
        out[o + 5] = f;
        out[o + 11] = -1;
        out[o + 14] = near;
    }
```

This is §4.6's matrix, entry for entry:

- `f` is $1/\tan(\varphi/2)$ (§4.2). `fovY * 0.5` is a `float` widened to `double` for `Math.tan`; the
  `1.0` literal keeps the division in double; one cast at the end.
- **The zeroing loop** comes first because the method writes only four entries; the array may hold last
  frame's matrix (`IsoCamera` reuses its arrays every frame).
- Index 0 $= f/a$ and index 5 $= f$ scale $x$ and $y$; index 11 (row 3, column 2) $= -1$ copies $-z_v$
  into $w_c$; index 14 (row 2, column 3) $= n$ is the whole depth row $(0, 0, 0, n)$.

There is no `far` parameter: the far plane is at infinity (§4.5). The Javadoc's "see `Mat4f`" points at
the record's documentation of the conventions: "Projections are reversed-Z with depth in `[0, 1]`
(near maps to 1), for use with `glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)` and a `GL_GEQUAL` depth
test."

### 16.2 orthoReversedZ

```java
    /** Writes a reversed-Z orthographic projection (see {@link Mat4f}). */
    public static void orthoReversedZ(
            float left,
            float right,
            float bottom,
            float top,
            float near,
            float far,
            float[] out,
            int o) {
        for (int i = 0; i < 16; i++) {
            out[o + i] = 0;
        }
        float rl = 1 / (right - left);
        float tb = 1 / (top - bottom);
        float fn = 1 / (far - near);
        out[o] = 2 * rl;
        out[o + 5] = 2 * tb;
        out[o + 10] = fn;
        out[o + 12] = -(right + left) * rl;
        out[o + 13] = -(top + bottom) * tb;
        out[o + 14] = far * fn;
        out[o + 15] = 1;
    }
```

§6's formulas: three reciprocals computed once (`rl`, `tb`, `fn`), then $2/(r - l)$ and $2/(t - b)$ on
the diagonal, $1/(F - n)$ at index 10 (the $z_v$ coefficient of the depth row), the three offsets in
column 3, and 1 at index 15 so that $w_c = 1$ and the GPU's divide changes nothing. Here a finite far
plane is needed: depth is linear, so it must end somewhere.

Both builders have record twins, `Mat4f.perspectiveReversedZ(fovY, aspect, near)` and
`Mat4f.orthoReversedZ(...)`, separate implementations of the same formulas.
`reversedZPerspectiveMapsNearToOneAndFarTowardsZero` compares the perspective pair entry by entry with
a tolerance of exactly 0, and checks the orthographic record by the values it projects (§28.1).

## 17. Step 4: project and invert

### 17.1 project: multiply and divide by w

```java
    /**
     * Transforms point {@code (x, y, z, 1)} and writes the perspective-divided result to {@code
     * out[o..o+2]}.
     */
    public static void project(float[] m, int mo, float x, float y, float z, float[] out, int o) {
        float px = m[mo] * x + m[mo + 4] * y + m[mo + 8] * z + m[mo + 12];
        float py = m[mo + 1] * x + m[mo + 5] * y + m[mo + 9] * z + m[mo + 13];
        float pz = m[mo + 2] * x + m[mo + 6] * y + m[mo + 10] * z + m[mo + 14];
        float pw = m[mo + 3] * x + m[mo + 7] * y + m[mo + 11] * z + m[mo + 15];
        float iw = 1 / pw;
        out[o] = px * iw;
        out[o + 1] = py * iw;
        out[o + 2] = pz * iw;
    }
```

The four lines are rows 0–3 of `m` dotted with $(x, y, z, 1)$, the last column added as is because the
fourth coordinate is 1. Then the divide of §5.2, as one reciprocal and three multiplications. Given
the view-projection matrix it goes from world to NDC (§4.7); given its *inverse* it goes from NDC back
to world (§11.2). There is no separate `unproject` kernel: "multiply by a matrix and divide by $w$" is
the same operation in both directions.

`project` does not check $w$. It cannot know what the caller wants for a point behind the camera
($w \le 0$) or at infinity ($w = 0$), so that check is the caller's: `Party.selectBox` skips
$w_c \le 0$ (§26.3), and `IsoCamera.screenRay` only unprojects depths where $w$ is safely positive
(§11.3).

### 17.2 invert

```java
    /**
     * Writes the inverse of {@code m} to {@code out} (may overlap). Returns {@code false}, leaving
     * {@code out} untouched, if {@code m} is singular.
     */
    public static boolean invert(float[] m, int o, float[] out, int oo) {
        float a00 = m[o], a01 = m[o + 1], a02 = m[o + 2], a03 = m[o + 3];
        // ... all 16 inputs into locals, twelve 2×2 sub-determinants b00 .. b11
        float det = b00 * b11 - b01 * b10 + b02 * b09 + b03 * b08 - b04 * b07 + b05 * b06;
        if (det == 0 || !Float.isFinite(det)) {
            return false;
        }
        float inv = 1 / det;
        out[oo] = (a11 * b11 - a12 * b10 + a13 * b09) * inv;
        // ... the other 15 entries the same way
        return true;
    }
```

(Condensed; chapter [02](02-math-foundations.md) derives the full cofactor expansion in its section on
the 4×4 inverse.) Three things matter here:

- **"may overlap"**: unlike `mul`, every input is read into a local before any output is written, so
  `invert(m, 0, m, 0)` inverts in place.
- **The guard**: a matrix with determinant 0 has no inverse. `!Float.isFinite(det)` also rejects NaN and
  infinity, which a NaN-filled matrix (§3.5) would produce. On failure `out` is left untouched and the
  method returns `false`, an allocation-free way of reporting it.
- **The cost**: about 94 multiplications and one division, against the transpose-and-dot-products of a
  rigid inverse (§3.2). The camera pays it once per frame for the inverse view-projection; nothing
  per object pays it.

`IsoCamera.update` ignores the return value: the view-projection of a valid camera is never singular
($\det P = f^2 n/a \ne 0$, §4.6, and a rigid $V$ has determinant 1). If the camera were broken (a
distance of 0 puts the eye on the target, and `lookAt` fills $V$ with NaN), the inverse would keep last
frame's values while the other matrices filled with NaN; nothing would be drawn either way.

## 18. Step 5: the camera, IsoCamera

### 18.1 The class and its fields

Start `IsoCamera.java` in `dev.jeddy.math`
([`IsoCamera.java`](../../modules/math/src/main/java/dev/jeddy/math/IsoCamera.java)):

```java
/**
 * Isometric-style RPG camera math (DESIGN §8.2, §9.4): orbits a target point at a fixed pitch,
 * rotatable yaw and zoom distance, with a narrow-FOV perspective or an orthographic projection.
 * Produces view, projection and view-projection matrices (reversed-Z) and screen-to-world rays for
 * picking. All results go into preallocated arrays.
 *
 * <p>Thread ownership: single owner thread (the game thread builds it; the render packet copies the
 * matrices).
 */
public final class IsoCamera {
    /** Point the camera looks at (usually on the ground). */
    public final MVec3f target = new MVec3f();

    /** Rotation around the vertical axis, radians. 0 looks towards -Z. */
    public float yaw = MathF.PI / 4;

    /** Downward tilt, radians (π/2 looks straight down). */
    public float pitch = MathF.toRadians(55);

    /** Distance from the target along the view direction. */
    public float distance = 20;

    /** Vertical field of view for perspective mode, radians. */
    public float fovY = MathF.toRadians(30);

    /** Near plane distance. */
    public float near = 0.5f;

    /** {@code true} for orthographic projection. */
    public boolean orthographic;

    /** Height of the visible area in orthographic mode, world units. */
    public float orthoHeight = 20;

    /** Far plane in orthographic mode. */
    public float orthoFar = 500;
```

The inputs are **public mutable fields**, not getters and setters. The camera is a small piece of math
owned by one thread; the controller and the game write the fields, then call `update`. `target` is an
`MVec3f`, the math module's mutable vector (DESIGN §8.1), and `final` so that the reference never
changes and callers may keep it. The defaults are §9.1's table.

```java
    /** View matrix (column-major), updated by {@link #update(float)}. */
    public final float[] view = new float[16];

    /** Projection matrix (column-major), updated by {@link #update(float)}. */
    public final float[] projection = new float[16];

    /** View-projection matrix (column-major), updated by {@link #update(float)}. */
    public final float[] viewProjection = new float[16];

    /** Inverse of {@link #viewProjection}, updated by {@link #update(float)}. */
    public final float[] inverseViewProjection = new float[16];

    /** Frustum planes for {@link CullOps}, updated by {@link #update(float)}. */
    public final float[] frustumPlanes = new float[CullOps.PLANES_FLOATS];

    private final MVec3f eye = new MVec3f();
    private final float[] p0 = new float[3];
    private final float[] p1 = new float[3];

    /** Creates a camera with default RPG framing. */
    public IsoCamera() {}

    /** Returns the eye position computed by the last {@link #update(float)}. */
    public MVec3f eye() {
        return eye;
    }
```

The outputs are five arrays allocated once, with the camera, and overwritten by every `update`. They
are public and `final`: readers take the array itself (the render packet copies them with
`System.arraycopy`, §24.2), and nobody can swap one for a different array. `p0` and `p1` are scratch for
`screenRay`. `eye()` returns the internal vector rather than a copy: a copy would allocate every call.
The cost is a convention: callers read it and must not change it.

### 18.2 update: everything a frame needs

```java
    /** Recomputes every matrix for viewport aspect ratio {@code aspect} (width / height). */
    public void update(float aspect) {
        float cp = (float) Math.cos(pitch);
        float sp = (float) Math.sin(pitch);
        float sy = (float) Math.sin(yaw);
        float cy = (float) Math.cos(yaw);
        // Forward is (-sin yaw * cos pitch, -sin pitch, -cos yaw * cos pitch); eye is behind it.
        eye.set(
                target.x + sy * cp * distance,
                target.y + sp * distance,
                target.z + cy * cp * distance);
        Mat4Ops.lookAt(eye.x, eye.y, eye.z, target.x, target.y, target.z, 0, 1, 0, view, 0);
```

The four trigonometric values are computed once and reused. `eye.set(...)` is §9.1's formula
$\mathbf{e} = \mathbf{T} + D(\sin\psi\cos\theta, \sin\theta, \cos\psi\cos\theta)$, term for term. Then
`lookAt` from the eye to the target with world +Y as the up hint builds $V$. Building the eye and then
calling `lookAt` recomputes the forward vector that the comment already knows; the alternative, writing
the view matrix directly from the angles, would save a square root or two per frame and duplicate the
look-at code. Once per frame, clarity wins.

```java
        if (orthographic) {
            float h = orthoHeight * 0.5f;
            float w = h * aspect;
            Mat4Ops.orthoReversedZ(-w, w, -h, h, near, orthoFar, projection, 0);
        } else {
            Mat4Ops.perspectiveReversedZ(fovY, aspect, near, projection, 0);
        }
        Mat4Ops.mul(projection, 0, view, 0, viewProjection, 0);
        Mat4Ops.invert(viewProjection, 0, inverseViewProjection, 0);
        CullOps.extractPlanes(viewProjection, 0, frustumPlanes);
    }
```

- The orthographic box is centered on the view axis: half-height `h`, half-width `h * aspect` (§6).
  Note that `distance` does not appear in it: in orthographic mode only `orthoHeight` sizes the view,
  which is why zooming does nothing there (exercise 7).
- `aspect` is a parameter, not a field, because it belongs to the window, which the camera does not
  know about. The caller passes `pixelWidth / pixelHeight` every frame, so resizing the window just
  works.
- The last three lines produce the derived results: $`P\,V`$ (§14.2), its inverse for picking and shaders
  (§17.2), and the six frustum planes (§20.1).

Everything is written into the preallocated arrays, so calling `update` every frame allocates nothing;
`MatrixAndQuatTest.kernelsDoNotAllocate` proves it (§28.1).

### 18.3 Why the camera computes everything at once

`update` computes five results whether or not anyone needs them this frame. The alternative is lazy
getters that compute on demand and cache. Each result costs at most a few hundred floating-point
operations (one `Mat4Ops.mul` measures 15 ns in the committed benchmark baseline,
`bench/baseline/linux-x64.json`); every frame needs all of them anyway (the renderer culls with the planes
and the shaders use the inverse); and a lazy cache needs invalidation logic whenever any field changes.
Computing everything at the one moment the inputs are known to be complete is simpler and fast enough.

### 18.4 screenRay and unproject

```java
    /**
     * Builds the world-space ray through normalized device coordinates {@code (ndcX, ndcY)} in
     * {@code [-1, 1]} (y up). Writes origin to {@code origin[0..2]} and unit direction to {@code
     * direction[0..2]}.
     */
    public void screenRay(float ndcX, float ndcY, float[] origin, float[] direction) {
        // Reversed-Z: depth 1 is the near plane; 0.5 is a point further along the ray.
        unproject(ndcX, ndcY, 1f, p0);
        unproject(ndcX, ndcY, 0.5f, p1);
        float dx = p1[0] - p0[0];
        float dy = p1[1] - p0[1];
        float dz = p1[2] - p0[2];
        float inv = (float) (1 / Math.sqrt(dx * dx + dy * dy + dz * dz));
        origin[0] = p0[0];
        origin[1] = p0[1];
        origin[2] = p0[2];
        direction[0] = dx * inv;
        direction[1] = dy * inv;
        direction[2] = dz * inv;
    }

    private void unproject(float x, float y, float depth, float[] out) {
        Mat4Ops.project(inverseViewProjection, 0, x, y, depth, out, 0);
    }
```

§11.2–11.3 in code: unproject depth 1 (the near plane) and depth 0.5 (one metre in front of the eye
with $n = 0.5$), take the first as the origin and the normalized difference as the direction. The
results go into caller-supplied arrays, so a picking query allocates nothing. The input is NDC, not
pixels: the camera does not know the window size, so converting pixels (§11.1) is the caller's job.

The ray uses the matrices of the last `update`. A game that moves the camera and picks in the same
frame must decide which camera it means; the demo picks first, with the camera that drew the frame the
player is looking at (§26.1).

<details><summary>Check: why does <code>screenRay</code> start the ray on the near plane rather than at the eye?</summary>

For perspective the two give the same line, but the near-plane point is what unprojecting depth 1
naturally gives, with no special case. For the orthographic camera there is no single eye point: all
rays are parallel, and each starts at its own place on the near plane. One formula serves both
projections.
</details>

## 19. Step 6: switching the GPU to reversed-Z

The matrices of §16 only work if the GPU state agrees with them (§8.1). Five places in the render
module set that state.

### 19.1 Clip control, once per context

[`GpuDevice`](../../modules/render/src/main/java/dev/jeddy/render/gpu/GpuDevice.java) is the
render module's resource layer, created once the GL context is current. It owns the one method that
switches conventions:

```java
    /**
     * Switches clip space to depth [0, 1] with a lower-left origin ({@code glClipControl}), which
     * the reversed-Z projections need (DESIGN §8.2): clear depth to 0 and test {@code GL_GEQUAL}
     * ({@link PipelineState#OPAQUE} does). Call once after creating the context.
     */
    public void useReversedZ() {
        dev.jeddy.gl.ffi.GL46.glClipControl(
                dev.jeddy.gl.ffi.GL46.GL_LOWER_LEFT, dev.jeddy.gl.ffi.GL46.GL_ZERO_TO_ONE);
        dev.jeddy.gl.ffi.GL46.glClearDepthf(0f);
    }
```

- `glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)` is §5.7. `GL_LOWER_LEFT` keeps OpenGL's window
  origin; only the depth convention changes.
- `glClearDepthf(0f)` sets the value a plain `glClear(GL_DEPTH_BUFFER_BIT)` writes. The engine's own
  clears pass their value explicitly (§19.4), so this is a safety net for any code that uses the plain
  call.
- The calls go through the generated `GL46` bindings (L00 §12), written with full names because this
  class does not import them statically.
- It is a method, not part of the constructor, so that a test can create a device without changing the
  context's conventions. In the application, `WorldRenderer.init` calls it right after creating the
  device
  ([`WorldRenderer.java`](../../modules/app/src/main/java/dev/jeddy/app/world/WorldRenderer.java)),
  and so does every GPU test that renders through `SceneRenderer`:

```java
        device = new GpuDevice(caps);
        device.useReversedZ();
```

Clip control is per-context state that nothing else in the engine changes, so once is enough. It must
come before the first draw: everything drawn with GL's default convention would land at window depths
in $(0.5, 1]$.

### 19.2 The depth function, packed into the pipeline state

[`PipelineState`](../../modules/render/src/main/java/dev/jeddy/render/gpu/PipelineState.java) packs
all fixed-function state the engine uses (culling, depth test and write, depth function, blending,
color mask, polygon offset, wireframe, scissor) into one `long`, so a pass can set it all with one
call and the state cache can tell what changed with one XOR. The depth part:

```java
    /** Depth function index: never .. always, in GL order ({@code GL_NEVER + i}). */
    public static final int DEPTH_GEQUAL = 6;

    /** Depth function index for {@code GL_ALWAYS}. */
    public static final int DEPTH_ALWAYS = 7;

    /** Depth function index for {@code GL_EQUAL}. */
    public static final int DEPTH_EQUAL = 2;

    static final int CULL_SHIFT = 0; // 2 bits
    static final long DEPTH_TEST = 1L << 2;
    static final long DEPTH_WRITE = 1L << 3;
    static final int DEPTH_FUNC_SHIFT = 4; // 3 bits
    static final int BLEND_SHIFT = 7; // 3 bits
    static final int COLOR_MASK_SHIFT = 10; // 4 bits (r g b a)
```

GL's eight depth functions are consecutive enum values: `GL_NEVER`, `GL_LESS`, `GL_EQUAL`, `GL_LEQUAL`,
`GL_GREATER`, `GL_NOTEQUAL`, `GL_GEQUAL`, `GL_ALWAYS` are `0x0200` to `0x0207`. So the state stores only
the **index** from `GL_NEVER`, which fits in 3 bits (L00 §4.1: 8 values), and index 6 is `GL_GEQUAL`.
The layout is L00 §6.4's packing: each field at its own shift.

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
```

Work out `OPAQUE` bit by bit: `CULL_BACK` is 1 at bit 0; `DEPTH_TEST` is bit 2 (4); `DEPTH_WRITE` is bit
3 (8); `6 << 4` is `0x60`; the color mask `0xF << 10` is `0x3C00`. Together `0x3C6D` (15,469).
`DEPTH_ONLY` clears the four color-mask bits: `0x6D`. Every preset built from `OPAQUE` (the transparent
one is `0x3CE4`: the depth write and cull bits cleared, blend mode 1 at bit 7) inherits the reversed
test. `FULLSCREEN` (`0x3C00`, the color mask alone) has the depth test off and depth-function index 0,
`GL_NEVER`, which does not matter while the test is off; the sky pass turns the test on with `GEQUAL`
through `withDepth` (§19.5). And one preset records GL's own defaults, which the cache assumes after it
is invalidated:

```java
    /** The GL defaults (what {@link StateCache} assumes after {@link StateCache#invalidate}). */
    public static final long GL_DEFAULT =
            ((long) 1 << DEPTH_FUNC_SHIFT) /* GL_LESS */ | DEPTH_WRITE | (0xFL << COLOR_MASK_SHIFT);
```

Index 1 is `GL_LESS`, GL's initial depth function, which is §8.5's "nothing drawn" if anything ever
draws with it against a buffer cleared to 0.

### 19.3 The state cache issues the call

[`StateCache.apply`](../../modules/render/src/main/java/dev/jeddy/render/gpu/StateCache.java)
compares the requested state with the current one and issues GL calls only for fields that changed
(chapter [05](05-opengl-from-java.md) builds the cache). The depth lines:

```java
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

`diff` is `old ^ newState` (L00 §6.1: XOR is 1 where they differ), and `full` is true right after an
invalidation, when the cache knows nothing. The last block unpacks the 3-bit index with a shift and a
mask (L00 §6.4) and adds `GL_NEVER` to turn it back into the enum. What the driver does with
`glDepthFunc` and `glDepthMask`: it records them as state for the next draws. On most hardware the depth
function is a register of the output units; changing it is cheap, but it is still a call, and §5.6's
hierarchical-Z note is a reason to keep it constant.

### 19.4 The depth target, its clear and the viewport

`SceneRenderer` declares its targets once, as frame-graph resources
([`SceneRenderer.java`](../../modules/render/src/main/java/dev/jeddy/render/SceneRenderer.java)):

```java
        hdr = graph.texture("hdr", GL_RGBA16F, 1f);
        depth = graph.texture("depth", GL_DEPTH_COMPONENT32F, 1f);
        // ...
        graph.addPass("depth prepass", this::depthPrepass).depth(depth, 0f);
        // ...
        FrameGraph.Pass opaque =
                graph.addPass("opaque", this::opaque)
                        .color(hdr, 0, 0, 0, 1)
                        .depthReadOnly(depth)
                        // ... lights, shadows, AO
```

`GL_DEPTH_COMPONENT32F` is the float format of §7.2, at scale `1f` (full resolution). `.depth(depth,
0f)` attaches it to the prepass and asks for a clear to 0, the far end. The opaque pass attaches the
same texture read-only. When the frame graph runs a pass, it binds the pass's framebuffer, sets the
viewport and performs the declared clears
([`FrameGraph.java`](../../modules/render/src/main/java/dev/jeddy/render/graph/FrameGraph.java)):

```java
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
                // ... color clears
                if (p.depthTarget >= 0 && !Float.isNaN(p.depthClear)) {
                    clearValue.set(ValueLayout.JAVA_FLOAT, 0, p.depthClear);
                    glClearNamedFramebufferfv(p.framebuffer, GL_DEPTH, 0, clearValue);
                }
```

- **The viewport** is the target's size: the frame size times the target's scale, so a half-resolution
  target (scale 0.5) gets a half-size viewport and the same NDC covers it (§5.3).
- **"Clears obey the ... depth mask"**: a GL clear writes only what the masks allow. If the previous
  pass left depth writes off (the lit pass does), a depth clear would silently do nothing. The graph
  opens the masks before clearing. This is a classic bug in hand-written renderers.
- **The clear call**: `glClearNamedFramebufferfv` is the direct-state-access clear (L00 §10.5); it
  takes the value through a pointer, here a 16-byte native scratch segment `clearValue` allocated once,
  so the per-frame clear allocates nothing. The value comes from the pass declaration: 0 for the
  prepass.

The shadow maps clear the same way, in their own code: `SunShadows` sets its clear value with the
comment "the far value in reversed-Z" before calling `glClearNamedFramebufferfv(fbo, GL_DEPTH, 0,
clearDepth)`.

### 19.5 The sky at depth 0

The sky pass draws one triangle with the full-screen vertex shader
([`engine/fullscreen.vert`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/engine/fullscreen.vert)):

```glsl
#version 460 core
// One triangle covering the screen; vUv is in [0, 1] over the viewport. Draw 3 vertices with an
// empty vertex array.
layout(location = 0) out vec2 vUv;

void main() {
    vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    vUv = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
```

No vertex buffer at all: the three positions come from the vertex number. For `gl_VertexID` 0, 1, 2,
`(id << 1) & 2` is 0, 2, 0 and `id & 2` is 0, 0, 2, so `p` is (0, 0), (2, 0), (0, 2) and the clip
positions are $(-1, -1)$, $(3, -1)$, $(-1, 3)$: one triangle twice the screen's size whose corner covers
the whole viewport (the guard band of §5.1 discards the rest without clipping). $z_c = 0$, $w_c = 1$:
NDC depth 0. The pass's state in `SceneRenderer.sky`:

```java
        state.apply(
                PipelineState.withDepth(
                        PipelineState.FULLSCREEN, true, false, PipelineState.DEPTH_GEQUAL));
```

Depth test on, write off, `GEQUAL`: the triangle's depth 0 passes only where the buffer still holds the
clear value 0 (§8.3). Why one triangle and not a two-triangle quad? GPUs run fragment shaders on 2×2 blocks of pixels
(so that a shader can compare neighbours, which texture filtering needs), and the blocks along a quad's
diagonal are touched by both triangles and partly shaded twice. One big triangle has no inner edge.

### 19.6 The shadow comparison and the polygon offset

Two more places must agree with reversed-Z, both in the shadow code (chapter
[07](07-lighting-and-shadows.md) explains shadow maps). The shadow sampler compares with `GL_GEQUAL`
(`GpuSamplers`: `glSamplerParameteri(name, GL_TEXTURE_COMPARE_FUNC, GL_GEQUAL)`), and `SunShadows` and
`LocalShadows` push casters away from the light with `glPolygonOffset(-1.5f, -2f)`: negative, because
"away" means "lower depth" now (§8.5). `GpuDeviceTest` asserts both depth comparisons: the state
cache's `GL_DEPTH_FUNC` after applying `OPAQUE`, and the shadow sampler's compare function (§28.5).

## 20. Step 7: the culling kernels, CullOps

### 20.1 The class and extractPlanes

Start `CullOps.java` in `dev.jeddy.math`
([`CullOps.java`](../../modules/math/src/main/java/dev/jeddy/math/CullOps.java)):

```java
/**
 * Frustum culling kernels (DESIGN §9.2/§9.4). Planes are stored as 24 floats: six planes {@code (a,
 * b, c, d)} with {@code a·x + b·y + c·z + d >= 0} inside, normalized so {@code d} is a distance.
 * Order: left, right, bottom, top, near, far.
 *
 * <p>The far plane of an infinite reversed-Z projection is degenerate; it is stored as {@code (0,
 * 0, 0, 1)}, which accepts everything.
 *
 * <p>Thread ownership: stateless, callable from any thread.
 */
public final class CullOps {
    /** Floats in a plane set. */
    public static final int PLANES_FLOATS = 24;

    private CullOps() {}
```

A plane set is a plain `float[24]`: six planes of four floats, in a fixed order. Not six `Planef`
records: the arrays are allocated once (one per camera, one per sun cascade, one reused for the local
lights' views), copied into the render
packet with one `System.arraycopy`, and uploaded to a compute shader as six `vec4`s (§25) with no
conversion. The records `Planef`, `Ray3f`, `Spheref` and `Aabb3f` exist for APIs and gameplay code,
the first layer of DESIGN §8.1; `Planef.intersect(Ray3f)` is §11.4's formula as a method.

```java
    /**
     * Extracts the six planes of a column-major view-projection matrix whose clip depth range is
     * {@code [0, w]} (D3D/zero-to-one convention, which Jeddy's reversed-Z projections use).
     */
    public static void extractPlanes(float[] m, int mo, float[] planes) {
        // Row r of the matrix is (m[r], m[4 + r], m[8 + r], m[12 + r]).
        for (int i = 0; i < 6; i++) {
            float a;
            float b;
            float c;
            float d;
            switch (i) {
                case 0 -> { // left: row3 + row0
                    a = m[mo + 3] + m[mo];
                    b = m[mo + 7] + m[mo + 4];
                    c = m[mo + 11] + m[mo + 8];
                    d = m[mo + 15] + m[mo + 12];
                }
                case 1 -> { // right: row3 - row0
                    a = m[mo + 3] - m[mo];
                    b = m[mo + 7] - m[mo + 4];
                    c = m[mo + 11] - m[mo + 8];
                    d = m[mo + 15] - m[mo + 12];
                }
                case 2 -> { // bottom: row3 + row1
                    a = m[mo + 3] + m[mo + 1];
                    b = m[mo + 7] + m[mo + 5];
                    c = m[mo + 11] + m[mo + 9];
                    d = m[mo + 15] + m[mo + 13];
                }
                case 3 -> { // top: row3 - row1
                    a = m[mo + 3] - m[mo + 1];
                    b = m[mo + 7] - m[mo + 5];
                    c = m[mo + 11] - m[mo + 9];
                    d = m[mo + 15] - m[mo + 13];
                }
                case 4 -> { // z <= w (reversed-Z: the near plane)
                    a = m[mo + 3] - m[mo + 2];
                    b = m[mo + 7] - m[mo + 6];
                    c = m[mo + 11] - m[mo + 10];
                    d = m[mo + 15] - m[mo + 14];
                }
                default -> { // z >= 0 (reversed-Z: the far plane)
                    a = m[mo + 2];
                    b = m[mo + 6];
                    c = m[mo + 10];
                    d = m[mo + 14];
                }
            }
```

This is §10.2's table, one `case` per row. Read one with the storage table: row 3 is
`(m[3], m[7], m[11], m[15])` and row 0 is `(m[0], m[4], m[8], m[12])`, so the left plane's four numbers
are their sums, component by component. The `switch` with arrow cases (Java 14+) needs no `break`; each
case assigns all four locals, which the compiler checks (they are declared without values, so a missing
assignment would not compile).

```java
            float len = (float) Math.sqrt(a * a + b * b + c * c);
            if (len > 1e-12f) {
                planes[i * 4] = a / len;
                planes[i * 4 + 1] = b / len;
                planes[i * 4 + 2] = c / len;
                planes[i * 4 + 3] = d / len;
            } else {
                planes[i * 4] = 0;
                planes[i * 4 + 1] = 0;
                planes[i * 4 + 2] = 0;
                planes[i * 4 + 3] = 1;
            }
        }
    }
```

Normalization (§10.1): divide all four by the normal's length, so the plane test gives distances in
metres and the sphere test can compare with a radius. The `else` branch is §10.3's degenerate far
plane: a normal shorter than $10^{-12}$ is treated as zero, and `(0, 0, 0, 1)` is stored, whose test
value is 1 for every point. Storing a plane that accepts everything keeps every consumer simple: the
sphere test, the batch kernel and the compute shader all loop over six planes with no special case.

The method assumes the zero-to-one clip convention, as its Javadoc says. It also works for orthographic
matrices: their row 3 is $(0, 0, 0, 1)$, and the formulas give the six faces of the box (for the
orthographic example camera the far plane comes out at distance 490 from the target: 500 from the eye,
minus the 10 between eye and target).

### 20.2 sphereVisible

```java
    /** Returns {@code true} if the sphere is at least partly inside the frustum. */
    public static boolean sphereVisible(float[] planes, float cx, float cy, float cz, float r) {
        for (int p = 0; p < 24; p += 4) {
            if (planes[p] * cx + planes[p + 1] * cy + planes[p + 2] * cz + planes[p + 3] < -r) {
                return false;
            }
        }
        return true;
    }
```

§10.5's test: for each plane, the signed distance of the center; if it is below $-r$, the sphere is
wholly outside and the method returns early. The early return is right here, because this version
tests *one* sphere: when it is outside the first plane, the other five need not be read. Its bytecode
is 58 bytes, above HotSpot's `MaxInlineSize` (35) but far below `FreqInlineSize` (325), so C2 inlines it
into a hot caller's loop (chapter [12](12-java-performance.md) explains inlining).

### 20.3 aabbVisible

```java
    /** Returns {@code true} if the box is at least partly inside the frustum (conservative). */
    public static boolean aabbVisible(
            float[] planes,
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ) {
        for (int p = 0; p < 24; p += 4) {
            float a = planes[p];
            float b = planes[p + 1];
            float c = planes[p + 2];
            // Corner furthest along the plane normal.
            float x = a >= 0 ? maxX : minX;
            float y = b >= 0 ? maxY : minY;
            float z = c >= 0 ? maxZ : minZ;
            if (a * x + b * y + c * z + planes[p + 3] < 0) {
                return false;
            }
        }
        return true;
    }
```

§10.6's positive vertex: per axis, the maximum if the normal's component is non-negative, else the
minimum. If even that corner is outside (distance below 0, no radius here), the whole box is. For the
degenerate far plane `(0, 0, 0, 1)` every comparison picks the max corner and the distance is 1: never
outside.

### 20.4 spheres: the batch kernel

The renderer culls thousands of spheres a frame. The batch kernel tests a range of spheres stored as
**struct of arrays** ([Lesson 3](lessons/03-data-layout.md) §4: one array per field, so a loop reads
each field sequentially) and writes the indices of the visible ones:

```java
    /**
     * Tests bounding spheres {@code [start, end)} and appends the indices of visible ones to {@code
     * out} starting at {@code outOffset}. Returns the number written. The compaction is
     * branch-free, so {@code out} needs room for {@code end - start} entries.
     */
    public static int spheres(
            float[] planes,
            float[] cx,
            float[] cy,
            float[] cz,
            float[] r,
            int start,
            int end,
            int[] out,
            int outOffset) {
        float p0a = planes[0], p0b = planes[1], p0c = planes[2], p0d = planes[3];
        float p1a = planes[4], p1b = planes[5], p1c = planes[6], p1d = planes[7];
        float p2a = planes[8], p2b = planes[9], p2c = planes[10], p2d = planes[11];
        float p3a = planes[12], p3b = planes[13], p3c = planes[14], p3d = planes[15];
        float p4a = planes[16], p4b = planes[17], p4c = planes[18], p4d = planes[19];
        float p5a = planes[20], p5b = planes[21], p5c = planes[22], p5d = planes[23];
        int n = outOffset;
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
        return n - outOffset;
    }
```

Three performance habits are visible:

- **The 24 plane values are loaded into locals once**, outside the loop. Inside, the JIT can keep them
  in registers (L00 §9.1): x86-64 has 16 vector registers (32 with AVX-512), enough for most of them,
  instead of re-reading the array for every sphere.
- **The six comparisons use the non-short-circuit `&`.** `&&` would stop at the first failing plane, a
  branch whose outcome the CPU must predict per sphere. With `&` all six comparisons always run and are
  combined with plain AND instructions: more arithmetic, no unpredictable jump.
- **Branch-free compaction.** The loop always writes the index, `out[n] = i`, and then advances the
  output cursor by 0 or 1. An invisible sphere's index is simply overwritten by the next one. The
  alternative, `if (visible) out[n++] = i`, is a branch on visibility. When visibility flips often from
  one object to the next, the CPU's branch predictor guesses wrong often, and a misprediction (about 15
  to 20 cycles of discarded work on current cores) can cost as much as the sphere test itself. This is
  why the Javadoc warns that `out` needs room for `end - start` entries: the kernel writes one past the
  last visible index.

`n += visible ? 1 : 0` looks like a branch in Java, but a choice between the constants 1 and 0
typically compiles to an instruction that turns a comparison result into 0 or 1 (x86's `setcc`), or a
**conditional move**, not a jump. `MathBench.cullSpheres10k` measures the kernel on 10,000 spheres
(§29.2).

### 20.5 aabbs

(Javadoc omitted.)

```java
    public static int aabbs(
            float[] planes,
            float[] minX,
            float[] minY,
            float[] minZ,
            float[] maxX,
            float[] maxY,
            float[] maxZ,
            int start,
            int end,
            int[] out,
            int outOffset) {
        int n = outOffset;
        for (int i = start; i < end; i++) {
            if (aabbVisible(planes, minX[i], minY[i], minZ[i], maxX[i], maxY[i], maxZ[i])) {
                out[n++] = i;
            }
        }
        return n - outOffset;
    }
```

The box version is the simple form: a call per box and a branch on the result. It has no user on a hot
path today (the renderer culls spheres), so nobody has paid to optimize it; the sphere kernel shows how
if somebody needs to.

## 21. Step 8: culling a render packet, Culling

### 21.1 The problem

The renderer does not cull an `IsoCamera`'s arrays; it culls a **render packet**, the snapshot of the
frame the game thread hands to the main thread (chapter [06](06-renderer-architecture.md)). Each
instance has a world bounding sphere in `RenderPacket.bounds` (x, y, z, radius: 4 floats per instance,
interleaved, not struct of arrays) and flag bits in `RenderPacket.flags`. `World.extract` fills the
bounds from the mesh's bounding sphere, transformed by the instance's world matrix and scaled by its
largest axis scale. The same culling serves the main view, the sun cascades and every local light's
shadow view, each with its own planes and filters.

### 21.2 Fields and the overloads

[`Culling.java`](../../modules/render/src/main/java/dev/jeddy/render/draw/Culling.java):

```java
/**
 * Frustum culling of a packet's instances by their world bounding spheres (TASKS M5-09, DESIGN
 * §9.4): a parallel pass over the SoA bounds writes a visibility byte per instance, then a short
 * sequential pass compacts the visible indices in packet order (deterministic).
 *
 * <p>Thread ownership: the render (main) thread calls {@link #cull}; it borrows job workers.
 */
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

    /** Creates a culler; {@code jobs} may be {@code null} to cull on the calling thread. */
    public Culling(JobSystem jobs) {
        this.jobs = jobs;
    }
```

(The comment's "SoA" is the packet's layout: one array per field, `bounds`, `flags`, `mesh` and so
on. Inside `bounds` the four numbers of one instance sit together, the right choice here because the
test always reads all four.)

- **`visibleFlags`**: one byte per instance, the output of the parallel pass (§21.3). It grows when a
  packet has more instances, and is otherwise reused.
- **The per-call fields** (`packet`, `planes`, the flags and the sphere) are the inputs of the kernel.
  They are fields, not parameters, because of how the job system works: a **job kernel** is an object
  with one method `run(start, end, thread)`, and the job system calls it from several threads with
  different ranges. `JobKernel`'s Javadoc spells out the pattern: "Kernels should be long-lived objects
  ... configured through fields before submission, so submitting allocates nothing."
- **`kernel = this::cullRange`**: a method reference bound to `this`, created **once**, in the field
  initializer. Writing `jobs.parallelFor(this::cullRange, ...)` at the call would create a new object
  every frame (CLAUDE.md: no capturing lambdas in hot paths).
- **`jobs` may be null**: tests and tiny packets cull on the calling thread.

The public API is a chain of overloads, each filling in defaults for the next:

```java
    public int cull(RenderPacket p, int[] out) {
        return cull(p, p.frustumPlanes, 0, out);
    }

    public int cull(RenderPacket p, float[] frustumPlanes, int requiredFlags, int[] out) {
        return cull(p, frustumPlanes, requiredFlags, 0, 0, 0, -1, out);
    }

    public int cull(
            RenderPacket p,
            float[] frustumPlanes,
            int requiredFlags,
            float x,
            float y,
            float z,
            float radius,
            int[] out) {
        return cull(p, frustumPlanes, requiredFlags, 0, x, y, z, radius, out);
    }
```

(Javadoc omitted.) The main view culls with the packet's own planes. Shadow views pass their own
planes, which `SunShadows` (one set per cascade) and `LocalShadows` (each light view in turn, into one
reused array) extract with
`CullOps.extractPlanes` from their own view-projection matrices, plus `RenderPacket.FLAG_CASTS_SHADOW`
as a required flag; a point or spot light also passes its range as a sphere (an instance must touch
the light to cast its shadow). A radius below 0 means "no sphere".

### 21.3 The general cull

```java
    /** The general form: also drops instances with any of {@code excludedFlags}. */
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

Two phases:

1. **Parallel test.** The inputs go into the fields, then from 4096 instances up (`PARALLEL_FROM`)
   `parallelFor` runs `cullRange` on the job workers in chunks of 1024 (`GRAIN`), and returns when all
   chunks are done (the calling thread runs chunks too). Each chunk writes only its own range of the
   `visibleFlags` byte array, so no synchronization is needed beyond the job system's own completion.
   Below 4096 instances the calling thread does it alone: waking workers costs microseconds (L00 §9.4),
   and [Lesson 3](lessons/03-data-layout.md) §15 derives when parallel work pays.
2. **Sequential compaction.** The calling thread walks the flags and appends visible indices in packet
   order. A parallel compaction would need an atomic counter shared by all workers, and would produce a
   different order every frame depending on which worker got there first. Sequential compaction costs
   one pass over a byte array and gives the same order every frame: **deterministic**, so two runs
   draw identically and a bug reproduces.

The array grows by doubling (`Math.max(n, length * 2)`), so a growing level reallocates a handful of
times, then never again. `packet` and `planes` are cleared afterwards so the culler does not keep the
packet alive.

### 21.4 cullRange

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

- **Fields into locals first**: `pl`, `b`, `flags` and the rest are read once per chunk. Inside the loop
  the JIT then knows they cannot change, and keeps them in registers.
- **The flag filter** uses masks (L00 §6.2): `(flags & required) == required` is "all required bits
  set", `(flags & excluded) == 0` "no excluded bit set". `&&` is right here: the flags are cheap and
  often decide alone (a shadow view skips every non-caster before touching its bounds).
- **The frustum test** is `CullOps.sphereVisible`, inlined (§20.2).
- **The light's range**: two spheres touch when the distance between their centers is at most the sum
  of their radii. Comparing squares, $\Delta x^2 + \Delta y^2 + \Delta z^2 \le (r_1 + r_2)^2$, avoids a
  square root (both sides are non-negative, so squaring keeps the order).
- **`thread`** is unused here: it is the worker's index for per-thread scratch, which this kernel does
  not need.

Why does `Culling` not use the branch-free `CullOps.spheres`? The bounds here are interleaved and
filtered by flags, and the output is a byte per instance, which the compaction then turns into indices
in order. The two-phase design trades the batch kernel's compaction trick for determinism across
threads.

## 22. Step 9: the controller, IsoCameraController

### 22.1 Fields

`IsoCamera` is math; the controller is behaviour (TASKS M5-09): §9.3–9.6 in code. It lives in the
render module's `camera` package, takes plain numbers rather than input events (so the game decides
which keys and gestures do what), and runs on the game thread
([`IsoCameraController.java`](../../modules/render/src/main/java/dev/jeddy/render/camera/IsoCameraController.java)):

```java
/**
 * Drives an {@link IsoCamera} the way CRPGs do (TASKS M5-09, DESIGN §9.4): keyboard and screen-edge
 * panning relative to the view direction, drag panning that keeps the grabbed ground point under
 * the cursor, smoothed zoom and yaw rotation, following a point, and clamping to the level bounds.
 * It takes plain numbers (no input dependency): the game maps actions to calls.
 *
 * <p>Thread ownership: the game thread (the render packet copies the resulting matrices).
 */
public final class IsoCameraController {
    private final IsoCamera camera;

    /** Pan speed in world units per second at distance 20 (scales with the zoom). */
    public float panSpeed = 18;

    /** Fraction of the screen at each edge that pans the camera (0 disables edge panning). */
    public float edgeMargin = 0.01f;

    /** Smoothing time constant for zoom, rotation and follow, seconds. */
    public float smoothing = 0.12f;

    /** Closest zoom distance. */
    public float minDistance = 6;

    /** Farthest zoom distance. */
    public float maxDistance = 60;

    /** Zoom factor per wheel step. */
    public float zoomStep = 1.15f;
```

The tuning values are public fields, like the camera's: the demo changes three of them (§27).

```java
    private float goalDistance;
    private float goalYaw;
    private float minX = -Float.MAX_VALUE, maxX = Float.MAX_VALUE;
    private float minZ = -Float.MAX_VALUE, maxZ = Float.MAX_VALUE;
    private float panX, panZ; // requested pan direction this frame, view-relative
    private boolean following;
    private float followX, followY, followZ;
    private boolean dragging;
    private final float[] dragAnchor = new float[3];
    private final float[] hit = new float[3];
    private final float[] origin = new float[3];
    private final float[] direction = new float[3];
    private float groundY;
```

- **Goals** (`goalDistance`, `goalYaw`): where zoom and rotation are gliding to (§9.3). The camera's own
  `distance` and `yaw` are where they are now.
- **Bounds** start at $\pm$`Float.MAX_VALUE`, "no limit", so clamping needs no special case.
- **`panX`, `panZ`**: this frame's accumulated pan input, consumed and reset by `update`.
- **Four 3-float scratch arrays** for the drag anchor, the hit point and the ray: allocated once, so
  picking the ground every frame allocates nothing.

### 22.2 Constructor and goals

```java
    /** Controls {@code camera}, starting from its current distance and yaw. */
    public IsoCameraController(IsoCamera camera) {
        this.camera = camera;
        goalDistance = camera.distance;
        goalYaw = camera.yaw;
    }

    /**
     * Takes the camera's current distance (clamped to the zoom limits) and yaw as the goals, after
     * the game set them directly; otherwise the smoothing glides back to the old goals.
     */
    public void syncGoals() {
        goalDistance = MathF.clamp(camera.distance, minDistance, maxDistance);
        goalYaw = camera.yaw;
    }
```

The goals start where the camera is, so nothing moves until input arrives. The trap is a game that sets
`camera.distance` *after* creating the controller: the next `update` would glide back to the old goal.
`syncGoals` adopts the camera's current values; the demo calls it after its setup (§27), and
`syncedGoalsKeepADistanceSetDirectly` tests both behaviours (exercise 8 starts from it).

### 22.3 Input: pan, edge pan, zoom, rotate, follow

```java
    /**
     * Pans this frame: {@code right} and {@code forward} in [-1, 1], relative to the view (forward
     * moves away from the camera on the ground). Cancels following.
     */
    public void pan(float right, float forward) {
        panX += right;
        panZ += forward;
        if (right != 0 || forward != 0) {
            following = false;
        }
    }

    /** Pans when the cursor ({@code x, y} in pixels, y down) touches a screen edge. */
    public void edgePan(float x, float y, float width, float height) {
        if (edgeMargin <= 0) {
            return;
        }
        float mx = width * edgeMargin;
        float my = height * edgeMargin;
        pan(
                x <= mx ? -1 : x >= width - 1 - mx ? 1 : 0,
                y <= my ? 1 : y >= height - 1 - my ? -1 : 0);
    }
```

`pan` **adds** to this frame's input, so the keyboard and the screen edge can both contribute; `update`
normalizes the sum (§22.4). Any pan cancels following: the player took control. `edgePan` turns the
cursor into pan input (§9.5): within `edgeMargin` (1%) of the left edge, $-1$ (pan left); of the right
edge ($x \ge \text{width} - 1 - m$, the last pixel column being $\text{width} - 1$), $+1$. For $y$ the
signs are reversed, because screen $y$ grows downwards and the top edge means forward. A margin of 0 or
less disables it.

```java
    /** Zooms by wheel steps (positive zooms in). */
    public void zoom(float steps) {
        goalDistance =
                MathF.clamp(
                        goalDistance * (float) Math.pow(zoomStep, -steps),
                        minDistance,
                        maxDistance);
    }

    /** Rotates the view by {@code radians} around the target (smoothed). */
    public void rotate(float radians) {
        goalYaw += radians;
    }

    /** Glides the target to a point and keeps it there until the player pans. */
    public void follow(float x, float y, float z) {
        following = true;
        followX = x;
        followY = y;
        followZ = z;
    }

    /** Stops following. */
    public void stopFollowing() {
        following = false;
    }
```

Zoom and rotation change only the **goals**; `update` glides the camera there. `zoom` is §9.4:
`Math.pow(1.15, -steps)` is $1.15^{-\text{steps}}$, so positive steps shrink the distance, and `steps`
is a `float` because touchpads report fractional wheel motion. Clamping the *goal* means the camera
never glides past a limit. `rotate` adds to the goal yaw without wrapping it into $[0, 2\pi)$; sine
and cosine do not care, and a wrap would make the smoothing take the long way round.

### 22.4 update: one frame of behaviour

```java
    /** Applies this frame's input and smoothing, then updates the camera matrices. */
    public void update(float dt, float aspect) {
        lastAspect = aspect;
        float k = smoothing <= 0 ? 1 : 1 - (float) Math.exp(-dt / smoothing);
        camera.distance += (goalDistance - camera.distance) * k;
        camera.yaw += (goalYaw - camera.yaw) * k;
```

- `lastAspect` remembers the aspect ratio for drags, which happen between updates (§22.5). It is a
  field declared just above `update` in the source, initialized to `16f / 9f`.
- `k` is §9.3's $1 - e^{-\Delta t/\tau}$. A smoothing of 0 or less means "no smoothing": $k = 1$ jumps
  straight to the goal (and avoids dividing by zero).
- Distance and yaw step towards their goals by the fraction $k$ of the gap.

```java
        if (panX != 0 || panZ != 0) {
            float len = (float) Math.sqrt(panX * panX + panZ * panZ);
            float scale = panSpeed * (camera.distance / 20f) * dt / Math.max(1, len);
            float s = (float) Math.sin(camera.yaw);
            float c = (float) Math.cos(camera.yaw);
            // Forward on the ground is (-sin yaw, -cos yaw); right is (cos yaw, -sin yaw).
            camera.target.x += (panX * c - panZ * s) * scale;
            camera.target.z += (-panX * s - panZ * c) * scale;
            panX = 0;
            panZ = 0;
        } else if (following) {
            camera.target.x += (followX - camera.target.x) * k;
            camera.target.y += (followY - camera.target.y) * k;
            camera.target.z += (followZ - camera.target.z) * k;
        }
        clampTarget();
        camera.update(aspect);
    }

    private void clampTarget() {
        camera.target.x = MathF.clamp(camera.target.x, minX, maxX);
        camera.target.z = MathF.clamp(camera.target.z, minZ, maxZ);
    }
```

- **Panning** is §9.5. `scale` combines the speed, the zoom factor `distance / 20`, the frame time (so
  the speed is per second, not per frame) and `1 / max(1, len)`, which shrinks diagonal input to
  length 1 but leaves smaller inputs (a gamepad stick half-pushed) alone. The two `target` lines are
  §9.5's $\Delta x$ and $\Delta z$ with `panX` as "right" and `panZ` as "forward". The input is then
  reset: it was this frame's.
- **Following** glides the target with the same $k$, and only when the player is not panning.
- **Clamping** keeps the target inside the level rectangle on the ground. Only $x$ and $z$: height is
  free.
- **`camera.update(aspect)`** last, so the matrices reflect this frame's behaviour.

The whole method allocates nothing: `followGlidesToTheTargetAndFrameUpdatesDoNotAllocate` runs an edge
pan, a zoom and an update under `Allocations.assertNoAllocation` (§28.2).

### 22.5 Drag panning and the ground under the cursor

```java
    /**
     * Starts a drag pan at the cursor (normalized device coordinates, y up): the ground point under
     * it stays under the cursor while dragging.
     */
    public void beginDrag(float ndcX, float ndcY) {
        dragging = groundPoint(ndcX, ndcY, dragAnchor);
        following = false;
    }

    /** Continues a drag pan. */
    public void drag(float ndcX, float ndcY) {
        if (dragging && groundPoint(ndcX, ndcY, hit)) {
            camera.target.x += dragAnchor[0] - hit[0];
            camera.target.z += dragAnchor[2] - hit[2];
            clampTarget();
            camera.update(lastAspect);
        }
    }

    /** Ends a drag pan. */
    public void endDrag() {
        dragging = false;
    }
```

§9.6 in code: remember the anchor; on each move find where the cursor's ray hits the ground now and
shift the target by the difference. `dragging` is only set if the first ray hit the ground at all
(clicking the sky starts no drag). `drag` updates the camera immediately with `lastAspect`, so the next
`groundPoint` already sees the moved camera. If the target is clamped at the level's edge, the anchor
slides off the cursor: the bounds win.

```java
    /**
     * Writes the ground point under the cursor (NDC, y up) to {@code out}; returns {@code false} if
     * the ray misses the ground plane. Uses the matrices of the last {@link #update}.
     */
    public boolean groundPoint(float ndcX, float ndcY, float[] out) {
        camera.screenRay(ndcX, ndcY, origin, direction);
        if (Math.abs(direction[1]) < 1e-6f) {
            return false;
        }
        float t = (groundY - origin[1]) / direction[1];
        if (t < 0) {
            return false;
        }
        out[0] = origin[0] + direction[0] * t;
        out[1] = groundY;
        out[2] = origin[2] + direction[2] * t;
        return true;
    }
```

§11.4's ray–plane intersection for the horizontal plane $y = $ `groundY` (set with `setGroundHeight`):
$t = (y_g - o_y)/d_y$. A ray almost parallel to the ground ($`\lvert d_y \rvert < 10^{-6}`$) has no
useful hit, and a negative $t$ means the ground is behind the ray's start (a ray pointing at the sky).
`out[1]` is set to exactly `groundY` rather than computed, avoiding a rounding error in the one
coordinate known exactly.

### 22.6 lodPixels and the rest

```java
    /**
     * Pixels per world unit at distance 1 for LOD selection ({@code DrawBuilder.setLod}), for a
     * viewport {@code heightPixels} tall.
     */
    public float lodPixels(float heightPixels) {
        return camera.orthographic
                ? heightPixels / camera.orthoHeight
                : heightPixels / (2f * (float) Math.tan(camera.fovY / 2f));
    }
```

§12.2's $K$ from the camera's parameters: $H/h_o$ for orthographic, $H/(2\tan(\varphi/2))$ for
perspective. The renderer computes the same number from the packet's projection matrix instead
(`SceneRenderer.lodPixels`, §23.1), because it receives matrices, not an `IsoCamera`; this method is
for game code that wants the value on the game thread, and for the controller's test. The remaining
methods are one-liners: `camera()` returns the controlled camera, `setBounds(minX, minZ, maxX, maxZ)`
stores the level rectangle, `setGroundHeight(y)` the drag plane's height.

What the controller deliberately does not do: read input devices (the game maps them; the render
module does not depend on the `input` module at all), animate pitch (fixed, §9.2), or collide the camera with
walls (the camera floats above the level; occluding walls fade out instead, with the camera fade of
DESIGN §9.3).

## 23. Step 10: choosing the level of detail

### 23.1 The input: pixels per unit, from the projection

The renderer turns §12.2 into one line
([`SceneRenderer.java`](../../modules/render/src/main/java/dev/jeddy/render/SceneRenderer.java)):

```java
    private float lodPixels(RenderPacket p) {
        // Pixels per world unit: orthographic from the projection's y scale; perspective at
        // distance 1.
        return p.projection[5] * height * 0.5f;
    }
```

Index 5 is row 1, column 1 (§1.4): $f$ for the perspective matrix, $2/h_o$ for the orthographic one.
Times half the viewport height, it is $K = Hf/2$ or $H/h_o$, without asking which projection it is.
`SceneRenderer.render` hands it to the draw builder once per frame, together with the projection type
and a threshold of 1 pixel:

```java
        int n = cullMainView(p);
        float lod = lodPixels(p);
        draws.setLod(lod, p.orthographic, 1f);
        draws.build(p, visible, n, ring);
```

and passes the same `lod` value to the outlines, the sun shadows and the local shadows, so a mesh uses
the same detail in every view of the same frame.

### 23.2 Distance and scale per instance

`DrawBuilder.setLod` stores the three values in fields; the work happens in `buildItems`, the kernel
that turns each visible instance into draw items, on the job workers
([`DrawBuilder.java`](../../modules/render/src/main/java/dev/jeddy/render/draw/DrawBuilder.java);
Javadoc omitted):

```java
    public void setLod(float pixels, boolean ortho, float threshold) {
        pixelsPerUnit = pixels;
        orthographic = ortho;
        thresholdPixels = threshold;
    }
```

```java
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

`world` is the packet's `currWorld`: 12 floats per instance, row-major 3×4 (§1.2). Each row is
`(m0, m1, m2, t)`, so indices 3, 7 and 11 are the translation, the instance's origin; `dx, dy, dz` is
the vector from the eye to it and `distance` its length (§12.4's straight-line distance). Indices 0, 4
and 8 are the first **column** of the 3×3 part, the image of the local X axis, and its length is the
instance's scale along X. Using the current tick's transform, not the interpolated one, is fine: a LOD
choice does not need a 33 ms-accurate distance.

### 23.3 selectLod

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

§12.3's inequality, rearranged so the loop does one multiplication per LOD: `k` is "pixels per mesh
unit" for this instance, $\sigma K/s$ (or $\sigma K$ in orthographic mode), computed once. `Math.max(distance,
1e-3f)` keeps an instance at the eye from dividing by zero. LOD 0 is always acceptable; the loop tries
LOD 1, 2, … and stops at the first whose projected error exceeds the threshold, keeping the last one
that passed. The `break` relies on errors growing with the LOD index, which the cooker's construction
gives: each LOD is simplified from the full mesh to a smaller triangle budget than the one before it,
so it strays further.

The chosen LOD goes into the draw item's 64-bit sort key (4 bits), next to the mesh, so instances of
the same mesh at the same LOD become one run of one indirect draw (chapter
[06](06-renderer-architecture.md) covers the keys). The eye distance feeds the keys too: opaque items
carry `distance * 64` in 16 bits (front to back, in 1/64 m steps, to help the early depth test of
§5.5), translucent ones a 32-bit back-to-front distance (blending needs the far ones first).

### 23.4 The same formula on the GPU

Static instances are culled and LOD-selected by a compute shader (§25). It uses the identical formula,
with the pixels-per-unit value in the `w` of the eye uniform and each item's scale precomputed when the
static scene is uploaded
([`cull/static_cull.comp`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/cull/static_cull.comp)):

```glsl
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

`rows[r].w` is the translation, as on the CPU; `uParams.y` is the orthographic flag; the threshold
travels as the bits of a float inside an integer vector (`uintBitsToFloat`). The eight LOD errors are
packed in two `vec4`s, so error `l` is component `l & 3` of vector `l >> 2` (`& 3` is `mod 4`,
L00 §6.5; `>> 2` divides by 4, L00 §6.3). `StaticSceneTest.farInstancesUseCoarserLods` checks that the GPU picks
LOD 1 for a far quad and LOD 0 for a near one (§28.4).

---

# Part C — Using it and proving it

## 24. One frame, end to end

### 24.1 The game thread moves the camera and fills the packet

The [game template](../../samples/game-template/src/main/java/com/example/mygame/MyGame.java) shows the
minimal wiring. In `update`, once per frame on the game thread, the input gestures drive the
controller, and the controller updates the camera:

```java
        var g = ctx.gestures();
        controller.pan(g.edgeScrollX(), -g.edgeScrollY());
        controller.zoom(g.zoomDelta());
        controller.rotate(g.rotateDelta() * 0.006f);
        var in = ctx.input();
        controller.update(
                dt, in.pixelHeight() > 0 ? (float) in.pixelWidth() / in.pixelHeight() : 1);
```

The input module's `CrpgGestures` reports edge scrolling as $-1$ (top) to $1$ (bottom), so the minus
sign turns "top" into "forward". `rotateDelta` is the mouse's horizontal motion while the rotate
button is held, in window coordinates, and 0.006 converts it to radians (a 1000-pixel drag turns the
view 6 rad, about one turn). The aspect ratio comes from the framebuffer size in pixels, guarded
against a minimized window whose height is 0.

In `extract`, the last step of the frame, the game copies the finished camera into the render packet
for the main thread:

```java
        p.alpha = ctx.simulation().alpha();
        p.setCamera(
                camera.view,
                camera.projection,
                camera.viewProjection,
                camera.inverseViewProjection,
                camera.frustumPlanes,
                camera.eye().x,
                camera.eye().y,
                camera.eye().z);
        p.near = camera.near;
        p.far = camera.distance * 2 + 60;
        p.orthographic = camera.orthographic;
```

### 24.2 The packet takes copies

[`RenderPacket.setCamera`](../../modules/render/src/main/java/dev/jeddy/render/RenderPacket.java)
copies the arrays, it does not keep references (Javadoc omitted):

```java
    public void setCamera(
            float[] viewMatrix,
            float[] projectionMatrix,
            float[] viewProjectionMatrix,
            float[] inverseViewProjectionMatrix,
            float[] planes,
            float eyeX,
            float eyeY,
            float eyeZ) {
        System.arraycopy(viewMatrix, 0, view, 0, 16);
        System.arraycopy(projectionMatrix, 0, projection, 0, 16);
        System.arraycopy(viewProjectionMatrix, 0, viewProjection, 0, 16);
        System.arraycopy(inverseViewProjectionMatrix, 0, inverseViewProjection, 0, 16);
        System.arraycopy(planes, 0, frustumPlanes, 0, CullOps.PLANES_FLOATS);
        eye[0] = eyeX;
        eye[1] = eyeY;
        eye[2] = eyeZ;
    }
```

This is the thread hand-off. The game thread keeps changing its `IsoCamera` next frame while the main
thread renders this one; with references, the main thread could read a half-updated matrix. Copies into
the packet's own preallocated arrays (91 floats, 364 bytes, with the eye) make the packet a consistent snapshot, and
the packet is handed over whole through the frame pipeline (L00 §9.5, chapter
[01](01-architecture.md)). `near`, `far` and `orthographic` travel too: the light clusters and shadow
cascades need the near plane and the "far" range (§8.4), and the shaders need to know whether every
pixel looks in the same direction.

### 24.3 The render thread culls, picks LODs and interpolates

`SceneRenderer.render` runs on the main thread. Its camera-related steps are §23.1's four lines: cull
the main view, compute the pixels per unit, set up LOD selection, build the draws. The culling step:

```java
    /**
     * Culls the main view on the CPU: everything, or with GPU culling the dynamic instances plus
     * the static ones the GPU path leaves to the CPU (blended materials).
     */
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

Without GPU culling this is one call: the packet's planes, no required flags, no excluded flags, no
light sphere (radius $-1$). With GPU culling active, static instances are excluded
(`FLAG_STATIC` as the *excluded* flag), because the compute shader culls them (§25), except a short
list the GPU path cannot draw: static instances with blended materials, which must be sorted back to
front every frame. Those few are tested here one by one with `sphereVisible` and appended.

`DrawBuilder.build` then writes one 64-byte instance record per draw item into the frame ring
([Lesson 1](lessons/01-buffers-in-flight.md) §14.1). While writing, the job workers blend the two
ticks' transforms (§13, D30):

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
            // ... material, flags, entity and fade into bytes 48 to 63
        }
    }
```

The inner loop is §13's $X_{prev} + \alpha(X_{curr} - X_{prev})$ on the 12 floats, written straight
into the mapped ring with no intermediate array. These are the rows `jeddyTransformPoint` dots with
the vertex (§1.2).

### 24.4 The frame constants

The matrices reach the shaders through one uniform block, the **frame constants** (uniform binding 0,
544 bytes), which `SceneRenderer.writeFrameConstants` writes once per frame into the frame ring
(condensed: the rest of the block is chapter [07](07-lighting-and-shadows.md)'s):

```java
    private long writeFrameConstants(RenderPacket p) {
        long o =
                ring.allocate(
                        FRAME_BYTES, Math.max(16, device.caps().uniformBufferOffsetAlignment()));
        // ... (ring full: exception)
        MemorySegment m = ring.memory();
        putMatrix(m, o, p.view);
        putMatrix(m, o + 64, p.projection);
        putMatrix(m, o + 128, p.viewProjection);
        putMatrix(m, o + 192, p.inverseViewProjection);
        Mat4Ops.invert(p.projection, 0, scratch, 0);
        putMatrix(m, o + 256, scratch);
        putVec4(m, o + 320, p.eye[0], p.eye[1], p.eye[2], p.time);
        putVec4(m, o + 336, width, height, 1f / width, 1f / height);
        float near = Math.max(1e-3f, p.near);
        float far = Math.max(near * 2, p.far);
        // ... cluster slice scale and bias from near and far, then the sun, environment, counts
    }

    private static void putMatrix(MemorySegment m, long o, float[] v) {
        MemorySegment.copy(v, 0, m, ValueLayout.JAVA_FLOAT, o, 16);
    }
```

Because `Mat4` is column-major in Java *and* in GLSL, `putMatrix` is one bulk copy of 16 floats, with
no transposing (D21). The inverse projection is computed here, per frame, from the packet's projection
(the packet does not carry it). The camera part of the block, matching
[`engine/frame.glsl`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/engine/frame.glsl):

| Offset | GLSL member | Written from |
|---|---|---|
| 0 | `mat4 jView` | `p.view` |
| 64 | `mat4 jProjection` | `p.projection` |
| 128 | `mat4 jViewProjection` | `p.viewProjection` |
| 192 | `mat4 jInverseViewProjection` | `p.inverseViewProjection` |
| 256 | `mat4 jInverseProjection` | `Mat4Ops.invert(p.projection, ...)`, computed per frame |
| 320 | `vec4 jEye` | eye position, time in `w` |
| 336 | `vec4 jViewport` | width, height, 1/width, 1/height |
| 352 | `vec4 jClusterZ` | near, far, cluster slice scale and bias |
| 444 | `uvec4 jCounts`, its `w` | bit 0: orthographic |

The offsets follow the **std140** layout rules (chapter [05](05-opengl-from-java.md)): a `mat4` takes
64 bytes, a `vec4` 16, in declaration order, so five matrices end at 320. Bit 0 of `jCounts.w` is read
by `jeddyOrthographic()`:

```glsl
bool jeddyOrthographic() {
    return (jCounts.w & 1u) != 0u;
}
```

### 24.5 Shaders that run the pipeline backwards

[`engine/frame.glsl`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/engine/frame.glsl)
writes §11.2 and §11.6 once, for every shader that includes it:

```glsl
// World-space position of a pixel at window depth d (reversed-Z, depth in [0, 1]).
vec3 jeddyWorldFromDepth(vec2 uv, float d) {
    vec4 p = jInverseViewProjection * vec4(uv * 2.0 - 1.0, d, 1.0);
    return p.xyz / p.w;
}

// Positive view-space distance along -Z of a world position.
float jeddyViewDepth(vec3 world) {
    return -(jView * vec4(world, 1.0)).z;
}
```

`uv * 2.0 - 1.0` turns texture coordinates in $[0, 1]$ into NDC; because of `GL_ZERO_TO_ONE` the
stored depth *is* NDC $z$ and needs no remapping (with the default convention you would write
`d * 2.0 - 1.0`). `jeddyViewDepth` is the view-space $s$ of §4.1: row 2 of $V$ gives $z_v$, and the
minus sign makes it positive. The lit pass uses it to find the pixel's light-cluster slice
(`pbr.frag`), and particles use it for soft edges (`particle.vert`).

Passes that work in view space use `jInverseProjection` instead, for example the ambient-occlusion pass
([`post/gtao.frag`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/post/gtao.frag)):

```glsl
vec3 viewPos(vec2 uv) {
    // Sky texels (reversed-Z 0, infinite far plane) become very distant points, not infinities.
    float d = max(textureLod(uDepth, uv, 0.0).r, 1e-7);
    vec4 p = jInverseProjection * vec4(uv * 2.0 - 1.0, d, 1.0);
    return p.xyz / p.w;
}
```

The clamp is §8.4's "depth 0 is at infinity" rule: $10^{-7}$ is $n/10^{-7} = 5{,}000{,}000$ m away with
$n = 0.5$ (§11.6), far but finite. `gtao_blur.frag` and `particle.frag` clamp the same way.

The sky shader ([`pbr/sky.frag`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/pbr/sky.frag))
needs a direction per pixel:

```glsl
void main() {
    vec3 far = jeddyWorldFromDepth(vUv, 0.0001);
    vec3 dir = jeddyOrthographic()
            ? -vec3(jView[0][2], jView[1][2], jView[2][2])
            : normalize(far - jEye.xyz);
    outColor = vec4(textureLod(jEnvironmentMap, dir, 0.0).rgb * jEnvironment.x, 1.0);
}
```

In perspective, the direction from the eye to a point far along the pixel's ray (depth `0.0001`, 5 km).
In orthographic mode every pixel looks the same way, the camera's forward vector, read straight out of
the view matrix: GLSL indexes matrices `[column][row]`, so `jView[0][2], jView[1][2], jView[2][2]` is
row 2's first three entries, $-\mathbf{f}$ (§3.2), and the minus sign gives $\mathbf{f}$.

## 25. GPU culling of static instances

Static instances never move, so Jeddy keeps them resident on the GPU and culls them there with a
compute shader (TASKS M7-11, D49). One invocation tests one (instance, submesh) item with the same
plane test as `sphereVisible`, against the same 24 floats, uploaded as `uniform vec4 uPlanes[6]`
([`cull/static_cull.comp`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/cull/static_cull.comp)):

```glsl
    vec4 b = it.bounds;
    for (int p = 0; p < 6; p++) {
        if (dot(uPlanes[p].xyz, b.xyz) + uPlanes[p].w < -b.w) {
            return;
        }
    }
```

A `vec4` plane is $(a, b, c, d)$ and a `vec4` sphere is (center, radius), so the test is one `dot` and
one comparison per plane; the degenerate far plane `(0, 0, 0, 1)` passes as it does on the CPU.
Surviving items pick their LOD with §23.4's loop and append themselves to the indirect draw command of
their (bin, LOD) with `atomicAdd`, which hands each invocation a unique slot even though thousands run
at once:

```glsl
    uint command = bin.info.w + lod;
    uint slot = atomicAdd(commands[command * 5u + 1u], 1u);
```

`commands[command * 5 + 1]` is the instance count of a 5-integer `DrawElementsIndirectCommand`; the
old value `atomicAdd` returns is this item's slot in that command's run of records. The GPU's order of
invocations is not deterministic, so the order of records within a run varies from frame to frame;
for opaque geometry drawn with a depth test it does not matter. (The CPU path in §21.3 *is*
deterministic, because there it costs only one sequential pass over a byte array.)

`StaticSceneTest.gpuCulledStaticInstancesLookLikeTheCpuPath` asserts that "GPU and CPU frustum tests
agree" on the number of drawn instances (§28.4). How the bins, commands and
`glMultiDrawElementsIndirectCount` fit together, and the memory barriers between the compute pass and
the draws, are chapter [06](06-renderer-architecture.md)'s and
[Lesson 1](lessons/01-buffers-in-flight.md) §15's subject. The measured gain: on 20,000 instances the
main thread's frame preparation went from 2.2 ms to 0.33 ms (TASKS M7-11, measured with llvmpipe, which
runs the compute shader on the CPU inside the GL calls; real-GPU confirmation is part of M10).

## 26. Picking and selection in the demo

### 26.1 What is under the cursor

The demo needs more than a plane: a cursor over a skeleton must pick the skeleton, and a cursor over a
wall must not pick the floor behind it. `DemoGame.handleInput` builds the ray with §11.1's conversion
and sends it into the physics module
([`DemoGame.java`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/DemoGame.java)):

```java
        // What is under the cursor.
        camera.screenRay(g.x() / ww * 2 - 1, 1 - g.y() / wh * 2, rayOrigin, rayDirection);
        int kind =
                level.collision.pick(
                        rayOrigin[0],
                        rayOrigin[1],
                        rayOrigin[2],
                        rayDirection[0],
                        rayDirection[1],
                        rayDirection[2],
                        400,
                        Level.UNITS,
                        0,
                        0.3f,
                        pickQuery);
        int unit = kind == CollisionWorld.PICK_UNIT ? pickQuery.entity : -1;
        groundValid = kind == CollisionWorld.PICK_GROUND;
```

`ww` and `wh` are the window size, `Math.max(1, ...)` so a minimized window cannot divide by zero.
`rayOrigin`, `rayDirection` and `pickQuery` are fields allocated once: picking every frame allocates
nothing.

### 26.2 CollisionWorld.pick

`CollisionWorld.pick` (in `physics.query`) casts the ray, up to 400 m, against the bodies in the unit
layers (here `Level.UNITS`, with radii grown by 0.3 m so small targets are easy to click), the
interactable layers (none in this call: the mask is 0) and the static collision geometry, and returns
the nearest hit. It reports `PICK_GROUND` only if the nearest surface is a walkable triangle, so
clicking a wall gives `PICK_NONE` instead of a floor point behind it. Body tests use
`Intersect.raySphere`, `Intersect.rayVerticalCylinder` and `Intersect.rayAabb` (for boxes turned about
the vertical axis, by rotating the ray into the box's frame first); the static triangles go through a
bounding volume hierarchy. Chapter [11](11-physics-navigation-crowds.md) covers them.

`handleInput` runs before `updateCamera` in the demo's `update`, so it picks with the matrices of the
previous frame's camera update: those of the frame on screen, which is what the player is pointing at.

### 26.3 Box selection: projecting points yourself

Box selection is §11.5. `Party.selectBox` projects each member's chest point (0.9 m above the feet)
with the view-projection matrix, reading the rows straight out of the column-major array
([`Party.java`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/Party.java)):

```java
            float x = crowd.x(a);
            float y = crowd.y(a) + 0.9f;
            float z = crowd.z(a);
            float cw = vp[3] * x + vp[7] * y + vp[11] * z + vp[15];
            if (cw <= 0) {
                continue;
            }
            float sx = ((vp[0] * x + vp[4] * y + vp[8] * z + vp[12]) / cw + 1) * 0.5f * windowWidth;
            float sy =
                    (1 - (vp[1] * x + vp[5] * y + vp[9] * z + vp[13]) / cw) * 0.5f * windowHeight;
```

Every piece is in this chapter: $w_c$ from row 3 (`vp[3], vp[7], vp[11], vp[15]`), the check
$`w_c > 0`$ that the GPU's clipping would have done (§5.1), the divide, the viewport transform (§5.3)
and the $y$ flip into top-left window coordinates, `1 - y_n`, so the result compares directly with the
mouse rectangle. Only rows 0, 1 and 3 are computed: depth is not needed.

### 26.4 The editor

The editor picks with the same ray but tests it against each renderable's oriented bounding box,
through the inverse of its world transform, after a bounding-sphere reject (`World.pick`, D93); chapter
[14](14-editor.md) builds it.

## 27. The camera in the demo

The iso demo ([`DemoGame`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/DemoGame.java))
creates its camera and controller as fields, then tunes them in `init`:

```java
        camera.distance = options.perf() ? 30 : 22;
        camera.yaw = MathF.PI * 0.25f;
        camera.pitch = MathF.toRadians(52);
        controller.minDistance = 8;
        controller.maxDistance = 48;
        controller.smoothing = 0.15f;
        controller.syncGoals(); // keep the distance and yaw set above
```

A starting distance of 22 (30 in `--perf` runs, to see more of the crypt), yaw 45°, pitch 52°, zoom
limits 8–48 and smoothing 0.15 s. The controller was created before these lines, with the default
distance 20 as its goal, hence `syncGoals` (§22.2). When the level is built, the bounds come from its
size and the target starts at its center:

```java
        controller.setBounds(
                level.minX, level.minZ, level.minX + level.sizeX, level.minZ + level.sizeZ);
        controller.setGroundHeight(0);
        camera.target.set(center[0], center[1], center[2]);
```

Every frame, `updateCamera` maps W/S/A/D and the arrow keys plus the screen edges to `pan`, the wheel
to `zoom`, Q and E (1.6 rad/s) and the middle-button drag to `rotate`, and, while following is on (the
C key toggles it), feeds the party's center to `follow`; in `--perf` runs the camera orbits slowly by
itself (0.12 rad/s) and follows the party. The end of the method:

```java
        int pw = in.pixelWidth();
        int ph = in.pixelHeight();
        controller.update(dt, ph > 0 ? (float) pw / ph : 16f / 9f);
```

The demo does not use `edgePan` or the drag pan: its edge scrolling comes from `CrpgGestures`, and the
right and left buttons are for orders and selection. `extract` copies the camera exactly like the
template (§24.1), with `p.far = cam.distance * 2 + 60`; while the editor is open it copies the editor's
own `IsoCamera` instead.

## 28. The tests, walked through

### 28.1 MatrixAndQuatTest (math module, no GPU)

[`MatrixAndQuatTest`](../../modules/math/src/test/java/dev/jeddy/math/MatrixAndQuatTest.java) holds
the chapter's math tests.

`reversedZPerspectiveMapsNearToOneAndFarTowardsZero` checks §4.5 and §6 with numbers:

```java
        Mat4f p = Mat4f.perspectiveReversedZ(MathF.toRadians(60), 16f / 9f, 0.5f);
        assertEquals(1f, p.project(new Vec3f(0, 0, -0.5f)).z(), EPS);
        assertEquals(0.5f, p.project(new Vec3f(0, 0, -1f)).z(), EPS);
        assertTrue(p.project(new Vec3f(0, 0, -1e6f)).z() < 1e-6f);
        Mat4f o = Mat4f.orthoReversedZ(-10, 10, -5, 5, 1, 101);
        assertEquals(1f, o.project(new Vec3f(10, 5, -1)).z(), EPS);
        assertEquals(0f, o.project(new Vec3f(-10, -5, -101)).z(), EPS);
        assertEquals(1f, o.project(new Vec3f(10, 5, -1)).x(), EPS);
        float[] arr = new float[16];
        Mat4Ops.perspectiveReversedZ(MathF.toRadians(60), 16f / 9f, 0.5f, arr, 0);
        assertTrue(Mat4f.fromArray(arr, 0).approxEquals(p, 0));
```

With $n = 0.5$: distance 0.5 gives depth 1, distance 1 gives 0.5, a million metres less than
$10^{-6}$ ($n/s = 5 \times 10^{-7}$). For the orthographic box the near top-right corner
$(10, 5, -1)$ lands at $x_n = 1$ with depth 1, and the far corner $(-10, -5, -101)$ at depth 0. The last
line compares the kernel with the record with a tolerance of 0: they must agree to the bit.

`lookAtPutsEyeAtOriginLookingDownMinusZ` checks §3's two properties on an arbitrary camera, eye
$(3, 10, 7)$ and target $(-2, 0, 1)$: the eye maps to the origin, and the target to
$(0, 0, -\lVert \mathbf{t} - \mathbf{e} \rVert)$, straight ahead at its true distance. It also checks
that the `float[]` kernel and the `Mat4f` record agree to $10^{-4}$.

`cameraCullingAndPicking` builds a default `IsoCamera` aimed at $(10, 0, -5)$ and checks:

- culling: a sphere at the target is visible, one far behind the camera (at $z = 500$) and one far to
  the side (at $x = 500$) are culled, and a box around the target is visible;
- picking: `screenRay(0, 0, ...)`, the screen center, intersected with the ground by
  `Intersect.rayPlane`, hits $x = 10$, $z = -5$ to 0.01; then the same in orthographic mode;
- the batch kernel: 1000 spheres, every other one 1000 m away, run through `CullOps.spheres`: exactly
  500 survive, and the second visible index is 2 (index 1 was culled and overwritten, §20.4).

`kernelsDoNotAllocate` wraps a camera update (with the yaw changing, so nothing is cached), a hierarchy
pass, sphere culling and a ray–triangle test in `Allocations.assertNoAllocation`: the rule of L00 §8.2,
proved.

### 28.2 IsoCameraControllerTest (render module, no GPU)

[`IsoCameraControllerTest`](../../modules/render/src/test/java/dev/jeddy/render/camera/IsoCameraControllerTest.java)
drives a real controller with plain numbers. Its helper `settle` runs 200 updates of 1/60 s, more than
enough for any glide (§9.3).

- `panIsRelativeToTheViewAndClampedToTheLevel`: at yaw 0, panning forward for one second moves the
  target towards −Z (beyond $z = -10$) with $x$ unchanged, panning right moves it towards +X; with
  bounds $[-5, 5]$ the next update clamps it to exactly $(5, -5)$.
- `zoomAndRotationAreSmoothedAndClamped`: after `zoom(3)` and one update the distance is strictly
  between 20 and $20/1.15^3$ (smoothed, not jumped); after settling it is $13.15$ to $10^{-3}$;
  `zoom(100)` settles at `minDistance`; `rotate(π/2)` settles at the old yaw plus $\pi/2$.
- `syncedGoalsKeepADistanceSetDirectly`: setting `camera.distance = 30` directly, without `syncGoals`,
  glides back to 20; with it, stays at 30; lowering `maxDistance` to 25 and syncing clamps to 25.
- `dragKeepsTheGrabbedGroundPointUnderTheCursor`: §9.6, to $10^{-3}$ m.
- `followGlidesToTheTargetAndFrameUpdatesDoNotAllocate`: `follow(10, 0, -4)` settles there;
  `lodPixels(1080)` is above 1000 ("a narrow FOV gives many pixels per unit", §12.2: 2015); and a full
  frame of edge pan, zoom and update allocates nothing.

### 28.3 DrawBuilderTest (GPU)

[`DrawBuilderTest`](../../modules/render/src/test/java/dev/jeddy/render/draw/DrawBuilderTest.java)
builds a packet of a 200×100 grid of quads (instances) inside $[-100, 100]$, plus a row of 100
instances at $x = 500$ and beyond, outside the view. The "camera" is a bare orthographic projection,
`orthoReversedZ(-100, 100, -100, 100, -10, 10)`, whose planes `CullOps.extractPlanes` extracts into
the packet; `Culling` must keep exactly 20,000 instances. LOD selection runs in orthographic mode with
`setLod(SIZE / 200f, true, 1f)`: 128 pixels show 200 units, 0.64 pixels per unit, so the test mesh's
LOD 1 with an error of 0.5 projects to 0.32 pixels and is chosen. The test also checks that 40,000
items (two submeshes each) become 2 multi-draws, reads back pixels, and runs cull and build under
`assertNoAllocation`.

### 28.4 StaticSceneTest (GPU)

[`StaticSceneTest`](../../modules/render/src/test/java/dev/jeddy/render/cull/StaticSceneTest.java)
renders a ground and a 9×9 grid of spheres twice, once through the CPU path and once marked static
through the GPU path, and compares the images. `gpuCulledStaticInstancesLookLikeTheCpuPath` then reads
back the GPU's instance counts and counts the visible instances itself with `CullOps.sphereVisible`:
"GPU and CPU frustum tests agree", and fewer than all 82 are drawn. `farInstancesUseCoarserLods` puts
one quad at the origin and one 40 m further away, with a LOD 1 error of 0.05 and a 40° camera at
$(0, 0, 3)$ in a 96-pixel viewport: $K = 96/(2\tan 20°) \approx 132$, so the near quad's LOD 1 would
project to $0.05 \cdot 132/3 = 2.2$ pixels (LOD 0 kept) and the far one to $0.05 \cdot 132/43 = 0.15$
(LOD 1 chosen). The test asserts exactly that split.

### 28.5 GpuDeviceTest and SceneRendererTest (GPU)

[`GpuDeviceTest`](../../modules/render/src/test/java/dev/jeddy/render/gpu/GpuDeviceTest.java) asserts
the reversed-Z comparisons: `stateCacheSkipsRedundantCalls` applies `PipelineState.OPAQUE` and reads
back `GL_DEPTH_FUNC`, which must be `GL_GEQUAL`; `samplersAreCachedByDescription` reads the shadow
sampler's `GL_TEXTURE_COMPARE_FUNC`, also `GL_GEQUAL`.
[`SceneRendererTest`](../../modules/render/src/test/java/dev/jeddy/render/pbr/SceneRendererTest.java)
builds its camera from nothing but `Mat4Ops`, which is the whole of this chapter in a handful of lines:

```java
        Mat4Ops.lookAt(0, 0, 4, 0, 0, 0, 0, 1, 0, view, 0);
        Mat4Ops.perspectiveReversedZ((float) Math.toRadians(40), 1, 0.1f, proj, 0);
        Mat4Ops.mul(proj, 0, view, 0, vp, 0);
        Mat4Ops.invert(vp, 0, inv, 0);
        float[] planes = new float[CullOps.PLANES_FLOATS];
        CullOps.extractPlanes(vp, 0, planes);
        packet.setCamera(view, proj, vp, inv, planes, 0, 0, 4);
```

Look-at, projection, product, inverse (for picking and depth reconstruction) and frustum planes (for
culling), into the packet.

## 29. Things to run, and the numbers measured

### 29.1 Commands

```
./gradlew :math:test --tests 'dev.jeddy.math.MatrixAndQuatTest'
./gradlew :render:test --tests 'dev.jeddy.render.camera.IsoCameraControllerTest'
./gradlew :render:test -Pjeddy.gpuTests=true --tests 'dev.jeddy.render.draw.DrawBuilderTest'
./gradlew :render:test -Pjeddy.gpuTests=true --tests 'dev.jeddy.render.cull.StaticSceneTest'
./gradlew :bench:jmh -Pjmh.includes=MathBench
./gradlew :iso-demo:run
```

The first two need no GPU. The GPU tests need a GL 4.6 driver (Mesa's llvmpipe is enough);
`-Pjeddy.gpuTests=true` adds them to the run ([running.md](../running.md) has the details, including
PowerShell's quoting).

### 29.2 The numbers measured

| What | Number | Where it was measured |
|---|---|---|
| `MathBench.cullSpheres10k`: 10,000 spheres through `CullOps.spheres` | 39.7 µs, about 4 ns per sphere | the committed baseline `bench/baseline/linux-x64.json`, a shared GitHub runner (noisy by nature) |
| `MathBench.mat4Mul`: one `Mat4Ops.mul` | 15 ns | the same baseline |
| Cull and build 20,000 instances (`DrawBuilderTest` prints it) | ≈ 1.7–2.6 ms per frame | TASKS M5-08, the 2-core development sandbox |
| Main-thread frame preparation, 20,000 static instances | 2.2 ms on the CPU path, 0.33 ms with GPU culling | TASKS M7-11, llvmpipe |

The culling kernel is a few nanoseconds per object: culling is never the bottleneck, and testing
twenty thousand spheres costs less than drawing a dozen of them.

### 29.3 Things to try in the demo

- Hover the floor and units: the outline under the cursor is §26.1, every frame.
- Compare Q/E rotation with the wheel zoom: both glide with the same 0.15 s time constant (§9.3).
- Zoom fully in and out (8 to 48 m) and watch the ground scroll at the same on-screen speed with the
  keys at every zoom level (§9.5).
- Press C to follow the party, then tap a movement key: following stops (§22.3).

## Exercises

Each has a hint and a full solution; try before you open them. Exercises 1–8 are the chapter's
original set (exercise 7 is the warm-up task "orthographic zoom" of
[next-milestones.md](../next-milestones.md)); 9–16 practise the sections that had none.

**1. By hand, then by test (§3, §4).** Set up the example camera with `IsoCamera`: `yaw = 0`,
`pitch = (float) Math.atan2(6, 8)`, `distance = 10`, target at the origin, `update(16f / 9f)`. Compute
by hand the NDC of $(0, 1.8, 0)$ (the head of a character standing on the target), then check it with
`Mat4Ops.project(cam.viewProjection, 0, 0, 1.8f, 0, out, 0)` in a new test next to
`cameraCullingAndPicking`.

<details><summary>Hint</summary>

View space is $`(0,\; 0.8 \cdot 1.8,\; 0.6 \cdot 1.8 - 10)`$ (§3.4's rows). Then §4.7's steps. Use a
tolerance around $10^{-4}$: the camera is built from `float` trigonometry.
</details>
<details><summary>Solution</summary>

View: $(0, 1.44, -8.92)$, so $s = 8.92$. Clip: $x_c = 0$, $y_c = 3.7321 \cdot 1.44 = 5.3742$,
$z_c = n = 0.5$, $w_c = 8.92$. NDC: $y_n = 5.3742/8.92 = 0.6025$, depth $= 0.5/8.92 = 0.05605$. The
head is 60% of the way from the screen center to the top edge.

```java
    @Test
    void headOfACharacterOnTheTarget() {
        IsoCamera cam = new IsoCamera();
        cam.yaw = 0;
        cam.pitch = (float) Math.atan2(6, 8);
        cam.distance = 10;
        cam.update(16f / 9f);
        float[] out = new float[3];
        Mat4Ops.project(cam.viewProjection, 0, 0, 1.8f, 0, out, 0);
        assertEquals(0f, out[0], 1e-4f);
        assertEquals(0.6025f, out[1], 1e-4f);
        assertEquals(0.05605f, out[2], 1e-4f);
    }
```

The real matrices give $(0, 0.602483, 0.056054)$.
</details>

**2. Plot depth precision (§7).** Write a small Java program which, for distances from 1 to 400 m,
computes the stored depth for the standard mapping ($n = 0.5$, $F = 500$) and for reversed-Z ($n/s$),
takes the step of each format there (`Math.ulp(float)` for float32, $1/(2^{24} - 1)$ for 24-bit), and
converts it to metres. Reproduce §7.5's table, then run it with $n = 0.05$. Which columns change and
which does not?

<details><summary>Hint</summary>

$\Delta s = \Delta d/\lvert d'(s) \rvert$ with $d'(s) = Fn/((F - n)s^2)$ for standard and $n/s^2$ for
reversed (§7.1). Compute in `double` and cast only the stored depth to `float` before `Math.ulp`, so the
only float rounding is the one the depth buffer does.
</details>
<details><summary>Solution</summary>

```java
public class DepthPrecision {
    public static void main(String[] args) {
        float n = args.length > 0 ? Float.parseFloat(args[0]) : 0.5f;
        double far = 500;
        double step24 = 1.0 / ((1 << 24) - 1);       // one step of a 24-bit integer buffer
        System.out.println("   s (m)   std 24-bit   rev 24-bit   std float    rev float");
        for (double s : new double[] {1, 5, 10, 20, 50, 100, 400}) {
            double dStd = far / (far - n) * (1 - n / s);              // standard: near 0, far 1
            double slopeStd = far * n / ((far - n) * s * s);
            double dRev = n / s;                                      // reversed, infinite far
            double slopeRev = n / (s * s);
            System.out.printf("%8.0f %12.3g %12.3g %12.3g %12.3g%n",
                    s,
                    step24 / slopeStd,
                    step24 / slopeRev,
                    Math.ulp((float) dStd) / slopeStd,
                    Math.ulp((float) dRev) / slopeRev);
        }
    }
}
```

With $n = 0.5$ it prints §7.5's table (for example `20  4.76e-05  4.77e-05  4.76e-05  1.49e-06`). With
`0.05` the first three columns get ten times worse (`20  0.000477 ...`, because they go as $1/n$), and
the reversed float column barely moves (`1.86e-06` at 20 m, `1.16e-05` at 100 m): its relative
precision is between $2^{-24}$ and $2^{-23}$ of the distance whatever the near plane (§7.4).
</details>

**3. A finite reversed-Z perspective (§4.5, §10.3).** Add
`Mat4Ops.perspectiveReversedZ(float fovY, float aspect, float near, float far, float[] out, int o)`.
Test that `near` maps to depth 1 and `far` to 0, and that `CullOps.extractPlanes` now produces a real
far plane that culls a sphere beyond `far`.

<details><summary>Hint</summary>

From §4.5: row 2 is $(0, 0, A, B)$ with $A = n/(F - n)$ and $B = nF/(F - n)$, so `out[o + 10] = A` and
`out[o + 14] = B`. The far plane is row 2 of $`P\,V`$; it is no longer degenerate because $A \ne 0$.
</details>
<details><summary>Solution</summary>

```java
    /** Writes a reversed-Z perspective projection with a finite far plane (depth 0 at {@code far}). */
    public static void perspectiveReversedZ(
            float fovY, float aspect, float near, float far, float[] out, int o) {
        perspectiveReversedZ(fovY, aspect, near, out, o);
        float fn = 1 / (far - near);
        out[o + 10] = near * fn;        // A = n / (F - n)
        out[o + 14] = near * far * fn;  // B = n F / (F - n)
    }
```

```java
    @Test
    void finiteReversedZPerspective() {
        float[] p = new float[16];
        Mat4Ops.perspectiveReversedZ(MathF.toRadians(60), 16f / 9f, 0.5f, 100f, p, 0);
        float[] out = new float[3];
        Mat4Ops.project(p, 0, 0, 0, -0.5f, out, 0);
        assertEquals(1f, out[2], 1e-5f);
        Mat4Ops.project(p, 0, 0, 0, -100f, out, 0);
        assertEquals(0f, out[2], 1e-5f);
        float[] planes = new float[CullOps.PLANES_FLOATS];
        CullOps.extractPlanes(p, 0, planes);  // view = identity: the camera at the origin
        assertTrue(CullOps.sphereVisible(planes, 0, 0, -99, 0.5f));
        assertFalse(CullOps.sphereVisible(planes, 0, 0, -102, 1f));
    }
```

With the identity as view matrix, row 2 of $P$ is $(0, 0, A, B)$, which normalizes to
$(0, 0, 1, B/A) = (0, 0, 1, F)$: the test $z + F \ge 0$, "no further than $F$ in front". The sphere at
$z = -102$ has distance $`-2 < -1`$: culled. In general, §10.3's algebra gives
$\mathbf{f}\cdot(\mathbf{p} - \mathbf{e}) \le F$. (This solution was compiled and run against the
math module: depths 1.0 and 0.0, far plane `(0, 0, 1, 100)`.)
</details>

**4. Inside, intersecting or outside (§10.5).** Hierarchical culling (test a group's sphere first,
then its members only if needed) wants three answers, not two. Add
`CullOps.sphereClassify(planes, cx, cy, cz, r)` returning 0 (outside), 1 (intersecting) or 2 (fully
inside), and test it with an `IsoCamera`. Remember the infinite far plane.

<details><summary>Hint</summary>

Outside as before (distance $`< -r`$ for some plane). Fully inside if the distance is $\ge r$ for every
plane. The degenerate far plane `(0, 0, 0, 1)` gives distance 1 for every point, so with $`r > 1`$
nothing would ever be "fully inside"; skip planes whose normal is zero.
</details>
<details><summary>Solution</summary>

```java
    /** Returns 0 if the sphere is outside the frustum, 1 if it crosses a plane, 2 if fully inside. */
    public static int sphereClassify(float[] planes, float cx, float cy, float cz, float r) {
        int result = 2;
        for (int p = 0; p < 24; p += 4) {
            float a = planes[p];
            float b = planes[p + 1];
            float c = planes[p + 2];
            if (a == 0 && b == 0 && c == 0) {
                continue; // the degenerate far plane accepts everything
            }
            float distance = a * cx + b * cy + c * cz + planes[p + 3];
            if (distance < -r) {
                return 0;
            }
            if (distance < r) {
                result = 1;
            }
        }
        return result;
    }
```

```java
    @Test
    void sphereClassification() {
        IsoCamera cam = new IsoCamera();
        cam.target.set(10, 0, -5);
        cam.update(16f / 9f);
        assertEquals(2, CullOps.sphereClassify(cam.frustumPlanes, 10, 0, -5, 1));   // at the target
        assertEquals(1, CullOps.sphereClassify(cam.frustumPlanes, 10, 0, -5, 30));  // bigger than the view
        assertEquals(0, CullOps.sphereClassify(cam.frustumPlanes, 500, 0, -5, 1));  // far to the side
    }
```

A group classified 2 draws all its members without testing them; 0 skips them all; only 1 tests each
member. Like the boolean test it is conservative near the corners: "1" can come back for a sphere that
is in fact outside.
</details>

**5. A cone against the frustum (§10.5).** Spot lights and cone spells are cones. Write
`CullOps.coneVisible(planes, apex, axis, height, halfAngle)` and test it against a sphere-based
version.

<details><summary>Hint</summary>

A cone with apex $\mathbf{a}$, unit axis $\mathbf{v}$, height $h$ and base radius
$R = h\tan(\text{halfAngle})$ is the convex hull of its apex and its base disc, so it is outside a plane
$(\mathbf{n}, d)$ when the apex is outside, $`\mathbf{n}\cdot\mathbf{a} + d < 0`$, and so is the whole base
disc. The disc's point furthest along $\mathbf{n}$ has signed distance
$\mathbf{n}\cdot(\mathbf{a} + h\mathbf{v}) + d + R\sqrt{1 - (\mathbf{n}\cdot\mathbf{v})^2}$.
</details>
<details><summary>Solution</summary>

Why the disc formula: the disc's points are $\mathbf{c} + R\mathbf{q}$ with
$\mathbf{c} = \mathbf{a} + h\mathbf{v}$ and $\mathbf{q}$ any unit vector perpendicular to $\mathbf{v}$. $\mathbf{n}\cdot\mathbf{q}$
is largest when $\mathbf{q}$ is the part of $\mathbf{n}$ perpendicular to $\mathbf{v}$,
$\mathbf{n} - (\mathbf{n}\cdot\mathbf{v})\mathbf{v}$, normalized; its length is
$\sqrt{1 - (\mathbf{n}\cdot\mathbf{v})^2}$ (Pythagoras, with $\lVert\mathbf{n}\rVert = 1$).

```java
    /** Returns {@code true} if the cone is at least partly inside the frustum (conservative). */
    public static boolean coneVisible(
            float[] planes,
            float ax, float ay, float az,     // apex
            float vx, float vy, float vz,     // unit axis
            float height,
            float halfAngle) {
        float radius = height * (float) Math.tan(halfAngle);
        float bx = ax + vx * height;           // center of the base disc
        float by = ay + vy * height;
        float bz = az + vz * height;
        for (int p = 0; p < 24; p += 4) {
            float a = planes[p];
            float b = planes[p + 1];
            float c = planes[p + 2];
            float d = planes[p + 3];
            float apex = a * ax + b * ay + c * az + d;
            float nv = a * vx + b * vy + c * vz;
            float rim = a * bx + b * by + c * bz + d
                    + radius * (float) Math.sqrt(Math.max(0, 1 - nv * nv));
            if (apex < 0 && rim < 0) {
                return false;
            }
        }
        return true;
    }
```

(`Math.max(0, ...)` guards against $1 - (\mathbf{n}\cdot\mathbf{v})^2$ rounding to a tiny negative
number.) The test: build a default `IsoCamera` aimed at $(10, 0, -5)$; a cone standing on the target is
visible, one at $x = 500$ is culled, and a cone whose apex is 40 m to the side but whose 45 m axis
points back into view is visible even though `sphereVisible` on its apex alone says "outside". Then
compare with the cone's bounding sphere (center at half height, radius $\sqrt{(h/2)^2 + R^2}$) on
100,000 random cones: the cone test must never keep a cone the sphere culls. Run against the real
camera (cones drawn with `new java.util.Random(7)`), it never did, and it culled several hundred cones
the sphere kept: the tighter shape pays off at the frustum's edges.
</details>

**6. Conservative culling across the tick (§13).** Make culling safe for interpolated motion: give each
moving instance a bounding sphere that covers its pose at both ticks, not only at the current one.
Decide whether this belongs in `World.extract` (game thread, once per instance) or `Culling.cullRange`
(render thread, every view), and add a test where an instance moving out of the frustum stays visible
at `alpha = 0`.

<details><summary>Hint</summary>

Transform the mesh's sphere center by both `prevWorld` and `currWorld` to get $`\mathbf{c}_0`$ and
$`\mathbf{c}_1`$. The blend is linear in the matrix entries, so the interpolated center always lies on
the segment between them, and the sphere centered at $`(\mathbf{c}_0 + \mathbf{c}_1)/2`$ with radius
$`r + \lVert \mathbf{c}_1 - \mathbf{c}_0 \rVert / 2`$ contains the object at every $\alpha$ (assuming its
scale does not change).
</details>
<details><summary>Solution</summary>

Put it in `World.extract`: it runs once per instance on the game thread, while `cullRange` runs once per
*view* (main camera, every cascade, every shadowed light) and would redo it for each; and the packet's
`bounds` stays one sphere per instance, so no view's loop changes. The cost is one more point
transform and one square root per moving instance per frame. A helper:

```java
    /** Writes to out[0..3] a sphere containing the mesh sphere (sx, sy, sz, radius) at both ticks. */
    static void sweptSphere(
            float[] prev, int po, float[] curr, int co,
            float sx, float sy, float sz, float radius, float[] scratch, float[] out) {
        AffineOps.transformPoint(prev, po, sx, sy, sz, scratch, 0);  // c0
        AffineOps.transformPoint(curr, co, sx, sy, sz, out, 0);      // c1
        float dx = out[0] - scratch[0];
        float dy = out[1] - scratch[1];
        float dz = out[2] - scratch[2];
        out[0] = (out[0] + scratch[0]) * 0.5f;
        out[1] = (out[1] + scratch[1]) * 0.5f;
        out[2] = (out[2] + scratch[2]) * 0.5f;
        out[3] = radius + 0.5f * (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
```

In `World.extract`, the non-animated, non-static branch then calls it with `prev, i * 12, world,
s * 12`, the mesh sphere scaled by `maxScale(world, s * 12)`, and two preallocated arrays, and passes
the result to `packet.setBounds`. Static instances keep the plain sphere (their two transforms are the
same).

The test, with the running example camera (its right plane is $(-0.9028, \ldots, 4.3005)$): an instance
at $x = 4$ in the previous tick and $x = 7$ in the current one, mesh sphere radius 0.5. The current-tick
sphere at $x = 7$ is culled (distance $`-2.02 < -0.5`$), yet at `alpha = 0` the instance is drawn at
$x = 4$, inside the view. The swept sphere, center $x = 5.5$ and radius $0.5 + 1.5 = 2$, has distance
$-0.665 \ge -2$: kept. (Checked against the real `CullOps` and `AffineOps`.)
</details>

**7. Zoom for the orthographic camera (§9.4, §12.2).** In orthographic mode `IsoCamera` sizes the view
with `orthoHeight` only, so `IsoCameraController.zoom` (which changes `distance`) has no visible effect.
Make zoom work in both modes and keep `lodPixels` consistent. Extend `IsoCameraControllerTest`.

<details><summary>Hint</summary>

One option: in orthographic mode derive the visible height from the distance, for example
$h_o = 2D\tan(\varphi/2)$, which matches what the perspective camera shows at the target. Keep the eye
far enough back that nothing between the target and the camera crosses the near plane.
</details>
<details><summary>Solution</summary>

The smallest change is in the controller, just before it updates the camera in `update`:

```java
        clampTarget();
        if (camera.orthographic) {
            // Show at the target what the perspective camera would show there.
            camera.orthoHeight = 2 * camera.distance * (float) Math.tan(camera.fovY * 0.5f);
        }
        camera.update(aspect);
```

Zoom now works in both modes, with the same smoothing (the distance glides, the height follows), and
the pan speed, which scales with `distance`, stays right. `lodPixels` needs no change: it reads
`orthoHeight`, and $H/h_o = H/(2D\tan(\varphi/2))$ is exactly the perspective $K/D$ at the target
(§12.2), so switching projections keeps the same LOD at the target. `SceneRenderer.lodPixels` reads
`projection[5]` $= 2/h_o$ and agrees automatically.

```java
    @Test
    void zoomWorksInOrthographicMode() {
        IsoCamera cam = new IsoCamera();
        cam.orthographic = true;
        IsoCameraController c = new IsoCameraController(cam);
        settle(c);
        float before = cam.projection[5];
        c.zoom(3);
        settle(c);
        assertEquals(before * Math.pow(1.15, 3), cam.projection[5], 1e-4);   // 1.5209 times bigger
        assertEquals(cam.projection[5] * 540, c.lodPixels(1080), 1e-2);       // renderer and controller agree
    }
```

Run against the real classes: `projection[5]` goes from 0.18660 to 0.28380, a ratio of 1.52087
($1.15^3 = 1.520875$), and both LOD values are 153.25.

Two trade-offs to know. The controller now owns `orthoHeight` in orthographic mode: a game that sets it
directly sees it overwritten (the editor zooms its own orthographic camera by scaling `orthoHeight`, in
`Viewport`, and does not use this controller). And the eye still moves with `distance`, so at the
closest zoom tall geometry between the eye and the target can cross the near plane. The fix the hint
suggests: in `IsoCamera.update`, place the eye at a fixed stand-off in orthographic mode (say a field
`orthoStandoff = 100`, used instead of `distance` in `eye.set`), since in orthographic projection the
eye's distance changes nothing in the picture; `orthoFar` must then cover the stand-off plus the depth
of the level.
</details>

**8. Goals that follow the camera, and smoothing you can trust (§9.3, §22.2).** The controller copies
`distance` and `yaw` into its goals in its constructor. The demo creates the controller as a field,
before `init` sets `camera.distance = 22`, so it calls `controller.syncGoals()` afterwards; without that
call the smoothing would glide the camera back to the default 20 during the first second
(`IsoCameraControllerTest.syncedGoalsKeepADistanceSetDirectly` shows both cases). Now test the claim of
§9.3: write a test that zooms, then runs `update` once with $\Delta t = 0.032$ s on one controller and
twice with 0.016 s on another, and asserts the two distances agree to within $10^{-4}$. Then replace
$k$ with the naive `dt / smoothing` and watch the test fail.

<details><summary>Hint</summary>

Two cameras and two controllers with the same settings. After one call with $2\Delta t$ the gap is
multiplied by $e^{-2\Delta t/\tau}$; after two calls with $\Delta t$ it is multiplied by
$e^{-\Delta t/\tau}$ twice, the same number. With the naive rate the factors are $1 - 2\Delta t/\tau$ and
$(1 - \Delta t/\tau)^2$, which differ by $(\Delta t/\tau)^2$.
</details>
<details><summary>Solution</summary>

```java
    @Test
    void smoothingDoesNotDependOnTheFrameRate() {
        IsoCamera a = new IsoCamera();
        IsoCamera b = new IsoCamera();
        IsoCameraController ca = new IsoCameraController(a);
        IsoCameraController cb = new IsoCameraController(b);
        ca.zoom(3);
        cb.zoom(3);
        ca.update(0.032f, 1.6f);
        cb.update(0.016f, 1.6f);
        cb.update(0.016f, 1.6f);
        assertEquals(a.distance, b.distance, 1e-4f);
    }
```

The numbers: the goal is $20/1.15^3 = 13.1503$, so the gap starts at 6.8497. Exactly,
$`6.8497\,e^{-0.032/0.12} = 5.2464`$ either way, and both distances are 18.3967 (run against the real
controller, they agree to the last bit). With the naive $k$: one step leaves
$6.8497(1 - 0.2667) = 5.0231$, two steps $6.8497(1 - 0.1333)^2 = 5.1449$. The distances differ by 0.12,
a thousand times the tolerance: the test fails, as it should.
</details>

**9. Straight down (§3.5).** With yaw 0, set an `IsoCamera`'s pitch to `MathF.toRadians(89.9f)` and
then `MathF.toRadians(90)`, update both, and print row 0 of each view matrix. Explain the difference, then
propose an up hint for `lookAt` that works for a top-down camera, and check it still gives the same
matrix at pitch 55°.

<details><summary>Hint</summary>

Row 0 of $V$ is the right axis $\mathbf{r} = \mathbf{f}\times\mathbf{w}$ normalized. Look at the sign of
`Math.cos` of each pitch. For the fix, a hint must never be parallel to $\mathbf{f}$: try the ground
forward of §9.5.
</details>
<details><summary>Solution</summary>

At 89.9°, with yaw 0, row 0 is $(1, 0, 0)$; at `MathF.toRadians(90)` it is $(-1, 0, 0)$. The float
1.5707964 is slightly more than $\pi/2$, its cosine is $-4.37 \times 10^{-8}$, so $\mathbf{f}$'s
horizontal part has flipped sign and so has $\mathbf{f}\times\mathbf{w}$: the image turns 180°. No NaN,
because the cross product is tiny but not zero.

Use the ground forward $\mathbf{g} = (-\sin\psi, 0, -\cos\psi)$ as the hint. Then
$`\mathbf{f}\times\mathbf{g} = \sin\theta\,(\cos\psi, 0, -\sin\psi)`$, which normalizes to the ground right
for every pitch $`\theta > 0`$, including exactly 90°, where the true up becomes $\mathbf{g}$ itself
("screen up is ground forward", what a top-down view wants). It fails only at $\theta = 0$, a horizontal
camera, the opposite end. Checked with the real `lookAt` and an `IsoCamera` with yaw 0.3, distance 10
and the target at the origin: at pitch 90° row 0 is $(0.9553, 0, -0.2955) = (\cos 0.3, 0, -\sin 0.3)$
and row 1 is $(-0.2955, 0, -0.9553)$; at pitch 55° the matrix equals the one built with the $+Y$ hint to
$1.2 \times 10^{-7}$ (float rounding, which grows with the translation entries: at distance 20 it is
$2.4 \times 10^{-7}$).
</details>

**10. Overlapping arrays (§14.2).** `Mat4Ops.mul` forbids `out` to overlap `a` or `b`. Find a pair of
matrices for which `mul(a, 0, r, 0, a, 0)` (output into `a`) gives a wrong answer, and explain why
`mul(a, 0, r, 0, r, 0)` (output into `b`) happens to work.

<details><summary>Hint</summary>

Iteration $c$ writes column $c$ of `out` and reads *all* of `a` but only column $c$ of `b`. You need a
`b` whose column 1 uses column 0 of `a`.
</details>
<details><summary>Solution</summary>

Take $a = \text{diag}(2, 3, 1, 1)$ and $r$ a 90° rotation about $Z$, whose column 0 is $(0, 1, 0, 0)$
and column 1 $(-1, 0, 0, 0)$. The correct product has column 0 $= a \cdot (0,1,0,0) = (0, 3, 0, 0)$ and
column 1 $= a \cdot (-1, 0, 0, 0) = (-2, 0, 0, 0)$. Writing into `a`, iteration 0 overwrites `a`'s
column 0 with $(0, 3, 0, 0)$; iteration 1 then computes $-1 \times$ (the new column 0) $= (0, -3, 0, 0)$:
wrong. Writing into `r` works because iteration $c$ copies column $c$ of `r` into the locals `b0..b3`
before writing column $c$ of `out`, and no later iteration reads that column again. (Checked with the
real kernel.) It is still forbidden: the guarantee would tie the kernel to this loop order forever.
</details>

**11. Clipping by hand (§5.1).** A triangle has vertices $(0, 0, 0)$ and $(2, 0, 0)$ in front of the
example camera and $(0, 6, 9)$, 1 m behind the eye. Where does the clipper cut the edge from
$(0, 0, 0)$ to $(0, 6, 9)$, and what depth does the new vertex get?

<details><summary>Hint</summary>

Write the edge as $`\mathbf{p}(t) = t\,(0, 6, 9)`$, compute $w_c(t) = s(t)$ with row 2 of $V$, and solve
$z_c = w_c$ (§5.1, with $z_c = n$).
</details>
<details><summary>Solution</summary>

Row 2 of $V$ is $(0, 0.6, 0.8, -10)$, so $z_v(t) = 0.6 \cdot 6t + 0.8 \cdot 9t - 10 = 10.8t - 10$ and
$w_c = s = 10 - 10.8t$. The near clip $z_c \le w_c$ is $0.5 \le 10 - 10.8t$, so the cut is at
$t = 9.5/10.8 = 0.8796$: the new vertex is $(0, 5.278, 7.917)$, exactly on the near plane, with depth
$n/s = 0.5/0.5 = 1$. (`Mat4Ops.project` on that point gives depth 1.0.) Because $w_c$ and $z_c$ are
linear in $t$, interpolating clip coordinates finds the same point; interpolating NDC would not, since
one end has $w_c = -0.8$ and its NDC is the mirrored ghost of the table in §5.1.
</details>

**12. The positive vertex (§10.6).** With the example camera, test the cube of half-size 1 centered at
$(6, 0, 0)$, then at $(7, 0, 0)$, against the right plane $(-0.9028, -0.2580, -0.3440, 4.3005)$ by
hand, and compare with `CullOps.aabbVisible` and with `sphereVisible` on the cube's bounding sphere.

<details><summary>Hint</summary>

All three normal components are negative, so the positive vertex is the minimum corner.
</details>
<details><summary>Solution</summary>

Center 6: positive vertex $(5, -1, -1)$, distance
$-0.9028 \cdot 5 + 0.2580 + 0.3440 + 4.3005 = 0.389 \ge 0$: kept. Center 7: $(6, -1, -1)$, distance $`-0.514 < 0`$: culled. `aabbVisible` agrees (true, then
false), and so does `sphereVisible` with radius $\sqrt 3$ (the cube's corner distance). The box test
picked the one corner that mattered with three comparisons instead of testing eight.
</details>

**13. When does a LOD switch? (§12.3)** A mesh's LOD 1 has an error of 2 cm. At what distance does a
1080-pixel-high, 30° camera start using it? At 2160 pixels? For an instance scaled by 2?

<details><summary>Hint</summary>

$`\varepsilon\,\sigma\,K/s \le 1`$ gives $`s \ge \varepsilon\,\sigma\,K`$, with $K = H/(2\tan 15°)$.
</details>
<details><summary>Solution</summary>

$K = 1080/(2 \cdot 0.26795) = 2015.3$, so $s \ge 0.02 \cdot 2015.3 = 40.3$ m. At 2160 pixels $K$
doubles: 80.6 m (a sharper screen shows the error, so detail is kept longer). Scaled by 2: also
80.6 m. With the demo's camera at 22 m, LOD 1 of this mesh would be used only for objects about twice
as far away as the target.
</details>

**14. Depth and distance (§11.6).** With $n = 0.5$, what distance does stored depth 0.05 represent? The
sky's `0.0001`? The clamp `1e-7`? The smallest normal float, $1.18 \times 10^{-38}$? What does that say
about the "infinite" far plane?

<details><summary>Hint</summary>

$s = n/d$.
</details>
<details><summary>Solution</summary>

10 m; 5,000 m; 5,000,000 m; about $4 \times 10^{37}$ m. Long before the float range ends, the depth of
any real object is representable with relative precision $2^{-23}$ (§7.4): the far plane is infinite in
every practical sense, and depth exactly 0 is reserved for "nothing was drawn here".
</details>

**15. Glide times (§9.3).** How long does the controller take to cover 99% of a zoom with the default
0.12 s time constant? With the demo's 0.15 s? What is $k$ per frame at 144 Hz?

<details><summary>Hint</summary>

The gap left after time $t$ is $e^{-t/\tau}$; solve $e^{-t/\tau} = 0.01$ with `Math.log`.
</details>
<details><summary>Solution</summary>

$`t = -\tau\ln 0.01 = 4.605\,\tau`$: 0.553 s, and 0.691 s for the demo. At 144 Hz,
$k = 1 - e^{-(1/144)/0.12} = 1 - e^{-0.0579} = 0.0562$: each frame covers 5.6% of the gap, less than the
13% at 60 Hz, and after one second both have covered the same distance.
</details>

**16. Panning at yaw 90° (§9.5).** At yaw 90°, which world direction does "forward" pan move the
target? And "right"? Check with `update`'s two lines.

<details><summary>Hint</summary>

At yaw 90° the eye is on the +X side of the target (§9.1).
</details>
<details><summary>Solution</summary>

Ground forward $(-\sin 90°, 0, -\cos 90°) = (-1, 0, 0)$: towards −X, away from the eye. Right
$(\cos 90°, 0, -\sin 90°) = (0, 0, -1)$. In `update`, with `s = 1`, `c = 0`: `panX = 1` gives
$\Delta x = 1 \cdot 0 - 0 = 0$ and $\Delta z = -1 \cdot 1 - 0 = -1$, so right moves towards −Z; `panZ = 1`
gives $\Delta x = -1$, $\Delta z = 0$. Both times `scale`. Facing −X with +Y up, your right hand points
to −Z: $(-1, 0, 0) \times (0, 1, 0) = (0, 0, -1)$.
</details>

## Glossary

- **Aspect ratio** ($a$): viewport width divided by height (§4.2).
- **Bounding sphere, bounding box:** a simple shape known to enclose an object, tested instead of the
  object (§10.5, §10.6).
- **Clip coordinates, clip space:** the four numbers $(x_c, y_c, z_c, w_c)$ a vertex shader outputs,
  before the divide (§4.3).
- **Clip volume, clipping:** the region $-w_c \le x_c, y_c \le w_c$, $0 \le z_c \le w_c$; cutting
  triangles at its boundary, before the divide (§5.1).
- **Conservative test:** a test that may keep something invisible but never rejects something visible
  (§10.5).
- **Coordinate space:** an origin and axes that give three numbers a meaning (§1.1).
- **Depth buffer, window depth:** the per-pixel texture of depths; the value stored in it (§5.3, §5.6).
- **Depth function, depth test:** the comparison between a fragment's depth and the stored one;
  `GL_GEQUAL` in Jeddy (§5.5).
- **Depth prepass:** drawing opaque geometry with depth only, so the lit pass shades each pixel once
  (§5.5).
- **Depth resolution** ($\Delta s$): how far apart two surfaces must be to get different stored depths
  (§7.1).
- **Derivative, slope:** how fast a quantity changes per unit of another; $-c/s^2$ for $c/s$ (§7.1).
- **Early depth test:** running the depth test before the fragment shader (§5.5).
- **Eye:** the camera's position (§3.1).
- **Far plane, near plane:** the distances beyond and before which nothing is drawn; Jeddy's far plane
  is at infinity (§4.5).
- **Field of view** ($\varphi$, `fovY`): the vertical angle the camera sees (§4.2).
- **Fixed function:** GPU stages you configure but do not program (§1.1).
- **Frustum, frustum culling:** the truncated pyramid the camera sees; skipping objects entirely
  outside it (§10).
- **Geometric error:** how far a simplified LOD strays from the original, in mesh units (§12.1).
- **Guard band:** the region around the screen in which the rasterizer works without clipping (§5.1).
- **Hierarchical Z:** a per-tile summary of the depth buffer used to reject hidden triangles early
  (§5.6).
- **Homogeneous coordinates:** four numbers for a 3D point, equal up to a common factor; the GPU divides
  by the fourth (§4.3).
- **Image plane:** the plane at distance 1 a pinhole camera projects onto (§4.1).
- **Isometric:** strictly an orthographic view at yaw 45° and pitch 35.26°; in Jeddy, the CRPG camera
  of §9.
- **Level of detail (LOD):** a simplified version of a mesh used when its error is too small to see
  (§12).
- **Look-at:** building a view matrix from an eye, a target and an up hint (§3.3).
- **NDC (normalized device coordinates):** clip coordinates divided by $w_c$: $x_n, y_n \in [-1, 1]$,
  $z_n \in [0, 1]$ (§5.2).
- **Orthographic projection:** a projection without perspective, a scale and an offset (§6).
- **Orthonormal basis:** three mutually perpendicular unit vectors; as a matrix, its inverse is its
  transpose (§2.4).
- **Perspective-correct interpolation:** interpolating $\text{value}/w$ and $1/w$ on the screen and
  dividing per pixel (§5.4).
- **Perspective divide:** dividing clip coordinates by $w_c$ (§5.2).
- **Picking:** finding what is under the mouse with a ray from the camera (§11).
- **Pitch, yaw, distance, target:** the isometric camera's tilt, turn, zoom and orbit point (§9.1).
- **Plane, normal, signed distance:** $\mathbf{n}\cdot\mathbf{p} + d = 0$; the perpendicular direction;
  the value $\mathbf{n}\cdot\mathbf{p} + d$ for a unit normal (§10.1).
- **Positive vertex:** the corner of a box furthest along a plane's normal (§10.6).
- **Radian:** the angle unit in which a full turn is $2\pi$ (§2.1).
- **Rasterizer:** the fixed-function stage that turns triangles into fragments (§5.4).
- **Reversed-Z:** mapping the near plane to depth 1 and the far plane to 0, with a float buffer and
  `GL_GEQUAL` (§4.5, §7.4, §8).
- **Similar triangles:** triangles with equal angles, whose sides are proportional (§4.1).
- **Standard Z:** the textbook mapping, near to 0 and far to 1 (§4.5).
- **Time constant** ($\tau$): the time scale of an exponential glide; 95% of the way after $3\tau$
  (§9.3).
- **Ulp:** the gap between a float and the next one, `Math.ulp` (§7.2).
- **Unprojection:** multiplying by the inverse view-projection and dividing by $w$, from NDC back to the
  world (§11.2).
- **View depth** ($s$): distance in front of the camera along its view axis, $-z_v$ (§4.1).
- **View matrix** ($V$): world to view space, the inverse of the camera's transform (§3.2).
- **Viewport, viewport transform:** the target rectangle; the map from NDC to pixels (§5.3).
- **Z-fighting:** two surfaces flickering through each other because their depths round to the same
  value (§7.1).

## Further reading

- Tomas Akenine-Möller, Eric Haines, Naty Hoffman et al., *Real-Time Rendering*, 4th edition:
  chapter 4 (Transforms), especially the section on projections; chapter 19 (Acceleration
  Algorithms) for culling and LOD; chapter 22 (Intersection Test Methods) for plane, sphere, box and
  frustum tests.
- Nathan Reed, "Depth Precision Visualized" (2015),
  [reedbeta.com/blog/depth-precision-visualized](https://www.reedbeta.com/blog/depth-precision-visualized/):
  the clearest explanation of reversed-Z with float depth, with graphs.
- Gil Gribb and Klaus Hartmann, "Fast Extraction of Viewing Frustum Planes from the World-View-Projection
  Matrix" (2001): the plane-extraction trick, for both clip conventions.
- Paul Upchurch and Mathieu Desbrun, "Tightening the Precision of Perspective Rendering", Journal of
  Graphics Tools (2012): an analysis of infinite and reversed projections in floating point.
- LearnOpenGL, [Coordinate Systems](https://learnopengl.com/Getting-started/Coordinate-Systems) and
  [Camera](https://learnopengl.com/Getting-started/Camera): the same pipeline from the OpenGL side
  (note that they use the default $[-1, 1]$ depth and standard Z).
- The [OpenGL 4.6 core specification](https://registry.khronos.org/OpenGL/specs/gl/glspec46.core.pdf),
  the chapter on fixed-function vertex post-processing (clip control, clipping, coordinate
  transformations), and the
  [`ARB_clip_control` extension](https://registry.khronos.org/OpenGL/extensions/ARB/ARB_clip_control.txt),
  which gives both motivations for the feature: matching Direct3D's conventions and better depth
  precision.
- Eric Lengyel, *Foundations of Game Engine Development, Volume 2: Rendering*: projections, infinite
  and reversed depth, and frustum planes, derived carefully.
- Fletcher Dunn and Ian Parberry, *3D Math Primer for Graphics and Game Development*: the camera and
  clip-space chapters, if the matrix algebra here went too fast.

---

*Code this chapter builds or quotes: `Mat4Ops`, `Mat4f`, `IsoCamera`, `CullOps`, `Intersect.rayPlane`,
`Planef`, `GpuDevice.useReversedZ`, `PipelineState`, `StateCache.apply`, `FrameGraph` (viewport and
clears), `SceneRenderer` (depth target, `cullMainView`, `lodPixels`, `writeFrameConstants`, `sky`),
`Culling`, `IsoCameraController`, `DrawBuilder` (`setLod`, `buildItems`, `selectLod`, `writeRecords`),
`RenderPacket.setCamera`, `FixedStepper.alpha`, `MyGame`, `DemoGame` (camera setup, `handleInput`,
`updateCamera`), `Party.selectBox`, the shaders `engine/frame.glsl`, `engine/instance.glsl`,
`engine/fullscreen.vert`, `pbr/pbr.vert`, `pbr/depth.vert`, `pbr/sky.frag`, `post/gtao.frag`,
`cull/static_cull.comp`, and the tests `MatrixAndQuatTest`, `IsoCameraControllerTest`,
`DrawBuilderTest`, `StaticSceneTest`, `GpuDeviceTest`, `SceneRendererTest`. Decisions: D21, D23, D30,
D42, D49, D93 in [DESIGN §17](../DESIGN.md#17-decision-log). Related: [Lesson 0](lessons/00-foundations.md)
(floats, the GPU), [Lesson 1](lessons/01-buffers-in-flight.md) (the frame ring the records and
constants live in), [Lesson 3](lessons/03-data-layout.md) (struct of arrays, when to go parallel);
chapters [02](02-math-foundations.md) and [03](03-rotations-and-transforms.md) (the math underneath),
[05](05-opengl-from-java.md) (the GL pipeline and state cache), [06](06-renderer-architecture.md) (sort
keys, indirect draws), [07](07-lighting-and-shadows.md) (shadow maps with reversed-Z),
[11](11-physics-navigation-crowds.md) (ray casts against the level) and [14](14-editor.md) (the
editor's camera and picking).*

[← 03 Rotations and transforms](03-rotations-and-transforms.md) · [Index](README.md) · [05 OpenGL from Java →](05-opengl-from-java.md)
