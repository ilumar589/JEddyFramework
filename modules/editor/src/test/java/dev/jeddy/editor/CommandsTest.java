package dev.jeddy.editor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.format.LevelData;
import dev.jeddy.core.handle.Handles;
import dev.jeddy.editor.command.AddObjectsCommand;
import dev.jeddy.editor.command.ComponentRowCommand;
import dev.jeddy.editor.command.DeleteObjectsCommand;
import dev.jeddy.editor.command.ObjectField;
import dev.jeddy.editor.command.SetComponentValueCommand;
import dev.jeddy.editor.command.SetFloatCommand;
import dev.jeddy.editor.command.SetIntCommand;
import dev.jeddy.editor.command.SetSettingsCommand;
import dev.jeddy.editor.command.SetStringCommand;
import dev.jeddy.editor.command.TableCommand;
import dev.jeddy.editor.command.TransformCommand;
import dev.jeddy.render.RenderPacket;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * Every command applied, undone and redone against a level (TASKS M14-05): the level ends byte for
 * byte where it started after undo, and the world follows placements through the loader.
 */
class CommandsTest {
    @TempDir Path dir;

    private static int[] ids(int... ids) {
        return ids;
    }

    /** Applies, undoes, redoes and undoes again; the bytes must match before and after. */
    private static void roundTrip(
            EditorContext ctx, dev.jeddy.editor.command.Command c, Runnable checkApplied) {
        byte[] before = ctx.level().toBytes();
        ctx.undo().push(c);
        checkApplied.run();
        byte[] applied = ctx.level().toBytes();
        assertFalse(java.util.Arrays.equals(before, applied), "the command changed the level");
        ctx.undo().undo();
        assertArrayEquals(before, ctx.level().toBytes(), "undo restores the level exactly");
        ctx.undo().redo();
        assertArrayEquals(applied, ctx.level().toBytes(), "redo reapplies it exactly");
        checkApplied.run();
        ctx.undo().undo();
        assertArrayEquals(before, ctx.level().toBytes());
    }

    @Test
    void componentRowsAreCommandsAndFollowDeletesAndDuplicates() {
        LevelData level = WorldFixture.level();
        EditorContext ctx = new EditorContext(level, 16);
        int wall = level.placementId(0);
        int table = level.placementId(1);
        int[] synced = new int[1];
        ctx.setSchema(null, (l, id) -> synced[0]++);
        // Add a component to two objects, set a value, remove it.
        roundTrip(
                ctx,
                ComponentRowCommand.add(level, ids(wall, table), 2, "Health"),
                () -> {
                    assertTrue(level.componentRow(wall, "Health") >= 0);
                    assertTrue(level.componentRow(table, "Health") >= 0);
                    assertEquals(2, level.componentRowCount());
                });
        assertEquals(0, level.componentRowCount(), "undone");
        ctx.undo().push(ComponentRowCommand.add(level, ids(wall), 1, "Health"));
        assertEquals(1, level.componentRowCount());
        roundTrip(
                ctx,
                new SetComponentValueCommand(
                        level,
                        ids(wall, table),
                        2,
                        "Health",
                        "current",
                        LevelData.VALUE_INT,
                        7,
                        ""),
                () -> {
                    int row = level.componentRow(wall, "Health");
                    assertEquals(
                            7, level.componentValueBits(row, level.componentValue(row, "current")));
                    assertEquals(-1, level.componentRow(table, "Health"), "no row, nothing set");
                });
        // Merging: two edits of the same column make one entry.
        ctx.undo().redo(); // back to the applied state (the round trip ends undone)
        int entries = ctx.undo().size();
        ctx.undo()
                .push(
                        new SetComponentValueCommand(
                                level,
                                ids(wall),
                                1,
                                "Health",
                                "current",
                                LevelData.VALUE_INT,
                                8,
                                ""),
                        true);
        ctx.undo()
                .push(
                        new SetComponentValueCommand(
                                level,
                                ids(wall),
                                1,
                                "Health",
                                "current",
                                LevelData.VALUE_INT,
                                9,
                                ""),
                        true);
        assertEquals(entries + 1, ctx.undo().size());
        ctx.undo().undo();
        assertEquals(
                7,
                level.componentValueBits(level.componentRow(wall, "Health"), 0),
                "back to the value before the merged pair");
        ctx.undo().redo();
        assertEquals(9, level.componentValueBits(level.componentRow(wall, "Health"), 0));
        // Removing a row remembers its values.
        roundTrip(
                ctx,
                ComponentRowCommand.remove(level, ids(wall), 1, "Health"),
                () -> assertEquals(-1, level.componentRow(wall, "Health")));
        assertEquals(
                9,
                level.componentValueBits(level.componentRow(wall, "Health"), 0),
                "restored with its value");
        // Deleting the object takes the row along and undo brings both back; a duplicate copies.
        roundTrip(
                ctx,
                new DeleteObjectsCommand(level, ids(wall), 1),
                () -> assertEquals(0, level.componentRowCount()));
        assertEquals(9, level.componentValueBits(level.componentRow(wall, "Health"), 0));
        AddObjectsCommand dup = AddObjectsCommand.duplicate(level, ids(wall), 1, 1, 0, 0);
        roundTrip(
                ctx,
                dup,
                () -> {
                    assertEquals(2, level.componentRowCount());
                    int row = level.componentRow(dup.id(0), "Health");
                    assertTrue(row >= 0, "the copy has the component");
                    assertEquals(9, level.componentValueBits(row, 0));
                });
        assertTrue(synced[0] > 0, "the game was told about the changes");
    }

