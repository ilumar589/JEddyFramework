# Next milestones: the options

Milestones M0 to M12 are done (see [TASKS.md](TASKS.md)). This page keeps every candidate for the
next milestone, M13, so you can pick one when you come back.

**How resuming works (your request of 2026-10-11).** When you return, Claude asks which option you
want. It then writes a **tutorial** for that milestone in `docs/tutorials/`, with the theory,
step-by-step explanations and the code. You type the code yourself, and Claude does not implement
the milestone. When you ask, it reviews your code, helps you debug, and ticks the tasks.
[The tutorial format](#the-tutorial-format) at the end describes what each tutorial contains.

Each option below lists:
- what it is and why a Pathfinder-like CRPG needs it;
- the code it touches;
- a draft task list;
- what you learn, especially math and OpenGL;
- which chapters of the [learning guide](learn/README.md) to read first.

The size is S (a day or two), M (about a week) or L (several weeks).

| # | Option | Size | Mostly teaches |
|---|---|---|---|
| 1 | [Game UI and debug UI](#1-game-ui-and-debug-ui) | L | OpenGL 2D: orthographic projection, batching, text, scissor; FFM to a C++ library |
| 2 | [Animation, part 2](#2-animation-part-2) | M | Math: two-bone IK, quaternions, root motion, quantization |
| 3 | [VFX authoring](#3-vfx-authoring) | M | OpenGL compute, GPU particles, curves and gradients, flipbooks, soft particles |
| 4 | [Rules (Pathfinder-like)](#4-rules-pathfinder-like) | L | Data-oriented game logic, deterministic RNG, turn flow |
| 5 | [Save system](#5-save-system) | M | Binary formats, versioning, compression, background IO |
| 6 | [Dialogue and quest scripting](#6-dialogue-and-quest-scripting) | M | Graph data, a small interpreter, tooling |
| 7 | [Hierarchical pathfinding](#7-hierarchical-pathfinding) | M | Graph algorithms on the navmesh, clustering, caching |
| 8 | [Declarative system scheduler](#8-declarative-system-scheduler) | M | Dependency graphs, the job system, data races |
| 9 | [Tracy profiler integration](#9-tracy-profiler-integration) | S | FFM to a C API, GPU timestamp queries |
| 10 | [BC6H HDR textures](#10-bc6h-hdr-textures) | S | Texture compression, HDR formats, KTX2 |
| 11 | [LZ4 codec](#11-lz4-codec) | S | Compression trade-offs, FFM, benchmarking |
| 12 | [Chase-Lev work stealing](#12-chase-lev-work-stealing) | M | Lock-free concurrency, the Java memory model |
| 13 | [GPU validation on real hardware](#13-gpu-validation-on-real-hardware) | S | GPU profiling, vendor differences (needs NVIDIA, AMD and Intel GPUs) |
| 14 | [Value records and the Vector API](#14-value-records-and-the-vector-api) | S | Valhalla and SIMD in Java (waits for the JDK features to become final) |

[Warm-up tasks](#warm-up-tasks) are small real fixes, a good way back into the code before a
milestone. [Other ideas](#other-ideas-not-in-the-backlog-yet) lists a few rendering topics that are
not in the backlog yet but teach a lot of OpenGL.

**For learning math and OpenGL**, this order works well:
1. **Animation part 2** (option 2): IK is the best math workout left in the project.
2. **Game UI** (option 1): 2D rendering, text and batching from scratch.
3. **VFX** (option 3): compute shaders and GPU-driven drawing.

Rules, saves and dialogue come after that, when you want a playable game rather than an engine.

---

## 1. Game UI and debug UI

**What.** The game needs screens: inventory, character sheet, spellbook, dialogue window, party
portraits, tooltips and a combat log. The developers need a debug UI to tweak values live and to
inspect entities. Today there is only the HUD overlay (`render/debug/Overlay`, a 5×7 bitmap font).

**Approach.**
- A new `ui` module with a retained-mode game UI:
  - widgets as SoA arrays: rectangles, nine-slice images, text, a scroll list, a drag source and
    drop target;
  - a layout pass (anchors plus simple flex rows and columns);
  - an input router that sits in front of the world's picking, so a click on a button never moves
    the party.
- Text uses signed-distance-field (SDF) or MSDF fonts baked by the cooker, so it stays sharp at
  every scale.
- Rendering is one batched pass after post-processing:
  - quads go into a mapped ring buffer and draw with one `glMultiDrawElementsIndirect`;
  - an orthographic projection maps pixels;
  - clipping uses scissor rectangles, or a clip rectangle per quad in the shader.
- Debug UI: Dear ImGui through **cimgui** (its C API), built into the natives and bound with
  jextract. A Java GL backend renders its draw lists into the same pass. It is behind a flag and
  never in release builds.

**Draft tasks.**
- M13-01 Design: DESIGN §16 → §18 `ui`, decisions, tasks.
- M13-02 Cooker: SDF/MSDF font atlas from TTF (glyph metrics, kerning) into a cooked `.jfont`.
- M13-03 `ui` renderer: quad batch, nine-slice, SDF text shader, scissor, an allocation test.
- M13-04 Widgets and layout: SoA widget tree, anchors, rows and columns, text wrapping.
- M13-05 Input routing and focus: hover, press, drag and drop, keyboard focus, gamepad navigation.
- M13-06 Demo screens: party portraits with health, an inventory grid with drag and drop, a
  tooltip, a combat log.
- M13-07 Dear ImGui: cimgui in the natives build, jextract bindings, GL backend, an entity
  inspector in the demo (F2).
- M13-08 Docs and tests.

**What you learn.**
- OpenGL: orthographic projection; vertex layouts for 2D; premultiplied-alpha blending; scissor
  test; texture atlases; SDF rendering (`smoothstep` on a distance with `fwidth` for
  antialiasing); instanced quads.
- Math: rectangles, anchoring and layout, the screen → UI → world transforms.
- FFM: calling a C++ library through a C shim, callbacks (upcalls), and keeping them off hot paths.

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

## The tutorial format

Each tutorial is written to `docs/tutorials/m13-<topic>.md` (later milestones use their own
number) and contains:

1. **Goal and result.** What you build and what the demo shows at the end, with a screenshot or a
   test you can run.
2. **Background.** The theory, with derivations, diagrams and small numeric examples, so the code
   makes sense before you type it. It links to the learning guide for what you already met.
3. **Steps.** Each step:
   - names the files to create or change;
   - explains the design;
   - gives the code with comments on the lines that matter;
   - ends with a **check**: a unit test, a GPU test, or a run of the demo, and what you should see.
4. **Pitfalls.** The usual mistakes: coordinate spaces, handedness, matrix order, GL state that
   leaks between passes, allocation in hot paths.
5. **Performance.** The allocation test and, where it matters, a JMH benchmark (CLAUDE.md
   non-negotiable 6).
6. **Exercises.** Smaller extensions to try alone, with hints.
7. **Finish.** Updating DESIGN.md and TASKS.md, and opening the PR, the way the earlier
   milestones did.
