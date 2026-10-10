package dev.jeddy.physics.nav;

import java.util.Arrays;

/**
 * Doors and other toggles on the navmesh (DESIGN §11.4 dynamic changes): each door is a box; the
 * ground polygons whose centres lie inside it get {@link NavFlags#DOOR}, and closing the door sets
 * {@link NavFlags#DISABLED} on them, which default {@link QueryFilter}s exclude (a filter that can
 * open doors simply does not exclude it). Bake doorways with an area volume so their polygons match
 * the door's footprint. Polygon references change when tiles are rebuilt: call {@link #refresh}
 * after tile swaps.
 *
 * <p>Thread ownership: the game thread, between navmesh query phases.
 */
public final class NavDoors {
    private final NavMeshQuery query;
    private float[] boxes = new float[8 * 6];
    private boolean[] open = new boolean[8];
    private int[] polyStart = new int[9];
    private int[] polys = new int[64];
    private int count;
    private final int[] found = new int[256];
    private final float[] centre = new float[3];

    /** Creates door bookkeeping for {@code query}'s mesh. */
    public NavDoors(NavMeshQuery query) {
        this.query = query;
    }

    /** Adds a door covering the box, initially {@code isOpen}; returns its id. */
    public int add(
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ,
            boolean isOpen) {
        if (count == open.length) {
            boxes = Arrays.copyOf(boxes, count * 2 * 6);
            open = Arrays.copyOf(open, count * 2);
        }
        int id = count++;
        int o = id * 6;
        boxes[o] = minX;
        boxes[o + 1] = minY;
        boxes[o + 2] = minZ;
        boxes[o + 3] = maxX;
        boxes[o + 4] = maxY;
        boxes[o + 5] = maxZ;
        open[id] = isOpen;
        refresh();
        return id;
    }

    /** Returns the number of doors. */
    public int count() {
        return count;
    }

    /** Opens or closes door {@code id}. */
    public void setOpen(int id, boolean isOpen) {
        open[id] = isOpen;
        apply(id);
    }

    /** Returns whether door {@code id} is open. */
    public boolean isOpen(int id) {
        return open[id];
    }

    /** Returns how many polygons door {@code id} covers. */
    public int polygonCount(int id) {
        return polyStart[id + 1] - polyStart[id];
    }

    /** Finds every door's polygons again (after tiles were rebuilt) and re-applies their state. */
    public void refresh() {
        if (polyStart.length < count + 1) {
            polyStart = new int[count * 2 + 1];
        }
        int n = 0;
        NavMesh mesh = query.mesh();
        for (int d = 0; d < count; d++) {
            polyStart[d] = n;
            int o = d * 6;
            int k =
                    query.queryPolygons(
                            boxes[o],
                            boxes[o + 1],
                            boxes[o + 2],
                            boxes[o + 3],
                            boxes[o + 4],
                            boxes[o + 5],
                            null,
                            found);
            for (int i = 0; i < k; i++) {
                NavGeometry.polyCenter(mesh.tile(found[i]), mesh.poly(found[i]), centre);
                if (centre[0] < boxes[o]
                        || centre[0] > boxes[o + 3]
                        || centre[2] < boxes[o + 2]
                        || centre[2] > boxes[o + 5]) {
                    continue;
                }
                if (n == polys.length) {
                    polys = Arrays.copyOf(polys, n * 2);
                }
                polys[n++] = found[i];
            }
        }
        polyStart[count] = n;
        for (int d = 0; d < count; d++) {
            apply(d);
        }
    }

    private void apply(int id) {
        NavMesh mesh = query.mesh();
        for (int i = polyStart[id]; i < polyStart[id + 1]; i++) {
            int ref = polys[i];
            if (!mesh.isValid(ref)) {
                continue;
            }
            int f = mesh.polyFlags(ref) | NavFlags.DOOR;
            mesh.setPolyFlags(ref, open[id] ? f & ~NavFlags.DISABLED : f | NavFlags.DISABLED);
        }
    }
}