    @Test
    void transformMovesRowsOfEveryKind() {
        LevelData level = WorldFixture.level();
        EditorContext ctx = new EditorContext(level, 16);
        int wall = level.placementId(0);
        int torch = level.lightId(0);
        int spawn = level.spawnId(0);
        TransformCommand move = new TransformCommand(level, ids(wall, torch, spawn), 3, "Move");
        assertFalse(move.changed());
        for (int i = 0; i < 3; i++) {
            move.setAfter(i, 0, move.before()[move.offset(i)] + 2.5f);
        }
        assertTrue(move.changed());
        roundTrip(
                ctx,
                move,
                () -> {
                    assertEquals(6.5f, level.placementFloat(0, 0));
                    assertEquals(3.5f, level.lightFloat(0, 0));
                    assertEquals(0.5f, level.spawnFloat(0, 0));
                });
        // A drag keeps editing the same pushed command.
        ctx.undo().push(move);
        move.setAfter(0, 1, 7);
        move.apply(ctx);
        assertEquals(7, level.placementFloat(0, 1));
        assertEquals(1, ctx.undo().size());
        // Merging folds a later transform of the same objects into the entry.
        TransformCommand more = new TransformCommand(level, ids(wall, torch, spawn), 3, "Move");
        more.setAfter(0, 2, 9);
        ctx.undo().push(more, true);
        assertEquals(1, ctx.undo().size());
        ctx.undo().undo();
        assertEquals(0, level.placementFloat(0, 2));
        assertEquals(4, level.placementFloat(0, 0), "back to the very start");
    }

    @Test
    void addDeleteAndDuplicateKeepIdsAndIndices() {
        LevelData level = WorldFixture.level();
        EditorContext ctx = new EditorContext(level, 16);
        int wall = level.placementId(0);
        int table = level.placementId(1);
        int torch = level.lightId(0);
        int door = level.triggerId(0);
        // Delete the first placement and the trigger: the table shifts to index 0 and back.
        roundTrip(
                ctx,
                new DeleteObjectsCommand(level, ids(wall, door), 2),
                () -> {
                    assertEquals(1, level.placementCount());
                    assertEquals(table, level.placementId(0));
                    assertEquals(0, level.triggerCount());
                    assertEquals(LevelData.KIND_NONE, level.kindOf(wall));
                });
        assertEquals(0, level.placementIndex(wall), "the wall is back at index 0");
        assertEquals(1, level.placementIndex(table));
        // Add a placement, a light, a spawn and a trigger.
        int nextId = level.nextId();
        AddObjectsCommand add =
                AddObjectsCommand.placement(level, 1, "table 2", "prop", 0, 1, 2, 3);
        roundTrip(
                ctx,
                add,
                () -> {
                    assertEquals(3, level.placementCount());
                    assertEquals(nextId, level.placementId(2));
                    assertEquals("table 2", level.placementName(2));
                    assertEquals(1, level.placementFloat(2, 0));
                    assertEquals(1, level.placementFloat(2, 6), "identity rotation");
                    assertEquals(1, level.placementFloat(2, 9), "unit scale");
                });
        assertEquals(nextId, add.id(0), "the id is allocated once and kept through undo/redo");
        roundTrip(
                ctx,
                AddObjectsCommand.pointLight(level, "lamp", 0, 3, 0, 1, 1, 1, 5, 6, 0),
                () -> assertEquals(2, level.lightCount()));
        roundTrip(
                ctx,
                AddObjectsCommand.spawn(level, "enemy 1", "enemy", 5, 0, 5, 1),
                () -> assertEquals("enemy", level.spawnKind(1)));
        roundTrip(
                ctx,
                AddObjectsCommand.trigger(level, "t", "area", "x", 0, 0, 0, 0, 1, 1, 1),
                () -> assertEquals(2, level.triggerCount()));
        // Duplicate the wall and the torch one meter along +X: copies get new ids and names.
        AddObjectsCommand dup = AddObjectsCommand.duplicate(level, ids(wall, torch), 2, 1, 0, 0);
        roundTrip(
                ctx,
                dup,
                () -> {
                    assertEquals(3, level.placementCount());
                    assertEquals("wall 1 copy", level.placementName(2));
                    assertEquals(5, level.placementFloat(2, 0));
                    assertEquals(2, level.lightCount());
                    assertEquals(2, level.lightFloat(1, 0));
                    assertNotEquals(wall, dup.id(0));
                    assertEquals(LevelData.KIND_LIGHT, level.kindOf(dup.id(1)));
                });
    }

