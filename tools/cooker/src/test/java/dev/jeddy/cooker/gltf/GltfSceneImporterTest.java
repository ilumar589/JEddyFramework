package dev.jeddy.cooker.gltf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.AssetPaths;
import dev.jeddy.asset.format.Ktx2;
import dev.jeddy.asset.format.MaterialData;
import dev.jeddy.asset.format.MeshData;
import dev.jeddy.asset.format.SceneData;
import dev.jeddy.cooker.CookReport;
import dev.jeddy.cooker.CookReport.Status;
import dev.jeddy.cooker.Cooker;
import dev.jeddy.cooker.CookerConfig;
import dev.jeddy.cooker.TestImages;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** glTF to .jscene/.jmesh/.jmat/.ktx2 through the cooker (TASKS M6-03, M6-04, M6-06). */
class GltfSceneImporterTest {
    @TempDir Path dir;

    private static MemorySegment read(Path p) throws IOException {
        return MemorySegment.ofArray(Files.readAllBytes(p));
    }

    /** A cube with two primitives, PBR textures, a hierarchy, a light, a skin and an animation. */
    private static GltfBuilder crate(boolean externalImages) {
        float[][] c = GltfBuilder.cube();
        GltfBuilder b = new GltfBuilder();
        int pos = b.floats(c[0], "VEC3");
        int nrm = b.floats(c[1], "VEC3");
        int uv = b.floats(c[2], "VEC2");
        int[] all = GltfBuilder.toInts(c[3]);
        int first = b.indices(java.util.Arrays.copyOfRange(all, 0, 18));
        int second = b.indices(java.util.Arrays.copyOfRange(all, 18, 36));
        Map<String, Object> attrs = Map.of("POSITION", pos, "NORMAL", nrm, "TEXCOORD_0", uv);
        b.add(
                "meshes",
                Map.of(
                        "name",
                        "Crate Body",
                        "primitives",
                        List.of(
                                Map.of("attributes", attrs, "indices", first, "material", 0),
                                Map.of("attributes", attrs, "indices", second))));
        byte[][] images = {
            TestImages.painted(32, 1),
            TestImages.normalMap(32),
            TestImages.painted(32, 4),
            TestImages.painted(16, 7)
        };
        String[] names = {"wood", "wood_normal", "wood_mr", "wood_ao"};
        for (int i = 0; i < images.length; i++) {
            if (externalImages) {
                b.add("images", Map.of("uri", "textures/" + names[i] + ".png"));
            } else {
                b.add(
                        "images",
                        Map.of(
                                "bufferView",
                                b.view(images[i], 0),
                                "mimeType",
                                "image/png",
                                "name",
                                names[i]));
            }
            b.add("textures", Map.of("source", i));
        }
        Map<String, Object> pbr = new LinkedHashMap<>();
        pbr.put("baseColorTexture", Map.of("index", 0));
        pbr.put("metallicRoughnessTexture", Map.of("index", 2));
        pbr.put("metallicFactor", 0.0);
        Map<String, Object> mat = new LinkedHashMap<>();
        mat.put("name", "Wood");
        mat.put("pbrMetallicRoughness", pbr);
        mat.put("normalTexture", Map.of("index", 1));
        mat.put("occlusionTexture", Map.of("index", 3));
        b.add("materials", mat);
        // A second material reusing the base color (dedupe) and one alpha-tested (own texture).
        b.add(
                "materials",
                Map.of(
                        "name",
                        "Wood copy",
                        "pbrMetallicRoughness",
                        Map.of("baseColorTexture", Map.of("index", 0))));
        b.add(
                "materials",
                Map.of(
                        "name",
                        "Leaves",
                        "alphaMode",
                        "MASK",
                        "pbrMetallicRoughness",
                        Map.of("baseColorTexture", Map.of("index", 0))));
        b.add(
                "nodes",
                Map.of(
                        "name",
                        "root",
                        "children",
                        List.of(1, 2),
                        "translation",
                        List.of(0.0, 1.0, 0.0)));
        b.add("nodes", Map.of("name", "body", "mesh", 0, "skin", 0));
        b.add(
                "nodes",
                Map.of(
                        "name",
                        "lamp",
                        "extensions",
                        Map.of("KHR_lights_punctual", Map.of("light", 0))));
        b.add("nodes", Map.of("name", "unused"));
        b.add("scenes", Map.of("nodes", List.of(0)));
        b.root.put("scene", 0);
        b.root.put(
                "extensions",
                Map.of(
                        "KHR_lights_punctual",
                        Map.of("lights", List.of(Map.of("type", "point", "intensity", 5.0)))));
        b.add("skins", Map.of("joints", List.of(0, 1)));
        int times = b.floats(new float[] {0, 1, 2}, "SCALAR");
        int values = b.floats(new float[] {0, 0, 0, 0, 1, 0, 0, 0, 0}, "VEC3");
        b.add(
                "animations",
                Map.of(
                        "name", "bob",
                        "samplers", List.of(Map.of("input", times, "output", values)),
                        "channels",
                                List.of(
                                        Map.of(
                                                "sampler",
                                                0,
                                                "target",
                                                Map.of("node", 1, "path", "translation")))));
        return b;
    }

