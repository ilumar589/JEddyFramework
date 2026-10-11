package dev.jeddy.cooker.physics;

import dev.jeddy.cooker.CookContext;
import dev.jeddy.cooker.ImportSettings;
import dev.jeddy.cooker.Importer;
import dev.jeddy.cooker.gltf.GltfDocument;
import dev.jeddy.cooker.gltf.GltfImporter;
import dev.jeddy.cooker.pak.Glob;
import dev.jeddy.core.json.Json;
import dev.jeddy.physics.bvh.Bvh;
import dev.jeddy.physics.bvh.TriFlags;
import dev.jeddy.physics.bvh.TriangleMesh;
import dev.jeddy.physics.nav.bake.NavBakeConfig;
import dev.jeddy.physics.nav.bake.NavMeshBaker;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bakes a level's physics data (DESIGN §10.3, §11; docs/formats/jcol.md, jnav.md, jocc.md) from a
 * {@code <level>.physics.json} description that points at the level's glTF:
 *
 * <ul>
 *   <li>{@code <base>.jcol}: the static collision BVH, with per-triangle flags from the {@code
 *       collision} rules (first glob over node names that matches wins; {@code "flags": []} leaves
 *       a node out), or a node's {@code extras.collision};
 *   <li>{@code <base>.<size>.jnav} per agent size class in {@code navmesh.sizes}, with the area
 *       volumes ({@code navmesh.areas}: doorways, difficult terrain) and off-mesh links ({@code
 *       navmesh.links}: jumps, ladders);
 *   <li>{@code <base>.jocc}: the fog-of-war occlusion grid from the {@code BLOCKS_LOS} triangles.
 * </ul>
 *
 * The glTF is a dependency (the scene importer still cooks it for rendering). See
 * docs/formats/physics-json.md for the description format.
 *
 * <p>Thread ownership: stateless; any thread.
 */
