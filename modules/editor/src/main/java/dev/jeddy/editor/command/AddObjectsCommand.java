package dev.jeddy.editor.command;

import dev.jeddy.asset.format.LevelData;
import dev.jeddy.editor.EditorContext;

/**
 * Adds objects to the level: new placements, lights, spawns or triggers, or duplicates of selected
 * ones (D89: the command holds the rows, with their ids and indices, so redo puts back exactly what
 * undo took out and the selection can keep referring to the ids).
 *
 * <p>Thread ownership: the game thread.
 */
public final class AddObjectsCommand implements Command {
    private final ObjectRow[] rows;
    private final String name;

    /** Adds {@code rows}, each at its {@code index} (in ascending index order per kind). */
    public AddObjectsCommand(ObjectRow[] rows, String name) {
        this.rows = rows;
        this.name = name;
    }

    /** Returns the number of objects added. */
    public int count() {
        return rows.length;
    }

    /** Returns the id of added object {@code i}. */
    public int id(int i) {
        return rows[i].id;
    }

    /**
     * Makes a command appending one placement of {@code prefab} named {@code name} on {@code layer}
     * at {@code (x, y, z)} with identity rotation and unit scale.
     */
    public static AddObjectsCommand placement(
            LevelData level,
            int prefab,
            String name,
            String layer,
            int flags,
            float x,
            float y,
            float z) {
        ObjectRow r = new ObjectRow();
        r.kind = LevelData.KIND_PLACEMENT;
        r.index = level.placementCount();
        r.id = level.allocateId();
        r.name = name;
        r.prefab = prefab;
        r.layer = layer;
        r.flags = flags;
        r.collision = LevelData.COLLISION_BY_LAYER;
        r.data[0] = x;
        r.data[1] = y;
        r.data[2] = z;
        r.data[6] = 1; // qw
        r.data[7] = 1;
        r.data[8] = 1;
        r.data[9] = 1;
        return new AddObjectsCommand(new ObjectRow[] {r}, "Add " + name);
    }

    /** Makes a command appending a point light at {@code (x, y, z)}. */
    public static AddObjectsCommand pointLight(
            LevelData level,
            String name,
            float x,
            float y,
            float z,
            float r,
            float g,
            float b,
            float intensity,
            float range,
            int flags) {
        ObjectRow row = new ObjectRow();
        row.kind = LevelData.KIND_LIGHT;
        row.index = level.lightCount();
        row.id = level.allocateId();
        row.name = name;
        row.type = LevelData.LIGHT_POINT;
        row.flags = flags;
        float[] d = row.data;
        d[0] = x;
        d[1] = y;
        d[2] = z;
        d[4] = -1; // pointing down, in case it becomes a spot
        d[6] = r;
        d[7] = g;
        d[8] = b;
        d[9] = intensity;
        d[10] = range;
        d[11] = 0.4f;
        d[12] = 0.6f;
        return new AddObjectsCommand(new ObjectRow[] {row}, "Add light");
    }

    /** Makes a command appending a spawn point. */
    public static AddObjectsCommand spawn(
            LevelData level, String name, String kind, float x, float y, float z, float yaw) {
        ObjectRow r = new ObjectRow();
        r.kind = LevelData.KIND_SPAWN;
        r.index = level.spawnCount();
        r.id = level.allocateId();
        r.name = name;
        r.kind2 = kind;
        r.data[0] = x;
        r.data[1] = y;
        r.data[2] = z;
        r.data[3] = yaw;
        return new AddObjectsCommand(new ObjectRow[] {r}, "Add spawn");
    }

    /** Makes a command appending a trigger box of {@code (sx, sy, sz)} at {@code (x, y, z)}. */
    public static AddObjectsCommand trigger(
            LevelData level,
            String name,
            String kind,
            String payload,
            int flags,
            float x,
            float y,
            float z,
            float sx,
            float sy,
            float sz) {
        ObjectRow r = new ObjectRow();
        r.kind = LevelData.KIND_TRIGGER;
        r.index = level.triggerCount();
        r.id = level.allocateId();
        r.name = name;
        r.kind2 = kind;
        r.payload = payload;
        r.flags = flags;
        r.data[0] = x;
        r.data[1] = y;
        r.data[2] = z;
        r.data[3] = sx;
        r.data[4] = sy;
        r.data[5] = sz;
        return new AddObjectsCommand(new ObjectRow[] {r}, "Add trigger");
    }

    /**
     * Makes a command appending copies of {@code ids[0, n)} shifted by {@code (dx, dy, dz)}, each
     * with a new id and " copy" after its name.
     */
    public static AddObjectsCommand duplicate(
            LevelData level, int[] ids, int n, float dx, float dy, float dz) {
        ObjectRow[] rows = new ObjectRow[n];
        int placements = level.placementCount();
        int lights = level.lightCount();
        int spawns = level.spawnCount();
        int triggers = level.triggerCount();
        for (int i = 0; i < n; i++) {
            ObjectRow src = new ObjectRow().capture(level, ids[i]);
            ObjectRow r = new ObjectRow().copyOf(src, level.allocateId());
            r.index =
                    switch (r.kind) {
                        case LevelData.KIND_PLACEMENT -> placements++;
                        case LevelData.KIND_LIGHT -> lights++;
                        case LevelData.KIND_SPAWN -> spawns++;
                        default -> triggers++;
                    };
            r.name = src.name.isEmpty() ? "" : src.name + " copy";
            r.data[0] += dx;
            r.data[1] += dy;
            r.data[2] += dz;
            rows[i] = r;
        }
        return new AddObjectsCommand(rows, n == 1 ? "Duplicate" : "Duplicate " + n + " objects");
    }

    @Override
    public void apply(EditorContext ctx) {
        for (ObjectRow r : rows) {
            r.insert(ctx.level(), ctx.loader());
        }
    }

    @Override
    public void revert(EditorContext ctx) {
        for (int i = rows.length - 1; i >= 0; i--) {
            rows[i].remove(ctx.level(), ctx.loader());
        }
    }

    @Override
    public String name() {
        return name;
    }
}
