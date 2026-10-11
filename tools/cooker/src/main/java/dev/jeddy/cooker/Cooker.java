package dev.jeddy.cooker;

import dev.jeddy.asset.AssetPaths;
import dev.jeddy.cooker.CookCache.Entry;
import dev.jeddy.cooker.CookCache.Output;
import dev.jeddy.cooker.CookCache.SourceState;
import dev.jeddy.cooker.CookReport.Item;
import dev.jeddy.cooker.CookReport.Status;
import dev.jeddy.cooker.env.BrdfLutImporter;
import dev.jeddy.cooker.env.EnvironmentImporter;
import dev.jeddy.cooker.font.DefaultFontImporter;
import dev.jeddy.cooker.font.FontImporter;
import dev.jeddy.cooker.gltf.GltfSceneImporter;
import dev.jeddy.cooker.level.LevelImporter;
import dev.jeddy.cooker.lut.CubeLutImporter;
import dev.jeddy.cooker.material.MaterialImporter;
import dev.jeddy.cooker.physics.PhysicsImporter;
import dev.jeddy.cooker.rules.RulesImporter;
import dev.jeddy.cooker.shader.ShaderImporter;
import dev.jeddy.cooker.texture.TextureImporter;
import dev.jeddy.core.job.JobSystem;
import dev.jeddy.core.json.Json;
import dev.jeddy.core.log.Log;
import dev.jeddy.core.log.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Cooks a source tree into the loose cooked tree (TASKS M6-02, DESIGN §10.3).
 *
 * <ol>
 *   <li>Scan the source root (hidden entries and {@code .meta} sidecars excluded), load each file's
 *       {@link ImportSettings} and pick its {@link Importer} (the {@code importer} setting, else
 *       the first that accepts it). Files claimed by another source (a glTF's images and buffers)
 *       are not cooked on their own.
 *   <li>Run one job per source on the {@link JobSystem}. A job hashes the source; if the {@link
 *       CookCache} has an entry for {@code sha256(cooker version, importer and version, path,
 *       settings, bytes)} whose recorded dependencies still match, the outputs are restored from
 *       the cache, otherwise the importer cooks and the cache stores the result.
 *   <li>Outputs are written atomically and only when their content changed, so the runtime's
 *       reloader (M4-07) sees each change once. Outputs that a source no longer produces, and those
 *       of deleted sources, are removed.
 *   <li>The per-source state and the report ({@code report.json}, {@code report.html}) are written.
 * </ol>
 *
 * Cook jobs read their source and dependency files on job threads. That breaks the job system's
 * no-blocking-I/O rule on purpose: the cooker is an offline tool whose jobs are long and mostly
 * CPU-bound, and nothing latency-sensitive shares its workers.
 *
 * <p>Thread ownership: one driving thread calls {@link #cookAll} and {@link #cook}; importers run
 * on the job threads.
 */
public final class Cooker implements AutoCloseable {
    /** Cooker version; bump it to invalidate every cached result. */
    public static final int VERSION = 1;

    /** Source path of the built-in outputs (BRDF LUT). */
    static final String BUILTIN_PREFIX = "<builtin>/";

    private static final Logger LOG = Log.logger("cooker");

    private final CookerConfig config;
    private final List<Importer> importers;
    private final List<Importer> builtins;
    private final JobSystem jobs;
    private final CookCache cache;

    /**
     * A source to cook; {@code error} is set when its settings or importer could not be resolved.
     */
    private record Job(
            String source, Path file, Importer importer, ImportSettings settings, String error) {}

    /**
     * One job's outcome. {@code intended} lists the outputs the importer produced even when writing
     * them failed, so two sources aiming at the same file are both caught: on Windows the second
     * writer of a shared output fails with an I/O error (the first still holds the file), and
     * without this the first would pass as cooked.
     */
    private record Result(Item item, SourceState state, List<String> intended) {}

    /** Creates a cooker with the default importers. */
    public Cooker(CookerConfig config) throws IOException {
        this(config, defaultImporters(), List.of(new BrdfLutImporter(), new DefaultFontImporter()));
    }

    /**
     * Creates a cooker.
     *
     * @param importers importers in priority order
     * @param builtins importers that run once per cook without a source file (engine outputs)
     */
    public Cooker(CookerConfig config, List<Importer> importers, List<Importer> builtins)
            throws IOException {
        this.config = config;
        this.importers = List.copyOf(importers);
        this.builtins = List.copyOf(builtins);
        this.jobs = new JobSystem(config.threads() - 1);
        this.cache = new CookCache(config.cacheDir());
        Files.createDirectories(config.outputRoot());
    }

    /** The default importers, in priority order. */
    public static List<Importer> defaultImporters() {
        return List.of(
                new GltfSceneImporter(),
                new PhysicsImporter(),
                new LevelImporter(),
                new RulesImporter(),
                new MaterialImporter(),
                new EnvironmentImporter(),
                new CubeLutImporter(),
                new FontImporter(),
                new TextureImporter(),
                new ShaderImporter(),
                new CopyImporter());
    }

    /** Returns the configuration. */
    public CookerConfig config() {
        return config;
    }

    /** Cooks every source and removes the outputs of deleted ones. */
    public CookReport cookAll() throws IOException {
        return run(null);
    }

    /**
     * Cooks {@code sources} (relative paths; deleted ones have their outputs removed) and leaves
     * the rest of the state alone. Watch mode passes {@link #affectedBy} of the changed files.
     */
    public CookReport cook(Collection<String> sources) throws IOException {
        return run(new HashSet<>(sources));
    }

    /**
     * Returns the sources to cook again after {@code changed} files (relative to the source root)
     * were created, modified or deleted: the files themselves, sources whose sidecar or directory
     * defaults changed, and sources that recorded one of them as a dependency.
     */
    public Set<String> affectedBy(Collection<String> changed) throws IOException {
        Map<String, SourceState> state = cache.loadState();
        Set<String> out = new TreeSet<>();
        List<String> all = null;
        for (String c : changed) {
            String p = c.replace('\\', '/');
            String name = p.substring(p.lastIndexOf('/') + 1);
            if (name.equals(ImportSettings.DIRECTORY_DEFAULTS)) {
                String dir = p.substring(0, p.length() - name.length());
                if (all == null) {
                    all = scan();
                }
                for (String s : all) {
                    if (s.startsWith(dir)) {
                        out.add(s);
                    }
                }
                for (String s : state.keySet()) {
                    if (s.startsWith(dir)) {
                        out.add(s);
                    }
                }
                continue;
            }
            if (name.endsWith(ImportSettings.SIDECAR)) {
                p = p.substring(0, p.length() - ImportSettings.SIDECAR.length());
            }
            out.add(p);
            for (Map.Entry<String, SourceState> e : state.entrySet()) {
                if (e.getValue().dependencies().contains(p)) {
                    out.add(e.getKey());
                }
            }
        }
        return out;
    }

    private CookReport run(Set<String> subset) throws IOException {
        long t0 = System.nanoTime();
        Map<String, SourceState> previous = cache.loadState();
        List<Job> all = plan();
        List<Job> todo = new ArrayList<>();
        Set<String> planned = new HashSet<>();
        for (Job j : all) {
            planned.add(j.source());
            if (subset == null || subset.contains(j.source()) || j.file() == null) {
                todo.add(j);
            }
        }
        int perJob =
                Math.max(
                        1, config.threads() / Math.max(1, Math.min(config.threads(), todo.size())));
        Result[] results = new Result[todo.size()];
        jobs.parallelFor(
                (start, end, thread) -> {
                    for (int i = start; i < end; i++) {
                        results[i] =
                                cookOne(todo.get(i), previous.get(todo.get(i).source()), perJob);
                    }
                },
                todo.size(),
                1);

        // Two sources writing the same output fail together, whether or not their writes
        // succeeded (the intended outputs, not the written ones, decide).
        Map<String, List<String>> producers = new HashMap<>();
        for (Result r : results) {
            for (String o : r.intended()) {
                producers.computeIfAbsent(o, k -> new ArrayList<>()).add(r.item().source());
            }
        }
        List<Item> items = new ArrayList<>();
        Map<String, SourceState> next = new TreeMap<>(previous);
        Set<String> produced = new HashSet<>();
        for (Result r : results) {
            Item item = r.item();
            List<String> clash = new ArrayList<>();
            for (String o : r.intended()) {
                if (producers.get(o).size() > 1) {
                    clash.add(o + " (also from " + String.join(", ", producers.get(o)) + ")");
                }
            }
            if (!clash.isEmpty()) {
                String why = "outputs written by more than one source: " + String.join("; ", clash);
                item = failed(item, item.error() == null ? why : item.error() + "; " + why);
                next.remove(item.source());
            } else if (r.state() != null) {
                next.put(item.source(), r.state());
                produced.addAll(r.state().outputs());
            }
            items.add(item);
        }
        // Sources that are gone (or no longer cooked on their own): delete their outputs.
        Set<String> gone = new TreeSet<>();
        for (String s : previous.keySet()) {
            if (!planned.contains(s) && (subset == null || subset.contains(s))) {
                gone.add(s);
            }
        }
        if (subset != null) {
            for (String s : subset) {
                if (!planned.contains(s) && previous.containsKey(s)) {
                    gone.add(s);
                }
            }
        }
        for (String s : next.keySet()) {
            if (!gone.contains(s)) {
                produced.addAll(next.get(s).outputs());
            }
        }
        for (String s : gone) {
            SourceState st = next.remove(s);
            List<String> removed = new ArrayList<>();
            for (String o : st.outputs()) {
                if (!produced.contains(o) && deleteOutput(o)) {
                    removed.add(o);
                }
            }
            items.add(
                    new Item(
                            s,
                            st.importer(),
                            Status.REMOVED,
                            0,
                            removed,
                            0,
                            List.of(),
                            Map.of(),
                            null));
        }
        // Outputs a re-cooked source stopped producing.
        for (Result r : results) {
            SourceState before = previous.get(r.item().source());
            if (before != null && r.state() != null) {
                for (String o : before.outputs()) {
                    if (!produced.contains(o)) {
                        deleteOutput(o);
                    }
                }
            }
        }
        cache.saveState(next);
        items.sort((a, b) -> a.source().compareTo(b.source()));
        CookReport report = new CookReport(items, System.nanoTime() - t0);
        report.write(config.reportDir());
        for (Item item : items) {
            if (item.status() == Status.FAILED) {
                LOG.debug("{}: {}", item.source(), item.error());
            }
        }
        LOG.debug("{}", report.summary());
        return report;
    }

    /** Lists the sources (relative, {@code /}-separated) and builds their jobs. */
    private List<Job> plan() throws IOException {
        List<String> sources = scan();
        Map<String, ImportSettings> settings = new HashMap<>();
        Map<String, Importer> chosen = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();
        for (String s : sources) {
            try {
                ImportSettings st = ImportSettings.load(config.sourceRoot(), file(s));
                settings.put(s, st);
                Importer imp = select(s, st);
                if (imp != null) {
                    chosen.put(s, imp);
                }
            } catch (IOException | RuntimeException e) {
                errors.put(s, describe(e));
            }
        }
        Set<String> claimed = new HashSet<>();
        for (Map.Entry<String, Importer> e : chosen.entrySet()) {
            try {
                for (String c :
                        e.getValue()
                                .claims(
                                        config.sourceRoot(),
                                        e.getKey(),
                                        settings.get(e.getKey()))) {
                    if (!c.equals(e.getKey())) {
                        claimed.add(c);
                    }
                }
            } catch (IOException | RuntimeException ex) {
                // The source's own cook reports the problem.
                LOG.debug("{}: cannot list claimed files: {}", e.getKey(), ex.getMessage());
            }
        }
        List<Job> out = new ArrayList<>();
        for (String s : sources) {
            if (errors.containsKey(s)) {
                out.add(new Job(s, file(s), null, ImportSettings.EMPTY, errors.get(s)));
                continue;
            }
            Importer imp = chosen.get(s);
            ImportSettings st = settings.get(s);
            if (imp == null || (claimed.contains(s) && !st.bool("standalone", false))) {
                continue;
            }
            out.add(new Job(s, file(s), imp, st, null));
        }
        for (Importer b : builtins) {
            out.add(new Job(BUILTIN_PREFIX + b.name(), null, b, ImportSettings.EMPTY, null));
        }
        return out;
    }

    private Importer select(String source, ImportSettings settings) {
        String forced = settings.string("importer", null);
        if (forced != null) {
            if (forced.equals("none")) {
                return null;
            }
            for (Importer i : importers) {
                if (i.name().equals(forced)) {
                    return i;
                }
            }
            throw new IllegalArgumentException(source + ": unknown importer " + forced);
        }
        for (Importer i : importers) {
            if (i.accepts(source, settings)) {
                return i;
            }
        }
        return null;
    }

    /** Returns the relative paths of all source files, sorted. */
    List<String> scan() throws IOException {
        List<String> out = new ArrayList<>();
        walk(config.sourceRoot(), "", out);
        out.sort(null);
        return out;
    }

    private static void walk(Path dir, String prefix, List<String> out) throws IOException {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String name = p.getFileName().toString();
                if (name.startsWith(".") || name.endsWith(ImportSettings.SIDECAR)) {
                    continue;
                }
                if (Files.isDirectory(p)) {
                    walk(p, prefix + name + "/", out);
                } else if (Files.isRegularFile(p)) {
                    out.add(prefix + name);
                }
            }
        }
    }

    private Path file(String source) {
        return config.sourceRoot().resolve(source);
    }

    private Result cookOne(Job job, SourceState before, int threads) {
        long t0 = System.nanoTime();
        if (job.error() != null) {
            return new Result(
                    new Item(
                            job.source(),
                            "?",
                            Status.FAILED,
                            0,
                            List.of(),
                            0,
                            List.of(),
                            Map.of(),
                            job.error()),
                    null,
                    List.of());
        }
        String importerName = job.importer().name();
        List<String> intended = List.of();
        try {
            byte[] bytes = job.file() == null ? new byte[0] : Files.readAllBytes(job.file());
            String key = key(job, bytes);
            Entry hit = config.force() ? null : cache.lookup(key);
            if (hit != null && dependenciesUnchanged(hit)) {
                long size = 0;
                List<String> outs = new ArrayList<>();
                boolean sameKey = before != null && before.key().equals(key);
                for (Output o : hit.outputs()) {
                    restore(o, sameKey);
                    outs.add(o.path());
                    size += o.size();
                }
                SourceState st =
                        new SourceState(
                                key,
                                importerName,
                                outs,
                                new ArrayList<>(hit.dependencies().keySet()));
                return new Result(
                        new Item(
                                job.source(),
                                importerName,
                                Status.CACHED,
                                System.nanoTime() - t0,
                                outs,
                                size,
                                hit.warnings(),
                                hit.stats(),
                                null),
                        st,
                        outs);
            }
            CookContext ctx =
                    new CookContext(
                            config, job.source(), job.file(), bytes, job.settings(), jobs, threads);
            job.importer().cook(ctx);
            intended = new ArrayList<>(ctx.outputs().keySet());
            List<Output> outputs = new ArrayList<>();
            long size = 0;
            for (Map.Entry<String, byte[]> e : ctx.outputs().entrySet()) {
                byte[] data = e.getValue();
                Output o = new Output(e.getKey(), CookCache.sha256(data), data.length);
                outputs.add(o);
                writeOutput(o, data);
                size += data.length;
            }
            Map<String, String> deps = new TreeMap<>(ctx.dependencies());
            // Statistics go through JSON so a cooked item reports exactly what a cached one will.
            Map<String, Object> stats = Json.asObject(Json.parse(Json.write(ctx.stats())), "stats");
            Entry entry =
                    new Entry(importerName, outputs, deps, List.copyOf(ctx.warnings()), stats);
            cache.store(key, entry, ctx.outputs());
            List<String> outs = outputs.stream().map(Output::path).toList();
            return new Result(
                    new Item(
                            job.source(),
                            importerName,
                            Status.COOKED,
                            System.nanoTime() - t0,
                            outs,
                            size,
                            entry.warnings(),
                            entry.stats(),
                            null),
                    new SourceState(key, importerName, outs, new ArrayList<>(deps.keySet())),
                    outs);
        } catch (Exception | LinkageError | AssertionError e) {
            LOG.debug("{} failed", job.source());
            return new Result(
                    new Item(
                            job.source(),
                            importerName,
                            Status.FAILED,
                            System.nanoTime() - t0,
                            List.of(),
                            0,
                            List.of(),
                            Map.of(),
                            describe(e)),
                    null,
                    intended);
        }
    }

    private static Item failed(Item item, String error) {
        return new Item(
                item.source(),
                item.importer(),
                Status.FAILED,
                item.nanos(),
                List.of(),
                0,
                item.warnings(),
                item.stats(),
                error);
    }

    private static String describe(Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (!sb.isEmpty()) {
                sb.append(" <- ");
            }
            sb.append(t.getClass().getSimpleName());
            if (t.getMessage() != null) {
                sb.append(": ").append(t.getMessage());
            }
            if (t.getCause() == t) {
                break;
            }
        }
        StackTraceElement[] st = e.getStackTrace();
        if (st.length > 0) {
            sb.append(" (at ").append(st[0]).append(')');
        }
        return sb.toString();
    }

    private String key(Job job, byte[] bytes) {
        MessageDigest d = CookCache.digest();
        String header =
                "jeddy-cook "
                        + VERSION
                        + "\n"
                        + job.importer().name()
                        + " "
                        + job.importer().version()
                        + "\n"
                        + job.source()
                        + "\n"
                        + job.settings().canonical()
                        + "\n";
        d.update(header.getBytes(StandardCharsets.UTF_8));
        d.update(bytes);
        return HexFormat.of().formatHex(d.digest());
    }

    private boolean dependenciesUnchanged(Entry e) throws IOException {
        for (Map.Entry<String, String> d : e.dependencies().entrySet()) {
            Path p = Path.of(d.getKey());
            Path file = p.isAbsolute() ? p : config.sourceRoot().resolve(d.getKey());
            if (!Files.isRegularFile(file)
                    || !CookCache.sha256(Files.readAllBytes(file)).equals(d.getValue())) {
                return false;
            }
        }
        return true;
    }

    /** Puts a cached output in place unless the file already has that content. */
    private void restore(Output o, boolean trustSize) throws IOException {
        Path target = outputFile(o.path());
        if (Files.isRegularFile(target) && Files.size(target) == o.size()) {
            if (trustSize || CookCache.sha256(Files.readAllBytes(target)).equals(o.sha256())) {
                return;
            }
        }
        CookCache.writeAtomically(target, cache.object(o.sha256()));
    }

    private void writeOutput(Output o, byte[] data) throws IOException {
        Path target = outputFile(o.path());
        if (Files.isRegularFile(target)
                && Files.size(target) == data.length
                && CookCache.sha256(Files.readAllBytes(target)).equals(o.sha256())) {
            return; // unchanged: don't wake the runtime's reloader
        }
        CookCache.writeAtomically(target, data);
    }

    private Path outputFile(String path) {
        if (!AssetPaths.isNormalized(path)) {
            throw new IllegalArgumentException("output path is not normalized: " + path);
        }
        Path p = config.outputRoot().resolve(path).normalize();
        if (!p.startsWith(config.outputRoot())) {
            throw new IllegalArgumentException("output escapes the output root: " + path);
        }
        return p;
    }

    /** Deletes an output and the directories it leaves empty. */
    private boolean deleteOutput(String path) {
        try {
            Path p = outputFile(path);
            boolean deleted = Files.deleteIfExists(p);
            for (Path d = p.getParent();
                    d != null
                            && !d.equals(config.outputRoot())
                            && d.startsWith(config.outputRoot());
                    d = d.getParent()) {
                try (Stream<Path> s = Files.list(d)) {
                    if (s.findAny().isPresent()) {
                        break;
                    }
                }
                Files.deleteIfExists(d);
            }
            return deleted;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Deletes cache entries and objects that the current state does not use.
     *
     * @return the number of files deleted
     */
    public int pruneCache() throws IOException {
        Set<String> keep = new HashSet<>();
        for (SourceState s : cache.loadState().values()) {
            keep.add(s.key());
        }
        return cache.prune(keep);
    }

    @Override
    public void close() {
        jobs.close();
    }
}
