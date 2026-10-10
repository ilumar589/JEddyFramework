package dev.jeddy.asset;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import dev.jeddy.core.log.Log;
import dev.jeddy.core.log.Logger;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Hot reload for development (TASKS M4-07, DESIGN §10.2): watches a loose <em>cooked</em> directory
 * mount, and when files there change (the cooker in watch mode rewrote them), rescans the mount and
 * reloads the affected assets in place behind their handles ({@link AssetManager#reload}). The
 * asset module never talks to the cooker; the file system is the only interface.
 *
 * <p>A daemon thread blocks on a {@link WatchService} and records changed paths with a time stamp;
 * {@link #poll} applies the changes that have been quiet for the debounce interval, so a file
 * written in several steps reloads once.
 *
 * <p>Thread ownership: {@link #poll} on the asset manager's owner thread; the constructor and
 * {@link #close} on any thread.
 */
public final class AssetReloader implements AutoCloseable {
    private static final Logger LOG = Log.logger("asset.reload");

    private final Vfs vfs;
    private final int mount;
    private final AssetManager manager;
    private final Path root;
    private final long debounceNanos;
    private final WatchService watcher;
    private final Map<WatchKey, Path> directories = new HashMap<>();
    private final ConcurrentHashMap<String, Long> changed = new ConcurrentHashMap<>();
    private volatile boolean overflowed;
    private final Thread thread;

    /**
     * Starts watching the directory mount {@code mount} of {@code vfs}.
     *
     * @param debounceMillis how long a path must be quiet before it reloads (100 is typical)
     * @throws IOException if the directory cannot be watched
     */
    public AssetReloader(Vfs vfs, int mount, AssetManager manager, long debounceMillis)
            throws IOException {
        this.vfs = vfs;
        this.mount = mount;
        this.manager = manager;
        this.root = vfs.directory(mount);
        this.debounceNanos = debounceMillis * 1_000_000L;
        watcher = FileSystems.getDefault().newWatchService();
        registerTree(root);
        thread = Thread.ofPlatform().daemon().name("jeddy-asset-watch").start(this::watchLoop);
    }

    /**
     * Applies settled changes: rescans the mount and reloads each changed asset that is loaded.
     * Allocates nothing when no change is pending.
     *
     * @return the number of assets that will reload
     */
    public int poll() {
        if (changed.isEmpty() && !overflowed) {
            return 0;
        }
        long now = System.nanoTime();
        List<String> ready = new ArrayList<>();
        for (Map.Entry<String, Long> e : changed.entrySet()) {
            if (now - e.getValue() >= debounceNanos) {
                ready.add(e.getKey());
            }
        }
        boolean full = overflowed;
        if (ready.isEmpty() && !full) {
            return 0;
        }
        for (String path : ready) {
            changed.remove(path);
        }
        try {
            vfs.rescan(mount);
        } catch (IOException | RuntimeException e) {
            LOG.error("rescanning " + root + " failed; will retry", e);
            for (String path : ready) {
                changed.putIfAbsent(path, now);
            }
            return 0;
        }
        int reloads = 0;
        if (full) {
            overflowed = false;
            LOG.warn("file watch events were lost; rescanned " + root + ", touch files to reload");
        }
        for (String path : ready) {
            try {
                int n = manager.reload(AssetPaths.id(path));
                if (n > 0) {
                    LOG.info("reloading " + path);
                }
                reloads += n;
            } catch (IllegalArgumentException e) {
                LOG.warn("ignoring " + path + ": " + e.getMessage());
            }
        }
        return reloads;
    }

    /** Returns the number of changed paths not yet applied. */
    public int pendingCount() {
        return changed.size();
    }

    /** Stops watching. */
    @Override
    public void close() {
        try {
            watcher.close();
        } catch (IOException e) {
            LOG.warn("closing the watch service failed", e);
        }
        try {
            thread.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void registerTree(Path dir) throws IOException {
        try (Stream<Path> dirs = Files.walk(dir)) {
            for (Path d : (Iterable<Path>) dirs.filter(Files::isDirectory)::iterator) {
                register(d);
            }
        }
    }

    private void register(Path dir) throws IOException {
        WatchKey key = dir.register(watcher, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
        synchronized (directories) {
            directories.put(key, dir);
        }
    }

    private void watchLoop() {
        try {
            while (true) {
                WatchKey key = watcher.take();
                Path dir;
                synchronized (directories) {
                    dir = directories.get(key);
                }
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == OVERFLOW) {
                        overflowed = true;
                        continue;
                    }
                    if (dir == null) {
                        continue;
                    }
                    Path file = dir.resolve((Path) event.context());
                    if (event.kind() == ENTRY_CREATE && Files.isDirectory(file)) {
                        try {
                            registerTree(file);
                            try (Stream<Path> files = Files.walk(file)) {
                                files.filter(Files::isRegularFile).forEach(this::record);
                            }
                        } catch (IOException e) {
                            LOG.warn("cannot watch " + file, e);
                        }
                        continue;
                    }
                    record(file);
                }
                if (!key.reset()) {
                    synchronized (directories) {
                        directories.remove(key);
                    }
                }
            }
        } catch (ClosedWatchServiceException | InterruptedException e) {
            // closed
        }
    }

    private void record(Path file) {
        String name = file.getFileName().toString();
        if (name.startsWith(".") || name.endsWith(".tmp")) {
            return; // the cooker's temporary files; the rename that follows is recorded
        }
        String relative = root.relativize(file).toString().replace('\\', '/');
        try {
            changed.put(AssetPaths.normalize(relative), System.nanoTime());
        } catch (IllegalArgumentException e) {
            // not an asset path
        }
    }
}
