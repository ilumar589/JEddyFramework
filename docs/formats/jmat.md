# `.jmat` cooked material, version 1

A material: shading model, feature mask, PBR factors (glTF metallic-roughness plus
`KHR_materials_emissive_strength` and `KHR_texture_transform`), alpha mode and texture references.
Read and written by `dev.jeddy.asset.format.MaterialData`. Little-endian.

## Fixed part (128 bytes)

| Offset | Type | Field | Notes |
|---|---|---|---|
| 0 | u32 | magic | `0x54414D4A` (`"JMAT"` in file order) |
| 4 | u16 | version | 1 |
| 6 | u16 | flags | bit 0 double-sided, bit 1 casts shadows |
| 8 | u32 | shadingModel | 0 PBR, 1 unlit, 2 foliage |
| 12 | u32 | featureMask | shader permutation bits: 0–15 engine features (below), 16–31 custom |
| 16 | u8 | alphaMode | 0 opaque, 1 mask, 2 blend |
| 17 | u8×3 | reserved | |
| 20 | f32 | alphaCutoff | for mask |
| 24 | f32×4 | baseColorFactor | linear RGBA |
| 40 | f32×3 | emissiveFactor | linear RGB |
| 52 | f32 | emissiveStrength | |
| 56 | f32 | metallicFactor | |
| 60 | f32 | roughnessFactor | |
| 64 | f32 | normalScale | |
| 68 | f32 | occlusionStrength | |
| 72 | f32×4 | uvTransform | scale U, scale V, offset U, offset V |
| 88 | f32 | uvRotation | radians |
| 92 | u32 | textureCount | at most 64 |
| 96 | 32 bytes | reserved | |

## Textures (16 bytes each, after the fixed part)

| Offset | Type | Field |
|---|---|---|
| 0 | u32 | slot: 0 base color (sRGB BC7), 1 normal (BC5), 2 ORM (BC7), 3 emissive (sRGB BC7), 4 occlusion alone (BC4, when it reads another UV set than ORM), higher values renderer-defined |
| 4 | u32 | flags: bit 0 samples with uv1 |
| 8 | u64 | `StrId` of the `.ktx2` asset path |

The material loader declares every texture as a dependency (`DecodeContext.dependsOn`), so
textures load with the material's priority and are released with it.

## Feature bits

`MaterialData.Builder.deriveFeatures()` sets bits 0–15 from the material; the cooker always calls
it. `MaterialData.FEATURE_NAMES` holds the matching shader `#define` names.

| Bit | Define | Set when |
|---|---|---|
| 0 | `BASE_COLOR_MAP` | a base color texture |
| 1 | `NORMAL_MAP` | a normal map |
| 2 | `ORM_MAP` | an ORM texture |
| 3 | `EMISSIVE_MAP` | an emissive texture |
| 4 | `OCCLUSION_MAP` | a separate occlusion texture (slot 4) |
| 5 | `ALPHA_MASK` | alpha mode mask |
| 6 | `ALPHA_BLEND` | alpha mode blend |
| 7 | `DOUBLE_SIDED` | the double-sided flag |
| 8 | `UV_TRANSFORM` | a non-identity UV transform |
| 9 | `UV1` | some texture samples the second UV set |
