package dev.jeddy.asset.format;

import static dev.jeddy.asset.format.Bytes.F32;
import static dev.jeddy.asset.format.Bytes.U16;
import static dev.jeddy.asset.format.Bytes.U32;
import static dev.jeddy.asset.format.Bytes.U64;
import static dev.jeddy.asset.format.Bytes.align;
import static dev.jeddy.asset.format.Bytes.checkRange;

import dev.jeddy.core.collections.IntIntMap;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A level ({@code .jlevel}, {@code docs/formats/jlevel.md}; TASKS M14-02, DESIGN §19, D86): a
 * composition of prefab placements, lights, spawn points, triggers, navigation area volumes and
 * off-mesh links, collision rules, agent size classes and settings. The same class is the file's
 * reader and writer and the editor's in-memory model: a mutable struct-of-arrays store whose rows
 * carry stable ids (unique across placements, lights, spawns and triggers), so selection, undo and
 * triggers refer to ids while rows are removed and reinserted. Reading allocates once; the runtime
 * never mutates what it read.
 *
 * <p>Rows are addressed by index in their kind ({@code placementCount()}, {@code placementId(i)},
 * ...). Float fields live in flat arrays with a fixed stride per kind ({@link #PLACEMENT_FLOATS}
 * ...), so a transform is {@code placementData()[i * PLACEMENT_FLOATS + k]}.
 *
 * <p>Thread ownership: one thread at a time (the game thread in the editor and the runtime; a
 * cooker worker when baking).
 */
public final class LevelData {
    /** File magic, {@code "JLVL"}. */
    public static final int MAGIC = 0x4C564C4A;

    /** The format version this code reads and writes. */
    public static final int VERSION = 1;

    /** Header size in bytes. */
    public static final int HEADER_SIZE = 160;

    static final int PREFAB_SIZE = 16;
    static final int PLACEMENT_SIZE = 96;
    static final int LIGHT_SIZE = 80;
    static final int SPAWN_SIZE = 48;
    static final int TRIGGER_SIZE = 64;
    static final int AREA_SIZE = 32;
    static final int LINK_SIZE = 48;
    static final int RULE_SIZE = 24;
    static final int SIZE_CLASS_SIZE = 32;
    static final int SETTINGS_SIZE = 128;

    // ---- Object kinds (what an id refers to) --------------------------------------------------

    /** Id kind: a placement. */
    public static final int KIND_PLACEMENT = 0;

    /** Id kind: a light. */
    public static final int KIND_LIGHT = 1;

    /** Id kind: a spawn point. */
    public static final int KIND_SPAWN = 2;

    /** Id kind: a trigger. */
    public static final int KIND_TRIGGER = 3;

    /** Id kind: none. */
    public static final int KIND_NONE = -1;

    // ---- Placement flags ----------------------------------------------------------------------

    /** Placement flag: static geometry (never moves at run time). */
    public static final int PLACEMENT_STATIC = 1;

    /** Placement flag: casts shadows. */
    public static final int PLACEMENT_CASTS_SHADOW = 2;

    /** Placement flag: fades when it hides the party from the camera. */
    public static final int PLACEMENT_FADE_OCCLUDER = 4;

    /** Placement flag: not shown. */
    public static final int PLACEMENT_HIDDEN = 8;

    /** Placement flag: decals do not project onto it. */
    public static final int PLACEMENT_NO_DECALS = 16;

    /** Placement flag: left out of the physics bake whatever its layer says. */
    public static final int PLACEMENT_NO_COLLISION = 32;

    /** Placement collision override value meaning "by the level's rules for its layer". */
    public static final int COLLISION_BY_LAYER = -1;

    // ---- Lights -------------------------------------------------------------------------------

    /** Light type: point. */
    public static final int LIGHT_POINT = 1;

    /** Light type: spot. */
    public static final int LIGHT_SPOT = 2;

    /** Light flag: casts a shadow. */
    public static final int LIGHT_SHADOW = 1;

    /** Light flag: flickers like a torch. */
    public static final int LIGHT_FLICKER = 2;

    // ---- Triggers and links -------------------------------------------------------------------

    /** Trigger flag: fires once. */
    public static final int TRIGGER_ONCE = 1;

    /** Trigger flag: enabled at load. */
    public static final int TRIGGER_ENABLED = 2;

    /** Link flag: usable in both directions. */
    public static final int LINK_BIDIRECTIONAL = 1;

    // ---- Float strides ------------------------------------------------------------------------

    /** Floats per placement: position 3, rotation quaternion 4, scale 3. */
    public static final int PLACEMENT_FLOATS = 10;

    /** Floats per light: position 3, direction 3, color 3, intensity, range, inner, outer. */
    public static final int LIGHT_FLOATS = 13;

    /** Floats per spawn: position 3, yaw. */
    public static final int SPAWN_FLOATS = 4;

    /** Floats per trigger: center 3, size 3, yaw. */
    public static final int TRIGGER_FLOATS = 7;

    /** Floats per link: start 3, end 3, radius. */
    public static final int LINK_FLOATS = 7;

    /** Floats per size class: radius, height, climb. */
    public static final int SIZE_CLASS_FLOATS = 3;

    /** The level's settings (navigation and occlusion bake, lighting, editor grid). */
    public static final class Settings {
        /** Navmesh voxel size in meters. */
        public float navCellSize = 0.15f;

        /** Navmesh voxel height in meters. */
        public float navCellHeight = 0.1f;

        /** Navmesh tile size in meters. */
        public float navTileSize = 32;

        /** Steepest walkable slope in degrees. */
        public float navMaxSlope = 45;

        /** Fog-of-war occlusion grid cell size. */
        public float occlusionCellSize = 0.5f;

        /** Bottom of the eye-height band that blocks sight. */
        public float occlusionYMin = 0.3f;

        /** Top of the eye-height band that blocks sight. */
        public float occlusionYMax = 2.0f;

        /** Whether the occlusion grid is baked. */
        public boolean occlusionEnabled = true;

        /** Sun direction (towards the ground). */
        public final float[] sunDirection = {-0.4f, -0.8f, -0.45f};

        /** Sun color (linear radiance). */
        public final float[] sunColor = {3f, 2.9f, 2.7f};

        /** Exposure compensation. */
        public float exposure = 1;

        /** Environment specular strength. */
        public float environmentSpecular = 1;

        /** Environment diffuse strength. */
        public float environmentDiffuse = 1;

        /** The environment cube map's asset path, or empty. */
        public String environment = "";

        /** The editor's snapping grid step. */
        public float gridStep = 0.5f;

        /** Creates default settings. */
        public Settings() {}

        /** Copies every field from {@code o}. */
        public void copyFrom(Settings o) {
            navCellSize = o.navCellSize;
            navCellHeight = o.navCellHeight;
            navTileSize = o.navTileSize;
            navMaxSlope = o.navMaxSlope;
            occlusionCellSize = o.occlusionCellSize;
            occlusionYMin = o.occlusionYMin;
            occlusionYMax = o.occlusionYMax;
            occlusionEnabled = o.occlusionEnabled;
            System.arraycopy(o.sunDirection, 0, sunDirection, 0, 3);
            System.arraycopy(o.sunColor, 0, sunColor, 0, 3);
            exposure = o.exposure;
            environmentSpecular = o.environmentSpecular;
            environmentDiffuse = o.environmentDiffuse;
            environment = o.environment;
            gridStep = o.gridStep;
        }
    }

    /** The settings. */
    public final Settings settings = new Settings();

    // Prefabs
    private int prefabCount;
    private String[] prefabScene = new String[8];
    private String[] prefabNode = new String[8];
    // Placements
    private int placementCount;
    private int[] placementId = new int[64];
    private int[] placementPrefab = new int[64];
    private String[] placementName = new String[64];
    private String[] placementLayer = new String[64];
    private int[] placementFlags = new int[64];
    private int[] placementCollision = new int[64];
    private float[] placementData = new float[64 * PLACEMENT_FLOATS];
    // Lights
    private int lightCount;
    private int[] lightId = new int[16];
    private int[] lightType = new int[16];
    private String[] lightName = new String[16];
    private int[] lightFlags = new int[16];
    private float[] lightData = new float[16 * LIGHT_FLOATS];
    // Spawns
    private int spawnCount;
    private int[] spawnId = new int[16];
    private String[] spawnName = new String[16];
    private String[] spawnKind = new String[16];
    private float[] spawnData = new float[16 * SPAWN_FLOATS];
    // Triggers
    private int triggerCount;
    private int[] triggerId = new int[16];
    private String[] triggerName = new String[16];
    private String[] triggerKind = new String[16];
    private String[] triggerPayload = new String[16];
    private int[] triggerFlags = new int[16];
    private float[] triggerData = new float[16 * TRIGGER_FLOATS];
    // Navigation areas
    private int areaCount;
    private int[] areaArea = new int[8];
    private float[] areaYMin = new float[8];
    private float[] areaYMax = new float[8];
    private float[][] areaPolygon = new float[8][];
    // Links
    private int linkCount;
    private int[] linkFlags = new int[8];
    private int[] linkArea = new int[8];
    private int[] linkUserId = new int[8];
    private float[] linkData = new float[8 * LINK_FLOATS];
    // Rules
    private int ruleCount;
    private String[] ruleLayer = new String[8];
    private int[] ruleFlags = new int[8];
    private int[] ruleSurface = new int[8];
    private int[] ruleArea = new int[8];
    // Size classes
    private int sizeClassCount;
    private String[] sizeClassName = new String[4];
    private float[] sizeClassData = new float[4 * SIZE_CLASS_FLOATS];

    private int nextId = 1;
    private int version;
    // id → kind << 28 | row index, kept by every insert and remove so lookups by id are O(1) (the
    // gizmo resolves the selection every frame; a linear scan over 400 placements per object
    // would not do).
    private final IntIntMap ids = new IntIntMap(256, -1);

    /** Creates an empty level. */
    public LevelData() {}

    /** Returns a counter that changes with every edit (the editor's dirty tracking). */
    public int version() {
        return version;
    }

    /** Bumps {@link #version} after a change made through a public field ({@link #settings}). */
    public void touch() {
        version++;
    }

    /** Returns the next free object id (ids start at 1). */
    public int nextId() {
        return nextId;
    }

    /** Allocates an object id. */
    public int allocateId() {
        version++;
        return nextId++;
    }

    private void noteId(int id) {
        if (id >= nextId) {
            nextId = id + 1;
        }
    }

    /** Returns the kind of the object with {@code id}, or {@link #KIND_NONE}. */
    public int kindOf(int id) {
        int v = ids.get(id);
        return v < 0 ? KIND_NONE : v >>> 28;
    }

    private int indexOf(int id, int kind) {
        int v = ids.get(id);
        return v >= 0 && v >>> 28 == kind ? v & 0x0FFFFFFF : -1;
    }

    /** Records row {@code index} of {@code kind} under {@code id}; rejects a duplicate id. */
    private void indexInsert(int id, int kind, int index, int[] rowIds, int count) {
        if (ids.containsKey(id)) {
            throw new IllegalArgumentException("duplicate object id " + id);
        }
        for (int i = index; i < count; i++) { // the rows after it moved up by one
            ids.put(rowIds[i], kind << 28 | i);
        }
    }

    private void indexRemove(int id, int kind, int index, int[] rowIds, int count) {
        ids.remove(id);
        for (int i = index; i < count; i++) {
            ids.put(rowIds[i], kind << 28 | i);
        }
    }

    // ---- Prefabs ------------------------------------------------------------------------------

    /** Returns the number of prefabs referenced. */
    public int prefabCount() {
        return prefabCount;
    }

    /** Returns prefab {@code p}'s cooked scene asset path. */
    public String prefabScene(int p) {
        return prefabScene[p];
    }

    /** Returns prefab {@code p}'s root node name (empty for the whole scene). */
    public String prefabNode(int p) {
        return prefabNode[p];
    }

    /** Returns the prefab for {@code (scene, node)}, adding it if new. */
    public int prefab(String scene, String node) {
        for (int p = 0; p < prefabCount; p++) {
            if (prefabScene[p].equals(scene) && prefabNode[p].equals(node)) {
                return p;
            }
        }
        return appendPrefab(scene, node);
    }

    private int appendPrefab(String scene, String node) {
        if (prefabCount == prefabScene.length) {
            prefabScene = Arrays.copyOf(prefabScene, prefabCount * 2);
            prefabNode = Arrays.copyOf(prefabNode, prefabCount * 2);
        }
        prefabScene[prefabCount] = scene;
        prefabNode[prefabCount] = node;
        version++;
        return prefabCount++;
    }

    /** Returns how many placements use prefab {@code p}. */
    public int prefabUses(int p) {
        int n = 0;
        for (int i = 0; i < placementCount; i++) {
            if (placementPrefab[i] == p) {
                n++;
            }
        }
        return n;
    }

    // ---- Placements ---------------------------------------------------------------------------

    /** Returns the number of placements. */
    public int placementCount() {
        return placementCount;
    }

    /** Returns placement {@code i}'s id. */
    public int placementId(int i) {
        return placementId[i];
    }

    /** Returns the index of the placement with {@code id}, or -1. */
    public int placementIndex(int id) {
        return indexOf(id, KIND_PLACEMENT);
    }

    /** Returns placement {@code i}'s prefab index. */
    public int placementPrefab(int i) {
        return placementPrefab[i];
    }

    /** Returns placement {@code i}'s name. */
    public String placementName(int i) {
        return placementName[i];
    }

    /** Returns placement {@code i}'s layer. */
    public String placementLayer(int i) {
        return placementLayer[i];
    }

    /** Returns placement {@code i}'s flags ({@link #PLACEMENT_STATIC} ...). */
    public int placementFlags(int i) {
        return placementFlags[i];
    }

    /** Returns placement {@code i}'s collision override, or {@link #COLLISION_BY_LAYER}. */
    public int placementCollision(int i) {
        return placementCollision[i];
    }

    /** Returns the placements' floats, {@link #PLACEMENT_FLOATS} per placement. */
    public float[] placementData() {
        return placementData;
    }

    /** Returns float {@code k} of placement {@code i} (0-2 position, 3-6 rotation, 7-9 scale). */
    public float placementFloat(int i, int k) {
        return placementData[i * PLACEMENT_FLOATS + k];
    }

    /** Sets placement {@code i}'s prefab. */
    public void setPlacementPrefab(int i, int prefab) {
        placementPrefab[i] = prefab;
        version++;
    }

    /** Sets placement {@code i}'s name. */
    public void setPlacementName(int i, String name) {
        placementName[i] = name;
        version++;
    }

    /** Sets placement {@code i}'s layer. */
    public void setPlacementLayer(int i, String layer) {
        placementLayer[i] = layer;
        version++;
    }

    /** Sets placement {@code i}'s flags. */
    public void setPlacementFlags(int i, int flags) {
        placementFlags[i] = flags;
        version++;
    }

    /** Sets placement {@code i}'s collision override. */
    public void setPlacementCollision(int i, int collision) {
        placementCollision[i] = collision;
        version++;
    }

    /** Sets float {@code k} of placement {@code i}. */
    public void setPlacementFloat(int i, int k, float v) {
        placementData[i * PLACEMENT_FLOATS + k] = v;
        version++;
    }

    /** Copies {@code trs[offset .. +10)} into placement {@code i}. */
    public void setPlacementTransform(int i, float[] trs, int offset) {
        System.arraycopy(trs, offset, placementData, i * PLACEMENT_FLOATS, PLACEMENT_FLOATS);
        version++;
    }

    /**
     * Appends a placement with a new id at {@code (x, y, z)}, rotation {@code (qx, qy, qz, qw)} and
     * scale {@code (sx, sy, sz)}; returns its index.
     */
    public int addPlacement(
            int prefab,
            String name,
            String layer,
            int flags,
            float x,
            float y,
            float z,
            float qx,
            float qy,
            float qz,
            float qw,
            float sx,
            float sy,
            float sz) {
        int i =
                insertPlacement(
                        placementCount,
                        allocateId(),
                        prefab,
                        name,
                        layer,
                        flags,
                        COLLISION_BY_LAYER);
        float[] d = placementData;
        int o = i * PLACEMENT_FLOATS;
        d[o] = x;
        d[o + 1] = y;
        d[o + 2] = z;
        d[o + 3] = qx;
        d[o + 4] = qy;
        d[o + 5] = qz;
        d[o + 6] = qw;
        d[o + 7] = sx;
        d[o + 8] = sy;
        d[o + 9] = sz;
        return i;
    }

    /**
     * Inserts a placement row at {@code index} with the given id (an undo re-inserting a deleted
     * row); its floats are zero until set. Returns {@code index}.
     */
    public int insertPlacement(
            int index, int id, int prefab, String name, String layer, int flags, int collision) {
        if (placementCount == placementId.length) {
            int n = placementCount * 2;
            placementId = Arrays.copyOf(placementId, n);
            placementPrefab = Arrays.copyOf(placementPrefab, n);
            placementName = Arrays.copyOf(placementName, n);
            placementLayer = Arrays.copyOf(placementLayer, n);
            placementFlags = Arrays.copyOf(placementFlags, n);
            placementCollision = Arrays.copyOf(placementCollision, n);
            placementData = Arrays.copyOf(placementData, n * PLACEMENT_FLOATS);
        }
        int tail = placementCount - index;
        if (tail > 0) {
            System.arraycopy(placementId, index, placementId, index + 1, tail);
            System.arraycopy(placementPrefab, index, placementPrefab, index + 1, tail);
            System.arraycopy(placementName, index, placementName, index + 1, tail);
            System.arraycopy(placementLayer, index, placementLayer, index + 1, tail);
            System.arraycopy(placementFlags, index, placementFlags, index + 1, tail);
            System.arraycopy(placementCollision, index, placementCollision, index + 1, tail);
            System.arraycopy(
                    placementData,
                    index * PLACEMENT_FLOATS,
                    placementData,
                    (index + 1) * PLACEMENT_FLOATS,
                    tail * PLACEMENT_FLOATS);
        }
        placementId[index] = id;
        placementPrefab[index] = prefab;
        placementName[index] = name;
        placementLayer[index] = layer;
        placementFlags[index] = flags;
        placementCollision[index] = collision;
        Arrays.fill(placementData, index * PLACEMENT_FLOATS, (index + 1) * PLACEMENT_FLOATS, 0);
        placementCount++;
        indexInsert(id, KIND_PLACEMENT, index, placementId, placementCount);
        noteId(id);
        version++;
        return index;
    }

    /** Removes placement {@code index}, keeping the order of the others. */
    public void removePlacement(int index) {
        int removed = placementId[index];
        int tail = placementCount - index - 1;
        if (tail > 0) {
            System.arraycopy(placementId, index + 1, placementId, index, tail);
            System.arraycopy(placementPrefab, index + 1, placementPrefab, index, tail);
            System.arraycopy(placementName, index + 1, placementName, index, tail);
            System.arraycopy(placementLayer, index + 1, placementLayer, index, tail);
            System.arraycopy(placementFlags, index + 1, placementFlags, index, tail);
            System.arraycopy(placementCollision, index + 1, placementCollision, index, tail);
            System.arraycopy(
                    placementData,
                    (index + 1) * PLACEMENT_FLOATS,
                    placementData,
                    index * PLACEMENT_FLOATS,
                    tail * PLACEMENT_FLOATS);
        }
        placementCount--;
        indexRemove(removed, KIND_PLACEMENT, index, placementId, placementCount);
        placementName[placementCount] = null;
        placementLayer[placementCount] = null;
        version++;
    }

    // ---- Lights -------------------------------------------------------------------------------

    /** Returns the number of lights. */
    public int lightCount() {
        return lightCount;
    }

    /** Returns light {@code i}'s id. */
    public int lightId(int i) {
        return lightId[i];
    }

    /** Returns the index of the light with {@code id}, or -1. */
    public int lightIndex(int id) {
        return indexOf(id, KIND_LIGHT);
    }

    /** Returns light {@code i}'s type ({@link #LIGHT_POINT}, {@link #LIGHT_SPOT}). */
    public int lightType(int i) {
        return lightType[i];
    }

    /** Returns light {@code i}'s name. */
    public String lightName(int i) {
        return lightName[i];
    }

    /** Returns light {@code i}'s flags. */
    public int lightFlags(int i) {
        return lightFlags[i];
    }

    /** Returns the lights' floats, {@link #LIGHT_FLOATS} per light. */
    public float[] lightData() {
        return lightData;
    }

    /**
     * Returns float {@code k} of light {@code i}: 0-2 position, 3-5 direction, 6-8 color, 9
     * intensity, 10 range, 11 inner angle, 12 outer angle.
     */
    public float lightFloat(int i, int k) {
        return lightData[i * LIGHT_FLOATS + k];
    }

    /** Sets light {@code i}'s type. */
    public void setLightType(int i, int type) {
        lightType[i] = type;
        version++;
    }

    /** Sets light {@code i}'s name. */
    public void setLightName(int i, String name) {
        lightName[i] = name;
        version++;
    }

    /** Sets light {@code i}'s flags. */
    public void setLightFlags(int i, int flags) {
        lightFlags[i] = flags;
        version++;
    }

    /** Sets float {@code k} of light {@code i}. */
    public void setLightFloat(int i, int k, float v) {
        lightData[i * LIGHT_FLOATS + k] = v;
        version++;
    }

    /** Appends a point light with a new id; returns its index. */
    public int addPointLight(
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
        int i = insertLight(lightCount, allocateId(), LIGHT_POINT, name, flags);
        float[] d = lightData;
        int o = i * LIGHT_FLOATS;
        d[o] = x;
        d[o + 1] = y;
        d[o + 2] = z;
        d[o + 3] = 0;
        d[o + 4] = -1;
        d[o + 5] = 0;
        d[o + 6] = r;
        d[o + 7] = g;
        d[o + 8] = b;
        d[o + 9] = intensity;
        d[o + 10] = range;
        d[o + 11] = 0.4f;
        d[o + 12] = 0.6f;
        return i;
    }

    /** Inserts a light row at {@code index} with the given id; floats are zero. */
    public int insertLight(int index, int id, int type, String name, int flags) {
        if (lightCount == lightId.length) {
            int n = lightCount * 2;
            lightId = Arrays.copyOf(lightId, n);
            lightType = Arrays.copyOf(lightType, n);
            lightName = Arrays.copyOf(lightName, n);
            lightFlags = Arrays.copyOf(lightFlags, n);
            lightData = Arrays.copyOf(lightData, n * LIGHT_FLOATS);
        }
        int tail = lightCount - index;
        if (tail > 0) {
            System.arraycopy(lightId, index, lightId, index + 1, tail);
            System.arraycopy(lightType, index, lightType, index + 1, tail);
            System.arraycopy(lightName, index, lightName, index + 1, tail);
            System.arraycopy(lightFlags, index, lightFlags, index + 1, tail);
            System.arraycopy(
                    lightData,
                    index * LIGHT_FLOATS,
                    lightData,
                    (index + 1) * LIGHT_FLOATS,
                    tail * LIGHT_FLOATS);
        }
        lightId[index] = id;
        lightType[index] = type;
        lightName[index] = name;
        lightFlags[index] = flags;
        Arrays.fill(lightData, index * LIGHT_FLOATS, (index + 1) * LIGHT_FLOATS, 0);
        lightCount++;
        indexInsert(id, KIND_LIGHT, index, lightId, lightCount);
        noteId(id);
        version++;
        return index;
    }

    /** Removes light {@code index}. */
    public void removeLight(int index) {
        int removed = lightId[index];
        int tail = lightCount - index - 1;
        if (tail > 0) {
            System.arraycopy(lightId, index + 1, lightId, index, tail);
            System.arraycopy(lightType, index + 1, lightType, index, tail);
            System.arraycopy(lightName, index + 1, lightName, index, tail);
            System.arraycopy(lightFlags, index + 1, lightFlags, index, tail);
            System.arraycopy(
                    lightData,
                    (index + 1) * LIGHT_FLOATS,
                    lightData,
                    index * LIGHT_FLOATS,
                    tail * LIGHT_FLOATS);
        }
        lightCount--;
        indexRemove(removed, KIND_LIGHT, index, lightId, lightCount);
        lightName[lightCount] = null;
        version++;
    }

    // ---- Spawns -------------------------------------------------------------------------------

    /** Returns the number of spawn points. */
    public int spawnCount() {
        return spawnCount;
    }

    /** Returns spawn {@code i}'s id. */
    public int spawnId(int i) {
        return spawnId[i];
    }

    /** Returns the index of the spawn with {@code id}, or -1. */
    public int spawnIndex(int id) {
        return indexOf(id, KIND_SPAWN);
    }

    /** Returns spawn {@code i}'s name. */
    public String spawnName(int i) {
        return spawnName[i];
    }

    /** Returns spawn {@code i}'s kind ({@code party}, {@code enemy}, ...). */
    public String spawnKind(int i) {
        return spawnKind[i];
    }

    /** Returns the spawns' floats, {@link #SPAWN_FLOATS} per spawn (position, yaw). */
    public float[] spawnData() {
        return spawnData;
    }

    /** Returns float {@code k} of spawn {@code i} (0-2 position, 3 yaw). */
    public float spawnFloat(int i, int k) {
        return spawnData[i * SPAWN_FLOATS + k];
    }

    /** Sets spawn {@code i}'s name. */
    public void setSpawnName(int i, String name) {
        spawnName[i] = name;
        version++;
    }

    /** Sets spawn {@code i}'s kind. */
    public void setSpawnKind(int i, String kind) {
        spawnKind[i] = kind;
        version++;
    }

    /** Sets float {@code k} of spawn {@code i}. */
    public void setSpawnFloat(int i, int k, float v) {
        spawnData[i * SPAWN_FLOATS + k] = v;
        version++;
    }

    /** Appends a spawn point with a new id; returns its index. */
    public int addSpawn(String name, String kind, float x, float y, float z, float yaw) {
        int i = insertSpawn(spawnCount, allocateId(), name, kind);
        int o = i * SPAWN_FLOATS;
        spawnData[o] = x;
        spawnData[o + 1] = y;
        spawnData[o + 2] = z;
        spawnData[o + 3] = yaw;
        return i;
    }

    /** Inserts a spawn row at {@code index} with the given id; floats are zero. */
    public int insertSpawn(int index, int id, String name, String kind) {
        if (spawnCount == spawnId.length) {
            int n = spawnCount * 2;
            spawnId = Arrays.copyOf(spawnId, n);
            spawnName = Arrays.copyOf(spawnName, n);
            spawnKind = Arrays.copyOf(spawnKind, n);
            spawnData = Arrays.copyOf(spawnData, n * SPAWN_FLOATS);
        }
        int tail = spawnCount - index;
        if (tail > 0) {
            System.arraycopy(spawnId, index, spawnId, index + 1, tail);
            System.arraycopy(spawnName, index, spawnName, index + 1, tail);
            System.arraycopy(spawnKind, index, spawnKind, index + 1, tail);
            System.arraycopy(
                    spawnData,
                    index * SPAWN_FLOATS,
                    spawnData,
                    (index + 1) * SPAWN_FLOATS,
                    tail * SPAWN_FLOATS);
        }
        spawnId[index] = id;
        spawnName[index] = name;
        spawnKind[index] = kind;
        Arrays.fill(spawnData, index * SPAWN_FLOATS, (index + 1) * SPAWN_FLOATS, 0);
        spawnCount++;
        indexInsert(id, KIND_SPAWN, index, spawnId, spawnCount);
        noteId(id);
        version++;
        return index;
    }

    /** Removes spawn {@code index}. */
    public void removeSpawn(int index) {
        int removed = spawnId[index];
        int tail = spawnCount - index - 1;
        if (tail > 0) {
            System.arraycopy(spawnId, index + 1, spawnId, index, tail);
            System.arraycopy(spawnName, index + 1, spawnName, index, tail);
            System.arraycopy(spawnKind, index + 1, spawnKind, index, tail);
            System.arraycopy(
                    spawnData,
                    (index + 1) * SPAWN_FLOATS,
                    spawnData,
                    index * SPAWN_FLOATS,
                    tail * SPAWN_FLOATS);
        }
        spawnCount--;
        indexRemove(removed, KIND_SPAWN, index, spawnId, spawnCount);
        spawnName[spawnCount] = null;
        spawnKind[spawnCount] = null;
        version++;
    }

    // ---- Triggers -----------------------------------------------------------------------------

    /** Returns the number of triggers. */
    public int triggerCount() {
        return triggerCount;
    }

    /** Returns trigger {@code i}'s id. */
    public int triggerId(int i) {
        return triggerId[i];
    }

    /** Returns the index of the trigger with {@code id}, or -1. */
    public int triggerIndex(int id) {
        return indexOf(id, KIND_TRIGGER);
    }

    /** Returns trigger {@code i}'s name. */
    public String triggerName(int i) {
        return triggerName[i];
    }

    /** Returns trigger {@code i}'s kind. */
    public String triggerKind(int i) {
        return triggerKind[i];
    }

    /** Returns trigger {@code i}'s payload (what the game does with it). */
    public String triggerPayload(int i) {
        return triggerPayload[i];
    }

    /** Returns trigger {@code i}'s flags. */
    public int triggerFlags(int i) {
        return triggerFlags[i];
    }

    /** Returns the triggers' floats, {@link #TRIGGER_FLOATS} per trigger. */
    public float[] triggerData() {
        return triggerData;
    }

    /** Returns float {@code k} of trigger {@code i} (0-2 center, 3-5 size, 6 yaw). */
    public float triggerFloat(int i, int k) {
        return triggerData[i * TRIGGER_FLOATS + k];
    }

    /** Sets trigger {@code i}'s name. */
    public void setTriggerName(int i, String name) {
        triggerName[i] = name;
        version++;
    }

    /** Sets trigger {@code i}'s kind. */
    public void setTriggerKind(int i, String kind) {
        triggerKind[i] = kind;
        version++;
    }

    /** Sets trigger {@code i}'s payload. */
    public void setTriggerPayload(int i, String payload) {
        triggerPayload[i] = payload;
        version++;
    }

    /** Sets trigger {@code i}'s flags. */
    public void setTriggerFlags(int i, int flags) {
        triggerFlags[i] = flags;
        version++;
    }

    /** Sets float {@code k} of trigger {@code i}. */
    public void setTriggerFloat(int i, int k, float v) {
        triggerData[i * TRIGGER_FLOATS + k] = v;
        version++;
    }

    /** Appends a trigger box with a new id; returns its index. */
    public int addTrigger(
            String name,
            String kind,
            String payload,
            int flags,
            float cx,
            float cy,
            float cz,
            float sx,
            float sy,
            float sz,
            float yaw) {
        int i = insertTrigger(triggerCount, allocateId(), name, kind, payload, flags);
        int o = i * TRIGGER_FLOATS;
        triggerData[o] = cx;
        triggerData[o + 1] = cy;
        triggerData[o + 2] = cz;
        triggerData[o + 3] = sx;
        triggerData[o + 4] = sy;
        triggerData[o + 5] = sz;
        triggerData[o + 6] = yaw;
        return i;
    }

    /** Inserts a trigger row at {@code index} with the given id; floats are zero. */
    public int insertTrigger(
            int index, int id, String name, String kind, String payload, int flags) {
        if (triggerCount == triggerId.length) {
            int n = triggerCount * 2;
            triggerId = Arrays.copyOf(triggerId, n);
            triggerName = Arrays.copyOf(triggerName, n);
            triggerKind = Arrays.copyOf(triggerKind, n);
            triggerPayload = Arrays.copyOf(triggerPayload, n);
            triggerFlags = Arrays.copyOf(triggerFlags, n);
            triggerData = Arrays.copyOf(triggerData, n * TRIGGER_FLOATS);
        }
        int tail = triggerCount - index;
        if (tail > 0) {
            System.arraycopy(triggerId, index, triggerId, index + 1, tail);
            System.arraycopy(triggerName, index, triggerName, index + 1, tail);
            System.arraycopy(triggerKind, index, triggerKind, index + 1, tail);
            System.arraycopy(triggerPayload, index, triggerPayload, index + 1, tail);
            System.arraycopy(triggerFlags, index, triggerFlags, index + 1, tail);
            System.arraycopy(
                    triggerData,
                    index * TRIGGER_FLOATS,
                    triggerData,
                    (index + 1) * TRIGGER_FLOATS,
                    tail * TRIGGER_FLOATS);
        }
        triggerId[index] = id;
        triggerName[index] = name;
        triggerKind[index] = kind;
        triggerPayload[index] = payload;
        triggerFlags[index] = flags;
        Arrays.fill(triggerData, index * TRIGGER_FLOATS, (index + 1) * TRIGGER_FLOATS, 0);
        triggerCount++;
        indexInsert(id, KIND_TRIGGER, index, triggerId, triggerCount);
        noteId(id);
        version++;
        return index;
    }

    /** Removes trigger {@code index}. */
    public void removeTrigger(int index) {
        int removed = triggerId[index];
        int tail = triggerCount - index - 1;
        if (tail > 0) {
            System.arraycopy(triggerId, index + 1, triggerId, index, tail);
            System.arraycopy(triggerName, index + 1, triggerName, index, tail);
            System.arraycopy(triggerKind, index + 1, triggerKind, index, tail);
            System.arraycopy(triggerPayload, index + 1, triggerPayload, index, tail);
            System.arraycopy(triggerFlags, index + 1, triggerFlags, index, tail);
            System.arraycopy(
                    triggerData,
                    (index + 1) * TRIGGER_FLOATS,
                    triggerData,
                    index * TRIGGER_FLOATS,
                    tail * TRIGGER_FLOATS);
        }
        triggerCount--;
        indexRemove(removed, KIND_TRIGGER, index, triggerId, triggerCount);
        triggerName[triggerCount] = null;
        triggerKind[triggerCount] = null;
        triggerPayload[triggerCount] = null;
        version++;
    }

    // ---- Navigation areas and links -----------------------------------------------------------

    /** Returns the number of navigation area volumes. */
    public int areaCount() {
        return areaCount;
    }

    /** Returns area volume {@code i}'s area id (0-63). */
    public int areaArea(int i) {
        return areaArea[i];
    }

    /** Returns area volume {@code i}'s bottom. */
    public float areaYMin(int i) {
        return areaYMin[i];
    }

    /** Returns area volume {@code i}'s top. */
    public float areaYMax(int i) {
        return areaYMax[i];
    }

    /** Returns area volume {@code i}'s XZ polygon ({@code x, z} pairs; do not modify). */
    public float[] areaPolygon(int i) {
        return areaPolygon[i];
    }

    /** Appends an area volume; returns its index. */
    public int addArea(int area, float yMin, float yMax, float[] polygonXz) {
        if (polygonXz.length < 6 || polygonXz.length % 2 != 0) {
            throw new IllegalArgumentException("a polygon needs at least three x, z pairs");
        }
        if (areaCount == areaArea.length) {
            int n = areaCount * 2;
            areaArea = Arrays.copyOf(areaArea, n);
            areaYMin = Arrays.copyOf(areaYMin, n);
            areaYMax = Arrays.copyOf(areaYMax, n);
            areaPolygon = Arrays.copyOf(areaPolygon, n);
        }
        areaArea[areaCount] = area;
        areaYMin[areaCount] = yMin;
        areaYMax[areaCount] = yMax;
        areaPolygon[areaCount] = polygonXz.clone();
        version++;
        return areaCount++;
    }

    /** Sets area volume {@code i}'s navigation area. */
    public void setArea(int i, int area, float yMin, float yMax) {
        areaArea[i] = area;
        areaYMin[i] = yMin;
        areaYMax[i] = yMax;
        version++;
    }

    /** Replaces area volume {@code i}'s polygon (copied). */
    public void setAreaPolygon(int i, float[] polygonXz) {
        areaPolygon[i] = polygonXz.clone();
        version++;
    }

    /** Inserts an area volume at {@code index} (an undo putting a removed one back). */
    public int insertArea(int index, int area, float yMin, float yMax, float[] polygonXz) {
        addArea(area, yMin, yMax, polygonXz);
        for (int i = areaCount - 1; i > index; i--) {
            areaArea[i] = areaArea[i - 1];
            areaYMin[i] = areaYMin[i - 1];
            areaYMax[i] = areaYMax[i - 1];
            areaPolygon[i] = areaPolygon[i - 1];
        }
        areaArea[index] = area;
        areaYMin[index] = yMin;
        areaYMax[index] = yMax;
        areaPolygon[index] = polygonXz.clone();
        return index;
    }

    /** Removes area volume {@code index}. */
    public void removeArea(int index) {
        int tail = areaCount - index - 1;
        if (tail > 0) {
            System.arraycopy(areaArea, index + 1, areaArea, index, tail);
            System.arraycopy(areaYMin, index + 1, areaYMin, index, tail);
            System.arraycopy(areaYMax, index + 1, areaYMax, index, tail);
            System.arraycopy(areaPolygon, index + 1, areaPolygon, index, tail);
        }
        areaCount--;
        areaPolygon[areaCount] = null;
        version++;
    }

    /** Returns the number of off-mesh links. */
    public int linkCount() {
        return linkCount;
    }

    /** Returns link {@code i}'s flags. */
    public int linkFlags(int i) {
        return linkFlags[i];
    }

    /** Returns link {@code i}'s navigation area. */
    public int linkArea(int i) {
        return linkArea[i];
    }

    /** Returns link {@code i}'s user id. */
    public int linkUserId(int i) {
        return linkUserId[i];
    }

    /** Returns float {@code k} of link {@code i} (0-2 start, 3-5 end, 6 radius). */
    public float linkFloat(int i, int k) {
        return linkData[i * LINK_FLOATS + k];
    }

    /** Sets float {@code k} of link {@code i}. */
    public void setLinkFloat(int i, int k, float v) {
        linkData[i * LINK_FLOATS + k] = v;
        version++;
    }

    /** Appends an off-mesh link; returns its index. */
    public int addLink(
            float sx,
            float sy,
            float sz,
            float ex,
            float ey,
            float ez,
            float radius,
            int flags,
            int area,
            int userId) {
        if (linkCount == linkFlags.length) {
            int n = linkCount * 2;
            linkFlags = Arrays.copyOf(linkFlags, n);
            linkArea = Arrays.copyOf(linkArea, n);
            linkUserId = Arrays.copyOf(linkUserId, n);
            linkData = Arrays.copyOf(linkData, n * LINK_FLOATS);
        }
        int i = linkCount++;
        linkFlags[i] = flags;
        linkArea[i] = area;
        linkUserId[i] = userId;
        int o = i * LINK_FLOATS;
        linkData[o] = sx;
        linkData[o + 1] = sy;
        linkData[o + 2] = sz;
        linkData[o + 3] = ex;
        linkData[o + 4] = ey;
        linkData[o + 5] = ez;
        linkData[o + 6] = radius;
        version++;
        return i;
    }

    /** Sets link {@code i}'s flags, area and user id. */
    public void setLink(int i, int flags, int area, int userId) {
        linkFlags[i] = flags;
        linkArea[i] = area;
        linkUserId[i] = userId;
        version++;
    }

    /** Inserts a link at {@code index}. */
    public int insertLink(
            int index,
            float sx,
            float sy,
            float sz,
            float ex,
            float ey,
            float ez,
            float radius,
            int flags,
            int area,
            int userId) {
        addLink(sx, sy, sz, ex, ey, ez, radius, flags, area, userId);
        for (int i = linkCount - 1; i > index; i--) {
            linkFlags[i] = linkFlags[i - 1];
            linkArea[i] = linkArea[i - 1];
            linkUserId[i] = linkUserId[i - 1];
            System.arraycopy(
                    linkData, (i - 1) * LINK_FLOATS, linkData, i * LINK_FLOATS, LINK_FLOATS);
        }
        setLink(index, flags, area, userId);
        int o = index * LINK_FLOATS;
        linkData[o] = sx;
        linkData[o + 1] = sy;
        linkData[o + 2] = sz;
        linkData[o + 3] = ex;
        linkData[o + 4] = ey;
        linkData[o + 5] = ez;
        linkData[o + 6] = radius;
        return index;
    }

    /** Removes link {@code index}. */
    public void removeLink(int index) {
        int tail = linkCount - index - 1;
        if (tail > 0) {
            System.arraycopy(linkFlags, index + 1, linkFlags, index, tail);
            System.arraycopy(linkArea, index + 1, linkArea, index, tail);
            System.arraycopy(linkUserId, index + 1, linkUserId, index, tail);
            System.arraycopy(
                    linkData,
                    (index + 1) * LINK_FLOATS,
                    linkData,
                    index * LINK_FLOATS,
                    tail * LINK_FLOATS);
        }
        linkCount--;
        version++;
    }

    // ---- Collision rules and size classes -----------------------------------------------------

    /** Returns the number of collision rules. */
    public int ruleCount() {
        return ruleCount;
    }

    /** Returns rule {@code i}'s layer glob. */
    public String ruleLayer(int i) {
        return ruleLayer[i];
    }

    /** Returns rule {@code i}'s triangle flags (see {@code physics.bvh.TriFlags}). */
    public int ruleFlags(int i) {
        return ruleFlags[i];
    }

    /** Returns rule {@code i}'s surface type. */
    public int ruleSurface(int i) {
        return ruleSurface[i];
    }

    /** Returns rule {@code i}'s navigation area. */
    public int ruleArea(int i) {
        return ruleArea[i];
    }

    /** Sets rule {@code i}. */
    public void setRule(int i, String layer, int flags, int surface, int area) {
        ruleLayer[i] = layer;
        ruleFlags[i] = flags;
        ruleSurface[i] = surface;
        ruleArea[i] = area;
        version++;
    }

    /** Appends a collision rule; returns its index. */
    public int addRule(String layer, int flags, int surface, int area) {
        if (ruleCount == ruleLayer.length) {
            int n = ruleCount * 2;
            ruleLayer = Arrays.copyOf(ruleLayer, n);
            ruleFlags = Arrays.copyOf(ruleFlags, n);
            ruleSurface = Arrays.copyOf(ruleSurface, n);
            ruleArea = Arrays.copyOf(ruleArea, n);
        }
        int i = ruleCount++;
        setRule(i, layer, flags, surface, area);
        return i;
    }

    /** Inserts a rule at {@code index} (rules match in order, so position matters). */
    public int insertRule(int index, String layer, int flags, int surface, int area) {
        addRule(layer, flags, surface, area);
        for (int i = ruleCount - 1; i > index; i--) {
            ruleLayer[i] = ruleLayer[i - 1];
            ruleFlags[i] = ruleFlags[i - 1];
            ruleSurface[i] = ruleSurface[i - 1];
            ruleArea[i] = ruleArea[i - 1];
        }
        setRule(index, layer, flags, surface, area);
        return index;
    }

    /** Removes rule {@code index}. */
    public void removeRule(int index) {
        int tail = ruleCount - index - 1;
        if (tail > 0) {
            System.arraycopy(ruleLayer, index + 1, ruleLayer, index, tail);
            System.arraycopy(ruleFlags, index + 1, ruleFlags, index, tail);
            System.arraycopy(ruleSurface, index + 1, ruleSurface, index, tail);
            System.arraycopy(ruleArea, index + 1, ruleArea, index, tail);
        }
        ruleCount--;
        ruleLayer[ruleCount] = null;
        version++;
    }

    /** Returns the number of agent size classes. */
    public int sizeClassCount() {
        return sizeClassCount;
    }

    /** Returns size class {@code i}'s name. */
    public String sizeClassName(int i) {
        return sizeClassName[i];
    }

    /** Returns float {@code k} of size class {@code i} (0 radius, 1 height, 2 climb). */
    public float sizeClassFloat(int i, int k) {
        return sizeClassData[i * SIZE_CLASS_FLOATS + k];
    }

    /** Appends a size class; returns its index. */
    public int addSizeClass(String name, float radius, float height, float climb) {
        if (sizeClassCount == sizeClassName.length) {
            int n = sizeClassCount * 2;
            sizeClassName = Arrays.copyOf(sizeClassName, n);
            sizeClassData = Arrays.copyOf(sizeClassData, n * SIZE_CLASS_FLOATS);
        }
        int i = sizeClassCount++;
        sizeClassName[i] = name;
        int o = i * SIZE_CLASS_FLOATS;
        sizeClassData[o] = radius;
        sizeClassData[o + 1] = height;
        sizeClassData[o + 2] = climb;
        version++;
        return i;
    }

    /** Sets size class {@code i}. */
    public void setSizeClass(int i, String name, float radius, float height, float climb) {
        sizeClassName[i] = name;
        int o = i * SIZE_CLASS_FLOATS;
        sizeClassData[o] = radius;
        sizeClassData[o + 1] = height;
        sizeClassData[o + 2] = climb;
        version++;
    }

    /** Inserts a size class at {@code index}. */
    public int insertSizeClass(int index, String name, float radius, float height, float climb) {
        addSizeClass(name, radius, height, climb);
        for (int i = sizeClassCount - 1; i > index; i--) {
            sizeClassName[i] = sizeClassName[i - 1];
            System.arraycopy(
                    sizeClassData,
                    (i - 1) * SIZE_CLASS_FLOATS,
                    sizeClassData,
                    i * SIZE_CLASS_FLOATS,
                    SIZE_CLASS_FLOATS);
        }
        setSizeClass(index, name, radius, height, climb);
        return index;
    }

    /** Removes size class {@code index}. */
    public void removeSizeClass(int index) {
        int tail = sizeClassCount - index - 1;
        if (tail > 0) {
            System.arraycopy(sizeClassName, index + 1, sizeClassName, index, tail);
            System.arraycopy(
                    sizeClassData,
                    (index + 1) * SIZE_CLASS_FLOATS,
                    sizeClassData,
                    index * SIZE_CLASS_FLOATS,
                    tail * SIZE_CLASS_FLOATS);
        }
        sizeClassCount--;
        sizeClassName[sizeClassCount] = null;
        version++;
    }

    // ---- File -------------------------------------------------------------------------------

    /**
     * Parses a {@code .jlevel} file into a new level.
     *
     * @throws AssetFormatException if the file is malformed
     */
    public static LevelData read(MemorySegment file) throws AssetFormatException {
        checkRange(file, 0, HEADER_SIZE, ".jlevel header");
        if (file.get(U32, 0) != MAGIC) {
            throw new AssetFormatException("not a .jlevel file");
        }
        int version = Short.toUnsignedInt(file.get(U16, 4));
        if (version != VERSION) {
            throw new AssetFormatException(".jlevel version " + version + ", expected " + VERSION);
        }
        int prefabs = count(file, 8, "prefab");
        int placements = count(file, 12, "placement");
        int lights = count(file, 16, "light");
        int spawns = count(file, 20, "spawn");
        int triggers = count(file, 24, "trigger");
        int areas = count(file, 28, "area");
        int links = count(file, 32, "link");
        int rules = count(file, 36, "rule");
        int sizes = count(file, 40, "size class");
        int nextId = file.get(U32, 44);
        long prefabsAt = file.get(U64, 48);
        long placementsAt = file.get(U64, 56);
        long lightsAt = file.get(U64, 64);
        long spawnsAt = file.get(U64, 72);
        long triggersAt = file.get(U64, 80);
        long areasAt = file.get(U64, 88);
        long linksAt = file.get(U64, 96);
        long rulesAt = file.get(U64, 104);
        long sizesAt = file.get(U64, 112);
        long settingsAt = file.get(U64, 120);
        long polygonsAt = file.get(U64, 128);
        long stringsAt = file.get(U64, 136);
        long stringBytes = file.get(U64, 144);
        if (file.get(U64, 152) != file.byteSize()) {
            throw new AssetFormatException(".jlevel size does not match its header");
        }
        checkRange(file, prefabsAt, (long) PREFAB_SIZE * prefabs, ".jlevel prefabs");
        checkRange(file, placementsAt, (long) PLACEMENT_SIZE * placements, ".jlevel placements");
        checkRange(file, lightsAt, (long) LIGHT_SIZE * lights, ".jlevel lights");
        checkRange(file, spawnsAt, (long) SPAWN_SIZE * spawns, ".jlevel spawns");
        checkRange(file, triggersAt, (long) TRIGGER_SIZE * triggers, ".jlevel triggers");
        checkRange(file, areasAt, (long) AREA_SIZE * areas, ".jlevel areas");
        checkRange(file, linksAt, (long) LINK_SIZE * links, ".jlevel links");
        checkRange(file, rulesAt, (long) RULE_SIZE * rules, ".jlevel rules");
        checkRange(file, sizesAt, (long) SIZE_CLASS_SIZE * sizes, ".jlevel size classes");
        checkRange(file, settingsAt, SETTINGS_SIZE, ".jlevel settings");
        checkRange(file, stringsAt, stringBytes, ".jlevel strings");
        MemorySegment strings = file.asSlice(stringsAt, stringBytes);

        LevelData level = new LevelData();
        for (int p = 0; p < prefabs; p++) {
            long at = prefabsAt + (long) PREFAB_SIZE * p;
            // Appended as stored: placements index this table, so no deduplication here.
            level.appendPrefab(string(strings, file, at), string(strings, file, at + 8));
        }
        for (int i = 0; i < placements; i++) {
            long at = placementsAt + (long) PLACEMENT_SIZE * i;
            int prefab = file.get(U32, at + 4);
            if (prefab < 0 || prefab >= prefabs) {
                throw new AssetFormatException(".jlevel placement " + i + " prefab " + prefab);
            }
            level.insertPlacement(
                    i,
                    file.get(U32, at),
                    prefab,
                    string(strings, file, at + 8),
                    string(strings, file, at + 16),
                    file.get(U32, at + 24),
                    file.get(U32, at + 28));
            floats(file, at + 32, level.placementData, i * PLACEMENT_FLOATS, PLACEMENT_FLOATS);
        }
        for (int i = 0; i < lights; i++) {
            long at = lightsAt + (long) LIGHT_SIZE * i;
            int type = file.get(U32, at + 4);
            if (type != LIGHT_POINT && type != LIGHT_SPOT) {
                throw new AssetFormatException(".jlevel light type " + type);
            }
            level.insertLight(
                    i,
                    file.get(U32, at),
                    type,
                    string(strings, file, at + 8),
                    file.get(U32, at + 68));
            floats(file, at + 16, level.lightData, i * LIGHT_FLOATS, LIGHT_FLOATS);
        }
        for (int i = 0; i < spawns; i++) {
            long at = spawnsAt + (long) SPAWN_SIZE * i;
            level.insertSpawn(
                    i,
                    file.get(U32, at),
                    string(strings, file, at + 4),
                    string(strings, file, at + 12));
            floats(file, at + 20, level.spawnData, i * SPAWN_FLOATS, SPAWN_FLOATS);
        }
        for (int i = 0; i < triggers; i++) {
            long at = triggersAt + (long) TRIGGER_SIZE * i;
            level.insertTrigger(
                    i,
                    file.get(U32, at),
                    string(strings, file, at + 4),
                    string(strings, file, at + 12),
                    string(strings, file, at + 20),
                    file.get(U32, at + 56));
            floats(file, at + 28, level.triggerData, i * TRIGGER_FLOATS, TRIGGER_FLOATS);
        }
        for (int i = 0; i < areas; i++) {
            long at = areasAt + (long) AREA_SIZE * i;
            int points = file.get(U32, at + 12);
            int first = file.get(U32, at + 16);
            if (points < 3 || points > 1 << 16) {
                throw new AssetFormatException(".jlevel area " + i + " has " + points + " points");
            }
            long polyAt = polygonsAt + 8L * first;
            checkRange(file, polyAt, 8L * points, ".jlevel polygon");
            float[] poly = new float[points * 2];
            floats(file, polyAt, poly, 0, points * 2);
            level.addArea(file.get(U32, at), file.get(F32, at + 4), file.get(F32, at + 8), poly);
        }
        for (int i = 0; i < links; i++) {
            long at = linksAt + (long) LINK_SIZE * i;
            level.addLink(
                    file.get(F32, at),
                    file.get(F32, at + 4),
                    file.get(F32, at + 8),
                    file.get(F32, at + 12),
                    file.get(F32, at + 16),
                    file.get(F32, at + 20),
                    file.get(F32, at + 24),
                    file.get(U32, at + 28),
                    file.get(U32, at + 32),
                    file.get(U32, at + 36));
        }
        for (int i = 0; i < rules; i++) {
            long at = rulesAt + (long) RULE_SIZE * i;
            level.addRule(
                    string(strings, file, at),
                    file.get(U32, at + 8),
                    file.get(U32, at + 12),
                    file.get(U32, at + 16));
        }
        for (int i = 0; i < sizes; i++) {
            long at = sizesAt + (long) SIZE_CLASS_SIZE * i;
            level.addSizeClass(
                    string(strings, file, at),
                    file.get(F32, at + 8),
                    file.get(F32, at + 12),
                    file.get(F32, at + 16));
        }
        Settings s = level.settings;
        s.navCellSize = file.get(F32, settingsAt);
        s.navCellHeight = file.get(F32, settingsAt + 4);
        s.navTileSize = file.get(F32, settingsAt + 8);
        s.navMaxSlope = file.get(F32, settingsAt + 12);
        s.occlusionCellSize = file.get(F32, settingsAt + 16);
        s.occlusionYMin = file.get(F32, settingsAt + 20);
        s.occlusionYMax = file.get(F32, settingsAt + 24);
        s.occlusionEnabled = file.get(U32, settingsAt + 28) != 0;
        floats(file, settingsAt + 32, s.sunDirection, 0, 3);
        floats(file, settingsAt + 44, s.sunColor, 0, 3);
        s.exposure = file.get(F32, settingsAt + 56);
        s.environmentSpecular = file.get(F32, settingsAt + 60);
        s.environmentDiffuse = file.get(F32, settingsAt + 64);
        s.environment = string(strings, file, settingsAt + 68);
        s.gridStep = file.get(F32, settingsAt + 76);
        level.nextId = Math.max(level.nextId, nextId);
        level.version = 0;
        return level;
    }

    private static void floats(MemorySegment file, long at, float[] out, int offset, int n) {
        for (int k = 0; k < n; k++) {
            out[offset + k] = file.get(F32, at + 4L * k);
        }
    }

    private static int count(MemorySegment file, long at, String what) throws AssetFormatException {
        int n = file.get(U32, at);
        if (n < 0 || n > 1 << 24) {
            throw new AssetFormatException(".jlevel has " + n + " " + what + "s");
        }
        return n;
    }

    private static String string(MemorySegment strings, MemorySegment file, long at)
            throws AssetFormatException {
        int offset = file.get(U32, at);
        int length = file.get(U32, at + 4);
        if (length == 0) {
            return "";
        }
        checkRange(
                strings,
                Integer.toUnsignedLong(offset),
                Integer.toUnsignedLong(length),
                ".jlevel string");
        byte[] utf8 = strings.asSlice(offset, length).toArray(ValueLayout.JAVA_BYTE);
        return new String(utf8, StandardCharsets.UTF_8);
    }

    /** Encodes the level. */
    public byte[] toBytes() {
        ByteArrayOutputStream strings = new ByteArrayOutputStream();
        int polygonFloats = 0;
        for (int i = 0; i < areaCount; i++) {
            polygonFloats += areaPolygon[i].length;
        }
        long prefabsAt = HEADER_SIZE;
        long placementsAt = align(prefabsAt + (long) PREFAB_SIZE * prefabCount, 16);
        long lightsAt = align(placementsAt + (long) PLACEMENT_SIZE * placementCount, 16);
        long spawnsAt = align(lightsAt + (long) LIGHT_SIZE * lightCount, 16);
        long triggersAt = align(spawnsAt + (long) SPAWN_SIZE * spawnCount, 16);
        long areasAt = align(triggersAt + (long) TRIGGER_SIZE * triggerCount, 16);
        long linksAt = align(areasAt + (long) AREA_SIZE * areaCount, 16);
        long rulesAt = align(linksAt + (long) LINK_SIZE * linkCount, 16);
        long sizesAt = align(rulesAt + (long) RULE_SIZE * ruleCount, 16);
        long settingsAt = align(sizesAt + (long) SIZE_CLASS_SIZE * sizeClassCount, 16);
        long polygonsAt = align(settingsAt + SETTINGS_SIZE, 16);
        long stringsAt = align(polygonsAt + 4L * polygonFloats, 16);
        // Strings are interned in order of first use; the table is built before the bytes.
        ByteBuffer b = ByteBuffer.allocate((int) stringsAt).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0, MAGIC);
        b.putShort(4, (short) VERSION);
        b.putShort(6, (short) 0);
        b.putInt(8, prefabCount);
        b.putInt(12, placementCount);
        b.putInt(16, lightCount);
        b.putInt(20, spawnCount);
        b.putInt(24, triggerCount);
        b.putInt(28, areaCount);
        b.putInt(32, linkCount);
        b.putInt(36, ruleCount);
        b.putInt(40, sizeClassCount);
        b.putInt(44, nextId);
        b.putLong(48, prefabsAt);
        b.putLong(56, placementsAt);
        b.putLong(64, lightsAt);
        b.putLong(72, spawnsAt);
        b.putLong(80, triggersAt);
        b.putLong(88, areasAt);
        b.putLong(96, linksAt);
        b.putLong(104, rulesAt);
        b.putLong(112, sizesAt);
        b.putLong(120, settingsAt);
        b.putLong(128, polygonsAt);
        b.putLong(136, stringsAt);
        for (int p = 0; p < prefabCount; p++) {
            int at = (int) (prefabsAt + (long) PREFAB_SIZE * p);
            putString(b, at, strings, prefabScene[p]);
            putString(b, at + 8, strings, prefabNode[p]);
        }
        for (int i = 0; i < placementCount; i++) {
            int at = (int) (placementsAt + (long) PLACEMENT_SIZE * i);
            b.putInt(at, placementId[i]);
            b.putInt(at + 4, placementPrefab[i]);
            putString(b, at + 8, strings, placementName[i]);
            putString(b, at + 16, strings, placementLayer[i]);
            b.putInt(at + 24, placementFlags[i]);
            b.putInt(at + 28, placementCollision[i]);
            putFloats(b, at + 32, placementData, i * PLACEMENT_FLOATS, PLACEMENT_FLOATS);
        }
        for (int i = 0; i < lightCount; i++) {
            int at = (int) (lightsAt + (long) LIGHT_SIZE * i);
            b.putInt(at, lightId[i]);
            b.putInt(at + 4, lightType[i]);
            putString(b, at + 8, strings, lightName[i]);
            putFloats(b, at + 16, lightData, i * LIGHT_FLOATS, LIGHT_FLOATS);
            b.putInt(at + 68, lightFlags[i]);
        }
        for (int i = 0; i < spawnCount; i++) {
            int at = (int) (spawnsAt + (long) SPAWN_SIZE * i);
            b.putInt(at, spawnId[i]);
            putString(b, at + 4, strings, spawnName[i]);
            putString(b, at + 12, strings, spawnKind[i]);
            putFloats(b, at + 20, spawnData, i * SPAWN_FLOATS, SPAWN_FLOATS);
        }
        for (int i = 0; i < triggerCount; i++) {
            int at = (int) (triggersAt + (long) TRIGGER_SIZE * i);
            b.putInt(at, triggerId[i]);
            putString(b, at + 4, strings, triggerName[i]);
            putString(b, at + 12, strings, triggerKind[i]);
            putString(b, at + 20, strings, triggerPayload[i]);
            putFloats(b, at + 28, triggerData, i * TRIGGER_FLOATS, TRIGGER_FLOATS);
            b.putInt(at + 56, triggerFlags[i]);
        }
        int firstPoint = 0;
        for (int i = 0; i < areaCount; i++) {
            int at = (int) (areasAt + (long) AREA_SIZE * i);
            float[] poly = areaPolygon[i];
            b.putInt(at, areaArea[i]);
            b.putFloat(at + 4, areaYMin[i]);
            b.putFloat(at + 8, areaYMax[i]);
            b.putInt(at + 12, poly.length / 2);
            b.putInt(at + 16, firstPoint);
            putFloats(b, (int) (polygonsAt + 8L * firstPoint), poly, 0, poly.length);
            firstPoint += poly.length / 2;
        }
        for (int i = 0; i < linkCount; i++) {
            int at = (int) (linksAt + (long) LINK_SIZE * i);
            putFloats(b, at, linkData, i * LINK_FLOATS, LINK_FLOATS);
            b.putInt(at + 28, linkFlags[i]);
            b.putInt(at + 32, linkArea[i]);
            b.putInt(at + 36, linkUserId[i]);
        }
        for (int i = 0; i < ruleCount; i++) {
            int at = (int) (rulesAt + (long) RULE_SIZE * i);
            putString(b, at, strings, ruleLayer[i]);
            b.putInt(at + 8, ruleFlags[i]);
            b.putInt(at + 12, ruleSurface[i]);
            b.putInt(at + 16, ruleArea[i]);
        }
        for (int i = 0; i < sizeClassCount; i++) {
            int at = (int) (sizesAt + (long) SIZE_CLASS_SIZE * i);
            putString(b, at, strings, sizeClassName[i]);
            putFloats(b, at + 8, sizeClassData, i * SIZE_CLASS_FLOATS, SIZE_CLASS_FLOATS);
        }
        int at = (int) settingsAt;
        Settings s = settings;
        b.putFloat(at, s.navCellSize);
        b.putFloat(at + 4, s.navCellHeight);
        b.putFloat(at + 8, s.navTileSize);
        b.putFloat(at + 12, s.navMaxSlope);
        b.putFloat(at + 16, s.occlusionCellSize);
        b.putFloat(at + 20, s.occlusionYMin);
        b.putFloat(at + 24, s.occlusionYMax);
        b.putInt(at + 28, s.occlusionEnabled ? 1 : 0);
        putFloats(b, at + 32, s.sunDirection, 0, 3);
        putFloats(b, at + 44, s.sunColor, 0, 3);
        b.putFloat(at + 56, s.exposure);
        b.putFloat(at + 60, s.environmentSpecular);
        b.putFloat(at + 64, s.environmentDiffuse);
        putString(b, at + 68, strings, s.environment);
        b.putFloat(at + 76, s.gridStep);
        byte[] stringBytes = strings.toByteArray();
        long fileSize = align(stringsAt + stringBytes.length, 16);
        b.putLong(144, stringBytes.length);
        b.putLong(152, fileSize);
        byte[] out = Arrays.copyOf(b.array(), (int) fileSize);
        System.arraycopy(stringBytes, 0, out, (int) stringsAt, stringBytes.length);
        return out;
    }

    private static void putString(ByteBuffer b, int at, ByteArrayOutputStream strings, String s) {
        if (s == null || s.isEmpty()) {
            b.putInt(at, 0);
            b.putInt(at + 4, 0);
            return;
        }
        byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
        b.putInt(at, strings.size());
        b.putInt(at + 4, utf8.length);
        strings.write(utf8, 0, utf8.length);
    }

    private static void putFloats(ByteBuffer b, int at, float[] src, int offset, int n) {
        for (int k = 0; k < n; k++) {
            b.putFloat(at + 4 * k, src[offset + k]);
        }
    }

    // ---- Inspection -------------------------------------------------------------------------

    /** Dumps the level as JSON for review (nothing reads it back). */
    public String toJson() {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("{\n  \"version\": ").append(VERSION).append(",\n  \"nextId\": ").append(nextId);
        sb.append(",\n  \"prefabs\": [");
        for (int p = 0; p < prefabCount; p++) {
            sb.append(p == 0 ? "\n" : ",\n").append("    {\"scene\": ");
            quote(sb, prefabScene[p]).append(", \"node\": ");
            quote(sb, prefabNode[p]).append('}');
        }
        sb.append("\n  ],\n  \"placements\": [");
        for (int i = 0; i < placementCount; i++) {
            sb.append(i == 0 ? "\n" : ",\n").append("    {\"id\": ").append(placementId[i]);
            sb.append(", \"prefab\": ").append(placementPrefab[i]).append(", \"name\": ");
            quote(sb, placementName[i]).append(", \"layer\": ");
            quote(sb, placementLayer[i]);
            sb.append(", \"flags\": ").append(placementFlags[i]);
            sb.append(", \"collision\": ").append(placementCollision[i]);
            sb.append(", \"transform\": ");
            floats(sb, placementData, i * PLACEMENT_FLOATS, PLACEMENT_FLOATS).append('}');
        }
        sb.append("\n  ],\n  \"lights\": [");
        for (int i = 0; i < lightCount; i++) {
            sb.append(i == 0 ? "\n" : ",\n").append("    {\"id\": ").append(lightId[i]);
            sb.append(", \"type\": ").append(lightType[i]).append(", \"name\": ");
            quote(sb, lightName[i]).append(", \"flags\": ").append(lightFlags[i]);
            sb.append(", \"data\": ");
            floats(sb, lightData, i * LIGHT_FLOATS, LIGHT_FLOATS).append('}');
        }
        sb.append("\n  ],\n  \"spawns\": [");
        for (int i = 0; i < spawnCount; i++) {
            sb.append(i == 0 ? "\n" : ",\n").append("    {\"id\": ").append(spawnId[i]);
            sb.append(", \"name\": ");
            quote(sb, spawnName[i]).append(", \"kind\": ");
            quote(sb, spawnKind[i]).append(", \"data\": ");
            floats(sb, spawnData, i * SPAWN_FLOATS, SPAWN_FLOATS).append('}');
        }
        sb.append("\n  ],\n  \"triggers\": [");
        for (int i = 0; i < triggerCount; i++) {
            sb.append(i == 0 ? "\n" : ",\n").append("    {\"id\": ").append(triggerId[i]);
            sb.append(", \"name\": ");
            quote(sb, triggerName[i]).append(", \"kind\": ");
            quote(sb, triggerKind[i]).append(", \"payload\": ");
            quote(sb, triggerPayload[i]).append(", \"flags\": ").append(triggerFlags[i]);
            sb.append(", \"data\": ");
            floats(sb, triggerData, i * TRIGGER_FLOATS, TRIGGER_FLOATS).append('}');
        }
        sb.append("\n  ],\n  \"areas\": [");
        for (int i = 0; i < areaCount; i++) {
            sb.append(i == 0 ? "\n" : ",\n").append("    {\"area\": ").append(areaArea[i]);
            sb.append(", \"yMin\": ")
                    .append(areaYMin[i])
                    .append(", \"yMax\": ")
                    .append(areaYMax[i]);
            sb.append(", \"polygon\": ");
            floats(sb, areaPolygon[i], 0, areaPolygon[i].length).append('}');
        }
        sb.append("\n  ],\n  \"links\": [");
        for (int i = 0; i < linkCount; i++) {
            sb.append(i == 0 ? "\n" : ",\n").append("    {\"data\": ");
            floats(sb, linkData, i * LINK_FLOATS, LINK_FLOATS);
            sb.append(", \"flags\": ")
                    .append(linkFlags[i])
                    .append(", \"area\": ")
                    .append(linkArea[i]);
            sb.append(", \"userId\": ").append(linkUserId[i]).append('}');
        }
        sb.append("\n  ],\n  \"rules\": [");
        for (int i = 0; i < ruleCount; i++) {
            sb.append(i == 0 ? "\n" : ",\n").append("    {\"layer\": ");
            quote(sb, ruleLayer[i]).append(", \"flags\": ").append(ruleFlags[i]);
            sb.append(", \"surface\": ")
                    .append(ruleSurface[i])
                    .append(", \"area\": ")
                    .append(ruleArea[i])
                    .append('}');
        }
        sb.append("\n  ],\n  \"sizeClasses\": [");
        for (int i = 0; i < sizeClassCount; i++) {
            sb.append(i == 0 ? "\n" : ",\n").append("    {\"name\": ");
            quote(sb, sizeClassName[i]).append(", \"data\": ");
            floats(sb, sizeClassData, i * SIZE_CLASS_FLOATS, SIZE_CLASS_FLOATS).append('}');
        }
        Settings s = settings;
        sb.append("\n  ],\n  \"settings\": {\"navCellSize\": ").append(s.navCellSize);
        sb.append(", \"navCellHeight\": ").append(s.navCellHeight);
        sb.append(", \"navTileSize\": ").append(s.navTileSize);
        sb.append(", \"navMaxSlope\": ").append(s.navMaxSlope);
        sb.append(", \"occlusionCellSize\": ").append(s.occlusionCellSize);
        sb.append(", \"occlusionYMin\": ").append(s.occlusionYMin);
        sb.append(", \"occlusionYMax\": ").append(s.occlusionYMax);
        sb.append(", \"occlusionEnabled\": ").append(s.occlusionEnabled);
        sb.append(", \"sunDirection\": ");
        floats(sb, s.sunDirection, 0, 3).append(", \"sunColor\": ");
        floats(sb, s.sunColor, 0, 3).append(", \"exposure\": ").append(s.exposure);
        sb.append(", \"environmentSpecular\": ").append(s.environmentSpecular);
        sb.append(", \"environmentDiffuse\": ").append(s.environmentDiffuse);
        sb.append(", \"environment\": ");
        quote(sb, s.environment).append(", \"gridStep\": ").append(s.gridStep).append("}\n}\n");
        return sb.toString();
    }

    private static StringBuilder quote(StringBuilder sb, String s) {
        sb.append('"');
        if (s != null) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    default -> {
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
        }
        return sb.append('"');
    }

    private static StringBuilder floats(StringBuilder sb, float[] a, int offset, int n) {
        sb.append('[');
        for (int k = 0; k < n; k++) {
            if (k > 0) {
                sb.append(", ");
            }
            sb.append(a[offset + k]);
        }
        return sb.append(']');
    }

    /** Copies everything from {@code other} into this level (the editor's reload). */
    public void copyFrom(LevelData other) {
        ids.clear();
        prefabCount = 0;
        placementCount = 0;
        lightCount = 0;
        spawnCount = 0;
        triggerCount = 0;
        areaCount = 0;
        linkCount = 0;
        ruleCount = 0;
        sizeClassCount = 0;
        for (int p = 0; p < other.prefabCount; p++) {
            appendPrefab(other.prefabScene[p], other.prefabNode[p]);
        }
        for (int i = 0; i < other.placementCount; i++) {
            insertPlacement(
                    i,
                    other.placementId[i],
                    other.placementPrefab[i],
                    other.placementName[i],
                    other.placementLayer[i],
                    other.placementFlags[i],
                    other.placementCollision[i]);
            setPlacementTransform(i, other.placementData, i * PLACEMENT_FLOATS);
        }
        for (int i = 0; i < other.lightCount; i++) {
            insertLight(
                    i,
                    other.lightId[i],
                    other.lightType[i],
                    other.lightName[i],
                    other.lightFlags[i]);
            System.arraycopy(
                    other.lightData, i * LIGHT_FLOATS, lightData, i * LIGHT_FLOATS, LIGHT_FLOATS);
        }
        for (int i = 0; i < other.spawnCount; i++) {
            insertSpawn(i, other.spawnId[i], other.spawnName[i], other.spawnKind[i]);
            System.arraycopy(
                    other.spawnData, i * SPAWN_FLOATS, spawnData, i * SPAWN_FLOATS, SPAWN_FLOATS);
        }
        for (int i = 0; i < other.triggerCount; i++) {
            insertTrigger(
                    i,
                    other.triggerId[i],
                    other.triggerName[i],
                    other.triggerKind[i],
                    other.triggerPayload[i],
                    other.triggerFlags[i]);
            System.arraycopy(
                    other.triggerData,
                    i * TRIGGER_FLOATS,
                    triggerData,
                    i * TRIGGER_FLOATS,
                    TRIGGER_FLOATS);
        }
        for (int i = 0; i < other.areaCount; i++) {
            addArea(other.areaArea[i], other.areaYMin[i], other.areaYMax[i], other.areaPolygon[i]);
        }
        for (int i = 0; i < other.linkCount; i++) {
            float[] d = other.linkData;
            int o = i * LINK_FLOATS;
            addLink(
                    d[o],
                    d[o + 1],
                    d[o + 2],
                    d[o + 3],
                    d[o + 4],
                    d[o + 5],
                    d[o + 6],
                    other.linkFlags[i],
                    other.linkArea[i],
                    other.linkUserId[i]);
        }
        for (int i = 0; i < other.ruleCount; i++) {
            addRule(
                    other.ruleLayer[i],
                    other.ruleFlags[i],
                    other.ruleSurface[i],
                    other.ruleArea[i]);
        }
        for (int i = 0; i < other.sizeClassCount; i++) {
            float[] d = other.sizeClassData;
            int o = i * SIZE_CLASS_FLOATS;
            addSizeClass(other.sizeClassName[i], d[o], d[o + 1], d[o + 2]);
        }
        settings.copyFrom(other.settings);
        nextId = Math.max(nextId, other.nextId);
        version++;
    }
}
