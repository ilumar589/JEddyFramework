package dev.jeddy.physics.crowd;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.core.io.ByteReader;
import dev.jeddy.core.io.ByteWriter;
import dev.jeddy.core.job.JobSystem;
import dev.jeddy.physics.bvh.TriFlags;
import dev.jeddy.physics.bvh.TriangleMesh;
import dev.jeddy.physics.nav.NavMesh;
import dev.jeddy.physics.nav.NavMeshData;
import dev.jeddy.physics.nav.bake.NavBakeConfig;
import dev.jeddy.physics.nav.bake.NavMeshBaker;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

/** Crowd simulation: following, avoidance, arrival, off-mesh links, stuck detection (M8-08). */
class CrowdTest {
    static final NavBakeConfig CFG =
            new NavBakeConfig(0.2f, 0.1f, 1.8f, 0.4f, 0.4f, 45, 32, 8, 20, 12, 1.3f, 6);
    static NavMeshData room;
    static NavMeshData field;
    static final float DT = 1 / 30f;

    @BeforeAll
    static void bake() {
        TriangleMesh m = new TriangleMesh();
        m.addGround(0, 0, 20, 20, 0, TriFlags.WALKABLE);
        m.addBox(8, 0, 8, 12, 1.5f, 12, TriFlags.BLOCKS_MOVEMENT);
        TriangleMesh f = new TriangleMesh();
        f.addGround(0, 0, 30, 30, 0, TriFlags.WALKABLE);
        try (JobSystem jobs = new JobSystem(2)) {
            room = NavMeshBaker.bake(m, CFG, jobs);
            field = NavMeshBaker.bake(f, CFG, jobs);
        }
    }

    static void step(Crowd c, int ticks) {
        for (int t = 0; t < ticks; t++) {
            c.update(DT);
        }
    }

    @Test
    void anAgentWalksAroundTheBlockAndArrives() {
        Crowd c = new Crowd(new NavMesh(room), 8, 0, 0, 20, 20, null);
        int a = c.addAgent(2, 0, 10, new AgentParams());
        assertEquals(Crowd.STATE_WALKING, c.state(a));
        assertTrue(c.requestMoveTarget(a, 18, 0, 10));
        int ticks = 0;
        while (c.targetState(a) != Crowd.TARGET_ARRIVED && ticks < 30 * 15) {
            c.update(DT);
            ticks++;
            float x = c.x(a);
            float z = c.z(a);
            assertTrue(!(x > 7.7f && x < 12.3f && z > 7.7f && z < 12.3f), "outside the block");
        }
        assertEquals(Crowd.TARGET_ARRIVED, c.targetState(a));
        assertEquals(18, c.x(a), 0.15f);
        assertEquals(10, c.z(a), 0.15f);
        // ≈ 17 m at 3.5 m/s plus acceleration and slow-down.
        float seconds = ticks * DT;
        assertTrue(seconds > 4.5f && seconds < 7.5f, "took " + seconds + " s");
        // It then stands still.
        step(c, 30);
        assertEquals(18, c.x(a), 0.15f);
        assertEquals(0, c.velocityX(a), 1e-3f);
    }

    @Test
    void headOnAgentsAvoidEachOther() {
        Crowd c = new Crowd(new NavMesh(field), 8, 0, 0, 30, 30, null);
        AgentParams p = new AgentParams();
        p.separationWeight = 0;
        int a = c.addAgent(5, 0, 15, p);
        int b = c.addAgent(25, 0, 15.05f, p);
        c.requestMoveTarget(a, 25, 0, 15);
        c.requestMoveTarget(b, 5, 0, 15);
        float minDist = Float.MAX_VALUE;
        for (int t = 0; t < 30 * 12; t++) {
            c.update(DT);
            float dx = c.x(a) - c.x(b);
            float dz = c.z(a) - c.z(b);
            minDist = Math.min(minDist, (float) Math.sqrt(dx * dx + dz * dz));
        }
        assertTrue(minDist > 0.75f, "they never overlap: closest " + minDist);
        assertEquals(Crowd.TARGET_ARRIVED, c.targetState(a));
        assertEquals(Crowd.TARGET_ARRIVED, c.targetState(b));
        assertEquals(25, c.x(a), 0.2f);
        assertEquals(5, c.x(b), 0.2f);
    }

