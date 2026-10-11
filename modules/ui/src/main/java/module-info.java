/**
 * Immediate-mode user interface: element tree, flex layout, SDF text measurement, widgets and draw
 * lists, without any GL. See §18 of docs/DESIGN.md.
 */
module dev.jeddy.ui {
    requires transitive dev.jeddy.core;
    requires transitive dev.jeddy.math;
    requires transitive dev.jeddy.asset;

    exports dev.jeddy.ui;
}
