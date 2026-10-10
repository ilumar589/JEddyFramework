package dev.jeddy.physics.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.core.collections.IntList;
import dev.jeddy.core.job.JobSystem;
import dev.jeddy.physics.bvh.Bvh;
import dev.jeddy.physics.bvh.BvhBuilder;
import dev.jeddy.physics.bvh.TriFlags;
import dev.jeddy.physics.bvh.TriangleMesh;
import dev.jeddy.physics.grid.Bodies;
import dev.jeddy.physics.grid.UniformGrid;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

/** Rays, overlaps, sweeps, line of sight and picking (TASKS M8-03). */
class CollisionWorldTest {
    static final int UNITS = 1;
    static final int PROPS = 2;
    static final int ITEMS = 4;

    Bodies bodies;
    CollisionWorld world;
    final QueryContext q = new QueryContext();
    final IntList out = new IntList();

    /** A 40×40 m floor at y 0 with a wall along x = 10 (z from 0 to 20, 3 m high). */
    static Bvh level() {
        TriangleMesh m = new TriangleMesh();
        m.addGround(-20, -20, 20, 20, 0, TriFlags.WALKABLE | TriFlags.BLOCKS_PROJECTILE);
        m.addBox(
                10,
                0,
                0,
                10.2f,
                3,
                20,
                TriFlags.BLOCKS_LOS | TriFlags.BLOCKS_MOVEMENT | TriFlags.BLOCKS_PROJECTILE);
        return BvhBuilder.build(m, null);
    }

    @BeforeEach
    void setUp() {
        bodies = new Bodies(16);
        bodies.addCylinder(100, 0, 0, 5, 0.5f, 1.8f, UNITS); // a unit at (0, 5)
        bodies.addSphere(101, 3, 1, 5, 0.4f, PROPS); // a floating orb
        bodies.addBox(102, -4, 0, 5, 1, 0.5f, 1, (float) Math.toRadians(30), PROPS); // a crate
        bodies.addCylinder(103, 6, 0, -6, 0.4f, 1.0f, ITEMS); // a chest-like interactable
        world = new CollisionWorld(level(), bodies, new UniformGrid(-20, -20, 40, 40, 2, null));
        world.update();
    }

    @Test
    void raysHitTheNearestOfStaticAndBodies() {
        // From the west along +x at y 0.5, z 5: the crate, the unit, then the wall (the orb
        // floats at y 1 ± 0.4).
        assertTrue(world.raycast(-10, 0.5f, 5, 1, 0, 0, 100, -1, -1, -1, q));
        assertEquals(5f, q.t, 1e-4f, "the turned crate's corner region");
        assertEquals(102, q.entity, "the crate first");
        assertTrue(world.raycast(-10, 0.5f, 5, 1, 0, 0, 100, -1, UNITS, -1, q));
        assertEquals(100, q.entity);
        assertEquals(-0.5f, q.x, 1e-4f, "unit surface");
        assertEquals(-1f, q.nx, 1e-4f, "facing the ray");
        assertTrue(
                world.raycast(-10, 0.5f, 5, 1, 0, 0, 100, -1, UNITS, 100, q), "ignoring the unit");
        assertEquals(-1, q.entity);
        assertEquals(10f, q.x, 1e-4f, "the wall");
        assertTrue((q.triangleFlags & TriFlags.BLOCKS_LOS) != 0);
        // Down onto the floor.
        assertTrue(world.raycast(-15, 5, -15, 0, -1, 0, 10, TriFlags.WALKABLE, 0, -1, q));
        assertEquals(0f, q.y, 1e-5f);
        assertEquals(1f, Math.abs(q.ny), 1e-5f);
        // A long diagonal ray crossing many cells still finds a distant body.
        assertTrue(world.raycast(-19, 0.5f, -19, 25, 0, 13, 1, 0, ITEMS, -1, q));
        assertEquals(103, q.entity);
        assertFalse(world.raycastAny(-19, 0.5f, -19, 0, 0, 1, 1, 0, ITEMS, -1, q));
    }

