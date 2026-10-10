package dev.jeddy.physics.trigger;

import dev.jeddy.core.collections.IntList;
import dev.jeddy.math.Poly2;
import dev.jeddy.physics.debug.DebugSink;
import dev.jeddy.physics.grid.Bodies;
import dev.jeddy.physics.grid.UniformGrid;

import java.util.Arrays;

/**
 * Trigger volumes (DESIGN §11.6): vertical prisms (a polygon footprint between two heights), yawed
 * boxes and spheres that detect the bodies in their layer mask by position (a body is inside when
 * its base point is). Every {@link #update} compares each trigger's occupants with the previous
 * tick's and appends {@code (trigger id, entity, kind)} triples to a primitive event buffer, in
 * trigger order and ascending entity order, so gameplay (traps, dialogs, area transitions, ambient
 * zones) sees deterministic events.
 *
 * <p>Thread ownership: the physics thread.
 */
public final class Triggers {
    /** Event kind: the entity entered the trigger. */
    public static final int ENTER = 1;

    /** Event kind: the entity left the trigger (or its body was removed). */
    public static final int EXIT = 2;

    /** Shape: vertical prism. */
    public static final int PRISM = 0;

    /** Shape: box standing on its base, turned by a yaw. */
    public static final int BOX = 1;

    /** Shape: sphere. */
    public static final int SPHERE = 2;

    private int count;
    private int[] id = new int[16];
    private int[] shape = new int[16];
    private int[] layers = new int[16];
    private boolean[] active = new boolean[16];
    // Shape parameters: prism (vertex offset, count, yMin, yMax), box (x, y, z, hx, hz, h, yaw),
    // sphere (x, y, z, r).
    private float[] params = new float[16 * 8];
    private int[] polyOffset = new int[16];
    private int[] polyCount = new int[16];
    private float[] polygons = new float[64];
    private int polygonFloats;
    private final float[] bounds = new float[4];
    // Occupants: previous and current, per trigger a sorted range in a flat array.
    private int[] previous = new int[64];
    private int[] previousStart = new int[17];
    private int[] current = new int[64];
    private int[] currentStart = new int[17];
    private final IntList candidates = new IntList(64);
    private int[] events = new int[48];
    private int eventCount;

    /** Creates an empty trigger set. */
    public Triggers() {}

    private int add(int triggerId, int triggerShape, int layerMask) {
        if (count == id.length) {
            int cap = count * 2;
            id = Arrays.copyOf(id, cap);
            shape = Arrays.copyOf(shape, cap);
            layers = Arrays.copyOf(layers, cap);
            active = Arrays.copyOf(active, cap);
            params = Arrays.copyOf(params, cap * 8);
            polyOffset = Arrays.copyOf(polyOffset, cap);
            polyCount = Arrays.copyOf(polyCount, cap);
            previousStart = Arrays.copyOf(previousStart, cap + 1);
            currentStart = Arrays.copyOf(currentStart, cap + 1);
        }
        int t = count++;
        id[t] = triggerId;
        shape[t] = triggerShape;
        layers[t] = layerMask;
        active[t] = true;
        previousStart[t + 1] = previousStart[t];
        return t;
    }

    /** Adds a prism over polygon {@code xz[0..2n)} between {@code yMin} and {@code yMax}. */
    public int addPrism(int triggerId, float[] xz, int n, float yMin, float yMax, int layerMask) {
        int t = add(triggerId, PRISM, layerMask);
        if (polygonFloats + n * 2 > polygons.length) {
            polygons =
                    Arrays.copyOf(polygons, Math.max(polygons.length * 2, polygonFloats + n * 2));
        }
        System.arraycopy(xz, 0, polygons, polygonFloats, n * 2);
        polyOffset[t] = polygonFloats;
        polyCount[t] = n;
        polygonFloats += n * 2;
        params[t * 8] = yMin;
        params[t * 8 + 1] = yMax;
        return t;
    }

    /** Adds a box standing at {@code (x, y, z)}. */
    public int addBox(
            int triggerId,
            float x,
            float y,
            float z,
            float hx,
            float hz,
            float h,
            float yaw,
            int layerMask) {
        int t = add(triggerId, BOX, layerMask);
        int o = t * 8;
        params[o] = x;
        params[o + 1] = y;
        params[o + 2] = z;
        params[o + 3] = hx;
        params[o + 4] = hz;
        params[o + 5] = h;
        params[o + 6] = (float) Math.cos(yaw);
        params[o + 7] = (float) Math.sin(yaw);
        return t;
    }

