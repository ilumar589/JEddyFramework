# `.jcol` static collision BVH, version 1

A level's static collision triangles with per-triangle flags, in a flattened binned-SAH BVH
(DESIGN §11.1). Written by the cooker's physics importer, read by `dev.jeddy.physics.bvh.Bvh.read`.
Little-endian.

## Header (16 bytes)

| Offset | Type | Field | Notes |
|---|---|---|---|
| 0 | u32 | magic | `0x4C4F434A` (`"JCOL"` in file order) |
| 4 | u32 | version | 1 |
| 8 | u32 | nodeCount | |
| 12 | u32 | triangleCount | |

## Nodes (32 bytes each)

Depth-first order: an interior node's left child directly follows it.

| Offset | Type | Field |
|---|---|---|
| 0 | f32×3 | bounds min x, y, z |
| 12 | u32 | leaf: first triangle; interior: index of the right child |
| 16 | f32×3 | bounds max x, y, z |
| 28 | u32 | leaf: triangle count (> 0); interior: 0 |

## Triangles

After the nodes, in leaf order:

1. `triangleCount × 9` f32: vertices a, b, c (x, y, z each), world space.
2. `triangleCount` u32 flags: bit 0 `WALKABLE`, 1 `BLOCKS_LOS`, 2 `BLOCKS_PROJECTILE`,
   3 `CAMERA_FADE`, 4 `BLOCKS_MOVEMENT`; bits 8–15 surface type; bits 16–21 navigation area
   (`dev.jeddy.physics.bvh.TriFlags`).
3. `triangleCount` u32 ids: the triangle's index in the source mesh.
