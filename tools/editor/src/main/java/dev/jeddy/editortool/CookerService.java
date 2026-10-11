package dev.jeddy.editortool;

import dev.jeddy.cooker.CookReport;
import dev.jeddy.cooker.Cooker;
import dev.jeddy.cooker.CookerConfig;
import dev.jeddy.cooker.watch.SourceWatcher;
import dev.jeddy.core.log.Log;
import dev.jeddy.core.log.Logger;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * The cooker on its own thread (DESIGN §19): cooks the whole project once, then watches the source
 * tree and re-cooks what an edit affects, as {@code jeddy-cook --watch} does. Reports are queued as
 * lines for the editor's console ({@link #drain}), and the cooked output is a loose directory the
 * asset manager hot-reloads from ({@code AssetReloader}), so a saved level shows its re-baked
 * physics and an edited piece its new mesh without a restart.
 *
 * <p>Thread ownership: {@link #start} and {@link #close} on the main thread; {@link #drain} on the
 * game thread; the cooker runs on the service's thread.
 */
final class CookerService implements AutoCloseable {
    private static final Logger LOG = Log.logger("editor-tool");

    private final CookerConfig config;
    private final ConcurrentLinkedQueue<String> lines = new ConcurrentLinkedQueue<>();
    private volatile boolean firstCookDone;
    private volatile boolean failed;
    private Thread thread;
    private SourceWatcher watcher;

    CookerService(CookerConfig config) {
        this.config = config;
    }

    /** Starts the cook-then-watch thread. */
    void start() {
        thread = Thread.ofPlatform().daemon().name("jeddy-cooker").start(this::run);
    }

    /** Returns whether the first full cook finished (well or badly). */
    boolean isFirstCookDone() {
        return firstCookDone;
    }

    /** Returns whether the last cook had failures. */
    boolean failed() {
        return failed;
    }

    /** Moves queued report lines into {@code sink}; returns how many. */
    int drain(java.util.function.Consumer<String> sink) {
        int n = 0;
        String line;
        while ((line = lines.poll()) != null) {
            sink.accept(line);
            n++;
        }
        return n;
    }

    private void run() {
        try (Cooker cooker = new Cooker(config)) {
            report(cooker.cookAll());
            firstCookDone = true;
            try (SourceWatcher w = new SourceWatcher(config.sourceRoot())) {
                watcher = w;
                lines.add("watching " + config.sourceRoot());
                while (!Thread.currentThread().isInterrupted()) {
                    Set<String> changed = w.poll(1, TimeUnit.HOURS, 150);
                    if (changed.isEmpty()) {
                        continue;
                    }
                    report(
                            changed.contains(SourceWatcher.EVERYTHING)
                                    ? cooker.cookAll()
                                    : cooker.cook(cooker.affectedBy(changed)));
                }
            } catch (ClosedWatchServiceException | InterruptedException e) {
                // shutting down
            }
        } catch (IOException e) {
            LOG.error("cooker failed", e);
            lines.add("cooker failed: " + e);
            failed = true;
            firstCookDone = true;
        }
    }

    private void report(CookReport r) {
        failed = r.failed();
        lines.add("cooked: " + r.summary());
        for (CookReport.Item item : r.items()) {
            if (item.status() == CookReport.Status.FAILED) {
                lines.add("  FAILED " + item.source() + ": " + item.error());
            } else if (item.status() == CookReport.Status.COOKED) {
                lines.add(
                        "  "
                                + item.source()
                                + " ("
                                + item.nanos() / 1_000_000
                                + " ms)"
                                + (item.warnings().isEmpty() ? "" : " " + item.warnings()));
            }
        }
    }

    @Override
    public void close() {
        if (thread != null) {
            thread.interrupt();
            SourceWatcher w = watcher;
            if (w != null) {
                try {
                    w.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }
}
