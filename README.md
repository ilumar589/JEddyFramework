# Jeddy Framework

A Java 27 framework for building 3D isometric RPGs in the style of *Pathfinder: Wrath of the Righteous*.

- **Performance first:** data-oriented (struct-of-arrays on primitive arrays), a job system, zero allocation per frame in steady state.
- **Native through FFM only:** SDL3, SDL3_mixer, OpenGL 4.6 core, zstd, libktx and meshoptimizer through `java.lang.foreign`. No JNI.
- **Targets:** Windows x64 and Linux x64.
- **Build:** Gradle 9.8.1 multi-module, one JPMS module per subsystem.

![The iso demo: the animated party in a KayKit crypt, weapons in hand](docs/images/iso-demo.png)

## Modules
| Module | What it does |
|---|---|
| [core](modules/core/README.md) | Collections on primitives, pools, the job system, lock-free rings, logging, natives loading |
| [math](modules/math/README.md) | Vectors, matrices, quaternions, the isometric camera; batch kernels over float arrays |
| [compress](modules/compress/README.md) | zstd through FFM, with dictionaries |
| [sdl](modules/sdl/README.md), [gl](modules/gl/README.md) | Generated SDL3, SDL3_mixer and OpenGL 4.6 bindings |
| [window](modules/window/README.md) | Windows, GL contexts, display modes, the main-thread command queue |
| [input](modules/input/README.md) | Event pump, keyboard, mouse and gamepads, action maps, CRPG gestures |
| [asset](modules/asset/README.md) | Virtual file system, `.jpak` archives, async loading, hot reload |
| [render](modules/render/README.md) | Forward+ PBR on GL 4.6: shadows, decals, particles, fog of war, post-processing |
| [audio](modules/audio/README.md) | Voices, buses, 3D sound, music and ambience on SDL3_mixer |
| [physics](modules/physics/README.md) | Collision queries, navmesh baking and pathfinding, crowds, visibility and turn-based movement |
| [animation](modules/animation/README.md) | Skeletons, clips baked at load time, blended and crossfaded instances evaluated on jobs, GPU skinning palettes |
| [ui](modules/ui/README.md) | Immediate-mode interface: end-of-frame flex layout, signed-distance-field text, widgets, a draw list the renderer draws in one call ([screenshot](docs/images/iso-demo-m13-ui.jpg)) |
| [scene](modules/scene/README.md) | Struct-of-arrays transforms and hierarchy |
| [app](modules/app/README.md) | The two-thread game loop, `World` and `WorldRenderer` |
| [editor](modules/editor/README.md) | The level editor as a library: `.jlevel` levels, commands with undo, picking, gizmos, docked panels built from `ui` ([screenshot](docs/images/editor-m14.jpg)) |
| [rules](modules/rules/README.md) | A d20 game-mechanics core: creatures and typed modifiers as SoA stores, checks, attacks and saves, effects as data, encounters with an action economy in turn-based or real time with pause |
| [save](modules/save/README.md) | Save games as the dynamic state only: a chunked, versioned, checksummed `.jsave` container every system writes itself into, captured between ticks and written on a background thread; quick, auto and named slots in the user's data directory |
| [components](modules/components/README.md) | Components and behaviours: `@Component` records and `@Behaviour` static methods that an annotation processor (`tools/component-processor`) turns into SoA stores, flyweight views, loops, a job-graph schedule and the game's registry; the editor edits their rows in levels |

Tools: the asset cooker (`tools/cooker`, also the `dev.jeddy.assets` Gradle plugin), the component processor (`tools/component-processor`, on every game's annotation processor path through `dev.jeddy.game`), the standalone level editor (`tools/editor`, `./gradlew :editor-tool:run --args="--project samples/iso-demo/assets --level levels/crypt.jlevel"`) and the Javadoc site (`tools/site`).

## Getting started
- [Running Jeddy](docs/running.md): how to build, run the tests and benchmarks, play the iso demo, use the editor and read what CI does.
- [Getting started](docs/getting-started.md): a game project on the `dev.jeddy.game` plugin, from `samples/game-template` to a packaged build.
- `./gradlew build` builds and tests everything; `./gradlew :iso-demo:run` cooks the demo's assets and runs it (`--perf 600` for the performance capture).
- `./gradlew :iso-demo:distImage` builds a runnable image with its own runtime and an AOT cache; `:iso-demo:jpackage` makes an app image or installer.

## Documents
- [Learning guide](docs/learn/README.md): seventeen chapters and four lessons on how the engine works
  and the 3D math and OpenGL behind it, with exercises
- [Architecture and design](docs/DESIGN.md)
- [Task plan](docs/TASKS.md) and [the options for the next milestone](docs/next-milestones.md)
- [M10 performance capture and fix list](docs/perf/m10.md)
- [Agent guide](CLAUDE.md)

## Status
Pre-alpha. Milestones M0–M15 and M22 are implemented: the runtime modules, the cooker, the iso demo
with its performance capture, packaging, publishing, skeletal animation with GPU skinning, the
immediate-mode UI, the level editor, the d20 rules, components and behaviours, save games, and the docs. Version 0.1.0 is tagged at M12. The CPU-side budgets pass in CI; the GPU
and main-thread budgets still need a run on the reference hardware (docs/perf/m10.md, O1–O2).

## License
MIT ([LICENSE](LICENSE)). Bundled native libraries and demo content keep their own licenses: see
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
