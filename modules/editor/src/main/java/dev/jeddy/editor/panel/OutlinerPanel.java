package dev.jeddy.editor.panel;

import dev.jeddy.asset.format.LevelData;
import dev.jeddy.editor.EditorContext;
import dev.jeddy.editor.Selection;
import dev.jeddy.editor.Text;
import dev.jeddy.ui.TextBuffer;
import dev.jeddy.ui.Ui;
import dev.jeddy.ui.UiKey;
import dev.jeddy.ui.Widgets;

/**
 * The level's objects as a tree by kind with a filter (DESIGN §19): a row per placement (name or
 * prefab node, layer, id), light, spawn and trigger. Clicking selects (Ctrl toggles, Shift adds), a
 * double click frames the object in the viewport.
 *
 * <p>Thread ownership: the game thread.
 */
public final class OutlinerPanel extends Panel {
    private static final long FILTER = 1;
    private static final long KIND = 2;
    private static final long ROW = 3;
    private static final float INDENT = 12;

    private final TextBuffer filter = new TextBuffer(64);
    private final StringBuilder[] headings = {
        new StringBuilder(32), new StringBuilder(32), new StringBuilder(32), new StringBuilder(32)
    };
    private static final String[] KIND_NAMES = {"Placements", "Lights", "Spawns", "Triggers"};

    /** Creates the panel. */
    public OutlinerPanel() {
        super("Outliner");
    }

    @Override
    public void draw(EditorContext ctx, Widgets w) {
        Ui ui = w.ui;
        LevelData level = ctx.level();
        w.textField(FILTER, filter, "Filter by name or layer");
        if (level == null) {
            w.labelDim("No level open");
            return;
        }
        for (int kind = 0; kind < 4; kind++) {
            int n =
                    switch (kind) {
                        case LevelData.KIND_PLACEMENT -> level.placementCount();
                        case LevelData.KIND_LIGHT -> level.lightCount();
                        case LevelData.KIND_SPAWN -> level.spawnCount();
                        default -> level.triggerCount();
                    };
            StringBuilder h = headings[kind];
            h.setLength(0);
            h.append(KIND_NAMES[kind]).append(" (").append(n).append(')');
            ui.pushId(kind);
            if (w.treeNode(KIND, h, kind == LevelData.KIND_PLACEMENT)) {
                for (int i = 0; i < n; i++) {
                    row(ctx, w, level, kind, i);
                }
                w.treeEnd();
            }
            ui.popId();
        }
    }

    private void row(EditorContext ctx, Widgets w, LevelData level, int kind, int i) {
        Ui ui = w.ui;
        int id;
        String name;
        String detail;
        switch (kind) {
            case LevelData.KIND_PLACEMENT -> {
                id = level.placementId(i);
                name = level.placementName(i);
                detail = level.placementLayer(i);
                if (name.isEmpty()) {
                    name = level.prefabNode(level.placementPrefab(i));
                }
            }
            case LevelData.KIND_LIGHT -> {
                id = level.lightId(i);
                name = level.lightName(i);
                detail = level.lightType(i) == LevelData.LIGHT_SPOT ? "spot" : "point";
            }
            case LevelData.KIND_SPAWN -> {
                id = level.spawnId(i);
                name = level.spawnName(i);
                detail = level.spawnKind(i);
            }
            default -> {
                id = level.triggerId(i);
                name = level.triggerName(i);
                detail = level.triggerKind(i);
            }
        }
        if (filter.length() > 0
                && !Text.containsIgnoreCase(name, filter)
                && !Text.containsIgnoreCase(detail, filter)) {
            return;
        }
        Selection sel = ctx.selection();
        boolean selected = sel.contains(id);
        ui.pushId(id);
        w.beginSelectableRow(ROW, selected, INDENT);
        ui.text(name.isEmpty() ? "(unnamed)" : name).color(selected ? 0xFFFFFFFF : w.theme.text);
        ui.text(detail)
                .fontSize(w.theme.fontSizeSmall)
                .color(selected ? 0xFFE0E0E0 : w.theme.textDim);
        w.spacer();
        ui.text()
                .append('#')
                .append(id)
                .fontSize(w.theme.fontSizeSmall)
                .color(selected ? 0xFFE0E0E0 : w.theme.textDim);
        boolean clicked = w.endSelectableRow(ROW);
        boolean doubleClicked = ui.doubleClicked(ROW);
        ui.popId();
        if (clicked) {
            int mods = ui.input().modifiers;
            if ((mods & UiKey.MOD_CTRL) != 0) {
                sel.toggle(id);
            } else if ((mods & UiKey.MOD_SHIFT) != 0) {
                sel.add(id);
            } else {
                sel.select(id);
            }
        }
        if (doubleClicked && ctx.viewport() != null) {
            ctx.viewport().frameSelection();
        }
    }
}