public final class PhysicsImporter implements Importer {
    @Override
    public String name() {
        return "physics";
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public boolean accepts(String path, ImportSettings settings) {
        String kind = settings.string("kind", null);
        return "physics".equals(kind)
                || (kind == null && path.toLowerCase(Locale.ROOT).endsWith(".physics.json"));
    }

    private record Rule(Glob nodes, int flags) {}

    @Override
    public void cook(CookContext ctx) throws Exception {
        Map<String, Object> json = Json.asObject(Json.parse(ctx.bytes()), "physics description");
        String scenePath = Json.string(json, "scene", null);
        if (scenePath == null) {
            throw new IOException(ctx.path() + ": \"scene\" (the level's glTF) is required");
        }
        Path gltf = ctx.file().resolveSibling(scenePath).normalize();
        GltfDocument doc = GltfDocument.read(gltf, ctx.readDependency(gltf), ctx::readDependency);
        GltfImporter.Scene scene = GltfImporter.importScene(doc);
        for (String w : scene.warnings()) {
            ctx.warn(w);
        }
        List<Rule> rules = rules(json);
        TriangleMesh mesh = collect(doc, scene, rules);
        if (mesh.triangleCount() == 0) {
            throw new IOException(ctx.path() + ": no collision triangles");
        }
        String base = ctx.outputBase();

        Bvh bvh = PhysicsBake.bakeCollision(ctx, mesh, base);
        Map<String, Object> nav = optObject(json, "navmesh");
        if (nav != null) {
            bakeNavmeshes(ctx, nav, mesh, base);
        }
        Map<String, Object> occ = optObject(json, "occlusion");
        if (occ == null || Json.bool(occ, "enabled", true)) {
            float cell = occ == null ? 0.5f : (float) Json.number(occ, "cellSize", 0.5);
            float yMin = occ == null ? 0.3f : (float) Json.number(occ, "yMin", 0.3);
            float yMax = occ == null ? 2.0f : (float) Json.number(occ, "yMax", 2.0);
            PhysicsBake.bakeOcclusion(ctx, bvh, cell, yMin, yMax, base);
        }
    }

    private static List<Rule> rules(Map<String, Object> json) throws IOException {
        List<Rule> rules = new ArrayList<>();
        Object list = json.get("collision");
        if (list != null) {
            for (Object o : Json.asArray(list, "collision")) {
                Map<String, Object> r = Json.asObject(o, "collision rule");
                rules.add(new Rule(Glob.of(Json.string(r, "nodes", "*")), flags(r)));
            }
        }
        if (rules.isEmpty()) {
            rules.add(new Rule(Glob.of("*"), TriFlags.SOLID));
        }
        return rules;
    }

    /** Parses {@code flags} (names), {@code surface} and {@code area} of a rule or node extras. */
    static int flags(Map<String, Object> r) throws IOException {
        int f = 0;
        Object names = r.get("flags");
        if (names != null) {
            for (Object n : Json.asArray(names, "flags")) {
                f |=
                        switch (String.valueOf(n).toUpperCase(Locale.ROOT)) {
                            case "WALKABLE" -> TriFlags.WALKABLE;
                            case "BLOCKS_LOS" -> TriFlags.BLOCKS_LOS;
                            case "BLOCKS_PROJECTILE" -> TriFlags.BLOCKS_PROJECTILE;
                            case "BLOCKS_MOVEMENT" -> TriFlags.BLOCKS_MOVEMENT;
                            case "CAMERA_FADE" -> TriFlags.CAMERA_FADE;
                            case "SOLID" -> TriFlags.SOLID;
                            default -> throw new IOException("unknown collision flag " + n);
                        };
            }
        }
        f = TriFlags.withSurface(f, Json.integer(r, "surface", 0));
        return TriFlags.withArea(f, Json.integer(r, "area", 0));
    }

    /** Gathers every mesh node's triangles in world space with its flags. */
    private static TriangleMesh collect(
            GltfDocument doc, GltfImporter.Scene scene, List<Rule> rules) throws IOException {
        List<GltfImporter.Node> nodes = scene.nodes();
        float[][] world = new float[nodes.size()][];
        TriangleMesh mesh = new TriangleMesh();
        for (int i = 0; i < nodes.size(); i++) {
            GltfImporter.Node node = nodes.get(i);
            if (node.mesh() < 0) {
                continue;
            }
            int flags = nodeFlags(doc, i, node.name(), rules);
            if ((flags & (TriFlags.SOLID | TriFlags.CAMERA_FADE)) == 0) {
                continue; // not collision
            }
            float[] m = worldMatrix(nodes, i, world);
            appendMesh(scene, node.mesh(), m, flags, mesh);
        }
        return mesh;
    }

    /** Appends glTF mesh {@code meshIndex}'s triangles transformed by {@code m} (column-major). */
    static void appendMesh(
            GltfImporter.Scene scene, int meshIndex, float[] m, int flags, TriangleMesh mesh) {
        boolean flip = det3(m) < 0;
        for (GltfImporter.Primitive p : scene.meshes().get(meshIndex).primitives()) {
            float[] pos = p.positions;
            int baseVertex = mesh.vertexCount();
            for (int v = 0; v < pos.length / 3; v++) {
                float x = pos[v * 3];
                float y = pos[v * 3 + 1];
                float z = pos[v * 3 + 2];
                mesh.addVertex(
                        m[0] * x + m[4] * y + m[8] * z + m[12],
                        m[1] * x + m[5] * y + m[9] * z + m[13],
                        m[2] * x + m[6] * y + m[10] * z + m[14]);
            }
            int[] idx = p.indices;
            for (int t = 0; t + 2 < idx.length; t += 3) {
                int a = baseVertex + idx[t];
                int b = baseVertex + idx[t + 1];
                int c = baseVertex + idx[t + 2];
                if (flip) {
                    mesh.addTriangle(a, c, b, flags);
                } else {
                    mesh.addTriangle(a, b, c, flags);
                }
            }
        }
    }

    private static int nodeFlags(GltfDocument doc, int node, String name, List<Rule> rules)
            throws IOException {
        Map<String, Object> json = doc.get("nodes", node);
        Map<String, Object> extras = optObject(json, "extras");
        if (extras != null && extras.get("collision") instanceof Map<?, ?>) {
            return flags(Json.asObject(extras.get("collision"), "extras.collision"));
        }
        for (Rule r : rules) {
            if (r.nodes.matches(name.toLowerCase(Locale.ROOT))) {
                return r.flags;
            }
        }
        return 0;
    }

    /** Returns node {@code i}'s column-major world matrix (memoized in {@code world}). */
    private static float[] worldMatrix(List<GltfImporter.Node> nodes, int i, float[][] world) {
        if (world[i] != null) {
            return world[i];
        }
        GltfImporter.Node n = nodes.get(i);
        float[] local = trs(n.translation(), n.rotation(), n.scale());
        float[] m = n.parent() < 0 ? local : mul(worldMatrix(nodes, n.parent(), world), local);
        world[i] = m;
        return m;
    }

    /** A column-major 4×4 from a translation, rotation quaternion and scale. */
    public static float[] trs(float[] t, float[] q, float[] s) {
        float x = q[0];
        float y = q[1];
        float z = q[2];
        float w = q[3];
        float[] m = new float[16];
        m[0] = (1 - 2 * (y * y + z * z)) * s[0];
        m[1] = 2 * (x * y + z * w) * s[0];
        m[2] = 2 * (x * z - y * w) * s[0];
        m[4] = 2 * (x * y - z * w) * s[1];
        m[5] = (1 - 2 * (x * x + z * z)) * s[1];
        m[6] = 2 * (y * z + x * w) * s[1];
        m[8] = 2 * (x * z + y * w) * s[2];
        m[9] = 2 * (y * z - x * w) * s[2];
        m[10] = (1 - 2 * (x * x + y * y)) * s[2];
        m[12] = t[0];
        m[13] = t[1];
        m[14] = t[2];
        m[15] = 1;
        return m;
    }

    /** Column-major 4×4 product {@code a × b}. */
    public static float[] mul(float[] a, float[] b) {
        float[] r = new float[16];
        for (int c = 0; c < 4; c++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0;
                for (int k = 0; k < 4; k++) {
                    sum += a[k * 4 + row] * b[c * 4 + k];
                }
                r[c * 4 + row] = sum;
            }
        }
        return r;
    }

