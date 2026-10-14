# 02 — Math foundations: vectors, matrices and affine transforms

*Every frame, the engine places thousands of things in a 3D world: characters walk, a fireball
flies, the camera orbits the party, and every mesh has to land on the right pixels. All of that is
done with a few small mathematical objects — vectors, 3×3 and 4×4 matrices, and a compact 3×4
"affine" transform — and a handful of operations on them. This chapter builds that math from
nothing but arithmetic: what a vector is, how to measure lengths and angles, why the dot and cross
products work, what a matrix does to a vector, how a fourth coordinate makes translation a matrix
product, how to undo a transform, and why normals need special care. Then it writes the `math`
module's real code, class by class, and shows the same formulas running on the GPU. It ends with
the tests that prove the code, measured numbers, and exercises.*

**Before you start.** You need [Lesson 0](lessons/00-foundations.md): the notation of §2 (sums with
$\Sigma$ in §2.2, powers and square roots in §2.3, intervals in §2.6), packing bits (§6.4), how a
`float` is stored (§7), objects, arrays and the zero-allocation rule (§8.2), cache lines (§9.3), and
what a GPU, a shader and a buffer are (§10). [Lesson 3](lessons/03-data-layout.md) §4 explains the
struct-of-arrays layout the kernels of Part B use. No trigonometry, geometry or linear algebra is
assumed: §4 introduces angles, sine and cosine, and every formula is derived where it is used.

**What you will build**

| Piece | File | What it does |
|---|---|---|
| Vectors | [`Vec3f`](../../modules/math/src/main/java/dev/jeddy/math/Vec3f.java), [`Vec2f`](../../modules/math/src/main/java/dev/jeddy/math/Vec2f.java), [`Vec4f`](../../modules/math/src/main/java/dev/jeddy/math/Vec4f.java) | immutable records: add, scale, dot, cross, length, normalize, lerp |
| Scratch vector | [`MVec3f`](../../modules/math/src/main/java/dev/jeddy/math/MVec3f.java) | the same operations, changing `this`, for per-frame code that must not allocate |
| Scalar helpers | [`MathF`](../../modules/math/src/main/java/dev/jeddy/math/MathF.java) | clamp, lerp, inverse lerp, smoothstep, frame-rate independent `damp`, angles |
| Vector kernels | [`Vec3Ops`](../../modules/math/src/main/java/dev/jeddy/math/Vec3Ops.java) | loops over struct-of-arrays positions |
| 3×3 matrix | [`Mat3f`](../../modules/math/src/main/java/dev/jeddy/math/Mat3f.java) | product, transpose, determinant, inverse, normal matrix |
| 4×4 matrix | [`Mat4f`](../../modules/math/src/main/java/dev/jeddy/math/Mat4f.java), [`MMat4f`](../../modules/math/src/main/java/dev/jeddy/math/MMat4f.java) | column-major 4×4: translation, scale, TRS, look-at, inverse |
| 4×4 kernels | [`Mat4Ops`](../../modules/math/src/main/java/dev/jeddy/math/Mat4Ops.java) | the same over `float[]` with offsets, allocation-free |
| Affine kernels | [`AffineOps`](../../modules/math/src/main/java/dev/jeddy/math/AffineOps.java) | row-major 3×4 transforms: compose, multiply, hierarchy, invert, boxes |
| Geometry | [`Planef`](../../modules/math/src/main/java/dev/jeddy/math/Planef.java), [`Ray3f`](../../modules/math/src/main/java/dev/jeddy/math/Ray3f.java), [`Spheref`](../../modules/math/src/main/java/dev/jeddy/math/Spheref.java), [`Aabb3f`](../../modules/math/src/main/java/dev/jeddy/math/Aabb3f.java), [`CullOps`](../../modules/math/src/main/java/dev/jeddy/math/CullOps.java) | planes, rays, bounding volumes, the sphere-culling loop |
| Packing | [`Packing`](../../modules/math/src/main/java/dev/jeddy/math/Packing.java) | half floats, snorm and unorm integers, octahedral unit vectors |
| The GPU side | [`instance.glsl`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/engine/instance.glsl) | the vertex shader's transform of points, directions and normals |
| The proof | [`MatrixAndQuatTest`](../../modules/math/src/test/java/dev/jeddy/math/MatrixAndQuatTest.java), [`GeometryAndUtilTest`](../../modules/math/src/test/java/dev/jeddy/math/GeometryAndUtilTest.java), [`MathBench`](../../bench/src/main/java/dev/jeddy/bench/MathBench.java) | tests against hand-checked values and a `double` reference; benchmarks |

Rotations get their own chapter, [03 Rotations and transforms](03-rotations-and-transforms.md), which
builds quaternions and the transform hierarchy; here a rotation is "a matrix that keeps lengths and
angles", and the quaternion formulas inside `composeTRS` are taken as given. Projection matrices and
the camera are in [04 Cameras and projection](04-cameras-and-projection.md). The design is in
[DESIGN §8](../DESIGN.md).

**Contents**

