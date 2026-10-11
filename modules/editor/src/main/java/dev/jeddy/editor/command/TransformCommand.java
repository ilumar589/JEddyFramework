package dev.jeddy.editor.command;

import dev.jeddy.asset.format.LevelData;
import dev.jeddy.editor.EditorContext;
import dev.jeddy.editor.LevelObjects;

import java.util.Arrays;

/**
 * Changes the float rows (position, rotation, scale, light direction, trigger extents ...) of one
 * or more objects (D89). Created at the start of a gizmo drag with {@code before == after}, pushed
 * once, then {@link #setAfter} and {@link #apply} are called every frame of the drag so the same
 * history entry follows the mouse; a nudge from the keyboard or a typed value makes one in one go.
 *
 * <p>Rows are stored back to back in {@link #before} and {@link #after}; {@link #offset} gives
 * object {@code i}'s row start, {@link #floats} its length.
 *
 * <p>Thread ownership: the game thread.
 */
public final class TransformCommand implements Command {
    private final int[] ids;
    private final int[] offset;
    private final int[] floats;
    private final float[] before;
    private final float[] after;
    private final String name;

    /** Captures the rows of {@code ids[0, n)} from {@code level} as both before and after. */
    public TransformCommand(LevelData level, int[] ids, int n, String name) {
        this.ids = Arrays.copyOf(ids, n);
        this.offset = new int[n];
        this.floats = new int[n];
        this.name = name;
        int total = 0;
        for (int i = 0; i < n; i++) {
            offset[i] = total;
            floats[i] = LevelObjects.floats(level.kindOf(ids[i]));
            total += floats[i];
        }
        before = new float[total];
        after = new float[total];
        for (int i = 0; i < n; i++) {
            LevelObjects.copyRow(level, ids[i], before, offset[i]);
        }
        System.arraycopy(before, 0, after, 0, total);
    }

    /** Returns the number of objects. */
    public int count() {
        return ids.length;
    }

    /** Returns object {@code i}'s id. */
    public int id(int i) {
        return ids[i];
    }

    /** Returns where object {@code i}'s row starts in {@link #before} and {@link #after}. */
    public int offset(int i) {
        return offset[i];
    }

    /** Returns the length of object {@code i}'s row. */
    public int floats(int i) {
        return floats[i];
    }

    /** The rows before the edit (read-only). */
    public float[] before() {
        return before;
    }

    /** The rows after the edit: a drag writes here, then calls {@link #apply}. */
    public float[] after() {
        return after;
    }

    /** Sets float {@code k} of object {@code i}'s after row. */
    public void setAfter(int i, int k, float v) {
        after[offset[i] + k] = v;
    }

    /** Returns whether any value changed (a click without a drag leaves nothing to keep). */
    public boolean changed() {
        return !Arrays.equals(before, after);
    }

    @Override
    public void apply(EditorContext ctx) {
        write(ctx, after);
    }

    @Override
    public void revert(EditorContext ctx) {
        write(ctx, before);
    }

    private void write(EditorContext ctx, float[] rows) {
        LevelData level = ctx.level();
        for (int i = 0; i < ids.length; i++) {
            LevelObjects.writeRow(level, ids[i], rows, offset[i]);
            ctx.syncTransform(ids[i]);
        }
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean mergeWith(Command next) {
        if (!(next instanceof TransformCommand t) || !Arrays.equals(ids, t.ids)) {
            return false;
        }
        System.arraycopy(t.after, 0, after, 0, after.length);
        return true;
    }
}
