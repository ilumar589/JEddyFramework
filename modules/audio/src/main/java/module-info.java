/**
 * SDL3_mixer wrapper: voice pool, buses, 3D emitters, music and ambience. See §12.3 of
 * docs/DESIGN.md.
 */
module dev.jeddy.audio {
    requires transitive dev.jeddy.core;
    requires transitive dev.jeddy.math;
    requires dev.jeddy.sdl;
    requires dev.jeddy.asset;

// Exported together with the first public type (javac rejects exporting a package with no types):
// exports dev.jeddy.audio;
}
