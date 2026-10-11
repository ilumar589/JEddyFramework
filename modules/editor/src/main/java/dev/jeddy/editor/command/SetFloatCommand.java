package dev.jeddy.editor.command;

import dev.jeddy.asset.format.LevelData;
import dev.jeddy.editor.EditorContext;
import dev.jeddy.editor.LevelObjects;

import java.util.Arrays;

/**
 * Sets float {@code k} of the rows of one or more objects to the same value (a coordinate typed
 * into the Inspector, a light's range dragged). Merges with the next command on the same objects
 * and float, so a drag or typed digits make one history entry. Every change goes through {@link
 * EditorContext#syncTransform} (cheap, and floats 0–9 of a placement are its transform).
 *
 * <p>Thread ownership: the game thread.
 */
public final class SetFloatCommand implements Command {
    private final int k;
    private final int[] ids;
    private final float[] before;
    private float after;
    private final String name;

    /** Captures float {@code k} of {@code ids[0, n)}. */
    public SetFloatCommand(LevelData level, int[] ids, int n, int k, float after, String name) {
        this.k = k;
        this.ids = Arrays.copyOf(ids, n);
        this.before = new float[n];
        this.after = after;
        this.name = name;
        for (int i = 0; i < n; i++) {
            before[i] = LevelObjects.get(level, ids[i], k);
        }
    }

    @Override
    public void apply(EditorContext ctx) {
        for (int id : ids) {
            LevelObjects.set(ctx.level(), id, k, after);
            ctx.syncTransform(id);
        }
    }

    @Override
    public void revert(EditorContext ctx) {
        for (int i = 0; i < ids.length; i++) {
            LevelObjects.set(ctx.level(), ids[i], k, before[i]);
            ctx.syncTransform(ids[i]);
        }
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean mergeWith(Command next) {
        if (next instanceof SetFloatCommand s && s.k == k && Arrays.equals(ids, s.ids)) {
            after = s.after;
            return true;
        }
        return false;
    }
}
