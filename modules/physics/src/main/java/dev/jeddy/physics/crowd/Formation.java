package dev.jeddy.physics.crowd;

import dev.jeddy.physics.nav.NavMesh;
import dev.jeddy.physics.nav.NavMeshQuery;
import dev.jeddy.physics.nav.NavRayHit;
import dev.jeddy.physics.nav.QueryFilter;

/**
 * A party formation (DESIGN §11.4): slot offsets relative to the click point and facing (right and
 * back, in meters), placed on the navmesh (slots behind walls are pulled back toward the centre),
 * and the assignment of members to slots that minimizes the total distance walked (Hungarian
 * algorithm; such an assignment never has crossing paths). Templates for the usual shapes; slots
 * can be edited.
 *
 * <p>Thread ownership: the game thread. {@link #place} and {@link #assign} allocate nothing.
 */
public final class Formation {
    private final float[] right;
    private final float[] back;
    private final int count;

    // Hungarian algorithm scratch (1-based, n ≤ count).
    private final float[] cost;
    private final float[] u;
    private final float[] v;
    private final int[] match;
    private final int[] way;
    private final float[] minv;
    private final boolean[] used;
    private final float[] p = new float[3];
    private final NavRayHit hit = new NavRayHit();

    /** Creates a formation from slot offsets {@code (right, back)} pairs. */
    public Formation(float[] rightBack) {
        count = rightBack.length / 2;
        right = new float[count];
        back = new float[count];
        for (int i = 0; i < count; i++) {
            right[i] = rightBack[i * 2];
            back[i] = rightBack[i * 2 + 1];
        }
        cost = new float[(count + 1) * (count + 1)];
        u = new float[count + 1];
        v = new float[count + 1];
        match = new int[count + 1];
        way = new int[count + 1];
        minv = new float[count + 1];
        used = new boolean[count + 1];
    }

    /** A line abreast, {@code spacing} meters apart, centred on the click point. */
    public static Formation line(int n, float spacing) {
        float[] o = new float[n * 2];
        for (int i = 0; i < n; i++) {
            o[i * 2] = (i - (n - 1) * 0.5f) * spacing;
        }
        return new Formation(o);
    }

    /** A single file, the first member on the click point. */
    public static Formation column(int n, float spacing) {
        float[] o = new float[n * 2];
        for (int i = 0; i < n; i++) {
            o[i * 2 + 1] = i * spacing;
        }
        return new Formation(o);
    }

    /** A wedge: the first member at the tip, the others alternating left and right behind. */
    public static Formation wedge(int n, float spacing) {
        float[] o = new float[n * 2];
        for (int i = 1; i < n; i++) {
            int rank = (i + 1) / 2;
            o[i * 2] = (i % 2 == 1 ? -1 : 1) * rank * spacing * 0.75f;
            o[i * 2 + 1] = rank * spacing;
        }
        return new Formation(o);
    }

    /** Rows of {@code columns} members, centred on the click point (Pathfinder's default box). */
    public static Formation box(int n, int columns, float spacing) {
        float[] o = new float[n * 2];
        int rows = (n + columns - 1) / columns;
        for (int i = 0; i < n; i++) {
            int r = i / columns;
            int inRow = Math.min(columns, n - r * columns);
            int c = i % columns;
            o[i * 2] = (c - (inRow - 1) * 0.5f) * spacing;
            o[i * 2 + 1] = (r - (rows - 1) * 0.5f) * spacing;
        }
        return new Formation(o);
    }

