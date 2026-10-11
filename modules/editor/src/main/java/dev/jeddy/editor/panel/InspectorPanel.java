package dev.jeddy.editor.panel;

import dev.jeddy.asset.format.LevelData;
import dev.jeddy.editor.EditorContext;
import dev.jeddy.editor.GizmoMath;
import dev.jeddy.editor.LevelObjects;
import dev.jeddy.editor.Selection;
import dev.jeddy.editor.Text;
import dev.jeddy.editor.command.ObjectField;
import dev.jeddy.editor.command.SetFloatCommand;
import dev.jeddy.editor.command.SetIntCommand;
import dev.jeddy.editor.command.SetStringCommand;
import dev.jeddy.editor.command.TransformCommand;
import dev.jeddy.math.MathF;
import dev.jeddy.ui.TextBuffer;
import dev.jeddy.ui.Ui;
import dev.jeddy.ui.Widgets;

/**
 * A property grid for the primary selected object (DESIGN §19): name, layer, prefab, flags and the
 * transform of a placement (rotation as yaw, pitch and roll in degrees); type, color, intensity,
 * range, cone and flags of a light; kind and facing of a spawn; kind, payload, extents and flags of
 * a trigger. Every change is a command on the whole selection (the same value for every selected
 * object of a kind that has the field), pushed with {@code merge} so typing or dragging a field
 * makes one history entry.
 *
 * <p>The text buffers mirror the level and are re-read when the primary changes or the level is
 * edited elsewhere, except for a field being typed into.
 *
 * <p>Thread ownership: the game thread.
 */
public final class InspectorPanel extends Panel {
    private static final long NAME = 1;
    private static final long LAYER = 2;
    private static final long KIND = 3;
    private static final long PAYLOAD = 4;
    private static final long PREFAB = 5;
    private static final long FLAG = 6;
    private static final long COLLISION = 7;
    private static final long POSITION = 8;
    private static final long ROTATION = 9;
    private static final long SCALE = 10;
    private static final long TYPE = 11;
    private static final long COLOR = 12;
    private static final long INTENSITY = 13;
    private static final long RANGE = 14;
    private static final long DIRECTION = 15;
    private static final long CONE = 16;
    private static final long YAW = 17;
    private static final long SIZE = 18;

    private static final CharSequence[] LIGHT_TYPES = {"Point", "Spot"};
    private static final String[] PLACEMENT_FLAG_NAMES = {
        "Static", "Casts shadow", "Fade occluder", "Hidden", "No decals", "No collision"
    };
    private static final int[] PLACEMENT_FLAG_BITS = {
        LevelData.PLACEMENT_STATIC,
        LevelData.PLACEMENT_CASTS_SHADOW,
        LevelData.PLACEMENT_FADE_OCCLUDER,
        LevelData.PLACEMENT_HIDDEN,
        LevelData.PLACEMENT_NO_DECALS,
        LevelData.PLACEMENT_NO_COLLISION
    };

    private final TextBuffer name = new TextBuffer(64);
    private final TextBuffer layer = new TextBuffer(32);
    private final TextBuffer kind = new TextBuffer(32);
    private final TextBuffer payload = new TextBuffer(128);
    private int syncedId = -1;
    private int syncedVersion = -1;
    private final float[] vec = new float[4];
    private final float[] vec2 = new float[4];
    private final float[] quat = new float[4];
    private CharSequence[] prefabLabels = new CharSequence[0];
    private int prefabLabelsFor = -1;
    private LevelData prefabLabelsLevel;

    /** Creates the panel. */
    public InspectorPanel() {
        super("Inspector");
    }

    @Override
    public void levelChanged(EditorContext ctx) {
        syncedId = -1;
        prefabLabelsFor = -1;
    }

    @Override
    public void draw(EditorContext ctx, Widgets w) {
        Ui ui = w.ui;
        LevelData level = ctx.level();
        Selection sel = ctx.selection();
        if (level == null) {
            w.labelDim("No level open");
            return;
        }
        int id = sel.primary();
        if (id < 0 || !LevelObjects.exists(level, id)) {
            w.labelDim("Nothing selected");
            w.small("Click an object in the view or the Outliner.");
            return;
        }
        int kindOf = level.kindOf(id);
        int index = LevelObjects.index(level, id);
        sync(ctx, w, level, id, kindOf, index);

        // Header.
        w.row().open();
        w.heading(LevelObjects.kindName(kindOf));
        ui.text().append('#').append(id).color(w.theme.textDim);
        if (sel.size() > 1) {
            ui.text().append("(+").append(sel.size() - 1).append(" more)").color(w.theme.textDim);
        }
        ui.close();

        // Name.
        w.beginProperty("Name");
        if (w.textField(NAME, name)) {
            push(
                    ctx,
                    new SetStringCommand(
                            level, ids(sel), sel.size(), ObjectField.NAME, name.toString()));
        }
        w.endProperty();

        switch (kindOf) {
            case LevelData.KIND_PLACEMENT -> placement(ctx, w, level, sel, index);
            case LevelData.KIND_LIGHT -> light(ctx, w, level, sel, index);
            case LevelData.KIND_SPAWN -> spawn(ctx, w, level, sel, index);
            default -> trigger(ctx, w, level, sel, index);
        }
    }

