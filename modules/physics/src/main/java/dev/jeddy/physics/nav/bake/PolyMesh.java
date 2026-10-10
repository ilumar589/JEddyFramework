package dev.jeddy.physics.nav.bake;

import java.util.Arrays;

/**
 * Convex polygons from region contours (Recast's {@code rcBuildPolyMesh}): each contour is
 * triangulated by ear clipping (shortest diagonals first), the triangles are merged into convex
 * polygons of up to {@code nvp} vertices (longest shared edge first), vertices on the tile border
 * that Recast marks for removal are removed and their holes re-triangulated, and edge adjacency is
 * computed; open edges on the tile's sides become portals ({@code 0x8000 | side}).
 *
 * <p>Thread ownership: one baking thread.
 */
final class PolyMesh {
    static final int NULL_IDX = 0xffff;
    static final int MULTIPLE_REGS = 0;
    private static final int BUCKETS = 1 << 12;

    int[] verts; // x, y, z per vertex in voxels
    int nverts;
    int[] polys; // nvp vertex indices then nvp neighbour links per polygon
    int[] regs;
    int[] areas;
    int npolys;
    final int nvp;
    int maxpolys;

    PolyMesh(Contours cset, int nvp) {
        this.nvp = nvp;
        int maxVertices = 0;
        int maxTris = 0;
        int maxVertsPerCont = 0;
        for (Contours.Contour c : cset.contours) {
            if (c.nverts < 3) {
                continue;
            }
            maxVertices += c.nverts;
            maxTris += c.nverts - 2;
            maxVertsPerCont = Math.max(maxVertsPerCont, c.nverts);
        }
        if (maxVertices >= 0xfffe) {
            throw new IllegalStateException(
                    "too many navmesh vertices in one tile: " + maxVertices);
        }
        int[] vflags = new int[maxVertices + 1];
        verts = new int[Math.max(3, maxVertices * 3)];
        polys = new int[Math.max(1, maxTris * nvp * 2)];
        Arrays.fill(polys, NULL_IDX);
        regs = new int[Math.max(1, maxTris)];
        areas = new int[Math.max(1, maxTris)];
        maxpolys = maxTris;
        int[] nextVert = new int[Math.max(1, maxVertices)];
        int[] firstVert = new int[BUCKETS];
        Arrays.fill(firstVert, -1);
        int[] indices = new int[Math.max(1, maxVertsPerCont)];
        int[] tris = new int[Math.max(3, maxVertsPerCont * 3)];
        int[] tmp = new int[Math.max(1, (maxVertsPerCont + 1) * nvp)];
        for (Contours.Contour cont : cset.contours) {
            if (cont.nverts < 3) {
                continue;
            }
            for (int j = 0; j < cont.nverts; j++) {
                indices[j] = j;
            }
            int ntris = triangulate(cont.nverts, cont.verts, indices, tris);
            if (ntris <= 0) {
                ntris = -ntris;
            }
            for (int j = 0; j < cont.nverts; j++) {
                int v = j * 4;
                indices[j] =
                        addVertex(
                                cont.verts[v],
                                cont.verts[v + 1],
                                cont.verts[v + 2],
                                firstVert,
                                nextVert);
                if ((cont.verts[v + 3] & Contours.BORDER_VERTEX) != 0) {
                    vflags[indices[j]] = 1;
                }
            }
            int np = 0;
            Arrays.fill(tmp, NULL_IDX);
            for (int j = 0; j < ntris; j++) {
                int t0 = tris[j * 3];
                int t1 = tris[j * 3 + 1];
                int t2 = tris[j * 3 + 2];
                if (t0 != t1 && t0 != t2 && t1 != t2) {
                    tmp[np * nvp] = indices[t0];
                    tmp[np * nvp + 1] = indices[t1];
                    tmp[np * nvp + 2] = indices[t2];
                    np++;
                }
            }
            if (np == 0) {
                continue;
            }
            np = mergePolys(tmp, np, null, verts);
            for (int j = 0; j < np; j++) {
                int p = npolys * nvp * 2;
                System.arraycopy(tmp, j * nvp, polys, p, nvp);
                regs[npolys] = cont.reg;
                areas[npolys] = cont.area;
                npolys++;
            }
        }
        // Remove tile-border vertices Recast flagged during contour building.
        for (int i = 0; i < nverts; i++) {
            if (vflags[i] != 0) {
                if (!canRemoveVertex(i)) {
                    continue;
                }
                removeVertex(i, maxTris);
                for (int j = i; j < nverts; j++) {
                    vflags[j] = vflags[j + 1];
                }
                i--;
            }
        }
        buildAdjacency();
        if (cset.borderSize > 0) {
            int w = cset.width;
            int h = cset.height;
            for (int i = 0; i < npolys; i++) {
                int p = i * nvp * 2;
                for (int j = 0; j < nvp; j++) {
                    if (polys[p + j] == NULL_IDX) {
                        break;
                    }
                    if (polys[p + nvp + j] != NULL_IDX) {
                        continue;
                    }
                    int nj = j + 1;
                    if (nj >= nvp || polys[p + nj] == NULL_IDX) {
                        nj = 0;
                    }
                    int va = polys[p + j] * 3;
                    int vb = polys[p + nj] * 3;
                    if (verts[va] == 0 && verts[vb] == 0) {
                        polys[p + nvp + j] = 0x8000;
                    } else if (verts[va + 2] == h && verts[vb + 2] == h) {
                        polys[p + nvp + j] = 0x8000 | 1;
                    } else if (verts[va] == w && verts[vb] == w) {
                        polys[p + nvp + j] = 0x8000 | 2;
                    } else if (verts[va + 2] == 0 && verts[vb + 2] == 0) {
                        polys[p + nvp + j] = 0x8000 | 3;
                    }
                }
            }
        }
    }

