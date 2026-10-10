package dev.jeddy.input;

import static dev.jeddy.sdl.ffi.SDL3.SDL_AddGamepadMapping;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads extra controller mappings in SDL's text format, such as the community {@code
 * gamecontrollerdb.txt} (TASKS M2-08). SDL ships mappings for common pads; a game can add more at
 * startup or from a mod folder.
 *
 * <p>Thread ownership: any thread ({@code SDL_AddGamepadMapping} is thread-safe); cold path.
 */
public final class GamepadMappings {
    private GamepadMappings() {}

    /**
     * Adds every mapping line in {@code text}; blank lines and {@code #} comments are skipped.
     *
     * @return the number of mappings added or updated
     */
    public static int add(String text) {
        int added = 0;
        try (Arena arena = Arena.ofConfined()) {
            for (String line : text.split("\r?\n")) {
                String l = line.trim();
                if (l.isEmpty() || l.startsWith("#")) {
                    continue;
                }
                if (SDL_AddGamepadMapping(arena.allocateFrom(l)) >= 0) {
                    added++;
                }
            }
        }
        return added;
    }

    /** Adds the mappings in a UTF-8 file. */
    public static int addFromFile(Path file) throws IOException {
        return add(Files.readString(file, StandardCharsets.UTF_8));
    }
}
