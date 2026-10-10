/**
 * Entity handles, sparse-set component stores and the SoA transform hierarchy. See §13.1 of
 * docs/DESIGN.md.
 */
module dev.jeddy.scene {
    requires transitive dev.jeddy.core;
    requires transitive dev.jeddy.math;

// Exported together with the first public type (javac rejects exporting a package with no types):
// exports dev.jeddy.scene;
}
