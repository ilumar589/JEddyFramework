# jeddy-render

Module `dev.jeddy.render`. The OpenGL 4.6 renderer: a frame graph over render packets, multi-draw-indirect submission, PBR with clustered lights, sun and local shadows, GTAO, decals, outlines, GPU particles, fog of war, post-processing and debug draw.

## Key types

- `SceneRenderer`, `RenderPacket`, `RenderPackets`: the game thread fills a packet, the main thread draws it
- `FrameGraph`, `GpuTimers`, `GpuDevice`, `StateCache`, `UploadRing`, `FrameRing`
- `MeshRegistry`, `MeshAssets`, `MaterialAssets`, `MaterialSystem`, `TextureLoader`
- `IsoCameraController`, `DebugDraw`, `Overlay`, `ProfilerOverlay`
- `EngineShaders`, `ProgramCache`, `ProgramBinaryCache`

## Threading

The GL thread, except `RenderPacket`, which the game thread fills before handing it over.

## More

- Design: [DESIGN.md](../../docs/DESIGN.md) §9
- API: `./gradlew :render:javadoc`, or the Javadoc site (`./gradlew :site:javadocSite`)
- Tests: `./gradlew :render:test` (GPU tests: `-Pjeddy.gpuTests=true`)
