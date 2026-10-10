package dev.jeddy.cooker.pak;

import dev.jeddy.asset.AssetPaths;
import dev.jeddy.asset.pak.Pak;
import dev.jeddy.asset.pak.PakWriter;
import dev.jeddy.compress.Zstd;
import dev.jeddy.compress.ZstdDictionary;
import dev.jeddy.compress.ZstdException;
import dev.jeddy.core.json.Json;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Packs a cooked tree into {@code .jpak} archives (TASKS M6-08, DESIGN §10.1): files are grouped by
 * the layout's glob patterns (first match wins; the rest go to the default pak), compressed at zstd
 * level 19, and each pak with enough small entries (materials, scenes, data) gets a zstd dictionary
 * trained on them. Every pak is verified (CRC32C of each entry) after it is written, and {@code
 * paks.json} lists the paks in mount order.
 *
 * <p>Layout file ({@code paks.json} in the source root by convention):
 *
 * <pre>{@code
 * {
 *   "default": "data",
 *   "level": 19,
 *   "names": true,
 *   "dictionary": {"size": 65536, "maxEntry": 16384, "minSamples": 32},
 *   "paks": [
 *     {"name": "shared", "include": ["engine/**", "shaders/**"]},
 *     {"name": "area_tavern", "include": ["areas/tavern/**"]},
 *     {"name": "audio", "include": ["**}{@code /*.ogg"], "compression": "store"}
 *   ]
 * }
 * }</pre>
 *
 * <p>Thread ownership: one thread per build.
 */
public final class PakBuilder {
    private PakBuilder() {}

    /** A pak and the patterns that select its files. */
    public record Group(String name, List<Glob> include, PakWriter.Compression compression) {}

    /**
     * How files are split into paks.
     *
     * @param dictionarySize trained dictionary capacity in bytes ({@code 0} disables training)
     * @param dictionaryMaxEntry entries up to this size are training samples
     * @param dictionaryMinSamples train only with at least this many samples
     */
    public record Layout(
            List<Group> groups,
            String defaultName,
            int level,
            boolean names,
            int dictionarySize,
            int dictionaryMaxEntry,
            int dictionaryMinSamples) {
        /** Everything in {@code data.jpak} at level 19 with a dictionary. */
        public static final Layout DEFAULT =
                new Layout(List.of(), "data", Zstd.RELEASE_LEVEL, true, 64 << 10, 16 << 10, 32);

        /** Reads a layout file. */
        public static Layout read(Path file) throws IOException {
            Map<String, Object> json =
                    Json.asObject(Json.parse(Files.readAllBytes(file)), file.toString());
            List<Group> groups = new ArrayList<>();
            Object paks = json.get("paks");
            if (paks != null) {
                for (Object o : Json.asArray(paks, "paks")) {
                    Map<String, Object> g = Json.asObject(o, "pak");
                    List<Glob> include = new ArrayList<>();
                    for (Object p : Json.asArray(g.get("include"), "include")) {
                        include.add(Glob.of(String.valueOf(p)));
                    }
                    groups.add(
                            new Group(
                                    name(Json.string(g, "name", null)),
                                    include,
                                    PakWriter.Compression.valueOf(
                                            Json.string(g, "compression", "auto")
                                                    .toUpperCase(Locale.ROOT))));
                }
            }
            Map<String, Object> dict =
                    json.get("dictionary") == null
                            ? Map.of()
                            : Json.asObject(json.get("dictionary"), "dictionary");
            return new Layout(
                    groups,
                    name(Json.string(json, "default", DEFAULT.defaultName())),
                    Json.integer(json, "level", DEFAULT.level()),
                    Json.bool(json, "names", true),
                    Json.integer(dict, "size", DEFAULT.dictionarySize()),
                    Json.integer(dict, "maxEntry", DEFAULT.dictionaryMaxEntry()),
                    Json.integer(dict, "minSamples", DEFAULT.dictionaryMinSamples()));
        }

        private static String name(String n) {
            if (n == null || !n.matches("[a-z0-9_\\-]+")) {
                throw new IllegalArgumentException(
                        "pak names are lower-case letters, digits, _ and -: " + n);
            }
            return n;
        }
    }

    /** What was written. */
    public record PakInfo(
            String name,
            Path file,
            int entries,
            long rawBytes,
            long fileBytes,
            int dictionaryBytes,
            int dictionarySamples,
            long nanos) {}

