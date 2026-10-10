package dev.jeddy.bench;

import dev.jeddy.core.job.JobSystem;
import dev.jeddy.physics.bvh.Bvh;
import dev.jeddy.physics.bvh.BvhBuilder;
import dev.jeddy.physics.bvh.RayHit;
import dev.jeddy.physics.bvh.TriFlags;
import dev.jeddy.physics.bvh.TriangleMesh;
import dev.jeddy.physics.crowd.AgentParams;
import dev.jeddy.physics.crowd.Crowd;
import dev.jeddy.physics.grid.Bodies;
import dev.jeddy.physics.grid.UniformGrid;
import dev.jeddy.physics.nav.NavMesh;
import dev.jeddy.physics.nav.NavMeshData;
import dev.jeddy.physics.nav.NavPathBatch;
import dev.jeddy.physics.nav.QueryFilter;
import dev.jeddy.physics.nav.bake.NavBakeConfig;
import dev.jeddy.physics.nav.bake.NavMeshBaker;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * Gameplay physics (TASKS M8-01, M8-02, M8-07, M8-08): BVH raycasts on a 180k-triangle terrain
 * (target 1M rays/s/core), the broadphase rebuild for 5k bodies (target < 0.3 ms), 1000 paths
 * across a 200×200 m map (corridor plus string pulling), and a 256-agent crowd tick.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class PhysicsBench {
    private JobSystem jobs;
    private Bvh terrain;
    private final RayHit hit = new RayHit();
    private final float[] rays = new float[6 * 4096];
    private int ray;
    private Bodies bodies;
    private UniformGrid grid;
    private NavPathBatch paths;
    private final QueryFilter filter = new QueryFilter();
    private Crowd crowd;
    private int tick;
    private final SplittableRandom rnd = new SplittableRandom(5);

    /** Builds the terrain, bodies, the 200×200 m navmesh and the crowd. */
    @Setup(Level.Trial)
    public void setup() {
        jobs = new JobSystem(JobSystem.defaultWorkerCount());
        int n = 300;
        TriangleMesh t = new TriangleMesh((n + 1) * (n + 1), n * n * 2);
        SplittableRandom r = new SplittableRandom(7);
        for (int z = 0; z <= n; z++) {
            for (int x = 0; x <= n; x++) {
                t.addVertex(x, (float) r.nextDouble(0.3), z);
            }
        }
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                int a = z * (n + 1) + x;
                t.addTriangle(a, a + n + 1, a + n + 2, TriFlags.WALKABLE);
                t.addTriangle(a, a + n + 2, a + 1, TriFlags.WALKABLE);
            }
        }
        terrain = BvhBuilder.build(t, jobs);
        for (int i = 0; i < 4096; i++) {
            rays[i * 6] = (float) r.nextDouble(300);
            rays[i * 6 + 1] = 20;
            rays[i * 6 + 2] = (float) r.nextDouble(300);
            rays[i * 6 + 3] = (float) r.nextDouble(-0.5, 0.5);
            rays[i * 6 + 4] = -1;
            rays[i * 6 + 5] = (float) r.nextDouble(-0.5, 0.5);
        }

        bodies = new Bodies(5000);
        for (int i = 0; i < 5000; i++) {
            bodies.addCylinder(
                    i, (float) r.nextDouble(200), 0, (float) r.nextDouble(200), 0.4f, 1.8f, 1);
        }
        grid = new UniformGrid(0, 0, 200, 200, 2, jobs);

        NavMeshData map =
                NavMeshBaker.bake(
                        bigMap(),
                        new NavBakeConfig(
                                0.2f, 0.1f, 1.8f, 0.4f, 0.4f, 45, 160, 8, 20, 12, 1.3f, 6),
                        jobs);
        NavMesh mesh = new NavMesh(map);
        paths = new NavPathBatch(mesh, 1000, 512, 32, 4096, jobs.threadSlots());
        for (int i = 0; i < 1000; i++) {
            paths.add(
                    0,
                    (float) r.nextDouble(1, 199),
                    0,
                    (float) r.nextDouble(1, 199),
                    0,
                    (float) r.nextDouble(1, 199),
                    0,
                    (float) r.nextDouble(1, 199));
        }

        crowd = new Crowd(mesh, 256, 0, 0, 200, 200, jobs);
        crowd.setMaxRequestsPerTick(32);
        AgentParams p = new AgentParams();
        for (int i = 0; i < 256; i++) {
            crowd.addAgent(80 + (i % 16) * 2.5f, 0, 80 + (i / 16) * 2.5f, p);
        }
    }

    /** Stops the workers. */
    @TearDown(Level.Trial)
    public void tearDown() {
        jobs.close();
    }

    /** A 200×200 m floor with 1.5 m blocks on a jittered 8 m grid and walls with gaps. */
    static TriangleMesh bigMap() {
        TriangleMesh m = new TriangleMesh();
        for (int z = 0; z < 200; z += 20) {
            for (int x = 0; x < 200; x += 20) {
                m.addGround(x, z, x + 20, z + 20, 0, TriFlags.WALKABLE);
            }
        }
        SplittableRandom rnd = new SplittableRandom(3);
        int flags = TriFlags.BLOCKS_MOVEMENT | TriFlags.BLOCKS_LOS;
        for (int z = 4; z < 196; z += 8) {
            for (int x = 4; x < 196; x += 8) {
                if (rnd.nextInt(4) == 0) {
                    continue;
                }
                float cx = x + (float) rnd.nextDouble(-1.5, 1.5);
                float cz = z + (float) rnd.nextDouble(-1.5, 1.5);
                m.addBox(cx - 1, 0, cz - 1, cx + 1, 1.5f, cz + 1, flags);
            }
        }
        for (int w = 40; w < 200; w += 50) {
            m.addBox(10, 0, w, 90, 1.5f, w + 0.6f, flags);
            m.addBox(110, 0, w, 190, 1.5f, w + 0.6f, flags);
        }
        return m;
    }

    /** One closest-hit ray against the 180k-triangle terrain. */
    @Benchmark
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public float raycastTerrain() {
        int i = (ray++ & 4095) * 6;
        terrain.raycast(
                rays[i],
                rays[i + 1],
                rays[i + 2],
                rays[i + 3],
                rays[i + 4],
                rays[i + 5],
                100,
                -1,
                hit);
        return hit.t;
    }

    /** Broadphase rebuild with 5000 bodies. */
    @Benchmark
    public int gridRebuild5k() {
        grid.rebuild(bodies);
        return grid.entryCount();
    }

    /** 1000 paths (corridor and corners) on one thread. */
    @Benchmark
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public int paths1000Serial() {
        paths.run(filter, null);
        return paths.polyCount[0];
    }

    /** 1000 paths spread over the workers. */
    @Benchmark
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public int paths1000Parallel() {
        paths.run(filter, jobs);
        return paths.polyCount[0];
    }

    /** One 30 Hz tick of 256 agents, retargeted every 4 s. */
    @Benchmark
    public float crowd256() {
        if (tick++ % 120 == 0) {
            for (int i = 0; i < 256; i++) {
                crowd.requestMoveTarget(
                        i, (float) rnd.nextDouble(60, 140), 0, (float) rnd.nextDouble(60, 140));
            }
        }
        crowd.update(1 / 30f);
        return crowd.x(0);
    }
}
