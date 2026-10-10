package dev.jeddy.physics.nav.bake;

import dev.jeddy.physics.nav.bake.CompactHeightfield.IntArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Region outlines (Recast's {@code rcBuildContours}): walks each region's border on the compact
 * heightfield, simplifies it within {@code maxError} (keeping vertices where the neighbour region
 * or area changes) and splits long wall edges; holes are merged into their region's outline.
 * Vertices are {@code (x, y, z, flags)} in voxel units, the tile border already removed.
 *
 * <p>Thread ownership: one baking thread.
 */
final class Contours {
    static final int BORDER_VERTEX = 0x10000;
    static final int AREA_BORDER = 0x20000;
    static final int REG_MASK = 0xffff;

    /** One contour: simplified and raw vertices, region and area. */
    static final class Contour {
        int[] verts;
        int nverts;
        int[] rverts;
        int nrverts;
        int reg;
        int area;
    }

    final List<Contour> contours = new ArrayList<>();
    final int width;
    final int height;
    final int borderSize;

    Contours(CompactHeightfield chf, float maxError, int maxEdgeLen) {
        int w = chf.width;
        int h = chf.height;
        borderSize = chf.borderSize;
        width = w - borderSize * 2;
        height = h - borderSize * 2;
        int[] flags = new int[chf.spanCount];
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                int c = x + z * w;
                for (int i = chf.cellIndex[c], end = i + chf.cellCount[c]; i < end; i++) {
                    int res = 0;
                    int r = chf.reg[i];
                    if (r == 0 || (r & CompactHeightfield.BORDER_REG) != 0) {
                        flags[i] = 0;
                        continue;
                    }
                    for (int dir = 0; dir < 4; dir++) {
                        int nr = 0;
                        if (chf.getCon(i, dir) != CompactHeightfield.NOT_CONNECTED) {
                            nr = chf.reg[chf.neighbour(x, z, i, dir)];
                        }
                        if (nr == r) {
                            res |= 1 << dir;
                        }
                    }
                    flags[i] = res ^ 0xf;
                }
            }
        }
        IntArray verts = new IntArray();
        IntArray simplified = new IntArray();
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                int c = x + z * w;
                for (int i = chf.cellIndex[c], end = i + chf.cellCount[c]; i < end; i++) {
                    if (flags[i] == 0 || flags[i] == 0xf) {
                        flags[i] = 0;
                        continue;
                    }
                    int r = chf.reg[i];
                    if (r == 0 || (r & CompactHeightfield.BORDER_REG) != 0) {
                        continue;
                    }
                    verts.clear();
                    simplified.clear();
                    walkContour(chf, x, z, i, flags, verts);
                    simplify(verts, simplified, maxError, maxEdgeLen);
                    removeDegenerateSegments(simplified);
                    if (simplified.n / 4 >= 3) {
                        Contour cont = new Contour();
                        cont.nverts = simplified.n / 4;
                        cont.verts = Arrays.copyOf(simplified.v, simplified.n);
                        cont.nrverts = verts.n / 4;
                        cont.rverts = Arrays.copyOf(verts.v, verts.n);
                        if (borderSize > 0) {
                            for (int j = 0; j < cont.nverts; j++) {
                                cont.verts[j * 4] -= borderSize;
                                cont.verts[j * 4 + 2] -= borderSize;
                            }
                            for (int j = 0; j < cont.nrverts; j++) {
                                cont.rverts[j * 4] -= borderSize;
                                cont.rverts[j * 4 + 2] -= borderSize;
                            }
                        }
                        cont.reg = r;
                        cont.area = chf.area[i];
                        contours.add(cont);
                    }
                }
            }
        }
        mergeHoles(chf.maxRegions);
    }

    private static int cornerHeight(
            CompactHeightfield chf, int x, int z, int i, int dir, boolean[] border) {
        int ch = chf.y[i];
        int dirp = (dir + 1) & 3;
        int[] regs = new int[4];
        regs[0] = chf.reg[i] | (chf.area[i] << 16);
        if (chf.getCon(i, dir) != CompactHeightfield.NOT_CONNECTED) {
            int ax = x + CompactHeightfield.dx(dir);
            int az = z + CompactHeightfield.dz(dir);
            int ai = chf.cellIndex[ax + az * chf.width] + chf.getCon(i, dir);
            ch = Math.max(ch, chf.y[ai]);
            regs[1] = chf.reg[ai] | (chf.area[ai] << 16);
            if (chf.getCon(ai, dirp) != CompactHeightfield.NOT_CONNECTED) {
                int ax2 = ax + CompactHeightfield.dx(dirp);
                int az2 = az + CompactHeightfield.dz(dirp);
                int ai2 = chf.cellIndex[ax2 + az2 * chf.width] + chf.getCon(ai, dirp);
                ch = Math.max(ch, chf.y[ai2]);
                regs[2] = chf.reg[ai2] | (chf.area[ai2] << 16);
            }
        }
        if (chf.getCon(i, dirp) != CompactHeightfield.NOT_CONNECTED) {
            int ax = x + CompactHeightfield.dx(dirp);
            int az = z + CompactHeightfield.dz(dirp);
            int ai = chf.cellIndex[ax + az * chf.width] + chf.getCon(i, dirp);
            ch = Math.max(ch, chf.y[ai]);
            regs[3] = chf.reg[ai] | (chf.area[ai] << 16);
            if (chf.getCon(ai, dir) != CompactHeightfield.NOT_CONNECTED) {
                int ax2 = ax + CompactHeightfield.dx(dir);
                int az2 = az + CompactHeightfield.dz(dir);
                int ai2 = chf.cellIndex[ax2 + az2 * chf.width] + chf.getCon(ai, dir);
                ch = Math.max(ch, chf.y[ai2]);
                regs[2] = chf.reg[ai2] | (chf.area[ai2] << 16);
            }
        }
        for (int j = 0; j < 4; j++) {
            int a = j;
            int b = (j + 1) & 3;
            int c = (j + 2) & 3;
            int d = (j + 3) & 3;
            boolean twoSameExts =
                    (regs[a] & regs[b] & CompactHeightfield.BORDER_REG) != 0 && regs[a] == regs[b];
            boolean twoInts = ((regs[c] | regs[d]) & CompactHeightfield.BORDER_REG) == 0;
            boolean intsSameArea = (regs[c] >>> 16) == (regs[d] >>> 16);
            boolean noZeros = regs[a] != 0 && regs[b] != 0 && regs[c] != 0 && regs[d] != 0;
            if (twoSameExts && twoInts && intsSameArea && noZeros) {
                border[0] = true;
                break;
            }
        }
        return ch;
    }

    private static void walkContour(
            CompactHeightfield chf, int x, int z, int i, int[] flags, IntArray points) {
        int dir = 0;
        while ((flags[i] & (1 << dir)) == 0) {
            dir++;
        }
        int startDir = dir;
        int starti = i;
        int area = chf.area[i];
        boolean[] border = new boolean[1];
        int iter = 0;
        while (++iter < 40000) {
            if ((flags[i] & (1 << dir)) != 0) {
                border[0] = false;
                boolean areaBorder = false;
                int px = x;
                int py = cornerHeight(chf, x, z, i, dir, border);
                int pz = z;
                switch (dir) {
                    case 0 -> pz++;
                    case 1 -> {
                        px++;
                        pz++;
                    }
                    case 2 -> px++;
                    default -> {}
                }
                int r = 0;
                if (chf.getCon(i, dir) != CompactHeightfield.NOT_CONNECTED) {
                    int ai = chf.neighbour(x, z, i, dir);
                    r = chf.reg[ai];
                    if (area != chf.area[ai]) {
                        areaBorder = true;
                    }
                }
                if (border[0]) {
                    r |= BORDER_VERTEX;
                }
                if (areaBorder) {
                    r |= AREA_BORDER;
                }
                points.add(px);
                points.add(py);
                points.add(pz);
                points.add(r);
                flags[i] &= ~(1 << dir);
                dir = (dir + 1) & 3;
            } else {
                if (chf.getCon(i, dir) == CompactHeightfield.NOT_CONNECTED) {
                    return;
                }
                int ni = chf.neighbour(x, z, i, dir);
                x += CompactHeightfield.dx(dir);
                z += CompactHeightfield.dz(dir);
                i = ni;
                dir = (dir + 3) & 3;
            }
            if (starti == i && startDir == dir) {
                break;
            }
        }
    }

    private static float distancePtSeg(int x, int z, int px, int pz, int qx, int qz) {
        float pqx = qx - px;
        float pqz = qz - pz;
        float dx = x - px;
        float dz = z - pz;
        float d = pqx * pqx + pqz * pqz;
        float t = pqx * dx + pqz * dz;
        if (d > 0) {
            t /= d;
        }
        t = Math.clamp(t, 0f, 1f);
        dx = px + t * pqx - x;
        dz = pz + t * pqz - z;
        return dx * dx + dz * dz;
    }

    private static void insert(IntArray simplified, int at, int[] points, int pi) {
        simplified.add(0);
        simplified.add(0);
        simplified.add(0);
        simplified.add(0);
        System.arraycopy(
                simplified.v, at * 4, simplified.v, (at + 1) * 4, simplified.n - (at + 1) * 4);
        simplified.v[at * 4] = points[pi * 4];
        simplified.v[at * 4 + 1] = points[pi * 4 + 1];
        simplified.v[at * 4 + 2] = points[pi * 4 + 2];
        simplified.v[at * 4 + 3] = pi;
    }

    private static void simplify(
            IntArray raw, IntArray simplified, float maxError, int maxEdgeLen) {
        int[] points = raw.v;
        int pn = raw.n / 4;
        boolean hasConnections = false;
        for (int i = 0; i < pn; i++) {
            if ((points[i * 4 + 3] & REG_MASK) != 0) {
                hasConnections = true;
                break;
            }
        }
        if (hasConnections) {
            for (int i = 0; i < pn; i++) {
                int ii = (i + 1) % pn;
                boolean differentRegs =
                        (points[i * 4 + 3] & REG_MASK) != (points[ii * 4 + 3] & REG_MASK);
                boolean areaBorders =
                        (points[i * 4 + 3] & AREA_BORDER) != (points[ii * 4 + 3] & AREA_BORDER);
                if (differentRegs || areaBorders) {
                    simplified.add(points[i * 4]);
                    simplified.add(points[i * 4 + 1]);
                    simplified.add(points[i * 4 + 2]);
                    simplified.add(i);
                }
            }
        }
        if (simplified.n == 0) {
            int llx = points[0], lly = points[1], llz = points[2], lli = 0;
            int urx = points[0], ury = points[1], urz = points[2], uri = 0;
            for (int i = 0; i < pn; i++) {
                int x = points[i * 4];
                int y = points[i * 4 + 1];
                int z = points[i * 4 + 2];
                if (x < llx || (x == llx && z < llz)) {
                    llx = x;
                    lly = y;
                    llz = z;
                    lli = i;
                }
                if (x > urx || (x == urx && z > urz)) {
                    urx = x;
                    ury = y;
                    urz = z;
                    uri = i;
                }
            }
            simplified.add(llx);
            simplified.add(lly);
            simplified.add(llz);
            simplified.add(lli);
            simplified.add(urx);
            simplified.add(ury);
            simplified.add(urz);
            simplified.add(uri);
        }
        for (int i = 0; i < simplified.n / 4; ) {
            int ii = (i + 1) % (simplified.n / 4);
            int ax = simplified.v[i * 4];
            int az = simplified.v[i * 4 + 2];
            int ai = simplified.v[i * 4 + 3];
            int bx = simplified.v[ii * 4];
            int bz = simplified.v[ii * 4 + 2];
            int bi = simplified.v[ii * 4 + 3];
            float maxd = 0;
            int maxi = -1;
            int ci;
            int cinc;
            int endi;
            if (bx > ax || (bx == ax && bz > az)) {
                cinc = 1;
                ci = (ai + cinc) % pn;
                endi = bi;
            } else {
                cinc = pn - 1;
                ci = (bi + cinc) % pn;
                endi = ai;
                int t = ax;
                ax = bx;
                bx = t;
                t = az;
                az = bz;
                bz = t;
            }
            if ((points[ci * 4 + 3] & REG_MASK) == 0 || (points[ci * 4 + 3] & AREA_BORDER) != 0) {
                while (ci != endi) {
                    float d = distancePtSeg(points[ci * 4], points[ci * 4 + 2], ax, az, bx, bz);
                    if (d > maxd) {
                        maxd = d;
                        maxi = ci;
                    }
                    ci = (ci + cinc) % pn;
                }
            }
            if (maxi != -1 && maxd > maxError * maxError) {
                insert(simplified, i + 1, points, maxi);
            } else {
                i++;
            }
        }
        if (maxEdgeLen > 0) {
            for (int i = 0; i < simplified.n / 4; ) {
                int n = simplified.n / 4;
                int ii = (i + 1) % n;
                int ax = simplified.v[i * 4];
                int az = simplified.v[i * 4 + 2];
                int ai = simplified.v[i * 4 + 3];
                int bx = simplified.v[ii * 4];
                int bz = simplified.v[ii * 4 + 2];
                int bi = simplified.v[ii * 4 + 3];
                int maxi = -1;
                int ci = (ai + 1) % pn;
                if ((points[ci * 4 + 3] & REG_MASK) == 0) {
                    int dx = bx - ax;
                    int dz = bz - az;
                    if (dx * dx + dz * dz > maxEdgeLen * maxEdgeLen) {
                        int m = bi < ai ? (bi + pn - ai) : (bi - ai);
                        if (m > 1) {
                            if (bx > ax || (bx == ax && bz > az)) {
                                maxi = (ai + m / 2) % pn;
                            } else {
                                maxi = (ai + (m + 1) / 2) % pn;
                            }
                        }
                    }
                }
                if (maxi != -1) {
                    insert(simplified, i + 1, points, maxi);
                } else {
                    i++;
                }
            }
        }
        for (int i = 0; i < simplified.n / 4; i++) {
            int ai = (simplified.v[i * 4 + 3] + 1) % pn;
            int bi = simplified.v[i * 4 + 3];
            simplified.v[i * 4 + 3] =
                    (points[ai * 4 + 3] & (REG_MASK | AREA_BORDER))
                            | (points[bi * 4 + 3] & BORDER_VERTEX);
        }
    }

    private static void removeDegenerateSegments(IntArray s) {
        int npts = s.n / 4;
        for (int i = 0; i < npts; i++) {
            int ni = (i + 1) % npts;
            if (s.v[i * 4] == s.v[ni * 4] && s.v[i * 4 + 2] == s.v[ni * 4 + 2]) {
                System.arraycopy(s.v, (i + 1) * 4, s.v, i * 4, s.n - (i + 1) * 4);
                s.n -= 4;
                npts--;
            }
        }
    }

    private static int area2D(int[] verts, int n) {
        int area = 0;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            area += verts[i * 4] * verts[j * 4 + 2] - verts[j * 4] * verts[i * 4 + 2];
        }
        return (area + 1) / 2;
    }

    // Holes -------------------------------------------------------------------------------------

    private void mergeHoles(int maxRegions) {
        int n = contours.size();
        if (n == 0) {
            return;
        }
        int[] winding = new int[n];
        int holes = 0;
        for (int i = 0; i < n; i++) {
            Contour c = contours.get(i);
            winding[i] = area2D(c.verts, c.nverts) < 0 ? -1 : 1;
            if (winding[i] < 0) {
                holes++;
            }
        }
        if (holes == 0) {
            return;
        }
        int nregions = maxRegions + 1;
        Contour[] outline = new Contour[nregions];
        List<List<Contour>> regionHoles = new ArrayList<>();
        for (int r = 0; r < nregions; r++) {
            regionHoles.add(new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            Contour c = contours.get(i);
            if (c.reg >= nregions) {
                continue;
            }
            if (winding[i] > 0) {
                outline[c.reg] = c;
            } else {
                regionHoles.get(c.reg).add(c);
            }
        }
        for (int r = 0; r < nregions; r++) {
            List<Contour> hs = regionHoles.get(r);
            if (hs.isEmpty() || outline[r] == null) {
                continue;
            }
            mergeRegionHoles(outline[r], hs);
        }
        contours.removeIf(c -> c.nverts == 0);
    }

    private static void mergeRegionHoles(Contour outline, List<Contour> holes) {
        int nh = holes.size();
        int[] leftmost = new int[nh];
        int[] minx = new int[nh];
        int[] minz = new int[nh];
        for (int k = 0; k < nh; k++) {
            Contour h = holes.get(k);
            int mx = h.verts[0];
            int mz = h.verts[2];
            int lm = 0;
            for (int i = 1; i < h.nverts; i++) {
                int x = h.verts[i * 4];
                int z = h.verts[i * 4 + 2];
                if (x < mx || (x == mx && z < mz)) {
                    mx = x;
                    mz = z;
                    lm = i;
                }
            }
            leftmost[k] = lm;
            minx[k] = mx;
            minz[k] = mz;
        }
        Integer[] order = new Integer[nh];
        for (int k = 0; k < nh; k++) {
            order[k] = k;
        }
        Arrays.sort(
                order,
                (a, b) ->
                        minx[a] != minx[b]
                                ? Integer.compare(minx[a], minx[b])
                                : Integer.compare(minz[a], minz[b]));
        for (int oi = 0; oi < nh; oi++) {
            int k = order[oi];
            Contour hole = holes.get(k);
            int index = -1;
            int bestVertex = leftmost[k];
            for (int iter = 0; iter < hole.nverts; iter++) {
                int[] corner = {hole.verts[bestVertex * 4], 0, hole.verts[bestVertex * 4 + 2]};
                int ndiags = 0;
                int[] diagVert = new int[outline.nverts];
                long[] diagDist = new long[outline.nverts];
                for (int j = 0; j < outline.nverts; j++) {
                    if (inConeContour(j, outline.nverts, outline.verts, corner)) {
                        long dx = outline.verts[j * 4] - corner[0];
                        long dz = outline.verts[j * 4 + 2] - corner[2];
                        diagVert[ndiags] = j;
                        diagDist[ndiags] = dx * dx + dz * dz;
                        ndiags++;
                    }
                }
                // Sort by distance (insertion sort; few candidates).
                for (int a = 1; a < ndiags; a++) {
                    long dd = diagDist[a];
                    int dv = diagVert[a];
                    int b = a - 1;
                    while (b >= 0 && diagDist[b] > dd) {
                        diagDist[b + 1] = diagDist[b];
                        diagVert[b + 1] = diagVert[b];
                        b--;
                    }
                    diagDist[b + 1] = dd;
                    diagVert[b + 1] = dv;
                }
                for (int j = 0; j < ndiags; j++) {
                    int[] pt = {
                        outline.verts[diagVert[j] * 4], 0, outline.verts[diagVert[j] * 4 + 2]
                    };
                    boolean intersect =
                            intersectSegContour(
                                    pt, corner, diagVert[j], outline.nverts, outline.verts);
                    for (int ok = oi; ok < nh && !intersect; ok++) {
                        Contour other = holes.get(order[ok]);
                        intersect = intersectSegContour(pt, corner, -1, other.nverts, other.verts);
                    }
                    if (!intersect) {
                        index = diagVert[j];
                        break;
                    }
                }
                if (index != -1) {
                    break;
                }
                bestVertex = (bestVertex + 1) % hole.nverts;
            }
            if (index == -1) {
                continue; // could not merge this hole; it is dropped from the outline
            }
            mergeContours(outline, hole, index, bestVertex);
        }
    }

    private static void mergeContours(Contour ca, Contour cb, int ia, int ib) {
        int maxVerts = ca.nverts + cb.nverts + 2;
        int[] verts = new int[maxVerts * 4];
        int nv = 0;
        for (int i = 0; i <= ca.nverts; i++) {
            System.arraycopy(ca.verts, ((ia + i) % ca.nverts) * 4, verts, nv * 4, 4);
            nv++;
        }
        for (int i = 0; i <= cb.nverts; i++) {
            System.arraycopy(cb.verts, ((ib + i) % cb.nverts) * 4, verts, nv * 4, 4);
            nv++;
        }
        ca.verts = verts;
        ca.nverts = nv;
        cb.verts = new int[0];
        cb.nverts = 0;
    }

    // 2D integer geometry on (x, z) with stride-4 vertices (Recast's conventions). ----------------

    static int area2(int ax, int az, int bx, int bz, int cx, int cz) {
        return (bx - ax) * (cz - az) - (cx - ax) * (bz - az);
    }

    static boolean left(int[] a, int ao, int[] b, int bo, int[] c, int co) {
        return area2(a[ao], a[ao + 2], b[bo], b[bo + 2], c[co], c[co + 2]) < 0;
    }

    static boolean leftOn(int[] a, int ao, int[] b, int bo, int[] c, int co) {
        return area2(a[ao], a[ao + 2], b[bo], b[bo + 2], c[co], c[co + 2]) <= 0;
    }

    static boolean collinear(int[] a, int ao, int[] b, int bo, int[] c, int co) {
        return area2(a[ao], a[ao + 2], b[bo], b[bo + 2], c[co], c[co + 2]) == 0;
    }

    static boolean intersectProp(
            int[] a, int ao, int[] b, int bo, int[] c, int co, int[] d, int dO) {
        if (collinear(a, ao, b, bo, c, co)
                || collinear(a, ao, b, bo, d, dO)
                || collinear(c, co, d, dO, a, ao)
                || collinear(c, co, d, dO, b, bo)) {
            return false;
        }
        return (left(a, ao, b, bo, c, co) ^ left(a, ao, b, bo, d, dO))
                && (left(c, co, d, dO, a, ao) ^ left(c, co, d, dO, b, bo));
    }

    static boolean between(int[] a, int ao, int[] b, int bo, int[] c, int co) {
        if (!collinear(a, ao, b, bo, c, co)) {
            return false;
        }
        if (a[ao] != b[bo]) {
            return (a[ao] <= c[co] && c[co] <= b[bo]) || (a[ao] >= c[co] && c[co] >= b[bo]);
        }
        return (a[ao + 2] <= c[co + 2] && c[co + 2] <= b[bo + 2])
                || (a[ao + 2] >= c[co + 2] && c[co + 2] >= b[bo + 2]);
    }

    static boolean intersect(int[] a, int ao, int[] b, int bo, int[] c, int co, int[] d, int dO) {
        return intersectProp(a, ao, b, bo, c, co, d, dO)
                || between(a, ao, b, bo, c, co)
                || between(a, ao, b, bo, d, dO)
                || between(c, co, d, dO, a, ao)
                || between(c, co, d, dO, b, bo);
    }

    static boolean vequal(int[] a, int ao, int[] b, int bo) {
        return a[ao] == b[bo] && a[ao + 2] == b[bo + 2];
    }

    private static boolean inConeContour(int i, int n, int[] verts, int[] pj) {
        int pi = i * 4;
        int pi1 = ((i + 1) % n) * 4;
        int pin1 = ((i + n - 1) % n) * 4;
        if (leftOn(verts, pin1, verts, pi, verts, pi1)) {
            return left(verts, pi, pj, 0, verts, pin1) && left(pj, 0, verts, pi, verts, pi1);
        }
        return !(leftOn(verts, pi, pj, 0, verts, pi1) && leftOn(pj, 0, verts, pi, verts, pin1));
    }

    private static boolean intersectSegContour(int[] d0, int[] d1, int i, int n, int[] verts) {
        for (int k = 0; k < n; k++) {
            int k1 = (k + 1) % n;
            if (i == k || i == k1) {
                continue;
            }
            int p0 = k * 4;
            int p1 = k1 * 4;
            if (vequal(d0, 0, verts, p0)
                    || vequal(d1, 0, verts, p0)
                    || vequal(d0, 0, verts, p1)
                    || vequal(d1, 0, verts, p1)) {
                continue;
            }
            if (intersect(d0, 0, d1, 0, verts, p0, verts, p1)) {
                return true;
            }
        }
        return false;
    }
}
