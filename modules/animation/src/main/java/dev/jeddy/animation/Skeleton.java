package dev.jeddy.animation;

import dev.jeddy.asset.format.SceneData;
import dev.jeddy.core.id.StrId;
import dev.jeddy.math.AffineOps;

import java.util.Arrays;

/**
 * The joints of one skin of a cooked scene (TASKS M12-02, DESIGN §13.4), in skin order: joint
 * {@code j} is what the mesh skin stream's joint index {@code j} refers to.
 *
 * <p>Each joint has a parent joint (or -1), its bind-pose local transform (translation, rotation
 * quaternion {@code xyzw}, scale), its inverse bind matrix as a row-major 3×4 affine transform, and
 * a static <em>pre-transform</em> that folds in the scene nodes between the joint and its nearest
 * joint ancestor, or the scene root for root joints (an armature node's rotation or 0.01 scale). A
 * joint's model transform, relative to the scene root, is {@code model(parent) × pre × local}; its
 * skinning matrix is {@code model × inverseBind}. Joints are evaluated in scene-node order ({@link
 * #evaluationOrder}), which puts parents first.
 *
 * <p>Joint names hash to {@link StrId}s: clips bind to joints by name, so one animation library
 * serves every skeleton of a rig. Skeletons with the same joint names and parents in the same order
 * share a {@link #layout} and can play the same clips.
 *
 * <p>Thread ownership: immutable after {@link #of}; any thread.
 */
public final class Skeleton {
    /** Floats per joint of a local pose: translation (3), rotation (4), scale (3). */
    public static final int TRS = 10;

    private final int jointCount;
    private final int[] parent;
    private final int[] node;
    private final int[] order;
    private final long[] names;
    private final String[] nameStrings;
    private final float[] bind;
    private final float[] inverseBind;
    private final float[] pre;
    private final boolean[] hasPre;
    private final int[] jointOfNode;
    private final float[] nodeLocal;
    private final int[] nodeParent;
    private final long layout;