    /** Two groups of 20 swap sides of the field. */
    static Crowd swap(JobSystem jobs, float[] minGap) {
        Crowd c = new Crowd(new NavMesh(field), 64, 0, 0, 30, 30, jobs);
        AgentParams p = new AgentParams();
        for (int k = 0; k < 20; k++) {
            float z = 5 + k;
            c.addAgent(3 + (k % 2), 0, z, p);
            c.addAgent(27 - (k % 2), 0, z, p);
        }
        for (int i = 0; i < 40; i++) {
            float z = c.z(i);
            c.requestMoveTarget(i, i % 2 == 0 ? 27 - (i / 2 % 2) : 3 + (i / 2 % 2), 0, z);
        }
        for (int t = 0; t < 30 * 25; t++) {
            c.update(DT);
            if (minGap != null && t > 15) {
                for (int i = 0; i < 40; i++) {
                    for (int j = i + 1; j < 40; j++) {
                        float dx = c.x(i) - c.x(j);
                        float dz = c.z(i) - c.z(j);
                        minGap[0] = Math.min(minGap[0], (float) Math.sqrt(dx * dx + dz * dz));
                    }
                }
            }
        }
        return c;
    }

    @Test
    void groupsCrossThroughEachOther() {
        float[] gap = {Float.MAX_VALUE};
        Crowd c = swap(null, gap);
        int arrived = 0;
        for (int i = 0; i < 40; i++) {
            if (c.targetState(i) == Crowd.TARGET_ARRIVED) {
                arrived++;
            }
        }
        assertTrue(arrived >= 38, "arrived: " + arrived);
        assertTrue(gap[0] > 0.6f, "bodies (0.8 m apart when touching) barely overlap: " + gap[0]);
    }

    @Test
    void parallelUpdatesMatchSerialOnes() {
        Crowd serial = swap(null, null);
        Crowd parallel;
        try (JobSystem jobs = new JobSystem(3)) {
            parallel = swap(jobs, null);
        }
        float[] a = new float[80];
        float[] b = new float[80];
        for (int i = 0; i < 40; i++) {
            a[i * 2] = serial.x(i);
            a[i * 2 + 1] = serial.z(i);
            b[i * 2] = parallel.x(i);
            b[i * 2 + 1] = parallel.z(i);
        }
        assertArrayEquals(a, b, "deterministic for any worker count");
    }

    @Test
    void agentsJumpAcrossOffMeshLinks() {
        TriangleMesh m = new TriangleMesh();
        m.addGround(0, 0, 8, 10, 0, TriFlags.WALKABLE);
        m.addGround(12, 0, 20, 10, 0, TriFlags.WALKABLE);
        NavMeshData gap =
                NavMeshBaker.bake(
                        m,
                        CFG,
                        List.of(),
                        List.of(
                                new NavMeshBaker.OffMeshLink(
                                        6.5f, 0, 5, 13.5f, 0, 5, 0.6f, false, 63, 42)),
                        null);
        Crowd c = new Crowd(new NavMesh(gap), 4, 0, 0, 20, 10, null);
        int a = c.addAgent(2, 0, 5, new AgentParams());
        c.requestMoveTarget(a, 18, 0, 5);
        boolean jumped = false;
        for (int t = 0; t < 30 * 10 && c.targetState(a) != Crowd.TARGET_ARRIVED; t++) {
            c.update(DT);
            if (c.state(a) == Crowd.STATE_OFFMESH) {
                jumped = true;
                assertEquals(42, c.offMeshUserId(a));
                assertTrue(c.x(a) >= 5.5f && c.x(a) <= 14, "between the link ends: " + c.x(a));
            }
        }
        assertTrue(jumped);
        assertEquals(Crowd.TARGET_ARRIVED, c.targetState(a));
        assertEquals(18, c.x(a), 0.15f);
    }

