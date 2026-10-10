package dev.jeddy.physics.query;

import dev.jeddy.core.collections.IntList;
import dev.jeddy.math.Intersect;
import dev.jeddy.physics.bvh.Bvh;
import dev.jeddy.physics.bvh.RayHit;
import dev.jeddy.physics.grid.Bodies;
import dev.jeddy.physics.grid.UniformGrid;

/**
 * Collision queries over the static BVH and the dynamic bodies (DESIGN §11.3): rays (closest or
 * any), overlaps for the shapes spells and abilities use (sphere, vertical cylinder, cone, line,
 * yawed box, polygon prism), cylinder sweeps for kinematic movement, line of sight, and picking.
 * Static triangles are selected by {@code TriFlags} masks, bodies by layer masks. Results go into a
 * caller-owned {@link QueryContext} (single results) or {@link IntList} (entity lists); nothing
 * allocates.
 *
 * <p>Thread ownership: {@link #update} on the physics thread once per tick; queries from any number
 * of threads between updates, each with its own context.
 */
public final class CollisionWorld {
    /** Pick result: nothing (or blocked by a non-walkable surface). */
    public static final int PICK_NONE = 0;

    /** Pick result: a unit (body in the unit layers). */
    public static final int PICK_UNIT = 1;

    /** Pick result: an interactable (body in the interactable layers). */
    public static final int PICK_INTERACTABLE = 2;

    /** Pick result: walkable ground. */
    public static final int PICK_GROUND = 3;

    private static final float EPSILON = 1e-6f;

    private final Bvh bvh;
    private final Bodies bodies;
    private final UniformGrid grid;

    /** Creates a world over a static BVH ({@code null} for none), bodies and their grid. */
    public CollisionWorld(Bvh staticWorld, Bodies bodies, UniformGrid grid) {
        this.bvh = staticWorld;
        this.bodies = bodies;
        this.grid = grid;
    }

    /** Returns the static BVH, or {@code null}. */
    public Bvh staticWorld() {
        return bvh;
    }

    /** Returns the bodies. */
    public Bodies bodies() {
        return bodies;
    }

    /** Returns the broadphase grid. */
    public UniformGrid grid() {
        return grid;
    }

    /** Rebuilds the broadphase after the bodies moved (once per tick). */
    public void update() {
        grid.rebuild(bodies);
    }

    // Rays ------------------------------------------------------------------------------------

    /**
     * Closest hit along {@code o + t·d}, {@code t ∈ [0, maxT]}, among static triangles whose flags
     * share a bit with {@code triangleMask} and bodies whose layers share a bit with {@code
     * layerMask} (except {@code ignoreEntity}).
     */
    public boolean raycast(
            float ox,
            float oy,
            float oz,
            float dx,
            float dy,
            float dz,
            float maxT,
            int triangleMask,
            int layerMask,
            int ignoreEntity,
            QueryContext q) {
        return ray(
                ox, oy, oz, dx, dy, dz, maxT, triangleMask, layerMask, ignoreEntity, q, false, 0);
    }

    /** Like {@link #raycast} but returns as soon as anything is hit. */
    public boolean raycastAny(
            float ox,
            float oy,
            float oz,
            float dx,
            float dy,
            float dz,
            float maxT,
            int triangleMask,
            int layerMask,
            int ignoreEntity,
            QueryContext q) {
        return ray(ox, oy, oz, dx, dy, dz, maxT, triangleMask, layerMask, ignoreEntity, q, true, 0);
    }

    private boolean ray(
            float ox,
            float oy,
            float oz,
            float dx,
            float dy,
            float dz,
            float maxT,
            int triangleMask,
            int layerMask,
            int ignoreEntity,
            QueryContext q,
            boolean any,
            float unitPadding) {
        q.clear();
        float best = maxT;
        if (bvh != null && triangleMask != 0) {
            RayHit h = q.rayHit;
            boolean hit =
                    any
                            ? bvh.raycastAny(ox, oy, oz, dx, dy, dz, best, triangleMask, h)
                            : bvh.raycast(ox, oy, oz, dx, dy, dz, best, triangleMask, h);
            if (hit) {
                best = h.t;
                q.t = h.t;
                q.triangle = h.triangle;
                q.triangleFlags = h.flags;
                q.nx = h.nx;
                q.ny = h.ny;
                q.nz = h.nz;
                if (any) {
                    return point(q, ox, oy, oz, dx, dy, dz);
                }
            }
        }
        if (layerMask != 0 && bodies.count() > 0) {
            rayBodies(ox, oy, oz, dx, dy, dz, best, layerMask, ignoreEntity, q, any, unitPadding);
        }
        return q.hit() && point(q, ox, oy, oz, dx, dy, dz);
    }

