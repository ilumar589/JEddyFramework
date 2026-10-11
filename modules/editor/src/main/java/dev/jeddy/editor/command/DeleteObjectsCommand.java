package dev.jeddy.editor.command;

import dev.jeddy.asset.format.LevelData;
import dev.jeddy.editor.EditorContext;

import java.util.Arrays;
import java.util.Comparator;

/**
 * Removes objects of any kind. The rows are captured when the command is made and removed in
 * descending index order, so undo can put them back in ascending order and every row lands at the
 * index it had (D89).
 *
 * <p>Thread ownership: the game thread.
 */
public final class DeleteObjectsCommand implements Command {
    private final ObjectRow[] rows;
    private final String name;

    /** Captures {@code ids[0, n)} from {@code level}. */
    public DeleteObjectsCommand(LevelData level, int[] ids, int n) {
        rows = new ObjectRow[n];
        for (int i = 0; i < n; i++) {
            rows[i] = new ObjectRow().capture(level, ids[i]);
        }
        // Ascending by (kind, index): inserting in this order restores every index.
        Arrays.sort(
                rows,
                Comparator.<ObjectRow>comparingInt(r -> r.kind).thenComparingInt(r -> r.index));
        name = n == 1 ? "Delete " + rows[0].name : "Delete " + n + " objects";
    }

    /** Returns the number of objects removed. */
    public int count() {
        return rows.length;
    }

    @Override
    public void apply(EditorContext ctx) {
        for (int i = rows.length - 1; i >= 0; i--) {
            rows[i].remove(ctx.level(), ctx.loader());
        }
    }

    @Override
    public void revert(EditorContext ctx) {
        for (ObjectRow r : rows) {
            r.insert(ctx.level(), ctx.loader());
        }
    }

    @Override
    public String name() {
        return name;
    }
}
