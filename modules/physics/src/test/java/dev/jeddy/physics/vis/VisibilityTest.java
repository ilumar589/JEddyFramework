package dev.jeddy.physics.vis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.core.job.JobSystem;
import dev.jeddy.physics.bvh.Bvh;
import dev.jeddy.physics.bvh.BvhBuilder;
import dev.jeddy.physics.bvh.TriFlags;
import dev.jeddy.physics.bvh.TriangleMesh;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Random;

/** Occlusion grid bake and symmetric shadowcasting fog of war (TASKS M8-10). */
class VisibilityTest {
    @Test
    void openGroundShowsADisc() {
        OcclusionGrid g = new OcclusionGrid(0, 0, 1, 100, 100);
        Visibility v = new Visibility(g, null);
        v.setViewer(1, 50.5f, 50.5f, 10);
        assertEquals(1, v.update());
        int count = 0;
        for (int z = 0; z < 100; z++) {
            for (int x = 0; x < 100; x++) {
                if (v.isVisibleCell(x, z)) {
                    count++;
                    int dx = x - 50;
                    int dz = z - 50;
                    assertTrue(dx * dx + dz * dz <= 110, "inside the radius");
                }
            }
        }
        assertEquals(Math.PI * 110, count, 30, "about π (r² + r) cells: " + count);
        assertTrue(v.isVisible(55, 50));
        assertFalse(v.isVisible(62, 50));
    }

    @Test
    void wallsHideWhatIsBehindThemButAreSeen() {
        OcclusionGrid g = new OcclusionGrid(0, 0, 1, 40, 40);
        for (int z = 14; z <= 30; z++) {
            g.setBlocked(25, z, true);
        }
        Visibility v = new Visibility(g, null);
        v.setViewer(1, 20.5f, 20.5f, 15);
        v.update();
        assertTrue(v.isVisibleCell(25, 20), "the wall is lit");
        assertFalse(v.isVisibleCell(27, 20), "behind the wall");
        assertFalse(v.isVisibleCell(30, 22), "behind the wall");
        assertTrue(v.isVisibleCell(24, 20), "in front of the wall");
        assertTrue(v.isVisibleCell(27, 9), "around the end of the wall");
        // A door: open a gap and the view recomputes because the grid version changed.
        g.setBlocked(25, 20, false);
        assertEquals(1, v.update());
        assertTrue(v.isVisibleCell(27, 20), "through the open door");
    }

    @Test
    void visibilityIsSymmetric() {
        Random r = new Random(1);
        OcclusionGrid g = new OcclusionGrid(0, 0, 1, 48, 48);
        for (int i = 0; i < 300; i++) {
            g.setBlocked(r.nextInt(48), r.nextInt(48), true);
        }
        int checked = 0;
        for (int i = 0; i < 200; i++) {
            int ax = 8 + r.nextInt(32), az = 8 + r.nextInt(32);
            int bx = ax + r.nextInt(13) - 6, bz = az + r.nextInt(13) - 6;
            if (g.blocked(ax, az) || g.blocked(bx, bz)) {
                continue;
            }
            Visibility va = new Visibility(g, null);
            va.setViewer(1, ax + 0.5f, az + 0.5f, 8);
            va.update();
            Visibility vb = new Visibility(g, null);
            vb.setViewer(1, bx + 0.5f, bz + 0.5f, 8);
            vb.update();
            assertEquals(
                    va.isVisibleCell(bx, bz), vb.isVisibleCell(ax, az), "A sees B iff B sees A");
            checked++;
        }
        assertTrue(checked > 100);
    }

