package dev.jeddy.asset.format;

import static dev.jeddy.asset.format.Bytes.F32;
import static dev.jeddy.asset.format.Bytes.U16;
import static dev.jeddy.asset.format.Bytes.U32;
import static dev.jeddy.asset.format.Bytes.U64;
import static dev.jeddy.asset.format.Bytes.align;
import static dev.jeddy.asset.format.Bytes.checkRange;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A cooked scene ({@code .jscene}, {@code docs/formats/jscene.md}): a node hierarchy with local
 * TRS, mesh and light references, punctual lights, and skins and animations kept raw for the future
 * animation module (DESIGN §10.1, §16). Nodes are stored parents first, so a loader can spawn them
 * in file order.
 *
 * <p>Thread ownership: immutable after {@link #read} or {@link Builder#build}; any thread.
 */
public final class SceneData {
    /** {@code "JSCN"} in file order. */
    public static final int MAGIC = 0x4E43534A;

    /** Format version. */
    public static final int VERSION = 1;

    /** Header bytes. */
    public static final int HEADER_SIZE = 96;

    /** Bytes per node record. */
    public static final int NODE_SIZE = 64;

    /** Bytes per light record. */
    public static final int LIGHT_SIZE = 48;

    /** Light types. */
    public static final int LIGHT_DIRECTIONAL = 0;

    /** Point light. */
    public static final int LIGHT_POINT = 1;

    /** Spot light. */
    public static final int LIGHT_SPOT = 2;

    /** Animation channel paths. */
    public static final int PATH_TRANSLATION = 0;

    /** Rotation channel (quaternion x, y, z, w). */
    public static final int PATH_ROTATION = 1;

    /** Scale channel. */
    public static final int PATH_SCALE = 2;

    /** Morph-target weights channel. */
    public static final int PATH_WEIGHTS = 3;

    /** Interpolation modes. */
    public static final int INTERPOLATION_LINEAR = 0;

    /** Step interpolation. */
    public static final int INTERPOLATION_STEP = 1;

    /** Cubic spline interpolation (in-tangent, value, out-tangent per key). */
    public static final int INTERPOLATION_CUBIC = 2;

    /** A punctual light ({@code KHR_lights_punctual} units: lux or candela). */
    public record Light(
            String name,
            int type,
            float r,
            float g,
            float b,
            float intensity,
            float range,
            float innerCone,
            float outerCone) {}

    /** A skin: joint nodes and their inverse bind matrices (column-major 4×4 each). */
    public record Skin(int skeleton, int[] joints, float[] inverseBind) {}

    /** One animated property of one node. */
    public record Channel(
            int node, int path, int interpolation, int components, float[] times, float[] values) {}

    /** A named animation clip. */
    public record Animation(String name, float duration, List<Channel> channels) {}

    private final String[] names;
    private final int[] parent;
    private final int[] mesh;
    private final int[] light;
    private final int[] skin;
    private final float[] trs;
    private final long[] meshIds;
    private final List<Light> lights;
    private final List<Skin> skins;
    private final List<Animation> animations;

    private SceneData(Builder b) {
        int n = b.nodeCount;
        names = Arrays.copyOf(b.names, n);
        parent = Arrays.copyOf(b.parent, n);
        mesh = Arrays.copyOf(b.mesh, n);
        light = Arrays.copyOf(b.light, n);
        skin = Arrays.copyOf(b.skin, n);
        trs = Arrays.copyOf(b.trs, n * 10);
        meshIds = b.meshIds.stream().mapToLong(Long::longValue).toArray();
        lights = List.copyOf(b.lights);
        skins = List.copyOf(b.skins);
        animations = List.copyOf(b.animations);
    }

    /** Returns the node count. */
    public int nodeCount() {
        return parent.length;
    }

    /** Returns the name of node {@code i} (may be empty). */
    public String nodeName(int i) {
        return names[i];
    }

    /** Returns the parent of node {@code i} (always a smaller index), or -1 for roots. */
    public int parent(int i) {
        return parent[i];
    }

    /** Returns the index into {@link #meshId} of node {@code i}'s mesh, or -1. */
    public int mesh(int i) {
        return mesh[i];
    }

    /** Returns the index into {@link #lights()} of node {@code i}'s light, or -1. */
    public int light(int i) {
        return light[i];
    }

    /** Returns the index into {@link #skins()} of node {@code i}'s skin, or -1. */
    public int skin(int i) {
        return skin[i];
    }

    /** Returns translation ({@code k} 0..2), rotation (3..6, xyzw) or scale (7..9) of node i. */
    public float trs(int i, int k) {
        return trs[i * 10 + k];
    }

    /** Returns the number of distinct meshes the nodes reference. */
    public int meshCount() {
        return meshIds.length;
    }

    /** Returns the {@code StrId} of mesh {@code m}'s {@code .jmesh} path. */
    public long meshId(int m) {
        return meshIds[m];
    }

    /** Returns the lights. */
    public List<Light> lights() {
        return lights;
    }

    /** Returns the skins (raw). */
    public List<Skin> skins() {
        return skins;
    }

    /** Returns the animations (raw). */
    public List<Animation> animations() {
        return animations;
    }

    /**
     * Parses a {@code .jscene}. The result copies what it needs.
     *
     * @throws AssetFormatException if the file is malformed
     */
    public static SceneData read(MemorySegment file) throws AssetFormatException {
        checkRange(file, 0, HEADER_SIZE, ".jscene header");
        if (file.get(U32, 0) != MAGIC) {
            throw new AssetFormatException("not a .jscene file");
        }
        int version = Short.toUnsignedInt(file.get(U16, 4));
        if (version != VERSION) {
            throw new AssetFormatException(".jscene version " + version + ", expected " + VERSION);
        }
        int nodes = count(file, 8, "node");
        int meshes = count(file, 12, "mesh");
        int lightCount = count(file, 16, "light");
        int skinCount = count(file, 20, "skin");
        int animationCount = count(file, 24, "animation");
        long nodesOffset = file.get(U64, 32);
        long meshesOffset = file.get(U64, 40);
        long lightsOffset = file.get(U64, 48);
        long skinsOffset = file.get(U64, 56);
        long animationsOffset = file.get(U64, 64);
        long stringsOffset = file.get(U64, 72);
        long stringBytes = file.get(U64, 80);
        if (file.get(U64, 88) != file.byteSize()) {
            throw new AssetFormatException(".jscene size does not match its header");
        }
        checkRange(file, nodesOffset, (long) NODE_SIZE * nodes, ".jscene nodes");
        checkRange(file, meshesOffset, 8L * meshes, ".jscene meshes");
        checkRange(file, lightsOffset, (long) LIGHT_SIZE * lightCount, ".jscene lights");
        checkRange(file, skinsOffset, 16L * skinCount, ".jscene skins");
        checkRange(file, animationsOffset, 32L * animationCount, ".jscene animations");
        checkRange(file, stringsOffset, stringBytes, ".jscene strings");
        MemorySegment strings = file.asSlice(stringsOffset, stringBytes);

        Builder b = new Builder();
        for (int m = 0; m < meshes; m++) {
            b.meshIds.add(file.get(U64, meshesOffset + 8L * m));
        }
        for (int l = 0; l < lightCount; l++) {
            long at = lightsOffset + (long) LIGHT_SIZE * l;
            int type = file.get(U32, at);
            if (type < LIGHT_DIRECTIONAL || type > LIGHT_SPOT) {
                throw new AssetFormatException(".jscene light type " + type);
            }
            b.lights.add(
                    new Light(
                            string(strings, file.get(U32, at + 32), file.get(U32, at + 36)),
                            type,
                            file.get(F32, at + 4),
                            file.get(F32, at + 8),
                            file.get(F32, at + 12),
                            file.get(F32, at + 16),
                            file.get(F32, at + 20),
                            file.get(F32, at + 24),
                            file.get(F32, at + 28)));
        }
        for (int s = 0; s < skinCount; s++) {
            long at = skinsOffset + 16L * s;
            int skeleton = file.get(U32, at);
            int joints = file.get(U32, at + 4);
            long data = file.get(U64, at + 8);
            if (joints < 0 || joints > 1 << 16) {
                throw new AssetFormatException(".jscene skin has " + joints + " joints");
            }
            checkRange(file, data, 68L * joints, ".jscene skin data");
            int[] j = new int[joints];
            for (int k = 0; k < joints; k++) {
                j[k] = file.get(U32, data + 4L * k);
                if (j[k] < 0 || j[k] >= nodes) {
                    throw new AssetFormatException(".jscene skin joint " + j[k] + " is not a node");
                }
            }
            float[] ibm = new float[joints * 16];
            MemorySegment.copy(file, F32, data + 4L * joints, ibm, 0, ibm.length);
            if (skeleton < -1 || skeleton >= nodes) {
                throw new AssetFormatException(".jscene skin skeleton " + skeleton);
            }
            b.skins.add(new Skin(skeleton, j, ibm));
        }
        for (int a = 0; a < animationCount; a++) {
            long at = animationsOffset + 32L * a;
            String name = string(strings, file.get(U32, at), file.get(U32, at + 4));
            int channels = file.get(U32, at + 8);
            float duration = file.get(F32, at + 12);
            long channelsOffset = file.get(U64, at + 16);
            if (channels < 0 || channels > 1 << 20) {
                throw new AssetFormatException(".jscene animation has " + channels + " channels");
            }
            checkRange(file, channelsOffset, 32L * channels, ".jscene channels");
            List<Channel> list = new ArrayList<>(channels);
            for (int c = 0; c < channels; c++) {
                long ch = channelsOffset + 32L * c;
                int node = file.get(U32, ch);
                int path = file.get(U32, ch + 4);
                int interpolation = file.get(U32, ch + 8);
                int keys = file.get(U32, ch + 12);
                int components = file.get(U32, ch + 16);
                long data = file.get(U64, ch + 24);
                if (node < 0
                        || node >= nodes
                        || path < 0
                        || path > PATH_WEIGHTS
                        || interpolation < 0
                        || interpolation > INTERPOLATION_CUBIC
                        || keys < 0
                        || components <= 0
                        || components > 64) {
                    throw new AssetFormatException(".jscene channel " + c + " is malformed");
                }
                long values =
                        (long) keys * components * (interpolation == INTERPOLATION_CUBIC ? 3 : 1);
                checkRange(file, data, 4L * (keys + values), ".jscene channel data");
                float[] times = new float[keys];
                float[] out = new float[(int) values];
                MemorySegment.copy(file, F32, data, times, 0, keys);
                MemorySegment.copy(file, F32, data + 4L * keys, out, 0, out.length);
                list.add(new Channel(node, path, interpolation, components, times, out));
            }
            b.animations.add(new Animation(name, duration, list));
        }
        for (int i = 0; i < nodes; i++) {
            long at = nodesOffset + (long) NODE_SIZE * i;
            int p = file.get(U32, at + 8);
            int m = file.get(U32, at + 12);
            int l = file.get(U32, at + 16);
            int s = file.get(U32, at + 20);
            if (p < -1 || p >= i) {
                throw new AssetFormatException(".jscene node " + i + " has parent " + p);
            }
            if (m < -1 || m >= meshes || l < -1 || l >= lightCount || s < -1 || s >= skinCount) {
                throw new AssetFormatException(".jscene node " + i + " has a bad reference");
            }
            float[] t = new float[10];
            MemorySegment.copy(file, F32, at + 24, t, 0, 10);
            b.node(string(strings, file.get(U32, at), file.get(U32, at + 4)), p, m, l, s, t);
        }
        return new SceneData(b);
    }

    private static int count(MemorySegment file, long at, String what) throws AssetFormatException {
        int n = file.get(U32, at);
        if (n < 0 || n > 1 << 24) {
            throw new AssetFormatException(".jscene has " + n + " " + what + "s");
        }
        return n;
    }

    private static String string(MemorySegment strings, int offset, int length)
            throws AssetFormatException {
        checkRange(
                strings,
                Integer.toUnsignedLong(offset),
                Integer.toUnsignedLong(length),
                ".jscene string");
        byte[] utf8 = strings.asSlice(offset, length).toArray(ValueLayout.JAVA_BYTE);
        return new String(utf8, StandardCharsets.UTF_8);
    }

    /** Encodes the scene. */
    public byte[] toBytes() {
        ByteArrayOutputStream strings = new ByteArrayOutputStream();
        int nodes = nodeCount();
        long nodesOffset = HEADER_SIZE;
        long meshesOffset = align(nodesOffset + (long) NODE_SIZE * nodes, 16);
        long lightsOffset = align(meshesOffset + 8L * meshIds.length, 16);
        long skinsOffset = align(lightsOffset + (long) LIGHT_SIZE * lights.size(), 16);
        long animationsOffset = align(skinsOffset + 16L * skins.size(), 16);
        long cursor = align(animationsOffset + 32L * animations.size(), 16);
        long[] skinData = new long[skins.size()];
        for (int s = 0; s < skins.size(); s++) {
            skinData[s] = cursor;
            cursor = align(cursor + 68L * skins.get(s).joints().length, 16);
        }
        long[] channelTables = new long[animations.size()];
        List<long[]> channelData = new ArrayList<>();
        for (int a = 0; a < animations.size(); a++) {
            channelTables[a] = cursor;
            List<Channel> channels = animations.get(a).channels();
            cursor = align(cursor + 32L * channels.size(), 16);
            long[] offsets = new long[channels.size()];
            for (int c = 0; c < channels.size(); c++) {
                offsets[c] = cursor;
                Channel ch = channels.get(c);
                cursor = align(cursor + 4L * (ch.times().length + ch.values().length), 16);
            }
            channelData.add(offsets);
        }
        long stringsOffset = cursor;
        // Strings first, so their size is known.
        int[][] nodeNames = new int[nodes][];
        for (int i = 0; i < nodes; i++) {
            nodeNames[i] = put(strings, names[i]);
        }
        int[][] lightNames = new int[lights.size()][];
        for (int l = 0; l < lights.size(); l++) {
            lightNames[l] = put(strings, lights.get(l).name());
        }
        int[][] animNames = new int[animations.size()][];
        for (int a = 0; a < animations.size(); a++) {
            animNames[a] = put(strings, animations.get(a).name());
        }
        byte[] stringBytes = strings.toByteArray();
        long size = stringsOffset + stringBytes.length;
        if (size > Integer.MAX_VALUE) {
            throw new IllegalStateException(".jscene larger than 2 GiB");
        }
        ByteBuffer b = ByteBuffer.allocate((int) size).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0, MAGIC);
        b.putShort(4, (short) VERSION);
        b.putInt(8, nodes);
        b.putInt(12, meshIds.length);
        b.putInt(16, lights.size());
        b.putInt(20, skins.size());
        b.putInt(24, animations.size());
        b.putLong(32, nodesOffset);
        b.putLong(40, meshesOffset);
        b.putLong(48, lightsOffset);
        b.putLong(56, skinsOffset);
        b.putLong(64, animationsOffset);
        b.putLong(72, stringsOffset);
        b.putLong(80, stringBytes.length);
        b.putLong(88, size);
        for (int i = 0; i < nodes; i++) {
            int at = (int) (nodesOffset + (long) NODE_SIZE * i);
            b.putInt(at, nodeNames[i][0]);
            b.putInt(at + 4, nodeNames[i][1]);
            b.putInt(at + 8, parent[i]);
            b.putInt(at + 12, mesh[i]);
            b.putInt(at + 16, light[i]);
            b.putInt(at + 20, skin[i]);
            for (int k = 0; k < 10; k++) {
                b.putFloat(at + 24 + 4 * k, trs[i * 10 + k]);
            }
        }
        for (int m = 0; m < meshIds.length; m++) {
            b.putLong((int) (meshesOffset + 8L * m), meshIds[m]);
        }
        for (int l = 0; l < lights.size(); l++) {
            Light light = lights.get(l);
            int at = (int) (lightsOffset + (long) LIGHT_SIZE * l);
            b.putInt(at, light.type());
            b.putFloat(at + 4, light.r());
            b.putFloat(at + 8, light.g());
            b.putFloat(at + 12, light.b());
            b.putFloat(at + 16, light.intensity());
            b.putFloat(at + 20, light.range());
            b.putFloat(at + 24, light.innerCone());
            b.putFloat(at + 28, light.outerCone());
            b.putInt(at + 32, lightNames[l][0]);
            b.putInt(at + 36, lightNames[l][1]);
        }
        for (int s = 0; s < skins.size(); s++) {
            Skin skin = skins.get(s);
            int at = (int) (skinsOffset + 16L * s);
            b.putInt(at, skin.skeleton());
            b.putInt(at + 4, skin.joints().length);
            b.putLong(at + 8, skinData[s]);
            int d = (int) skinData[s];
            for (int j : skin.joints()) {
                b.putInt(d, j);
                d += 4;
            }
            for (float f : skin.inverseBind()) {
                b.putFloat(d, f);
                d += 4;
            }
        }
        for (int a = 0; a < animations.size(); a++) {
            Animation anim = animations.get(a);
            int at = (int) (animationsOffset + 32L * a);
            b.putInt(at, animNames[a][0]);
            b.putInt(at + 4, animNames[a][1]);
            b.putInt(at + 8, anim.channels().size());
            b.putFloat(at + 12, anim.duration());
            b.putLong(at + 16, channelTables[a]);
            for (int c = 0; c < anim.channels().size(); c++) {
                Channel ch = anim.channels().get(c);
                int ct = (int) (channelTables[a] + 32L * c);
                long data = channelData.get(a)[c];
                b.putInt(ct, ch.node());
                b.putInt(ct + 4, ch.path());
                b.putInt(ct + 8, ch.interpolation());
                b.putInt(ct + 12, ch.times().length);
                b.putInt(ct + 16, ch.components());
                b.putLong(ct + 24, data);
                int d = (int) data;
                for (float f : ch.times()) {
                    b.putFloat(d, f);
                    d += 4;
                }
                for (float f : ch.values()) {
                    b.putFloat(d, f);
                    d += 4;
                }
            }
        }
        b.put((int) stringsOffset, stringBytes);
        return b.array();
    }

    private static int[] put(ByteArrayOutputStream strings, String s) {
        byte[] utf8 = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        int offset = strings.size();
        strings.writeBytes(utf8);
        return new int[] {offset, utf8.length};
    }

    /** Builds scenes (cooker, tests). */
    public static final class Builder {
        String[] names = new String[16];
        int[] parent = new int[16];
        int[] mesh = new int[16];
        int[] light = new int[16];
        int[] skin = new int[16];
        float[] trs = new float[160];
        int nodeCount;
        final List<Long> meshIds = new ArrayList<>();
        final List<Light> lights = new ArrayList<>();
        final List<Skin> skins = new ArrayList<>();
        final List<Animation> animations = new ArrayList<>();

        /** Creates an empty builder. */
        public Builder() {}

        /**
         * Adds a node; returns its index.
         *
         * @param parent parent index (must already exist) or -1
         * @param trs translation (3), rotation quaternion xyzw (4), scale (3)
         */
        public int node(String name, int parent, int mesh, int light, int skin, float[] trs) {
            if (parent < -1 || parent >= nodeCount) {
                throw new IllegalArgumentException("parents must be added before their children");
            }
            if (trs.length != 10) {
                throw new IllegalArgumentException("trs needs 10 floats");
            }
            if (nodeCount == this.parent.length) {
                int n = nodeCount * 2;
                names = Arrays.copyOf(names, n);
                this.parent = Arrays.copyOf(this.parent, n);
                this.mesh = Arrays.copyOf(this.mesh, n);
                this.light = Arrays.copyOf(this.light, n);
                this.skin = Arrays.copyOf(this.skin, n);
                this.trs = Arrays.copyOf(this.trs, n * 10);
            }
            int i = nodeCount++;
            names[i] = name == null ? "" : name;
            this.parent[i] = parent;
            this.mesh[i] = mesh;
            this.light[i] = light;
            this.skin[i] = skin;
            System.arraycopy(trs, 0, this.trs, i * 10, 10);
            return i;
        }

        /** Adds (or finds) a mesh reference by {@code StrId}; returns its index. */
        public int mesh(long id) {
            int i = meshIds.indexOf(id);
            if (i >= 0) {
                return i;
            }
            meshIds.add(id);
            return meshIds.size() - 1;
        }

        /** Adds a light; returns its index. */
        public int light(Light light) {
            lights.add(light);
            return lights.size() - 1;
        }

        /** Adds a skin; returns its index. */
        public int skin(Skin skin) {
            if (skin.inverseBind().length != skin.joints().length * 16) {
                throw new IllegalArgumentException("one 4×4 inverse bind matrix per joint");
            }
            skins.add(skin);
            return skins.size() - 1;
        }

        /** Adds an animation. */
        public Builder animation(Animation animation) {
            for (Channel c : animation.channels()) {
                int per = c.components() * (c.interpolation() == INTERPOLATION_CUBIC ? 3 : 1);
                if (c.values().length != c.times().length * per) {
                    throw new IllegalArgumentException("channel values do not match its keys");
                }
            }
            animations.add(animation);
            return this;
        }

        /** Builds the scene; references are checked when it is read back. */
        public SceneData build() {
            return new SceneData(this);
        }
    }
}
