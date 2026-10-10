package dev.jeddy.physics.grid;

import dev.jeddy.core.collections.IntList;
import dev.jeddy.core.job.JobKernel;
import dev.jeddy.core.job.JobSystem;

import java.util.Arrays;

/**
 * The dynamic broadphase (DESIGN §11.2): a uniform grid over the level's XZ footprint, rebuilt from
 * scratch every tick with a counting sort. Per-chunk histograms of the cells each body's footprint
 * covers, a prefix sum, then a scatter in which every chunk writes to offsets computed in advance,
 * so the cell lists are built in parallel, without allocation, and in a stable, deterministic order
 * (bodies ascending within each cell), which ORCA and trigger results rely on. Queries return
 * candidates once each, even for bodies spanning several cells.
 *
 * <p>Thread ownership: {@link #rebuild} on the physics thread (borrowing job workers); queries from
 * any thread between rebuilds.
 */
public final class UniformGrid {
    private static final int PARALLEL_FROM = 8192;
    private static final int CHUNKS = 8;

    private final float minX;
    private final float minZ;
    private final float cell;
    private final float inverseCell;
    private final int cellsX;
    private final int cellsZ;
    private final JobSystem jobs;
    private final int[] cellStart;
    private int[] entries = new int[1024];
    private int entryCount;
    private int[] x0 = new int[256];
    private int[] z0 = new int[256];
    private int[] x1 = new int[256];
    private int[] z1 = new int[256];
    private int[] layers = new int[256];
    private final int[][] histogram;
    private int bodyCount;
    private Bodies bodies;
    private int chunks;
    private final JobKernel rangesKernel = this::rangesChunk;
    private final JobKernel scatterKernel = this::scatterChunk;

    /**
     * Creates a grid of {@code cellSize}-meter cells covering {@code [minX, minX + sizeX) × [minZ,
     * minZ + sizeZ)}; bodies outside are clamped to the border cells. {@code jobs} may be {@code
     * null}.
     */
    public UniformGrid(
            float minX, float minZ, float sizeX, float sizeZ, float cellSize, JobSystem jobs) {
        this.minX = minX;
        this.minZ = minZ;
        this.cell = cellSize;
        this.inverseCell = 1f / cellSize;
        this.cellsX = Math.max(1, (int) Math.ceil(sizeX / cellSize));
        this.cellsZ = Math.max(1, (int) Math.ceil(sizeZ / cellSize));
        this.jobs = jobs;
        cellStart = new int[cellsX * cellsZ + 1];
        histogram = new int[CHUNKS][cellsX * cellsZ];
    }

    /** Returns the cell size in meters. */
    public float cellSize() {
        return cell;
    }

    /** Returns the number of cells along x. */
    public int cellsX() {
        return cellsX;
    }

    /** Returns the number of cells along z. */
    public int cellsZ() {
        return cellsZ;
    }

    /** Returns the grid origin x. */
    public float minX() {
        return minX;
    }

    /** Returns the grid origin z. */
    public float minZ() {
        return minZ;
    }

    /** Returns the cell column of world {@code x}, clamped to the grid. */
    public int cellX(float x) {
        return Math.clamp((int) Math.floor((x - minX) * inverseCell), 0, cellsX - 1);
    }

    /** Returns the cell row of world {@code z}, clamped to the grid. */
    public int cellZ(float z) {
        return Math.clamp((int) Math.floor((z - minZ) * inverseCell), 0, cellsZ - 1);
    }

    /** Returns the number of body entries (bodies times cells they cover) after the rebuild. */
    public int entryCount() {
        return entryCount;
    }

    /** Re-sorts every body of {@code b} into the cells its footprint covers. */
    public void rebuild(Bodies b) {
        bodies = b;
        int n = b.count();
        bodyCount = n;
        if (x0.length < n) {
            int cap = Math.max(n, x0.length * 2);
            x0 = new int[cap];
            z0 = new int[cap];
            x1 = new int[cap];
            z1 = new int[cap];
            layers = new int[cap];
        }
        boolean parallel = jobs != null && n >= PARALLEL_FROM;
        chunks = parallel ? CHUNKS : 1;
        int cells = cellsX * cellsZ;
        if (parallel) {
            jobs.parallelFor(rangesKernel, chunks, 1);
        } else {
            rangesChunk(0, 1, 0);
        }
        // Prefix sums: cell starts, then each chunk's first slot in each cell.
        int total = 0;
        for (int c = 0; c < cells; c++) {
            cellStart[c] = total;
            for (int k = 0; k < chunks; k++) {
                int h = histogram[k][c];
                histogram[k][c] = total;
                total += h;
            }
        }
        cellStart[cells] = total;
        entryCount = total;
        if (entries.length < total) {
            entries = new int[Math.max(total, entries.length * 2)];
        }
        if (parallel) {
            jobs.parallelFor(scatterKernel, chunks, 1);
        } else {
            scatterChunk(0, 1, 0);
        }
        bodies = null;
    }

