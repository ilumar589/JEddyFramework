package dev.jeddy.testing;

import org.junit.jupiter.api.Assertions;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Portability check for generated FFM bindings (DESIGN §6.4, D22): initializes every class in a
 * generated package, including each per-function holder, so a missing symbol, a bad function
 * descriptor or a layout the platform ABI rejects fails the test instead of the first call in a
 * game. CI runs these tests on Windows and Linux.
 *
 * <p>Thread ownership: call from the test thread.
 */
public final class GeneratedBindings {
    private GeneratedBindings() {}

    /**
     * Initializes every top-level and nested class in {@code pkg}.
     *
     * @return how many classes were initialized
     */
    public static int initializeAll(Class<?> anchor, String pkg) {
        ClassLoader loader = anchor.getClassLoader();
        List<String> names;
        try {
            names = classNames(loader, pkg);
        } catch (IOException | URISyntaxException e) {
            throw new IllegalStateException(e);
        }
        List<String> failures = new ArrayList<>();
        for (String name : names) {
            try {
                Class.forName(name, true, loader);
            } catch (Throwable t) {
                Throwable cause = t.getCause() != null ? t.getCause() : t;
                failures.add(name + ": " + cause);
            }
        }
        Assertions.assertTrue(
                failures.isEmpty(),
                failures.size() + " binding classes failed:\n" + String.join("\n", failures));
        return names.size();
    }

    private static List<String> classNames(ClassLoader loader, String pkg)
            throws IOException, URISyntaxException {
        String path = pkg.replace('.', '/');
        URL url = loader.getResource(path);
        Assertions.assertNotNull(url, "package " + pkg + " not found");
        URI uri = url.toURI();
        if (uri.getScheme().equals("jar")) {
            try (FileSystem fs = FileSystems.newFileSystem(uri, Map.of())) {
                return list(fs.getPath(path), pkg);
            }
        }
        return list(Path.of(uri), pkg);
    }

    private static List<String> list(Path dir, String pkg) throws IOException {
        List<String> names = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files.sorted()::iterator) {
                String file = p.getFileName().toString();
                if (file.endsWith(".class") && !file.equals("package-info.class")) {
                    names.add(pkg + "." + file.substring(0, file.length() - ".class".length()));
                }
            }
        }
        return names;
    }
}
