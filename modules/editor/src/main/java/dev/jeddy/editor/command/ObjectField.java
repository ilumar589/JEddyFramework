package dev.jeddy.editor.command;

import dev.jeddy.asset.format.LevelData;

/**
 * The non-float fields of a level object, so the Inspector's rows and the {@link SetStringCommand}
 * and {@link SetIntCommand} can name them. Which fields a kind has: a placement {@link #NAME},
 * {@link #LAYER}, {@link #FLAGS}, {@link #COLLISION}, {@link #PREFAB}; a light {@link #NAME},
 * {@link #FLAGS}, {@link #LIGHT_TYPE}; a spawn {@link #NAME}, {@link #KIND}; a trigger {@link
 * #NAME}, {@link #KIND}, {@link #PAYLOAD}, {@link #FLAGS}.
 *
 * <p>Thread ownership: the game thread.
 */
public enum ObjectField {
    NAME,
    LAYER,
    KIND,
    PAYLOAD,
    FLAGS,
    COLLISION,
    PREFAB,
    LIGHT_TYPE;

    /** Returns the string value of this field on object {@code i} of {@code kind}. */
    public String getString(LevelData level, int kind, int i) {
        return switch (this) {
            case NAME ->
                    switch (kind) {
                        case LevelData.KIND_PLACEMENT -> level.placementName(i);
                        case LevelData.KIND_LIGHT -> level.lightName(i);
                        case LevelData.KIND_SPAWN -> level.spawnName(i);
                        default -> level.triggerName(i);
                    };
            case LAYER -> level.placementLayer(i);
            case KIND -> kind == LevelData.KIND_SPAWN ? level.spawnKind(i) : level.triggerKind(i);
            case PAYLOAD -> level.triggerPayload(i);
            default -> throw new IllegalArgumentException(this + " is not a string field");
        };
    }

    /** Sets the string value of this field on object {@code i} of {@code kind}. */
    public void setString(LevelData level, int kind, int i, String v) {
        switch (this) {
            case NAME -> {
                switch (kind) {
                    case LevelData.KIND_PLACEMENT -> level.setPlacementName(i, v);
                    case LevelData.KIND_LIGHT -> level.setLightName(i, v);
                    case LevelData.KIND_SPAWN -> level.setSpawnName(i, v);
                    default -> level.setTriggerName(i, v);
                }
            }
            case LAYER -> level.setPlacementLayer(i, v);
            case KIND -> {
                if (kind == LevelData.KIND_SPAWN) {
                    level.setSpawnKind(i, v);
                } else {
                    level.setTriggerKind(i, v);
                }
            }
            case PAYLOAD -> level.setTriggerPayload(i, v);
            default -> throw new IllegalArgumentException(this + " is not a string field");
        }
    }

    /** Returns the integer value of this field on object {@code i} of {@code kind}. */
    public int getInt(LevelData level, int kind, int i) {
        return switch (this) {
            case FLAGS ->
                    switch (kind) {
                        case LevelData.KIND_PLACEMENT -> level.placementFlags(i);
                        case LevelData.KIND_LIGHT -> level.lightFlags(i);
                        case LevelData.KIND_TRIGGER -> level.triggerFlags(i);
                        default -> 0;
                    };
            case COLLISION -> level.placementCollision(i);
            case PREFAB -> level.placementPrefab(i);
            case LIGHT_TYPE -> level.lightType(i);
            default -> throw new IllegalArgumentException(this + " is not an int field");
        };
    }

    /** Sets the integer value of this field on object {@code i} of {@code kind}. */
    public void setInt(LevelData level, int kind, int i, int v) {
        switch (this) {
            case FLAGS -> {
                switch (kind) {
                    case LevelData.KIND_PLACEMENT -> level.setPlacementFlags(i, v);
                    case LevelData.KIND_LIGHT -> level.setLightFlags(i, v);
                    case LevelData.KIND_TRIGGER -> level.setTriggerFlags(i, v);
                    default -> {}
                }
            }
            case COLLISION -> level.setPlacementCollision(i, v);
            case PREFAB -> level.setPlacementPrefab(i, v);
            case LIGHT_TYPE -> level.setLightType(i, v);
            default -> throw new IllegalArgumentException(this + " is not an int field");
        }
    }
}
