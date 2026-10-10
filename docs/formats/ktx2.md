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
- 2D textures, arrays (`layerCount > 0`) and cube maps (`faceCount = 6`); `levelCount` 0 is read
  as 1.
- Key/value data is readable (`KTXorientation`, `KTXwriter`); the data format descriptor is not
  interpreted (the format comes from `vkFormat`).

Level `i` (level 0 is the largest) is decoded with `decodeLevel(i, dst, offset, decompressor)`:
a copy, or a zstd decompression straight into the destination (a staging ring or a mapped GPU
upload buffer), allocating nothing. The reader checks that every level lies inside the file and
that its decoded size matches the format, dimensions, layers and faces.
