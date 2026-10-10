package dev.jeddy.animation;

import dev.jeddy.asset.format.SceneData;
import dev.jeddy.math.AffineOps;

import java.util.List;

/**
 * A small rig for the tests: an "Armature" node (turned 90° about Y, scaled 2) holding a hips →
 * spine → hand chain of joints, a "socket" node under the hand holding a "sword" mesh, and a
 * skinned "body" mesh. The skin lists its joints out of node order: spine, hips, hand.
 */
final class TestRig {
    static final int ARMATURE = 0;
    static final int HIPS = 1;
    static final int SPINE = 2;
    static final int HAND = 3;
    static final int SOCKET = 4;
    static final int SWORD = 5;
    static final int BODY = 6;

    /** Skin order. */
    static final int J_SPINE = 0;

    static final int J_HIPS = 1;
    static final int J_HAND = 2;

    private TestRig() {}

    static float[] trs(
            float tx, float ty, float tz, float qx, float qy, float qz, float qw, float s) {
        return new float[] {tx, ty, tz, qx, qy, qz, qw, s, s, s};
    }

    static final float H = (float) Math.sqrt(0.5);

    /** The rig, with optional animations. */
    static SceneData scene(SceneData.Animation... animations) {
        SceneData.Builder b = new SceneData.Builder();
        b.node("Armature", -1, -1, -1, -1, trs(0, 0, 0, 0, H, 0, H, 2));
        b.node("hips", ARMATURE, -1, -1, -1, trs(0, 1, 0, 0, 0, 0, 1, 1));
        b.node("spine", HIPS, -1, -1, -1, trs(0, 0.5f, 0, 0, 0, 0, 1, 1));
        b.node("hand", SPINE, -1, -1, -1, trs(0.3f, 0, 0, 0, 0, 0, 1, 1));
        b.node("socket", HAND, -1, -1, -1, trs(0, 0.1f, 0, 0, 0, 0, 1, 1));
        int sword = b.mesh(11);
        b.node("sword", SOCKET, sword, -1, -1, trs(0, 0, 0.2f, 0, 0, 0, 1, 1));
        int body = b.mesh(12);
        int[] joints = {SPINE, HIPS, HAND};
        float[] ibm = new float[joints.length * 16];
        float[][] world = worldTransforms(b.build());
        float[] inv = new float[12];
        for (int j = 0; j < joints.length; j++) {
            AffineOps.invert(world[joints[j]], 0, inv, 0);
            AffineOps.toMat4(inv, 0, ibm, j * 16);
        }
        int skin = b.skin(new SceneData.Skin(-1, joints, ibm));
        b.node("body", ARMATURE, body, -1, skin, trs(0, 0, 0, 0, 0, 0, 1, 1));
        for (SceneData.Animation a : animations) {
            b.animation(a);
        }
        return b.build();
    }

    /** World (scene-root-relative) transforms of every node of {@code scene}, from its TRS. */
    static float[][] worldTransforms(SceneData scene) {
        float[][] world = new float[scene.nodeCount()][12];
        float[] local = new float[12];
        for (int i = 0; i < scene.nodeCount(); i++) {
            AffineOps.composeTRS(
                    scene.trs(i, 0),
                    scene.trs(i, 1),
                    scene.trs(i, 2),
                    scene.trs(i, 3),
                    scene.trs(i, 4),
                    scene.trs(i, 5),
                    scene.trs(i, 6),
                    scene.trs(i, 7),
                    scene.trs(i, 8),
                    scene.trs(i, 9),
                    local,
                    0);
            int p = scene.parent(i);
            if (p < 0) {
                System.arraycopy(local, 0, world[i], 0, 12);
            } else {
                AffineOps.mul(world[p], 0, local, 0, world[i], 0);
            }
        }
        return world;
    }

    /** A linear translation channel on {@code node} from {@code a} to {@code b} over {@code t}. */
    static SceneData.Channel move(int node, float t, float[] a, float[] b) {
        return new SceneData.Channel(
                node,
                SceneData.PATH_TRANSLATION,
                SceneData.INTERPOLATION_LINEAR,
                3,
                new float[] {0, t},
                new float[] {a[0], a[1], a[2], b[0], b[1], b[2]});
    }

    /**
     * A linear rotation channel on {@code node} about Y from 0 to {@code radians} over {@code t}.
     */
    static SceneData.Channel turn(int node, float t, float radians) {
        float h = radians / 2;
        return new SceneData.Channel(
                node,
                SceneData.PATH_ROTATION,
                SceneData.INTERPOLATION_LINEAR,
                4,
                new float[] {0, t},
                new float[] {0, 0, 0, 1, 0, (float) Math.sin(h), 0, (float) Math.cos(h)});
    }

    static SceneData.Animation animation(String name, float duration, SceneData.Channel... c) {
        return new SceneData.Animation(name, duration, List.of(c));
    }
}
