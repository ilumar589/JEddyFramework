# `.jnav` navmesh, version 1

The navmesh tiles of one agent size class (DESIGN §11.4), baked by
`dev.jeddy.physics.nav.bake.NavMeshBaker` (the cooker writes `<level>.<size>.jnav`) and read by
`dev.jeddy.physics.nav.NavMeshData.read`. Little-endian.

## Header (40 bytes)

| Offset | Type | Field | Notes |
|---|---|---|---|
| 0 | u32 | magic | `0x56414E4A` (`"JNAV"` in file order) |
| 4 | u32 | version | 1 |
| 8 | u32 | tileCount | |
| 12 | u32 | reserved | 0 |
| 16 | f32 | originX | world x of tile (0, 0)'s corner |
| 20 | f32 | originZ | world z of tile (0, 0)'s corner |
| 24 | f32 | tileSize | tile edge in meters |
| 28 | f32 | agentRadius | the walkable area is eroded by it |
| 32 | f32 | agentHeight | clearance |
| 36 | f32 | agentClimb | highest step; portal heights may differ by this much |

## Tiles

Each tile: a 16-byte header, its vertices, then its polygons.

| Type | Field |
|---|---|
| i32 | tileX (column; covers `originX + tileX · tileSize` onward) |
| i32 | tileZ |
| u32 | vertCount |
| u32 | polyCount |
| f32×3 × vertCount | vertices, world space |

Per polygon (72 bytes):

| Type | Field | Notes |
|---|---|---|
| i32×6 | vertices | indices into the tile's vertices; -1 for unused slots |
| i32×6 | neighbours | per edge `i` (vertex `i` to `i + 1`): -1 a wall, `0x40000000 | side` a portal on the tile side (0 −x, 1 +z, 2 +x, 3 −z) linked to the next tile at run time, else a polygon of this tile |
| u32 | vertCount | 3..6 for ground polygons, 2 for off-mesh links |
| u32 | area | 1..63 (63 default ground); the query filter's cost class |
| u32 | type | 0 ground, 1 off-mesh link (vertices: start, end) |
| u32 | bidirectional | off-mesh links: 1 both ways, 0 start to end only |
| f32 | radius | off-mesh links: how far the ends may be from the ground |
| u32 | userId | off-mesh links: the game's id (animations, doors) |

Ground polygons are convex, wound so that `(b − a) × (c − a) < 0` in XZ for consecutive vertices
(Recast's convention). Polygon vertices carry the surface height; there is no detail mesh (D52).
Polygon flags are not stored: they start as `NavFlags.WALK` and change at run time.
