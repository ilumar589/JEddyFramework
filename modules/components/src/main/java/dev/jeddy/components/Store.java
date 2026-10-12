package dev.jeddy.components;

import dev.jeddy.core.collections.SparseSet;
import dev.jeddy.core.handle.Handles;
import dev.jeddy.core.io.ByteReader;
import dev.jeddy.core.io.ByteWriter;
import dev.jeddy.core.log.Log;
import dev.jeddy.core.log.Logger;
import dev.jeddy.scene.EntityPool;
import dev.jeddy.scene.EntityStore;

import java.util.Arrays;

/**
 * The base of every generated component store (DESIGN §22, D105): a sparse set from entity to a
 * dense row, the entity handle per row, and the growth and swap-remove bookkeeping. The generated
 * subclass holds one primitive array per column in the same dense order and implements the column
 * hooks; views read those arrays directly.
 *
 * <p>Rows are packed: removal moves the last row into the hole, so dense order is not stable and a
 * loop that removes the row it is visiting must walk backwards (the generated loops do).
 *
 * <p>Columns are the scalar expansion of the record's components (a {@code Vec3} is three {@code
 * float} columns {@code name.x}, {@code name.y}, {@code name.z}; an enum is an {@code int}), and
 * the generic column API ({@link #columnIndex}, {@link #getInt}, {@link #setFloat}, ...) is what
 * the level loader, the editor and the snapshots use: cold paths, by name, without reflection.
 *
 * <p>Thread ownership: the pool's owner thread (the game thread) changes membership; job threads
 * read and write rows of disjoint ranges between structural changes.
 */
public abstract class Store implements EntityStore {
    private static final Logger LOG = Log.logger("components");

    /** Column types, as {@link #columnType} reports them. */
    public static final int INT = 0;

    public static final int LONG = 1;
    public static final int FLOAT = 2;
    public static final int BOOL = 3;

    /**
     * The snapshot header's type for an {@code @EntityRef} column ({@link #columnType} still says
     * {@link #INT}): its values are 64-bit stable ids in the stream, not ints.
     */
    public static final int ENTITY = 4;

    private final EntityPool pool;
    private final String name;
    private final SparseSet members;
    private int[] entities;
    private int capacity;

    /**
     * Creates an empty store for {@code name} registered with {@code pool}, with room for {@code
     * capacity} rows (it grows past it, allocating). The subclass allocates its columns for the
     * same capacity.
     */
    @SuppressWarnings("this-escape") // registration only stores the reference; no call back
    protected Store(EntityPool pool, String name, int capacity) {
        this.pool = pool;
        this.name = name;
        this.capacity = Math.max(1, capacity);
        members = new SparseSet(Math.max(64, pool.highWaterMark()), this.capacity);
        entities = new int[this.capacity];
        pool.register(this);
    }

    /** The component's simple name. */
    public final String name() {
        return name;
    }

    /** The pool the entities come from. */
    public final EntityPool pool() {
        return pool;
    }

    /** Rows in use. */
    public final int size() {
        return members.size();
    }

    /** Rows allocated; {@link #add} past it grows the columns. */
    public final int capacity() {
        return capacity;
    }

    /** The entity at dense row {@code row}. */
    public final int entityAt(int row) {
        return entities[row];
    }

    /** The dense row of {@code entity}, or -1 (a stale handle is not a member). */
    public final int indexOf(int entity) {
        int row = members.indexOf(Handles.index(entity));
        return row >= 0 && entities[row] == entity ? row : -1;
    }

    /** Whether {@code entity} has the component. */
    public final boolean has(int entity) {
        return indexOf(entity) >= 0;
    }

    /**
     * Gives {@code entity} the component with every column at its default and returns its row; an
     * entity that has it keeps its row and values. Throws for a dead entity.
     */
    public final int add(int entity) {
        if (!pool.isAlive(entity)) {
            throw new IllegalArgumentException(
                    "stale or invalid entity " + Handles.toString(entity) + " for " + name);
        }
        int index = Handles.index(entity);
        int existing = members.indexOf(index);
        if (existing >= 0 && entities[existing] == entity) {
            return existing;
        }
        int row = members.add(index);
        if (row == capacity) {
            capacity *= 2;
            entities = Arrays.copyOf(entities, capacity);
            growColumns(capacity);
        }
        entities[row] = entity;
        resetRow(row);
        return row;
    }

