package dev.jeddy.testing;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Golden files for format tests (DESIGN §21, D117): a fixture is the bytes a format version wrote
 * when it was current, checked in under {@code src/test/resources}, so a later build proves it
 * still reads them. {@link #bytes} loads one from the class path; when it is missing (a new fixture
 * being added), {@link #bytesOrCreate} writes it from the supplied bytes into the module's test
 * resources and fails the test once, so the file is reviewed and committed rather than silently
 * regenerated.
 *
 * <p>Thread ownership: any.
 */
public final class Fixtures {
    private Fixtures() {}

    /** The resource {@code name} (absolute, {@code /dev/jeddy/...}) as bytes, or {@code null}. */
    public static byte[] bytes(Class<?> near, String name) {
        try (InputStream in = near.getResourceAsStream(name)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The fixture {@code name} next to {@code near}'s package, or, when it does not exist yet,
     * {@code fresh} written to {@code src/test/resources} (the tests run with the module as the
     * working directory) and an {@link AssertionError} asking for a rerun after review.
     */
    public static byte[] bytesOrCreate(Class<?> near, String name, byte[] fresh) {
        byte[] existing = bytes(near, name);
        if (existing != null) {
            return existing;
        }
        String resource =
                name.startsWith("/")
                        ? name.substring(1)
                        : near.getPackageName().replace('.', '/') + "/" + name;
        Path file = Path.of("src", "test", "resources").resolve(resource);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, fresh);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        throw new AssertionError(
                "fixture "
                        + file
                        + " did not exist; written from the current build ("
                        + fresh.length
                        + " bytes): review it, commit it and run again");
    }
}
