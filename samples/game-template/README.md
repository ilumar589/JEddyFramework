# Game template

A minimal game project on Jeddy: a cooked glTF level under an isometric camera, the
`dev.jeddy.game` Gradle plugin (asset cooking, run, packaging) and a composite build of the
framework checkout two levels up. Copy it to start a game; [docs/getting-started.md](../../docs/getting-started.md)
walks through it.

```
../../gradlew -p . run          # cook, pack and run
../../gradlew -p . distImage    # build/dist/my-game: runtime, launchers, paks
```

The level (`assets/levels/yard.gltf`) is the procedural yard of the framework's tests.
