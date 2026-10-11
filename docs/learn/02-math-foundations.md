# 02. Math foundations: vectors, matrices and affine transforms

Every frame, Jeddy places thousands of things in a 3D world: characters walk, a fireball flies, the
camera orbits the party, and every mesh has to land on the right pixels. All of that is linear
algebra on a few small objects: 3D vectors, 3×3 and 4×4 matrices, and a compact 3×4 "affine"
transform. This chapter builds that math from first principles and maps each idea to the code that
implements it.

You will learn:

- the difference between points and vectors, and Jeddy's coordinate conventions (right-handed, Y up);
- the dot and cross products, what they measure, and where the engine uses them;
- matrices as linear maps and as changes of basis, and why the order of multiplication matters;
- homogeneous coordinates, and how a 4×4 matrix encodes a translation;
- how Jeddy stores matrices in memory: column-major 4×4 for cameras, row-major 3×4 for object
  transforms, and why it uses both;
- composing and inverting affine transforms, and why normals need the inverse transpose;
- determinants and mirroring, compact vertex encodings (half floats, snorm), and floating-point
  pitfalls.

Rotations get their own chapter ([03 Rotations, quaternions and transform hierarchies](03-rotations-and-transforms.md)).
Here a rotation is just "a 3×3 matrix with special properties". Projection matrices and the camera
are in [04 Cameras and projection](04-cameras-and-projection.md).

**Where it lives.** The `math` module (`modules/math`, Java module and package `dev.jeddy.math`) has
no dependencies at all: its `module-info.java` only exports the package. The key classes are
[`Vec3f`](../../modules/math/src/main/java/dev/jeddy/math/Vec3f.java),
[`Vec3Ops`](../../modules/math/src/main/java/dev/jeddy/math/Vec3Ops.java),
[`Mat3f`](../../modules/math/src/main/java/dev/jeddy/math/Mat3f.java),
[`Mat4f`](../../modules/math/src/main/java/dev/jeddy/math/Mat4f.java),
[`MMat4f`](../../modules/math/src/main/java/dev/jeddy/math/MMat4f.java),
[`Mat4Ops`](../../modules/math/src/main/java/dev/jeddy/math/Mat4Ops.java),
[`AffineOps`](../../modules/math/src/main/java/dev/jeddy/math/AffineOps.java),
[`MathF`](../../modules/math/src/main/java/dev/jeddy/math/MathF.java),
[`Packing`](../../modules/math/src/main/java/dev/jeddy/math/Packing.java) and the geometry records
[`Aabb3f`](../../modules/math/src/main/java/dev/jeddy/math/Aabb3f.java),
[`Spheref`](../../modules/math/src/main/java/dev/jeddy/math/Spheref.java),
[`Planef`](../../modules/math/src/main/java/dev/jeddy/math/Planef.java),
[`Ray3f`](../../modules/math/src/main/java/dev/jeddy/math/Ray3f.java), plus the intersection tests
in [`Intersect`](../../modules/math/src/main/java/dev/jeddy/math/Intersect.java). The unit tests are
[`MatrixAndQuatTest`](../../modules/math/src/test/java/dev/jeddy/math/MatrixAndQuatTest.java) and
[`GeometryAndUtilTest`](../../modules/math/src/test/java/dev/jeddy/math/GeometryAndUtilTest.java).
On the GPU side the same math appears in
[`instance.glsl`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/engine/instance.glsl).
The design is in [DESIGN §8](../DESIGN.md).

---

## The three layers of the math module

Before the math itself, look at how the code is organized, because it explains why you will see the
same formula written three times. DESIGN §8.1 splits the module into three layers.

| Layer | Types | Use it for | Allocation |
|---|---|---|---|
| 1. Value types | records `Vec2f`, `Vec3f`, `Vec4f`, `Quatf`, `Mat3f`, `Mat4f`, `Aabb3f`, `Ray3f`, `Planef`, `Spheref`, `Capsulef` | APIs, configuration, setup code, tests | Every operation returns a new object |
| 2. Mutable scratch | `MVec3f`, `MQuatf`, `MMat4f` | Per-frame code that works on one thing at a time (cameras, controllers) | None: methods modify `this` and return it |
| 3. Bulk kernels | `Vec3Ops`, `QuatOps`, `Mat4Ops`, `AffineOps`, `CullOps` | Hot loops over many entities | None: static methods over `float[]` plus offsets |

Layer 1 looks like ordinary Java. From
[`Vec3f.java`](../../modules/math/src/main/java/dev/jeddy/math/Vec3f.java):

```java
public record Vec3f(float x, float y, float z) {
    // ...
    /** Returns the cross product {@code this × o}. */
    public Vec3f cross(Vec3f o) {
        return new Vec3f(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
    }
```

Records are immutable and have no identity-sensitive operations, so when JEP 401 (value classes)
becomes final they turn into `value record` with a one-word change (decision D4). A value object has
no identity and can be stored flat, without a heap allocation. Until then each `new Vec3f(...)` is a
real allocation unless the JIT's escape analysis removes it, and the engine's rule (DESIGN §4.2) is
not to rely on escape analysis in hot paths.

Layer 2 is the answer for per-frame code that thinks in single objects. From
[`MVec3f.java`](../../modules/math/src/main/java/dev/jeddy/math/MVec3f.java):

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

A system keeps these as fields (`private final MVec3f tmp = new MVec3f()`) and reuses them every
frame. `IsoCamera` does exactly that with its `eye` field.

Layer 3 is where the engine spends its time. A kernel takes **struct-of-arrays** (SoA) data: one
`float[]` per component instead of one object per entity. From
[`Vec3Ops.java`](../../modules/math/src/main/java/dev/jeddy/math/Vec3Ops.java):

```java
    /** {@code p[i] += v[i] * dt} for {@code [start, end)}: integrate positions by velocities. */
    public static void addScaled(
            float[] px,
            float[] py,
            float[] pz,
            float[] vx,
            float[] vy,
            float[] vz,
            float dt,
            int start,
            int end) {
        for (int i = start; i < end; i++) {
            px[i] += vx[i] * dt;
            py[i] += vy[i] * dt;
            pz[i] += vz[i] * dt;
        }
    }
```

A simple counted loop over primitive arrays lets HotSpot's C2 compiler eliminate bounds checks and
use SIMD instructions (auto-vectorization, DESIGN §4.1). The `[start, end)` range is there so the
job system can split the loop across worker threads.

The math is identical in all three layers. Once you understand `Vec3f.cross`, you can read
`MVec3f.cross` and the cross products written out inside `QuatOps.rotate` or `AffineOps.invert`.

> DESIGN §8.1 sketches two kernel signatures from the planning stage. The real ones are
> `AffineOps.composeTRS(px, py, pz, qx, qy, qz, qw, sx, sy, sz, int start, int end, float[] out)`
> and `CullOps.spheres(planes, cx, cy, cz, r, start, end, out, outOffset)`.

---

## Conventions at a glance

Most bugs in 3D code are convention bugs: a sign, an axis, or an index in the wrong place. Keep this
table close. Each entry is explained later in this chapter or in chapter 03.

| Topic | Jeddy's choice | Where it is stated |
|---|---|---|
| Handedness | Right-handed: $\mathbf{x} \times \mathbf{y} = \mathbf{z}$ | `Vec3f` Javadoc, DESIGN §8.2 |
| Up axis | +Y; the ground is the XZ plane | `Vec3f.UNIT_Y`, `Planef.GROUND`, `Vec3f.flat()` |
| Camera forward (view space) | −Z | `Mat4Ops.lookAt`, `Quatf.forward()` |
| Model forward | +Z (glTF) | `AgentSync` Javadoc: "models face +Z" |
| Angles | Radians | `MathF.toRadians` for conversions |
| Scalar type | `float` | DESIGN §8.2: "CRPG maps are small" |
| Vectors in products | Column vectors: $\mathbf{v}' = M\mathbf{v}$ | `Mat4f` Javadoc: "Vectors are columns" |
| `Mat4` storage | Column-major, 16 floats, element (row r, column c) at index `c * 4 + r` | `Mat4Ops` Javadoc |
| Affine storage | Row-major 3×4, 12 floats: `[r00 r01 r02 tx, r10 r11 r12 ty, r20 r21 r22 tz]` | `AffineOps` Javadoc, D21 |
| Product order | `a.mul(b)` applies `b` first | `Mat4f`, `AffineOps.mul`, `Quatf` |
| Quaternions | `(x, y, z, w)`, w is the scalar part, Hamilton product | `Quatf` Javadoc |
| Depth | Reversed-Z, near plane at depth 1 | `Mat4f` Javadoc, chapter 04 |

---

## Points, vectors and coordinate systems

### Points and vectors are different things

A **point** is a location: "the torch is at (4, 2, −3)". A **vector** is a displacement: "move 1 m
east and 2 m up". Both are written as three numbers, and both use `Vec3f` in Jeddy, but they behave
differently:

- point − point = vector (the displacement from one to the other);
- point + vector = point (move the point);
- vector + vector = vector, and a vector can be scaled;
- point + point has no meaning on its own. Averages such as $\tfrac{1}{2}(\mathbf{p} + \mathbf{q})$
  are fine, because the weights sum to 1.

