/**
 * Generated OpenGL 4.6 core bindings, function loader and capability detection. See §9.1 of
 * docs/DESIGN.md.
 */
module dev.jeddy.gl {
    requires transitive dev.jeddy.core;

    exports dev.jeddy.gl;
    exports dev.jeddy.gl.ffi to
            dev.jeddy.render,
            dev.jeddy.app,
            dev.jeddy.samples.triangle,
            dev.jeddy.samples.iso;
}
