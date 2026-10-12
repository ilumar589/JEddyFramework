package dev.jeddy.cooker.gltf;

import dev.jeddy.cooker.pak.Glob;
import dev.jeddy.core.json.Json;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a {@link GltfDocument} into the cooker's import model (TASKS M6-03, DESIGN §10.3): meshes
 * as lists of triangle primitives with float vertex streams, PBR metallic-roughness materials (with
 * {@code KHR_texture_transform} and {@code KHR_materials_emissive_strength}), textures with their
 * wrap modes, the node hierarchy and {@code KHR_lights_punctual} lights. Skins and animations are
 * read as they are (joints, inverse bind matrices, keyframe streams) and stored raw in the {@code
 * .jscene} for the future animation module.
 *
 * <p>Thread ownership: stateless; any thread.
 */
public final class GltfImporter {
    private GltfImporter() {}

    /** A triangle list with float streams; optional streams are {@code null}. */
    public static final class Primitive {
        /** {@code x, y, z} per vertex. */
        public float[] positions;

        /** {@code x, y, z} per vertex. */
        public float[] normals;

        /** {@code x, y, z, w} per vertex ({@code w} = bitangent sign). */
        public float[] tangents;

        /** {@code u, v} per vertex. */
        public float[] uv0;

        /** {@code u, v} per vertex. */
        public float[] uv1;

        /** Four joint indices per vertex. */
        public int[] joints;

        /** Four weights per vertex. */
        public float[] weights;

        /** Three indices per triangle. */
        public int[] indices;

        /** glTF material index, or -1 for the default material. */
        public int material = -1;

        /** Returns the vertex count. */
        public int vertexCount() {
            return positions.length / 3;
        }
    }

    /** A glTF mesh. */
    public record Mesh(String name, List<Primitive> primitives) {}

    /** A texture reference of a material. */
    public record TextureRef(int texture, int texCoord, float scale) {}

    /** A glTF material, reduced to what {@code .jmat} stores. */
    public record Material(
            String name,
            float[] baseColor,
            float metallic,
            float roughness,
            float[] emissive,
            float emissiveStrength,
            float normalScale,
            float occlusionStrength,
            String alphaMode,
            float alphaCutoff,
            boolean doubleSided,
            float[] uvTransform,
            float uvRotation,
            TextureRef baseColorTexture,
            TextureRef metallicRoughnessTexture,
            TextureRef normalTexture,
            TextureRef occlusionTexture,
            TextureRef emissiveTexture) {}

    /** A texture: an image and its sampler's wrap modes ({@code true} = repeat). */
    public record Texture(int image, boolean wrapS, boolean wrapT) {}

    /** A node: local TRS, parent (-1 for roots), mesh, light and skin (-1 for none). */
    public record Node(
            String name,
            int parent,
            float[] translation,
            float[] rotation,
            float[] scale,
            int mesh,
            int light,
            int skin) {}

    /** A punctual light. */
    public record Light(
            String name,
            String type,
            float[] color,
            float intensity,
            float range,
            float innerCone,
            float outerCone) {}

    /** A skin: joint nodes and inverse bind matrices (column-major 4×4, identity if absent). */
    public record Skin(int skeleton, int[] joints, float[] inverseBind) {}

    /** One animation channel: target node and path, keyframe times and output values. */
    public record Channel(
            int node,
            String path,
            String interpolation,
            int components,
            float[] times,
            float[] values) {}

    /** An animation clip. */
    public record Animation(String name, List<Channel> channels) {}

    /** Everything imported from one file. */
    public record Scene(
            List<Mesh> meshes,
            List<Material> materials,
            List<Texture> textures,
            List<Node> nodes,
            List<Light> lights,
            List<Skin> skins,
            List<Animation> animations,
            List<String> warnings) {}

    /** Imports {@code doc} with nothing excluded. */
    public static Scene importScene(GltfDocument doc) throws IOException {
        return importScene(doc, List.of());
    }

