package dev.jeddy.physics.nav.bake;

import java.util.Arrays;

/**
 * A voxel heightfield (Recast's {@code rcHeightfield}, DESIGN §11.4): columns of solid spans
 * ({@code [min, max)} in voxels, sorted, linked) with an area per span. Triangles rasterize into
 * it; walkable areas then pass three filters (low hanging obstacles, ledges, low ceilings). Spans
 * live in SoA arrays with an {@code int} free list instead of objects.
 *
 * <p>Thread ownership: one baking thread.
 */
final class Heightfield {
    /** Area of non-walkable spans. */
    static final int NULL_AREA = 0;

    /** Default walkable area. */
    static final int WALKABLE_AREA = 63;

    static final int MAX_HEIGHT = (1 << 13) - 1;

    final int width;
    final int height;
    final float minX;
    final float minY;
    final float minZ;
    final float maxY;
    final float cs;
    final float ch;
    final int[] columns; // first span per column, -1 none
    int[] smin = new int[1024];
    int[] smax = new int[1024];
    int[] area = new int[1024];
    int[] next = new int[1024];
    private int spanCount;
    private int free = -1;

    // Clipping scratch: 7 vertices × 3 floats each.
    private final float[] in = new float[7 * 3 * 2];
    private final float[] row = new float[7 * 3 * 2];
    private final float[] p1 = new float[7 * 3 * 2];
    private final float[] p2 = new float[7 * 3 * 2];
    private final float[] distances = new float[14];
    private final int[] counts = new int[2];

    Heightfield(
            int width,
            int height,
            float minX,
            float minY,
            float minZ,
            float maxY,
            float cs,
            float ch) {
        this.width = width;
        this.height = height;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxY = maxY;
        this.cs = cs;
        this.ch = ch;
        columns = new int[width * height];
        Arrays.fill(columns, -1);
    }

    private int allocSpan() {
        if (free >= 0) {
            int s = free;
            free = next[s];
            return s;
        }
        if (spanCount == smin.length) {
            int cap = spanCount * 2;
            smin = Arrays.copyOf(smin, cap);
            smax = Arrays.copyOf(smax, cap);
            area = Arrays.copyOf(area, cap);
            next = Arrays.copyOf(next, cap);
        }
        return spanCount++;
    }

    private void freeSpan(int s) {
        next[s] = free;
        free = s;
    }

    /** Adds a span to column {@code (x, z)}, merging overlapping ones (Recast's addSpan). */
    void addSpan(int x, int z, int min, int max, int spanArea, int mergeThreshold) {
        int idx = x + z * width;
        int s = allocSpan();
        smin[s] = min;
        smax[s] = max;
        area[s] = spanArea;
        next[s] = -1;
        if (columns[idx] < 0) {
            columns[idx] = s;
            return;
        }
        int prev = -1;
        int cur = columns[idx];
        int newTop = max;
        while (cur >= 0) {
            if (smin[cur] > smax[s]) {
                break;
            } else if (smax[cur] < smin[s]) {
                prev = cur;
                cur = next[cur];
            } else {
                if (smin[cur] < smin[s]) {
                    smin[s] = smin[cur];
                }
                if (smax[cur] > smax[s]) {
                    smax[s] = smax[cur];
                }
                // The top surface decides the area: both when the tops are within the merge
                // threshold, the existing span's when it reaches higher (D51).
                if (Math.abs(newTop - smax[cur]) <= mergeThreshold) {
                    area[s] = Math.max(area[s], area[cur]);
                } else if (smax[cur] > newTop) {
                    area[s] = area[cur];
                }
                newTop = smax[s];
                int nx = next[cur];
                freeSpan(cur);
                if (prev >= 0) {
                    next[prev] = nx;
                } else {
                    columns[idx] = nx;
                }
                cur = nx;
            }
        }
        if (prev >= 0) {
            next[s] = next[prev];
            next[prev] = s;
        } else {
            next[s] = columns[idx];
            columns[idx] = s;
        }
    }

