package dev.jeddy.app.world;

import dev.jeddy.animation.Animations;
import dev.jeddy.animation.Skeleton;
import dev.jeddy.asset.AssetType;
import dev.jeddy.asset.format.SceneData;
import dev.jeddy.core.collections.SparseSet;
import dev.jeddy.core.handle.Handles;
import dev.jeddy.core.job.JobSystem;
import dev.jeddy.math.AffineOps;
import dev.jeddy.render.RenderPacket;
import dev.jeddy.render.mesh.GpuMesh;
import dev.jeddy.scene.EntityPool;
import dev.jeddy.scene.TransformStore;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The game-side scene (TASKS M10-02, DESIGN §13): entities with transforms, and <b>renderables</b>
 * (a mesh asset, a material override, render flags) that {@link #extract} turns into render packet
 * instances. Renderables keep the world transform of the previous simulation tick, so the renderer
 * interpolates between ticks; static renderables are written first, in a stable order, with {@code
 * staticVersion} bumped whenever that set changes (the GPU static-scene contract, D49).
 *
 * <p>Typical tick: {@link #beginTick} (current transforms become the previous ones), gameplay and
 * physics write local transforms, {@link #updateTransforms}; per frame, {@link #updateAnimations}
 * and {@link #extract} into the packet the main thread will render.
 *
 * <p><b>Animated scenes</b> (TASKS M12-05, DESIGN §13.4): {@link #instantiateAnimated} gives each
 * skin of a scene an {@link Animations} instance. Skinned meshes become renderables on children of
 * the root with identity transforms (skinning is relative to the scene root), meshes under joints
 * become attachments that follow their joint, and {@link #extract} adds each visible instance's
 * palette to the packet once per frame and bounds its skinned renderables by its joints.
 *
 * <p>Thread ownership: the game thread. Allocates nothing per tick or frame once entities exist.
 */
public final class World {
    private final EntityPool pool;
    private final TransformStore transforms;
    private final AssetType<GpuMesh> meshes;
    private final SparseSet renderables = new SparseSet(1024, 256); // keyed by entity index
    private int[] entityOf = new int[256];
    private int[] meshAsset = new int[256];
    private int[] material = new int[256];
    private int[] flags = new int[256];
    private int[] style = new int[256];
    private int[] group = new int[256];
    private boolean[] hiddenGroup = new boolean[1024];
    private float[] prev = new float[256 * 12];
    private boolean[] hasPrev = new boolean[256];
    private int staticStructure;
    private int lastStaticReady = -1;
    private int staticVersion;
    private final float[] tmp = new float[12];
    private final float[] point = new float[3];

    // Animation.
    private final Animations animations; // null: no animated scenes
    private int[] animInstance = new int[256]; // per renderable: the instance it follows, or -1
    private int[] attachJoint = new int[256]; // per renderable: the joint it hangs off; -1 skins
    private float[] attachOffset = new float[256 * 12]; // relative to that joint
    private final SparseSet animated = new SparseSet(1024, 64); // animated roots by entity index
    private int[] animatedRoot = new int[64];
    private int[][] animatedInstances = new int[64][];
    private final Map<SceneData, Skeleton[]> skeletons = new IdentityHashMap<>();
    private final int[] paletteStamp;
    private final int[] paletteBase;
    private int extractStamp;
    private float skinMargin = 0.5f;
    private final float[] attachLocal = new float[12];
    private final float[] attachPrev = new float[12];
    private final float[] attachCurr = new float[12];

    /** Creates a world for up to {@code maxEntities} entities drawing meshes of {@code meshes}. */
    public World(int maxEntities, AssetType<GpuMesh> meshes) {
        this(maxEntities, meshes, null);
    }

    /**
     * Creates a world whose animated scenes ({@link #instantiateAnimated}) play in {@code
     * animations} ({@code null} for none).
     */
    public World(int maxEntities, AssetType<GpuMesh> meshes, Animations animations) {
        this.pool = new EntityPool(maxEntities);
        this.transforms = new TransformStore(pool, 1024);
        this.meshes = meshes;
        this.animations = animations;
        int slots = animations == null ? 0 : animations.capacity();
        this.paletteStamp = new int[slots];
        this.paletteBase = new int[slots];
    }

    /** Returns the animated instances, or {@code null} if this world has none. */
    public Animations animations() {
        return animations;
    }

    /** Returns the entity pool. */
    public EntityPool entities() {
        return pool;
    }

    /** Returns the transforms. */
    public TransformStore transforms() {
        return transforms;
    }

    /** Returns the mesh asset type. */
    public AssetType<GpuMesh> meshes() {
        return meshes;
    }

    /**
     * Creates an entity with a transform at {@code (x, y, z)} turned by {@code yaw} about +Y, under
     * {@code parent} ({@link Handles#NULL} for a root).
     */
    public int create(int parent, float x, float y, float z, float yaw) {
        int e = pool.create();
        transforms.add(e, parent);
        transforms.setPosition(e, x, y, z);
        setYaw(e, yaw);
        return e;
    }

    /** Sets an entity's rotation to {@code yaw} radians about +Y. */
    public void setYaw(int entity, float yaw) {
        float h = yaw * 0.5f;
        transforms.setRotation(entity, 0, (float) Math.sin(h), 0, (float) Math.cos(h));
    }

    /**
     * Destroys an entity and its renderable, releasing its mesh. Destroying an animated root
     * ({@link #instantiateAnimated}) also destroys its renderables' entities and removes its
     * animation instances.
     */
    public void destroy(int entity) {
        int a = animated.indexOf(Handles.index(entity));
        if (a >= 0 && animatedRoot[a] == entity) {
            for (int i = renderables.size() - 1; i >= 0; i--) {
                if (group[i] == entity && entityOf[i] != entity) {
                    int e = entityOf[i];
                    removeRenderable(e);
                    pool.destroy(e);
                }
            }
            for (int instance : animatedInstances[a]) {
                animations.remove(instance);
            }
            int last = animated.size() - 1;
            animated.remove(Handles.index(entity));
            animatedRoot[a] = animatedRoot[last];
            animatedInstances[a] = animatedInstances[last];
            animatedInstances[last] = null;
        }
        removeRenderable(entity);
        pool.destroy(entity);
    }

    // Renderables --------------------------------------------------------------------------------

    /**
     * Makes {@code entity} draw mesh asset {@code meshId} ({@code materialIndex} or {@code
     * RenderPacket.MESH_MATERIALS}) with {@code RenderPacket.FLAG_*} flags; the mesh loads in the
     * background and the entity appears once it is ready.
     */
    public void addRenderable(int entity, long meshId, int materialIndex, int renderFlags) {
        int i = renderables.indexOf(Handles.index(entity));
        if (i < 0) {
            i = renderables.add(Handles.index(entity));
            grow(i + 1);
            entityOf[i] = entity;
        } else {
            meshes.release(meshAsset[i]);
        }
        meshAsset[i] = meshes.load(meshId);
        material[i] = materialIndex;
        flags[i] = renderFlags;
        style[i] = 0;
        group[i] = entity;
        hasPrev[i] = false;
        animInstance[i] = -1;
        attachJoint[i] = -1;
        if ((renderFlags & RenderPacket.FLAG_STATIC) != 0) {
            staticStructure++;
        }
    }

    private void grow(int n) {
        if (n <= meshAsset.length) {
            return;
        }
        int cap = Math.max(n, meshAsset.length * 2);
        entityOf = Arrays.copyOf(entityOf, cap);
        meshAsset = Arrays.copyOf(meshAsset, cap);
        material = Arrays.copyOf(material, cap);
        flags = Arrays.copyOf(flags, cap);
        style = Arrays.copyOf(style, cap);
        group = Arrays.copyOf(group, cap);
        prev = Arrays.copyOf(prev, cap * 12);
        hasPrev = Arrays.copyOf(hasPrev, cap);
        animInstance = Arrays.copyOf(animInstance, cap);
        attachJoint = Arrays.copyOf(attachJoint, cap);
        attachOffset = Arrays.copyOf(attachOffset, cap * 12);
    }

    /** Removes {@code entity}'s renderable (if any). */
    public void removeRenderable(int entity) {
        int i = renderables.indexOf(Handles.index(entity));
        if (i < 0 || entityOf[i] != entity) {
            return;
        }
        meshes.release(meshAsset[i]);
        if ((flags[i] & RenderPacket.FLAG_STATIC) != 0) {
            staticStructure++;
        }
        int last = renderables.size() - 1;
        renderables.remove(Handles.index(entity));
        if (i != last) {
            entityOf[i] = entityOf[last];
            meshAsset[i] = meshAsset[last];
            material[i] = material[last];
            flags[i] = flags[last];
            style[i] = style[last];
            group[i] = group[last];
            hasPrev[i] = hasPrev[last];
            animInstance[i] = animInstance[last];
            attachJoint[i] = attachJoint[last];
            System.arraycopy(prev, last * 12, prev, i * 12, 12);
            System.arraycopy(attachOffset, last * 12, attachOffset, i * 12, 12);
            if ((flags[i] & RenderPacket.FLAG_STATIC) != 0) {
                staticStructure++; // a static instance moved in the order
            }
        }
    }

    /** Returns the number of renderables. */
    public int renderableCount() {
        return renderables.size();
    }

    /** Sets the outline style of {@code entity} ({@code RenderPacket.HIGHLIGHT_*}, 0 for none). */
    public void setHighlight(int entity, int highlightStyle) {
        int i = renderables.indexOf(Handles.index(entity));
        if (i >= 0 && entityOf[i] == entity) {
            style[i] = highlightStyle;
        }
    }

    /**
     * Hides or shows every renderable of a group: an instantiated scene's root (characters in the
     * fog of war), or a single renderable's own entity. Hidden animated scenes stop animating (they
     * keep their pose and cost nothing).
     */
    public void setGroupHidden(int groupEntity, boolean hidden) {
        int index = Handles.index(groupEntity);
        if (index >= hiddenGroup.length) {
            hiddenGroup = Arrays.copyOf(hiddenGroup, Math.max(index + 1, hiddenGroup.length * 2));
        }
        hiddenGroup[index] = hidden;
        int a = animated.indexOf(index);
        if (a >= 0 && animatedRoot[a] == groupEntity) {
            for (int instance : animatedInstances[a]) {
                animations.setActive(instance, !hidden);
            }
        }
    }

    /** Sets the outline style of every renderable of a group (see {@link #setGroupHidden}). */
    public void setGroupHighlight(int groupEntity, int highlightStyle) {
        for (int i = 0, n = renderables.size(); i < n; i++) {
            if (group[i] == groupEntity) {
                style[i] = highlightStyle;
            }
        }
    }

    /** Returns the number of renderables whose mesh is still loading. */
    public int loadingRenderables() {
        int n = 0;
        for (int i = 0, c = renderables.size(); i < c; i++) {
            if (!meshes.isReady(meshAsset[i])) {
                n++;
            }
        }
        return n;
    }

    // Scenes -------------------------------------------------------------------------------------

    /**
     * Instantiates a cooked scene under a new root entity at {@code (x, y, z)} turned by {@code
     * yaw}: one entity per node (parents kept), a renderable for every mesh node with {@code
     * renderFlags}. Returns the root.
     */
    public int instantiate(SceneData scene, float x, float y, float z, float yaw, int renderFlags) {
        return instantiate(scene, x, y, z, yaw, renderFlags, null, false);
    }

    /**
     * Like {@link #instantiate(SceneData, float, float, float, float, int)}, with flags chosen per
     * node by {@code nodeFlags} (by node name; {@code null} for {@code renderFlags} everywhere),
     * for example fading walls or hidden alternative equipment.
     *
     * <p>With {@code flatten}, only the mesh nodes become entities, as direct children of the root
     * with their transforms composed down the node hierarchy: for scenes that never animate their
     * nodes (levels, props, characters without an animation system) this keeps a few transforms per
     * instance instead of a whole bone hierarchy. Composition assumes no shear (uniform scale on
     * nodes with rotated children), which holds for typical exports.
     */
    public int instantiate(
            SceneData scene,
            float x,
            float y,
            float z,
            float yaw,
            int renderFlags,
            NodeFlags nodeFlags,
            boolean flatten) {
        float h = yaw * 0.5f;
        float[] trs = rootTrs;
        trs[0] = x;
        trs[1] = y;
        trs[2] = z;
        trs[3] = 0;
        trs[4] = (float) Math.sin(h);
        trs[5] = 0;
        trs[6] = (float) Math.cos(h);
        trs[7] = 1;
        trs[8] = 1;
        trs[9] = 1;
        return instantiateNode(scene, -1, trs, 0, renderFlags, nodeFlags, flatten);
    }

    private final float[] rootTrs = new float[10];
    private boolean[] subtree = new boolean[64];

    /**
     * Instantiates the subtree of scene node {@code rootNode} (or the whole scene with -1) under a
     * new root entity whose transform is {@code trs[offset .. +10)} (position, rotation quaternion,
     * scale), as {@link #instantiate(SceneData, float, float, float, float, int, NodeFlags,
     * boolean)} does. This is how a level places a <em>prefab</em> (DESIGN §19, D87): a named root
     * of a library scene, with the placement's full transform. Returns the root entity; the subtree
     * root keeps its own local transform under it. Cold path.
     *
     * @throws IllegalArgumentException if {@code rootNode} is not a node of the scene
     */
    public int instantiateNode(
            SceneData scene,
            int rootNode,
            float[] trs,
            int offset,
            int renderFlags,
            NodeFlags nodeFlags,
            boolean flatten) {
        int n = scene.nodeCount();
        if (rootNode >= n) {
            throw new IllegalArgumentException("node " + rootNode + " of " + n);
        }
        int root = pool.create();
        transforms.add(root, Handles.NULL);
        transforms.setPosition(root, trs[offset], trs[offset + 1], trs[offset + 2]);
        transforms.setRotation(
                root, trs[offset + 3], trs[offset + 4], trs[offset + 5], trs[offset + 6]);
        transforms.setScale(root, trs[offset + 7], trs[offset + 8], trs[offset + 9]);
        boolean isStatic = (renderFlags & RenderPacket.FLAG_STATIC) != 0;
        transforms.setStatic(root, isStatic);
        if (subtree.length < n) {
            subtree = new boolean[Math.max(n, subtree.length * 2)];
        }
        boolean[] in = subtree;
        int[] entity = new int[n];
        float[] composed = flatten ? new float[n * 10] : null;
        for (int i = 0; i < n; i++) {
            int p = scene.parent(i); // always a smaller index: parents come first
            in[i] = rootNode < 0 || i == rootNode || (p >= 0 && in[p]);
            if (!in[i]) {
                continue;
            }
            boolean top = rootNode < 0 ? p < 0 : i == rootNode; // a child of the new root
            int m = scene.mesh(i);
            int f = -1;
            if (m >= 0) {
                f =
                        nodeFlags == null
                                ? renderFlags
                                : nodeFlags.flags(scene.nodeName(i), renderFlags);
            }
            int e;
            if (flatten) {
                compose(scene, i, top ? -1 : p, composed);
                if (f < 0) {
                    continue;
                }
                e = pool.create();
                transforms.add(e, root);
                int o = i * 10;
                transforms.setPosition(e, composed[o], composed[o + 1], composed[o + 2]);
                transforms.setRotation(
                        e, composed[o + 3], composed[o + 4], composed[o + 5], composed[o + 6]);
                transforms.setScale(e, composed[o + 7], composed[o + 8], composed[o + 9]);
            } else {
                e = pool.create();
                transforms.add(e, top ? root : entity[p]);
                transforms.setPosition(e, scene.trs(i, 0), scene.trs(i, 1), scene.trs(i, 2));
                transforms.setRotation(
                        e, scene.trs(i, 3), scene.trs(i, 4), scene.trs(i, 5), scene.trs(i, 6));
                transforms.setScale(e, scene.trs(i, 7), scene.trs(i, 8), scene.trs(i, 9));
            }
            transforms.setStatic(e, isStatic);
            entity[i] = e;
            if (f >= 0) {
                addRenderable(e, scene.meshId(m), RenderPacket.MESH_MATERIALS, f);
                group[renderables.indexOf(Handles.index(e))] = root;
            }
        }
        return root;
    }

    /** Returns the index of the node named {@code name} in {@code scene}, or -1. */
    public static int nodeNamed(SceneData scene, String name) {
        if (name == null || name.isEmpty()) {
            return -1;
        }
        for (int i = 0, n = scene.nodeCount(); i < n; i++) {
            if (name.equals(scene.nodeName(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Destroys an instantiated scene: the root, every entity under it and their renderables. Cold
     * path (a pass over the transform store).
     */
    public void destroyGroup(int root) {
        // Renderables first (they may belong to entities below the root); destroying an entity
        // removes its transform (the pool tells its stores).
        for (int i = renderables.size() - 1; i >= 0; i--) {
            if (group[i] == root && entityOf[i] != root) {
                int e = entityOf[i];
                removeRenderable(e);
                pool.destroy(e);
            }
        }
        // Then any remaining descendants (non-flattened intermediate nodes).
        boolean again = true;
        while (again) {
            again = false;
            for (int s = transforms.size() - 1; s >= 0; s--) {
                int e = transforms.entityAt(s);
                if (e != root && isUnder(e, root)) {
                    pool.destroy(e);
                    again = true;
                    break; // the store compacts; restart the scan
                }
            }
        }
        destroy(root);
    }

    private boolean isUnder(int entity, int root) {
        int p = transforms.parent(entity);
        while (p != Handles.NULL) {
            if (p == root) {
                return true;
            }
            p = transforms.parent(p);
        }
        return false;
    }

    /**
     * Moves an instantiated scene's root to {@code trs[offset .. +10)}; its entities follow through
     * the hierarchy. Static groups invalidate the renderer's static set (D49), so this is for
     * editors and one-off placement, not per-frame motion.
     */
    public void setGroupTransform(int root, float[] trs, int offset) {
        transforms.setPosition(root, trs[offset], trs[offset + 1], trs[offset + 2]);
        transforms.setRotation(
                root, trs[offset + 3], trs[offset + 4], trs[offset + 5], trs[offset + 6]);
        transforms.setScale(root, trs[offset + 7], trs[offset + 8], trs[offset + 9]);
        if (transforms.isStatic(root)) {
            staticStructure++;
        }
    }

    /** Returns the group root of {@code entity}'s renderable, or {@code entity} itself. */
    public int groupOf(int entity) {
        int i = renderables.indexOf(Handles.index(entity));
        return i >= 0 && entityOf[i] == entity ? group[i] : entity;
    }

    /** Sets the render flags of every renderable of a group (static-ness included). */
    public void setGroupFlags(int groupEntity, int renderFlags) {
        for (int i = 0, n = renderables.size(); i < n; i++) {
            if (group[i] == groupEntity) {
                if (flags[i] != renderFlags) {
                    flags[i] = renderFlags;
                    transforms.setStatic(
                            entityOf[i], (renderFlags & RenderPacket.FLAG_STATIC) != 0);
                    staticStructure++;
                }
            }
        }
        transforms.setStatic(groupEntity, (renderFlags & RenderPacket.FLAG_STATIC) != 0);
    }

    /**
     * Writes node {@code i}'s TRS composed with its (already composed) parent's into {@code out}.
     */
    private static void compose(SceneData scene, int i, int p, float[] out) {
        int o = i * 10;
        float tx = scene.trs(i, 0), ty = scene.trs(i, 1), tz = scene.trs(i, 2);
        float qx = scene.trs(i, 3),
                qy = scene.trs(i, 4),
                qz = scene.trs(i, 5),
                qw = scene.trs(i, 6);
        float sx = scene.trs(i, 7), sy = scene.trs(i, 8), sz = scene.trs(i, 9);
        if (p < 0) {
            out[o] = tx;
            out[o + 1] = ty;
            out[o + 2] = tz;
            out[o + 3] = qx;
            out[o + 4] = qy;
            out[o + 5] = qz;
            out[o + 6] = qw;
            out[o + 7] = sx;
            out[o + 8] = sy;
            out[o + 9] = sz;
            return;
        }
        int q = p * 10;
        float px = out[q + 3], py = out[q + 4], pz = out[q + 5], pw = out[q + 6];
        // Parent scale, then parent rotation, then parent translation applied to the child's.
        float vx = tx * out[q + 7];
        float vy = ty * out[q + 8];
        float vz = tz * out[q + 9];
        float cx = 2 * (py * vz - pz * vy);
        float cy = 2 * (pz * vx - px * vz);
        float cz = 2 * (px * vy - py * vx);
        out[o] = out[q] + vx + pw * cx + (py * cz - pz * cy);
        out[o + 1] = out[q + 1] + vy + pw * cy + (pz * cx - px * cz);
        out[o + 2] = out[q + 2] + vz + pw * cz + (px * cy - py * cx);
        float rx = pw * qx + px * qw + py * qz - pz * qy;
        float ry = pw * qy - px * qz + py * qw + pz * qx;
        float rz = pw * qz + px * qy - py * qx + pz * qw;
        float rw = pw * qw - px * qx - py * qy - pz * qz;
        float len = (float) Math.sqrt(rx * rx + ry * ry + rz * rz + rw * rw);
        out[o + 3] = rx / len;
        out[o + 4] = ry / len;
        out[o + 5] = rz / len;
        out[o + 6] = rw / len;
        out[o + 7] = out[q + 7] * sx;
        out[o + 8] = out[q + 8] * sy;
        out[o + 9] = out[q + 9] * sz;
    }

    /** Chooses render flags per scene node. */
    @FunctionalInterface
    public interface NodeFlags {
        /**
         * Returns the flags for node {@code name} (the default is {@code flags}), or -1 to leave
         * the node without a renderable (alternative equipment, helpers).
         */
        int flags(String name, int flags);
    }

    // Animated scenes ----------------------------------------------------------------------------

    /**
     * Instantiates a skinned scene under a new root entity at {@code (x, y, z)} turned by {@code
     * yaw}, with an {@link Animations} instance per skin in its bind pose (play clips on {@link
     * #animation}). Only mesh nodes become entities, all direct children of the root:
     *
     * <ul>
     *   <li>skinned meshes, with identity transforms: their palette places their vertices;
     *   <li>meshes under a joint (weapons, shields, helmets): <em>attachments</em> that follow
     *       their joint at a fixed offset;
     *   <li>other meshes: their transform composed down the node hierarchy, as with {@code
     *       flatten}.
     * </ul>
     *
     * {@code nodeFlags} chooses flags per mesh node ({@code null}: {@code renderFlags} for all);
     * animated renderables are never static. Skeletons are built once per scene object and shared.
     * Cold path.
     *
     * @return the root entity
     * @throws IllegalStateException if this world has no {@link Animations} or they are full
     */
    public int instantiateAnimated(
            SceneData scene,
            float x,
            float y,
            float z,
            float yaw,
            int renderFlags,
            NodeFlags nodeFlags) {
        if (animations == null) {
            throw new IllegalStateException("this world was created without Animations");
        }
        Skeleton[] rigs = skeletons(scene);
        int flags = renderFlags & ~RenderPacket.FLAG_STATIC;
        int[] instances = new int[rigs.length];
        int added = 0;
        try {
            for (; added < rigs.length; added++) {
                instances[added] = animations.add(rigs[added]);
            }
        } catch (RuntimeException e) {
            for (int k = 0; k < added; k++) {
                animations.remove(instances[k]);
            }
            throw e;
        }
        int root = create(Handles.NULL, x, y, z, yaw);
        int n = scene.nodeCount();
        float[] composed = new float[n * 10];
        for (int i = 0; i < n; i++) {
            compose(scene, i, scene.parent(i), composed);
            int m = scene.mesh(i);
            if (m < 0) {
                continue;
            }
            int f = nodeFlags == null ? flags : nodeFlags.flags(scene.nodeName(i), flags);
            if (f < 0) {
                continue;
            }
            f &= ~RenderPacket.FLAG_STATIC;
            int skin = scene.skin(i);
            int instance = -1;
            int joint = -1;
            if (skin >= 0) {
                instance = instances[skin];
            } else {
                for (int k = 0; k < rigs.length && instance < 0; k++) {
                    joint = rigs[k].attachment(i, attachLocal, 0);
                    if (joint >= 0) {
                        instance = instances[k];
                    }
                }
            }
            int e = pool.create();
            transforms.add(e, root);
            if (instance < 0) {
                int o = i * 10;
                transforms.setPosition(e, composed[o], composed[o + 1], composed[o + 2]);
                transforms.setRotation(
                        e, composed[o + 3], composed[o + 4], composed[o + 5], composed[o + 6]);
                transforms.setScale(e, composed[o + 7], composed[o + 8], composed[o + 9]);
            }
            addRenderable(e, scene.meshId(m), RenderPacket.MESH_MATERIALS, f);
            int r = renderables.indexOf(Handles.index(e));
            group[r] = root;
            animInstance[r] = instance;
            attachJoint[r] = joint;
            if (joint >= 0) {
                System.arraycopy(attachLocal, 0, attachOffset, r * 12, 12);
            }
        }
        int a = animated.add(Handles.index(root));
        if (a >= animatedRoot.length) {
            animatedRoot = Arrays.copyOf(animatedRoot, a * 2);
            animatedInstances = Arrays.copyOf(animatedInstances, a * 2);
        }
        animatedRoot[a] = root;
        animatedInstances[a] = instances;
        return root;
    }

    private Skeleton[] skeletons(SceneData scene) {
        Skeleton[] rigs = skeletons.get(scene);
        if (rigs == null) {
            rigs = new Skeleton[scene.skins().size()];
            for (int s = 0; s < rigs.length; s++) {
                rigs[s] = Skeleton.of(scene, s);
            }
            skeletons.put(scene, rigs);
        }
        return rigs;
    }

    /**
     * Returns the {@link Animations} instance of the first skin of an animated root, or -1 if
     * {@code root} is not one.
     */
    public int animation(int root) {
        return animation(root, 0);
    }

    /** Returns the {@link Animations} instance of skin {@code skin} of an animated root, or -1. */
    public int animation(int root, int skin) {
        int a = animated.indexOf(Handles.index(root));
        if (a < 0 || animatedRoot[a] != root || skin >= animatedInstances[a].length) {
            return -1;
        }
        return animatedInstances[a][skin];
    }

    /**
     * Sets how far skin may reach beyond its joints (model-space meters, default 0.5): the margin
     * added to the joints' bounding sphere when culling skinned renderables.
     */
    public void setSkinMargin(float meters) {
        skinMargin = meters;
    }

    /**
     * Advances every animated instance by {@code dt} seconds (a frame, not a tick: poses need no
     * interpolation) on {@code jobs}. Call before {@link #extract}. Allocates nothing.
     */
    public void updateAnimations(float dt, JobSystem jobs) {
        if (animations != null) {
            animations.update(dt, jobs);
        }
    }

    /** Adds instance {@code a}'s palette to the packet once per extract; returns its base. */
    private int palette(RenderPacket packet, int a) {
        if (paletteStamp[a] != extractStamp) {
            paletteStamp[a] = extractStamp;
            paletteBase[a] =
                    packet.addPalette(
                            animations.palettes(),
                            animations.jointBase(a) * AffineOps.STRIDE,
                            animations.jointCount(a));
        }
        return paletteBase[a];
    }

    // Ticks and extraction -----------------------------------------------------------------------

    /**
     * Remembers every moving renderable's world transform as the previous tick's (call before
     * moving things). Static renderables never move, so they are skipped.
     */
    public void beginTick() {
        float[] world = transforms.world();
        for (int i = 0, n = renderables.size(); i < n; i++) {
            if ((flags[i] & RenderPacket.FLAG_STATIC) != 0) {
                continue;
            }
            int s = transforms.indexOf(entityOf[i]);
            System.arraycopy(world, s * 12, prev, i * 12, 12);
            hasPrev[i] = true;
        }
    }

    /** Recomputes changed world transforms. */
    public void updateTransforms(JobSystem jobs) {
        transforms.update(jobs);
    }

    /**
     * Writes every renderable whose mesh is ready into {@code packet} (after {@code packet.reset()}
     * and the camera): static ones first in a stable order, then the rest. Returns the number of
     * instances.
     */
    public int extract(RenderPacket packet) {
        float[] world = transforms.world();
        int n = renderables.size();
        int staticReady = 0;
        if (++extractStamp == 0) {
            Arrays.fill(paletteStamp, 0); // after 2^32 extracts
            extractStamp = 1;
        }
        for (int pass = 0; pass < 2; pass++) {
            boolean wantStatic = pass == 0;
            for (int i = 0; i < n; i++) {
                if (((flags[i] & RenderPacket.FLAG_STATIC) != 0) != wantStatic) {
                    continue;
                }
                int h = meshAsset[i];
                if (!meshes.isReady(h)) {
                    continue;
                }
                int g = Handles.index(group[i]);
                if (g < hiddenGroup.length && hiddenGroup[g] && !wantStatic) {
                    continue;
                }
                GpuMesh mesh = meshes.get(h);
                int s = transforms.indexOf(entityOf[i]);
                int k;
                int a = animInstance[i];
                if (a >= 0) {
                    k = extractAnimated(packet, i, s, a, mesh, world);
                } else if (wantStatic) {
                    k =
                            packet.add(
                                    world,
                                    s * 12,
                                    world,
                                    s * 12,
                                    mesh.handle(),
                                    material[i],
                                    flags[i],
                                    entityOf[i]);
                } else {
                    if (!hasPrev[i]) {
                        System.arraycopy(world, s * 12, prev, i * 12, 12);
                        hasPrev[i] = true;
                    }
                    k =
                            packet.add(
                                    prev,
                                    i * 12,
                                    world,
                                    s * 12,
                                    mesh.handle(),
                                    material[i],
                                    flags[i],
                                    entityOf[i]);
                }
                if (a < 0) {
                    AffineOps.transformPoint(
                            world,
                            s * 12,
                            mesh.sphere(0),
                            mesh.sphere(1),
                            mesh.sphere(2),
                            point,
                            0);
                    packet.setBounds(
                            k,
                            point[0],
                            point[1],
                            point[2],
                            mesh.sphere(3) * maxScale(world, s * 12));
                }
                if (style[i] != 0) {
                    packet.setHighlight(k, style[i]);
                }
                if (wantStatic) {
                    staticReady++;
                }
            }
        }
        if (staticReady != lastStaticReady || staticStructure != 0) {
            staticVersion++;
            lastStaticReady = staticReady;
            staticStructure = 0;
        }
        packet.staticVersion = staticVersion;
        return packet.count();
    }

    /**
     * Writes animated renderable {@code i} (entity transform slot {@code s}, following animation
     * instance {@code a}): an attachment at root × joint × offset for the previous and current
     * tick, or a skinned mesh with the instance's palette, bounded by its joints.
     */
    private int extractAnimated(
            RenderPacket packet, int i, int s, int a, GpuMesh mesh, float[] world) {
        if (!hasPrev[i]) {
            System.arraycopy(world, s * 12, prev, i * 12, 12);
            hasPrev[i] = true;
        }
        int joint = attachJoint[i];
        if (joint >= 0) {
            AffineOps.mul(
                    animations.models(),
                    (animations.jointBase(a) + joint) * AffineOps.STRIDE,
                    attachOffset,
                    i * 12,
                    attachLocal,
                    0);
            AffineOps.mul(prev, i * 12, attachLocal, 0, attachPrev, 0);
            AffineOps.mul(world, s * 12, attachLocal, 0, attachCurr, 0);
            int k =
                    packet.add(
                            attachPrev,
                            0,
                            attachCurr,
                            0,
                            mesh.handle(),
                            material[i],
                            flags[i],
                            entityOf[i]);
            AffineOps.transformPoint(
                    attachCurr, 0, mesh.sphere(0), mesh.sphere(1), mesh.sphere(2), point, 0);
            packet.setBounds(
                    k, point[0], point[1], point[2], mesh.sphere(3) * maxScale(attachCurr, 0));
            return k;
        }
        int k =
                packet.add(
                        prev,
                        i * 12,
                        world,
                        s * 12,
                        mesh.handle(),
                        material[i],
                        flags[i],
                        entityOf[i]);
        packet.setPalette(k, palette(packet, a));
        float[] b = animations.bounds();
        AffineOps.transformPoint(world, s * 12, b[a * 4], b[a * 4 + 1], b[a * 4 + 2], point, 0);
        packet.setBounds(
                k,
                point[0],
                point[1],
                point[2],
                (b[a * 4 + 3] + skinMargin) * maxScale(world, s * 12));
        return k;
    }

    private static float maxScale(float[] m, int o) {
        float sx = m[o] * m[o] + m[o + 4] * m[o + 4] + m[o + 8] * m[o + 8];
        float sy = m[o + 1] * m[o + 1] + m[o + 5] * m[o + 5] + m[o + 9] * m[o + 9];
        float sz = m[o + 2] * m[o + 2] + m[o + 6] * m[o + 6] + m[o + 10] * m[o + 10];
        return (float) Math.sqrt(Math.max(sx, Math.max(sy, sz)));
    }

    /** Returns the world position of {@code entity} into {@code out} (after the last update). */
    public void position(int entity, float[] out) {
        int s = transforms.indexOf(entity);
        float[] w = transforms.world();
        out[0] = w[s * 12 + 3];
        out[1] = w[s * 12 + 7];
        out[2] = w[s * 12 + 11];
    }
}
