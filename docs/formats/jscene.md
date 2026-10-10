# `.jscene` cooked scene, version 1

A node hierarchy with local transforms, mesh and light references, punctual lights, and the skins
and animations of the source kept raw for the future animation module (DESIGN §16). The cooker
writes one per glTF file; level authoring (decals, trigger volumes, nav and collision references)
extends it in a later version. Read and written by `dev.jeddy.asset.format.SceneData`.
Little-endian; every section starts on a 16-byte boundary.

## Header (96 bytes)

| Offset | Type | Field | Notes |
|---|---|---|---|
| 0 | u32 | magic | `0x4E43534A` (`"JSCN"` in file order) |
| 4 | u16 | version | 1 |
| 6 | u16 | flags | 0 |
| 8 | u32 | nodeCount | |
| 12 | u32 | meshCount | distinct meshes referenced by nodes |
| 16 | u32 | lightCount | |
| 20 | u32 | skinCount | |
| 24 | u32 | animationCount | |
| 28 | u32 | reserved | |
| 32 | u64 | nodesOffset | |
| 40 | u64 | meshesOffset | |
| 48 | u64 | lightsOffset | |
| 56 | u64 | skinsOffset | |
| 64 | u64 | animationsOffset | |
| 72 | u64 | stringsOffset | UTF-8 bytes, referenced by (offset, length) pairs |
| 80 | u64 | stringBytes | |
| 88 | u64 | fileSize | for truncation checks |

## Nodes (64 bytes each)

Nodes are stored **parents first** (`parent < index`), so a loader spawns them in file order.

| Offset | Type | Field |
|---|---|---|
| 0 | u32 ×2 | name (string offset, length); may be empty |
| 8 | i32 | parent node, -1 for roots |
| 12 | i32 | mesh table index, -1 for none |
| 16 | i32 | light index, -1 for none |
| 20 | i32 | skin index, -1 for none |
| 24 | f32 ×3 | translation |
| 36 | f32 ×4 | rotation quaternion `x, y, z, w` |
| 52 | f32 ×3 | scale |

## Meshes (8 bytes each)

The `StrId` (`AssetPaths.id`) of a `.jmesh` asset path. Default materials come from the mesh.

## Lights (48 bytes each)

| Offset | Type | Field |
|---|---|---|
| 0 | u32 | type: 0 directional, 1 point, 2 spot |
| 4 | f32 ×3 | linear RGB color |
| 16 | f32 | intensity: lux (directional) or candela (point, spot), as in `KHR_lights_punctual` |
| 20 | f32 | range in meters, 0 = unlimited |
| 24 | f32 | spot inner cone angle (radians) |
| 28 | f32 | spot outer cone angle (radians) |
| 32 | u32 ×2 | name (string offset, length) |
| 40 | 8 bytes | reserved |

Lights point down their node's local −Z axis.

## Skins (16 bytes each)

| Offset | Type | Field |
|---|---|---|
| 0 | i32 | skeleton root node, -1 if unspecified |
| 4 | u32 | jointCount |
| 8 | u64 | data offset: `i32 joints[jointCount]` (node indices), then `f32 inverseBind[16 × jointCount]` (column-major 4×4) |

## Animations (32 bytes each)

| Offset | Type | Field |
|---|---|---|
| 0 | u32 ×2 | name (string offset, length) |
| 8 | u32 | channelCount |
| 12 | f32 | duration in seconds |
| 16 | u64 | channel table offset |
| 24 | 8 bytes | reserved |

Each channel (32 bytes):

| Offset | Type | Field |
|---|---|---|
| 0 | i32 | target node |
| 4 | u32 | path: 0 translation, 1 rotation, 2 scale, 3 morph weights |
| 8 | u32 | interpolation: 0 linear, 1 step, 2 cubic spline |
| 12 | u32 | keyCount |
| 16 | u32 | components per value (3, 4 or the morph target count) |
| 20 | u32 | reserved |
| 24 | u64 | data offset: `f32 times[keyCount]`, then `f32 values[keyCount × components]` (× 3 for cubic spline: in-tangent, value, out-tangent per key, as in glTF) |

## Validation

The reader checks every section and data range against the file size, parents against node
order, and node, joint and channel references against their tables, so a corrupt file fails with
`AssetFormatException` rather than an out-of-bounds read.
