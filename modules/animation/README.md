# jeddy-animation

Module `dev.jeddy.animation`. Skeletal animation for CRPG crowds: skeletons read from cooked scenes, clips baked at load time into fixed-rate frames of joint-local poses, and SoA animated instances that blend, crossfade and evaluate in parallel into joint matrices, GPU skinning palettes and joint bounds.

## Key types

- `Skeleton`: the joints of one glTF skin (parents, bind pose, inverse binds, pre-transforms, names, layout hash); `attachment` finds where a mesh hangs off a joint
- `Clip`: `Clip.bake(library, name, skeleton)` resamples a scene's animation (linear, step and cubic channels) at 30 Hz, binding channels to joints by name; an optional root lock plays locomotion in place
- `Animations`: up to N instances with states (one clip, or a synchronized two-clip blend such as walk/run), speed, looping or holding, crossfades and a phase; `update(dt, jobs)` evaluates them in parallel; `models()`, `palettes()` and `bounds()` are flat `float[]` outputs

`World.instantiateAnimated` in `app` spawns animated scenes and feeds the palettes to the renderer (DESIGN §13.4); [docs/getting-started.md](../../docs/getting-started.md) has a walkthrough.

## Threading

The game thread; `Animations.update` borrows job workers. Skeletons and baked clips are immutable and shared.

## More

- Design: [DESIGN.md](../../docs/DESIGN.md) §13.4, D79, D80
- API: `./gradlew :animation:javadoc`, or the Javadoc site (`./gradlew :site:javadocSite`)
- Tests: `./gradlew :animation:test`; benchmark: `./gradlew :bench:jmh -Pjmh.includes=AnimationBench`
