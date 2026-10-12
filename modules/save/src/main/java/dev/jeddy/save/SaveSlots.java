package dev.jeddy.save;

import dev.jeddy.core.log.Log;
import dev.jeddy.core.log.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The save slots of one directory (DESIGN §21, D119): {@link #QUICK} is {@code quick.jsave}, {@link
 * #AUTO} is {@code auto.jsave}, and a named slot is {@code slot-<name>.jsave} with the name reduced
 * to letters, digits, dashes and underscores ({@link #sanitize}). {@link #list} reads the meta
 * block of every file for a menu, newest first; {@link #save} hands a capture to a {@link SaveJob};
 * {@link #load} reads a whole file; {@link #delete} removes one.
 *
 * <p>Thread ownership: the game thread (the listing and the load do file I/O synchronously; a menu
 * lists once when it opens, and a load replaces the game's state anyway).
 */
public final class SaveSlots {
    private static final Logger LOG = Log.logger("save");

    public static final String QUICK = "quick";
    public static final String AUTO = "auto";
    public static final String EXTENSION = ".jsave";
    private static final String NAMED_PREFIX = "slot-";

    /** One slot as the list shows it: its name, file, meta entries, time and size. */
    public record SaveInfo(
            String slot, Path path, Map<String, String> meta, FileTime time, long bytes) {
        /** A meta value or {@code fallback}. */
        public String meta(String key, String fallback) {
            String v = meta.get(key);
            return v == null ? fallback : v;
        }
    }

    private final Path directory;
    private final SaveJob job;

    /** Slots in {@code directory}, written by {@code job}. */
    public SaveSlots(Path directory, SaveJob job) {
        this.directory = directory;
        this.job = job;
    }

    /** Slots in {@code directory} with a job of their own. */
    public SaveSlots(Path directory) {
        this(directory, new SaveJob());
    }

    public Path directory() {
        return directory;
    }

    public SaveJob job() {
        return job;
    }

    /** A slot name reduced to {@code [A-Za-z0-9_-]}, at most 48 characters, never empty. */
    public static String sanitize(String name) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < name.length() && b.length() < 48; i++) {
            char c = name.charAt(i);
            if (c >= 'a' && c <= 'z'
                    || c >= 'A' && c <= 'Z'
                    || c >= '0' && c <= '9'
                    || c == '-'
                    || c == '_') {
                b.append(c);
            } else if (c == ' ') {
                b.append('_');
            }
        }
        return b.isEmpty() ? "save" : b.toString();
    }

    /** The file of {@code slot} ({@link #QUICK}, {@link #AUTO} or a name, sanitized). */
    public Path pathOf(String slot) {
        if (QUICK.equals(slot) || AUTO.equals(slot)) {
            return directory.resolve(slot + EXTENSION);
        }
        return directory.resolve(NAMED_PREFIX + sanitize(slot) + EXTENSION);
    }

    /** The slot name of a file in the directory, or {@code null} for a file that is not one. */
    public static String slotOf(Path file) {
        String n = file.getFileName().toString();
        if (!n.endsWith(EXTENSION)) {
            return null;
        }
        String stem = n.substring(0, n.length() - EXTENSION.length());
        if (stem.equals(QUICK) || stem.equals(AUTO)) {
            return stem;
        }
        return stem.startsWith(NAMED_PREFIX) ? stem.substring(NAMED_PREFIX.length()) : null;
    }

    /** Whether {@code slot} has a file. */
    public boolean exists(String slot) {
        return Files.isRegularFile(pathOf(slot));
    }

    /**
     * Every slot with a readable meta block, newest first; a file that is not a save is logged and
     * left out. Returns an empty list when the directory does not exist.
     */
    public List<SaveInfo> list() throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<SaveInfo> out = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*" + EXTENSION)) {
            for (Path p : files) {
                String slot = slotOf(p);
                if (slot == null || !Files.isRegularFile(p)) {
                    continue;
                }
                try {
                    SaveFile f = SaveFile.peek(p);
                    out.add(
                            new SaveInfo(
                                    slot,
                                    p,
                                    f.meta(),
                                    Files.getLastModifiedTime(p),
                                    Files.size(p)));
                } catch (IOException e) {
                    LOG.warn("skipping " + p + ": " + e.getMessage());
                }
            }
        }
        out.sort((a, b) -> b.time().compareTo(a.time()));
        return Collections.unmodifiableList(out);
    }

    /** Hands {@code writer}'s capture to the job for {@code slot}; returns the job to poll. */
    public SaveJob save(String slot, SaveWriter writer, long captureNanos) {
        job.submit(writer, pathOf(slot), captureNanos);
        return job;
    }

    /** Reads {@code slot} whole. */
    public SaveFile load(String slot) throws IOException {
        job.await(); // a save of this slot may still be writing
        return SaveFile.read(pathOf(slot));
    }

    /** Deletes {@code slot}'s file; returns whether there was one. */
    public boolean delete(String slot) throws IOException {
        job.await();
        return Files.deleteIfExists(pathOf(slot));
    }
}
