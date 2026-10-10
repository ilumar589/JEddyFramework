/**
 * Offline asset cooker (jeddy-cook): glTF, textures, materials, shaders, physics bakes and pak
 * building. See §10.3 of docs/DESIGN.md.
 */
module dev.jeddy.cooker {
    requires java.desktop; // ImageIO for PNG, JPEG, BMP and GIF sources
    requires dev.jeddy.core;
    requires dev.jeddy.math;
    requires dev.jeddy.compress;
    requires dev.jeddy.asset;
    requires dev.jeddy.physics;
}
