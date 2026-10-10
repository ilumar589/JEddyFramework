# jeddy-asset

Module `dev.jeddy.asset`. The runtime side of the asset pipeline: a virtual file system over loose files and `.jpak` archives, asynchronous loading with budgeted GPU uploads, cooked format readers and hot reload.

## Key types

- `Vfs`, `Pak`, `PakWriter`, `AssetPaths`
- `AssetManager`, `AssetType`, `AssetLoader`, `AssetState`, `DecodeContext`
- `SceneData`, `MeshData`, `MaterialData`, `Ktx2`: cooked formats (docs/formats)
- `ShaderPreprocessor`, `ShaderSources`, `AssetReloader`

A game finds and mounts its content with `Vfs.contentDirectory(fallback)` (`-Djeddy.assets.dir`, else `paks/` under `-Djeddy.app.dir`, which the packaged launchers and the `dev.jeddy.game` plugin's `run` task set) and `vfs.mountContent(dir)` (every `.jpak` in name order, or a loose cooked tree).

## Threading

The game thread owns the manager; decoding runs on job workers; uploads on the main thread.

## More

- Design: [DESIGN.md](../../docs/DESIGN.md) §10
- API: `./gradlew :asset:javadoc`, or the Javadoc site (`./gradlew :site:javadocSite`)
- Tests: `./gradlew :asset:test`
