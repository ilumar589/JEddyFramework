# jeddy-app

Module `dev.jeddy.app`. Ties the modules together: the two-thread application loop and the world integration that turns entities into render packets, crowd agents into transforms and the camera into the audio listener.

## Key types

- `JeddyApp`, `AppConfig`, `Game`, `FrameRenderer`, `AppContext`, `MainContext`
- `World`, `WorldRenderer`, `WorldAssets`, `AgentSync` (`dev.jeddy.app.world`)
- `AudioSync`, `AmbienceTriggers`, `FogOfWarFeed`

A game is a `Game` plus a renderer (normally `WorldRenderer`) passed to `JeddyApp.run(AppConfig, Game)`; [docs/getting-started.md](../../docs/getting-started.md) builds one from scratch. `AppConfig.quitAfterFrames` (or `-Djeddy.app.quitAfterFrames=N`) stops after N frames for smoke tests.

## Threading

`Game` callbacks on the game thread, `FrameRenderer` callbacks on the main thread.

## More

- Design: [DESIGN.md](../../docs/DESIGN.md) §5, §13.2; guide: [getting-started.md](../../docs/getting-started.md)
- API: `./gradlew :app:javadoc`, or the Javadoc site (`./gradlew :site:javadocSite`)
- Tests: `./gradlew :app:test` (GPU tests: `-Pjeddy.gpuTests=true`)
