package dev.jeddy.asset.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** {@code .jscene} round trips and validation. */
class SceneDataTest {
    private static final float[] IDENTITY = {0, 0, 0, 0, 0, 0, 1, 1, 1, 1};

    private static SceneData sample() {
        SceneData.Builder b = new SceneData.Builder();
        int crate = b.mesh(0x1234L);
        int barrel = b.mesh(0x5678L);
        int torch =
                b.light(
                        new SceneData.Light(
                                "torch", SceneData.LIGHT_SPOT, 1, 0.8f, 0.6f, 20, 8, 0.2f, 0.6f));
        int root = b.node("root", -1, -1, -1, -1, new float[] {1, 2, 3, 0, 0, 0, 1, 1, 1, 1});
        b.node(
                "crate",
                root,
                crate,
                -1,
                -1,
                new float[] {0, 0, 1, 0, 0.7071f, 0, 0.7071f, 2, 2, 2});
        b.node("barrel", root, barrel, -1, 0, IDENTITY);
        b.node("crate copy", -1, b.mesh(0x1234L), torch, -1, IDENTITY);
        float[] ibm = new float[32];
        ibm[0] = ibm[5] = ibm[10] = ibm[15] = 1;
        ibm[16] = ibm[21] = ibm[26] = ibm[31] = 2;
        b.skin(new SceneData.Skin(0, new int[] {1, 2}, ibm));
        b.animation(
                new SceneData.Animation(
                        "open",
                        1.5f,
                        List.of(
                                new SceneData.Channel(
                                        1,
                                        SceneData.PATH_TRANSLATION,
                                        SceneData.INTERPOLATION_LINEAR,
                                        3,
                                        new float[] {0, 1.5f},
                                        new float[] {0, 0, 0, 0, 1, 0}),
                                new SceneData.Channel(
                                        2,
                                        SceneData.PATH_ROTATION,
                                        SceneData.INTERPOLATION_CUBIC,
                                        4,
                                        new float[] {0},
                                        new float[] {0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0}))));
        return b.build();
    }

    @Test
    void roundTrips() throws AssetFormatException {
        SceneData s = SceneData.read(MemorySegment.ofArray(sample().toBytes()));
        assertEquals(4, s.nodeCount());
        assertEquals(2, s.meshCount(), "mesh references are shared");
        assertEquals(0x1234L, s.meshId(0));
        assertEquals("crate", s.nodeName(1));
        assertEquals("crate copy", s.nodeName(3));
        assertEquals(0, s.parent(1));
        assertEquals(-1, s.parent(3));
        assertEquals(0, s.mesh(3));
        assertEquals(1, s.mesh(2));
        assertEquals(0, s.light(3));
        assertEquals(0, s.skin(2));
        assertEquals(1f, s.trs(0, 0));
        assertEquals(0.7071f, s.trs(1, 4));
        assertEquals(2f, s.trs(1, 9));
        SceneData.Light l = s.lights().get(0);
        assertEquals("torch", l.name());
        assertEquals(SceneData.LIGHT_SPOT, l.type());
        assertEquals(0.6f, l.outerCone());
        assertArrayEquals(new int[] {1, 2}, s.skins().get(0).joints());
        assertEquals(2f, s.skins().get(0).inverseBind()[31]);
        SceneData.Animation a = s.animations().get(0);
        assertEquals("open", a.name());
        assertEquals(1.5f, a.duration());
        assertEquals(2, a.channels().size());
        assertArrayEquals(new float[] {0, 0, 0, 0, 1, 0}, a.channels().get(0).values());
        assertEquals(12, a.channels().get(1).values().length);
    }

    @Test
    void rejectsCorruptFiles() {
        byte[] good = sample().toBytes();
        assertThrows(
                AssetFormatException.class,
                () -> SceneData.read(MemorySegment.ofArray(new byte[8])));
        byte[] truncated = java.util.Arrays.copyOf(good, good.length - 4);
        assertThrows(
                AssetFormatException.class, () -> SceneData.read(MemorySegment.ofArray(truncated)));
        // A child before its parent.
        byte[] badParent = good.clone();
        ByteBuffer.wrap(badParent)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(SceneData.HEADER_SIZE + 8, 2);
        assertThrows(
                AssetFormatException.class, () -> SceneData.read(MemorySegment.ofArray(badParent)));
        // A mesh index past the table.
        byte[] badMesh = good.clone();
        ByteBuffer.wrap(badMesh)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(SceneData.HEADER_SIZE + SceneData.NODE_SIZE + 12, 9);
        assertThrows(
                AssetFormatException.class, () -> SceneData.read(MemorySegment.ofArray(badMesh)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SceneData.Builder().node("x", 0, -1, -1, -1, IDENTITY));
    }
}
