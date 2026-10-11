package dev.jeddy.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.format.LevelData;
import dev.jeddy.editor.command.Command;

import org.junit.jupiter.api.Test;

/** The history: apply, undo, redo, merging, the saved mark, capacity, cancel (TASKS M14-05). */
class UndoStackTest {
    /** A command adding {@code delta} to a counter; merges with another Add. */
    static final class Add implements Command {
        final int[] counter;
        int delta;

        Add(int[] counter, int delta) {
            this.counter = counter;
            this.delta = delta;
        }

        @Override
        public void apply(EditorContext ctx) {
            counter[0] += delta;
        }

        @Override
        public void revert(EditorContext ctx) {
            counter[0] -= delta;
        }

        @Override
        public String name() {
            return "Add " + delta;
        }

        @Override
        public boolean mergeWith(Command next) {
            if (next instanceof Add a) {
                delta += a.delta;
                return true;
            }
            return false;
        }
    }

    private final int[] counter = new int[1];
    private final EditorContext ctx = new EditorContext(new LevelData(), 4);
    private final UndoStack stack = ctx.undo();

    @Test
    void appliesUndoesAndRedoesInOrder() {
        stack.push(new Add(counter, 1));
        stack.push(new Add(counter, 10));
        assertEquals(11, counter[0]);
        assertEquals("Add 10", stack.undoName());
        assertNull(stack.redoName());
        assertTrue(stack.undo());
        assertEquals(1, counter[0]);
        assertEquals("Add 10", stack.redoName());
        assertTrue(stack.undo());
        assertEquals(0, counter[0]);
        assertFalse(stack.undo());
        assertTrue(stack.redo());
        assertTrue(stack.redo());
        assertFalse(stack.redo());
        assertEquals(11, counter[0]);
        // A new command after an undo discards the redo tail.
        stack.undo();
        stack.push(new Add(counter, 100));
        assertEquals(101, counter[0]);
        assertFalse(stack.canRedo());
        assertEquals(2, stack.size());
    }

    @Test
    void mergesContinuousEditsIntoOneEntry() {
        stack.push(new Add(counter, 1));
        stack.push(new Add(counter, 2), true);
        stack.push(new Add(counter, 3), true);
        assertEquals(6, counter[0]);
        assertEquals(1, stack.size(), "three steps, one entry");
        stack.undo();
        assertEquals(0, counter[0]);
        // Without merge they stay separate.
        stack.push(new Add(counter, 1));
        stack.push(new Add(counter, 2));
        assertEquals(2, stack.size());
    }

    @Test
    void tracksTheSavedState() {
        assertFalse(stack.isDirty());
        stack.push(new Add(counter, 1));
        assertTrue(stack.isDirty());
        stack.markSaved();
        assertFalse(stack.isDirty());
        stack.undo();
        assertTrue(stack.isDirty(), "undoing past the save is a change");
        stack.redo();
        assertFalse(stack.isDirty());
        // A merge never crosses the saved mark: the new step is its own entry.
        stack.push(new Add(counter, 5), true);
        assertEquals(2, stack.size());
        assertTrue(stack.isDirty());
        stack.undo();
        assertFalse(stack.isDirty());
        // A new branch after the saved state makes it unreachable.
        stack.undo();
        stack.push(new Add(counter, 7));
        assertTrue(stack.isDirty());
        stack.undo();
        assertTrue(stack.isDirty(), "the saved state is gone");
    }

    @Test
    void forgetsTheOldestPastCapacity() {
        for (int i = 1; i <= 6; i++) {
            stack.push(new Add(counter, i));
        }
        assertEquals(4, stack.size());
        assertEquals(21, counter[0]);
        stack.seek(0);
        assertEquals(1 + 2, counter[0], "the two oldest cannot be undone");
        stack.seek(4);
        assertEquals(21, counter[0]);
    }

    @Test
    void cancelRevertsAndForgets() {
        stack.push(new Add(counter, 1));
        stack.push(new Add(counter, 2));
        stack.cancelTop();
        assertEquals(1, counter[0]);
        assertEquals(1, stack.size());
        assertFalse(stack.canRedo());
    }
}
