package dev.jeddy.physics.nav.bake;

import dev.jeddy.core.job.JobKernel;
import dev.jeddy.core.job.JobSystem;
import dev.jeddy.physics.bvh.TriangleMesh;
import dev.jeddy.physics.nav.NavMesh;
import dev.jeddy.physics.nav.NavMeshData;
import dev.jeddy.physics.nav.NavTile;

import java.util.Arrays;
import java.util.List;

/**
 * Temporary obstacles on a baked navmesh (DESIGN §11.4 dynamic changes): keeps each tile's voxels
 * (the compact heightfield before erosion), and when obstacles (summoned walls, barricades, big
 * props) are added or removed, rebuilds the touched tiles from those voxels on job workers, in the
 * background, swapping the finished tiles into the {@link NavMesh} on the game thread. Obstacles
 * are marked before erosion, so agents keep their radius from them.
 *
 * <p>Thread ownership: the game thread; {@link #update} must run between navmesh query phases (it
 * changes the mesh). Rebuilds allocate (cold path); a quiet {@link #update} allocates nothing.
 */
public final class TileCache {
    /**
     * Floats per obstacle: type (0 cylinder, 1 box), then x, y, z, radius, height or box min, max.
     */
    static final int OBSTACLE_FLOATS = 7;

    private final NavBakeConfig cfg;
    private final NavMeshBaker.Layout layout;
    private final List<NavMeshBaker.AreaVolume> volumes;
    private final List<NavMeshBaker.OffMeshLink> links;
    private final CompactHeightfield[] voxels;
    private final NavMeshData data;
    private final boolean[] dirty;
    private int dirtyCount;

    private float[] obstacles = new float[16 * OBSTACLE_FLOATS];
    private boolean[] alive = new boolean[16];
    private int obstacleSlots;

    // The rebuild in flight.
    private final int[] pending;
    private final NavTile[] results;
    private int pendingCount;
    private int handle = JobSystem.NO_JOB;
    private float[] snapshot = new float[16 * OBSTACLE_FLOATS];
    private int snapshotCount;
    private final JobKernel kernel = this::rebuildRange;

    private TileCache(
            NavBakeConfig cfg,
            NavMeshBaker.Layout layout,
            List<NavMeshBaker.AreaVolume> volumes,
            List<NavMeshBaker.OffMeshLink> links,
            CompactHeightfield[] voxels,
            NavMeshData data) {
        this.cfg = cfg;
        this.layout = layout;
        this.volumes = volumes;
        this.links = links;
        this.voxels = voxels;
        this.data = data;
        int tiles = voxels.length;
        dirty = new boolean[tiles];
        pending = new int[tiles];
        results = new NavTile[tiles];
    }

    /**
     * Bakes {@code mesh} like {@link NavMeshBaker#bake} and keeps the voxels for rebuilds; {@code
     * jobs} may be {@code null}.
     */
    public static TileCache build(
            TriangleMesh mesh,
            NavBakeConfig cfg,
            List<NavMeshBaker.AreaVolume> volumes,
            List<NavMeshBaker.OffMeshLink> links,
            JobSystem jobs) {
        NavMeshBaker.Layout l = NavMeshBaker.layout(mesh, cfg, jobs);
        int tiles = l.tilesX() * l.tilesZ();
        CompactHeightfield[] voxels = new CompactHeightfield[tiles];
        NavTile[] out = new NavTile[tiles];
        NavMeshBaker.forEachTile(
                l,
                jobs,
                (tx, tz, t) -> {
                    CompactHeightfield chf =
                            NavMeshBaker.rasterizeTile(
                                    l.bvh(),
                                    cfg,
                                    tx,
                                    tz,
                                    l.originX(),
                                    l.originZ(),
                                    l.tileCells(),
                                    l.bottom(),
                                    l.top());
                    voxels[t] = chf;
                    out[t] =
                            NavMeshBaker.finishTile(
                                    chf == null ? null : new CompactHeightfield(chf),
                                    cfg,
                                    tx,
                                    tz,
                                    l.originX(),
                                    l.originZ(),
                                    l.tileCells(),
                                    l.bottom(),
                                    volumes,
                                    links,
                                    null,
                                    0);
                });
        return new TileCache(cfg, l, volumes, links, voxels, NavMeshBaker.data(l, cfg, out));
    }

    /** Returns the tiles as baked (no obstacles), to create the {@link NavMesh} from. */
    public NavMeshData data() {
        return data;
    }

    /** Adds a vertical cylinder obstacle standing at {@code (x, y, z)}; returns its id. */
    public int addCylinder(float x, float y, float z, float radius, float height) {
        int id = slot();
        int o = id * OBSTACLE_FLOATS;
        obstacles[o] = 0;
        obstacles[o + 1] = x;
        obstacles[o + 2] = y;
        obstacles[o + 3] = z;
        obstacles[o + 4] = radius;
        obstacles[o + 5] = height;
        obstacles[o + 6] = 0;
        touch(x - radius, z - radius, x + radius, z + radius);
        return id;
    }

    /** Adds an axis-aligned box obstacle; returns its id. */
    public int addBox(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        int id = slot();
        int o = id * OBSTACLE_FLOATS;
        obstacles[o] = 1;
        obstacles[o + 1] = minX;
        obstacles[o + 2] = minY;
        obstacles[o + 3] = minZ;
        obstacles[o + 4] = maxX;
        obstacles[o + 5] = maxY;
        obstacles[o + 6] = maxZ;
        touch(minX, minZ, maxX, maxZ);
        return id;
    }