    private static boolean point(
            QueryContext q, float ox, float oy, float oz, float dx, float dy, float dz) {
        q.x = ox + dx * q.t;
        q.y = oy + dy * q.t;
        q.z = oz + dz * q.t;
        return true;
    }

    /** Walks the grid cells under the ray (2D DDA) and tests their bodies. */
    private void rayBodies(
            float ox,
            float oy,
            float oz,
            float dx,
            float dy,
            float dz,
            float maxT,
            int layerMask,
            int ignoreEntity,
            QueryContext q,
            boolean any,
            float padding) {
        UniformGrid g = grid;
        float cell = g.cellSize();
        int cx = g.cellX(ox);
        int cz = g.cellZ(oz);
        int stepX = dx > 0 ? 1 : -1;
        int stepZ = dz > 0 ? 1 : -1;
        float tDeltaX = dx == 0 ? Float.POSITIVE_INFINITY : cell / Math.abs(dx);
        float tDeltaZ = dz == 0 ? Float.POSITIVE_INFINITY : cell / Math.abs(dz);
        float boundaryX = g.minX() + (cx + (dx > 0 ? 1 : 0)) * cell;
        float boundaryZ = g.minZ() + (cz + (dz > 0 ? 1 : 0)) * cell;
        float tNextX = dx == 0 ? Float.POSITIVE_INFINITY : Math.max(0, (boundaryX - ox) / dx);
        float tNextZ = dz == 0 ? Float.POSITIVE_INFINITY : Math.max(0, (boundaryZ - oz) / dz);
        float best = q.hit() ? q.t : maxT;
        int[] entries = g.entries();
        Bodies b = bodies;
        while (true) {
            for (int e = g.cellBegin(cx, cz), end = g.cellEnd(cx, cz); e < end; e++) {
                int i = entries[e];
                if ((g.layersOf(i) & layerMask) == 0 || b.entity[i] == ignoreEntity) {
                    continue;
                }
                float t = rayBody(i, ox, oy, oz, dx, dy, dz, best, padding, q.scratch);
                if (t < best) {
                    best = t;
                    q.t = t;
                    q.body = i;
                    q.entity = b.entity[i];
                    q.triangle = -1;
                    q.triangleFlags = 0;
                    q.nx = q.scratch[0];
                    q.ny = q.scratch[1];
                    q.nz = q.scratch[2];
                    if (any) {
                        return;
                    }
                }
            }
            float exit = Math.min(tNextX, tNextZ);
            if (best <= exit || exit > maxT) {
                return; // nothing beyond this cell can be nearer
            }
            if (tNextX < tNextZ) {
                cx += stepX;
                if (cx < 0 || cx >= g.cellsX()) {
                    return;
                }
                tNextX += tDeltaX;
            } else {
                cz += stepZ;
                if (cz < 0 || cz >= g.cellsZ()) {
                    return;
                }
                tNextZ += tDeltaZ;
            }
        }
    }

