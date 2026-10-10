/**
 * Offline asset cooker (jeddy-cook): glTF, textures, materials, shaders, physics bakes and pak
 * building. See §10.3 of docs/DESIGN.md.
 */
module dev.jeddy.cooker {
    requires dev.jeddy.core;
    requires dev.jeddy.math;
    requires dev.jeddy.compress;
    requires dev.jeddy.asset;
    requires dev.jeddy.physics;
}
