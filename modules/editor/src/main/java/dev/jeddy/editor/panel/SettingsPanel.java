package dev.jeddy.editor.panel;

import dev.jeddy.asset.format.LevelData;
import dev.jeddy.editor.EditorContext;
import dev.jeddy.editor.EditorSettings;
import dev.jeddy.editor.Text;
import dev.jeddy.editor.command.SetSettingsCommand;
import dev.jeddy.editor.command.TableCommand;
import dev.jeddy.ui.TextBuffer;
import dev.jeddy.ui.Ui;
import dev.jeddy.ui.Widgets;

/**
 * Two groups of settings (DESIGN §19): the editor's own ({@link EditorSettings}: snapping, what the
 * viewport draws, camera speeds; changed directly, not undoable) and the level's ({@link
 * LevelData.Settings}: navmesh and occlusion bake parameters, sun, exposure, environment, grid
 * step; every change is a merged {@link SetSettingsCommand}), followed by the level's collision
 * rules and agent size classes as editable tables ({@link TableCommand}).
 *
 * <p>Thread ownership: the game thread.
 */
public final class SettingsPanel extends Panel {
    private static final long EDITOR = 1;
    private static final long LEVEL = 2;
    private static final long RULES = 3;
    private static final long SIZES = 4;
    private static final long FIELD = 100; // + k per field, well clear of the others
    private static final long ROW = 6;
    private static final long ADD = 7;
    private static final long REMOVE = 8;
    private static final long TEXT = 9;
    private static final long ENV = 10;

    private final LevelData.Settings edited = new LevelData.Settings();
    private final float[] vec = new float[3];
    private final TextBuffer environment = new TextBuffer(96);
    private final TextBuffer[] ruleLayers = new TextBuffer[16];
    private final TextBuffer[] sizeNames = new TextBuffer[8];
    private final TableCommand.Row row = new TableCommand.Row();
    private int syncedVersion = -1;
    private LevelData syncedLevel;

    /** Creates the panel. */
    public SettingsPanel() {
        super("Settings");
        for (int i = 0; i < ruleLayers.length; i++) {
            ruleLayers[i] = new TextBuffer(32);
        }
        for (int i = 0; i < sizeNames.length; i++) {
            sizeNames[i] = new TextBuffer(16);
        }
    }