    /** Ray against body {@code i}; writes the hit normal into {@code n[0..3)}. */
    private float rayBody(
            int i,
            float ox,
            float oy,
            float oz,
            float dx,
            float dy,
            float dz,
            float maxT,
            float padding,
            float[] n) {
        Bodies b = bodies;
        float t;
        switch (b.shape[i]) {
            case Bodies.SPHERE -> {
                float r = b.radius[i] + padding;
                t = Intersect.raySphere(ox, oy, oz, dx, dy, dz, b.x[i], b.y[i], b.z[i], r);
                if (t <= maxT) {
                    n[0] = (ox + dx * t - b.x[i]) / r;
                    n[1] = (oy + dy * t - b.y[i]) / r;
                    n[2] = (oz + dz * t - b.z[i]) / r;
                }
            }
            case Bodies.CYLINDER -> {
                float r = b.radius[i] + padding;
                float top = b.y[i] + b.height[i];
                t =
                        Intersect.rayVerticalCylinder(
                                ox, oy, oz, dx, dy, dz, b.x[i], b.z[i], r, b.y[i], top);
                if (t <= maxT) {
                    float py = oy + dy * t;
                    if (py >= top - 1e-4f) {
                        n[0] = 0;
                        n[1] = 1;
                        n[2] = 0;
                    } else if (py <= b.y[i] + 1e-4f) {
                        n[0] = 0;
                        n[1] = -1;
                        n[2] = 0;
                    } else {
                        n[0] = (ox + dx * t - b.x[i]) / r;
                        n[1] = 0;
                        n[2] = (oz + dz * t - b.z[i]) / r;
                    }
                }
            }
            default -> {
                // Into the box's frame: x' = c·x − s·z, z' = s·x + c·z (yaw about +Y).
                float c = (float) Math.cos(b.yaw[i]);
                float s = (float) Math.sin(b.yaw[i]);
                float px = ox - b.x[i];
                float pz = oz - b.z[i];
                float lx = c * px - s * pz;
                float lz = s * px + c * pz;
                float ldx = c * dx - s * dz;
                float ldz = s * dx + c * dz;
                float hx = b.halfX[i] + padding;
                float hz = b.halfZ[i] + padding;
                t =
                        Intersect.rayAabb(
                                lx,
                                oy - b.y[i],
                                lz,
                                ldx,
                                dy,
                                ldz,
                                -hx,
                                0,
                                -hz,
                                hx,
                                b.height[i],
                                hz,
                                maxT);
                if (t <= maxT) {
                    float hxp = lx + ldx * t;
                    float hyp = oy - b.y[i] + dy * t;
                    float hzp = lz + ldz * t;
                    float ex = Math.abs(Math.abs(hxp) - hx);
                    float ey = Math.min(Math.abs(hyp), Math.abs(hyp - b.height[i]));
                    float ez = Math.abs(Math.abs(hzp) - hz);
                    float nlx = 0;
                    float nlz = 0;
                    n[1] = 0;
                    if (ey <= ex && ey <= ez) {
                        n[1] = hyp > b.height[i] * 0.5f ? 1 : -1;
                    } else if (ex <= ez) {
                        nlx = Math.signum(hxp);
                    } else {
                        nlz = Math.signum(hzp);
                    }
                    // Back to the world: x = c·x' + s·z', z = −s·x' + c·z'.
                    n[0] = c * nlx + s * nlz;
                    n[2] = -s * nlx + c * nlz;
                }
            }
        }
        return t;
    }

    // Overlaps --------------------------------------------------------------------------------

    /** Appends the entities of bodies touching a sphere. */
    public int overlapSphere(
            float cx, float cy, float cz, float r, int layerMask, IntList out, QueryContext q) {
        IntList c = candidates(cx - r, cz - r, cx + r, cz + r, layerMask, q);
        int added = 0;
        for (int k = 0; k < c.size(); k++) {
            int i = c.get(k);
            if (bodySphere(i, cx, cy, cz, r)) {
                out.add(bodies.entity[i]);
                added++;
            }
        }
        return added;
    }

    /**
     * Appends the entities of bodies touching a vertical cylinder standing at {@code (x, y, z)}.
     */
    public int overlapCylinder(
            float x,
            float y,
            float z,
            float r,
            float h,
            int layerMask,
            IntList out,
            QueryContext q) {
        IntList c = candidates(x - r, z - r, x + r, z + r, layerMask, q);
        int added = 0;
        for (int k = 0; k < c.size(); k++) {
            int i = c.get(k);
            if (overlapsY(i, y, y + h) && bodyCircle(i, x, z, r, y, y + h)) {
                out.add(bodies.entity[i]);
                added++;
            }
        }
        return added;
    }

    /**
     * Appends the entities of bodies touching a cone template seen from above: apex {@code (x, z)},
     * unit direction {@code (dirX, dirZ)}, {@code length}, half-angle in radians, between heights
     * {@code yMin} and {@code yMax}. Boxes count by their bounding circle.
     */
    public int overlapCone(
            float x,
            float z,
            float dirX,
            float dirZ,
            float length,
            float halfAngle,
            float yMin,
            float yMax,
            int layerMask,
            IntList out,
            QueryContext q) {
        IntList c = candidates(x - length, z - length, x + length, z + length, layerMask, q);
        float cos = (float) Math.cos(halfAngle);
        float sin = (float) Math.sin(halfAngle);
        int added = 0;
        Bodies b = bodies;
        for (int k = 0; k < c.size(); k++) {
            int i = c.get(k);
            if (overlapsY(i, yMin, yMax)
                    && Intersect.sectorCircle(
                            x, z, dirX, dirZ, cos, sin, length, b.x[i], b.z[i], b.radius[i])) {
                out.add(b.entity[i]);
                added++;
            }
        }
        return added;
    }

