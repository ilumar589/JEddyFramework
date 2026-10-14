# 07 — Lighting: PBR, clustered lights and shadows

*Every pixel of a lit surface ends up as three numbers: how much red, green and blue light leaves
that point of the surface towards the camera. This chapter builds, from empty files, the code that
computes those numbers the way physics says light behaves. It starts from nothing but arithmetic:
what an amount of light is (energy, power, radiance, irradiance, the candela and the lux), how a
sum over every incoming direction becomes the rendering equation, and how that equation turns,
term by term, into the lines of `pbr.frag`: the Cook–Torrance reflection model with its GGX,
Smith and Fresnel parts, each derived and checked with numbers, the sun, point and spot lights,
light from the whole sky through a prefiltered cube map, nine spherical-harmonic numbers and a
lookup table, and normal maps. Then come the three systems that make it fast and believable:
clustered light lists built by compute shaders so thousands of lights cost what the few near each
pixel cost, shadow maps for the sun (cascaded and stabilized) and for torches (in a cached atlas),
and ground-truth ambient occlusion. It ends with the tests that prove it, the white furnace among
them, the numbers, and the places where the code is still not right.*

**Before you start.** You need [Lesson 0](lessons/00-foundations.md): the notation (§2: sums,
powers, roots, intervals), how a `float` is stored (§7; chapter 05, on texture formats, adds the
16-bit half float of `GL_RGBA16F`), memory and threads (§8–9), and what a GPU, a shader, a compute shader and the driver
are (§10–11). From the earlier chapters: vectors, the dot and cross products, normalizing and the
inverse transpose of a matrix (chapter [02](02-math-foundations.md), on math foundations); angles in
radians, sine, cosine and arc-cosine, quaternions and the QTangent (chapter
[03](03-rotations-and-transforms.md), on rotations); the view and projection matrices, reversed-Z
depth, orthographic projection, unprojecting a pixel back into a ray, and frustum planes (chapter
[04](04-cameras-and-projection.md), on cameras and projection); the GPU pipeline, quads and
derivatives, the depth test and polygon offset, compute shaders, textures, mip levels, filtering,
depth comparison, the std140 and std430 layouts and samplers (chapter
[05](05-opengl-from-java.md), on OpenGL from Java); and the render packet, draw building, material
rows and their binders, the frame graph and its barriers, `atomicAdd` and the scene renderer
(chapter [06](06-renderer-architecture.md), on the renderer's architecture).
[Lesson 1](lessons/01-buffers-in-flight.md) explains the frame ring every per-frame byte here is
written into, fences and memory barriers; [Lesson 2](lessons/02-dice-and-probability.md) §3 explains
the mean (expected value) that Monte Carlo integration rests on. This chapter teaches integrals,
solid angles and everything about light from zero (§2–4).

**What you will build**

| Piece | File | What it does |
|---|---|---|
| The BRDF | [`engine/brdf.glsl`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/engine/brdf.glsl) | GGX distribution, height-correlated Smith visibility, Schlick Fresnel, Lambert diffuse, one light's contribution |
| The lit shaders | [`pbr/pbr.vert`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/pbr/pbr.vert), [`pbr/pbr.frag`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/pbr/pbr.frag) | the rendering equation evaluated per pixel: material, normal map, sun, clustered lights, image-based light, emission |
| Lights | [`engine/lights.glsl`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/engine/lights.glsl), [`RenderPacket`](../../modules/render/src/main/java/dev/jeddy/render/RenderPacket.java) | the 16-float light record, the cluster lookup, distance and cone falloff |
| Image-based lighting | [`light/Environment.java`](../../modules/render/src/main/java/dev/jeddy/render/light/Environment.java), [`engine/environment.glsl`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/engine/environment.glsl), [`pbr/brdf_lut.comp`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/pbr/brdf_lut.comp) | the prefiltered cube, nine SH coefficients, the split-sum lookup table generated at startup |
| The bakes | [`EnvironmentBaker`](../../tools/cooker/src/main/java/dev/jeddy/cooker/env/EnvironmentBaker.java), [`EnvironmentImporter`](../../tools/cooker/src/main/java/dev/jeddy/cooker/env/EnvironmentImporter.java) | the cooker's prefilter (filtered importance sampling), SH projection and LUT, on the CPU |
| Clusters | [`light/LightClusters.java`](../../modules/render/src/main/java/dev/jeddy/render/light/LightClusters.java), [`pbr/cluster_bounds.comp`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/pbr/cluster_bounds.comp), [`pbr/light_cull.comp`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/pbr/light_cull.comp) | 64-pixel tiles × 24 depth slices; per-cluster light and decal lists built by compute shaders with an atomic counter |
| Sun shadows | [`shadow/SunShadows.java`](../../modules/render/src/main/java/dev/jeddy/render/shadow/SunShadows.java), [`engine/sun_shadows.glsl`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/engine/sun_shadows.glsl), [`engine/shadows.glsl`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/engine/shadows.glsl) | three stable cascades fitted to spheres and snapped to texels, 3×3 PCF |
| Local shadows | [`shadow/LocalShadows.java`](../../modules/render/src/main/java/dev/jeddy/render/shadow/LocalShadows.java), [`engine/local_shadows.glsl`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/engine/local_shadows.glsl) | spot and point light shadows in a 2048² atlas of 256² tiles, with a per-light cache of static casters |
| Ambient occlusion | [`post/Gtao.java`](../../modules/render/src/main/java/dev/jeddy/render/post/Gtao.java), [`post/gtao.frag`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/post/gtao.frag), [`post/gtao_blur.frag`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/post/gtao_blur.frag), [`engine/effects.glsl`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/engine/effects.glsl) | half-resolution horizon-based occlusion from the depth prepass, a depth-aware blur |
| Wiring | [`SceneRenderer`](../../modules/render/src/main/java/dev/jeddy/render/SceneRenderer.java), [`material/MaterialSystem.java`](../../modules/render/src/main/java/dev/jeddy/render/material/MaterialSystem.java) | the passes in order, the bindings the lit passes read, the `GL_GEQUAL` lit pass |

Shader paths below are relative to `modules/render/src/main/resources/dev/jeddy/render/shaders/`.
The design is summarized in [DESIGN](../DESIGN.md) §9.3.

**Contents**

- Part A — The ideas
  1. [What the lit pass computes](#1-what-the-lit-pass-computes)
  2. [A math toolbox: integrals and directions](#2-a-math-toolbox-integrals-and-directions)
  3. [Radiometry: measuring light](#3-radiometry-measuring-light)
  4. [The rendering equation](#4-the-rendering-equation)
  5. [The material model: metallic–roughness](#5-the-material-model-metallicroughness)
  6. [The Cook–Torrance BRDF, term by term](#6-the-cooktorrance-brdf-term-by-term)
  7. [Punctual lights](#7-punctual-lights)
  8. [Image-based lighting](#8-image-based-lighting)
  9. [Normal mapping](#9-normal-mapping)
  10. [Many lights: clustered forward+](#10-many-lights-clustered-forward)
  11. [Shadow maps](#11-shadow-maps)
  12. [The depth prepass and GEQUAL](#12-the-depth-prepass-and-gequal)
  13. [Ambient occlusion](#13-ambient-occlusion)
- Part B — Building it
  14. [Step 1: the BRDF in GLSL](#14-step-1-the-brdf-in-glsl)
  15. [Step 2: what the frame constants carry for lighting](#15-step-2-what-the-frame-constants-carry-for-lighting)
  16. [Step 3: pbr.frag, part 1 — the surface](#16-step-3-pbrfrag-part-1--the-surface)
  17. [Step 4: light records on the CPU](#17-step-4-light-records-on-the-cpu)
  18. [Step 5: the light include](#18-step-5-the-light-include)
  19. [Step 6: pbr.frag, part 2 — summing the light](#19-step-6-pbrfrag-part-2--summing-the-light)
  20. [Step 7: Environment and its include](#20-step-7-environment-and-its-include)
  21. [Step 8: the BRDF lookup table on the GPU](#21-step-8-the-brdf-lookup-table-on-the-gpu)
  22. [Step 9: baking the environment in the cooker](#22-step-9-baking-the-environment-in-the-cooker)
  23. [Step 10: LightClusters](#23-step-10-lightclusters)
  24. [Step 11: the cluster bounds shader](#24-step-11-the-cluster-bounds-shader)
  25. [Step 12: the light cull shader](#25-step-12-the-light-cull-shader)
  26. [Step 13: SunShadows](#26-step-13-sunshadows)
  27. [Step 14: receiving the sun's shadow](#27-step-14-receiving-the-suns-shadow)
  28. [Step 15: LocalShadows](#28-step-15-localshadows)
  29. [Step 16: receiving local shadows](#29-step-16-receiving-local-shadows)
  30. [Step 17: Gtao and its shaders](#30-step-17-gtao-and-its-shaders)
  31. [Step 18: wiring it into SceneRenderer](#31-step-18-wiring-it-into-scenerenderer)
- Part C — Using it and proving it
  32. [One lit frame, in order](#32-one-lit-frame-in-order)
  33. [The tests, walked through](#33-the-tests-walked-through)
  34. [Things to run](#34-things-to-run)
  35. [The numbers](#35-the-numbers)
  36. [Why it is built this way](#36-why-it-is-built-this-way)
  37. [Known limits and open problems](#37-known-limits-and-open-problems)
  38. [Further reading](#38-further-reading)
- [Exercises](#exercises)
- [Glossary](#glossary)

---

# Part A — The ideas

## 1. What the lit pass computes

### 1.1 One number per pixel per color channel

A camera pixel, and the eye, measure how much light arrives from one small cone of directions. A
renderer has to produce the same thing: for every pixel, the amount of red, green and blue light
that leaves the visible surface point **towards the camera**. Everything in this chapter serves
that one result. Three things decide it:

- **the lights**: where light comes from and how much of it — the sun, torches, spells, and the
  sky and walls, which send light back too;
- **the surface**: how a material turns incoming light into outgoing light — a polished metal
  sends almost all of it in one direction, chalk spreads it everywhere;
- **what is in the way**: shadows, and the darkening of corners and creases.

The physics of all three is old and well understood. The problem is cost: the true answer is a sum
over every direction light can come from, for two million pixels, sixty times a second. So the
chapter is a sequence of honest approximations, each with a name and a measurable error, and each
chosen so that the GPU can evaluate it in a few hundred instructions per pixel.

### 1.2 The lit frame at a glance

`SceneRenderer` declares its passes once, in this order (chapter
[06](06-renderer-architecture.md), on the scene renderer, builds that list). The passes this chapter
covers, and what each one hands to the next:

```
 light cull (compute)  ─────── cluster lists ─────────────────────────────┐
 static cull (compute, chapter 06)                                         │
 sun shadows (3 depth layers) ───── cascades, unit 7 ─────────────────────┤
 local shadows (atlas tiles) ────── atlas, unit 8 ────────────────────────┤
 depth prepass ──── depth ──┬──▶ ao, ao blur (half resolution) ── AO, unit 9 ─┤
                            │                                              ▼
                            └──────────────────────────────────▶  opaque: pbr.frag (GL_GEQUAL)
                                                                           │
                                                                    sky ─▶ transparent: pbr.frag
                                                                           │
                                                   particles, outlines, post (chapter 08)
```

The **opaque** pass is where the lighting happens. Before it draws, `SceneRenderer.bindLighting`
binds everything the lit shader reads:

| Kind | Binding | Content | Owner |
|---|---|---|---|
| Uniform buffer | 0 | frame constants (`engine/frame.glsl`, 544 bytes) | `SceneRenderer.FRAME_BINDING` |
| Uniform buffer | 1 | sun cascades | `SunShadows.BINDING` |
| Uniform buffer | 2 | SH9 irradiance | `Environment.SH_BINDING` |
| Storage buffer | 2, 3, 4, 6 | lights, cluster ranges, cluster items, decals | `LightClusters.*_BINDING` (its `bind`) |
| Storage buffer | 8 | local shadow views | `LocalShadows.BINDING` |
| Texture unit | 5, 6 | prefiltered environment cube, BRDF lookup table | `Environment.CUBE_UNIT`, `LUT_UNIT` |
| Texture unit | 7, 8 | sun cascade array, local shadow atlas | `SunShadows.UNIT`, `LocalShadows.UNIT` |
| Texture unit | 9 | ambient occlusion | `Gtao.UNIT` |
| Texture unit | 10, 12 | decal textures, fog-of-war grid (chapter [08](08-effects-and-post.md)) | `DecalTextures.UNIT`, `FogOfWar.UNIT` |

Uniform-buffer bindings, storage-buffer bindings and texture units are three separate numbering
spaces (chapter [05](05-opengl-from-java.md), on getting data to shaders), so "uniform 2" and
"storage 2" are different slots. The cluster bounds (storage 7, `BOUNDS_BINDING`) are read only by
the culling compute pass, so the lit pass does not bind them. The instance records and material
rows (storage 0 and 1) are bound by `DrawBuilder.submit`, and the material textures (units 0–4) per
batch by `MaterialSystem`'s binder (chapter [06](06-renderer-architecture.md), on draw building and
the material system).

### 1.3 The structure of pbr.frag

Open [`pbr/pbr.frag`](../../modules/render/src/main/resources/dev/jeddy/render/shaders/pbr/pbr.frag)
and read it once from top to bottom before the details; it is 148 lines. Its `main` does this, in
order:

1. Takes the screen-space derivatives of the world position (`dFdx`, `dFdy`) while control flow is
   still the same for all four pixels of a quad (chapter 05, on the fragment shader); decals need
   them later.
2. Camera fade: discards dithered pixels of faded surfaces (chapter 08).
3. Computes the view depth and finds the pixel's **cluster** (`jeddyClusterIndex`, §10), which
   gives the range of lights and decals to visit.
4. Reads the material row and its textures: base color, alpha mask, ORM (occlusion, roughness,
   metallic), separate occlusion (§5).
5. Applies decals, which tint the base color and make the surface matte (chapter 08).
6. Clamps roughness to $[0.045, 1]$ and metallic to $[0, 1]$.
7. Builds the shading normal: interpolated normal, flipped for back faces of double-sided
   materials, tilted by the normal map through the tangent frame (§9).
8. Fills a `JeddySurface`: view vector, $\mathbf n \cdot \mathbf v$, diffuse color, $F_0$, $\alpha$,
   energy compensation (§5, §6).
9. Adds the sun, with its cascaded shadow (§7.3, §11).
10. Loops over the cluster's point and spot lights, with their atlas shadows (§7, §11.7).
11. Adds image-based lighting, weakened by ambient occlusion (§8, §13).
12. Adds emission, applies fog of war, writes `vec4(color, base.a)`.

Steps 9 to 12 are the rendering equation of §4, one term at a time. Sections 2 to 4 build the
language to say that precisely.

## 2. A math toolbox: integrals and directions

Light arrives at a point from every direction above it, and the answer to "how much light leaves"
is a sum over all of those directions. Sums over a continuum are **integrals**. This section teaches
the little integration the chapter needs, from the $\Sigma$ of L00 §2.2.

### 2.1 An integral is a sum of thin slices

Take a function, say $f(x) = x^2$, and ask for the area between its curve and the $x$ axis from
$x = 0$ to $x = 1$. Cut the interval into $n$ slices of width $h = 1/n$; over each slice the
function hardly changes, so the slice's area is about its height in the middle times its width:

$$\text{area} \approx \sum_{i=0}^{n-1} f\big((i + \tfrac12)\,h\big)\,h$$

With $n = 4$ slices the middles are $0.125, 0.375, 0.625, 0.875$, the heights $0.015625$,
$0.140625$, $0.390625$, $0.765625$, and the area is $(0.015625 + 0.140625 + 0.390625 + 0.765625)
\cdot 0.25 = 0.328125$. With 10 slices it is $0.3325$; with 100, $0.333325$. The sums settle on
one number as the slices get thinner, $1/3$. That limit is the **integral**:

$$\int_0^1 x^2\,dx = \frac13$$

Read $\int_a^b \ldots\,dx$ as "the sum, over thin slices of width $dx$ from $a$ to $b$, of …". It
is a `for` loop over infinitely many infinitely thin slices, and you can always approximate it with
a finite loop, which is exactly what the cooker and the GPU do in §8.6.

Two rules carry over from sums (L00 §2.2): a constant factor comes out,
$\int c\,f(x)\,dx = c \int f(x)\,dx$, and the integral of a sum is the sum of the integrals.

### 2.2 Antiderivatives: the shortcut

Adding slices is slow. The shortcut is the reverse of the slope (derivative) of chapter
[04](04-cameras-and-projection.md), on depth precision: if $F$ is a function whose slope is $f$,
then

$$\int_a^b f(x)\,dx = F(b) - F(a)$$

Why: the area of a thin slice at $x$ is $f(x)\,dx$, and since $f$ is the slope of $F$, that is also
how much $F$ rises across the slice. Adding up all the rises from $a$ to $b$ gives the total rise of
$F$, which is $F(b) - F(a)$. $F$ is called an **antiderivative** of $f$, and $F(b) - F(a)$ is
written $\big[F(x)\big]_a^b$.

The chapter needs only these, each checkable by taking the slope of the right side:

| $f(x)$ | an antiderivative $F(x)$ | check |
|---|---|---|
| $x^n$ ($n \ne -1$) | $\dfrac{x^{n+1}}{n+1}$ | $\int_0^1 x^2\,dx = \big[\tfrac{x^3}{3}\big]_0^1 = \tfrac13$ ✓ |
| $\cos x$ | $\sin x$ | the slope of $\sin$ at 0 is 1 $= \cos 0$ |
| $\sin x$ | $-\cos x$ | $\int_0^{\pi/2}\sin x\,dx = -\cos\tfrac{\pi}{2} + \cos 0 = 1$ |
| $\sin x \cos x$ | $\tfrac12 \sin^2 x$ | $\int_0^{\pi/2} \sin x\cos x\,dx = \tfrac12$ |
| $\dfrac{1}{x}$ ($x > 0$) | $\ln x$ | used once, in §6.7, as $\ln(1 + c)$ for $1/(1 + c)$ |

(Angles are in radians throughout, chapter 03. A 10-slice midpoint sum of $\sin x \cos x$ over
$[0, \pi/2]$ gives $0.502$, and of $\sin x$ gives $1.001$: the shortcut and the loop agree.)

One more trick, **substitution**, lets you rename the variable: if $u = g(x)$ then a slice $dx$
becomes a slice $du = g'(x)\,dx$. You will see it used once, in §8.6, with every step written out.

### 2.3 Directions as two angles

A direction in 3D is a unit vector (chapter 02). Around a surface point with normal $\mathbf n$,
it is often easier to name a direction by two angles, like latitude and longitude on a globe with
the normal at the north pole:

```
                n (θ = 0)
                ▲
                │   ω
                │  ╱
                │θ╱
                │╱
   ─────────────●──────────── surface (θ = 90°)
               ╱ ╲
              ╱ φ ╲   φ: the angle around n, measured in the surface plane
```

- $\theta$ (theta), the **polar angle**: from the normal, 0 straight up, $\pi/2$ along the surface;
- $\phi$ (phi), the **azimuth**: the angle around the normal, from 0 to $2\pi$.

In a frame where $\mathbf n$ is the $z$ axis, the direction is

$$\boldsymbol\omega = (\sin\theta\cos\phi,\ \sin\theta\sin\phi,\ \cos\theta)$$

and $\mathbf n \cdot \boldsymbol\omega = \cos\theta$. You will see exactly this line in the BRDF
lookup table shader (§21) and the cooker's prefilter (§22): `vec3 h = vec3(sinTheta * cos(phi),
sinTheta * sin(phi), cosTheta)`.

### 2.4 Solid angle

An ordinary angle measures how much of a circle something covers: an arc of length $s$ on a circle
of radius $r$ is the angle $s/r$ radians, and the whole circle is $2\pi r / r = 2\pi$. A **solid
angle** does the same for a sphere: a patch of area $A$ on a sphere of radius $r$ covers the solid
angle

$$\Omega = \frac{A}{r^2}$$

measured in **steradians** (sr). The whole sphere is $4\pi r^2 / r^2 = 4\pi$ sr, a hemisphere
$2\pi$ sr. Solid angle is how big something looks from a point: the sun and the moon both cover
about $6.7 \times 10^{-5}$ sr from Earth, though one is four hundred times farther away.

**A small patch in the two angles.** Step $\theta$ by $d\theta$ and $\phi$ by $d\phi$ on a unit
sphere. The step in $\theta$ moves along a meridian by $d\theta$. The step in $\phi$ moves along a
circle of latitude, whose radius is $\sin\theta$ (it shrinks to 0 at the pole), so by
$\sin\theta\,d\phi$. The little patch is a rectangle of those two sides:

$$d\omega = \sin\theta\,d\theta\,d\phi$$

Check by adding up the whole hemisphere:

$$\int_0^{2\pi}\!\!\int_0^{\pi/2} \sin\theta\,d\theta\,d\phi = 2\pi \cdot \big[-\cos\theta\big]_0^{\pi/2} = 2\pi\,(0 + 1) = 2\pi\ \text{sr}$$

✓. (A double integral is a loop inside a loop: the inner integral over $\theta$ for each $\phi$,
then the outer one over $\phi$. When the inner result does not depend on $\phi$, the outer loop just
multiplies by $2\pi$.) The same calculation over $\theta \in [0, \alpha]$ gives the solid angle of a
cone of half-angle $\alpha$: $2\pi(1 - \cos\alpha)$. The sun's disc is about $0.53°$ across, so
$\alpha = 0.00463$ rad and $\Omega = 2\pi(1 - \cos 0.00463) = 6.72 \times 10^{-5}$ sr.

### 2.5 Two hemisphere integrals you will use

Most lighting integrals weight each direction by $\cos\theta$ (§3.3 says why). The
**cosine-weighted hemisphere** integral is

$$\int_{\Omega} \cos\theta\,d\omega = \int_0^{2\pi}\!\!\int_0^{\pi/2} \cos\theta\sin\theta\,d\theta\,d\phi = 2\pi \cdot \tfrac12 = \pi$$

using the table of §2.2. Remember this $\pi$: it is why a Lambertian surface divides by $\pi$ (§6.6),
why a uniform sky of radiance $L$ gives irradiance $\pi L$ (§8.2), and why ambient occlusion
divides by $\pi$ (§13.1).

The second is the plain hemisphere, $\int_\Omega d\omega = 2\pi$, from §2.4.

<details><summary>Check: what solid angle does a 10° cone (half-angle) cover, and what fraction of the hemisphere is that?</summary>

$\Omega = 2\pi(1 - \cos 10°) = 2\pi(1 - 0.98481) = 0.0955$ sr, which is $0.0955 / 2\pi = 1.5\,\%$
of the hemisphere. A cone that looks wide covers little of the sky.
</details>

## 3. Radiometry: measuring light

**Radiometry** is the measurement of light as energy. It has four quantities, and the shader works
with all four. Each answers a different question.

### 3.1 Energy and power

Light carries **energy**, measured in joules (J). What matters for brightness is energy per
second, **power**, measured in watts (1 W = 1 J/s). In radiometry the power of light is called
**radiant flux**, $\Phi$: "a bulb emits 5 W of light" is a flux.

### 3.2 Intensity and the inverse square law

A small light (a bulb, a torch flame) sends its flux out into directions. How much it sends into a
small cone, per steradian of that cone, is its **radiant intensity**:

$$I = \frac{d\Phi}{d\omega}\quad \text{(W/sr)}$$

A bulb that sends 5 W equally in all directions has $I = 5 / 4\pi = 0.40$ W/sr.

Now hold a small card of area $A$, facing the light, at distance $d$. From the light, the card
covers the solid angle $A/d^2$ (§2.4), so it catches the flux $I \cdot A / d^2$. Flux per unit
area of the card is

$$E_\perp = \frac{I A / d^2}{A} = \frac{I}{d^2}$$

This is the **inverse square law**: twice as far, a quarter of the light. The same flux spreads over
a sphere whose area grows as $d^2$. A torch of 12 cd (the unit of §3.5) gives 3 lux at 2 m and only
0.75 lux at 4 m.

### 3.3 Irradiance and the cosine law

Flux arriving per unit area of a surface is **irradiance**, $E$, in W/m². It answers "how much light
falls on this spot". $E_\perp$ above is the irradiance on a card *facing* the light.

Tilt the card by an angle $\theta$ away from the light. The same beam now spreads over a larger
area, $1/\cos\theta$ times larger:

```
   light rays ↓↓↓↓↓↓↓↓            ↓↓↓↓↓↓↓↓
              ────────            ╲
              card facing          ╲  card tilted by θ:
              the light             ╲ the same rays cover a
                                     ╲ strip 1/cos θ longer
```

so each square metre receives less:

$$E = E_\perp\cos\theta = E_\perp\,(\mathbf n\cdot\mathbf l)$$

where $\mathbf n$ is the unit surface normal and $\mathbf l$ the unit vector towards the light, so
that $\mathbf n \cdot \mathbf l = \cos\theta$ (chapter 02, on Lambert's cosine law, met it with
the dot product). This is the **cosine law**, and it is why "N dot L" appears in every lighting
formula. At $\theta = 60°$ a surface receives half the
light it would facing the source; at $90°$, grazing, none.

### 3.4 Radiance

Irradiance says how much light falls on a spot, from all directions together. A camera needs
something finer: how bright a point looks **from one direction**. That is **radiance**, $L$: flux
per unit area per unit solid angle, with the area measured perpendicular to the direction the light
travels:

$$L = \frac{d^2\Phi}{dA_\perp\,d\omega}\quad \text{(W/(m}^2\text{·sr))}$$

Why is this the right quantity for a pixel? A pixel looks through a fixed small cone. A wall twice
as far sends a quarter of the flux per square metre into the lens (inverse square), but the pixel's
cone now covers four times as much wall. The two cancel: **radiance does not change along a ray**
(in clean air), and a white wall looks equally bright at 2 m and at 4 m, only smaller. So radiance is
what a camera measures, what the eye judges as brightness, and what the lit shader writes into each
pixel.

Two relations tie the quantities together. Irradiance is radiance integrated over the incoming
directions, each weighted by the cosine law:

$$E = \int_\Omega L_i(\boldsymbol\omega)\,\cos\theta\,d\omega$$

and for a uniform sky of radiance $L$ that is $L \cdot \pi$, by §2.5. A small source of radiance $L_s$
that covers the solid angle $\Omega_s$ gives $E_\perp \approx L_s\,\Omega_s$ (§4.4 uses this).

### 3.5 Photometric units, and the ones Jeddy uses

The eye is not equally sensitive to all colors. **Photometry** measures light weighted by that
sensitivity, with its own units, one for each radiometric quantity:

| Radiometric | Unit | Photometric | Unit |
|---|---|---|---|
| radiant flux $\Phi$ | W | luminous flux | **lumen** (lm) |
| radiant intensity $I$ | W/sr | luminous intensity | **candela** (cd) = lm/sr |
| irradiance $E$ | W/m² | illuminance | **lux** (lx) = lm/m² |
| radiance $L$ | W/(m²·sr) | luminance | **nit** = cd/m² |

The conversion at the eye's most sensitive green is fixed by the SI definition of the candela: 683
lumens per watt. The formulas do not change between the two systems; only the labels do.

Jeddy follows glTF's `KHR_lights_punctual` extension, which uses photometric units:

- A **point or spot light** has a color and an **intensity in candela**: `RenderPacket.addPointLight`
  documents its `intensity` parameter as "luminous intensity in candela ({@code
  KHR_lights_punctual})". The packet stores color × intensity per channel. Divided by $d^2$
  (§7.1), that is an illuminance in lux.
- The **sun** has `sunColor`, documented as "Sun color times illuminance (linear RGB)": already an
  illuminance in lux, because the sun is so far away that it does not fall off (§7.3).
- The shader's output is then a luminance in nits, per channel.

A worked example with the demo's party light, 14 cd with color $(1, 0.75, 0.5)$ (`DemoGame.extract`
puts it 2.6 m above the party's centre; take a point of the floor 2 m below it): the red channel
receives $E = 14 / 2^2 = 3.5$ lux (§7.1's window trims it to 3.49), and a white diffuse floor sends
back $E/\pi = 1.11$ nits of red towards any viewer (§6.6 derives the $1/\pi$).

How physical are the engine's numbers? Not very, on purpose. Direct sunlight is on the order of
100,000 lux and a full moon well under 1 lux; the packet's default `sunColor` is `{3f, 2.9f, 2.7f}`
and the demo's crypt moonlight `{0.22f, 0.27f, 0.4f}`. That is fine: **automatic exposure** (chapter
[08](08-effects-and-post.md)) maps whatever absolute level a scene has to the display, so only the
**ratios** between lights matter. What would not be fine is mixing conventions: a sun "in lux" next
to torches "in arbitrary brightness". Using one convention everywhere keeps those ratios physical.

<details><summary>Check: a spot light of 50 cd shines straight down from 5 m onto a floor, and at a point 3 m to the side. What illuminance does each point receive (ignore the cone and the range window)?</summary>

Below the light: $E = 50 / 5^2 = 2$ lux, at $\theta = 0$. The side point is at distance $\sqrt{5^2 +
3^2} = 5.83$ m, and the light arrives at $\cos\theta = 5 / 5.83 = 0.857$ from the floor's normal:
$E = 50 / 34 \cdot 0.857 = 1.26$ lux.
</details>

## 4. The rendering equation

### 4.1 Light out = light emitted + light reflected

Take one point on a surface, with unit normal $\mathbf n$, and one direction towards the camera,
$\mathbf v$ (the **view vector**, unit, pointing from the point to the eye). The radiance leaving the
point towards the eye is what the surface emits by itself plus what it reflects of all the light
arriving at it:

$$L_o(\mathbf v) = L_e(\mathbf v) + \int_{\Omega} f(\mathbf l, \mathbf v)\; L_i(\mathbf l)\; (\mathbf n\cdot\mathbf l)\; d\omega_l$$

Symbol by symbol:

- $L_o(\mathbf v)$: outgoing radiance towards $\mathbf v$, what the pixel shows.
- $L_e(\mathbf v)$: emitted radiance (a glowing rune, lava). Zero for most surfaces.
- $\Omega$: the hemisphere of directions above the surface, the integral running over every
  incoming direction $\mathbf l$ in it, each covering a small solid angle $d\omega_l$.
- $L_i(\mathbf l)$: incoming radiance from direction $\mathbf l$: whatever is visible that way —
  a light, the sky, another surface.
- $(\mathbf n\cdot\mathbf l)$: the cosine law of §3.3, so $L_i(\mathbf l)(\mathbf n\cdot\mathbf l)\,
  d\omega_l$ is the irradiance arriving through the small cone around $\mathbf l$ (§3.4).
- $f(\mathbf l, \mathbf v)$: the **BRDF**, which says what fraction of that irradiance leaves
  towards $\mathbf v$.

This is **the rendering equation** (Kajiya, 1986). It is exact, and impossible to evaluate
directly: $L_i$ from another surface is that surface's $L_o$, which depends on its own integral, and
so on. Real-time rendering cuts that recursion with a few approximations; this section says which
ones Jeddy makes.

### 4.2 The BRDF

**BRDF** stands for *bidirectional reflectance distribution function*. It is a function of two
directions, the incoming $\mathbf l$ and the outgoing $\mathbf v$, defined as the ratio

$$f(\mathbf l, \mathbf v) = \frac{dL_o(\mathbf v)}{dE(\mathbf l)} = \frac{dL_o(\mathbf v)}{L_i(\mathbf l)\,(\mathbf n\cdot\mathbf l)\,d\omega_l}$$

"how much radiance goes out towards $\mathbf v$, per unit of irradiance coming in from $\mathbf l$".
Its unit is 1/sr (radiance over irradiance). It is not a probability and it can be much larger than
1: a mirror sends all the light from one direction into one direction, and its BRDF is a huge spike.

A physically plausible BRDF has two properties:

- **Reciprocity**: $f(\mathbf l, \mathbf v) = f(\mathbf v, \mathbf l)$. Light paths can be followed
  either way.
- **Energy conservation**: a surface cannot reflect more than it receives. For light arriving from
  any one direction $\mathbf v$, the total reflected over all outgoing directions is at most 1:

  $$\int_\Omega f(\mathbf l, \mathbf v)\,(\mathbf n\cdot\mathbf l)\,d\omega_l \le 1$$

  This integral is the BRDF's **directional albedo**. §6.7 computes it for Jeddy's BRDF, finds where
  it falls short of 1, and fixes it.

### 4.3 From the equation to pbr.frag: the plan

`pbr.frag` evaluates the rendering equation by cutting the incoming light $L_i$ into pieces, each
handled by a different technique, and adding the results. This table is the spine of the chapter:
every row is a section of Part A and a few lines of the shader.

| Piece of the equation | Approximation | `pbr.frag` | Where |
|---|---|---|---|
| $L_e$ | the material's emissive color × strength (× texture) | `color += m.emissive.rgb * m.emissive.w ...` | §5, §19 |
| $f$ | Cook–Torrance: Lambert diffuse + GGX/Smith/Schlick specular | `jeddyShade` (in `brdf.glsl`) | §6, §14 |
| $L_i$ from the sun | one direction, constant illuminance | `color += jeddyShade(s, l, jSunColor.rgb) * shadow;` | §7.3 |
| $L_i$ from torches, spells | point and spot lights, one direction each, inverse square | the loop over `jClusterLights` | §7.1–7.2, §10 |
| "is the light visible?" | shadow maps | `jeddySunShadow`, `jeddyLocalShadow` | §11 |
| $L_i$ from everything else, diffuse part | irradiance from nine spherical-harmonic numbers | `jeddyIrradiance(n) * s.diffuse / J_PI` | §8.2 |
| $L_i$ from everything else, specular part | prefiltered cube × split-sum lookup | `jeddyPrefiltered(r, roughness) * (s.f0 * dfg.x + dfg.y) * s.energy` | §8.4–8.7 |
| "is the sky visible?" | material occlusion × GTAO, specular occlusion | `ao`, `so` | §8.8, §13 |
| light bouncing between microfacets | energy compensation | `s.energy` | §6.7 |

What it **leaves out**: light that bounces off other surfaces and then reaches this one (indirect
light), except as baked into the environment cube; the environment is the same for every point of
the level (it is a sky, not a probe per room); and every light is a point (no area lights, no soft
shadows from size). Those are the usual limits of a forward renderer of this generation.

### 4.4 A punctual light turns the integral into one term

A **punctual light** is a light so small that all its light arrives from one direction $\mathbf l$:
a point, a spot, or the sun. Treat it as a tiny bright patch of radiance $L_s$ covering a tiny
solid angle $\Omega_s$ around $\mathbf l$. Inside the integral, $L_i$ is $L_s$ over that patch and 0
elsewhere, and across the patch $f$ and $\mathbf n\cdot\mathbf l$ hardly change, so:

$$\int_\Omega f\,L_i\,(\mathbf n\cdot\mathbf l)\,d\omega \approx f(\mathbf l, \mathbf v)\,(\mathbf n\cdot\mathbf l)\,L_s\,\Omega_s = f(\mathbf l, \mathbf v)\;E_\perp\;(\mathbf n\cdot\mathbf l)$$

using $E_\perp = L_s\,\Omega_s$ from §3.4. The integral collapses to **one evaluation of the BRDF**,
times the light's illuminance, times the cosine law. With several lights, the terms add:

$$L_o(\mathbf v) = L_e + \sum_{k} f(\mathbf l_k, \mathbf v)\;E_{\perp,k}\;(\mathbf n\cdot\mathbf l_k)\;V_k \;+\; \text{(environment)}$$

where $V_k$, between 0 and 1, says how much of light $k$ is visible from the point: its shadow. That
sum is the sun term and the light loop of `pbr.frag`, and the function that evaluates one term of it
is `jeddyShade` (§14). The rest of the light, from everywhere else, keeps its integral; §8 deals
with it.

## 5. The material model: metallic–roughness

A BRDF needs parameters: what makes stone different from gold. Jeddy uses glTF's
**metallic–roughness** model, the common language of art tools, so a material exported from Blender
looks the same in the engine. A material has:

- **Base color**, linear RGB plus alpha. For a **dielectric** (an electrical insulator: plastic,
  stone, wood, skin, cloth) it is the **diffuse albedo**: the fraction of light that enters the
  surface, scatters inside and comes back out, per channel. For a **metal** it is the color of the
  specular reflection (gold reflects more red than blue).
- **Metallic**, in $[0, 1]$: 0 for dielectrics, 1 for metals. Values in between exist for texture
  filtering at the border of a painted metal, and for transitions such as rust.
- **Roughness**, in $[0, 1]$: "perceptual" roughness, 0 a mirror, 1 fully rough. The BRDF formulas
  use $\alpha = \text{roughness}^2$; squaring makes the slider feel even to artists (half-way on the
  slider looks half-way between glossy and rough).

From those, the shader derives the two colors the BRDF uses:

$$c_{\text{diff}} = \text{baseColor}\,(1 - \text{metallic}), \qquad
F_0 = \operatorname{mix}(0.04,\ \text{baseColor},\ \text{metallic})$$

where $\operatorname{mix}(a, b, t) = a + (b - a)\,t$ (GLSL's `mix`), so $F_0$ is 0.04 for a
dielectric and the base color for a metal.

$F_0$ is the **specular reflectance at normal incidence**: the fraction of light a surface reflects
mirror-like when light hits it head-on. For a dielectric it follows from its **index of refraction**
$\eta$ (how much it slows light):

$$F_0 = \left(\frac{\eta - 1}{\eta + 1}\right)^2$$

Glass and most plastics have $\eta \approx 1.5$: $F_0 = (0.5 / 2.5)^2 = 0.04$, which is why 4 % is the
dielectric default; water ($\eta = 1.33$) gives $(0.33 / 2.33)^2 = 0.020$. A metal is different:
light that enters it is absorbed at once by its free electrons, so it has **no diffuse term** at all
($c_{\text{diff}} = 0$ when metallic is 1) and a strong, colored $F_0$.

The code, from `pbr.frag`:

```glsl
    float metallic = m.pbr.x;
    float roughness = m.pbr.y;
    float occlusion = 1.0;
#ifdef ORM_MAP
    vec3 orm = texture(jOrmMap, vec3(jeddySlotUv(m, 2u, vUv), float(m.layers.z))).rgb;
    occlusion = mix(1.0, orm.r, m.pbr.w);
    roughness *= orm.g;
    metallic *= orm.b;
#endif
// ...
    roughness = clamp(roughness, 0.045, 1.0);
    metallic = clamp(metallic, 0.0, 1.0);
// ...
    s.diffuse = base.rgb * (1.0 - metallic);
    s.f0 = mix(vec3(0.04), base.rgb, metallic);
    s.roughness = roughness;
    s.alpha = roughness * roughness;
```

- The **ORM texture** packs ambient **O**cclusion, **R**oughness and **M**etallic into its red, green
  and blue channels, as glTF does: one texture fetch for three maps. The material's factors multiply
  the texture values, so one texture serves several materials.
- `m.pbr.w` is the **occlusion strength**: `mix(1.0, orm.r, strength)` fades the baked occlusion in
  from "none" (1) to the full texture value.
- The **clamp at 0.045** keeps $\alpha = 0.045^2 = 0.002025$ and $\alpha^2 = 4.1 \times 10^{-6}$ away
  from zero. At $\alpha = 0$ the GGX formula of §6.3 is $0/0$ at the center of the highlight; just
  above it, the peak $1/(\pi\alpha^2)$ explodes. With the clamp the peak is 77,625: large but
  finite, and the highlight's half-width, $0.64\,\alpha$ radians (§6.3), is $0.07°$: narrow, but
  still many float steps of $\mathbf n \cdot \mathbf h$ wide. Filament, the renderer whose formulas Jeddy follows most
  closely, uses the same minimum.

The material row the shader reads (`JeddyMaterial` in `engine/material.glsl`) is 32 floats, 128
bytes (D41). Chapter [06](06-renderer-architecture.md), on the material table, builds it; the fields
this chapter reads:

| Floats | Field | Content |
|---|---|---|
| 0–3 | `baseColor` | linear RGBA factor |
| 4–7 | `emissive` | RGB factor, strength |
| 8–11 | `pbr` | metallic, roughness, normal scale, occlusion strength |
| 12–15 | `uvScaleOffset` | `KHR_texture_transform` scale and offset |
| 16–19 | `misc` | UV rotation, alpha cutoff |
| 20–23 | `layers` | array layers of base color, normal, ORM, emissive |
| 24–27 | `layers2` | occlusion layer, UV1 bit per slot, material flags |
| 28–31 | `reserved` | |

## 6. The Cook–Torrance BRDF, term by term

### 6.1 Microfacets and the half vector

A rough surface looks smooth from a distance, but under a microscope it is a landscape of tiny flat
**microfacets**, each a perfect mirror, tilted every which way. The surface's normal $\mathbf n$ is
their average; each facet has its own normal $\mathbf m$.

A mirror reflects light from $\mathbf l$ into $\mathbf v$ only if its normal lies exactly halfway
between them. That direction is the **half vector**:

$$\mathbf h = \frac{\mathbf l + \mathbf v}{\lVert \mathbf l + \mathbf v \rVert}$$

```
        l         h         v
         ╲        │        ╱
          ╲   θ_d │ θ_d   ╱      a facet whose normal is h reflects l into v:
           ╲      │      ╱       the angle in equals the angle out
            ╲     │     ╱
   ──────────●═══════════──────  ◀ the facet
```

So of all the facets, only those with $\mathbf m = \mathbf h$ send light from the light to the eye.
The specular BRDF is "how many facets point along $\mathbf h$" × "how many of those are neither
hidden from the light nor from the eye" × "how much each mirror reflects". Because $\mathbf h$
bisects them, $\mathbf l \cdot \mathbf h = \mathbf v \cdot \mathbf h$; call that cosine $\cos\theta_d$.

### 6.2 The specular formula, and where the 4 comes from

The **Cook–Torrance** microfacet BRDF is

$$f_{\text{spec}}(\mathbf l, \mathbf v) = \frac{D(\mathbf h)\; G(\mathbf l, \mathbf v)\; F(\mathbf v, \mathbf h)}{4\,(\mathbf n\cdot\mathbf l)\,(\mathbf n\cdot\mathbf v)}$$

- $D$: the **normal distribution function** (NDF), the density of facet normals around $\mathbf h$
  (§6.3).
- $G$: the **geometry** or **masking–shadowing** term, the fraction of those facets that are
  neither hidden from $\mathbf v$ (masked) nor from $\mathbf l$ (shadowed), in $[0, 1]$ (§6.4).
- $F$: the **Fresnel** reflectance of one mirror facet (§6.5).

The denominator is not arbitrary; here is where it comes from. Precisely, $D$ is defined so that
the facets of a patch of surface of area $A$ whose normals lie within a small solid angle
$d\omega_h$ around $\mathbf h$ have total area $A\,D(\mathbf h)\,d\omega_h$.

1. **Light caught by those facets.** Light of radiance $L_i$ arrives from $\mathbf l$ through a
   small cone $d\omega_l$. A facet of area $a$ facing $\mathbf h$ presents the area $a\,(\mathbf
   l\cdot\mathbf h)$ to it (§3.3), so the facets catch the flux
   $d\Phi = L_i\,d\omega_l\,(\mathbf l\cdot\mathbf h)\,A\,D(\mathbf h)\,d\omega_h$.
2. **Reflected flux.** A fraction $F$ of it is reflected, into the directions $\mathbf v$ that these
   facet normals produce.
3. **Into which solid angle?** Reflection doubles angles: if a facet normal tilts by $\delta$, the
   reflected ray tilts by $2\delta$. Measure angles from $\mathbf l$: a normal at angle $\theta_h$
   reflects into a direction at angle $2\theta_h$, at the same azimuth $\phi$. With §2.4's
   $d\omega = \sin\theta\,d\theta\,d\phi$:

   $$d\omega_v = \sin(2\theta_h)\,d(2\theta_h)\,d\phi = 2\sin\theta_h\cos\theta_h \cdot 2\,d\theta_h\,d\phi = 4\cos\theta_h\,d\omega_h$$

   using $\sin 2x = 2\sin x\cos x$ (chapter 03), and $\cos\theta_h = \mathbf l\cdot\mathbf h =
   \mathbf v\cdot\mathbf h$. So $d\omega_v = 4\,(\mathbf v\cdot\mathbf h)\,d\omega_h$: **the 4 is the
   doubling of angles by a mirror**, once for each of the two angles.
4. **Outgoing radiance.** Radiance is flux per solid angle per area perpendicular to $\mathbf v$
   (§3.4), and the patch seen from $\mathbf v$ has area $A\,(\mathbf n\cdot\mathbf v)$:

   $$dL_o = \frac{F\,d\Phi}{d\omega_v\;A\,(\mathbf n\cdot\mathbf v)} = \frac{F\,L_i\,d\omega_l\,(\mathbf v\cdot\mathbf h)\,A\,D\,d\omega_h}{4\,(\mathbf v\cdot\mathbf h)\,d\omega_h\;A\,(\mathbf n\cdot\mathbf v)} = \frac{F\,D\,L_i\,d\omega_l}{4\,(\mathbf n\cdot\mathbf v)}$$

5. **The BRDF** is $dL_o$ divided by the irradiance $L_i\,(\mathbf n\cdot\mathbf l)\,d\omega_l$ (§4.2),
   which gives $F D / (4 (\mathbf n\cdot\mathbf l)(\mathbf n\cdot\mathbf v))$. Facets hidden by
   other facets reflect nothing, which multiplies in $G$.

Jeddy folds $G$ and the denominator into one **visibility** term,

$$V = \frac{G}{4\,(\mathbf n\cdot\mathbf l)\,(\mathbf n\cdot\mathbf v)}, \qquad f_{\text{spec}} = D\,V\,F$$

because $V$ has a form (§6.4) that never divides by $\mathbf n\cdot\mathbf l$ or $\mathbf
n\cdot\mathbf v$, which reach zero at grazing angles.

### 6.3 D: the GGX distribution

Jeddy's NDF is **GGX** (Walter et al. 2007; the same function as Trowbridge and Reitz's of 1975):

$$D_{\text{GGX}}(\mathbf h) = \frac{\alpha^2}{\pi\left((\mathbf n\cdot\mathbf h)^2(\alpha^2 - 1) + 1\right)^2}$$

with $\alpha = \text{roughness}^2$ and $\mathbf n\cdot\mathbf h = \cos\theta_h$. Writing the
parenthesis with $\cos^2 + \sin^2 = 1$ shows its shape better:
$(\mathbf n\cdot\mathbf h)^2(\alpha^2 - 1) + 1 = \alpha^2\cos^2\theta_h + \sin^2\theta_h$.

- **At the center** ($\mathbf h = \mathbf n$, $\theta_h = 0$) the parenthesis is $\alpha^2$, so
  $D = 1/(\pi\alpha^2)$: the smoother the surface, the taller the peak.
- **Its width.** $D$ falls to half its peak where $\alpha^2\cos^2\theta + \sin^2\theta =
  \sqrt2\,\alpha^2$, that is $\sin\theta = \alpha\sqrt{(\sqrt2 - 1)/(1 - \alpha^2)} \approx 0.64\,\alpha$:
  the highlight's angular size is proportional to $\alpha$.
- **Its tail.** Far from the peak the parenthesis is about $\sin^2\theta$, so $D$ decays only like
  $\alpha^2 / \sin^4\theta$: slowly. A GGX highlight has a sharp core and a soft glow around it,
  which matches measured materials better than older lobes (Blinn–Phong, Beckmann) that die out
  quickly.

Numbers for roughness 0.5 ($\alpha = 0.25$, $\alpha^2 = 0.0625$):

| $\mathbf n\cdot\mathbf h$ | 1.0 | 0.95 | 0.9 | 0.8 | 0.5 |
|---|---|---|---|---|---|
| $D$ | 5.093 | 0.840 | 0.344 | 0.124 | 0.034 |

At $\mathbf n\cdot\mathbf h = 1$: $0.0625 / (\pi \cdot 0.0625^2) = 1/(\pi \cdot 0.0625) = 5.093$.
For roughness 0.2 the peak is 198.9; for roughness 1 ($\alpha = 1$) the parenthesis is 1 everywhere
and $D = 1/\pi$ in every direction: facets point every which way equally.

**Normalization.** $D$ is a density of facet *area*, and the facets of a patch, projected onto the
patch, must cover it exactly once. So the facet area times the cosine of each facet's tilt must add
up to the patch's area:

$$\int_\Omega D(\mathbf h)\,(\mathbf n\cdot\mathbf h)\,d\omega_h = 1$$

Check it for GGX. With §2.4, the integral is $2\pi\int_0^{\pi/2} D\cos\theta\sin\theta\,d\theta$.
Substitute $x = \cos^2\theta$ (§2.2): $dx = -2\cos\theta\sin\theta\,d\theta$, and $\theta$ from 0
to $\pi/2$ is $x$ from 1 to 0, so

$$2\pi\int_0^{\pi/2} \frac{\alpha^2\cos\theta\sin\theta\,d\theta}{\pi\,((\alpha^2 - 1)\cos^2\theta + 1)^2} = \int_0^1 \frac{\alpha^2\,dx}{((\alpha^2 - 1)x + 1)^2}$$

An antiderivative of $\alpha^2/((\alpha^2 - 1)x + 1)^2$ is $-\alpha^2 / \big((\alpha^2 - 1)((\alpha^2 -
1)x + 1)\big)$ (take its slope to check). Evaluate from 0 to 1:
$\frac{-\alpha^2}{(\alpha^2 - 1)\alpha^2} + \frac{\alpha^2}{\alpha^2 - 1} = \frac{-1 + \alpha^2}{\alpha^2
- 1} = 1$. ✓ For every $\alpha$. §8.6 reuses this exact integral, stopped part-way, to draw random
facet normals.

The code, from `engine/brdf.glsl`:

```glsl
float jeddyD_GGX(float NoH, float alpha) {
    float a2 = alpha * alpha;
    float f = (NoH * a2 - NoH) * NoH + 1.0;
    return a2 / (3.14159265358979 * f * f);
}
```

`(NoH * a2 - NoH) * NoH + 1.0` is $(\mathbf n\cdot\mathbf h)^2(\alpha^2 - 1) + 1$ rearranged into
two multiply-adds, which GPUs execute as single fused instructions (§14 walks through it).

### 6.4 G and V: Smith masking and shadowing

Facets hide each other. Seen from a grazing direction, the far slopes of the bumps are hidden behind
the near ones (**masking**); lit from a grazing direction, they lie in the near ones' shadow
(**shadowing**). Both matter more as the surface gets rougher and the angle flatter.

**Smith's model** assumes the facet heights are random and uncorrelated with their slopes, and gets
the fraction of facets visible from a direction $\mathbf w$ (the **masking function**) as

$$G_1(\mathbf w) = \frac{1}{1 + \Lambda(\mathbf w)}, \qquad \Lambda_{\text{GGX}}(\mathbf w) = \frac{-1 + \sqrt{1 + \alpha^2\tan^2\theta_w}}{2}$$

where $\theta_w$ is the angle between $\mathbf w$ and $\mathbf n$. $\Lambda$ (lambda) measures how
much of the surface is hidden: 0 when looking straight down ($\tan 0 = 0$), growing towards
grazing angles.

Combining the light and the eye, there are two choices:

- **Separable**: $G_2 = G_1(\mathbf l)\,G_1(\mathbf v)$, as if being visible and being lit were
  independent.
- **Height-correlated** (Heitz 2014): $G_2 = \dfrac{1}{1 + \Lambda(\mathbf v) + \Lambda(\mathbf
  l)}$. A facet high on the surface is likely both visible *and* lit, so the two are not
  independent; this form accounts for it and is the more accurate. Jeddy's direct lights use it.

**From $G_2$ to $V$ without a division by zero.** Let $c = \mathbf n\cdot\mathbf w = \cos\theta_w$.
Since $\tan^2\theta = \sin^2\theta/\cos^2\theta = (1 - c^2)/c^2$,

$$1 + \alpha^2\tan^2\theta_w = \frac{c^2 + \alpha^2 - \alpha^2c^2}{c^2} = \frac{c^2(1 - \alpha^2) + \alpha^2}{c^2}$$

so $\sqrt{1 + \alpha^2\tan^2\theta_w} = A_w / c$ with $A_w = \sqrt{c^2(1 - \alpha^2) + \alpha^2}$, and
$\Lambda(\mathbf w) = (A_w/c - 1)/2$. Then

$$1 + \Lambda(\mathbf v) + \Lambda(\mathbf l) = \tfrac12\left(\frac{A_v}{c_v} + \frac{A_l}{c_l}\right), \qquad
G_2 = \frac{2\,c_v c_l}{A_v c_l + A_l c_v}$$

and dividing by $4\,c_l c_v$:

$$V = \frac{G_2}{4\,c_l c_v} = \frac{0.5}{c_l A_v + c_v A_l} = \frac{0.5}{(\mathbf n\cdot\mathbf l)\sqrt{(\mathbf n\cdot\mathbf v)^2(1-\alpha^2)+\alpha^2} \;+\; (\mathbf n\cdot\mathbf v)\sqrt{(\mathbf n\cdot\mathbf l)^2(1-\alpha^2)+\alpha^2}}$$

No tangent, no division by a cosine: at grazing angles the denominator shrinks, but it is never 0
while $\alpha > 0$.

**Worked example**, roughness 0.5 ($\alpha = 0.25$), $\mathbf n\cdot\mathbf v = 0.8$,
$\mathbf n\cdot\mathbf l = 0.6$: $\Lambda(\mathbf v) = 0.0087$, $\Lambda(\mathbf l) = 0.0270$, so
$G_2 = 1/1.0358 = 0.9655$ and $V = 0.9655 / (4 \cdot 0.6 \cdot 0.8) = 0.503$. The closed form gives
the same: $A_v = \sqrt{0.64 \cdot 0.9375 + 0.0625} = 0.8139$, $A_l = \sqrt{0.36 \cdot 0.9375 +
0.0625} = 0.6325$, $V = 0.5 / (0.6 \cdot 0.8139 + 0.8 \cdot 0.6325) = 0.503$. Without any masking
$V$ would be $1/(4 \cdot 0.6 \cdot 0.8) = 0.521$: at moderate angles and roughness little light is
lost. The separable product of the exact $G_1$s gives 0.9653 here, almost the same; the difference
grows at grazing angles.

```glsl
float jeddyV_SmithGGXCorrelated(float NoV, float NoL, float alpha) {
    float a2 = alpha * alpha;
    float ggxL = NoV * sqrt((NoL - a2 * NoL) * NoL + a2);
    float ggxV = NoL * sqrt((NoV - a2 * NoV) * NoV + a2);
    return 0.5 / max(ggxV + ggxL, 1e-5);
}
```

`(NoL - a2 * NoL) * NoL + a2` is $c_l^2(1 - \alpha^2) + \alpha^2 = A_l^2$, so `ggxL` is
$c_v A_l$ and `ggxV` is $c_l A_v$. (The names say which square root each holds.)

**The other Smith you will meet.** The BRDF lookup table of §8.5 uses an older, cheaper
approximation: separable, with Schlick's form of $G_1$,

$$G_1(\mathbf w) \approx \frac{\mathbf n\cdot\mathbf w}{(\mathbf n\cdot\mathbf w)(1 - k) + k}, \qquad k = \frac{\alpha}{2}$$

(as in Karis's notes on Unreal Engine 4). It is lower than the exact Smith $G_1$: at $\alpha = 0.25$
it gives 0.970 against 0.991 at $\mathbf n\cdot\mathbf w = 0.8$, and 0.667 against 0.775 at 0.2. That
the table and the direct lights use different $G$ is a real inconsistency; §6.7 measures it and
exercise 6 removes it.

### 6.5 F: Fresnel–Schlick

Every surface becomes a mirror at grazing angles: look across a lake and it reflects the sky, look
straight down and you see the bottom. The physics is in Fresnel's equations; Schlick's
approximation of them is what real-time renderers use:

$$F(\mathbf v, \mathbf h) = F_0 + (1 - F_0)\,(1 - \mathbf v\cdot\mathbf h)^5$$

At $\mathbf v\cdot\mathbf h = 1$ (head-on) it is $F_0$; at 0 (grazing) it is 1. For $F_0 = 0.04$:

| $\mathbf v\cdot\mathbf h$ | 1 | 0.5 | 0.1 | 0 |
|---|---|---|---|---|
| $F$ | 0.040 | 0.070 | 0.607 | 1.000 |

($0.04 + 0.96 \cdot 0.5^5 = 0.04 + 0.96 \cdot 0.03125 = 0.07$.) It is evaluated with
$\mathbf v\cdot\mathbf h$, the angle at the facet, not $\mathbf n\cdot\mathbf v$: each facet is its own
mirror.

```glsl
vec3 jeddyF_Schlick(float VoH, vec3 f0) {
    float f = pow(1.0 - VoH, 5.0);
    return f + f0 * (1.0 - f);
}
```

`f + f0 * (1 - f)` equals $F_0 + (1 - F_0) f$, rearranged so that the one vector operation is a
multiply-add. $F_0$ is a `vec3`: a colored metal reflects each channel differently, and every
surface turns white at grazing angles. The caller passes $\mathbf l\cdot\mathbf h$, which equals
$\mathbf v\cdot\mathbf h$ (§6.1).

### 6.6 Diffuse: Lambert

Light that enters a dielectric scatters many times below the surface and comes out in all
directions equally: the surface looks equally bright from everywhere. A BRDF that is the same for
every $\mathbf l$ and $\mathbf v$ is a constant; the **Lambertian** BRDF is

$$f_{\text{diff}} = \frac{c_{\text{diff}}}{\pi}$$

Why $\pi$: under a uniform sky of radiance $L$, the reflected radiance is

$$\int_\Omega \frac{c}{\pi}\,L\cos\theta\,d\omega = \frac{c}{\pi}\,L \cdot \pi = c\,L$$

by §2.5. A white ($c = 1$) Lambertian surface reflects exactly what it receives: its directional
albedo (§4.2) is 1. Without the $\pi$ it would be 3.14 and create light.

**The full BRDF** of a Jeddy surface is $f = f_{\text{diff}} + f_{\text{spec}}$: the diffuse part
from the light that entered, the specular part from the light the facets reflected. Two
simplifications are common in real-time engines and Jeddy makes both: the diffuse part is not
reduced by the light the specular layer already reflected (physically, only the $1 - F$ that
entered can scatter back out), and Lambert ignores roughness. §6.7 measures what the first one costs.

### 6.7 Energy: what single scattering loses, and the white furnace

**What $f_{\text{spec}}$ loses.** The microfacet model counts a ray that hits a facet, bounces into
another facet and then leaves as lost: $G$ removes it. On rough surfaces that is a lot of light. Take
the **directional albedo** of the specular lobe alone, with $F = 1$ (a perfect, white mirror at
every facet):

$$E_{ss}(\mathbf n\cdot\mathbf v,\ r) = \int_\Omega D\,V\,(\mathbf n\cdot\mathbf l)\,d\omega_l$$

($ss$ for "single scattering"). A lossless lobe would give 1. An exact case shows how far off it is:
roughness 1 ($\alpha = 1$, so $D = 1/\pi$ everywhere, §6.3), viewed head-on ($\mathbf v = \mathbf n$).
Then $\Lambda(\mathbf v) = 0$, and with $\alpha = 1$, $A_w = 1$ and $\Lambda(\mathbf l) = (1/c_l -
1)/2$, so $G_2 = 1/(1 + (1 - c_l)/(2c_l)) = 2c_l/(1 + c_l)$. The integral becomes

$$E_{ss} = \int_\Omega \frac{1}{\pi}\,\frac{2c_l/(1 + c_l)}{4\,c_l\cdot 1}\,c_l\,d\omega_l = \frac{1}{2\pi}\int_\Omega \frac{c_l}{1 + c_l}\,d\omega_l = \int_0^1 \frac{c}{1 + c}\,dc$$

(the last step: $d\omega = \sin\theta\,d\theta\,d\phi$, the $\phi$ loop gives $2\pi$, and with $c =
\cos\theta$, $\sin\theta\,d\theta$ is a slice of width $dc$). Since $c/(1 + c) = 1 - 1/(1 + c)$, and
$\ln(1 + c)$ is an antiderivative of $1/(1 + c)$:

$$E_{ss} = \big[c - \ln(1 + c)\big]_0^1 = 1 - \ln 2 = 0.307$$

**69 % of the light is lost.** A rough white metal ball would render at less than a third of the
brightness it should have. Less rough, less loss: the values the engine's lookup table holds (§8.5,
computed with the cooker's algorithm, 128² entries, 512 samples) include

| $\mathbf n\cdot\mathbf v$ | roughness | $E_{ss}$ | $1/E_{ss}$ |
|---|---|---|---|
| 0.996 | 0.098 | 1.000 | 1.00 |
| 0.949 | 0.199 | 0.998 | 1.00 |
| 0.496 | 0.496 | 0.749 | 1.34 |
| 0.949 | 0.801 | 0.535 | 1.87 |
| 0.996 | 0.996 | 0.312 | 3.21 |

(The last row is $1 - \ln 2$ up to sampling noise and the half-texel offset.)

**The compensation.** The missing $1 - E_{ss}$ is light that bounced between facets more than once.
Most of it still leaves the surface. Turquin's approximation (2019), as Filament presents it, adds
it back by scaling the specular lobe:

$$f_{\text{spec}}' = f_{\text{spec}}\left(1 + F_0\left(\frac{1}{E_{ss}} - 1\right)\right)$$

For a white metal ($F_0 = 1$) the factor is $1/E_{ss}$, and the lobe's albedo becomes $E_{ss} \cdot
1/E_{ss} = 1$: exactly what a lossless white surface should reflect. For a dielectric ($F_0 = 0.04$)
it barely changes anything (1.088 even at roughness 1), because at each extra bounce a dielectric
facet reflects only about $F_0$ and lets the rest enter the surface, where it is the diffuse term's
business. $E_{ss}$ costs nothing extra: it is the sum of the two channels of the lookup table that
image-based lighting already reads (§8.5), so `pbr.frag` computes the factor once per pixel:

```glsl
    vec2 dfg = jeddyDfg(s.NoV, roughness);
    s.energy = 1.0 + s.f0 * (1.0 / max(dfg.x + dfg.y, 1e-4) - 1.0);
```

**The white furnace test.** The classic check of energy conservation: put a white object inside a
uniformly white environment ($L_i = 1$ from every direction) with no other light. A surface that
reflects all the light it receives, no more and no less, sends back $L_o = 1$ in every direction and
becomes **invisible** against the background. `SceneRendererTest.whiteFurnaceReflectsTheEnvironment`
renders four white spheres (metallic 0 and 1, roughness 0.2 and 0.8) inside
`environment().setUniform(1, 1, 1)` and asserts every channel of a pixel near each sphere's center
is $1 \pm 0.12$. What should it see? Computed from the formulas of §8 with the lookup-table values at
those pixels (a prediction; the test records only pass or fail):

| Sphere | $\mathbf n\cdot\mathbf v$ | $E_{ss}$ | specular | diffuse | total | without `s.energy` |
|---|---|---|---|---|---|---|
| metal, roughness 0.2 | 0.996 | 0.999 | 1.000 | 0 | **1.000** | 0.999 |
| metal, roughness 0.8 | 0.968 | 0.537 | 1.000 | 0 | **1.000** | 0.537 |
| dielectric, roughness 0.2 | 0.968 | 0.998 | 0.040 | 1.000 | **1.040** | 1.040 |
| dielectric, roughness 0.8 | 0.996 | 0.537 | 0.022 | 1.000 | **1.022** | 1.022 |

- **Metals come out at exactly 1.** For $F_0 = 1$ the image-based specular term is
  $1 \cdot E_{ss} \cdot (1/E_{ss}) = 1$ whatever the roughness, by construction (§8.5).
- **The rough metal fails without compensation**: 0.537 is far outside $1 \pm 0.12$. That is the
  bug the test is there to catch.
- **Dielectrics come out 2–4 % too bright**: the diffuse term returns all of the light (1.0) and the
  specular layer adds its 2–4 % on top, because of the first simplification of §6.6. The test's
  tolerance of 0.12 accepts it.

So the furnace proves the compensation for metals and bounds the dielectric error. It cannot see
that the lookup table's $G$ (separable Schlick–Smith) differs from the direct lights' $G$
(height-correlated): for image-based light both $E_{ss}$ and the factor come from the same table and
cancel. For direct light they do not. At $\mathbf n\cdot\mathbf v = 0.5$ and roughness 0.5 the table
gives $E_{ss} = 0.749$, the height-correlated term 0.860; a rough white metal under the sun therefore
gets the factor $1/0.749 = 1.34$ where $1/0.860 = 1.16$ would restore exactly the light it loses: it
reflects about 15 % more than it receives. At grazing angles on very rough surfaces the two differ
much more (0.61 against 0.97 at $\mathbf n\cdot\mathbf v = 0.004$, roughness 1). Exercise 6 makes
them consistent.

## 7. Punctual lights

§4.4 reduced each light to its illuminance $E_\perp$ at the point and its direction $\mathbf l$.
This section says how the shader gets those two from a light's record, for the three kinds of light.

### 7.1 Point lights: inverse square with a range window

A point light at position $\mathbf p$, with intensity $I$ (color × candela, per channel): for a
surface point $\mathbf x$, the vector to the light is $\mathbf p - \mathbf x$, its length $d$, and

$$\mathbf l = \frac{\mathbf p - \mathbf x}{d}, \qquad E_\perp = \frac{I}{d^2}$$

by §3.2. Pure inverse square never reaches zero: a torch would light every pixel of the level, a
little, and every light would belong to every cluster of §10. So Jeddy multiplies it by a
**window** that falls to exactly zero at the light's `range` $r$ (Karis 2013):

$$\text{att}(d) = \frac{\operatorname{saturate}\!\left(1 - (d/r)^4\right)^2}{\max(d^2,\ 10^{-4})}$$

where $\operatorname{saturate}(x)$ clamps $x$ to $[0, 1]$ (GLSL `clamp(x, 0.0, 1.0)`). Why this
shape:

- **The fourth power** keeps the window near 1 for most of the range: at half the range,
  $(1 - 0.5^4)^2 = 0.879$. The light keeps its physical falloff near the source and fades only
  towards the edge.
- **The outer square** makes the window reach 0 with zero slope, so there is no visible ring at
  the edge.
- **`max(d², 1e-4)`** caps the inverse square at 10,000 within 1 cm of the light, where
  $1/d^2$ would explode (a light inside a wall, a particle passing through the source).

With $r = 4$ m:

| $d$ (m) | 0.5 | 1 | 2 | 3 | 3.5 | 4 |
|---|---|---|---|---|---|---|
| $1/d^2$ | 4.000 | 1.000 | 0.250 | 0.111 | 0.082 | 0.0625 |
| window | 1.000 | 0.992 | 0.879 | 0.467 | 0.171 | 0 |
| att | 4.00 | 0.99 | 0.22 | 0.052 | 0.014 | 0 |

The demo's party light (14 cd, range 12 m) gives 3.49 lux at 2 m and 0.14 at 8 m, where the window
is 0.644, and nothing at 12 m. A range of 0 means "unlimited": inverse square only, and the light is
in every cluster (§10).

### 7.2 Spot lights: a cone in cosines

A spot light is a point light masked by a cone. With $\theta_s$ the angle between the spot's axis
$\mathbf a$ and the direction from the light to the surface point, an **inner angle** $\theta_i$
(full intensity inside it) and an **outer angle** $\theta_o$ (darkness outside it):

$$t = \operatorname{saturate}\!\left(\frac{\cos\theta_s - \cos\theta_o}{\cos\theta_i - \cos\theta_o}\right), \qquad \text{att}_{\text{spot}} = t^2$$

- $t$ is 1 inside the inner cone, 0 outside the outer, and goes between linearly *in the cosine*.
- Working with cosines avoids an `acos` per pixel and light: $\cos\theta_s$ is one dot product,
  $-\mathbf l \cdot \mathbf a$ ($-\mathbf l$ points from the light to the surface).
- Squaring softens the edge.

`RenderPacket.addSpotLight` stores $\cos\theta_o$ and $\cos(\min(\theta_i, \theta_o - 0.001))$, so
the denominator is never 0 even if a caller passes equal angles. With the test's spot (inner 0.6 rad,
outer 1.0 rad: $\cos = 0.825$ and $0.540$):

| $\theta_s$ (rad) | 0.5 | 0.6 | 0.7 | 0.8 | 0.9 | 1.0 |
|---|---|---|---|---|---|---|
| $t^2$ | 1 | 1.000 | 0.621 | 0.301 | 0.081 | 0 |

### 7.3 The sun: a directional light

The sun is so far away that its rays arrive parallel and its illuminance is the same everywhere in a
level: a **directional light**. Its direction $\mathbf l$ is constant (towards the sun) and its
$E_\perp$ is `sunColor` itself (color × illuminance, §3.5). The packet stores `sunDirection`, the
direction sunlight **travels** (so $\mathbf l$ is its negation), and `sunColor`, where all zeros turn
the sun off. `SceneRenderer.writeFrameConstants` normalizes the direction into `jSunDirection` and
sets `jSunColor.w` to 1 when any channel is positive (§15).

No range, no falloff, no cluster: one sun term per pixel, before the light loop. Its shadow is the
cascaded shadow map of §11.3.

<details><summary>Check: a point light of range 6 m and a surface 3 m away. By how much does the window dim it, compared with pure inverse square?</summary>

$(d/r)^4 = 0.5^4 = 0.0625$, window $= (1 - 0.0625)^2 = 0.879$: about 12 % dimmer. At 5 m,
$(5/6)^4 = 0.482$, window $= 0.518^2 = 0.268$.
</details>

## 8. Image-based lighting

### 8.1 Light from everywhere

Punctual lights model the strongest, most direct light. The rest arrives from everywhere at once:
the sky, the ground, the walls, light from the torches that bounced once already. For that part
the integral of §4 cannot collapse to one term, because $L_i$ is non-zero in every direction.

**Image-based lighting** (IBL) records that incoming light as an image: the radiance seen in every
direction from a point in the level, stored in a **cube map**, six square images on the faces of a
cube around the viewer, each texel the radiance arriving from the direction through its center.
Sampling a cube map takes a direction, not a texture coordinate:

```
          +Y                 direction (x, y, z): the face is the axis with the largest |component|
       ┌──────┐              ("major axis"), the coordinate on it the other two divided by that one:
  -X   │  +Z  │  +X   -Z     for +X: s = (−z/|x| + 1)/2, t = (−y/|x| + 1)/2, and so on for each face
       └──────┘
          -Y
```

The texture unit does this face selection and division in hardware (chapter
[05](05-opengl-from-java.md), on kinds of texture), then filters within the face like any 2D
texture. Near an edge, bilinear filtering wants texels from the neighbouring face;
`GL_TEXTURE_CUBE_MAP_SEAMLESS`, which `Environment` enables, makes the hardware fetch them, so blurry
mips show no seams at the cube's edges.

Evaluating the full integral per pixel would need hundreds of cube-map reads. Jeddy instead
precomputes three things, and §8.2–8.7 derive each:

| For | Precomputed | Size | Where |
|---|---|---|---|
| diffuse light | the **irradiance** for every normal, as 9 spherical-harmonic coefficients per channel | 27 floats | cooker (`irradianceSh9`) |
| specular light, the environment part | the environment **blurred by the GGX lobe**, one cube mip per roughness | a cube with mips, 256² faces | cooker (`prefilter`) |
| specular light, the BRDF part | a 2D **lookup table** of two numbers per (n·v, roughness) | 128² `RG16F` | GPU at startup (`brdf_lut.comp`, D43) |

Split by the two parts of the BRDF, the environment's contribution is

$$L_{\text{env}}(\mathbf v) = \underbrace{\int_\Omega \frac{c_{\text{diff}}}{\pi}\,L(\boldsymbol\omega)\,(\mathbf n\cdot\boldsymbol\omega)\,d\omega}_{\text{diffuse, §8.2}} \;+\; \underbrace{\int_\Omega f_{\text{spec}}(\boldsymbol\omega, \mathbf v)\,L(\boldsymbol\omega)\,(\mathbf n\cdot\boldsymbol\omega)\,d\omega}_{\text{specular, §8.4}}$$

### 8.2 Diffuse: irradiance in nine numbers

The Lambertian BRDF is a constant, so it comes out of the integral, and what is left depends only
on the normal:

$$L_{\text{diff}}(\mathbf n) = \frac{c_{\text{diff}}}{\pi}\,E(\mathbf n), \qquad E(\mathbf n) = \int_{\Omega(\mathbf n)} L(\boldsymbol\omega)\,(\mathbf n\cdot\boldsymbol\omega)\,d\omega$$

$E(\mathbf n)$ is the **irradiance** a surface facing $\mathbf n$ receives from the whole environment
(§3.4). It is the environment averaged over a hemisphere with cosine weights: a huge blur. Turning
the normal a little changes $E$ very little. A function that changes that slowly can be stored with
a handful of numbers, in the same way a smooth curve can be described by its first few Fourier
frequencies.

**Spherical harmonics.** The **spherical harmonics** (SH) $Y_{lm}$ are, for functions on a sphere,
what sines and cosines of increasing frequency are for a periodic curve: a set of basis functions
such that any reasonable function is a weighted sum of them. They come in **bands** $l = 0, 1, 2,
\ldots$; band $l$ has $2l + 1$ functions, indexed by $m = -l \ldots l$, and higher bands wiggle
faster. For a unit direction $(x, y, z)$, the first three bands (nine functions, "SH9") are simple
polynomials:

| Band | Functions | Shape |
|---|---|---|
| 0 | $Y_{00} = 0.282095$ | a constant: the average |
| 1 | $Y_{1,-1} = 0.488603\,y$, $Y_{10} = 0.488603\,z$, $Y_{11} = 0.488603\,x$ | one bright side, one dark side |
| 2 | $Y_{2,-2} = 1.092548\,xy$, $Y_{2,-1} = 1.092548\,yz$, $Y_{20} = 0.315392\,(3z^2 - 1)$, $Y_{21} = 1.092548\,xz$, $Y_{22} = 0.546274\,(x^2 - y^2)$ | two bright and two dark lobes |

**Where the constants come from.** They make each function **normalized**: $\int Y_{lm}^2\,d\omega =
1$ over the whole sphere. For band 0, $\int c^2\,d\omega = 4\pi c^2 = 1$ gives $c = 1/(2\sqrt\pi) =
0.282095$. For band 1, by symmetry $\int x^2\,d\omega = \int y^2\,d\omega = \int z^2\,d\omega$ and
the three add up to $\int (x^2 + y^2 + z^2)\,d\omega = \int 1\,d\omega = 4\pi$, so each is $4\pi/3$;
then $k^2 \cdot 4\pi/3 = 1$ gives $k = \sqrt{3/(4\pi)} = 0.488603$. Band 2 uses $\int x^2y^2\,d\omega =
4\pi/15$ in the same way: $\sqrt{15/(4\pi)} = 1.092548$. The functions are also **orthogonal**: the
integral of the product of two different ones is 0 (for example $\int x\,y\,d\omega = 0$, because the
sphere has as much of each sign).

Orthonormal functions work like the orthonormal axes of chapter 02: the weight of $Y_{lm}$ in a
function $L$ is its "dot product" with it,

$$L_{lm} = \int L(\boldsymbol\omega)\,Y_{lm}(\boldsymbol\omega)\,d\omega$$

and $L \approx \sum_{l,m} L_{lm}\,Y_{lm}$. That is the **projection** the cooker computes.

**The cosine blur scales each band.** $E(\mathbf n)$ is $L$ blurred by the **clamped cosine**
$\max(\mathbf n\cdot\boldsymbol\omega, 0)$, a kernel that looks the same from every side of its axis.
A theorem about such kernels, the **Funk–Hecke theorem**, says that blurring with a kernel
$k(\mathbf n\cdot\boldsymbol\omega)$ that depends only on the angle to its axis cannot move energy between
bands or between the $m$s of a band: it only multiplies band $l$ by a number $\hat A_l$, the kernel's
own weight in that band. The theorem is stated here without proof; here is why it is plausible, and
the sketch that gives the numbers. Each band is closed under rotation (turning a band-$l$ function
gives another band-$l$ function; for band 1, turning $0.488603\,y$ gives $0.488603$ times some other
unit direction's coordinate, a mix of $x$, $y$ and $z$). A blur that looks the same from every
direction commutes with rotations, so it maps each band to itself, and the only such map that treats
every direction alike is multiplication by a constant. To find that constant, blur one function of
the band whose profile along the axis is known: for the axis $\mathbf z$, the band-$l$ function
$Y_{l0}$ is $P_l(\cos\theta)$ times a constant, with $P_0 = 1$, $P_1 = t$, $P_2 = (3t^2 - 1)/2$ (the
Legendre polynomials; compare $Y_{20} = 0.315392\,(3z^2 - 1)$). The blurred value at the axis is the
integral of kernel × function over the sphere, which in §2.4's angles, with $t = \cos\theta$, is
$2\pi\int_{-1}^{1} k(t)\,P_l(t)\,dt$ divided by $P_l(1) = 1$. For the clamped cosine $k(t) =
\max(t, 0)$, that is $\hat A_l = 2\pi\int_0^1 t\,P_l(t)\,dt$. With §2.2's power rule:

$$\hat A_0 = 2\pi \cdot \tfrac12 = \pi, \qquad \hat A_1 = 2\pi \cdot \tfrac13 = \frac{2\pi}{3}, \qquad \hat A_2 = 2\pi \cdot \tfrac12\left(\tfrac34 - \tfrac12\right) = \frac{\pi}{4}$$

(For band 1 you can check the constant directly: blurring $L = c\,z$ with the clamped cosine gives,
at the axis, $\int \max(\cos\theta, 0)\,c\cos\theta\,d\omega = c \cdot 2\pi/3$ by the table of §2.2,
which is $\hat A_1$ times the function's value there.) Ramamoorthi and Hanrahan (2001) used this to
write the irradiance directly:

$$E(\mathbf n) = \sum_{l=0}^{2}\sum_{m=-l}^{l} \hat A_l\,L_{lm}\,Y_{lm}(\mathbf n), \qquad \hat A_0 = \pi,\quad \hat A_1 = \frac{2\pi}{3},\quad \hat A_2 = \frac{\pi}{4}$$

The next bands give $\hat A_3 = 0$ and $\hat A_4 = -\pi/24$, about 4 % of $\hat A_0$: the
blur almost erases everything above band 2. That is why nine numbers per channel are enough.

**Two checks with numbers.**

- *A uniform sky of radiance $L$.* Only band 0 is non-zero: $L_{00} = L \cdot Y_{00} \cdot 4\pi$, and
  $E = \hat A_0\,L_{00}\,Y_{00} = \pi \cdot 4\pi L\,Y_{00}^2 = \pi L$, since $Y_{00}^2 = 1/(4\pi)$:
  the $\pi L$ of §2.5 in every direction. ✓ `Environment.setUniform` uses exactly this shortcut
  (`coefficients[0] = r * scale` with `scale = (float) Math.PI / Y00`, §20), and the cooker test
  `uniformLightGivesPiIrradianceAndAFlatPrefilter` checks $E = 0.5\pi$ for a cube filled with 0.5.
- *A sky that is 1 above the horizon and 0 below* (light from $+y$ only). By symmetry only $Y_{00}$
  and $Y_{1,-1}$ (the $y$ one) get weight: $L_{00} = 0.282095 \cdot 2\pi = 1.7725$ (the hemisphere's
  solid angle), $L_{1,-1} = 0.488603 \int_{y>0} y\,d\omega = 0.488603\,\pi = 1.5350$ (the cosine
  integral of §2.5). Facing up:

  $$E(\text{up}) = \pi \cdot 1.7725 \cdot 0.282095 + \tfrac{2\pi}{3} \cdot 1.5350 \cdot 0.488603 = 1.5708 + 1.5708 = \pi$$

  and facing down the second term changes sign: $E(\text{down}) = 0$. Both exact, and the true
  values: a surface facing up sees the whole bright hemisphere ($\pi$), facing down none of it.
  A wall gets $\pi/2$. The cooker test `skyLightComesFromAbove` checks $E(\text{up}) = \pi \pm 0.25$
  and $E(\text{down}) < 0.25$ on a 16² cube, where the texel sums only approximate the integrals.

**Ringing.** A small, very bright spot in the environment (a sun disc) cannot be represented by
smooth low bands: their sum overshoots around it and dips below zero on the opposite side, as a
truncated Fourier series rings. Negative irradiance is meaningless, so the shader clamps:
`return max(e, vec3(0.0));` (§20).

### 8.3 The solid angle of a cube-map texel

To project a cube map onto SH the cooker sums over texels instead of integrating, so each texel's
radiance must be weighted by the solid angle it covers, and texels at the corners of a face cover
much less than those at its center: they are farther from the cube's center and seen at a slant.

Put the cube's center at the origin with half-size 1, so a face lies on the plane $z = 1$ and its
points are $(s, t, 1)$ with $s, t \in [-1, 1]$. An $N \times N$ face has texels of area $(2/N)^2$.
A small flat patch of area $\Delta A$ at distance $r$, whose normal makes the angle $\beta$ with the
line of sight, covers the solid angle $\Delta A \cos\beta / r^2$ (§2.4, tilted by the cosine law of
§3.3). Here $r = \sqrt{1 + s^2 + t^2}$ and the face normal $(0, 0, 1)$ makes $\cos\beta =
1/r$ with the direction $(s, t, 1)/r$. So

$$d\Omega = \frac{\Delta A}{r^3} = \frac{4/N^2}{(1 + s^2 + t^2)^{3/2}}$$

For a 256² face the center texel covers $6.10 \times 10^{-5}$ sr and a corner texel $1.18 \times
10^{-5}$ sr, $3^{1.5} = 5.2$ times less. Summed over all six faces the formula gives 12.578 for $N =
16$ and 12.566418 for $N = 256$, against $4\pi = 12.566371$: the texel sum converges on the integral.

### 8.4 Specular: the split-sum approximation

The specular integral depends on the view direction, the normal, the roughness and $F_0$: far too
many inputs to tabulate. Karis's **split sum** ("Real Shading in Unreal Engine 4", 2013) splits it
into a product of two factors, each tabulable:

$$\int_\Omega L(\mathbf l)\, f_{\text{spec}}(\mathbf l,\mathbf v)\,(\mathbf n\cdot\mathbf l)\,d\omega_l
\;\approx\;
\underbrace{\frac{\int_\Omega L(\mathbf l)\,D(\mathbf h)\,(\mathbf n\cdot\mathbf l)\,d\omega_l}{\int_\Omega D(\mathbf h)\,(\mathbf n\cdot\mathbf l)\,d\omega_l}}_{\text{prefiltered environment}}
\;\cdot\;
\underbrace{\int_\Omega f_{\text{spec}}(\mathbf l,\mathbf v)\,(\mathbf n\cdot\mathbf l)\,d\omega_l}_{\text{environment BRDF}}$$

The idea: if $L$ were constant over the lobe, $L$ would come out of the integral and the split would
be exact. It is approximately so when the lobe is narrow (glossy surfaces) or the environment smooth
(rough ones). The first factor is then a weighted average of $L$ over the lobe; the second is the
BRDF's directional albedo, which §6.7 already met.

**The first factor** is the environment blurred by the GGX lobe. It still depends on $\mathbf v$ and
$\mathbf n$; to make it depend on one direction only, it is computed **assuming $\mathbf n = \mathbf v
= \mathbf r$**, where $\mathbf r$ is the mirror reflection of the view vector. Then it depends on $\mathbf r$
and roughness, and can be stored in a cube map whose mip $i$ holds the blur for roughness
$i / (\text{levels} - 1)$: mip 0 is the sharp environment, the last mip the blur for roughness 1.
The price of the assumption is that reflections never stretch: in reality a rough floor seen at a
grazing angle reflects lights as long vertical streaks; with $\mathbf n = \mathbf v$ the lobe stays
round. Every split-sum renderer accepts this.

At run time one `textureLod` reads it, with the roughness choosing the mip:

```glsl
vec3 jeddyPrefiltered(vec3 r, float roughness) {
    return textureLod(jEnvironmentMap, r, roughness * max(jEnvironment.z - 1.0, 0.0)).rgb;
}
```

`jEnvironment.z` is the number of mips (`Environment.levels()`), so roughness 0 reads mip 0 and
roughness 1 the last mip; a trilinear sampler blends the two mips around a fractional level (chapter
05, on filtering). The default cooked cube has 256² faces and 7 levels (256 down to 4×4).

### 8.5 The environment BRDF: pulling F0 out

The second factor still depends on $F_0$, a color. Write Schlick's Fresnel (§6.5) as
$F = F_0 + (1 - F_0)F_c = F_0(1 - F_c) + F_c$ with $F_c = (1 - \mathbf v\cdot\mathbf h)^5$, and split
the integral in two:

$$\int f_{\text{spec}}\,(\mathbf n\cdot\mathbf l)\,d\omega = F_0 \underbrace{\int \frac{f_{\text{spec}}}{F}\,(1 - F_c)\,(\mathbf n\cdot\mathbf l)\,d\omega}_{A(\mathbf n\cdot\mathbf v,\ r)} + \underbrace{\int \frac{f_{\text{spec}}}{F}\,F_c\,(\mathbf n\cdot\mathbf l)\,d\omega}_{B(\mathbf n\cdot\mathbf v,\ r)}$$

$f_{\text{spec}}/F = DV$ no longer contains $F_0$, so $A$ (the **scale** of $F_0$) and $B$ (the
**bias**) depend only on $\mathbf n\cdot\mathbf v$ and roughness: a 2D table, the **BRDF lookup
table** (LUT), sometimes called the "DFG" table. Two facts fall out:

- $A + B = \int DV\,(\mathbf n\cdot\mathbf l)\,d\omega$ is the $E_{ss}$ of §6.7, which is how
  `s.energy` gets it for free.
- For $F_0 = 1$, the environment BRDF is $A + B = E_{ss}$, and times the energy factor $1/E_{ss}$ it
  is exactly 1: the furnace result of §6.7.

The shader combines everything:

```glsl
    vec3 r = reflect(-s.view, n);
    vec3 specular = jeddyPrefiltered(r, roughness) * (s.f0 * dfg.x + dfg.y) * s.energy;
```

`reflect(I, N)` is GLSL's $I - 2(\mathbf N\cdot I)\mathbf N$; with $I = -\mathbf v$ it gives $\mathbf r =
2(\mathbf n\cdot\mathbf v)\mathbf n - \mathbf v$, the mirror direction.

### 8.6 Monte Carlo and importance sampling

$A$, $B$ and the prefiltered cube are integrals without a closed form. They are estimated by
**Monte Carlo integration**: average the integrand at random points.

**Why averaging random samples gives an integral.** Draw a random direction $\mathbf x$ with a
probability density $p(\mathbf x)$ (the chance of landing in a small solid angle $d\omega$ around
$\mathbf x$ is $p\,d\omega$; densities integrate to 1). The mean (expected value, L02 §3.2) of the
quantity $f(\mathbf x)/p(\mathbf x)$ is its value weighted by the chances:

$$\mathbb E\!\left[\frac{f}{p}\right] = \int \frac{f(\mathbf x)}{p(\mathbf x)}\,p(\mathbf x)\,d\omega = \int f(\mathbf x)\,d\omega$$

So the average of $f(\mathbf x_i)/p(\mathbf x_i)$ over $N$ samples, $\frac{1}{N}\sum_i f(\mathbf x_i)/p(\mathbf x_i)$,
approaches the integral as $N$ grows. Any $p$ works, but not equally well: where $p$ is small and $f$
large, a rare sample brings a huge value, and the average jumps around. The noise is smallest when
$p$ has the same shape as $f$. Choosing $p$ that way is **importance sampling**. A GGX lobe is
concentrated around $\mathbf h = \mathbf n$, so the samples should be too.

**Drawing GGX half vectors.** Sample the half vector $\mathbf h$ with density $p(\mathbf h) =
D(\mathbf h)(\mathbf n\cdot\mathbf h)$, which integrates to 1 by the normalization of §6.3. The azimuth
$\phi$ does not matter to $D$, so it is uniform: $\phi = 2\pi\xi_1$ for a uniform random $\xi_1 \in [0,
1)$. For $\theta$, use the **inverse of the cumulative distribution**: the probability that $\theta_h$
is below some $\theta$ is the normalization integral of §6.3 stopped at $\theta$. In the variable $x =
\cos^2\theta$ that integral from $x$ to 1 is

$$P(\theta_h \le \theta) = \left[\frac{-\alpha^2}{(\alpha^2 - 1)((\alpha^2 - 1)t + 1)}\right]_{x}^{1} = \frac{1 - x}{(\alpha^2 - 1)x + 1}$$

(the same antiderivative; at $x = 0$ it is 1, at $x = 1$ it is 0). Setting it equal to a uniform
random $\xi_2$ and solving for $x$: $1 - x = \xi_2((\alpha^2 - 1)x + 1)$, so
$x(1 + (\alpha^2 - 1)\xi_2) = 1 - \xi_2$, and

$$\cos\theta_h = \sqrt{x} = \sqrt{\frac{1 - \xi_2}{1 + (\alpha^2 - 1)\,\xi_2}}$$

A uniform $\xi_2$ now lands on $\theta_h$ with exactly GGX's density: a value of $\theta$ that
covers more of the probability takes up more of the interval $[0, 1)$. Example, roughness 0.5
($\alpha^2 = 0.0625$): $\xi_2 = 0.5$ gives $\cos\theta_h = \sqrt{0.5 / 0.53125} = 0.970$, $14°$; $\xi_2 =
0.9$ gives $37°$. So half of the facet area of a roughness-0.5 surface faces within $14°$ of the
normal. The sample is turned into a direction with §2.3's formula.

**From $\mathbf h$ to $\mathbf l$.** The light direction is $\mathbf v$ reflected about $\mathbf h$:
$\mathbf l = 2(\mathbf v\cdot\mathbf h)\mathbf h - \mathbf v$. A density over $\mathbf h$ becomes a
density over $\mathbf l$ through the solid-angle stretch of §6.2, $d\omega_l = 4(\mathbf v\cdot\mathbf h)\,d\omega_h$:

$$p(\mathbf l) = \frac{p(\mathbf h)}{4\,(\mathbf v\cdot\mathbf h)} = \frac{D(\mathbf h)\,(\mathbf n\cdot\mathbf h)}{4\,(\mathbf v\cdot\mathbf h)}$$

**The estimator for the LUT.** One sample of the integrand of $A$ or $B$, divided by its density,
with $f_{\text{spec}}/F = DG/(4(\mathbf n\cdot\mathbf l)(\mathbf n\cdot\mathbf v))$:

$$\frac{(f_{\text{spec}}/F)\,(\mathbf n\cdot\mathbf l)}{p(\mathbf l)} = \frac{D\,G}{4(\mathbf n\cdot\mathbf l)(\mathbf n\cdot\mathbf v)}\,(\mathbf n\cdot\mathbf l)\cdot\frac{4(\mathbf v\cdot\mathbf h)}{D\,(\mathbf n\cdot\mathbf h)} = \frac{G\,(\mathbf v\cdot\mathbf h)}{(\mathbf n\cdot\mathbf h)(\mathbf n\cdot\mathbf v)}$$

$D$ cancels: every sample contributes a well-behaved number. This is `gVis` in `brdf_lut.comp`
(§21), with $G$ the separable Schlick–Smith term of §6.4; $A$ adds $(1 - F_c)\,\text{gVis}$ and $B$
adds $F_c\,\text{gVis}$.

**Better than random: the Hammersley points.** Random numbers clump and leave gaps. A
**low-discrepancy** sequence covers the square more evenly, and Monte Carlo with it converges
faster. Jeddy uses the **Hammersley** set of $N$ points, $\big(i/N,\ \text{radicalInverse}_2(i)\big)$:
the **radical inverse** writes $i$ in binary and mirrors the digits around the binary point, so
$1 = 1_2 \to 0.1_2 = 0.5$, $2 = 10_2 \to 0.01_2 = 0.25$, $3 = 11_2 \to 0.11_2 = 0.75$, $4 = 100_2
\to 0.001_2 = 0.125$. For $N = 4$ the points are $(0, 0)$, $(0.25, 0.5)$, $(0.5, 0.25)$, $(0.75,
0.75)$: one in each column and each row of a 4×4 grid. Reversing the bits of a 32-bit integer and
reading the result as a fraction of $2^{32}$ is exactly that mirror (L00 §6): GLSL's
`bitfieldReverse(i) * 2^-32`, Java's `Integer.reverse(i) >>> 1` divided by $2^{31}$.

### 8.7 Prefiltering without fireflies

The cooker's prefilter computes the first factor of §8.4 for every texel direction $\mathbf n$ of
every mip: it draws GGX samples around $\mathbf n$ (with $\mathbf v = \mathbf n$), reads the
environment along each $\mathbf l$, and averages with the weight $\mathbf n\cdot\mathbf l$ (Karis's
weighting; the importance-sampling density supplies the $D$).

With a modest number of samples, a small bright spot in the environment (the sun disc) is hit by one
sample of one texel and missed by its neighbour's: the result sparkles with isolated bright texels,
**fireflies**. The cure is **filtered importance sampling** (Colbert and Křivánek, *GPU Gems 3*): a
sample drawn where the density is low stands for a large solid angle, so it should read a
**blurrier** version of the source, which already averages that solid angle. With $N$ samples, a
sample of density $p$ represents about

$$\Omega_s = \frac{1}{N\,p}$$

steradians (the $N$ samples share the probability, and $p$ is probability per steradian). A texel
of the source mip 0 covers on average $\Omega_p = 4\pi / (6W^2)$, the sphere shared by the
$6W^2$ texels of a $W \times W$ cube. Each mip level up has a quarter as many texels, each covering 4
times the solid angle, so the level whose texels match $\Omega_s$ is $\log_4(\Omega_s / \Omega_p) =
\tfrac12 \log_2(\Omega_s / \Omega_p)$. Jeddy adds a bias of one level towards blur:

$$\text{lod} = \tfrac12 \log_2\frac{\Omega_s}{\Omega_p} + 1$$

**Worked**, with the cooker's defaults ($W = 256$, $N = 128$ samples, 7 levels): $\Omega_p = 4\pi /
(6 \cdot 256^2) = 3.20 \times 10^{-5}$ sr. For mip 3 (roughness 0.5, $\alpha = 0.25$), a sample at
the lobe's center has $D = 5.093$, density $p = D/4 = 1.273$ (with $\mathbf v = \mathbf n$,
$\mathbf n\cdot\mathbf h = \mathbf v\cdot\mathbf h$ and the density of §8.6 is $D/4$), so $\Omega_s =
1/(128 \cdot 1.273) = 6.14 \times 10^{-3}$ sr and $\text{lod} = \tfrac12\log_2(192) + 1 = 4.79$. A sample
at $\mathbf n\cdot\mathbf h = 0.9$ ($D = 0.344$) reads level 6.74. For mip 1 (roughness 1/6) the
center sample reads level 1.62. The source chain (`mips(base)`) goes down to 1×1, and the level is
clamped to its last mip.

### 8.8 Specular occlusion

Ambient occlusion (§13) says what fraction of the sky a point sees, and `pbr.frag` multiplies the
diffuse environment light by it. For specular light that would be wrong: a glossy reflection comes
from a narrow cone around $\mathbf r$, which may be wide open even in a crevice, or blocked even on
an open floor at a grazing view. "Moving Frostbite to PBR" (Lagarde and de Rousiers, 2014) gives an
**empirical fit**, not a derivation, for a **specular occlusion** $so$ from $ao$, $\mathbf n\cdot\mathbf
v$ and roughness $r$, chosen to behave as described below:

$$so = \operatorname{saturate}\!\left((\mathbf n\cdot\mathbf v + ao)^{\,2^{-16r - 1}} - 1 + ao\right)$$

- With no occlusion ($ao = 1$) the power is at least 1, and $so = 1$.
- For a rough surface the exponent is tiny ($2^{-17}$ at $r = 1$), the power is about 1, and $so
  \approx ao$: a rough reflection gathers from the whole hemisphere, like diffuse light.
- For a glossy one ($r = 0$, exponent $1/2$), with $ao = 0.5$: seen head-on ($\mathbf n\cdot\mathbf
  v = 1$), $so = \sqrt{1.5} - 0.5 = 0.72$, more than $ao$; at a grazing view ($\mathbf n\cdot\mathbf
  v = 0.1$), $so = \sqrt{0.6} - 0.5 = 0.27$, less.

```glsl
    float so = clamp(pow(s.NoV + ao, exp2(-16.0 * roughness - 1.0)) - 1.0 + ao, 0.0, 1.0);
    color += (diffuse * ao * jEnvironment.y + specular * so * jEnvironment.x);
```

`jEnvironment.x` and `.y` are the packet's `environmentSpecular` and `environmentDiffuse` scales (the
demo's crypt turns them down to 0.15 and 0.12). AO affects only environment light: direct lights
have their own shadows.

## 9. Normal mapping

A **normal map** adds detail the mesh does not have — mortar between stones, wood grain, the
dents of a shield — by tilting the shading normal per pixel. It stores a direction per texel in
**tangent space**: $x$ along the texture's $u$ direction on the surface (the **tangent**
$\mathbf t$), $y$ along $v$ (the **bitangent** $\mathbf b$), $z$ out of the surface (the normal
$\mathbf n$). A texel $(x, y, z)$ means "this much along $\mathbf t$, $\mathbf b$ and $\mathbf n$".

The vertex side is built in earlier chapters, and this chapter only uses it:

- Each vertex stores its whole tangent frame as a **QTangent**, a unit quaternion in four
  `snorm16` values whose sign carries the bitangent's handedness (D27); chapter
  [03](03-rotations-and-transforms.md), on a tangent frame in one quaternion, encodes and decodes it
  (`jeddyTangentFrame` in `engine/vertex.glsl`).
- `pbr.vert` moves the normal into world space with the inverse transpose of the instance's matrix,
  through the cofactor trick (`jeddyTransformNormal`), so that non-uniform scale keeps it
  perpendicular to the surface (chapter [02](02-math-foundations.md), on why normals need the
  inverse transpose), moves the tangent like any direction, and multiplies the handedness by
  $-1$ for mirrored instances (chapter 03, on mirrored instances and normal maps).

For reference, the two functions that do it. From `engine/vertex.glsl`:

```glsl
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

`jeddyQuatRotate` is the expanded form of rotating $\mathbf v$ by the quaternion $q$,
$\mathbf v' = \mathbf v + 2w(\mathbf q_{xyz}\times\mathbf v) + 2\,\mathbf q_{xyz}\times(\mathbf
q_{xyz}\times\mathbf v)$ (chapter 03, on rotating a vector with fewer multiplications): the frame's
rotation maps $+z$ to the normal and $+x$ to the tangent, and the sign of $w$ is the handedness. From
`engine/instance.glsl`:

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

The cofactor matrix equals $\det(M)\,M^{-T}$ and needs no division; `normalize` removes the
$\det(M)$, and the sign flip keeps a mirrored instance's normals pointing outwards. Under a
non-uniform scale such as $(2, 1, 1)$, a surface tilted 45° in the $xy$ plane gets flatter, and a
normal transformed like an ordinary direction would no longer be perpendicular to it: that is why
the normal needs this matrix and the tangent does not.

The fragment side is the lighting's business. From `pbr.frag`:

```glsl
    vec3 n = normalize(vNormal);
#ifdef DOUBLE_SIDED
    if (!gl_FrontFacing) {
        n = -n;
    }
#endif
#ifdef NORMAL_MAP
    vec2 xy = texture(jNormalMap, vec3(jeddySlotUv(m, 1u, vUv), float(m.layers.y))).rg * 2.0 - 1.0;
    xy *= m.pbr.z;
    vec3 tn = vec3(xy, sqrt(clamp(1.0 - dot(xy, xy), 0.0, 1.0)));
    vec3 t = normalize(vTangent.xyz - n * dot(n, vTangent.xyz));
    vec3 b = cross(n, t) * vTangent.w;
    n = normalize(t * tn.x + b * tn.y + n * tn.z);
#endif
```

Line by line:

1. **Renormalize.** The rasterizer interpolated the three vertices' normals linearly across the
   triangle (chapter 05, on interpolation), and the blend of unit vectors is shorter than 1.
2. **Back faces.** A double-sided material (a banner, a leaf) is seen from behind; its normal must
   then point the other way. `gl_FrontFacing` is false for back-facing triangles.
3. **Decode two channels.** Normal maps are cooked to **BC5**, which stores two channels in 16 bytes
   per 4×4 block (chapter 05, on block compression): half the size of storing three, and each
   channel gets its own endpoints. The texture holds $(x, y)$ remapped from $[-1, 1]$ to $[0, 1]$;
   `* 2.0 - 1.0` undoes that. A stored $(0.75, 0.5)$ becomes $(0.5, 0)$.
4. **Normal scale.** `m.pbr.z` exaggerates or flattens the bumps; glTF calls it the normal texture's
   `scale`.
5. **Rebuild $z$.** A unit vector satisfies $x^2 + y^2 + z^2 = 1$ and tangent-space normals point
   out of the surface ($z \ge 0$), so $z = \sqrt{1 - x^2 - y^2}$. For $(0.5, 0)$: $z = 0.866$, a tilt
   of $30°$ towards $+\mathbf t$. The `clamp` matters when the normal scale pushes $x^2 + y^2$ past
   1: $z$ becomes 0 instead of the square root of a negative number (NaN, L00 §7.3), and the final
   `normalize` restores unit length.
6. **Gram–Schmidt.** Interpolation also leaves $\mathbf t$ slightly non-perpendicular to $\mathbf n$.
   Subtracting $\mathbf t$'s component along $\mathbf n$ and normalizing,
   $\mathbf t' = \operatorname{normalize}(\mathbf t - \mathbf n(\mathbf n\cdot\mathbf t))$, makes it
   perpendicular again (chapter 02, on projections).
7. **The bitangent** is $(\mathbf n\times\mathbf t')\,w$, with $w = \pm 1$ the handedness: mirrored
   UVs reuse a texture flipped, and their bitangent points the other way. The cooker computes it in
   glTF's convention, where the bitangent follows $-v$ (D33).
8. **Change of basis.** The world normal is the matrix $[\mathbf t'\ \mathbf b\ \mathbf n]$ times the
   tangent-space vector: $t_x\mathbf t' + t_y\mathbf b + t_z\mathbf n$.

**Only the shading normal uses the map.** Shadow lookups (the normal offset of §11.2) and decals use
`vNormal`, the interpolated geometric normal: those depend on where the surface really is, and a
painted dent does not move it. And a normal-mapped normal can face away from the viewer even on a
visible surface; `s.NoV = max(dot(n, s.view), 1e-4)` keeps the BRDF and the LUT lookup defined
there.

## 10. Many lights: clustered forward+

### 10.1 Why clusters

The light loop of §4.4 has one term per light. A renderer that evaluates every light at every pixel
pays pixels × lights: at the demo's 1600 × 900 and its performance scene's 256 lights, that is
$1{,}440{,}000 \times 256 = 369$ million BRDF evaluations a frame, almost all of them for lights far
from the pixel, whose range window (§7.1) is 0 there. Spells that light up a battle make hundreds of
lights normal. The options:

- **Deferred shading** first writes every pixel's material properties (base color, normal,
  roughness …) to a set of screen-sized textures, the **G-buffer**, then lights each pixel with only
  the lights that touch it. It costs a lot of memory bandwidth (several full-screen textures written
  and read every frame), and it makes transparency (a G-buffer holds one surface per pixel) and
  hardware anti-aliasing awkward.
- **Tiled forward**, also called **forward+**, divides the screen into 2D tiles and builds, per
  tile, the list of lights that touch it; the ordinary forward shader then loops only over its
  tile's list. But a tile spans all depths: a torch near the camera and a wall 50 m behind it share
  one list, and at silhouettes lists get long.
- **Clustered shading** (Olsson, Billeter and Assarsson, 2012) also slices each tile in depth. Each
  3D **cluster** is a small chunk of the view frustum, and its list holds only the lights that
  overlap that chunk.

Jeddy uses **clustered forward**: a compute pass builds the per-cluster lists each frame, and the
forward shader reads its pixel's list. It keeps forward shading's flexibility — any material,
transparency through the same shader (the transparent pass reads the same lists), no G-buffer — and
gets per-pixel light counts close to deferred's. Decals ride in the same lists (D45, chapter
[08](08-effects-and-post.md)): one cull pass and one lookup per pixel serve both.

### 10.2 Slicing depth geometrically

From `LightClusters`: `TILE = 64` pixels and `SLICES = 24`. The grid has
$\lceil W/64 \rceil \times \lceil H/64 \rceil \times 24$ clusters (the round-up of L00 §2.5): at
1600 × 900, $25 \times 15 \times 24 = 9000$; at 1920 × 1080, $30 \times 17 \times 24 = 12{,}240$.

**How should the depth range $[n, f]$ be cut?** In a perspective view a screen tile's width in world
units grows in proportion to depth: at depth $z$ a tile of 64 pixels is $z \cdot 2\tan(\text{fov}/2)
\cdot 64/H$ wide. For clusters to keep the same shape at every distance, their depth thickness
should grow in proportion too, $\Delta z = c\,z$. Then each slice boundary is the previous one times
$(1 + c)$, a constant ratio $q$, and after $S$ slices from $n$ you reach $f$:

$$z_k = n\,q^k, \qquad q^S = \frac{f}{n} \;\Rightarrow\; z_k = n\left(\frac{f}{n}\right)^{k/S}$$

This is **geometric** (exponential) spacing: thin slices near the camera, thick ones far away. To
find the slice of a fragment at view depth $z$, solve for $k$ by taking logarithms ($\ln(a^b) =
b\ln a$):

$$k(z) = S\,\frac{\ln(z/n)}{\ln(f/n)} = \ln z\cdot\underbrace{\frac{S}{\ln(f/n)}}_{\text{scale}} + \underbrace{\left(-\frac{S\ln n}{\ln(f/n)}\right)}_{\text{bias}}$$

using $\ln(z/n) = \ln z - \ln n$. The CPU computes scale and bias once per frame
(`SceneRenderer.writeFrameConstants`, §15); the shader does one `log` and one multiply-add per pixel.

With the packet's defaults, $n = 0.1$, $f = 200$, $S = 24$: scale $= 24/\ln 2000 = 3.158$, bias $=
7.270$, and each slice is $2000^{1/24} = 1.373$ times deeper than the one before:

| $k$ | 0 | 3 | 6 | 9 | 12 | 15 | 18 | 21 | 24 |
|---|---|---|---|---|---|---|---|---|---|
| $z_k$ (m) | 0.1 | 0.26 | 0.67 | 1.73 | 4.47 | 11.6 | 29.9 | 77.3 | 200 |

A fragment at $z = 10$ m: $k = \ln 10 \cdot 3.158 + 7.270 = 14.54$, slice 14.

The projection is infinite reversed-Z (chapter [04](04-cameras-and-projection.md), on reversed and
infinite depth), so $f$, the packet's `far`, is not a clipping plane: it only says how far lights are
clustered. The demo sets `p.near = cam.near` (0.5 m) and `p.far = cam.distance * 2 + 60`. **The
demo's camera** (`DemoGame.init`: pitch 52°, distance 22 m, zoom range 8–48 m; `IsoCamera`'s own
defaults are 55° and 20 m, and `--perf` uses 30 m) therefore clusters up to 104 m at its starting
zoom. Its slices are then $208^{1/24} = 1.249$ times deeper each, and at the camera's distance of
22 m a cluster is 0.84 m wide (a 64-pixel tile at fov 30°, 900 pixels high: $22 \cdot 2\tan 15° \cdot
64/900$) and 5.5 m deep: much longer than wide. For an isometric camera that is no loss, because
the visible ground lies at view depths from about 18.2 to 27.8 m (§11.3 computes them), which is
only three slices (16, 17, 18: 17.6–21.9, 21.9–27.4 and 27.4–34.2 m); most of the culling is done
by the tiles.

With an **orthographic** camera, tiles do not grow with depth, and the slicing is linear: $k = S(z -
n)/(f - n)$.

### 10.3 The box of a cluster

To test lights against a cluster, the cluster needs a shape. Jeddy uses its **axis-aligned bounding
box** (AABB) in view space: the smallest box with faces parallel to the view axes that contains it.

A cluster is a frustum piece: the set of view rays through its tile's pixel rectangle, cut between
two slice depths. Its eight corners are where the four corner rays of the tile cross the two depth
planes, and since the piece is convex (any segment between two of its points stays inside), the AABB
of the eight corners is the AABB of the whole cluster. Each corner ray comes from **unprojecting**:
the tile's pixel rectangle becomes an NDC rectangle ($\text{ndc} = \text{pixel}/\text{size} \cdot 2 -
1$), and the inverse projection turns two NDC points on the same pixel, at depths 1 (the near plane
in reversed-Z) and 0.5, into two view-space points on the ray (chapter 04, on unprojection, says why
not depth 0: in an infinite projection it maps to a point at infinity). The point at view depth $z$
on the line through them is found by linear interpolation along $z$. The same construction works for
an orthographic projection, whose rays are parallel.

Example, the demo at 1600 × 900 (far 104 m): the tile at column 12, row 7 (pixels 768–832 by 448–512,
the middle of the screen) in slice 17 (21.92 to 27.39 m) has the box from $(-0.522, -0.033,
-27.39)$ to $(0.522, 1.011, -21.92)$, in metres of view space (the camera looks along $-z$): the
corner rays at NDC $x = \pm 0.04$ and $y = -0.0044$ or $0.138$ (pixel rows 448 and 512 of 900), cut
at the two depths, with $\tan 15° = 0.268$ and the aspect ratio $16/9$: at 27.39 m the $x$ extent is
$0.04 \cdot 0.268 \cdot \tfrac{16}{9} \cdot 27.39 = 0.522$ m and the top $0.138 \cdot 0.268 \cdot
27.39 = 1.011$ m.

The boxes depend only on the projection, the viewport and the slicing, not on the camera's position:
view space moves with the camera. So they are computed by a compute shader only when those change
(§23 and, for one way that check falls short, §37).

### 10.4 Does a sphere touch a box?

Each light's influence is a sphere: its position and its range (§7.1). A sphere of center
$\mathbf c$ and radius $r$ touches a box exactly when the point of the box closest to $\mathbf c$ is
within $r$ of it. For an axis-aligned box from $\mathbf{lo}$ to $\mathbf{hi}$ that closest point is
found one axis at a time: clamp each coordinate of $\mathbf c$ into the box's interval on that axis.
If $c_x$ is inside $[lo_x, hi_x]$ it stays; if it is below, the closest $x$ is $lo_x$; above,
$hi_x$. So

$$\mathbf d = \mathbf c - \operatorname{clamp}(\mathbf c, \mathbf{lo}, \mathbf{hi}), \qquad \text{touches} \iff \mathbf d\cdot\mathbf d \le r^2$$

comparing squared lengths saves a square root. Example: the box above and a torch at view-space
$(1.5, 0.5, -24)$ with range 1.2 m: the clamp gives $(0.522, 0.5, -24)$, $\mathbf d = (0.978, 0, 0)$,
$\mathbf d\cdot\mathbf d = 0.956 \le 1.44$: it touches. The test is exact for the box but the box is
larger than the cluster (a frustum piece is narrower at its near end), so some lights are listed in
clusters they do not actually reach; they cost a few wasted iterations, never a missing light.

### 10.5 Building lists on the GPU

`light_cull.comp` runs one invocation per cluster (§25). Each tests every light's sphere against its
box, then every decal's, collecting hits in a small local array, and then appends them to **one
shared list** for the whole grid:

```
 cluster 0: hits {3, 7}       atomicAdd(count, 2) → 0     list: [3 7 . . . . . . .]
 cluster 1: hits {7}          atomicAdd(count, 1) → 5     list: [3 7 5 1 9 7 . . .]
 cluster 2: hits {5, 1, 9}    atomicAdd(count, 3) → 2     ranges: c0 (0,2), c1 (5,1), c2 (2,3)
```

`atomicAdd` (chapter [06](06-renderer-architecture.md), on counting and reserving at once) adds to a
counter in memory indivisibly and returns the value it had before: one operation reserves a
contiguous block of the list for this cluster, however many other invocations do the same at the
same moment. The list is compact — no fixed number of slots per cluster, so a crowded cluster does
not waste the room of an empty one — and the blocks' order changes from frame to frame with the
GPU's scheduling, which does not matter: each cluster records its own `(offset, count)`. Within a
block, lights keep their ascending index order, so the floating-point sum of the light loop is the
same every frame.

**What the hardware does.** The 9000 invocations run in workgroups of 64; within a group the GPU
executes them in lockstep lanes (chapter 05, on compute shaders). Each loops over all lights, so the
work is clusters × lights sphere tests (2.3 million at 256 lights, 13.5 million at 1500), simple
arithmetic that GPUs are built for. The local `hits` array, 160 entries of 4 bytes per invocation, is
indexed by a variable; shader compilers usually cannot keep such an array in registers and place it
in per-invocation scratch memory that goes through the GPU's caches. The atomic counter lives in one
place in memory; atomics on it are executed by the memory system (on most GPUs in the L2 cache), one
after another, but there is only one per cluster per frame.

**Synchronization.** Three orderings matter, and lesson [L01](lessons/01-buffers-in-flight.md) (§14.4
and §15) explains each mechanism:

1. **The counter must be 0 when the pass starts.** The CPU cannot simply write 0 into a buffer the
   previous frame's pass may still be incrementing; the reset is a GPU-side copy from the frame ring
   (`FrameRing.upload`, D103), which has a place in the command stream after the previous pass.
2. **The lit passes must see the lists.** Shader writes to storage buffers are not ordered against
   later reads automatically; `glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)` after the dispatch
   makes them visible to every later shader that reads storage buffers.
3. **The next frame's reset copy must not overtake this frame's atomics.** A buffer copy over memory
   that shaders wrote is ordered after those writes only if a barrier with
   `GL_BUFFER_UPDATE_BARRIER_BIT` was issued in between. So the cull pass ends with both bits:
   `glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT)`. The second bit
   was added in commit 6756c4d, when the lessons were checked against the specification: it had
   always worked without it, because a whole frame of other commands separates the two, but the
   specification does not promise that.

<details><summary>Check: with $n = 0.5$, $f = 100$ and 24 slices, which slice holds a fragment at 40 m, and what happens to a fragment at 150 m?</summary>

$k = 24\ln(40/0.5)/\ln(200) = 24 \cdot 4.382 / 5.298 = 19.85$: slice 19. At 150 m, $k = 24\ln 300 /
\ln 200 = 25.8$, which the shader clamps to the last slice, 23. That slice's box only reaches 100 m,
so lights that touch the fragment but not the box are missing: beyond `far`, clustered lighting is not
guaranteed.
</details>

## 11. Shadow maps

### 11.1 The idea

A point is in a light's shadow if something lies between it and the light: the visibility $V_k$ of
§4.4 is 0. **Shadow mapping** answers that question for a whole frame with two passes:

1. Render the scene's depth **from the light**: put a camera at the light, looking where it shines,
   and draw every shadow caster into a depth texture, the **shadow map**. Each texel stores the
   depth of the closest surface along its ray.
2. When shading a point, transform it into that light camera's clip space and compare its depth with
   the stored one. If the stored surface is closer to the light, something is in the way: shadow.

```
   light
     *
     │╲          the shadow map stores, along each ray from the light,
     │ ╲         the depth of the closest caster
     │  ████  ◀ caster: stored depth d_map
     │   ╲
     │    ╲
  ───┴─────P───  receiver at depth d_P:  shadowed if the caster is nearer the light
```

Jeddy's shadow maps use reversed-Z like the main view (chapter 04): depth 1 is near the light, 0 far,
maps are cleared to 0, and "closer to the light" means "larger". The comparison is done by the
**texture unit**, not the shader: the sampler `SamplerDesc.SHADOW` sets `GL_TEXTURE_COMPARE_MODE` to
`GL_COMPARE_REF_TO_TEXTURE` with `GL_TEXTURE_COMPARE_FUNC` set to `GL_GEQUAL` (in `GpuSamplers`).
Sampled through a `sampler2DShadow` with a reference depth, the hardware returns 1 where
$\text{ref} \ge d_{\text{map}}$ (lit: the point is at least as close to the light as the stored
caster) and 0 otherwise, as `engine/sun_shadows.glsl` states in its header. The sampler wraps with
`BORDER` and GL's default border depth is 0, the far value, so lookups outside a map compare as lit
(chapter [05](05-opengl-from-java.md), on wrapping, borders and depth comparison).

### 11.2 Acne, peter-panning and bias

The map stores one depth per texel, measured at the texel's center; the receiver is a continuous
surface. Consider a floor tilted by the angle $\theta$ away from facing the light. One texel covers a
stretch of floor of width $w$ (in the light's view), and across that stretch the floor's depth from
the light changes by $w\tan\theta$. The stored value is the depth at the texel's center; half the
points of the stretch are farther from the light than that, by up to $(w/2)\tan\theta$, and compare as
shadowed. The floor shadows itself in stripes, one per texel row: **shadow acne**.

The cure is to bias the comparison towards "lit" by more than $(w/2)\tan\theta$. Too much bias
detaches shadows from their casters: a crate on the floor seems to hover above its own shadow, which
is called **peter-panning**. The needed bias grows with the texel size and with $\tan\theta$, which
explodes at grazing light, so a fixed bias is either too small at grazing angles or too large
everywhere else. Jeddy combines two biases that scale correctly:

- **Slope-scaled depth offset on the casters.** `glPolygonOffset(-1.5f, -2f)` with polygon offset
  enabled in the pipeline state. The rasterizer adds $o = m\cdot\text{factor} + r\cdot\text{units}$
  to each caster fragment's depth, where $m$ is the triangle's steepest depth slope in window
  coordinates and $r$ the smallest resolvable depth difference (chapter 05, on the depth test). A
  steep triangle, seen at a grazing angle from the light, has a large $m$ and gets a large offset:
  exactly where acne is worst. The values are negative because in reversed-Z "farther from the
  light" means "smaller".
- **Normal offset on the receivers.** Before the lookup, the shader moves the world position along
  the geometric normal by about one or two texels of the map. That moves the lookup onto the lit side
  of its own surface. The offset grows at grazing light: $\text{normalOffset} \cdot w \cdot (1.5 -
  \mathbf n\cdot\mathbf l)$ texels for the sun, so 0.75 texels facing the sun and 2.25 at grazing
  angles with the default `normalOffset` of 1.5.

*Worked:* in the demo's middle cascade a texel covers $w = 11.9$ mm (§11.4). A floor lit at
$\mathbf n\cdot\mathbf l = 0.5$ ($\theta = 60°$) has acne of up to $(w/2)\tan 60° = 10.3$ mm. The
normal offset moves the lookup by $1.5 \cdot 11.9 \cdot (1.5 - 0.5) = 17.9$ mm along the normal, of
which $17.9\cos 60° = 8.9$ mm is towards the light; the polygon offset adds the rest.

`SceneRendererTest.sunCastsShadowsOnTheGround` checks both failure modes: the ground under the sphere
must be dark (no excessive peter-panning) and the top of the sphere, which faces the sun, must be lit
("no acne").

### 11.3 Cascades for the sun: where to split

The sun lights the whole level, but its shadow map has a fixed number of texels. One map covering
everything the camera sees up to 60 m would need a bounding sphere of radius 44.3 m for the demo's
camera (fitted as in §11.4), so a 2048² map would have texels of $2 \cdot 44.3 / 2048 = 4.3$ cm
everywhere: blurry
next to the camera, where the player looks, and wastefully fine far away, where a pixel covers
metres. **Cascaded shadow maps** split the camera's view by depth into a few slices and give each its
own map: small near the camera, large far away. `SunShadows.Config.DEFAULT` is three 2048² cascades
over 60 m.

**Where to split.** Let the view run from $n$ to the shadow distance $f$, cut into $N$ cascades with
far depths $C_1, \ldots, C_N = f$:

- **Uniform splits**, $C_i = n + (f - n)\,i/N$, give each cascade the same depth range. The first
  cascade, which covers the most screen pixels per metre, gets no more texels than the last.
- **Logarithmic splits**, $C_i = n(f/n)^{i/N}$, the geometric spacing of §10.2, give each cascade the
  same ratio of far to near. Since a screen pixel at depth $z$ covers a width proportional to $z$,
  that keeps the ratio of shadow texels to screen pixels about the same in every cascade, which is the
  ideal for perspective. But with a tiny $n$, it crams almost everything into a sliver near the
  camera.

The **practical split scheme** (Zhang et al., *Parallel-Split Shadow Maps*, 2006) blends them with a
weight $\lambda$:

$$C_i = \lambda\, n\left(\frac{f}{n}\right)^{i/N} + (1-\lambda)\left(n + (f - n)\frac{i}{N}\right)$$

with $f$ = `min(p.far, config.distance())` (60 m by default) and $\lambda$ = `lambda` (0.75). With the
packet's default near plane, $n = 0.1$: the logarithmic splits are 0.84, 7.11, 60, the uniform ones
20.07, 40.03, 60, and the blend $0.75 \cdot 0.84 + 0.25 \cdot 20.07 = 5.65$, then **5.65 m, 15.3 m,
60 m**. With the demo's near plane of 0.5 m: **6.93 m, 19.17 m, 60 m**.

**What that means for an isometric camera.** The demo's camera looks down at 52° from 22 m away
(`DemoGame.init` sets `camera.pitch` to 52° and `camera.distance` to 22; `IsoCamera`'s vertical fov
is 30°). Its eye is $22\sin 52° = 17.3$ m above the ground. The ray through the bottom of the screen
points $52° + 15° = 67°$ down and meets the ground $17.3/\sin 67° = 18.8$ m away, at a view depth of
$18.8\cos 15° = 18.2$ m; the ray through the top, $37°$ down, meets it $17.3/\sin 37° = 28.8$ m away,
at a view depth of $28.8\cos 15° = 27.8$ m. So everything on the ground lies between 18.2 and
27.8 m, across the boundary of cascades 2 and 3, and cascade 1 (0.5–6.9 m) covers only the air
between the camera and the floor. (With `IsoCamera`'s own defaults, 55° and 20 m, the same
calculation gives 16.8–24.6 m.) This is a consequence of tuning the split for a generic
perspective camera, not a bug; exercise 12 tunes it for this one.

### 11.4 Fitting a cascade, and keeping it still

**Fitting.** For cascade $c$ between depths $a$ and $b$, `fitCascade` computes the eight world-space
corners of that piece of the camera's frustum (the CPU twin of §10.3's unprojection), then a bounding
**sphere** around them: the center at their average, the radius the largest distance from it. The
light camera is an orthographic projection looking along the sun's direction, covering the sphere: a
square of side $2r$ in light space.

Why a sphere and not the tightest box around the corners? A box aligned with the light changes size
as the camera turns (a frustum seen from the sun looks wider or narrower depending on its rotation),
and every change of size changes the texel size, so shadow edges would swim. A sphere is the same size
from every direction.

**Stabilizing.** As the camera moves, a naive fit makes the shadow map slide continuously under the
world; each world point then lands on a slightly different place inside its texel every frame, the
comparison flips at the edges, and shadow edges crawl and shimmer. Two measures stop it:

1. **Constant texel size.** The sphere's radius depends only on the projection and the split depths,
   because moving or turning the camera moves the corners rigidly and does not change distances
   between them. Rounding it up to 1/16 m (`Math.ceil(r * 16) / 16`) absorbs the float rounding that
   would otherwise make it differ in the last bits from frame to frame. So the world size of a texel,
   $w = 2r/\text{size}$, is constant.
2. **Whole-texel steps.** The sphere's center, expressed in light space, is snapped down to a
   multiple of $w$: `Math.floor(point[0] / texel) * texel`. When the camera moves, the map moves by
   whole texels, and each world point keeps landing on the same spot of the texel grid.

For the snapping to mean anything, "light space" must be the world turned to face the sun and nothing
else, so that moving the camera moves the snapped center by plain translations. `lightRotation` is a
look-at from the origin along the sun direction: a pure rotation of the world.

The demo's three cascades (16:9 window, fov 30°, near 0.5 m; computed with the formulas above):

| Cascade | Depths (m) | Radius (m), quantized | Texel $w = 2r/2048$ |
|---|---|---|---|
| 1 | 0.5 – 6.93 | 5.0000 | 4.9 mm |
| 2 | 6.93 – 19.17 | 12.1875 | 11.9 mm |
| 3 | 19.17 – 60 | 38.6875 | 37.8 mm |

### 11.5 Casters outside the cascade: pull-back and depth clamp

The cascade's box covers the sphere, but shadows can come from outside it: a tall tower behind the
camera casts into the visible area. The orthographic near plane is therefore **pulled back** towards
the sun by `pullback` (60 m by default) beyond the sphere: the light camera's depth range runs from
`distance - r - pullback` to `distance + r`.

Casters even closer to the sun than that would be clipped away by the near plane. `SunShadows.render`
enables **`GL_DEPTH_CLAMP`** for the cascades: the hardware then skips clipping against the near and
far planes and instead clamps each fragment's depth into $[0, 1]$. A caster beyond the near plane is
flattened onto it, at depth 1 (the near value in reversed-Z), the largest possible value: it still
shadows everything behind it, which is all a shadow map needs. Depth clamp is turned off again at the
end of the pass, because the main view must clip normally.

### 11.6 Soft edges: percentage-closer filtering

A shadow map lookup is a yes-or-no per texel, so shadow edges come out as jagged stair-steps of
texels. Averaging the stored *depths* first and then comparing would not help: the comparison of an
average is still a yes-or-no. **Percentage-closer filtering** (PCF) compares first and averages the
*results*: several lookups around the point, each 0 or 1, averaged into the fraction of lookups that
are lit. Near an edge that fraction goes smoothly from 0 to 1.

Jeddy's sun takes 9 lookups on a 3×3 grid of texel offsets. And because the shadow sampler filters
`LINEAR`, the texture unit itself compares the 2×2 texels around each lookup and blends the four
results bilinearly (chapter 05), so each of the nine is already a small PCF. Together they cover 3×3
texels when the point sits on a texel center and up to 4×4 when it does not: an edge three to four
texels wide, which in the demo's second cascade is 3.6–4.8 cm.

### 11.7 Local lights: perspective views in an atlas

Spot and point lights cast shadows too, when a game asks for it with
`RenderPacket.setLightShadow(i, key)`, where `key` identifies the light across frames (an entity id).

- A **spot light** gets a perspective view from its position along its axis, with a field of view
  that covers its cone plus a margin: `fov = min(170°, 2·outer + 4°)`. The margin keeps the
  normal-offset and filtered lookups of points near the cone's edge inside the map.
- A **point light** shines in every direction; it gets six 90° views along $\pm x$, $\pm y$, $\pm z$,
  like the six faces of a cube map, which together see everything.

All views go into one **atlas**: a 2048² depth texture cut into 256² **tiles**, an 8 × 8 grid of 64.
`LocalShadows.Config.DEFAULT` has 10 **slots** (shadowed lights per frame), each owning six
consecutive tiles: a spot light uses one, a point light all six, one per face. With perspective, a
texel's world size grows with the distance $d$ from the light:

$$w(d) = d \cdot \frac{2\tan(\text{fov}/2)}{\text{tileSize}}$$

For a cube face (fov 90°, $\tan 45° = 1$), 1 m from the light that is $2/256 = 7.8$ mm, and at 3 m
2.3 cm. The receiver's normal offset scales with $d$ accordingly (§29).

**The static-caster cache (D44).** Most casters near a torch never move: walls, floors, pillars.
Rendering them again every frame for every face of every shadowed light would be most of the shadow
cost. So each slot keeps its static casters' depth in a **second atlas**, the cache, and redraws it
only when something that changes those depths changes: the light's position, range, direction or
cone, the packet's `staticVersion`, or the light switching between point and spot. Each frame, per
tile, the renderer copies the cached tile into the live atlas on the GPU (`glCopyImageSubData`, no
draws) and draws only the dynamic casters — characters, a moving door — on top. Torches in a tavern
cost a copy and the few characters near them.

**Choosing the lights.** Each frame the closest shadowed lights to the camera get slots; a light
keeps its slot (and its cache) while it stays among them, and a new one takes the least recently used
slot, whose cache is lost. §28 builds all of it.

## 12. The depth prepass and GEQUAL

Before the lit pass, the **depth prepass** draws every opaque instance with a cheap program
(`pbr/depth.vert` and `pbr/depth.frag`: positions only, plus UVs for alpha-tested materials and the
camera-fade inputs) into the depth buffer, with no color. It serves two purposes:

1. **No overdraw in the expensive shader.** After the prepass the depth buffer holds the closest
   surface of every pixel. The lit pass then draws the same geometry testing against it, so the GPU's
   early depth test (chapter 05, on the depth test) rejects every hidden fragment before `pbr.frag`,
   with its light loop, runs: each pixel is lit once.
2. **Depth for screen-space effects.** GTAO needs the scene's depth *before* the lit pass, because
   the lit pass consumes its result (§13).

The depth target is cleared to 0 (`graph.addPass("depth prepass", ...).depth(depth, 0f)`), the far
value in reversed-Z, and `PipelineState.OPAQUE` tests `GL_GEQUAL` (nearer or equal passes). The lit
pass then uses `depthReadOnly(depth)` and the `MaterialSystem.litOpaque` binder, whose pipeline is
`PipelineState.withDepthWrite(PipelineState.OPAQUE, false)`: the same test, no depth writes.

**Why not `GL_EQUAL`, the textbook choice?** The prepass and lit programs are different programs.
Without GLSL's `invariant` qualifier on `gl_Position`, nothing obliges the compiler to evaluate the
position the same way in both (it may reorder or fuse multiply-adds differently), and a difference in
the last bit turns `EQUAL` into holes. `GEQUAL` accepts the visible surface even if its depth came out
a hair nearer, and still rejects everything behind it (D42). Its Javadoc, from `MaterialSystem`:

```java
    /**
     * Binder for the lit opaque pass after a depth prepass: reversed-Z test {@code GL_GEQUAL}
     * without depth writes. (Not {@code GL_EQUAL}: the prepass and lit programs compute the same
     * positions, but nothing guarantees bit-identical depth across two different programs.)
     */
    public final DrawBuilder.BatchBinder litOpaque;
```

and the binder itself, from `MaterialSystem`'s constructor:

```java
        this.litOpaque =
                (state, slot, translucent) ->
                        !translucent
                                && bindLit(
                                        state,
                                        slot,
                                        PipelineState.withDepthWrite(PipelineState.OPAQUE, false));
```

It refuses translucent batches (they belong to the transparent pass) and binds the slot's program
and textures with the opaque state minus depth writes.

The camera-fade dither (chapter 08) runs in both programs with the same pattern, so a faded pixel is
missing from the depth buffer too, and the lit pass does not draw it (D48). Chapter
[06](06-renderer-architecture.md), on the scene renderer's passes, builds the prepass's executor.

## 13. Ambient occlusion

### 13.1 What AO approximates

Image-based lighting assumes every direction above a surface sees the environment. In a corner,
under a table, where a character's feet meet the floor, much of that hemisphere is blocked by nearby
geometry, and the real indirect light there is darker. **Ambient occlusion** is the cosine-weighted
fraction of the hemisphere that is open:

$$AO(\mathbf x) = \frac{1}{\pi}\int_{\Omega} V(\mathbf x, \boldsymbol\omega)\,(\mathbf n\cdot\boldsymbol\omega)\,d\omega$$

$V$ is 1 if direction $\boldsymbol\omega$ is unblocked (within some radius) and 0 otherwise;
dividing by $\pi$ (§2.5) makes an open surface have $AO = 1$.

**An exact case.** A sphere of angular radius $\alpha$ (it covers a cone of half-angle $\alpha$ as seen
from the point) straight overhead blocks $\frac{1}{\pi}\int_0^{2\pi}\!\int_0^{\alpha}\cos\theta
\sin\theta\,d\theta\,d\phi = \frac{1}{\pi}\cdot 2\pi\cdot\tfrac12\sin^2\alpha = \sin^2\alpha$. Away from
overhead, with its center at angle $\beta$ from the normal and entirely above the horizon, it blocks
$\sin^2\alpha\cos\beta$. Why: measure directions from the cone's own axis $\mathbf a$ (angle $\theta'$,
azimuth $\phi'$, §2.3). The cosine weight of a direction is $\mathbf n\cdot\boldsymbol\omega =
\cos\beta\cos\theta' - \sin\beta\sin\theta'\cos\phi'$ (the normal has the component $\cos\beta$ along the
axis and $\sin\beta$ across it). Integrated over a full turn of $\phi'$ the second term vanishes, and
what is left is $\cos\beta$ times the overhead integral: $\frac{1}{\pi}\cos\beta \cdot \pi\sin^2\alpha$.
The scene of the AO test: a sphere of radius
0.4 m resting on the ground, and a ground point 0.47 m in front of the contact. The center is
$\sqrt{0.4^2 + 0.47^2} = 0.617$ m away, so $\sin\alpha = 0.4/0.617 = 0.648$, and it is 0.4 m up, so
$\cos\beta = 0.648$ too: blocked $0.648^2 \cdot 0.648 = 0.272$, $AO = 0.728$. (A Monte Carlo check with
four million cosine-distributed rays gives 0.7277.)

### 13.2 Horizon-based integration (GTAO)

The renderer does not trace rays. Screen-space AO only knows the depth buffer: a height field seen
from the camera. **GTAO** ("ground-truth ambient occlusion", Jimenez, Wu, Pesce and Jarabo, 2016)
integrates that height field exactly, under the assumption that it has no holes.

**Slices.** Split the hemisphere around the view vector $\mathbf v$ into **slices**: half-planes that
contain $\mathbf v$, one for each screen direction $\phi \in [0, \pi)$. Within a slice, directions are
measured by an angle $\theta$ from $\mathbf v$, from $-\pi/2$ to $\pi/2$. Walking from the pixel along
the slice's screen direction, the depth buffer reveals the highest geometry on each side; its angle
from $\mathbf v$ is that side's **horizon**, $h_1$ (negative side) and $h_2$ (positive side). Everything
beyond a horizon is blocked; the arc between them is open.

```
            v (θ = 0)
            ▲
   h1 ╲     │     ╱ h2       open arc between the two horizons;
        ╲   │   ╱            outside it the height field blocks the sky
  ████    ╲ │ ╱    ▄▄▄▄
  ████──────●──────████
```

**The integral in one slice.** In spherical coordinates around $\mathbf v$, a solid-angle slice is
$|\sin\theta|\,d\theta\,d\phi$ (§2.4). Let $\gamma$ be the angle of the normal projected into the
slice plane, measured from $\mathbf v$, and $\ell$ (`projLen`) the length of that projection; the
cosine weight of a direction at angle $\theta$ in the slice is then $\ell\cos(\theta - \gamma)$. The
open arc of one slice contributes $\ell\,\hat a$ with

$$\hat a(h_1, h_2, \gamma) = \int_{h_1}^{h_2} \cos(\theta - \gamma)\,|\sin\theta|\,d\theta$$

Split at $\theta = 0$ to drop the absolute value, and use $\cos(\theta - \gamma)\sin\theta =
\tfrac12\big(\sin(2\theta - \gamma) + \sin\gamma\big)$ (add the angle-sum identities of chapter 03 for
$\sin(\theta + (\theta - \gamma))$ and $\sin(\theta - (\theta - \gamma))$).
The positive side gives $\tfrac12\big[-\tfrac12\cos(2\theta - \gamma) + \theta\sin\gamma\big]_0^{h_2}$,
the negative side the same with the opposite sign from $h_1$ to 0, and together:

$$\hat a = \tfrac14\left(-\cos(2h_1-\gamma) + \cos\gamma + 2h_1\sin\gamma\right) + \tfrac14\left(-\cos(2h_2-\gamma) + \cos\gamma + 2h_2\sin\gamma\right)$$

Checks:

- **Open, facing the camera**: $\gamma = 0$, horizons at $\mp\pi/2$: $\hat a = \tfrac14(1 + 1) +
  \tfrac14(1 + 1) = 1$. Averaged over the slices, $AO = \frac{1}{\pi}\int_0^\pi \ell\,\hat a\,d\phi =
  1$. ✓
- **A slope rising at $45°$ on one side**: $\gamma = 0$, $h_1 = -\pi/2$, $h_2 = \pi/4$: $\hat a =
  \tfrac12 + \tfrac14(-\cos\tfrac{\pi}{2} + 1) = 0.75$, which a 200,000-step numerical integral of
  the definition confirms.

Averaged over $S$ slices, $AO \approx \frac{1}{S}\sum_s \ell_s\,\hat a_s$ (the $\frac{1}{\pi}\int_0^\pi
d\phi$ becomes an average over evenly spread $\phi$s). §30 walks through the shader: reconstructing
positions and normals from depth, searching horizons in a world-space radius, noise to spread few
slices over many directions, a falloff so distant geometry does not occlude, half resolution and a
depth-aware blur.

# Part B — Building it

Part A derived every formula. Part B writes the files, in the order you would: first the BRDF, which
needs nothing else; then the shader that evaluates the rendering equation, with the light records
and the cluster lookup it reads; then the image-based lighting on the GPU and in the cooker; then
the systems that produce the lit shader's inputs — clusters, sun shadows, local shadows, ambient
occlusion — and last the wiring into `SceneRenderer`. Shader files are GLSL 4.60 with the engine's
`#include` and `#pragma once` preprocessor (chapter [05](05-opengl-from-java.md), on the shader
preprocessor).

## 14. Step 1: the BRDF in GLSL

Create `engine/brdf.glsl`. It depends on nothing, so every shader that lights anything can include
it.

```glsl
// Cook-Torrance terms (DESIGN §9.3): GGX distribution, height-correlated Smith visibility and
// Schlick Fresnel, plus Lambert diffuse. Roughness is perceptual; alpha = roughness^2.
#pragma once
```

`#pragma once` makes a second `#include` of the file a no-op, so `pbr.frag` can include files that
include each other without duplicate definitions.

**The distribution** (§6.3):

```glsl
float jeddyD_GGX(float NoH, float alpha) {
    float a2 = alpha * alpha;
    float f = (NoH * a2 - NoH) * NoH + 1.0;
    return a2 / (3.14159265358979 * f * f);
}
```

- The parameters are already-computed cosines: the caller computes each dot product once and passes
  it to every term that needs it. Names like `NoH` read "N dot H".
- `f` is $(\mathbf n\cdot\mathbf h)^2(\alpha^2 - 1) + 1$, factored as $((\mathbf n\cdot\mathbf h)\,
  \alpha^2 - \mathbf n\cdot\mathbf h)\cdot\mathbf n\cdot\mathbf h + 1$. Each `x * y + z` compiles to one
  **fused multiply-add** (FMA) instruction, which GPUs execute at the rate of a single multiply and
  round only once: two instructions for the whole parenthesis.
- π is written out because this file includes nothing; `J_PI` lives in `frame.glsl`.

**The visibility** (§6.4):

```glsl
float jeddyV_SmithGGXCorrelated(float NoV, float NoL, float alpha) {
    float a2 = alpha * alpha;
    float ggxL = NoV * sqrt((NoL - a2 * NoL) * NoL + a2);
    float ggxV = NoL * sqrt((NoV - a2 * NoV) * NoV + a2);
    return 0.5 / max(ggxV + ggxL, 1e-5);
}
```

`(NoL - a2 * NoL) * NoL + a2` is $c_l^2(1 - \alpha^2) + \alpha^2$ again in multiply-adds, and the
`max` keeps the division finite in the one case where the denominator can vanish: both cosines 0.

**The Fresnel term** (§6.5):

```glsl
vec3 jeddyF_Schlick(float VoH, vec3 f0) {
    float f = pow(1.0 - VoH, 5.0);
    return f + f0 * (1.0 - f);
}
```

`pow` with a constant exponent of 5 is cheap (a compiler may expand it into multiplications or an
`exp2`/`log2` pair), and `1.0 - VoH` is never negative here, because the caller clamps `VoH` to
$[0, 1]$; GLSL leaves `pow` undefined for a negative base.

**The surface.** Everything about the shaded point that does not depend on the light goes into one
struct, filled once per pixel, so the light loop recomputes only what changes per light:

```glsl
// Shading inputs of one surface point.
struct JeddySurface {
    vec3 position;   // world
    vec3 normal;     // world, unit, facing the viewer
    vec3 view;       // unit vector to the eye
    vec3 diffuse;    // albedo * (1 - metallic)
    vec3 f0;         // specular reflectance at normal incidence
    float roughness; // perceptual, clamped
    float alpha;     // roughness^2
    float NoV;
    vec3 energy;     // multiple-scattering compensation, 1 + f0 (1 / E_ss - 1)
};
```

**One light's contribution**, the term $f(\mathbf l, \mathbf v)\,E_\perp\,(\mathbf n\cdot\mathbf l)$ of
§4.4:

```glsl
// Radiance reflected towards the viewer from light arriving along l with irradiance-scaled
// color (radiance * NoL is applied here).
vec3 jeddyShade(JeddySurface s, vec3 l, vec3 lightColor) {
    vec3 h = normalize(s.view + l);
    float NoL = clamp(dot(s.normal, l), 0.0, 1.0);
    if (NoL <= 0.0) {
        return vec3(0.0);
    }
    float NoH = clamp(dot(s.normal, h), 0.0, 1.0);
    float LoH = clamp(dot(l, h), 0.0, 1.0);
    float D = jeddyD_GGX(NoH, s.alpha);
    float V = jeddyV_SmithGGXCorrelated(s.NoV, NoL, s.alpha);
    vec3 F = jeddyF_Schlick(LoH, s.f0);
    vec3 specular = D * V * F * s.energy;
    vec3 diffuse = s.diffuse / 3.14159265358979;
    return (diffuse + specular) * lightColor * NoL;
}
```

Line by line:

1. `h` is the half vector of §6.1. If $\mathbf l = -\mathbf v$ exactly, `s.view + l` is zero and
   `normalize` returns garbage, but then $\mathbf n\cdot\mathbf l = -\mathbf n\cdot\mathbf v < 0$ and the
   function returns before `h` is used.
2. A light below the surface's horizon ($\mathbf n\cdot\mathbf l \le 0$) contributes nothing: the
   early return skips the rest. On a GPU, lanes of a group that return early wait for the others
   (chapter 05, on the fragment shader), so the saving is real only when whole groups are back-lit,
   which is common: the dark side of a wall.
3. The three cosines are clamped to $[0, 1]$ because interpolation and normal maps can push them
   slightly outside, and `pow` and the square roots need them in range.
4. `LoH` is passed as `VoH`: equal by construction (§6.1).
5. `specular` is $D\,V\,F$ times the energy factor of §6.7, a `vec3` because $F$ and the factor are
   colors. `diffuse` is Lambert, $c_{\text{diff}}/\pi$.
6. The result is $(f_{\text{diff}} + f_{\text{spec}})\cdot E_\perp\cdot(\mathbf n\cdot\mathbf l)$:
   radiance towards the eye, with `lightColor` $= E_\perp$.

GLSL functions are not calls on a GPU: the compiler inlines them into `main`, and passing the
`JeddySurface` by value costs nothing.

<details><summary>Check: evaluate jeddyShade by hand for a white dielectric (base 1, metallic 0, roughness 0.5) lit head-on by $E_\perp = 1$ and seen head-on (all cosines 1).</summary>

$\alpha = 0.25$; $D = 5.093$; $V = 0.5/(1 \cdot 1 + 1 \cdot 1) = 0.25$ (both square roots are 1 when the
cosines are 1); $F = F_0 = 0.04$ (with $\mathbf v\cdot\mathbf h = 1$); $E_{ss}$ at $\mathbf
n\cdot\mathbf v = 1$, roughness 0.5 is 0.896 (computed with the LUT's algorithm), so the energy factor is
$1 + 0.04(1/0.896 - 1) = 1.005$. Specular $= 5.093 \cdot 0.25 \cdot 0.04 \cdot 1.005 = 0.051$;
diffuse $= 1/\pi = 0.318$. The result is $0.369$ per channel, of which 14 % is the highlight.
</details>

## 15. Step 2: what the frame constants carry for lighting

The lit shader reads its per-frame inputs from the uniform block `JeddyFrame` at binding 0
(`engine/frame.glsl`), written once per frame by `SceneRenderer.writeFrameConstants` into the frame
ring. Chapter [06](06-renderer-architecture.md), on the frame constants, builds the whole 544-byte
block and its std140 offsets; the members this chapter uses:

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

const float J_PI = 3.14159265358979;

bool jeddyOrthographic() {
    return (jCounts.w & 1u) != 0u;
}

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

`jeddyViewDepth` is the $z$ of §10.2: the view matrix moves the point into camera space, where the
camera looks along $-z$, so the negated $z$ is the distance in front of the camera. The writer, for
the lighting members (from `writeFrameConstants`):

```java
        float near = Math.max(1e-3f, p.near);
        float far = Math.max(near * 2, p.far);
        float logRatio = (float) Math.log(far / near);
        float scale = LightClusters.SLICES / logRatio;
        float bias = (float) (-LightClusters.SLICES * Math.log(near) / logRatio);
        putVec4(m, o + 352, near, far, scale, bias);
        m.set(ValueLayout.JAVA_INT, o + 368, clusters.dimX());
        m.set(ValueLayout.JAVA_INT, o + 372, clusters.dimY());
        m.set(ValueLayout.JAVA_INT, o + 376, LightClusters.SLICES);
        m.set(ValueLayout.JAVA_INT, o + 380, LightClusters.TILE);
        float[] d = p.sunDirection;
        float len = (float) Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        float inv = len > 0 ? 1 / len : 0;
        putVec4(m, o + 384, d[0] * inv, d[1] * inv, d[2] * inv, 0);
        float[] c = p.sunColor;
        boolean sun = c[0] > 0 || c[1] > 0 || c[2] > 0;
        putVec4(m, o + 400, c[0], c[1], c[2], sun ? 1 : 0);
        putVec4(
                m,
                o + 416,
                p.environmentSpecular,
                p.environmentDiffuse,
                environment.levels(),
                p.exposure);
        m.set(ValueLayout.JAVA_INT, o + 432, clusters.frameLightCount());
        m.set(ValueLayout.JAVA_INT, o + 436, clusters.frameDecalCount());
```

- **Slicing.** `scale` and `bias` are §10.2's formulas. The two `Math.max` guards keep $n > 0$ and
  $f > n$, so both logarithms are defined and `logRatio` is positive.
- **Sun.** The direction is normalized on the CPU, once, instead of in every pixel; a zero vector
  stays zero instead of dividing by 0. `w` of `jSunColor` is the "sun on" flag the shader tests, so a
  black sun costs one comparison.
- **Environment.** The two packet scales and the cube's mip count for `jeddyPrefiltered` (§8.4).
- **Counts.** The light and decal counts are what `LightClusters.upload` actually managed to copy
  this frame (0 if the ring was full), so the cull pass never reads past the uploaded array. That is
  why `render` writes the frame constants after the uploads (chapter 06, on `render`).

## 16. Step 3: pbr.frag, part 1 — the surface

### 16.1 What the vertex shader hands over

`pbr.vert` runs per vertex: it reads the instance record and the vertex's QTangent, skins if needed,
and outputs (chapter [03](03-rotations-and-transforms.md), on mirrored instances and normal maps,
walks through its `main`):

```glsl
layout(location = 0) out vec3 vWorld;
layout(location = 1) out vec3 vNormal;
layout(location = 2) out vec4 vTangent; // xyz tangent, w bitangent sign
layout(location = 3) out vec4 vUv;      // uv0, uv1
layout(location = 4) flat out uint vMaterial;
layout(location = 5) flat out uint vFlags;
layout(location = 6) flat out float vFade;
```

The rasterizer interpolates the first four across each triangle, perspective-correctly; `flat`
outputs are not interpolated but taken from one vertex of the triangle (chapter 05, on interpolating
the vertex outputs). Integers must be `flat` (blending two material indices means nothing), and the
fade is per instance, the same at all three vertices.

### 16.2 The fragment shader's start

Create `pbr/pbr.frag`:

```glsl
#version 460 core
// Forward+ PBR (TASKS M7-01, M7-02, M7-03; DESIGN §9.3): glTF metallic-roughness with GGX,
// height-correlated Smith and Schlick terms, multiple-scattering energy compensation, the sun,
// clustered point and spot lights, image-based lighting from a prefiltered cube and SH9, and
// clustered decals (M7-07), camera fade and fog of war (M7-10).
#include "/engine/frame.glsl"
#include "/engine/brdf.glsl"
#include "/engine/material.glsl"
#include "/engine/lights.glsl"
#include "/engine/environment.glsl"
#include "/engine/shadows.glsl"
#include "/engine/effects.glsl"
#include "/engine/camera_fade.glsl"
#if defined(DECALS) && !defined(ALPHA_BLEND)
#include "/engine/decals.glsl"
#endif

layout(location = 0) in vec3 vWorld;
layout(location = 1) in vec3 vNormal;
layout(location = 2) in vec4 vTangent;
layout(location = 3) in vec4 vUv;
layout(location = 4) flat in uint vMaterial;
layout(location = 5) flat in uint vFlags;
layout(location = 6) flat in float vFade;

layout(location = 0) out vec4 outColor;
```

The inputs match the vertex shader's outputs by `location`. The `#ifdef`s throughout come from the
material's feature mask and the renderer's global features, which `ProgramCache` turns into
`#define`s for each variant (chapter 06, on feature masks): a material without a normal map gets a
program without the normal-map code, and a renderer without sun shadows gets the stub of
`shadows.glsl` that returns 1 (§27). Decals are left out of blended (`ALPHA_BLEND`) variants: a
decal projects onto the opaque surface behind a window, not onto the window.

```glsl
void main() {
    // Derivatives first, in uniform control flow (decals sample with explicit gradients).
    vec3 dpdx = dFdx(vWorld);
    vec3 dpdy = dFdy(vWorld);
    if (jeddyFadeDiscard(gl_FragCoord.xy, jeddyCameraFade(vWorld, vFlags, vFade))) {
        discard;
    }
    float viewDepth = jeddyViewDepth(vWorld);
    uvec2 range = jClusterRanges[jeddyClusterIndex(gl_FragCoord.xy, viewDepth)];
```

- **Derivatives first.** `dFdx` subtracts the neighbour's value inside the 2×2 quad (chapter 05,
  on quads and derivatives); after a `discard` or inside a branch some neighbours may have stopped,
  and the result would be undefined. Taking them at the top, before any branch, is always safe. The
  decals use them to choose mip levels (chapter 08).
- **Camera fade** (chapter 08) discards a dithered pattern of pixels of surfaces fading out of the
  way of the camera.
- **The cluster.** One view depth, one lookup: `range` is this pixel's `(offset, count)` pair in the
  cluster list (§18).

```glsl
    JeddyMaterial m = jMaterials[vMaterial];
    vec4 base = m.baseColor;
#ifdef BASE_COLOR_MAP
    base *= texture(jBaseColorMap, vec3(jeddySlotUv(m, 0u, vUv), float(m.layers.x)));
#endif
#ifdef ALPHA_MASK
    if (base.a < m.misc.y) {
        discard;
    }
    base.a = 1.0;
#endif
```

The material row is read by index from the storage buffer at binding 1 (chapter 06, on the material
row in the fragment shader). Textures live in array textures, addressed by a layer number stored in
the row (D41). An **alpha-masked** material (foliage, a fence) discards pixels below its cutoff and
is otherwise opaque. The ORM and occlusion reads that follow are §5's; then the decals:

```glsl
    vec3 decalGlow = vec3(0.0);
#if defined(DECALS) && !defined(ALPHA_BLEND)
    if ((vFlags & 16u) == 0u) { // RenderPacket.FLAG_NO_DECALS
        JeddyDecalResult decal = jeddyDecals(range, vWorld, normalize(vNormal), dpdx, dpdy);
        base.rgb = base.rgb * (1.0 - decal.tint.a) + decal.tint.rgb;
        // Painted-on marks are matte and not metallic.
        metallic *= 1.0 - decal.tint.a;
        roughness = mix(roughness, 0.9, decal.tint.a);
        decalGlow = decal.emitted;
    }
#endif
    roughness = clamp(roughness, 0.045, 1.0);
    metallic = clamp(metallic, 0.0, 1.0);
```

Decals (chapter 08) change the *material* before lighting, so a scorch mark on a floor is lit,
shadowed and occluded like the floor: they tint the base color by their coverage `tint.a`, make the
surface less metallic and rougher there, and may glow (`decalGlow`, added to the color later). The
clamps of §5 come after, so nothing upstream can push roughness below 0.045. The normal of §9
follows.

### 16.3 Filling the surface

```glsl
    JeddySurface s;
    s.position = vWorld;
    s.view = jeddyOrthographic() ? normalize(vec3(jView[0][2], jView[1][2], jView[2][2]))
                                 : normalize(jEye.xyz - vWorld);
    // Keep normals that face away from the viewer (normal maps, interpolation) usable.
    s.normal = n;
    s.NoV = max(dot(n, s.view), 1e-4);
    s.diffuse = base.rgb * (1.0 - metallic);
    s.f0 = mix(vec3(0.04), base.rgb, metallic);
    s.roughness = roughness;
    s.alpha = roughness * roughness;
    vec2 dfg = jeddyDfg(s.NoV, roughness);
    s.energy = 1.0 + s.f0 * (1.0 / max(dfg.x + dfg.y, 1e-4) - 1.0);

    vec3 color = decalGlow;
```

- **The view vector.** In perspective, every pixel looks from the eye: $\mathbf v =
  \operatorname{normalize}(\text{eye} - \text{point})$. In an orthographic view all rays are parallel,
  so $\mathbf v$ is the same everywhere: the camera's backward axis. The view matrix's rotation has
  the camera's axes as its rows (chapter 04, on the view matrix), and `jView[c][r]` is column `c`,
  row `r` of a column-major matrix, so `(jView[0][2], jView[1][2], jView[2][2])` is row 2, the
  camera's $+z$ axis in world space, which points from the scene towards the viewer.
- **`NoV` at least $10^{-4}$.** A normal-mapped normal can face slightly away from the eye on a
  visible surface. The visibility term and the LUT need a positive cosine; the clamp keeps them
  finite and treats such pixels as grazing.
- **The material colors** of §5, and $E_{ss}$ from the LUT for the energy factor of §6.7 (with a
  `max` so a LUT value of 0 cannot divide by zero). The lookup itself is `jeddyDfg` (§20).
- `color` starts with the decals' glow; every light adds to it.

## 17. Step 4: light records on the CPU

Lights reach the GPU as **records**: 16 floats, 64 bytes each, in a storage buffer. The game thread
writes them straight into the render packet in that layout, and `LightClusters.upload` copies the
whole array into the frame ring with one `MemorySegment.copy` (D44): no conversion on the main thread,
no per-light work.

```java
    /** Light type: point. */
    public static final int LIGHT_POINT = 0;

    /** Light type: spot. */
    public static final int LIGHT_SPOT = 1;

    /** Floats per light in {@link #lights} (the GPU layout of {@code engine/lights.glsl}). */
    public static final int LIGHT_FLOATS = 16;

    /**
     * Point and spot lights, {@link #LIGHT_FLOATS} floats each in the GPU layout: position xyz,
     * range; color × intensity rgb, type (int bits); spot direction xyz, cos(outer); cos(inner),
     * shadow index (int bits, -1 none), 0, 0. Fill with {@link #addPointLight} and {@link
     * #addSpotLight}. Index 14 holds the light's shadow key ({@link #setLightShadow}).
     */
    public float[] lights = new float[LIGHT_FLOATS * 64];

    /** Number of lights. */
    public int lightCount;
```

The record, float by float:

| Float | Written by | Content |
|---|---|---|
| 0–3 | `addPointLight` | position, range |
| 4–6 | `addPointLight` | color × intensity |
| 7 | `addPointLight` / `addSpotLight` | type as int bits (`LIGHT_POINT` 0, `LIGHT_SPOT` 1) |
| 8–11 | `addSpotLight` | spot direction, cos(outer) (point: `0, -1, 0, -1`) |
| 12 | `addSpotLight` | cos(inner) (point: 1) |
| 13 | renderer | shadow index as int bits; the packet stores −1, `LocalShadows.update` patches the frame's copy |
| 14 | `setLightShadow` | shadow key as int bits (an entity id), read only on the CPU |
| 15 | | 0 |

```java
    private int nextLight() {
        if ((lightCount + 1) * LIGHT_FLOATS > lights.length) {
            lights = Arrays.copyOf(lights, lights.length * 2);
        }
        return lightCount++ * LIGHT_FLOATS;
    }

    public int addPointLight(
            float x, float y, float z, float range, float r, float g, float b, float intensity) {
        int o = nextLight();
        float[] l = lights;
        l[o] = x;
        l[o + 1] = y;
        l[o + 2] = z;
        l[o + 3] = range;
        l[o + 4] = r * intensity;
        l[o + 5] = g * intensity;
        l[o + 6] = b * intensity;
        l[o + 7] = Float.intBitsToFloat(LIGHT_POINT);
        l[o + 8] = 0;
        l[o + 9] = -1;
        l[o + 10] = 0;
        l[o + 11] = -1;
        l[o + 12] = 1;
        l[o + 13] = Float.intBitsToFloat(-1);
        l[o + 14] = Float.intBitsToFloat(-1);
        l[o + 15] = 0;
        return o / LIGHT_FLOATS;
    }
```

(Javadoc condensed out.) Points worth a line each:

- **Growth by doubling**, the only allocation, happens while the light count is still rising; once
  the game reaches its usual count, a frame allocates nothing (the frame tests prove it, §33).
- **Color × intensity** is premultiplied, so the shader multiplies once.
- **A point light is a spot light that cannot be cut off.** Its "direction" $(0, -1, 0)$ and $\cos
  \theta_o = -1$ (an outer angle of 180°) are harmless defaults, and the type field decides; the
  shader never applies the cone to a point light.
- **Integers in a `float[]`.** The type, the shadow index and the shadow key are integers, stored as
  the raw bits of a float with `Float.intBitsToFloat`, so the whole record stays one homogeneous
  `float[]` that one bulk copy moves (chapter 05, on integers among floats). The GPU reads them back
  with `floatBitsToUint`/`floatBitsToInt`: the 32 bits pass through untouched.

Two traps hide in that trick, and the code avoids both:

- **−1 is a NaN.** The bits of the integer −1 are `0xFFFFFFFF`, which as a float is a NaN (exponent
  all ones, fraction non-zero: L00 §7.3). Java keeps a quiet NaN's bits through arrays and
  `MemorySegment.copy`, and `Float.floatToRawIntBits` returns them: −1. But
  `Float.floatToIntBits` (without "Raw") replaces every NaN by the canonical `0x7FC00000`, which is
  the integer 2,143,289,344. A `select` that used it would see every unshadowed light as shadowed with
  a huge positive key. `LocalShadows` uses the raw version everywhere. (`jshell`:
  `Float.floatToIntBits(Float.intBitsToFloat(-1))` is 2143289344, the raw one −1.)
- **1 is a tiny subnormal.** `Float.intBitsToFloat(1)` is $1.4 \times 10^{-45}$, a subnormal float that
  some GPU arithmetic flushes to zero. Nothing does arithmetic on it: the shader reinterprets the bits
  with `floatBitsToUint` straight after the load, so they arrive intact.

The spot light reuses the point light and overwrites the cone:

```java
    public int addSpotLight(
            float x,
            float y,
            float z,
            float dx,
            float dy,
            float dz,
            float range,
            float r,
            float g,
            float b,
            float intensity,
            float innerAngle,
            float outerAngle) {
        int i = addPointLight(x, y, z, range, r, g, b, intensity);
        int o = i * LIGHT_FLOATS;
        float[] l = lights;
        l[o + 7] = Float.intBitsToFloat(LIGHT_SPOT);
        l[o + 8] = dx;
        l[o + 9] = dy;
        l[o + 10] = dz;
        l[o + 11] = (float) Math.cos(outerAngle);
        l[o + 12] = (float) Math.cos(Math.min(innerAngle, outerAngle - 1e-3f));
        return i;
    }

    /**
     * Makes light {@code i} cast shadows. {@code key} must identify the light across frames (an
     * entity id): the renderer keeps its shadow tiles, and their static casters, under that key.
     * {@code -1} turns shadows off.
     */
    public void setLightShadow(int i, int key) {
        lights[i * LIGHT_FLOATS + 14] = Float.intBitsToFloat(key);
    }
```

The cosines are computed here, on the game thread, once per light, so the shader never calls `cos`
or `acos` (§7.2). The inner angle is pulled 0.001 rad inside the outer one so that $\cos\theta_i -
\cos\theta_o$ cannot be 0.

How the demo uses it (`DemoGame.extract` and `Level.extractLights`): every torch of the level is a
point light; the three torches nearest the party get `setLightShadow` with the key `100 + torch
index`, and the party's own light (14 cd, range 12 m) gets key 1: four shadowed lights.

## 18. Step 5: the light include

Create `engine/lights.glsl`:

```glsl
// Punctual lights and the cluster grid (DESIGN §9.3, TASKS M7-03). Lights live in a storage
// buffer (binding 2); the light-cull compute pass writes, per cluster, an (offset, count) pair
// (binding 3) into a list of light indices (binding 4).
#pragma once
#include "frame.glsl"

const uint J_LIGHT_POINT = 0u;
const uint J_LIGHT_SPOT = 1u;

struct JeddyLight {
    vec4 positionRange;  // xyz world position, w range in meters (0 = unlimited)
    vec4 colorType;      // rgb linear color times intensity (candela), w type
    vec4 directionOuter; // xyz unit direction the spot points, w cos(outer angle)
    vec4 params;         // x cos(inner angle), y shadow index (< 0: none), z CPU-side shadow key, w unused
};

layout(std430, binding = 2) readonly buffer JeddyLights {
    JeddyLight jLights[];
};

layout(std430, binding = 3) readonly buffer JeddyClusterGrid {
    uvec2 jClusterRanges[]; // offset, count
};

layout(std430, binding = 4) readonly buffer JeddyClusterItems {
    uint jClusterLights[];
};
```

- **The struct is four `vec4`s**, 64 bytes, the same in std430 as the Java record's 16 floats
  (chapter 05, on the layout rules: `vec4` members have no gaps). Shaders never read `params.z`, the
  CPU-side key.
- **`readonly`** promises the compiler the shader never writes these buffers, which lets it use
  read-only cache paths where the hardware has them.
- **Unsized arrays** take their length from the bound range (chapter 05, on getting data to shaders).
- `jClusterRanges` is one `uvec2` (8 bytes) per cluster; `jClusterLights` is the shared index list
  of §10.5, read here from its first byte. The cull shader declares a 16-byte header in front of the
  same buffer, which is why the offsets it stores are shifted by 4 (§25).

**Which cluster?**

```glsl
// Index of the cluster holding a fragment at window position fragCoord and view depth z.
uint jeddyClusterIndex(vec2 fragCoord, float viewDepth) {
    uvec3 dims = jClusterDims.xyz;
    uvec2 tile = min(uvec2(fragCoord) / jClusterDims.w, dims.xy - 1u);
    float slice;
    if (jeddyOrthographic()) {
        slice = (viewDepth - jClusterZ.x) / (jClusterZ.y - jClusterZ.x) * float(dims.z);
    } else {
        slice = log(max(viewDepth, 1e-4)) * jClusterZ.z + jClusterZ.w;
    }
    uint z = uint(clamp(slice, 0.0, float(dims.z - 1u)));
    return (z * dims.y + tile.y) * dims.x + tile.x;
}
```

1. `gl_FragCoord.xy` is the pixel's center in window coordinates, such as $(800.5, 450.5)$;
   `uvec2(...)` drops the fraction (the pixel index), and integer division by the tile size (64) gives
   the tile. The `min` keeps a pixel of a partial last tile in range.
2. The slice is §10.2's formula: linear for orthographic, `log` × scale + bias for perspective, with
   the logarithm's argument kept positive.
3. `clamp` then the conversion to `uint` (which truncates) gives slices 0 to 23; fragments nearer
   than $n$ or beyond $f$ land in the first or last slice.
4. The index is laid out $x$ fastest, then $y$, then $z$: the order `cluster_bounds.comp` decodes.

*Worked*, in the demo at 1600 × 900 ($n = 0.5$, $f = 104$ at the starting zoom, so scale 4.4965 and
bias 3.1167, and $25 \times 15$ tiles): the pixel $(800.5, 450.5)$ at view depth 22 m is in tile
$(12, 7)$, slice $\lfloor 3.0910 \cdot 4.4965 + 3.1167 \rfloor = \lfloor 17.02 \rfloor = 17$, cluster
$(17 \cdot 15 + 7) \cdot 25 + 12 = 6562$.

**Falloff** (§7.1):

```glsl
// Smooth inverse-square falloff that reaches 0 at the light's range (Karis 2013).
float jeddyDistanceAttenuation(float d2, float range) {
    float falloff = 1.0 / max(d2, 1e-4);
    if (range > 0.0) {
        float r = d2 / (range * range);
        float window = clamp(1.0 - r * r, 0.0, 1.0);
        falloff *= window * window;
    }
    return falloff;
}
```

It works with the **squared** distance throughout: `r` here is $(d/r)^2$, so `r * r` is $(d/r)^4$,
and no square root is needed. A range of 0 skips the window.

**The cone** (§7.2):

```glsl
float jeddySpotAttenuation(JeddyLight light, vec3 l) {
    float cosOuter = light.directionOuter.w;
    float cosInner = light.params.x;
    float cd = dot(-l, light.directionOuter.xyz);
    float t = clamp((cd - cosOuter) / max(cosInner - cosOuter, 1e-4), 0.0, 1.0);
    return t * t;
}
```

`-l` points from the light to the surface, so `cd` is $\cos\theta_s$. The `max` repeats on the GPU the
guard `addSpotLight` already applied, in case a record was written some other way.

## 19. Step 6: pbr.frag, part 2 — summing the light

Back in `pbr.frag`, after the surface is filled. **The sun** (§7.3):

```glsl
    // Sun.
    if (jSunColor.w > 0.0) {
        vec3 l = -jSunDirection.xyz;
        float shadow = jeddySunShadow(vWorld, vNormal, l, viewDepth);
        color += jeddyShade(s, l, jSunColor.rgb) * shadow;
    }
```

$\mathbf l$ points *towards* the sun, the opposite of the direction sunlight travels. The shadow
lookup receives the **geometric** normal `vNormal` (§9) and the view depth that chooses the cascade
(§27). The branch is uniform (every pixel of the frame takes the same side), so it costs nothing.

**The clustered lights** (§4.4, §10):

```glsl
    // Clustered point and spot lights.
    uint lightCount = range.y & 0xFFFFu;
    for (uint i = 0u; i < lightCount; i++) {
        JeddyLight light = jLights[jClusterLights[range.x + i]];
        vec3 toLight = light.positionRange.xyz - vWorld;
        float d2 = dot(toLight, toLight);
        vec3 l = toLight * inversesqrt(max(d2, 1e-8));
        float attenuation = jeddyDistanceAttenuation(d2, light.positionRange.w);
        if (floatBitsToUint(light.colorType.w) == J_LIGHT_SPOT) {
            attenuation *= jeddySpotAttenuation(light, l);
        }
        if (attenuation <= 0.0) {
            continue;
        }
        attenuation *= jeddyLocalShadow(light, vWorld, vNormal, l);
        color += jeddyShade(s, l, light.colorType.rgb * attenuation);
    }
```

1. **The count** is the low 16 bits of `range.y` (decals are in the high 16, D45; L00 §6.2 for the
   mask).
2. **Two loads per light**: the light's index from the cluster list, then its 64-byte record. The
   second depends on the first (the "dependent load" of L00 §9.3), and GPUs hide that wait by
   switching to other pixel groups.
3. **Direction without a division**: `inversesqrt` gives $1/\sqrt{d^2}$ in one instruction; the
   `max` avoids $1/0$ for a light exactly at the point.
4. **Attenuation** (§7.1), times the cone for spot lights; the type is read as integer bits (§17).
5. **`continue` when the light does not reach**: the cluster's box is larger than the cluster
   (§10.4), so some listed lights contribute 0 here; skipping saves the shadow lookup and the BRDF.
6. **The shadow** of a local light (§29), then one term of §4.4's sum, with $E_\perp$ = color ×
   intensity × attenuation.

Pixels of the same tile and slice read the **same list**, so the lanes of a GPU group usually loop the
same number of times over the same lights: the loop is coherent. Only at slice boundaries do
neighbouring pixels walk different lists, and the group runs the longer one with some lanes masked.

**The environment** (§8):

```glsl
    // Image-based lighting, with ambient occlusion from the material and screen space.
    float ao = occlusion * jeddyScreenOcclusion(gl_FragCoord.xy);
    vec3 r = reflect(-s.view, n);
    vec3 specular = jeddyPrefiltered(r, roughness) * (s.f0 * dfg.x + dfg.y) * s.energy;
    vec3 diffuse = jeddyIrradiance(n) * s.diffuse / J_PI;
    // Specular occlusion (Lagarde): AO weakens with gloss and grazing views.
    float so = clamp(pow(s.NoV + ao, exp2(-16.0 * roughness - 1.0)) - 1.0 + ao, 0.0, 1.0);
    color += (diffuse * ao * jEnvironment.y + specular * so * jEnvironment.x);
```

The total occlusion is the material's baked occlusion times the screen-space AO (§30). The
specular term is §8.4–8.5's split sum with §6.7's compensation, the diffuse term §8.2's irradiance
times Lambert, and §8.8 weakens each. `dfg` was read once for `s.energy` and is reused here.

**Emission and output:**

```glsl
#ifdef EMISSIVE_MAP
    color += m.emissive.rgb * m.emissive.w
            * texture(jEmissiveMap, vec3(jeddySlotUv(m, 3u, vUv), float(m.layers.w))).rgb;
#else
    color += m.emissive.rgb * m.emissive.w;
#endif

    color = jeddyApplyEffects(color, vWorld, vFlags);
    outColor = vec4(color, base.a);
}
```

$L_e$ of §4.1: the emissive color times its strength (the cooker reads it from glTF's
`KHR_materials_emissive_strength`), times the emissive texture if there is one. `jeddyApplyEffects` darkens and desaturates areas
under the fog of war (chapter 08). The result is a radiance per channel, unbounded, written into the
`GL_RGBA16F` "hdr" target; exposure and tone mapping turn it into display values later (chapter 08).
Alpha is the material's, used only by blended variants.

That is the whole rendering equation of §4.3, one line per row.

## 20. Step 7: Environment and its include

### 20.1 The include

Create `engine/environment.glsl`. It declares what §8 needs at run time — the prefiltered cube, the
lookup table and the nine coefficients — and three small functions:

```glsl
// Image-based lighting (TASKS M7-02, DESIGN §9.3): a GGX-prefiltered radiance cube (mip i for
// roughness i / (levels - 1)), SH9 irradiance (already convolved with the cosine lobe; uniform
// block at binding 2) and the split-sum BRDF LUT.
#pragma once
#include "frame.glsl"

layout(binding = 5) uniform samplerCube jEnvironmentMap;
layout(binding = 6) uniform sampler2D jBrdfLut;

layout(std140, binding = 2) uniform JeddyIrradiance {
    vec4 jSh[9]; // rgb per coefficient: Y00, Y1-1, Y10, Y11, Y2-2, Y2-1, Y20, Y21, Y22
};

vec3 jeddyIrradiance(vec3 n) {
    vec3 e = jSh[0].rgb * 0.282095
        + jSh[1].rgb * (0.488603 * n.y)
        + jSh[2].rgb * (0.488603 * n.z)
        + jSh[3].rgb * (0.488603 * n.x)
        + jSh[4].rgb * (1.092548 * n.x * n.y)
        + jSh[5].rgb * (1.092548 * n.y * n.z)
        + jSh[6].rgb * (0.315392 * (3.0 * n.z * n.z - 1.0))
        + jSh[7].rgb * (1.092548 * n.x * n.z)
        + jSh[8].rgb * (0.546274 * (n.x * n.x - n.y * n.y));
    return max(e, vec3(0.0));
}

// Split-sum scale and bias of F0 for (N.V, roughness).
vec2 jeddyDfg(float NoV, float roughness) {
    return texture(jBrdfLut, vec2(NoV, roughness)).rg;
}

vec3 jeddyPrefiltered(vec3 r, float roughness) {
    return textureLod(jEnvironmentMap, r, roughness * max(jEnvironment.z - 1.0, 0.0)).rgb;
}
```

- **`jSh` is nine `vec4`s, not 27 floats.** In std140 every array element is padded to 16 bytes
  (chapter 05, on the layout rules), so an array of `vec3` would have the same stride with a
  misleading type; a `vec4` with an unused `w` makes the 144-byte layout explicit.
- **`jeddyIrradiance`** is §8.2's sum $\sum \hat A_l L_{lm} Y_{lm}(\mathbf n)$: the coefficients
  arrive already multiplied by $\hat A_l$, and the code writes out the nine basis polynomials in the
  table's order. The `max` removes the negative values of ringing (§8.2).
- **`jeddyDfg`** reads $(A, B)$ at $(\mathbf n\cdot\mathbf v, \text{roughness})$ with a bilinear,
  clamped sampler: texel $x$ was computed for $\mathbf n\cdot\mathbf v = (x + 0.5)/128$ (§21), which is
  exactly where a 128-wide texture's texel centers are, so the lookup interpolates between the right
  neighbours.
- **`jeddyPrefiltered`** is §8.4's lookup. `textureLod` takes the mip level explicitly instead of
  computing it from derivatives (chapter 05, on choosing the level): the blur is chosen by roughness,
  not by distance.

### 20.2 The class: fields and constructor

[`Environment`](../../modules/render/src/main/java/dev/jeddy/render/light/Environment.java) owns those
three inputs. Thread ownership: the GL thread.

```java
public final class Environment implements AutoCloseable {
    /** Texture unit of the radiance cube. */
    public static final int CUBE_UNIT = 5;

    /** Texture unit of the BRDF LUT. */
    public static final int LUT_UNIT = 6;

    /** Uniform binding of the SH9 block. */
    public static final int SH_BINDING = 2;

    /** BRDF LUT size. */
    public static final int LUT_SIZE = 128;

    private static final float Y00 = 0.282095f;

    private final GpuDevice device;
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment sh = arena.allocate(9 * 16, 16);
    private final MemorySegment uniformTexels = arena.allocate(6 * 8, 8); // one RGBA16F per face
    private final float[] uniformSh = new float[27];
    private final int shBuffer;
    private boolean shDirty;
    private final int lut;
    private final int defaultCube;
    private final int cubeSampler;
    private final int lutSampler;
    private int cube;
    private int levels = 1;
    private boolean ownsCube;
```

- `sh` is the **CPU copy** of the uniform block, 9 × 16 bytes in native memory (L00 §8.3), from which
  uploads are staged; `shDirty` says it changed since the last upload.
- `uniformTexels` holds the six texels of the 1×1 default cube, 8 bytes each (four half floats).
- `cube` is the texture currently bound for lighting; `ownsCube` says whether `Environment` must
  destroy it; `levels` is its mip count, which becomes `jEnvironment.z`.

```java
    public Environment(GpuDevice device, ProgramCache programs) {
        this.device = device;
        shBuffer = device.buffers().create(9 * 16, GpuBuffers.DYNAMIC, "environment SH9");
        lut = device.textures().create2D(GL_RG16F, LUT_SIZE, LUT_SIZE, 1, "BRDF LUT");
        int lutProgram =
                programs.getBlocking(
                        programs.defineCompute(
                                "brdf lut", "pbr/brdf_lut.comp", ShaderFeatures.NONE),
                        0);
        glUseProgram(lutProgram);
        glProgramUniform1i(lutProgram, 0, 512);
        glBindImageTexture(0, device.textures().glName(lut), 0, false, 0, GL_WRITE_ONLY, GL_RG16F);
        glDispatchCompute((LUT_SIZE + 7) / 8, (LUT_SIZE + 7) / 8, 1);
        glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT);
        glUseProgram(0);
        device.state().invalidate();
```

**Generating the lookup table on the GPU, once (D43).** The alternative was to load the cooker's
`engine/brdf_lut.ktx2`. Generating it at startup instead means a renderer works without any cooked
engine asset (tests, tools, a fresh checkout), at the price D43 records as "a few ms" of startup.
The cooked LUT remains for tools.

1. **The texture**: 128 × 128, `GL_RG16F` (two half floats per texel: $A$ and $B$), one mip level.
   Half floats keep about three significant digits, plenty for values between 0 and 1.
2. **The program** is compiled synchronously (`getBlocking`): it is needed right now.
3. **The sample count** goes into the uniform at location 0 (`uSamples`): 512.
4. **`glBindImageTexture`** binds level 0 of the texture to **image unit** 0 for writing. An *image*
   binding lets a shader write individual texels with `imageStore`, unlike a texture binding, which
   only samples. The format (`GL_RG16F`) must match the shader's `layout(rg16f)` declaration; the
   hardware converts the shader's floats to half floats as it stores.
5. **The dispatch**: $\lceil 128/8 \rceil = 16$ groups in $x$ and in $y$, each of 8 × 8 invocations
   (the shader's local size), one invocation per texel, 16,384 in all.
6. **The barrier.** Image stores are incoherent writes (L01 §6.3): a later `texture()` read of the
   LUT is only guaranteed to see them after `glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT)`, the bit
   for "later shaders will sample this".
7. **`device.state().invalidate()`**: the constructor changed the bound program behind the state
   cache's back (chapter 05, on the state cache), so the cache forgets what it thinks is bound.

```java
        glEnable(GL_TEXTURE_CUBE_MAP_SEAMLESS); // filtering across cube faces, for every cube
        cubeSampler =
                device.samplers()
                        .get(
                                SamplerDesc.of(
                                        SamplerDesc.LINEAR,
                                        SamplerDesc.LINEAR,
                                        SamplerDesc.MIP_LINEAR,
                                        SamplerDesc.CLAMP));
        lutSampler = device.samplers().get(SamplerDesc.LINEAR_CLAMP);
        defaultCube =
                device.textures()
                        .create(
                                GpuTextures.KIND_CUBE,
                                GL_RGBA16F,
                                1,
                                1,
                                1,
                                1,
                                "default environment");
        setUniform(0.3f, 0.3f, 0.32f);
    }
```

- **Seamless cube filtering** (§8.1) is a global switch of the context in GL 4.6, not a per-texture
  setting, so it is turned on once here.
- **The cube's sampler** filters trilinearly (`MIP_LINEAR`), which `jeddyPrefiltered` needs to blend
  between roughness mips; the LUT's is bilinear and clamped (no mips; clamping keeps
  $\mathbf n\cdot\mathbf v = 1$ from wrapping to 0).
- **A default environment**: a 1×1 cube lit by a uniform, slightly blue gray of 0.3. Until a level's
  cooked environment is loaded, shadowed areas are not pitch black.

### 20.3 A uniform environment and a cooked one

```java
    /**
     * Uses a uniform environment of radiance {@code (r, g, b)}: the cube is that color and the
     * irradiance is {@code π} times it.
     */
    public void setUniform(float r, float g, float b) {
        MemorySegment px = uniformTexels;
        for (int f = 0; f < 6; f++) {
            px.set(ValueLayout.JAVA_SHORT, f * 8L, Float.floatToFloat16(r));
            px.set(ValueLayout.JAVA_SHORT, f * 8L + 2, Float.floatToFloat16(g));
            px.set(ValueLayout.JAVA_SHORT, f * 8L + 4, Float.floatToFloat16(b));
            px.set(ValueLayout.JAVA_SHORT, f * 8L + 6, Float.floatToFloat16(1));
        }
        device.textures().uploadLevel(defaultCube, 0, px.address(), (int) px.byteSize());
        float[] coefficients = uniformSh; // only the constant band is non-zero
        float scale = (float) Math.PI / Y00;
        coefficients[0] = r * scale;
        coefficients[1] = g * scale;
        coefficients[2] = b * scale;
        releaseCube();
        cube = defaultCube;
        levels = 1;
        writeSh(coefficients);
    }
```

- **The cube**: six faces of one texel, each four half floats. `Float.floatToFloat16` (JDK 20+)
  converts a `float` to the 16 bits of a half float (the design of L00 §7 at a smaller size: 5
  exponent and 10 fraction bits, chapter 05 on texture formats), returned as a `short`. One `uploadLevel` sends all six faces.
- **The coefficients**: for a uniform radiance $L$ only band 0 is non-zero, and the stored coefficient
  is $\hat A_0 L_{00} = \pi \cdot 4\pi L\,Y_{00}$; the shader multiplies it by $Y_{00}$ again. Since
  $4\pi Y_{00}^2 = 1$, that is the same as storing $\pi L / Y_{00}$: `scale = π / Y00` (§8.2's first
  check). `coefficients[0..2]` are the red, green and blue of $Y_{00}$; the other 24 stay 0.
- **One mip level**, so `jEnvironment.z - 1 = 0` and every roughness reads level 0, which is right for
  a uniform color.

The white-furnace test calls `setUniform(1, 1, 1)`; the other lighting tests `setUniform(0, 0, 0)`
to see direct light alone.

```java
    public void set(int cubeTexture, float[] sh9, boolean takeOwnership) {
        if (device.textures().kind(cubeTexture) != GpuTextures.KIND_CUBE) {
            throw new IllegalArgumentException("environment needs a cube map");
        }
        releaseCube();
        cube = cubeTexture;
        ownsCube = takeOwnership;
        levels = device.textures().levels(cubeTexture);
        writeSh(sh9);
    }

    private void writeSh(float[] c) {
        for (int i = 0; i < 9; i++) {
            sh.set(ValueLayout.JAVA_FLOAT, i * 16L, c[i * 3]);
            sh.set(ValueLayout.JAVA_FLOAT, i * 16L + 4, c[i * 3 + 1]);
            sh.set(ValueLayout.JAVA_FLOAT, i * 16L + 8, c[i * 3 + 2]);
            sh.set(ValueLayout.JAVA_FLOAT, i * 16L + 12, 0f);
        }
        shDirty = true;
    }
```

`set` takes a cooked environment: the prefiltered cube and its 27 coefficients. They travel inside the
cube's KTX2 file as key/value data under `jeddy.sh9` (D39), `TextureLoader` reads them into
`GpuTexture.sh9`, and `WorldRenderer` calls `set` once the texture is loaded. `writeSh` turns 27
packed floats into nine std140 `vec4`s.

### 20.4 Uploading through the ring, and binding

```java
    /**
     * Uploads changed SH coefficients through the frame ring (a GPU-side copy, D103), or from the
     * CPU when {@code ring} is null or full. The renderer calls it before binding.
     */
    public void flush(FrameRing ring) {
        if (!shDirty) {
            return;
        }
        if (ring == null
                || !ring.upload(device.buffers().glName(shBuffer), 0, sh, 0, sh.byteSize())) {
            device.buffers().update(shBuffer, 0, sh);
        }
        shDirty = false;
    }
```

The SH buffer is read by every lit draw of every frame in flight, so a change must not be written
from the CPU into it: an Intel driver silently dropped exactly such writes (L01 §17). `flush` stages
the 144 bytes in this frame's ring region and issues a GPU copy that sits after the earlier frames'
reads in the command stream (D103; L01 §13 builds `upload`). It costs nothing on frames where nothing
changed.

```java
    /** Binds the cube, the LUT and the SH block. */
    public void bind(StateCache state) {
        state.bindTexture(CUBE_UNIT, device.textures().glName(cube));
        state.bindSampler(CUBE_UNIT, cubeSampler);
        state.bindTexture(LUT_UNIT, device.textures().glName(lut));
        state.bindSampler(LUT_UNIT, lutSampler);
        state.bindUniform(SH_BINDING, device.buffers().glName(shBuffer), 0, 9 * 16);
    }
```

Through the state cache, so rebinding the same objects every pass costs no GL call. `releaseCube`
destroys the previous cube only if it was owned and is not the default one; `close` destroys the
default cube, the LUT and the buffer, and closes the arena.

## 21. Step 8: the BRDF lookup table on the GPU

Create `pbr/brdf_lut.comp`: §8.5's $A$ and $B$, estimated with §8.6's importance sampling, one
invocation per texel.

```glsl
#version 460 core
// The split-sum environment BRDF (Karis 2013), generated once at startup (TASKS M7-02): scale and
// bias of F0 for N.V along x and perceptual roughness along y, Smith-GGX with k = alpha / 2.
layout(local_size_x = 8, local_size_y = 8) in;
layout(rg16f, binding = 0) uniform writeonly image2D uLut;
layout(location = 0) uniform int uSamples;

vec2 hammersley(uint i, uint n) {
    uint bits = bitfieldReverse(i);
    return vec2(float(i) / float(n), float(bits) * 2.3283064365386963e-10);
}
```

- **Workgroups of 8 × 8**: a 2D grid matches the 2D texture, and 64 invocations fill the lockstep
  lanes of most GPUs.
- **`uLut`** is the image `Environment` bound to unit 0, write-only, with the `rg16f` format the
  binding declared.
- **`hammersley`**: $2.3283064365386963 \times 10^{-10}$ is $2^{-32}$, so the reversed bits become a
  fraction in $[0, 1)$ (§8.6).

```glsl
void main() {
    ivec2 size = imageSize(uLut);
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= size.x || p.y >= size.y) {
        return;
    }
    float NoV = (float(p.x) + 0.5) / float(size.x);
    float roughness = (float(p.y) + 0.5) / float(size.y);
    float alpha = roughness * roughness;
    float a2 = alpha * alpha;
    float k = alpha * 0.5;
    vec3 v = vec3(sqrt(1.0 - NoV * NoV), 0.0, NoV);
    float scale = 0.0;
    float bias = 0.0;
    uint n = uint(uSamples);
```

1. **The guard** returns invocations outside the texture (none for 128, which is a multiple of 8; the
   guard keeps other sizes safe).
2. **Texel centers**: $\mathbf n\cdot\mathbf v$ and roughness at $(x + 0.5)/128$, the same convention
   the sampler uses when the lit shader reads it (§20.1). No texel is computed at exactly
   $\mathbf n\cdot\mathbf v = 0$, where the estimator would divide by zero.
3. **Tangent space**: $\mathbf n = +z$, and by symmetry only the angle between $\mathbf v$ and
   $\mathbf n$ matters, so $\mathbf v$ is put in the $xz$ plane: $(\sin\theta_v, 0, \cos\theta_v)$ with
   $\sin = \sqrt{1 - \cos^2}$.
4. `k` is the Schlick–Smith constant of §6.4.

```glsl
    for (uint i = 0u; i < n; i++) {
        vec2 xi = hammersley(i, n);
        float phi = 6.28318530718 * xi.x;
        float cosTheta = sqrt((1.0 - xi.y) / (1.0 + (a2 - 1.0) * xi.y));
        float sinTheta = sqrt(1.0 - cosTheta * cosTheta);
        vec3 h = vec3(sinTheta * cos(phi), sinTheta * sin(phi), cosTheta);
        float VoH = dot(v, h);
        vec3 l = 2.0 * VoH * h - v;
        float NoL = max(l.z, 0.0);
        float NoH = max(h.z, 0.0);
        VoH = max(VoH, 0.0);
        if (NoL > 0.0) {
            float gv = NoV / (NoV * (1.0 - k) + k);
            float gl = NoL / (NoL * (1.0 - k) + k);
            float gVis = gv * gl * VoH / max(NoH * NoV, 1e-6);
            float fc = pow(1.0 - VoH, 5.0);
            scale += (1.0 - fc) * gVis;
            bias += fc * gVis;
        }
    }
    imageStore(uLut, p, vec4(scale / float(n), bias / float(n), 0.0, 0.0));
}
```

Line by line, with the section each comes from:

- `phi`, `cosTheta`: the GGX half-vector sample of §8.6 ($6.28318530718 = 2\pi$). `h` is the
  spherical-to-Cartesian formula of §2.3.
- `l`: $\mathbf v$ reflected about $\mathbf h$. In tangent space $\mathbf n\cdot\mathbf l$ is just `l.z`
  and $\mathbf n\cdot\mathbf h$ is `h.z`.
- Samples whose $\mathbf l$ falls below the surface contribute 0 (the integrand has $(\mathbf
  n\cdot\mathbf l)^+$) but still count in the average's denominator $n$: they are part of the
  distribution that was sampled.
- `gVis` is the estimator $G\,(\mathbf v\cdot\mathbf h)/((\mathbf n\cdot\mathbf h)(\mathbf n\cdot\mathbf
  v))$ of §8.6, with the separable Schlick–Smith $G = G_1(\mathbf v)\,G_1(\mathbf l)$.
- `fc` is $F_c = (1 - \mathbf v\cdot\mathbf h)^5$; `scale` accumulates $A$, `bias` accumulates $B$
  (§8.5).
- `imageStore` writes the averages; the hardware converts them to half floats.

`EnvironmentBaker.brdfLut` (§22.6) is the same algorithm in Java; the cooker's test checks its values,
and they are the numbers quoted in §6.7. Exercise 6 replaces `gVis` with the height-correlated term.

## 22. Step 9: baking the environment in the cooker

The cooker turns an HDR sky photograph (an equirectangular `.hdr`, an image twice as wide as high
covering every direction) into one KTX2 file holding everything the runtime needs:
[`EnvironmentImporter`](../../tools/cooker/src/main/java/dev/jeddy/cooker/env/EnvironmentImporter.java)
drives it, and
[`EnvironmentBaker`](../../tools/cooker/src/main/java/dev/jeddy/cooker/env/EnvironmentBaker.java) does
the math, on the CPU, fanned out on the job system. Chapter [10](10-assets.md) covers the importer
and the KTX2 container; this section builds the bakes. Thread ownership: stateless, any thread.

### 22.1 Cubes and directions

```java
    /** A cube map: 6 faces of {@code size × size} linear RGB floats. */
    public record Cube(int size, float[][] faces) {
        /** An empty cube. */
        public Cube(int size) {
            this(size, new float[6][size * size * 3]);
        }
    }

    /** Writes the direction through face {@code f}'s texel center {@code (x, y)} into {@code d}. */
    static void direction(int f, int x, int y, int size, float[] d) {
        float s = 2f * (x + 0.5f) / size - 1f;
        float t = 2f * (y + 0.5f) / size - 1f;
        float dx;
        float dy;
        float dz;
        switch (f) {
            case 0 -> {
                dx = 1;
                dy = -t;
                dz = -s;
            }
            case 1 -> {
                dx = -1;
                dy = -t;
                dz = s;
            }
            case 2 -> {
                dx = s;
                dy = 1;
                dz = t;
            }
            case 3 -> {
                dx = s;
                dy = -1;
                dz = -t;
            }
            case 4 -> {
                dx = s;
                dy = -t;
                dz = 1;
            }
            default -> {
                dx = -s;
                dy = -t;
                dz = -1;
            }
        }
        float inv = (float) (1 / Math.sqrt(dx * dx + dy * dy + dz * dz));
        d[0] = dx * inv;
        d[1] = dy * inv;
        d[2] = dz * inv;
    }
```

- **A cube is six `float[]`s** of RGB triples, row by row: plain arrays, one per face, in GL's face
  order $+x, -x, +y, -y, +z, -z$.
- **`direction`** is the hardware's face table of §8.1 run backwards: from a face and a texel center
  $(s, t) \in (-1, 1)^2$ to the direction through it. Face $+x$ ($f = 0$) sits at $x = 1$; its $s$ axis
  runs along $-z$ and its $t$ axis along $-y$ (GL's table has the image's rows going down). Only this
  table has to match the GPU; nothing else about the axes is a choice.
- `EnvironmentTest.cubeFacesFollowTheGlTable` checks three directions against the table, and that
  `sample` (next) applied to a texel's direction returns that texel.

`sample(Cube c, float x, float y, float z, float[] out)` is the forward table: the major axis picks
the face, `sc / ma` and `tc / ma` give the face coordinates, and a bilinear lookup inside the face
(clamped at its edges) gives the radiance. `fromEquirect` builds the base cube by sampling the
equirectangular image along each texel's direction (supersampling when the source is much larger),
and `mips` builds a 2×2 box-filtered chain of the base cube down to 1×1: the blurrier sources that
§8.7's filtered importance sampling reads.

### 22.2 Prefiltering: the loop over texels

```java
    public static List<Cube> prefilter(Cube base, int levels, int samples, JobSystem jobs) {
        List<Cube> chain = mips(base);
        List<Cube> out = new ArrayList<>(levels);
        out.add(base);
        float texelSolidAngle = (float) (4 * Math.PI / (6.0 * base.size() * base.size()));
        float[][] hammersley = hammersley(samples);
        for (int level = 1; level < levels; level++) {
            int size = Math.max(1, base.size() >> level);
            float roughness = (float) level / (levels - 1);
            float alpha = roughness * roughness;
            Cube dst = new Cube(size);
            jobs.parallelFor(
                    (start, end, thread) -> {
                        float[] n = new float[3];
                        float[] rgb = new float[3];
                        for (int row = start; row < end; row++) {
                            int f = row / size;
                            int y = row % size;
                            for (int x = 0; x < size; x++) {
                                direction(f, x, y, size, n);
                                filterTexel(chain, n, alpha, hammersley, texelSolidAngle, rgb);
                                int o = (y * size + x) * 3;
                                dst.faces()[f][o] = rgb[0];
                                dst.faces()[f][o + 1] = rgb[1];
                                dst.faces()[f][o + 2] = rgb[2];
                            }
                        }
                    },
                    6 * size,
                    Math.max(1, size / 8));
            out.add(dst);
        }
        return out;
    }
```

- **Level 0 is the unfiltered base**: roughness 0 is a mirror, whose lobe is a single direction.
- **Level $i$ is half the size of level $i - 1$** and filtered for roughness $i/(\text{levels} - 1)$.
  A blurrier image needs fewer texels, so each level is cheaper than the one before.
- **`texelSolidAngle`** is $\Omega_p$ of §8.7, the average over the base cube.
- **The Hammersley points** are generated once and shared by every texel.
- **Parallel by rows**: all $6 \times \text{size}$ rows of all faces are one index space, split
  between job workers in chunks of `size / 8` rows (L03 §15 explains when splitting pays). Each
  worker allocates its own two scratch arrays: the cooker is not on the zero-allocation rule.

### 22.3 Prefiltering: one texel

```java
    private static void filterTexel(
            List<Cube> chain,
            float[] n,
            float alpha,
            float[][] xi,
            float texelSolidAngle,
            float[] out) {
        // Tangent frame around N (V = N = R).
        float ux = Math.abs(n[2]) < 0.999f ? 0 : 1;
        float uz = Math.abs(n[2]) < 0.999f ? 1 : 0;
        // t = normalize(cross(up, n)); b = cross(n, t)
        float tx = 0 * n[2] - uz * n[1];
        float ty = uz * n[0] - ux * n[2];
        float tz = ux * n[1] - 0 * n[0];
        float tl = (float) (1 / Math.sqrt(tx * tx + ty * ty + tz * tz));
        tx *= tl;
        ty *= tl;
        tz *= tl;
        float bx = n[1] * tz - n[2] * ty;
        float by = n[2] * tx - n[0] * tz;
        float bz = n[0] * ty - n[1] * tx;
```

The samples are drawn in a frame where $\mathbf n$ is the $z$ axis (§2.3), so the texel needs two more
axes perpendicular to $\mathbf n$ and to each other. A cross product with any vector not parallel to
$\mathbf n$ gives one (chapter 02): the code uses $+z$ as "up", or $+x$ when $\mathbf n$ is within
2.6° of $\pm z$ (where the cross product would be nearly zero and its normalization unstable). The
cross product is written out with the known zero component of `up`.

```java
        float a2 = alpha * alpha;
        float r = 0;
        float g = 0;
        float b = 0;
        float weight = 0;
        float[] rgb = new float[3];
        int maxLevel = chain.size() - 1;
        for (float[] e : xi) {
            float phi = (float) (2 * Math.PI * e[0]);
            float cosTheta = (float) Math.sqrt((1 - e[1]) / (1 + (a2 - 1) * e[1]));
            float sinTheta = (float) Math.sqrt(1 - cosTheta * cosTheta);
            float hx = sinTheta * (float) Math.cos(phi);
            float hy = sinTheta * (float) Math.sin(phi);
            float hz = cosTheta;
            float wx = tx * hx + bx * hy + n[0] * hz;
            float wy = ty * hx + by * hy + n[1] * hz;
            float wz = tz * hx + bz * hy + n[2] * hz;
            // L = reflect(-V, H) with V = N.
            float vh = n[0] * wx + n[1] * wy + n[2] * wz;
            float lx = 2 * vh * wx - n[0];
            float ly = 2 * vh * wy - n[1];
            float lz = 2 * vh * wz - n[2];
            float nl = n[0] * lx + n[1] * ly + n[2] * lz;
            if (nl <= 0) {
                continue;
            }
```

The same GGX sample as the LUT shader, then a **change of basis** from the tangent frame to world
space: $\mathbf w = h_x\mathbf t + h_y\mathbf b + h_z\mathbf n$ (chapter 02). With $\mathbf v = \mathbf
n$ (§8.4's assumption), $\mathbf l$ is $\mathbf n$ reflected about $\mathbf w$; directions below the
surface are skipped.

```java
            // pdf(L) = D(h) (n·h) / (4 (v·h)) = D / 4 when V = N.
            float nh = cosTheta;
            float d = a2 / (float) (Math.PI * Math.pow(nh * nh * (a2 - 1) + 1, 2));
            float pdf = d / 4;
            float sampleSolidAngle = 1f / (xi.length * pdf + 1e-6f);
            float lod =
                    alpha == 0
                            ? 0
                            : Math.max(0, 0.5f * log2(sampleSolidAngle / texelSolidAngle) + 1);
            lod = Math.min(lod, maxLevel);
            int l0 = (int) lod;
            int l1 = Math.min(l0 + 1, maxLevel);
            float t = lod - l0;
            sample(chain.get(l0), lx, ly, lz, rgb);
            float sr = rgb[0] * (1 - t);
            float sg = rgb[1] * (1 - t);
            float sb = rgb[2] * (1 - t);
            if (t > 0) {
                sample(chain.get(l1), lx, ly, lz, rgb);
                sr += rgb[0] * t;
                sg += rgb[1] * t;
                sb += rgb[2] * t;
            }
            r += sr * nl;
            g += sg * nl;
            b += sb * nl;
            weight += nl;
        }
        float inv = weight > 0 ? 1 / weight : 0;
        out[0] = r * inv;
        out[1] = g * inv;
        out[2] = b * inv;
    }
```

- **The density** of §8.6 simplifies to $D/4$ because with $\mathbf v = \mathbf n$, $\mathbf
  n\cdot\mathbf h = \mathbf v\cdot\mathbf h$.
- **`sampleSolidAngle`** is $\Omega_s = 1/(N p)$; the `1e-6f` keeps the division finite where $D$
  underflows. **`lod`** is §8.7's formula, clamped to the source chain (whose last level is 1×1); at
  $\alpha = 0$ every sample reads level 0.
- **Trilinear by hand**: the cooker's `sample` reads one level, so the code blends the two levels
  around the fractional `lod`, as a GPU's trilinear filter would.
- **Weighted by $\mathbf n\cdot\mathbf l$** and normalized by the sum of the weights: §8.4's first
  factor, with the importance sampling supplying $D$.

`EnvironmentTest.uniformLightGivesPiIrradianceAndAFlatPrefilter` fills a 16² cube with 0.5 and
checks that the prefiltered level 2 is 0.5 everywhere: a weighted average of a constant is that
constant, whatever the weights, so any error in the sampling would show.

### 22.4 The Hammersley points in Java

```java
    /** The Hammersley point set: radical inverse in base 2 against {@code i / n}. */
    static float[][] hammersley(int n) {
        float[][] out = new float[n][2];
        for (int i = 0; i < n; i++) {
            out[i][0] = (float) i / n;
            out[i][1] = (Integer.reverse(i) >>> 1) / (float) (1L << 31);
        }
        return out;
    }
```

`Integer.reverse` mirrors all 32 bits (§8.6). The result can be negative as an `int` (its top bit is
`i`'s lowest bit), so `>>> 1` shifts in a zero (L00 §6.3) and the division is by $2^{31}$ instead of
$2^{32}$: the same fraction, losing only the last of 32 bits. For $i = 1$: reversed, `0x80000000`;
shifted, `0x40000000` = $2^{30}$; divided by $2^{31}$: 0.5.

### 22.5 Projecting onto SH

```java
    public static float[] irradianceSh9(Cube cube) {
        double[] sh = new double[27];
        int n = cube.size();
        float[] d = new float[3];
        double[] y = new double[9];
        for (int f = 0; f < 6; f++) {
            float[] face = cube.faces()[f];
            for (int py = 0; py < n; py++) {
                for (int px = 0; px < n; px++) {
                    direction(f, px, py, n, d);
                    double s = 2.0 * (px + 0.5) / n - 1;
                    double t = 2.0 * (py + 0.5) / n - 1;
                    double solidAngle = (4.0 / (n * n)) / Math.pow(1 + s * s + t * t, 1.5);
                    basis(d[0], d[1], d[2], y);
                    int o = (py * n + px) * 3;
                    for (int i = 0; i < 9; i++) {
                        double w = y[i] * solidAngle;
                        sh[i * 3] += face[o] * w;
                        sh[i * 3 + 1] += face[o + 1] * w;
                        sh[i * 3 + 2] += face[o + 2] * w;
                    }
                }
            }
        }
        double[] band = {
            Math.PI,
            2 * Math.PI / 3,
            2 * Math.PI / 3,
            2 * Math.PI / 3,
            Math.PI / 4,
            Math.PI / 4,
            Math.PI / 4,
            Math.PI / 4,
            Math.PI / 4
        };
        float[] out = new float[27];
        for (int i = 0; i < 9; i++) {
            for (int c = 0; c < 3; c++) {
                out[i * 3 + c] = (float) (sh[i * 3 + c] * band[i]);
            }
        }
        return out;
    }
```

- **The projection** $L_{lm} = \int L\,Y_{lm}\,d\omega$ (§8.2) as a sum over every texel of every face:
  radiance × basis value × the texel's solid angle of §8.3.
- **`double` accumulators**: a 256² cube has 393,216 texels, and adding that many small terms in
  `float` (24 significant bits, L00 §7.3) would lose the small ones against the growing total.
- **`band`** is $\hat A_l$, once per function: one $\pi$, three $2\pi/3$, five $\pi/4$. The output is
  the coefficients ready to evaluate, $\hat A_l L_{lm}$, three per basis function.

```java
    /** Evaluates the real SH basis (bands 0..2) for a unit direction. */
    static void basis(double x, double y, double z, double[] out) {
        out[0] = 0.282095;
        out[1] = 0.488603 * y;
        out[2] = 0.488603 * z;
        out[3] = 0.488603 * x;
        out[4] = 1.092548 * x * y;
        out[5] = 1.092548 * y * z;
        out[6] = 0.315392 * (3 * z * z - 1);
        out[7] = 1.092548 * x * z;
        out[8] = 0.546274 * (x * x - y * y);
    }
```

The same polynomials, in the same order, as the shader's `jeddyIrradiance`: if one changed and the
other did not, every lit surface would be wrong in a direction-dependent way. `evaluateSh9` is the
shader's sum in Java, for tests.

### 22.6 The LUT in Java, and the importer

`brdfLut(size, samples, jobs)` is `brdf_lut.comp` line for line, fanned out over rows. One
simplification is written in a comment: since $\mathbf v$ lies in the $xz$ plane, the $y$ component of
$\mathbf h$ cannot change $\mathbf v\cdot\mathbf h$ or $\mathbf n\cdot\mathbf l$, so the Java version
does not compute it. The built-in `BrdfLutImporter` writes its result as `engine/brdf_lut.ktx2`
(128², 512 samples, `RG16F`) for tools (D39, D43).

`EnvironmentImporter.cook` ties the bakes together:

```java
        int size = Integer.highestOneBit(Math.max(4, s.integer("size", 256)));
        int maxLevels = Integer.numberOfTrailingZeros(size) - 1; // down to 4×4
        int levels = Math.max(1, Math.min(maxLevels, s.integer("levels", maxLevels)));
        int samples = Math.max(16, s.integer("samples", 128));
```

The face size is rounded down to a power of two (`highestOneBit`), at least 4. A power of two $2^k$
has $k$ trailing zero bits, and halving it $k - 2$ times reaches 4, so `numberOfTrailingZeros(size) -
1` is the number of levels from $2^k$ down to 4×4: 7 for 256. Then it bakes the base cube, the
prefiltered levels and the SH, writes the levels as `RGBA16F` (half floats clamped at 65,504, the
largest finite half float) into a KTX2 cube, and stores the 27 coefficients as 108 little-endian bytes
under the key `jeddy.sh9`. `EnvironmentTest.cooksAnHdrIntoAPrefilteredCube` cooks a 64 × 32 sky (bright
above, dark below) with face size 32 and checks the four levels (32, 16, 8, 4), the format, the 108
bytes, and that blue's $Y_{1,-1}$ coefficient is positive: more light from above.

## 23. Step 10: LightClusters

[`LightClusters`](../../modules/render/src/main/java/dev/jeddy/render/light/LightClusters.java) owns
the cluster grid of §10: its buffers, the two compute programs, the per-frame upload of lights and
decals, and the dispatches. Thread ownership: the GL thread.

### 23.1 Constants and fields

```java
public final class LightClusters implements AutoCloseable {
    /** Screen tile size in pixels. */
    public static final int TILE = 64;

    /** Depth slices. */
    public static final int SLICES = 24;

    /** Storage binding of the light array. */
    public static final int LIGHTS_BINDING = 2;

    /** Storage binding of the per-cluster ranges. */
    public static final int RANGES_BINDING = 3;

    /** Storage binding of the light index list. */
    public static final int ITEMS_BINDING = 4;

    /** Storage binding of the cluster bounds. */
    public static final int BOUNDS_BINDING = 7;

    /** Storage binding of the decal array. */
    public static final int DECALS_BINDING = 6;

    /** Average light references per cluster the index list has room for. */
    public static final int AVERAGE_PER_CLUSTER = 48;

    private static final int GROUP = 64;

    private final GpuDevice device;
    private final ProgramCache programs;
    private final int boundsProgram;
    private final int cullProgram;
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment header = arena.allocate(16, 16);
    private final float[] lastProjection = new float[16];
    private int dimX;
    private int dimY;
    private int clusters;
    private int boundsBuffer;
    private int rangesBuffer;
    private int itemsBuffer;
    private int capacity;
    private boolean boundsDirty = true;
    private long lightsOffset;
    private long lightsBytes;
    private int lightCount;
    private long decalsOffset;
    private long decalsBytes;
    private int decalCount;
    private int width;
    private int height;
```

- **Tile and slices.** 64-pixel tiles and 24 slices are the grid of §10.2. Fewer, larger clusters
  make the cull pass cheaper and the lists longer; more, smaller ones the reverse. These values keep
  the grid around ten thousand clusters at common resolutions.
- **The bindings** are the shader's `layout(binding = …)` numbers, in one place (§1.2's table).
- **`AVERAGE_PER_CLUSTER = 48`** sizes the shared list: the list has room for 48 entries per cluster
  *on average*, so a crowded cluster can hold far more as long as others hold fewer (§10.5).
- **`GROUP = 64`** must equal the shaders' `local_size_x`.
- **`header`**: 16 bytes of native memory from which the list's counter is reset each frame (§23.4).
- **`lastProjection`** remembers the projection the bounds were computed for.
- The buffers are **handles** in the device's buffer table (L01 §7).

### 23.2 Constructor and resize

```java
    public LightClusters(GpuDevice device, ProgramCache programs) {
        this.device = device;
        this.programs = programs;
        boundsProgram =
                programs.defineCompute(
                        "cluster bounds", "pbr/cluster_bounds.comp", ShaderFeatures.NONE);
        cullProgram =
                programs.defineCompute("light cull", "pbr/light_cull.comp", ShaderFeatures.NONE);
    }

    /** Sizes the grid for a {@code w}×{@code h} viewport. */
    public void resize(int w, int h) {
        if (w == width && h == height) {
            return;
        }
        width = w;
        height = h;
        dimX = (w + TILE - 1) / TILE;
        dimY = (h + TILE - 1) / TILE;
        clusters = dimX * dimY * SLICES;
        release();
        boundsBuffer = device.buffers().create((long) clusters * 32, 0, "cluster bounds");
        rangesBuffer = device.buffers().create((long) clusters * 8, 0, "cluster ranges");
        capacity = clusters * AVERAGE_PER_CLUSTER;
        itemsBuffer =
                device.buffers()
                        .create(16 + (long) capacity * 4, GpuBuffers.DYNAMIC, "cluster lights");
        boundsDirty = true;
    }
```

- The constructor only **defines** the two compute programs; `ProgramCache` compiles them (chapter
  05, on the program cache), and `dispatch` waits for them on first use.
- `resize` computes the grid with the round-up of L00 §2.5, frees the old buffers (`release`) and
  creates new ones. Sizes per cluster: the bounds are two `vec4`s (32 bytes), the range one `uvec2`
  (8 bytes); the list is a 16-byte header plus `capacity` `uint`s.
- **Buffer flags.** The bounds and ranges are written only by shaders, so they are created with no
  flags at all: the driver may keep them entirely in GPU memory (L01 §4). The list is `DYNAMIC`
  because its header has a CPU fallback path (§23.4).
- At 1600 × 900: 9000 clusters, so 288,000 bytes of bounds, 72,000 of ranges and 1,728,016 of list
  (432,000 entries, 1.65 MiB).
- `boundsDirty` forces the bounds pass on the next frame.

### 23.3 Uploading the frame's lights and decals

```java
    public boolean upload(RenderPacket p, FrameRing ring) {
        int align = device.caps().shaderStorageBufferOffsetAlignment();
        decalCount = p.decalCount;
        decalsBytes = Math.max(112L, (long) p.decalCount * RenderPacket.DECAL_FLOATS * 4);
        decalsOffset = ring.allocate(decalsBytes, align);
        if (decalsOffset < 0) {
            decalCount = 0;
            lightCount = 0;
            return false;
        }
        if (p.decalCount > 0) {
            MemorySegment.copy(
                    p.decals,
                    0,
                    ring.memory(),
                    ValueLayout.JAVA_FLOAT,
                    decalsOffset,
                    p.decalCount * RenderPacket.DECAL_FLOATS);
        }
        lightCount = p.lightCount;
        lightsBytes = Math.max(64L, (long) p.lightCount * RenderPacket.LIGHT_FLOATS * 4);
        lightsOffset = ring.allocate(lightsBytes, align);
        if (lightsOffset < 0) {
            lightCount = 0;
            return false;
        }
        if (p.lightCount > 0) {
            MemorySegment.copy(
                    p.lights,
                    0,
                    ring.memory(),
                    ValueLayout.JAVA_FLOAT,
                    lightsOffset,
                    p.lightCount * RenderPacket.LIGHT_FLOATS);
        }
        return true;
    }
```

- **Into the frame ring** (L01): per-frame data, written by the CPU into the region no frame in
  flight reads, at the storage-buffer offset alignment the driver requires.
- **One bulk copy each** (D44): the packet already holds the GPU layout, so `MemorySegment.copy` from
  the `float[]` into the mapping is a sequential write, the shape write-combined memory likes (L01
  §3.3).
- **Never an empty range.** Binding a storage range of 0 bytes is not allowed, so at least one
  record's worth is reserved even with no lights (64 bytes) or no decals (112 bytes: a decal record
  is 28 floats). The shaders never read it: the counts are 0.
- **A full ring** zeroes the counts and returns `false`: no lights this frame rather than a crash.
  (§37 notes what `SceneRenderer` does, and does not do, with that result.)

### 23.4 dispatch: bounds when needed, the counter, the cull

```java
    public void dispatch(StateCache state, RenderPacket p, FrameRing ring) {
        boolean changed = boundsDirty;
        for (int i = 0; i < 16 && !changed; i++) {
            changed = lastProjection[i] != p.projection[i];
        }
        state.bindStorage(
                BOUNDS_BINDING, device.buffers().glName(boundsBuffer), 0, (long) clusters * 32);
        int groups = (clusters + GROUP - 1) / GROUP;
        if (changed) {
            int program = programs.getBlocking(boundsProgram, 0);
            state.useProgram(program);
            glDispatchCompute(groups, 1, 1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            System.arraycopy(p.projection, 0, lastProjection, 0, 16);
            boundsDirty = false;
        }
```

1. **Did the projection change?** Sixteen float comparisons, stopping at the first difference. The
   boxes live in view space (§10.3), so a moving or turning camera does not change them; a new field of
   view, aspect ratio or near plane does. (So, the code assumes, does nothing else; §37 shows a case
   where that assumption breaks.)
2. **The bounds buffer** is bound for both shaders: the bounds pass writes it, the cull pass reads it.
3. **Groups**: $\lceil 9000/64 \rceil = 141$ workgroups, 9024 invocations, 24 of which find
   themselves past the end and return.
4. **The bounds pass**, when needed, followed by a storage barrier: the cull pass reads what it wrote.

```java
        header.set(ValueLayout.JAVA_INT, 0, 0);
        header.set(ValueLayout.JAVA_INT, 4, capacity);
        // The item counter resets on the GPU, ordered after last frame's cull (D103).
        if (!ring.upload(device.buffers().glName(itemsBuffer), 0, header, 0, header.byteSize())) {
            device.buffers().update(itemsBuffer, 0, header);
        }
        bind(state, ring);
        state.useProgram(programs.getBlocking(cullProgram, 0));
        glDispatchCompute(groups, 1, 1);
        // Storage: the shading pass reads the lists. Buffer update: next frame's counter reset is
        // a buffer copy over memory these atomics wrote, which GL orders only after this bit.
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT);
    }
```

5. **The header**: the counter at byte 0 back to 0, the capacity at byte 4 (the shader reads it to
   know when the list is full). It reaches the GPU as a ring-staged **copy** (D103, L01 §14.4): a
   command in the stream after last frame's cull pass, never a CPU write into memory that pass may
   still be incrementing. Only if this frame's ring region is full does it fall back to
   `glNamedBufferSubData`.
6. **The cull pass**, then the barrier with the two bits of §10.5: storage (the lit passes read the
   ranges and the list) and buffer update (the next frame's reset copy must wait for this frame's
   atomics). The frame graph also inserts a storage barrier before the passes that declare
   `readsStorage(clusterItems)` (chapter 06, on barriers from declared reads); that one is redundant
   with this one, which is harmless, and this one also covers the ranges buffer, which the graph does
   not know about.

```java
    /** Binds the lights, decals, ranges and index list for shading. */
    public void bind(StateCache state, FrameRing ring) {
        state.bindStorage(LIGHTS_BINDING, ring.glName(), lightsOffset, lightsBytes);
        state.bindStorage(DECALS_BINDING, ring.glName(), decalsOffset, decalsBytes);
        state.bindStorage(
                RANGES_BINDING, device.buffers().glName(rangesBuffer), 0, (long) clusters * 8);
        state.bindStorage(
                ITEMS_BINDING, device.buffers().glName(itemsBuffer), 0, 16 + (long) capacity * 4);
    }
```

The same four bindings serve the cull pass and the lit passes. `rangesGlName` and `itemsGlName` give
tests the raw GL names (the cluster test reads the counter back, §33); `close` releases the buffers and
the arena.

## 24. Step 11: the cluster bounds shader

Create `pbr/cluster_bounds.comp`: one invocation per cluster, writing its view-space box (§10.3).

```glsl
#version 460 core
// View-space bounds of every cluster (TASKS M7-03): screen tiles × depth slices (exponential
// for perspective, linear for orthographic). Rerun when the projection or the viewport changes.
#include "/engine/frame.glsl"

layout(local_size_x = 64) in;

layout(std430, binding = 7) writeonly buffer JeddyClusterBounds {
    vec4 jBounds[]; // min.xyz, max.xyz per cluster (two entries)
};
```

The box is stored as two `vec4`s with an unused `w`: a `vec3` array would have a 16-byte stride in
std430 anyway (chapter 05, the `vec3` array trap), so this only makes it explicit.

```glsl
// View-space point on the line through a window position at view depth z (> 0).
vec3 pointAtDepth(vec2 ndc, float z) {
    vec4 a = jInverseProjection * vec4(ndc, 1.0, 1.0); // near plane (reversed-Z)
    vec4 b = jInverseProjection * vec4(ndc, 0.5, 1.0);
    vec3 pa = a.xyz / a.w;
    vec3 pb = b.xyz / b.w;
    float t = (-z - pa.z) / (pb.z - pa.z);
    return pa + t * (pb - pa);
}
```

Two points on the pixel's ray, then the one at view $z = -\text{depth}$:

- `a` and `b` unproject the same NDC $x, y$ at depths 1 and 0.5 (chapter 04, on unprojection); the
  divisions by `w` finish the projective transform.
- The line through them is $\mathbf p(t) = \mathbf p_a + t(\mathbf p_b - \mathbf p_a)$. Its $z$ is
  $-\text{depth}$ when $p_{a,z} + t(p_{b,z} - p_{a,z}) = -\text{depth}$, which gives `t`.
- *Worked*, the demo's perspective with near plane 0.5: depth 1 unprojects to view $z = -0.5$ and
  depth 0.5 to $z = -1$ (reversed-Z infinite projection stores $n/z$, chapter 04). For depth 21.92:
  $t = (-21.92 + 0.5)/(-1 + 0.5) = 42.84$, the point 42.84 times the segment's length along the ray.
- For an orthographic projection, depth is linear and the rays parallel; the same lines still work,
  which is why the shader needs no branch here.

```glsl
float sliceDepth(uint slice) {
    float n = jClusterZ.x;
    float f = jClusterZ.y;
    float k = float(slice) / float(jClusterDims.z);
    return jeddyOrthographic() ? mix(n, f, k) : n * pow(f / n, k);
}
```

The inverse of the slicing (§10.2): $z_k = n(f/n)^{k/S}$, or linear for orthographic.

```glsl
void main() {
    uint index = gl_GlobalInvocationID.x;
    uvec3 dims = jClusterDims.xyz;
    if (index >= dims.x * dims.y * dims.z) {
        return;
    }
    uint x = index % dims.x;
    uint y = (index / dims.x) % dims.y;
    uint z = index / (dims.x * dims.y);
    float tile = float(jClusterDims.w);
    vec2 minPx = vec2(x, y) * tile;
    vec2 maxPx = min(vec2(x + 1u, y + 1u) * tile, jViewport.xy);
    vec2 ndcMin = minPx * jViewport.zw * 2.0 - 1.0;
    vec2 ndcMax = maxPx * jViewport.zw * 2.0 - 1.0;
    float zNear = sliceDepth(z);
    float zFar = sliceDepth(z + 1u);
    vec3 lo = vec3(1e30);
    vec3 hi = vec3(-1e30);
    for (int c = 0; c < 8; c++) {
        vec2 ndc = vec2((c & 1) == 0 ? ndcMin.x : ndcMax.x, (c & 2) == 0 ? ndcMin.y : ndcMax.y);
        vec3 p = pointAtDepth(ndc, (c & 4) == 0 ? zNear : zFar);
        lo = min(lo, p);
        hi = max(hi, p);
    }
    jBounds[index * 2u] = vec4(lo, 0.0);
    jBounds[index * 2u + 1u] = vec4(hi, 0.0);
}
```

1. **Decode the index** written by `jeddyClusterIndex` as $((z \cdot d_y + y)\,d_x + x)$: the
   remainder by $d_x$ is $x$, the next division and remainder $y$, the last division $z$ (L00 §2.4).
2. **The tile's pixel rectangle**, clipped to the viewport for the partial last row and column, then
   to NDC with `jViewport.zw` = $(1/W, 1/H)$.
3. **The slab** between the two slice depths.
4. **Eight corners from the three low bits of `c`**: bit 0 picks min or max $x$, bit 1 min or max
   $y$, bit 2 near or far depth (L00 §6.2) — $2^3 = 8$ combinations, one loop.
5. **The AABB** grows from "inverted infinity" ($\mathbf{lo} = +10^{30}$, $\mathbf{hi} = -10^{30}$)
   with component-wise `min`/`max`.

## 25. Step 12: the light cull shader

Create `pbr/light_cull.comp`: one invocation per cluster, every frame (§10.4–10.5).

```glsl
#version 460 core
// Assigns lights and decals to clusters (TASKS M7-03, M7-07): one invocation per cluster tests
// every light's and decal's bounding sphere (in view space) against the cluster's bounds and
// appends the hits, lights first, to a global index list. The count packs lights (low 16 bits)
// and decals (high 16). The counter at the start of the list buffer is cleared every frame.
#include "/engine/frame.glsl"

layout(local_size_x = 64) in;

struct JeddyLight {
    vec4 positionRange;
    vec4 colorType;
    vec4 directionOuter;
    vec4 params;
};

layout(std430, binding = 2) readonly buffer JeddyLights {
    JeddyLight jLights[];
};

struct JeddyDecal {
    vec4 rows[3];
    vec4 bounds;
    vec4 color;
    vec4 params;
    vec4 extra;
};

layout(std430, binding = 6) readonly buffer JeddyDecals {
    JeddyDecal jDecals[];
};

layout(std430, binding = 3) writeonly buffer JeddyClusterGrid {
    uvec2 jClusterRanges[];
};

layout(std430, binding = 4) buffer JeddyClusterItems {
    uint jItemCount;
    uint jItemCapacity;
    uint jPad0;
    uint jPad1;
    uint jClusterLights[];
};

layout(std430, binding = 7) readonly buffer JeddyClusterBounds {
    vec4 jBounds[];
};

const uint MAX_LIGHTS_PER_CLUSTER = 128u;
const uint MAX_DECALS_PER_CLUSTER = 32u;
```

- **Its own declarations.** This shader writes bindings 3 and 4, which `lights.glsl` declares
  `readonly` (and without the header), so it does not include that file; it repeats the light struct,
  which must stay identical to `JeddyLight` there and to the Java record (§17). The decal record is
  seven `vec4`s, 112 bytes, matching `DECAL_FLOATS = 28`.
- **The list buffer's header.** `jItemCount` (the counter), `jItemCapacity`, and two pads so that
  `jClusterLights` starts at byte 16. The pads are not required by std430 (a `uint` array aligns to
  4); they keep the header the same 16 bytes that `LightClusters` uploads.
- **Per-cluster caps**: at most 128 lights and 32 decals per cluster; beyond that a cluster drops
  the rest (in index order), which bounds the local array below and every pixel's loop.

```glsl
bool touches(vec3 center, float radius, vec3 lo, vec3 hi) {
    vec3 c = (jView * vec4(center, 1.0)).xyz;
    vec3 d = c - clamp(c, lo, hi);
    return dot(d, d) <= radius * radius;
}
```

§10.4's test, after moving the sphere's center from world to view space with the view matrix (the
boxes are in view space). Every invocation transforms every light again: 9000 times the same
transform per light per frame; exercise 7's pre-pass would do it once.

```glsl
void main() {
    uint index = gl_GlobalInvocationID.x;
    uvec3 dims = jClusterDims.xyz;
    if (index >= dims.x * dims.y * dims.z) {
        return;
    }
    vec3 lo = jBounds[index * 2u].xyz;
    vec3 hi = jBounds[index * 2u + 1u].xyz;
    uint hits[MAX_LIGHTS_PER_CLUSTER + MAX_DECALS_PER_CLUSTER];
    uint lights = 0u;
    for (uint i = 0u; i < jCounts.x && lights < MAX_LIGHTS_PER_CLUSTER; i++) {
        vec4 pr = jLights[i].positionRange;
        if (touches(pr.xyz, pr.w > 0.0 ? pr.w : 1e30, lo, hi)) {
            hits[lights++] = i;
        }
    }
    uint decals = 0u;
    for (uint i = 0u; i < jCounts.y && decals < MAX_DECALS_PER_CLUSTER; i++) {
        vec4 b = jDecals[i].bounds;
        if (touches(b.xyz, b.w, lo, hi)) {
            hits[lights + decals++] = i;
        }
    }
```

- **The loop bound** `jCounts.x` is the frame's uploaded light count (§15); the second condition
  stops at the cap.
- **Unlimited range.** A range of 0 means "no window" (§7.1), so the radius becomes $10^{30}$, and
  `radius * radius` is $10^{60}$, which does not fit in a float: IEEE arithmetic turns it into
  $+\infty$, and every distance compares as less. Such a light lands in every cluster. (This leans on
  the GPU producing $+\infty$ on overflow, as IEEE-754 arithmetic does and desktop GPUs implement.)
- **Lights first, then decals**, into one local array: `hits[0 .. lights)` are light indices,
  `hits[lights .. lights + decals)` decal indices.

```glsl
    uint count = lights + decals;
    uint offset = atomicAdd(jItemCount, count);
    if (offset + count > jItemCapacity) {
        // Out of room: keep what fits, lights first.
        uint room = offset < jItemCapacity ? jItemCapacity - offset : 0u;
        lights = min(lights, room);
        decals = min(decals, room - lights);
        count = lights + decals;
    }
    for (uint k = 0u; k < count; k++) {
        jClusterLights[offset + k] = hits[k];
    }
    jClusterRanges[index] = uvec2(offset + 4u, lights | decals << 16u);
}
```

1. **Reserve** a block of `count` entries with one `atomicAdd` (§10.5). Even a cluster with no hits
   calls it (adding 0); the returned offset is then never used for writing.
2. **Overflow.** If the block runs past the list's capacity, the cluster keeps what fits, lights
   before decals. `room` is computed so it cannot underflow (unsigned subtraction would wrap around,
   L00 §5): 0 when the block starts past the end. If the lights were cut, `room - lights` is 0 and no
   decals remain; otherwise the decals take what is left after all the lights. The counter itself
   keeps counting past the capacity, which does no harm: only `offset + k < capacity` is ever written.
   Which clusters overflow depends on the order of the atomics, which changes from frame to frame, so
   an overfull list shows as lights flickering in random clusters; that is why the capacity is
   generous.
3. **Copy** the hits into the reserved block.
4. **Record the range.** The offset gets **+4** because the list buffer starts with the 4-word
   header, and the lit shader (`lights.glsl`) reads binding 4 as a bare `uint` array from byte 0: entry
   `offset` of this shader's `jClusterLights` is entry `offset + 4` there. The count packs lights in
   the low 16 bits and decals in the high 16 (D45): `lights | decals << 16u` (`<<` binds tighter than
   `|` in GLSL, as in Java), and the lit shader takes `range.y & 0xFFFFu` lights, while the decal code
   takes the high half (chapter 08).

`SceneRendererTest.clusteredPointLightsShadeOnlyWhereTheyReach` checks the whole path through the
counter (§33).

## 26. Step 13: SunShadows

[`SunShadows`](../../modules/render/src/main/java/dev/jeddy/render/shadow/SunShadows.java) builds the
cascades of §11.3–11.6: it fits them on the CPU each frame, culls and builds the casters' draws, writes
the receivers' uniform block, and renders the depth layers. Thread ownership: the render thread.

### 26.1 Constants, settings and fields

```java
public final class SunShadows implements AutoCloseable {
    /** Texture unit of the cascade array. */
    public static final int UNIT = 7;

    /** Uniform binding of the cascade data. */
    public static final int BINDING = 1;

    /** Bytes of the uniform block. */
    public static final int BLOCK_BYTES = 4 * 64 + 3 * 16;

    /**
     * Settings.
     *
     * @param cascades 1 to 4
     * @param size texels per side of each cascade
     * @param distance how far from the camera shadows reach (meters)
     * @param lambda split scheme: 0 uniform, 1 logarithmic
     * @param pullback extra distance towards the light that still casts into a cascade
     * @param normalOffset receiver offset along the normal, in texels
     */
    public record Config(
            int cascades,
            int size,
            float distance,
            float lambda,
            float pullback,
            float normalOffset) {
        /** Three 2048² cascades over 60 m. */
        public static final Config DEFAULT = new Config(3, 2048, 60f, 0.75f, 60f, 1.5f);
    }
```

- **The uniform block** holds four matrices (64 bytes each) and three `vec4`s: $4 \cdot 64 + 3 \cdot 16
  = 304$ bytes. Four matrices whatever the cascade count, so the GLSL declaration is fixed; that is
  why `cascades` is limited to 4.
- **The settings** are §11's: three cascades of 2048², shadows up to 60 m, $\lambda = 0.75$, 60 m of
  pull-back, a normal offset of 1.5 texels. Each 2048² layer of 32-bit depth is $2048^2 \cdot 4 =
  16$ MiB, so the three cascades take 48 MiB of GPU memory.

```java
    private final GpuDevice device;
    private final MaterialSystem materials;
    private final Config config;
    private final int texture;
    private final int[] framebuffers;
    private final DrawBuilder[] builders;
    private final Culling culling;
    private final int sampler;
    private final float[][] matrices;
    private final float[][] planes;
    private final float[] splits = new float[4];
    private final float[] texelWorld = new float[4];
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment matrix = arena.allocate(64, 16);
    private final MemorySegment clearDepth = arena.allocate(16, 16);
    private final float[] scratch = new float[16];
    private final float[] inverseView = new float[16];
    private final float[] inverseProjection = new float[16];
    private final float[] lightRotation = new float[16];
    private final float[] lightProjection = new float[16];
    private final float[] corners = new float[24];
    private final float[] point = new float[3];
    private int[] visible = new int[1024];
    private int cascadeCount;
    private StaticScene staticScene;
    private final float[] eye = new float[3];
    private float lod;
    private boolean orthographic;
    private long blockOffset = -1;
    private final MaterialSystem.DepthUniforms uniforms =
            program -> {
                glProgramUniformMatrix4fv(program, 0, 1, false, matrix);
                glProgramUniform1i(program, 1, 0); // no camera fade in shadow maps
            };
```

- **One texture, one framebuffer per cascade**: the cascades are layers of one depth **array
  texture** (chapter 05, on kinds of texture), so receivers sample them through one binding, choosing
  the layer by number. Each layer is a framebuffer's depth attachment.
- **One `DrawBuilder` per cascade**: each cascade sees different casters, so each builds its own
  sorted, batched draws (chapter 06) into the frame ring.
- **Every scratch array is a field**, allocated once: a frame of `SunShadows` allocates nothing
  (`framesAllocateNothing`, §33).
- **`matrix`** is native memory for `glProgramUniformMatrix4fv`, which takes a pointer.
- **`uniforms`** is the callback the depth binder calls whenever it switches program (chapter 06, on
  the binders): it sets the cascade's matrix at uniform location 0 of `pbr/depth.vert`, and turns off
  the camera-fade dither (uniform 1 of `pbr/depth.frag`): a roof faded out for the camera must still
  cast its shadow. A lambda stored in a field is created once; calling it allocates nothing.

### 26.2 Constructor

```java
    public SunShadows(
            GpuDevice device,
            MeshRegistry meshes,
            MaterialSystem materials,
            JobSystem jobs,
            Config config) {
        if (config.cascades() < 1 || config.cascades() > 4) {
            throw new IllegalArgumentException("1 to 4 cascades");
        }
        this.device = device;
        this.materials = materials;
        this.config = config;
        texture =
                device.textures()
                        .create(
                                GpuTextures.KIND_2D_ARRAY,
                                GL_DEPTH_COMPONENT32F,
                                config.size(),
                                config.size(),
                                config.cascades(),
                                1,
                                "sun shadow cascades");
        framebuffers = new int[config.cascades()];
        builders = new DrawBuilder[config.cascades()];
        matrices = new float[config.cascades()][16];
        planes = new float[config.cascades()][CullOps.PLANES_FLOATS];
        for (int c = 0; c < config.cascades(); c++) {
            framebuffers[c] =
                    device.framebuffers().createDepthLayer(texture, c, "sun cascade " + c);
            builders[c] = new DrawBuilder(meshes, materials.table(), jobs);
        }
        culling = new Culling(jobs);
        sampler = device.samplers().get(SamplerDesc.SHADOW);
    }
```

`GL_DEPTH_COMPONENT32F`, one mip level (shadow maps are not mipmapped: averaging depths is
meaningless, §11.6), and the comparison sampler of §11.1. `createDepthLayer(texture, c, …)` attaches
layer `c` of the array as a framebuffer's depth (chapter 05, on framebuffers).

### 26.3 update: fitting the cascades and building their draws

`SceneRenderer.render` calls `update` on the main thread, before any GL work of the frame, with job
workers available for culling and draw building.

```java
    public void update(RenderPacket p, FrameRing ring, float lodPixels) {
        cascadeCount = 0;
        float[] sun = p.sunColor;
        if (!p.sunShadows || (sun[0] <= 0 && sun[1] <= 0 && sun[2] <= 0)) {
            writeBlock(ring);
            return;
        }
        float[] d = p.sunDirection;
        float len = (float) Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        if (len == 0) {
            writeBlock(ring);
            return;
        }
        float lx = d[0] / len;
        float ly = d[1] / len;
        float lz = d[2] / len;
        // Rotation-only light view: looking along the light direction from the origin.
        boolean nearlyVertical = Math.abs(ly) > 0.99f;
        Mat4Ops.lookAt(
                0,
                0,
                0,
                lx,
                ly,
                lz,
                nearlyVertical ? 1 : 0,
                nearlyVertical ? 0 : 1,
                0,
                lightRotation,
                0);
        Mat4Ops.invert(p.view, 0, inverseView, 0);
        Mat4Ops.invert(p.projection, 0, inverseProjection, 0);
```

1. **No sun, no shadows**: with the packet's `sunShadows` off or a black sun, zero cascades. The block
   is still written (with a cascade count of 0, so receivers return 1 at once, §27) because the lit
   shader's variant still reads it.
2. **The light's rotation** (§11.4): a look-at from the origin towards the direction sunlight travels.
   "Up" is $+y$, unless the sun is within about 8° of vertical (|y| > 0.99), where $+y$ is nearly
   parallel to the view direction and the look-at's cross product breaks down; then $+x$ (chapter 04,
   on when look-at breaks). The result is a pure rotation: "light space" is the world, turned.
3. **The camera's inverse view and projection**, to turn the frustum corners back into world space.

```java
        int n = config.cascades();
        float near = Math.max(1e-3f, p.near);
        float far = Math.max(near * 2, Math.min(p.far, config.distance()));
        float previous = near;
        for (int c = 0; c < n; c++) {
            float t = (float) (c + 1) / n;
            float log = (float) (near * Math.pow(far / near, t));
            float uni = near + (far - near) * t;
            float split = config.lambda() * log + (1 - config.lambda()) * uni;
            fitCascade(c, previous, split, p);
            splits[c] = split;
            previous = split;
        }
        cascadeCount = n;
```

The practical split scheme of §11.3, term for term: `t` is $i/N$, `log` and `uni` the two schemes,
`split` their blend; each cascade runs from the previous split (or the near plane) to its own.

```java
        if (visible.length < p.count()) {
            visible = new int[Math.max(p.count(), visible.length * 2)];
        }
        boolean gpu = staticScene != null && staticScene.active();
        System.arraycopy(p.eye, 0, eye, 0, 3);
        lod = lodPixels;
        orthographic = p.orthographic;
        for (int c = 0; c < n; c++) {
            int count =
                    culling.cull(
                            p,
                            planes[c],
                            RenderPacket.FLAG_CASTS_SHADOW,
                            gpu ? RenderPacket.FLAG_STATIC : 0,
                            0,
                            0,
                            0,
                            -1,
                            visible);
            if (gpu) {
                count = appendCpuStatic(p, planes[c], count);
            }
            builders[c].setLod(lodPixels, p.orthographic, 1f);
            builders[c].build(p, visible, count, ring);
        }
        writeBlock(ring);
    }
```

- **Culling per cascade** (chapter 06, on culling on the CPU) against the cascade's own frustum
  planes, keeping only instances flagged `FLAG_CASTS_SHADOW`. With GPU-driven culling active
  (D49), static instances are excluded here and culled on the GPU per cascade in `render`
  (`StaticScene.VIEW_CASCADES + c`); `appendCpuStatic` adds back the few static casters the GPU path
  leaves to the CPU (blended materials).
- **The level of detail** follows the main view's (`lodPixels`), so a shadow is cast by the same mesh
  the camera sees.
- `eye`, `lod` and `orthographic` are kept for the GPU culling in `render`, which runs later, inside
  the frame graph.

(The method's Javadoc mentions "Main-view LOD settings are copied from {@code lodSource}", a
parameter that no longer exists; it is `lodPixels`.)

### 26.4 fitCascade: sphere, texel size, snapping

```java
    private void fitCascade(int c, float a, float b, RenderPacket p) {
        // World-space corners of the slice.
        for (int k = 0; k < 8; k++) {
            float nx = (k & 1) == 0 ? -1 : 1;
            float ny = (k & 2) == 0 ? -1 : 1;
            viewPointAtDepth(nx, ny, (k & 4) == 0 ? a : b);
            Mat4Ops.project(inverseView, 0, point[0], point[1], point[2], corners, k * 3);
        }
```

The same eight-corner loop as the cluster bounds (§24), over the whole screen ($\text{ndc} = \pm1$):
`viewPointAtDepth` finds each corner in view space, and `Mat4Ops.project` with the inverse view
matrix moves it into world space.

```java
        float cx = 0;
        float cy = 0;
        float cz = 0;
        for (int k = 0; k < 8; k++) {
            cx += corners[k * 3];
            cy += corners[k * 3 + 1];
            cz += corners[k * 3 + 2];
        }
        cx /= 8;
        cy /= 8;
        cz /= 8;
        float r = 0;
        for (int k = 0; k < 8; k++) {
            float dx = corners[k * 3] - cx;
            float dy = corners[k * 3 + 1] - cy;
            float dz = corners[k * 3 + 2] - cz;
            r = Math.max(r, (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        r = (float) Math.ceil(r * 16) / 16; // quantized so it does not flicker with rounding
```

The sphere of §11.4: the corners' average as the center, the farthest corner as the radius, rounded
**up** to a multiple of 1/16 m (rounding down could leave a corner outside). It is not the smallest
sphere around the corners, but it contains them all and, being computed from distances between them,
does not change as the camera moves.

```java
        // Center in light space, snapped to whole texels.
        Mat4Ops.project(lightRotation, 0, cx, cy, cz, point, 0);
        float texel = 2 * r / config.size();
        float sx = (float) Math.floor(point[0] / texel) * texel;
        float sy = (float) Math.floor(point[1] / texel) * texel;
        float distance = -point[2];
        Mat4Ops.orthoReversedZ(
                sx - r,
                sx + r,
                sy - r,
                sy + r,
                distance - r - config.pullback(),
                distance + r,
                lightProjection,
                0);
        Mat4Ops.mul(lightProjection, 0, lightRotation, 0, matrices[c], 0);
        CullOps.extractPlanes(matrices[c], 0, planes[c]);
        texelWorld[c] = texel;
    }
```

1. **Into light space**: the rotation turns the center so that the light looks along $-z$.
2. **The texel size** $w = 2r/\text{size}$: the box is $2r$ wide and has `size` texels.
3. **Snapping**: `Math.floor(x / w) * w` is the largest multiple of $w$ not above $x$ (L00 §2.5's floor,
   on floats). *Worked*: the demo's middle cascade has $w = 2 \cdot 12.1875 / 2048 = 0.0119$ m. A
   center at light-space $x = 3.1000$ snaps to $260 \cdot 0.0119 = 3.0945$; the camera moves 4 mm and
   the center goes to 3.1040, which still snaps to 3.0945: the map does not move. Another 6 mm, to
   3.1100, and it snaps to $261 \cdot 0.0119 = 3.1064$: one whole texel.
4. **Depth range**: the center's distance along the light's view direction is $-z$ (it looks along
   $-z$). The box runs from $r + \text{pullback}$ before it to $r$ behind it (§11.5).
5. **The projection** is orthographic with reversed-Z (chapter 04, on the two projections), then
   multiplied by the rotation: `matrices[c]` takes a world point straight to the cascade's clip space.
6. **Frustum planes** of that matrix for culling the cascade's casters (chapter 04, on planes from the
   view-projection matrix), and the texel size for the receivers' normal offset.

```java
    /** View-space point on the camera ray through NDC (x, y) at view depth z. */
    private void viewPointAtDepth(float x, float y, float z) {
        Mat4Ops.project(inverseProjection, 0, x, y, 1f, scratch, 0);
        float ax = scratch[0];
        float ay = scratch[1];
        float az = scratch[2];
        Mat4Ops.project(inverseProjection, 0, x, y, 0.5f, scratch, 3);
        float t = (-z - az) / (scratch[5] - az);
        point[0] = ax + t * (scratch[3] - ax);
        point[1] = ay + t * (scratch[4] - ay);
        point[2] = -z;
    }
```

Line for line the GLSL `pointAtDepth` of §24: two unprojected points at depths 1 and 0.5 (`project`
divides by $w$), then interpolation to view $z = -\text{depth}$, which is written exactly.

### 26.5 writeBlock: the receivers' uniform block

```java
    private void writeBlock(FrameRing ring) {
        blockOffset =
                ring.allocate(
                        BLOCK_BYTES, Math.max(16, device.caps().uniformBufferOffsetAlignment()));
        if (blockOffset < 0) {
            return;
        }
        MemorySegment m = ring.memory();
        for (int c = 0; c < 4; c++) {
            float[] src = c < cascadeCount ? matrices[c] : matrices[0];
            MemorySegment.copy(src, 0, m, ValueLayout.JAVA_FLOAT, blockOffset + c * 64L, 16);
        }
        long o = blockOffset + 256;
        for (int c = 0; c < 4; c++) {
            m.set(ValueLayout.JAVA_FLOAT, o + c * 4L, c < cascadeCount ? splits[c] : 0f);
            m.set(ValueLayout.JAVA_FLOAT, o + 16 + c * 4L, c < cascadeCount ? texelWorld[c] : 0f);
        }
        m.set(ValueLayout.JAVA_FLOAT, o + 32, config.normalOffset());
        m.set(ValueLayout.JAVA_FLOAT, o + 36, config.size());
        m.set(ValueLayout.JAVA_FLOAT, o + 40, 0.9f);
        m.set(ValueLayout.JAVA_FLOAT, o + 44, cascadeCount);
    }
```

The block, in std140 (chapter 05, on the layout rules), matching `JeddySunShadows` of §27:

| Offset | Member | Written |
|---|---|---|
| 0, 64, 128, 192 | `mat4 jCascadeMatrix[4]` | the cascades' matrices; unused slots repeat matrix 0 (never read) |
| 256 | `vec4 jCascadeSplits` | far view depth of each cascade, 0 for unused |
| 272 | `vec4 jCascadeTexel` | world texel size of each cascade |
| 288 | `vec4 jSunShadowParams` | normal offset (texels), map size, fade start 0.9, cascade count |

Per-frame data, so it goes into the ring (L01), at the uniform-buffer offset alignment.

### 26.6 render: the depth layers

```java
    public void render(StateCache state, FrameRing ring) {
        if (cascadeCount == 0) {
            return;
        }
        glEnable(GL_DEPTH_CLAMP);
        glPolygonOffset(-1.5f, -2f);
        long pipeline = PipelineState.withPolygonOffset(PipelineState.DEPTH_ONLY, true);
        clearDepth.set(ValueLayout.JAVA_FLOAT, 0, 0f); // the far value in reversed-Z
        for (int c = 0; c < cascadeCount; c++) {
            int fbo = device.framebuffers().glName(framebuffers[c]);
            state.bindFramebuffer(fbo);
            state.viewport(0, 0, config.size(), config.size());
            state.apply(PipelineState.withDepthWrite(state.state(), true));
            glClearNamedFramebufferfv(fbo, GL_DEPTH, 0, clearDepth);
            MemorySegment.copy(matrices[c], 0, matrix, ValueLayout.JAVA_FLOAT, 0, 16);
            materials.configureDepth(uniforms, pipeline);
            builders[c].submit(state, ring, materials.depthOnly);
            if (staticScene != null) {
                staticScene.cull(
                        state,
                        StaticScene.VIEW_CASCADES + c,
                        planes[c],
                        eye,
                        lod,
                        orthographic,
                        RenderPacket.FLAG_CASTS_SHADOW);
                staticScene.submit(state, StaticScene.VIEW_CASCADES + c, materials.depthOnly);
            }
        }
        materials.configureDepth(null, 0);
        glDisable(GL_DEPTH_CLAMP);
    }
```

The "sun shadows" pass of the frame graph runs this (§31):

1. **Depth clamp on** (§11.5) and the **polygon offset values** (§11.2). These two are set with direct
   GL calls; the switch that enables the offset (`GL_POLYGON_OFFSET_FILL`) is part of the packed
   pipeline state (`withPolygonOffset`), which the state cache applies (chapter 05, on
   `PipelineState`). `DEPTH_ONLY` is the opaque state with color writes masked off.
2. **Per cascade**: bind its framebuffer, set a viewport of the whole layer, and clear its depth to 0.
   Depth writes are turned on *before* the clear because `glClear*` respects the depth write mask: with
   writes off from a previous pass, the clear would silently do nothing.
3. **Draw the casters** with the depth binder and this cascade's matrix: the CPU-built draws, then,
   with GPU-driven culling, the static scene culled and drawn for this cascade's view (chapter 06, on
   the static scene on the GPU).
4. **Restore**: the depth binder back to the main view's defaults, depth clamp off.

What the GPU does with it: a pass with no color output and an empty or near-empty fragment shader
runs mostly in the fixed-function hardware (rasterizer and depth units), which many GPUs run faster
than color rendering. The three cascades are three passes over different subsets of the casters into
three layers; nothing here needs a memory barrier, because rendering into a texture and sampling it
in a later pass is ordered by GL itself (chapter 06, on barriers).

```java
    /** Binds the cascade array and the uniform block for receivers. */
    public void bind(StateCache state, FrameRing ring) {
        state.bindTexture(UNIT, device.textures().glName(texture));
        state.bindSampler(UNIT, sampler);
        if (blockOffset >= 0) {
            state.bindUniform(BINDING, ring.glName(), blockOffset, BLOCK_BYTES);
        }
    }
```

## 27. Step 14: receiving the sun's shadow

### 27.1 The switch

Create `engine/shadows.glsl`, the file `pbr.frag` includes:

```glsl
// Shadow lookups (TASKS M7-04). Without SUN_SHADOWS / LOCAL_SHADOWS the functions return 1.
#pragma once
#include "frame.glsl"
#include "lights.glsl"

#ifdef SUN_SHADOWS
#include "sun_shadows.glsl"
#else
float jeddySunShadow(vec3 world, vec3 geometricNormal, vec3 l, float viewDepth) {
    return 1.0;
}
#endif

#ifdef LOCAL_SHADOWS
#include "local_shadows.glsl"
#else
float jeddyLocalShadow(JeddyLight light, vec3 world, vec3 geometricNormal, vec3 l) {
    return 1.0;
}
#endif
```

`SUN_SHADOWS` and `LOCAL_SHADOWS` are **global features**: `SceneRenderer` adds them to every lit
program's feature mask when it creates the corresponding system (`MaterialSystem.SUN_SHADOWS`, bit
32; `LOCAL_SHADOWS`, bit 33), so a renderer configured without shadows compiles stubs that the
compiler folds away entirely, and binds nothing at units 7 and 8.

### 27.2 The cascade lookup

Create `engine/sun_shadows.glsl`:

```glsl
// Cascaded sun shadows (TASKS M7-04): SunShadows fills uniform block 1 and binds the depth array
// at unit 7 (comparison sampler, reversed-Z: lit where the fragment is at least as close to the
// sun as the stored caster).
#pragma once
#include "frame.glsl"

layout(std140, binding = 1) uniform JeddySunShadows {
    mat4 jCascadeMatrix[4];
    vec4 jCascadeSplits; // far view depth per cascade
    vec4 jCascadeTexel;  // world size of a texel per cascade
    vec4 jSunShadowParams; // x normal offset (texels), y map size, z fade start (fraction), w cascades
};

layout(binding = 7) uniform sampler2DArrayShadow jSunShadowMap;
```

A `sampler2DArrayShadow` is an array texture read through a comparison sampler: `texture()` takes
$(u, v, \text{layer}, \text{reference})$ and returns the filtered result of the comparisons (§11.1,
§11.6).

```glsl
float jeddyCascadeSample(int c, vec3 world, vec3 geometricNormal, vec3 l) {
    float NoL = clamp(dot(geometricNormal, l), 0.0, 1.0);
    // Normal offset grows at grazing light, where acne would appear first.
    vec3 offset = geometricNormal * (jSunShadowParams.x * jCascadeTexel[c] * (1.5 - NoL));
    vec4 p = jCascadeMatrix[c] * vec4(world + offset, 1.0);
    vec2 uv = p.xy * 0.5 + 0.5;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) {
        return 1.0;
    }
    float texel = 1.0 / jSunShadowParams.y;
    float sum = 0.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            sum += texture(jSunShadowMap, vec4(uv + vec2(x, y) * texel, float(c), p.z));
        }
    }
    return sum / 9.0;
}
```

1. **The normal offset** of §11.2: `normalOffset` texels × the cascade's world texel size × $(1.5 -
   \mathbf n\cdot\mathbf l)$, along the geometric normal. Using the cascade's own texel size makes the
   offset match its resolution: farther cascades, coarser texels, larger offsets.
2. **Into the cascade's clip space.** The projection is orthographic, so `p.w` is 1 and there is no
   division. With `glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)` (chapter 04, on clip-space
   conventions) the clip $z$ is directly the window depth the map stores, so `p.z` is the reference.
3. **NDC to texture coordinates**: $[-1, 1] \to [0, 1]$.
4. **Outside the map: lit.** A point beyond the cascade's square (possible near its edges after the
   normal offset) is treated as unshadowed; the border would do the same, this skips nine lookups.
5. **PCF** (§11.6): nine comparisons on a 3×3 grid one texel apart, each one already a bilinear
   2×2 comparison in hardware, averaged.

```glsl
float jeddySunShadow(vec3 world, vec3 geometricNormal, vec3 l, float viewDepth) {
    int cascades = int(jSunShadowParams.w);
    for (int c = 0; c < cascades; c++) {
        if (viewDepth <= jCascadeSplits[c]) {
            float shadow = jeddyCascadeSample(c, world, normalize(geometricNormal), l);
            // Fade out towards the end of the last cascade.
            if (c == cascades - 1) {
                float start = jCascadeSplits[c] * jSunShadowParams.z;
                shadow = mix(shadow, 1.0, clamp((viewDepth - start) / max(jCascadeSplits[c] - start, 1e-3), 0.0, 1.0));
            }
            return shadow;
        }
    }
    return 1.0;
}
```

- **Choosing the cascade**: the first whose far split lies beyond the pixel's view depth, matching
  how the cascades were fitted (by view depth, §26.3). Beyond the last split: no shadow.
- **The fade**: in the last cascade, from 90 % of its range (`jSunShadowParams.z`, written as `0.9f`)
  the shadow blends linearly towards 1, reaching it at the end: from 54 to 60 m by default. Without it,
  shadows would stop in a hard line across the ground at 60 m.
- **The geometric normal is normalized here**: `vNormal` arrives interpolated, slightly short (§9).
- In a quad, neighbouring pixels can choose different cascades at a boundary; the loop and the
  lookups are then divergent for that quad, which costs a little at the boundary lines and nothing
  elsewhere.

## 28. Step 15: LocalShadows

[`LocalShadows`](../../modules/render/src/main/java/dev/jeddy/render/shadow/LocalShadows.java) gives
spot and point lights shadows from an atlas, with the static-caster cache of §11.7 (D44). Thread
ownership: the render thread.

### 28.1 Constants, settings and fields

```java
public final class LocalShadows implements AutoCloseable {
    /** Texture unit of the atlas. */
    public static final int UNIT = 8;

    /** Storage binding of the view records. */
    public static final int BINDING = 8;

    /** Bytes per view record (mat4, rect, params). */
    public static final int VIEW_BYTES = 96;

    private static final int FACES = 6;

    /**
     * Settings.
     *
     * @param atlasSize atlas side in texels
     * @param tileSize tile side in texels
     * @param slots shadowed lights per frame (each reserves six tiles)
     * @param near near plane of the light views
     */
    public record Config(int atlasSize, int tileSize, int slots, float near) {
        /** A 2048² atlas of 256² tiles, 10 lights. */
        public static final Config DEFAULT = new Config(2048, 256, 10, 0.05f);
    }
```

- **A view record** is what a receiver needs about one shadow view: its view-projection (64 bytes),
  its rectangle in the atlas and three parameters (16 bytes each): 96 bytes, in a storage buffer
  because there are up to 60 of them.
- **The defaults**: 2048² atlas, 256² tiles, so $(2048/256)^2 = 64$ tiles, of which 10 slots × 6 use
  60; light views start 5 cm from the light.

```java
    private final GpuDevice device;
    private final MaterialSystem materials;
    private final Config config;
    private final int live;
    private final int cache;
    private final int liveFbo;
    private final int cacheFbo;
    private final int sampler;
    private final Culling culling;
    private final DrawBuilder[] staticDraws;
    private final DrawBuilder[] dynamicDraws;
    private final float[][] matrices;
    private final float[] tanHalf;
    private final float[] planes = new float[CullOps.PLANES_FLOATS];
    private final float[] view = new float[16];
    private final float[] projection = new float[16];
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment matrix = arena.allocate(64, 16);
    private final MemorySegment clearDepth = arena.allocate(16, 16);
    private final MaterialSystem.DepthUniforms uniforms =
            program -> {
                glProgramUniformMatrix4fv(program, 0, 1, false, matrix);
                glProgramUniform1i(program, 1, 0); // no camera fade in shadow maps
            };

    // Slots.
    private final int[] slotKey;
    private final long[] slotHash;
    private final int[] slotStaticVersion;
    private final boolean[] slotValid;
    private final long[] slotUsed;
    private final int[] slotLight; // light index this frame, -1 if unused
    private final boolean[] slotPoint;
    private final boolean[] slotRefresh;
    // Selection scratch.
    private final int[] candidates;
    private final float[] candidateScore;
    private int[] visible = new int[1024];
    private long frame;
    private long viewsOffset = -1;
    private int staticRenders;
```

- **Two atlases**, `live` (what receivers sample) and `cache` (static casters only), each a 2048²
  `GL_DEPTH_COMPONENT32F` texture (16 MiB each) with its framebuffer.
- **Two draw builders per view**, one for static casters (built only when the cache is refreshed)
  and one for dynamic casters (every frame).
- **The slots** are a struct of arrays (L03 §4): for each slot, the key of the light it holds, a hash
  of what its cached depth depends on, the `staticVersion` it was drawn with, whether its cache is
  valid, the frame it was last used, this frame's light index, whether it holds a point light, and
  whether this frame refreshes it.
- **`staticRenders`** counts static refreshes, for tests (§33).

### 28.2 Constructor

```java
    public LocalShadows(
            GpuDevice device,
            MeshRegistry meshes,
            MaterialSystem materials,
            JobSystem jobs,
            Config config) {
        int tilesPerSide = config.atlasSize() / config.tileSize();
        if (tilesPerSide * tilesPerSide < config.slots() * FACES) {
            throw new IllegalArgumentException("the atlas has fewer tiles than slots × 6");
        }
        this.device = device;
        this.materials = materials;
        this.config = config;
        live =
                device.textures()
                        .create2D(
                                GL_DEPTH_COMPONENT32F,
                                config.atlasSize(),
                                config.atlasSize(),
                                1,
                                "shadow atlas");
        cache =
                device.textures()
                        .create2D(
                                GL_DEPTH_COMPONENT32F,
                                config.atlasSize(),
                                config.atlasSize(),
                                1,
                                "shadow atlas static cache");
        liveFbo = device.framebuffers().createDepthLayer(live, -1, "shadow atlas");
        cacheFbo = device.framebuffers().createDepthLayer(cache, -1, "shadow atlas static cache");
        sampler = device.samplers().get(SamplerDesc.SHADOW);
        culling = new Culling(jobs);
        int views = config.slots() * FACES;
        staticDraws = new DrawBuilder[views];
        dynamicDraws = new DrawBuilder[views];
        matrices = new float[views][16];
        tanHalf = new float[views];
        for (int v = 0; v < views; v++) {
            staticDraws[v] = new DrawBuilder(meshes, materials.table(), null);
            dynamicDraws[v] = new DrawBuilder(meshes, materials.table(), null);
        }
        int s = config.slots();
        slotKey = new int[s];
        slotHash = new long[s];
        slotStaticVersion = new int[s];
        slotValid = new boolean[s];
        slotUsed = new long[s];
        slotLight = new int[s];
        slotPoint = new boolean[s];
        slotRefresh = new boolean[s];
        java.util.Arrays.fill(slotKey, -1);
        candidates = new int[s];
        candidateScore = new float[s];
    }
```

- The configuration is checked: every slot needs six tiles.
- `createDepthLayer(texture, -1, …)` attaches a plain 2D texture (no layer) as the framebuffer's
  depth.
- The draw builders get **no job system** (`null`): a light's view holds few casters, so building on
  the main thread is cheaper than waking workers (L03 §15).
- Every slot starts with key −1: no light.

### 28.3 update: choose, refresh, cull, build, patch

```java
    public void update(RenderPacket p, FrameRing ring, long lightsOffset, float lodPixels) {
        frame++;
        int n = select(p);
        java.util.Arrays.fill(slotLight, -1);
        for (int c = 0; c < n; c++) {
            int light = candidates[c];
            int key = Float.floatToRawIntBits(p.lights[light * RenderPacket.LIGHT_FLOATS + 14]);
            int slot = slotFor(key);
            slotLight[slot] = light;
            slotUsed[slot] = frame;
        }
        viewsOffset =
                ring.allocate(
                        (long) config.slots() * FACES * VIEW_BYTES,
                        device.caps().shaderStorageBufferOffsetAlignment());
        if (viewsOffset < 0) {
            return;
        }
        MemorySegment mem = ring.memory();
        if (visible.length < p.count()) {
            visible = new int[Math.max(p.count(), visible.length * 2)];
        }
```

1. **Choose** this frame's shadowed lights (`select`, §28.4) and give each a slot by its key
   (`slotFor`), marking the slot used this frame. The key is read with `floatToRawIntBits` (§17).
2. **Reserve the view records** for every possible view (60 × 96 = 5,760 bytes) in the ring, at the
   storage alignment. Records of unused views are simply not written and never read.

```java
        for (int s = 0; s < config.slots(); s++) {
            int light = slotLight[s];
            slotRefresh[s] = false;
            if (light < 0) {
                continue;
            }
            int o = light * RenderPacket.LIGHT_FLOATS;
            float[] l = p.lights;
            boolean point = Float.floatToRawIntBits(l[o + 7]) == RenderPacket.LIGHT_POINT;
            long hash = hash(l, o);
            if (!slotValid[s]
                    || slotHash[s] != hash
                    || slotStaticVersion[s] != p.staticVersion
                    || slotPoint[s] != point) {
                slotRefresh[s] = true;
                slotValid[s] = true;
                slotHash[s] = hash;
                slotStaticVersion[s] = p.staticVersion;
                slotPoint[s] = point;
            }
```

3. **Is the cache still right?** It is refreshed when the slot is new (`slotValid` false), the
   light's position, range or cone changed (`hash`), the static geometry changed (the packet's
   `staticVersion`, bumped by the game when it moves a static object), or the light changed between
   point and spot (one tile versus six).

```java
            int faces = point ? FACES : 1;
            float range = l[o + 3] > 0 ? l[o + 3] : 1000f;
            for (int f = 0; f < faces; f++) {
                int v = s * FACES + f;
                lightView(l, o, point, f, v);
                CullOps.extractPlanes(matrices[v], 0, planes);
                if (slotRefresh[s]) {
                    int count =
                            culling.cull(
                                    p,
                                    planes,
                                    RenderPacket.FLAG_CASTS_SHADOW | RenderPacket.FLAG_STATIC,
                                    0,
                                    l[o],
                                    l[o + 1],
                                    l[o + 2],
                                    range,
                                    visible);
                    staticDraws[v].setLod(lodPixels, p.orthographic, 1f);
                    staticDraws[v].build(p, visible, count, ring);
                }
                int count =
                        culling.cull(
                                p,
                                planes,
                                RenderPacket.FLAG_CASTS_SHADOW,
                                RenderPacket.FLAG_STATIC,
                                l[o],
                                l[o + 1],
                                l[o + 2],
                                range,
                                visible);
                dynamicDraws[v].setLod(lodPixels, p.orthographic, 1f);
                dynamicDraws[v].build(p, visible, count, ring);
                writeView(mem, viewsOffset + (long) v * VIEW_BYTES, v, point);
            }
            // The receiver finds its records through the light's shadow index.
            mem.set(ValueLayout.JAVA_INT, lightsOffset + (long) o * 4 + 13 * 4, s * FACES);
        }
    }
```

4. **Per view** (one for a spot, six for a point): its matrix (`lightView`, §28.6), its frustum
   planes, then two culls with an extra **sphere test** against the light's range (a 1000 m sphere for
   an unlimited light): casters must be in the view *and* within reach.
   - **Static casters** (`FLAG_CASTS_SHADOW | FLAG_STATIC` required) only when refreshing, into
     `staticDraws`.
   - **Dynamic casters** (`FLAG_CASTS_SHADOW` required, `FLAG_STATIC` excluded) every frame.
5. **The view record** goes into the ring (§28.6).
6. **Patch the light's shadow index.** The light array was already copied into the ring by
   `LightClusters.upload` at `lightsOffset`; float 13 of this light's record there gets the index of
   the slot's first view, `s * 6`, as an `int`. The packet itself is never modified (the game thread
   owns it; chapter 01 on the triple buffer), only this frame's GPU copy. Byte offset: record start
   `o * 4` plus 13 floats × 4 bytes. It is a write into the mapped ring, which is allowed: write-only,
   never read (L01 §3.3).

### 28.4 select: the closest shadowed lights

```java
    private int select(RenderPacket p) {
        int n = 0;
        int max = config.slots();
        for (int i = 0; i < p.lightCount; i++) {
            int o = i * RenderPacket.LIGHT_FLOATS;
            if (Float.floatToRawIntBits(p.lights[o + 14]) < 0) {
                continue;
            }
            float dx = p.lights[o] - p.eye[0];
            float dy = p.lights[o + 1] - p.eye[1];
            float dz = p.lights[o + 2] - p.eye[2];
            float score = dx * dx + dy * dy + dz * dz;
            if (n < max) {
                int k = n++;
                while (k > 0 && candidateScore[k - 1] > score) {
                    candidates[k] = candidates[k - 1];
                    candidateScore[k] = candidateScore[k - 1];
                    k--;
                }
                candidates[k] = i;
                candidateScore[k] = score;
            } else if (score < candidateScore[n - 1]) {
                int k = n - 1;
                while (k > 0 && candidateScore[k - 1] > score) {
                    candidates[k] = candidates[k - 1];
                    candidateScore[k] = candidateScore[k - 1];
                    k--;
                }
                candidates[k] = i;
                candidateScore[k] = score;
            }
        }
        return n;
    }
```

Keeps the `slots` lights nearest the camera among those with a key ($\ge 0$; −1 means no shadow, §17).
The score is the squared distance (no square root: comparing squares orders the same way). The
candidates stay sorted by **insertion**: a new light slides in from the end past every farther one.
While fewer than `max` are held, every light enters; afterwards a light enters only if it beats the
farthest, which falls off the end. That is $O(\text{lights} \times \text{slots})$ in the worst case,
with no allocation and no sort call — right for 10 slots.

### 28.5 slotFor and hash: keeping a cache across frames

```java
    /** The slot holding {@code key}, or the least recently used one (which loses its cache). */
    private int slotFor(int key) {
        int oldest = 0;
        for (int s = 0; s < slotKey.length; s++) {
            if (slotKey[s] == key) {
                return s;
            }
            if (slotUsed[s] < slotUsed[oldest]) {
                oldest = s;
            }
        }
        slotKey[oldest] = key;
        slotValid[oldest] = false;
        return oldest;
    }
```

A light that held a slot last frame finds it again by its key, with its cached tiles. A new light takes
the **least recently used** slot (the smallest `slotUsed`; never-used slots have 0), whose cache is
invalidated. That is why the key must identify the light across frames: with the light's *index*
instead, a light added before it in the packet would shift every index and throw away every cache.

One consequence of assigning slots in distance order: if every slot was used last frame and a new
light is closer than a light that keeps its shadow this frame, the new light may evict that light's
slot first, and the old light then evicts another one. Both still get a slot; one cache is lost
needlessly. With the demo's four shadowed lights and ten slots, the least recently used slot is
always one of the six idle ones, so this never happens there.

```java
    private static long hash(float[] l, int o) {
        long h = 1125899906842597L;
        for (int k = 0; k < 13; k++) {
            if (k >= 4 && k <= 7) {
                continue; // color and type do not change the depth
            }
            h = 31 * h + Float.floatToRawIntBits(l[o + k]);
        }
        return h;
    }
```

A 64-bit polynomial hash, like `String.hashCode` but over `long` with a large odd start value, of
floats 0–3 (position, range) and 8–12 (spot direction, cos outer, cos inner): everything that moves
or reshapes the light's views. Color and type are skipped (a flickering torch changes its color every
frame; the type is checked separately), and so are floats 13–15 (the shadow index and key). Comparing
a hash instead of keeping a copy of nine floats per slot is a little cheaper; a collision (two
different lights with the same 64-bit hash) would reuse a stale cache, which is improbable enough to
ignore.

### 28.6 lightView and writeView

```java
    private void lightView(float[] l, int o, boolean point, int face, int v) {
        float px = l[o];
        float py = l[o + 1];
        float pz = l[o + 2];
        float dx;
        float dy;
        float dz;
        float ux = 0;
        float uy = 1;
        float uz = 0;
        float fov;
        if (point) {
            dx = face == 0 ? 1 : face == 1 ? -1 : 0;
            dy = face == 2 ? 1 : face == 3 ? -1 : 0;
            dz = face == 4 ? 1 : face == 5 ? -1 : 0;
            if (face == 2 || face == 3) {
                uy = 0;
                uz = 1;
            }
            fov = (float) (Math.PI / 2);
        } else {
            dx = l[o + 8];
            dy = l[o + 9];
            dz = l[o + 10];
            if (Math.abs(dy) > 0.99f) {
                uy = 0;
                uz = 1;
            }
            float outer = (float) Math.acos(Math.max(-1, Math.min(1, l[o + 11])));
            fov = Math.min((float) Math.toRadians(170), 2 * outer + (float) Math.toRadians(4));
        }
        Mat4Ops.lookAt(px, py, pz, px + dx, py + dy, pz + dz, ux, uy, uz, view, 0);
        Mat4Ops.perspectiveReversedZ(fov, 1, config.near(), projection, 0);
        Mat4Ops.mul(projection, 0, view, 0, matrices[v], 0);
        // World size of one texel at 1 m, for the receiver's normal offset.
        tanHalf[v] = (float) Math.tan(fov / 2) * 2 / config.tileSize();
    }
```

- **Point light**: face $f$ looks along $+x, -x, +y, -y, +z, -z$ for $f = 0 \ldots 5$, with a 90°
  field of view: four 90° frusta around an axis and two capping it tile all directions exactly. Faces
  2 and 3 look along $\pm y$, parallel to the usual up vector, so they use $+z$ as up (chapter 04, on
  when look-at breaks). The orientation within a face does not have to match a GL cube map's: the
  atlas is a 2D texture and the receiver uses this very matrix. What must match is the **face index**
  rule, which the receiver recomputes (§29).
- **Spot light**: looks along its axis; the cone's outer angle comes back from its cosine with `acos`
  (clamped into $[-1, 1]$ against rounding), and the field of view covers the cone plus 4° (§11.7),
  at most 170° (a perspective projection cannot reach 180°). The test's spot (outer angle 1.0 rad) gets
  $2 + 0.0698 = 2.07$ rad, 118.6°.
- **The projection**: square aspect (tiles are square), reversed-Z, near plane at 5 cm, infinite far
  (chapter 04).
- **`tanHalf[v]`** is the texel size at 1 m of §11.7: $2\tan(\text{fov}/2)/256$, 7.8 mm for a cube face
  and 13.2 mm for the test's spot.

```java
    private void writeView(MemorySegment mem, long at, int v, boolean point) {
        MemorySegment.copy(matrices[v], 0, mem, ValueLayout.JAVA_FLOAT, at, 16);
        int tiles = config.atlasSize() / config.tileSize();
        int tile = v; // slot s owns tiles s*6 .. s*6+5
        float scale = (float) config.tileSize() / config.atlasSize();
        mem.set(ValueLayout.JAVA_FLOAT, at + 64, (tile % tiles) * scale);
        mem.set(ValueLayout.JAVA_FLOAT, at + 68, (tile / tiles) * scale);
        mem.set(ValueLayout.JAVA_FLOAT, at + 72, scale);
        mem.set(ValueLayout.JAVA_FLOAT, at + 76, scale);
        mem.set(ValueLayout.JAVA_FLOAT, at + 80, tanHalf[v]);
        mem.set(ValueLayout.JAVA_FLOAT, at + 84, 1f / config.atlasSize());
        mem.set(ValueLayout.JAVA_FLOAT, at + 88, point ? 1 : 0);
        mem.set(ValueLayout.JAVA_FLOAT, at + 92, 0);
    }
```

The record (`JeddyShadowView` in §29): the matrix, then `rect` (the tile's lower-left corner in atlas
texture coordinates and its size, $256/2048 = 0.125$), then `params` (texel size at 1 m, one atlas
texel in texture coordinates, a cube-face flag). View $v$ uses tile $v$: column $v \bmod 8$, row
$\lfloor v/8 \rfloor$. Slot 1's first face is tile 6 at $(0.75, 0)$; slot 1's third face is tile 8, the
first of the second row, at $(0, 0.125)$; slot 9's last face is tile 59 at $(0.375, 0.875)$:

```
 atlas 2048 x 2048, tiles 256 x 256 (tile v = slot * 6 + face,
 x = v % 8, y = v / 8; rows listed from y = 0, the bottom of the texture in GL)
 +----+----+----+----+----+----+----+----+
 | s0 | s0 | s0 | s0 | s0 | s0 | s1 | s1 |   y = 0: tiles 0-7
 | f0 | f1 | f2 | f3 | f4 | f5 | f0 | f1 |
 +----+----+----+----+----+----+----+----+
 | s1 | s1 | s1 | s1 | s2 | s2 | s2 | s2 |   y = 1: tiles 8-15
 | f2 | f3 | f4 | f5 | f0 | f1 | f2 | f3 |
 +----+----+----+----+----+----+----+----+
 ...                                         10 slots use tiles 0-59, 4 spare
```

### 28.7 render: refresh, copy, draw

```java
    public void render(StateCache state, FrameRing ring) {
        if (viewsOffset < 0) {
            return;
        }
        glPolygonOffset(-1.5f, -2f);
        long pipeline =
                PipelineState.withScissor(
                        PipelineState.withPolygonOffset(PipelineState.DEPTH_ONLY, true), true);
        clearDepth.set(ValueLayout.JAVA_FLOAT, 0, 0f);
        int tiles = config.atlasSize() / config.tileSize();
        int size = config.tileSize();
        int cacheName = device.framebuffers().glName(cacheFbo);
        int liveName = device.framebuffers().glName(liveFbo);
        for (int s = 0; s < config.slots(); s++) {
            if (slotLight[s] < 0) {
                continue;
            }
            int faces = slotPoint[s] ? FACES : 1;
            for (int f = 0; f < faces; f++) {
                int v = s * FACES + f;
                int x = (v % tiles) * size;
                int y = (v / tiles) * size;
                MemorySegment.copy(matrices[v], 0, matrix, ValueLayout.JAVA_FLOAT, 0, 16);
                if (slotRefresh[s]) {
                    drawTile(state, cacheName, x, y, size, staticDraws[v], ring, pipeline);
                    staticRenders++;
                }
                glCopyImageSubData(
                        device.textures().glName(cache),
                        GL_TEXTURE_2D,
                        0,
                        x,
                        y,
                        0,
                        device.textures().glName(live),
                        GL_TEXTURE_2D,
                        0,
                        x,
                        y,
                        0,
                        size,
                        size,
                        1);
                drawTile(state, liveName, x, y, size, dynamicDraws[v], ring, pipeline);
            }
        }
        materials.configureDepth(null, 0);
    }
```

Per view, three steps, in command-stream order:

1. **Refresh the cache tile** if needed: clear it and draw the static casters (`drawTile` on the cache
   framebuffer).
2. **Copy the cache tile into the live atlas**: `glCopyImageSubData(src, target, level, x, y, z, dst,
   …, width, height, depth)` copies a 256 × 256 region of level 0 from one texture to another on the
   GPU, with no draw and no CPU involvement. Both are 32-bit float depth, a compatible format pair for
   the copy. GL orders it after the rendering into the cache tile (a framebuffer write followed by a
   copy needs no barrier), and the live tile is overwritten entirely, which is why the live atlas never
   needs clearing.
3. **Draw the dynamic casters** on top, into the live tile, depth-tested against the copied static
   depth.

What the hardware does is driver business: depth buffers are often stored compressed (chapter 05,
on the framebuffer), and a copy between them may make the driver decompress, copy and recompress.
Whether the copy is cheaper than redrawing the static casters depends on how many there are. The
cache (D44) is built on the bet that for a torch in a stone room — a room full of walls and pillars against one copy of 256
KiB ($256^2 \times 4$ bytes) — the copy wins; no measurement of it is recorded yet (exercise 15).

```java
    private void drawTile(
            StateCache state,
            int fbo,
            int x,
            int y,
            int size,
            DrawBuilder draws,
            FrameRing ring,
            long pipeline) {
        state.bindFramebuffer(fbo);
        state.viewport(x, y, size, size);
        glScissor(x, y, size, size);
        if (fbo == device.framebuffers().glName(cacheFbo)) {
            state.apply(
                    PipelineState.withScissor(
                            PipelineState.withDepthWrite(state.state(), true), true));
            glClearNamedFramebufferfv(fbo, GL_DEPTH, 0, clearDepth); // scissored to the tile
        }
        materials.configureDepth(uniforms, pipeline);
        draws.submit(state, ring, materials.depthOnly);
    }
```

- **The viewport** maps the light view's clip space onto the tile's 256 × 256 pixels; triangles are
  clipped to the view volume first (chapter 05, on clipping), so what they draw lands inside the tile.
- **A clear ignores the viewport**: `glClearNamedFramebufferfv` would wipe the whole 2048² atlas,
  every other light's cached tiles with it. It does honour the **scissor test**, enabled through the
  pipeline state with its rectangle set by `glScissor`, which is what makes clearing one tile of a
  shared texture possible. For the draws the scissor is a second fence around the tile. And like
  §26.6, depth writes must be on for the clear to write anything.

`bind` binds the live atlas with the comparison sampler at unit 8 and the view records at storage
binding 8; `close` destroys the two framebuffers and textures and closes the arena.

## 29. Step 16: receiving local shadows

Create `engine/local_shadows.glsl`:

```glsl
// Spot and point light shadows from the atlas (TASKS M7-04). LocalShadows writes one record per
// view (a spot cone, or one cube face of a point light) into storage buffer 8; a light's params.y
// is the index of its first record (-1: no shadow). Unit 8 holds the atlas with comparison.
#pragma once

struct JeddyShadowView {
    mat4 viewProjection;
    vec4 rect;   // atlas uv offset xy, uv scale zw
    vec4 params; // x texel size at 1 m, y 1 / atlas size, z 1 for a cube face
};

layout(std430, binding = 8) readonly buffer JeddyShadowViews {
    JeddyShadowView jShadowViews[];
};

layout(binding = 8) uniform sampler2DShadow jShadowAtlas;
```

The struct is the 96-byte record of §28.6 (`mat4` + 2 × `vec4`, no padding in std430). Storage 8 and
texture unit 8 are different slots that happen to share a number (§1.2).

```glsl
float jeddyLocalShadow(JeddyLight light, vec3 world, vec3 geometricNormal, vec3 l) {
    int index = floatBitsToInt(light.params.y);
    if (index < 0) {
        return 1.0;
    }
    vec3 v = world - light.positionRange.xyz;
    if (floatBitsToUint(light.colorType.w) == J_LIGHT_POINT) {
        vec3 a = abs(v);
        int face = a.x >= a.y && a.x >= a.z ? (v.x > 0.0 ? 0 : 1)
                : (a.y >= a.z ? (v.y > 0.0 ? 2 : 3) : (v.z > 0.0 ? 4 : 5));
        index += face;
    }
    JeddyShadowView view = jShadowViews[index];
```

1. **No shadow?** A light whose index is still the packet's −1 (not selected this frame, or never
   shadowed) is fully lit. The index is read as integer bits (§17).
2. **Which face?** For a point light, the face is the **major axis** of the vector from the light to
   the point, the same rule the hardware uses to pick a cube-map face (§8.1), with the same order as
   `lightView`: $+x, -x, +y, -y, +z, -z$. A point at $(2, -5, 1)$ from the light has $|y|$ largest and
   $y < 0$: face 3, the $-y$ view.

```glsl
    float distance = length(v);
    vec3 n = normalize(geometricNormal);
    float NoL = clamp(dot(n, l), 0.0, 1.0);
    vec3 offset = n * (view.params.x * distance * (2.0 - NoL));
    vec4 p = view.viewProjection * vec4(world + offset, 1.0);
    if (p.w <= 0.0) {
        return 1.0;
    }
    p.xyz /= p.w;
    if (any(greaterThan(abs(p.xy), vec2(1.0)))) {
        return 1.0;
    }
```

3. **The normal offset** of §11.2 for a perspective view: the texel size grows with the distance
   from the light (§11.7), so the offset is (texel size at 1 m) × distance × $(2 - \mathbf n\cdot\mathbf
   l)$: one texel facing the light, two at grazing light. *Worked*: a point-light face, 3 m away, at
   $\mathbf n\cdot\mathbf l = 0.5$: $0.0078 \cdot 3 \cdot 1.5 = 3.5$ cm.
4. **Projection with a divide.** This view is a perspective one: a point behind the light's view
   (`p.w <= 0`) cannot be in its shadow map and is lit; otherwise the perspective divide gives NDC,
   and a point outside the view's square is lit too.

```glsl
    vec2 uv = view.rect.xy + (p.xy * 0.5 + 0.5) * view.rect.zw;
    float texel = view.params.y;
    // Keep the 2x2 kernel inside the tile.
    vec2 lo = view.rect.xy + texel;
    vec2 hi = view.rect.xy + view.rect.zw - texel;
    float sum = 0.0;
    for (int y = 0; y < 2; y++) {
        for (int x = 0; x < 2; x++) {
            vec2 q = clamp(uv + (vec2(x, y) - 0.5) * texel, lo, hi);
            sum += texture(jShadowAtlas, vec3(q, p.z));
        }
    }
    return sum * 0.25;
}
```

5. **Into the tile**: NDC to $[0, 1]$, scaled by the tile's size and moved to its corner. For slot 1,
   face 0 (tile 6), the tile's center $(0.5, 0.5)$ maps to $(0.75 + 0.0625, 0.0625)$.
6. **A 2×2 PCF** at offsets of ±half a texel, each lookup a bilinear 2×2 comparison in hardware (§11.6):
   a footprint of 3×3 texels with weights 1-2-1 in each direction when the point sits on a texel
   center. Cheaper than the sun's nine lookups: local shadows are many and small.
7. **The clamp keeps every lookup one texel inside the tile**, so bilinear filtering never reads the
   neighbouring tile, which belongs to another face or another light. The price is that a point at a
   point light's face boundary is filtered with taps pulled inwards, a slight seam along the cube's
   edges.

## 30. Step 17: Gtao and its shaders

### 30.1 The class

[`Gtao`](../../modules/render/src/main/java/dev/jeddy/render/post/Gtao.java) runs §13's ambient
occlusion in two full-screen passes at half resolution. Thread ownership: the render thread.

```java
public final class Gtao {
    /** Texture unit the lit passes read the AO from. */
    public static final int UNIT = 9;

    /**
     * Settings.
     *
     * @param radius world-space radius of the occlusion search (meters)
     * @param intensity exponent applied to the visibility (above 1 darkens)
     * @param slices directions per pixel
     * @param steps samples per direction and side
     */
    public record Config(float radius, float intensity, int slices, int steps) {
        /** One meter, two slices of four steps: enough for the iso camera's distance. */
        public static final Config DEFAULT = new Config(1.0f, 1.0f, 2, 4);
    }

    private final ProgramCache programs;
    private final int aoProgram;
    private final int blurProgram;
    private final int nearest;
    private final int linear;
    private final Config config;
    private final int emptyVao;

    /** Defines the programs. {@code emptyVao} is any vertex array without attributes. */
    public Gtao(GpuDevice device, ProgramCache programs, Config config, int emptyVao) {
        this.programs = programs;
        this.config = config;
        this.emptyVao = emptyVao;
        aoProgram =
                programs.define(
                        "gtao", "engine/fullscreen.vert", "post/gtao.frag", ShaderFeatures.NONE);
        blurProgram =
                programs.define(
                        "gtao blur",
                        "engine/fullscreen.vert",
                        "post/gtao_blur.frag",
                        ShaderFeatures.NONE);
        nearest = device.samplers().get(SamplerDesc.NEAREST_CLAMP);
        linear = device.samplers().get(SamplerDesc.LINEAR_CLAMP);
    }
```

- **The settings**: a 1 m search radius, no intensity exponent (1), two slices of four steps per
  side. Two slices is very few: the noise of §30.2 and the blur turn them into many directions over a
  neighbourhood of pixels.
- **Two programs** drawing the full-screen triangle of `engine/fullscreen.vert` (chapter
  [06](06-renderer-architecture.md), on the sky pass, explains it) from an empty vertex array.
- **Two samplers**: nearest for reading depth and the raw AO texel by texel, linear for the lit
  pass's upsampling.

```java
    /** Draws the raw occlusion (the target is bound by the frame graph). */
    public void occlusion(StateCache state, int depthTexture) {
        int program = programs.get(aoProgram, 0);
        if (program == 0) {
            return;
        }
        state.apply(PipelineState.FULLSCREEN);
        state.useProgram(program);
        glProgramUniform4f(
                program, 0, config.radius(), config.intensity(), config.slices(), config.steps());
        state.bindTexture(0, depthTexture);
        state.bindSampler(0, nearest);
        state.bindVertexArray(emptyVao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }
```

`programs.get` returns 0 while the program is still compiling in the background (chapter 05, on
compiling without stopping the frame); the pass then draws nothing, and the target keeps whatever it
held: the graph declares it with `color(aoRaw)`, which loads the attachment rather than clearing it
(§37 notes what that means for the first frames). The four settings travel as one `vec4` uniform. The depth texture is the prepass's
`GL_DEPTH_COMPONENT32F` target, sampled like any texture: without a comparison mode, a depth texture
returns its stored value in `.r`. `blur` is the same with the blur program, the raw AO at unit 0 and
the depth at unit 1, both nearest; `bind` puts the blurred AO at unit 9 with the linear sampler.

The frame graph owns the targets (§31): `"ao raw"` and `"ao"`, both `GL_R8` (one 8-bit channel, 0 to 1
in 256 steps) at half the window's width and height, a quarter of its pixels.

### 30.2 gtao.frag: reconstructing the surface

```glsl
#version 460 core
// Ground-truth ambient occlusion at half resolution (TASKS M7-05; Jimenez et al. 2016): view
// positions and normals reconstructed from the depth prepass, a few slice directions per pixel
// rotated by interleaved gradient noise, horizon search on both sides, and the cosine-weighted
// visibility integral of each slice.
#include "/engine/frame.glsl"

layout(binding = 0) uniform sampler2D uDepth;
layout(location = 0) uniform vec4 uParams; // x radius (m), y intensity exponent, z slices, w steps
layout(location = 0) in vec2 vUv;
layout(location = 0) out float outAo;

vec3 viewPos(vec2 uv) {
    // Sky texels (reversed-Z 0, infinite far plane) become very distant points, not infinities.
    float d = max(textureLod(uDepth, uv, 0.0).r, 1e-7);
    vec4 p = jInverseProjection * vec4(uv * 2.0 - 1.0, d, 1.0);
    return p.xyz / p.w;
}
```

`viewPos` turns a depth-buffer texel back into a view-space position (chapter 04, on world positions
from the depth buffer): texture coordinates to NDC, the stored depth as NDC $z$ (window depth equals
NDC $z$ with `GL_ZERO_TO_ONE`), the inverse projection, the divide. In an infinite reversed-Z
projection depth 0 is infinitely far and would unproject to $w = 0$; clamping to $10^{-7}$ gives a
very distant point instead of a division by zero.

```glsl
void main() {
    float depth = textureLod(uDepth, vUv, 0.0).r;
    if (depth <= 0.0) {
        outAo = 1.0; // sky (reversed-Z far plane)
        return;
    }
    vec2 texel = jViewport.zw;
    vec3 p = viewPos(vUv);
    vec3 pr = viewPos(vUv + vec2(texel.x, 0.0));
    vec3 pl = viewPos(vUv - vec2(texel.x, 0.0));
    vec3 pu = viewPos(vUv + vec2(0.0, texel.y));
    vec3 pd = viewPos(vUv - vec2(0.0, texel.y));
    vec3 dx = abs(pr.z - p.z) < abs(p.z - pl.z) ? pr - p : p - pl;
    vec3 dy = abs(pu.z - p.z) < abs(p.z - pd.z) ? pu - p : p - pd;
    vec3 n = normalize(cross(dx, dy));
    vec3 v = jeddyOrthographic() ? vec3(0.0, 0.0, 1.0) : normalize(-p);
    if (dot(n, v) < 0.0) {
        n = -n;
    }
```

1. **Sky pixels** (depth still at the clear value 0) are unoccluded.
2. **Neighbours one full-resolution pixel away**: `jViewport.zw` is $(1/W, 1/H)$ of the window, not of
   the half-resolution target. (A half-resolution pixel center falls exactly on the corner between
   four full-resolution texels; a nearest sampler then returns one of them, in exact arithmetic the
   upper-right one. The center and its neighbours are all shifted the same way, so the tangents below
   are unaffected.)
3. **The normal from depth**: two tangent vectors along the surface, $\mathbf d_x$ and $\mathbf d_y$,
   and their cross product (chapter 02). At an object's silhouette, one neighbour may be on the
   background, metres away; taking, on each axis, the neighbour with the smaller depth difference keeps
   the tangents on the object. Six depth reads (the center is read twice, once for the sky test; the
   texture cache serves the second).
4. **The view vector** in view space: from the point to the eye at the origin, or $+z$ in an
   orthographic view. The normal is flipped to face it, since the cross product's direction depends
   on which neighbours were chosen.

```glsl
    float radius = uParams.x;
    // Pixels covered by the radius at this depth (projection y scale × half the viewport).
    float scale = jProjection[1][1] * 0.5 * jViewport.y;
    float radiusPx = jeddyOrthographic() ? radius * scale : radius * scale / max(-p.z, 1e-3);
    radiusPx = min(radiusPx, 0.25 * jViewport.y);
    if (radiusPx < 1.0) {
        outAo = 1.0;
        return;
    }
    float noise = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    int slices = int(uParams.z);
    int steps = int(uParams.w);
    float visibility = 0.0;
```

5. **The radius in pixels.** A length $R$ at view depth $z$ projects to $R\,P_{11}/z$ in NDC, where
   $P_{11}$ = `jProjection[1][1]` is the projection's $y$ scale ($1/\tan(\text{fov}/2)$, chapter 04),
   and NDC spans $H/2$ pixels per unit. So $R\,P_{11}\,H / (2z)$ pixels; in an orthographic view the
   scale does not depend on $z$. *Worked*, the demo at 1600 × 900 (fov 30°, $P_{11} = 3.732$): 1 m at
   22 m (the demo camera's distance) is $3.732 \cdot 450 / 22 = 76$ pixels. The radius is capped at a quarter of the screen's height
   (huge radii near the camera would read far-apart texels and thrash the texture cache), and a radius
   under one pixel means the search would see nothing: AO 1.
6. **Interleaved gradient noise** (Jimenez 2014): a cheap hash of the pixel position into $[0, 1)$
   whose neighbouring values are spread out evenly. It rotates each pixel's slice directions and
   jitters its steps, so neighbouring pixels sample different directions; the blur then averages them.

### 30.3 gtao.frag: horizons and the integral

```glsl
    for (int s = 0; s < slices; s++) {
        float phi = (float(s) + noise) * J_PI / float(slices);
        vec2 dir = vec2(cos(phi), sin(phi));
        vec3 dir3 = vec3(dir, 0.0);
        vec3 orthoDir = dir3 - dot(dir3, v) * v;
        vec3 axis = normalize(cross(dir3, v));
        vec3 projN = n - axis * dot(n, axis);
        float projLen = max(length(projN), 1e-4);
        float cosN = clamp(dot(projN, v) / projLen, -1.0, 1.0);
        float angleN = sign(dot(orthoDir, projN)) * acos(cosN);
```

7. **The slice** (§13.2): its screen direction `dir` at angle $\phi$ (the slices split $[0, \pi)$
   evenly, shifted by the noise); `axis`, perpendicular to both `dir3` and $\mathbf v$, is the normal
   of the slice's plane; `orthoDir` is the in-plane direction perpendicular to $\mathbf v$, which tells
   the positive side from the negative.
8. **The normal in the slice**: `projN` removes the normal's component along the plane's normal
   (§9's Gram–Schmidt step again), `projLen` is its length $\ell$, and `angleN` its signed angle
   $\gamma$ from $\mathbf v$.

```glsl
        float h[2];
        for (int side = 0; side < 2; side++) {
            float sgn = side == 0 ? -1.0 : 1.0;
            // Unoccluded, the horizon is the tangent plane; distant samples fade towards it.
            float lowCos = cos(angleN + sgn * J_PI * 0.5);
            float maxCos = lowCos;
            for (int k = 0; k < steps; k++) {
                float t = (float(k) + fract(noise + 0.618 * float(k))) / float(steps);
                vec2 offset = dir * sgn * max(t * radiusPx, 1.0) * texel;
                vec3 delta = viewPos(vUv + offset) - p;
                float len2 = dot(delta, delta);
                float inv = inversesqrt(max(len2, 1e-8));
                float c = dot(delta, v) * inv;
                // Full weight up to 40% of the radius, then a linear fade (XeGTAO's falloff).
                float falloff = clamp((radius - len2 * inv) / (0.6 * radius), 0.0, 1.0);
                maxCos = max(maxCos, mix(lowCos, c, falloff));
            }
            float horizon = sgn * acos(clamp(maxCos, -1.0, 1.0));
            h[side] = side == 0 ? max(horizon, angleN - J_PI * 0.5) : min(horizon, angleN + J_PI * 0.5);
        }
```

9. **The starting horizon** is the tangent plane: the angle $\gamma \pm \pi/2$, written as its cosine.
   Horizons are tracked as cosines of the angle from $\mathbf v$: a *higher* horizon (more occlusion)
   has a *larger* cosine, so the search keeps the maximum.
10. **Steps** along the slice, at fractions `t` of the radius, jittered per pixel and per step (`0.618`
    is the golden ratio's fractional part, which spreads successive offsets evenly), at least one pixel
    from the center. Each step reads one depth texel and forms the vector `delta` from the point to the
    sample; `c` is the cosine of its angle from $\mathbf v$ (`len2 * inv` is its length, without a
    division).
11. **Falloff**: geometry within 40 % of the radius counts fully; from there the sample's cosine is
    blended towards the tangent plane's, reaching it at the full radius ($(R - \text{len})/(0.6R)$
    is 1 at $0.4R$ and 0 at $R$). A wall three metres behind a character must not darken it.
12. **The horizon angle** from its cosine, with the side's sign, then clamped to the hemisphere around
    the normal: nothing below the surface's own tangent plane counts.

```glsl
        float sinN = sin(angleN);
        float a = -cos(2.0 * h[0] - angleN) + cosN + 2.0 * h[0] * sinN;
        float b = -cos(2.0 * h[1] - angleN) + cosN + 2.0 * h[1] * sinN;
        visibility += projLen * 0.25 * (a + b);
    }
    outAo = pow(clamp(visibility / float(slices), 0.0, 1.0), uParams.y);
}
```

13. **The integral** $\ell\,\hat a(h_1, h_2, \gamma)$ of §13.2, term for term: `a` and `b` are the two
    brackets, `0.25` the $\tfrac14$, `cosN` and `sinN` the $\cos\gamma$, $\sin\gamma$.
14. **The average** over slices, clamped to $[0, 1]$, raised to the intensity exponent (1 by default:
    unchanged).

Per pixel: 2 slices × 2 sides × 4 steps = 16 depth reads for the search, plus the six of the
reconstruction, at a quarter of the window's pixels.

### 30.4 gtao_blur.frag

```glsl
#version 460 core
// Depth-aware 4x4 blur of the half-resolution AO (TASKS M7-05): removes the noise pattern
// without bleeding occlusion across depth edges.
#include "/engine/frame.glsl"

layout(binding = 0) uniform sampler2D uAo;
layout(binding = 1) uniform sampler2D uDepth;
layout(location = 0) in vec2 vUv;
layout(location = 0) out float outAo;

float viewDepth(vec2 uv) {
    float d = max(textureLod(uDepth, uv, 0.0).r, 1e-7);
    vec4 p = jInverseProjection * vec4(uv * 2.0 - 1.0, d, 1.0);
    return -p.z / p.w;
}

void main() {
    vec2 texel = 1.0 / vec2(textureSize(uAo, 0));
    float center = viewDepth(vUv);
    float sum = 0.0;
    float weight = 0.0;
    for (int y = -2; y < 2; y++) {
        for (int x = -2; x < 2; x++) {
            vec2 uv = vUv + (vec2(x, y) + 0.5) * texel;
            float w = 1.0 / (1e-3 + abs(viewDepth(uv) - center) / max(center, 1e-3) * 50.0);
            sum += textureLod(uAo, uv, 0.0).r * w;
            weight += w;
        }
    }
    outAo = sum / weight;
}
```

- **Sixteen taps**, offsets $-1.5, -0.5, 0.5, 1.5$ half-resolution texels on each axis. With the
  nearest sampler each lands on the edge between two texels and reads one of them, so the taps cover a
  4×4 block of the raw AO: an even-sized block cannot be centered on a texel, and this one sits half a
  texel off. Its size matches the noise: two slices rotated by a per-pixel noise repeat their pattern
  over a few pixels.
- **Depth-aware weights**: $w = 1/(10^{-3} + 50\,|z_i - z_c|/z_c)$, with $z$ the view depth (the
  depth is read at full resolution, at the tap's coordinates). A tap at the same depth weighs 1000; 1 %
  deeper or nearer, 2.0; 2 %, 1.0; 10 %, 0.2. Taps across a silhouette (a character in front of a wall)
  hardly count, so occlusion does not bleed across depth edges.
- **Normalized** by the sum of the weights, so the result stays in $[0, 1]$.

### 30.5 Applying it: effects.glsl

```glsl
// Screen-space terms and per-pixel effects shared by the lit passes (TASKS M7-05, M7-07, M7-10).
#pragma once
#include "frame.glsl"

// Blended surfaces lie in front of the depth the AO came from: they skip it.
#if defined(SCREEN_AO) && !defined(ALPHA_BLEND)
layout(binding = 9) uniform sampler2D jAmbientOcclusion;
float jeddyScreenOcclusion(vec2 fragCoord) {
    return texture(jAmbientOcclusion, fragCoord * jViewport.zw).r;
}
#else
float jeddyScreenOcclusion(vec2 fragCoord) {
    return 1.0;
}
#endif
```

The lit pass reads the half-resolution AO at the full-resolution pixel's normalized coordinates with
the **linear** sampler: the texture unit blends the four nearest half-resolution texels, a bilinear
upsampling for free. Blended surfaces (glass, smoke) are not in the depth prepass, so the AO under
them belongs to whatever is behind; they skip it. In `pbr.frag`, AO multiplies the diffuse environment
light and feeds the specular occlusion (§8.8); direct light is never touched.

## 31. Step 18: wiring it into SceneRenderer

Everything above is created, updated and bound by
[`SceneRenderer`](../../modules/render/src/main/java/dev/jeddy/render/SceneRenderer.java); chapter
[06](06-renderer-architecture.md), on the scene renderer, builds the class. The lighting parts:

**Creation**, in the constructor (condensed: the decal, outline, fog and particle features follow the
same pattern):

```java
        this.environment = new Environment(device, programs);
        this.clusters = new LightClusters(device, programs);
        // ...
        this.sunShadows =
                config.sunShadows() == null
                        ? null
                        : new SunShadows(device, meshes, materials, jobs, config.sunShadows());
        if (sunShadows != null) {
            materials.setGlobalFeatures(materials.globalFeatures() | MaterialSystem.SUN_SHADOWS);
            sunShadows.setStaticScene(staticScene);
        }
        this.localShadows =
                config.localShadows() == null
                        ? null
                        : new LocalShadows(device, meshes, materials, jobs, config.localShadows());
        if (localShadows != null) {
            materials.setGlobalFeatures(materials.globalFeatures() | MaterialSystem.LOCAL_SHADOWS);
        }
        // ... the empty vertex array
        gtao =
                config.ambientOcclusion() == null
                        ? null
                        : new Gtao(device, programs, config.ambientOcclusion(), emptyVao);
        if (gtao != null) {
            materials.setGlobalFeatures(materials.globalFeatures() | MaterialSystem.SCREEN_AO);
        }
```

A `null` sub-configuration turns a feature off; each feature that exists adds its global bit, so
every lit program variant includes its code (§27.1). Tests use this to build renderers with one feature
at a time (`Config.DEFAULT.withLocalShadows(null)` and so on; `FeatureBisectTest` switches each off in
turn, L01 §17).

**The passes**, declared once (§1.2's diagram):

```java
        graph.addComputePass("light cull", this::lightCull).writes(clusterItems);
        // ... static cull
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
```

The cluster list, the cascades and the atlas are **imported** resources (owned by their systems, not
by the graph), and declaring who writes and reads them is what orders the passes and places the
barriers (chapter 06, on the frame graph). The two AO targets are graph-owned transients at half size
(`0.5f`). The transparent pass declares the same reads as the opaque one.

**Per frame**, in `render`, before the graph executes (the full method is in chapter 06):

```java
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
```

The order matters twice: `LocalShadows.update` patches the light array `clusters.upload` just copied
(so it must come after), and `writeFrameConstants` writes the light and decal counts the upload
produced (so it comes last).

**The executors** each pass runs (condensed: `staticCull`, between `lightCull` and the shadow
passes, and `depthPrepass`, between them and `aoPass`, are left out; chapter 06, on the passes'
executors, shows both):

```java
    private void lightCull(PassContext ctx) {
        StateCache state = ctx.state();
        state.bindUniform(FRAME_BINDING, ring.glName(), frameOffset, FRAME_BYTES);
        clusters.dispatch(state, packet, ring);
    }

    // ... staticCull (chapter 06)

    private void sunShadowPass(PassContext ctx) {
        if (sunShadows != null) {
            sunShadows.render(ctx.state(), ring);
        }
    }

    private void localShadowPass(PassContext ctx) {
        if (localShadows != null) {
            localShadows.render(ctx.state(), ring);
        }
    }

    // ... depthPrepass (chapter 06)

    private void aoPass(PassContext ctx) {
        StateCache state = ctx.state();
        state.bindUniform(FRAME_BINDING, ring.glName(), frameOffset, FRAME_BYTES);
        gtao.occlusion(state, ctx.texture(depth));
    }

    private void aoBlurPass(PassContext ctx) {
        StateCache state = ctx.state();
        state.bindUniform(FRAME_BINDING, ring.glName(), frameOffset, FRAME_BYTES);
        gtao.blur(state, ctx.texture(aoRaw), ctx.texture(depth));
    }
```

and, for the opaque and transparent passes, before they submit their draws:

```java
    /** Binds what the lit passes read: frame constants, AO, decals, IBL, lights, shadows. */
    private void bindLighting(PassContext ctx) {
        StateCache state = ctx.state();
        state.bindUniform(FRAME_BINDING, ring.glName(), frameOffset, FRAME_BYTES);
        if (gtao != null) {
            gtao.bind(state, ctx.texture(ao));
        }
        if (decalTextures != null) {
            decalTextures.bind(state);
        }
        if (fogOfWar != null) {
            fogOfWar.bind(state);
        }
        environment.bind(state);
        clusters.bind(state, ring);
        if (sunShadows != null) {
            sunShadows.bind(state, ring);
        }
        if (localShadows != null) {
            localShadows.bind(state, ring);
        }
        materials.invalidateBindings();
    }
```

Every binding of §1.2's table, through the state cache. The last line makes the material binder
forget which texture sets it bound: some of these units overlap with what a previous pass left, and a
stale belief would skip a needed bind (chapter 06, on binding a set).

# Part C — Using it and proving it

## 32. One lit frame, in order

Everything in Part B, in the order it runs in one call of `SceneRenderer.render` (chapter
[06](06-renderer-architecture.md) has the whole method).

**On the CPU, main thread (with job workers), before any GL work of the lighting passes:**

```
ring.beginFrame()                          the frame's ring region is free (L01)
... main-view culling and draw building (chapter 06)
SunShadows.update                          fit 3 cascades (§26.4); per cascade: cull casters,
                                           build draws into the ring (workers); write the
                                           304-byte cascade block into the ring
Environment.flush                          if the SH changed: stage 144 bytes, GPU copy (D103)
LightClusters.upload                       lights (64 B each) and decals (112 B each) into the ring
LocalShadows.update                        pick ≤ 10 lights, refresh stale slots, cull and build
                                           static (if stale) and dynamic casters per view, write
                                           the view records (5,760 B), patch float 13 of each
                                           shadowed light in the ring's copy
writeFrameConstants                        544 bytes: cluster slicing, sun, environment, counts
graph.execute                              records the passes below
ring.endFrame()                            fence
```

**In the command stream, in the order the GPU executes it:**

```
light cull      copy 16 B header (counter = 0) ─▶ [bounds pass + storage barrier, if needed]
                ─▶ cull dispatch (one invocation per cluster, atomicAdd)
                ─▶ barrier: SHADER_STORAGE | BUFFER_UPDATE
static cull     (chapter 06)
sun shadows     depth clamp on; per cascade: clear layer, draw casters (polygon offset)
local shadows   per used view: [clear + draw static casters into the cache tile]
                ─▶ copy cache tile → live tile ─▶ draw dynamic casters
depth prepass   every opaque instance, depth only, GEQUAL, cleared to 0
ao              half resolution: depth ─▶ raw AO
ao blur         raw AO + depth ─▶ AO
opaque          bindLighting; every visible opaque pixel runs pbr.frag once (GEQUAL, no writes)
sky             where depth is still 0
transparent     sorted blended surfaces through pbr.frag (no decals, no screen AO)
... particles, outlines, post (chapter 08)
```

Which mechanism orders each hand-over (L01 §6.4 for the three kinds):

| Producer → consumer | Kind of write | Ordered by |
|---|---|---|
| CPU ring writes (lights, blocks, records) → any pass | CPU, coherent mapping | written before the commands are issued (L01 §6.2) |
| header copy → cull's atomics | buffer copy | GL's own ordering of copies before later reads |
| cull's atomics → next frame's header copy | shader storage writes | `GL_BUFFER_UPDATE_BARRIER_BIT` after the cull |
| bounds pass → cull pass | shader storage writes | `GL_SHADER_STORAGE_BARRIER_BIT` after the bounds pass |
| cull pass → opaque, transparent | shader storage writes | `GL_SHADER_STORAGE_BARRIER_BIT` after the cull (and the graph's own) |
| shadow passes → opaque | rendering into textures | GL's own ordering of framebuffer writes before later sampling |
| cache tile rendering → copy → dynamic draws | rendering, image copy | GL's own ordering |
| LUT image stores (startup) → every lit pass | image writes | `GL_TEXTURE_FETCH_BARRIER_BIT` after the dispatch |

## 33. The tests, walked through

### 33.1 How they run

The renderer tests that touch the GPU carry `@Tag("gpu")` and run with
`-Pjeddy.gpuTests=true` (DESIGN §9.7). CI runs them on Mesa's **llvmpipe**, a software implementation
of OpenGL that runs everything on the CPU; they also run on real GPUs (L01 §17 tells what that found).
`SceneRendererTest` builds one `SceneRenderer` with `Config.DEFAULT` on a 128 × 128 window, a sphere
of radius 0.4 and a 6 m ground plane, renders each scene twice (so the frame ring and the program
cache have settled) and reads the HDR target back as floats with `glGetTextureImage`. Each test starts
from a camera at $(0, 0, 4)$ looking at the origin (40° field of view, near 0.1 m, `far` 50 m) and a
black sun; `lookFrom` moves the camera when needed. The cooker's `EnvironmentTest` is a plain CPU
test.

### 33.2 whiteFurnaceReflectsTheEnvironment

Four white spheres (metallic 0 and 1 × roughness 0.2 and 0.8) inside `setUniform(1, 1, 1)`, camera at
$z = 6$: every channel at a point near each sphere's center must be $1 \pm 0.12$, the background
(the sky pass) $1 \pm 0.01$, and the GL debug output must report no error. §6.7 computed what it should
see: metals 1.000, dielectrics 1.040 and 1.022 (the specular layer on top of a full diffuse return), a
rough metal without energy compensation 0.537, which would fail.

### 33.3 sunLightsTheFacingSide

The sun travels along $+x$ (so it lights from $-x$) with `sunColor` 3, in a black environment, onto a
white sphere of roughness 0.6. The pixel over the sphere's left side must be above 0.5, the right side
below 0.02. Predicted from §6: the left surface point under that pixel has its normal at
$\mathbf n\cdot\mathbf l = 0.747$, so its diffuse term alone is $3 \cdot 0.747/\pi = 0.71$; the right
point faces away from the sun ($\mathbf n\cdot\mathbf l < 0$) and gets exactly 0 from `jeddyShade`'s
early return, with no environment to fill it.

### 33.4 clusteredPointLightsShadeOnlyWhereTheyReach

Three scenes, each read back through the list's counter (`glGetNamedBufferSubData` of the first 4
bytes of the list buffer: the number of entries the cull pass appended):

1. **A red light of 2 cd, range 1.2 m, at $(-0.6, 0, 0.6)$**, left of the sphere. The left pixel must
   be red (> 0.2) and not green (< 0.01), the right pixel dark (< 0.02), and the light must be in
   some clusters but fewer than 200 entries. Predicted: the left surface point is 0.45 m from the
   light (window 0.961, attenuation 4.75, $\mathbf n\cdot\mathbf l = 0.994$): red $\approx 2 \cdot 4.75
   \cdot 0.994/\pi = 3.0$. The right point is 0.96 m away, *inside* the range (window 0.35), but its
   normal faces away ($\mathbf n\cdot\mathbf l = -0.47$): the assertion message says "out of range",
   but it is the cosine law that makes it pass. The grid at 128² has only $2 \times 2 \times 24 = 96$
   clusters, so "fewer than 200" holds for any single light; the check that matters is "more than 0".
2. **A light behind the camera** (6 m behind it, range 1): in no cluster, counter 0.
3. **1500 random lights** of range 0.5 m in front of the camera: more than 1500 entries (each light
   overlaps several clusters), and every sampled pixel finite. With 96 clusters the list's capacity is
   $96 \cdot 48 = 4608$ entries, so some clusters may overflow and drop lights (§25): the test checks
   that overflow is harmless, not that nothing is dropped.

### 33.5 framesAllocateNothing

Nine spheres, 64 point lights, a sun and a 0.5 environment: ten frames of warm-up, ten timed frames
whose average it prints as `scene frame (llvmpipe, 16384 px): … ms`, then
`Allocations.assertNoAllocation("scene frame", 5, 10, …)`: after the JIT has compiled it, a whole
frame — culling, the cascades' fitting and draws, the light upload, local shadows, the frame graph —
allocates zero bytes (L00 §8.2). The printed time is CPU time on a software renderer; it says little
about a GPU.

### 33.6 sunCastsShadowsOnTheGround

Camera at $(0, 3, 3)$ looking down at the sphere on the ground plane (both shadow casters), the sun at
45° (travelling along $(1, -1, 0)$), sun color 2. Asserts: three cascades; the open ground at $(-1,
-0.4, 0.5)$ lit (> 0.3; predicted $2 \cdot \cos 45°/\pi = 0.45$); the ground at $(0.8, -0.4, 0)$, inside
the sphere's shadow (an ellipse from $x = -0.17$ to $0.97$ along the sun's direction), darker than a
quarter of that ("no excessive peter-panning"); the top of the sphere, facing the sun, lit ("no
acne"); and with the sphere's `FLAG_CASTS_SHADOW` cleared, the ground under it lit again.

### 33.7 spotAndPointLightsCastCachedShadows

The static-caster cache of §28, step by step: a static ground and a dynamic sphere, a spot light at
$(-1.5, 2, 0)$ aimed down and to the right (12 cd, range 8, cone 0.6–1.0 rad) with shadow key 7.

| Step | Asserts | Why |
|---|---|---|
| first render | spot-lit ground > 0.2; shadowed point < 0.15 × lit; `staticRenders()` = 1 | a new slot refreshes its one view |
| move the sphere to $x = 3$ | `staticRenders` unchanged; the old shadow is gone | a dynamic caster does not touch the cache |
| replace with a point light, same place, same key | `staticRenders` + 6; point shadow < 0.15 × lit | type changed (and the hash: direction and cone are different defaults): six faces refresh |
| render again | `staticRenders` unchanged ("cached again") | nothing changed |
| `staticVersion++` | `staticRenders` + 6 more | static geometry may have moved |

### 33.8 ambientOcclusionDarkensTheContactBetweenSphereAndGround

Environment 1, the sphere resting on the ground, no shadows needed. It reads the half-resolution AO
texture back and asserts: the open ground above 0.9; the crease in front of the contact below 0.85
and at least 0.12 below the open ground; the lit pass darker in the crease than on the open ground
(< 0.9 ×); the sphere's top above 0.85. TASKS M7-05 records the measured values: crease 0.76, open
0.96. §13.1 computed the exact value for that ground point, 0.728; GTAO comes out a little higher
because its falloff (§30.3) discounts the parts of the sphere more than 0.4 m away.

### 33.9 EnvironmentTest (cooker, CPU)

| Test | Checks |
|---|---|
| `cubeFacesFollowTheGlTable` | `direction` against GL's face table for three faces; `sample` of a texel's own direction returns it (§22.1) |
| `uniformLightGivesPiIrradianceAndAFlatPrefilter` | a cube of 0.5: SH irradiance $0.5\pi \pm 0.01$ in two directions; three prefiltered levels, the last 4×4, all 0.5 (§8.2, §22.3) |
| `skyLightComesFromAbove` | light only above the horizon: $E(\text{up}) = \pi \pm 0.25$, $E(\text{down}) < 0.25$ (§8.2's second check) |
| `brdfLutIsBoundedAndMatchesKnownValues` | a 32² LUT with 256 samples: every value in $[0, 1.0001]$; head-on and smooth, $A + B = 1 \pm 0.03$ and $B < 0.02$; rough at grazing, $A + B < 0.75$ (0.613: single scattering loses energy); along a row of constant $\mathbf n\cdot\mathbf v$, $A + B$ never rises with roughness |
| `cooksAnHdrIntoAPrefilteredCube` | a 64 × 32 HDR sky cooked at face size 32: a cube, `RGBA16F`, 4 levels, 108 bytes of SH with positive blue band 0 and blue $Y_{1,-1}$ (§22.6) |

The light and shadow paths also run in `SkinningTest` (a skinned strip in the lit pass, the depth
prepass and the sun's shadow) and in `FeatureBisectTest`, which renders a lit sphere with each
feature switched off in turn and prints the table (L01 §17).

## 34. Things to run

```bash
# The cooker's bakes (no GPU needed)
./gradlew :cooker:test --tests 'dev.jeddy.cooker.env.EnvironmentTest'

# The lit frame on the GPU (llvmpipe works)
./gradlew :render:test "-Pjeddy.gpuTests=true" --tests 'dev.jeddy.render.pbr.SceneRendererTest'
./gradlew :render:test "-Pjeddy.gpuTests=true" --tests 'dev.jeddy.render.pbr.FeatureBisectTest'

# The demo with 1000 dynamic point lights and the profiler overlay (F3: GPU time per pass)
./gradlew :iso-demo:run --args="--lights 1000 --profiler"

# The scripted performance scene (256 lights, 4 shadowed), measured for 60 s: writes perf.md
# (into build/perf by default, --report DIR to change it) with the GPU time of every pass
./gradlew :iso-demo:run --args="--perf 60"
```

On Windows use `gradlew.bat` and the same arguments ([running.md](../running.md)). Things to look at
in the demo: zoom with the wheel and watch the sun's cascade boundary (about 19 m from the camera,
§11.3) cross the floor; walk the party past torches and watch their shadows follow; with `--lights
1000`, compare the "light cull" and "opaque" GPU times with the default.

## 35. The numbers

Computed in this chapter (with the engine's formulas and the cooker's algorithms; the scripts are in
the exercises):

| What | Value | Where |
|---|---|---|
| GGX peak at roughness 0.5 / 0.2 / minimum 0.045 | 5.093 / 198.9 / 77,625 | §6.3, §5 |
| $V$ at roughness 0.5, $\mathbf n\cdot\mathbf v = 0.8$, $\mathbf n\cdot\mathbf l = 0.6$ | 0.503 ($G_2 = 0.965$) | §6.4 |
| $E_{ss}$ at roughness 1, head-on (exact) | $1 - \ln 2 = 0.307$ | §6.7 |
| $E_{ss}$ at $\mathbf n\cdot\mathbf v = 0.5$, roughness 0.5: LUT (separable) / height-correlated | 0.749 / 0.857–0.860 | §6.7, exercise 6 |
| White furnace, predicted | metals 1.000; dielectrics 1.022–1.040 | §6.7 |
| Cluster grid at 1600 × 900 | 9000 clusters; lists 1.65 MiB | §10.2, §23 |
| Sun cascades for the demo camera | splits 6.93 / 19.17 / 60 m; texels 4.9 / 11.9 / 37.8 mm | §11.3–11.4 |
| One map for 0.5–60 m instead | 4.3 cm texels | §11.3 |
| Local shadow texel at 1 m (cube face / test spot) | 7.8 mm / 13.2 mm | §28.6 |
| GTAO radius in pixels, demo, at 22 m | 76 | §30.2 |
| AO at the test's crease point, exact | 0.728 | §13.1 |

Measured, with their sources:

| What | Result | Source |
|---|---|---|
| AO test: crease / open ground | 0.76 / 0.96 | TASKS M7-05 |
| Generating the 128² LUT at startup | "a few ms" | D43 |

No GPU timings of the lighting passes are recorded yet. DESIGN §14's budget for them is part of "GPU
(256 dynamic lights, 4 shadowed, full post) ≤ 12 ms" on the reference PC (a GTX 1660 / RX 6600 class
GPU at 1080p), which the `--perf` capture measures per pass; filling in that row on real hardware is
part of M10.

## 36. Why it is built this way

| Choice | Instead of | Why | Decision |
|---|---|---|---|
| glTF metallic–roughness | specular–glossiness, custom models | the format of the art tools and of the cooker's glTF importer | — |
| GGX, height-correlated Smith, Schlick | Blinn–Phong, Beckmann, separable Smith | GGX's tail matches measured materials; the correlated Smith term is the more accurate and costs the same | — |
| Energy compensation from the LUT | none | rough metals would lose up to 69 % of their light (§6.7); the factor is free | — |
| Split sum: prefiltered cube + LUT | sampling the environment per pixel | one texture read and one LUT read instead of hundreds | — |
| SH9 for diffuse | an irradiance cube map | 27 floats in a uniform block; the cosine blur leaves nothing above band 2 worth keeping | D39 (SH in the KTX2 key) |
| LUT generated at startup | loading the cooker's LUT | a renderer needs no cooked engine asset | D43 |
| Lights in the GPU layout in the packet | light objects converted on the main thread | one bulk copy per frame; the shadow key lets caches survive | D44 |
| Clustered forward | deferred, tiled forward | forward keeps transparency and any material in one shader; clusters cut lists by depth | — |
| Decals in the light clusters | a second grid | one cull pass, one lookup per pixel | D45 |
| Counter reset by a GPU copy | `glNamedBufferSubData` | ordered after the previous frame's cull; a driver dropped the CPU write | D103 |
| Sphere-fitted, texel-snapped cascades | tight boxes | shadow edges stay still as the camera moves | — |
| Shadow atlas with a static-caster cache | a cube map per light; redrawing everything | one texture and one sampler for every local shadow; static casters drawn once per change | D44 |
| `GL_GEQUAL` after the prepass | `GL_EQUAL` | two programs need not produce bit-identical depth | D42 |
| Camera-fade dither in the prepass too | sorting faded objects | the prepass and the lit pass drop the same pixels | D48 |
| GPU culling of static casters per cascade | CPU culling only | the static scene is already on the GPU | D49 |
| GTAO at half resolution | SSAO at full resolution | an exact horizon integral; a quarter of the pixels | — |

## 37. Known limits and open problems

What the code does not do yet, or does wrongly, found while writing this chapter. Each is a good first
change to make, and several are exercises.

1. **Cluster bounds go stale when `far` changes.** `LightClusters.dispatch` recomputes the boxes only
   when the projection matrix (or the viewport) changes. But the boxes also depend on the slicing's
   `near` and `far` (`jClusterZ`), and the demo sets `p.far = cam.distance * 2 + 60` every frame: the
   wheel zoom changes `far` without changing the perspective matrix (which depends only on the field of
   view, the aspect ratio and the near plane). After a zoom, `jeddyClusterIndex` slices with the new
   `far` while the boxes were cut with the old one. Example: bounds computed at the default zoom (far
   104 m, the demo's starting zoom), then zoomed out to distance 40 (far 140 m): a fragment at 40 m
   falls in slice 18 ($24\ln 80/\ln 280 = 18.7$), whose box still covers 27.4–34.2 m, so a torch near
   the fragment whose range does not reach back to 34.2 m is missing from its list, and its light is
   cut off. Exercise 13 fixes it.
2. **The LUT and the direct lights use different Smith terms** (§6.7): rough metals reflect up to
   about 15 % more direct light than they receive at $\mathbf n\cdot\mathbf v = 0.5$, roughness 0.5.
   Exercise 6.
3. **Dielectrics are 2–4 % too bright** under uniform light: the diffuse term ignores the light the
   specular layer reflected (§6.6–6.7). Lambert also ignores roughness.
4. **One environment for the whole level**, with the split sum's round reflections (§8.4) and no
   light bouncing between surfaces other than what AO removes.
5. **The sun's split is tuned for a generic camera**: for the isometric camera the first cascade
   covers air, and the ground straddles the second and third (§11.3). Exercise 12.
6. **Point-light face seams**: the clamped 2×2 kernel cannot filter across cube faces (§29); spot cones
   wider than 166° (170° minus the margin) are cut off.
7. **Cache churn** when every shadow slot was in use: slots are assigned in distance order, which can
   evict a cache needlessly (§28.5).
8. **A full ring is not handled for local shadows.** `SceneRenderer.render` ignores the result of
   `clusters.upload`, and `LocalShadows.update` patches each shadowed light at
   `clusters.lightsOffset()`. If the decal allocation failed, that offset is still last frame's (in a
   region a frame in flight may read); if the light allocation failed, it is −1, and the patch lands a
   few bytes into the ring buffer. A full ring is already a configuration error (other allocations
   throw), but this one corrupts memory instead.
9. **The AO targets start undefined.** The graph loads (does not clear) `"ao raw"` and `"ao"`, and
   while the GTAO programs are still compiling the passes draw nothing; the first lit frames then read
   whatever the new textures contain.
10. **Beyond `far`**, every fragment uses the last slice, whose box ends at `far` (§10.5's check).
11. **The cull pass is clusters × lights** and transforms every light 9000 times (§25). Fine for
    hundreds of lights; exercise 7 for thousands.
12. A small documentation slip: `SunShadows.update`'s Javadoc mentions a parameter `lodSource`; it is
    `lodPixels`.

## 38. Further reading

- Brian Karis, "Real Shading in Unreal Engine 4", SIGGRAPH 2013 course *Physically Based Shading in
  Theory and Practice*: the split sum, the BRDF LUT, the range window.
- Sébastien Lagarde and Charles de Rousiers, "Moving Frostbite to Physically Based Rendering",
  SIGGRAPH 2014: units, punctual lights, specular occlusion, the height-correlated Smith term.
- Tomas Akenine-Möller et al., *Real-Time Rendering*, 4th edition: chapter 9 (physically based
  shading), 10 (local illumination, IBL), 11 (global illumination, ambient occlusion), 7 (shadows), 20
  (efficient shading, clustered methods).
- Ola Olsson, Markus Billeter and Ulf Assarsson, "Clustered Deferred and Forward Shading",
  High-Performance Graphics 2012.
- Jorge Jimenez, Xian-Chun Wu, Angelo Pesce and Adrian Jarabo, "Practical Realtime Strategies for
  Accurate Indirect Occlusion", SIGGRAPH 2016 course *Physically Based Shading in Theory and Practice*
  (GTAO).
- Eric Heitz, "Understanding the Masking-Shadowing Function in Microfacet-Based BRDFs", Journal of
  Computer Graphics Techniques, 2014.
- Bruce Walter, Stephen Marschner, Hongsong Li and Kenneth Torrance, "Microfacet Models for Refraction
  through Rough Surfaces", EGSR 2007 (the GGX distribution).
- Ravi Ramamoorthi and Pat Hanrahan, "An Efficient Representation for Irradiance Environment Maps",
  SIGGRAPH 2001.
- Mark Colbert and Jaroslav Křivánek, "GPU-Based Importance Sampling", *GPU Gems 3*, chapter 20.
- Fan Zhang, Hanqiu Sun, Leilei Xu and Lee Kit Lun, "Parallel-Split Shadow Maps for Large-Scale
  Virtual Environments", 2006 (the practical split scheme).
- Romain Guy and Mathias Agopian, "Physically Based Rendering in Filament" (the Filament
  documentation), for the energy compensation and many of the same formulas, with derivations.
- James T. Kajiya, "The Rendering Equation", SIGGRAPH 1986.
- LearnOpenGL: [PBR theory](https://learnopengl.com/PBR/Theory),
  [diffuse irradiance](https://learnopengl.com/PBR/IBL/Diffuse-irradiance),
  [specular IBL](https://learnopengl.com/PBR/IBL/Specular-IBL),
  [normal mapping](https://learnopengl.com/Advanced-Lighting/Normal-Mapping),
  [shadow mapping](https://learnopengl.com/Advanced-Lighting/Shadows/Shadow-Mapping), and the
  cascaded shadow mapping guest article.
- The [OpenGL 4.6 core specification](https://registry.khronos.org/OpenGL/specs/gl/glspec46.core.pdf)
  (depth comparison for shadow samplers, polygon offset, depth clamping, memory barriers) and the
  [GLSL 4.60 specification](https://registry.khronos.org/OpenGL/specs/gl/GLSLangSpec.4.60.pdf)
  (derivatives in non-uniform control flow, atomic operations).

## Exercises

Each has a hint and a full solution; try before you open them. Exercises 1–7 change engine code: run
the tests of §34 afterwards and undo the changes. Exercises 8–11 and 16 need only `jshell` or a
calculator.

**1. Visualize the cluster light counts.** In `pbr.frag`, right after `range` is computed, output a
heat map of the cluster's light count and return. Run the demo with `--lights 512 --screenshot
heat.png` and explain the pattern you see.

<details><summary>Hint</summary>

The count is `range.y & 0xFFFFu`. The output goes into the HDR target and then through exposure and
tone mapping, so make it bright. Think about which pixels share a cluster: a 64-pixel tile *and* a
depth slice.
</details>
<details><summary>Solution</summary>

```glsl
    uvec2 range = jClusterRanges[jeddyClusterIndex(gl_FragCoord.xy, viewDepth)];
    // Debug: lights per cluster, blue (0) → green (16) → red (32 or more).
    float t = clamp(float(range.y & 0xFFFFu) / 32.0, 0.0, 1.0);
    outColor = vec4(t, 1.0 - abs(t * 2.0 - 1.0), 1.0 - t, 1.0) * 4.0;
    return;
```

```bash
./gradlew :iso-demo:run --args="--lights 512 --screenshot heat.png"
```

The `* 4.0` compensates for exposure and tone mapping, which would otherwise darken the colors (the
screenshot is taken 3 s after loading). What you see: **64-pixel blocks**, since all pixels of a tile
at the same depth share one list; inside a block, a **second color wherever the depth jumps** (an
object in front of the floor falls in another slice than the floor behind it, §10.2); and generally
higher counts on the **farther** parts of the floor, whose slices are deeper (geometric slicing) and
whose boxes therefore hold more lights. The clusters' boxes are larger than the clusters (§10.4), so
counts are slightly higher than the lights that really reach.
</details>

**2. Change the spot falloff.** Replace `t * t` in `jeddySpotAttenuation` with `smoothstep(0.0, 1.0,
t)` and run `SceneRendererTest`. Which tests still pass, and why?

<details><summary>Hint</summary>

`smoothstep(0, 1, t)` is $t^2(3 - 2t)$. Compare it with $t^2$ at $t = 0$, $0.5$, $1$. Then find where
the test's two sample points sit in the spot's cone.
</details>
<details><summary>Solution</summary>

$t^2(3 - 2t)$ equals $t^2$ at both ends and is brighter in between ($0.5$ against $0.25$ at
$t = 0.5$): a wider, brighter edge band. For the test's spot (inner 0.6 rad, outer 1.0 rad) at
$\theta_s = 0.8$ rad, $t^2 = 0.30$ and smoothstep gives $0.57$.

Every test still passes. Only `spotAndPointLightsCastCachedShadows` uses a spot light, and both of its
sample points are inside the inner cone, where $t = 1$ either way: the lit point $(-0.8, -0.4, 0.8)$
is $29°$ (0.51 rad) from the spot's axis and the shadowed point $(0.6, -0.4, 0)$ about $2°$, against an
inner angle of $34°$ (0.6 rad). The change is visible only on the ground near the cone's edge.
</details>

**3. Color the cascades.** Make the sun's shadow lookup tint the image by cascade (red, green, blue),
run the demo, and compare the boundaries with the split distances of §11.3. What happens as you zoom
in and out?

<details><summary>Hint</summary>

`jeddySunShadow` returns a `float`. A global `vec3` declared in `sun_shadows.glsl` can carry the
cascade out; `pbr.frag` must only use it when `SUN_SHADOWS` is defined, because without it
`sun_shadows.glsl` is not included (§27.1). The demo's near plane is 0.5 m, not the packet's default
0.1 m.
</details>
<details><summary>Solution</summary>

In `engine/sun_shadows.glsl`, before `jeddySunShadow`:

```glsl
vec3 jCascadeTint = vec3(1.0); // debug
```

and inside its loop, just before `return shadow;`:

```glsl
            jCascadeTint = c == 0 ? vec3(1.0, 0.4, 0.4) : (c == 1 ? vec3(0.4, 1.0, 0.4) : vec3(0.4, 0.4, 1.0));
```

In `pbr.frag`, before `color = jeddyApplyEffects(...)`:

```glsl
#ifdef SUN_SHADOWS
    color *= jCascadeTint; // debug
#endif
```

With the demo's near plane of 0.5 m and `far` = min(`p.far`, 60) = 60 m, the boundaries are at view
depths **6.93 m** and **19.17 m** (§11.3; the 5.65 / 15.3 m of the packet's defaults assume a 0.1 m
near plane). The splits do not move with the camera: they are fixed view depths, because neither the
near plane nor the 60 m limit changes (the demo's `p.far` is `2 · distance + 60`, at least 76 m at
the controller's minimum zoom of 8 m). What moves is the floor under them. At the starting zoom
(distance 22 m, pitch 52°) the floor spans 18.2–27.8 m (§11.3), so the bottom of the screen is green
and the top blue, with a visible change of shadow sharpness at the line (11.9 mm against 37.8 mm
texels). Zoomed in to the minimum, 8 m (eye 6.3 m up: $8\sin 52°$), the floor spans
$6.3/\sin 67° \cdot \cos 15° = 6.6$ m to $6.3/\sin 37° \cdot \cos 15° = 10.1$ m: red at the bottom,
green above. Zoomed out to the maximum, 48 m (eye 37.8 m up), it spans 39.7–60.7 m: blue, and the
very top of the screen is beyond 60 m, where the shadow has faded to nothing (from 54 m). Exercise 12
retunes the split.
</details>

**4. Widen the PCF kernel.** Change the 3×3 loop in `jeddyCascadeSample` to 5×5. Check that
`sunCastsShadowsOnTheGround` still passes, and compare the frame time that `framesAllocateNothing`
prints ("scene frame (llvmpipe, …)").

<details><summary>Hint</summary>

Loops from −2 to 2, and divide by 25. Count lookups per sunlit pixel before and after.
</details>
<details><summary>Solution</summary>

```glsl
    for (int y = -2; y <= 2; y++) {
        for (int x = -2; x <= 2; x++) {
            sum += texture(jSunShadowMap, vec4(uv + vec2(x, y) * texel, float(c), p.z));
        }
    }
    return sum / 25.0;
```

The edge widens from 3–4 texels to 5–6 (each of the 25 lookups is still a bilinear 2×2 comparison),
and the cost grows from 9 to 25 comparison lookups per sunlit pixel. The shadow test still passes:
its shadowed point is 0.4 m from the shadow's center along the shadow's long half-axis of 0.57 m, so
about 17 cm inside its edge, while the wider kernel blurs only 5–6 texels of a few millimetres each
(the test's camera is about 4.6 m from the scene, in the first or second cascade). On llvmpipe the printed time is CPU time spent rasterizing in
software and only roughly tracks what a GPU would spend; compare a few runs before concluding
anything, and use a real GPU's per-pass timers (F3 in the demo) for the real cost.
</details>

**5. Test the slice formula on the CPU.** Write a plain JUnit test that reproduces
`SceneRenderer.writeFrameConstants`'s `scale` and `bias` and checks that the depth $z_k = n(f/n)^{k/S}$
maps to slice index $k$ (within a small epsilon) for every $k$; do the same for orthographic slicing.

<details><summary>Hint</summary>

`(float) Math.log(z) * scale + bias` should be within `1e-3` of `k`. The orthographic slice of
$z_k = n + (f - n)k/S$ is $(z_k - n)/(f - n) \cdot S$. This catches a mismatch between
`jeddyClusterIndex` and `sliceDepth` in `cluster_bounds.comp`, which must be inverses.
</details>
<details><summary>Solution</summary>

`modules/render/src/test/java/dev/jeddy/render/light/ClusterSlicingTest.java`:

```java
package dev.jeddy.render.light;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The slicing of writeFrameConstants (jeddyClusterIndex) inverts sliceDepth (cluster_bounds). */
class ClusterSlicingTest {
    private static final int S = LightClusters.SLICES;

    @Test
    void sliceBoundariesMapToWholeSlices() {
        float[][] cases = {{0.1f, 200f}, {0.5f, 100f}, {0.001f, 1000f}};
        for (float[] c : cases) {
            // As writeFrameConstants clamps and computes them.
            float near = Math.max(1e-3f, c[0]);
            float far = Math.max(near * 2, c[1]);
            float logRatio = (float) Math.log(far / near);
            float scale = S / logRatio;
            float bias = (float) (-S * Math.log(near) / logRatio);
            for (int k = 0; k <= S; k++) {
                float z = (float) (near * Math.pow(far / near, (double) k / S)); // sliceDepth
                assertEquals(k, (float) Math.log(z) * scale + bias, 1e-3f, near + ".." + far + ": " + k);
                float zo = near + (far - near) * k / S; // orthographic sliceDepth (mix)
                assertEquals(k, (zo - near) / (far - near) * S, 1e-3f, "orthographic " + k);
            }
        }
    }
}
```

Run it with `./gradlew :render:test --tests 'dev.jeddy.render.light.ClusterSlicingTest'` (no GPU
needed). In `float`, the worst error over all $k$ is about $2 \times 10^{-6}$ for these cases, far inside
the tolerance. The test copies the formula, which is its weakness: if `writeFrameConstants` changed,
the test would not notice. Moving `scale` and `bias` into a static method of `LightClusters`, called by
both, would make the test test the engine's own code.
</details>

**6. Make the BRDF LUT consistent with direct lighting.** Replace the Schlick–Smith `gVis` in
`pbr/brdf_lut.comp` (and in `EnvironmentBaker.brdfLut`) with the height-correlated visibility that
`jeddyV_SmithGGXCorrelated` uses. Run the white furnace test and
`EnvironmentTest.brdfLutIsBoundedAndMatchesKnownValues`, then measure the difference the change makes.

<details><summary>Hint</summary>

The estimator is $(f_{\text{spec}}/F)(\mathbf n\cdot\mathbf l)/p(\mathbf l)$ (§8.6). With
$f_{\text{spec}}/F = DV$ and $p = D(\mathbf n\cdot\mathbf h)/(4\,\mathbf v\cdot\mathbf h)$, what is left
after $D$ cancels? For the measurement: the furnace test cannot see the inconsistency (§6.7 says
why); what can is the directional albedo of `jeddyShade`'s specular term for a white metal, energy
factor included, which should be exactly 1.
</details>
<details><summary>Solution</summary>

**The change.** $\frac{DV(\mathbf n\cdot\mathbf l)}{D(\mathbf n\cdot\mathbf h)/(4\,\mathbf v\cdot\mathbf h)} =
\frac{4\,V\,(\mathbf n\cdot\mathbf l)(\mathbf v\cdot\mathbf h)}{\mathbf n\cdot\mathbf h}$. In
`brdf_lut.comp`, inside `if (NoL > 0.0)`:

```glsl
            float ggxL = NoV * sqrt((NoL - a2 * NoL) * NoL + a2);
            float ggxV = NoL * sqrt((NoV - a2 * NoV) * NoV + a2);
            float vis = 0.5 / max(ggxV + ggxL, 1e-5);
            float gVis = 4.0 * vis * NoL * VoH / max(NoH, 1e-6);
```

(`k` is no longer used; delete it and fix the header comment "Smith-GGX with k = alpha / 2"). In
`EnvironmentBaker.brdfLut` the same four lines in Java replace `gv`, `gl` and `gVis`, and its Javadoc
loses "({@code k = α / 2})".

**The tests.** The furnace test still passes: metals stay at exactly 1 (both $E_{ss}$ and the factor
come from the new table and cancel), and the dielectrics move by thousandths (the rough one from
1.022 to 1.023). `brdfLutIsBoundedAndMatchesKnownValues` **fails twice**, because two of its
assertions describe the separable term, not the BRDF:

- the bound `v <= 1.0001f`: the largest value is now 1.000149 (Monte Carlo noise around a true value
  of 1 for smooth surfaces), so loosen it to `1.001f`;
- "rough at grazing angles, $A + B < 0.75$": with the correlated term that texel is 0.938 (§6.7: the
  separable product double-counts masking at grazing angles). Replace it with a check against an
  independent value, a brute-force integral of $\int DV(\mathbf n\cdot\mathbf l)\,d\omega$ on a grid
  (the integrator below gives 0.848 at $\mathbf n\cdot\mathbf v$ = roughness = 16.5/32), for example
  `assertEquals(0.848f, lut[k] + lut[k + 1], 0.01f)` with `k = (16 * 32 + 16) * 2`; the LUT gives
  0.846 there.

**The measurement.** A white metal under one direct light reflects, summed over all light
directions, $\int f_{\text{spec}}\,(\mathbf n\cdot\mathbf l)\,d\omega$ with $F = 1$, times
`s.energy` $= 1/E_{ss}^{\text{LUT}}$. Integrate the shader's $D$ and $V$ on a grid in Java:

```java
static double albedo(double nov, double roughness, int steps) {
    double alpha = roughness * roughness, a2 = alpha * alpha;
    double vx = Math.sqrt(1 - nov * nov), vz = nov;
    double sum = 0, dTheta = (Math.PI / 2) / steps, dPhi = (2 * Math.PI) / (4 * steps);
    for (int i = 0; i < steps; i++) {
        double theta = (i + 0.5) * dTheta, st = Math.sin(theta), nol = Math.cos(theta);
        for (int j = 0; j < 4 * steps; j++) {
            double phi = (j + 0.5) * dPhi;
            double hx = st * Math.cos(phi) + vx, hy = st * Math.sin(phi), hz = nol + vz;
            double noh = hz / Math.sqrt(hx * hx + hy * hy + hz * hz);
            double f = (noh * a2 - noh) * noh + 1, d = a2 / (Math.PI * f * f);
            double ggxL = nov * Math.sqrt((nol - a2 * nol) * nol + a2);
            double ggxV = nol * Math.sqrt((nov - a2 * nov) * nov + a2);
            double v = 0.5 / Math.max(ggxV + ggxL, 1e-5);
            sum += d * v * nol * st * dTheta * dPhi; // dω = sinθ dθ dφ
        }
    }
    return sum;
}
```

`albedo(0.5, 0.5, 400)` is 0.857. Divided by the old table's $E_{ss}$ there (0.749) a white rough
metal reflects **1.14 times** the light it receives; divided by the new table's (0.857–0.860) it
reflects 1.00. At $\mathbf n\cdot\mathbf v = 0.95$, roughness 0.8: 0.557 / 0.535 = 1.04 before, 1.00
after. Put the integrator and these two cases in a test of the cooker (it can call
`EnvironmentBaker.brdfLut`), asserting $1 \pm 0.02$: that is the "direct-light furnace" the GPU test
cannot be.
</details>

**7. Cull lights against the frustum first (hard).** With 10,000 lights every cluster still loops
over all of them. Add a compute pass before `light_cull.comp` that writes the indices of lights whose
sphere touches the view frustum into a compact list, and make `light_cull.comp` loop over that list.

<details><summary>Hint</summary>

Reuse the `atomicAdd` append of `light_cull.comp` and its header-reset pattern (a ring copy, D103).
Give the list a new storage binding (10 and 11 are free). The frustum planes are already in the packet
(`p.frustumPlanes`, 24 floats, the format of `CullOps.sphereVisible`). Think about which barriers the
new pass needs before and after, and what the nondeterministic order of the compact list does to a
cluster that hits its 128-light cap. The 1500-light case of `clusteredPointLightsShadeOnlyWhereTheyReach`
must still pass, and `framesAllocateNothing` must stay at 0 bytes.
</details>
<details><summary>Solution</summary>

The new shader, `pbr/light_frustum.comp`:

```glsl
#version 460 core
// Compacts the frame's lights to those whose sphere touches the view frustum.
#include "/engine/frame.glsl"

layout(local_size_x = 64) in;

struct JeddyLight {
    vec4 positionRange;
    vec4 colorType;
    vec4 directionOuter;
    vec4 params;
};

layout(std430, binding = 2) readonly buffer JeddyLights {
    JeddyLight jLights[];
};

layout(std430, binding = 10) buffer JeddyVisibleLights {
    uint jVisibleCount;
    uint jVisiblePad0;
    uint jVisiblePad1;
    uint jVisiblePad2;
    uint jVisible[];
};

layout(location = 0) uniform vec4 uPlanes[6];

void main() {
    uint i = gl_GlobalInvocationID.x;
    if (i >= jCounts.x) {
        return;
    }
    vec4 pr = jLights[i].positionRange;
    float r = pr.w > 0.0 ? pr.w : 1e30;
    for (int p = 0; p < 6; p++) {
        if (dot(uPlanes[p].xyz, pr.xyz) + uPlanes[p].w < -r) {
            return;
        }
    }
    jVisible[atomicAdd(jVisibleCount, 1u)] = i;
}
```

The plane test is `CullOps.sphereVisible`'s, on the GPU. In `light_cull.comp`, declare the same
binding 10 (read-only) and loop over it:

```glsl
    for (uint j = 0u; j < jVisibleCount && lights < MAX_LIGHTS_PER_CLUSTER; j++) {
        uint i = jVisible[j];
        vec4 pr = jLights[i].positionRange;
        if (touches(pr.xyz, pr.w > 0.0 ? pr.w : 1e30, lo, hi)) {
            hits[lights++] = i;
        }
    }
```

In `LightClusters` (condensed): a third program (`defineCompute("light frustum", ...)`); a buffer
`visibleBuffer` of `16 + 4 * maxLights` bytes, created `DYNAMIC` and recreated larger (doubling) when
`p.lightCount` exceeds `maxLights` — an allocation only while the light count grows; a 96-byte native
segment `planes` filled from `p.frustumPlanes` with `MemorySegment.copy` each frame. In `dispatch`,
before the cull:

```java
        header.set(ValueLayout.JAVA_INT, 0, 0); // the visible counter
        if (!ring.upload(device.buffers().glName(visibleBuffer), 0, header, 0, 4)) {
            device.buffers().update(visibleBuffer, 0, header.address(), 4);
        }
        MemorySegment.copy(p.frustumPlanes, 0, planes, ValueLayout.JAVA_FLOAT, 0, 24);
        int frustum = programs.getBlocking(frustumProgram, 0);
        glProgramUniform4fv(frustum, 0, 6, planes);
        state.bindStorage(VISIBLE_BINDING, device.buffers().glName(visibleBuffer), 0, visibleBytes);
        bind(state, ring);
        state.useProgram(frustum);
        glDispatchCompute((lightCount + GROUP - 1) / GROUP, 1, 1);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT); // the cull reads the compact list
```

then the existing header reset, cull and final barrier; the final barrier's `GL_BUFFER_UPDATE_BARRIER_BIT`
covers every shader write before it, so it also orders next frame's reset of the visible counter
after this frame's appends. (Doing it all inside `dispatch`, like the bounds pass, needs no change to
the frame graph. As a separate graph pass it would declare `.writes(visible)`, and "light cull" would
declare `.readsStorage(visible)` so that the graph inserts the storage barrier; declaring only the
write would insert nothing.)

Two consequences to check. The compact list's order changes from frame to frame (atomics), so a
cluster that hits its 128-light cap may keep different lights each frame: visible flicker where the
cap is reached, which the old ascending order avoided. And the per-cluster cost drops from all lights
to the visible ones: with lights spread over a level, most are off screen. Test it with 10,000 lights
of which 1500 are in view: the counter of the cluster list (`clusterItems()` in the test) must equal
its value with only those 1500.
</details>

**8. GGX by hand.** Compute $D_{\text{GGX}}$ for roughness 0.5 at $\mathbf n\cdot\mathbf h = 0.95$ by
hand, then check the normalization $\int D(\mathbf n\cdot\mathbf h)\,d\omega = 1$ numerically in
`jshell` with a 1000-slice midpoint sum.

<details><summary>Hint</summary>

The parenthesis is $0.95^2(0.0625 - 1) + 1$. For the integral, use §2.4's $d\omega$ and let the $\phi$
loop contribute $2\pi$.
</details>
<details><summary>Solution</summary>

$0.9025 \cdot (-0.9375) + 1 = 0.15391$; squared $0.023687$; $D = 0.0625 / (\pi \cdot 0.023687) =
0.840$, the table's value (§6.3).

```
jshell> double a2 = 0.0625, s = 0; int n = 1000;
jshell> for (int i = 0; i < n; i++) { double t = (i + 0.5) * (Math.PI / 2) / n, c = Math.cos(t), f = c * c * (a2 - 1) + 1; s += a2 / (Math.PI * f * f) * c * Math.sin(t) * (Math.PI / 2) / n; }
jshell> 2 * Math.PI * s
$4 ==> 1.0000033027627393
```

One, to six digits: the facets cover the surface exactly once.
</details>

**9. Where 69 % of the light goes.** Check §6.7's $E_{ss} = 1 - \ln 2$ for roughness 1 seen head-on
by evaluating $\int_0^1 c/(1 + c)\,dc$ with a midpoint sum, and compare with the LUT's value at its
corner texel.

<details><summary>Hint</summary>

Same pattern as exercise 8, one loop. The LUT's corner texel is at $\mathbf n\cdot\mathbf v$ =
roughness = 127.5/128.
</details>
<details><summary>Solution</summary>

```
jshell> double s = 0; int n = 1000;
jshell> for (int i = 0; i < n; i++) { double c = (i + 0.5) / n; s += c / (1 + c) / n; }
jshell> s
$3 ==> 0.30685285069004786
jshell> 1 - Math.log(2)
$4 ==> 0.3068528194400547
```

The LUT texel (127, 127) holds $A + B = 0.312$: slightly higher because it sits at roughness 0.996
and $\mathbf n\cdot\mathbf v = 0.996$, not exactly 1, and carries Monte Carlo noise. The white metal
compensation there is $1/0.312 = 3.2$: without it a rough gold ornament would render at less than a
third of its brightness.
</details>

**10. Hammersley by hand.** Write the 8 points of the Hammersley set for $N = 8$, then the GGX
half-vector angle $\theta_h$ that point 3 gives for roughness 0.5. Check the second coordinate in
`jshell` with the engine's Java formula.

<details><summary>Hint</summary>

Write $i$ in three bits and mirror them behind the binary point: $3 = 011_2 \to 0.110_2$. Then §8.6's
formula with $\alpha^2 = 0.0625$.
</details>
<details><summary>Solution</summary>

| $i$ | binary | mirrored | point |
|---|---|---|---|
| 0 | 000 | 0.000 | (0, 0) |
| 1 | 001 | 0.100 | (0.125, 0.5) |
| 2 | 010 | 0.010 | (0.25, 0.25) |
| 3 | 011 | 0.110 | (0.375, 0.75) |
| 4 | 100 | 0.001 | (0.5, 0.125) |
| 5 | 101 | 0.101 | (0.625, 0.625) |
| 6 | 110 | 0.011 | (0.75, 0.375) |
| 7 | 111 | 0.111 | (0.875, 0.875) |

`(Integer.reverse(3) >>> 1) / (float) (1L << 31)` is 0.75. Point 3: $\phi = 2\pi \cdot 0.375 = 135°$,
$\cos\theta_h = \sqrt{0.25/(1 - 0.9375 \cdot 0.75)} = \sqrt{0.25/0.29688} = 0.918$, $\theta_h = 23.4°$.
</details>

**11. SH rings.** The environment is black except for one tiny bright spot straight up ($+y$), of
radiance $L$ and solid angle $\Omega$. Compute the SH9 irradiance $E$ for a normal at angle $\theta$
from $+y$, find where it goes negative, and compare with the true irradiance $L\Omega\max(\cos\theta,
0)$. Why does `jeddyIrradiance` end with `max(e, vec3(0.0))`?

<details><summary>Hint</summary>

Project the spot: $L_{lm} = L\Omega\,Y_{lm}(+y)$. At $(0, 1, 0)$ only $Y_{00}$, $Y_{1,-1}$, $Y_{20}$
and $Y_{22}$ are non-zero. By symmetry $E$ depends only on $\cos\theta$; evaluate it along the $x$–$y$
plane, where $n = (\sin\theta, \cos\theta, 0)$.
</details>
<details><summary>Solution</summary>

At $+y$: $Y_{00} = 0.282095$, $Y_{1,-1} = 0.488603$, $Y_{20} = 0.315392(3 \cdot 0 - 1) = -0.315392$,
$Y_{22} = 0.546274(0 - 1) = -0.546274$. With $c = \cos\theta$ and $n = (\sqrt{1 - c^2}, c, 0)$, the sum
$\sum \hat A_l L_{lm} Y_{lm}(n)$ is

$$\frac{E}{L\Omega} = \pi\,(0.282095)^2 + \frac{2\pi}{3}(0.488603)^2\,c + \frac{\pi}{4}\Big((-0.315392)(-0.315392) + (-0.546274)\cdot 0.546274\,(1 - 2c^2)\Big)$$

using $Y_{1,-1}(n) = 0.488603\,c$, $Y_{20}(n) = -0.315392$ ($n$ has no $z$) and $Y_{22}(n) =
0.546274(x^2 - y^2) = 0.546274(1 - 2c^2)$. Term by term: $\pi \cdot 0.079578 = 0.25$;
$\tfrac{2\pi}{3} \cdot 0.238733 = 0.5$; and $\tfrac{\pi}{4}(0.099472 - 0.298415 + 0.596831\,c^2) =
-0.15625 + 0.46875\,c^2$. So $E/(L\Omega) = 0.09375 + 0.5c + 0.46875c^2$. Checks: at
$c = 1$, 1.0625 (true 1); at $c = 0$, 0.094 (true 0); at $c = -1$, 0.0625 (true 0). Its minimum is
where the slope $0.5 + 0.9375c$ is 0: $c = -0.533$, $\theta = 122°$, where $E = -0.040\,L\Omega$:
**negative**. Nine coefficients cannot represent a spike; their sum overshoots it and dips below zero
beside the opposite side, like a truncated Fourier series. Negative irradiance would subtract light,
darkening the back of objects below black; the `max` clamps it to 0. (A real sky is smooth and rings
much less; a cooked environment with a bright sun disc rings this way.)
</details>

**12. Cascades for the isometric camera.** For the demo's camera (pitch 52°, distance 22 m, vertical
field of view 30°, 16:9, near 0.5 m, shadows to 60 m), compute the split distances and texel sizes for
$\lambda$ = 0, 0.5, 0.75 and 1, and choose a $\lambda$ for this game. What would a better scheme for
an isometric camera do?

<details><summary>Hint</summary>

The floor lies between view depths 18.2 and 27.8 m (§11.3). For each $\lambda$, which cascades does
it fall in, and what is their texel size $2r/2048$? The radius of a cascade is dominated by the
frustum's width at its far end, not its depth, and it does not depend on the camera's pitch or
distance (§11.4), so §11.4's table applies.
</details>
<details><summary>Solution</summary>

Computed with `fitCascade`'s formulas (radii quantized to 1/16 m):

| $\lambda$ | cascade 1 (m), texel | cascade 2 (m), texel | cascade 3 (m), texel |
|---|---|---|---|
| 0 | 0.5–20.33, 14.6 mm | 20.33–40.17, 23.6 mm | 40.17–60, 33.5 mm |
| 0.5 | 0.5–11.40, 8.1 mm | **11.40–26.17, 15.7 mm** | 26.17–60, 36.1 mm |
| 0.75 (default) | 0.5–6.93, 4.9 mm | 6.93–19.17, 11.9 mm | 19.17–60, 37.8 mm |
| 1 | 0.5–2.47, 1.6 mm | 2.47–12.16, 8.1 mm | 12.16–60, 39.7 mm |

With the default, the floor (18.2–27.8 m) is split at 19.17 m between 11.9 mm and 37.8 mm texels:
all but the bottom 15 % of the screen gets the blurrier cascade, with a visible line between. With
$\lambda = 0.5$ the floor straddles 26.17 m instead (15.7 mm against 36.1 mm), with the line 12 %
below the top of the screen; with $\lambda = 0$ the whole floor lies in cascade 2 at 23.6 mm, uniform but twice
as blurry as $\lambda = 0.5$'s near part. None of the four is right for this view: the split scheme
assumes the interesting depths start at the near plane. $\lambda = 0.5$ is the best of them
(`new SunShadows.Config(3, 2048, 60f, 0.5f, 60f, 1.5f)` through `SceneRenderer.Config.withSunShadows`);
judge it on screen, and at the minimum zoom (floor at 6.6–10.1 m) too.

Notice also what the floor alone needs: a cascade fitted to the slice 18.2–27.8 m has a sphere of
radius 15.9 m (quantized 15.9375; the frustum is 26.5 m wide at its far end) and texels of
$2 \cdot 15.9375/2048 = 15.6$ mm; even a slice of only 17.5–22.5 m needs 12.5625 m and 12.3 mm. About
12–16 mm per texel is the best any cascade can do for this view at 2048². A scheme made for an
isometric camera would therefore **start the cascades at the ground**, not at the near plane: fit
the first cascade to the visible ground (for example from the depth range the camera controller
knows), and spend the others on the far edge and tall objects. That needs a new setting, such as a
"shadow start" depth that replaces `near` in the split formula.
</details>

**13. Fix the stale cluster bounds.** §37 item 1: make `LightClusters.dispatch` recompute the bounds
when the slicing's near or far distance (or the orthographic flag) changes, and write a GPU test that
fails before the fix and passes after.

<details><summary>Hint</summary>

The bounds shader reads `jClusterZ.x`/`.y` (near, far) and the orthographic bit, besides the inverse
projection and the viewport. Remember them like `lastProjection`. For the test, render once with
`packet.far = 50`, then with `packet.far = 200` and the same camera, with a light that should reach a
surface around 28 m away.
</details>
<details><summary>Solution</summary>

In `LightClusters`, three more fields and a wider check:

```java
    private float lastNear = Float.NaN;
    private float lastFar = Float.NaN;
    private boolean lastOrthographic;

    public void dispatch(StateCache state, RenderPacket p, FrameRing ring) {
        boolean changed =
                boundsDirty
                        || p.near != lastNear
                        || p.far != lastFar
                        || p.orthographic != lastOrthographic;
        for (int i = 0; i < 16 && !changed; i++) {
            changed = lastProjection[i] != p.projection[i];
        }
        // ... as before; inside if (changed), after System.arraycopy:
            lastNear = p.near;
            lastFar = p.far;
            lastOrthographic = p.orthographic;
```

Comparing the raw packet values is enough: `writeFrameConstants` derives `jClusterZ` from them. (NaN
makes the first comparison true, but `boundsDirty` already is.) In the demo this recomputes the
bounds during a zoom, 9000 invocations of a few dozen instructions each, only on the frames where the
zoom moves.

The test (in `SceneRendererTest`, so it can reuse its helpers): the default camera at $z = 4$, a sphere
scaled by 4 (radius 1.6; its world matrix has 4 on the diagonal) at $(0, 0, -26)$, whose front is at
view depth 28.4 m, in a black environment, and a red light of 20 cd and range 4 m at $(-2, 0, -24)$,
depth 28. Render with `packet.far = 50` and read the pixel over the sphere's center: the fragment is
in slice 21 ($24\ln(284)/\ln 500 = 21.8$), whose box (23.0–29.8 m) the light touches, so it is lit;
predicted red $\approx 20 \cdot 0.209 \cdot 0.196/\pi = 0.26$ (window 0.869 at 2.04 m, $\mathbf
n\cdot\mathbf l = 0.196$). Then render with `packet.far = 200` and read it again. Before the fix, the
fragment falls in slice 17 ($24\ln(284)/\ln 2000 = 17.8$), whose stale box (cut for `far` 50) covers
only 8.2–10.6 m; the light's sphere (24–32 m) misses it, and the pixel is black. After the fix, slice
17's box covers 21.8–29.9 m, the light is listed, and the pixel is lit again. Assert red above 0.05 in
both renders. (These values are predictions from §7 and §10; run it to see them.)
</details>

**14. The NaN in the light record.** In `jshell`, store the shadow index −1 in a `float[]` the way
`addPointLight` does, read it back with `Float.floatToIntBits` and with `Float.floatToRawIntBits`, and
explain what `LocalShadows.select` would do with each.

<details><summary>Hint</summary>

L00 §7.3: exponent all ones and a non-zero fraction.
</details>
<details><summary>Solution</summary>

```
jshell> float[] l = new float[1];
jshell> l[0] = Float.intBitsToFloat(-1);
jshell> l[0]
$3 ==> NaN
jshell> Float.floatToRawIntBits(l[0])
$4 ==> -1
jshell> Float.floatToIntBits(l[0])
$5 ==> 2143289344
```

`0xFFFFFFFF` is a NaN; Java keeps its bits through the array, and the raw read returns −1, so
`select` skips the light (`< 0`). `floatToIntBits` replaces every NaN by the canonical `0x7FC00000` =
2,143,289,344: `select` would take every unshadowed light as shadowed with that key, the slots would
fill with lights that never asked for shadows, and they would all share one key. The GPU side
(`floatBitsToInt`) always reads raw bits.
</details>

**15. Measure the static-caster cache.** Find out what the cache of §28 saves in the demo: compare the
GPU time of the "local shadows" pass with the cache working and with every slot refreshed every
frame.

<details><summary>Hint</summary>

F3 in the demo shows the GPU time of every frame-graph pass (chapter 06, on GPU timers). To defeat
the cache, change one condition in `LocalShadows.update`, not the packet's `staticVersion` (which would
also make the GPU-culled static scene rebuild every frame and spoil the comparison).
</details>
<details><summary>Solution</summary>

In `update`, make the refresh unconditional for the experiment:

```java
            if (true
                    || !slotValid[s]
                    || slotHash[s] != hash
                    || slotStaticVersion[s] != p.staticVersion
                    || slotPoint[s] != point) {
```

Run the demo with the profiler (`--args="--profiler"`), stand the party in the same place both times
(the four shadowed lights are point lights: 24 views), and read the "local shadows" pass time with and
without the change; or run `--perf 60` both ways and compare the pass in the two `perf.md` reports.
What to expect (a prediction, not a recorded measurement): with the cache, the pass costs 24 tile
copies plus the few dynamic casters near the torches; without it, every wall, pillar and floor tile
within each torch's range is drawn six times per torch, so the forced version should be slower by an
amount that grows with the static geometry around the lights. Record the two numbers in TASKS or
`running.md` with the GPU: this chapter's §35 has no GPU timing of the lighting passes yet.
</details>

**16. GTAO by hand.** A slice in which the projected normal is tilted $30°$ from the view vector
($\gamma = 30°$, $\ell = 1$): on the negative side nothing is in the way, on the positive side a wall
rises to $60°$ from the view vector. Compute $\hat a$, compare it with the unoccluded slice, and check
it against the integral's definition.

<details><summary>Hint</summary>

Unoccluded, the horizons are the tangent plane, $\gamma \mp 90°$: $h_1 = -60°$, $h_2 = 120°$. With the
wall, $h_2 = 60°$. Use radians in the $2h\sin\gamma$ terms.
</details>
<details><summary>Solution</summary>

Negative side ($h_1 = -\pi/3$): $\tfrac14(-\cos(-150°) + \cos 30° - \tfrac{2\pi}{3} \cdot 0.5) =
\tfrac14(0.866 + 0.866 - 1.047) = 0.171$. Positive side with the wall ($h_2 = \pi/3$):
$\tfrac14(-\cos 90° + 0.866 + \tfrac{2\pi}{3} \cdot 0.5) = \tfrac14(0 + 0.866 + 1.047) = 0.478$.
$\hat a = 0.650$. Without the wall ($h_2 = 2\pi/3$): $\tfrac14(-\cos 210° + 0.866 + \tfrac{4\pi}{3} \cdot
0.5) = 0.957$, total 1.128. The wall removes $1 - 0.650/1.128 = 42\,\%$ of this slice's weighted
visibility. (A single slice can exceed 1 for a tilted normal; averaged over all slices with their
weights $\ell$, an open surface comes to exactly 1.) A 200,000-step trapezoid integral of
$\cos(\theta - 30°)\,|\sin\theta|$ from $-60°$ to $60°$ gives 0.649519, the closed form 0.649519.
</details>

## Glossary

- **AABB (axis-aligned bounding box):** the smallest box with faces parallel to the axes that contains
  a shape; a cluster's box in view space (§10.3).
- **Albedo, directional albedo:** the fraction of light a surface reflects in total; for one incoming
  direction, $\int f\,(\mathbf n\cdot\mathbf l)\,d\omega$, at most 1 (§4.2, §6.7).
- **Ambient occlusion (AO):** the cosine-weighted fraction of the hemisphere above a point that is not
  blocked by nearby geometry (§13).
- **Antiderivative:** a function whose slope is the given function; it turns an integral into a
  subtraction (§2.2).
- **Atlas (shadow atlas):** one large texture cut into tiles, each holding one light view's shadow map
  (§11.7, §28).
- **Bias (shadow):** a push of the depth comparison towards "lit" that prevents acne (§11.2).
- **BRDF:** bidirectional reflectance distribution function: outgoing radiance towards $\mathbf v$
  per unit of irradiance from $\mathbf l$, in 1/sr (§4.2).
- **BRDF lookup table (LUT, "DFG"):** the 2D table of the split sum's scale $A$ and bias $B$ over
  $(\mathbf n\cdot\mathbf v, \text{roughness})$ (§8.5, §21).
- **Candela (cd):** the unit of luminous intensity, lumens per steradian; Jeddy's point and spot light
  intensity (§3.5).
- **Cascade, cascaded shadow maps:** one of several sun shadow maps, each covering a depth range of
  the camera's view (§11.3).
- **Cluster, clustered shading:** a piece of the view frustum (screen tile × depth slice) with its own
  light list; shading that loops only over the pixel's cluster's list (§10).
- **Cook–Torrance BRDF:** the microfacet specular model $DGF/(4(\mathbf n\cdot\mathbf l)(\mathbf
  n\cdot\mathbf v))$ (§6.2).
- **Cosine law:** a beam's irradiance on a surface is its perpendicular irradiance times
  $\mathbf n\cdot\mathbf l$ (§3.3).
- **Cube map:** six square images on the faces of a cube, addressed by a direction (§8.1).
- **Depth clamp:** a GL mode that clamps fragment depths into $[0, 1]$ instead of clipping at the near
  and far planes (§11.5).
- **Directional light:** a light so far away that its direction and illuminance are the same
  everywhere: the sun (§7.3).
- **Energy compensation:** the factor $1 + F_0(1/E_{ss} - 1)$ that restores light lost to multiple
  bounces between microfacets (§6.7).
- **$F_0$:** specular reflectance at normal incidence; 0.04 for dielectrics, the base color for metals
  (§5).
- **Filtered importance sampling:** reading a blurrier source mip for samples that stand for larger
  solid angles, against fireflies (§8.7).
- **Fresnel effect, Schlick's approximation:** surfaces reflect more at grazing angles;
  $F_0 + (1 - F_0)(1 - \mathbf v\cdot\mathbf h)^5$ (§6.5).
- **G, masking–shadowing, Smith:** the fraction of facets visible from both the light and the eye;
  separable or height-correlated (§6.4).
- **GGX (Trowbridge–Reitz):** the normal distribution function Jeddy uses, with a long tail (§6.3).
- **GTAO:** ground-truth ambient occlusion: horizon angles searched in the depth buffer, integrated in
  closed form per slice (§13.2, §30).
- **Half vector:** the unit vector halfway between $\mathbf l$ and $\mathbf v$, the normal of the
  facets that reflect one into the other (§6.1).
- **Hammersley points:** a low-discrepancy point set $(i/N, \text{radicalInverse}_2(i))$ (§8.6).
- **Horizon (GTAO):** the angle from the view vector of the highest geometry on one side of a slice
  (§13.2).
- **Image-based lighting (IBL):** lighting from an image of the surroundings, here a cube map, SH and
  a LUT (§8).
- **Importance sampling:** drawing Monte Carlo samples where the integrand is large, with the density
  divided out (§8.6).
- **Integral:** the limit of a sum over ever thinner slices (§2.1).
- **Intensity (radiant, luminous):** flux per steradian from a small source (§3.2).
- **Inverse square law:** irradiance from a small source falls as $1/d^2$ (§3.2).
- **Irradiance, illuminance:** flux arriving per unit area; W/m² or lux (§3.3, §3.5).
- **Lambertian:** reflecting equally in all directions; BRDF $c/\pi$ (§6.6).
- **Lux (lx):** lumens per square metre; the unit of illuminance; the sun's `sunColor` (§3.5).
- **Metallic–roughness:** glTF's material model: base color, metallic, roughness (§5).
- **Microfacet:** one of the tiny mirrors a rough surface is modelled as (§6.1).
- **Monte Carlo integration:** estimating an integral by averaging $f/p$ at random samples (§8.6).
- **NDF (normal distribution function), $D$:** the density of facet normals (§6.2–6.3).
- **Nit:** candela per square metre; the unit of luminance, what the shader outputs per channel (§3.5).
- **Normal map, tangent space:** a texture of per-texel normal tilts, in the frame of tangent,
  bitangent and normal (§9).
- **Normal offset:** moving a receiver's position along its normal before a shadow lookup (§11.2).
- **PCF (percentage-closer filtering):** averaging the results of several shadow comparisons (§11.6).
- **Peter-panning:** shadows detached from their casters by too much bias (§11.2).
- **Polygon offset:** a depth offset proportional to a triangle's depth slope, added by the
  rasterizer (§11.2).
- **Practical split scheme:** cascade splits blended between logarithmic and uniform by $\lambda$
  (§11.3).
- **Prefiltered environment:** the cube map blurred by the GGX lobe, one mip per roughness (§8.4).
- **Punctual light:** a light all of whose light arrives from one direction: point, spot, sun (§4.4).
- **Radiance, luminance:** flux per unit area per unit solid angle; what a pixel shows; constant along
  a ray (§3.4).
- **Radiant flux, power:** energy per second, in watts (§3.1).
- **Radical inverse:** mirroring a number's binary digits behind the binary point (§8.6).
- **Range window:** the factor $\operatorname{saturate}(1 - (d/r)^4)^2$ that ends a light at its range
  (§7.1).
- **Rendering equation:** outgoing radiance = emitted + the integral of BRDF × incoming radiance ×
  cosine over the hemisphere (§4.1).
- **Roughness, $\alpha$:** perceptual roughness and its square, which the formulas use (§5).
- **Shadow acne:** stripes of false self-shadowing on lit surfaces (§11.2).
- **Shadow map:** a depth image rendered from a light, compared against to decide shadow (§11.1).
- **Slice (clusters):** one of the 24 depth intervals of the cluster grid (§10.2). **Slice (GTAO):** a
  half-plane through the view vector (§13.2).
- **Solid angle, steradian (sr):** how much of a sphere a direction set covers, area over radius
  squared; the sphere is $4\pi$ sr (§2.4).
- **Specular occlusion:** an occlusion factor for reflections derived from AO, roughness and
  $\mathbf n\cdot\mathbf v$ (§8.8).
- **Spherical harmonics (SH, SH9):** basis functions on the sphere by bands of frequency; the first
  nine store diffuse irradiance (§8.2).
- **Split sum:** the specular environment integral approximated as (prefiltered environment) × (BRDF
  integral) (§8.4).
- **Static-caster cache:** per light, a stored depth tile of its static casters, copied into the live
  atlas each frame (§11.7, §28).
- **Texel snapping:** moving a cascade by whole texels so shadow edges stay still (§11.4).
- **Visibility term $V$:** $G/(4(\mathbf n\cdot\mathbf l)(\mathbf n\cdot\mathbf v))$, written without
  divisions by cosines (§6.2, §6.4).
- **White furnace test:** a white object in a uniform white environment must vanish; it checks energy
  conservation (§6.7, §33.2).

---

*Code this chapter builds or quotes: `engine/brdf.glsl`, `engine/lights.glsl`,
`engine/environment.glsl`, `engine/shadows.glsl`, `engine/sun_shadows.glsl`,
`engine/local_shadows.glsl`, `engine/effects.glsl`, `engine/frame.glsl`, `engine/material.glsl`,
`pbr/pbr.vert`, `pbr/pbr.frag`, `pbr/brdf_lut.comp`, `pbr/cluster_bounds.comp`,
`pbr/light_cull.comp`, `post/gtao.frag`, `post/gtao_blur.frag`; `RenderPacket` (lights),
`light.Environment`, `light.LightClusters`, `shadow.SunShadows`, `shadow.LocalShadows`, `post.Gtao`,
`SceneRenderer` and `MaterialSystem` (the parts that wire lighting); the cooker's `EnvironmentBaker`,
`EnvironmentImporter` and `BrdfLutImporter`; the tests `SceneRendererTest` and `EnvironmentTest`.
Decisions: D27, D33, D39, D41, D42, D43, D44, D45, D48, D49, D103 in [DESIGN §17](../DESIGN.md).
Lessons: [L00](lessons/00-foundations.md) (the foundations), [L01](lessons/01-buffers-in-flight.md)
(the frame ring, fences and barriers, the counter reset), [L02](lessons/02-dice-and-probability.md)
(the mean, for Monte Carlo), [L03](lessons/03-data-layout.md) (struct of arrays, when threads pay).
Chapters: [02](02-math-foundations.md) (vectors, normals, change of basis), [03](03-rotations-and-transforms.md)
(angles, the QTangent, mirrored instances), [04](04-cameras-and-projection.md) (projection,
reversed-Z, unprojection, frustum planes), [05](05-opengl-from-java.md) (the pipeline, textures,
samplers, layouts), [06](06-renderer-architecture.md) (the renderer these passes live in),
[08](08-effects-and-post.md) (decals, fog of war, camera fade, exposure and tone mapping that turn
this chapter's radiance into an image), [10](10-assets.md) (the cooker and the KTX2 files).*

[← 06 The renderer](06-renderer-architecture.md) · [Index](README.md) · [08 Effects and post-processing →](08-effects-and-post.md)
