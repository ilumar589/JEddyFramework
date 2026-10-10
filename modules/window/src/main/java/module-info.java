/**
 * SDL video init, windows, displays and DPI, GL context, swap, frame pacing and cursors. Main
 * thread only. See §12.1 of docs/DESIGN.md.
 */
module dev.jeddy.window {
    requires transitive dev.jeddy.core;
    requires dev.jeddy.sdl;

    exports dev.jeddy.window;
}