    /**
     * Appends the entities of bodies touching a line template: from {@code (x0, z0)} to {@code (x1,
     * z1)}, {@code width} wide, between heights {@code yMin} and {@code yMax}. Boxes count by their
     * bounding circle.
     */
    public int overlapLine(
            float x0,
            float z0,
            float x1,
            float z1,
            float width,
            float yMin,
            float yMax,
            int layerMask,
            IntList out,
            QueryContext q) {
        float hw = width * 0.5f;
        IntList c =
                candidates(
                        Math.min(x0, x1) - hw,
                        Math.min(z0, z1) - hw,
                        Math.max(x0, x1) + hw,
                        Math.max(z0, z1) + hw,
                        layerMask,
                        q);
        float dx = x1 - x0;
        float dz = z1 - z0;
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        float ux = len > 0 ? dx / len : 1;
        float uz = len > 0 ? dz / len : 0;
        int added = 0;
        Bodies b = bodies;
        for (int k = 0; k < c.size(); k++) {
            int i = c.get(k);
            if (overlapsY(i, yMin, yMax)
                    && Intersect.rectCircle(x0, z0, ux, uz, len, hw, b.x[i], b.z[i], b.radius[i])) {
                out.add(b.entity[i]);
                added++;
            }
        }
        return added;
    }

    /**
     * Appends the entities of bodies touching a box standing at {@code (x, y, z)} with half extents
     * {@code hx}, {@code hz}, height {@code h}, turned by {@code yaw}.
     */
    public int overlapBox(
            float x,
            float y,
            float z,
            float hx,
            float hz,
            float h,
            float yaw,
            int layerMask,
            IntList out,
            QueryContext q) {
        float c = (float) Math.cos(yaw);
        float s = (float) Math.sin(yaw);
        float ex = Math.abs(c) * hx + Math.abs(s) * hz;
        float ez = Math.abs(s) * hx + Math.abs(c) * hz;
        IntList cand = candidates(x - ex, z - ez, x + ex, z + ez, layerMask, q);
        int added = 0;
        Bodies b = bodies;
        for (int k = 0; k < cand.size(); k++) {
            int i = cand.get(k);
            if (!overlapsY(i, y, y + h)) {
                continue;
            }
            boolean hit;
            if (b.shape[i] == Bodies.BOX) {
                hit = boxesOverlap(x, z, hx, hz, c, s, i);
            } else {
                // Body circle (at the box's height range) against the box, in the box's frame.
                float r = circleRadiusAt(i, y, y + h);
                float px = b.x[i] - x;
                float pz = b.z[i] - z;
                float lx = c * px - s * pz;
                float lz = s * px + c * pz;
                float qx = Math.clamp(lx, -hx, hx) - lx;
                float qz = Math.clamp(lz, -hz, hz) - lz;
                hit = qx * qx + qz * qz <= r * r;
            }
            if (hit) {
                out.add(b.entity[i]);
                added++;
            }
        }
        return added;
    }

    /**
     * Appends the entities of bodies touching a vertical prism: the polygon {@code xz[0..2n)} (any
     * simple polygon) between heights {@code yMin} and {@code yMax}. Boxes count by their bounding
     * circle.
     */
    public int overlapPrism(
            float[] xz, int n, float yMin, float yMax, int layerMask, IntList out, QueryContext q) {
        float lx = Float.MAX_VALUE,
                lz = Float.MAX_VALUE,
                hx = -Float.MAX_VALUE,
                hz = -Float.MAX_VALUE;
        for (int k = 0; k < n; k++) {
            lx = Math.min(lx, xz[k * 2]);
            hx = Math.max(hx, xz[k * 2]);
            lz = Math.min(lz, xz[k * 2 + 1]);
            hz = Math.max(hz, xz[k * 2 + 1]);
        }
        IntList c = candidates(lx, lz, hx, hz, layerMask, q);
        int added = 0;
        Bodies b = bodies;
        for (int k = 0; k < c.size(); k++) {
            int i = c.get(k);
            if (!overlapsY(i, yMin, yMax)) {
                continue;
            }
            float r = circleRadiusAt(i, yMin, yMax);
            boolean hit = dev.jeddy.math.Poly2.contains(xz, n, b.x[i], b.z[i]);
            for (int e = 0; e < n && !hit; e++) {
                int f = (e + 1) % n;
                hit =
                        Intersect.segmentCircle(
                                xz[e * 2],
                                xz[e * 2 + 1],
                                xz[f * 2],
                                xz[f * 2 + 1],
                                b.x[i],
                                b.z[i],
                                r);
            }
            if (hit) {
                out.add(b.entity[i]);
                added++;
            }
        }
        return added;
    }

