package dev.jeddy.cooker.level;

import dev.jeddy.asset.AssetPaths;
import dev.jeddy.asset.format.ComponentSchema;
import dev.jeddy.asset.format.LevelData;
import dev.jeddy.cooker.CookContext;
import dev.jeddy.cooker.ImportSettings;
import dev.jeddy.cooker.Importer;
import dev.jeddy.cooker.gltf.GltfDocument;
import dev.jeddy.cooker.gltf.GltfImporter;
import dev.jeddy.cooker.pak.Glob;
import dev.jeddy.cooker.physics.PhysicsBake;
import dev.jeddy.cooker.physics.PhysicsImporter;
import dev.jeddy.physics.bvh.Bvh;
import dev.jeddy.physics.bvh.TriFlags;
import dev.jeddy.physics.bvh.TriangleMesh;
import dev.jeddy.physics.nav.bake.NavBakeConfig;
import dev.jeddy.physics.nav.bake.NavMeshBaker;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Cooks {@code .jlevel} levels (TASKS M14-04, DESIGN §19, D90): the file is copied to the output
 * unchanged (the runtime reads what the editor wrote), and its physics is derived from the
 * placements: every placed prefab's mesh nodes are composed with the placement transform into one
 * triangle soup, flagged by the level's collision rules matched against the placement's layer (or
 * the placement's own override), then {@link PhysicsBake} writes the collision BVH, one navmesh per
 * agent size class with the level's area volumes and links, and the occlusion grid.
 *
 * <p>The prefab glTFs are dependencies: editing a piece re-bakes every level that places it. A
 * prefab's scene path ({@code levels/dungeon.jscene}) is mapped back to its source ({@code
 * levels/dungeon.glb} or {@code .gltf} under the source root, matched case-insensitively).
 *
 * <p>Thread ownership: cooker workers.
 */
public final class LevelImporter implements Importer {
    private record Rule(Glob layer, int flags) {}

    @Override
    public String name() {
        return "level";
    }

    @Override
    public int version() {
        return 2; // component rows validated (M22)
    }

    @Override
    public boolean accepts(String path, ImportSettings settings) {
        return path.toLowerCase(Locale.ROOT).endsWith(".jlevel");
    }

    /**
     * Checks the component rows against the game's schema when the cook has one ({@code
     * --components}, DESIGN §22, D109): unknown components, columns and enum constants are
     * warnings, since the runtime skips them too. The schema is a dependency, so a changed game
     * re-cooks the level.
     */
    private static void validateComponents(CookContext ctx, LevelData level) throws IOException {
        Path schemaFile = ctx.config().componentsSchema();
        if (schemaFile == null || level.componentRowCount() == 0) {
            return;
        }
        if (!Files.isRegularFile(schemaFile)) {
            ctx.recordAbsentDependency(schemaFile);
            ctx.warn("components schema " + schemaFile + " is missing: rows not validated");
            return;
        }
        ComponentSchema schema;
        try {
            schema = ComponentSchema.parse(ctx.readDependency(schemaFile));
        } catch (RuntimeException e) {
            ctx.warn("components schema " + schemaFile + ": " + e.getMessage());
            return;
        }
        for (int row = 0; row < level.componentRowCount(); row++) {
            String name = level.componentRowComponent(row);
            int object = level.componentRowObject(row);
            ComponentSchema.Component c = schema.component(name);
            if (c == null) {
                ctx.warn("object " + object + ": unknown component " + name);
                continue;
            }
            for (int k = 0; k < level.componentValueCount(row); k++) {
                String column = level.componentValueColumn(row, k);
                ComponentSchema.Field f = c.fieldOfColumn(column);
                if (f == null) {
                    ctx.warn("object " + object + ": " + name + " has no column " + column);
                } else if (f.type() == ComponentSchema.Type.ENUM
                        && level.componentValueType(row, k) == LevelData.VALUE_ENUM
                        && !f.enumValues().contains(level.componentValueText(row, k))) {
                    ctx.warn(
                            "object "
                                    + object
                                    + ": "
                                    + name
                                    + "."
                                    + column
                                    + " has no constant "
                                    + level.componentValueText(row, k));
                }
            }
        }
    }

