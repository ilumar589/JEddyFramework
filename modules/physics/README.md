# jeddy-physics

Module `dev.jeddy.physics`. Pure-Java gameplay physics for CRPGs: a static collision BVH, a broadphase grid, spell-shaped queries and picking, navmesh baking (Recast-style), pathfinding and corridors, crowds with ORCA avoidance and formations, doors and temporary obstacles, turn-based movement ranges, fog-of-war visibility and triggers.

## Key types

- `Bvh`, `BvhBuilder`, `CollisionWorld`, `QueryContext`, `Bodies`, `UniformGrid`
- `NavMeshBaker`, `TileCache`, `NavMesh`, `NavMeshQuery`, `PathCorridor`, `NavPathBatch`, `TurnBasedNav`
- `Crowd`, `AgentParams`, `Formation`, `FormationMove`
- `OcclusionGrid`, `Visibility`, `Triggers`, `DebugSink`

## Threading

The game thread (the simulation tick); queries are safe from several threads with one context each.

## More

- Design: [DESIGN.md](../../docs/DESIGN.md) §11
- API: `./gradlew :physics:javadoc`, or the Javadoc site (`./gradlew :site:javadocSite`)
- Tests: `./gradlew :physics:test`