    /** Takes the component from {@code entity} if it has it (the last row fills the hole). */
    @Override
    public final void remove(int entity) {
        int row = indexOf(entity);
        if (row < 0) {
            return;
        }
        int last = members.size() - 1;
        members.remove(Handles.index(entity));
        if (row != last) {
            entities[row] = entities[last];
            moveRow(last, row);
        }
    }

    /** Removes every row. */
    public final void clear() {
        members.clear();
    }

    /** Picks the smaller store: the generated loops drive from it. */
    public static Store smaller(Store a, Store b) {
        return a.size() <= b.size() ? a : b;
    }

    // ---- Hooks the generated store implements -----------------------------------------------

    /** Reallocates every column to {@code capacity} rows, keeping the values. */
    protected abstract void growColumns(int capacity);

    /** Copies row {@code from} over row {@code to} in every column. */
    protected abstract void moveRow(int from, int to);

    /** Sets every column of {@code row} to its default. */
    protected abstract void resetRow(int row);

    // ---- The generic column API (cold paths: loading, editing, snapshots) -----------------------

    /** Scalar columns. */
    public abstract int columnCount();

    /** The column's name: the record component's, or {@code name.x} for a vector's parts. */
    public abstract String columnName(int column);

    /** {@link #INT}, {@link #LONG}, {@link #FLOAT} or {@link #BOOL}. */
    public abstract int columnType(int column);

    /** The column named {@code name}, or -1. */
    public abstract int columnIndex(String name);

    /**
     * For an enum column, the ordinal of the constant named {@code name}, or -1; -1 for other
     * columns. Levels and snapshots store enums by name so that reordering constants is safe.
     */
    public abstract int enumOrdinal(int column, String name);

    /** For an enum column, the name of ordinal {@code ordinal}; {@code null} for other columns. */
    public abstract String enumName(int column, int ordinal);

    /** For an {@code @AssetRef} column, its asset kind; {@code null} for other columns. */
    public abstract String assetKind(int column);

    /** Whether {@code column} is an {@code @EntityRef}: a handle a save translates (D114). */
    public abstract boolean isEntityRef(int column);

    public abstract int getInt(int row, int column);

    public abstract void setInt(int row, int column, int value);

    public abstract long getLong(int row, int column);

    public abstract void setLong(int row, int column, long value);

    public abstract float getFloat(int row, int column);

    public abstract void setFloat(int row, int column, float value);

    public abstract boolean getBool(int row, int column);

    public abstract void setBool(int row, int column, boolean value);

    // ---- Snapshots (D111, D114) ---------------------------------------------------------------

    /**
     * Writes the store: its name, its columns by name and type, the entity of each row as a stable
     * id from {@code ids}, and the columns, with every {@code @EntityRef} column as ids too.
     * Allocates nothing when the writer's buffer is large enough (a save-time path).
     */
    public final void write(ByteWriter w, EntityIds ids) {
        w.string(name);
        int columns = columnCount();
        w.int32(columns);
        for (int c = 0; c < columns; c++) {
            w.string(columnName(c));
            w.int32(isEntityRef(c) ? ENTITY : columnType(c));
        }
        int n = size();
        w.int32(n);
        for (int i = 0; i < n; i++) {
            w.int64(ids.idOf(entities[i]));
        }
        for (int c = 0; c < columns; c++) {
            switch (columnType(c)) {
                case INT -> {
                    if (isEntityRef(c)) {
                        for (int i = 0; i < n; i++) {
                            w.int64(ids.idOf(getInt(i, c)));
                        }
                    } else {
                        for (int i = 0; i < n; i++) {
                            w.int32(getInt(i, c));
                        }
                    }
                }
                case LONG -> {
                    for (int i = 0; i < n; i++) {
                        w.int64(getLong(i, c));
                    }
                }
                case FLOAT -> {
                    for (int i = 0; i < n; i++) {
                        w.float32(getFloat(i, c));
                    }
                }
                default -> {
                    for (int i = 0; i < n; i++) {
                        w.bool(getBool(i, c));
                    }
                }
            }
        }
    }

