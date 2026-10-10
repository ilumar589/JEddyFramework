package dev.jeddy.cooker.gltf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.cooker.gltf.GltfImporter.Material;
import dev.jeddy.cooker.gltf.GltfImporter.Primitive;
import dev.jeddy.cooker.gltf.GltfImporter.Scene;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** glTF 2.0 import: containers, accessors, materials, nodes and lights (TASKS M6-03). */
class GltfImporterTest {
    @TempDir Path dir;

    private static GltfBuilder cubeScene() {
        float[][] c = GltfBuilder.cube();
        GltfBuilder b = new GltfBuilder();
        int pos = b.floats(c[0], "VEC3");
        int nrm = b.floats(c[1], "VEC3");
        int uv = b.floats(c[2], "VEC2");
        int idx = b.indices(GltfBuilder.toInts(c[3]));
        b.add(
                "meshes",
                Map.of(
                        "name",
                        "cube",
                        "primitives",
                        List.of(
                                Map.of(
                                        "attributes",
                                        Map.of("POSITION", pos, "NORMAL", nrm, "TEXCOORD_0", uv),
                                        "indices",
                                        idx,
                                        "material",
                                        0))));
        Map<String, Object> pbr = new LinkedHashMap<>();
        pbr.put("baseColorFactor", List.of(0.5, 0.25, 1.0, 0.75));
        pbr.put("metallicFactor", 0.0);
        pbr.put("roughnessFactor", 0.4);
        pbr.put(
                "baseColorTexture",
                Map.of(
                        "index",
                        0,
                        "extensions",
                        Map.of(
                                "KHR_texture_transform",
                                Map.of(
                                        "scale",
                                        List.of(2.0, 3.0),
                                        "offset",
                                        List.of(0.5, 0.25),
                                        "rotation",
                                        0.1))));
        pbr.put("metallicRoughnessTexture", Map.of("index", 1));
        Map<String, Object> mat = new LinkedHashMap<>();
        mat.put("name", "stone");
        mat.put("pbrMetallicRoughness", pbr);
        mat.put("normalTexture", Map.of("index", 1, "scale", 0.5));
        mat.put("emissiveFactor", List.of(1.0, 0.5, 0.0));
        mat.put(
                "extensions",
                Map.of("KHR_materials_emissive_strength", Map.of("emissiveStrength", 4.0)));
        mat.put("alphaMode", "MASK");
        mat.put("alphaCutoff", 0.3);
        mat.put("doubleSided", true);
        b.add("materials", mat);
        b.add("samplers", Map.of("wrapS", 33071, "wrapT", 10497));
        b.add("textures", Map.of("source", 0, "sampler", 0));
        b.add("textures", Map.of("source", 0));
        b.add("images", Map.of("uri", "textures/stone%20albedo.png"));
        // A parent node with a TRS and a child given as a matrix (90° about Y, scale 2, offset).
        b.add(
                "nodes",
                Map.of(
                        "name", "root",
                        "children", List.of(1, 2),
                        "translation", List.of(1.0, 2.0, 3.0)));
        b.add(
                "nodes",
                Map.of(
                        "name",
                        "cube",
                        "mesh",
                        0,
                        "matrix",
                        List.of(
                                0.0, 0.0, -2.0, 0.0, 0.0, 2.0, 0.0, 0.0, 2.0, 0.0, 0.0, 0.0, 5.0,
                                6.0, 7.0, 1.0)));
        b.add(
                "nodes",
                Map.of(
                        "name",
                        "lamp",
                        "extensions",
                        Map.of("KHR_lights_punctual", Map.of("light", 0))));
        b.root.put(
                "extensions",
                Map.of(
                        "KHR_lights_punctual",
                        Map.of(
                                "lights",
                                List.of(
                                        Map.of(
                                                "name",
                                                "torch",
                                                "type",
                                                "spot",
                                                "color",
                                                List.of(1.0, 0.8, 0.6),
                                                "intensity",
                                                20.0,
                                                "range",
                                                8.0,
                                                "spot",
                                                Map.of(
                                                        "innerConeAngle",
                                                        0.2,
                                                        "outerConeAngle",
                                                        0.6))))));
        return b;
    }