    private int addVertex(int x, int y, int z, int[] firstVert, int[] nextVert) {
        int bucket = (int) ((0x8da6b343L * x + 0xcb1ab31fL * z) & (BUCKETS - 1));
        int i = firstVert[bucket];
        while (i != -1) {
            int v = i * 3;
            if (verts[v] == x && Math.abs(verts[v + 1] - y) <= 2 && verts[v + 2] == z) {
                return i;
            }
            i = nextVert[i];
        }
        i = nverts++;
        verts[i * 3] = x;
        verts[i * 3 + 1] = y;
        verts[i * 3 + 2] = z;
        nextVert[i] = firstVert[bucket];
        firstVert[bucket] = i;
        return i;
    }

    /** Greedy merge of polygons in {@code tmp} (np of them); returns the new count. */
    private int mergePolys(int[] p, int np, int[] pregs, int[] vs) {
        if (nvp <= 3) {
            return np;
        }
        int[] edges = new int[2];
        int[] merged = new int[nvp];
        while (true) {
            int best = 0;
            int bestA = 0;
            int bestB = 0;
            int bestEa = 0;
            int bestEb = 0;
            for (int j = 0; j < np - 1; j++) {
                for (int k = j + 1; k < np; k++) {
                    int v = mergeValue(p, j * nvp, p, k * nvp, vs, edges);
                    if (v > best) {
                        best = v;
                        bestA = j;
                        bestB = k;
                        bestEa = edges[0];
                        bestEb = edges[1];
                    }
                }
            }
            if (best <= 0) {
                return np;
            }
            mergePolyVerts(p, bestA * nvp, bestB * nvp, bestEa, bestEb, merged);
            if (pregs != null && pregs[bestA] != pregs[bestB]) {
                pregs[bestA] = MULTIPLE_REGS;
            }
            int last = (np - 1) * nvp;
            if (bestB * nvp != last) {
                System.arraycopy(p, last, p, bestB * nvp, nvp);
                if (pregs != null) {
                    pregs[bestB] = pregs[np - 1];
                }
            }
            np--;
        }
    }

