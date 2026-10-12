# Running Jeddy: tests, the demo, the editor and your game

Everything the repository can run, with the exact commands. The commands are for a shell in the
repository root; on Windows use `gradlew.bat` instead of `./gradlew` and the same arguments. Gradle
provisions JDK 27 by itself the first time, so the only things to install are a JDK of any recent
version to bootstrap the wrapper and, for anything that draws, an OpenGL 4.6 driver.

Two shell notes. IntelliJ shows a run icon next to every `./gradlew` line in this file and
runs it as a Gradle task, not through a shell, so the explanations in the command blocks sit on
their own `#` lines rather than after the command (a trailing comment would become a task named
`#`). And PowerShell splits `-Pjeddy.gpuTests=only` at the dot into two arguments, so the `-P`
options below are quoted; the quotes are harmless in bash.

Related: [Getting started](getting-started.md) builds a game project of your own on the framework;
the [learning guide](learn/README.md) explains how the engine works. This page is about operating
what is already here.

Contents: [first build](#1-the-first-build) · [tests](#2-tests) · [benchmarks](#3-benchmarks) ·
[the iso demo](#4-the-iso-demo-the-game) · [the level editor](#5-the-level-editor) ·
[the other samples](#6-the-other-samples) · [the cooker](#7-the-asset-cooker) ·
[what CI runs](#8-what-ci-runs) · [troubleshooting](#9-troubleshooting)

## 1. The first build

```
git clone https://github.com/ilumar589/JEddyFramework
cd JEddyFramework
./gradlew build
```

`build` compiles every module, runs every test that needs no GPU, and checks formatting. The first
run downloads Gradle 9.8.1, a JDK 27 toolchain and the prebuilt native libraries (SDL3, SDL3_mixer,
zstd, libktx, meshoptimizer) from the `natives-v<N>` GitHub release named in `gradle.properties`.
After that, everything is offline except for new dependencies.

Useful variations:

| Command | What it does |
|---|---|
| `./gradlew build -x test` | Compile and check formatting only |
| `./gradlew spotlessApply` | Reformat the Java sources (google-java-format, AOSP style). `check` fails on unformatted code, so run this before pushing |
| `./gradlew :physics:build` | One module. Short paths (`:core`, `:render`, `:cooker`, `:editor-tool`, `:iso-demo`...) map to the folders under `modules/`, `tools/` and `samples/` |
| `./gradlew build "-Pjeddy.natives.dir=natives/out/linux-x64"` | Use natives you built yourself with `python natives/build.py` (needs CMake and a C compiler) instead of the release |
| `./gradlew :sdl:regenerateBindings` | Regenerate the jextract bindings (needs jextract and libclang); the GL bindings are generated at build time from `bindings/gl/gl.xml` |

## 2. Tests

The tests are JUnit 5, run by Gradle. Reports land in `<module>/build/reports/tests/test/index.html`
(for example `modules/rules/build/reports/tests/test/index.html`), with the stdout of each test.

### Everyday commands

| Command | What it runs |
|---|---|
| `./gradlew test` | Every non-GPU test of every module |
| `./gradlew :rules:test` | One module |
| `./gradlew :rules:test --tests "dev.jeddy.rules.RulesTest"` | One class |
| `./gradlew :rules:test --tests "dev.jeddy.rules.RulesTest.aConfirmedThreatMultipliesTheDamage"` | One method |
| `./gradlew :rules:test --tests "*Encounter*"` | A pattern |
| `./gradlew :rules:test --rerun` | Run again even when Gradle thinks nothing changed |
| `./gradlew test --continue` | Keep going through the other modules after a failure |

Gradle only reruns a module's tests when its inputs changed, so a second `./gradlew test` with no
edits reports `UP-TO-DATE` in a few seconds; that is expected, not a skipped run.

### The kinds of tests you will meet

- **Unit tests** with hand-computed expectations: the math, the collections, the rules (`RulesTest`
  checks every dice roll against a second random generator seeded the same way).
- **Allocation tests**: `Allocations.assertNoAllocation("what", () -> ...)` from the `testing`
  module runs a body until the JIT has compiled it and then asserts it allocates zero bytes
  (DESIGN §4). Every hot path has one. They need a quiet machine: a very loaded laptop can make the
  JIT lag and the test retry for longer, and in the worst case fail with "allocates: at best N bytes
  per call". Rerun it alone before suspecting the code.
- **The app-loop soak**: `JeddyAppTest` runs the two-thread loop under SDL's dummy driver and checks
  both threads allocate nothing per frame. CI runs it for 4 seconds; locally you can ask for the
  full ten minutes the acceptance criteria want:

  ```
  ./gradlew :app:test "-Pjeddy.soakSeconds=600"
  ```

- **Content tests**: the rules and editor tests build the demo's content from
  `samples/iso-demo/assets/rules/*.json` through the cooker's importer, so editing that content
  changes what those tests assert. They skip themselves (not fail) when the sample is missing.
- **GPU tests** are tagged `gpu` and excluded by default, because they need a window and a GL 4.6
  context. See next.

### GPU tests

```
# the normal tests plus the GPU ones
./gradlew test "-Pjeddy.gpuTests=true"
# just the GPU ones
./gradlew test "-Pjeddy.gpuTests=only"
```

They open real windows for a moment. On a Linux machine without a display (a server, WSL without
WSLg, CI), run them under Xvfb with Mesa's software renderer, exactly as CI does:

```
sudo apt-get install -y xvfb libgl1-mesa-dri libglx-mesa0 libegl-mesa0
xvfb-run -a -s "-screen 0 1920x1080x24" ./gradlew test "-Pjeddy.gpuTests=only"
```

If Mesa reports an OpenGL version below 4.6, add `LIBGL_ALWAYS_SOFTWARE=1
MESA_GL_VERSION_OVERRIDE=4.6 MESA_GLSL_VERSION_OVERRIDE=460` in front of the command.

### Local scratch runs without Gradle

For fast edit-test loops, any JDK 27 can compile and run the modules directly (`javac` on the
module sources, `java -p` with the natives directory in `-Djeddy.natives.dir`), which is how the
agent sessions iterate. Gradle remains the source of truth: before a commit, run the Gradle tests
of the modules you touched, since Gradle adds `-Werror`, the formatting check and the module-path
rules the quick loop skips.

## 3. Benchmarks

JMH benchmarks live in `bench/`. They are not part of `build`:

```
./gradlew :bench:jmh "-Pjmh.includes=IntIntMap"
./gradlew :bench:jmh '-Pjmh.includes=.*Crowd.*' '-Pjmh.args=-f 1 -wi 3 -i 5'
```

`jmh.includes` is a regular expression on the benchmark class and method names; `jmh.args` passes
JMH's own options (forks, warm-up and measurement iterations). Results print to the console and go
to `bench/build/reports/jmh/results.json`. CI runs a one-fork smoke of every benchmark and keeps a baseline;
real numbers need a quiet machine and the default forks.

## 4. The iso demo (the game)

The demo is a playable slice: the six-adventurer party in a crypt full of skeletons, fights
resolved by the d20 rules in real time with pause or turn by turn, fireballs, the UI, and the level
editor over the running game.

### Run it

```
./gradlew :iso-demo:run
./gradlew :iso-demo:run --args="--size 1920x1080 --no-vsync"
# with the GPU driver's messages in the log
./gradlew :iso-demo:run "-Pjeddy.jvmArgs=-Djeddy.gl.debug=true"
```

`run` first cooks the sample's assets (`samples/iso-demo/assets` → `samples/iso-demo/build/cooked`)
and packs them into `samples/iso-demo/build/paks`, then starts the game with the framework's JVM
flags. The first cook takes a minute or two (glTF models, texture compression, the navmesh bake);
later cooks only redo what changed. Install `glslangValidator` (`glslang-tools` on Debian/Ubuntu)
if you want the cook to compile every shader permutation up front.

### Controls

| Key or mouse | Action |
|---|---|
| Left click | Select a party member; on the ground, move the selection there |
| Left drag | Box-select members |
| Right click | Move the selection |
| 1–6, Tab | Select a member, everyone (Shift adds to the selection) |
| W A S D, arrows | Pan the camera; mouse wheel zooms; Q / E or middle-drag rotates; C centers on the selection |
| G | Cycle formations (wedge, box, line, column, circle) |
| F | Fireball: click where to cast, right click cancels; Shift keeps casting |
| T | Switch between real time and turn-based combat (the fight keeps its state) |
| Enter | End the current turn (turn-based mode) |
| Space | Pause and resume |
| I | Inventory |
| F3 | Profiler overlay |
| F10 | The level editor over the running game |
| F12 | Screenshot |
| Esc | Cancel, then the menu |

In real time, party members swing at adjacent skeletons on their own when the rules give them an
attack this round (one full attack per six-second round); casters only cast on order. In
turn-based mode the initiative bar at the top shows whose turn it is; on a party member's turn,
click a skeleton in reach to attack, click the ground inside the highlighted range to move (the
budget is one move action, a second one costs the standard action), and press Enter or the
button to end the turn. Skeletons take their turns by themselves. Everything that happens is
written to the combat log at the bottom right with the rolls.

### Options

`./gradlew :iso-demo:run --args="--help"` prints the list. The ones you will use:

| Option | Meaning |
|---|---|
| `--size WxH` | Window size (default 1600x900) |
| `--no-vsync` | Render as fast as possible |
| `--no-audio` | Do not open an audio device (machines without one; headless runs) |
| `--enemies N` | Number of skeletons (default 64) |
| `--seed N` | Seed of everything random, including the dice: the same seed replays the same fight |
| `--turn-based` | Start in turn-based mode |
| `--echo-combat` | Also print the combat log to the console (scripted and headless runs) |
| `--auto-turns` | Scripted play: the party marches on the skeletons and ends its own turns; for smoke tests |
| `--profiler` | Start with the profiler overlay on |
| `--show-ui` | Start with the inventory and the menu open (UI screenshots) |
| `--screenshot FILE --screenshot-at S` | Write a PNG S seconds after loading (default 3) |
| `--quit-after SECONDS` | Quit that long after loading |
| `--editor`, `--editor-command CMD` | Start with the editor open and run console commands (repeatable) |
| `--level-source FILE` | The `.jlevel` the editor saves (default: the sample's `assets/levels/crypt.jlevel`) |
| `--assets DIR` | Play from other paks or a loose cooked tree (default `build/paks`, then `build/cooked`) |
| `--headless` | No window, no GPU: the game thread alone, for servers and the perf scene |

Extra JVM flags go through a property, not `--args`: `"-Pjeddy.jvmArgs=-Xlog:gc*:file=build/gc.log"`.

### The performance scene

`--perf SECONDS` plays a scripted scene (the party tours the crypt, fireballs every 1.5 s, 294
skeletons, 16 000 props, 256 lights by default), measures for that long after a warm-up, writes a
report and quits. This is the check behind the "0 bytes per frame" rule:

```
./gradlew :iso-demo:run --args="--headless --perf 120 --warmup 30 --alloc-probe --report build/perf --fail-on alloc,flags"
```

The report is `samples/iso-demo/build/perf/perf.md` (readable) and `perf.json` (the numbers):
game-thread time per tick, allocation per frame with the JIT's one-time class resolution separated
from real allocation (`--alloc-probe` attributes the rest to systems), and the budgets. `--fail-on`
lists the budgets that make the process exit with code 4: `game`, `animation`, `main`, `gpu`,
`alloc`, `flags`, `gc`, `load`. Drop `--headless` to include the main thread and the GPU on a real
machine; CI runs headless. The perf scene keeps the party invulnerable and the caster's spell
slots unlimited, so the fight does not change what is measured.

### Package it

```
# build/dist/iso-demo: a jlink runtime, launchers, paks, AOT cache
./gradlew :iso-demo:distImage
# the same, zipped under build/distributions
./gradlew :iso-demo:distImageZip
# an app image; "-Pjeddy.package.type=deb|rpm|msi|exe" for installers
./gradlew :iso-demo:jpackage
```

The image runs without a JDK: `samples/iso-demo/build/dist/iso-demo/bin/iso-demo` (or
`iso-demo.bat`), with the same `--args` options given directly. Images are per platform. The
packaged launchers accept JVM flags through the `JDK_JAVA_OPTIONS` environment variable, for example
`-Djeddy.app.quitAfterFrames=60` for a smoke run. `"-Pjeddy.aot=false"` skips the AOT training run
(a headless play of a few seconds at build time).

### Smoke runs and screenshots

The runs CI does, usable locally to check a change end to end:

```
# twenty seconds of play with the profiler, one screenshot
./gradlew :iso-demo:run --args="--size 960x540 --quit-after 20 --screenshot build/screenshots/ci.png --screenshot-at 8 --profiler"

# a scripted turn-based fight, with the rolls in the console
./gradlew :iso-demo:run --args="--no-audio --turn-based --auto-turns --echo-combat --quit-after 60"
```

On a Linux box without a display, put `xvfb-run -a -s "-screen 0 1280x720x24"` in front and set
the Mesa variables from the GPU-tests section.

## 5. The level editor

### Inside the demo

Press **F10** while playing. The editor's panels dock around the view (Outliner, Assets, Inspector,
Settings, Rules, Console, History; the View menu shows and hides them), the game keeps simulating
underneath, and every change goes into the running world at once. **Ctrl+S** saves the level to the
`.jlevel` named by `--level-source`; the demo's cook then picks it up next time.

| Key | Editor action |
|---|---|
| Left click, drag | Select, box select; Shift adds |
| W / E / R | Move, rotate, scale gizmo |
| X | Toggle snapping (Ctrl inverts it while dragging) |
| G | Grid |
| F | Frame the selection |
| Ctrl+Z / Ctrl+Y | Undo, redo |
| Ctrl+D | Duplicate |
| Ctrl+A | Select all |
| Del | Delete |
| Esc | Cancel a gizmo drag, otherwise deselect |

The console (bottom panel) takes commands: `help` lists them — `select <id>...`, `deselect`,
`delete`, `goto <id>`, `save [path]`, `dump` (the level as JSON next to it), `snap <step>`,
`stats`, `panel <name> [left|right|bottom|hidden]`, `rules <kind> [id]`.

The **Rules** panel edits the game's rules content (`assets/rules/*.json`: conditions, ancestries,
classes, feats, spells, items, monsters, characters). Pick a kind, filter and select a definition,
edit it in the property grid (every change is undoable), then **Save**: the files are rewritten,
the watcher re-cooks them and the game rebuilds its creatures from the new content.

### Standalone

```
./gradlew :editor-tool:run --args="--project samples/iso-demo/assets --level levels/crypt.jlevel --shader-include modules/render/src/main/resources/dev/jeddy/render/shaders"
```

The tool cooks the project into `<project>/../build/cooked` (`--cooked DIR` to choose, `--no-cook`
to skip), watches the source tree and re-cooks what you save, and hot-reloads the cooked assets the
level uses. `--size WxH`, `--frames N`, `--screenshot FILE` and `--command CMD` serve smoke runs:

```
./gradlew :editor-tool:run --args="--project samples/iso-demo/assets --level levels/crypt.jlevel --shader-include modules/render/src/main/resources/dev/jeddy/render/shaders --size 1280x720 --frames 30 --screenshot build/editor.png --command \"select 150 151\""
```

## 6. The other samples

| Sample | Run | Notes |
|---|---|---|
| `samples/hello-triangle` | `./gradlew :hello-triangle:run` | A window, a GL 4.6 context, one triangle: the smallest check that the platform layer works. `JEDDY_SAMPLE_QUIT_AFTER=3` (environment) quits after three seconds |
| `samples/game-template` | `./gradlew -p samples/game-template run` | The starting point of a game of your own (see [Getting started](getting-started.md)): it is its own Gradle build that includes the framework from source, so it runs through `-p` rather than a `:` path. `build distImage` packages it the same way as the demo |

## 7. The asset cooker

`run` tasks cook for you; the cooker is also a command line for cooking a tree by hand, watching
it, or packing:

```
./gradlew :cooker:run --args="cook --source samples/iso-demo/assets --out build/cooked --cache build/cook-cache --report build/cook-report --shader-include modules/render/src/main/resources/dev/jeddy/render/shaders"
./gradlew :cooker:run --args="cook --source samples/iso-demo/assets --out build/cooked --watch"
./gradlew :cooker:run --args="pak --cooked build/cooked --out build/paks --layout samples/iso-demo/paks.json"
```

`--force` recooks everything, `--prune` deletes outputs whose sources are gone, `--threads N`
sets the parallelism. The report directory gets a `report.json` with what was cooked, cached or
failed and why. In a game project, `./gradlew cookAssets packAssets` are the same two steps as
Gradle tasks of the `dev.jeddy.assets` plugin.

## 8. What CI runs

Every push and pull request runs `.github/workflows/ci.yml`:

| Job | What it does |
|---|---|
| Build (ubuntu, windows) | `./gradlew build`, a configuration-cache reuse check, then the sample cook twice (the second must come entirely from the cache) and the pak build |
| GPU tests (Mesa llvmpipe) | `test "-Pjeddy.gpuTests=only"` under Xvfb, the hello-triangle run, the demo for 20 s with a screenshot, the editor for 30 frames with a screenshot (the screenshots are workflow artifacts) |
| Perf scene (headless game thread) | The perf capture with `--fail-on alloc,flags`; `perf.md` goes into the job summary |
| Package (ubuntu, windows) | `distImageZip`, `jpackage` (and a `.deb` on Linux), then a cold-start measurement of the image with and without the AOT cache |
| Game template (composite build) | Builds and packages `samples/game-template` and runs its image for 60 frames |
| Benchmarks (smoke) | One fork of every JMH benchmark |

Other workflows: `nightly.yml` (every night: the full JMH suite with the GC profiler against a
kept baseline, and a ten-minute headless perf scene), `docs.yml` (the Javadoc site to GitHub
Pages on every push to `main`, once Pages is enabled), `release.yml` (a `v*` tag publishes the
modules to GitHub Packages and attaches the demo images to a GitHub release), `natives.yml`
(builds the native libraries into a `natives-v<N>` release) and `bindings.yml` (regenerates the
jextract bindings).

When a job fails, the failing test's output is attached as an annotation on the commit and the PR,
so you rarely need the raw log: open the run, and the "Annotations" box names the test and shows
its stdout and stack trace.

## 9. Troubleshooting

### Drivers seen so far

CI renders with Mesa's llvmpipe, which is permissive. Real drivers differ; what they have taught us:

| Driver | Finding | What the engine does |
|---|---|---|
| Intel Graphics, Windows, 32.0.101.8331 | `glCopyImageSubData` returns `GL_INVALID_VALUE` and `glGetCompressedTextureSubImage` `GL_INVALID_OPERATION` for the mip levels of a block-compressed texture that are smaller than a block (2x2, 1x1), which the spec and Mesa allow. Growing a texture array lost those levels and, with them, the textures loaded afterwards (black characters, no UI) | `GpuTextures` keeps a CPU copy of those levels from the upload (16 bytes per level per layer) and `copyLayers` uploads them again instead of copying or reading back (D101) |

Add a row when you run on new hardware (`-Djeddy.gl.debug=true` shows the driver's complaints).


| Symptom | What to do |
|---|---|
| `gh`, `python3` or `xvfb-run` not found | Those are only needed by the CI scripts and the headless recipes; on Windows the Gradle commands alone are enough |
| The first build cannot download the natives (a proxy, no network) | Build them locally: `python natives/build.py` (CMake, a C compiler and Git), then `"-Pjeddy.natives.dir=natives/out/<platform>"` on every Gradle command, or set it in `~/.gradle/gradle.properties` |
| Something renders wrong on your GPU (black models, missing UI) while CI's pictures are fine | Run with the driver's debug output in the log: `./gradlew :iso-demo:run "-Pjeddy.jvmArgs=-Djeddy.gl.debug=true"` (any Jeddy app takes the property; it also turns off critical linking so the callback can run). Errors and performance warnings from the driver appear as `gl:` lines; send them with the `GL ... on <GPU>` line |
| A laptop runs the game on its Intel GPU instead of the NVIDIA or AMD one (the log says `GL ... on Intel(R) Graphics`) | Windows picks the GPU per executable, and a Java program cannot ask for the fast one the way a C++ game does (by exporting `NvOptimusEnablement`), so tell Windows once: Settings → System → Display → Graphics → Add an app → browse to the `java.exe` that runs the game → Options → High performance. For `gradlew :iso-demo:run` that is the JDK 27 Gradle uses (`gradlew -q javaToolchains` prints its path; typically `%USERPROFILE%\.gradle\jdks\...\bin\java.exe` or `C:\Program Files\...\jdk-27\bin\java.exe`); for a packaged image it is `runtime\bin\java.exe` inside the image. The NVIDIA Control Panel (Manage 3D settings → Program Settings) does the same. The log line names the GPU in use, so you can check |
| "OpenGL 4.6 is required" or a black window | Update the GPU driver; on Linux check `glxinfo \| grep "core profile version"`. Mesa's llvmpipe works with the environment variables of the GPU-tests section, slowly |
| No audio device (servers, WSL, CI) | `--no-audio`, or `SDL_AUDIO_DRIVER=dummy` in the environment |
| Running on a machine with no display at all | `--headless` for the demo and the perf scene (no window is opened), `SDL_VIDEO_DRIVER=dummy` for the app tests |
| An allocation test fails only on your machine | Rerun it alone (`--tests`), with nothing else running; if it still allocates, the stack from JFR with small TLABs shows where (`-XX:TLABSize=256 -XX:MinTLABSize=256 -XX:-ResizeTLAB`, see getting-started §9) |
| `spotlessCheck` fails | `./gradlew spotlessApply` and commit the result |
| Gradle says the configuration cache cannot be reused | Harmless locally; CI checks it to keep builds fast. `--no-configuration-cache` if a plugin misbehaves |
| The demo plays an old level after editing in the editor | The editor saves the source `.jlevel`; the next `:iso-demo:run` re-cooks it. The standalone tool re-cooks and hot-reloads by itself |
