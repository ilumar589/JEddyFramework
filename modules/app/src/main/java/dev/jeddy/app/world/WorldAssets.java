package dev.jeddy.app.world;

import dev.jeddy.asset.AssetLoader;
import dev.jeddy.asset.format.SceneData;
import dev.jeddy.physics.bvh.Bvh;
import dev.jeddy.physics.nav.NavMeshData;
import dev.jeddy.physics.vis.OcclusionGrid;

import java.nio.ByteOrder;

/**
 * Asset loaders for level data (TASKS M10-02): cooked scenes ({@code .jscene}), collision BVHs
 * ({@code .jcol}), navmeshes ({@code .jnav}) and occlusion grids ({@code .jocc}). Each copies what
 * it keeps, so the staging memory can be reused.
 *
 * <p>Thread ownership: decoding runs on asset workers; the values are immutable.
 */
public final class WorldAssets {
    private WorldAssets() {}

    /** Loads {@code .jscene} files. */
    public static AssetLoader<SceneData> scenes() {
        return (id, data, context) -> SceneData.read(data);
    }

    /** Loads {@code .jcol} collision BVHs. */
    public static AssetLoader<Bvh> collision() {
        return (id, data, context) -> Bvh.read(data.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN));
    }

    /** Loads {@code .jnav} navmeshes. */
    public static AssetLoader<NavMeshData> navmeshes() {
        return (id, data, context) ->
                NavMeshData.read(data.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN));
    }

    /** Loads {@code .jocc} occlusion grids. */
    public static AssetLoader<OcclusionGrid> occlusion() {
        return (id, data, context) ->
                OcclusionGrid.read(data.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN));
    }
}