    /** Removes obstacle {@code id}. */
    public void remove(int id) {
        if (id < 0 || id >= obstacleSlots || !alive[id]) {
            return;
        }
        alive[id] = false;
        int o = id * OBSTACLE_FLOATS;
        if (obstacles[o] == 0) {
            float r = obstacles[o + 4];
            touch(
                    obstacles[o + 1] - r,
                    obstacles[o + 3] - r,
                    obstacles[o + 1] + r,
                    obstacles[o + 3] + r);
        } else {
            touch(obstacles[o + 1], obstacles[o + 3], obstacles[o + 4], obstacles[o + 6]);
        }
    }

    private int slot() {
        for (int i = 0; i < obstacleSlots; i++) {
            if (!alive[i]) {
                alive[i] = true;
                return i;
            }
        }
        if (obstacleSlots == alive.length) {
            alive = Arrays.copyOf(alive, obstacleSlots * 2);
            obstacles = Arrays.copyOf(obstacles, obstacleSlots * 2 * OBSTACLE_FLOATS);
        }
        alive[obstacleSlots] = true;
        return obstacleSlots++;
    }

    /** Marks the tiles whose voxels (tile plus border) overlap the rectangle. */
    private void touch(float minX, float minZ, float maxX, float maxZ) {
        float pad = cfg.borderSize() * cfg.cellSize();
        float w = layout.tileWorld();
        int x0 = Math.max(0, (int) Math.floor((minX - pad - layout.originX()) / w));
        int x1 =
                Math.min(
                        layout.tilesX() - 1, (int) Math.floor((maxX + pad - layout.originX()) / w));
        int z0 = Math.max(0, (int) Math.floor((minZ - pad - layout.originZ()) / w));
        int z1 =
                Math.min(
                        layout.tilesZ() - 1, (int) Math.floor((maxZ + pad - layout.originZ()) / w));
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                int t = x + z * layout.tilesX();
                if (!dirty[t]) {
                    dirty[t] = true;
                    dirtyCount++;
                }
            }
        }
    }

    /** Returns whether a rebuild is running or tiles wait for one. */
    public boolean busy() {
        return handle != JobSystem.NO_JOB || dirtyCount > 0;
    }

    /**
     * Swaps in tiles whose rebuild finished and starts rebuilding tiles that changed since; with
     * {@code jobs == null} rebuilds synchronously. Returns the number of tiles swapped into {@code
     * mesh} (refresh door flags and the like when it is not 0).
     */
    public int update(NavMesh mesh, JobSystem jobs) {
        int swapped = 0;
        if (handle != JobSystem.NO_JOB) {
            if (!jobs.isDone(handle)) {
                return 0;
            }
            jobs.wait(handle); // rethrows a failed rebuild
            handle = JobSystem.NO_JOB;
            swapped = swap(mesh);
        }
        if (dirtyCount == 0) {
            return swapped;
        }
        start();
        if (jobs == null) {
            rebuildRange(0, pendingCount, 0);
            return swapped + swap(mesh);
        }
        handle = jobs.submit(kernel, pendingCount, 1);
        return swapped;
    }

    /** Finishes the rebuild in flight and every pending change (loading screens, tests). */
    public int flush(NavMesh mesh, JobSystem jobs) {
        int swapped = 0;
        while (busy()) {
            if (handle != JobSystem.NO_JOB) {
                jobs.wait(handle);
            }
            swapped += update(mesh, jobs);
        }
        return swapped;
    }

    private void start() {
        pendingCount = 0;
        for (int t = 0; t < dirty.length; t++) {
            if (dirty[t]) {
                dirty[t] = false;
                pending[pendingCount++] = t;
            }
        }
        dirtyCount = 0;
        // Snapshot the obstacles: the game may change them while the rebuild runs.
        snapshotCount = 0;
        if (snapshot.length < obstacleSlots * OBSTACLE_FLOATS) {
            snapshot = new float[obstacles.length];
        }
        for (int i = 0; i < obstacleSlots; i++) {
            if (alive[i]) {
                System.arraycopy(
                        obstacles,
                        i * OBSTACLE_FLOATS,
                        snapshot,
                        snapshotCount * OBSTACLE_FLOATS,
                        OBSTACLE_FLOATS);
                snapshotCount++;
            }
        }
    }

    private void rebuildRange(int from, int to, int thread) {
        NavMeshBaker.Layout l = layout;
        for (int k = from; k < to; k++) {
            int t = pending[k];
            CompactHeightfield src = voxels[t];
            results[k] =
                    NavMeshBaker.finishTile(
                            src == null ? null : new CompactHeightfield(src),
                            cfg,
                            t % l.tilesX(),
                            t / l.tilesX(),
                            l.originX(),
                            l.originZ(),
                            l.tileCells(),
                            l.bottom(),
                            volumes,
                            links,
                            snapshot,
                            snapshotCount);
        }
    }

    private int swap(NavMesh mesh) {
        for (int k = 0; k < pendingCount; k++) {
            int t = pending[k];
            int tx = t % layout.tilesX();
            int tz = t / layout.tilesX();
            mesh.removeTile(tx, tz);
            if (results[k] != null) {
                mesh.addTile(results[k]);
                results[k] = null;
            }
        }
        int n = pendingCount;
        pendingCount = 0;
        return n;
    }
}
