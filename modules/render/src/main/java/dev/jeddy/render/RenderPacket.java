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

    /** Instance flag: decals do not project onto it (characters, props standing in AoE areas). */
    public static final int FLAG_NO_DECALS = 16;

    /**
     * Instance flag: dissolves where it stands between the camera and a reveal point ({@link
     * #addReveal}): walls, roofs, trees.
     */
    public static final int FLAG_FADE_OCCLUDER = 32;

    /** First bit of the instance's highlight style in {@link #flags} (4 bits, 0 = none). */
    public static final int HIGHLIGHT_SHIFT = 8;

    /** Highlight style: hovered (white outline by default). */
    public static final int HIGHLIGHT_HOVER = 1;

    /** Highlight style: selected (green outline). */
    public static final int HIGHLIGHT_SELECTED = 2;

    /** Highlight style: hostile under the cursor (red outline). */
    public static final int HIGHLIGHT_HOSTILE = 3;

    /** Highlight style: interactable while the highlight key is held (gold outline). */
    public static final int HIGHLIGHT_INTERACTABLE = 4;

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

    /**
     * Camera fade per instance, 0 (solid) to 1 (gone), drawn as a screen-door dither: roofs while
     * the party is inside, objects the game wants out of the way.
     */
    public float[] fade;

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
        fade = fade == null ? new float[cap] : Arrays.copyOf(fade, cap);
    }

    /** Empties the packet before the game thread fills it. */
    public void reset() {
        count = 0;
        debugLineCount = 0;
        lightCount = 0;
        decalCount = 0;
        emitterCount = 0;
        revealCount = 0;
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

    /** Decal shape: a texture from the decal array ({@link #setDecalTexture}). */
    public static final int DECAL_TEXTURE = 0;

    /**
     * Decal shape: a ring (selection circle); p0 inner radius (fraction of the outer), p1 edge
     * softness (meters).
     */
    public static final int DECAL_RING = 1;

    /** Decal shape: a disc (circular AoE); p1 outline width (meters), fill opacity inside. */
    public static final int DECAL_DISC = 2;

    /**
     * Decal shape: a cone from the center along local +Z (yaw 0 = world +Z); p0 half-angle
     * (radians), p1 outline width (meters), fill opacity inside.
     */
    public static final int DECAL_CONE = 3;

    /** Decal shape: a rectangle (line AoE); p1 outline width (meters), fill opacity inside. */
    public static final int DECAL_LINE = 4;

    /**
     * Floats per decal in {@link #decals} (the GPU layout of {@code engine/decals.glsl}): world to
     * local box rows (12; the box is [-1, 1]³), bounding sphere (4), color rgba (4), shape (int
     * bits), p0, p1, emission, fill, texture layer, 0, 0.
     */
    public static final int DECAL_FLOATS = 28;

    /** Decals projected downward onto opaque surfaces, {@link #DECAL_FLOATS} floats each. */
    public float[] decals = new float[DECAL_FLOATS * 16];

    /** Number of decals. */
    public int decalCount;

    /**
     * Adds a decal: a box centered at {@code (x, y, z)}, {@code halfWidth} along its local X,
     * {@code halfDepth} vertically (how far above and below the center it projects) and {@code
     * halfLength} along its local Z, turned by {@code yaw} radians about +Y. Surfaces inside the
     * box facing up take its shape in color {@code (r, g, b)} (linear, may exceed 1 when emissive)
     * with opacity {@code a}. Shapes {@link #DECAL_DISC}, {@link #DECAL_CONE} and {@link
     * #DECAL_LINE} default to a 5 cm outline and 30% fill; {@link #DECAL_RING} to an inner radius
     * of 0.85.
     *
     * @return the decal index ({@link #setDecalShape}, {@link #setDecalEmission}, ...)
     */
    public int addDecal(
            int shape,
            float x,
            float y,
            float z,
            float halfWidth,
            float halfDepth,
            float halfLength,
            float yaw,
            float r,
            float g,
            float b,
            float a) {
        if ((decalCount + 1) * DECAL_FLOATS > decals.length) {
            decals = Arrays.copyOf(decals, decals.length * 2);
        }
        int o = decalCount * DECAL_FLOATS;
        float[] d = decals;
        float c = (float) Math.cos(yaw);
        float sn = (float) Math.sin(yaw);
        float iw = 1f / halfWidth;
        float ih = 1f / halfDepth;
        float il = 1f / halfLength;
        // Local X = (cos, 0, -sin), Y = up, Z = (sin, 0, cos); rows map world to [-1, 1].
        d[o] = c * iw;
        d[o + 1] = 0;
        d[o + 2] = -sn * iw;
        d[o + 3] = -(c * x - sn * z) * iw;
        d[o + 4] = 0;
        d[o + 5] = ih;
        d[o + 6] = 0;
        d[o + 7] = -y * ih;
        d[o + 8] = sn * il;
        d[o + 9] = 0;
        d[o + 10] = c * il;
        d[o + 11] = -(sn * x + c * z) * il;
        d[o + 12] = x;
        d[o + 13] = y;
        d[o + 14] = z;
        d[o + 15] =
                (float)
                        Math.sqrt(
                                halfWidth * halfWidth
                                        + halfDepth * halfDepth
                                        + halfLength * halfLength);
        d[o + 16] = r;
        d[o + 17] = g;
        d[o + 18] = b;
        d[o + 19] = a;
        d[o + 20] = Float.intBitsToFloat(shape);
        d[o + 21] = shape == DECAL_RING ? 0.85f : 0f;
        d[o + 22] = shape == DECAL_RING ? 0.02f : 0.05f;
        d[o + 23] = 0;
        d[o + 24] = 0.3f;
        d[o + 25] = 0;
        d[o + 26] = 0;
        d[o + 27] = 0;
        return decalCount++;
    }

    /** Sets a decal's shape parameters (see the {@code DECAL_*} shapes) and interior opacity. */
    public void setDecalShape(int decal, float p0, float p1, float fill) {
        int o = decal * DECAL_FLOATS;
        decals[o + 21] = p0;
        decals[o + 22] = p1;
        decals[o + 24] = fill;
    }

    /**
     * Sets how much of a decal's color is emitted (0: it tints the surface, which is then lit; 1:
     * it glows on top, visible in darkness, like selection circles).
     */
    public void setDecalEmission(int decal, float emission) {
        decals[decal * DECAL_FLOATS + 23] = emission;
    }

    /** Sets the decal texture layer of a {@link #DECAL_TEXTURE} decal ({@code DecalTextures}). */
    public void setDecalTexture(int decal, int layer) {
        decals[decal * DECAL_FLOATS + 25] = layer;
    }

    /**
     * Adds a selection circle: an emissive ring of {@code radius} on the ground at {@code (x, y,
     * z)}.
     */
    public int addSelectionCircle(
            float x, float y, float z, float radius, float r, float g, float b, float a) {
        int d = addDecal(DECAL_RING, x, y, z, radius, 0.5f, radius, 0, r, g, b, a);
        setDecalEmission(d, 1);
        return d;
    }

    /** Adds a circular area-of-effect template. */
    public int addAoeCircle(
            float x, float y, float z, float radius, float r, float g, float b, float a) {
        int d = addDecal(DECAL_DISC, x, y, z, radius, 1f, radius, 0, r, g, b, a);
        setDecalShape(d, 0, 0.06f, 0.3f);
        setDecalEmission(d, 1);
        return d;
    }

    /**
     * Adds a cone template from {@code (x, y, z)} towards {@code yaw} (0 = +Z), {@code length} long
     * with half-angle {@code halfAngle} radians.
     */
    public int addAoeCone(
            float x,
            float y,
            float z,
            float yaw,
            float length,
            float halfAngle,
            float r,
            float g,
            float b,
            float a) {
        int d = addDecal(DECAL_CONE, x, y, z, length, 1f, length, yaw, r, g, b, a);
        setDecalShape(d, halfAngle, 0.04f, 0.3f);
        setDecalEmission(d, 1);
        return d;
    }

    /** Adds a line template from {@code (x0, z0)} to {@code (x1, z1)} at height {@code y}. */
    public int addAoeLine(
            float x0,
            float z0,
            float x1,
            float z1,
            float y,
            float width,
            float r,
            float g,
            float b,
            float a) {
        float dx = x1 - x0;
        float dz = z1 - z0;
        float half = Math.max(1e-3f, (float) Math.sqrt(dx * dx + dz * dz) * 0.5f);
        float yaw = (float) Math.atan2(dx, dz);
        int d =
                addDecal(
                        DECAL_LINE,
                        (x0 + x1) * 0.5f,
                        y,
                        (z0 + z1) * 0.5f,
                        width * 0.5f,
                        1f,
                        half,
                        yaw,
                        r,
                        g,
                        b,
                        a);
        setDecalShape(d, 0, 0.05f, 0.3f);
        setDecalEmission(d, 1);
        return d;
    }

    /**
     * Floats per emitter in {@link #emitters}: position xyz, spawn radius; velocity xyz, random
     * speed; start color rgba; end color rgba; lifetime, lifetime randomness, start size, end size;
     * rate, burst, gravity scale, drag. Colors are premultiplied linear RGBA (alpha 0 adds light).
     */
    public static final int EMITTER_FLOATS = 24;

    /** Particle emitters ({@link #addEmitter}), {@link #EMITTER_FLOATS} floats each. */
    public float[] emitters = new float[EMITTER_FLOATS * 8];

    /** Number of emitters. */
    public int emitterCount;

    /**
     * Adds a particle emitter at {@code (x, y, z)} spawning {@code rate} particles per second and
     * {@code burst} once (when the renderer first sees this packet's {@link #frame}). Defaults: a
     * 0.1 m spawn sphere, 1 m/s upward plus up to 0.5 m/s at random, white fading to transparent, 1
     * s ± 20% lifetime, size 0.1 m shrinking to 0, no gravity or drag; change them with the {@code
     * setEmitter*} methods.
     *
     * @return the emitter index
     */
    public int addEmitter(float x, float y, float z, float rate, int burst) {
        if ((emitterCount + 1) * EMITTER_FLOATS > emitters.length) {
            emitters = Arrays.copyOf(emitters, emitters.length * 2);
        }
        int o = emitterCount * EMITTER_FLOATS;
        float[] e = emitters;
        e[o] = x;
        e[o + 1] = y;
        e[o + 2] = z;
        e[o + 3] = 0.1f;
        e[o + 4] = 0;
        e[o + 5] = 1;
        e[o + 6] = 0;
        e[o + 7] = 0.5f;
        e[o + 8] = 1;
        e[o + 9] = 1;
        e[o + 10] = 1;
        e[o + 11] = 1;
        e[o + 12] = 0;
        e[o + 13] = 0;
        e[o + 14] = 0;
        e[o + 15] = 0;
        e[o + 16] = 1;
        e[o + 17] = 0.2f;
        e[o + 18] = 0.1f;
        e[o + 19] = 0;
        e[o + 20] = rate;
        e[o + 21] = burst;
        e[o + 22] = 0;
        e[o + 23] = 0;
        return emitterCount++;
    }

    /** Sets an emitter's spawn sphere radius. */
    public void setEmitterRadius(int emitter, float radius) {
        emitters[emitter * EMITTER_FLOATS + 3] = radius;
    }

    /** Sets an emitter's base velocity and the most random speed added in any direction. */
    public void setEmitterVelocity(int emitter, float vx, float vy, float vz, float spread) {
        int o = emitter * EMITTER_FLOATS;
        emitters[o + 4] = vx;
        emitters[o + 5] = vy;
        emitters[o + 6] = vz;
        emitters[o + 7] = spread;
    }

    /** Sets an emitter's premultiplied start and end colors (alpha 0: additive). */
    public void setEmitterColors(
            int emitter,
            float r0,
            float g0,
            float b0,
            float a0,
            float r1,
            float g1,
            float b1,
            float a1) {
        int o = emitter * EMITTER_FLOATS + 8;
        float[] e = emitters;
        e[o] = r0;
        e[o + 1] = g0;
        e[o + 2] = b0;
        e[o + 3] = a0;
        e[o + 4] = r1;
        e[o + 5] = g1;
        e[o + 6] = b1;
        e[o + 7] = a1;
    }

    /** Sets an emitter's particle lifetime (seconds, ± {@code randomness} as a fraction). */
    public void setEmitterLifetime(int emitter, float seconds, float randomness) {
        int o = emitter * EMITTER_FLOATS;
        emitters[o + 16] = seconds;
        emitters[o + 17] = randomness;
    }

    /** Sets an emitter's particle half-size at birth and at death (meters). */
    public void setEmitterSize(int emitter, float start, float end) {
        int o = emitter * EMITTER_FLOATS;
        emitters[o + 18] = start;
        emitters[o + 19] = end;
    }

    /** Sets an emitter's gravity scale (1 = 9.81 m/s² down) and drag (1 / s). */
    public void setEmitterForces(int emitter, float gravity, float drag) {
        int o = emitter * EMITTER_FLOATS;
        emitters[o + 22] = gravity;
        emitters[o + 23] = drag;
    }

    /** Reveal points for camera fade: x, y, z, radius per point (at most 4 are used). */
    public final float[] reveal = new float[16];

    /** Number of reveal points. */
    public int revealCount;

    /**
     * Adds a reveal point (a party member): surfaces flagged {@link #FLAG_FADE_OCCLUDER} in front
     * of it dissolve within {@code radius} meters of the line of sight. At most 4; extra ones are
     * ignored.
     */
    public void addReveal(float x, float y, float z, float radius) {
        if (revealCount == 4) {
            return;
        }
        int o = revealCount++ * 4;
        reveal[o] = x;
        reveal[o + 1] = y;
        reveal[o + 2] = z;
        reveal[o + 3] = radius;
    }

    /**
     * Fog-of-war visibility grid, {@link #fogWidth}×{@link #fogHeight} cells row by row from {@code
     * (fogMinX, fogMinZ)}: 0 unexplored, 128 explored, 255 visible ({@link #setFogOfWar}). {@code
     * null}: no fog.
     */
    public byte[] fogOfWar;

    /** Fog grid width in cells. */
    public int fogWidth;

    /** Fog grid height in cells. */
    public int fogHeight;

    /** Fog grid world bounds: minimum x. */
    public float fogMinX;

    /** Fog grid world bounds: minimum z. */
    public float fogMinZ;

    /** Fog grid world extent along x. */
    public float fogSizeX;

    /** Fog grid world extent along z. */
    public float fogSizeZ;

    /**
     * Changes whenever the grid's content changes (a counter the game keeps across packets), so the
     * renderer uploads only new grids.
     */
    public int fogVersion;

    /** Brightness of explored cells out of sight. */
    public float fogExploredBrightness = 0.4f;

    /** Saturation of explored cells out of sight (0 grey, 1 full color). */
    public float fogExploredSaturation = 0.3f;

    /**
     * Copies a fog-of-war grid into this packet (growing its array if needed) covering {@code
     * sizeX}×{@code sizeZ} meters from {@code (minX, minZ)}; {@code version} is the game's counter
     * for the grid's content.
     */
    public void setFogOfWar(
            byte[] cells,
            int width,
            int height,
            float minX,
            float minZ,
            float sizeX,
            float sizeZ,
            int version) {
        int n = width * height;
        if (fogOfWar == null || fogOfWar.length < n) {
            fogOfWar = new byte[n];
        }
        System.arraycopy(cells, 0, fogOfWar, 0, n);
        fogWidth = width;
        fogHeight = height;
        fogMinX = minX;
        fogMinZ = minZ;
        fogSizeX = sizeX;
        fogSizeZ = sizeZ;
        fogVersion = version;
    }

    /** Sets the camera fade of instance {@code i} (0 solid .. 1 gone). */
    public void setFade(int i, float value) {
        fade[i] = value;
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
        fade[i] = 0;
        return i;
    }

    /**
     * Gives instance {@code i} a highlight style (1..15, the {@code HIGHLIGHT_*} defaults or game
     * styles set on the renderer's outlines), or removes it with 0. Sets or clears {@link
     * #FLAG_HIGHLIGHT}.
     */
    public void setHighlight(int i, int style) {
        int f = flags[i] & ~(0xF << HIGHLIGHT_SHIFT) & ~FLAG_HIGHLIGHT;
        if (style != 0) {
            f |= (style & 0xF) << HIGHLIGHT_SHIFT | FLAG_HIGHLIGHT;
        }
        flags[i] = f;
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
