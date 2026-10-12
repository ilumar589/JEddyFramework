# Learning guide: Jeddy from the inside

This guide explains everything built in milestones M0 to M14, so you can understand the code and
learn the 3D math and OpenGL behind it. From M13 on, each milestone ships with its own chapter.

Each chapter takes one part of the engine and follows the same path:
1. **Concept.** The idea and the intuition behind it.
2. **Math or API.** Derived step by step, with small numeric examples.
3. **The real code.** A walkthrough with excerpts copied from the repository; file links sit next
   to them.
4. **Why it is built this way.** The performance reasons and the design decisions, with their
   numbers (D1–D95) in [DESIGN.md](../DESIGN.md) §17.
5. **Try it.** Tests to run, demo flags and experiments.
6. **Exercises.** Each one has a hint, and most are checked by a test.

**How to read it.** Keep the code open next to the chapter. When a chapter quotes a class, open
the file and read the surrounding code too. The excerpts are short on purpose. Run the tests a
chapter points to and change things: the exercises are designed for that.

**What you need.** Solid Java. No graphics background: the math starts at vectors, and OpenGL starts
at "what is a vertex shader".

## Chapters

| # | Chapter | What you learn | Main code |
|---|---|---|---|
| 01 | [Architecture: how a frame happens](01-architecture.md) | Modules and threads, fixed ticks and interpolation, render packets and the triple buffer, data-oriented design (SoA, handles, pools), the job system, the app layer | `core`, `scene`, `app`, `RenderPacket` |
| 02 | [Math foundations](02-math-foundations.md) | Vectors, dot and cross products, matrices, homogeneous coordinates, column-major `Mat4` against row-major 3×4 affine transforms, inverses, normals, packing | `math` |
| 03 | [Rotations, quaternions and transform hierarchies](03-rotations-and-transforms.md) | Rotation matrices, gimbal lock, quaternions from scratch, slerp and nlerp, the QTangent, parent-child transforms, interpolating between ticks | `math`, `scene`, `MeshEncoding` |
| 04 | [Cameras, projection, depth and culling](04-cameras-and-projection.md) | Coordinate spaces, look-at, perspective and orthographic projection, reversed-Z depth, the isometric camera, frustum culling, picking, LOD | `Mat4Ops`, `IsoCamera`, `CullOps`, `Culling` |
| 05 | [OpenGL from Java](05-opengl-from-java.md) | The GPU pipeline, GL 4.6 core and direct state access, generated FFM bindings, buffers, vertex arrays, textures, shaders and variants, uniform and storage buffers, the state cache | `gl`, `render/gpu`, `render/shader`, hello-triangle |
| 06 | [The renderer: from render packets to draw calls](06-renderer-architecture.md) | Mega buffers, multi-draw-indirect, instance records, sort keys and batching, frame rings, materials and texture arrays, the frame graph, GPU culling | `render/mesh`, `render/draw`, `render/material`, `render/graph`, `SceneRenderer` |
| 07 | [Lighting: PBR, clustered lights and shadows](07-lighting-and-shadows.md) | Radiometry, the Cook–Torrance BRDF (GGX, Smith, Schlick), image-based lighting, normal mapping, clustered forward+, cascaded and local shadow maps, GTAO | `pbr.frag`, `render/light`, `render/shadow`, `render/post/Gtao` |
| 08 | [Effects and post-processing](08-effects-and-post.md) | HDR, bloom, auto exposure, tone mapping, color grading, FXAA, outlines, decals, camera fade, fog of war, GPU particles, the sky | `render/post`, `outline`, `decal`, `fog`, `particle` |
| 09 | [Skeletal animation and GPU skinning](09-animation-and-skinning.md) | Skeletons and inverse binds, baked clips, blending and crossfades, linear blend skinning on the GPU, animated scenes in the world, the demo's animator | `animation`, `World`, `skinning.glsl` |
| 10 | [Assets: from glTF to the GPU](10-assets.md) | The cooker, glTF import, mesh optimization, BCn textures in KTX2, materials, environment maps, paks with zstd, the async asset manager, hot reload | `tools/cooker`, `asset` |
| 11 | [Physics: collision, navigation and crowds](11-physics-navigation-crowds.md) | Ray–box and ray–triangle tests, BVHs with SAH, navmesh baking in the style of Recast, A* and the funnel algorithm, crowds with ORCA, formations, visibility | `physics` |
| 12 | [Fast Java: FFM, zero allocation and the JIT](12-java-performance.md) | The Foreign Function & Memory API, zero allocation per frame and how it is proved, JMH, the perf capture, JIT and AOT, lock-free handoffs | `core`, `testing`, `bench`, the perf capture |
| 13 | [The user interface: immediate mode, flex layout and distance-field text](13-ui.md) | Immediate-mode UI, the six-pass flex layout derived and checked, hit testing against last frame's rectangles, drag and drop and focus, the exact Euclidean distance transform behind SDF fonts, the one-draw UI renderer with per-vertex clipping and premultiplied blending, widgets, the demo's screens | `ui`, `render/ui`, `app/ui`, `cooker/font`, `DemoUi` |
| 14 | [The level editor: a binary level format, commands with undo, picking, gizmos and docked panels](14-editor.md) | The `.jlevel` composition format and an editable SoA store with stable ids, placements as entity groups that follow edits, commands with explicit inverse data and merging, picking through inverse transforms, the gizmo math derived (ray–axis, ray–plane, signed angles, snapping, pixels at any depth), rotating a selection about a pivot, immediate-mode panels and id scopes, the standalone tool with the cooker watching | `editor`, `LevelData`, `LevelLoader`, `World.pick`, `cooker/level`, `tools/editor` |

