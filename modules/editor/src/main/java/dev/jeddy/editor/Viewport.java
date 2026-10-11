package dev.jeddy.editor;

import dev.jeddy.app.world.LevelLoader;
import dev.jeddy.app.world.World;
import dev.jeddy.asset.format.LevelData;
import dev.jeddy.core.collections.IntList;
import dev.jeddy.core.handle.Handles;
import dev.jeddy.editor.command.TransformCommand;
import dev.jeddy.math.Intersect;
import dev.jeddy.math.IsoCamera;
import dev.jeddy.math.MathF;
import dev.jeddy.render.RenderPacket;
import dev.jeddy.ui.Ui;

/**
 * The editable 3D view (DESIGN §19, TASKS M14-06): the editor camera (orbit with the right button,
 * pan with the middle one or Shift+right, zoom with the wheel, {@code F} frames the selection),
 * picking (a click casts the camera ray against the world's renderables, {@link World#pick}, and
 * against the markers of lights, spawns and triggers; a drag on empty space box-selects whatever
 * projects inside the rectangle) and the three gizmos (translate, rotate, scale; {@code W}, {@code
 * E}, {@code R}) drawn as overlay debug lines at the primary selected object and dragged with the
 * math in {@link GizmoMath}. A drag is one {@link TransformCommand}, pushed on the first change and
 * re-applied every frame of the drag (D89).
 *
 * <p>Input is accepted inside the {@link #setRegion region} only (the part of the window not
 * covered by panels) and never while the UI wants the pointer. Nothing here allocates per frame
 * except the command a drag creates.
 *
 * <p>Thread ownership: the game thread.
 */
public final class Viewport {
    /** Gizmo modes. */
    public static final int MODE_TRANSLATE = 0;

    public static final int MODE_ROTATE = 1;
    public static final int MODE_SCALE = 2;

    // Handles: an axis (0..2), a plane named by its normal axis (3..5), or the center.
    static final int NONE = -1;
    static final int AXIS_X = 0;
    static final int AXIS_Y = 1;
    static final int AXIS_Z = 2;
    static final int PLANE_X = 3;
    static final int PLANE_Y = 4;
    static final int PLANE_Z = 5;
    static final int CENTER = 6;

    private static final float PICK_PIXELS = 8; // hit tolerance on screen
    private static final float MARKER_PIXELS = 10; // light / spawn / trigger marker hit radius
    private static final float CLICK_PIXELS = 4; // a press that moves less is a click
    private static final int COLOR_X = 0xFFE04848;
    private static final int COLOR_Y = 0xFF58D858;
    private static final int COLOR_Z = 0xFF4C8CFF;
    private static final int COLOR_HOT = 0xFFFFE040;
    private static final int COLOR_GRID = 0x40FFFFFF;
    private static final int COLOR_GRID_MAJOR = 0x70FFFFFF;
    private static final int COLOR_LIGHT = 0xFFFFC060;
    private static final int COLOR_SPAWN = 0xFF60E0FF;
    private static final int COLOR_TRIGGER = 0xFFE060FF;
    private static final int COLOR_SELECTED = 0xFFFFFFFF;
    private static final int RING_SEGMENTS = 32;

    private final EditorContext ctx;
    private float rx, ry, rw, rh; // the region, window points
    private int mode = MODE_TRANSLATE;
    private int hover = NONE;
    private int active = NONE;
    private boolean orbiting;
    private boolean panning;
    private boolean pressed; // the left button went down on empty space
    private boolean boxSelecting;
    private float pressX, pressY, mouseX, mouseY;
    private TransformCommand drag;
    private boolean dragPushed;
    private final float[] pivot = new float[3]; // gizmo center at drag start (the primary)
    private final float[] center = new float[3]; // gizmo center now
    private final float[] startPoint = new float[3];
    private float startParam;
    private float startDist;
    private float gizmoLen; // world length of a gizmo axis
    private float metersPerPixel; // at the gizmo
    private final float[] ray = new float[6]; // origin, unit direction
    private final float[] tmp = new float[12];
    private final float[] qa = new float[4];
    private final float[] qb = new float[4];
    private final float[] outT = new float[1];
    private final IntList boxHits = new IntList(64);
    private int lastWindowW = 1, lastWindowH = 1;
    private static final float[][] AXES = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
    private static final int[] AXIS_COLORS = {COLOR_X, COLOR_Y, COLOR_Z};

    private boolean regionSet;

