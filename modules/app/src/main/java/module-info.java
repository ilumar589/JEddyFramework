/**
 * Application lifecycle, main and game thread loops, system scheduler, settings and crash handling.
 * See §13.2 of docs/DESIGN.md.
 */
module dev.jeddy.app {
    requires transitive dev.jeddy.core;
    requires transitive dev.jeddy.math;
    requires transitive dev.jeddy.window;
    requires transitive dev.jeddy.input;
    requires transitive dev.jeddy.render;
    requires transitive dev.jeddy.audio;
    requires transitive dev.jeddy.physics;
    requires transitive dev.jeddy.scene;
    requires transitive dev.jeddy.asset;
    requires dev.jeddy.sdl;

// Exported together with the first public type (javac rejects exporting a package with no types):
// exports dev.jeddy.app;
}
