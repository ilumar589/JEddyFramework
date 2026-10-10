# `.jocc` occlusion grid, version 1

The fog-of-war occlusion grid (DESIGN §11.5): one bit per cell, set where `BLOCKS_LOS` geometry
crosses the eye-height band. Baked by `dev.jeddy.physics.vis.OcclusionGrid.bake` (the cooker
writes `<level>.jocc`), read by `OcclusionGrid.read`. Little-endian.

## Header (28 bytes)

| Offset | Type | Field | Notes |
|---|---|---|---|
| 0 | u32 | magic | `0x43434F4A` (`"JOCC"` in file order) |
| 4 | u32 | version | 1 |
| 8 | u32 | width | cells along x |
| 12 | u32 | height | cells along z |
| 16 | f32 | cellSize | meters |
| 20 | f32 | minX | world x of cell (0, 0)'s corner |
| 24 | f32 | minZ | world z of cell (0, 0)'s corner |

## Cells

`height` rows of `ceil(width / 64)` u64 words. Cell `(x, z)` is bit `x & 63` of word
`z · wordsPerRow + (x >> 6)`; 1 means blocked.
