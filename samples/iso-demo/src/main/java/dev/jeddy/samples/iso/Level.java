package dev.jeddy.samples.iso;

import dev.jeddy.app.physics.FogOfWarFeed;
import dev.jeddy.app.world.LevelLoader;
import dev.jeddy.asset.format.LevelData;
import dev.jeddy.core.job.JobSystem;
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

/**
 * The crypt once its cooked data is in: collision, navmesh, crowd, fog-of-war visibility, and the
 * level's lights and spawn points (TASKS M10-03, M10-04, M14-03). The placements themselves are in
 * the world through the {@link LevelLoader}.
 *
 * <p>Thread ownership: the game thread.
 */
final class Level {
    static final String LEVEL = "levels/crypt.jlevel";
    static final String COLLISION = "levels/crypt.jcol";
    static final String NAVMESH = "levels/crypt.medium.jnav";
    static final String OCCLUSION = "levels/crypt.jocc";

    /** Body layer of every crowd agent (party and enemies). */
    static final int UNITS = 1;

    /** Scales the level's torch intensities (candela) to the demo's exposure. */
    static final float TORCH_GAIN = 2.5f;

    final LevelData data;
    final LevelLoader loader;
    final float minX;
    final float minZ;
    final float sizeX;
    final float sizeZ;
    final NavMesh nav;
    final NavMeshQuery query;
    final QueryFilter filter;
    final Crowd crowd;
    final CollisionWorld collision;
    final Visibility visibility;
    final FogOfWarFeed fog;
    final float[] partySpawns;
    final float[] enemySpawns;

    /** Random walkable points (x, y, z each) for wandering and props. */
    final float[] walkable;

    final int walkableCount;

    private final float[] point = new float[3];

    /**
     * Builds the crypt's physics and systems around {@code data}, whose placements {@code loader}
     * has put into the world (TASKS M14-03: the level is a {@code .jlevel} of prefab placements).
     */
    Level(
            JobSystem jobs,
            LevelData data,
            LevelLoader loader,
            Bvh bvh,
            NavMeshData navData,
            OcclusionGrid occlusion,
            int maxAgents,
            long seed) {
        this.data = data;
        this.loader = loader;
        minX = occlusion.minX();
        minZ = occlusion.minZ();
        sizeX = occlusion.width() * occlusion.cellSize();
        sizeZ = occlusion.height() * occlusion.cellSize();

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

        partySpawns = spawns(data, "party");
        enemySpawns = spawns(data, "enemy");

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

    /** The positions (x, y, z each) of the level's spawn points of {@code kind}. */
    private static float[] spawns(LevelData data, String kind) {
        int n = 0;
        for (int i = 0; i < data.spawnCount(); i++) {
            if (kind.equals(data.spawnKind(i))) {
                n++;
            }
        }
        float[] out = new float[n * 3];
        int k = 0;
        for (int i = 0; i < data.spawnCount(); i++) {
            if (kind.equals(data.spawnKind(i))) {
                out[k * 3] = data.spawnFloat(i, 0);
                out[k * 3 + 1] = data.spawnFloat(i, 1);
                out[k * 3 + 2] = data.spawnFloat(i, 2);
                k++;
            }
        }
        return out;
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
     * Adds the level's lights (torches flicker); the {@code shadowed} ones nearest to {@code (x,
     * z)} cast shadows (keyed by light, so their shadow tiles persist).
     */
    void extractLights(RenderPacket p, float time, float x, float z, int shadowed) {
        int n = Math.min(shadowed, nearest.length);
        for (int k = 0; k < n; k++) {
            nearest[k] = -1;
            nearestD2[k] = Float.MAX_VALUE;
        }
        float[] d = data.lightData();
        for (int i = 0; i < data.lightCount(); i++) {
            int o = i * LevelData.LIGHT_FLOATS;
            float dx = d[o] - x;
            float dz = d[o + 2] - z;
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
        int first = loader.extractLights(p, time, TORCH_GAIN);
        for (int k = 0; k < n; k++) {
            if (nearest[k] >= 0) {
                p.setLightShadow(first + nearest[k], SHADOW_KEY_TORCHES + nearest[k]);
            }
        }
    }

    /** Shadow keys of torches start here (the party light uses 1). */
    static final int SHADOW_KEY_TORCHES = 100;

    private final int[] nearest = new int[4];
    private final float[] nearestD2 = new float[4];
}
