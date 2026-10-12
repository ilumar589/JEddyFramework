package dev.jeddy.cooker;

import dev.jeddy.cooker.pak.PakBuilder;
import dev.jeddy.cooker.watch.SourceWatcher;
import dev.jeddy.core.log.Log;
import dev.jeddy.core.log.LogLevel;

import java.io.PrintStream;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The {@code jeddy-cook} command line (TASKS M6-02, M6-08, M6-10).
 *
 * <pre>
 * jeddy-cook [cook] --source DIR --out DIR [--cache DIR] [--report DIR] [--threads N]
 *                   [--force] [--prune] [--watch] [--shader-include DIR]... [--no-validate]
 * jeddy-cook pak    --cooked DIR --out DIR [--layout FILE] [--level N] [--threads N] [--no-names]
 * </pre>
 *
 * Exit status: 0 on success, 1 if a source failed, 2 on bad arguments.
 *
 * <p>Thread ownership: the main thread.
 */
public final class CookerMain {
    private CookerMain() {}

    /** Entry point. */
    public static void main(String[] args) throws Exception {
        System.exit(run(args, System.out));
    }

    /** Runs the command line and returns the exit status (tests call this). */
    public static int run(String[] args, PrintStream out) throws Exception {
        List<String> a = new ArrayList<>(List.of(args));
        String command = !a.isEmpty() && !a.get(0).startsWith("--") ? a.remove(0) : "cook";
        try {
            return switch (command) {
                case "cook" -> cook(a, out);
                case "pak" -> pak(a, out);
                case "bindings" -> bindings(a, out);
                case "help", "--help", "-h" -> {
                    usage(out);
                    yield 0;
                }
                default -> throw new IllegalArgumentException("unknown command " + command);
            };
        } catch (IllegalArgumentException e) {
            out.println("jeddy-cook: " + e.getMessage());
            usage(out);
            return 2;
        }
    }

    private static void usage(PrintStream out) {
        out.println(
                "usage: jeddy-cook [cook] --source DIR --out DIR [--cache DIR] [--report DIR]"
                        + " [--threads N]");
        out.println(
                "                         [--force] [--prune] [--watch] [--shader-include DIR]..."
                        + " [--no-validate]");
        out.println("                         [--components FILE]");
        out.println(
                "       jeddy-cook pak --cooked DIR --out DIR [--layout FILE] [--level N]"
                        + " [--threads N] [--no-names]");
        out.println("       jeddy-cook bindings --source DIR --out DIR --package NAME");
    }

