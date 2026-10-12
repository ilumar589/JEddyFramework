package dev.jeddy.editor.command;

import dev.jeddy.asset.format.LevelData;
import dev.jeddy.editor.EditorContext;

import java.util.Arrays;

/**
 * Sets one column of one component on every selected object that has the component (DESIGN §22,
 * D109); objects without the row are left alone. Each object remembers whether the column was at
 * its default (no value) so that undo removes the value again. Consecutive edits of the same column
 * merge into one history entry.
 *
 * <p>Thread ownership: the game thread.
 */
public final class SetComponentValueCommand implements Command {
    private final String component;
    private final String column;
    private final int[] ids;
    private final int[] beforeType;
    private final long[] beforeBits;
    private final String[] beforeText;
    private int type;
    private long bits;
    private String text;
    private final String label;

    /**
     * Captures {@code column} of {@code component} on {@code ids[0, n)} and will set it to the
     * given value: {@code type} is a {@code LevelData.VALUE_*}, {@code bits} the number payload,
     * {@code text} the enum constant or asset path.
     */
    public SetComponentValueCommand(
            LevelData level,
            int[] ids,
            int n,
            String component,
            String column,
            int type,
            long bits,
            String text) {
        this.component = component;
        this.column = column;
        this.ids = Arrays.copyOf(ids, n);
        this.type = type;
        this.bits = bits;
        this.text = text == null ? "" : text;
        this.label = component + "." + column;
        beforeType = new int[n];
        beforeBits = new long[n];
        beforeText = new String[n];
        for (int i = 0; i < n; i++) {
            int row = level.componentRow(ids[i], component);
            int k = row < 0 ? -1 : level.componentValue(row, column);
            beforeType[i] = k < 0 ? -1 : level.componentValueType(row, k);
            beforeBits[i] = k < 0 ? 0 : level.componentValueBits(row, k);
            beforeText[i] = k < 0 ? "" : level.componentValueText(row, k);
        }
    }

    @Override
    public void apply(EditorContext ctx) {
        LevelData level = ctx.level();
        for (int i = 0; i < ids.length; i++) {
            int row = level.componentRow(ids[i], component);
            if (row >= 0) {
                level.setComponentValue(row, column, type, bits, text);
                ctx.syncComponents(ids[i]);
            }
        }
    }

    @Override
    public void revert(EditorContext ctx) {
        LevelData level = ctx.level();
        for (int i = 0; i < ids.length; i++) {
            int row = level.componentRow(ids[i], component);
            if (row < 0) {
                continue;
            }
            if (beforeType[i] < 0) {
                level.removeComponentValue(row, column);
            } else {
                level.setComponentValue(row, column, beforeType[i], beforeBits[i], beforeText[i]);
            }
            ctx.syncComponents(ids[i]);
        }
    }

    @Override
    public String name() {
        return "Set " + label;
    }

    @Override
    public boolean mergeWith(Command next) {
        if (next instanceof SetComponentValueCommand o
                && o.label.equals(label)
                && Arrays.equals(o.ids, ids)) {
            type = o.type;
            bits = o.bits;
            text = o.text;
            return true;
        }
        return false;
    }
}
