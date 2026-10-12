package dev.jeddy.physics.crowd;

import dev.jeddy.core.collections.IntList;
import dev.jeddy.core.io.ByteReader;
import dev.jeddy.core.io.ByteWriter;
import dev.jeddy.core.job.JobKernel;
import dev.jeddy.core.job.JobSystem;
import dev.jeddy.core.pool.PerWorker;
import dev.jeddy.physics.debug.DebugSink;
import dev.jeddy.physics.grid.Bodies;
import dev.jeddy.physics.grid.UniformGrid;
import dev.jeddy.physics.nav.NavMesh;
import dev.jeddy.physics.nav.NavMeshQuery;
import dev.jeddy.physics.nav.NavPathBatch;
import dev.jeddy.physics.nav.PathCorridor;
import dev.jeddy.physics.nav.QueryFilter;

/**
 * Crowd simulation for navmesh agents (DESIGN §11.4): path following through {@link PathCorridor}s,
 * steering toward the string-pulled corners (with turn anticipation, slow-down and arrival),
 * separation, ORCA local avoidance with neighbours from a {@link UniformGrid}, collision
 * resolution, off-mesh link traversal, and stuck detection with repaths. Path requests are
 * throttled per tick and solved in parallel ({@link NavPathBatch}); the per-agent phases run as
 * {@code parallelFor} kernels that only read other agents' previous state and write their own, so
 * the result is the same for any number of workers.
 *
 * <p>Agents are SoA slots addressed by {@code int} index (stable until removed). The structure
 * follows Detour's {@code dtCrowd}, with ORCA in place of its sampled velocity planner.
 *
 * <p>Thread ownership: the game thread (it borrows job workers inside {@link #update}). Allocates
 * nothing per tick once agents are added.
 */
public final class Crowd {
    /** Agent state: not on the navmesh. */
    public static final int STATE_INVALID = 0;

    /** Agent state: walking on the navmesh. */
    public static final int STATE_WALKING = 1;

    /** Agent state: traversing an off-mesh link (jump, ladder). */
    public static final int STATE_OFFMESH = 2;

    /** Move target: none (standing). */
    public static final int TARGET_NONE = 0;

    /** Move target: no path, or the agent gave up after repeated repaths. */
    public static final int TARGET_FAILED = 1;

    /** Move target: following a path. */
    public static final int TARGET_VALID = 2;

    /** Move target: waiting for a path. */
    public static final int TARGET_REQUESTING = 3;

    /** Move target: moving at a requested velocity. */
    public static final int TARGET_VELOCITY = 4;

    /** Move target: arrived (the target, or the nearest reachable point if it was partial). */
    public static final int TARGET_ARRIVED = 5;

    private static final int MAX_NEIS = 8;
    private static final int MAX_CORNERS = 4;
    private static final int MAX_PATH = 256;
    private static final int CHECK_LOOKAHEAD = 10;
    private static final float TOPOLOGY_INTERVAL = 0.5f;
    private static final int MAX_TOPOLOGY_PER_TICK = 4;
    private static final int COLLISION_ITERATIONS = 4;
    private static final float COLLISION_RESOLVE = 0.7f;
    private static final int GRAIN = 16;

    private final NavMesh mesh;
    private final int capacity;
    private final JobSystem jobs;
    private final QueryFilter[] filters = new QueryFilter[16];
    private final float[] extents = {2, 4, 2};
    private final Bodies bodies;
    private final UniformGrid grid;
    private final NavPathBatch batch;
    private final int[] batchAgent;
    private final PerWorker<Scratch> scratch;
    private int maxRequestsPerTick = 16;
    private float timeHorizon = 1.5f;
    private float stuckSeconds = 1.5f;
    private int maxRepaths = 5;

    // Agent SoA.
    private final boolean[] active;
    private final int[] state;
    private final int[] targetState;
    private final PathCorridor[] corridors;
    private final float[] px;
    private final float[] py;
    private final float[] pz;
    private final float[] prevX;
    private final float[] prevZ;
    private final float[] vx;
    private final float[] vz;
    private final float[] dvx;
    private final float[] dvz;
    private final float[] nvx;
    private final float[] nvz;
    private final float[] dispX;
    private final float[] dispZ;
    private final float[] radius;
    private final float[] height;
    private final float[] maxSpeed;
    private final float[] maxAccel;
    private final float[] queryRange;
    private final float[] optRange;
    private final float[] sepWeight;
    private final float[] arrival;
    private final int[] flags;
    private final int[] filterIndex;
    private final int[] targetRef;
    private final float[] tx;
    private final float[] ty;
    private final float[] tz;
    private final boolean[] partial;
    private final boolean[] outOfNodes;
    private final float[] reqVx;
    private final float[] reqVz;
    private final int[] neiCount;
    private final int[] nei;
    private final float[] neiDist;
    private final int[] cornerCount;
    private final float[] corners;
    private final int[] cornerFlags;
    private final int[] cornerPolys;
    private final float[] topologyTime;
    private final float[] stuckTime;
    private final int[] repaths;
    private final float[] animT;
    private final float[] animMax;
    private final float[] animPos; // init xyz, start xyz, end xyz per agent
    private final int[] animRef;
    private final boolean[] queued;
    private final int[] queue;
    private int queueHead;
    private int queueCount;
    private final int[] activeList;
    private int activeCount;
    private final int[] topo = new int[MAX_TOPOLOGY_PER_TICK];
    private final int[] scratchPath = new int[MAX_PATH];
    private float dt;

    private final JobKernel steerKernel = this::steerRange;
    private final JobKernel orcaKernel = this::orcaRange;
    private final JobKernel integrateKernel = this::integrateRange;
    private final JobKernel collideKernel = this::collideRange;
    private final JobKernel applyKernel = this::applyRange;
    private final JobKernel moveKernel = this::moveRange;

