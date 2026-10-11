package dev.jeddy.editor.command;

import dev.jeddy.app.world.LevelLoader;
import dev.jeddy.asset.format.LevelData;
import dev.jeddy.editor.LevelObjects;

/**
 * A copy of one level object's row, whatever its kind: everything {@link #insert} needs to put it
 * back exactly where {@link #remove} took it from (D89: a delete stores the row, not the level).
 * Which fields are meaningful depends on {@link #kind}: a placement uses {@code prefab}, {@code
 * layer}, {@code flags} and {@code collision}; a light {@code type} and {@code flags}; a spawn
 * {@code kind2}; a trigger {@code kind2}, {@code payload} and {@code flags}. All use {@code id},
 * {@code name}, {@code index} and {@code data}.
 *
 * <p>Thread ownership: the game thread.
 */
public final class ObjectRow {
    /** Creates an empty row; fill it with {@link #capture} or field by field. */
    public ObjectRow() {}

    /** {@code LevelData.KIND_*}. */
    public int kind;

    /** The row index in its table at capture time. */
    public int index;

    public int id;
    public String name = "";
    public int prefab;
    public String layer = "";
    public int flags;
    public int collision = LevelData.COLLISION_BY_LAYER;
    public int type = LevelData.LIGHT_POINT;
    public String kind2 = "";
    public String payload = "";
    public final float[] data = new float[LevelData.LIGHT_FLOATS];

    /** Fills this row from object {@code id} of {@code level}. */
    public ObjectRow capture(LevelData level, int objectId) {
        kind = level.kindOf(objectId);
        index = LevelObjects.index(level, objectId);
        id = objectId;
        switch (kind) {
            case LevelData.KIND_PLACEMENT -> {
                name = level.placementName(index);
                prefab = level.placementPrefab(index);
                layer = level.placementLayer(index);
                flags = level.placementFlags(index);
                collision = level.placementCollision(index);
            }
            case LevelData.KIND_LIGHT -> {
                name = level.lightName(index);
                type = level.lightType(index);
                flags = level.lightFlags(index);
            }
            case LevelData.KIND_SPAWN -> {
                name = level.spawnName(index);
                kind2 = level.spawnKind(index);
            }
            case LevelData.KIND_TRIGGER -> {
                name = level.triggerName(index);
                kind2 = level.triggerKind(index);
                payload = level.triggerPayload(index);
                flags = level.triggerFlags(index);
            }
            default -> throw new IllegalArgumentException("no object " + objectId);
        }
        LevelObjects.copyRow(level, objectId, data, 0);
        return this;
    }

    /** Copies {@code other}, giving the copy a new id and leaving its index to the caller. */
    public ObjectRow copyOf(ObjectRow other, int newId) {
        kind = other.kind;
        index = other.index;
        id = newId;
        name = other.name;
        prefab = other.prefab;
        layer = other.layer;
        flags = other.flags;
        collision = other.collision;
        type = other.type;
        kind2 = other.kind2;
        payload = other.payload;
        System.arraycopy(other.data, 0, data, 0, data.length);
        return this;
    }

    /** Puts the row back at {@link #index} and, for a placement, into the world. */
    public void insert(LevelData level, LevelLoader loader) {
        switch (kind) {
            case LevelData.KIND_PLACEMENT -> {
                level.insertPlacement(index, id, prefab, name, layer, flags, collision);
                level.setPlacementTransform(index, data, 0);
                if (loader != null && loader.level() == level) {
                    loader.placementInserted(index);
                }
            }
            case LevelData.KIND_LIGHT -> level.insertLight(index, id, type, name, flags);
            case LevelData.KIND_SPAWN -> level.insertSpawn(index, id, name, kind2);
            case LevelData.KIND_TRIGGER ->
                    level.insertTrigger(index, id, name, kind2, payload, flags);
            default -> throw new IllegalStateException("kind " + kind);
        }
        if (kind != LevelData.KIND_PLACEMENT) {
            LevelObjects.writeRow(level, id, data, 0);
        }
    }

    /** Takes the row out of the level (and, for a placement, out of the world). */
    public void remove(LevelData level, LevelLoader loader) {
        int i = LevelObjects.index(level, id);
        if (i < 0) {
            throw new IllegalStateException("object " + id + " is not in the level");
        }
        index = i;
        switch (kind) {
            case LevelData.KIND_PLACEMENT -> {
                if (loader != null && loader.level() == level) {
                    loader.placementRemoving(i);
                }
                level.removePlacement(i);
            }
            case LevelData.KIND_LIGHT -> level.removeLight(i);
            case LevelData.KIND_SPAWN -> level.removeSpawn(i);
            case LevelData.KIND_TRIGGER -> level.removeTrigger(i);
            default -> throw new IllegalStateException("kind " + kind);
        }
    }
}
