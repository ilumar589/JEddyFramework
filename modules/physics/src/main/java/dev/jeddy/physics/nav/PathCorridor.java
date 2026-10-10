package dev.jeddy.physics.nav;

/**
 * A path corridor (DESIGN §11.4): the polygons from an agent's current polygon to its goal's, kept
 * valid as the agent moves. Moving the agent slides it along the navmesh and splices the polygons
 * it crossed into the start of the corridor; the corners to steer for come from string pulling the
 * corridor; visibility and topology optimisation shorten it locally. Following Detour's {@code
 * dtPathCorridor}.
 *
 * <p>Thread ownership: owned by one agent; called with a {@link NavMeshQuery} of the calling
 * thread. Allocates nothing after construction.
 */
public final class PathCorridor {
    private static final float MIN_TARGET_DIST = 0.01f;
    private static final int MAX_VISITED = 16;
    private static final int MAX_RAY_PATH = 32;

    private final float[] pos = new float[3];
    private final float[] target = new float[3];
    private final int[] path;
    private int count;
    private final int[] visited = new int[MAX_VISITED];
    private final int[] rayPath = new int[MAX_RAY_PATH];
    private final float[] scratch = new float[3];
    private final NavRayHit hit = new NavRayHit();

    /** Creates a corridor holding up to {@code maxPath} polygons. */
    public PathCorridor(int maxPath) {
        path = new int[maxPath];
    }

    /** Puts the corridor at {@code (x, y, z)} in polygon {@code ref}, with the target there too. */
    public void reset(int ref, float x, float y, float z) {
        pos[0] = target[0] = x;
        pos[1] = target[1] = y;
        pos[2] = target[2] = z;
        path[0] = ref;
        count = ref == NavMesh.NULL_REF ? 0 : 1;
    }

    /** Replaces the corridor with {@code polys[0..n)} leading to {@code (x, y, z)}. */
    public void setCorridor(float x, float y, float z, int[] polys, int n) {
        target[0] = x;
        target[1] = y;
        target[2] = z;
        count = Math.min(n, path.length);
        System.arraycopy(polys, 0, path, 0, count);
    }

    /** Returns the current position (x, y, z; do not modify). */
    public float[] pos() {
        return pos;
    }

    /** Returns the target position (x, y, z; do not modify). */
    public float[] target() {
        return target;
    }

    /** Returns the polygon the agent is in, or 0. */
    public int firstPoly() {
        return count == 0 ? NavMesh.NULL_REF : path[0];
    }

    /** Returns the polygon the target is in, or 0. */
    public int lastPoly() {
        return count == 0 ? NavMesh.NULL_REF : path[count - 1];
    }

    /** Returns the corridor's polygons (the first {@link #count()} are valid; do not modify). */
    public int[] path() {
        return path;
    }

    /** Returns the number of polygons in the corridor. */
    public int count() {
        return count;
    }

    /**
     * Writes the next corners to steer for (string pulled, up to and including the first off-mesh
     * link) and returns how many; corners within 1 cm of the agent are dropped.
     */
    public int findCorners(
            float[] cornerVerts,
            int[] cornerFlags,
            int[] cornerPolys,
            int maxCorners,
            NavMeshQuery q) {
        if (count == 0) {
            return 0;
        }
        int n =
                q.findStraightPath(
                        pos[0],
                        pos[1],
                        pos[2],
                        target[0],
                        target[1],
                        target[2],
                        path,
                        count,
                        cornerVerts,
                        cornerFlags,
                        cornerPolys,
                        maxCorners);
        // Drop the corners the agent is already at.
        int skip = 0;
        while (skip < n) {
            if ((cornerFlags[skip] & NavMeshQuery.STRAIGHT_OFFMESH) != 0
                    || dist2DSqr(cornerVerts, skip * 3, pos[0], pos[2])
                            > MIN_TARGET_DIST * MIN_TARGET_DIST) {
                break;
            }
            skip++;
        }
        if (skip > 0) {
            n -= skip;
            System.arraycopy(cornerVerts, skip * 3, cornerVerts, 0, n * 3);
            System.arraycopy(cornerFlags, skip, cornerFlags, 0, n);
            if (cornerPolys != null) {
                System.arraycopy(cornerPolys, skip, cornerPolys, 0, n);
            }
        }
        // Stop at the first off-mesh link: the agent traverses it before steering further.
        for (int i = 0; i < n; i++) {
            if ((cornerFlags[i] & NavMeshQuery.STRAIGHT_OFFMESH) != 0) {
                return i + 1;
            }
        }
        return n;
    }

    private static float dist2DSqr(float[] v, int o, float x, float z) {
        float dx = v[o] - x;
        float dz = v[o + 2] - z;
        return dx * dx + dz * dz;
    }

