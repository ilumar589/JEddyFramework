package dev.jeddy.editor;

import dev.jeddy.asset.format.AssetFormatException;
import dev.jeddy.asset.format.LevelData;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reads and writes {@code .jlevel} files for the editor (DESIGN §19). Saving serializes on the game
 * thread (a few hundred microseconds for a level of a few hundred rows) and writes on a virtual
 * thread, to a temporary file renamed over the target so a crash mid-write never leaves a truncated
 * level; {@link Save#isDone} and {@link Save#error} report the outcome back on the game thread.
 *
 * <p>Thread ownership: {@link #read} and {@link #save} on the game thread; the write runs on its
 * own thread.
 */
public final class LevelFiles {
    private LevelFiles() {}

    /** Reads a level from {@code path}. */
    public static LevelData read(Path path) throws IOException, AssetFormatException {
        try (Arena arena = Arena.ofConfined();
                FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            MemorySegment m = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size(), arena);
            return LevelData.read(m);
        }
    }

    /** A save in flight. */
    public static final class Save {
        private final Path path;
        private final AtomicReference<Object> result =
                new AtomicReference<>(); // null, DONE or a Throwable
        private static final Object DONE = new Object();

        private Save(Path path) {
            this.path = path;
        }

        /** Returns the target path. */
        public Path path() {
            return path;
        }

        /** Returns whether the write finished (well or badly). */
        public boolean isDone() {
            return result.get() != null;
        }

        /** Returns the error if the write failed, else {@code null}; valid once done. */
        public Throwable error() {
            Object r = result.get();
            return r instanceof Throwable t ? t : null;
        }
    }

    /** Starts writing {@code level} to {@code path}; the bytes are taken now. */
    public static Save save(LevelData level, Path path) {
        byte[] bytes = level.toBytes();
        Save save = new Save(path);
        Thread.ofVirtual()
                .name("level-save")
                .start(
                        () -> {
                            try {
                                Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
                                Path parent = path.getParent();
                                if (parent != null) {
                                    Files.createDirectories(parent);
                                }
                                Files.write(tmp, bytes);
                                Files.move(
                                        tmp,
                                        path,
                                        StandardCopyOption.REPLACE_EXISTING,
                                        StandardCopyOption.ATOMIC_MOVE);
                                save.result.set(Save.DONE);
                            } catch (Throwable t) {
                                save.result.set(t);
                            }
                        });
        return save;
    }

    /** Writes the JSON dump of {@code level} next to {@code path} ({@code <name>.json}). */
    public static Path dumpJson(LevelData level, Path path) throws IOException {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        Path out = path.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + ".json");
        Files.writeString(out, level.toJson());
        return out;
    }
}
