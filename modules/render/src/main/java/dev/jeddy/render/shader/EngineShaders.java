package dev.jeddy.render.shader;

import dev.jeddy.asset.shader.ShaderSources;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;

/**
 * The engine's built-in GLSL sources, shipped as resources of this module under {@value #ROOT}.
 * Read from here rather than through {@code ShaderSources.classpath}: on the module path a module's
 * resource packages are encapsulated, so only code in {@code dev.jeddy.render} can open them (D64).
 *
 * <p>Thread ownership: stateless; any thread.
 */
public final class EngineShaders {
    /** Resource directory of the engine shaders. */
    public static final String ROOT = "/dev/jeddy/render/shaders";

    private EngineShaders() {}

    /** Returns sources that load {@code ROOT/path} from this module. */
    public static ShaderSources sources() {
        return EngineShaders::load;
    }

    private static String load(String path) throws IOException {
        String name = ROOT + "/" + path;
        try (InputStream in = EngineShaders.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new NoSuchFileException(name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