    @Override
    public void draw(EditorContext ctx, Widgets w) {
        Ui ui = w.ui;
        EditorSettings s = ctx.settings();
        if (w.treeNode(EDITOR, "Editor", true)) {
            s.snap = w.checkbox(FIELD + 0, "Snap (X toggles, Ctrl inverts)", s.snap);
            w.beginProperty("Move step");
            s.translateStep = w.numberField(FIELD + 1, s.translateStep, 0.01f, 0.01f, 100, 2);
            w.endProperty();
            w.beginProperty("Turn step");
            s.rotateStepDegrees = w.numberField(FIELD + 2, s.rotateStepDegrees, 0.5f, 1, 180, 1);
            w.endProperty();
            w.beginProperty("Scale step");
            s.scaleStep = w.numberField(FIELD + 3, s.scaleStep, 0.01f, 0.01f, 10, 2);
            w.endProperty();
            s.showGrid = w.checkbox(FIELD + 4, "Grid (G)", s.showGrid);
            s.showMarkers =
                    w.checkbox(FIELD + 5, "Light, spawn and trigger markers", s.showMarkers);
            w.beginProperty("Gizmo size");
            s.gizmoPixels = w.numberField(FIELD + 6, s.gizmoPixels, 1, 30, 300, 0);
            w.endProperty();
            w.beginProperty("Orbit speed");
            s.orbitSpeed = w.numberField(FIELD + 7, s.orbitSpeed, 0.0005f, 0.001f, 0.05f, 4);
            w.endProperty();
            w.treeEnd();
        }
        LevelData level = ctx.level();
        if (level == null) {
            return;
        }
        if (level != syncedLevel || level.version() != syncedVersion) {
            sync(level, ui);
        }
        if (w.treeNode(LEVEL, "Level", true)) {
            LevelData.Settings ls = level.settings;
            edited.copyFrom(ls);
            boolean changed = false;
            w.beginProperty("Nav cell");
            edited.navCellSize = w.numberField(FIELD + 10, ls.navCellSize, 0.005f, 0.05f, 2, 3);
            changed |= edited.navCellSize != ls.navCellSize;
            edited.navCellHeight = w.numberField(FIELD + 11, ls.navCellHeight, 0.005f, 0.02f, 2, 3);
            changed |= edited.navCellHeight != ls.navCellHeight;
            w.endProperty();
            w.beginProperty("Nav tile");
            edited.navTileSize = w.numberField(FIELD + 12, ls.navTileSize, 0.5f, 4, 256, 0);
            changed |= edited.navTileSize != ls.navTileSize;
            ui.text("m").fontSize(w.theme.fontSizeSmall).color(w.theme.textDim);
            w.endProperty();
            w.beginProperty("Max slope");
            edited.navMaxSlope = w.numberField(FIELD + 13, ls.navMaxSlope, 0.5f, 0, 89, 1);
            changed |= edited.navMaxSlope != ls.navMaxSlope;
            ui.text("deg").fontSize(w.theme.fontSizeSmall).color(w.theme.textDim);
            w.endProperty();
            w.beginProperty("Occlusion");
            edited.occlusionEnabled = w.checkbox(FIELD + 14, "bake", ls.occlusionEnabled);
            changed |= edited.occlusionEnabled != ls.occlusionEnabled;
            edited.occlusionCellSize =
                    w.numberField(FIELD + 15, ls.occlusionCellSize, 0.01f, 0.1f, 5, 2);
            changed |= edited.occlusionCellSize != ls.occlusionCellSize;
            w.endProperty();
            w.beginProperty("Occlusion Y");
            edited.occlusionYMin = w.numberField(FIELD + 16, ls.occlusionYMin, 0.01f, -10, 10, 2);
            changed |= edited.occlusionYMin != ls.occlusionYMin;
            edited.occlusionYMax = w.numberField(FIELD + 17, ls.occlusionYMax, 0.01f, -10, 10, 2);
            changed |= edited.occlusionYMax != ls.occlusionYMax;
            w.endProperty();
            w.beginProperty("Sun direction");
            System.arraycopy(ls.sunDirection, 0, vec, 0, 3);
            if (w.vector3Field(FIELD + 18, vec, 0, 0.005f, 3) != 0) {
                System.arraycopy(vec, 0, edited.sunDirection, 0, 3);
                changed = true;
            }
            w.endProperty();
            w.beginProperty("Sun color");
            System.arraycopy(ls.sunColor, 0, vec, 0, 3);
            if (w.colorField(FIELD + 19, vec, 0, 20) != 0) {
                System.arraycopy(vec, 0, edited.sunColor, 0, 3);
                changed = true;
            }
            w.endProperty();
            w.beginProperty("Exposure");
            edited.exposure = w.numberField(FIELD + 20, ls.exposure, 0.005f, 0.01f, 20, 2);
            changed |= edited.exposure != ls.exposure;
            w.endProperty();
            w.beginProperty("Environment");
            edited.environmentSpecular =
                    w.numberField(FIELD + 21, ls.environmentSpecular, 0.005f, 0, 10, 2);
            changed |= edited.environmentSpecular != ls.environmentSpecular;
            edited.environmentDiffuse =
                    w.numberField(FIELD + 22, ls.environmentDiffuse, 0.005f, 0, 10, 2);
            changed |= edited.environmentDiffuse != ls.environmentDiffuse;
            w.endProperty();
            w.beginProperty("Env map");
            if (w.textField(ENV, environment, "cube map .ktx2 (empty: none)")) {
                edited.environment = environment.toString();
                changed = true;
            }
            w.endProperty();
            w.beginProperty("Grid step");
            edited.gridStep = w.numberField(FIELD + 23, ls.gridStep, 0.01f, 0.01f, 100, 2);
            changed |= edited.gridStep != ls.gridStep;
            w.endProperty();
            if (changed) {
                push(ctx, new SetSettingsCommand(level, edited));
            }
            w.treeEnd();
        }
        if (w.treeNode(RULES, "Collision rules (first match wins)", false)) {
            w.small("layer glob · flags · surface · area");
            for (int i = 0; i < level.ruleCount() && i < ruleLayers.length; i++) {
                ui.pushId(i);
                w.row().open();
                boolean set = false;
                row.capture(level, TableCommand.Table.RULES, i);
                if (w.textField(TEXT, ruleLayers[i])) {
                    row.text = ruleLayers[i].toString();
                    set = true;
                }
                int flags = Math.round(w.numberField(FIELD, row.ints[0], 0.1f, 0, 0xFFFF, 0));
                int surface = Math.round(w.numberField(FIELD + 1, row.ints[1], 0.1f, 0, 255, 0));
                int area = Math.round(w.numberField(FIELD + 2, row.ints[2], 0.1f, 0, 63, 0));
                if (flags != row.ints[0] || surface != row.ints[1] || area != row.ints[2]) {
                    row.ints[0] = flags;
                    row.ints[1] = surface;
                    row.ints[2] = area;
                    set = true;
                }
                if (set) {
                    push(ctx, TableCommand.set(level, TableCommand.Table.RULES, i, row));
                }
                if (w.button(REMOVE, "x")) {
                    ctx.undo().push(TableCommand.remove(level, TableCommand.Table.RULES, i));
                    syncedVersion = -1;
                }
                ui.close();
                ui.popId();
            }
            if (w.button(ADD, "Add rule")) {
                row.text = "*";
                row.ints[0] = 0;
                row.ints[1] = 0;
                row.ints[2] = 0;
                row.floats = new float[0];
                ctx.undo().push(TableCommand.add(TableCommand.Table.RULES, level.ruleCount(), row));
                syncedVersion = -1;
            }
            w.treeEnd();
        }
        if (w.treeNode(SIZES, "Agent size classes", false)) {
            w.small("name · radius · height · climb");
            for (int i = 0; i < level.sizeClassCount() && i < sizeNames.length; i++) {
                ui.pushId(i);
                w.row().open();
                boolean set = false;
                row.capture(level, TableCommand.Table.SIZE_CLASSES, i);
                if (w.textField(TEXT, sizeNames[i])) {
                    row.text = sizeNames[i].toString();
                    set = true;
                }
                for (int k = 0; k < 3; k++) {
                    ui.pushId(k);
                    float v = w.numberField(FIELD, row.floats[k], 0.01f, 0.05f, 10, 2);
                    if (v != row.floats[k]) {
                        row.floats[k] = v;
                        set = true;
                    }
                    ui.popId();
                }
                if (set) {
                    push(ctx, TableCommand.set(level, TableCommand.Table.SIZE_CLASSES, i, row));
                }
                if (w.button(REMOVE, "x")) {
                    ctx.undo().push(TableCommand.remove(level, TableCommand.Table.SIZE_CLASSES, i));
                    syncedVersion = -1;
                }
                ui.close();
                ui.popId();
            }
            if (w.button(ADD, "Add size class")) {
                row.text = "large";
                row.floats = new float[] {0.8f, 2.4f, 0.5f};
                ctx.undo()
                        .push(
                                TableCommand.add(
                                        TableCommand.Table.SIZE_CLASSES,
                                        level.sizeClassCount(),
                                        row));
                syncedVersion = -1;
            }
            w.treeEnd();
        }
    }

    private void push(EditorContext ctx, dev.jeddy.editor.command.Command c) {
        ctx.undo().push(c, true);
        syncedVersion = ctx.level().version();
    }

    /** Re-reads the text buffers that mirror strings of the level (not while typing in one). */
    private void sync(LevelData level, Ui ui) {
        if (!ui.focused(ENV) && !Text.equals(environment, level.settings.environment)) {
            environment.set(level.settings.environment);
        }
        for (int i = 0; i < level.ruleCount() && i < ruleLayers.length; i++) {
            ui.pushId(i);
            boolean typing = ui.focused(TEXT);
            ui.popId();
            if (!typing && !Text.equals(ruleLayers[i], level.ruleLayer(i))) {
                ruleLayers[i].set(level.ruleLayer(i));
            }
        }
        for (int i = 0; i < level.sizeClassCount() && i < sizeNames.length; i++) {
            ui.pushId(i);
            boolean typing = ui.focused(TEXT);
            ui.popId();
            if (!typing && !Text.equals(sizeNames[i], level.sizeClassName(i))) {
                sizeNames[i].set(level.sizeClassName(i));
            }
        }
        syncedLevel = level;
        syncedVersion = level.version();
    }
}
