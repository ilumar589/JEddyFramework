# 03 — Rotations, quaternions and transform hierarchies

*Moving a thing is easy: add a vector. Making it bigger is easy: multiply by a number. Turning it
is where 3D math gets hard. Turns do not commute, the obvious way to describe them (three angles)
breaks down at certain orientations, and the description engines actually use, the quaternion,
looks like magic until you build it. This chapter builds it from nothing: angles and sines first,
then rotations in a plane, complex numbers, rotations in space, and finally quaternions, derived
line by line and written as the engine's `Quatf`. Then it follows rotations through the engine:
into the 3×4 matrices the GPU reads, into an 8-byte vertex attribute that stores a whole tangent
frame, down a tree of parents and children in `TransformStore`, and between two simulation ticks.
It ends with the tests that pin all of it down and the numbers that show what each shortcut
costs.*

**Before you start.** You need [Lesson 0](lessons/00-foundations.md): the notation of §2
(functions, powers, roots), bit masks of §6 (for the dirty flags), how a `float` is stored and why
it rounds (§7), the heap and the zero-allocation rule (§8.2), threads and handing work between them
(§9.4–9.5), and what a shader, a buffer and a frame are (§10–§11). You also need chapter
[02 Math foundations](02-math-foundations.md): vectors, the dot and cross products, the length of a
vector, matrices as linear maps whose columns are the images of the axes, matrix products and why
their order matters, homogeneous coordinates, the row-major 3×4 layout, TRS (translation × rotation
× scale), parent × local, determinants and mirroring, transforming normals, Gram–Schmidt and the
`snorm16` encoding. Every one of these is used here and none is re-taught. Lesson
[3](lessons/03-data-layout.md) (handles, sparse sets, swap-removal) helps for the transform store,
and lesson [1](lessons/01-buffers-in-flight.md) (the frame ring and instance records) for the last
step. No trigonometry is assumed: §2 starts from what an angle is.

**What you will build**

| Piece | File | What it does |
|---|---|---|
| Angle helpers | [`MathF`](../../modules/math/src/main/java/dev/jeddy/math/MathF.java) | degrees ↔ radians, wrapping an angle to the short way round |
| The quaternion | [`Quatf`](../../modules/math/src/main/java/dev/jeddy/math/Quatf.java) | an immutable rotation: build, multiply, invert, rotate a vector, interpolate, compare, convert from a matrix |
| Scratch and batch versions | [`MQuatf`](../../modules/math/src/main/java/dev/jeddy/math/MQuatf.java), [`QuatOps`](../../modules/math/src/main/java/dev/jeddy/math/QuatOps.java) | the same operations without allocation, one object or whole arrays |
| Quaternion → matrix | [`AffineOps.composeTRS`](../../modules/math/src/main/java/dev/jeddy/math/AffineOps.java), [`Mat4f.trs`](../../modules/math/src/main/java/dev/jeddy/math/Mat4f.java) | translation, rotation and scale into the 3×4 the GPU reads |
| Matrix → TRS | [`GltfImporter.decompose`](../../tools/cooker/src/main/java/dev/jeddy/cooker/gltf/GltfImporter.java) | splits a glTF node matrix, mirrors included |
| The QTangent | [`MeshEncoding`](../../modules/asset/src/main/java/dev/jeddy/asset/format/MeshEncoding.java), [`vertex.glsl`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/engine/vertex.glsl), [`pbr.vert`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/pbr/pbr.vert) | a vertex's normal, tangent and handedness in four 16-bit numbers, and their decoding on the GPU |
| The hierarchy | [`TransformStore`](../../modules/scene/src/main/java/dev/jeddy/scene/TransformStore.java) | parents and children, world transforms recomputed only where something moved, level by level, in parallel |
| Flattening | [`World.compose`](../../modules/app/src/main/java/dev/jeddy/app/world/World.java) | composes a cooked scene's node tree into a few transforms at spawn time |
| Between ticks | [`FixedStepper`](../../modules/core/src/main/java/dev/jeddy/core/time/FixedStepper.java), `World.beginTick`/`extract`, [`RenderPacket`](../../modules/render/src/main/java/dev/jeddy/render/RenderPacket.java), [`DrawBuilder`](../../modules/render/src/main/java/dev/jeddy/render/draw/DrawBuilder.java) | the fixed 30 Hz tick and the interpolated transforms the GPU draws |

The conventions are chapter 02's: right-handed, Y up, column vectors, angles in radians,
quaternions stored as `(x, y, z, w)` with `w` the scalar part, and `a.mul(b)` applies `b` first.

**Contents**