    private static int[] ids(Selection sel) {
        return sel.ids().array();
    }

    private void push(EditorContext ctx, dev.jeddy.editor.command.Command c) {
        ctx.undo().push(c, true);
        syncedVersion = ctx.level().version();
    }

    /** Re-reads the text buffers from the level when the object or the level changed. */
    private void sync(
            EditorContext ctx, Widgets w, LevelData level, int id, int kindOf, int index) {
        boolean newObject = id != syncedId;
        if (!newObject && level.version() == syncedVersion) {
            return;
        }
        Ui ui = w.ui;
        if (newObject || !ui.focused(NAME)) {
            setIfDifferent(name, LevelObjects.name(level, id));
        }
        if (kindOf == LevelData.KIND_PLACEMENT && (newObject || !ui.focused(LAYER))) {
            setIfDifferent(layer, level.placementLayer(index));
        }
        if (kindOf == LevelData.KIND_SPAWN && (newObject || !ui.focused(KIND))) {
            setIfDifferent(kind, level.spawnKind(index));
        }
        if (kindOf == LevelData.KIND_TRIGGER) {
            if (newObject || !ui.focused(KIND)) {
                setIfDifferent(kind, level.triggerKind(index));
            }
            if (newObject || !ui.focused(PAYLOAD)) {
                setIfDifferent(payload, level.triggerPayload(index));
            }
        }
        syncedId = id;
        syncedVersion = level.version();
    }

    private static void setIfDifferent(TextBuffer buffer, String value) {
        if (!Text.equals(buffer, value)) {
            buffer.set(value);
        }
    }

    // ---- Placements ---------------------------------------------------------------------------

    private void placement(EditorContext ctx, Widgets w, LevelData level, Selection sel, int i) {
        Ui ui = w.ui;
        w.beginProperty("Layer");
        if (w.textField(LAYER, layer)) {
            push(
                    ctx,
                    new SetStringCommand(
                            level, ids(sel), sel.size(), ObjectField.LAYER, layer.toString()));
        }
        w.endProperty();

        w.beginProperty("Prefab");
        if (prefabLabelsFor != level.prefabCount() || prefabLabelsLevel != level) {
            prefabLabels = new CharSequence[level.prefabCount()];
            for (int p = 0; p < prefabLabels.length; p++) {
                String node = level.prefabNode(p);
                prefabLabels[p] = node.isEmpty() ? level.prefabScene(p) : node;
            }
            prefabLabelsFor = level.prefabCount();
            prefabLabelsLevel = level;
        }
        int prefab = level.placementPrefab(i);
        int chosen = w.dropdown(PREFAB, prefab, prefabLabels);
        if (chosen != prefab) {
            push(ctx, new SetIntCommand(level, ids(sel), sel.size(), ObjectField.PREFAB, chosen));
        }
        w.endProperty();

        int flags = level.placementFlags(i);
        w.beginProperty("Flags");
        ui.box().column().widthGrow().gap(0).open();
        for (int f = 0; f < PLACEMENT_FLAG_NAMES.length; f++) {
            ui.pushId(f);
            boolean on = (flags & PLACEMENT_FLAG_BITS[f]) != 0;
            boolean now = w.checkbox(FLAG, PLACEMENT_FLAG_NAMES[f], on);
            if (now != on) {
                push(
                        ctx,
                        SetIntCommand.flag(
                                level, ids(sel), sel.size(), PLACEMENT_FLAG_BITS[f], now));
            }
            ui.popId();
        }
        ui.close();
        w.endProperty();

        w.beginProperty("Collision");
        int collision = level.placementCollision(i);
        int now = Math.round(w.numberField(COLLISION, collision, 0.1f, -1, 0x3FFFFF, 0));
        if (now != collision) {
            push(ctx, new SetIntCommand(level, ids(sel), sel.size(), ObjectField.COLLISION, now));
        }
        ui.text(collision < 0 ? "by layer" : "override")
                .fontSize(w.theme.fontSizeSmall)
                .color(w.theme.textDim);
        w.endProperty();

        position(ctx, w, level, sel, i, 0);

        w.beginProperty("Rotation");
        for (int k = 0; k < 4; k++) {
            quat[k] = level.placementFloat(i, 3 + k);
        }
        GizmoMath.toEuler(quat, 0, vec, 0);
        for (int k = 0; k < 3; k++) {
            vec[k] = MathF.toDegrees(vec[k]);
        }
        if (w.vector3Field(ROTATION, vec, 0, 0.5f, 1) != 0) {
            GizmoMath.fromEuler(
                    MathF.toRadians(vec[0]),
                    MathF.toRadians(vec[1]),
                    MathF.toRadians(vec[2]),
                    quat,
                    0);
            TransformCommand c = new TransformCommand(level, ids(sel), sel.size(), "Rotate");
            for (int o = 0; o < c.count(); o++) {
                if (level.kindOf(c.id(o)) == LevelData.KIND_PLACEMENT) {
                    for (int k = 0; k < 4; k++) {
                        c.setAfter(o, 3 + k, quat[k]);
                    }
                }
            }
            push(ctx, c);
        }
        w.endProperty();

        w.beginProperty("Scale");
        floats(ctx, w, level, sel, i, SCALE, 7, 3, 0.01f, 0.001f, 1000, 3, "Scale");
        w.endProperty();
    }

