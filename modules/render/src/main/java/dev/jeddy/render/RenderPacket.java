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
    /** Material index meaning "each submesh draws with its mesh's own material". */
    public static final int MESH_MATERIALS = -1;

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

    /** Material index per instance, or {@link #MESH_MATERIALS}. */
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

    /** Debug lines: x0 y0 z0 x1 y1 z1 per line ({@code DebugDraw.collect} fills them). */
    public float[] debugLines = new float[6 * 256];

    /** Debug line colors, {@code 0xAARRGGBB}. */
    public int[] debugColors = new int[256];

    /** Number of debug lines. */
    public int debugLineCount;

    /** Light type: point. */
    public static final int LIGHT_POINT = 0;

    /** Light type: spot. */
    public static final int LIGHT_SPOT = 1;

    /** Floats per light in {@link #lights} (the GPU layout of {@code engine/lights.glsl}). */
    public static final int LIGHT_FLOATS = 16;

    /**
     * Point and spot lights, {@link #LIGHT_FLOATS} floats each in the GPU layout: position xyz,
     * range; color × intensity rgb, type (int bits); spot direction xyz, cos(outer); cos(inner),
     * shadow index (int bits, -1 none), 0, 0. Fill with {@link #addPointLight} and {@link
     * #addSpotLight}. Index 14 holds the light's shadow key ({@link #setLightShadow}).
     */
    public float[] lights = new float[LIGHT_FLOATS * 64];

    /** Number of lights. */
    public int lightCount;

    /** Direction the sunlight travels (unit, world space). */
    public final float[] sunDirection = {-0.4f, -0.8f, -0.45f};

    /** Sun color times illuminance (linear RGB); all zero turns the sun off. */
    public final float[] sunColor = {3f, 2.9f, 2.7f};

    /** Whether the sun casts shadows. */
    public boolean sunShadows = true;

    /** Image-based lighting scale for specular and diffuse. */
    public float environmentSpecular = 1;

    /** Image-based lighting diffuse scale. */
    public float environmentDiffuse = 1;

    /**
     * Exposure compensation: multiplies the automatic exposure (or the fixed exposure of 1) before
     * tone mapping.
     */
    public float exposure = 1;

    /** Near plane distance (clusters, shadows). */
    public float near = 0.1f;

    /** Distance up to which lights are clustered and shadows cascade. */
    public float far = 200f;

    /** Whether {@link #projection} is orthographic. */
    public boolean orthographic;

    /** Seconds since start (animated effects). */
    public float time;

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
        debugLineCount = 0;
        lightCount = 0;
    }

    private int nextLight() {
        if ((lightCount + 1) * LIGHT_FLOATS > lights.length) {
            lights = Arrays.copyOf(lights, lights.length * 2);
        }
        return lightCount++ * LIGHT_FLOATS;
    }

    /**
     * Adds a point light.
     *
     * @param range distance where the light fades to 0 (0 = unlimited, inverse square only)
     * @param intensity luminous intensity in candela ({@code KHR_lights_punctual})
     * @return the light index
     */
    public int addPointLight(
            float x, float y, float z, float range, float r, float g, float b, float intensity) {
        int o = nextLight();
        float[] l = lights;
        l[o] = x;
        l[o + 1] = y;
        l[o + 2] = z;
        l[o + 3] = range;
        l[o + 4] = r * intensity;
        l[o + 5] = g * intensity;
        l[o + 6] = b * intensity;
        l[o + 7] = Float.intBitsToFloat(LIGHT_POINT);
        l[o + 8] = 0;
        l[o + 9] = -1;
        l[o + 10] = 0;
        l[o + 11] = -1;
        l[o + 12] = 1;
        l[o + 13] = Float.intBitsToFloat(-1);
        l[o + 14] = Float.intBitsToFloat(-1);
        l[o + 15] = 0;
        return o / LIGHT_FLOATS;
    }

    /**
     * Adds a spot light pointing along {@code (dx, dy, dz)} (unit).
     *
     * @param innerAngle half-angle of full intensity, radians
     * @param outerAngle half-angle where it reaches 0, radians
     * @return the light index
     */
    public int addSpotLight(
            float x,
            float y,
            float z,
            float dx,
            float dy,
            float dz,
            float range,
            float r,
            float g,
            float b,
            float intensity,
            float innerAngle,
            float outerAngle) {
        int i = addPointLight(x, y, z, range, r, g, b, intensity);
        int o = i * LIGHT_FLOATS;
        float[] l = lights;
        l[o + 7] = Float.intBitsToFloat(LIGHT_SPOT);
        l[o + 8] = dx;
        l[o + 9] = dy;
        l[o + 10] = dz;
        l[o + 11] = (float) Math.cos(outerAngle);
        l[o + 12] = (float) Math.cos(Math.min(innerAngle, outerAngle - 1e-3f));
        return i;
    }

    /**
     * Makes light {@code i} cast shadows. {@code key} must identify the light across frames (an
     * entity id): the renderer keeps its shadow tiles, and their static casters, under that key.
     * {@code -1} turns shadows off.
     */
    public void setLightShadow(int i, int key) {
        lights[i * LIGHT_FLOATS + 14] = Float.intBitsToFloat(key);
    }

    /** Bumped by the game whenever static geometry changes (invalidates cached shadow maps). */
    public int staticVersion;

    /** Sets the debug line count, growing the arrays if needed. */
    public void resizeDebugLines(int n) {
        if (n > debugColors.length) {
            int cap = Math.max(n, debugColors.length * 2);
            debugLines = Arrays.copyOf(debugLines, cap * 6);
            debugColors = Arrays.copyOf(debugColors, cap);
        }
        debugLineCount = n;
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
