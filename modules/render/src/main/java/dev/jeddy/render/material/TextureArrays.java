package dev.jeddy.render.material;

import dev.jeddy.core.handle.Handles;
import dev.jeddy.render.gpu.GpuDevice;
import dev.jeddy.render.gpu.GpuTextures;

import java.util.Arrays;

/**
 * Material textures as layers of 2D array textures, one array ("bucket") per (format, width,
 * height, levels), the binding model of ADR 0001 (DESIGN §9.2): a material refers to a texture by
 * {@code (bucket, layer)}, the shader samples {@code sampler2DArray}s with the layer as the third
 * coordinate, and draws whose materials share buckets share bindings. Buckets start with a few
 * layers and double on the GPU ({@code glCopyImageSubData}) when full; freed layers are reused.
 *
 * <p>A reference packs the bucket into the high 16 bits and the layer into the low 16 ({@link
 * #bucket}, {@link #layer}); {@link #NONE} is no texture.
 *
 * <p>Thread ownership: the GL thread.
 */
public final class TextureArrays implements AutoCloseable {
    /** No texture. */
    public static final int NONE = -1;

    private static final int INITIAL_LAYERS = 4;

    private final GpuDevice device;
    private final int maxLayers;
    private int bucketCount;
    private int[] format = new int[8];
    private int[] width = new int[8];
    private int[] height = new int[8];
    private int[] levels = new int[8];
    private int[] texture = new int[8];
    private int[] capacity = new int[8];
    private int[] used = new int[8];
    private int[][] freeLayers = new int[8][];
    private int[] freeCount = new int[8];
    private int generation;

    /** Creates an empty set of arrays. */
    public TextureArrays(GpuDevice device) {
        this.device = device;
        this.maxLayers = Math.max(64, device.caps().maxArrayTextureLayers());
    }

    /** Returns the bucket of a reference. */
    public static int bucket(int ref) {
        return ref >>> 16;
    }

    /** Returns the layer of a reference. */
    public static int layer(int ref) {
        return ref & 0xFFFF;
    }

    /**
     * Reserves a layer for a texture of this description, creating or growing its bucket.
     *
     * @return a reference ({@link #bucket}, {@link #layer})
     */
    public int allocate(int internalFormat, int w, int h, int levelCount) {
        int b = find(internalFormat, w, h, levelCount);
        if (b < 0) {
            b = createBucket(internalFormat, w, h, levelCount);
        }
        int layer;
        if (freeCount[b] > 0) {
            layer = freeLayers[b][--freeCount[b]];
        } else {
            if (used[b] == capacity[b]) {
                grow(b);
            }
            layer = used[b]++;
        }
        return b << 16 | layer;
    }

    /** Returns a layer to its bucket. */
    public void free(int ref) {
        if (ref == NONE) {
            return;
        }
        int b = bucket(ref);
        if (freeLayers[b].length == freeCount[b]) {
            freeLayers[b] = Arrays.copyOf(freeLayers[b], Math.max(8, freeCount[b] * 2));
        }
        freeLayers[b][freeCount[b]++] = layer(ref);
    }

    /** Returns the texture handle ({@link GpuTextures}) of a bucket. */
    public int textureHandle(int bucket) {
        return texture[bucket];
    }

    /** Returns the GL name of a bucket's array texture. */
    public int glName(int bucket) {
        return device.textures().glName(texture[bucket]);
    }

    /** Returns the number of buckets. */
    public int bucketCount() {
        return bucketCount;
    }

    /** Returns the number of layers in use (allocated minus freed) in a bucket. */
    public int layersInUse(int bucket) {
        return used[bucket] - freeCount[bucket];
    }

    /** Returns the layer capacity of a bucket. */
    public int capacity(int bucket) {
        return capacity[bucket];
    }

    /**
     * Returns a counter that changes whenever a bucket's GL texture is replaced (growth), so cached
     * bindings can be refreshed.
     */
    public int generation() {
        return generation;
    }

    private int find(int internalFormat, int w, int h, int levelCount) {
        for (int b = 0; b < bucketCount; b++) {
            if (format[b] == internalFormat
                    && width[b] == w
                    && height[b] == h
                    && levels[b] == levelCount
                    && (freeCount[b] > 0 || used[b] < maxLayers)) {
                return b;
            }
        }
        return -1;
    }

    private int createBucket(int internalFormat, int w, int h, int levelCount) {
        if (bucketCount == 0xFFFF) {
            throw new IllegalStateException("too many texture buckets");
        }
        if (bucketCount == format.length) {
            int cap = bucketCount * 2;
            format = Arrays.copyOf(format, cap);
            width = Arrays.copyOf(width, cap);
            height = Arrays.copyOf(height, cap);
            levels = Arrays.copyOf(levels, cap);
            texture = Arrays.copyOf(texture, cap);
            capacity = Arrays.copyOf(capacity, cap);
            used = Arrays.copyOf(used, cap);
            freeLayers = Arrays.copyOf(freeLayers, cap);
            freeCount = Arrays.copyOf(freeCount, cap);
        }
        int b = bucketCount++;
        format[b] = internalFormat;
        width[b] = w;
        height[b] = h;
        levels[b] = levelCount;
        capacity[b] = INITIAL_LAYERS;
        texture[b] =
                device.textures()
                        .create(
                                GpuTextures.KIND_2D_ARRAY,
                                internalFormat,
                                w,
                                h,
                                INITIAL_LAYERS,
                                levelCount,
                                "texture bucket " + b + " " + w + "x" + h);
        freeLayers[b] = new int[8];
        generation++;
        return b;
    }

    private void grow(int b) {
        int cap = Math.min(maxLayers, capacity[b] * 2);
        if (cap == capacity[b]) {
            throw new IllegalStateException("texture bucket " + b + " is at the layer limit");
        }
        int bigger =
                device.textures()
                        .create(
                                GpuTextures.KIND_2D_ARRAY,
                                format[b],
                                width[b],
                                height[b],
                                cap,
                                levels[b],
                                "texture bucket " + b + " " + width[b] + "x" + height[b]);
        device.textures().copyLayers(texture[b], bigger, used[b]);
        device.textures().destroy(texture[b]);
        texture[b] = bigger;
        capacity[b] = cap;
        generation++;
    }

    @Override
    public void close() {
        for (int b = 0; b < bucketCount; b++) {
            if (texture[b] != Handles.NULL) {
                device.textures().destroy(texture[b]);
                texture[b] = Handles.NULL;
            }
        }
        bucketCount = 0;
    }
}
