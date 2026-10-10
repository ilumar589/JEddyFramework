package dev.jeddy.cooker.watch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.cooker.CookReport;
import dev.jeddy.cooker.Cooker;
import dev.jeddy.cooker.CookerConfig;
import dev.jeddy.cooker.TestImages;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Watch mode: changes are debounced and recooked incrementally (TASKS M6-10). */
class SourceWatcherTest {
    @TempDir Path dir;

    /** Polls until {@code expected} have all been reported (watch services may split events). */
    private static Set<String> collect(SourceWatcher w, Set<String> expected) throws Exception {
        Set<String> seen = new HashSet<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!seen.containsAll(expected) && System.nanoTime() < deadline) {
            seen.addAll(w.poll(500, TimeUnit.MILLISECONDS, 100));
        }
        return seen;
    }

    @Test
    void reportsCreatedModifiedDeletedAndNewDirectories() throws Exception {
        Path src = Files.createDirectories(dir.resolve("assets"));
        Files.writeString(src.resolve("a.txt"), "1");
        try (SourceWatcher w = new SourceWatcher(src)) {
            Files.writeString(src.resolve("a.txt"), "2");
            Files.writeString(src.resolve("b.txt"), "new");
            Set<String> seen = collect(w, Set.of("a.txt", "b.txt"));
            assertTrue(seen.containsAll(Set.of("a.txt", "b.txt")), seen.toString());

            Path sub = Files.createDirectories(src.resolve("sub/deeper"));
            Files.writeString(sub.resolve("c.txt"), "c");
            seen = collect(w, Set.of("sub/deeper/c.txt"));
            assertTrue(seen.contains("sub/deeper/c.txt"), seen.toString());
            // The new directory is watched now.
            Files.writeString(sub.resolve("d.txt"), "d");
            seen = collect(w, Set.of("sub/deeper/d.txt"));
            assertTrue(seen.contains("sub/deeper/d.txt"), seen.toString());

            Files.delete(src.resolve("b.txt"));
            seen = collect(w, Set.of("b.txt"));
            assertTrue(seen.contains("b.txt"), seen.toString());

            Files.createDirectories(src.resolve(".git"));
            Files.writeString(src.resolve(".git/index"), "x");
            assertEquals(
                    Set.of(), w.poll(300, TimeUnit.MILLISECONDS, 50), "hidden entries are ignored");
        }
    }

    @Test
    void watchLoopRecooksAChangedTexture() throws Exception {
        Path src = Files.createDirectories(dir.resolve("assets/textures"));
        Files.write(src.resolve("rock.png"), TestImages.painted(16, 1));
        Files.write(src.resolve("moss.png"), TestImages.painted(16, 2));
        Path root = dir.resolve("assets");
        try (Cooker c = new Cooker(CookerConfig.of(root, dir.resolve("cooked")).withThreads(2));
                SourceWatcher w = new SourceWatcher(root)) {
            c.cookAll();
            Files.write(src.resolve("rock.png"), TestImages.painted(16, 3));
            Set<String> changed = collect(w, Set.of("textures/rock.png"));
            CookReport r = c.cook(c.affectedBy(changed));
            assertEquals(CookReport.Status.COOKED, r.item("textures/rock.png").status());
            assertEquals(
                    null, r.item("textures/moss.png"), "untouched sources are not even looked at");
            Files.write(src.resolve("rock.png.meta"), "{\"kind\": \"mask\"}".getBytes());
            changed = collect(w, Set.of("textures/rock.png.meta"));
            assertEquals(List.of("textures/rock.png"), List.copyOf(c.affectedBy(changed)));
        }
    }
}
