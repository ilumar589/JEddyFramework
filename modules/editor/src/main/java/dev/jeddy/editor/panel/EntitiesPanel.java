package dev.jeddy.editor.panel;

import dev.jeddy.animation.Animations;
import dev.jeddy.animation.Clip;
import dev.jeddy.app.world.LevelLoader;
import dev.jeddy.app.world.World;
import dev.jeddy.core.handle.Handles;
import dev.jeddy.editor.EditorContext;
import dev.jeddy.render.RenderPacket;
import dev.jeddy.scene.TransformStore;
import dev.jeddy.ui.TextBuffer;
import dev.jeddy.ui.Ui;
import dev.jeddy.ui.Widgets;

/**
 * The running world (DESIGN §19): every entity of the transform store in a list (index, generation,
 * parent, static), with a filter by index, and for the one picked here its world position, its
 * local position (editable live, not undoable: this is the game's state, not the level's), the
 * placement it belongs to if any (one click selects it in the level), and the animation it plays.
 *
 * <p>Thread ownership: the game thread.
 */
public final class EntitiesPanel extends Panel {
    private static final long FILTER = 1;
    private static final long ROW = 2;
    private static final long LOCAL = 3;
    private static final long SELECT_PLACEMENT = 4;
    private static final int MAX_ROWS = 400;

    private final TextBuffer filter = new TextBuffer(16);
    private final float[] vec = new float[3];
    private int picked = Handles.NULL;
    private int highlighted = Handles.NULL;

    /** Creates the panel. */
    public EntitiesPanel() {
        super("Entities");
    }

    @Override
    public void draw(EditorContext ctx, Widgets w) {
        Ui ui = w.ui;
        World world = ctx.world();
        if (world == null) {
            w.labelDim("No world attached");
            return;
        }
        TransformStore t = world.transforms();
        w.row().open();
        ui.text()
                .append(t.size())
                .append(" entities, ")
                .append(world.renderableCount())
                .append(" renderables")
                .color(w.theme.textDim);
        ui.close();
        w.textField(FILTER, filter, "Filter by index");
        int wanted = filter.length() > 0 ? parseIndex(filter) : -1;
        int shown = 0;
        for (int s = 0; s < t.size() && shown < MAX_ROWS; s++) {
            int e = t.entityAt(s);
            int index = Handles.index(e);
            if (wanted >= 0 && index != wanted) {
                continue;
            }
            ui.pushId(e);
            w.beginSelectableRow(ROW, e == picked, 0);
            ui.text().append('#').append(index).append(':').append(Handles.generation(e));
            int p = t.parent(e);
            if (p != Handles.NULL) {
                ui.text()
                        .append("under #")
                        .append(Handles.index(p))
                        .fontSize(w.theme.fontSizeSmall)
                        .color(w.theme.textDim);
            }
            if (t.isStatic(e)) {
                ui.text("static").fontSize(w.theme.fontSizeSmall).color(w.theme.textDim);
            }
            if (w.endSelectableRow(ROW)) {
                pick(world, e);
            }
            ui.popId();
            shown++;
        }
        if (shown == MAX_ROWS) {
            w.labelDim("... filter to see more");
        }
        if (picked != Handles.NULL && !world.entities().isAlive(picked)) {
            picked = Handles.NULL;
        }
        if (picked == Handles.NULL) {
            return;
        }
        w.separator();
        w.heading("Entity");
        if (!t.has(picked)) {
            w.labelDim("no transform");
            return;
        }
        world.position(picked, vec);
        w.beginProperty("World");
        ui.text().append(vec[0], 2).append(", ").append(vec[1], 2).append(", ").append(vec[2], 2);
        w.endProperty();
        w.beginProperty("Local");
        for (int k = 0; k < 3; k++) {
            vec[k] = t.position(picked, k);
        }
        if (w.vector3Field(LOCAL, vec, 0, 0.05f, 2) != 0) {
            t.setPosition(picked, vec[0], vec[1], vec[2]);
        }
        w.endProperty();
        LevelLoader loader = ctx.loader();
        int root = world.groupOf(picked);
        int placement = loader == null ? -1 : loader.placementOf(root);
        if (placement < 0 && root != picked && loader != null) {
            placement = loader.placementOf(picked);
        }
        if (placement >= 0) {
            w.beginProperty("Placement");
            ui.text().append('#').append(placement);
            if (w.button(SELECT_PLACEMENT, "Select")) {
                ctx.selection().select(placement);
            }
            w.endProperty();
        }
        Animations anims = world.animations();
        int instance = anims == null ? -1 : world.animation(root);
        if (instance >= 0) {
            w.beginProperty("Animation");
            Clip clip = anims.current(instance);
            ui.text(clip == null ? "(none)" : clip.name());
            ui.text()
                    .append("phase ")
                    .append(anims.phase(instance), 2)
                    .fontSize(w.theme.fontSizeSmall)
                    .color(w.theme.textDim);
            w.endProperty();
        }
    }

    private void pick(World world, int e) {
        if (highlighted != Handles.NULL && world.entities().isAlive(highlighted)) {
            world.setGroupHighlight(world.groupOf(highlighted), 0);
        }
        picked = e;
        highlighted = e;
        world.setGroupHighlight(world.groupOf(e), RenderPacket.HIGHLIGHT_INTERACTABLE);
    }

    private static int parseIndex(CharSequence s) {
        int v = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return -2;
            }
            v = v * 10 + (c - '0');
        }
        return v;
    }
}
