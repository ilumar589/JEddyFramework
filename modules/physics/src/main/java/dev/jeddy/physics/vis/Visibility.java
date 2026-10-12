package dev.jeddy.physics.vis;

import dev.jeddy.core.io.ByteReader;
import dev.jeddy.core.io.ByteWriter;
import dev.jeddy.core.job.JobKernel;
import dev.jeddy.core.job.JobSystem;

import java.util.Arrays;

/**
 * Fog of war (DESIGN §11.5): viewers (party members, allies) see the occlusion grid's cells through
 * <b>symmetric shadowcasting</b> (Albert Ford's algorithm: if A sees B then B sees A, walls are
 * lit, and there are no artifacts at pillars) within their sight radius. Each viewer keeps its own
 * visible patch, recomputed in parallel only when it moved past a threshold, its radius changed or
 * the occlusion grid changed; the patches are OR-ed into the {@code visible} bitset, which is OR-ed
 * into {@code explored}. {@link #pack} turns both into the byte grid the renderer draws ({@code
 * RenderPacket.setFogOfWar}: 255 visible, 128 explored, 0 unexplored).
 *
 * <p>Thread ownership: the game thread (fog-of-war step), borrowing job workers; queries such as
 * {@link #isVisible} between updates.
 */
public final class Visibility {
    /** Byte value of visible cells in {@link #pack}. */
    public static final byte VISIBLE = (byte) 255;

    /** Byte value of explored cells out of sight. */
    public static final byte EXPLORED = (byte) 128;

    private final OcclusionGrid grid;
    private final JobSystem jobs;
    private final int words;
    private final long[] visible;
    private final long[] explored;
    private float moveThreshold = 0.25f;

    // Viewers (SoA) and their cached patches.
    private int viewerCount;
    private int[] viewerId = new int[8];
    private float[] vx = new float[8];
    private float[] vz = new float[8];
    private float[] vRadius = new float[8];
    private float[] cachedX = new float[8];
    private float[] cachedZ = new float[8];
    private float[] cachedRadius = new float[8];
    private int[] cachedVersion = new int[8];
    private int[] patchX = new int[8];
    private int[] patchZ = new int[8];
    private int[] patchSize = new int[8];
    private long[][] patch = new long[8][];
    private boolean[] dirty = new boolean[8];
    private final Scanner[] scanners;
    private int[] dirtyList = new int[8];
    private int dirtyCount;
    private final JobKernel kernel = this::scanRange;

    /** Creates visibility over {@code grid}; {@code jobs} may be {@code null}. */
    public Visibility(OcclusionGrid grid, JobSystem jobs) {
        this.grid = grid;
        this.jobs = jobs;
        this.words = grid.wordsPerRow() * grid.height();
        this.visible = new long[words];
        this.explored = new long[words];
        int slots = jobs == null ? 1 : jobs.threadSlots();
        scanners = new Scanner[slots];
        for (int i = 0; i < slots; i++) {
            scanners[i] = new Scanner();
        }
    }

    /** Sets how far (meters) a viewer moves before its view is recomputed (default 0.25). */
    public void setMoveThreshold(float meters) {
        moveThreshold = meters;
    }

    /** Adds or moves the viewer {@code id} at world {@code (x, z)} seeing {@code radius} meters. */
    public void setViewer(int id, float x, float z, float radius) {
        int v = find(id);
        if (v < 0) {
            if (viewerCount == viewerId.length) {
                growViewers();
            }
            v = viewerCount++;
            viewerId[v] = id;
            cachedVersion[v] = Integer.MIN_VALUE;
            cachedRadius[v] = -1;
        }
        vx[v] = x;
        vz[v] = z;
        vRadius[v] = radius;
    }

    /** Removes viewer {@code id} (its cells stop being visible; explored stays). */
    public void removeViewer(int id) {
        int v = find(id);
        if (v < 0) {
            return;
        }
        int last = --viewerCount;
        viewerId[v] = viewerId[last];
        vx[v] = vx[last];
        vz[v] = vz[last];
        vRadius[v] = vRadius[last];
        cachedX[v] = cachedX[last];
        cachedZ[v] = cachedZ[last];
        cachedRadius[v] = cachedRadius[last];
        cachedVersion[v] = cachedVersion[last];
        patchX[v] = patchX[last];
        patchZ[v] = patchZ[last];
        patchSize[v] = patchSize[last];
        long[] p = patch[v];
        patch[v] = patch[last];
        patch[last] = p;
    }

