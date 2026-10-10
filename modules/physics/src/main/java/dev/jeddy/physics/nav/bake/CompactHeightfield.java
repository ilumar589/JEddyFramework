package dev.jeddy.physics.nav.bake;

import java.util.Arrays;

/**
 * The open space above walkable spans (Recast's {@code rcCompactHeightfield}): per column a range
 * of spans with floor height, clearance, neighbour links (6 bits per direction), area, distance to
 * the nearest border and region. Built from a {@link Heightfield}; then eroded by the agent radius,
 * given a distance field and split into regions by watershed (Recast's {@code rcBuildRegions}, with
 * the same merge and filter rules).
 *
 * <p>Thread ownership: one baking thread.
 */
final class CompactHeightfield {
    static final int NOT_CONNECTED = 0x3f;
    static final int BORDER_REG = 0x8000;
    private static final int[] DX = {-1, 0, 1, 0};
    private static final int[] DZ = {0, 1, 0, -1};

    final int width;
    final int height;
    final int spanCount;
    final float minX;
    final float minY;
    final float minZ;
    final float cs;
    final float ch;
    final int walkableHeight;
    final int walkableClimb;
    final int[] cellIndex;
    final int[] cellCount;
    final int[] y;
    final int[] h;
    final int[] con;
    final int[] reg;
    final int[] area;
    int[] dist;
    int maxDistance;
    int maxRegions;
    int borderSize;

    static int dx(int dir) {
        return DX[dir];
    }

    static int dz(int dir) {
        return DZ[dir];
    }

