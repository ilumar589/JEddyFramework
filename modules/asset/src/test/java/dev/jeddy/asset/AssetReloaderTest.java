package dev.jeddy.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.core.job.JobSystem;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Hot reload from the cooked directory (TASKS M4-07). */
class AssetReloaderTest {
    @TempDir Path dir;

    /** Writes like the cooker: a temporary file renamed over the target. */
    private static void cook(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, content);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    @Test
    void changedFilesReloadInPlace() throws Exception {
        Path cooked = Files.createDirectories(dir.resolve("cooked"));
        cook(cooked.resolve("ui/theme.txt"), "dark");
        try (JobSystem jobs = new JobSystem(1);
                Vfs vfs = new Vfs()) {
            int mount = vfs.mountDirectory(cooked);
            try (AssetManager m = new AssetManager(vfs, jobs);
                    AssetReloader reloader = new AssetReloader(vfs, mount, m, 50)) {
                AssetType<String> text =
                        m.register(
                                "text",
                                AssetLoaders.of(
                                        (id, data, ctx) ->
                                                new String(
                                                        data.toArray(ValueLayout.JAVA_BYTE),
                                                        StandardCharsets.UTF_8)));
                int h = text.load("ui/theme.txt");
                long deadline = System.nanoTime() + 10_000_000_000L;
                while (!text.isComplete(h)) {
                    m.update();
                    assertTrue(System.nanoTime() < deadline, "first load");
                }
                assertEquals(0, reloader.poll());
                Allocations.assertNoAllocation("idle reload poll", 5, 1000, reloader::poll);

                cook(cooked.resolve("ui/theme.txt"), "light, and longer");
                cook(cooked.resolve("new/dir/added.txt"), "new"); // a new directory is watched too
                int reloads = 0;
                while (text.version(h) == 0 || !vfs.exists("new/dir/added.txt")) {
                    reloads += reloader.poll();
                    m.update();
                    Thread.sleep(5);
                    assertTrue(System.nanoTime() < deadline, "reload: " + reloader.pendingCount());
                }
                assertEquals(1, reloads);
                assertEquals("light, and longer", text.get(h));
                int added = text.load("new/dir/added.txt");
                while (!text.isReady(added)) {
                    m.update();
                    assertTrue(System.nanoTime() < deadline, "added");
                }
                assertEquals("new", text.get(added));
            }
        }
    }
}
