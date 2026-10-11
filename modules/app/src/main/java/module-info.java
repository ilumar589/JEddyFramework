/**
 * Application lifecycle, main and game thread loops, world integration, settings and crash
 * handling. See §13.2 of docs/DESIGN.md.
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
    requires transitive dev.jeddy.animation;
    requires transitive dev.jeddy.ui;
    requires dev.jeddy.sdl;

    exports dev.jeddy.app;
    exports dev.jeddy.app.audio;
    exports dev.jeddy.app.physics;
    exports dev.jeddy.app.ui;
    exports dev.jeddy.app.world;
}
