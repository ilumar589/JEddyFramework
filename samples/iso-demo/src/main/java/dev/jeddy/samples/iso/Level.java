package dev.jeddy.samples.iso;

import dev.jeddy.app.physics.FogOfWarFeed;
import dev.jeddy.app.world.World;
import dev.jeddy.asset.format.SceneData;
import dev.jeddy.core.job.JobSystem;
import dev.jeddy.core.json.Json;
import dev.jeddy.math.Rng;
import dev.jeddy.physics.bvh.Bvh;
import dev.jeddy.physics.crowd.Crowd;
import dev.jeddy.physics.grid.UniformGrid;
import dev.jeddy.physics.nav.NavMesh;
import dev.jeddy.physics.nav.NavMeshData;
import dev.jeddy.physics.nav.NavMeshQuery;
import dev.jeddy.physics.nav.QueryFilter;
import dev.jeddy.physics.query.CollisionWorld;
import dev.jeddy.physics.vis.OcclusionGrid;
import dev.jeddy.physics.vis.Visibility;
import dev.jeddy.render.RenderPacket;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The crypt once its cooked data is in: the static scene in the {@link World}, collision, navmesh,
 * crowd, fog-of-war visibility, torch lights and spawn points (TASKS M10-03, M10-04).
 *
 * <p>Thread ownership: the game thread.
 */
final class Level {
    static final String SCENE = "levels/crypt.jscene";
    static final String COLLISION = "levels/crypt.jcol";
    static final String NAVMESH = "levels/crypt.medium.jnav";
    static final String OCCLUSION = "levels/crypt.jocc";
    static final String SPAWNS = "levels/crypt.spawns.json";

    /** Body layer of every crowd agent (party and enemies). */
    static final int UNITS = 1;

    /** Scales the cooked torch intensities (candela) to the demo's exposure. */
    static final float TORCH_GAIN = 2.5f;

    /** Floats per torch: x, y, z, radius, r, g, b, intensity. */
    static final int TORCH_FLOATS = 8;

    final SceneData scene;
    final float minX;
    final float minZ;
    final float sizeX;
    final float sizeZ;
    final int root;
    final NavMesh nav;
    final NavMeshQuery query;
    final QueryFilter filter;
    final Crowd crowd;
    final CollisionWorld collision;
    final Visibility visibility;
    final FogOfWarFeed fog;
    final float[] partySpawns;
    final float[] enemySpawns;
    final float[] torches;
    final int torchCount;

    /** Random walkable points (x, y, z each) for wandering and props. */
    final float[] walkable;

    final int walkableCount;

    private final float[] point = new float[3];

    Level(
            World world,
            JobSystem jobs,
            SceneData scene,
            Bvh bvh,
            NavMeshData navData,
            OcclusionGrid occlusion,
            byte[] spawnsJson,
            int maxAgents,
            long seed) {
        this.scene = scene;
        minX = occlusion.minX();
        minZ = occlusion.minZ();
        sizeX = occlusion.width() * occlusion.cellSize();
        sizeZ = occlusion.height() * occlusion.cellSize();
        root =
                world.instantiate(
                        scene, 0, 0, 0, 0, RenderPacket.FLAG_STATIC, Level::nodeFlags, true);

        nav = new NavMesh(navData);
        query = new NavMeshQuery(nav, 4096);
        filter = new QueryFilter();
        crowd = new Crowd(nav, maxAgents, minX - 4, minZ - 4, sizeX + 8, sizeZ + 8, jobs);
        crowd.setMaxRequestsPerTick(24);
        collision =
                new CollisionWorld(
                        bvh,
                        crowd.bodies(),
                        new UniformGrid(minX - 4, minZ - 4, sizeX + 8, sizeZ + 8, 2f, jobs));
        visibility = new Visibility(occlusion, jobs);
        visibility.setMoveThreshold(0.5f);
        fog = new FogOfWarFeed(visibility);

        Map<String, Object> spawns =
                Json.asObject(Json.parse(new String(spawnsJson, StandardCharsets.UTF_8)), "spawns");
        partySpawns = points(spawns.get("party"));
        enemySpawns = points(spawns.get("enemies"));
        List<Object> lights = Json.asArray(spawns.get("lights"), "lights");
        torchCount = lights.size();
        torches = new float[torchCount * TORCH_FLOATS];
        for (int i = 0; i < torchCount; i++) {
            Map<String, Object> l = Json.asObject(lights.get(i), "light");
            List<Object> c = Json.asArray(l.get("color"), "color");
            int o = i * TORCH_FLOATS;
            torches[o] = (float) Json.number(l, "x", 0);
            torches[o + 1] = (float) Json.number(l, "y", 2);
            torches[o + 2] = (float) Json.number(l, "z", 0);
            torches[o + 3] = (float) Json.number(l, "radius", 8);
            torches[o + 4] = ((Double) c.get(0)).floatValue();
            torches[o + 5] = ((Double) c.get(1)).floatValue();
            torches[o + 6] = ((Double) c.get(2)).floatValue();
            torches[o + 7] = (float) Json.number(l, "intensity", 5);
        }

        // Sample walkable ground: random points snapped to the navmesh (wander goals, props).
        Rng rng = new Rng(seed);
        float[] samples = new float[4096 * 3];
        int count = 0;
        for (int attempt = 0; attempt < 20_000 && count < 4096; attempt++) {
            float x = minX + rng.nextFloat() * sizeX;
            float z = minZ + rng.nextFloat() * sizeZ;
            if (snap(x, 0, z, 0.3f, point)) {
                samples[count * 3] = point[0];
                samples[count * 3 + 1] = point[1];
                samples[count * 3 + 2] = point[2];
                count++;
            }
        }
        walkable = samples;
        walkableCount = count;
    }

