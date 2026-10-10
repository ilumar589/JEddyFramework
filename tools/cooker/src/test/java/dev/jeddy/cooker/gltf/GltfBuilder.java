package dev.jeddy.cooker.gltf;

import dev.jeddy.core.json.Json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes small glTF files for tests: one binary buffer, accessors and free-form JSON. */
final class GltfBuilder {
    final Map<String, Object> root = new LinkedHashMap<>();
    private final ByteArrayOutputStream bin = new ByteArrayOutputStream();
    private final List<Object> views = new ArrayList<>();
    private final List<Object> accessors = new ArrayList<>();

    GltfBuilder() {
        root.put("asset", Map.of("version", "2.0"));
    }

    /** Appends a float accessor of {@code type} (VEC3, ...). */
    int floats(float[] data, String type) {
        ByteBuffer b = ByteBuffer.allocate(data.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : data) {
            b.putFloat(f);
        }
        return accessor(
                b.array(), GltfDocument.FLOAT, data.length / components(type), type, false, 0);
    }

    /** Appends an interleaved view of two VEC3 float streams with a byte stride. */
    int[] interleaved(float[] a, float[] b) {
        int n = a.length / 3;
        ByteBuffer buf = ByteBuffer.allocate(n * 24).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < 3; k++) {
                buf.putFloat(a[i * 3 + k]);
            }
            for (int k = 0; k < 3; k++) {
                buf.putFloat(b[i * 3 + k]);
            }
        }
        int view = view(buf.array(), 24);
        return new int[] {
            addAccessor(view, 0, GltfDocument.FLOAT, n, "VEC3", false),
            addAccessor(view, 12, GltfDocument.FLOAT, n, "VEC3", false)
        };
    }

    /** Appends unsigned-short (or unsigned-byte, normalized) data. */
    int shorts(int[] data, String type, int componentType, boolean normalized) {
        int size = componentType == GltfDocument.UNSIGNED_BYTE ? 1 : 2;
        ByteBuffer b =
                ByteBuffer.allocate((data.length * size + 3) & ~3).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : data) {
            if (size == 1) {
                b.put((byte) v);
            } else {
                b.putShort((short) v);
            }
        }
        return accessor(
                b.array(), componentType, data.length / components(type), type, normalized, 0);
    }

    /** Appends an unsigned-int index accessor. */
    int indices(int[] data) {
        ByteBuffer b = ByteBuffer.allocate(data.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : data) {
            b.putInt(v);
        }
        return accessor(b.array(), GltfDocument.UNSIGNED_INT, data.length, "SCALAR", false, 0);
    }

    /** Adds a sparse substitution to a float accessor. */
    void sparse(int accessor, int[] targets, float[] values) {
        ByteBuffer ib = ByteBuffer.allocate(targets.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int t : targets) {
            ib.putInt(t);
        }
        ByteBuffer vb = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : values) {
            vb.putFloat(v);
        }
        int iv = view(ib.array(), 0);
        int vv = view(vb.array(), 0);
        @SuppressWarnings("unchecked")
        Map<String, Object> acc = (Map<String, Object>) accessors.get(accessor);
        acc.put(
                "sparse",
                Map.of(
                        "count", targets.length,
                        "indices",
                                Map.of(
                                        "bufferView",
                                        iv,
                                        "componentType",
                                        GltfDocument.UNSIGNED_INT),
                        "values", Map.of("bufferView", vv)));
    }

    int view(byte[] data, int stride) {
        int offset = bin.size();
        bin.writeBytes(data);
        while (bin.size() % 4 != 0) {
            bin.write(0);
        }
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("buffer", 0);
        v.put("byteOffset", offset);
        v.put("byteLength", data.length);
        if (stride > 0) {
            v.put("byteStride", stride);
        }
        views.add(v);
        return views.size() - 1;
    }

    private int accessor(
            byte[] data,
            int componentType,
            int count,
            String type,
            boolean normalized,
            int stride) {
        return addAccessor(view(data, stride), 0, componentType, count, type, normalized);
    }

    private int addAccessor(
            int view, int offset, int componentType, int count, String type, boolean normalized) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("bufferView", view);
        a.put("byteOffset", offset);
        a.put("componentType", componentType);
        a.put("count", count);
        a.put("type", type);
        if (normalized) {
            a.put("normalized", true);
        }
        accessors.add(a);
        return accessors.size() - 1;
    }

    private static int components(String type) {
        return switch (type) {
            case "SCALAR" -> 1;
            case "VEC2" -> 2;
            case "VEC3" -> 3;
            default -> 4;
        };
    }

    /** Adds {@code value} to the top-level array {@code key}; returns its index. */
    @SuppressWarnings("unchecked")
    int add(String key, Object value) {
        List<Object> list = (List<Object>) root.computeIfAbsent(key, k -> new ArrayList<>());
        list.add(value);
        return list.size() - 1;
    }

    private Map<String, Object> json(Map<String, Object> buffer) {
        Map<String, Object> out = new LinkedHashMap<>(root);
        out.put("buffers", List.of(buffer));
        out.put("bufferViews", views);
        out.put("accessors", accessors);
        return out;
    }

    /** Writes a {@code .gltf} with the buffer as a base64 data URI. */
    Path writeGltf(Path file) throws IOException {
        byte[] data = bin.toByteArray();
        Map<String, Object> buffer = new LinkedHashMap<>();
        buffer.put("byteLength", data.length);
        buffer.put(
                "uri",
                "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(data));
        Files.writeString(file, Json.write(json(buffer)));
        return file;
    }

    /** Writes a {@code .gltf} with the buffer in a sibling {@code .bin} file. */
    Path writeGltfExternal(Path file, String binName) throws IOException {
        byte[] data = bin.toByteArray();
        Files.write(file.resolveSibling(binName), data);
        Map<String, Object> buffer = new LinkedHashMap<>();
        buffer.put("byteLength", data.length);
        buffer.put("uri", binName.replace(" ", "%20"));
        Files.writeString(file, Json.write(json(buffer)));
        return file;
    }

    /** Writes a binary {@code .glb}. */
    Path writeGlb(Path file) throws IOException {
        byte[] data = bin.toByteArray();
        byte[] json =
                Json.write(json(Map.of("byteLength", data.length)))
                        .getBytes(StandardCharsets.UTF_8);
        int jsonPadded = (json.length + 3) & ~3;
        int binPadded = (data.length + 3) & ~3;
        int total = 12 + 8 + jsonPadded + 8 + binPadded;
        ByteBuffer b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x46546C67).putInt(2).putInt(total);
        b.putInt(jsonPadded).putInt(0x4E4F534A).put(json);
        for (int i = json.length; i < jsonPadded; i++) {
            b.put((byte) ' ');
        }
        b.putInt(binPadded).putInt(0x004E4942).put(data);
        Files.write(file, b.array());
        return file;
    }

    /** A unit cube with 24 vertices (flat faces), normals and UVs; 36 indices. */
    static float[][] cube() {
        float[][] faces = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        float[] pos = new float[24 * 3];
        float[] nrm = new float[24 * 3];
        float[] uv = new float[24 * 2];
        int v = 0;
        for (float[] n : faces) {
            // Two axes spanning the face, chosen so (u × w) = n (counter-clockwise from outside).
            float[] u =
                    n[0] != 0
                            ? new float[] {0, n[0], 0}
                            : n[1] != 0 ? new float[] {0, 0, n[1]} : new float[] {n[2], 0, 0};
            float[] w = cross(n, u);
            float[][] corners = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
            for (float[] c : corners) {
                for (int k = 0; k < 3; k++) {
                    pos[v * 3 + k] = 0.5f * (n[k] + c[0] * u[k] + c[1] * w[k]);
                    nrm[v * 3 + k] = n[k];
                }
                uv[v * 2] = (c[0] + 1) / 2;
                uv[v * 2 + 1] = (1 - c[1]) / 2;
                v++;
            }
        }
        float[] idx = new float[36];
        for (int f = 0; f < 6; f++) {
            int b = f * 4;
            int[] tri = {b, b + 1, b + 2, b, b + 2, b + 3};
            for (int k = 0; k < 6; k++) {
                idx[f * 6 + k] = tri[k];
            }
        }
        return new float[][] {pos, nrm, uv, idx};
    }

    /** An {@code n × n} grid on the XZ plane with a smooth bump, normals and UVs. */
    static float[][] bumpyGrid(int n) {
        int verts = (n + 1) * (n + 1);
        float[] pos = new float[verts * 3];
        float[] nrm = new float[verts * 3];
        float[] uv = new float[verts * 2];
        for (int z = 0; z <= n; z++) {
            for (int x = 0; x <= n; x++) {
                int i = z * (n + 1) + x;
                float fx = x / (float) n - 0.5f;
                float fz = z / (float) n - 0.5f;
                float h = (float) (0.2 * Math.exp(-(fx * fx + fz * fz) * 8));
                pos[i * 3] = fx;
                pos[i * 3 + 1] = h;
                pos[i * 3 + 2] = fz;
                float dx = -16 * fx * h;
                float dz = -16 * fz * h;
                float len = (float) Math.sqrt(dx * dx + 1 + dz * dz);
                nrm[i * 3] = -dx / len;
                nrm[i * 3 + 1] = 1 / len;
                nrm[i * 3 + 2] = -dz / len;
                uv[i * 2] = x / (float) n;
                uv[i * 2 + 1] = z / (float) n;
            }
        }
        float[] idx = new float[n * n * 6];
        int k = 0;
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                int a = z * (n + 1) + x;
                int b = a + 1;
                int c = a + n + 1;
                int d = c + 1;
                // Counter-clockwise seen from +Y.
                idx[k++] = a;
                idx[k++] = c;
                idx[k++] = b;
                idx[k++] = b;
                idx[k++] = c;
                idx[k++] = d;
            }
        }
        return new float[][] {pos, nrm, uv, idx};
    }

    static int[] toInts(float[] f) {
        int[] out = new int[f.length];
        for (int i = 0; i < f.length; i++) {
            out[i] = (int) f[i];
        }
        return out;
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[] {
            a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]
        };
    }
}
