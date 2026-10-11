package dev.jeddy.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.format.LevelData;
import dev.jeddy.math.IsoCamera;
import dev.jeddy.math.MathF;
import dev.jeddy.render.RenderPacket;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * Drags through the viewport (TASKS M14-06): the mouse is put on a handle's projection, moved to
 * another world point's projection, and the objects must end where the math says; picking and box
 * selection; an idle frame allocates nothing.
 */
class ViewportTest {
    @TempDir Path dir;
    private final Editor editor = new Editor();
    private final EditorContext ctx = editor.context();
    private final Viewport vp = editor.viewport();
    private final ViewportInput in = new ViewportInput();
    private final float[] center = new float[3];
    private final float[] px = new float[3];
    private LevelData level;
    private int wall;

    @BeforeEach
    void setUp() {
        level = WorldFixture.level();
        wall = level.placementId(0);
        editor.open(level, null);
        editor.setShown(true);
        in.windowWidth = 800;
        in.windowHeight = 600;
        IsoCamera cam = ctx.camera();
        cam.target.set(4, 0, 0);
        cam.distance = 16;
        ctx.settings().snap = false;
        frame(0, 0); // computes the camera matrices
    }

    /** One frame at window point {@code (x, y)}. */
    private void frame(float x, float y) {
        in.mouseX = x;
        in.mouseY = y;
        vp.update(in, 1f / 60);
        in.endFrame();
    }

    /** Projects world point {@code (x, y, z)} to the window into {@code px}. */
    private void project(float x, float y, float z) {
        assertTrue(GizmoMath.project(ctx.camera().viewProjection, x, y, z, 0, 0, 800, 600, px, 0));
    }

    /** Presses the left button on the projection of a world point. */
    private void pressAt(float x, float y, float z) {
        project(x, y, z);
        in.leftPressed = true;
        in.leftDown = true;
        frame(px[0], px[1]);
    }

    /** Moves (button held) to the projection of a world point. */
    private void dragTo(float x, float y, float z) {
        project(x, y, z);
        frame(px[0], px[1]);
    }

    private void release() {
        in.leftDown = false;
        frame(in.mouseX, in.mouseY);
    }

    @Test
    void translateAlongAnAxisHandle() {
        ctx.selection().select(wall);
        frame(0, 0); // the gizmo settles on the wall
        vp.gizmoCenter(center);
        float L = vp.gizmoLength();
        assertEquals(4, center[0]);
        assertTrue(L > 0.1f);
        // Grab the X axis halfway and drag to a point one axis length further along X.
        pressAt(center[0] + 0.5f * L, center[1], center[2]);
        assertEquals(Viewport.AXIS_X, vp.hoveredHandle());
        assertTrue(vp.isDragging());
        dragTo(center[0] + 1.5f * L, center[1], center[2]);
        assertEquals(4 + L, level.placementFloat(0, 0), 1e-3);
        assertEquals(0, level.placementFloat(0, 2), 1e-4, "nothing moved along z");
        release();
        assertFalse(vp.isDragging());
        assertEquals(1, ctx.undo().size());
        assertEquals("Move", ctx.undo().undoName());
        editor.undo();
        assertEquals(4, level.placementFloat(0, 0), 1e-5);
        // With snapping, the same drag lands on a multiple of the step.
        ctx.settings().snap = true;
        ctx.settings().translateStep = 0.5f;
        pressAt(center[0] + 0.5f * L, center[1], center[2]);
        dragTo(center[0] + 1.5f * L, center[1], center[2]);
        release();
        float moved = level.placementFloat(0, 0) - 4;
        assertEquals(Math.round(L / 0.5f) * 0.5f, moved, 1e-3);
    }

    @Test
    void translateOnAPlaneHandleMovesTwoObjectsTogether() {
        int torch = level.lightId(0);
        ctx.selection().select(wall);
        ctx.selection().add(torch); // the torch is primary: the gizmo sits at (1, 2, 3)
        frame(0, 0);
        vp.gizmoCenter(center);
        float L = vp.gizmoLength();
        assertEquals(1, center[0]);
        // The XZ plane square (normal Y) spans 0.3 L .. 0.65 L on x and z.
        pressAt(center[0] + 0.45f * L, center[1], center[2] + 0.45f * L);
        assertEquals(Viewport.PLANE_Y, vp.hoveredHandle());
        dragTo(center[0] + 0.45f * L + 2, center[1], center[2] + 0.45f * L - 1);
        release();
        assertEquals(3, level.lightFloat(0, 0), 1e-3);
        assertEquals(2, level.lightFloat(0, 1), 1e-3, "y is untouched on the XZ plane");
        assertEquals(2, level.lightFloat(0, 2), 1e-3);
        assertEquals(6, level.placementFloat(0, 0), 1e-3, "the wall moved by the same delta");
        assertEquals(-1, level.placementFloat(0, 2), 1e-3);
    }

