package dev.jeddy.cooker;

import dev.jeddy.core.json.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The content-addressed cook cache (DESIGN §10.3): {@code entries/<key>.json} records what a cook
 * produced for a key ({@code sha256} of cooker and importer versions, the source path, its settings
 * and bytes), {@code objects/<aa>/<sha256>} holds every output once by content, and {@code
 * state.json} remembers, per source, the outputs and dependencies of its latest cook (for
 * stale-output removal and watch mode). A hit also checks that every recorded dependency still has
 * its recorded hash.
 *
 * <p>Thread ownership: {@link #lookup}, {@link #store} and {@link #object} are thread-safe (files
 * are written to temporary names and moved into place); state is loaded and saved by the cooker's
 * driving thread.
 */
final class CookCache {
    /** One output of a cached cook. */
    record Output(String path, String sha256, long size) {}

    /** A cached cook. */
    record Entry(
            String importer,
            List<Output> outputs,
            Map<String, String> dependencies,
            List<String> warnings,
            Map<String, Object> stats) {}

    /** What the latest cook of one source produced. */
    record SourceState(
            String key, String importer, List<String> outputs, List<String> dependencies) {}

    private final Path dir;

    CookCache(Path dir) throws IOException {
        this.dir = dir;
        Files.createDirectories(dir.resolve("entries"));
        Files.createDirectories(dir.resolve("objects"));
    }

    /** Returns the hex SHA-256 of {@code data}. */
    static String sha256(byte[] data) {
        return HexFormat.of().formatHex(digest().digest(data));
    }

    /** Returns a new SHA-256 digest. */
    static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Returns the cached cook for {@code key}, or {@code null} (also when an object is missing).
     */
    Entry lookup(String key) throws IOException {
        Path file = entryFile(key);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        Map<String, Object> json;
        try {
            json = Json.asObject(Json.parse(Files.readAllBytes(file)), "cache entry");
        } catch (RuntimeException e) {
            return null; // a damaged entry is a miss
        }
        List<Output> outputs = new ArrayList<>();
        for (Object o : Json.asArray(json.get("outputs"), "outputs")) {
            Map<String, Object> m = Json.asObject(o, "output");
            Output out =
                    new Output(
                            Json.string(m, "path", ""),
                            Json.string(m, "sha256", ""),
                            (long) Json.number(m, "size", -1));
            if (!Files.isRegularFile(objectFile(out.sha256()))) {
                return null;
            }
            outputs.add(out);
        }
        Map<String, String> deps = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e :
                Json.asObject(json.get("dependencies"), "dependencies").entrySet()) {
            deps.put(e.getKey(), String.valueOf(e.getValue()));
        }
        List<String> warnings = new ArrayList<>();
        for (Object w : Json.asArray(json.getOrDefault("warnings", List.of()), "warnings")) {
            warnings.add(String.valueOf(w));
        }
        return new Entry(
                Json.string(json, "importer", ""),
                outputs,
                deps,
                warnings,
                Json.asObject(json.getOrDefault("stats", Map.of()), "stats"));
    }

    /** Stores a cook's outputs and entry. */
    void store(String key, Entry entry, Map<String, byte[]> data) throws IOException {
        for (Output o : entry.outputs()) {
            Path obj = objectFile(o.sha256());
            if (!Files.isRegularFile(obj)) {
                writeAtomically(obj, data.get(o.path()));
            }
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("importer", entry.importer());
        List<Object> outs = new ArrayList<>();
        for (Output o : entry.outputs()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", o.path());
            m.put("sha256", o.sha256());
            m.put("size", o.size());
            outs.add(m);
        }
        json.put("outputs", outs);
        json.put("dependencies", new TreeMap<>(entry.dependencies()));
        json.put("warnings", entry.warnings());
        json.put("stats", entry.stats());
        writeAtomically(entryFile(key), Json.write(json).getBytes(StandardCharsets.UTF_8));
    }

    /** Returns the bytes of a stored output. */
    byte[] object(String sha256) throws IOException {
        return Files.readAllBytes(objectFile(sha256));
    }

    Path objectFile(String sha256) {
        return dir.resolve("objects").resolve(sha256.substring(0, 2)).resolve(sha256);
    }

    private Path entryFile(String key) {
        return dir.resolve("entries").resolve(key + ".json");
    }

    /** Loads the per-source state of the previous cook (empty if there is none). */
    Map<String, SourceState> loadState() throws IOException {
        Path file = dir.resolve("state.json");
        Map<String, SourceState> out = new TreeMap<>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try {
            Map<String, Object> json = Json.asObject(Json.parse(Files.readAllBytes(file)), "state");
            for (Map.Entry<String, Object> e :
                    Json.asObject(json.get("sources"), "sources").entrySet()) {
                Map<String, Object> s = Json.asObject(e.getValue(), e.getKey());
                out.put(
                        e.getKey(),
                        new SourceState(
                                Json.string(s, "key", ""),
                                Json.string(s, "importer", ""),
                                strings(s.get("outputs")),
                                strings(s.get("dependencies"))));
            }
        } catch (RuntimeException e) {
            out.clear(); // a damaged state only costs stale-output tracking
        }
        return out;
    }

    /** Saves the per-source state. */
    void saveState(Map<String, SourceState> state) throws IOException {
        Map<String, Object> sources = new TreeMap<>();
        for (Map.Entry<String, SourceState> e : state.entrySet()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("key", e.getValue().key());
            s.put("importer", e.getValue().importer());
            s.put("outputs", e.getValue().outputs());
            s.put("dependencies", e.getValue().dependencies());
            sources.put(e.getKey(), s);
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("version", Cooker.VERSION);
        json.put("sources", sources);
        writeAtomically(
                dir.resolve("state.json"), Json.write(json).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Deletes entries not used by {@code keep} and objects no remaining entry references.
     *
     * @return the number of files deleted
     */
    int prune(Set<String> keep) throws IOException {
        int deleted = 0;
        Set<String> live = new HashSet<>();
        try (Stream<Path> entries = Files.list(dir.resolve("entries"))) {
            for (Path p : entries.toList()) {
                String name = p.getFileName().toString();
                String key = name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
                if (keep.contains(key)) {
                    Entry e = lookup(key);
                    if (e != null) {
                        for (Output o : e.outputs()) {
                            live.add(o.sha256());
                        }
                        continue;
                    }
                }
                Files.deleteIfExists(p);
                deleted++;
            }
        }
        try (Stream<Path> objects = Files.walk(dir.resolve("objects"))) {
            for (Path p : objects.filter(Files::isRegularFile).toList()) {
                if (!live.contains(p.getFileName().toString())) {
                    Files.deleteIfExists(p);
                    deleted++;
                }
            }
        }
        return deleted;
    }

    private static List<String> strings(Object v) {
        List<String> out = new ArrayList<>();
        if (v != null) {
            for (Object o : Json.asArray(v, "list")) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }

    /** Writes {@code data} to a temporary sibling and moves it over {@code target}. */
    static void writeAtomically(Path target, byte[] data) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), ".tmp-", ".part");
        try {
            Files.write(tmp, data);
            try {
                Files.move(
                        tmp,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (NoSuchFileException ignored) {
                // moved
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
