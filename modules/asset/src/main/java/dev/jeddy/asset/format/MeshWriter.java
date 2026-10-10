package dev.jeddy.asset.format;

import static dev.jeddy.asset.format.Bytes.align;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a {@code .jmesh} ({@code docs/formats/jmesh.md}) from float arrays: encodes the tangent
 * frames as QTangents and the texture coordinates as half floats, computes bounds and lays out the
 * aligned sections. The cooker uses it after welding, optimizing and simplifying.
 *
 * <p>Thread ownership: one thread (tool code).
 */
public final class MeshWriter {
    private record Submesh(int material, int baseVertex, int vertexCount) {}

    private record Lod(float error, int[] firstIndex, int[] indexCount) {}

    private float[] positions;
    private float[] normals;
    private float[] tangents;
    private float[] uv0;
    private float[] uv1;
    private int[] joints;
    private float[] weights;
    private int[] indices = new int[0];
    private final List<Submesh> submeshes = new ArrayList<>();
    private final List<Lod> lods = new ArrayList<>();
    private long[] materials = new long[0];

    /** Creates an empty writer. */
    public MeshWriter() {}

    /** Sets positions, {@code x, y, z} per vertex. */
    public MeshWriter positions(float[] xyz) {
        positions = xyz.clone();
        return this;
    }

    /**
     * Sets the tangent frames and texture coordinates.
     *
     * @param normals {@code x, y, z} per vertex
     * @param tangents {@code x, y, z, w} per vertex, {@code w} the bitangent sign (glTF
     *     convention), or {@code null} for an arbitrary tangent
     * @param uv0 {@code u, v} per vertex, or {@code null}
     * @param uv1 {@code u, v} per vertex, or {@code null}
     */
    public MeshWriter attributes(float[] normals, float[] tangents, float[] uv0, float[] uv1) {
        this.normals = normals.clone();
        this.tangents = tangents == null ? null : tangents.clone();
        this.uv0 = uv0 == null ? null : uv0.clone();
        this.uv1 = uv1 == null ? null : uv1.clone();
        return this;
    }

    /**
     * Adds the skin stream.
     *
     * @param joints four joint indices (0 to 65535) per vertex
     * @param weights four weights per vertex, summing to 1
     */
    public MeshWriter skin(int[] joints, float[] weights) {
        this.joints = joints.clone();
        this.weights = weights.clone();
        return this;
    }

    /** Sets the index buffer: all LODs of all submeshes, relative to each base vertex. */
    public MeshWriter indices(int[] indices) {
        this.indices = indices.clone();
        return this;
    }

    /** Adds a submesh drawn with material slot {@code material}. */
    public MeshWriter submesh(int material, int baseVertex, int vertexCount) {
        submeshes.add(new Submesh(material, baseVertex, vertexCount));
        return this;
    }

    /**
     * Adds a LOD: one index range per submesh (in submesh order). Add LOD 0 first.
     *
     * @param error the LOD's geometric error in mesh units (0 for LOD 0)
     */
    public MeshWriter lod(float error, int[] firstIndex, int[] indexCount) {
        lods.add(new Lod(error, firstIndex.clone(), indexCount.clone()));
        return this;
    }

    /** Sets the default material of each slot ({@code StrId}s of material asset paths). */
    public MeshWriter materials(long... ids) {
        materials = ids.clone();
        return this;
    }

