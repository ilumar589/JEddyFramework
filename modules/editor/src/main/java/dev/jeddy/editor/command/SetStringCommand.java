package dev.jeddy.editor.command;

import dev.jeddy.asset.format.LevelData;
import dev.jeddy.editor.EditorContext;
import dev.jeddy.editor.LevelObjects;

import java.util.Arrays;

/**
 * Sets a string field (name, layer, kind, payload) of one or more objects to the same value. Merges
 * with the next command on the same objects and field, so typing a name is one history entry.
 *
 * <p>Thread ownership: the game thread.
 */
public final class SetStringCommand implements Command {
    private final ObjectField field;
    private final int[] ids;
    private final String[] before;
    private String after;

    /** Captures the current values of {@code field} on {@code ids[0, n)}. */
    public SetStringCommand(LevelData level, int[] ids, int n, ObjectField field, String after) {
        this.field = field;
        this.ids = Arrays.copyOf(ids, n);
        this.before = new String[n];
        this.after = after;
        for (int i = 0; i < n; i++) {
            int id = ids[i];
            before[i] = field.getString(level, level.kindOf(id), LevelObjects.index(level, id));
        }
    }

    @Override
    public void apply(EditorContext ctx) {
        LevelData level = ctx.level();
        for (int id : ids) {
            field.setString(level, level.kindOf(id), LevelObjects.index(level, id), after);
        }
    }

    @Override
    public void revert(EditorContext ctx) {
        LevelData level = ctx.level();
        for (int i = 0; i < ids.length; i++) {
            int id = ids[i];
            field.setString(level, level.kindOf(id), LevelObjects.index(level, id), before[i]);
        }
    }

    @Override
    public String name() {
        return "Set " + field.name().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public boolean mergeWith(Command next) {
        if (next instanceof SetStringCommand s && s.field == field && Arrays.equals(ids, s.ids)) {
            after = s.after;
            return true;
        }
        return false;
    }
}