    /** Returns the number of viewers. */
    public int viewerCount() {
        return viewerCount;
    }

    private int find(int id) {
        for (int v = 0; v < viewerCount; v++) {
            if (viewerId[v] == id) {
                return v;
            }
        }
        return -1;
    }

    private void growViewers() {
        int cap = viewerId.length * 2;
        viewerId = Arrays.copyOf(viewerId, cap);
        vx = Arrays.copyOf(vx, cap);
        vz = Arrays.copyOf(vz, cap);
        vRadius = Arrays.copyOf(vRadius, cap);
        cachedX = Arrays.copyOf(cachedX, cap);
        cachedZ = Arrays.copyOf(cachedZ, cap);
        cachedRadius = Arrays.copyOf(cachedRadius, cap);
        cachedVersion = Arrays.copyOf(cachedVersion, cap);
        patchX = Arrays.copyOf(patchX, cap);
        patchZ = Arrays.copyOf(patchZ, cap);
        patchSize = Arrays.copyOf(patchSize, cap);
        patch = Arrays.copyOf(patch, cap);
        dirty = Arrays.copyOf(dirty, cap);
        dirtyList = Arrays.copyOf(dirtyList, cap);
    }

    /**
     * Recomputes the views of viewers that moved (or whose grid changed) in parallel, then rebuilds
     * {@code visible} and grows {@code explored}.
     *
     * @return the number of viewers recomputed
     */
    public int update() {
        dirtyCount = 0;
        int version = grid.version();
        for (int v = 0; v < viewerCount; v++) {
            float dx = vx[v] - cachedX[v];
            float dz = vz[v] - cachedZ[v];
            boolean moved = dx * dx + dz * dz > moveThreshold * moveThreshold;
            if (moved || cachedVersion[v] != version || cachedRadius[v] != vRadius[v]) {
                cachedX[v] = vx[v];
                cachedZ[v] = vz[v];
                cachedRadius[v] = vRadius[v];
                cachedVersion[v] = version;
                int r = (int) Math.ceil(vRadius[v] / grid.cellSize());
                int size = 2 * r + 1;
                int needed = size * ((size + 63) >>> 6);
                if (patch[v] == null || patch[v].length < needed) {
                    patch[v] = new long[Math.max(needed, 64)];
                }
                patchSize[v] = size;
                patchX[v] = grid.cellX(vx[v]) - r;
                patchZ[v] = grid.cellZ(vz[v]) - r;
                dirtyList[dirtyCount++] = v;
            }
        }
        if (jobs != null && dirtyCount > 1) {
            jobs.parallelFor(kernel, dirtyCount, 1);
        } else {
            scanRange(0, dirtyCount, 0);
        }
        Arrays.fill(visible, 0);
        for (int v = 0; v < viewerCount; v++) {
            orPatch(v);
        }
        for (int i = 0; i < words; i++) {
            explored[i] |= visible[i];
        }
        return dirtyCount;
    }

    private void scanRange(int from, int to, int thread) {
        Scanner s = scanners[thread];
        for (int k = from; k < to; k++) {
            int v = dirtyList[k];
            long[] p = patch[v];
            int size = patchSize[v];
            int rowWords = (size + 63) >>> 6;
            Arrays.fill(p, 0, size * rowWords, 0);
            s.scan(this, v, p, size, rowWords);
        }
    }

    /** ORs viewer v's patch into {@code visible}, clipped to the grid. */
    private void orPatch(int v) {
        long[] p = patch[v];
        int size = patchSize[v];
        int rowWords = (size + 63) >>> 6;
        int wpr = grid.wordsPerRow();
        int width = grid.width();
        for (int row = 0; row < size; row++) {
            int z = patchZ[v] + row;
            if (z < 0 || z >= grid.height()) {
                continue;
            }
            int base = row * rowWords;
            for (int w = 0; w < rowWords; w++) {
                long bitsWord = p[base + w];
                while (bitsWord != 0) {
                    int b = Long.numberOfTrailingZeros(bitsWord);
                    bitsWord &= bitsWord - 1;
                    int x = patchX[v] + w * 64 + b;
                    if (x >= 0 && x < width) {
                        visible[z * wpr + (x >>> 6)] |= 1L << (x & 63);
                    }
                }
            }
        }
    }

