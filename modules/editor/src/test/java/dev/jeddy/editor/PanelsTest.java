package dev.jeddy.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.format.LevelData;
import dev.jeddy.editor.panel.Dock;
import dev.jeddy.render.RenderPacket;
import dev.jeddy.testing.Allocations;
import dev.jeddy.ui.Theme;
import dev.jeddy.ui.Ui;
import dev.jeddy.ui.UiDrawList;
import dev.jeddy.ui.UiInput;
import dev.jeddy.ui.Widgets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * The dock and the panels declared into a UI (TASKS M14-07): the viewport region left free, the
 * layout string, every panel drawing over a live world without allocating, the console commands,
 * and an Inspector field driving a merged command.
 */
class PanelsTest {
    @TempDir Path dir;
    private final Ui ui = new Ui();
    private final Theme theme = Theme.dark();
    private final Widgets w = new Widgets(ui, theme);
    private final UiInput in = new UiInput();
    private final UiDrawList out = new UiDrawList(4096, 64);

    PanelsTest() {
        in.width = 1280;
        in.height = 800;
        in.dt = 1f / 60;
        ui.defaultFontSize = 10;
        theme.fontSize = 10;
        w.applyTheme();
    }

    private void frame(Editor editor) {
        ui.beginFrame(in);
        editor.declare(ui, w);
        ui.endFrame(out);
        in.clearEvents();
    }

    /** Folds id parts the way nested {@code pushId}s do; ints are list indices. */
    private static long id(Object... parts) {
        long scope = 0;
        for (Object p : parts) {
            long v = p instanceof Integer i ? i + 0x100000001L : (Long) p;
            scope = Ui.combine(scope, v);
        }
        return scope;
    }

    @Test
    void dockLeavesTheViewportRegionAndPersistsItsLayout() {
        Editor editor = new Editor();
        editor.open(WorldFixture.level(), null);
        editor.setShown(true);
        Dock dock = editor.dock();
        frame(editor);
        float split = theme.splitterWidth;
        assertEquals(dock.size(Dock.LEFT) + split, dock.regionX(), 1e-4);
        assertEquals(theme.widgetHeight, dock.regionY(), 1e-4);
        assertEquals(
                1280 - dock.size(Dock.LEFT) - dock.size(Dock.RIGHT) - 2 * split,
                dock.regionW(),
                1e-4);
        assertEquals(
                800 - theme.widgetHeight - dock.size(Dock.BOTTOM) - split, dock.regionH(), 1e-4);
        String layout = dock.layout();
        assertTrue(
                layout.startsWith(
                        "left=300:Outliner,Assets|right=340:Inspector,Settings|bottom=220:Console"),
                layout);
        dock.setLayout("left=250:Inspector|right=400:Outliner,History|bottom=150:");
        frame(editor);
        assertEquals(250, dock.size(Dock.LEFT), 1e-4);
        assertEquals(Dock.LEFT, dock.zone(2), "the Inspector moved left");
        assertEquals(Dock.RIGHT, dock.zone(0));
        assertEquals(Dock.HIDDEN, dock.zone(6), "the Console is hidden");
        assertEquals(1280 - 250 - 400 - 2 * split, dock.regionW(), 1e-4);
        assertEquals(800 - theme.widgetHeight, dock.regionH(), 1e-4, "no bottom zone");
        assertEquals("left=250:Inspector|right=400:Outliner,History|bottom=150:", dock.layout());
        // Toggling shows a hidden panel in its last zone, and hides a shown one.
        dock.toggle(6);
        assertEquals(Dock.BOTTOM, dock.zone(6));
        dock.toggle(6);
        assertEquals(Dock.HIDDEN, dock.zone(6));
        // Hidden: nothing is declared.
        editor.setShown(false);
        ui.beginFrame(in);
        editor.declare(ui, w);
        ui.endFrame(out);
        assertEquals(0, out.commandCount());
    }

