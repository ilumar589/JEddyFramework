package dev.jeddy.app.world;

import dev.jeddy.asset.AssetType;
import dev.jeddy.asset.format.LevelData;
import dev.jeddy.asset.format.SceneData;
import dev.jeddy.core.collections.IntIntMap;
import dev.jeddy.core.handle.Handles;
import dev.jeddy.core.log.Log;
import dev.jeddy.core.log.Logger;
import dev.jeddy.render.RenderPacket;

import java.util.Arrays;

/**
 * Puts a {@link LevelData} into a {@link World} (TASKS M14-03, DESIGN §19): loads the scenes its
 * prefabs come from, and once they are ready instantiates every placement as a group under its own
 * root entity (D87: the subtree of a named root of a library scene, or a whole scene), with the
 * placement's transform and render flags. It keeps the placement id → root entity mapping both
 * ways, so an editor can move, add or remove placements while the game runs, and it feeds the
 * level's lights to the render packet each frame (torches flicker).
 *
 * <pre>{@code
 * loader = new LevelLoader(world, scenes);
 * loader.load(level);                 // starts loading the prefab scenes
 * ...
 * if (loader.update()) ready();       // true once, when everything is instantiated
 * loader.extractLights(packet, time); // every frame
 * }</pre>
 *
 * <p>Thread ownership: the game thread.
 */
public final class LevelLoader {
    private static final Logger LOG = Log.logger("world");

    private final World world;
    private final AssetType<SceneData> scenes;
    private LevelData level;
    private int[] sceneHandle = new int[8]; // per prefab
    private int[] rootNode = new int[8]; // per prefab, resolved once the scene is ready
    private int[] placementRoot = new int[64]; // per placement index: the root entity, or NULL
    private final IntIntMap rootToPlacement = new IntIntMap(256, -1); // root entity → placement id
    private boolean ready;
    private boolean announced;
    private final float[] trs = new float[LevelData.PLACEMENT_FLOATS];

    /** Creates a loader for {@code world} that reads prefab scenes through {@code scenes}. */
    public LevelLoader(World world, AssetType<SceneData> scenes) {
        this.world = world;
        this.scenes = scenes;
    }

    /** Returns the level being shown, or {@code null}. */
    public LevelData level() {
        return level;
    }

    /** Returns whether every placement has been instantiated. */
    public boolean isReady() {
        return ready;
    }

    /** Starts showing {@code level}: unloads the previous one and loads the prefab scenes. */
    public void load(LevelData level) {
        unload();
        this.level = level;
        int prefabs = level.prefabCount();
        if (sceneHandle.length < prefabs) {
            sceneHandle = new int[prefabs];
            rootNode = new int[prefabs];
        }
        for (int p = 0; p < prefabs; p++) {
            sceneHandle[p] = scenes.load(level.prefabScene(p));
            rootNode[p] = -2; // unresolved
        }
        loadedPrefabs = prefabs;
        ensurePlacements(level.placementCount());
        Arrays.fill(placementRoot, 0, level.placementCount(), Handles.NULL);
        ready = false;
        announced = false;
    }

    /** Destroys every instantiated placement and releases the scenes. */
    public void unload() {
        if (level == null) {
            return;
        }
        for (int i = 0; i < level.placementCount(); i++) {
            if (placementRoot[i] != Handles.NULL) {
                world.destroyGroup(placementRoot[i]);
                placementRoot[i] = Handles.NULL;
            }
        }
        for (int p = 0; p < level.prefabCount(); p++) {
            scenes.release(sceneHandle[p]);
        }
        rootToPlacement.clear();
        level = null;
        loadedPrefabs = 0;
        ready = false;
    }