The difference becomes visible as soon as you move the coordinate system. If you shift the origin
5 m east, every point's coordinates change, but a displacement such as "1 m east" does not. Later in
this chapter this becomes the difference between `transformPoint` (translation applies) and
`transformDirection` (translation does not).

### The axes

A coordinate system is an origin plus three axis directions. Jeddy uses a **right-handed, Y-up**
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

"Right-handed" is a statement about the cross product: $\mathbf{x} \times \mathbf{y} = \mathbf{z}$.
You can check it in the code with `Vec3f.UNIT_X.cross(Vec3f.UNIT_Y)`, which returns `(0, 0, 1)`.
In a left-handed system (traditional Direct3D, Unity) the same three numbers would describe a
mirrored world. Mixing the two is a classic source of flipped models and inverted rotations.

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

The ground is the XZ plane. Several helpers encode that: `Vec3f.flat()` sets y to 0 ("projected
onto the ground plane"), `Planef.GROUND` is the plane y = 0 facing up, and the 2D tests in
`Intersect` work in `(x, z)`.

### Which way is forward

There are two forwards in Jeddy and both are standard:

- The **camera** looks down its local −Z axis. That is the OpenGL convention; `Mat4Ops.lookAt`
  builds a view matrix in which the target ends up on the negative Z axis, and `Quatf.forward()`
  returns the rotated −Z axis.
- **Models** face +Z, because glTF assets are authored that way. `AgentSync` turns a character
  towards its motion with `Math.atan2(dx, dz)`, which is 0 when the motion is along +Z. Chapter 03
  derives that formula.

---

## Vector operations

### Length and normalization

The length (or magnitude) of $\mathbf{v} = (x, y, z)$ comes from Pythagoras applied twice:

```math
\lVert \mathbf{v} \rVert = \sqrt{x^2 + y^2 + z^2}
```

A **unit vector** has length 1 and describes a pure direction. Normalizing divides by the length:
$\hat{\mathbf{v}} = \mathbf{v} / \lVert \mathbf{v} \rVert$. A zero vector has no direction, so the
code has to decide what to return. `Vec3f.normalize` returns `ZERO`:

```java
    /** Returns this vector at unit length, or zero if it has no length. */
    public Vec3f normalize() {
        float l = length();
        return l > 0 ? new Vec3f(x / l, y / l, z / l) : ZERO;
    }
```

The batch version in `Vec3Ops.normalize` computes `1 / Math.sqrt(l2)` once and multiplies three
times, because a multiply is cheaper than a divide.

**Prefer squared lengths for comparisons.** `sqrt` is monotonic, so $`a < b \iff a^2 < b^2`$ for
non-negative values. "Is the point inside the sphere?" never needs a square root:

```java
    /** Returns {@code true} if {@code p} is inside or on the sphere. */
    public boolean contains(Vec3f p) {
        return center.distanceSquared(p) <= radius * radius;
    }
```

That is [`Spheref.contains`](../../modules/math/src/main/java/dev/jeddy/math/Spheref.java).
`Vec3Ops.distanceSquared` exists for the same reason in batch form.

### The dot product

The dot product of two vectors is a single number:

```math
\mathbf{a} \cdot \mathbf{b} = a_x b_x + a_y b_y + a_z b_z
```

It also has a geometric meaning:

```math
\mathbf{a} \cdot \mathbf{b} = \lVert \mathbf{a} \rVert \, \lVert \mathbf{b} \rVert \cos\theta
```

where $\theta$ is the angle between the vectors. Why are the two formulas the same? Take the
triangle with sides $\mathbf{a}$, $\mathbf{b}$ and $\mathbf{a} - \mathbf{b}$. The law of cosines says

```math
\lVert \mathbf{a} - \mathbf{b} \rVert^2 = \lVert \mathbf{a} \rVert^2 + \lVert \mathbf{b} \rVert^2 - 2 \lVert \mathbf{a} \rVert \lVert \mathbf{b} \rVert \cos\theta
```

Expand the left side component by component:
$(a_x - b_x)^2 + \dots = \lVert \mathbf{a} \rVert^2 + \lVert \mathbf{b} \rVert^2 - 2(a_x b_x + a_y b_y + a_z b_z)$.
Compare both sides and the component formula equals $\lVert \mathbf{a} \rVert \lVert \mathbf{b} \rVert \cos\theta$.

**Numeric example.** $\mathbf{a} = (3, 0, 4)$, $\mathbf{b} = (1, 0, 0)$. Then
$\mathbf{a} \cdot \mathbf{b} = 3$, $\lVert \mathbf{a} \rVert = 5$, so $\cos\theta = 3/5$ and
$\theta \approx 53.13°$.

What the dot product gives you:

1. **Projection.** If $\hat{\mathbf{n}}$ is a unit vector, $\mathbf{a} \cdot \hat{\mathbf{n}}$ is
   the signed length of $\mathbf{a}$ along $\hat{\mathbf{n}}$ (in the example, 3: the "shadow" of
   $\mathbf{a}$ on the x axis). The vector projection is $(\mathbf{a} \cdot \hat{\mathbf{n}})\hat{\mathbf{n}}$,
   and $\mathbf{a} - (\mathbf{a} \cdot \hat{\mathbf{n}})\hat{\mathbf{n}}$ is the part of $\mathbf{a}$
   perpendicular to $\hat{\mathbf{n}}$.
2. **Angle and alignment.** For unit vectors the dot product is the cosine of the angle: 1 means the
   same direction, 0 perpendicular, −1 opposite.
3. **Side tests.** The sign tells you whether two vectors point into the same half-space. That is
   how a plane decides "in front" and "behind".
4. **Lighting.** The light a surface receives is proportional to $\cos\theta$ between its normal
   $\hat{\mathbf{n}}$ and the direction to the light $\hat{\mathbf{l}}$ (Lambert's cosine law),
   which is just $\hat{\mathbf{n}} \cdot \hat{\mathbf{l}}$ clamped to zero.

Each of these appears in Jeddy.

*Projection onto a segment*, in `Intersect.closestPointOnSegment`. The parameter of the closest
point on segment $\mathbf{a}\mathbf{b}$ to $\mathbf{p}$ is the projection of $\mathbf{p} - \mathbf{a}$
onto $\mathbf{b} - \mathbf{a}$, divided by the segment's squared length so that 0 is at a and 1 at b:

```java
        float len2 = abx * abx + aby * aby + abz * abz;
        if (len2 == 0) {
            return 0;
        }
        float t = ((px - ax) * abx + (py - ay) * aby + (pz - az) * abz) / len2;
        return Math.max(0, Math.min(1, t));
```

*Removing a component* (Gram–Schmidt orthogonalization). A tangent vector must be perpendicular to
the normal. Both the asset encoder and the fragment shader fix that by subtracting the projection.
From [`pbr.frag`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/pbr/pbr.frag):

```glsl
    vec3 t = normalize(vTangent.xyz - n * dot(n, vTangent.xyz));
    vec3 b = cross(n, t) * vTangent.w;
```

*Projection plus Pythagoras*, in the cone test for cone spells (`Intersect.coneSphere`):

```java
        float lenSq = vx * vx + vy * vy + vz * vz;
        float along = vx * axisX + vy * axisY + vz * axisZ;
        float perp = (float) Math.sqrt(Math.max(0, lenSq - along * along));
```

`along` is the distance of the target along the cone's axis, and `perp` its distance from the axis.

*Lighting*, in [`brdf.glsl`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/engine/brdf.glsl):

```glsl
    float NoL = clamp(dot(s.normal, l), 0.0, 1.0);
    if (NoL <= 0.0) {
        return vec3(0.0);
    }
```

Chapter [07 Lighting and shadows](07-lighting-and-shadows.md) explains the rest of that function.

### The cross product

The cross product of two 3D vectors is a vector:

```math
\mathbf{a} \times \mathbf{b} = (a_y b_z - a_z b_y,\; a_z b_x - a_x b_z,\; a_x b_y - a_y b_x)
```

The pattern is easy to rebuild: for the x component use the other two axes in cyclic order (y, z),
for y use (z, x), for z use (x, y). Compare with `Vec3f.cross` above: it is exactly this.

Its properties:

- $\mathbf{a} \times \mathbf{b}$ is **perpendicular** to both $\mathbf{a}$ and $\mathbf{b}$. (Check:
  $\mathbf{a} \cdot (\mathbf{a} \times \mathbf{b}) = a_x(a_y b_z - a_z b_y) + a_y(a_z b_x - a_x b_z) + a_z(a_x b_y - a_y b_x) = 0$,
  every term cancels.)
- Its **length** is $\lVert \mathbf{a} \rVert \lVert \mathbf{b} \rVert \sin\theta$, which is the area
  of the parallelogram spanned by $\mathbf{a}$ and $\mathbf{b}$. Half of it is the area of the
  triangle.
- Its **direction** follows the right-hand rule: fingers along $\mathbf{a}$, curl towards
  $\mathbf{b}$, the thumb gives $\mathbf{a} \times \mathbf{b}$. In a right-handed system,
  $\mathbf{x} \times \mathbf{y} = \mathbf{z}$, $\mathbf{y} \times \mathbf{z} = \mathbf{x}$,
  $\mathbf{z} \times \mathbf{x} = \mathbf{y}$.
- It is **anti-commutative**: $\mathbf{b} \times \mathbf{a} = -(\mathbf{a} \times \mathbf{b})$. Swap
  the operands and the result flips. Many sign bugs are an accidental swap.
- Parallel vectors give the zero vector.

**Numeric example.** $`(2, 0, 0) \times (0, 0, -3) = (0 \cdot (-3) - 0 \cdot 0,\; 0 \cdot 0 - 2 \cdot (-3),\; 2 \cdot 0 - 0 \cdot 0) = (0, 6, 0)`$.
The vectors are perpendicular with lengths 2 and 3, so the area is 6. The result points up: x crossed
with −z is +y, because z × x = y.

Where Jeddy uses it:

*Triangle normals.* A plane through three points faces the side from which the points run
counter-clockwise ([`Planef.fromPoints`](../../modules/math/src/main/java/dev/jeddy/math/Planef.java)):

```java
    /** Returns the plane through three points, facing towards the side where a→b→c is CCW. */
    public static Planef fromPoints(Vec3f a, Vec3f b, Vec3f c) {
        return fromPointNormal(a, b.sub(a).cross(c.sub(a)));
    }
```

With a = (0, 0, 0), b = (0, 0, 1), c = (1, 0, 0) this gives the normal (0, 1, 0), up: seen from
above, origin → +Z (down the screen) → +X (right) is a counter-clockwise turn.

*Building an orthonormal basis.* Given a forward direction and a rough "up", two cross products give
three perpendicular axes. `Mat4f.lookAt` starts like this:

```java
        Vec3f f = target.sub(eye).normalize();
        Vec3f r = f.cross(up).normalize();
        Vec3f u = r.cross(f);
```

`r` (right) is perpendicular to forward and up; `u` (the true up) is perpendicular to right and
forward. If `up` were parallel to `f` the first cross product would be zero; `Quatf.lookRotation`
handles that case by trying another axis.

*Volumes and mirroring.* The scalar triple product $\mathbf{a} \cdot (\mathbf{b} \times \mathbf{c})$
is the signed volume of the box spanned by three vectors. It is the determinant, and its sign tells
whether a transform mirrors. `instance.glsl` uses it (see "Determinants and mirroring" below).

### Linear interpolation

Moving a fraction $t$ of the way from $a$ to $b$ is **lerp**:

```math
\operatorname{lerp}(a, b, t) = a + (b - a)\,t = (1 - t)\,a + t\,b
```

At $t = 0$ you get $a$, at $t = 1$ you get $b$. It works the same for numbers, points, colors and
(element-wise) matrices. `MathF.lerp`, `Vec3f.lerp`, `MVec3f.lerp` and `Vec3Ops.lerp` all use the
first form. [`MathF`](../../modules/math/src/main/java/dev/jeddy/math/MathF.java) adds the
relatives you will meet in shaders and gameplay code:

- `inverseLerp(a, b, v)` answers "how far is v between a and b?";
- `remap(v, inMin, inMax, outMin, outMax)` is `lerp(outMin, outMax, inverseLerp(inMin, inMax, v))`;
- `smoothstep(a, b, v)` is $t^2(3 - 2t)$ on the clamped `inverseLerp`, an S-curve with zero slope at
  both ends.

`MathF.damp` deserves a closer look, because it solves a real problem:

```java
    public static float damp(float current, float target, float lambda, float dt) {
        return lerp(target, current, (float) Math.exp(-lambda * dt));
    }
```

A common pattern is `x = lerp(x, target, 0.1f)` every frame. It moves faster at 144 FPS than at
30 FPS, because it runs more often. Exponential decay fixes that. Ask for the distance to the target
$x^{\ast}$ to shrink at a rate proportional to itself, with decay rate $\lambda$ per second:
$\frac{d}{dt}(x - x^{\ast}) = -\lambda (x - x^{\ast})$. The solution after a step of $\Delta t$ seconds is

```math
x(t + \Delta t) = x^{\ast} + (x(t) - x^{\ast})\,e^{-\lambda \Delta t}
```

which is `lerp(target, current, exp(-lambda * dt))`. Two steps of $\Delta t / 2$ multiply to
$e^{-\lambda \Delta t}$, so the result does not depend on the frame rate. With `damp(0, 10, 5, 1/60f)`
you get 0.7995558; with two steps of 1/120 s you get 0.7995548, the same up to float rounding.

**Try it.** The tests for this section are short. Run
`./gradlew :math:test --tests 'dev.jeddy.math.GeometryAndUtilTest'` and read `raySphereCylinderCapsulePlane`
and `aoeShapes`: most assertions are distances and angles you can check by hand with the formulas of
this section.

---

## Matrices

### A matrix is a linear map

A function $f$ on vectors is **linear** if it respects addition and scaling:
$f(a\mathbf{u} + b\mathbf{v}) = a f(\mathbf{u}) + b f(\mathbf{v})$. Rotations, scales, mirrors and
shears are linear; translation is not (it moves the zero vector).

Write any vector in terms of the basis vectors $`\mathbf{e}_x = (1, 0, 0)`$, $`\mathbf{e}_y`$ and
$`\mathbf{e}_z`$: $`\mathbf{v} = x\,\mathbf{e}_x + y\,\mathbf{e}_y + z\,\mathbf{e}_z`$. By linearity,

```math
f(\mathbf{v}) = x\,f(\mathbf{e}_x) + y\,f(\mathbf{e}_y) + z\,f(\mathbf{e}_z)
```

So a linear map is completely described by **where it sends the three basis vectors**. Write those
three images as the columns of a matrix:

```math
M = \begin{bmatrix} | & | & | \\ f(\mathbf{e}_x) & f(\mathbf{e}_y) & f(\mathbf{e}_z) \\ | & | & | \end{bmatrix}
\qquad
M\mathbf{v} = x \cdot \text{column}_0 + y \cdot \text{column}_1 + z \cdot \text{column}_2
```

This is the most useful sentence in this chapter: **the columns of a matrix are the images of the
axes.** To read a matrix, look at its columns. To build one, decide where the axes should go.

**Numeric example: a rotation of 90° about +Y.** Rotating counter-clockwise when seen from above
(chapter 03 derives the sign) sends +X to −Z, keeps +Y, and sends +Z to +X:

```math
R_y(90°) = \begin{bmatrix} 0 & 0 & 1 \\ 0 & 1 & 0 \\ -1 & 0 & 0 \end{bmatrix}
```

Column 0 is (0, 0, −1), the image of +X. The test `trsComposition` in `MatrixAndQuatTest` relies on
exactly this: "(1,0,0) scaled to (2,0,0), rotated 90° about Y to (0,0,-2)".

A **scale** by $(s_x, s_y, s_z)$ stretches each axis: its columns are $(s_x, 0, 0)$, $(0, s_y, 0)$ and
$(0, 0, s_z)$, a diagonal matrix. That is `Mat4f.scaling`.

### Two ways to read matrix × vector

The same product can be computed two ways:

- **By columns:** $M\mathbf{v}$ is a weighted sum of the columns (as above). Good for intuition.
- **By rows:** component $i$ of $M\mathbf{v}$ is the dot product of row $i$ with $\mathbf{v}$. Good
  for code.

Both give the same numbers. The shader that places every mesh instance uses the row form, because its
transform is stored as three rows. From
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
column 2's top entry". (`Mat4f` names its components `mCR`, column first; more on that trap below.)

### A matrix is also a change of basis

There is a second way to read the same matrix. Suppose a frame (a "local space") has its own axes
$`\mathbf{u}_0, \mathbf{u}_1, \mathbf{u}_2`$, given in world coordinates. A point with local
coordinates $(a, b, c)$ is at $`a\mathbf{u}_0 + b\mathbf{u}_1 + c\mathbf{u}_2`$ in the world. That is
$M(a, b, c)$ for the matrix whose columns are the frame's axes. So:

- a matrix whose **columns** are a frame's axes converts **local → world** coordinates;
- if the axes are orthonormal (unit length, mutually perpendicular), the matrix whose **rows** are
  those axes converts **world → local**: row $i$ dotted with a world vector is its component along
  axis $i$, a projection.

The camera's view matrix is the second case. `Mat4Ops.lookAt` writes the camera's right, up and
backward axes into the rows of the matrix. For an eye at (0, 10, 10) looking at the origin, the
rows of the view matrix are (1, 0, 0), (0, 0.707, −0.707) and (0, 0.707, 0.707): right, up, and the
direction from the target back to the eye. `pbr.frag` reads that third row directly when it needs
the direction towards an orthographic camera:

```glsl
    s.view = jeddyOrthographic() ? normalize(vec3(jView[0][2], jView[1][2], jView[2][2]))
                                 : normalize(jEye.xyz - vWorld);
```

(GLSL indexes matrices `m[column][row]`, so `jView[0][2], jView[1][2], jView[2][2]` are the first
three entries of row 2.)

The vertex tangent frame is the first case: a matrix whose columns are tangent, bitangent and normal
maps tangent-space vectors (such as normal-map samples) into object space. Chapter 03 shows how Jeddy
stores that matrix as a single quaternion.

### Matrix × matrix: composition, and why order matters

Applying $B$ and then $A$ to a vector is $A(B\mathbf{v})$. Because matrix multiplication is
associative, that equals $(AB)\mathbf{v}$: the product $AB$ is the combined transform. The rule to
remember:

> In $AB\mathbf{v}$, the matrix next to the vector acts first. Read right to left.

Each column of $AB$ is $A$ applied to the corresponding column of $B$: "where $B$ sends an axis, then
where $A$ sends that". In components, $`(AB)_{rc} = \sum_k A_{rk} B_{kc}`$.

Matrix multiplication is **not commutative**. Rotating and translating in different orders gives
different results. Take $T$ = translate by (5, 0, 0) and $R = R_y(90°)$, and apply them to the point
(1, 0, 0):

- $TR$: rotate first, (1, 0, 0) → (0, 0, −1), then translate → **(5, 0, −1)**. The object turns in
  place, then moves.
- $RT$: translate first, (1, 0, 0) → (6, 0, 0), then rotate about the origin → **(0, 0, −6)**. The
  object swings around the origin like a hammer thrower.

Running `Mat4f.translation(5, 0, 0).mul(rotation)` and `rotation.mul(translation)` on `UNIT_X` gives
exactly these two answers (up to rounding). The Javadoc of `Mat4f` states the rule:
"`a.mul(b)` applies `b` first". `AffineOps.mul` says the same, and so does `Quatf.mul` for
quaternions. `IsoCamera.update` uses it to build the view-projection matrix:

```java
        Mat4Ops.mul(projection, 0, view, 0, viewProjection, 0);
```

That is $P \cdot V$: world positions go through the view matrix first, then the projection.

### Column vectors, row vectors, and storage order

You will read code and books that write $\mathbf{v}' = \mathbf{v}M$ instead, with $\mathbf{v}$ as a
row vector. That is the **row-vector convention** (classic Direct3D/HLSL samples). Its matrices are
the transposes of ours, and products read left to right. Both conventions describe the same
geometry. Jeddy uses **column vectors** everywhere: `Mat4f`, `Mat3f`, `AffineOps`, and GLSL
(`jViewProjection * vec4(vWorld, 1.0)` in `pbr.vert`).

Do not confuse that with **storage order**, which is a separate question: in which order the 16 (or
12) numbers sit in memory. Jeddy stores 4×4 matrices column by column and 3×4 transforms row by row,
but both are used with column vectors. "Row-major 3×4" says nothing about row vectors.

---

## Homogeneous coordinates

### Translation does not fit in a 3×3 matrix

A 3×3 matrix always maps the zero vector to zero, so it cannot express "move by (5, 0, 0)". The
general **affine** transform is a linear part plus a translation:

```math
\mathbf{p}' = A\mathbf{p} + \mathbf{t}
```

The trick that makes this a single matrix product is to add a fourth coordinate $w$:

```math
\begin{bmatrix} \mathbf{p}' \\ 1 \end{bmatrix} =
\begin{bmatrix} A & \mathbf{t} \\ 0\;0\;0 & 1 \end{bmatrix}
\begin{bmatrix} \mathbf{p} \\ 1 \end{bmatrix}
=
\begin{bmatrix} A\mathbf{p} + \mathbf{t} \\ 1 \end{bmatrix}
```

The translation sits in the fourth column, which multiplies the $w = 1$ coordinate. This is
`Mat4f.translation`:

```java
    /** Returns a translation matrix. */
    public static Mat4f translation(float x, float y, float z) {
        return new Mat4f(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, x, y, z, 1);
    }
```

The constructor takes the 16 values column by column, so x, y and z land in column 3.

### Points have w = 1, directions have w = 0

What should $w$ be for a direction? Remember that a direction is a difference of two points:
$(\mathbf{p}, 1) - (\mathbf{q}, 1) = (\mathbf{p} - \mathbf{q}, 0)$. With $w = 0$ the translation
column is multiplied by zero, so directions rotate and scale but do not move. That matches the
geometry: "1 m east" is still "1 m east" after you move the whole world.

The engine encodes this as two separate methods instead of asking you for a $w$:

```java
    /** Transforms a point (w = 1) without the perspective divide. */
    public Vec3f transformPoint(Vec3f p) {
        return new Vec3f(
                m00 * p.x() + m10 * p.y() + m20 * p.z() + m30,
                m01 * p.x() + m11 * p.y() + m21 * p.z() + m31,
                m02 * p.x() + m12 * p.y() + m22 * p.z() + m32);
    }
```

The only difference from `transformDirection` (shown earlier) is the `+ m30`, `+ m31`, `+ m32`
translation terms. `AffineOps.transformPoint`/`transformDirection` and the shader pair
`jeddyTransformPoint`/`jeddyTransformDirection` follow the same pattern.

For affine transforms the bottom row is always (0, 0, 0, 1) and $w$ stays 1. Projection matrices
change the bottom row, so $w$ becomes something else and you divide by it afterwards (`Vec4f.perspectiveDivide`,
`Mat4Ops.project`). That is the subject of [04 Cameras and projection](04-cameras-and-projection.md).

---

## Storing matrices in memory

A matrix is a 2D table; memory is a 1D list. Jeddy uses two layouts on purpose (decision D21).

### `Mat4`: column-major, 16 floats

All 4×4 matrices (`Mat4f`, `MMat4f`, `Mat4Ops`, the camera matrices of `IsoCamera`) store
**columns one after another**. Element (row r, column c) is at index `c * 4 + r`, as the `Mat4Ops`
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

For `Mat4f.translation(1, 2, 3)`, `toArray` writes
`[1, 0, 0, 0,  0, 1, 0, 0,  0, 0, 1, 0,  1, 2, 3, 1]`: the translation is at indices 12, 13 and 14.

Why column-major? Because OpenGL and GLSL use it. A `mat4` in a std140 uniform block (such as
`jViewProjection` in `frame.glsl`) is four `vec4` columns, and `glProgramUniformMatrix4fv` is called
with `transpose = false` throughout the renderer (for example in `SceneRenderer`). The floats go to
the GPU exactly as they are, with no conversion.

`Mat4Ops.mul` computes the product one output column at a time:

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
            // ...
        }
    }