    @Test
    void fieldCommandsSetAndMerge() {
        LevelData level = WorldFixture.level();
        EditorContext ctx = new EditorContext(level, 16);
        int wall = level.placementId(0);
        int table = level.placementId(1);
        int spawn = level.spawnId(0);
        roundTrip(
                ctx,
                new SetStringCommand(level, ids(wall, table), 2, ObjectField.LAYER, "decor"),
                () -> {
                    assertEquals("decor", level.placementLayer(0));
                    assertEquals("decor", level.placementLayer(1));
                });
        roundTrip(
                ctx,
                new SetStringCommand(level, ids(spawn), 1, ObjectField.KIND, "enemy"),
                () -> assertEquals("enemy", level.spawnKind(0)));
        roundTrip(
                ctx,
                SetIntCommand.flag(level, ids(wall, table), 2, LevelData.PLACEMENT_HIDDEN, true),
                () -> {
                    assertEquals(
                            LevelData.PLACEMENT_STATIC | LevelData.PLACEMENT_HIDDEN,
                            level.placementFlags(0),
                            "the wall keeps its other flags");
                    assertEquals(LevelData.PLACEMENT_HIDDEN, level.placementFlags(1));
                });
        roundTrip(
                ctx,
                new SetIntCommand(level, ids(wall), 1, ObjectField.COLLISION, 7),
                () -> assertEquals(7, level.placementCollision(0)));
        // Typing digits: the merged entry undoes to the first "before".
        ctx.undo().push(new SetFloatCommand(level, ids(wall), 1, 0, 1, "x"));
        ctx.undo().push(new SetFloatCommand(level, ids(wall), 1, 0, 12, "x"), true);
        ctx.undo().push(new SetFloatCommand(level, ids(wall), 1, 0, 123, "x"), true);
        assertEquals(123, level.placementFloat(0, 0));
        assertEquals(1, ctx.undo().size());
        ctx.undo().undo();
        assertEquals(4, level.placementFloat(0, 0));
        // A different float does not merge.
        ctx.undo().push(new SetFloatCommand(level, ids(wall), 1, 0, 1, "x"));
        ctx.undo().push(new SetFloatCommand(level, ids(wall), 1, 1, 2, "y"), true);
        assertEquals(2, ctx.undo().size());
    }