    private int chunkStart(int k) {
        return (int) ((long) bodyCount * k / chunks);
    }

    /** Footprint cell ranges and the chunk's histogram. */
    private void rangesChunk(int from, int to, int thread) {
        Bodies b = bodies;
        for (int k = from; k < to; k++) {
            int[] hist = histogram[k];
            Arrays.fill(hist, 0);
            for (int i = chunkStart(k), end = chunkStart(k + 1); i < end; i++) {
                float ex = b.extentX[i];
                float ez = b.extentZ[i];
                int ax = cellX(b.x[i] - ex);
                int az = cellZ(b.z[i] - ez);
                int bx = cellX(b.x[i] + ex);
                int bz = cellZ(b.z[i] + ez);
                x0[i] = ax;
                z0[i] = az;
                x1[i] = bx;
                z1[i] = bz;
                layers[i] = b.layers[i];
                for (int cz = az; cz <= bz; cz++) {
                    int row = cz * cellsX;
                    for (int cx = ax; cx <= bx; cx++) {
                        hist[row + cx]++;
                    }
                }
            }
        }
    }

    private void scatterChunk(int from, int to, int thread) {
        for (int k = from; k < to; k++) {
            int[] next = histogram[k];
            for (int i = chunkStart(k), end = chunkStart(k + 1); i < end; i++) {
                for (int cz = z0[i]; cz <= z1[i]; cz++) {
                    int row = cz * cellsX;
                    for (int cx = x0[i]; cx <= x1[i]; cx++) {
                        entries[next[row + cx]++] = i;
                    }
                }
            }
        }
    }

    /**
     * Appends to {@code out} every body (index into the rebuilt {@link Bodies}) whose footprint
     * cells overlap the rectangle and whose layers share a bit with {@code layerMask}, each once,
     * in grid order. Candidates only: callers test the exact shapes. Allocates nothing unless
     * {@code out} grows.
     *
     * @return the number of bodies appended
     */
    public int query(float qx0, float qz0, float qx1, float qz1, int layerMask, IntList out) {
        int ax = cellX(qx0);
        int az = cellZ(qz0);
        int bx = cellX(qx1);
        int bz = cellZ(qz1);
        int added = 0;
        for (int cz = az; cz <= bz; cz++) {
            for (int cx = ax; cx <= bx; cx++) {
                int c = cz * cellsX + cx;
                for (int e = cellStart[c], end = cellStart[c + 1]; e < end; e++) {
                    int i = entries[e];
                    // Report a multi-cell body only from the first cell both rectangles share.
                    if ((layers[i] & layerMask) == 0
                            || cx != Math.max(x0[i], ax)
                            || cz != Math.max(z0[i], az)) {
                        continue;
                    }
                    out.add(i);
                    added++;
                }
            }
        }
        return added;
    }

    /** Returns the first entry of cell {@code (cx, cz)} in {@link #entries()}. */
    public int cellBegin(int cx, int cz) {
        return cellStart[cz * cellsX + cx];
    }

    /** Returns the end of cell {@code (cx, cz)}'s entries. */
    public int cellEnd(int cx, int cz) {
        return cellStart[cz * cellsX + cx + 1];
    }

    /** Returns the cell entries (body indices); read with {@link #cellBegin}/{@link #cellEnd}. */
    public int[] entries() {
        return entries;
    }

    /** Returns the layers body {@code i} had at the last rebuild. */
    public int layersOf(int i) {
        return layers[i];
    }

    /** Returns whether body {@code i}'s first footprint cell is {@code (cx, cz)} (dedupe). */
    public boolean firstCell(int i, int cx, int cz, int fromX, int fromZ) {
        return cx == Math.max(x0[i], fromX) && cz == Math.max(z0[i], fromZ);
    }
}