    private static float det3(float[] m) {
        return m[0] * (m[5] * m[10] - m[9] * m[6])
                - m[4] * (m[1] * m[10] - m[9] * m[2])
                + m[8] * (m[1] * m[6] - m[5] * m[2]);
    }

    private static void bakeNavmeshes(
            CookContext ctx, Map<String, Object> nav, TriangleMesh mesh, String base)
            throws IOException {
        float cell = (float) Json.number(nav, "cellSize", 0.15);
        float cellHeight = (float) Json.number(nav, "cellHeight", 0.1);
        float tileMeters = (float) Json.number(nav, "tileSize", 32);
        float slope = (float) Json.number(nav, "maxSlope", 45);
        NavBakeConfig cfg =
                navConfig(
                        cell,
                        cellHeight,
                        tileMeters,
                        slope,
                        Json.integer(nav, "minRegionArea", 8),
                        Json.integer(nav, "mergeRegionArea", 20),
                        (float) Json.number(nav, "maxEdgeLength", 12),
                        (float) Json.number(nav, "maxSimplificationError", 1.3));
        List<NavMeshBaker.AreaVolume> areas = new ArrayList<>();
        Object areaList = nav.get("areas");
        if (areaList != null) {
            for (Object o : Json.asArray(areaList, "navmesh.areas")) {
                Map<String, Object> a = Json.asObject(o, "area volume");
                areas.add(
                        new NavMeshBaker.AreaVolume(
                                floats(a.get("polygon"), "polygon"),
                                (float) Json.number(a, "yMin", -1e6),
                                (float) Json.number(a, "yMax", 1e6),
                                Json.integer(a, "area", NavMeshBaker.GROUND_AREA)));
            }
        }
        List<NavMeshBaker.OffMeshLink> links = new ArrayList<>();
        Object linkList = nav.get("links");
        if (linkList != null) {
            for (Object o : Json.asArray(linkList, "navmesh.links")) {
                Map<String, Object> l = Json.asObject(o, "off-mesh link");
                float[] s = floats(l.get("start"), "start");
                float[] e = floats(l.get("end"), "end");
                links.add(
                        new NavMeshBaker.OffMeshLink(
                                s[0],
                                s[1],
                                s[2],
                                e[0],
                                e[1],
                                e[2],
                                (float) Json.number(l, "radius", 0.5),
                                Json.bool(l, "bidirectional", true),
                                Json.integer(l, "area", NavMeshBaker.GROUND_AREA),
                                Json.integer(l, "id", 0)));
            }
        }
        Map<String, Object> sizes = optObject(nav, "sizes");
        if (sizes == null) {
            sizes = new LinkedHashMap<>();
            sizes.put("medium", Map.of());
        }
        List<PhysicsBake.SizeClass> classes = new ArrayList<>();
        for (Map.Entry<String, Object> size : sizes.entrySet()) {
            Map<String, Object> s = Json.asObject(size.getValue(), "size class");
            classes.add(
                    new PhysicsBake.SizeClass(
                            size.getKey(),
                            (float) Json.number(s, "radius", 0.4),
                            (float) Json.number(s, "height", 1.8),
                            (float) Json.number(s, "climb", 0.4)));
        }
        PhysicsBake.bakeNavmeshes(ctx, cfg, areas, links, classes, mesh, base);
    }

    /**
     * The voxel, region and tile settings shared by every size class (the agent fields are filled
     * per class by {@code forAgent}).
     */
    public static NavBakeConfig navConfig(
            float cell,
            float cellHeight,
            float tileMeters,
            float slope,
            int minRegionArea,
            int mergeRegionArea,
            float maxEdgeLength,
            float maxSimplificationError) {
        return new NavBakeConfig(
                cell,
                cellHeight,
                1.8f,
                0.4f,
                0.4f,
                slope,
                Math.max(16, Math.round(tileMeters / cell)),
                minRegionArea,
                mergeRegionArea,
                maxEdgeLength,
                maxSimplificationError,
                6);
    }

    private static float[] floats(Object v, String what) {
        List<Object> list = Json.asArray(v, what);
        float[] out = new float[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = ((Number) list.get(i)).floatValue();
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> optObject(Map<String, Object> owner, String key) {
        Object v = owner.get(key);
        return v instanceof Map<?, ?> ? (Map<String, Object>) v : null;
    }
}