    /** Adds a sphere. */
    public int addSphere(int triggerId, float x, float y, float z, float r, int layerMask) {
        int t = add(triggerId, SPHERE, layerMask);
        int o = t * 8;
        params[o] = x;
        params[o + 1] = y;
        params[o + 2] = z;
        params[o + 3] = r;
        return t;
    }

    /**
     * Turns trigger {@code t} on or off; an inactive trigger reports nobody inside (so its
     * occupants exit).
     */
    public void setActive(int t, boolean on) {
        active[t] = on;
    }

    /** Returns the number of triggers. */
    public int count() {
        return count;
    }

    /**
     * Finds every trigger's occupants among {@code bodies} (through {@code grid}, rebuilt this
     * tick) and writes the enter and exit events. Allocates nothing once warm.
     */
    public void update(Bodies bodies, UniformGrid grid) {
        eventCount = 0;
        int written = 0;
        for (int t = 0; t < count; t++) {
            currentStart[t] = written;
            if (active[t]) {
                footprint(t);
                candidates.clear();
                grid.query(bounds[0], bounds[1], bounds[2], bounds[3], layers[t], candidates);
                for (int k = 0; k < candidates.size(); k++) {
                    int b = candidates.get(k);
                    if (contains(t, bodies.x[b], bodies.y[b], bodies.z[b])) {
                        if (written == current.length) {
                            current = Arrays.copyOf(current, written * 2);
                        }
                        // Insertion keeps the trigger's range sorted by entity.
                        int e = bodies.entity[b];
                        int at = written++;
                        while (at > currentStart[t] && current[at - 1] > e) {
                            current[at] = current[at - 1];
                            at--;
                        }
                        current[at] = e;
                    }
                }
            }
            currentStart[t + 1] = written;
            diff(t);
        }
        // The current occupants become the previous ones.
        int[] swap = previous;
        previous = current;
        current = swap;
        int[] swapStart = previousStart;
        previousStart = currentStart;
        currentStart = swapStart;
    }

    /** Merges trigger t's previous and current sorted ranges into events. */
    private void diff(int t) {
        int a = previousStart[t];
        int aEnd = previousStart[t + 1];
        int b = currentStart[t];
        int bEnd = currentStart[t + 1];
        while (a < aEnd || b < bEnd) {
            if (b == bEnd || a < aEnd && previous[a] < current[b]) {
                event(id[t], previous[a++], EXIT);
            } else if (a == aEnd || current[b] < previous[a]) {
                event(id[t], current[b++], ENTER);
            } else {
                a++;
                b++;
            }
        }
    }

    private void event(int trigger, int entity, int kind) {
        if ((eventCount + 1) * 3 > events.length) {
            events = Arrays.copyOf(events, events.length * 2);
        }
        int o = eventCount++ * 3;
        events[o] = trigger;
        events[o + 1] = entity;
        events[o + 2] = kind;
    }

    private void footprint(int t) {
        int o = t * 8;
        switch (shape[t]) {
            case PRISM -> {
                float lx = Float.MAX_VALUE,
                        lz = Float.MAX_VALUE,
                        hx = -Float.MAX_VALUE,
                        hz = -Float.MAX_VALUE;
                for (int k = 0; k < polyCount[t]; k++) {
                    float x = polygons[polyOffset[t] + k * 2];
                    float z = polygons[polyOffset[t] + k * 2 + 1];
                    lx = Math.min(lx, x);
                    hx = Math.max(hx, x);
                    lz = Math.min(lz, z);
                    hz = Math.max(hz, z);
                }
                bounds[0] = lx;
                bounds[1] = lz;
                bounds[2] = hx;
                bounds[3] = hz;
            }
            case BOX -> {
                float c = Math.abs(params[o + 6]);
                float s = Math.abs(params[o + 7]);
                float ex = c * params[o + 3] + s * params[o + 4];
                float ez = s * params[o + 3] + c * params[o + 4];
                bounds[0] = params[o] - ex;
                bounds[1] = params[o + 2] - ez;
                bounds[2] = params[o] + ex;
                bounds[3] = params[o + 2] + ez;
            }
            default -> {
                float r = params[o + 3];
                bounds[0] = params[o] - r;
                bounds[1] = params[o + 2] - r;
                bounds[2] = params[o] + r;
                bounds[3] = params[o + 2] + r;
            }
        }
    }