```

`b0..b3` is column c of b. Output column c is a times that column: column 0 of a (`a[ao..ao+3]`)
times `b0`, plus column 1 of a (`a[ao+4..]`) times `b1`, and so on. This is "each column of $AB$ is
$A$ applied to a column of $B$", in code. The warning "must not overlap" matters: if `out` were `a`,
the loop would overwrite columns of `a` it still needs. `MMat4f.mul` therefore multiplies into a
private `tmp` array and copies back.

### Affine transforms: row-major 3×4, 12 floats

Object transforms (entity world matrices, instance records, joint matrices) never need the bottom
row of a 4×4: it is always (0, 0, 0, 1). So Jeddy drops it and stores the remaining 3×4 block **row
by row**. From the `AffineOps` Javadoc: "three rows
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
$j$) is at indices $j$, $4 + j$ and $8 + j$. `AffineOps.STRIDE` is 12.

Why rows, and why 12 floats?

1. **Size.** 12 floats instead of 16 is 25% less memory and bandwidth for every transform. The
   transform store, the render packet (`prevWorld`, `currWorld`) and the instance buffer all hold
   thousands of them.
2. **The GPU layout.** Three rows of four floats are exactly three `vec4`s, which pack without
   padding in a std430 storage buffer. Storing the same 3×4 block by columns would give four `vec3`
   columns, and std430 pads each `vec3` to 16 bytes, so it would take 64 bytes instead of 48.
3. **No conversion.** World transforms go into instance records float by float, with no reordering
   (`DrawBuilder` only blends the previous and current tick on the way, chapter 01): 12 floats plus
   four 32-bit values (material, flags, entity, and the camera fade as float bits) make the 64-byte
   record (`DrawBuilder.INSTANCE_BYTES = 64`), which the shader reads as

   ```glsl
   struct JeddyInstance {
       vec4 row0;
       vec4 row1;
       vec4 row2;
       uvec4 data; // x material, y flags, z entity, w camera fade (float bits)
   };
   ```
4. **Cheap transforms.** Transforming a point is three 4-component dot products with $(x, y, z, 1)$,
   which is what `jeddyTransformPoint` does.

### Converting between the two

When something needs a real `mat4` (a uniform, or a multiplication with the camera), `AffineOps.toMat4`
expands a 3×4 into column-major 4×4:

```java
    /** Converts to a column-major 4×4 matrix (for uniforms). */
    public static void toMat4(float[] m, int mo, float[] out, int o) {
        out[o] = m[mo];
        out[o + 1] = m[mo + 4];
        out[o + 2] = m[mo + 8];
        out[o + 3] = 0;
        out[o + 4] = m[mo + 1];
        // ...
        out[o + 12] = m[mo + 3];
        out[o + 13] = m[mo + 7];
        out[o + 14] = m[mo + 11];
        out[o + 15] = 1;
    }