    @Test
    void cooksAnimationLibrariesWithoutMeshes() throws Exception {
        // A rig and its clips only (TASKS M12-06): what tools/kaykit.py writes per rig.
        GltfBuilder b = new GltfBuilder();
        b.add("nodes", Map.of("name", "root", "children", List.of(1)));
        b.add("nodes", Map.of("name", "hips", "translation", List.of(0.0, 1.0, 0.0)));
        b.add("scenes", Map.of("nodes", List.of(0)));
        b.root.put("scene", 0);
        int times = b.floats(new float[] {0, 0.5f, 1}, "SCALAR");
        int one = b.floats(new float[] {0}, "SCALAR");
        int bob = b.floats(new float[] {0, 1, 0, 0, 1.2f, 0, 0, 1, 0}, "VEC3");
        int turn = b.floats(new float[] {0, 0, 0, 1}, "VEC4");
        b.add(
                "animations",
                Map.of(
                        "name", "Walking_A",
                        "samplers",
                                List.of(
                                        Map.of("input", times, "output", bob),
                                        Map.of("input", one, "output", turn)),
                        "channels",
                                List.of(
                                        Map.of(
                                                "sampler",
                                                0,
                                                "target",
                                                Map.of("node", 1, "path", "translation")),
                                        Map.of(
                                                "sampler",
                                                1,
                                                "target",
                                                Map.of("node", 1, "path", "rotation")))));
        Path src = dir.resolve("assets");
        Path out = dir.resolve("cooked");
        Files.createDirectories(src.resolve("anims"));
        b.writeGlb(src.resolve("anims/heroes.glb"));
        try (Cooker c = new Cooker(CookerConfig.of(src, out).withThreads(2))) {
            CookReport r = c.cookAll();
            CookReport.Item item = r.item("anims/heroes.glb");
            assertEquals(Status.COOKED, item.status(), r.toJson());
            assertEquals(List.of("anims/heroes.jscene"), item.outputs());
            SceneData scene = SceneData.read(read(out.resolve("anims/heroes.jscene")));
            assertEquals(2, scene.nodeCount());
            assertEquals(0, scene.meshCount());
            assertEquals("hips", scene.nodeName(1));
            SceneData.Animation walk = scene.animations().get(0);
            assertEquals("Walking_A", walk.name());
            assertEquals(1f, walk.duration());
            assertEquals(2, walk.channels().size());
            assertEquals(1, walk.channels().get(1).times().length, "a single-key channel");
        }
    }

