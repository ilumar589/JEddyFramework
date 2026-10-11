/**
 * The level editor as a library: commands with undo, selection, picking and gizmos, docked panels
 * built from the {@code ui} widgets over the live scene. See §19 of docs/DESIGN.md.
 */
module dev.jeddy.editor {
    requires transitive dev.jeddy.core;
    requires transitive dev.jeddy.math;
    requires transitive dev.jeddy.asset;
    requires transitive dev.jeddy.ui;
    requires transitive dev.jeddy.physics;
    requires transitive dev.jeddy.app;

    exports dev.jeddy.editor;
    exports dev.jeddy.editor.command;
    exports dev.jeddy.editor.panel;
    exports dev.jeddy.editor.rules;
}