    /** {@link #write(ByteWriter, EntityIds)} with handles as ids (one run of the program). */
    public final void write(ByteWriter w) {
        write(w, EntityIds.HANDLES);
    }

    /**
     * Reads a store written by {@link #write} into this one, replacing its rows: columns are
     * matched by name (a stored column this store lacks is skipped, a column the file lacks keeps
     * its default, a column that changed type keeps its default), each stored entity id is resolved
     * through {@code ids} and a row whose id resolves to nothing is dropped with a warning. The
     * name must match; {@link ByteReader#peekString} lets the caller route a store by name first.
     * Allocates (a load-time path).
     *
     * @throws IllegalStateException if the name is another store's
     */
    public final void read(ByteReader r, EntityIds ids) {
        String stored = r.string();
        if (!stored.equals(name)) {
            throw new IllegalStateException("snapshot has " + stored + ", store is " + name);
        }
        int columns = r.int32();
        int[] local = new int[columns];
        int[] types = new int[columns];
        for (int c = 0; c < columns; c++) {
            local[c] = columnIndex(r.string());
            types[c] = r.int32();
        }
        clear();
        int n = r.int32();
        int[] rows = new int[n]; // -1 for a dropped row
        int dropped = 0;
        for (int i = 0; i < n; i++) {
            long id = r.int64();
            int e = id == EntityIds.NONE ? 0 : ids.entityOf(id);
            if (e != 0 && pool.isAlive(e)) {
                rows[i] = add(e);
            } else {
                rows[i] = -1;
                dropped++;
            }
        }
        if (dropped > 0) {
            LOG.warn(name + ": " + dropped + " of " + n + " saved rows name no entity; dropped");
        }
        for (int c = 0; c < columns; c++) {
            int to = local[c];
            // The stored type must be the column's: an int column that became a reference (or
            // the reverse) and any other change of type keeps its default.
            if (to >= 0 && types[c] != (isEntityRef(to) ? ENTITY : columnType(to))) {
                to = -1;
            }
            switch (types[c]) {
                case INT -> {
                    for (int i = 0; i < n; i++) {
                        int v = r.int32();
                        if (to >= 0 && rows[i] >= 0) {
                            setInt(rows[i], to, v);
                        }
                    }
                }
                case ENTITY -> {
                    for (int i = 0; i < n; i++) {
                        long id = r.int64();
                        if (to >= 0 && rows[i] >= 0) {
                            setInt(rows[i], to, id == EntityIds.NONE ? 0 : ids.entityOf(id));
                        }
                    }
                }
                case LONG -> {
                    for (int i = 0; i < n; i++) {
                        long v = r.int64();
                        if (to >= 0 && rows[i] >= 0) {
                            setLong(rows[i], to, v);
                        }
                    }
                }
                case FLOAT -> {
                    for (int i = 0; i < n; i++) {
                        float v = r.float32();
                        if (to >= 0 && rows[i] >= 0) {
                            setFloat(rows[i], to, v);
                        }
                    }
                }
                default -> {
                    for (int i = 0; i < n; i++) {
                        boolean v = r.bool();
                        if (to >= 0 && rows[i] >= 0) {
                            setBool(rows[i], to, v);
                        }
                    }
                }
            }
        }
    }

    /** {@link #read(ByteReader, EntityIds)} with handles as ids (one run of the program). */
    public final void read(ByteReader r) {
        read(r, EntityIds.HANDLES);
    }

    /**
     * Skips one store in a snapshot (a component this build no longer has) and returns its name.
     */
    public static String skip(ByteReader r) {
        String stored = r.string();
        int columns = r.int32();
        int[] types = new int[columns];
        for (int c = 0; c < columns; c++) {
            r.string();
            types[c] = r.int32();
        }
        int n = r.int32();
        r.skip(8 * n);
        for (int c = 0; c < columns; c++) {
            switch (types[c]) {
                case INT, FLOAT -> r.skip(4 * n);
                case LONG, ENTITY -> r.skip(8 * n);
                default -> r.skip(n);
            }
        }
        return stored;
    }

    @Override
    public String toString() {
        return name + "[" + size() + "/" + capacity + "]";
    }
}
