# Jeddy Framework

A Java 27 framework for building 3D isometric RPGs in the style of *Pathfinder: Wrath of the Righteous*.

- **Performance first:** data-oriented (struct-of-arrays on primitive arrays), a job system, zero allocation per frame in steady state.
- **Native through FFM only:** SDL3, SDL3_mixer, OpenGL 4.6 core, zstd, libktx and meshoptimizer through `java.lang.foreign`. No JNI.
- **Targets:** Windows x64 and Linux x64.
- **Build:** Gradle 9.8.1 multi-module, one JPMS module per subsystem.

![The iso demo: a party in a KayKit crypt with fog of war and a fireball](docs/images/iso-demo.png)

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
| [scene](modules/scene/README.md) | Struct-of-arrays transforms and hierarchy |
| [app](modules/app/README.md) | The two-thread game loop, `World` and `WorldRenderer` |

Tools: the asset cooker (`tools/cooker`, also the `dev.jeddy.assets` Gradle plugin) and the Javadoc site (`tools/site`).

## Getting started
- [Getting started](docs/getting-started.md): a game project on the `dev.jeddy.game` plugin, from `samples/game-template` to a packaged build.
- `./gradlew build` builds and tests everything; `./gradlew :iso-demo:run` cooks the demo's assets and runs it (`--perf 600` for the performance capture).
- `./gradlew :iso-demo:distImage` builds a runnable image with its own runtime and an AOT cache; `:iso-demo:jpackage` makes an app image or installer.

## Documents
- [Architecture and design](docs/DESIGN.md)
- [Task plan](docs/TASKS.md)
- [M10 performance capture and fix list](docs/perf/m10.md)
- [Agent guide](CLAUDE.md)

## Status
Pre-alpha. Milestones M0–M11 are implemented: the runtime modules, the cooker, the iso demo with its
performance capture, packaging, publishing and the docs. The CPU-side budgets pass in CI; the GPU
and main-thread budgets still need a run on the reference hardware (docs/perf/m10.md, O1–O2).
