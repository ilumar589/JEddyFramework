package dev.jeddy.cooker.gltf;

import dev.jeddy.core.json.Json;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * A parsed glTF 2.0 asset (TASKS M6-03): {@code .gltf} with external or data-URI buffers, or a
 * binary {@code .glb}. Gives typed access to accessors (with byte strides, normalized integer types
 * and sparse substitution), the JSON objects and image bytes.
 *
 * <p>Thread ownership: immutable after {@link #read}; any thread.
 */
public final class GltfDocument {
    /** {@code componentType} values. */
    static final int BYTE = 5120;

    static final int UNSIGNED_BYTE = 5121;
    static final int SHORT = 5122;
    static final int UNSIGNED_SHORT = 5123;
    static final int UNSIGNED_INT = 5125;
    static final int FLOAT = 5126;

    /** Reads the files that URIs refer to (lets the cooker record them as dependencies). */
    @FunctionalInterface
    public interface UriReader {
        /** Returns the bytes of {@code file}. */
        byte[] read(Path file) throws IOException;
    }

    private final Path file;
    private final Map<String, Object> json;
    private final UriReader reader;
    private final List<ByteBuffer> buffers = new ArrayList<>();

    private GltfDocument(
            Path file, Map<String, Object> json, ByteBuffer glbBinary, UriReader reader)
            throws IOException {
        this.file = file;
        this.json = json;
        this.reader = reader;
        Map<String, Object> asset = object(json.get("asset"), "asset");
        String version = Json.string(asset, "version", "");
        if (!version.startsWith("2.")) {
            throw new IOException(file + ": glTF version " + version + " is not supported");
        }
        for (Object b : list("buffers")) {
            Map<String, Object> buffer = object(b, "buffer");
            String uri = Json.string(buffer, "uri", null);
            ByteBuffer data;
            if (uri == null) {
                if (glbBinary == null) {
                    throw new IOException(file + ": a buffer has no uri and there is no GLB chunk");
                }
                data = glbBinary;
            } else {
                data = ByteBuffer.wrap(readUri(uri));
            }
            buffers.add(data.order(ByteOrder.LITTLE_ENDIAN));
        }
    }

    /** Reads a {@code .gltf} or {@code .glb} file. */
    public static GltfDocument read(Path file) throws IOException {
        return read(file, Files.readAllBytes(file), Files::readAllBytes);
    }

    /**
     * Parses {@code bytes}, the content of {@code file}; external buffers and images are read
     * through {@code reader}.
     */
    public static GltfDocument read(Path file, byte[] bytes, UriReader reader) throws IOException {
        if (isGlb(bytes)) {
            return readGlb(file, bytes, reader);
        }
        return new GltfDocument(file, parseJson(file, bytes, 0, bytes.length), null, reader);
    }

    private static boolean isGlb(byte[] bytes) {
        return bytes.length >= 12
                && bytes[0] == 'g'
                && bytes[1] == 'l'
                && bytes[2] == 'T'
                && bytes[3] == 'F';
    }

    private static Map<String, Object> parseJson(Path file, byte[] bytes, int offset, int length)
            throws IOException {
        try {
            return object(
                    Json.parse(new String(bytes, offset, length, StandardCharsets.UTF_8)), "glTF");
        } catch (RuntimeException e) {
            throw new IOException(file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Returns the files that {@code bytes} (a {@code .gltf} or {@code .glb}) refers to: external
     * buffers and images, resolved against its directory. Only the JSON is parsed.
     */
    public static List<Path> externalFiles(Path file, byte[] bytes) throws IOException {
        Map<String, Object> json;
        if (isGlb(bytes)) {
            ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if (bytes.length < 20) {
                throw new IOException(file + ": truncated GLB");
            }
            int length = b.getInt(12);
            if (b.getInt(16) != 0x4E4F534A || length < 0 || 20L + length > bytes.length) {
                throw new IOException(file + ": GLB does not start with a JSON chunk");
            }
            json = parseJson(file, bytes, 20, length);
        } else {
            json = parseJson(file, bytes, 0, bytes.length);
        }
        List<Path> out = new ArrayList<>();
        for (String key : List.of("buffers", "images")) {
            Object list = json.get(key);
            if (list == null) {
                continue;
            }
            for (Object o : Json.asArray(list, key)) {
                String uri = Json.string(object(o, key), "uri", null);
                if (uri != null && !uri.startsWith("data:")) {
                    out.add(resolveUri(file, uri));
                }
            }
        }
        return out;
    }

    private static Path resolveUri(Path file, String uri) {
        String path = URLDecoder.decode(uri.replace("+", "%2B"), StandardCharsets.UTF_8);
        return file.resolveSibling(path).normalize();
    }

    private static GltfDocument readGlb(Path file, byte[] bytes, UriReader reader)
            throws IOException {
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int version = b.getInt(4);
        int length = b.getInt(8);
        if (version != 2 || length > bytes.length) {
            throw new IOException(file + ": bad GLB header");
        }
        Map<String, Object> json = null;
        ByteBuffer bin = null;
        int p = 12;
        while (p + 8 <= length) {
            int chunkLength = b.getInt(p);
            int type = b.getInt(p + 4);
            int start = p + 8;
            if (chunkLength < 0 || start + chunkLength > length) {
                throw new IOException(file + ": truncated GLB chunk");
            }
            if (type == 0x4E4F534A) { // JSON
                json = parseJson(file, bytes, start, chunkLength);
            } else if (type == 0x004E4942) { // BIN
                bin = ByteBuffer.wrap(bytes, start, chunkLength).slice();
            }
            p = start + ((chunkLength + 3) & ~3);
        }
        if (json == null) {
            throw new IOException(file + ": GLB has no JSON chunk");
        }
        return new GltfDocument(file, json, bin, reader);
    }

    /** Returns the source file. */
    public Path file() {
        return file;
    }

    /** Returns the top-level JSON object. */
    public Map<String, Object> json() {
        return json;
    }

    /** Returns a top-level array ({@code meshes}, {@code nodes}, ...), empty if absent. */
    public List<Object> list(String name) {
        Object v = json.get(name);
        return v == null ? List.of() : Json.asArray(v, name);
    }

    /** Returns element {@code i} of a top-level array as an object. */
    public Map<String, Object> get(String name, int i) {
        return object(list(name).get(i), name + "[" + i + "]");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object v, String what) {
        return Json.asObject(v, what);
    }

    /** Returns the number of elements of accessor {@code a}. */
    public int count(int a) {
        return Json.integer(get("accessors", a), "count", 0);
    }

    /** Returns the component count of an accessor's type (SCALAR 1 .. MAT4 16). */
    public int components(int a) {
        return switch (Json.string(get("accessors", a), "type", "SCALAR")) {
            case "SCALAR" -> 1;
            case "VEC2" -> 2;
            case "VEC3" -> 3;
            case "VEC4", "MAT2" -> 4;
            case "MAT3" -> 9;
            case "MAT4" -> 16;
            default -> throw new IllegalArgumentException("accessor type");
        };
    }

    /**
     * Reads accessor {@code a} as floats ({@code count × components}), converting normalized
     * integers to [0, 1] or [-1, 1] and applying sparse substitutions.
     */
    public float[] floats(int a) throws IOException {
        Map<String, Object> acc = get("accessors", a);
        int n = count(a);
        int c = components(a);
        int type = Json.integer(acc, "componentType", FLOAT);
        boolean normalized = Json.bool(acc, "normalized", false);
        float[] out = new float[n * c];
        if (acc.containsKey("bufferView")) {
            View v = view(Json.integer(acc, "bufferView", 0), Json.integer(acc, "byteOffset", 0));
            int size = size(type);
            int stride = v.stride > 0 ? v.stride : size * c;
            for (int i = 0; i < n; i++) {
                for (int k = 0; k < c; k++) {
                    out[i * c + k] =
                            component(v.data, v.offset + i * stride + k * size, type, normalized);
                }
            }
        }
        Object sparse = acc.get("sparse");
        if (sparse != null) {
            Map<String, Object> s = object(sparse, "sparse");
            int count = Json.integer(s, "count", 0);
            Map<String, Object> idx = object(s.get("indices"), "sparse.indices");
            Map<String, Object> val = object(s.get("values"), "sparse.values");
            View iv = view(Json.integer(idx, "bufferView", 0), Json.integer(idx, "byteOffset", 0));
            View vv = view(Json.integer(val, "bufferView", 0), Json.integer(val, "byteOffset", 0));
            int itype = Json.integer(idx, "componentType", UNSIGNED_INT);
            for (int i = 0; i < count; i++) {
                int target = (int) integer(iv.data, iv.offset + i * size(itype), itype);
                for (int k = 0; k < c; k++) {
                    out[target * c + k] =
                            component(
                                    vv.data,
                                    vv.offset + (i * c + k) * size(type),
                                    type,
                                    normalized);
                }
            }
        }
        return out;
    }

    /** Reads an index (or joint) accessor as integers. */
    public int[] ints(int a) throws IOException {
        Map<String, Object> acc = get("accessors", a);
        int n = count(a);
        int c = components(a);
        int type = Json.integer(acc, "componentType", UNSIGNED_INT);
        int[] out = new int[n * c];
        if (!acc.containsKey("bufferView")) {
            return out;
        }
        View v = view(Json.integer(acc, "bufferView", 0), Json.integer(acc, "byteOffset", 0));
        int size = size(type);
        int stride = v.stride > 0 ? v.stride : size * c;
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < c; k++) {
                out[i * c + k] = (int) integer(v.data, v.offset + i * stride + k * size, type);
            }
        }
        return out;
    }

    /**
     * Returns the name of image {@code i} (its {@code name}, else its file name) without extension.
     */
    public String imageName(int i) {
        Map<String, Object> img = get("images", i);
        String name = Json.string(img, "name", null);
        String uri = Json.string(img, "uri", null);
        if (name == null && uri != null && !uri.startsWith("data:")) {
            String path = URLDecoder.decode(uri.replace("+", "%2B"), StandardCharsets.UTF_8);
            name = path.substring(path.lastIndexOf('/') + 1);
        }
        return stripExtension(name == null || name.isEmpty() ? "image" + i : name);
    }

    /** Returns the bytes and MIME type of image {@code i}. */
    public Image image(int i) throws IOException {
        Map<String, Object> img = get("images", i);
        String uri = Json.string(img, "uri", null);
        if (uri != null) {
            String mime =
                    uri.startsWith("data:") ? uri.substring(5, uri.indexOf(';')) : guessMime(uri);
            return new Image(imageName(i), mime, readUri(uri));
        }
        View v = view(Json.integer(img, "bufferView", 0), 0);
        byte[] bytes = new byte[v.length];
        v.data.get(v.offset, bytes);
        return new Image(imageName(i), Json.string(img, "mimeType", ""), bytes);
    }

    /** An embedded or referenced image. */
    public record Image(String name, String mimeType, byte[] bytes) {}

    private record View(ByteBuffer data, int offset, int length, int stride) {}

    private View view(int index, int extraOffset) throws IOException {
        Map<String, Object> v = get("bufferViews", index);
        int buffer = Json.integer(v, "buffer", 0);
        int offset = Json.integer(v, "byteOffset", 0) + extraOffset;
        int length = Json.integer(v, "byteLength", 0);
        if (buffer >= buffers.size()) {
            throw new IOException(file + ": bufferView " + index + " refers to a missing buffer");
        }
        ByteBuffer data = buffers.get(buffer);
        if (offset < 0 || offset > data.capacity()) {
            throw new IOException(file + ": bufferView " + index + " lies outside its buffer");
        }
        return new View(data, offset, length, Json.integer(v, "byteStride", 0));
    }

    private static int size(int type) {
        return switch (type) {
            case BYTE, UNSIGNED_BYTE -> 1;
            case SHORT, UNSIGNED_SHORT -> 2;
            case UNSIGNED_INT, FLOAT -> 4;
            default -> throw new IllegalArgumentException("componentType " + type);
        };
    }

    private static float component(ByteBuffer b, int at, int type, boolean normalized) {
        return switch (type) {
            case FLOAT -> b.getFloat(at);
            case UNSIGNED_BYTE -> normalized ? (b.get(at) & 0xFF) / 255f : b.get(at) & 0xFF;
            case BYTE -> normalized ? Math.max(b.get(at) / 127f, -1f) : b.get(at);
            case UNSIGNED_SHORT ->
                    normalized ? (b.getShort(at) & 0xFFFF) / 65535f : b.getShort(at) & 0xFFFF;
            case SHORT -> normalized ? Math.max(b.getShort(at) / 32767f, -1f) : b.getShort(at);
            case UNSIGNED_INT -> Integer.toUnsignedLong(b.getInt(at));
            default -> throw new IllegalArgumentException("componentType " + type);
        };
    }

    private static long integer(ByteBuffer b, int at, int type) {
        return switch (type) {
            case UNSIGNED_BYTE -> b.get(at) & 0xFF;
            case UNSIGNED_SHORT -> b.getShort(at) & 0xFFFF;
            case UNSIGNED_INT -> Integer.toUnsignedLong(b.getInt(at));
            case BYTE -> b.get(at);
            case SHORT -> b.getShort(at);
            default -> throw new IllegalArgumentException("integer componentType " + type);
        };
    }

    private byte[] readUri(String uri) throws IOException {
        if (uri.startsWith("data:")) {
            int comma = uri.indexOf(',');
            if (comma < 0 || !uri.substring(0, comma).endsWith(";base64")) {
                throw new IOException(file + ": unsupported data URI");
            }
            return Base64.getDecoder().decode(uri.substring(comma + 1));
        }
        return reader.read(resolveUri(file, uri));
    }

    private static String guessMime(String uri) {
        String u = uri.toLowerCase(java.util.Locale.ROOT);
        return u.endsWith(".png")
                ? "image/png"
                : u.endsWith(".jpg") || u.endsWith(".jpeg") ? "image/jpeg" : "";
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
