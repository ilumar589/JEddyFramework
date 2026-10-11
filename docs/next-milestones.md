# Next milestones: the roadmap

Milestones M0 to M14 are done (see [TASKS.md](TASKS.md)). This page is the plan for what comes
next, decided with the owner on 2026-10-11, and the reference for every option.

**How the work goes.** Claude implements the milestones in the order below and asks the owner
whenever a decision is theirs. Every milestone ends with a learning-guide chapter
(`docs/learn/NN-<topic>.md`: the concept, the math or API derived step by step, the real code
explained, why it was built that way, exercises), the DESIGN section with its decisions, and a
merged PR. Two standing rules: **no new native libraries** (the runtime keeps SDL3, SDL3_mixer,
zstd, libktx and meshoptimizer; the cooker may use the JDK's `java.desktop`), and the UI and the
editor are **pure Java on top of `render`**.

## The order

| Milestone | Option | Size | Learning chapter |
|---|---|---|---|
| M13 | [Game UI](#1-game-ui-and-debug-ui): immediate-mode widgets, flex layout, SDF text, one-draw rendering | L | 13 |
| M14 | [Editor](#the-editor-m14): level editing with gizmos and undo, live entity inspector, asset browser with previews, built from the M13 widgets | L | 14 |
| M15 | [Rules (Pathfinder-like)](#4-rules-pathfinder-like) | L | 15 |
| M16 | [Save system](#5-save-system) | M | 16 |
| M17 | [Dialogue and quest scripting](#6-dialogue-and-quest-scripting), plus the graph editor panel in the editor | M | 17 |
| M18 | [Animation, part 2](#2-animation-part-2) | M | 18 |
| M19 | [VFX authoring](#3-vfx-authoring) | M | 19 |
| M20 | [Hierarchical pathfinding](#7-hierarchical-pathfinding) | M | 20 |
| M21 | Small items: [declarative scheduler](#8-declarative-system-scheduler), [BC6H](#10-bc6h-hdr-textures), [Chase-Lev](#12-chase-lev-work-stealing) if measurements ask for it | S–M | 21 |

Dropped, because they would add native libraries: [Tracy](#9-tracy-profiler-integration) (the
built-in profiler overlay and the perf capture stay the tools) and [LZ4](#11-lz4-codec) (zstd
stays the only codec). [GPU validation on real hardware](#13-gpu-validation-on-real-hardware)
waits for access to the GPUs; [value records and the Vector API](#14-value-records-and-the-vector-api)
wait for the JDK.

Each option below lists:
- what it is and why a Pathfinder-like CRPG needs it;
- the code it touches;
- a draft task list;
- what you learn, especially math and OpenGL;
- which chapters of the [learning guide](learn/README.md) to read first.

The size is S (a day or two), M (about a week) or L (several weeks).

| # | Option | Size | Mostly teaches |
|---|---|---|---|
| 1 | [Game UI and debug UI](#1-game-ui-and-debug-ui) | L | OpenGL 2D: orthographic projection, batching, SDF text, clipping; layout algorithms |
| 2 | [Animation, part 2](#2-animation-part-2) | M | Math: two-bone IK, quaternions, root motion, quantization |
| 3 | [VFX authoring](#3-vfx-authoring) | M | OpenGL compute, GPU particles, curves and gradients, flipbooks, soft particles |
| 4 | [Rules (Pathfinder-like)](#4-rules-pathfinder-like) | L | Data-oriented game logic, deterministic RNG, turn flow |
| 5 | [Save system](#5-save-system) | M | Binary formats, versioning, compression, background IO |
| 6 | [Dialogue and quest scripting](#6-dialogue-and-quest-scripting) | M | Graph data, a small interpreter, tooling |
| 7 | [Hierarchical pathfinding](#7-hierarchical-pathfinding) | M | Graph algorithms on the navmesh, clustering, caching |
| 8 | [Declarative system scheduler](#8-declarative-system-scheduler) | M | Dependency graphs, the job system, data races |
| 9 | [Tracy profiler integration](#9-tracy-profiler-integration) (dropped) | S | FFM to a C API, GPU timestamp queries |
| 10 | [BC6H HDR textures](#10-bc6h-hdr-textures) | S | Texture compression, HDR formats, KTX2 |
| 11 | [LZ4 codec](#11-lz4-codec) (dropped) | S | Compression trade-offs, FFM, benchmarking |
| 12 | [Chase-Lev work stealing](#12-chase-lev-work-stealing) | M | Lock-free concurrency, the Java memory model |
| 13 | [GPU validation on real hardware](#13-gpu-validation-on-real-hardware) | S | GPU profiling, vendor differences (needs NVIDIA, AMD and Intel GPUs) |
| 14 | [Value records and the Vector API](#14-value-records-and-the-vector-api) | S | Valhalla and SIMD in Java (waits for the JDK features to become final) |

[Warm-up tasks](#warm-up-tasks) are small real fixes. [Other ideas](#other-ideas-not-in-the-backlog-yet)
lists a few rendering topics that are not in the backlog yet but teach a lot of OpenGL.

---

## The editor (M14)

**What.** A level and content editor that runs as a `Game` on the same application loop, drawn
with the M13 UI module over the live scene (no native UI library, no second window system):
- **Level editing:** place, move, rotate and scale props, lights, spawn points and triggers in the
  3D view with gizmos; snapping; undo and redo; multi-selection; save and load level files that the
  cooker and the game read.
- **Live entity inspector:** a tree of entities and their components, with editable properties
  (transforms, materials, lights, animation state) while the game runs.
- **Asset browser and previews:** browse the cooked tree, preview meshes, textures, materials and
  animation clips, re-cook on change (the cooker's watch mode).
- **Dialogue and quest graph editor:** a node-graph panel, added with M17.

**Approach.** Panels dock around a viewport region; the scene renders into the viewport and the
UI draws the rest. Editing operations are commands on a stack (undo/redo). The level file is a
text format (JSON, through `core.json`) that the cooker turns into the scene and spawn data the
game already loads. The editor lives in `tools/editor`, a `jeddy.application` like the demo, and
the demo's crypt becomes its first project.

**Done (2026-10-11).** Shipped as planned with three changes decided with the owner when it
started: the level file is a binary `.jlevel` of record rather than JSON (D86, the JSON is a dump
for review), the editor is a *library* any game embeds behind F10 plus a thin `tools/editor`
application (D88), and the panels dock over the scene rendered full-window instead of into a
viewport texture (D88). Previews in the asset browser and the graph editor wait for M17. The
tasks are M14-01 to M14-09 in [TASKS.md](TASKS.md); the chapter is
[14 The level editor](learn/14-editor.md).

**What you learn.** Gizmo math (ray–plane and ray–axis intersection for dragging in 3D, rotation
by projected angles), command pattern with undo, docking layouts, rendering into a viewport, the
difference between an editor's data model and the runtime's.

---

## 1. Game UI and debug UI

**What.** The game needs screens: inventory, character sheet, spellbook, dialogue window, party
portraits, tooltips and a combat log. The developers need a debug UI to tweak values live and to
inspect entities. Today there is only the HUD overlay (`render/debug/Overlay`, a 5×7 bitmap font).

**Approach (decided: see DESIGN §18, D82–D84).**
- A new `ui` module, pure Java, independent of GL: an **immediate-mode** API (the game declares its
  UI every frame; state stays in the game's data) with an **end-of-frame flex layout** pass over an
  element tree kept in SoA arrays, as Clay does. Hit testing uses the previous frame's layout.
- Text uses **signed-distance-field** glyph atlases cooked from TTF/OTF by the cooker (rasterized
  with the JDK's `java.desktop`, no native library), so one atlas serves every size.
- Rendering is one batched pass after post-processing: the UI's draw list (vertices, indices,
  clip rectangles, texture references) travels in the render packet and the renderer draws it with
  one draw per texture change; clipping is a per-vertex clip rectangle index tested in the
  fragment shader.
- The debug UI is the same module: no Dear ImGui and no cimgui.

**Draft tasks.**
- M13-01 Design: DESIGN §18 `ui`, decisions, tasks.
- M13-02 Cooker: SDF font atlas from TTF/OTF (glyph metrics, kerning) into `.jfont` + KTX2; the
  default font (Inter, OFL) as a built-in output.
- M13-03 `ui` core: element tree, flex layout (fit, grow, percent, wrap), text measurement, the
  draw list; layout tests and an allocation test.
- M13-04 Widgets: text, images and nine-slices, buttons, checkboxes, sliders and drag values,
  text fields, dropdowns, tree nodes, tabs, scroll regions, tooltips, popups, drag and drop.
- M13-05 Input and focus: hover, press, click, drag, keyboard focus and navigation, gamepad
  navigation; the game ignores input the UI consumed.
- M13-06 `render`: the UI renderer (vertex buffer in the frame ring, SDF text shader, rounded
  rectangles, clipping), a GPU test.
- M13-07 Demo screens: party portraits with health bars, an inventory grid with drag and drop,
  tooltips, a combat log, a pause panel; the UI in the perf capture with a budget.
- M13-08 Docs: learning chapter 13, DESIGN, README.

**What you learn.**
- OpenGL: orthographic projection; vertex layouts for 2D; premultiplied-alpha blending; scissor
  test; texture atlases; SDF rendering (`smoothstep` on a distance with `fwidth` for
  antialiasing); instanced quads.
- Math: rectangles, anchoring and layout, the screen → UI → world transforms.
- Algorithms: a two-pass flex layout (fit sizes up the tree, grow sizes down), text wrapping,
  signed distance transforms, the hot/active/focus model of immediate-mode UIs.

**Read first:** learning guide [05 OpenGL from Java](learn/05-opengl-from-java.md) and
[06 Renderer architecture](learn/06-renderer-architecture.md).

---

## 2. Animation, part 2

**What.** The animation features M12 left out (DESIGN §13.4, "Not yet"):
- **Root motion:** the clip moves the character, for lunges and knock-backs, instead of the crowd
  doing it.
- **Animation events:** footstep sounds, the frame where a weapon hits, the frame where a spell
  leaves the hand.
- **Foot IK:** feet planted on stairs and slopes instead of floating or sinking.
- **Additive layers:** a wounded limp or a breathing layer on top of any locomotion.
- **Clip compression:** quantized rotations and translations, so libraries stay small when there
  are hundreds of clips.

**Approach.**
- Root motion: extract the root joint's per-frame delta (position and yaw) at bake time; expose
  `Animations.rootDelta(instance)`; the game applies it to the agent (crowd velocity control).
- Events: named markers per clip in a sidecar (`.anim.json`) or from glTF extras, fired by
  `update` into a preallocated event ring that the game drains on the game thread.
- Foot IK: per foot, a ray cast down against the collision BVH, then an analytic **two-bone IK**
  (hip, knee, ankle) solved with the law of cosines. The pole vector keeps the knee pointing
  forward, the pelvis drops by the largest correction, and the ankle aligns with the ground
  normal.
- Additive: store a clip as a delta from a reference pose, $\Delta q = q_{ref}^{-1} q$. Apply it
  with $q' = q_{base} \cdot \text{slerp}(1, \Delta q, w)$.
- Compression:
  - rotations as "smallest three": drop the largest quaternion component, store three 15-bit
    values and 2 bits for which component was dropped;
  - translations quantized inside per-clip bounds;
  - a test that keeps the error under a tolerance.

**Draft tasks.**
- M13-01 Design.
- M13-02 Root motion bake and API.
- M13-03 Events.
- M13-04 Two-bone IK solver with tests.
- M13-05 Foot IK in the demo, on stairs and rubble.
- M13-06 Additive clips and layers.
- M13-07 Quantized clip storage with an error test and a benchmark.
- M13-08 Docs.

**What you learn.**
- Math: the law of cosines, building rotations from two vectors, pole vectors, quaternion algebra
  (inverse, delta, slerp toward identity), frame-to-frame differences of rigid transforms,
  quantization error.
- Java: keeping the solvers allocation-free and parallel.

**Read first:** [02 Math foundations](learn/02-math-foundations.md),
[03 Rotations and transforms](learn/03-rotations-and-transforms.md),
[09 Animation and skinning](learn/09-animation-and-skinning.md).

---

## 3. VFX authoring

**What.** Today the GPU particles (`render/particle/GpuParticles`) take emitters straight from the
render packet with fixed parameters. Spells in a CRPG need data-driven effects: an effect asset
with several emitters, curves over lifetime (size, color, speed), flipbook textures, soft particles
that fade where they touch geometry, ribbons and trails, and light flashes.

**Approach.**
- Effect assets (`.jvfx`, JSON source cooked to binary):
  - emitters with spawn shapes;
  - curves sampled into small 1D textures, or a constant array per emitter;
  - a flipbook atlas;
  - blend mode.
- Simulation stays in compute shaders:
  - curve lookups by normalized age;
  - optional collision with the depth buffer.
- Soft particles fade by the difference between the particle's depth and the scene depth (both
  reversed-Z).
- Trails: a ring of points per emitter, expanded to a camera-facing strip in the vertex shader.
- A `VfxSystem` on the game thread spawns effects by id at positions or attached to joints
  (fireball in the caster's hand, using M12's attachments).

**Draft tasks.**
- M13-01 Design.
- M13-02 Effect format and cooker.
- M13-03 Curves and gradients on the GPU.
- M13-04 Flipbooks and soft particles.
- M13-05 Trails.
- M13-06 Joint-attached effects.
- M13-07 The demo's fireball, hit sparks and death puffs as assets.
- M13-08 Docs.

**What you learn.**
- OpenGL: compute shaders, SSBOs, atomic counters, indirect dispatch and draw, depth reconstruction
  from the depth buffer, blending modes, texture arrays for flipbooks.
- Math: camera-facing billboards and strips, curve evaluation (piecewise linear, Bézier), random
  distributions on spheres and cones.

**Read first:** [06 Renderer architecture](learn/06-renderer-architecture.md),
[08 Effects](learn/08-effects-and-post.md).

---

## 4. Rules (Pathfinder-like)

**What.** The game-mechanics layer that makes it a CRPG:
- **Characters and checks:** ability scores and modifiers; d20 checks against a DC; attack rolls
  against AC; damage dice; saving throws.
- **Effects over time:** conditions with durations (prone, frightened, stunned); buffs and
  stacking rules.
- **Turn-based flow:** initiative order; action economy (standard, move, swift, full-round);
  attacks of opportunity (using `TurnBasedNav`'s threat-zone crossing query).
- **Real time with pause:** the same rules on 6-second rounds.

**Approach.**
- A new `rules` module, pure Java and data-oriented:
  - stats as SoA arrays per creature;
  - modifiers as typed entries with stacking rules (same-type bonuses don't stack);
  - a deterministic RNG per encounter (replays, tests).
- Rules are data: feats, spells and items describe their effects as small operation lists that the
  rules engine interprets. They are not subclasses.
- A combat log of events feeds the UI, or the overlay until the UI exists.

**Draft tasks.**
- M13-01 Design.
- M13-02 Creatures, stats, modifiers and stacking.
- M13-03 Dice, checks, attacks and damage.
- M13-04 Conditions and durations.
- M13-05 Initiative and the turn-based loop.
- M13-06 Attacks of opportunity with `TurnBasedNav`.
- M13-07 Real-time-with-pause rounds.
- M13-08 The demo's fights with the rules (to-hit, damage, death).
- M13-09 Docs.

**What you learn.** Data-oriented design of game logic; probability (the distribution of d20 plus a
modifier, expected damage); deterministic simulation; testing rules with fixed seeds.

**Read first:** [01 Architecture](learn/01-architecture.md),
[11 Physics, navigation and crowds](learn/11-physics-navigation-crowds.md).

---

## 5. Save system

**What.** Save and load at any time:
- quick save, autosave on area change, and named saves;
- a save made by an older build still loads after the game is patched;
- no hitch while saving.

**Approach.**
- A new `save` module:
  - Each system writes its SoA arrays as tagged, versioned chunks: world entities and transforms,
    renderables, animation states, crowd agents, AI state, rules state.
  - A snapshot is taken on the game thread between ticks by copying arrays into a preallocated
    buffer. Copying is fast because everything is primitive arrays.
  - Serializing, compressing with zstd and writing to disk happen on a background virtual thread.
- Handles are remapped on load, because entity indices may differ.
- Version upgrades are small functions per chunk version.

**Draft tasks.**
- M13-01 Design.
- M13-02 Chunk format and writer/reader.
- M13-03 Snapshots of `World`, `TransformStore` and `Animations`.
- M13-04 Crowd and AI.
- M13-05 Background writing and the hitch test.
- M13-06 Load with handle remapping.
- M13-07 Version migrations with tests.
- M13-08 Demo quick save/load (F5/F9).
- M13-09 Docs.

**What you learn.** Binary formats (alignment, endianness, versioning); copy-on-snapshot with a
background writer; the hard parts of identity (handles across sessions).

**Read first:** [01 Architecture](learn/01-architecture.md), [10 Assets](learn/10-assets.md).

---

## 6. Dialogue and quest scripting

**What.**
- Branching conversations with conditions ("if Intelligence ≥ 14", "if the quest is at stage 3"),
  skill checks and consequences (give an item, start a fight, set a flag).
- Quests with stages and journal entries.

**Approach.**
- Dialogue and quest graphs in a text format, cooked to a compact binary.
- A small interpreter on the game thread: nodes, edges with conditions, actions.
- Conditions and actions call into `rules` (or a stub until `rules` exists).
- A dialogue window (needs some UI, or a simple overlay version first).

**Draft tasks.**
- M13-01 Design.
- M13-02 Source format and cooker.
- M13-03 Interpreter, conditions and actions.
- M13-04 Quest stages and journal.
- M13-05 Demo: talk to a ghost in the crypt.
- M13-06 Docs.

**What you learn.** Graph data structures, interpreters, designing a data format for writers rather
than programmers.

---

## 7. Hierarchical pathfinding

**What.** Paths across large maps cost a lot with plain A* over thousands of navmesh polygons.
Hierarchical pathfinding (HPA*-style) clusters polygons into regions and searches the region
graph first, then refines the path inside each region.

**Approach.**
- Cluster the baked navmesh (by tile or by flood fill).
- Precompute portals between clusters and the costs between portals.
- At query time, run A* on the abstract graph, then a corridor of polygon searches.
- Cache abstract paths and invalidate them when doors and obstacles change (the tile cache already
  rebuilds tiles).

**Draft tasks.**
- M13-01 Design.
- M13-02 Clustering and the portal graph.
- M13-03 Abstract search and refinement.
- M13-04 Invalidation with dynamic obstacles.
- M13-05 Benchmarks against plain A* on a 1 km map.
- M13-06 Docs.

**What you learn.** A*, admissible heuristics, graph abstraction, cache invalidation.

**Read first:** [11 Physics, navigation and crowds](learn/11-physics-navigation-crowds.md).

---

## 8. Declarative system scheduler

**What.** Today a game calls its systems in an explicit order in `fixedUpdate`, and each system
parallelizes internally (D67). A scheduler would let systems declare what they read and write.
It would then run independent systems in parallel and check for data races in debug builds.

**Draft tasks.**
- M13-01 Design.
- M13-02 System descriptors (reads, writes, phase).
- M13-03 DAG build and a parallel executor on the `JobSystem`.
- M13-04 Debug race checks.
- M13-05 Port the demo.
- M13-06 Benchmarks and docs.

**What you learn.** Dependency graphs, topological sorting, how ECS schedulers (Bevy, Unity DOTS)
work, and when they are worth it.

---

## 9. Tracy profiler integration

**What.** [Tracy](https://github.com/wolfpld/tracy) shows CPU zones from every thread and GPU
timings on one timeline. That beats the built-in profiler overlay when hunting a spike.

**Approach.**
- Tracy's C API in the natives build, with jextract bindings and a `Profiler` facade.
- Zone begin and end calls are allocation-free, through static source-location structs in a
  confined arena.
- GPU zones come from the existing `GpuTimers`.
- Off unless `-Djeddy.tracy=true`.

**What you learn.** FFM details: structs, static memory, very short downcalls, and when
`Linker.Option.critical` is safe; GL timestamp queries.

---

## 10. BC6H HDR textures

**What.** The environment cube and other HDR textures are uncompressed half-float today. BC6H
compresses HDR at 1 byte per texel. KTX v5 (libktx) can encode it.

**What you learn.** Block compression formats, HDR color, and how BC6H differs from BC7.

---

## 11. LZ4 codec

**What.** A faster-to-decompress codec next to zstd, for paks where load time matters more than
size. It is only worth it if profiling shows zstd decompression is the bottleneck (DESIGN §16).

**What you learn.** Compression trade-offs (ratio against speed), measuring before optimizing.

---

## 12. Chase-Lev work stealing

**What.** The `JobSystem` uses shared queues. A Chase-Lev deque per worker (owner pushes and pops
at the bottom, thieves steal at the top) scales better under contention. Do it only if M1-07's
measurements show contention.

**What you learn.** Lock-free algorithms, `VarHandle` memory ordering (acquire, release, opaque),
the ABA problem, testing concurrency with jcstress.

---

## 13. GPU validation on real hardware

**What.** The two open tasks:
- **M5-11:** measure texture arrays against `ARB_bindless_texture` on NVIDIA, AMD and Intel, then
  finalize ADR 0001.
- **M10-06:** run the perf scene on the reference PC and fix the main-thread and GPU budgets
  (docs/perf/m10.md, O1–O2).

**Needs:** access to those GPUs (CI only has Mesa llvmpipe).

**What you learn.** Reading GPU timings per pass, vendor differences, and the performance cost of
binding models.

---

## 14. Value records and the Vector API

**What.** The math records are written to become `value record`s (JEP 401), and batch kernels
could use the Vector API. Both wait for the JDK features to become final (non-negotiable 2:
stable Java only). They are not available yet.

---

## Warm-up tasks

These small fixes and improvements are real gaps in the code. Each takes an evening and is a good
way back into the code before a milestone. They come with the guide exercise that sets them up.

- **Mirrored instances.** A transform with a negative determinant flips triangle winding, so
  back-face culling hides a mirrored prop's front faces (DESIGN §9.2, known limitation). The fix:
  - give mirrored instances their own batches through a sort-key bit;
  - draw those batches with `glFrontFace(GL_CW)`;
  - prove it with a GPU test.

  Background: [02 Math foundations, determinants](learn/02-math-foundations.md#determinants-and-mirroring).
- **Orthographic zoom.** In orthographic mode the wheel zoom changes `distance`, which has no
  visible effect. Make zoom work in both modes and keep `lodPixels` consistent. See
  [04, exercise 7](learn/04-cameras-and-projection.md#exercises).
- **One geometry term.** The BRDF LUT uses a separable Schlick–Smith term, while direct lights use
  the height-correlated Smith term. Make them consistent and measure the difference in the white
  furnace test. See [07, exercise 6](learn/07-lighting-and-shadows.md#exercises).
- **DESIGN housekeeping.** While reading the guide, check DESIGN.md against the code and fix what
  drifted. The guide's reviewers fixed the drift they found.

## Other ideas (not in the backlog yet)

These teach a lot of OpenGL and fit an isometric CRPG. Ask to add one to the options.
- **Terrain and water:** outdoor maps with a height field (vertex texture fetch or tessellation),
  splat-mapped materials and a water plane with reflections, refraction and depth-based color.
- **Temporal anti-aliasing:** motion vectors per pixel (skinned meshes included), a history buffer,
  neighborhood clamping. It replaces FXAA, and later enables upscaling.
- **GPU skinning in compute:** evaluate palettes, or pre-skin vertices, on the GPU for very large
  crowds.

---

## The chapter that ships with each milestone

Each milestone's learning chapter, `docs/learn/NN-<topic>.md`, follows the chapters of M0–M12:

1. **Goal and result.** What was built and what the demo or the editor shows at the end.
2. **Background.** The theory, with derivations, diagrams and small numeric examples.
3. **The code.** A walkthrough of the real sources with excerpts and file links; the design, and
   why the alternatives were rejected (with the decision numbers from DESIGN §17).
4. **Pitfalls.** The mistakes the implementation had to avoid: coordinate spaces, GL state that
   leaks between passes, allocation in hot paths, threads.
5. **Performance.** The allocation tests, the benchmark and the budget in the perf capture.
6. **Try it and exercises.** Tests to run, demo flags, and extensions to try with hints.