    private static final class Scratch {
        final NavMeshQuery query;
        final Orca orca = new Orca(MAX_NEIS);
        final IntList candidates;
        final float[] p = new float[3];
        final float[] a = new float[3];
        final float[] b = new float[3];
        final int[] refs = new int[2];
        final float[] corners = new float[MAX_CORNERS * 3];
        final int[] cornerFlags = new int[MAX_CORNERS];
        final int[] cornerPolys = new int[MAX_CORNERS];

        Scratch(NavMesh mesh, int maxAgents) {
            query = new NavMeshQuery(mesh, 1024);
            // A neighbour query returns each body at most once: sized for all of them, a dense
            // crowd never grows the list mid-game.
            candidates = new IntList(Math.max(64, maxAgents));
        }
    }

    /**
     * Creates a crowd of up to {@code maxAgents} on {@code mesh}, with a neighbour grid over {@code
     * [minX, minX + sizeX) × [minZ, minZ + sizeZ)}; {@code jobs} may be {@code null}.
     */
    public Crowd(
            NavMesh mesh,
            int maxAgents,
            float minX,
            float minZ,
            float sizeX,
            float sizeZ,
            JobSystem jobs) {
        this.mesh = mesh;
        this.capacity = maxAgents;
        this.jobs = jobs;
        filters[0] = new QueryFilter();
        bodies = new Bodies(maxAgents);
        grid = new UniformGrid(minX, minZ, sizeX, sizeZ, 2f, jobs);
        int slots = jobs == null ? 1 : jobs.threadSlots();
        batch = new NavPathBatch(mesh, 32, MAX_PATH, 4, 4096, slots);
        batchAgent = new int[maxAgents];
        scratch = new PerWorker<>(slots, i -> new Scratch(mesh, maxAgents));
        int n = maxAgents;
        active = new boolean[n];
        state = new int[n];
        targetState = new int[n];
        corridors = new PathCorridor[n];
        for (int i = 0; i < n; i++) {
            corridors[i] = new PathCorridor(MAX_PATH);
        }
        px = new float[n];
        py = new float[n];
        pz = new float[n];
        prevX = new float[n];
        prevZ = new float[n];
        vx = new float[n];
        vz = new float[n];
        dvx = new float[n];
        dvz = new float[n];
        nvx = new float[n];
        nvz = new float[n];
        dispX = new float[n];
        dispZ = new float[n];
        radius = new float[n];
        height = new float[n];
        maxSpeed = new float[n];
        maxAccel = new float[n];
        queryRange = new float[n];
        optRange = new float[n];
        sepWeight = new float[n];
        arrival = new float[n];
        flags = new int[n];
        filterIndex = new int[n];
        targetRef = new int[n];
        tx = new float[n];
        ty = new float[n];
        tz = new float[n];
        partial = new boolean[n];
        outOfNodes = new boolean[n];
        reqVx = new float[n];
        reqVz = new float[n];
        neiCount = new int[n];
        nei = new int[n * MAX_NEIS];
        neiDist = new float[n * MAX_NEIS];
        cornerCount = new int[n];
        corners = new float[n * MAX_CORNERS * 3];
        cornerFlags = new int[n * MAX_CORNERS];
        cornerPolys = new int[n * MAX_CORNERS];
        topologyTime = new float[n];
        stuckTime = new float[n];
        repaths = new int[n];
        animT = new float[n];
        animMax = new float[n];
        animPos = new float[n * 9];
        animRef = new int[n];
        queued = new boolean[n];
        queue = new int[n];
        activeList = new int[n];
    }

    // Configuration ------------------------------------------------------------------------------

    /** Returns query filter {@code i} (0..15; 0 exists, others are created on first use). */
    public QueryFilter filter(int i) {
        if (filters[i] == null) {
            filters[i] = new QueryFilter();
        }
        return filters[i];
    }

    /** Sets how many path requests are solved per tick (the rest wait). */
    public void setMaxRequestsPerTick(int n) {
        maxRequestsPerTick = Math.max(1, n);
    }

    /** Sets the ORCA time horizon in seconds (how early agents start to avoid each other). */
    public void setTimeHorizon(float seconds) {
        timeHorizon = seconds;
    }

    /**
     * Sets stuck detection: an agent that barely moves while trying to for {@code seconds} repaths;
     * after {@code maxRepaths} repaths without arriving its target fails.
     */
    public void setStuckPolicy(float seconds, int repathLimit) {
        stuckSeconds = seconds;
        maxRepaths = repathLimit;
    }

    /** Half extents of the search for the polygons under agents and targets. */
    public void setQueryExtents(float hx, float hy, float hz) {
        extents[0] = hx;
        extents[1] = hy;
        extents[2] = hz;
    }

    // Agents -------------------------------------------------------------------------------------

    /**
     * Adds an agent at the navmesh point nearest to {@code (x, y, z)} and returns its index, or -1
     * when the crowd is full. An agent off the navmesh starts {@link #STATE_INVALID}.
     */
    public int addAgent(float x, float y, float z, AgentParams p) {
        int i = -1;
        for (int k = 0; k < capacity; k++) {
            if (!active[k]) {
                i = k;
                break;
            }
        }
        if (i < 0) {
            return -1;
        }
        setParams(i, p);
        place(i, x, y, z);
        return i;
    }