    @Override
    public void cook(CookContext ctx) throws Exception {
        LevelData level = LevelData.read(MemorySegment.ofArray(ctx.bytes()));
        String base = ctx.outputBase();
        ctx.output(base + ".jlevel", ctx.bytes());
        ctx.stat("placements", level.placementCount());
        ctx.stat("lights", level.lightCount());
        ctx.stat("spawns", level.spawnCount());
        ctx.stat("triggers", level.triggerCount());
        ctx.stat("componentRows", level.componentRowCount());
        validateComponents(ctx, level);

        List<Rule> rules = new ArrayList<>();
        for (int r = 0; r < level.ruleCount(); r++) {
            int flags = level.ruleFlags(r);
            flags = TriFlags.withSurface(flags, level.ruleSurface(r));
            flags = TriFlags.withArea(flags, level.ruleArea(r));
            rules.add(new Rule(Glob.of(level.ruleLayer(r).toLowerCase(Locale.ROOT)), flags));
        }
        Map<Integer, Prefab> prefabs = new HashMap<>();
        TriangleMesh mesh = new TriangleMesh();
        float[] matrix = new float[16];
        for (int i = 0; i < level.placementCount(); i++) {
            int flags = collisionFlags(level, i, rules);
            if (flags == 0) {
                continue;
            }
            int p = level.placementPrefab(i);
            Prefab prefab = prefabs.get(p);
            if (prefab == null) {
                prefab = loadPrefab(ctx, level, p);
                prefabs.put(p, prefab);
            }
            if (prefab == null || prefab.root == -2) {
                continue;
            }
            placementMatrix(level, i, matrix);
            PhysicsBake.collectSubtree(prefab.scene, prefab.root, matrix, flags, mesh);
        }
        if (mesh.triangleCount() == 0) {
            ctx.warn("no collision triangles: no physics baked");
            return;
        }
        Bvh bvh = PhysicsBake.bakeCollision(ctx, mesh, base);

        LevelData.Settings s = level.settings;
        NavBakeConfig cfg =
                PhysicsImporter.navConfig(
                        s.navCellSize,
                        s.navCellHeight,
                        s.navTileSize,
                        s.navMaxSlope,
                        8,
                        20,
                        12,
                        1.3f);
        List<NavMeshBaker.AreaVolume> areas = new ArrayList<>();
        for (int a = 0; a < level.areaCount(); a++) {
            areas.add(
                    new NavMeshBaker.AreaVolume(
                            level.areaPolygon(a),
                            level.areaYMin(a),
                            level.areaYMax(a),
                            level.areaArea(a)));
        }
        List<NavMeshBaker.OffMeshLink> links = new ArrayList<>();
        for (int l = 0; l < level.linkCount(); l++) {
            links.add(
                    new NavMeshBaker.OffMeshLink(
                            level.linkFloat(l, 0),
                            level.linkFloat(l, 1),
                            level.linkFloat(l, 2),
                            level.linkFloat(l, 3),
                            level.linkFloat(l, 4),
                            level.linkFloat(l, 5),
                            level.linkFloat(l, 6),
                            (level.linkFlags(l) & LevelData.LINK_BIDIRECTIONAL) != 0,
                            level.linkArea(l),
                            level.linkUserId(l)));
        }
        List<PhysicsBake.SizeClass> sizes = new ArrayList<>();
        for (int c = 0; c < level.sizeClassCount(); c++) {
            sizes.add(
                    new PhysicsBake.SizeClass(
                            level.sizeClassName(c),
                            level.sizeClassFloat(c, 0),
                            level.sizeClassFloat(c, 1),
                            level.sizeClassFloat(c, 2)));
        }
        if (sizes.isEmpty()) {
            sizes.add(new PhysicsBake.SizeClass("medium", 0.4f, 1.8f, 0.4f));
        }
        PhysicsBake.bakeNavmeshes(ctx, cfg, areas, links, sizes, mesh, base);
        if (s.occlusionEnabled) {
            PhysicsBake.bakeOcclusion(
                    ctx, bvh, s.occlusionCellSize, s.occlusionYMin, s.occlusionYMax, base);
        }
    }