    private IntList candidates(
            float x0, float z0, float x1, float z1, int layerMask, QueryContext q) {
        IntList c = q.candidates;
        c.clear();
        grid.query(x0, z0, x1, z1, layerMask, c);
        return c;
    }

    private boolean overlapsY(int i, float yMin, float yMax) {
        return bodies.bottom(i) <= yMax && bodies.top(i) >= yMin;
    }

    /** Radius of body {@code i}'s horizontal section within {@code [yMin, yMax]}. */
    private float circleRadiusAt(int i, float yMin, float yMax) {
        Bodies b = bodies;
        if (b.shape[i] != Bodies.SPHERE) {
            return b.radius[i];
        }
        float y = Math.clamp(b.y[i], yMin, yMax);
        float dy = y - b.y[i];
        float r2 = b.radius[i] * b.radius[i] - dy * dy;
        return r2 > 0 ? (float) Math.sqrt(r2) : 0;
    }

    /** Body {@code i}'s footprint (at heights {@code [yMin, yMax]}) against a circle. */
    private boolean bodyCircle(int i, float x, float z, float r, float yMin, float yMax) {
        Bodies b = bodies;
        if (b.shape[i] == Bodies.BOX) {
            float c = (float) Math.cos(b.yaw[i]);
            float s = (float) Math.sin(b.yaw[i]);
            float px = x - b.x[i];
            float pz = z - b.z[i];
            float lx = c * px - s * pz;
            float lz = s * px + c * pz;
            float qx = Math.clamp(lx, -b.halfX[i], b.halfX[i]) - lx;
            float qz = Math.clamp(lz, -b.halfZ[i], b.halfZ[i]) - lz;
            return qx * qx + qz * qz <= r * r;
        }
        float br = circleRadiusAt(i, yMin, yMax);
        return Intersect.circleCircle(x, z, r, b.x[i], b.z[i], br);
    }

    private boolean bodySphere(int i, float cx, float cy, float cz, float r) {
        Bodies b = bodies;
        switch (b.shape[i]) {
            case Bodies.SPHERE -> {
                float dx = cx - b.x[i];
                float dy = cy - b.y[i];
                float dz = cz - b.z[i];
                float rr = r + b.radius[i];
                return dx * dx + dy * dy + dz * dz <= rr * rr;
            }
            case Bodies.CYLINDER -> {
                float dy = cy - Math.clamp(cy, b.y[i], b.y[i] + b.height[i]);
                float dx = cx - b.x[i];
                float dz = cz - b.z[i];
                float d = (float) Math.sqrt(dx * dx + dz * dz);
                float radial = Math.max(0, d - b.radius[i]);
                return radial * radial + dy * dy <= r * r;
            }
            default -> {
                float c = (float) Math.cos(b.yaw[i]);
                float s = (float) Math.sin(b.yaw[i]);
                float px = cx - b.x[i];
                float pz = cz - b.z[i];
                float lx = c * px - s * pz;
                float lz = s * px + c * pz;
                float qx = Math.clamp(lx, -b.halfX[i], b.halfX[i]) - lx;
                float qy = Math.clamp(cy - b.y[i], 0, b.height[i]) - (cy - b.y[i]);
                float qz = Math.clamp(lz, -b.halfZ[i], b.halfZ[i]) - lz;
                return qx * qx + qy * qy + qz * qz <= r * r;
            }
        }
    }