    /** Returns whether cell {@code (cx, cz)} is visible to some viewer. */
    public boolean isVisibleCell(int cx, int cz) {
        return inside(cx, cz)
                && (visible[cz * grid.wordsPerRow() + (cx >>> 6)] & 1L << (cx & 63)) != 0;
    }

    /** Returns whether cell {@code (cx, cz)} was ever visible. */
    public boolean isExploredCell(int cx, int cz) {
        return inside(cx, cz)
                && (explored[cz * grid.wordsPerRow() + (cx >>> 6)] & 1L << (cx & 63)) != 0;
    }

    /** Returns whether world {@code (x, z)} is visible to some viewer (AI, stealth). */
    public boolean isVisible(float x, float z) {
        return isVisibleCell(grid.cellX(x), grid.cellZ(z));
    }

    /** Returns whether world {@code (x, z)} was ever visible. */
    public boolean isExplored(float x, float z) {
        return isExploredCell(grid.cellX(x), grid.cellZ(z));
    }

    private boolean inside(int cx, int cz) {
        return cx >= 0 && cz >= 0 && cx < grid.width() && cz < grid.height();
    }

    /** Marks every cell explored (cheats, revealed maps). */
    public void exploreAll() {
        Arrays.fill(explored, -1L);
    }

    /** Forgets what was explored. */
    public void clearExplored() {
        Arrays.fill(explored, 0);
    }

    /** Returns the explored bitset (save games): {@code wordsPerRow × height} words. */
    public long[] exploredBits() {
        return explored;
    }

    /** The layout {@link #write} uses (D117). */
    public static final int SAVE_FORMAT = 1;

    /**
     * Writes the grid's size and the explored bits (saves; the visible bits are recomputed by the
     * next {@link #update}). Allocates nothing.
     */
    public void write(ByteWriter w) {
        w.int32(SAVE_FORMAT);
        w.int32(grid.width());
        w.int32(grid.height());
        w.longs(explored);
    }

    /**
     * Reads what {@link #write} wrote. A save from a grid of another size (the level's occlusion
     * grid was re-cooked) is skipped and the exploration cleared, since its bits would land on the
     * wrong cells. Returns whether the bits were taken. Marks every viewer dirty so the next update
     * recomputes the visible bits.
     */
    public boolean read(ByteReader r) {
        int format = r.int32();
        if (format != 1) {
            throw new IllegalStateException("fog save format " + format + "; this build reads 1");
        }
        int width = r.int32();
        int height = r.int32();
        int n = r.int32();
        boolean fits = width == grid.width() && height == grid.height() && n == explored.length;
        if (!fits) {
            r.skip(8 * n);
            clearExplored();
        } else {
            for (int i = 0; i < n; i++) {
                explored[i] = r.int64();
            }
        }
        for (int v = 0; v < viewerCount; v++) {
            cachedVersion[v] = Integer.MIN_VALUE; // every viewer rescans
        }
        return fits;
    }

    /**
     * Writes one byte per cell, row by row ({@code width × height}): {@link #VISIBLE}, {@link
     * #EXPLORED} or 0. Allocates nothing.
     */
    public void pack(byte[] out) {
        int width = grid.width();
        int wpr = grid.wordsPerRow();
        for (int z = 0; z < grid.height(); z++) {
            int row = z * width;
            int wrow = z * wpr;
            for (int x = 0; x < width; x++) {
                long bit = 1L << (x & 63);
                int w = wrow + (x >>> 6);
                out[row + x] =
                        (visible[w] & bit) != 0 ? VISIBLE : (explored[w] & bit) != 0 ? EXPLORED : 0;
            }
        }
    }

    /** Returns the occlusion grid. */
    public OcclusionGrid grid() {
        return grid;
    }

