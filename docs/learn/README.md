# Learning guide: Jeddy from the inside

This guide explains everything built in milestones M0 to M16 and M22, so you can understand the
code and learn the 3D math and OpenGL behind it. From M13 on, each milestone ships with its own
chapter, numbered after the milestone (22 was built before 16, which builds on it).

Since 2026-10-14 every chapter is written like a chapter of a book on building the engine, for a
reader who knows no math and no GPU programming beyond lesson [L00](lessons/00-foundations.md) (the
owner's request of 2026-10-12). Each one takes one part of the engine and has three parts:
1. **Part A — the ideas.** The concepts from zero; every formula the code uses derived from
   definitions, step by step, with a worked numeric example; for every GPU feature, what the
   pipeline stage, the driver and the hardware do, what memory it touches, what synchronization it
   needs and what can go wrong.
2. **Part B — building it.** "Step 1, Step 2, …" in the order you would write the code from empty
   files, quoting the repository's real code (condensed excerpts say so), with what every method and
   every non-obvious line does, and every decision with the alternatives that were rejected and its
   number (D1–D119) in [DESIGN.md](../DESIGN.md) §17.
3. **Part C — using it and proving it.** How the rest of the engine uses it, the tests walked
   through, things to run with exact commands, measured numbers with their sources, and the known
   problems in the code.

Then **exercises**, each with a hint and a full worked solution, and a **glossary**. Every code
quote and number was checked against the repository by an independent reviewer.

**How to read it.** Keep the code open next to the chapter, run the tests a chapter points to and
change things: the exercises are designed for that. The chapters are long (3,500 to 14,000 lines);
the Contents list at the top of each links every section. Chapter 11 is larger than GitHub's web
view renders in full, so read it in a local checkout or an editor.

**What you need.** Solid Java. No graphics background: the math starts at vectors, and OpenGL starts
at "what is a vertex shader". If binary numbers, bit operations, caches or what a GPU does are new
to you, read lesson [L00 Foundations](lessons/00-foundations.md) first; the lessons assume nothing
beyond it.

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
| 15 | [Rules: a d20 engine without objects](15-rules.md) | The mathematics of a d20 game (modifiers, the 5 %-per-point check, criticals and expected damage, saves, stacking, dying, rounds and the real-time clock); dice packed in an `int`; creatures and modifiers as SoA stores; checks, attacks and damage; events instead of strings; content as flat effect operations in `.jrules`; encounters, action budgets, the turn-based and real-time drivers; the demo's `Combat` | `rules`, `math.Dice`, `math.Rng`, `cooker/rules`, `RulesSchema`, `Combat` |
| 16 | [Saves: the dynamic state, in chunks, written in the background](16-saves.md) | What a save is (dynamic, not static or derived) and why that makes patching possible; one binary stream every system writes itself into; identity across runs (slots that stay, stable entity ids from the game, creatures by content id rebuilt from the current rulebook); the `.jsave` container with its meta block and checksums; versions at three levels with golden files; the capture on the game thread and the job on a parked worker with an atomic replace; the demo's quick save, autosave and slots, and the byte-exact round-trip check that found the real bugs | `save`, `core.io`, `EntityIds`, `Crowd`/`Visibility`/`Rules`/`Encounter` `write`/`read`, `CryptSave`, `CryptIds` |
| 22 | [Components and behaviours: a gameplay API generated at compile time](22-components.md) | Records as components and static methods as behaviours; what an annotation processor generates (SoA stores over sparse sets, flyweight views, backwards loops, job graphs from declared reads and writes, the registry, the schema); why the flyweight is free after inlining; stages, signals, the demo's skeletons and fireballs as components; component rows in levels and the inspector; level bindings; self-describing snapshots; the parallel measurement and its rule | `components`, `tools/component-processor`, `SkeletonAi`, `CryptGame`, `LevelData` v2, `InspectorPanel` |

## Lessons: math and GPU deep dives

The guide also has standalone **lessons** in [`lessons/`](lessons/). Since 2026-10-12 they are
written for a reader who knows no math and no GPU programming (the owner's request): lesson L00
teaches the foundations, and every other lesson is a chapter of a book on building the engine. It
starts from empty files and writes the repository's real code step by step, says what every
function and every non-obvious line does, gives each decision with the alternatives that were
rejected, derives every formula from definitions with worked numbers, and explains what the driver
and the hardware do behind each GPU call. Each lesson ends with exercises that have hints and full
solutions, and a glossary. Every milestone adds at least one lesson.

| # | Lesson | What you build and learn | Needs |
|---|---|---|---|
| L00 | [Foundations](lessons/00-foundations.md) | The math notation (sums, powers, logarithms, `mod`, rounding up to a multiple, big-O); binary and hexadecimal; bits, bytes and Java's number types; two's complement and the signed-byte trap; bit operations, masks and packing; how a `float` is stored; memory, the heap, native memory and alignment; the CPU, caches, cache lines and dependent loads; threads, `volatile` and compare-and-set; what a GPU, a driver, OpenGL and a frame are; FFM | Java |
| L01 | [Build a frame ring](lessons/01-buffers-in-flight.md) | `GpuBuffers` and `FrameRing` from empty files: the command stream and "in flight", GPU memory, mapping and write-combining, immutable storage and its flags, the three ways to change a buffer, fences, coherence and barriers; the three-region design, the lock-free allocator, the GPU-side copy; the ring's users (instance records, the material table, a GPU counter, GPU culling); its test; the three driver findings on an Intel GPU and the bisect that found them | L00 §6, §8–11 |
| L02 | [Build the dice](lessons/02-dice-and-probability.md) | Probability from counting (events, independence, means, variances, sums of dice and the bell, the flat d20 and its 5 % per point, conditional probability, criticals, saves, a Markov chain for fight length); `Rng` from empty file (SplitMix64, xoshiro256**, Lemire's unbiased `nextInt`, floats from bits); `Dice` packed in an `int`; `Rules.check` and `attack`; testing randomness with chosen seeds and with tolerances derived from the variance | L00 §2, §4–6 |
| L03 | [Build a component store](lessons/03-data-layout.md) | A measured experiment on access order; what a Java object costs; AoS against SoA with the cache lines counted; handles and the handle pool; the sparse set; the store base class, the generated columns, flyweight views and the generated loop; what the JIT does to them; the benchmark; when a parallel loop pays | L00 §6, §8–9 |
| L04 | [Build a save file format](lessons/04-binary-formats.md) | Byte order, floats as bits, UTF-8, alignment and padding, checksums from parity to CRC-32C, compression and versions; `ByteWriter`, `ByteReader` (and the three hostile-file gaps the lesson's review closed), `SaveWriter`, `SaveFile` encode and parse in the right order, the atomic replace, golden files; a real `.jsave` read byte by byte | L00 §3–8 |

Planned next (one per milestone, in the order the milestones need them): a script compiler and a
stack machine — grammars, recursive descent, bytecode (M17); two-bone IK and quaternion deltas,
derived (M18); compute shaders, workgroups and atomics (M19); A* and admissible
heuristics (M20); rendering for tools (M23). Standing requests from the owner for the GPU side, to be scheduled: the projection
matrix and depth precision with numbers, the rendering equation to `pbr.frag` step by step,
texture memory (mips, block compression bit layouts, bandwidth), the pipeline stage by stage.

## Reading paths

- **New to programming close to the machine:** lesson L00, then the lessons L01–L04 (each builds a
  real part of the engine from empty files), then the chapters in order.
- **Everything, in order:** 01 → 15, then 22, then 16 (it uses the stores of 22). Each chapter
  links back to earlier ones when it builds on them.
- **3D math:**
  1. 02 Math foundations
  2. 03 Rotations
  3. 04 Cameras
  4. 09 Animation (applied quaternions and matrices)
  5. 11 Physics (geometry and algorithms)
  6. The lighting math in 07
  7. The gizmo and picking math in 14 (rays against axes, planes and boxes; Euler angles)
  8. The probability in 15 and lesson L02 (a different kind of math: discrete distributions)
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
  7. 15 Rules (a gameplay module with the same discipline: SoA stores, `int` handles, events)
  8. 22 Components (the discipline generated: records, static behaviours, a processor) and
     lesson L03
  9. 16 Saves (the state crossing runs: identity, formats, a background job) and lesson L04

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
| `physics` | 11; 16 (`Crowd`, `Visibility` and `FormationMove` saving themselves) |
| `animation` | 09 |
| `app` (`JeddyApp`, `World`, `WorldRenderer`) | 01, 06, 09 |
| `ui` | 13 |
| `editor`, `tools/editor` | 14, 22 (the Components section of the inspector) |
| `rules` | 15, lesson L02; 16 (creatures by content id, the overlay) |
| `components`, `tools/component-processor` | 22, lesson L03; 16 (`EntityIds`, stores as ids) |
| `save` | 16, lesson L04 |
| `tools/cooker` | 10, 15 (`RulesImporter`), 22 (row validation, level bindings) |
| `samples/iso-demo` | 01, 09 (animation), 12 (perf capture), 15 (`Combat`, the drivers), 22 (`SkeletonAi`, `Fireballs`, `CryptGame`), 16 (`CryptSave`, `CryptIds`, the pause menu's saves) |

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
- **Check, DC, threat range, action budget:** the d20 vocabulary ([15](15-rules.md)).
- **Component, behaviour, registry, view:** a record of per-entity data, a static method over
  views of it, the generated class that owns the stores and runs the phases, and the flyweight
  that reads one row ([22](22-components.md)).
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
- **Save, capture, chunk, stable id, golden file:** a save is the dynamic state only; a capture
  copies it into kept buffers between ticks; a chunk is one system's tagged, versioned record;
  a stable id names an entity across runs; a golden file is the bytes an old format wrote, kept
  under test ([16](16-saves.md), [L04](lessons/04-binary-formats.md)).
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
