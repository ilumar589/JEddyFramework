# `<level>.physics.json` physics bake description

Source file of the cooker's physics importer (`dev.jeddy.cooker.physics.PhysicsImporter`) for a
level that is a single glTF. It points at the level's glTF and says how to turn its nodes into
collision, navigation and visibility data. Levels composed in the editor ([`.jlevel`](jlevel.md))
carry the same settings themselves and are baked by `LevelImporter` through the shared
`PhysicsBake` (D90). Outputs, next to the description (`levels/town.physics.json` → `levels/town.*`):
[`.jcol`](jcol.md), one [`.jnav`](jnav.md) per size class, [`.jocc`](jocc.md). The glTF is a
dependency (edits re-bake); it is still cooked for rendering on its own.

```json
{
  "scene": "town.gltf",
  "collision": [
    {"nodes": "ground_*", "flags": ["WALKABLE"], "surface": 2},
    {"nodes": "wall_*", "flags": ["BLOCKS_MOVEMENT", "BLOCKS_LOS", "BLOCKS_PROJECTILE"]},
    {"nodes": "roof_*", "flags": ["CAMERA_FADE"]},
    {"nodes": "fx_*", "flags": []},
    {"nodes": "*", "flags": ["SOLID"]}
  ],
  "navmesh": {
    "cellSize": 0.15, "cellHeight": 0.1, "tileSize": 32, "maxSlope": 45,
    "sizes": {
      "medium": {"radius": 0.4, "height": 1.8, "climb": 0.4},
      "large": {"radius": 0.9, "height": 2.6, "climb": 0.5}
    },
    "areas": [
      {"polygon": [10, 4, 12, 4, 12, 6, 10, 6], "yMin": -1, "yMax": 2, "area": 62}
    ],
    "links": [
      {"start": [3, 0, 18], "end": [7, 0, 18], "radius": 0.5, "bidirectional": false, "area": 63, "id": 4}
    ]
  },
  "occlusion": {"cellSize": 0.5, "yMin": 0.3, "yMax": 2.0}
}
```

- **`collision`**: rules over node names (lower-cased; globs as in pak rules: `*`, `?`, `{a,b}`);
  the first match wins, and `"flags": []` leaves a node out. Without rules every mesh node is
  `SOLID`. Flags: `WALKABLE`, `BLOCKS_MOVEMENT`, `BLOCKS_LOS`, `BLOCKS_PROJECTILE`,
  `CAMERA_FADE`, `SOLID` (all four blocking flags). `surface` (0–255) is the footstep and decal
  surface type, `area` (0–63) a navigation area for walkable triangles. A node's
  `extras.collision` object (same keys) overrides the rules.
- **`navmesh`**: voxel and tile settings (`tileSize` in meters), one entry per agent size class
  (defaults: radius 0.4, height 1.8, climb 0.4; without `sizes`, one `medium` class), **area
  volumes** (XZ polygon and Y range; doorways for `NavDoors`, difficult terrain, hazards) and
  **off-mesh links** (jumps, ladders; `id` comes back as the link's user id). Without `navmesh`,
  no `.jnav` is written.
- **`occlusion`**: the fog-of-war grid's cell size and the eye-height band whose `BLOCKS_LOS`
  geometry blocks sight; `"enabled": false` skips it.
