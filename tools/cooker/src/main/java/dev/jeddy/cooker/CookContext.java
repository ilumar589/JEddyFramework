package dev.jeddy.cooker;

import dev.jeddy.asset.AssetPaths;
import dev.jeddy.core.job.JobSystem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What an {@link Importer} sees while cooking one source: its bytes and settings, and where it puts
 * outputs, dependencies, warnings and report statistics. Importers may fan work out on {@link
 * #jobs()}; the output, dependency, warning and statistic methods are thread-safe.
 *
 * <p>Thread ownership: one cook job; the recording methods may be called from its helper jobs.
 */
public final class CookContext {
    private final CookerConfig config;
    private final String path;
    private final Path file;
    private final byte[] bytes;
    private final ImportSettings settings;
    private final JobSystem jobs;
    private final int threads;
    private final Map<String, byte[]> outputs = new LinkedHashMap<>();
    private final Map<String, String> dependencies = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private final Map<String, Object> stats = new LinkedHashMap<>();

    CookContext(
            CookerConfig config,
            String path,
            Path file,
            byte[] bytes,
            ImportSettings settings,
            JobSystem jobs,
            int threads) {
        this.config = config;
        this.path = path;
        this.file = file;
        this.bytes = bytes;
        this.settings = settings;
        this.jobs = jobs;
        this.threads = threads;
    }

    /** Returns the source path relative to the source root ({@code /}-separated, original case). */
    public String path() {
        return path;
    }

    /** Returns the source file. */
    public Path file() {
        return file;
    }

    /** Returns the source bytes. */
    public byte[] bytes() {
        return bytes;
    }

    /** Returns the merged import settings. */
    public ImportSettings settings() {
        return settings;
    }

    /** Returns the cooker configuration. */
    public CookerConfig config() {
        return config;
    }

    /** Returns the job system for fanning out work (textures of a glTF, LOD builds). */
    public JobSystem jobs() {
        return jobs;
    }

    /** Returns how many threads one native encoder call in this job should use. */
    public int threads() {
        return threads;
    }

    /**
     * Returns the normalized output path of this source without its extension, for example {@code
     * models/crate} for {@code Models/Crate.glb}.
     */
    public String outputBase() {
        String p = path;
        int slash = p.lastIndexOf('/');
        int dot = p.indexOf('.', slash + 1);
        if (dot > slash + 1) {
            p = p.substring(0, dot);
        }
        return AssetPaths.normalize(sanitize(p));
    }

    /** Replaces characters that do not belong in asset paths with {@code _}. */
    public static String sanitize(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok =
                    (c >= 'a' && c <= 'z')
                            || (c >= 'A' && c <= 'Z')
                            || (c >= '0' && c <= '9')
                            || c == '_'
                            || c == '-'
                            || c == '.'
                            || c == '/';
            sb.append(ok ? c : '_');
        }
        return sb.toString();
    }

    /**
     * Reads another file this source depends on and records its hash, so the source is cooked again
     * when it changes.
     */
    public byte[] readDependency(Path dependency) throws IOException {
        byte[] data = Files.readAllBytes(dependency);
        recordDependency(dependency, data);
        return data;
    }

    /** Records a dependency whose bytes were read elsewhere. */
    public void recordDependency(Path dependency, byte[] data) {
        Path abs = dependency.toAbsolutePath().normalize();
        String key =
                abs.startsWith(config.sourceRoot())
                        ? config.sourceRoot().relativize(abs).toString().replace('\\', '/')
                        : abs.toString();
        String hash = CookCache.sha256(data);
        synchronized (dependencies) {
            dependencies.put(key, hash);
        }
    }

    /**
     * Writes an output. {@code outputPath} is normalized ({@link AssetPaths}); writing the same
     * path twice from one source is an error.
     */
    public void output(String outputPath, byte[] data) {
        String p = AssetPaths.normalize(outputPath);
        synchronized (outputs) {
            if (outputs.putIfAbsent(p, data) != null) {
                throw new IllegalStateException(path + " writes " + p + " twice");
            }
        }
    }

    /** Adds a report warning. */
    public void warn(String message) {
        synchronized (warnings) {
            warnings.add(message);
        }
    }

    /** Adds a report statistic ({@code String}, {@code Number}, {@code Boolean}, lists, maps). */
    public void stat(String key, Object value) {
        synchronized (stats) {
            stats.put(key, value);
        }
    }

    Map<String, byte[]> outputs() {
        return Collections.unmodifiableMap(outputs);
    }

    Map<String, String> dependencies() {
        return dependencies;
    }

    List<String> warnings() {
        return warnings;
    }

    Map<String, Object> stats() {
        return stats;
    }
}
