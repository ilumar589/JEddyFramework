package dev.jeddy.app.world;

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

/**
 * The game-side scene (TASKS M10-02, DESIGN §13): entities with transforms, and <b>renderables</b>
 * (a mesh asset, a material override, render flags) that {@link #extract} turns into render packet
 * instances. Renderables keep the world transform of the previous simulation tick, so the renderer
 * interpolates between ticks; static renderables are written first, in a stable order, with {@code
 * staticVersion} bumped whenever that set changes (the GPU static-scene contract, D49).
 *
 * <p>Typical tick: {@link #beginTick} (current transforms become the previous ones), gameplay and
 * physics write local transforms, {@link #updateTransforms}; per frame, {@link #extract} into the
 * packet the main thread will render.
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

    /** Creates a world for up to {@code maxEntities} entities drawing meshes of {@code meshes}. */
    public World(int maxEntities, AssetType<GpuMesh> meshes) {
        this.pool = new EntityPool(maxEntities);
        this.transforms = new TransformStore(pool, 1024);
        this.meshes = meshes;
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

    /** Destroys an entity and its renderable, releasing its mesh. */
    public void destroy(int entity) {
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
            System.arraycopy(prev, last * 12, prev, i * 12, 12);
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
     * fog of war), or a single renderable's own entity.
     */
    public void setGroupHidden(int groupEntity, boolean hidden) {
        int index = Handles.index(groupEntity);
        if (index >= hiddenGroup.length) {
            hiddenGroup = Arrays.copyOf(hiddenGroup, Math.max(index + 1, hiddenGroup.length * 2));
        }
        hiddenGroup[index] = hidden;
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
        int root = create(Handles.NULL, x, y, z, yaw);
        boolean isStatic = (renderFlags & RenderPacket.FLAG_STATIC) != 0;
        transforms.setStatic(root, isStatic);
        int n = scene.nodeCount();
        int[] entity = new int[n];
        float[] composed = flatten ? new float[n * 10] : null;
        for (int i = 0; i < n; i++) {
            int p = scene.parent(i); // always a smaller index: parents come first
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
                compose(scene, i, p, composed);
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
                transforms.add(e, p < 0 ? root : entity[p]);
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
                if (wantStatic) {
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
                AffineOps.transformPoint(
                        world, s * 12, mesh.sphere(0), mesh.sphere(1), mesh.sphere(2), point, 0);
                packet.setBounds(
                        k, point[0], point[1], point[2], mesh.sphere(3) * maxScale(world, s * 12));
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
