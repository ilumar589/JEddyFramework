/**
 * Entity handles, sparse-set component stores and the SoA transform hierarchy. See §13.1 of
 * docs/DESIGN.md.
 */
module dev.jeddy.scene {
    requires transitive dev.jeddy.core;
    requires transitive dev.jeddy.math;

    exports dev.jeddy.scene;
}
