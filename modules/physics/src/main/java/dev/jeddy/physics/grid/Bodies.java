package dev.jeddy.physics.grid;

import dev.jeddy.core.collections.IntIntMap;
import dev.jeddy.physics.debug.DebugSink;

import java.util.Arrays;

/**
 * The dynamic collision bodies (DESIGN §11.2), SoA: units are vertical cylinders (base center,
 * radius, height); props are spheres (center, radius), vertical cylinders or yawed boxes (base
 * center, half extents in x and z, height, yaw). Every body has collision layers and the entity it
 * belongs to. Bodies are dense: removal moves the last body into the hole, and {@link #indexOf}
 * finds a body by entity.
 *
 * <p>Thread ownership: the game thread writes (sync from gameplay each tick); queries read during
 * the physics step.
 */
public final class Bodies {
    /** Shape: vertical cylinder standing on its base (units, columns). */
    public static final int CYLINDER = 0;

    /** Shape: sphere around its center (projectiles, floating objects). */
    public static final int SPHERE = 1;

    /** Shape: box standing on its base, turned by a yaw about +Y (crates, tables). */
    public static final int BOX = 2;

    /** Base (cylinder, box) or center (sphere) x. */
    public float[] x;

    /** Base or center y. */
    public float[] y;

    /** Base or center z. */
    public float[] z;

    /** Cylinder and sphere radius; for boxes the radius of the XZ footprint's bounding circle. */
    public float[] radius;

    /** Cylinder and box height (sphere: 0). */
    public float[] height;

    /** Box half extent along its local x. */
    public float[] halfX;

    /** Box half extent along its local z. */
    public float[] halfZ;

    /** Box yaw (radians about +Y); change it with {@link #setYaw}. */
    public float[] yaw;

    /** Half extent of the footprint's axis-aligned box along x (derived; see {@link #setYaw}). */
    public float[] extentX;

    /** Half extent of the footprint's axis-aligned box along z. */
    public float[] extentZ;

    /** Shape ({@link #CYLINDER}, {@link #SPHERE}, {@link #BOX}). */
    public int[] shape;

    /** Collision layer bits. */
    public int[] layers;

    /** Owning entity. */
    public int[] entity;

    private int count;
    private final IntIntMap byEntity = new IntIntMap(64, -1);

    /** Creates storage for {@code capacity} bodies (grows). */
    public Bodies(int capacity) {
        allocate(Math.max(16, capacity));
    }

    private void allocate(int cap) {
        x = grow(x, cap);
        y = grow(y, cap);
        z = grow(z, cap);
        radius = grow(radius, cap);
        height = grow(height, cap);
        halfX = grow(halfX, cap);
        halfZ = grow(halfZ, cap);
        yaw = grow(yaw, cap);
        extentX = grow(extentX, cap);
        extentZ = grow(extentZ, cap);
        shape = grow(shape, cap);
        layers = grow(layers, cap);
        entity = grow(entity, cap);
    }

    private static float[] grow(float[] a, int cap) {
        return a == null ? new float[cap] : Arrays.copyOf(a, cap);
    }

    private static int[] grow(int[] a, int cap) {
        return a == null ? new int[cap] : Arrays.copyOf(a, cap);
    }

    /** Returns the number of bodies. */
    public int count() {
        return count;
    }

    /** Returns the body of {@code entityId}, or -1. */
    public int indexOf(int entityId) {
        return byEntity.get(entityId);
    }

    private int add(int entityId, int bodyShape, int bodyLayers) {
        if (byEntity.containsKey(entityId)) {
            throw new IllegalArgumentException("entity " + entityId + " already has a body");
        }
        if (count == x.length) {
            allocate(count * 2);
        }
        int i = count++;
        shape[i] = bodyShape;
        layers[i] = bodyLayers;
        entity[i] = entityId;
        halfX[i] = 0;
        halfZ[i] = 0;
        yaw[i] = 0;
        byEntity.put(entityId, i);
        return i;
    }

    /** Adds a vertical cylinder standing at {@code (x, y, z)}; returns its index. */
    public int addCylinder(
            int entityId, float bx, float by, float bz, float r, float h, int bodyLayers) {
        int i = add(entityId, CYLINDER, bodyLayers);
        x[i] = bx;
        y[i] = by;
        z[i] = bz;
        radius[i] = r;
        height[i] = h;
        extentX[i] = r;
        extentZ[i] = r;
        return i;
    }