    /** Rasterizes a triangle (Recast's rasterizeTri: clip to rows, then to columns). */
    void rasterizeTriangle(
            float ax,
            float ay,
            float az,
            float bx,
            float by,
            float bz,
            float cx,
            float cy,
            float cz,
            int triArea,
            int mergeThreshold) {
        float maxX = minX + width * cs;
        float maxZ = minZ + height * cs;
        float tminX = Math.min(ax, Math.min(bx, cx));
        float tmaxX = Math.max(ax, Math.max(bx, cx));
        float tminY = Math.min(ay, Math.min(by, cy));
        float tmaxY = Math.max(ay, Math.max(by, cy));
        float tminZ = Math.min(az, Math.min(bz, cz));
        float tmaxZ = Math.max(az, Math.max(bz, cz));
        if (tminX > maxX
                || tmaxX < minX
                || tminY > maxY
                || tmaxY < minY
                || tminZ > maxZ
                || tmaxZ < minZ) {
            return;
        }
        float ics = 1f / cs;
        float ich = 1f / ch;
        float bySpan = maxY - minY;
        int z0 = (int) ((tminZ - minZ) * ics);
        int z1 = (int) ((tmaxZ - minZ) * ics);
        z0 = Math.clamp(z0, -1, height - 1);
        z1 = Math.clamp(z1, 0, height - 1);
        float[] bufIn = in;
        float[] bufRow = row;
        float[] bufA = p1;
        float[] bufB = p2;
        bufIn[0] = ax;
        bufIn[1] = ay;
        bufIn[2] = az;
        bufIn[3] = bx;
        bufIn[4] = by;
        bufIn[5] = bz;
        bufIn[6] = cx;
        bufIn[7] = cy;
        bufIn[8] = cz;
        int nvIn = 3;
        int[] counts = this.counts;
        for (int z = z0; z <= z1; z++) {
            float cellZ = minZ + z * cs;
            dividePoly(bufIn, nvIn, bufRow, bufA, cellZ + cs, 2, counts);
            int nvRow = counts[0];
            nvIn = counts[1];
            float[] t = bufIn;
            bufIn = bufA;
            bufA = t;
            if (nvRow < 3 || z < 0) {
                continue;
            }
            float rowMin = bufRow[0];
            float rowMax = bufRow[0];
            for (int i = 1; i < nvRow; i++) {
                rowMin = Math.min(rowMin, bufRow[i * 3]);
                rowMax = Math.max(rowMax, bufRow[i * 3]);
            }
            int x0 = (int) ((rowMin - minX) * ics);
            int x1 = (int) ((rowMax - minX) * ics);
            if (x1 < 0 || x0 >= width) {
                continue;
            }
            x0 = Math.clamp(x0, -1, width - 1);
            x1 = Math.clamp(x1, 0, width - 1);
            int nv2 = nvRow;
            for (int x = x0; x <= x1; x++) {
                float cellX = minX + x * cs;
                dividePoly(bufRow, nv2, bufA, bufB, cellX + cs, 0, counts);
                int nv = counts[0];
                nv2 = counts[1];
                float[] u = bufRow;
                bufRow = bufB;
                bufB = u;
                if (nv < 3 || x < 0) {
                    continue;
                }
                float spanMin = bufA[1];
                float spanMax = bufA[1];
                for (int i = 1; i < nv; i++) {
                    spanMin = Math.min(spanMin, bufA[i * 3 + 1]);
                    spanMax = Math.max(spanMax, bufA[i * 3 + 1]);
                }
                spanMin -= minY;
                spanMax -= minY;
                if (spanMax < 0 || spanMin > bySpan) {
                    continue;
                }
                spanMin = Math.max(spanMin, 0);
                spanMax = Math.min(spanMax, bySpan);
                int ismin = Math.clamp((int) Math.floor(spanMin * ich), 0, MAX_HEIGHT);
                int ismax = Math.clamp((int) Math.ceil(spanMax * ich), ismin + 1, MAX_HEIGHT);
                addSpan(x, z, ismin, ismax, triArea, mergeThreshold);
            }
        }
    }