## Lessons: math and GPU deep dives

From 2026-10-12 the guide also has standalone **lessons** in [`lessons/`](lessons/): one topic
each, derived from definitions to the line in the code, with numeric examples and exercises,
and with what the driver and the hardware do behind the GL calls. Every milestone adds at least
one. They assume the chapters they name.

| # | Lesson | What you learn | Builds on |
|---|---|---|---|
| L01 | [Buffers in flight](lessons/01-buffers-in-flight.md) | The GPU as a second computer and the command stream; immutable storage and its flags; the three ways to change a buffer and their ordering; fences, the three-frame ring and its sizing; barriers; the three findings of the first run on an Intel GPU and the bisect method that found the dropped write | 05, 06 |

Planned next (one per milestone, in the order the milestones need them): the d20's probability
distributions (M15); binary formats, alignment and endianness (M16); two-bone IK and quaternion
deltas, derived (M18); compute shaders, workgroups and atomics (M19); A* and admissible
heuristics (M20); data layout, cache lines and the flyweight view (M22); rendering for tools
(M23). Standing requests from the owner for the GPU side, to be scheduled: the projection
matrix and depth precision with numbers, the rendering equation to `pbr.frag` step by step,
texture memory (mips, block compression bit layouts, bandwidth), the pipeline stage by stage.

## Reading paths

- **Everything, in order:** 01 → 14. Each chapter links back to earlier ones when it builds on
  them.
- **3D math:**
  1. 02 Math foundations
  2. 03 Rotations
  3. 04 Cameras
  4. 09 Animation (applied quaternions and matrices)
  5. 11 Physics (geometry and algorithms)
  6. The lighting math in 07
  7. The gizmo and picking math in 14 (rays against axes, planes and boxes; Euler angles)
- **OpenGL:**
  1. 05 OpenGL from Java
  2. 06 The renderer
  3. 07 Lighting
  4. 08 Effects
  5. The GPU half of 09 (skinning)
  6. The renderer half of 13 (distance fields, vertex pulling, per-vertex clipping)

  Read 04 first if projection matrices are new to you.
- **Engine architecture:**
  1. 01 Architecture
  2. 12 Fast Java
  3. 06 The renderer
  4. 10 Assets
  5. 13 The user interface (an immediate-mode module on top of the renderer)
  6. 14 The level editor (commands, a level format, tools built from the same modules)

## Conventions at a glance

| Topic | Convention | Chapter |
|---|---|---|
| Axes | Right-handed, +Y up, models face +Z, the camera looks down its −Z | 02, 04 |
| Angles | Radians; yaw about +Y, yaw 0 faces +Z | 03 |
| `Mat4` | 16 floats, column-major: element (row r, column c) at index `c * 4 + r`; column vectors, `M · v` | 02 |
| Affine transforms | 12 floats, row-major 3×4: rows `[r00 r01 r02 tx]`, `[r10 r11 r12 ty]`, `[r20 r21 r22 tz]`; world = parent × local | 02, 03 |
| Quaternions | `(x, y, z, w)`, unit length, `q` and `−q` are the same rotation | 03 |
| Depth | Reversed-Z: near → 1, far → 0, `glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)`, 32-bit float depth, `GL_GEQUAL` | 04 |
| Units | Meters, seconds, linear RGB; light intensity in candela, the sun in lux | 07 |
| Threads | The main thread is the SDL and GL thread; the game thread simulates 30 Hz ticks and extracts a packet per frame; job workers help both | 01 |
| Memory | Primitive arrays (SoA) and `int` handles; no allocation per frame after loading | 01, 12 |

## Where each module is explained

| Module | Chapters |
|---|---|
| `core` (collections, handles, jobs, rings, natives) | 01, 12 |
| `math` | 02, 03, 04 |
| `scene` (`EntityPool`, `TransformStore`) | 01, 03 |
| `sdl`, `gl`, `window` | 05, 12 |
| `input` | 01 (event pump, gestures), 04 (picking) |
| `render` | 05, 06, 07, 08, 09 (skinning) |
| `asset` | 10 |
| `audio` | 01 (threads; Java never runs on the audio thread), 12 (FFM) |
| `physics` | 11 |
| `animation` | 09 |
| `app` (`JeddyApp`, `World`, `WorldRenderer`) | 01, 06, 09 |
| `tools/cooker` | 10 |
| `samples/iso-demo` | 01, 09 (animation), 12 (perf capture) |