```

Column 0 of the 4×4 (`out[0..3]`) is gathered from indices 0, 4 and 8 of the 3×4: the first entry of
each row. `Mat4Ops.mulAffine` skips the conversion entirely and multiplies a 4×4 by a 3×4 "treated as
a 4×4 with last row (0, 0, 0, 1)".

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

Row-major index `r * 4 + c` on the left, column-major index `c * 4 + r` on the right.

### A naming trap

Three different naming schemes for matrix elements meet in this module. Read signatures carefully.

| Code | Name `mAB` means | Example |
|---|---|---|
| `Mat4f`, `Mat3f` record components | A = **column**, B = row (`mCR`) | `m30` is column 3, row 0: translation x |
| `Quatf.fromMatrix(m00, m01, ...)` parameters | A = **row**, B = column (`mRC`), "given row by row" | `m01` is row 0, column 1 |
| `AffineOps` index | plain index into the row-major 3×4 | `m[mo + 3]` is tx |

The test `quaternionsAgreeWithMatrices` shows the consequence: to pass a `Mat4f` to
`Quatf.fromMatrix` it has to swap the indices, `fromMatrix(r.m00(), r.m10(), r.m20(), r.m01(), ...)`.
The first three arguments are row 0, which in `Mat4f`'s naming is `m00, m10, m20`.

---

## Composing affine transforms

### The product of two 3×4 transforms

Write each affine transform as a 4×4 with the implied bottom row and multiply in block form:

```math
\begin{bmatrix} A & \mathbf{a} \\ \mathbf{0}^T & 1 \end{bmatrix}
\begin{bmatrix} B & \mathbf{b} \\ \mathbf{0}^T & 1 \end{bmatrix}
=
\begin{bmatrix} AB & A\mathbf{b} + \mathbf{a} \\ \mathbf{0}^T & 1 \end{bmatrix}
```

The linear parts multiply, and the translation of the result is "B's translation transformed by A,
plus A's translation". The bottom row stays (0, 0, 0, 1), so the product of two affine transforms is
affine and fits in 12 floats again. [`AffineOps.mul`](../../modules/math/src/main/java/dev/jeddy/math/AffineOps.java)
is this formula, one output row at a time:

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

`a0..a2` is row r of A's linear part and `a3` its translation. Output entry (r, c) is that row dotted
with column c of B (`b[bo + c]`, `b[bo + 4 + c]`, `b[bo + 8 + c]`). The fourth output column is row r
of A dotted with B's translation, plus `a3`: that is $A\mathbf{b} + \mathbf{a}$. Compared with
`Mat4Ops.mul` it does 36 multiplies instead of 64.

### TRS: scale, then rotate, then translate

Almost every object transform in a game is built from a translation $\mathbf{t}$, a rotation $R$ and
a scale $S = \operatorname{diag}(s_x, s_y, s_z)$, combined as

```math
M = T\,R\,S
\qquad\text{so}\qquad
M\mathbf{p} = R(S\mathbf{p}) + \mathbf{t}
```

Read right to left: scale the model in its own space, rotate it, then move it into place. Other
orders are possible but surprising: scaling after rotating would stretch along world axes and shear
a rotated object.

The linear part is $RS$. Multiplying by a diagonal matrix on the right scales **columns**: column $j$
of $RS$ is $s_j$ times column $j$ of $R$. In row-major storage, column 0 lives at indices 0, 4, 8, so
those three entries carry the factor `sx`. That is exactly the pattern in `AffineOps.composeTRS`:

```java
        out[o] = (1 - 2 * (yy + zz)) * sx;
        out[o + 1] = 2 * (xy - wz) * sy;
        out[o + 2] = 2 * (xz + wy) * sz;
        out[o + 3] = px;
        out[o + 4] = 2 * (xy + wz) * sx;
        // ...
