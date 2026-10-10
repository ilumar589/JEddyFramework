package dev.jeddy.asset.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.AssetPaths;
import dev.jeddy.compress.Zstd;
import dev.jeddy.compress.ZstdCompressor;
import dev.jeddy.compress.ZstdDecompressor;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/** KTX2, .jmesh and .jmat readers and writers (TASKS M4-06). */
class FormatsTest {
    private static final Arena ARENA = Arena.ofShared();

    @AfterAll
    static void close() {
        ARENA.close();
    }

    private static MemorySegment nativeCopy(byte[] bytes) {
        MemorySegment s = ARENA.allocate(Math.max(1, bytes.length), 64);
        MemorySegment.copy(bytes, 0, s, ValueLayout.JAVA_BYTE, 0, bytes.length);
        return s.asSlice(0, bytes.length);
    }

    // ---------------------------------------------------------------------------------------
    // KTX2

    /** Writes a minimal KTX2 file (no DFD) with the given level contents, level 0 first. */
    static byte[] ktx2(int vkFormat, int width, int height, byte[][] levels, boolean zstd) {
        byte[][] stored = new byte[levels.length][];
        try (ZstdCompressor c = new ZstdCompressor(Zstd.DEFAULT_LEVEL);
                Arena a = Arena.ofConfined()) {
            for (int i = 0; i < levels.length; i++) {
                if (!zstd) {
                    stored[i] = levels[i];
                    continue;
                }
                MemorySegment src = a.allocate(Math.max(1, levels[i].length));
                MemorySegment.copy(levels[i], 0, src, ValueLayout.JAVA_BYTE, 0, levels[i].length);
                MemorySegment dst = a.allocate(Zstd.compressBound(levels[i].length));
                long n = c.compress(dst, src.asSlice(0, levels[i].length));
                stored[i] = dst.asSlice(0, n).toArray(ValueLayout.JAVA_BYTE);
            }
        }
        byte[] kv = keyValue("KTXwriter", "jeddy test");
        int kvOffset = Ktx2.LEVEL_INDEX_OFFSET + 24 * levels.length;
        long dataStart = (kvOffset + kv.length + 15) & ~15L;
        // Levels are stored smallest first.
        long[] offsets = new long[levels.length];
        long p = dataStart;
        for (int i = levels.length - 1; i >= 0; i--) {
            p = (p + 15) & ~15L;
            offsets[i] = p;
            p += stored[i].length;
        }
        ByteBuffer b = ByteBuffer.allocate((int) p).order(ByteOrder.LITTLE_ENDIAN);
        b.put(
                new byte[] {
                    (byte) 0xAB, 'K', 'T', 'X', ' ', '2', '0', (byte) 0xBB, '\r', '\n', 0x1A, '\n'
                });
        b.putInt(vkFormat).putInt(1).putInt(width).putInt(height).putInt(0);
        b.putInt(0).putInt(1).putInt(levels.length).putInt(zstd ? 2 : 0);
        b.putInt(0).putInt(0).putInt(kvOffset).putInt(kv.length).putLong(0).putLong(0);
        for (int i = 0; i < levels.length; i++) {
            b.putLong(offsets[i]).putLong(stored[i].length).putLong(levels[i].length);
        }
        b.put(kvOffset, kv);
        for (int i = 0; i < levels.length; i++) {
            b.put((int) offsets[i], stored[i]);
        }
        return b.array();
    }

