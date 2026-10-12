package dev.jeddy.cooker.gltf;

import dev.jeddy.asset.AssetPaths;
import dev.jeddy.asset.format.MaterialData;
import dev.jeddy.asset.format.SceneData;
import dev.jeddy.cooker.CookContext;
import dev.jeddy.cooker.CopyImporter;
import dev.jeddy.cooker.ImportSettings;
import dev.jeddy.cooker.Importer;
import dev.jeddy.cooker.gltf.GltfImporter.Animation;
import dev.jeddy.cooker.gltf.GltfImporter.Channel;
import dev.jeddy.cooker.gltf.GltfImporter.Material;
import dev.jeddy.cooker.gltf.GltfImporter.Mesh;
import dev.jeddy.cooker.gltf.GltfImporter.Node;
import dev.jeddy.cooker.gltf.GltfImporter.Primitive;
import dev.jeddy.cooker.gltf.GltfImporter.Scene;
import dev.jeddy.cooker.gltf.GltfImporter.Skin;
import dev.jeddy.cooker.gltf.GltfImporter.TextureRef;
import dev.jeddy.cooker.mesh.MeshProcessor;
import dev.jeddy.cooker.texture.ImageDecoder;
import dev.jeddy.cooker.texture.ImageRgba;
import dev.jeddy.cooker.texture.MipGenerator;
import dev.jeddy.cooker.texture.TextureCooker;
import dev.jeddy.cooker.texture.TextureCooker.Kind;
import dev.jeddy.core.json.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Cooks a glTF 2.0 file ({@code .gltf} or {@code .glb}) into a scene (TASKS M6-03, M6-04, M6-06):
 *
 * <ul>
 *   <li>{@code <base>.jscene}: the nodes reachable from the default scene, parents first, with
 *       lights, and skins and animations kept raw;
 *   <li>{@code <base>/<mesh>.jmesh} per mesh ({@link MeshProcessor}), one submesh per primitive;
 *   <li>{@code <base>/<material>.jmat} per material, with derived shader features;
 *   <li>{@code <base>/<image>_<usage>.ktx2} per image and usage: base color and emissive as BC7
 *       sRGB, normal maps as BC5, occlusion + roughness + metallic packed into one BC7 ORM texture
 *       (or occlusion on its own as BC4 when it reads another UV set).
 * </ul>
 *
 * External buffers and images are dependencies of the glTF and are not cooked on their own ({@link
 * #claims}). Settings: {@code lods}, {@code lodRatio}, {@code lodError}, {@code tangents} (meshes),
 * {@code maxSize}, {@code uastcLevel}, {@code rdo}, {@code zstd} (textures), {@code scene} (index
 * of the glTF scene to cook).
 *
 * <p>Thread ownership: stateless; any thread. Meshes and textures of one file cook in parallel on
 * the context's job system.
 */
public final class GltfSceneImporter implements Importer {
    @Override
    public String name() {
        return "gltf";
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public boolean accepts(String path, ImportSettings settings) {
        String ext = CopyImporter.extension(path);
        return ext.equals("gltf") || ext.equals("glb");
    }

    @Override
    public List<String> claims(Path root, String path, ImportSettings settings) throws IOException {
        Path file = root.resolve(path);
        List<String> out = new ArrayList<>();
        for (Path p : GltfDocument.externalFiles(file, Files.readAllBytes(file))) {
            Path abs = p.toAbsolutePath().normalize();
            if (abs.startsWith(root)) {
                out.add(root.relativize(abs).toString().replace('\\', '/'));
            }
        }
        return out;
    }

    /** A texture to cook: one usage of one image (two images for ORM). */
    private record TextureJob(
            String output,
            Kind kind,
            boolean orm,
            int imageA,
            int imageB,
            boolean wrap,
            float alphaCutoff) {}

    @Override
    public void cook(CookContext ctx) throws Exception {
        GltfDocument doc = GltfDocument.read(ctx.file(), ctx.bytes(), ctx::readDependency);
        Scene scene =
                GltfImporter.importScene(doc, ctx.settings().strings("excludeNodes", List.of()));
        for (String w : scene.warnings()) {
            ctx.warn(w);
        }
        String base = ctx.outputBase();
        Names names = new Names(base);
        ImportSettings s = ctx.settings();

        // Materials and the textures they use.
        Map<String, TextureJob> textures = new LinkedHashMap<>();
        List<String> materialPaths = new ArrayList<>();
        boolean needsDefault = false;
        for (Mesh m : scene.meshes()) {
            for (Primitive p : m.primitives()) {
                needsDefault |= p.material < 0 || p.material >= scene.materials().size();
            }
        }
        for (int i = 0; i < scene.materials().size(); i++) {
            Material mat = scene.materials().get(i);
            String path = names.unique(mat.name(), ".jmat");
            materialPaths.add(path);
            ctx.output(path, material(ctx, doc, scene, mat, names, textures).toBytes());
        }
        String defaultMaterial = null;
        if (needsDefault) {
            defaultMaterial = names.unique("default", ".jmat");
            ctx.output(
                    defaultMaterial, new MaterialData.Builder().deriveFeatures().build().toBytes());
        }

        // Meshes, in parallel.
        MeshProcessor.Options mo =
                new MeshProcessor.Options(
                        s.integer("lods", MeshProcessor.Options.DEFAULT.lods()),
                        s.number("lodRatio", MeshProcessor.Options.DEFAULT.lodRatio()),
                        s.number("lodError", MeshProcessor.Options.DEFAULT.lodError()),
                        s.bool("tangents", MeshProcessor.Options.DEFAULT.tangents()),
                        s.number(
                                "overdrawThreshold",
                                MeshProcessor.Options.DEFAULT.overdrawThreshold()));
        List<Mesh> meshes = scene.meshes();
        String[] meshPaths = new String[meshes.size()];
        for (int i = 0; i < meshes.size(); i++) {
            meshPaths[i] =
                    meshes.get(i).primitives().isEmpty()
                            ? null
                            : names.unique(meshes.get(i).name(), ".jmesh");
        }
        MeshProcessor.Result[] meshResults = new MeshProcessor.Result[meshes.size()];
        final String defaultMat = defaultMaterial;
        ctx.jobs()
                .parallelFor(
                        (start, end, thread) -> {
                            for (int i = start; i < end; i++) {
                                if (meshPaths[i] != null) {
                                    meshResults[i] =
                                            cookMesh(meshes.get(i), materialPaths, defaultMat, mo);
                                }
                            }
                        },
                        meshes.size(),
                        1);
        List<Object> meshStats = new ArrayList<>();
        for (int i = 0; i < meshes.size(); i++) {
            if (meshResults[i] == null) {
                continue;
            }
            ctx.output(meshPaths[i], meshResults[i].jmesh());
            MeshProcessor.Stats st = meshResults[i].stats();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("mesh", meshPaths[i]);
            m.put("vertices", st.vertices());
            m.put("triangles", st.triangles());
            List<Object> lods = new ArrayList<>();
            for (int l = 0; l < st.lodTriangles().length; l++) {
                lods.add(List.of(st.lodTriangles()[l], (double) st.lodErrors()[l]));
            }
            m.put("lods", lods);
            m.put("acmr", round(st.acmr()));
            m.put("atvr", round(st.atvr()));
            m.put("overdraw", round(st.overdraw()));
            meshStats.add(m);
        }

        // Textures, in parallel.
        List<TextureJob> jobs = new ArrayList<>(textures.values());
        TextureCooker.Cooked[] cooked = new TextureCooker.Cooked[jobs.size()];
        TextureCooker.Options to = TextureCooker.Options.defaults(Kind.COLOR).with(s);
        ctx.jobs()
                .parallelFor(
                        (start, end, thread) -> {
                            for (int i = start; i < end; i++) {
                                try {
                                    cooked[i] = cookTexture(doc, jobs.get(i), to, ctx.threads());
                                } catch (IOException e) {
                                    throw new UncheckedIOException(
                                            jobs.get(i).output() + ": " + e.getMessage(), e);
                                }
                            }
                        },
                        jobs.size(),
                        1);
        List<Object> textureStats = new ArrayList<>();
        for (int i = 0; i < jobs.size(); i++) {
            ctx.output(jobs.get(i).output(), cooked[i].ktx2());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("texture", jobs.get(i).output());
            m.putAll(cooked[i].stats());
            textureStats.add(m);
            if (jobs.get(i).kind() == Kind.COLOR
                    && cooked[i].stats().get("psnr") instanceof Double psnr
                    && psnr < 40) {
                ctx.warn(
                        String.format(
                                Locale.ROOT, "%s: low PSNR %.1f dB", jobs.get(i).output(), psnr));
            }
        }

        // The scene.
        SceneData sceneData = scene(ctx, doc, scene, meshPaths);
        ctx.output(base + ".jscene", sceneData.toBytes());
        ctx.stat("nodes", sceneData.nodeCount());
        ctx.stat("lights", sceneData.lights().size());
        ctx.stat("skins", sceneData.skins().size());
        ctx.stat("animations", sceneData.animations().size());
        ctx.stat("materials", materialPaths.size() + (defaultMaterial != null ? 1 : 0));
        ctx.stat("meshes", meshStats);
        ctx.stat("textures", textureStats);
    }

    private static double round(float v) {
        return Math.round(v * 1000) / 1000.0;
    }

    private static MeshProcessor.Result cookMesh(
            Mesh mesh,
            List<String> materialPaths,
            String defaultMaterial,
            MeshProcessor.Options o) {
        List<Integer> slotMaterials = new ArrayList<>();
        int[] slots = new int[mesh.primitives().size()];
        for (int p = 0; p < slots.length; p++) {
            int m = mesh.primitives().get(p).material;
            int key = m < 0 || m >= materialPaths.size() ? -1 : m;
            int slot = slotMaterials.indexOf(key);
            if (slot < 0) {
                slotMaterials.add(key);
                slot = slotMaterials.size() - 1;
            }
            slots[p] = slot;
        }
        long[] ids = new long[slotMaterials.size()];
        for (int i = 0; i < ids.length; i++) {
            int m = slotMaterials.get(i);
            ids[i] = AssetPaths.id(m < 0 ? defaultMaterial : materialPaths.get(m));
        }
        return MeshProcessor.process(mesh.primitives(), slots, ids, o);
    }

    private static MaterialData material(
            CookContext ctx,
            GltfDocument doc,
            Scene scene,
            Material m,
            Names names,
            Map<String, TextureJob> textures) {
        MaterialData.Builder b = new MaterialData.Builder();
        b.baseColor(
                m.baseColor()[0],
                m.baseColor()[1],
                m.baseColor()[2],
                m.baseColor().length > 3 ? m.baseColor()[3] : 1);
        b.emissive(m.emissive()[0], m.emissive()[1], m.emissive()[2], m.emissiveStrength());
        b.metallicRoughness(m.metallic(), m.roughness());
        b.normalOcclusion(m.normalScale(), m.occlusionStrength());
        float[] uv = m.uvTransform();
        b.uvTransform(uv[0], uv[1], uv[2], uv[3], m.uvRotation());
        int alpha =
                switch (m.alphaMode()) {
                    case "MASK" -> MaterialData.ALPHA_MASK;
                    case "BLEND" -> MaterialData.ALPHA_BLEND;
                    default -> MaterialData.ALPHA_OPAQUE;
                };
        b.alpha(alpha, m.alphaCutoff());
        int flags =
                MaterialData.FLAG_CASTS_SHADOWS
                        | (m.doubleSided() ? MaterialData.FLAG_DOUBLE_SIDED : 0);
        b.flags(flags);

        float cutoff = alpha == MaterialData.ALPHA_MASK ? m.alphaCutoff() : -1;
        texture(
                ctx,
                doc,
                scene,
                m.baseColorTexture(),
                -1,
                Kind.COLOR,
                "basecolor",
                cutoff,
                MaterialData.SLOT_BASE_COLOR,
                b,
                names,
                textures);
        texture(
                ctx,
                doc,
                scene,
                m.normalTexture(),
                -1,
                Kind.NORMAL,
                "normal",
                -1,
                MaterialData.SLOT_NORMAL,
                b,
                names,
                textures);
        texture(
                ctx,
                doc,
                scene,
                m.emissiveTexture(),
                -1,
                Kind.COLOR,
                "emissive",
                -1,
                MaterialData.SLOT_EMISSIVE,
                b,
                names,
                textures);
        TextureRef mr = m.metallicRoughnessTexture();
        TextureRef occ = m.occlusionTexture();
        if (mr != null && occ != null && mr.texCoord() != occ.texCoord()) {
            texture(
                    ctx,
                    doc,
                    scene,
                    mr,
                    -2,
                    Kind.LINEAR,
                    "orm",
                    -1,
                    MaterialData.SLOT_ORM,
                    b,
                    names,
                    textures);
            texture(
                    ctx,
                    doc,
                    scene,
                    occ,
                    -1,
                    Kind.MASK,
                    "occlusion",
                    -1,
                    MaterialData.SLOT_OCCLUSION,
                    b,
                    names,
                    textures);
        } else if (mr != null || occ != null) {
            // Pack: R occlusion, G roughness, B metallic. imageB carries the occlusion image.
            TextureRef primary = mr != null ? mr : occ;
            int occImage = occ == null ? -2 : image(scene, occ);
            texture(
                    ctx,
                    doc,
                    scene,
                    primary,
                    mr == null ? -3 : occImage,
                    Kind.LINEAR,
                    "orm",
                    -1,
                    MaterialData.SLOT_ORM,
                    b,
                    names,
                    textures);
        }
        return b.deriveFeatures().build();
    }

    private static int image(Scene scene, TextureRef ref) {
        if (ref.texture() < 0 || ref.texture() >= scene.textures().size()) {
            return -1;
        }
        return scene.textures().get(ref.texture()).image();
    }

    /**
     * Adds one texture slot. For ORM, {@code second} is the occlusion image (-2: none, -3: the
     * reference itself is the occlusion map and there is no metallic-roughness map).
     */
    private static void texture(
            CookContext ctx,
            GltfDocument doc,
            Scene scene,
            TextureRef ref,
            int second,
            Kind kind,
            String usage,
            float alphaCutoff,
            int slot,
            MaterialData.Builder b,
            Names names,
            Map<String, TextureJob> textures) {
        if (ref == null) {
            return;
        }
        int img = image(scene, ref);
        if (img < 0 || img >= doc.list("images").size()) {
            ctx.warn(
                    "texture "
                            + ref.texture()
                            + " has no supported image; slot "
                            + usage
                            + " left empty");
            return;
        }
        boolean wrap =
                scene.textures().get(ref.texture()).wrapS()
                        && scene.textures().get(ref.texture()).wrapT();
        int texFlags = 0;
        if (ref.texCoord() == 1) {
            texFlags = MaterialData.TEXTURE_UV1;
        } else if (ref.texCoord() > 1) {
            ctx.warn(
                    usage
                            + " texture uses TEXCOORD_"
                            + ref.texCoord()
                            + "; only 0 and 1 are kept, using 0");
        }
        String key = usage + ":" + img + ":" + second + ":" + wrap + ":" + alphaCutoff;
        TextureJob job = textures.get(key);
        if (job == null) {
            String image = doc.imageName(img);
            // "wood_normal" used as a normal map stays "wood_normal", not "wood_normal_normal".
            String stem =
                    image.toLowerCase(Locale.ROOT).endsWith("_" + usage)
                            ? image
                            : image + "_" + usage;
            job =
                    new TextureJob(
                            names.unique(stem, ".ktx2"),
                            kind,
                            usage.equals("orm"),
                            img,
                            second,
                            wrap,
                            alphaCutoff);
            textures.put(key, job);
        }
        b.texture(slot, AssetPaths.id(job.output()), texFlags);
    }

    private static TextureCooker.Cooked cookTexture(
            GltfDocument doc, TextureJob job, TextureCooker.Options base, int threads)
            throws IOException {
        TextureCooker.Options o =
                base.withKind(job.kind()).withWrap(job.wrap()).withAlphaCutoff(job.alphaCutoff());
        GltfDocument.Image a = doc.image(job.imageA());
        ImageRgba image = ImageDecoder.decode(a.bytes(), name(a), TextureCooker.srgb(job.kind()));
        if (job.orm()) {
            image = orm(doc, image, job.imageA(), job.imageB());
        }
        return TextureCooker.cook(image, o, threads);
    }

    private static String name(GltfDocument.Image img) {
        return switch (img.mimeType()) {
            case "image/png" -> img.name() + ".png";
            case "image/jpeg" -> img.name() + ".jpg";
            default -> img.name();
        };
    }

    /**
     * Packs occlusion into R of a metallic-roughness image (G roughness, B metallic, as glTF stores
     * them). {@code occlusion}: -2 none (R = 1), -3 {@code mr} is the occlusion map itself (G = B =
     * 1), otherwise the occlusion image (resampled to the same size).
     */
    static ImageRgba orm(GltfDocument doc, ImageRgba mr, int mrImage, int occlusion)
            throws IOException {
        float[] p = mr.pixels();
        int n = mr.width() * mr.height();
        if (occlusion == -2) {
            for (int i = 0; i < n; i++) {
                p[i * 4] = 1;
                p[i * 4 + 3] = 1;
            }
            return mr;
        }
        if (occlusion == -3) {
            for (int i = 0; i < n; i++) {
                p[i * 4 + 1] = 1;
                p[i * 4 + 2] = 1;
                p[i * 4 + 3] = 1;
            }
            return mr;
        }
        if (occlusion != mrImage) {
            GltfDocument.Image o = doc.image(occlusion);
            ImageRgba occ =
                    resize(ImageDecoder.decode(o.bytes(), name(o), false), mr.width(), mr.height());
            float[] q = occ.pixels();
            for (int i = 0; i < n; i++) {
                p[i * 4] = q[i * 4];
            }
        }
        for (int i = 0; i < n; i++) {
            p[i * 4 + 3] = 1;
        }
        return mr;
    }

    /** Resizes with the mip filter when shrinking and bilinear filtering when growing. */
    static ImageRgba resize(ImageRgba src, int w, int h) {
        if (src.width() == w && src.height() == h) {
            return src;
        }
        if (src.width() >= w && src.height() >= h) {
            return MipGenerator.downsample(src, w, h, false);
        }
        ImageRgba out = new ImageRgba(w, h);
        for (int y = 0; y < h; y++) {
            float sy = Math.max(0, (y + 0.5f) * src.height() / h - 0.5f);
            int y0 = Math.min((int) sy, src.height() - 1);
            int y1 = Math.min(y0 + 1, src.height() - 1);
            float fy = sy - y0;
            for (int x = 0; x < w; x++) {
                float sx = Math.max(0, (x + 0.5f) * src.width() / w - 0.5f);
                int x0 = Math.min((int) sx, src.width() - 1);
                int x1 = Math.min(x0 + 1, src.width() - 1);
                float fx = sx - x0;
                for (int c = 0; c < 4; c++) {
                    float top = src.get(x0, y0, c) * (1 - fx) + src.get(x1, y0, c) * fx;
                    float bottom = src.get(x0, y1, c) * (1 - fx) + src.get(x1, y1, c) * fx;
                    out.set(x, y, c, top * (1 - fy) + bottom * fy);
                }
            }
        }
        return out;
    }

    private static SceneData scene(
            CookContext ctx, GltfDocument doc, Scene scene, String[] meshPaths) {
        List<Node> nodes = scene.nodes();
        List<Integer> roots = new ArrayList<>();
        List<Object> scenes = doc.list("scenes");
        if (!scenes.isEmpty()) {
            int index = ctx.settings().integer("scene", Json.integer(doc.json(), "scene", 0));
            if (index < 0 || index >= scenes.size()) {
                throw new IllegalArgumentException("scene " + index + " does not exist");
            }
            Object list = Json.asObject(scenes.get(index), "scene").get("nodes");
            if (list != null) {
                for (Object o : Json.asArray(list, "nodes")) {
                    roots.add(((Number) o).intValue());
                }
            }
        } else {
            for (int i = 0; i < nodes.size(); i++) {
                if (nodes.get(i).parent() < 0) {
                    roots.add(i);
                }
            }
        }
        // Parents first: depth-first from the roots.
        List<List<Integer>> children = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            children.add(new ArrayList<>());
        }
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).parent() >= 0) {
                children.get(nodes.get(i).parent()).add(i);
            }
        }
        int[] remap = new int[nodes.size()];
        java.util.Arrays.fill(remap, -1);
        List<Integer> order = new ArrayList<>();
        List<Integer> stack = new ArrayList<>();
        for (int r = roots.size() - 1; r >= 0; r--) {
            stack.add(roots.get(r));
        }
        while (!stack.isEmpty()) {
            int n = stack.remove(stack.size() - 1);
            if (n < 0 || n >= nodes.size() || remap[n] >= 0) {
                continue;
            }
            remap[n] = order.size();
            order.add(n);
            List<Integer> c = children.get(n);
            for (int k = c.size() - 1; k >= 0; k--) {
                stack.add(c.get(k));
            }
        }
        if (order.size() < nodes.size()) {
            ctx.warn(
                    (nodes.size() - order.size())
                            + " nodes are not in the cooked scene and were left out");
        }
        SceneData.Builder b = new SceneData.Builder();
        int[] lightIndex = new int[scene.lights().size()];
        for (int l = 0; l < lightIndex.length; l++) {
            GltfImporter.Light light = scene.lights().get(l);
            int type =
                    switch (light.type()) {
                        case "directional" -> SceneData.LIGHT_DIRECTIONAL;
                        case "spot" -> SceneData.LIGHT_SPOT;
                        default -> SceneData.LIGHT_POINT;
                    };
            float[] c = light.color();
            lightIndex[l] =
                    b.light(
                            new SceneData.Light(
                                    light.name(),
                                    type,
                                    c[0],
                                    c[1],
                                    c[2],
                                    light.intensity(),
                                    light.range(),
                                    light.innerCone(),
                                    light.outerCone()));
        }
        int[] skinIndex = new int[scene.skins().size()];
        for (int s = 0; s < skinIndex.length; s++) {
            Skin skin = scene.skins().get(s);
            int[] joints = new int[skin.joints().length];
            boolean ok = true;
            for (int j = 0; j < joints.length; j++) {
                int src = skin.joints()[j];
                joints[j] = src >= 0 && src < remap.length ? remap[src] : -1;
                ok &= joints[j] >= 0;
            }
            if (!ok) {
                ctx.warn("skin " + s + " has joints outside the cooked scene and was left out");
                skinIndex[s] = -1;
                continue;
            }
            int skeleton =
                    skin.skeleton() >= 0 && skin.skeleton() < remap.length
                            ? remap[skin.skeleton()]
                            : -1;
            skinIndex[s] = b.skin(new SceneData.Skin(skeleton, joints, skin.inverseBind()));
        }
        Set<Integer> missingMeshes = new HashSet<>();
        for (int n : order) {
            Node node = nodes.get(n);
            int parent = node.parent() >= 0 ? remap[node.parent()] : -1;
            int mesh = -1;
            if (node.mesh() >= 0) {
                if (node.mesh() < meshPaths.length && meshPaths[node.mesh()] != null) {
                    mesh = b.mesh(AssetPaths.id(meshPaths[node.mesh()]));
                } else {
                    missingMeshes.add(node.mesh());
                }
            }
            int light =
                    node.light() >= 0 && node.light() < lightIndex.length
                            ? lightIndex[node.light()]
                            : -1;
            int skin =
                    node.skin() >= 0 && node.skin() < skinIndex.length
                            ? skinIndex[node.skin()]
                            : -1;
            float[] trs = new float[10];
            System.arraycopy(node.translation(), 0, trs, 0, 3);
            System.arraycopy(node.rotation(), 0, trs, 3, 4);
            System.arraycopy(node.scale(), 0, trs, 7, 3);
            b.node(node.name(), parent, mesh, light, skin, trs);
        }
        if (!missingMeshes.isEmpty()) {
            ctx.warn("nodes refer to meshes without triangles: " + missingMeshes);
        }
        for (Animation a : scene.animations()) {
            List<SceneData.Channel> channels = new ArrayList<>();
            float duration = 0;
            for (Channel c : a.channels()) {
                int node = c.node() >= 0 && c.node() < remap.length ? remap[c.node()] : -1;
                if (node < 0) {
                    continue;
                }
                int path =
                        switch (c.path()) {
                            case "translation" -> SceneData.PATH_TRANSLATION;
                            case "rotation" -> SceneData.PATH_ROTATION;
                            case "scale" -> SceneData.PATH_SCALE;
                            default -> SceneData.PATH_WEIGHTS;
                        };
                int interpolation =
                        switch (c.interpolation()) {
                            case "STEP" -> SceneData.INTERPOLATION_STEP;
                            case "CUBICSPLINE" -> SceneData.INTERPOLATION_CUBIC;
                            default -> SceneData.INTERPOLATION_LINEAR;
                        };
                for (float t : c.times()) {
                    duration = Math.max(duration, t);
                }
                channels.add(
                        new SceneData.Channel(
                                node, path, interpolation, c.components(), c.times(), c.values()));
            }
            b.animation(new SceneData.Animation(a.name(), duration, channels));
        }
        return b.build();
    }

    /** Unique, sanitized output paths under the file's base directory. */
    private static final class Names {
        private final String base;
        private final Set<String> used = new HashSet<>();

        Names(String base) {
            this.base = base;
        }

        String unique(String name, String extension) {
            String stem =
                    CookContext.sanitize(name == null || name.isBlank() ? "unnamed" : name)
                            .replace('/', '_')
                            .toLowerCase(Locale.ROOT);
            if (stem.startsWith(".")) {
                stem = "_" + stem.substring(1);
            }
            String candidate = stem;
            for (int i = 2; !used.add(candidate + extension); i++) {
                candidate = stem + "_" + i;
            }
            return base + "/" + candidate + extension;
        }
    }
}