## Running things

The `-P` options are quoted because PowerShell would otherwise split them at the dot; bash does
not mind the quotes. See [running.md](../running.md) for everything else that runs.

```
# build and test everything
./gradlew build
# one module's tests
./gradlew :math:test
./gradlew :math:test --tests 'dev.jeddy.math.MatrixAndQuatTest'
# include GPU tests (needs a GL 4.6 driver)
./gradlew :render:test "-Pjeddy.gpuTests=true"
# a JMH benchmark
./gradlew :bench:jmh "-Pjmh.includes=MathBench"
# the demo (cooks and packs assets first)
./gradlew :iso-demo:run
# its options
./gradlew :iso-demo:run --args="--help"
# a short perf capture, no GPU
./gradlew :iso-demo:run --args="--headless --perf 120"
# the smallest OpenGL program in the repo
./gradlew :hello-triangle:run
```

## Glossary

Each term links to the chapter that explains it best.

- **Affine transform:** rotation, scale and translation in 12 floats ([02](02-math-foundations.md)).
- **Alpha (interpolation factor):** how far the current frame is between two simulation ticks
  ([01](01-architecture.md)).
- **Bind pose, inverse bind matrix:** the pose a skinned mesh was modeled in, and the matrix that
  undoes it ([09](09-animation-and-skinning.md)).
- **BRDF:** the function that says how a surface reflects light ([07](07-lighting-and-shadows.md)).
- **BVH:** a bounding volume hierarchy for fast ray and overlap queries
  ([11](11-physics-navigation-crowds.md)).
- **Cascaded shadow maps:** several sun shadow maps that cover the view at increasing distances
  ([07](07-lighting-and-shadows.md)).
- **Clustered forward+:** lights binned into a 3D grid of view-space cells, so each pixel shades
  only nearby lights ([07](07-lighting-and-shadows.md)).
- **Cook (cooker), pak:** offline conversion of source assets to engine formats, and the archive
  that ships them ([10](10-assets.md)).
- **DSA (direct state access):** OpenGL calls that edit an object by name without binding it
  ([05](05-opengl-from-java.md)).
- **FFM:** Java's Foreign Function & Memory API, used for every native call
  ([12](12-java-performance.md)).
- **Frame graph:** the list of render passes with the resources they read and write, from which
  barriers and targets are derived ([06](06-renderer-architecture.md)).
- **Frustum:** the region the camera sees, bounded by six planes ([04](04-cameras-and-projection.md)).
- **Handle:** an `int` naming an object (index plus generation) instead of a reference
  ([01](01-architecture.md)).
- **IBL (image-based lighting):** lighting from an environment cube map, prefiltered per roughness
  ([07](07-lighting-and-shadows.md)).
- **Instance record:** the 64 bytes per drawn object that the vertex shader reads from a storage
  buffer ([06](06-renderer-architecture.md)).
- **LBS (linear blend skinning):** a vertex transformed by a weighted sum of joint matrices
  ([09](09-animation-and-skinning.md)).
- **MDI (multi-draw-indirect):** one GL call that issues many draws described in a buffer
  ([06](06-renderer-architecture.md)).
- **NDC, clip space:** the coordinates after projection ([04](04-cameras-and-projection.md)).
- **ORCA:** reciprocal collision avoidance for crowds ([11](11-physics-navigation-crowds.md)).
- **Palette:** the skinning matrices of one animated character for one frame
  ([09](09-animation-and-skinning.md)).
- **PBR:** physically based rendering ([07](07-lighting-and-shadows.md)).
- **QTangent:** a whole tangent frame stored as one quaternion ([03](03-rotations-and-transforms.md)).
- **Render packet:** everything the main thread needs to draw one frame, written by the game
  thread ([01](01-architecture.md), [06](06-renderer-architecture.md)).
- **Reversed-Z:** mapping near to depth 1 and far to 0 for even precision
  ([04](04-cameras-and-projection.md)).
- **SoA (struct of arrays):** one primitive array per field instead of an object per entity
  ([01](01-architecture.md)).
- **SSBO, UBO:** shader storage and uniform buffer objects ([05](05-opengl-from-java.md)).
- **Tick:** one fixed 1/30 s simulation step ([01](01-architecture.md)).
- **Zero allocation:** steady-state frames allocate no Java objects, which tests prove
  ([12](12-java-performance.md)).

## After the guide

- [DESIGN.md](../DESIGN.md) is the architecture reference, with the decision log in §17.
- [TASKS.md](../TASKS.md) has every milestone and what each task delivered.
- [getting-started.md](../getting-started.md) builds a game project from the template.
- [next-milestones.md](../next-milestones.md) lists the options for the next milestone. When you
  pick one, Claude writes a tutorial for it, and you write the code.

---

[01 Architecture →](01-architecture.md)