    /** The position row (floats 0–2 of every kind). */
    private void position(
            EditorContext ctx, Widgets w, LevelData level, Selection sel, int i, int o) {
        w.beginProperty("Position");
        floats(ctx, w, level, sel, i, POSITION, 0, 3, 0.05f, -1e6f, 1e6f, 2, "Move");
        w.endProperty();
    }

    /**
     * A vector field over {@code n} floats starting at {@code first} of the primary's row; a change
     * becomes one merged {@link SetFloatCommand} per component on the whole selection.
     */
    private void floats(
            EditorContext ctx,
            Widgets w,
            LevelData level,
            Selection sel,
            int i,
            long id,
            int first,
            int n,
            float step,
            float min,
            float max,
            int decimals,
            String name) {
        int primary = sel.primary();
        for (int k = 0; k < n; k++) {
            vec[k] = LevelObjects.get(level, primary, first + k);
        }
        int changed = w.vectorField(id, vec, 0, n, step, min, max, decimals);
        for (int k = 0; k < n; k++) {
            if ((changed & 1 << k) != 0) {
                push(
                        ctx,
                        new SetFloatCommand(level, ids(sel), sel.size(), first + k, vec[k], name));
            }
        }
    }

    private void degrees(
            EditorContext ctx,
            Widgets w,
            LevelData level,
            Selection sel,
            long id,
            int k,
            String name) {
        float v = MathF.toDegrees(LevelObjects.get(level, sel.primary(), k));
        float now = w.numberField(id, v, 0.5f, -36000, 36000, 1);
        if (now != v) {
            push(
                    ctx,
                    new SetFloatCommand(
                            level, ids(sel), sel.size(), k, MathF.toRadians(now), name));
        }
    }

    // ---- Lights -------------------------------------------------------------------------------

    private void light(EditorContext ctx, Widgets w, LevelData level, Selection sel, int i) {
        Ui ui = w.ui;
        w.beginProperty("Type");
        int type = level.lightType(i);
        int chosen = w.dropdown(TYPE, type - 1, LIGHT_TYPES) + 1;
        if (chosen != type) {
            push(
                    ctx,
                    new SetIntCommand(level, ids(sel), sel.size(), ObjectField.LIGHT_TYPE, chosen));
        }
        w.endProperty();
        position(ctx, w, level, sel, i, 0);
        w.beginProperty("Color");
        for (int k = 0; k < 3; k++) {
            vec2[k] = level.lightFloat(i, 6 + k);
        }
        int changed = w.colorField(COLOR, vec2, 0, 10);
        for (int k = 0; k < 3; k++) {
            if ((changed & 1 << k) != 0) {
                push(
                        ctx,
                        new SetFloatCommand(level, ids(sel), sel.size(), 6 + k, vec2[k], "Color"));
            }
        }
        w.endProperty();
        w.beginProperty("Intensity");
        float v = level.lightFloat(i, 9);
        float now = w.numberField(INTENSITY, v, 0.05f, 0, 1e5f, 2);
        if (now != v) {
            push(ctx, new SetFloatCommand(level, ids(sel), sel.size(), 9, now, "Intensity"));
        }
        ui.text("cd").fontSize(w.theme.fontSizeSmall).color(w.theme.textDim);
        w.endProperty();
        w.beginProperty("Range");
        v = level.lightFloat(i, 10);
        now = w.numberField(RANGE, v, 0.05f, 0.1f, 1000, 2);
        if (now != v) {
            push(ctx, new SetFloatCommand(level, ids(sel), sel.size(), 10, now, "Range"));
        }
        ui.text("m").fontSize(w.theme.fontSizeSmall).color(w.theme.textDim);
        w.endProperty();
        if (type == LevelData.LIGHT_SPOT) {
            w.beginProperty("Direction");
            floats(ctx, w, level, sel, i, DIRECTION, 3, 3, 0.01f, -1, 1, 3, "Direction");
            w.endProperty();
            w.beginProperty("Cone");
            ui.pushId(0);
            degrees(ctx, w, level, sel, CONE, 11, "Inner cone");
            ui.popId();
            ui.pushId(1);
            degrees(ctx, w, level, sel, CONE, 12, "Outer cone");
            ui.popId();
            w.endProperty();
        }
        int flags = level.lightFlags(i);
        w.beginProperty("Flags");
        ui.box().column().widthGrow().gap(0).open();
        ui.pushId(0);
        boolean shadow = w.checkbox(FLAG, "Shadow", (flags & LevelData.LIGHT_SHADOW) != 0);
        if (shadow != ((flags & LevelData.LIGHT_SHADOW) != 0)) {
            push(
                    ctx,
                    SetIntCommand.flag(
                            level, ids(sel), sel.size(), LevelData.LIGHT_SHADOW, shadow));
        }
        ui.popId();
        ui.pushId(1);
        boolean flicker =
                w.checkbox(FLAG, "Flicker (torch)", (flags & LevelData.LIGHT_FLICKER) != 0);
        if (flicker != ((flags & LevelData.LIGHT_FLICKER) != 0)) {
            push(
                    ctx,
                    SetIntCommand.flag(
                            level, ids(sel), sel.size(), LevelData.LIGHT_FLICKER, flicker));
        }
        ui.popId();
        ui.close();
        w.endProperty();
    }

