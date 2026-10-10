# Jeddy Framework: agent guide

A Java 27 framework for 3D isometric CRPGs (Pathfinder: WotR style). Multi-module Gradle 9.8.1 build with JPMS.
All native code is called through FFM (SDL3, SDL3_mixer, OpenGL 4.6 core, zstd, libktx, meshoptimizer). Targets are Windows x64 and Linux x64.

- Architecture: `docs/DESIGN.md` (read the relevant section before touching a module)
- Work plan: `docs/TASKS.md` (task IDs like `M5-08`; tick the checkbox when done)
- Decisions: `docs/DESIGN.md` §17 and `docs/adr/`

## Non-negotiables
1. **Performance first.** Hot paths (game loop, render loop, job kernels, event pump) must allocate **0 bytes per frame**. Use SoA primitive arrays, `int` handles, thread-confined pools and pre-allocated scratch. No boxing, streams, iterators, capturing lambdas, varargs or string building in hot paths. See DESIGN §4.
2. **Stable Java only.** No `--enable-preview`, no `jdk.incubator.*`. Math value types are records written so they can become `value record` later.
3. **FFM rules.** Downcall handles live in `static final` holders. `Linker.Option.critical` only for allow-listed, extremely short calls. Never `Arena.ofAuto()` in engine code. No Java code on the audio thread.
4. **SDL main thread = render thread.** `SDL_PollEvent`, `SDL_GL_MakeCurrent`, `SDL_GL_SwapWindow`, cursor, text-input and window-mode calls, and all GL calls run on the thread that called `SDL_Init(SDL_INIT_VIDEO)`. Simulation runs on the game thread and sends main-thread work through the command queue (DESIGN §5).
5. **Module boundaries.** Follow the DESIGN §3 graph. `physics`, `scene` and `render` never depend on each other, and `app` glues them. Runtime modules have no third-party dependencies (`tools/cooker` may have some).
6. **Prove it.** Every hot-path change ships with an allocation test (`Allocations.assertNoAllocation` from `:testing`) and, where relevant, a JMH benchmark in `bench/`.

## Commands
- Build and test: `./gradlew build` (Windows: `gradlew.bat build`)
- One module: `./gradlew :physics:test` (short project paths map to `modules/<name>`, see DESIGN §14)
- Benchmarks: `./gradlew :bench:jmh -Pjmh.includes=<regex>`
- Regenerate jextract bindings (needs jextract + libclang): `./gradlew :sdl:regenerateBindings`
- GL bindings are generated at build time from `bindings/gl/gl.xml`. Don't edit generated sources.
- Format: `./gradlew spotlessApply` (google-java-format AOSP, enforced by `check`).
- Native libraries: pinned release `natives-v<N>` (see `gradle.properties`); local builds via `python natives/build.py` and `-Pjeddy.natives.dir=natives/out/<platform>`.

## Conventions
- Root package and Maven group `dev.jeddy` (`dev.jeddy.<module>`). Repo: https://github.com/ilumar589/JEddyFramework (CI is GitHub Actions). Raw bindings go in `*.ffi` packages with qualified exports only.
- Math: right-handed, Y-up, column-major `Mat4`, radians, `float`, reversed-Z; affine transforms are row-major 3×4 (12 floats).
- Javadoc on every public type states its **thread ownership** (which thread may call it).
- All versions go in `gradle/libs.versions.toml`. Module build scripts only apply a `jeddy.*` convention plugin and declare dependencies.
- When you deviate from DESIGN.md, update it (§17) in the same change.