    /** Activates slot {@code i} at the navmesh point nearest {@code (x, y, z)}; params are set. */
    private void place(int i, float x, float y, float z) {
        active[i] = true;
        Scratch s = scratch.get(0);
        int ref =
                s.query.findNearestPoly(
                        x, y, z, extents[0], extents[1], extents[2], filters[filterIndex[i]], s.p);
        if (ref != NavMesh.NULL_REF) {
            x = s.p[0];
            y = s.p[1];
            z = s.p[2];
        }
        corridors[i].reset(ref, x, y, z);
        px[i] = prevX[i] = x;
        py[i] = y;
        pz[i] = prevZ[i] = z;
        vx[i] = vz[i] = dvx[i] = dvz[i] = nvx[i] = nvz[i] = 0;
        state[i] = ref != NavMesh.NULL_REF ? STATE_WALKING : STATE_INVALID;
        targetState[i] = TARGET_NONE;
        neiCount[i] = 0;
        cornerCount[i] = 0;
        queued[i] = false;
        bodies.addCylinder(i, x, y, z, radius[i], height[i], 1);
    }

    /** Copies new settings to agent {@code i}. */
    public void setParams(int i, AgentParams p) {
        radius[i] = p.radius;
        height[i] = p.height;
        maxSpeed[i] = p.maxSpeed;
        maxAccel[i] = p.maxAcceleration;
        queryRange[i] = p.collisionQueryRange;
        optRange[i] = p.pathOptimizationRange;
        sepWeight[i] = p.separationWeight;
        arrival[i] = p.arrivalDistance;
        flags[i] = p.flags;
        filterIndex[i] = p.filter;
        filter(p.filter);
        int b = bodies.indexOf(i);
        if (b >= 0) {
            bodies.radius[b] = p.radius;
            bodies.extentX[b] = p.radius;
            bodies.extentZ[b] = p.radius;
            bodies.height[b] = p.height;
        }
    }

    /** Returns agent {@code i}'s top speed. */
    public float maxSpeed(int i) {
        return maxSpeed[i];
    }

    /** Changes agent {@code i}'s top speed (formation pacing, haste, slow effects). */
    public void setMaxSpeed(int i, float speed) {
        maxSpeed[i] = speed;
    }

    /** Returns agent {@code i}'s radius. */
    public float radius(int i) {
        return radius[i];
    }

    /** Removes agent {@code i}. */
    public void removeAgent(int i) {
        if (!active[i]) {
            return;
        }
        active[i] = false;
        bodies.remove(bodies.indexOf(i));
    }

    /**
     * Asks agent {@code i} to go to the navmesh point nearest {@code (x, y, z)}; the path is solved
     * within the next ticks. Returns {@code false} when no navmesh is near the point.
     */
    public boolean requestMoveTarget(int i, float x, float y, float z) {
        Scratch s = scratch.get(0);
        int ref =
                s.query.findNearestPoly(
                        x, y, z, extents[0], extents[1], extents[2], filters[filterIndex[i]], s.p);
        if (ref == NavMesh.NULL_REF) {
            return false;
        }
        targetRef[i] = ref;
        tx[i] = s.p[0];
        ty[i] = s.p[1];
        tz[i] = s.p[2];
        repaths[i] = 0;
        stuckTime[i] = 0;
        request(i);
        return true;
    }

    private void request(int i) {
        targetState[i] = TARGET_REQUESTING;
        if (!queued[i]) {
            queued[i] = true;
            queue[(queueHead + queueCount) % capacity] = i;
            queueCount++;
        }
    }

    /**
     * Makes agent {@code i} move at velocity {@code (vx, vz)} (direct control), sliding on walls.
     */
    public void requestMoveVelocity(int i, float velX, float velZ) {
        reqVx[i] = velX;
        reqVz[i] = velZ;
        targetState[i] = TARGET_VELOCITY;
        corridors[i].reset(corridors[i].firstPoly(), px[i], py[i], pz[i]);
    }

    /** Stops agent {@code i} (it keeps avoiding others). */
    public void resetMoveTarget(int i) {
        targetState[i] = TARGET_NONE;
        corridors[i].reset(corridors[i].firstPoly(), px[i], py[i], pz[i]);
    }

    // ---- Saves (DESIGN §21) -------------------------------------------------------------------

    /** The layout {@link #write} uses; {@link #read} keeps every older one readable (D117). */
    public static final int SAVE_FORMAT = 1;

    /**
     * Writes every active agent in its slot: position, velocity, parameters and the move target
     * (saves). The corridor is not written: {@link #read} re-requests the target and the path is
     * solved again. Allocates nothing.
     */
    public void write(ByteWriter w) {
        w.int32(SAVE_FORMAT);
        int n = 0;
        for (int i = 0; i < capacity; i++) {
            if (active[i]) {
                n++;
            }
        }
        w.int32(capacity);
        w.int32(n);
        for (int i = 0; i < capacity; i++) {
            if (!active[i]) {
                continue;
            }
            w.int32(i);
            w.float32(px[i]);
            w.float32(py[i]);
            w.float32(pz[i]);
            w.float32(vx[i]);
            w.float32(vz[i]);
            w.float32(radius[i]);
            w.float32(height[i]);
            w.float32(maxSpeed[i]);
            w.float32(maxAccel[i]);
            w.float32(queryRange[i]);
            w.float32(optRange[i]);
            w.float32(sepWeight[i]);
            w.float32(arrival[i]);
            w.int32(flags[i]);
            w.int32(filterIndex[i]);
            w.int32(targetState[i]);
            w.float32(tx[i]);
            w.float32(ty[i]);
            w.float32(tz[i]);
            w.float32(reqVx[i]);
            w.float32(reqVz[i]);
        }
    }

