package dev.jeddy.editor.command;

import dev.jeddy.asset.format.LevelData;
import dev.jeddy.editor.EditorContext;

import java.util.Arrays;

/**
 * Adds a component row to, or removes one from, one or more objects (DESIGN §22, D109). A removal
 * keeps the row's values so that undo restores them; an addition starts every column at the
 * component's default. Changes reach the running game through {@link EditorContext#syncComponents}.
 *
 * <p>Thread ownership: the game thread.
 */
public final class ComponentRowCommand implements Command {
    private final String component;
    private final int[] ids;
    private final boolean add;

    /** Per object, the saved values of a removed row (null when the object had no row). */
    private final String[][] columns;

    private final int[][] types;
    private final long[][] bits;
    private final String[][] texts;

    private ComponentRowCommand(LevelData level, int[] ids, int n, String component, boolean add) {
        this.component = component;
        this.ids = Arrays.copyOf(ids, n);
        this.add = add;
        columns = new String[n][];
        types = new int[n][];
        bits = new long[n][];
        texts = new String[n][];
        for (int i = 0; i < n; i++) {
            int row = level.componentRow(ids[i], component);
            if (row >= 0) {
                int count = level.componentValueCount(row);
                columns[i] = new String[count];
                types[i] = new int[count];
                bits[i] = new long[count];
                texts[i] = new String[count];
                for (int k = 0; k < count; k++) {
                    columns[i][k] = level.componentValueColumn(row, k);
                    types[i][k] = level.componentValueType(row, k);
                    bits[i][k] = level.componentValueBits(row, k);
                    texts[i][k] = level.componentValueText(row, k);
                }
            }
        }
    }

    /** Adds {@code component} to {@code ids[0, n)} (objects that have it keep their row). */
    public static ComponentRowCommand add(LevelData level, int[] ids, int n, String component) {
        return new ComponentRowCommand(level, ids, n, component, true);
    }

    /** Removes {@code component} from {@code ids[0, n)}. */
    public static ComponentRowCommand remove(LevelData level, int[] ids, int n, String component) {
        return new ComponentRowCommand(level, ids, n, component, false);
    }

    @Override
    public void apply(EditorContext ctx) {
        LevelData level = ctx.level();
        for (int i = 0; i < ids.length; i++) {
            if (add) {
                level.addComponentRow(ids[i], component);
            } else {
                int row = level.componentRow(ids[i], component);
                if (row >= 0) {
                    level.removeComponentRow(row);
                }
            }
            ctx.syncComponents(ids[i]);
        }
    }

    @Override
    public void revert(EditorContext ctx) {
        LevelData level = ctx.level();
        for (int i = 0; i < ids.length; i++) {
            if (add) {
                if (columns[i] == null) {
                    int row = level.componentRow(ids[i], component);
                    if (row >= 0) {
                        level.removeComponentRow(row);
                    }
                }
            } else if (columns[i] != null) {
                int row = level.addComponentRow(ids[i], component);
                for (int k = 0; k < columns[i].length; k++) {
                    level.setComponentValue(
                            row, columns[i][k], types[i][k], bits[i][k], texts[i][k]);
                }
            }
            ctx.syncComponents(ids[i]);
        }
    }

    @Override
    public String name() {
        return (add ? "Add " : "Remove ") + component;
    }
}