    private static void checkCube(Scene s) {
        float[][] c = GltfBuilder.cube();
        assertEquals(1, s.meshes().size());
        Primitive p = s.meshes().get(0).primitives().get(0);
        assertArrayEquals(c[0], p.positions);
        assertArrayEquals(c[1], p.normals);
        assertArrayEquals(c[2], p.uv0);
        assertArrayEquals(GltfBuilder.toInts(c[3]), p.indices);
        assertNull(p.tangents);
        assertNull(p.joints);
        assertEquals(0, p.material);

        Material m = s.materials().get(0);
        assertEquals("stone", m.name());
        assertArrayEquals(new float[] {0.5f, 0.25f, 1f, 0.75f}, m.baseColor());
        assertEquals(0f, m.metallic());
        assertEquals(0.4f, m.roughness());
        assertArrayEquals(new float[] {1f, 0.5f, 0f}, m.emissive());
        assertEquals(4f, m.emissiveStrength());
        assertEquals(0.5f, m.normalScale());
        assertEquals("MASK", m.alphaMode());
        assertEquals(0.3f, m.alphaCutoff());
        assertTrue(m.doubleSided());
        assertArrayEquals(new float[] {2, 3, 0.5f, 0.25f}, m.uvTransform());
        assertEquals(0.1f, m.uvRotation(), 1e-6f);
        assertEquals(0, m.baseColorTexture().texture());
        assertEquals(1, m.metallicRoughnessTexture().texture());
        assertEquals(1, m.normalTexture().texture());
        assertNull(m.occlusionTexture());

        assertEquals(2, s.textures().size());
        assertEquals(false, s.textures().get(0).wrapS());
        assertEquals(true, s.textures().get(0).wrapT());
        assertEquals(true, s.textures().get(1).wrapS());

        assertEquals(3, s.nodes().size());
        assertEquals(-1, s.nodes().get(0).parent());
        assertArrayEquals(new float[] {1, 2, 3}, s.nodes().get(0).translation());
        GltfImporter.Node cube = s.nodes().get(1);
        assertEquals(0, cube.parent());
        assertEquals(0, cube.mesh());
        assertArrayEquals(new float[] {5, 6, 7}, cube.translation());
        assertArrayEquals(new float[] {2, 2, 2}, cube.scale(), 1e-6f);
        float h = (float) Math.sqrt(0.5);
        assertArrayEquals(new float[] {0, h, 0, h}, cube.rotation(), 1e-6f);
        assertEquals(0, s.nodes().get(2).light());

        GltfImporter.Light l = s.lights().get(0);
        assertEquals("spot", l.type());
        assertEquals(20f, l.intensity());
        assertEquals(8f, l.range());
        assertEquals(0.2f, l.innerCone());
        assertEquals(0.6f, l.outerCone());
        assertTrue(s.warnings().isEmpty(), s.warnings().toString());
    }

    @Test
    void importsTheSameSceneFromGltfGlbAndExternalBuffers() throws IOException {
        checkCube(
                GltfImporter.importScene(
                        GltfDocument.read(cubeScene().writeGltf(dir.resolve("a.gltf")))));
        checkCube(
                GltfImporter.importScene(
                        GltfDocument.read(cubeScene().writeGlb(dir.resolve("a.glb")))));
        checkCube(
                GltfImporter.importScene(
                        GltfDocument.read(
                                cubeScene()
                                        .writeGltfExternal(
                                                dir.resolve("b.gltf"), "cube data.bin"))));
    }

    @Test
    void imageNamesComeFromTheUri() throws IOException {
        GltfDocument doc = GltfDocument.read(cubeScene().writeGltf(dir.resolve("a.gltf")));
        assertThrows(IOException.class, () -> doc.image(0)); // the PNG does not exist
        GltfBuilder b = new GltfBuilder();
        int view = b.view(new byte[] {1, 2, 3}, 0);
        b.add("images", Map.of("bufferView", view, "mimeType", "image/png", "name", "rock.png"));
        GltfDocument.Image img = GltfDocument.read(b.writeGlb(dir.resolve("i.glb"))).image(0);
        assertEquals("rock", img.name());
        assertEquals("image/png", img.mimeType());
        assertArrayEquals(new byte[] {1, 2, 3}, img.bytes());
    }

