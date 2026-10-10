package dev.jeddy.physics.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.physics.bvh.Bvh;
import dev.jeddy.physics.bvh.BvhBuilder;
import dev.jeddy.physics.bvh.TriFlags;
import dev.jeddy.physics.bvh.TriangleMesh;
import dev.jeddy.physics.crowd.AgentParams;
import dev.jeddy.physics.crowd.Crowd;
import dev.jeddy.physics.grid.Bodies;
import dev.jeddy.physics.nav.NavFlags;
import dev.jeddy.physics.nav.NavMesh;
import dev.jeddy.physics.nav.bake.NavBakeConfig;
import dev.jeddy.physics.nav.bake.NavMeshBaker;
import dev.jeddy.physics.trigger.Triggers;
import dev.jeddy.physics.vis.OcclusionGrid;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.Test;

import java.util.List;

/** Physics and navigation debug views (TASKS M8-14). */
class DebugDrawTest {
    /** Counts lines per color; checks they are finite. */
    static final class Counter implements DebugSink {
        int lines;
        int walls;
        int portals;
        int links;
        int disabled;

        @Override
        public void line(
                int thread, float x0, float y0, float z0, float x1, float y1, float z1, int argb) {
            assertTrue(Float.isFinite(x0 + y0 + z0 + x1 + y1 + z1));
            lines++;
            switch (argb) {
                case 0xffe0e0e0 -> walls++;
                case 0xff40c0ff -> portals++;
                case 0xffffe040, 0xff40ff60 -> links++;
                case 0xffff3030 -> disabled++;
                default -> {}
            }
        }
    }

    @Test
    void everythingDraws() {
        TriangleMesh m = new TriangleMesh();
        m.addGround(0, 0, 20, 10, 0, TriFlags.WALKABLE);
        m.addBox(9, 0, 3, 11, 1.5f, 7, TriFlags.BLOCKS_MOVEMENT | TriFlags.BLOCKS_LOS);
        NavBakeConfig cfg =
                new NavBakeConfig(0.2f, 0.1f, 1.8f, 0.4f, 0.4f, 45, 32, 8, 20, 12, 1.3f, 6);
        NavMesh mesh =
                new NavMesh(
                        NavMeshBaker.bake(
                                m,
                                cfg,
                                List.of(),
                                List.of(
                                        new NavMeshBaker.OffMeshLink(
                                                2, 0, 2, 4, 0, 8, 0.5f, false, 63, 1)),
                                null));
        mesh.setPolyFlags(mesh.refAt(0, 0, 0), NavFlags.WALK | NavFlags.DISABLED);
        Counter c = new Counter();
        mesh.debugDraw(c, 0, 0.05f);
        assertTrue(c.walls > 4, "walls: " + c.walls);
        assertTrue(c.portals > 4, "linked portals: " + c.portals);
        assertEquals(8, c.links, "one jump arc");
        assertEquals(2, c.disabled, "a cross on the disabled polygon");

        Crowd crowd = new Crowd(mesh, 4, 0, 0, 20, 10, null);
        crowd.addAgent(2, 0, 5, new AgentParams());
        crowd.requestMoveTarget(0, 18, 0, 5);
        crowd.update(1 / 30f);
        crowd.update(1 / 30f);
        Bvh bvh = BvhBuilder.build(m, null);
        Bodies bodies = new Bodies(4);
        bodies.addCylinder(1, 3, 0, 3, 0.5f, 2, 1);
        bodies.addBox(2, 6, 0, 6, 1, 0.5f, 1, 0.7f, 1);
        Triggers triggers = new Triggers();
        triggers.addBox(7, 5, 0, 5, 1, 1, 2, 0.3f, -1);
        triggers.addSphere(8, 15, 1, 5, 2, -1);
        triggers.addPrism(9, new float[] {0, 0, 2, 0, 2, 2}, 3, 0, 2, -1);
        OcclusionGrid occ =
                OcclusionGrid.bake(bvh, 0, 0, 20, 10, 0.5f, 0.2f, 1.8f, TriFlags.BLOCKS_LOS);
        Counter all = new Counter();
        Allocations.assertNoAllocation(
                "debug views",
                () -> {
                    all.lines = 0;
                    mesh.debugDraw(all, 0, 0.05f);
                    crowd.debugDraw(all, 0);
                    bvh.debugDraw(all, 0, 4, 0xff808080);
                    bodies.debugDraw(all, 0, 0xffffffff);
                    triggers.debugDraw(all, 0, 0xffff00ff);
                    occ.debugDraw(all, 0, 0, 0, 20, 10, 0.1f, 0xff000000);
                });
        assertTrue(all.lines > c.lines + 100, "lines: " + all.lines);
    }
}