    private int countVerts(int[] p, int o) {
        for (int i = 0; i < nvp; i++) {
            if (p[o + i] == NULL_IDX) {
                return i;
            }
        }
        return nvp;
    }

    private static boolean uleft(int[] v, int a, int b, int c) {
        return (v[b * 3] - v[a * 3]) * (v[c * 3 + 2] - v[a * 3 + 2])
                        - (v[c * 3] - v[a * 3]) * (v[b * 3 + 2] - v[a * 3 + 2])
                < 0;
    }

    private int mergeValue(int[] pa, int ao, int[] pb, int bo, int[] vs, int[] out) {
        int na = countVerts(pa, ao);
        int nb = countVerts(pb, bo);
        if (na + nb - 2 > nvp) {
            return -1;
        }
        int ea = -1;
        int eb = -1;
        for (int i = 0; i < na; i++) {
            int va0 = pa[ao + i];
            int va1 = pa[ao + (i + 1) % na];
            if (va0 > va1) {
                int t = va0;
                va0 = va1;
                va1 = t;
            }
            for (int j = 0; j < nb; j++) {
                int vb0 = pb[bo + j];
                int vb1 = pb[bo + (j + 1) % nb];
                if (vb0 > vb1) {
                    int t = vb0;
                    vb0 = vb1;
                    vb1 = t;
                }
                if (va0 == vb0 && va1 == vb1) {
                    ea = i;
                    eb = j;
                    break;
                }
            }
        }
        if (ea == -1 || eb == -1) {
            return -1;
        }
        int va = pa[ao + (ea + na - 1) % na];
        int vb = pa[ao + ea];
        int vc = pb[bo + (eb + 2) % nb];
        if (!uleft(vs, va, vb, vc)) {
            return -1;
        }
        va = pb[bo + (eb + nb - 1) % nb];
        vb = pb[bo + eb];
        vc = pa[ao + (ea + 2) % na];
        if (!uleft(vs, va, vb, vc)) {
            return -1;
        }
        va = pa[ao + ea];
        vb = pa[ao + (ea + 1) % na];
        int dx = vs[va * 3] - vs[vb * 3];
        int dz = vs[va * 3 + 2] - vs[vb * 3 + 2];
        out[0] = ea;
        out[1] = eb;
        return dx * dx + dz * dz;
    }

    private void mergePolyVerts(int[] p, int ao, int bo, int ea, int eb, int[] tmp) {
        int na = countVerts(p, ao);
        int nb = countVerts(p, bo);
        Arrays.fill(tmp, NULL_IDX);
        int n = 0;
        for (int i = 0; i < na - 1; i++) {
            tmp[n++] = p[ao + (ea + 1 + i) % na];
        }
        for (int i = 0; i < nb - 1; i++) {
            tmp[n++] = p[bo + (eb + 1 + i) % nb];
        }
        System.arraycopy(tmp, 0, p, ao, nvp);
    }

    // Vertex removal ----------------------------------------------------------------------------

    private boolean canRemoveVertex(int rem) {
        int numTouched = 0;
        int numRemainingEdges = 0;
        for (int i = 0; i < npolys; i++) {
            int p = i * nvp * 2;
            int nv = countVerts(polys, p);
            int removed = 0;
            for (int j = 0; j < nv; j++) {
                if (polys[p + j] == rem) {
                    numTouched++;
                    removed++;
                }
            }
            if (removed > 0) {
                numRemainingEdges += nv - (removed + 1);
            }
        }
        if (numRemainingEdges <= 2) {
            return false;
        }
        int[] edges = new int[numTouched * 2 * 3];
        int nedges = 0;
        for (int i = 0; i < npolys; i++) {
            int p = i * nvp * 2;
            int nv = countVerts(polys, p);
            for (int j = 0, k = nv - 1; j < nv; k = j++) {
                if (polys[p + j] == rem || polys[p + k] == rem) {
                    int a = polys[p + j];
                    int b = polys[p + k];
                    if (b == rem) {
                        int t = a;
                        a = b;
                        b = t;
                    }
                    boolean exists = false;
                    for (int m = 0; m < nedges; m++) {
                        if (edges[m * 3 + 1] == b) {
                            edges[m * 3 + 2]++;
                            exists = true;
                        }
                    }
                    if (!exists) {
                        edges[nedges * 3] = a;
                        edges[nedges * 3 + 1] = b;
                        edges[nedges * 3 + 2] = 1;
                        nedges++;
                    }
                }
            }
        }
        int open = 0;
        for (int i = 0; i < nedges; i++) {
            if (edges[i * 3 + 2] < 2) {
                open++;
            }
        }
        return open <= 2;
    }