- Part A — The ideas
  1. [What makes rotation hard](#1-what-makes-rotation-hard)
  2. [Angles: degrees, radians, sine and cosine](#2-angles-degrees-radians-sine-and-cosine)
  3. [Rotation in the plane](#3-rotation-in-the-plane)
  4. [Complex numbers: rotation as multiplication](#4-complex-numbers-rotation-as-multiplication)
  5. [Rotations about the three axes](#5-rotations-about-the-three-axes)
  6. [Euler angles and gimbal lock](#6-euler-angles-and-gimbal-lock)
  7. [Any rotation is one turn about one axis](#7-any-rotation-is-one-turn-about-one-axis)
  8. [Quaternions](#8-quaternions)
  9. [Rotating a vector with fewer multiplications](#9-rotating-a-vector-with-fewer-multiplications)
  10. [Quaternions and matrices](#10-quaternions-and-matrices)
  11. [Interpolating rotations](#11-interpolating-rotations)
  12. [A tangent frame in one quaternion](#12-a-tangent-frame-in-one-quaternion)
  13. [Transform hierarchies](#13-transform-hierarchies)
  14. [Fixed ticks and interpolation](#14-fixed-ticks-and-interpolation)
- Part B — Building it
  15. [Step 1: the quaternion record](#15-step-1-the-quaternion-record)
  16. [Step 2: a mutable quaternion and batch kernels](#16-step-2-a-mutable-quaternion-and-batch-kernels)
  17. [Step 3: from quaternion to matrix](#17-step-3-from-quaternion-to-matrix)
  18. [Step 4: from matrix to quaternion](#18-step-4-from-matrix-to-quaternion)
  19. [Step 5: splitting node matrices in the cooker](#19-step-5-splitting-node-matrices-in-the-cooker)
  20. [Step 6: encoding a QTangent](#20-step-6-encoding-a-qtangent)
  21. [Step 7: decoding a QTangent, in Java and on the GPU](#21-step-7-decoding-a-qtangent-in-java-and-on-the-gpu)
  22. [Step 8: the transform store's data](#22-step-8-the-transform-stores-data)
  23. [Step 9: adding, removing and reparenting](#23-step-9-adding-removing-and-reparenting)
  24. [Step 10: local transforms and dirty marks](#24-step-10-local-transforms-and-dirty-marks)
  25. [Step 11: the traversal order](#25-step-11-the-traversal-order)
  26. [Step 12: update and the level kernel](#26-step-12-update-and-the-level-kernel)
  27. [Step 13: flattening a scene at spawn time](#27-step-13-flattening-a-scene-at-spawn-time)
  28. [Step 14: interpolating between ticks](#28-step-14-interpolating-between-ticks)
- Part C — Using it and proving it
  29. [Where the engine turns things](#29-where-the-engine-turns-things)
  30. [The tests, walked through](#30-the-tests-walked-through)
  31. [Measured numbers](#31-measured-numbers)
  32. [Things to run](#32-things-to-run)
  33. [Exercises](#33-exercises)
  34. [Glossary](#34-glossary)
  35. [Further reading](#35-further-reading)

---

# Part A — The ideas

## 1. What makes rotation hard

Every entity in the engine has a **transform**: where it is, which way it is turned, and how big it
is. `TransformStore` keeps the three parts separately, in arrays: a position (three `float`s), a
rotation (four `float`s, a quaternion) and a scale (three `float`s). Chapter 02 showed how the three
combine into one 3×4 matrix, $M = T\,R\,S$, and treated the rotation $R$ as "some 3×3 matrix with
special properties". This chapter is about that $R$: what it is, how to build it from things a game
programmer can say ("turn 30° to the left", "look at that door"), how to store it, how to combine two
of them, and how to blend between them.

Three facts make this harder than position and scale:

- **Order matters.** Turn a camera that looks straight ahead (down $-z$) by 90° about the sideways
  ($x$) axis and then by 90° about the vertical ($y$) axis, and it ends up looking up. Do the same
  two turns in the other order and it looks to the left (§8.8 computes both). Positions do not care
  about order: moving 1 m east then 1 m north lands where moving north then east does.
- **The obvious description breaks.** Three angles ("yaw, pitch, roll") describe any orientation,
  but at some orientations two of the three angles do the same thing and a whole direction of
  turning is lost (§6).
- **Blending is not averaging.** Halfway between "facing north" and "facing east" is "facing
  north-east". Averaging the numbers of two rotation matrices does not give a rotation at all
  (§14.3), and averaging angles goes wrong across the 360° seam (§2.6).

The answer the engine uses is the **quaternion**, a four-part number that multiplies like a rotation,
inverts for free, rotates a vector with 18 multiplications, never loses a direction of turning, and
blends with one line of arithmetic. Getting there takes some groundwork: angles (§2), rotations in a
flat plane (§3), the complex numbers that rotate a plane by multiplying (§4), and rotations in space
(§5–§7). Quaternions start in §8.

## 2. Angles: degrees, radians, sine and cosine

### 2.1 Measuring a turn

An **angle** measures how far something has turned about a point. Picture an arrow pinned at its
tail, lying along the $x$ axis, and turn it. A quarter of the way round, it points along $y$; half
way, along $-x$; a whole turn brings it back.

By a convention as old as Babylon, a whole turn is **360 degrees** (360°): a quarter turn is 90°, a
half turn 180°. Mathematics and the engine count **counter-clockwise as positive** in the usual
picture with $x$ to the right and $y$ up, so turning by $-90°$ is a quarter turn clockwise.

### 2.2 Radians

Degrees are arbitrary: nothing about a circle says 360. A natural unit falls out of the circle
itself. Draw a circle of radius 1 around the pivot. As the arrow turns, its tip walks along the
circle. The **radian** measure of the angle is the length of that walk:

```
                    y
                    |   . tip after turning by θ
                    |  /   ← the arc walked along the circle has length θ
                    | /  )  (on a circle of radius 1)
         -----------+----*------ x
                    |    (1, 0): where the tip starts
```

The number $\pi$ (pi, about 3.14159) is the ratio of any circle's circumference to its diameter, so a
circle of radius $r$ has circumference $2\pi r$, and a whole turn on the unit circle walks
$2\pi \approx 6.2832$. A whole turn is $2\pi$ radians, and converting is a proportion:

$$\theta_{\text{radians}} = \theta_{\text{degrees}} \cdot \frac{\pi}{180}, \qquad \theta_{\text{degrees}} = \theta_{\text{radians}} \cdot \frac{180}{\pi}$$

| Degrees | 0 | 30 | 45 | 60 | 90 | 180 | 360 |
|---|---|---|---|---|---|---|---|
| Radians | 0 | $\pi/6 \approx 0.5236$ | $\pi/4 \approx 0.7854$ | $\pi/3 \approx 1.0472$ | $\pi/2 \approx 1.5708$ | $\pi \approx 3.1416$ | $2\pi \approx 6.2832$ |

One radian is $180/\pi \approx 57.2958°$, a bit less than a sixth of a turn.

The engine uses radians everywhere (chapter 02's conventions), for three reasons:

- **Java's trigonometry takes radians.** `Math.sin`, `Math.cos` and `Math.atan2` all work in radians,
  so degrees would need a conversion at every call.
- **Rates come out in natural units.** A character turning at 10 radians per second turns $10 \cdot
  \Delta t$ radians in a tick of $\Delta t$ seconds, and a point at distance $r$ from the pivot moves
  $r \cdot \theta$ along its arc. `AgentSync`'s default turn rate is exactly that: 10 rad/s.
- **Small angles become simple** (§2.4).

[`MathF`](../../modules/math/src/main/java/dev/jeddy/math/MathF.java) has the constants and the two
conversions for code that starts from degrees (an editor field, a designer's table); the block shows
the constants from the top of the file and the two conversions from further down:

```java
    /** π. */
    public static final float PI = (float) Math.PI;

    /** 2π. */
    public static final float TAU = (float) (2 * Math.PI);

    /** π / 2. */
    public static final float HALF_PI = (float) (Math.PI / 2);

    /** Converts degrees to radians. */
    public static float toRadians(float degrees) {
        return degrees * (PI / 180f);
    }

    /** Converts radians to degrees. */
    public static float toDegrees(float radians) {
        return radians * (180f / PI);
    }
```

`Math.PI` is a `double`; the casts round it to the nearest `float`. These are compile-time
constants, so `javac` computes them once and writes the results into the code that uses them, and the
brackets around `PI / 180f` make that quotient a constant too: a conversion costs one multiplication. `IsoCamera` uses it for its defaults: `pitch = MathF.toRadians(55)` is 0.95993.

### 2.3 Sine and cosine

Turn the unit arrow (the one of length 1 along $x$) by an angle $\theta$. Its tip lands at some point
of the unit circle. The two coordinates of that point have names:

$$\text{tip} = (\cos\theta,\; \sin\theta)$$

**Cosine** ($\cos$) is the $x$ coordinate of the tip, **sine** ($\sin$) its $y$ coordinate. That is
the whole definition.

```
            y
            |        tip = (cos θ, sin θ)
            |       *
            |     / |
            |   /   | sin θ
            | / θ   |
    --------+-------+-------- x
            |cos θ
```

Drop a vertical line from the tip and you get a right triangle with a hypotenuse of length 1, a
horizontal side $\cos\theta$ and a vertical side $\sin\theta$. That is the school definition
("adjacent over hypotenuse", "opposite over hypotenuse") for a hypotenuse of 1. For an arrow of
length $r$ everything scales: a point at distance $r$ and angle $\varphi$ from the $x$ axis is

$$(x, y) = (r\cos\varphi,\; r\sin\varphi)$$

which is called the **polar form** of the point.

Some values follow from the definition and simple geometry:

- $\theta = 0$: the tip has not moved, $(\cos 0, \sin 0) = (1, 0)$. A quarter turn, $\pi/2$: $(0,
  1)$. A half turn, $\pi$: $(-1, 0)$. Three quarters, $3\pi/2$: $(0, -1)$.
- $\theta = 45°$: the tip is on the diagonal, so $x = y$, and it is on the unit circle, so $x^2 + y^2
  = 1$ (Pythagoras, which is chapter 02's length of a vector). Then $2x^2 = 1$ and $\cos 45° = \sin
  45° = \sqrt{1/2} \approx 0.7071$.
- $\theta = 30°$: turn one unit arrow by $+30°$ and another by $-30°$. The two arrows and the line
  joining their tips form a triangle with two sides of length 1 and 60° between them. Its other two
  angles are equal (the triangle is symmetric) and the three add up to 180°, so each is 60° too: the
  triangle is equilateral, and the line joining the tips also has length 1. That line is vertical,
  from height $-\sin 30°$ to $+\sin 30°$, so $2\sin 30° = 1$ and $\sin 30° = 0.5$. Pythagoras gives
  $\cos 30° = \sqrt{1 - 0.25} \approx 0.8660$. A 60° turn swaps them (mirror the picture across the
  diagonal): $\cos 60° = 0.5$, $\sin 60° \approx 0.8660$.

The sign tells the quadrant: $\cos$ is negative when the tip is left of the $y$ axis (angles
between 90° and 270°), $\sin$ is negative below the $x$ axis.

In Java, `Math.sin(double)` and `Math.cos(double)` take radians and return `double`; the engine's
code writes `(float) Math.sin(h)` to round the result to the `float` it stores. They cost far more
than a multiplication (they evaluate a polynomial after reducing the angle), which is one reason the
engine stores rotations as quaternions and not as angles: building a rotation from an angle needs
sines and cosines, but combining and applying quaternions needs only multiplications and additions.

### 2.4 Four facts you will use

1. **The Pythagorean identity.** The tip is at distance 1 from the pivot, so for every $\theta$:

   $$\cos^2\theta + \sin^2\theta = 1$$

   ($\cos^2\theta$ means $(\cos\theta)^2$.) Check: $0.8660^2 + 0.5^2 = 0.75 + 0.25 = 1$.
2. **Mirror.** Turning by $-\theta$ (clockwise) lands on the mirror image of the tip across the $x$
   axis: same $x$, opposite $y$.

   $$\cos(-\theta) = \cos\theta, \qquad \sin(-\theta) = -\sin\theta$$
3. **Whole and half turns.** A whole turn changes nothing; a half turn lands on the opposite point:

   $$\cos(\theta + 2\pi) = \cos\theta, \quad \sin(\theta + 2\pi) = \sin\theta, \qquad \cos(\theta + \pi) = -\cos\theta, \quad \sin(\theta + \pi) = -\sin\theta$$
4. **Small angles.** For a small angle the arc is almost straight and almost vertical, so its height
   is almost its length: $\sin\theta \approx \theta$ (in radians, which is why this works only in
   radians). From fact 1, $\cos\theta = \sqrt{1 - \sin^2\theta} \approx \sqrt{1 - \theta^2}$, and since
   $(1 - x/2)^2 = 1 - x + x^2/4 \approx 1 - x$ for small $x$, $\sqrt{1 - x} \approx 1 - x/2$:

   $$\sin\theta \approx \theta, \qquad \cos\theta \approx 1 - \frac{\theta^2}{2} \qquad (\theta \text{ small, in radians})$$

   For $\theta = 0.1$: $\sin 0.1 = 0.09983$ against 0.1, and $\cos 0.1 = 0.995004$ against 0.995.
   §11.2 and §15.6 use this to judge tolerances.

### 2.5 From a direction back to an angle: acos and atan2

Sometimes you have the cosine and want the angle back. **Arc cosine**, `Math.acos(c)`, returns the
angle in $[0, \pi]$ whose cosine is $c$: `Math.acos(0.5)` is $\pi/3$. Only half the circle is
returned because $\cos\theta = \cos(-\theta)$ (fact 2): the cosine alone cannot tell a turn to the
left from a turn to the right.

More often you have a direction $(x, y)$ and want its angle from the $x$ axis.
**`Math.atan2(y, x)`** returns it, in $[-\pi, \pi]$, using the signs of both coordinates to find the
quadrant. (Both ends name the same direction, straight left: `Math.atan2(0, -1)` is $+\pi$, and only a
"negative zero" $y$, as in `Math.atan2(-0.0, -1)`, gives $-\pi$; L00 §7.2's sign bit makes $-0.0$ a
value of its own.)

- `Math.atan2(1, 1)` is $\pi/4$ (45°): the diagonal up and to the right;
- `Math.atan2(1, -1)` is $3\pi/4$ (135°): up and to the left;
- `Math.atan2(-1, 0)` is $-\pi/2$: straight down.

Why not the older "arc tangent of $y/x$", `Math.atan(y / x)`? It sees only the ratio: the directions
$(1, -1)$ and $(-1, 1)$ both have $y/x = -1$, so it returns $-45°$ for both, which is wrong by 180° for
$(-1, 1)$ (up and to the left, 135°). And $x = 0$ divides by zero. `atan2` takes the two numbers separately, so it has neither problem. Note the
argument order, $y$ first; §5.3 explains why the engine's yaw calls it as `atan2(dx, dz)`.

### 2.6 Angles wrap around

The angles $\theta$, $\theta + 2\pi$, $\theta - 2\pi$ and so on name the same direction. That
matters as soon as you compare or blend two angles. A character facing 171.9° (3 radians) that must
turn to face $-171.9°$ ($-3$ radians) should turn 16.2° the short way, through 180°, not 343.8° the
long way round. The difference of the two numbers, $-3 - 3 = -6$, is the long way.

The fix is to **wrap** the difference into one turn centred on zero. `AgentSync.apply` does it
with a floor (L00 §2.5):

```java
            float d = target - yaw[a];
            d -= (float) (Math.PI * 2) * (float) Math.floor((d + Math.PI) / (Math.PI * 2));
```

Why it works: $d + \pi$ shifted by whole turns should land in $[0, 2\pi)$. The quotient
$(d + \pi) / 2\pi$ counts how many whole turns $d + \pi$ is above zero, the floor rounds that down to
a whole number $k$, and subtracting $2\pi k$ leaves $d + \pi$ in $[0, 2\pi)$, so $d$ in $[-\pi, \pi)$.
Worked: $d = -6$. $(-6 + 3.1416) / 6.2832 = -0.455$, whose floor is $-1$; $d - 2\pi \cdot (-1) = -6 +
6.2832 = 0.2832$ radians, 16.2°. The character turns 16.2° counter-clockwise.

`MathF` has the same tool as a function, built on `Math.IEEEremainder(a, b)`, which returns $a - nb$
for the whole number $n$ nearest to $a/b$:

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

$-6 / 2\pi = -0.955$, nearest whole number $-1$, so `IEEEremainder(-6, TAU)` is $-6 + 2\pi = 0.2832$
as well. The remainder can land on exactly $-\pi$; the second line moves that one value to $+\pi$, so
the result is in $(-\pi, \pi]$. (`AgentSync`'s version returns $[-\pi, \pi)$ instead: the two differ
only for a turn of exactly half a circle, where both directions are equally short.) `lerpAngle` is
"wrap the difference, then go a fraction $t$ of it": the short-way blend of two angles.

Single angles are the easy case. Rotations in space have the same wrap-around problem in a harder
form, and §11.4 solves it for quaternions.

<details><summary>Check: what are 135° and 0.25 rad in the other unit? What does <code>Math.atan2(-1, -1)</code> return? What is <code>MathF.wrapAngle(7f)</code>?</summary>

$135 \cdot \pi / 180 = 3\pi/4 \approx 2.3562$ rad; $0.25 \cdot 180/\pi \approx 14.32°$. `atan2(-1, -1)`
is $-3\pi/4$ ($-135°$): down and to the left. $7 / 2\pi = 1.114$, nearest whole number 1, so
`wrapAngle(7f)` is $7 - 6.2832 = 0.7168$ rad.
</details>

## 3. Rotation in the plane

### 3.1 Where the axes go

A rotation turns the whole plane about the origin by an angle $\theta$. We want its matrix: the
2×2 matrix $R(\theta)$ with $R(\theta)\,\mathbf{v}$ = "$\mathbf{v}$ turned by $\theta$".

Chapter 02 showed that a matrix is a **linear** map, one that respects addition and scaling, and
that such a map is completely described by where it sends the two axis vectors
$\mathbf{e}_x = (1, 0)$ and $\mathbf{e}_y = (0, 1)$: those images are its columns. A rotation is
linear: turning a whole picture turns the parallelogram that adds two arrows into the parallelogram
of the two turned arrows, and an arrow twice as long turns into an arrow twice as long. So we only
need two images.

**The first column.** $\mathbf{e}_x$ is the unit arrow along $x$. Turned by $\theta$, its tip is
$(\cos\theta, \sin\theta)$ by the definition of §2.3.

**The second column.** First, a quarter turn. Take any arrow $(a, b)$: it is $a$ steps along $x$
followed by $b$ steps along $y$. Turn the picture a quarter turn counter-clockwise: the $x$
direction becomes the $y$ direction, and the $y$ direction becomes the $-x$ direction. So the $a$
steps now go along $y$ and the $b$ steps along $-x$:

$$\text{quarter turn of } (a, b) = (-b,\; a)$$

Check with the axes: $(1, 0) \to (0, 1)$ and $(0, 1) \to (-1, 0)$. And with $(2, 1) \to (-1, 2)$,
which is still of length $\sqrt 5$ and is perpendicular to $(2, 1)$ (dot product $-2 + 2 = 0$).

Now, $\mathbf{e}_y$ is $\mathbf{e}_x$ after a quarter turn. Turning it by $\theta$ is turning
$\mathbf{e}_x$ by a quarter turn and then by $\theta$, which is the same as by $\theta$ and then a
quarter turn (two turns about the same point add up, in either order). $\mathbf{e}_x$ turned by
$\theta$ is $(\cos\theta, \sin\theta)$, and its quarter turn is $(-\sin\theta, \cos\theta)$. The two
columns give:

$$R(\theta) = \begin{bmatrix} \cos\theta & -\sin\theta \\ \sin\theta & \cos\theta \end{bmatrix}, \qquad R(\theta)\begin{bmatrix} x \\ y \end{bmatrix} = \begin{bmatrix} x\cos\theta - y\sin\theta \\ x\sin\theta + y\cos\theta \end{bmatrix}$$

A positive $\theta$ turns $x$ towards $y$: counter-clockwise in the usual picture. This one matrix
is the seed of every rotation in this chapter.

### 3.2 A worked rotation

Turn $(2, 1)$ by 30°, with $\cos 30° = 0.8660$ and $\sin 30° = 0.5$:

$$x' = 2 \cdot 0.8660 - 1 \cdot 0.5 = 1.2321, \qquad y' = 2 \cdot 0.5 + 1 \cdot 0.8660 = 1.8660$$

The length is unchanged: $\sqrt{1.2321^2 + 1.8660^2} = \sqrt{5.0000} = 2.2361 = \sqrt 5$, as it was.
The angle grew by 30°: `Math.atan2(1, 2)` is 26.57°, `Math.atan2(1.8660, 1.2321)` is 56.57°.

### 3.3 Turning twice: the angle-sum identities

Turning by $\beta$ and then by $\alpha$ is turning by $\alpha + \beta$. In matrices (chapter 02:
the right-hand factor acts first):

$$R(\alpha)\,R(\beta) = R(\alpha + \beta)$$

Multiply out the left side, row by column:

$$\begin{bmatrix} \cos\alpha & -\sin\alpha \\ \sin\alpha & \cos\alpha \end{bmatrix}\begin{bmatrix} \cos\beta & -\sin\beta \\ \sin\beta & \cos\beta \end{bmatrix} = \begin{bmatrix} \cos\alpha\cos\beta - \sin\alpha\sin\beta & \cdots \\ \sin\alpha\cos\beta + \cos\alpha\sin\beta & \cdots \end{bmatrix}$$

and compare the first column with the first column of $R(\alpha + \beta)$, which is
$(\cos(\alpha + \beta), \sin(\alpha + \beta))$:

$$\cos(\alpha + \beta) = \cos\alpha\cos\beta - \sin\alpha\sin\beta, \qquad \sin(\alpha + \beta) = \sin\alpha\cos\beta + \cos\alpha\sin\beta$$

These are the **angle-sum identities**, and we got them for free from the geometry. Check with
$\alpha = 30°$, $\beta = 60°$: $\cos 90° = 0.8660 \cdot 0.5 - 0.5 \cdot 0.8660 = 0$ and $\sin 90° = 0.5
\cdot 0.5 + 0.8660 \cdot 0.8660 = 0.25 + 0.75 = 1$. Replacing $\beta$ by $-\beta$ and using fact 2
gives the differences:

$$\cos(\alpha - \beta) = \cos\alpha\cos\beta + \sin\alpha\sin\beta, \qquad \sin(\alpha - \beta) = \sin\alpha\cos\beta - \cos\alpha\sin\beta$$

They also give back the polar picture: a point at angle $\varphi$, $(r\cos\varphi, r\sin\varphi)$,
turned by $\theta$ is $(r\cos\varphi\cos\theta - r\sin\varphi\sin\theta,\; r\cos\varphi\sin\theta +
r\sin\varphi\cos\theta) = (r\cos(\varphi + \theta),\; r\sin(\varphi + \theta))$. Rotating adds to the
angle and keeps the distance, which is what "rotation" means.

### 3.4 Half angles and double angles

Quaternions (§8) store half of the rotation angle, so we will often need to get from $\theta/2$ back
to $\theta$. Put $\alpha = \beta = \theta/2$ in the angle-sum identities:

$$\cos\theta = \cos^2\tfrac{\theta}{2} - \sin^2\tfrac{\theta}{2}, \qquad \sin\theta = 2\sin\tfrac{\theta}{2}\cos\tfrac{\theta}{2}$$

Replacing $\cos^2$ or $\sin^2$ with the Pythagorean identity gives two more forms of the first one:

$$\cos\theta = 1 - 2\sin^2\tfrac{\theta}{2} = 2\cos^2\tfrac{\theta}{2} - 1$$

Check with $\theta = 60°$: $1 - 2 \cdot 0.5^2 = 0.5 = \cos 60°$, and $2 \cdot 0.5 \cdot 0.8660 =
0.8660 = \sin 60°$. Read backwards, the last form says $\cos\frac{\theta}{2} = \sqrt{(1 +
\cos\theta)/2}$, which §10.2 uses to recover a quaternion from a matrix.

### 3.5 What a rotation keeps

Three properties of $R(\theta)$ carry over to every rotation in this chapter:

- **Lengths.** $\lVert R\mathbf{v} \rVert^2 = (x c - y s)^2 + (x s + y c)^2$ with $c = \cos\theta$,
  $s = \sin\theta$. Expand: $x^2 c^2 - 2xycs + y^2 s^2 + x^2 s^2 + 2xysc + y^2 c^2 = x^2(c^2 + s^2) +
  y^2(s^2 + c^2) = x^2 + y^2$. The cross terms cancel and the identity of fact 1 does the rest.
- **The inverse is the transpose.** Turning back is $R(-\theta)$, and by fact 2 that is
  $\begin{bmatrix} c & s \\ -s & c \end{bmatrix}$, which is $R(\theta)$ with rows and columns swapped:
  its **transpose** $R^T$. Chapter 02 shows the general reason: the columns of a rotation are
  perpendicular unit vectors (**orthonormal**), and for such a matrix $R^T R = I$.
- **No mirror.** The determinant is $c \cdot c - (-s) \cdot s = c^2 + s^2 = 1$. A mirror would have
  $-1$ (chapter 02, determinants and mirroring). A rotation never turns a left hand into a right
  hand, which §12 relies on.

## 4. Complex numbers: rotation as multiplication

The matrix of §3 works, but there is a second way to rotate a plane that leads straight to
quaternions. It needs a strange kind of number.

### 4.1 A number whose square is minus one

No ordinary number squared gives $-1$. Invent one: call it $i$, and declare $i^2 = -1$. A
**complex number** is $a + b\,i$, with ordinary numbers $a$ (the **real part**) and $b$ (the
**imaginary part**). Draw it as the point $(a, b)$: real part along $x$, imaginary part along $y$.

Adding complex numbers adds the points: $(2 + i) + (1 + 3i) = 3 + 4i$, just as $(2, 1) + (1, 3) =
(3, 4)$. Multiplying uses ordinary algebra (multiply everything out) plus the one new rule
$i^2 = -1$:

$$(a + bi)(c + di) = ac + ad\,i + bc\,i + bd\,i^2 = (ac - bd) + (ad + bc)\,i$$

Worked: $(2 + i)(3 + 2i) = 6 + 4i + 3i + 2i^2 = 6 + 7i - 2 = 4 + 7i$.

The **conjugate** of $z = a + bi$ flips the sign of the imaginary part, $\bar z = a - bi$ (the
mirror image across $x$), and $z\bar z = a^2 - abi + abi - b^2 i^2 = a^2 + b^2$: a plain number, the
squared length of the point. A complex number of length 1 is a **unit** complex number.

### 4.2 Multiplying by a unit complex number rotates

Take the unit complex number on the circle at angle $\theta$, $z_\theta = \cos\theta + i\sin\theta$,
and multiply the point $p = x + yi$ by it with the rule above ($a = \cos\theta$, $b = \sin\theta$,
$c = x$, $d = y$):

$$z_\theta\, p = (x\cos\theta - y\sin\theta) + (x\sin\theta + y\cos\theta)\,i$$

That is exactly $R(\theta)(x, y)$ from §3.1. **Multiplying by a unit complex number rotates the
plane.** Worked, with the example of §3.2: $(0.8660 + 0.5i)(2 + i) = (1.7321 - 0.5) + (0.8660 +
1)\,i = 1.2321 + 1.8660\,i$, the same point.

Everything about plane rotations now becomes arithmetic:

- **Composing** two rotations is multiplying their numbers: $z_\alpha z_\beta = z_{\alpha + \beta}$
  (multiply it out and you get the angle-sum identities again).
- **Undoing** a rotation is multiplying by the conjugate: $\bar z_\theta = \cos\theta - i\sin\theta =
  z_{-\theta}$, and $z_\theta \bar z_\theta = \cos^2\theta + \sin^2\theta = 1$.
- **Order does not matter** in the plane: $z_\alpha z_\beta = z_\beta z_\alpha$, because ordinary
  multiplication commutes. Turning by 30° then 60° is turning by 60° then 30°.
- **Storage** is two numbers for a rotation, against four for the 2×2 matrix.

### 4.3 What carries over to 3D

In the 1830s William Rowan Hamilton looked for the same thing in space: numbers with three parts
that multiply like rotations. Three parts never worked (later mathematicians proved they cannot), and
in 1843 he found that **four** can:
one real part and three imaginary units $i$, $j$, $k$. Those are the quaternions of §8. Three things
carry over from complex numbers unchanged:

- a rotation is a unit number;
- composing rotations is multiplying their numbers;
- undoing a rotation is taking the conjugate.

Three things change, and each will be derived when we get there:

- the product is **not commutative**, because rotations in space are not (§1, §8.2);
- a vector is not multiplied on one side but **sandwiched**, $q\,\mathbf{p}\,\bar q$ (§8.5);
- because of the sandwich, the number stores **half** the rotation angle (§8.6).

<details><summary>Check: compute <code>(3 + 4i)(0 + 1i)</code> and say what rotation it was. What is the conjugate of the rotation by 30°?</summary>

$(3 + 4i)\,i = 3i + 4i^2 = -4 + 3i$: the point $(3, 4)$ turned a quarter turn counter-clockwise, to
$(-4, 3)$, because $0 + 1i = \cos 90° + i\sin 90°$. The conjugate of $0.8660 + 0.5i$ is
$0.8660 - 0.5i = \cos(-30°) + i\sin(-30°)$: the rotation by $-30°$.
</details>

## 5. Rotations about the three axes

### 5.1 The right-hand rule

In space, a rotation needs an **axis**: the line it turns about. The simplest rotations turn about
one of the three coordinate axes. Which way is positive? Jeddy's coordinates are right-handed
(chapter 02), and the matching rule is the **right-hand rule**: point your right thumb along the
positive axis, and your fingers curl in the direction of a positive turn. Equivalently, look from the
positive end of the axis back towards the origin: a positive turn is counter-clockwise, exactly as in
the plane of §3.

A rotation about an axis moves only the two other coordinates, and §3's 2D rotation does that
part. The one thing to get right is which of the two other axes plays "$x$" and which plays "$y$".
Take them in cyclic order, $x \to y \to z \to x$:

- about **Z**, a positive turn takes $x$ towards $y$;
- about **X**, a positive turn takes $y$ towards $z$;
- about **Y**, a positive turn takes **$z$ towards $x$**.

(Check the first with the right-hand rule: thumb up along $+z$, out of a page whose $x$ points right
and $y$ up; your fingers curl from right to up, counter-clockwise.)

### 5.2 The three matrices

Build each one from where the axes go (chapter 02: the columns are the images of $\mathbf{e}_x$,
$\mathbf{e}_y$, $\mathbf{e}_z$), with $c = \cos\theta$ and $s = \sin\theta$.

- **About Z.** The $(x, y)$ plane turns exactly as in §3.1: $\mathbf{e}_x \to (c, s, 0)$,
  $\mathbf{e}_y \to (-s, c, 0)$, and $\mathbf{e}_z$ stays.
- **About X.** The pair $(y, z)$ plays the role of $(x, y)$: $\mathbf{e}_y \to (0, c, s)$,
  $\mathbf{e}_z \to (0, -s, c)$, and $\mathbf{e}_x$ stays.
- **About Y.** The pair $(z, x)$ plays the role of $(x, y)$: $\mathbf{e}_z \to c\,\mathbf{e}_z + s\,
  \mathbf{e}_x = (s, 0, c)$ and $\mathbf{e}_x \to -s\,\mathbf{e}_z + c\,\mathbf{e}_x = (c, 0, -s)$, and
  $\mathbf{e}_y$ stays.

Writing those images as columns:

$$R_x(\theta) = \begin{bmatrix} 1 & 0 & 0 \\ 0 & c & -s \\ 0 & s & c \end{bmatrix} \qquad R_y(\theta) = \begin{bmatrix} c & 0 & s \\ 0 & 1 & 0 \\ -s & 0 & c \end{bmatrix} \qquad R_z(\theta) = \begin{bmatrix} c & -s & 0 \\ s & c & 0 \\ 0 & 0 & 1 \end{bmatrix}$$

$R_y$ looks as if its signs were swapped. They are not: its 2D rotation acts on the pair $(z, x)$ in
that order, so the $-s$ lands in the $z$ row. This is the most common sign bug in 3D code, so it is
worth one check with numbers: $R_y(90°)$ sends $\mathbf{e}_x$ to its first column, $(0, 0, -1)$. Two
tests pin exactly that (§30.1): `trsComposition` expects $(1, 0, 0)$, scaled by 2 and turned 90°
about $Y$, at $(0, 0, -2)$, and `mat3NormalMatrixAndMutableTypes` turns $(1, 0, 0)$ with a mutable
quaternion and expects $(0, 0, -1)$.

Each of these matrices has the three properties of §3.5: it keeps lengths, its inverse is its
transpose, and its determinant is 1. So does any product of them.

### 5.3 Yaw: turning on the ground

For a game seen from above, the rotation that matters most is **yaw**, a turn about the vertical
$+Y$ axis: a character turning to face somewhere. Seen from above ($+Y$ pointing at you), with $+X$
to the right and $+Z$ down the screen (chapter 02, which way is forward), a positive yaw turns $+Z$
towards $+X$, which is counter-clockwise on the screen:

```
   Seen from above (+Y towards you).  Positive yaw is counter-clockwise.

                     -Z   (yaw = π for a model)
                      ^
                      |
   (yaw = -π/2) -X <--+--> +X  (yaw = +π/2)
                      |
                      v
                     +Z   (yaw = 0: a model's forward)
```

Models face $+Z$ (the glTF convention the cooker keeps). A model turned by yaw $\psi$ therefore faces
$R_y(\psi)\,\mathbf{e}_z$, the third column of $R_y$:

$$\text{facing}(\psi) = (\sin\psi,\; 0,\; \cos\psi)$$

To make a character at $(x_0, z_0)$ face a point $(x_1, z_1)$, you need the yaw whose facing points
along $(d_x, d_z) = (x_1 - x_0, z_1 - z_0)$: $\sin\psi$ proportional to $d_x$ and $\cos\psi$ to
$d_z$. §2.5's `atan2(y, x)` returns the angle whose sine goes with its first argument and cosine with
its second, so:

$$\psi = \operatorname{atan2}(d_x,\; d_z)$$

Note the order: $x$ first, then $z$. It looks backwards next to the usual `atan2(y, x)`, and it is
right, because yaw is measured from $+Z$ towards $+X$. Worked: from $(2, 3)$ to $(-1, 7)$, $d =
(-3, 4)$, $\psi = \operatorname{atan2}(-3, 4) = -0.6435$ rad ($-36.87°$), and the facing is
$(\sin(-0.6435), 0, \cos(-0.6435)) = (-0.6, 0, 0.8)$, which is $d$ divided by its length 5. This is
what `AgentSync.faceToward` computes (§29.1).

The camera uses the same yaw, but a camera looks down its own $-Z$ (chapter 04). `IsoCamera`'s yaw of
0 "looks towards -Z", and the camera also tilts down by a **pitch** $\phi$. Its forward vector is
$R_y(\psi)$ applied to $-\mathbf{e}_z$, which is $(-\sin\psi, 0, -\cos\psi)$, then tilted down: in the
vertical plane through that horizontal direction, $\cos\phi$ of it plus $\sin\phi$ of straight down:

$$\text{forward} = (-\sin\psi\cos\phi,\; -\sin\phi,\; -\cos\psi\cos\phi)$$

With the camera's defaults, $\psi = \pi/4$ and $\phi = 55°$, that is $(-0.4056, -0.8192, -0.4056)$:
down at 55° and diagonally towards $-X$ and $-Z$, the classic isometric view. §29.2 shows the code.

## 6. Euler angles and gimbal lock

### 6.1 Three angles in a fixed order

Any orientation can be written as three rotations about coordinate axes, applied in a fixed order.
The three angles are **Euler angles**. For characters, vehicles and cameras they are usually called
**yaw** (about the vertical axis), **pitch** (about the sideways axis, nose up or down) and **roll**
(about the forward axis). The engine has one helper for them, `Quatf.fromYawPitchRoll` (§15.2), whose
Javadoc says "applied as yaw (Y), then pitch (X), then roll (Z), i.e. `q = qYaw * qPitch * qRoll`".
As matrices:

$$R = R_y(\text{yaw})\;R_x(\text{pitch})\;R_z(\text{roll})$$

### 6.2 Two readings of one product

There are two equally valid ways to read that product, and both appear in documentation:

- **Fixed axes, right to left.** A product of matrices applies its right-hand factor first (chapter
  02). So: roll about the world $Z$ axis first, then pitch about world $X$, then yaw about world $Y$.
- **Moving axes, left to right.** Yaw about $Y$ first; then pitch about the object's *own* $X$ axis,
  wherever the yaw put it; then roll about the object's own $Z$ axis, wherever yaw and pitch put it.
  This is how a pilot thinks, and what the Javadoc means by "yaw, then pitch, then roll".

Why are they the same? A rotation about a *turned* axis can be built from a rotation about the
original axis: turn the axis back where it was ($A^{-1}$), rotate about it ($R$), and turn it forward
again ($A$). So a rotation about the axis $A\,\mathbf{e}_x$ is $A\,R_x\,A^{-1}$. Now follow the moving
reading with $Y = R_y(\text{yaw})$:

1. Yaw: the orientation is $Y$.
2. Pitch about the object's $X$ axis, which is now $Y\mathbf{e}_x$: apply $Y R_x Y^{-1}$ after $Y$,
   giving $(Y R_x Y^{-1})\,Y = Y R_x$.
3. Roll about the object's $Z$ axis, now $(Y R_x)\,\mathbf{e}_z$: apply $(Y R_x) R_z (Y R_x)^{-1}$ after
   $Y R_x$, giving $Y R_x R_z$.

The same product. The inverses cancel at every step.

### 6.3 Gimbal lock, multiplied out

Set the pitch to exactly 90° and multiply it out, with $\alpha$ the yaw and $\gamma$ the roll. With
$\cos 90° = 0$ and $\sin 90° = 1$:

$$R_x(90°) = \begin{bmatrix} 1 & 0 & 0 \\ 0 & 0 & -1 \\ 0 & 1 & 0 \end{bmatrix}, \qquad R_x(90°)\,R_z(\gamma) = \begin{bmatrix} \cos\gamma & -\sin\gamma & 0 \\ 0 & 0 & -1 \\ \sin\gamma & \cos\gamma & 0 \end{bmatrix}$$

Then multiply by $R_y(\alpha)$ on the left. Its first row is $(\cos\alpha, 0, \sin\alpha)$, so the
first row of the result is $(\cos\alpha\cos\gamma + \sin\alpha\sin\gamma,\; -\cos\alpha\sin\gamma +
\sin\alpha\cos\gamma,\; 0)$, which the difference identities of §3.3 turn into $(\cos(\alpha -
\gamma),\; \sin(\alpha - \gamma),\; 0)$. The other rows go the same way:

$$R_y(\alpha)\,R_x(90°)\,R_z(\gamma) = \begin{bmatrix} \cos(\alpha - \gamma) & \sin(\alpha - \gamma) & 0 \\ 0 & 0 & -1 \\ -\sin(\alpha - \gamma) & \cos(\alpha - \gamma) & 0 \end{bmatrix}$$

The result depends only on $\alpha - \gamma$. Yaw and roll have become the same rotation, and one
of the three degrees of freedom is gone. Worked: yaw 0.3 with roll 0.1, and yaw 0.8 with roll 0.6,
both have $\alpha - \gamma = 0.2$ and both give the matrix with $\cos 0.2 = 0.9801$ and $\sin 0.2 =
0.1987$ in those places. The engine's `fromYawPitchRoll(0.3f, MathF.HALF_PI, 0.1f)` and
`fromYawPitchRoll(0.8f, MathF.HALF_PI, 0.6f)` return the same quaternion, (0.7036, 0.0706, −0.0706,
0.7036), as running them shows (§8.8 derives those numbers).

This is **gimbal lock**. The name comes from a gimbal: three rings nested inside each other, each
turning about one axis, the way a gyroscope or a camera stabilizer is mounted. Turn the middle ring
by 90° and the axes of the outer and inner rings line up; both rings now turn the object the same
way, and no ring can tilt it about the third direction. With angles, it means: at that pitch, no
small change of the three numbers can produce some small rotations at all. An animation that blends
Euler angles near that pitch swings wildly to get around it.

Euler angles have two more problems. Blending the three angles separately gives curved, uneven
paths between orientations. And many triples name the same orientation: the whole family $(\alpha,
90°, \gamma)$ with the same $\alpha - \gamma$, and in general $(\alpha + \pi,\; \pi - \beta,\; \gamma +
\pi)$ is the same orientation as $(\alpha, \beta, \gamma)$ (a numeric check of the matrices agrees to
$10^{-16}$), so comparing two orientations by their angles is unreliable.

### 6.4 What the engine does instead

Jeddy stores rotations as quaternions everywhere: `TransformStore` keeps `qx, qy, qz, qw` columns,
`.jscene` nodes and animation clips store quaternions, and the vertex format stores one. Angles
appear only where a single angle is the natural input:

- **yaw for characters on the ground** (`World.setYaw`, `AgentSync`), turned into a quaternion at
  once (§29.1);
- **the camera's yaw and pitch**, which `IsoCamera` turns into an eye position and a look-at matrix
  (chapter 04), not into a product of three axis rotations;
- **the editor's Inspector**, which shows yaw, pitch and roll in degrees because people think in
  them, and converts to and from a quaternion (`GizmoMath.toEuler` and `fromEuler`, chapter
  [14](14-editor.md)). At pitch ±90° it gives the whole turn to yaw, the one choice that does not
  depend on how the object got there.

A single angle cannot gimbal-lock: gimbal lock needs two of the three rotations to line up.

## 7. Any rotation is one turn about one axis

### 7.1 Axis and angle

In 1775 Euler also proved the fact that makes quaternions possible: **every rotation of space about
the origin is a single turn by some angle $\theta$ about some axis through the origin.** However many
rotations you chain, the result is one turn about one axis. (The proof needs more about 3×3
determinants than this course uses; §10.2 shows how to read the axis and angle back out of any
rotation matrix.) A rotation can therefore be described by a **unit axis** $\hat{\mathbf{n}}$ (a
vector of length 1; the hat marks unit length) and an **angle** $\theta$: the **axis–angle** form.
`Quatf.fromAxisAngle(axis, radians)` takes exactly that.

### 7.2 Rodrigues' formula

How does a turn by $\theta$ about $\hat{\mathbf{n}}$ move a vector $\mathbf{v}$? Split $\mathbf{v}$
into the part along the axis and the part perpendicular to it. The part along the axis is the
projection of chapter 02's dot product:

$$\mathbf{v}_\parallel = (\hat{\mathbf{n}} \cdot \mathbf{v})\,\hat{\mathbf{n}}, \qquad \mathbf{v}_\perp = \mathbf{v} - \mathbf{v}_\parallel$$

The parallel part lies on the axis and does not move. The perpendicular part turns in the plane
perpendicular to the axis, and in that plane we can use the 2D rotation of §3.1 if we find two
perpendicular directions of the same length to play $x$ and $y$:

- "$x$" is $\mathbf{v}_\perp$ itself;
- "$y$" is $\hat{\mathbf{n}} \times \mathbf{v}$. It equals $\hat{\mathbf{n}} \times \mathbf{v}_\perp$,
  because the cross product of the axis with the parallel part is zero (parallel vectors, chapter
  02). It is perpendicular to both $\hat{\mathbf{n}}$ and $\mathbf{v}_\perp$, so it lies in the plane;
  its length is $\lVert\hat{\mathbf{n}}\rVert\,\lVert\mathbf{v}_\perp\rVert \sin 90° =
  \lVert\mathbf{v}_\perp\rVert$; and it is $\mathbf{v}_\perp$ turned a quarter turn the positive way
  (right-hand rule: $\mathbf{e}_z \times \mathbf{e}_x = \mathbf{e}_y$, and $\mathbf{e}_x$ turned by
  $+90°$ about $z$ is $\mathbf{e}_y$).

So, as in the plane, $\mathbf{v}_\perp$ turned by $\theta$ is $\cos\theta$ of "$x$" plus $\sin\theta$
of "$y$":

$$\mathbf{v}_\perp' = \mathbf{v}_\perp\cos\theta + (\hat{\mathbf{n}} \times \mathbf{v})\sin\theta$$

Add the parallel part back, $\mathbf{v}' = \mathbf{v}_\parallel + \mathbf{v}_\perp'$, and expand
$\mathbf{v}_\perp = \mathbf{v} - (\hat{\mathbf{n}} \cdot \mathbf{v})\hat{\mathbf{n}}$:

$$\mathbf{v}' = (\hat{\mathbf{n}} \cdot \mathbf{v})\hat{\mathbf{n}} + \big(\mathbf{v} - (\hat{\mathbf{n}} \cdot \mathbf{v})\hat{\mathbf{n}}\big)\cos\theta + (\hat{\mathbf{n}} \times \mathbf{v})\sin\theta$$

$$\mathbf{v}' = \mathbf{v}\cos\theta + (\hat{\mathbf{n}} \times \mathbf{v})\sin\theta + \hat{\mathbf{n}}\,(\hat{\mathbf{n}} \cdot \mathbf{v})(1 - \cos\theta)$$

This is **Rodrigues' rotation formula** (1840). Keep it in mind: §8.5 will show that the quaternion
rotation is exactly this formula, written with half angles.

### 7.3 A worked example

Turn by 120° about the diagonal axis $\hat{\mathbf{n}} = (1, 1, 1)/\sqrt 3$. With $\cos 120° = -0.5$,
$\sin 120° = 0.8660$ and $\mathbf{v} = (1, 0, 0)$:

- $\hat{\mathbf{n}} \cdot \mathbf{v} = 1/\sqrt 3$;
- $\hat{\mathbf{n}} \times \mathbf{v} = (1 \cdot 0 - 1 \cdot 0,\; 1 \cdot 1 - 1 \cdot 0,\; 1 \cdot 0 - 1 \cdot
  1)/\sqrt 3 = (0, 1, -1)/\sqrt 3$;
- $\mathbf{v}' = (1, 0, 0)(-0.5) + (0, 1, -1)\frac{0.8660}{\sqrt 3} + (1, 1, 1)\frac{1}{\sqrt 3}\cdot
  \frac{1}{\sqrt 3}\cdot 1.5 = (-0.5, 0, 0) + (0, 0.5, -0.5) + (0.5, 0.5, 0.5) = (0, 1, 0)$.

$x$ goes to $y$. The same computation sends $y$ to $z$ and $z$ to $x$: a turn by a third of a circle
about the diagonal cycles the three axes. This rotation comes back in §8 as the quaternion $(0.5,
0.5, 0.5, 0.5)$, where every step can be checked against these numbers.

## 8. Quaternions

### 8.1 Hamilton's four-part numbers

A **quaternion** is a number with one real part and three imaginary parts:

$$q = w + x\,i + y\,j + z\,k$$

where $w$, $x$, $y$, $z$ are ordinary numbers and $i$, $j$, $k$ are three different imaginary units.
Hamilton's rule, which he famously carved into a bridge in Dublin when he found it, is:

$$i^2 = j^2 = k^2 = ijk = -1$$

Everything else follows from it (multiplication of quaternions is associative, so brackets can be
moved, but it is **not** commutative, so factors may not be swapped). Multiply $ijk = -1$ on the
right by $k$: $ij\,k^2 = -k$, so $-ij = -k$, so $ij = k$. Multiply $ijk = -1$ on the left by $i$:
$i^2 jk = -i$, so $jk = i$. The rest come the same way, and the full table (row times column) is:

| × | $i$ | $j$ | $k$ |
|---|---|---|---|
| $i$ | $-1$ | $k$ | $-j$ |
| $j$ | $-k$ | $-1$ | $i$ |
| $k$ | $j$ | $-i$ | $-1$ |

The pattern is the cross product of chapter 02 in disguise: $ij = k$, $jk = i$, $ki = j$ follow the
cyclic order like $\mathbf{e}_x \times \mathbf{e}_y = \mathbf{e}_z$, and swapping the order flips the
sign ($ji = -k$) like $\mathbf{e}_y \times \mathbf{e}_x = -\mathbf{e}_z$. That is not a coincidence,
as §8.3 shows.

Jeddy stores a quaternion as four `float`s in the order **`(x, y, z, w)`**: the three imaginary
parts first, the real part last. Many math books write $(w, x, y, z)$ instead. The engine's order is
the one glTF files use for node rotations and the one a GLSL `vec4` gives naturally (`q.xyz` is the
vector part, `q.w` the scalar), so no data is shuffled between the file, the Java code and the
shader.

### 8.2 The product, term by term

Multiply two quaternions $q_1 = w_1 + x_1 i + y_1 j + z_1 k$ and $q_2 = w_2 + x_2 i + y_2 j + z_2 k$
by multiplying every part of the first with every part of the second (16 terms) and replacing each
product of units from the table. Grouped by unit:

- **real:** $w_1w_2 + x_1x_2\,i^2 + y_1y_2\,j^2 + z_1z_2\,k^2 = w_1w_2 - x_1x_2 - y_1y_2 - z_1z_2$
- **$i$:** $w_1x_2 + x_1w_2 + y_1z_2\,(jk = i) + z_1y_2\,(kj = -i) = w_1x_2 + x_1w_2 + y_1z_2 - z_1y_2$
- **$j$:** $w_1y_2 + y_1w_2 + z_1x_2\,(ki = j) + x_1z_2\,(ik = -j) = w_1y_2 - x_1z_2 + y_1w_2 + z_1x_2$
- **$k$:** $w_1z_2 + z_1w_2 + x_1y_2\,(ij = k) + y_1x_2\,(ji = -k) = w_1z_2 + x_1y_2 - y_1x_2 + z_1w_2$

These four lines are, character for character, the four lines of `Quatf.mul` (§15.3) with `this` as
$q_1$ and `o` as $q_2$.

**Worked.** A yaw of 90° is (§8.5 will show why) $q_1 = (0, s, 0, s)$ and a pitch of 90° is $q_2 =
(s, 0, 0, s)$, with $s = \sqrt{1/2} = 0.7071$ and the engine's $(x, y, z, w)$ order. Then:

- $x = w_1x_2 + x_1w_2 + y_1z_2 - z_1y_2 = s \cdot s + 0 + s \cdot 0 - 0 = 0.5$
- $y = w_1y_2 - x_1z_2 + y_1w_2 + z_1x_2 = 0 - 0 + s \cdot s + 0 = 0.5$
- $z = w_1z_2 + x_1y_2 - y_1x_2 + z_1w_2 = 0 + 0 - s \cdot s + 0 = -0.5$
- $w = w_1w_2 - x_1x_2 - y_1y_2 - z_1z_2 = s \cdot s - 0 - 0 - 0 = 0.5$

So yaw × pitch = (0.5, 0.5, −0.5, 0.5). In the other order, pitch × yaw, the same steps give (0.5,
0.5, 0.5, 0.5): only the sign of $z$ differs, and it is a different rotation (§8.8).

### 8.3 The product in vector form

The four lines have structure. Write a quaternion as a **scalar part** $w$ and a **vector part**
$\mathbf{v} = (x, y, z)$, as $q = (\mathbf{v}, w)$. A quaternion with $w = 0$ is a **pure**
quaternion, and we will use pure quaternions to hold ordinary 3D vectors.

First multiply two pure quaternions ($w_1 = w_2 = 0$). The four lines lose every term with a $w$:

- real: $-(x_1x_2 + y_1y_2 + z_1z_2) = -\,\mathbf{v}_1 \cdot \mathbf{v}_2$
- $i$, $j$, $k$: $(y_1z_2 - z_1y_2,\; z_1x_2 - x_1z_2,\; x_1y_2 - y_1x_2) = \mathbf{v}_1 \times \mathbf{v}_2$

$$\mathbf{v}_1\,\mathbf{v}_2 = \big(\mathbf{v}_1 \times \mathbf{v}_2,\; -\,\mathbf{v}_1 \cdot \mathbf{v}_2\big)$$

The quaternion product of two vectors holds both of chapter 02's products at once: the cross
product in the vector part, minus the dot product in the scalar part. For general quaternions,
multiply $(w_1 + \mathbf{v}_1)(w_2 + \mathbf{v}_2)$ out as four pieces: $w_1w_2$ (a scalar), $w_1
\mathbf{v}_2$ and $w_2\mathbf{v}_1$ (ordinary scaled vectors), and $\mathbf{v}_1\mathbf{v}_2$ from
above:

$$q_1 q_2 = \big(\,w_1\mathbf{v}_2 + w_2\mathbf{v}_1 + \mathbf{v}_1 \times \mathbf{v}_2,\;\; w_1w_2 - \mathbf{v}_1 \cdot \mathbf{v}_2\,\big)$$

Check the $x$ component against §8.2: $w_1x_2 + w_2x_1 + (y_1z_2 - z_1y_2)$. Same.

This form explains the non-commutativity. Swapping $q_1$ and $q_2$ changes nothing but the cross
product, which flips sign, so $q_1q_2 - q_2q_1 = (2\,\mathbf{v}_1 \times \mathbf{v}_2,\; 0)$. Two
quaternions commute exactly when their vector parts are parallel: two yaws commute (turning about
the same axis in either order ends the same way), a yaw and a pitch do not.

### 8.4 Norm, conjugate and inverse

The definitions copy the complex numbers of §4.1:

- The **norm** is the length of the four numbers as a vector, $\lVert q \rVert = \sqrt{x^2 + y^2 +
  z^2 + w^2}$. A **unit quaternion** has norm 1.
- The **conjugate** flips the vector part: $q^* = (-\mathbf{v}, w)$.
- $q\,q^* = \big(w(-\mathbf{v}) + w\mathbf{v} + \mathbf{v} \times (-\mathbf{v}),\; w^2 + \mathbf{v} \cdot
  \mathbf{v}\big) = (\mathbf{0},\; \lVert q \rVert^2)$, a plain number (a vector crossed with itself is
  zero). So the **inverse**, the quaternion with $q\,q^{-1} = 1$, is

  $$q^{-1} = \frac{q^*}{\lVert q \rVert^2}$$

  and for a unit quaternion the inverse is just the conjugate, which costs three sign flips.

Two more facts, both checked by expanding with the vector form:

- **The conjugate of a product reverses the order:** $(q_1q_2)^* = q_2^*\,q_1^*$. (Expand $q_2^*q_1^* =
  (-\mathbf{v}_2, w_2)(-\mathbf{v}_1, w_1)$: the vector part is $-w_2\mathbf{v}_1 - w_1\mathbf{v}_2 +
  \mathbf{v}_2 \times \mathbf{v}_1 = -(w_1\mathbf{v}_2 + w_2\mathbf{v}_1 + \mathbf{v}_1 \times
  \mathbf{v}_2)$, the negated vector part of $q_1q_2$; the scalar parts agree.)
- **Norms multiply:** $\lVert q_1q_2 \rVert^2 = (q_1q_2)(q_1q_2)^* = q_1\,q_2q_2^*\,q_1^* = q_1\,\lVert
  q_2\rVert^2\,q_1^* = \lVert q_1\rVert^2\,\lVert q_2\rVert^2$ (a plain number can be moved anywhere in a
  product). So the product of two unit quaternions is a unit quaternion: composing rotations gives a
  rotation. In `float` arithmetic each product rounds a little, which is why code that multiplies
  many quaternions in a row normalizes the result (§27).

### 8.5 A unit quaternion is a rotation

Here is the central fact. Take a unit axis $\hat{\mathbf{n}}$ and an angle $\theta$, and build

$$q = \big(\,\hat{\mathbf{n}}\sin\tfrac{\theta}{2},\;\; \cos\tfrac{\theta}{2}\,\big)$$

It is a unit quaternion: $\lVert q \rVert^2 = \sin^2\frac{\theta}{2}\,\lVert\hat{\mathbf{n}}\rVert^2 +
\cos^2\frac{\theta}{2} = 1$. This is what `Quatf.fromAxisAngle` builds (§15.2). Put a vector
$\mathbf{p}$ into a pure quaternion $(\mathbf{p}, 0)$ and compute the **sandwich** $q\,\mathbf{p}\,
q^*$. Write $\mathbf{u}$ for the vector part of $q$ and $w$ for its scalar part, and use the vector
form of §8.3 twice.

**First product.** $q\,\mathbf{p} = (\mathbf{u}, w)(\mathbf{p}, 0)$:

$$q\,\mathbf{p} = \big(w\,\mathbf{p} + \mathbf{u} \times \mathbf{p},\;\; -\,\mathbf{u} \cdot \mathbf{p}\big)$$

**Second product.** Multiply that by $q^* = (-\mathbf{u}, w)$ on the right. Call the vector part
above $\mathbf{A} = w\mathbf{p} + \mathbf{u} \times \mathbf{p}$ and the scalar part $a = -\mathbf{u}
\cdot \mathbf{p}$. The scalar part of the result is

$$a\,w - \mathbf{A} \cdot (-\mathbf{u}) = -w\,(\mathbf{u} \cdot \mathbf{p}) + w\,(\mathbf{p} \cdot \mathbf{u}) + (\mathbf{u} \times \mathbf{p}) \cdot \mathbf{u} = 0$$

(the first two cancel; the third is zero because a cross product is perpendicular to its factors).
So the result is again a pure quaternion, a 3D vector. Its vector part is

$$a\,(-\mathbf{u}) + w\,\mathbf{A} + \mathbf{A} \times (-\mathbf{u}) = (\mathbf{u} \cdot \mathbf{p})\,\mathbf{u} + w^2\,\mathbf{p} + w\,(\mathbf{u} \times \mathbf{p}) - \mathbf{A} \times \mathbf{u}$$

The last term needs one identity of the cross product, the **triple product expansion** ("BAC minus
CAB"):

$$\mathbf{a} \times (\mathbf{b} \times \mathbf{c}) = \mathbf{b}\,(\mathbf{a} \cdot \mathbf{c}) - \mathbf{c}\,(\mathbf{a} \cdot \mathbf{b})$$

Check its $x$ component from the cross product's definition: $\mathbf{b} \times \mathbf{c} = (b_yc_z -
b_zc_y,\; b_zc_x - b_xc_z,\; b_xc_y - b_yc_x)$, and the $x$ component of $\mathbf{a} \times$ that is
$a_y(b_xc_y - b_yc_x) - a_z(b_zc_x - b_xc_z) = b_x(a_yc_y + a_zc_z) - c_x(a_yb_y + a_zb_z)$. Add and
subtract $a_xb_xc_x$: $b_x(\mathbf{a} \cdot \mathbf{c}) - c_x(\mathbf{a} \cdot \mathbf{b})$. The $y$
and $z$ components follow by cycling the letters. With it, and $\mathbf{p} \times \mathbf{u} =
-\,\mathbf{u} \times \mathbf{p}$:

$$\mathbf{A} \times \mathbf{u} = w\,(\mathbf{p} \times \mathbf{u}) + (\mathbf{u} \times \mathbf{p}) \times \mathbf{u} = -w\,(\mathbf{u} \times \mathbf{p}) + (\mathbf{u} \cdot \mathbf{u})\,\mathbf{p} - (\mathbf{u} \cdot \mathbf{p})\,\mathbf{u}$$

(the middle step is the identity applied to $-\mathbf{u} \times (\mathbf{u} \times \mathbf{p})$).
Substitute and collect the terms in $\mathbf{p}$, in $\mathbf{u} \times \mathbf{p}$ and in
$\mathbf{u}$:

$$\mathbf{p}' = (w^2 - \mathbf{u} \cdot \mathbf{u})\,\mathbf{p} + 2w\,(\mathbf{u} \times \mathbf{p}) + 2\,(\mathbf{u} \cdot \mathbf{p})\,\mathbf{u}$$

That holds for any quaternion. Now put in $\mathbf{u} = \hat{\mathbf{n}}\sin\frac{\theta}{2}$ and $w =
\cos\frac{\theta}{2}$ and use the double-angle identities of §3.4:

- $w^2 - \mathbf{u} \cdot \mathbf{u} = \cos^2\frac{\theta}{2} - \sin^2\frac{\theta}{2} = \cos\theta$
- $2w\,\mathbf{u} = 2\sin\frac{\theta}{2}\cos\frac{\theta}{2}\,\hat{\mathbf{n}} = \sin\theta\,\hat{\mathbf{n}}$
- $2\,(\mathbf{u} \cdot \mathbf{p})\,\mathbf{u} = 2\sin^2\frac{\theta}{2}\,(\hat{\mathbf{n}} \cdot \mathbf{p})\,\hat{\mathbf{n}} = (1 - \cos\theta)(\hat{\mathbf{n}} \cdot \mathbf{p})\,\hat{\mathbf{n}}$

$$\mathbf{p}' = \mathbf{p}\cos\theta + (\hat{\mathbf{n}} \times \mathbf{p})\sin\theta + \hat{\mathbf{n}}\,(\hat{\mathbf{n}} \cdot \mathbf{p})(1 - \cos\theta)$$

That is Rodrigues' formula from §7.2. **The sandwich $q\,\mathbf{p}\,q^*$ rotates $\mathbf{p}$ by
$\theta$ about $\hat{\mathbf{n}}$.** And since every unit quaternion can be written as
$(\hat{\mathbf{n}}\sin\frac{\theta}{2}, \cos\frac{\theta}{2})$ (its $w$ is between $-1$ and 1, so it is
the cosine of some half angle, and its vector part has length $\sqrt{1 - w^2}$, which is the sine of
that half angle), every unit quaternion is a rotation.

**Worked, with the rotation of §7.3.** $q = (0.5, 0.5, 0.5, 0.5)$. Its $w = 0.5 = \cos 60°$, so
$\theta/2 = 60°$ and $\theta = 120°$; its vector part $(0.5, 0.5, 0.5)$ divided by $\sin 60° = 0.8660$
is $(0.5774, 0.5774, 0.5774) = (1, 1, 1)/\sqrt 3$. The sandwich on $\mathbf{p} = (1, 0, 0)$:

1. $q\,\mathbf{p} = (w\mathbf{p} + \mathbf{u} \times \mathbf{p},\; -\mathbf{u} \cdot \mathbf{p})$. Here
   $\mathbf{u} \times \mathbf{p} = (0, 0.5, -0.5)$, so $q\,\mathbf{p} = ((0.5, 0.5, -0.5),\; -0.5)$.
2. Times $q^* = ((-0.5, -0.5, -0.5),\; 0.5)$, with the product formula of §8.3 ($\mathbf{v}_1 =
   (0.5, 0.5, -0.5)$, $w_1 = -0.5$, $\mathbf{v}_2 = (-0.5, -0.5, -0.5)$, $w_2 = 0.5$): the vector part
   is $w_1\mathbf{v}_2 + w_2\mathbf{v}_1 + \mathbf{v}_1 \times \mathbf{v}_2 = (0.25, 0.25, 0.25) + (0.25,
   0.25, -0.25) + (-0.5, 0.5, 0) = (0, 1, 0)$, and the scalar part is $w_1w_2 - \mathbf{v}_1 \cdot
   \mathbf{v}_2 = -0.25 - (-0.25) = 0$.

$(1, 0, 0) \to (0, 1, 0)$, as Rodrigues' formula said in §7.3.

### 8.6 Why half the angle

The half angle is not a choice; the sandwich forces it. In the derivation, $q$ acts twice, once from
the left and once (conjugated) from the right, and the angle came out as $w^2 - \mathbf{u}\cdot
\mathbf{u} = \cos\theta$ and $2w\lVert\mathbf{u}\rVert = \sin\theta$, double-angle formulas of the half
angle stored in $q$. Store the full angle in $q$ and the sandwich turns by twice that.

Why not multiply on one side only, as with complex numbers? Because in space a one-sided product
leaves 3D space. In step 1 of the worked example, $q\,\mathbf{p} = ((0.5, 0.5, -0.5),\; -0.5)$ has a
non-zero scalar part: it is not a 3D vector any more. In the plane, a complex number times a point is
always a point, so one multiplication is enough; in space, the second multiplication by $q^*$ is
what cancels the scalar part, and it doubles the turn on the way.

### 8.7 Two quaternions for every rotation

Negate all four numbers: $-q = (-\hat{\mathbf{n}}\sin\frac{\theta}{2},\; -\cos\frac{\theta}{2})$. In the
sandwich the two minus signs cancel, $(-q)\,\mathbf{p}\,(-q)^* = q\,\mathbf{p}\,q^*$, so **$q$ and $-q$
are the same rotation.** Geometrically, by fact 3 of §2.4, $-\sin\frac{\theta}{2} = \sin(\frac{\theta}{2}
+ \pi)$ and $-\cos\frac{\theta}{2} = \cos(\frac{\theta}{2} + \pi)$, so $-q$ is the rotation by $\theta +
2\pi$ about the same axis: one extra full turn, which ends in the same place.

Unit quaternions cover every rotation exactly twice, a **double cover**. That is harmless, but code
must know it:

- **Comparing** two rotations must accept either sign. `Quatf.approxEquals` tests
  $1 - \lvert q_0 \cdot q_1 \rvert \le \varepsilon$ (§15.6).
- **The angle** of a rotation, read back from $w = \cos\frac{\theta}{2}$, should be the short one.
  `Quatf.angle()` uses $\lvert w \rvert$, so it returns $2\arccos\lvert w\rvert$ in $[0, \pi]$. For
  $-$yaw(90°) $= (0, -0.7071, 0, -0.7071)$ it returns 90°, not 270°.
- **Interpolation** must pick the sign that takes the short way (§11.4).
- **The QTangent** uses the free sign to store one extra bit (§12.2).

### 8.8 Composition: the right-hand factor acts first

Rotating $\mathbf{p}$ by $q_1$ and then by $q_2$ is a sandwich in a sandwich. With $(q_2q_1)^* =
q_1^*q_2^*$ from §8.4:

$$q_2\,(q_1\,\mathbf{p}\,q_1^*)\,q_2^* = (q_2\,q_1)\;\mathbf{p}\;(q_2\,q_1)^*$$

The combined rotation is the product $q_2 q_1$, with **the right-hand factor acting first**, exactly
as with matrices (chapter 02). In code, `a.mul(b)` "rotates by `b`, then by `this`" (the Javadoc of
`Quatf.mul` says it in those words).

**Worked.** Rotate the camera's forward vector $(0, 0, -1)$ with the two products of §8.2:

- `yaw.mul(pitch)` = (0.5, 0.5, −0.5, 0.5): the pitch acts first and turns $(0, 0, -1)$ to $(0, 1,
  0)$ (straight up); the yaw then turns about that same vertical line and leaves it alone. Result
  $(0, 1, 0)$: the camera looks up.
- `pitch.mul(yaw)` = (0.5, 0.5, 0.5, 0.5): the yaw acts first and turns $(0, 0, -1)$ to $(-1, 0, 0)$;
  the pitch turns about the $x$ axis and leaves that alone. Result $(-1, 0, 0)$: it looks left. This
  is the 120° turn about the diagonal from §7.3, which cycles $z \to x$, so $-z \to -x$.

**The gimbal-lock numbers of §6.3.** `fromYawPitchRoll` computes $q_y(\alpha)\,q_x(90°)\,q_z(\gamma)$
with $q_y(\alpha) = (0, \sin\frac{\alpha}{2}, 0, \cos\frac{\alpha}{2})$, $q_x(90°) = (s, 0, 0, s)$ and
$q_z(\gamma) = (0, 0, \sin\frac{\gamma}{2}, \cos\frac{\gamma}{2})$. Multiplying with the four lines of
§8.2 (the first product as in the worked example there, the second the same way) and simplifying
with §3.3's difference identities gives

$$q_y(\alpha)\,q_x(90°)\,q_z(\gamma) = s\,\big(\cos\tfrac{\alpha - \gamma}{2},\;\; \sin\tfrac{\alpha - \gamma}{2},\;\; -\sin\tfrac{\alpha - \gamma}{2},\;\; \cos\tfrac{\alpha - \gamma}{2}\big)$$

which, like the matrix, depends only on $\alpha - \gamma$. For $\alpha - \gamma = 0.2$: $0.7071 \cos
0.1 = 0.7036$ and $0.7071 \sin 0.1 = 0.0706$, the (0.7036, 0.0706, −0.0706, 0.7036) the code returns.

## 9. Rotating a vector with fewer multiplications

Computing the sandwich literally takes two full quaternion products, 32 multiplications. The
general result of §8.5 is cheaper, and one more step makes it cheaper still. Start from

$$\mathbf{p}' = (w^2 - \mathbf{u} \cdot \mathbf{u})\,\mathbf{p} + 2w\,(\mathbf{u} \times \mathbf{p}) + 2\,(\mathbf{u} \cdot \mathbf{p})\,\mathbf{u}$$

The triple product expansion with $\mathbf{a} = \mathbf{b} = \mathbf{u}$, $\mathbf{c} = \mathbf{p}$
says $\mathbf{u} \times (\mathbf{u} \times \mathbf{p}) = \mathbf{u}\,(\mathbf{u} \cdot \mathbf{p}) -
\mathbf{p}\,(\mathbf{u} \cdot \mathbf{u})$, so $2(\mathbf{u} \cdot \mathbf{p})\,\mathbf{u} = 2\,
\mathbf{u} \times (\mathbf{u} \times \mathbf{p}) + 2(\mathbf{u} \cdot \mathbf{u})\,\mathbf{p}$.
Substitute:

$$\mathbf{p}' = (w^2 + \mathbf{u} \cdot \mathbf{u})\,\mathbf{p} + 2w\,(\mathbf{u} \times \mathbf{p}) + 2\,\mathbf{u} \times (\mathbf{u} \times \mathbf{p})$$

For a unit quaternion $w^2 + \mathbf{u} \cdot \mathbf{u} = 1$, and the first term is just $\mathbf{p}$:

$$\mathbf{p}' = \mathbf{p} + 2w\,(\mathbf{u} \times \mathbf{p}) + 2\,\mathbf{u} \times (\mathbf{u} \times \mathbf{p})$$

The engine writes this in two equivalent ways:

- **The shader's form.** The cross product is linear, so $2\,\mathbf{u} \times$ can be pulled out of
  both terms: $\mathbf{p}' = \mathbf{p} + 2\,\mathbf{u} \times (\mathbf{u} \times \mathbf{p} +
  w\,\mathbf{p})$. That is, word for word, `jeddyQuatRotate` in `vertex.glsl` (§21).
- **The Java form.** Name $\mathbf{t} = 2\,(\mathbf{u} \times \mathbf{p})$. Then $2w(\mathbf{u} \times
  \mathbf{p}) = w\,\mathbf{t}$ and $2\,\mathbf{u} \times (\mathbf{u} \times \mathbf{p}) = \mathbf{u}
  \times \mathbf{t}$, so $\mathbf{p}' = \mathbf{p} + w\,\mathbf{t} + \mathbf{u} \times \mathbf{t}$. That
  is `Quatf.rotate`, `MQuatf.rotate` and `QuatOps.rotate` (§15.5, §16).

**Worked.** $q = (0.5, 0.5, 0.5, 0.5)$, $\mathbf{p} = (0, 0, -1)$, Java form:

- $\mathbf{t} = 2\,(u_yp_z - u_zp_y,\; u_zp_x - u_xp_z,\; u_xp_y - u_yp_x) = 2\,(-0.5, 0.5, 0) = (-1, 1, 0)$
- $w\,\mathbf{t} = (-0.5, 0.5, 0)$
- $\mathbf{u} \times \mathbf{t} = (0.5 \cdot 0 - 0.5 \cdot 1,\; 0.5 \cdot (-1) - 0.5 \cdot 0,\; 0.5 \cdot 1 - 0.5 \cdot (-1)) = (-0.5, -0.5, 1)$
- $\mathbf{p}' = (0, 0, -1) + (-0.5, 0.5, 0) + (-0.5, -0.5, 1) = (-1, 0, 0)$, as §8.8 said.

**What it costs.** Each cross product is 6 multiplications. The Java form does one cross product and
3 doublings for $\mathbf{t}$ (9), 3 for $w\,\mathbf{t}$, and 6 for $\mathbf{u} \times \mathbf{t}$: **18
multiplications** (and 12 additions or subtractions). A 3×3 matrix times a vector is 9
multiplications and 6 additions. But building the matrix from
the quaternion (§10.1) costs 9 products of components plus 9 doublings, another 18. So the rule of
thumb is: **one rotation applied to a few vectors → use the formula; one rotation applied to many
vectors → build the matrix once.** The engine follows it. Each mesh instance gets a matrix
(`composeTRS`, §17), shared by all its vertices; each vertex's QTangent is a different quaternion
applied to just two vectors, so the vertex shader uses the formula (§21).

The formula needs a **unit** quaternion: the step that dropped $w^2 + \mathbf{u} \cdot \mathbf{u}$
assumed it is 1. A quaternion of norm $k$ would scale the vector part of the result wrongly. That is
why the shader normalizes the decoded QTangent before rotating, and why the test `quatOpsMatchQuatf`
scales its quaternions by 3 and calls `QuatOps.normalize` before `QuatOps.rotate` (§30.1).

## 10. Quaternions and matrices

The GPU wants matrices (three rows of four floats per instance, chapter 02). Files and gameplay code
want quaternions. So the engine converts in both directions.

### 10.1 Quaternion to matrix

The columns of a rotation matrix are the images of the axes, and §9 rotates any vector. Rotate
$\mathbf{e}_x = (1, 0, 0)$ with $q = (x, y, z, w)$, so $\mathbf{u} = (x, y, z)$:

- $\mathbf{u} \times \mathbf{e}_x = (y \cdot 0 - z \cdot 0,\; z \cdot 1 - x \cdot 0,\; x \cdot 0 - y \cdot 1) = (0, z, -y)$
- $\mathbf{u} \times (\mathbf{u} \times \mathbf{e}_x) = \mathbf{u} \times (0, z, -y) = (y(-y) - z \cdot z,\; z \cdot 0 - x(-y),\; x \cdot z - y \cdot 0) = (-y^2 - z^2,\; xy,\; xz)$
- $R\,\mathbf{e}_x = (1, 0, 0) + 2w\,(0, z, -y) + 2\,(-y^2 - z^2,\; xy,\; xz) = \big(1 - 2(y^2 + z^2),\;\; 2(xy + wz),\;\; 2(xz - wy)\big)$

The same three steps for the other two axes:

- $\mathbf{u} \times \mathbf{e}_y = (-z, 0, x)$ and $\mathbf{u} \times (-z, 0, x) = (xy,\; -x^2 - z^2,\; yz)$, so $R\,\mathbf{e}_y = \big(2(xy - wz),\;\; 1 - 2(x^2 + z^2),\;\; 2(yz + wx)\big)$
- $\mathbf{u} \times \mathbf{e}_z = (y, -x, 0)$ and $\mathbf{u} \times (y, -x, 0) = (xz,\; yz,\; -x^2 - y^2)$, so $R\,\mathbf{e}_z = \big(2(xz + wy),\;\; 2(yz - wx),\;\; 1 - 2(x^2 + y^2)\big)$

Those three vectors are the columns:

$$R(q) = \begin{bmatrix} 1 - 2(y^2 + z^2) & 2(xy - wz) & 2(xz + wy) \\ 2(xy + wz) & 1 - 2(x^2 + z^2) & 2(yz - wx) \\ 2(xz - wy) & 2(yz + wx) & 1 - 2(x^2 + y^2) \end{bmatrix}$$

Nine products of components ($x^2$, $y^2$, $z^2$, $xy$, $xz$, $yz$, $wx$, $wy$, $wz$), then sums and
doublings: no trigonometry. This is the matrix inside `AffineOps.composeTRS` and `Mat4f.trs` (§17).

**Worked, twice.**

- Yaw 90°, $q = (0, 0.7071, 0, 0.7071)$: $y^2 = 0.5$ and $wy = 0.5$, every other product is 0. The rows
  are $(1 - 1,\; 0,\; 2 \cdot 0.5) = (0, 0, 1)$, then $(0, 1, 0)$, then $(-2 \cdot 0.5,\; 0,\; 1 - 1) =
  (-1, 0, 0)$: exactly $R_y(90°)$ from §5.2. In general, $q = (0, \sin\frac{\theta}{2}, 0,
  \cos\frac{\theta}{2})$ gives $1 - 2\sin^2\frac{\theta}{2} = \cos\theta$ and $2\sin\frac{\theta}{2}
  \cos\frac{\theta}{2} = \sin\theta$ (§3.4) in the right places: the $R_y(\theta)$ of §5.2. So the yaw
  quaternion $(0, \sin\frac{\psi}{2}, 0, \cos\frac{\psi}{2})$ that `World.setYaw` writes is the right one.
- $q = (0.5, 0.5, 0.5, 0.5)$: every product is 0.25. The columns are $(1 - 1,\; 1,\; 0) = (0, 1, 0)$,
  $(0, 0, 1)$ and $(1, 0, 0)$: $x \to y$, $y \to z$, $z \to x$, as in §7.3.

The formula used $w^2 + \mathbf{u} \cdot \mathbf{u} = 1$ (it came from §9's unit-only form). Feed it a
non-unit quaternion and the result is not a rotation: it scales and skews. `TransformStore.setRotation`
documents its argument as "a unit quaternion" for that reason.

### 10.2 Matrix to quaternion

Going back starts with the diagonal of $R(q)$. Its sum, the **trace**, is

$$m_{00} + m_{11} + m_{22} = 3 - 4(x^2 + y^2 + z^2) = 3 - 4(1 - w^2) = 4w^2 - 1$$

(using $x^2 + y^2 + z^2 + w^2 = 1$). So $w = \frac{1}{2}\sqrt{\text{trace} + 1}$, choosing the
positive root (both signs are the same rotation, §8.7). The differences of mirrored off-diagonal
entries isolate the vector part:

$$m_{21} - m_{12} = 2(yz + wx) - 2(yz - wx) = 4wx, \quad m_{02} - m_{20} = 4wy, \quad m_{10} - m_{01} = 4wz$$

So with $s = 4w = 2\sqrt{\text{trace} + 1}$: $w = s/4$, $x = (m_{21} - m_{12})/s$, $y = (m_{02} -
m_{20})/s$, $z = (m_{10} - m_{01})/s$. Worked with $R_y(90°)$ (rows $(0, 0, 1)$, $(0, 1, 0)$, $(-1, 0,
0)$): trace 1, $s = 2\sqrt 2 = 2.8284$, $w = 0.7071$, $x = (0 - 0)/s = 0$, $y = (1 - (-1))/2.8284 =
0.7071$, $z = 0$. The yaw quaternion again.

**Why one formula is not enough.** If the rotation is close to 180°, $w$ is close to 0 and so is
$s$. Dividing by a tiny $s$ multiplies the rounding errors of the matrix entries (about $10^{-7}$ in
`float`, L00 §7.3): with $s = 0.004$, an error of $10^{-7}$ becomes $2.5 \times 10^{-5}$, and the
quaternion points the wrong way by that much. At exactly 180°, $s = 0$ and the division gives
nonsense. The cure is to divide by the *largest* of the four components instead. The diagonal also
gives each of $x$, $y$, $z$ separately; for $x$:

$$1 + m_{00} - m_{11} - m_{22} = 1 + \big(1 - 2y^2 - 2z^2\big) - \big(1 - 2x^2 - 2z^2\big) - \big(1 - 2x^2 - 2y^2\big) = 4x^2$$

and the same pattern gives $4y^2$ and $4z^2$. Sums of mirrored entries give products of components:
$m_{01} + m_{10} = 4xy$, $m_{02} + m_{20} = 4xz$, $m_{12} + m_{21} = 4yz$. So if $x$ is the largest,
take $s = 4x = 2\sqrt{1 + m_{00} - m_{11} - m_{22}}$, and then $w = (m_{21} - m_{12})/s$, $y = (m_{01} +
m_{10})/s$, $z = (m_{02} + m_{20})/s$. And which one is largest can be read off the diagonal, because
$m_{00} = 1 - 2(y^2 + z^2) = 1 - 2(1 - w^2 - x^2) = 2x^2 + 2w^2 - 1$ and likewise for $m_{11}$ and
$m_{22}$: the diagonal entries are in the same order as $x^2$, $y^2$, $z^2$.

The four branches of `Quatf.fromMatrix` (§18) follow:

1. If the trace is positive, $4w^2 - 1 > 0$, so $w^2 > \frac14$ and $\lvert w\rvert > 0.5$: use $w$, and $s = 4w > 2$.
2. Otherwise $w^2 \le \frac14$, so $x^2 + y^2 + z^2 \ge \frac34$ and the largest of the three squares
   is at least $\frac14$: the largest component is at least 0.5 in size, and $s \ge 2$. The largest
   diagonal entry says which one it is.

No branch ever divides by less than 2. Worked with a 180° turn about $x$, $R_x(180°)$, whose diagonal
is $(1, -1, -1)$: the trace is $-1$, $m_{00}$ is the largest, $s = 2\sqrt{1 + 1 + 1 + 1} = 4$, $x = s/4
= 1$, and $w$, $y$, $z$ are all $0/4$: $q = (1, 0, 0, 0)$, a half turn about $x$ ($\theta/2 = 90°$).

### 10.3 A rotation that looks somewhere

"Turn to look at that" is a common request, and it is the matrix of §10.2 built from directions.
Given a direction `forward` and a rough `up`, the rotation should send the engine's forward axis,
$-Z$, to `forward`, and keep the result's $+Y$ as close to `up` as possible. Chapter 02 builds the
axes with two cross products: $\mathbf{f}$ = normalized forward, $\mathbf{r} = \mathbf{f} \times
\text{up}$ normalized (right), $\mathbf{u} = \mathbf{r} \times \mathbf{f}$ (the true up). The rotation
must send $\mathbf{e}_x \to \mathbf{r}$, $\mathbf{e}_y \to \mathbf{u}$, and $-\mathbf{e}_z \to
\mathbf{f}$, that is $\mathbf{e}_z \to -\mathbf{f}$; those are its columns, and §10.2 turns them into a
quaternion. That is `Quatf.lookRotation` (§18).

**Worked.** `lookRotation(UNIT_X, UNIT_Y)`: $\mathbf{f} = (1, 0, 0)$, $\mathbf{r} = (1, 0, 0) \times (0,
1, 0) = (0, 0, 1)$, $\mathbf{u} = (0, 0, 1) \times (1, 0, 0) = (0, 1, 0)$. Columns $(0, 0, 1)$, $(0, 1,
0)$, $(-1, 0, 0)$, so the rows are $(0, 0, -1)$, $(0, 1, 0)$, $(1, 0, 0)$. Trace 1, $s = 2.8284$, $w =
0.7071$, $y = (m_{02} - m_{20})/s = (-1 - 1)/2.8284 = -0.7071$, $x = z = 0$: $(0, -0.7071, 0, 0.7071)$, a
yaw of $-90°$. Check: $R_y(-90°)$ sends $-\mathbf{e}_z$ to $-(\sin(-90°), 0, \cos(-90°)) = (1, 0, 0)$. It
looks along $+X$.

### 10.4 Splitting a matrix into translation, rotation and scale

glTF nodes may store a whole 4×4 matrix instead of a translation, a rotation and a scale. The cooker
splits it, because `.jscene` stores TRS per node and animation needs TRS to interpolate (chapter
[09](09-animation-and-skinning.md)). For a matrix $M = T\,R\,S$ with $S = \operatorname{diag}(s_x,
s_y, s_z)$:

1. **Translation** is the last column (chapter 02, homogeneous coordinates).
2. **Scale.** The linear part is $RS$, and multiplying by a diagonal matrix on the right scales
   columns (chapter 02, TRS): column $j$ of $RS$ is $s_j$ times column $j$ of $R$. The columns of $R$
   have length 1 (§3.5), so $\lvert s_j \rvert$ is the length of column $j$.
3. **Mirror.** A length is never negative, so a negative scale cannot show up in step 2. It shows up
   in the **determinant**: $\det(RS) = \det R \cdot s_xs_ys_z = s_xs_ys_z$ (chapter 02, determinants),
   which is negative exactly when an odd number of scales are negative, a mirror. Then give the
   minus sign to one axis, say $x$. Any single axis would do: a mirror across one axis followed by a
   suitable rotation produces any other mirror. Two negative scales are a rotation by 180° (about the
   third axis), so they come back as positive scales and a different rotation.
4. **Rotation.** Divide each column by its signed scale to get $R$, and convert with §10.2.

This works only when the matrix *is* a TRS. If the columns of the linear part are not perpendicular
to each other (a **shear**, §13.4), no rotation times scale reproduces it. The glTF specification
requires node matrices to be decomposable into TRS, so the cooker can rely on it. §19 builds it.

## 11. Interpolating rotations

Animation, camera motion and blending need "the rotation a fraction $t$ of the way from $q_0$ to
$q_1$". Quaternions make this easier than angles or matrices, once you see them as points on a
sphere.

### 11.1 Quaternions as points on a sphere in four dimensions

A unit quaternion is four numbers with $x^2 + y^2 + z^2 + w^2 = 1$: a point at distance 1 from the
origin in a four-dimensional space, on its **unit sphere**. Four dimensions cannot be pictured, but
nothing here needs them all: two points and the origin always lie in one flat plane (as two arrows
from the origin do), and in that plane the two quaternions are two points on an ordinary unit circle.
The angle $\Omega$ between them comes from the dot product, exactly as in chapter 02 with one more
term:

$$\cos\Omega = q_0 \cdot q_1 = x_0x_1 + y_0y_1 + z_0z_1 + w_0w_1$$

How does $\Omega$ relate to the rotations? The rotation that takes orientation $q_0$ to orientation
$q_1$ is $r = q_0^*\,q_1$ (then $q_0\,r = q_1$). Its scalar part, from §8.3 with $q_0^* = (-\mathbf{v}_0,
w_0)$, is $w_0w_1 - (-\mathbf{v}_0) \cdot \mathbf{v}_1 = w_0w_1 + \mathbf{v}_0 \cdot \mathbf{v}_1 = q_0
\cdot q_1$. And the scalar part of a rotation by $\varphi$ is $\cos\frac{\varphi}{2}$. So $\cos\Omega =
\cos\frac{\varphi}{2}$: **the 4D angle between two quaternions is half the angle of the rotation
between them.** Identity and a 90° yaw are 45° apart on the sphere.

### 11.2 Slerp, derived

The rotation halfway between two orientations is halfway along the arc between their points.
**Slerp** (spherical linear interpolation, introduced to graphics by Ken Shoemake in 1985) walks
that arc at constant speed: at $t$ it is at angle $t\,\Omega$ from $q_0$. Let us find the point.

In the plane of $q_0$ and $q_1$, build a unit vector perpendicular to $q_0$ that points towards $q_1$
(chapter 02's Gram–Schmidt, in four dimensions): remove from $q_1$ its part along $q_0$ and divide by
what is left, whose length is $\sin\Omega$ (the side of the right triangle with hypotenuse 1 and angle
$\Omega$):

$$q_\perp = \frac{q_1 - \cos\Omega\; q_0}{\sin\Omega}$$

$q_0$ and $q_\perp$ are the "$x$" and "$y$" of the circle, and the point at angle $t\,\Omega$ on it is
(§2.3) $\cos(t\Omega)\,q_0 + \sin(t\Omega)\,q_\perp$. Substitute $q_\perp$ and collect:

$$\cos(t\Omega)\,q_0 + \frac{\sin(t\Omega)}{\sin\Omega}\big(q_1 - \cos\Omega\,q_0\big) = \frac{\cos(t\Omega)\sin\Omega - \sin(t\Omega)\cos\Omega}{\sin\Omega}\,q_0 + \frac{\sin(t\Omega)}{\sin\Omega}\,q_1$$

The numerator of the first fraction is $\sin(\Omega - t\Omega)$ by the difference identity of §3.3:

$$\operatorname{slerp}(q_0, q_1, t) = \frac{\sin\big((1 - t)\,\Omega\big)}{\sin\Omega}\,q_0 + \frac{\sin(t\,\Omega)}{\sin\Omega}\,q_1$$

**Worked.** From the identity $q_0 = (0, 0, 0, 1)$ to a 90° yaw $q_1 = (0, 0.7071, 0, 0.7071)$:
$\cos\Omega = 0.7071$, $\Omega = 45°$. At $t = \frac13$ the weights are $\sin 30°/\sin 45° = 0.7071$ and
$\sin 15°/\sin 45° = 0.3660$, and the result is $(0,\; 0.3660 \cdot 0.7071,\; 0,\; 0.7071 + 0.3660
\cdot 0.7071) = (0, 0.2588, 0, 0.9659) = (0, \sin 15°, 0, \cos 15°)$: a 30° yaw, a third of the way.

When the two quaternions are nearly equal, $\sin\Omega$ is nearly 0 and the division magnifies
rounding errors. But then the arc is nearly straight, and by §2.4's small-angle fact the weights are
nearly $1 - t$ and $t$. So `Quatf.slerp` (§15.6) switches to those plain weights when $\cos\Omega >
0.9995$, which is $\Omega < 1.81°$, a rotation of less than 3.6° between the two.

### 11.3 Nlerp and how far it is off

**Nlerp** (normalized lerp) skips the trigonometry: lerp the four numbers (chapter 02's linear
interpolation) and normalize the result. Geometrically it walks the straight chord between the two
points and pushes each point of the chord out onto the sphere. The result is on the same arc, so it
reaches exactly the same orientations, but not at constant speed: it is slower near the ends and
faster in the middle.

How much slower? From the identity to a yaw by $\theta$, the lerp at $t$ is $(0,\; t\sin\frac{\theta}{2},
\; 0,\; (1 - t) + t\cos\frac{\theta}{2})$. Normalizing does not change its direction, and the yaw
angle $\varphi$ of a quaternion $(0, y, 0, w)$ satisfies $y/w = \sin\frac{\varphi}{2}/\cos\frac{\varphi}
{2}$, so $\varphi = 2\operatorname{atan2}(y, w)$:

$$\varphi_{\text{nlerp}}(t) = 2\,\operatorname{atan2}\!\Big(t\sin\tfrac{\theta}{2},\;\; (1 - t) + t\cos\tfrac{\theta}{2}\Big)$$

For a 120° turn (slerp turns exactly $120°\,t$):

| $t$ | slerp | nlerp |
|---|---|---|
| 0.25 | 30.0° | 27.8° |
| 0.5 | 60.0° | 60.0° |
| 0.75 | 90.0° | 92.2° |

The midpoint is exact (by symmetry), and the largest error over the whole range is 2.23°, at $t
\approx 0.22$ and 0.78. The error shrinks fast with the size of the step. For a step of $\frac13$
radian (19.1°, the most a character turns in one 30 Hz tick at `AgentSync`'s default 10 rad/s) the
largest error is 0.0085°. These numbers come from running the formula (and `Quatf.nlerp` itself, for
the table).

That is why the engine mostly uses nlerp. The animation system resamples every clip at a fixed rate
when it loads (30 Hz by default, D79), so neighbouring frames are close together, and the lerp/nlerp
it uses between them is indistinguishable from slerp and much cheaper: no `acos`, no sines, one square
root. `Quatf.nlerp`, `MQuatf.nlerp` and the batch kernel `QuatOps.nlerp` ("Good enough for animation
blending between close keys") implement it (§15.6, §16).

### 11.4 The short way round

There is a catch, and it is the double cover of §8.7. $q_1$ and $-q_1$ are the same orientation, so
the pairs $(q_0, q_1)$ and $(q_0, -q_1)$ describe the same start and the same end. But the arcs
between them differ: one goes the short way, the other the long way, more than 180° of rotation.

**Worked.** $q_0$ = identity = $(0, 0, 0, 1)$ and $q_1 = -$yaw(90°) $= (0, -0.7071, 0, -0.7071)$, a
perfectly valid way to write a 90° yaw. Their dot product is $-0.7071$. A naive lerp at $t = 0.5$ is
$(0, -0.3536, 0, 0.1464)$, normalized $(0, -0.9239, 0, 0.3827)$: a rotation by $2\arccos 0.3827 =
135°$ about $-Y$. The object would swing $-135°$, halfway along a 270° turn the long way round,
instead of $+45°$.

The fix: **if $q_0 \cdot q_1 < 0$, negate one of them first.** Then the dot product is positive,
$\Omega \le 90°$, and the rotation between them is at most 180°: the short way. Every interpolation
in the engine does it. With the example above, `Quatf.IDENTITY.nlerp(q1, 0.5f)` returns $(0, 0.3827,
0, 0.9239)$, a 45° yaw. §15.6 shows the two ways the code does it, and §29.3 how the animation system
does it once per clip at load time.

## 12. A tangent frame in one quaternion

### 12.1 What a tangent frame is for

A **normal map** is a texture that stores, for every texel, a small tilt of the surface's normal, so
that a flat triangle can light up as if it had bumps and grooves. The tilts are stored in **tangent
space**: $x$ along the texture's $u$ direction on the surface, $y$ along its $v$ direction, $z$
straight out of the surface. To use the map, the shader needs three vectors at every point:

- the **normal** $\mathbf{n}$, out of the surface;
- the **tangent** $\mathbf{t}$, the direction in which $u$ grows along the surface;
- the **bitangent** $\mathbf{b}$, the direction in which $v$ grows.

The matrix with those columns, $[\,\mathbf{t}\;\mathbf{b}\;\mathbf{n}\,]$, turns a tangent-space tilt
$(t_x, t_y, t_z)$ into a direction in the model's space, $t_x\,\mathbf{t} + t_y\,\mathbf{b} + t_z\,
\mathbf{n}$: a change of basis (chapter 02). `pbr.frag` does exactly that (§29.4); chapter
[07](07-lighting-and-shadows.md) shows the lighting that consumes it.

Stored as plain floats, the frame costs a lot per vertex: three vectors are 36 bytes, or 28 bytes the
way glTF stores it (a normal of three floats, a tangent of four, the fourth a sign from which the
bitangent is rebuilt). Every vertex of every mesh carries one, and the GPU reads it for every vertex
it draws.

### 12.2 A frame is a rotation, plus one sign

The three vectors are perpendicular unit vectors (the cooker makes them so, §20). If the bitangent is
$\mathbf{b} = \mathbf{n} \times \mathbf{t}$, the matrix $[\,\mathbf{t}\;\mathbf{b}\;\mathbf{n}\,]$ is
a rotation: its columns are orthonormal, and its determinant is $+1$, because by the triple product
expansion of §8.5, $\mathbf{t} \times \mathbf{b} = \mathbf{t} \times (\mathbf{n} \times \mathbf{t}) =
\mathbf{n}\,(\mathbf{t} \cdot \mathbf{t}) - \mathbf{t}\,(\mathbf{t} \cdot \mathbf{n}) = \mathbf{n}$, and
the determinant of three columns is $(\mathbf{t} \times \mathbf{b}) \cdot \mathbf{n} = \mathbf{n} \cdot
\mathbf{n} = 1$ (chapter 02, determinants). A rotation fits in a quaternion: four numbers instead of
nine.

There is one complication. Artists often **mirror** texture coordinates: the left half of a
symmetric face reuses the texture of the right half, flipped. On the mirrored half the $u$ direction
runs backwards relative to the geometry, and the true bitangent is $\mathbf{b} = -(\mathbf{n} \times
\mathbf{t})$. That frame has determinant $-1$: it is a mirror, and no rotation (no quaternion) can
produce a mirror (§3.5). So the frame needs one more bit, the **handedness** $h = \pm 1$, with

$$\mathbf{b} = h\,(\mathbf{n} \times \mathbf{t})$$

Store the rotation of the right-handed frame $[\,\mathbf{t}\;\;\mathbf{n} \times \mathbf{t}\;\;
\mathbf{n}\,]$, and the bit separately. Where? In the **sign of the quaternion**. $q$ and $-q$ are the
same rotation (§8.7), so the sign carries no information about the frame and is free to carry the
handedness: keep $w \ge 0$ for $h = +1$, negate the whole quaternion for $h = -1$. The decoder rotates
with $q$ (the sign does not matter for that) and reads $h$ from the sign of $w$. This is the
**QTangent**, from Crytek's 2011 talk on spherical skinning, and Jeddy stores it as four `snorm16`
values (chapter 02): 8 bytes per vertex, sharing a 16-byte attribute with two half-float UV sets
(D27).

**One more trap: zero has no sign.** A frame turned by exactly 180° from the identity has $w =
\cos 90° = 0$. Quantized to `snorm16` that is the integer 0, and an integer zero has no sign (two's
complement has a single zero, L00 §5): the handedness would be lost. So the encoder first makes $w$
at least `W_BIAS` = 1/32767, the smallest positive `snorm16` step, then rescales the vector part so the
quaternion stays unit length. That tilts the rotation by $2\arcsin(1/32767) = 0.0035°$, far below
anything visible, and the sign survives.

**Worked, with the real encoder** (`MeshEncoding.encodeQTangent`, built in §20; these are its outputs):

| Normal | Tangent | $h$ | Stored `snorm16` $(x, y, z, w)$ | Why |
|---|---|---|---|---|
| (0, 0, 1) | (1, 0, 0) | +1 | (0, 0, 0, 32767) | $[\mathbf{t}\,\mathbf{b}\,\mathbf{n}]$ is the identity, $q = (0, 0, 0, 1)$ |
| (0, 0, 1) | (1, 0, 0) | −1 | (0, 0, 0, −32767) | the same rotation, negated |
| (0, 1, 0) | (1, 0, 0) | +1 | (−23170, 0, 0, 23170) | $\mathbf{b} = \mathbf{n} \times \mathbf{t} = (0, 0, -1)$; the frame is $R_x(-90°)$, $q = (-0.7071, 0, 0, 0.7071)$ |
| (0, 1, 0) | (1, 0, 0) | −1 | (23170, 0, 0, −23170) | the same rotation, negated |
| (0, 0, −1) | (1, 0, 0) | −1 | (−32767, 0, 0, −1) | the frame is $R_x(180°)$, $q = (1, 0, 0, 0)$; $w$ would be 0, the bias makes it 1/32767, the negation $-1/32767$ |

(−0.7071 × 32767 = −23169.8, which rounds to −23170.) The last row decodes to the normal $(0,
-0.000061, -1)$: the bias costs six hundred-thousandths, and the handedness survives.

### 12.3 What the GPU does with it

The QTangent is read at the very start of the graphics pipeline (L00 §10.2), where vertices enter the
vertex shader.

- **Memory.** A mesh has two vertex streams in GPU buffers (D27): positions, 12 bytes per vertex
  (three `float`s), and attributes, 16 bytes per vertex: the QTangent in bytes 0–7, the first UV set
  as two half floats in bytes 8–11, the second in 12–15. Positions are separate so that depth-only
  passes (shadow maps, the depth pre-pass) fetch 12 bytes per vertex and nothing else. All meshes
  share these buffers (`MeshRegistry`'s "mesh positions" and "mesh attributes", chapter
  [10](10-assets.md)).
- **What the driver records.** A **vertex array object** (VAO) describes how to read the streams.
  `MeshRegistry` creates a single one for all meshes and declares the QTangent with
  `glVertexArrayAttribFormat(vao, 1, 4, GL_SHORT, true, 0)`: attribute location 1, four components,
  signed 16-bit integers, **normalized**, at byte 0 of each vertex in its stream. The driver stores
  that format in the VAO's state; at draw time it programs the GPU's vertex fetch with it. Depending on
  the GPU, fetch is a fixed-function unit configured with the format, or a short routine the driver
  compiles into the start of the vertex shader. Because one VAO with one format serves every mesh, that
  setup never changes between draws.
- **What the hardware does.** For every vertex a shader invocation processes, fetch reads the 16 bytes
  at stream start + vertex index × 16 through the GPU's caches (neighbouring vertices share cache
  lines, which is why the cooker reorders vertices for fetch locality with meshoptimizer, chapter 10). "Normalized" means each
  signed 16-bit integer $c$ is converted to the float $\max(c/32767,\; -1)$, the same rule as
  `MeshEncoding.unsnorm16` and chapter 02's `snorm16`. The shader receives `jeddyQTangent` as a `vec4`
  already in $[-1, 1]$.
- **Synchronization.** The mesh was uploaded long before it is drawn (chapter 10): `MeshRegistry`
  copies it in with a GPU-side buffer copy from the frame ring, or with `glNamedBufferSubData` when
  it has no ring, earlier in the same command stream. Commands run in order, and reading vertex
  attributes after a buffer copy needs no barrier; barriers are for data written by shaders (L01
  §6.3).
- **The decode** is about 40 multiplications per vertex: a normalize and two of §9's rotations (§21).
  A GPU has far more arithmetic throughput than memory bandwidth, so trading those for bytes is the
  usual good bargain. The QTangent is 8 bytes where a float normal and tangent are 28; with the
  half-float UVs, a whole vertex is 28 bytes (12 of position, 16 of attributes) instead of 56 for
  float normals, tangents and two float UV sets. For a 10,000-vertex mesh that is 280,000 bytes
  instead of 560,000, read in every pass that needs the attributes.
- **Decoding per vertex, not per pixel.** The vertex shader decodes the quaternion to a normal and a
  tangent and passes those on. The **rasterizer** (the fixed hardware between the vertex and fragment
  stages) interpolates them across each triangle. Interpolating the quaternions themselves would go
  wrong exactly where it matters: at a mirror seam the three vertices of a triangle can have
  quaternions of opposite signs, and their blend is meaningless (§11.4). Vectors blend safely, at the
  price of a little shortening, which the fragment shader removes by normalizing again (§29.4).
- **What can go wrong.** Declaring the attribute with `glVertexArrayAttribIFormat` (integers) while
  the shader expects a `vec4` gives undefined values. A wrong offset or stride reads UVs as a
  quaternion, and only normal-mapped lighting looks wrong, which makes it hard to spot. An encoder
  without the bias loses the handedness on frames turned 180°, and the lighting of those vertices
  flips at mirror seams. `FormatsTest` guards the encoder and decoder (§30.2); the shader path is
  tested on a real GPU only (`-Pjeddy.gpuTests=true`, §32).

## 13. Transform hierarchies

### 13.1 Local and world

Objects in a scene form a tree. A torch hangs on a bracket, the bracket on a wall; a helmet sits on a
head; a level's props hang off the level root. Each node stores a **local** transform relative to its
parent, and its **world** transform is its parent's world transform times its local one (chapter 02,
parent × local):

$$W(e) = W(\operatorname{parent}(e)) \cdot L(e), \qquad W(\text{root}) = L(\text{root})$$

```
   root           W(root) = L(root)
   ├── A          W(A) = W(root) · L(A)
   │   └── B      W(B) = W(A) · L(B)
   │       └── D  W(D) = W(B) · L(D)
   └── C          W(C) = W(root) · L(C)
```

Move the root and everything under it follows, without touching any child's local transform. The
price is that world transforms must be recomputed from the top down, and a node can be computed only
once its parent is up to date.

### 13.2 What a hierarchy update must do

Thirty times a second the game thread moves things (characters walk, doors swing) and then asks for
the new world transforms. Three requirements shape the code:

1. **Order.** A world transform needs its parent's, so parents must be computed before their
   children.
2. **Only what changed.** Most of a level does not move in a given tick. An entity needs recomputing
   if its own local transform was set, or if its parent's world transform changed in this update
   (then the change flows down to every descendant). Everything else keeps last tick's matrix.
3. **The engine's rules.** No allocation per tick (L00 §8.2), and big scenes split across cores (the
   job system, chapter [01](01-architecture.md)).

The question that decides the design is how to get requirement 1 cheaply.

### 13.3 Keeping the data still and sorting an index

Three designs were considered:

- **A tree of objects.** Each node an object with a list of children; update by recursion from the
  roots. It is the textbook design and the slowest: an object per node, a reference chase per child
  (L03 §2 measures what that costs), recursion that can overflow the stack on deep chains, and no
  easy way to split the work between threads.
- **Arrays kept sorted parents-first.** Struct-of-arrays columns (L03 §4) in an order where every
  parent comes before its children, and a single loop from the start. Updating is perfect, but
  keeping the order is expensive: spawning a child under an early parent, or reparenting anything,
  means moving every column of everything after it, ten `float`s of TRS, the flags, the 12-float world
  block and more.
- **Data stays still, an index is sorted (chosen, D28).** The columns stay in **dense slots** in
  whatever order entities arrived; removing one moves the last slot into the hole (swap-removal, L03
  §8). A separate `int` array, the **traversal order**, lists the slots sorted by **depth** (0 for a
  root, parent's depth + 1 otherwise), and is rebuilt only after a structural change (add, remove,
  reparent). Rebuilding moves `int`s, not columns, with a **counting sort**: $O(n)$ (L00 §2.8), no
  comparisons, no allocation once the arrays are big enough.

Sorting by depth gives more than order. All entities of one depth form a **level**, and everything a
level reads (its parents' world transforms) was finished by the level before. So levels are the unit
of correctness, and inside a level every entity is independent of the others: a big level can be
split across worker threads with no locks. D28 records exactly this: "Depth levels are the unit of
parallelism (each level reads only earlier levels), and dirty flags are a `byte[]` so workers can
clear them in parallel" (§26 shows why `byte`).

**Counting sort, worked.** Five slots, in arrival order:

```
  slot:    0     1     2     3     4
  entity:  A     B     C     D     E
  parent:  -     A     -     B     C
  depth:   0     1     0     2     1

  1. count per depth:     depth 0: 2   depth 1: 2   depth 2: 1
  2. prefix sums:         levelStart = [0, 2, 4, 5]      (where each level begins; 5 = the end)
  3. scatter the slots:   order      = [0, 2, 1, 4, 3]   (A C | B E | D)
                                         level 0  level 1  level 2
```

Step 2 turns counts into starting positions: level 0 starts at 0; level 1 starts after the 2 slots of
level 0; level 2 after 2 + 2. Step 3 walks the slots in order and drops each into the next free place
of its level. Within a level the slots keep their original order, so the memory walk stays roughly
sequential. §25 builds it, including the trick the code uses to avoid a second array of cursors.

### 13.4 Why TRS and not matrices, and when TRS is not enough

**Local transforms are stored as TRS**, ten `float`s, not as matrices:

- gameplay code sets the parts directly (`setPosition`, `setRotation`, `setScale`, `World.setYaw`), and
  files and animation clips store TRS;
- the world matrix is rebuilt from the TRS every time it changes, so rounding errors never pile up. A
  matrix updated by multiplying in small rotations, tick after tick, slowly stops being a rotation
  (chapter 02, floating point: drift); a quaternion renormalized from fresh inputs cannot drift.

**World transforms are stored as 3×4 matrices**: they are what the renderer copies into instance
records (chapter 02: copied, never converted), and a matrix can represent something TRS cannot,
**shear**.

Where does shear come from? From a parent with a **non-uniform** scale and a rotated child. Take a
parent scale of $(2, 1, 1)$ and a child turned 45° about $Z$. The child's linear part in the world is
$S_pR_c$:

$$S_pR_c = \begin{bmatrix} 2 & 0 & 0 \\ 0 & 1 & 0 \\ 0 & 0 & 1 \end{bmatrix}\begin{bmatrix} 0.7071 & -0.7071 & 0 \\ 0.7071 & 0.7071 & 0 \\ 0 & 0 & 1 \end{bmatrix} = \begin{bmatrix} 1.4142 & -1.4142 & 0 \\ 0.7071 & 0.7071 & 0 \\ 0 & 0 & 1 \end{bmatrix}$$

The first two columns, $(1.4142, 0.7071, 0)$ and $(-1.4142, 0.7071, 0)$, have a dot product of $-2 +
0.5 = -1.5$: they are no longer perpendicular. The child's square has become a parallelogram, a
**shear**. A rotation times a diagonal scale always has perpendicular columns (rotated, scaled axes),
so no TRS can represent this matrix. The hierarchy in `TransformStore` has no such limit: it
multiplies full 3×4 matrices.

**Flattening.** The limit matters for `World.compose` (§27), which collapses a cooked scene's node
tree into one TRS per mesh at spawn time, because the transform store stores TRS. Composing a parent
$(\mathbf{t}_p, q_p, \mathbf{s}_p)$ with a child $(\mathbf{t}_c, q_c, \mathbf{s}_c)$ means writing
$T_pR_pS_p\;T_cR_cS_c$ as one TRS:

- **Translation:** the child's origin is $\mathbf{t}_c$ in the parent's space, so it lands at
  $\mathbf{t}_p + R_p(S_p\,\mathbf{t}_c)$: scale it by the parent's scale, rotate it by the parent's
  rotation, add the parent's translation.
- **Linear part:** $R_pS_pR_cS_c$. If the parent's scale is uniform, $S_p = s\,I$, it commutes with
  $R_c$ (a number times a matrix can be moved anywhere), and this becomes $(R_pR_c)(s\,S_c)$: rotation
  $q_pq_c$, scale $\mathbf{s}_p \odot \mathbf{s}_c$ (component by component).

With a non-uniform $S_p$ and a rotated child the second step is false, and the composed TRS is simply
wrong. With the example above: the composed TRS would be rotation 45°, scale $(2, 1, 1)$, whose
columns are $(1.4142, 1.4142, 0)$ and $(-0.7071, 0.7071, 0)$; the true matrix has $(1.4142, 0.7071, 0)$
and $(-1.4142, 0.7071, 0)$; two entries are off by 0.7071. That is why `World.instantiate`'s Javadoc
warns: "Composition assumes no shear (uniform scale on nodes with rotated children), which holds for
typical exports."

**Worked (uniform).** A parent at $(0, 1, 0)$ with a 90° yaw and uniform scale 2; a child at local
$(1, 0, 0)$ with no rotation. $S_p\mathbf{t}_c = (2, 0, 0)$, turned by the yaw to $(0, 0, -2)$ (§5.2:
$R_y(90°)$ sends $x$ to $-z$), plus the parent translation: the child sits at $(0, 1, -2)$, with a 90°
yaw and scale 2.

## 14. Fixed ticks and interpolation

### 14.1 Fixed ticks, smooth frames

The simulation runs at a fixed **30 ticks per second** (D13, DESIGN §5): movement, physics and AI
always step by exactly 1/30 s, so they behave the same on every machine, whatever the frame rate. The
display runs at 60, 144 or any other rate. If every frame showed the latest tick, things would move
in visible 33 ms jumps; at 144 Hz each tick's state would stay on screen for four or five frames in a
row. Instead the renderer **interpolates between the last two ticks**: each frame shows the world a
fraction of the way from the previous tick's state to the latest one.

### 14.2 The accumulator

`FixedStepper` decides how many ticks to run each frame, and how far the frame is between ticks
(§28 builds it). It keeps an **accumulator**: real time that has passed but not yet been simulated.
Each frame it adds the frame's duration, runs one tick for every whole tick in the accumulator, and
keeps the remainder. The remainder divided by the tick length is the interpolation factor
$\alpha \in [0, 1)$.

**Worked, at 144 frames per second.** One tick is 33,333,333 ns, one frame 6,944,444 ns:

| Frame | Accumulator before ticks | Ticks run | Remainder | $\alpha$ |
|---|---|---|---|---|
| 1 | 6.94 ms | 0 | 6.94 ms | 0.208 |
| 2 | 13.89 ms | 0 | 13.89 ms | 0.417 |
| 3 | 20.83 ms | 0 | 20.83 ms | 0.625 |
| 4 | 27.78 ms | 0 | 27.78 ms | 0.833 |
| 5 | 34.72 ms | 1 | 1.39 ms | 0.042 |
| 6 | 8.33 ms | 0 | 8.33 ms | 0.250 |

Each frame shows the state $\alpha$ of the way from the previous tick to the latest one.

**What it costs: one tick of latency.** Let $T$ be the real time of a frame, $h$ the tick length and
$a$ the remainder. The latest tick simulated the world up to time $T - a$, the one before up to $T -
a - h$. The frame shows $\alpha = a/h$ of the way between them:

$$(T - a - h) + \frac{a}{h}\cdot h = T - h$$

So the picture is **exactly one tick (33 ms) behind real time**, and behind the latest simulated
state by $(1 - \alpha)\,h$, up to one tick. DESIGN §5 accepts a few frames of latency for a CRPG in
exchange for smooth motion.

The stepper also caps the ticks per frame (`maxTicksPerFrame`, 4 by default in `AppConfig`). After a
long stall (loading, a debugger), simulating all the missed ticks at once would take so long that
the next frame would be late too, and the one after, a **spiral of death**. With the cap, a one-second
stall runs 4 ticks and drops the rest: the game slows down for a moment instead of locking up.

### 14.3 Lerping matrices, and what it costs

The renderer interpolates the simplest way possible: it lerps the 12 numbers of the previous and the
current 3×4 world matrices, element by element (`AffineOps.lerp`). Its Javadoc is honest about the
limit: "fine for render interpolation between two close simulation ticks; not a true rotation blend".
What exactly goes wrong?

Translation is fine: its lerp is a straight line between two positions a tick apart. The rotation
part is the problem: §1 said averaging rotation matrices does not give a rotation. Take two rotations
about the same axis that differ by $\Delta$. In the plane perpendicular to the axis, the lerp of $R(0)
= I$ and $R(\Delta)$ at $t$ is

$$(1 - t)\,I + t\,R(\Delta) = \begin{bmatrix} 1 - t + t\cos\Delta & -t\sin\Delta \\ t\sin\Delta & 1 - t + t\cos\Delta \end{bmatrix}$$

Its first column has length $\sqrt{(1 - t + t\cos\Delta)^2 + t^2\sin^2\Delta}$. Expanding the square
and using $\cos^2 + \sin^2 = 1$, this is

$$\sqrt{1 - 2t(1 - t)(1 - \cos\Delta)}$$

At $t = \frac12$ it is $\sqrt{(1 + \cos\Delta)/2} = \cos\frac{\Delta}{2}$ by §3.4's double-angle
formula, and the halfway matrix itself is

$$\frac12\begin{bmatrix} 1 + \cos\Delta & -\sin\Delta \\ \sin\Delta & 1 + \cos\Delta \end{bmatrix} = \cos\tfrac{\Delta}{2}\begin{bmatrix} \cos\frac{\Delta}{2} & -\sin\frac{\Delta}{2} \\ \sin\frac{\Delta}{2} & \cos\frac{\Delta}{2} \end{bmatrix}$$

(using $1 + \cos\Delta = 2\cos^2\frac{\Delta}{2}$ and $\sin\Delta = 2\sin\frac{\Delta}{2}\cos\frac{\Delta}
{2}$). So the halfway matrix is **the correct halfway rotation, shrunk by $\cos\frac{\Delta}{2}$** in
the plane of the rotation; the axis direction keeps its length. For other $t$ the angle is slightly
off too, in the same way as nlerp's (§11.3): the matrix lerp walks the chord.

How big is $\Delta$ between two ticks?

- A character turning at `AgentSync`'s default 10 rad/s turns at most $\frac13$ rad (19.1°) per tick:
  $\cos\frac16 = 0.9861$, a 1.4% shrink at the midpoint, for one frame, while turning at full speed.
- An object spinning a full turn per second turns 12° per tick: $\cos 6° = 0.9945$, a 0.5% shrink.
- Running `AffineOps.lerp` on two yaw matrices $\frac13$ rad apart gives column lengths of 0.98614,
  1.00000 and 0.98614 at $t = 0.5$ ($x$, $y$, $z$; the yaw axis $y$ keeps its length), matching the
  formula.

Normals are unaffected in practice, because the shader normalizes them after transforming
(`jeddyTransformNormal`, and the tangent in `pbr.vert`). What the shortcut buys:

- 12 multiply-adds per instance: no trigonometry, no square roots, no decomposition;
- it works for any affine transform, including attachments composed from joint matrices
  (`World.extractAnimated` builds a previous and a current matrix for a weapon in a hand and hands
  both to the packet, §28);
- the data is already in the GPU's layout, so interpolating and copying into the instance buffer are
  one loop (D30).

The alternative, storing TRS for both ticks and slerping every instance every frame, would need more
packet data and much more arithmetic for a difference nobody can see at 30 Hz. Skeletal animation is
a different case: it is evaluated every frame, not every tick, so poses need no interpolation between
ticks at all (DESIGN §13.4, chapter 09).

# Part B — Building it

Part A derived every formula. Part B writes the code in the order you would: the quaternion type
first, then its mutable and batch versions, the conversions to and from matrices, the cooker's
decomposition, the QTangent, the transform store, the flattening at spawn time, and the interpolation
between ticks. Every block is copied from the repository; where a block leaves lines out, the text
says which.

## 15. Step 1: the quaternion record

### 15.1 The record, its fields and the identity

Start with an empty file, `modules/math/src/main/java/dev/jeddy/math/Quatf.java`:

```java
/**
 * Immutable rotation quaternion {@code (x, y, z, w)}, Hamilton convention: {@code a.mul(b)} applies
 * {@code b} first, then {@code a}. Value type (DESIGN §8.1).
 *
 * <p>Thread ownership: immutable.
 *
 * @param x vector part x
 * @param y vector part y
 * @param z vector part z
 * @param w scalar part
 */
public record Quatf(float x, float y, float z, float w) {
    /** The identity rotation. */
    public static final Quatf IDENTITY = new Quatf(0, 0, 0, 1);
```

- **A `record`.** Java generates the constructor, the four accessors (`q.x()`), `equals`, `hashCode`
  and a `toString` that prints `Quatf[x=0.0, y=0.70710677, z=0.0, w=0.70710677]`. The fields are
  `final`, so a `Quatf` never changes after construction and can be shared between threads freely,
  which is what "Thread ownership: immutable" means.
- **Why immutable, when the engine hates allocation?** This is layer 1 of DESIGN §8.1's three-layer
  math: value types for APIs, configuration and gameplay code that is not per-element. The project
  uses stable Java only (D4), and Java's value classes (JEP 401), which would let the JVM store a
  `Quatf` without an object header or a pointer, are not in JDK 27 at all: its `javac` rejects
  `value record` even with `--enable-preview`, and DESIGN §15 notes "JEP 401 is preview in 28". The
  records are written so that they "become `value record` with a one-word change" once the feature is
  final (DESIGN §8.1).
  Until then, every operation below allocates a new `Quatf`, and per-frame, per-entity code uses
  layers 2 and 3 instead (§16).
- **`float`, `(x, y, z, w)`.** `float` is what the GPU and the rest of the engine use (L00 §7.3), and
  the order is the file's and the shader's (§8.1).
- **`IDENTITY`** is $(\mathbf{0}, 1)$, the rotation by angle 0: $\sin 0 = 0$, $\cos 0 = 1$ in §8.5's
  form, and the sandwich $1\,\mathbf{p}\,1 = \mathbf{p}$.

### 15.2 Making rotations

The constructors a caller actually uses build the unit quaternion of §8.5,
$(\hat{\mathbf{n}}\sin\frac{\theta}{2}, \cos\frac{\theta}{2})$:

```java
    /** Returns the rotation of {@code radians} around the unit {@code axis}. */
    public static Quatf fromAxisAngle(Vec3f axis, float radians) {
        float h = radians * 0.5f;
        float s = (float) Math.sin(h);
        return new Quatf(axis.x() * s, axis.y() * s, axis.z() * s, (float) Math.cos(h));
    }

    /** Returns a rotation around Y (yaw), the common case for characters and cameras. */
    public static Quatf fromYaw(float radians) {
        float h = radians * 0.5f;
        return new Quatf(0, (float) Math.sin(h), 0, (float) Math.cos(h));
    }
```

- `h` is the **half angle**, and `radians * 0.5f` is the cheap way to halve.
- The sine is computed once into `s` and used three times; `Math.sin` and `Math.cos` work in `double`
  (§2.3) and the casts round to `float`.
- The axis must be **unit length**; the method does not check or normalize it (that would cost a
  square root on every call). An axis of length 2 would give a quaternion of norm $\sqrt{4\sin^2 +
  \cos^2} \ne 1$, which is not a rotation (§10.1). The Javadoc says "unit".
- `fromYaw` is `fromAxisAngle(Vec3f.UNIT_Y, radians)` with the zero multiplications removed. Worked:
  `Quatf.fromYaw(MathF.HALF_PI)` prints `Quatf[x=0.0, y=0.70710677, z=0.0, w=0.70710677]`:
  $\sin 45° = \cos 45° = 0.70710677$ in `float`.

Euler angles (§6) are three axis rotations multiplied in order:

```java
    /**
     * Returns the rotation from Euler angles applied as yaw (Y), then pitch (X), then roll (Z),
     * i.e. {@code q = qYaw * qPitch * qRoll}.
     */
    public static Quatf fromYawPitchRoll(float yaw, float pitch, float roll) {
        return fromAxisAngle(Vec3f.UNIT_Y, yaw)
                .mul(fromAxisAngle(Vec3f.UNIT_X, pitch))
                .mul(fromAxisAngle(Vec3f.UNIT_Z, roll));
    }
```

Read the chain as §6.2's moving axes, left to right, or as fixed axes, right to left: roll first. It
allocates five `Quatf`s (three rotations and two products), which is fine for setup code and the
reason it is not used per frame.

### 15.3 The product

```java
    /** Returns {@code this * o} (rotate by {@code o}, then by {@code this}). */
    public Quatf mul(Quatf o) {
        return new Quatf(
                w * o.x + x * o.w + y * o.z - z * o.y,
                w * o.y - x * o.z + y * o.w + z * o.x,
                w * o.z + x * o.y - y * o.x + z * o.w,
                w * o.w - x * o.x - y * o.y - z * o.z);
    }
```

These are the four lines derived in §8.2, with `this` as $q_1$ and `o` as $q_2$: 16 multiplications
and 12 additions or subtractions. Inside the record, `o.x` reads the other quaternion's field
directly (a record's fields are private to the record class, which this code is in). The Javadoc
states the order rule of §8.8, because it is the one thing every caller must get right.

### 15.4 Conjugate, inverse, dot, norm, normalize

```java
    /** Returns the conjugate (the inverse of a unit quaternion). */
    public Quatf conjugate() {
        return new Quatf(-x, -y, -z, w);
    }

    /** Returns the inverse. */
    public Quatf inverse() {
        float n = lengthSquared();
        return new Quatf(-x / n, -y / n, -z / n, w / n);
    }

    /** Returns the dot product. */
    public float dot(Quatf o) {
        return x * o.x + y * o.y + z * o.z + w * o.w;
    }

    /** Returns the squared norm. */
    public float lengthSquared() {
        return x * x + y * y + z * z + w * w;
    }

    /** Returns this quaternion at unit length (identity if it is zero). */
    public Quatf normalize() {
        float n = (float) Math.sqrt(lengthSquared());
        return n > 0 ? new Quatf(x / n, y / n, z / n, w / n) : IDENTITY;
    }
```

- `conjugate` and `inverse` are §8.4's $q^*$ and $q^*/\lVert q\rVert^2$. For a unit quaternion they
  are equal, and `conjugate` is the one to call: no division. `inverse` of the zero quaternion
  divides zero by zero and returns NaNs (`0f / 0f` and `-0f / 0f` are both NaN, L00 §7.3); the zero
  quaternion is not a rotation, so there is nothing sensible to return.
- `dot` is the 4D dot product of §11.1; `lengthSquared` is `dot` with itself, without the square root
  that a length would need.
- `normalize` divides by the norm. Its one decision is the **zero case**: a zero quaternion has no
  direction to keep. It comes from a bug, or from a plain lerp of $q$ and $-q$ at $t = \frac12$; the
  engine's interpolations never produce one, because they flip the sign first (§11.4, and §16 shows
  why the flipped blend cannot vanish), so this branch is defensive. Returning the
  identity, "no rotation", is the safest value a renderer can draw with, and the same choice appears
  in `MQuatf.normalize` and `QuatOps.normalize`.

### 15.5 Rotating a vector

```java
    /** Rotates {@code v} by this unit quaternion. */
    public Vec3f rotate(Vec3f v) {
        // v' = v + 2w(q×v) + 2q×(q×v)
        float tx = 2 * (y * v.z() - z * v.y());
        float ty = 2 * (z * v.x() - x * v.z());
        float tz = 2 * (x * v.y() - y * v.x());
        return new Vec3f(
                v.x() + w * tx + (y * tz - z * ty),
                v.y() + w * ty + (z * tx - x * tz),
                v.z() + w * tz + (x * ty - y * tx));
    }
```

This is §9's Java form. The three `t` lines are $\mathbf{t} = 2\,(\mathbf{u} \times \mathbf{v})$, each
component of the cross product written out (chapter 02's cyclic pattern: the $x$ component uses $y$
and $z$, and so on). In the result, `w * tx` is $w\,\mathbf{t}$ and the bracket `(y * tz - z * ty)`
is the $x$ component of $\mathbf{u} \times \mathbf{t}$. The comment states the formula being
implemented. Eighteen multiplications, as counted in §9; "unit" in the Javadoc is the precondition.

One convenience uses it:

```java
    /** Returns the rotated forward axis (-Z). */
    public Vec3f forward() {
        return rotate(new Vec3f(0, 0, -1));
    }
```

"Where does this rotation make a camera or a character look?" The engine's forward axis is $-Z$
(chapter 02, which way is forward). The test of `lookRotation` uses it (§30.1).

### 15.6 Interpolating and comparing

Slerp is §11.2's formula, plus §11.4's sign flip and the small-angle fallback:

```java
    /** Spherical interpolation along the shortest arc. */
    public Quatf slerp(Quatf o, float t) {
        float d = dot(o);
        float ox = o.x;
        float oy = o.y;
        float oz = o.z;
        float ow = o.w;
        if (d < 0) {
            d = -d;
            ox = -ox;
            oy = -oy;
            oz = -oz;
            ow = -ow;
        }
        float a;
        float b;
        if (d > 0.9995f) {
            a = 1 - t;
            b = t;
        } else {
            double theta = Math.acos(d);
            double sin = Math.sin(theta);
            a = (float) (Math.sin((1 - t) * theta) / sin);
            b = (float) (Math.sin(t * theta) / sin);
        }
        return new Quatf(a * x + b * ox, a * y + b * oy, a * z + b * oz, a * w + b * ow)
                .normalize();
    }
```

- **The short way.** If the dot product is negative, the code uses $-o$ instead of $o$: it copies
  `o`'s components into four locals and negates those, rather than allocating a negated `Quatf`. `d`
  is negated with them, because $q \cdot (-o) = -(q \cdot o)$.
- **The small-angle fallback.** Above 0.9995 ($\Omega < 1.81°$, §11.2) the weights are plain $1 - t$
  and $t$: a lerp, normalized at the end.
- **`double` for the trigonometry.** `theta` is $\Omega$, and the two weights are
  $\sin((1 - t)\Omega)/\sin\Omega$ and $\sin(t\Omega)/\sin\Omega$. Near the threshold $\sin\Omega$ is
  about 0.03, and dividing by it magnifies rounding; `Math.acos` and `Math.sin` work in `double`
  anyway, so keeping the intermediate values in `double` costs nothing and keeps the weights accurate
  to far below `float` rounding.
- **`.normalize()` at the end.** In exact arithmetic slerp stays on the unit sphere. The lerp branch
  does not (it walks the chord, §11.3), and rounding nudges both. One normalization makes the result
  a clean unit quaternion either way.

Nlerp is much shorter:

```java
    /** Normalized linear interpolation along the shortest arc (cheaper than slerp). */
    public Quatf nlerp(Quatf o, float t) {
        float a = 1 - t;
        float b = dot(o) < 0 ? -t : t;
        return new Quatf(x * a + o.x * b, y * a + o.y * b, z * a + o.z * b, w * a + o.w * b)
                .normalize();
    }
```

Here the sign flip moves to the weight: $a\,q + (-t)\,o = a\,q + t\,(-o)$, so negating `b` is the same
as negating `o`, with no copies at all. Then a lerp of the four components and a normalization (§11.3).

The angle of a rotation, read back from $w = \cos\frac{\theta}{2}$ (§8.7):

```java
    /** Returns the rotation angle in radians, in {@code [0, π]}. */
    public float angle() {
        return (float) (2 * Math.acos(Math.min(1, Math.abs(w))));
    }
```

`Math.abs(w)` picks the short angle of the two quaternions $\pm q$. `Math.min(1, …)` guards against
rounding: a normalized quaternion can come out with $\lvert w\rvert$ = 1.0000001, and `Math.acos` of
anything above 1 is NaN (L00 §7.3).

Comparing two rotations:

```java
    /** Returns {@code true} if both represent the same rotation within {@code epsilon}. */
    public boolean approxEquals(Quatf o, float epsilon) {
        return 1 - Math.abs(dot(o)) <= epsilon;
    }
}
```

$\lvert q_0 \cdot q_1\rvert$ is 1 for the same rotation with either sign (§8.7), and $\cos\frac{\varphi}{2}$
for two rotations $\varphi$ apart (§11.1). What does an `epsilon` mean as an angle? With §2.4's
small-angle fact, $1 - \cos\frac{\varphi}{2} \approx \frac{1}{2}\big(\frac{\varphi}{2}\big)^2 =
\frac{\varphi^2}{8}$, so the test accepts rotations up to $\varphi \approx \sqrt{8\,\varepsilon}$ apart:
**about 0.51° for `epsilon` = 1e-5, and 0.16° for 1e-6.** That is coarser than the number suggests,
and worth remembering when you read the tests in §30.1, several of which use 1e-5. (The closing `}`
above ends the record. Two more static methods live in it, between `fromYawPitchRoll` and `mul`:
`lookRotation` and `fromMatrix`, which are Step 4.)

## 16. Step 2: a mutable quaternion and batch kernels

`Quatf` allocates on every operation. The engine's rule is zero allocation per frame (L00 §8.2), and
DESIGN §4.2 adds that systems "own pre-allocated mutable scratch … so they don't rely on escape
analysis" (the JIT's ability to remove an allocation that never leaves a method, which works often
but not always). So the math module has two more layers (DESIGN §8.1).

**Layer 2: `MQuatf`, a mutable scratch quaternion** (`MQuatf.java`):

```java
/**
 * Mutable rotation quaternion for per-frame scratch work without allocation (DESIGN §8.1, layer 2).
 * Same conventions as {@link Quatf}.
 *
 * <p>Thread ownership: single owner thread.
 */
public final class MQuatf {
    /** Vector part x. */
    public float x;

    /** Vector part y. */
    public float y;

    /** Vector part z. */
    public float z;

    /** Scalar part. */
    public float w = 1;

    /** Creates the identity rotation. */
    public MQuatf() {}

    /** Sets the components. */
    public MQuatf set(float nx, float ny, float nz, float nw) {
        x = nx;
        y = ny;
        z = nz;
        w = nw;
        return this;
    }
```

- **Public mutable fields**, so a system can read and write them without calls; "single owner
  thread" because nothing protects them from two threads at once.
- **`w = 1`**: a new `MQuatf` is the identity, not the zero quaternion.
- **Methods return `this`** ("dest-style", DESIGN §8.1), so calls chain:
  `new MQuatf().fromAxisAngle(0, 1, 0, MathF.HALF_PI).rotate(v)` is one line of the test in §30.1.

Two small helpers follow in the file, `set(Quatf q)` (copy an immutable quaternion) and `identity()`
(set $(0, 0, 0, 1)$). Then the operations mirror `Quatf`'s:

```java
    /** Sets to a rotation of {@code radians} around unit axis {@code (ax, ay, az)}. */
    public MQuatf fromAxisAngle(float ax, float ay, float az, float radians) {
        float h = radians * 0.5f;
        float s = (float) Math.sin(h);
        return set(ax * s, ay * s, az * s, (float) Math.cos(h));
    }

    /** Sets {@code this = this * o}. */
    public MQuatf mul(MQuatf o) {
        return set(
                w * o.x + x * o.w + y * o.z - z * o.y,
                w * o.y - x * o.z + y * o.w + z * o.x,
                w * o.z + x * o.y - y * o.x + z * o.w,
                w * o.w - x * o.x - y * o.y - z * o.z);
    }
```

`mul` hides a subtlety worth knowing. It computes all four new components **as arguments of `set`**,
and Java evaluates every argument before the method body runs. So all four expressions read the *old*
`x`, `y`, `z`, `w`. Had it been written as four assignments, `x = w * o.x + …; y = w * o.y - x * o.z
+ …;`, the second line would read the *new* `x`, a classic bug. It even works for `q.mul(q)`, where `o`
is `this`.

```java
    /** Normalizes in place (identity if zero). */
    public MQuatf normalize() {
        float n = x * x + y * y + z * z + w * w;
        if (n == 0) {
            return identity();
        }
        float inv = (float) (1 / Math.sqrt(n));
        return set(x * inv, y * inv, z * inv, w * inv);
    }

    /** Rotates {@code v} in place. */
    public MVec3f rotate(MVec3f v) {
        float tx = 2 * (y * v.z - z * v.y);
        float ty = 2 * (z * v.x - x * v.z);
        float tz = 2 * (x * v.y - y * v.x);
        return v.set(
                v.x + w * tx + (y * tz - z * ty),
                v.y + w * ty + (z * tx - x * tz),
                v.z + w * tz + (x * ty - y * tx));
    }

    /** Normalized lerp towards {@code o} along the shortest arc. */
    public MQuatf nlerp(MQuatf o, float t) {
        float a = 1 - t;
        float b = (x * o.x + y * o.y + z * o.z + w * o.w) < 0 ? -t : t;
        return set(x * a + o.x * b, y * a + o.y * b, z * a + o.z * b, w * a + o.w * b).normalize();
    }

    /** Returns an immutable copy (allocates). */
    public Quatf toQuatf() {
        return new Quatf(x, y, z, w);
    }
}
```

`normalize` computes one reciprocal, `1 / Math.sqrt(n)`, and multiplies four times, instead of four
divisions. `rotate` overwrites the vector it is given (the same trick as `mul`: `v.set(...)` evaluates
every argument from the old `v` first). The small `identity()` and `set(Quatf)` helpers are in the
file too. `toQuatf` says "(allocates)" so nobody calls it per frame by accident.

**Layer 3: `QuatOps`, kernels over struct-of-arrays data.** When there are many quaternions (one per
entity, one per joint), the engine stores them as four `float[]` columns (L03 §4) and runs a static
method over a range of them:

```java
/**
 * Quaternion kernels on struct-of-arrays data ({@code float[] qx, qy, qz, qw}). Allocation-free.
 *
 * <p>Thread ownership: stateless, callable from any thread.
 */
public final class QuatOps {
    private QuatOps() {}

    /** Normalizes quaternions {@code [start, end)} in place (identity if zero). */
    public static void normalize(
            float[] qx, float[] qy, float[] qz, float[] qw, int start, int end) {
        for (int i = start; i < end; i++) {
            float n = qx[i] * qx[i] + qy[i] * qy[i] + qz[i] * qz[i] + qw[i] * qw[i];
            if (n > 0) {
                float inv = (float) (1 / Math.sqrt(n));
                qx[i] *= inv;
                qy[i] *= inv;
                qz[i] *= inv;
                qw[i] *= inv;
            } else {
                qx[i] = 0;
                qy[i] = 0;
                qz[i] = 0;
                qw[i] = 1;
            }
        }
    }
```

- **A range `[start, end)`** (L00 §2.6) rather than "all of it", so the job system can hand chunks of
  one array to several workers (a `JobKernel` receives exactly such a range, §26).
- **Stateless**, so "callable from any thread": two workers on disjoint ranges never touch the same
  element.
- The loop body is `MQuatf.normalize` on the `i`-th element of four arrays.

```java
    /**
     * Normalized lerp along the shortest arc: {@code out[i] = nlerp(a[i], b[i], t)} for {@code
     * [start, end)}. Good enough for animation blending between close keys.
     */
    public static void nlerp(
            float[] ax,
            float[] ay,
            float[] az,
            float[] aw,
            float[] bx,
            float[] by,
            float[] bz,
            float[] bw,
            float t,
            float[] ox,
            float[] oy,
            float[] oz,
            float[] ow,
            int start,
            int end) {
        float s = 1 - t;
        for (int i = start; i < end; i++) {
            float d = ax[i] * bx[i] + ay[i] * by[i] + az[i] * bz[i] + aw[i] * bw[i];
            float tt = d < 0 ? -t : t;
            float x = ax[i] * s + bx[i] * tt;
            float y = ay[i] * s + by[i] * tt;
            float z = az[i] * s + bz[i] * tt;
            float w = aw[i] * s + bw[i] * tt;
            float inv = (float) (1 / Math.sqrt(x * x + y * y + z * z + w * w));
            ox[i] = x * inv;
            oy[i] = y * inv;
            oz[i] = z * inv;
            ow[i] = w * inv;
        }
    }
```

Twelve arrays, a weight and a range: verbose, but nothing is allocated and every array is walked in
order. `s = 1 - t` is hoisted out of the loop because it does not change. There is no zero check
before the square root, and none is needed for unit inputs: after the sign flip the dot product $d$ is
at least 0, and the squared length of the blend is $s^2 + t^2 + 2st\,d \ge s^2 + t^2 \ge \frac12$ (the
smallest value of $s^2 + t^2$ with $s + t = 1$ is at $s = t = \frac12$).

```java
    /**
     * Rotates struct-of-arrays vectors {@code [start, end)} in place, each by its own unit
     * quaternion.
     */
    public static void rotate(
            float[] qx,
            float[] qy,
            float[] qz,
            float[] qw,
            float[] vx,
            float[] vy,
            float[] vz,
            int start,
            int end) {
        for (int i = start; i < end; i++) {
            float x = qx[i];
            float y = qy[i];
            float z = qz[i];
            float w = qw[i];
            float px = vx[i];
            float py = vy[i];
            float pz = vz[i];
            float tx = 2 * (y * pz - z * py);
            float ty = 2 * (z * px - x * pz);
            float tz = 2 * (x * py - y * px);
            vx[i] = px + w * tx + (y * tz - z * ty);
            vy[i] = py + w * ty + (z * tx - x * tz);
            vz[i] = pz + w * tz + (x * ty - y * tx);
        }
    }

    /**
     * Writes the yaw rotation {@code (0, sin(a/2), 0, cos(a/2))} for each angle in {@code [start,
     * end)}, the common case for characters turning on the ground.
     */
    public static void fromYaw(
            float[] radians, float[] qx, float[] qy, float[] qz, float[] qw, int start, int end) {
        for (int i = start; i < end; i++) {
            float h = radians[i] * 0.5f;
            qx[i] = 0;
            qy[i] = (float) Math.sin(h);
            qz[i] = 0;
            qw[i] = (float) Math.cos(h);
        }
    }
}
```

`rotate` loads each element into locals first, then computes `Quatf.rotate`'s 18 multiplications, then
stores. Loading first matters for correctness here: the output overwrites `vx[i]`, and the later lines
still need the old value, which is safe in `px`.

The engine's own hottest loops (the transform store, the animation blend, `World.compose`) write the
same arithmetic inline over their own arrays, as you will see in §26 and §27; `QuatOps` is the
general-purpose set for code that has its quaternions in plain columns. Exercise 9 adds a slerp to it.

## 17. Step 3: from quaternion to matrix

The renderer and the transform store want the 3×4 matrix $T\,R\,S$ (chapter 02) built from a
position, a quaternion and a scale. In `AffineOps` (row-major 3×4 blocks of 12 floats, `STRIDE` = 12):

```java
    /** Writes translation * rotation(quaternion) * scale. */
    public static void composeTRS(
            float px,
            float py,
            float pz,
            float qx,
            float qy,
            float qz,
            float qw,
            float sx,
            float sy,
            float sz,
            float[] out,
            int o) {
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

- **The nine products first.** §10.1's matrix uses each of $x^2, y^2, z^2, xy, xz, yz, wx, wy, wz$ in
  two places; computing them once into locals is what keeps the cost at 9 products plus 9 doublings.
- **Row by row.** Row-major means `out[o .. o+3]` is the first row: the three entries of row 0 of
  $R(q)$, then the translation $p_x$. `out[o + 4 .. o + 7]` is row 1, `out[o + 8 .. o + 11]` row 2.
  Compare each line with the matrix of §10.1: `out[o + 1]` is row 0, column 1, $2(xy - wz)$.
- **Scale multiplies columns.** $RS$ scales column $j$ by $s_j$ (chapter 02, TRS), and in row-major
  storage column 0 is at offsets 0, 4, 8: those three lines end in `* sx`, offsets 1, 5, 9 in `* sy`,
  and 2, 6, 10 in `* sz`.
- **The translation** goes in unchanged at offsets 3, 7, 11, because $T$ is applied last.
- **A unit quaternion is assumed** (§10.1). The method trusts its caller, as `TransformStore`'s
  "a unit quaternion" contract says.

The method is 246 bytes of bytecode: more than `MaxInlineSize` (35), so the JIT does not inline it
everywhere, but less than `FreqInlineSize` (325), so it is inlined into the hot loops that call it,
such as the transform store's level kernel (§26). The batch version over SoA columns just calls it in
a loop:

```java
    /**
     * Composes TRS transforms for items {@code [start, end)} from struct-of-arrays inputs into
     * {@code out[i * 12]}.
     */
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
        for (int i = start; i < end; i++) {
            composeTRS(
                    px[i],
                    py[i],
                    pz[i],
                    qx[i],
                    qy[i],
                    qz[i],
                    qw[i],
                    sx[i],
                    sy[i],
                    sz[i],
                    out,
                    i * STRIDE);
        }
    }
```

`MathBench.composeTRS10k` measures it over 10,000 items (§32).

The immutable column-major `Mat4f` (chapter 02) has the same matrix for uniforms and tests:

```java
    /** Returns the rotation matrix of a unit quaternion. */
    public static Mat4f rotation(Quatf q) {
        return trs(Vec3f.ZERO, q, Vec3f.ONE);
    }

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
        return new Mat4f(
                (1 - 2 * (yy + zz)) * s.x(),
                2 * (xy + wz) * s.x(),
                2 * (xz - wy) * s.x(),
                0,
                2 * (xy - wz) * s.y(),
                (1 - 2 * (xx + zz)) * s.y(),
                2 * (yz + wx) * s.y(),
                0,
                2 * (xz + wy) * s.z(),
                2 * (yz - wx) * s.z(),
                (1 - 2 * (xx + yy)) * s.z(),
                0,
                t.x(),
                t.y(),
                t.z(),
                1);
    }
```

`Mat4f`'s components are listed **column by column** (`m00, m01, m02, m03` is column 0), so the
first three arguments are §10.1's first column, $R\,\mathbf{e}_x$, all times `s.x()`; the fourth is
the 0 of the bottom row; the last four are the translation column and the 1. The same numbers as
`composeTRS`, in the other storage order. The test `affineOpsAgreeWithMat4` checks that the two agree
for 100 random transforms (§30.1).

## 18. Step 4: from matrix to quaternion

Back in `Quatf`, §10.2's four branches:

```java
    /** Returns the quaternion of a rotation matrix given row by row ({@code mRC}). */
    public static Quatf fromMatrix(
            float m00,
            float m01,
            float m02,
            float m10,
            float m11,
            float m12,
            float m20,
            float m21,
            float m22) {
        float trace = m00 + m11 + m22;
        float x;
        float y;
        float z;
        float w;
        if (trace > 0) {
            float s = (float) Math.sqrt(trace + 1.0) * 2;
            w = 0.25f * s;
            x = (m21 - m12) / s;
            y = (m02 - m20) / s;
            z = (m10 - m01) / s;
        } else if (m00 > m11 && m00 > m22) {
            float s = (float) Math.sqrt(1.0 + m00 - m11 - m22) * 2;
            w = (m21 - m12) / s;
            x = 0.25f * s;
            y = (m01 + m10) / s;
            z = (m02 + m20) / s;
        } else if (m11 > m22) {
            float s = (float) Math.sqrt(1.0 + m11 - m00 - m22) * 2;
            w = (m02 - m20) / s;
            x = (m01 + m10) / s;
            y = 0.25f * s;
            z = (m12 + m21) / s;
        } else {
            float s = (float) Math.sqrt(1.0 + m22 - m00 - m11) * 2;
            w = (m10 - m01) / s;
            x = (m02 + m20) / s;
            y = (m12 + m21) / s;
            z = 0.25f * s;
        }
        return new Quatf(x, y, z, w).normalize();
    }
```

- **The parameters are row first**: `m01` is row 0, column 1. `Mat4f`'s components are **column
  first** (`m01` there is column 0, row 1). This is chapter 02's naming trap, and the test that
  converts a `Mat4f` back passes `r.m00(), r.m10(), r.m20(), r.m01(), …`: `Mat4f`'s column 1, row 0
  is `fromMatrix`'s row 0, column 1.
- **Each branch is one case of §10.2.** In every branch `s` is 4 times the component chosen as the
  largest (`w`, then `x`, `y`, `z`), and is at least 2. The first branch computes $s = 2\sqrt{\text{trace}
  + 1}$, then $w = s/4$ and the other three from the differences $m_{21} - m_{12} = 4wx$ and so on.
  The other branches use $1 + m_{00} - m_{11} - m_{22} = 4x^2$ and the sums of mirrored entries.
- **`trace + 1.0`** is a `double` expression (1.0 is a `double` literal), so the square root is taken
  in `double` and rounded once by the `(float)` cast. `0.25f * s` is $s/4$ without a division.
- **`.normalize()` at the end** absorbs rounding, and also makes the result a rotation when the input
  is only nearly a rotation matrix (a matrix built from slightly non-perpendicular axes, for example).

`lookRotation` (§10.3) builds three axes and hands them to `fromMatrix` as columns:

```java
    /**
     * Returns the rotation that turns -Z (the forward axis) towards {@code forward}, keeping {@code
     * up} as close to +Y of the result as possible.
     */
    public static Quatf lookRotation(Vec3f forward, Vec3f up) {
        Vec3f f = forward.normalize();
        Vec3f r = f.cross(up).normalize();
        if (r.lengthSquared() == 0) {
            r = f.cross(Math.abs(f.y()) < 0.99f ? Vec3f.UNIT_Y : Vec3f.UNIT_X).normalize();
        }
        Vec3f u = r.cross(f);
        // Columns of the rotation matrix: right, up, -forward
        return fromMatrix(r.x(), u.x(), -f.x(), r.y(), u.y(), -f.y(), r.z(), u.z(), -f.z());
    }
```

- `f × up` is zero when `forward` and `up` are parallel (looking straight up or down with a $+Y$
  up), and `Vec3f.normalize` returns the zero vector for a zero input. The `if` catches that and
  crosses with another helper axis: $+Y$, unless `f` is itself nearly vertical, in which case $+X$.
- `u = r × f` is the true up, already unit length (two perpendicular unit vectors, chapter 02).
- The arguments are given **row by row** (because `fromMatrix` wants rows), and each row holds one
  component of each column: row 0 is `r.x(), u.x(), -f.x()`. The comment says what the columns are.

## 19. Step 5: splitting node matrices in the cooker

`GltfImporter` reads glTF nodes for the cooker (chapter 10). A node has either `translation`,
`rotation` and `scale`, or a `matrix` of 16 floats in **column-major** order. When it has a matrix,
the importer starts from the defaults (identity TRS) and overwrites them:

```java
            float[] t = floats(node, "translation", new float[] {0, 0, 0});
            float[] r = floats(node, "rotation", new float[] {0, 0, 0, 1});
            float[] s = floats(node, "scale", new float[] {1, 1, 1});
            if (node.containsKey("matrix")) {
                float[] m = floats(node, "matrix", null);
                decompose(m, t, r, s);
            }
```

`decompose` is §10.4's four steps. In stages, first the translation, the scales and the mirror:

```java
    /** Splits a column-major affine matrix into translation, rotation (quaternion) and scale. */
    static void decompose(float[] m, float[] t, float[] r, float[] s) {
        t[0] = m[12];
        t[1] = m[13];
        t[2] = m[14];
        s[0] = (float) Math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]);
        s[1] = (float) Math.sqrt(m[4] * m[4] + m[5] * m[5] + m[6] * m[6]);
        s[2] = (float) Math.sqrt(m[8] * m[8] + m[9] * m[9] + m[10] * m[10]);
        double det =
                m[0] * (m[5] * m[10] - m[9] * m[6])
                        - m[4] * (m[1] * m[10] - m[9] * m[2])
                        + m[8] * (m[1] * m[6] - m[5] * m[2]);
        if (det < 0) {
            s[0] = -s[0];
        }
```

- **Column-major indexing.** Column $c$ occupies `m[4c .. 4c + 3]`, so the translation column is
  `m[12..14]` and the three linear columns start at 0, 4 and 8.
- **Scales** are the column lengths (step 2).
- **The determinant** is the cofactor expansion along the first row (chapter 02, the general 3×3
  inverse): with $a_{rc}$ = `m[4c + r]`, it is $a_{00}(a_{11}a_{22} - a_{12}a_{21}) - a_{01}(a_{10}a_{22} -
  a_{12}a_{20}) + a_{02}(a_{10}a_{21} - a_{11}a_{20})$. Only its sign is used.
- **The mirror goes to $x$** (step 3).

Then the rotation, in `double`:

```java
        double r00 = m[0] / s[0], r10 = m[1] / s[0], r20 = m[2] / s[0];
        double r01 = m[4] / s[1], r11 = m[5] / s[1], r21 = m[6] / s[1];
        double r02 = m[8] / s[2], r12 = m[9] / s[2], r22 = m[10] / s[2];
        double trace = r00 + r11 + r22;
        double x, y, z, w;
        if (trace > 0) {
            double k = Math.sqrt(trace + 1) * 2;
            w = 0.25 * k;
            x = (r21 - r12) / k;
            y = (r02 - r20) / k;
            z = (r10 - r01) / k;
        } else if (r00 > r11 && r00 > r22) {
            double k = Math.sqrt(1 + r00 - r11 - r22) * 2;
            w = (r21 - r12) / k;
            x = 0.25 * k;
            y = (r01 + r10) / k;
            z = (r02 + r20) / k;
        } else if (r11 > r22) {
            double k = Math.sqrt(1 + r11 - r00 - r22) * 2;
            w = (r02 - r20) / k;
            x = (r01 + r10) / k;
            y = 0.25 * k;
            z = (r12 + r21) / k;
        } else {
            double k = Math.sqrt(1 + r22 - r00 - r11) * 2;
            w = (r10 - r01) / k;
            x = (r02 + r20) / k;
            y = (r12 + r21) / k;
            z = 0.25 * k;
        }
        r[0] = (float) x;
        r[1] = (float) y;
        r[2] = (float) z;
        r[3] = (float) w;
    }
```

- Dividing column $j$ by the **signed** scale $s_j$ gives the pure rotation $R$; the names `rRC` are
  row first, so `r10` = `m[1] / s[0]` is row 1 of column 0.
- The four branches are `Quatf.fromMatrix`'s, in `double` (the cooker runs offline; precision costs
  nothing there). The variable is called `k` here because `s` is taken by the scale array.
- The quaternion is written in glTF's and the engine's `(x, y, z, w)` order.
- There is no final normalization: for a true TRS matrix the result is unit length up to rounding,
  and the importer passes it on as computed.
- There is no check for a zero scale either. A column of length 0 makes every `r` entry of that
  column $0/0$, NaN, and the whole quaternion comes out NaN (a node matrix that squashes an axis flat,
  which some exporters use to hide a node, would do it).

The test `decomposesMirroredMatrices` feeds it a rotation of 30° about $Z$ with scale $(-1, 2, 3)$
and translation $(4, 5, 6)$ and gets exactly those back (§30.3). Exercise 11 round-trips random
matrices.

## 20. Step 6: encoding a QTangent

`MeshEncoding` (in `modules/asset/src/main/java/dev/jeddy/asset/format/`) holds the encodings of the
`.jmesh` vertex attributes ([docs/formats/jmesh.md](../formats/jmesh.md)). The cooker's mesh writer
calls the encoder once per vertex, with the normal, the tangent and the handedness from the glTF
file (or from MikkTSpace when the file has none, §29.4), and a default tangent $(1, 0, 0)$ with
$h = +1$ for meshes without tangents:

```java
        short[] q = new short[4];
        float[] defaultTangent = {1, 0, 0};
        for (int v = 0; v < vertexCount; v++) {
            int at = (int) attributesOffset + v * MeshData.ATTRIBUTE_STRIDE;
            if (tangents != null) {
                MeshEncoding.encodeQTangent(
                        normals, 3 * v, tangents, 4 * v, tangents[4 * v + 3], q, 0);
            } else {
                MeshEncoding.encodeQTangent(normals, 3 * v, defaultTangent, 0, 1f, q, 0);
            }
            for (int k = 0; k < 4; k++) {
                b.putShort(at + 2 * k, q[k]);
            }
```

(from `MeshWriter`; glTF tangents are four floats, the fourth being the handedness, hence `4 * v`
and `tangents[4 * v + 3]`). The four shorts go to bytes 0–7 of the vertex's 16-byte attribute record.

The encoder is long, so here it is in four stages. **Stage 1: a clean, perpendicular pair.**

```java
    /** Smallest |w| written, so the handedness sign survives quantization. */
    private static final float W_BIAS = 1f / 32767f;

    /**
     * Encodes a tangent frame as a QTangent.
     *
     * @param n unit normal (x, y, z at {@code nOff})
     * @param t tangent (x, y, z at {@code tOff}); orthogonalized against the normal here
     * @param handedness +1 or -1: {@code bitangent = cross(normal, tangent) * handedness}
     * @param out receives four {@code snorm16} values (x, y, z, w) at {@code outOff}
     */
    public static void encodeQTangent(
            float[] n, int nOff, float[] t, int tOff, float handedness, short[] out, int outOff) {
        float nx = n[nOff];
        float ny = n[nOff + 1];
        float nz = n[nOff + 2];
        float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (nl < 1e-12f) {
            nx = 0;
            ny = 0;
            nz = 1;
        } else {
            nx /= nl;
            ny /= nl;
            nz /= nl;
        }
        float tx = t[tOff];
        float ty = t[tOff + 1];
        float tz = t[tOff + 2];
        float d = nx * tx + ny * ty + nz * tz;
        tx -= nx * d;
        ty -= ny * d;
        tz -= nz * d;
        float tl = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (tl < 1e-12f) { // degenerate: any vector orthogonal to the normal
            if (Math.abs(nx) < 0.9f) {
                tx = 0;
                ty = -nz;
                tz = ny;
            } else {
                tx = nz;
                ty = 0;
                tz = -nx;
            }
            tl = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
        }
        tx /= tl;
        ty /= tl;
        tz /= tl;
```

- The arrays-plus-offset signature (`n, nOff`) lets the caller pass its whole normal array and a
  position in it: no `Vec3f` per vertex.
- **The normal** is normalized; a zero normal (broken input) becomes $+Z$ instead of NaN.
- **The tangent** is made perpendicular to the normal by removing its part along it: chapter 02's
  Gram–Schmidt step, $\mathbf{t} \leftarrow \mathbf{t} - (\mathbf{n} \cdot \mathbf{t})\,\mathbf{n}$. Tools
  export tangents that are only roughly perpendicular, and §12.2's argument needs exactly
  perpendicular unit vectors.
- **If nothing is left** (the tangent was parallel to the normal), any perpendicular vector will do.
  $(0, -n_z, n_y)$ is $\mathbf{e}_x \times \mathbf{n}$ and $(n_z, 0, -n_x)$ is $\mathbf{e}_y \times
  \mathbf{n}$; both are perpendicular to $\mathbf{n}$, and each is zero when $\mathbf{n}$ is parallel
  to its helper axis. So the code uses $\mathbf{e}_x$ unless the normal is close to $\pm\mathbf{e}_x$
  ($\lvert n_x\rvert \ge 0.9$), in which case it uses $\mathbf{e}_y$.

**Stage 2: the right-handed frame as a matrix.**

```java
        // b = cross(n, t): the matrix [t b n] is a rotation.
        float bx = ny * tz - nz * ty;
        float by = nz * tx - nx * tz;
        float bz = nx * ty - ny * tx;

        float m00 = tx, m10 = ty, m20 = tz;
        float m01 = bx, m11 = by, m21 = bz;
        float m02 = nx, m12 = ny, m22 = nz;
```

The bitangent is **computed**, never taken from the input, so the frame is right-handed whatever the
input said (§12.2: $\det[\mathbf{t}\;\mathbf{n}\times\mathbf{t}\;\mathbf{n}] = +1$). The names are
`mRC`, row then column, so each line fills one **column**: column 0 is $\mathbf{t}$, column 1 is
$\mathbf{b}$, column 2 is $\mathbf{n}$.

**Stage 3: matrix to quaternion**, the four branches of §10.2, written exactly as in
`Quatf.fromMatrix` but in local variables and without the final normalization (the frame is exactly
orthonormal by construction, and quantization will cost far more than rounding):

```java
        float qx, qy, qz, qw;
        float trace = m00 + m11 + m22;
        if (trace > 0) {
            float s = (float) Math.sqrt(trace + 1) * 2;
            qw = 0.25f * s;
            qx = (m21 - m12) / s;
            qy = (m02 - m20) / s;
            qz = (m10 - m01) / s;
        } else if (m00 > m11 && m00 > m22) {
            float s = (float) Math.sqrt(1 + m00 - m11 - m22) * 2;
            qw = (m21 - m12) / s;
            qx = 0.25f * s;
            qy = (m01 + m10) / s;
            qz = (m02 + m20) / s;
        } else if (m11 > m22) {
            float s = (float) Math.sqrt(1 + m11 - m00 - m22) * 2;
            qw = (m02 - m20) / s;
            qx = (m01 + m10) / s;
            qy = 0.25f * s;
            qz = (m12 + m21) / s;
        } else {
            float s = (float) Math.sqrt(1 + m22 - m00 - m11) * 2;
            qw = (m10 - m01) / s;
            qx = (m02 + m20) / s;
            qy = (m12 + m21) / s;
            qz = 0.25f * s;
        }
```

(Why not call `Quatf.fromMatrix`? The `asset` module does not depend on `math` at all: its build
script declares only `core` and `compress`. And a `Quatf` per vertex would allocate.)

**Stage 4: the sign carries the handedness, then quantize.**

```java
        if (qw < 0) {
            qx = -qx;
            qy = -qy;
            qz = -qz;
            qw = -qw;
        }
        if (qw < W_BIAS) {
            float scale = (float) Math.sqrt(1 - W_BIAS * W_BIAS);
            float len = (float) Math.sqrt(qx * qx + qy * qy + qz * qz);
            qx = qx / len * scale;
            qy = qy / len * scale;
            qz = qz / len * scale;
            qw = W_BIAS;
        }
        if (handedness < 0) {
            qx = -qx;
            qy = -qy;
            qz = -qz;
            qw = -qw;
        }
        out[outOff] = snorm16(qx);
        out[outOff + 1] = snorm16(qy);
        out[outOff + 2] = snorm16(qz);
        out[outOff + 3] = snorm16(qw);
    }
```

1. **Force $w \ge 0$**, allowed because $-q$ is the same rotation (§8.7). Now the sign of $w$ is free.
2. **Keep $w$ away from zero** (§12.2). If $w$ is below `W_BIAS` = 1/32767, set it to exactly
   `W_BIAS` and give the vector part the length $\sqrt{1 - W^2}$ (divide by its current length, times
   `scale`), so that $\lVert q\rVert^2 = (1 - W^2) + W^2 = 1$ still. The vector part cannot be zero
   here: a unit quaternion with $w < 3 \times 10^{-5}$ has a vector part of length almost 1.
3. **Negate for a mirrored frame**, which makes $w$ negative without changing the rotation.
4. **Quantize** each component with `snorm16` (chapter 02): `W_BIAS` × 32767 rounds to 1, so a biased
   $w$ is stored as $\pm 1$, never 0.

The quantizer and its inverse, at the end of the file:

```java
    /** Returns {@code v} clamped to [-1, 1] as a rounded {@code snorm16}. */
    public static short snorm16(float v) {
        return (short) Math.round(Math.max(-1f, Math.min(1f, v)) * 32767f);
    }

    /** Returns the value of an {@code snorm16}. */
    public static float unsnorm16(short v) {
        return Math.max(-1f, v / 32767f);
    }
```

The step is $1/32767 \approx 3 \times 10^{-5}$, so a quantized component is off by at most half of
that. §12.2's table lists real outputs.

## 21. Step 7: decoding a QTangent, in Java and on the GPU

**The Java reference.** The decoder in `MeshEncoding` is what tests compare with, and what the shader
must agree with:

```java
    /** Decodes the normal of a QTangent into {@code out[outOff..+3)}. */
    public static void qtangentNormal(short[] q, int qOff, float[] out, int outOff) {
        rotate(q, qOff, 0, 0, 1, out, outOff);
    }

    /** Decodes the tangent of a QTangent into {@code out[outOff..+3)}. */
    public static void qtangentTangent(short[] q, int qOff, float[] out, int outOff) {
        rotate(q, qOff, 1, 0, 0, out, outOff);
    }

    /** Returns the bitangent handedness (+1 or -1) of a QTangent. */
    public static float qtangentHandedness(short[] q, int qOff) {
        return q[qOff + 3] < 0 ? -1f : 1f;
    }

    private static void rotate(
            short[] q, int qOff, float vx, float vy, float vz, float[] out, int outOff) {
        float x = unsnorm16(q[qOff]);
        float y = unsnorm16(q[qOff + 1]);
        float z = unsnorm16(q[qOff + 2]);
        float w = unsnorm16(q[qOff + 3]);
        float len = (float) Math.sqrt(x * x + y * y + z * z + w * w);
        x /= len;
        y /= len;
        z /= len;
        w /= len;
        // v' = v + 2 * cross(q.xyz, cross(q.xyz, v) + w * v)
        float cx = y * vz - z * vy + w * vx;
        float cy = z * vx - x * vz + w * vy;
        float cz = x * vy - y * vx + w * vz;
        out[outOff] = vx + 2 * (y * cz - z * cy);
        out[outOff + 1] = vy + 2 * (z * cx - x * cz);
        out[outOff + 2] = vz + 2 * (x * cy - y * cx);
    }
```

- **Why rotating the axes gives the vectors.** The quaternion is the rotation whose matrix is
  $[\,\mathbf{t}\;\mathbf{b}\;\mathbf{n}\,]$, and a matrix sends $\mathbf{e}_z$ to its third column and
  $\mathbf{e}_x$ to its first. So rotating $(0, 0, 1)$ gives the normal and rotating $(1, 0, 0)$ the
  tangent.
- **Normalize first.** Quantization leaves the four numbers slightly off unit length, and §9's formula
  assumes unit length.
- **The rotation is the shader's form** of §9, $\mathbf{v} + 2\,\mathbf{u} \times (\mathbf{u} \times
  \mathbf{v} + w\,\mathbf{v})$: `c` is the bracket, and the three output lines are $\mathbf{v} + 2\,
  \mathbf{u} \times \mathbf{c}$. Writing the Java reference in the same form as the GLSL keeps the two
  close. They are not bit for bit equal: a GPU may fuse a multiply and an add into one rounding, and
  its driver chooses how the shader's arithmetic is compiled, so the results agree to about the last
  bits of a `float`, not exactly.
- **The handedness** is the sign of the stored `w`, read straight from the `short`: negative means
  $-1$. The rotation does not care about the sign (§8.7).

**On the GPU.** The same decoding in GLSL (L00 §10.2), in `engine/vertex.glsl`, which every mesh
vertex shader includes:

```glsl
// The mesh vertex layout of MeshRegistry (DESIGN §9.2, D27) and QTangent decoding. The skin
// stream (locations 4 and 5) is declared by engine/skinning.glsl.
#pragma once

layout(location = 0) in vec3 jeddyPosition;
layout(location = 1) in vec4 jeddyQTangent; // snorm16 x4, normalized by the vertex fetch
layout(location = 2) in vec2 jeddyUv0;
layout(location = 3) in vec2 jeddyUv1;

vec3 jeddyQuatRotate(vec4 q, vec3 v) {
    return v + 2.0 * cross(q.xyz, cross(q.xyz, v) + q.w * v);
}

// Normal, tangent and bitangent sign of the vertex (MeshEncoding is the reference).
void jeddyTangentFrame(out vec3 normal, out vec3 tangent, out float handedness) {
    vec4 q = normalize(jeddyQTangent);
    normal = jeddyQuatRotate(q, vec3(0.0, 0.0, 1.0));
    tangent = jeddyQuatRotate(q, vec3(1.0, 0.0, 0.0));
    handedness = jeddyQTangent.w < 0.0 ? -1.0 : 1.0;
}
```

GLSL reads much like Java. The new pieces:

- `#pragma once` belongs to the engine's shader include system (chapter
  [05](05-opengl-from-java.md)): a file included twice is pasted once.
- `layout(location = 1) in vec4 jeddyQTangent;` declares a **vertex input**: one value per vertex, fed
  by vertex fetch from attribute location 1, which is the location `MeshRegistry` described with
  `glVertexArrayAttribFormat(vao, 1, 4, GL_SHORT, true, 0)` (§12.3). Because that format is
  normalized, the four shorts arrive as floats in $[-1, 1]$; the comment says so.
- `vec4`, `vec3` are GLSL's built-in vector types. `q.xyz` is a **swizzle**: the first three
  components as a `vec3`, here the vector part $\mathbf{u}$; `q.w` is the scalar part. This is why the
  engine stores quaternions as $(x, y, z, w)$ (§8.1).
- `cross` and `normalize` are built-in functions; `cross` is chapter 02's cross product.
- `out` parameters are how a GLSL function returns several values.

`jeddyQuatRotate` is word for word §9's shader form, and `jeddyTangentFrame` is the Java reference
above. The handedness reads the **raw** `jeddyQTangent.w`, before normalizing: dividing by a positive
length does not change a sign.

The vertex shader runs once for every vertex the GPU processes; for indexed meshes GPUs usually reuse
the results for a vertex shared by neighbouring triangles while it is still in a small cache (the
post-transform cache, which the cooker orders triangles for, chapter 10), so the decode often runs
fewer times than there are triangle corners. `pbr.vert` calls `jeddyTangentFrame`
and transforms the results into world space (§29.4).

## 22. Step 8: the transform store's data

Now the hierarchy of §13. Start the file `modules/scene/src/main/java/dev/jeddy/scene/TransformStore.java`
with the class comment, which states the whole design and the thread rule:

```java
/**
 * The transform hierarchy (TASKS M5-02, DESIGN §13.1): local position, rotation and scale in SoA
 * columns, a parent per entity, and world transforms as row-major 3×4 blocks ({@link AffineOps},
 * the GPU instance layout) in {@link #world()}.
 *
 * <p>{@link #update} recomputes only what changed: an entity whose local transform was set, and
 * every descendant of an entity whose world transform changed. It walks a depth-sorted order
 * (parents before children), one depth level at a time; levels with many entities run in parallel
 * on the job system, since everything a level reads (its parents' world transforms) was finished by
 * the level before (D28). Data stays in dense slots (swap-remove); only the traversal order, an
 * {@code int} permutation, is rebuilt after structural changes (add, remove, reparent).
 *
 * <p>Static entities ({@link #setStatic}) are marked for consumers such as render extraction, which
 * upload them once; their world transforms are computed like any other, so they cost nothing per
 * frame while they do not change.
 *
 * <p>Thread ownership: the owner thread (the game thread) for every method; {@link #update} borrows
 * job workers internally.
 */
public final class TransformStore implements EntityStore {
    private static final byte DIRTY = 1;
    private static final byte STATIC = 2;
    private static final int PARALLEL_LEVEL = 2048;
    private static final int GRAIN = 512;
    private static final int STRIDE = AffineOps.STRIDE;
```

- **Two flag bits** in one `byte` per entity (L00 §6.2): `DIRTY` is bit 0 ("my local transform
  changed since the last update"), `STATIC` bit 1 ("render may upload me once", D49).
- **`PARALLEL_LEVEL` = 2048**: a level smaller than that runs on the calling thread, because waking
  workers costs more than the work (L03 §15 derives when parallel loops pay). **`GRAIN` = 512**: the
  chunk size workers claim.
- **`implements EntityStore`**: the entity pool calls `remove(entity)` on every registered store when an
  entity is destroyed (L03 §7), so a destroyed entity never leaves a transform behind.

The fields:

```java
    private final EntityPool pool;
    private final SparseSet members = new SparseSet(1024, 64);
    private int capacity;
    private int[] entities;
    private float[] px, py, pz, qx, qy, qz, qw, sx, sy, sz;
    private int[] parentEntity; // handle of the parent, or NULL
    private int[] parentSlot; // dense index of the parent, or -1 (rebuilt with the order)
    private int[] childCount;
    private byte[] flags;
    private int[] changedFrame; // the update in which the world transform last changed
    private float[] world;

    private int[] order = new int[0];
    private int[] depth = new int[0];
    private int[] levelStart = new int[2];
    private boolean[] levelDirty = new boolean[1]; // a local transform changed in the level
    private int levelCount;
    private boolean orderDirty;
    private int frame;

    private final LevelKernel kernel = new LevelKernel();
```

One by one:

- **`members`** maps an entity's slot index (from its handle) to its dense slot here: the sparse set
  of L03 §8. **`entities[s]`** is the full handle in slot `s`, used to reject stale handles (L03,
  exercise 4).
- **`px … sz`**: the local TRS, ten `float` columns (L03 §4's struct of arrays).
- **`parentEntity[s]`**: the parent's handle, the truth. **`parentSlot[s]`**: the parent's dense slot,
  a cache rebuilt with the order, because swap-removal moves slots and a stored slot number goes stale
  while a handle does not.
- **`childCount[s]`** lets `remove` skip a scan for children when there are none (§23).
- **`flags`**: one `byte` per entity, `DIRTY` and `STATIC`. Why bytes and not 64 flags packed into a
  `long`: §26.
- **`changedFrame[s]`**: the number of the update in which this world transform last changed. Comparing
  it with the current number answers "did my parent change *this* update?" without a boolean array that
  would have to be cleared every update.
- **`world`**: 12 floats per slot, the row-major 3×4 of chapter 02 and the layout of the GPU's
  instance records.
- **`order`, `depth`, `levelStart`, `levelCount`**: the traversal order of §13.3 and its levels;
  **`levelDirty[l]`**: some entity at depth `l` was set since the last update; **`orderDirty`**: the
  structure changed and the order must be rebuilt.
- **`frame`**: the update counter that `changedFrame` is compared with.
- **`kernel`**: the object the job system runs for big levels, created once (§26).

Per slot that is 4 (`entities`) + 40 (TRS) + 16 (`parentEntity`, `parentSlot`, `childCount`,
`changedFrame`) + 1 (`flags`) + 48 (`world`) = 109 bytes, plus 8 for `order` and `depth`: 117 bytes.
The sparse set `members` adds its own two `int` arrays (L03 §8.2): `dense`, 4 bytes per transform,
and `sparse`, 4 bytes per entity slot index up to the highest one used, so about 8 more bytes per
entity when most entities have a transform. In all about 125 bytes, some 12.5 MB for the 100,000
transforms of the benchmark (§31).

Construction and growth:

```java
    /** Creates a store and registers it with {@code pool}. */
    public TransformStore(EntityPool pool, int initialCapacity) {
        this.pool = pool;
        allocate(Math.max(16, initialCapacity));
        pool.register(this);
    }

    private void allocate(int cap) {
        capacity = cap;
        entities = grow(entities, cap);
        px = grow(px, cap);
        py = grow(py, cap);
        pz = grow(pz, cap);
        qx = grow(qx, cap);
        qy = grow(qy, cap);
        qz = grow(qz, cap);
        qw = grow(qw, cap);
        sx = grow(sx, cap);
        sy = grow(sy, cap);
        sz = grow(sz, cap);
        parentEntity = grow(parentEntity, cap);
        parentSlot = grow(parentSlot, cap);
        childCount = grow(childCount, cap);
        flags = flags == null ? new byte[cap] : Arrays.copyOf(flags, cap);
        changedFrame = grow(changedFrame, cap);
        world = world == null ? new float[cap * STRIDE] : Arrays.copyOf(world, cap * STRIDE);
    }

    private static float[] grow(float[] a, int cap) {
        return a == null ? new float[cap] : Arrays.copyOf(a, cap);
    }

    private static int[] grow(int[] a, int cap) {
        return a == null ? new int[cap] : Arrays.copyOf(a, cap);
    }
```

`allocate` serves both the first allocation (every array `null`) and growth (`Arrays.copyOf` keeps the
old contents and pads with zeros). Growth doubles the capacity (§23), so it happens rarely and only
while entities are being added, which is loading, not a steady frame. One consequence is in the
Javadoc of `world()`: the array is "Replaced when the store grows", so callers must not keep a
reference to it across adds. `World.extract` fetches `transforms.world()` at the start of every
call.

## 23. Step 9: adding, removing and reparenting

```java
    /** Gives {@code entity} an identity transform with no parent; returns its dense index. */
    public int add(int entity) {
        return add(entity, Handles.NULL);
    }

    /**
     * Gives {@code entity} an identity local transform under {@code parent} ({@link Handles#NULL}
     * for a root); returns its dense index.
     */
    public int add(int entity, int parent) {
        pool.check(entity);
        int index = Handles.index(entity);
        if (members.indexOf(index) >= 0) {
            throw new IllegalStateException(
                    "entity " + Handles.toString(entity) + " has a transform");
        }
        int ps = parent == Handles.NULL ? -1 : slotOf(parent);
        int s = members.add(index);
        if (s == capacity) {
            allocate(capacity * 2);
        }
        entities[s] = entity;
        px[s] = 0;
        py[s] = 0;
        pz[s] = 0;
        qx[s] = 0;
        qy[s] = 0;
        qz[s] = 0;
        qw[s] = 1;
        sx[s] = 1;
        sy[s] = 1;
        sz[s] = 1;
        parentEntity[s] = parent;
        childCount[s] = 0;
        if (ps >= 0) {
            childCount[ps]++;
        }
        flags[s] = DIRTY;
        changedFrame[s] = -1;
        AffineOps.identity(world, s * STRIDE);
        orderDirty = true;
        return s;
    }
```

- **Checks first**: the entity must be alive (`pool.check` throws for a stale handle), must not
  already have a transform, and the parent must have one (`slotOf` throws otherwise). The string
  building in the exceptions only runs when something is wrong, so it costs nothing normally.
- **`members.add(index)`** returns the new dense slot, which is the old size: slots are always packed.
  If it equals the capacity, every column doubles.
- **The identity TRS**: position 0, quaternion $(0, 0, 0, 1)$ (§15.1), scale 1.
- **`flags[s] = DIRTY`** so the first update computes the world matrix; **`changedFrame[s] = -1`**, a
  value no update number ever has (they start at 1), so "changed in this update" is false.
- **`orderDirty = true`**: the next update must rebuild the order (§25). Adding never moves existing
  data.

Removal is swap-removal (L03 §8), plus the hierarchy's own rule for orphans:

```java
    /**
     * Removes {@code entity}'s transform. Its children move to its parent, keeping their local
     * transforms (so they move in the world unless the removed transform was the identity).
     */
    @Override
    public void remove(int entity) {
        int index = Handles.index(entity);
        int s = members.indexOf(index);
        if (s < 0 || entities[s] != entity) {
            return;
        }
        int grandParent = parentEntity[s];
        int gs = grandParent == Handles.NULL ? -1 : members.indexOf(Handles.index(grandParent));
        int n = members.size();
        if (childCount[s] > 0) {
            for (int i = 0; i < n; i++) {
                if (parentEntity[i] == entity) {
                    parentEntity[i] = grandParent;
                    flags[i] |= DIRTY;
                }
            }
            if (gs >= 0) {
                childCount[gs] += childCount[s];
            }
        }
        if (gs >= 0) {
            childCount[gs]--;
        }
        int last = n - 1;
        members.remove(index);
        if (s != last) {
            entities[s] = entities[last];
            px[s] = px[last];
            py[s] = py[last];
            pz[s] = pz[last];
            qx[s] = qx[last];
            qy[s] = qy[last];
            qz[s] = qz[last];
            qw[s] = qw[last];
            sx[s] = sx[last];
            sy[s] = sy[last];
            sz[s] = sz[last];
            parentEntity[s] = parentEntity[last];
            childCount[s] = childCount[last];
            flags[s] = flags[last];
            changedFrame[s] = changedFrame[last];
            System.arraycopy(world, last * STRIDE, world, s * STRIDE, STRIDE);
        }
        orderDirty = true;
    }
```

- **Not a member, or a stale handle** (`entities[s] != entity`: the slot index was reused by a newer
  entity): nothing to do. The pool calls `remove` on every store for every destroyed entity, and most
  stores do not hold most entities, so this is the normal fast exit.
- **Orphans move up.** The children of the removed entity get its parent as their new parent and
  are marked dirty: they keep their local transforms, so they move in the world unless the removed
  transform was the identity. The alternative, destroying the whole subtree, would make a store
  decide the lifetime of entities, which is the pool's job; `World.destroyGroup` destroys subtrees
  explicitly when that is what the game wants.
- **The scan for children** visits every slot, $O(n)$, but only runs when `childCount[s] > 0`. Most
  removed entities are leaves, and structural changes are not per-frame work anyway.
- **`childCount` stays consistent**: the grandparent gains the orphans and loses the removed entity.
  All of this happens before the swap below, so if the grandparent happens to sit in the last slot, its
  corrected count is what gets copied.
- **The swap**: `members.remove` moves the sparse set's last member into row `s`, and the code moves
  every column of slot `last` into slot `s` to match, the world block with one `System.arraycopy` of
  12 floats. `parentSlot` is not copied; it is stale anyway until the order is rebuilt.

Reparenting:

```java
    /**
     * Moves {@code entity} under {@code parent} ({@link Handles#NULL} for a root), keeping its
     * local transform.
     *
     * @throws IllegalArgumentException if that would make a cycle
     */
    public void setParent(int entity, int parent) {
        int s = slotOf(entity);
        if (parent != Handles.NULL) {
            int p = slotOf(parent);
            for (int a = p, guard = 0; ; guard++) {
                if (entities[a] == entity) {
                    throw new IllegalArgumentException("reparenting would create a cycle");
                }
                int up = parentEntity[a];
                if (up == Handles.NULL || guard > members.size()) {
                    break;
                }
                a = members.indexOf(Handles.index(up));
            }
        }
        if (parentEntity[s] != parent) {
            int old = parentEntity[s];
            if (old != Handles.NULL) {
                childCount[members.indexOf(Handles.index(old))]--;
            }
            if (parent != Handles.NULL) {
                childCount[members.indexOf(Handles.index(parent))]++;
            }
            parentEntity[s] = parent;
            flags[s] |= DIRTY;
            orderDirty = true;
        }
    }
```

- **The cycle check** walks up from the new parent to its root. If it meets `entity` on the way, the
  new parent is `entity` itself or one of its descendants, and making it the parent would close a loop
  (a node would be its own ancestor, with no depth). `guard` bounds the walk at the number of
  transforms, so even a corrupted structure cannot loop forever.
- **The bookkeeping**: old parent loses a child, new parent gains one, the entity is dirty (its world
  transform changes even though its local one does not), and the order must be rebuilt.

The small accessors are what you would expect (`parent`, `has`, `indexOf`, `size`, `entityAt`). Two
of them matter for what follows, `indexOf` and the private lookup every setter uses (they sit in
different places in the file):

```java
    /** Returns the dense index of {@code entity} (its block in {@link #world()}), or -1. */
    public int indexOf(int entity) {
        int s = members.indexOf(Handles.index(entity));
        return s >= 0 && entities[s] == entity ? s : -1;
    }

    private int slotOf(int entity) {
        int s = members.indexOf(Handles.index(entity));
        if (s < 0 || entities[s] != entity) {
            throw new IllegalArgumentException(
                    "entity " + Handles.toString(entity) + " has no transform");
        }
        return s;
    }
```

`indexOf` is public because the renderer needs it: an entity's world matrix is the 12 floats at
`indexOf(e) * 12` in `world()`.

## 24. Step 10: local transforms and dirty marks

```java
    /** Sets the local position. */
    public void setPosition(int entity, float x, float y, float z) {
        int s = slotOf(entity);
        px[s] = x;
        py[s] = y;
        pz[s] = z;
        markDirty(s);
    }

    /** Sets the local rotation (a unit quaternion). */
    public void setRotation(int entity, float x, float y, float z, float w) {
        int s = slotOf(entity);
        qx[s] = x;
        qy[s] = y;
        qz[s] = z;
        qw[s] = w;
        markDirty(s);
    }

    /** Sets the local scale. */
    public void setScale(int entity, float x, float y, float z) {
        int s = slotOf(entity);
        sx[s] = x;
        sy[s] = y;
        sz[s] = z;
        markDirty(s);
    }

    private void markDirty(int s) {
        flags[s] |= DIRTY;
        if (!orderDirty) {
            levelDirty[depth[s]] = true;
        }
    }
```

- The setters take **separate `float`s**, never a `Vec3f` or `Quatf`, so gameplay code that already
  has numbers in arrays allocates nothing. `setRotation` documents "a unit quaternion" (§10.1); the
  store does not normalize, because the caller (`World.setYaw`, an animation, the editor) built a unit
  quaternion in the first place.
- **`markDirty`** sets the entity's bit (`|=`, L00 §6.2) and marks its **level** dirty, so that
  `update` knows the level must be scanned. If the order is dirty, `depth[s]` may be stale or missing
  (the entity was just added), and the rebuild marks every level dirty anyway, so the level mark is
  skipped.

The static mark and one of the getters (`isStatic` sits between them in the file):

```java
    /** Marks {@code entity} static (render may upload it once) or dynamic. */
    public void setStatic(int entity, boolean value) {
        int s = slotOf(entity);
        flags[s] = (byte) (value ? flags[s] | STATIC : flags[s] & ~STATIC);
    }

    /** Returns local position component {@code axis} (0 x, 1 y, 2 z). */
    public float position(int entity, int axis) {
        int s = slotOf(entity);
        return axis == 0 ? px[s] : axis == 1 ? py[s] : pz[s];
    }
```

- `flags[s] | STATIC` sets bit 1 and `flags[s] & ~STATIC` clears it (`~STATIC` is every bit but bit 1).
  Java widens `byte` operands to `int` before `|` and `&` (L00 §5.4), so the result is an `int` and
  the `(byte)` cast stores it back. (The compound `flags[s] |= DIRTY` above needs no cast: compound
  assignments narrow implicitly.)
- The getters take a component number instead of returning a `Vec3f`, again to avoid allocating;
  `rotation(entity, i)` and `scale(entity, axis)` follow the same pattern, and `isStatic`,
  `isStaticAt(s)` and `changedInLastUpdate(s)` read the flags and stamps.

## 25. Step 11: the traversal order

§13.3 explained the counting sort; here is the code that rebuilds the order after any structural
change:

```java
    /** Sorts the dense indices by depth (counting sort) and resolves parent slots. */
    private void rebuildOrder() {
        int n = members.size();
        if (order.length < n) {
            order = new int[capacity];
            depth = new int[capacity];
        }
        for (int s = 0; s < n; s++) {
            int p = parentEntity[s];
            if (p == Handles.NULL) {
                parentSlot[s] = -1;
            } else {
                int ps = members.indexOf(Handles.index(p));
                if (ps < 0 || entities[ps] != p) {
                    parentEntity[s] = Handles.NULL; // the parent was destroyed with no transform
                    parentSlot[s] = -1;
                } else {
                    parentSlot[s] = ps;
                }
            }
            depth[s] = -1;
        }
        int maxDepth = 0;
        for (int s = 0; s < n; s++) {
            maxDepth = Math.max(maxDepth, depthOf(s));
        }
        levelCount = n == 0 ? 0 : maxDepth + 1;
        if (levelStart.length < levelCount + 1) {
            levelStart = new int[levelCount + 1];
            levelDirty = new boolean[levelCount + 1];
        }
        Arrays.fill(levelDirty, true); // conservative after a structural change
        Arrays.fill(levelStart, 0, levelCount + 1, 0);
        for (int s = 0; s < n; s++) {
            levelStart[depth[s] + 1]++;
        }
        for (int l = 0; l < levelCount; l++) {
            levelStart[l + 1] += levelStart[l];
        }
        // Fill using levelStart as cursors, then shift back.
        for (int s = 0; s < n; s++) {
            order[levelStart[depth[s]]++] = s;
        }
        for (int l = levelCount; l > 0; l--) {
            levelStart[l] = levelStart[l - 1];
        }
        levelStart[0] = 0;
        orderDirty = false;
    }
```

Pass by pass:

1. **Arrays.** `order` and `depth` grow to the whole capacity when too small, so the next few adds
   do not allocate again. Otherwise the rebuild allocates nothing.
2. **Resolve parent slots.** Convert every parent handle into the parent's current slot, the only
   moment slot numbers are trustworthy. A parent handle that no longer has a transform (defensive:
   `remove` already reparents children) turns its child into a root. Every depth is reset to $-1$,
   "unknown".
3. **Depths**, by `depthOf` below, and the number of levels.
4. **Level arrays** grow if there are more levels than ever before. Every level is marked dirty:
   after a structural change, the cheap and safe choice is to scan everything once.
5. **Count.** `levelStart[d + 1]++` counts the slots of depth `d` one position to the right, so that
   the prefix sum in the next pass produces *starts* rather than ends.
6. **Prefix sums.** After `levelStart[l + 1] += levelStart[l]`, `levelStart[l]` is the number of slots
   in levels before `l`: where level `l` starts. `levelStart[levelCount]` is `n`, the end.
7. **Scatter**, using `levelStart` itself as the write cursors: `order[levelStart[d]++] = s` writes the
   slot at its level's next free place and advances the cursor. This saves a second array, at a price:
   afterwards each `levelStart[l]` points at the *end* of level `l`, which is the start of level
   `l + 1`.
8. **Shift back.** Copy each entry one place to the right and put 0 in front. The loop runs
   **backwards**: each step reads `levelStart[l - 1]` before that entry is itself overwritten. Run it
   forwards and the first step would overwrite `levelStart[1]` before it is copied into
   `levelStart[2]`.

**Traced** with §13.3's five slots (depths 0, 1, 0, 2, 1; `levelCount` = 3):

```
  after counting (pass 5):        levelStart = [0, 2, 2, 1]
  after prefix sums (pass 6):     levelStart = [0, 2, 4, 5]
  scatter (pass 7), slot by slot:
    s=0 (depth 0) -> order[0], levelStart[0] = 1
    s=1 (depth 1) -> order[2], levelStart[1] = 3
    s=2 (depth 0) -> order[1], levelStart[0] = 2
    s=3 (depth 2) -> order[4], levelStart[2] = 5
    s=4 (depth 1) -> order[3], levelStart[1] = 4
  after the scatter:              levelStart = [2, 4, 5, 5]    order = [0, 2, 1, 4, 3]
  after the shift (pass 8):       levelStart = [0, 2, 4, 5]
```

The depth of one slot, iteratively and with memory:

```java
    /** Depth of slot {@code s}, memoized; iterative so deep chains cannot overflow the stack. */
    private int depthOf(int s) {
        if (depth[s] >= 0) {
            return depth[s];
        }
        int d = 0;
        int a = s;
        while (parentSlot[a] >= 0 && depth[a] < 0) {
            a = parentSlot[a];
            d++;
            if (d > members.size()) {
                throw new IllegalStateException("cycle in the transform hierarchy");
            }
        }
        int base = depth[a] >= 0 ? depth[a] : 0;
        int total = base + d;
        // Fill in the chain.
        int k = total;
        for (int b = s; b != a; b = parentSlot[b]) {
            depth[b] = k--;
        }
        depth[a] = base;
        return total;
    }
```

- **Walk up** from `s` until reaching either a root (no parent) or an ancestor whose depth is already
  known, counting the steps in `d`. That ancestor is `a`; its depth is `base` (0 for a root whose depth
  was not yet stored).
- **Fill the chain**: walk up from `s` again and give every slot on the way its depth, counting down
  from `total`. Every slot's depth is computed once (**memoized**), so the whole rebuild is $O(n)$.
- **Iterative, not recursive.** "Depth = parent's depth + 1" is naturally recursive, but a chain of
  100,000 nested transforms would need 100,000 nested calls, more than a thread's stack holds
  (`StackOverflowError`). A loop has no such limit.
- **A cycle** (impossible through the public API, which `setParent` guards) would walk forever; the
  check throws after more steps than there are transforms.

Traced for slot 3 (D, parent B, grandparent A), after slots 0 and 1 have been computed: `parentSlot[3]`
is 1 and `depth[3]` unknown, so `a = 1`, `d = 1`; `depth[1]` is known (1), so the walk stops. `base = 1`,
`total = 2`, `depth[3] = 2`. One step instead of two.

## 26. Step 12: update and the level kernel

```java
    /**
     * Recomputes changed world transforms. Allocates nothing unless the structure changed (then the
     * traversal order may grow).
     *
     * @param jobs workers for large depth levels, or {@code null} to run on this thread
     * @return the number of world transforms that changed
     */
    public int update(JobSystem jobs) {
        if (orderDirty) {
            rebuildOrder();
        }
        frame++;
        kernel.frame = frame;
        int changed = 0;
        boolean previousLevelChanged = false;
        for (int l = 0; l < levelCount; l++) {
            if (!levelDirty[l] && !previousLevelChanged) {
                continue; // nothing set here and no parent moved: skip the scan
            }
            levelDirty[l] = false;
            int start = levelStart[l];
            int end = levelStart[l + 1];
            int n;
            kernel.levelBase = start;
            kernel.changed.set(0);
            if (jobs != null && end - start >= PARALLEL_LEVEL) {
                jobs.parallelFor(kernel, end - start, GRAIN);
                n = kernel.changed.get();
            } else {
                n = kernel.process(start, end);
            }
            changed += n;
            previousLevelChanged = n > 0;
        }
        return changed;
    }
```

- **`frame++`** starts a new update number; every world transform recomputed now will be stamped with
  it.
- **The level loop** walks the levels top-down, so parents are always final before children (§13.3).
- **Skipping a level.** A level can change only if something in it was set (`levelDirty`) or something
  in the level above changed (`previousLevelChanged`). If neither, nothing in it can change, and it is
  skipped **without even scanning it**. In a scene where nothing moved, `update` touches no entity at
  all.
- **One level, two ways.** Big levels go to the job system, small ones run here. Either way the
  kernel processes slots `order[start .. end)`. `parallelFor` counts from 0, so the kernel adds
  `levelBase`.
- **The return value** is the number of world transforms that changed, which tests check exactly
  (§30.4).

The whole flow, from a setter to a recomputed matrix:

```
  setPosition / setRotation / setScale
      -> DIRTY bit on the entity, levelDirty[its depth] (unless the order is dirty)

  update(jobs)
      structure changed?  -> rebuildOrder: depths, counting sort, every level dirty
      for each depth level, parents first:
          level dirty, or previous level changed?    no -> skip it without scanning
          2048 or more entities (and a job system)?  yes -> jobs.parallelFor(kernel, count, 512)
                                                     no  -> kernel.process on this thread
          per entity: dirty, or parent stamped with this update's number?
              compose TRS (times the parent's world), clear DIRTY, stamp changedFrame
```

The kernel is an inner class, so it can read the store's arrays:

```java
    /** Processes one depth level; ranges are relative to {@link #levelBase}. */
    private final class LevelKernel implements JobKernel {
        int levelBase;
        int frame;
        final AtomicInteger changed = new AtomicInteger();
        private final float[][] scratch = new float[JobSystem.MAX_EXTERNAL_THREADS + 257][];

        LevelKernel() {
            for (int i = 0; i < scratch.length; i++) {
                scratch[i] = new float[STRIDE];
            }
        }

        @Override
        public void run(int start, int end, int threadIndex) {
            int n = process(levelBase + start, levelBase + end, threadIndex);
            if (n != 0) {
                changed.addAndGet(n);
            }
        }

        int process(int start, int end) {
            return process(start, end, scratch.length - 1);
        }
```

- **`JobKernel`** is the job system's interface: `run(start, end, threadIndex)` for one chunk. A
  long-lived object instead of a lambda, so submitting it allocates nothing (DESIGN §4.5: kernels
  "don't capture per frame").
- **`scratch`**: one 12-float matrix per thread, for the child's local transform. Thread indices go
  up to 255 for workers (a `JobSystem` allows 256) plus `MAX_EXTERNAL_THREADS` = 8 for threads such as
  the game thread, so the highest is 263. The array has 8 + 257 = 265 entries, and the last one, 264,
  which the job system never hands out, is reserved for `process(start, end)`, the call `update`
  makes when there is no job system (and so no thread index to ask for).
- **`changed`** sums the counts of all chunks with **one atomic add per chunk** (and none for an empty
  one), not one per entity, so workers barely contend.

The loop itself:

```java
        private int process(int start, int end, int threadIndex) {
            float[] local = scratch[threadIndex];
            int[] ord = order;
            int[] parents = parentSlot;
            byte[] f = flags;
            int[] changedAt = changedFrame;
            float[] w = world;
            int fr = frame;
            int n = 0;
            for (int k = start; k < end; k++) {
                int s = ord[k];
                int p = parents[s];
                boolean dirty = (f[s] & DIRTY) != 0;
                if (!dirty && (p < 0 || changedAt[p] != fr)) {
                    continue;
                }
                if (p < 0) {
                    AffineOps.composeTRS(
                            px[s],
                            py[s],
                            pz[s],
                            qx[s],
                            qy[s],
                            qz[s],
                            qw[s],
                            sx[s],
                            sy[s],
                            sz[s],
                            w,
                            s * STRIDE);
                } else {
                    AffineOps.composeTRS(
                            px[s], py[s], pz[s], qx[s], qy[s], qz[s], qw[s], sx[s], sy[s], sz[s],
                            local, 0);
                    AffineOps.mul(w, p * STRIDE, local, 0, w, s * STRIDE);
                }
                if (dirty) {
                    f[s] &= ~DIRTY;
                }
                changedAt[s] = fr;
                n++;
            }
            return n;
        }
    }
```

- **Locals for the arrays.** Copying the fields into local variables before the loop lets the JIT
  keep the array references in registers; inside an inner class every field read would otherwise go
  through the outer object. The indices themselves come from data (`s = ord[k]`, `p = parents[s]`,
  `s * STRIDE`), so the JIT cannot prove them in range ahead of time, and the bounds checks on those
  accesses stay.
- **The skip test** is §13.2's rule: recompute if dirty, or if the parent exists and was stamped with
  this update's number (it changed in an earlier level of this same update).
- **A root** has no parent, so its TRS goes straight into its own world block.
- **A child** composes its TRS into this thread's scratch matrix, then multiplies: parent's world ×
  local into its own world block, $W(e) = W(\operatorname{parent}(e))\,L(e)$ (§13.1). `AffineOps.mul`
  forbids its output to overlap an input; here the output is the child's block and the inputs are the
  parent's block and the scratch matrix, three different places.
- **Clearing the flag**: `~DIRTY` is the `int` with every bit set but bit 0, and `&=` clears bit 0
  (the compound assignment narrows back to `byte`). It writes only when there is something to clear;
  children recomputed only because their parent moved are left untouched.
- **The stamp** `changedAt[s] = fr` is what the next level's skip test reads.

**Why this is safe on several threads at once.** Inside one level:

- every entity's parent is in an earlier level, already final: `parallelFor` returns only when every
  chunk of the previous level is done, and that return is a handover in L00 §9.5's sense (the job
  system publishes completion with an atomic counter, a full memory fence, DESIGN §5), so the next
  level's workers see every value the previous level wrote. The plain fields `levelBase` and `frame`
  are published the same way: they are written before `parallelFor` submits the job, and the submission
  is itself such a handover;
- each entity writes only its own world block, its own `changedFrame` entry and its own flag byte;
- each thread composes into its own scratch matrix.

The flag bytes are the subtle part. Java guarantees that each array element is a separate variable:
"two threads that update adjacent elements of a byte array separately must not interfere or interact
and do not need synchronization to ensure sequential consistency" (Java Language Specification §17.6,
word tearing). Bits packed into a
`long` have no such guarantee. Clearing a bit is a read-modify-write of the whole `long`: two workers
can both read it, each clear its own bit, and both write it back, and the second write brings back the
bit the first one cleared. Separate bytes make that impossible without any atomic operation (D28,
DESIGN §13.1).

In `TransformStoreTest`'s 12,000-entity forest (§30.4), the two biggest levels (3,034 and 2,219
entities) take the parallel path and the other thirteen run inline. The benchmark's 100,000-transform
forest (§31) has three levels of about 40,000, 40,000 and 20,000 entities, all of which go to the
workers when it runs with the job system.

## 27. Step 13: flattening a scene at spawn time

A cooked `.jscene` is a node tree. A character exported from a modelling tool can have 50 nodes, of
which only a handful carry meshes; the rest are bones and empty grouping nodes. If the game never
animates those nodes (props, level pieces, characters without an animation system), keeping the whole
tree in the `TransformStore` would cost 50 transforms per character every time it moves. So
`World.instantiate(..., flatten = true)` keeps only the mesh nodes, makes them direct children of the
instance's root entity, and gives each one its transform **composed down the node hierarchy** once,
at spawn time (DESIGN §13.2: characters "keep ~10 transforms instead of a 50-bone hierarchy").
Animated characters are flattened the same way (`instantiateAnimated`, D81), with their joints living
in the animation system instead of the transform store.

The composition must produce TRS again, because the transform store stores TRS: §13.4's rule,
translation $\mathbf{t}_p + R_p(S_p\mathbf{t}_c)$, rotation $q_pq_c$, scale $\mathbf{s}_p \odot
\mathbf{s}_c$, valid without shear. In `World`:

```java
    /**
     * Writes node {@code i}'s TRS composed with its (already composed) parent's into {@code out}.
     */
    private static void compose(SceneData scene, int i, int p, float[] out) {
        int o = i * 10;
        float tx = scene.trs(i, 0), ty = scene.trs(i, 1), tz = scene.trs(i, 2);
        float qx = scene.trs(i, 3),
                qy = scene.trs(i, 4),
                qz = scene.trs(i, 5),
                qw = scene.trs(i, 6);
        float sx = scene.trs(i, 7), sy = scene.trs(i, 8), sz = scene.trs(i, 9);
        if (p < 0) {
            out[o] = tx;
            out[o + 1] = ty;
            out[o + 2] = tz;
            out[o + 3] = qx;
            out[o + 4] = qy;
            out[o + 5] = qz;
            out[o + 6] = qw;
            out[o + 7] = sx;
            out[o + 8] = sy;
            out[o + 9] = sz;
            return;
        }
        int q = p * 10;
        float px = out[q + 3], py = out[q + 4], pz = out[q + 5], pw = out[q + 6];
        // Parent scale, then parent rotation, then parent translation applied to the child's.
        float vx = tx * out[q + 7];
        float vy = ty * out[q + 8];
        float vz = tz * out[q + 9];
        float cx = 2 * (py * vz - pz * vy);
        float cy = 2 * (pz * vx - px * vz);
        float cz = 2 * (px * vy - py * vx);
        out[o] = out[q] + vx + pw * cx + (py * cz - pz * cy);
        out[o + 1] = out[q + 1] + vy + pw * cy + (pz * cx - px * cz);
        out[o + 2] = out[q + 2] + vz + pw * cz + (px * cy - py * cx);
        float rx = pw * qx + px * qw + py * qz - pz * qy;
        float ry = pw * qy - px * qz + py * qw + pz * qx;
        float rz = pw * qz + px * qy - py * qx + pz * qw;
        float rw = pw * qw - px * qx - py * qy - pz * qz;
        float len = (float) Math.sqrt(rx * rx + ry * ry + rz * rz + rw * rw);
        out[o + 3] = rx / len;
        out[o + 4] = ry / len;
        out[o + 5] = rz / len;
        out[o + 6] = rw / len;
        out[o + 7] = out[q + 7] * sx;
        out[o + 8] = out[q + 8] * sy;
        out[o + 9] = out[q + 9] * sz;
    }
```

Read it with care, because the names are reused:

- **The layout**: `out` holds ten floats per node, `[tx ty tz | qx qy qz qw | sx sy sz]`, at `i * 10`.
  `q = p * 10` is the start of the **parent's** composed entry, so `out[q]`, `out[q + 1]`, `out[q + 2]`
  are the parent's translation, `out[q + 3 .. q + 6]` its rotation and `out[q + 7 .. q + 9]` its scale.
- **Inside this method `px, py, pz, pw` are the parent's quaternion**, not a position, and `qx … qw`
  (read from the scene) are the child's local rotation.
- **A node without a parent in the subtree** (`p < 0`) is copied unchanged.
- **Translation.** `v` is $S_p\mathbf{t}_c$, the child's offset scaled by the parent's scale. The `c`
  vector is $\mathbf{t} = 2\,(\mathbf{u}_p \times \mathbf{v})$ and the three `out[o + k]` lines are §9's
  Java form, $\mathbf{v} + w\,\mathbf{t} + \mathbf{u} \times \mathbf{t}$, plus the parent's translation:
  $\mathbf{t}_p + R_p(S_p\mathbf{t}_c)$.
- **Rotation.** The `r` lines are the Hamilton product $q_pq_c$ of §8.2 (compare with `Quatf.mul`:
  parent as `this`, child as `o`, so the child's rotation acts first, inside the parent's), then
  **renormalized**: in a deep tree every product rounds a little (§8.4), and the store's contract is a
  unit quaternion.
- **Scale.** Component by component.

The caller walks the scene's nodes in order. A `.jscene` lists parents before children (D39: "nodes
(parents first)"), so when node `i` is composed its parent has been already:

```java
        for (int i = 0; i < n; i++) {
            int p = scene.parent(i); // always a smaller index: parents come first
            in[i] = rootNode < 0 || i == rootNode || (p >= 0 && in[p]);
            if (!in[i]) {
                continue;
            }
            boolean top = rootNode < 0 ? p < 0 : i == rootNode; // a child of the new root
            int m = scene.mesh(i);
            int f = -1;
            if (m >= 0) {
                f =
                        nodeFlags == null
                                ? renderFlags
                                : nodeFlags.flags(scene.nodeName(i), renderFlags);
            }
            int e;
            if (flatten) {
                compose(scene, i, top ? -1 : p, composed);
                if (f < 0) {
                    continue;
                }
                e = pool.create();
                transforms.add(e, root);
                int o = i * 10;
                transforms.setPosition(e, composed[o], composed[o + 1], composed[o + 2]);
                transforms.setRotation(
                        e, composed[o + 3], composed[o + 4], composed[o + 5], composed[o + 6]);
                transforms.setScale(e, composed[o + 7], composed[o + 8], composed[o + 9]);
            } else {
```

(the loop of `instantiateNode`, up to the non-flattened branch, which adds one entity per node under
its parent's entity instead). Every node in the subtree is composed, mesh or not, because its children
need it; only nodes with a mesh (`f >= 0`) become entities, all under `root`, whose own transform is
the placement in the level. `composed` is a `new float[n * 10]`: spawning is a cold path ("Cold path"
says the Javadoc of `instantiateNode`), so allocating there is allowed.

The worked example of §13.4 (parent at $(0, 1, 0)$, yaw 90°, scale 2; child at $(1, 0, 0)$) gives,
through these lines, $\mathbf{v} = (2, 0, 0)$ and the child at $(0, 1, -2)$ with rotation
$(0, 0.7071, 0, 0.7071)$ and scale 2. Exercise 17 compares the flattened TRS with the full hierarchy
and shows where they part.

## 28. Step 14: interpolating between ticks

Five pieces cooperate: the stepper that counts ticks, the world that remembers the previous tick,
the packet that carries both transforms, the draw builder that blends them into GPU memory, and the
vertex shader that reads the result:

```
  game thread, every tick:   beginTick (world -> prev), gameplay sets local transforms,
                             updateTransforms (TransformStore.update)
  game thread, every frame:  extract: prev and world into the render packet, alpha from FixedStepper
  main thread and workers:   DrawBuilder.writeRecords: lerp(prev, curr, alpha) into 64-byte records
  GPU, vertex shader:        jeddyTransformPoint with the interpolated rows
```

**The stepper.** `FixedStepper` (in `core.time`):

```java
public final class FixedStepper {
    private final long stepNanos;
    private final int maxStepsPerFrame;
    private long last = Long.MIN_VALUE;
    private long accumulator;
    private long ticks;
    private double timeScale = 1.0;
    private boolean paused;

    public FixedStepper(double ticksPerSecond, int maxStepsPerFrame) {
        if (!(ticksPerSecond > 0) || maxStepsPerFrame < 1) {
            throw new IllegalArgumentException("invalid rate or step cap");
        }
        this.stepNanos = Math.round(1e9 / ticksPerSecond);
        this.maxStepsPerFrame = maxStepsPerFrame;
    }
```

(Some Javadoc comments and the small accessors are left out of this block and the next.) Time is kept in
`long` nanoseconds, from `System.nanoTime()`: integers, so the accumulator never collects rounding
error the way a `float` of seconds would (L00 §7.3). One tick at 30 Hz is `Math.round(1e9 / 30)` =
33,333,333 ns. `!(ticksPerSecond > 0)` rejects zero, negatives and NaN in one test (NaN fails every
comparison). `last` starts at `Long.MIN_VALUE`, meaning "no frame yet".

```java
    public int advance(long nowNanos) {
        if (last == Long.MIN_VALUE) {
            last = nowNanos;
            return 0;
        }
        long elapsed = Math.max(0, nowNanos - last);
        last = nowNanos;
        if (paused) {
            return 0;
        }
        long scaled = timeScale == 1.0 ? elapsed : (long) (elapsed * timeScale);
        accumulator = Math.min(accumulator + scaled, stepNanos * (long) maxStepsPerFrame);
        int steps = (int) (accumulator / stepNanos);
        accumulator -= steps * stepNanos;
        ticks += steps;
        return steps;
    }

    /** Returns the fraction of the next tick already elapsed, in {@code [0, 1)}. */
    public float alpha() {
        return (float) accumulator / stepNanos;
    }
```

- **The first call** only records the time; there is no elapsed time to account for yet.
- **`Math.max(0, …)`** guards against a clock that steps backwards.
- **Paused**, time passes but is not added: the world freezes and `alpha` stays where it was.
- **`timeScale`** (slow motion at 0.5) scales real time before it enters the accumulator; the `== 1.0`
  test skips the `double` multiply in the normal case.
- **The cap** (§14.2): the accumulator never holds more than `maxStepsPerFrame` ticks.
- **Whole ticks out, remainder kept**: integer division gives the number of ticks, and the remainder
  stays for the next frame. This is §14.2's table, line by line.
- **`alpha()`**: the cast binds to `accumulator` first, so the division is `float` by `long`, done in
  `float`: the remainder as a fraction of a tick.

The game thread's loop (`JeddyApp`) uses it once per frame:

```java
                int ticks = ctx.stepper.advance(now);
                for (int i = 0; i < ticks; i++) {
                    game.fixedUpdate(ctx, dt);
                }
                game.update(ctx, ctx.frameNanos * 1e-9f, ctx.stepper.alpha());
                game.extract(ctx, frames.writeIndex());
```

**The previous tick.** `World` keeps one extra 3×4 per moving renderable, `prev` (indexed by the
renderable's dense index `i`, not by the transform slot), and refreshes it at the start of every tick,
before anything moves:

```java
    /**
     * Remembers every moving renderable's world transform as the previous tick's (call before
     * moving things). Static renderables never move, so they are skipped.
     */
    public void beginTick() {
        float[] world = transforms.world();
        for (int i = 0, n = renderables.size(); i < n; i++) {
            if ((flags[i] & RenderPacket.FLAG_STATIC) != 0) {
                continue;
            }
            int s = transforms.indexOf(entityOf[i]);
            System.arraycopy(world, s * 12, prev, i * 12, 12);
            hasPrev[i] = true;
        }
    }
```

A tick then runs gameplay, which sets local transforms, and ends with `world.updateTransforms(jobs)`,
which is `transforms.update(jobs)`. The demo's `fixedUpdate` begins with `world.beginTick()` and ends
with `world.updateTransforms(ctx.jobs())`. If a frame runs no tick, `prev` and the current transform
both stay, and only `alpha` moves; if it runs two, `prev` holds the state before the second.

**The packet.** Once per frame, `World.extract` writes every renderable into the render packet with
**both** matrices (excerpt from its loop):

```java
                if (a >= 0) {
                    k = extractAnimated(packet, i, s, a, mesh, world);
                } else if (wantStatic) {
                    k =
                            packet.add(
                                    world,
                                    s * 12,
                                    world,
                                    s * 12,
                                    mesh.handle(),
                                    material[i],
                                    flags[i],
                                    entityOf[i]);
                } else {
                    if (!hasPrev[i]) {
                        System.arraycopy(world, s * 12, prev, i * 12, 12);
                        hasPrev[i] = true;
                    }
                    k =
                            packet.add(
                                    prev,
                                    i * 12,
                                    world,
                                    s * 12,
                                    mesh.handle(),
                                    material[i],
                                    flags[i],
                                    entityOf[i]);
                }
```

- **Static** renderables pass their current matrix twice: they never move, so interpolating them is
  a no-op, and `beginTick` skipped them.
- **A renderable added since the last tick** has no previous matrix yet (`hasPrev` false); it gets its
  current one as the previous, so it appears in place instead of sliding in from wherever `prev` held
  garbage.
- **Animated** renderables go through `extractAnimated`, which composes attachments (a sword in a
  hand) as root × joint × offset twice, once with the previous root matrix and once with the current
  (§14.3), and passes both.

`RenderPacket.add` copies the two matrices into the packet's own arrays, and the game sets the factor:
`p.alpha = ctx.simulation().alpha();` in the demo's `extract`. The packet is triple-buffered and handed
to the main thread (DESIGN §5, chapter 01):

```java
        int i = count++;
        System.arraycopy(prev, prevOffset, prevWorld, i * 12, 12);
        System.arraycopy(curr, currOffset, currWorld, i * 12, 12);
```

**The blend.** The packet can produce one interpolated matrix:

```java
    /**
     * Writes instance {@code i}'s transform interpolated at {@link #alpha} to {@code out[o..o+12)}.
     */
    public void interpolatedWorld(int i, float[] out, int o) {
        AffineOps.lerp(prevWorld, i * 12, currWorld, i * 12, alpha, out, o);
    }
```

with `AffineOps.lerp`, chapter 02's lerp applied to all 12 numbers:

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

But the hot path does not go through an intermediate array. On the main thread, `DrawBuilder` writes
one 64-byte **instance record** per draw item straight into GPU-visible memory from the frame ring
(lesson [1](lessons/01-buffers-in-flight.md) builds the ring, and its §14.1 these writes), and it
interpolates while it writes. For 2,048 or more items it splits this loop between job workers in
chunks of 512 (D30: "Records are interpolated (prev/curr tick) while they are written on the
workers"):

```java
    /** Instance records for sorted items {@code [start, end)}. */
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

- **Records follow the sorted draw order** (`order[k]`), so each indirect draw command's instances are
  contiguous (D30); `instance` is the packet index of the item's instance, and `w` its 12-float block.
- **`o`** is the record's byte offset in the mapped segment: `instanceBase`, which `ring.allocate`
  returned for this frame's `n` records (aligned to 256 bytes), plus `k × 64`, computed in `long`
  (L00 §4.3).
- **The inner loop is `AffineOps.lerp`**, writing each blended float at byte `o + 4j` with a
  `MemorySegment` setter (L00 §8.3). `F32` is `JAVA_FLOAT_UNALIGNED` in the machine's byte order: the
  GPU reads the same little-endian floats.
- **Bytes 48–63** carry the material, the flags, the entity handle and the camera fade.
- **Write only.** The mapping is write-combined memory, and the code never reads it back (L01 §3.3).

**On the GPU.** The vertex shader reads the record from a storage buffer (`engine/instance.glsl`):

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
```

- **The memory.** A shader storage buffer at binding point 0, laid out with the `std430` rules, in
  which a `vec4` is 16 bytes with 16-byte alignment: four `vec4`-sized members make exactly the
  64-byte record, with no padding. `readonly` tells the compiler the shader never writes it.
- **Which record.** An indirect draw names a first instance (`gl_BaseInstance`), and the GPU numbers
  the instances of the draw from 0 (`gl_InstanceID`): their sum is the record's index, the `k` of
  `writeRecords`.
- **The transform.** Row-major storage pays off here: each output coordinate is one row dotted with
  $(x, y, z, 1)$, and the 1 brings in the translation in the fourth column (chapter 02, homogeneous
  coordinates). `jeddyTransformDirection` uses `.xyz` and drops it.
- **Synchronization** is the frame ring's (L01 §6): the main thread waits on the region's fence
  before the workers write, the workers' completion is published by the job system's atomic counter
  before the draw is issued (DESIGN §5), and the GPU reads the region only after the draw commands
  that follow.

# Part C — Using it and proving it

## 29. Where the engine turns things

### 29.1 Characters on the ground

Gameplay code thinks in yaw angles (§5.3) and the store wants quaternions, so `World` converts at the
door:

```java
    /**
     * Creates an entity with a transform at {@code (x, y, z)} turned by {@code yaw} about +Y, under
     * {@code parent} ({@link Handles#NULL} for a root).
     */
    public int create(int parent, float x, float y, float z, float yaw) {
        int e = pool.create();
        transforms.add(e, parent);
        transforms.setPosition(e, x, y, z);
        setYaw(e, yaw);
        return e;
    }

    /** Sets an entity's rotation to {@code yaw} radians about +Y. */
    public void setYaw(int entity, float yaw) {
        float h = yaw * 0.5f;
        transforms.setRotation(entity, 0, (float) Math.sin(h), 0, (float) Math.cos(h));
    }
```

`setYaw` writes $(0, \sin\frac{\psi}{2}, 0, \cos\frac{\psi}{2})$, `Quatf.fromYaw`'s four numbers
(§15.2), without creating a `Quatf`. `World.instantiate` builds a scene root's rotation the same way,
into a scratch array:

```java
        float h = yaw * 0.5f;
        float[] trs = rootTrs;
        trs[0] = x;
        trs[1] = y;
        trs[2] = z;
        trs[3] = 0;
        trs[4] = (float) Math.sin(h);
        trs[5] = 0;
        trs[6] = (float) Math.cos(h);
```

Characters that walk are turned by [`AgentSync`](../../modules/app/src/main/java/dev/jeddy/app/world/AgentSync.java),
which copies each crowd agent's position into its entity after the crowd update and turns the entity
towards where it is going (models face $+Z$). "Face that enemy" is §5.3's formula:

```java
    /** Like {@link #face}, toward the point {@code (x, z)}. */
    public void faceToward(int agent, float x, float z) {
        float dx = x - crowd.x(agent);
        float dz = z - crowd.z(agent);
        if (dx * dx + dz * dz > 1e-6f) {
            face(agent, (float) Math.atan2(dx, dz));
        }
    }
```

`atan2(dx, dz)`, $x$ first, because yaw is measured from $+Z$ towards $+X$. The guard skips a target
closer than 1 mm (squared distance $10^{-6}$), whose direction is meaningless. The turning itself
happens once per tick, at a limited rate, in `apply`:

```java
    /** Copies positions and facings for one tick of {@code dt} seconds. */
    public void apply(float dt) {
        float maxTurn = turnRate * dt;
        for (int a = 0, n = Math.min(entity.length, crowd.capacity()); a < n; a++) {
            int e = entity[a];
            if (e == 0 || !crowd.isActive(a)) {
                continue;
            }
            world.transforms().setPosition(e, crowd.x(a), crowd.y(a), crowd.z(a));
            float vx = crowd.velocityX(a);
            float vz = crowd.velocityZ(a);
            float target;
            if (vx * vx + vz * vz > 0.04f) {
                target = (float) Math.atan2(vx, vz);
                wants[a] = false;
            } else if (wants[a]) {
                target = wantYaw[a];
            } else {
                continue;
            }
            float d = target - yaw[a];
            d -= (float) (Math.PI * 2) * (float) Math.floor((d + Math.PI) / (Math.PI * 2));
            if (Math.abs(d) <= maxTurn) {
                wants[a] = false; // facing reached: stop touching the transform
            }
            yaw[a] += Math.clamp(d, -maxTurn, maxTurn);
            world.setYaw(e, yaw[a]);
        }
    }
```

- **`maxTurn`** is the turn rate times the tick: $10 \times \frac{1}{30} = \frac13$ rad (19.1°) by
  default, the step §11.3 and §14.3 used.
- **The target**: moving faster than 0.2 m/s ($0.2^2 = 0.04$) means "face where you are going",
  `atan2(vx, vz)`; standing still with a `face` request means "face that"; otherwise the entity's
  rotation is not touched at all, so standing characters cost no transform update.
- **The short way** is §2.6's wrap; **the rate limit** is `Math.clamp` (Java 21) to $\pm$`maxTurn`.
- **`yaw[a]` itself is never wrapped.** It can drift past $\pm\pi$ after a few turns in the same
  direction; that is harmless, because only the wrapped *difference* is used and $\sin$ and $\cos$
  repeat every $2\pi$.

### 29.2 The camera

`IsoCamera.update` (chapter [04](04-cameras-and-projection.md) builds the rest) places the eye behind
the target along the forward vector of §5.3:

```java
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

The eye is $\text{target} - \text{distance} \cdot \text{forward}$: every sign of the forward vector
flipped. Then a look-at matrix, not a product of axis rotations: yaw and pitch are two independent
numbers that never gimbal-lock, because the camera never rolls and its pitch stays well away from 90°
in play.

### 29.3 Animation: hemispheres and blends

Animation clips (chapter [09](09-animation-and-skinning.md)) are baked at load time into frames of
joint TRS at a fixed rate (D79), and playing them is "two frames and a lerp". §11.4's sign problem is
solved once, at bake time, in [`Clip.bake`](../../modules/animation/src/main/java/dev/jeddy/animation/Clip.java):

```java
        for (int j = 0; j < joints; j++) {
            for (int i = 1; i < frameCount; i++) {
                int q = i * frameFloats + j * Skeleton.TRS + 3;
                int prev = q - frameFloats;
                // Keep consecutive frames in one hemisphere, so frame lerps take the short way.
                if (f[q] * f[prev]
                                + f[q + 1] * f[prev + 1]
                                + f[q + 2] * f[prev + 2]
                                + f[q + 3] * f[prev + 3]
                        < 0) {
                    for (int k = 0; k < 4; k++) {
                        f[q + k] = -f[q + k];
                    }
                }
            }
        }
```

Each frame stores 10 floats per joint (`Skeleton.TRS`), the rotation at offset 3. For every joint,
each frame's quaternion is compared with the previous frame's (already fixed) one, and negated if their
dot product is negative. After this pass, every pair of neighbouring frames is in the same
**hemisphere** of the 4D sphere, and a plain lerp between them takes the short way. The work is done
once per clip, not once per sample.

Blending several clips (a walk and a run, a crossfade) happens per frame in
[`Animations`](../../modules/animation/src/main/java/dev/jeddy/animation/Animations.java), as a
weighted sum of up to four samples per joint. Different clips were baked independently, so their
hemispheres may disagree, and each contribution is flipped against the running sum:

```java
                float qx = f[xm + 3] + (f[ym + 3] - f[xm + 3]) * t;
                float qy = f[xm + 4] + (f[ym + 4] - f[xm + 4]) * t;
                float qz = f[xm + 5] + (f[ym + 5] - f[xm + 5]) * t;
                float qw = f[xm + 6] + (f[ym + 6] - f[xm + 6]) * t;
                float d = p[o + 3] * qx + p[o + 4] * qy + p[o + 5] * qz + p[o + 6] * qw;
                float wq = d < 0 ? -w : w;
                p[o + 3] += qx * wq;
                p[o + 4] += qy * wq;
                p[o + 5] += qz * wq;
                p[o + 6] += qw * wq;
```

then the sum is normalized, which makes the whole blend an nlerp with several inputs:

```java
            float len2 =
                    p[o + 3] * p[o + 3]
                            + p[o + 4] * p[o + 4]
                            + p[o + 5] * p[o + 5]
                            + p[o + 6] * p[o + 6];
            if (len2 > 1e-24f) {
                float inv = (float) (1 / Math.sqrt(len2));
                p[o + 3] *= inv;
                p[o + 4] *= inv;
                p[o + 5] *= inv;
                p[o + 6] *= inv;
            } else {
                System.arraycopy(bind, o + 3, p, o + 3, 4);
            }
```

The first four lines lerp between the clip's two frames (`xm`, `ym`) at the clip's fraction `t`; the
sign test is nlerp's (§15.6), with `wq` the weight of this clip.

Can the sum be zero, so that there is nothing to normalize? Not with real weights. `add` drops every
contribution whose weight is not positive. The first contribution is a lerp between two neighbouring
frames of one clip, which `bake` keeps in one hemisphere, so (as for `QuatOps.nlerp` in §16) it has
length at least $1/\sqrt 2$, times its weight $w_0$. Each later term $w\,\mathbf{q}$ is sign-flipped so
that its dot product with the running sum $\mathbf{S}$ is not negative, so

$$\lVert \mathbf{S} + w\,\mathbf{q}\rVert^2 = \lVert\mathbf{S}\rVert^2 + 2w\,\mathbf{S}\cdot\mathbf{q} + w^2\lVert\mathbf{q}\rVert^2 \ge \lVert\mathbf{S}\rVert^2 + w^2\lVert\mathbf{q}\rVert^2$$

Adding a term never shortens the sum. So the squared length is at least $\frac12 w_0^2$, and falling to
the `1e-24f` limit needs $w_0 \lesssim 1.4 \times 10^{-12}$. The bind-pose fallback is defensive, the
animation counterpart of `Quatf.normalize`'s identity.

### 29.4 Mirrored instances and normal maps

`pbr.vert` turns the decoded frame (§21) into world space for the fragment shader:

```glsl
void main() {
    JeddyInstance inst = jeddyInstance();
    vec3 n;
    vec3 t;
    float handedness;
    jeddyTangentFrame(n, t, handedness);
    vec3 position = jeddyPosition;
#ifdef SKINNED
    JeddySkin skin = jeddySkin(inst.data.y);
    position = jeddySkinPoint(skin, position);
    n = jeddySkinDirection(skin, n);
    t = jeddySkinDirection(skin, t);
#endif
    vWorld = jeddyTransformPoint(inst, position);
    float mirror = jeddyMirrored(inst) ? -1.0 : 1.0;
    vNormal = jeddyTransformNormal(inst, n);
    vTangent = vec4(normalize(jeddyTransformDirection(inst, t)), handedness * mirror);
```

(the rest writes the UVs, material and flags). The normal is transformed as a normal (chapter 02:
by the inverse transpose, here the cofactor matrix), the tangent as an ordinary direction, and
`vTangent.w` carries the handedness, **multiplied by `mirror`**. Why? If the *instance* transform
mirrors (negative determinant, chapter 02), the handedness flips too. Check with $M =
\operatorname{diag}(-1, 1, 1)$, $\mathbf{t} = (1, 0, 0)$, $\mathbf{n} = (0, 0, 1)$ and $\mathbf{b} =
h\,(\mathbf{n} \times \mathbf{t}) = h\,(0, 1, 0)$:

- the tangent becomes $\mathbf{t}' = M\mathbf{t} = (-1, 0, 0)$;
- the true bitangent becomes $\mathbf{b}' = M\mathbf{b} = h\,(0, 1, 0)$;
- the normal stays $\mathbf{n}' = (0, 0, 1)$: the cofactor matrix of $M$ sends it to $(0, 0, -1)$,
  and `jeddyTransformNormal` flips it back for a negative determinant to keep it pointing outwards.

But $\mathbf{n}' \times \mathbf{t}' = (0, 0, 1) \times (-1, 0, 0) = (0, -1, 0)$, so $\mathbf{b}' =
-h\,(\mathbf{n}' \times \mathbf{t}')$: the stored sign must be multiplied by the sign of $\det M$,
which is `mirror`. The two helpers, from `instance.glsl`:

```glsl
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

The determinant is the triple product of the three columns (chapter 02). The rasterizer
interpolates `vNormal` and `vTangent` across the triangle, and `pbr.frag` rebuilds the frame per pixel
before using the normal map:

```glsl
    vec3 t = normalize(vTangent.xyz - n * dot(n, vTangent.xyz));
    vec3 b = cross(n, t) * vTangent.w;
    n = normalize(t * tn.x + b * tn.y + n * tn.z);
```

Interpolated unit vectors come out a little short and no longer exactly perpendicular (the chord
effect of §14.3 again), so the tangent is made perpendicular to the normal once more (Gram–Schmidt)
and normalized; the bitangent is the cross product times the handedness, $h\,(\mathbf{n} \times
\mathbf{t})$ of §12.2; and the last line is the change of basis of §12.1, with `tn` the tilt read from
the normal map.

One more convention, **D33**: when the cooker has to generate tangents itself (with MikkTSpace, the
standard algorithm), it flips the handedness, because MikkTSpace's bitangent follows $+v$ while glTF's
tangent `w` (and a normal map's $+Y$) follows $-v$: glTF's texture origin is the image's top-left
corner. From the cooker's `MeshProcessor.mikk`:

```java
            // MikkTSpace's bitangent follows +v; glTF's (the normal map's +Y) follows -v because
            // its UV origin is the image's top-left corner, so flip the sign (DESIGN D33).
            for (int i = 3; i < t.length; i += 4) {
                t[i] = -t[i];
            }
```

Every fourth float is a tangent's `w`, the handedness that `encodeQTangent` then stores in the
quaternion's sign.

### 29.5 The editor's angles

The level editor shows rotations as yaw, pitch and roll in degrees and converts with
`GizmoMath.toEuler` and `fromEuler` (chapter [14](14-editor.md)). Going from a quaternion back to
angles reads entries of §10.1's matrix: for $R = R_y R_x R_z$ (§6), row 1, column 2 of the product
is $-\sin(\text{pitch})$, and in the quaternion matrix it is $2(yz - wx)$, so $\sin(\text{pitch}) =
2(wx - yz)$, which is the first line of `toEuler`. Exercise 7 derives it, and the gimbal-lock branch
that gives the whole turn to yaw when $\lvert\sin(\text{pitch})\rvert > 0.9999$.

## 30. The tests, walked through

All the tests below were run while writing this chapter (2026-10-13) and pass: `MatrixAndQuatTest`
(14 tests), `TransformStoreTest` (5), `FormatsTest` (6), `GltfImporterTest` (6) and `RenderPacketTest`
(4).

### 30.1 MatrixAndQuatTest

[`MatrixAndQuatTest`](../../modules/math/src/test/java/dev/jeddy/math/MatrixAndQuatTest.java) builds
random rotations by normalizing four random numbers in $[-1, 1]$ (seed 1234, so failures repeat):

```java
    private Quatf randomQuat() {
        return new Quatf(
                        random.nextFloat() * 2 - 1,
                        random.nextFloat() * 2 - 1,
                        random.nextFloat() * 2 - 1,
                        random.nextFloat() * 2 - 1)
                .normalize();
    }
```

**`quaternionsAgreeWithMatrices`**, 200 times:

```java
            Quatf q = randomQuat();
            Vec3f v = randomVec(3);
            Vec3f byQuat = q.rotate(v);
            Vec3f byMat = Mat4f.rotation(q).transformDirection(v);
            assertTrue(byQuat.approxEquals(byMat, EPS));
            Mat4f r = Mat4f.rotation(q);
            Quatf back =
                    Quatf.fromMatrix(
                            r.m00(), r.m10(), r.m20(), r.m01(), r.m11(), r.m21(), r.m02(), r.m12(),
                            r.m22());
            assertTrue(back.approxEquals(q, 1e-5f));
            Quatf p = randomQuat();
            assertTrue(q.mul(p).rotate(v).approxEquals(q.rotate(p.rotate(v)), 1e-3f));
            assertTrue(q.mul(q.inverse()).approxEquals(Quatf.IDENTITY, 1e-5f));
```

Four facts in one loop: §9's formula equals §10.1's matrix (to `EPS` = $10^{-4}$ per component);
§10.2 inverts §10.1 (note the argument order, the naming trap of §18; and remember from §15.6 that
`approxEquals(…, 1e-5f)` allows about half a degree); §8.8's order rule, `q.mul(p)` = "p first, then
q"; and §8.4's inverse.

**`slerpAndLookRotation`**:

```java
        Quatf a = Quatf.IDENTITY;
        Quatf b = Quatf.fromYaw(MathF.HALF_PI);
        assertTrue(a.slerp(b, 0).approxEquals(a, 1e-6f));
        assertTrue(a.slerp(b, 1).approxEquals(b, 1e-6f));
        assertEquals(MathF.PI / 4, a.slerp(b, 0.5f).angle(), 1e-4f);
        assertEquals(MathF.PI / 4, a.nlerp(b, 0.5f).angle(), 1e-3f);
        Vec3f dir = new Vec3f(1, 0, 1).normalize();
        Quatf look = Quatf.lookRotation(dir, Vec3f.UNIT_Y);
        assertTrue(look.forward().approxEquals(dir, EPS));
        Quatf ypr = Quatf.fromYawPitchRoll(0.3f, 0.2f, 0.1f);
        assertEquals(1f, ypr.lengthSquared(), 1e-5f);
```

Slerp hits both ends and the 45° midpoint (§11.2); nlerp's midpoint is exact too (§11.3, symmetry);
`lookRotation` really points $-Z$ at the requested direction (§10.3, through `forward()`); and a
product of three unit quaternions is a unit quaternion (§8.4).

**`quatOpsMatchQuatf`** stores 64 random quaternions **times 3** in SoA arrays, calls
`QuatOps.normalize`, then `QuatOps.rotate`, and compares with `Quatf.rotate` (to $10^{-4}$). The
factor 3 proves the normalization works, which §9's formula needs.

**`trsComposition`** checks §5.2's sign of $R_y$ and the TRS order (chapter 02):

```java
        Quatf q = Quatf.fromAxisAngle(Vec3f.UNIT_Y, MathF.HALF_PI);
        Mat4f m = Mat4f.trs(new Vec3f(10, 0, 0), q, new Vec3f(2, 2, 2));
        // (1,0,0) scaled to (2,0,0), rotated 90° about Y to (0,0,-2), translated to (10,0,-2).
        assertTrue(m.transformPoint(Vec3f.UNIT_X).approxEquals(new Vec3f(10, 0, -2), EPS));
```

**`affineOpsAgreeWithMat4`** composes 100 random TRS with `AffineOps.composeTRS` and with `Mat4f.trs`
and checks they agree (and that their products and inverses agree), so the row-major and column-major
versions of §17 are the same matrix. **`hierarchyConcatenation`** chains three +1 translations with
`AffineOps.concatenate` and expects $x = 3$ (chapter 02's parent × local). And
**`mat3NormalMatrixAndMutableTypes`** includes the mutable quaternion:

```java
        MVec3f v = new MVec3f(1, 0, 0);
        new MQuatf().fromAxisAngle(0, 1, 0, MathF.HALF_PI).rotate(v);
        assertTrue(v.toVec3f().approxEquals(new Vec3f(0, 0, -1), EPS));
```

`kernelsDoNotAllocate` wraps `AffineOps.concatenate` and other kernels in
`Allocations.assertNoAllocation`; it does not cover `QuatOps` (exercise 9 adds that).

### 30.2 FormatsTest: QTangents

[`FormatsTest.qtangentsKeepTheFrameAndHandedness`](../../modules/asset/src/test/java/dev/jeddy/asset/format/FormatsTest.java)
encodes and decodes 20,000 random frames (seed 17) with random handedness:

```java
        for (int i = 0; i < 20_000; i++) {
            randomUnit(r, n);
            randomUnit(r, t);
            float sign = r.nextBoolean() ? 1 : -1;
            MeshEncoding.encodeQTangent(n, 0, t, 0, sign, q, 0);
            MeshEncoding.qtangentNormal(q, 0, outN, 0);
            MeshEncoding.qtangentTangent(q, 0, outT, 0);
            assertEquals(sign, MeshEncoding.qtangentHandedness(q, 0));
            for (int k = 0; k < 3; k++) {
                assertEquals(n[k], outN[k], 2e-3f, "normal");
            }
            // The decoded tangent is the input orthogonalized against the normal.
            float d = n[0] * t[0] + n[1] * t[1] + n[2] * t[2];
            float[] ortho = {t[0] - n[0] * d, t[1] - n[1] * d, t[2] - n[2] * d};
            float len = (float) Math.sqrt(dot(ortho, ortho));
            if (len > 0.05f) {
                for (int k = 0; k < 3; k++) {
                    assertEquals(ortho[k] / len, outT[k], 3e-3f, "tangent");
                }
            }
        }
```

- The handedness must come back **exactly**: the sign trick and the bias of §12.2.
- The normal within $2 \times 10^{-3}$ per component, the cost of 16-bit quantization.
- The tangent is compared with the **input made perpendicular to the normal** (stage 1 of §20), within
  $3 \times 10^{-3}$, and only when that perpendicular part is longer than 0.05: a tangent almost
  parallel to the normal has an ill-defined direction, and the encoder's fallback (any perpendicular
  vector) is allowed to differ.

The same file's `meshRoundTrip` writes two quads whose tangents have opposite handedness through a
whole `.jmesh` and reads back their normals and signs.

### 30.3 GltfImporterTest: a mirrored matrix

```java
    @Test
    void decomposesMirroredMatrices() {
        // Rotation of 30° about Z, scale (-1, 2, 3), translation (4, 5, 6).
        double c = Math.cos(Math.PI / 6);
        double sn = Math.sin(Math.PI / 6);
        float[] m = {
            (float) (-c),
            (float) (-sn),
            0,
            0,
            (float) (-2 * sn),
            (float) (2 * c),
            0,
            0,
            0,
            0,
            3,
            0,
            4,
            5,
            6,
            1
        };
        float[] t = new float[3];
        float[] r = new float[4];
        float[] s = new float[3];
        GltfImporter.decompose(m, t, r, s);
        assertArrayEquals(new float[] {4, 5, 6}, t);
        assertArrayEquals(new float[] {-1, 2, 3}, s, 1e-5f);
        assertArrayEquals(
                new float[] {0, 0, (float) Math.sin(Math.PI / 12), (float) Math.cos(Math.PI / 12)},
                r,
                1e-5f);
    }
```

The matrix is written column by column: column 0 is $-1 \cdot (\cos 30°, \sin 30°, 0)$, column 1 is $2
\cdot (-\sin 30°, \cos 30°, 0)$, column 2 is $3\,\mathbf{e}_z$, column 3 the translation. The
determinant is $-1 \cdot 2 \cdot 3 = -6$, so §19 gives the mirror to $x$ and recovers exactly the
scale $(-1, 2, 3)$, and the rotation $(0, 0, \sin 15°, \cos 15°)$: 30° about $Z$, with §8.5's half
angle. The test lives in the same package as `GltfImporter`, because `decompose` is package-private.

### 30.4 TransformStoreTest

[`TransformStoreTest`](../../modules/scene/src/test/java/dev/jeddy/scene/TransformStoreTest.java)
compares the store with the simplest possible implementation, a recursive one that recomputes from
scratch:

```java
    /** Reference: world(e) = world(parent(e)) * TRS(e), recursively, from scratch. */
    private static float[] reference(TransformStore t, int entity) {
        float[] local = new float[12];
        AffineOps.composeTRS(
                t.position(entity, 0),
                t.position(entity, 1),
                t.position(entity, 2),
                t.rotation(entity, 0),
                t.rotation(entity, 1),
                t.rotation(entity, 2),
                t.rotation(entity, 3),
                t.scale(entity, 0),
                t.scale(entity, 1),
                t.scale(entity, 2),
                local,
                0);
        int p = t.parent(entity);
        if (p == Handles.NULL) {
            return local;
        }
        float[] out = new float[12];
        AffineOps.mul(reference(t, p), 0, local, 0, out, 0);
        return out;
    }
```

Slow, allocating and recursive: exactly what the store avoids, and therefore a good judge. The tests:

- **`matchesTheReferenceSequentialAndParallel`**: a random forest of 12,000 entities (each attached
  to a random earlier one, or a root with probability 1/4), once without and once with a 3-worker job
  system. The first update must change all 12,000 and match the reference; then 500 random
  reparentings (some rejected as cycles) and 300 random local changes, an update, the reference again;
  and finally an update with nothing changed must return 0, which proves the level skipping of §26.
  Its two biggest levels go through the parallel path (§26).
- **`onlyChangedSubtreesRecompute`** checks the dirty logic number by number:

  ```java
        assertEquals(4, t.update(null));
        t.setPosition(root, 1, 2, 3);
        assertEquals(3, t.update(null), "root and its two descendants");
        assertTrue(t.changedInLastUpdate(t.indexOf(grandChild)));
        assertFalse(t.changedInLastUpdate(t.indexOf(other)));
        assertTrue(t.isStaticAt(t.indexOf(other)));
        assertEquals(3, t.world()[t.indexOf(grandChild) * 12 + 11]);
        t.setPosition(grandChild, 0, 0, 1);
        assertEquals(1, t.update(null));
        assertEquals(4, t.world()[t.indexOf(grandChild) * 12 + 11]);
  ```

  Four transforms (`root`, `other`, `child` under `root`, `grandChild` under `child`): the first update
  computes all 4. Moving `root` to $(1, 2, 3)$ changes it and its two descendants, 3, not `other`; the
  grandchild's world $z$ translation (index 11 of its block, row 2, column 3) is now 3. Moving the
  grandchild to local $(0, 0, 1)$ changes only it, and its world $z$ becomes $3 + 1 = 4$.
- **`cyclesAreRejected`**: with `a → b → c`, `setParent(a, c)` and `setParent(a, a)` throw; after `c`
  becomes a root, `setParent(a, c)` is allowed.
- **`removingAParentMovesChildrenUp`**: destroying `b` (at local $y = 5$ under `a` at $x = 10$) moves
  its children `c` and `d` under `a`; their world $y$ loses the 5, their $x$ keeps the 10. Destroying
  `a` makes them roots at $x = 0$.
- **`updatesAllocateNothing`**: a 10,000-entity forest, then `Allocations.assertNoAllocation` around
  "64 `setPosition` calls and an update with the job system", the zero-allocation rule of L00 §8.2
  proved for the real path.

### 30.5 RenderPacketTest: interpolation

```java
        RenderPacket p = new RenderPacket(4);
        float[] prev = new float[12];
        float[] curr = new float[12];
        AffineOps.identity(prev, 0);
        AffineOps.identity(curr, 0);
        curr[3] = 10; // x translation
        p.add(prev, 0, curr, 0, 7, 3, RenderPacket.FLAG_CASTS_SHADOW, 99);
        p.alpha = 0.25f;
        float[] out = new float[12];
        p.interpolatedWorld(0, out, 0);
        assertEquals(2.5f, out[3]);
```

From $x = 0$ to $x = 10$ at $\alpha = 0.25$: $0 + (10 - 0) \times 0.25 = 2.5$, exactly, because 2.5 is
representable and the arithmetic has no rounding here. (The rest of `interpolatesBetweenTicks` checks
that the packet grows past its initial capacity of 4.)

## 31. Measured numbers

From [bench/README.md](../../bench/README.md), "Scene and render numbers (M5)": local runs on a
2-core sandbox, hand-timed (not JMH), of `TransformStore.update` over 100,000 transforms in the shape
of `TransformBench` (40% roots, 40% children, 20% grandchildren):

| Case | Time |
|---|---|
| all 100,000 dirty | 2.5–3.3 ms |
| 10% of the roots moved | 0.6–0.8 ms |

What those mean, computed from the benchmark's own setup (seed 3, the same code run outside JMH):

- **All dirty** is 25–33 ns per transform: a TRS composition (§17), a 3×4 product for children (36
  multiplications, chapter 02), the flag and stamp writes.
- **10% of the roots** is 4,000 roots, and the update reports **10,066** changed transforms: the moved
  roots and their descendants (about a tenth of each level, since children pick their parents at
  random). Yet the time is about a quarter of the all-dirty case, not a tenth: every level is
  scanned, because each one contains something that changed, and the scan visits all 100,000 slots
  with a skip test each. Level skipping helps when whole levels are quiet, which is common in a real
  level (most props never move) but not in this benchmark.

A quick hand-timed check while writing this chapter, on another 2-core machine with the same code
on one thread, gave 3.3–4.1 ms and 0.77–0.94 ms: the same picture. These are single measurements on
small cloud machines; run the benchmark on yours (§32) before drawing conclusions about other
hardware.

There are no recorded numbers for `MathBench` (TRS composition and the hierarchy pass over 10,000
items), only the benchmark; and no GPU measurements of the QTangent decode, which would need a real
GPU and a profiler.

## 32. Things to run

- The tests of this chapter (Linux/macOS; on Windows use `gradlew.bat`):

  ```
  ./gradlew :math:test --tests 'dev.jeddy.math.MatrixAndQuatTest'
  ./gradlew :scene:test --tests 'dev.jeddy.scene.TransformStoreTest'
  ./gradlew :asset:test --tests 'dev.jeddy.asset.format.FormatsTest'
  ./gradlew :cooker:test --tests 'dev.jeddy.cooker.gltf.GltfImporterTest'
  ./gradlew :render:test --tests 'dev.jeddy.render.RenderPacketTest'
  ```

- The shader side (`jeddyQuatRotate`, `jeddyTangentFrame`) only runs on a GPU:
  `./gradlew :render:test -Pjeddy.gpuTests=true` (needs an OpenGL 4.6 driver).
- The benchmarks: `./gradlew :bench:jmh -Pjmh.includes=TransformBench` (all dirty and 10% of roots,
  with and without the job system) and `./gradlew :bench:jmh -Pjmh.includes=MathBench`
  (`composeTRS10k`, `concatenate10k`).
- In `jshell`, after `./gradlew :math:classes`:

  ```
  $ jshell --class-path modules/math/build/classes/java/main
  jshell> import dev.jeddy.math.*
  jshell> Quatf.fromYaw(MathF.HALF_PI)
  jshell> Quatf.fromYawPitchRoll(0.3f, MathF.HALF_PI, 0.1f)
  jshell> Quatf.fromYawPitchRoll(0.8f, MathF.HALF_PI, 0.6f)
  jshell> Quatf.IDENTITY.nlerp(new Quatf(0, -0.70710677f, 0, -0.70710677f), 0.5f)
  jshell> Quatf.lookRotation(Vec3f.UNIT_X, Vec3f.UNIT_Y)
  jshell> MathF.wrapAngle(-6f)
  ```

  Expect §15.2's yaw, §6.3's twin gimbal-locked quaternions, §11.4's 45° yaw, §10.3's $-90°$ yaw and
  §2.6's 0.2832.
- The demo with its allocation probe, which attributes game-thread allocations to systems, the
  transform update among them ("tick: transforms"):
  `./gradlew :iso-demo:run --args="--headless --perf 20 --warmup 6 --alloc-probe"`.

## 33. Exercises

Try each one before opening the hint, and the hint before the solution. Every number in the
solutions was computed, and every code solution was compiled and run against the repository's code
while this chapter was written.

**1. Degrees, radians and the short way round** (§2). Convert 270° to radians and 2 rad to degrees.
What does `MathF.wrapAngle(5f)` return? A character with `yaw[a]` = 2.8 is told to face yaw −2.5;
with `AgentSync`'s default turn rate, how many ticks until it faces it, and which way does it turn?

<details><summary>Hint</summary>

Multiply by $\pi/180$ or $180/\pi$. For `wrapAngle`, find the whole number of turns nearest to
$5/2\pi$. For the character, wrap $-2.5 - 2.8$ with §2.6's formula, then turn at most $\frac13$ rad
per tick.
</details>
<details><summary>Solution</summary>

$270 \cdot \pi/180 = 3\pi/2 = 4.7124$ rad; $2 \cdot 180/\pi = 114.59°$. $5/2\pi = 0.796$, nearest whole
number 1, so `wrapAngle(5f)` = $5 - 6.2832 = -1.2832$.

The raw difference is $-2.5 - 2.8 = -5.3$; $\lfloor(-5.3 + \pi)/2\pi\rfloor = \lfloor -0.3435\rfloor =
-1$, so the wrapped difference is $-5.3 + 2\pi = 0.9832$ rad (56.3°): turn **counter-clockwise**, through
$\pi$, not 303.7° clockwise. `maxTurn` is $10 \times \frac{1}{30} = 0.3333$. Running `apply`'s lines:
tick 1, $d = 0.9832$, yaw becomes 3.1333; tick 2, $d = 0.6499$, yaw 3.4667; tick 3, $d = 0.3165 \le
0.3333$, the facing is reached, yaw 3.7832, which is $-2.5 + 2\pi$: the same direction as $-2.5$.
**Three ticks.** (`yaw[a]` is now above $\pi$, which is harmless, §29.1.)
</details>

**2. Facing a point** (§5.3). A character stands at $(2, 0, 3)$ and must face $(-1, 0, 7)$. Compute
its yaw, the quaternion `World.setYaw` writes, and the direction the model's $+Z$ ends up pointing.

<details><summary>Hint</summary>

$\psi = \operatorname{atan2}(d_x, d_z)$, $x$ first. The quaternion is $(0, \sin\frac{\psi}{2}, 0,
\cos\frac{\psi}{2})$, and the facing is $(\sin\psi, 0, \cos\psi)$.
</details>
<details><summary>Solution</summary>

$d = (-1 - 2,\; 7 - 3) = (-3, 4)$, so $\psi = \operatorname{atan2}(-3, 4) = -0.6435$ rad ($-36.87°$).
The quaternion is $(0, \sin(-0.3218), 0, \cos(-0.3218)) = (0, -0.3162, 0, 0.9487)$. The facing is
$(\sin(-0.6435), 0, \cos(-0.6435)) = (-0.6, 0, 0.8)$, which is $d$ divided by its length 5.
`Quatf.fromYaw((float) Math.atan2(-3, 4)).rotate(Vec3f.UNIT_Z)` returns `Vec3f[x=-0.6, y=0.0, z=0.8]`.
</details>

**3. Turning by multiplying** (§4). Rotate the point $(3, 4)$ by 60° using complex numbers, and check
the length. Then square the rotation by 30°, $0.8660 + 0.5i$, and say which rotation you get.

<details><summary>Hint</summary>

Multiply $(\cos 60° + i\sin 60°)(3 + 4i)$ out with $i^2 = -1$. Squaring a rotation applies it twice.
</details>
<details><summary>Solution</summary>

$(0.5 + 0.8660i)(3 + 4i) = 1.5 + 2i + 2.5981i + 3.4641i^2 = (1.5 - 3.4641) + (2 + 2.5981)i = -1.9641 +
4.5981i$. Length: $\sqrt{1.9641^2 + 4.5981^2} = \sqrt{3.8577 + 21.1425} = \sqrt{25.0002} = 5.0000$, as
before (rotations keep lengths). $(0.8660 + 0.5i)^2 = 0.75 + 0.8660i + 0.25i^2 = 0.5 + 0.8660i =
\cos 60° + i\sin 60°$: the rotation by 60°, as composing two 30° turns should be. The real part is
§3.4's $\cos^2\frac{\theta}{2} - \sin^2\frac{\theta}{2}$, the imaginary part $2\sin\frac{\theta}{2}
\cos\frac{\theta}{2}$.
</details>

**4. Yaw by hand** (§10.1). Compute the quaternion and the 3×4 matrix for a yaw of 60° on paper,
then check them with `Quatf.fromYaw` and `AffineOps.composeTRS` in a test. Also check where $+Z$ (a
model's forward) goes.

<details><summary>Hint</summary>

$q = (0, \sin 30°, 0, \cos 30°)$. In §10.1's matrix only $y^2$ and $wy$ are non-zero. $+Z$ goes to the
third column.
</details>
<details><summary>Solution</summary>

$q = (0, 0.5, 0, 0.8660)$. $y^2 = 0.25$, $wy = 0.4330$, the other products 0. Rows: $(1 - 2 \cdot 0.25,\;
0,\; 2 \cdot 0.4330) = (0.5, 0, 0.8660)$; $(0, 1, 0)$; $(-0.8660, 0, 0.5)$: that is $R_y(60°)$ with
$\cos 60° = 0.5$, $\sin 60° = 0.8660$. $+Z$ goes to the third column, $(0.8660, 0, 0.5)$, and
`atan2(0.8660, 0.5)` is 60° again. As a test (in `MatrixAndQuatTest`):

```java
    @Test
    void yawBy60() {
        Quatf q = Quatf.fromYaw(MathF.toRadians(60));
        assertEquals(0.5f, q.y(), 1e-6f);
        assertEquals(0.8660254f, q.w(), 1e-6f);
        float[] m = new float[12];
        AffineOps.composeTRS(0, 0, 0, q.x(), q.y(), q.z(), q.w(), 1, 1, 1, m, 0);
        assertEquals(0.5f, m[0], 1e-6f); // row 0: cos 60°, 0, sin 60°
        assertEquals(0.8660254f, m[2], 1e-6f);
        assertEquals(-0.8660254f, m[8], 1e-6f); // row 2: -sin 60°, 0, cos 60°
        Vec3f facing = q.rotate(Vec3f.UNIT_Z);
        assertEquals(MathF.toRadians(60), (float) Math.atan2(facing.x(), facing.z()), 1e-6f);
    }
```

`composeTRS` prints `[0.5, 0.0, 0.8660254, 0.0, 0.0, 1.0, 0.0, 0.0, -0.8660254, 0.0, 0.5, 0.0]`.
</details>

**5. A product by hand** (§8.2, §8.8). With $s = 0.7071$, a yaw of 90° is $(0, s, 0, s)$ and a roll
(about $Z$) of 90° is $(0, 0, s, s)$. Compute `yaw.mul(roll)` and `roll.mul(yaw)` with the four lines
of §8.2, and say where each sends $(1, 0, 0)$.

<details><summary>Hint</summary>

Only products with two non-zero factors survive. The right-hand factor acts first: in `yaw.mul(roll)`
the roll turns $x$ first.
</details>
<details><summary>Solution</summary>

`yaw.mul(roll)` ($q_1$ = yaw, $q_2$ = roll): $x = w_1x_2 + x_1w_2 + y_1z_2 - z_1y_2 = 0 + 0 + s^2 - 0 =
0.5$; $y = w_1y_2 - x_1z_2 + y_1w_2 + z_1x_2 = s^2 = 0.5$; $z = w_1z_2 + x_1y_2 - y_1x_2 + z_1w_2 = s^2
= 0.5$; $w = w_1w_2 = 0.5$. So $(0.5, 0.5, 0.5, 0.5)$, §7.3's 120° turn about the diagonal: $(1, 0, 0)
\to (0, 1, 0)$. Check by steps: the roll turns $x$ to $y$, and the yaw leaves $y$ alone.

`roll.mul(yaw)`: $x = s \cdot 0 + 0 + 0 - s \cdot s = -0.5$; $y = s \cdot s = 0.5$; $z = s \cdot s = 0.5$; $w =
0.5$. So $(-0.5, 0.5, 0.5, 0.5)$. §9's formula on $(1, 0, 0)$: $\mathbf{t} = 2(0, 0.5, -0.5) = (0, 1, -1)$,
$w\mathbf{t} = (0, 0.5, -0.5)$, $\mathbf{u} \times \mathbf{t} = (-1, -0.5, -0.5)$, sum $(0, 0, -1)$. By
steps: the yaw turns $x$ to $-z$ (§5.2), and the roll about $Z$ leaves $-z$ alone. The code agrees:
`yaw.mul(roll).rotate(UNIT_X)` is $(0, 1, 0)$ and `roll.mul(yaw).rotate(UNIT_X)` is $(0, 0, -1)$, up to
`float` rounding.
</details>

**6. Demonstrate gimbal lock** (§6.3). In `MatrixAndQuatTest`, assert that
`fromYawPitchRoll(a, MathF.HALF_PI, c)` and `fromYawPitchRoll(a + d, MathF.HALF_PI, c + d)` are the
same rotation for random `a`, `c`, `d`, and that the same is false for a pitch of 0.3.

<details><summary>Hint</summary>

Use `Quatf.approxEquals`, which already ignores the sign of the quaternion. With `epsilon` = 1e-5 it
accepts about half a degree (§15.6), far more than the rounding here; for the negative case pick a
clearly non-zero `d`.
</details>
<details><summary>Solution</summary>

```java
    @Test
    void pitchOf90DegreesLocksYawAndRoll() {
        for (int n = 0; n < 100; n++) {
            float a = random.nextFloat() * 6 - 3;
            float c = random.nextFloat() * 6 - 3;
            float d = random.nextFloat() * 2 - 1;
            Quatf q1 = Quatf.fromYawPitchRoll(a, MathF.HALF_PI, c);
            Quatf q2 = Quatf.fromYawPitchRoll(a + d, MathF.HALF_PI, c + d);
            assertTrue(q1.approxEquals(q2, 1e-5f), "locked at " + a + ", " + c + ", " + d);
        }
        Quatf q1 = Quatf.fromYawPitchRoll(0.4f, 0.3f, 0.2f);
        Quatf q2 = Quatf.fromYawPitchRoll(0.9f, 0.3f, 0.7f);
        assertFalse(q1.approxEquals(q2, 1e-5f));
    }
```

Why it passes: §8.8's closed form depends only on $\alpha - \gamma$, and adding $d$ to both leaves it
unchanged. At pitch 0.3 there is no such identity: yaw and roll turn about different axes, and the two
rotations are about 34° apart.
</details>

**7. The Inspector's angles** (§29.5). For $R = R_y(\psi)\,R_x(\theta)\,R_z(\phi)$, compute row 1 and
the entries $(0, 2)$ and $(2, 2)$ of the product, compare with §10.1's matrix, and derive
`GizmoMath.toEuler`'s three formulas. What happens at $\theta = 90°$?

<details><summary>Hint</summary>

Row 1 of $R_y$ is $(0, 1, 0)$, so row 1 of $R$ is row 1 of $R_xR_z$. For the yaw, row 0 and row 2 of
$R_y$ meet column 2 of $R_xR_z$, which is $(0, -\sin\theta, \cos\theta)$.
</details>
<details><summary>Solution</summary>

$R_xR_z$ has row 1 $= (0, \cos\theta, -\sin\theta)$ times the columns of $R_z$: $(\cos\theta\sin\phi,\;
\cos\theta\cos\phi,\; -\sin\theta)$, and that is row 1 of $R$. So $m_{12} = -\sin\theta$, and in the
quaternion matrix $m_{12} = 2(yz - wx)$: **$\sin\theta = 2(wx - yz)$**. Also $m_{10} = \cos\theta
\sin\phi$ and $m_{11} = \cos\theta\cos\phi$, so **$\phi = \operatorname{atan2}(m_{10}, m_{11}) =
\operatorname{atan2}(2(xy + wz),\; 1 - 2(x^2 + z^2))$**. Column 2 of $R_xR_z$ is $(0, -\sin\theta,
\cos\theta)$; row 0 of $R_y$, $(\cos\psi, 0, \sin\psi)$, gives $m_{02} = \sin\psi\cos\theta$, and row 2,
$(-\sin\psi, 0, \cos\psi)$, gives $m_{22} = \cos\psi\cos\theta$: **$\psi = \operatorname{atan2}(2(xz +
wy),\; 1 - 2(x^2 + y^2))$**. These are `toEuler`'s lines (the pitch clamped to $[-1, 1]$ before
`asin`, against rounding). Check: `fromYawPitchRoll(0.7f, 0.4f, -0.3f)` gives back 0.7, 0.4, −0.3.

At $\theta = 90°$, $\cos\theta = 0$ and all four entries used for yaw and roll are 0: `atan2(0, 0)`
says nothing. §6.3's locked matrix still has $m_{00} = \cos(\psi - \phi)$ and $m_{20} = -\sin(\psi -
\phi)$, so only $\psi - \phi$ is known; `toEuler` sets the roll to 0 and the yaw to
$\operatorname{atan2}(-m_{20}, m_{00}) = \operatorname{atan2}(2(wy - xz),\; 1 - 2(y^2 + z^2))$. (At
$-90°$ the same formula gives $\psi + \phi$, again with roll 0.)
</details>

**8. Shortest-arc rotation.** Add `Quatf.fromTo(Vec3f from, Vec3f to)`, returning the smallest
rotation that turns unit vector `from` into unit vector `to`, with a test that `fromTo(a, b).rotate(a)`
≈ `b` for random unit vectors, including nearly opposite ones.

<details><summary>Hint</summary>

For unit vectors at angle $\theta$, $\mathbf{a} \times \mathbf{b} = \hat{\mathbf{n}}\sin\theta$ and $1 +
\mathbf{a}\cdot\mathbf{b} = 1 + \cos\theta$. Use §3.4 to show that $(\mathbf{a} \times \mathbf{b},\; 1 +
\mathbf{a}\cdot\mathbf{b})$ is a multiple of $(\hat{\mathbf{n}}\sin\frac{\theta}{2}, \cos\frac{\theta}{2})$.
When the vectors are opposite, any axis perpendicular to `a` works.
</details>
<details><summary>Solution</summary>

$\sin\theta = 2\sin\frac{\theta}{2}\cos\frac{\theta}{2}$ and $1 + \cos\theta = 2\cos^2\frac{\theta}{2}$, so
$(\hat{\mathbf{n}}\sin\theta,\; 1 + \cos\theta) = 2\cos\frac{\theta}{2}\,(\hat{\mathbf{n}}\sin\frac{\theta}{2},
\;\cos\frac{\theta}{2})$: normalizing gives §8.5's rotation by $\theta$ about $\hat{\mathbf{n}}$, the axis
perpendicular to both vectors (the shortest arc). No trigonometry at all. When $\theta \to 180°$ both
parts go to 0 and the direction is lost, so opposite vectors get a half turn, $(\hat{\mathbf{n}}, 0)$,
about an axis made perpendicular to `a` with a cross product (with $\mathbf{e}_x$, or $\mathbf{e}_y$ if
`a` is along $x$, as §20 does for tangents):

```java
    /**
     * Returns the smallest rotation that turns unit vector {@code from} into unit vector {@code
     * to}. Within about 0.08° of opposite vectors it returns a half turn about an arbitrary axis
     * perpendicular to {@code from}, which turns {@code from} into {@code -from}.
     */
    public static Quatf fromTo(Vec3f from, Vec3f to) {
        float d = from.dot(to);
        if (d < -0.999999f) {
            // Opposite: a half turn about any axis perpendicular to from.
            Vec3f axis = Vec3f.UNIT_X.cross(from);
            if (axis.lengthSquared() < 1e-6f) {
                axis = Vec3f.UNIT_Y.cross(from);
            }
            axis = axis.normalize();
            return new Quatf(axis.x(), axis.y(), axis.z(), 0);
        }
        Vec3f c = from.cross(to);
        return new Quatf(c.x(), c.y(), c.z(), 1 + d).normalize();
    }
```

```java
    @Test
    void fromToTurnsOneVectorIntoAnother() {
        for (int n = 0; n < 1000; n++) {
            Vec3f a = randomQuat().rotate(Vec3f.UNIT_X);
            Vec3f b = randomQuat().rotate(Vec3f.UNIT_X);
            if (n % 10 == 0) {
                // nearly opposite
                b = a.negate().add(randomQuat().rotate(Vec3f.UNIT_X).mul(1e-2f)).normalize();
            }
            assertTrue(Quatf.fromTo(a, b).rotate(a).approxEquals(b, 1e-3f), "case " + n);
        }
        Vec3f up = Vec3f.UNIT_Y;
        assertTrue(Quatf.fromTo(up, up.negate()).rotate(up).approxEquals(up.negate(), 1e-5f));
        Vec3f x = Vec3f.UNIT_X;
        assertTrue(Quatf.fromTo(x, x.negate()).rotate(x).approxEquals(x.negate(), 1e-5f));
    }
```

Random unit vectors come from rotating $\mathbf{e}_x$ by a random rotation, which never produces a zero
vector. Every tenth pair is $-\mathbf{a}$ nudged by a vector of length $10^{-2}$: between 179.43° and
179.89° apart in this test's 100 such pairs, all of which take the **general** formula (their dot
products are above $-0.999999$), with a worst error of $1.4 \times 10^{-4}$, well inside the $10^{-3}$
tolerance. Both parts of $(\mathbf{a} \times \mathbf{b},\; 1 + \mathbf{a} \cdot \mathbf{b})$ are small
there but still far above rounding. The two exactly opposite pairs at the end take the special branch.
Do not nudge by much less: a nudge of $10^{-3}$ puts every pair past the threshold, so the special
branch would answer all of them, and the test would pass only because its tolerance equals the
nudge.

The threshold has a price. $\arccos 0.999999 = 1.41 \times 10^{-3}$ rad (0.081°), so for vectors within
0.081° of opposite, `fromTo` returns a half turn that sends `from` to exactly $-$`from`, which can miss
`to` by up to $1.4 \times 10^{-3}$ rad; and that half turn is about an arbitrary perpendicular axis, not
the axis of the smallest rotation. In that thin band "the smallest rotation" is an approximation, as
the Javadoc says. The method goes in `Quatf` next to `lookRotation`.
</details>

**9. A slerp kernel.** Add `QuatOps.slerp(...)` with the same SoA signature as `QuatOps.nlerp`. Test it
against `Quatf.slerp` on 64 random pairs and in an `Allocations.assertNoAllocation` block.

<details><summary>Hint</summary>

Copy the structure of `Quatf.slerp`, including the sign flip and the 0.9995 threshold, into the loop
body; flip the sign of the second weight instead of the second quaternion, as `nlerp` does. Keep
`Math.acos` and `Math.sin` in `double`. `kernelsDoNotAllocate` shows the allocation test pattern.
</details>
<details><summary>Solution</summary>

```java
    /**
     * Spherical interpolation along the shortest arc: {@code out[i] = slerp(a[i], b[i], t)} for
     * {@code [start, end)}, the arithmetic of {@link Quatf#slerp}.
     */
    public static void slerp(
            float[] ax,
            float[] ay,
            float[] az,
            float[] aw,
            float[] bx,
            float[] by,
            float[] bz,
            float[] bw,
            float t,
            float[] ox,
            float[] oy,
            float[] oz,
            float[] ow,
            int start,
            int end) {
        for (int i = start; i < end; i++) {
            float d = ax[i] * bx[i] + ay[i] * by[i] + az[i] * bz[i] + aw[i] * bw[i];
            float sign = 1;
            if (d < 0) {
                d = -d;
                sign = -1;
            }
            float wa;
            float wb;
            if (d > 0.9995f) {
                wa = 1 - t;
                wb = t;
            } else {
                double theta = Math.acos(d);
                double sin = Math.sin(theta);
                wa = (float) (Math.sin((1 - t) * theta) / sin);
                wb = (float) (Math.sin(t * theta) / sin);
            }
            wb *= sign;
            float x = wa * ax[i] + wb * bx[i];
            float y = wa * ay[i] + wb * by[i];
            float z = wa * az[i] + wb * bz[i];
            float w = wa * aw[i] + wb * bw[i];
            float inv = (float) (1 / Math.sqrt(x * x + y * y + z * z + w * w));
            ox[i] = x * inv;
            oy[i] = y * inv;
            oz[i] = z * inv;
            ow[i] = w * inv;
        }
    }
```

```java
    @Test
    void slerpKernelMatchesQuatfAndAllocatesNothing() {
        int n = 64;
        float[] ax = new float[n], ay = new float[n], az = new float[n], aw = new float[n];
        float[] bx = new float[n], by = new float[n], bz = new float[n], bw = new float[n];
        float[] ox = new float[n], oy = new float[n], oz = new float[n], ow = new float[n];
        Quatf[] a = new Quatf[n];
        Quatf[] b = new Quatf[n];
        for (int i = 0; i < n; i++) {
            a[i] = randomQuat();
            b[i] = i % 8 == 0 ? a[i] : randomQuat(); // some identical pairs: the lerp branch
            ax[i] = a[i].x();
            ay[i] = a[i].y();
            az[i] = a[i].z();
            aw[i] = a[i].w();
            bx[i] = b[i].x();
            by[i] = b[i].y();
            bz[i] = b[i].z();
            bw[i] = b[i].w();
        }
        float t = 0.3f;
        QuatOps.slerp(ax, ay, az, aw, bx, by, bz, bw, t, ox, oy, oz, ow, 0, n);
        for (int i = 0; i < n; i++) {
            Quatf expected = a[i].slerp(b[i], t);
            assertEquals(expected.x(), ox[i], 1e-5f);
            assertEquals(expected.y(), oy[i], 1e-5f);
            assertEquals(expected.z(), oz[i], 1e-5f);
            assertEquals(expected.w(), ow[i], 1e-5f);
        }
        Allocations.assertNoAllocation(
                "slerp kernel",
                () -> QuatOps.slerp(ax, ay, az, aw, bx, by, bz, bw, t, ox, oy, oz, ow, 0, n));
    }
```

The lambda captures the arrays, so it is one object created once, outside the measured calls. The
kernel multiplies by one reciprocal where `Quatf.normalize` divides four times, which is why the
comparison allows $10^{-5}$ rather than exact equality.
</details>

**10. Slerp and nlerp by hand** (§11). Slerp from the identity to a 120° yaw at $t = \frac12$, and
nlerp at $t = \frac14$. Compare with §11.3's table.

<details><summary>Hint</summary>

$q_1 = (0, \sin 60°, 0, \cos 60°) = (0, 0.8660, 0, 0.5)$, so $\cos\Omega = 0.5$ and $\Omega = 60°$. For
nlerp, lerp the four numbers and use $\varphi = 2\operatorname{atan2}(y, w)$.
</details>
<details><summary>Solution</summary>

Slerp: both weights are $\sin 30°/\sin 60° = 0.5/0.8660 = 0.5774$. The result is $(0,\; 0.5774 \cdot
0.8660,\; 0,\; 0.5774 + 0.5774 \cdot 0.5) = (0, 0.5, 0, 0.8660)$: a 60° yaw, exactly half. Nlerp at
$\frac14$: $(0,\; 0.25 \cdot 0.8660,\; 0,\; 0.75 + 0.25 \cdot 0.5) = (0, 0.2165, 0, 0.875)$, an angle of
$2\operatorname{atan2}(0.2165, 0.875) = 2 \cdot 13.90° = 27.80°$ instead of 30°: the table's first
row. `Quatf.IDENTITY.nlerp(q1, 0.25f).angle()` gives 27.7958° in degrees.
</details>

**11. Round-trip the decomposition.** In `GltfImporterTest` (same package as the package-private
`GltfImporter.decompose`), build random TRS matrices with `AffineOps.composeTRS` and
`AffineOps.toMat4`, decompose them, recompose, and compare. Include a negative scale on one axis and on
two axes. Run `./gradlew :cooker:test --tests 'dev.jeddy.cooker.gltf.GltfImporterTest'`.

<details><summary>Hint</summary>

Compare the recomposed **matrices**, not the TRS values: a scale of $(-1, -1, 1)$ comes back as $(1, 1,
1)$ with a different rotation, and $q$ may come back as $-q$. Matrices are unique; TRS values are
not.
</details>
<details><summary>Solution</summary>

```java
    @Test
    void decomposeRoundTripsTrsMatrices() {
        Random random = new Random(7);
        float[] trs = new float[12];
        float[] m = new float[16];
        float[] t = new float[3];
        float[] r = new float[4];
        float[] s = new float[3];
        float[] back = new float[12];
        for (int n = 0; n < 300; n++) {
            float qx = random.nextFloat() * 2 - 1;
            float qy = random.nextFloat() * 2 - 1;
            float qz = random.nextFloat() * 2 - 1;
            float qw = random.nextFloat() * 2 - 1;
            float len = (float) Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
            qx /= len;
            qy /= len;
            qz /= len;
            qw /= len;
            float sx = 0.5f + random.nextFloat();
            float sy = 0.5f + random.nextFloat();
            float sz = 0.5f + random.nextFloat();
            if (n % 3 == 1) {
                sx = -sx; // one mirrored axis
            } else if (n % 3 == 2) {
                sx = -sx; // two: a half turn in disguise
                sy = -sy;
            }
            float tx = random.nextFloat() * 20 - 10;
            float ty = random.nextFloat() * 20 - 10;
            float tz = random.nextFloat() * 20 - 10;
            AffineOps.composeTRS(tx, ty, tz, qx, qy, qz, qw, sx, sy, sz, trs, 0);
            AffineOps.toMat4(trs, 0, m, 0);
            GltfImporter.decompose(m, t, r, s);
            AffineOps.composeTRS(
                    t[0], t[1], t[2], r[0], r[1], r[2], r[3], s[0], s[1], s[2], back, 0);
            for (int k = 0; k < 12; k++) {
                assertEquals(trs[k], back[k], 1e-4f, "matrix " + n + " entry " + k);
            }
        }
    }
```

The cooker's tests can use `AffineOps`, because the cooker depends on `math`. For two negative scales,
`decompose` finds a positive determinant, returns scale $(\lvert s_x\rvert, \lvert s_y\rvert, s_z)$ and puts the half turn
about $z$ into the rotation, and the recomposed matrix is the same (§10.4).
</details>

**12. A cheaper tangent frame** (§10.1, §21). The shader calls `jeddyQuatRotate` twice on axis
vectors. Using the quaternion-to-matrix formula, write the tangent (column 0) and the normal (column
2) directly. Add the Java version to `MeshEncoding` and test that it matches `qtangentNormal` and
`qtangentTangent` on random QTangents. How many multiplications does it save?

<details><summary>Hint</summary>

Column 0 is $(1 - 2(y^2 + z^2),\; 2(xy + wz),\; 2(xz - wy))$ and column 2 is $(2(xz + wy),\; 2(yz -
wx),\; 1 - 2(x^2 + y^2))$. Normalize the decoded quaternion first.
</details>
<details><summary>Solution</summary>

```java
    /**
     * Decodes the tangent and the normal of a QTangent at once, from the columns of the rotation
     * matrix of the quaternion (column 0 is the tangent, column 2 the normal).
     */
    public static void qtangentFrame(
            short[] q, int qOff, float[] tangent, int tOff, float[] normal, int nOff) {
        float x = unsnorm16(q[qOff]);
        float y = unsnorm16(q[qOff + 1]);
        float z = unsnorm16(q[qOff + 2]);
        float w = unsnorm16(q[qOff + 3]);
        float inv = (float) (1 / Math.sqrt(x * x + y * y + z * z + w * w));
        x *= inv;
        y *= inv;
        z *= inv;
        w *= inv;
        float xx = x * x;
        float yy = y * y;
        float zz = z * z;
        float xy = x * y;
        float xz = x * z;
        float yz = y * z;
        float wx = w * x;
        float wy = w * y;
        float wz = w * z;
        tangent[tOff] = 1 - 2 * (yy + zz);
        tangent[tOff + 1] = 2 * (xy + wz);
        tangent[tOff + 2] = 2 * (xz - wy);
        normal[nOff] = 2 * (xz + wy);
        normal[nOff + 1] = 2 * (yz - wx);
        normal[nOff + 2] = 1 - 2 * (xx + yy);
    }
```

```java
    @Test
    void qtangentFrameMatchesTheRotations() {
        Random r = new Random(5);
        short[] q = new short[4];
        float[] n = new float[3];
        float[] t = new float[3];
        float[] rn = new float[3];
        float[] rt = new float[3];
        float[] fn = new float[3];
        float[] ft = new float[3];
        for (int i = 0; i < 10_000; i++) {
            for (int k = 0; k < 3; k++) {
                n[k] = r.nextFloat() * 2 - 1;
                t[k] = r.nextFloat() * 2 - 1;
            }
            MeshEncoding.encodeQTangent(n, 0, t, 0, r.nextBoolean() ? 1 : -1, q, 0);
            MeshEncoding.qtangentNormal(q, 0, rn, 0);
            MeshEncoding.qtangentTangent(q, 0, rt, 0);
            MeshEncoding.qtangentFrame(q, 0, ft, 0, fn, 0);
            for (int k = 0; k < 3; k++) {
                assertEquals(rn[k], fn[k], 1e-5f, "normal");
                assertEquals(rt[k], ft[k], 1e-5f, "tangent");
            }
        }
    }
```

After the shared normalization, two rotations cost 36 multiplications; the columns cost 9 products and
6 doublings, 15. In GLSL (not run here: the shader tests need a GPU, `./gradlew :render:test
-Pjeddy.gpuTests=true`):

```glsl
void jeddyTangentFrame(out vec3 normal, out vec3 tangent, out float handedness) {
    vec4 q = normalize(jeddyQTangent);
    vec3 q2 = 2.0 * q.xyz;
    float xx = q.x * q2.x, yy = q.y * q2.y, zz = q.z * q2.z;
    float xy = q.x * q2.y, xz = q.x * q2.z, yz = q.y * q2.z;
    float wx = q.w * q2.x, wy = q.w * q2.y, wz = q.w * q2.z;
    tangent = vec3(1.0 - (yy + zz), xy + wz, xz - wy);
    normal = vec3(xz + wy, yz - wx, 1.0 - (xx + yy));
    handedness = jeddyQTangent.w < 0.0 ? -1.0 : 1.0;
}
```

Doubling `q.xyz` once (three multiplications) replaces the six doublings of the Java version, so the
GLSL needs 12 multiplications after the normalization instead of 15. Whether it is measurably faster on a GPU,
where the vertex stage is rarely the bottleneck, is a question for a GPU profiler.
</details>

**13. A QTangent by hand** (§12.2, §20). Encode the normal $(1, 0, 0)$ with the tangent $(0, 0, -1)$,
first with $h = +1$, then with $h = -1$. Does the bias step run?

<details><summary>Hint</summary>

$\mathbf{b} = \mathbf{n} \times \mathbf{t}$. Write the columns $\mathbf{t}, \mathbf{b}, \mathbf{n}$ and
recognize the matrix from §5.2.
</details>
<details><summary>Solution</summary>

$\mathbf{b} = (1, 0, 0) \times (0, 0, -1) = (0 \cdot (-1) - 0 \cdot 0,\; 0 \cdot 0 - 1 \cdot (-1),\; 1 \cdot 0 -
0 \cdot 0) = (0, 1, 0)$. Columns $(0, 0, -1)$, $(0, 1, 0)$, $(1, 0, 0)$, so the rows are $(0, 0, 1)$,
$(0, 1, 0)$, $(-1, 0, 0)$: $R_y(90°)$. Trace 1, first branch: $s = 2\sqrt 2$, $w = 0.7071$, $y = (m_{02}
- m_{20})/s = 2/2.8284 = 0.7071$: $q = (0, 0.7071, 0, 0.7071)$. $w$ is far above `W_BIAS`, so the bias
does not run. Quantized: $(0, 23170, 0, 23170)$, and with $h = -1$, $(0, -23170, 0, -23170)$, which is
what the encoder writes. A normal along $+X$ with its tangent along $-Z$ is the identity frame turned by
a 90° yaw.
</details>

**14. Counting sort by hand** (§25). Six transforms in slots 0 to 5. Slots 1 and 5 are roots; 2 and 3
are children of 1; 0 is a child of 3; 4 is a child of 0. Compute the depths, then `levelStart` after
each pass of `rebuildOrder`, and `order`. Which slot does `depthOf(0)` fill in, and in how many steps?

<details><summary>Hint</summary>

Count each depth at index `depth + 1`. During the scatter, a slot goes to `order[levelStart[depth]]`
and that entry then grows by one.
</details>
<details><summary>Solution</summary>

Depths: slot 0 → 2 (0 → 3 → 1), 1 → 0, 2 → 1, 3 → 1, 4 → 3, 5 → 0. `levelCount` = 4.

```
  after counting:     levelStart = [0, 2, 2, 1, 1]
  after prefix sums:  levelStart = [0, 2, 4, 5, 6]
  scatter: s0 (d2) -> order[4];  s1 (d0) -> order[0];  s2 (d1) -> order[2];
           s3 (d1) -> order[3];  s4 (d3) -> order[5];  s5 (d0) -> order[1]
  after the scatter:  levelStart = [2, 4, 5, 6, 6]     order = [1, 5, 2, 3, 0, 4]
  after the shift:    levelStart = [0, 2, 4, 5, 6]
```

Levels: {1, 5}, {2, 3}, {0}, {4}. `depthOf(0)` runs first (the loop goes in slot order), with nothing
known yet: it walks 0 → 3 → 1 (two steps, 1 is a root), sets `depth[0] = 2` and `depth[3] = 1` on the
way back and `depth[1] = 0`. Later calls for slots 1 and 3 return at once, and `depthOf(4)` takes one
step (to 0, whose depth is known). A store built this way (add six roots, then `setParent` four times)
and read back after `update` gives exactly this `order` and `levelStart`.
</details>

**15. Predict the update** (§26). A store holds `r` with children `a` and `b`, `c` under `a`, and a
separate root `s`. After the first `update`, predict the return value of each of these updates, and
which levels each one scans: (i) after `setPosition(c, …)`; (ii) after `setRotation(a, …)`; (iii)
after `setScale(s, …)` and `setPosition(b, …)`; (iv) with nothing set; (v) after `setParent(c,
Handles.NULL)`.

<details><summary>Hint</summary>

Levels: 0 = {r, s}, 1 = {a, b}, 2 = {c}. A level is scanned if it is dirty or the level above changed
something. A structural change marks every level dirty.
</details>
<details><summary>Solution</summary>

The first update returns 5. (i) Only level 2 is dirty; levels 0 and 1 are skipped without a scan;
level 2 recomputes `c`: **1**. (ii) Level 1 is dirty; it recomputes `a` (not `b`), so level 2 is
scanned and `c` follows its changed parent: **2**. (iii) Levels 0 and 1 are dirty: `s` in level 0, `b`
in level 1; level 2 is scanned because level 1 changed, but `c`'s parent `a` did not change, so `c` is
skipped: **2**. (iv) Nothing dirty, nothing scanned: **0**. (v) Reparenting rebuilds the order and
marks every level dirty, so every level is scanned, but only `c` (now a root, flagged dirty by
`setParent`) is recomputed: **1**. Running these steps on a real store prints 5, 1, 2, 2, 0, 1.
</details>

**16. Measure the matrix-lerp shrink** (§14.3). Write a test that lerps two yaw matrices $\Delta$ apart
with `AffineOps.lerp` and asserts that at $t = 0.5$ the length of column 0 is $\cos\frac{\Delta}{2}$ and
the length of column 1 (the $Y$ axis) is 1. Then find the largest $\Delta$ for which the shrink stays
under 1%.

<details><summary>Hint</summary>

Column 0 of a row-major 3×4 is at indices 0, 4 and 8. Solve $\cos\frac{\Delta}{2} \ge 0.99$.
</details>
<details><summary>Solution</summary>

```java
    @Test
    void matrixLerpShrinksTheRotationPlane() {
        float[] a = new float[12];
        float[] b = new float[12];
        float[] mid = new float[12];
        for (float delta : new float[] {0.1f, 1f / 3f, 1f, 2f}) {
            Quatf q = Quatf.fromYaw(delta);
            AffineOps.identity(a, 0);
            AffineOps.composeTRS(0, 0, 0, q.x(), q.y(), q.z(), q.w(), 1, 1, 1, b, 0);
            AffineOps.lerp(a, 0, b, 0, 0.5f, mid, 0);
            float col0 = (float) Math.sqrt(mid[0] * mid[0] + mid[4] * mid[4] + mid[8] * mid[8]);
            float col1 = (float) Math.sqrt(mid[1] * mid[1] + mid[5] * mid[5] + mid[9] * mid[9]);
            assertEquals((float) Math.cos(delta / 2), col0, 1e-5f);
            assertEquals(1f, col1, 1e-6f);
        }
    }
```

$\cos\frac{\Delta}{2} \ge 0.99$ gives $\Delta \le 2\arccos 0.99 = 0.2831$ rad, about 16.2°. A character
turning at the default 10 rad/s turns 19.1° per tick, a little more, so at full turning speed the
midpoint frame shrinks by 1.4% (§14.3).
</details>

**17. Flattening versus the hierarchy** (§13.4, §27). Copy `World.compose` (it is private) into a test
helper. Put a parent and a child into a `TransformStore` with `add(child, parent)`, compute the child's
world transform with `update(null)`, and compare it with `composeTRS` of the flattened TRS. Show that
they agree for a uniform parent scale and disagree for a parent scale of $(2, 1, 1)$ with a rotated
child.

<details><summary>Hint</summary>

`TransformStoreTest` shows how to create an `EntityPool` and entities. Read the child's world block
with `t.world()` at `t.indexOf(child) * 12`. For the failing case, the dot product of the first two
columns of the hierarchy's result shows the shear directly.
</details>
<details><summary>Solution</summary>

```java
    /** World.compose's arithmetic: child TRS c under the composed parent TRS p (10 floats each). */
    private static void compose(float[] p, float[] c, float[] out) {
        float px = p[3], py = p[4], pz = p[5], pw = p[6];
        float vx = c[0] * p[7];
        float vy = c[1] * p[8];
        float vz = c[2] * p[9];
        float cx = 2 * (py * vz - pz * vy);
        float cy = 2 * (pz * vx - px * vz);
        float cz = 2 * (px * vy - py * vx);
        out[0] = p[0] + vx + pw * cx + (py * cz - pz * cy);
        out[1] = p[1] + vy + pw * cy + (pz * cx - px * cz);
        out[2] = p[2] + vz + pw * cz + (px * cy - py * cx);
        float qx = c[3], qy = c[4], qz = c[5], qw = c[6];
        float rx = pw * qx + px * qw + py * qz - pz * qy;
        float ry = pw * qy - px * qz + py * qw + pz * qx;
        float rz = pw * qz + px * qy - py * qx + pz * qw;
        float rw = pw * qw - px * qx - py * qy - pz * qz;
        float len = (float) Math.sqrt(rx * rx + ry * ry + rz * rz + rw * rw);
        out[3] = rx / len;
        out[4] = ry / len;
        out[5] = rz / len;
        out[6] = rw / len;
        out[7] = p[7] * c[7];
        out[8] = p[8] * c[8];
        out[9] = p[9] * c[9];
    }

    /** {max entry difference, dot of columns 0 and 1} for the parent scale (sx, sy, sz). */
    private static float[] compare(float sx, float sy, float sz) {
        float h = (float) (Math.PI / 4); // parent: yaw 90°, half angle 45°
        float k = (float) (Math.PI / 8); // child: 45° about Z, half angle 22.5°
        float[] parent = {0, 1, 0, 0, (float) Math.sin(h), 0, (float) Math.cos(h), sx, sy, sz};
        float[] child = {1, 0, 0, 0, 0, (float) Math.sin(k), (float) Math.cos(k), 1, 1, 1};
        EntityPool pool = new EntityPool();
        TransformStore t = new TransformStore(pool, 4);
        int p = pool.create();
        int c = pool.create();
        t.add(p);
        t.add(c, p);
        for (int[] e : new int[][] {{p, 0}, {c, 1}}) {
            float[] v = e[1] == 0 ? parent : child;
            t.setPosition(e[0], v[0], v[1], v[2]);
            t.setRotation(e[0], v[3], v[4], v[5], v[6]);
            t.setScale(e[0], v[7], v[8], v[9]);
        }
        t.update(null);
        float[] w = Arrays.copyOfRange(t.world(), t.indexOf(c) * 12, t.indexOf(c) * 12 + 12);
        float[] flat = new float[10];
        compose(parent, child, flat);
        float[] m = new float[12];
        AffineOps.composeTRS(
                flat[0], flat[1], flat[2], flat[3], flat[4], flat[5], flat[6], flat[7], flat[8],
                flat[9], m, 0);
        float diff = 0;
        for (int i = 0; i < 12; i++) {
            diff = Math.max(diff, Math.abs(w[i] - m[i]));
        }
        float dot = w[0] * w[1] + w[4] * w[5] + w[8] * w[9]; // columns 0 and 1
        return new float[] {diff, dot};
    }

    @Test
    void flatteningMatchesTheHierarchyOnlyWithoutShear() {
        float[] uniform = compare(2, 2, 2);
        assertTrue(uniform[0] < 1e-5f);
        float[] stretched = compare(2, 1, 1);
        assertTrue(stretched[0] > 0.5f);
        assertEquals(-1.5f, stretched[1], 1e-5f);
    }
```

With the uniform scale the two agree to $3.6 \times 10^{-7}$ (rounding), and the child sits at $(0, 1,
-2)$ as in §13.4's worked example. With $(2, 1, 1)$ the largest difference is 0.7071, and the dot
product of the hierarchy's first two columns is $-1.5$: §13.4's shear, unchanged by the parent's yaw
because rotations keep dot products. The flattened TRS cannot hold it.
</details>

**18. The stepper by hand** (§14.2, §28). A 30 Hz `FixedStepper` with `maxTicksPerFrame` 4 receives
`advance` calls at $t = 0$, then every $16{,}666{,}667$ ns (60 Hz) three times, then after a one-second
stall. Give the ticks and `alpha` after each call.

<details><summary>Hint</summary>

The first call only records the time. The tick is $33{,}333{,}333$ ns; the cap is four ticks'
worth.
</details>
<details><summary>Solution</summary>

| Call | Accumulator before | Ticks | Accumulator after | `alpha` |
|---|---|---|---|---|
| $t = 0$ | (first call) | 0 | 0 | 0 |
| +16,666,667 | 16,666,667 | 0 | 16,666,667 | 0.5 |
| +16,666,667 | 33,333,334 | 1 | 1 | $3 \times 10^{-8}$ |
| +16,666,667 | 16,666,668 | 0 | 16,666,668 | 0.5 |
| +1,000,000,000 | capped at 133,333,332 | 4 | 0 | 0 |

At 60 Hz every other frame runs a tick, and the frames in between show the halfway state. The stall
would have been 30 ticks; the cap runs 4 and drops the rest. (A `float` prints the halves as
0.50000006: $16{,}666{,}667 / 33{,}333{,}333$ rounded to `float`.)
</details>

## 34. Glossary

- **Accumulator:** real time that has passed but not yet been simulated; `FixedStepper` runs a tick
  for every whole tick in it (§14.2).
- **Alpha ($\alpha$):** the fraction of a tick left in the accumulator, used to interpolate between the
  last two ticks (§14.2).
- **Angle-sum and difference identities:** $\cos(\alpha \pm \beta)$ and $\sin(\alpha \pm \beta)$ in
  terms of the two angles, from composing rotations (§3.3).
- **atan2:** the angle of a direction $(x, y)$, using both signs to find the quadrant; the engine's yaw
  uses `atan2(dx, dz)` (§2.5, §5.3).
- **Axis–angle:** a rotation described as a unit axis and an angle (§7.1).
- **Complex number, conjugate:** $a + bi$ with $i^2 = -1$; $a - bi$. A unit complex number rotates the
  plane by multiplication (§4).
- **Composition order:** `a.mul(b)` applies `b` first, then `a`, as with matrices (§8.8).
- **Conjugate, inverse (quaternion):** $q^* = (-\mathbf{v}, w)$; $q^{-1} = q^*/\lVert q\rVert^2$, which is
  $q^*$ for a unit quaternion (§8.4).
- **Counting sort:** sorting small whole-number keys by counting each key, turning counts into start
  positions, and scattering; $O(n)$ (§13.3, §25).
- **Depth, level:** a transform's distance from its root (0 for a root); all transforms of one depth
  (§13.3).
- **Dirty flag:** the per-entity bit saying "my local transform changed since the last update" (§24).
- **Double-angle identities:** $\cos\theta = \cos^2\frac{\theta}{2} - \sin^2\frac{\theta}{2}$ and $\sin\theta =
  2\sin\frac{\theta}{2}\cos\frac{\theta}{2}$ (§3.4).
- **Double cover:** every rotation has exactly two unit quaternions, $q$ and $-q$ (§8.7).
- **Euler angles; yaw, pitch, roll:** an orientation as three rotations about coordinate axes in a
  fixed order; about the vertical, the sideways and the forward axis (§6.1).
- **Flattening:** composing a scene's node tree into one TRS per mesh at spawn time (§27).
- **Gimbal lock:** the loss of one direction of turning when two Euler rotations line up (§6.3).
- **Handedness:** the sign $h$ in $\mathbf{b} = h\,(\mathbf{n} \times \mathbf{t})$; $-1$ for mirrored
  texture coordinates (§12.2).
- **Hemisphere (quaternions):** the half of the 4D sphere on one side of a quaternion; keeping
  neighbours in one hemisphere makes lerps take the short way (§11.4, §29.3).
- **Local, world transform:** relative to the parent; relative to the world, $W(e) =
  W(\operatorname{parent}(e))\,L(e)$ (§13.1).
- **Memoize:** remember a computed result so it is computed once (§25).
- **Nlerp:** lerp the four components of two quaternions and normalize; same path as slerp, uneven
  speed (§11.3).
- **Norm, unit quaternion:** $\sqrt{x^2 + y^2 + z^2 + w^2}$; a quaternion of norm 1 (§8.4).
- **Normal map, tangent space:** a texture of small normal tilts; the frame (tangent, bitangent,
  normal) they are stored in (§12.1).
- **Normalized attribute:** a vertex input whose integers the vertex fetch converts to floats in
  $[-1, 1]$ or $[0, 1]$ (§12.3).
- **Orthonormal:** perpendicular unit vectors; a rotation's columns are orthonormal (§3.5).
- **Polar form:** a point written as $(r\cos\varphi, r\sin\varphi)$ (§2.3).
- **Pure quaternion:** one with $w = 0$, used to hold a 3D vector (§8.3).
- **QTangent:** a vertex's tangent frame stored as one unit quaternion, its sign carrying the
  handedness (§12.2, D27).
- **Quaternion; scalar part, vector part:** $w + xi + yj + zk$; $w$; $(x, y, z)$. Stored as `(x, y, z,
  w)` (§8.1, §8.3).
- **Radian:** the angle whose arc on a unit circle has length 1; a whole turn is $2\pi$ (§2.2).
- **Rasterizer:** the fixed GPU hardware between the vertex and fragment stages that finds covered
  pixels and interpolates vertex outputs (§12.3).
- **Right-hand rule:** thumb along the axis, fingers curl the positive way (§5.1).
- **Rodrigues' formula:** the rotation of a vector by an angle about a unit axis (§7.2).
- **Rotation matrix:** a matrix with orthonormal columns and determinant 1 (§3, §5.2).
- **Sandwich:** $q\,\mathbf{p}\,q^*$, how a quaternion rotates a vector (§8.5).
- **Shear:** a linear map whose axes stop being perpendicular; no TRS can represent it (§13.4).
- **Sine, cosine:** the $y$ and $x$ coordinates of the unit arrow turned by an angle (§2.3).
- **Slerp:** interpolation along the arc of the 4D unit sphere at constant speed (§11.2).
- **Spiral of death:** catch-up ticks taking so long that the next frame needs even more (§14.2).
- **Swizzle:** GLSL's `q.xyz`, `q.w`: picking components by name (§21).
- **Tick:** one fixed 1/30 s simulation step (§14.1).
- **Trace:** the sum of a matrix's diagonal; $4w^2 - 1$ for a rotation (§10.2).
- **Traversal order:** the `int` array listing the transform slots sorted by depth (§13.3).
- **Triple product expansion ("BAC minus CAB"):** $\mathbf{a} \times (\mathbf{b} \times \mathbf{c}) =
  \mathbf{b}(\mathbf{a}\cdot\mathbf{c}) - \mathbf{c}(\mathbf{a}\cdot\mathbf{b})$ (§8.5).
- **Vertex array object (VAO), vertex fetch:** GL's description of how to read vertex streams; the
  GPU step that reads and converts them (§12.3).
- **Word tearing:** a write to one variable disturbing a neighbour; Java forbids it between array
  elements, which makes `byte` flags safe in parallel (§26).
- **Wrap (an angle):** bring it into one turn around zero, to get the short way round (§2.6).
- **Yaw:** a turn about the vertical $+Y$ axis; $+Z$ turns towards $+X$ (§5.3).

## 35. Further reading

- Fletcher Dunn and Ian Parberry, *3D Math Primer for Graphics and Game Development*: the chapters on
  orientation compare matrices, Euler angles and quaternions with the same care as this chapter.
- Eric Lengyel, *Foundations of Game Engine Development, Volume 1: Mathematics*: transforms,
  rotations and quaternions, with derivations.
- Tomas Akenine-Möller et al., *Real-Time Rendering* (4th edition), chapter 4 "Transforms":
  quaternions, interpolation and the matrix–quaternion conversions.
- Ken Shoemake, "Animating Rotation with Quaternion Curves", SIGGRAPH 1985: the paper that introduced
  slerp to computer graphics.
- Jonathan Blow, "Understanding Slerp, Then Not Using It" (2004): the case for nlerp.
- Ivo Zoltan Frey, "Spherical Skinning with Dual-Quaternions and QTangents", SIGGRAPH 2011 talk
  (Crytek): the origin of the QTangent encoding, including the sign trick and the bias.
- Jason Gregory, *Game Engine Architecture*: transform hierarchies, scene graphs and the game loop.
- Glenn Fiedler, "Fix Your Timestep!": the fixed-step accumulator and interpolation that
  `FixedStepper` implements.
- The glTF 2.0 specification (node transforms, TRS and the tangent convention):
  https://registry.khronos.org/glTF/specs/2.0/glTF-2.0.html
- The Java Language Specification, §17.6 "Word Tearing", for why separate `byte` elements are safe to
  write from different threads.

---

*Code this chapter builds or quotes: `math.MathF`, `Quatf`, `MQuatf`, `QuatOps`, `AffineOps`
(`composeTRS`, `mul`, `lerp`), `Mat4f` (`trs`, `rotation`), `IsoCamera.update`; `asset.format.MeshEncoding`
and `MeshWriter`; `cooker.gltf.GltfImporter.decompose` and `cooker.mesh.MeshProcessor.mikk`;
`render.mesh.MeshRegistry` (the vertex format), `RenderPacket`, `draw.DrawBuilder.writeRecords`, the
shaders `engine/vertex.glsl`, `engine/instance.glsl`, `pbr/pbr.vert` and `pbr/pbr.frag`;
`scene.TransformStore`; `app.world.World` (`create`, `setYaw`, `instantiate`, `instantiateNode`,
`compose`, `beginTick`, `extract`), `app.world.AgentSync`, `app.JeddyApp`'s game loop;
`core.time.FixedStepper`; `animation.Clip.bake` and `Animations`; `editor.GizmoMath`. Tests:
`MatrixAndQuatTest`, `FormatsTest`, `GltfImporterTest`, `TransformStoreTest`, `RenderPacketTest`.
Benchmarks: `TransformBench`, `MathBench`. Decisions: D4, D13, D27, D28, D30, D33, D39, D49, D79,
D81 in [DESIGN §17](../DESIGN.md), and DESIGN §4.2, §4.5, §5, §8.1, §13.1, §13.2, §13.4, §15.
Related: chapter [02](02-math-foundations.md) (vectors, matrices, TRS), [04](04-cameras-and-projection.md)
(the camera), [07](07-lighting-and-shadows.md) (normal mapping), [09](09-animation-and-skinning.md)
(animation and skinning), [10](10-assets.md) (the cooker and the mesh format), [14](14-editor.md)
(gizmos and the Inspector); lessons [L00](lessons/00-foundations.md),
[L01](lessons/01-buffers-in-flight.md) (instance records in the frame ring) and
[L03](lessons/03-data-layout.md) (handles, sparse sets, struct of arrays).*

[← 02 Math foundations](02-math-foundations.md) · [Index](README.md) · [04 Cameras and projection →](04-cameras-and-projection.md)