    /**
     * Reads what {@link #write} wrote: every current agent is removed, the saved ones are placed in
     * their slots (so the indices the game holds stay valid), their targets are requested again and
     * the paths solved at once. A slot past this crowd's capacity is skipped. Allocates nothing.
     */
    public void read(ByteReader r) {
        int format = r.int32();
        if (format != 1) {
            throw new IllegalStateException("crowd save format " + format + "; this build reads 1");
        }
        for (int i = 0; i < capacity; i++) {
            removeAgent(i);
            queued[i] = false;
        }
        queueHead = 0; // pending requests belonged to the agents just removed
        queueCount = 0;
        r.int32(); // the saved capacity, informative
        int n = r.int32();
        for (int k = 0; k < n; k++) {
            int i = r.int32();
            float x = r.float32();
            float y = r.float32();
            float z = r.float32();
            float velX = r.float32();
            float velZ = r.float32();
            boolean fits = i >= 0 && i < capacity;
            if (fits) {
                radius[i] = r.float32();
                height[i] = r.float32();
                maxSpeed[i] = r.float32();
                maxAccel[i] = r.float32();
                queryRange[i] = r.float32();
                optRange[i] = r.float32();
                sepWeight[i] = r.float32();
                arrival[i] = r.float32();
                flags[i] = r.int32();
                filterIndex[i] = Math.clamp(r.int32(), 0, filters.length - 1);
                filter(filterIndex[i]);
                place(i, x, y, z);
                // The saved position was on the mesh already: keep its exact bits rather than
                // the re-projection's (a few ulps off in y), so that a save made right after a
                // load is the same bytes. The corridor starts there too.
                px[i] = prevX[i] = x;
                py[i] = y;
                pz[i] = prevZ[i] = z;
                corridors[i].reset(corridors[i].firstPoly(), x, y, z);
                vx[i] = velX;
                vz[i] = velZ;
            } else {
                r.skip(8 * 4 + 2 * 4);
            }
            int target = r.int32();
            float targetX = r.float32();
            float targetY = r.float32();
            float targetZ = r.float32();
            float requestedVx = r.float32();
            float requestedVz = r.float32();
            if (!fits) {
                continue;
            }
            switch (target) {
                case TARGET_VALID, TARGET_REQUESTING ->
                        requestMoveTarget(i, targetX, targetY, targetZ);
                case TARGET_VELOCITY -> requestMoveVelocity(i, requestedVx, requestedVz);
                case TARGET_ARRIVED, TARGET_FAILED -> targetState[i] = target; // nothing to walk
                default -> {}
            }
            // The saved target was the snapped point and may be stale (a velocity target, none):
            // keep its exact bits for the same reason as the position.
            tx[i] = targetX;
            ty[i] = targetY;
            tz[i] = targetZ;
            reqVx[i] = requestedVx;
            reqVz[i] = requestedVz;
        }
        // Solve every path now rather than over the next ticks: the loaded agents walk at once,
        // and their target states are what the save had.
        while (queueCount > 0) {
            solveRequests();
        }
    }

    /** Returns whether slot {@code i} holds an agent. */
    public boolean isActive(int i) {
        return active[i];
    }

    /** Returns the agent slot count. */
    public int capacity() {
        return capacity;
    }

    /** Returns agent {@code i}'s position. */
    public float x(int i) {
        return px[i];
    }

    /** Returns agent {@code i}'s position. */
    public float y(int i) {
        return py[i];
    }

    /** Returns agent {@code i}'s position. */
    public float z(int i) {
        return pz[i];
    }

    /** Returns agent {@code i}'s velocity. */
    public float velocityX(int i) {
        return vx[i];
    }

    /** Returns agent {@code i}'s velocity. */
    public float velocityZ(int i) {
        return vz[i];
    }

    /** Returns agent {@code i}'s state ({@code STATE_*}). */
    public int state(int i) {
        return state[i];
    }

    /** Returns agent {@code i}'s move target state ({@code TARGET_*}). */
    public int targetState(int i) {
        return targetState[i];
    }

    /** Returns whether agent {@code i}'s path ends short of its target (unreachable). */
    public boolean isPartial(int i) {
        return partial[i];
    }

    /** Returns agent {@code i}'s corridor. */
    public PathCorridor corridor(int i) {
        return corridors[i];
    }

    /** Returns the game id of the off-mesh link agent {@code i} is traversing, or 0. */
    public int offMeshUserId(int i) {
        if (state[i] != STATE_OFFMESH || !mesh.isValid(animRef[i])) {
            return 0;
        }
        return mesh.tile(animRef[i]).offMeshUserId[mesh.poly(animRef[i])];
    }

    /** Returns how far agent {@code i} is through its off-mesh link (0..1). */
    public float offMeshProgress(int i) {
        return state[i] == STATE_OFFMESH ? Math.min(1f, animT[i] / animMax[i]) : 0f;
    }

    /** Returns the number of corners agent {@code i} steers for (debug drawing). */
    public int cornerCount(int i) {
        return cornerCount[i];
    }

    /** Returns corner {@code k} of agent {@code i}, coordinate {@code c} (0..2). */
    public float corner(int i, int k, int c) {
        return corners[(i * MAX_CORNERS + k) * 3 + c];
    }

    /** Returns the agent bodies (positions synced each tick; entity id = agent index). */
    public Bodies bodies() {
        return bodies;
    }

    // Update -------------------------------------------------------------------------------------

    /** Advances the crowd by {@code seconds}. */
    public void update(float seconds) {
        dt = seconds;
        activeCount = 0;
        for (int i = 0; i < capacity; i++) {
            if (active[i]) {
                activeList[activeCount++] = i;
                prevX[i] = px[i];
                prevZ[i] = pz[i];
            }
        }
        checkPathValidity();
        solveRequests();
        optimizeTopology();
        // Neighbours come from the grid of last tick's (synced) positions.
        grid.rebuild(bodies);
        run(steerKernel);
        run(orcaKernel);
        run(integrateKernel);
        for (int it = 0; it < COLLISION_ITERATIONS; it++) {
            run(collideKernel);
            run(applyKernel);
        }
        run(moveKernel);
        detectStuck();
        for (int k = 0; k < activeCount; k++) {
            int i = activeList[k];
            bodies.setPosition(bodies.indexOf(i), px[i], py[i], pz[i]);
        }
    }