    private void removeVertex(int rem, int maxTris) {
        int numRemoved = 0;
        for (int i = 0; i < npolys; i++) {
            int p = i * nvp * 2;
            int nv = countVerts(polys, p);
            for (int j = 0; j < nv; j++) {
                if (polys[p + j] == rem) {
                    numRemoved++;
                }
            }
        }
        int cap = Math.max(4, numRemoved * nvp);
        int[] edges = new int[cap * 4];
        int nedges = 0;
        int[] hole = new int[cap];
        int nhole = 0;
        int[] hreg = new int[cap];
        int nhreg = 0;
        int[] harea = new int[cap];
        int nharea = 0;
        for (int i = 0; i < npolys; i++) {
            int p = i * nvp * 2;
            int nv = countVerts(polys, p);
            boolean hasRem = false;
            for (int j = 0; j < nv; j++) {
                if (polys[p + j] == rem) {
                    hasRem = true;
                }
            }
            if (hasRem) {
                for (int j = 0, k = nv - 1; j < nv; k = j++) {
                    if (polys[p + j] != rem && polys[p + k] != rem) {
                        edges[nedges * 4] = polys[p + k];
                        edges[nedges * 4 + 1] = polys[p + j];
                        edges[nedges * 4 + 2] = regs[i];
                        edges[nedges * 4 + 3] = areas[i];
                        nedges++;
                    }
                }
                int last = (npolys - 1) * nvp * 2;
                if (p != last) {
                    System.arraycopy(polys, last, polys, p, nvp);
                }
                Arrays.fill(polys, p + nvp, p + nvp * 2, NULL_IDX);
                Arrays.fill(polys, last + nvp, last + nvp * 2, NULL_IDX);
                regs[i] = regs[npolys - 1];
                areas[i] = areas[npolys - 1];
                npolys--;
                i--;
            }
        }
        for (int i = rem; i < nverts - 1; i++) {
            verts[i * 3] = verts[(i + 1) * 3];
            verts[i * 3 + 1] = verts[(i + 1) * 3 + 1];
            verts[i * 3 + 2] = verts[(i + 1) * 3 + 2];
        }
        nverts--;
        for (int i = 0; i < npolys; i++) {
            int p = i * nvp * 2;
            int nv = countVerts(polys, p);
            for (int j = 0; j < nv; j++) {
                if (polys[p + j] > rem) {
                    polys[p + j]--;
                }
            }
        }
        for (int i = 0; i < nedges; i++) {
            if (edges[i * 4] > rem) {
                edges[i * 4]--;
            }
            if (edges[i * 4 + 1] > rem) {
                edges[i * 4 + 1]--;
            }
        }
        if (nedges == 0) {
            return;
        }
        hole[nhole++] = edges[0];
        hreg[nhreg++] = edges[2];
        harea[nharea++] = edges[3];
        while (nedges > 0) {
            boolean match = false;
            for (int i = 0; i < nedges; i++) {
                int ea = edges[i * 4];
                int eb = edges[i * 4 + 1];
                int r = edges[i * 4 + 2];
                int a = edges[i * 4 + 3];
                boolean add = false;
                if (hole[0] == eb) {
                    System.arraycopy(hole, 0, hole, 1, nhole);
                    hole[0] = ea;
                    nhole++;
                    System.arraycopy(hreg, 0, hreg, 1, nhreg);
                    hreg[0] = r;
                    nhreg++;
                    System.arraycopy(harea, 0, harea, 1, nharea);
                    harea[0] = a;
                    nharea++;
                    add = true;
                } else if (hole[nhole - 1] == ea) {
                    hole[nhole++] = eb;
                    hreg[nhreg++] = r;
                    harea[nharea++] = a;
                    add = true;
                }
                if (add) {
                    System.arraycopy(edges, (nedges - 1) * 4, edges, i * 4, 4);
                    nedges--;
                    match = true;
                    i--;
                }
            }
            if (!match) {
                break;
            }
        }
        int[] tris = new int[nhole * 3];
        int[] tverts = new int[nhole * 4];
        int[] thole = new int[nhole];
        for (int i = 0; i < nhole; i++) {
            int pi = hole[i];
            tverts[i * 4] = verts[pi * 3];
            tverts[i * 4 + 1] = verts[pi * 3 + 1];
            tverts[i * 4 + 2] = verts[pi * 3 + 2];
            thole[i] = i;
        }
        int ntris = triangulate(nhole, tverts, thole, tris);
        if (ntris < 0) {
            ntris = -ntris;
        }
        int[] tp = new int[(ntris + 1) * nvp];
        int[] pregs = new int[Math.max(1, ntris)];
        int[] pareas = new int[Math.max(1, ntris)];
        Arrays.fill(tp, NULL_IDX);
        int np = 0;
        for (int j = 0; j < ntris; j++) {
            int t0 = tris[j * 3];
            int t1 = tris[j * 3 + 1];
            int t2 = tris[j * 3 + 2];
            if (t0 != t1 && t0 != t2 && t1 != t2) {
                tp[np * nvp] = hole[t0];
                tp[np * nvp + 1] = hole[t1];
                tp[np * nvp + 2] = hole[t2];
                pregs[np] = hreg[t0] != hreg[t1] || hreg[t1] != hreg[t2] ? MULTIPLE_REGS : hreg[t0];
                pareas[np] = harea[t0];
                np++;
            }
        }
        if (np == 0) {
            return;
        }
        // Merge (keeping areas in step with the polygons).
        if (nvp > 3) {
            int[] edgesOut = new int[2];
            int[] merged = new int[nvp];
            while (true) {
                int best = 0;
                int bestA = 0;
                int bestB = 0;
                int bestEa = 0;
                int bestEb = 0;
                for (int j = 0; j < np - 1; j++) {
                    for (int k = j + 1; k < np; k++) {
                        int v = mergeValue(tp, j * nvp, tp, k * nvp, verts, edgesOut);
                        if (v > best) {
                            best = v;
                            bestA = j;
                            bestB = k;
                            bestEa = edgesOut[0];
                            bestEb = edgesOut[1];
                        }
                    }
                }
                if (best <= 0) {
                    break;
                }
                mergePolyVerts(tp, bestA * nvp, bestB * nvp, bestEa, bestEb, merged);
                if (pregs[bestA] != pregs[bestB]) {
                    pregs[bestA] = MULTIPLE_REGS;
                }
                int last = (np - 1) * nvp;
                if (bestB * nvp != last) {
                    System.arraycopy(tp, last, tp, bestB * nvp, nvp);
                }
                pregs[bestB] = pregs[np - 1];
                pareas[bestB] = pareas[np - 1];
                np--;
            }
        }
        for (int i = 0; i < np; i++) {
            if (npolys >= maxTris) {
                break;
            }
            int p = npolys * nvp * 2;
            Arrays.fill(polys, p, p + nvp * 2, NULL_IDX);
            System.arraycopy(tp, i * nvp, polys, p, nvp);
            regs[npolys] = pregs[i];
            areas[npolys] = pareas[i];
            npolys++;
        }
    }