    @Test
    void overlapsFindBodiesByShape() {
        out.clear();
        world.overlapSphere(0, 1, 5.9f, 0.5f, -1, out, q);
        assertEquals(1, out.size(), "touches the unit only");
        assertEquals(100, out.get(0));
        out.clear();
        world.overlapSphere(0, 3.0f, 5, 1.0f, -1, out, q);
        assertEquals(0, out.size(), "above the unit's head (1.8 m)");
        out.clear();
        world.overlapCylinder(2.5f, 0, 5, 0.2f, 2, -1, out, q);
        assertEquals(1, out.size(), "reaches the orb's side");
        assertEquals(101, out.get(0));
        // A 60° cone from the origin towards +z, 7 m long: the unit, the orb (at 31°: outside),
        // the crate.
        out.clear();
        world.overlapCone(0, 0, 0, 1, 7, (float) Math.toRadians(30), 0, 2, -1, out, q);
        int[] cone = sorted(out);
        assertTrue(Arrays.binarySearch(cone, 100) >= 0, "unit in the cone");
        assertTrue(Arrays.binarySearch(cone, 103) < 0, "chest behind");
        // A line along z = 5 from x = -10 to 10, 1 m wide: crate, unit, orb.
        out.clear();
        world.overlapLine(-10, 5, 10, 5, 1, 0, 2, -1, out, q);
        assertEquals(3, out.size());
        // A rotated box around the chest.
        out.clear();
        world.overlapBox(6, 0, -6, 0.3f, 0.3f, 1, 0.7f, -1, out, q);
        assertEquals(1, out.size());
        assertEquals(103, out.get(0));
        // The crate is turned 30°: a thin box along its diagonal overlaps it, one off its corner
        // does not.
        out.clear();
        world.overlapBox(-4, 0, 5, 0.05f, 0.05f, 1, 0, PROPS, out, q);
        assertEquals(1, out.size());
        out.clear();
        world.overlapBox(-5.05f, 0, 5.8f, 0.05f, 0.05f, 1, 0, PROPS, out, q);
        assertEquals(0, out.size(), "outside the turned crate");
        // A triangular prism around the unit and the orb.
        out.clear();
        float[] tri = {-1, 3, 5, 3, 2, 8};
        world.overlapPrism(tri, 3, 0, 2, -1, out, q);
        assertEquals(2, out.size());
    }

    @Test
    void sweptCylindersStopAtWallsAndBodies() {
        // Walking east into the wall at x = 10 with radius 0.5: stops at x = 9.5.
        assertTrue(
                world.sweepCylinder(
                        8, 0, 10, 0.5f, 1.8f, 0.3f, 4, 0, TriFlags.BLOCKS_MOVEMENT, 0, -1, q));
        assertEquals(1.5f / 4, q.t, 1e-4f);
        assertEquals(-1f, q.nx, 1e-4f);
        assertEquals(9.5f, q.x, 1e-4f);
        // Walking along the wall is free; the floor (below the step height) never blocks.
        assertFalse(
                world.sweepCylinder(9.4f, 0, 2, 0.5f, 1.8f, 0.3f, 0, 10, TriFlags.SOLID, 0, -1, q));
        // Into the unit at (0, 5) from the south.
        assertTrue(world.sweepCylinder(0, 0, 0, 0.5f, 1.8f, 0.3f, 0, 10, 0, UNITS, -1, q));
        assertEquals(100, q.entity);
        assertEquals(4f / 10, q.t, 1e-4f);
        assertEquals(-1f, q.nz, 1e-4f);
        // Into the turned crate from the west.
        assertTrue(world.sweepCylinder(-8, 0, 5, 0.3f, 1.8f, 0.3f, 6, 0, 0, PROPS, -1, q));
        assertEquals(102, q.entity);
        assertTrue(q.x < -4.5f && q.x > -5.6f, "stops at the crate's turned face: " + q.x);
        // Overlapping already: free to move out.
        assertFalse(world.sweepCylinder(0, 0, 5, 0.5f, 1.8f, 0.3f, 0, -3, 0, UNITS, 100, q));
    }

    @Test
    void lineOfSightIsBlockedByWallsAndOptionallyBodies() {
        assertTrue(
                world.lineOfSight(
                        0, 0, 10, 1.6f, 8, 0, 10, 1.6f, TriFlags.BLOCKS_LOS, 0, -1, -1, q));
        assertFalse(
                world.lineOfSight(
                        0, 0, 10, 1.6f, 15, 0, 10, 1.6f, TriFlags.BLOCKS_LOS, 0, -1, -1, q));
        // Over the 3 m wall from high up.
        assertTrue(
                world.lineOfSight(0, 0, 10, 4f, 15, 0, 10, 4f, TriFlags.BLOCKS_LOS, 0, -1, -1, q));
        // The unit at (0, 5) stands between (0, 0) and (0, 10) when units block sight.
        assertTrue(world.lineOfSight(0, 0, 0, 1, 0, 0, 10, 1, TriFlags.BLOCKS_LOS, 0, -1, -1, q));
        assertFalse(
                world.lineOfSight(0, 0, 0, 1, 0, 0, 10, 1, TriFlags.BLOCKS_LOS, UNITS, -1, -1, q));
        // ...but not for itself.
        assertTrue(
                world.lineOfSight(0, 0, 5, 1, 0, 0, 10, 1, TriFlags.BLOCKS_LOS, UNITS, 100, -1, q));
    }