    /**
     * Shortens the start of the corridor when the agent can walk straight toward {@code next} (the
     * corner after the next one, typically): a navmesh raycast of up to {@code range} meters,
     * spliced in when it reaches its end.
     */
    public void optimizePathVisibility(
            float nextX,
            float nextY,
            float nextZ,
            float range,
            NavMeshQuery q,
            QueryFilter filter) {
        if (count == 0) {
            return;
        }
        float dx = nextX - pos[0];
        float dz = nextZ - pos[2];
        float dist = (float) Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.01f) {
            return;
        }
        dist = Math.min(dist + 0.01f, range);
        float s = range / dist;
        float gx = pos[0] + dx * s;
        float gz = pos[2] + dz * s;
        q.raycast(path[0], pos[0], pos[1], pos[2], gx, nextY, gz, filter, hit, rayPath);
        if (hit.pathCount > 1 && hit.t > 0.99f) {
            count = mergeStartShortcut(path, count, rayPath, hit.pathCount);
        }
    }

    /**
     * Re-plans the start of the corridor with a small search ({@code maxIterations} expansions)
     * toward its last polygon, splicing in any shorter route found; returns whether it did. Uses
     * the query's sliced search, so do not interleave it with another sliced search on {@code q}.
     */
    public boolean optimizePathTopology(NavMeshQuery q, QueryFilter filter, int maxIterations) {
        if (count < 3) {
            return false;
        }
        q.initSlicedFindPath(
                path[0],
                path[count - 1],
                pos[0],
                pos[1],
                pos[2],
                target[0],
                target[1],
                target[2],
                filter);
        q.updateSlicedFindPath(maxIterations);
        int n = q.finalizeSlicedFindPathPartial(path, count, rayPath);
        if ((q.lastStatus() & NavMeshQuery.SUCCESS) != 0 && n > 0) {
            count = mergeStartShortcut(path, count, rayPath, n);
            return true;
        }
        return false;
    }

    /**
     * Moves the agent toward {@code (x, y, z)} along the navmesh (sliding on walls), splicing the
     * crossed polygons into the corridor. Returns {@code false} when the corridor is empty or its
     * first polygon is gone.
     */
    public boolean movePosition(float x, float y, float z, NavMeshQuery q, QueryFilter filter) {
        if (count == 0) {
            return false;
        }
        int n =
                q.moveAlongSurface(
                        path[0], pos[0], pos[1], pos[2], x, y, z, filter, scratch, visited);
        if ((q.lastStatus() & NavMeshQuery.SUCCESS) == 0) {
            return false;
        }
        count = mergeStartMoved(path, count, path.length, visited, n);
        float h = q.polyHeight(path[0], scratch[0], scratch[2]);
        pos[0] = scratch[0];
        pos[1] = h == h ? h : scratch[1];
        pos[2] = scratch[2];
        return true;
    }

    /**
     * Moves the target toward {@code (x, y, z)} along the navmesh, extending the corridor's end.
     */
    public boolean moveTargetPosition(
            float x, float y, float z, NavMeshQuery q, QueryFilter filter) {
        if (count == 0) {
            return false;
        }
        int n =
                q.moveAlongSurface(
                        path[count - 1],
                        target[0],
                        target[1],
                        target[2],
                        x,
                        y,
                        z,
                        filter,
                        scratch,
                        visited);
        if ((q.lastStatus() & NavMeshQuery.SUCCESS) == 0) {
            return false;
        }
        count = mergeEndMoved(path, count, path.length, visited, n);
        target[0] = scratch[0];
        target[1] = scratch[1];
        target[2] = scratch[2];
        return true;
    }

    /**
     * Advances the corridor over the off-mesh link {@code offMeshRef} (which must be in the
     * corridor): drops the polygons up to it and writes the polygon before it and the link to
     * {@code refs}, the link's entry point to {@code start} and exit point to {@code end}. The
     * agent's position becomes the exit point. Returns {@code false} if the link is not in the
     * corridor.
     */
    public boolean moveOverOffMeshLink(
            int offMeshRef, int[] refs, float[] start, float[] end, NavMesh mesh) {
        int prevRef = NavMesh.NULL_REF;
        int polyRef = path[0];
        int npos = 0;
        while (npos < count && polyRef != offMeshRef) {
            prevRef = polyRef;
            polyRef = path[npos];
            npos++;
        }
        if (npos == count || polyRef != offMeshRef) {
            return false;
        }
        // The corridor continues from the polygon the link lands in.
        System.arraycopy(path, npos, path, 0, count - npos);
        count -= npos;
        refs[0] = prevRef;
        refs[1] = polyRef;
        NavTile t = mesh.tile(offMeshRef);
        int p = mesh.poly(offMeshRef);
        // Enter at the end attached to the previous polygon.
        int enter = 0;
        for (int l = mesh.firstLink(offMeshRef); l >= 0; l = mesh.nextLink(offMeshRef, l)) {
            if (mesh.linkTarget(offMeshRef, l) == prevRef) {
                enter = mesh.linkEdge(offMeshRef, l);
                break;
            }
        }
        int a = t.vertex(p, enter);
        int b = t.vertex(p, 1 - enter);
        for (int k = 0; k < 3; k++) {
            start[k] = t.verts[a + k];
            end[k] = t.verts[b + k];
            pos[k] = end[k];
        }
        return true;
    }

    /**
     * Returns whether the first {@code maxLookAhead} polygons of the corridor are loaded and pass
     * {@code filter}.
     */
    public boolean isValid(int maxLookAhead, NavMesh mesh, QueryFilter filter) {
        int n = Math.min(count, maxLookAhead);
        for (int i = 0; i < n; i++) {
            if (!mesh.isValid(path[i]) || !filter.passes(mesh, path[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Cuts the corridor at its first invalid polygon; if the first polygon itself is invalid, the
     * corridor restarts at {@code safeRef} and {@code (x, y, z)}. The target moves to the end of
     * what remains.
     */
    public void trimInvalidPath(
            int safeRef, float x, float y, float z, NavMeshQuery q, QueryFilter filter) {
        NavMesh mesh = q.mesh();
        int n = 0;
        while (n < count && mesh.isValid(path[n]) && filter.passes(mesh, path[n])) {
            n++;
        }
        if (n == 0) {
            pos[0] = x;
            pos[1] = y;
            pos[2] = z;
            path[0] = safeRef;
            n = 1;
        } else if (n < count) {
            // Clamp the target to the last valid polygon.
            q.closestPointOnPoly(path[n - 1], target[0], target[1], target[2], scratch);
            target[0] = scratch[0];
            target[1] = scratch[1];
            target[2] = scratch[2];
        }
        count = n;
    }

    /** Replaces the corridor's first polygon (agent pushed elsewhere) with {@code safeRef}. */
    public void fixPathStart(int safeRef, float x, float y, float z) {
        pos[0] = x;
        pos[1] = y;
        pos[2] = z;
        if (count < 3 && count > 0) {
            path[2] = path[count - 1];
            path[0] = safeRef;
            path[1] = NavMesh.NULL_REF;
            count = 3;
        } else {
            path[0] = safeRef;
            if (count == 0) {
                count = 1;
            }
        }
    }

    // Splicing (Detour's dtMergeCorridor*) -------------------------------------------------------

    /** Splices polygons visited while moving (start first) into the corridor's start. */
    static int mergeStartMoved(int[] path, int npath, int maxPath, int[] visited, int nvisited) {
        int furthestPath = -1;
        int furthestVisited = -1;
        for (int i = npath - 1; i >= 0 && furthestPath < 0; i--) {
            for (int j = nvisited - 1; j >= 0; j--) {
                if (path[i] == visited[j]) {
                    furthestPath = i;
                    furthestVisited = j;
                }
            }
        }
        if (furthestPath < 0) {
            return npath;
        }
        int req = nvisited - furthestVisited;
        int orig = Math.min(furthestPath + 1, npath);
        int size = Math.max(0, npath - orig);
        if (req + size > maxPath) {
            size = maxPath - req;
        }
        if (size > 0) {
            System.arraycopy(path, orig, path, req, size);
        }
        for (int i = 0; i < req; i++) {
            path[i] = visited[nvisited - 1 - i];
        }
        return req + size;
    }

    /** Splices polygons visited while moving the target into the corridor's end. */
    static int mergeEndMoved(int[] path, int npath, int maxPath, int[] visited, int nvisited) {
        int furthestPath = -1;
        int furthestVisited = -1;
        for (int i = 0; i < npath && furthestPath < 0; i++) {
            for (int j = nvisited - 1; j >= 0; j--) {
                if (path[i] == visited[j]) {
                    furthestPath = i;
                    furthestVisited = j;
                }
            }
        }
        if (furthestPath < 0) {
            return npath;
        }
        int ppos = furthestPath + 1;
        int vpos = furthestVisited + 1;
        int n = Math.min(nvisited - vpos, maxPath - ppos);
        if (n > 0) {
            System.arraycopy(visited, vpos, path, ppos, n);
        }
        return ppos + n;
    }

    /** Splices a shortcut (polygons from the corridor's start) over the corridor's start. */
    static int mergeStartShortcut(int[] path, int npath, int[] visited, int nvisited) {
        int maxPath = path.length;
        int furthestPath = -1;
        int furthestVisited = -1;
        for (int i = npath - 1; i >= 0 && furthestPath < 0; i--) {
            for (int j = nvisited - 1; j >= 0; j--) {
                if (path[i] == visited[j]) {
                    furthestPath = i;
                    furthestVisited = j;
                }
            }
        }
        if (furthestPath < 0) {
            return npath;
        }
        int req = furthestVisited;
        if (req <= 0) {
            return npath;
        }
        int orig = furthestPath;
        int size = Math.max(0, npath - orig);
        if (req + size > maxPath) {
            size = maxPath - req;
        }
        if (size > 0) {
            System.arraycopy(path, orig, path, req, size);
        }
        System.arraycopy(visited, 0, path, 0, req);
        return req + size;
    }
}
