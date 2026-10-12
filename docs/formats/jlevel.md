# `.jlevel` level, version 2

A level as a *composition* (DESIGN §19, D86): placements of prefabs, lights, spawn points,
triggers, navigation area volumes and off-mesh links, collision rules, agent size classes and
the level's settings. The editor writes it, the cooker copies it to the output unchanged and
derives the physics data from it ([`.jcol`](jcol.md), [`.jnav`](jnav.md), [`.jocc`](jocc.md),
D90), and the runtime reads it (`dev.jeddy.asset.format.LevelData`, also the reader and writer).
It is the format of record; `LevelData.toJson` dumps it for inspection and review, and nothing
reads that JSON back.

A **prefab** (D87) is a named root node of a cooked scene (`levels/dungeon.jscene`, node
`wall_doorway`) or, with an empty node name, a whole scene. Every placement, light, spawn and
trigger has a **stable `u32` id** within the level, unique across the four kinds; the header's
`nextId` is the next free one. The editor's selection and undo stack and the game's triggers refer
to ids, never to indices, so rows can be removed and reinserted.

Version 2 (M22, D109) adds the **component rows**: per level object the components the editor
attached and the values it set, keyed by component and column *names*, so a renamed record
field does not break a level. Version 1 files (a 160-byte header, no rows) are still read;
the writer always writes version 2.

Little-endian; offsets are from the start of the file; every section starts at a 16-byte
boundary. Strings are UTF-8 bytes in the string section, referenced by `(u32 offset, u32
length)` pairs; an empty string is `(0, 0)`.

## Header (192 bytes; 160 in version 1)

| Offset | Type | Field | Notes |
|---|---|---|---|
| 0 | u32 | magic | `0x4C564C4A` (`"JLVL"` in file order) |
| 4 | u16 | version | 2 (1 is read) |
| 6 | u16 | flags | 0 |
| 8 | u32 | prefabCount | |
| 12 | u32 | placementCount | |
| 16 | u32 | lightCount | |
| 20 | u32 | spawnCount | |
| 24 | u32 | triggerCount | |
| 28 | u32 | areaCount | navigation area volumes |
| 32 | u32 | linkCount | off-mesh links |
| 36 | u32 | ruleCount | collision rules |
| 40 | u32 | sizeClassCount | navigation agent sizes |
| 44 | u32 | nextId | the next free object id (ids start at 1) |
| 48 | u64 | prefabsOffset | |
| 56 | u64 | placementsOffset | |
| 64 | u64 | lightsOffset | |
| 72 | u64 | spawnsOffset | |
| 80 | u64 | triggersOffset | |
| 88 | u64 | areasOffset | |
| 96 | u64 | linksOffset | |
| 104 | u64 | rulesOffset | |
| 112 | u64 | sizesOffset | |
| 120 | u64 | settingsOffset | the fixed settings block |
| 128 | u64 | polygonsOffset | the area polygons' `f32` x, z pairs |
| 136 | u64 | stringsOffset | |
| 144 | u64 | stringBytes | |
| 152 | u64 | fileSize | for truncation checks |
| 160 | u32 | componentRowCount | version 2 |
| 164 | u32 | componentValueCount | version 2 |
| 168 | u64 | componentRowsOffset | version 2 |
| 176 | u64 | componentValuesOffset | version 2 |
| 184 | 8 bytes | reserved (0) | version 2 |

## Prefabs (16 bytes each)

| Offset | Type | Field |
|---|---|---|
| 0 | u32 ×2 | scene: the cooked scene's asset path (`levels/dungeon.jscene`) |
| 8 | u32 ×2 | node: the root node's name, empty for the whole scene |

## Placements (96 bytes each)

| Offset | Type | Field |
|---|---|---|
| 0 | u32 | id |
| 4 | u32 | prefab index |
| 8 | u32 ×2 | name |
| 16 | u32 ×2 | layer: the collision rule key (`floor`, `wall`, `prop`, ...) |
| 24 | u32 | flags: bit 0 `STATIC`, bit 1 `CASTS_SHADOW`, bit 2 `FADE_OCCLUDER`, bit 3 `HIDDEN`, bit 4 `NO_DECALS`, bit 5 `NO_COLLISION` (left out of the physics bake whatever its layer) |
| 28 | u32 | collision override: `0xFFFFFFFF` for "by layer", else triangle flags as in [jcol.md](jcol.md) (`WALKABLE`, `BLOCKS_LOS`, ..., surface in bits 8–15, area in bits 16–21) |
| 32 | f32 ×3 | position |
| 44 | f32 ×4 | rotation quaternion `x, y, z, w` |
| 60 | f32 ×3 | scale |
| 72 | 24 bytes | reserved (0) |

## Lights (80 bytes each)

| Offset | Type | Field |
|---|---|---|
| 0 | u32 | id |
| 4 | u32 | type: 1 point, 2 spot (as `.jscene`) |
| 8 | u32 ×2 | name |
| 16 | f32 ×3 | position |
| 28 | f32 ×3 | direction (spot), a unit vector |
| 40 | f32 ×3 | linear RGB color |
| 52 | f32 | intensity in candela |
| 56 | f32 | range in meters |
| 60 | f32 | spot inner cone angle (radians) |
| 64 | f32 | spot outer cone angle (radians) |
| 68 | u32 | flags: bit 0 `SHADOW`, bit 1 `FLICKER` (a torch) |
| 72 | 8 bytes | reserved (0) |

## Spawns (48 bytes each)

