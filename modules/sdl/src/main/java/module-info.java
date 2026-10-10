/**
 * Raw SDL3 and SDL3_mixer bindings plus low-level helpers. Exported only to engine modules, never
 * to games. See §6.4 of docs/DESIGN.md.
 */
module dev.jeddy.sdl {
    requires transitive dev.jeddy.core;

    exports dev.jeddy.sdl to
            dev.jeddy.window,
            dev.jeddy.input,
            dev.jeddy.audio,
            dev.jeddy.app;
    exports dev.jeddy.sdl.ffi to
            dev.jeddy.window,
            dev.jeddy.input,
            dev.jeddy.audio,
            dev.jeddy.app;
    exports dev.jeddy.sdl.mixer.ffi to
            dev.jeddy.audio;
}