    private static byte[] keyValue(String key, String value) {
        ByteArrayOutputStream kv = new ByteArrayOutputStream();
        byte[] k = key.getBytes(StandardCharsets.UTF_8);
        byte[] v = value.getBytes(StandardCharsets.UTF_8);
        int len = k.length + 1 + v.length + 1;
        kv.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(len).array());
        kv.writeBytes(k);
        kv.write(0);
        kv.writeBytes(v);
        kv.write(0);
        while (kv.size() % 4 != 0) {
            kv.write(0);
        }
        return kv.toByteArray();
    }

    /** BC7 levels of a 16x8 texture: 8, 2 and 1 blocks of 16 bytes. */
    private static byte[][] bc7Levels() {
        Random r = new Random(3);
        int[] blocks = {8, 2, 1, 1};
        byte[][] levels = new byte[blocks.length][];
        for (int i = 0; i < blocks.length; i++) {
            levels[i] = new byte[blocks[i] * 16];
            for (int k = 0; k < levels[i].length; k++) {
                levels[i][k] = (byte) (k % 5 == 0 ? r.nextInt() : i);
            }
        }
        return levels;
    }

    @Test
    void readsKtx2LevelsWithAndWithoutZstd() throws Exception {
        byte[][] levels = bc7Levels();
        try (ZstdDecompressor d = new ZstdDecompressor()) {
            for (boolean zstd : new boolean[] {false, true}) {
                Ktx2 k =
                        Ktx2.read(
                                nativeCopy(
                                        ktx2(Ktx2.VK_FORMAT_BC7_SRGB_BLOCK, 16, 8, levels, zstd)));
                assertEquals(Ktx2.VK_FORMAT_BC7_SRGB_BLOCK, k.vkFormat());
                assertTrue(Ktx2.isSrgb(k.vkFormat()));
                assertTrue(Ktx2.isBlockCompressed(k.vkFormat()));
                assertEquals(16, k.width());
                assertEquals(8, k.height());
                assertEquals(1, k.depth());
                assertEquals(1, k.layers());
                assertFalse(k.isArray());
                assertFalse(k.isCubeMap());
                assertEquals(4, k.levels());
                assertEquals(zstd ? Ktx2.SUPERCOMPRESSION_ZSTD : 0, k.supercompression());
                assertEquals(2, k.levelWidth(3));
                assertEquals(1, k.levelHeight(3));
                assertEquals(8 * 16 + 2 * 16 + 16 + 16, k.totalSize());
                assertEquals("jeddy test", k.keyValue("KTXwriter"));
                assertNull(k.keyValue("KTXorientation"));
                MemorySegment out = ARENA.allocate(k.totalSize());
                long at = 0;
                for (int i = 0; i < k.levels(); i++) {
                    assertEquals(levels[i].length, k.decodeLevel(i, out, at, d));
                    assertArrayEquals(
                            levels[i],
                            out.asSlice(at, levels[i].length).toArray(ValueLayout.JAVA_BYTE));
                    at += levels[i].length;
                }
                Allocations.assertNoAllocation(
                        "ktx2 level decode",
                        () -> {
                            long o = 0;
                            for (int i = 0; i < 4; i++) {
                                o += k.decodeLevel(i, out, o, d);
                            }
                        });
            }
        }
    }

    @Test
    void rejectsBadKtx2Files() {
        byte[][] levels = bc7Levels();
        byte[] good = ktx2(Ktx2.VK_FORMAT_BC7_UNORM_BLOCK, 16, 8, levels, false);

        byte[] badId = good.clone();
        badId[1] = 'X';
        assertThrows(AssetFormatException.class, () -> Ktx2.read(nativeCopy(badId)));

        byte[] basis = good.clone();
        ByteBuffer.wrap(basis).order(ByteOrder.LITTLE_ENDIAN).putInt(44, 1);
        AssetFormatException e =
                assertThrows(AssetFormatException.class, () -> Ktx2.read(nativeCopy(basis)));
        assertTrue(e.getMessage().contains("supercompression"), e.getMessage());

        byte[] truncated = java.util.Arrays.copyOf(good, good.length - 20);
        assertThrows(AssetFormatException.class, () -> Ktx2.read(nativeCopy(truncated)));

        byte[][] wrongSize = bc7Levels();
        wrongSize[0] = new byte[16 * 7]; // one block short
        assertThrows(
                AssetFormatException.class,
                () ->
                        Ktx2.read(
                                nativeCopy(
                                        ktx2(
                                                Ktx2.VK_FORMAT_BC7_UNORM_BLOCK,
                                                16,
                                                8,
                                                wrongSize,
                                                false))));
    }

    // ---------------------------------------------------------------------------------------
    // .jmesh

    @Test
    void qtangentsKeepTheFrameAndHandedness() {
        Random r = new Random(17);
        short[] q = new short[4];
        float[] n = new float[3];
        float[] t = new float[4];
        float[] outN = new float[3];
        float[] outT = new float[3];
        for (int i = 0; i < 20_000; i++) {
            randomUnit(r, n);
            randomUnit(r, t);
            float sign = r.nextBoolean() ? 1 : -1;
            MeshEncoding.encodeQTangent(n, 0, t, 0, sign, q, 0);
            MeshEncoding.qtangentNormal(q, 0, outN, 0);
            MeshEncoding.qtangentTangent(q, 0, outT, 0);
            assertEquals(sign, MeshEncoding.qtangentHandedness(q, 0));
            for (int k = 0; k < 3; k++) {
                assertEquals(n[k], outN[k], 2e-3f, "normal");
            }
            // The decoded tangent is the input orthogonalized against the normal.
            float d = n[0] * t[0] + n[1] * t[1] + n[2] * t[2];
            float[] ortho = {t[0] - n[0] * d, t[1] - n[1] * d, t[2] - n[2] * d};
            float len = (float) Math.sqrt(dot(ortho, ortho));
            if (len > 0.05f) {
                for (int k = 0; k < 3; k++) {
                    assertEquals(ortho[k] / len, outT[k], 3e-3f, "tangent");
                }
            }
        }
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static void randomUnit(Random r, float[] v) {
        float len;
        do {
            v[0] = r.nextFloat() * 2 - 1;
            v[1] = r.nextFloat() * 2 - 1;
            v[2] = r.nextFloat() * 2 - 1;
            len = (float) Math.sqrt(dot(v, v));
        } while (len < 0.1f || len > 1);
        v[0] /= len;
        v[1] /= len;
        v[2] /= len;
    }

    /** Two quads (submeshes) of 4 vertices, two LODs, skinned. */
    private static byte[] twoQuads() {
        float[] positions = {
            0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0, // quad A in z = 0
            2, 0, -1, 3, 0, -1, 3, 0, 1, 2, 0, 1 // quad B in y = 0
        };
        float[] normals = {0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0};
        float[] tangents = new float[32];
        for (int v = 0; v < 8; v++) {
            tangents[4 * v] = 1;
            tangents[4 * v + 3] = v < 4 ? 1 : -1;
        }
        float[] uv0 = {0, 0, 1, 0, 1, 1, 0, 1, 0, 0, 2.5f, 0, 2.5f, 2.5f, 0, 2.5f};
        int[] joints = new int[32];
        float[] weights = new float[32];
        for (int v = 0; v < 8; v++) {
            joints[4 * v] = v;
            joints[4 * v + 1] = 300;
            weights[4 * v] = 0.75f;
            weights[4 * v + 1] = 0.25f;
        }
        int[] indices = {
            0,
            1,
            2,
            0,
            2,
            3, // A LOD0
            0,
            1,
            2,
            0,
            2,
            3, // B LOD0
            0,
            1,
            2, // A LOD1
            0,
            2,
            3 // B LOD1
        };
        return new MeshWriter()
                .positions(positions)
                .attributes(normals, tangents, uv0, null)
                .skin(joints, weights)
                .indices(indices)
                .submesh(0, 0, 4)
                .submesh(1, 4, 4)
                .lod(0, new int[] {0, 6}, new int[] {6, 6})
                .lod(0.5f, new int[] {12, 15}, new int[] {3, 3})
                .materials(AssetPaths.id("mat/stone.jmat"), AssetPaths.id("mat/moss.jmat"))
                .toBytes();
    }

    @Test
    void meshRoundTrip() throws Exception {
        MeshData m = MeshData.read(nativeCopy(twoQuads()));
        assertEquals(8, m.vertexCount());
        assertEquals(18, m.indexCount());
        assertEquals(2, m.submeshCount());
        assertEquals(2, m.lodCount());
        assertEquals(2, m.materialSlotCount());
        assertTrue(m.hasSkin());
        assertEquals(0, m.boundsMin(0));
        assertEquals(-1, m.boundsMin(2));
        assertEquals(3, m.boundsMax(0));
        assertEquals(1, m.boundsMax(1));
        assertEquals(1.5f, m.sphere(0));
        assertEquals(Math.sqrt(1.5 * 1.5 + 0.5 * 0.5 + 1), m.sphere(3), 1e-5);
        assertEquals(1, m.submeshMaterial(1));
        assertEquals(4, m.submeshBaseVertex(1));
        assertEquals(4, m.submeshVertexCount(1));
        assertEquals(2, m.submeshMin(1, 0));
        assertEquals(0, m.submeshMax(0, 2));
        assertEquals(0.5f, m.lodError(1));
        assertEquals(15, m.lodFirstIndex(1, 1));
        assertEquals(3, m.lodIndexCount(1, 1));
        assertEquals(AssetPaths.id("mat/moss.jmat"), m.materialId(1));
        assertEquals(8 * 12, m.positions().byteSize());
        assertEquals(0, m.positions().address() % 64, "aligned streams");
        assertEquals(0, m.attributes().address() % 64);
        assertEquals(0, m.indices().address() % 64);
        assertEquals(3f, m.positions().getAtIndex(ValueLayout.JAVA_FLOAT, 3 * 6));
        assertEquals(3, m.indices().getAtIndex(ValueLayout.JAVA_INT, 17));

        short[] q = new short[4];
        float[] n = new float[3];
        for (int v = 0; v < 8; v++) {
            long at = (long) v * MeshData.ATTRIBUTE_STRIDE;
            for (int k = 0; k < 4; k++) {
                q[k] = m.attributes().get(ValueLayout.JAVA_SHORT, at + 2 * k);
            }
            MeshEncoding.qtangentNormal(q, 0, n, 0);
            assertEquals(v < 4 ? 1 : 0, n[2], 1e-3f);
            assertEquals(v < 4 ? 0 : 1, n[1], 1e-3f);
            assertEquals(v < 4 ? 1f : -1f, MeshEncoding.qtangentHandedness(q, 0));
            float u = MeshEncoding.unhalf(m.attributes().get(ValueLayout.JAVA_SHORT, at + 8));
            assertEquals(v == 5 || v == 6 ? 2.5f : (v == 1 || v == 2 ? 1f : 0f), u);
            long s = (long) v * MeshData.SKIN_STRIDE;
            assertEquals(v, m.skin().get(ValueLayout.JAVA_SHORT, s));
            assertEquals(300, m.skin().get(ValueLayout.JAVA_SHORT, s + 2));
            assertEquals(
                    0.75f,
                    MeshEncoding.ununorm16(m.skin().get(ValueLayout.JAVA_SHORT, s + 8)),
                    1e-4f);
        }
    }

    @Test
    void rejectsBadMeshes() {
        byte[] good = twoQuads();
        byte[] badIndex = good.clone();
        ByteBuffer b = ByteBuffer.wrap(badIndex).order(ByteOrder.LITTLE_ENDIAN);
        long indices = b.getLong(88);
        b.putInt((int) indices + 4 * 7, 4); // submesh B has vertices 0..3
        AssetFormatException e =
                assertThrows(AssetFormatException.class, () -> MeshData.read(nativeCopy(badIndex)));
        assertTrue(e.getMessage().contains("outside submesh 1"), e.getMessage());

        byte[] truncated = java.util.Arrays.copyOf(good, good.length - 8);
        assertThrows(AssetFormatException.class, () -> MeshData.read(nativeCopy(truncated)));

        byte[] badMagic = good.clone();
        badMagic[0] = 0;
        assertThrows(AssetFormatException.class, () -> MeshData.read(nativeCopy(badMagic)));

        assertThrows(
                IllegalStateException.class,
                () -> new MeshWriter().positions(new float[] {0, 0, 0}).toBytes());
    }

    // ---------------------------------------------------------------------------------------
    // .jmat

    @Test
    void materialRoundTrip() throws Exception {
        MaterialData written =
                new MaterialData.Builder()
                        .flags(MaterialData.FLAG_DOUBLE_SIDED)
                        .shadingModel(MaterialData.SHADING_FOLIAGE)
                        .featureMask(0b1011)
                        .alpha(MaterialData.ALPHA_MASK, 0.3f)
                        .baseColor(0.5f, 0.25f, 1, 0.9f)
                        .emissive(1, 0.5f, 0, 4)
                        .metallicRoughness(0, 0.7f)
                        .normalOcclusion(0.8f, 0.6f)
                        .uvTransform(2, 3, 0.25f, 0.5f, 0.1f)
                        .texture(MaterialData.SLOT_BASE_COLOR, AssetPaths.id("tex/leaf_c.ktx2"), 0)
                        .texture(MaterialData.SLOT_NORMAL, AssetPaths.id("tex/leaf_n.ktx2"), 0)
                        .texture(MaterialData.SLOT_ORM, AssetPaths.id("tex/leaf_orm.ktx2"), 0)
                        .texture(MaterialData.SLOT_EMISSIVE, AssetPaths.id("tex/glow.ktx2"), 0)
                        .texture(7, AssetPaths.id("tex/wind.ktx2"), MaterialData.TEXTURE_UV1)
                        .build();
        MaterialData m = MaterialData.read(nativeCopy(written.toBytes()));
        assertTrue(m.doubleSided());
        assertFalse(m.castsShadows());
        assertEquals(MaterialData.SHADING_FOLIAGE, m.shadingModel());
        assertEquals(0b1011, m.featureMask());
        assertEquals(MaterialData.ALPHA_MASK, m.alphaMode());
        assertEquals(0.3f, m.alphaCutoff());
        assertEquals(0.25f, m.baseColor(1));
        assertEquals(0.9f, m.baseColor(3));
        assertEquals(0.5f, m.emissive(1));
        assertEquals(4f, m.emissiveStrength());
        assertEquals(0f, m.metallic());
        assertEquals(0.7f, m.roughness());
        assertEquals(0.8f, m.normalScale());
        assertEquals(0.6f, m.occlusionStrength());
        assertEquals(3f, m.uvTransform(1));
        assertEquals(0.1f, m.uvRotation());
        assertEquals(5, m.textureCount());
        assertEquals(
                AssetPaths.id("tex/leaf_orm.ktx2"),
                m.textureId(m.findTexture(MaterialData.SLOT_ORM)));
        assertEquals(MaterialData.TEXTURE_UV1, m.textureFlags(4));
        assertEquals(-1, m.findTexture(9));
        assertArrayEquals(written.toBytes(), m.toBytes());

        MaterialData defaults =
                MaterialData.read(nativeCopy(new MaterialData.Builder().build().toBytes()));
        assertTrue(defaults.castsShadows());
        assertEquals(1f, defaults.roughness());
        assertEquals(0, defaults.textureCount());

        byte[] bad = written.toBytes();
        bad[16] = 9; // alpha mode
        assertThrows(AssetFormatException.class, () -> MaterialData.read(nativeCopy(bad)));
    }
}
