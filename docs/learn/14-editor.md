# 14 — The level editor: a binary level format, commands with undo, picking, gizmos and docked panels

*A level editor is where a game's content gets made, and it is the hardest test an engine's
architecture takes: it must show the live world, change it while it runs, undo anything, and save a
file the runtime loads straight back. This chapter builds the engine's editor from empty files: a
binary level file that is a composition of prefab placements, lights, spawn points and triggers; a
store that reads it, edits it and writes it back; a loader that mirrors the level into the running
world as entity groups; a cooker importer that derives the collision, navigation and occlusion data
from it; commands with explicit inverse data on an undo stack; a viewport that turns a mouse pixel
into a ray, picks objects through the inverse of their transforms, and drags translate, rotate and
scale gizmos whose geometry is derived with numbers; panels that dock over the scene in immediate
mode; and two hosts, the editor inside the iso demo (press F10) and the standalone tool with the
cooker watching the project. It ends with the tests that prove each piece, the numbers measured on
them, and the things that went wrong on the way.*

**Before you start.** You need [Lesson 0](lessons/00-foundations.md): the notation of §2 (sums,
intervals, big-O), bytes and Java's number types (§4), two's complement and `0xFFFFFFFF` as −1 (§5),
masks, shifts and packing (§6: the id index packs a kind and a row into one `int`, flags are bits),
how a `float` is stored (§7: the file stores floats as their 32 bits), the heap and the
zero-allocation rule (§8), threads and `volatile` (§9.4–9.5: the save runs on another thread), what
the GPU, the driver, a shader and the rasterizer are (§10), and frames (§11).
[Lesson 4](lessons/04-binary-formats.md) teaches the byte-level conventions this chapter's file
follows: little-endian order (L04 §2), floats and booleans as bytes (§3), UTF-8 strings (§4),
alignment and padding (§5), and what a version number promises (§8); the chapter cites them instead
of repeating them. From the earlier chapters you need vectors, dot and cross products, matrices and
the row-major 3×4 affine transform from [02](02-math-foundations.md); quaternions and their
products from [03](03-rotations-and-transforms.md); the view and projection matrices, the isometric
camera and unprojecting a pixel to a ray from [04](04-cameras-and-projection.md) (the section on
picking is the one this chapter extends); the world of entities and renderables from
[01](01-architecture.md) and [09](09-animation-and-skinning.md); the cooker, its importers and hot
reload from [10](10-assets.md); the ray tests, the collision bake and the navmesh from
[11](11-physics-navigation-crowds.md); and the immediate-mode UI, its ids and its hit testing from
[13](13-ui.md). [Lesson 3](lessons/03-data-layout.md) explains the struct-of-arrays layout the level
store uses and the handles the world hands out.

**What you will build**

| Piece | File | What it does |
|---|---|---|
| The level file | [`docs/formats/jlevel.md`](../formats/jlevel.md), [`LevelData`](../../modules/asset/src/main/java/dev/jeddy/asset/format/LevelData.java) | the `.jlevel` format of record; a mutable struct-of-arrays store with stable ids that reads it, edits it and writes it back |
| The runtime | [`LevelLoader`](../../modules/app/src/main/java/dev/jeddy/app/world/LevelLoader.java), [`World.instantiateNode`, `World.pick`](../../modules/app/src/main/java/dev/jeddy/app/world/World.java) | placements become entity groups that follow every edit; a ray against every renderable's real box |
| The cooker | [`LevelImporter`](../../tools/cooker/src/main/java/dev/jeddy/cooker/level/LevelImporter.java), [`PhysicsBake`](../../tools/cooker/src/main/java/dev/jeddy/cooker/physics/PhysicsBake.java) | collision, navmeshes and the occlusion grid derived from the placements and the level's rules |
| Commands | [`Command`](../../modules/editor/src/main/java/dev/jeddy/editor/command/Command.java), [`TransformCommand`](../../modules/editor/src/main/java/dev/jeddy/editor/command/TransformCommand.java), [`ObjectRow`](../../modules/editor/src/main/java/dev/jeddy/editor/command/ObjectRow.java), [`AddObjectsCommand`](../../modules/editor/src/main/java/dev/jeddy/editor/command/AddObjectsCommand.java), [`DeleteObjectsCommand`](../../modules/editor/src/main/java/dev/jeddy/editor/command/DeleteObjectsCommand.java), [`SetFloatCommand`](../../modules/editor/src/main/java/dev/jeddy/editor/command/SetFloatCommand.java), [`SetIntCommand`](../../modules/editor/src/main/java/dev/jeddy/editor/command/SetIntCommand.java), [`SetStringCommand`](../../modules/editor/src/main/java/dev/jeddy/editor/command/SetStringCommand.java), [`SetSettingsCommand`](../../modules/editor/src/main/java/dev/jeddy/editor/command/SetSettingsCommand.java), [`TableCommand`](../../modules/editor/src/main/java/dev/jeddy/editor/command/TableCommand.java), [`ComponentRowCommand`](../../modules/editor/src/main/java/dev/jeddy/editor/command/ComponentRowCommand.java), [`SetComponentValueCommand`](../../modules/editor/src/main/java/dev/jeddy/editor/command/SetComponentValueCommand.java) | every edit as explicit inverse data; merging; rows that go back where they were |
| The controller | [`Editor`](../../modules/editor/src/main/java/dev/jeddy/editor/Editor.java), [`EditorContext`](../../modules/editor/src/main/java/dev/jeddy/editor/EditorContext.java), [`UndoStack`](../../modules/editor/src/main/java/dev/jeddy/editor/UndoStack.java), [`Selection`](../../modules/editor/src/main/java/dev/jeddy/editor/Selection.java), [`LevelObjects`](../../modules/editor/src/main/java/dev/jeddy/editor/LevelObjects.java), [`LevelFiles`](../../modules/editor/src/main/java/dev/jeddy/editor/LevelFiles.java), [`Console`](../../modules/editor/src/main/java/dev/jeddy/editor/Console.java), [`EditorSettings`](../../modules/editor/src/main/java/dev/jeddy/editor/EditorSettings.java) | the history with a saved mark, the selection of ids, uniform access to any object, saving on another thread |
| The viewport | [`Viewport`](../../modules/editor/src/main/java/dev/jeddy/editor/Viewport.java), [`GizmoMath`](../../modules/editor/src/main/java/dev/jeddy/editor/GizmoMath.java), [`ViewportInput`](../../modules/editor/src/main/java/dev/jeddy/editor/ViewportInput.java) | the camera, the ray, picking, box selection, three gizmos and their math |
| The panels | [`Dock`](../../modules/editor/src/main/java/dev/jeddy/editor/panel/Dock.java), [`Panel`](../../modules/editor/src/main/java/dev/jeddy/editor/panel/Panel.java), [`OutlinerPanel`](../../modules/editor/src/main/java/dev/jeddy/editor/panel/OutlinerPanel.java), [`InspectorPanel`](../../modules/editor/src/main/java/dev/jeddy/editor/panel/InspectorPanel.java), [`EntitiesPanel`](../../modules/editor/src/main/java/dev/jeddy/editor/panel/EntitiesPanel.java), [`AssetsPanel`](../../modules/editor/src/main/java/dev/jeddy/editor/panel/AssetsPanel.java), [`ConsolePanel`](../../modules/editor/src/main/java/dev/jeddy/editor/panel/ConsolePanel.java), [`SettingsPanel`](../../modules/editor/src/main/java/dev/jeddy/editor/panel/SettingsPanel.java), [`HistoryPanel`](../../modules/editor/src/main/java/dev/jeddy/editor/panel/HistoryPanel.java), [`RulesPanel`](../../modules/editor/src/main/java/dev/jeddy/editor/panel/RulesPanel.java) | immediate-mode panels in three dock zones; the rules panel's model in [`RulesSource`](../../modules/editor/src/main/java/dev/jeddy/editor/rules/RulesSource.java), [`RulesPath`](../../modules/editor/src/main/java/dev/jeddy/editor/rules/RulesPath.java), [`RulesSetCommand`](../../modules/editor/src/main/java/dev/jeddy/editor/rules/RulesSetCommand.java), [`RulesListCommand`](../../modules/editor/src/main/java/dev/jeddy/editor/rules/RulesListCommand.java) |
| The widgets the panels needed | [`Widgets`](../../modules/ui/src/main/java/dev/jeddy/ui/Widgets.java) | `numberField`, `vectorField`, `colorField`, `listBox`, `beginProperty`, `splitter`, the menu bar, allocation-free float formatting and parsing |
| The hosts | [`DemoGame`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/DemoGame.java) (F10), [`EditorMain`](../../tools/editor/src/main/java/dev/jeddy/editortool/EditorMain.java), [`EditorOptions`](../../tools/editor/src/main/java/dev/jeddy/editortool/EditorOptions.java), [`EditorGame`](../../tools/editor/src/main/java/dev/jeddy/editortool/EditorGame.java), [`EditorRenderer`](../../tools/editor/src/main/java/dev/jeddy/editortool/EditorRenderer.java), [`CookerService`](../../tools/editor/src/main/java/dev/jeddy/editortool/CookerService.java), [`Png`](../../tools/editor/src/main/java/dev/jeddy/editortool/Png.java) | the editor inside a game, and the standalone tool with the cooker watching |
| Tests | [`LevelDataTest`](../../modules/asset/src/test/java/dev/jeddy/asset/format/LevelDataTest.java), [`LevelLoaderTest`](../../modules/app/src/test/java/dev/jeddy/app/world/LevelLoaderTest.java), [`LevelImporterTest`](../../tools/cooker/src/test/java/dev/jeddy/cooker/level/LevelImporterTest.java), [`UndoStackTest`](../../modules/editor/src/test/java/dev/jeddy/editor/UndoStackTest.java), [`CommandsTest`](../../modules/editor/src/test/java/dev/jeddy/editor/CommandsTest.java), [`GizmoMathTest`](../../modules/editor/src/test/java/dev/jeddy/editor/GizmoMathTest.java), [`ViewportTest`](../../modules/editor/src/test/java/dev/jeddy/editor/ViewportTest.java), [`PanelsTest`](../../modules/editor/src/test/java/dev/jeddy/editor/PanelsTest.java), [`RulesPanelTest`](../../modules/editor/src/test/java/dev/jeddy/editor/RulesPanelTest.java), [`RulesCommandsTest`](../../modules/editor/src/test/java/dev/jeddy/editor/rules/RulesCommandsTest.java), [`EditorWidgetsTest`](../../modules/ui/src/test/java/dev/jeddy/ui/EditorWidgetsTest.java) | round trips byte for byte, drags through projected handles, 0 bytes per idle frame |

The design is [DESIGN §19](../DESIGN.md#19-editor-levels-commands-gizmos-and-panels-m14) and the
decisions D86–D95 (the level model, the editor's shape, commands, the cooker's bake, the id index,
overlay lines, picking, world-axis gizmos, id scopes), D97 (the rules panel), D109 (component rows
in the file) and D110 (scene bindings) in [DESIGN §17](../DESIGN.md#17-decision-log). The work was
milestone M14 in [TASKS.md](../TASKS.md), with the Rules panel from M15-06 and the components
section of the inspector from M22-05.

![The standalone editor on the crypt](../images/editor-m14.jpg)

**Contents**

- Part A — The ideas
  1. [What a level editor has to do](#1-what-a-level-editor-has-to-do)
  2. [A level is a composition, not a mesh](#2-a-level-is-a-composition-not-a-mesh)
  3. [The `.jlevel` file, byte by byte](#3-the-jlevel-file-byte-by-byte)
  4. [Tables you can edit: ids, order and versions](#4-tables-you-can-edit-ids-order-and-versions)
  5. [Placements become entity groups](#5-placements-become-entity-groups)
  6. [Physics is derived from the level](#6-physics-is-derived-from-the-level)
  7. [Edits are commands with explicit inverse data](#7-edits-are-commands-with-explicit-inverse-data)
  8. [From a pixel to a ray](#8-from-a-pixel-to-a-ray)
  9. [Picking: what is under the pointer](#9-picking-what-is-under-the-pointer)
  10. [The gizmo geometry, derived](#10-the-gizmo-geometry-derived)
  11. [Turning and scaling a selection about a pivot](#11-turning-and-scaling-a-selection-about-a-pivot)
  12. [What the GPU does with the gizmo's lines](#12-what-the-gpu-does-with-the-gizmos-lines)
  13. [Panels, ids and the dock](#13-panels-ids-and-the-dock)
- Part B — Building it
  14. [Step 1: `LevelData`, the tables](#14-step-1-leveldata-the-tables)
  15. [Step 2: reading and writing the file](#15-step-2-reading-and-writing-the-file)
  16. [Step 3: the world: groups and picking](#16-step-3-the-world-groups-and-picking)
  17. [Step 4: `LevelLoader`](#17-step-4-levelloader)
  18. [Step 5: the cooker's `LevelImporter`](#18-step-5-the-cookers-levelimporter)
  19. [Step 6: `Command` and `UndoStack`](#19-step-6-command-and-undostack)
  20. [Step 7: the commands](#20-step-7-the-commands)
  21. [Step 8: the context, the selection and the small helpers](#21-step-8-the-context-the-selection-and-the-small-helpers)
  22. [Step 9: `GizmoMath`](#22-step-9-gizmomath)
  23. [Step 10: `Viewport`](#23-step-10-viewport)
  24. [Step 11: `Dock` and `Panel`](#24-step-11-dock-and-panel)
  25. [Step 12: the panels](#25-step-12-the-panels)
  26. [Step 13: the Rules panel](#26-step-13-the-rules-panel)
  27. [Step 14: the widgets the panels needed](#27-step-14-the-widgets-the-panels-needed)
  28. [Step 15: `Editor`, the controller](#28-step-15-editor-the-controller)
  29. [Step 16: the hosts](#29-step-16-the-hosts)
- Part C — Using it and proving it
  30. [One drag, frame by frame](#30-one-drag-frame-by-frame)
  31. [The tests, walked through](#31-the-tests-walked-through)
  32. [Threads and performance](#32-threads-and-performance)
  33. [Pitfalls](#33-pitfalls)
  34. [Things to run](#34-things-to-run)
- [Exercises](#exercises)
- [Glossary](#glossary)

---

# Part A — The ideas

## 1. What a level editor has to do

### 1.1 Four jobs

Strip away the panels and the handles and an editor does four things, and the architecture follows
from keeping them apart:

1. **Hold the level as data** that can be read from a file, changed, and written back: a list of
   what is where. This is the `.jlevel` file and the `LevelData` store that holds it in memory.
2. **Show it live.** The running world must mirror the data: when a placement's position changes
   in the store, its entities move. This is `LevelLoader`, which keeps the mapping from level rows
   to world entities in both directions.
3. **Change it reversibly.** Every edit must be undoable, and a drag of the mouse must be one undo
   step, not sixty. This is the command pattern: a `Command` with `apply` and `revert` on an
   `UndoStack`.
4. **Take input from a 3D view and from panels.** A click must find the object under the pointer;
   a dragged handle must move an object by exactly the right amount; a typed number must become a
   command. This is the `Viewport` with its ray math and gizmos, and the `Dock` of panels built
   from the UI module of chapter [13](13-ui.md).

Everything else, the cooker that bakes the physics from the level and the two hosts, hangs off
these four.

### 1.2 The whole path at a glance

```
                 read                      load / update
 .jlevel  ────────────────▶  LevelData  ──────────────────▶  LevelLoader  ───▶  World
 (file)   ◀────────────────  (SoA rows, ◀──── apply/revert ──  Command        (entities,
                 save          id index)           │              ▲           renderables)
   │                               ▲               │              │                │
   │ cook                          │ sync          │ push         │ pick           │
   ▼                               │               ▼              │                ▼
 LevelImporter ──▶ PhysicsBake   Dock + panels ──▶ UndoStack ◀── Viewport ◀── camera ray
                   .jcol .jnav .jocc  (Outliner, Inspector, ...)  (camera, gizmos, picking)
```

Read it as a loop. The file is parsed into `LevelData`. The loader instantiates its placements into
the world. A panel or the viewport pushes a command; the command changes rows of `LevelData` and
tells the loader, which moves the affected entities. Saving writes the store back to the file; the
cooker reads that file and bakes the physics the game needs. The rule that keeps the loop simple:
**the file is the level; the world is a view of it.** Nothing the editor does goes to the world
first and to the data second.

### 1.3 Where the code lives

The editor is a *library*, `modules/editor`, that a game embeds (D88): the iso demo creates an
`Editor`, attaches its `World` and `LevelLoader`, opens the level it is showing, and while the editor
is shown calls three methods from its own frame: `update` (input: shortcuts, the viewport),
`declare` (the panels, into the game's `Ui`) and `extract` (the grid, markers and gizmo lines, into
the render packet). It renders with the editor's camera while the editor is open. The standalone
tool, `tools/editor`, is the same library over an almost empty game, with the cooker running on its
own thread. §29 builds both hosts.

The alternatives were a standalone-only editor (no live inspection of a running game, which is
where most bugs show), an embedded-only one (every game ships the editor, and a level can only be
edited from inside a game) and rendering the scene into a viewport texture with real tabs around it
(cleaner, but render-target plumbing and a full-screen copy per frame for nothing the first editor
needs). D88 chose the library plus a thin application, with the scene drawn full-window beneath the
panels. Every later milestone's panel (the Rules panel of M15, the Components section of M22) was
added to the library and appeared in both hosts for free.

## 2. A level is a composition, not a mesh

### 2.1 The problem with one big glTF

Before M14 the crypt was one glTF file with 372 pieces of dungeon kit flattened into it, plus two
JSON files beside it: the physics rules and the spawn points. That is fine to render and hopeless
to edit. Moving a wall meant re-running the script that produced the glTF; placing a torch meant
editing JSON by hand; there was no place at all for a trigger volume. And the renderer could not
tell one wall from the next: the pieces were baked into one scene, and nothing in it said which
wall was which or let a tool address one.

The editor needs a level that is a *list of placements*: "the `wall_doorway` prefab at (12, 0, 40),
turned 90°". A **placement** names a prefab and carries a transform (position, rotation, scale);
everything heavy (vertices, textures, materials) lives in the prefab, which the cooker already
produces from a glTF. Lights, spawn points and trigger boxes are rows of the same kind: a position
and a few numbers each. This is D86: *a level is a composition.*

### 2.2 Prefabs are root nodes of a library scene

A **prefab** (D87) is a named root node of a cooked scene. The script that used to build the big
glTF now writes a *library* glTF, `levels/dungeon.glb`, with one root node per dungeon piece
(`wall`, `floor_tile_large`, `torch_mounted`, ...) and the piece's meshes as its children. The
cooker turns it into `levels/dungeon.jscene` and the meshes beside it, exactly as for any other
scene (chapter [10](10-assets.md)). The level refers to a prefab by two strings, the cooked scene's
path and the root node's name:

```
prefab 1 = ("levels/dungeon.jscene", "wall")
```

An empty node name means "the whole scene", which is how a one-off model (a boss statue exported
on its own) becomes a placement without a library.

Why a library rather than one glTF per piece? The cooker cooks textures *per glTF*: one library
means one texture set and one material table shared by every piece, instead of fifteen copies of
the same stone texture. It also gives the Assets panel something to browse: a library's root nodes
are its placeable prefabs. The cost is that a piece is edited in its DCC tool and the whole library
re-cooked; that is what the standalone tool's watcher does (§29.3).

### 2.3 What else a level carries

The placements are most of the file, but a level also holds, in the order the file stores them:

| Section | What it is | Who uses it |
|---|---|---|
| Prefabs | the `(scene, node)` pairs placements refer to by index | the loader, the cooker |
| Placements | prefab, name, *layer*, flags, collision override, transform | everything |
| Lights | point or spot: position, direction, colour, intensity, range, cone, flags | the loader feeds them to the render packet every frame |
| Spawns | a named point with a yaw and a *kind* (`party`, `enemy`) | the game |
| Triggers | an oriented box with a kind and a payload string | the game |
| Navigation areas | vertical prisms over an XZ polygon that set the navigation area of the walkable triangles inside | the cooker's navmesh bake |
| Off-mesh links | a jump or ladder between two points | the navmesh bake |
| Collision rules | `layer glob → triangle flags, surface, area`, matched in order | the cooker (§6) |
| Agent size classes | one navmesh per class (`medium`, `large`) | the navmesh bake |
| Settings | navmesh and occlusion bake parameters, sun, exposure, environment map, the editor's grid step | the cooker, the hosts, the editor |
| Component rows | (version 2) per object, the components the editor attached and the values it set | the game's component registry (chapter [22](22-components.md)) |

The **layer** of a placement is a short string, `floor`, `wall`, `prop`, that the collision rules
match with a glob. It replaces the node-name prefixes the old script used to drive collision: a
designer sets a layer in the Inspector, and the physics follows.

### 2.4 Why not JSON, and why not glTF

The owner asked for an engine-native format, and the runtime reads the level on every load, so the
file is binary: a header, sections of fixed-size rows, a string table (§3). The rejected
alternatives, from D86:

- **JSON as the format of record.** Slower to load (parse, allocate, validate), no fixed layout
  (every reader walks a tree), and no advantage for a file that only the editor writes. A JSON
  *dump* exists (`LevelData.toJson`, the console's `dump` command) for humans and code review, and
  nothing reads it back: one format of record.
- **Editing the level's glTF in place.** glTF has no place for triggers, spawns or collision rules
  without extensions, and round-tripping a glTF through a writer is fragile (every exporter writes
  something slightly different).

## 3. The `.jlevel` file, byte by byte

This section is the whole file, every field. The byte-level conventions are those of
[Lesson 4](lessons/04-binary-formats.md): every number is **little-endian** (L04 §2: the lowest
byte at the lowest address), a float is its 32 bits written as an `int` (L04 §3), a string is UTF-8
bytes in a string section referenced by an `(offset, length)` pair (L04 §4), and sections start on
16-byte boundaries with zero padding between them (L04 §5). What a version number promises is L04
§8; this file is at version 2.

### 3.1 The shape

```
offset 0      ┌──────────────────────────────┐
              │ header (192 bytes)           │  magic, version, counts, section offsets
offset 192    ├──────────────────────────────┤
              │ prefabs      16 bytes each   │
              ├──────────────────────────────┤  ← each section starts on a multiple of 16
              │ placements   96 bytes each   │
              ├──────────────────────────────┤
              │ lights       80 bytes each   │
              │ spawns       48              │
              │ triggers     64              │
              │ areas        32              │
              │ links        48              │
              │ rules        24              │
              │ size classes 32              │
              ├──────────────────────────────┤
              │ settings     128 bytes       │
              ├──────────────────────────────┤
              │ polygons     f32 x, z pairs  │  the areas' points
              ├──────────────────────────────┤
              │ component rows   24 each     │  version 2
              │ component values 24 each     │  version 2
              ├──────────────────────────────┤
              │ strings      UTF-8 bytes     │  referenced by (offset, length)
fileSize      └──────────────────────────────┘  rounded up to a multiple of 16
```

Fixed-size rows mean that row $i$ of a section is at `sectionOffset + size × i`: a reader that wants
placement 200 computes one address and reads it. There is no walking. The string section comes last
because its size is only known once every row has been written.

### 3.2 The header

Byte offsets from the start of the file; `u32` is an unsigned 32-bit integer, `u64` an unsigned
64-bit one (Java reads both into signed types; the counts are checked against a sane limit, §15.1).

| Offset | Type | Field | Notes |
|---|---|---|---|
| 0 | u32 | magic | `0x4C564C4A`: the bytes `4A 4C 56 4C` = `"JLVL"` in file order (L04 §11.3 explains why the constant is written backwards) |
| 4 | u16 | version | 2; a version 1 file (160-byte header, no component rows) is still read |
| 6 | u16 | flags | 0 |
| 8 | u32 | prefabCount | |
| 12 | u32 | placementCount | |
| 16 | u32 | lightCount | |
| 20 | u32 | spawnCount | |
| 24 | u32 | triggerCount | |
| 28 | u32 | areaCount | navigation area volumes |
| 32 | u32 | linkCount | off-mesh links |
| 36 | u32 | ruleCount | collision rules |
| 40 | u32 | sizeClassCount | agent sizes |
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
| 120 | u64 | settingsOffset | the 128-byte settings block |
| 128 | u64 | polygonsOffset | the areas' `f32 x, z` pairs |
| 136 | u64 | stringsOffset | |
| 144 | u64 | stringBytes | |
| 152 | u64 | fileSize | for truncation checks |
| 160 | u32 | componentRowCount | version 2 |
| 164 | u32 | componentValueCount | version 2 |
| 168 | u64 | componentRowsOffset | version 2 |
| 176 | u64 | componentValuesOffset | version 2 |
| 184 | 8 bytes | reserved (0) | version 2 |

Offsets are 64-bit so that the format never needs a new version for a level over 2 GiB, which no
level will be; the cost is a few bytes. `fileSize` lets a reader reject a truncated file before it
reads a single row (§15.1).

### 3.3 The rows

**Prefabs (16 bytes).** Two string references.

| Offset | Type | Field |
|---|---|---|
| 0 | u32 ×2 | scene: the cooked scene's asset path (`levels/dungeon.jscene`) |
| 8 | u32 ×2 | node: the root node's name, empty for the whole scene |

**Placements (96 bytes).** The row every editing operation touches.

| Offset | Type | Field |
|---|---|---|
| 0 | u32 | id |
| 4 | u32 | prefab index |
| 8 | u32 ×2 | name |
| 16 | u32 ×2 | layer: the collision rule key (`floor`, `wall`, `prop`, ...) |
| 24 | u32 | flags: bit 0 `STATIC`, bit 1 `CASTS_SHADOW`, bit 2 `FADE_OCCLUDER`, bit 3 `HIDDEN`, bit 4 `NO_DECALS`, bit 5 `NO_COLLISION` |
| 28 | u32 | collision override: `0xFFFFFFFF` for "by layer", else triangle flags as in [jcol.md](../formats/jcol.md) |
| 32 | f32 ×3 | position |
| 44 | f32 ×4 | rotation quaternion `x, y, z, w` |
| 60 | f32 ×3 | scale |
| 72 | 24 bytes | reserved (0) |

The ten floats from offset 32 are the placement's **transform row** in the order position,
rotation, scale: the same order `World.instantiateNode` and `TransformStore` take a TRS, so the
loader passes the level's float array straight through (§5). The 24 reserved bytes round the row
to 96, a multiple of 16, and leave room for a later version to add fields without moving anything.
`0xFFFFFFFF` is all ones, which Java reads as the `int` −1 (L00 §5.3): that is the
`COLLISION_BY_LAYER` constant.

**Lights (80 bytes).**

| Offset | Type | Field |
|---|---|---|
| 0 | u32 | id |
| 4 | u32 | type: 1 point, 2 spot (as `.jscene`) |
| 8 | u32 ×2 | name |
| 16 | f32 ×3 | position |
| 28 | f32 ×3 | direction (spot), a unit vector |
| 40 | f32 ×3 | linear RGB colour |
| 52 | f32 | intensity in candela |
| 56 | f32 | range in metres |
| 60 | f32 | spot inner cone angle (radians) |
| 64 | f32 | spot outer cone angle (radians) |
| 68 | u32 | flags: bit 0 `SHADOW`, bit 1 `FLICKER` (a torch) |
| 72 | 8 bytes | reserved (0) |

Thirteen floats from offset 16: position 0–2, direction 3–5, colour 6–8, intensity 9, range 10,
inner 11, outer 12. The Inspector and the gizmo address them by these indices.

**Spawns (48 bytes).**

| Offset | Type | Field |
|---|---|---|
| 0 | u32 | id |
| 4 | u32 ×2 | name |
| 12 | u32 ×2 | kind (`party`, `enemy`, or anything a game defines) |
| 20 | f32 ×3 | position |
| 32 | f32 | yaw (radians) |
| 36 | 12 bytes | reserved (0) |

**Triggers (64 bytes).** An oriented box.

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

Notice the one convention shared by all four object kinds: **the position is the first three
floats of the row** (placement offset 32, light 16, spawn 20, trigger 28: floats 0–2 of each
kind's float run). That is what lets one translate gizmo and one Outliner row move or list any
object (§11, §21.2). Rotation and size differ by kind: a placement has a quaternion at floats 3–6
and a scale at 7–9, a spawn a yaw at float 3, a trigger extents at 3–5 and a yaw at 6, a light a
direction vector at 3–5.

**Navigation areas (32 bytes)**, **off-mesh links (48 bytes)**, **collision rules (24 bytes)** and
**agent size classes (32 bytes)** have no ids; they are addressed by index, which is why the editor's
`TableCommand` carries an index (§20.6).

| Areas | | | Links | | |
|---|---|---|---|---|---|
| 0 | u32 | area (0–63) | 0 | f32 ×3 | start |
| 4 | f32 | yMin | 12 | f32 ×3 | end |
| 8 | f32 | yMax | 24 | f32 | radius |
| 12 | u32 | pointCount | 28 | u32 | flags: bit 0 `BIDIRECTIONAL` |
| 16 | u32 | first point (index into the polygon section) | 32 | u32 | area |
| 20 | 12 bytes | reserved | 36 | u32 | user id (comes back from the navmesh query) |
| | | | 40 | 8 bytes | reserved |

| Rules | | | Size classes | | |
|---|---|---|---|---|---|
| 0 | u32 ×2 | layer glob (`*`, `?`, `{a,b}`) | 0 | u32 ×2 | name (`medium`, `large`) |
| 8 | u32 | triangle flags (0 leaves the layer out) | 8 | f32 | radius |
| 12 | u32 | surface (0–255) | 12 | f32 | height |
| 16 | u32 | area (0–63) | 16 | f32 | climb |
| 20 | 4 bytes | reserved | 20 | 12 bytes | reserved |

**Settings (128 bytes).**

| Offset | Type | Field | Default |
|---|---|---|---|
| 0 | f32 | navmesh cell size | 0.15 |
| 4 | f32 | navmesh cell height | 0.1 |
| 8 | f32 | navmesh tile size (metres) | 32 |
| 12 | f32 | navmesh max slope (degrees) | 45 |
| 16 | f32 | occlusion cell size | 0.5 |
| 20 | f32 | occlusion yMin | 0.3 |
| 24 | f32 | occlusion yMax | 2.0 |
| 28 | u32 | occlusion enabled (0/1) | 1 |
| 32 | f32 ×3 | sun direction | (−0.4, −0.8, −0.45) |
| 44 | f32 ×3 | sun colour | (3, 2.9, 2.7) |
| 56 | f32 | exposure | 1 |
| 60 | f32 | environment specular | 1 |
| 64 | f32 | environment diffuse | 1 |
| 68 | u32 ×2 | environment map asset path (a cube map `.ktx2`), empty for none | |
| 76 | f32 | grid step (the editor's snapping) | 0.5 |
| 80 | 48 bytes | reserved (0) | |

**Component rows and values (24 bytes each, version 2).** One row per (object, component), and a
run of values per row; a column that has no value keeps the component's default. They are keyed by
*names* (the component's simple name, the column's name) so a renamed record field does not break a
level (D109; chapter [22](22-components.md) explains the schema they come from).

| Row | | | Value | | |
|---|---|---|---|---|---|
| 0 | u32 | object id | 0 | u32 ×2 | column name (`current`, `pos.x`) |
| 4 | u32 ×2 | component name | 8 | u32 | type: 0 `INT`, 1 `LONG`, 2 `FLOAT`, 3 `BOOL`, 4 `ENUM`, 5 `ENTITY`, 6 `ASSET` |
| 12 | u32 | first value (index into the values) | 12 | 4 bytes | reserved |
| 16 | u32 | value count | 16 | 8 bytes | payload: `i32` (`INT`, `BOOL` as 0/1, `ENTITY` = an object id), `i64` (`LONG`), `f32` (`FLOAT`), or a string reference (`ENUM`, `ASSET`) |
| 20 | 4 bytes | reserved | | | |

### 3.4 A real file: the crypt

`samples/iso-demo/assets/levels/crypt.jlevel` is the demo's level, written by the kit script
`kaykit.py` (still at version 1, so its header is 160 bytes and the sections start at 160). Its
first 48 bytes (`od -A d -t x1 crypt.jlevel | head -3`):

```
    0: 4a 4c 56 4c 01 00 00 00 0f 00 00 00 74 01 00 00
   16: 0f 00 00 00 0a 00 00 00 00 00 00 00 01 00 00 00
   32: 00 00 00 00 04 00 00 00 01 00 00 00 8e 01 00 00
```

Decode it with L04 §2: `4a 4c 56 4c` is the magic, read as the little-endian `u32`
`0x4C564C4A`. `01 00` is version 1, `00 00` flags. Then the counts: `0f 00 00 00` = 15 prefabs,
`74 01 00 00` = `0x174` = 372 placements, 15 lights, `0a` = 10 spawns, 0 triggers, 1 area, 0 links,
4 rules, 1 size class, and `8e 01 00 00` = `0x18E` = 398 as the next id: the 372 + 15 + 10 = 397
objects took ids 1 to 397. The offsets that follow (bytes 48 to 159) say the prefabs start at 160,
the placements at 400, the lights at 36,112, the spawns at 37,312; the file is 44,800 bytes.

Check the placement offset: 160 + 15 × 16 = 400, already a multiple of 16. The light offset: 400 +
372 × 96 = 400 + 35,712 = 36,112, and 36,112 / 16 = 2,257 exactly. The spawns: 36,112 + 15 × 80 =
37,312. When a section ends off a 16-byte boundary the writer pads: the string section starts at
38,112 and holds 6,678 bytes, 38,112 + 6,678 = 44,790, rounded up to 44,800.

The first placement, 96 bytes at offset 400:

```
  400: 01 00 00 00 00 00 00 00 f7 01 00 00 10 00 00 00
  416: 07 02 00 00 05 00 00 00 01 00 00 00 ff ff ff ff
  432: 00 00 c0 40 00 00 00 00 00 00 c0 40 00 00 00 00
  448: 00 00 00 00 00 00 00 00 00 00 80 3f 00 00 80 3f
  464: 00 00 80 3f 00 00 80 3f 00 00 00 00 00 00 00 00
  480: 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
```

- id 1, prefab 0 (`floor_tile_large` of `levels/dungeon.jscene`).
- name: offset `0x1F7` = 503, length 16: the 16 bytes at 38,112 + 503 in the string section,
  `floor_tile_large`. Layer: offset `0x207` = 519, length 5: `floor`.
- flags 1 = `STATIC`; collision `ff ff ff ff` = by layer.
- position: `00 00 c0 40` is the float `0x40C00000`. With L00 §7.2: sign 0, exponent
  `1000 0001` = 129, so $2^{129-127} = 4$, fraction `1000…` = 1.5: $1.5 \times 4 = 6.0$. The row
  reads (6, 0, 6).
- rotation `(0, 0, 0, 1)`: `00 00 80 3f` is `0x3F800000` = 1.0, the identity quaternion's $w$.
- scale (1, 1, 1), then 24 zero bytes.

The second placement (index 1, id 2, offset 496) is an unturned wall at (6, 0, 4). The one at index 2
(id 3) is a wall at (4, 0, 6) with rotation `(0, 0.7071068, 0, 0.7071068)`: a quarter turn
about Y, whose $y$ and $w$ are both $\sin 45° = \cos 45°$; its bits are `0x3F3504F4`. Light 0 is a
torch (`FLICKER` flag, bit 1 = 2) at (10, 2.8, 4.9) with colour (1, 0.55, 0.25), 8 candela, 9 m
range and the default cone angles 0.4 and 0.6 rad. The rules are `floor*` → flags 1 (walkable),
surface 1; `wall` → 30 (blocks line of sight, projectiles, movement and fades the camera: 2 + 4 + 8 +
16), surface 2; `prop` → 20 (4 + 16), surface 3; `*` → 0, which leaves anything else out of the
bake. The size class is `medium`, radius 0.4, height 1.8, climb 0.4. The first time the editor saves
this file it becomes version 2: the header grows to 192 bytes and every section moves by 32 (the
prefabs to 192, the placements to 432); exercise 2 has you compute the rest.

<details><summary>Check: a level has 3 prefabs and 10 placements. Where does the light section start in a version 2 file?</summary>

Prefabs at 192, 3 × 16 = 48 bytes, so the placements would start at 240, which is a multiple of 16
(240 = 15 × 16). Placements: 10 × 96 = 960, 240 + 960 = 1,200 = 75 × 16. The lights start at 1,200.
</details>

## 4. Tables you can edit: ids, order and versions

The runtime could read this file into plain arrays and be done. The editor needs three more
things from the in-memory store, and each one is a design decision.

### 4.1 Stable ids

Rows move. Delete placement 5 and placements 6, 7, 8 slide down to 5, 6, 7; undo the delete and they
slide back. Anything that remembers "placement 7" across that is wrong afterwards. So every
placement, light, spawn and trigger has an **id**: a `u32`, unique across the four kinds within the
level, allocated from the header's `nextId` and never reused. The selection is a set of ids; a
command stores the ids it touched; a trigger in the game refers to an id; the generated scene
bindings (`CryptLevel.PLACEMENT_ALTAR`, D110, chapter [22](22-components.md)) are ids. A row can be
removed and reinserted, sorted or shifted, and nothing dangles.

The one thing an id does not give you for free is the row: finding the row of id 150 by scanning
372 placements is $O(n)$ (L00 §2.8). §4.3 fixes that.

### 4.2 Rows keep their order

Two reasons the store never swaps rows (compare the sparse set of
[Lesson 3](lessons/03-data-layout.md), which fills a hole with the last row):

- **Collision rules match in order**, first match wins: `floor*` before `*`. Reordering them changes
  the bake.
- **Undo must put a deleted row back where it was.** The Outliner lists placements in row order;
  a designer who deletes the third wall and undoes expects it third again, not last.

So every table has `insertXxx(index, ...)` and `removeXxx(index)` that shift the tail with
`System.arraycopy`, and `addXxx` is `insert` at the end. An insert in the middle of 372 placements
copies the tail of seven arrays, a few kilobytes; it happens on a user action, a few times a
second at most. The loader is told *before* a row is removed (§17.3), because after the shift the
index no longer names the entity to destroy.

### 4.3 O(1) lookup by id (D91)

The gizmo resolves the primary selected object every frame; the Inspector resolves the whole
selection; `syncTransform(id)` during a drag resolves an id per object per frame. A linear scan
per object would add up: 400 placements × a selection of 50 × 60 frames is 1.2 million comparisons
a second for nothing. The store keeps an `IntIntMap` (an open-addressing hash map from `int` to
`int`, no boxing) from id to a packed `kind << 28 | index` (L00 §6.4: the kind in the top four bits,
the row in the low 28), updated by every insert and remove:

```
 bit:  31 ... 28 | 27 ................................ 0
       kind (4)  | row index (28 bits: up to 268 million rows)
```

`kindOf(id)` is `v >>> 28`; `indexOf(id, kind)` checks the top bits and returns `v & 0x0FFFFFFF`.
An insert at index $i$ re-indexes the rows from $i$ to the end (they each moved up by one), an
append touches one entry. The map also rejects a duplicate id on read for free. The alternatives
D91 names: linear scans (above), and a cache on the editor's side rebuilt whenever the level's
version changes, which would rebuild it on every frame of a drag, since a drag bumps the version
every frame.

Measured here (JDK 27, this container's 2 vCPUs; a `System.nanoTime` loop of ten million calls
after a million warm-up calls in a scratch program, not JMH, so repeated runs vary by about 15 %):
`placementIndex(id)` on the crypt's 372 placements takes about 6.6–6.8 ns, a hash, a probe and a
compare.

### 4.4 A version counter

Every mutator ends with `version++`. The panels remember the version they last read and re-read
their caches (text buffers, label arrays) only when it changed; the editor prunes dead ids from
the selection only when it changed; the Inspector uses it to tell its own edits (it bumps its own
`syncedVersion` right after pushing) from everyone else's (§25.2). The counter costs one `int` and
makes "did anything change since I last looked?" a comparison instead of a diff.

<details><summary>Check: why is <code>kind << 28 | index</code> not ambiguous for kind 0 (placements) and index 0?</summary>

The packed value is 0, and the map's missing value is −1 (`new IntIntMap(256, -1)`), so a lookup
can tell "placement 0" (0) from "absent" (−1). `kindOf` tests `v < 0` first for exactly this reason.
</details>

## 5. Placements become entity groups

### 5.1 A placement is a subtree with a transform on top

The world of chapter [01](01-architecture.md) instantiates a cooked scene as a tree of entities:
one per node, parents kept, a renderable for every mesh node. A placement is not a whole scene; it
is the *subtree* under one root node of the library scene, placed with its own transform. So the
world gained `World.instantiateNode(scene, rootNode, trs, ...)` (§16.1):

```
 library scene (levels/dungeon.jscene)          the world after placing "wall" at trs
   wall                                           root entity E0  ← trs (position, quaternion, scale)
     wall_mesh    (mesh, 1 m up)                    └─ E1 (wall_mesh, renderable, group = E0)
   table
     table_base   (mesh)
       table_top  (mesh, 1 m up, scale 2)
```

It marks the nodes under the root (a node is in the subtree if it is the root or its parent is),
creates a new **root entity** with the placement's transform, and instantiates the marked mesh
nodes below it, *flattened*: each mesh node becomes a direct child of the root with its transform
composed down the node chain (the table top at "1 m up, scale 2" relative to the base, relative to
the table). Flattening keeps a few transforms per placement instead of a bone hierarchy nobody
animates. Every child renderable's **group** is the root, so three calls act on a whole piece:
`destroyGroup(root)`, `setGroupTransform(root, trs)` and `setGroupHighlight(root, style)`, and
`groupOf(entity)` leads back from a picked renderable to its placement's root.

### 5.2 The loader keeps the mapping both ways

`LevelLoader` (§17) holds `placementRoot[i]` (row index → root entity) and `rootToPlacement`
(root entity → id, an `IntIntMap`). `load(level)` requests the prefab scenes through the asset
manager; `update()`, called every frame, instantiates each placement once its scene is ready and
returns `true` on the frame that finishes the level. The editing entry points are small because
the world already understands groups: `syncTransform(i)` is one `setGroupTransform`, `syncFlags(i)`
one `setGroupFlags` and one `setGroupHidden`, `placementInserted(i)`, `placementRemoving(i)` and
`placementReplaced(i)` shift `placementRoot` in step with the level's rows.

Lights, spawns and triggers have no entities. `extractLights` reads the level's light rows into the
render packet every frame (torches flicker from a sine of time and their index), so moving a light
is a change of three floats and nothing to sync.

### 5.3 Static means cached (D49)

One subtlety from chapter [06](06-renderer-architecture.md): renderables flagged `FLAG_STATIC` are
uploaded to the GPU once per `staticVersion` and culled there; the cached static set ignores
per-frame hiding. Two consequences for the editor:

- `LevelLoader.renderFlags` drops `FLAG_STATIC` when a placement is `HIDDEN`, so hiding goes
  through the per-frame path.
- `World.setGroupTransform`, `setGroupFlags`, `setGroupHighlight` and `setHighlight` bump
  `staticStructure` when they touch a static entity, and `World.extract` turns that into a new
  `staticVersion`, so the renderer rebuilds its static set once. Moving a static wall with the gizmo
  rebuilds the set every frame of the drag; that is what D49's contract costs, and it is an
  editor-only cost ("for editors and one-off placement, not per-frame motion", the Javadoc says).

## 6. Physics is derived from the level

### 6.1 The bake, from placements

The game needs a level's collision BVH (`.jcol`), one navmesh per agent size (`.jnav`) and the
occlusion grid for the fog of war (`.jocc`), all built in chapter
[11](11-physics-navigation-crowds.md) from a triangle soup with flags per triangle. Before M14 that
soup came from the level's glTF and a `physics.json`. Now it comes from the `.jlevel` (D90): the
cooker's `LevelImporter` (§18)

1. reads the level, copies the `.jlevel` to the output *unchanged* (the editor, the cooker and the
   game read the same bytes);
2. compiles the collision rules into globs;
3. for every placement with non-zero flags, loads the prefab's **source glTF** (a dependency: an
   edited piece re-bakes every level that places it), finds the root node, composes every mesh
   node under it with the placement's transform, and appends the triangles with the flags;
4. hands the soup to the same `PhysicsBake` the `physics.json` path uses: collision, then the
   navmeshes with the level's area volumes, links and size classes, then the occlusion grid if
   enabled.

A single-glTF level with a `physics.json` keeps working; the two paths share the bake.

### 6.2 Flags from layers

A placement's triangle flags come from, in order: `NO_COLLISION` → 0 (left out); its collision
override if not `COLLISION_BY_LAYER`; else the first rule whose glob matches its layer
(lower-cased); else 0. The crypt's rules (§3.4) make `floor*` walkable, `wall` a full blocker, `prop`
block movement and projectiles, and everything else nothing. A rule's surface (0–255, the footstep
sound, say) and navigation area (0–63) are packed into the flags with `TriFlags.withSurface` and
`withArea`, the layout of [jcol.md](../formats/jcol.md).

### 6.3 Children before parents

glTF does not promise that a parent node comes before its children in the node array, and the
library script writes a piece's *children first* (it appends the mesh nodes while walking the kit's
tree and the piece's root last). A loop that composes "parent matrix × local" in index order would
read an unresolved parent. `PhysicsBake.collectSubtree` resolves each mesh node's matrix relative
to the placement *recursively through its parent chain* and memoizes the result per node (§18.3).
The first cook of the demo's crypt produced "no collision triangles: no physics baked" until that
was fixed; the party walked through walls.

## 7. Edits are commands with explicit inverse data

### 7.1 The pattern

A **command** is an object with two methods, `apply` and `revert`, that change the level in
opposite directions, and an **undo stack** is a list of applied commands with a cursor:

```
 commands: [ Move #1 ][ Delete #7 ][ Rename #3 ][ Scale #1 ]
                                        ▲
                                     cursor = 3: entries 0..2 are applied, entry 3 was undone
 undo: revert entry 2, cursor = 2       redo: apply entry 3, cursor = 4
 push: discard entries ≥ cursor, apply the new one, append it
```

Three designs were on the table for what a command *stores* (D89):

- **Snapshot the level per command.** Simple and always right; $O(\text{level})$ memory and time
  per edit, 45 KB per keystroke for the crypt, far more for a real level.
- **Event sourcing**: store only the forward edits and rebuild the level from the start to undo.
  Undo would rebuild the world, and rebuilding is seconds for a level that loads scenes.
- **Explicit inverse data** (chosen): a command carries exactly what its two methods need. A move
  stores the ids it moved and their rows *before* and *after*; a delete stores the whole rows with
  their indices; a rename stores the old names. A drag of three placements costs 3 × 10 floats
  twice; undoing a delete of ten objects reinserts ten rows at the indices they had.

The rule that keeps the pattern honest: `apply` and `revert` must be safe to call any number of
times in alternation, because the stack applies a command when pushed, reverts it on undo and
applies it again on redo.

### 7.2 Why a drag is one entry

A gizmo drag changes the level sixty times a second. Two ways to make it one history entry:

- **Mutate and re-apply.** The viewport creates one `TransformCommand` with *before* = the current
  rows, pushes it on the first frame that actually changes something, then writes new *after*
  values and calls `apply` again every frame. The entry's *before* never changes; its *after*
  follows the mouse. Escape calls `cancelTop`, which reverts and *removes* the entry (unlike
  `undo`, which would leave it to redo).
- **Merge.** Typing `1`, `12`, `123` into a number field pushes three `SetFloatCommand`s with a
  `merge` flag. The stack asks the previous entry `mergeWith(next)`; the command decides whether
  the two are steps of one edit (same ids, same field) and, if so, keeps its own *before* and takes
  the newcomer's *after*. One Ctrl+Z goes back to the value before the first digit.

Both are in `UndoStack.push` (§19.2). Merging has one guard: a merged step must never fold into the
entry that is the *saved state*, or the dirty flag would lie (§7.3).

### 7.3 Dirty

"Unsaved changes" is a position, not a flag: the stack remembers the cursor at the last save,
`savedAt`, and the level is dirty when the cursor is elsewhere. Undo past the save is dirty; redo
back to it is clean. When a new command is pushed after an undo that went below `savedAt`, the
saved state is on the discarded redo tail and can never be reached again: `savedAt` becomes −1,
dirty until the next save. The same happens when the stack forgets its oldest entry past capacity
while the saved mark is at 0: that mark means "the state before entry 0", and once entry 0 is
forgotten that state can no longer be reached by undo.

### 7.4 Cold by design

Commands run on the game thread between frames and may allocate: they are user actions, a few per
second. The per-frame path (hover tests, declaring panels, drawing handles) allocates nothing. This
split is the whole performance design of the editor, and the tests hold both halves (§32).

## 8. From a pixel to a ray

Everything the viewport does with the mouse starts the same way: the pixel under the pointer
becomes a **ray** in the world, a start point $\mathbf{o}$ and a unit direction $\mathbf{d}$, the set of
points $\mathbf{o} + t\,\mathbf{d}$ for $t \ge 0$. Chapter [04](04-cameras-and-projection.md) derived
this in its section on picking; this section repeats the derivation on the editor's own numbers,
because every later formula in the chapter consumes its output.

### 8.1 The camera of the worked examples

`ViewportTest` sets up the camera that every number below uses: an `IsoCamera` (chapter 04) with its
defaults, yaw $\psi = \pi/4$, pitch $\theta = 55°$, vertical field of view $\mathrm{fov}_y = 30°$,
near plane $n = 0.5$ m, looking at the target $(4, 0, 0)$ from a distance of 16 m, in a window of
$W \times H = 800 \times 600$. The camera's forward vector is
$(-\sin\psi\cos\theta,\; -\sin\theta,\; -\cos\psi\cos\theta)$ and the eye sits behind the target
along it:

$$\mathbf{e} = (4, 0, 0) + 16\,(\sin\psi\cos\theta,\ \sin\theta,\ \cos\psi\cos\theta)
 = (4 + 16 \cdot 0.7071 \cdot 0.5736,\ 16 \cdot 0.8192,\ 16 \cdot 0.7071 \cdot 0.5736)
 = (10.489,\ 13.106,\ 6.489).$$

`IsoCamera.update(800f / 600)` computes this eye, the view matrix $V$, the reversed-Z projection $P$
(chapter 04: $z_{clip} = n$, $w_{clip} = -z_{view}$, so the stored depth is $n / s$ for a point at
distance $s$ in front of the eye, 1 on the near plane and 0 at infinity), their product $PV$ and its
inverse $(PV)^{-1}$.

### 8.2 Window coordinates to normalized device coordinates

The pointer arrives in window coordinates $(p_x, p_y)$, origin top-left, $y$ down, in UI logical
pixels (the `ViewportInput` has already multiplied by `Input.uiScale`, §21.6). The GPU's normalized
device coordinates run from $-1$ to $1$ in both axes with $y$ *up* (chapter 04). Invert the viewport
transform and flip $y$:

$$x_n = \frac{2 p_x}{W} - 1, \qquad y_n = 1 - \frac{2 p_y}{H}.$$

Check the corners: $(0, 0)$, the top-left pixel, gives $(-1, 1)$; $(400, 300)$, the center, gives
$(0, 0)$. One detail the editor has to get right: the scene is rendered **full-window** beneath the
panels (D88), so the division is by the *window* size, not by the viewport region's. A pixel in the
region at $(432.35, 326.50)$ is $x_n = 432.35 / 400 - 1 = 0.0809$, $y_n = 1 - 326.50 / 300 = -0.0883$.

### 8.3 Unprojecting two depths

A pixel is a whole line of world points, one per depth. The GPU went from a world point
$\mathbf{p}$ to clip coordinates $\mathbf{p}_c = PV\,(\mathbf{p}, 1)$ and divided by $w_c$ to get NDC.
Going back, take an NDC point $(x_n, y_n, z_n, 1)$, multiply by the inverse and divide by the
fourth coordinate again:

$$\mathbf{q}_h = (PV)^{-1} \begin{pmatrix} x_n \\ y_n \\ z_n \\ 1 \end{pmatrix}, \qquad
\mathbf{q} = \frac{(q_{h,x},\ q_{h,y},\ q_{h,z})}{q_{h,w}}.$$

Why it works: $(x_n, y_n, z_n, 1)$ is $\mathbf{p}_c / w_c$, and homogeneous coordinates do not care
about a common factor, so the inverse returns $(\mathbf{p}, 1) / w_c$, and the division by the fourth
coordinate removes the $1/w_c$. `IsoCamera.screenRay` unprojects depth 1 (the near plane) as the
origin and depth 0.5 (one metre in front of the eye, $n / 0.5 = 2n = 1$ m) as a second point, and
normalizes the difference. Not depth 0: that point is at infinity, its $w$ is 0 and the division
gives NaN (chapter 04).

**Worked example**, the center pixel $(x_n, y_n) = (0, 0)$. With $x_n = y_n = 0$ only the third and
fourth columns of $(PV)^{-1}$ matter; for the camera of §8.1 they are, to four places,
$\mathbf{c}_2 = (20.979,\ 26.213,\ 12.979,\ 2.0)$ and $\mathbf{c}_3 = (-0.4056,\ -0.8192,\ -0.4056,\ 0)$.
(Notice $\mathbf{c}_2 / 2$ is the eye and $\mathbf{c}_3$ is the forward vector: the center pixel's
line is the view axis.)

- Depth 1: $\mathbf{q}_h = \mathbf{c}_2 + \mathbf{c}_3 = (20.573,\ 25.394,\ 12.573,\ 2.0)$, so
  $\mathbf{q} = (10.287,\ 12.697,\ 6.287)$: half a metre in front of the eye along the forward
  vector, on the near plane.
- Depth 0.5: $\mathbf{q}_h = 0.5\,\mathbf{c}_2 + \mathbf{c}_3 = (10.084,\ 12.287,\ 6.084,\ 1.0)$, so
  $\mathbf{q} = (10.084,\ 12.287,\ 6.084)$: one metre in front.
- The ray: $\mathbf{o} = (10.287, 12.697, 6.287)$ and $\mathbf{d}$ = the normalized difference
  $= (-0.4056,\ -0.8192,\ -0.4056)$.

Where does it hit the ground $y = 0$? Solve $o_y + t\,d_y = 0$: $t = 12.697 / 0.8192 = 15.5$, and
$\mathbf{o} + 15.5\,\mathbf{d} = (4.0,\ 0.0,\ 0.0)$, the target. The 15.5 is no accident: the ray
starts on the near plane, half a metre in front of an eye that is 16 m from the target.

The handle pixel of §8.2, $(0.0809, -0.0883)$, gives $\mathbf{o} = (10.304, 12.690, 6.283)$ and
$\mathbf{d} = (-0.3712, -0.8321, -0.4120)$; §10.2 drags with it.

### 8.4 Going forward: projecting a point yourself

Box selection and the gizmo's hover test need the opposite direction: a world point to a pixel.
Four dot products with the rows of $PV$ give clip coordinates; if $w_c \le 0$ the point is behind
the camera and would project to a mirrored position, so it is refused; otherwise divide by $w_c$ and
apply the viewport transform with the $y$ flip:

$$p_x = r_x + \left(\tfrac{x_c}{w_c} \cdot 0.5 + 0.5\right) r_w, \qquad
p_y = r_y + \left(0.5 - \tfrac{y_c}{w_c} \cdot 0.5\right) r_h,$$

for a region at $(r_x, r_y)$ of size $(r_w, r_h)$. `GizmoMath.project` (§22.6) is this formula and
also returns the NDC depth $z_c / w_c$. `GizmoMathTest` checks it: a camera looking at $(3, 0, 5)$
projects its target to the center of a region at $(100, 50)$ of size $800 \times 600$, pixel
$(500, 350)$; a point 2 m above the target must land higher on screen (the test asserts only
$p_y < 350$; computed with the same camera, it lands at $p_y = 280.05$).

### 8.5 One pixel's worth of metres

A quantity the whole viewport is built on: how many metres one pixel covers at a given depth. The
perspective frustum is $2 s \tan(\mathrm{fov}_y / 2)$ metres tall at distance $s$ in front of the eye
(the half-height is the opposite side of a right triangle whose adjacent side is $s$ and whose
angle is half the field of view), and the window is $H$ pixels tall, so

$$\text{metersPerPixel}(s) = \frac{2\, s\, \tan(\mathrm{fov}_y / 2)}{H}.$$

For the example camera at the target's depth, $s = 16$: $2 \cdot 16 \cdot \tan 15° / 600 = 8.574 /
600 = 0.01429$ m, about 1.4 cm per pixel. It sizes the gizmo (90 pixels whatever the distance:
$L = 90 \cdot 0.01429 = 1.286$ m here), the marker spheres (10 pixels) and the hit tolerances (8
pixels in screen space, or the same number converted at the handle's depth). For the orthographic
camera the frustum is `orthoHeight` tall everywhere, so a pixel is `orthoHeight / H` metres at any
depth; `GizmoMathTest` checks that `metersPerPixel × H` is the frustum height.

## 9. Picking: what is under the pointer

A click casts the ray of §8 and asks two questions: did it hit a marker (a light, a spawn, a
trigger), and did it hit a placement's geometry? The closest hit wins.

### 9.1 Markers are spheres of a fixed pixel size

Lights, spawns and triggers have no mesh in the world; the viewport draws them as overlay lines
(§12) and picks them as **spheres** whose radius is 10 pixels' worth of metres at their depth: the
marker radius is `MARKER_PIXELS × metersPerPixel(|p − eye|)`. The torch of the test level at
$(1, 2, 3)$ is $15.02$ m from the eye, so its sphere has radius $10 \cdot 2 \cdot 15.02 \cdot \tan
15° / 600 = 0.134$ m: a click within 10 pixels of it hits, near or far.

The ray–sphere test from chapter [11](11-physics-navigation-crowds.md), `Intersect.raySphere`:
with $\mathbf{m} = \mathbf{o} - \mathbf{c}$, the point $\mathbf{o} + t\mathbf{d}$ lies on the sphere of
radius $r$ when $|\mathbf{m} + t\mathbf{d}|^2 = r^2$, a quadratic $t^2 + 2(\mathbf{m}\cdot\mathbf{d})\,t +
(|\mathbf{m}|^2 - r^2) = 0$ for a unit $\mathbf{d}$; the first root is $t = -b - \sqrt{b^2 - c}$ with
$b = \mathbf{m}\cdot\mathbf{d}$ and $c = |\mathbf{m}|^2 - r^2$. The code returns 0 when the origin is
inside ($c \le 0$), a miss when the sphere is behind ($b > 0$) or the discriminant is negative.

### 9.2 Placements: the ray through the inverse transform (D93)

A placement's renderables each have a mesh with a local axis-aligned bounding box (`GpuMesh.bounds`,
six floats the cooker computed and the runtime keeps on the CPU for this) and a world transform
$M$, a row-major 3×4 affine matrix of chapter [02](02-math-foundations.md). The obvious test,
transform the box's eight corners into the world and test a world-aligned box around them, picks a
turned wall by a sloppy box: a 4 m wall half a metre thick turned 45° gets a box 3.2 m square
($(4 + 0.5)/\sqrt 2$; worked below). GPU id buffers (render
every object's id into a texture and read the pixel back) are exact but a frame late and cost a
pass. D93 chose the third way: **bring the ray into the mesh's space** with the inverse transform
and test the local box with the slab method of chapter [11](11-physics-navigation-crowds.md).

Why the parameter is the same in both spaces. Write $M$ as a linear part $A$ (3×3: rotation and
scale) and a translation $\mathbf{t}$, so $M\mathbf{p} = A\mathbf{p} + \mathbf{t}$ and
$M^{-1}\mathbf{p} = A^{-1}(\mathbf{p} - \mathbf{t})$. A point of the ray maps to

$$M^{-1}(\mathbf{o} + t\,\mathbf{d}) = A^{-1}(\mathbf{o} - \mathbf{t}) + t\,A^{-1}\mathbf{d}
 = \mathbf{o}' + t\,\mathbf{d}',$$

a ray in local space with origin $\mathbf{o}' = M^{-1}\mathbf{o}$ (a point: translation applied)
and direction $\mathbf{d}' = A^{-1}\mathbf{d}$ (a direction: no translation), **with the same $t$**,
as long as $\mathbf{d}'$ is *not renormalized*. If the mesh is scaled 2×, $\mathbf{d}'$ is half as
long and the slab test's $t$ is still in world metres. Normalize it and the $t$ comes back in local
units. `AffineOps.transformPoint` and `transformDirection` are exactly these two maps.

**Worked example.** The crypt's placement at index 2 (id 3) is a wall at $(4, 0, 6)$ turned 90° about Y
(quaternion $(0, 0.7071, 0, 0.7071)$). Chapter 03's quaternion-to-matrix formula gives the rotation
$R$ with $R_{00} = 1 - 2(y^2 + z^2) = 0$, $R_{02} = 2(xz + wy) = 1$, $R_{20} = 2(xz - wy) = -1$, $R_{22} = 0$:
the columns of $R$ are where the local axes go, so local $+X$ points along world $-Z$ and local
$+Z$ along world $+X$; read the other way, the world's $+X$ is local $+Z$ and the world's $+Z$ is
local $-X$. With unit scale the rows of $M$ are

$$M = \begin{pmatrix} 0 & 0 & 1 & 4 \\ 0 & 1 & 0 & 0 \\ -1 & 0 & 0 & 6 \end{pmatrix}, \qquad
M^{-1} = \begin{pmatrix} 0 & 0 & -1 & 6 \\ 0 & 1 & 0 & 0 \\ 1 & 0 & 0 & -4 \end{pmatrix}.$$

(`AffineOps.invert` computes the inverse of the 3×3 by cofactors and then $-A^{-1}\mathbf{t}$ for
the last column; check: $M^{-1}$ applied to $(4, 0, 6)$ gives $(0, 0, 0)$.) Say the wall's local box
is $[-2, 0, -0.25]$ to $[2, 2, 0.25]$: 4 m long along local X, 2 m tall, half a metre thick. A ray
straight down from $(4, 5, 7.5)$:

- $\mathbf{o}' = M^{-1}(4, 5, 7.5) = (0 \cdot 4 + 0 \cdot 5 - 7.5 + 6,\ 5,\ 4 - 4) = (-1.5, 5, 0)$,
  $\mathbf{d}' = A^{-1}(0, -1, 0) = (0, -1, 0)$.
- Slabs: $x$ enters at $t$ for $-1.5 \in [-2, 2]$ (no constraint from a zero direction component:
  the code's division by zero gives $\pm\infty$ and the min/max logic keeps the other slabs), $y$
  enters at $(2 - 5) / (-1) = 3$ and leaves at $5$, $z = 0 \in [-0.25, 0.25]$. Entry $t = 3$: the
  world hit is $(4, 2, 7.5)$, the top of the wall. The wall's length (local $X$) runs along world
  $-Z$ after the turn, so world $z = 7.5$, 1.5 m on the $+Z$ side of the center, is local $x = -1.5$:
  on the wall.
- The same ray at $(5.5, 5, 6)$ gives $\mathbf{o}' = (0, 5, 1.5)$: local $z = 1.5$ is outside
  $[-0.25, 0.25]$, a miss. For a quarter turn a world-aligned box would agree, so turn the wall
  45° instead (quaternion $(0, 0.3827, 0, 0.9239)$, the half-angle sine and cosine of 22.5°). Its
  world-aligned box is $x \in [2.41, 5.59]$, $z \in [4.41, 7.59]$: 3.18 m square, 10.1 m² around a
  wall whose footprint is $4 \times 0.5 = 2$ m², so four clicks in five inside that box miss the
  real wall. A ray down at $(5, 5, 7)$ is inside the sloppy box, but in the wall's space its origin
  is $(0, 5, 1.414)$, 1.4 m off the wall's thin side: the real test says miss. (A ray at $(5, 5, 5)$,
  on the wall, gives local $(1.414, 5, 0)$ and $t = 3$.)
- Scale the wall 2× along its local X (`sx = 2`): the row $M_2$ becomes $(-2, 0, 0, 6)$ and the
  inverse row $(0, 0, -0.5, 3)$. A ray from $(4, 1, 12)$ toward $-Z$ has $\mathbf{o}' = (-3, 1, 0)$
  and $\mathbf{d}' = (0.5, 0, 0)$, half a unit long; it enters the box at local $x = -2$ when $-3 +
  0.5\,t = -2$, $t = 2$, and in the world $12 - 2 = 10$ is the near end of the 8 m wall (local $x$
  from $-2$ to $2$, doubled, is world $z$ from 2 to 10). With a normalized $\mathbf{d}'$ the test
  would say $t = 1$, wrong by the scale.

Before the inverse, `World.pick` tests the renderable's bounding *sphere* (center and radius from
the mesh, transformed and scaled by the largest axis scale): a few multiplies that reject almost
everything. For the center-pixel ray of §8.3 and the wall's sphere (center $(4, 1, 6)$, radius 2.25),
$\mathbf{m} = (6.29, 11.70, 0.29)$, $c = |\mathbf{m}|^2 - r^2 = 171.4$, $b = \mathbf{m}\cdot\mathbf{d} =
-12.25$, $b^2 - c = -21.3 < 0$: no inverse, no slabs. The loop is $O(\text{renderables})$ per click;
the crypt's 1,041 renderables (the count TASKS M14-03 recorded for the crypt loaded through
`LevelLoader`) cost microseconds.

From the entity, `World.groupOf` gives the placement's root and `LevelLoader.placementOf` the id.
Shift adds to the selection, Ctrl toggles, a plain click replaces.

### 9.3 Box selection

A press that moves more than four pixels on empty space becomes a **box selection**: on release,
every object's position is projected (§8.4) and the ones inside the rectangle are selected
(markers only if they are shown). The rubber band is a floating UI box the viewport declares with
`noHit()`, so it never steals the pointer. Objects behind walls are taken too; exercise 4 fixes
that.

## 10. The gizmo geometry, derived

A **gizmo** is the set of handles drawn at the primary selected object: in translate mode three
axis arrows and three plane squares, in rotate mode three rings, in scale mode three axis cubes
and a center cube. Dragging a handle needs a way to turn the mouse ray into one number: a distance
along an axis, a point on a plane, an angle about an axis, a scale factor. Each is a small piece
of geometry in `GizmoMath` (§22), checked by `GizmoMathTest` against hand-computed cases. The
symbols: the gizmo center $\mathbf{c}$, an axis as a unit vector $\mathbf{a}$ (the world's $X$, $Y$ or
$Z$, D94), the mouse ray $\mathbf{o} + s\,\mathbf{d}$ with $\mathbf{d}$ unit.

### 10.1 The closest point on an axis to the mouse ray

An axis handle is the line $\mathbf{c} + t\,\mathbf{a}$. The mouse ray almost never meets it exactly
(two lines in space miss each other), so "where on the axis is the mouse?" means: the point of the
axis closest to the ray. Write $\mathbf{w} = \mathbf{o} - \mathbf{c}$ and minimize the squared
distance between a point of the ray and a point of the axis,

$$f(s, t) = |\mathbf{w} + s\,\mathbf{d} - t\,\mathbf{a}|^2.$$

At the minimum both partial derivatives vanish. Differentiating a squared length gives twice the
vector dotted with the derivative of the vector:

$$\frac{\partial f}{\partial s} = 2\,(\mathbf{w} + s\mathbf{d} - t\mathbf{a})\cdot\mathbf{d} = 0
 \;\Rightarrow\; \mathbf{w}\cdot\mathbf{d} + s - t\,(\mathbf{a}\cdot\mathbf{d}) = 0,$$
$$\frac{\partial f}{\partial t} = -2\,(\mathbf{w} + s\mathbf{d} - t\mathbf{a})\cdot\mathbf{a} = 0
 \;\Rightarrow\; \mathbf{w}\cdot\mathbf{a} + s\,(\mathbf{a}\cdot\mathbf{d}) - t = 0,$$

using $\mathbf{d}\cdot\mathbf{d} = \mathbf{a}\cdot\mathbf{a} = 1$. Call $b = \mathbf{a}\cdot\mathbf{d}$.
From the first, $s = t\,b - \mathbf{w}\cdot\mathbf{d}$; substitute into the second:

$$\mathbf{w}\cdot\mathbf{a} + (t\,b - \mathbf{w}\cdot\mathbf{d})\,b - t = 0
 \;\Rightarrow\; t\,(1 - b^2) = \mathbf{w}\cdot\mathbf{a} - b\,(\mathbf{w}\cdot\mathbf{d})
 \;\Rightarrow\; t = \frac{\mathbf{w}\cdot\mathbf{a} - b\,(\mathbf{w}\cdot\mathbf{d})}{1 - b^2}.$$

The denominator $1 - b^2 = 1 - \cos^2\alpha = \sin^2\alpha$, where $\alpha$ is the angle between
the ray and the axis. When you look straight down an axis it goes to zero, the handle is useless
(the axis is a point on screen), and the gizmo hides that axis (`hitTest` skips an axis whose
$|b| > 0.995$; `closestAxisParam` returns a fallback below $10^{-4}$). In code:

```java
// modules/editor/src/main/java/dev/jeddy/editor/GizmoMath.java
float b = ax * dx + ay * dy + az * dz;
float denom = 1 - b * b;
if (denom < 1e-4f) {
    return fallback;
}
float wa = wx * ax + wy * ay + wz * az;
float wd = wx * dx + wy * dy + wz * dz;
return (wa - b * wd) / denom;
```

**Worked example**, the first drag of `ViewportTest.translateAlongAnAxisHandle`. The wall is the
primary at $\mathbf{c} = (4, 0, 0)$; the X handle is grabbed halfway along the gizmo, at
$\mathbf{c} + 0.5L\,\mathbf{a}$ with $L = 1.286$ (§8.5), which projects to pixel $(432.35, 326.50)$;
the ray through that pixel is the one of §8.3, $\mathbf{o} = (10.304, 12.690, 6.283)$, $\mathbf{d} =
(-0.3712, -0.8321, -0.4120)$, and $\mathbf{a} = (1, 0, 0)$:

- $\mathbf{w} = \mathbf{o} - \mathbf{c} = (6.304, 12.690, 6.283)$;
- $b = \mathbf{a}\cdot\mathbf{d} = -0.3712$, $1 - b^2 = 0.8622$;
- $\mathbf{w}\cdot\mathbf{a} = 6.304$; $\mathbf{w}\cdot\mathbf{d} = 6.304 \cdot (-0.3712) + 12.690 \cdot
  (-0.8321) + 6.283 \cdot (-0.4120) = -2.340 - 10.559 - 2.589 = -15.488$;
- $t_0 = (6.304 - (-0.3712)(-15.488)) / 0.8622 = (6.304 - 5.749) / 0.8622 = 0.643$.

And $0.5\,L = 0.643$: the foot of the common perpendicular is where the handle was grabbed, as it
must be when the ray passes through the axis. The test then moves the mouse to the projection of
$\mathbf{c} + 1.5L\,\mathbf{a}$; the same computation gives $t = 1.929$, a delta of $t - t_0 = 1.286 =
L$, and every selected object moves by $(t - t_0)\,\mathbf{a}$: the wall ends at $x = 4 + L$, which
is what the test asserts. `GizmoMathTest.closestAxisParamIsTheFootOfTheCommonPerpendicular` checks
four more cases by hand: a vertical ray at $x = 3$ gives $t = 3$ whether it crosses the axis or
passes 5 m beside it; a diagonal ray from $(0, 10, 0)$ along $(1, -1, 0)/\sqrt 2$ crosses at $t =
10$; moving the axis origin to $x = 4$ makes it 6; a ray parallel to the axis returns the fallback.

A **scale** drag along an axis uses the *ratio* $t / t_0$ as the factor: grab the handle halfway out
($t_0 = 0.5L$) and drag to its end ($t = L$) for ×2, which `scaleAlongAnAxisAndUniformly` asserts.

### 10.2 A plane hit

The plane squares (move in a plane), the rotation rings (measure an angle in a plane) and
camera panning all need the point where the ray meets a plane. A plane through $\mathbf{c}$ with
unit normal $\mathbf{n}$ is the set of points $\mathbf{p}$ with $\mathbf{n}\cdot(\mathbf{p} - \mathbf{c})
= 0$, or $\mathbf{n}\cdot\mathbf{p} + d_0 = 0$ with $d_0 = -\mathbf{n}\cdot\mathbf{c}$ (chapter 04's
plane form). Substitute the ray and solve for $s$:

$$\mathbf{n}\cdot(\mathbf{o} + s\,\mathbf{d}) + d_0 = 0 \;\Rightarrow\;
 s = -\frac{\mathbf{n}\cdot\mathbf{o} + d_0}{\mathbf{n}\cdot\mathbf{d}} = \frac{\mathbf{n}\cdot(\mathbf{c} - \mathbf{o})}{\mathbf{n}\cdot\mathbf{d}}.$$

If $\mathbf{n}\cdot\mathbf{d} = 0$ the ray runs parallel to the plane (no hit); if $s < 0$ the plane
is behind the ray's start. `Intersect.rayPlane` does the division and both rejections;
`GizmoMath.rayPlanePoint` computes $d_0$ from $\mathbf{c}$ and writes the point. `GizmoMathTest`:
the ground plane $y = 2$ (normal $(0, 1, 0)$ through $(0, 2, 0)$, so $d_0 = -2$) hit by a ray down
from $(1, 10, 5)$ gives $s = -(10 - 2) / (-1) = 8$ and the point $(1, 2, 5)$; a ray along the plane
and one pointing away both miss.

A **plane drag** is the difference of two such points, the one at the press and the one now; the
component along the normal is zero by construction, so a drag on the XZ square never changes $y$.
`translateOnAPlaneHandleMovesTwoObjectsTogether` presses on the XZ square of a torch at $(1, 2, 3)$
and moves the mouse to the projection of a point $(2, 0, -1)$ further: the torch ends at $(3, 2, 2)$
with $y$ untouched, and a wall selected with it moves by the same $(2, 0, -1)$.

### 10.3 A signed angle about an axis

The rotate ring about $\mathbf{a}$ compares the **lever** at the press, $\mathbf{v}_0 = \mathbf{p}_0 -
\mathbf{c}$, with the lever now, $\mathbf{v}_1 = \mathbf{p} - \mathbf{c}$, both plane hits on the ring's
plane and so both perpendicular to $\mathbf{a}$. The dot product gives the cosine of the angle
between them, $\mathbf{v}_0\cdot\mathbf{v}_1 = |\mathbf{v}_0||\mathbf{v}_1|\cos\theta$, but not its sign.
The cross product gives the sine *with* a sign: $\mathbf{v}_0 \times \mathbf{v}_1$ has length
$|\mathbf{v}_0||\mathbf{v}_1|\sin\theta$ and points along $+\mathbf{a}$ for a right-handed turn about
$\mathbf{a}$ and along $-\mathbf{a}$ otherwise, so $(\mathbf{v}_0 \times \mathbf{v}_1)\cdot\mathbf{a} =
|\mathbf{v}_0||\mathbf{v}_1|\sin\theta$. Then

$$\theta = \operatorname{atan2}\big((\mathbf{v}_0 \times \mathbf{v}_1)\cdot\mathbf{a},\; \mathbf{v}_0\cdot\mathbf{v}_1\big),$$

and `atan2` takes care of all four quadrants and of the common factor $|\mathbf{v}_0||\mathbf{v}_1|$.

**Worked example**, `rotateAroundTheYRing`: the Y ring passes through $\mathbf{c} + L\,\mathbf{x}$; the
test presses there and drags round to $\mathbf{c} + L\,\mathbf{z}$. With $\mathbf{v}_0 = (L, 0, 0)$,
$\mathbf{v}_1 = (0, 0, L)$, $\mathbf{a} = (0, 1, 0)$:
$\mathbf{v}_0 \times \mathbf{v}_1 = (0 \cdot L - 0 \cdot 0,\ 0 \cdot 0 - L \cdot L,\ L \cdot 0 - 0 \cdot 0)
= (0, -L^2, 0)$, dotted with $\mathbf{a}$: $-L^2 = -1.654$; $\mathbf{v}_0\cdot\mathbf{v}_1 = 0$;
$\theta = \operatorname{atan2}(-1.654, 0) = -\pi/2$. From $+X$ to $+Z$ about $+Y$ is $-90°$ because
$\mathbf{x} \times \mathbf{z} = -\mathbf{y}$: a right-handed turn about $+Y$ takes $+Z$ toward $+X$
(that is the engine's yaw convention, which `GizmoMathTest.quaternionsComposeAndConvertToEuler`
also checks: a 30° yaw rotates $+Z$ to $(\sin 30°, 0, \cos 30°) = (0.5, 0, 0.866)$). The test expects
the wall's quaternion to become $(0, \sin(-45°), 0, \cos(-45°)) = (0, -0.7071, 0, 0.7071)$, the
axis–angle quaternion of §11.1 for $-90°$ about $Y$.

### 10.4 Snapping

**Snapping** rounds to the nearest multiple of a step, $\text{snap}(v, \delta) = \operatorname{round}(v /
\delta)\,\delta$, and the editor applies it to the *delta* of a drag, not to the absolute value, so a
drag that starts off-grid still moves by whole steps; to the angle; and to the scale factor. The
translate step is the level's grid step (0.5 m in the crypt), the rotate step 15°, the scale step
0.25 by default (`EditorSettings`). **X** toggles snapping, Ctrl inverts it for one drag
(`snap = s.snap != ctrl`, an exclusive-or on booleans). Examples from `GizmoMathTest.snapRoundsToTheNearestStep`:
$\text{snap}(1.6, 0.5) = \operatorname{round}(3.2) \cdot 0.5 = 1.5$; $\text{snap}(-1.8, 1) = -2$;
$\text{snap}(0.24, 0.5) = 0$; a step of 0 leaves the value alone; $37°$ at a 15° step is $30°$. In
the drag of §10.1 the delta $L = 1.286$ snapped to 0.5 is $\operatorname{round}(2.57) \cdot 0.5 = 1.5$,
and in the rotation of §10.3 a $-40°$ turn becomes $\operatorname{round}(-2.67) \cdot 15° = -45°$
(`Math.round` rounds half up toward $+\infty$; $-2.67$ rounds to $-3$).

### 10.5 The center cube: a ratio of screen distances

Uniform scaling has no axis. The center cube uses the distance in *pixels* from the pointer to the
gizmo center's projection: $\text{factor} = |\mathbf{p} - \mathbf{c}_{px}| / |\mathbf{p}_0 - \mathbf{c}_{px}|$,
with the start distance clamped to at least one pixel so the division is safe. Press 3 pixels from
the center and drag to 9: ×3 on every axis, the second half of `scaleAlongAnAxisAndUniformly`.

### 10.6 Hover tests

Before a drag the viewport must know which handle is under the pointer. Three tests, in the order
the code runs them:

- **Plane squares** (translate mode): intersect the ray with the plane of each square (normal
  $\mathbf{a}_n$); the hit's coordinates along the plane's two axes, $(\mathbf{p} - \mathbf{c})\cdot
  \mathbf{a}_u$ and $\cdot\,\mathbf{a}_v$, must both lie in $[0.3L, 0.65L]$, the square. A hit beats the
  axes because the squares are smaller targets.
- **Rings** (rotate mode): intersect with the ring's plane; the hit's distance from the center
  compared with $L$, converted to pixels by dividing by `metersPerPixel`, must be within the 8-pixel
  tolerance. A ring seen edge-on ($|\mathbf{a}\cdot\mathbf{d}| < 0.08$) is a line and is skipped.
- **Axes**: the screen distance from the pointer to the projected segment from $\mathbf{c}$ to
  $\mathbf{c} + L\,\mathbf{a}$ (`pointSegmentDistance`: project the pointer onto the segment, clamp
  the parameter to $[0, 1]$, measure), within 8 pixels; the closest axis wins; an axis seen end-on
  is skipped.

The scale mode's center cube is tested first, within 12 pixels of the center.

**Worked numbers, a plane square.** `ViewportTest.translateOnAPlaneHandleMovesTwoObjectsTogether`
puts the gizmo on the torch at $\mathbf{c} = (1, 2, 3)$. Its depth along the view direction is
14.36 m, so a pixel there is $2 \cdot 14.36 \cdot \tan 15° / 600 = 0.01283$ m and $L = 90 \cdot
0.01283 = 1.154$ m. The test presses on the projection of $\mathbf{c} + (0.45L, 0, 0.45L)$, pixel
$(59.25, 256.20)$, whose ray is $\mathbf{o} = (10.168, 12.708, 6.383)$, $\mathbf{d} = (-0.6151,
-0.7617, -0.2037)$. The loop tries the squares in the order $n = 0, 1, 2$:

| $n$ (normal) | hit on the plane | along $\mathbf{a}_u$ | along $\mathbf{a}_v$ | inside $[0.3L, 0.65L]^2$? |
|---|---|---|---|---|
| 0 ($X$; $u = Y$, $v = Z$) | $(1, 1.357, 3.348)$ | $-0.643 = -0.557L$ | $0.348 = 0.301L$ | no |
| 1 ($Y$; $u = Z$, $v = X$) | $(1.520, 2, 3.520)$ | $0.520 = 0.450L$ | $0.519 = 0.450L$ | yes: `PLANE_Y` |
| 2 ($Z$) | not reached | | | |

The X square's $v$ coordinate falls inside its range by a hair (0.301 L), which is why both
coordinates are tested.

**Worked numbers, a ring.** `rotateAroundTheYRing` puts the gizmo on the wall at $(4, 0, 0)$ ($L =
1.286$ m, 0.01429 m per pixel) and presses on the projection of $\mathbf{c} + L\,\mathbf{x}$, pixel
$(465.78, 353.89)$; the ray is $\mathbf{o} = (10.321, 12.683, 6.280)$, $\mathbf{d} = (-0.3352,
-0.8443, -0.4180)$:

| Ring (normal) | facing $\lvert\mathbf{a}\cdot\mathbf{d}\rvert$ | hit | distance from $\mathbf{c}$ | $\lvert \text{dist} - L\rvert$ in pixels |
|---|---|---|---|---|
| $X$ | 0.335 | $(4, -3.240, -1.604)$ | 3.615 | 163: no |
| $Y$ | 0.844 | $(5.2862, 0, 0)$ | 1.28617 | 0.0007 |
| $Z$ | 0.418 | $(5.2862, 0.0001, 0)$ | 1.28619 | 0.0026 |

The point $\mathbf{c} + L\,\mathbf{x}$ lies on *two* rings, the Y ring (in the XZ plane) and the Z ring
(in the XY plane), so both are within tolerance; the code keeps the smaller error, and the Y ring
wins by float rounding (both errors are 0 in exact arithmetic). A designer clicking where two rings
cross gets whichever is a fraction of a pixel closer; the test relies on that rounding (§33). Five
pixels to the right of that point the Y ring's error is 3.5 pixels, still a hit: on screen the ring
is an ellipse, and a horizontal pixel moves the hit along the ring's radius by less than a pixel's
worth.

## 11. Turning and scaling a selection about a pivot

### 11.1 World-axis gizmos about the primary (D94)

The gizmo sits on the **primary** selected object (the last one clicked) and its axes are the
world's $X$, $Y$, $Z$ whatever the object's own orientation. With several objects selected, the
operations act on all of them *as a group* about the **pivot**, the primary's position at the start
of the drag:

- **Translation** adds one delta $\Delta$ to every object's position.
- **Rotation** turns every object's *offset from the pivot* by the drag's quaternion and composes
  the same turn into every object's own orientation: $\mathbf{p}' = \mathbf{c} + q\,(\mathbf{p} -
  \mathbf{c})\,q^{-1}$ and $q' = q\,q_{old}$.
- **Scale** multiplies every offset from the pivot and every object's own size per axis.

A **local-space** mode (axes aligned with the primary's rotation) was left as an exercise (exercise
1); the math below does not care which three unit vectors it gets.

The drag's turn is the **axis–angle quaternion** of chapter [03](03-rotations-and-transforms.md):
a rotation of $\theta$ about unit $\mathbf{a}$ is

$$q_a = \left(\mathbf{a}\,\sin\tfrac{\theta}{2},\ \cos\tfrac{\theta}{2}\right) = (a_x s,\ a_y s,\ a_z s,\ c),
\quad s = \sin\tfrac{\theta}{2},\ c = \cos\tfrac{\theta}{2},$$

stored as $(x, y, z, w)$. For the $-90°$ turn about $Y$ of §10.3, $\theta / 2 = -45°$ and $q_a =
(0, -0.7071, 0, 0.7071)$.

### 11.2 The quaternion product and "world turn, then the old"

Quaternion products apply **right to left**: $q_a\,q$ means "first $q$, then $q_a$". A world-axis
gizmo promises that the drag's turn happens in world space *after* the object's own rotation, so
the new orientation is $q_a\,q_{old}$: the object's old rotation, then the drag's turn. The product
formula chapter 03 derived, with $a = (a_x, a_y, a_z, a_w)$ applied second and $b$ first:

$$ab = \big(a_w b_x + a_x b_w + a_y b_z - a_z b_y,\ \
 a_w b_y - a_x b_z + a_y b_w + a_z b_x,\ \
 a_w b_z + a_x b_y - a_y b_x + a_z b_w,\ \
 a_w b_w - a_x b_x - a_y b_y - a_z b_z\big).$$

`GizmoMath.mul(a, b)` is this, four lines. The result is renormalized (`GizmoMath.normalize`),
because a product of unit quaternions in `float` drifts from unit length by a bit per operation
and a drag does sixty of them a second: the rotation would slowly become a rotation plus a scale.

Rotating a *vector* by a quaternion uses the shortcut $\mathbf{t} = 2\,\mathbf{q}_{xyz} \times \mathbf{v}$,
$\mathbf{v}' = \mathbf{v} + w\,\mathbf{t} + \mathbf{q}_{xyz} \times \mathbf{t}$ (chapter 03 derives it from
$q\,\mathbf{v}\,q^{-1}$), which is `GizmoMath.rotate`. Check with the test's turn: $\mathbf{v} = (1, 0,
0)$, $\mathbf{q}_{xyz} = (0, -0.7071, 0)$, $w = 0.7071$: $\mathbf{t} = 2\,(0, -0.7071, 0) \times (1, 0, 0) =
2\,(0 \cdot 0 - 0 \cdot 0,\ 0 \cdot 1 - 0 \cdot 0,\ 0 \cdot 0 - (-0.7071) \cdot 1) = (0, 0, 1.4142)$;
$\mathbf{q}_{xyz} \times \mathbf{t} = (0, -0.7071, 0) \times (0, 0, 1.4142) = (-0.7071 \cdot 1.4142 - 0,\ 0,\ 0)
= (-1, 0, 0)$; $\mathbf{v}' = (1, 0, 0) + 0.7071 \cdot (0, 0, 1.4142) + (-1, 0, 0) = (0, 0, 1)$. $+X$ went to
$+Z$, as the ring was dragged.

### 11.3 Each kind turns in its own way

Only placements have a quaternion. The viewport's `writeRotate` (§23.7) switches on the kind:

- a **placement**: `qa * before`, normalized;
- a **spawn** or a **trigger** has only a yaw, so a turn about $Y$ adds `axis[1] * angle` to it
  (`axis[1]` is 1 for the Y ring, 0 for the X and Z rings: a turn about X or Z moves it about the
  pivot but leaves it facing where it was);
- a **light**'s direction is a vector and is simply rotated.

Scale (`writeScale`) is the same shape with multiplication: offsets from the pivot and, where the
kind has a size (`LevelObjects.scaleOffset`: a placement's scale at floats 7–9, a trigger's extents
at 3–5), the size, per axis.

### 11.4 Euler angles for the Inspector, derived

Designers do not type quaternions. The Inspector edits a placement's rotation as **yaw** $\psi$
(about $Y$), **pitch** $\theta$ (about $X$) and **roll** $\phi$ (about $Z$) in degrees, composed as

$$q = q_y(\psi)\,q_x(\theta)\,q_z(\phi),$$

roll applied first, then pitch, then yaw (right to left). `GizmoMath.fromEuler` builds the three
axis–angle quaternions and multiplies twice. For yaw 30°, pitch 20°, roll 0: $q_y = (0, 0.2588,
0, 0.9659)$, $q_x = (0.1736, 0, 0, 0.9848)$, and the product formula of §11.2 with $a = q_y$,
$b = q_x$ gives

$$q = (0.9659 \cdot 0.1736,\ 0.2588 \cdot 0.9848,\ -0.2588 \cdot 0.1736,\ 0.9659 \cdot 0.9848)
 = (0.1677,\ 0.2549,\ -0.0449,\ 0.9513),$$

which `quaternionsComposeAndConvertToEuler` compares against the hand-composed product.

Going back is the interesting direction. Write the rotation matrix of the composition. With $c_\psi
= \cos\psi$, $s_\psi = \sin\psi$ and so on, the single-axis matrices are

$$R_y = \begin{pmatrix} c_\psi & 0 & s_\psi \\ 0 & 1 & 0 \\ -s_\psi & 0 & c_\psi \end{pmatrix},\
R_x = \begin{pmatrix} 1 & 0 & 0 \\ 0 & c_\theta & -s_\theta \\ 0 & s_\theta & c_\theta \end{pmatrix},\
R_z = \begin{pmatrix} c_\phi & -s_\phi & 0 \\ s_\phi & c_\phi & 0 \\ 0 & 0 & 1 \end{pmatrix},$$

and multiplying $R = R_y R_x R_z$ out (first $R_y R_x$, then by $R_z$):

$$R = \begin{pmatrix}
 c_\psi c_\phi + s_\psi s_\theta s_\phi & -c_\psi s_\phi + s_\psi s_\theta c_\phi & s_\psi c_\theta \\
 c_\theta s_\phi & c_\theta c_\phi & -s_\theta \\
 -s_\psi c_\phi + c_\psi s_\theta s_\phi & s_\psi s_\phi + c_\psi s_\theta c_\phi & c_\psi c_\theta
\end{pmatrix}.$$

Three entries are simple: $R_{12} = -\sin\theta$ gives the pitch directly; $R_{02} / R_{22} =
s_\psi c_\theta / (c_\psi c_\theta) = \tan\psi$ gives the yaw as $\operatorname{atan2}(R_{02},
R_{22})$; $R_{10} / R_{11} = \tan\phi$ gives the roll as $\operatorname{atan2}(R_{10}, R_{11})$. Now
substitute the quaternion-to-matrix entries of chapter 03, $R_{12} = 2(yz - wx)$, $R_{02} = 2(xz +
wy)$, $R_{22} = 1 - 2(x^2 + y^2)$, $R_{10} = 2(xy + wz)$, $R_{11} = 1 - 2(x^2 + z^2)$:

$$\sin\theta = 2(wx - yz), \qquad
\psi = \operatorname{atan2}\big(2(xz + wy),\ 1 - 2(x^2 + y^2)\big), \qquad
\phi = \operatorname{atan2}\big(2(xy + wz),\ 1 - 2(x^2 + z^2)\big),$$

which are the three lines of `GizmoMath.toEuler` (§22.7). For the $q$ above: $\sin\theta = 2(0.9513
\cdot 0.1677 - 0.2549 \cdot (-0.0449)) = 2(0.1595 + 0.0114) = 0.3420 = \sin 20°$; $\psi =
\operatorname{atan2}(2(-0.0075 + 0.2425),\ 1 - 2(0.0281 + 0.0650)) = \operatorname{atan2}(0.4699, 0.8138)
= 30.0°$; $\phi = \operatorname{atan2}(2(0.0427 - 0.0427), \ldots) = 0$. The test round-trips four
triples, including $(-100°, 45°, 70°)$ and $(170°, -80°, -10°)$, to within 0.01°.

**Gimbal lock.** At $\theta = \pm 90°$, $c_\theta = 0$ and $R_{02} = R_{22} = R_{10} = R_{11} = 0$:
both `atan2`s get $(0, 0)$ and yaw and roll are not separable. At $\theta = +90°$ the matrix's first
row becomes $(\cos(\psi - \phi),\ \sin(\psi - \phi),\ 0)$: only their *difference* survives; at
$\theta = -90°$ it becomes $(\cos(\psi + \phi),\ -\sin(\psi + \phi),\ 0)$: only their *sum*. The code
detects $|\sin\theta| > 0.9999$ and attributes the whole turn to yaw with
$\operatorname{atan2}(2(wy - xz),\ 1 - 2(y^2 + z^2))$, which is $\operatorname{atan2}(-R_{20}, R_{00})$
(at $+90°$, $-R_{20} = \sin(\psi - \phi)$ and $R_{00} = \cos(\psi - \phi)$, so the yaw comes out as
$\psi - \phi$; at $-90°$ as $\psi + \phi$), and sets roll to 0. The `Math.max(-1, Math.min(1, sinPitch))` clamp before `asin` guards against a
quaternion that is a hair over unit length, whose $2(wx - yz)$ can be $1.0000001$ and whose `asin`
would be NaN.

## 12. What the GPU does with the gizmo's lines

### 12.1 Overlay lines (D92)

The grid, the markers and the gizmo are **debug lines**: `RenderPacket.addDebugLine(x0, y0, z0,
x1, y1, z1, argb)`, the same path the physics and navigation overlays of chapter
[11](11-physics-navigation-crowds.md) use. One addition for the editor: lines added after
`packet.beginDebugOverlay()` are drawn *without* the depth test, so the gizmo shows through walls,
while the grid and the markers before that mark are meant to be depth-tested like everything else
(§12.2 says why, today, they are not). The packet records the index of the first overlay line,
`debugOverlayStart`; `DebugRenderer` draws two ranges.

The alternatives D92 names: drawing the handles as UI triangles (3D geometry in the 2D draw list, a
second projection path) and `glLineWidth` for thickness. Lines kept the gizmo in world space under
the same clipping and projection as the rest of the overlays.

### 12.2 From the packet to the GPU

Here is the whole path of one gizmo line, on the main thread, in `DebugRenderer.draw`:

1. **Upload.** The renderer allocates `lines × 32` bytes from the **frame ring** of
   [Lesson 1](lessons/01-buffers-in-flight.md) (256-byte aligned, three frames in flight so the GPU
   can still be reading last frame's lines) and writes each line as two vertices of 16 bytes:
   `x, y, z` as floats and the colour's 32 bits in the fourth float's slot. The ring is coherently
   mapped (L01): the CPU write lands in memory the GPU reads without a copy call.
2. **Bind.** The ring's buffer range is bound as a shader storage buffer at binding 2; the view-
   projection matrix goes to uniform location 0; a vertex array object with *no* attributes is bound.
3. **Draw.** `glDrawArrays(GL_LINES, first, count)`: the driver records a draw of `count` vertices as
   `count / 2` independent line segments (L00 §10.5: the call returns at once; the GPU executes it
   later). The vertex shader has no inputs; it reads its vertex from the storage buffer by
   `gl_VertexID`, the index the hardware hands each invocation (**vertex pulling**, the same trick
   the UI renderer of chapter 13 uses), unpacks the colour, and writes `gl_Position = viewProjection
   * vec4(v.xyz, 1)`.
4. **Rasterization.** For `GL_LINES` the fixed-function rasterizer walks each segment after the
   perspective divide and generates one fragment per pixel the line crosses, one pixel wide (the
   *diamond-exit* rule decides which pixels a thin line touches). Core-profile OpenGL promises width
   1 only: `glLineWidth` above 1 is deprecated, and drivers may reject or ignore it (D92). Depth is
   interpolated along the segment.
5. **Depth test and blend.** The first range (grid, markers) asks for `PipelineState.TRANSPARENT`
   with depth writes off: a reversed-Z `GL_GEQUAL` test (chapter 04) that would discard a grid line
   behind a wall in the hardware's per-fragment test, without the lines ever writing depth. The
   second range (the gizmo) uses `withDepth(TRANSPARENT, false, false, DEPTH_ALWAYS)`: the depth
   test *disabled* (`glDisable(GL_DEPTH_TEST)` through the state cache), depth writes off, and the
   function set to `GL_ALWAYS` as well, so the state would mean "every fragment passes" even if a
   later change turned the test back on. The gizmo is painted over whatever is there.
6. **Blend.** Alpha blending (`SRC_ALPHA, ONE_MINUS_SRC_ALPHA`, chapter
   [05](05-opengl-from-java.md)) is on in both ranges, so the grid's `0x40FFFFFF` is a
   quarter-opaque white. Blending happens in the GPU's **ROP** (render output unit), the fixed
   function at the end of the pipeline that owns the colour buffer: for each fragment that survives
   the tests it *reads* the pixel's current colour from the framebuffer (through the ROP's own
   colour cache), computes $\mathbf{c}_s \alpha_s + \mathbf{c}_d (1 - \alpha_s)$, and *writes* the
   result back. That read-modify-write is why blending costs memory bandwidth that opaque drawing
   does not (an opaque fragment only writes), and why the hardware must keep fragments that land on
   the same pixel in submission order: two overlapping gizmo lines blend in the order they were
   added to the packet. For a few hundred one-pixel lines the cost is nothing.

What the state asks for and what happens differ today, and you should know it. `WorldRenderer`
draws the lines into the **window's** framebuffer, *after* the finished display image and the UI,
and the window is created with no depth buffer (D23: no sRGB, no alpha, no depth bits, because some
X servers have no such visuals). With no depth attachment OpenGL skips the depth test entirely, so
the grid and the markers also show through walls, the "overlay" distinction is moot, and the
lines are painted over the panels too, since the UI was drawn first. Chapter
[08](08-effects-and-post.md), which builds `DebugRenderer`, lists "draw the depth-tested lines into
a framebuffer with the scene's depth attachment" among its open problems; the gizmo code is written
for that day. Other things that can go wrong: forgetting the ring's 256-byte alignment (the storage
binding would fail), or packing the colour in the wrong byte order (the packet stores `0xAARRGGBB`;
the shader unpacks and swizzles `(z, y, x, w)` to get `rgba`).

### 12.3 Thickness by drawing three times

One-pixel lines are thin on a high-resolution display. The viewport's `thick` draws each axis
**three times**, offset by one pixel's worth of metres along the camera's **right** and **up**
vectors. Those two vectors are the first two rows of the view matrix: the view matrix maps world
to camera space, so its first row dotted with a world vector gives that vector's camera-$x$
component, which means the row *is* the camera's right axis in world coordinates (chapter 04). In
the column-major `float[16]`, row 0 is `v[0], v[4], v[8]` and row 1 is `v[1], v[5], v[9]`. For the
example camera, row 0 is $(0.7071, 0, -0.7071) = (\cos\psi, 0, -\sin\psi)$, the right vector the pan
code also uses. Multiplying by `metersPerPixel` at the gizmo's depth makes the offset exactly one
pixel on screen whatever the zoom: a cheap three-pixel line.

## 13. Panels, ids and the dock

### 13.1 The dock leaves a hole for the viewport

The scene renders full-window; the panels float on top. The **dock** (§24) owns three zones, left,
right and bottom; each is a tabs row over a scrolling column that draws the visible panel, with a
splitter on its inner edge. The editor declares, in this order, one full-window floating root that
does *not* block the pointer (`noHit()`: chapter [13](13-ui.md) explains that a box without a
background is transparent to hits), the menu bar across the top, and the dock below it; the free
space in the middle is a `grow` box with `noHit()` that lets clicks through to the viewport.

```
 ┌──────────────────────── menu bar (widgetHeight) ────────────────────────┐
 │ left zone │▌                                        ▐│ right zone        │
 │ (tabs)    │▌         the viewport region            ▐│ (tabs)            │
 │ Outliner  │▌   regionX = left + splitter            ▐│ Inspector         │
 │ Assets    │▌   regionY = top                        ▐│ Settings          │
 │           │▌   regionW = width − left − right − 2s  ▐│                   │
 ├───────────┴▌────────────────────────────────────────▐┴───────────────────┤
 │ ▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬ splitter ▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬ │
 │ bottom zone: Console                                                      │
 └───────────────────────────────────────────────────────────────────────────┘
```

The dock reports the free rectangle and the editor hands it to the viewport as its **region**: the
camera controller and picking accept input only inside it and only while the UI does not want the
pointer (`Ui.wantsMouse()`: a press on a panel, a drag of a splitter). Without that check a splitter
drag would also orbit the camera. The layout is one string the host keeps in its settings file,
`left=300:Outliner,Assets|right=340:Inspector,Settings|bottom=220:Console`, and the View menu
toggles panels in and out of it. Dragging a tab from one zone to another, as desktop editors allow,
was deferred (D95): the View menu and the console's `panel <name> <zone>` place panels, which
covered every need of the first editor without a drag-and-drop layer over the UI.

### 13.2 Panels are immediate mode too (D95)

A **panel** is a name and a `draw(ctx, widgets)` that declares its contents every frame it is the
visible tab of its zone. There is no panel state to keep in sync with the level: the Outliner walks
the level's rows every frame and declares one selectable row each; the Inspector reads the primary
object's fields and declares widgets for them. What a panel keeps is *scratch*: a `TextBuffer` per
text field, a `StringBuilder` per counted heading, a label array for a dropdown, re-filled only
when the level's version or the primary changed (§4.4).

The alternative, retained panel objects with data binding, would make the Inspector a mirror of the
level's state with the usual two-way-binding bugs; immediate mode makes "the level changed" the
only event. The price is that drawing must allocate nothing, which the tests hold (§31.8).

### 13.3 Ids need scopes

Every widget needs an **id** that is stable across frames (so the UI can match this frame's
declaration to last frame's rectangle and state) and unique within the frame. Panels use small
constants (`FILTER = 1`, `ROW = 3`), which would collide between panels and between rows, so ids are
scoped: `ui.pushId(scope)` combines the scope into every id declared until `popId()`, with a mixing
function (`Ui.combine`: a xor-shift-multiply hash) so that nested scopes rarely collide. The dock
pushes a scope per zone and per panel; a panel pushes one per row (`pushId(id)` with the object's
id, or the row index); and the compound widgets scope their own components: `vectorField` pushes
its id before its three number fields, or a vector field's X and a colour field's R in the same
panel would share the id `(0, FIELD)` and drag together. The first version did exactly that, and
`EditorWidgetsTest` checks that the fields now edit their own components. `PanelsTest` reaches an
Inspector field by folding the same scopes: `id(Dock.RIGHT, 2, POSITION, 0, FIELD)`, the right zone,
the third panel, the position row, component 0, the number field. Integer scopes are offset by
$2^{32} + 1$ inside `pushId(int)` so a row index 3 and a widget id 3 never mix.

### 13.4 Text buffers that mirror the level

The Inspector's name field edits a `TextBuffer`. When the user types, the panel pushes a
`SetStringCommand` (merged) and the level changes; when the *gizmo* moves the object, the level
changes too, and the panel must re-read the position fields, but must not reset the name buffer
in the middle of typing. The rule: re-read a text buffer when the primary object changed, or when
the level's version changed *and that field is not focused*. `syncedVersion` is set to the new
version right after the panel's own push, so its own edits never trigger a re-read (§25.2).

# Part B — Building it

The order below is the order the code was written in M14, and the order a reader can rebuild it
in: the data first (the store, then the file), then the world and the cooker that consume it, then
the editor's commands, viewport and panels, then the hosts. Every step compiles on its own.

## 14. Step 1: `LevelData`, the tables

`LevelData` lives in the `asset` module
([`LevelData.java`](../../modules/asset/src/main/java/dev/jeddy/asset/format/LevelData.java)),
next to the other format readers, because the runtime reads it and the editor must not be a
dependency of the game. It is one class of 2,377 lines, most of them the same four methods repeated
per table; this step shows one table in full and says where the others differ.

### 14.1 Constants and the settings block

The file's magic, versions and sizes first, then the kinds, flags and float strides the rest of the
engine addresses rows by (condensed: the Javadoc per constant is in the file):

```java
public final class LevelData {
    public static final int MAGIC = 0x4C564C4A;
    public static final int VERSION = 2;
    public static final int VERSION_1 = 1;
    public static final int HEADER_SIZE = 192;
    public static final int HEADER_SIZE_V1 = 160;

    public static final int VALUE_INT = 0;
    public static final int VALUE_LONG = 1;
    public static final int VALUE_FLOAT = 2;
    public static final int VALUE_BOOL = 3;
    public static final int VALUE_ENUM = 4;
    public static final int VALUE_ENTITY = 5;
    public static final int VALUE_ASSET = 6;
    public static final int COMPONENT_ROW_SIZE = 24;
    public static final int COMPONENT_VALUE_SIZE = 24;

    static final int PREFAB_SIZE = 16;
    static final int PLACEMENT_SIZE = 96;
    static final int LIGHT_SIZE = 80;
    static final int SPAWN_SIZE = 48;
    static final int TRIGGER_SIZE = 64;
    static final int AREA_SIZE = 32;
    static final int LINK_SIZE = 48;
    static final int RULE_SIZE = 24;
    static final int SIZE_CLASS_SIZE = 32;
    static final int SETTINGS_SIZE = 128;

    public static final int KIND_PLACEMENT = 0;
    public static final int KIND_LIGHT = 1;
    public static final int KIND_SPAWN = 2;
    public static final int KIND_TRIGGER = 3;
    public static final int KIND_NONE = -1;

    public static final int PLACEMENT_STATIC = 1;
    public static final int PLACEMENT_CASTS_SHADOW = 2;
    public static final int PLACEMENT_FADE_OCCLUDER = 4;
    public static final int PLACEMENT_HIDDEN = 8;
    public static final int PLACEMENT_NO_DECALS = 16;
    public static final int PLACEMENT_NO_COLLISION = 32;
    public static final int COLLISION_BY_LAYER = -1;

    public static final int LIGHT_POINT = 1;
    public static final int LIGHT_SPOT = 2;
    public static final int LIGHT_SHADOW = 1;
    public static final int LIGHT_FLICKER = 2;
    public static final int TRIGGER_ONCE = 1;
    public static final int TRIGGER_ENABLED = 2;
    public static final int LINK_BIDIRECTIONAL = 1;

    public static final int PLACEMENT_FLOATS = 10;
    public static final int LIGHT_FLOATS = 13;
    public static final int SPAWN_FLOATS = 4;
    public static final int TRIGGER_FLOATS = 7;
    public static final int LINK_FLOATS = 7;
    public static final int SIZE_CLASS_FLOATS = 3;
```

- Flags are powers of two, one bit each (L00 §6.2), so a placement's flags are an `int` and the
  Inspector's checkboxes are `(flags & bit) != 0`.
- `COLLISION_BY_LAYER = -1` is the `0xFFFFFFFF` of the file read as a signed `int` (L00 §5.3):
  the reader needs no translation.
- The row sizes are package-private (`static final int`, no `public`): only the reader and writer
  in this file need them. The float strides are public: the loader passes `placementData()` with
  `i * PLACEMENT_FLOATS` to the world.

The settings are the one place the store uses a plain object: a nested `Settings` class of public
fields with the file's defaults, and a `copyFrom` the settings command uses to snapshot it
(condensed: the Javadoc per field says what each one is):

```java
    public static final class Settings {
        public float navCellSize = 0.15f;
        public float navCellHeight = 0.1f;
        public float navTileSize = 32;
        public float navMaxSlope = 45;
        public float occlusionCellSize = 0.5f;
        public float occlusionYMin = 0.3f;
        public float occlusionYMax = 2.0f;
        public boolean occlusionEnabled = true;
        public final float[] sunDirection = {-0.4f, -0.8f, -0.45f};
        public final float[] sunColor = {3f, 2.9f, 2.7f};
        public float exposure = 1;
        public float environmentSpecular = 1;
        public float environmentDiffuse = 1;
        public String environment = "";
        public float gridStep = 0.5f;

        public Settings() {}

        public void copyFrom(Settings o) { ... }   // every field, arrays with System.arraycopy
    }

    public final Settings settings = new Settings();
```

A public field cannot bump the version counter, so `LevelData.touch()` exists for whoever edits
`settings` directly (`SetSettingsCommand` calls it).

### 14.2 The columns

Each table is struct-of-arrays ([Lesson 3](lessons/03-data-layout.md)): one array per column, a
count, and one flat `float[]` per kind for the numeric row at a fixed stride. The arrays start
small and double when full:

```java
    // Prefabs
    private int prefabCount;
    private String[] prefabScene = new String[8];
    private String[] prefabNode = new String[8];
    // Placements
    private int placementCount;
    private int[] placementId = new int[64];
    private int[] placementPrefab = new int[64];
    private String[] placementName = new String[64];
    private String[] placementLayer = new String[64];
    private int[] placementFlags = new int[64];
    private int[] placementCollision = new int[64];
    private float[] placementData = new float[64 * PLACEMENT_FLOATS];
    // Lights
    private int lightCount;
    private int[] lightId = new int[16];
    private int[] lightType = new int[16];
    private String[] lightName = new String[16];
    private int[] lightFlags = new int[16];
    private float[] lightData = new float[16 * LIGHT_FLOATS];
    // Spawns
    private int spawnCount;
    private int[] spawnId = new int[16];
    private String[] spawnName = new String[16];
    private String[] spawnKind = new String[16];
    private float[] spawnData = new float[16 * SPAWN_FLOATS];
    // Triggers
    private int triggerCount;
    private int[] triggerId = new int[16];
    private String[] triggerName = new String[16];
    private String[] triggerKind = new String[16];
    private String[] triggerPayload = new String[16];
    private int[] triggerFlags = new int[16];
    private float[] triggerData = new float[16 * TRIGGER_FLOATS];
    // Navigation areas
    private int areaCount;
    private int[] areaArea = new int[8];
    private float[] areaYMin = new float[8];
    private float[] areaYMax = new float[8];
    private float[][] areaPolygon = new float[8][];
    // Links
    private int linkCount;
    private int[] linkFlags = new int[8];
    private int[] linkArea = new int[8];
    private int[] linkUserId = new int[8];
    private float[] linkData = new float[8 * LINK_FLOATS];
    // Rules
    private int ruleCount;
    private String[] ruleLayer = new String[8];
    private int[] ruleFlags = new int[8];
    private int[] ruleSurface = new int[8];
    private int[] ruleArea = new int[8];
    // Size classes
    private int sizeClassCount;
    private String[] sizeClassName = new String[4];
    private float[] sizeClassData = new float[4 * SIZE_CLASS_FLOATS];
```

Why a `float[]` per kind rather than `x[]`, `y[]`, `z[]` columns: the consumers want *rows*. The
world takes a placement's ten floats as one TRS run, the gizmo copies a whole row into a command,
the light extractor reads thirteen consecutive floats. With one flat array, placement $i$'s row is
the slice `[i * 10, i * 10 + 10)`, one `System.arraycopy` away. Area polygons are the exception, a
`float[]` per area, because they have different lengths.

Then the id machinery and the version:

```java
    private int nextId = 1;
    private int version;
    // id → kind << 28 | row index, kept by every insert and remove so lookups by id are O(1) (the
    // gizmo resolves the selection every frame; a linear scan over 400 placements per object
    // would not do).
    private final IntIntMap ids = new IntIntMap(256, -1);

    public LevelData() {}

    public int version() {
        return version;
    }

    public void touch() {
        version++;
    }

    public int nextId() {
        return nextId;
    }

    public int allocateId() {
        version++;
        return nextId++;
    }

    private void noteId(int id) {
        if (id >= nextId) {
            nextId = id + 1;
        }
    }

    public int kindOf(int id) {
        int v = ids.get(id);
        return v < 0 ? KIND_NONE : v >>> 28;
    }

    private int indexOf(int id, int kind) {
        int v = ids.get(id);
        return v >= 0 && v >>> 28 == kind ? v & 0x0FFFFFFF : -1;
    }

    private void indexInsert(int id, int kind, int index, int[] rowIds, int count) {
        if (ids.containsKey(id)) {
            throw new IllegalArgumentException("duplicate object id " + id);
        }
        for (int i = index; i < count; i++) { // the rows after it moved up by one
            ids.put(rowIds[i], kind << 28 | i);
        }
    }

    private void indexRemove(int id, int kind, int index, int[] rowIds, int count) {
        ids.remove(id);
        for (int i = index; i < count; i++) {
            ids.put(rowIds[i], kind << 28 | i);
        }
    }
```

- `allocateId` bumps the version because it changes `nextId`, which the file stores.
- `noteId` keeps `nextId` above any id that arrives from outside: an undo reinserting an old id
  must not burn a new one (`LevelDataTest` checks `nextId` is unchanged), and an id "from the
  future" (a merge of two levels, a hand-edited file) moves the counter past itself.
- `kindOf` uses `>>>`, the unsigned shift (L00 §6.3): a kind in the top four bits never sets the
  sign bit for kinds 0–3, but the unsigned shift is the honest operator for "the top bits".
- The index loop in `indexInsert` starts *at* `index` because the row inserted there is new and
  the rows that were at `index` and after have each moved up by one; `count` is the count *after*
  the insert, so the loop covers the tail. `indexRemove` is called with the count *after* the
  remove, and the rows from `index` on have each moved down.

### 14.3 One table in full: placements

Reading is trivial (an array index); the shape to learn is insert and remove. `insertPlacement`
takes the row's *id* as a parameter, because an undo reinserts a row with the id it had;
`addPlacement` is the convenience that allocates a fresh id and appends:

```java
    public int addPlacement(
            int prefab,
            String name,
            String layer,
            int flags,
            float x,
            float y,
            float z,
            float qx,
            float qy,
            float qz,
            float qw,
            float sx,
            float sy,
            float sz) {
        int i =
                insertPlacement(
                        placementCount,
                        allocateId(),
                        prefab,
                        name,
                        layer,
                        flags,
                        COLLISION_BY_LAYER);
        float[] d = placementData;
        int o = i * PLACEMENT_FLOATS;
        d[o] = x;
        d[o + 1] = y;
        d[o + 2] = z;
        d[o + 3] = qx;
        d[o + 4] = qy;
        d[o + 5] = qz;
        d[o + 6] = qw;
        d[o + 7] = sx;
        d[o + 8] = sy;
        d[o + 9] = sz;
        return i;
    }

    public int insertPlacement(
            int index, int id, int prefab, String name, String layer, int flags, int collision) {
        if (placementCount == placementId.length) {
            int n = placementCount * 2;
            placementId = Arrays.copyOf(placementId, n);
            placementPrefab = Arrays.copyOf(placementPrefab, n);
            placementName = Arrays.copyOf(placementName, n);
            placementLayer = Arrays.copyOf(placementLayer, n);
            placementFlags = Arrays.copyOf(placementFlags, n);
            placementCollision = Arrays.copyOf(placementCollision, n);
            placementData = Arrays.copyOf(placementData, n * PLACEMENT_FLOATS);
        }
        int tail = placementCount - index;
        if (tail > 0) {
            System.arraycopy(placementId, index, placementId, index + 1, tail);
            System.arraycopy(placementPrefab, index, placementPrefab, index + 1, tail);
            System.arraycopy(placementName, index, placementName, index + 1, tail);
            System.arraycopy(placementLayer, index, placementLayer, index + 1, tail);
            System.arraycopy(placementFlags, index, placementFlags, index + 1, tail);
            System.arraycopy(placementCollision, index, placementCollision, index + 1, tail);
            System.arraycopy(
                    placementData,
                    index * PLACEMENT_FLOATS,
                    placementData,
                    (index + 1) * PLACEMENT_FLOATS,
                    tail * PLACEMENT_FLOATS);
        }
        placementId[index] = id;
        placementPrefab[index] = prefab;
        placementName[index] = name;
        placementLayer[index] = layer;
        placementFlags[index] = flags;
        placementCollision[index] = collision;
        Arrays.fill(placementData, index * PLACEMENT_FLOATS, (index + 1) * PLACEMENT_FLOATS, 0);
        placementCount++;
        indexInsert(id, KIND_PLACEMENT, index, placementId, placementCount);
        noteId(id);
        version++;
        return index;
    }

    public void removePlacement(int index) {
        int removed = placementId[index];
        int tail = placementCount - index - 1;
        if (tail > 0) {
            System.arraycopy(placementId, index + 1, placementId, index, tail);
            System.arraycopy(placementPrefab, index + 1, placementPrefab, index, tail);
            System.arraycopy(placementName, index + 1, placementName, index, tail);
            System.arraycopy(placementLayer, index + 1, placementLayer, index, tail);
            System.arraycopy(placementFlags, index + 1, placementFlags, index, tail);
            System.arraycopy(placementCollision, index + 1, placementCollision, index, tail);
            System.arraycopy(
                    placementData,
                    (index + 1) * PLACEMENT_FLOATS,
                    placementData,
                    index * PLACEMENT_FLOATS,
                    tail * PLACEMENT_FLOATS);
        }
        placementCount--;
        indexRemove(removed, KIND_PLACEMENT, index, placementId, placementCount);
        removeComponentRowsOf(removed);
        placementName[placementCount] = null;
        placementLayer[placementCount] = null;
        version++;
    }
```

- **Growth doubles** every column together, so the arrays never disagree on their capacity.
- **`System.arraycopy` handles overlap** correctly (it copies as if through a temporary), which
  is what shifting a tail by one inside the same array needs; the float row is shifted by
  `tail * PLACEMENT_FLOATS` floats.
- **The new row's floats are zeroed** by `Arrays.fill`: an inserted row's transform is set by the
  caller afterwards (`setPlacementTransform`), and a half-initialized quaternion `(0, 0, 0, 0)`
  would be a disaster in the world, so zero is the honest "not set".
- **`removePlacement` takes the removed id first**, because after the shift `placementId[index]`
  is the next row's id. It also removes the object's component rows (`removeComponentRowsOf`): an
  object that is gone must not leave rows that refer to it, and `ObjectRow` captured them before
  the delete so undo can restore them.
- **The vacated string slots are nulled** so the garbage collector can collect the strings; the
  `int` and `float` slots keep stale values, which cost nothing.

The setters are one line and a version bump each (`setPlacementName`, `setPlacementFlags`,
`setPlacementFloat(i, k, v)`, ...), plus `setPlacementTransform(i, trs, offset)`, which copies ten
floats with one `System.arraycopy`, and `prefab(scene, node)`, which returns the index of an
existing `(scene, node)` pair or appends a new one (`LevelDataTest` checks the deduplication).

### 14.4 The other tables

Lights, spawns and triggers are the same four methods with their own columns: `insertLight(index,
id, type, name, flags)`, `insertSpawn(index, id, name, kind)`, `insertTrigger(index, id, name, kind,
payload, flags)`, each followed by `removeXxx(index)` that shifts, re-indexes and removes the
component rows, and an `addXxx` that allocates an id and fills the floats (`addPointLight` sets the
direction to $(0, -1, 0)$ and the cone angles to 0.4 and 0.6, so a point light switched to a spot
in the Inspector points down with a sensible cone; `AddObjectsCommand.pointLight` does the same,
with the comment "pointing down, in case it becomes a spot").

The four tables without ids (areas, links, rules, size classes) are simpler: no index map, no
component rows. Their `insert` reuses `add` and then shifts the tail *downwards* by one with a
loop that walks backwards, for example the rules (matched in order, so the position matters):

```java
    public int addRule(String layer, int flags, int surface, int area) {
        if (ruleCount == ruleLayer.length) {
            int n = ruleCount * 2;
            ruleLayer = Arrays.copyOf(ruleLayer, n);
            ruleFlags = Arrays.copyOf(ruleFlags, n);
            ruleSurface = Arrays.copyOf(ruleSurface, n);
            ruleArea = Arrays.copyOf(ruleArea, n);
        }
        int i = ruleCount++;
        setRule(i, layer, flags, surface, area);
        return i;
    }

    public int insertRule(int index, String layer, int flags, int surface, int area) {
        addRule(layer, flags, surface, area);
        for (int i = ruleCount - 1; i > index; i--) {
            ruleLayer[i] = ruleLayer[i - 1];
            ruleFlags[i] = ruleFlags[i - 1];
            ruleSurface[i] = ruleSurface[i - 1];
            ruleArea[i] = ruleArea[i - 1];
        }
        setRule(index, layer, flags, surface, area);
        return index;
    }
```

The loop runs backwards (`i` from the last row down to `index + 1`) so that each row is copied
from its predecessor *before* the predecessor is overwritten; forwards, the first copy would
clobber the second's source. `addRule` already grew the arrays and bumped the count, so the loop
has room. `addArea` also checks that a polygon has at least three `(x, z)` pairs (six floats, an
even count) and clones the array, because the caller may reuse its buffer.

### 14.5 Component rows (version 2)

The component rows arrived in M22 (D109). A row is one small object per (object, component) with
parallel arrays for its values, grown on demand (condensed: the three methods' bodies are a scan,
an append-or-replace and a tail shift):

```java
    private int componentRowCount;
    private ComponentRow[] componentRows = new ComponentRow[16];

    private static final class ComponentRow {
        int object;
        String component;
        int count;
        String[] column = new String[4];
        int[] type = new int[4];
        long[] bits = new long[4];
        String[] text = new String[4];

        int indexOf(String name) { ... }           // a linear scan over count (a handful)

        void set(String name, int valueType, long valueBits, String valueText) { ... }

        void remove(int k) { ... }                 // shifts the tail down, nulls the vacated slots
    }
```

A value's number payload is one `long` whatever its type: an `int` widened, a `long`, a `float`'s
bits (`Float.floatToRawIntBits`, L00 §7), a boolean as 0/1, an object id; enum constants and asset
paths are text. `addComponentRow` returns the existing row if the object already has that
component (one row per pair), `insertComponentRow(index, ...)` puts one back for an undo,
`removeComponentRowsOf(objectId)` walks the rows *backwards* so that removing one does not skip
the next, and `copyComponentRows(from, to)` duplicates an object's rows for `AddObjectsCommand.duplicate`:

```java
    public void removeComponentRowsOf(int objectId) {
        for (int i = componentRowCount - 1; i >= 0; i--) {
            if (componentRows[i].object == objectId) {
                removeComponentRow(i);
            }
        }
    }

    public void copyComponentRows(int fromObject, int toObject) {
        int n = componentRowCount;
        for (int i = 0; i < n; i++) {
            ComponentRow r = componentRows[i];
            if (r.object == fromObject) {
                int row = addComponentRow(toObject, r.component);
                for (int k = 0; k < r.count; k++) {
                    componentRows[row].set(r.column[k], r.type[k], r.bits[k], r.text[k]);
                }
            }
        }
    }
```

`copyComponentRows` reads the count into `n` first: `addComponentRow` appends, and iterating to a
growing `componentRowCount` would visit the copies it is making. The typed setters
(`setComponentInt`, `setComponentFloat`, ...) are one call to `setComponentValue` each with the
`VALUE_*` type; `componentValue(row, column)` returns the value's index or −1 for "at the default".
Chapter [22](22-components.md) explains who reads these rows at load.

### 14.6 `copyFrom`

A reload must put a level into the *same* `LevelData` object the world and the editor hold, so
there is a `copyFrom(other)` ("the editor's reload", its Javadoc says; no host calls it yet, only
the tests do) that clears every table and the id map, then inserts every row of `other` in order
with its id, copies the settings and takes the larger `nextId`. It ends with `version++`, and
`LevelDataTest.copiesAndDumps` checks the copy is byte-identical.

## 15. Step 2: reading and writing the file

### 15.1 `read`: trust nothing

The reader takes a `MemorySegment` (a mapped file in the editor, a byte array in the cooker) and
follows L04 §10's rule: check every count and every range before touching it. The accessors are
the asset module's `Bytes.U16`, `U32`, `U64` and `F32`, little-endian *unaligned* layouts
(`ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN)`), and `Bytes.checkRange`,
which throws `AssetFormatException` unless `[offset, offset + length)` lies inside the segment.

Why *unaligned*, when every field of the format is naturally aligned (a `u32` or `f32` at a
multiple of 4, a `u64` at a multiple of 8: the placement's float at offset 44 is $4 \times 11$, and
sections start on 16, L00 §8.4)? Because the segment may not be. FFM checks an aligned layout's
offset against the segment's **maximum alignment**, what the segment can promise about where its
first byte lives. A segment the editor maps from a file (`LevelFiles.read`) is native memory
starting on a page boundary, and promises a lot (an arena allocation aligned to 16 reports 32 here).
A segment made with `MemorySegment.ofArray(byte[])`, which the cooker uses on the file's bytes and
the tests use on `toBytes()`, is a **heap segment**: a view of a Java array, which the garbage
collector may move (L00 §8.2), and of whose elements Java promises only the element size, 1 byte
for a `byte[]`. Its `maxByteAlignment()` is 1, so *every* aligned `JAVA_INT` access throws, even at
offset 0:

```
java.lang.IllegalArgumentException: Target offset 0 is incompatible with alignment constraint 4
(of i4) for segment MemorySegment{ kind: heap, heapBase: [B@55ca8de8, address: 0x0, byteSize: 64 }
```

(measured with JDK 27 in a scratch program; an `int[]` heap segment reports 4). The unaligned
layouts accept any offset; on x86-64 an unaligned load costs the same as an aligned one when it does
not cross a cache line, which a naturally aligned field never does. One reader then serves the
mapped file and the byte array alike.

In stages. First the header, with the version 1 and 2 cases:

```java
    public static LevelData read(MemorySegment file) throws AssetFormatException {
        checkRange(file, 0, HEADER_SIZE_V1, ".jlevel header");
        if (file.get(U32, 0) != MAGIC) {
            throw new AssetFormatException("not a .jlevel file");
        }
        int version = Short.toUnsignedInt(file.get(U16, 4));
        if (version != VERSION && version != VERSION_1) {
            throw new AssetFormatException(".jlevel version " + version + ", expected " + VERSION);
        }
        int componentRows = 0;
        int componentValues = 0;
        long componentRowsAt = 0;
        long componentValuesAt = 0;
        if (version == VERSION) {
            checkRange(file, 0, HEADER_SIZE, ".jlevel header");
            componentRows = count(file, 160, "component row");
            componentValues = count(file, 164, "component value");
            componentRowsAt = file.get(U64, 168);
            componentValuesAt = file.get(U64, 176);
            checkRange(
                    file,
                    componentRowsAt,
                    (long) COMPONENT_ROW_SIZE * componentRows,
                    ".jlevel component rows");
            checkRange(
                    file,
                    componentValuesAt,
                    (long) COMPONENT_VALUE_SIZE * componentValues,
                    ".jlevel component values");
        }
        int prefabs = count(file, 8, "prefab");
        int placements = count(file, 12, "placement");
        int lights = count(file, 16, "light");
        int spawns = count(file, 20, "spawn");
        int triggers = count(file, 24, "trigger");
        int areas = count(file, 28, "area");
        int links = count(file, 32, "link");
        int rules = count(file, 36, "rule");
        int sizes = count(file, 40, "size class");
        int nextId = file.get(U32, 44);
        long prefabsAt = file.get(U64, 48);
        ...                                          // the other offsets, 56 .. 144
        if (file.get(U64, 152) != file.byteSize()) {
            throw new AssetFormatException(".jlevel size does not match its header");
        }
        checkRange(file, prefabsAt, (long) PREFAB_SIZE * prefabs, ".jlevel prefabs");
        ...                                          // one checkRange per section
        checkRange(file, stringsAt, stringBytes, ".jlevel strings");
        MemorySegment strings = file.asSlice(stringsAt, stringBytes);
```

- The 160-byte check comes *before* the version is known: a version 1 header is the smaller one,
  and a file shorter than that is not a level at all. Only a version 2 file is checked to 192.
- `Short.toUnsignedInt` on the `u16`: a `short` is signed (L00 §5.4), and a version above 32,767,
  though absurd, must not read as negative.
- `count` rejects a count that is negative (a `u32` above $2^{31}$ read into a signed `int`, L00
  §5) or above $2^{24}$ (16 million rows) with a message that names the count, before the range
  checks turn it into a less readable "lies outside the file"; it is a sane ceiling, not a
  format limit.
- `(long) PREFAB_SIZE * prefabs`: the cast *before* the multiply (L00 §4.3), so a large count
  cannot overflow in `int` first.
- The `fileSize` comparison catches truncation in one check; `LevelDataTest.rejectsBadFiles` cuts
  16 bytes off a valid file and expects the exception.

Then the rows, one loop per section. The placements:

```java
        LevelData level = new LevelData();
        for (int p = 0; p < prefabs; p++) {
            long at = prefabsAt + (long) PREFAB_SIZE * p;
            // Appended as stored: placements index this table, so no deduplication here.
            level.appendPrefab(string(strings, file, at), string(strings, file, at + 8));
        }
        for (int i = 0; i < placements; i++) {
            long at = placementsAt + (long) PLACEMENT_SIZE * i;
            int prefab = file.get(U32, at + 4);
            if (prefab < 0 || prefab >= prefabs) {
                throw new AssetFormatException(".jlevel placement " + i + " prefab " + prefab);
            }
            level.insertPlacement(
                    i,
                    file.get(U32, at),
                    prefab,
                    string(strings, file, at + 8),
                    string(strings, file, at + 16),
                    file.get(U32, at + 24),
                    file.get(U32, at + 28));
            floats(file, at + 32, level.placementData, i * PLACEMENT_FLOATS, PLACEMENT_FLOATS);
        }
```

The reader goes through the public `insertPlacement`, so the id index and `nextId` are maintained
for free, and a duplicate id in the file throws from `indexInsert`. The prefab index is checked
against the prefab count because a placement that points past the table would crash the loader
much later. `floats` copies `n` little-endian floats into the row; `string` reads an `(offset,
length)` pair, returns `""` for length 0 without touching the table, range-checks with
`Integer.toUnsignedLong` (an offset above 2 GiB is nonsense but must not be negative), and decodes
UTF-8 (L04 §4). Lights, spawns, triggers, areas (with a range check on the polygon's points), links,
rules, size classes and the settings follow the same pattern, then the component rows:

```java
        for (int i = 0; i < componentRows; i++) {
            long at = componentRowsAt + (long) COMPONENT_ROW_SIZE * i;
            int object = file.get(U32, at);
            String component = string(strings, file, at + 4);
            int start = file.get(U32, at + 12);
            int n = file.get(U32, at + 16);
            if (start < 0 || n < 0 || start + n > componentValues) {
                throw new AssetFormatException(".jlevel component row " + i + " values");
            }
            int row = level.addComponentRow(object, component);
            for (int k = 0; k < n; k++) {
                long v = componentValuesAt + (long) COMPONENT_VALUE_SIZE * (start + k);
                int type = file.get(U32, v + 8);
                long bits =
                        switch (type) {
                            case VALUE_LONG -> file.get(U64, v + 16);
                            case VALUE_ENUM, VALUE_ASSET -> 0;
                            default -> file.get(U32, v + 16);
                        };
                String text =
                        type == VALUE_ENUM || type == VALUE_ASSET
                                ? string(strings, file, v + 16)
                                : "";
                level.setComponentValue(row, string(strings, file, v), type, bits, text);
            }
        }
        level.nextId = Math.max(level.nextId, nextId);
        level.version = 0;
        return level;
    }
```

The last two lines: the header's `nextId` wins over whatever `noteId` computed from the rows (a
level may have burnt ids that are no longer in use), and the version counter is reset to 0 so that
a freshly read level is "nothing changed yet" for every panel.

### 15.2 `toBytes`: lay out, then fill

The writer computes every section's offset first (the string section's start is the size of the
fixed part), allocates one `ByteBuffer` of that size in little-endian order, fills the header and
rows, and appends the string bytes collected while writing. In stages. The layout:

```java
    public byte[] toBytes() {
        ByteArrayOutputStream strings = new ByteArrayOutputStream();
        int polygonFloats = 0;
        for (int i = 0; i < areaCount; i++) {
            polygonFloats += areaPolygon[i].length;
        }
        long prefabsAt = HEADER_SIZE;
        long placementsAt = align(prefabsAt + (long) PREFAB_SIZE * prefabCount, 16);
        long lightsAt = align(placementsAt + (long) PLACEMENT_SIZE * placementCount, 16);
        long spawnsAt = align(lightsAt + (long) LIGHT_SIZE * lightCount, 16);
        long triggersAt = align(spawnsAt + (long) SPAWN_SIZE * spawnCount, 16);
        long areasAt = align(triggersAt + (long) TRIGGER_SIZE * triggerCount, 16);
        long linksAt = align(areasAt + (long) AREA_SIZE * areaCount, 16);
        long rulesAt = align(linksAt + (long) LINK_SIZE * linkCount, 16);
        long sizesAt = align(rulesAt + (long) RULE_SIZE * ruleCount, 16);
        long settingsAt = align(sizesAt + (long) SIZE_CLASS_SIZE * sizeClassCount, 16);
        long polygonsAt = align(settingsAt + SETTINGS_SIZE, 16);
        long componentRowsAt = align(polygonsAt + 4L * polygonFloats, 16);
        int componentValues = 0;
        for (int i = 0; i < componentRowCount; i++) {
            componentValues += componentRows[i].count;
        }
        long componentValuesAt =
                align(componentRowsAt + (long) COMPONENT_ROW_SIZE * componentRowCount, 16);
        long stringsAt =
                align(componentValuesAt + (long) COMPONENT_VALUE_SIZE * componentValues, 16);
        // Strings are interned in order of first use; the table is built before the bytes.
        ByteBuffer b = ByteBuffer.allocate((int) stringsAt).order(ByteOrder.LITTLE_ENDIAN);
```

`Bytes.align(value, 16)` is `(value + 15) & -16`, the power-of-two rounding of L00 §6.6. Every row
size is a multiple of 16 except the rules, the component rows and the component values (24 each)
and the areas' polygons (8 bytes per point), which is where the `align` calls actually move
something. For the crypt (§3.4) they produce: prefabs 192, placements 432, lights 36,144, spawns
37,344, triggers 37,824, areas 37,824, links 37,856, rules 37,856, size classes 37,952, settings
37,984, polygons 38,112, component rows 38,144, component values 38,144 and strings 38,144 (the
three empty sections share an offset), which is what the re-saved file's header holds.

Then the header and rows. The placements again, as the representative:

```java
        b.putInt(0, MAGIC);
        b.putShort(4, (short) VERSION);
        b.putShort(6, (short) 0);
        b.putInt(8, prefabCount);
        ...                                          // the counts, nextId and the offsets
        b.putInt(160, componentRowCount);
        b.putInt(164, componentValues);
        b.putLong(168, componentRowsAt);
        b.putLong(176, componentValuesAt);
        ...                                          // component rows and values
        for (int i = 0; i < placementCount; i++) {
            int at = (int) (placementsAt + (long) PLACEMENT_SIZE * i);
            b.putInt(at, placementId[i]);
            b.putInt(at + 4, placementPrefab[i]);
            putString(b, at + 8, strings, placementName[i]);
            putString(b, at + 16, strings, placementLayer[i]);
            b.putInt(at + 24, placementFlags[i]);
            b.putInt(at + 28, placementCollision[i]);
            putFloats(b, at + 32, placementData, i * PLACEMENT_FLOATS, PLACEMENT_FLOATS);
        }
        ...                                          // the other sections and the settings
        byte[] stringBytes = strings.toByteArray();
        long fileSize = align(stringsAt + stringBytes.length, 16);
        b.putLong(144, stringBytes.length);
        b.putLong(152, fileSize);
        byte[] out = Arrays.copyOf(b.array(), (int) fileSize);
        System.arraycopy(stringBytes, 0, out, (int) stringsAt, stringBytes.length);
        return out;
    }

    private static void putString(ByteBuffer b, int at, ByteArrayOutputStream strings, String s) {
        if (s == null || s.isEmpty()) {
            b.putInt(at, 0);
            b.putInt(at + 4, 0);
            return;
        }
        byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
        b.putInt(at, strings.size());
        b.putInt(at + 4, utf8.length);
        strings.write(utf8, 0, utf8.length);
    }
```

- The 24 reserved bytes of each placement are never written: `ByteBuffer.allocate` zero-fills, and
  the format says reserved bytes are 0.
- `putString` appends every string to the stream and records where it landed: there is no
  deduplication, so `levels/dungeon.jscene` appears fifteen times in the crypt's string section
  (once per prefab). A few hundred bytes; exercise 3 is to intern them.
- `Arrays.copyOf(b.array(), fileSize)` extends the buffer to the padded size and the strings are
  copied in at `stringsAt`. The final padding is zeros again.
- Two writes of the same level are byte-identical (`roundTripsEveryKind` asserts it), which is
  what makes `CommandsTest`'s "undo restores the bytes" check possible.

Measured here (JDK 27, this container's 2 vCPUs; a `System.nanoTime` loop over 2,000 calls after
2,000 warm-up calls in a scratch program, not JMH, so runs vary by about 15 %): `toBytes` on the
crypt's 372 placements takes about 85–94 µs, which is why a save serializes on the game thread and
only the disk write moves to another thread (§21.5).

### 15.3 The JSON dump

`toJson` writes every table into a `StringBuilder` with a small `quote` that escapes `"`, `\`,
newlines and control characters (`\u%04x`), and `floats` that prints a row as `[a, b, c]`. It is
for `dump` in the console and for reviewing a level in a diff; `LevelDataTest.copiesAndDumps` checks
a few strings appear and that no `null` leaks into it. Nothing parses it (D86).

## 16. Step 3: the world: groups and picking

Four additions to `World` (`modules/app`), all cold except `pick`.

### 16.1 `instantiateNode`

```java
    public int instantiateNode(
            SceneData scene,
            int rootNode,
            float[] trs,
            int offset,
            int renderFlags,
            NodeFlags nodeFlags,
            boolean flatten) {
        int n = scene.nodeCount();
        if (rootNode >= n) {
            throw new IllegalArgumentException("node " + rootNode + " of " + n);
        }
        int root = pool.create();
        transforms.add(root, Handles.NULL);
        transforms.setPosition(root, trs[offset], trs[offset + 1], trs[offset + 2]);
        transforms.setRotation(
                root, trs[offset + 3], trs[offset + 4], trs[offset + 5], trs[offset + 6]);
        transforms.setScale(root, trs[offset + 7], trs[offset + 8], trs[offset + 9]);
        boolean isStatic = (renderFlags & RenderPacket.FLAG_STATIC) != 0;
        transforms.setStatic(root, isStatic);
        if (subtree.length < n) {
            subtree = new boolean[Math.max(n, subtree.length * 2)];
        }
        boolean[] in = subtree;
        int[] entity = new int[n];
        float[] composed = flatten ? new float[n * 10] : null;
        for (int i = 0; i < n; i++) {
            int p = scene.parent(i); // always a smaller index: parents come first
            in[i] = rootNode < 0 || i == rootNode || (p >= 0 && in[p]);
            if (!in[i]) {
                continue;
            }
            boolean top = rootNode < 0 ? p < 0 : i == rootNode; // a child of the new root
            int m = scene.mesh(i);
            int f = -1;
            if (m >= 0) {
                f =
                        nodeFlags == null
                                ? renderFlags
                                : nodeFlags.flags(scene.nodeName(i), renderFlags);
            }
            int e;
            if (flatten) {
                compose(scene, i, top ? -1 : p, composed);
                if (f < 0) {
                    continue;
                }
                e = pool.create();
                transforms.add(e, root);
                int o = i * 10;
                transforms.setPosition(e, composed[o], composed[o + 1], composed[o + 2]);
                transforms.setRotation(
                        e, composed[o + 3], composed[o + 4], composed[o + 5], composed[o + 6]);
                transforms.setScale(e, composed[o + 7], composed[o + 8], composed[o + 9]);
            } else {
                e = pool.create();
                transforms.add(e, top ? root : entity[p]);
                transforms.setPosition(e, scene.trs(i, 0), scene.trs(i, 1), scene.trs(i, 2));
                transforms.setRotation(
                        e, scene.trs(i, 3), scene.trs(i, 4), scene.trs(i, 5), scene.trs(i, 6));
                transforms.setScale(e, scene.trs(i, 7), scene.trs(i, 8), scene.trs(i, 9));
            }
            transforms.setStatic(e, isStatic);
            entity[i] = e;
            if (f >= 0) {
                addRenderable(e, scene.meshId(m), RenderPacket.MESH_MATERIALS, f);
                group[renderables.indexOf(Handles.index(e))] = root;
            }
        }
        return root;
    }
```

- **The root entity gets the placement's TRS** straight from the level's float row (`trs[offset
  ..]`): position 0–2, quaternion 3–6, scale 7–9, the order §3.3 fixed.
- **Membership is one pass** because a *cooked* scene (unlike the glTF of §6.3) stores parents
  before children: node $i$ is in the subtree if there is no root (`rootNode < 0`: the whole
  scene), or it *is* the root, or its parent is in. `subtree` is a reused scratch array; `entity`
  and `composed` are allocated per call (cold path: a placement instantiates once).
- **`top`** marks the nodes that hang directly under the new root: the subtree root itself, or
  every scene root when the whole scene is placed. The subtree root keeps its *own* local
  transform under the new root (a kit piece authored 1 m off its origin stays 1 m off).
- **Flattened**, `compose` writes node $i$'s TRS composed with its parent's already-composed TRS
  (parent scale, then parent rotation, then parent translation applied to the child's; the
  rotation product renormalized), and only mesh nodes become entities, as direct children of the
  root. A node with `f < 0` (a helper the `NodeFlags` excluded) is composed but gets no entity, so
  its children still find their matrix.
- **The group** of every renderable is the root, written straight into the renderables' `group`
  column after `addRenderable`.

`nodeNamed(scene, name)` is the linear scan the loader uses once per prefab to find the root's
index.

### 16.2 `destroyGroup`, `setGroupTransform`, `groupOf`, `setGroupFlags`

```java
    public void destroyGroup(int root) {
        // Renderables first (they may belong to entities below the root); destroying an entity
        // removes its transform (the pool tells its stores).
        for (int i = renderables.size() - 1; i >= 0; i--) {
            if (group[i] == root && entityOf[i] != root) {
                int e = entityOf[i];
                removeRenderable(e);
                pool.destroy(e);
            }
        }
        // Then any remaining descendants (non-flattened intermediate nodes).
        boolean again = true;
        while (again) {
            again = false;
            for (int s = transforms.size() - 1; s >= 0; s--) {
                int e = transforms.entityAt(s);
                if (e != root && isUnder(e, root)) {
                    pool.destroy(e);
                    again = true;
                    break; // the store compacts; restart the scan
                }
            }
        }
        destroy(root);
    }
```

The renderables loop runs backwards because `removeRenderable` swap-removes (the last row fills the
hole, [Lesson 3](lessons/03-data-layout.md)): walking down from the end, a swapped-in row is one
already visited. The descendants loop restarts after every destroy for the same reason, with
`isUnder` walking the parent chain; for a flattened placement there are none, and the loop ends
after one scan. Then:

```java
    public void setGroupTransform(int root, float[] trs, int offset) {
        transforms.setPosition(root, trs[offset], trs[offset + 1], trs[offset + 2]);
        transforms.setRotation(
                root, trs[offset + 3], trs[offset + 4], trs[offset + 5], trs[offset + 6]);
        transforms.setScale(root, trs[offset + 7], trs[offset + 8], trs[offset + 9]);
        if (transforms.isStatic(root)) {
            staticStructure++;
        }
    }

    public int groupOf(int entity) {
        int i = renderables.indexOf(Handles.index(entity));
        return i >= 0 && entityOf[i] == entity ? group[i] : entity;
    }
```

`setGroupTransform` touches only the root; the children follow through the transform hierarchy
when `updateTransforms` runs. The `staticStructure++` is the D49 contract of §5.3. `groupOf`
checks `entityOf[i] == entity` after the sparse lookup for the reason Lesson 3's exercise 4 gives:
a slot reused by a new entity would otherwise answer for a dead one. `setGroupFlags` rewrites every
member's render flags and static-ness and bumps `staticStructure`; `setGroupHidden` sets a
per-group bit the extractor and `pick` consult; `setGroupHighlight` sets each member's outline
style.

### 16.3 `pick`

```java
    public int pick(
            float ox, float oy, float oz, float dx, float dy, float dz, float maxT, float[] outT) {
        float[] world = transforms.world();
        float best = maxT;
        int bestEntity = Handles.NULL;
        for (int i = 0, n = renderables.size(); i < n; i++) {
            int h = meshAsset[i];
            if (!meshes.isReady(h)) {
                continue;
            }
            int g = Handles.index(group[i]);
            if (g < hiddenGroup.length && hiddenGroup[g]) {
                continue;
            }
            GpuMesh mesh = meshes.get(h);
            int s = transforms.indexOf(entityOf[i]);
            // A sphere test first: it rejects almost everything for the price of a few multiplies.
            AffineOps.transformPoint(
                    world, s * 12, mesh.sphere(0), mesh.sphere(1), mesh.sphere(2), point, 0);
            float r = mesh.sphere(3) * maxScale(world, s * 12);
            float ts = Intersect.raySphere(ox, oy, oz, dx, dy, dz, point[0], point[1], point[2], r);
            if (ts == Intersect.MISS || ts > best) {
                continue;
            }
            if (!AffineOps.invert(world, s * 12, pickInverse, 0)) {
                continue;
            }
            AffineOps.transformPoint(pickInverse, 0, ox, oy, oz, pickLocal, 0);
            AffineOps.transformDirection(pickInverse, 0, dx, dy, dz, pickLocal, 3);
            float t =
                    Intersect.rayAabb(
                            pickLocal[0],
                            pickLocal[1],
                            pickLocal[2],
                            pickLocal[3],
                            pickLocal[4],
                            pickLocal[5],
                            mesh.bounds(0),
                            mesh.bounds(1),
                            mesh.bounds(2),
                            mesh.bounds(3),
                            mesh.bounds(4),
                            mesh.bounds(5),
                            best);
            if (t < best) {
                best = t;
                bestEntity = entityOf[i];
            }
        }
        outT[0] = best;
        return bestEntity;
    }

    private final float[] pickInverse = new float[12];
    private final float[] pickLocal = new float[6];
```

§9.2 derived every line. Three details: the `maxT` parameter lets the viewport pass the best
marker hit so far, so a wall *behind* a torch does not beat it; `ts > best` rejects spheres that
start beyond the current best before any inverse; and `maxScale` takes the largest column length
of the 3×3 part (the largest axis scale) so the sphere stays conservative for non-uniform scale.
The two scratch arrays are fields: a click allocates nothing (`ViewportTest.idleFramesAllocateNothing`
picks twenty times under `Allocations.assertNoAllocation`).

## 17. Step 4: `LevelLoader`

### 17.1 Fields and loading

```java
public final class LevelLoader {
    private static final Logger LOG = Log.logger("world");

    private final World world;
    private final AssetType<SceneData> scenes;
    private LevelData level;
    private int[] sceneHandle = new int[8]; // per prefab
    private int[] rootNode = new int[8]; // per prefab, resolved once the scene is ready
    private int[] placementRoot = new int[64]; // per placement index: the root entity, or NULL
    private final IntIntMap rootToPlacement = new IntIntMap(256, -1); // root entity → placement id
    private boolean ready;
    private boolean announced;
    private final float[] trs = new float[LevelData.PLACEMENT_FLOATS];

    public LevelLoader(World world, AssetType<SceneData> scenes) {
        this.world = world;
        this.scenes = scenes;
    }

    public void load(LevelData level) {
        unload();
        this.level = level;
        int prefabs = level.prefabCount();
        if (sceneHandle.length < prefabs) {
            sceneHandle = new int[prefabs];
            rootNode = new int[prefabs];
        }
        for (int p = 0; p < prefabs; p++) {
            sceneHandle[p] = scenes.load(level.prefabScene(p));
            rootNode[p] = -2; // unresolved
        }
        loadedPrefabs = prefabs;
        ensurePlacements(level.placementCount());
        Arrays.fill(placementRoot, 0, level.placementCount(), Handles.NULL);
        ready = false;
        announced = false;
    }
```

`loadedPrefabs` is one more field, declared further down the file next to `loadNewPrefabs`
(§17.3): `private int loadedPrefabs;`, how many of the level's prefabs have had their scenes
requested. `scenes.load(path)` asks the asset manager of chapter [10](10-assets.md) for the scene and returns
a handle at once; the bytes arrive on a loader thread later. `rootNode[p] = -2` means "not looked
up yet" (−1 will mean "the whole scene"). The crypt's fifteen prefabs are fifteen `load` calls for
the *same* path, which the asset manager deduplicates by reference count.

### 17.2 `update` and `instantiate`

```java
    public boolean update() {
        if (level == null || ready) {
            return false;
        }
        boolean all = true;
        for (int i = 0; i < level.placementCount(); i++) {
            if (placementRoot[i] != Handles.NULL) {
                continue;
            }
            int p = level.placementPrefab(i);
            if (!scenes.isReady(sceneHandle[p])) {
                if (scenes.error(sceneHandle[p]) != null && !announced) {
                    LOG.warn("prefab scene " + level.prefabScene(p) + " failed to load");
                    announced = true;
                }
                all = false;
                continue;
            }
            instantiate(i);
        }
        if (all) {
            ready = true;
        }
        return all;
    }

    private void instantiate(int i) {
        int p = level.placementPrefab(i);
        SceneData scene = scenes.get(sceneHandle[p]);
        if (rootNode[p] == -2) {
            String node = level.prefabNode(p);
            rootNode[p] = node.isEmpty() ? -1 : World.nodeNamed(scene, node);
            if (rootNode[p] == -1 && !node.isEmpty()) {
                LOG.warn("prefab node " + node + " not in " + level.prefabScene(p));
            }
        }
        int root =
                world.instantiateNode(
                        scene,
                        rootNode[p],
                        level.placementData(),
                        i * LevelData.PLACEMENT_FLOATS,
                        renderFlags(level.placementFlags(i)),
                        null,
                        true);
        if ((level.placementFlags(i) & LevelData.PLACEMENT_HIDDEN) != 0) {
            world.setGroupHidden(root, true);
        }
        placementRoot[i] = root;
        rootToPlacement.put(root, level.placementId(i));
    }
```

`update` is called every frame and is cheap once `ready`: it returns at the first line. Before
that, it walks the placements and instantiates those whose scene has arrived, so a level with
prefabs from several scenes appears piece by piece; it returns `true` once, on the frame that
instantiates the last one, which the hosts use to announce "level instantiated". A missing node
name is a warning, and the placement instantiates the whole scene rather than nothing (the level
still loads; the designer sees the wrong piece and the log line).

`renderFlags` translates the file's flags into the packet's (`FLAG_STATIC`, `FLAG_CASTS_SHADOW`,
`FLAG_FADE_OCCLUDER`, `FLAG_NO_DECALS`), with the D49 rule of §5.3: `STATIC` only when not
`HIDDEN`, written as `(flags & (STATIC | HIDDEN)) == STATIC`, a two-bit test in one mask.

### 17.3 The live-editing entry points

```java
    public void syncTransform(int i) {
        int root = placementRoot[i];
        if (root != Handles.NULL) {
            world.setGroupTransform(root, level.placementData(), i * LevelData.PLACEMENT_FLOATS);
        }
    }

    public void syncFlags(int i) {
        int root = placementRoot[i];
        if (root != Handles.NULL) {
            int flags = level.placementFlags(i);
            world.setGroupFlags(root, renderFlags(flags));
            world.setGroupHidden(root, (flags & LevelData.PLACEMENT_HIDDEN) != 0);
        }
    }

    public void placementInserted(int i) {
        int n = level.placementCount();
        ensurePlacements(n);
        System.arraycopy(placementRoot, i, placementRoot, i + 1, n - 1 - i);
        placementRoot[i] = Handles.NULL;
        loadNewPrefabs();
        int p = level.placementPrefab(i);
        if (scenes.isReady(sceneHandle[p])) {
            instantiate(i);
        } else {
            ready = false;
        }
    }

    public void placementRemoving(int i) {
        int root = placementRoot[i];
        if (root != Handles.NULL) {
            rootToPlacement.remove(root);
            world.destroyGroup(root);
        }
        int n = level.placementCount();
        System.arraycopy(placementRoot, i + 1, placementRoot, i, n - 1 - i);
        placementRoot[n - 1] = Handles.NULL;
    }

    public void placementReplaced(int i) {
        int root = placementRoot[i];
        if (root != Handles.NULL) {
            rootToPlacement.remove(root);
            world.destroyGroup(root);
            placementRoot[i] = Handles.NULL;
        }
        loadNewPrefabs();
        int p = level.placementPrefab(i);
        if (scenes.isReady(sceneHandle[p])) {
            instantiate(i);
        } else {
            ready = false;
        }
    }
```

- `placementInserted(i)` is called *after* the level inserted row $i$, so `level.placementCount()`
  is the new count and `placementRoot` shifts up by one from $i$. `placementRemoving(i)` is called
  *before* the level removes row $i$ (hence the `-ing`), so the index still names the entity to
  destroy; it shifts down and clears the last slot.
- `loadNewPrefabs` loads the scenes of prefabs appended since `load` (placing a prefab from a
  scene the level never used before, through the Assets panel) and grows the per-prefab arrays.
- A placement whose scene is still loading leaves `ready = false`, and the next `update` finishes
  it: nothing in the editor waits.
- `rootOf(id)` (id → root entity through `placementIndex`) and `placementOf(root)` (root → id
  through the map) are the two lookups the editor uses every frame; `rootAt(i)` is the raw array.

### 17.4 Lights every frame

```java
    public int extractLights(RenderPacket packet, float time, float gain) {
        int first = packet.lightCount;
        if (level == null) {
            return first;
        }
        float[] d = level.lightData();
        for (int i = 0, n = level.lightCount(); i < n; i++) {
            int o = i * LevelData.LIGHT_FLOATS;
            float intensity = d[o + 9] * gain;
            if ((level.lightFlags(i) & LevelData.LIGHT_FLICKER) != 0) {
                float t = time * 7 + i * 1.7f;
                intensity *= 0.85f + 0.15f * (float) Math.sin(t) * (float) Math.sin(t * 2.3f + 1);
            }
            if (level.lightType(i) == LevelData.LIGHT_SPOT) {
                packet.addSpotLight(
                        d[o], d[o + 1], d[o + 2], d[o + 3], d[o + 4], d[o + 5], d[o + 10], d[o + 6],
                        d[o + 7], d[o + 8], intensity, d[o + 11], d[o + 12]);
            } else {
                packet.addPointLight(
                        d[o], d[o + 1], d[o + 2], d[o + 10], d[o + 6], d[o + 7], d[o + 8],
                        intensity);
            }
        }
        return first;
    }
```

The flicker is two sines of incommensurate frequencies (7 and $7 \cdot 2.3$ rad/s) offset per
light by $1.7\,i$, so no two torches pulse together; the product stays within $[0.7, 1.0]$ of the
base intensity. The return value is the packet index of the first level light, so a host can give
some of them shadow maps (`EditorGame.extract` gives the first four with the `SHADOW` flag).
`LevelLoaderTest` holds this method at 0 bytes.

## 18. Step 5: the cooker's `LevelImporter`

### 18.1 The importer contract

An `Importer` in the cooker (chapter [10](10-assets.md)) declares a name, a version (bumped when
its output changes, so cached outputs are re-cooked), which paths it accepts, and `cook`. The
level importer's version is 2 since M22, when it began validating component rows:

```java
public final class LevelImporter implements Importer {
    private record Rule(Glob layer, int flags) {}

    @Override
    public String name() {
        return "level";
    }

    @Override
    public int version() {
        return 2; // component rows validated (M22)
    }

    @Override
    public boolean accepts(String path, ImportSettings settings) {
        return path.toLowerCase(Locale.ROOT).endsWith(".jlevel");
    }
```

### 18.2 `cook`

```java
    @Override
    public void cook(CookContext ctx) throws Exception {
        LevelData level = LevelData.read(MemorySegment.ofArray(ctx.bytes()));
        String base = ctx.outputBase();
        ctx.output(base + ".jlevel", ctx.bytes());
        ctx.stat("placements", level.placementCount());
        ctx.stat("lights", level.lightCount());
        ctx.stat("spawns", level.spawnCount());
        ctx.stat("triggers", level.triggerCount());
        ctx.stat("componentRows", level.componentRowCount());
        validateComponents(ctx, level);

        List<Rule> rules = new ArrayList<>();
        for (int r = 0; r < level.ruleCount(); r++) {
            int flags = level.ruleFlags(r);
            flags = TriFlags.withSurface(flags, level.ruleSurface(r));
            flags = TriFlags.withArea(flags, level.ruleArea(r));
            rules.add(new Rule(Glob.of(level.ruleLayer(r).toLowerCase(Locale.ROOT)), flags));
        }
        Map<Integer, Prefab> prefabs = new HashMap<>();
        TriangleMesh mesh = new TriangleMesh();
        float[] matrix = new float[16];
        for (int i = 0; i < level.placementCount(); i++) {
            int flags = collisionFlags(level, i, rules);
            if (flags == 0) {
                continue;
            }
            int p = level.placementPrefab(i);
            Prefab prefab = prefabs.get(p);
            if (prefab == null) {
                prefab = loadPrefab(ctx, level, p);
                prefabs.put(p, prefab);
            }
            if (prefab == null || prefab.root == -2) {
                continue;
            }
            placementMatrix(level, i, matrix);
            PhysicsBake.collectSubtree(prefab.scene, prefab.root, matrix, flags, mesh);
        }
        if (mesh.triangleCount() == 0) {
            ctx.warn("no collision triangles: no physics baked");
            return;
        }
        Bvh bvh = PhysicsBake.bakeCollision(ctx, mesh, base);

        LevelData.Settings s = level.settings;
        NavBakeConfig cfg =
                PhysicsImporter.navConfig(
                        s.navCellSize,
                        s.navCellHeight,
                        s.navTileSize,
                        s.navMaxSlope,
                        8,
                        20,
                        12,
                        1.3f);
        List<NavMeshBaker.AreaVolume> areas = new ArrayList<>();
        ...                                          // the level's areas, links and size classes
        if (sizes.isEmpty()) {
            sizes.add(new PhysicsBake.SizeClass("medium", 0.4f, 1.8f, 0.4f));
        }
        PhysicsBake.bakeNavmeshes(ctx, cfg, areas, links, sizes, mesh, base);
        if (s.occlusionEnabled) {
            PhysicsBake.bakeOcclusion(
                    ctx, bvh, s.occlusionCellSize, s.occlusionYMin, s.occlusionYMax, base);
        }
    }
```

- `ctx.output(base + ".jlevel", ctx.bytes())` copies the input bytes through: no re-encoding, so
  what the editor wrote is what the game reads (`LevelImporterTest` asserts `copied unchanged`).
- The rules are compiled once into `Glob`s (the cooker's glob matcher: `*`, `?`, `{a,b}`), lower-
  cased on both sides so `Wall` and `wall` match.
- Prefabs are loaded once each and cached in a map for the cook; a placement with zero flags never
  loads its prefab at all. A prefab whose glTF source is missing is `null` (with a warning); one
  whose root node is missing has `root == -2`; both are skipped.
- With no triangles there is nothing to bake, and the importer says so instead of writing empty
  files; that warning is how the children-before-parents bug of §6.3 was found.
- The navmesh configuration takes the level's four settings plus the fixed values the
  `physics.json` path uses (the region, edge and detail parameters of chapter 11); a level with no
  size classes gets `medium`.

### 18.3 Flags, matrices and the prefab's source

```java
    static int collisionFlags(LevelData level, int i, List<Rule> rules) {
        if ((level.placementFlags(i) & LevelData.PLACEMENT_NO_COLLISION) != 0) {
            return 0;
        }
        int override = level.placementCollision(i);
        if (override != LevelData.COLLISION_BY_LAYER) {
            return override;
        }
        String layer = level.placementLayer(i).toLowerCase(Locale.ROOT);
        for (Rule r : rules) {
            if (r.layer.matches(layer)) {
                return r.flags;
            }
        }
        return 0;
    }

    static void placementMatrix(LevelData level, int i, float[] out) {
        float[] d = level.placementData();
        int o = i * LevelData.PLACEMENT_FLOATS;
        float[] m =
                PhysicsImporter.trs(
                        new float[] {d[o], d[o + 1], d[o + 2]},
                        new float[] {d[o + 3], d[o + 4], d[o + 5], d[o + 6]},
                        new float[] {d[o + 7], d[o + 8], d[o + 9]});
        System.arraycopy(m, 0, out, 0, 16);
    }
```

The physics bake works in column-major 4×4 matrices (the glTF convention the importer already
spoke), so the placement's TRS becomes one through `PhysicsImporter.trs`. `loadPrefab` maps the
cooked scene path back to its source (`levels/dungeon.jscene` → `levels/dungeon.glb` or `.gltf`,
case-insensitively, through `findSource`), reads the glTF as a **dependency** (`ctx.readDependency`:
the cooker records it, so a change to the kit re-cooks the level), applies the glTF's own import
settings (`.meta` sidecar: a node excluded from the cooked scene, like the crypt's door leaves, is
excluded from the collision too), imports the scene, and finds the root node by name. Then
`PhysicsBake.collectSubtree` gathers the triangles:

```java
    public static void collectSubtree(
            GltfImporter.Scene scene,
            int rootNode,
            float[] rootMatrix,
            int flags,
            TriangleMesh mesh) {
        List<GltfImporter.Node> nodes = scene.nodes();
        int n = nodes.size();
        float[][] rel = new float[n][];
        byte[] state = new byte[n]; // 0 unknown, 1 inside the subtree, 2 outside
        for (int i = 0; i < n; i++) {
            if (nodes.get(i).mesh() < 0) {
                continue;
            }
            float[] m = relative(nodes, i, rootNode, rootMatrix, rel, state);
            if (m != null) {
                PhysicsImporter.appendMesh(scene, nodes.get(i).mesh(), m, flags, mesh);
            }
        }
    }

    private static float[] relative(
            List<GltfImporter.Node> nodes,
            int i,
            int rootNode,
            float[] rootMatrix,
            float[][] rel,
            byte[] state) {
        if (state[i] == 2) {
            return null;
        }
        if (state[i] == 1) {
            return rel[i];
        }
        GltfImporter.Node node = nodes.get(i);
        int p = node.parent();
        float[] local = PhysicsImporter.trs(node.translation(), node.rotation(), node.scale());
        float[] parent;
        if (rootNode < 0 ? p < 0 : i == rootNode) {
            parent = rootMatrix; // a direct child of the placement
        } else if (p < 0) {
            state[i] = 2; // another root: not in the subtree
            return null;
        } else {
            parent = relative(nodes, p, rootNode, rootMatrix, rel, state);
            if (parent == null) {
                state[i] = 2;
                return null;
            }
        }
        rel[i] = PhysicsImporter.mul(parent, local);
        state[i] = 1;
        return rel[i];
    }
```

This is the memoized recursion of §6.3. `state` has three values so that a node outside the
subtree (another piece's child) is answered with `null` at once the second time it is asked,
instead of climbing its parent chain again; `rel[i]` caches a node's matrix *relative to the
placement* (root matrix × the chain of locals from the subtree root down), so a mesh deep in a
piece costs one climb for the whole cook. `appendMesh` transforms the triangles and flips the
winding when the matrix mirrors (a negative determinant), so a mirrored kit piece still has its
normals facing out.

The cooker also validates the level's component rows against the game's schema when the cook has
one (`--components`: unknown components, columns and enum constants are warnings, since the
runtime skips them too), and `LevelBindings` generates a `<Level>Level.java` of the named objects'
ids (D110); both belong to chapter [22](22-components.md).

## 19. Step 6: `Command` and `UndoStack`

### 19.1 The interface

```java
public interface Command {
    void apply(EditorContext ctx);

    void revert(EditorContext ctx);

    String name();

    default boolean mergeWith(Command next) {
        return false;
    }
}
```

Four methods. `apply` and `revert` get the context (§21.1) because a command must reach the level
*and* tell the world through it. `name` labels the Edit menu, the console line and the History
panel ("Move 3 objects"). `mergeWith` is a default method returning `false`, so a command that
cannot merge (a delete, a duplicate) writes nothing; one that can keeps its *before* and takes
`next`'s *after* and returns `true`. The contract in its Javadoc: `next` has *already been applied*
when `mergeWith` is called, so the merging command only has to absorb the data, never to apply it.

### 19.2 The stack

```java
public final class UndoStack {
    private final ArrayList<Command> commands = new ArrayList<>();
    private final EditorContext ctx;
    private final int capacity;
    private int cursor; // commands [0, cursor) are applied
    private int savedAt; // the cursor at the last save, or -1 once that state is unreachable

    public UndoStack(EditorContext ctx, int capacity) {
        this.ctx = ctx;
        this.capacity = capacity;
    }

    public void push(Command command) {
        push(command, false);
    }

    public void push(Command command, boolean merge) {
        command.apply(ctx);
        if (cursor < commands.size()) {
            commands.subList(cursor, commands.size()).clear(); // the redo tail is gone
            if (savedAt > cursor) {
                savedAt = -1;
            }
        }
        if (merge
                && cursor > 0
                && cursor != savedAt
                && commands.get(cursor - 1).mergeWith(command)) {
            return;
        }
        commands.add(command);
        cursor++;
        if (commands.size() > capacity) {
            commands.removeFirst();
            cursor--;
            savedAt = savedAt < 0 ? -1 : savedAt - 1;
        }
    }
```

Read `push` in order:

1. **Apply first.** Whatever happens to the history, the edit happens now.
2. **Drop the redo tail.** Entries from `cursor` on were undone; a new edit makes them
   unreachable. If the saved state was among them (`savedAt > cursor`), it is gone: −1 (§7.3).
3. **Try to merge.** Only if asked (`merge`), only if there is a previous entry, only if that entry
   is not the saved state (`cursor != savedAt`), and only if the previous entry agrees. On a merge
   the new command is *not* added; it was applied in step 1 and its data now lives in the previous
   entry.
4. **Append and advance.** Past `capacity` (the editor uses 256), forget the oldest entry: the
   cursor and the saved mark move down with it, and a saved mark at 0 becomes −1 because the
   state before the forgotten entry can no longer be reached.

The `ArrayList` of commands is an exception to the no-objects rule, deliberately: a few hundred
command objects, created on user actions, are the right tool.

```java
    public boolean undo() {
        if (cursor == 0) {
            return false;
        }
        commands.get(--cursor).revert(ctx);
        return true;
    }

    public boolean redo() {
        if (cursor == commands.size()) {
            return false;
        }
        commands.get(cursor++).apply(ctx);
        return true;
    }

    public Command top() {
        return cursor > 0 ? commands.get(cursor - 1) : null;
    }

    public void cancelTop() {
        if (cursor == 0) {
            return;
        }
        commands.get(--cursor).revert(ctx);
        commands.remove(cursor);
        if (savedAt > cursor) {
            savedAt = -1;
        }
    }

    public void markSaved() {
        savedAt = cursor;
    }

    public boolean isDirty() {
        return cursor != savedAt;
    }

    public void clear() {
        commands.clear();
        cursor = 0;
        savedAt = 0;
    }

    public void seek(int target) {
        while (cursor > target && undo()) {}
        while (cursor < target && redo()) {}
    }
```

`--cursor` and `cursor++` inside the index expression are the usual idiom for "move, then act on
the entry at the boundary": undo reverts the entry just below the cursor, redo applies the one at
it. `cancelTop` is undo plus removal, for a drag abandoned with Escape; it cannot leave a redo
entry because the drag never existed as far as the user is concerned. `clear` marks the state
saved, because a freshly opened level is clean. `seek` is what the History panel calls when a row
is clicked. `canUndo`, `canRedo`, `undoName`, `redoName`, `size`, `cursor` and `commandAt` are the
one-line accessors the menus and the panel read.

`UndoStackTest` (§31.4) drives all of this with a trivial `Add` command on a counter, including the
capacity case: six pushes into a stack of four leave the two oldest unreachable, and `seek(0)`
stops at $1 + 2 = 3$.

## 20. Step 7: the commands

Twelve commands, in the order a reader meets them: the one a drag uses, the row snapshot that
makes add and delete symmetric, the three field setters, the settings swap, the table command, and
the two component commands.

### 20.1 `TransformCommand`

```java
public final class TransformCommand implements Command {
    private final int[] ids;
    private final int[] offset;
    private final int[] floats;
    private final float[] before;
    private final float[] after;
    private final String name;

    public TransformCommand(LevelData level, int[] ids, int n, String name) {
        this.ids = Arrays.copyOf(ids, n);
        this.offset = new int[n];
        this.floats = new int[n];
        this.name = name;
        int total = 0;
        for (int i = 0; i < n; i++) {
            offset[i] = total;
            floats[i] = LevelObjects.floats(level.kindOf(ids[i]));
            total += floats[i];
        }
        before = new float[total];
        after = new float[total];
        for (int i = 0; i < n; i++) {
            LevelObjects.copyRow(level, ids[i], before, offset[i]);
        }
        System.arraycopy(before, 0, after, 0, total);
    }
```

The rows of the selected objects, whatever their kinds (10 floats for a placement, 13 for a light,
4 for a spawn, 7 for a trigger), are packed back to back in one `before` and one `after` array;
`offset[i]` says where object $i$'s row starts and `floats[i]` how long it is. `Arrays.copyOf(ids,
n)` takes a private copy, because the caller passes the selection's live array. At construction
*after* equals *before*: nothing has changed yet.

```java
    public void setAfter(int i, int k, float v) {
        after[offset[i] + k] = v;
    }

    public boolean changed() {
        return !Arrays.equals(before, after);
    }

    @Override
    public void apply(EditorContext ctx) {
        write(ctx, after);
    }

    @Override
    public void revert(EditorContext ctx) {
        write(ctx, before);
    }

    private void write(EditorContext ctx, float[] rows) {
        LevelData level = ctx.level();
        for (int i = 0; i < ids.length; i++) {
            LevelObjects.writeRow(level, ids[i], rows, offset[i]);
            ctx.syncTransform(ids[i]);
        }
    }

    // ... name() returns the label given at construction ("Move", "Rotate 3 objects")

    @Override
    public boolean mergeWith(Command next) {
        if (!(next instanceof TransformCommand t) || !Arrays.equals(ids, t.ids)) {
            return false;
        }
        System.arraycopy(t.after, 0, after, 0, after.length);
        return true;
    }
}
```

`apply` and `revert` are the same `write` with the other array: every object's row goes back into
the level through `LevelObjects.writeRow` and the world is told. `changed()` is how the viewport
knows whether a press that never moved should be pushed at all (it should not: a click on a
handle that goes nowhere must leave no history entry). `mergeWith` accepts another transform of the
*same ids in the same order* and takes its `after` wholesale: the Inspector's rotation field uses
that to fold successive edits. The accessors `count`, `id(i)`, `offset(i)`, `floats(i)`, `before()`
and `after()` are what the viewport's `write*` methods read and write (§23.7).

### 20.2 `ObjectRow`

A delete must remember everything about a row, whatever its kind, to put it back; an add is a
delete run backwards; a duplicate is an add of copies. One class holds that snapshot:

```java
public final class ObjectRow {
    public ObjectRow() {}

    public int kind;
    public int index;
    public int id;
    public String name = "";
    public int prefab;
    public String layer = "";
    public int flags;
    public int collision = LevelData.COLLISION_BY_LAYER;
    public int type = LevelData.LIGHT_POINT;
    public String kind2 = "";
    public String payload = "";
    public final float[] data = new float[LevelData.LIGHT_FLOATS];

    public String[] components = new String[0];
    public String[][] columns = new String[0][];
    public int[][] types = new int[0][];
    public long[][] bits = new long[0][];
    public String[][] texts = new String[0][];
```

Public fields and a union-like layout: which of them matter depends on `kind` (a placement uses
`prefab`, `layer`, `flags`, `collision`; a light `type` and `flags`; a spawn `kind2`; a trigger
`kind2`, `payload`, `flags`). `data` is sized for the longest row (13 floats). `kind2` is the
spawn's or trigger's *kind string*, named so because `kind` is already the object kind. The
component arrays hold the object's rows (§14.5) so that a deleted object comes back with its
components.

```java
    public ObjectRow capture(LevelData level, int objectId) {
        kind = level.kindOf(objectId);
        index = LevelObjects.index(level, objectId);
        id = objectId;
        switch (kind) {
            case LevelData.KIND_PLACEMENT -> {
                name = level.placementName(index);
                prefab = level.placementPrefab(index);
                layer = level.placementLayer(index);
                flags = level.placementFlags(index);
                collision = level.placementCollision(index);
            }
            case LevelData.KIND_LIGHT -> {
                name = level.lightName(index);
                type = level.lightType(index);
                flags = level.lightFlags(index);
            }
            case LevelData.KIND_SPAWN -> {
                name = level.spawnName(index);
                kind2 = level.spawnKind(index);
            }
            case LevelData.KIND_TRIGGER -> {
                name = level.triggerName(index);
                kind2 = level.triggerKind(index);
                payload = level.triggerPayload(index);
                flags = level.triggerFlags(index);
            }
            default -> throw new IllegalArgumentException("no object " + objectId);
        }
        LevelObjects.copyRow(level, objectId, data, 0);
        ...                                          // then the component rows, counted and copied
        return this;
    }

    public void insert(LevelData level, LevelLoader loader) {
        switch (kind) {
            case LevelData.KIND_PLACEMENT -> {
                level.insertPlacement(index, id, prefab, name, layer, flags, collision);
                level.setPlacementTransform(index, data, 0);
                if (loader != null && loader.level() == level) {
                    loader.placementInserted(index);
                }
            }
            case LevelData.KIND_LIGHT -> level.insertLight(index, id, type, name, flags);
            case LevelData.KIND_SPAWN -> level.insertSpawn(index, id, name, kind2);
            case LevelData.KIND_TRIGGER ->
                    level.insertTrigger(index, id, name, kind2, payload, flags);
            default -> throw new IllegalStateException("kind " + kind);
        }
        if (kind != LevelData.KIND_PLACEMENT) {
            LevelObjects.writeRow(level, id, data, 0);
        }
        for (int c = 0; c < components.length; c++) {
            int row = level.addComponentRow(id, components[c]);
            for (int k = 0; k < columns[c].length; k++) {
                level.setComponentValue(row, columns[c][k], types[c][k], bits[c][k], texts[c][k]);
            }
        }
    }

    public void remove(LevelData level, LevelLoader loader) {
        int i = LevelObjects.index(level, id);
        if (i < 0) {
            throw new IllegalStateException("object " + id + " is not in the level");
        }
        index = i;
        switch (kind) {
            case LevelData.KIND_PLACEMENT -> {
                if (loader != null && loader.level() == level) {
                    loader.placementRemoving(i);
                }
                level.removePlacement(i);
            }
            case LevelData.KIND_LIGHT -> level.removeLight(i);
            case LevelData.KIND_SPAWN -> level.removeSpawn(i);
            case LevelData.KIND_TRIGGER -> level.removeTrigger(i);
            default -> throw new IllegalStateException("kind " + kind);
        }
    }
```

- `insert` puts the row back **at `index` with its `id`**, which is the whole point of §4; for a
  placement the transform is written before the loader is told, so the entity appears at the
  right place, and the loader is only told when it is showing *this* level.
- `remove` re-reads the index (`index = i`) because rows may have shifted since capture (another
  object deleted first); the index it records is the one `insert` will use. The loader is told
  *before* the row goes (§17.3).
- `copyOf(other, newId)` copies every field and clones the component arrays, for duplicates.

### 20.3 `DeleteObjectsCommand` and `AddObjectsCommand`

```java
public final class DeleteObjectsCommand implements Command {
    private final ObjectRow[] rows;
    private final String name;

    public DeleteObjectsCommand(LevelData level, int[] ids, int n) {
        rows = new ObjectRow[n];
        for (int i = 0; i < n; i++) {
            rows[i] = new ObjectRow().capture(level, ids[i]);
        }
        // Ascending by (kind, index): inserting in this order restores every index.
        Arrays.sort(
                rows,
                Comparator.<ObjectRow>comparingInt(r -> r.kind).thenComparingInt(r -> r.index));
        name = n == 1 ? "Delete " + rows[0].name : "Delete " + n + " objects";
    }

    @Override
    public void apply(EditorContext ctx) {
        for (int i = rows.length - 1; i >= 0; i--) {
            rows[i].remove(ctx.level(), ctx.loader());
        }
    }

    @Override
    public void revert(EditorContext ctx) {
        for (ObjectRow r : rows) {
            r.insert(ctx.level(), ctx.loader());
        }
    }
```

The ordering argument, with numbers. Delete placements at indices 2 and 5 of a table of 8.
Removing 2 first shifts 5 to 4; removing "5" would then remove the wrong row. Removing in
**descending** index order (5, then 2) keeps every captured index valid. Reinserting in
**ascending** order (2, then 5) puts 2 back first, which shifts the old 4 to 5, and then 5 lands
in its own slot. Sorting by `(kind, index)` once at construction and walking the array in the two
directions is all it takes; the `Comparator` with lambdas is fine here, a cold path.

`AddObjectsCommand` is the mirror: `apply` inserts in ascending order, `revert` removes in
descending. Its factories build one `ObjectRow` each for a placement (identity rotation `data[6] =
1`, unit scale, `COLLISION_BY_LAYER`), a point light, a spawn and a trigger, each with
`level.allocateId()` called **once, at construction**: redo reuses the same id, so a selection made
of the new object survives an undo–redo pair (`addDeleteAndDuplicateKeepIdsAndIndices` asserts the
id "is allocated once and kept through undo/redo"). `duplicate`:

```java
    public static AddObjectsCommand duplicate(
            LevelData level, int[] ids, int n, float dx, float dy, float dz) {
        ObjectRow[] rows = new ObjectRow[n];
        int placements = level.placementCount();
        int lights = level.lightCount();
        int spawns = level.spawnCount();
        int triggers = level.triggerCount();
        for (int i = 0; i < n; i++) {
            ObjectRow src = new ObjectRow().capture(level, ids[i]);
            ObjectRow r = new ObjectRow().copyOf(src, level.allocateId());
            r.index =
                    switch (r.kind) {
                        case LevelData.KIND_PLACEMENT -> placements++;
                        case LevelData.KIND_LIGHT -> lights++;
                        case LevelData.KIND_SPAWN -> spawns++;
                        default -> triggers++;
                    };
            r.name = src.name.isEmpty() ? "" : src.name + " copy";
            r.data[0] += dx;
            r.data[1] += dy;
            r.data[2] += dz;
            rows[i] = r;
        }
        return new AddObjectsCommand(rows, n == 1 ? "Duplicate" : "Duplicate " + n + " objects");
    }
```

Copies are *appended*: each gets the next free index of its kind, counted up per kind as the
loop goes, so two duplicated placements land at `count` and `count + 1`. The offset `(dx, dy, dz)`
is one grid step along $+X$ from the editor (`duplicateSelection`), so the copy does not sit
exactly on the original. Position is `data[0..2]` for every kind (§3.3), which is why one line
moves any object.

### 20.4 `SetFloatCommand`, `SetIntCommand`, `SetStringCommand`

Three small classes over a list of ids (the Inspector applies one value to the *whole* selection)
with the old value per id. The float one:

```java
public final class SetFloatCommand implements Command {
    private final int k;
    private final int[] ids;
    private final float[] before;
    private float after;
    private final String name;

    public SetFloatCommand(LevelData level, int[] ids, int n, int k, float after, String name) {
        this.k = k;
        this.ids = Arrays.copyOf(ids, n);
        this.before = new float[n];
        this.after = after;
        this.name = name;
        for (int i = 0; i < n; i++) {
            before[i] = LevelObjects.get(level, ids[i], k);
        }
    }

    @Override
    public void apply(EditorContext ctx) {
        for (int id : ids) {
            LevelObjects.set(ctx.level(), id, k, after);
            ctx.syncTransform(id);
        }
    }

    @Override
    public void revert(EditorContext ctx) {
        for (int i = 0; i < ids.length; i++) {
            LevelObjects.set(ctx.level(), ids[i], k, before[i]);
            ctx.syncTransform(ids[i]);
        }
    }

    // ... name() returns the label given at construction ("Move", "Intensity")

    @Override
    public boolean mergeWith(Command next) {
        if (next instanceof SetFloatCommand s && s.k == k && Arrays.equals(ids, s.ids)) {
            after = s.after;
            return true;
        }
        return false;
    }
}
```

`before` is an array (one per object: three selected walls at different $x$ all get the typed
$x$, and undo gives each its own back), `after` a single value. `after` is not `final` because
merging replaces it. `syncTransform` is called for any float: it is cheap, and floats 0–9 of a
placement are its transform; for a light's intensity it reaches `EditorContext.syncTransform`,
which does nothing for a non-placement.

`SetStringCommand` is the same over an `ObjectField` (§20.5) with `getString`/`setString`.
`SetIntCommand` adds the per-object **flag** variant: `SetIntCommand.flag(level, ids, n, bit, set)`
stores a `perObjectBit`, and `apply` computes each object's after value as `before[i] | bit` or
`before[i] & ~bit` (L00 §6.2), so "set Hidden on these three" keeps each one's other flags, which
`fieldCommandsSetAndMerge` checks ("the wall keeps its other flags"). A flag command never merges
(its after differs per object). `write` routes flags to `syncFlags` and a prefab change to
`syncPrefab`, which re-instantiates the placement.

### 20.5 `ObjectField`

An `enum` naming the non-float fields, `NAME`, `LAYER`, `KIND`, `PAYLOAD`, `FLAGS`, `COLLISION`,
`PREFAB`, `LIGHT_TYPE`, with `getString`/`setString` and `getInt`/`setInt` that switch on the field
and the object's kind. It exists so the Inspector's rows and the two commands can say *which*
field without a class per field; a field that is not a string throws from `getString`, which is
a programming error, not a user one.

### 20.6 `SetSettingsCommand` and `TableCommand`

`SetSettingsCommand` snapshots the whole settings block before and after (two `Settings` objects,
`copyFrom` each way), applies with `copyFrom` plus `touch()`, and merges with the next settings
change, so a dragged slider is one entry; the Settings panel builds the *after* in a scratch
`Settings` every frame and pushes only when something differs.

`TableCommand` covers the four tables without ids by index, as `ADD`, `REMOVE` or `SET` of a `Row`:
a text, up to three ints and a float array, laid out per `Table` (`RULES`: text = layer glob, ints
= flags, surface, area; `SIZE_CLASSES`: text = name, floats = radius, height, climb; `AREAS`: ints[0]
= area, floats = yMin, yMax, then the polygon; `LINKS`: ints = flags, area, user id, floats = start,
end, radius). `apply` of `ADD` inserts *after* at `index`, of `REMOVE` removes, of `SET` writes
*after*; `revert` does the opposite with *before*. Only `SET` merges, with a `SET` of the same table
and index. `settingsAndTablesRoundTrip` inserts a rule at 0, removes it, changes one, and adds a
size class, an area and a link, each round-tripped byte for byte.

### 20.7 `ComponentRowCommand` and `SetComponentValueCommand`

`ComponentRowCommand.add(level, ids, n, name)` adds a component row to every selected object
(those that have it keep theirs) and `remove` takes it away; a removal keeps each object's values
so that undo restores them, and an addition remembers which objects had no row, so that undo
removes only those. `SetComponentValueCommand` sets one column of one component on every selected
object *that has the component* (objects without the row are left alone), remembering per object
whether the column was at its default (`beforeType = -1`), so undo removes the value again rather
than writing a default of its own; it merges with the next set of the same `component.column` on
the same ids. Both call `ctx.syncComponents(id)`, which a running game answers by despawning the
object's entity and loading its rows again (chapter [22](22-components.md)).
`componentRowsAreCommandsAndFollowDeletesAndDuplicates` round-trips every case, including a delete
that takes the rows along and a duplicate that copies them.

## 21. Step 8: the context, the selection and the small helpers

### 21.1 `EditorContext`

Commands and panels do not see the `Editor`; they see a context that holds what they may touch:

```java
public final class EditorContext {
    private LevelData level;
    private LevelLoader loader;
    private World world;
    private final Selection selection = new Selection();
    private final UndoStack undo;
    private final EditorSettings settings = new EditorSettings();
    private final Console console = new Console(256);
    private final IsoCamera camera = new IsoCamera();
    private Ui ui;
    private Widgets widgets;
    private Editor editor;
    private Viewport viewport;
    private Vfs vfs;
    private AssetType<SceneData> scenes;
    private RulesSource rules;
    private ComponentSchema schema = ComponentSchema.EMPTY;
    private ComponentSync componentSync;

    public interface ComponentSync {
        void componentsChanged(LevelData level, int objectId);
    }

    public EditorContext(LevelData level, int undoCapacity) {
        this.level = level;
        this.undo = new UndoStack(this, undoCapacity);
    }
```

The world is optional: the command tests edit a level with no world, so every `sync*` method
checks that a loader is attached *and showing this level* before it forwards:

```java
    private boolean showing() {
        return loader != null && loader.level() == level;
    }

    public void syncTransform(int id) {
        if (showing() && level.kindOf(id) == LevelData.KIND_PLACEMENT) {
            loader.syncTransform(level.placementIndex(id));
        }
    }

    public void syncFlags(int id) {
        if (showing() && level.kindOf(id) == LevelData.KIND_PLACEMENT) {
            loader.syncFlags(level.placementIndex(id));
        }
    }

    public void syncPrefab(int id) {
        if (showing() && level.kindOf(id) == LevelData.KIND_PLACEMENT) {
            loader.placementReplaced(level.placementIndex(id));
        }
    }

    public void syncComponents(int id) {
        if (componentSync != null && (loader == null || showing())) {
            componentSync.componentsChanged(level, id);
        }
    }
```

Only placements have anything in the world to sync; the id-to-index lookup is the O(1) map of
§4.3. The setters (`setLevel`, `setWorld`, `setUi`, ...) are package-private: only the `Editor`
wires the context. `ui()` and `widgets()` are non-null only during `declare`.

### 21.2 `Selection`

```java
public final class Selection {
    private final IntList ids = new IntList(32);
    private final IntSet set = new IntSet(32);
    private int primary = -1;
    private int version;

    public void select(int id) {
        if (ids.size() == 1 && primary == id) {
            return;
        }
        clear();
        add(id);
    }

    public void add(int id) {
        if (set.add(id)) {
            ids.add(id);
        }
        primary = id;
        version++;
    }

    public void remove(int id) {
        if (!set.remove(id)) {
            return;
        }
        ids.removeAt(ids.indexOf(id));
        if (primary == id) {
            primary = ids.isEmpty() ? -1 : ids.get(ids.size() - 1);
        }
        version++;
    }

    public void toggle(int id) {
        if (set.contains(id)) {
            remove(id);
        } else {
            add(id);
        }
    }

    public void clear() {
        if (ids.isEmpty()) {
            return;
        }
        ids.clear();
        set.clear();
        primary = -1;
        version++;
    }
```

Two containers for one set: the `IntList` keeps selection *order* (the Inspector leads with the
primary, a drag's command packs rows in this order) and the `IntSet` answers `contains` in O(1)
for the Outliner's 372 rows per frame. `add` makes the added id the **primary** even if it was
already selected (clicking a selected object with Shift makes it the gizmo's pivot). `select` is a
no-op when the single selected object is clicked again, so the version does not change and the
highlights are not rebuilt. `set(ids, n)` and `setPrimary(id)` complete the API; `ids()` exposes
the list for commands to copy from.

### 21.3 `LevelObjects`

Static functions that make "an object of any kind" one thing for the editor: `floats(kind)` (the
row length), `index(level, id)` (the row, switching on `kindOf`), `exists`, `get(level, id, k)` and
`set(level, id, k, v)` (float $k$ of the row), `copyRow`/`writeRow` (a whole row to or from an
array, with `System.arraycopy` for the copy and the kind's setter per float for the write, so the
version bumps), `position` (floats 0–2 of any kind: the convention of §3.3), `name`, `kindName`,
and the two offsets the gizmo switches on:

```java
    public static int rotationOffset(int kind) {
        return switch (kind) {
            case LevelData.KIND_PLACEMENT -> 3;
            case LevelData.KIND_SPAWN -> 3;
            case LevelData.KIND_TRIGGER -> 6;
            default -> -1;
        };
    }

    public static int scaleOffset(int kind) {
        return switch (kind) {
            case LevelData.KIND_PLACEMENT -> 7;
            case LevelData.KIND_TRIGGER -> 3;
            default -> -1;
        };
    }
```

A light has no rotation offset: its direction is a vector at floats 3–5, rotated as a vector.

### 21.4 `EditorSettings` and `Console`

`EditorSettings` is a class of public fields with the defaults: `snap = true`, `translateStep =
0.5f` (replaced by the level's grid step when a level opens), `rotateStepDegrees = 15`,
`scaleStep = 0.25f`, `showGrid`, `showMarkers`, `showBounds`, `gizmoPixels = 90`, `orbitSpeed =
0.006f` radians per pixel, `zoomStep = 1.15f` per wheel notch. The Settings panel edits them
directly (the editor's own preferences are not undoable) and the host persists them under
`editor.*` (§28.4).

`Console` is a ring of the last 256 lines with a severity each, mirrored to the `editor` logger.
`add` computes the slot as `(start + count) % lines.length` and, when full, advances `start`
instead of growing (L00 §2.4: `mod` is a ring); `line(i)` and `level(i)` read oldest-first with the
same modulo. Lines are strings made when logged (cold); reading them back each frame allocates
nothing, and `version()` tells the Console panel to scroll to the end.

### 21.5 `LevelFiles`: save on another thread

```java
    public static LevelData read(Path path) throws IOException, AssetFormatException {
        try (Arena arena = Arena.ofConfined();
                FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            MemorySegment m = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size(), arena);
            return LevelData.read(m);
        }
    }

    public static Save save(LevelData level, Path path) {
        byte[] bytes = level.toBytes();
        Save save = new Save(path);
        Thread.ofVirtual()
                .name("level-save")
                .start(
                        () -> {
                            try {
                                Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
                                Path parent = path.getParent();
                                if (parent != null) {
                                    Files.createDirectories(parent);
                                }
                                Files.write(tmp, bytes);
                                Files.move(
                                        tmp,
                                        path,
                                        StandardCopyOption.REPLACE_EXISTING,
                                        StandardCopyOption.ATOMIC_MOVE);
                                save.result.set(Save.DONE);
                            } catch (Throwable t) {
                                save.result.set(t);
                            }
                        });
        return save;
    }
```

- **Read** maps the file into a confined arena (L00 §12): no copy, and the segment is freed when
  the arena closes at the end of the `try`, after `LevelData.read` has copied everything into its
  own arrays.
- **Save** serializes on the game thread (`toBytes`, about 0.1 ms for the crypt, §15.2), then hands
  the byte array to a **virtual thread** that writes a temporary file and renames it over the
  target with `ATOMIC_MOVE`: a crash in the middle of the write leaves the old level intact and a
  `.tmp` beside it, never a truncated `.jlevel` (L04 §15, the same technique the save system
  uses). A virtual thread is the right tool for a blocking write that happens a few
  times a minute: it costs almost nothing to create and parks while the disk works. The
  alternatives: writing on the game thread (a file write is milliseconds and unbounded: a hitch
  every Ctrl+S), and a platform worker thread kept between saves. The game's own save system
  (chapter [16](16-saves.md)) chose the latter (D118), because a game save must allocate nothing
  after the first, and unparking a virtual thread allocates a scheduler task (24 bytes per save,
  measured there). The editor's save allocates anyway (the byte array, the `Save`, the lambda), so
  that constraint does not apply, and the code really does start a virtual thread per save
  (`Thread.ofVirtual().name("level-save").start(...)` in `LevelFiles.save`); there is no decision
  record for it.
- **The result crosses threads** through an `AtomicReference<Object>` that is `null` while the
  write runs, `DONE` on success or the `Throwable` on failure (L00 §9.5: a volatile-style publish;
  the game thread polls `isDone()` every frame in `Editor.pollSave` and reads the error after). The
  lambda captures `bytes`, `path` and `save`: an allocation, on a cold path.

`dumpJson` writes `<name>.json` next to the level with `Files.writeString`.

### 21.6 `ViewportInput` and `Text`

`ViewportInput` is one frame of pointer input as a plain struct of public fields: position and
movement, wheel, which buttons are held and which went down this frame, the modifiers, the
window size and `uiWantsPointer`. `copyFrom(in, ui)` fills it from the engine's `Input` and the UI,
multiplying positions, deltas and the window size by `Input.uiScale()` so that everything is in the
UI's logical pixels, the units of the dock's region (on a 150 % Windows desktop window
coordinates are pixels and the UI's units are pixels / 1.5; chapter [13](13-ui.md) explains D102).
The tests fill it by hand and call `endFrame()` to clear the per-frame events. Keys are not in it:
the `Editor` handles shortcuts.

`Text` holds two allocation-free helpers the panels filter with: `containsIgnoreCase(hay, needle)`
(a nested loop with `Character.toLowerCase` per char; an empty needle matches) and `equals(a, b)`
over `CharSequence`s, so a `TextBuffer` can be compared with a `String` without `toString()`.

## 22. Step 9: `GizmoMath`

A class of static functions on floats, stateless, so the tests can hand it rays and check the
numbers of §10. The first three were quoted and derived there; here is the rest of the file in the
order it is written.

### 22.1 `closestAxisParam`

```java
    public static float closestAxisParam(
            float ox,
            float oy,
            float oz,
            float dx,
            float dy,
            float dz,
            float cx,
            float cy,
            float cz,
            float ax,
            float ay,
            float az,
            float fallback) {
        float wx = ox - cx;
        float wy = oy - cy;
        float wz = oz - cz;
        float b = ax * dx + ay * dy + az * dz;
        float denom = 1 - b * b;
        if (denom < 1e-4f) {
            return fallback;
        }
        float wa = wx * ax + wy * ay + wz * az;
        float wd = wx * dx + wy * dy + wz * dz;
        return (wa - b * wd) / denom;
    }
```

Thirteen `float` parameters rather than three arrays: no object, no array, nothing to allocate or
bounds-check, and the JIT keeps them in registers. The threshold $10^{-4}$ on $\sin^2\alpha$ is an
angle of about 0.57° between the ray and the axis.

### 22.2 `rayPlanePoint`

```java
    public static boolean rayPlanePoint(
            float ox, float oy, float oz,
            float dx, float dy, float dz,
            float cx, float cy, float cz,
            float nx, float ny, float nz,
            float[] out, int o) {
        float d = -(nx * cx + ny * cy + nz * cz);
        float t = Intersect.rayPlane(ox, oy, oz, dx, dy, dz, nx, ny, nz, d);
        if (t == Intersect.MISS) {
            return false;
        }
        out[o] = ox + dx * t;
        out[o + 1] = oy + dy * t;
        out[o + 2] = oz + dz * t;
        return true;
    }
```

(Parameters condensed onto fewer lines; the file has one per line.) `d` is the plane's $d_0 =
-\mathbf{n}\cdot\mathbf{c}$ of §10.2; `Intersect.rayPlane` returns `MISS` (positive infinity) for a
parallel ray or a hit behind the origin, and the method leaves `out` untouched then, so a caller
can keep its previous point.

### 22.3 `signedAngle` and `snap`

```java
    public static float signedAngle(
            float v0x, float v0y, float v0z,
            float v1x, float v1y, float v1z,
            float ax, float ay, float az) {
        float cx = v0y * v1z - v0z * v1y;
        float cy = v0z * v1x - v0x * v1z;
        float cz = v0x * v1y - v0y * v1x;
        float sin = cx * ax + cy * ay + cz * az;
        float cos = v0x * v1x + v0y * v1y + v0z * v1z;
        return (float) Math.atan2(sin, cos);
    }

    public static float snap(float v, float step) {
        return step > 0 ? Math.round(v / step) * step : v;
    }
```

(Parameters condensed again.) The three `c` lines are the cross product written out (chapter 02), then its dot with the axis
and the plain dot product: the two arguments of §10.3's `atan2`, up to the common factor it
ignores. `snap` with `step <= 0` returns the value unchanged, so "no snapping" is `step = 0` and
no caller needs a branch.

### 22.4 `project`

```java
    public static boolean project(
            float[] viewProjection,
            float x,
            float y,
            float z,
            float rx,
            float ry,
            float rw,
            float rh,
            float[] out,
            int o) {
        float[] m = viewProjection;
        float px = m[0] * x + m[4] * y + m[8] * z + m[12];
        float py = m[1] * x + m[5] * y + m[9] * z + m[13];
        float pz = m[2] * x + m[6] * y + m[10] * z + m[14];
        float pw = m[3] * x + m[7] * y + m[11] * z + m[15];
        if (pw <= 1e-6f) {
            return false;
        }
        float iw = 1 / pw;
        out[o] = rx + (px * iw * 0.5f + 0.5f) * rw;
        out[o + 1] = ry + (0.5f - py * iw * 0.5f) * rh;
        out[o + 2] = pz * iw;
        return true;
    }
```

The matrix is column-major (chapter 02): row 0 is `m[0], m[4], m[8], m[12]`, so `px` is the first
row dotted with $(x, y, z, 1)$. `pw <= 1e-6f` rejects points behind or on the camera plane (§8.4).
One reciprocal and three multiplies instead of three divisions.

### 22.5 `pointSegmentDistance` and `metersPerPixel`

```java
    public static float pointSegmentDistance(
            float px, float py, float ax, float ay, float bx, float by) {
        float abx = bx - ax;
        float aby = by - ay;
        float len2 = abx * abx + aby * aby;
        float t = len2 == 0 ? 0 : ((px - ax) * abx + (py - ay) * aby) / len2;
        t = Math.max(0, Math.min(1, t));
        float cx = ax + abx * t - px;
        float cy = ay + aby * t - py;
        return (float) Math.sqrt(cx * cx + cy * cy);
    }

    public static float metersPerPixel(float fovY, float depth, float heightPx) {
        return 2 * depth * (float) Math.tan(fovY * 0.5) / heightPx;
    }
```

The segment distance projects the point onto the line through $a$ and $b$ ($t$ is the dot product
over the squared length, the fraction of the way from $a$ to $b$), clamps $t$ to the segment, and
measures from the clamped point: a pointer past the arrow's tip is measured from the tip, not
from the line's extension. `len2 == 0` guards an axis seen exactly end-on. `GizmoMathTest`: the
point $(5, 3)$ is 3 from the segment $(0,0)$–$(10,0)$, $(-3, 4)$ is 5 (from the end $(0, 0)$,
a 3-4-5 triangle), $(10, 0)$ is 0. `metersPerPixel` is §8.5.

### 22.6 Quaternions

```java
    public static void axisAngle(float ax, float ay, float az, float angle, float[] out, int o) {
        float h = angle * 0.5f;
        float s = (float) Math.sin(h);
        out[o] = ax * s;
        out[o + 1] = ay * s;
        out[o + 2] = az * s;
        out[o + 3] = (float) Math.cos(h);
    }

    public static void mul(float[] a, int ao, float[] b, int bo, float[] out, int o) {
        float ax = a[ao], ay = a[ao + 1], az = a[ao + 2], aw = a[ao + 3];
        float bx = b[bo], by = b[bo + 1], bz = b[bo + 2], bw = b[bo + 3];
        float x = aw * bx + ax * bw + ay * bz - az * by;
        float y = aw * by - ax * bz + ay * bw + az * bx;
        float z = aw * bz + ax * by - ay * bx + az * bw;
        float w = aw * bw - ax * bx - ay * by - az * bz;
        out[o] = x;
        out[o + 1] = y;
        out[o + 2] = z;
        out[o + 3] = w;
    }

    public static void rotate(float[] q, int qo, float[] v, int vo, float[] out, int o) {
        float qx = q[qo], qy = q[qo + 1], qz = q[qo + 2], qw = q[qo + 3];
        float vx = v[vo], vy = v[vo + 1], vz = v[vo + 2];
        // t = 2 q × v; v' = v + w t + q × t
        float tx = 2 * (qy * vz - qz * vy);
        float ty = 2 * (qz * vx - qx * vz);
        float tz = 2 * (qx * vy - qy * vx);
        out[o] = vx + qw * tx + (qy * tz - qz * ty);
        out[o + 1] = vy + qw * ty + (qz * tx - qx * tz);
        out[o + 2] = vz + qw * tz + (qx * ty - qy * tx);
    }

    public static void normalize(float[] q, int o) {
        float n = q[o] * q[o] + q[o + 1] * q[o + 1] + q[o + 2] * q[o + 2] + q[o + 3] * q[o + 3];
        if (n > 0) {
            float inv = (float) (1 / Math.sqrt(n));
            q[o] *= inv;
            q[o + 1] *= inv;
            q[o + 2] *= inv;
            q[o + 3] *= inv;
        }
    }
```

All four take arrays with offsets so that a command's packed row (`after, o + 3`) is an input or
output without a copy. `mul` reads every component into locals *before* writing any, so `out` may
be one of the inputs (`writeRotate` passes `before` and `after` rows that never alias, but the
Inspector's `fromEuler` relies on the same property). `rotate` is the formula of §11.2. The math
module's `QuatOps` has the same operations as *batch kernels* over struct-of-arrays columns
(`normalize(qx, qy, qz, qw, start, end)`, chapter 03); the gizmo works on one quaternion packed in
a row, so four small single-value versions were written here rather than adapting the kernels.

### 22.7 Euler angles

```java
    public static void toEuler(float[] q, int o, float[] out, int oo) {
        float x = q[o], y = q[o + 1], z = q[o + 2], w = q[o + 3];
        // Rotation matrix entries of the Y-X-Z decomposition.
        float sinPitch = 2 * (w * x - y * z);
        sinPitch = Math.max(-1, Math.min(1, sinPitch));
        float pitch = (float) Math.asin(sinPitch);
        float yaw;
        float roll;
        if (Math.abs(sinPitch) > 0.9999f) {
            // Gimbal lock: all the turn goes to yaw.
            yaw = (float) Math.atan2(2 * (w * y - x * z), 1 - 2 * (y * y + z * z));
            roll = 0;
        } else {
            yaw = (float) Math.atan2(2 * (w * y + x * z), 1 - 2 * (x * x + y * y));
            roll = (float) Math.atan2(2 * (w * z + x * y), 1 - 2 * (x * x + z * z));
        }
        out[oo] = yaw;
        out[oo + 1] = pitch;
        out[oo + 2] = roll;
    }

    public static void fromEuler(float yaw, float pitch, float roll, float[] out, int o) {
        float cy = (float) Math.cos(yaw * 0.5), sy = (float) Math.sin(yaw * 0.5);
        float cp = (float) Math.cos(pitch * 0.5), sp = (float) Math.sin(pitch * 0.5);
        float cr = (float) Math.cos(roll * 0.5), sr = (float) Math.sin(roll * 0.5);
        // q = qy(yaw) * qx(pitch) * qz(roll)
        float ax = 0, ay = sy, az = 0, aw = cy; // yaw
        float bx = sp, by = 0, bz = 0, bw = cp; // pitch
        float x = aw * bx + ax * bw + ay * bz - az * by;
        float y = aw * by - ax * bz + ay * bw + az * bx;
        float z = aw * bz + ax * by - ay * bx + az * bw;
        float w = aw * bw - ax * bx - ay * by - az * bz;
        float rx = 0, ry = 0, rz = sr, rw = cr; // roll
        out[o] = w * rx + x * rw + y * rz - z * ry;
        out[o + 1] = w * ry - x * rz + y * rw + z * rx;
        out[o + 2] = w * rz + x * ry - y * rx + z * rw;
        out[o + 3] = w * rw - x * rx - y * ry - z * rz;
    }
```

§11.4 derived every line of `toEuler`. `fromEuler` is two inline products: yaw × pitch into
`(x, y, z, w)`, then that × roll into `out`; the zero components are written out so the formula
reads as the general product and the JIT folds the multiplications by zero. The Inspector converts
degrees to radians before calling it (`MathF.toRadians`) and back after `toEuler`.

## 23. Step 10: `Viewport`

The viewport is the longest class of the module (1,189 lines) because it is a state machine over
the mouse: idle, hovering a handle, dragging a handle, pressed on empty space, box-selecting. It
is shown in stages.

### 23.1 Constants and state

```java
public final class Viewport {
    public static final int MODE_TRANSLATE = 0;
    public static final int MODE_ROTATE = 1;
    public static final int MODE_SCALE = 2;

    // Handles: an axis (0..2), a plane named by its normal axis (3..5), or the center.
    static final int NONE = -1;
    static final int AXIS_X = 0;
    static final int AXIS_Y = 1;
    static final int AXIS_Z = 2;
    static final int PLANE_X = 3;
    static final int PLANE_Y = 4;
    static final int PLANE_Z = 5;
    static final int CENTER = 6;

    private static final float PICK_PIXELS = 8; // hit tolerance on screen
    private static final float MARKER_PIXELS = 10; // light / spawn / trigger marker hit radius
    private static final float CLICK_PIXELS = 4; // a press that moves less is a click
    private static final int COLOR_X = 0xFFE04848;
    private static final int COLOR_Y = 0xFF58D858;
    private static final int COLOR_Z = 0xFF4C8CFF;
    private static final int COLOR_HOT = 0xFFFFE040;
    private static final int COLOR_GRID = 0x40FFFFFF;
    private static final int COLOR_GRID_MAJOR = 0x70FFFFFF;
    private static final int COLOR_LIGHT = 0xFFFFC060;
    private static final int COLOR_SPAWN = 0xFF60E0FF;
    private static final int COLOR_TRIGGER = 0xFFE060FF;
    private static final int COLOR_SELECTED = 0xFFFFFFFF;
    private static final int RING_SEGMENTS = 32;

    private final EditorContext ctx;
    private float rx, ry, rw, rh; // the region, window points
    private int mode = MODE_TRANSLATE;
    private int hover = NONE;
    private int active = NONE;
    private boolean orbiting;
    private boolean panning;
    private boolean pressed; // the left button went down on empty space
    private boolean boxSelecting;
    private float pressX, pressY, mouseX, mouseY;
    private TransformCommand drag;
    private boolean dragPushed;
    private final float[] pivot = new float[3]; // gizmo center at drag start (the primary)
    private final float[] center = new float[3]; // gizmo center now
    private final float[] startPoint = new float[3];
    private float startParam;
    private float startDist;
    private float gizmoLen; // world length of a gizmo axis
    private float metersPerPixel; // at the gizmo
    private final float[] ray = new float[6]; // origin, unit direction
    private final float[] tmp = new float[12];
    private final float[] qa = new float[4];
    private final float[] qb = new float[4];
    private final float[] outT = new float[1];
    private final IntList boxHits = new IntList(64);
    private int lastWindowW = 1, lastWindowH = 1;
    private static final float[][] AXES = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
    private static final int[] AXIS_COLORS = {COLOR_X, COLOR_Y, COLOR_Z};

    private boolean regionSet;
```

- Handles are small ints in one range so that `handle - PLANE_X` or `handle - AXIS_X` is the
  axis index; `PLANE_n` is the plane whose *normal* is axis $n$ (the XZ square is `PLANE_Y`).
- The colours are `0xAARRGGBB`: the grid is white at alpha `0x40`, a quarter.
- Every array the per-frame path needs (`ray`, `tmp`, `qa`, `outT`, `boxHits`) is a field: the
  frame allocates nothing. `AXES` is the world basis; exercise 1 replaces it with a rotated copy.
- `hover` is what the pointer is over this frame; `active` is the handle being dragged; `drag`
  is the drag's command and `dragPushed` whether it is already on the stack (§7.2).

### 23.2 `update`: the state machine

```java
    public void update(ViewportInput in, float dt) {
        lastWindowW = in.windowWidth;
        lastWindowH = in.windowHeight;
        if (!regionSet) {
            rx = 0;
            ry = 0;
            rw = lastWindowW;
            rh = lastWindowH;
        }
        IsoCamera cam = ctx.camera();
        cam.update((float) lastWindowW / lastWindowH);
        mouseX = in.mouseX;
        mouseY = in.mouseY;
        boolean inRegion = contains(mouseX, mouseY) && !in.uiWantsPointer;
        boolean shift = in.shift;
        boolean ctrl = in.ctrl;

        cameraInput(in, cam, inRegion, shift);
        computeRay(cam, mouseX, mouseY);
        LevelData level = ctx.level();
        if (level == null) {
            return;
        }
        Selection sel = ctx.selection();
        boolean hasPrimary = sel.primary() >= 0 && LevelObjects.exists(level, sel.primary());
        if (hasPrimary) {
            if (active == NONE) {
                LevelObjects.position(level, sel.primary(), center, 0);
            }
            gizmoScale(cam);
        }

        if (active != NONE) {
            if (in.leftDown) {
                updateDrag(ctrl);
            } else {
                endDrag();
            }
            return;
        }
        if (pressed) {
            if (in.leftDown) {
                if (!boxSelecting
                        && (Math.abs(mouseX - pressX) > CLICK_PIXELS
                                || Math.abs(mouseY - pressY) > CLICK_PIXELS)) {
                    boxSelecting = true;
                }
            } else {
                if (boxSelecting) {
                    finishBox(shift, ctrl);
                } else {
                    click(shift, ctrl);
                }
                pressed = false;
                boxSelecting = false;
            }
            return;
        }
        hover = hasPrimary && inRegion ? hitTest(cam) : NONE;
        if (in.leftPressed && inRegion) {
            if (hover != NONE) {
                beginDrag(hover);
            } else {
                pressed = true;
                pressX = mouseX;
                pressY = mouseY;
            }
        }
    }
```

Read it top to bottom as a frame:

1. The camera's matrices are recomputed every frame (`cam.update`) with the window's aspect,
   *before* `cameraInput` runs. So an orbit or a pan changes the camera's fields this frame and
   the matrices catch up next frame: one frame of latency nobody can see at 60 fps, and the ray
   is computed from the matrices that drew the frame the user is looking at (the rule chapter
   [04](04-cameras-and-projection.md) gives for picking).
2. `inRegion` is the D88 rule: inside the free rectangle and the UI does not want the pointer.
3. The ray through the pointer is computed every frame (§23.3) whether or not it is used.
4. With a primary, the gizmo center follows the object's current position *unless a drag is on*
   (during a drag `writeTranslate` moves it), and `gizmoScale` sizes the handles.
5. Three exits in order: a drag in progress (continue or end), a press on empty space in progress
   (promote to a box after four pixels, or finish as a click or a box on release), else hover and
   maybe begin.

`cancel()` reverts and forgets the drag's entry if it was pushed, and clears every state flag;
`setMode` cancels before switching so a half-made move is not committed as a scale.

### 23.3 The camera and the ray

```java
    private void cameraInput(ViewportInput in, IsoCamera cam, boolean inRegion, boolean shift) {
        EditorSettings s = ctx.settings();
        if (inRegion && in.wheelY != 0) {
            float f = (float) Math.pow(s.zoomStep, -in.wheelY);
            if (cam.orthographic) {
                cam.orthoHeight = MathF.clamp(cam.orthoHeight * f, 1, 400);
            } else {
                cam.distance = MathF.clamp(cam.distance * f, 1, 400);
            }
        }
        if (in.rightPressed && inRegion) {
            if (shift) {
                panning = true;
            } else {
                orbiting = true;
            }
        }
        if (in.middlePressed && inRegion) {
            panning = true;
        }
        if (!in.rightDown && !in.middleDown) {
            orbiting = false;
            panning = false;
        }
        float dx = in.deltaX;
        float dy = in.deltaY;
        if (orbiting) {
            cam.yaw -= dx * s.orbitSpeed;
            cam.pitch =
                    MathF.clamp(
                            cam.pitch + dy * s.orbitSpeed, MathF.toRadians(5), MathF.toRadians(89));
        } else if (panning) {
            // One pixel of mouse moves the target by one pixel's worth of ground at the target's
            // depth, along the camera's right and its forward projected onto the ground.
            float mpp =
                    cam.orthographic
                            ? cam.orthoHeight / lastWindowH
                            : GizmoMath.metersPerPixel(cam.fovY, cam.distance, lastWindowH);
            float sy = (float) Math.sin(cam.yaw);
            float cy = (float) Math.cos(cam.yaw);
            // right = (cos yaw, 0, -sin yaw); ground forward = (-sin yaw, 0, -cos yaw)
            float rxw = cy, rzw = -sy;
            float fxw = -sy, fzw = -cy;
            cam.target.x += (-dx * rxw + dy * fxw) * mpp;
            cam.target.z += (-dx * rzw + dy * fzw) * mpp;
        }
    }
```

- **Zoom** multiplies the distance by `zoomStep` to the power of minus the wheel: one notch
  toward you ($-1$) multiplies by 1.15, one away divides (`cameraOrbitsPansAndZooms` checks
  `distance / zoomStep` after `wheelY = 1`), clamped to $[1, 400]$ m.
- **Orbit**: yaw changes by `orbitSpeed` (0.006 rad, about 0.34°) per pixel, pitch likewise but
  clamped between 5° and 89° so the camera never goes under the ground or flips over the top.
- **Pan** moves the target by one pixel's worth of ground per pixel of mouse (§8.5 at the target's
  depth): the camera's right vector in the ground plane is $(\cos\psi, 0, -\sin\psi)$ and its
  forward projected onto the ground is $(-\sin\psi, 0, -\cos\psi)$ (from the forward vector of
  §8.1 with the $y$ dropped), and the mouse moves the *world* against the pointer, hence `-dx`.
  A drag right pulls the target left so the scene follows the hand.

```java
    private void computeRay(IsoCamera cam, float x, float y) {
        // The scene fills the window (the panels sit on top of it, D88), so normalized device
        // coordinates are relative to the window, not to the region.
        float ndcX = (x / lastWindowW) * 2 - 1;
        float ndcY = 1 - (y / lastWindowH) * 2;
        cam.screenRay(ndcX, ndcY, ray, qa);
        ray[3] = qa[0];
        ray[4] = qa[1];
        ray[5] = qa[2];
    }
```

§8.2 and §8.3 in six lines; `qa` is borrowed as the direction's scratch and copied into
`ray[3..5]` so the ray is one array for the hit tests.

`gizmoScale` computes the primary's depth *along the view direction* (the dot product of the
center's offset from the eye with the normalized eye-to-target vector, not the plain distance:
objects at the screen's edge are farther from the eye than from the view plane, and their gizmo
should still be 90 pixels) and sets `metersPerPixel` and `gizmoLen = gizmoPixels × metersPerPixel`;
the orthographic branch uses `orthoHeight / height`. `frameSelection` averages the selected
positions (or all placements when nothing is selected) and takes a radius: with a selection, the
largest horizontal distance from the centroid *plus 1.5 m* (an object's own extent, roughly); with
none, the bare largest distance of any placement (a level is big enough); at least 1 m either way.
It then sets the distance so that radius fits the field of view with a 10 % margin (the
orthographic camera gets `orthoHeight = 2.4 r` instead):

$$d = \max\left(3,\ \frac{r}{\tan(\mathrm{fov}_y / 2)} \cdot 1.1\right),$$

the right-triangle relation of §8.5 solved for the distance; a radius of 2.5 m gives $2.5 / 0.2679
\cdot 1.1 = 10.26$ m.

### 23.4 Picking and box selection

```java
    public int pick() {
        LevelData level = ctx.level();
        float best = Float.POSITIVE_INFINITY;
        int bestId = -1;
        if (ctx.settings().showMarkers) {
            for (int kind = LevelData.KIND_LIGHT; kind <= LevelData.KIND_TRIGGER; kind++) {
                int n =
                        kind == LevelData.KIND_LIGHT
                                ? level.lightCount()
                                : kind == LevelData.KIND_SPAWN
                                        ? level.spawnCount()
                                        : level.triggerCount();
                for (int i = 0; i < n; i++) {
                    int id =
                            kind == LevelData.KIND_LIGHT
                                    ? level.lightId(i)
                                    : kind == LevelData.KIND_SPAWN
                                            ? level.spawnId(i)
                                            : level.triggerId(i);
                    LevelObjects.position(level, id, tmp, 0);
                    float r = markerRadius(tmp[0], tmp[1], tmp[2]);
                    float t =
                            Intersect.raySphere(
                                    ray[0], ray[1], ray[2], ray[3], ray[4], ray[5], tmp[0], tmp[1],
                                    tmp[2], r);
                    if (t < best) {
                        best = t;
                        bestId = id;
                    }
                }
            }
        }
        World world = ctx.world();
        LevelLoader loader = ctx.loader();
        if (world != null && loader != null && loader.level() == level) {
            int entity = world.pick(ray[0], ray[1], ray[2], ray[3], ray[4], ray[5], best, outT);
            if (entity != Handles.NULL) {
                int id = loader.placementOf(world.groupOf(entity));
                if (id >= 0) {
                    bestId = id;
                }
            }
        }
        return bestId;
    }
```

The markers first, so that `best` is the nearest marker's $t$ when the world is asked, and the
world only wins with something closer (§16.3). `MISS` is positive infinity, so `t < best` is false
for a miss without a special case. `click` then applies the modifiers: Ctrl toggles, Shift adds,
plain selects, and a click on nothing without modifiers clears. `finishBox` projects every
placement and, if markers are shown, every light, spawn and trigger with `boxTest` into `boxHits`,
then clears (unless Shift or Ctrl) and adds or toggles each hit.

### 23.5 `hitTest`

The three tests of §10.6, with the scale mode's center first:

```java
    private int hitTest(IsoCamera cam) {
        float[] vp = cam.viewProjection;
        if (!GizmoMath.project(
                vp, center[0], center[1], center[2], 0, 0, lastWindowW, lastWindowH, tmp, 0)) {
            return NONE;
        }
        float cpx = tmp[0], cpy = tmp[1];
        float tol = PICK_PIXELS;
        int best = NONE;
        float bestD = Float.POSITIVE_INFINITY;
        if (mode == MODE_SCALE) {
            float d = (float) Math.hypot(mouseX - cpx, mouseY - cpy);
            if (d <= tol * 1.5f) {
                return CENTER;
            }
        }
        if (mode == MODE_TRANSLATE) {
            // Plane squares between 0.3 L and 0.6 L along the two axes of each plane; a hit
            // beats the axes because the squares are smaller targets.
            for (int n = 0; n < 3; n++) {
                int u = (n + 1) % 3, v = (n + 2) % 3;
                if (!GizmoMath.rayPlanePoint(
                        ray[0], ray[1], ray[2], ray[3], ray[4], ray[5],
                        center[0], center[1], center[2],
                        AXES[n][0], AXES[n][1], AXES[n][2],
                        tmp, 3)) {
                    continue;
                }
                float pu = dotAxis(tmp, 3, u);
                float pv = dotAxis(tmp, 3, v);
                if (pu >= 0.3f * gizmoLen
                        && pu <= 0.65f * gizmoLen
                        && pv >= 0.3f * gizmoLen
                        && pv <= 0.65f * gizmoLen) {
                    return PLANE_X + n;
                }
            }
        }
        if (mode == MODE_ROTATE) {
            // Rings: distance of the ray's hit on the ring's plane from the center, against L.
            for (int n = 0; n < 3; n++) {
                float facing =
                        Math.abs(ray[3] * AXES[n][0] + ray[4] * AXES[n][1] + ray[5] * AXES[n][2]);
                if (facing < 0.08f) {
                    continue; // edge-on: the ring is a line, leave it to the others
                }
                if (!GizmoMath.rayPlanePoint(
                        ray[0], ray[1], ray[2], ray[3], ray[4], ray[5],
                        center[0], center[1], center[2],
                        AXES[n][0], AXES[n][1], AXES[n][2],
                        tmp, 3)) {
                    continue;
                }
                float dx = tmp[3] - center[0], dy = tmp[4] - center[1], dz = tmp[5] - center[2];
                float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                float d = Math.abs(dist - gizmoLen) / metersPerPixel; // in pixels
                if (d <= tol && d < bestD) {
                    bestD = d;
                    best = AXIS_X + n;
                }
            }
            return best;
        }
        // Axes: screen distance to the projected segment, skipping an axis seen end-on.
        for (int n = 0; n < 3; n++) {
            float along = Math.abs(ray[3] * AXES[n][0] + ray[4] * AXES[n][1] + ray[5] * AXES[n][2]);
            if (along > 0.995f) {
                continue;
            }
            if (!GizmoMath.project(
                    vp,
                    center[0] + AXES[n][0] * gizmoLen,
                    center[1] + AXES[n][1] * gizmoLen,
                    center[2] + AXES[n][2] * gizmoLen,
                    0, 0, lastWindowW, lastWindowH,
                    tmp, 3)) {
                continue;
            }
            float d = GizmoMath.pointSegmentDistance(mouseX, mouseY, cpx, cpy, tmp[3], tmp[4]);
            if (d <= tol && d < bestD) {
                bestD = d;
                best = AXIS_X + n;
            }
        }
        return best;
    }
```

(The `rayPlanePoint` and `project` argument lists are condensed onto fewer lines.) `(n + 1) % 3`
and `(n + 2) % 3` are the other two axes of plane $n$: for the XZ square ($n = 1$), $u = 2$ ($Z$)
and $v = 0$ ($X$). `dotAxis` is $(\mathbf{p} - \mathbf{c})\cdot\mathbf{a}_u$. The rotate branch
`return`s its best ring and never falls through to the axis test, because in rotate mode there are
no axis handles. The axis test's `along > 0.995` is $\cos\alpha$ within about 5.7° of looking down
the axis, the same condition as `closestAxisParam`'s fallback seen from the other side.

### 23.6 `beginDrag` and `updateDrag`

```java
    private void beginDrag(int handle) {
        Selection sel = ctx.selection();
        LevelData level = ctx.level();
        active = handle;
        dragPushed = false;
        String name =
                switch (mode) {
                    case MODE_TRANSLATE ->
                            sel.size() == 1 ? "Move" : "Move " + sel.size() + " objects";
                    case MODE_ROTATE ->
                            sel.size() == 1 ? "Rotate" : "Rotate " + sel.size() + " objects";
                    default -> sel.size() == 1 ? "Scale" : "Scale " + sel.size() + " objects";
                };
        drag = new TransformCommand(level, sel.ids().array(), sel.size(), name);
        System.arraycopy(center, 0, pivot, 0, 3);
        int axis = handle - (handle >= PLANE_X && handle <= PLANE_Z ? PLANE_X : AXIS_X);
        if (handle == CENTER) {
            GizmoMath.project(ctx.camera().viewProjection, pivot[0], pivot[1], pivot[2],
                    0, 0, lastWindowW, lastWindowH, tmp, 0);
            startDist = Math.max(1, (float) Math.hypot(mouseX - tmp[0], mouseY - tmp[1]));
        } else if (mode == MODE_ROTATE || handle >= PLANE_X) {
            if (!GizmoMath.rayPlanePoint(ray[0], ray[1], ray[2], ray[3], ray[4], ray[5],
                    pivot[0], pivot[1], pivot[2], AXES[axis][0], AXES[axis][1], AXES[axis][2],
                    startPoint, 0)) {
                System.arraycopy(pivot, 0, startPoint, 0, 3);
            }
        } else {
            startParam = GizmoMath.closestAxisParam(ray[0], ray[1], ray[2], ray[3], ray[4], ray[5],
                    pivot[0], pivot[1], pivot[2], AXES[axis][0], AXES[axis][1], AXES[axis][2], 0);
            if (mode == MODE_SCALE && Math.abs(startParam) < 1e-3f) {
                startParam = gizmoLen;
            }
        }
    }
```

(Argument lists condensed.) The press is where the drag's command is *created* (the string for
its name and the command itself: the allocation a drag is allowed) and the three reference values
are recorded: `startDist` for the center cube, `startPoint` for planes and rings, `startParam` for
axes. A scale drag that starts exactly at the center would divide by zero in `t / startParam`;
`startParam` becomes `gizmoLen` then. The pivot is the center *now*, frozen for the drag.

```java
    private void updateDrag(boolean ctrl) {
        EditorSettings s = ctx.settings();
        boolean snap = s.snap != ctrl; // Ctrl inverts
        int handle = active;
        int axis = handle - (handle >= PLANE_X && handle <= PLANE_Z ? PLANE_X : AXIS_X);
        float[] a = handle == CENTER ? AXES[0] : AXES[axis];
        switch (mode) {
            case MODE_TRANSLATE -> {
                if (handle >= PLANE_X) {
                    if (!GizmoMath.rayPlanePoint(
                            ray[0], ray[1], ray[2], ray[3], ray[4], ray[5], pivot[0], pivot[1],
                            pivot[2], a[0], a[1], a[2], tmp, 0)) {
                        return;
                    }
                    float dx = tmp[0] - startPoint[0];
                    float dy = tmp[1] - startPoint[1];
                    float dz = tmp[2] - startPoint[2];
                    if (snap) {
                        dx = GizmoMath.snap(dx, s.translateStep);
                        dy = GizmoMath.snap(dy, s.translateStep);
                        dz = GizmoMath.snap(dz, s.translateStep);
                    }
                    writeTranslate(dx, dy, dz);
                } else {
                    float t =
                            GizmoMath.closestAxisParam(
                                    ray[0], ray[1], ray[2], ray[3], ray[4], ray[5],
                                    pivot[0], pivot[1], pivot[2],
                                    a[0], a[1], a[2],
                                    startParam);
                    float d = t - startParam;
                    if (snap) {
                        d = GizmoMath.snap(d, s.translateStep);
                    }
                    writeTranslate(a[0] * d, a[1] * d, a[2] * d);
                }
            }
            case MODE_ROTATE -> {
                if (!GizmoMath.rayPlanePoint(
                        ray[0], ray[1], ray[2], ray[3], ray[4], ray[5], pivot[0], pivot[1],
                        pivot[2], a[0], a[1], a[2], tmp, 0)) {
                    return;
                }
                float angle =
                        GizmoMath.signedAngle(
                                startPoint[0] - pivot[0],
                                startPoint[1] - pivot[1],
                                startPoint[2] - pivot[2],
                                tmp[0] - pivot[0],
                                tmp[1] - pivot[1],
                                tmp[2] - pivot[2],
                                a[0], a[1], a[2]);
                if (snap) {
                    angle = GizmoMath.snap(angle, MathF.toRadians(s.rotateStepDegrees));
                }
                writeRotate(a, angle);
            }
            default -> {
                float factor;
                if (handle == CENTER) {
                    GizmoMath.project(ctx.camera().viewProjection, pivot[0], pivot[1], pivot[2],
                            0, 0, lastWindowW, lastWindowH, tmp, 0);
                    factor = (float) Math.hypot(mouseX - tmp[0], mouseY - tmp[1]) / startDist;
                } else {
                    float t =
                            GizmoMath.closestAxisParam(
                                    ray[0], ray[1], ray[2], ray[3], ray[4], ray[5],
                                    pivot[0], pivot[1], pivot[2],
                                    a[0], a[1], a[2],
                                    startParam);
                    factor = t / startParam;
                }
                if (snap) {
                    factor = GizmoMath.snap(factor, s.scaleStep);
                }
                factor = Math.max(0.01f, factor);
                if (handle == CENTER) {
                    writeScale(factor, factor, factor);
                } else {
                    writeScale(
                            axis == 0 ? factor : 1, axis == 1 ? factor : 1, axis == 2 ? factor : 1);
                }
            }
        }
        if (drag.changed()) {
            if (!dragPushed) {
                ctx.undo().push(drag);
                dragPushed = true;
            } else {
                drag.apply(ctx);
            }
        }
    }
```

(Argument lists condensed.) Every branch computes one number from §10 and hands it to a `write*`
that fills the command's `after` rows; then the last six lines are the "mutate and re-apply" of
§7.2: the first frame that changes something pushes the command (which applies it), later frames
apply it again. A plane whose ray misses this frame (the camera turned edge-on during the drag)
simply skips the frame. The axis parameter's fallback is `startParam`, so a ray that becomes
parallel to the axis mid-drag holds the object still. The scale factor is clamped at 0.01 so an
object cannot be scaled to zero (whose inverse transform would be singular, and `pick` would skip
it forever).

### 23.7 The three writers

```java
    private void writeTranslate(float dx, float dy, float dz) {
        float[] before = drag.before();
        float[] after = drag.after();
        for (int i = 0; i < drag.count(); i++) {
            int o = drag.offset(i);
            after[o] = before[o] + dx;
            after[o + 1] = before[o + 1] + dy;
            after[o + 2] = before[o + 2] + dz;
        }
        center[0] = pivot[0] + dx;
        center[1] = pivot[1] + dy;
        center[2] = pivot[2] + dz;
    }

    private void writeRotate(float[] axis, float angle) {
        GizmoMath.axisAngle(axis[0], axis[1], axis[2], angle, qa, 0);
        float[] before = drag.before();
        float[] after = drag.after();
        LevelData level = ctx.level();
        for (int i = 0; i < drag.count(); i++) {
            int o = drag.offset(i);
            int kind = level.kindOf(drag.id(i));
            // Position: rotate the offset from the pivot.
            tmp[0] = before[o] - pivot[0];
            tmp[1] = before[o + 1] - pivot[1];
            tmp[2] = before[o + 2] - pivot[2];
            GizmoMath.rotate(qa, 0, tmp, 0, tmp, 3);
            after[o] = pivot[0] + tmp[3];
            after[o + 1] = pivot[1] + tmp[4];
            after[o + 2] = pivot[2] + tmp[5];
            switch (kind) {
                case LevelData.KIND_PLACEMENT -> {
                    GizmoMath.mul(qa, 0, before, o + 3, after, o + 3); // world turn, then the old
                    GizmoMath.normalize(after, o + 3);
                }
                case LevelData.KIND_SPAWN -> after[o + 3] = before[o + 3] + axis[1] * angle;
                case LevelData.KIND_TRIGGER -> after[o + 6] = before[o + 6] + axis[1] * angle;
                case LevelData.KIND_LIGHT -> GizmoMath.rotate(qa, 0, before, o + 3, after, o + 3);
                default -> {}
            }
        }
    }

    private void writeScale(float fx, float fy, float fz) {
        float[] before = drag.before();
        float[] after = drag.after();
        LevelData level = ctx.level();
        for (int i = 0; i < drag.count(); i++) {
            int o = drag.offset(i);
            int kind = level.kindOf(drag.id(i));
            after[o] = pivot[0] + (before[o] - pivot[0]) * fx;
            after[o + 1] = pivot[1] + (before[o + 1] - pivot[1]) * fy;
            after[o + 2] = pivot[2] + (before[o + 2] - pivot[2]) * fz;
            int so = LevelObjects.scaleOffset(kind);
            if (so >= 0) {
                after[o + so] = before[o + so] * fx;
                after[o + so + 1] = before[o + so + 1] * fy;
                after[o + so + 2] = before[o + so + 2] * fz;
            }
        }
    }
```

All three write *from `before`*, never from the current `after`: the drag's total is recomputed
from the press every frame, so a mouse that wanders and comes back leaves the objects exactly
where they started, and snapping rounds the total, not an accumulation of rounded steps. §11.1
to §11.3 explain the rotation; `rotate(qa, 0, tmp, 0, tmp, 3)` rotates the offset in `tmp[0..2]`
into `tmp[3..5]`, and `axis[1]` is the trick that turns a spawn's yaw only for the Y ring.

### 23.8 Drawing

`extract` adds the grid (if shown), the markers (if shown) and, when there is a primary, calls
`packet.beginDebugOverlay()` and draws the gizmo, in that order, so the overlay mark separates
the two ranges of §12.1. The grid is 49 lines each way around the camera target, snapped to the
step, with the step doubled until 48 steps cover about twice the distance to the target (at 16 m
the half-metre grid becomes 1 m, at 30 m 2 m: far away, a fine grid is a blur), the axes through
the origin brighter. Markers: a light is a cross of 0.25 m plus, for a spot, its direction;
a spawn is a 0.4 m circle on the ground, a 1.8 m pole and a 0.7 m facing line along
$(\sin\text{yaw}, 0, \cos\text{yaw})$; a trigger is its oriented box, twelve edges from the corner
bits (`EDGE_A`, `EDGE_B` list the corner pairs that differ in one bit, each corner's coordinates
read from its bits with `(k & 1)`, `(k & 2)`, `(k & 4)`, turned by the yaw in the XZ plane). The
gizmo draws thick axes (§12.3) with arrow heads, squares or rings (32 segments, in the plane
spanned by two unit vectors built from the normal with a cross product and the axis least
aligned with it) or cubes, the hovered or active handle in `COLOR_HOT`, and during a rotation the
lever from the pivot to the press point.

## 24. Step 11: `Dock` and `Panel`

### 24.1 `Panel`

```java
public abstract class Panel {
    private final String name;

    protected Panel(String name) {
        this.name = name;
    }

    public final String name() {
        return name;
    }

    public abstract void draw(EditorContext ctx, Widgets w);

    public void levelChanged(EditorContext ctx) {}
}
```

The name is the tab's label and the layout string's key, so it is `final`. `levelChanged` is the
one hook: when a level opens or closes, panels drop their caches (the Inspector forgets which
object it synced, the Assets panel re-lists the VFS).

### 24.2 `Dock`: fields and registration

```java
public final class Dock {
    public static final int LEFT = 0;
    public static final int RIGHT = 1;
    public static final int BOTTOM = 2;
    public static final int HIDDEN = -1;

    private static final long ZONE_ID = 0xD0C1L;
    private static final long TABS_ID = 0x7AB5L;
    private static final long SPLIT_ID = 0x5B11L;
    private static final long BODY_ID = 0xB0D1L;
    private static final float MIN_SIZE = 120;

    private final List<Panel> panels = new ArrayList<>();
    private final List<Integer> zoneOf = new ArrayList<>();
    private final float[] size = {300, 340, 220};
    private final int[] selected = new int[3];
    private final CharSequence[][] labels = new CharSequence[3][];
    private final Panel[][] zonePanels = new Panel[3][];
    private final int[] zoneCount = new int[3];
    private float regionX, regionY, regionW, regionH;
    private int layoutVersion;
    private int builtVersion = -1;

    public void add(Panel panel, int zone) {
        panels.add(panel);
        zoneOf.add(zone);
        layoutVersion++;
    }
```

The widget ids are hex spellings of their
names (`0xD0C1` for the dock, `0x7AB5` for tabs, `0x5B11` for a splitter, `0xB0D1` for a body): any
distinct constants would do; these are recognizable in a debugger. `zoneOf` is a `List<Integer>`,
boxed, touched only when a panel moves: a cold path. `size` holds the three zone sizes (width for
the sides, height for the bottom) and `selected` the visible tab per zone. The per-zone arrays
`zonePanels` and `labels` are rebuilt from `panels` and `zoneOf` by `rebuild()` whenever
`layoutVersion` moved past `builtVersion`, so the per-frame `declare` never allocates: moving a
panel allocates once, drawing never.

`setZone(i, zone)` moves a panel and makes it its zone's visible tab; `toggle(i)` hides a shown
panel or shows a hidden one *in its last zone* (`lastZoneOf`, recorded by `rebuild`; a panel that
was never shown goes to `LEFT`, since the array's default is 0, although the Javadoc says the right
zone); `setSize` clamps at `MIN_SIZE`.

### 24.3 `declare`

```java
    public void declare(EditorContext ctx, Widgets w, float top, float width, float height) {
        if (builtVersion != layoutVersion) {
            rebuild();
        }
        Ui ui = w.ui;
        float split = w.theme.splitterWidth;
        boolean left = zoneCount[LEFT] > 0;
        boolean right = zoneCount[RIGHT] > 0;
        boolean bottom = zoneCount[BOTTOM] > 0;
        float bottomH = bottom ? size[BOTTOM] + split : 0;
        float middleH = Math.max(0, height - top - bottomH);
        // The middle row: left zone | splitter | free space | splitter | right zone.
        ui.box().row().widthGrow().height(middleH).noHit().open();
        if (left) {
            zone(ctx, w, LEFT, size[LEFT], middleH);
            ui.pushId(LEFT);
            setSize(LEFT, size[LEFT] + w.splitter(SPLIT_ID, true));
            ui.popId();
        }
        ui.box().grow().noHit(); // the viewport shows through here
        if (right) {
            ui.pushId(RIGHT);
            setSize(RIGHT, size[RIGHT] - w.splitter(SPLIT_ID, true));
            ui.popId();
            zone(ctx, w, RIGHT, size[RIGHT], middleH);
        }
        ui.close();
        if (bottom) {
            ui.pushId(BOTTOM);
            setSize(BOTTOM, size[BOTTOM] - w.splitter(SPLIT_ID, false));
            ui.popId();
            zone(ctx, w, BOTTOM, width, size[BOTTOM]);
        }
        float lw = left ? size[LEFT] + split : 0;
        float rw = right ? size[RIGHT] + split : 0;
        regionX = lw;
        regionY = top;
        regionW = Math.max(1, width - lw - rw);
        regionH = Math.max(1, middleH);
    }
```

- The structure is the figure of §13.1: a `row` box of the middle height holding the left zone,
  a splitter, a growing empty box, a splitter and the right zone; then a splitter and the bottom
  zone. The row and the middle box are `noHit()`, so the viewport underneath gets the pointer.
- A **splitter** returns how far it was dragged this frame; the left zone grows with a drag to the
  right (`+`), the right zone *shrinks* with a drag to the right (`−`), the bottom zone shrinks
  with a drag down. Each splitter is declared inside `pushId(zone)` because all three use
  `SPLIT_ID`.
- The region is computed from the zone sizes *after* the splitters have moved them, so a resize
  reaches the viewport in the same frame. `PanelsTest.dockLeavesTheViewportRegionAndPersistsItsLayout`
  checks `regionX = left + splitter`, `regionY = top`, `regionW = width − left − right − 2 × splitter`
  and `regionH = height − top − bottom − splitter` with the theme's numbers, and that a layout
  with no bottom zone gives the full height.

```java
    private void zone(EditorContext ctx, Widgets w, int z, float zw, float zh) {
        Ui ui = w.ui;
        ui.pushId(z);
        ui.box(ZONE_ID)
                .column()
                .size(zw, zh)
                .gap(0)
                .background(w.theme.panel)
                .border(w.theme.panelBorder, 1)
                .clip()
                .hit()
                .open();
        selected[z] = w.tabs(TABS_ID, selected[z], labels[z]);
        ui.box().widthGrow().height(1).background(w.theme.separator).noHit();
        w.scrollColumn(BODY_ID).widthGrow().heightGrow().padding(w.theme.padding * 0.75f).open();
        if (zoneCount[z] > 0) {
            Panel p = zonePanels[z][selected[z]];
            ui.pushId(panels.indexOf(p));
            p.draw(ctx, w);
            ui.popId();
        }
        ui.close();
        ui.close();
        ui.popId();
    }
```

A zone is a column with a background and a border that *does* take hits (`hit()`: clicks on a
panel never reach the viewport), a tabs row, a one-pixel separator, and a scrolling column the
visible panel draws into. The two `pushId`s are the id scopes of §13.3: the zone, then the panel's
index in the registration order, which is why `PanelsTest` folds `(Dock.RIGHT, 2, ...)` to reach
the Inspector, panel 2.

### 24.4 The layout string

`layout()` builds `left=300:Outliner,Assets|right=340:Inspector,Settings|bottom=220:Console` with a
`StringBuilder` (cold: on save), and `setLayout` parses it back: every panel is hidden first, then
each `zone=size:names` part sets the size (a bad number keeps the current one) and places the
named panels; unknown names are ignored, so a layout saved with a panel a later build removed
still loads. The host stores the string under `editor.layout` (§28.4). What the dock does *not* do
is let you drag a tab from one zone to another: D95 deferred it, and moving a panel is the View
menu's toggle (hidden, then shown in its last zone), `setZone`, or the console's
`panel <name> <zone>`. A drag would need the tabs row to become a drag source and the zones drop
targets, with a preview of the new layout while the button is held; exercise material for a
later milestone.

## 25. Step 12: the panels

Seven panels, each a `draw` over the context. The Outliner shows the shape; the Inspector the
hard parts; the rest are summarized with what is particular to each.

### 25.1 `OutlinerPanel`

```java
public final class OutlinerPanel extends Panel {
    private static final long FILTER = 1;
    private static final long KIND = 2;
    private static final long ROW = 3;
    private static final float INDENT = 12;

    private final TextBuffer filter = new TextBuffer(64);
    private final StringBuilder[] headings = {
        new StringBuilder(32), new StringBuilder(32), new StringBuilder(32), new StringBuilder(32)
    };
    private static final String[] KIND_NAMES = {"Placements", "Lights", "Spawns", "Triggers"};

    @Override
    public void draw(EditorContext ctx, Widgets w) {
        Ui ui = w.ui;
        LevelData level = ctx.level();
        w.textField(FILTER, filter, "Filter by name or layer");
        if (level == null) {
            w.labelDim("No level open");
            return;
        }
        for (int kind = 0; kind < 4; kind++) {
            int n =
                    switch (kind) {
                        case LevelData.KIND_PLACEMENT -> level.placementCount();
                        case LevelData.KIND_LIGHT -> level.lightCount();
                        case LevelData.KIND_SPAWN -> level.spawnCount();
                        default -> level.triggerCount();
                    };
            StringBuilder h = headings[kind];
            h.setLength(0);
            h.append(KIND_NAMES[kind]).append(" (").append(n).append(')');
            ui.pushId(kind);
            if (w.treeNode(KIND, h, kind == LevelData.KIND_PLACEMENT)) {
                for (int i = 0; i < n; i++) {
                    row(ctx, w, level, kind, i);
                }
                w.treeEnd();
            }
            ui.popId();
        }
    }
```

The headings are `StringBuilder`s reused every frame (`setLength(0)` then append: a count written
without a `String`), because `"Placements (" + n + ")"` would allocate per frame. The four kinds are
tree nodes, the placements open by default, each scoped by `pushId(kind)` so that `KIND` and the
rows' ids differ per kind. A row:

```java
    private void row(EditorContext ctx, Widgets w, LevelData level, int kind, int i) {
        Ui ui = w.ui;
        int id;
        String name;
        String detail;
        switch (kind) {
            case LevelData.KIND_PLACEMENT -> {
                id = level.placementId(i);
                name = level.placementName(i);
                detail = level.placementLayer(i);
                if (name.isEmpty()) {
                    name = level.prefabNode(level.placementPrefab(i));
                }
            }
            ...                                      // lights ("spot"/"point"), spawns, triggers
        }                                                // (the three other kinds elided)
        if (filter.length() > 0
                && !Text.containsIgnoreCase(name, filter)
                && !Text.containsIgnoreCase(detail, filter)) {
            return;
        }
        Selection sel = ctx.selection();
        boolean selected = sel.contains(id);
        ui.pushId(id);
        w.beginSelectableRow(ROW, selected, INDENT);
        ui.text(name.isEmpty() ? "(unnamed)" : name).color(selected ? 0xFFFFFFFF : w.theme.text);
        ui.text(detail)
                .fontSize(w.theme.fontSizeSmall)
                .color(selected ? 0xFFE0E0E0 : w.theme.textDim);
        w.spacer();
        ui.text()
                .append('#')
                .append(id)
                .fontSize(w.theme.fontSizeSmall)
                .color(selected ? 0xFFE0E0E0 : w.theme.textDim);
        boolean clicked = w.endSelectableRow(ROW);
        boolean doubleClicked = ui.doubleClicked(ROW);
        ui.popId();
        if (clicked) {
            int mods = ui.input().modifiers;
            if ((mods & UiKey.MOD_CTRL) != 0) {
                sel.toggle(id);
            } else if ((mods & UiKey.MOD_SHIFT) != 0) {
                sel.add(id);
            } else {
                sel.select(id);
            }
        }
        if (doubleClicked && ctx.viewport() != null) {
            ctx.viewport().frameSelection();
        }
    }
```

A row is a selectable row (chapter 13) scoped by the object's *id* (`pushId(id)`, so the row keeps
its UI state when rows shift), holding the name (or the prefab's node name for an unnamed
placement), the detail (the layer, the light type, the kind) and `#id` appended as a number into
the text's buffer. `sel.contains(id)` is the `IntSet` lookup of §21.2, 372 times a frame. The
modifiers mirror the viewport's click. Every row declares five elements (the row, three texts, a
spacer), so the crypt's 372 placements are most of the 2,012 elements an editor frame declares
with the Outliner, the Inspector and the Console open (measured in §32).

### 25.2 `InspectorPanel`: sync and the property rows

The fields, condensed:

```java
public final class InspectorPanel extends Panel {
    private static final long NAME = 1;
    private static final long LAYER = 2;
    ...                                              // KIND, PAYLOAD, PREFAB, FLAG, COLLISION,
    private static final long POSITION = 8;          // ROTATION, SCALE, TYPE, COLOR, INTENSITY,
    ...                                              // RANGE, DIRECTION, CONE, YAW, SIZE,
    private static final long COMPONENT_FIELD = 20;  // COMPONENT, COMPONENT_REMOVE, COMPONENT_ADD

    private final TextBuffer name = new TextBuffer(64);
    private final TextBuffer layer = new TextBuffer(32);
    private final TextBuffer kind = new TextBuffer(32);
    private final TextBuffer payload = new TextBuffer(128);
    private int syncedId = -1;
    private int syncedVersion = -1;
    private final float[] vec = new float[4];
    private final float[] vec2 = new float[4];
    private final float[] quat = new float[4];
    private CharSequence[] prefabLabels = new CharSequence[0];
    private int prefabLabelsFor = -1;
    private LevelData prefabLabelsLevel;
```

Four text buffers for the four string fields, three float scratch arrays, and the prefab
dropdown's labels, rebuilt only when the prefab count or the level changes. The `draw`:

```java
    @Override
    public void draw(EditorContext ctx, Widgets w) {
        Ui ui = w.ui;
        LevelData level = ctx.level();
        Selection sel = ctx.selection();
        if (level == null) {
            w.labelDim("No level open");
            return;
        }
        int id = sel.primary();
        if (id < 0 || !LevelObjects.exists(level, id)) {
            w.labelDim("Nothing selected");
            w.small("Click an object in the view or the Outliner.");
            return;
        }
        int kindOf = level.kindOf(id);
        int index = LevelObjects.index(level, id);
        sync(ctx, w, level, id, kindOf, index);

        // Header.
        w.row().open();
        w.heading(LevelObjects.kindName(kindOf));
        ui.text().append('#').append(id).color(w.theme.textDim);
        if (sel.size() > 1) {
            ui.text().append("(+").append(sel.size() - 1).append(" more)").color(w.theme.textDim);
        }
        ui.close();

        // Name.
        w.beginProperty("Name");
        if (w.textField(NAME, name)) {
            push(
                    ctx,
                    new SetStringCommand(
                            level, ids(sel), sel.size(), ObjectField.NAME, name.toString()));
        }
        w.endProperty();

        switch (kindOf) {
            case LevelData.KIND_PLACEMENT -> placement(ctx, w, level, sel, index);
            case LevelData.KIND_LIGHT -> light(ctx, w, level, sel, index);
            case LevelData.KIND_SPAWN -> spawn(ctx, w, level, sel, index);
            default -> trigger(ctx, w, level, sel, index);
        }
        components(ctx, w, level, sel, id);
    }

    private void push(EditorContext ctx, dev.jeddy.editor.command.Command c) {
        ctx.undo().push(c, true);
        syncedVersion = ctx.level().version();
    }

    private void sync(
            EditorContext ctx, Widgets w, LevelData level, int id, int kindOf, int index) {
        boolean newObject = id != syncedId;
        if (!newObject && level.version() == syncedVersion) {
            return;
        }
        Ui ui = w.ui;
        if (newObject || !ui.focused(NAME)) {
            setIfDifferent(name, LevelObjects.name(level, id));
        }
        if (kindOf == LevelData.KIND_PLACEMENT && (newObject || !ui.focused(LAYER))) {
            setIfDifferent(layer, level.placementLayer(index));
        }
        if (kindOf == LevelData.KIND_SPAWN && (newObject || !ui.focused(KIND))) {
            setIfDifferent(kind, level.spawnKind(index));
        }
        if (kindOf == LevelData.KIND_TRIGGER) {
            if (newObject || !ui.focused(KIND)) {
                setIfDifferent(kind, level.triggerKind(index));
            }
            if (newObject || !ui.focused(PAYLOAD)) {
                setIfDifferent(payload, level.triggerPayload(index));
            }
        }
        syncedId = id;
        syncedVersion = level.version();
    }
```

- Every push goes through `push`, which merges (`true`) and records the level's new version as
  "seen", so the panel's own edit does not make `sync` re-read the buffer it just typed into
  (§13.4).
- `sync` is the rule of §13.4 in code: a new primary re-reads everything; a changed version
  re-reads the buffers that are not focused. `setIfDifferent` compares first (`Text.equals`) so an
  unchanged string does not reset the buffer's caret and selection.
- The text field returns `true` when its buffer changed this frame; the command gets
  `name.toString()`, one `String` per keystroke, a user action.

The placement section shows the three ways a value becomes a command:

```java
        w.beginProperty("Flags");
        ui.box().column().widthGrow().gap(0).open();
        for (int f = 0; f < PLACEMENT_FLAG_NAMES.length; f++) {
            ui.pushId(f);
            boolean on = (flags & PLACEMENT_FLAG_BITS[f]) != 0;
            boolean now = w.checkbox(FLAG, PLACEMENT_FLAG_NAMES[f], on);
            if (now != on) {
                push(
                        ctx,
                        SetIntCommand.flag(
                                level, ids(sel), sel.size(), PLACEMENT_FLAG_BITS[f], now));
            }
            ui.popId();
        }
        ui.close();
        w.endProperty();
        ...
        w.beginProperty("Rotation");
        for (int k = 0; k < 4; k++) {
            quat[k] = level.placementFloat(i, 3 + k);
        }
        GizmoMath.toEuler(quat, 0, vec, 0);
        for (int k = 0; k < 3; k++) {
            vec[k] = MathF.toDegrees(vec[k]);
        }
        if (w.vector3Field(ROTATION, vec, 0, 0.5f, 1) != 0) {
            GizmoMath.fromEuler(
                    MathF.toRadians(vec[0]),
                    MathF.toRadians(vec[1]),
                    MathF.toRadians(vec[2]),
                    quat,
                    0);
            TransformCommand c = new TransformCommand(level, ids(sel), sel.size(), "Rotate");
            for (int o = 0; o < c.count(); o++) {
                if (level.kindOf(c.id(o)) == LevelData.KIND_PLACEMENT) {
                    for (int k = 0; k < 4; k++) {
                        c.setAfter(o, 3 + k, quat[k]);
                    }
                }
            }
            push(ctx, c);
        }
        w.endProperty();
```

- **Checkboxes** in a loop each get `pushId(f)` (the id-scope rule), and a change pushes the
  per-object flag command, so three selected walls each keep their other flags.
- **Rotation** converts the primary's quaternion to degrees for display, and a change converts
  the three fields back to one quaternion and writes it into a `TransformCommand`'s *after* for
  every selected placement (the whole selection takes the primary's new orientation), pushed with
  merge so a drag on the yaw field is one entry. `vector3Field` returns a bit mask of changed
  components; `!= 0` is "any".
- **Position and scale** go through `floats(...)`, which reads $n$ floats of the primary into
  `vec`, declares a `vectorField`, and pushes one `SetFloatCommand` per changed component on the
  whole selection (the bit mask again: `(changed & 1 << k) != 0`); `degrees(...)` is the same for a
  single angle field (a spawn's facing, a spot's cone), converting degrees to radians.

Lights get a type dropdown, colour (`colorField`, a swatch plus three channels up to 10 for HDR
values), intensity in candela, range in metres, the spot's direction and cone angles, and two flag
checkboxes; spawns a kind and a facing; triggers a kind, a payload, size and facing, and two flags.

### 25.3 The Components section

`components(...)` draws one tree node per component row of the primary object, with a field per
column from the game's schema (`ComponentSchema.Field`: int, long, float, bool, enum, vec2, vec3,
entity, asset; chapter [22](22-components.md)), a *Remove* button, and an *Add component* dropdown
listing the components the object does not have yet. Each field reads the value or the default
(`level.componentValue(row, column) < 0` means "at the default") and pushes a
`SetComponentValueCommand` on change; an angle field shows degrees and stores radians; a
`vec3` is three columns `pos.x`, `pos.y`, `pos.z` and pushes one command per changed one; an
`entity` field is a dropdown over every object of the level (labels rebuilt when the level's
version changes); an `asset` field is a text field with the same focus rule as the name. One
subtlety in the loop: removing a row *inside* the loop over rows would shift the rows under it,
so after pushing the remove command the method pops its id scope and returns, and the rows are
drawn afresh next frame (`PanelsTest.inspectorShowsComponentRowsFromTheSchema` clicks the button
and checks).

### 25.4 `EntitiesPanel`, `AssetsPanel`, `ConsolePanel`, `SettingsPanel`, `HistoryPanel`

- **Entities** lists the world's transform store, up to 400 rows (`#index:generation`, the parent,
  `static`), filtered by index, and for the picked entity its world position, its *local* position
  as a vector field that writes `TransformStore.setPosition` **directly, not through a command**
  (this is the game's live state, not the level's: exercise 6 asks what an undo should do), the
  placement it belongs to with a *Select* button, and the animation clip and phase if it has one.
  Picking an entity outlines its group with `HIGHLIGHT_INTERACTABLE`.
- **Assets** lists the level's prefabs first, each with its use count and a *Place* button, then
  every path of the `Vfs` (listed once per level and on *Refresh*: `vfs.paths(list)` allocates), in
  a 220-pixel scrolling box with a filter and at most 300 rows; choosing a `.jscene` loads it
  through the scene asset type and lists its root nodes, each with *Place*. `place` pushes an
  `AddObjectsCommand.placement` at the camera target on layer `prop`, static and casting shadows,
  and selects it.
- **Console** draws the log lines (errors red, warnings amber) in a scrolling box, scrolls to the
  end when the log's version changed (`ui.setScroll(LINES, 1, 1e9f)`: the layout clamps it), and
  a command line. `run` splits the line and switches: `help`, `clear`, `select <id>...`,
  `deselect`, `delete`, `goto <id>` (select and frame), `save [path]`, `dump`, `snap <step>`,
  `stats`, `panel <name> [left|right|bottom|hidden]`, `rules <kind> [id]`; an exception becomes an
  error line. The demo's `--editor-command` and the tool's `--command` feed it at start.
- **Settings** has three tree nodes: the editor's own settings, written straight into
  `EditorSettings` (not undoable); the level's settings, where every `numberField` writes into a
  scratch `Settings` and one `SetSettingsCommand` is pushed (merged) if any value differs from the
  level's; and the collision rules and size classes as editable rows (text buffers from small
  pools, 16 and 8, synced like the Inspector's; a change is `TableCommand.set`, the `x` button
  `remove`, *Add rule* and *Add size class* an `add` at the end).
- **History** is the stack as rows: `(opened)` at the top, then every entry, the applied ones in
  the normal colour and the undone ones dimmed, the current one highlighted; a click calls
  `undo.seek(i + 1)`.

## 26. Step 13: the Rules panel

The Rules panel (M15-06, D97) edits the game's rules *content*, the JSON files of `assets/rules`
(conditions, feats, spells, monsters, ...), which chapter [15](15-rules.md) describes: the schema
of kinds and fields, the cooker's importer, the binary the game reads. This chapter builds the
editor's side: a model of the JSON trees addressed by paths, two commands over it, and a panel
whose grid is generated from the schema. It reuses every mechanism above and adds two of its own
for allocation-free drawing over tree-shaped data.

### 26.1 `RulesPath`

```java
public final class RulesPath {
    private final Object[] segments;

    private RulesPath(Object[] segments) {
        this.segments = segments;
    }

    public static RulesPath of(Object... segments) {
        for (Object s : segments) {
            if (!(s instanceof String) && !(s instanceof Integer)) {
                throw new IllegalArgumentException("segment " + s);
            }
        }
        return new RulesPath(segments.clone());
    }

    public RulesPath parent() {
        return new RulesPath(Arrays.copyOf(segments, segments.length - 1));
    }

    public RulesPath child(Object segment) {
        Object[] s = Arrays.copyOf(segments, segments.length + 1);
        s[segments.length] = segment;
        return of(s);
    }
```

A path is where a value lives: `feats[3].effects[1].value` is the segments `"feats", 3,
"effects", 1, "value"`, strings for keys and integers for list indices. Commands carry paths
rather than object references, so that undo and redo find the same place after rows were removed
and reinserted (the same reason the level uses ids). It is immutable (`clone` on the way in,
`equals`/`hashCode` over the array), and its `toString` prints `feats[3].effects[1].value`, which
the History panel shows.

### 26.2 `RulesSource`

The model: one `List<Object>` of definitions per top-level kind, parsed with the core JSON
reader into `Map`, `List`, `String`, `Double` and `Boolean` (every number is a `Double`: JSON has
no integers). The editor never touches the cooked `.jrules`: it edits these trees and writes them
back; the cooker's watcher re-cooks them and the game hot-reloads. The path operations:

```java
    public Object get(RulesPath path) {
        Object node = tables;
        for (int i = 0; i < path.length(); i++) {
            node = step(node, path.segment(i));
            if (node == null) {
                return null;
            }
        }
        return node;
    }

    // ... list(path): the list at a path, created empty in its parent object when absent

    @SuppressWarnings("unchecked")
    public Object set(RulesPath path, Object value) {
        Object parent = get(path.parent());
        Object before;
        if (parent instanceof Map<?, ?> m && path.last() instanceof String key) {
            Map<String, Object> map = (Map<String, Object>) m;
            before = value == null ? map.remove(key) : map.put(key, value);
        } else if (parent instanceof List<?> l && path.last() instanceof Integer i) {
            before = ((List<Object>) l).set(i, value);
        } else {
            throw new IllegalArgumentException("nothing to set at " + path);
        }
        version++;
        return before;
    }

    public void insert(RulesPath listPath, int index, Object element) {
        list(listPath).add(index, element);
        version++;
    }

    public Object remove(RulesPath listPath, int index) {
        Object e = list(listPath).remove(index);
        version++;
        return e;
    }

    public void move(RulesPath listPath, int from, int to) {
        List<Object> l = list(listPath);
        Object e = l.remove(from);
        l.add(to, e);
        version++;
    }
```

`get` walks the segments (`step`, further down the file: a map by key, a list by a bounds-checked
index, `null` otherwise). `set` with `null` *removes* the key, which means "the importer's default applies"
(an absent field is not an error in the content), and returns the previous value so that the
command can remember it. `list(path)` creates an empty list in its parent object when absent, so
adding the first effect to a feat that had none works. `version` increments on every change for
the panel's caches; `isDirty()` compares a *fingerprint* of the files it would write (an FNV-style
hash over the serialized tables, recomputed only when the version changed) with the one at load or
save, so undoing back to the saved state is clean again even though the version moved.

`load(dir)` reads every `<kind>.json`; `save` writes each one to a `.tmp` and renames it over the
target (L04 §15 again), in a layout "the way the files are kept by hand": one definition per line,
scalar lists inline, lists of objects one per line, so a diff of a saved file is readable.
`validate(out)` appends every problem the importer would report, with the same messages: a
missing or duplicate id, an unknown field, a wrong type, an enum name not in the list, a dice
expression that does not parse (`Dice.parse`, [Lesson 2](lessons/02-dice-and-probability.md)), a
reference to a missing id, an operation without the `ref` its kind needs. `newDefinition` and
`newOperation` make the fresh objects the *Add* buttons insert, and `freeId` finds `base`,
`base2`, `base3`, ... unused in a kind.

### 26.3 `RulesSetCommand` and `RulesListCommand`

```java
public final class RulesSetCommand implements Command {
    private final RulesPath path;
    private Object before;
    private Object after;
    private boolean applied;

    public RulesSetCommand(RulesPath path, Object after) {
        this.path = path;
        this.after = after;
    }

    // ... path() returns the path; name() is "Set " + path ("Set feats[1].effects[0].value")

    @Override
    public void apply(EditorContext ctx) {
        Object previous = ctx.rules().set(path, RulesSource.copy(after));
        if (!applied) {
            before = previous;
            applied = true;
        }
    }

    @Override
    public void revert(EditorContext ctx) {
        ctx.rules().set(path, RulesSource.copy(before));
    }

    @Override
    public boolean mergeWith(Command next) {
        if (next instanceof RulesSetCommand s && s.path.equals(path)) {
            after = s.after;
            return true;
        }
        return false;
    }
}
```

Two differences from the level's commands. The *before* is captured on the **first `apply`**
(`applied` guards it) rather than at construction, because `set` returns the previous value for
free; a redo must not recapture it. And values are **deep-copied** on the way in (`RulesSource.copy`:
new maps and lists, scalars shared) so that a tree held by a command can never be mutated through
the model. `RulesListCommand` inserts, removes or moves one element of a list at a path; a removal
keeps the element it took out, so undo re-inserts the very same tree
(`RulesCommandsTest.listCommandsInsertRemoveAndMoveWithUndo` checks it is the same object). A
move's revert is a move back.

### 26.4 The panel's two tricks

The grid is generated from `RulesSchema`: a `Kind` has fields, each with a type (`STRING`, `INT`,
`BOOL`, `ENUM`, `FLAGS`, `DICE`, `REF`, `REF_LIST`, `INT_LIST`, `OPS`, `NESTED`), and `grid(...)`
switches on the type to declare a text field, a number field, a checkbox, a dropdown, a column of
flag checkboxes, a dice field that validates as you type, a reference dropdown over the referenced
kind's ids, a list of references, an operation list with add, remove and reorder, or nested rows
(a class's features, a monster's attacks) as tree nodes with the same grid, recursively. Every
change pushes a `RulesSetCommand` (merged for text and numbers, not for dropdowns and checkboxes)
or a `RulesListCommand`. Two problems make this different from the Inspector:

**Paths without allocating.** A command needs a `RulesPath`, but building one per field per frame
would allocate thousands of arrays. The panel keeps a `PathStack`, the path to what the grid is
drawing as a stack of segments, pushed and popped as the grid descends, and materializes a
`RulesPath` only when a command is pushed:

```java
    private static final class PathStack {
        private final Object[] segments = new Object[32];
        private final Integer[] boxed = new Integer[1024]; // indices without boxing per frame
        private int depth;

        void push(int index) {
            if (index < boxed.length) {
                if (boxed[index] == null) {
                    boxed[index] = index;
                }
                segments[depth++] = boxed[index];
            } else {
                segments[depth++] = index;
            }
        }

        RulesPath path(Object key) {
            Object[] s = java.util.Arrays.copyOf(segments, depth + 1);
            s[depth] = key;
            return RulesPath.of(s);
        }
```

The `boxed` cache is the small trick: a list index is an `Integer` in a path, and `Integer.valueOf`
allocates above 127, so the first frame boxes each index once and keeps it.

**Text buffers from a pool.** The grid does not know in advance how many text fields it will
declare (a monster has nested attacks with their own dice), so it draws them from a pool of 128
`TextBuffer`s assigned in declaration order (`poolCursor = 0` at the top of `draw`, `buffer()`
hands out the next). A buffer is re-filled from the value only when its field is not focused and
the text differs, the Inspector's rule. The dice fields keep, per pool slot, the buffer's version
at which they last parsed it and whether it parsed, so `Dice.parse` runs once per edit, not per
frame. The dropdown option arrays (one per kind, the ids) and the validation report are rebuilt
only when the content's version changed (`refreshCaches`).

`RulesPanelTest` (§31.9) drags a number field on the demo's `dodge` feat and gets one merged
entry, clicks a flag checkbox of a condition, adds, duplicates and deletes definitions, saves and
reads the file back, and holds the richest grid (a monster with nested attacks) at 0 bytes per
frame.

## 27. Step 14: the widgets the panels needed

The `ui` module of chapter [13](13-ui.md) gained what the panels need and games can use too: a
menu bar with hover switching between open menus, splitters, number fields that drag or take
typed input, vector and colour fields, list boxes, selectable rows and a property-row helper,
with allocation-free float formatting and parsing. Chapter 13 owns the widget machinery (ids,
state slots, focus, the press/release state machine); this section says what the editor's widgets
add on top of it.

### 27.1 `numberField`: a drag with a second life

```java
    public float numberField(long id, float value, float step, float min, float max, int decimals) {
        sync();
        int slot = ui.state(id);
        boolean editing = ui.stateI(slot, 1) != 0;
        if (editing) {
            boolean cancel = ui.input().pressed(UiKey.ESCAPE);
            ui.pushId(id);
            textField(FIELD, numberEdit);
            boolean enter = submitted(FIELD);
            boolean focused = ui.focused(FIELD);
            ui.popId();
            if (cancel) {
                ui.setStateI(slot, 1, 0);
            } else if (enter || !focused) {
                float parsed = parseFloat(numberEdit);
                if (!Float.isNaN(parsed)) {
                    value = Math.clamp(parsed, min, max);
                }
                ui.setStateI(slot, 1, 0);
                if (enter) {
                    ui.focus(0);
                }
            }
            return value;
        }
        if (ui.doubleClicked(id)) {
            numberEdit.clear();
            appendFloat(numberEdit, value, decimals);
            numberEdit.selectAll();
            ui.setStateI(slot, 1, 1);
            ui.pushId(id);
            ui.focus(FIELD);
            ui.popId();
        }
        if (ui.active(id)) {
            value += mouseDX * step;
        }
        if (ui.focused(id)) {
            UiInput in = ui.input();
            if (in.pressed(UiKey.LEFT) || in.pressed(UiKey.DOWN)) {
                value -= step;
            }
            if (in.pressed(UiKey.RIGHT) || in.pressed(UiKey.UP)) {
                value += step;
            }
        }
        value = Math.clamp(value, min, max);
        Box b =
                ui.box(id)
                        .widthGrow()
                        .height(theme.widgetHeight)
                        .minWidth(48)
                        .padding(theme.padding * 0.5f, 0)
                        .align(Ui.END, Ui.CENTER)
                        .radius(theme.radius)
                        .background(widgetColor(id, true))
                        .hit()
                        .focusable()
                        .captureKeys();
        focusRing(b, id);
        b.open();
        ui.text().append(value, decimals).noKerning();
        ui.close();
        return value;
    }
```

A number field is `dragValue` (chapter 13) with one bit of per-widget state, "editing", in the UI's
state store (`ui.state(id)` gives the widget's slot; integer 1 of it is the flag). Not editing: a
held button adds `mouseDX × step` per frame (0.05 m per pixel for a position), the arrow keys step
once, the value is clamped and drawn right-aligned without kerning so digits do not jitter. A
double click copies the value into the *shared* `numberEdit` buffer (one buffer for every number
field in the UI, since only one can be edited at a time), formatted without allocating by
`appendFloat`, selects it all, sets the flag and focuses a text field declared *inside the number
field's own id scope* (`pushId(id)`, then `FIELD`: the id `PanelsTest` folds last). Editing: the
text field edits the buffer; Enter or a click elsewhere (`!focused`) commits through `parseFloat`
(a small decimal parser that returns NaN for nonsense, which is ignored), Escape cancels. The
widget returns the value *after* this frame's input, so the caller compares it with what it
passed to know whether to push a command.

`EditorWidgetsTest.numberFieldDragsAndTakesTypedValues` drags 20 pixels at step 0.1 for +2, double
clicks, types `-7.25`, Enter, and reads −7.25; Escape leaves it; `x` is ignored.

### 27.2 Formatting and parsing without allocating

`appendFloat(out, v, decimals)` rounds $v \cdot 10^{d}$ to a `long`, writes the whole part digit
by digit from the highest power of ten that fits (so no `String`, no `Long.toString`), then the
point and $d$ fractional digits with leading zeros; `0.999` at two decimals becomes `1.00`
(rounded as a whole, not per digit). `parseFloat(s)` accepts an optional sign, digits, a point,
digits, an exponent (`3e2`), and surrounding spaces, accumulating in a `double`, and returns NaN
for anything else (`"1.2.3"`, `""`, `"abc"`). `floatFormattingAndParsingRoundTrip` checks the
examples and holds a hundred format-and-parse round trips at 0 bytes.

### 27.3 Vector, colour, list and property rows

```java
    public int vectorField(
            long id, float[] v, int o, int n, float step, float min, float max, int decimals) {
        int changed = 0;
        ui.box(id).row().gap(4).widthGrow().align(Ui.START, Ui.CENTER).open();
        ui.pushId(id); // the component fields live in the row's scope
        for (int k = 0; k < n; k++) {
            ui.pushId(k);
            ui.text(AXIS_LABELS[k]).fontSize(theme.fontSizeSmall).color(AXIS_COLORS[k]);
            float before = v[o + k];
            float after = numberField(FIELD, before, step, min, max, decimals);
            if (after != before) {
                v[o + k] = after;
                changed |= 1 << k;
            }
            ui.popId();
        }
        ui.popId();
        ui.close();
        return changed;
    }
```

A vector field is $n$ number fields labelled X, Y, Z, W in the axis colours, **in the field's own
id scope and each in its component's** (`pushId(id)`, then `pushId(k)`): the fix of §13.3. It edits
the array in place and returns a bit mask of the changed components, so the Inspector pushes one
command per component and nothing when nothing changed. `vector3Field` is `vectorField` with $n =
3$ and wide limits. `colorField` is a swatch (the linear colour converted to an sRGB byte per
channel for display: the UI draws in sRGB space, chapter 13) and three channels, the same shape.
`listBox` is a scrolling column of selectable rows, each in `pushId(i)`, returning the selected
index; `beginProperty(label)` opens a row with a dim label of the theme's label width, so values
line up in a grid; `splitter` was §24.3. `EditorWidgetsTest` drags the vector's X and the
colour's green and checks the other components are untouched, and holds the whole set at 0 bytes.

## 28. Step 15: `Editor`, the controller

### 28.1 Construction and wiring

```java
public final class Editor {
    private static final long ROOT = 0xED17L;
    private static final long MENU_BAR = 1;
    private static final long MENU_FILE = 2;
    private static final long MENU_EDIT = 3;
    private static final long MENU_VIEW = 4;
    private static final long MENU_MODE = 5;
    private static final long ITEM = 100;
    private static final long PANEL_ITEM = 200;
    private static final CharSequence[] MODE_NAMES = {"Move (W)", "Rotate (E)", "Scale (R)"};

    private final EditorContext ctx;
    private final Viewport viewport;
    private final Dock dock = new Dock();
    private final ConsolePanel consolePanel = new ConsolePanel();
    private boolean shown;
    private Path file;
    private String fileName = ""; // cached: Path.getFileName allocates
    private LevelFiles.Save saving;
    private final ViewportInput viewportInput = new ViewportInput();
    private final IntList highlighted = new IntList(64);
    private int highlightedVersion = -1;
    private int levelVersionSeen = -1;

    public Editor() {
        ctx = new EditorContext(null, 256);
        viewport = new Viewport(ctx);
        ctx.setEditor(this, viewport);
        dock.add(new OutlinerPanel(), Dock.LEFT);
        dock.add(new AssetsPanel(), Dock.LEFT);
        dock.add(new InspectorPanel(), Dock.RIGHT);
        dock.add(new SettingsPanel(), Dock.RIGHT);
        dock.add(new EntitiesPanel(), Dock.HIDDEN);
        dock.add(new RulesPanel(), Dock.HIDDEN);
        dock.add(consolePanel, Dock.BOTTOM);
        dock.add(new HistoryPanel(), Dock.HIDDEN);
    }
```

An `Editor` owns one context, one viewport, one dock with the eight panels in their default zones
(the registration order is the panel index the tests fold into ids: Outliner 0, Assets 1,
Inspector 2, Settings 3, Entities 4, Rules 5, Console 6, History 7). The file name is cached
because `Path.getFileName()` creates a new `Path` object every call, and the menu bar shows the
name every frame. The host wires what it has:

```java
    public void attach(World world, LevelLoader loader) {
        ctx.setWorld(world, loader);
    }

    public void attachAssets(Vfs vfs, AssetType<SceneData> scenes) {
        ctx.setAssets(vfs, scenes);
    }

    public void attachSchema(
            dev.jeddy.asset.format.ComponentSchema schema, EditorContext.ComponentSync sync) {
        ctx.setSchema(schema, sync);
    }

    public boolean attachRules(Path dir) {
        try {
            RulesSource rules = RulesSource.load(dir);
            ctx.setRules(rules);
            ctx.console().info("rules " + dir.toAbsolutePath());
            return true;
        } catch (IOException | RuntimeException e) {
            ctx.console().error("rules " + dir + ": " + e.getMessage());
            return false;
        }
    }

    public void open(LevelData level, Path file) {
        close();
        ctx.setLevel(level);
        setFile(file);
        ctx.settings().translateStep = level.settings.gridStep;
        ctx.undo().clear();
        ctx.selection().clear();
        levelVersionSeen = level.version();
        dock.levelChanged(ctx);
        ctx.console().info("opened " + (file == null ? "a new level" : file.toString()));
    }
```

Everything attached is optional: without a world the commands still edit the level (the command
tests), without a VFS the Assets panel says so, without a schema the Components section is
absent, without rules the Rules panel says where to find them. `open` takes the level's grid step
as the snapping step, clears the history (a fresh level is clean) and the selection, and tells
the panels. `schemaOf(Class)` loads `META-INF/jeddy/components.json` through a game class's loader,
for the embedded host.

### 28.2 The frame

```java
    public void update(AppContext app, Ui ui, float dt) {
        if (!shown) {
            return;
        }
        pollSave();
        Input in = app.input();
        viewportInput.copyFrom(in, ui);
        if (ctx.level() == null) {
            viewport.update(viewportInput, dt);
            return;
        }
        pruneSelection();
        if (!ui.wantsKeyboard()) {
            shortcuts(in);
        }
        viewport.update(viewportInput, dt);
        syncHighlights();
    }

    public void declare(Ui ui, Widgets widgets) {
        if (!shown) {
            return;
        }
        ctx.setUi(ui, widgets);
        float width = ui.width();
        float height = ui.height();
        ui.box(ROOT)
                .floatingOnScreen()
                .anchor(Ui.TOP_LEFT, Ui.TOP_LEFT)
                .size(width, height)
                .column()
                .gap(0)
                .z(50)
                .noHit()
                .open();
        menuBar(widgets);
        dock.declare(ctx, widgets, widgets.theme.widgetHeight, width, height);
        ui.close();
        viewport.setRegion(dock.regionX(), dock.regionY(), dock.regionW(), dock.regionH());
        viewport.declare(ui);
        ctx.setUi(null, null);
    }

    public void extract(RenderPacket packet) {
        if (!shown) {
            return;
        }
        viewport.extract(packet);
    }
```

Hidden, all three return at once: the editor costs nothing per frame when closed. `update`:
finish a save if one completed (a console line), copy the input, prune ids that no longer exist
from the selection (only when the level's version changed: after an undo of an add), run the
shortcuts unless a text field has the keyboard, run the viewport, and outline the selection.
`declare` builds the full-window root (floating at the top-left, `z(50)` above the game's own UI,
`noHit()` so the viewport gets the pointer where no panel is), the menu bar, the dock, then hands
the dock's free rectangle to the viewport and lets it declare the rubber band. The context's `ui`
is set only for the duration, so a panel cannot hold on to it. `extract` adds the grid, markers
and gizmo after the game's own lines, so the overlay mark of §12 falls after everything else.

### 28.3 Shortcuts, highlights, actions

```java
    private void shortcuts(Input in) {
        int mods = in.modifiers();
        boolean ctrl = (mods & Modifiers.CTRL) != 0;
        boolean shift = (mods & Modifiers.SHIFT) != 0;
        if (ctrl && in.wasKeyPressed(Scancode.Z)) {
            if (shift) {
                redo();
            } else {
                undo();
            }
        } else if (ctrl && in.wasKeyPressed(Scancode.Y)) {
            redo();
        } else if (ctrl && in.wasKeyPressed(Scancode.S)) {
            save();
        } else if (ctrl && in.wasKeyPressed(Scancode.D)) {
            duplicateSelection();
        } else if (ctrl && in.wasKeyPressed(Scancode.A)) {
            selectAll();
        } else if (in.wasKeyPressed(Scancode.DELETE) || in.wasKeyPressed(Scancode.BACKSPACE)) {
            deleteSelection();
        } else if (in.wasKeyPressed(Scancode.ESCAPE)) {
            if (viewport.isDragging()) {
                viewport.cancel();
            } else {
                ctx.selection().clear();
            }
        } else if (!ctrl && in.wasKeyPressed(Scancode.W)) {
            viewport.setMode(Viewport.MODE_TRANSLATE);
        } else if (!ctrl && in.wasKeyPressed(Scancode.E)) {
            viewport.setMode(Viewport.MODE_ROTATE);
        } else if (!ctrl && in.wasKeyPressed(Scancode.R)) {
            viewport.setMode(Viewport.MODE_SCALE);
        } else if (!ctrl && in.wasKeyPressed(Scancode.F)) {
            viewport.frameSelection();
        } else if (!ctrl && in.wasKeyPressed(Scancode.G)) {
            ctx.settings().showGrid = !ctx.settings().showGrid;
        } else if (!ctrl && in.wasKeyPressed(Scancode.X)) {
            ctx.settings().snap = !ctx.settings().snap;
        }
    }
```

Scancodes, not key names: a scancode is a key's *position* on the keyboard (SDL's convention,
which the `input` module keeps), so W/E/R are the same physical keys on every layout. Escape
cancels a drag if one is on, else
clears the selection. The menu bar (`menuBar`) declares the same actions as items with their
shortcut labels, the View menu's checks for the grid, markers, snapping and every panel's
visibility (`dock.toggle(i)`), a Mode menu, and on the right the file name, a `*` when dirty, the
selection count and the snap state.

`syncHighlights` outlines every selected placement's group in the world (`HIGHLIGHT_SELECTED` for
the primary, `HIGHLIGHT_HOVER` for the rest), only when the selection's version changed; it
remembers what it outlined in `highlighted` so `clearHighlights` can take the outlines off when the
selection or the editor's visibility changes. The actions: `push(command, merge)`, `undo` and
`redo` (with a console line naming the command), `deleteSelection` (one `DeleteObjectsCommand` of
the selection's ids, then clear), `duplicateSelection` (`AddObjectsCommand.duplicate` one grid
step along $+X$, then select the copies by the ids the command allocated), `selectAll`.

### 28.4 Saving and settings

```java
    public void saveAs(Path path) {
        if (ctx.level() == null) {
            return;
        }
        if (saving != null && !saving.isDone()) {
            ctx.console().warn("a save is still in progress");
            return;
        }
        setFile(path);
        saving = LevelFiles.save(ctx.level(), path);
        ctx.undo().markSaved();
    }

    private void pollSave() {
        if (saving != null && saving.isDone()) {
            Throwable error = saving.error();
            if (error == null) {
                ctx.console().info("saved " + saving.path());
            } else {
                ctx.console().error("save failed: " + error);
            }
            saving = null;
        }
    }
```

`save()` is `saveAs(file)` or a warning without one. The saved mark is set when the bytes are
*taken* (`LevelFiles.save` serializes before it returns), not when the write finishes: the history
state that was written is the one at that moment. A second save while one is in flight is
refused rather than queued, with a warning. `saveSettings(settings)` writes `editor.layout` and the
editor settings (`editor.snap`, `editor.translateStep`, `editor.rotateStep`, `editor.scaleStep`,
`editor.grid`, `editor.markers`, `editor.gizmoPixels`, `editor.orbitSpeed`) into the host's
`Settings`, and `loadSettings` reads them back with the defaults as fallbacks; both hosts call
them at shutdown and start.

## 29. Step 16: the hosts

### 29.1 Inside the iso demo

`DemoGame` holds an `Editor` from construction. When the crypt is built it attaches the world and
the level loader, the VFS and the scene type for the Assets panel, opens the loaded level with its
source path (`--level-source`, by default `samples/iso-demo/assets/levels/crypt.jlevel` when run
from the repository, else `build/crypt.jlevel`), and attaches the components schema with a sync
that reloads an object's rows into the running game:

```java
        editor.attach(world, levelLoader);
        editor.attachAssets(renderer.vfs(), scenes);
        editor.open(levelLoader.level(), options.levelSource);
        if (options.editor) {
            toggleEditor();
            // The game camera has not followed the party yet on this first frame: look at the
            // level (or whatever --editor-command selects).
            for (String command : options.editorCommands) {
                editor.runCommand(command);
            }
            editor.viewport().frameSelection();
        }
        ...
        editor.attachSchema(
                Editor.schemaOf(DemoGame.class),
                (lvl, id) -> {
                    int e = levelLoader.rootOf(id);
                    if (e != 0) {
                        game.despawn(e);
                        game.loadObject(lvl, id, e, binding);
                        game.spawn(e);
                    }
                });
```

Then, per frame in `update`:

```java
            if (in.wasKeyPressed(Scancode.F10) && !demoUi.ui.wantsKeyboard()) {
                toggleEditor();
            }
            if (editor.isShown()) {
                // The editor owns the pointer, the keys and the camera while it is open; the
                // game keeps simulating underneath (Space pauses it as usual).
                editor.update(ctx, demoUi.ui, dt);
                if (in.wasKeyPressed(Scancode.SPACE) && !demoUi.ui.wantsKeyboard()) {
                    ctx.simulation().setPaused(!ctx.simulation().isPaused());
                }
            } else {
                handleInput(ctx, in);
            }
```

and `toggleEditor` copies the game camera into the editor's on opening (`takeCameraFrom`: target,
yaw, pitch, distance, field of view, near, the orthographic flag and height, so nothing jumps) and
logs the key help. While shown, the game skips its own input handling and camera update, hides its
HUD (`DemoUi.declare` skips the status panel, the messages, the portraits, the combat log and the
turn panel when `editorOpen` is set, because the editor's panels take the screen edges), draws the
editor's panels after its own UI (`editor.declare(demoUi.ui, demoUi.w)`), renders with the editor's
camera (`IsoCamera cam = editor.isShown() ? editor.camera() : camera` in `extract`), lifts the fog
of war (`p.fogOfWar = null`: the whole level is visible while editing it) and adds the editor's
lines after its own debug lines. The simulation keeps running: the Entities panel shows the party
and the skeletons moving, and Space pauses as usual. When the rules asset's version changes
because the Rules panel saved and the cooker re-cooked, `buildCombat()` rebuilds the fight's
creatures from the new content (chapter [15](15-rules.md)).

### 29.2 The standalone tool: `EditorMain` and `EditorOptions`

```java
    public static void main(String[] args) throws IOException {
        EditorOptions options = EditorOptions.parse(args);
        if (options.level == null) {
            System.err.println("no .jlevel found under " + options.project + "; pass --level");
            System.exit(2);
        }
        Path levelFile = options.project.resolve(options.level);
        LevelData level;
        if (Files.isRegularFile(levelFile)) {
            level = LevelFiles.read(levelFile);
        } else {
            LOG.info("creating a new level at " + levelFile);
            level = new LevelData();
            level.addRule("floor*", 1, 1, 0);
            level.addRule("wall*", 30, 2, 0);
            level.addRule("prop*", 20, 3, 0);
            level.addRule("*", 0, 0, 0);
            level.addSizeClass("medium", 0.4f, 1.8f, 0.4f);
        }
        Files.createDirectories(options.cooked);
        CookerService cooker = null;
        if (options.cook) {
            CookerConfig config =
                    CookerConfig.of(options.project, options.cooked)
                            .withCache(options.cache)
                            .withShaders(options.shaderIncludes, true);
            cooker = new CookerService(config);
            cooker.start();
        }
        Vfs vfs = new Vfs();
        int mount = vfs.mountDirectory(options.cooked);
        RenderPackets packets = new RenderPackets(8192);
        EditorRenderer renderer =
                new EditorRenderer(vfs, packets, level.settings.environment, options.screenshot);
        EditorGame game =
                new EditorGame(options, renderer, packets, vfs, mount, cooker, level, levelFile);
        AppConfig config =
                new AppConfig()
                        .app("Jeddy editor", "0.1.0", "dev.jeddy.editor")
                        .window(
                                new WindowConfig()
                                        .title("Jeddy editor – " + options.level)
                                        .size(options.width, options.height)
                                        .openGl(true)
                                        .vsync(VSync.ADAPTIVE))
                        .frameCaps(120, 20)
                        .renderer(renderer);
        int code = JeddyApp.run(config, game);
        if (cooker != null) {
            cooker.close();
        }
        vfs.close();
        System.exit(code);
    }
```

A **project** is an asset source tree (`--project`) and its cooked output (`--cooked`, default
`<project>/../build/cooked`); the level is a `.jlevel` under the source tree (`--level`, default the
first one found). A level that does not exist yet is created with the crypt's four rules and the
`medium` size class, so a new project starts editable. The cooker runs unless `--no-cook`; the
cooked directory is mounted into a `Vfs` the way a game would mount it; the renderer gets the
level's environment map; and `JeddyApp.run` (chapter [01](01-architecture.md)) starts the main and
game threads. `EditorOptions` also takes `--shader-include` (repeatable, for the cooker),
`--components FILE` (the game's schema; default the sibling `build/classes/java/main/META-INF/jeddy/components.json`
the last compile wrote), `--size WxH`, and the smoke-run trio `--frames N`, `--screenshot FILE`,
`--command CMD`.

### 29.3 `CookerService`: the cooker on its own thread

```java
    private void run() {
        try (Cooker cooker = new Cooker(config)) {
            report(cooker.cookAll());
            firstCookDone = true;
            try (SourceWatcher w = new SourceWatcher(config.sourceRoot())) {
                watcher = w;
                lines.add("watching " + config.sourceRoot());
                while (!Thread.currentThread().isInterrupted()) {
                    Set<String> changed = w.poll(1, TimeUnit.HOURS, 150);
                    if (changed.isEmpty()) {
                        continue;
                    }
                    report(
                            changed.contains(SourceWatcher.EVERYTHING)
                                    ? cooker.cookAll()
                                    : cooker.cook(cooker.affectedBy(changed)));
                }
            } catch (ClosedWatchServiceException | InterruptedException e) {
                // shutting down
            }
        } catch (IOException e) {
            LOG.error("cooker failed", e);
            lines.add("cooker failed: " + e);
            failed = true;
            firstCookDone = true;
        }
    }
```

A platform daemon thread named `jeddy-cooker` runs a full cook, then the `SourceWatcher` of
chapter [10](10-assets.md): `poll` blocks up to an hour, coalescing changes for 150 ms, and the
cooker re-cooks what the changed files affect (a saved `.jlevel` re-bakes its physics; an edited
glTF re-cooks its scene *and* every level that places it, through the dependency of §18.3).
Reports become lines in a `ConcurrentLinkedQueue` that the game thread `drain`s into the console
every frame (L00 §9.5: a queue hands the strings over whole); `firstCookDone` and `failed` are
`volatile` because two threads read them. `close` interrupts the thread and closes the watcher,
which makes `poll` throw and the loop end.

### 29.4 `EditorGame` and `EditorRenderer`

`EditorGame` is the game thread's side: in `init` it registers the scene asset type, creates an
`AssetReloader` on the mount (chapter 10's hot reload: the cooker rewrites `levels/dungeon/*.jmesh`,
the reloader rescans, the loaded meshes swap behind their handles), the UI bridge and fonts, a
`World` of 16,384 entities and a `LevelLoader`, reserves the three render packets, and wires the
editor: world and loader, assets, the rules directory if `<project>/rules` exists, the components
schema if the file exists (with a `null` sync: no game runs here, rows are edited, not applied),
the saved settings, then `loader.load(level)`, `editor.open(level, levelFile)`, `setShown(true)`, a
camera at 30 m and 50° pitch, and `frameSelection`. Per frame, `update` pumps the asset manager,
the reloader, the fonts and the cooker's lines, announces the level once instantiated, runs the
`--command`s once the loader is ready, and then does what a game does: `bridge.beginFrame`,
`editor.update`, `world.updateTransforms`, `editor.declare`. `extract` fills the packet with the
editor's camera, the level's own sun, exposure and environment settings (the Settings panel's
changes show at once), the world, the level's lights with shadow maps for the first four with
the `SHADOW` flag, the debug lines and the editor's lines, and the UI. `onQuitRequested` refuses
the first quit with unsaved changes ("save with Ctrl+S, or quit again to discard"); `shutdown`
saves the settings.

`EditorRenderer` is the main thread's side: a `WorldRenderer` with an empty HUD drawing the
packets, plus the smoke run's screenshot: after the requested frame, `glFinish()` (L00 §10.5: the
one synchronization that waits for the GPU to finish everything), `glReadPixels` of the window into
a confined arena, and `Png.write`, a minimal PNG encoder in `java.base` only (rows bottom-up as GL
returns them, filter byte 0 per row, zlib through `Deflater`, `IHDR`, `IDAT`, `IEND` chunks with
CRC-32; L04 §6 explains the CRC). CI runs exactly this: the crypt for 30 frames under Xvfb at
1280×720 with `select 150 151`, and checks the PNG.

# Part C — Using it and proving it

## 30. One drag, frame by frame

Put the pieces together on the drag of `ViewportTest.translateAlongAnAxisHandle`, with the
camera of §8.1 and the test level of `WorldFixture` (a wall at $(4, 0, 0)$, a table at $(0, 0, 6)$,
a torch, a spawn, a trigger). The wall is selected.

**Frame 0, the pointer idle at the window's corner.** `Editor.update` copies the input,
`Viewport.update` recomputes the camera, computes the ray through $(0, 0)$, reads the wall's
position into `center`, sizes the gizmo ($L = 1.286$ m), and, since the pointer is outside every
handle, sets `hover = NONE`. `Editor.declare` builds the menu bar and the dock; the dock hands the
free rectangle to the viewport. `extract` draws the grid, the markers and, after
`beginDebugOverlay`, the three axes with arrow heads and the three squares at the wall. Nothing
allocated.

**Frame 1, press on the X handle.** The test projects $\mathbf{c} + 0.5L\,\mathbf{x}$ to pixel
$(432.35, 326.50)$ and presses there. `hitTest`: the squares' plane hits do not fall in $[0.3L,
0.65L]$ on both axes; the X axis's projected segment runs from $(400, 300)$ to $(465.78, 353.89)$
and the pointer is on it, distance 0, within 8 pixels: `hover = AXIS_X`. `leftPressed` inside the
region: `beginDrag(AXIS_X)`. A `TransformCommand` of the wall's ten floats is created (the
allocation of the frame), the pivot is frozen at $(4, 0, 0)$, and `startParam =
closestAxisParam(...) = 0.643$ (§10.1). `active = AXIS_X`, `dragPushed = false`. Not yet in the
history: nothing has changed.

**Frame 2, the pointer at the projection of $\mathbf{c} + 1.5L\,\mathbf{x}$, button held.**
`updateDrag`: the new ray gives $t = 1.929$, $d = t - t_0 = 1.286$; snapping is off in the test, so
`writeTranslate(1.286, 0, 0)`: `after[0] = before[0] + 1.286 = 5.286`, and `center` moves with it.
`drag.changed()` is now true and `dragPushed` false: `ctx.undo().push(drag)`. The stack applies the
command: `LevelObjects.writeRow` writes the wall's row (`setPlacementFloat` ten times, the version
bumps), `ctx.syncTransform(id)` finds the placement's index through the id map and calls
`loader.syncTransform(0)`, which calls `world.setGroupTransform(root, placementData, 0)`: the
wall's root entity moves and `staticStructure++` because the wall is static. The entry's name is
"Move". The test asserts `placementFloat(0, 0) == 4 + L` to $10^{-3}$ and that $z$ is untouched.

**Frames 3 to n, still held.** Every frame recomputes the delta *from the pivot* and calls
`drag.apply(ctx)` again; the history still has one entry. The renderer, on its own thread, sees a
new `staticVersion` each frame and rebuilds its static set (D49).

**Release.** `endDrag`: `active = NONE`, `drag = null`. The history holds "Move" at cursor 1; the
level is dirty; the Inspector's next `sync` sees a new version and re-reads the position fields
(the name buffer is not focused, so it is re-read too, unchanged). `editor.undo()` reverts the
entry: the row goes back to $(4, 0, 0)$ and the world follows. With Escape instead of a release,
`cancel` would call `cancelTop`: the same revert, and the entry removed.

**With snapping on** (the second half of the test, step 0.5): the delta $1.286$ snaps to $1.5$,
the wall lands at $x = 5.5$; the test computes the same $\operatorname{round}(L / 0.5) \cdot 0.5$.

## 31. The tests, walked through

All of the editor's tests run headless in a few seconds (`./gradlew :editor:test`); the ones
that need a world build one in a temporary directory from a two-prefab library scene written on
the fly (`WorldFixture`, §31.5). The GL side has no test here: the overlay lines are the
`DebugRenderer` of chapter [08](08-effects-and-post.md), and the CI smoke run (§29.4) draws the
real editor under Xvfb.

### 31.1 `LevelDataTest` (asset)

`sample()` builds a level with two prefabs (deduplicated), three placements (one with a collision
override), a torch, two spawns, a trigger, an area, a link, three rules, a size class and changed
settings. `roundTripsEveryKind` writes it, checks the size is a multiple of 16, reads it back and
asserts every field of every kind, that ids survive, and that a second write is byte-identical.
`idsAreUniqueAcrossKindsAndStableThroughRemoval` checks `kindOf` for all four kinds and an absent
id, removes the middle placement (the chest keeps its id and moves to index 1), reinserts the wall
at index 1 with its old id (order and ids restored, `nextId` unchanged), and inserts an id of 500
(`nextId` becomes 501). `mutationsBumpTheVersion`, `rejectsBadFiles` (a wrong magic, a truncated
file, version 9), `componentRowsRoundTripAndFollowTheirObjects` (every value type through the
file, `copyFrom`, `copyComponentRows`, rows removed with their objects) and `copiesAndDumps`
complete it.

### 31.2 `LevelLoaderTest` (app)

Writes a quad mesh and a library scene (`wall` with one mesh node 1 m up; `table` with a base and a
top 1 m up at scale 2) into a temporary directory, mounts it, and loads a level of a wall at
$x = 4$ and a table at $z = 6$. `placementsBecomeGroupsAtTheirTransforms` settles the loader and
checks three renderables, the wall's world matrix (translation at indices 3, 7, 11 of the
row-major 3×4: $x = 4$, $y = 1$), the table top's ($z = 6$, $y = 1$, scale 2 at index 0), the
mapping both ways (`rootOf`, `placementOf`, `groupOf`), and that `extractLights` adds one light
without allocating. `movingRemovingAndAddingPlacementsFollowsTheLevel` moves the wall through
`syncTransform` (the matrix follows; a second move bumps `staticVersion`), removes the table
(`placementRemoving` then `removePlacement`: one renderable left, the root dead, two transforms),
inserts a wall at the front (instantiated at once, the old wall's root shifted to index 1), and
unloads. `wholeScenesAndHiddenPlacements` places the whole scene hidden (three renderables, none
extracted) and un-hides it through `syncFlags` (three extracted, with `FLAG_STATIC`).

### 31.3 `LevelImporterTest` (cooker)

`placementsBecomeCollisionNavmeshAndOcclusion` writes a glTF library (`floor` 4 × 4 m under a root
node, `block` a cube scaled 2 tall through a child node, `decor` a cube nobody collides with) and
a level: a 3 × 3 floor, a block at $(6, 6)$ turned 90°, decor on a layer no rule matches, a `ghost`
with `NO_COLLISION`, a `crate` on the decor layer with a collision *override*, two size classes
and an area. It cooks and asserts: the `.jlevel` copied unchanged; the BVH has $9 \times 2 + 12 + 12$
triangles (floors, block, crate; no decor, no ghost); a ray down at $(6, 5, 6)$ hits the block's
top at $t = 3$ (2 m tall on a 4 m tile: $5 - 2$) with `BLOCKS_LOS` and surface 1; at $(1, 5, 1)$ the
floor at $t = 5$ with surface 2; at $(10, 5, 2)$ the crate at $4.5$ (0.25 × 2 m tall) with the
override's surface 3; nothing where the ghost stands. Then the two navmeshes with their radii,
walkable floor and nothing under the block, and the occlusion grid blocked at the block and clear
at the decor. A second part writes a `.meta` sidecar excluding the block's mesh node and cooks
again: the level re-cooks (it depended on the absent, now present, settings file), the block and
the crate vanish from the BVH, the space under the block becomes walkable, and a third cook is
`CACHED`. `componentRowsAreValidatedAgainstTheSchemaAndBoundToJava` is chapter 22's.

### 31.4 `UndoStackTest`

A command `Add` that adds a delta to a counter and merges with another `Add`. Five tests:
`appliesUndoesAndRedoesInOrder` (names, the redo tail discarded by a push after undo),
`mergesContinuousEditsIntoOneEntry` (three merged pushes make one entry that undoes to 0; without
the flag they stay separate), `tracksTheSavedState` (every case of §7.3: dirty after a push, clean
after `markSaved`, dirty after undo past it, clean after redo, a merge that refuses to cross the
saved mark, a new branch that makes the saved state unreachable),
`forgetsTheOldestPastCapacity` (six pushes into a capacity of four: `seek(0)` stops at $1 + 2 =
3$) and `cancelRevertsAndForgets`.

### 31.5 `CommandsTest` and `WorldFixture`

`roundTrip(ctx, command, check)` is the test's engine: take the level's bytes, push the command,
run the check, take the bytes again (they must differ), undo (the bytes must equal the first
snapshot *exactly*), redo (equal to the second), check again, undo. Every command goes through
it: `transformMovesRowsOfEveryKind` (a placement, a light and a spawn moved by 2.5 together, then
the drag protocol and a merge), `addDeleteAndDuplicateKeepIdsAndIndices` (delete the first
placement and the trigger: the table shifts to 0 and back; add one of each kind; duplicate a wall
and a torch with new ids and " copy" names), `fieldCommandsSetAndMerge` (strings on two objects, a
spawn's kind, a flag that keeps the other flags, a collision override, three merged floats that
undo to the first *before*, and a different float that does not merge),
`settingsAndTablesRoundTrip` (settings, a rule inserted at 0 and removed, a changed one, a size
class, an area, a link) and `componentRowsAreCommandsAndFollowDeletesAndDuplicates`.

`placementsEditedHereMoveInTheWorld` needs the world: `WorldFixture` builds the same library and
level as `LevelLoaderTest` plus a torch, a spawn, a trigger, rules and a size class, with a
one-worker job system, a VFS, an asset manager, the headless mesh type and a `LevelLoader`, and
`settle()` pumps until the level is instantiated. The test moves the wall to $x = 10$ through a
`TransformCommand` and reads the renderable's world matrix from an extracted packet, undoes,
deletes the table (two renderables gone, the selection cleared), undoes (three back, under a new
root that maps to the same id), duplicates the wall (a fourth group at $x = 5$, the copy selected),
hides the wall through a flag command (three extracted: the hidden static instance is skipped),
undoes, and checks the wall kept its entity throughout.

### 31.6 `GizmoMathTest`

Every function against the hand-computed cases of §10 and §11: `closestAxisParamIsTheFootOfTheCommonPerpendicular`,
`rayPlanePointHitsThePlaneThroughTheCenter`, `signedAngleFollowsTheRightHandRule` (with the
consistency check that rotating $+X$ by the quaternion of the measured angle lands on $+Z$),
`snapRoundsToTheNearestStep`, `projectMapsTheCameraTargetToTheRegionCenter` (including a point
behind the camera refused and `metersPerPixel × H` = the frustum height),
`pointSegmentDistanceOnScreen`, and `quaternionsComposeAndConvertToEuler` (the hand-composed
product, four round trips to 0.01°, and the yaw convention).

### 31.7 `ViewportTest`

The tests of §30, each a drag "the way a user would": the mouse is put on a handle's
*projection*, moved to another world point's projection, and the objects must end where the math
says. `translateAlongAnAxisHandle`, `translateOnAPlaneHandleMovesTwoObjectsTogether` (the torch is
primary, the wall moves by the same delta, $y$ untouched), `rotateAroundTheYRing` (the quaternion
of $-90°$, the primary stays on the pivot, a $-40°$ turn snapped to $-45°$),
`scaleAlongAnAxisAndUniformly` (×2 along X, ×3 from the center cube at 3 and 9 pixels),
`escapeCancelsADragWithoutLeavingHistory` (the entry is gone and the release after a cancel does
nothing), `clicksPickMarkersAndBoxesSelectWhatProjectsInside` (a click on the torch's marker, on
empty ground, Shift-click on the spawn, and a box around the wall and the torch whose expected
contents the test computes by projecting every object itself), `cameraOrbitsPansAndZooms` (yaw
changes by `100 × orbitSpeed`, the wheel divides the distance by `zoomStep`, a pan moves the
target, F frames), and `idleFramesAllocateNothing` over the live `WorldFixture`: twenty idle frames
of `update` and `extract` under `Allocations.assertNoAllocation`, more than a hundred lines drawn
with an overlay start inside them, then twenty frames hovering the X handle and picking, also
at 0 bytes, with `hoveredHandle() == AXIS_X` at the end.

### 31.8 `PanelsTest` and `EditorWidgetsTest`

`dockLeavesTheViewportRegionAndPersistsItsLayout` (§24.3), `everyPanelDrawsOverALiveWorldWithoutAllocating`
(every panel placed in some zone, each made the visible tab in turn for three rounds so each
draws at least once, then ten frames of viewport update, declare and extract at 0 bytes),
`consoleCommandsDriveTheEditor` (`select`, `snap`, `goto`, `delete` and undo, an unknown command,
`stats`, `save` polled to completion and read back, `dump`),
`inspectorShowsComponentRowsFromTheSchema` (a schema with every field type parsed from JSON, the
`current` field declared, the *Add component* dropdown absent when the object has everything,
the *Remove* button clicked through the UI's records and the row gone with one sync, the dropdown
back, undo) and `inspectorFieldsPushMergedCommands` (a 40-pixel drag on the position's X field at
0.05 per pixel moves the wall by 2 as one merged "Move" entry). The widget tests
(`EditorWidgetsTest`, in the `ui` module) cover the menu bar, the splitter, the number field, the
vector and colour fields, the list box, the property row's label column, formatting and parsing,
and an idle frame of all of them at 0 bytes.

### 31.9 `RulesPanelTest`, `RulesCommandsTest`, `RulesSourceTest`

Over the demo's real content (`samples/iso-demo/assets/rules`, skipped if absent):
`theGridEditsThroughMergedCommands` drags the value of `dodge`'s first effect by 40 pixels at 0.1
per pixel (from 1 to 5, one merged entry, the source dirty, clean again after undo) and clicks
the `cannotAct` flag of `prone`; `addDuplicateDeleteAndSave` adds `newConditions`, duplicates it
as `newConditions2`, deletes, undoes three times, then saves into a temporary directory and finds
the id in the file; `thePanelDrawsWithoutAllocating` holds the monster grid at 0 bytes.
`RulesCommandsTest` checks the set command's merging and the removal of an absent key on undo,
and the list command's insert, remove (the same object comes back) and move.

## 32. Threads and performance

### 32.1 Threads

The editor runs on the **game thread** like any game system and never touches GL: it reads the
world's transform store (`World.pick`, the Entities panel), writes the level, and declares into
the UI, all on the thread that owns them. Two things leave it: a save's disk write (a virtual
thread, §21.5) and, in the standalone tool, the cooker (a platform daemon thread, §29.3). Both
hand results back through a thread-safe container polled from the game thread (an
`AtomicReference`, a `ConcurrentLinkedQueue`), never by calling into the editor from another
thread. The main thread draws the editor's lines and UI from the render packet it was handed,
one frame behind, as for everything else (chapter [01](01-architecture.md)).

### 32.2 What a frame costs

Measured on this container (2 vCPUs, Intel Xeon at 2.1 GHz, JDK 27) with the crypt open in a
headless `Ui` (no font loaded, so text is measured with the fallback metrics), in a scratch
program, not JMH. The method, so you can reproduce it: read the crypt with `LevelFiles.read`,
`new Editor()`, `open(level, null)`, `setShown(true)`, select placement 0, set a layout with
`dock().setLayout(...)`, then run frames of `ui.beginFrame(in)` (a `UiInput` of 1600 × 900),
`editor.declare(ui, w)`, read `ui.elementCount()`, `ui.endFrame(out)`; the timing is a
`System.nanoTime` loop over 2,000 such frames after 2,000 warm-up frames, and repeated runs vary
by about 15 %:

| What | Measured |
|---|---|
| Elements declared with the Outliner, the Inspector and the Console open, the first placement selected | 2,012 |
| Elements with the Assets, Settings and Console panels | 283 |
| `editor.declare` + the UI's layout and draw-list emission (Outliner, Inspector, Console) | about 0.25–0.33 ms per frame |
| `LevelData.toBytes` of the crypt (372 placements, 44.8 KB) | about 85–94 µs |
| `placementIndex(id)` | about 6.6–6.8 ns |

For scale, DESIGN §18 records the UI benchmark on the same class of machine: a 2,002-element list
with about 13,000 glyph quads at ≈ 1.0 ms, of which declaration and layout ≈ 0.35 ms, and
`everyPanelDrawsOverALiveWorldWithoutAllocating` is the proof that none of the editor's elements
allocate. The viewport's own per-frame work is a few dot products per handle and, for a click, a
sphere test per renderable (1,041 in the crypt, TASKS M14-03: microseconds); box selection projects every
object once per release. DESIGN §19 recorded "about 1,500 UI elements" for a 372-row Outliner
plus the Inspector when M14 shipped; the Inspector has grown a Components section and the
Console panel is counted here, which accounts for part of the difference.

### 32.3 What allocates, and why that is fine

Everything that allocates is a user action or rarer, and each is listed:

- a command (its arrays and name) per edit; a drag's command once at the press;
- a save: `toBytes` (a `ByteBuffer`, a string stream, the output array) and the virtual thread;
- a text field's `toString` of its buffer per keystroke, for the string command;
- the Assets panel's `vfs.paths` listing (per level and per *Refresh*), a scene load;
- a cook report's lines, a console line;
- the dock's `rebuild` when a panel moves, a layout string on save;
- `Path.getFileName` on open or save (cached, because the menu bar would otherwise allocate a
  `Path` every frame: that was the first allocation the idle-frame test caught; the M14 version of
  this chapter recorded it as 48 bytes a frame, a figure not re-measured here).

The tests that hold the line: `ViewportTest.idleFramesAllocateNothing` (idle, hover, pick),
`PanelsTest.everyPanelDrawsOverALiveWorldWithoutAllocating`, `RulesPanelTest.thePanelDrawsWithoutAllocating`,
`EditorWidgetsTest.idleFramesAllocateNothing` and `floatFormattingAndParsingRoundTrip`, and
`LevelLoaderTest`'s `extractLights`.

### 32.4 Cold paths, deliberately

Three cold paths that look like they should be hot and are not: an insert in the middle of a
table shifts the tail of seven arrays (a user action), `destroyGroup` scans the renderables and
the transform store (a delete), and a static placement moved by the gizmo rebuilds the
renderer's static set every frame of the drag (D49; the editor is the only thing that moves
static objects). None of them has shown up in a profile of the editor; if a 10,000-placement level
does, the fixes are known (a per-row free list, a per-group member list, a dynamic flag while
dragging) and were not worth their complexity for the first editor.

## 33. Pitfalls

Things that went wrong while building the editor, or that will go wrong for whoever extends it.

- **Rows shift, ids do not.** Anything stored across frames must be an id; a row index is valid
  until the next insert or remove. The loader is told *before* a row is removed for exactly that
  reason (`placementRemoving`), and `ObjectRow.remove` re-reads its index at the time of the
  removal.
- **Static means cached.** Moving, hiding, re-flagging or outlining a static placement must bump
  the world's `staticStructure`, or the renderer keeps drawing the old static set (D49). Every
  `World` method the editor uses does; a new one must too.
- **The redo tail and the saved mark.** Pushing after an undo discards the redo entries; if the
  saved state was among them, the level is dirty until the next save (`savedAt = -1`). Forgetting
  the oldest entry past capacity does the same to a mark at 0.
- **Merging across a save.** A merged step must never fold into an entry that *is* the saved
  state, or `isDirty()` lies: the stack checks `cursor != savedAt` before asking `mergeWith`.
- **Ids in rows of rows.** Any widget declared in a loop needs `pushId(i)`, and any widget that
  declares several children with fixed ids needs its own scope. The first vector field dragged a
  colour field's red channel with its X.
- **The pointer belongs to one of two owners.** The viewport ignores input while
  `Ui.wantsMouse()` is true (a press on a panel, a drag of a splitter); without that check a
  splitter drag also orbits the camera. The same for the keyboard: shortcuts are skipped while a
  text field has focus, or typing `w` into a name would switch the gizmo mode.
- **The ray's parameter is the same in local space only if the direction is not renormalized.**
  Normalize it and the `t` from `rayAabb` is in local units (§9.2).
- **Children before parents.** glTF node order is arbitrary; resolve matrices through the parent
  chain, memoized, never by index order (§6.3). A cooked scene *does* order parents first, which
  is why `instantiateNode` can do it in one pass and the cooker cannot.
- **Where two rings cross.** The point $\mathbf{c} + L\,\mathbf{x}$ lies on both the Y ring and the
  Z ring (§10.6); the hover test keeps the smaller pixel error, and at that exact point both errors
  are 0 up to float rounding. `ViewportTest.rotateAroundTheYRing` presses exactly there and expects
  `AXIS_Y`, which it gets because the Y ring's rounding error (0.0007 px) is smaller than the Z
  ring's (0.0026 px). A change to the camera or to the order of the floating-point operations could
  flip it; pressing a little off the crossing (at $\mathbf{c} + L(\cos 10°, 0, -\sin 10°)$, say)
  would make the test independent of rounding.
- **NDC is relative to the window, not the region.** The scene is drawn full-window beneath the
  panels; a ray computed from region-relative coordinates would be off by the dock's width.
- **The depth test that is not there.** The overlay machinery is written for a depth-tested grid
  and a depth-free gizmo, but the window has no depth buffer and the lines draw after the UI
  (§12.2). Until the renderer draws the lines with the scene's depth, the grid shows through
  walls and the gizmo over the panels; do not spend an afternoon looking for the bug in the
  editor.
- **A world-axis scale of a turned placement.** `writeScale` scales offsets from the pivot in
  world axes and a placement's *own* scale components per axis (`sx` for the X handle); for a
  placement turned 90° those are not the same axis, so a non-uniform scale along world X stretches
  the wall along its own X, which points along world $-Z$ (§9.2). Uniform scale (the center cube) and
  unrotated placements are right; the local-space mode of exercise 1 makes the two agree.
- **`Path.getFileName` allocates.** So does anything that builds a `String` per frame; the menu
  bar caches the file name for that reason, and the Outliner's counted headings are
  `StringBuilder`s. The allocation tests find these in seconds; run them after every change to a
  panel.
- **A level with scenes still loading is not an error.** `placementInserted` of a prefab whose
  scene has not arrived sets `ready = false` and lets `update` finish it; code that assumes
  `rootOf(id)` is never `NULL` after an add is wrong.

## 34. Things to run

- `./gradlew :editor:test :ui:test :asset:test --tests 'dev.jeddy.asset.format.LevelDataTest' :app:test --tests 'dev.jeddy.app.world.LevelLoaderTest' :cooker:test --tests 'dev.jeddy.cooker.level.LevelImporterTest'`
  runs everything this chapter walked through. `GizmoMathTest` is the place to check the
  derivations with your own numbers; `ViewportTest` drags through projected handles the way a
  user would; the `Allocations` tests fail within a second of a per-frame `new`.
- `od -A d -t x1 samples/iso-demo/assets/levels/crypt.jlevel | head -40` and decode the header
  and the first placement by hand against §3.4 (`Format-Hex` in PowerShell).
- `./gradlew :iso-demo:run`, then **F10**. Click a tile, press **W**, **E**, **R**, drag the
  handles; **Ctrl+Z**; right-drag to orbit; type `help` in the console. **Ctrl+S** writes
  `samples/iso-demo/assets/levels/crypt.jlevel` (as version 2, 44,832 bytes); the next
  `./gradlew :iso-demo:run` cooks it and the party walks on your new collision.
  `--editor` starts with it open; `--editor-command "select 150 151"` runs a console command at
  start, which is how the screenshot at the top of this chapter was taken.
- `./gradlew :editor-tool:run --args="--project samples/iso-demo/assets --level levels/crypt.jlevel --shader-include modules/render/src/main/resources/dev/jeddy/render/shaders"`
  opens the standalone editor; watch the console as the cooker finishes, then edit a glTF in the
  source tree (or re-run `kaykit.py`) and see it reload.
- In the Settings panel, change the level's sun direction and exposure: the view follows at once
  because the standalone tool renders with the level's settings. Change the grid step and watch
  the snapping follow.
- `--editor-command dump` (demo) or `dump` in the console writes `crypt.json` next to the level;
  open it and find the placement of §3.4.
- The smoke run of CI, locally:
  `./gradlew :editor-tool:run --args="--project samples/iso-demo/assets --level levels/crypt.jlevel --shader-include modules/render/src/main/resources/dev/jeddy/render/shaders --size 1280x720 --frames 30 --screenshot build/editor.png --command \"select 150 151\""`.

## Exercises

**1. Local-space gizmo.** Add a toggle that aligns the gizmo with the primary object's rotation
instead of the world axes (D94 left it out of the first editor). Check: a scale drag along the
local X of a turned wall changes the wall's `sx`, not its position, and the gizmo's X arrow points
along the wall.

<details><summary>Hint</summary>Rotate the three <code>AXES</code> by the primary's quaternion
at the start of each frame (<code>GizmoMath.rotate</code>) into a field, and use those vectors
everywhere the viewport reads <code>AXES[n]</code>; the math does not care which unit vectors it
gets.</details>
<details><summary>Solution</summary>

Add a field `private final float[][] axes = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};` and a flag
`localSpace` in `EditorSettings`. In `update`, after the primary's position is read and before
`gizmoScale`:

```java
        if (ctx.settings().localSpace && level.kindOf(sel.primary()) == LevelData.KIND_PLACEMENT
                && active == NONE) {
            int i = level.placementIndex(sel.primary());
            for (int k = 0; k < 4; k++) {
                qb[k] = level.placementFloat(i, 3 + k);
            }
            for (int n = 0; n < 3; n++) {
                GizmoMath.rotate(qb, 0, AXES[n], 0, axes[n], 0);
            }
        } else if (!ctx.settings().localSpace) {
            for (int n = 0; n < 3; n++) {
                System.arraycopy(AXES[n], 0, axes[n], 0, 3);
            }
        }
```

(`qb` is the spare quaternion scratch the class already has; the axes are frozen during a drag so
a rotation drag does not rotate its own rings.) Then replace every read of `AXES[...]` in
`hitTest`, `dotAxis`, `beginDrag`, `updateDrag`, `drawGizmo`, `arrowHead` and `square` with
`axes[...]`. `closestAxisParam`, `rayPlanePoint` and `signedAngle` take any unit vectors;
`writeTranslate` already moves along `a`; `writeRotate` builds the axis–angle quaternion from `a`.
The one place to think: `writeScale` multiplies a placement's `sx, sy, sz` by `(fx, fy, fz)` per
*handle*, which is right exactly when the handles are the object's local axes, so local mode makes
the scale gizmo honest (§33's pitfall). The offsets from the pivot, `(before − pivot) × f`, should
be scaled along the local axes too: project the offset onto each local axis, scale the
components, recompose. A test: place a wall with yaw 90°, drag the local X handle from $0.5L$ to
$L$, assert `sx == 2`, `sz == 1` and the position unchanged.
</details>

**2. Version 2 offsets.** The crypt on disk is version 1 (§3.4). Compute by hand every section
offset and the file size the editor writes when it saves it as version 2, then check against a
saved file's header (`od -A d -t u8 -j 48 -N 112 crypt.jlevel` prints the offsets as decimals).

<details><summary>Hint</summary>Start at 192 and add each section's <code>size × count</code>,
rounding up to a multiple of 16 after each; the component sections are empty but still aligned;
the string section is 6,678 bytes.</details>
<details><summary>Solution</summary>

| Section | Start | Bytes | End |
|---|---|---|---|
| prefabs | 192 | 15 × 16 = 240 | 432 |
| placements | 432 | 372 × 96 = 35,712 | 36,144 |
| lights | 36,144 | 15 × 80 = 1,200 | 37,344 |
| spawns | 37,344 | 10 × 48 = 480 | 37,824 |
| triggers | 37,824 | 0 | 37,824 |
| areas | 37,824 | 1 × 32 | 37,856 |
| links | 37,856 | 0 | 37,856 |
| rules | 37,856 | 4 × 24 = 96 | 37,952 |
| size classes | 37,952 | 1 × 32 | 37,984 |
| settings | 37,984 | 128 | 38,112 |
| polygons | 38,112 | 4 points × 8 = 32 | 38,144 |
| component rows | 38,144 | 0 | 38,144 |
| component values | 38,144 | 0 | 38,144 |
| strings | 38,144 | 6,678 | 44,822 → 44,832 |

Every end here happens to be a multiple of 16 already except the last: $44{,}822$ rounds up to
$44{,}832$ (the rule of L00 §2.5: $\lfloor (44822 + 15) / 16 \rfloor \cdot 16 = 2802 \cdot 16$). Re-saving
through `LevelData.read` then `toBytes` gives exactly these numbers and a 44,832-byte file, 32
bytes more than version 1: the header's extra 32 bytes, and nothing else moved relative to its
section.
</details>

**3. Intern the strings.** `toBytes` writes `levels/dungeon.jscene` fifteen times. Make
`putString` reuse an earlier copy of the same string, keep the output byte-identical across two
writes, and measure the saving on the crypt.

<details><summary>Hint</summary>A <code>HashMap&lt;String, Integer&gt;</code> from string to
offset, created per call of <code>toBytes</code> (cold), consulted before writing.</details>
<details><summary>Solution</summary>

Give `putString` a map parameter, created at the top of `toBytes` (`new HashMap<>()`):

```java
    private static void putString(
            ByteBuffer b, int at, ByteArrayOutputStream strings, Map<String, Integer> seen, String s) {
        if (s == null || s.isEmpty()) {
            b.putInt(at, 0);
            b.putInt(at + 4, 0);
            return;
        }
        byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
        Integer at0 = seen.get(s);
        if (at0 == null) {
            at0 = strings.size();
            strings.write(utf8, 0, utf8.length);
            seen.put(s, at0);
        }
        b.putInt(at, at0);
        b.putInt(at + 4, utf8.length);
    }
```

Two writes stay identical because interning is deterministic (first use wins, in row order). The
reader needs no change: two references to the same bytes are still two valid references. On the
crypt the string section drops from 6,678 bytes to the distinct strings (the fifteen scene paths
alone are $14 \times 21 = 294$ bytes saved; the placement names, which repeat the prefab node
names, far more). `LevelDataTest.roundTripsEveryKind` must still pass: it compares fields, not
offsets.
</details>

**4. Snap to the surface.** When a placement is dragged with **Shift** held, drop it onto what is
below: cast a ray down from its position with `World.pick` and move `y` to the hit.

<details><summary>Hint</summary>The pick ignores the dragged object only if you hide its group
first (<code>setGroupHidden</code>) and show it again after; <code>updateDrag</code> needs the
shift state, which <code>ViewportInput</code> already carries.</details>
<details><summary>Solution</summary>

Pass `in.shift` into `updateDrag` and, after `writeTranslate` in the translate branch:

```java
        if (shift && ctx.world() != null && ctx.loader() != null) {
            World world = ctx.world();
            LevelLoader loader = ctx.loader();
            float[] after = drag.after();
            for (int i = 0; i < drag.count(); i++) {           // hide the dragged groups
                int root = loader.rootOf(drag.id(i));
                if (root != Handles.NULL) {
                    world.setGroupHidden(root, true);
                }
            }
            for (int i = 0; i < drag.count(); i++) {
                int o = drag.offset(i);
                int hit = world.pick(after[o], after[o + 1] + 50, after[o + 2], 0, -1, 0, 100, outT);
                if (hit != Handles.NULL) {
                    after[o + 1] = after[o + 1] + 50 - outT[0];
                }
            }
            for (int i = 0; i < drag.count(); i++) {           // show them again
                int root = loader.rootOf(drag.id(i));
                if (root != Handles.NULL) {
                    world.setGroupHidden(root, false);
                }
            }
        }
```

The ray starts 50 m above the object's new position so that an object already below the
surface is lifted onto it; `outT[0]` is the distance down from there. Hiding goes through
`setGroupHidden` rather than a temporary flag change so that no `staticStructure` bump happens
(hiding is a per-group bit the picker consults). Cost: one `pick` per dragged object per frame,
a sphere test per renderable: for ten objects over the crypt, about 10,000 sphere tests, tens of
microseconds. A snapped $y$ is not re-snapped to the grid, which is what a designer wants: the
floor is where it is.
</details>

**5. Duplicate along the drag.** Make **Ctrl+D** followed by a drag place the copies where the
drag ends (Blender's Shift+D): the copies follow the mouse on the ground until a click.

<details><summary>Hint</summary>Push the duplicate, then begin a translate drag on the new
selection in the same frame; a plane drag on the ground plane needs no handle under the pointer.
The two commands stay separate entries, which is what users expect.</details>
<details><summary>Solution</summary>

Add to `Viewport` a `beginFreeDrag()` that starts a translate drag on the XZ plane without a
hit test:

```java
    public void beginFreeDrag() {
        if (ctx.selection().isEmpty()) {
            return;
        }
        mode = MODE_TRANSLATE;
        LevelObjects.position(ctx.level(), ctx.selection().primary(), center, 0);
        beginDrag(PLANE_Y);
        freeDrag = true; // ends on the next press, not on release
    }
```

`beginDrag(PLANE_Y)` records the ray's hit on the ground plane through the pivot as `startPoint`;
`updateDrag` then moves every selected object by the difference of plane hits, as for the plane
square. In `update`, while `freeDrag` is set, keep calling `updateDrag` whether or not the button
is down, and end the drag on `leftPressed` (consume the press so it does not also pick). In
`Editor.duplicateSelection`, after selecting the copies, call `viewport.beginFreeDrag()`. The
duplicate is pushed with `merge = false` and the drag pushes its own `TransformCommand` on the
first moving frame: two entries, "Duplicate" and "Move", so one Ctrl+Z puts the copies back at
the original's position and a second removes them. Escape during the free drag cancels the move
but keeps the duplicate, which matches Blender.
</details>

**6. Marquee with depth.** Box selection takes anything whose center projects inside the
rectangle, including objects behind walls. Drop the hidden ones, and measure the cost on the
crypt's 372 placements.

<details><summary>Hint</summary>A <code>World.pick</code> from the eye toward each candidate's
position, with <code>maxT</code> just short of the distance: a hit that belongs to another group
means something is in front. The occlusion grid of chapter 11 is the two-dimensional
alternative.</details>
<details><summary>Solution</summary>

In `boxTest`, after the projection test passes and before `boxHits.add(id)`:

```java
        if (ctx.world() == null || ctx.loader() == null) {
            boxHits.add(id); // no world attached (the command tests): keep the projection's answer
            return;
        }
        IsoCamera cam = ctx.camera();
        float dx = tmp[0] - cam.eye().x, dy = tmp[1] - cam.eye().y, dz = tmp[2] - cam.eye().z;
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float inv = 1 / dist;
        int hit = ctx.world().pick(cam.eye().x, cam.eye().y, cam.eye().z,
                dx * inv, dy * inv, dz * inv, dist - 0.05f, outT);
        if (hit != Handles.NULL && ctx.loader().placementOf(ctx.world().groupOf(hit)) != id) {
            return; // something else is in front of its center
        }
```

(For a placement, its own renderables may be hit first; comparing the hit's placement with `id`
keeps it.) Cost: one `pick` per candidate, each a sphere test per renderable plus a slab test for
the few spheres the ray crosses: $372 \times 1{,}041 \approx 387{,}000$ sphere tests, each a few
nanoseconds, so on the order of a millisecond per release, once per box; acceptable for a user
action, and `boxTest` is already cold. A center-based test still misses an object half visible
behind a pillar; testing a few points of its bounding sphere is the next refinement. The
occlusion grid (`OcclusionGrid.blocked`, chapter [11](11-physics-navigation-crowds.md)) answers in
2D at eye height and would wrongly hide an object on a balcony; the pick is the honest test.
</details>

**7. An area volume tool.** Draw a polygon on the ground with clicks and push a `TableCommand`
adding a navigation area with it; draw existing areas as prisms with overlay lines.

<details><summary>Hint</summary>The ground point of a click is <code>rayPlanePoint</code> with
the normal $+Y$ through the camera target; a <code>TableCommand.Row</code> for <code>AREAS</code>
is <code>ints[0]</code> = area, <code>floats</code> = yMin, yMax, then x, z pairs.</details>
<details><summary>Solution</summary>

A new viewport mode, `MODE_AREA`, with a growing `float[] points` (an `IntList`-like float
buffer) and a count. On a click in that mode:

```java
        if (GizmoMath.rayPlanePoint(ray[0], ray[1], ray[2], ray[3], ray[4], ray[5],
                0, ctx.camera().target.y, 0, 0, 1, 0, tmp, 0)) {
            points[count * 2] = tmp[0];
            points[count * 2 + 1] = tmp[2];
            count++;
        }
```

On Enter with at least three points, build the row and push:

```java
        TableCommand.Row r = new TableCommand.Row();
        r.ints[0] = ctx.settings().areaId;                // a setting for the area number
        r.floats = new float[2 + count * 2];
        r.floats[0] = ctx.camera().target.y - 1;
        r.floats[1] = ctx.camera().target.y + 1;
        System.arraycopy(points, 0, r.floats, 2, count * 2);
        ctx.undo().push(TableCommand.add(TableCommand.Table.AREAS, ctx.level().areaCount(), r));
        count = 0;
```

Drawing: in `extract`, for every area, the polygon at `yMin` and at `yMax` (an edge from point
$k$ to $k + 1$, wrapping) and a vertical line per point, with `addDebugLine`, before the overlay
mark so the prisms are depth-tested like the markers; the polygon under construction in
`COLOR_HOT`. The cooker reads the areas on the next save and the navmesh's triangles inside the
prism get the area number (chapter 11's `AreaVolume`).
</details>

**8. Undo the world.** The Entities panel edits an entity's local position live and not through
a command. Write a `WorldTransformCommand` that captures `TransformStore.position`, `rotation`
and `scale` before and after, and decide what should happen when the entity has died by the time
of the undo.

<details><summary>Hint</summary>The command needs the entity handle and twenty floats;
<code>EntityPool.isAlive(handle)</code> (Lesson 3's generation check) tells a dead entity from a
live one.</details>
<details><summary>Solution</summary>

```java
public final class WorldTransformCommand implements Command {
    private final int entity;
    private final float[] before = new float[10];
    private final float[] after = new float[10];

    public WorldTransformCommand(World world, int entity) {
        this.entity = entity;
        read(world, before);
        System.arraycopy(before, 0, after, 0, 10);
    }

    public void setAfter(World world) { read(world, after); }

    private void read(World world, float[] out) {
        TransformStore t = world.transforms();
        for (int k = 0; k < 3; k++) out[k] = t.position(entity, k);
        for (int k = 0; k < 4; k++) out[3 + k] = t.rotation(entity, k);
        for (int k = 0; k < 3; k++) out[7 + k] = t.scale(entity, k);
    }

    private void write(EditorContext ctx, float[] v) {
        World world = ctx.world();
        if (world == null || !world.entities().isAlive(entity)) {
            return; // the entity died: nothing to restore
        }
        TransformStore t = world.transforms();
        t.setPosition(entity, v[0], v[1], v[2]);
        t.setRotation(entity, v[3], v[4], v[5], v[6]);
        t.setScale(entity, v[7], v[8], v[9]);
    }

    @Override public void apply(EditorContext ctx) { write(ctx, after); }
    @Override public void revert(EditorContext ctx) { write(ctx, before); }
    @Override public String name() { return "Move entity"; }
    @Override public boolean mergeWith(Command next) {
        if (next instanceof WorldTransformCommand w && w.entity == entity) {
            System.arraycopy(w.after, 0, after, 0, 10);
            return true;
        }
        return false;
    }
}
```

`TransformStore` has `position`, `rotation` and `scale` getters by entity and component, and the
three setters. The panel pushes it with `merge` while the vector field changes. The dead-entity decision: a handle's generation makes `isAlive` exact
(Lesson 3), so a dead entity is detected and the command does nothing, silently. The alternative,
refusing the undo or throwing, would block the history over state the *game* owns and the editor
only borrowed; the level's own commands never face this because the loader recreates a
placement's entity on reinsertion. Note the command in the console when it is skipped so the
user knows why nothing moved.
</details>

**9. Prefab previews.** Show the scene chosen in the Assets panel as a picture in the panel.

<details><summary>Hint</summary>Two ways: instantiate it in a corner of the world and look with
the main camera, or a second world rendered to a texture, which needs the render-target plumbing
D88 rejected for the first editor; chapter 08's frame graph has the texture resources, chapter
13's draw list has an image command.</details>
<details><summary>Solution</summary>

The cheap version: `world.instantiate(scene, x, y, z, 0, 0)` at a corner far from the level
(say $(-1000, 0, -1000)$), remember the root, destroy it when the choice changes, and give the
Assets panel a *Look* button that sets the editor camera's target there with `frameSelection`'s
distance rule; `Viewport.frameSelection` already knows how to frame a radius. It is a preview in
the viewport, not in the panel, and costs nothing new.

The real version, in order: (1) a second `World` holding only the preview instance; (2) a
`SceneRenderer` pass that draws it into a frame-graph colour texture (chapter
[08](08-effects-and-post.md): declare a texture resource and a pass that writes it, with its own
camera looking at the instance's bounding sphere); (3) the UI draw list's image command (chapter
[13](13-ui.md)) referencing that texture by handle, declared by the panel as an `image` box of
200 × 200 pixels; (4) the packet carries the preview's camera and instance list beside the main
scene's. The cost D88 named is real: a full pass per frame for one small image, and a copy or a
second render target per preview; render it only when the choice changed and keep the texture.
</details>

**10. The closest point by hand.** With the axis $\mathbf{c} + t\,\mathbf{a}$, $\mathbf{c} = (2, 0,
0)$, $\mathbf{a} = (0, 0, 1)$, and the ray $\mathbf{o} = (5, 4, 3)$, $\mathbf{d} = (-0.6, -0.8, 0)$,
compute $t$ with §10.1 and check it against `GizmoMath.closestAxisParam` in `jshell`.

<details><summary>Hint</summary>$\mathbf{a}\cdot\mathbf{d} = 0$ here, which makes the formula
collapse.</details>
<details><summary>Solution</summary>

$\mathbf{w} = \mathbf{o} - \mathbf{c} = (3, 4, 3)$; $b = \mathbf{a}\cdot\mathbf{d} = 0$, so $1 - b^2 =
1$; $\mathbf{w}\cdot\mathbf{a} = 3$; $\mathbf{w}\cdot\mathbf{d} = -1.8 - 3.2 + 0 = -5$; $t = (3 - 0
\cdot (-5)) / 1 = 3$. The ray moves in the XY plane at $z = 3$, the axis is the line $x = 2, y = 0$
along $z$: the closest point on the axis is at $z = 3$, so $t = 3$. In `jshell` (with the editor
module on the class path): `GizmoMath.closestAxisParam(5, 4, 3, -0.6f, -0.8f, 0, 2, 0, 0, 0, 0, 1,
-99)` prints `3.0`.
</details>

**11. Descending removal.** A table has placements at indices 0 to 4 with ids 11 to 15. Delete
ids 12 and 14 (indices 1 and 3) through `DeleteObjectsCommand`: write the table after each
removal and after each reinsertion of the undo, then do it in the wrong order and show what
breaks.

<details><summary>Hint</summary>The command removes in descending index order and reinserts in
ascending order (§20.3).</details>
<details><summary>Solution</summary>

Captured rows: `(index 1, id 12)` and `(index 3, id 14)`, sorted ascending. `apply` walks
backwards: remove index 3 → `[11, 12, 13, 15]`; remove index 1 → `[11, 13, 15]`. `revert` walks
forwards: insert id 12 at 1 → `[11, 12, 13, 15]`; insert id 14 at 3 → `[11, 12, 13, 14, 15]`. Every
id is back at its index.

Wrong order, removing ascending: `ObjectRow.remove` finds its row by *id*, so the right objects
go either way, but it records the index it found *at removal time*. Remove 12 (index 1) → `[11,
13, 14, 15]`; now 14 is at index 2, recorded as 2 → `[11, 13, 15]`. Reinserting ascending by the
captured order: insert 12 at 1 → `[11, 12, 13, 15]`; insert 14 at its recorded 2 → `[11, 12, 14,
13, 15]`: 13 and 14 are swapped. Descending removal is what keeps every recorded index equal to
the original one, and ascending reinsertion what makes those indices land. The level would be the
same set of objects either way, but the Outliner's order and, for rules, the matching order would
have changed.
</details>

**12. The dirty flag.** Starting from a clean stack, predict `isDirty()` after each step: push
A, push B, save, undo, push C, undo, undo, redo. Then check with `UndoStackTest`'s `Add` command.

<details><summary>Hint</summary>Track <code>cursor</code> and <code>savedAt</code>; a push after
an undo discards the redo tail.</details>
<details><summary>Solution</summary>

| Step | commands | cursor | savedAt | dirty? |
|---|---|---|---|---|
| start | [] | 0 | 0 | no |
| push A | [A] | 1 | 0 | yes |
| push B | [A, B] | 2 | 0 | yes |
| save | [A, B] | 2 | 2 | no |
| undo | [A, B] | 1 | 2 | yes |
| push C | [A, C] | 2 | −1 | yes (B, the saved state, is gone) |
| undo | [A, C] | 1 | −1 | yes |
| undo | [A, C] | 0 | −1 | yes |
| redo | [A, C] | 1 | −1 | yes |

Once `savedAt` is −1, nothing but a save makes the level clean again, which is right: the state on
disk (A then B) is not in the history any more. `tracksTheSavedState` checks the same sequence's
shape.
</details>

**13. Euler angles of a quarter turn.** Compute `toEuler` by hand for $q = (0, 0.7071, 0,
0.7071)$ (the crypt's turned wall) and for $q = (0.7071, 0, 0, 0.7071)$, and say which one is in
gimbal lock.

<details><summary>Hint</summary>§11.4's three formulas; watch $\sin\theta$ for the second
one.</details>
<details><summary>Solution</summary>

First: $\sin\theta = 2(wx - yz) = 0$, pitch 0; yaw $= \operatorname{atan2}(2(xz + wy),\ 1 - 2(x^2 +
y^2)) = \operatorname{atan2}(2 \cdot 0.7071 \cdot 0.7071,\ 1 - 2 \cdot 0.5) = \operatorname{atan2}(1, 0)
= 90°$; roll $= \operatorname{atan2}(0, 1 - 0) = 0$. A 90° yaw, as the quaternion says.

Second: $\sin\theta = 2(0.7071 \cdot 0.7071 - 0) = 1$, pitch $90°$: gimbal lock. The normal
formulas would give yaw $= \operatorname{atan2}(0, 1 - 2 \cdot 0.5) = \operatorname{atan2}(0, 0)$,
undefined; the code's lock branch gives yaw $= \operatorname{atan2}(2(wy - xz),\ 1 - 2(y^2 + z^2)) =
\operatorname{atan2}(0, 1) = 0$ and roll 0: a pure 90° pitch, which is what the quaternion is.
`GizmoMath.toEuler` returns $(0, \pi/2, 0)$ for it, and `fromEuler(0, π/2, 0)` gives the
quaternion back.
</details>

## Glossary

- **Box selection (marquee):** selecting everything whose position projects inside a dragged
  rectangle (§9.3).
- **Command:** an object with `apply` and `revert` that changes the level in opposite directions,
  carrying exactly the data both need (§7, §19).
- **Composition (level):** a level as a list of placements of prefabs plus lights, spawns,
  triggers and tables, not a mesh (§2).
- **Dirty:** the level differs from what was last saved or opened; the cursor is not at the saved
  mark (§7.3).
- **Dock, zone:** the container of panels; one of its three edges, left, right or bottom (§13.1,
  §24).
- **Euler angles (yaw, pitch, roll):** three angles about $Y$, $X$ and $Z$ that compose to a
  rotation; the Inspector's view of a quaternion (§11.4).
- **Gimbal lock:** the pitch of $\pm 90°$ at which yaw and roll become one degree of freedom
  (§11.4).
- **Gizmo, handle:** the set of draggable shapes at the primary object; one of them (an axis, a
  plane square, a ring, the center) (§10).
- **Group (world):** the root entity of an instantiated placement, shared by all its renderables
  (§5.1).
- **Id (object):** the stable `u32` that names a placement, light, spawn or trigger across row
  moves (§4.1).
- **Id scope:** a value combined into every widget id declared until `popId`, so fixed ids never
  collide between rows or panels (§13.3).
- **Immediate mode (panel):** a panel that declares its widgets from the level every frame and
  keeps only scratch (§13.2).
- **Layer:** a placement's short string that the collision rules match (§2.3, §6.2).
- **Lever:** the vector from the pivot to the ray's hit on a ring's plane, at the press and now
  (§10.3).
- **Merge:** folding a new command into the previous history entry when both are steps of one edit
  (§7.2).
- **metersPerPixel:** how many metres one pixel covers at a depth (§8.5).
- **Overlay (lines):** debug lines after `beginDebugOverlay`, meant to be drawn without the depth
  test (§12).
- **Pivot:** the primary object's position at the start of a drag, about which a selection turns
  or scales (§11.1).
- **Placement:** a row naming a prefab and a transform (§2.1).
- **Prefab:** a named root node of a cooked scene, or a whole scene (§2.2).
- **Primary:** the last selected object; the gizmo's seat and the Inspector's subject (§21.2).
- **Project (tool):** an asset source tree and its cooked output (§29.2).
- **Ray (mouse):** the world line through a pixel, origin on the near plane and unit direction
  (§8).
- **Saved mark:** the cursor at the last save, or −1 when that state is unreachable (§7.3).
- **Slab test:** the ray–box test that intersects the ray's intervals in each axis (§9.2,
  chapter 11).
- **Snapping:** rounding a drag's delta, angle or factor to a step (§10.4).
- **Unprojection:** mapping a pixel and a depth back to a world point through the inverse
  view-projection matrix (§8.3).
- **Version (counter):** an `int` bumped by every mutation, compared to know whether to re-read
  (§4.4).
- **Vertex pulling:** a vertex shader with no attributes that reads its vertex from a storage
  buffer by `gl_VertexID` (§12.2).

*Code this chapter builds or quotes: `asset.format.LevelData`, `app.world.World` (`instantiateNode`,
`destroyGroup`, `setGroupTransform`, `groupOf`, `pick`), `app.world.LevelLoader`,
`cooker.level.LevelImporter`, `cooker.physics.PhysicsBake.collectSubtree`, `editor.command.*`,
`editor.Editor`, `EditorContext`, `UndoStack`, `Selection`, `LevelObjects`, `LevelFiles`, `Console`,
`EditorSettings`, `ViewportInput`, `Text`, `GizmoMath`, `Viewport`, `editor.panel.*`,
`editor.rules.*`, `ui.Widgets` (`numberField`, `appendFloat`, `parseFloat`, `vectorField`,
`colorField`, `listBox`, `beginProperty`, `splitter`), `render.debug.DebugRenderer`,
`samples.iso.DemoGame`, `editortool.*`. Decisions: D86–D95, D97, D109, D110, D49, D23, D102, D118 in
[DESIGN §17](../DESIGN.md#17-decision-log); the design in [DESIGN §19](../DESIGN.md#19-editor-levels-commands-gizmos-and-panels-m14).
Related: [04 Cameras](04-cameras-and-projection.md) (the projection, picking),
[11 Physics](11-physics-navigation-crowds.md) (the ray tests, the bakes), [10 Assets](10-assets.md)
(the cooker, hot reload), [13 UI](13-ui.md) (widgets, ids, hit testing), [15 Rules](15-rules.md)
(the rules content the Rules panel edits), [22 Components](22-components.md) (the component rows
and the scene bindings); [Lesson 3](lessons/03-data-layout.md) (struct of arrays, handles),
[Lesson 4](lessons/04-binary-formats.md) (the byte-level conventions).*

---

[← 13 The user interface](13-ui.md) · [Index](README.md) · [15 Rules →](15-rules.md)