    @Test
    void stuckAgentsRepathThenGiveUp() {
        Crowd c = new Crowd(new NavMesh(room), 4, 0, 0, 20, 20, null);
        c.setStuckPolicy(0.5f, 2);
        AgentParams p = new AgentParams();
        p.maxAcceleration = 0; // cannot get going
        int a = c.addAgent(2, 0, 2, p);
        c.requestMoveTarget(a, 18, 0, 2);
        step(c, 2);
        assertEquals(Crowd.TARGET_VALID, c.targetState(a));
        step(c, 30 * 3);
        assertEquals(Crowd.TARGET_FAILED, c.targetState(a));
    }

    @Test
    void partialTargetsArriveAtTheNearestReachablePoint() {
        TriangleMesh m = new TriangleMesh();
        m.addGround(0, 0, 8, 10, 0, TriFlags.WALKABLE);
        m.addGround(12, 0, 20, 10, 0, TriFlags.WALKABLE);
        Crowd c = new Crowd(new NavMesh(NavMeshBaker.bake(m, CFG, null)), 4, 0, 0, 20, 10, null);
        int a = c.addAgent(2, 0, 5, new AgentParams());
        assertTrue(c.requestMoveTarget(a, 18, 0, 5));
        step(c, 30 * 6);
        assertEquals(Crowd.TARGET_ARRIVED, c.targetState(a));
        assertTrue(c.isPartial(a));
        assertEquals(7.4f, c.x(a), 0.3f, "at the near edge of the gap");
        assertEquals(5, c.z(a), 0.2f);
    }

    @Test
    void twoHundredFiftySixAgentsUpdateQuickly() {
        try (JobSystem jobs = new JobSystem(JobSystem.defaultWorkerCount())) {
            Crowd c = new Crowd(new NavMesh(field), 256, 0, 0, 30, 30, jobs);
            c.setMaxRequestsPerTick(64);
            AgentParams p = new AgentParams();
            java.util.SplittableRandom rnd = new java.util.SplittableRandom(2);
            for (int k = 0; k < 256; k++) {
                c.addAgent(1 + (k % 16) * 1.8f, 0, 1 + (k / 16) * 1.8f, p);
            }
            for (int t = 0; t < 600; t++) {
                if (t % 120 == 0) {
                    for (int i = 0; i < 256; i++) {
                        c.requestMoveTarget(
                                i, (float) rnd.nextDouble(1, 29), 0, (float) rnd.nextDouble(1, 29));
                    }
                }
                c.update(DT);
            }
            for (int i = 0; i < 256; i++) {
                c.requestMoveTarget(
                        i, (float) rnd.nextDouble(1, 29), 0, (float) rnd.nextDouble(1, 29));
            }
            long t0 = System.nanoTime();
            for (int t = 0; t < 120; t++) {
                c.update(DT); // everyone repaths (64 per tick), then walks through the others
            }
            double ms = (System.nanoTime() - t0) / 120 / 1e6;
            System.out.printf(
                    "crowd update, 256 agents: %.2f ms on %d workers%n", ms, jobs.workerCount());
            assertTrue(ms < 8, "update " + ms + " ms");
        }
    }

    @Test
    void updatesAllocateNothing() {
        try (JobSystem jobs = new JobSystem(2)) {
            Crowd c = new Crowd(new NavMesh(field), 64, 0, 0, 30, 30, jobs);
            AgentParams p = new AgentParams();
            for (int k = 0; k < 48; k++) {
                c.addAgent(2 + (k % 8) * 3.3f, 0, 2 + (k / 8) * 4f, p);
            }
            int[] tick = {0};
            Allocations.assertNoAllocation(
                    "Crowd.update",
                    10,
                    60,
                    () -> {
                        if (tick[0]++ % 90 == 0) {
                            for (int i = 0; i < 48; i++) {
                                c.requestMoveTarget(i, 30 - c.x(i), 0, 30 - c.z(i));
                            }
                        }
                        c.update(DT);
                    });
        }
    }

