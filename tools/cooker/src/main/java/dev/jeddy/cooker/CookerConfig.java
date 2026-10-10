package dev.jeddy.cooker;

import java.nio.file.Path;
import java.util.List;

/**
 * Where the cooker reads and writes (DESIGN §10.3).
 *
 * <p>Thread ownership: immutable; any thread.
 *
 * @param sourceRoot the source asset tree (glTF, images, shaders, sidecars)
 * @param outputRoot the loose cooked tree, mounted by the runtime VFS in development
 * @param cacheDir the content-addressed cache and the cook state
 * @param reportDir where {@code report.json} and {@code report.html} go
 * @param threads worker threads for cook jobs and encoders
 * @param force ignore the cache and cook everything again
 * @param shaderIncludeDirs extra roots for {@code #include} (engine shaders)
 * @param validateShaders run {@code glslangValidator} when it is on the {@code PATH}
 */
public record CookerConfig(
        Path sourceRoot,
        Path outputRoot,
        Path cacheDir,
        Path reportDir,
        int threads,
        boolean force,
        List<Path> shaderIncludeDirs,
        boolean validateShaders) {
    /** Normalizes the paths. */
    public CookerConfig {
        sourceRoot = sourceRoot.toAbsolutePath().normalize();
        outputRoot = outputRoot.toAbsolutePath().normalize();
        cacheDir = cacheDir.toAbsolutePath().normalize();
        reportDir = reportDir.toAbsolutePath().normalize();
        threads = Math.max(1, threads);
        shaderIncludeDirs =
                shaderIncludeDirs.stream().map(p -> p.toAbsolutePath().normalize()).toList();
        if (outputRoot.startsWith(sourceRoot) || sourceRoot.startsWith(outputRoot)) {
            throw new IllegalArgumentException(
                    "the source and output trees must not contain each other");
        }
    }

    /**
     * Defaults: the cache next to the output ({@code <out>.cache}), the report inside the cache,
     * one thread per core, shader validation on.
     */
    public static CookerConfig of(Path sourceRoot, Path outputRoot) {
        Path out = outputRoot.toAbsolutePath().normalize();
        Path cache = out.resolveSibling(out.getFileName() + ".cache");
        return new CookerConfig(
                sourceRoot,
                out,
                cache,
                cache.resolve("report"),
                Runtime.getRuntime().availableProcessors(),
                false,
                List.of(),
                true);
    }

    /** Returns a copy with {@code force} set. */
    public CookerConfig withForce(boolean value) {
        return new CookerConfig(
                sourceRoot,
                outputRoot,
                cacheDir,
                reportDir,
                threads,
                value,
                shaderIncludeDirs,
                validateShaders);
    }

    /** Returns a copy with another thread count. */
    public CookerConfig withThreads(int value) {
        return new CookerConfig(
                sourceRoot,
                outputRoot,
                cacheDir,
                reportDir,
                value,
                force,
                shaderIncludeDirs,
                validateShaders);
    }

    /** Returns a copy with another cache directory (the report moves with it). */
    public CookerConfig withCache(Path cache) {
        return new CookerConfig(
                sourceRoot,
                outputRoot,
                cache,
                cache.resolve("report"),
                threads,
                force,
                shaderIncludeDirs,
                validateShaders);
    }

    /** Returns a copy with another report directory. */
    public CookerConfig withReport(Path report) {
        return new CookerConfig(
                sourceRoot,
                outputRoot,
                cacheDir,
                report,
                threads,
                force,
                shaderIncludeDirs,
                validateShaders);
    }

    /** Returns a copy with shader include roots and validation. */
    public CookerConfig withShaders(List<Path> includeDirs, boolean validate) {
        return new CookerConfig(
                sourceRoot, outputRoot, cacheDir, reportDir, threads, force, includeDirs, validate);
    }
}