    /** Separating-axis test of two yawed rectangles in XZ. */
    private boolean boxesOverlap(float x, float z, float hx, float hz, float c, float s, int i) {
        Bodies b = bodies;
        float c2 = (float) Math.cos(b.yaw[i]);
        float s2 = (float) Math.sin(b.yaw[i]);
        // Axes: each box's local x and z in world coordinates.
        float[] ax = {c, s, c2, s2};
        float[] az = {-s, c, -s2, c2};
        float tx = b.x[i] - x;
        float tz = b.z[i] - z;
        for (int k = 0; k < 4; k++) {
            float axisX = ax[k];
            float axisZ = az[k];
            float d = Math.abs(tx * axisX + tz * axisZ);
            float ra = hx * Math.abs(c * axisX - s * axisZ) + hz * Math.abs(s * axisX + c * axisZ);
            float rb =
                    b.halfX[i] * Math.abs(c2 * axisX - s2 * axisZ)
                            + b.halfZ[i] * Math.abs(s2 * axisX + c2 * axisZ);
            if (d > ra + rb) {
                return false;
            }
        }
        return true;
    }

    // Sweeps ----------------------------------------------------------------------------------

    /**
     * Sweeps a vertical cylinder standing at {@code (x, y, z)} horizontally by {@code (dx, dz)}:
     * static triangles in {@code triangleMask} whose height range reaches into {@code [y +
     * stepHeight, y + h]} (walls, not floors or steps) and bodies in {@code layerMask} (except
     * {@code ignoreEntity}) stop it. Shapes it already overlaps do not (it can move out).
     *
     * @return {@code true} if blocked: {@code q.t} is the fraction of the motion that is free,
     *     {@code q.nx, q.nz} the contact normal (for sliding)
     */
    public boolean sweepCylinder(
            float x,
            float y,
            float z,
            float r,
            float h,
            float stepHeight,
            float dx,
            float dz,
            int triangleMask,
            int layerMask,
            int ignoreEntity,
            QueryContext q) {
        q.clear();
        q.t = Float.POSITIVE_INFINITY;
        float best = 1;
        float yLow = y + stepHeight;
        float yHigh = y + h;
        float minX = Math.min(x, x + dx) - r;
        float maxX = Math.max(x, x + dx) + r;
        float minZ = Math.min(z, z + dz) - r;
        float maxZ = Math.max(z, z + dz) + r;
        float[] n = q.scratch;
        if (bvh != null && triangleMask != 0) {
            SweepVisitor v = q.sweep;
            v.set(bvh, x, z, dx, dz, r, yLow, yHigh);
            bvh.overlapBox(minX, yLow, minZ, maxX, yHigh, maxZ, triangleMask, q.stack, v);
            if (v.best < best) {
                best = v.best;
                q.t = v.best;
                q.triangle = v.triangle;
                q.triangleFlags = bvh.triangleFlags(v.triangle);
                q.nx = v.nx;
                q.ny = 0;
                q.nz = v.nz;
            }
        }
        if (layerMask != 0) {
            IntList c = candidates(minX, minZ, maxX, maxZ, layerMask, q);
            Bodies b = bodies;
            for (int k = 0; k < c.size(); k++) {
                int i = c.get(k);
                if (b.entity[i] == ignoreEntity || !overlapsY(i, yLow, yHigh)) {
                    continue;
                }
                float t;
                if (b.shape[i] == Bodies.BOX) {
                    t = sweepCircleBox(i, x, z, dx, dz, r, best, n);
                } else {
                    float rr = r + circleRadiusAt(i, yLow, yHigh);
                    t = sweepCircleCircle(x, z, dx, dz, b.x[i], b.z[i], rr, n);
                }
                if (t < best) {
                    best = t;
                    q.t = t;
                    q.body = i;
                    q.entity = b.entity[i];
                    q.triangle = -1;
                    q.nx = n[0];
                    q.ny = 0;
                    q.nz = n[1];
                }
            }
        }
        if (!q.hit()) {
            return false;
        }
        q.x = x + dx * q.t;
        q.y = y;
        q.z = z + dz * q.t;
        return true;
    }

    /** Moving point vs circle of radius {@code rr}; normal into {@code n[0], n[1]}. */
    static float sweepCircleCircle(
            float x, float z, float dx, float dz, float cx, float cz, float rr, float[] n) {
        float mx = x - cx;
        float mz = z - cz;
        float c = mx * mx + mz * mz - rr * rr;
        float bb = mx * dx + mz * dz;
        if (c <= 0 || bb >= 0) {
            return Float.POSITIVE_INFINITY; // overlapping already, or moving away
        }
        float a = dx * dx + dz * dz;
        float disc = bb * bb - a * c;
        if (disc < 0 || a < EPSILON) {
            return Float.POSITIVE_INFINITY;
        }
        float t = (float) ((-bb - Math.sqrt(disc)) / a);
        if (t < 0 || t > 1) {
            return Float.POSITIVE_INFINITY;
        }
        n[0] = (mx + dx * t) / rr;
        n[1] = (mz + dz * t) / rr;
        return t;
    }

