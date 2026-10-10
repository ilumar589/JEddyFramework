/** Gameplay physics: collision BVH, broadphase grid, queries, navmesh, pathfinding, crowd, visibility and triggers. See §11 of docs/DESIGN.md. */
module dev.jeddy.physics {
    requires transitive dev.jeddy.core;
    requires transitive dev.jeddy.math;

    // Exported together with the first public type (javac rejects exporting a package with no types):
    // exports dev.jeddy.physics;
}