    /**
     * Packs every file under {@code cooked} into {@code outDir}.
     *
     * @param threads zstd worker threads for large entries
     */
    public static List<PakInfo> build(Path cooked, Path outDir, Layout layout, int threads)
            throws IOException {
        Map<String, List<String>> assignment = new LinkedHashMap<>();
        Map<String, PakWriter.Compression> modes = new LinkedHashMap<>();
        for (Group g : layout.groups()) {
            assignment.put(g.name(), new ArrayList<>());
            modes.put(g.name(), g.compression());
        }
        assignment.putIfAbsent(layout.defaultName(), new ArrayList<>());
        modes.putIfAbsent(layout.defaultName(), PakWriter.Compression.AUTO);
        List<String> files;
        try (Stream<Path> walk = Files.walk(cooked)) {
            files =
                    walk.filter(Files::isRegularFile)
                            .map(p -> cooked.relativize(p).toString().replace('\\', '/'))
                            .filter(p -> !p.startsWith(".") && !p.contains("/."))
                            .sorted()
                            .toList();
        }
        for (String f : files) {
            if (!AssetPaths.isNormalized(f)) {
                throw new IOException(
                        f + " is not a normalized asset path; was it written by the cooker?");
            }
            String target = layout.defaultName();
            outer:
            for (Group g : layout.groups()) {
                for (Glob glob : g.include()) {
                    if (glob.matches(f)) {
                        target = g.name();
                        break outer;
                    }
                }
            }
            assignment.get(target).add(f);
        }
        Files.createDirectories(outDir);
        List<PakInfo> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : assignment.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            out.add(
                    write(
                            cooked,
                            outDir,
                            e.getKey(),
                            e.getValue(),
                            modes.get(e.getKey()),
                            layout,
                            threads));
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        List<Object> paks = new ArrayList<>();
        for (PakInfo p : out) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.name());
            m.put("file", p.file().getFileName().toString());
            m.put("entries", p.entries());
            m.put("rawBytes", p.rawBytes());
            m.put("fileBytes", p.fileBytes());
            m.put("dictionaryBytes", p.dictionaryBytes());
            paks.add(m);
        }
        manifest.put("paks", paks);
        Files.writeString(
                outDir.resolve("paks.json"), Json.write(manifest), StandardCharsets.UTF_8);
        return out;
    }

    private static PakInfo write(
            Path cooked,
            Path outDir,
            String name,
            List<String> files,
            PakWriter.Compression mode,
            Layout layout,
            int threads)
            throws IOException {
        long t0 = System.nanoTime();
        Path target = outDir.resolve(name + ".jpak");
        long raw = 0;
        int dictBytes = 0;
        int samples = 0;
        try (PakWriter w = new PakWriter(layout.level()).names(layout.names())) {
            w.workers(threads > 1 ? threads : 0);
            if (mode != PakWriter.Compression.STORE && layout.dictionarySize() > 0) {
                List<byte[]> sample = new ArrayList<>();
                for (String f : files) {
                    Path p = cooked.resolve(f);
                    if (Files.size(p) <= layout.dictionaryMaxEntry() && trainable(f)) {
                        sample.add(Files.readAllBytes(p));
                    }
                }
                if (sample.size() >= layout.dictionaryMinSamples()) {
                    byte[] dict = train(sample, layout.dictionarySize());
                    if (dict != null) {
                        w.dictionary(dict);
                        dictBytes = dict.length;
                        samples = sample.size();
                    }
                }
            }
            for (String f : files) {
                Path p = cooked.resolve(f);
                raw += Files.size(p);
                PakWriter.Compression m = mode;
                if (m == PakWriter.Compression.ZSTD_DICTIONARY && dictBytes == 0) {
                    m = PakWriter.Compression.ZSTD;
                }
                w.add(f, p, m);
            }
            w.write(target);
        }
        try (Pak pak = Pak.open(target)) {
            for (int i = 0; i < pak.size(); i++) {
                if (!pak.verify(i)) {
                    throw new IOException(
                            target
                                    + ": entry "
                                    + pak.name(i)
                                    + " fails its checksum after writing");
                }
            }
        }
        return new PakInfo(
                name,
                target,
                files.size(),
                raw,
                Files.size(target),
                dictBytes,
                samples,
                System.nanoTime() - t0);
    }

    /** Already-compressed formats don't help a dictionary. */
    private static boolean trainable(String path) {
        return !(path.endsWith(".ktx2")
                || path.endsWith(".ogg")
                || path.endsWith(".mp3")
                || path.endsWith(".flac"));
    }

    private static byte[] train(List<byte[]> samples, int capacity) {
        long total = 0;
        for (byte[] s : samples) {
            total += s.length;
        }
        try (Arena a = Arena.ofConfined()) {
            MemorySegment buf = a.allocate(Math.max(1, total));
            long[] sizes = new long[samples.size()];
            long at = 0;
            for (int i = 0; i < samples.size(); i++) {
                byte[] s = samples.get(i);
                MemorySegment.copy(s, 0, buf, ValueLayout.JAVA_BYTE, at, s.length);
                sizes[i] = s.length;
                at += s.length;
            }
            return ZstdDictionary.train(
                    buf, sizes, (int) Math.min(capacity, Math.max(1024, total / 4)));
        } catch (ZstdException e) {
            return null; // too few or too uniform samples: no dictionary
        }
    }
}