    Viewport(EditorContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Sets the part of the window that is the 3D view (the dock leaves it free of panels); until it
     * is set, the whole window is.
     */
    public void setRegion(float x, float y, float w, float h) {
        rx = x;
        ry = y;
        rw = Math.max(1, w);
        rh = Math.max(1, h);
        regionSet = true;
    }

    /** Returns the gizmo mode ({@code MODE_*}). */
    public int mode() {
        return mode;
    }

    /** Sets the gizmo mode ({@code MODE_*}); a drag in progress is cancelled. */
    public void setMode(int mode) {
        if (this.mode != mode) {
            cancel();
            this.mode = mode;
        }
    }

    /** Returns the handle under the pointer ({@code AXIS_X} ... {@code CENTER}) or -1. */
    public int hoveredHandle() {
        return hover;
    }

    /** Returns whether a gizmo drag or a box selection is in progress. */
    public boolean isDragging() {
        return active != NONE || boxSelecting;
    }

    /** Returns whether a box selection is in progress, and its rectangle through {@link #box}. */
    public boolean isBoxSelecting() {
        return boxSelecting;
    }

    /** Writes the box selection rectangle (x, y, w, h) to {@code out}. */
    public void box(float[] out) {
        out[0] = Math.min(pressX, mouseX);
        out[1] = Math.min(pressY, mouseY);
        out[2] = Math.abs(mouseX - pressX);
        out[3] = Math.abs(mouseY - pressY);
    }

    /** Returns the world length of a gizmo axis (sized for {@code gizmoPixels} on screen). */
    public float gizmoLength() {
        return gizmoLen;
    }

    /** Writes the gizmo center (the primary object's position) to {@code out[0..2]}. */
    public void gizmoCenter(float[] out) {
        System.arraycopy(center, 0, out, 0, 3);
    }

    /** Cancels a drag (its command is reverted and forgotten) or a box selection. */
    public void cancel() {
        if (active != NONE && dragPushed) {
            ctx.undo().cancelTop();
        }
        drag = null;
        dragPushed = false;
        active = NONE;
        pressed = false;
        boxSelecting = false;
    }

    // ---- Per frame ----------------------------------------------------------------------------

    /** One frame of viewport input (see {@link ViewportInput}). */
    public void update(ViewportInput in, float dt) {
        lastWindowW = in.windowWidth;
        lastWindowH = in.windowHeight;
        if (!regionSet) {
            rx = 0;
            ry = 0;
            rw = lastWindowW;
            rh = lastWindowH;
        }
        IsoCamera cam = ctx.camera();
        cam.update((float) lastWindowW / lastWindowH);
        mouseX = in.mouseX;
        mouseY = in.mouseY;
        boolean inRegion = contains(mouseX, mouseY) && !in.uiWantsPointer;
        boolean shift = in.shift;
        boolean ctrl = in.ctrl;

        cameraInput(in, cam, inRegion, shift);
        computeRay(cam, mouseX, mouseY);
        LevelData level = ctx.level();
        if (level == null) {
            return;
        }
        Selection sel = ctx.selection();
        boolean hasPrimary = sel.primary() >= 0 && LevelObjects.exists(level, sel.primary());
        if (hasPrimary) {
            if (active == NONE) {
                LevelObjects.position(level, sel.primary(), center, 0);
            }
            gizmoScale(cam);
        }

        if (active != NONE) {
            if (in.leftDown) {
                updateDrag(ctrl);
            } else {
                endDrag();
            }
            return;
        }
        if (pressed) {
            if (in.leftDown) {
                if (!boxSelecting
                        && (Math.abs(mouseX - pressX) > CLICK_PIXELS
                                || Math.abs(mouseY - pressY) > CLICK_PIXELS)) {
                    boxSelecting = true;
                }
            } else {
                if (boxSelecting) {
                    finishBox(shift, ctrl);
                } else {
                    click(shift, ctrl);
                }
                pressed = false;
                boxSelecting = false;
            }
            return;
        }
        hover = hasPrimary && inRegion ? hitTest(cam) : NONE;
        if (in.leftPressed && inRegion) {
            if (hover != NONE) {
                beginDrag(hover);
            } else {
                pressed = true;
                pressX = mouseX;
                pressY = mouseY;
            }
        }
    }

    private boolean contains(float x, float y) {
        return x >= rx && y >= ry && x < rx + rw && y < ry + rh;
    }

    private void cameraInput(ViewportInput in, IsoCamera cam, boolean inRegion, boolean shift) {
        EditorSettings s = ctx.settings();
        if (inRegion && in.wheelY != 0) {
            float f = (float) Math.pow(s.zoomStep, -in.wheelY);
            if (cam.orthographic) {
                cam.orthoHeight = MathF.clamp(cam.orthoHeight * f, 1, 400);
            } else {
                cam.distance = MathF.clamp(cam.distance * f, 1, 400);
            }
        }
        if (in.rightPressed && inRegion) {
            if (shift) {
                panning = true;
            } else {
                orbiting = true;
            }
        }
        if (in.middlePressed && inRegion) {
            panning = true;
        }
        if (!in.rightDown && !in.middleDown) {
            orbiting = false;
            panning = false;
        }
        float dx = in.deltaX;
        float dy = in.deltaY;
        if (orbiting) {
            cam.yaw -= dx * s.orbitSpeed;
            cam.pitch =
                    MathF.clamp(
                            cam.pitch + dy * s.orbitSpeed, MathF.toRadians(5), MathF.toRadians(89));
        } else if (panning) {
            // One pixel of mouse moves the target by one pixel's worth of ground at the target's
            // depth, along the camera's right and its forward projected onto the ground.
            float mpp =
                    cam.orthographic
                            ? cam.orthoHeight / lastWindowH
                            : GizmoMath.metersPerPixel(cam.fovY, cam.distance, lastWindowH);
            float sy = (float) Math.sin(cam.yaw);
            float cy = (float) Math.cos(cam.yaw);
            // right = (cos yaw, 0, -sin yaw); ground forward = (-sin yaw, 0, -cos yaw)
            float rxw = cy, rzw = -sy;
            float fxw = -sy, fzw = -cy;
            cam.target.x += (-dx * rxw + dy * fxw) * mpp;
            cam.target.z += (-dx * rzw + dy * fzw) * mpp;
        }
    }

    /** Builds the world ray through window point {@code (x, y)} into {@link #ray}. */
    private void computeRay(IsoCamera cam, float x, float y) {
        // The scene fills the window (the panels sit on top of it, D88), so normalized device
        // coordinates are relative to the window, not to the region.
        float ndcX = (x / lastWindowW) * 2 - 1;
        float ndcY = 1 - (y / lastWindowH) * 2;
        cam.screenRay(ndcX, ndcY, ray, qa);
        ray[3] = qa[0];
        ray[4] = qa[1];
        ray[5] = qa[2];
    }

    /** Declares the box-selection rectangle into {@code ui} while one is being dragged. */
    void declare(Ui ui) {
        if (!boxSelecting) {
            return;
        }
        box(tmp);
        ui.box()
                .floatingAt(tmp[0], tmp[1])
                .size(tmp[2], tmp[3])
                .background(0x30FFFFFF)
                .border(0xC0FFFFFF, 1)
                .noHit()
                .z(100);
    }

    /** Sizes the gizmo so it is {@code gizmoPixels} tall on screen whatever its distance. */
    private void gizmoScale(IsoCamera cam) {
        if (cam.orthographic) {
            metersPerPixel = cam.orthoHeight / lastWindowH;
        } else {
            // Depth of the center along the view direction (eye to target, normalized).
            float fx = cam.target.x - cam.eye().x;
            float fy = cam.target.y - cam.eye().y;
            float fz = cam.target.z - cam.eye().z;
            float inv = 1 / Math.max(1e-6f, (float) Math.sqrt(fx * fx + fy * fy + fz * fz));
            float depth =
                    (center[0] - cam.eye().x) * fx * inv
                            + (center[1] - cam.eye().y) * fy * inv
                            + (center[2] - cam.eye().z) * fz * inv;
            metersPerPixel = GizmoMath.metersPerPixel(cam.fovY, Math.max(0.1f, depth), lastWindowH);
        }
        gizmoLen = ctx.settings().gizmoPixels * metersPerPixel;
    }

    /** Frames the selection (or the level's placements when nothing is selected). */
    public void frameSelection() {
        LevelData level = ctx.level();
        if (level == null) {
            return;
        }
        Selection sel = ctx.selection();
        float cx = 0, cy = 0, cz = 0;
        int n = 0;
        if (sel.isEmpty()) {
            for (int i = 0; i < level.placementCount(); i++) {
                cx += level.placementFloat(i, 0);
                cy += level.placementFloat(i, 1);
                cz += level.placementFloat(i, 2);
                n++;
            }
        } else {
            for (int i = 0; i < sel.size(); i++) {
                LevelObjects.position(level, sel.get(i), tmp, 0);
                cx += tmp[0];
                cy += tmp[1];
                cz += tmp[2];
                n++;
            }
        }
        if (n == 0) {
            return;
        }
        cx /= n;
        cy /= n;
        cz /= n;
        float radius = 1;
        if (sel.isEmpty()) {
            for (int i = 0; i < level.placementCount(); i++) {
                float dx = level.placementFloat(i, 0) - cx;
                float dz = level.placementFloat(i, 2) - cz;
                radius = Math.max(radius, (float) Math.sqrt(dx * dx + dz * dz));
            }
        } else {
            for (int i = 0; i < sel.size(); i++) {
                LevelObjects.position(level, sel.get(i), tmp, 0);
                float dx = tmp[0] - cx;
                float dz = tmp[2] - cz;
                radius = Math.max(radius, (float) Math.sqrt(dx * dx + dz * dz) + 1.5f);
            }
        }
        IsoCamera cam = ctx.camera();
        cam.target.set(cx, cy, cz);
        if (cam.orthographic) {
            cam.orthoHeight = radius * 2.4f;
        } else {
            cam.distance = Math.max(3, radius / (float) Math.tan(cam.fovY * 0.5) * 1.1f);
        }
    }

    // ---- Picking ------------------------------------------------------------------------------

    /**
     * Returns the object under the ray: the nearest of the marker spheres (lights, spawns,
     * triggers) and the placement whose renderable {@link World#pick} hits first, or -1.
     */
    public int pick() {
        LevelData level = ctx.level();
        float best = Float.POSITIVE_INFINITY;
        int bestId = -1;
        if (ctx.settings().showMarkers) {
            for (int kind = LevelData.KIND_LIGHT; kind <= LevelData.KIND_TRIGGER; kind++) {
                int n =
                        kind == LevelData.KIND_LIGHT
                                ? level.lightCount()
                                : kind == LevelData.KIND_SPAWN
                                        ? level.spawnCount()
                                        : level.triggerCount();
                for (int i = 0; i < n; i++) {
                    int id =
                            kind == LevelData.KIND_LIGHT
                                    ? level.lightId(i)
                                    : kind == LevelData.KIND_SPAWN
                                            ? level.spawnId(i)
                                            : level.triggerId(i);
                    LevelObjects.position(level, id, tmp, 0);
                    float r = markerRadius(tmp[0], tmp[1], tmp[2]);
                    float t =
                            Intersect.raySphere(
                                    ray[0], ray[1], ray[2], ray[3], ray[4], ray[5], tmp[0], tmp[1],
                                    tmp[2], r);
                    if (t < best) {
                        best = t;
                        bestId = id;
                    }
                }
            }
        }
        World world = ctx.world();
        LevelLoader loader = ctx.loader();
        if (world != null && loader != null && loader.level() == level) {
            int entity = world.pick(ray[0], ray[1], ray[2], ray[3], ray[4], ray[5], best, outT);
            if (entity != Handles.NULL) {
                int id = loader.placementOf(world.groupOf(entity));
                if (id >= 0) {
                    bestId = id;
                }
            }
        }
        return bestId;
    }

    /** The world radius of a marker at {@code (x, y, z)}: {@code MARKER_PIXELS} on screen. */
    private float markerRadius(float x, float y, float z) {
        IsoCamera cam = ctx.camera();
        if (cam.orthographic) {
            return MARKER_PIXELS * cam.orthoHeight / lastWindowH;
        }
        float dx = x - cam.eye().x;
        float dy = y - cam.eye().y;
        float dz = z - cam.eye().z;
        float depth = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        return MARKER_PIXELS * GizmoMath.metersPerPixel(cam.fovY, depth, lastWindowH);
    }

    private void click(boolean shift, boolean ctrl) {
        int id = pick();
        Selection sel = ctx.selection();
        if (id < 0) {
            if (!shift && !ctrl) {
                sel.clear();
            }
        } else if (ctrl) {
            sel.toggle(id);
        } else if (shift) {
            sel.add(id);
        } else {
            sel.select(id);
        }
    }

    private void finishBox(boolean shift, boolean ctrl) {
        LevelData level = ctx.level();
        float x0 = Math.min(pressX, mouseX), x1 = Math.max(pressX, mouseX);
        float y0 = Math.min(pressY, mouseY), y1 = Math.max(pressY, mouseY);
        float[] vp = ctx.camera().viewProjection;
        boxHits.clear();
        for (int i = 0; i < level.placementCount(); i++) {
            boxTest(vp, level.placementId(i), x0, y0, x1, y1);
        }
        if (ctx.settings().showMarkers) {
            for (int i = 0; i < level.lightCount(); i++) {
                boxTest(vp, level.lightId(i), x0, y0, x1, y1);
            }
            for (int i = 0; i < level.spawnCount(); i++) {
                boxTest(vp, level.spawnId(i), x0, y0, x1, y1);
            }
            for (int i = 0; i < level.triggerCount(); i++) {
                boxTest(vp, level.triggerId(i), x0, y0, x1, y1);
            }
        }
        Selection sel = ctx.selection();
        if (!shift && !ctrl) {
            sel.clear();
        }
        for (int i = 0; i < boxHits.size(); i++) {
            if (ctrl) {
                sel.toggle(boxHits.get(i));
            } else {
                sel.add(boxHits.get(i));
            }
        }
    }

    private void boxTest(float[] vp, int id, float x0, float y0, float x1, float y1) {
        LevelObjects.position(ctx.level(), id, tmp, 0);
        if (GizmoMath.project(vp, tmp[0], tmp[1], tmp[2], 0, 0, lastWindowW, lastWindowH, tmp, 3)
                && tmp[3] >= x0
                && tmp[3] <= x1
                && tmp[4] >= y0
                && tmp[4] <= y1) {
            boxHits.add(id);
        }
    }

    // ---- Gizmo hit testing --------------------------------------------------------------------

    /** Returns the handle under the pointer for the current mode, or {@code NONE}. */
    private int hitTest(IsoCamera cam) {
        float[] vp = cam.viewProjection;
        if (!GizmoMath.project(
                vp, center[0], center[1], center[2], 0, 0, lastWindowW, lastWindowH, tmp, 0)) {
            return NONE;
        }
        float cpx = tmp[0], cpy = tmp[1];
        float tol = PICK_PIXELS;
        int best = NONE;
        float bestD = Float.POSITIVE_INFINITY;
        if (mode == MODE_SCALE) {
            float d = (float) Math.hypot(mouseX - cpx, mouseY - cpy);
            if (d <= tol * 1.5f) {
                return CENTER;
            }
        }
        if (mode == MODE_TRANSLATE) {
            // Plane squares between 0.3 L and 0.6 L along the two axes of each plane; a hit
            // beats the axes because the squares are smaller targets.
            for (int n = 0; n < 3; n++) {
                int u = (n + 1) % 3, v = (n + 2) % 3;
                if (!GizmoMath.rayPlanePoint(
                        ray[0],
                        ray[1],
                        ray[2],
                        ray[3],
                        ray[4],
                        ray[5],
                        center[0],
                        center[1],
                        center[2],
                        AXES[n][0],
                        AXES[n][1],
                        AXES[n][2],
                        tmp,
                        3)) {
                    continue;
                }
                float pu = dotAxis(tmp, 3, u);
                float pv = dotAxis(tmp, 3, v);
                if (pu >= 0.3f * gizmoLen
                        && pu <= 0.65f * gizmoLen
                        && pv >= 0.3f * gizmoLen
                        && pv <= 0.65f * gizmoLen) {
                    return PLANE_X + n;
                }
            }
        }
        if (mode == MODE_ROTATE) {
            // Rings: distance of the ray's hit on the ring's plane from the center, against L.
            for (int n = 0; n < 3; n++) {
                float facing =
                        Math.abs(ray[3] * AXES[n][0] + ray[4] * AXES[n][1] + ray[5] * AXES[n][2]);
                if (facing < 0.08f) {
                    continue; // edge-on: the ring is a line, leave it to the others
                }
                if (!GizmoMath.rayPlanePoint(
                        ray[0],
                        ray[1],
                        ray[2],
                        ray[3],
                        ray[4],
                        ray[5],
                        center[0],
                        center[1],
                        center[2],
                        AXES[n][0],
                        AXES[n][1],
                        AXES[n][2],
                        tmp,
                        3)) {
                    continue;
                }
                float dx = tmp[3] - center[0], dy = tmp[4] - center[1], dz = tmp[5] - center[2];
                float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                float d = Math.abs(dist - gizmoLen) / metersPerPixel; // in pixels
                if (d <= tol && d < bestD) {
                    bestD = d;
                    best = AXIS_X + n;
                }
            }
            return best;
        }
        // Axes: screen distance to the projected segment, skipping an axis seen end-on.
        for (int n = 0; n < 3; n++) {
            float along = Math.abs(ray[3] * AXES[n][0] + ray[4] * AXES[n][1] + ray[5] * AXES[n][2]);
            if (along > 0.995f) {
                continue;
            }
            if (!GizmoMath.project(
                    vp,
                    center[0] + AXES[n][0] * gizmoLen,
                    center[1] + AXES[n][1] * gizmoLen,
                    center[2] + AXES[n][2] * gizmoLen,
                    0,
                    0,
                    lastWindowW,
                    lastWindowH,
                    tmp,
                    3)) {
                continue;
            }
            float d = GizmoMath.pointSegmentDistance(mouseX, mouseY, cpx, cpy, tmp[3], tmp[4]);
            if (d <= tol && d < bestD) {
                bestD = d;
                best = AXIS_X + n;
            }
        }
        return best;
    }

    private float dotAxis(float[] p, int o, int axis) {
        return (p[o] - center[0]) * AXES[axis][0]
                + (p[o + 1] - center[1]) * AXES[axis][1]
                + (p[o + 2] - center[2]) * AXES[axis][2];
    }

    // ---- Dragging -----------------------------------------------------------------------------

    private void beginDrag(int handle) {
        Selection sel = ctx.selection();
        LevelData level = ctx.level();
        active = handle;
        dragPushed = false;
        String name =
                switch (mode) {
                    case MODE_TRANSLATE ->
                            sel.size() == 1 ? "Move" : "Move " + sel.size() + " objects";
                    case MODE_ROTATE ->
                            sel.size() == 1 ? "Rotate" : "Rotate " + sel.size() + " objects";
                    default -> sel.size() == 1 ? "Scale" : "Scale " + sel.size() + " objects";
                };
        drag = new TransformCommand(level, sel.ids().array(), sel.size(), name);
        System.arraycopy(center, 0, pivot, 0, 3);
        int axis = handle - (handle >= PLANE_X && handle <= PLANE_Z ? PLANE_X : AXIS_X);
        if (handle == CENTER) {
            GizmoMath.project(
                    ctx.camera().viewProjection,
                    pivot[0],
                    pivot[1],
                    pivot[2],
                    0,
                    0,
                    lastWindowW,
                    lastWindowH,
                    tmp,
                    0);
            startDist = Math.max(1, (float) Math.hypot(mouseX - tmp[0], mouseY - tmp[1]));
        } else if (mode == MODE_ROTATE || handle >= PLANE_X) {
            if (!GizmoMath.rayPlanePoint(
                    ray[0],
                    ray[1],
                    ray[2],
                    ray[3],
                    ray[4],
                    ray[5],
                    pivot[0],
                    pivot[1],
                    pivot[2],
                    AXES[axis][0],
                    AXES[axis][1],
                    AXES[axis][2],
                    startPoint,
                    0)) {
                System.arraycopy(pivot, 0, startPoint, 0, 3);
            }
        } else {
            startParam =
                    GizmoMath.closestAxisParam(
                            ray[0],
                            ray[1],
                            ray[2],
                            ray[3],
                            ray[4],
                            ray[5],
                            pivot[0],
                            pivot[1],
                            pivot[2],
                            AXES[axis][0],
                            AXES[axis][1],
                            AXES[axis][2],
                            0);
            if (mode == MODE_SCALE && Math.abs(startParam) < 1e-3f) {
                startParam = gizmoLen;
            }
        }
    }

    private void updateDrag(boolean ctrl) {
        EditorSettings s = ctx.settings();
        boolean snap = s.snap != ctrl; // Ctrl inverts
        int handle = active;
        int axis = handle - (handle >= PLANE_X && handle <= PLANE_Z ? PLANE_X : AXIS_X);
        float[] a = handle == CENTER ? AXES[0] : AXES[axis];
        switch (mode) {
            case MODE_TRANSLATE -> {
                if (handle >= PLANE_X) {
                    if (!GizmoMath.rayPlanePoint(
                            ray[0], ray[1], ray[2], ray[3], ray[4], ray[5], pivot[0], pivot[1],
                            pivot[2], a[0], a[1], a[2], tmp, 0)) {
                        return;
                    }
                    float dx = tmp[0] - startPoint[0];
                    float dy = tmp[1] - startPoint[1];
                    float dz = tmp[2] - startPoint[2];
                    if (snap) {
                        dx = GizmoMath.snap(dx, s.translateStep);
                        dy = GizmoMath.snap(dy, s.translateStep);
                        dz = GizmoMath.snap(dz, s.translateStep);
                    }
                    writeTranslate(dx, dy, dz);
                } else {
                    float t =
                            GizmoMath.closestAxisParam(
                                    ray[0],
                                    ray[1],
                                    ray[2],
                                    ray[3],
                                    ray[4],
                                    ray[5],
                                    pivot[0],
                                    pivot[1],
                                    pivot[2],
                                    a[0],
                                    a[1],
                                    a[2],
                                    startParam);
                    float d = t - startParam;
                    if (snap) {
                        d = GizmoMath.snap(d, s.translateStep);
                    }
                    writeTranslate(a[0] * d, a[1] * d, a[2] * d);
                }
            }
            case MODE_ROTATE -> {
                if (!GizmoMath.rayPlanePoint(
                        ray[0], ray[1], ray[2], ray[3], ray[4], ray[5], pivot[0], pivot[1],
                        pivot[2], a[0], a[1], a[2], tmp, 0)) {
                    return;
                }
                float angle =
                        GizmoMath.signedAngle(
                                startPoint[0] - pivot[0],
                                startPoint[1] - pivot[1],
                                startPoint[2] - pivot[2],
                                tmp[0] - pivot[0],
                                tmp[1] - pivot[1],
                                tmp[2] - pivot[2],
                                a[0],
                                a[1],
                                a[2]);
                if (snap) {
                    angle = GizmoMath.snap(angle, MathF.toRadians(s.rotateStepDegrees));
                }
                writeRotate(a, angle);
            }
            default -> {
                float factor;
                if (handle == CENTER) {
                    GizmoMath.project(
                            ctx.camera().viewProjection,
                            pivot[0],
                            pivot[1],
                            pivot[2],
                            0,
                            0,
                            lastWindowW,
                            lastWindowH,
                            tmp,
                            0);
                    factor = (float) Math.hypot(mouseX - tmp[0], mouseY - tmp[1]) / startDist;
                } else {
                    float t =
                            GizmoMath.closestAxisParam(
                                    ray[0],
                                    ray[1],
                                    ray[2],
                                    ray[3],
                                    ray[4],
                                    ray[5],
                                    pivot[0],
                                    pivot[1],
                                    pivot[2],
                                    a[0],
                                    a[1],
                                    a[2],
                                    startParam);
                    factor = t / startParam;
                }
                if (snap) {
                    factor = GizmoMath.snap(factor, s.scaleStep);
                }
                factor = Math.max(0.01f, factor);
                if (handle == CENTER) {
                    writeScale(factor, factor, factor);
                } else {
                    writeScale(
                            axis == 0 ? factor : 1, axis == 1 ? factor : 1, axis == 2 ? factor : 1);
                }
            }
        }
        if (drag.changed()) {
            if (!dragPushed) {
                ctx.undo().push(drag);
                dragPushed = true;
            } else {
                drag.apply(ctx);
            }
        }
    }

    private void endDrag() {
        active = NONE;
        drag = null;
        dragPushed = false;
    }

    /** Every object moves by the same delta. */
    private void writeTranslate(float dx, float dy, float dz) {
        float[] before = drag.before();
        float[] after = drag.after();
        for (int i = 0; i < drag.count(); i++) {
            int o = drag.offset(i);
            after[o] = before[o] + dx;
            after[o + 1] = before[o + 1] + dy;
            after[o + 2] = before[o + 2] + dz;
        }
        center[0] = pivot[0] + dx;
        center[1] = pivot[1] + dy;
        center[2] = pivot[2] + dz;
    }

    /** Every object turns by {@code angle} about {@code axis} through the pivot (the primary). */
    private void writeRotate(float[] axis, float angle) {
        GizmoMath.axisAngle(axis[0], axis[1], axis[2], angle, qa, 0);
        float[] before = drag.before();
        float[] after = drag.after();
        LevelData level = ctx.level();
        for (int i = 0; i < drag.count(); i++) {
            int o = drag.offset(i);
            int kind = level.kindOf(drag.id(i));
            // Position: rotate the offset from the pivot.
            tmp[0] = before[o] - pivot[0];
            tmp[1] = before[o + 1] - pivot[1];
            tmp[2] = before[o + 2] - pivot[2];
            GizmoMath.rotate(qa, 0, tmp, 0, tmp, 3);
            after[o] = pivot[0] + tmp[3];
            after[o + 1] = pivot[1] + tmp[4];
            after[o + 2] = pivot[2] + tmp[5];
            switch (kind) {
                case LevelData.KIND_PLACEMENT -> {
                    GizmoMath.mul(qa, 0, before, o + 3, after, o + 3); // world turn, then the old
                    GizmoMath.normalize(after, o + 3);
                }
                case LevelData.KIND_SPAWN -> after[o + 3] = before[o + 3] + axis[1] * angle;
                case LevelData.KIND_TRIGGER -> after[o + 6] = before[o + 6] + axis[1] * angle;
                case LevelData.KIND_LIGHT -> GizmoMath.rotate(qa, 0, before, o + 3, after, o + 3);
                default -> {}
            }
        }
    }

    /** Placements scale their size, triggers their extents; offsets from the pivot scale too. */
    private void writeScale(float fx, float fy, float fz) {
        float[] before = drag.before();
        float[] after = drag.after();
        LevelData level = ctx.level();
        for (int i = 0; i < drag.count(); i++) {
            int o = drag.offset(i);
            int kind = level.kindOf(drag.id(i));
            after[o] = pivot[0] + (before[o] - pivot[0]) * fx;
            after[o + 1] = pivot[1] + (before[o + 1] - pivot[1]) * fy;
            after[o + 2] = pivot[2] + (before[o + 2] - pivot[2]) * fz;
            int so = LevelObjects.scaleOffset(kind);
            if (so >= 0) {
                after[o + so] = before[o + so] * fx;
                after[o + so + 1] = before[o + so + 1] * fy;
                after[o + so + 2] = before[o + so + 2] * fz;
            }
        }
    }

    // ---- Drawing ------------------------------------------------------------------------------

    /** Adds the grid, the markers and the gizmo to {@code packet} (after the game's own lines). */
    void extract(RenderPacket packet) {
        EditorSettings s = ctx.settings();
        LevelData level = ctx.level();
        if (s.showGrid) {
            drawGrid(packet, s.translateStep);
        }
        if (level == null) {
            return;
        }
        if (s.showMarkers) {
            drawMarkers(packet, level);
        }
        Selection sel = ctx.selection();
        if (sel.primary() >= 0 && LevelObjects.exists(level, sel.primary())) {
            packet.beginDebugOverlay();
            drawGizmo(packet);
        }
    }

    private void drawGrid(RenderPacket p, float step) {
        if (step <= 0) {
            return;
        }
        IsoCamera cam = ctx.camera();
        int half = 24;
        // Far away, a half-meter grid is a blur: coarsen it by powers of two until the drawn
        // square (48 steps) covers about twice the distance to the target.
        float span = cam.orthographic ? cam.orthoHeight : cam.distance;
        while (step * 2 * half < span * 2 && step < 1e5f) {
            step *= 2;
        }
        float cx = GizmoMath.snap(cam.target.x, step);
        float cz = GizmoMath.snap(cam.target.z, step);
        float y = 0.01f;
        float ext = half * step;
        for (int i = -half; i <= half; i++) {
            float x = cx + i * step;
            float z = cz + i * step;
            boolean majorX = Math.abs(x) < 1e-4f;
            boolean majorZ = Math.abs(z) < 1e-4f;
            p.addDebugLine(x, y, cz - ext, x, y, cz + ext, majorX ? COLOR_GRID_MAJOR : COLOR_GRID);
            p.addDebugLine(cx - ext, y, z, cx + ext, y, z, majorZ ? COLOR_GRID_MAJOR : COLOR_GRID);
        }
    }

    private void drawMarkers(RenderPacket p, LevelData level) {
        Selection sel = ctx.selection();
        for (int i = 0; i < level.lightCount(); i++) {
            int id = level.lightId(i);
            float x = level.lightFloat(i, 0),
                    y = level.lightFloat(i, 1),
                    z = level.lightFloat(i, 2);
            int c = sel.contains(id) ? COLOR_SELECTED : COLOR_LIGHT;
            float r = 0.25f;
            cross(p, x, y, z, r, c);
            if (level.lightType(i) == LevelData.LIGHT_SPOT) {
                float dx = level.lightFloat(i, 3),
                        dy = level.lightFloat(i, 4),
                        dz = level.lightFloat(i, 5);
                p.addDebugLine(x, y, z, x + dx * 1.5f, y + dy * 1.5f, z + dz * 1.5f, c);
            }
        }
        for (int i = 0; i < level.spawnCount(); i++) {
            int id = level.spawnId(i);
            float x = level.spawnFloat(i, 0),
                    y = level.spawnFloat(i, 1),
                    z = level.spawnFloat(i, 2);
            float yaw = level.spawnFloat(i, 3);
            int c = sel.contains(id) ? COLOR_SELECTED : COLOR_SPAWN;
            circle(p, x, y + 0.02f, z, 0.4f, 0, 1, 0, c);
            // Facing: yaw turns +Z toward +X (right-handed about Y: forward = (sin yaw, 0, cos
            // yaw)).
            p.addDebugLine(
                    x,
                    y + 0.02f,
                    z,
                    x + (float) Math.sin(yaw) * 0.7f,
                    y + 0.02f,
                    z + (float) Math.cos(yaw) * 0.7f,
                    c);
            p.addDebugLine(x, y, z, x, y + 1.8f, z, c);
        }
        for (int i = 0; i < level.triggerCount(); i++) {
            int id = level.triggerId(i);
            int c = sel.contains(id) ? COLOR_SELECTED : COLOR_TRIGGER;
            orientedBox(
                    p,
                    level.triggerFloat(i, 0),
                    level.triggerFloat(i, 1),
                    level.triggerFloat(i, 2),
                    level.triggerFloat(i, 3) * 0.5f,
                    level.triggerFloat(i, 4) * 0.5f,
                    level.triggerFloat(i, 5) * 0.5f,
                    level.triggerFloat(i, 6),
                    c);
        }
    }

    private void drawGizmo(RenderPacket p) {
        float L = gizmoLen;
        float cx = center[0], cy = center[1], cz = center[2];
        int hot = active != NONE ? active : hover;
        switch (mode) {
            case MODE_TRANSLATE -> {
                for (int n = 0; n < 3; n++) {
                    int c = hot == AXIS_X + n ? COLOR_HOT : AXIS_COLORS[n];
                    float ax = AXES[n][0], ay = AXES[n][1], az = AXES[n][2];
                    thick(p, cx, cy, cz, cx + ax * L, cy + ay * L, cz + az * L, c);
                    arrowHead(p, n, L, c);
                }
                for (int n = 0; n < 3; n++) {
                    int u = (n + 1) % 3, v = (n + 2) % 3;
                    int c = hot == PLANE_X + n ? COLOR_HOT : AXIS_COLORS[n];
                    float a = 0.3f * L, b = 0.65f * L;
                    square(p, u, v, a, b, c);
                }
            }
            case MODE_ROTATE -> {
                for (int n = 0; n < 3; n++) {
                    int c = hot == AXIS_X + n ? COLOR_HOT : AXIS_COLORS[n];
                    circle(p, cx, cy, cz, L, AXES[n][0], AXES[n][1], AXES[n][2], c);
                }
                if (active != NONE) {
                    // The lever from the pivot to the drag start and to the pointer.
                    p.addDebugLine(
                            pivot[0],
                            pivot[1],
                            pivot[2],
                            startPoint[0],
                            startPoint[1],
                            startPoint[2],
                            COLOR_HOT);
                }
            }
            default -> {
                for (int n = 0; n < 3; n++) {
                    int c = hot == AXIS_X + n ? COLOR_HOT : AXIS_COLORS[n];
                    float ax = AXES[n][0], ay = AXES[n][1], az = AXES[n][2];
                    thick(p, cx, cy, cz, cx + ax * L, cy + ay * L, cz + az * L, c);
                    float s = L * 0.07f;
                    cube(p, cx + ax * L, cy + ay * L, cz + az * L, s, c);
                }
                cube(p, cx, cy, cz, L * 0.09f, hot == CENTER ? COLOR_HOT : 0xFFE0E0E0);
            }
        }
    }

    /** A line drawn three times, offset by one pixel along the camera's right and up. */
    private void thick(
            RenderPacket p, float x0, float y0, float z0, float x1, float y1, float z1, int c) {
        float[] v = ctx.camera().view;
        float m = metersPerPixel;
        p.addDebugLine(x0, y0, z0, x1, y1, z1, c);
        // Row 0 of the view matrix is the camera's right in world space, row 1 its up.
        float rxw = v[0] * m, ryw = v[4] * m, rzw = v[8] * m;
        float uxw = v[1] * m, uyw = v[5] * m, uzw = v[9] * m;
        p.addDebugLine(x0 + rxw, y0 + ryw, z0 + rzw, x1 + rxw, y1 + ryw, z1 + rzw, c);
        p.addDebugLine(x0 + uxw, y0 + uyw, z0 + uzw, x1 + uxw, y1 + uyw, z1 + uzw, c);
    }

    private void arrowHead(RenderPacket p, int axis, float L, int c) {
        int u = (axis + 1) % 3, v = (axis + 2) % 3;
        float tipX = center[0] + AXES[axis][0] * L;
        float tipY = center[1] + AXES[axis][1] * L;
        float tipZ = center[2] + AXES[axis][2] * L;
        float back = L * 0.85f;
        float w = L * 0.05f;
        float bx = center[0] + AXES[axis][0] * back;
        float by = center[1] + AXES[axis][1] * back;
        float bz = center[2] + AXES[axis][2] * back;
        for (int k = 0; k < 4; k++) {
            float su = (k & 1) == 0 ? w : -w;
            float sv = (k & 2) == 0 ? w : -w;
            float px = bx + AXES[u][0] * su + AXES[v][0] * sv;
            float py = by + AXES[u][1] * su + AXES[v][1] * sv;
            float pz = bz + AXES[u][2] * su + AXES[v][2] * sv;
            p.addDebugLine(tipX, tipY, tipZ, px, py, pz, c);
        }
    }

    private void square(RenderPacket p, int u, int v, float a, float b, int c) {
        float[] U = AXES[u], V = AXES[v];
        float cx = center[0], cy = center[1], cz = center[2];
        // Corners (a,a) (b,a) (b,b) (a,b) in the (u, v) plane.
        float x0 = cx + U[0] * a + V[0] * a,
                y0 = cy + U[1] * a + V[1] * a,
                z0 = cz + U[2] * a + V[2] * a;
        float x1 = cx + U[0] * b + V[0] * a,
                y1 = cy + U[1] * b + V[1] * a,
                z1 = cz + U[2] * b + V[2] * a;
        float x2 = cx + U[0] * b + V[0] * b,
                y2 = cy + U[1] * b + V[1] * b,
                z2 = cz + U[2] * b + V[2] * b;
        float x3 = cx + U[0] * a + V[0] * b,
                y3 = cy + U[1] * a + V[1] * b,
                z3 = cz + U[2] * a + V[2] * b;
        p.addDebugLine(x0, y0, z0, x1, y1, z1, c);
        p.addDebugLine(x1, y1, z1, x2, y2, z2, c);
        p.addDebugLine(x2, y2, z2, x3, y3, z3, c);
        p.addDebugLine(x3, y3, z3, x0, y0, z0, c);
    }

    /**
     * A circle of radius {@code r} around {@code (cx, cy, cz)} in the plane normal to {@code n}.
     */
    private static void circle(
            RenderPacket p,
            float cx,
            float cy,
            float cz,
            float r,
            float nx,
            float ny,
            float nz,
            int c) {
        // Two unit vectors spanning the plane: u = n × (the axis least aligned with n), v = n × u.
        float ax = Math.abs(nx) < 0.9f ? 1 : 0;
        float ay = ax == 0 ? 1 : 0;
        float ux = ny * 0 - nz * ay, uy = nz * ax - nx * 0, uz = nx * ay - ny * ax;
        float inv = 1 / (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
        ux *= inv;
        uy *= inv;
        uz *= inv;
        float vx = ny * uz - nz * uy, vy = nz * ux - nx * uz, vz = nx * uy - ny * ux;
        float px = cx + ux * r, py = cy + uy * r, pz = cz + uz * r;
        for (int i = 1; i <= RING_SEGMENTS; i++) {
            double a = 2 * Math.PI * i / RING_SEGMENTS;
            float ca = (float) Math.cos(a) * r, sa = (float) Math.sin(a) * r;
            float qx = cx + ux * ca + vx * sa,
                    qy = cy + uy * ca + vy * sa,
                    qz = cz + uz * ca + vz * sa;
            p.addDebugLine(px, py, pz, qx, qy, qz, c);
            px = qx;
            py = qy;
            pz = qz;
        }
    }

    private static void cross(RenderPacket p, float x, float y, float z, float r, int c) {
        p.addDebugLine(x - r, y, z, x + r, y, z, c);
        p.addDebugLine(x, y - r, z, x, y + r, z, c);
        p.addDebugLine(x, y, z - r, x, y, z + r, c);
    }

    private static void cube(RenderPacket p, float x, float y, float z, float h, int c) {
        orientedBox(p, x, y, z, h, h, h, 0, c);
    }

    /** The 12 edges of a box of half extents {@code (hx, hy, hz)} turned by {@code yaw} about Y. */
    private static void orientedBox(
            RenderPacket p,
            float cx,
            float cy,
            float cz,
            float hx,
            float hy,
            float hz,
            float yaw,
            int c) {
        float s = (float) Math.sin(yaw), co = (float) Math.cos(yaw);
        for (int e = 0; e < 12; e++) {
            // Edge e joins corners k and k' differing in one bit.
            int k0 = EDGE_A[e], k1 = EDGE_B[e];
            float x0 = ((k0 & 1) == 0 ? -hx : hx),
                    y0 = ((k0 & 2) == 0 ? -hy : hy),
                    z0 = ((k0 & 4) == 0 ? -hz : hz);
            float x1 = ((k1 & 1) == 0 ? -hx : hx),
                    y1 = ((k1 & 2) == 0 ? -hy : hy),
                    z1 = ((k1 & 4) == 0 ? -hz : hz);
            p.addDebugLine(
                    cx + x0 * co + z0 * s,
                    cy + y0,
                    cz - x0 * s + z0 * co,
                    cx + x1 * co + z1 * s,
                    cy + y1,
                    cz - x1 * s + z1 * co,
                    c);
        }
    }

    private static final int[] EDGE_A = {0, 1, 3, 2, 4, 5, 7, 6, 0, 1, 2, 3};
    private static final int[] EDGE_B = {1, 3, 2, 0, 5, 7, 6, 4, 4, 5, 6, 7};
}