    /**
     * Imports {@code doc}. The nodes whose names match a pattern in {@code excludeNodes} (globs,
     * case-insensitive; the {@code "excludeNodes"} import setting) and every node under them lose
     * their mesh, light and skin, so neither the cooked scene nor the collision and navmesh bakes
     * see them, while node indices stay valid for skins and animations. This is how a kit piece
     * sheds a part the level does not want: the demo's dungeon kit builds its doorway with a closed
     * door leaf as a child node, and the crypt wants open archways (D104).
     */
    public static Scene importScene(GltfDocument doc, List<String> excludeNodes)
            throws IOException {
        List<String> warnings = new ArrayList<>();
        List<Mesh> meshes = new ArrayList<>();
        for (int m = 0; m < doc.list("meshes").size(); m++) {
            meshes.add(mesh(doc, m, warnings));
        }
        List<Material> materials = new ArrayList<>();
        for (int i = 0; i < doc.list("materials").size(); i++) {
            materials.add(material(doc, i));
        }
        List<Texture> textures = new ArrayList<>();
        for (int i = 0; i < doc.list("textures").size(); i++) {
            Map<String, Object> t = doc.get("textures", i);
            int sampler = Json.integer(t, "sampler", -1);
            boolean ws = true;
            boolean wt = true;
            if (sampler >= 0) {
                Map<String, Object> s = doc.get("samplers", sampler);
                ws = Json.integer(s, "wrapS", 10497) != 33071; // CLAMP_TO_EDGE
                wt = Json.integer(s, "wrapT", 10497) != 33071;
            }
            textures.add(new Texture(Json.integer(t, "source", 0), ws, wt));
        }
        List<Light> lights = new ArrayList<>();
        Map<String, Object> ext = optObject(doc.json(), "extensions");
        Map<String, Object> punctual = ext == null ? null : optObject(ext, "KHR_lights_punctual");
        if (punctual != null) {
            for (Object o : Json.asArray(punctual.getOrDefault("lights", List.of()), "lights")) {
                Map<String, Object> l = GltfDocument.object(o, "light");
                Map<String, Object> spot = optObject(l, "spot");
                lights.add(
                        new Light(
                                Json.string(l, "name", "light"),
                                Json.string(l, "type", "point"),
                                floats(l, "color", new float[] {1, 1, 1}),
                                (float) Json.number(l, "intensity", 1),
                                (float) Json.number(l, "range", 0),
                                spot == null ? 0 : (float) Json.number(spot, "innerConeAngle", 0),
                                spot == null
                                        ? (float) (Math.PI / 4)
                                        : (float)
                                                Json.number(spot, "outerConeAngle", Math.PI / 4)));
            }
        }
        List<Node> nodes = detach(nodes(doc), excludeNodes, warnings);
        List<Skin> skins = new ArrayList<>();
        for (int i = 0; i < doc.list("skins").size(); i++) {
            Map<String, Object> skin = doc.get("skins", i);
            int[] joints = intArray(skin.get("joints"));
            float[] ibm;
            if (skin.containsKey("inverseBindMatrices")) {
                ibm = doc.floats(Json.integer(skin, "inverseBindMatrices", 0));
                if (ibm.length != joints.length * 16) {
                    throw new IOException(
                            doc.file()
                                    + ": skin "
                                    + i
                                    + " has the wrong inverse bind matrix count");
                }
            } else {
                ibm = new float[joints.length * 16];
                for (int j = 0; j < joints.length; j++) {
                    ibm[j * 16] = ibm[j * 16 + 5] = ibm[j * 16 + 10] = ibm[j * 16 + 15] = 1;
                }
            }
            skins.add(new Skin(Json.integer(skin, "skeleton", -1), joints, ibm));
        }
        List<Animation> animations = new ArrayList<>();
        for (int i = 0; i < doc.list("animations").size(); i++) {
            animations.add(animation(doc, i, warnings));
        }
        return new Scene(meshes, materials, textures, nodes, lights, skins, animations, warnings);
    }