    /** Moving point vs 2D capsule (segment a–b inflated by r). */
    static float sweepCircleSegment(
            float x,
            float z,
            float dx,
            float dz,
            float ax,
            float az,
            float bx,
            float bz,
            float r,
            float[] n) {
        float ex = bx - ax;
        float ez = bz - az;
        float len2 = ex * ex + ez * ez;
        // Already touching: no contact (can move out).
        float d2 = Intersect.pointSegmentDistanceSquared2(x, z, ax, az, bx, bz);
        if (d2 <= r * r) {
            return Float.POSITIVE_INFINITY;
        }
        float best = Float.POSITIVE_INFINITY;
        float bnx = 0;
        float bnz = 0;
        if (len2 > EPSILON) {
            float len = (float) Math.sqrt(len2);
            float nx = -ez / len;
            float nz = ex / len;
            float s = (x - ax) * nx + (z - az) * nz;
            float ds = dx * nx + dz * nz;
            if (s * ds < 0) {
                float t = (Math.abs(s) - r) / Math.abs(ds);
                if (t >= 0 && t <= 1) {
                    float px = x + dx * t - ax;
                    float pz = z + dz * t - az;
                    float along = (px * ex + pz * ez) / len2;
                    if (along >= 0 && along <= 1) {
                        best = t;
                        float sign = Math.signum(s);
                        bnx = nx * sign;
                        bnz = nz * sign;
                    }
                }
            }
        }
        float ta = sweepCircleCircle(x, z, dx, dz, ax, az, r, n);
        if (ta < best) {
            best = ta;
            bnx = n[0];
            bnz = n[1];
        }
        float tb = sweepCircleCircle(x, z, dx, dz, bx, bz, r, n);
        if (tb < best) {
            best = tb;
            bnx = n[0];
            bnz = n[1];
        }
        n[0] = bnx;
        n[1] = bnz;
        return best;
    }

    private float sweepCircleBox(
            int i, float x, float z, float dx, float dz, float r, float maxT, float[] n) {
        Bodies b = bodies;
        float c = (float) Math.cos(b.yaw[i]);
        float s = (float) Math.sin(b.yaw[i]);
        float px = x - b.x[i];
        float pz = z - b.z[i];
        float lx = c * px - s * pz;
        float lz = s * px + c * pz;
        float ldx = c * dx - s * dz;
        float ldz = s * dx + c * dz;
        float hx = b.halfX[i];
        float hz = b.halfZ[i];
        float best = maxT;
        float bnx = 0;
        float bnz = 0;
        for (int e = 0; e < 4; e++) {
            // Corners counter-clockwise: (-hx,-hz), (hx,-hz), (hx,hz), (-hx,hz).
            float ax = (e == 1 || e == 2) ? hx : -hx;
            float az = e >= 2 ? hz : -hz;
            float bx = (e == 0 || e == 1) ? hx : -hx;
            float bz = (e == 1 || e == 2) ? hz : -hz;
            float t = sweepCircleSegment(lx, lz, ldx, ldz, ax, az, bx, bz, r, n);
            if (t < best) {
                best = t;
                bnx = n[0];
                bnz = n[1];
            }
        }
        // Normal back to the world.
        n[0] = c * bnx + s * bnz;
        n[1] = -s * bnx + c * bnz;
        return best;
    }

    /** Sweeps against static triangles projected onto the XZ plane. */
    static final class SweepVisitor implements Bvh.TriangleVisitor {
        private Bvh bvh;
        private float x;
        private float z;
        private float dx;
        private float dz;
        private float r;
        private float yLow;
        private float yHigh;
        private final float[] a = new float[3];
        private final float[] b = new float[3];
        private final float[] c = new float[3];
        private final float[] n = new float[2];
        float best;
        int triangle;
        float nx;
        float nz;

        void set(
                Bvh tree,
                float px,
                float pz,
                float mx,
                float mz,
                float radius,
                float low,
                float high) {
            bvh = tree;
            x = px;
            z = pz;
            dx = mx;
            dz = mz;
            r = radius;
            yLow = low;
            yHigh = high;
            best = 1;
            triangle = -1;
        }

