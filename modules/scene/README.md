# jeddy-scene

Module `dev.jeddy.scene`. Entity handles, sparse-set component stores and the struct-of-arrays transform hierarchy.

## Key types

- `EntityPool`, `ComponentStore`, `IntColumn`, `FloatColumn`, `LongColumn`
- `TransformStore`: local TRS, world 3×4 matrices, dirty propagation on the job system

## Threading

The game thread.

## More

- Design: [DESIGN.md](../../docs/DESIGN.md) §13.1
- API: `./gradlew :scene:javadoc`, or the Javadoc site (`./gradlew :site:javadocSite`)
- Tests: `./gradlew :scene:test`
