# jeddy-gl

Module `dev.jeddy.gl`. OpenGL 4.6 core bindings generated at build time from `bindings/gl/gl.xml`, the function loader and capability detection.

## Key types

- `GL46` (in `dev.jeddy.gl.ffi`, exported to the renderer and samples): static methods over `static final` handles
- `GLLoader`, `GLCapabilities`, `GLDebug`, `GLShaders`

## Threading

The GL thread only (the SDL main thread).

## More

- Design: [DESIGN.md](../../docs/DESIGN.md) §9.1
- API: `./gradlew :gl:javadoc`, or the Javadoc site (`./gradlew :site:javadocSite`)
- Tests: `./gradlew :gl:test` (GPU tests: `-Pjeddy.gpuTests=true`)
