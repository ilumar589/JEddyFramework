package dev.jeddy.animation;

import static dev.jeddy.animation.TestRig.*;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.format.SceneData;
import dev.jeddy.core.id.StrId;
import dev.jeddy.math.AffineOps;

import org.junit.jupiter.api.Test;

/** Joints, parents, pre-transforms, inverse binds and attachments (TASKS M12-02). */
class SkeletonTest {
    private static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};

    @Test
    void readsJointsInSkinOrderWithParentsAndEvaluationOrder() {
        Skeleton s = Skeleton.of(TestRig.scene(), 0);
        assertEquals(3, s.jointCount());
        assertEquals(SPINE, s.node(J_SPINE));
        assertEquals(J_HIPS, s.parent(J_SPINE));
        assertEquals(-1, s.parent(J_HIPS));
        assertEquals(J_SPINE, s.parent(J_HAND));
        // Parents first: hips, spine, hand.
        assertEquals(J_HIPS, s.evaluationOrder(0));
        assertEquals(J_SPINE, s.evaluationOrder(1));
        assertEquals(J_HAND, s.evaluationOrder(2));
        assertEquals(J_HAND, s.find("hand"));
        assertEquals(J_HAND, s.find(StrId.of("hand")));
        assertEquals(-1, s.find("sword"));
        assertEquals(J_HIPS, s.jointOfNode(HIPS));
        assertEquals(-1, s.jointOfNode(SOCKET));
        assertEquals(0.5f, s.bind(J_SPINE, 1));
        assertTrue(s.hasPre(J_HIPS), "the armature's turn and scale");
        assertTrue(!s.hasPre(J_SPINE));
    }

    @Test
    void bindPosePalettesAreIdentityAndModelsMatchTheScene() {
        SceneData scene = TestRig.scene();
        Skeleton s = Skeleton.of(scene, 0);
        Animations a = new Animations(4, 64, 1);
        int i = a.add(s);
        float[][] world = TestRig.worldTransforms(scene);
        for (int j = 0; j < s.jointCount(); j++) {
            int o = (a.jointBase(i) + j) * AffineOps.STRIDE;
            assertArrayEquals(
                    IDENTITY,
                    java.util.Arrays.copyOfRange(a.palettes(), o, o + 12),
                    1e-5f,
                    "palette " + j);
            assertArrayEquals(
                    world[s.node(j)],
                    java.util.Arrays.copyOfRange(a.models(), o, o + 12),
                    1e-5f,
                    "model " + j);
        }
        // Joints at hips (0,2,0), spine (0,3,0) and hand: the armature scales by 2.
        float[] b = a.bounds();
        assertEquals(0, b[i * 4 + 1] - 2.5f, 1e-5f);
    }

    @Test
    void findsAttachmentsAndTheirOffsets() {
        SceneData scene = TestRig.scene();
        Skeleton s = Skeleton.of(scene, 0);
        float[] offset = new float[12];
        assertEquals(J_HAND, s.attachment(SWORD, offset, 0));
        // socket (0, 0.1, 0) then sword (0, 0, 0.2), relative to the hand.
        assertEquals(0, offset[3], 1e-6f);
        assertEquals(0.1f, offset[7], 1e-6f);
        assertEquals(0.2f, offset[11], 1e-6f);
        assertEquals(-1, s.attachment(BODY, offset, 0), "the body hangs off the armature");
    }

    @Test
    void layoutsMatchForTheSameRigOnly() {
        Skeleton a = Skeleton.of(TestRig.scene(), 0);
        Skeleton b = Skeleton.of(TestRig.scene(), 0);
        assertEquals(a.layout(), b.layout());
        SceneData.Builder other = new SceneData.Builder();
        other.node("hips", -1, -1, -1, -1, trs(0, 1, 0, 0, 0, 0, 1, 1));
        other.node("spine", 0, -1, -1, -1, trs(0, 1, 0, 0, 0, 0, 1, 1));
        float[] ibm = new float[32];
        ibm[0] = ibm[5] = ibm[10] = ibm[15] = ibm[16] = ibm[21] = ibm[26] = ibm[31] = 1;
        other.skin(new SceneData.Skin(-1, new int[] {0, 1}, ibm));
        assertNotEquals(a.layout(), Skeleton.of(other.build(), 0).layout());
        assertThrows(IllegalArgumentException.class, () -> Skeleton.of(other.build(), 1));
    }
}
