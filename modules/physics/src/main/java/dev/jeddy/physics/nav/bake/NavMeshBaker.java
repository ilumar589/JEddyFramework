package dev.jeddy.physics.nav.bake;

import dev.jeddy.core.job.JobSystem;
import dev.jeddy.physics.bvh.Bvh;
import dev.jeddy.physics.bvh.BvhBuilder;
import dev.jeddy.physics.bvh.TriFlags;
import dev.jeddy.physics.bvh.TriangleMesh;
import dev.jeddy.physics.nav.NavMeshData;
import dev.jeddy.physics.nav.NavTile;

import java.util.ArrayList;
import java.util.List;

/**
 * Bakes a tiled navmesh from collision triangles (DESIGN §11.4), Recast's pipeline in Java:
 * rasterize the {@link TriFlags#WALKABLE} (within the slope limit) and {@link
 * TriFlags#BLOCKS_MOVEMENT} triangles into a voxel heightfield per tile (with a border so tiles
 * meet), filter low obstacles, ledges and low ceilings, erode by the agent radius, mark area
 * volumes, partition by watershed, trace and simplify contours, and build convex polygons. Tiles
 * bake in parallel. Off-mesh links are added as two-vertex polygons in the tile of their start.
 *
 * <p>Detail meshes are not sampled: polygon vertices carry the surface height, which suits the
 * mostly flat floors of iso levels (DESIGN §17 D52).
 *
 * <p>Thread ownership: any thread; borrows job workers.
 */
public final class NavMeshBaker {
    /** Default walkable area id. */
    public static final int GROUND_AREA = 63;

    /**
     * A volume that marks the navmesh inside it with an area (difficult terrain, water, hazard): a
     * convex polygon {@code xz} between {@code yMin} and {@code yMax}.
     */
    public record AreaVolume(float[] xz, float yMin, float yMax, int area) {}

    /**
     * An off-mesh link (jump, ladder, door, teleport) from {@code start} to {@code end}, usable
     * both ways or one way; {@code radius} is how far its ends may be from the mesh.
     */
    public record OffMeshLink(
            float startX,
            float startY,
            float startZ,
            float endX,
            float endY,
            float endZ,
            float radius,
            boolean bidirectional,
            int area,
            int userId) {}

    private NavMeshBaker() {}

    /** Bakes {@code mesh} with no area volumes or links. */
    public static NavMeshData bake(TriangleMesh mesh, NavBakeConfig cfg, JobSystem jobs) {
        return bake(mesh, cfg, List.of(), List.of(), jobs);
    }

    /** Bakes {@code mesh} for the agent in {@code cfg}; {@code jobs} may be {@code null}. */
    public static NavMeshData bake(
            TriangleMesh mesh,
            NavBakeConfig cfg,
            List<AreaVolume> volumes,
            List<OffMeshLink> links,
            JobSystem jobs) {
        Layout l = layout(mesh, cfg, jobs);
        NavTile[] out = new NavTile[l.tilesX * l.tilesZ];
        forEachTile(
                l,
                jobs,
                (tx, tz, t) ->
                        out[t] =
                                bakeTile(
                                        l.bvh,
                                        cfg,
                                        tx,
                                        tz,
                                        l.originX,
                                        l.originZ,
                                        l.tileCells,
                                        l.bottom,
                                        l.top,
                                        volumes,
                                        links));
        return data(l, cfg, out);
    }

    /** Where the tiles of a bake lie. */
    record Layout(
            Bvh bvh,
            float originX,
            float originZ,
            int tileCells,
            float tileWorld,
            int tilesX,
            int tilesZ,
            float bottom,
            float top) {}

    @FunctionalInterface
    interface TileWork {
        void bake(int tx, int tz, int index);
    }

