package dev.jeddy.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.math.IsoCamera;
import dev.jeddy.math.MathF;

import org.junit.jupiter.api.Test;

/** The gizmo geometry against hand-computed cases (TASKS M14-06). */
class GizmoMathTest {
    private static final float EPS = 1e-4f;

    @Test
    void closestAxisParamIsTheFootOfTheCommonPerpendicular() {
        // X axis through the origin; a ray coming straight down at x = 3 hits it at t = 3.
        float t = GizmoMath.closestAxisParam(3, 10, 0, 0, -1, 0, 0, 0, 0, 1, 0, 0, -99);
        assertEquals(3, t, EPS);
        // The same ray offset in z (it misses the axis): the closest point is still x = 3.
        t = GizmoMath.closestAxisParam(3, 10, 5, 0, -1, 0, 0, 0, 0, 1, 0, 0, -99);
        assertEquals(3, t, EPS);
        // A diagonal ray from (0, 10, 0) toward (+1, -1, 0)/√2 crosses the axis at x = 10.
        float s = (float) Math.sqrt(0.5);
        t = GizmoMath.closestAxisParam(0, 10, 0, s, -s, 0, 0, 0, 0, 1, 0, 0, -99);
        assertEquals(10, t, 1e-3f);
        // The axis shifted to start at x = 4: the parameter is relative to its origin.
        t = GizmoMath.closestAxisParam(0, 10, 0, s, -s, 0, 4, 0, 0, 1, 0, 0, -99);
        assertEquals(6, t, 1e-3f);
        // Parallel to the axis: the fallback.
        t = GizmoMath.closestAxisParam(0, 1, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, -99);
        assertEquals(-99, t, EPS);
    }

    @Test
    void rayPlanePointHitsThePlaneThroughTheCenter() {
        float[] out = new float[3];
        // Ground plane (normal Y) through (0, 2, 0); a ray down from (1, 10, 5) lands at y = 2.
        assertTrue(GizmoMath.rayPlanePoint(1, 10, 5, 0, -1, 0, 0, 2, 0, 0, 1, 0, out, 0));
        assertEquals(1, out[0], EPS);
        assertEquals(2, out[1], EPS);
        assertEquals(5, out[2], EPS);
        // A ray along the plane misses; one pointing away misses.
        assertFalse(GizmoMath.rayPlanePoint(1, 10, 5, 1, 0, 0, 0, 2, 0, 0, 1, 0, out, 0));
        assertFalse(GizmoMath.rayPlanePoint(1, 10, 5, 0, 1, 0, 0, 2, 0, 0, 1, 0, out, 0));
    }

    @Test
    void signedAngleFollowsTheRightHandRule() {
        // Around +Y: from +X to +Z is a turn of -90° (x → -z is +90° about y).
        assertEquals(-MathF.PI / 2, GizmoMath.signedAngle(1, 0, 0, 0, 0, 1, 0, 1, 0), EPS);
        assertEquals(MathF.PI / 2, GizmoMath.signedAngle(0, 0, 1, 1, 0, 0, 0, 1, 0), EPS);
        // Around +Z: from +X to +Y is +90°.
        assertEquals(MathF.PI / 2, GizmoMath.signedAngle(1, 0, 0, 0, 1, 0, 0, 0, 1), EPS);
        assertEquals(0, GizmoMath.signedAngle(2, 0, 0, 5, 0, 0, 0, 1, 0), EPS);
        // Consistent with the quaternion: rotating +X by the angle lands on +Z.
        float[] q = new float[4];
        float[] v = {1, 0, 0};
        float[] r = new float[3];
        GizmoMath.axisAngle(0, 1, 0, GizmoMath.signedAngle(1, 0, 0, 0, 0, 1, 0, 1, 0), q, 0);
        GizmoMath.rotate(q, 0, v, 0, r, 0);
        assertEquals(0, r[0], EPS);
        assertEquals(1, r[2], EPS);
    }