    private static int cook(List<String> a, PrintStream out) throws Exception {
        Path source = null;
        Path output = null;
        Path cache = null;
        Path report = null;
        int threads = Runtime.getRuntime().availableProcessors();
        boolean force = false;
        boolean prune = false;
        boolean watch = false;
        boolean validate = true;
        Path components = null;
        List<Path> includes = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) {
            String opt = a.get(i);
            switch (opt) {
                case "--components" -> components = Path.of(value(a, ++i, opt));
                case "--source" -> source = Path.of(value(a, ++i, opt));
                case "--out" -> output = Path.of(value(a, ++i, opt));
                case "--cache" -> cache = Path.of(value(a, ++i, opt));
                case "--report" -> report = Path.of(value(a, ++i, opt));
                case "--threads" -> threads = Integer.parseInt(value(a, ++i, opt));
                case "--shader-include" -> includes.add(Path.of(value(a, ++i, opt)));
                case "--force" -> force = true;
                case "--prune" -> prune = true;
                case "--watch" -> watch = true;
                case "--no-validate" -> validate = false;
                case "--verbose" -> Log.setThreshold(LogLevel.DEBUG);
                default -> throw new IllegalArgumentException("unknown option " + opt);
            }
        }
        if (source == null || output == null) {
            throw new IllegalArgumentException("--source and --out are required");
        }
        if (!Files.isDirectory(source)) {
            throw new IllegalArgumentException("source directory " + source + " does not exist");
        }
        CookerConfig config = CookerConfig.of(source, output).withThreads(threads).withForce(force);
        if (cache != null) {
            config = config.withCache(cache);
        }
        if (report != null) {
            config = config.withReport(report);
        }
        config = config.withShaders(includes, validate).withComponents(components);
        try (Cooker cooker = new Cooker(config)) {
            CookReport r = cooker.cookAll();
            print(r, out);
            if (prune) {
                out.println("pruned " + cooker.pruneCache() + " cache files");
            }
            if (!watch) {
                return r.failed() ? 1 : 0;
            }
            out.println("watching " + config.sourceRoot() + " (Ctrl+C to stop)");
            try (SourceWatcher watcher = new SourceWatcher(config.sourceRoot())) {
                while (!Thread.currentThread().isInterrupted()) {
                    Set<String> changed = watcher.poll(1, TimeUnit.HOURS, 150);
                    if (changed.isEmpty()) {
                        continue;
                    }
                    CookReport w =
                            changed.contains(SourceWatcher.EVERYTHING)
                                    ? cooker.cookAll()
                                    : cooker.cook(cooker.affectedBy(changed));
                    print(w, out);
                }
            } catch (ClosedWatchServiceException | InterruptedException e) {
                // shutting down
            }
            return 0;
        }
    }

    private static void print(CookReport r, PrintStream out) {
        for (CookReport.Item i : r.items()) {
            if (i.status() == CookReport.Status.CACHED) {
                continue;
            }
            out.printf(
                    Locale.ROOT,
                    "%-8s %-11s %7.1f ms  %s%n",
                    i.status().name().toLowerCase(Locale.ROOT),
                    i.importer(),
                    i.nanos() / 1e6,
                    i.source());
            if (i.error() != null) {
                out.println("         " + i.error());
            }
            for (String w : i.warnings()) {
                out.println("         warning: " + w);
            }
        }
        out.println(r.summary());
    }

    /**
     * {@code bindings --source DIR --out DIR --package NAME}: writes one {@code <Name>Level.java}
     * per {@code .jlevel} under the source tree into {@code out} (DESIGN §22, D110).
     */
    private static int bindings(List<String> a, PrintStream out) throws Exception {
        Path source = null;
        Path output = null;
        String pkg = null;
        for (int i = 0; i < a.size(); i++) {
            String opt = a.get(i);
            switch (opt) {
                case "--source" -> source = Path.of(value(a, ++i, opt));
                case "--out" -> output = Path.of(value(a, ++i, opt));
                case "--package" -> pkg = value(a, ++i, opt);
                default -> throw new IllegalArgumentException("unknown option " + opt);
            }
        }
        if (source == null || output == null || pkg == null) {
            throw new IllegalArgumentException("--source, --out and --package are required");
        }
        int n = dev.jeddy.cooker.level.LevelBindings.generateAll(source, output, pkg);
        out.println("wrote " + n + " level binding" + (n == 1 ? "" : "s") + " to " + output);
        return 0;
    }

    private static int pak(List<String> a, PrintStream out) throws Exception {
        Path cooked = null;
        Path output = null;
        Path layoutFile = null;
        Integer level = null;
        boolean names = true;
        int threads = Runtime.getRuntime().availableProcessors();
        for (int i = 0; i < a.size(); i++) {
            String opt = a.get(i);
            switch (opt) {
                case "--cooked" -> cooked = Path.of(value(a, ++i, opt));
                case "--out" -> output = Path.of(value(a, ++i, opt));
                case "--layout" -> layoutFile = Path.of(value(a, ++i, opt));
                case "--level" -> level = Integer.parseInt(value(a, ++i, opt));
                case "--threads" -> threads = Integer.parseInt(value(a, ++i, opt));
                case "--no-names" -> names = false;
                default -> throw new IllegalArgumentException("unknown option " + opt);
            }
        }
        if (cooked == null || output == null) {
            throw new IllegalArgumentException("--cooked and --out are required");
        }
        PakBuilder.Layout layout =
                layoutFile != null && Files.isRegularFile(layoutFile)
                        ? PakBuilder.Layout.read(layoutFile)
                        : PakBuilder.Layout.DEFAULT;
        layout =
                new PakBuilder.Layout(
                        layout.groups(),
                        layout.defaultName(),
                        level != null ? level : layout.level(),
                        names && layout.names(),
                        layout.dictionarySize(),
                        layout.dictionaryMaxEntry(),
                        layout.dictionaryMinSamples());
        for (PakBuilder.PakInfo p : PakBuilder.build(cooked, output, layout, threads)) {
            out.printf(
                    Locale.ROOT,
                    "%-16s %5d entries  %10d -> %10d bytes (%.1f%%)  dictionary %d B from %d"
                            + " samples  %.2f s%n",
                    p.name(),
                    p.entries(),
                    p.rawBytes(),
                    p.fileBytes(),
                    p.rawBytes() == 0 ? 100.0 : 100.0 * p.fileBytes() / p.rawBytes(),
                    p.dictionaryBytes(),
                    p.dictionarySamples(),
                    p.nanos() / 1e9);
        }
        return 0;
    }

    private static String value(List<String> a, int i, String opt) {
        if (i >= a.size()) {
            throw new IllegalArgumentException(opt + " needs a value");
        }
        return a.get(i);
    }
}