    private void run(JobKernel k) {
        if (jobs != null && activeCount > GRAIN) {
            jobs.parallelFor(k, activeCount, GRAIN);
        } else {
            k.run(0, activeCount, 0);
        }
    }

    private void checkPathValidity() {
        Scratch s = scratch.get(0);
        NavMeshQuery q = s.query;
        for (int k = 0; k < activeCount; k++) {
            int i = activeList[k];
            if (state[i] != STATE_WALKING) {
                continue;
            }
            QueryFilter f = filters[filterIndex[i]];
            PathCorridor c = corridors[i];
            boolean replan = false;
            int ref = c.firstPoly();
            if (!mesh.isValid(ref) || !f.passes(mesh, ref)) {
                // The ground went away (tile unloaded, door closed): snap to the nearest polygon.
                ref =
                        q.findNearestPoly(
                                px[i], py[i], pz[i], extents[0], extents[1], extents[2], f, s.p);
                if (ref == NavMesh.NULL_REF) {
                    c.reset(NavMesh.NULL_REF, px[i], py[i], pz[i]);
                    state[i] = STATE_INVALID;
                    targetState[i] = TARGET_NONE;
                    continue;
                }
                c.fixPathStart(ref, s.p[0], s.p[1], s.p[2]);
                px[i] = s.p[0];
                py[i] = s.p[1];
                pz[i] = s.p[2];
                replan = true;
            }
            int ts = targetState[i];
            if (ts != TARGET_VALID && ts != TARGET_REQUESTING) {
                continue;
            }
            if (!mesh.isValid(targetRef[i]) || !f.passes(mesh, targetRef[i])) {
                int t =
                        q.findNearestPoly(
                                tx[i], ty[i], tz[i], extents[0], extents[1], extents[2], f, s.p);
                if (t == NavMesh.NULL_REF) {
                    c.reset(ref, px[i], py[i], pz[i]);
                    targetState[i] = TARGET_NONE;
                    continue;
                }
                targetRef[i] = t;
                tx[i] = s.p[0];
                ty[i] = s.p[1];
                tz[i] = s.p[2];
                replan = true;
            }
            if (ts == TARGET_VALID) {
                if (!c.isValid(CHECK_LOOKAHEAD, mesh, f)) {
                    c.trimInvalidPath(ref, px[i], py[i], pz[i], q, f);
                    replan = true;
                }
                // A search that ran out of nodes stopped short: plan the rest when near its end.
                if (outOfNodes[i] && c.count() < CHECK_LOOKAHEAD && c.lastPoly() != targetRef[i]) {
                    replan = true;
                }
            }
            if (replan) {
                request(i);
            }
        }
    }

    private void solveRequests() {
        batch.clear();
        int n = 0;
        while (queueCount > 0 && n < maxRequestsPerTick) {
            int i = queue[queueHead];
            queueHead = (queueHead + 1) % capacity;
            queueCount--;
            queued[i] = false;
            if (!active[i] || targetState[i] != TARGET_REQUESTING || state[i] == STATE_INVALID) {
                continue;
            }
            PathCorridor c = corridors[i];
            float[] p = c.pos();
            int b = batch.add(c.firstPoly(), p[0], p[1], p[2], targetRef[i], tx[i], ty[i], tz[i]);
            batch.filterIndex[b] = filterIndex[i];
            batchAgent[n++] = i;
        }
        if (n == 0) {
            return;
        }
        batch.run(filters, jobs);
        Scratch s = scratch.get(0);
        for (int b = 0; b < n; b++) {
            int i = batchAgent[b];
            int st = batch.status[b];
            int count = batch.polyCount[b];
            PathCorridor c = corridors[i];
            if ((st & NavMeshQuery.SUCCESS) == 0 || count == 0) {
                targetState[i] = TARGET_FAILED;
                c.reset(c.firstPoly(), px[i], py[i], pz[i]);
                continue;
            }
            int[] polys = batch.polys;
            int o = b * batch.maxPath();
            float gx = tx[i];
            float gy = ty[i];
            float gz = tz[i];
            boolean isPartial = (st & NavMeshQuery.PARTIAL) != 0;
            if (isPartial) {
                // Head for the reachable point nearest the target.
                s.query.closestPointOnPoly(polys[o + count - 1], gx, gy, gz, s.p);
                gx = s.p[0];
                gy = s.p[1];
                gz = s.p[2];
            }
            System.arraycopy(polys, o, scratchPath, 0, count);
            c.setCorridor(gx, gy, gz, scratchPath, count);
            partial[i] = isPartial;
            outOfNodes[i] = (st & NavMeshQuery.OUT_OF_NODES) != 0;
            targetState[i] = TARGET_VALID;
            topologyTime[i] = 0;
            stuckTime[i] = 0;
        }
    }