    // Adjacency ---------------------------------------------------------------------------------

    private void buildAdjacency() {
        int maxEdges = npolys * nvp;
        int[] firstEdge = new int[nverts];
        int[] nextEdge = new int[Math.max(1, maxEdges)];
        Arrays.fill(firstEdge, NULL_IDX);
        // Edge: vert0, vert1, poly0, polyEdge0, poly1, polyEdge1.
        int[] e = new int[Math.max(1, maxEdges) * 6];
        int count = 0;
        for (int i = 0; i < npolys; i++) {
            int t = i * nvp * 2;
            for (int j = 0; j < nvp; j++) {
                if (polys[t + j] == NULL_IDX) {
                    break;
                }
                int v0 = polys[t + j];
                int v1 = j + 1 >= nvp || polys[t + j + 1] == NULL_IDX ? polys[t] : polys[t + j + 1];
                if (v0 < v1) {
                    int o = count * 6;
                    e[o] = v0;
                    e[o + 1] = v1;
                    e[o + 2] = i;
                    e[o + 3] = j;
                    e[o + 4] = i;
                    e[o + 5] = 0;
                    nextEdge[count] = firstEdge[v0];
                    firstEdge[v0] = count;
                    count++;
                }
            }
        }
        for (int i = 0; i < npolys; i++) {
            int t = i * nvp * 2;
            for (int j = 0; j < nvp; j++) {
                if (polys[t + j] == NULL_IDX) {
                    break;
                }
                int v0 = polys[t + j];
                int v1 = j + 1 >= nvp || polys[t + j + 1] == NULL_IDX ? polys[t] : polys[t + j + 1];
                if (v0 > v1) {
                    for (int k = firstEdge[v1]; k != NULL_IDX; k = nextEdge[k]) {
                        int o = k * 6;
                        if (e[o + 1] == v0 && e[o + 2] == e[o + 4]) {
                            e[o + 4] = i;
                            e[o + 5] = j;
                            break;
                        }
                    }
                }
            }
        }
        for (int k = 0; k < count; k++) {
            int o = k * 6;
            if (e[o + 2] != e[o + 4]) {
                polys[e[o + 2] * nvp * 2 + nvp + e[o + 3]] = e[o + 4];
                polys[e[o + 4] * nvp * 2 + nvp + e[o + 5]] = e[o + 2];
            }
        }
    }

