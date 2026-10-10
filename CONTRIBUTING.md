# Contributing to Jeddy

Read [docs/DESIGN.md](docs/DESIGN.md) for the architecture and [docs/TASKS.md](docs/TASKS.md) for the plan.
Pick a task by ID (for example `M1-07`), check that its dependencies are ticked, and work on a branch named after it.

## Requirements
- JDK 27. If Gradle runs on an older JDK, the foojay resolver downloads 27 automatically as the toolchain.
- Nothing else. The Gradle wrapper (9.8.1) downloads itself.

## Build
```
./gradlew build                 # compile and test every module
./gradlew :core:test            # one module (short project paths, see DESIGN §14)
./gradlew :bench:jmh -Pjmh.includes=Baseline
./gradlew build -Pjeddy.gpuTests=true   # include @Tag("gpu") tests (needs a GL 4.6 driver)
```
On Windows use `gradlew.bat`.

## Rules for runtime code (summary of DESIGN §4)
1. **Zero allocation per frame** in steady state. Hot paths are the game loop, render loop, job kernels and event pump.
   No boxing, streams, iterators over collections, capturing lambdas, varargs, string building or per-element objects there.
2. **Data-oriented:** struct-of-arrays on primitive arrays, generational `int` handles, `long[]` bit sets, 64-bit `StrId`s instead of strings.
3. **Pools** only for objects that must exist (native-backed, expensive), and thread-confined.
4. **Threads:** the SDL main thread owns events, the window and all GL calls. The game thread sends main-thread work through the command queue. CPU work runs on the job system, never on virtual threads or the common pool.
5. **FFM:** `static final` downcall handles, `Linker.Option.critical` only from the allow-list, no `Arena.ofAuto()`, no Java upcalls on native threads we don't own.
6. **Stable Java only:** no `--enable-preview`, no incubator modules.
7. **Prove it:** an `@NoAllocation` test for hot APIs and a JMH benchmark in `bench/` for performance-sensitive code.

## Code conventions
- Root package `dev.jeddy.<module>`. Raw native bindings live in `*.ffi` packages with qualified exports only.
- Every public type's Javadoc says which thread may call it.
- `javac` runs with `-Xlint:all,-module,-processing -Werror`: fix warnings, don't suppress them without a comment.
- Formatting follows `.editorconfig` (4 spaces, 120 columns, LF). A Spotless check will be added and enforced in CI.
- All dependency versions live in `gradle/libs.versions.toml`. Runtime modules have no third-party dependencies.
- A module exports a package only once it contains a public type (javac rejects exporting a package with no types).

## Changing the design
If a change deviates from DESIGN.md, update the relevant section and add a row to its §17 decision log in the same pull request.
