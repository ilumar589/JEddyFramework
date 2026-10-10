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
    exports dev.jeddy.render.camera;
    exports dev.jeddy.render.debug;
    exports dev.jeddy.render.decal;
    exports dev.jeddy.render.draw;
    exports dev.jeddy.render.fog;
    exports dev.jeddy.render.gpu;
    exports dev.jeddy.render.graph;
    exports dev.jeddy.render.light;
    exports dev.jeddy.render.material;
    exports dev.jeddy.render.mesh;
    exports dev.jeddy.render.outline;
    exports dev.jeddy.render.particle;
    exports dev.jeddy.render.post;
    exports dev.jeddy.render.shader;
    exports dev.jeddy.render.shadow;
}