    // Triangulation (Recast's ear clipping) -----------------------------------------------------

    private static final int CAN_REMOVE = 0x80000000;
    private static final int INDEX_MASK = 0x0fffffff;

    static int triangulate(int n, int[] verts, int[] indices, int[] tris) {
        int ntris = 0;
        int dst = 0;
        for (int i = 0; i < n; i++) {
            int i1 = (i + 1) % n;
            int i2 = (i1 + 1) % n;
            if (diagonal(i, i2, n, verts, indices, false)) {
                indices[i1] |= CAN_REMOVE;
            }
        }
        while (n > 3) {
            int minLen = -1;
            int mini = -1;
            for (int i = 0; i < n; i++) {
                int i1 = (i + 1) % n;
                if ((indices[i1] & CAN_REMOVE) != 0) {
                    int p0 = (indices[i] & INDEX_MASK) * 4;
                    int p2 = (indices[(i1 + 1) % n] & INDEX_MASK) * 4;
                    int dx = verts[p2] - verts[p0];
                    int dz = verts[p2 + 2] - verts[p0 + 2];
                    int len = dx * dx + dz * dz;
                    if (minLen < 0 || len < minLen) {
                        minLen = len;
                        mini = i;
                    }
                }
            }
            if (mini == -1) {
                minLen = -1;
                for (int i = 0; i < n; i++) {
                    int i1 = (i + 1) % n;
                    int i2 = (i1 + 1) % n;
                    if (diagonal(i, i2, n, verts, indices, true)) {
                        int p0 = (indices[i] & INDEX_MASK) * 4;
                        int p2 = (indices[(i2 + 1) % n] & INDEX_MASK) * 4;
                        int dx = verts[p2] - verts[p0];
                        int dz = verts[p2 + 2] - verts[p0 + 2];
                        int len = dx * dx + dz * dz;
                        if (minLen < 0 || len < minLen) {
                            minLen = len;
                            mini = i;
                        }
                    }
                }
                if (mini == -1) {
                    return -ntris;
                }
            }
            int i = mini;
            int i1 = (i + 1) % n;
            int i2 = (i1 + 1) % n;
            tris[dst++] = indices[i] & INDEX_MASK;
            tris[dst++] = indices[i1] & INDEX_MASK;
            tris[dst++] = indices[i2] & INDEX_MASK;
            ntris++;
            n--;
            for (int k = i1; k < n; k++) {
                indices[k] = indices[k + 1];
            }
            if (i1 >= n) {
                i1 = 0;
            }
            i = (i1 + n - 1) % n;
            if (diagonal((i + n - 1) % n, i1, n, verts, indices, false)) {
                indices[i] |= CAN_REMOVE;
            } else {
                indices[i] &= INDEX_MASK;
            }
            if (diagonal(i, (i1 + 1) % n, n, verts, indices, false)) {
                indices[i1] |= CAN_REMOVE;
            } else {
                indices[i1] &= INDEX_MASK;
            }
        }
        tris[dst++] = indices[0] & INDEX_MASK;
        tris[dst++] = indices[1] & INDEX_MASK;
        tris[dst] = indices[2] & INDEX_MASK;
        ntris++;
        return ntris;
    }