    @Test
    void rotateAroundTheYRing() {
        ctx.selection().select(wall);
        vp.setMode(Viewport.MODE_ROTATE);
        frame(0, 0);
        vp.gizmoCenter(center);
        float L = vp.gizmoLength();
        // The Y ring passes through center + L·X; drag that point round to center + L·Z: a turn
        // of -90° about Y (x → z is the negative direction under the right-hand rule).
        pressAt(center[0] + L, center[1], center[2]);
        assertEquals(Viewport.AXIS_Y, vp.hoveredHandle());
        dragTo(center[0], center[1], center[2] + L);
        release();
        float h = -MathF.PI / 4;
        assertEquals(0, level.placementFloat(0, 3), 1e-3);
        assertEquals(Math.sin(h), level.placementFloat(0, 4), 1e-3);
        assertEquals(0, level.placementFloat(0, 5), 1e-3);
        assertEquals(Math.cos(h), level.placementFloat(0, 6), 1e-3);
        assertEquals(4, level.placementFloat(0, 0), 1e-4, "the primary stays on the pivot");
        // Snapped to 15°: a 40° turn becomes 45°.
        editor.undo();
        ctx.settings().snap = true;
        ctx.settings().rotateStepDegrees = 15;
        float a = MathF.toRadians(-40);
        pressAt(center[0] + L, center[1], center[2]);
        dragTo(center[0] + L * (float) Math.cos(a), center[1], center[2] - L * (float) Math.sin(a));
        release();
        assertEquals(Math.sin(MathF.toRadians(-45) / 2), level.placementFloat(0, 4), 1e-3);
    }

    @Test
    void scaleAlongAnAxisAndUniformly() {
        ctx.selection().select(wall);
        vp.setMode(Viewport.MODE_SCALE);
        frame(0, 0);
        vp.gizmoCenter(center);
        float L = vp.gizmoLength();
        pressAt(center[0] + 0.5f * L, center[1], center[2]);
        assertEquals(Viewport.AXIS_X, vp.hoveredHandle());
        dragTo(center[0] + 1.0f * L, center[1], center[2]);
        release();
        assertEquals(2, level.placementFloat(0, 7), 1e-3, "twice as far along the axis: ×2");
        assertEquals(1, level.placementFloat(0, 8), 1e-4);
        editor.undo();
        // The center cube scales uniformly by the ratio of screen distances from the center.
        project(center[0], center[1], center[2]);
        float cx = px[0], cy = px[1];
        in.leftPressed = true;
        in.leftDown = true;
        frame(cx + 3, cy);
        assertEquals(Viewport.CENTER, vp.hoveredHandle());
        frame(cx + 9, cy);
        release();
        assertEquals(3, level.placementFloat(0, 7), 1e-3);
        assertEquals(3, level.placementFloat(0, 8), 1e-3);
        assertEquals(3, level.placementFloat(0, 9), 1e-3);
    }

    @Test
    void escapeCancelsADragWithoutLeavingHistory() {
        ctx.selection().select(wall);
        frame(0, 0);
        vp.gizmoCenter(center);
        float L = vp.gizmoLength();
        pressAt(center[0] + 0.5f * L, center[1], center[2]);
        dragTo(center[0] + 1.5f * L, center[1], center[2]);
        assertEquals(1, ctx.undo().size());
        vp.cancel();
        assertEquals(4, level.placementFloat(0, 0), 1e-5);
        assertEquals(0, ctx.undo().size());
        assertFalse(vp.isDragging());
        release();
        assertEquals(0, ctx.undo().size(), "the release after a cancel does nothing");
    }