    private void optimizeTopology() {
        int n = 0;
        for (int k = 0; k < activeCount; k++) {
            int i = activeList[k];
            if (targetState[i] != TARGET_VALID || state[i] != STATE_WALKING) {
                continue;
            }
            topologyTime[i] += dt;
            if ((flags[i] & AgentParams.OPTIMIZE_TOPOLOGY) == 0
                    || topologyTime[i] < TOPOLOGY_INTERVAL) {
                continue;
            }
            // Keep the agents that waited longest (a tiny list sorted by waiting time).
            int at;
            if (n < MAX_TOPOLOGY_PER_TICK) {
                at = n++;
            } else if (topologyTime[i] > topologyTime[topo[MAX_TOPOLOGY_PER_TICK - 1]]) {
                at = MAX_TOPOLOGY_PER_TICK - 1;
            } else {
                continue;
            }
            topo[at] = i;
            while (at > 0 && topologyTime[topo[at - 1]] < topologyTime[topo[at]]) {
                int t = topo[at - 1];
                topo[at - 1] = topo[at];
                topo[at] = t;
                at--;
            }
        }
        NavMeshQuery q = scratch.get(0).query;
        for (int k = 0; k < n; k++) {
            int i = topo[k];
            corridors[i].optimizePathTopology(q, filters[filterIndex[i]], 32);
            topologyTime[i] = 0;
        }
    }

    // Per-agent kernels (read others' state from before the phase, write only their own).

    private void steerRange(int from, int to, int thread) {
        Scratch s = scratch.get(thread);
        for (int k = from; k < to; k++) {
            steer(activeList[k], s);
        }
    }

    private void steer(int i, Scratch s) {
        dvx[i] = 0;
        dvz[i] = 0;
        if (state[i] != STATE_WALKING) {
            neiCount[i] = 0;
            cornerCount[i] = 0;
            return;
        }
        findNeighbours(i, s);
        PathCorridor c = corridors[i];
        QueryFilter f = filters[filterIndex[i]];
        int cb = i * MAX_CORNERS;
        int n = 0;
        if (targetState[i] == TARGET_VALID) {
            n = c.findCorners(s.corners, s.cornerFlags, s.cornerPolys, MAX_CORNERS, s.query);
            System.arraycopy(s.corners, 0, corners, cb * 3, n * 3);
            System.arraycopy(s.cornerFlags, 0, cornerFlags, cb, n);
            System.arraycopy(s.cornerPolys, 0, cornerPolys, cb, n);
            if (n > 0 && (flags[i] & AgentParams.OPTIMIZE_VISIBILITY) != 0) {
                int t = (cb + Math.min(1, n - 1)) * 3;
                c.optimizePathVisibility(
                        corners[t], corners[t + 1], corners[t + 2], optRange[i], s.query, f);
            }
        }
        cornerCount[i] = n;
        float x = px[i];
        float z = pz[i];
        if (n > 0) {
            int last = cb + n - 1;
            float ldx = corners[last * 3] - x;
            float ldz = corners[last * 3 + 2] - z;
            float lastDistSq = ldx * ldx + ldz * ldz;
            float r = radius[i];
            if ((cornerFlags[last] & NavMeshQuery.STRAIGHT_OFFMESH) != 0
                    && lastDistSq < r * r * 2.25f * 2.25f) {
                if (c.moveOverOffMeshLink(cornerPolys[last], s.refs, s.a, s.b, mesh)) {
                    startOffMesh(i, s);
                    return;
                }
            }
        }
        if (targetState[i] == TARGET_VALID) {
            boolean end = n == 0 || (cornerFlags[cb + n - 1] & NavMeshQuery.STRAIGHT_END) != 0;
            if (end) {
                float ex = n == 0 ? x : corners[(cb + n - 1) * 3];
                float ez = n == 0 ? z : corners[(cb + n - 1) * 3 + 2];
                float d = (float) Math.sqrt((ex - x) * (ex - x) + (ez - z) * (ez - z));
                if (d <= arrival[i]) {
                    targetState[i] = TARGET_ARRIVED;
                    cornerCount[i] = 0;
                    return;
                }
            }
            // Direction: the first corner, or a blend toward the second (anticipating turns).
            float d0x = corners[cb * 3] - x;
            float d0z = corners[cb * 3 + 2] - z;
            float dirX = d0x;
            float dirZ = d0z;
            if ((flags[i] & AgentParams.ANTICIPATE_TURNS) != 0 && n > 1) {
                float d1x = corners[(cb + 1) * 3] - x;
                float d1z = corners[(cb + 1) * 3 + 2] - z;
                float len0 = (float) Math.sqrt(d0x * d0x + d0z * d0z);
                float len1 = (float) Math.sqrt(d1x * d1x + d1z * d1z);
                if (len1 > 0.001f) {
                    d1x /= len1;
                    d1z /= len1;
                }
                dirX = d0x - d1x * len0 * 0.5f;
                dirZ = d0z - d1z * len0 * 0.5f;
            }
            float len = (float) Math.sqrt(dirX * dirX + dirZ * dirZ);
            if (len > 1e-6f) {
                // Slow down over the last two radii.
                float slow = radius[i] * 2;
                float toGoal = slow;
                if (end) {
                    float ex = corners[(cb + n - 1) * 3] - x;
                    float ez = corners[(cb + n - 1) * 3 + 2] - z;
                    toGoal = Math.min((float) Math.sqrt(ex * ex + ez * ez), slow);
                }
                float speed = maxSpeed[i] * toGoal / slow;
                dvx[i] = dirX / len * speed;
                dvz[i] = dirZ / len * speed;
            }
        } else if (targetState[i] == TARGET_VELOCITY) {
            dvx[i] = reqVx[i];
            dvz[i] = reqVz[i];
        }
        boolean moving = targetState[i] == TARGET_VALID || targetState[i] == TARGET_VELOCITY;
        if (moving && (flags[i] & AgentParams.SEPARATION) != 0 && sepWeight[i] > 0) {
            separate(i);
        }
    }

