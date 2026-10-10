/** Event pump, device state, action mapping, input contexts, CRPG gestures and gamepads. See §12.2 of docs/DESIGN.md. */
module dev.jeddy.input {
    requires transitive dev.jeddy.core;
    requires transitive dev.jeddy.math;
    requires dev.jeddy.sdl;
    requires dev.jeddy.window;

    // Exported together with the first public type (javac rejects exporting a package with no types):
    // exports dev.jeddy.input;
}
