package dev.jeddy.cooker.material;

import dev.jeddy.asset.AssetPaths;
import dev.jeddy.asset.format.MaterialData;
import dev.jeddy.cooker.CookContext;
import dev.jeddy.cooker.ImportSettings;
import dev.jeddy.cooker.Importer;
import dev.jeddy.cooker.texture.TextureCooker;
import dev.jeddy.cooker.texture.TextureCooker.Kind;
import dev.jeddy.core.json.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cooks a hand-written material ({@code <name>.jmat.json}) into {@code <name>.jmat} (TASKS M6-06).
 * Keys: {@code shading} ({@code pbr}, {@code unlit}, {@code foliage}), {@code baseColor}, {@code
 * metallic}, {@code roughness}, {@code emissive}, {@code emissiveStrength}, {@code normalScale},
 * {@code occlusionStrength}, {@code alpha} ({@code opaque}, {@code mask}, {@code blend}), {@code
 * alphaCutoff}, {@code doubleSided}, {@code castsShadows}, {@code uvTransform} ({@code [scaleU,
 * scaleV, offsetU, offsetV]}), {@code uvRotation}, {@code features} (custom bits 16..31) and {@code
 * textures}: an object from slot name ({@code baseColor}, {@code normal}, {@code orm}, {@code
 * emissive}, {@code occlusion}) to a path or {@code {"path": ..., "uv": 1}}.
 *
 * <p>Texture paths are relative to the material (or to the source root with a leading {@code /}). A
 * source image path ({@code rock_n.png}) refers to its cooked {@code .ktx2}; the importer warns
 * when the image does not exist or its usage does not fit the slot.
 *
 * <p>Thread ownership: stateless; any thread.
 */
public final class MaterialImporter implements Importer {
    private static final String SUFFIX = ".jmat.json";