    @Test
    void everyPanelDrawsOverALiveWorldWithoutAllocating() throws Exception {
        try (WorldFixture f = new WorldFixture(dir)) {
            Editor editor = new Editor();
            editor.attach(f.world, f.loader);
            editor.attachAssets(f.vfs, f.scenes);
            editor.open(f.level, dir.resolve("levels/test.jlevel"));
            editor.setShown(true);
            editor.context().selection().select(f.level.placementId(0));
            editor.context().console().info("hello");
            // Show every panel somewhere.
            editor.dock()
                    .setLayout(
                            "left=300:Outliner,Assets,Entities|right=340:Inspector,Settings,History|bottom=220:Console");
            Dock dock = editor.dock();
            // Cycle the tabs so each panel draws at least once (tabs are per zone).
            for (int round = 0; round < 3; round++) {
                for (int i = 0; i < dock.panels().size(); i++) {
                    if (dock.zone(i) != Dock.HIDDEN) {
                        dock.setZone(i, dock.zone(i)); // makes it the visible tab
                        frame(editor);
                    }
                }
            }
            assertTrue(out.commandCount() > 0);
            RenderPacket p = new RenderPacket(16);
            ViewportInput idle = new ViewportInput();
            idle.windowWidth = 1280;
            idle.windowHeight = 800;
            for (int i = 0; i < 3; i++) {
                editor.viewport().update(idle, 1f / 60);
                frame(editor);
                p.reset();
                editor.extract(p);
            }
            Allocations.assertNoAllocation(
                    "editor panels frame",
                    () -> {
                        for (int i = 0; i < 10; i++) {
                            editor.viewport().update(idle, 1f / 60);
                            frame(editor);
                            p.reset();
                            editor.extract(p);
                        }
                    });
        }
    }

    @Test
    void consoleCommandsDriveTheEditor() throws Exception {
        try (WorldFixture f = new WorldFixture(dir)) {
            Editor editor = new Editor();
            editor.attach(f.world, f.loader);
            editor.open(f.level, dir.resolve("levels/test.jlevel"));
            EditorContext ctx = editor.context();
            int wall = f.level.placementId(0);
            int torch = f.level.lightId(0);
            editor.runCommand("select " + wall + " " + torch);
            assertEquals(2, ctx.selection().size());
            editor.runCommand("snap 0.25");
            assertEquals(0.25f, ctx.settings().translateStep);
            editor.runCommand("goto " + torch);
            assertEquals(torch, ctx.selection().primary());
            assertEquals(1, ctx.camera().target.x, 1e-4);
            editor.runCommand("delete");
            assertEquals(LevelData.KIND_NONE, f.level.kindOf(torch));
            editor.undo();
            assertEquals(LevelData.KIND_NONE != f.level.kindOf(torch), true);
            int lines = ctx.console().size();
            editor.runCommand("nonsense");
            assertEquals(lines + 1, ctx.console().size());
            assertTrue(ctx.console().line(ctx.console().size() - 1).startsWith("unknown command"));
            editor.runCommand("stats");
            assertTrue(ctx.console().line(ctx.console().size() - 1).contains("placements"));
            editor.runCommand("save");
            for (int i = 0; i < 200 && editor.isSaving(); i++) {
                Thread.sleep(5);
            }
            assertFalse(editor.isSaving());
            assertTrue(java.nio.file.Files.exists(dir.resolve("levels/test.jlevel")));
            LevelData back = LevelFiles.read(dir.resolve("levels/test.jlevel"));
            assertEquals(f.level.placementCount(), back.placementCount());
            editor.runCommand("dump");
            assertTrue(java.nio.file.Files.exists(dir.resolve("levels/test.json")));
        }
    }