    /**
     * Splits polygon {@code src} ({@code n} vertices) by the plane {@code axis = x}: the part on
     * the low side into {@code out1}, the rest into {@code out2}; counts into {@code counts}.
     */
    private void dividePoly(
            float[] src, int n, float[] out1, float[] out2, float x, int axis, int[] counts) {
        float[] d = distances;
        for (int i = 0; i < n; i++) {
            d[i] = x - src[i * 3 + axis];
        }
        int m = 0;
        int k = 0;
        for (int i = 0, j = n - 1; i < n; j = i, i++) {
            boolean ina = d[j] >= 0;
            boolean inb = d[i] >= 0;
            if (ina != inb) {
                float s = d[j] / (d[j] - d[i]);
                out1[m * 3] = src[j * 3] + (src[i * 3] - src[j * 3]) * s;
                out1[m * 3 + 1] = src[j * 3 + 1] + (src[i * 3 + 1] - src[j * 3 + 1]) * s;
                out1[m * 3 + 2] = src[j * 3 + 2] + (src[i * 3 + 2] - src[j * 3 + 2]) * s;
                out2[k * 3] = out1[m * 3];
                out2[k * 3 + 1] = out1[m * 3 + 1];
                out2[k * 3 + 2] = out1[m * 3 + 2];
                m++;
                k++;
                if (d[i] > 0) {
                    System.arraycopy(src, i * 3, out1, m * 3, 3);
                    m++;
                } else if (d[i] < 0) {
                    System.arraycopy(src, i * 3, out2, k * 3, 3);
                    k++;
                }
            } else {
                if (d[i] >= 0) {
                    System.arraycopy(src, i * 3, out1, m * 3, 3);
                    m++;
                    if (d[i] != 0) {
                        continue;
                    }
                }
                System.arraycopy(src, i * 3, out2, k * 3, 3);
                k++;
            }
        }
        counts[0] = m;
        counts[1] = k;
    }

    // Filters ---------------------------------------------------------------------------------

    /** Walkable spans just below a non-walkable one within the climb make it walkable too. */
    void filterLowHangingWalkableObstacles(int walkableClimb) {
        for (int c = 0; c < columns.length; c++) {
            int prev = -1;
            boolean prevWalkable = false;
            int prevArea = NULL_AREA;
            for (int s = columns[c]; s >= 0; s = next[s]) {
                boolean walkable = area[s] != NULL_AREA;
                if (!walkable && prevWalkable && Math.abs(smax[s] - smax[prev]) <= walkableClimb) {
                    area[s] = prevArea;
                }
                prevWalkable = walkable;
                prevArea = area[s];
                prev = s;
            }
        }
    }

    /** Removes walkable spans at ledges (a drop beyond the climb to a neighbour, or steep). */
    void filterLedgeSpans(int walkableHeight, int walkableClimb) {
        int[] dxs = {-1, 0, 1, 0};
        int[] dzs = {0, 1, 0, -1};
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                for (int s = columns[x + z * width]; s >= 0; s = next[s]) {
                    if (area[s] == NULL_AREA) {
                        continue;
                    }
                    int bot = smax[s];
                    int top = next[s] >= 0 ? smin[next[s]] : MAX_HEIGHT;
                    int minh = MAX_HEIGHT;
                    int asmin = smax[s];
                    int asmax = smax[s];
                    for (int dir = 0; dir < 4; dir++) {
                        int dx = x + dxs[dir];
                        int dz = z + dzs[dir];
                        if (dx < 0 || dz < 0 || dx >= width || dz >= height) {
                            minh = Math.min(minh, -walkableClimb - bot);
                            continue;
                        }
                        int ns = columns[dx + dz * width];
                        int nbot = -walkableClimb;
                        int ntop = ns >= 0 ? smin[ns] : MAX_HEIGHT;
                        if (Math.min(top, ntop) - Math.max(bot, nbot) > walkableHeight) {
                            minh = Math.min(minh, nbot - bot);
                        }
                        for (; ns >= 0; ns = next[ns]) {
                            nbot = smax[ns];
                            ntop = next[ns] >= 0 ? smin[next[ns]] : MAX_HEIGHT;
                            if (Math.min(top, ntop) - Math.max(bot, nbot) > walkableHeight) {
                                minh = Math.min(minh, nbot - bot);
                                if (Math.abs(nbot - bot) <= walkableClimb) {
                                    asmin = Math.min(asmin, nbot);
                                    asmax = Math.max(asmax, nbot);
                                }
                            }
                        }
                    }
                    if (minh < -walkableClimb || asmax - asmin > walkableClimb) {
                        area[s] = NULL_AREA;
                    }
                }
            }
        }
    }

    /** Removes walkable spans without the clearance of an agent above them. */
    void filterWalkableLowHeightSpans(int walkableHeight) {
        for (int c = 0; c < columns.length; c++) {
            for (int s = columns[c]; s >= 0; s = next[s]) {
                int top = next[s] >= 0 ? smin[next[s]] : MAX_HEIGHT;
                if (top - smax[s] < walkableHeight) {
                    area[s] = NULL_AREA;
                }
            }
        }
    }

    /** Returns the number of walkable spans. */
    int walkableSpanCount() {
        int n = 0;
        for (int c = 0; c < columns.length; c++) {
            for (int s = columns[c]; s >= 0; s = next[s]) {
                if (area[s] != NULL_AREA) {
                    n++;
                }
            }
        }
        return n;
    }
}