    private static boolean diagonal(
            int i, int j, int n, int[] verts, int[] indices, boolean loose) {
        return inCone(i, j, n, verts, indices, loose) && diagonalie(i, j, n, verts, indices, loose);
    }

    private static boolean inCone(int i, int j, int n, int[] verts, int[] indices, boolean loose) {
        int pi = (indices[i] & INDEX_MASK) * 4;
        int pj = (indices[j] & INDEX_MASK) * 4;
        int pi1 = (indices[(i + 1) % n] & INDEX_MASK) * 4;
        int pin1 = (indices[(i + n - 1) % n] & INDEX_MASK) * 4;
        if (Contours.leftOn(verts, pin1, verts, pi, verts, pi1)) {
            return loose
                    ? Contours.leftOn(verts, pi, verts, pj, verts, pin1)
                            && Contours.leftOn(verts, pj, verts, pi, verts, pi1)
                    : Contours.left(verts, pi, verts, pj, verts, pin1)
                            && Contours.left(verts, pj, verts, pi, verts, pi1);
        }
        return !(Contours.leftOn(verts, pi, verts, pj, verts, pi1)
                && Contours.leftOn(verts, pj, verts, pi, verts, pin1));
    }

    private static boolean diagonalie(
            int i, int j, int n, int[] verts, int[] indices, boolean loose) {
        int d0 = (indices[i] & INDEX_MASK) * 4;
        int d1 = (indices[j] & INDEX_MASK) * 4;
        for (int k = 0; k < n; k++) {
            int k1 = (k + 1) % n;
            if (k == i || k1 == i || k == j || k1 == j) {
                continue;
            }
            int p0 = (indices[k] & INDEX_MASK) * 4;
            int p1 = (indices[k1] & INDEX_MASK) * 4;
            if (Contours.vequal(verts, d0, verts, p0)
                    || Contours.vequal(verts, d1, verts, p0)
                    || Contours.vequal(verts, d0, verts, p1)
                    || Contours.vequal(verts, d1, verts, p1)) {
                continue;
            }
            boolean hit =
                    loose
                            ? Contours.intersectProp(verts, d0, verts, d1, verts, p0, verts, p1)
                            : Contours.intersect(verts, d0, verts, d1, verts, p0, verts, p1);
            if (hit) {
                return false;
            }
        }
        return true;
    }
}