    private static Animation animation(GltfDocument doc, int i, List<String> warnings)
            throws IOException {
        Map<String, Object> anim = doc.get("animations", i);
        String name = Json.string(anim, "name", "animation" + i);
        List<Object> samplers = Json.asArray(anim.getOrDefault("samplers", List.of()), "samplers");
        List<Channel> channels = new ArrayList<>();
        for (Object o : Json.asArray(anim.getOrDefault("channels", List.of()), "channels")) {
            Map<String, Object> ch = GltfDocument.object(o, "channel");
            Map<String, Object> target = GltfDocument.object(ch.get("target"), "target");
            if (!target.containsKey("node")) {
                warnings.add(name + ": a channel without a target node is skipped");
                continue;
            }
            Map<String, Object> sampler =
                    GltfDocument.object(samplers.get(Json.integer(ch, "sampler", 0)), "sampler");
            int input = Json.integer(sampler, "input", 0);
            int output = Json.integer(sampler, "output", 0);
            String interpolation = Json.string(sampler, "interpolation", "LINEAR");
            float[] times = doc.floats(input);
            float[] values = doc.floats(output);
            int perKey = interpolation.equals("CUBICSPLINE") ? 3 : 1;
            int components =
                    times.length == 0
                            ? doc.components(output)
                            : values.length / (times.length * perKey);
            if (components <= 0 || values.length != times.length * perKey * components) {
                throw new IOException(doc.file() + ": " + name + " has mismatched sampler sizes");
            }
            channels.add(
                    new Channel(
                            Json.integer(target, "node", 0),
                            Json.string(target, "path", "translation"),
                            interpolation,
                            components,
                            times,
                            values));
        }
        return new Animation(name, channels);
    }

    private static int[] intArray(Object v) {
        List<Object> list = Json.asArray(v, "array");
        int[] out = new int[list.size()];
        for (int k = 0; k < out.length; k++) {
            out[k] = ((Number) list.get(k)).intValue();
        }
        return out;
    }

    private static Mesh mesh(GltfDocument doc, int m, List<String> warnings) throws IOException {
        Map<String, Object> mesh = doc.get("meshes", m);
        String name = Json.string(mesh, "name", "mesh" + m);
        List<Primitive> out = new ArrayList<>();
        List<Object> prims = Json.asArray(mesh.get("primitives"), "primitives");
        for (int p = 0; p < prims.size(); p++) {
            Map<String, Object> prim = GltfDocument.object(prims.get(p), "primitive");
            int mode = Json.integer(prim, "mode", 4);
            if (mode != 4) {
                warnings.add(
                        name + " primitive " + p + ": mode " + mode + " is not triangles, skipped");
                continue;
            }
            Map<String, Object> attributes =
                    GltfDocument.object(prim.get("attributes"), "attributes");
            if (!attributes.containsKey("POSITION")) {
                warnings.add(name + " primitive " + p + " has no POSITION, skipped");
                continue;
            }
            Primitive pr = new Primitive();
            pr.positions = doc.floats(Json.integer(attributes, "POSITION", 0));
            pr.normals = attribute(doc, attributes, "NORMAL");
            pr.tangents = attribute(doc, attributes, "TANGENT");
            pr.uv0 = attribute(doc, attributes, "TEXCOORD_0");
            pr.uv1 = attribute(doc, attributes, "TEXCOORD_1");
            if (attributes.containsKey("JOINTS_0")) {
                pr.joints = doc.ints(Json.integer(attributes, "JOINTS_0", 0));
                pr.weights = attribute(doc, attributes, "WEIGHTS_0");
            }
            int vertices = pr.vertexCount();
            if (prim.containsKey("indices")) {
                pr.indices = doc.ints(Json.integer(prim, "indices", 0));
            } else {
                pr.indices = new int[vertices];
                for (int i = 0; i < vertices; i++) {
                    pr.indices[i] = i;
                }
            }
            if (pr.indices.length % 3 != 0) {
                warnings.add(
                        name + " primitive " + p + ": index count is not a multiple of 3, skipped");
                continue;
            }
            for (int index : pr.indices) {
                if (index < 0 || index >= vertices) {
                    throw new IOException(
                            doc.file() + ": " + name + " has an index outside its vertices");
                }
            }
            pr.material = Json.integer(prim, "material", -1);
            out.add(pr);
        }
        return new Mesh(name, out);
    }

    private static float[] attribute(GltfDocument doc, Map<String, Object> attributes, String name)
            throws IOException {
        return attributes.containsKey(name) ? doc.floats(Json.integer(attributes, name, 0)) : null;
    }

