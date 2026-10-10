# KTX2 profile

Textures are standard [KTX 2.0](https://registry.khronos.org/KTX/specs/2.0/ktxspec.v2.html)
files written by libktx in the cooker (DESIGN §10.1, §10.3). `dev.jeddy.asset.format.Ktx2` reads
the subset the engine produces:

- `vkFormat`: BC7 (sRGB for base color and emissive, UNORM for ORM), BC5 UNORM (normal maps, Z
  reconstructed in the shader), BC4 UNORM (masks), BC6H (HDR, later) and uncompressed
  `R8G8B8A8`, `R16G16B16A16_SFLOAT`, `R32G32B32A32_SFLOAT` (HDR environments, LUTs). Other formats
  load but are not size-checked.
- `supercompressionScheme`: 0 (none) or 2 (Zstandard, one frame per level). BasisLZ and zlib are
  rejected: the cooker transcodes UASTC to BCn before writing, so there is no runtime transcoding
  (D8).
- 2D textures, arrays (`layerCount > 0`), cube maps (`faceCount = 6`) and 3D textures
  (`pixelDepth > 1`); `levelCount` 0 is read as 1.
- Key/value data is readable as text (`keyValue`: `KTXorientation`, `KTXwriter`) or bytes
  (`keyValueBytes`); the data format descriptor is not interpreted (the format comes from
  `vkFormat`).
- Cooked HDR environments are RGBA16F cube maps (faces in GL order) whose mip `i` is
  GGX-prefiltered for `roughness = i / (levels - 1)`. Their key `jeddy.sh9` holds 27 little-endian
  `f32`: the irradiance `E(n) = Σ c_i Y_i(n)` (already convolved with the clamped cosine) for the
  real SH basis `Y00, Y1-1, Y10, Y11, Y2-2, Y2-1, Y20, Y21, Y22` (constants 0.282095, 0.488603 y,
  0.488603 z, 0.488603 x, 1.092548 xy, 1.092548 yz, 0.315392 (3z² − 1), 1.092548 xz, 0.546274 (x²
  − y²)), three floats (RGB) per coefficient. Diffuse lighting is `albedo / π · E(n)`.
- Color-grading LUTs cooked from `.cube` files are RGBA16F 3D textures of `n`³ texels (red along
  x, green along y, blue along z), indexed by the sRGB-encoded display color and holding the
  graded encoded color; the key `jeddy.lut` holds the source's `TITLE` (TASKS M7-06).
- `engine/brdf_lut.ktx2` is RG16F 128×128: scale and bias of F0 for `N·V` along x and roughness
  along y (row 0 first), from the split-sum approximation with Smith-GGX `k = α / 2`.

Level `i` (level 0 is the largest) is decoded with `decodeLevel(i, dst, offset, decompressor)`:
a copy, or a zstd decompression straight into the destination (a staging ring or a mapped GPU
upload buffer), allocating nothing. The reader checks that every level lies inside the file and
that its decoded size matches the format, dimensions, layers and faces.
