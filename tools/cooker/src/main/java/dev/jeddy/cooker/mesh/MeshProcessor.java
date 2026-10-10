package dev.jeddy.cooker.mesh;

import static dev.jeddy.cooker.meshopt.ffi.MeshOpt.*;

import dev.jeddy.asset.format.MeshWriter;
import dev.jeddy.cooker.gltf.GltfImporter.Primitive;
import dev.jeddy.cooker.meshopt.ffi.meshopt_OverdrawStatistics;
import dev.jeddy.cooker.meshopt.ffi.meshopt_VertexCacheStatistics;
import dev.jeddy.cooker.natives.ToolNatives;
import dev.jeddy.cooker.shim.ffi.JeddyTools;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Cooks a mesh (TASKS M6-04, DESIGN §10.3): per primitive (submesh) it fills in missing normals
 * (flat) and tangents (MikkTSpace, through the native shim), welds identical vertices, optimizes
 * for the post-transform cache, overdraw and vertex fetch (meshoptimizer), builds LODs by
 * simplification with their geometric error, and writes a {@code .jmesh} with {@link MeshWriter}
 * (QTangents, half-float UVs, bounds).
 *
 * <p>Thread ownership: stateless; calls may run in parallel.
 */
public final class MeshProcessor {
    private MeshProcessor() {}

    /**
     * Interleaved working vertex: pos 3, normal 3, tangent 4, uv0 2, uv1 2, joints 4, weights 4.
     */
    private static final int FLOATS = 22;

    /**
     * Options.
     *
     * @param lods number of LODs including LOD 0
     * @param lodRatio index count ratio between successive LODs
     * @param lodError largest relative error a LOD may have (fraction of the mesh extent)
     * @param tangents generate MikkTSpace tangents when the source has none (normal-mapped)
     * @param overdrawThreshold how much worse the vertex cache may get for less overdraw
     */
    public record Options(
            int lods, float lodRatio, float lodError, boolean tangents, float overdrawThreshold) {
        /** Defaults: 4 LODs halving the triangles, 5 % error budget, tangents, 1.05 threshold. */
        public static final Options DEFAULT = new Options(4, 0.5f, 0.05f, true, 1.05f);
    }

    /** Numbers for the cook report. */
    public record Stats(
            int vertices,
            int triangles,
            int[] lodTriangles,
            float[] lodErrors,
            float acmr,
            float atvr,
            float overdraw) {}

    /** The cooked mesh. */
    public record Result(byte[] jmesh, Stats stats) {}

    /** A submesh after welding and optimization: interleaved vertices and LOD index lists. */
    private record Cooked(
            float[] vertices,
            int vertexCount,
            List<int[]> lods,
            float[] errors,
            int slot,
            boolean skinned) {}