    private void findNeighbours(int i, Scratch s) {
        float range = queryRange[i];
        float x = px[i];
        float z = pz[i];
        IntList cand = s.candidates;
        cand.clear();
        grid.query(x - range, z - range, x + range, z + range, -1, cand);
        int base = i * MAX_NEIS;
        int count = 0;
        int[] arr = cand.array();
        for (int k = 0, n = cand.size(); k < n; k++) {
            int j = bodies.entity[arr[k]];
            if (j == i || !active[j]) {
                continue;
            }
            if (Math.abs(py[j] - py[i]) >= (height[i] + height[j]) * 0.5f) {
                continue; // another floor
            }
            float dx = px[j] - x;
            float dz = pz[j] - z;
            float d = dx * dx + dz * dz;
            if (d > range * range) {
                continue;
            }
            // Insert sorted by distance, keeping the nearest MAX_NEIS (ties by index: stable).
            int at = count;
            if (count == MAX_NEIS) {
                if (d >= neiDist[base + MAX_NEIS - 1]) {
                    continue;
                }
                at = MAX_NEIS - 1;
            } else {
                count++;
            }
            while (at > 0 && neiDist[base + at - 1] > d) {
                nei[base + at] = nei[base + at - 1];
                neiDist[base + at] = neiDist[base + at - 1];
                at--;
            }
            nei[base + at] = j;
            neiDist[base + at] = d;
        }
        neiCount[i] = count;
    }

    private void separate(int i) {
        float range = queryRange[i];
        float inv = 1f / range;
        float w = sepWeight[i];
        float sx = 0;
        float sz = 0;
        float weights = 0;
        int base = i * MAX_NEIS;
        for (int k = 0; k < neiCount[i]; k++) {
            int j = nei[base + k];
            float dx = px[i] - px[j];
            float dz = pz[i] - pz[j];
            float d2 = dx * dx + dz * dz;
            if (d2 < 1e-5f || d2 > range * range) {
                continue;
            }
            float d = (float) Math.sqrt(d2);
            float weight = w * (1 - (d * inv) * (d * inv));
            sx += dx * weight / d;
            sz += dz * weight / d;
            weights += 1;
        }
        if (weights > 1e-4f) {
            float desired = (float) Math.sqrt(dvx[i] * dvx[i] + dvz[i] * dvz[i]);
            // Fade separation with the arrival slow-down, or it holds agents off their goals.
            float fade = desired / maxSpeed[i] / weights;
            dvx[i] += sx * fade;
            dvz[i] += sz * fade;
            float speed = (float) Math.sqrt(dvx[i] * dvx[i] + dvz[i] * dvz[i]);
            if (speed > desired && speed > 0) {
                dvx[i] *= desired / speed;
                dvz[i] *= desired / speed;
            }
        }
    }

    private void startOffMesh(int i, Scratch s) {
        state[i] = STATE_OFFMESH;
        animRef[i] = s.refs[1];
        int o = i * 9;
        animPos[o] = px[i];
        animPos[o + 1] = py[i];
        animPos[o + 2] = pz[i];
        for (int k = 0; k < 3; k++) {
            animPos[o + 3 + k] = s.a[k];
            animPos[o + 6 + k] = s.b[k];
        }
        float dx = s.b[0] - s.a[0];
        float dz = s.b[2] - s.a[2];
        animT[i] = 0;
        animMax[i] = Math.max(0.1f, (float) Math.sqrt(dx * dx + dz * dz) / maxSpeed[i] * 0.5f);
        vx[i] = vz[i] = dvx[i] = dvz[i] = 0;
        cornerCount[i] = 0;
    }

    private void orcaRange(int from, int to, int thread) {
        Orca orca = scratch.get(thread).orca;
        float h = timeHorizon;
        float step = dt;
        for (int k = from; k < to; k++) {
            int i = activeList[k];
            if (state[i] != STATE_WALKING) {
                nvx[i] = nvz[i] = 0;
                continue;
            }
            if ((flags[i] & AgentParams.AVOIDANCE) == 0) {
                nvx[i] = dvx[i];
                nvz[i] = dvz[i];
                continue;
            }
            orca.clear();
            int base = i * MAX_NEIS;
            for (int n = 0; n < neiCount[i]; n++) {
                int j = nei[base + n];
                float share =
                        state[j] == STATE_WALKING && (flags[j] & AgentParams.AVOIDANCE) != 0
                                ? 0.5f
                                : 1f;
                orca.addAgent(
                        px[i],
                        pz[i],
                        vx[i],
                        vz[i],
                        px[j],
                        pz[j],
                        vx[j],
                        vz[j],
                        radius[i] + radius[j],
                        h,
                        step,
                        share);
            }
            orca.solve(dvx[i], dvz[i], maxSpeed[i]);
            nvx[i] = orca.resultX;
            nvz[i] = orca.resultY;
        }
    }

    private void integrateRange(int from, int to, int thread) {
        float step = dt;
        for (int k = from; k < to; k++) {
            int i = activeList[k];
            if (state[i] != STATE_WALKING) {
                continue;
            }
            float ddx = nvx[i] - vx[i];
            float ddz = nvz[i] - vz[i];
            float ds = (float) Math.sqrt(ddx * ddx + ddz * ddz);
            float maxDelta = maxAccel[i] * step;
            if (ds > maxDelta) {
                ddx *= maxDelta / ds;
                ddz *= maxDelta / ds;
            }
            vx[i] += ddx;
            vz[i] += ddz;
            if (vx[i] * vx[i] + vz[i] * vz[i] > 1e-8f) {
                px[i] += vx[i] * step;
                pz[i] += vz[i] * step;
            } else {
                vx[i] = vz[i] = 0;
            }
        }
    }

