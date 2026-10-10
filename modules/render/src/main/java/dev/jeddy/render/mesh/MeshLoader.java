package dev.jeddy.render.mesh;

import dev.jeddy.asset.AssetLoader;
import dev.jeddy.asset.AssetType;
import dev.jeddy.asset.DecodeContext;
import dev.jeddy.asset.format.MeshData;

import java.lang.foreign.MemorySegment;

/**
 * Loads {@code .jmesh} assets into the {@link MeshRegistry} (TASKS M5-07): validation on a worker,
 * the upload into the mega buffers on the main thread within the asset upload budget. Default
 * materials are declared as dependencies when a material type is given.
 *
 * <p>Thread ownership: see {@link AssetLoader}.
 */
public final class MeshLoader implements AssetLoader<GpuMesh> {
    private final MeshRegistry registry;
    private final AssetType<?> materials;

    /**
     * Creates the loader.
     *
     * @param materials the material asset type for default materials, or {@code null}
     */
    public MeshLoader(MeshRegistry registry, AssetType<?> materials) {
        this.registry = registry;
        this.materials = materials;
    }

    @Override
    public GpuMesh decode(long id, MemorySegment data, DecodeContext context) throws Exception {
        MeshData mesh = MeshData.read(data);
        if (materials != null) {
            for (int slot = 0; slot < mesh.materialSlotCount(); slot++) {
                context.dependsOn(materials, mesh.materialId(slot));
            }
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
        return decoded;
    }

    @Override
    public void unload(GpuMesh value) {
        registry.remove(value.handle);
    }
}