```

The expressions in `xx`, `xy`, `wz` and so on are the rotation matrix of a quaternion; chapter 03
derives them. For now note the structure: `* sx` on indices 0, 4, 8, `* sy` on 1, 5, 9, `* sz` on
2, 6, 10, and the translation copied into 3, 7, 11.

**Numeric example** (from the test `trsComposition`): $\mathbf{t} = (10, 0, 0)$, $R = R_y(90°)$,
scale 2. The point (1, 0, 0) is scaled to (2, 0, 0), rotated to (0, 0, −2) and translated to
(10, 0, −2):

```java
        Quatf q = Quatf.fromAxisAngle(Vec3f.UNIT_Y, MathF.HALF_PI);
        Mat4f m = Mat4f.trs(new Vec3f(10, 0, 0), q, new Vec3f(2, 2, 2));
        // (1,0,0) scaled to (2,0,0), rotated 90° about Y to (0,0,-2), translated to (10,0,-2).
        assertTrue(m.transformPoint(Vec3f.UNIT_X).approxEquals(new Vec3f(10, 0, -2), EPS));
        Mat4f composed =
                Mat4f.translation(10, 0, 0).mul(Mat4f.rotation(q)).mul(Mat4f.scaling(2, 2, 2));
        assertTrue(composed.approxEquals(m, EPS));
```

The second assertion checks that `trs` really is translation × rotation × scale, in that order.

### Parent × local

A sword in a hand is placed relative to the hand, the hand relative to the arm, and so on. Each
object stores a **local** transform relative to its parent, and its **world** transform is

```math
W_{\text{child}} = W_{\text{parent}} \cdot L_{\text{child}}
```

(apply the child's local transform first, then wherever the parent is). `AffineOps.concatenate` runs
that over a whole array, as long as parents come before children:

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

The test `hierarchyConcatenation` chains three transforms that each translate by +1 along x and
checks that the third ends up at x = 3. Chapter 03 shows the real hierarchy, `TransformStore`, which
adds dirty tracking and parallelism.

### Transforming boxes

Bounding boxes have to follow their objects for culling. Transforming the 8 corners of a box and
taking min/max works, but there is a cheaper way, **Arvo's method**, used by
`AffineOps.transformAabb`. Output coordinate $r$ of a transformed point is

```math
p'_r = t_r + m_{r0}\,x + m_{r1}\,y + m_{r2}\,z
```

Each term depends on one input coordinate only. Over the box, $x$ ranges over
$`[\text{min}_x, \text{max}_x]`$, so the smallest possible value of $`m_{r0} x`$ is
$`\min(m_{r0}\,\text{min}_x,\; m_{r0}\,\text{max}_x)`$, independent of the other terms. Sum the three
minimums for the new min, the three maximums for the new max:

```java
        for (int r = 0; r < 3; r++) {
            float lo = m[mo + r * 4 + 3];
            float hi = lo;
            float a = m[mo + r * 4] * minX;
            float b = m[mo + r * 4] * maxX;
            lo += Math.min(a, b);
            hi += Math.max(a, b);
            // ... the same for y and z
            out[o + r] = lo;
            out[o + 3 + r] = hi;
        }
```

18 multiplies instead of 8 full point transforms. The result is the tightest axis-aligned box around
the transformed box (not around the object inside it). The test `aabbTransformContainsCorners`
checks that all 8 transformed corners fall inside.

Spheres are simpler: the center transforms as a point, and the radius scales by the largest axis
scale, which is the longest column of the linear part. `World` computes it for render bounds
([`World.java`](../../modules/app/src/main/java/dev/jeddy/app/world/World.java)):

```java
    private static float maxScale(float[] m, int o) {
        float sx = m[o] * m[o] + m[o + 4] * m[o + 4] + m[o + 8] * m[o + 8];
        float sy = m[o + 1] * m[o + 1] + m[o + 5] * m[o + 5] + m[o + 9] * m[o + 9];
        float sz = m[o + 2] * m[o + 2] + m[o + 6] * m[o + 6] + m[o + 10] * m[o + 10];
        return (float) Math.sqrt(Math.max(sx, Math.max(sy, sz)));
    }
