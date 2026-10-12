package dev.jeddy.render;

import dev.jeddy.asset.format.MeshData;
import dev.jeddy.asset.format.MeshWriter;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/** Procedural meshes for GPU tests. */
public final class TestMeshes {
    private TestMeshes() {}

    /** A UV sphere of radius {@code r} with normals, tangents along +u and UVs. */
    public static MeshData sphere(Arena arena, float r, int segments, int rings) {
        return sphere(arena, r, segments, rings, false);
    }

    /** {@link #sphere}, skinned with every vertex on joint 0 when {@code skinned}. */
    public static MeshData skinnedSphere(Arena arena, float r, int segments, int rings) {
        return sphere(arena, r, segments, rings, true);
    }

    private static MeshData sphere(Arena arena, float r, int segments, int rings, boolean skinned) {
        int verts = (segments + 1) * (rings + 1);
        float[] pos = new float[verts * 3];
        float[] nrm = new float[verts * 3];
        float[] tan = new float[verts * 4];
        float[] uv = new float[verts * 2];
        int v = 0;
        for (int y = 0; y <= rings; y++) {
            double theta = Math.PI * y / rings;
            for (int x = 0; x <= segments; x++) {
                double phi = 2 * Math.PI * x / segments;
                float nx = (float) (Math.sin(theta) * Math.cos(phi));
                float ny = (float) Math.cos(theta);
                float nz = (float) (-Math.sin(theta) * Math.sin(phi));
                pos[v * 3] = nx * r;
                pos[v * 3 + 1] = ny * r;
                pos[v * 3 + 2] = nz * r;
                nrm[v * 3] = nx;
                nrm[v * 3 + 1] = ny;
                nrm[v * 3 + 2] = nz;
                // d(position)/d(phi), normalized; at the poles pick any perpendicular.
                float tx = (float) -Math.sin(phi);
                float tz = (float) -Math.cos(phi);
                tan[v * 4] = tx;
                tan[v * 4 + 1] = 0;
                tan[v * 4 + 2] = tz;
                tan[v * 4 + 3] = 1;
                uv[v * 2] = (float) x / segments;
                uv[v * 2 + 1] = (float) y / rings;
                v++;
            }
        }
        int[] idx = new int[segments * rings * 6];
        int k = 0;
        for (int y = 0; y < rings; y++) {
            for (int x = 0; x < segments; x++) {
                int a = y * (segments + 1) + x;
                int b = a + segments + 1;
                idx[k++] = a;
                idx[k++] = b;
                idx[k++] = a + 1;
                idx[k++] = a + 1;
                idx[k++] = b;
                idx[k++] = b + 1;
            }
        }
        MeshWriter writer = new MeshWriter().positions(pos).attributes(nrm, tan, uv, null);
        if (skinned) {
            int[] joints = new int[verts * 4];
            float[] weights = new float[verts * 4];
            for (int i = 0; i < verts; i++) {
                weights[i * 4] = 1;
            }
            writer.skin(joints, weights);
        }
        return read(arena, writer.indices(idx).materials(0).toBytes());
    }

    /** A square of side {@code size} on the XZ plane facing +Y. */
    public static MeshData plane(Arena arena, float size) {
        float h = size / 2;
        float[] pos = {-h, 0, -h, h, 0, -h, h, 0, h, -h, 0, h};
        float[] nrm = {0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0};
        float[] tan = {1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1};
        float[] uv = {0, 0, 1, 0, 1, 1, 0, 1};
        int[] idx = {0, 3, 2, 0, 2, 1};
        return read(
                arena,
                new MeshWriter()
                        .positions(pos)
                        .attributes(nrm, tan, uv, null)
                        .indices(idx)
                        .materials(0)
                        .toBytes());
    }

    private static MeshData read(Arena arena, byte[] bytes) {
        MemorySegment s = arena.allocate(bytes.length, 64);
        MemorySegment.copy(bytes, 0, s, ValueLayout.JAVA_BYTE, 0, bytes.length);
        try {
            return MeshData.read(s);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
