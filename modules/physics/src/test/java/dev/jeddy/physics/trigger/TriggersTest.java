package dev.jeddy.physics.trigger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.jeddy.physics.grid.Bodies;
import dev.jeddy.physics.grid.UniformGrid;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

/** Trigger volumes and their enter/exit events (TASKS M8-13). */
class TriggersTest {
    @Test
    void entitiesEnterAndExitPrismsBoxesAndSpheres() {
        Bodies bodies = new Bodies(8);
        int a = bodies.addCylinder(7, -5, 0, 0, 0.5f, 1.8f, 1);
        int b = bodies.addCylinder(3, 5, 0, 0, 0.5f, 1.8f, 1);
        bodies.addSphere(9, 0, 10, 0, 0.5f, 2); // another layer, high up
        UniformGrid grid = new UniformGrid(-20, -20, 40, 40, 2, null);
        Triggers t = new Triggers();
        float[] square = {-2, -2, 2, -2, 2, 2, -2, 2};
        t.addPrism(100, square, 4, -1, 3, 1);
        t.addBox(200, 5, 0, 0, 1, 1, 2, 0.4f, 1);
        t.addSphere(300, 0, 10, 0, 1, -1);
        grid.rebuild(bodies);
        t.update(bodies, grid);
        // b starts inside the box; the orb inside the sphere.
        assertArrayEquals(new int[] {200, 3, Triggers.ENTER, 300, 9, Triggers.ENTER}, events(t));
        grid.rebuild(bodies);
        t.update(bodies, grid);
        assertEquals(0, t.eventCount(), "no change, no events");
        // a walks into the prism; b leaves the box.
        bodies.setPosition(a, 0, 0, 0);
        bodies.setPosition(b, 9, 0, 0);
        grid.rebuild(bodies);
        t.update(bodies, grid);
        assertArrayEquals(new int[] {100, 7, Triggers.ENTER, 200, 3, Triggers.EXIT}, events(t));
        // Deactivating a trigger lets its occupants out; removed bodies exit too.
        t.setActive(0, false);
        bodies.remove(bodies.indexOf(9));
        grid.rebuild(bodies);
        t.update(bodies, grid);
        assertArrayEquals(new int[] {100, 7, Triggers.EXIT, 300, 9, Triggers.EXIT}, events(t));
    }

    @Test
    void eventsAreSortedByEntityAndUpdatesAllocateNothing() {
        Bodies bodies = new Bodies(64);
        for (int i = 40; i > 0; i--) {
            bodies.addCylinder(i, (i % 8) - 4, 0, (i / 8) - 2, 0.3f, 1.8f, 1);
        }
        UniformGrid grid = new UniformGrid(-20, -20, 40, 40, 2, null);
        Triggers t = new Triggers();
        t.addSphere(1, 0, 0, 0, 30, 1);
        grid.rebuild(bodies);
        t.update(bodies, grid);
        assertEquals(40, t.eventCount());
        for (int k = 1; k < 40; k++) {
            assertEquals(k + 1, t.events()[k * 3 + 1], "ascending entities");
        }
        Allocations.assertNoAllocation(
                "triggers",
                () -> {
                    bodies.x[0] = -bodies.x[0];
                    grid.rebuild(bodies);
                    t.update(bodies, grid);
                });
    }

    private static int[] events(Triggers t) {
        return Arrays.copyOf(t.events(), t.eventCount() * 3);
    }
}