- Part A — The ideas
  1. [What this chapter is for](#1-what-this-chapter-is-for)
  2. [Vectors: arrows and lists of numbers](#2-vectors-arrows-and-lists-of-numbers)
  3. [Adding, scaling and measuring vectors](#3-adding-scaling-and-measuring-vectors)
  4. [Angles, sine and cosine](#4-angles-sine-and-cosine)
  5. [The dot product](#5-the-dot-product)
  6. [The cross product](#6-the-cross-product)
  7. [Matrices](#7-matrices)
  8. [Homogeneous coordinates and affine transforms](#8-homogeneous-coordinates-and-affine-transforms)
  9. [Determinants and mirroring](#9-determinants-and-mirroring)
  10. [Inverses](#10-inverses)
  11. [Transforming normals](#11-transforming-normals)
  12. [Matrices in memory and on the GPU](#12-matrices-in-memory-and-on-the-gpu)
  13. [Compact numbers: half floats, snorm and octahedral vectors](#13-compact-numbers-half-floats-snorm-and-octahedral-vectors)
- Part B — Building it
  14. [Step 1: the module and its three layers](#14-step-1-the-module-and-its-three-layers)
  15. [Step 2: Vec3f, the value type](#15-step-2-vec3f-the-value-type)
  16. [Step 3: Vec2f and Vec4f](#16-step-3-vec2f-and-vec4f)
  17. [Step 4: MVec3f, the scratch vector](#17-step-4-mvec3f-the-scratch-vector)
  18. [Step 5: MathF, the scalar helpers](#18-step-5-mathf-the-scalar-helpers)
  19. [Step 6: Vec3Ops, loops over arrays](#19-step-6-vec3ops-loops-over-arrays)
  20. [Step 7: Mat3f](#20-step-7-mat3f)
  21. [Step 8: Mat4f](#21-step-8-mat4f)
  22. [Step 9: Mat4Ops, the 4×4 kernels](#22-step-9-mat4ops-the-44-kernels)
  23. [Step 10: MMat4f, the scratch matrix](#23-step-10-mmat4f-the-scratch-matrix)
  24. [Step 11: AffineOps, the 3×4 kernels](#24-step-11-affineops-the-34-kernels)
  25. [Step 12: planes, rays, bounding volumes and culling](#25-step-12-planes-rays-bounding-volumes-and-culling)
  26. [Step 13: Packing](#26-step-13-packing)
  27. [Step 14: the GPU side, instance.glsl](#27-step-14-the-gpu-side-instanceglsl)
- Part C — Using it and proving it
  28. [Where the engine uses it](#28-where-the-engine-uses-it)
  29. [Floating point in practice](#29-floating-point-in-practice)
  30. [The tests, walked through](#30-the-tests-walked-through)
  31. [Things to run and measured numbers](#31-things-to-run-and-measured-numbers)
  32. [Exercises](#32-exercises)
  33. [Glossary](#33-glossary)
  34. [Further reading](#34-further-reading)

---

# Part A — The ideas

## 1. What this chapter is for

A game world is a set of things with positions: the torch on the wall, the party's fighter, the
camera. Every frame, the engine has to answer questions like these:

- The fighter walks 3.5 m/s towards the door. Where is she after 1/30 of a second?
- Is the skeleton within 2 m of her? Is it in front of her or behind?
- The fireball's cone is 90° wide. Is the goblin inside it?
- The sword hangs from the fighter's hand, which hangs from her arm. Where is the sword's tip in the
  world?
- The camera sits 20 m away, tilted 55° down. On which pixel does each corner of each mesh land?
- A statue was scaled twice as wide. Which way does each point of its surface face now, so the light
  falls on it correctly?

Each of these is a few multiplications and additions once you have the right objects: vectors for
positions and directions, matrices for "move, turn and scale", and a few products between them. The
engine does them on two processors: the CPU, in Java, for gameplay, physics and culling; and the GPU,
in shaders, for every vertex of every mesh. The math is the same on both. This Part builds it; Part B
writes the Java; Part C shows where the engine uses it and how the tests prove it.

## 2. Vectors: arrows and lists of numbers

### 2.1 A vector is an arrow

Start with a picture. "Walk 3 m east and 4 m north" is a **displacement**: a length (5 m, as §3.2
will show) and a direction. Draw it as an arrow. Where you draw the arrow does not matter: "3 m east,
4 m north" from the door and from the window are the same instruction. A **vector** is exactly that:
an arrow that has a length and a direction, but no fixed place.

```
     north
       ^
       |        tip
       |       ^
       |      /
       |     /   the vector "3 east, 4 north"
       |    /    (same arrow wherever you draw it)
       |   /
       |  /
       | /
       |/ tail
       +-----------------> east
```

### 2.2 A vector is a list of numbers

Arrows are good for thinking but useless for computing. To compute, choose **axes**: a few fixed
directions at right angles to each other, each with a unit of length. Then any arrow is "so far along
the first axis, so far along the second, …". In the plane with an east axis and a north axis, the
arrow above is the list $(3, 4)$. In 3D a vector is three numbers $(x, y, z)$, called its
**components**.

Notation used in this chapter:

- vectors are bold letters: $`\mathbf{v} = (v_x, v_y, v_z)`$, or $`\mathbf{a} = (a_x, a_y, a_z)`$;
- when a formula loops over the components, they are numbered $`v_0, v_1, v_2`$ instead of $x, y, z$,
  so that a sum $`\sum_{i=0}^{2}`$ (L00 §2.2) can run over them;
- plain letters ($s$, $t$, $\theta$) are single numbers, called **scalars**.

In Java, a 3D vector is three `float`s. The engine has three ways to hold them (§14): a record
`Vec3f(float x, float y, float z)`, a mutable `MVec3f` with three public fields, and — for many
vectors at once — three `float[]` arrays, one per component.

### 2.3 Points and vectors are different things

A **point** is a location: "the torch is at (4, 2, −3)". A **vector** is a displacement: "move 1 m
east and 2 m up". Both are written as three numbers, and both use `Vec3f` in Jeddy, but they behave
differently:

- point − point = vector (the displacement from one to the other);
- point + vector = point (move the point);
- vector + vector = vector, and a vector can be scaled;
- point + point has no meaning on its own. Averages such as $\tfrac{1}{2}(\mathbf{p} + \mathbf{q})$
  are fine, because the weights sum to 1.

The difference shows as soon as you move the axes. If you shift the origin 5 m east, every point's
coordinates change, but a displacement such as "1 m east" does not. Later in this chapter this
becomes the difference between transforming a point (translation applies) and transforming a
direction (it does not), §8.3.

### 2.4 The axes: right-handed, Y up

A **coordinate system** is an origin plus three axis directions. Jeddy uses a **right-handed, Y-up**
system:

```
                +Y (up)
                 |
                 |
                 |
                 +-------- +X (right)
                /
               /
             +Z (towards the viewer)

   Right-handed: point the fingers of your right hand along +X,
   curl them towards +Y, and your thumb points along +Z.
```

"Right-handed" is, precisely, a statement about the cross product of §6: $\mathbf{x} \times \mathbf{y}
= \mathbf{z}$. You can check it in code: `Vec3f.UNIT_X.cross(Vec3f.UNIT_Y)` returns `(0, 0, 1)`. In a
left-handed system (traditional Direct3D, Unity) the same three numbers describe a mirrored world.
Mixing the two is a classic source of flipped models and inverted rotations.

For a top-down game it helps to look at the world from above, along −Y. Then +X points right and +Z
points **down** the page. (The default `IsoCamera` sits on the +X, +Z side of its target and looks
towards −X and −Z.)

```
   Seen from above (+Y comes out of the page towards you)

                 -Z
                  ^
                  |
        -X <------+------> +X
                  |
                  v
                 +Z
```

The ground is the XZ plane. Several helpers encode that: `Vec3f.flat()` sets y to 0 ("projected onto
the ground plane"), `Planef.GROUND` is the plane y = 0 facing up, and the 2D tests in `Intersect` work
in `(x, z)`.

### 2.5 Which way is forward

There are two "forwards" in Jeddy, and both are standard:

- The **camera** looks down its own −Z axis. That is the OpenGL convention: `Mat4Ops.lookAt` builds a
  view matrix in which the target ends up on the negative Z axis (§10.3), and `Quatf.forward()`
  returns the rotated −Z axis.
- **Models** face +Z, because glTF assets are authored that way. `AgentSync` turns a character towards
  its motion with `Math.atan2(dx, dz)` (§4.3), which is 0 when the motion is along +Z. Chapter
  [03](03-rotations-and-transforms.md) derives that formula.

### 2.6 Conventions at a glance

Most bugs in 3D code are convention bugs: a sign, an axis, or an index in the wrong place. Keep this
table close. Each entry is explained in this chapter or in chapter 03.

| Topic | Jeddy's choice | Where it is stated |
|---|---|---|
| Handedness | Right-handed: $\mathbf{x} \times \mathbf{y} = \mathbf{z}$ | `Vec3f` Javadoc, DESIGN §8.2 |
| Up axis | +Y; the ground is the XZ plane | `Vec3f.UNIT_Y`, `Planef.GROUND`, `Vec3f.flat()` |
| Camera forward (view space) | −Z | `Mat4Ops.lookAt`, `Quatf.forward()` |
| Model forward | +Z (glTF) | `AgentSync` Javadoc: "models face +Z" |
| Angles | Radians (§4.1) | `MathF.toRadians` for conversions |
| Scalar type | `float` | DESIGN §8.2: "CRPG maps are small" |
| Vectors in products | Column vectors: $\mathbf{v}' = M\mathbf{v}$ (§7.9) | `Mat4f` Javadoc: "Vectors are columns" |
| `Mat4` storage | Column-major, 16 floats, element (row r, column c) at index `c * 4 + r` (§12.1) | `Mat4Ops` Javadoc |
| Affine storage | Row-major 3×4, 12 floats: `[r00 r01 r02 tx, r10 r11 r12 ty, r20 r21 r22 tz]` (§12.2) | `AffineOps` Javadoc, D21 |
| Product order | `a.mul(b)` applies `b` first (§7.6) | `Mat4f`, `AffineOps.mul`, `Quatf` |
| Quaternions | `(x, y, z, w)`, w is the scalar part, Hamilton product | `Quatf` Javadoc, chapter 03 |
| Depth | Reversed-Z, near plane at depth 1 | `Mat4f` Javadoc, chapter 04 |

## 3. Adding, scaling and measuring vectors

### 3.1 Addition and scaling

**Adding** two vectors means doing one displacement and then the other: put the second arrow's tail
on the first arrow's tip, and the sum is the arrow from the first tail to the last tip. With
components, you add axis by axis, because "3 east then 1 east" is "4 east" whatever happens north:

```math
\mathbf{a} + \mathbf{b} = (a_x + b_x,\; a_y + b_y,\; a_z + b_z)
```

```
   y
   ^
 4 |                * (4, 4): the tip of b, so a + b = (4, 4)
 3 |               /
 2 |           b  /        a = (3, 1), b = (1, 3)
 1 |      _____.-*  the tip of a, where b's tail goes
 0 O-----'   a
   +------------------> x
```

**Scaling** a vector by a number $s$ multiplies its length by $s$ and keeps its direction (a negative
$s$ reverses it): $`s\mathbf{v} = (s v_x, s v_y, s v_z)`$. Subtraction is adding the negative:
$\mathbf{a} - \mathbf{b} = \mathbf{a} + (-1)\mathbf{b}$. For points, $\mathbf{q} - \mathbf{p}$ is the
arrow from $\mathbf{p}$ to $\mathbf{q}$.

**Example.** The door is at $\mathbf{p} = (1, 0, 2)$ and the fighter at $\mathbf{q} = (4, 0, 6)$.
The arrow from door to fighter is $\mathbf{q} - \mathbf{p} = (3, 0, 4)$. Half-way between them is
$\mathbf{p} + \tfrac12(\mathbf{q} - \mathbf{p}) = (2.5, 0, 4)$.

Moving by velocity is the most common use. A velocity $\mathbf{v}$ in metres per second, over a time
step $\Delta t$ ("delta t", a small time difference), moves a position by $`\mathbf{v}\,\Delta t`$:
$`\mathbf{p} \leftarrow \mathbf{p} + \mathbf{v}\,\Delta t`$. That one line, written for many entities
at once, is `Vec3Ops.addScaled` (§19).

### 3.2 Length: Pythagoras twice

How long is the arrow $(3, 4)$? It is the long side of a right-angled triangle whose other sides are
3 and 4. **Pythagoras' theorem** says that in a right-angled triangle the squares of the two short
sides add up to the square of the long side (the **hypotenuse**): $3^2 + 4^2 = 9 + 16 = 25 = 5^2$, so
the length is 5.

Why is that true? Take a square whose sides are $a + b$ long. Mark each side at distance $a$ from
one corner, going round the square in the same direction each time, and join the four marks. The
joining lines cut off four right-angled triangles with legs $a$ and $b$, one in each corner, and what
is left in the middle is a tilted square whose side is the triangles' hypotenuse $c$:

```
   +------a------*---b---+     each side of the big square: a then b
   |          .    .     |
   b       .         .   a     the four corner triangles have legs a and b;
   |    .               .|     the dotted lines are their hypotenuses, c,
   *.       tilted       *     which form the tilted square in the middle
   | .      square     . |
   a   .     side c   .  b
   |     .          .    |
   +---b---*-------a-----+
```

Now count the big square's area two ways. As a square: $(a + b)^2 = a^2 + 2ab + b^2$. As its pieces:
four triangles of area $\tfrac12 ab$ each plus the tilted square, $4 \cdot \tfrac12 ab + c^2 = 2ab +
c^2$. Setting the two equal, $a^2 + 2ab + b^2 = 2ab + c^2$, so $a^2 + b^2 = c^2$.

In 3D apply it twice. The vector $(x, y, z)$ first goes $x$ along X and $z$ along Z, on the ground: by
Pythagoras that ground part is $\sqrt{x^2 + z^2}$ long. Then it rises $y$, at right angles to the
ground. A second right-angled triangle, with legs $\sqrt{x^2 + z^2}$ and $y$, gives the full length:

$$\lVert \mathbf{v} \rVert = \sqrt{\left(\sqrt{x^2 + z^2}\right)^2 + y^2} = \sqrt{x^2 + y^2 + z^2}$$

The double bars $\lVert \mathbf{v} \rVert$ mean "the length of". **Example:** $(1, 2, 2)$ has ground
part $\sqrt{1 + 4} = \sqrt5$ and length $\sqrt{5 + 4} = 3$. In code this is `Vec3f.length()`,
`(float) Math.sqrt(x * x + y * y + z * z)` (§15). The **distance** between two points is the length of
their difference, $\lVert \mathbf{q} - \mathbf{p} \rVert$: door to fighter above is
$\lVert(3, 0, 4)\rVert = 5$ m.

### 3.3 Unit vectors and normalization

A **unit vector** has length 1. It describes a pure direction: "towards the door", without "how far".
Directions are stored as unit vectors all over the engine: a light's direction, a surface's normal
(§5.5), a ray's direction (§25). A unit vector gets a hat: $\hat{\mathbf{v}}$.

To get the direction of any vector, divide it by its length. This is called **normalizing**:

$$\hat{\mathbf{v}} = \frac{\mathbf{v}}{\lVert \mathbf{v} \rVert} \qquad\text{since}\qquad \left\lVert \frac{\mathbf{v}}{L} \right\rVert = \frac{\lVert \mathbf{v} \rVert}{L} = 1 \text{ when } L = \lVert \mathbf{v} \rVert$$

**Example:** $(1, 2, 2)$ has length 3, so its direction is $(\tfrac13, \tfrac23, \tfrac23)$. In `float`
that is `(0.33333334, 0.6666667, 0.6666667)`: $\tfrac13$ has no exact binary form (L00 §7.1), so each
component is the nearest float.

The zero vector has no direction, and dividing by its length 0 would give NaN (L00 §7.3). Code that
normalizes has to choose what to return. `Vec3f.normalize` returns `ZERO`; `Quatf.normalize` returns
the identity rotation; `Vec3Ops.normalize` leaves zero vectors at zero (§15, §19).

### 3.4 Compare squared lengths

The square root is the expensive part of a length: a multiply or an add takes a few CPU cycles, a
square root ten or more. Many questions do not need it. Because the square root grows when its input
grows (it is **monotonic**), for numbers that are not negative

$$a < b \iff a^2 < b^2$$

(read "$\iff$" as "exactly when"). So "is the point within 2 m of the centre?" is "is the squared
distance at most 4?". **Example:** centre $(0, 0, 0)$, radius 2, point $(1, 1, 1)$: squared distance
$1 + 1 + 1 = 3 \le 4$, inside, without a single square root. That is `Spheref.contains` (§25), and
`Vec3Ops.distanceSquared` exists for the same reason in batch form (§19).

### 3.5 Linear interpolation and its relatives

Moving a fraction $t$ of the way from $a$ to $b$ is **linear interpolation**, "lerp". Start at $a$
and add the fraction $t$ of the difference:

```math
\operatorname{lerp}(a, b, t) = a + (b - a)\,t = (1 - t)\,a + t\,b
```

The second form comes from multiplying out: $a + bt - at = (1 - t)a + tb$. At $t = 0$ you get $a$, at
$t = 1$ you get $b$, at $t = 0.25$ a quarter of the way: $\operatorname{lerp}(10, 20, 0.25) = 12.5$.
Values of $t$ outside $[0, 1]$ go past the ends (**extrapolation**). It works the same for numbers,
points, colours and — component by component — vectors and matrices. `MathF.lerp`, `Vec3f.lerp`,
`MVec3f.lerp`, `Vec3Ops.lerp` and `AffineOps.lerp` all use the first form; §29 explains why the two
forms are not the same in `float`.

Three relatives, all in `MathF` (§18):

- **Inverse lerp** answers "how far between $a$ and $b$ is $v$?". Solve $v = a + (b - a)t$ for $t$:
  $t = \frac{v - a}{b - a}$. **Example:** $\operatorname{inverseLerp}(10, 20, 15) = 0.5$. When
  $a = b$ there is no answer; the code returns 0 instead of dividing by zero.
- **Remap** maps a value from one range to another: find where it sits in the first range, then go
  that far into the second. $`\operatorname{remap}(v, i_0, i_1, o_0, o_1) = \operatorname{lerp}(o_0, o_1, \operatorname{inverseLerp}(i_0, i_1, v))`$. **Example:** 15 in $[10, 20]$ remapped to
  $[0, 100]$ is 50.
- **Smoothstep** is an S-shaped version of $t$ that starts and ends gently. Clamp
  $t = \operatorname{inverseLerp}(a, b, v)$ to $[0, 1]$, then take $s(t) = t^2(3 - 2t)$. It still
  gives $s(0) = 0$, $s(1) = 1$ and $s(\tfrac12) = \tfrac14 \cdot 2 = \tfrac12$, but near the ends it
  barely moves: $s(0.01) = 0.0001 \cdot 2.98 = 0.000298$, where plain $t$ would be $0.01$. (The
  steepness of $s$ at $t$ is $6t(1 - t)$, which is 0 at both ends; you do not need that formula, only
  the picture: it eases in and eases out.) $s(0.25) = 0.0625 \cdot 2.5 = 0.15625$.

```
 s(t)
  1 |                         ..------  smoothstep: flat at both ends
    |                    .-''        .  lerp: a straight line
    |                .-'        .
    |             .'      .
    |          .'    .
    |        .' .
    |      .:
    |  ..-'
  0 +----------------------------------- t
    0                                  1
```

### 3.6 Frame-rate independent smoothing: damp

A camera that follows the party should glide, not jump. A common way to write that is to move a fixed
fraction of the remaining distance every frame: `x = lerp(x, target, 0.1f)`. It has a flaw: it runs
once per frame, so it moves faster on a fast machine. After one second at 60 frames per second the
remaining distance is $0.9^{60} \approx 0.0018$ of what it was; at 30 frames per second it is
$0.9^{30} \approx 0.042$, 24 times more. The game feels different on every machine.

What do we want? Let $f(\Delta t)$ be the fraction of the distance that is **left** after a step of
$\Delta t$ seconds. Frame-rate independence means that one step of $`\Delta t_1 + \Delta t_2`$ leaves the
same as a step of $`\Delta t_1`$ followed by a step of $`\Delta t_2`$:

$$f(\Delta t_1 + \Delta t_2) = f(\Delta t_1) \cdot f(\Delta t_2)$$

A function that turns sums into products is a power: $f(\Delta t) = r^{\Delta t}$ for some number
$r$, because $r^{a + b} = r^a r^b$ (L00 §2.3). Here $r$ is the fraction left after one second. The
standard way to write a power is with the number $e \approx 2.71828$ and the function
$e^x$ = `Math.exp(x)`: $r^{\Delta t} = e^{-\lambda \Delta t}$ with $\lambda$ chosen so that $e^{-\lambda}
= r$. The number $\lambda$ ("lambda") is the **decay rate**: for a short step, $`e^{-\lambda \Delta t} \approx 1 - \lambda\,\Delta t`$, so about $`\lambda\,\Delta t`$ of the remaining distance goes per step —
"$\lambda$ per second". With $x^\ast$ the target, the update is

```math
x \leftarrow x^\ast + (x - x^\ast)\,e^{-\lambda \Delta t} = \operatorname{lerp}\!\left(x^\ast,\; x,\; e^{-\lambda \Delta t}\right)
```

which is exactly `MathF.damp(current, target, lambda, dt)` (§18). **Worked numbers:** `damp(0, 10, 5,
1/60f)`: $e^{-5/60} = 0.920044$, so $x = 10 + (0 - 10) \cdot 0.920044 = 0.7995558$. Two steps of
1/120 s give 0.7995548: the same up to `float` rounding (a difference of 1 in the sixth significant
digit). The **half-life**, the time to close half the gap, solves $e^{-\lambda t} = \tfrac12$: $t =
\ln 2 / \lambda$, where $\ln$ ("natural logarithm", `Math.log`) undoes `Math.exp`. For $\lambda = 5$
that is $0.693 / 5 = 0.139$ s.

The camera controller uses the same idea written the other way round:
`IsoCameraController.update` computes `k = 1 - (float) Math.exp(-dt / smoothing)` and moves
`distance += (goalDistance - distance) * k`, which is `damp` with $\lambda = 1/\text{smoothing}$.

## 4. Angles, sine and cosine

The dot product (§5), rotations (§7.3) and lighting all talk about angles. This section gives you the
three facts they need.

### 4.1 Radians

Draw a circle of radius 1 (the **unit circle**) around the origin. An angle at the centre cuts off a
piece of the circle's edge, an **arc**. The **radian** measure of the angle is the length of that arc.
The whole circle's edge is $2\pi \approx 6.2832$ long ($\pi \approx 3.14159$ is the ratio of any
circle's circumference to its diameter), so:

| Turn | Degrees | Radians |
|---|---|---|
| full turn | 360° | $2\pi \approx 6.2832$ (`MathF.TAU`) |
| half turn | 180° | $\pi \approx 3.1416$ (`MathF.PI`) |
| quarter turn | 90° | $\pi/2 \approx 1.5708$ (`MathF.HALF_PI`) |
| one radian | $\approx 57.2958°$ | 1 |

To convert, multiply degrees by $\pi / 180$: `MathF.toRadians(55)` is `0.9599311` (the camera's
default pitch). Java's `Math.sin` and `Math.cos`, GLSL's `sin` and `cos`, and every angle in the
engine use radians. Mixing in degrees is a classic bug: `Math.cos(90)` is $-0.448$, not 0.

### 4.2 Sine and cosine on the unit circle

Walk an angle $\theta$ ("theta") counter-clockwise around the unit circle, starting from the point
$(1, 0)$. Where you stop has two coordinates. They are given names:

```math
\text{the point at angle } \theta = (\cos\theta,\; \sin\theta)
```

```
            y
            ^
          1 +       . (cos θ, sin θ)
            |    .'/|
            |  .' / |
            | .  /  | sin θ
            |.  / θ |
   ---------+--+----+-----> x
           0|  cos θ  1
```

The **cosine** is how far right, the **sine** how far up. A few values you will meet:

| $\theta$ | 0 | 30° | 45° | 60° | 90° | 180° |
|---|---|---|---|---|---|---|
| $\cos\theta$ | 1 | 0.8660 | 0.7071 | 0.5 | 0 | −1 |
| $\sin\theta$ | 0 | 0.5 | 0.7071 | 0.8660 | 1 | 0 |

Two facts follow straight from the picture:

- **$\cos^2\theta + \sin^2\theta = 1$.** The point is on a circle of radius 1, and by Pythagoras
  (§3.2) its distance from the origin is $\sqrt{\cos^2\theta + \sin^2\theta}$. ($\cos^2\theta$ means
  $(\cos\theta)^2$.)
- **In a right-angled triangle**, scale the picture: if the hypotenuse is $h$ long and makes the angle
  $\theta$ with one leg, that leg is $h\cos\theta$ long and the other $h\sin\theta$. This is what turns
  "an arrow of length $L$ at angle $\theta$" into components.

### 4.3 Going back: arccos and atan2

Sometimes you have the cosine and want the angle. `Math.acos(c)` (**arccos**) returns the angle in
$[0, \pi]$ whose cosine is $c$: `Math.acos(0.6)` is 0.9273 rad, 53.13°. It cannot tell a turn to the
left from a turn to the right: both have the same cosine.

`Math.atan2(y, x)` takes both coordinates of a point and returns its full angle in $(-\pi, \pi]$, sign
included. `AgentSync` calls `Math.atan2(dx, dz)` — note the order — to get a character's heading
measured from +Z; chapter [03](03-rotations-and-transforms.md) explains it. `MathF.wrapAngle` brings
any angle back into $(-\pi, \pi]$, and `MathF.lerpAngle` interpolates two angles the short way round
(from 170° to −170° through 180°, not through 0°).

## 5. The dot product

### 5.1 Definition

The **dot product** of two vectors multiplies them component by component and adds the results. It
is a single number:

$$\mathbf{a} \cdot \mathbf{b} = a_x b_x + a_y b_y + a_z b_z = \sum_{i=0}^{2} a_i b_i$$

The $\Sigma$ form (L00 §2.2) is a loop; in Java,

```java
float dot = 0;
for (int i = 0; i < 3; i++) {
    dot += a[i] * b[i];
}
```

**Example:** $(3, 0, 4) \cdot (1, 0, 0) = 3 \cdot 1 + 0 \cdot 0 + 4 \cdot 0 = 3$, and $(1, 2, 2)
\cdot (2, -1, 0) = 2 - 2 + 0 = 0$.

Rules that follow from the definition (check each with the components):

- order does not matter: $\mathbf{a} \cdot \mathbf{b} = \mathbf{b} \cdot \mathbf{a}$;
- it spreads over sums and lets numbers out: $\mathbf{a} \cdot (\mathbf{b} + \mathbf{c}) = \mathbf{a}
  \cdot \mathbf{b} + \mathbf{a} \cdot \mathbf{c}$ and $(s\mathbf{a}) \cdot \mathbf{b} = s(\mathbf{a}
  \cdot \mathbf{b})$;
- a vector dotted with itself is its squared length: $`\mathbf{a} \cdot \mathbf{a} = a_x^2 + a_y^2 + a_z^2 = \lVert \mathbf{a} \rVert^2`$.

### 5.2 Why it measures angles

Here is the surprising part. The same number also equals

```math
\mathbf{a} \cdot \mathbf{b} = \lVert \mathbf{a} \rVert \, \lVert \mathbf{b} \rVert \cos\theta
```

where $\theta$ is the angle between the two arrows when their tails touch. Why should adding products
of components know about angles? Two steps.

**Step 1: the law of cosines.** Take the triangle with sides $\mathbf{a}$, $\mathbf{b}$ (tails
together at $O$) and $\mathbf{a} - \mathbf{b}$ (from the tip of $\mathbf{b}$ to the tip of
$\mathbf{a}$). Drop a line from the tip of $\mathbf{b}$ straight down onto the line of $\mathbf{a}$:

```
                 tip of b
                 *
                /|\
               / | \  a - b
          b   /  |  \
             /   |h  \
            / θ  |    \
         O *-----+-----* tip of a
            <-p-> <-q->
            <------a------>
```

By §4.2 the foot of that line is $p = \lVert \mathbf{b} \rVert\cos\theta$ along $\mathbf{a}$, and its
height is $h = \lVert \mathbf{b} \rVert\sin\theta$. The rest of $\mathbf{a}$ is $q = \lVert
\mathbf{a} \rVert - \lVert \mathbf{b} \rVert\cos\theta$. The right-angled triangle on the right has
legs $q$ and $h$ and hypotenuse $\mathbf{a} - \mathbf{b}$, so by Pythagoras:

$$\lVert \mathbf{a} - \mathbf{b} \rVert^2 = \left(\lVert \mathbf{a} \rVert - \lVert \mathbf{b} \rVert\cos\theta\right)^2 + \left(\lVert \mathbf{b} \rVert\sin\theta\right)^2$$

Multiply out the first square and use $\cos^2\theta + \sin^2\theta = 1$:

$$\lVert \mathbf{a} - \mathbf{b} \rVert^2 = \lVert \mathbf{a} \rVert^2 - 2\lVert \mathbf{a} \rVert\lVert \mathbf{b} \rVert\cos\theta + \lVert \mathbf{b} \rVert^2(\cos^2\theta + \sin^2\theta) = \lVert \mathbf{a} \rVert^2 + \lVert \mathbf{b} \rVert^2 - 2\lVert \mathbf{a} \rVert\lVert \mathbf{b} \rVert\cos\theta$$

(If the angle is wider than 90°, the foot falls behind $O$ and $\cos\theta$ is negative; the same
algebra holds.)

**Step 2: the same length with components.** Expand $\lVert \mathbf{a} - \mathbf{b} \rVert^2$ with the
rules of §5.1 instead:

```math
\lVert \mathbf{a} - \mathbf{b} \rVert^2 = (\mathbf{a} - \mathbf{b}) \cdot (\mathbf{a} - \mathbf{b}) = \mathbf{a} \cdot \mathbf{a} - 2\,\mathbf{a} \cdot \mathbf{b} + \mathbf{b} \cdot \mathbf{b} = \lVert \mathbf{a} \rVert^2 + \lVert \mathbf{b} \rVert^2 - 2\,\mathbf{a} \cdot \mathbf{b}
```

Both lines describe the same length. Everything matches except the last terms, so
$\mathbf{a} \cdot \mathbf{b} = \lVert \mathbf{a} \rVert \lVert \mathbf{b} \rVert \cos\theta$. ∎

**Numeric check.** $\mathbf{a} = (3, 0, 4)$, $\mathbf{b} = (1, 0, 0)$. Components: $\mathbf{a} \cdot
\mathbf{b} = 3$, $\lVert \mathbf{a} \rVert = 5$, $\lVert \mathbf{b} \rVert = 1$, so $\cos\theta = 3/5
= 0.6$ and $\theta = \arccos 0.6 = 53.13°$. The law of cosines: $\mathbf{a} - \mathbf{b} = (2, 0, 4)$,
squared length $4 + 16 = 20$; and $25 + 1 - 2 \cdot 5 \cdot 1 \cdot 0.6 = 20$. ✓

What the formula gives you:

- **The angle:** $\cos\theta = \frac{\mathbf{a} \cdot \mathbf{b}}{\lVert \mathbf{a} \rVert \lVert
  \mathbf{b} \rVert}$. For unit vectors it is just the dot product.
- **Alignment at a glance**, for unit vectors: 1 means the same direction, 0 perpendicular, −1
  opposite. $(1, 2, 2) \cdot (2, -1, 0) = 0$ above: those two are perpendicular.
- **Sign:** positive when the angle is under 90° ("roughly the same way"), negative when it is over.

### 5.3 Projection and Gram–Schmidt

If $\hat{\mathbf{n}}$ is a unit vector, $\mathbf{a} \cdot \hat{\mathbf{n}} = \lVert \mathbf{a}
\rVert\cos\theta$ is the length of $\mathbf{a}$'s "shadow" on the line of $\hat{\mathbf{n}}$ — the
$p$ of the picture in §5.2, with a sign. That is the **projection** of $\mathbf{a}$ onto
$\hat{\mathbf{n}}$:

- the **scalar projection** is $\mathbf{a} \cdot \hat{\mathbf{n}}$, "how far $\mathbf{a}$ goes along
  $\hat{\mathbf{n}}$";
- the **vector projection** is $`(\mathbf{a} \cdot \hat{\mathbf{n}})\,\hat{\mathbf{n}}`$, the shadow as
  an arrow;
- what is left, $`\mathbf{a} - (\mathbf{a} \cdot \hat{\mathbf{n}})\,\hat{\mathbf{n}}`$, is perpendicular
  to $\hat{\mathbf{n}}$. Check: dot it with $\hat{\mathbf{n}}$ to get
  $\mathbf{a} \cdot \hat{\mathbf{n}} - (\mathbf{a} \cdot \hat{\mathbf{n}})(\hat{\mathbf{n}} \cdot \hat{\mathbf{n}}) = 0$,
  since $\hat{\mathbf{n}} \cdot \hat{\mathbf{n}} = 1$.

**Example.** $\mathbf{a} = (3, 0, 4)$ onto $\hat{\mathbf{n}} = (1, 0, 0)$: scalar projection 3, vector
projection $(3, 0, 0)$, perpendicular part $(0, 0, 4)$.

Making one vector perpendicular to another by subtracting its projection is called
**Gram–Schmidt orthogonalization**. A tangent vector, which runs along a surface, must be
perpendicular to the surface's normal, but after interpolation or a normal map it rarely is
exactly. **Example:** normal $\hat{\mathbf{n}} = (0, 1, 0)$, tangent $\mathbf{t} = (1, 0.2, 0)$:
$\mathbf{t} - \hat{\mathbf{n}}(\hat{\mathbf{n}} \cdot \mathbf{t}) = (1, 0.2, 0) - (0, 0.2, 0) =
(1, 0, 0)$. The fragment shader does it per pixel; from
[`pbr.frag`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/pbr/pbr.frag):

```glsl
    vec3 t = normalize(vTangent.xyz - n * dot(n, vTangent.xyz));
    vec3 b = cross(n, t) * vTangent.w;
```

The second line builds the third axis with the cross product of §6. The asset encoder does the same
before it stores a vertex's tangent frame: `MeshEncoding.encodeQTangent` takes the tangent
"orthogonalized against the normal here" (its Javadoc), computing `d = nx * tx + ny * ty + nz * tz` and
subtracting `nx * d` and so on from the tangent — $\mathbf{t} - \hat{\mathbf{n}}(\hat{\mathbf{n}} \cdot
\mathbf{t})$ written out.

### 5.4 Signs, half-spaces and planes

A **plane** is a flat surface that goes on for ever. Describe it by one point $`\mathbf{p}_0`$ on it and
a unit **normal** $\hat{\mathbf{n}}$, the direction perpendicular to it. A point $\mathbf{p}$ is on the
plane exactly when the arrow from $`\mathbf{p}_0`$ to $\mathbf{p}$ runs along the plane, that is, is
perpendicular to the normal:

$$(\mathbf{p} - \mathbf{p}_0) \cdot \hat{\mathbf{n}} = 0 \iff \hat{\mathbf{n}} \cdot \mathbf{p} + d = 0 \quad\text{with}\quad d = -\hat{\mathbf{n}} \cdot \mathbf{p}_0$$

For a point that is not on the plane, the same expression $\hat{\mathbf{n}} \cdot \mathbf{p} + d$ is
the scalar projection of $`\mathbf{p} - \mathbf{p}_0`$ onto the normal: the **signed distance** of
$\mathbf{p}$ from the plane. Positive means "on the side the normal points to" (in front), negative
behind. The plane splits space into two **half-spaces**, and one dot product says which one a point is
in.

**Example.** The ground $y = 0$ facing up: $\hat{\mathbf{n}} = (0, 1, 0)$, $d = 0$. The point
$(5, 2, 5)$ has signed distance 2: it is 2 m above the ground. That is `Planef.distance` (§25), and
the test `valueRecords` checks this very number. A frustum is six such planes, and a sphere is
visible when its centre is no further than its radius behind each (§25, chapter 04).

### 5.5 Lighting: Lambert's cosine law

Why does a surface facing a lamp look brighter than one tilted away? A beam of light of width $w$
falling straight onto a surface lights a strip $w$ wide. Tilt the surface by $\theta$ and the same
beam spreads over a strip $w / \cos\theta$ wide (the right-angled triangle of §4.2 again), so each
square metre receives $\cos\theta$ times as much light. This is **Lambert's cosine law**. With
$\hat{\mathbf{n}}$ the surface normal and $\hat{\mathbf{l}}$ the unit direction towards the light,
$\cos\theta = \hat{\mathbf{n}} \cdot \hat{\mathbf{l}}$. A light at 45° gives
$(0, 1, 0) \cdot (0.7071, 0.7071, 0) = 0.7071$: 71 % of the full strength. A negative value means the
light is behind the surface, so it is clamped to 0. From
[`brdf.glsl`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/engine/brdf.glsl):

```glsl
    float NoL = clamp(dot(s.normal, l), 0.0, 1.0);
    if (NoL <= 0.0) {
        return vec3(0.0);
    }
```

`NoL` reads "N dot L". This runs in the **fragment shader** (L00 §10.2): once per covered pixel, per
light, on the GPU. A `dot` of two `vec3`s is three multiplies and two adds, which GPUs do as a short
chain of **fused multiply-add** instructions (one instruction that computes $a \cdot b + c$ with a
single rounding). Chapter [07](07-lighting-and-shadows.md) explains the rest of
that function.

### 5.6 More places the engine uses it

*Closest point on a segment.* Where on the segment from $\mathbf{a}$ to $\mathbf{b}$ is the point
nearest to $\mathbf{p}$? Project $\mathbf{p} - \mathbf{a}$ onto the segment's direction
$\mathbf{b} - \mathbf{a}$. To get a parameter $t$ that is 0 at $\mathbf{a}$ and 1 at $\mathbf{b}$
rather than a distance, divide by the squared length once more:
$t = \frac{(\mathbf{p} - \mathbf{a}) \cdot (\mathbf{b} - \mathbf{a})}{\lVert \mathbf{b} - \mathbf{a}
\rVert^2}$, then clamp it to $[0, 1]$ so the point stays on the segment. From
[`Intersect.closestPointOnSegment`](../../modules/math/src/main/java/dev/jeddy/math/Intersect.java):

```java
        float len2 = abx * abx + aby * aby + abz * abz;
        if (len2 == 0) {
            return 0;
        }
        float t = ((px - ax) * abx + (py - ay) * aby + (pz - az) * abz) / len2;
        return Math.max(0, Math.min(1, t));
```

**Example:** $\mathbf{a} = (0, 0, 0)$, $\mathbf{b} = (4, 0, 0)$, $\mathbf{p} = (1, 2, 0)$: $t = 4 / 16
= 0.25$, the point $(1, 0, 0)$, at distance 2. A zero-length segment has no direction; the code
returns $t = 0$ rather than divide by zero. Capsules (a character's collision shape) and the
"threat zone" tests are built on this.

*Projection plus Pythagoras: the cone of a cone spell*, in `Intersect.coneSphere`. With $\mathbf{v}$
the vector from the cone's apex to a target and $\hat{\mathbf{u}}$ the cone's axis, `along` is the
scalar projection (how far down the axis the target is) and `perp` the remaining leg of the right
triangle (how far from the axis):

```java
        float lenSq = vx * vx + vy * vy + vz * vz;
        float along = vx * axisX + vy * axisY + vz * axisZ;
        float perp = (float) Math.sqrt(Math.max(0, lenSq - along * along));
```

The `Math.max(0, …)` guards against rounding: for a target exactly on the axis, `lenSq - along *
along` should be 0 but can come out as a tiny negative number, and the square root of a negative is
NaN.

## 6. The cross product

### 6.1 Looking for a perpendicular vector

Given two vectors $\mathbf{a}$ and $\mathbf{b}$ that are not parallel, can we find a vector
$\mathbf{n}$ perpendicular to both? That is the normal of a triangle, the "up" of a camera, the axis of
a turn. By §5.2, perpendicular means a dot product of 0, so we need

$$a_x n_x + a_y n_y + a_z n_z = 0 \qquad (1) \qquad\qquad b_x n_x + b_y n_y + b_z n_z = 0 \qquad (2)$$

Two equations, three unknowns: there is a whole line of answers (any length), and we want one.
Eliminate $`n_z`$: multiply (1) by $`b_z`$ and (2) by $`a_z`$ and subtract:

```math
(a_x b_z - a_z b_x)\,n_x + (a_y b_z - a_z b_y)\,n_y = 0
```

One solution of that is $`n_x = a_y b_z - a_z b_y`$ and $`n_y = -(a_x b_z - a_z b_x) = a_z b_x - a_x b_z`$
(plug them in: the two products cancel). Eliminating $`n_x`$ the same way gives $`n_z = a_x b_y - a_y b_x`$.
This vector is the **cross product**:

```math
\mathbf{a} \times \mathbf{b} = (a_y b_z - a_z b_y,\;\; a_z b_x - a_x b_z,\;\; a_x b_y - a_y b_x)
```

Check that it really is perpendicular to $\mathbf{a}$:

$$\mathbf{a} \cdot (\mathbf{a} \times \mathbf{b}) = a_x(a_y b_z - a_z b_y) + a_y(a_z b_x - a_x b_z) + a_z(a_x b_y - a_y b_x) = 0$$

Every product appears twice with opposite signs ($`a_x a_y b_z`$ and $`-a_y a_x b_z`$, and so on). The same
holds for $\mathbf{b}$.

The pattern is easy to rebuild: for the x component use the other two axes in cyclic order (y, z), for
y use (z, x), for z use (x, y) — each time "first times second minus second times first". `Vec3f.cross`
(§15) is this line, character for character.

### 6.2 Its length is an area

How long is $\mathbf{a} \times \mathbf{b}$? Square it and expand (it is long but mechanical; exercise
1 does it in full):

$$\lVert \mathbf{a} \times \mathbf{b} \rVert^2 = \lVert \mathbf{a} \rVert^2 \lVert \mathbf{b} \rVert^2 - (\mathbf{a} \cdot \mathbf{b})^2$$

This is **Lagrange's identity**. Now use §5.2, $(\mathbf{a} \cdot \mathbf{b})^2 = \lVert \mathbf{a}
\rVert^2 \lVert \mathbf{b} \rVert^2 \cos^2\theta$, and $1 - \cos^2\theta = \sin^2\theta$ (§4.2):

$$\lVert \mathbf{a} \times \mathbf{b} \rVert^2 = \lVert \mathbf{a} \rVert^2 \lVert \mathbf{b} \rVert^2 (1 - \cos^2\theta) = \lVert \mathbf{a} \rVert^2 \lVert \mathbf{b} \rVert^2 \sin^2\theta \quad\Rightarrow\quad \lVert \mathbf{a} \times \mathbf{b} \rVert = \lVert \mathbf{a} \rVert \lVert \mathbf{b} \rVert \sin\theta$$

That is the **area of the parallelogram** spanned by $\mathbf{a}$ and $\mathbf{b}$: base $\lVert
\mathbf{a} \rVert$ times height $\lVert \mathbf{b} \rVert\sin\theta$ (the $h$ in the picture of
§5.2). Half of it is the area of the triangle with those two sides.

**Numeric check.** $\mathbf{a} = (1, 2, 3)$, $\mathbf{b} = (4, 5, 6)$:
$`\mathbf{a} \times \mathbf{b} = (2 \cdot 6 - 3 \cdot 5,\; 3 \cdot 4 - 1 \cdot 6,\; 1 \cdot 5 - 2 \cdot 4) = (-3, 6, -3)`$. Perpendicular: $(1, 2, 3) \cdot (-3, 6, -3) = -3 + 12 - 9 = 0$ and $(4, 5, 6) \cdot
(-3, 6, -3) = -12 + 30 - 18 = 0$. Length squared: $9 + 36 + 9 = 54$; Lagrange: $14 \cdot 77 - 32^2 =
1078 - 1024 = 54$. ✓

### 6.3 Its direction: the right-hand rule

There are two directions perpendicular to $\mathbf{a}$ and $\mathbf{b}$, opposite to each other. The
formula picks one. Try the axes: $`(1, 0, 0) \times (0, 1, 0) = (0 \cdot 0 - 0 \cdot 1,\; 0 \cdot 0 - 1 \cdot 0,\; 1 \cdot 1 - 0 \cdot 0) = (0, 0, 1)`$. So $\mathbf{x} \times \mathbf{y} = \mathbf{z}$, and
the same way $\mathbf{y} \times \mathbf{z} = \mathbf{x}$ and $\mathbf{z} \times \mathbf{x} =
\mathbf{y}$. In a right-handed system (§2.4) that is the **right-hand rule**: fingers along
$\mathbf{a}$, curl them towards $\mathbf{b}$, and the thumb gives $\mathbf{a} \times \mathbf{b}$.

The formula itself does not know about hands; the axes do. The same numbers in a left-handed system
would point the result the "left-hand" way. That is why §2.6 states handedness as
$\mathbf{x} \times \mathbf{y} = \mathbf{z}$.

### 6.4 Properties and a worked example

- $\mathbf{a} \times \mathbf{b}$ is **perpendicular** to both $\mathbf{a}$ and $\mathbf{b}$ (§6.1).
- Its **length** is $\lVert \mathbf{a} \rVert \lVert \mathbf{b} \rVert \sin\theta$, the parallelogram's
  area (§6.2).
- It is **anti-commutative**: $\mathbf{b} \times \mathbf{a} = -(\mathbf{a} \times \mathbf{b})$. Swap
  the operands and every component changes sign (each is "first times second minus second times
  first"). `new Vec3f(4, 5, 6).cross(new Vec3f(1, 2, 3))` is `(3, -6, 3)`. Many sign bugs are an
  accidental swap.
- **Parallel vectors give the zero vector** ($\sin 0 = 0$), and so does a zero vector.

**Worked example.** $`(2, 0, 0) \times (0, 0, -3) = (0 \cdot (-3) - 0 \cdot 0,\; 0 \cdot 0 - 2 \cdot (-3),\; 2 \cdot 0 - 0 \cdot 0) = (0, 6, 0)`$. The vectors are perpendicular with lengths 2 and 3, so the
area is 6. The result points up: x crossed with −z is +y, because $\mathbf{x} \times (-\mathbf{z}) =
-(\mathbf{x} \times \mathbf{z}) = \mathbf{z} \times \mathbf{x} = \mathbf{y}$. (Java prints it as
`Vec3f[x=-0.0, y=6.0, z=0.0]`: $0 \cdot (-3)$ is $-0.0$ in IEEE arithmetic, a zero with the sign bit
set, L00 §7.2. It equals `0.0` under `==`, but §15 shows where it does not.)

In 2D there is no third axis to point along, but the same formula with $z = 0$ inputs leaves only the
z component, $`a_x b_y - a_y b_x`$: a single number, the **signed area** of the parallelogram. It is
positive when $\mathbf{b}$ is counter-clockwise from $\mathbf{a}$ and negative when clockwise.
`Vec2f.cross` returns it ("twice the signed triangle area"), and polygon code uses its sign to tell left
turns from right turns.

### 6.5 Where the engine uses it

*Triangle normals.* The normal of the triangle $\mathbf{a}, \mathbf{b}, \mathbf{c}$ is $(\mathbf{b} -
\mathbf{a}) \times (\mathbf{c} - \mathbf{a})$, normalized. Its direction depends on the order of the
corners: by the right-hand rule it points towards the side from which $\mathbf{a} \to \mathbf{b} \to
\mathbf{c}$ runs counter-clockwise. From
[`Planef.fromPoints`](../../modules/math/src/main/java/dev/jeddy/math/Planef.java):

```java
    /** Returns the plane through three points, facing towards the side where a→b→c is CCW. */
    public static Planef fromPoints(Vec3f a, Vec3f b, Vec3f c) {
        return fromPointNormal(a, b.sub(a).cross(c.sub(a)));
    }
```

With $\mathbf{a} = (0, 0, 0)$, $\mathbf{b} = (0, 0, 1)$, $\mathbf{c} = (1, 0, 0)$: $(0, 0, 1) \times
(1, 0, 0) = (0, 1, 0)$, up. Seen from above, origin → +Z (down the page) → +X (right) is a
counter-clockwise turn. The triangle's area is half the length, 0.5. The order of a triangle's corners
— its **winding** — is how the GPU tells a triangle's front from its back (§9.4).

*Building perpendicular axes.* Given a forward direction $\mathbf{f}$ and a rough "up" $`\mathbf{u}_0`$,
two cross products give three mutually perpendicular axes: right $`= \mathbf{f} \times \mathbf{u}_0`$
(normalized), then the true up $= \text{right} \times \mathbf{f}$. `Mat4f.lookAt` starts like this:

```java
        Vec3f f = target.sub(eye).normalize();
        Vec3f r = f.cross(up).normalize();
        Vec3f u = r.cross(f);
```

`u` needs no normalizing: `r` and `f` are perpendicular unit vectors, so $\lVert \mathbf{r} \times
\mathbf{f} \rVert = 1 \cdot 1 \cdot \sin 90° = 1$. If `up` were parallel to `f` (a camera looking
straight down with "up" = +Y), the first cross product would be zero and normalizing it would give
`ZERO`; `Quatf.lookRotation` handles that case by trying another axis.

*Volumes and mirroring.* The **scalar triple product** $\mathbf{a} \cdot (\mathbf{b} \times
\mathbf{c})$ is the signed volume of the slanted box spanned by three vectors. It is the determinant
of §9, and its sign tells whether a transform mirrors space.

## 7. Matrices

### 7.1 A table of numbers that transforms vectors

A **matrix** is a rectangular table of numbers. A 3×3 matrix has 3 rows and 3 columns; "3×4" means 3
rows and 4 columns (rows first, always). The entry in row $r$ and column $c$ is written $`M_{rc}`$, with
rows and columns numbered from 0 as in Java:

```math
M = \begin{bmatrix} M_{00} & M_{01} & M_{02} \\ M_{10} & M_{11} & M_{12} \\ M_{20} & M_{21} & M_{22} \end{bmatrix}
```

What makes a table of numbers useful is one operation: **multiplying a matrix by a vector**. Write the
vector as a column of three numbers. Component $r$ of the result is row $r$ of the matrix dotted with
the vector:

```math
(M\mathbf{v})_r = \sum_{c=0}^{2} M_{rc}\, v_c = M_{r0} v_0 + M_{r1} v_1 + M_{r2} v_2
```

```java
float[] out = new float[3];
for (int r = 0; r < 3; r++) {          // one output component per row
    for (int c = 0; c < 3; c++) {      // the dot product of row r with v
        out[r] += m[r][c] * v[c];
    }
}
```

**Example.**

```math
\begin{bmatrix} 2 & 0 & 0 \\ 0 & 1 & 0 \\ 0 & 0 & 1 \end{bmatrix} \begin{bmatrix} 3 \\ 4 \\ 5 \end{bmatrix} = \begin{bmatrix} 2 \cdot 3 + 0 \cdot 4 + 0 \cdot 5 \\ 0 \cdot 3 + 1 \cdot 4 + 0 \cdot 5 \\ 0 \cdot 3 + 0 \cdot 4 + 1 \cdot 5 \end{bmatrix} = \begin{bmatrix} 6 \\ 4 \\ 5 \end{bmatrix}
```

This matrix doubles x and leaves y and z alone: it stretches space along the X axis. A matrix is a
machine that takes a vector in and gives a vector out, and the rest of this section shows that the
machines it can describe — turning, stretching, mirroring — are exactly the ones a game needs to
place objects.

### 7.2 Linear maps: the columns are the images of the axes

A function $f$ that turns vectors into vectors is **linear** if it respects addition and scaling:

```math
f(\mathbf{u} + \mathbf{v}) = f(\mathbf{u}) + f(\mathbf{v}) \qquad f(s\mathbf{u}) = s\,f(\mathbf{u})
```

Rotations, scales, mirrors and shears (slanting) are linear: rotate two arrows and add them, or add
them and rotate the sum, and you get the same arrow. Translation (moving everything by a fixed
offset) is not: it moves the zero vector, while a linear $f$ must have $f(\mathbf{0}) = f(0 \cdot
\mathbf{0}) = 0 \cdot f(\mathbf{0}) = \mathbf{0}$. §8 deals with that.

Now the key observation. Write any vector in terms of the three **basis vectors** $`\mathbf{e}_x = (1, 0, 0)`$, $`\mathbf{e}_y = (0, 1, 0)`$, $`\mathbf{e}_z = (0, 0, 1)`$: $`\mathbf{v} = x\,\mathbf{e}_x + y\,\mathbf{e}_y + z\,\mathbf{e}_z`$. By linearity,

```math
f(\mathbf{v}) = x\,f(\mathbf{e}_x) + y\,f(\mathbf{e}_y) + z\,f(\mathbf{e}_z)
```

So a linear map is completely described by **where it sends the three axes**. Write those three
images as the columns of a matrix:

```math
M = \begin{bmatrix} | & | & | \\ f(\mathbf{e}_x) & f(\mathbf{e}_y) & f(\mathbf{e}_z) \\ | & | & | \end{bmatrix}
```

Then the row rule of §7.1 computes exactly $f(\mathbf{v})$. Look at component $r$:
$`M_{r0}x + M_{r1}y + M_{r2}z`$ is "x times component $r$ of column 0, plus y times component $r$ of column 1, plus z times
component $r$ of column 2" — component $r$ of $`x f(\mathbf{e}_x) + y f(\mathbf{e}_y) + z f(\mathbf{e}_z)`$. And $`M\mathbf{e}_x`$ is column 0, $`M\mathbf{e}_y`$ column 1, $`M\mathbf{e}_z`$ column 2.

This is the most useful sentence in the chapter: **the columns of a matrix are the images of the
axes.** To read a matrix, look at its columns. To build one, decide where the axes should go.

### 7.3 Scale, rotation, mirror

**Scale** by $`(s_x, s_y, s_z)`$: the X axis goes to $`(s_x, 0, 0)`$, Y to $`(0, s_y, 0)`$, Z to $`(0, 0, s_z)`$. The columns are those images, so the matrix is **diagonal** (zeros off the main diagonal),
written $`\operatorname{diag}(s_x, s_y, s_z)`$. That is `Mat4f.scaling` (§21). The example of §7.1 was
$\operatorname{diag}(2, 1, 1)$.

**Rotation by 90° about +Y.** Turning a quarter turn about the up axis, counter-clockwise when seen
from above (+Y pointing at you; chapter [03](03-rotations-and-transforms.md) derives which way a
positive angle turns), sends +X to −Z, keeps +Y, and sends +Z to +X:

```
   seen from above (+X right, +Z down the page)

                -Z
                 ^  image of +X
                 |
                 +----> +X  image of +Z
                 |
                 v
                +Z
```

```math
R_y(90°) = \begin{bmatrix} 0 & 0 & 1 \\ 0 & 1 & 0 \\ -1 & 0 & 0 \end{bmatrix}
```

Column 0 is $(0, 0, -1)$, the image of +X; column 2 is $(1, 0, 0)$, the image of +Z. For a general
angle $\theta$, +X goes to $(\cos\theta, 0, -\sin\theta)$ and +Z to $(\sin\theta, 0, \cos\theta)$ by
§4.2, and those are the columns. A rotation keeps every length and every angle, so its columns are
unit vectors at right angles to each other: the matrix is **orthonormal** (§10.3 uses this).

**Mirror.** $\operatorname{diag}(-1, 1, 1)$ sends +X to −X: it reflects the world in the YZ plane, like
a mirror. A mirror is linear but not a rotation; §9 shows how to detect one, and why the renderer has
to.

### 7.4 Two ways to read matrix × vector

The same product can be computed two ways:

- **By rows:** component $r$ of $M\mathbf{v}$ is the dot product of row $r$ with $\mathbf{v}$ (§7.1).
  Good for code: three dot products.
- **By columns:** $M\mathbf{v}$ is a weighted sum of the columns, $`x \cdot \text{column}_0 + y \cdot \text{column}_1 + z \cdot \text{column}_2`$ (§7.2). Good for intuition.

Both give the same numbers. With $`R_y(90°)`$ and $\mathbf{v} = (1, 0, 0)$: by rows, $(0, 0, -1)$; by
columns, $1 \cdot (0, 0, -1) + 0 + 0$. The shader that places every mesh instance uses the row form,
because its transform is stored as three rows (§12.2). From
[`instance.glsl`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/engine/instance.glsl):

```glsl
vec3 jeddyTransformPoint(JeddyInstance i, vec3 p) {
    vec4 v = vec4(p, 1.0);
    return vec3(dot(i.row0, v), dot(i.row1, v), dot(i.row2, v));
}
```

`Mat4f.transformDirection` writes the column form out in full:

```java
    public Vec3f transformDirection(Vec3f d) {
        return new Vec3f(
                m00 * d.x() + m10 * d.y() + m20 * d.z(),
                m01 * d.x() + m11 * d.y() + m21 * d.z(),
                m02 * d.x() + m12 * d.y() + m22 * d.z());
    }
```

Read the first line as "x times column 0's top entry, plus y times column 1's top entry, plus z times
column 2's top entry". (`Mat4f` names its components `mCR`, **column first**: `m10` is column 1,
row 0. §12.5 is about this trap.)

### 7.5 A matrix is also a change of basis

There is a second way to read the same matrix. Suppose an object has its own axes — its **local
space**: "forward" for a character, "along the blade" for a sword. Let those axes be
$`\mathbf{u}_0, \mathbf{u}_1, \mathbf{u}_2`$, given in world coordinates. A point with local coordinates
$(a, b, c)$ — "$a$ along my first axis, $b$ along my second, $c$ along my third" — is at
$`a\mathbf{u}_0 + b\mathbf{u}_1 + c\mathbf{u}_2`$ in the world. By §7.4 that is $M(a, b, c)$ for the
matrix whose columns are $`\mathbf{u}_0, \mathbf{u}_1, \mathbf{u}_2`$. So:

- a matrix whose **columns** are a frame's axes converts **local → world** coordinates;
- if the axes are orthonormal (unit length, mutually perpendicular), the matrix whose **rows** are
  those axes converts **world → local**: row $i$ dotted with a world vector is its scalar projection
  on axis $i$ (§5.3), which is exactly its local coordinate $i$.

**Example.** A frame turned 90° about Y has axes $`\mathbf{u}_0 = (0, 0, -1)`$, $`\mathbf{u}_1 = (0, 1, 0)`$, $`\mathbf{u}_2 = (1, 0, 0)`$ (the columns of $`R_y(90°)`$). The local point $(2, 0, 0)$ is at $`2 \mathbf{u}_0 = (0, 0, -2)`$ in the world. Back: $`\mathbf{u}_0 \cdot (0, 0, -2) = 2`$, $`\mathbf{u}_1 \cdot (0, 0, -2) = 0`$, $`\mathbf{u}_2 \cdot (0, 0, -2) = 0`$, so local $(2, 0, 0)$ again.

The camera's **view matrix** is the second case: it converts world coordinates into the camera's own
(right, up, backward). `Mat4Ops.lookAt` writes the camera's right, up and backward axes into the
**rows** of the matrix. For an eye at $(0, 10, 10)$ looking at the origin, the rows are $(1, 0, 0)$,
$(0, 0.7071, -0.7071)$ and $(0, 0.7071, 0.7071)$: right, up, and the direction from the target back to
the eye. `pbr.frag` reads that third row directly when it needs the direction towards an orthographic
camera (which is the same for every pixel):

```glsl
    s.view = jeddyOrthographic() ? normalize(vec3(jView[0][2], jView[1][2], jView[2][2]))
                                 : normalize(jEye.xyz - vWorld);
```

GLSL indexes matrices `m[column][row]`, so `jView[0][2], jView[1][2], jView[2][2]` are the first three
entries of row 2.

The vertex tangent frame is the first case: a matrix whose columns are a vertex's tangent, bitangent
and normal converts vectors from the surface's own "tangent space" (normal-map samples) into object
space. Chapter [03](03-rotations-and-transforms.md) shows how Jeddy stores that matrix as one
quaternion.

### 7.6 Matrix × matrix: composition

Applying $B$ first and then $A$ to a vector is $A(B\mathbf{v})$. Is there a single matrix that does
both? Its columns must be the images of the axes (§7.2): column $c$ is $`A(B\mathbf{e}_c)`$, that is,
$A$ applied to column $c$ of $B$. Write that out with the row rule: entry $(r, c)$ is row $r$ of $A$
dotted with column $c$ of $B$:

```math
(AB)_{rc} = \sum_{k} A_{rk}\, B_{kc}
```

The sum runs over $k$, the "inner" index: across row $r$ of $A$ and down column $c$ of $B$ at the same
time. In Java it is a triple loop, which is exactly the `double`-precision reference the tests use
(`mulRef` in `MatrixAndQuatTest`, §30):

```java
for (int r = 0; r < n; r++)
    for (int c = 0; c < n; c++)
        for (int k = 0; k < n; k++)
            ab[r][c] += a[r][k] * b[k][c];
```

$AB$ is the **product** of $A$ and $B$, and $A(B\mathbf{v}) = (AB)\mathbf{v}$ for every vector. The
product is **associative**: $(AB)C = A(BC)$, so a chain of transforms can be multiplied in any
grouping. The rule to remember:

> In $AB\mathbf{v}$, the matrix next to the vector acts first. Read right to left.

The engine's methods follow it: "`a.mul(b)` applies `b` first" (the `Mat4f` Javadoc; `AffineOps.mul`
and `Quatf.mul` say the same). `IsoCamera.update` builds the camera's combined matrix with

```java
        Mat4Ops.mul(projection, 0, view, 0, viewProjection, 0);
```

which is $P \cdot V$: a world position goes through the view matrix first, then the projection.

**Example.** $A = \operatorname{diag}(2, 1, 1)$ and $`B = R_y(90°)`$:

```math
AB = \begin{bmatrix} 0 & 0 & 2 \\ 0 & 1 & 0 \\ -1 & 0 & 0 \end{bmatrix} \qquad BA = \begin{bmatrix} 0 & 0 & 1 \\ 0 & 1 & 0 \\ -2 & 0 & 0 \end{bmatrix}
```

Check one entry of each with the sum: $`(AB)_{02} = A_{00}B_{02} + A_{01}B_{12} + A_{02}B_{22} = 2 \cdot 1 + 0 + 0 = 2`$; $`(BA)_{20} = B_{20}A_{00} + B_{21}A_{10} + B_{22}A_{20} = (-1) \cdot 2 + 0 + 0 = -2`$.
Notice the pattern, which §8.6 relies on: a diagonal matrix on the **left** scales the **rows** of the
other matrix, and on the **right** it scales the **columns**.

### 7.7 Order matters

The example shows that $AB \ne BA$: matrix multiplication is **not commutative**. Apply both to
$(0, 0, 1)$: $AB$ first rotates it to $(1, 0, 0)$ and then doubles x, giving $(2, 0, 0)$; $BA$ first
doubles x (there is none) and then rotates, giving $(1, 0, 0)$. Stretching then turning is not turning
then stretching. With translation (§8.4) the difference becomes dramatic.

### 7.8 Transpose and identity

The **transpose** $M^T$ swaps rows and columns: $`(M^T)_{rc} = M_{cr}`$. The transpose of $`R_y(90°)`$ is
the matrix whose rows are the axes, the "world → local" matrix of §7.5. Two facts the chapter uses:
$(AB)^T = B^T A^T$ (the order reverses; check it on one entry with the sum), and $\mathbf{a} \cdot
\mathbf{b}$ can be written $\mathbf{a}^T\mathbf{b}$, a 1×3 row times a 3×1 column.

The **identity** matrix $I = \operatorname{diag}(1, 1, 1)$ sends every axis to itself and so changes
nothing: $I\mathbf{v} = \mathbf{v}$ and $IM = MI = M$. It is `Mat4f.IDENTITY`, `Mat3f.IDENTITY`,
`Mat4Ops.identity` and `AffineOps.identity`.

### 7.9 Column vectors, row vectors, and storage order

You will read code and books that write $\mathbf{v}' = \mathbf{v}M$ instead, with $\mathbf{v}$ as a
row. That is the **row-vector convention** (classic Direct3D and HLSL samples). Its matrices are the
transposes of ours, and products read left to right. Both conventions describe the same geometry.
Jeddy uses **column vectors** everywhere: `Mat4f`, `Mat3f`, `AffineOps`, and GLSL
(`jViewProjection * vec4(vWorld, 1.0)` in `pbr.vert`).

Do not confuse that with **storage order**, which is a separate question: in which order the 16
(or 12) numbers of a matrix sit in memory, a one-dimensional list. Jeddy stores 4×4 matrices column by
column and 3×4 transforms row by row, but both are used with column vectors. "Row-major 3×4" says
nothing about row vectors. §12 is about storage.

## 8. Homogeneous coordinates and affine transforms

### 8.1 Translation does not fit in a 3×3 matrix

A 3×3 matrix always sends the zero vector to zero (§7.2), so it cannot express "move by $(5, 0, 0)$".
Placing an object needs a linear part (turn and scale) plus a translation $\mathbf{t}$:

$$\mathbf{p}' = A\mathbf{p} + \mathbf{t}$$

A map of this shape is called **affine**. Every object transform in the engine is affine.

### 8.2 A fourth coordinate

The trick that makes an affine map a single matrix product is to give every point a fourth
coordinate, $w$, equal to 1, and to use a 4×4 matrix whose last column holds $\mathbf{t}$ and whose
last row is $(0, 0, 0, 1)$:

```math
\begin{bmatrix} \mathbf{p}' \\ 1 \end{bmatrix} = \begin{bmatrix} A & \mathbf{t} \\ 0\;0\;0 & 1 \end{bmatrix} \begin{bmatrix} \mathbf{p} \\ 1 \end{bmatrix} = \begin{bmatrix} A\mathbf{p} + 1 \cdot \mathbf{t} \\ 0 + 1 \end{bmatrix}
```

Row by row (§7.1): the first three rows are a row of $A$ dotted with $\mathbf{p}$, plus the
translation entry times $w = 1$. The last row is $(0, 0, 0, 1) \cdot (\mathbf{p}, 1) = 1$, so $w$ stays
1 and the result is again a point of the same kind. The four numbers $(x, y, z, w)$ are called
**homogeneous coordinates**. This is `Mat4f.translation`:

```java
    /** Returns a translation matrix. */
    public static Mat4f translation(float x, float y, float z) {
        return new Mat4f(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, x, y, z, 1);
    }
```

The constructor takes the 16 values column by column (§12.1), so x, y and z land in column 3, the
last one.

### 8.3 Points have w = 1, directions have w = 0

What should $w$ be for a direction? A direction is a difference of two points (§2.3):
$(\mathbf{p}, 1) - (\mathbf{q}, 1) = (\mathbf{p} - \mathbf{q}, 0)$. With $w = 0$ the translation column is multiplied
by zero, so directions turn and stretch but do not move. That matches the geometry: "1 m east" is
still "1 m east" after you move the whole world.

The engine encodes this as two methods instead of asking for a $w$. `Mat4f.transformPoint`:

```java
    /** Transforms a point (w = 1) without the perspective divide. */
    public Vec3f transformPoint(Vec3f p) {
        return new Vec3f(
                m00 * p.x() + m10 * p.y() + m20 * p.z() + m30,
                m01 * p.x() + m11 * p.y() + m21 * p.z() + m31,
                m02 * p.x() + m12 * p.y() + m22 * p.z() + m32);
    }
```

The only difference from `transformDirection` (§7.4) is the `+ m30`, `+ m31`, `+ m32` translation
terms. `AffineOps.transformPoint`/`transformDirection` and the shader pair
`jeddyTransformPoint`/`jeddyTransformDirection` follow the same pattern (§24, §27).

### 8.4 Translate and rotate: order again

Take $T$ = translate by $(5, 0, 0)$ and $`R = R_y(90°)`$, and apply them to the point $(1, 0, 0)$:

- $TR$: rotate first, $(1, 0, 0) \to (0, 0, -1)$, then translate $\to (5, 0, -1)$. The object turns in
  place, then moves.
- $RT$: translate first, $(1, 0, 0) \to (6, 0, 0)$, then rotate about the origin $\to (0, 0, -6)$. The
  object swings round the origin like a hammer thrower.

```
   seen from above (+X right, -Z up the page)

   -Z
    ^
  6 x (0,0,-6)    RT: moved out to (6,0,0) first, then swung round the origin
    |
    |
    |
    |
  1 |                  x (5,0,-1)    TR: turned in place to (0,0,-1), then moved
    +----o-------------+-----> +X
    0    1             5
         o = (1,0,0), the point before either transform
```

Running `Mat4f.translation(5, 0, 0).mul(rotation)` and `rotation.mul(translation)` on `UNIT_X` gives
`(5.0, 0.0, -0.99999994)` and `(3.5762787E-7, 0.0, -5.9999995)`: the two answers, up to `float`
rounding (§29).

### 8.5 The product of two affine transforms

Write each affine transform as a 4×4 with the bottom row $(0, 0, 0, 1)$ and multiply in **blocks**
(the sum of §7.6 works on blocks just as on numbers, as long as the shapes fit):

```math
\begin{bmatrix} A & \mathbf{a} \\ \mathbf{0}^T & 1 \end{bmatrix} \begin{bmatrix} B & \mathbf{b} \\ \mathbf{0}^T & 1 \end{bmatrix} = \begin{bmatrix} AB + \mathbf{a}\mathbf{0}^T & A\mathbf{b} + \mathbf{a} \cdot 1 \\ \mathbf{0}^T B + 1 \cdot \mathbf{0}^T & \mathbf{0}^T\mathbf{b} + 1 \end{bmatrix} = \begin{bmatrix} AB & A\mathbf{b} + \mathbf{a} \\ \mathbf{0}^T & 1 \end{bmatrix}
```

The linear parts multiply, and the translation of the result is "$B$'s translation transformed by $A$,
plus $A$'s translation" — where $B$'s origin ends up after $A$. The bottom row stays $(0, 0, 0, 1)$, so
the product of two affine transforms is affine. That is why the engine can store only the top three
rows (12 floats) and never lose anything: `AffineOps.mul` (§24) is this formula.

**Counting the work.** A full 4×4 product computes 16 entries, each a sum of 4 products: 64
multiplications and 48 additions. The block formula needs $AB$ (9 entries of 3 products) and
$A\mathbf{b} + \mathbf{a}$ (3 entries of 3 products plus one addition each): 36 multiplications and 27 additions.

### 8.6 TRS: scale, then rotate, then translate

Almost every object transform in a game is built from a translation $\mathbf{t}$, a rotation $R$ and
a scale $`S = \operatorname{diag}(s_x, s_y, s_z)`$, combined as

```math
M = T\,R\,S \qquad\text{so}\qquad M\mathbf{p} = R(S\mathbf{p}) + \mathbf{t}
```

Read right to left: scale the model in its own space, turn it, then move it into place. Other orders
are possible but surprising: scaling after rotating would stretch along the *world* axes and slant
(shear) a rotated object.

The linear part is $RS$. By §7.6, multiplying by a diagonal matrix on the right scales **columns**:
column $j$ of $RS$ is $`s_j`$ times column $j$ of $R$. (The images of the axes: X is first stretched to
$`s_x\mathbf{e}_x`$, then turned to $`s_x R\mathbf{e}_x`$.) So in a TRS matrix, every column of the linear
part has the length of its axis's scale. §24 shows this pattern in `AffineOps.composeTRS`: `* sx` on
the three entries of column 0, `* sy` on column 1, `* sz` on column 2.

**Worked example** (the test `trsComposition`, §30): $\mathbf{t} = (10, 0, 0)$, $`R = R_y(90°)`$, scale 2.
The point $(1, 0, 0)$ is scaled to $(2, 0, 0)$, turned to $(0, 0, -2)$ and moved to $(10, 0, -2)$.

### 8.7 Parent × local

A sword in a hand is placed relative to the hand, the hand relative to the arm, the arm relative to
the body. Each object stores a **local** transform relative to its parent, and its **world** transform
is

$$W_{\text{child}} = W_{\text{parent}} \cdot L_{\text{child}}$$

Apply the child's local transform first (place it on the parent), then wherever the parent is.
**Example:** the parent is at $(10, 0, 0)$ turned 90° about Y, $`W_p = T(10, 0, 0)\,R_y(90°)`$; the
child sits 2 m along the parent's X axis, $L = T(2, 0, 0)$. The child's origin is $`W_p (2, 0, 0) = R_y(90°)(2, 0, 0) + (10, 0, 0) = (0, 0, -2) + (10, 0, 0) = (10, 0, -2)`$: 2 m along the parent's
turned X axis, which now points along −Z. `AffineOps.concatenate` (§24) runs this over a whole array of
objects, parents before children. Chapter [03](03-rotations-and-transforms.md) builds the real
hierarchy, `TransformStore`, with dirty tracking and parallel levels.

### 8.8 Transforming boxes and spheres

Culling (deciding what the camera can see) works on simple **bounding volumes** around each mesh: an
**axis-aligned bounding box** (AABB: a box whose edges run along X, Y and Z, stored as a min and a max
corner) or a **bounding sphere**. Meshes store them in their own local space, so they have to follow
the object's transform.

*Boxes.* Transforming the 8 corners and taking the min and max works: 8 point transforms of 9
multiplications each, 72 in all. **Arvo's method** (Graphics Gems, 1990) does it with 18. Output
coordinate $r$ of a transformed point is

```math
p'_r = t_r + m_{r0}\,x + m_{r1}\,y + m_{r2}\,z
```

and each term depends on **one** input coordinate only. Over the box, $x$ ranges over $`[\min_x, \max_x]`$, so the smallest possible value of $`m_{r0}x`$ is $`\min(m_{r0}\min_x,\; m_{r0}\max_x)`$ —
whichever end is smaller after multiplying, because a negative $`m_{r0}`$ swaps them — whatever $y$ and
$z$ are. The smallest $`p'_r`$ is $`t_r`$ plus the three smallest terms; the largest is $`t_r`$ plus the
three largest. Two products per term, three terms, three rows: 18 multiplications. The result is the
tightest axis-aligned box around the transformed box (not around the object inside it, which may be
smaller).

**Example.** The box $[-1, 1]^3$ turned 45° about Y: row 0 is $(\cos 45°, 0, \sin 45°) = (0.7071, 0,
0.7071)$, so $x'$ ranges over $`[-0.7071 - 0.7071,\; 0.7071 + 0.7071] = [-1.4142, 1.4142]`$: the
corner $(1, \cdot, 1)$ swings out to $x' = 1.4142$. `AffineOps.transformAabb` (§24) is this loop.

*Spheres.* The centre transforms as a point. The radius has to grow by the largest stretch the
transform applies in any direction. For a TRS matrix that is the largest axis scale, which by §8.6 is
the length of the longest column of the linear part. `World.maxScale` computes it (§28).

### 8.9 The bottom row and projections

For affine transforms the bottom row is always $(0, 0, 0, 1)$ and $w$ stays 1. Projection matrices
change the bottom row, so $w$ comes out as something else — for a perspective camera, the distance in
front of the eye — and the result is divided by it afterwards (`Vec4f.perspectiveDivide`,
`Mat4Ops.project`). Dividing by distance is what makes far things small. That is the subject of
chapter [04](04-cameras-and-projection.md); in this chapter, every matrix is affine.

## 9. Determinants and mirroring

### 9.1 Area in 2D

A 2×2 matrix with columns $\mathbf{u} = (a, c)$ and $\mathbf{v} = (b, d)$ sends the unit square (sides
$`\mathbf{e}_x`$, $`\mathbf{e}_y`$, area 1) to the parallelogram with sides $\mathbf{u}$ and $\mathbf{v}$.
How much bigger is it? Its area is $\lVert \mathbf{u} \rVert \lVert \mathbf{v} \rVert \sin\theta$
(§6.2). Square it and replace $\sin^2\theta$ by $1 - \cos^2\theta$, as §6.2 did:

$$\text{area}^2 = \lVert \mathbf{u} \rVert^2 \lVert \mathbf{v} \rVert^2 - (\mathbf{u} \cdot \mathbf{v})^2 = (a^2 + c^2)(b^2 + d^2) - (ab + cd)^2 = a^2 d^2 + b^2 c^2 - 2abcd = (ad - bc)^2$$

So the area is $|ad - bc|$. The number $ad - bc$, with its sign, is the **determinant** of the matrix:

```math
\det \begin{bmatrix} a & b \\ c & d \end{bmatrix} = ad - bc
```

It is the 2D cross product of the columns (§6.4): positive when $\mathbf{v}$ is counter-clockwise from
$\mathbf{u}$, as $`\mathbf{e}_y`$ is from $`\mathbf{e}_x`$, and negative when the map has flipped that
order — a **mirror**. **Example:** columns $(3, 1)$ and $(1, 2)$: $\det = 3 \cdot 2 - 1 \cdot 1 = 5$, and
indeed $(10)(5) - 5^2 = 25 = 5^2$. The mirror $\operatorname{diag}(-1, 1)$ has $\det = -1$: area kept,
orientation flipped.

### 9.2 Volume in 3D: the triple product

A 3×3 matrix sends the unit cube to the slanted box (a **parallelepiped**) spanned by its three
columns $`\mathbf{c}_0, \mathbf{c}_1, \mathbf{c}_2`$. Its volume is base area times height. Take the
base to be the parallelogram of $`\mathbf{c}_1`$ and $`\mathbf{c}_2`$: its area is $`\lVert \mathbf{c}_1 \times \mathbf{c}_2 \rVert`$ (§6.2), and $`\mathbf{c}_1 \times \mathbf{c}_2`$ is perpendicular to the
base. The height is how far $`\mathbf{c}_0`$ reaches along that perpendicular: the scalar projection of
$`\mathbf{c}_0`$ onto the unit vector $`\frac{\mathbf{c}_1 \times \mathbf{c}_2}{\lVert \mathbf{c}_1 \times \mathbf{c}_2 \rVert}`$. Multiply:

$$\text{volume} = \lVert \mathbf{c}_1 \times \mathbf{c}_2 \rVert \cdot \frac{\mathbf{c}_0 \cdot (\mathbf{c}_1 \times \mathbf{c}_2)}{\lVert \mathbf{c}_1 \times \mathbf{c}_2 \rVert} = \mathbf{c}_0 \cdot (\mathbf{c}_1 \times \mathbf{c}_2)$$

with a sign: positive when $`\mathbf{c}_0`$ is on the side $`\mathbf{c}_1 \times \mathbf{c}_2`$ points to.
This is the **scalar triple product**, and it is the **determinant** of the 3×3 matrix:

$$\det A = \mathbf{c}_0 \cdot (\mathbf{c}_1 \times \mathbf{c}_2)$$

Writing it out with the rows named $`\begin{bmatrix} a & b & c \\ d & e & f \\ g & h & k \end{bmatrix}`$
(so the columns are $(a, d, g)$, $(b, e, h)$, $(c, f, k)$):

$$\det A = a(ek - fh) - b(dk - fg) + c(dh - eg)$$

Each of $a$, $b$, $c$ (the first row) multiplies the 2×2 determinant of the four entries left when
you cross out its row and its column, with signs $+, -, +$. That is the **cofactor expansion along the
first row**, and §10.4 uses its pieces. **Example:** $\det \operatorname{diag}(1, 2, 3) = 1 \cdot (2
\cdot 3 - 0) - 0 + 0 = 6$; the test `inverseTimesMatrixIsIdentity` checks
`Mat4f.scaling(1, 2, 3).determinant()` is 6.

### 9.3 Properties

The determinant is the factor by which a transform multiplies volumes, with a sign for orientation.
From that picture:

- $`\det(\operatorname{diag}(s_x, s_y, s_z)) = s_x s_y s_z`$: the unit cube becomes an $`s_x \times s_y \times s_z`$ box.
- A rotation keeps volume and orientation: $\det R = 1$. The test `mat3NormalMatrixAndMutableTypes`
  checks `Mat3f.rotation(randomQuat()).determinant()` is 1 within $10^{-4}$.
- $\det(AB) = \det A \cdot \det B$: $B$ multiplies volumes by $\det B$, then $A$ by $\det A$. So a TRS
  matrix has $`\det = 1 \cdot s_x s_y s_z`$.
- $\det = 0$ means the transform flattens space onto a plane, a line or a point: the box has no
  volume. It cannot be undone (§10.6): `Mat4f.scaling(1, 0, 1).invert()` throws.
- $\det < 0$ means the transform **mirrors**. A scale of $(-1, 1, 1)$ has determinant −1, and so does
  any rotation followed by an odd number of mirrors.

### 9.4 Determinants and mirroring

Mirroring matters to the renderer, and to understand why you need to know how the GPU tells the front
of a triangle from its back.

**Winding.** Meshes are made of triangles whose three corners are stored in a fixed order. The
convention (glTF's and OpenGL's default) is that, seen from the outside of the object, the corners run
**counter-clockwise**. Seen from inside, the same corners run clockwise.

**What the GPU does with it.** After the vertex shader has placed the three corners (chapter
[04](04-cameras-and-projection.md) follows them through projection), the fixed-function stage that
sets up each triangle for the **rasterizer** (L00 §10.2) has their positions in **window coordinates**
— pixels, with $x$ to the right and $y$ up (Jeddy calls `glClipControl(GL_LOWER_LEFT,
GL_ZERO_TO_ONE)` in `GpuDevice.useReversedZ`). It computes the triangle's signed area
exactly as the 2D cross product of §6.4 does; the OpenGL specification's section on basic polygon
rasterization defines it as

```math
a = \frac12 \sum_{i=0}^{n-1} \left(x_i\, y_{i \oplus 1} - x_{i \oplus 1}\, y_i\right)
```

where $i \oplus 1$ means "the next corner, wrapping round". With the default `glFrontFace(GL_CCW)`, a
positive area (counter-clockwise on screen) makes the triangle **front-facing**. If **back-face
culling** is on — and the engine's opaque pipeline state (`PipelineState.OPAQUE`) turns it on — the
back-facing triangles are thrown away right there, before a single fragment of them is shaded. For a
closed mesh that halves the work: the back faces are hidden behind the front ones anyway.

**What a mirror does.** A transform with $\det < 0$ turns a counter-clockwise triangle into a
clockwise one (the 2D determinant of §9.1 flips sign, and so does the 3D orientation). The mesh's
outside faces now arrive with clockwise winding, so the GPU decides they are back faces and culls them,
while it keeps the inside faces. A mirrored statue drawn with back-face culling shows its inside. The
glTF 2.0 specification, which the engine's assets follow, states the rule directly: "the determinant
of the node's global transform defines the winding order of that primitive" — counter-clockwise when
it is positive, clockwise otherwise.

Mirroring also flips the handedness of tangent frames, which the normal-mapping code depends on.
`instance.glsl` detects it with the triple product:

```glsl
// True if the transform mirrors (negative determinant): triangles flip their winding.
bool jeddyMirrored(JeddyInstance i) {
    mat3 m = jeddyLinear(i);
    return dot(m[0], cross(m[1], m[2])) < 0.0;
}
```

`pbr.vert` uses it to flip the bitangent sign of mirrored instances (chapter
[03](03-rotations-and-transforms.md) explains why).

The shaders fix the tangent frame, but nothing flips the winding test yet. DESIGN §9.2 records this as
a known limitation. The fix is a state change: draw mirrored instances with `glFrontFace(GL_CW)`, which
tells the rasterizer that clockwise is front. The engine draws a whole batch of instances with one
**indirect draw call** (a draw whose parameters — which mesh, how many instances, from which record —
the GPU reads from a buffer instead of from the call's arguments; chapter
[06](06-renderer-architecture.md)), and a draw call has one pipeline state. So mirrored instances would
have to go in their own batches: the renderer groups draws by sorting a 64-bit **sort key** per item,
and one more bit in that key would keep mirrored and normal instances apart. It makes a good small project: see
[the warm-up tasks](../next-milestones.md#warm-up-tasks).

## 10. Inverses

### 10.1 What an inverse is

The **inverse** $M^{-1}$ of a transform undoes it: $M^{-1}(M\mathbf{v}) = \mathbf{v}$ for every
$\mathbf{v}$, that is $M^{-1}M = I$ (and then also $MM^{-1} = I$). Engines need inverses constantly:

- the view matrix is the inverse of the camera's own transform (§10.3);
- picking a point on screen "un-projects" the mouse with the inverse of the view-projection matrix
  (`IsoCamera.inverseViewProjection`, chapter 04);
- testing a ray against a mesh is cheaper in the mesh's own space, which needs the inverse of its
  world transform (§28);
- skinning uses **inverse bind matrices**, which take a vertex from the model into a joint's space
  (chapter [09](09-animation-and-skinning.md)).

Two facts about inverses follow from the definition: $(AB)^{-1} = B^{-1}A^{-1}$ (to undo "B then A",
undo A first), and $(M^T)^{-1} = (M^{-1})^T$, written $M^{-T}$ (§11 needs it).

### 10.2 The affine inverse

Solve $\mathbf{p}' = A\mathbf{p} + \mathbf{t}$ for $\mathbf{p}$. Subtract $\mathbf{t}$, then undo $A$:

$$\mathbf{p} = A^{-1}(\mathbf{p}' - \mathbf{t}) = A^{-1}\mathbf{p}' - A^{-1}\mathbf{t}$$

So the inverse is again affine, with linear part $A^{-1}$ and translation $-A^{-1}\mathbf{t}$.
Everything reduces to inverting the 3×3 part. There are two cases: rotations, which are easy, and
everything else.

### 10.3 Rotations: the inverse is the transpose

A rotation matrix $R$ has orthonormal columns $`\mathbf{c}_0, \mathbf{c}_1, \mathbf{c}_2`$ (§7.3). Entry
$(i, j)$ of $R^T R$ is row $i$ of $R^T$ dotted with column $j$ of $R$, that is $`\mathbf{c}_i \cdot \mathbf{c}_j`$: 1 when $i = j$ (unit length) and 0 otherwise (perpendicular). So $R^T R = I$ and

$$R^{-1} = R^T$$

No division, no determinant. This is §7.5 again: the transpose has the axes as its rows, and rows of
axes convert world to local. With a uniform scale $s$, $(sR)^{-1} = R^T / s$.

The **view matrix** is the standard example. A camera at position $\mathbf{e}$ ("eye") with axes right
$\mathbf{r}$, up $\mathbf{u}$ and backward $-\mathbf{f}$ ($\mathbf{f}$ is forward; the camera looks down
its −Z, §2.5) has the world transform $`[\,\mathbf{r}\;\mathbf{u}\;{-\mathbf{f}} \mid \mathbf{e}\,]`$
(three axis columns, then the translation). By §10.2, its inverse has the transposed rotation (the axes
become rows) and translation $-R^T\mathbf{e}$, whose components are the rows dotted with $\mathbf{e}$:
$`(-\mathbf{r}\cdot\mathbf{e},\; -\mathbf{u}\cdot\mathbf{e},\; \mathbf{f}\cdot\mathbf{e})`$. Compare with
the end of `Mat4Ops.lookAt`:

```java
        out[o] = rx;
        out[o + 1] = vx;
        out[o + 2] = -fx;
        out[o + 3] = 0;
        // ... columns 1 and 2 the same way
        out[o + 12] = -(rx * ex + ry * ey + rz * ez);
        out[o + 13] = -(vx * ex + vy * ey + vz * ez);
        out[o + 14] = fx * ex + fy * ey + fz * ez;
        out[o + 15] = 1;
```

(`vx, vy, vz` is the up vector.) In column-major storage (§12.1), `out[0], out[4], out[8]` is row 0, so
the rows are right, up and −forward, and the translation column holds the three dot products.
**Example:** eye $(0, 10, 10)$, target the origin: $\mathbf{f} = (0, -0.7071, -0.7071)$, and the
translation is $(0, 0, \mathbf{f}\cdot\mathbf{e}) = (0, 0, -14.142)$ — the target, 14.14 m in front of
the eye, sits on the camera's −Z axis. The test `lookAtPutsEyeAtOriginLookingDownMinusZ` checks that
the eye maps to the origin and the target to $(0, 0, -\text{distance})$.

### 10.4 The general 3×3 inverse: cross products, cofactors, the adjugate

With non-uniform scale the columns are no longer orthonormal and the transpose is not the inverse. We
need a general method. For a 3×3 matrix $A$ with columns $`\mathbf{c}_0, \mathbf{c}_1, \mathbf{c}_2`$,
there is a neat one built from cross products. Consider the matrix $N$ whose **rows** are cross
products of the columns:

```math
N = \begin{bmatrix} (\mathbf{c}_1 \times \mathbf{c}_2)^T \\ (\mathbf{c}_2 \times \mathbf{c}_0)^T \\ (\mathbf{c}_0 \times \mathbf{c}_1)^T \end{bmatrix}
```

Entry $(i, j)$ of $NA$ is row $i$ of $N$ dotted with column $j$ of $A$. Row 0, $`\mathbf{c}_1 \times \mathbf{c}_2`$, is perpendicular to $`\mathbf{c}_1`$ and $`\mathbf{c}_2`$ (§6.1), so it dots to 0 with
columns 1 and 2; with $`\mathbf{c}_0`$ it gives the triple product $`\mathbf{c}_0 \cdot (\mathbf{c}_1 \times \mathbf{c}_2) = \det A`$ (§9.2). Rows 1 and 2 work the same way (the triple product does not
change when its three vectors rotate places: $`\mathbf{c}_1 \cdot (\mathbf{c}_2 \times \mathbf{c}_0) = \mathbf{c}_0 \cdot (\mathbf{c}_1 \times \mathbf{c}_2)`$, the same box). So $`NA = (\det A)\,I`$, and when
$\det A \ne 0$:

```math
A^{-1} = \frac{1}{\det A}\,N
```

$N$ is called the **adjugate** of $A$. Its transpose — the matrix whose **columns** are those cross
products — is the **cofactor matrix**: entry $(r, c)$ is the 2×2 determinant left after crossing out
row $r$ and column $c$ of $A$, with the sign $(-1)^{r + c}$ (a checkerboard $+ - +$ / $- + -$ / $+ - +$).
Those signed 2×2 determinants are the **cofactors**, the same pieces as the expansion in §9.2.

[`AffineOps.invert`](../../modules/math/src/main/java/dev/jeddy/math/AffineOps.java) is this formula
written out. With the linear part named row by row $`\begin{bmatrix} a & b & c \\ d & e & f \\ g & h & k \end{bmatrix}`$, the columns are $(a, d, g)$, $(b, e, h)$ and $(c, f, k)$, and row 0 of the adjugate is

```math
\mathbf{c}_1 \times \mathbf{c}_2 = (b, e, h) \times (c, f, k) = (ek - hf,\;\; hc - bk,\;\; bf - ec)
```

which is `co0`, `c * h - b * k` and `b * f - c * e` in the code (§24). The determinant is computed as
$a \cdot \text{co0} + b \cdot \text{co1} + c \cdot \text{co2}$, the cofactor expansion of §9.2 with
`co1` $= fg - dk$ and `co2` $= dh - eg$: the cofactors of the first row, which are also the first
entries of the adjugate's three rows, so the code reuses them for column 0 of the inverse.

**Worked example with every entry non-trivial.** Take

```math
A = \begin{bmatrix} 2 & 0 & 1 \\ 1 & 3 & 0 \\ 0 & 1 & 4 \end{bmatrix}, \quad \mathbf{c}_0 = (2, 1, 0),\; \mathbf{c}_1 = (0, 3, 1),\; \mathbf{c}_2 = (1, 0, 4)
```

- $`\mathbf{c}_1 \times \mathbf{c}_2 = (3 \cdot 4 - 1 \cdot 0,\; 1 \cdot 1 - 0 \cdot 4,\; 0 \cdot 0 - 3 \cdot 1) = (12, 1, -3)`$;
- $`\mathbf{c}_2 \times \mathbf{c}_0 = (0 \cdot 0 - 4 \cdot 1,\; 4 \cdot 2 - 1 \cdot 0,\; 1 \cdot 1 - 0 \cdot 2) = (-4, 8, 1)`$;
- $`\mathbf{c}_0 \times \mathbf{c}_1 = (1 \cdot 1 - 0 \cdot 3,\; 0 \cdot 0 - 2 \cdot 1,\; 2 \cdot 3 - 1 \cdot 0) = (1, -2, 6)`$;
- $`\det A = \mathbf{c}_0 \cdot (12, 1, -3) = 24 + 1 + 0 = 25`$. With the code's names: `co0` $= 3 \cdot 4 -
  0 \cdot 1 = 12$, `co1` $= 0 \cdot 0 - 1 \cdot 4 = -4$, `co2` $= 1 \cdot 1 - 3 \cdot 0 = 1$, and
  $2 \cdot 12 + 0 \cdot (-4) + 1 \cdot 1 = 25$.

```math
A^{-1} = \frac{1}{25}\begin{bmatrix} 12 & 1 & -3 \\ -4 & 8 & 1 \\ 1 & -2 & 6 \end{bmatrix} = \begin{bmatrix} 0.48 & 0.04 & -0.12 \\ -0.16 & 0.32 & 0.04 \\ 0.04 & -0.08 & 0.24 \end{bmatrix}
```

Check one entry of $A^{-1}A$: row 0 of $A^{-1}$ times column 0 of $A$ is $(12 \cdot 2 + 1 \cdot 1 - 3
\cdot 0)/25 = 1$, and times column 1, $(0 + 3 - 3)/25 = 0$. `AffineOps.invert` on this matrix (with zero
translation) writes `[0.48, 0.04, -0.12, -0.0, -0.16, 0.32, 0.04, -0.0, 0.04, -0.08, 0.24, -0.0]` —
the three rows, each followed by its translation, which is $-0$ because $-(\ldots \cdot 0)$ is
negative zero.

**A transform example.** $`M = T(4, 0, 0)\,R_y(90°)\,S(2)`$. `composeTRS` produces the rows
$(0, 0, 2 \mid 4)$, $(0, 2, 0 \mid 0)$, $(-2, 0, 0 \mid 0)$ — up to `float` rounding: the entries that
should be 0 come out as `1.1920929E-7`. So $a = b = 0, c = 2$; $d = 0, e = 2, f = 0$; $g = -2, h = k =
0$:

- `co0` = 0, `co1` = 0, `co2` = $0 - 2 \cdot (-2) = 4$, so `det` = $2 \cdot 4 = 8$ (a scale of 2 on
  three axes multiplies volumes by $2^3 = 8$, §9.3);
- row 0 of the inverse: $(0, 0, (0 - 4)/8) = (0, 0, -0.5)$; row 1: $(0, (0 - 2 \cdot (-2))/8, 0) =
  (0, 0.5, 0)$; row 2: $(4/8, 0, 0) = (0.5, 0, 0)$;
- translation: $`-A^{-1}\mathbf{t} = -(0 \cdot 4,\; 0 \cdot 4,\; 0.5 \cdot 4) = (0, 0, -2)`$.

$M$ sends $(1, 0, 0)$ to $(4, 0, -2)$; the inverse sends $(4, 0, -2)$ to $`(-0.5 \cdot (-2),\; 0,\; 0.5 \cdot 4) + (0, 0, -2) = (1, 0, 0)`$. The rotation shortcut of §10.3 gives the same result: the linear
part is $2R$, so its inverse is $R^T / 2$.

### 10.5 The 4×4 inverse

`Mat4Ops.invert` inverts a general 4×4. It is needed for the inverse view-projection, which is not
affine (its bottom row is not $(0, 0, 0, 1)$), so the shortcut of §10.2 does not apply. The idea is the
same as §10.4 — each entry of the inverse is a cofactor (now the signed determinant of a 3×3 piece)
divided by $\det$ — but organized so that no work is repeated.

The key is a generalization of the cofactor expansion: the determinant of a 4×4 can be expanded along
a **pair of columns**. Take every pair of rows $`\{i, j\}`$; multiply the 2×2 determinant of those rows
in columns 0 and 1 by the 2×2 determinant of the **other** two rows in columns 2 and 3; add the six
products with the signs $+, -, +, +, -, +$, which come from the same checkerboard rule as in §10.4
(the sign is $(-1)$ to the power of the sum of the row and column numbers involved, counted from 1).
The code computes the twelve 2×2 determinants once:

- `b00`…`b05`: the six 2×2 determinants of columns 0 and 1, for row pairs (0,1), (0,2), (0,3), (1,2),
  (1,3), (2,3);
- `b06`…`b11`: the same six row pairs in columns 2 and 3.

Then `det = b00 * b11 - b01 * b10 + b02 * b09 + b03 * b08 - b04 * b07 + b05 * b06` pairs each row pair
with its complement: rows (0,1) with (2,3), (0,2) with (1,3), and so on. (The code's local names `aCR`
follow the column-major storage: `a01` is `m[1]`, column 0, row 1.)

**Worked example.** $`M = T(1, 2, 3)\,S(2, 3, 4)`$: in the code's names, `a00 = 2`, `a11 = 3`,
`a22 = 4`, `a33 = 1`, and the translation `a30 = 1`, `a31 = 2`, `a32 = 3`; everything else is 0. Then
`b00` $= 2 \cdot 3 - 0 = 6$ and `b01` … `b05` are 0; `b07` $`= a_{20}a_{32} - a_{22}a_{30} = 0 - 4 = -4`$,
`b09` $`= a_{21}a_{32} - a_{22}a_{31} = 0 - 8 = -8`$, `b11` $`= a_{22}a_{33} - a_{23}a_{32} = 4`$, and
`b06`, `b08`, `b10` are 0. So $\det = 6 \cdot 4 = 24$, which is $2 \cdot 3 \cdot 4$ as §9.3
predicts.

Each entry of the inverse is a 3×3 cofactor expanded along one column, reusing the `b`s. For example
`out[0]`, entry (row 0, column 0) of the inverse, is `(a11 * b11 - a12 * b10 + a13 * b09) * inv`: the
3×3 determinant of $M$ without row 0 and column 0, expanded along column 1 — `a11`, `a12`, `a13` are
rows 1, 2 and 3 of column 1, and each multiplies the 2×2 determinant of columns 2 and 3 in the other
two rows (`b11` for rows 2, 3; `b10` for rows 1, 3; `b09` for rows 1, 2). You do not need to memorize
the sixteen lines (§22 shows them); the test is the specification:
`assertTrue(m.mul(m.invert()).approxEquals(Mat4f.IDENTITY, 1e-4f))` over 200 random TRS matrices.

### 10.6 When there is no inverse

If $\det A = 0$ the transform flattened space (§9.3): several points went to the same place, and no
transform can tell them apart again. The formula would divide by zero. In `float`, a determinant can
also overflow to infinity or become NaN when the entries are huge or broken. So all three
implementations (`Mat3f`, `Mat4Ops`, `AffineOps`; `Mat4f` and `MMat4f` call `Mat4Ops`) check first:

```java
        if (det == 0 || !Float.isFinite(det)) {
```

and then differ in what they do: `AffineOps.invert` and `Mat4Ops.invert` return `false` (the kernels
never throw; the caller decides — `World.pick` skips the object, §28), while `Mat4f.invert`,
`Mat3f.invert` and `MMat4f.invert` throw `ArithmeticException("singular matrix")`. A matrix with
$\det = 0$ is called **singular**. Note that the check is exact: a determinant of $10^{-30}$ passes,
and the inverse can then have enormous entries. For transforms built from TRS with sane scales that
does not happen.

## 11. Transforming normals

### 11.1 Why normals need the inverse transpose

A **normal** is the unit vector perpendicular to a surface at a point; lighting needs it (§5.5). A
mesh stores one per vertex, in the mesh's own space, and the vertex shader must bring it into the
world with the rest of the vertex. The obvious way — transform it like any direction — is wrong as
soon as a scale is not the same on every axis.

A normal is not an ordinary direction. **Tangent** vectors lie *along* a surface, and they transform
like any direction: the surface is carried along by the transform, and so are the arrows drawn on it.
The normal is defined differently, by being *perpendicular* to all of them. Under a non-uniform scale,
transforming the normal like a tangent breaks the perpendicularity.

**Example.** Take the plane $x + y = 0$, a 45° slope. Its normal is $\mathbf{n} = (1, 1, 0)/\sqrt{2}$
and a tangent along it is $\mathbf{t} = (1, -1, 0)$; check $\mathbf{n} \cdot \mathbf{t} = 0$. Stretch x
by 2: $M = \operatorname{diag}(2, 1, 1)$.

- The tangent becomes $M\mathbf{t} = (2, -1, 0)$. The slope is now flatter: it drops 1 in y over 2 in
  x instead of over 1.
- Transforming the normal the same way gives $M\mathbf{n} \propto (2, 1, 0)$ ("$\propto$": the same
  direction, any length), and $(2, 1, 0) \cdot (2, -1, 0) = 4 - 1 = 3 \ne 0$. Not perpendicular any more.

```
   before (in the xy plane)               after stretching x by 2

   y   n = (1,1)                          y      G n ~ (0.5,1): perpendicular to M t
   ^  /                                   ^     /      M n ~ (2,1): not perpendicular
   | /                                    |    /    __/
   |/                                     |   /  __/
 --+-------> x                          --+--/--'-------> x
    \                                      `--.__
     \  t = (1,-1), along the slope              `--.__  M t = (2,-1): the slope
      v                                                   got flatter
```

What transform $G$ keeps normals perpendicular? We need $(G\mathbf{n}) \cdot (M\mathbf{t}) = 0$ for
every tangent with $\mathbf{n} \cdot \mathbf{t} = 0$. Write the dot products as row times column (§7.8)
and use $(G\mathbf{n})^T = \mathbf{n}^T G^T$:

```math
(G\mathbf{n})^T (M\mathbf{t}) = \mathbf{n}^T (G^T M)\, \mathbf{t}
```

If $G^T M = I$, this is $\mathbf{n}^T\mathbf{t} = 0$ for every tangent, which is what we want. So
choose $G^T = M^{-1}$, that is

$$G = (M^{-1})^T = M^{-T}$$

the **inverse transpose**, also called the **normal matrix**. For the example, $M^{-T} =
\operatorname{diag}(0.5, 1, 1)$ and $G\mathbf{n} \propto (0.5, 1, 0)$: $(0.5, 1, 0) \cdot (2, -1, 0) =
1 - 1 = 0$. Perpendicular again. Notice that normals **shrink** along the stretched axis, the opposite
of tangents: the surface got flatter, so its normal turned towards +Y. That is exactly what the test
`mat3NormalMatrixAndMutableTypes` asserts (`n.x() * 2 == n.y()`) using
[`Mat3f.normalMatrix`](../../modules/math/src/main/java/dev/jeddy/math/Mat3f.java), which returns
`(0.35355338, 0.70710677, 0.0)` for this input:

```java
    public static Mat3f normalMatrix(Mat4f model) {
        return of(model).invert().transpose();
    }
```

Two special cases explain why many engines get away without it. For a rotation, $R^{-T} = (R^T)^T = R$
(§10.3): normals transform like everything else. For a uniform scale $s$, $(sR)^{-T} = R/s$, which only
changes the length — and a normal has to be renormalized after transformation anyway. Only
non-uniform scale (a statue stretched to fit a niche, a squashed barrel) needs the real thing.

### 11.2 The cofactor trick

Computing a full inverse for every vertex, on the GPU, with a division by the determinant, would be
wasteful. The shader in `instance.glsl` uses the result of §10.4 instead. There we found that the
**rows** of $`(\det A)\,A^{-1}`$ are $`\mathbf{c}_1 \times \mathbf{c}_2`$, $`\mathbf{c}_2 \times \mathbf{c}_0`$ and $`\mathbf{c}_0 \times \mathbf{c}_1`$. Transpose both sides: those three cross products
are the **columns** of $`(\det A)\,A^{-T}`$, the cofactor matrix. So

```math
\text{cofactor}(A)\,\mathbf{n} = (\det A)\,A^{-T}\mathbf{n}
```

points along the correct normal — times $\det A$, which only scales it, and possibly flips it when
$\det A < 0$. From
[`instance.glsl`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/engine/instance.glsl):

```glsl
// Transforms a normal by the inverse transpose (the cofactor matrix up to scale), so non-uniform
// scale keeps normals perpendicular; mirrored transforms keep their outward direction.
vec3 jeddyTransformNormal(JeddyInstance i, vec3 n) {
    mat3 m = jeddyLinear(i);
    mat3 cofactor = mat3(cross(m[1], m[2]), cross(m[2], m[0]), cross(m[0], m[1]));
    float det = dot(m[0], cross(m[1], m[2]));
    return normalize(cofactor * n) * (det < 0.0 ? -1.0 : 1.0);
}
```

Things to notice:

- GLSL's `mat3(a, b, c)` constructor takes **columns**, and `m[0]` is column 0. `jeddyLinear` builds
  `m` from the instance's three rows with `transpose(mat3(i.row0.xyz, i.row1.xyz, i.row2.xyz))`, so
  that `m[j]` really is column $j$ (§27).
- `cofactor * n` equals $`(\det A)\,A^{-T}\mathbf{n}`$. `normalize` removes the size $|\det A|$ but not
  its sign.
- When $\det A < 0$ (a mirror, §9.4), the result points inwards, so the last factor flips it back to
  the direction of $A^{-T}\mathbf{n}$, the outward normal.
- There is no division by the determinant. A very thin object (a tiny scale on one axis, a tiny
  determinant) still gets correct normals instead of huge numbers. Even a truly flat transform mostly
  works: for $\operatorname{diag}(1, 1, 0)$ the columns are $(1, 0, 0)$, $(0, 1, 0)$, $(0, 0, 0)$, the
  cofactor matrix keeps only $`\mathbf{c}_0 \times \mathbf{c}_1 = (0, 0, 1)`$, and cofactor
  $`\cdot\,\mathbf{n} = n_z\,(0, 0, 1)`$ — the flattened object's own normal, ±Z. Only a normal with
  $`n_z = 0`$, perpendicular to the squashed axis, becomes the zero vector, whose `normalize` GLSL leaves
  undefined; such a face has no area left to draw anyway.

**Worked numbers.** $A = \operatorname{diag}(2, 1, 1)$: columns $(2, 0, 0)$, $(0, 1, 0)$, $(0, 0, 1)$.
The cross products are $(1, 0, 0)$, $(0, 2, 0)$, $(0, 0, 2)$, so cofactor $= \operatorname{diag}(1, 2,
2)$ and $\det = 2$. For $\mathbf{n} = (1, 1, 0)/\sqrt2$: cofactor $`\cdot\, \mathbf{n} \propto (1, 2, 0)`$, normalized $(0.4472, 0.8944, 0)$ — the same direction as `normalMatrix`'s $(0.3536, 0.7071, 0)$,
whose y is also twice its x.

A mirror, $A = \operatorname{diag}(-1, 1, 1)$: columns $(-1, 0, 0)$, $(0, 1, 0)$, $(0, 0, 1)$; cross
products $(1, 0, 0)$, $(0, -1, 0)$, $(0, 0, -1)$; $\det = -1$. A face of the mirrored object that
pointed up, $\mathbf{n} = (0, 1, 0)$, must still point up. Cofactor $`\cdot\,\mathbf{n} = (0, -1, 0)`$,
normalized, times $-1$: $(0, 1, 0)$. ✓ A face that pointed along +X now faces −X: cofactor $`\cdot\, (1, 0, 0) = (1, 0, 0)`$, times $-1$: $(-1, 0, 0)$. ✓

**Cost.** Three cross products and a dot product per vertex cost about as much as building the
inverse, but this version needs no reciprocal and handles mirrors in one place. Skinned vertices go
through it too, after `jeddySkinDirection` has applied the joint matrices with their plain linear part;
the comment there says why that is enough: "joints scale uniformly, so normals stay perpendicular"
(chapter [09](09-animation-and-skinning.md)).

**What happens next on the GPU.** The vertex shader writes the normal to an output, `vNormal`. The
rasterizer **interpolates** outputs across the triangle: each fragment gets a weighted average of the
three corners' values. An average of unit vectors is shorter than 1 — halfway between $(1, 0, 0)$ and
$(0, 1, 0)$ is $(0.5, 0.5, 0)$, length 0.7071 — so the fragment shader normalizes again before
lighting: `vec3 n = normalize(vNormal);` in `pbr.frag`. Forgetting that makes the middle of every
triangle darker than its corners.

## 12. Matrices in memory and on the GPU

A matrix is a 2D table; memory is a 1D list of bytes (L00 §8.1). Some order has to be chosen, and the
GPU has to agree with it. Jeddy uses two orders on purpose (decision D21).

### 12.1 Column-major Mat4, 16 floats

All 4×4 matrices — `Mat4f`, `MMat4f`, `Mat4Ops` and the camera matrices of `IsoCamera` — store
**columns one after another**. Element (row $r$, column $c$) is at index `c * 4 + r`, as the `Mat4Ops`
Javadoc says ("column-major (element `[c * 4 + r]`)"):

```
   math layout (rows x columns)          index in float[16]

   | m00  m01  m02  m03 |               |  0   4   8  12 |
   | m10  m11  m12  m13 |   stored as   |  1   5   9  13 |
   | m20  m21  m22  m23 |   -------->   |  2   6  10  14 |
   | m30  m31  m32  m33 |               |  3   7  11  15 |
     (conventional row-column names)
```

| Index | 0–3 | 4–7 | 8–11 | 12–15 |
|---|---|---|---|---|
| Holds | column 0: image of X, then 0 | column 1: image of Y, then 0 | column 2: image of Z, then 0 | column 3: translation x, y, z, then 1 |

For `Mat4f.translation(1, 2, 3)`, `toArray` writes `[1, 0, 0, 0,  0, 1, 0, 0,  0, 0, 1, 0,  1, 2, 3, 1]`:
the translation is at indices 12, 13 and 14.

**Why columns?** Because it is what OpenGL and GLSL use by default. In GLSL a `mat4` is four `vec4`
columns, and `m[c]` is column `c`. The engine hands 4×4 matrices to the GPU in two ways, and in both
the 16 floats go over unchanged:

- **The per-frame constants.** `frame.glsl` declares a uniform block with `layout(std140)` whose first
  members are `mat4 jView; mat4 jProjection; mat4 jViewProjection; …`. **std140** is the GLSL rule set
  for laying out a uniform block in memory; under it a `mat4` is four 16-byte columns, 64 bytes in a
  row. `SceneRenderer.writeFrameConstants` copies each camera matrix into the frame's memory with
  `MemorySegment.copy(v, 0, m, ValueLayout.JAVA_FLOAT, o, 16)` — sixteen floats, in array order.
- **Single matrix uniforms**, for the depth-only passes (and the shadow, outline and debug passes):
  `glProgramUniformMatrix4fv(program, 0, 1, false, matrix)` in `SceneRenderer`. The fourth argument is `transpose`; `false` tells GL the data is
  already column by column.

**What the driver and the GPU do with them.** The frame constants live in the per-frame ring of
mapped GPU-visible memory that [Lesson 1](lessons/01-buffers-in-flight.md) builds: the CPU writes them
with plain stores, and `state.bindUniform(FRAME_BINDING, …)` (a `glBindBufferRange` underneath) tells
the GPU where this frame's 544 bytes start. No copy is made; Lesson 1's fences make sure the CPU never
overwrites a frame the GPU is still reading. When a shader reads `jViewProjection`, every invocation
reads the same 64 bytes, which is the access pattern GPUs serve from small, fast caches for constant
data. `glProgramUniformMatrix4fv` is different: it stores the 16 floats in the program object's own
uniform state at the moment of the call (the driver copies them), so the CPU-side `matrix` can be
reused right away.

**What goes wrong if the order is mixed up.** Upload a row-major array as if it were column-major and
the GPU uses the **transpose**. For a translation $T(1, 2, 3)$, the transpose has the translation in
the **bottom row**: the shader computes $w = x + 2y + 3z + 1$ instead of 1, the perspective divide
(§8.9) divides by that, and the mesh collapses towards the origin or vanishes. A transposed rotation
turns the wrong way (§10.3: the transpose is the inverse). Both bugs are silent: GL reports no error.

`Mat4Ops.mul` computes the product one output column at a time (§22 walks through it).

### 12.2 Row-major 3×4 affine transforms, 12 floats

Object transforms — entity world matrices, instance records, joint matrices — never need the bottom
row of a 4×4: it is always $(0, 0, 0, 1)$ (§8.5). So Jeddy drops it and stores the remaining 3×4
block **row by row**. From the `AffineOps` Javadoc: "three rows
`[r0c0 r0c1 r0c2 tx | r1c0 r1c1 r1c2 ty | r2c0 r2c1 r2c2 tz]`".

```
   math layout (3 x 4, last row 0 0 0 1 implied)    index in float[12]

   | r00  r01  r02  tx |                            |  0   1   2   3 |
   | r10  r11  r12  ty |                            |  4   5   6   7 |
   | r20  r21  r22  tz |                            |  8   9  10  11 |
```

| Index | 0–2 | 3 | 4–6 | 7 | 8–10 | 11 |
|---|---|---|---|---|---|---|
| Holds | row 0 of the linear part | tx | row 1 | ty | row 2 | tz |

So the translation is at indices 3, 7 and 11, and column $j$ of the linear part (the image of axis
$j$) is at indices $j$, $4 + j$ and $8 + j$. `AffineOps.STRIDE` is 12: transform $i$ of an array
starts at `i * 12`.

On the GPU each row is a `vec4`, and an instance record is four of them. From `instance.glsl`:

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
```

12 floats plus four 32-bit values (material, flags, entity, and the camera fade as float bits) make the
64-byte record (`DrawBuilder.INSTANCE_BYTES = 64`). `DrawBuilder` writes the 12 floats straight from
the world transforms, in order, blending the previous and the current simulation tick on the way
([Lesson 1](lessons/01-buffers-in-flight.md) §14.1 builds that loop). The block is a **shader storage
buffer** (SSBO) laid out with **std430**, the storage-buffer rule set. The vertex shader picks its
record with `jeddyInstances[gl_BaseInstance + gl_InstanceID]`; every vertex of one instance reads the
same 64 bytes, so after the first vertex they come from cache.

### 12.3 Why two layouts, and the alternatives

D21 records the choice: "`Mat4` stays column-major for GL uniforms, while affine transforms are
row-major 3×4 for direct SSBO upload. `AffineOps.toMat4` converts when a uniform needs one." The
reasons, and the alternatives they rule out:

1. **Size.** 12 floats instead of 16 is 25 % less memory and bandwidth for every transform. The
   transform store, the render packet (`prevWorld`, `currWorld`) and the instance buffer all hold
   thousands of them. *Rejected: store every transform as a full column-major 4×4.* The instance
   record would grow from 64 to 80 bytes (64 for the matrix, 16 for the data), and every hierarchy
   product would do 64 multiplications instead of 36 (§8.5).
2. **The GPU layout.** Three rows of four floats are exactly three `vec4`s, which std430 packs with no
   padding: 48 bytes. *Rejected: store the 3×4 block by columns.* That is four 3-float columns, and
   std430 aligns a `vec3` to 16 bytes, so each column would sit in a 16-byte slot: 64 bytes, the same
   as the full 4×4 and a third more than the rows.
3. **No conversion.** World transforms go into instance records float by float, with no reordering, on
   the worker threads that build the frame.
4. **Cheap transforms.** Transforming a point is three 4-component dot products with $(x, y, z, 1)$,
   which is what `jeddyTransformPoint` does (§7.4): on the GPU, a short chain of multiply-adds per
   row.

And why not make the 4×4s row-major too, for uniformity? GLSL can read row-major matrices
(`layout(row_major)`), and `glProgramUniformMatrix4fv` can transpose (`transpose = true`), but the
default everywhere — GLSL, the GL API, glTF files (§12.4) and most references — is column-major, and a
4×4 that matches the default needs no flag anywhere. The 3×4 transforms never go to the GPU as
matrices at all, only as three `vec4`s, so their order is free to be chosen for the reasons above.

### 12.4 Converting between the two

When something needs a real `mat4` (a uniform, or a product with the camera), `AffineOps.toMat4`
expands a 3×4 into a column-major 4×4:

```java
    /** Converts to a column-major 4×4 matrix (for uniforms). */
    public static void toMat4(float[] m, int mo, float[] out, int o) {
        out[o] = m[mo];
        out[o + 1] = m[mo + 4];
        out[o + 2] = m[mo + 8];
        out[o + 3] = 0;
        out[o + 4] = m[mo + 1];
        // ... columns 1 and 2 the same way
        out[o + 12] = m[mo + 3];
        out[o + 13] = m[mo + 7];
        out[o + 14] = m[mo + 11];
        out[o + 15] = 1;
    }
```

Column 0 of the 4×4 (`out[0..3]`) is gathered from indices 0, 4 and 8 of the 3×4 — the first entry of
each row — then the implied 0. `Mat4Ops.mulAffine` skips the conversion entirely: it multiplies a 4×4
by a 3×4 "treated as a 4×4 with last row (0, 0, 0, 1)" (§22).

Going the other way, glTF stores matrices column-major 4×4. When
[`Skeleton`](../../modules/animation/src/main/java/dev/jeddy/animation/Skeleton.java) reads the
inverse bind matrices of a skin, it converts with a loop that is a good summary of both layouts:

```java
            // glTF matrices are column-major 4×4: element (row r, column c) is m[c * 4 + r].
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 4; c++) {
                    inverseBind[j * AffineOps.STRIDE + r * 4 + c] = ibm[j * 16 + c * 4 + r];
                }
            }
```

Row-major index `r * 4 + c` on the left, column-major index `c * 4 + r` on the right. The loop stops at
row 2: the glTF matrix's bottom row is the implied $(0, 0, 0, 1)$ and is simply not copied.

### 12.5 A naming trap

Three different naming schemes for matrix elements meet in this module. Read signatures carefully.

| Code | Name `mAB` means | Example |
|---|---|---|
| `Mat4f`, `Mat3f` record components | A = **column**, B = row (`mCR`) | `m30` is column 3, row 0: translation x |
| `Quatf.fromMatrix(m00, m01, ...)` parameters | A = **row**, B = column (`mRC`), "given row by row" | `m01` is row 0, column 1 |
| `AffineOps` index | plain index into the row-major 3×4 | `m[mo + 3]` is tx |

(This chapter's own formulas use the usual mathematical $`M_{rc}`$, row first, like `Quatf.fromMatrix`.)
The test `quaternionsAgreeWithMatrices` shows the consequence: to pass a `Mat4f` to `Quatf.fromMatrix`
it has to swap the indices, `fromMatrix(r.m00(), r.m10(), r.m20(), r.m01(), ...)`. The first three
arguments are row 0, which in `Mat4f`'s naming is `m00, m10, m20`.

## 13. Compact numbers: half floats, snorm and octahedral vectors

Vertex data is the largest stream the GPU reads every frame, so Jeddy stores it compactly (decision
D27). A `.jmesh` vertex has a 12-byte position stream (three `float`s) and a 16-byte attribute stream
([docs/formats/jmesh.md](../formats/jmesh.md)):

| Bytes | Attribute | Encoding |
|---|---|---|
| 0–7 | QTangent (normal, tangent, handedness) | `snorm16` × 4 |
| 8–11 | uv0 | `f16` × 2 |
| 12–15 | uv1 | `f16` × 2 |

Stored as plain floats, the same vertex would need a normal (12 bytes), a tangent with its handedness
(16) and two UV pairs (8 + 8): 44 bytes of attributes instead of 16, 56 bytes per vertex instead of 28.
Depth-only passes read just the 12-byte positions (D27). This section derives the three encodings.

### 13.1 Half floats

A **half float** (IEEE 754 binary16) is a 16-bit floating-point number built exactly like the 32-bit
`float` of L00 §7.2, with smaller fields:

```
bit:  15 | 14 ...... 10 | 9 ............ 0
      sign | exponent (5) | fraction (10)
value = (-1)^sign × 1.fraction × 2^(exponent − 15)
```

The exponent bias is 15 instead of 127. **Worked example: 1.5.** $`1.5 = 1.1_2 \times 2^0`$: sign 0,
exponent $0 + 15 = 15$ = `01111`, fraction `1000000000`. Together `0 01111 1000000000` = `0011 1110
0000 0000` = `0x3E00`.

**0.1.** $0.1 = 1.6 \times 2^{-4}$: exponent $-4 + 15 = 11$ = `01011`; the fraction is $0.6 \times
1024 = 614.4$, rounded to 614 = `1001100110`. Bits `0 01011 1001100110` = `0x2E66`, whose value is $(1 +
614/1024) \times 2^{-4} = 0.0999755859375$. Similarly 0.7 becomes 0.7001953125 and $\tfrac13$ becomes
0.333251953125.

The largest half is $(2 - 2^{-10}) \times 2^{15} = 65504$, and 10 fraction bits give about 3 decimal
digits. Between $2^e$ and $2^{e+1}$ the representable values are $2^{e-10}$ apart (1024 steps per
doubling). For texture coordinates:

- $u \in [0.5, 1)$: spacing $2^{-11} = 1/2048$, one texel of a 2048-wide texture;
- tiling coordinates $u \in [2, 4)$: spacing $2^{-9}$, four times coarser.

That is fine for typical models with UVs in $[0, 1]$, and it is the trade the format makes for halving
the UV size. Java has converted to and from binary16 since JDK 20 (`Float.floatToFloat16`,
`Float.float16ToFloat`); HotSpot turns those calls into single instructions on CPUs that have them
(DESIGN §4.6 lists the `Float.floatToFloat16` intrinsics).

### 13.2 snorm and unorm integers

A **signed normalized** integer ("snorm") stores a number in $[-1, 1]$ as an integer scaled to the
type's range. For 16 bits the largest positive `short` is $2^{15} - 1 = 32767$:

```math
s = \operatorname{round}(\operatorname{clamp}(v, -1, 1) \cdot 32767), \qquad v' = \max\!\left(-1,\; \frac{s}{32767}\right)
```

The step between neighbouring values is $1/32767 \approx 3.05 \times 10^{-5}$, so the worst rounding
error is half that. **Example:** $0.5 \cdot 32767 = 16383.5$, which `Math.round` rounds up to 16384,
which decodes to $16384 / 32767 = 0.50001526$.

Why the `max(-1, …)` when decoding? A `short` goes down to $-32768$ (L00 §5), one step more than the
encoder ever writes, and $-32768/32767 = -1.0000305$ is slightly out of range. Clamping makes every bit
pattern decode into $[-1, 1]$: $-32767$ and $-32768$ both decode to $-1$, and nothing decodes below
it. (The encoder clamps too: `packSnorm16(-2f)` is $-32767$, which decodes to exactly $-1$, as the
test `packing` checks.) This is the same
rule OpenGL applies when a vertex attribute is declared as a normalized `GL_SHORT`: the conversion of
signed normalized fixed-point values in the GL specification is $\max(c / (2^{b-1} - 1), -1)$ for a
$b$-bit integer $c$. Encoder and GPU agree to the bit.

The 8-bit version uses 127: `packSnorm8(0.5f)` is $\operatorname{round}(63.5) = 64$, which decodes to
$64/127 = 0.503937$ — a step of $1/127 \approx 0.008$, fine for colours and coarse directions. The
unsigned versions ("unorm") map $[0, 1]$ to $[0, 255]$ or $[0, 65535]$: `packUnorm8(0.5f)` is 128.
Four unorm8 values make an RGBA colour in one `int`, red in the lowest byte, which is how GL reads 4
bytes as `GL_RGBA8` on a little-endian machine (L00 §6.4, §8.1): `packRgba8(1, 0.5f, 0, 1)` is
`0xFF0080FF` (alpha 255, blue 0, green 128, red 255).

### 13.3 Octahedral unit vectors

A unit vector has three components, but only two of them carry information: once you know $x$ and $y$,
$z$ is $\pm\sqrt{1 - x^2 - y^2}$. **Octahedral encoding** stores a unit vector in two numbers, and does
it so that the stored values spread evenly over the directions.

1. **Project onto the octahedron.** Divide the vector by $|x| + |y| + |z|$. The result lies on the
   octahedron $|x| + |y| + |z| = 1$ — a diamond shape with corners on the six axes. Keep its
   $(x, y)$ as $(u, v)$: for the upper half ($z \ge 0$) this is the diamond $|u| + |v| \le 1$ in the
   square $[-1, 1]^2$.
2. **Fold the lower half.** For $z < 0$, reflect the point into one of the four corner triangles of
   the square outside the diamond: $u' = (1 - |v|)\operatorname{sign}(u)$, $v' = (1 -
   |u|)\operatorname{sign}(v)$. Now every direction has its own point of the square.
3. **Store** $u$ and $v$ as two snorm16 values (§13.2): 4 bytes instead of 12.

```
      v
      ^
    1 +---------+---------+
      |z<0    / | \    z<0|      the diamond |u| + |v| <= 1 holds the
      |     /   |   \     |      upper half (z >= 0) of the sphere;
      |   /   z >= 0  \   |      the four corner triangles hold the
      | /       |       \ |      folded lower half (z < 0)
      +---------+---------+---> u
      | \       |       / |
      |   \   z >= 0  /   |
      |     \   |   /     |
      |z<0    \ | /    z<0|
   -1 +---------+---------+
     -1                   1
```

**Worked example.** $(0, 0.6, -0.8)$: $|x| + |y| + |z| = 1.4$, so $(u, v) = (0, 0.428571)$. Since $z <
0$, fold: $u' = (1 - 0.428571) \cdot \operatorname{sign}(0) = 0.571429$ (`signNotZero` treats 0 as
positive) and $v' = (1 - 0) \cdot 1 = 1$. As snorm16: $\operatorname{round}(0.571429 \cdot 32767) =
18724$ = `0x4924` and 32767 = `0x7FFF`, packed as `0x7FFF4924` (x in the low 16 bits). Decoding
reverses the fold and normalizes: $(0, 0.59999996, -0.8)$.

**Accuracy, measured.** Encoding 1,000,000 uniformly random unit vectors and decoding them (the
program is exercise 9) gave an angular error of 0.0013° on average and 0.0037° at worst, on the 2-core
sandbox this chapter was written on; the `Packing` Javadoc's "about 0.002°" is the right order. The test
`packing` checks 5,000 random vectors keep a dot product above 0.99999 with the original.

The mesh format does not use this encoding for normals. It stores a **QTangent** instead — the whole
tangent frame (normal, tangent, handedness) as one quaternion in four snorm16 — because one encoding
then carries all three (chapter [03](03-rotations-and-transforms.md)).

### 13.4 What the vertex fetch does with them

`MeshRegistry` tells GL how to read the attribute stream:

```java
        glVertexArrayAttribFormat(vao, 1, 4, GL_SHORT, true, 0);
        ...
        glVertexArrayAttribFormat(vao, 2, 2, GL_HALF_FLOAT, false, 8);
```

Attribute 1 is "four `short`s, **normalized**, at byte 0 of each 16-byte attribute"; attribute 2 is "two
half floats at byte 8". The driver records these formats in the **vertex array object** (VAO). When a
draw runs, the GPU's **vertex fetch** reads each vertex's bytes from the buffer and converts them to
32-bit floats before the vertex shader sees them: the snorm rule of §13.2 for the normalized shorts,
the half-to-float conversion for the halves (`normalized` is ignored for floating-point types). On some
GPUs fixed-function hardware does this; on others the driver adds a few instructions at the start of
the vertex shader. Either way the shader declares plain floats — `layout(location = 1) in vec4
jeddyQTangent; // snorm16 x4, normalized by the vertex fetch` in `vertex.glsl` — and pays nothing to
decode. What can go wrong: declaring `normalized = false` would hand the shader values like 16384.0
instead of 0.5, and a wrong offset would read the UVs' bytes as the tangent. Neither raises a GL
error.

---

# Part B — Building it

Part A derived every formula. Part B writes the `math` module in the order you would write it from
empty files, quoting the repository's code. Each method points back to the section of Part A that
derives it. If you are rebuilding it yourself (L00 §1), make a scratch package in
`modules/math/src/test/java/scratch/` and type along; §30's tests are the ones to copy.

## 14. Step 1: the module and its three layers

### 14.1 An empty module

The module is `modules/math`, Java module and package `dev.jeddy.math`. Its `module-info.java` is the
smallest in the engine:

```java
/**
 * Vectors, matrices, quaternions, geometry, intersection tests, bulk SoA kernels, RNG/dice and
 * packing. See §8 of docs/DESIGN.md.
 */
module dev.jeddy.math {
    exports dev.jeddy.math;
}
```

No `requires`: the module depends on nothing but `java.base`. That is deliberate. Physics, scene,
render, animation and gameplay all need vectors, and DESIGN §3 keeps `physics`, `scene` and `render`
from depending on each other; a dependency-free `math` at the bottom is what they can all share. Its
build script applies only a `jeddy.*` convention plugin (CLAUDE.md, "Conventions").

Every public type's Javadoc ends with a **thread ownership** line, the engine-wide rule (CLAUDE.md).
For this module it is one of three answers: "immutable" (records, safe everywhere), "single owner
thread" (the mutable scratch types), or "stateless, callable from any thread" (the static kernels).

### 14.2 Why three layers

The same formula appears three times in the module, written for three situations. DESIGN §8.1 calls
them layers:

| Layer | Types | Use it for | Allocation |
|---|---|---|---|
| 1. Value types | records `Vec2f`, `Vec3f`, `Vec4f`, `Quatf`, `Mat3f`, `Mat4f`, `Aabb3f`, `Ray3f`, `Planef`, `Spheref`, `Capsulef` | APIs, configuration, setup code, tests | Every operation returns a new object |
| 2. Mutable scratch | `MVec3f`, `MQuatf`, `MMat4f` | Per-frame code that works on one thing at a time (cameras, controllers) | None: methods modify `this` and return it |
| 3. Bulk kernels | `Vec3Ops`, `QuatOps`, `Mat4Ops`, `AffineOps`, `CullOps` | Hot loops over many entities | None: static methods over `float[]` plus offsets |

The reason is the engine's first rule: no allocation per frame (L00 §8.2, DESIGN §4.2, D5). Each layer
is the cheapest way to obey it for its kind of caller, and the math is identical in all three. Once you
understand `Vec3f.cross`, you can read `MVec3f.cross` and the cross products written out inside
`QuatOps.rotate` or `AffineOps.invert`.

### 14.3 Layer 1: records, and what they cost today

The value types are Java **records**: a class declared by its components, for which the compiler
writes the constructor, the accessors (`x()`), `equals`, `hashCode` and `toString`. From
[`Vec3f.java`](../../modules/math/src/main/java/dev/jeddy/math/Vec3f.java):

```java
public record Vec3f(float x, float y, float z) {
```

Records are immutable (their fields are `final`) and the engine's code never uses their identity —
never compares them with `==`, never locks on them. That is the shape the JDK's **value classes**
(JEP 401) will need: objects with no identity, which the JVM may store flat, inside an array or another
object, without a separate heap allocation. When JEP 401 is final, `record` becomes `value record`, a
one-word change (decision D4: stable Java only, and math types written to be "Valhalla-ready").

Until then each `new Vec3f(...)` is a real heap object: an 8-byte header plus three 4-byte floats,
rounded up to 24 bytes (L00 §8.2). The JIT's **escape analysis** can remove an allocation when it
proves the object never leaves the method being compiled — but only after the code is hot enough to
be compiled by C2 (L00 §9.1), and only when every call involved is inlined. A small experiment shows
both sides (exercise 10 has the program). It calls `Vec3f.cross` and `Mat4f.mul` in loops of a
million calls and reads the thread's allocation counter, on the 2-core sandbox (Intel Xeon, which
`/proc/cpuinfo` reports at 2.10 GHz; JDK 27+35):

| Run | `cross` bytes per call | `Mat4f.mul` bytes per call |
|---|---|---|
| interpreter only (`-Xint`): nothing is compiled, every object is allocated | 24 | 312 |
| first two rounds (interpreter, C1 and C2 mixed while compilation finishes) | 4–12 | 178–195 |
| after C2 has compiled the loop | 0 | 160 |
| after C2, with `-XX:-DoEscapeAnalysis` | 24 | 312 |

The 24 bytes are one `Vec3f`. The 312 bytes of `Mat4f.mul` are its three temporary `float[16]` arrays
(80 bytes each: header, length, 64 bytes of floats) plus the 72-byte result (8 + 16 × 4). C2 removes
the small one completely and only half of the big one. That is why DESIGN §4.2 says hot paths must
not rely on escape analysis: whether it works depends on how much the JIT decided to inline, which you
cannot see from the source and which can change with a JDK update. (**Inlined** means the JIT has
copied the called method's body into the caller, so it can optimize the two as one piece of code; a
**JEP**, JDK Enhancement Proposal, is the OpenJDK document that proposes and tracks a change to Java.) Records are for code that is not
per element: configuration, setup, tests, and the fields of components (a `Vec3f home` in a component
record becomes three `float` columns, [Lesson 3](lessons/03-data-layout.md) §4.1).

### 14.4 Layer 2: mutable scratch

Per-frame code that thinks in single objects — a camera, a controller — gets mutable classes instead.
From [`MVec3f.java`](../../modules/math/src/main/java/dev/jeddy/math/MVec3f.java):

```java
public final class MVec3f {
    /** X component. */
    public float x;
    // ...
    /** Replaces this with {@code this × o}. */
    public MVec3f cross(MVec3f o) {
        return set(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
    }
```

A system keeps these as fields (`private final MVec3f tmp = new MVec3f()`, DESIGN §4.2) and reuses them
every frame. `IsoCamera` does exactly that with its `eye` field (§17).

### 14.5 Layer 3: kernels over arrays

Where the engine spends its time, data is **struct-of-arrays** (SoA): one `float[]` per component
instead of one object per entity ([Lesson 3](lessons/03-data-layout.md) §4, DESIGN §4.1). The kernels
take that data directly. From [`Vec3Ops.java`](../../modules/math/src/main/java/dev/jeddy/math/Vec3Ops.java):

```java
    /** {@code p[i] += v[i] * dt} for {@code [start, end)}: integrate positions by velocities. */
    public static void addScaled(
            float[] px, float[] py, float[] pz,          // (parameter list condensed)
            float[] vx, float[] vy, float[] vz,
            float dt, int start, int end) {
        for (int i = start; i < end; i++) {
            px[i] += vx[i] * dt;
            py[i] += vy[i] * dt;
            pz[i] += vz[i] * dt;
        }
    }
```

That is §3.1's $`\mathbf{p} \leftarrow \mathbf{p} + \mathbf{v}\,\Delta t`$ for a whole range of entities. A
simple counted loop over primitive arrays is the shape HotSpot's C2 compiler handles best: it can prove
the indices stay in range and drop the bounds checks (Lesson 3 §13.2), and it can use **SIMD**
instructions (single instruction, multiple data: one instruction that adds or multiplies, say, eight
floats held side by side in a wide register) to process several elements at once. The `[start, end)` range (L00 §2.6) is there so the job system
can split one loop across worker threads, each taking a slice. Matrices in this layer are runs of
`float`s inside a bigger array, addressed by an offset (`float[] m, int mo`), so an array of 10,000
transforms is one `float[120_000]`, not 10,000 objects.

## 15. Step 2: Vec3f, the value type

Start with the record line of §14.3, then the constants:

```java
    /** (0, 0, 0). */
    public static final Vec3f ZERO = new Vec3f(0, 0, 0);

    /** (1, 1, 1). */
    public static final Vec3f ONE = new Vec3f(1, 1, 1);

    /** (1, 0, 0). */
    public static final Vec3f UNIT_X = new Vec3f(1, 0, 0);

    /** (0, 1, 0), up. */
    public static final Vec3f UNIT_Y = new Vec3f(0, 1, 0);

    /** (0, 0, 1). */
    public static final Vec3f UNIT_Z = new Vec3f(0, 0, 1);
```

Shared constants cost nothing per use: they are created once, when the class is first used. `ONE` is
the "no scale" argument of `Mat4f.trs`, and the unit vectors are the axes of §7.2.

**Addition, subtraction, scaling** (§3.1):

```java
    /** Returns {@code this + o}. */
    public Vec3f add(Vec3f o) {
        return new Vec3f(x + o.x, y + o.y, z + o.z);
    }

    /** Returns {@code this + (x, y, z)}. */
    public Vec3f add(float ox, float oy, float oz) {
        return new Vec3f(x + ox, y + oy, z + oz);
    }

    /** Returns {@code this - o}. */
    public Vec3f sub(Vec3f o) {
        return new Vec3f(x - o.x, y - o.y, z - o.z);
    }

    /** Returns {@code this * s}. */
    public Vec3f mul(float s) {
        return new Vec3f(x * s, y * s, z * s);
    }

    /** Returns the component-wise product. */
    public Vec3f mul(Vec3f o) {
        return new Vec3f(x * o.x, y * o.y, z * o.z);
    }

    /** Returns {@code -this}. */
    public Vec3f negate() {
        return new Vec3f(-x, -y, -z);
    }
```

- Inside the record, `o.x` reads the other vector's field directly; outside, code calls the accessor
  `o.x()`. Both are the same field.
- `add(float, float, float)` exists to avoid building a temporary `Vec3f` just to add it:
  `Aabb3f.expand` grows a box with `max.add(amount, amount, amount)`.
- `mul(Vec3f)` multiplies component by component. It is not a vector product from Part A; it is how
  you apply a per-axis scale $`(s_x, s_y, s_z)`$, which is $`\operatorname{diag}(s_x, s_y, s_z)`$ times the
  vector (§7.3).

**The products** (§5.1 and §6.1), each a single line:

```java
    /** Returns the dot product. */
    public float dot(Vec3f o) {
        return x * o.x + y * o.y + z * o.z;
    }

    /** Returns the cross product {@code this × o}. */
    public Vec3f cross(Vec3f o) {
        return new Vec3f(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
    }
```

Compare `cross` with the formula $`(a_y b_z - a_z b_y,\; a_z b_x - a_x b_z,\; a_x b_y - a_y b_x)`$:
`this` is $\mathbf{a}$, `o` is $\mathbf{b}$, and the three arguments are the three components in
order.

**Lengths and distances** (§3.2–3.4):

```java
    /** Returns the length. */
    public float length() {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    /** Returns the squared length. */
    public float lengthSquared() {
        return x * x + y * y + z * z;
    }

    /** Returns this vector at unit length, or zero if it has no length. */
    public Vec3f normalize() {
        float l = length();
        return l > 0 ? new Vec3f(x / l, y / l, z / l) : ZERO;
    }

    /** Returns the distance to {@code o}. */
    public float distance(Vec3f o) {
        return (float) Math.sqrt(distanceSquared(o));
    }

    /** Returns the squared distance to {@code o}. */
    public float distanceSquared(Vec3f o) {
        float dx = x - o.x;
        float dy = y - o.y;
        float dz = z - o.z;
        return dx * dx + dy * dy + dz * dz;
    }
```

- Java has no `float` square root: `Math.sqrt` takes and returns a `double`. The `float` sum is
  widened, rooted in `double`, and the `(float)` cast rounds back. Because `double` has more than twice
  the precision bits of `float`, rounding twice this way gives the same `float` a correctly rounded
  `float` square root would.
- `normalize` divides each component by the length (§3.3). The test `l > 0` does three jobs: it catches
  the zero vector, and it is also `false` when `l` is NaN (every comparison with NaN is false, L00 §7.3),
  so a broken input returns `ZERO` instead of spreading NaN. An infinite length passes the test, and
  then $x / \infty = 0$.
- `distance` reuses `distanceSquared`; callers that only compare distances call `distanceSquared` and
  skip the root (§3.4).

**Interpolation and helpers:**

```java
    /** Returns {@code this + (o - this) * t}. */
    public Vec3f lerp(Vec3f o, float t) {
        return new Vec3f(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t);
    }

    /** Returns the component-wise minimum. */
    public Vec3f min(Vec3f o) {
        return new Vec3f(Math.min(x, o.x), Math.min(y, o.y), Math.min(z, o.z));
    }

    /** Returns the component-wise maximum. */
    public Vec3f max(Vec3f o) {
        return new Vec3f(Math.max(x, o.x), Math.max(y, o.y), Math.max(z, o.z));
    }

    /** Returns this vector with y set to 0 (projected onto the ground plane). */
    public Vec3f flat() {
        return new Vec3f(x, 0, z);
    }

    /** Returns {@code true} if every component is within {@code epsilon} of {@code o}'s. */
    public boolean approxEquals(Vec3f o, float epsilon) {
        return Math.abs(x - o.x) <= epsilon
                && Math.abs(y - o.y) <= epsilon
                && Math.abs(z - o.z) <= epsilon;
    }
}
```

- `lerp` is the first form of §3.5, per component.
- `min` and `max` are per component, which is what a bounding box needs: the union of two boxes is the
  `min` of their minimum corners and the `max` of their maximum corners (`Aabb3f.union`, §25).
- `flat` drops the height, for ground-plane logic (§2.4).
- `approxEquals` compares with a tolerance, because `float` results are rarely exact (§29). It is what
  every test uses instead of `equals`.

**What the record gives you for free, and one surprise.** The generated `equals` compares components
the way `Float.compare` does, not with `==`. The two differ in two places: `Float.compare` treats NaN
as equal to NaN, and it treats $-0.0$ and $0.0$ as **different**. So `new Vec3f(-0.0f, 6, 0)` is not
`equals` to `new Vec3f(0, 6, 0)`, though `==` on the components would say they match. The cross product
of §6.4 produced exactly such a $-0.0$. The test `valueRecords` has a comment about this for `Vec2f`
(`perpendicular` of $(1, 0)$ is $(-0.0, 1)$) and compares the components one at a time with
`assertEquals(0f, perp.x(), 0f)`, which passes because JUnit accepts two floats whose difference is at
most the delta, and $|0 - (-0)| = 0 \le 0$. One more reason to compare with `approxEquals`.

## 16. Step 3: Vec2f and Vec4f

`Vec2f` is the 2D version, used for the ground plane and for screen positions. Most of it repeats
`Vec3f` with one component fewer; two methods are new:

```java
    /** Returns the z component of the 3D cross product (twice the signed triangle area). */
    public float cross(Vec2f o) {
        return x * o.y - y * o.x;
    }

    /** Returns this vector rotated 90° counter-clockwise. */
    public Vec2f perpendicular() {
        return new Vec2f(-y, x);
    }
```

- `cross` is the 2D cross product of §6.4: positive when `o` is counter-clockwise from `this`.
- `perpendicular` is the 90° rotation matrix of §7.3 in 2D: the X axis $(1, 0)$ goes to $(0, 1)$ and
  the Y axis $(0, 1)$ goes to $(-1, 0)$, so the matrix has columns $(0, 1)$ and $(-1, 0)$, and $(x, y)
  \mapsto (-y, x)$.

`Vec4f` holds homogeneous points (§8.2), planes and colours. Its additions over `Vec3f`:

```java
    /** Creates {@code (v, w)}. */
    public Vec4f(Vec3f v, float w) {
        this(v.x(), v.y(), v.z(), w);
    }

    /** Returns {@code (x, y, z)}. */
    public Vec3f xyz() {
        return new Vec3f(x, y, z);
    }

    /** Returns {@code (x, y, z) / w}. */
    public Vec3f perspectiveDivide() {
        return new Vec3f(x / w, y / w, z / w);
    }
```

`new Vec4f(p, 1)` makes a point and `new Vec4f(d, 0)` a direction (§8.3). `perspectiveDivide` is the
division by $w$ of §8.9, which `Mat4f.project` uses (§21).

## 17. Step 4: MVec3f, the scratch vector

`MVec3f` is `Vec3f` turned inside out: public mutable fields, and methods that change `this`.

```java
public final class MVec3f {
    /** X component. */
    public float x;

    /** Y component (up). */
    public float y;

    /** Z component. */
    public float z;

    /** Creates (0, 0, 0). */
    public MVec3f() {}

    /** Creates {@code (x, y, z)}. */
    public MVec3f(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** Sets the components. */
    public MVec3f set(float nx, float ny, float nz) {
        x = nx;
        y = ny;
        z = nz;
        return this;
    }
```

- **Public fields, no getters.** Scratch objects are owned by one system on one thread ("single owner
  thread" in the Javadoc); encapsulation would buy nothing and cost a method call in the interpreter.
- **`final` class.** No subclass can override a method, so every call can be inlined (DESIGN §4.6).
- **`set` returns `this`**, and so does every other mutator. That allows chaining:
  `tmp.set(target).sub(eye).normalize()` reads like the record version but allocates nothing.

Every operation is written in terms of `set`:

```java
    /** Adds {@code o * s}. */
    public MVec3f addScaled(MVec3f o, float s) {
        return set(x + o.x * s, y + o.y * s, z + o.z * s);
    }

    /** Replaces this with {@code this × o}. */
    public MVec3f cross(MVec3f o) {
        return set(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
    }
```

`cross` is the subtle one. It overwrites `x`, `y` and `z` with values computed *from* `x`, `y` and `z`.
That is safe only because Java evaluates all three arguments before `set` runs: by the time `x = nx`
executes, the old `x` has already been used. Writing the same thing as three assignments
(`x = y * o.z - z * o.y; y = z * o.x - x * o.z; …`) would be a bug: the second line would read the new
`x`. It even works when `o` is `this`: `v.cross(v)` correctly gives zero.

```java
    /** Scales to unit length (zero stays zero). */
    public MVec3f normalize() {
        float l2 = lengthSquared();
        return l2 > 0 ? mul((float) (1 / Math.sqrt(l2))) : this;
    }
```

Here the reciprocal $1/\sqrt{l^2}$ is computed once (one division, in `double`) and the vector is
multiplied by it: three multiplications instead of the three divisions of `Vec3f.normalize`. On
current CPUs a division takes several times longer than a multiplication; the two results can differ in
the last bit.

```java
    /** Transforms this point by a column-major 4×4 matrix (no perspective divide). */
    public MVec3f transformPoint(float[] m, int o) {
        return set(
                m[o] * x + m[o + 4] * y + m[o + 8] * z + m[o + 12],
                m[o + 1] * x + m[o + 5] * y + m[o + 9] * z + m[o + 13],
                m[o + 2] * x + m[o + 6] * y + m[o + 10] * z + m[o + 14]);
    }

    /** Returns an immutable copy (allocates). */
    public Vec3f toVec3f() {
        return new Vec3f(x, y, z);
    }
```

`transformPoint` reads a column-major 4×4 (§12.1) straight from a `float[]`: `m[o]`, `m[o + 4]`,
`m[o + 8]` are the first entries of columns 0, 1, 2, and `m[o + 12]` is the translation x. It is §8.3's
point transform. `toVec3f` and `toString` allocate; their Javadoc says so, and they are for the edges
of a system (results handed to an API, debug output), not for its inner loop. `MQuatf` and `MMat4f`
(§23) follow the same pattern.

## 18. Step 5: MathF, the scalar helpers

`MathF` collects the single-number operations of §3.5, §3.6 and §4 in `float`. Java's `Math` works in
`double`; every helper here takes and returns `float` so callers need no casts.

```java
    /** π. */
    public static final float PI = (float) Math.PI;

    /** 2π. */
    public static final float TAU = (float) (2 * Math.PI);

    /** π / 2. */
    public static final float HALF_PI = (float) (Math.PI / 2);
```

The constants are computed in `double` and rounded once to `float`. (For these three it makes no
difference: multiplying or dividing by 2 only changes a float's exponent, so it is exact, and `2 * PI`
computed from the `float` `PI` gives the same bits as `TAU`. Rounding once is the habit that is safe
for every constant.)

```java
    /** Clamps {@code v} to {@code [min, max]}. */
    public static float clamp(float v, float min, float max) {
        return v < min ? min : v > max ? max : v;
    }

    /** Clamps {@code v} to {@code [0, 1]}. */
    public static float saturate(float v) {
        return clamp(v, 0, 1);
    }

    /** Returns {@code a + (b - a) * t}. */
    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** Returns where {@code v} lies between {@code a} and {@code b} (0 at a, 1 at b). */
    public static float inverseLerp(float a, float b, float v) {
        return a == b ? 0 : (v - a) / (b - a);
    }

    /** Maps {@code v} from {@code [inMin, inMax]} to {@code [outMin, outMax]}. */
    public static float remap(float v, float inMin, float inMax, float outMin, float outMax) {
        return lerp(outMin, outMax, inverseLerp(inMin, inMax, v));
    }

    /** Hermite smoothstep between edges {@code a} and {@code b}. */
    public static float smoothstep(float a, float b, float v) {
        float t = saturate(inverseLerp(a, b, v));
        return t * t * (3 - 2 * t);
    }
```

- `clamp` is a chain of two conditional expressions; there is an `int` overload with the same body.
  `saturate` is the name shaders use for clamping to $[0, 1]$.
- `inverseLerp` returns 0 when the range is empty instead of dividing by zero (§3.5).
- `smoothstep` is §3.5's $t^2(3 - 2t)$ on the clamped inverse lerp. "Hermite" names the family of
  cubic curves it belongs to (curves fixed by their end values and end slopes). GLSL has the same
  built-in.

```java
    /**
     * Frame-rate independent exponential approach: moves {@code current} towards {@code target}
     * with decay rate {@code lambda} (per second) over {@code dt} seconds.
     */
    public static float damp(float current, float target, float lambda, float dt) {
        return lerp(target, current, (float) Math.exp(-lambda * dt));
    }

    /** Moves {@code current} towards {@code target} by at most {@code maxDelta}. */
    public static float moveTowards(float current, float target, float maxDelta) {
        float d = target - current;
        return Math.abs(d) <= maxDelta ? target : current + Math.signum(d) * maxDelta;
    }
```

- `damp` is §3.6's formula. Note the argument order: `lerp(target, current, f)` starts at the
  **target** and goes the fraction $`f = e^{-\lambda\,\Delta t}`$ of the way back towards `current`, which
  is "the target plus the remaining gap".
- `moveTowards` is the linear alternative: a fixed speed, never overshooting. `Math.signum` is $+1$ or
  $-1$ (or 0). `moveTowards(0, 10, 3)` is 3 and `moveTowards(9, 10, 3)` is 10 (the test
  `scalarHelpers`).

```java
    /** Wraps an angle to {@code (-π, π]}. */
    public static float wrapAngle(float radians) {
        float a = (float) Math.IEEEremainder(radians, TAU);
        return a <= -PI ? a + TAU : a;
    }

    /** Interpolates angles along the shortest arc. */
    public static float lerpAngle(float a, float b, float t) {
        return a + wrapAngle(b - a) * t;
    }
```

- `Math.IEEEremainder(r, TAU)` subtracts the multiple of $2\pi$ **nearest** to $r$ (unlike `%`, which
  truncates towards zero, L00 §2.4), so the result is in $[-\pi, \pi]$. The second line moves the one
  ambiguous end, $-\pi$, to $+\pi$, giving the interval $(-\pi, \pi]$. `wrapAngle(PI + 0.5f)` is $-\pi +
  0.5$.
- `lerpAngle` interpolates the **wrapped difference**: from 170° to −170° the raw difference is −340°,
  wrapped it is +20°, so the angle goes through 180° (§4.3).

```java
    /** Floor that is faster than {@link Math#floor(double)} for floats in int range. */
    public static int floorToInt(float v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    /** Returns the smallest power of two ≥ {@code v} (v ≥ 1). */
    public static int nextPowerOfTwo(int v) {
        return v <= 1 ? 1 : Integer.highestOneBit(v - 1) << 1;
    }
```

- Casting a `float` to `int` drops the fraction, which rounds towards zero (that is Java's rule for the
  narrowing conversion): `(int) -1.5f` is −1, but the floor (L00 §2.5) is −2. When
  `v` was negative with a fraction, `v < i` is true and the line subtracts 1. `floorToInt(-1.5f)` is
  −2 (the test). It avoids `Math.floor`'s `double` round trip, and is correct only while `v` fits in an
  `int`, as the Javadoc says.
- `Integer.highestOneBit(x)` keeps only the top 1 bit of `x` (L00 §6). For $v = 33$: $v - 1 = 32$ =
  `100000`, highest bit 32, shifted left once: 64. For $v = 32$: $31$ = `11111`, highest bit 16, times
  2: 32. Subtracting 1 first is what makes an exact power of two map to itself.

The rest — `toRadians`, `toDegrees`, `approx` (an absolute-tolerance comparison of two floats) — are
one line each.

## 19. Step 6: Vec3Ops, loops over arrays

`Vec3Ops` is layer 3 for vectors: each method is one formula of Part A wrapped in a loop over
struct-of-arrays data. You saw `addScaled` in §14.5. The others:

```java
    /** {@code out[i] = |p[i] - (cx, cy, cz)|²} for {@code [start, end)}. */
    public static void distanceSquared(
            float[] px, float[] py, float[] pz,          // (parameter list condensed)
            float cx, float cy, float cz,
            float[] out, int start, int end) {
        for (int i = start; i < end; i++) {
            float dx = px[i] - cx;
            float dy = py[i] - cy;
            float dz = pz[i] - cz;
            out[i] = dx * dx + dy * dy + dz * dz;
        }
    }

    /** Normalizes vectors {@code [start, end)} in place; zero vectors stay zero. */
    public static void normalize(float[] x, float[] y, float[] z, int start, int end) {
        for (int i = start; i < end; i++) {
            float l2 = x[i] * x[i] + y[i] * y[i] + z[i] * z[i];
            float inv = l2 > 0 ? (float) (1 / Math.sqrt(l2)) : 0;
            x[i] *= inv;
            y[i] *= inv;
            z[i] *= inv;
        }
    }
```

- `distanceSquared` measures every point against one centre (§3.4): "which units are within the
  fireball's radius" is this loop followed by a comparison with $r^2$.
- `normalize` uses the reciprocal trick of §17. For a zero vector it multiplies by 0 instead of
  branching around the multiplications, so the loop body is the same for every element — friendlier to
  SIMD.

`dot` and `lerp` are §5.1 and §3.5 per element. `bounds` computes the axis-aligned box of a set of
points:

```java
    public static void bounds(float[] x, float[] y, float[] z, int start, int end, float[] out) {
        float minX = Float.POSITIVE_INFINITY,
                minY = Float.POSITIVE_INFINITY,
                minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY,
                maxY = Float.NEGATIVE_INFINITY,
                maxZ = Float.NEGATIVE_INFINITY;
        for (int i = start; i < end; i++) {
            minX = Math.min(minX, x[i]);
            // ... the other five the same way
        }
        out[0] = minX;
        // ... out[1..5]
    }
```

Starting the minimum at $+\infty$ and the maximum at $-\infty$ means the first point replaces both, with
no special case for "the first element". An empty range leaves min $> $ max, an **inverted** box, which
`Aabb3f.isValid` reports as not valid and which `Aabb3f.union` treats as empty (`Aabb3f.EMPTY` is built
the same way, §25). The outputs are six floats rather than an `Aabb3f`, so nothing is allocated.

Today no engine system calls `Vec3Ops`, and it has no test of its own. The systems that move many
entities write the same formulas inline next to their own data — the crowd integrates its agents with
`px[i] += vx[i] * step` inside a longer loop in `Crowd`, where one pass over the arrays does several
steps at once. `Vec3Ops` stays in the module as the layer-3 reference for vectors.

## 20. Step 7: Mat3f

`Mat3f` is a 3×3 matrix record, used for rotations and normal matrices (§11). Its nine components are
named **column first**, `mCR` (§12.5), and declared column by column:

```java
public record Mat3f(
        float m00,
        float m01,
        float m02,
        float m10,
        float m11,
        float m12,
        float m20,
        float m21,
        float m22) {

    /** The identity matrix. */
    public static final Mat3f IDENTITY = new Mat3f(1, 0, 0, 0, 1, 0, 0, 0, 1);
```

So `m00, m01, m02` is column 0 (the image of X, §7.2), and `m10, m20` — not `m01, m02` — continue row 0.
Getting from a 4×4 is a matter of taking its upper-left 3×3, the linear part:

```java
    /** Returns the rotation matrix of a unit quaternion. */
    public static Mat3f rotation(Quatf q) {
        Mat4f r = Mat4f.rotation(q);
        return new Mat3f(
                r.m00(), r.m01(), r.m02(), r.m10(), r.m11(), r.m12(), r.m20(), r.m21(), r.m22());
    }

    /** Returns the upper-left 3×3 of {@code m}. */
    public static Mat3f of(Mat4f m) {
        return new Mat3f(
                m.m00(), m.m01(), m.m02(), m.m10(), m.m11(), m.m12(), m.m20(), m.m21(), m.m22());
    }
```

`rotation` reuses `Mat4f`'s quaternion formula rather than repeat it; it allocates a temporary `Mat4f`,
which is fine in layer 1.

**Product and transform** (§7.6, §7.1):

```java
    /** Returns {@code this * o}. */
    public Mat3f mul(Mat3f o) {
        return new Mat3f(
                m00 * o.m00 + m10 * o.m01 + m20 * o.m02,
                m01 * o.m00 + m11 * o.m01 + m21 * o.m02,
                m02 * o.m00 + m12 * o.m01 + m22 * o.m02,
                // ... (condensed) columns 1 and 2: the same three lines with o.m10, o.m11, o.m12
                // and with o.m20, o.m21, o.m22; the last line is
                m02 * o.m20 + m12 * o.m21 + m22 * o.m22);
    }

    /** Returns {@code this * v}. */
    public Vec3f transform(Vec3f v) {
        return new Vec3f(
                m00 * v.x() + m10 * v.y() + m20 * v.z(),
                m01 * v.x() + m11 * v.y() + m21 * v.z(),
                m02 * v.x() + m12 * v.y() + m22 * v.z());
    }
```

The first output, column 0 row 0 of the product, is row 0 of `this` — `m00, m10, m20` — dotted with
column 0 of `o` — `o.m00, o.m01, o.m02`. That is $`(AB)_{00} = \sum_k A_{0k}B_{k0}`$ with the names
translated. The result's components come out in constructor order, which is column order, so the first
three lines are the result's column 0: `this` applied to `o`'s column 0 (§7.6). `transform` is the column
form of §7.4.

**Transpose, determinant, inverse** (§7.8, §9.2, §10.4):

```java
    /** Returns the transpose. */
    public Mat3f transpose() {
        return new Mat3f(m00, m10, m20, m01, m11, m21, m02, m12, m22);
    }

    /** Returns the determinant. */
    public float determinant() {
        return m00 * (m11 * m22 - m21 * m12)
                - m10 * (m01 * m22 - m21 * m02)
                + m20 * (m01 * m12 - m11 * m02);
    }

    /** Returns the inverse. Throws {@link ArithmeticException} if singular. */
    public Mat3f invert() {
        float det = determinant();
        if (det == 0 || !Float.isFinite(det)) {
            throw new ArithmeticException("singular matrix");
        }
        float inv = 1 / det;
        return new Mat3f(
                (m11 * m22 - m21 * m12) * inv,
                (m21 * m02 - m01 * m22) * inv,
                (m01 * m12 - m11 * m02) * inv,
                (m20 * m12 - m10 * m22) * inv,
                (m00 * m22 - m20 * m02) * inv,
                (m10 * m02 - m00 * m12) * inv,
                (m10 * m21 - m20 * m11) * inv,
                (m20 * m01 - m00 * m21) * inv,
                (m00 * m11 - m10 * m01) * inv);
    }
```

- `transpose` passes the rows as the new columns: the first three arguments, the new column 0, are the
  old row 0 (`m00, m10, m20`).
- `determinant` is the first-row cofactor expansion of §9.2: `m00`, `m10`, `m20` are row 0, and each
  multiplies the 2×2 determinant of the entries outside its row and column, with signs $+, -, +$.
- `invert` computes one reciprocal and nine multiplications by it (§17's trick). Its nine entries are
  the adjugate of §10.4 — the cofactors, transposed — in column order. Check the second one: the
  inverse's column 0, row 1 is cofactor $`C_{01}`$ divided by the determinant (the adjugate is the
  cofactor matrix transposed). $`C_{01}`$ is $`-(A_{10}A_{22} - A_{12}A_{20})`$, and in `mCR` names
  $`A_{10}`$ = `m01`, $`A_{22}`$ = `m22`, $`A_{12}`$ = `m21`, $`A_{20}`$ = `m02`: `m21 * m02 - m01 * m22`. ✓

```java
    public static Mat3f normalMatrix(Mat4f model) {
        return of(model).invert().transpose();
    }
```

`normalMatrix` is §11.1's $M^{-T}$, written literally. The GPU uses the cofactor trick instead (§27);
this is the readable reference that the test compares against.

## 21. Step 8: Mat4f

`Mat4f` is the 4×4 record. Its Javadoc states the conventions in one paragraph:

```java
/**
 * Immutable 4×4 matrix, column-major like OpenGL (value type, DESIGN §8.1/§8.2). Component {@code
 * mCR} is column {@code C}, row {@code R}: {@code m30, m31, m32} hold the translation. Vectors are
 * columns; {@code a.mul(b)} applies {@code b} first.
 *
 * <p>Projections are reversed-Z with depth in {@code [0, 1]} (near maps to 1), for use with {@code
 * glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)} and a {@code GL_GEQUAL} depth test.
 *
 * <p>Thread ownership: immutable.
 */
public record Mat4f(
        float m00,
        float m01,
        // ... sixteen components, column by column (condensed)
        float m33) {

    /** The identity matrix. */
    public static final Mat4f IDENTITY = new Mat4f(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1);
```

**To and from arrays.** The record has to meet the layer-3 kernels, which work on `float[]`:

```java
    /** Reads 16 column-major floats starting at {@code offset}. */
    public static Mat4f fromArray(float[] a, int offset) {
        return new Mat4f(
                a[offset],
                a[offset + 1],
                // ... a[offset + 2] to a[offset + 14] (condensed)
                a[offset + 15]);
    }

    /** Writes 16 column-major floats starting at {@code offset}. */
    public void toArray(float[] a, int offset) {
        a[offset] = m00;
        a[offset + 1] = m01;
        // ... (condensed)
        a[offset + 15] = m33;
    }

    /** Returns the element at {@code column}, {@code row}. */
    public float get(int column, int row) {
        float[] a = new float[16];
        toArray(a, 0);
        return a[column * 4 + row];
    }
```

Because the components are declared in column order, both conversions are a straight copy: component
$k$ goes to index $k$. `get` turns a (column, row) pair into a component by writing all 16 into a fresh
array and indexing it with `column * 4 + row` (§12.1). It allocates 80 bytes to read one float; that is
acceptable only because `Mat4f` is layer 1, for code that is not per frame.

**Builders.** Translation (§8.2) and scale (§7.3):

```java
    /** Returns a translation matrix. */
    public static Mat4f translation(float x, float y, float z) {
        return new Mat4f(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, x, y, z, 1);
    }

    /** Returns a scaling matrix. */
    public static Mat4f scaling(float x, float y, float z) {
        return new Mat4f(x, 0, 0, 0, 0, y, 0, 0, 0, 0, z, 0, 0, 0, 0, 1);
    }

    /** Returns the rotation matrix of a unit quaternion. */
    public static Mat4f rotation(Quatf q) {
        return trs(Vec3f.ZERO, q, Vec3f.ONE);
    }
```

Read each argument list in groups of four: column 0, column 1, column 2, column 3. In `translation`,
the last group is `x, y, z, 1`. In `scaling`, each column has its scale on the diagonal.

**TRS** (§8.6):

```java
    /** Returns translation * rotation * scale. */
    public static Mat4f trs(Vec3f t, Quatf q, Vec3f s) {
        float x = q.x();
        float y = q.y();
        float z = q.z();
        float w = q.w();
        float xx = x * x;
        float yy = y * y;
        float zz = z * z;
        float xy = x * y;
        float xz = x * z;
        float yz = y * z;
        float wx = w * x;
        float wy = w * y;
        float wz = w * z;
        return new Mat4f( // condensed: one column per line (the source has one value per line)
                (1 - 2 * (yy + zz)) * s.x(), 2 * (xy + wz) * s.x(), 2 * (xz - wy) * s.x(), 0,
                2 * (xy - wz) * s.y(), (1 - 2 * (xx + zz)) * s.y(), 2 * (yz + wx) * s.y(), 0,
                2 * (xz + wy) * s.z(), 2 * (yz - wx) * s.z(), (1 - 2 * (xx + yy)) * s.z(), 0,
                t.x(), t.y(), t.z(), 1);
    }
```

The expressions in `xx`, `xy`, `wz` and so on are the rotation matrix of a quaternion $(x, y, z, w)$;
chapter [03](03-rotations-and-transforms.md) derives them. The products are computed once into locals
because each appears in two entries. Without the scale factors, the four groups of four would be the
rotation's three columns and the translation. With them you can see §8.6's column scaling directly:
every entry of column 0 is multiplied by `s.x()`, of column 1 by `s.y()`, of column 2 by `s.z()`. And
the last group is the translation, untouched — $T$ is applied last, so it is not scaled or turned.

**Projections.** `perspectiveReversedZ` and `orthoReversedZ` build the two camera projections; they
change the bottom row (§8.9) and are derived in chapter [04](04-cameras-and-projection.md).

**Look-at** (§6.5, §10.3):

```java
    /** Returns a right-handed view matrix looking from {@code eye} at {@code target}. */
    public static Mat4f lookAt(Vec3f eye, Vec3f target, Vec3f up) {
        Vec3f f = target.sub(eye).normalize();
        Vec3f r = f.cross(up).normalize();
        Vec3f u = r.cross(f);
        return new Mat4f( // condensed: one column per line (the source has one value per line)
                r.x(), u.x(), -f.x(), 0,
                r.y(), u.y(), -f.y(), 0,
                r.z(), u.z(), -f.z(), 0,
                -r.dot(eye), -u.dot(eye), f.dot(eye), 1);
    }
```

The three axes come from §6.5's two cross products. Then each group of four is a **column**, and the
first column is `r.x(), u.x(), -f.x()`: the x components of the three axes. So the axes end up in the
**rows** — right in row 0, up in row 1, $-$forward in row 2 — which is the world-to-camera matrix of
§10.3, and the last column is $`(-\mathbf{r}\cdot\mathbf{e},\; -\mathbf{u}\cdot\mathbf{e},\; \mathbf{f}\cdot\mathbf{e})`$.

**Products and transforms:**

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

The record does not repeat the 4×4 product; it copies both matrices into arrays, calls the kernel
(§22), and reads the result back. One implementation of the formula is easier to keep right than two,
and §14.3 measured what the copies cost (312 bytes per call before escape analysis, 160 after). The
same pattern serves `determinant` and `invert`:

```java
    /** Returns the inverse. Throws {@link ArithmeticException} if the matrix is singular. */
    public Mat4f invert() {
        float[] a = new float[16];
        float[] r = new float[16];
        toArray(a, 0);
        if (!Mat4Ops.invert(a, 0, r, 0)) {
            throw new ArithmeticException("singular matrix");
        }
        return fromArray(r, 0);
    }
```

The kernel reports a singular matrix with `false`; the record turns that into an exception, because
layer-1 callers are setup code where an exception is the clearer failure (§10.6).

`transformPoint` and `transformDirection` were shown in §8.3 and §7.4. Two more:

```java
    /** Transforms a homogeneous vector. */
    public Vec4f transform(Vec4f v) {
        return new Vec4f(
                m00 * v.x() + m10 * v.y() + m20 * v.z() + m30 * v.w(),
                m01 * v.x() + m11 * v.y() + m21 * v.z() + m31 * v.w(),
                m02 * v.x() + m12 * v.y() + m22 * v.z() + m32 * v.w(),
                m03 * v.x() + m13 * v.y() + m23 * v.z() + m33 * v.w());
    }

    /** Transforms a point and divides by w. */
    public Vec3f project(Vec3f p) {
        return transform(new Vec4f(p, 1)).perspectiveDivide();
    }
```

`transform` is the full 4×4 product with all four rows, including the bottom one that affine
transforms leave at $(0, 0, 0, 1)$. `project` is "make it a point, transform, divide by $w$" (§8.9); the
projection tests of chapter 04 use it. The remaining methods — `transpose`, `translation()` (the
translation column as a `Vec3f`) and `approxEquals` (16 comparisons through two temporary arrays) — need
no new ideas.

## 22. Step 9: Mat4Ops, the 4×4 kernels

`Mat4Ops` is where the 4×4 arithmetic actually lives: static methods over a `float[]` and an offset,
column-major (`[c * 4 + r]`), allocating nothing. Its Javadoc adds one rule: "inputs and output may not
overlap unless stated".

```java
    /** Writes the identity matrix. */
    public static void identity(float[] out, int o) {
        for (int i = 0; i < 16; i++) {
            out[o + i] = (i % 5 == 0) ? 1 : 0;
        }
    }
```

The diagonal entries $(c, c)$ sit at `c * 4 + c = 5c`: indices 0, 5, 10, 15, exactly the multiples of 5
below 16.

**The product** (§7.6):

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

Each pass of the loop produces one **column** of the result. `b0..b3` is column `c` of `b`. The first
output line is "column 0 of `a` (`a[ao..ao+3]`, first entry) times `b0`, plus column 1 of `a`
(`a[ao + 4..]`) times `b1`, …": the column form of §7.4, so output column `c` is $A$ applied to $B$'s
column `c` — "each column of $AB$ is $A$ applied to a column of $B$", in code. Sixteen outputs of four
products: the 64 multiplications of §8.5.

**Why "must not overlap".** If `out` were `a`, the first pass would overwrite column 0 of `a`, which
the second pass still needs. Interestingly, `out == b` would work: each pass copies its column of `b`
into `b0..b3` before writing that same column, and later passes never read it again (exercise 7 makes
you check). The Javadoc forbids both because the rule is simpler to keep than the exception. Callers
that want an in-place product use `MMat4f.mul`, which multiplies into a scratch array and copies back
(§23).

**A 4×4 times a 3×4** (§12.4):

```java
    public static void mulAffine(float[] m, int mo, float[] affine, int ao, float[] out, int o) {
        for (int c = 0; c < 4; c++) {
            float b0 = affine[ao + c];
            float b1 = affine[ao + 4 + c];
            float b2 = affine[ao + 8 + c];
            float b3 = c == 3 ? 1 : 0;
            out[o + c * 4] = m[mo] * b0 + m[mo + 4] * b1 + m[mo + 8] * b2 + m[mo + 12] * b3;
            // ... the other three rows exactly as in mul
        }
    }
```

The same loop as `mul`, except for where column `c` of the second matrix comes from: in the row-major
3×4, column `c` is at `c`, `4 + c`, `8 + c` (§12.2), and its fourth entry is the implied bottom row —
0 for the three linear columns, 1 for the translation column. That is "view-projection times model"
without first expanding the model to 16 floats.

**Transpose and determinant:**

```java
    /** Writes the transpose of {@code m} to {@code out} (may not overlap). */
    public static void transpose(float[] m, int mo, float[] out, int o) {
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) {
                out[o + r * 4 + c] = m[mo + c * 4 + r];
            }
        }
    }
```

Element (row r, column c) is read from `c * 4 + r` and written to where element (row c, column r)
lives, `r * 4 + c`. `determinant` loads the 16 entries into locals `a00 … a33` (`aCR` again: column,
row), computes `b00 … b11` and combines them exactly as §10.5 did.

**The inverse** (§10.5), in full:

```java
    public static boolean invert(float[] m, int o, float[] out, int oo) {
        float a00 = m[o], a01 = m[o + 1], a02 = m[o + 2], a03 = m[o + 3];
        float a10 = m[o + 4], a11 = m[o + 5], a12 = m[o + 6], a13 = m[o + 7];
        float a20 = m[o + 8], a21 = m[o + 9], a22 = m[o + 10], a23 = m[o + 11];
        float a30 = m[o + 12], a31 = m[o + 13], a32 = m[o + 14], a33 = m[o + 15];
        float b00 = a00 * a11 - a01 * a10;
        float b01 = a00 * a12 - a02 * a10;
        float b02 = a00 * a13 - a03 * a10;
        float b03 = a01 * a12 - a02 * a11;
        float b04 = a01 * a13 - a03 * a11;
        float b05 = a02 * a13 - a03 * a12;
        float b06 = a20 * a31 - a21 * a30;
        float b07 = a20 * a32 - a22 * a30;
        float b08 = a20 * a33 - a23 * a30;
        float b09 = a21 * a32 - a22 * a31;
        float b10 = a21 * a33 - a23 * a31;
        float b11 = a22 * a33 - a23 * a32;
        float det = b00 * b11 - b01 * b10 + b02 * b09 + b03 * b08 - b04 * b07 + b05 * b06;
        if (det == 0 || !Float.isFinite(det)) {
            return false;
        }
        float inv = 1 / det;
        out[oo] = (a11 * b11 - a12 * b10 + a13 * b09) * inv;
        out[oo + 1] = (a02 * b10 - a01 * b11 - a03 * b09) * inv;
        out[oo + 2] = (a31 * b05 - a32 * b04 + a33 * b03) * inv;
        out[oo + 3] = (a22 * b04 - a21 * b05 - a23 * b03) * inv;
        out[oo + 4] = (a12 * b08 - a10 * b11 - a13 * b07) * inv;
        out[oo + 5] = (a00 * b11 - a02 * b08 + a03 * b07) * inv;
        out[oo + 6] = (a32 * b02 - a30 * b05 - a33 * b01) * inv;
        out[oo + 7] = (a20 * b05 - a22 * b02 + a23 * b01) * inv;
        out[oo + 8] = (a10 * b10 - a11 * b08 + a13 * b06) * inv;
        out[oo + 9] = (a01 * b08 - a00 * b10 - a03 * b06) * inv;
        out[oo + 10] = (a30 * b04 - a31 * b02 + a33 * b00) * inv;
        out[oo + 11] = (a21 * b02 - a20 * b04 - a23 * b00) * inv;
        out[oo + 12] = (a11 * b07 - a10 * b09 - a12 * b06) * inv;
        out[oo + 13] = (a00 * b09 - a01 * b07 + a02 * b06) * inv;
        out[oo + 14] = (a31 * b01 - a30 * b03 - a32 * b00) * inv;
        out[oo + 15] = (a20 * b03 - a21 * b01 + a22 * b00) * inv;
        return true;
    }
```

- **All 16 inputs are read into locals first.** That is why this method, unlike `mul`, "may overlap":
  writing `out` cannot disturb inputs that have already been read. `MMat4f.invert` relies on it to
  invert in place (§23).
- **The `b`s are each used several times**: twelve 2×2 determinants serve the determinant and all
  sixteen 3×3 cofactors.
- **On a singular matrix it returns `false` before writing anything**, so `out` keeps its old contents,
  as the Javadoc promises.
- **The second line** is the cofactor $`C_{01}`$ (row 0, column 1 of $M$) divided by $\det$, which lands
  in the inverse's (row 1, column 0) — index 1. Removing row 0 and column 1 leaves columns 0, 2, 3;
  expanding along column 0 gives `a01 * b11 - a02 * b10 + a03 * b09`, and the cofactor sign of $(0, 1)$
  is negative: `a02 * b10 - a01 * b11 - a03 * b09`.

**Projecting a point** (§8.9):

```java
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

All four rows, then one reciprocal and three multiplications. `IsoCamera.screenRay` calls it with the
inverse view-projection to turn a mouse position back into a world point (chapter 04).

`perspectiveReversedZ` and `orthoReversedZ` write the projection matrices of chapter 04 into an array.
`lookAt` is `Mat4f.lookAt` with primitives: it normalizes with a reciprocal square root, writes the
cross products of §6.5 out component by component (`rx = fy * uz - fz * uy` is the x of $`\mathbf{f} \times \mathbf{u}_0`$), and ends with the lines quoted in §10.3.

## 23. Step 10: MMat4f, the scratch matrix

`MMat4f` is layer 2 for matrices: a column-major `float[16]` you can hand straight to a kernel or a
uniform upload.

```java
public final class MMat4f {
    /** Column-major elements. */
    public final float[] m = new float[16];

    private final float[] tmp = new float[16];

    /** Creates an identity matrix. */
    public MMat4f() {
        Mat4Ops.identity(m, 0);
    }
```

- `m` is `public final`: the reference never changes (so it can be cached by a caller, or wrapped once
  in a `MemorySegment` for an upload), but its contents do.
- `tmp` is a second array allocated once with the object. It exists for exactly one purpose:

```java
    /** Sets {@code this = this * o}. */
    public MMat4f mul(MMat4f o) {
        Mat4Ops.mul(m, 0, o.m, 0, tmp, 0);
        System.arraycopy(tmp, 0, m, 0, 16);
        return this;
    }

    /** Sets {@code this = a * b}. {@code this} may be {@code a} or {@code b}. */
    public MMat4f mul(MMat4f a, MMat4f b) {
        Mat4Ops.mul(a.m, 0, b.m, 0, tmp, 0);
        System.arraycopy(tmp, 0, m, 0, 16);
        return this;
    }
```

`Mat4Ops.mul` must not write over its inputs (§22), so the product goes into `tmp` and is then copied
over `m`. That makes "`this` may be `a` or `b`" true, at the price of a 64-byte copy, and still no
allocation. `transpose` uses `tmp` the same way. `invert` does not need it:

```java
    /** Inverts in place. Throws {@link ArithmeticException} if singular. */
    public MMat4f invert() {
        if (!Mat4Ops.invert(m, 0, m, 0)) {
            throw new ArithmeticException("singular matrix");
        }
        return this;
    }
```

— `Mat4Ops.invert` reads everything before writing (§22). The rest of the class wraps the kernels
(`perspectiveReversedZ`, `orthoReversedZ`, `lookAt` taking `MVec3f`s) and copies in and out (`set`,
`get`, and `toMat4f`, which allocates). The test `mat3NormalMatrixAndMutableTypes` builds a translation,
inverts a copy, multiplies the two with `mul(a, b)` and checks the identity.

## 24. Step 11: AffineOps, the 3×4 kernels

`AffineOps` is the workhorse of the engine's transforms: row-major 3×4 blocks of 12 floats (§12.2).

```java
public final class AffineOps {
    /** Floats per transform. */
    public static final int STRIDE = 12;
```

`identity` writes `1, 0, 0, 0,  0, 1, 0, 0,  0, 0, 1, 0`: three rows, each with a zero translation.

### 24.1 composeTRS

The scalar version builds one transform from a position, a quaternion and a scale (§8.6):

```java
    /** Writes translation * rotation(quaternion) * scale. */
    public static void composeTRS(
            float px, float py, float pz,                // (parameter list condensed)
            float qx, float qy, float qz, float qw,
            float sx, float sy, float sz,
            float[] out, int o) {
        float xx = qx * qx;
        float yy = qy * qy;
        float zz = qz * qz;
        float xy = qx * qy;
        float xz = qx * qz;
        float yz = qy * qz;
        float wx = qw * qx;
        float wy = qw * qy;
        float wz = qw * qz;
        out[o] = (1 - 2 * (yy + zz)) * sx;
        out[o + 1] = 2 * (xy - wz) * sy;
        out[o + 2] = 2 * (xz + wy) * sz;
        out[o + 3] = px;
        out[o + 4] = 2 * (xy + wz) * sx;
        out[o + 5] = (1 - 2 * (xx + zz)) * sy;
        out[o + 6] = 2 * (yz - wx) * sz;
        out[o + 7] = py;
        out[o + 8] = 2 * (xz - wy) * sx;
        out[o + 9] = 2 * (yz + wx) * sy;
        out[o + 10] = (1 - 2 * (xx + yy)) * sz;
        out[o + 11] = pz;
    }
```

Compare with `Mat4f.trs` (§21): the same nine products and the same nine expressions, written in the
other storage order. Here `out[o + 1]` is row 0, column 1, `2 * (xy - wz) * sy`, which in `Mat4f.trs`
is component `m10` (column 1, row 0) — the same entry. The scale pattern of §8.6 shows up as
**columns of the layout**: `* sx` on indices 0, 4, 8 (column 0), `* sy` on 1, 5, 9, `* sz` on 2, 6, 10.
The translation is copied into 3, 7, 11. Ten plain floats in, twelve out: no record, no allocation.

The batch version is a loop over struct-of-arrays inputs:

```java
    // (condensed: the source has one parameter and one argument per line)
    public static void composeTRS(
            float[] px, float[] py, float[] pz,
            float[] qx, float[] qy, float[] qz, float[] qw,
            float[] sx, float[] sy, float[] sz,
            int start, int end, float[] out) {
        for (int i = start; i < end; i++) {
            composeTRS(px[i], py[i], pz[i], qx[i], qy[i], qz[i], qw[i], sx[i], sy[i], sz[i],
                    out, i * STRIDE);
        }
    }
```

Transform `i` lands at `out[i * 12]`. The call to the scalar version costs nothing once compiled: its
bytecode is 246 bytes, under C2's limit for inlining a hot call (`FreqInlineSize`, 325 bytes), so C2
can paste it into the loop. This is the signature DESIGN §8.1 gives as the example of a bulk kernel,
and `TransformStore` (chapter 03) calls the scalar version for every dirty entity.

### 24.2 mul and concatenate

The product of two affine transforms (§8.5):

```java
    /** {@code out = a * b} (apply {@code b} first). {@code out} may overlap neither input. */
    public static void mul(float[] a, int ao, float[] b, int bo, float[] out, int o) {
        for (int r = 0; r < 3; r++) {
            float a0 = a[ao + r * 4];
            float a1 = a[ao + r * 4 + 1];
            float a2 = a[ao + r * 4 + 2];
            float a3 = a[ao + r * 4 + 3];
            out[o + r * 4] = a0 * b[bo] + a1 * b[bo + 4] + a2 * b[bo + 8];
            out[o + r * 4 + 1] = a0 * b[bo + 1] + a1 * b[bo + 5] + a2 * b[bo + 9];
            out[o + r * 4 + 2] = a0 * b[bo + 2] + a1 * b[bo + 6] + a2 * b[bo + 10];
            out[o + r * 4 + 3] = a0 * b[bo + 3] + a1 * b[bo + 7] + a2 * b[bo + 11] + a3;
        }
    }
```

This loop goes by **rows**, the natural order of the storage. `a0..a2` is row `r` of $A$'s linear part
and `a3` its translation. Output entry $(r, c)$ for $c < 3$ is that row dotted with column $c$ of $B$
(`b[bo + c]`, `b[bo + 4 + c]`, `b[bo + 8 + c]`): $`(AB)_{rc}`$. The fourth output is row $r$ of $A$
dotted with $B$'s translation column, plus `a3`: component $r$ of $A\mathbf{b} + \mathbf{a}$. Thirty-six
multiplications (§8.5). (Here the opposite of `Mat4Ops.mul` holds: `out == a` would work, `out == b`
would not — exercise 7.)

The hierarchy pass (§8.7):

```java
    public static void concatenate(int[] parent, float[] local, float[] world, int start, int end) {
        for (int i = start; i < end; i++) {
            int p = parent[i];
            if (p < 0) {
                System.arraycopy(local, i * STRIDE, world, i * STRIDE, STRIDE);
            } else {
                mul(world, p * STRIDE, local, i * STRIDE, world, i * STRIDE);
            }
        }
    }
```

- `parent[i] < 0` marks a root: its world transform is its local one.
- Otherwise $`W_i = W_{p} \cdot L_i`$, with `a` = the parent's world transform and `out` = this item's.
  Both are in the same `world` array, which looks like the overlap `mul` forbids. It is not: they are
  different 12-float ranges, because an item is never its own parent.
- The Javadoc's condition, "parents come before children", is what makes one forward pass enough:
  when item `i` is computed, `world[p]` is already final. Chapter 03's `TransformStore` keeps an `int`
  order array sorted that way (D28) rather than moving the data.

### 24.3 invert

The affine inverse of §10.2, with the 3×3 adjugate of §10.4:

```java
    public static boolean invert(float[] m, int mo, float[] out, int o) {
        float a = m[mo], b = m[mo + 1], c = m[mo + 2];
        float d = m[mo + 4], e = m[mo + 5], f = m[mo + 6];
        float g = m[mo + 8], h = m[mo + 9], k = m[mo + 10];
        float co0 = e * k - f * h;
        float co1 = f * g - d * k;
        float co2 = d * h - e * g;
        float det = a * co0 + b * co1 + c * co2;
        if (det == 0 || !Float.isFinite(det)) {
            return false;
        }
        float inv = 1 / det;
        float i00 = co0 * inv;
        float i01 = (c * h - b * k) * inv;
        float i02 = (b * f - c * e) * inv;
        float i10 = co1 * inv;
        float i11 = (a * k - c * g) * inv;
        float i12 = (c * d - a * f) * inv;
        float i20 = co2 * inv;
        float i21 = (b * g - a * h) * inv;
        float i22 = (a * e - b * d) * inv;
        float tx = m[mo + 3];
        float ty = m[mo + 7];
        float tz = m[mo + 11];
        out[o] = i00;
        out[o + 1] = i01;
        out[o + 2] = i02;
        out[o + 3] = -(i00 * tx + i01 * ty + i02 * tz);
        out[o + 4] = i10;
        out[o + 5] = i11;
        out[o + 6] = i12;
        out[o + 7] = -(i10 * tx + i11 * ty + i12 * tz);
        out[o + 8] = i20;
        out[o + 9] = i21;
        out[o + 10] = i22;
        out[o + 11] = -(i20 * tx + i21 * ty + i22 * tz);
        return true;
    }
```

- The letters `a … k` are the linear part read row by row (`k` rather than `i`, so as not to clash with
  the `iRC` names of the result).
- Rows of the inverse are the three cross products of §10.4 divided by the determinant: row 0 is
  $`\mathbf{c}_1 \times \mathbf{c}_2 / \det`$ = (`co0`, `c * h - b * k`, `b * f - c * e`) × `inv`; row 1
  is $`\mathbf{c}_2 \times \mathbf{c}_0 / \det`$, starting with `co1`; row 2 is $`\mathbf{c}_0 \times \mathbf{c}_1 / \det`$, starting with `co2`.
- Each output row ends with its component of $-A^{-1}\mathbf{t}$: the row of $A^{-1}$ just computed,
  dotted with the old translation, negated.
- §10.4 traced both of its worked examples through these lines.

The Javadoc says `out` "may not overlap" `m`. In fact every input is read into a local before the first
write, so an in-place call would work (exercise 7), but the documented contract is the stricter one.

### 24.4 Transforming points, directions and boxes

```java
    /** Transforms point {@code (x, y, z)} into {@code out[o..o+2]}. */
    public static void transformPoint(
            float[] m, int mo, float x, float y, float z, float[] out, int o) {
        out[o] = m[mo] * x + m[mo + 1] * y + m[mo + 2] * z + m[mo + 3];
        out[o + 1] = m[mo + 4] * x + m[mo + 5] * y + m[mo + 6] * z + m[mo + 7];
        out[o + 2] = m[mo + 8] * x + m[mo + 9] * y + m[mo + 10] * z + m[mo + 11];
    }
```

The row form of §7.4: each output is a row dotted with $(x, y, z, 1)$. `transformDirection` is the same
without `m[mo + 3]`, `m[mo + 7]`, `m[mo + 11]` — $w = 0$ (§8.3). The batch version shows a detail worth
copying:

```java
    /** Transforms struct-of-arrays points {@code [start, end)} by one transform, in place. */
    public static void transformPoints(
            float[] m, int mo, float[] xs, float[] ys, float[] zs, int start, int end) {
        float m00 = m[mo], m01 = m[mo + 1], m02 = m[mo + 2], m03 = m[mo + 3];
        float m10 = m[mo + 4], m11 = m[mo + 5], m12 = m[mo + 6], m13 = m[mo + 7];
        float m20 = m[mo + 8], m21 = m[mo + 9], m22 = m[mo + 10], m23 = m[mo + 11];
        for (int i = start; i < end; i++) {
            float x = xs[i];
            float y = ys[i];
            float z = zs[i];
            xs[i] = m00 * x + m01 * y + m02 * z + m03;
            ys[i] = m10 * x + m11 * y + m12 * z + m13;
            zs[i] = m20 * x + m21 * y + m22 * z + m23;
        }
    }
```

The twelve matrix entries are read **once, before the loop**, into locals the JIT can keep in
registers. Why not just write `m[mo] * x` in the loop and trust the compiler? Because `m` and `xs` are
both `float[]`, and nothing stops a caller from passing the same array for both. The JIT must then
assume that writing `xs[i]` might change `m[mo]`, and reload all twelve entries every iteration. Copying
them to locals first states the intent: the transform is fixed for the whole loop. (The local names here
are row first, `mRC`, unlike `Mat4f`'s components: one more instance of §12.5.)

`transformAabb` is Arvo's method (§8.8):

```java
    public static void transformAabb(
            float[] m, int mo,                           // (parameter list condensed)
            float minX, float minY, float minZ,
            float maxX, float maxY, float maxZ,
            float[] out, int o) {
        for (int r = 0; r < 3; r++) {
            float lo = m[mo + r * 4 + 3];
            float hi = lo;
            float a = m[mo + r * 4] * minX;
            float b = m[mo + r * 4] * maxX;
            lo += Math.min(a, b);
            hi += Math.max(a, b);
            a = m[mo + r * 4 + 1] * minY;
            b = m[mo + r * 4 + 1] * maxY;
            lo += Math.min(a, b);
            hi += Math.max(a, b);
            a = m[mo + r * 4 + 2] * minZ;
            b = m[mo + r * 4 + 2] * maxZ;
            lo += Math.min(a, b);
            hi += Math.max(a, b);
            out[o + r] = lo;
            out[o + 3 + r] = hi;
        }
    }
```

For output axis `r`, both bounds start at the translation $`t_r`$ (`m[mo + r * 4 + 3]`), then each of the
three terms adds its smaller product to `lo` and its larger one to `hi`. `Math.min(a, b)` rather than
"`a` if the entry is positive" handles negative entries (rotations, mirrors) with no branch on the
sign. The output is the usual six floats: three minimums, then three maximums.

### 24.5 toMat4 and lerp

`toMat4` was shown in §12.4. `lerp` interpolates two transforms entry by entry:

```java
    /**
     * Linearly interpolates two transforms element-wise (fine for render interpolation between two
     * close simulation ticks; not a true rotation blend).
     */
    public static void lerp(float[] a, int ao, float[] b, int bo, float t, float[] out, int o) {
        for (int i = 0; i < STRIDE; i++) {
            float x = a[ao + i];
            out[o + i] = x + (b[bo + i] - x) * t;
        }
    }
```

Lerping a rotation matrix entry by entry does not give a rotation in between (halfway between a
matrix and its 90° turn, the columns shrink to length 0.71). Between two simulation ticks 1/30 s apart
the rotations differ by a few degrees and the error is invisible; chapter
[03](03-rotations-and-transforms.md) works out how small it is. `RenderPacket.interpolatedWorld` calls
this, and `DrawBuilder` writes the same formula inline while it fills instance records.

## 25. Step 12: planes, rays, bounding volumes and culling

The geometry records are thin wrappers around the ideas of Part A.

**Planes** (§5.4):

```java
public record Planef(Vec3f normal, float d) {
    /** The ground plane y = 0, facing up. */
    public static final Planef GROUND = new Planef(Vec3f.UNIT_Y, 0);

    /** Returns the plane through {@code point} with unit {@code normal}. */
    public static Planef fromPointNormal(Vec3f point, Vec3f normal) {
        Vec3f n = normal.normalize();
        return new Planef(n, -n.dot(point));
    }

    /** Returns the signed distance of {@code p}. */
    public float distance(Vec3f p) {
        return normal.dot(p) + d;
    }

    /** Returns the ray parameter where {@code ray} hits the plane, or NaN if parallel. */
    public float intersect(Ray3f ray) {
        float denom = normal.dot(ray.direction());
        if (Math.abs(denom) < 1e-12f) {
            return Float.NaN;
        }
        return -(normal.dot(ray.origin()) + d) / denom;
    }
}
```

`fromPointNormal` normalizes the normal (so `distance` really is a distance) and sets $`d = -\hat{\mathbf{n}} \cdot \mathbf{p}_0`$ (§5.4). `fromPoints` was shown in §6.5.

**Rays.** A **ray** is a half-line: the points $\mathbf{o} + t\mathbf{d}$ for $t \ge 0$, an origin and
a direction. `Ray3f.at(t)` returns that point; with a unit direction, $t$ is the distance travelled.
Where does a ray hit a plane? Substitute the ray into the plane equation and solve for $t$:

```math
\hat{\mathbf{n}} \cdot (\mathbf{o} + t\mathbf{d}) + d = 0 \;\Rightarrow\; \hat{\mathbf{n}} \cdot \mathbf{o} + t\,(\hat{\mathbf{n}} \cdot \mathbf{d}) + d = 0 \;\Rightarrow\; t = -\frac{\hat{\mathbf{n}} \cdot \mathbf{o} + d}{\hat{\mathbf{n}} \cdot \mathbf{d}}
```

That is `Planef.intersect`. When $\hat{\mathbf{n}} \cdot \mathbf{d} = 0$ the ray runs parallel to the
plane and never hits it (or lies in it); the code treats anything below $10^{-12}$ as parallel and
returns NaN. **Example** (test `valueRecords`): the ground plane and a ray from $(0, 3, 0)$ straight
down: $t = -(3 + 0)/(-1) = 3$. `Intersect.rayPlane` is the same formula with primitive arguments, and it
also rejects $t < 0$ (a plane behind the ray's origin). Picking the ground under the mouse is this
formula specialized to the horizontal plane $`y = y_g`$, where $\hat{\mathbf{n}} = (0, 1, 0)$ and the
dot products collapse to the y components: `IsoCameraController.groundPoint` computes
`t = (groundY - origin[1]) / direction[1]`.

**Spheres and boxes:**

```java
    /** Returns {@code true} if {@code p} is inside or on the sphere. */
    public boolean contains(Vec3f p) {
        return center.distanceSquared(p) <= radius * radius;
    }

    /** Returns {@code true} if the spheres overlap. */
    public boolean intersects(Spheref o) {
        float r = radius + o.radius;
        return center.distanceSquared(o.center) <= r * r;
    }
```

Two spheres overlap when their centres are closer than the sum of their radii; both tests compare
squares (§3.4). `Aabb3f` is a min corner and a max corner:

- `EMPTY` has min $= +\infty$ and max $= -\infty$, so `union` with it returns the other box unchanged
  (the same trick as `Vec3Ops.bounds`, §19);
- `contains(p)` checks $\min \le p \le \max$ on each axis;
- `intersects(o)` checks that the two boxes overlap on **every** axis: two boxes are apart exactly when
  some axis separates them;
- `union` is `min.min(o.min)` and `max.max(o.max)` (§15); `include` and `expand` grow a box;
- `surfaceArea()` is $`2(d_x d_y + d_y d_z + d_z d_x)`$: the six faces, two of each size. Only its test
  calls it. The BVH builder of chapter [11](11-physics-navigation-crowds.md), which needs box areas to
  estimate how often a ray will hit a box, has its own private `area(x, y, z)` returning
  `x * y + y * z + z * x` — half the surface area, which is enough because it only compares areas
  with each other.

`Capsulef` (a segment with a radius, a character's shape) contains a point when the point's squared
distance to the segment is at most $r^2$, using §5.6's closest point.

[`Intersect`](../../modules/math/src/main/java/dev/jeddy/math/Intersect.java) holds the full set of
tests — ray–box slabs, watertight ray–triangle, ray–sphere, ray–capsule, ray–cylinder, sphere–box,
cone and sector tests for spell areas, 2D segment tests — with primitive arguments, so they allocate
nothing. Chapter [11](11-physics-navigation-crowds.md) uses them.

**Culling many spheres.** A **frustum** — the volume a camera sees — is six planes, stored as 24 floats
(`CullOps.PLANES_FLOATS`), each $(a, b, c, d)$ with "inside" positive. Chapter
[04](04-cameras-and-projection.md) extracts them from the view-projection matrix. A sphere is visible
unless it is entirely behind one plane: unless, for some plane, its centre's signed distance is less
than $-r$. `CullOps.spheres` runs that test over struct-of-arrays spheres and writes the indices of the
visible ones:

```java
        float p0a = planes[0], p0b = planes[1], p0c = planes[2], p0d = planes[3];
        // ... planes 1 to 5 the same way: 24 locals
        int n = outOffset;
        for (int i = start; i < end; i++) {
            float x = cx[i];
            float y = cy[i];
            float z = cz[i];
            float nr = -r[i];
            boolean visible =
                    p0a * x + p0b * y + p0c * z + p0d >= nr
                            & p1a * x + p1b * y + p1c * z + p1d >= nr
                            // ... planes 2 to 4
                            & p5a * x + p5b * y + p5c * z + p5d >= nr;
            out[n] = i;
            n += visible ? 1 : 0; // branch-free compaction
        }
        return n - outOffset;
```

Each line is a plane's signed distance (§5.4) compared with $-r$. Three details are performance
choices:

- **The planes are loaded into 24 locals before the loop**, for the reason of §24.4: `planes` and `cx`
  are both `float[]`, so without the copies the JIT would have to assume a write could change them. (The
  loop writes only the `int[] out`, but reading the planes once is still the cheapest way.)
- **`&`, not `&&`.** On `boolean`s, `&` evaluates both sides and combines them without jumping;
  `&&` skips the rest after the first `false`, which needs a branch per plane. Whether a sphere is
  visible is unpredictable from one element to the next, and a mispredicted branch costs a CPU around
  15–20 cycles; six multiply-adds cost less.
- **Branch-free compaction.** The index is written to `out[n]` every time, and the counter only moves
  when the sphere is visible. An invisible sphere's index is simply overwritten by the next one. That is
  why the Javadoc asks for room for `end - start` entries.

## 26. Step 13: Packing

[`Packing`](../../modules/math/src/main/java/dev/jeddy/math/Packing.java) holds the encoders of §13 as
static methods.

```java
    /** Converts to IEEE 754 binary16 bits (intrinsified). */
    public static short toHalf(float f) {
        return Float.floatToFloat16(f);
    }

    /** Converts binary16 bits to a float (intrinsified). */
    public static float fromHalf(short h) {
        return Float.float16ToFloat(h);
    }
```

Half floats (§13.1) need no code of their own: the JDK does the rounding, and "intrinsified" means
HotSpot replaces the call with the CPU's conversion instruction where there is one. The result is a
`short` holding the 16 bits.

```java
    /** Packs {@code v} in [-1, 1] into a signed normalized 16-bit integer. */
    public static short packSnorm16(float v) {
        return (short) Math.round(Math.max(-1f, Math.min(1f, v)) * 32767f);
    }

    /** Unpacks a signed normalized 16-bit integer. */
    public static float unpackSnorm16(short s) {
        return Math.max(-1f, s / 32767f);
    }
```

§13.2's formulas, literally: clamp, scale, round, narrow to `short`. `Math.round(float)` returns an
`int`, rounding halves up; the value is at most 32767 in size, so the `(short)` cast keeps it exactly.
`packSnorm8` and `unpackSnorm8` are the same with 127 and `byte`. `packUnorm8` and `packUnorm16` return
an `int`: Java has no unsigned byte or short, and 255 or 65535 would not survive a cast to `byte` or
`short` as a positive number (L00 §5.4).

```java
    public static int packOctahedral16(float x, float y, float z) {
        float inv = 1 / (Math.abs(x) + Math.abs(y) + Math.abs(z));
        float u = x * inv;
        float v = y * inv;
        if (z < 0) {
            float ou = u;
            u = (1 - Math.abs(v)) * signNotZero(ou);
            v = (1 - Math.abs(ou)) * signNotZero(v);
        }
        return (packSnorm16(u) & 0xFFFF) | (packSnorm16(v) << 16);
    }

    private static float signNotZero(float v) {
        return v >= 0 ? 1f : -1f;
    }
```

- The first three lines project onto the octahedron (§13.3, step 1).
- The fold (step 2) saves the old `u` in `ou` first, because the new `v` needs the **old** `u` — the
  same ordering hazard as `MVec3f.cross` (§17), solved with a temporary.
- `signNotZero` returns $+1$ for 0, where `Math.signum` would return 0 and collapse the fold onto an
  axis.
- The last line packs two 16-bit values into one `int` (L00 §6.4): `u` in the low half, `v` in the high
  half. The `& 0xFFFF` is needed for `u` because a negative `short` widens to an `int` with sixteen
  extra 1 bits (sign extension, L00 §5.4), which would overwrite `v`. `v` needs no mask: its sign bits
  are shifted out of the top.

`unpackOctahedral16` reverses it: `(short) packed` takes the low 16 bits as a signed number, `(short)
(packed >>> 16)` the high ones; it recovers $z = 1 - |u| - |v|$, unfolds when $z < 0$, and normalizes.

```java
    /** Packs linear 0..1 components into {@code 0xAABBGGRR} (R in the lowest byte, GL order). */
    public static int packRgba8(float r, float g, float b, float a) {
        return packUnorm8(r) | (packUnorm8(g) << 8) | (packUnorm8(b) << 16) | (packUnorm8(a) << 24);
    }

    /** Returns component {@code index} (0 = R .. 3 = A) of an RGBA8 value as 0..1. */
    public static float unpackRgba8(int rgba, int index) {
        return ((rgba >>> (index * 8)) & 0xFF) / 255f;
    }
```

Four bytes into an `int` with shifts, and back with an unsigned shift and a mask (L00 §6.3–6.4).
`srgbToLinear` and `linearToSrgb` are the exact piecewise sRGB curves; chapter
[08](08-effects-and-post.md) explains why colours are stored that way.

Who calls `Packing`? Today only its test. The mesh writer, in the `asset` module, uses `MeshEncoding`'s
own `snorm16` and `half`, which are the same formulas: `asset` does not depend on `math` (its build
script names only `core` and `compress`). The cooker has its own sRGB curve in `ImageRgba`. `Packing`
is the math module's reference for the encodings, and the place its test pins them down.

## 27. Step 14: the GPU side, instance.glsl

Everything above runs on the CPU. The same transforms run on the GPU, once per vertex, in GLSL. The
whole of
[`instance.glsl`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/engine/instance.glsl)
is this chapter's math; walk through it top to bottom.

```glsl
// Instance records written by DrawBuilder (DESIGN §9.2): three rows of the interpolated world
// transform (row-major 3x4) and (material, flags, entity, camera fade as float bits). Vertex
// shaders only.
#pragma once
```

`#pragma once` is honoured by the engine's own shader preprocessor (`ShaderPreprocessor`, D34), which
expands `#include` lines before the driver sees the source: the file is pasted only once however many
includes name it.

Next come the `JeddyInstance` struct and the `JeddyInstances` buffer block quoted in §12.2, then:

```glsl
JeddyInstance jeddyInstance() {
    return jeddyInstances[gl_BaseInstance + gl_InstanceID];
}
```

- The struct is §12.2's 64-byte record. In std430 each `vec4` and `uvec4` takes 16 bytes at a 16-byte
  aligned offset, so the GLSL layout and `DrawBuilder`'s byte offsets (0, 16, 32 for the rows; 48–60
  for the data) agree with no padding anywhere.
- `buffer` makes it a **shader storage buffer**: a block of GPU memory the shader indexes like an
  array, of any length (`[]`). `binding = 0` is the slot the renderer binds this frame's instance
  records to. `readonly` promises the shader never writes it, which allows the compiler to read it
  through read-only data paths.
- `gl_InstanceID` counts the instances within one draw (0, 1, 2, …), and `gl_BaseInstance` is the
  draw's starting record, taken from its indirect command. Their sum is this instance's record. Chapter
  [06](06-renderer-architecture.md) shows how the draws are arranged so the records of each draw are
  contiguous (D30).
- `data.w` holds a `float`'s bits in a `uint`; `pbr.vert` turns them back with `uintBitsToFloat`.

```glsl
vec3 jeddyTransformPoint(JeddyInstance i, vec3 p) {
    vec4 v = vec4(p, 1.0);
    return vec3(dot(i.row0, v), dot(i.row1, v), dot(i.row2, v));
}

vec3 jeddyTransformDirection(JeddyInstance i, vec3 d) {
    return vec3(dot(i.row0.xyz, d), dot(i.row1.xyz, d), dot(i.row2.xyz, d));
}
```

The row form of §7.4, and $w = 1$ against $w = 0$ of §8.3: for a direction, `.xyz` drops the
translation entry of each row. These are `AffineOps.transformPoint` and `transformDirection` in GLSL.

```glsl
// The 3x3 part of the instance transform (columns).
mat3 jeddyLinear(JeddyInstance i) {
    return transpose(mat3(i.row0.xyz, i.row1.xyz, i.row2.xyz));
}
```

GLSL's `mat3(a, b, c)` takes **columns**, so `mat3(row0.xyz, row1.xyz, row2.xyz)` is a matrix whose
columns are our rows — the transpose of the linear part. `transpose` turns it back, so `m[j]` of the
result is column $j$, the image of axis $j$ (§7.2). The two remaining functions need columns, because
the triple product and the cofactors are defined on columns: `jeddyMirrored`, quoted in §9.4, is the
determinant as a triple product of these columns (§9.2), compared with zero.

`jeddyTransformNormal` was walked through in §11.2. Here is how `pbr.vert`, the vertex shader of the
lit pass, uses all of them:

```glsl
    vWorld = jeddyTransformPoint(inst, position);
    float mirror = jeddyMirrored(inst) ? -1.0 : 1.0;
    vNormal = jeddyTransformNormal(inst, n);
    vTangent = vec4(normalize(jeddyTransformDirection(inst, t)), handedness * mirror);
    // ...
    gl_Position = jViewProjection * vec4(vWorld, 1.0);
```

The position is a point, the tangent a direction (it lies along the surface, §11.1), the normal goes
through the cofactor matrix, and the bitangent's sign is flipped for mirrored instances. The last line
multiplies by the column-major view-projection of §12.1 (`jViewProjection * vec4(...)`: column vectors,
§7.9).

**What the hardware does.** This runs in the **vertex shader** stage, once for each vertex the draw
needs (a GPU usually reuses the result for an index it has just processed, so a vertex shared by
several triangles is often shaded once). Per vertex, the record is 64 bytes read from the storage
buffer — the same 64 bytes for every vertex of an instance, so after the first vertex they come from
the GPU's caches. The work is three 4-component dot products for the point, three 3-component ones for
the tangent, three cross products and a dot product for the normal: a few dozen multiply-adds, small
next to the lighting the fragment shader does later. The record was written by the CPU into the frame
ring before the draw was issued; Lesson 1 explains the fence that keeps the CPU from overwriting it
while the GPU reads. What can go wrong here: a layout mismatch between `DrawBuilder` and this struct (a
`vec3` row, an extra field) shifts every record and produces garbage transforms with no error from GL.
GPU tests that draw through this file and read pixels back — `DrawBuilderTest` (tagged `gpu`) draws
20,000 instances with `jeddyTransformPoint` — are what catch it.

---

# Part C — Using it and proving it

## 28. Where the engine uses it

### 28.1 The camera, every frame

[`IsoCamera`](../../modules/math/src/main/java/dev/jeddy/math/IsoCamera.java) turns four numbers —
target, yaw, pitch, distance — into the matrices the renderer needs, without allocating:

```java
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
        if (orthographic) {
            // ... orthographic projection (chapter 04)
        } else {
            Mat4Ops.perspectiveReversedZ(fovY, aspect, near, projection, 0);
        }
        Mat4Ops.mul(projection, 0, view, 0, viewProjection, 0);
        Mat4Ops.invert(viewProjection, 0, inverseViewProjection, 0);
        CullOps.extractPlanes(viewProjection, 0, frustumPlanes);
    }
```

Almost every section of Part A is in these lines:

- **The eye** (§4.2): the direction from the target to the eye is an arrow of length 1 tilted up by
  `pitch` — height $\sin(\text{pitch})$, ground part $\cos(\text{pitch})$ — and turned on the ground by
  `yaw`, whose ground part splits into $\sin(\text{yaw})$ along X and $\cos(\text{yaw})$ along Z. Times
  `distance`, added to the target. With the defaults (yaw 45°, pitch 55°, distance 20, target at the
  origin) the eye is at $(8.11, 16.38, 8.11)$, and its distance from the target is 20.0. The `eye` is
  an `MVec3f` field (§14.4): set, never allocated.
- **The view matrix** (§6.5, §10.3): `lookAt` with up $= (0, 1, 0)$. For the default camera its
  translation column comes out as $(0, 0, -20)$: the target sits 20 m down the camera's −Z.
- **The product** $P \cdot V$ (§7.6) and **its inverse** (§10.5), for un-projecting the mouse.
- **The frustum planes** for culling (§25, chapter 04).

All results land in `public final float[]` fields created with the camera. The test
`kernelsDoNotAllocate` runs `update` inside `Allocations.assertNoAllocation` (§30).

### 28.2 Sending the matrices to the GPU

The camera's arrays reach the shaders unchanged (§12.1). `SceneRenderer.writeFrameConstants` copies
`view`, `projection`, `viewProjection` and `inverseViewProjection` — plus the inverse projection,
computed there with `Mat4Ops.invert` — into the 544-byte frame block, 64 bytes each, at offsets 0, 64,
128, 192 and 256: the order of the `mat4` members in `frame.glsl`. The depth-only passes take the
view-projection as a single uniform, copied into a 64-byte native segment and passed to
`glProgramUniformMatrix4fv` with `transpose = false`.

### 28.3 Object transforms

Each entity's position, rotation and scale live in struct-of-arrays columns in `TransformStore`
(chapter [03](03-rotations-and-transforms.md)). When one changes, the store calls
`AffineOps.composeTRS` (§24.1) and, for a child, `AffineOps.mul` with its parent's world transform
(§8.7). The render packet keeps the previous and the current tick's world transforms (`prevWorld`,
`currWorld`), and `DrawBuilder` writes their interpolation into the 64-byte instance records the vertex
shader reads (§12.2, §27). From simulation to GPU, a transform is 12 floats in the same order at every
step.

### 28.4 Bounding spheres

A mesh's bounding sphere is stored in the mesh's own space. To use it in the world, `World` moves the
centre with `AffineOps.transformPoint` and scales the radius by the largest axis scale (§8.8):

```java
    private static float maxScale(float[] m, int o) {
        float sx = m[o] * m[o] + m[o + 4] * m[o + 4] + m[o + 8] * m[o + 8];
        float sy = m[o + 1] * m[o + 1] + m[o + 5] * m[o + 5] + m[o + 9] * m[o + 9];
        float sz = m[o + 2] * m[o + 2] + m[o + 6] * m[o + 6] + m[o + 10] * m[o + 10];
        return (float) Math.sqrt(Math.max(sx, Math.max(sy, sz)));
    }
```

Indices 0, 4, 8 are column 0 of the row-major layout (§12.2), so `sx` is the squared length of the
image of the X axis. For $M = TRS$ that length is $`|s_x|`$, because a rotation does not change lengths
(§8.6). The method compares squared lengths and takes one square root at the end (§3.4). It is exact
for TRS transforms; for a sheared matrix the longest column can underestimate the largest stretch, but
the engine's transforms are built from TRS.

### 28.5 Picking with an inverse

When the editor or the game asks "which object is under the mouse?", `World.pick` casts a ray against
every renderable. For each one it first tests the ray against the bounding sphere of §28.4 — a few
multiplications that reject almost everything — and then tests the mesh's **local** bounding box in
the mesh's own space:

```java
            if (!AffineOps.invert(world, s * 12, pickInverse, 0)) {
                continue;
            }
            AffineOps.transformPoint(pickInverse, 0, ox, oy, oz, pickLocal, 0);
            AffineOps.transformDirection(pickInverse, 0, dx, dy, dz, pickLocal, 3);
```

The ray's origin is a point, so it goes through `transformPoint`; its direction is a direction, so it
goes through `transformDirection` (§8.3). A transform that cannot be inverted (zero scale) is skipped
(§10.6). Then `Intersect.rayAabb` tests the local ray against the local box: a turned wall is picked by
its real outline, not by a box around its turned shape.

Why can the hit distance found in local space be compared with hits in world space? Because an affine
map keeps "$t$ of the way along a line": $`M^{-1}(\mathbf{o} + t\mathbf{d}) = M^{-1}\mathbf{o} + t\, A^{-1}\mathbf{d}`$, where $A^{-1}$ is the linear part. The local ray, with origin $M^{-1}\mathbf{o}$ and
direction $A^{-1}\mathbf{d}$, reaches the same point at the same $t$. That only works because the code
does **not** normalize the local direction; the method's Javadoc says exactly this. `pickInverse` and
`pickLocal` are fields, so picking allocates nothing.

### 28.6 Skins and the ground

- `Skeleton` converts glTF's column-major inverse bind matrices to the row-major 3×4 (§12.4) and
  composes joint transforms with `AffineOps.composeTRS`; chapter [09](09-animation-and-skinning.md)
  multiplies them into the palettes `skinning.glsl` reads, as three `vec4` rows per joint — the same
  layout as instance records.
- `IsoCameraController.groundPoint` intersects the mouse ray with the ground plane (§25); drag-panning
  keeps that point under the cursor (chapter 04).

## 29. Floating point in practice

### 29.1 How precise is a float?

A `float` has 24 significant bits, about 7 decimal digits (L00 §7.3). The gap between neighbouring
values (`Math.ulp`, "unit in the last place") grows with the size of the number:

| Value | Spacing (`Math.ulp`) | If the unit is metres |
|---|---|---|
| 1 | $1.19 \times 10^{-7}$ | 0.1 µm |
| 100 | $7.63 \times 10^{-6}$ | 7.6 µm |
| 1000 | $6.10 \times 10^{-5}$ | 61 µm |
| 4000 | $2.44 \times 10^{-4}$ | 0.24 mm |

A CRPG level is a few hundred metres across, so `float` positions are far more precise than needed
(DESIGN §8.2). Open-world engines whose worlds span tens of kilometres hit visible jitter and use
camera-relative rendering or `double`; Jeddy does not need to.

### 29.2 Never compare floats with ==

Rounding errors appear in almost every computation. A rotation by 90° gives entries like `5.9604645E-8`
instead of 0 (see §31's jshell session and exercise 2), and a matrix times its inverse gives an identity that is off by about $10^{-7}$. So
comparisons use a tolerance:

- `Vec3f.approxEquals(o, epsilon)`, `Mat4f.approxEquals` and `MathF.approx` compare each component with
  an **absolute** tolerance: $|a - b| \le \varepsilon$. The math tests use `EPS = 1e-4f`.
- When values can be large, an absolute tolerance is wrong: $10^{-4}$ is huge next to 0.001 and smaller
  than one `ulp` next to 10,000. `TransformStoreTest` uses `2e-4f * (1 + Math.abs(ref[k]))`: absolute
  ($2 \times 10^{-4}$) near zero, **relative** ($2 \times 10^{-4}$ of the value) for large values.
- `Quatf.approxEquals` compares rotations, not components: `1 - Math.abs(dot(o)) <= epsilon`, because
  $q$ and $-q$ are the same rotation (chapter 03).
- Record `equals` is exact and, worse, tells $-0.0$ from $0.0$ (§15). Tests compare records with
  `approxEquals`.

Guards against degenerate input use exact checks only where exactness is meaningful: the inverses
reject `det == 0 || !Float.isFinite(det)` (a determinant that overflowed or is NaN is as useless as
zero, §10.6), and `Planef.intersect` treats a ray as parallel when $|\hat{\mathbf{n}} \cdot \mathbf{d}|
< 10^{-12}$.

### 29.3 Subtle consequences

- **Lerp endpoints.** `a + (b - a) * t` returns exactly `a` at $t = 0$ but not always `b` at $t = 1$:
  with `a = 1e8f, b = 1f`, the difference `b - a` rounds to $-10^8$ and `MathF.lerp(1e8f, 1f, 1f)`
  returns 0, not 1. The other form, $(1 - t)a + tb$, is exact at both ends, but it has its own flaws: it
  is not **monotonic** (as $t$ grows the result can step backwards — for $a = 1.1$, $b = 7.3$, stepping
  $t$ through every `float` in $[0, 0.01]$, the result decreased 92,275 times), and it does not return
  `a` when `a == b` (for random `a` in $[0, 10)$ and $t$ in $[0, 1)$, 3.6 % of cases). The engine uses
  the first form, which is monotonic and exact when `a == b`. For game values of similar size the
  difference is invisible; know it exists. (Measured with a small program on the sandbox; exercise 11.)
- **Drift.** Multiplying rotations frame after frame slowly loses orthonormality, and quaternions drift
  from unit length. Jeddy avoids the accumulation: `TransformStore` keeps the local translation,
  rotation and scale and rebuilds the world matrix from them every time, and code that composes
  quaternions renormalizes (`QuatOps.normalize`, `World.compose`, every `nlerp`).
- **Determinism.** Since JDK 17 (JEP 306) all Java floating-point arithmetic is strict IEEE 754, so
  `+ - * /` and `Math.sqrt` give the same bits on every machine. `Math.sin`, `Math.exp` and the other
  **transcendental** functions (those that cannot be computed with a finite number of additions,
  multiplications and roots, so libraries approximate them) may differ in the last bit between platforms; DESIGN §4.6 says gameplay code
  that needs bit-exact results uses `StrictMath`. It also says to avoid `Math.fma` in kernels, because
  without hardware FMA it falls back to a very slow software path.
- **References in double.** The kernel tests compute expected values in `double` (`mulRef` in
  `MatrixAndQuatTest`), so the reference is far more precise than the code under test, and the
  tolerance measures the kernel's error, not the test's (§30).

## 30. The tests, walked through

The module's tests are
[`MatrixAndQuatTest`](../../modules/math/src/test/java/dev/jeddy/math/MatrixAndQuatTest.java) and
[`GeometryAndUtilTest`](../../modules/math/src/test/java/dev/jeddy/math/GeometryAndUtilTest.java)
(`DiceTest` belongs to Lesson 2). Their random inputs come from seeded `java.util.Random`s
(`new Random(1234)` in `MatrixAndQuatTest`), so a failure repeats exactly.

**Against a double reference.**

```java
    /** Reference 4×4 multiply in double precision. */
    private static double[] mulRef(float[] a, float[] b) {
        double[] r = new double[16];
        for (int c = 0; c < 4; c++) {
            for (int row = 0; row < 4; row++) {
                double s = 0;
                for (int k = 0; k < 4; k++) {
                    s += (double) a[k * 4 + row] * b[c * 4 + k];
                }
                r[c * 4 + row] = s;
            }
        }
        return r;
    }
```

This is §7.6's $`\sum_k A_{rk}B_{kc}`$ in its plainest form, with column-major indices: $`A_{row,k}`$ is
`a[k * 4 + row]`. `mat4MultiplyMatchesDoubleReference` compares `Mat4Ops.mul` with it on 200 pairs of
random matrices with entries in $[-2, 2]$, to within $10^{-4}$. The plain triple loop is the
specification; the unrolled kernel is the implementation. DESIGN §8.3 asks this of every kernel; today
`Mat4Ops.mul` is the one kernel with a `double` reference, and the others are checked against each other
or against hand-computed values, as follows.

**Inverses and determinants.** `inverseTimesMatrixIsIdentity` builds 200 random TRS matrices (positions
up to ±50, random rotations, scales between 0.5 and 1.5) and checks $M \cdot M^{-1} \approx I$ within
$10^{-4}$; then `Mat4f.scaling(1, 0, 1).invert()` must throw (§10.6) and `Mat4f.scaling(1, 2, 3)` must
have determinant 6 (§9.3).

**Composition.** `trsComposition` is §8.6's worked example: `Mat4f.trs` with translation $(10, 0, 0)$,
a 90° turn about Y and scale 2 must send `UNIT_X` to $(10, 0, -2)$, and must equal
`translation × rotation × scaling` multiplied out — the order $TRS$, checked.

**The view matrix.** `lookAtPutsEyeAtOriginLookingDownMinusZ` uses eye $(3, 10, 7)$ and target
$(-2, 0, 1)$: the eye must map to the origin, the target to $(0, 0, -\lVert \text{eye} - \text{target}
\rVert)$ (§10.3), and `Mat4Ops.lookAt` must agree with `Mat4f.lookAt`.

**The two layouts agree.** `affineOpsAgreeWithMat4` is the bridge between §12.1 and §12.2. For 100
random pairs (one with scale $(1, 2, 0.5)$, so non-uniform), it checks that `AffineOps.composeTRS`
expanded with `toMat4` equals `Mat4f.trs`; that `AffineOps.mul` equals `Mat4f.mul`; that
`AffineOps.invert` (the 3×3 adjugate of §10.4) equals `Mat4f.invert` (the 4×4 cofactors of §10.5) —
two different algorithms that must give the same matrix; and that `transformPoint` agrees. The
tolerance is $10^{-3}$ because positions reach ±20 and errors grow with size (§29.1).

**Hierarchy and boxes.** `hierarchyConcatenation` chains three items (`parent = {-1, 0, 1}`) that each
translate by +1 along x and checks the third ends at $x = 3$ (§8.7). `aabbTransformContainsCorners`
transforms the box $[-1, 1] \times [-2, 2] \times [-3, 3]$ by a random rotation with scale $(2, 1, 3)$
and checks that all 8 transformed corners — enumerated with the bits of `c` (`(c & 1) == 0 ? -1 : 1`,
L00 §6.2) — fall inside the box Arvo's method produced (§8.8).

**Normals and scratch types.** `mat3NormalMatrixAndMutableTypes`:

```java
        Mat4f model = Mat4f.scaling(2, 1, 1);
        Vec3f n = Mat3f.normalMatrix(model).transform(new Vec3f(1, 1, 0).normalize());
        assertEquals(n.x() * 2, n.y(), EPS); // normals shrink along the stretched axis
        assertEquals(1f, Mat3f.rotation(randomQuat()).determinant(), 1e-4f);
        MMat4f a = new MMat4f().set(Mat4f.translation(1, 2, 3));
        MMat4f b = new MMat4f().set(a).invert();
        assertTrue(new MMat4f().mul(a, b).toMat4f().approxEquals(Mat4f.IDENTITY, EPS));
```

§11.1's example, §9.3's rotation determinant, and the in-place inverse and product of §23.

**Camera, culling and picking.** `cameraCullingAndPicking` sets the camera's target to $(10, 0, -5)$:
a sphere there must be visible, spheres far behind or far to the side must not; the ray through the
centre of the screen must hit the ground at the target (within $10^{-2}$), in perspective and in
orthographic mode; and `CullOps.spheres` over 1,000 spheres, every other one far away, must return 500
and write the visible indices in order (`visible[1]` is 2).

**No allocation.**

```java
        Allocations.assertNoAllocation(
                "math kernels",
                () -> {
                    cam.yaw += 0.01f;
                    cam.update(1.5f);
                    AffineOps.concatenate(parent, local, world, 0, n);
                    sink += CullOps.spheres(cam.frustumPlanes, cx, cy, cz, r, 0, n, out, 0);
                    sink +=
                            Intersect.rayTriangle(
                                    0, 1, 0, 0, -1, 0, -1, 0, -1, 1, 0, -1, 0, 0, 1, 10, null);
                });
```

A camera update, a hierarchy pass over 256 items, sphere culling and a ray–triangle test: the per-frame
math, run until the JIT has compiled it, must not move the thread's allocation counter (L00 §8.2, D18).
The results go into the field `sink` so the JIT cannot delete the work as unused.

**The rest.** `quaternionsAgreeWithMatrices`, `slerpAndLookRotation` and `quatOpsMatchQuatf` test chapter
03's quaternions (and `quaternionsAgreeWithMatrices` contains the index swap of §12.5);
`reversedZPerspectiveMapsNearToOneAndFarTowardsZero` tests chapter 04's projections. In
`GeometryAndUtilTest`, `valueRecords` checks §25's records — `Planef.fromPoints` distance 2 for
$(5, 2, 5)$ (§5.4), the ray hitting the ground at 3, $\mathbf{x} \times \mathbf{y} = \mathbf{z}$ — and
the $-0.0$ trap; `packing` checks §13 (octahedral round trips, `fromHalf(toHalf(1.5f))`, snorm clamping,
`0xFF0080FF`, the sRGB curves); `scalarHelpers` checks `MathF` (wrap, damp converging to its target,
`moveTowards`, `floorToInt(-1.5f) == -2`, `nextPowerOfTwo(33) == 64`, `smoothstep(0, 1, 0.5f) == 0.5`).
The ray tests (`rayAabb`, `rayTriangle…`, `raySphereCylinderCapsulePlane`, `aoeShapes`, `polygons`)
belong to chapter 11, but most of their assertions are distances and angles you can check by hand with
§5 and §6.

What is not tested: `Vec3Ops` has no test, and `Mat4Ops.mulAffine`, `AffineOps.transformPoints` and
`AffineOps.lerp` are not called by any test. Exercise 8 adds one.

## 31. Things to run and measured numbers

**The tests.**

- `./gradlew :math:test` runs both test classes (Windows: `gradlew.bat :math:test`).
- `./gradlew :math:test --tests 'dev.jeddy.math.MatrixAndQuatTest'` runs only the matrix tests.
- `./gradlew :math:test --tests 'dev.jeddy.math.GeometryAndUtilTest'`, then read `valueRecords`,
  `raySphereCylinderCapsulePlane` and `aoeShapes`: most assertions are distances and angles you can
  check with this chapter's formulas.

**Try it in jshell.** Compile the module, then start `jshell` with its classes on the class path:

```
$ ./gradlew :math:compileJava
$ jshell --class-path modules/math/build/classes/java/main
jshell> import dev.jeddy.math.*
jshell> new Vec3f(3, 0, 4).normalize()
$2 ==> Vec3f[x=0.6, y=0.0, z=0.8]
jshell> Math.toDegrees(Math.acos(new Vec3f(3, 0, 4).normalize().dot(Vec3f.UNIT_X)))
$3 ==> 53.13010064660867
jshell> new Vec3f(1, 2, 3).cross(new Vec3f(4, 5, 6))
$4 ==> Vec3f[x=-3.0, y=6.0, z=-3.0]
jshell> var q = Quatf.fromYaw(MathF.HALF_PI)
q ==> Quatf[x=0.0, y=0.70710677, z=0.0, w=0.70710677]
jshell> Mat4f.rotation(q).transformDirection(Vec3f.UNIT_X)
$6 ==> Vec3f[x=5.9604645E-8, y=0.0, z=-0.99999994]
jshell> Mat4f.translation(5, 0, 0).mul(Mat4f.rotation(q)).transformPoint(Vec3f.UNIT_X)
$7 ==> Vec3f[x=5.0, y=0.0, z=-0.99999994]
jshell> Mat4f.rotation(q).mul(Mat4f.translation(5, 0, 0)).transformPoint(Vec3f.UNIT_X)
$8 ==> Vec3f[x=3.5762787E-7, y=0.0, z=-5.9999995]
jshell> Mat3f.normalMatrix(Mat4f.scaling(2, 1, 1)).transform(new Vec3f(1, 1, 0).normalize())
$9 ==> Vec3f[x=0.35355338, y=0.70710677, z=0.0]
jshell> Mat4f.scaling(-1, 1, 1).determinant()
$10 ==> -1.0
jshell> MathF.damp(0, 10, 5, 1/60f)
$11 ==> 0.7995558
jshell> Integer.toHexString(Packing.toHalf(0.1f) & 0xFFFF)
$12 ==> "2e66"
jshell> Packing.unpackSnorm16(Packing.packSnorm16(0.5f))
$13 ==> 0.50001526
```

(On Windows, write the class path with backslashes. The `$n` numbers depend on what you typed before.)
Every value above was produced by the code; each matches a worked example of Part A.

**Benchmarks.** `MathBench` measures the kernels on 10,000 items:
`./gradlew :bench:jmh -Pjmh.includes=MathBench`, and with `"-Pjmh.args=-prof gc"` added it also reports
allocation, which should be about 0 bytes per operation (D77 explains why JMH's figure, which sums every
thread of the run, can show a few bytes that are not the kernel's). The committed baseline the nightly job compares
against (`bench/baseline/linux-x64.json`, measured on a shared GitHub Linux runner, so noisy; D77) has:

| Benchmark | Time per operation | Per item |
|---|---|---|
| `composeTRS10k` | 60.1 µs | 6.0 ns |
| `concatenate10k` | 96.5 µs | 9.6 ns |
| `cullSpheres10k` | 39.7 µs | 4.0 ns |
| `mat4Mul` | 14.7 ns | — |

A few nanoseconds per entity: at 60 frames per second, composing and concatenating 10,000 transforms
takes about 0.16 ms of the 16.7 ms frame (L00 §11). `concatenate10k` costs more per item than
`composeTRS10k`: it does 36 multiplications per child against `composeTRS`'s 27 (nine products, then
two multiplications for each of nine entries), and in the benchmark's hierarchy (chains of eight, each
item the child of the one before) every product reads the result its parent wrote a moment earlier,
so consecutive items cannot overlap. That second reason is an explanation, not a measurement.

**The escape-analysis experiment** of §14.3 is exercise 10; run it with and without
`-XX:-DoEscapeAnalysis`.

## 32. Exercises

Solutions that add code to the repository must pass `./gradlew check`, which enforces
google-java-format (AOSP style, 100 columns); run `./gradlew spotlessApply` after pasting one in.

**1. Lagrange's identity.** Prove $\lVert \mathbf{a} \times \mathbf{b} \rVert^2 = \lVert \mathbf{a}
\rVert^2 \lVert \mathbf{b} \rVert^2 - (\mathbf{a} \cdot \mathbf{b})^2$ (§6.2) by expanding both sides.

<details><summary>Hint</summary>

Expand the left side as the sum of the squares of the three components of §6.1, and the right side as
$`(a_x^2 + a_y^2 + a_z^2)(b_x^2 + b_y^2 + b_z^2) - (a_x b_x + a_y b_y + a_z b_z)^2`$. Group terms by which
pair of axes they involve.

</details>

<details><summary>Solution</summary>

Left:

$$(a_y b_z - a_z b_y)^2 + (a_z b_x - a_x b_z)^2 + (a_x b_y - a_y b_x)^2 = a_y^2 b_z^2 + a_z^2 b_y^2 + a_z^2 b_x^2 + a_x^2 b_z^2 + a_x^2 b_y^2 + a_y^2 b_x^2 - 2(a_y a_z b_y b_z + a_x a_z b_x b_z + a_x a_y b_x b_y)$$

Right: the product of the two sums of squares has nine terms; the three with matching indices
($`a_x^2 b_x^2`$, $`a_y^2 b_y^2`$, $`a_z^2 b_z^2`$) are cancelled by the same three squares inside $(\mathbf{a}
\cdot \mathbf{b})^2$. What is left of the product is the six mixed terms $`a_x^2 b_y^2 + a_x^2 b_z^2 + a_y^2 b_x^2 + a_y^2 b_z^2 + a_z^2 b_x^2 + a_z^2 b_y^2`$, and what is left of $-(\mathbf{a} \cdot
\mathbf{b})^2$ is the cross terms $`-2(a_x b_x a_y b_y + a_x b_x a_z b_z + a_y b_y a_z b_z)`$. Both sides
are the same nine-term expression. Check with §6.2's numbers: $54 = 14 \cdot 77 - 32^2$.

</details>

**2. Read a matrix by its columns.** Without running anything, write down the 16 floats of
`Mat4f.rotation(Quatf.fromYaw(MathF.HALF_PI))` in `toArray` order, then the 12 floats
`AffineOps.composeTRS` writes for the same rotation with zero translation and unit scale. Check both in
jshell or a small test.

<details><summary>Hint</summary>

The images of the axes are X → (0, 0, −1), Y → (0, 1, 0), Z → (1, 0, 0) (§7.3). Column-major writes those
columns one after another, each followed by a 0, then (0, 0, 0, 1). Row-major writes the rows, each
followed by its translation. Expect values like 5.96e-8 instead of exact zeros, so compare with a
tolerance.

</details>

<details><summary>Solution</summary>

Column-major: `[0, 0, -1, 0,  0, 1, 0, 0,  1, 0, 0, 0,  0, 0, 0, 1]`. Row-major 3×4: the rows of
$`R_y(90°)`$ are $(0, 0, 1)$, $(0, 1, 0)$, $(-1, 0, 0)$, so `[0, 0, 1, 0,  0, 1, 0, 0,  -1, 0, 0, 0]`. The
code prints `[5.9604645E-8, 0.0, -0.99999994, 0.0, 0.0, 1.0, 0.0, 0.0, 0.99999994, 0.0, 5.9604645E-8,
0.0, 0.0, 0.0, 0.0, 1.0]` and `[5.9604645E-8, 0.0, 0.99999994, 0.0, 0.0, 1.0, 0.0, 0.0, -0.99999994, 0.0,
5.9604645E-8, 0.0]`: the quaternion's $\sin 45°$ and $\cos 45°$ are not exact in `float`, so $1 - 2
\cdot 0.70710677^2$ comes out as $5.96 \times 10^{-8}$ (§29.2). Note that the second list is the first
one's top three rows read across — the same matrix, the other storage order.

</details>

**3. Order matters.** Add a test to `MatrixAndQuatTest` that builds $T(5, 0, 0)$ and $`R_y(90°)`$ with
`AffineOps.composeTRS`, multiplies them both ways with `AffineOps.mul`, and asserts where each product
sends the point $(1, 0, 0)$.

<details><summary>Hint</summary>

Expected: $(5, 0, -1)$ for $T \cdot R$ and $(0, 0, -6)$ for $R \cdot T$ (§8.4). Use
`AffineOps.transformPoint` and a tolerance of `1e-5f`. Remember that `mul`'s `out` may not be either
input.

</details>

<details><summary>Solution</summary>

```java
    @Test
    void affineOrderMatters() {
        float[] t = new float[12];
        float[] r = new float[12];
        float[] tr = new float[12];
        float[] rt = new float[12];
        AffineOps.composeTRS(5, 0, 0, 0, 0, 0, 1, 1, 1, 1, t, 0);
        Quatf q = Quatf.fromYaw(MathF.HALF_PI);
        AffineOps.composeTRS(0, 0, 0, q.x(), q.y(), q.z(), q.w(), 1, 1, 1, r, 0);
        AffineOps.mul(t, 0, r, 0, tr, 0); // rotate first, then translate
        AffineOps.mul(r, 0, t, 0, rt, 0); // translate first, then rotate
        float[] p = new float[3];
        AffineOps.transformPoint(tr, 0, 1, 0, 0, p, 0);
        assertTrue(new Vec3f(p[0], p[1], p[2]).approxEquals(new Vec3f(5, 0, -1), 1e-5f));
        AffineOps.transformPoint(rt, 0, 1, 0, 0, p, 0);
        assertTrue(new Vec3f(p[0], p[1], p[2]).approxEquals(new Vec3f(0, 0, -6), 1e-5f));
    }
```

The worst error is about $4 \times 10^{-7}$ (§8.4's printed values), well inside the tolerance.

</details>

**4. Invert by hand, check by machine.** Take $`M = T(1, 2, 3)\,S(2, 4, 0.5)`$ (no rotation). Derive
$M^{-1}$ on paper with the affine inverse of §10.2, then assert that `AffineOps.invert` produces it and
that `AffineOps.mul(m, inv)` is the identity.

<details><summary>Hint</summary>

For a diagonal linear part the inverse is the diagonal of reciprocals, and the translation is
$-A^{-1}\mathbf{t}$.

</details>

<details><summary>Solution</summary>

$A^{-1} = \operatorname{diag}(0.5, 0.25, 2)$ and $-A^{-1}\mathbf{t} = -(0.5 \cdot 1, 0.25 \cdot 2, 2
\cdot 3) = (-0.5, -0.5, -6)$, so the expected 12 floats are `[0.5, 0, 0, -0.5,  0, 0.25, 0, -0.5,  0, 0,
2, -6]`. Check: $M$ sends $(1, 1, 1)$ to $(3, 6, 3.5)$, and $M^{-1}$ sends $(3, 6, 3.5)$ to $(1.5 -
0.5, 1.5 - 0.5, 7 - 6) = (1, 1, 1)$.

```java
        float[] m = new float[12];
        float[] inv = new float[12];
        float[] id = new float[12];
        AffineOps.composeTRS(1, 2, 3, 0, 0, 0, 1, 2, 4, 0.5f, m, 0);
        assertTrue(AffineOps.invert(m, 0, inv, 0));
        float[] expected = {0.5f, 0, 0, -0.5f, 0, 0.25f, 0, -0.5f, 0, 0, 2, -6};
        assertArrayEquals(expected, inv, 1e-6f);
        AffineOps.mul(m, 0, inv, 0, id, 0);
        float[] identity = new float[12];
        AffineOps.identity(identity, 0);
        assertArrayEquals(identity, id, 1e-6f);
```

(Add `import static org.junit.jupiter.api.Assertions.assertArrayEquals;` to the test class.) All
these numbers are powers of two or small integers, so the results are exact in `float`.

</details>

**5. Add `AffineOps.determinant`.** Write `public static float determinant(float[] m, int mo)` for the
linear part of a 3×4, and test it on a TRS with scale $(-1, 2, 3)$: expect $-6$ for any rotation. Follow
the kernel rules: no allocation, Javadoc with thread ownership.

<details><summary>Hint</summary>

Reuse the three cofactors at the top of `AffineOps.invert` (§24.3): `a * co0 + b * co1 + c * co2`. By
§9.3, $`\det(TRS) = \det R \cdot s_x s_y s_z = 1 \cdot (-6)`$. Add a call to `kernelsDoNotAllocate` to prove
it allocates nothing.

</details>

<details><summary>Solution</summary>

```java
    /**
     * Returns the determinant of the linear part of affine {@code m}: negative when the transform
     * mirrors (DESIGN §9.2), zero when it is singular.
     *
     * <p>Thread ownership: stateless, callable from any thread.
     */
    public static float determinant(float[] m, int mo) {
        float a = m[mo], b = m[mo + 1], c = m[mo + 2];
        float d = m[mo + 4], e = m[mo + 5], f = m[mo + 6];
        float g = m[mo + 8], h = m[mo + 9], k = m[mo + 10];
        return a * (e * k - f * h) + b * (f * g - d * k) + c * (d * h - e * g);
    }
```

The test: for several `randomQuat()`s, compose with scale $(-1, 2, 3)$ and
`assertEquals(-6f, AffineOps.determinant(m, 0), 1e-4f)`. (The class Javadoc already states thread
ownership for the whole class; the method comment repeats it only if you want it visible at the method.)

</details>

**6. A CPU version of `jeddyTransformNormal`.** Add `AffineOps.transformNormal(float[] m, int mo, float
x, float y, float z, float[] out, int o)` using the cofactor trick of §11.2, then test it against
`Mat3f.normalMatrix` for a non-uniform scale and for a mirror (scale $(-1, 1, 1)$).

<details><summary>Hint</summary>

The columns of the linear part are at indices (0, 4, 8), (1, 5, 9), (2, 6, 10) (§12.2). The cofactor
matrix has columns $`\mathbf{c}_1 \times \mathbf{c}_2`$, $`\mathbf{c}_2 \times \mathbf{c}_0`$, $`\mathbf{c}_0 \times \mathbf{c}_1`$, so "cofactor times $\mathbf{n}$" is $`n_x(\mathbf{c}_1 \times \mathbf{c}_2) + n_y(\mathbf{c}_2 \times \mathbf{c}_0) + n_z(\mathbf{c}_0 \times \mathbf{c}_1)`$. Normalize and multiply by
the sign of the determinant. `Mat3f.normalMatrix` gives $A^{-T}\mathbf{n}$ unnormalized, so compare
directions (normalize both).

</details>

<details><summary>Solution</summary>

```java
    /** Transforms a normal by the inverse transpose of the linear part (see instance.glsl). */
    public static void transformNormal(
            float[] m, int mo, float x, float y, float z, float[] out, int o) {
        float c0x = m[mo], c0y = m[mo + 4], c0z = m[mo + 8];
        float c1x = m[mo + 1], c1y = m[mo + 5], c1z = m[mo + 9];
        float c2x = m[mo + 2], c2y = m[mo + 6], c2z = m[mo + 10];
        // the three cross products: columns of the cofactor matrix
        float ax = c1y * c2z - c1z * c2y, ay = c1z * c2x - c1x * c2z, az = c1x * c2y - c1y * c2x;
        float bx = c2y * c0z - c2z * c0y, by = c2z * c0x - c2x * c0z, bz = c2x * c0y - c2y * c0x;
        float cx = c0y * c1z - c0z * c1y, cy = c0z * c1x - c0x * c1z, cz = c0x * c1y - c0y * c1x;
        float nx = ax * x + bx * y + cx * z;
        float ny = ay * x + by * y + cy * z;
        float nz = az * x + bz * y + cz * z;
        float det = c0x * ax + c0y * ay + c0z * az; // c0 · (c1 × c2)
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        float s = (det < 0 ? -1 : 1) / len;
        out[o] = nx * s;
        out[o + 1] = ny * s;
        out[o + 2] = nz * s;
    }
```

Test with `composeTRS` at scale $(2, 1, 1)$ and normal $(1, 1, 0)/\sqrt2$: expect $(0.4472, 0.8944, 0)$,
which is `Mat3f.normalMatrix(Mat4f.scaling(2, 1, 1))` applied and normalized. For the mirror
$(-1, 1, 1)$: $(0, 1, 0)$ must stay $(0, 1, 0)$ and $(1, 0, 0)$ must become $(-1, 0, 0)$ (§11.2's worked
numbers). A zero-scale matrix gives `len = 0`; decide whether to leave NaN (like GLSL) or write zero,
and say which in the Javadoc.

</details>

**7. Which aliasing does each kernel tolerate?** The Javadocs say `Mat4Ops.mul` and `AffineOps.mul`
"must not overlap" and `AffineOps.invert` "may not overlap". Predict, for each, whether `out == a`,
`out == b` (same array and offset) or, for `invert`, `out == m` would still give the right answer. Then
check with a test.

<details><summary>Hint</summary>

Follow the order of reads and writes. `Mat4Ops.mul` writes column by column; which inputs does a
column's computation read? `AffineOps.mul` writes row by row. `AffineOps.invert` reads everything into
locals first.

</details>

<details><summary>Solution</summary>

- `Mat4Ops.mul`: `out == a` **fails** (writing column 0 of the result destroys column 0 of `a`, which
  every later column needs); `out == b` **works** (each pass copies column `c` of `b` into `b0..b3`
  before writing column `c`, and no later pass reads it).
- `AffineOps.mul`: `out == a` **works** (row `r` of `a` is read into `a0..a3` before row `r` of `out` is
  written, and no later row reads row `r` of `a`); `out == b` **fails** (row 0 of the result overwrites
  entries of `b`'s columns that rows 1 and 2 still read).
- `AffineOps.invert`: in place **works**: all twelve inputs are in locals before the first write.

A quick check with random matrices, comparing against a non-aliased call, gives exactly these five
results. The documented contracts are stricter than the code on purpose: callers should not depend on
an accident of the loop order, which a future optimization might change. `concatenate` relies on none
of this: its `world` reads and writes are different 12-float ranges (§24.2).

</details>

**8. Test the untested kernels.** `Mat4Ops.mulAffine`, `AffineOps.transformPoints` and `AffineOps.lerp`
have no test (§30). Write one test for all three, using kernels that are already tested as references.

<details><summary>Hint</summary>

`mulAffine(m, affine)` must equal `Mat4Ops.mul(m, toMat4(affine))`. `transformPoints` over a few points
must equal `transformPoint` on each. `lerp` at $t = 0$ and $t = 1$ must return its inputs, and at $t =
0.5$ the average.

</details>

<details><summary>Solution</summary>

```java
    @Test
    void untestedKernelsAgreeWithReferences() {
        float[] a = new float[12];
        float[] b = new float[12];
        Quatf q = randomQuat();
        AffineOps.composeTRS(1, 2, 3, q.x(), q.y(), q.z(), q.w(), 2, 1, 0.5f, a, 0);
        AffineOps.composeTRS(-4, 0, 7, 0, 0, 0, 1, 1, 1, 1, b, 0);
        float[] vp = new float[16];
        Mat4Ops.perspectiveReversedZ(1, 1.5f, 0.5f, vp, 0);
        float[] a4 = new float[16], ref = new float[16], got = new float[16];
        AffineOps.toMat4(a, 0, a4, 0);
        Mat4Ops.mul(vp, 0, a4, 0, ref, 0);
        Mat4Ops.mulAffine(vp, 0, a, 0, got, 0);
        assertArrayEquals(ref, got, 1e-5f);

        float[] xs = {0, 1, -2}, ys = {0, 5, 3}, zs = {1, 0, -1};
        float[] p = new float[3];
        for (int i = 0; i < 3; i++) {
            AffineOps.transformPoint(a, 0, xs[i], ys[i], zs[i], p, 0);
            float[] one = {p[0], p[1], p[2]};
            float[] x = {xs[i]}, y = {ys[i]}, z = {zs[i]};
            AffineOps.transformPoints(a, 0, x, y, z, 0, 1);
            assertArrayEquals(one, new float[] {x[0], y[0], z[0]}, 1e-6f);
        }

        float[] out = new float[12];
        AffineOps.lerp(a, 0, b, 0, 0, out, 0);
        assertArrayEquals(a, out, 0f);
        AffineOps.lerp(a, 0, b, 0, 1, out, 0);
        assertArrayEquals(b, out, 1e-6f); // not exact: a + (b - a) * 1 (§29.3)
        AffineOps.lerp(a, 0, b, 0, 0.5f, out, 0);
        for (int i = 0; i < 12; i++) {
            assertEquals((a[i] + b[i]) / 2, out[i], 1e-6f);
        }
    }
```

Note the tolerances: $t = 0$ is exact by construction of `a + (b - a) * t`; $t = 1$ is not guaranteed
exact (§29.3). The test needs the `assertArrayEquals` import of exercise 4.

</details>

**9. Measure the octahedral error.** Encode a million uniformly random unit vectors with
`Packing.packOctahedral16`, decode them, and report the average and worst angle between input and
output, in degrees. Compare with the Javadoc's "about 0.002°".

<details><summary>Hint</summary>

Uniform directions: normalize three `nextGaussian()` values (normalizing three `nextFloat()`s in
$[-1, 1]$ would favour the cube's corners). Compute the angle as `atan2(|a × b|, a · b)` in `double`:
`acos` of a dot product near 1 loses almost all its precision.

</details>

<details><summary>Solution</summary>

```java
import dev.jeddy.math.Packing;

public class Oct {
    public static void main(String[] args) {
        java.util.Random r = new java.util.Random(9);
        float[] n = new float[3];
        double worst = 0, sum = 0;
        int count = 1_000_000;
        for (int i = 0; i < count; i++) {
            double x = r.nextGaussian(), y = r.nextGaussian(), z = r.nextGaussian();
            double l = Math.sqrt(x * x + y * y + z * z);
            float fx = (float) (x / l), fy = (float) (y / l), fz = (float) (z / l);
            Packing.unpackOctahedral16(Packing.packOctahedral16(fx, fy, fz), n, 0);
            double ax = fx, ay = fy, az = fz; // the input, widened: everything below is double
            double d = ax * n[0] + ay * n[1] + az * n[2];
            double cx = ay * n[2] - az * n[1];
            double cy = az * n[0] - ax * n[2];
            double cz = ax * n[1] - ay * n[0];
            double deg = Math.toDegrees(Math.atan2(Math.sqrt(cx * cx + cy * cy + cz * cz), d));
            sum += deg;
            worst = Math.max(worst, deg);
        }
        System.out.println("mean " + sum / count + " worst " + worst);
    }
}
```

Run it with the module on the class path
(`java -cp modules/math/build/classes/java/main Oct.java`). On the sandbox it printed a mean of
0.0013° and a worst case of 0.0037°. An earlier version that took `Math.acos` of a dot product
computed in `float` reported a worst case of about 0.04°: that was the measuring error of `acos` near
1, not the encoding's.

</details>

**10. Watch escape analysis work, and not work.** Write a program that calls `Vec3f.cross` and
`Mat4f.mul` in loops of a million calls, eight rounds, and prints the bytes allocated per call each
round (with `com.sun.management.ThreadMXBean.getThreadAllocatedBytes`). Run it normally and with
`-XX:-DoEscapeAnalysis`.

<details><summary>Hint</summary>

Put each loop in its own static method so C2 compiles it as a unit, keep a running sum of the results
so the work is used, and read the counter before and after each loop. The first rounds run before C2
has compiled the loops.

</details>

<details><summary>Solution</summary>

```java
import dev.jeddy.math.*;
import java.lang.management.ManagementFactory;

public class Escape {
    static final com.sun.management.ThreadMXBean MX =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    static float crossLoop(Vec3f[] vs, int n) {
        float s = 0;
        for (int i = 0; i < n; i++) {
            Vec3f c = vs[i & 7].cross(vs[(i + 1) & 7]); // a new Vec3f per call
            s += c.x() + c.y() + c.z();
        }
        return s;
    }

    static float mulLoop(Mat4f[] ms, int n) {
        float s = 0;
        for (int i = 0; i < n; i++) {
            s += ms[i & 7].mul(ms[(i + 1) & 7]).m30(); // three float[16] and a Mat4f per call
        }
        return s;
    }

    public static void main(String[] args) {
        Vec3f[] vs = new Vec3f[8];
        Mat4f[] ms = new Mat4f[8];
        for (int i = 0; i < 8; i++) {
            vs[i] = new Vec3f(i, i + 1, i + 2);
            ms[i] = Mat4f.translation(i, 1, 2);
        }
        long tid = Thread.currentThread().threadId();
        float sink = 0;
        int n = 1_000_000;
        for (int round = 0; round < 8; round++) {
            long b0 = MX.getThreadAllocatedBytes(tid);
            sink += crossLoop(vs, n);
            long b1 = MX.getThreadAllocatedBytes(tid);
            sink += mulLoop(ms, n);
            long b2 = MX.getThreadAllocatedBytes(tid);
            System.out.printf("round %d: cross %.1f B/call, mul %.1f B/call%n",
                    round, (b1 - b0) / (double) n, (b2 - b1) / (double) n);
        }
        System.out.println(sink);
    }
}
```

On the sandbox, in two runs, rounds 0 and 1 showed between 3.7 and 12.4 bytes per `cross` call (some
calls still interpreted or in C1) and between 178 and 195 for `mul`; from round 2 on, 0.0 and 160.0.
Running with `-Xint` (interpreter only) shows the full 24 and 312 bytes in every round. With `-XX:-DoEscapeAnalysis`: 24 and
312 in every round. The summary is §14.3's table. Your numbers for the early rounds will differ (they
depend on when compilation finishes); the steady state should match.

</details>

**11. The two lerps.** Write a program that compares `a + (b - a) * t` with `(1 - t) * a + t * b` in
`float`: (a) at $t = 1$ with $a = 10^8$, $b = 1$; (b) with $a = b$ and random $t$; (c) for monotonicity,
stepping $t$ through consecutive floats with `Math.nextUp`.

<details><summary>Hint</summary>

For (c), keep the previous result and count how often the new one is smaller while $t$ grows; use
$a = 1.1$, $b = 7.3$ and $t \in [0, 0.01]$.

</details>

<details><summary>Solution</summary>

```java
import java.util.Random;

public class Lerp {
    public static void main(String[] args) {
        System.out.println(1e8f + (1f - 1e8f) * 1f);            // (a) first form at t = 1
        System.out.println((1 - 1f) * 1e8f + 1f * 1f);          // (a) second form at t = 1
        Random r = new Random(1);
        int missesA = 0;
        for (int i = 0; i < 10_000_000; i++) {                  // (b) a == b
            float a = r.nextFloat() * 10, t = r.nextFloat();
            if ((1 - t) * a + t * a != a) {
                missesA++;
            }
            r.nextFloat(); // keeps the random sequence of the run quoted below
        }
        System.out.println("second form missed a: " + missesA);
        float a = 1.1f, b = 7.3f;                               // (c) monotonicity
        int down1 = 0, down2 = 0;
        float prev1 = -1, prev2 = -1;
        for (float t = 0; t <= 0.01f; t = Math.nextUp(t)) {
            float v1 = a + (b - a) * t;
            float v2 = (1 - t) * a + t * b;
            if (v1 < prev1) down1++;
            if (v2 < prev2) down2++;
            prev1 = v1;
            prev2 = v2;
        }
        System.out.println("decreases: first form " + down1 + ", second form " + down2);
    }
}
```

(a) `1e8f + (1f - 1e8f) * 1f` is 0.0, `(1 - 1f) * 1e8f + 1f * 1f` is 1.0. (b) Over 10,000,000 random
$a \in [0, 10)$ and $t \in [0, 1)$, the first form returns `a` every time (because `a - a` is exactly 0),
while the second missed in 356,822 cases (3.6 %), for example $a = 3.4647238$, $t = 0.91959304$ gives
3.464724. (c) Over every `float` $t$ in $[0, 0.01]$ the first form never decreased; the second decreased
92,275 times. A variant that also checked the range found neither form leaving the interval
$[\min(a, b), \max(a, b)]$ for those 10,000,000 random pairs (values in $[0, 10)$, $t$ in $[0, 1)$) —
a property of that sample, not a guarantee: part (a) shows the first form returning 0, outside
$[1, 10^8]$, at $t = 1$. That is why
§29.3 says the engine's choice trades exactness at $t = 1$ for monotonicity and exactness when the ends
are equal.

</details>

**12. Half-float UV error.** Write a test that encodes $u = k/4096$ for every $k$ in $[0, 4096)$ with
`Packing.toHalf`, decodes it, and reports the largest error in texels of a 4096-wide texture. Then repeat
for $u + 3$ (a tiling coordinate).

<details><summary>Hint</summary>

In $[0.5, 1)$ the spacing is $2^{-11}$ (§13.1), so the worst rounding error is half of it, $2^{-12}$:
one texel at 4096. Above 2 the spacing is larger.

</details>

<details><summary>Solution</summary>

```java
        for (float offset : new float[] {0, 3}) {
            double worst = 0;
            for (int k = 0; k < 4096; k++) {
                float u = k / 4096f + offset;
                float back = Packing.fromHalf(Packing.toHalf(u));
                worst = Math.max(worst, Math.abs((double) back - u) * 4096);
            }
            System.out.println(offset + ": " + worst + " texels");
        }
```

It prints 1.0 texel for $[0, 1)$ (reached at $k = 2049$, an odd multiple of $2^{-12}$ that lies exactly
halfway between two halves) and 4.0 texels for $[3, 4)$, where the spacing is $2^{-9}$ and the worst
error $2^{-10}$: four times worse, as §13.1 predicted.

</details>

**13. Benchmark 3×4 against 4×4.** Add a `@Benchmark` to `MathBench` that multiplies two 3×4 transforms
with `AffineOps.mul`, next to `mat4Mul`. Run `./gradlew :bench:jmh -Pjmh.includes=MathBench` and compare.
Add `"-Pjmh.args=-prof gc"` to check that neither allocates.

<details><summary>Hint</summary>

Return a value from the output array (as `mat4Mul` returns `out[15]`) so the JIT cannot remove the
work. Prediction, not a measurement: the 3×4 product should be somewhat faster, 36 multiplications
against 64 (§8.5), though at this size loads and stores matter as much as arithmetic.

</details>

<details><summary>Solution</summary>

```java
    private final float[] a12 = new float[12];
    private final float[] b12 = new float[12];
    private final float[] out12 = new float[12];

    /** One 3×4 affine multiply. */
    @Benchmark
    public float affineMul() {
        AffineOps.mul(a12, 0, b12, 0, out12, 0);
        return out12[11];
    }
```

Fill `a12` and `b12` with random values in `setup()` as the class does for `a` and `b`. Record your
numbers next to the baseline's 14.7 ns for `mat4Mul`; `-prof gc` should report
`gc.alloc.rate.norm` of about 0 B/op for both.

</details>

**14. Mirrored instances (a real fix).** DESIGN §9.2's known limitation (§9.4): mirrored instances lose
their front faces to back-face culling. Plan the fix: where would you compute "this instance mirrors"
on the CPU, how would it reach the sort key, and which GL state would the draw set?

<details><summary>Hint</summary>

The CPU already has the world transform in `RenderPacket`; exercise 5's determinant tells you the sign.
The sort key (chapter [06](06-renderer-architecture.md)) decides which instances share a draw; one bit
in it keeps mirrored and normal instances apart. The state is `glFrontFace`.

</details>

<details><summary>Solution</summary>

A sketch, not the only answer: when `DrawBuilder` builds keys, compute the determinant of each visible
instance's linear part (§24.3's cofactors, nine multiplications) and set a "mirrored" bit in the key, so
sorting puts mirrored instances into their own runs. When a run starts, set `glFrontFace(GL_CW)` if its
bit is set and `GL_CCW` otherwise (through the state cache, so the call happens only on a change). The
shaders need no change: `jeddyMirrored` already fixes the tangent frame. Prove it with a GPU test that
draws a one-sided quad with scale $(-1, 1, 1)$ and reads back a pixel that must be covered. This is the
warm-up task in [next-milestones](../next-milestones.md#warm-up-tasks).

</details>

## 33. Glossary

- **Affine transform:** a linear part plus a translation, $\mathbf{p}' = A\mathbf{p} + \mathbf{t}$;
  stored as a row-major 3×4 in Jeddy (§8.1, §12.2).
- **AABB (axis-aligned bounding box):** a box with edges along X, Y, Z, stored as min and max corners
  (§8.8).
- **Adjugate, cofactor matrix:** the matrix equal to $`(\det A)\,A^{-1}`$; its transpose, whose entries
  (the cofactors) are signed 2×2 determinants (§10.4).
- **Anti-commutative:** swapping the operands negates the result, as for the cross product (§6.4).
- **Arvo's method:** transforming a box with 18 multiplications by taking per-term minimums and
  maximums (§8.8).
- **Basis vectors:** the unit vectors along the axes, $`\mathbf{e}_x, \mathbf{e}_y, \mathbf{e}_z`$ (§7.2).
- **Change of basis:** converting coordinates between frames: axes as columns (local → world), as rows
  (world → local) (§7.5).
- **Column-major, row-major:** storing a matrix column after column, or row after row (§12).
- **Column vector convention:** vectors multiply on the right, $M\mathbf{v}$; products read right to
  left (§7.9).
- **Cosine, sine, radian:** the x and y of the point at angle $\theta$ on the unit circle; the angle
  measured as arc length (§4).
- **Cross product:** the vector perpendicular to two vectors, with length equal to their parallelogram's
  area (§6).
- **Damp:** frame-rate independent exponential smoothing, $\operatorname{lerp}(x^\ast, x, e^{-\lambda
  \Delta t})$ (§3.6).
- **Determinant:** the signed factor by which a matrix scales areas or volumes; negative for mirrors
  (§9).
- **Dot product:** $`\sum a_i b_i = \lVert \mathbf{a} \rVert \lVert \mathbf{b} \rVert \cos\theta`$ (§5).
- **Escape analysis:** the JIT's proof that an object never leaves a method, which lets it skip the
  allocation (§14.3).
- **Frustum:** the six-plane volume a camera sees (§25).
- **Gram–Schmidt:** making a vector perpendicular to another by subtracting its projection (§5.3).
- **Half float (binary16):** a 16-bit float, 5 exponent and 10 fraction bits (§13.1).
- **Homogeneous coordinates:** $(x, y, z, w)$, with $w = 1$ for points and 0 for directions (§8.2–8.3).
- **Identity matrix:** the matrix that changes nothing (§7.8).
- **Inverse, singular:** the transform that undoes another; a matrix with determinant 0 has none
  (§10).
- **Inverse transpose (normal matrix):** $M^{-T}$, the transform for normals (§11.1).
- **Lerp, inverse lerp, remap, smoothstep:** interpolation and its relatives (§3.5).
- **Linear map:** a function that respects addition and scaling; a matrix (§7.2).
- **Monotonic:** never decreasing as its input increases (§3.4, §29.3).
- **Normal:** a unit vector perpendicular to a surface (§11).
- **Normalize, unit vector:** scale to length 1; a vector of length 1 (§3.3).
- **Octahedral encoding:** a unit vector stored as two numbers via the octahedron $|x| + |y| + |z| = 1$
  (§13.3).
- **Orthonormal:** unit-length, mutually perpendicular columns; a rotation (§7.3, §10.3).
- **Plane, signed distance, half-space:** $\hat{\mathbf{n}} \cdot \mathbf{p} + d = 0$; that expression
  for any point; the two sides (§5.4).
- **Point, vector:** a location; a displacement (§2.3).
- **Projection (of a vector):** its signed length along a unit direction, $\mathbf{a} \cdot
  \hat{\mathbf{n}}$ (§5.3).
- **Pythagoras' theorem:** $a^2 + b^2 = c^2$ in a right-angled triangle (§3.2).
- **Ray:** the points $\mathbf{o} + t\mathbf{d}$, $t \ge 0$ (§25).
- **Record:** a Java class declared by its components, with generated accessors and `equals` (§14.3).
- **Right-handed:** axes with $\mathbf{x} \times \mathbf{y} = \mathbf{z}$ (§2.4).
- **Snorm, unorm:** integers that stand for $[-1, 1]$ or $[0, 1]$ (§13.2).
- **SSBO, std140, std430:** a shader storage buffer; the GLSL layout rules for uniform and storage
  blocks (§12).
- **Struct of arrays (SoA):** one array per component (§14.5, Lesson 3).
- **Transpose:** rows and columns swapped (§7.8).
- **TRS:** translate × rotate × scale, the usual object transform (§8.6).
- **Triple product:** $\mathbf{a} \cdot (\mathbf{b} \times \mathbf{c})$, a signed volume (§9.2).
- **Vertex fetch:** the GPU step that reads vertex attributes and converts them to floats (§13.4).
- **Winding, back-face culling:** the order of a triangle's corners on screen; discarding triangles seen
  from behind (§9.4).

## 34. Further reading

- Fletcher Dunn and Ian Parberry, *3D Math Primer for Graphics and Game Development*. Vectors,
  matrices, linear transformations and their geometric meaning, at the level of this chapter.
- Eric Lengyel, *Foundations of Game Engine Development, Volume 1: Mathematics*. Compact and rigorous;
  its treatment of normals, the adjugate and homogeneous coordinates matches the engine closely.
- Tomas Akenine-Möller et al., *Real-Time Rendering* (4th edition), chapter 4 "Transforms": normal
  transforms, inverse computation and the conventions used in graphics.
- Jason Gregory, *Game Engine Architecture*, the 3D math chapter: an engine programmer's view of the
  same material.
- James Arvo, "Transforming Axis-Aligned Bounding Boxes", *Graphics Gems* (1990): the method in
  `AffineOps.transformAabb`.
- Zina H. Cigolle et al., "A Survey of Efficient Representations for Independent Unit Vectors",
  *Journal of Computer Graphics Techniques* (2014): octahedral encoding and its alternatives.
- David Goldberg, "What Every Computer Scientist Should Know About Floating-Point Arithmetic" (1991).
- The glTF 2.0 specification (Khronos), on coordinate systems, units, matrices and winding:
  https://registry.khronos.org/glTF/specs/2.0/glTF-2.0.html
- LearnOpenGL, "Transformations": https://learnopengl.com/Getting-started/Transformations
- JEP 401, Value Classes and Objects: https://openjdk.org/jeps/401 and JEP 306, Restore Always-Strict
  Floating-Point Semantics: https://openjdk.org/jeps/306

---

*Code this chapter builds or quotes: `math.Vec3f`, `Vec2f`, `Vec4f`, `MVec3f`, `MathF`, `Vec3Ops`,
`Mat3f`, `Mat4f`, `Mat4Ops`, `MMat4f`, `AffineOps`, `Planef`, `Ray3f`, `Spheref`, `Aabb3f`, `Capsulef`,
`Intersect.closestPointOnSegment`, `Intersect.coneSphere`, `CullOps.spheres`, `Packing`, `IsoCamera`,
the tests `MatrixAndQuatTest` and `GeometryAndUtilTest`, `bench.MathBench`; `render`'s
`engine/instance.glsl`, `engine/brdf.glsl`, `pbr/pbr.vert`, `pbr/pbr.frag`, `MeshRegistry`,
`SceneRenderer.writeFrameConstants`; `app`'s `World.maxScale` and `World.pick`; `animation`'s
`Skeleton`. Decisions: D4, D5, D18, D21, D27, D28, D30, D34, D77 in [DESIGN §17](../DESIGN.md); the
design in DESIGN §8. Related: [Lesson 0](lessons/00-foundations.md) (notation, floats, bits),
[Lesson 1](lessons/01-buffers-in-flight.md) (how instance records reach the GPU),
[Lesson 3](lessons/03-data-layout.md) (struct of arrays, the JIT), chapters
[03](03-rotations-and-transforms.md) (rotations, hierarchies),
[04](04-cameras-and-projection.md) (projection, culling) and [09](09-animation-and-skinning.md)
(skinning).*

[← 01 Architecture](01-architecture.md) · [Index](README.md) · [03 Rotations and transforms →](03-rotations-and-transforms.md)
