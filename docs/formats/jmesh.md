# `.jmesh` cooked mesh, version 1

A `.jmesh` holds one mesh: vertex streams, a shared `u32` index buffer, submeshes (one draw per
material slot), LOD index ranges and default materials. The cooker writes it after welding,
MikkTSpace tangents and meshoptimizer passes (DESIGN §10.3); the runtime reads it in place
(`dev.jeddy.asset.format.MeshData`) and uploads the streams straight from the file memory.
`dev.jeddy.asset.format.MeshWriter` produces it.

All integers and floats are little-endian. Offsets are from the start of the file. Every section
starts at a multiple of 64.

## Header (128 bytes)

| Offset | Type | Field | Notes |
|---|---|---|---|
| 0 | u32 | magic | `0x48534D4A` (`"JMSH"` in file order) |
| 4 | u16 | version | 1 |
| 6 | u16 | flags | bit 0: skin stream present |
| 8 | u32 | vertexCount | |
| 12 | u32 | indexCount | all LODs of all submeshes |
| 16 | u16 | submeshCount | |
| 18 | u16 | lodCount | at least 1; LOD 0 is the most detailed |
| 20 | u16 | materialSlotCount | |
| 22 | u16 | reserved | 0 |
| 24 | f32×3 | boundsMin | axis-aligned box of all vertices |
| 36 | f32×3 | boundsMax | |
| 48 | f32×4 | sphere | center xyz (box center), radius |
| 64 | u64 | positionsOffset | |
| 72 | u64 | attributesOffset | |
| 80 | u64 | skinOffset | 0 without the skin flag |
| 88 | u64 | indicesOffset | |
| 96 | u64 | submeshesOffset | |
| 104 | u64 | lodsOffset | |
| 112 | u64 | materialsOffset | |
| 120 | u64 | fileSize | for truncation checks |

## Vertex streams

Separate streams, so depth-only passes fetch positions alone.

| Stream | Stride | Content |
|---|---|---|
| positions | 12 | `f32 x, y, z` |
| attributes | 16 | QTangent `snorm16 ×4` (bytes 0–7), uv0 `f16 ×2` (8–11), uv1 `f16 ×2` (12–15) |
| skin | 16 | joints `u16 ×4` (0–7), weights `unorm16 ×4` (8–15), summing to 1 |

**QTangent.** The tangent frame `[t, b, n]` (with `b = cross(n, t)`) as a unit quaternion. `w` is
kept positive and at least 1/32767 before quantization, then the whole quaternion is negated when
the bitangent is mirrored, so `sign(w)` is the handedness. The shader decodes
`t = rotate(q, (1,0,0))`, `n = rotate(q, (0,0,1))`, `b = cross(n, t) * sign(q.w)`.
`MeshEncoding` holds the reference encoder and decoder.

## Indices

`u32`, relative to the submesh's base vertex (drawn with `glDrawElementsBaseVertex` or a
multi-draw-indirect command).

## Submeshes (48 bytes each)

| Offset | Type | Field |
|---|---|---|
| 0 | u32 | material slot (< materialSlotCount, or 0 when there are none) |
| 4 | u32 | baseVertex |
| 8 | u32 | vertexCount |
| 12 | u32 | flags (0) |
| 16 | f32×3 | boundsMin |
| 28 | f32×3 | boundsMax |
| 40 | u32×2 | reserved |

## LODs

First `lodCount` entries of `{f32 error, u32 reserved}`: the LOD's geometric error in mesh units
(0 for LOD 0; selection compares its projected size with a pixel threshold). Then
`lodCount × submeshCount` entries of `{u32 firstIndex, u32 indexCount}`, LOD-major.

## Materials

`materialSlotCount × u64`: the `StrId` of each slot's default `.jmat` (scenes may override).

## Validation

Readers check every section against the file size, every submesh against the vertex count, every
LOD range against the index count, and every index against its submesh's vertex count, so a
corrupt file is rejected on a worker instead of reaching the GPU.