    private void collideRange(int from, int to, int thread) {
        for (int k = from; k < to; k++) {
            int i = activeList[k];
            dispX[i] = dispZ[i] = 0;
            if (state[i] != STATE_WALKING) {
                continue;
            }
            float sx = 0;
            float sz = 0;
            float w = 0;
            int base = i * MAX_NEIS;
            for (int n = 0; n < neiCount[i]; n++) {
                int j = nei[base + n];
                float dx = px[i] - px[j];
                float dz = pz[i] - pz[j];
                float r = radius[i] + radius[j];
                float d2 = dx * dx + dz * dz;
                if (d2 > r * r) {
                    continue;
                }
                float d = (float) Math.sqrt(d2);
                float pen;
                if (d < 1e-4f) {
                    // On top of each other: split sideways, in opposite directions.
                    if (i > j) {
                        dx = -vz[i];
                        dz = vx[i];
                    } else {
                        dx = vz[i];
                        dz = -vx[i];
                    }
                    if (dx == 0 && dz == 0) {
                        dx = i > j ? 1 : -1;
                    }
                    pen = 0.01f;
                } else {
                    pen = (r - d) * 0.5f * COLLISION_RESOLVE / d;
                }
                sx += dx * pen;
                sz += dz * pen;
                w += 1;
            }
            if (w > 1e-4f) {
                dispX[i] = sx / w;
                dispZ[i] = sz / w;
            }
        }
    }

    private void applyRange(int from, int to, int thread) {
        for (int k = from; k < to; k++) {
            int i = activeList[k];
            px[i] += dispX[i];
            pz[i] += dispZ[i];
        }
    }

    private void moveRange(int from, int to, int thread) {
        Scratch s = scratch.get(thread);
        for (int k = from; k < to; k++) {
            int i = activeList[k];
            if (state[i] == STATE_WALKING) {
                PathCorridor c = corridors[i];
                if (c.movePosition(px[i], py[i], pz[i], s.query, filters[filterIndex[i]])) {
                    float[] p = c.pos();
                    px[i] = p[0];
                    py[i] = p[1];
                    pz[i] = p[2];
                }
                int ts = targetState[i];
                if (ts == TARGET_NONE
                        || ts == TARGET_VELOCITY
                        || ts == TARGET_ARRIVED
                        || ts == TARGET_FAILED) {
                    c.reset(c.firstPoly(), px[i], py[i], pz[i]);
                }
            } else if (state[i] == STATE_OFFMESH) {
                animate(i);
            }
        }
    }

    private void animate(int i) {
        animT[i] += dt;
        int o = i * 9;
        float t = animT[i];
        float max = animMax[i];
        if (t >= max) {
            state[i] = STATE_WALKING;
            px[i] = animPos[o + 6];
            py[i] = animPos[o + 7];
            pz[i] = animPos[o + 8];
            vx[i] = vz[i] = 0;
            return;
        }
        // A short approach to the link's start, then along it.
        float ta = max * 0.15f;
        int a;
        int b;
        float u;
        if (t < ta) {
            a = o;
            b = o + 3;
            u = t / ta;
        } else {
            a = o + 3;
            b = o + 6;
            u = (t - ta) / (max - ta);
        }
        px[i] = animPos[a] + (animPos[b] - animPos[a]) * u;
        py[i] = animPos[a + 1] + (animPos[b + 1] - animPos[a + 1]) * u;
        pz[i] = animPos[a + 2] + (animPos[b + 2] - animPos[a + 2]) * u;
    }

    private void detectStuck() {
        float step = dt;
        for (int k = 0; k < activeCount; k++) {
            int i = activeList[k];
            if (state[i] != STATE_WALKING || targetState[i] != TARGET_VALID) {
                stuckTime[i] = 0;
                continue;
            }
            float desired = (float) Math.sqrt(dvx[i] * dvx[i] + dvz[i] * dvz[i]);
            float mx = px[i] - prevX[i];
            float mz = pz[i] - prevZ[i];
            float actual = (float) Math.sqrt(mx * mx + mz * mz) / step;
            if (desired > 0.25f * maxSpeed[i] && actual < 0.1f * desired) {
                stuckTime[i] += step;
            } else {
                stuckTime[i] = Math.max(0, stuckTime[i] - step);
            }
            if (stuckTime[i] > stuckSeconds) {
                stuckTime[i] = 0;
                if (++repaths[i] > maxRepaths) {
                    targetState[i] = TARGET_FAILED;
                    corridors[i].reset(corridors[i].firstPoly(), px[i], py[i], pz[i]);
                } else {
                    request(i);
                }
            }
        }
    }

    /**
     * Draws the agents (debug view): body circles colored by state, velocity (green), desired
     * velocity (yellow) and the corners each steers for (white). Allocates nothing.
     */
    public void debugDraw(DebugSink sink, int thread) {
        for (int i = 0; i < capacity; i++) {
            if (!active[i]) {
                continue;
            }
            float x = px[i];
            float y = py[i] + 0.05f;
            float z = pz[i];
            int color =
                    state[i] == STATE_OFFMESH
                            ? 0xff40c0ff
                            : targetState[i] == TARGET_FAILED
                                    ? 0xffff3030
                                    : targetState[i] == TARGET_ARRIVED ? 0xff80ff80 : 0xffe0e0e0;
            sink.circle(thread, x, y, z, radius[i], color);
            sink.line(thread, x, y, z, x + vx[i], y, z + vz[i], 0xff40ff40);
            sink.line(thread, x, y + 0.02f, z, x + dvx[i], y + 0.02f, z + dvz[i], 0xffffe040);
            float lx = x;
            float ly = y;
            float lz = z;
            int base = i * MAX_CORNERS * 3;
            for (int k = 0; k < cornerCount[i]; k++) {
                float cx = corners[base + k * 3];
                float cy = corners[base + k * 3 + 1] + 0.05f;
                float cz = corners[base + k * 3 + 2];
                sink.line(thread, lx, ly, lz, cx, cy, cz, 0x80ffffff);
                lx = cx;
                ly = cy;
                lz = cz;
            }
        }
    }
}
