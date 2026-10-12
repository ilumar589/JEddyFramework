package dev.jeddy.components;

import dev.jeddy.asset.format.LevelData;
import dev.jeddy.core.log.Log;
import dev.jeddy.core.log.Logger;

/**
 * Puts a level's component rows into the stores (DESIGN §22, D109): for each row the store of the
 * component name, the entity the object id maps to, and each value by column name. Unknown
 * components and columns are logged once per load and skipped, so a level outlives a renamed
 * record. A cold path: it allocates (strings) and runs at load and on editor edits.
 *
 * <p>Thread ownership: the game thread.
 */
public final class Levels {
    private static final Logger LOG = Log.logger("components");

    private Levels() {}

    /** Resolves level objects and asset paths to runtime handles. */
    public interface Binding {
        /** The entity of level object {@code objectId}, or {@code Handles.NULL} (0) for none. */
        int entityOf(int objectId);

        /** The handle of asset {@code path} of {@code kind}, or 0 when unresolved. */
        default int assetOf(String kind, String path) {
            return 0;
        }
    }

    /** Adds every row of {@code level} whose object maps to an entity. Returns the rows applied. */
    public static int load(Store[] stores, LevelData level, Binding binding) {
        int applied = 0;
        for (int row = 0; row < level.componentRowCount(); row++) {
            int entity = binding.entityOf(level.componentRowObject(row));
            if (entity != 0 && applyRow(stores, level, row, entity, binding)) {
                applied++;
            }
        }
        return applied;
    }

    /** Adds the rows of object {@code objectId} to {@code entity}. Returns the rows applied. */
    public static int loadObject(
            Store[] stores, LevelData level, int objectId, int entity, Binding binding) {
        int applied = 0;
        for (int row = level.nextComponentRowOf(objectId, 0);
                row >= 0;
                row = level.nextComponentRowOf(objectId, row + 1)) {
            if (applyRow(stores, level, row, entity, binding)) {
                applied++;
            }
        }
        return applied;
    }

    /** Adds one row to {@code entity}; false when the component is unknown. */
    public static boolean applyRow(
            Store[] stores, LevelData level, int row, int entity, Binding binding) {
        String name = level.componentRowComponent(row);
        Store store = find(stores, name);
        if (store == null) {
            LOG.warn(
                    "level object {} has unknown component {}",
                    level.componentRowObject(row),
                    name);
            return false;
        }
        int r = store.add(entity);
        for (int k = 0; k < level.componentValueCount(row); k++) {
            String column = level.componentValueColumn(row, k);
            int c = store.columnIndex(column);
            if (c < 0) {
                LOG.warn("component {} has no column {}", name, column);
                continue;
            }
            long bits = level.componentValueBits(row, k);
            switch (level.componentValueType(row, k)) {
                case LevelData.VALUE_INT -> store.setInt(r, c, (int) bits);
                case LevelData.VALUE_LONG -> store.setLong(r, c, bits);
                case LevelData.VALUE_FLOAT ->
                        store.setFloat(r, c, Float.intBitsToFloat((int) bits));
                case LevelData.VALUE_BOOL -> store.setBool(r, c, bits != 0);
                case LevelData.VALUE_ENUM -> {
                    int ordinal = store.enumOrdinal(c, level.componentValueText(row, k));
                    if (ordinal < 0) {
                        LOG.warn(
                                "component {} column {}: no constant {}",
                                name,
                                column,
                                level.componentValueText(row, k));
                    } else {
                        store.setInt(r, c, ordinal);
                    }
                }
                case LevelData.VALUE_ENTITY -> store.setInt(r, c, binding.entityOf((int) bits));
                case LevelData.VALUE_ASSET ->
                        store.setInt(
                                r,
                                c,
                                binding.assetOf(
                                        store.assetKind(c), level.componentValueText(row, k)));
                default -> LOG.warn("component {} column {}: unknown value type", name, column);
            }
        }
        return true;
    }

    /** The store named {@code name} among {@code stores}, or null. */
    public static Store find(Store[] stores, String name) {
        for (Store s : stores) {
            if (s.name().equals(name)) {
                return s;
            }
        }
        return null;
    }
}
