/**
 * Skeletal animation: skeletons and baked clips from cooked scenes, per-instance animation state
 * with blending and crossfades, and job-parallel evaluation of poses and skinning palettes. See
 * §13.4 of docs/DESIGN.md.
 */
module dev.jeddy.animation {
    requires transitive dev.jeddy.core;
    requires transitive dev.jeddy.math;
    requires transitive dev.jeddy.asset;

    exports dev.jeddy.animation;
}