    @Test
    void clicksPickMarkersAndBoxesSelectWhatProjectsInside() {
        // A click on the torch's marker selects it; on empty ground it clears.
        pressAt(1, 2, 3);
        release();
        assertEquals(level.lightId(0), ctx.selection().primary());
        pressAt(6, 0, 2);
        release();
        assertTrue(ctx.selection().isEmpty());
        // Shift-click adds.
        pressAt(1, 2, 3);
        release();
        in.shift = true;
        pressAt(-2, 0, -2);
        release();
        in.shift = false;
        assertEquals(2, ctx.selection().size());
        assertEquals(level.spawnId(0), ctx.selection().primary());
        // A box around the wall and the torch (their positions) selects them, and whatever else
        // projects inside the rectangle, which depends on the view: count those the same way.
        ctx.selection().clear();
        project(4, 0, 0);
        float ax = px[0], ay = px[1];
        project(1, 2, 3);
        float bx = px[0], by = px[1];
        float x0 = Math.min(ax, bx) - 3, x1 = Math.max(ax, bx) + 3;
        float y0 = Math.min(ay, by) - 3, y1 = Math.max(ay, by) + 3;
        assertTrue(x0 >= 0 && y0 >= 0 && x1 < 800 && y1 < 600, "both are on screen");
        in.leftPressed = true;
        in.leftDown = true;
        frame(x0, y0);
        frame(x1, y1);
        assertTrue(vp.isBoxSelecting());
        int expected = 0;
        for (int id = 1; id < level.nextId(); id++) {
            if (level.kindOf(id) == LevelData.KIND_NONE) {
                continue;
            }
            LevelObjects.position(level, id, center, 0);
            project(center[0], center[1], center[2]);
            if (px[0] >= x0 && px[0] <= x1 && px[1] >= y0 && px[1] <= y1) {
                expected++;
            }
        }
        release();
        assertTrue(ctx.selection().contains(wall));
        assertTrue(ctx.selection().contains(level.lightId(0)));
        assertEquals(expected, ctx.selection().size());
    }

    @Test
    void cameraOrbitsPansAndZooms() {
        IsoCamera cam = ctx.camera();
        float yaw = cam.yaw;
        in.rightPressed = true;
        in.rightDown = true;
        frame(400, 300);
        in.deltaX = 100;
        frame(500, 300);
        assertEquals(yaw - 100 * ctx.settings().orbitSpeed, cam.yaw, 1e-5);
        in.rightDown = false;
        frame(500, 300);
        in.wheelY = 1;
        float d = cam.distance;
        frame(500, 300);
        assertEquals(d / ctx.settings().zoomStep, cam.distance, 1e-4);
        // Pan with the middle button: the target moves against the mouse.
        float tx = cam.target.x;
        in.middlePressed = true;
        in.middleDown = true;
        frame(400, 300);
        in.deltaX = 10;
        frame(410, 300);
        assertTrue(cam.target.x != tx || cam.target.z != 0);
        // F frames the selection.
        ctx.selection().select(wall);
        vp.frameSelection();
        assertEquals(4, cam.target.x, 1e-5);
    }

    @Test
    void idleFramesAllocateNothing() throws Exception {
        try (WorldFixture f = new WorldFixture(dir)) {
            Editor e = new Editor();
            e.attach(f.world, f.loader);
            e.open(f.level, null);
            e.setShown(true);
            e.context().selection().select(f.level.placementId(0));
            Viewport v = e.viewport();
            RenderPacket p = new RenderPacket(16);
            ViewportInput idle = new ViewportInput();
            idle.windowWidth = 800;
            idle.windowHeight = 600;
            idle.mouseX = 300;
            idle.mouseY = 200;
            for (int i = 0; i < 3; i++) {
                v.update(idle, 1f / 60);
                p.reset();
                e.extract(p);
            }
            assertTrue(p.debugLineCount > 100, "grid, markers and a gizmo were drawn");
            assertTrue(p.debugOverlayStart > 0 && p.debugOverlayStart < p.debugLineCount);
            Allocations.assertNoAllocation(
                    "editor idle frame",
                    () -> {
                        for (int i = 0; i < 20; i++) {
                            v.update(idle, 1f / 60);
                            p.reset();
                            e.extract(p);
                        }
                    });
            // Hovering the gizmo and picking allocate nothing either.
            float[] c = new float[3];
            v.gizmoCenter(c);
            float[] pix = new float[3];
            GizmoMath.project(
                    e.context().camera().viewProjection,
                    c[0] + v.gizmoLength() * 0.5f,
                    c[1],
                    c[2],
                    0,
                    0,
                    800,
                    600,
                    pix,
                    0);
            idle.mouseX = pix[0];
            idle.mouseY = pix[1];
            Allocations.assertNoAllocation(
                    "editor hover and pick",
                    () -> {
                        for (int i = 0; i < 20; i++) {
                            v.update(idle, 1f / 60);
                            v.pick();
                        }
                    });
            assertEquals(Viewport.AXIS_X, v.hoveredHandle());
        }
    }
}
