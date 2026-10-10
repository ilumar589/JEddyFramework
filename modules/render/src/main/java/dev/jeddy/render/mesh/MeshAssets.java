package dev.jeddy.render.mesh;

import dev.jeddy.asset.AssetLoader;
import dev.jeddy.asset.AssetManager;
import dev.jeddy.asset.AssetType;
import dev.jeddy.asset.DecodeContext;
import dev.jeddy.asset.format.MeshData;
import dev.jeddy.render.material.MaterialAssets;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;

/**
 * Meshes through the {@link AssetManager} with their default materials wired (TASKS M10-02): the
 * {@code mesh} asset type ({@code .jmesh}) declares each slot's material as a dependency, uploads
 * into the {@link MeshRegistry} on the main thread, and {@link #update} points each slot at its
 * material's index once that material is uploaded. Instances drawn with {@code
 * RenderPacket.MESH_MATERIALS} then use them.
 *
 * <p>Thread ownership: registered before the game starts; {@link #update} on the GL thread after
 * the asset upload pump and {@link MaterialAssets#update}.
 */
public final class MeshAssets {
    private final MeshRegistry registry;
    private final MaterialAssets materials;
    private final AssetType<GpuMesh> meshes;
    private GpuMesh[] pending = new GpuMesh[64];
    private int pendingCount;

    /** Registers the {@code mesh} type with {@code manager}. */
    public MeshAssets(AssetManager manager, MeshRegistry registry, MaterialAssets materials) {
        this.registry = registry;
        this.materials = materials;
        this.meshes = manager.register("mesh", new Loader());
    }

    /**
     * Registers a CPU-only {@code mesh} type with {@code manager}, for runs without a GPU
     * (dedicated servers, headless perf captures): meshes decode (bounds, material slots) and
     * become ready without an upload; their registry handles stay 0. Owner thread, before loading.
     */
    public static AssetType<GpuMesh> registerHeadless(AssetManager manager) {
        return manager.register(
                "mesh",
                (id, data, context) -> {
                    GpuMesh mesh = new GpuMesh(MeshData.read(data));
                    mesh.data = null; // views the staging memory, which is reused after decode
                    return mesh;
                });
    }

    /** Returns the mesh asset type. */
    public AssetType<GpuMesh> meshes() {
        return meshes;
    }

    /** Returns the number of uploaded meshes still waiting for a material. */
    public int pendingMaterials() {
        return pendingCount;
    }

    private final class Loader implements AssetLoader<GpuMesh> {
        @Override
        public GpuMesh decode(long id, MemorySegment data, DecodeContext context) throws Exception {
            MeshData mesh = MeshData.read(data);
            for (int slot = 0; slot < mesh.materialSlotCount(); slot++) {
                context.dependsOn(materials.materials(), mesh.materialId(slot));
            }
            return new GpuMesh(mesh);
        }

        @Override
        public boolean needsUpload() {
            return true;
        }

        @Override
        public GpuMesh upload(GpuMesh decoded, MemorySegment data) {
            decoded.handle = registry.add(decoded.data);
            decoded.data = null; // the staging memory goes away after this call
            if (decoded.materialIds.length > 0) {
                if (pendingCount == pending.length) {
                    pending = Arrays.copyOf(pending, pendingCount * 2);
                }
                pending[pendingCount++] = decoded;
            }
            return decoded;
        }

        @Override
        public void unload(GpuMesh value) {
            for (int i = 0; i < pendingCount; i++) {
                if (pending[i] == value) {
                    pending[i] = pending[--pendingCount];
                    pending[pendingCount] = null;
                    break;
                }
            }
            registry.remove(value.handle);
        }
    }

    /** Wires the slots of meshes whose materials became ready. Allocates nothing. */
    public void update() {
        for (int i = pendingCount - 1; i >= 0; i--) {
            GpuMesh m = pending[i];
            boolean all = true;
            for (int s = 0; s < m.materialIds.length; s++) {
                int index = materials.indexOf(m.materialIds[s]);
                if (index < 0) {
                    all = false;
                } else {
                    registry.setSlotMaterial(m.handle, s, index);
                }
            }
            if (all) {
                m.wired = true;
                pending[i] = pending[--pendingCount];
                pending[pendingCount] = null;
            }
        }
    }
}