    /**
     * The triangle flags of placement {@code i}: its override, else the first rule its layer
     * matches.
     */
    static int collisionFlags(LevelData level, int i, List<Rule> rules) {
        if ((level.placementFlags(i) & LevelData.PLACEMENT_NO_COLLISION) != 0) {
            return 0;
        }
        int override = level.placementCollision(i);
        if (override != LevelData.COLLISION_BY_LAYER) {
            return override;
        }
        String layer = level.placementLayer(i).toLowerCase(Locale.ROOT);
        for (Rule r : rules) {
            if (r.layer.matches(layer)) {
                return r.flags;
            }
        }
        return 0;
    }

    /** Writes placement {@code i}'s column-major 4×4 world matrix into {@code out}. */
    static void placementMatrix(LevelData level, int i, float[] out) {
        float[] d = level.placementData();
        int o = i * LevelData.PLACEMENT_FLOATS;
        float[] m =
                PhysicsImporter.trs(
                        new float[] {d[o], d[o + 1], d[o + 2]},
                        new float[] {d[o + 3], d[o + 4], d[o + 5], d[o + 6]},
                        new float[] {d[o + 7], d[o + 8], d[o + 9]});
        System.arraycopy(m, 0, out, 0, 16);
    }

    private record Prefab(GltfImporter.Scene scene, int root) {}

    /** Loads prefab {@code p}'s source glTF (a dependency) and finds its root node. */
    private static Prefab loadPrefab(CookContext ctx, LevelData level, int p) throws IOException {
        String scenePath = level.prefabScene(p);
        Path gltf = findSource(ctx, scenePath);
        if (gltf == null) {
            ctx.warn(
                    "prefab scene "
                            + scenePath
                            + " has no glTF source; its placements have no collision");
            return null;
        }
        GltfDocument doc = GltfDocument.read(gltf, ctx.readDependency(gltf), ctx::readDependency);
        // The scene's own import settings (its .meta sidecar) apply here too, so a node excluded
        // from the cooked scene is excluded from the level's collision and navmesh as well.
        ImportSettings settings = ImportSettings.load(ctx.config().sourceRoot(), gltf);
        ctx.dependOnSettings(settings);
        GltfImporter.Scene scene =
                GltfImporter.importScene(doc, settings.strings("excludeNodes", List.of()));
        for (String w : scene.warnings()) {
            ctx.warn(scenePath + ": " + w);
        }
        for (String w : scene.warnings()) {
            ctx.warn(w);
        }
        String node = level.prefabNode(p);
        int root = -1;
        if (!node.isEmpty()) {
            root = -2;
            List<GltfImporter.Node> nodes = scene.nodes();
            for (int i = 0; i < nodes.size(); i++) {
                if (node.equals(nodes.get(i).name())) {
                    root = i;
                    break;
                }
            }
            if (root == -2) {
                ctx.warn("prefab node " + node + " is not in " + scenePath);
            }
        }
        return new Prefab(scene, root);
    }

    /**
     * Maps a cooked scene path back to its glTF source under the source root: {@code
     * levels/dungeon.jscene} → {@code levels/dungeon.glb} or {@code .gltf}, case-insensitively.
     */
    static Path findSource(CookContext ctx, String scenePath) throws IOException {
        String base =
                scenePath.endsWith(".jscene")
                        ? scenePath.substring(0, scenePath.length() - 7)
                        : scenePath;
        Path root = ctx.config().sourceRoot();
        for (String ext : new String[] {".glb", ".gltf"}) {
            Path candidate = root.resolve(base + ext);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        int slash = base.lastIndexOf('/');
        Path dir = slash < 0 ? root : root.resolve(base.substring(0, slash));
        String stem = slash < 0 ? base : base.substring(slash + 1);
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) {
                String name = f.getFileName().toString();
                String lower = name.toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".glb") && !lower.endsWith(".gltf")) {
                    continue;
                }
                String fileStem = name.substring(0, name.lastIndexOf('.'));
                if (AssetPaths.normalize(CookContext.sanitize(fileStem)).equals(stem)) {
                    return f;
                }
            }
        }
        return null;
    }
}
