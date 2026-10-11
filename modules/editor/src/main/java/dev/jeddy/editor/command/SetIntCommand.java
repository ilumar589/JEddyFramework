package dev.jeddy.editor.command;

import dev.jeddy.asset.format.LevelData;
import dev.jeddy.editor.EditorContext;
import dev.jeddy.editor.LevelObjects;

import java.util.Arrays;

/**
 * Sets an integer field (flags, collision override, prefab, light type) of one or more objects.
 * Flag changes reach the world ({@link EditorContext#syncFlags}); a prefab change re-instantiates
 * the placement ({@link EditorContext#syncPrefab}).
 *
 * <p>Thread ownership: the game thread.
 */
public final class SetIntCommand implements Command {
    private final ObjectField field;
    private final int[] ids;
    private final int[] before;
    private int after;

    /** Captures the current values of {@code field} on {@code ids[0, n)}. */
    public SetIntCommand(LevelData level, int[] ids, int n, ObjectField field, int after) {
        this.field = field;
        this.ids = Arrays.copyOf(ids, n);
        this.before = new int[n];
        this.after = after;
        for (int i = 0; i < n; i++) {
            int id = ids[i];
            before[i] = field.getInt(level, level.kindOf(id), LevelObjects.index(level, id));
        }
    }

    /** Makes a command setting or clearing {@code bit} in the flags of {@code ids[0, n)}. */
    public static SetIntCommand flag(LevelData level, int[] ids, int n, int bit, boolean set) {
        SetIntCommand c = new SetIntCommand(level, ids, n, ObjectField.FLAGS, 0);
        c.perObjectBit = bit;
        c.perObjectSet = set;
        return c;
    }

    private int perObjectBit; // when non-zero, "after" is before with this bit set or cleared
    private boolean perObjectSet;

    @Override
    public void apply(EditorContext ctx) {
        LevelData level = ctx.level();
        for (int i = 0; i < ids.length; i++) {
            int v =
                    perObjectBit == 0
                            ? after
                            : perObjectSet ? before[i] | perObjectBit : before[i] & ~perObjectBit;
            write(ctx, level, ids[i], v);
        }
    }

    @Override
    public void revert(EditorContext ctx) {
        LevelData level = ctx.level();
        for (int i = 0; i < ids.length; i++) {
            write(ctx, level, ids[i], before[i]);
        }
    }

    private void write(EditorContext ctx, LevelData level, int id, int v) {
        field.setInt(level, level.kindOf(id), LevelObjects.index(level, id), v);
        if (field == ObjectField.FLAGS) {
            ctx.syncFlags(id);
        } else if (field == ObjectField.PREFAB) {
            ctx.syncPrefab(id);
        }
    }

    @Override
    public String name() {
        return "Set " + field.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }

    @Override
    public boolean mergeWith(Command next) {
        if (next instanceof SetIntCommand s
                && s.field == field
                && perObjectBit == 0
                && s.perObjectBit == 0
                && Arrays.equals(ids, s.ids)) {
            after = s.after;
            return true;
        }
        return false;
    }
}
