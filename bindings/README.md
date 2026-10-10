# Native bindings inputs

Inputs for the jextract bindings (DESIGN §6.4). The generated Java lives in each module's
`src/ffi/java` and is committed, so a normal build never needs jextract, libclang or these headers.

| Library | Headers | Include list | Generated package | Module |
|---|---|---|---|---|
| SDL3 3.4.18 | `sdl3/include` | `sdl3.includes` | `dev.jeddy.sdl.ffi` (`SDL3`) | `sdl` |
| SDL3_mixer 3.2.4 | `sdl3_mixer/include` | `sdl3_mixer.includes` | `dev.jeddy.sdl.mixer.ffi` (`SDL3Mixer`) | `sdl` |
| zstd 1.5.7 | `zstd/include` | `zstd.includes` | `dev.jeddy.compress.ffi` | `compress` |

The headers are copied from the commits pinned in `natives/versions.json` (public headers only;
SDL's OpenGL, EGL, Vulkan, test and `main` implementation headers are left out). Each folder keeps
the library's license. When a library is bumped in `natives/versions.json`, copy its headers here
from the same commit and regenerate.

## Regenerating

```
./gradlew :sdl:regenerateBindings -Pjextract.home=/path/to/jextract
```

The task runs jextract with the include list, replaces the package under `src/ffi/java` and then
runs `BindingsPostProcessor` (`build-logic`), which makes the output portable across the x64
targets (decision D22). Run the module's tests afterwards: `SdlBindingsTest` initializes every
generated class against the shipped natives and checks the layouts the engine depends on.

The committed sources come from the official jextract build `22-jextract+5-33`. The `Bindings`
workflow regenerates them with that build on Windows and Linux for every change to these inputs,
fails if the two platforms disagree or if the committed sources are stale, and on a branch named
`regen/<anything>` commits the regenerated sources itself, so no local jextract is needed. Other
jextract versions produce equivalent but textually different code.

Include lists started from `jextract --dump-includes` and were trimmed by hand. Keep them that way:
add a symbol when wrapper code needs it, never bind callbacks SDL can call on its own threads
(DESIGN §4.4), varargs functions or opaque handle structs.

**jextract built against a distribution libclang** (for example Ubuntu's `libclang-18`) sees two
copies of clang's builtin headers, its own and libclang's, and `<stdint.h>` then defines nothing
(`unknown type name 'int8_t'`). Empty jextract's `runtime/conf/jextract` folder, or use an official
jextract build, which bundles its libclang.