    /**
     * Cooks {@code primitives} into one mesh.
     *
     * @param slots material slot per primitive
     * @param materials default material id per slot
     */
    public static Result process(
            List<Primitive> primitives, int[] slots, long[] materials, Options o) {
        ToolNatives.load();
        List<Cooked> cooked = new ArrayList<>();
        for (int p = 0; p < primitives.size(); p++) {
            cooked.add(cook(primitives.get(p), slots[p], o));
        }
        int lodCount = 1;
        for (Cooked c : cooked) {
            lodCount = Math.max(lodCount, c.lods.size());
        }
        boolean skinned = cooked.stream().allMatch(Cooked::skinned) && !cooked.isEmpty();
        int vertexTotal = 0;
        for (Cooked c : cooked) {
            vertexTotal += c.vertexCount;
        }
        float[] positions = new float[vertexTotal * 3];
        float[] normals = new float[vertexTotal * 3];
        float[] tangents = new float[vertexTotal * 4];
        float[] uv0 = new float[vertexTotal * 2];
        float[] uv1 = new float[vertexTotal * 2];
        int[] joints = skinned ? new int[vertexTotal * 4] : null;
        float[] weights = skinned ? new float[vertexTotal * 4] : null;
        MeshWriter writer = new MeshWriter();
        int base = 0;
        for (Cooked c : cooked) {
            for (int v = 0; v < c.vertexCount; v++) {
                int s = v * FLOATS;
                int d = base + v;
                System.arraycopy(c.vertices, s, positions, d * 3, 3);
                System.arraycopy(c.vertices, s + 3, normals, d * 3, 3);
                System.arraycopy(c.vertices, s + 6, tangents, d * 4, 4);
                System.arraycopy(c.vertices, s + 10, uv0, d * 2, 2);
                System.arraycopy(c.vertices, s + 12, uv1, d * 2, 2);
                if (skinned) {
                    for (int k = 0; k < 4; k++) {
                        joints[d * 4 + k] = Float.floatToRawIntBits(c.vertices[s + 14 + k]);
                        weights[d * 4 + k] = c.vertices[s + 18 + k];
                    }
                }
            }
            writer.submesh(c.slot, base, c.vertexCount);
            base += c.vertexCount;
        }
        // Indices: LOD-major, submesh-minor; submeshes with fewer LODs repeat their last one.
        List<Integer> all = new ArrayList<>();
        int[][] first = new int[lodCount][cooked.size()];
        int[][] count = new int[lodCount][cooked.size()];
        float[] lodErrors = new float[lodCount];
        int[] lodTriangles = new int[lodCount];
        int totalIndices = 0;
        for (int l = 0; l < lodCount; l++) {
            for (int s = 0; s < cooked.size(); s++) {
                Cooked c = cooked.get(s);
                if (l < c.lods.size()) {
                    int[] idx = c.lods.get(l);
                    first[l][s] = totalIndices;
                    count[l][s] = idx.length;
                    for (int i : idx) {
                        all.add(i);
                    }
                    totalIndices += idx.length;
                    lodErrors[l] = Math.max(lodErrors[l], c.errors[l]);
                } else {
                    first[l][s] = first[l - 1][s];
                    count[l][s] = count[l - 1][s];
                }
                lodTriangles[l] += count[l][s] / 3;
            }
        }
        int[] indices = new int[all.size()];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = all.get(i);
        }
        writer.positions(positions)
                .attributes(normals, tangents, uv0, uv1)
                .indices(indices)
                .materials(materials);
        if (skinned) {
            writer.skin(joints, weights);
        }
        for (int l = 0; l < lodCount; l++) {
            writer.lod(lodErrors[l], first[l], count[l]);
        }
        // Statistics on LOD 0.
        float acmr = 0, atvr = 0, overdraw = 0;
        try (Arena a = Arena.ofConfined()) {
            int stats = 0;
            for (Cooked c : cooked) {
                int[] lod0 = c.lods.get(0);
                if (lod0.length == 0) {
                    continue;
                }
                MemorySegment idx = a.allocateFrom(ValueLayout.JAVA_INT, lod0);
                MemorySegment vc =
                        meshopt_analyzeVertexCache(a, idx, lod0.length, c.vertexCount, 16, 0, 0);
                acmr += meshopt_VertexCacheStatistics.acmr(vc);
                atvr += meshopt_VertexCacheStatistics.atvr(vc);
                MemorySegment pos = positionsOf(a, c.vertices, c.vertexCount);
                MemorySegment od =
                        meshopt_analyzeOverdraw(a, idx, lod0.length, pos, c.vertexCount, 12);
                overdraw += meshopt_OverdrawStatistics.overdraw(od);
                stats++;
            }
            if (stats > 0) {
                acmr /= stats;
                atvr /= stats;
                overdraw /= stats;
            }
        }
        return new Result(
                writer.toBytes(),
                new Stats(
                        vertexTotal,
                        lodTriangles[0],
                        lodTriangles,
                        lodErrors,
                        acmr,
                        atvr,
                        overdraw));
    }

    private static Cooked cook(Primitive p, int slot, Options o) {
        int vertices = p.vertexCount();
        int[] indices = p.indices.clone();
        boolean skinned = p.joints != null && p.weights != null;
        float[] normals = p.normals;
        float[] tangents = p.tangents;
        float[] positions = p.positions;
        float[] uv0 = p.uv0;
        float[] uv1 = p.uv1;
        int[] joints = p.joints;
        float[] weights = p.weights;
        boolean unwelded = false;
        if (normals == null || (tangents == null && o.tangents && uv0 != null)) {
            // Work per corner: flat normals and MikkTSpace both produce per-corner values.
            int corners = indices.length;
            positions = gather(p.positions, indices, 3);
            normals = p.normals == null ? flatNormals(positions) : gather(p.normals, indices, 3);
            uv0 = uv0 == null ? null : gather(uv0, indices, 2);
            uv1 = uv1 == null ? null : gather(uv1, indices, 2);
            joints = joints == null ? null : gatherInts(joints, indices, 4);
            weights = weights == null ? null : gather(weights, indices, 4);
            tangents = tangents == null ? null : gather(tangents, indices, 4);
            indices = new int[corners];
            for (int i = 0; i < corners; i++) {
                indices[i] = i;
            }
            vertices = corners;
            unwelded = true;
            if (tangents == null && o.tangents && uv0 != null) {
                tangents = mikk(positions, normals, uv0, indices);
            }
        }
        float[] v = new float[vertices * FLOATS];
        for (int i = 0; i < vertices; i++) {
            int d = i * FLOATS;
            System.arraycopy(positions, i * 3, v, d, 3);
            System.arraycopy(normals, i * 3, v, d + 3, 3);
            if (tangents != null) {
                System.arraycopy(tangents, i * 4, v, d + 6, 4);
            } else {
                v[d + 6] = 1;
                v[d + 9] = 1;
            }
            if (uv0 != null) {
                System.arraycopy(uv0, i * 2, v, d + 10, 2);
            }
            if (uv1 != null) {
                System.arraycopy(uv1, i * 2, v, d + 12, 2);
            }
            if (skinned) {
                for (int k = 0; k < 4; k++) {
                    v[d + 14 + k] = Float.intBitsToFloat(joints[i * 4 + k]);
                    v[d + 18 + k] = weights[i * 4 + k];
                }
            }
        }
        try (Arena a = Arena.ofConfined()) {
            long size = FLOATS * 4L;
            MemorySegment idx = a.allocateFrom(ValueLayout.JAVA_INT, indices);
            MemorySegment vtx = a.allocateFrom(ValueLayout.JAVA_FLOAT, v);
            // Weld identical vertices (always: sources often duplicate them too).
            MemorySegment remap = a.allocate(ValueLayout.JAVA_INT, vertices);
            int unique =
                    (int)
                            meshopt_generateVertexRemap(
                                    remap, idx, indices.length, vtx, vertices, size);
            MemorySegment welded = a.allocate(size * Math.max(1, unique), 4);
            meshopt_remapVertexBuffer(welded, vtx, vertices, size, remap);
            meshopt_remapIndexBuffer(idx, idx, indices.length, remap);
            long n = indices.length;
            meshopt_optimizeVertexCache(idx, idx, n, unique);
            meshopt_optimizeOverdraw(idx, idx, n, welded, unique, size, o.overdrawThreshold());
            MemorySegment fetched = a.allocate(size * Math.max(1, unique), 4);
            int finalCount =
                    (int) meshopt_optimizeVertexFetch(fetched, idx, n, welded, unique, size);
            List<int[]> lods = new ArrayList<>();
            int[] lod0 = idx.toArray(ValueLayout.JAVA_INT);
            lods.add(lod0);
            float[] errors = new float[Math.max(1, o.lods())];
            float scale = n == 0 ? 0 : meshopt_simplifyScale(fetched, finalCount, size);
            MemorySegment dst = a.allocate(ValueLayout.JAVA_INT, Math.max(1, n));
            MemorySegment error = a.allocate(ValueLayout.JAVA_FLOAT);
            long previous = n;
            for (int l = 1; l < o.lods() && n > 0; l++) {
                long target = Math.max(3, (long) (previous * o.lodRatio()) / 3 * 3);
                long got =
                        meshopt_simplify(
                                dst,
                                idx,
                                n,
                                fetched,
                                finalCount,
                                size,
                                target,
                                o.lodError(),
                                0,
                                error);
                if (got == 0 || got > previous * 0.9) {
                    break; // the simplifier hit the error budget: no useful smaller LOD
                }
                meshopt_optimizeVertexCache(dst, dst, got, finalCount);
                lods.add(dst.asSlice(0, got * 4).toArray(ValueLayout.JAVA_INT));
                errors[l] = error.get(ValueLayout.JAVA_FLOAT, 0) * scale;
                previous = got;
            }
            float[] out = fetched.asSlice(0, size * finalCount).toArray(ValueLayout.JAVA_FLOAT);
            renormalize(out, finalCount);
            return new Cooked(
                    out, finalCount, lods, Arrays.copyOf(errors, lods.size()), slot, skinned);
        }
    }

    private static MemorySegment positionsOf(Arena a, float[] interleaved, int count) {
        MemorySegment pos = a.allocate(ValueLayout.JAVA_FLOAT, Math.max(1, count * 3L));
        for (int v = 0; v < count; v++) {
            for (int k = 0; k < 3; k++) {
                pos.setAtIndex(ValueLayout.JAVA_FLOAT, v * 3L + k, interleaved[v * FLOATS + k]);
            }
        }
        return pos;
    }

    private static void renormalize(float[] v, int count) {
        for (int i = 0; i < count; i++) {
            int d = i * FLOATS + 3;
            float len = (float) Math.sqrt(v[d] * v[d] + v[d + 1] * v[d + 1] + v[d + 2] * v[d + 2]);
            if (len > 0) {
                v[d] /= len;
                v[d + 1] /= len;
                v[d + 2] /= len;
            }
        }
    }

    private static float[] gather(float[] src, int[] indices, int stride) {
        float[] out = new float[indices.length * stride];
        for (int i = 0; i < indices.length; i++) {
            System.arraycopy(src, indices[i] * stride, out, i * stride, stride);
        }
        return out;
    }

    private static int[] gatherInts(int[] src, int[] indices, int stride) {
        int[] out = new int[indices.length * stride];
        for (int i = 0; i < indices.length; i++) {
            System.arraycopy(src, indices[i] * stride, out, i * stride, stride);
        }
        return out;
    }

    /** Face normals for an unwelded triangle list (three corners per triangle). */
    static float[] flatNormals(float[] corners) {
        float[] n = new float[corners.length];
        for (int t = 0; t < corners.length / 9; t++) {
            int o = t * 9;
            float ax = corners[o + 3] - corners[o],
                    ay = corners[o + 4] - corners[o + 1],
                    az = corners[o + 5] - corners[o + 2];
            float bx = corners[o + 6] - corners[o],
                    by = corners[o + 7] - corners[o + 1],
                    bz = corners[o + 8] - corners[o + 2];
            float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 0) {
                nx /= len;
                ny /= len;
                nz /= len;
            } else {
                nz = 1;
            }
            for (int k = 0; k < 3; k++) {
                n[o + k * 3] = nx;
                n[o + k * 3 + 1] = ny;
                n[o + k * 3 + 2] = nz;
            }
        }
        return n;
    }

    /** MikkTSpace tangents per corner of an unwelded triangle list. */
    static float[] mikk(float[] positions, float[] normals, float[] uv, int[] indices) {
        int vertices = positions.length / 3;
        int triangles = indices.length / 3;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(ValueLayout.JAVA_FLOAT, Math.max(1, 12L * triangles));
            int ok =
                    JeddyTools.jeddy_mikk_generate_tangents(
                            a.allocateFrom(ValueLayout.JAVA_FLOAT, positions),
                            a.allocateFrom(ValueLayout.JAVA_FLOAT, normals),
                            a.allocateFrom(ValueLayout.JAVA_FLOAT, uv),
                            vertices,
                            a.allocateFrom(ValueLayout.JAVA_INT, indices),
                            triangles,
                            out);
            if (ok != 1) {
                throw new IllegalStateException("MikkTSpace failed");
            }
            // Per corner, and the corners are the vertices here (indices are 0..n-1).
            float[] t = out.asSlice(0, 16L * vertices).toArray(ValueLayout.JAVA_FLOAT);
            // MikkTSpace's bitangent follows +v; glTF's (the normal map's +Y) follows -v because
            // its UV origin is the image's top-left corner, so flip the sign (DESIGN D33).
            for (int i = 3; i < t.length; i += 4) {
                t[i] = -t[i];
            }
            return t;
        }
    }
}