    @Test
    void inspectorShowsComponentRowsFromTheSchema() {
        Editor editor = new Editor();
        LevelData level = WorldFixture.level();
        dev.jeddy.asset.format.ComponentSchema schema =
                dev.jeddy.asset.format.ComponentSchema.parse(
                        "{\"version\": 1, \"registry\": \"t.Game\", \"components\": [{\"name\":"
                            + " \"Health\", \"type\": \"t.Health\", \"doc\": \"Hit points\","
                            + " \"fields\": [{\"name\": \"current\", \"type\": \"int\","
                            + " \"default\": 10},{\"name\": \"team\", \"type\": \"enum\","
                            + " \"values\": [\"A\", \"B\"], \"default\": \"B\"},{\"name\": \"pos\","
                            + " \"type\": \"vec3\", \"default\": [1, 2, 3]},{\"name\": \"owner\","
                            + " \"type\": \"entity\"},{\"name\": \"sound\", \"type\": \"asset\","
                            + " \"asset\": \"sound\"},{\"name\": \"angle\", \"type\": \"float\","
                            + " \"angle\": true},{\"name\": \"on\", \"type\": \"bool\","
                            + " \"default\": true}]},{\"name\": \"Dead\", \"type\": \"t.Dead\","
                            + " \"fields\": []}], \"behaviours\": []}");
        int[] synced = new int[1];
        editor.attachSchema(schema, (l, id) -> synced[0]++);
        editor.open(level, null);
        editor.setShown(true);
        in.height = 1600; // room for every row of the inspector
        int wall = level.placementId(0);
        int row = level.addComponentRow(wall, "Health");
        level.setComponentInt(row, "current", 3);
        level.addComponentRow(wall, "Dead");
        editor.context().selection().select(wall);
        frame(editor);
        frame(editor);
        assertTrue(out.commandCount() > 0);
        // Both rows draw their fields (the "current" number field of row 0, field 0), and the
        // "Add component" dropdown is absent: the wall has every component of the schema.
        long current = id(Dock.RIGHT, 2, 0, 0, 20L);
        assertTrue(ui.lastRecord(current) >= 0, "the current field was declared");
        assertTrue(ui.lastRecord(id(Dock.RIGHT, 2, 22L)) < 0, "nothing left to add");
        // Removing Dead through its "x" button is a command; afterwards the dropdown appears.
        long remove = id(Dock.RIGHT, 2, 1, 21L);
        int r = ui.lastRecord(remove);
        assertTrue(r >= 0, "the remove button of row 1 was declared");
        assertTrue(ui.recordY(r) < in.height, "visible: y " + ui.recordY(r));
        click(
                editor,
                ui.recordX(r) + ui.recordWidth(r) * 0.5f,
                ui.recordY(r) + ui.recordHeight(r) * 0.5f);
        frame(editor);
        assertEquals(-1, level.componentRow(wall, "Dead"));
        assertEquals("Remove Dead", editor.context().undo().undoName());
        assertEquals(1, synced[0]);
        frame(editor);
        assertTrue(ui.lastRecord(id(Dock.RIGHT, 2, 22L)) >= 0, "Dead can be added again");
        editor.undo();
        frame(editor);
        assertTrue(level.componentRow(wall, "Dead") >= 0);
    }

    private void click(Editor editor, float x, float y) {
        in.mouseX = x;
        in.mouseY = y;
        frame(editor);
        in.buttonsPressed = UiInput.LEFT;
        in.buttonsDown = UiInput.LEFT;
        frame(editor);
        in.buttonsDown = 0;
        in.buttonsReleased = UiInput.LEFT;
        frame(editor);
    }

    @Test
    void inspectorFieldsPushMergedCommands() {
        Editor editor = new Editor();
        LevelData level = WorldFixture.level();
        editor.open(level, null);
        editor.setShown(true);
        int wall = level.placementId(0);
        editor.context().selection().select(wall);
        frame(editor);
        frame(editor);
        // The Inspector is panel 2 in the right zone; its Position row (8) holds a vector field
        // whose X component (0) is a numberField with the FIELD id.
        long x = id(Dock.RIGHT, 2, 8L, 0, 0xF1E1DL);
        int r = ui.lastRecord(x);
        assertTrue(r >= 0, "the position x field was declared");
        float cx = ui.recordX(r) + ui.recordWidth(r) * 0.5f;
        float cy = ui.recordY(r) + ui.recordHeight(r) * 0.5f;
        in.mouseX = cx;
        in.mouseY = cy;
        frame(editor);
        in.buttonsPressed = UiInput.LEFT;
        in.buttonsDown = UiInput.LEFT;
        frame(editor);
        in.mouseX = cx + 20;
        frame(editor);
        in.mouseX = cx + 40;
        frame(editor);
        in.buttonsDown = 0;
        in.buttonsReleased = UiInput.LEFT;
        frame(editor);
        assertEquals(4 + 40 * 0.05f, level.placementFloat(0, 0), 1e-3, "dragged 40 px at 0.05/px");
        assertEquals(1, editor.context().undo().size(), "one merged entry");
        assertEquals("Move", editor.context().undo().undoName());
        editor.undo();
        assertEquals(4, level.placementFloat(0, 0), 1e-5);
    }
}