    private boolean contains(int t, float x, float y, float z) {
        int o = t * 8;
        switch (shape[t]) {
            case PRISM -> {
                return y >= params[o]
                        && y <= params[o + 1]
                        && Poly2.contains(polygonView(t), polyCount[t], x, z);
            }
            case BOX -> {
                float c = params[o + 6];
                float s = params[o + 7];
                float px = x - params[o];
                float pz = z - params[o + 2];
                float lx = c * px - s * pz;
                float lz = s * px + c * pz;
                float ly = y - params[o + 1];
                return Math.abs(lx) <= params[o + 3]
                        && Math.abs(lz) <= params[o + 4]
                        && ly >= 0
                        && ly <= params[o + 5];
            }
            default -> {
                float dx = x - params[o];
                float dy = y - params[o + 1];
                float dz = z - params[o + 2];
                return dx * dx + dy * dy + dz * dz <= params[o + 3] * params[o + 3];
            }
        }
    }

    private float[] polygonScratch = new float[64];

    private float[] polygonView(int t) {
        // Poly2.contains reads from index 0: copy the trigger's polygon into scratch.
        int n = polyCount[t] * 2;
        if (n > polygonScratch.length) {
            polygonScratch = new float[n];
        }
        System.arraycopy(polygons, polyOffset[t], polygonScratch, 0, n);
        return polygonScratch;
    }

    /** Returns the number of events from the last {@link #update}. */
    public int eventCount() {
        return eventCount;
    }

    /** Returns the events: {@code (trigger id, entity, kind)} triples. */
    public int[] events() {
        return events;
    }

    /** Draws every active trigger's outline (debug view; allocates nothing). */
    public void debugDraw(DebugSink sink, int thread, int argb) {
        for (int t = 0; t < count; t++) {
            if (!active[t]) {
                continue;
            }
            int o = t * 8;
            switch (shape[t]) {
                case PRISM -> {
                    float y0 = params[o];
                    float y1 = params[o + 1];
                    int n = polyCount[t];
                    int base = polyOffset[t];
                    for (int k = 0, j = n - 1; k < n; j = k++) {
                        float ax = polygons[base + j * 2];
                        float az = polygons[base + j * 2 + 1];
                        float bx = polygons[base + k * 2];
                        float bz = polygons[base + k * 2 + 1];
                        sink.line(thread, ax, y0, az, bx, y0, bz, argb);
                        sink.line(thread, ax, y1, az, bx, y1, bz, argb);
                        sink.line(thread, bx, y0, bz, bx, y1, bz, argb);
                    }
                }
                case BOX -> {
                    float x = params[o];
                    float y = params[o + 1];
                    float z = params[o + 2];
                    float hx = params[o + 3];
                    float hz = params[o + 4];
                    float h = params[o + 5];
                    float c = params[o + 6];
                    float s = params[o + 7];
                    for (int k = 0; k < 4; k++) {
                        // Corners in order around the box: (-,-) (+,-) (+,+) (-,+).
                        float lx0 = (k == 1 || k == 2) ? hx : -hx;
                        float lz0 = k >= 2 ? hz : -hz;
                        int k1 = (k + 1) & 3;
                        float lx1 = (k1 == 1 || k1 == 2) ? hx : -hx;
                        float lz1 = k1 >= 2 ? hz : -hz;
                        float ax = x + c * lx0 + s * lz0;
                        float az = z - s * lx0 + c * lz0;
                        float bx = x + c * lx1 + s * lz1;
                        float bz = z - s * lx1 + c * lz1;
                        sink.line(thread, ax, y, az, bx, y, bz, argb);
                        sink.line(thread, ax, y + h, az, bx, y + h, bz, argb);
                        sink.line(thread, ax, y, az, ax, y + h, az, argb);
                    }
                }
                default -> {
                    float x = params[o];
                    float y = params[o + 1];
                    float z = params[o + 2];
                    float r = params[o + 3];
                    sink.circle(thread, x, y, z, r, argb);
                    sink.line(thread, x, y - r, z, x, y + r, z, argb);
                }
            }
        }
    }
}