    /**
     * Instantiates the placements whose scenes have arrived. Returns {@code true} on the call that
     * completes the level. Allocates only when it instantiates.
     */
    public boolean update() {
        if (level == null || ready) {
            return false;
        }
        boolean all = true;
        for (int i = 0; i < level.placementCount(); i++) {
            if (placementRoot[i] != Handles.NULL) {
                continue;
            }
            int p = level.placementPrefab(i);
            if (!scenes.isReady(sceneHandle[p])) {
                if (scenes.error(sceneHandle[p]) != null && !announced) {
                    LOG.warn("prefab scene " + level.prefabScene(p) + " failed to load");
                    announced = true;
                }
                all = false;
                continue;
            }
            instantiate(i);
        }
        if (all) {
            ready = true;
        }
        return all;
    }

    private void instantiate(int i) {
        int p = level.placementPrefab(i);
        SceneData scene = scenes.get(sceneHandle[p]);
        if (rootNode[p] == -2) {
            String node = level.prefabNode(p);
            rootNode[p] = node.isEmpty() ? -1 : World.nodeNamed(scene, node);
            if (rootNode[p] == -1 && !node.isEmpty()) {
                LOG.warn("prefab node " + node + " not in " + level.prefabScene(p));
            }
        }
        int root =
                world.instantiateNode(
                        scene,
                        rootNode[p],
                        level.placementData(),
                        i * LevelData.PLACEMENT_FLOATS,
                        renderFlags(level.placementFlags(i)),
                        null,
                        true);
        if ((level.placementFlags(i) & LevelData.PLACEMENT_HIDDEN) != 0) {
            world.setGroupHidden(root, true);
        }
        placementRoot[i] = root;
        rootToPlacement.put(root, level.placementId(i));
    }

    /**
     * Translates placement flags into {@code RenderPacket.FLAG_*} bits. A hidden placement is never
     * static: the renderer's static set is cached on the GPU and ignores hiding (D49), so hiding
     * goes through the per-frame path.
     */
    public static int renderFlags(int placementFlags) {
        int f = 0;
        if ((placementFlags & (LevelData.PLACEMENT_STATIC | LevelData.PLACEMENT_HIDDEN))
                == LevelData.PLACEMENT_STATIC) {
            f |= RenderPacket.FLAG_STATIC;
        }
        if ((placementFlags & LevelData.PLACEMENT_CASTS_SHADOW) != 0) {
            f |= RenderPacket.FLAG_CASTS_SHADOW;
        }
        if ((placementFlags & LevelData.PLACEMENT_FADE_OCCLUDER) != 0) {
            f |= RenderPacket.FLAG_FADE_OCCLUDER;
        }
        if ((placementFlags & LevelData.PLACEMENT_NO_DECALS) != 0) {
            f |= RenderPacket.FLAG_NO_DECALS;
        }
        return f;
    }

    /** Returns the root entity of the placement with {@code id}, or {@link Handles#NULL}. */
    public int rootOf(int placementId) {
        int i = level == null ? -1 : level.placementIndex(placementId);
        return i < 0 ? Handles.NULL : placementRoot[i];
    }

    /** Returns the root entity of placement index {@code i}, or {@link Handles#NULL}. */
    public int rootAt(int i) {
        return placementRoot[i];
    }

    /**
     * Returns the placement id of the group root {@code rootEntity}, or -1 (an entity that is not a
     * placement's root: use {@link World#groupOf} first for a renderable's entity).
     */
    public int placementOf(int rootEntity) {
        return rootToPlacement.get(rootEntity);
    }

    // ---- Live editing -----------------------------------------------------------------------

    /** Re-reads placement {@code i}'s transform from the level and moves its group. */
    public void syncTransform(int i) {
        int root = placementRoot[i];
        if (root != Handles.NULL) {
            world.setGroupTransform(root, level.placementData(), i * LevelData.PLACEMENT_FLOATS);
        }
    }

    /** Re-reads placement {@code i}'s flags from the level (static, shadows, hidden ...). */
    public void syncFlags(int i) {
        int root = placementRoot[i];
        if (root != Handles.NULL) {
            int flags = level.placementFlags(i);
            world.setGroupFlags(root, renderFlags(flags));
            world.setGroupHidden(root, (flags & LevelData.PLACEMENT_HIDDEN) != 0);
        }
    }

