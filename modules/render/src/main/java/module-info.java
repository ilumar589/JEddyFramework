/**
 * GL resources, frame graph, render packets, MDI submission, PBR, lights, shadows, post, decals and
 * debug draw. See §9 of docs/DESIGN.md.
 */
module dev.jeddy.render {
    requires transitive dev.jeddy.core;
    requires transitive dev.jeddy.math;
    requires transitive dev.jeddy.gl;
    requires transitive dev.jeddy.window;
    requires transitive dev.jeddy.asset;

    exports dev.jeddy.render;
    exports dev.jeddy.render.draw;
    exports dev.jeddy.render.gpu;
    exports dev.jeddy.render.mesh;
    exports dev.jeddy.render.shader;
}