    CompactHeightfield(Heightfield hf, int walkableHeight, int walkableClimb) {
        this.width = hf.width;
        this.height = hf.height;
        this.minX = hf.minX;
        this.minY = hf.minY;
        this.minZ = hf.minZ;
        this.cs = hf.cs;
        this.ch = hf.ch;
        this.walkableHeight = walkableHeight;
        this.walkableClimb = walkableClimb;
        int n = hf.walkableSpanCount();
        spanCount = n;
        cellIndex = new int[width * height];
        cellCount = new int[width * height];
        y = new int[n];
        h = new int[n];
        con = new int[n];
        reg = new int[n];
        area = new int[n];
        int idx = 0;
        for (int c = 0; c < width * height; c++) {
            cellIndex[c] = idx;
            for (int s = hf.columns[c]; s >= 0; s = hf.next[s]) {
                if (hf.area[s] == Heightfield.NULL_AREA) {
                    continue;
                }
                int bot = hf.smax[s];
                int top = hf.next[s] >= 0 ? hf.smin[hf.next[s]] : 0xffff;
                y[idx] = Math.clamp(bot, 0, 0xffff);
                h[idx] = Math.clamp(top - bot, 0, 0xff);
                area[idx] = hf.area[s];
                idx++;
                cellCount[c]++;
            }
        }
        // Neighbour links.
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                int c = x + z * width;
                for (int i = cellIndex[c], end = i + cellCount[c]; i < end; i++) {
                    int links = 0;
                    for (int dir = 0; dir < 4; dir++) {
                        int link = NOT_CONNECTED;
                        int nx = x + DX[dir];
                        int nz = z + DZ[dir];
                        if (nx >= 0 && nz >= 0 && nx < width && nz < height) {
                            int nc = nx + nz * width;
                            for (int k = cellIndex[nc], kend = k + cellCount[nc]; k < kend; k++) {
                                int bot = Math.max(y[i], y[k]);
                                int top = Math.min(y[i] + h[i], y[k] + h[k]);
                                if (top - bot >= walkableHeight
                                        && Math.abs(y[k] - y[i]) <= walkableClimb) {
                                    int layer = k - cellIndex[nc];
                                    if (layer < NOT_CONNECTED) {
                                        link = layer;
                                    }
                                    break;
                                }
                            }
                        }
                        links |= link << (dir * 6);
                    }
                    con[i] = links;
                }
            }
        }
    }

    /**
     * Copies {@code src} for another build from the same voxels (tile cache rebuilds): the span
     * layout and links are shared (never modified), areas are copied, regions start empty.
     */
    CompactHeightfield(CompactHeightfield src) {
        width = src.width;
        height = src.height;
        spanCount = src.spanCount;
        minX = src.minX;
        minY = src.minY;
        minZ = src.minZ;
        cs = src.cs;
        ch = src.ch;
        walkableHeight = src.walkableHeight;
        walkableClimb = src.walkableClimb;
        cellIndex = src.cellIndex;
        cellCount = src.cellCount;
        y = src.y;
        h = src.h;
        con = src.con;
        reg = new int[spanCount];
        area = src.area.clone();
    }

    int getCon(int span, int dir) {
        return con[span] >>> (dir * 6) & 0x3f;
    }

    /** Index of the span neighbouring span {@code i} of column {@code (x, z)} in {@code dir}. */
    int neighbour(int x, int z, int i, int dir) {
        return cellIndex[(x + DX[dir]) + (z + DZ[dir]) * width] + getCon(i, dir);
    }

    // Erosion -----------------------------------------------------------------------------------

    /** Marks spans closer than {@code radius} cells to an edge as not walkable. */
    void erode(int radius) {
        int[] d = new int[spanCount];
        Arrays.fill(d, 0xff);
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                int c = x + z * width;
                for (int i = cellIndex[c], end = i + cellCount[c]; i < end; i++) {
                    if (area[i] == Heightfield.NULL_AREA) {
                        d[i] = 0;
                        continue;
                    }
                    int nc = 0;
                    for (int dir = 0; dir < 4; dir++) {
                        if (getCon(i, dir) != NOT_CONNECTED
                                && area[neighbour(x, z, i, dir)] != Heightfield.NULL_AREA) {
                            nc++;
                        }
                    }
                    if (nc != 4) {
                        d[i] = 0;
                    }
                }
            }
        }
        chamfer(d, false);
        int threshold = radius * 2;
        for (int i = 0; i < spanCount; i++) {
            if (d[i] < threshold) {
                area[i] = Heightfield.NULL_AREA;
            }
        }
    }

    /** Two-pass 3-4 chamfer distance transform over the span graph (2 straight, 3 diagonal). */
    private void chamfer(int[] d, boolean wide) {
        int cap = wide ? 0xffff : 0xff;
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                int c = x + z * width;
                for (int i = cellIndex[c], end = i + cellCount[c]; i < end; i++) {
                    relax(d, x, z, i, 0, 3, cap);
                    relax(d, x, z, i, 3, 2, cap);
                }
            }
        }
        for (int z = height - 1; z >= 0; z--) {
            for (int x = width - 1; x >= 0; x--) {
                int c = x + z * width;
                for (int i = cellIndex[c], end = i + cellCount[c]; i < end; i++) {
                    relax(d, x, z, i, 2, 1, cap);
                    relax(d, x, z, i, 1, 0, cap);
                }
            }
        }
    }

    private void relax(int[] d, int x, int z, int i, int dir, int dir2, int cap) {
        if (getCon(i, dir) == NOT_CONNECTED) {
            return;
        }
        int ax = x + DX[dir];
        int az = z + DZ[dir];
        int ai = cellIndex[ax + az * width] + getCon(i, dir);
        int nd = Math.min(d[ai] + 2, cap);
        if (nd < d[i]) {
            d[i] = nd;
        }
        if (getCon(ai, dir2) != NOT_CONNECTED) {
            int aai = cellIndex[(ax + DX[dir2]) + (az + DZ[dir2]) * width] + getCon(ai, dir2);
            nd = Math.min(d[aai] + 3, cap);
            if (nd < d[i]) {
                d[i] = nd;
            }
        }
    }

    // Distance field ----------------------------------------------------------------------------

    /** Distance to the nearest area border, box-blurred (Recast's rcBuildDistanceField). */
    void buildDistanceField() {
        int[] src = new int[spanCount];
        Arrays.fill(src, 0xffff);
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                int c = x + z * width;
                for (int i = cellIndex[c], end = i + cellCount[c]; i < end; i++) {
                    int nc = 0;
                    for (int dir = 0; dir < 4; dir++) {
                        if (getCon(i, dir) != NOT_CONNECTED
                                && area[neighbour(x, z, i, dir)] == area[i]) {
                            nc++;
                        }
                    }
                    if (nc != 4) {
                        src[i] = 0;
                    }
                }
            }
        }
        chamfer(src, true);
        int max = 0;
        for (int v : src) {
            max = Math.max(max, v);
        }
        maxDistance = max;
        // Box blur (threshold 1 → 2).
        int[] dst = new int[spanCount];
        int threshold = 2;
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                int c = x + z * width;
                for (int i = cellIndex[c], end = i + cellCount[c]; i < end; i++) {
                    int cd = src[i];
                    if (cd <= threshold) {
                        dst[i] = cd;
                        continue;
                    }
                    int sum = cd;
                    for (int dir = 0; dir < 4; dir++) {
                        if (getCon(i, dir) != NOT_CONNECTED) {
                            int ax = x + DX[dir];
                            int az = z + DZ[dir];
                            int ai = cellIndex[ax + az * width] + getCon(i, dir);
                            sum += src[ai];
                            int dir2 = (dir + 1) & 3;
                            if (getCon(ai, dir2) != NOT_CONNECTED) {
                                int ai2 =
                                        cellIndex[(ax + DX[dir2]) + (az + DZ[dir2]) * width]
                                                + getCon(ai, dir2);
                                sum += src[ai2];
                            } else {
                                sum += cd;
                            }
                        } else {
                            sum += cd * 2;
                        }
                    }
                    dst[i] = (sum + 5) / 9;
                }
            }
        }
        dist = dst;
    }

    // Regions (watershed) -----------------------------------------------------------------------

    /** Stack of (x, z, span) triples. */
    private static final class Stack {
        int[] v = new int[256];
        int n;

        void push(int x, int z, int i) {
            if (n + 3 > v.length) {
                v = Arrays.copyOf(v, v.length * 2);
            }
            v[n++] = x;
            v[n++] = z;
            v[n++] = i;
        }

        int size() {
            return n / 3;
        }

        void clear() {
            n = 0;
        }
    }

    /** Splits the walkable area into regions (Recast's rcBuildRegions, watershed partitioning). */
    void buildRegions(int border, int minRegionArea, int mergeRegionArea) {
        int[] srcReg = new int[spanCount];
        int[] srcDist = new int[spanCount];
        final int stacks = 8;
        Stack[] lvl = new Stack[stacks];
        for (int k = 0; k < stacks; k++) {
            lvl[k] = new Stack();
        }
        Stack stack = new Stack();
        int regionId = 1;
        int level = (maxDistance + 1) & ~1;
        int expandIters = 8;
        if (border > 0) {
            int bw = Math.min(width, border);
            int bh = Math.min(height, border);
            paintRect(0, bw, 0, height, regionId++ | BORDER_REG, srcReg);
            paintRect(width - bw, width, 0, height, regionId++ | BORDER_REG, srcReg);
            paintRect(0, width, 0, bh, regionId++ | BORDER_REG, srcReg);
            paintRect(0, width, height - bh, height, regionId++ | BORDER_REG, srcReg);
        }
        borderSize = border;
        int sId = -1;
        while (level > 0) {
            level = level >= 2 ? level - 2 : 0;
            sId = (sId + 1) & (stacks - 1);
            if (sId == 0) {
                sortCellsByLevel(level, srcReg, lvl);
            } else {
                appendStacks(lvl[sId - 1], lvl[sId], srcReg);
            }
            expandRegions(expandIters, level, srcReg, srcDist, lvl[sId], false);
            Stack s = lvl[sId];
            for (int j = 0; j < s.n; j += 3) {
                int x = s.v[j];
                int z = s.v[j + 1];
                int i = s.v[j + 2];
                if (i >= 0 && srcReg[i] == 0) {
                    if (floodRegion(x, z, i, level, regionId, srcReg, srcDist, stack)) {
                        regionId++;
                    }
                }
            }
        }
        expandRegions(expandIters * 8, 0, srcReg, srcDist, stack, true);
        maxRegions = regionId;
        mergeAndFilterRegions(minRegionArea, mergeRegionArea, srcReg);
        System.arraycopy(srcReg, 0, reg, 0, spanCount);
    }

    private void paintRect(int minx, int maxx, int minz, int maxz, int regId, int[] srcReg) {
        for (int z = minz; z < maxz; z++) {
            for (int x = minx; x < maxx; x++) {
                int c = x + z * width;
                for (int i = cellIndex[c], end = i + cellCount[c]; i < end; i++) {
                    if (area[i] != Heightfield.NULL_AREA) {
                        srcReg[i] = regId;
                    }
                }
            }
        }
    }

    private void sortCellsByLevel(int startLevel, int[] srcReg, Stack[] stacks) {
        int start = startLevel >> 1;
        for (Stack s : stacks) {
            s.clear();
        }
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                int c = x + z * width;
                for (int i = cellIndex[c], end = i + cellCount[c]; i < end; i++) {
                    if (area[i] == Heightfield.NULL_AREA || srcReg[i] != 0) {
                        continue;
                    }
                    int lv = dist[i] >> 1;
                    int sid = start - lv;
                    if (sid >= stacks.length) {
                        continue;
                    }
                    if (sid < 0) {
                        sid = 0;
                    }
                    stacks[sid].push(x, z, i);
                }
            }
        }
    }

    private static void appendStacks(Stack src, Stack dst, int[] srcReg) {
        for (int j = 0; j < src.n; j += 3) {
            int i = src.v[j + 2];
            if (i < 0 || srcReg[i] != 0) {
                continue;
            }
            dst.push(src.v[j], src.v[j + 1], i);
        }
    }

    private void expandRegions(
            int maxIter, int level, int[] srcReg, int[] srcDist, Stack stack, boolean fillStack) {
        if (fillStack) {
            stack.clear();
            for (int z = 0; z < height; z++) {
                for (int x = 0; x < width; x++) {
                    int c = x + z * width;
                    for (int i = cellIndex[c], end = i + cellCount[c]; i < end; i++) {
                        if (dist[i] >= level
                                && srcReg[i] == 0
                                && area[i] != Heightfield.NULL_AREA) {
                            stack.push(x, z, i);
                        }
                    }
                }
            }
        } else {
            for (int j = 0; j < stack.n; j += 3) {
                int i = stack.v[j + 2];
                if (srcReg[i] != 0) {
                    stack.v[j + 2] = -1;
                }
            }
        }
        int[] dirty = new int[Math.max(3, stack.n)];
        int iter = 0;
        while (stack.size() > 0) {
            int failed = 0;
            int nd = 0;
            for (int j = 0; j < stack.n; j += 3) {
                int x = stack.v[j];
                int z = stack.v[j + 1];
                int i = stack.v[j + 2];
                if (i < 0) {
                    failed++;
                    continue;
                }
                int r = srcReg[i];
                int d2 = 0xffff;
                int a = area[i];
                for (int dir = 0; dir < 4; dir++) {
                    if (getCon(i, dir) == NOT_CONNECTED) {
                        continue;
                    }
                    int ai = neighbour(x, z, i, dir);
                    if (area[ai] != a) {
                        continue;
                    }
                    if (srcReg[ai] > 0 && (srcReg[ai] & BORDER_REG) == 0 && srcDist[ai] + 2 < d2) {
                        r = srcReg[ai];
                        d2 = srcDist[ai] + 2;
                    }
                }
                if (r != 0) {
                    stack.v[j + 2] = -1;
                    if (nd + 3 > dirty.length) {
                        dirty = Arrays.copyOf(dirty, dirty.length * 2);
                    }
                    dirty[nd++] = i;
                    dirty[nd++] = r;
                    dirty[nd++] = d2;
                } else {
                    failed++;
                }
            }
            for (int k = 0; k < nd; k += 3) {
                srcReg[dirty[k]] = dirty[k + 1];
                srcDist[dirty[k]] = dirty[k + 2];
            }
            if (failed == stack.size()) {
                break;
            }
            if (level > 0) {
                iter++;
                if (iter >= maxIter) {
                    break;
                }
            }
        }
    }

    private boolean floodRegion(
            int x, int z, int i, int level, int r, int[] srcReg, int[] srcDist, Stack stack) {
        int a = area[i];
        stack.clear();
        stack.push(x, z, i);
        srcReg[i] = r;
        srcDist[i] = 0;
        int lev = level >= 2 ? level - 2 : 0;
        int count = 0;
        while (stack.n > 0) {
            int ci = stack.v[--stack.n];
            int cz = stack.v[--stack.n];
            int cx = stack.v[--stack.n];
            int ar = 0;
            for (int dir = 0; dir < 4; dir++) {
                if (getCon(ci, dir) == NOT_CONNECTED) {
                    continue;
                }
                int ax = cx + DX[dir];
                int az = cz + DZ[dir];
                int ai = cellIndex[ax + az * width] + getCon(ci, dir);
                if (area[ai] != a) {
                    continue;
                }
                int nr = srcReg[ai];
                if ((nr & BORDER_REG) != 0) {
                    continue;
                }
                if (nr != 0 && nr != r) {
                    ar = nr;
                    break;
                }
                int dir2 = (dir + 1) & 3;
                if (getCon(ai, dir2) != NOT_CONNECTED) {
                    int ai2 =
                            cellIndex[(ax + DX[dir2]) + (az + DZ[dir2]) * width] + getCon(ai, dir2);
                    if (area[ai2] != a) {
                        continue;
                    }
                    int nr2 = srcReg[ai2];
                    if (nr2 != 0 && nr2 != r) {
                        ar = nr2;
                        break;
                    }
                }
            }
            if (ar != 0) {
                srcReg[ci] = 0;
                continue;
            }
            count++;
            for (int dir = 0; dir < 4; dir++) {
                if (getCon(ci, dir) == NOT_CONNECTED) {
                    continue;
                }
                int ax = cx + DX[dir];
                int az = cz + DZ[dir];
                int ai = cellIndex[ax + az * width] + getCon(ci, dir);
                if (area[ai] != a) {
                    continue;
                }
                if (dist[ai] >= lev && srcReg[ai] == 0) {
                    srcReg[ai] = r;
                    srcDist[ai] = 0;
                    stack.push(ax, az, ai);
                }
            }
        }
        return count > 0;
    }

    /** Recast's rcRegion. */
    private static final class Region {
        int spanCount;
        int id;
        int areaType;
        boolean remap;
        boolean visited;
        boolean overlap;
        IntArray connections = new IntArray();
        IntArray floors = new IntArray();

        Region(int id) {
            this.id = id;
        }
    }

    /** A tiny growable int array. */
    static final class IntArray {
        int[] v = new int[8];
        int n;

        void add(int x) {
            if (n == v.length) {
                v = Arrays.copyOf(v, n * 2);
            }
            v[n++] = x;
        }

        void removeAt(int i) {
            System.arraycopy(v, i + 1, v, i, n - i - 1);
            n--;
        }

        boolean contains(int x) {
            for (int i = 0; i < n; i++) {
                if (v[i] == x) {
                    return true;
                }
            }
            return false;
        }

        void clear() {
            n = 0;
        }
    }

    private void mergeAndFilterRegions(int minRegionArea, int mergeRegionSize, int[] srcReg) {
        int nreg = maxRegions + 1;
        Region[] regions = new Region[nreg];
        for (int i = 0; i < nreg; i++) {
            regions[i] = new Region(i);
        }
        // Region sizes, floors and connections around their contours.
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                int c = x + z * width;
                for (int i = cellIndex[c], end = i + cellCount[c]; i < end; i++) {
                    int r = srcReg[i];
                    if (r == 0 || r >= nreg) {
                        continue;
                    }
                    Region rg = regions[r];
                    rg.spanCount++;
                    for (int j = cellIndex[c]; j < end; j++) {
                        if (i == j) {
                            continue;
                        }
                        int floor = srcReg[j];
                        if (floor == 0 || floor >= nreg) {
                            continue;
                        }
                        if (floor == r) {
                            rg.overlap = true;
                        }
                        if (!rg.floors.contains(floor)) {
                            rg.floors.add(floor);
                        }
                    }
                    if (rg.connections.n > 0) {
                        continue;
                    }
                    rg.areaType = area[i];
                    int ndir = -1;
                    for (int dir = 0; dir < 4; dir++) {
                        if (isSolidEdge(srcReg, x, z, i, dir)) {
                            ndir = dir;
                            break;
                        }
                    }
                    if (ndir != -1) {
                        walkRegionContour(x, z, i, ndir, srcReg, rg.connections);
                    }
                }
            }
        }
        // Remove too small regions (and groups of connected small regions).
        IntArray stack = new IntArray();
        IntArray trace = new IntArray();
        for (int i = 0; i < nreg; i++) {
            Region rg = regions[i];
            if (rg.id == 0 || (rg.id & BORDER_REG) != 0 || rg.spanCount == 0 || rg.visited) {
                continue;
            }
            boolean connectsToBorder = false;
            int spans = 0;
            stack.clear();
            trace.clear();
            rg.visited = true;
            stack.add(i);
            while (stack.n > 0) {
                int ri = stack.v[--stack.n];
                Region creg = regions[ri];
                spans += creg.spanCount;
                trace.add(ri);
                for (int j = 0; j < creg.connections.n; j++) {
                    int cid = creg.connections.v[j];
                    if ((cid & BORDER_REG) != 0) {
                        connectsToBorder = true;
                        continue;
                    }
                    Region nei = regions[cid];
                    if (nei.visited || nei.id == 0 || (nei.id & BORDER_REG) != 0) {
                        continue;
                    }
                    stack.add(nei.id);
                    nei.visited = true;
                }
            }
            if (spans < minRegionArea && !connectsToBorder) {
                for (int j = 0; j < trace.n; j++) {
                    regions[trace.v[j]].spanCount = 0;
                    regions[trace.v[j]].id = 0;
                }
            }
        }
        // Merge too small regions into neighbours.
        int mergeCount;
        do {
            mergeCount = 0;
            for (int i = 0; i < nreg; i++) {
                Region rg = regions[i];
                if (rg.id == 0 || (rg.id & BORDER_REG) != 0 || rg.overlap || rg.spanCount == 0) {
                    continue;
                }
                if (rg.spanCount > mergeRegionSize && isConnectedToBorder(rg)) {
                    continue;
                }
                int smallest = Integer.MAX_VALUE;
                int mergeId = rg.id;
                for (int j = 0; j < rg.connections.n; j++) {
                    int cid = rg.connections.v[j];
                    if ((cid & BORDER_REG) != 0) {
                        continue;
                    }
                    Region m = regions[cid];
                    if (m.id == 0 || (m.id & BORDER_REG) != 0 || m.overlap) {
                        continue;
                    }
                    if (m.spanCount < smallest && canMerge(rg, m) && canMerge(m, rg)) {
                        smallest = m.spanCount;
                        mergeId = m.id;
                    }
                }
                if (mergeId != rg.id) {
                    int oldId = rg.id;
                    Region target = regions[mergeId];
                    if (mergeRegions(target, rg)) {
                        for (int j = 0; j < nreg; j++) {
                            Region o = regions[j];
                            if (o.id == 0 || (o.id & BORDER_REG) != 0) {
                                continue;
                            }
                            if (o.id == oldId) {
                                o.id = mergeId;
                            }
                            replaceNeighbour(o, oldId, mergeId);
                        }
                        mergeCount++;
                    }
                }
            }
        } while (mergeCount > 0);
        // Compress region ids.
        for (Region rg : regions) {
            rg.remap = rg.id != 0 && (rg.id & BORDER_REG) == 0;
        }
        int gen = 0;
        for (int i = 0; i < nreg; i++) {
            if (!regions[i].remap) {
                continue;
            }
            int oldId = regions[i].id;
            int newId = ++gen;
            for (int j = i; j < nreg; j++) {
                if (regions[j].id == oldId) {
                    regions[j].id = newId;
                    regions[j].remap = false;
                }
            }
        }
        maxRegions = gen;
        for (int i = 0; i < spanCount; i++) {
            if ((srcReg[i] & BORDER_REG) == 0) {
                srcReg[i] = regions[srcReg[i]].id;
            }
        }
    }

    private static boolean isConnectedToBorder(Region rg) {
        for (int i = 0; i < rg.connections.n; i++) {
            if (rg.connections.v[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private boolean isSolidEdge(int[] srcReg, int x, int z, int i, int dir) {
        int r = 0;
        if (getCon(i, dir) != NOT_CONNECTED) {
            r = srcReg[neighbour(x, z, i, dir)];
        }
        return r != srcReg[i];
    }

    private void walkRegionContour(int x, int z, int i, int dir, int[] srcReg, IntArray cont) {
        int startDir = dir;
        int starti = i;
        int curReg = 0;
        if (getCon(i, dir) != NOT_CONNECTED) {
            curReg = srcReg[neighbour(x, z, i, dir)];
        }
        cont.add(curReg);
        int iter = 0;
        while (++iter < 40000) {
            if (isSolidEdge(srcReg, x, z, i, dir)) {
                int r = 0;
                if (getCon(i, dir) != NOT_CONNECTED) {
                    r = srcReg[neighbour(x, z, i, dir)];
                }
                if (r != curReg) {
                    curReg = r;
                    cont.add(curReg);
                }
                dir = (dir + 1) & 3;
            } else {
                if (getCon(i, dir) == NOT_CONNECTED) {
                    return;
                }
                int ni = neighbour(x, z, i, dir);
                x += DX[dir];
                z += DZ[dir];
                i = ni;
                dir = (dir + 3) & 3;
            }
            if (starti == i && startDir == dir) {
                break;
            }
        }
        // Remove adjacent duplicates.
        if (cont.n > 1) {
            for (int j = 0; j < cont.n; ) {
                int nj = (j + 1) % cont.n;
                if (cont.v[j] == cont.v[nj]) {
                    cont.removeAt(j);
                } else {
                    j++;
                }
            }
        }
    }

    private static boolean canMerge(Region a, Region b) {
        if (a.areaType != b.areaType) {
            return false;
        }
        int n = 0;
        for (int i = 0; i < a.connections.n; i++) {
            if (a.connections.v[i] == b.id) {
                n++;
            }
        }
        if (n > 1) {
            return false;
        }
        return !a.floors.contains(b.id);
    }

    private static boolean mergeRegions(Region a, Region b) {
        int aid = a.id;
        int bid = b.id;
        int[] acon = Arrays.copyOf(a.connections.v, a.connections.n);
        int[] bcon = Arrays.copyOf(b.connections.v, b.connections.n);
        int insa = -1;
        for (int i = 0; i < acon.length; i++) {
            if (acon[i] == bid) {
                insa = i;
                break;
            }
        }
        if (insa == -1) {
            return false;
        }
        int insb = -1;
        for (int i = 0; i < bcon.length; i++) {
            if (bcon[i] == aid) {
                insb = i;
                break;
            }
        }
        if (insb == -1) {
            return false;
        }
        a.connections.clear();
        for (int i = 0, ni = acon.length; i < ni - 1; i++) {
            a.connections.add(acon[(insa + 1 + i) % ni]);
        }
        for (int i = 0, ni = bcon.length; i < ni - 1; i++) {
            a.connections.add(bcon[(insb + 1 + i) % ni]);
        }
        removeAdjacentNeighbours(a);
        for (int j = 0; j < b.floors.n; j++) {
            if (!a.floors.contains(b.floors.v[j])) {
                a.floors.add(b.floors.v[j]);
            }
        }
        a.spanCount += b.spanCount;
        b.spanCount = 0;
        b.connections.clear();
        return true;
    }

    private static void removeAdjacentNeighbours(Region rg) {
        for (int i = 0; i < rg.connections.n && rg.connections.n > 1; ) {
            int ni = (i + 1) % rg.connections.n;
            if (rg.connections.v[i] == rg.connections.v[ni]) {
                rg.connections.removeAt(i);
            } else {
                i++;
            }
        }
    }

    private static void replaceNeighbour(Region rg, int oldId, int newId) {
        boolean changed = false;
        for (int i = 0; i < rg.connections.n; i++) {
            if (rg.connections.v[i] == oldId) {
                rg.connections.v[i] = newId;
                changed = true;
            }
        }
        for (int i = 0; i < rg.floors.n; i++) {
            if (rg.floors.v[i] == oldId) {
                rg.floors.v[i] = newId;
            }
        }
        if (changed) {
            removeAdjacentNeighbours(rg);
        }
    }
}
