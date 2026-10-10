package dev.jeddy.core.natives;

import dev.jeddy.core.json.Json;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
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

/**
 * Finds, verifies and loads Jeddy's native libraries (DESIGN §6.3).
 *
 * <p>Libraries come from the first of:
 *
 * <ol>
 *   <li>the directory in system property {@code jeddy.natives.dir} (holding {@code natives.json}
 *       and the libraries, as written by {@code natives/build.py}); used in place;
 *   <li>{@code lib/jeddy/natives} of the running Java image, where packaging (a {@code jlink}
 *       image, M11-01) puts them; used in place;
 *   <li>the classpath resource {@code natives/<platform>/natives.json} from a {@code
 *       jeddy-natives-<platform>} jar; the libraries are extracted once into a cache directory
 *       ({@code jeddy.natives.cache}, else {@code %LOCALAPPDATA%\Jeddy\natives} or {@code
 *       $XDG_CACHE_HOME/jeddy/natives}), each written to a temporary file, checked against its
 *       SHA-256 and atomically renamed.
 * </ol>
 *
 * Every library's SHA-256 is verified before {@link System#load(String)}. Libraries load in
 * manifest order, dependencies first; {@link System#load} makes their symbols visible to {@code
 * SymbolLookup.loaderLookup()}, which jextract-generated bindings use.
 *
 * <p>Thread ownership: thread-safe (synchronized; called during startup).
 */
public final class NativeLoader {
    /** One library from the manifest. */
    public record Library(
            String name, String file, String sha256, boolean runtime, List<String> depends) {}

    private static List<Library> manifest;
    private static Path directory;
    private static final Set<String> LOADED = new HashSet<>();

    private NativeLoader() {}

    /** Loads the named libraries and everything they depend on. Idempotent. */
    public static synchronized void load(String... names) {
        resolve();
        Map<String, Library> byName = new LinkedHashMap<>();
        for (Library l : manifest) {
            byName.put(l.name(), l);
        }
        Set<String> wanted = new HashSet<>();
        for (String n : names) {
            addWithDependencies(n, byName, wanted);
        }
        for (Library l : manifest) {
            if (wanted.contains(l.name()) && !LOADED.contains(l.name())) {
                Path p = directory.resolve(l.file());
                System.load(p.toAbsolutePath().toString());
                LOADED.add(l.name());
            }
        }
    }

    /** Loads every library the manifest marks as needed at runtime. */
    public static synchronized void loadRuntime() {
        resolve();
        List<String> names = new ArrayList<>();
        for (Library l : manifest) {
            if (l.runtime()) {
                names.add(l.name());
            }
        }
        load(names.toArray(String[]::new));
    }

    /** Returns {@code true} once {@code name} has been loaded by this class. */
    public static synchronized boolean isLoaded(String name) {
        return LOADED.contains(name);
    }

    /** Returns the libraries listed in the manifest. */
    public static synchronized List<Library> libraries() {
        resolve();
        return List.copyOf(manifest);
    }

    /** Returns the directory the libraries are loaded from. */
    public static synchronized Path directory() {
        resolve();
        return directory;
    }

    private static void addWithDependencies(
            String name, Map<String, Library> byName, Set<String> out) {
        Library l = byName.get(name);
        if (l == null) {
            throw new IllegalArgumentException(
                    "native library '" + name + "' is not in the manifest " + byName.keySet());
        }
        if (out.add(name)) {
            for (String d : l.depends()) {
                addWithDependencies(d, byName, out);
            }
        }
    }