    @Override
    public String name() {
        return "material";
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public boolean accepts(String path, ImportSettings settings) {
        return path.toLowerCase(Locale.ROOT).endsWith(SUFFIX);
    }

    @Override
    public void cook(CookContext ctx) throws Exception {
        Map<String, Object> m = Json.asObject(Json.parse(ctx.bytes()), ctx.path());
        MaterialData.Builder b = new MaterialData.Builder();
        b.shadingModel(
                switch (Json.string(m, "shading", "pbr")) {
                    case "pbr" -> MaterialData.SHADING_PBR;
                    case "unlit" -> MaterialData.SHADING_UNLIT;
                    case "foliage" -> MaterialData.SHADING_FOLIAGE;
                    default ->
                            throw new IllegalArgumentException(
                                    "unknown shading " + Json.string(m, "shading", ""));
                });
        float[] base = floats(m, "baseColor", new float[] {1, 1, 1, 1});
        b.baseColor(base[0], base[1], base[2], base.length > 3 ? base[3] : 1);
        float[] em = floats(m, "emissive", new float[] {0, 0, 0});
        b.emissive(em[0], em[1], em[2], (float) Json.number(m, "emissiveStrength", 1));
        b.metallicRoughness(
                (float) Json.number(m, "metallic", 0), (float) Json.number(m, "roughness", 1));
        b.normalOcclusion(
                (float) Json.number(m, "normalScale", 1),
                (float) Json.number(m, "occlusionStrength", 1));
        float[] uv = floats(m, "uvTransform", new float[] {1, 1, 0, 0});
        b.uvTransform(uv[0], uv[1], uv[2], uv[3], (float) Json.number(m, "uvRotation", 0));
        int alpha =
                switch (Json.string(m, "alpha", "opaque")) {
                    case "opaque" -> MaterialData.ALPHA_OPAQUE;
                    case "mask" -> MaterialData.ALPHA_MASK;
                    case "blend" -> MaterialData.ALPHA_BLEND;
                    default ->
                            throw new IllegalArgumentException(
                                    "unknown alpha mode " + Json.string(m, "alpha", ""));
                };
        b.alpha(alpha, (float) Json.number(m, "alphaCutoff", 0.5));
        int flags = 0;
        if (Json.bool(m, "doubleSided", false)) {
            flags |= MaterialData.FLAG_DOUBLE_SIDED;
        }
        if (Json.bool(m, "castsShadows", alpha != MaterialData.ALPHA_BLEND)) {
            flags |= MaterialData.FLAG_CASTS_SHADOWS;
        }
        b.flags(flags);
        int custom = Json.integer(m, "features", 0);
        if ((custom & MaterialData.ENGINE_FEATURES) != 0) {
            throw new IllegalArgumentException("custom features must use bits 16..31");
        }
        b.featureMask(custom);
        Object textures = m.get("textures");
        if (textures != null) {
            for (Map.Entry<String, Object> e : Json.asObject(textures, "textures").entrySet()) {
                int slot;
                Kind expected;
                switch (e.getKey()) {
                    case "baseColor" -> {
                        slot = MaterialData.SLOT_BASE_COLOR;
                        expected = Kind.COLOR;
                    }
                    case "normal" -> {
                        slot = MaterialData.SLOT_NORMAL;
                        expected = Kind.NORMAL;
                    }
                    case "orm" -> {
                        slot = MaterialData.SLOT_ORM;
                        expected = Kind.LINEAR;
                    }
                    case "emissive" -> {
                        slot = MaterialData.SLOT_EMISSIVE;
                        expected = Kind.COLOR;
                    }
                    case "occlusion" -> {
                        slot = MaterialData.SLOT_OCCLUSION;
                        expected = Kind.MASK;
                    }
                    default ->
                            throw new IllegalArgumentException(
                                    "unknown texture slot " + e.getKey());
                }
                String path;
                int uvSet = 0;
                if (e.getValue() instanceof String str) {
                    path = str;
                } else {
                    Map<String, Object> t = Json.asObject(e.getValue(), e.getKey());
                    path = Json.string(t, "path", null);
                    uvSet = Json.integer(t, "uv", 0);
                    if (path == null) {
                        throw new IllegalArgumentException(
                                "texture " + e.getKey() + " has no path");
                    }
                }
                String cooked = texture(ctx, path, expected, e.getKey());
                b.texture(slot, AssetPaths.id(cooked), uvSet == 1 ? MaterialData.TEXTURE_UV1 : 0);
            }
        }
        ctx.output(ctx.outputBase() + ".jmat", b.deriveFeatures().build().toBytes());
    }

    /** Resolves a texture reference to its cooked path and checks the source. */
    private static String texture(CookContext ctx, String ref, Kind expected, String slot)
            throws Exception {
        String rel;
        if (ref.startsWith("/")) {
            rel = ref.substring(1);
        } else {
            int slash = ctx.path().lastIndexOf('/');
            rel = (slash < 0 ? "" : ctx.path().substring(0, slash + 1)) + ref;
        }
        Path source = ctx.config().sourceRoot().resolve(rel).normalize();
        if (!source.startsWith(ctx.config().sourceRoot())) {
            throw new IllegalArgumentException("texture " + ref + " is outside the source root");
        }
        rel = ctx.config().sourceRoot().relativize(source).toString().replace('\\', '/');
        String lower = rel.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".ktx2")) {
            return AssetPaths.normalize(CookContext.sanitize(rel));
        }
        if (!Files.isRegularFile(source)) {
            ctx.warn(slot + " texture " + ref + " does not exist");
        } else {
            ImportSettings ts = ImportSettings.load(ctx.config().sourceRoot(), source);
            Kind kind =
                    ts.has("kind")
                            ? TextureCooker.parseKind(ts.string("kind", "color"))
                            : TextureCooker.guessKind(rel);
            if (kind != expected) {
                ctx.warn(
                        slot
                                + " texture "
                                + ref
                                + " cooks as "
                                + kind.name().toLowerCase(Locale.ROOT)
                                + ", the slot expects "
                                + expected.name().toLowerCase(Locale.ROOT)
                                + " (set \"kind\" in its .meta)");
            }
        }
        int slash = rel.lastIndexOf('/');
        int dot = rel.indexOf('.', slash + 1);
        String stem = dot > slash + 1 ? rel.substring(0, dot) : rel;
        return AssetPaths.normalize(CookContext.sanitize(stem) + ".ktx2");
    }

    private static float[] floats(Map<String, Object> m, String key, float[] fallback) {
        Object v = m.get(key);
        if (v == null) {
            return fallback;
        }
        List<Object> list = Json.asArray(v, key);
        if (list.size() < fallback.length - (key.equals("baseColor") ? 1 : 0)) {
            throw new IllegalArgumentException(key + " needs " + fallback.length + " numbers");
        }
        float[] out = new float[Math.max(list.size(), fallback.length)];
        System.arraycopy(fallback, 0, out, 0, fallback.length);
        for (int i = 0; i < list.size(); i++) {
            out[i] = ((Number) list.get(i)).floatValue();
        }
        return out;
    }
}
