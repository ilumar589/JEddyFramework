package dev.jeddy.editor.command;

import dev.jeddy.asset.format.LevelData;
import dev.jeddy.editor.EditorContext;

import java.util.Arrays;

/**
 * Adds, removes or replaces a row of one of the level's small tables that have no object ids:
 * collision rules, agent size classes, navigation area volumes and off-mesh links. Rows are
 * addressed by index (rules match in order, so the index is meaningful) and carried as a {@link
 * Row}: a text, up to three integers and the floats, laid out per {@link Table}.
 *
 * <p>Thread ownership: the game thread.
 */
public final class TableCommand implements Command {
    /** The tables. */
    public enum Table {
        /** text = layer glob; ints = flags, surface, area. */
        RULES,
        /** text = name; floats = radius, height, climb. */
        SIZE_CLASSES,
        /** ints[0] = area; floats = yMin, yMax, then x, z per polygon point. */
        AREAS,
        /** ints = flags, area, user id; floats = start xyz, end xyz, radius. */
        LINKS
    }

    /** The operations. */
    public enum Op {
        ADD,
        REMOVE,
        SET
    }

    /** One row of any table. */
    public static final class Row {
        /** Creates an empty row. */
        public Row() {}

        public String text = "";
        public final int[] ints = new int[3];
        public float[] floats = new float[0];

        /** Copies row {@code index} of {@code table}. */
        public Row capture(LevelData level, Table table, int index) {
            switch (table) {
                case RULES -> {
                    text = level.ruleLayer(index);
                    ints[0] = level.ruleFlags(index);
                    ints[1] = level.ruleSurface(index);
                    ints[2] = level.ruleArea(index);
                    floats = new float[0];
                }
                case SIZE_CLASSES -> {
                    text = level.sizeClassName(index);
                    floats = new float[3];
                    for (int k = 0; k < 3; k++) {
                        floats[k] = level.sizeClassFloat(index, k);
                    }
                }
                case AREAS -> {
                    ints[0] = level.areaArea(index);
                    float[] poly = level.areaPolygon(index);
                    floats = new float[2 + poly.length];
                    floats[0] = level.areaYMin(index);
                    floats[1] = level.areaYMax(index);
                    System.arraycopy(poly, 0, floats, 2, poly.length);
                }
                case LINKS -> {
                    ints[0] = level.linkFlags(index);
                    ints[1] = level.linkArea(index);
                    ints[2] = level.linkUserId(index);
                    floats = new float[LevelData.LINK_FLOATS];
                    for (int k = 0; k < floats.length; k++) {
                        floats[k] = level.linkFloat(index, k);
                    }
                }
            }
            return this;
        }

        /** Copies {@code o}. */
        public Row copyOf(Row o) {
            text = o.text;
            System.arraycopy(o.ints, 0, ints, 0, 3);
            floats = o.floats.clone();
            return this;
        }

        private float[] polygon() {
            return Arrays.copyOfRange(floats, 2, floats.length);
        }

        void insert(LevelData level, Table table, int index) {
            switch (table) {
                case RULES -> level.insertRule(index, text, ints[0], ints[1], ints[2]);
                case SIZE_CLASSES ->
                        level.insertSizeClass(index, text, floats[0], floats[1], floats[2]);
                case AREAS -> level.insertArea(index, ints[0], floats[0], floats[1], polygon());
                case LINKS ->
                        level.insertLink(
                                index, floats[0], floats[1], floats[2], floats[3], floats[4],
                                floats[5], floats[6], ints[0], ints[1], ints[2]);
            }
        }

        void write(LevelData level, Table table, int index) {
            switch (table) {
                case RULES -> level.setRule(index, text, ints[0], ints[1], ints[2]);
                case SIZE_CLASSES ->
                        level.setSizeClass(index, text, floats[0], floats[1], floats[2]);
                case AREAS -> {
                    level.setArea(index, ints[0], floats[0], floats[1]);
                    level.setAreaPolygon(index, polygon());
                }
                case LINKS -> {
                    level.setLink(index, ints[0], ints[1], ints[2]);
                    for (int k = 0; k < LevelData.LINK_FLOATS; k++) {
                        level.setLinkFloat(index, k, floats[k]);
                    }
                }
            }
        }

        static void remove(LevelData level, Table table, int index) {
            switch (table) {
                case RULES -> level.removeRule(index);
                case SIZE_CLASSES -> level.removeSizeClass(index);
                case AREAS -> level.removeArea(index);
                case LINKS -> level.removeLink(index);
            }
        }
    }

    private final Table table;
    private final Op op;
    private final int index;
    private final Row before; // REMOVE and SET
    private final Row after; // ADD and SET

    private TableCommand(Table table, Op op, int index, Row before, Row after) {
        this.table = table;
        this.op = op;
        this.index = index;
        this.before = before;
        this.after = after;
    }

    /** Inserts {@code row} at {@code index} of {@code table}. */
    public static TableCommand add(Table table, int index, Row row) {
        return new TableCommand(table, Op.ADD, index, null, new Row().copyOf(row));
    }

    /** Removes row {@code index} of {@code table}. */
    public static TableCommand remove(LevelData level, Table table, int index) {
        return new TableCommand(
                table, Op.REMOVE, index, new Row().capture(level, table, index), null);
    }

    /** Replaces row {@code index} of {@code table} with {@code row}. */
    public static TableCommand set(LevelData level, Table table, int index, Row row) {
        return new TableCommand(
                table,
                Op.SET,
                index,
                new Row().capture(level, table, index),
                new Row().copyOf(row));
    }

    /** Returns the table. */
    public Table table() {
        return table;
    }

    /** Returns the row index. */
    public int index() {
        return index;
    }

    @Override
    public void apply(EditorContext ctx) {
        switch (op) {
            case ADD -> after.insert(ctx.level(), table, index);
            case REMOVE -> Row.remove(ctx.level(), table, index);
            case SET -> after.write(ctx.level(), table, index);
        }
    }

    @Override
    public void revert(EditorContext ctx) {
        switch (op) {
            case ADD -> Row.remove(ctx.level(), table, index);
            case REMOVE -> before.insert(ctx.level(), table, index);
            case SET -> before.write(ctx.level(), table, index);
        }
    }

    @Override
    public String name() {
        String what =
                switch (table) {
                    case RULES -> "rule";
                    case SIZE_CLASSES -> "size class";
                    case AREAS -> "area";
                    case LINKS -> "link";
                };
        return switch (op) {
            case ADD -> "Add " + what;
            case REMOVE -> "Remove " + what;
            case SET -> "Change " + what;
        };
    }

    @Override
    public boolean mergeWith(Command next) {
        if (next instanceof TableCommand t
                && t.op == Op.SET
                && op == Op.SET
                && t.table == table
                && t.index == index) {
            after.copyOf(t.after);
            return true;
        }
        return false;
    }
}
