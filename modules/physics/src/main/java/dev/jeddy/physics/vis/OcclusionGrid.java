package dev.jeddy.physics.vis;

import dev.jeddy.physics.bvh.Bvh;
import dev.jeddy.physics.bvh.TriFlags;
import dev.jeddy.physics.debug.DebugSink;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * The fog-of-war occlusion grid (DESIGN §11.5): square cells over the level's XZ footprint, one bit
 * each ({@code long[]} words, rows of {@link #wordsPerRow} words), set where geometry that blocks
 * sight stands at eye height. Baked from the static BVH's {@link TriFlags#BLOCKS_LOS} triangles
 * with a conservative 2D triangle–cell test (walls project to segments); doors and other dynamic
 * occluders flip cells at run time, which bumps {@link #version}.
 *
 * <p>Thread ownership: one writer (bake, {@link #setBlocked}); readers between writes.
 */
public final class OcclusionGrid {
    /** Format magic of serialized grids ({@code .jocc}). */
    public static final int MAGIC = 0x43434F4A; // "JOCC"

    /** Format version. */
    public static final int VERSION = 1;

    private final float minX;
    private final float minZ;
    private final float cellSize;
    private final int width;
    private final int height;
    private final int wordsPerRow;
    private final long[] bits;
    private int version;

    /** Creates an open grid of {@code width × height} cells from {@code (minX, minZ)}. */
    public OcclusionGrid(float minX, float minZ, float cellSize, int width, int height) {
        this.minX = minX;
        this.minZ = minZ;
        this.cellSize = cellSize;
        this.width = width;
        this.height = height;
        this.wordsPerRow = (width + 63) >>> 6;
        this.bits = new long[wordsPerRow * height];
    }

    /**
     * Bakes a grid over {@code sizeX × sizeZ} meters from {@code (minX, minZ)}: a cell blocks when
     * a triangle in {@code mask} whose height range reaches into {@code [yMin, yMax]} (eye height
     * above a single-floor level) touches it.
     */
    public static OcclusionGrid bake(
            Bvh bvh,
            float minX,
            float minZ,
            float sizeX,
            float sizeZ,
            float cellSize,
            float yMin,
            float yMax,
            int mask) {
        int w = Math.max(1, (int) Math.ceil(sizeX / cellSize));
        int h = Math.max(1, (int) Math.ceil(sizeZ / cellSize));
        OcclusionGrid g = new OcclusionGrid(minX, minZ, cellSize, w, h);
        float[] a = new float[3];
        float[] b = new float[3];
        float[] c = new float[3];
        for (int t = 0; t < bvh.triangleCount(); t++) {
            if ((bvh.triangleFlags(t) & mask) == 0) {
                continue;
            }
            bvh.vertex(t, 0, a);
            bvh.vertex(t, 1, b);
            bvh.vertex(t, 2, c);
            if (Math.max(a[1], Math.max(b[1], c[1])) < yMin
                    || Math.min(a[1], Math.min(b[1], c[1])) > yMax) {
                continue;
            }
            g.rasterize(a[0], a[2], b[0], b[2], c[0], c[2]);
        }
        return g;
    }

    /** Marks every cell the 2D triangle touches (degenerate triangles: their segment). */
    void rasterize(float ax, float az, float bx, float bz, float cx, float cz) {
        int x0 = Math.max(0, cellX(Math.min(ax, Math.min(bx, cx))));
        int x1 = Math.min(width - 1, cellX(Math.max(ax, Math.max(bx, cx))));
        int z0 = Math.max(0, cellZ(Math.min(az, Math.min(bz, cz))));
        int z1 = Math.min(height - 1, cellZ(Math.max(az, Math.max(bz, cz))));
        for (int z = z0; z <= z1; z++) {
            float lz = minZ + z * cellSize;
            for (int x = x0; x <= x1; x++) {
                float lx = minX + x * cellSize;
                if (triangleTouchesBox(
                        ax, az, bx, bz, cx, cz, lx, lz, lx + cellSize, lz + cellSize)) {
                    setBlocked(x, z, true);
                }
            }
        }
    }

    /** Separating-axis test of a 2D triangle (possibly degenerate) and a box. */
    static boolean triangleTouchesBox(
            float ax,
            float az,
            float bx,
            float bz,
            float cx,
            float cz,
            float lx,
            float lz,
            float hx,
            float hz) {
        if (Math.max(ax, Math.max(bx, cx)) < lx
                || Math.min(ax, Math.min(bx, cx)) > hx
                || Math.max(az, Math.max(bz, cz)) < lz
                || Math.min(az, Math.min(bz, cz)) > hz) {
            return false;
        }
        return !separates(ax, az, bx, bz, ax, az, bx, bz, cx, cz, lx, lz, hx, hz)
                && !separates(bx, bz, cx, cz, ax, az, bx, bz, cx, cz, lx, lz, hx, hz)
                && !separates(cx, cz, ax, az, ax, az, bx, bz, cx, cz, lx, lz, hx, hz);
    }

    /** Whether the normal of edge p–q separates the triangle from the box. */
    private static boolean separates(
            float px,
            float pz,
            float qx,
            float qz,
            float ax,
            float az,
            float bx,
            float bz,
            float cx,
            float cz,
            float lx,
            float lz,
            float hx,
            float hz) {
        float nx = -(qz - pz);
        float nz = qx - px;
        if (nx == 0 && nz == 0) {
            return false;
        }
        float t0 = nx * ax + nz * az;
        float t1 = nx * bx + nz * bz;
        float t2 = nx * cx + nz * cz;
        float tMin = Math.min(t0, Math.min(t1, t2));
        float tMax = Math.max(t0, Math.max(t1, t2));
        float b0 = nx * lx + nz * lz;
        float b1 = nx * hx + nz * lz;
        float b2 = nx * lx + nz * hz;
        float b3 = nx * hx + nz * hz;
        float bMin = Math.min(Math.min(b0, b1), Math.min(b2, b3));
        float bMax = Math.max(Math.max(b0, b1), Math.max(b2, b3));
        return tMax < bMin || tMin > bMax;
    }

    /** Returns the cell column of world {@code x} (may be outside the grid). */
    public int cellX(float x) {
        return (int) Math.floor((x - minX) / cellSize);
    }

    /** Returns the cell row of world {@code z} (may be outside the grid). */
    public int cellZ(float z) {
        return (int) Math.floor((z - minZ) / cellSize);
    }

    /** Returns whether cell {@code (x, z)} blocks sight; cells outside the grid do. */
    public boolean blocked(int x, int z) {
        if (x < 0 || z < 0 || x >= width || z >= height) {
            return true;
        }
        return (bits[z * wordsPerRow + (x >>> 6)] & 1L << (x & 63)) != 0;
    }

    /** Sets or clears cell {@code (x, z)} (doors, summoned walls) and bumps the version. */
    public void setBlocked(int x, int z, boolean blocked) {
        int w = z * wordsPerRow + (x >>> 6);
        long bit = 1L << (x & 63);
        long before = bits[w];
        bits[w] = blocked ? before | bit : before & ~bit;
        if (bits[w] != before) {
            version++;
        }
    }

    /** Blocks or opens every cell a world-space segment crosses (a door's line). */
    public void setSegment(float ax, float az, float bx, float bz, boolean blocked) {
        int x0 = Math.max(0, cellX(Math.min(ax, bx)));
        int x1 = Math.min(width - 1, cellX(Math.max(ax, bx)));
        int z0 = Math.max(0, cellZ(Math.min(az, bz)));
        int z1 = Math.min(height - 1, cellZ(Math.max(az, bz)));
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                float lx = minX + x * cellSize;
                float lz = minZ + z * cellSize;
                if (triangleTouchesBox(
                        ax, az, bx, bz, bx, bz, lx, lz, lx + cellSize, lz + cellSize)) {
                    setBlocked(x, z, blocked);
                }
            }
        }
    }

    /** Returns a counter that changes whenever a cell changes. */
    public int version() {
        return version;
    }

    /** Returns the grid width in cells. */
    public int width() {
        return width;
    }

    /** Returns the grid height in cells. */
    public int height() {
        return height;
    }

    /** Returns the cell size in meters. */
    public float cellSize() {
        return cellSize;
    }

    /** Returns the grid origin x. */
    public float minX() {
        return minX;
    }

    /** Returns the grid origin z. */
    public float minZ() {
        return minZ;
    }

    /** Returns the number of {@code long} words per row. */
    public int wordsPerRow() {
        return wordsPerRow;
    }

    /** Returns the number of blocked cells. */
    public int blockedCount() {
        int n = 0;
        for (long w : bits) {
            n += Long.bitCount(w);
        }
        return n;
    }

    /** Serializes ({@code .jocc}, docs/formats/jocc.md). */
    public byte[] toBytes() {
        ByteBuffer b = ByteBuffer.allocate(28 + bits.length * 8).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(MAGIC)
                .putInt(VERSION)
                .putInt(width)
                .putInt(height)
                .putFloat(cellSize)
                .putFloat(minX)
                .putFloat(minZ);
        for (long w : bits) {
            b.putLong(w);
        }
        return b.array();
    }

    /** Reads a serialized grid. */
    public static OcclusionGrid read(ByteBuffer data) {
        ByteBuffer b = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        if (b.remaining() < 28 || b.getInt() != MAGIC) {
            throw new IllegalArgumentException("not a .jocc occlusion grid");
        }
        int version = b.getInt();
        if (version != VERSION) {
            throw new IllegalArgumentException(".jocc version " + version + " is not supported");
        }
        int w = b.getInt();
        int h = b.getInt();
        float cell = b.getFloat();
        float x = b.getFloat();
        float z = b.getFloat();
        OcclusionGrid g = new OcclusionGrid(x, z, cell, w, h);
        if (b.remaining() < g.bits.length * 8L) {
            throw new IllegalArgumentException(".jocc is truncated");
        }
        b.asLongBuffer().get(g.bits);
        return g;
    }

    long[] bits() {
        return bits;
    }

    /** Returns whether two grids have the same cells. */
    public boolean sameCells(OcclusionGrid other) {
        return width == other.width && height == other.height && Arrays.equals(bits, other.bits);
    }

    /**
     * Draws the blocked cells inside {@code [x0, x1] × [z0, z1]} as squares at height {@code y}
     * (debug view; allocates nothing).
     */
    public void debugDraw(
            DebugSink sink, int thread, float x0, float z0, float x1, float z1, float y, int argb) {
        int cx0 = Math.max(0, cellX(x0));
        int cx1 = Math.min(width() - 1, cellX(x1));
        int cz0 = Math.max(0, cellZ(z0));
        int cz1 = Math.min(height() - 1, cellZ(z1));
        float cs = cellSize();
        float inset = cs * 0.1f;
        for (int z = cz0; z <= cz1; z++) {
            for (int x = cx0; x <= cx1; x++) {
                if (!blocked(x, z)) {
                    continue;
                }
                float ax = minX() + x * cs + inset;
                float az = minZ() + z * cs + inset;
                float bx = ax + cs - inset * 2;
                float bz = az + cs - inset * 2;
                sink.line(thread, ax, y, az, bx, y, az, argb);
                sink.line(thread, bx, y, az, bx, y, bz, argb);
                sink.line(thread, bx, y, bz, ax, y, bz, argb);
                sink.line(thread, ax, y, bz, ax, y, az, argb);
            }
        }
    }
}