```

Indices 0, 4, 8 are column 0 of the row-major layout. For $M = TRS$ its length is $s_x$, because a
rotation does not change lengths.

---

## Inverting transforms

The inverse $M^{-1}$ undoes $M$: $M^{-1}M = I$. Engines need inverses constantly: the view matrix is
the inverse of the camera's transform, picking unprojects the mouse with the inverse
view-projection, skinning uses inverse bind matrices.

### The affine inverse

Solve $\mathbf{p}' = A\mathbf{p} + \mathbf{t}$ for $\mathbf{p}$:

```math
\mathbf{p} = A^{-1}(\mathbf{p}' - \mathbf{t}) = A^{-1}\mathbf{p}' - A^{-1}\mathbf{t}
```

So the inverse is again affine, with linear part $A^{-1}$ and translation $-A^{-1}\mathbf{t}$.
Everything reduces to inverting the 3×3 part.

### Rotations: the inverse is the transpose

A rotation matrix $R$ has orthonormal columns $`\mathbf{c}_0, \mathbf{c}_1, \mathbf{c}_2`$: each has
length 1 and they are mutually perpendicular. Entry $(i, j)$ of $R^T R$ is $`\mathbf{c}_i \cdot \mathbf{c}_j`$,
which is 1 when $i = j$ and 0 otherwise. So $R^T R = I$ and

```math
R^{-1} = R^T
```

No division, no determinant. With a uniform scale $s$, $(sR)^{-1} = R^T / s$.

The view matrix is the standard example. A camera at position $\mathbf{e}$ with axes right
$\mathbf{r}$, up $\mathbf{u}$ and backward $-\mathbf{f}$ has the world transform
$`[\,\mathbf{r}\;\mathbf{u}\;{-\mathbf{f}} \mid \mathbf{e}\,]`$. Its inverse has the transposed
rotation (the axes become rows) and translation $-R^T\mathbf{e}$, which is
$`(-\mathbf{r}\cdot\mathbf{e},\; -\mathbf{u}\cdot\mathbf{e},\; \mathbf{f}\cdot\mathbf{e})`$. Compare with
the end of `Mat4Ops.lookAt`:

```java
        out[o] = rx;
        out[o + 1] = vx;
        out[o + 2] = -fx;
        out[o + 3] = 0;
        // ...
        out[o + 12] = -(rx * ex + ry * ey + rz * ez);
        out[o + 13] = -(vx * ex + vy * ey + vz * ez);
        out[o + 14] = fx * ex + fy * ey + fz * ez;
        out[o + 15] = 1;
```

(`vx, vy, vz` is the up vector.) In column-major storage, `out[0], out[4], out[8]` is row 0, so the
rows are right, up and −forward, and the translation column holds the three dot products. The test
`lookAtPutsEyeAtOriginLookingDownMinusZ` checks that the eye maps to the origin and the target to
$(0, 0, -\text{distance})$.

### General 3×3: cofactors and the adjugate

With non-uniform scale the columns are no longer orthonormal and you need the general inverse. For a
3×3 matrix with columns $`\mathbf{c}_0, \mathbf{c}_1, \mathbf{c}_2`$ there is a neat formula. Consider
the matrix whose **rows** are cross products of the columns:

```math
N = \begin{bmatrix} (\mathbf{c}_1 \times \mathbf{c}_2)^T \\ (\mathbf{c}_2 \times \mathbf{c}_0)^T \\ (\mathbf{c}_0 \times \mathbf{c}_1)^T \end{bmatrix}
```

Entry $(i, j)$ of $NA$ is row $i$ of $N$ dotted with column $j$ of $A$. Row 0,
$`\mathbf{c}_1 \times \mathbf{c}_2`$, is perpendicular to $`\mathbf{c}_1`$ and $`\mathbf{c}_2`$, so it
dots to 0 with them; with $`\mathbf{c}_0`$ it gives the triple product
$`\mathbf{c}_0 \cdot (\mathbf{c}_1 \times \mathbf{c}_2) = \det A`$. The other rows work the same way, so
$`NA = (\det A)\,I`$ and

```math
A^{-1} = \frac{1}{\det A}\,N
```

$N$ is called the **adjugate** of $A$; its transpose is the **cofactor matrix** (each entry is a
signed 2×2 minor). [`AffineOps.invert`](../../modules/math/src/main/java/dev/jeddy/math/AffineOps.java)
is this formula written out. With the linear part named row by row
$`\begin{bmatrix} a & b & c \\ d & e & f \\ g & h & k \end{bmatrix}`$, the columns are
$(a, d, g)$, $(b, e, h)$ and $(c, f, k)$:

```java
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
        // ... rows 1 and 2 the same way
        out[o + 3] = -(i00 * tx + i01 * ty + i02 * tz);
```

Check row 0: $`\mathbf{c}_1 \times \mathbf{c}_2 = (b, e, h) \times (c, f, k) = (ek - hf,\; hc - bk,\; bf - ec)`$,
which is `co0`, `c * h - b * k` and `b * f - c * e`. The determinant is expanded along the first row
with the same cofactors. The translation of the inverse is $-A^{-1}\mathbf{t}$, row by row.

**Numeric example.** $`M = T(4, 0, 0)\,R_y(90°)\,S(2)`$. `composeTRS` produces the rows
(0, 0, 2 | 4), (0, 2, 0 | 0), (−2, 0, 0 | 0) (up to float rounding of order $10^{-7}$). So
$a = b = 0, c = 2$; $d = 0, e = 2, f = 0$; $g = -2, h = k = 0$:

- `co0` = 0, `co1` = 0, `co2` = $0 - 2 \cdot (-2) = 4$, so `det` = $2 \cdot 4 = 8$ (a scale of 2 on
  three axes multiplies volumes by 8);
- row 0 of the inverse: (0, 0, $(0 - 4)/8$) = (0, 0, −0.5); row 1: (0, $(0 - 2 \cdot (-2))/8$, 0) =
  (0, 0.5, 0); row 2: (4/8, 0, 0) = (0.5, 0, 0);
- translation: $-(0, 0, 0.5 \cdot 4) = (0, 0, -2)$.

$M$ sends (1, 0, 0) to (4, 0, −2); the inverse sends (4, 0, −2) to
$`(-0.5 \cdot (-2),\; 0,\; 0.5 \cdot 4) + (0, 0, -2) = (1, 0, 0)`$. The rotation-transpose shortcut gives
the same result: $R^T / 2$.

`invert` returns `false` for a singular matrix (for example a scale of 0 on some axis), and it also
rejects non-finite determinants. `Mat4f.invert` and `Mat3f.invert` throw `ArithmeticException`
instead. The test `inverseTimesMatrixIsIdentity` checks the round trip on random TRS matrices and the
exception for `Mat4f.scaling(1, 0, 1)`; `affineOpsAgreeWithMat4` checks `AffineOps.invert` against
`Mat4f.invert`.

### The 4×4 inverse

`Mat4Ops.invert` inverts a general 4×4 (needed for the inverse view-projection, which is not affine).
It uses the same cofactor idea, organized around twelve 2×2 sub-determinants: `b00..b05` are the six
2×2 minors of columns 0 and 1, `b06..b11` the six of columns 2 and 3 (a Laplace expansion along a
pair of columns), so each one is computed once and reused. (The code's local names `aCR` follow the
column-major storage: `a01` is `m[1]`, column 0, row 1.) You don't need
to memorize it; the test is the specification:
`assertTrue(m.mul(m.invert()).approxEquals(Mat4f.IDENTITY, 1e-4f))` over 200 random TRS matrices.

---

## Determinants and mirroring

The determinant of a 3×3 matrix is the triple product of its columns:

```math
\det A = \mathbf{c}_0 \cdot (\mathbf{c}_1 \times \mathbf{c}_2)
```

Geometrically it is the **signed volume** of the box spanned by the images of the three unit axes:
the factor by which the transform multiplies volumes.

- $\det(\operatorname{diag}(s_x, s_y, s_z)) = s_x s_y s_z$. The test checks
  `Mat4f.scaling(1, 2, 3).determinant() == 6`.
- A rotation preserves volume and orientation: $\det R = 1$ (test:
  `Mat3f.rotation(randomQuat()).determinant()` is 1 within 1e-4).
- $\det(AB) = \det A \cdot \det B$, so determinants of composed transforms multiply.
- $\det = 0$ means the transform flattens space onto a plane or a line. It has no inverse:
  `Mat4f.scaling(1, 0, 1).invert()` throws.
- $`\det < 0`$ means the transform **mirrors**. A scale of (−1, 1, 1) has determinant −1.

Mirroring matters for rendering. It turns a right-handed frame into a left-handed one, so a triangle
whose vertices ran counter-clockwise now runs clockwise; the winding order, which back-face culling
relies on, flips (the opaque pipeline state culls back faces, `PipelineState.OPAQUE`). It also flips
the handedness of tangent frames. `instance.glsl` detects it with the triple product:

```glsl
// True if the transform mirrors (negative determinant): triangles flip their winding.
bool jeddyMirrored(JeddyInstance i) {
    mat3 m = jeddyLinear(i);
    return dot(m[0], cross(m[1], m[2])) < 0.0;
}
```

`pbr.vert` uses it to flip the bitangent sign of mirrored instances (chapter 03 explains why).

The shaders fix the tangent frame, but nothing flips the winding test yet. A mirrored instance
drawn with back-face culling therefore loses its front faces and shows its inside. DESIGN §9.2
records this as a known limitation. The fix would split mirrored instances into their own batches
(a sort-key bit) drawn with `glFrontFace(GL_CW)`. It makes a good small project: see
[the warm-up tasks](../next-milestones.md#warm-up-tasks).

---

## Transforming normals

### Why normals need the inverse transpose

A normal is not an ordinary direction. Tangent vectors lie *along* a surface; the normal is defined
by being *perpendicular* to all of them. Under a non-uniform scale, transforming the normal like a
direction breaks that.

**Example.** Take the plane $x + y = 0$ (a 45° slope). Its normal is $\mathbf{n} = (1, 1, 0)/\sqrt{2}$
and a tangent along it is $\mathbf{t} = (1, -1, 0)$. Scale x by 2: $M = \operatorname{diag}(2, 1, 1)$.

- The tangent becomes $M\mathbf{t} = (2, -1, 0)$. The slope is now flatter: it drops 1 in y over 2
  in x instead of over 1.
- Transforming the normal the same way gives $M\mathbf{n} \propto (2, 1, 0)$, and
  $(2, 1, 0) \cdot (2, -1, 0) = 3 \neq 0$. Not perpendicular any more.

What transform $G$ keeps normals perpendicular? We need $(G\mathbf{n})^T (M\mathbf{t}) = 0$ for every
tangent with $\mathbf{n}^T\mathbf{t} = 0$. Since $(G\mathbf{n})^T M\mathbf{t} = \mathbf{n}^T G^T M\mathbf{t}$,
it is enough that $G^T M = I$, that is

```math
G = (M^{-1})^T = M^{-T}
```

For the example, $M^{-T} = \operatorname{diag}(0.5, 1, 1)$ and $G\mathbf{n} \propto (0.5, 1, 0)$:
$(0.5, 1, 0) \cdot (2, -1, 0) = 0$. Perpendicular again. Notice that normals shrink along the
stretched axis. That is exactly what the test `mat3NormalMatrixAndMutableTypes` asserts
(`n.x() * 2 == n.y()`) using [`Mat3f.normalMatrix`](../../modules/math/src/main/java/dev/jeddy/math/Mat3f.java):

```java
    public static Mat3f normalMatrix(Mat4f model) {
        return of(model).invert().transpose();
    }
```

Two special cases explain why many engines get away without it: for a rotation $R^{-T} = R$, and for
a uniform scale $s$ the inverse transpose is $R/s$, which only changes the length. A normal has to be
renormalized after transformation anyway.

### The cofactor trick in the shader

Computing an inverse per vertex would be expensive and would need a division by the determinant. The
shader in `instance.glsl` uses the result from the inverse section instead. We found that the rows of
$`(\det A)\,A^{-1}`$ are $`\mathbf{c}_1 \times \mathbf{c}_2`$, $`\mathbf{c}_2 \times \mathbf{c}_0`$ and
$`\mathbf{c}_0 \times \mathbf{c}_1`$. Transposing, those three cross products are the **columns** of
$`(\det A)\,A^{-T}`$, the cofactor matrix:

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
  that `m[j]` really is column j.
- `cofactor * n` equals $`(\det A)\,A^{-T}\mathbf{n}`$. `normalize` removes the magnitude $|\det A|$
  but not its sign.
- When $`\det A < 0`$ (a mirror), the cofactor result points inwards, so the last factor flips it back
  to $A^{-T}\mathbf{n}$, the outward normal.
- There is no division, so a degenerate (zero-scale) transform does not produce infinities.

Three cross products and a dot product cost about as much as building the inverse, but this version
needs no reciprocal and handles mirrors in one place. Skinned vertices go through it too, after
`jeddySkinDirection` has applied the joint matrices with their plain linear part (joints scale
uniformly, so that is enough there); see [09 Skeletal animation and GPU skinning](09-animation-and-skinning.md).

---

## Planes, spheres, boxes and rays

The geometry records are thin wrappers around the ideas above.

- **Plane** ([`Planef`](../../modules/math/src/main/java/dev/jeddy/math/Planef.java)): all points
  with $\hat{\mathbf{n}} \cdot \mathbf{p} + d = 0$. For a unit normal, `distance(p)` =
  $\hat{\mathbf{n}} \cdot \mathbf{p} + d$ is the signed distance: positive in front. `fromPointNormal`
  sets $`d = -\hat{\mathbf{n}} \cdot \mathbf{p}_0`$ so the plane passes through $`\mathbf{p}_0`$.
- **Ray** ([`Ray3f`](../../modules/math/src/main/java/dev/jeddy/math/Ray3f.java)): the points
  $\mathbf{o} + t\mathbf{d}$ for $t \ge 0$. Substituting into the plane equation gives
  $t = -(\hat{\mathbf{n}} \cdot \mathbf{o} + d)/(\hat{\mathbf{n}} \cdot \mathbf{d})$, which is
  `Planef.intersect` and `Intersect.rayPlane`. Picking the ground under the mouse is this formula
  with `Planef.GROUND`.
- **Sphere** and **axis-aligned box** (`Spheref`, `Aabb3f`): bounding volumes. A sphere is in front of
  a plane, or straddles it, if its center's signed distance is at least $-r$. That one comparison per
  plane is the frustum test in `CullOps.sphereVisible`, covered in chapter 04.

`Intersect` holds the full set of tests (ray–box slabs, watertight ray–triangle, ray–capsule, cone
and sector tests for spell areas) with primitive arguments, so they allocate nothing. Chapter
[11 Physics, navigation and crowds](11-physics-navigation-crowds.md) uses them.

---

## Batch kernels on struct-of-arrays

Here is the same data in the two layouts:

```
  Array of structures (AoS)              Structure of arrays (SoA)
  Unit[] units                           float[] x = { x0, x1, x2, x3, ... }
   [x0 y0 z0 r0 ...] [x1 y1 z1 r1 ...]    float[] y = { y0, y1, y2, y3, ... }
   (objects scattered on the heap)        float[] z = { z0, z1, z2, z3, ... }
                                          float[] r = { r0, r1, r2, r3, ... }
```

A loop that only needs `x`, `y`, `z` and `r` reads four dense streams in SoA, which is what caches,
prefetchers and SIMD units like. In AoS it would chase a pointer per unit and load fields it does
not need. Jeddy's per-frame data is SoA (DESIGN §4.1), so the kernels take SoA. A good example is
`CullOps.spheres`, which tests bounding spheres against the six frustum planes and writes the
indices of visible ones:

```java
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
```

Each line is a plane's signed distance compared with $-r$. Two details are performance tricks: the
planes are loaded into 24 local variables before the loop, and the non-short-circuit `&` plus the
unconditional `out[n] = i` avoid unpredictable branches (the index is always written, but the counter
only advances for visible spheres).

How fast is it? `MathBench` measures the kernels on 10,000 items. The committed baseline from a
shared GitHub Linux runner (`bench/baseline/linux-x64.json`, noisy by nature) has about 60 µs for
`composeTRS10k`, 96 µs for `concatenate10k`, 40 µs for `cullSpheres10k` and 15 ns for one
`Mat4Ops.mul`: a few nanoseconds per item.

**Try it.**

- `./gradlew :math:test --tests 'dev.jeddy.math.MatrixAndQuatTest'` runs the matrix tests, including
  `mat4MultiplyMatchesDoubleReference` (checks `Mat4Ops.mul` against a `double` reference, the
  pattern DESIGN §8.3 asks of every kernel) and `kernelsDoNotAllocate`, which wraps a camera update,
  a hierarchy pass, sphere culling and a ray–triangle test in `Allocations.assertNoAllocation`.
- `./gradlew :bench:jmh -Pjmh.includes=MathBench` runs the benchmarks.

---

## Packing: half floats and snorm

Vertex data is the largest stream the GPU reads, so Jeddy encodes it compactly (decision D27). A
`.jmesh` vertex has a 12-byte position stream and a 16-byte attribute stream
([docs/formats/jmesh.md](../formats/jmesh.md)):

| Bytes | Attribute | Encoding |
|---|---|---|
| 0–7 | QTangent (normal, tangent, handedness) | `snorm16` × 4 |
| 8–11 | uv0 | `f16` × 2 |
| 12–15 | uv1 | `f16` × 2 |

[`Packing`](../../modules/math/src/main/java/dev/jeddy/math/Packing.java) and
[`MeshEncoding`](../../modules/asset/src/main/java/dev/jeddy/asset/format/MeshEncoding.java) hold the
encoders.

### Half floats

An IEEE 754 binary16 ("half") has 1 sign bit, 5 exponent bits and 10 mantissa bits. It represents
numbers up to 65504 with about 3 decimal digits. Java has converted to and from it since JDK 20
(`Float.floatToFloat16`, `Float.float16ToFloat`), HotSpot turns those calls into single instructions
where the CPU has them, and `Packing` just wraps them:

```java
    /** Converts to IEEE 754 binary16 bits (intrinsified). */
    public static short toHalf(float f) {
        return Float.floatToFloat16(f);
    }
```

With 10 mantissa bits, the spacing between representable values in $[2^e, 2^{e+1})$ is $2^{e-10}$.
Some consequences for texture coordinates:

- 1.5 is exact; 0.1 becomes 0.0999755859375; 0.7 becomes 0.7001953.
- For $u \in [0.5, 1)$ the spacing is $2^{-11} = 1/2048$: one texel of a 2048-wide texture.
- For tiling coordinates in $[2, 4)$ the spacing is $2^{-9}$, four times coarser.

That is fine for typical models with UVs in [0, 1] and is the trade the format makes for halving the
UV size.

### snorm16 and snorm8

A **signed normalized** integer maps $[-1, 1]$ onto the integer range. For 16 bits:

```math
s = \operatorname{round}(\operatorname{clamp}(v, -1, 1) \cdot 32767),
\qquad
v' = \max(-1,\; s / 32767)
```

```java
    public static short packSnorm16(float v) {
        return (short) Math.round(Math.max(-1f, Math.min(1f, v)) * 32767f);
    }

    public static float unpackSnorm16(short s) {
        return Math.max(-1f, s / 32767f);
    }
```

The `max(-1, ...)` in the decoder handles −32768, which would otherwise decode to slightly less than
−1. This is the same rule OpenGL applies when a vertex attribute is declared as normalized
`GL_SHORT`, which is how `MeshRegistry` declares the QTangent:
`glVertexArrayAttribFormat(vao, 1, 4, GL_SHORT, true, 0)`. The shader receives floats in [−1, 1]
without doing any decoding itself.

The step is $1/32767 \approx 3 \times 10^{-5}$. Example: `packSnorm16(0.5f)` is 16384, which decodes
to 0.5000153.

### Octahedral normals (briefly)

`Packing.packOctahedral16` stores a unit vector in 4 bytes. It projects the unit sphere onto the
octahedron $|x| + |y| + |z| = 1$ (divide by that sum), folds the lower half ($`z < 0`$) over the
upper one, and stores the resulting 2D point as two snorm16 values. The test `packing` checks 5,000
random unit vectors: each round trip keeps a dot product above 0.99999 with the original. The mesh
format uses QTangents instead (chapter 03), because one quaternion carries the normal, the tangent
and the handedness together.

---

## Floating point in practice

### How precise is a `float`?

A `float` has a 24-bit significand, about 7 significant decimal digits. The gap between neighbouring
values (`Math.ulp`) grows with magnitude:

| Value | Spacing (`Math.ulp`) |
|---|---|
| 1 | $1.2 \times 10^{-7}$ |
| 100 | $7.6 \times 10^{-6}$ (7.6 µm if the unit is metres) |
| 1000 | $6.1 \times 10^{-5}$ (61 µm) |
| 4000 | $2.4 \times 10^{-4}$ (0.24 mm) |

A CRPG level is a few hundred metres across, so `float` positions are far more precise than needed
(DESIGN §8.2). Open-world engines that span tens of kilometres hit visible jitter and use
camera-relative rendering or `double`; Jeddy does not need to.

### Never compare floats with `==`

Rounding errors accumulate. A rotation by 90° gives entries like `5.9604645E-8` instead of 0, and an
inverse times its matrix gives an identity that is off by $10^{-7}$. So comparisons use a tolerance:

- `Vec3f.approxEquals(o, epsilon)`, `Mat4f.approxEquals` and `MathF.approx` compare each component
  with an absolute epsilon. The math tests use `EPS = 1e-4f`.
- When values can be large, an absolute epsilon is wrong: 1e-4 is huge next to 0.001 and tiny next to
  10,000. `TransformStoreTest` uses a relative tolerance, `2e-4f * (1 + Math.abs(ref[k]))`.
- `Quatf.approxEquals` compares rotations, not components: `1 - Math.abs(dot(o)) <= epsilon`, because
  $q$ and $-q$ are the same rotation (chapter 03).

Guards against degenerate input use exact checks only where exactness is meaningful: the inverses
reject `det == 0 || !Float.isFinite(det)` (a determinant that overflowed or is NaN is as useless as
zero), and `Planef.intersect` treats a ray as parallel when $`|\hat{\mathbf{n}} \cdot \mathbf{d}| < 10^{-12}`$.

### Subtle consequences

- **Lerp endpoints.** `a + (b - a) * t` returns exactly `a` at $t = 0$ but not always `b` at
  $t = 1$. With `a = 1e8f, b = 1f`, the difference `b - a` rounds to −1e8 and `MathF.lerp(1e8f, 1f, 1f)`
  returns 0, not 1. The form $(1 - t)a + tb$ is exact at both ends but can overshoot in between. For
  game values of similar magnitude the difference is invisible; know it exists.
- **Drift.** Multiplying rotations frame after frame slowly loses orthonormality, and quaternions
  drift from unit length. Jeddy avoids the accumulation: `TransformStore` keeps the local
  translation, rotation and scale and rebuilds the world matrix from them, and code that composes
  quaternions renormalizes (`QuatOps.normalize`, `World.compose`, every `nlerp`).
- **Determinism.** Since JDK 17 (JEP 306) all Java floating-point arithmetic is strict IEEE 754, so
  `+ - * /` and `Math.sqrt` give the same bits on every machine. Transcendental functions in `Math`
  may differ in the last bit between platforms; DESIGN §4.6 says gameplay code that needs bit-exact
  results uses `StrictMath`. It also says to avoid `Math.fma` in kernels, because without hardware
  FMA it falls back to a very slow software path.
- **References in double.** The kernel tests compute expected values in `double` (`mulRef` in
  `MatrixAndQuatTest`), so the reference is far more precise than the code under test and the
  tolerance measures the kernel's error, not the test's.

---

## Exercises

1. **Read a matrix by its columns.** Without running anything, write down the 16 floats of
   `Mat4f.rotation(Quatf.fromYaw(MathF.HALF_PI))` in `toArray` order, then the 12 floats
   `AffineOps.composeTRS` writes for the same rotation with zero translation and unit scale. Check
   both in a small test.
   <details><summary>Hint</summary>

   The images of the axes are X → (0, 0, −1), Y → (0, 1, 0), Z → (1, 0, 0). Column-major writes those
   columns one after another (each followed by a 0), then (0, 0, 0, 1). Row-major writes the rows
   (0, 0, 1, 0), (0, 1, 0, 0), (−1, 0, 0, 0). Expect values like 5.96e-8 instead of exact zeros, so
   compare with a tolerance.

   </details>

2. **Order matters.** Add a test to `MatrixAndQuatTest` that builds $T(5, 0, 0)$ and $R_y(90°)$ with
   `AffineOps.composeTRS`, multiplies them both ways with `AffineOps.mul`, and asserts where each
   product sends the point (1, 0, 0).
   <details><summary>Hint</summary>

   Expected: (5, 0, −1) for T·R and (0, 0, −6) for R·T. Use `AffineOps.transformPoint` and an epsilon
   of 1e-5.

   </details>

3. **Invert by hand, check by machine.** Pick $`M = T(1, 2, 3)\,S(2, 4, 0.5)`$ (no rotation). Derive
   $M^{-1}$ on paper from the affine inverse formula, then assert that `AffineOps.invert` produces it
   and that `AffineOps.mul(m, inv)` is the identity.
   <details><summary>Hint</summary>

   For a diagonal linear part the inverse is the reciprocal diagonal (0.5, 0.25, 2), and the
   translation is $-A^{-1}\mathbf{t} = (-0.5, -0.5, -6)$.

   </details>

4. **Add `AffineOps.determinant`.** Write `public static float determinant(float[] m, int mo)` for the
   linear part of a 3×4, and test it on a TRS with scale (−1, 2, 3) (expect −6 for any rotation).
   Follow the kernel rules: no allocation, Javadoc with thread ownership.
   <details><summary>Hint</summary>

   Reuse the three cofactors at the top of `AffineOps.invert`: `a * co0 + b * co1 + c * co2`. Add a
   case to `kernelsDoNotAllocate` if you want to prove it allocates nothing.

   </details>

5. **A CPU version of `jeddyTransformNormal`.** Add `AffineOps.transformNormal(float[] m, int mo, float x, float y, float z, float[] out, int o)`
   using the cofactor trick, then test it against `Mat3f.normalMatrix` for a non-uniform scale and
   for a mirror (scale (−1, 1, 1)).
   <details><summary>Hint</summary>

   Columns of the linear part are at indices (0, 4, 8), (1, 5, 9), (2, 6, 10). The cofactor matrix
   has columns $`\mathbf{c}_1 \times \mathbf{c}_2`$, $`\mathbf{c}_2 \times \mathbf{c}_0`$,
   $`\mathbf{c}_0 \times \mathbf{c}_1`$. Normalize and multiply by the sign of the determinant. `Mat3f.normalMatrix`
   gives $A^{-T}\mathbf{n}$ unnormalized, so compare directions.

   </details>

6. **Measure half-float UV error.** Write a test that encodes u = k/4096 for every k in [0, 4096) with
   `Packing.toHalf`, decodes it, and reports the largest error in texels of a 4096-wide texture. Then
   repeat for u + 3 (a tiling coordinate).
   <details><summary>Hint</summary>

   In [0.5, 1) the spacing is $2^{-11}$, so the worst rounding error is $2^{-12}$, one texel at 4096.
   Above 2 the error grows by a factor of 4.

   </details>

7. **Benchmark 3×4 against 4×4.** Add a `@Benchmark` to `MathBench` that multiplies two 3×4
   transforms with `AffineOps.mul`, next to `mat4Mul`. Run
   `./gradlew :bench:jmh -Pjmh.includes=MathBench` and compare. Add `"-Pjmh.args=-prof gc"` to check
   that neither allocates.
   <details><summary>Hint</summary>

   Return a value from the output array (as `mat4Mul` returns `out[15]`) so the JIT cannot remove the
   work. Expect the 3×4 product to be somewhat faster: 36 multiplies against 64.

   </details>

---

## Further reading

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
- LearnOpenGL, "Transformations": https://learnopengl.com/Getting-started/Transformations
- JEP 401, Value Classes and Objects: https://openjdk.org/jeps/401 and JEP 306, Restore
  Always-Strict Floating-Point Semantics: https://openjdk.org/jeps/306

---

[← 01 Architecture](01-architecture.md) · [Index](README.md) · [03 Rotations and transforms →](03-rotations-and-transforms.md)
