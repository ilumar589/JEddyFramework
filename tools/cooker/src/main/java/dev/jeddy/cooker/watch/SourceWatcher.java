package dev.jeddy.cooker.watch;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Watches a source tree for {@code jeddy-cook --watch} (TASKS M6-10): every directory is registered
 * (new ones as they appear), and {@link #poll} collects changed files until the tree has been quiet
 * for the debounce interval, so an editor's save (often a write, a rename and a delete) or a bulk
 * copy becomes one recook. Hidden entries are ignored.
 *
 * <p>Thread ownership: one thread polls; {@link #close} may be called from another.
 */
public final class SourceWatcher implements AutoCloseable {
    /** Returned by {@link #poll} when events were lost: cook everything. */
    public static final String EVERYTHING = "*";

    private final Path root;
    private final WatchService service;
    private final Map<WatchKey, Path> keys = new HashMap<>();

    /** Starts watching {@code root} and every directory below it. */
    public SourceWatcher(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.service = FileSystems.getDefault().newWatchService();
        registerTree(this.root, null);
    }

    private void registerTree(Path dir, Set<String> created) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.toList()) {
                if (hidden(p)) {
                    continue;
                }
                if (Files.isDirectory(p)) {
                    keys.put(p.register(service, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE), p);
                } else if (created != null) {
                    created.add(relative(p));
                }
            }
        }
    }

    private boolean hidden(Path p) {
        Path rel = root.relativize(p);
        for (Path part : rel) {
            if (part.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private String relative(Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }

    /**
     * Waits up to {@code timeout} for a change, then keeps collecting until nothing changed for
     * {@code debounceMillis}. Returns the changed files (relative to the root), {@link #EVERYTHING}
     * if events overflowed, or an empty set on timeout.
     */
    public Set<String> poll(long timeout, TimeUnit unit, long debounceMillis)
            throws InterruptedException, IOException {
        Set<String> changed = new TreeSet<>();
        WatchKey key = service.poll(timeout, unit);
        while (key != null) {
            drain(key, changed);
            key = service.poll(debounceMillis, TimeUnit.MILLISECONDS);
        }
        return changed;
    }

    private void drain(WatchKey key, Set<String> changed) throws IOException {
        Path dir = keys.get(key);
        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.kind() == OVERFLOW || dir == null) {
                changed.add(EVERYTHING);
                continue;
            }
            Path p = dir.resolve((Path) event.context());
            if (hidden(p)) {
                continue;
            }
            if (event.kind() == ENTRY_CREATE && Files.isDirectory(p)) {
                registerTree(p, changed); // files copied in with the directory
            } else if (!Files.isDirectory(p)) {
                changed.add(relative(p));
            }
        }
        if (!key.reset()) {
            keys.remove(key);
        }
    }

    @Override
    public void close() throws IOException {
        service.close();
    }
}
