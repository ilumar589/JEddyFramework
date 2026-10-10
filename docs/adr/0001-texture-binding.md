# ADR 0001: How materials reach their textures

- Status: **Proposed**. The decision waits for measurements on NVIDIA, AMD and Intel hardware
  (TASKS M5-11).
- Date: 2026-10-10
- Context: DESIGN §9.2 (textures and materials), D8 (BCn only).

## Context

The renderer draws a pass with a few `glMultiDrawElementsIndirect` calls (M5-08). Every instance
record carries a material index, and the material row in a storage buffer must lead the shader to
the material's textures. Rebinding textures between draws would split the multi-draws back into
one draw per material, which is the cost the submission design exists to avoid.

Two GL 4.6 options keep one multi-draw per program:

1. **Texture arrays.** Textures are grouped into `GL_TEXTURE_2D_ARRAY`s by (format, size), for
   example BC7 sRGB 1024², BC5 1024², BC7 512². A material row stores (array, layer) pairs; the
   arrays are bound to fixed units once per pass.
2. **`ARB_bindless_texture`.** Every texture gets a 64-bit handle, made resident once; the material
   row stores handles and the shader constructs samplers from them.

## Options compared

| | Texture arrays | Bindless |
|---|---|---|
| Availability | Core GL 4.6, every driver | Extension: NVIDIA, AMD (radeonsi, proprietary); not Intel's Windows driver for many generations, not llvmpipe (CI) |
| Draw submission | One multi-draw per program; arrays bound per pass | One multi-draw per program; nothing bound |
| Memory | Every layer of an array has the array's size and mip count; mixed sizes need more arrays or padding | Each texture its own size |
| Streaming | Upload a layer into a preallocated array; growing an array means a copy | Create, upload, make resident |
| Shader | `texture(arrays[k], vec3(uv, layer))`; non-uniform array selection needs care | `texture(sampler2D(handle), uv)`; handles must be dynamically uniform per draw on some hardware unless `NV_gpu_shader5`-class support |
| Residency limits | None beyond memory | Driver limits on resident handles (large, but finite) |
| Tooling | RenderDoc and Nsight show arrays | Capture support varies |

## Measurements

`bench/src/main/java/dev/jeddy/bench/TextureBindingBench.java` draws 256 materials (one 128²
texture each) per frame three ways, ending each frame with `glFinish` so driver and GPU time
count: rebinding per draw (baseline), one texture array, and bindless handles.

| Driver | bindPerDraw (µs) | textureArray (µs) | bindless (µs) |
|---|---|---|---|
| Mesa llvmpipe 25.2 (CI, software) | 3525 | 2613 | not supported |
| NVIDIA | pending | pending | pending |
| AMD | pending | pending | pending |
| Intel | pending | pending | pending |

Run on hardware with `./gradlew :bench:jmh -Pjmh.includes=TextureBindingBench`.

## Proposed decision

Texture arrays are the baseline everywhere (they work on every target driver and in CI).
Bindless becomes an optional fast path selected at startup by `GLCapabilities` if the hardware
numbers show a clear win, with the same material row layout behind an abstraction in the material
system (a 64-bit slot per texture holds either a handle or an (array, layer) pair).

## Consequences

- The cooker groups textures by (format, size) and pads or resizes outliers (M6-05/M6-08).
- The material system (M7) owns a small set of arrays per format and allocates layers from them.
- If bindless is adopted as a fast path, the renderer needs a residency manager (make handles
  resident when a material loads, non-resident when it unloads).
