/**
 * Gameplay physics: collision BVH, broadphase grid, queries, navmesh, pathfinding, crowd,
 * visibility and triggers. See §11 of docs/DESIGN.md.
 */
module dev.jeddy.physics {
    requires transitive dev.jeddy.core;
    requires transitive dev.jeddy.math;

    exports dev.jeddy.physics.bvh;
    exports dev.jeddy.physics.crowd;
    exports dev.jeddy.physics.debug;
    exports dev.jeddy.physics.grid;
    exports dev.jeddy.physics.nav;
    exports dev.jeddy.physics.nav.bake;
    exports dev.jeddy.physics.query;
    exports dev.jeddy.physics.trigger;
    exports dev.jeddy.physics.vis;
}