    @Test
    void cooksAGlbIntoSceneMeshesMaterialsAndTextures() throws Exception {
        Path src = dir.resolve("assets");
        Path out = dir.resolve("cooked");
        Files.createDirectories(src.resolve("models"));
        crate(false).writeGlb(src.resolve("models/Crate.glb"));
        try (Cooker c = new Cooker(CookerConfig.of(src, out).withThreads(4))) {
            CookReport r = c.cookAll();
            CookReport.Item item = r.item("models/Crate.glb");
            assertEquals(Status.COOKED, item.status(), r.toJson());
            assertTrue(
                    item.warnings().stream().anyMatch(w -> w.contains("not in the cooked scene")),
                    item.warnings().toString());
            Set<String> outputs = Set.copyOf(item.outputs());
            assertEquals(
                    Set.of(
                            "models/crate.jscene",
                            "models/crate/crate_body.jmesh",
                            "models/crate/wood.jmat",
                            "models/crate/wood_copy.jmat",
                            "models/crate/leaves.jmat",
                            "models/crate/default.jmat",
                            "models/crate/wood_basecolor.ktx2",
                            "models/crate/wood_basecolor_2.ktx2",
                            "models/crate/wood_normal.ktx2",
                            "models/crate/wood_mr_orm.ktx2"),
                    outputs);

            SceneData scene = SceneData.read(read(out.resolve("models/crate.jscene")));
            assertEquals(3, scene.nodeCount());
            assertEquals("root", scene.nodeName(0));
            assertEquals(1f, scene.trs(0, 1));
            assertEquals(0, scene.parent(1));
            assertEquals(
                    AssetPaths.id("models/crate/crate_body.jmesh"), scene.meshId(scene.mesh(1)));
            assertEquals(0, scene.light(2));
            assertEquals(SceneData.LIGHT_POINT, scene.lights().get(0).type());
            assertEquals(0, scene.skin(1));
            assertEquals(2, scene.skins().get(0).joints().length);
            assertEquals(2f, scene.animations().get(0).duration());
            assertEquals(1, scene.animations().get(0).channels().get(0).node());

            MeshData mesh = MeshData.read(read(out.resolve("models/crate/crate_body.jmesh")));
            assertEquals(2, mesh.submeshCount());
            assertEquals(
                    AssetPaths.id("models/crate/wood.jmat"),
                    mesh.materialId(mesh.submeshMaterial(0)));
            assertEquals(
                    AssetPaths.id("models/crate/default.jmat"),
                    mesh.materialId(mesh.submeshMaterial(1)));

            MaterialData wood = MaterialData.read(read(out.resolve("models/crate/wood.jmat")));
            assertEquals(
                    MaterialData.FEATURE_BASE_COLOR_MAP
                            | MaterialData.FEATURE_NORMAL_MAP
                            | MaterialData.FEATURE_ORM_MAP,
                    wood.featureMask());
            assertEquals(0f, wood.metallic());
            assertEquals(
                    AssetPaths.id("models/crate/wood_mr_orm.ktx2"),
                    wood.textureId(wood.findTexture(MaterialData.SLOT_ORM)));
            MaterialData copy = MaterialData.read(read(out.resolve("models/crate/wood_copy.jmat")));
            assertEquals(
                    wood.textureId(wood.findTexture(0)),
                    copy.textureId(copy.findTexture(0)),
                    "same image, same usage: one texture");
            MaterialData leaves = MaterialData.read(read(out.resolve("models/crate/leaves.jmat")));
            assertEquals(MaterialData.ALPHA_MASK, leaves.alphaMode());
            assertTrue((leaves.featureMask() & MaterialData.FEATURE_ALPHA_MASK) != 0);

            assertEquals(
                    Ktx2.VK_FORMAT_BC7_SRGB_BLOCK,
                    Ktx2.read(read(out.resolve("models/crate/wood_basecolor.ktx2"))).vkFormat());
            assertEquals(
                    Ktx2.VK_FORMAT_BC5_UNORM_BLOCK,
                    Ktx2.read(read(out.resolve("models/crate/wood_normal.ktx2"))).vkFormat());
            Ktx2 orm = Ktx2.read(read(out.resolve("models/crate/wood_mr_orm.ktx2")));
            assertEquals(Ktx2.VK_FORMAT_BC7_UNORM_BLOCK, orm.vkFormat());
            assertEquals(
                    32,
                    orm.width(),
                    "ORM takes the metallic-roughness size; occlusion is resampled");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> meshes =
                    (List<Map<String, Object>>) item.stats().get("meshes");
            assertEquals(12.0, ((Number) meshes.get(0).get("triangles")).doubleValue());
        }
    }

    @Test
    void externalImagesAreDependenciesNotSources() throws Exception {
        Path src = dir.resolve("assets");
        Path out = dir.resolve("cooked");
        Path models = Files.createDirectories(src.resolve("models"));
        Files.createDirectories(models.resolve("textures"));
        crate(true).writeGltfExternal(models.resolve("crate.gltf"), "crate data.bin");
        Files.write(models.resolve("textures/wood.png"), TestImages.painted(32, 1));
        Files.write(models.resolve("textures/wood_normal.png"), TestImages.normalMap(32));
        Files.write(models.resolve("textures/wood_mr.png"), TestImages.painted(32, 4));
        Files.write(models.resolve("textures/wood_ao.png"), TestImages.painted(16, 7));
        try (Cooker c = new Cooker(CookerConfig.of(src, out).withThreads(2))) {
            CookReport r = c.cookAll();
            assertFalse(r.failed(), r.toJson());
            assertEquals(null, r.item("models/textures/wood.png"), "claimed by the glTF");
            assertEquals(null, r.item("models/crate data.bin"));
            assertFalse(Files.exists(out.resolve("models/textures/wood.ktx2")));
            assertTrue(Files.exists(out.resolve("models/crate/wood_basecolor.ktx2")));
            // Editing an image recooks the glTF.
            Files.write(models.resolve("textures/wood_ao.png"), TestImages.painted(16, 8));
            Set<String> affected = c.affectedBy(List.of("models/textures/wood_ao.png"));
            assertTrue(affected.contains("models/crate.gltf"), affected.toString());
            assertEquals(Status.COOKED, c.cook(affected).item("models/crate.gltf").status());
            assertEquals(Status.CACHED, c.cookAll().item("models/crate.gltf").status());
            // A stand-alone opt-in cooks the image on its own too.
            Files.writeString(models.resolve("textures/wood.png.meta"), "{\"standalone\": true}");
            assertEquals(Status.COOKED, c.cookAll().item("models/textures/wood.png").status());
        }
    }
}
