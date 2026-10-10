package dev.jeddy.asset.shader;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * Where GLSL sources come from: a directory (development, hot reload), the classpath (engine
 * shaders shipped in the jar) or a cooked pak (later).
 *
 * <p>Thread ownership: implementations must be safe for the thread that compiles shaders.
 */
@FunctionalInterface
public interface ShaderSources {
    /**
     * Returns the text of the shader at {@code path} ({@code /}-separated, relative).
     *
     * @throws NoSuchFileException if there is none
     */
    String load(String path) throws IOException;

    /** Sources under a directory. */
    static ShaderSources directory(Path root) {
        return path -> Files.readString(root.resolve(path), StandardCharsets.UTF_8);
    }

    /**
     * Sources from {@code anchor}'s module or class loader, under {@code root} (no slash at the
     * end).
     */
    static ShaderSources classpath(Class<?> anchor, String root) {
        return path -> {
            String name = root + "/" + path;
            try (InputStream in = anchor.getResourceAsStream(name)) {
                if (in == null) {
                    throw new NoSuchFileException(name);
                }
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
        };
    }
}