    static Layout layout(TriangleMesh mesh, NavBakeConfig cfg, JobSystem jobs) {
        float[] v = mesh.vertices();
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < mesh.vertexCount(); i++) {
            minX = Math.min(minX, v[i * 3]);
            minY = Math.min(minY, v[i * 3 + 1]);
            minZ = Math.min(minZ, v[i * 3 + 2]);
            maxX = Math.max(maxX, v[i * 3]);
            maxY = Math.max(maxY, v[i * 3 + 1]);
            maxZ = Math.max(maxZ, v[i * 3 + 2]);
        }
        float cs = cfg.cellSize();
        int tileCells =
                cfg.tileSize() > 0
                        ? cfg.tileSize()
                        : (int) Math.ceil(Math.max(maxX - minX, maxZ - minZ) / cs) + 1;
        float tileWorld = tileCells * cs;
        int tilesX = Math.max(1, (int) Math.ceil((maxX - minX) / tileWorld));
        int tilesZ = Math.max(1, (int) Math.ceil((maxZ - minZ) / tileWorld));
        Bvh bvh = BvhBuilder.build(mesh, jobs);
        return new Layout(
                bvh,
                minX,
                minZ,
                tileCells,
                tileWorld,
                tilesX,
                tilesZ,
                minY - 1,
                maxY + cfg.agentHeight() + 1);
    }

    static void forEachTile(Layout l, JobSystem jobs, TileWork work) {
        int tiles = l.tilesX * l.tilesZ;
        if (jobs != null && tiles > 1) {
            jobs.parallelFor(
                    (from, to, thread) -> {
                        for (int i = from; i < to; i++) {
                            work.bake(i % l.tilesX, i / l.tilesX, i);
                        }
                    },
                    tiles,
                    1);
        } else {
            for (int i = 0; i < tiles; i++) {
                work.bake(i % l.tilesX, i / l.tilesX, i);
            }
        }
    }

    static NavMeshData data(Layout l, NavBakeConfig cfg, NavTile[] out) {
        List<NavTile> list = new ArrayList<>();
        for (NavTile t : out) {
            if (t != null) {
                list.add(t);
            }
        }
        return new NavMeshData(
                l.originX,
                l.originZ,
                l.tileWorld,
                cfg.agentRadius(),
                cfg.agentHeight(),
                cfg.agentMaxClimb(),
                list);
    }

    private static NavTile bakeTile(
            Bvh bvh,
            NavBakeConfig cfg,
            int tx,
            int tz,
            float originX,
            float originZ,
            int tileCells,
            float minY,
            float maxY,
            List<AreaVolume> volumes,
            List<OffMeshLink> links) {
        CompactHeightfield chf =
                rasterizeTile(bvh, cfg, tx, tz, originX, originZ, tileCells, minY, maxY);
        return finishTile(
                chf, cfg, tx, tz, originX, originZ, tileCells, minY, volumes, links, null, 0);
    }

    /**
     * Voxelizes tile {@code (tx, tz)} and filters it into a compact heightfield, before erosion
     * (what the tile cache keeps); {@code null} when nothing in the tile is walkable.
     */
    static CompactHeightfield rasterizeTile(
            Bvh bvh,
            NavBakeConfig cfg,
            int tx,
            int tz,
            float originX,
            float originZ,
            int tileCells,
            float minY,
            float maxY) {
        float cs = cfg.cellSize();
        float ch = cfg.cellHeight();
        int border = cfg.borderSize();
        float tileMinX = originX + tx * tileCells * cs;
        float tileMinZ = originZ + tz * tileCells * cs;
        float bminX = tileMinX - border * cs;
        float bminZ = tileMinZ - border * cs;
        int size = tileCells + border * 2;
        Heightfield hf = new Heightfield(size, size, bminX, minY, bminZ, maxY, cs, ch);
        float slope = (float) Math.cos(Math.toRadians(cfg.maxSlopeDegrees()));
        int climb = cfg.walkableClimb();
        float[] a = new float[3];
        float[] b = new float[3];
        float[] c = new float[3];
        int[] stack = new int[Bvh.MAX_DEPTH * 2];
        bvh.overlapBox(
                bminX,
                minY,
                bminZ,
                bminX + size * cs,
                maxY,
                bminZ + size * cs,
                TriFlags.WALKABLE | TriFlags.BLOCKS_MOVEMENT,
                stack,
                t -> {
                    bvh.vertex(t, 0, a);
                    bvh.vertex(t, 1, b);
                    bvh.vertex(t, 2, c);
                    int flags = bvh.triangleFlags(t);
                    float e1x = b[0] - a[0], e1y = b[1] - a[1], e1z = b[2] - a[2];
                    float e2x = c[0] - a[0], e2y = c[1] - a[1], e2z = c[2] - a[2];
                    float nx = e1y * e2z - e1z * e2y;
                    float ny = e1z * e2x - e1x * e2z;
                    float nz = e1x * e2y - e1y * e2x;
                    float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                    int area = Heightfield.NULL_AREA;
                    if ((flags & TriFlags.WALKABLE) != 0 && len > 0 && ny / len > slope) {
                        int custom = TriFlags.area(flags);
                        area = custom != 0 ? custom : GROUND_AREA;
                    }
                    hf.rasterizeTriangle(
                            a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2], area, climb);
                    return true;
                });
        int walkableHeight = cfg.walkableHeight();
        hf.filterLowHangingWalkableObstacles(climb);
        hf.filterLedgeSpans(walkableHeight, climb);
        hf.filterWalkableLowHeightSpans(walkableHeight);
        CompactHeightfield chf = new CompactHeightfield(hf, walkableHeight, climb);
        return chf.spanCount == 0 ? null : chf;
    }

    /**
     * Turns a compact heightfield (consumed) into the tile: marks {@code obstacleCount} obstacles
     * of {@code obstacles} (see {@link TileCache}) as blocked, erodes by the agent radius, applies
     * area volumes, builds regions, contours and polygons, and adds the off-mesh links starting in
     * the tile. {@code null} when the tile ends up empty.
     */
    static NavTile finishTile(
            CompactHeightfield chf,
            NavBakeConfig cfg,
            int tx,
            int tz,
            float originX,
            float originZ,
            int tileCells,
            float minY,
            List<AreaVolume> volumes,
            List<OffMeshLink> links,
            float[] obstacles,
            int obstacleCount) {
        float cs = cfg.cellSize();
        float ch = cfg.cellHeight();
        float tileMinX = originX + tx * tileCells * cs;
        float tileMinZ = originZ + tz * tileCells * cs;
        if (chf == null) {
            return offMeshOnly(tx, tz, tileMinX, tileMinZ, tileCells * cs, links);
        }
        for (int o = 0; o < obstacleCount; o++) {
            markObstacle(chf, obstacles, o * TileCache.OBSTACLE_FLOATS);
        }
        chf.erode(cfg.walkableRadius());
        for (AreaVolume vol : volumes) {
            markVolume(chf, vol);
        }
        chf.buildDistanceField();
        chf.buildRegions(cfg.borderSize(), cfg.minRegionArea(), cfg.mergeRegionArea());
        Contours contours = new Contours(chf, cfg.maxSimplificationError(), cfg.maxEdgeCells());
        PolyMesh pm = new PolyMesh(contours, cfg.maxVertsPerPoly());
        return toTile(pm, tx, tz, tileMinX, tileMinZ, minY, cs, ch, tileCells * cs, links);
    }

    /** Blocks the spans an obstacle stands on (cylinder or box; see {@link TileCache}). */
    private static void markObstacle(CompactHeightfield chf, float[] ob, int o) {
        boolean box = ob[o] != 0;
        float minX;
        float minZ;
        float maxX;
        float maxZ;
        float y0;
        float y1;
        if (box) {
            minX = ob[o + 1];
            y0 = ob[o + 2];
            minZ = ob[o + 3];
            maxX = ob[o + 4];
            y1 = ob[o + 5];
            maxZ = ob[o + 6];
        } else {
            float r = ob[o + 4];
            minX = ob[o + 1] - r;
            maxX = ob[o + 1] + r;
            minZ = ob[o + 3] - r;
            maxZ = ob[o + 3] + r;
            y0 = ob[o + 2];
            y1 = ob[o + 2] + ob[o + 5];
        }
        int x0 = Math.max(0, (int) Math.floor((minX - chf.minX) / chf.cs));
        int x1 = Math.min(chf.width - 1, (int) Math.floor((maxX - chf.minX) / chf.cs));
        int z0 = Math.max(0, (int) Math.floor((minZ - chf.minZ) / chf.cs));
        int z1 = Math.min(chf.height - 1, (int) Math.floor((maxZ - chf.minZ) / chf.cs));
        // Spans whose floor is within a climb below the obstacle's base up to its top.
        int ymin = (int) Math.floor((y0 - chf.minY) / chf.ch) - chf.walkableClimb;
        int ymax = (int) Math.ceil((y1 - chf.minY) / chf.ch);
        float r2 = box ? 0 : ob[o + 4] * ob[o + 4];
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                float px = chf.minX + (x + 0.5f) * chf.cs;
                float pz = chf.minZ + (z + 0.5f) * chf.cs;
                if (!box) {
                    float dx = px - ob[o + 1];
                    float dz = pz - ob[o + 3];
                    if (dx * dx + dz * dz > r2) {
                        continue;
                    }
                } else if (px < minX || px > maxX || pz < minZ || pz > maxZ) {
                    continue;
                }
                int c = x + z * chf.width;
                for (int i = chf.cellIndex[c], end = i + chf.cellCount[c]; i < end; i++) {
                    if (chf.y[i] >= ymin && chf.y[i] <= ymax) {
                        chf.area[i] = Heightfield.NULL_AREA;
                    }
                }
            }
        }
    }

    private static void markVolume(CompactHeightfield chf, AreaVolume vol) {
        float[] xz = vol.xz();
        int n = xz.length / 2;
        float lx = Float.MAX_VALUE,
                lz = Float.MAX_VALUE,
                hx = -Float.MAX_VALUE,
                hz = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            lx = Math.min(lx, xz[i * 2]);
            hx = Math.max(hx, xz[i * 2]);
            lz = Math.min(lz, xz[i * 2 + 1]);
            hz = Math.max(hz, xz[i * 2 + 1]);
        }
        int x0 = Math.max(0, (int) ((lx - chf.minX) / chf.cs));
        int x1 = Math.min(chf.width - 1, (int) ((hx - chf.minX) / chf.cs));
        int z0 = Math.max(0, (int) ((lz - chf.minZ) / chf.cs));
        int z1 = Math.min(chf.height - 1, (int) ((hz - chf.minZ) / chf.cs));
        int ymin = (int) ((vol.yMin() - chf.minY) / chf.ch);
        int ymax = (int) ((vol.yMax() - chf.minY) / chf.ch);
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                float px = chf.minX + (x + 0.5f) * chf.cs;
                float pz = chf.minZ + (z + 0.5f) * chf.cs;
                if (!dev.jeddy.math.Poly2.contains(xz, n, px, pz)) {
                    continue;
                }
                int c = x + z * chf.width;
                for (int i = chf.cellIndex[c], end = i + chf.cellCount[c]; i < end; i++) {
                    if (chf.area[i] != Heightfield.NULL_AREA
                            && chf.y[i] >= ymin
                            && chf.y[i] <= ymax) {
                        chf.area[i] = vol.area();
                    }
                }
            }
        }
    }

    private static NavTile toTile(
            PolyMesh pm,
            int tx,
            int tz,
            float ox,
            float oz,
            float oy,
            float cs,
            float ch,
            float tileWorld,
            List<OffMeshLink> links) {
        List<OffMeshLink> mine = linksStartingIn(links, ox, oz, tileWorld);
        int nv = pm.nverts + mine.size() * 2;
        int np = pm.npolys + mine.size();
        if (np == 0) {
            return null;
        }
        float[] verts = new float[nv * 3];
        for (int i = 0; i < pm.nverts; i++) {
            verts[i * 3] = ox + pm.verts[i * 3] * cs;
            verts[i * 3 + 1] = oy + pm.verts[i * 3 + 1] * ch;
            verts[i * 3 + 2] = oz + pm.verts[i * 3 + 2] * cs;
        }
        int m = NavTile.MAX_VERTS;
        int[] polys = new int[np * m];
        int[] neighbours = new int[np * m];
        int[] counts = new int[np];
        int[] areas = new int[np];
        int[] types = new int[np];
        java.util.Arrays.fill(polys, -1);
        java.util.Arrays.fill(neighbours, NavTile.NO_NEIGHBOUR);
        int nvp = pm.nvp;
        for (int p = 0; p < pm.npolys; p++) {
            int src = p * nvp * 2;
            int n = 0;
            for (int k = 0; k < nvp; k++) {
                int vi = pm.polys[src + k];
                if (vi == PolyMesh.NULL_IDX) {
                    break;
                }
                polys[p * m + k] = vi;
                int nb = pm.polys[src + nvp + k];
                neighbours[p * m + k] =
                        nb == PolyMesh.NULL_IDX
                                ? NavTile.NO_NEIGHBOUR
                                : (nb & 0x8000) != 0 ? NavTile.PORTAL | (nb & 0xf) : nb;
                n++;
            }
            counts[p] = n;
            areas[p] = pm.areas[p];
        }
        int vBase = pm.nverts;
        int pBase = pm.npolys;
        for (int i = 0; i < mine.size(); i++) {
            OffMeshLink l = mine.get(i);
            int va = vBase + i * 2;
            verts[va * 3] = l.startX();
            verts[va * 3 + 1] = l.startY();
            verts[va * 3 + 2] = l.startZ();
            verts[va * 3 + 3] = l.endX();
            verts[va * 3 + 4] = l.endY();
            verts[va * 3 + 5] = l.endZ();
            int p = pBase + i;
            polys[p * m] = va;
            polys[p * m + 1] = va + 1;
            counts[p] = 2;
            areas[p] = l.area();
            types[p] = NavTile.TYPE_OFFMESH;
        }
        NavTile tile = new NavTile(tx, tz, verts, nv, polys, neighbours, counts, areas, types, np);
        for (int i = 0; i < mine.size(); i++) {
            OffMeshLink l = mine.get(i);
            tile.offMeshBidirectional[pBase + i] = l.bidirectional() ? 1 : 0;
            tile.offMeshRadius[pBase + i] = l.radius();
            tile.offMeshUserId[pBase + i] = l.userId();
        }
        return tile;
    }

    private static NavTile offMeshOnly(
            int tx, int tz, float ox, float oz, float tileWorld, List<OffMeshLink> links) {
        List<OffMeshLink> mine = linksStartingIn(links, ox, oz, tileWorld);
        int np = mine.size();
        if (np == 0) {
            return null;
        }
        int m = NavTile.MAX_VERTS;
        float[] verts = new float[np * 6];
        int[] polys = new int[np * m];
        int[] neighbours = new int[np * m];
        java.util.Arrays.fill(polys, -1);
        java.util.Arrays.fill(neighbours, NavTile.NO_NEIGHBOUR);
        int[] counts = new int[np];
        int[] areas = new int[np];
        int[] types = new int[np];
        for (int i = 0; i < np; i++) {
            OffMeshLink l = mine.get(i);
            verts[i * 6] = l.startX();
            verts[i * 6 + 1] = l.startY();
            verts[i * 6 + 2] = l.startZ();
            verts[i * 6 + 3] = l.endX();
            verts[i * 6 + 4] = l.endY();
            verts[i * 6 + 5] = l.endZ();
            polys[i * m] = i * 2;
            polys[i * m + 1] = i * 2 + 1;
            counts[i] = 2;
            areas[i] = l.area();
            types[i] = NavTile.TYPE_OFFMESH;
        }
        NavTile tile =
                new NavTile(tx, tz, verts, np * 2, polys, neighbours, counts, areas, types, np);
        for (int i = 0; i < np; i++) {
            tile.offMeshBidirectional[i] = mine.get(i).bidirectional() ? 1 : 0;
            tile.offMeshRadius[i] = mine.get(i).radius();
            tile.offMeshUserId[i] = mine.get(i).userId();
        }
        return tile;
    }

    private static List<OffMeshLink> linksStartingIn(
            List<OffMeshLink> links, float ox, float oz, float size) {
        List<OffMeshLink> mine = new ArrayList<>();
        for (OffMeshLink l : links) {
            if (l.startX() >= ox
                    && l.startX() < ox + size
                    && l.startZ() >= oz
                    && l.startZ() < oz + size) {
                mine.add(l);
            }
        }
        return mine;
    }
}
