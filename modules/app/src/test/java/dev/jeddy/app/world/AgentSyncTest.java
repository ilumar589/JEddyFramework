package dev.jeddy.app.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.core.handle.Handles;
import dev.jeddy.core.job.JobSystem;
import dev.jeddy.physics.bvh.TriFlags;
import dev.jeddy.physics.bvh.TriangleMesh;
import dev.jeddy.physics.crowd.AgentParams;
import dev.jeddy.physics.crowd.Crowd;
import dev.jeddy.physics.nav.NavMesh;
import dev.jeddy.physics.nav.NavMeshData;
import dev.jeddy.physics.nav.bake.NavBakeConfig;
import dev.jeddy.physics.nav.bake.NavMeshBaker;

import org.junit.jupiter.api.Test;

/** Agents turn toward their motion, and standing agents toward what they face (M10-02, M12-07). */
class AgentSyncTest {
    private static final float DT = 1 / 30f;

    @Test
    void walkersFaceTheirMotionAndStandersTheirTarget() {
        NavMeshData field;
        TriangleMesh ground = new TriangleMesh();
        ground.addGround(0, 0, 20, 20, 0, TriFlags.WALKABLE);
        try (JobSystem jobs = new JobSystem(1)) {
            field =
                    NavMeshBaker.bake(
                            ground,
                            new NavBakeConfig(
                                    0.2f, 0.1f, 1.8f, 0.4f, 0.4f, 45, 32, 8, 20, 12, 1.3f, 6),
                            jobs);
        }
        Crowd crowd = new Crowd(new NavMesh(field), 4, 0, 0, 20, 20, null);
        World world = new World(16, null);
        int e = world.create(Handles.NULL, 2, 0, 10, 0);
        int a = crowd.addAgent(2, 0, 10, new AgentParams());
        AgentSync sync = new AgentSync(crowd, world);
        sync.bind(a, e, 0);
        // Walking toward +X turns the entity to yaw 90°.
        crowd.requestMoveTarget(a, 10, 0, 10);
        for (int t = 0; t < 30; t++) {
            crowd.update(DT);
            sync.apply(DT);
        }
        assertEquals(Math.PI / 2, sync.yaw(a), 0.05);
        crowd.resetMoveTarget(a);
        for (int t = 0; t < 30; t++) {
            crowd.update(DT);
            sync.apply(DT);
        }
        float standing = sync.yaw(a);
        assertEquals(Math.PI / 2, standing, 0.05, "standing still keeps the facing");
        // Facing a target behind it (-X): it turns at the turn rate (10 rad/s), then stops.
        sync.faceToward(a, crowd.x(a) - 5, crowd.z(a));
        crowd.update(DT);
        sync.apply(DT);
        assertTrue(Math.abs(sync.yaw(a) - standing) <= 10 * DT + 1e-4f, "limited turn");
        for (int t = 0; t < 15; t++) {
            crowd.update(DT);
            sync.apply(DT);
        }
        double d = Math.IEEEremainder(sync.yaw(a) + Math.PI / 2, Math.PI * 2);
        assertEquals(0, d, 1e-4, "now facing -X: " + sync.yaw(a));
    }
}
