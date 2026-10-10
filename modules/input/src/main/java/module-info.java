/**
 * Event pump, device state, action mapping, input contexts, CRPG gestures and gamepads. See §12.2
 * of docs/DESIGN.md.
 */
module dev.jeddy.input {
    requires transitive dev.jeddy.core;
    requires transitive dev.jeddy.math;
    requires transitive dev.jeddy.window;
    requires dev.jeddy.sdl;

    exports dev.jeddy.input;
}