    private static void resolve() {
        if (manifest != null) {
            return;
        }
        Platform platform = Platform.current();
        String dirProperty = System.getProperty("jeddy.natives.dir");
        try {
            Path image = Path.of(System.getProperty("java.home"), "lib", "jeddy", "natives");
            if ((dirProperty == null || dirProperty.isBlank())
                    && Files.isRegularFile(image.resolve("natives.json"))) {
                dirProperty = image.toString();
            }
            if (dirProperty != null && !dirProperty.isBlank()) {
                Path dir = Path.of(dirProperty).toAbsolutePath();
                Path json = dir.resolve("natives.json");
                if (!Files.isRegularFile(json)) {
                    throw new IllegalStateException(
                            "jeddy.natives.dir=" + dir + " has no natives.json");
                }
                List<Library> libs = parse(Files.readAllBytes(json), platform);
                for (Library l : libs) {
                    verify(dir.resolve(l.file()), l);
                }
                manifest = libs;
                directory = dir;
                return;
            }
            String base = "natives/" + platform.id() + "/";
            URL url = findResource(base + "natives.json");
            if (url == null) {
                throw new IllegalStateException(
                        "No native libraries for "
                                + platform.id()
                                + ": put jeddy-natives-"
                                + platform.id()
                                + ".jar on the classpath or set -Djeddy.natives.dir");
            }
            byte[] json;
            try (InputStream in = url.openStream()) {
                json = in.readAllBytes();
            }
            List<Library> libs = parse(json, platform);
            Path dir = cacheRoot().resolve(hex(sha256(json)).substring(0, 16));
            Files.createDirectories(dir);
            for (Library l : libs) {
                extract(base + l.file(), dir, l);
            }
            manifest = libs;
            directory = dir;
        } catch (IOException e) {
            throw new UncheckedIOException("could not prepare native libraries", e);
        }
    }

    private static URL findResource(String name) {
        ClassLoader[] loaders = {
            Thread.currentThread().getContextClassLoader(),
            NativeLoader.class.getClassLoader(),
            ClassLoader.getSystemClassLoader()
        };
        for (ClassLoader cl : loaders) {
            if (cl != null) {
                URL u = cl.getResource(name);
                if (u != null) {
                    return u;
                }
            }
        }
        return null;
    }

    private static void extract(String resource, Path dir, Library lib) throws IOException {
        Path target = dir.resolve(lib.file());
        if (Files.isRegularFile(target) && hex(sha256(target)).equals(lib.sha256())) {
            return;
        }
        URL url = findResource(resource);
        if (url == null) {
            throw new IllegalStateException(
                    "natives.json lists " + resource + " but the file is missing");
        }
        Path tmp = Files.createTempFile(dir, lib.file(), ".tmp");
        try {
            try (InputStream in = url.openStream()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            verify(tmp, lib);
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
            Files.deleteIfExists(tmp);
        }
    }

    private static void verify(Path file, Library lib) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("missing native library " + file);
        }
        String actual = hex(sha256(file));
        if (!actual.equals(lib.sha256())) {
            throw new IllegalStateException(
                    "SHA-256 mismatch for "
                            + file
                            + ": expected "
                            + lib.sha256()
                            + ", got "
                            + actual);
        }
    }

    private static List<Library> parse(byte[] json, Platform platform) {
        Map<String, Object> root = Json.asObject(Json.parse(json), "natives.json");
        String declared = Json.string(root, "platform", "");
        if (!declared.equals(platform.id())) {
            throw new IllegalStateException(
                    "natives.json is for '" + declared + "', this JVM runs on " + platform.id());
        }
        List<Library> out = new ArrayList<>();
        for (Object o : Json.asArray(root.get("libraries"), "libraries")) {
            Map<String, Object> m = Json.asObject(o, "library");
            List<String> deps = new ArrayList<>();
            Object d = m.get("depends");
            if (d != null) {
                for (Object x : Json.asArray(d, "depends")) {
                    deps.add(String.valueOf(x));
                }
            }
            out.add(
                    new Library(
                            Json.string(m, "name", null),
                            Json.string(m, "file", null),
                            Json.string(m, "sha256", "").toLowerCase(java.util.Locale.ROOT),
                            Json.bool(m, "runtime", true),
                            List.copyOf(deps)));
        }
        return out;
    }

    private static Path cacheRoot() {
        String override = System.getProperty("jeddy.natives.cache");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        if (Platform.current().isWindows()) {
            String local = System.getenv("LOCALAPPDATA");
            if (local != null) {
                return Path.of(local, "Jeddy", "natives");
            }
        } else {
            String xdg = System.getenv("XDG_CACHE_HOME");
            if (xdg != null && !xdg.isBlank()) {
                return Path.of(xdg, "jeddy", "natives");
            }
        }
        return Path.of(System.getProperty("user.home"), ".cache", "jeddy", "natives");
    }

    private static byte[] sha256(byte[] data) {
        return digest().digest(data);
    }

    private static byte[] sha256(Path file) throws IOException {
        MessageDigest md = digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return md.digest();
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