    private static Material material(GltfDocument doc, int i) {
        Map<String, Object> m = doc.get("materials", i);
        Map<String, Object> pbr = optObject(m, "pbrMetallicRoughness");
        Map<String, Object> ext = optObject(m, "extensions");
        float strength = 1;
        if (ext != null && optObject(ext, "KHR_materials_emissive_strength") != null) {
            strength =
                    (float)
                            Json.number(
                                    optObject(ext, "KHR_materials_emissive_strength"),
                                    "emissiveStrength",
                                    1);
        }
        TextureRef base = pbr == null ? null : texture(pbr, "baseColorTexture");
        float[] uv = {1, 1, 0, 0};
        float rotation = 0;
        Map<String, Object> baseInfo = pbr == null ? null : optObject(pbr, "baseColorTexture");
        Map<String, Object> baseExt = baseInfo == null ? null : optObject(baseInfo, "extensions");
        Map<String, Object> transform =
                baseExt == null ? null : optObject(baseExt, "KHR_texture_transform");
        if (transform != null) {
            float[] scale = floats(transform, "scale", new float[] {1, 1});
            float[] offset = floats(transform, "offset", new float[] {0, 0});
            uv = new float[] {scale[0], scale[1], offset[0], offset[1]};
            rotation = (float) Json.number(transform, "rotation", 0);
        }
        Map<String, Object> normal = optObject(m, "normalTexture");
        Map<String, Object> occlusion = optObject(m, "occlusionTexture");
        return new Material(
                Json.string(m, "name", "material" + i),
                pbr == null
                        ? new float[] {1, 1, 1, 1}
                        : floats(pbr, "baseColorFactor", new float[] {1, 1, 1, 1}),
                pbr == null ? 1 : (float) Json.number(pbr, "metallicFactor", 1),
                pbr == null ? 1 : (float) Json.number(pbr, "roughnessFactor", 1),
                floats(m, "emissiveFactor", new float[] {0, 0, 0}),
                strength,
                normal == null ? 1 : (float) Json.number(normal, "scale", 1),
                occlusion == null ? 1 : (float) Json.number(occlusion, "strength", 1),
                Json.string(m, "alphaMode", "OPAQUE"),
                (float) Json.number(m, "alphaCutoff", 0.5),
                Json.bool(m, "doubleSided", false),
                uv,
                rotation,
                base,
                pbr == null ? null : texture(pbr, "metallicRoughnessTexture"),
                texture(m, "normalTexture"),
                texture(m, "occlusionTexture"),
                texture(m, "emissiveTexture"));
    }

    private static TextureRef texture(Map<String, Object> owner, String key) {
        Map<String, Object> t = optObject(owner, key);
        if (t == null) {
            return null;
        }
        return new TextureRef(
                Json.integer(t, "index", 0),
                Json.integer(t, "texCoord", 0),
                (float) Json.number(t, "scale", 1));
    }

