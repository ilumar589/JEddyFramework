/**
 * GL resources, frame graph, render packets, MDI submission, PBR, lights, shadows, post, decals and
 * debug draw. See §9 of docs/DESIGN.md.
 */
module dev.jeddy.render {
    requires transitive dev.jeddy.core;
    requires transitive dev.jeddy.math;
    requires dev.jeddy.gl;
    requires dev.jeddy.window;
    requires dev.jeddy.asset;

// Exported together with the first public type (javac rejects exporting a package with no types):
// exports dev.jeddy.render;
}