    @Test
    void readsStridedNormalizedAndSparseAccessors() throws IOException {
        GltfBuilder b = new GltfBuilder();
        float[] pos = {0, 0, 0, 1, 0, 0, 0, 1, 0};
        float[] nrm = {0, 0, 1, 0, 0, 1, 0, 0, 1};
        int[] both = b.interleaved(pos, nrm);
        int uv =
                b.shorts(
                        new int[] {0, 255, 255, 0, 51, 102},
                        "VEC2",
                        GltfDocument.UNSIGNED_BYTE,
                        true);
        int weights =
                b.shorts(
                        new int[] {65535, 0, 0, 0, 32768, 32767, 0, 0, 0, 0, 0, 65535},
                        "VEC4",
                        GltfDocument.UNSIGNED_SHORT,
                        true);
        int joints =
                b.shorts(
                        new int[] {1, 0, 0, 0, 2, 3, 0, 0, 0, 0, 0, 7},
                        "VEC4",
                        GltfDocument.UNSIGNED_BYTE,
                        false);
        int moved = b.floats(pos, "VEC3");
        b.sparse(moved, new int[] {2}, new float[] {0, 2, 0});
        b.add(
                "meshes",
                Map.of(
                        "primitives",
                        List.of(
                                Map.of(
                                        "attributes",
                                        Map.of(
                                                "POSITION", both[0],
                                                "NORMAL", both[1],
                                                "TEXCOORD_0", uv,
                                                "JOINTS_0", joints,
                                                "WEIGHTS_0", weights)),
                                Map.of("attributes", Map.of("POSITION", moved)),
                                Map.of("attributes", Map.of("POSITION", moved), "mode", 1))));
        GltfDocument doc = GltfDocument.read(b.writeGltf(dir.resolve("s.gltf")));
        Scene s = GltfImporter.importScene(doc);
        List<Primitive> prims = s.meshes().get(0).primitives();
        assertEquals(2, prims.size(), "the line primitive is skipped");
        assertEquals(1, s.warnings().size());
        Primitive p = prims.get(0);
        assertArrayEquals(pos, p.positions);
        assertArrayEquals(nrm, p.normals);
        assertArrayEquals(new float[] {0, 1, 1, 0, 0.2f, 0.4f}, p.uv0, 1e-6f);
        assertArrayEquals(new int[] {1, 0, 0, 0, 2, 3, 0, 0, 0, 0, 0, 7}, p.joints);
        assertEquals(1f, p.weights[0]);
        assertEquals(32768 / 65535f, p.weights[4], 1e-6f);
        assertArrayEquals(new int[] {0, 1, 2}, p.indices, "non-indexed primitives get 0..n-1");
        assertArrayEquals(new float[] {0, 0, 0, 1, 0, 0, 0, 2, 0}, prims.get(1).positions);
    }

    @Test
    void rejectsIndicesOutsideTheVertices() throws IOException {
        GltfBuilder b = new GltfBuilder();
        int pos = b.floats(new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0}, "VEC3");
        int idx = b.indices(new int[] {0, 1, 3});
        b.add(
                "meshes",
                Map.of(
                        "primitives",
                        List.of(Map.of("attributes", Map.of("POSITION", pos), "indices", idx))));
        GltfDocument doc = GltfDocument.read(b.writeGltf(dir.resolve("bad.gltf")));
        assertThrows(IOException.class, () -> GltfImporter.importScene(doc));
    }

    @Test
    void decomposesMirroredMatrices() {
        // Rotation of 30° about Z, scale (-1, 2, 3), translation (4, 5, 6).
        double c = Math.cos(Math.PI / 6);
        double sn = Math.sin(Math.PI / 6);
        float[] m = {
            (float) (-c),
            (float) (-sn),
            0,
            0,
            (float) (-2 * sn),
            (float) (2 * c),
            0,
            0,
            0,
            0,
            3,
            0,
            4,
            5,
            6,
            1
        };
        float[] t = new float[3];
        float[] r = new float[4];
        float[] s = new float[3];
        GltfImporter.decompose(m, t, r, s);
        assertArrayEquals(new float[] {4, 5, 6}, t);
        assertArrayEquals(new float[] {-1, 2, 3}, s, 1e-5f);
        assertArrayEquals(
                new float[] {0, 0, (float) Math.sin(Math.PI / 12), (float) Math.cos(Math.PI / 12)},
                r,
                1e-5f);
    }
}
