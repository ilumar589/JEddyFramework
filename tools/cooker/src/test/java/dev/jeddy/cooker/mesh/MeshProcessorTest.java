package dev.jeddy.cooker.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.format.MeshData;
import dev.jeddy.asset.format.MeshEncoding;
import dev.jeddy.cooker.gltf.GltfImporter.Primitive;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;

/** Mesh cooking: normals, MikkTSpace tangents, welding, optimization and LODs (TASKS M6-04). */
class MeshProcessorTest {
    /** A unit cube, 24 vertices with face normals and per-face UVs (u along the first axis). */
    private static Primitive cube(boolean withNormals) {
        float[][] faces = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        Primitive p = new Primitive();
        p.positions = new float[72];
        p.normals = withNormals ? new float[72] : null;
        p.uv0 = new float[48];
        p.indices = new int[36];
        int v = 0;
        for (int f = 0; f < 6; f++) {
            float[] n = faces[f];
            float[] u =
                    n[0] != 0
                            ? new float[] {0, n[0], 0}
                            : n[1] != 0 ? new float[] {0, 0, n[1]} : new float[] {n[2], 0, 0};
            float[] w = {
                n[1] * u[2] - n[2] * u[1], n[2] * u[0] - n[0] * u[2], n[0] * u[1] - n[1] * u[0]
            };
            float[][] corners = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
            for (float[] c : corners) {
                for (int k = 0; k < 3; k++) {
                    p.positions[v * 3 + k] = 0.5f * (n[k] + c[0] * u[k] + c[1] * w[k]);
                    if (withNormals) {
                        p.normals[v * 3 + k] = n[k];
                    }
                }
                p.uv0[v * 2] = (c[0] + 1) / 2;
                p.uv0[v * 2 + 1] = (1 - c[1]) / 2; // glTF: v grows downwards in the image
                v++;
            }
            int b = f * 4;
            int[] tri = {b, b + 1, b + 2, b, b + 2, b + 3};
            System.arraycopy(tri, 0, p.indices, f * 6, 6);
        }
        return p;
    }