    @Test
    void settingsAndTablesRoundTrip() {
        LevelData level = WorldFixture.level();
        EditorContext ctx = new EditorContext(level, 16);
        LevelData.Settings s = new LevelData.Settings();
        s.copyFrom(level.settings);
        s.gridStep = 2;
        s.exposure = 0.5f;
        s.environment = "env/sky.ktx2";
        roundTrip(
                ctx,
                new SetSettingsCommand(level, s),
                () -> {
                    assertEquals(2, level.settings.gridStep);
                    assertEquals("env/sky.ktx2", level.settings.environment);
                });
        // Rules keep their order: insert a rule in front, remove the middle one, change one.
        TableCommand.Row r = new TableCommand.Row();
        r.text = "floor*";
        r.ints[0] = 1;
        r.ints[1] = 1;
        roundTrip(
                ctx,
                TableCommand.add(TableCommand.Table.RULES, 0, r),
                () -> {
                    assertEquals(3, level.ruleCount());
                    assertEquals("floor*", level.ruleLayer(0));
                    assertEquals("wall", level.ruleLayer(1));
                });
        roundTrip(
                ctx,
                TableCommand.remove(level, TableCommand.Table.RULES, 0),
                () -> {
                    assertEquals(1, level.ruleCount());
                    assertEquals("*", level.ruleLayer(0));
                });
        r.text = "prop";
        r.ints[0] = 20;
        roundTrip(
                ctx,
                TableCommand.set(level, TableCommand.Table.RULES, 1, r),
                () -> assertEquals("prop", level.ruleLayer(1)));
        // Size classes, areas and links.
        TableCommand.Row size = new TableCommand.Row();
        size.text = "large";
        size.floats = new float[] {0.8f, 2.5f, 0.6f};
        roundTrip(
                ctx,
                TableCommand.add(TableCommand.Table.SIZE_CLASSES, 1, size),
                () -> assertEquals("large", level.sizeClassName(1)));
        TableCommand.Row area = new TableCommand.Row();
        area.ints[0] = 5;
        area.floats = new float[] {-1, 1, 0, 0, 4, 0, 4, 4, 0, 4};
        roundTrip(
                ctx,
                TableCommand.add(TableCommand.Table.AREAS, 0, area),
                () -> {
                    assertEquals(1, level.areaCount());
                    assertEquals(4, level.areaPolygon(0).length / 2);
                });
        TableCommand.Row link = new TableCommand.Row();
        link.ints[0] = LevelData.LINK_BIDIRECTIONAL;
        link.floats = new float[] {0, 0, 0, 2, 1, 0, 0.5f};
        roundTrip(
                ctx,
                TableCommand.add(TableCommand.Table.LINKS, 0, link),
                () -> assertEquals(2, level.linkFloat(0, 3)));
    }

    @Test
    void placementsEditedHereMoveInTheWorld() throws Exception {
        try (WorldFixture f = new WorldFixture(dir)) {
            Editor editor = new Editor();
            editor.attach(f.world, f.loader);
            editor.open(f.level, null);
            EditorContext ctx = editor.context();
            LevelData level = f.level;
            int wall = level.placementId(0);
            int table = level.placementId(1);
            int wallRoot = f.loader.rootOf(wall);
            RenderPacket p = new RenderPacket(16);
            // Move the wall to x = 10: its renderable follows.
            TransformCommand move = new TransformCommand(level, ids(wall), 1, "Move");
            move.setAfter(0, 0, 10);
            editor.push(move, false);
            f.world.updateTransforms(f.jobs);
            p.reset();
            f.world.extract(p);
            assertEquals(10, p.currWorld[3], 1e-5);
            editor.undo();
            f.world.updateTransforms(f.jobs);
            p.reset();
            f.world.extract(p);
            assertEquals(4, p.currWorld[3], 1e-5);
            // Delete the table: its two renderables go; undo brings them back under a new root.
            ctx.selection().select(table);
            editor.deleteSelection();
            assertEquals(1, f.world.renderableCount());
            assertTrue(ctx.selection().isEmpty());
            editor.undo();
            f.settle();
            assertEquals(3, f.world.renderableCount());
            assertNotEquals(Handles.NULL, f.loader.rootOf(table));
            assertEquals(table, f.loader.placementOf(f.loader.rootOf(table)));
            // Duplicate the wall: a third group appears at x = 4 + grid step.
            ctx.selection().select(wall);
            ctx.settings().translateStep = 1;
            editor.duplicateSelection();
            f.settle();
            assertEquals(4, f.world.renderableCount());
            int copy = ctx.selection().primary();
            assertNotEquals(wall, copy);
            assertEquals(5, LevelObjects.get(level, copy, 0));
            assertNotEquals(Handles.NULL, f.loader.rootOf(copy));
            // Hiding through a flag command reaches the world: the static instance disappears.
            editor.push(
                    SetIntCommand.flag(level, ids(wall), 1, LevelData.PLACEMENT_HIDDEN, true),
                    false);
            f.world.updateTransforms(f.jobs);
            p.reset();
            int visible = f.world.extract(p);
            assertEquals(3, visible, "the hidden wall is not extracted");
            editor.undo();
            f.world.updateTransforms(f.jobs);
            p.reset();
            assertEquals(4, f.world.extract(p));
            assertEquals(wallRoot, f.loader.rootOf(wall), "the wall kept its entity throughout");
        }
    }
}
