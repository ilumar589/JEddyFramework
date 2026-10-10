package dev.jeddy.scene;

import dev.jeddy.core.collections.SparseSet;
import dev.jeddy.core.handle.Handles;

import java.util.Arrays;

/**
 * A sparse-set component store with SoA data columns (TASKS M5-01, DESIGN §4.1): entities that have
 * the component are packed densely, and every column holds one primitive per member in the same
 * dense order, so systems iterate {@code [0, size())} over plain arrays.
 *
 * <pre>{@code
 * ComponentStore health = new ComponentStore(pool, "health");
 * FloatColumn hp = health.floatColumn("hp", 0f);
 * ...
 * float[] h = hp.data();
 * for (int i = 0, n = health.size(); i < n; i++) h[i] = Math.min(h[i] + regen, max);
 * }</pre>
 *
 * Removal swaps the last member into the hole (O(1)), so dense order is not stable.
 *
 * <p>Thread ownership: the pool's owner thread changes membership and columns; job workers may read
 * and write column elements in parallel between structural changes.
 */
public final class ComponentStore implements EntityStore {
    private final EntityPool pool;
    private final String name;
    private final SparseSet members = new SparseSet(1024, 64);
    private int[] entities = new int[64];
    private Column[] columns = new Column[4];
    private int columnCount;

    /** Creates a store and registers it with {@code pool}. */
    public ComponentStore(EntityPool pool, String name) {
        this.pool = pool;
        this.name = name;
        pool.register(this);
    }

    /** Returns the store name. */
    public String name() {
        return name;
    }

    /** Adds a {@code float} column. Add columns before members, or they start at the default. */
    public FloatColumn floatColumn(String columnName, float defaultValue) {
        return addColumn(new FloatColumn(columnName, entities.length, defaultValue));
    }

    /** Adds an {@code int} column. */
    public IntColumn intColumn(String columnName, int defaultValue) {
        return addColumn(new IntColumn(columnName, entities.length, defaultValue));
    }

    /** Adds a {@code long} column. */
    public LongColumn longColumn(String columnName, long defaultValue) {
        return addColumn(new LongColumn(columnName, entities.length, defaultValue));
    }

    private <C extends Column> C addColumn(C column) {
        if (columnCount == columns.length) {
            columns = Arrays.copyOf(columns, columnCount * 2);
        }
        for (int i = 0; i < members.size(); i++) {
            column.reset(i);
        }
        columns[columnCount++] = column;
        return column;
    }

    /** Returns the number of members. */
    public int size() {
        return members.size();
    }

    /**
     * Gives {@code entity} the component (columns at their defaults) and returns its dense index;
     * returns the existing index if it already has it.
     */
    public int add(int entity) {
        pool.check(entity);
        int index = Handles.index(entity);
        int existing = members.indexOf(index);
        if (existing >= 0) {
            return existing;
        }
        int dense = members.add(index);
        if (dense == entities.length) {
            int capacity = entities.length * 2;
            entities = Arrays.copyOf(entities, capacity);
            for (int c = 0; c < columnCount; c++) {
                columns[c].grow(capacity);
            }
        }
        entities[dense] = entity;
        for (int c = 0; c < columnCount; c++) {
            columns[c].reset(dense);
        }
        return dense;
    }

    /** Returns {@code true} if {@code entity} (alive, same generation) has the component. */
    public boolean has(int entity) {
        int dense = members.indexOf(Handles.index(entity));
        return dense >= 0 && entities[dense] == entity;
    }

    /** Returns the dense index of {@code entity}, or -1. */
    public int indexOf(int entity) {
        int dense = members.indexOf(Handles.index(entity));
        return dense >= 0 && entities[dense] == entity ? dense : -1;
    }

    /** Returns the entity at dense index {@code dense}. */
    public int entityAt(int dense) {
        return entities[dense];
    }

    /** Removes the component from {@code entity} if it has it. */
    @Override
    public void remove(int entity) {
        int index = Handles.index(entity);
        int dense = members.indexOf(index);
        if (dense < 0 || entities[dense] != entity) {
            return;
        }
        int last = members.size() - 1;
        members.remove(index);
        if (dense != last) {
            entities[dense] = entities[last];
            for (int c = 0; c < columnCount; c++) {
                columns[c].move(last, dense);
            }
        }
    }
}