    /**
     * Instantiates placement {@code i}, inserted into the level after {@link #load}: its scene
     * loads if it is new; until it arrives the placement stays pending and {@link #update} finishes
     * it. Cold path.
     */
    public void placementInserted(int i) {
        int n = level.placementCount();
        ensurePlacements(n);
        System.arraycopy(placementRoot, i, placementRoot, i + 1, n - 1 - i);
        placementRoot[i] = Handles.NULL;
        loadNewPrefabs();
        int p = level.placementPrefab(i);
        if (scenes.isReady(sceneHandle[p])) {
            instantiate(i);
        } else {
            ready = false;
        }
    }

    /** Destroys placement {@code i}'s group before the level removes the row. Cold path. */
    public void placementRemoving(int i) {
        int root = placementRoot[i];
        if (root != Handles.NULL) {
            rootToPlacement.remove(root);
            world.destroyGroup(root);
        }
        int n = level.placementCount();
        System.arraycopy(placementRoot, i + 1, placementRoot, i, n - 1 - i);
        placementRoot[n - 1] = Handles.NULL;
    }

    /** Re-instantiates placement {@code i} (its prefab changed). Cold path. */
    public void placementReplaced(int i) {
        int root = placementRoot[i];
        if (root != Handles.NULL) {
            rootToPlacement.remove(root);
            world.destroyGroup(root);
            placementRoot[i] = Handles.NULL;
        }
        loadNewPrefabs();
        int p = level.placementPrefab(i);
        if (scenes.isReady(sceneHandle[p])) {
            instantiate(i);
        } else {
            ready = false;
        }
    }

    private int loadedPrefabs;

    /** Loads the scenes of prefabs added to the level since {@link #load}. */
    private void loadNewPrefabs() {
        int prefabs = level.prefabCount();
        if (sceneHandle.length < prefabs) {
            sceneHandle = Arrays.copyOf(sceneHandle, Math.max(prefabs, sceneHandle.length * 2));
            rootNode = Arrays.copyOf(rootNode, sceneHandle.length);
        }
        for (int p = loadedPrefabs; p < prefabs; p++) {
            sceneHandle[p] = scenes.load(level.prefabScene(p));
            rootNode[p] = -2;
        }
        loadedPrefabs = prefabs;
    }

    private void ensurePlacements(int n) {
        if (placementRoot.length < n + 1) {
            int old = placementRoot.length;
            placementRoot = Arrays.copyOf(placementRoot, Math.max(n + 1, old * 2));
            Arrays.fill(placementRoot, old, placementRoot.length, Handles.NULL);
        }
    }

    // ---- Lights -----------------------------------------------------------------------------

    /**
     * Adds the level's lights to {@code packet}: point lights (spot lights too, as spots), with
     * torches ({@link LevelData#LIGHT_FLICKER}) flickering on {@code time}. {@code gain} scales the
     * intensities to the game's exposure. Allocates nothing.
     */
    public void extractLights(RenderPacket packet, float time, float gain) {
        if (level == null) {
            return;
        }
        float[] d = level.lightData();
        for (int i = 0, n = level.lightCount(); i < n; i++) {
            int o = i * LevelData.LIGHT_FLOATS;
            float intensity = d[o + 9] * gain;
            if ((level.lightFlags(i) & LevelData.LIGHT_FLICKER) != 0) {
                float t = time * 7 + i * 1.7f;
                intensity *= 0.85f + 0.15f * (float) Math.sin(t) * (float) Math.sin(t * 2.3f + 1);
            }
            if (level.lightType(i) == LevelData.LIGHT_SPOT) {
                packet.addSpotLight(
                        d[o], d[o + 1], d[o + 2], d[o + 3], d[o + 4], d[o + 5], d[o + 10], d[o + 6],
                        d[o + 7], d[o + 8], intensity, d[o + 11], d[o + 12]);
            } else {
                packet.addPointLight(
                        d[o], d[o + 1], d[o + 2], d[o + 10], d[o + 6], d[o + 7], d[o + 8],
                        intensity);
            }
        }
    }
}
