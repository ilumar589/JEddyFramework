package dev.jeddy.editor;

import dev.jeddy.core.collections.IntList;
import dev.jeddy.core.collections.IntSet;

/**
 * The set of selected level objects (DESIGN §19): object ids of any kind (placements, lights,
 * spawns, triggers), in selection order, with a <em>primary</em> (the last one clicked: the gizmo
 * sits on it and the Inspector leads with it). Ids, never indices, so the selection survives rows
 * being removed and reinserted by undo.
 *
 * <p>{@link #version()} changes with every edit, so panels can rebuild their caches lazily.
 *
 * <p>Thread ownership: the game thread.
 */
public final class Selection {
    private final IntList ids = new IntList(32);
    private final IntSet set = new IntSet(32);
    private int primary = -1;
    private int version;

    /** Creates an empty selection. */
    public Selection() {}

    /** Returns the number of selected objects. */
    public int size() {
        return ids.size();
    }

    /** Returns whether nothing is selected. */
    public boolean isEmpty() {
        return ids.isEmpty();
    }

    /** Returns the {@code i}th selected id in selection order. */
    public int get(int i) {
        return ids.get(i);
    }

    /** Returns the selected ids as a list (not to be modified). */
    public IntList ids() {
        return ids;
    }

    /** Returns whether {@code id} is selected. */
    public boolean contains(int id) {
        return set.contains(id);
    }

    /** Returns the primary id, or -1. */
    public int primary() {
        return primary;
    }

    /** Returns a counter that changes with every modification. */
    public int version() {
        return version;
    }

    /** Selects only {@code id}. */
    public void select(int id) {
        if (ids.size() == 1 && primary == id) {
            return;
        }
        clear();
        add(id);
    }

    /** Adds {@code id} and makes it primary. */
    public void add(int id) {
        if (set.add(id)) {
            ids.add(id);
        }
        primary = id;
        version++;
    }

    /** Removes {@code id}; the primary becomes the last remaining id. */
    public void remove(int id) {
        if (!set.remove(id)) {
            return;
        }
        ids.removeAt(ids.indexOf(id));
        if (primary == id) {
            primary = ids.isEmpty() ? -1 : ids.get(ids.size() - 1);
        }
        version++;
    }

    /** Adds {@code id} if absent, else removes it (Ctrl+click). */
    public void toggle(int id) {
        if (set.contains(id)) {
            remove(id);
        } else {
            add(id);
        }
    }

    /** Deselects everything. */
    public void clear() {
        if (ids.isEmpty()) {
            return;
        }
        ids.clear();
        set.clear();
        primary = -1;
        version++;
    }

    /** Replaces the selection with {@code newIds[0, count)}; the last one becomes primary. */
    public void set(int[] newIds, int count) {
        clear();
        for (int i = 0; i < count; i++) {
            add(newIds[i]);
        }
    }

    /** Makes {@code id} the primary (it must be selected). */
    public void setPrimary(int id) {
        if (set.contains(id) && primary != id) {
            primary = id;
            version++;
        }
    }
}
