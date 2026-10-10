# jeddy-window

Module `dev.jeddy.window`. SDL video, windows, displays and DPI, the GL context, swap and frame pacing, cursors, and the main-thread command queue.

## Key types

- `Window`, `WindowConfig`, `VSync`, `WindowMode`, `DisplayInfo`
- `MainThreadQueue`, `WindowCommands`: game-thread requests executed on the main thread
- `FrameLimiter`, `Cursors`, `Platform`

## Threading

Main thread only, except `MainThreadQueue` producers.

## More

- Design: [DESIGN.md](../../docs/DESIGN.md) §5, §12.1
- API: `./gradlew :window:javadoc`, or the Javadoc site (`./gradlew :site:javadocSite`)
- Tests: `./gradlew :window:test` (GPU tests: `-Pjeddy.gpuTests=true`)