    private static List<Node> nodes(GltfDocument doc) {
        int n = doc.list("nodes").size();
        int[] parent = new int[n];
        java.util.Arrays.fill(parent, -1);
        for (int i = 0; i < n; i++) {
            Object children = doc.get("nodes", i).get("children");
            if (children != null) {
                for (Object c : Json.asArray(children, "children")) {
                    parent[((Number) c).intValue()] = i;
                }
            }
        }
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Object> node = doc.get("nodes", i);
            float[] t = floats(node, "translation", new float[] {0, 0, 0});
            float[] r = floats(node, "rotation", new float[] {0, 0, 0, 1});
            float[] s = floats(node, "scale", new float[] {1, 1, 1});
            if (node.containsKey("matrix")) {
                float[] m = floats(node, "matrix", null);
                decompose(m, t, r, s);
            }
            Map<String, Object> ext = optObject(node, "extensions");
            Map<String, Object> light = ext == null ? null : optObject(ext, "KHR_lights_punctual");
            nodes.add(
                    new Node(
                            Json.string(node, "name", "node" + i),
                            parent[i],
                            t,
                            r,
                            s,
                            Json.integer(node, "mesh", -1),
                            light == null ? -1 : Json.integer(light, "light", -1),
                            Json.integer(node, "skin", -1)));
        }
        return nodes;
    }

    /**
     * Returns {@code nodes} with the subtrees rooted at nodes matching {@code patterns} detached
     * (mesh, light and skin set to none); a pattern that matches nothing is reported.
     */
    static List<Node> detach(List<Node> nodes, List<String> patterns, List<String> warnings) {
        if (patterns.isEmpty()) {
            return nodes;
        }
        boolean[] excluded = new boolean[nodes.size()];
        for (String pattern : patterns) {
            Glob glob = Glob.of(pattern);
            boolean matched = false;
            for (int i = 0; i < nodes.size(); i++) {
                if (glob.matches(nodes.get(i).name().toLowerCase(Locale.ROOT))) {
                    excluded[i] = true;
                    matched = true;
                }
            }
            if (!matched) {
                warnings.add("excludeNodes: no node matches \"" + pattern + "\"");
            }
        }
        // Parents precede children in glTF order? Not guaranteed: propagate until stable.
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < nodes.size(); i++) {
                int parent = nodes.get(i).parent();
                if (!excluded[i] && parent >= 0 && excluded[parent]) {
                    excluded[i] = true;
                    changed = true;
                }
            }
        }
        List<Node> out = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            out.add(
                    excluded[i]
                            ? new Node(
                                    n.name(),
                                    n.parent(),
                                    n.translation(),
                                    n.rotation(),
                                    n.scale(),
                                    -1,
                                    -1,
                                    -1)
                            : n);
        }
        return out;
    }

    /** Splits a column-major affine matrix into translation, rotation (quaternion) and scale. */
    static void decompose(float[] m, float[] t, float[] r, float[] s) {
        t[0] = m[12];
        t[1] = m[13];
        t[2] = m[14];
        s[0] = (float) Math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]);
        s[1] = (float) Math.sqrt(m[4] * m[4] + m[5] * m[5] + m[6] * m[6]);
        s[2] = (float) Math.sqrt(m[8] * m[8] + m[9] * m[9] + m[10] * m[10]);
        double det =
                m[0] * (m[5] * m[10] - m[9] * m[6])
                        - m[4] * (m[1] * m[10] - m[9] * m[2])
                        + m[8] * (m[1] * m[6] - m[5] * m[2]);
        if (det < 0) {
            s[0] = -s[0];
        }
        double r00 = m[0] / s[0], r10 = m[1] / s[0], r20 = m[2] / s[0];
        double r01 = m[4] / s[1], r11 = m[5] / s[1], r21 = m[6] / s[1];
        double r02 = m[8] / s[2], r12 = m[9] / s[2], r22 = m[10] / s[2];
        double trace = r00 + r11 + r22;
        double x, y, z, w;
        if (trace > 0) {
            double k = Math.sqrt(trace + 1) * 2;
            w = 0.25 * k;
            x = (r21 - r12) / k;
            y = (r02 - r20) / k;
            z = (r10 - r01) / k;
        } else if (r00 > r11 && r00 > r22) {
            double k = Math.sqrt(1 + r00 - r11 - r22) * 2;
            w = (r21 - r12) / k;
            x = 0.25 * k;
            y = (r01 + r10) / k;
            z = (r02 + r20) / k;
        } else if (r11 > r22) {
            double k = Math.sqrt(1 + r11 - r00 - r22) * 2;
            w = (r02 - r20) / k;
            x = (r01 + r10) / k;
            y = 0.25 * k;
            z = (r12 + r21) / k;
        } else {
            double k = Math.sqrt(1 + r22 - r00 - r11) * 2;
            w = (r10 - r01) / k;
            x = (r02 + r20) / k;
            y = (r12 + r21) / k;
            z = 0.25 * k;
        }
        r[0] = (float) x;
        r[1] = (float) y;
        r[2] = (float) z;
        r[3] = (float) w;
    }

    private static Map<String, Object> optObject(Map<String, Object> owner, String key) {
        Object v = owner.get(key);
        return v == null ? null : GltfDocument.object(v, key);
    }

    private static float[] floats(Map<String, Object> owner, String key, float[] fallback) {
        Object v = owner.get(key);
        if (v == null) {
            return fallback == null ? null : fallback.clone();
        }
        List<Object> list = Json.asArray(v, key);
        float[] out = new float[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = ((Number) list.get(i)).floatValue();
        }
        return out;
    }
}
