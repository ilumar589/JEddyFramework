package dev.jeddy.render.material;

import dev.jeddy.asset.AssetLoader;
import dev.jeddy.asset.AssetManager;
import dev.jeddy.asset.AssetState;
import dev.jeddy.asset.AssetType;
import dev.jeddy.asset.DecodeContext;
import dev.jeddy.asset.format.MaterialData;
import dev.jeddy.core.collections.LongIntMap;
import dev.jeddy.render.gpu.GpuDevice;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;

/**
 * Textures and materials through the {@link AssetManager} (TASKS M7-01): registers the {@code
 * texture} ({@code .ktx2}) and {@code material} ({@code .jmat}) asset types and keeps materials'
 * texture slots in step with their textures. A material declares its textures as dependencies while
 * decoding, appears in the {@link MaterialSystem} as soon as it is uploaded (drawing with its
 * factors), and gains each texture once that texture is ready; reloaded textures and materials
 * update the same material index in place, so render packets never notice.
 *
 * <p>Thread ownership: the GL thread ({@link #update} once per frame after the asset manager's
 * update and upload pump).
 */
public final class MaterialAssets {
    private final MaterialSystem system;
    private final AssetType<GpuTexture> textures;
    private final AssetType<GpuMaterial> materials;
    private final LongIntMap indexById = new LongIntMap(64, -1);
    private final LongIntMap refsById = new LongIntMap(64, 0);

    // Watched texture slots: material index, slot, uv1, material asset id, texture version seen.
    private int watchCount;
    private int[] watchMaterial = new int[64];
    private int[] watchSlot = new int[64];
    private boolean[] watchUv1 = new boolean[64];
    private long[] watchOwner = new long[64];
    private int[] watchDependency = new int[64];
    private int[] watchVersion = new int[64];

    /** Registers the asset types with {@code manager}. */
    public MaterialAssets(AssetManager manager, GpuDevice device, MaterialSystem system) {
        this.system = system;
        this.textures = manager.register("texture", new TextureLoader(device, system.arrays()));
        this.materials = manager.register("material", new Loader());
    }

    /** Returns the texture asset type. */
    public AssetType<GpuTexture> textures() {
        return textures;
    }

    /** Returns the material asset type. */
    public AssetType<GpuMaterial> materials() {
        return materials;
    }

    /** Returns the material index of a loaded material asset, or -1. */
    public int indexOf(long id) {
        int i = indexById.get(id);
        return i < 0 ? -1 : i;
    }

    private final class Loader implements AssetLoader<GpuMaterial> {
        @Override
        public GpuMaterial decode(long id, MemorySegment data, DecodeContext context)
                throws Exception {
            MaterialData m = MaterialData.read(data);
            for (int i = 0; i < m.textureCount(); i++) {
                context.dependsOn(textures, m.textureId(i));
            }
            return new GpuMaterial(id, m);
        }

        @Override
        public boolean needsUpload() {
            return true;
        }

        @Override
        public GpuMaterial upload(GpuMaterial decoded, MemorySegment data) {
            int existing = indexById.get(decoded.id);
            if (existing >= 0) {
                system.update(existing, decoded.data); // a reload: same index
                decoded.index = existing;
                refsById.put(decoded.id, refsById.get(decoded.id) + 1);
                unwatch(existing);
            } else {
                decoded.index = system.add(decoded.data);
                indexById.put(decoded.id, decoded.index);
                refsById.put(decoded.id, 1);
            }
            for (int i = 0; i < decoded.data.textureCount(); i++) {
                int slot = decoded.data.textureSlot(i);
                if (slot < MaterialSystem.TEXTURE_SLOTS) {
                    watch(
                            decoded.index,
                            slot,
                            (decoded.data.textureFlags(i) & MaterialData.TEXTURE_UV1) != 0,
                            decoded.id,
                            i);
                }
            }
            return decoded;
        }

        @Override
        public void unload(GpuMaterial value) {
            int refs = refsById.get(value.id) - 1;
            if (refs > 0) {
                refsById.put(value.id, refs); // the reloaded value keeps the index
                return;
            }
            refsById.remove(value.id);
            indexById.remove(value.id);
            unwatch(value.index);
            system.remove(value.index);
        }
    }

    private void watch(int material, int slot, boolean uv1, long owner, int dependency) {
        if (watchCount == watchMaterial.length) {
            int cap = watchCount * 2;
            watchMaterial = Arrays.copyOf(watchMaterial, cap);
            watchSlot = Arrays.copyOf(watchSlot, cap);
            watchUv1 = Arrays.copyOf(watchUv1, cap);
            watchOwner = Arrays.copyOf(watchOwner, cap);
            watchDependency = Arrays.copyOf(watchDependency, cap);
            watchVersion = Arrays.copyOf(watchVersion, cap);
        }
        int w = watchCount++;
        watchMaterial[w] = material;
        watchSlot[w] = slot;
        watchUv1[w] = uv1;
        watchOwner[w] = owner;
        watchDependency[w] = dependency;
        watchVersion[w] = -1;
    }

    private void unwatch(int material) {
        for (int w = watchCount - 1; w >= 0; w--) {
            if (watchMaterial[w] == material) {
                int last = --watchCount;
                watchMaterial[w] = watchMaterial[last];
                watchSlot[w] = watchSlot[last];
                watchUv1[w] = watchUv1[last];
                watchOwner[w] = watchOwner[last];
                watchDependency[w] = watchDependency[last];
                watchVersion[w] = watchVersion[last];
            }
        }
    }

    /**
     * Gives materials the textures that became ready or were reloaded since the last call.
     * Allocates nothing.
     */
    public void update() {
        for (int w = 0; w < watchCount; w++) {
            int owner = materials.find(watchOwner[w]);
            if (owner < 0 || watchDependency[w] >= materials.dependencyCount(owner)) {
                continue;
            }
            int tex = materials.dependency(owner, watchDependency[w]);
            if (!textures.isReady(tex)) {
                continue;
            }
            int version = textures.version(tex);
            if (version == watchVersion[w]) {
                continue;
            }
            watchVersion[w] = version;
            GpuTexture t = textures.get(tex);
            int ref = t.isArrayLayer() ? t.arrayRef : TextureArrays.NONE;
            system.setTexture(watchMaterial[w], watchSlot[w], ref, watchUv1[w]);
        }
    }

    /**
     * Returns the number of material texture slots still waiting for their texture (not counting
     * textures that failed to load).
     */
    public int pendingTextures() {
        int n = 0;
        for (int w = 0; w < watchCount; w++) {
            if (watchVersion[w] >= 0) {
                continue;
            }
            int owner = materials.find(watchOwner[w]);
            boolean failed =
                    owner >= 0
                            && watchDependency[w] < materials.dependencyCount(owner)
                            && textures.state(materials.dependency(owner, watchDependency[w]))
                                    == AssetState.FAILED;
            if (!failed) {
                n++;
            }
        }
        return n;
    }
}
