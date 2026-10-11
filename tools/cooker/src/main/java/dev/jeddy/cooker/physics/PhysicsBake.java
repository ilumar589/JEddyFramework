package dev.jeddy.cooker.physics;

import dev.jeddy.cooker.CookContext;
import dev.jeddy.cooker.gltf.GltfImporter;
import dev.jeddy.physics.bvh.Bvh;
import dev.jeddy.physics.bvh.BvhBuilder;
import dev.jeddy.physics.bvh.TriFlags;
import dev.jeddy.physics.bvh.TriangleMesh;
import dev.jeddy.physics.nav.NavMeshData;
import dev.jeddy.physics.nav.NavTile;
import dev.jeddy.physics.nav.bake.NavBakeConfig;
import dev.jeddy.physics.nav.bake.NavMeshBaker;
import dev.jeddy.physics.vis.OcclusionGrid;

import java.util.List;
import java.util.Locale;

/**
 * The physics bake shared by {@link PhysicsImporter} (a glTF level with a {@code physics.json}) and
 * {@link dev.jeddy.cooker.level.LevelImporter} (a {@code .jlevel} of placements), D90: from a
 * triangle soup with per-triangle flags it writes the collision BVH ({@code .jcol}), one navmesh
 * per agent size class ({@code .<size>.jnav}) and the fog-of-war occlusion grid ({@code .jocc}).
 * The two importers only differ in how they gather the triangles.
 *
 * <p>Thread ownership: cooker workers; the methods are stateless.
 */
public final class PhysicsBake {
    /** An agent size class: one navmesh is baked per class. */
    public record SizeClass(String name, float radius, float height, float climb) {}

    private PhysicsBake() {}

    /** Builds the BVH, writes {@code <base>.jcol} and returns it for the occlusion bake. */
    public static Bvh bakeCollision(CookContext ctx, TriangleMesh mesh, String base) {
        Bvh bvh = BvhBuilder.build(mesh, ctx.jobs());
        ctx.output(base + ".jcol", bvh.toBytes());
        ctx.stat("triangles", mesh.triangleCount());
        ctx.stat("bvhNodes", bvh.nodeCount());
        return bvh;
    }

    /** Bakes one navmesh per size class into {@code <base>.<name>.jnav}. */
    public static void bakeNavmeshes(
            CookContext ctx,
            NavBakeConfig cfg,
            List<NavMeshBaker.AreaVolume> areas,
            List<NavMeshBaker.OffMeshLink> links,
            List<SizeClass> sizes,
            TriangleMesh mesh,
            String base) {
        for (SizeClass size : sizes) {
            NavBakeConfig agent = cfg.forAgent(size.radius(), size.height(), size.climb());
            NavMeshData data = NavMeshBaker.bake(mesh, agent, areas, links, ctx.jobs());
            String name = CookContext.sanitize(size.name().toLowerCase(Locale.ROOT));
            ctx.output(base + "." + name + ".jnav", data.toBytes());
            int polys = 0;
            for (NavTile t : data.tiles()) {
                polys += t.polyCount;
            }
            ctx.stat("navmesh." + name + ".tiles", data.tiles().size());
            ctx.stat("navmesh." + name + ".polygons", polys);
            if (polys == 0) {
                ctx.warn("size class " + name + ": the navmesh is empty");
            }
        }
    }

    /** Bakes the occlusion grid over the BVH's bounds into {@code <base>.jocc}. */
    public static void bakeOcclusion(
            CookContext ctx, Bvh bvh, float cell, float yMin, float yMax, String base) {
        float[] b = new float[6];
        bvh.bounds(b);
        OcclusionGrid grid =
                OcclusionGrid.bake(
                        bvh,
                        b[0],
                        b[2],
                        b[3] - b[0],
                        b[5] - b[2],
                        cell,
                        yMin,
                        yMax,
                        TriFlags.BLOCKS_LOS);
        ctx.output(base + ".jocc", grid.toBytes());
        ctx.stat("occlusionCells", grid.width() * grid.height());
        ctx.stat("occlusionBlocked", grid.blockedCount());
    }

    /**
     * Appends the triangles of every mesh node under {@code rootNode} of {@code scene} (every root
     * with -1) to {@code mesh}, transformed by {@code rootMatrix × (the chain of local transforms
     * from the subtree root down)} and flagged {@code flags} (a column-major 4×4; mirrored
     * transforms flip the winding). This is how a placed prefab's geometry is gathered.
     */
    public static void collectSubtree(
            GltfImporter.Scene scene,
            int rootNode,
            float[] rootMatrix,
            int flags,
            TriangleMesh mesh) {
        List<GltfImporter.Node> nodes = scene.nodes();
        int n = nodes.size();
        float[][] rel = new float[n][];
        boolean[] in = new boolean[n];
        for (int i = 0; i < n; i++) {
            GltfImporter.Node node = nodes.get(i);
            int p = node.parent();
            in[i] = rootNode < 0 ? true : i == rootNode || (p >= 0 && in[p]);
            if (!in[i]) {
                continue;
            }
            float[] local = PhysicsImporter.trs(node.translation(), node.rotation(), node.scale());
            boolean top = rootNode < 0 ? p < 0 : i == rootNode;
            rel[i] =
                    top
                            ? PhysicsImporter.mul(rootMatrix, local)
                            : PhysicsImporter.mul(rel[p], local);
            if (node.mesh() < 0) {
                continue;
            }
            PhysicsImporter.appendMesh(scene, node.mesh(), rel[i], flags, mesh);
        }
    }
}