    @Test
    void snapRoundsToTheNearestStep() {
        assertEquals(1.5f, GizmoMath.snap(1.6f, 0.5f), EPS);
        assertEquals(-2, GizmoMath.snap(-1.8f, 1), EPS);
        assertEquals(0, GizmoMath.snap(0.24f, 0.5f), EPS);
        assertEquals(0.7f, GizmoMath.snap(0.7f, 0), EPS);
        assertEquals(
                MathF.toRadians(30), GizmoMath.snap(MathF.toRadians(37), MathF.toRadians(15)), EPS);
    }

    @Test
    void projectMapsTheCameraTargetToTheRegionCenter() {
        IsoCamera cam = new IsoCamera();
        cam.target.set(3, 0, 5);
        cam.update(800f / 600);
        float[] out = new float[3];
        assertTrue(GizmoMath.project(cam.viewProjection, 3, 0, 5, 100, 50, 800, 600, out, 0));
        assertEquals(500, out[0], 0.01f);
        assertEquals(350, out[1], 0.01f);
        // Something above the target projects higher on screen (smaller y).
        GizmoMath.project(cam.viewProjection, 3, 2, 5, 100, 50, 800, 600, out, 0);
        assertTrue(out[1] < 350);
        // Behind the camera: refused.
        float ex = cam.eye().x, ey = cam.eye().y, ez = cam.eye().z;
        float bx = ex + (ex - 3), by = ey + (ey - 0), bz = ez + (ez - 5);
        assertFalse(GizmoMath.project(cam.viewProjection, bx, by, bz, 0, 0, 800, 600, out, 0));
        // metersPerPixel at the target's depth × the window height is the frustum height there.
        float mpp = GizmoMath.metersPerPixel(cam.fovY, cam.distance, 600);
        assertEquals(2 * cam.distance * Math.tan(cam.fovY / 2), mpp * 600, 1e-3);
    }

    @Test
    void pointSegmentDistanceOnScreen() {
        assertEquals(3, GizmoMath.pointSegmentDistance(5, 3, 0, 0, 10, 0), EPS);
        assertEquals(5, GizmoMath.pointSegmentDistance(-3, 4, 0, 0, 10, 0), EPS);
        assertEquals(0, GizmoMath.pointSegmentDistance(10, 0, 0, 0, 10, 0), EPS);
    }

    @Test
    void quaternionsComposeAndConvertToEuler() {
        float[] yaw = new float[4];
        float[] pitch = new float[4];
        float[] q = new float[4];
        float[] q2 = new float[4];
        float[] e = new float[3];
        // yaw 30° then pitch 20° composed by hand equals fromEuler.
        GizmoMath.axisAngle(0, 1, 0, MathF.toRadians(30), yaw, 0);
        GizmoMath.axisAngle(1, 0, 0, MathF.toRadians(20), pitch, 0);
        GizmoMath.mul(yaw, 0, pitch, 0, q, 0);
        GizmoMath.fromEuler(MathF.toRadians(30), MathF.toRadians(20), 0, q2, 0);
        for (int i = 0; i < 4; i++) {
            assertEquals(q2[i], q[i], EPS);
        }
        // Round trips for a few triples.
        float[][] triples = {{30, 20, 0}, {-100, 45, 70}, {170, -80, -10}, {0, 0, 90}};
        for (float[] t : triples) {
            GizmoMath.fromEuler(
                    MathF.toRadians(t[0]), MathF.toRadians(t[1]), MathF.toRadians(t[2]), q, 0);
            GizmoMath.toEuler(q, 0, e, 0);
            assertEquals(t[0], MathF.toDegrees(e[0]), 1e-2f, "yaw of " + t[0]);
            assertEquals(t[1], MathF.toDegrees(e[1]), 1e-2f, "pitch of " + t[1]);
            assertEquals(t[2], MathF.toDegrees(e[2]), 1e-2f, "roll of " + t[2]);
        }
        // A yaw rotates +Z toward +X (the engine's yaw convention, QuatOps.fromYaw).
        float[] z = {0, 0, 1};
        float[] r = new float[3];
        GizmoMath.rotate(yaw, 0, z, 0, r, 0);
        assertEquals(Math.sin(MathF.toRadians(30)), r[0], EPS);
        assertEquals(Math.cos(MathF.toRadians(30)), r[2], EPS);
    }
}
