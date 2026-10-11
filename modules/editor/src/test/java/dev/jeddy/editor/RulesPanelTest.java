package dev.jeddy.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.format.RulesSchema;
import dev.jeddy.editor.panel.Dock;
import dev.jeddy.editor.panel.RulesPanel;
import dev.jeddy.editor.rules.RulesPath;
import dev.jeddy.editor.rules.RulesSource;
import dev.jeddy.testing.Allocations;
import dev.jeddy.ui.Theme;
import dev.jeddy.ui.Ui;
import dev.jeddy.ui.UiDrawList;
import dev.jeddy.ui.UiInput;
import dev.jeddy.ui.Widgets;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The Rules panel over the demo's content (TASKS M15-06): the schema-generated grid drives merged
 * commands, add and delete go through the history, the panel allocates nothing per frame, and
 * saving writes the files.
 */
class RulesPanelTest {
    @TempDir Path dir;
    private final Ui ui = new Ui();
    private final Theme theme = Theme.dark();
    private final Widgets w = new Widgets(ui, theme);
    private final UiInput in = new UiInput();
    private final UiDrawList out = new UiDrawList(8192, 64);

    RulesPanelTest() {
        in.width = 1280;
        in.height = 1400;
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

    private static long id(Object... parts) {
        long scope = 0;
        for (Object p : parts) {
            long v = p instanceof Integer i ? i + 0x100000001L : (Long) p;
            scope = Ui.combine(scope, v);
        }
        return scope;
    }

    private void click(long id, Editor editor) {
        int r = ui.lastRecord(id);
        assertTrue(r >= 0, "widget declared: " + id);
        in.mouseX = ui.recordX(r) + ui.recordWidth(r) * 0.5f;
        in.mouseY = ui.recordY(r) + ui.recordHeight(r) * 0.5f;
        frame(editor);
        in.buttonsPressed = UiInput.LEFT;
        in.buttonsDown = UiInput.LEFT;
        frame(editor);
        in.buttonsDown = 0;
        in.buttonsReleased = UiInput.LEFT;
        frame(editor);
        frame(editor);
    }

    private Editor editor() {
        Path demo = Path.of("../../samples/iso-demo/assets/rules");
        Assumptions.assumeTrue(
                Files.isDirectory(demo), "the demo's rules are not next to this build");
        Editor editor = new Editor();
        editor.open(WorldFixture.level(), null);
        editor.setShown(true);
        assertTrue(editor.attachRules(demo));
        editor.dock().setLayout("left=300:Outliner|right=400:Rules|bottom=150:");
        return editor;
    }

    private static RulesPanel panel(Editor editor) {
        for (var p : editor.dock().panels()) {
            if (p instanceof RulesPanel r) {
                return r;
            }
        }
        throw new AssertionError("no Rules panel");
    }

    @Test
    void theGridEditsThroughMergedCommands() {
        Editor editor = editor();
        RulesSource rules = editor.context().rules();
        RulesPanel panel = panel(editor);
        int feats = 0;
        for (int i = 0; i < RulesSchema.KINDS.size(); i++) {
            if (RulesSchema.KINDS.get(i) == RulesSchema.FEATS) {
                break;
            }
            if (RulesSchema.KINDS.get(i).hasId()) {
                feats++;
            }
        }
        panel.setKindIndex(feats);
        int dodge = rules.find("feats", "dodge");
        panel.select(dodge);
        frame(editor);
        frame(editor);
        // The Rules panel is panel 5 in the right zone; the selected row scopes the grid, the
        // effects field scopes its operations, operation 0's value is a number field (23).
        int effectsField = RulesSchema.FEATS.require("effects");
        long value = id(Dock.RIGHT, 5, dodge, effectsField, 0, 23L);
        int r = ui.lastRecord(value);
        assertTrue(r >= 0, "the value field of dodge's effect was declared");
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
        RulesPath path = RulesPath.of("feats", dodge, "effects", 0, "value");
        assertEquals(5.0, rules.get(path), "dragged 40 px at 0.1 per px from 1");
        assertEquals(1, editor.context().undo().size(), "one merged entry");
        assertTrue(rules.isDirty());
        editor.undo();
        assertEquals(1.0, rules.get(path));
        assertFalse(rules.isDirty());
        // The flag checkbox of a condition: 'prone' gains 'cannotAct'.
        panel.setKindIndex(0);
        int prone = rules.find("conditions", "prone");
        panel.select(prone);
        frame(editor);
        frame(editor);
        int flagsField = RulesSchema.CONDITIONS.require("flags");
        int cannotAct = RulesSchema.CONDITIONS.fields().get(flagsField).enumIndex("cannotAct");
        click(id(Dock.RIGHT, 5, prone, flagsField, 40L + cannotAct), editor);
        Object flags = rules.get(RulesPath.of("conditions", prone, "flags"));
        assertTrue(
                flags instanceof java.util.List<?> l
                        && l.contains("cannotAct")
                        && l.contains("prone"),
                String.valueOf(flags));
        editor.undo();
        flags = rules.get(RulesPath.of("conditions", prone, "flags"));
        assertTrue(flags instanceof java.util.List<?> l && !l.contains("cannotAct"));
    }

    @Test
    void addDuplicateDeleteAndSave() throws Exception {
        Editor editor = editor();
        RulesSource rules = editor.context().rules();
        RulesPanel panel = panel(editor);
        panel.setKindIndex(0); // conditions
        frame(editor);
        frame(editor);
        int n = rules.rows("conditions").size();
        click(id(Dock.RIGHT, 5, 4L), editor); // Add
        assertEquals(n + 1, rules.rows("conditions").size());
        assertEquals(n, panel.selected());
        assertEquals("newConditions", rules.id("conditions", n));
        click(id(Dock.RIGHT, 5, 5L), editor); // Duplicate
        assertEquals(n + 2, rules.rows("conditions").size());
        assertEquals("newConditions2", rules.id("conditions", n + 1));
        click(id(Dock.RIGHT, 5, 6L), editor); // Delete
        assertEquals(n + 1, rules.rows("conditions").size());
        assertEquals(3, editor.context().undo().size());
        editor.undo();
        editor.undo();
        editor.undo();
        assertEquals(n, rules.rows("conditions").size());
        // Save writes the files where they were loaded from: redirect to the temp dir first.
        rules.save(dir);
        panel.select(0);
        frame(editor);
        click(id(Dock.RIGHT, 5, 4L), editor); // Add
        assertTrue(rules.isDirty());
        click(id(Dock.RIGHT, 5, 7L), editor); // Save
        assertFalse(rules.isDirty());
        String conditions = Files.readString(dir.resolve("conditions.json"));
        assertTrue(conditions.contains("\"id\": \"newConditions\""), conditions);
    }

    @Test
    void thePanelDrawsWithoutAllocating() {
        Editor editor = editor();
        RulesSource rules = editor.context().rules();
        RulesPanel panel = panel(editor);
        // Monsters: nested attacks, a reference, flags-free; the richest grid.
        int monsters = 0;
        for (var k : RulesSchema.KINDS) {
            if (k == RulesSchema.MONSTERS) {
                break;
            }
            if (k.hasId()) {
                monsters++;
            }
        }
        panel.setKindIndex(monsters);
        panel.select(rules.find("monsters", "skeletalWarrior"));
        for (int i = 0; i < 5; i++) {
            frame(editor);
        }
        assertTrue(out.commandCount() > 0);
        Allocations.assertNoAllocation(
                "rules panel frame",
                () -> {
                    for (int i = 0; i < 10; i++) {
                        frame(editor);
                    }
                });
    }
}
