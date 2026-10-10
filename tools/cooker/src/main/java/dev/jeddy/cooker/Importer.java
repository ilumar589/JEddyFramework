package dev.jeddy.cooker;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Cooks one kind of source file (DESIGN §10.3). Importers are stateless: the {@link Cooker} calls
 * {@link #cook} on job threads, possibly for several sources at once.
 *
 * <p>Thread ownership: implementations must be thread-safe.
 */
public interface Importer {
    /** Returns the name used in reports and by the {@code importer} setting. */
    String name();

    /**
     * Returns the importer's version. Bump it whenever the output for the same input changes, so
     * cached results are cooked again.
     */
    int version();

    /**
     * Returns {@code true} if this importer cooks {@code path} (relative, {@code /}-separated,
     * original case) by default.
     */
    boolean accepts(String path, ImportSettings settings);

    /**
     * Returns other source files (relative to the source root) this source consumes, such as a
     * glTF's external buffers and images. The cooker does not cook claimed files on their own
     * unless their settings say {@code "standalone": true}.
     */
    default List<String> claims(Path root, String path, ImportSettings settings)
            throws IOException {
        return List.of();
    }

    /** Cooks the source; outputs, dependencies, warnings and statistics go to {@code ctx}. */
    void cook(CookContext ctx) throws Exception;
}