    private Skeleton(SceneData scene, SceneData.Skin skin) {
        int[] joints = skin.joints();
        int n = joints.length;
        int nodes = scene.nodeCount();
        jointCount = n;
        parent = new int[n];
        node = Arrays.copyOf(joints, n);
        names = new long[n];
        nameStrings = new String[n];
        bind = new float[n * TRS];
        inverseBind = new float[n * AffineOps.STRIDE];
        pre = new float[n * AffineOps.STRIDE];
        hasPre = new boolean[n];
        jointOfNode = new int[nodes];
        Arrays.fill(jointOfNode, -1);
        nodeParent = new int[nodes];
        nodeLocal = new float[nodes * AffineOps.STRIDE];
        for (int i = 0; i < nodes; i++) {
            nodeParent[i] = scene.parent(i);
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
                    nodeLocal,
                    i * AffineOps.STRIDE);
        }
        for (int j = 0; j < n; j++) {
            if (joints[j] < 0 || joints[j] >= nodes) {
                throw new IllegalArgumentException("joint " + j + " names node " + joints[j]);
            }
            if (jointOfNode[joints[j]] >= 0) {
                throw new IllegalArgumentException("node " + joints[j] + " is joint twice");
            }
            jointOfNode[joints[j]] = j;
        }
        float[] ibm = skin.inverseBind();
        float[] tmp = new float[AffineOps.STRIDE];
        long hash = 0x9E3779B97F4A7C15L ^ n;
        for (int j = 0; j < n; j++) {
            int nd = joints[j];
            for (int k = 0; k < TRS; k++) {
                bind[j * TRS + k] = scene.trs(nd, k);
            }
            nameStrings[j] = scene.nodeName(nd);
            names[j] = nameStrings[j].isEmpty() ? StrId.NONE : StrId.of(nameStrings[j]);
            // glTF matrices are column-major 4×4: element (row r, column c) is m[c * 4 + r].
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 4; c++) {
                    inverseBind[j * AffineOps.STRIDE + r * 4 + c] = ibm[j * 16 + c * 4 + r];
                }
            }
            // Walk up to the nearest joint ancestor, composing the nodes in between.
            AffineOps.identity(pre, j * AffineOps.STRIDE);
            int p = scene.parent(nd);
            while (p >= 0 && jointOfNode[p] < 0) {
                AffineOps.mul(nodeLocal, p * AffineOps.STRIDE, pre, j * AffineOps.STRIDE, tmp, 0);
                System.arraycopy(tmp, 0, pre, j * AffineOps.STRIDE, AffineOps.STRIDE);
                p = scene.parent(p);
            }
            parent[j] = p < 0 ? -1 : jointOfNode[p];
            hasPre[j] = !isIdentity(pre, j * AffineOps.STRIDE);
            hash = (hash ^ names[j]) * 0x100000001B3L;
            hash = (hash ^ parent[j]) * 0x100000001B3L;
        }
        layout = hash;
        // Scene nodes come parents first, so sorting joints by node index orders parents first.
        Integer[] sorted = new Integer[n];
        for (int j = 0; j < n; j++) {
            sorted[j] = j;
        }
        Arrays.sort(sorted, (a, b) -> Integer.compare(joints[a], joints[b]));
        order = new int[n];
        for (int k = 0; k < n; k++) {
            order[k] = sorted[k];
        }
    }

    /**
     * Builds the skeleton of skin {@code skin} of {@code scene}.
     *
     * @throws IllegalArgumentException if the skin is malformed
     */
    public static Skeleton of(SceneData scene, int skin) {
        if (skin < 0 || skin >= scene.skins().size()) {
            throw new IllegalArgumentException("no skin " + skin);
        }
        return new Skeleton(scene, scene.skins().get(skin));
    }

    private static boolean isIdentity(float[] m, int o) {
        for (int k = 0; k < AffineOps.STRIDE; k++) {
            float expected = k == 0 || k == 5 || k == 10 ? 1 : 0;
            if (Math.abs(m[o + k] - expected) > 1e-6f) {
                return false;
            }
        }
        return true;
    }

    /** Returns the number of joints. */
    public int jointCount() {
        return jointCount;
    }

    /** Returns the parent joint of joint {@code j}, or -1 for a root joint. */
    public int parent(int j) {
        return parent[j];
    }

    /** Returns the scene node of joint {@code j}. */
    public int node(int j) {
        return node[j];
    }

    /** Returns the {@code k}-th joint to evaluate: parents always come before their children. */
    public int evaluationOrder(int k) {
        return order[k];
    }

    /** Returns the name id ({@link StrId}) of joint {@code j}, {@link StrId#NONE} if unnamed. */
    public long name(int j) {
        return names[j];
    }

    /** Returns the name of joint {@code j} (cold path). */
    public String nameString(int j) {
        return nameStrings[j];
    }

    /** Returns the joint called {@code name} ({@link StrId}), or -1. */
    public int find(long name) {
        if (name == StrId.NONE) {
            return -1;
        }
        for (int j = 0; j < jointCount; j++) {
            if (names[j] == name) {
                return j;
            }
        }
        return -1;
    }

    /** Returns the joint called {@code name}, or -1. */
    public int find(String name) {
        return find(name.isEmpty() ? StrId.NONE : StrId.of(name));
    }

    /** Returns the joint that scene node {@code node} is, or -1. */
    public int jointOfNode(int node) {
        return node >= 0 && node < jointOfNode.length ? jointOfNode[node] : -1;
    }

    /** Returns component {@code k} (0–9) of joint {@code j}'s bind-pose local transform. */
    public float bind(int j, int k) {
        return bind[j * TRS + k];
    }

    /**
     * Returns the hash of the joint names and parents, in order: skeletons with equal layouts play
     * the same baked clips.
     */
    public long layout() {
        return layout;
    }

    /**
     * Finds where a scene node hangs off this skeleton (an attachment: a weapon under a hand
     * joint): returns its nearest joint ancestor and writes the node's transform relative to that
     * joint (the scene nodes in between, composed with the node's own) into {@code offset[o..o +
     * 12)}. Returns -1 if no ancestor is a joint.
     */
    public int attachment(int sceneNode, float[] offset, int o) {
        float[] tmp = new float[AffineOps.STRIDE];
        System.arraycopy(nodeLocal, sceneNode * AffineOps.STRIDE, offset, o, AffineOps.STRIDE);
        int p = nodeParent[sceneNode];
        while (p >= 0 && jointOfNode[p] < 0) {
            AffineOps.mul(nodeLocal, p * AffineOps.STRIDE, offset, o, tmp, 0);
            System.arraycopy(tmp, 0, offset, o, AffineOps.STRIDE);
            p = nodeParent[p];
        }
        return p < 0 ? -1 : jointOfNode[p];
    }

    // Package-private views for the evaluator (read-only by convention).

    float[] bindPose() {
        return bind;
    }

    float[] inverseBinds() {
        return inverseBind;
    }

    float[] preTransforms() {
        return pre;
    }

    boolean hasPre(int j) {
        return hasPre[j];
    }

    int[] parents() {
        return parent;
    }

    int[] order() {
        return order;
    }
}
