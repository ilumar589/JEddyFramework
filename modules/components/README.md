# jeddy-components

Module `dev.jeddy.components`. Components and behaviours (DESIGN §22): a component is a public `record` with `@Component`, a behaviour a `public static` method in a `@Behaviour` class with a phase annotation, and `@Components(name = "...")` on a package names the game's registry. The annotation processor in `tools/component-processor` (on the annotation processor path through `dev.jeddy.game`) generates, per record, `<Name>Store` (a sparse set with one primitive array per column), `<Name>View` (a flyweight over one row) and `<Name>Mut` (with setters), and the registry with a loop per behaviour, the `@Parallel` ones as job-graph kernels with edges from the declared reads and writes, the phase entry points and `META-INF/jeddy/components.json`. This module is what the generated code is built on.

## Key types

- Annotations: `Component`, `Default`, `Range`, `Angle`, `Color`, `Doc`, `EntityRef`, `AssetRef`; `Behaviour`, `Components`; the phases `OnSpawn`, `OnDespawn`, `OnFixedUpdate(stage)`, `OnUpdate(stage)`, `OnTrigger(kind)`, `OnTurn`, `OnEvent(kind)`; `Optional`, `Without`, `Parallel(grain)`, `ThreadSafe`
- `Store`: the base of every generated store: membership and rows (`add`, `remove`, `has`, `indexOf`, `entityAt`, `size`, `capacity`), the column hooks the subclass implements, the generic column API by name (`columnIndex`, `getInt`/`setFloat`/..., `enumOrdinal`, `assetKind`) for the level loader, the editor and the snapshots, and `write`/`read`/`skip` (D111)
- `View`: the base of the flyweights (`present`, `row`, `none`)
- `Levels`: applies a level's component rows to the stores (`Levels.Binding` resolves object ids and asset paths)
- `SnapshotWriter`, `SnapshotReader`: the little-endian streams the stores write themselves into

The generated registry's entry points: `fixedUpdate(dt)` / `fixedUpdateStage(n, dt)`, `update(dt, alpha)` / `updateStage`, `spawn`, `despawn`, `turn`, `trigger` and `dispatch` (physics trigger events), `signal(kind, entity, a, b)` (the bridge from the engine's event rings), `load(level, binding)` / `loadObject`, `write`/`read`, and an accessor per store. [docs/learn/22-components.md](../../docs/learn/22-components.md) explains every generated line; [lesson L03](../../docs/learn/lessons/03-data-layout.md) the data layout behind it.

## Threading

Stores change membership on the game thread; `@Parallel` behaviours run on job threads over disjoint rows between structural changes, with their own views per thread. A tick of the generated registry allocates nothing (`GeneratedGameTest.aTickAllocatesNothingInlineAndOnTheJobSystem`); growth past the registry's `CAPACITY`, building records for tools, level loading and snapshots are the cold paths.

## More

- Design: [DESIGN.md](../../docs/DESIGN.md) §22, D105–D111
- API: `./gradlew :components:javadoc`, or the Javadoc site (`./gradlew :site:javadocSite`)
- Tests: `./gradlew :components:test` (the sample game under `src/test/java/.../sample` is compiled with the processor) and `./gradlew :component-processor:test` (the diagnostics)
- Benchmark: `./gradlew :bench:jmh -Pjmh.includes=ComponentsBench`
