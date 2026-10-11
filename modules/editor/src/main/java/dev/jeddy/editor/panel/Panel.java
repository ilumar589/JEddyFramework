package dev.jeddy.editor.panel;

import dev.jeddy.editor.EditorContext;
import dev.jeddy.ui.Widgets;

/**
 * A docked editor panel (DESIGN §19): a name for its tab and a {@link #draw} that declares its
 * contents into the context's UI every frame it is the visible tab of its zone. Panels keep their
 * own per-frame scratch (text buffers, label arrays) so drawing allocates nothing.
 *
 * <p>Thread ownership: the game thread.
 */
public abstract class Panel {
    private final String name;

    /** Creates a panel named {@code name} (its tab label and layout key). */
    protected Panel(String name) {
        this.name = name;
    }

    /** Returns the tab label. */
    public final String name() {
        return name;
    }

    /** Declares the panel's contents (inside a scrolling column the dock opened). */
    public abstract void draw(EditorContext ctx, Widgets w);

    /** Called when a level opens or closes, so caches can be dropped. */
    public void levelChanged(EditorContext ctx) {}
}
