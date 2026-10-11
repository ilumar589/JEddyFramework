package dev.jeddy.render.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.math.IsoCamera;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.Test;

/** CRPG camera behaviour (TASKS M5-09). */
class IsoCameraControllerTest {
    private static void settle(IsoCameraController c) {
        for (int i = 0; i < 200; i++) {
            c.update(1 / 60f, 16f / 9f);
        }
    }

    @Test
    void panIsRelativeToTheViewAndClampedToTheLevel() {
        IsoCamera cam = new IsoCamera();
        cam.yaw = 0; // looking towards -Z
        IsoCameraController c = new IsoCameraController(cam);
        c.update(0.016f, 1.6f);
        c.pan(0, 1); // forward
        c.update(1f, 1.6f);
        assertTrue(cam.target.z < -10, "forward is -Z at yaw 0: " + cam.target.z);
        assertEquals(0, cam.target.x, 1e-4f);
        c.pan(1, 0); // right is +X at yaw 0
        c.update(1f, 1.6f);
        assertTrue(cam.target.x > 10);
        c.setBounds(-5, -5, 5, 5);
        c.update(0.016f, 1.6f);
        assertEquals(5, cam.target.x);
        assertEquals(-5, cam.target.z);
    }

    @Test
    void zoomAndRotationAreSmoothedAndClamped() {
        IsoCamera cam = new IsoCamera();
        IsoCameraController c = new IsoCameraController(cam);
        float start = cam.distance;
        c.zoom(3);
        c.update(0.016f, 1.6f);
        assertTrue(cam.distance < start && cam.distance > start / Math.pow(1.15, 3), "smoothed");
        settle(c);
        assertEquals(start / Math.pow(1.15, 3), cam.distance, 1e-3);
        c.zoom(100);
        settle(c);
        assertEquals(c.minDistance, cam.distance, 1e-3f);
        float yaw = cam.yaw;
        c.rotate((float) Math.PI / 2);
        settle(c);
        assertEquals(yaw + Math.PI / 2, cam.yaw, 1e-3);
    }

    @Test
    void syncedGoalsKeepADistanceSetDirectly() {
        IsoCamera cam = new IsoCamera();
        IsoCameraController c = new IsoCameraController(cam); // goals from the default distance
        cam.distance = 30;
        cam.yaw = 1;
        settle(c);
        assertEquals(20, cam.distance, 1e-3f, "without a sync the old goal wins");
        cam.distance = 30;
        cam.yaw = 1;
        c.syncGoals();
        settle(c);
        assertEquals(30, cam.distance, 1e-3f);
        assertEquals(1, cam.yaw, 1e-6f);
        c.maxDistance = 25;
        c.syncGoals();
        settle(c);
        assertEquals(25, cam.distance, 1e-3f, "clamped to the zoom limits");
    }

    @Test
    void dragKeepsTheGrabbedGroundPointUnderTheCursor() {
        IsoCamera cam = new IsoCamera();
        IsoCameraController c = new IsoCameraController(cam);
        c.update(0.016f, 1.6f);
        float[] grabbed = new float[3];
        assertTrue(c.groundPoint(0.2f, -0.1f, grabbed));
        c.beginDrag(0.2f, -0.1f);
        c.drag(-0.3f, 0.25f);
        c.endDrag();
        float[] now = new float[3];
        assertTrue(c.groundPoint(-0.3f, 0.25f, now));
        assertEquals(grabbed[0], now[0], 1e-3f);
        assertEquals(grabbed[2], now[2], 1e-3f);
    }

    @Test
    void followGlidesToTheTargetAndFrameUpdatesDoNotAllocate() {
        IsoCamera cam = new IsoCamera();
        IsoCameraController c = new IsoCameraController(cam);
        c.follow(10, 0, -4);
        settle(c);
        assertEquals(10, cam.target.x, 1e-3f);
        assertEquals(-4, cam.target.z, 1e-3f);
        assertTrue(c.lodPixels(1080) > 1000, "a narrow FOV gives many pixels per unit");
        Allocations.assertNoAllocation(
                "camera frame",
                () -> {
                    c.edgePan(0, 500, 1920, 1080);
                    c.zoom(0.1f);
                    c.update(1 / 60f, 16f / 9f);
                });
    }
}
