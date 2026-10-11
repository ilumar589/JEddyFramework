# jeddy-editor

Module `dev.jeddy.editor`. The level editor as a library: a game creates an `Editor`, attaches its `World` and `LevelLoader`, opens the `LevelData` it is showing and, while the editor is shown (the iso demo: F10), calls `update`, `declare` and `extract` from its own callbacks and renders with the editor's camera. Every edit is a `Command` with explicit inverse data on an `UndoStack`; the loader mirrors changes into the running world at once; saving writes the `.jlevel` the runtime loads. `tools/editor` runs the same library as a standalone application with the cooker watching the project.

## Key types

- `Editor`: the controller: `attach`, `attachAssets`, `open`/`close`, `setShown`, `update`/`declare`/`extract`, `camera`, `save`/`saveAs`, `undo`/`redo`, `deleteSelection`, `duplicateSelection`, `selectAll`, `runCommand`, `saveSettings`/`loadSettings`
- `EditorContext`: what commands and panels see: `level`, `loader`, `world`, `selection`, `undo`, `settings`, `console`, `camera`, `ui`/`widgets`, `vfs`/`scenes`, and the `syncTransform`/`syncFlags`/`syncPrefab` calls that reach the world
- `UndoStack`, `Selection`, `LevelObjects` (uniform access to placements, lights, spawns and triggers by id), `Console`, `EditorSettings`, `LevelFiles` (read; save on a virtual thread; JSON dump)
- `command`: `Command`, `TransformCommand`, `AddObjectsCommand` (incl. `duplicate`), `DeleteObjectsCommand`, `ObjectRow`, `SetStringCommand`, `SetIntCommand` (incl. `flag`), `SetFloatCommand`, `SetSettingsCommand`, `TableCommand` (rules, size classes, areas, links)
- `Viewport`: the editor camera (orbit, pan, zoom, frame), picking (`World.pick` and markers), box selection, the translate/rotate/scale gizmos and their drags; `GizmoMath` holds the geometry (closest ray–axis point, ray–plane hits, signed angles, snapping, projection, quaternion helpers, Euler conversion); `ViewportInput` is one frame of pointer input
- `panel`: `Dock` (left/right/bottom zones, tabs, splitters, the free viewport region, a layout string), `Panel`, and `OutlinerPanel`, `InspectorPanel`, `EntitiesPanel`, `AssetsPanel`, `ConsolePanel`, `SettingsPanel`, `HistoryPanel`

The level format is [docs/formats/jlevel.md](../../docs/formats/jlevel.md); [docs/learn/14-editor.md](../../docs/learn/14-editor.md) derives the picking and gizmo math and explains the commands, the dock and the hosts.

## Threading

Everything runs on the game thread and never touches GL. The idle frame (viewport hover tests, panels, overlay lines) allocates nothing; commands, saves, cook reports and the asset listing allocate and are cold.

## More

- Design: [DESIGN.md](../../docs/DESIGN.md) §19, D86–D95
- API: `./gradlew :editor:javadoc`, or the Javadoc site (`./gradlew :site:javadocSite`)
- Tests: `./gradlew :editor:test`; the standalone tool: `./gradlew :editor-tool:run --args="--project samples/iso-demo/assets --level levels/crypt.jlevel"`
