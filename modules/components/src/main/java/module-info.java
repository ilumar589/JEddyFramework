/**
 * Components and behaviours: the annotations, the store and view bases, snapshot streams and the
 * phase runner the generated code uses. See §22 of docs/DESIGN.md.
 */
module dev.jeddy.components {
    requires transitive dev.jeddy.core;
    requires transitive dev.jeddy.math;
    requires transitive dev.jeddy.scene;
    requires transitive dev.jeddy.asset;

    exports dev.jeddy.components;
}
