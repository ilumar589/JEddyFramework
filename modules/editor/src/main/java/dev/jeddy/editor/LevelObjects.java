package dev.jeddy.editor;

import dev.jeddy.asset.format.LevelData;

/**
 * Uniform access to the four kinds of level object by id (placements, lights, spawns, triggers):
 * the {@link LevelData} stores each kind in its own table, and the editor's selection, commands and
 * gizmos want to treat "an object" the same way whatever its kind. Every kind keeps its position in
 * floats 0–2 of its row, which is what makes a shared translate gizmo possible; the rest of the row
 * differs (see the {@code *_FLOATS} constants and the field index tables in {@link LevelData}).
 *
 * <p>Thread ownership: the game thread.
 */
public final class LevelObjects {
    private LevelObjects() {}

    /** Returns the number of floats in a row of {@code kind} ({@code LevelData.KIND_*}). */
    public static int floats(int kind) {
        return switch (kind) {
            case LevelData.KIND_PLACEMENT -> LevelData.PLACEMENT_FLOATS;
            case LevelData.KIND_LIGHT -> LevelData.LIGHT_FLOATS;
            case LevelData.KIND_SPAWN -> LevelData.SPAWN_FLOATS;
            case LevelData.KIND_TRIGGER -> LevelData.TRIGGER_FLOATS;
            default -> throw new IllegalArgumentException("kind " + kind);
        };
    }

    /** Returns the row index of {@code id} in its kind's table, or -1. */
    public static int index(LevelData level, int id) {
        return switch (level.kindOf(id)) {
            case LevelData.KIND_PLACEMENT -> level.placementIndex(id);
            case LevelData.KIND_LIGHT -> level.lightIndex(id);
            case LevelData.KIND_SPAWN -> level.spawnIndex(id);
            case LevelData.KIND_TRIGGER -> level.triggerIndex(id);
            default -> -1;
        };
    }

    /** Returns whether {@code id} names an existing object. */
    public static boolean exists(LevelData level, int id) {
        return index(level, id) >= 0;
    }

    /** Returns float {@code k} of the row of object {@code id}. */
    public static float get(LevelData level, int id, int k) {
        int i = index(level, id);
        return switch (level.kindOf(id)) {
            case LevelData.KIND_PLACEMENT -> level.placementFloat(i, k);
            case LevelData.KIND_LIGHT -> level.lightFloat(i, k);
            case LevelData.KIND_SPAWN -> level.spawnFloat(i, k);
            case LevelData.KIND_TRIGGER -> level.triggerFloat(i, k);
            default -> throw new IllegalArgumentException("no object " + id);
        };
    }

    /** Sets float {@code k} of the row of object {@code id} (the world is not told; see Editor). */
    public static void set(LevelData level, int id, int k, float v) {
        int i = index(level, id);
        switch (level.kindOf(id)) {
            case LevelData.KIND_PLACEMENT -> level.setPlacementFloat(i, k, v);
            case LevelData.KIND_LIGHT -> level.setLightFloat(i, k, v);
            case LevelData.KIND_SPAWN -> level.setSpawnFloat(i, k, v);
            case LevelData.KIND_TRIGGER -> level.setTriggerFloat(i, k, v);
            default -> throw new IllegalArgumentException("no object " + id);
        }
    }

    /** Copies the float row of object {@code id} into {@code out[o..]}; returns its length. */
    public static int copyRow(LevelData level, int id, float[] out, int o) {
        int kind = level.kindOf(id);
        int i = index(level, id);
        int n = floats(kind);
        float[] src =
                switch (kind) {
                    case LevelData.KIND_PLACEMENT -> level.placementData();
                    case LevelData.KIND_LIGHT -> level.lightData();
                    case LevelData.KIND_SPAWN -> level.spawnData();
                    case LevelData.KIND_TRIGGER -> level.triggerData();
                    default -> throw new IllegalArgumentException("no object " + id);
                };
        System.arraycopy(src, i * n, out, o, n);
        return n;
    }

    /** Writes {@code in[o..]} over the float row of object {@code id}; returns its length. */
    public static int writeRow(LevelData level, int id, float[] in, int o) {
        int kind = level.kindOf(id);
        int i = index(level, id);
        int n = floats(kind);
        for (int k = 0; k < n; k++) {
            switch (kind) {
                case LevelData.KIND_PLACEMENT -> level.setPlacementFloat(i, k, in[o + k]);
                case LevelData.KIND_LIGHT -> level.setLightFloat(i, k, in[o + k]);
                case LevelData.KIND_SPAWN -> level.setSpawnFloat(i, k, in[o + k]);
                case LevelData.KIND_TRIGGER -> level.setTriggerFloat(i, k, in[o + k]);
                default -> throw new IllegalArgumentException("no object " + id);
            }
        }
        return n;
    }

    /** Writes the position of object {@code id} into {@code out[o..o+2]}. */
    public static void position(LevelData level, int id, float[] out, int o) {
        out[o] = get(level, id, 0);
        out[o + 1] = get(level, id, 1);
        out[o + 2] = get(level, id, 2);
    }

    /** Returns the name of object {@code id} (may be empty). */
    public static String name(LevelData level, int id) {
        int i = index(level, id);
        return switch (level.kindOf(id)) {
            case LevelData.KIND_PLACEMENT -> level.placementName(i);
            case LevelData.KIND_LIGHT -> level.lightName(i);
            case LevelData.KIND_SPAWN -> level.spawnName(i);
            case LevelData.KIND_TRIGGER -> level.triggerName(i);
            default -> "";
        };
    }

    /** Returns a short label for a kind ("placement", "light", ...). */
    public static String kindName(int kind) {
        return switch (kind) {
            case LevelData.KIND_PLACEMENT -> "placement";
            case LevelData.KIND_LIGHT -> "light";
            case LevelData.KIND_SPAWN -> "spawn";
            case LevelData.KIND_TRIGGER -> "trigger";
            default -> "none";
        };
    }

    /**
     * Returns the index of the first float of the rotation in a row of {@code kind}: the quaternion
     * of a placement (4 floats), the yaw of a spawn or trigger (1 float), or -1 for a light (its
     * direction is a vector, floats 3–5).
     */
    public static int rotationOffset(int kind) {
        return switch (kind) {
            case LevelData.KIND_PLACEMENT -> 3;
            case LevelData.KIND_SPAWN -> 3;
            case LevelData.KIND_TRIGGER -> 6;
            default -> -1;
        };
    }

    /**
     * Returns the index of the first of three size floats in a row of {@code kind}: a placement's
     * scale (7–9), a trigger's extents (3–5), or -1.
     */
    public static int scaleOffset(int kind) {
        return switch (kind) {
            case LevelData.KIND_PLACEMENT -> 7;
            case LevelData.KIND_TRIGGER -> 3;
            default -> -1;
        };
    }
}