    /** Encodes the mesh. */
    public byte[] toBytes() {
        if (positions == null || positions.length % 3 != 0) {
            throw new IllegalStateException("positions must be set, 3 floats per vertex");
        }
        int vertexCount = positions.length / 3;
        if (normals == null || normals.length != 3 * vertexCount) {
            throw new IllegalStateException("normals must be set, 3 floats per vertex");
        }
        checkLength(tangents, 4, vertexCount, "tangents");
        checkLength(uv0, 2, vertexCount, "uv0");
        checkLength(uv1, 2, vertexCount, "uv1");
        boolean hasSkin = joints != null;
        if (hasSkin) {
            checkLength(weights, 4, vertexCount, "weights");
            if (joints.length != 4 * vertexCount) {
                throw new IllegalStateException("joints need 4 ints per vertex");
            }
        }
        if (submeshes.isEmpty()) {
            submeshes.add(new Submesh(0, 0, vertexCount));
        }
        if (lods.isEmpty()) {
            if (submeshes.size() != 1) {
                throw new IllegalStateException("several submeshes need explicit LOD ranges");
            }
            lods.add(new Lod(0f, new int[] {0}, new int[] {indices.length}));
        }
        int submeshCount = submeshes.size();
        for (Lod l : lods) {
            if (l.firstIndex.length != submeshCount || l.indexCount.length != submeshCount) {
                throw new IllegalStateException("each LOD needs one range per submesh");
            }
        }

        long positionsOffset = MeshData.HEADER_SIZE;
        long attributesOffset =
                align(positionsOffset + (long) vertexCount * MeshData.POSITION_STRIDE, 64);
        long skinOffset =
                align(attributesOffset + (long) vertexCount * MeshData.ATTRIBUTE_STRIDE, 64);
        long indicesOffset =
                hasSkin
                        ? align(skinOffset + (long) vertexCount * MeshData.SKIN_STRIDE, 64)
                        : skinOffset;
        long submeshOffset = align(indicesOffset + 4L * indices.length, 64);
        long lodOffset = align(submeshOffset + (long) submeshCount * MeshData.SUBMESH_SIZE, 64);
        long materialOffset =
                align(lodOffset + 8L * lods.size() + 8L * lods.size() * submeshCount, 64);
        long size = materialOffset + 8L * materials.length;
        ByteBuffer b = ByteBuffer.allocate(Math.toIntExact(size)).order(ByteOrder.LITTLE_ENDIAN);

        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        bounds(0, vertexCount, min, max);
        float cx = (min[0] + max[0]) / 2;
        float cy = (min[1] + max[1]) / 2;
        float cz = (min[2] + max[2]) / 2;
        float r2 = 0;
        for (int v = 0; v < vertexCount; v++) {
            float dx = positions[3 * v] - cx;
            float dy = positions[3 * v + 1] - cy;
            float dz = positions[3 * v + 2] - cz;
            r2 = Math.max(r2, dx * dx + dy * dy + dz * dz);
        }
        if (vertexCount == 0) {
            min = new float[3];
            max = new float[3];
            cx = cy = cz = 0;
        }

        b.putInt(0, MeshData.MAGIC);
        b.putShort(4, (short) MeshData.VERSION);
        b.putShort(6, (short) (hasSkin ? MeshData.FLAG_SKIN : 0));
        b.putInt(8, vertexCount);
        b.putInt(12, indices.length);
        b.putShort(16, (short) submeshCount);
        b.putShort(18, (short) lods.size());
        b.putShort(20, (short) materials.length);
        for (int i = 0; i < 3; i++) {
            b.putFloat(24 + 4 * i, min[i]);
            b.putFloat(36 + 4 * i, max[i]);
        }
        b.putFloat(48, cx);
        b.putFloat(52, cy);
        b.putFloat(56, cz);
        b.putFloat(60, (float) Math.sqrt(r2));
        b.putLong(64, positionsOffset);
        b.putLong(72, attributesOffset);
        b.putLong(80, hasSkin ? skinOffset : 0);
        b.putLong(88, indicesOffset);
        b.putLong(96, submeshOffset);
        b.putLong(104, lodOffset);
        b.putLong(112, materialOffset);
        b.putLong(120, size);

        for (int i = 0; i < positions.length; i++) {
            b.putFloat((int) positionsOffset + 4 * i, positions[i]);
        }
        short[] q = new short[4];
        float[] defaultTangent = {1, 0, 0};
        for (int v = 0; v < vertexCount; v++) {
            int at = (int) attributesOffset + v * MeshData.ATTRIBUTE_STRIDE;
            if (tangents != null) {
                MeshEncoding.encodeQTangent(
                        normals, 3 * v, tangents, 4 * v, tangents[4 * v + 3], q, 0);
            } else {
                MeshEncoding.encodeQTangent(normals, 3 * v, defaultTangent, 0, 1f, q, 0);
            }
            for (int k = 0; k < 4; k++) {
                b.putShort(at + 2 * k, q[k]);
            }
            b.putShort(at + 8, MeshEncoding.half(uv0 == null ? 0 : uv0[2 * v]));
            b.putShort(at + 10, MeshEncoding.half(uv0 == null ? 0 : uv0[2 * v + 1]));
            b.putShort(at + 12, MeshEncoding.half(uv1 == null ? 0 : uv1[2 * v]));
            b.putShort(at + 14, MeshEncoding.half(uv1 == null ? 0 : uv1[2 * v + 1]));
        }
        if (hasSkin) {
            for (int v = 0; v < vertexCount; v++) {
                int at = (int) skinOffset + v * MeshData.SKIN_STRIDE;
                for (int k = 0; k < 4; k++) {
                    int j = joints[4 * v + k];
                    if (j < 0 || j > 0xFFFF) {
                        throw new IllegalStateException("joint index out of range: " + j);
                    }
                    b.putShort(at + 2 * k, (short) j);
                    b.putShort(at + 8 + 2 * k, MeshEncoding.unorm16(weights[4 * v + k]));
                }
            }
        }
        for (int i = 0; i < indices.length; i++) {
            b.putInt((int) indicesOffset + 4 * i, indices[i]);
        }
        for (int s = 0; s < submeshCount; s++) {
            Submesh sm = submeshes.get(s);
            int at = (int) submeshOffset + s * MeshData.SUBMESH_SIZE;
            b.putInt(at, sm.material);
            b.putInt(at + 4, sm.baseVertex);
            b.putInt(at + 8, sm.vertexCount);
            float[] smin = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
            float[] smax = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            bounds(sm.baseVertex, sm.vertexCount, smin, smax);
            if (sm.vertexCount == 0) {
                smin = new float[3];
                smax = new float[3];
            }
            for (int i = 0; i < 3; i++) {
                b.putFloat(at + 16 + 4 * i, smin[i]);
                b.putFloat(at + 28 + 4 * i, smax[i]);
            }
        }
        for (int l = 0; l < lods.size(); l++) {
            Lod lod = lods.get(l);
            b.putFloat((int) lodOffset + 8 * l, lod.error);
            for (int s = 0; s < submeshCount; s++) {
                int at = (int) lodOffset + 8 * lods.size() + 8 * (l * submeshCount + s);
                b.putInt(at, lod.firstIndex[s]);
                b.putInt(at + 4, lod.indexCount[s]);
            }
        }
        for (int m = 0; m < materials.length; m++) {
            b.putLong((int) materialOffset + 8 * m, materials[m]);
        }
        return b.array();
    }

    private void bounds(int first, int count, float[] min, float[] max) {
        for (int v = first; v < first + count; v++) {
            for (int i = 0; i < 3; i++) {
                float p = positions[3 * v + i];
                min[i] = Math.min(min[i], p);
                max[i] = Math.max(max[i], p);
            }
        }
    }

    private static void checkLength(float[] a, int per, int vertexCount, String what) {
        if (a != null && a.length != per * vertexCount) {
            throw new IllegalStateException(what + " needs " + per + " floats per vertex");
        }
    }
}
