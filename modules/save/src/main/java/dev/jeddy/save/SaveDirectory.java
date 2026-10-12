package dev.jeddy.save;

import java.nio.file.Path;

/**
 * Where a game's saves live (DESIGN §21, D119): the user's <em>data</em> directory, which survives
 * cache cleaning and is writable without privileges, unlike the install directory.
 *
 * <ul>
 *   <li>{@code jeddy.saves.dir} (a system property), when set: as given.
 *   <li>Windows: {@code %APPDATA%\<game>\saves} (roaming, so a domain profile carries it).
 *   <li>Linux: {@code $XDG_DATA_HOME/<game>/saves}, default {@code ~/.local/share/<game>/saves}.
 * </ul>
 *
 * <p>Thread ownership: any.
 */
public final class SaveDirectory {
    private SaveDirectory() {}

    /** The save directory of {@code game} (a short lower-case name such as {@code crypt}). */
    public static Path of(String game) {
        String explicit = System.getProperty("jeddy.saves.dir");
        if (explicit != null && !explicit.isBlank()) {
            return Path.of(explicit);
        }
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Path.of(appData, game, "saves");
        }
        String xdg = System.getenv("XDG_DATA_HOME");
        if (xdg != null && !xdg.isBlank()) {
            return Path.of(xdg, game, "saves");
        }
        String home = System.getProperty("user.home", ".");
        return Path.of(home, ".local", "share", game, "saves");
    }
}