    @Test
    void exploredCellsStayAndPackForTheRenderer() {
        OcclusionGrid g = new OcclusionGrid(0, 0, 1, 64, 32);
        Visibility v = new Visibility(g, null);
        v.setViewer(7, 10.5f, 16.5f, 5);
        v.update();
        v.setViewer(7, 40.5f, 16.5f, 5);
        v.update();
        byte[] out = new byte[64 * 32];
        v.pack(out);
        assertEquals(Visibility.VISIBLE, out[16 * 64 + 40]);
        assertEquals(Visibility.EXPLORED, out[16 * 64 + 10]);
        assertEquals(0, out[16 * 64 + 25]);
        // Small moves do not recompute; removing the viewer leaves only explored cells.
        v.setViewer(7, 40.6f, 16.5f, 5);
        assertEquals(0, v.update());
        v.removeViewer(7);
        v.update();
        assertFalse(v.isVisible(40.5f, 16.5f));
        assertTrue(v.isExplored(40.5f, 16.5f));
    }

    @Test
    void occlusionIsBakedFromLosTrianglesAtEyeHeight() {
        TriangleMesh m = new TriangleMesh();
        m.addGround(0, 0, 20, 20, 0, TriFlags.WALKABLE);
        m.addBox(10, 0, 2, 10.2f, 3, 18, TriFlags.BLOCKS_LOS); // a wall along x = 10
        m.addBox(4, 0, 4, 5, 0.3f, 5, TriFlags.BLOCKS_LOS); // a low step: under eye height
        Bvh bvh = BvhBuilder.build(m, null);
        OcclusionGrid g =
                OcclusionGrid.bake(bvh, 0, 0, 20, 20, 0.5f, 0.5f, 2f, TriFlags.BLOCKS_LOS);
        assertEquals(40, g.width());
        assertTrue(g.blocked(g.cellX(10.1f), g.cellZ(10)), "the wall");
        assertFalse(g.blocked(g.cellX(4.5f), g.cellZ(4.5f)), "the low step");
        assertFalse(g.blocked(g.cellX(5), g.cellZ(10)), "the floor");
        // About 0.2 m × 16 m of wall → 1–2 columns of 32 cells.
        assertTrue(
                g.blockedCount() >= 32 && g.blockedCount() <= 3 * 33, "cells: " + g.blockedCount());
        OcclusionGrid back = OcclusionGrid.read(ByteBuffer.wrap(g.toBytes()));
        assertTrue(back.sameCells(g));
    }

    @Test
    void manyViewersUpdateInParallelWithoutAllocating() {
        Random r = new Random(2);
        OcclusionGrid g = new OcclusionGrid(0, 0, 0.5f, 400, 400);
        for (int i = 0; i < 4000; i++) {
            g.setBlocked(r.nextInt(400), r.nextInt(400), true);
        }
        try (JobSystem jobs = new JobSystem(3)) {
            Visibility par = new Visibility(g, jobs);
            Visibility seq = new Visibility(g, null);
            for (int i = 0; i < 12; i++) {
                float x = 20 + r.nextFloat() * 160;
                float z = 20 + r.nextFloat() * 160;
                par.setViewer(i, x, z, 20);
                seq.setViewer(i, x, z, 20);
            }
            assertEquals(12, par.update());
            seq.update();
            byte[] a = new byte[400 * 400];
            byte[] b = new byte[400 * 400];
            par.pack(a);
            seq.pack(b);
            assertTrue(java.util.Arrays.equals(a, b));
            float[] step = {0};
            Runnable tick =
                    () -> {
                        step[0] += 0.3f;
                        for (int i = 0; i < 12; i++) {
                            par.setViewer(i, 20 + i * 12 + (step[0] % 20), 100, 20);
                        }
                        par.update();
                        par.pack(a);
                    };
            tick.run();
            Allocations.assertNoAllocation("fog of war", 10, 50, tick);
            long t0 = System.nanoTime();
            for (int i = 0; i < 50; i++) {
                tick.run();
            }
            System.out.printf(
                    "fog of war, 12 moving viewers (r 20 m, 0.5 m cells), 400² grid: %.2f ms%n",
                    (System.nanoTime() - t0) / 50 / 1e6);
        }
    }
}
