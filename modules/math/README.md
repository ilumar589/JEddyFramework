# jeddy-math

Module `dev.jeddy.math`. Value types and bulk kernels for 3D math: right-handed, Y-up, column-major, radians, `float`, reversed-Z, affine 3×4.

## Key types

- `Vec3f`, `Quatf`, `Mat4f`, `Aabb3f`, `Spheref`, `Planef`, `Ray3f`: immutable records, Valhalla-ready
- `MVec3f`, `MQuatf`, `MMat4f`: mutable scratch versions
- `AffineOps`, `Mat4Ops`, `QuatOps`, `Vec3Ops`, `CullOps`: SoA kernels over `float[]`
- `Intersect`, `Poly2`, `Noise`, `Rng`, `Ease`, `Packing`
- `IsoCamera`: the isometric camera (orbit, zoom, screen rays)

## Threading

Stateless kernels: any thread. Mutable types belong to one thread at a time.

## More

- Design: [DESIGN.md](../../docs/DESIGN.md) §8
- API: `./gradlew :math:javadoc`, or the Javadoc site (`./gradlew :site:javadocSite`)
- Tests: `./gradlew :math:test`