    /**
     * Symmetric shadowcasting for one viewer at a time, with an explicit row stack. Slopes are
     * exact rationals {@code (2·col − 1) / (2·depth)} kept as integer pairs.
     */
    static final class Scanner {
        // Row stack: depth, start slope (num, den), end slope (num, den).
        private int[] rows = new int[5 * 64];
        private long[] p;
        private int size;
        private int rowWords;
        private int originX;
        private int originZ;
        private int patchOriginX;
        private int patchOriginZ;
        private int radius;
        private long radius2;
        private OcclusionGrid grid;

        void scan(Visibility vis, int v, long[] patchBits, int patchSize, int words) {
            grid = vis.grid;
            p = patchBits;
            size = patchSize;
            rowWords = words;
            radius = (patchSize - 1) / 2;
            radius2 = (long) radius * radius + radius; // round the circle's edge
            patchOriginX = vis.patchX[v];
            patchOriginZ = vis.patchZ[v];
            originX = patchOriginX + radius;
            originZ = patchOriginZ + radius;
            reveal(originX, originZ);
            for (int quadrant = 0; quadrant < 4; quadrant++) {
                scanQuadrant(quadrant);
            }
        }

        private void reveal(int x, int z) {
            int dx = x - originX;
            int dz = z - originZ;
            if ((long) dx * dx + (long) dz * dz > radius2) {
                return;
            }
            int px = x - patchOriginX;
            int pz = z - patchOriginZ;
            p[pz * rowWords + (px >>> 6)] |= 1L << (px & 63);
        }

        /** World cell x of quadrant tile (depth, col). */
        private int tx(int quadrant, int depth, int col) {
            return switch (quadrant) {
                case 0, 2 -> originX + col; // north, south
                case 1 -> originX + depth; // east
                default -> originX - depth; // west
            };
        }

        private int tz(int quadrant, int depth, int col) {
            return switch (quadrant) {
                case 0 -> originZ - depth;
                case 2 -> originZ + depth;
                default -> originZ + col;
            };
        }

        private void push(int sp, int depth, int sn, int sd, int en, int ed) {
            if ((sp + 1) * 5 > rows.length) {
                rows = Arrays.copyOf(rows, rows.length * 2);
            }
            int o = sp * 5;
            rows[o] = depth;
            rows[o + 1] = sn;
            rows[o + 2] = sd;
            rows[o + 3] = en;
            rows[o + 4] = ed;
        }

        private void scanQuadrant(int quadrant) {
            int sp = 0;
            push(sp++, 1, -1, 1, 1, 1);
            while (sp > 0) {
                int o = --sp * 5;
                int depth = rows[o];
                int sn = rows[o + 1];
                int sd = rows[o + 2];
                int en = rows[o + 3];
                int ed = rows[o + 4];
                if (depth > radius) {
                    continue;
                }
                // Columns: round_ties_up(depth · start) .. round_ties_down(depth · end).
                int minCol = floorDiv(2 * depth * sn + sd, 2 * sd);
                int maxCol = ceilDiv(2 * depth * en - ed, 2 * ed);
                int prev = -1; // -1 none, 0 floor, 1 wall
                for (int col = minCol; col <= maxCol; col++) {
                    int x = tx(quadrant, depth, col);
                    int z = tz(quadrant, depth, col);
                    boolean wall = grid.blocked(x, z);
                    // Symmetric: col within [depth · start, depth · end].
                    boolean symmetric =
                            (long) col * sd >= (long) depth * sn
                                    && (long) col * ed <= (long) depth * en;
                    if (wall || symmetric) {
                        reveal(x, z);
                    }
                    if (prev == 1 && !wall) {
                        // New start slope at this floor tile: (2·col − 1) / (2·depth).
                        sn = 2 * col - 1;
                        sd = 2 * depth;
                    }
                    if (prev == 0 && wall) {
                        push(sp++, depth + 1, sn, sd, 2 * col - 1, 2 * depth);
                    }
                    prev = wall ? 1 : 0;
                }
                if (prev == 0) {
                    push(sp++, depth + 1, sn, sd, en, ed);
                }
            }
        }

        private static int floorDiv(int a, int b) {
            return Math.floorDiv(a, b);
        }

        private static int ceilDiv(int a, int b) {
            return -Math.floorDiv(-a, b);
        }
    }
}