    @Test
    void aSaveRestoresAgentsInTheirSlotsAndTheirTargets() {
        Crowd c = new Crowd(new NavMesh(room), 8, 0, 0, 20, 20, null);
        AgentParams fast = new AgentParams();
        fast.maxSpeed = 5;
        fast.radius = 0.3f;
        fast.flags = AgentParams.AVOIDANCE;
        int a = c.addAgent(2, 0, 10, new AgentParams());
        int b = c.addAgent(2, 0, 4, fast);
        int gone = c.addAgent(2, 0, 16, new AgentParams());
        c.removeAgent(gone);
        int d = c.addAgent(16, 0, 16, new AgentParams()); // takes the freed slot: slot 2
        assertEquals(gone, d);
        assertTrue(c.requestMoveTarget(a, 18, 0, 10));
        c.requestMoveVelocity(d, -1, 0);
        step(c, 45); // a is on its way around the block, d is drifting
        float ax = c.x(a);
        float az = c.z(a);
        float avx = c.velocityX(a);
        float dx = c.x(d);
        ByteWriter w = new ByteWriter();
        Allocations.assertNoAllocation(
                "crowd write",
                () -> {
                    w.reset();
                    c.write(w);
                });
        // A fresh crowd of the same capacity (another run); plus one stray agent it must drop.
        Crowd loaded = new Crowd(new NavMesh(room), 8, 0, 0, 20, 20, null);
        loaded.addAgent(5, 0, 5, new AgentParams());
        loaded.addAgent(6, 0, 6, new AgentParams());
        ByteReader r = new ByteReader(w.toBytes());
        Allocations.assertNoAllocation(
                "crowd read",
                10,
                50,
                () -> {
                    r.seek(0);
                    loaded.read(r);
                });
        assertTrue(loaded.isActive(a) && loaded.isActive(b) && loaded.isActive(d));
        for (int i = 3; i < 8; i++) {
            assertTrue(!loaded.isActive(i), "slot " + i + " is empty again");
        }
        assertEquals(ax, loaded.x(a), 1e-4f);
        assertEquals(az, loaded.z(a), 1e-4f);
        assertEquals(avx, loaded.velocityX(a), 1e-4f);
        assertEquals(5, loaded.maxSpeed(b));
        assertEquals(0.3f, loaded.radius(b));
        assertEquals(Crowd.TARGET_REQUESTING, loaded.targetState(a), "the target was re-requested");
        assertEquals(Crowd.TARGET_VELOCITY, loaded.targetState(d));
        assertEquals(Crowd.TARGET_NONE, loaded.targetState(b));
        assertEquals(dx, loaded.x(d), 1e-4f);
        // The loaded agent completes the same walk.
        int ticks = 0;
        while (loaded.targetState(a) != Crowd.TARGET_ARRIVED && ticks < 30 * 15) {
            loaded.update(DT);
            ticks++;
        }
        assertEquals(Crowd.TARGET_ARRIVED, loaded.targetState(a));
        assertEquals(18, loaded.x(a), 0.15f);
        assertTrue(loaded.x(d) < dx, "d kept drifting at its requested velocity");
        // A save from a bigger crowd: the slots past this capacity are skipped, the rest load.
        Crowd big = new Crowd(new NavMesh(room), 16, 0, 0, 20, 20, null);
        for (int i = 0; i < 12; i++) {
            big.addAgent(1 + i, 0, 2, new AgentParams());
        }
        ByteWriter w2 = new ByteWriter();
        big.write(w2);
        loaded.read(new ByteReader(w2.toBytes()));
        for (int i = 0; i < 8; i++) {
            assertTrue(loaded.isActive(i));
            assertEquals(1 + i, loaded.x(i), 0.2f);
        }
    }
}
