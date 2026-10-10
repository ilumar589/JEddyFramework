package dev.jeddy.cooker.watch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.AssetLoaders;
import dev.jeddy.asset.AssetManager;
import dev.jeddy.asset.AssetReloader;
import dev.jeddy.asset.AssetType;
import dev.jeddy.asset.Vfs;
import dev.jeddy.asset.format.MaterialData;
import dev.jeddy.cooker.Cooker;
import dev.jeddy.cooker.CookerConfig;
import dev.jeddy.core.job.JobSystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * End to end (TASKS M6-10): a source edit is seen by the watcher, recooked into the loose cooked
 * tree, picked up by the runtime's reloader and swapped in behind the same handle.
 */
class HotReloadTest {
    @TempDir Path dir;

    @Test
    void editingASourceReloadsTheRunningAsset() throws Exception {
        Path src = Files.createDirectories(dir.resolve("assets/materials"));
        Files.writeString(src.resolve("stone.jmat.json"), "{\"roughness\": 0.25}");
        Path cooked = dir.resolve("cooked");
        try (Cooker cooker =
                        new Cooker(CookerConfig.of(dir.resolve("assets"), cooked).withThreads(2));
                SourceWatcher watcher = new SourceWatcher(dir.resolve("assets"))) {
            cooker.cookAll();
            try (JobSystem jobs = new JobSystem(1);
                    Vfs vfs = new Vfs()) {
                int mount = vfs.mountDirectory(cooked);
                try (AssetManager m = new AssetManager(vfs, jobs);
                        AssetReloader reloader = new AssetReloader(vfs, mount, m, 50)) {
                    AssetType<MaterialData> materials =
                            m.register(
                                    "material",
                                    AssetLoaders.of((id, data, ctx) -> MaterialData.read(data)));
                    int h = materials.load("materials/stone.jmat");
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                    while (!materials.isReady(h)) {
                        m.update();
                        assertTrue(System.nanoTime() < deadline, "first load");
                    }
                    assertEquals(0.25f, materials.get(h).roughness());

                    Files.writeString(src.resolve("stone.jmat.json"), "{\"roughness\": 0.75}");
                    Set<String> changed = Set.of();
                    while (!changed.contains("materials/stone.jmat.json")) {
                        changed = watcher.poll(500, TimeUnit.MILLISECONDS, 100);
                        assertTrue(System.nanoTime() < deadline, "watcher");
                    }
                    cooker.cook(cooker.affectedBy(changed));
                    while (materials.version(h) == 0) {
                        reloader.poll();
                        m.update();
                        Thread.sleep(5);
                        assertTrue(System.nanoTime() < deadline, "reload");
                    }
                    assertEquals(0.75f, materials.get(h).roughness());
                }
            }
        }
    }
}