| Offset | Type | Field |
|---|---|---|
| 0 | u32 | id |
| 4 | u32 ×2 | name |
| 12 | u32 ×2 | kind (`party`, `enemy`, or anything a game defines) |
| 20 | f32 ×3 | position |
| 32 | f32 | yaw (radians) |
| 36 | 12 bytes | reserved (0) |

## Triggers (64 bytes each)

An oriented box.

| Offset | Type | Field |
|---|---|---|
| 0 | u32 | id |
| 4 | u32 ×2 | name |
| 12 | u32 ×2 | kind (`area`, `door`, `script`, ...) |
| 20 | u32 ×2 | payload: a string the game interprets (an event name, a script id) |
| 28 | f32 ×3 | center |
| 40 | f32 ×3 | size (full extents) |
| 52 | f32 | yaw (radians) |
| 56 | u32 | flags: bit 0 `ONCE`, bit 1 `ENABLED` |
| 60 | 4 bytes | reserved (0) |

## Navigation areas (32 bytes each)

A vertical prism over an XZ polygon that sets the navigation area of the walkable triangles it
contains (doorways, difficult terrain, hazards), as `physics.json` `navmesh.areas`.

| Offset | Type | Field |
|---|---|---|
| 0 | u32 | area (0–63) |
| 4 | f32 | yMin |
| 8 | f32 | yMax |
| 12 | u32 | pointCount |
| 16 | u32 | first point: an index into the polygon section (`f32 x, z` per point) |
| 20 | 12 bytes | reserved (0) |

## Off-mesh links (48 bytes each)

| Offset | Type | Field |
|---|---|---|
| 0 | f32 ×3 | start |
| 12 | f32 ×3 | end |
| 24 | f32 | radius |
| 28 | u32 | flags: bit 0 `BIDIRECTIONAL` |
| 32 | u32 | area |
| 36 | u32 | user id (comes back from the navmesh query) |
| 40 | 8 bytes | reserved (0) |

## Collision rules (24 bytes each)

Matched against a placement's layer in order, first match wins (globs: `*`, `?`, `{a,b}`); a
placement whose layer matches no rule is left out of the bake, as is one with `NO_COLLISION`.

| Offset | Type | Field |
|---|---|---|
| 0 | u32 ×2 | layer glob |
| 8 | u32 | triangle flags (as [jcol.md](jcol.md); `0` leaves the layer out) |
| 12 | u32 | surface (0–255) |
| 16 | u32 | area (0–63) |
| 20 | 4 bytes | reserved (0) |

## Agent size classes (32 bytes each)

One navmesh per class, `<level>.<name>.jnav`.

| Offset | Type | Field |
|---|---|---|
| 0 | u32 ×2 | name (`medium`, `large`) |
| 8 | f32 | radius |
| 12 | f32 | height |
| 16 | f32 | climb |
| 20 | 12 bytes | reserved (0) |

## Component rows (24 bytes each; version 2)

One row per (object, component): the object is any placement, light, spawn or trigger by id,
the component a record's simple name from the game's schema
(`META-INF/jeddy/components.json`, `asset.format.ComponentSchema`). A row's values are a run in
the value section; a column that has no value keeps the component's default.

| Offset | Type | Field |
|---|---|---|
| 0 | u32 | object id |
| 4 | u32 ×2 | component name |
| 12 | u32 | first value (index into the values) |
| 16 | u32 | value count |
| 20 | 4 bytes | reserved (0) |

## Component values (24 bytes each; version 2)

| Offset | Type | Field |
|---|---|---|
| 0 | u32 ×2 | column name (`current`, `pos.x`) |
| 8 | u32 | type: 0 `INT`, 1 `LONG`, 2 `FLOAT`, 3 `BOOL`, 4 `ENUM` (a constant's name), 5 `ENTITY` (a level object id, resolved to its entity at load), 6 `ASSET` (a path, resolved to a handle at load) |
| 12 | 4 bytes | reserved (0) |
| 16 | 8 bytes | the payload: `i32` (INT, BOOL as 0/1, ENTITY), `i64` (LONG), `f32` (FLOAT), or a string reference (ENUM, ASSET) |

`LevelData.addComponentRow`, `setComponentInt/Float/Bool/Long/Enum/Entity/Asset` and
`removeComponentValue` edit them; removing an object removes its rows; `copyComponentRows`
duplicates them. `components.Levels` applies them to the generated stores
(`Store.columnIndex`, `enumOrdinal`, the generic setters).

## Settings (128 bytes)

| Offset | Type | Field | Default |
|---|---|---|---|
| 0 | f32 | navmesh cell size | 0.15 |
| 4 | f32 | navmesh cell height | 0.1 |
| 8 | f32 | navmesh tile size (meters) | 32 |
| 12 | f32 | navmesh max slope (degrees) | 45 |
| 16 | f32 | occlusion cell size | 0.5 |
| 20 | f32 | occlusion yMin | 0.3 |
| 24 | f32 | occlusion yMax | 2.0 |
| 28 | u32 | occlusion enabled (0/1) | 1 |
| 32 | f32 ×3 | sun direction | (−0.4, −0.8, −0.45) |
| 44 | f32 ×3 | sun color | (3, 2.9, 2.7) |
| 56 | f32 | exposure | 1 |
| 60 | f32 | environment specular | 1 |
| 64 | f32 | environment diffuse | 1 |
| 68 | u32 ×2 | environment map asset path (a cube map `.ktx2`), empty for none | |
| 76 | f32 | grid step (the editor's snapping) | 0.5 |
| 80 | 48 bytes | reserved (0) | |
