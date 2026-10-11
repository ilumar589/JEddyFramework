package dev.jeddy.editor.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.editor.Editor;
import dev.jeddy.editor.EditorContext;
import dev.jeddy.editor.UndoStack;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/** Undoable edits of the content through the editor's history (TASKS M15-06). */
class RulesCommandsTest {
    private static EditorContext context() {
        Editor editor = new Editor();
        assertTrue(editor.attachRules(RulesSourceTest.demo()));
        return editor.context();
    }

    @Test
    void setMergesWhileTypingAndUndoRestoresTheOldValue() {
        EditorContext ctx = context();
        RulesSource s = ctx.rules();
        UndoStack undo = ctx.undo();
        int dodge = s.find("feats", "dodge");
        RulesPath name = RulesPath.of("feats", dodge, "name");
        undo.push(new RulesSetCommand(name, "Dodg"), true);
        undo.push(new RulesSetCommand(name, "Dodgy"), true);
        undo.push(new RulesSetCommand(name, "Dodgy!"), true);
        assertEquals(1, undo.size(), "merged into one entry");
        assertEquals("Dodgy!", s.get(name));
        assertEquals("Set feats[" + dodge + "].name", undo.undoName());
        undo.undo();
        assertEquals("Dodge", s.get(name));
        undo.redo();
        assertEquals("Dodgy!", s.get(name));
        // Setting an absent key and undoing removes it again.
        RulesPath minStr = RulesPath.of("feats", dodge, "minStr");
        undo.push(new RulesSetCommand(minStr, 11.0), true);
        assertEquals(11.0, s.get(minStr));
        undo.undo();
        assertNull(s.get(minStr));
        // The same path merges again, a different path does not.
        undo.push(new RulesSetCommand(name, "A"), true);
        assertEquals(1, undo.size());
        undo.push(new RulesSetCommand(minStr, 12.0), true);
        assertEquals(2, undo.size());
    }

    @Test
    void listCommandsInsertRemoveAndMoveWithUndo() {
        EditorContext ctx = context();
        RulesSource s = ctx.rules();
        UndoStack undo = ctx.undo();
        RulesPath feats = RulesPath.of("feats");
        int n = s.rows("feats").size();
        Map<String, Object> fresh =
                RulesSource.newDefinition(dev.jeddy.asset.format.RulesSchema.FEATS, "brave");
        undo.push(RulesListCommand.insert(feats, n, fresh, "Add brave"));
        assertEquals(n + 1, s.rows("feats").size());
        assertEquals(n, s.find("feats", "brave"));
        undo.undo();
        assertEquals(-1, s.find("feats", "brave"));
        undo.redo();
        assertEquals(n, s.find("feats", "brave"));
        // Removing keeps the element for undo; the same tree comes back.
        int dodge = s.find("feats", "dodge");
        Object dodgeRow = s.rows("feats").get(dodge);
        undo.push(RulesListCommand.remove(feats, dodge, "Delete dodge"));
        assertEquals(-1, s.find("feats", "dodge"));
        undo.undo();
        assertEquals(dodge, s.find("feats", "dodge"));
        assertTrue(dodgeRow == s.rows("feats").get(dodge), "the same object");
        // Moving.
        RulesPath effects = RulesPath.of("conditions", s.find("conditions", "shaken"), "effects");
        List<Object> list = s.list(effects);
        Object first = list.get(0);
        Object last = list.get(list.size() - 1);
        undo.push(RulesListCommand.move(effects, 0, list.size() - 1, "Move effect"));
        assertEquals(first, list.get(list.size() - 1));
        assertEquals(last, list.get(list.size() - 2));
        undo.undo();
        assertEquals(first, list.get(0));
        assertEquals(last, list.get(list.size() - 1));
    }
}