    /** A ring of radius {@code r} around the click point. */
    public static Formation circle(int n, float r) {
        float[] o = new float[n * 2];
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n;
            o[i * 2] = (float) (Math.sin(a) * r);
            o[i * 2 + 1] = (float) (-Math.cos(a) * r);
        }
        return new Formation(o);
    }

    /** Returns the number of slots. */
    public int size() {
        return count;
    }

    /** Moves slot {@code i} to {@code (right, back)}. */
    public void setSlot(int i, float slotRight, float slotBack) {
        right[i] = slotRight;
        back[i] = slotBack;
    }

    /** Returns slot {@code i}'s offset to the right of the facing. */
    public float slotRight(int i) {
        return right[i];
    }

    /** Returns slot {@code i}'s offset behind the click point. */
    public float slotBack(int i) {
        return back[i];
    }

    /**
     * Writes the world positions of the first {@code n} slots for a click at {@code (x, y, z)}
     * facing {@code (dirX, dirZ)} into {@code out} (x, y, z each). With a query, slots are placed
     * on the navmesh: a slot the centre cannot walk straight to (a wall between) is pulled back
     * along the line to {@code margin} meters short of the obstacle. Returns how many slots were
     * placed on the navmesh (all {@code n} without a query).
     */
    public int place(
            float x,
            float y,
            float z,
            float dirX,
            float dirZ,
            int n,
            NavMeshQuery q,
            QueryFilter filter,
            float margin,
            float[] out) {
        float len = (float) Math.sqrt(dirX * dirX + dirZ * dirZ);
        float fx = len > 1e-6f ? dirX / len : 0;
        float fz = len > 1e-6f ? dirZ / len : 1;
        // Right-handed, Y up: right = forward × up.
        float rx = -fz;
        float rz = fx;
        int centre = NavMesh.NULL_REF;
        if (q != null) {
            centre = q.findNearestPoly(x, y, z, 2, 4, 2, filter, p);
            if (centre != NavMesh.NULL_REF) {
                x = p[0];
                y = p[1];
                z = p[2];
            }
        }
        int placed = 0;
        for (int i = 0; i < n; i++) {
            float sx = x + rx * right[i] - fx * back[i];
            float sz = z + rz * right[i] - fz * back[i];
            float sy = y;
            if (centre != NavMesh.NULL_REF) {
                q.raycast(centre, x, y, z, sx, y, sz, filter, hit, null);
                if (hit.hit()) {
                    float dx = sx - x;
                    float dz = sz - z;
                    float d = (float) Math.sqrt(dx * dx + dz * dz);
                    float t = d > 1e-6f ? Math.max(0, hit.t - margin / d) : 0;
                    sx = x + dx * t;
                    sz = z + dz * t;
                }
                int ref = q.findNearestPoly(sx, y, sz, 1, 4, 1, filter, p);
                if (ref != NavMesh.NULL_REF) {
                    sx = p[0];
                    sy = p[1];
                    sz = p[2];
                    placed++;
                }
            } else {
                placed++;
            }
            out[i * 3] = sx;
            out[i * 3 + 1] = sy;
            out[i * 3 + 2] = sz;
        }
        return placed;
    }

    /**
     * Assigns {@code n} members at {@code positions} (x, y, z each) to the {@code n} slots at
     * {@code slots} minimizing the total XZ distance; writes each member's slot to {@code
     * slotOfMember}. O(n³); {@code n} at most {@link #size()}.
     */
    public void assign(float[] positions, float[] slots, int n, int[] slotOfMember) {
        int w = n + 1;
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= n; j++) {
                float dx = positions[(i - 1) * 3] - slots[(j - 1) * 3];
                float dz = positions[(i - 1) * 3 + 2] - slots[(j - 1) * 3 + 2];
                cost[i * w + j] = (float) Math.sqrt(dx * dx + dz * dz);
            }
        }
        // Kuhn-Munkres with potentials (rows: members, columns: slots).
        for (int j = 0; j <= n; j++) {
            u[j] = 0;
            v[j] = 0;
            match[j] = 0;
            way[j] = 0;
        }
        for (int i = 1; i <= n; i++) {
            match[0] = i;
            int j0 = 0;
            for (int j = 0; j <= n; j++) {
                minv[j] = Float.MAX_VALUE;
                used[j] = false;
            }
            do {
                used[j0] = true;
                int i0 = match[j0];
                float delta = Float.MAX_VALUE;
                int j1 = 0;
                for (int j = 1; j <= n; j++) {
                    if (used[j]) {
                        continue;
                    }
                    float cur = cost[i0 * w + j] - u[i0] - v[j];
                    if (cur < minv[j]) {
                        minv[j] = cur;
                        way[j] = j0;
                    }
                    if (minv[j] < delta) {
                        delta = minv[j];
                        j1 = j;
                    }
                }
                for (int j = 0; j <= n; j++) {
                    if (used[j]) {
                        u[match[j]] += delta;
                        v[j] -= delta;
                    } else {
                        minv[j] -= delta;
                    }
                }
                j0 = j1;
            } while (match[j0] != 0);
            do {
                int j1 = way[j0];
                match[j0] = match[j1];
                j0 = j1;
            } while (j0 != 0);
        }
        for (int j = 1; j <= n; j++) {
            slotOfMember[match[j] - 1] = j - 1;
        }
    }
}