    /** Adds a sphere centered at {@code (x, y, z)}; returns its index. */
    public int addSphere(int entityId, float cx, float cy, float cz, float r, int bodyLayers) {
        int i = add(entityId, SPHERE, bodyLayers);
        x[i] = cx;
        y[i] = cy;
        z[i] = cz;
        radius[i] = r;
        height[i] = 0;
        extentX[i] = r;
        extentZ[i] = r;
        return i;
    }

    /** Adds a box standing at {@code (x, y, z)}, turned by {@code yaw}; returns its index. */
    public int addBox(
            int entityId,
            float bx,
            float by,
            float bz,
            float hx,
            float hz,
            float h,
            float boxYaw,
            int bodyLayers) {
        int i = add(entityId, BOX, bodyLayers);
        x[i] = bx;
        y[i] = by;
        z[i] = bz;
        halfX[i] = hx;
        halfZ[i] = hz;
        height[i] = h;
        radius[i] = (float) Math.sqrt(hx * hx + hz * hz);
        setYaw(i, boxYaw);
        return i;
    }

    /** Turns box {@code i} and updates its footprint extents. */
    public void setYaw(int i, float boxYaw) {
        yaw[i] = boxYaw;
        if (shape[i] == BOX) {
            float c = Math.abs((float) Math.cos(boxYaw));
            float s = Math.abs((float) Math.sin(boxYaw));
            extentX[i] = c * halfX[i] + s * halfZ[i];
            extentZ[i] = s * halfX[i] + c * halfZ[i];
        }
    }

    /** Moves body {@code i}. */
    public void setPosition(int i, float px, float py, float pz) {
        x[i] = px;
        y[i] = py;
        z[i] = pz;
    }

    /**
     * Removes body {@code i}, moving the last body into its place.
     *
     * @return the index the moved body had (now {@code i}), or -1 if {@code i} was last
     */
    public int remove(int i) {
        byEntity.remove(entity[i]);
        int last = --count;
        if (i == last) {
            return -1;
        }
        x[i] = x[last];
        y[i] = y[last];
        z[i] = z[last];
        radius[i] = radius[last];
        height[i] = height[last];
        halfX[i] = halfX[last];
        halfZ[i] = halfZ[last];
        yaw[i] = yaw[last];
        extentX[i] = extentX[last];
        extentZ[i] = extentZ[last];
        shape[i] = shape[last];
        layers[i] = layers[last];
        entity[i] = entity[last];
        byEntity.put(entity[i], i);
        return last;
    }

    /** Removes every body. */
    public void clear() {
        count = 0;
        byEntity.clear();
    }

    /** Lowest y of body {@code i}. */
    public float bottom(int i) {
        return shape[i] == SPHERE ? y[i] - radius[i] : y[i];
    }

    /** Highest y of body {@code i}. */
    public float top(int i) {
        return shape[i] == SPHERE ? y[i] + radius[i] : y[i] + height[i];
    }

    /** Draws every body's shape (debug view; allocates nothing). */
    public void debugDraw(DebugSink sink, int thread, int argb) {
        for (int i = 0; i < count; i++) {
            switch (shape[i]) {
                case CYLINDER ->
                        sink.cylinder(thread, x[i], y[i], z[i], radius[i], height[i], argb);
                case SPHERE -> {
                    sink.circle(thread, x[i], y[i], z[i], radius[i], argb);
                    sink.line(
                            thread,
                            x[i],
                            y[i] - radius[i],
                            z[i],
                            x[i],
                            y[i] + radius[i],
                            z[i],
                            argb);
                }
                default -> {
                    float c = (float) Math.cos(yaw[i]);
                    float s = (float) Math.sin(yaw[i]);
                    float hx = halfX[i];
                    float hz = halfZ[i];
                    for (int k = 0; k < 4; k++) {
                        float lx0 = (k == 1 || k == 2) ? hx : -hx;
                        float lz0 = k >= 2 ? hz : -hz;
                        int k1 = (k + 1) & 3;
                        float lx1 = (k1 == 1 || k1 == 2) ? hx : -hx;
                        float lz1 = k1 >= 2 ? hz : -hz;
                        float ax = x[i] + c * lx0 + s * lz0;
                        float az = z[i] - s * lx0 + c * lz0;
                        float bx = x[i] + c * lx1 + s * lz1;
                        float bz = z[i] - s * lx1 + c * lz1;
                        sink.line(thread, ax, y[i], az, bx, y[i], bz, argb);
                        sink.line(thread, ax, y[i] + height[i], az, bx, y[i] + height[i], bz, argb);
                        sink.line(thread, ax, y[i], az, ax, y[i] + height[i], az, argb);
                    }
                }
            }
        }
    }
}