    // ---- Spawns -------------------------------------------------------------------------------

    private void spawn(EditorContext ctx, Widgets w, LevelData level, Selection sel, int i) {
        w.beginProperty("Kind");
        if (w.textField(KIND, kind, "party, enemy ...")) {
            push(
                    ctx,
                    new SetStringCommand(
                            level, ids(sel), sel.size(), ObjectField.KIND, kind.toString()));
        }
        w.endProperty();
        position(ctx, w, level, sel, i, 0);
        w.beginProperty("Facing");
        degrees(ctx, w, level, sel, YAW, 3, "Turn");
        w.ui.text("deg").fontSize(w.theme.fontSizeSmall).color(w.theme.textDim);
        w.endProperty();
    }

    // ---- Triggers -----------------------------------------------------------------------------

    private void trigger(EditorContext ctx, Widgets w, LevelData level, Selection sel, int i) {
        Ui ui = w.ui;
        w.beginProperty("Kind");
        if (w.textField(KIND, kind, "area, door, script ...")) {
            push(
                    ctx,
                    new SetStringCommand(
                            level, ids(sel), sel.size(), ObjectField.KIND, kind.toString()));
        }
        w.endProperty();
        w.beginProperty("Payload");
        if (w.textField(PAYLOAD, payload, "what the game receives")) {
            push(
                    ctx,
                    new SetStringCommand(
                            level, ids(sel), sel.size(), ObjectField.PAYLOAD, payload.toString()));
        }
        w.endProperty();
        position(ctx, w, level, sel, i, 0);
        w.beginProperty("Size");
        floats(ctx, w, level, sel, i, SIZE, 3, 3, 0.05f, 0.01f, 1000, 2, "Resize");
        w.endProperty();
        w.beginProperty("Facing");
        degrees(ctx, w, level, sel, YAW, 6, "Turn");
        ui.text("deg").fontSize(w.theme.fontSizeSmall).color(w.theme.textDim);
        w.endProperty();
        int flags = level.triggerFlags(i);
        w.beginProperty("Flags");
        ui.box().column().widthGrow().gap(0).open();
        ui.pushId(0);
        boolean once = w.checkbox(FLAG, "Once", (flags & LevelData.TRIGGER_ONCE) != 0);
        if (once != ((flags & LevelData.TRIGGER_ONCE) != 0)) {
            push(
                    ctx,
                    SetIntCommand.flag(level, ids(sel), sel.size(), LevelData.TRIGGER_ONCE, once));
        }
        ui.popId();
        ui.pushId(1);
        boolean enabled = w.checkbox(FLAG, "Enabled", (flags & LevelData.TRIGGER_ENABLED) != 0);
        if (enabled != ((flags & LevelData.TRIGGER_ENABLED) != 0)) {
            push(
                    ctx,
                    SetIntCommand.flag(
                            level, ids(sel), sel.size(), LevelData.TRIGGER_ENABLED, enabled));
        }
        ui.popId();
        ui.close();
        w.endProperty();
    }
}
