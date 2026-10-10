package dev.jeddy.render;

import dev.jeddy.math.AffineOps;
import dev.jeddy.math.CullOps;

import java.util.Arrays;

/**
 * Everything the main thread needs to draw one frame, written by the game thread (TASKS M5-06,
 * DESIGN §5): instances as preallocated SoA (previous and current tick transforms for
 * interpolation, mesh, material, flags, entity), the camera and the interpolation factor. Three
 * packets rotate through a triple buffer, so the game fills one while the main thread renders
 * another, without locks or allocation.
 *
 * <p>Thread ownership: the game thread writes a packet between acquiring its write index and
 * publishing it; the main thread reads it after acquiring the read index. Never both at once.
 */
public final class RenderPacket {
    /** Instance flag: never moves (render may keep its data on the GPU). */
    public static final int FLAG_STATIC = 1;

    /** Instance flag: casts shadows. */
    public static final int FLAG_CASTS_SHADOW = 2;

    /** Instance flag: drawn in the transparent pass. */
    public static final int FLAG_TRANSLUCENT = 4;

    /** Instance flag: hovered or selected (outline pass). */
    public static final int FLAG_HIGHLIGHT = 8;

    private int capacity;
    private int count;

    /** Previous tick's world transforms, 12 floats per instance (row-major 3×4). */
    public float[] prevWorld;

    /** Current tick's world transforms, 12 floats per instance. */
    public float[] currWorld;

    /** Mesh handle per instance. */
    public int[] mesh;

    /** Material index per instance. */
    public int[] material;

    /** {@code FLAG_*} bits per instance. */
    public int[] flags;

    /** The entity each instance came from (picking, debugging). */
    public int[] entity;

    /** Bounding sphere per instance in world space: x, y, z, radius (4 floats). */
    public float[] bounds;

    /** Interpolation factor between {@link #prevWorld} and {@link #currWorld}, in [0, 1]. */
    public float alpha;

    /** Camera view matrix (column-major). */
    public final float[] view = new float[16];

    /** Camera projection matrix (column-major, reversed-Z). */
    public final float[] projection = new float[16];

    /** View-projection matrix. */
    public final float[] viewProjection = new float[16];

    /** Inverse view-projection matrix. */
    public final float[] inverseViewProjection = new float[16];

    /** Frustum planes for {@link CullOps}. */
    public final float[] frustumPlanes = new float[CullOps.PLANES_FLOATS];

    /** Camera position in world space. */
    public final float[] eye = new float[3];

    /** The game frame that produced this packet. */
    public long frame;

    /** Creates a packet for {@code capacity} instances (grows on demand). */
    public RenderPacket(int capacity) {
        allocate(Math.max(16, capacity));
    }

    private void allocate(int cap) {
        capacity = cap;
        prevWorld = prevWorld == null ? new float[cap * 12] : Arrays.copyOf(prevWorld, cap * 12);
        currWorld = currWorld == null ? new float[cap * 12] : Arrays.copyOf(currWorld, cap * 12);
        mesh = mesh == null ? new int[cap] : Arrays.copyOf(mesh, cap);
        material = material == null ? new int[cap] : Arrays.copyOf(material, cap);
        flags = flags == null ? new int[cap] : Arrays.copyOf(flags, cap);
        entity = entity == null ? new int[cap] : Arrays.copyOf(entity, cap);
        bounds = bounds == null ? new float[cap * 4] : Arrays.copyOf(bounds, cap * 4);
    }

    /** Empties the packet before the game thread fills it. */
    public void reset() {
        count = 0;
    }

    /** Returns the number of instances. */
    public int count() {
        return count;
    }

    /** Returns the instance capacity. */
    public int capacity() {
        return capacity;
    }

    /**
     * Makes room for {@code n} instances and sets the count, so extraction jobs can fill the arrays
     * in parallel. Grows (allocates) only past the capacity.
     */
    public void resize(int n) {
        if (n > capacity) {
            allocate(Math.max(n, capacity * 2));
        }
        count = n;
    }

    /**
     * Appends an instance. Allocates nothing unless the packet grows.
     *
     * @return its index
     */
    public int add(
            float[] prev,
            int prevOffset,
            float[] curr,
            int currOffset,
            int meshHandle,
            int materialIndex,
            int instanceFlags,
            int entityHandle) {
        if (count == capacity) {
            allocate(capacity * 2);
        }
        int i = count++;
        System.arraycopy(prev, prevOffset, prevWorld, i * 12, 12);
        System.arraycopy(curr, currOffset, currWorld, i * 12, 12);
        mesh[i] = meshHandle;
        material[i] = materialIndex;
        flags[i] = instanceFlags;
        entity[i] = entityHandle;
        return i;
    }

    /** Sets the world bounding sphere of instance {@code i}. */
    public void setBounds(int i, float x, float y, float z, float radius) {
        bounds[i * 4] = x;
        bounds[i * 4 + 1] = y;
        bounds[i * 4 + 2] = z;
        bounds[i * 4 + 3] = radius;
    }

    /**
     * Writes instance {@code i}'s transform interpolated at {@link #alpha} to {@code out[o..o+12)}.
     */
    public void interpolatedWorld(int i, float[] out, int o) {
        AffineOps.lerp(prevWorld, i * 12, currWorld, i * 12, alpha, out, o);
    }

    /** Copies the camera matrices and planes from arrays laid out like {@code IsoCamera}'s. */
    public void setCamera(
            float[] viewMatrix,
            float[] projectionMatrix,
            float[] viewProjectionMatrix,
            float[] inverseViewProjectionMatrix,
            float[] planes,
            float eyeX,
            float eyeY,
            float eyeZ) {
        System.arraycopy(viewMatrix, 0, view, 0, 16);
        System.arraycopy(projectionMatrix, 0, projection, 0, 16);
        System.arraycopy(viewProjectionMatrix, 0, viewProjection, 0, 16);
        System.arraycopy(inverseViewProjectionMatrix, 0, inverseViewProjection, 0, 16);
        System.arraycopy(planes, 0, frustumPlanes, 0, CullOps.PLANES_FLOATS);
        eye[0] = eyeX;
        eye[1] = eyeY;
        eye[2] = eyeZ;
    }
}
