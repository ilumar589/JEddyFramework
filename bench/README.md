# Jeddy benchmarks (JMH)

Run all benchmarks, or a subset by regex:

```
./gradlew :bench:jmh
./gradlew :bench:jmh -Pjmh.includes=MathBench
./gradlew :bench:jmh -Pjmh.includes=JobBench "-Pjmh.args=-f 1 -wi 2 -i 3"
```

Results are written to `bench/build/reports/jmh/results.json`. CI runs a short smoke pass on
every push and posts the numbers as a notice annotation on the commit ("JMH results").

| Benchmark | What it measures | Task |
|---|---|---|
| `BaselineBench` | Harness floor on this machine | M1-15 |
| `CollectionsBench` | `IntIntMap` vs `HashMap<Integer,Integer>` lookups; radix sort vs `Arrays.sort` | M1-03 |
| `MathBench` | TRS composition, hierarchy pass, sphere culling for 10k items; `Mat4` multiply | M1-12 |
| `JobBench` | 1M-element `parallelFor` vs sequential (scaling target ≥ 6× on 8 cores); empty dispatch cost | M1-07 |
| `FfmBench` | FFM call overhead with C library functions: regular vs critical, `mat4` blocks from heap arrays vs off-heap | M3-05 |
| `ZstdBench` | zstd compress/decompress of 8 MiB of asset-like data at levels 1, 3, 19 through the FFM facade; CI also runs `zstd -b` on the same corpus | M4-01 |
| `AssetBench` | Pak TOC binary search (10k entries), VFS index lookup, 64 KiB zstd entry read from the mapping | M4-03, M4-04 |
| `TransformBench` | 100k-transform hierarchy update, all dirty vs 10 % of roots, sequential vs job system | M5-02 |
| `DrawBench` | Cull + sort + records + indirect commands for 20k instances, with and without the GL submit (needs GL) | M5-08 |
| `TextureBindingBench` | Spike: texture rebinding vs texture arrays vs bindless for 256 materials (needs GL; ADR 0001) | M5-11 |
| `GlBench` | `glUniform4f` regular vs critical vs generated, `glUniformMatrix4fv` heap vs off-heap, 64-byte `glNamedBufferSubData` (needs a GL 4.6 context; CI uses Xvfb + llvmpipe) | M3-05 |

### FFM numbers (M3-05)

Local run, JDK 27, x64 Linux, Mesa llvmpipe (single-thread loop, not JMH; CI posts JMH numbers):

| Call | ns |
|---|---|
| `abs` regular / critical | 16.5 / 3.6 |
| 64-byte block, off-heap regular / off-heap critical / heap critical / copy then regular | 13.9 / 10.1 / 10.5 / 18.9 |
| `glUniform4f` regular / critical / generated `GL46` | 37.0 / 33.2 / 33.5 |
| `glUniformMatrix4fv` off-heap regular / heap critical / copy then regular | 37.6 / 27.7 / 36.1 |
| `glNamedBufferSubData` 64 B | 139 |

Conclusions: critical linking saves about 13 ns per call; heap access makes `float[]` parameter
blocks as cheap as off-heap ones; the generated wrappers add nothing. The allow-list in
`bindings/gl/gl.properties` (state setters, binds, uniforms) stays as designed (DESIGN §4.4).

Vectorization of the math kernels is checked with JMH's `-prof perfasm` (needs `hsdis`), not
`-XX:+PrintCompilation`.

### Asset numbers (M4)

Local run, JDK 27, x64 Linux (hand-timed loops, not JMH; CI posts JMH numbers and the
`zstd -b` numbers for the same corpus side by side):

| Operation | Result |
|---|---|
| zstd level 1 / 3 / 19 compress, 8 MiB | 290 / 256 / 2 MB/s |
| zstd level 1 / 3 / 19 decompress, 8 MiB | 735 / 828 / 924 MB/s |
| `Pak.find` (binary search, 10k entries, shuffled) | 74 ns |
| `Vfs.size` (merged hash index) | 8.6 ns |
| `Vfs.read` of a 64 KiB zstd entry from the mapping | 18 µs (3.6 GB/s) |

### Scene and render numbers (M5)

Local runs, 2-core sandbox, Mesa llvmpipe (hand-timed, not JMH):

| Operation | Result |
|---|---|
| `TransformStore.update`, 100k, all dirty | 2.5–3.3 ms |
| `TransformStore.update`, 100k, 10 % of roots moved | 0.6–0.8 ms |
| `DrawBench.cullAndBuild`, 20k instances | 1.7 ms (warm) |
| `DrawBench.cullBuildSubmit`, 20k instances (llvmpipe does vertex work on the calling thread) | 8.6 ms |
| `TextureBindingBench`: rebind per draw / texture array (256 materials) | 3.5 / 2.6 ms |

### Cooker numbers (M6)

Local runs, 2-core sandbox (hand-timed; CI runs `TextureCookBench` on its 4-core runner):

| Operation | Result |
|---|---|
| 2048² color texture: Kaiser mips (parallel rows), sRGB encode | 0.14 s |
| 2048² color texture: whole cook (mips, UASTC level 0, BC7, zstd 10, PSNR/SSIM) | 1.8 s, 52.4 dB, SSIM 0.997 |
| Same texture at UASTC level 1 | 5.0 s encode, 53.0 dB |
| Sample assets (glTF scene, 6 textures, HDR environment, material, shaders) cold cook | 1.2 s |
| Same, served from the cooker cache | 10 ms |