    /**
     * Snaps {@code (x, y, z)} to the navmesh within {@code reach} meters horizontally; returns
     * {@code false} if there is no walkable ground that close.
     */
    boolean snap(float x, float y, float z, float reach, float[] out) {
        int ref = query.findNearestPoly(x, y, z, reach, 2f, reach, filter, out);
        return ref != NavMesh.NULL_REF
                && Math.abs(out[0] - x) <= reach
                && Math.abs(out[2] - z) <= reach;
    }

    /**
     * Adds the torches, flickering with {@code time}; the {@code shadowed} torches nearest to
     * {@code (x, z)} cast shadows (keyed by torch, so their shadow tiles persist).
     */
    void extractLights(RenderPacket p, float time, float x, float z, int shadowed) {
        int n = Math.min(shadowed, nearest.length);
        for (int k = 0; k < n; k++) {
            nearest[k] = -1;
            nearestD2[k] = Float.MAX_VALUE;
        }
        for (int i = 0; i < torchCount; i++) {
            int o = i * TORCH_FLOATS;
            float dx = torches[o] - x;
            float dz = torches[o + 2] - z;
            float d2 = dx * dx + dz * dz;
            for (int k = 0; k < n; k++) {
                if (d2 < nearestD2[k]) {
                    for (int m = n - 1; m > k; m--) {
                        nearest[m] = nearest[m - 1];
                        nearestD2[m] = nearestD2[m - 1];
                    }
                    nearest[k] = i;
                    nearestD2[k] = d2;
                    break;
                }
            }
        }
        for (int i = 0; i < torchCount; i++) {
            int o = i * TORCH_FLOATS;
            float flicker =
                    0.85f
                            + 0.1f * (float) Math.sin(time * 9.1f + i * 1.7f)
                            + 0.05f * (float) Math.sin(time * 23.3f + i * 4.1f);
            int light =
                    p.addPointLight(
                            torches[o],
                            torches[o + 1],
                            torches[o + 2],
                            torches[o + 3],
                            torches[o + 4],
                            torches[o + 5],
                            torches[o + 6],
                            torches[o + 7] * TORCH_GAIN * flicker);
            for (int k = 0; k < n; k++) {
                if (nearest[k] == i) {
                    p.setLightShadow(light, SHADOW_KEY_TORCHES + i);
                }
            }
        }
    }

    /** Shadow keys of torches start here (the party light uses 1). */
    static final int SHADOW_KEY_TORCHES = 100;

    private final int[] nearest = new int[4];
    private final float[] nearestD2 = new float[4];

    /**
     * Render flags per crypt node: floors only receive, walls and pillars cast shadows and fade
     * when they hide the party, props cast shadows.
     */
    private static int nodeFlags(String name, int flags) {
        if (name.startsWith("floor_")) {
            return RenderPacket.FLAG_STATIC;
        }
        if (name.startsWith("wall_")) {
            return RenderPacket.FLAG_STATIC
                    | RenderPacket.FLAG_CASTS_SHADOW
                    | RenderPacket.FLAG_FADE_OCCLUDER;
        }
        return RenderPacket.FLAG_STATIC | RenderPacket.FLAG_CASTS_SHADOW;
    }

    private static float[] points(Object value) {
        List<Object> list = Json.asArray(value, "points");
        float[] out = new float[list.size() * 3];
        for (int i = 0; i < list.size(); i++) {
            List<Object> p = Json.asArray(list.get(i), "point");
            for (int k = 0; k < 3; k++) {
                out[i * 3 + k] = ((Double) p.get(k)).floatValue();
            }
        }
        return out;
    }
}