    /** An {@code n × n} grid with a smooth bump (simplifiable). */
    private static Primitive bump(int n, float yOffset) {
        Primitive p = new Primitive();
        int verts = (n + 1) * (n + 1);
        p.positions = new float[verts * 3];
        p.normals = new float[verts * 3];
        p.uv0 = new float[verts * 2];
        for (int z = 0; z <= n; z++) {
            for (int x = 0; x <= n; x++) {
                int i = z * (n + 1) + x;
                float fx = x / (float) n - 0.5f;
                float fz = z / (float) n - 0.5f;
                float h = (float) (0.2 * Math.exp(-(fx * fx + fz * fz) * 8));
                p.positions[i * 3] = fx;
                p.positions[i * 3 + 1] = h + yOffset;
                p.positions[i * 3 + 2] = fz;
                float dx = -16 * fx * h;
                float dz = -16 * fz * h;
                float len = (float) Math.sqrt(dx * dx + 1 + dz * dz);
                p.normals[i * 3] = -dx / len;
                p.normals[i * 3 + 1] = 1 / len;
                p.normals[i * 3 + 2] = -dz / len;
                p.uv0[i * 2] = x / (float) n;
                p.uv0[i * 2 + 1] = z / (float) n;
            }
        }
        p.indices = new int[n * n * 6];
        int k = 0;
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                int a = z * (n + 1) + x;
                int c = a + n + 1;
                int[] q = {a, c, a + 1, a + 1, c, c + 1};
                System.arraycopy(q, 0, p.indices, k, 6);
                k += 6;
            }
        }
        return p;
    }

    private static MeshData read(byte[] jmesh) throws Exception {
        return MeshData.read(MemorySegment.ofArray(jmesh));
    }

    private static short[] qtangents(MeshData m) {
        MemorySegment a = m.attributes();
        short[] q = new short[m.vertexCount() * 4];
        for (int v = 0; v < m.vertexCount(); v++) {
            for (int k = 0; k < 4; k++) {
                q[v * 4 + k] = a.get(ValueLayout.JAVA_SHORT_UNALIGNED, v * 16L + k * 2);
            }
        }
        return q;
    }

    @Test
    void generatesTangentsFollowingGltfConventions() throws Exception {
        MeshProcessor.Result r =
                MeshProcessor.process(
                        List.of(cube(true)),
                        new int[] {0},
                        new long[] {42},
                        MeshProcessor.Options.DEFAULT);
        MeshData m = read(r.jmesh());
        assertEquals(24, m.vertexCount(), "corners with the same frame weld back to 24 vertices");
        assertEquals(36, m.lodIndexCount(0, 0));
        assertEquals(42, m.materialId(0));
        short[] q = qtangents(m);
        float[] n = new float[3];
        float[] t = new float[3];
        for (int v = 0; v < m.vertexCount(); v++) {
            MeshEncoding.qtangentNormal(q, v * 4, n, 0);
            MeshEncoding.qtangentTangent(q, v * 4, t, 0);
            assertEquals(1f, Math.abs(n[0]) + Math.abs(n[1]) + Math.abs(n[2]), 1e-3f);
            assertEquals(0f, n[0] * t[0] + n[1] * t[1] + n[2] * t[2], 1e-3f);
            // u grows along the face's first axis, so the tangent is that axis; the bitangent
            // (cross(n, t) * sign) must point up the image, i.e. towards decreasing v.
            float[] axis = {Math.round(n[0]), Math.round(n[1]), Math.round(n[2])};
            float[] faceU =
                    axis[0] != 0
                            ? new float[] {0, axis[0], 0}
                            : axis[1] != 0
                                    ? new float[] {0, 0, axis[1]}
                                    : new float[] {axis[2], 0, 0};
            for (int k = 0; k < 3; k++) {
                assertEquals(faceU[k], t[k], 1e-2f, "tangent of vertex " + v);
            }
            assertEquals(
                    1f, MeshEncoding.qtangentHandedness(q, v * 4), "handedness of vertex " + v);
        }
        assertEquals(12, r.stats().triangles());
        assertEquals(24, r.stats().vertices());
    }

    @Test
    void computesFlatNormalsWhenTheSourceHasNone() throws Exception {
        // Eight shared corners: welding must split them by face normal back to 24 vertices.
        Primitive c = cube(false);
        Primitive shared = new Primitive();
        shared.positions = new float[24];
        shared.indices = new int[36];
        int count = 0;
        int[] map = new int[24];
        for (int v = 0; v < 24; v++) {
            int found = -1;
            for (int s = 0; s < count && found < 0; s++) {
                if (shared.positions[s * 3] == c.positions[v * 3]
                        && shared.positions[s * 3 + 1] == c.positions[v * 3 + 1]
                        && shared.positions[s * 3 + 2] == c.positions[v * 3 + 2]) {
                    found = s;
                }
            }
            if (found < 0) {
                System.arraycopy(c.positions, v * 3, shared.positions, count * 3, 3);
                found = count++;
            }
            map[v] = found;
        }
        assertEquals(8, count);
        for (int i = 0; i < 36; i++) {
            shared.indices[i] = map[c.indices[i]];
        }
        MeshData m =
                read(
                        MeshProcessor.process(
                                        List.of(shared),
                                        new int[] {0},
                                        new long[] {1},
                                        MeshProcessor.Options.DEFAULT)
                                .jmesh());
        assertEquals(24, m.vertexCount());
        short[] q = qtangents(m);
        float[] n = new float[3];
        for (int v = 0; v < 24; v++) {
            MeshEncoding.qtangentNormal(q, v * 4, n, 0);
            float px = m.positions().get(ValueLayout.JAVA_FLOAT_UNALIGNED, v * 12L);
            float py = m.positions().get(ValueLayout.JAVA_FLOAT_UNALIGNED, v * 12L + 4);
            float pz = m.positions().get(ValueLayout.JAVA_FLOAT_UNALIGNED, v * 12L + 8);
            // Outward: the normal agrees with the position on its axis.
            assertTrue(n[0] * px + n[1] * py + n[2] * pz > 0.49f, "outward normal at vertex " + v);
        }
    }

    @Test
    void buildsLodsWithIncreasingErrorAndFewerTriangles() throws Exception {
        MeshProcessor.Result r =
                MeshProcessor.process(
                        List.of(bump(64, 0)),
                        new int[] {0},
                        new long[] {7},
                        MeshProcessor.Options.DEFAULT);
        MeshData m = read(r.jmesh());
        assertEquals(65 * 65, m.vertexCount());
        assertTrue(m.lodCount() >= 3, "LODs: " + m.lodCount());
        for (int l = 1; l < m.lodCount(); l++) {
            assertTrue(
                    m.lodIndexCount(l, 0) < m.lodIndexCount(l - 1, 0) * 0.9,
                    "LOD " + l + " is smaller");
            assertTrue(m.lodError(l) >= m.lodError(l - 1), "LOD " + l + " error grows");
            assertTrue(
                    m.lodError(l) <= 0.05f * 1.0f + 1e-4f,
                    "LOD " + l + " error within budget: " + m.lodError(l));
        }
        MeshProcessor.Stats s = r.stats();
        assertEquals(64 * 64 * 2, s.triangles());
        assertTrue(s.acmr() < 0.9f, "vertex cache optimized: ACMR " + s.acmr());
        assertTrue(s.atvr() < 1.6f, "ATVR " + s.atvr());
        assertTrue(s.overdraw() >= 1f);
    }

    @Test
    void mergesPrimitivesIntoSubmeshesWithSharedLodRanges() throws Exception {
        MeshProcessor.Result r =
                MeshProcessor.process(
                        List.of(bump(32, 0), cube(true)),
                        new int[] {0, 1},
                        new long[] {11, 12},
                        MeshProcessor.Options.DEFAULT);
        MeshData m = read(r.jmesh());
        assertEquals(2, m.submeshCount());
        assertEquals(2, m.materialSlotCount());
        assertEquals(0, m.submeshBaseVertex(0));
        assertEquals(33 * 33, m.submeshVertexCount(0));
        assertEquals(33 * 33, m.submeshBaseVertex(1));
        assertEquals(24, m.submeshVertexCount(1));
        assertEquals(1, m.submeshMaterial(1));
        assertTrue(m.lodCount() >= 2);
        for (int l = 1; l < m.lodCount(); l++) {
            // The cube cannot simplify, so its later LODs reuse its last range.
            assertEquals(m.lodFirstIndex(0, 1), m.lodFirstIndex(l, 1));
            assertEquals(36, m.lodIndexCount(l, 1));
        }
        assertEquals(-0.5f, m.boundsMin(1), 1e-6f);
        assertEquals(0.5f, m.boundsMax(0), 1e-6f);
    }

    @Test
    void keepsSkinWeights() throws Exception {
        Primitive p = bump(8, 0);
        int vertices = p.vertexCount();
        p.joints = new int[vertices * 4];
        p.weights = new float[vertices * 4];
        for (int v = 0; v < vertices; v++) {
            float x = p.positions[v * 3] + 0.5f;
            p.joints[v * 4] = 3;
            p.joints[v * 4 + 1] = 5;
            p.weights[v * 4] = 1 - x;
            p.weights[v * 4 + 1] = x;
        }
        MeshData m =
                read(
                        MeshProcessor.process(
                                        List.of(p),
                                        new int[] {0},
                                        new long[] {1},
                                        new MeshProcessor.Options(1, 0.5f, 0.05f, true, 1.05f))
                                .jmesh());
        assertTrue(m.hasSkin());
        MemorySegment skin = m.skin();
        for (int v = 0; v < m.vertexCount(); v++) {
            assertEquals(3, skin.get(ValueLayout.JAVA_SHORT_UNALIGNED, v * 16L));
            assertEquals(5, skin.get(ValueLayout.JAVA_SHORT_UNALIGNED, v * 16L + 2));
            float x = m.positions().get(ValueLayout.JAVA_FLOAT_UNALIGNED, v * 12L) + 0.5f;
            float w1 =
                    MeshEncoding.ununorm16(
                            skin.get(ValueLayout.JAVA_SHORT_UNALIGNED, v * 16L + 10));
            assertEquals(x, w1, 2e-3f);
        }
    }
}