    @Test
    void pickingPrefersUnitsThenInteractablesThenGround() {
        // Straight down onto the unit (padding makes a near miss count).
        assertEquals(
                CollisionWorld.PICK_UNIT,
                world.pick(0.6f, 20, 5, 0, -1, 0, 50, UNITS, ITEMS, 0.2f, q));
        assertEquals(100, q.entity);
        assertEquals(
                CollisionWorld.PICK_INTERACTABLE,
                world.pick(6, 20, -6, 0, -1, 0, 50, UNITS, ITEMS, 0.2f, q));
        assertEquals(103, q.entity);
        assertEquals(
                CollisionWorld.PICK_GROUND,
                world.pick(-10, 20, -10, 0, -1, 0, 50, UNITS, ITEMS, 0.2f, q));
        assertEquals(0f, q.y, 1e-5f);
        // The top of the wall is solid but not walkable.
        assertEquals(
                CollisionWorld.PICK_NONE,
                world.pick(10.1f, 20, 10, 0, -1, 0, 50, UNITS, ITEMS, 0.2f, q));
    }

    @Test
    void rayBatchesMatchSingleQueriesInParallel() {
        Random r = new Random(1);
        for (int i = 0; i < 200; i++) {
            bodies.addCylinder(
                    1000 + i,
                    r.nextFloat() * 36 - 18,
                    0,
                    r.nextFloat() * 36 - 18,
                    0.4f,
                    1.8f,
                    UNITS);
        }
        world.update();
        try (JobSystem jobs = new JobSystem(3)) {
            RayBatch batch = new RayBatch(512, jobs.threadSlots());
            for (int i = 0; i < 2000; i++) {
                batch.add(
                        r.nextFloat() * 36 - 18,
                        1,
                        r.nextFloat() * 36 - 18,
                        r.nextFloat() - 0.5f,
                        -0.05f,
                        r.nextFloat() - 0.5f,
                        40);
            }
            batch.run(world, TriFlags.SOLID, -1, false, jobs);
            for (int i = 0; i < batch.count(); i++) {
                int o = i * 6;
                boolean hit =
                        world.raycast(
                                batch.rays[o],
                                batch.rays[o + 1],
                                batch.rays[o + 2],
                                batch.rays[o + 3],
                                batch.rays[o + 4],
                                batch.rays[o + 5],
                                40,
                                TriFlags.SOLID,
                                -1,
                                -1,
                                q);
                assertEquals(hit ? q.t : Float.POSITIVE_INFINITY, batch.t[i], 1e-5f);
                assertEquals(hit ? q.entity : -1, batch.entity[i]);
            }
        }
    }

    @Test
    void queriesAllocateNothing() {
        float[] tri = {-1, 3, 5, 3, 2, 8};
        Allocations.assertNoAllocation(
                "queries",
                () -> {
                    out.clear();
                    world.raycast(-10, 1, 5, 1, 0, 0, 100, -1, -1, -1, q);
                    world.overlapSphere(0, 1, 5.9f, 0.5f, -1, out, q);
                    world.overlapCone(0, 0, 0, 1, 7, 0.5f, 0, 2, -1, out, q);
                    world.overlapPrism(tri, 3, 0, 2, -1, out, q);
                    world.sweepCylinder(
                            8, 0, 10, 0.5f, 1.8f, 0.3f, 4, 0, TriFlags.BLOCKS_MOVEMENT, -1, -1, q);
                    world.lineOfSight(
                            0, 0, 0, 1, 0, 0, 10, 1, TriFlags.BLOCKS_LOS, UNITS, -1, -1, q);
                    world.pick(0.6f, 20, 5, 0, -1, 0, 50, UNITS, ITEMS, 0.2f, q);
                });
    }

    private static int[] sorted(IntList l) {
        int[] a = Arrays.copyOf(l.array(), l.size());
        Arrays.sort(a);
        return a;
    }
}
