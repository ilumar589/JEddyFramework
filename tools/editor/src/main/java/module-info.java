/**
 * The standalone level editor: a project (an asset source tree and its cooked output), the cooker
 * in watch mode, and the {@code editor} library over an otherwise empty world. See §19 of
 * docs/DESIGN.md.
 */
module dev.jeddy.editortool {
    requires dev.jeddy.editor;
    requires dev.jeddy.app;
    requires dev.jeddy.gl;
    requires dev.jeddy.cooker;
}
