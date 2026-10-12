package dev.jeddy.cooker;

import dev.jeddy.core.json.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Import settings of one source file (DESIGN §10.3): a JSON object merged from {@value
 * #DIRECTORY_DEFAULTS} files in the source root and every directory down to the file (nearer wins),
 * then the file's own {@code <name>.meta} sidecar. Keys are importer-specific ({@code kind}, {@code
 * lods}, {@code maxSize}, ...); {@code importer} forces an importer by name and {@code "importer":
 * "none"} skips the file.
 *
 * <p>Thread ownership: immutable; any thread.
 */
public final class ImportSettings {
    /** Sidecar suffix. */
    public static final String SIDECAR = ".meta";

    /** Per-directory defaults file. */
    public static final String DIRECTORY_DEFAULTS = "_defaults.meta";

    /** No settings. */
    public static final ImportSettings EMPTY = new ImportSettings(Map.of(), List.of(), List.of());

    private final Map<String, Object> values;
    private final List<Path> files;
    private final List<Path> probed;

    private ImportSettings(Map<String, Object> values, List<Path> files, List<Path> probed) {
        this.values = values;
        this.files = files;
        this.probed = probed;
    }

    /** Settings from a map (tests, defaults). */
    public static ImportSettings of(Map<String, Object> values) {
        return new ImportSettings(new LinkedHashMap<>(values), List.of(), List.of());
    }

    /**
     * Loads the settings of {@code source}, a file under {@code root}.
     *
     * @throws IOException if a settings file cannot be read or is not a JSON object
     */
    public static ImportSettings load(Path root, Path source) throws IOException {
        Map<String, Object> merged = new LinkedHashMap<>();
        List<Path> used = new ArrayList<>();
        List<Path> probed = new ArrayList<>();
        Path dir = source.getParent();
        List<Path> chain = new ArrayList<>();
        for (Path d = dir; d != null && d.startsWith(root); d = d.getParent()) {
            chain.add(0, d);
        }
        for (Path d : chain) {
            merge(d.resolve(DIRECTORY_DEFAULTS), merged, used, probed);
        }
        merge(source.resolveSibling(source.getFileName() + SIDECAR), merged, used, probed);
        return new ImportSettings(merged, List.copyOf(used), List.copyOf(probed));
    }

    private static void merge(
            Path file, Map<String, Object> into, List<Path> used, List<Path> probed)
            throws IOException {
        probed.add(file);
        if (!Files.isRegularFile(file)) {
            return;
        }
        Object parsed;
        try {
            parsed = Json.parse(Files.readAllBytes(file));
        } catch (RuntimeException e) {
            throw new IOException(file + ": " + e.getMessage(), e);
        }
        if (!(parsed instanceof Map<?, ?>)) {
            throw new IOException(file + ": settings must be a JSON object");
        }
        into.putAll(Json.asObject(parsed, file.toString()));
        used.add(file);
    }

    /**
     * Every settings file that was looked for, present or not: a dependant records the absent ones
     * too, so a sidecar that appears later cooks it again ({@link CookContext#dependOnSettings}).
     */
    public List<Path> probed() {
        return probed;
    }

    /** Returns the settings files that contributed, nearest last. */
    public List<Path> files() {
        return files;
    }

    /** Returns {@code true} if {@code key} is set. */
    public boolean has(String key) {
        return values.containsKey(key);
    }

    /** Returns a string setting. */
    public String string(String key, String fallback) {
        return Json.string(values, key, fallback);
    }

    /** Returns a boolean setting. */
    public boolean bool(String key, boolean fallback) {
        return Json.bool(values, key, fallback);
    }

    /** Returns an integer setting. */
    public int integer(String key, int fallback) {
        return Json.integer(values, key, fallback);
    }

    /** Returns a number setting. */
    public float number(String key, float fallback) {
        return (float) Json.number(values, key, fallback);
    }

    /** A list of strings, or {@code fallback} when absent (a single string counts as one). */
    public List<String> strings(String key, List<String> fallback) {
        Object v = values.get(key);
        if (v == null) {
            return fallback;
        }
        if (v instanceof String one) {
            return List.of(one);
        }
        List<String> out = new ArrayList<>();
        for (Object o : Json.asArray(v, key)) {
            if (!(o instanceof String str)) {
                throw new IllegalArgumentException(key + ": expected strings");
            }
            out.add(str);
        }
        return List.copyOf(out);
    }

    /** Returns a raw value (lists, objects), or {@code null}. */
    public Object raw(String key) {
        return values.get(key);
    }

    /** Returns the settings as canonical JSON (sorted keys) for cache keys. */
    public String canonical() {
        return Json.write(sorted(values));
    }

    private static Object sorted(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new TreeMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), sorted(e.getValue()));
            }
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object o : l) {
                out.add(sorted(o));
            }
            return out;
        }
        return v;
    }

    @Override
    public String toString() {
        return canonical().strip();
    }
}