        @Override
        public boolean visit(int t) {
            bvh.vertex(t, 0, a);
            bvh.vertex(t, 1, b);
            bvh.vertex(t, 2, c);
            float lo = Math.min(a[1], Math.min(b[1], c[1]));
            float hi = Math.max(a[1], Math.max(b[1], c[1]));
            if (hi < yLow || lo > yHigh) {
                return true;
            }
            sweepEdge(t, a, b);
            sweepEdge(t, b, c);
            sweepEdge(t, c, a);
            return true;
        }

        private void sweepEdge(int t, float[] p, float[] q) {
            float time = sweepCircleSegment(x, z, dx, dz, p[0], p[2], q[0], q[2], r, n);
            if (time < best) {
                best = time;
                triangle = t;
                nx = n[0];
                nz = n[1];
            }
        }
    }

    // Line of sight and picking ---------------------------------------------------------------

    /**
     * Returns whether the eye at {@code (ax, ay + eyeA, az)} sees the eye at {@code (bx, by + eyeB,
     * bz)}: no static triangle in {@code triangleMask} (normally {@code BLOCKS_LOS}) and no body in
     * {@code layerMask} other than the two entities lies between them.
     */
    public boolean lineOfSight(
            float ax,
            float ay,
            float az,
            float eyeA,
            float bx,
            float by,
            float bz,
            float eyeB,
            int triangleMask,
            int layerMask,
            int entityA,
            int entityB,
            QueryContext q) {
        float oy = ay + eyeA;
        float dx = bx - ax;
        float dy = by + eyeB - oy;
        float dz = bz - az;
        q.clear();
        if (bvh != null
                && triangleMask != 0
                && bvh.raycastAny(ax, oy, az, dx, dy, dz, 1f - 1e-4f, triangleMask, q.rayHit)) {
            return false;
        }
        if (layerMask == 0) {
            return true;
        }
        IntList c =
                candidates(
                        Math.min(ax, bx),
                        Math.min(az, bz),
                        Math.max(ax, bx),
                        Math.max(az, bz),
                        layerMask,
                        q);
        for (int k = 0; k < c.size(); k++) {
            int i = c.get(k);
            int e = bodies.entity[i];
            if (e == entityA || e == entityB) {
                continue;
            }
            if (rayBody(i, ax, oy, az, dx, dy, dz, 1f, 0, q.scratch) <= 1f) {
                return false;
            }
        }
        return true;
    }

    /**
     * Picks under a screen ray (DESIGN §11.3): the nearest of units ({@code unitLayers}, radii
     * grown by {@code unitPadding} for easier clicking), interactables ({@code interactableLayers})
     * and solid static geometry. Sets {@code q.pickKind}: a unit or interactable (with {@code
     * q.entity}), {@link #PICK_GROUND} when the nearest surface is walkable (the point in {@code
     * q.x, q.y, q.z}), or {@link #PICK_NONE}.
     */
    public int pick(
            float ox,
            float oy,
            float oz,
            float dx,
            float dy,
            float dz,
            float maxT,
            int unitLayers,
            int interactableLayers,
            float unitPadding,
            QueryContext q) {
        int solid = dev.jeddy.physics.bvh.TriFlags.SOLID;
        // Units first with padding, then interactables without; the nearer wins.
        boolean unit =
                ray(ox, oy, oz, dx, dy, dz, maxT, solid, unitLayers, -1, q, false, unitPadding);
        float unitT = unit && q.entity >= 0 ? q.t : Float.POSITIVE_INFINITY;
        int unitEntity = q.entity;
        int unitBody = q.body;
        boolean other =
                ray(ox, oy, oz, dx, dy, dz, maxT, solid, interactableLayers, -1, q, false, 0);
        if (unitT != Float.POSITIVE_INFINITY && (!other || unitT <= q.t)) {
            q.t = unitT;
            q.entity = unitEntity;
            q.body = unitBody;
            q.triangle = -1;
            point(q, ox, oy, oz, dx, dy, dz);
            q.pickKind = PICK_UNIT;
            return PICK_UNIT;
        }
        if (!other) {
            q.pickKind = PICK_NONE;
            return PICK_NONE;
        }
        if (q.entity >= 0) {
            q.pickKind = PICK_INTERACTABLE;
        } else {
            q.pickKind =
                    (q.triangleFlags & dev.jeddy.physics.bvh.TriFlags.WALKABLE) != 0
                            ? PICK_GROUND
                            : PICK_NONE;
        }
        return q.pickKind;
    }
}
