package dev.jeddy.asset;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.pak.PakWriter;
import dev.jeddy.asset.pak.PakWriter.Compression;
import dev.jeddy.core.job.JobSystem;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/** The asynchronous load pipeline (TASKS M4-05) and in-place reloads (M4-07). */
class AssetManagerTest {
    private static JobSystem jobs;

    @TempDir Path dir;

    @BeforeAll
    static void startJobs() {
        jobs = new JobSystem(2);
    }

    @AfterAll
    static void stopJobs() {
        jobs.close();
    }

    private static String text(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    private static void await(AssetManager m, BooleanSupplier done) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(
                        "timed out: queued "
                                + m.queuedCount()
                                + ", in flight "
                                + m.inFlightCount());
            }
            m.update();
            m.pumpUploads(1_000_000);
            Thread.onSpinWait();
        }
    }

    private Path pak(String name, int count, int size) throws IOException {
        Path file = dir.resolve(name);
        try (PakWriter w = new PakWriter(3)) {
            for (int i = 0; i < count; i++) {
                byte[] b = new byte[size];
                for (int k = 0; k < size; k++) {
                    b[k] = (byte) ('a' + (i + k / 7) % 26);
                }
                w.add("asset/" + i + ".bin", b, Compression.AUTO);
            }
            w.write(file);
        }
        return file;
    }

    @Test
    void loadsByPriorityWithReferenceCounts() throws IOException {
        List<Long> order = new CopyOnWriteArrayList<>();
        try (Vfs vfs = new Vfs()) {
            vfs.mountPak(pak("p.jpak", 8, 1000));
            try (AssetManager m =
                    new AssetManager(vfs, jobs, new AssetManager.Config().maxInFlight(1))) {
                AssetType<byte[]> bytes =
                        m.register(
                                "bytes",
                                (id, data, ctx) -> {
                                    order.add(id);
                                    return data.toArray(ValueLayout.JAVA_BYTE);
                                });
                int[] h = new int[8];
                for (int i = 0; i < 8; i++) {
                    h[i] = bytes.load("asset/" + i + ".bin", 8 - i); // the last is most urgent
                    assertEquals(AssetState.QUEUED, bytes.state(h[i]));
                }
                int again = bytes.load("ASSET/3.bin", 100); // a less urgent request keeps the priority
                assertEquals(h[3], again);
                assertEquals(2, bytes.refCount(h[3]));
                await(m, () -> m.queuedCount() == 0 && m.inFlightCount() == 0);
                for (int i = 0; i < 8; i++) {
                    assertTrue(bytes.isReady(h[i]));
                    assertTrue(bytes.isComplete(h[i]));
                    assertEquals(1000, bytes.get(h[i]).length);
                    assertEquals('a' + i % 26, bytes.get(h[i])[0]);
                    assertEquals(AssetPaths.id("asset/" + i + ".bin"), bytes.id(h[i]));
                    assertEquals(0, bytes.version(h[i]));
                }
                for (int i = 0; i < 8; i++) {
                    assertEquals(AssetPaths.id("asset/" + (7 - i) + ".bin"), (long) order.get(i));
                }
                bytes.release(h[3]);
                assertTrue(bytes.isReady(h[3]));
                bytes.release(h[3]);
                assertEquals(AssetState.UNLOADED, bytes.state(h[3]));
                assertThrows(IllegalArgumentException.class, () -> bytes.get(h[3]));
                assertEquals(7, m.liveCount());
                int fresh = bytes.load("asset/3.bin");
                assertNotEquals(h[3], fresh, "a new generation");
                await(m, () -> bytes.isReady(fresh));
            }
        }
    }

    @Test
    void uploadsOnTheMainThreadWithinBudgetAndUnloadsThere() throws IOException {
        List<String> events = new CopyOnWriteArrayList<>();
        Thread main = Thread.currentThread();
        AssetLoader<StringBuilder> gpu =
                new AssetLoader<>() {
                    @Override
                    public StringBuilder decode(long id, MemorySegment data, DecodeContext ctx) {
                        return new StringBuilder("decoded " + data.byteSize());
                    }

                    @Override
                    public boolean needsUpload() {
                        return true;
                    }

                    @Override
                    public StringBuilder upload(StringBuilder decoded, MemorySegment data) {
                        assertTrue(Thread.currentThread() == main);
                        events.add("upload");
                        return decoded.append(" uploaded");
                    }

                    @Override
                    public void unload(StringBuilder value) {
                        assertTrue(Thread.currentThread() == main);
                        events.add("unload " + value);
                    }
                };
        try (Vfs vfs = new Vfs()) {
            vfs.mountPak(pak("p.jpak", 3, 100));
            try (AssetManager m = new AssetManager(vfs, jobs)) {
                AssetType<StringBuilder> textures = m.register("texture", gpu);
                int a = textures.load("asset/0.bin");
                int b = textures.load("asset/1.bin");
                long deadline = System.nanoTime() + 5_000_000_000L;
                while (textures.state(a) != AssetState.UPLOADING
                        || textures.state(b) != AssetState.UPLOADING) {
                    m.update();
                    assertTrue(System.nanoTime() < deadline, "decode timed out");
                }
                assertNull(textures.get(a));
                assertEquals(1, m.pumpUploads(0), "a zero budget still uploads one asset");
                assertEquals(1, m.pumpUploads(0));
                m.update();
                assertEquals("decoded 100 uploaded", textures.get(a).toString());
                textures.release(a);
                m.update();
                assertEquals(List.of("upload", "upload"), events);
                m.pumpUploads(0);
                assertEquals("unload decoded 100 uploaded", events.get(2));
                assertEquals(2, m.liveCount(), "freed on the next update");
                m.update();
                assertEquals(1, m.liveCount());
            }
            assertEquals(4, events.size(), "close unloads what is left");
        }
    }

    @Test
    void dependenciesLoadWithTheAssetAndAreReleasedWithIt() throws IOException {
        Path cooked = Files.createDirectories(dir.resolve("cooked"));
        Files.writeString(cooked.resolve("stone.jmat"), "tex/a.txt,tex/b.txt");
        Files.createDirectories(cooked.resolve("tex"));
        Files.writeString(cooked.resolve("tex/a.txt"), "A");
        Files.writeString(cooked.resolve("tex/b.txt"), "B");
        try (Vfs vfs = new Vfs()) {
            vfs.mountDirectory(cooked);
            try (AssetManager m = new AssetManager(vfs, jobs)) {
                AssetType<byte[]> textures = m.register("texture", AssetLoaders.bytes());
                AssetType<String[]> materials =
                        m.register(
                                "material",
                                (id, data, ctx) -> {
                                    String[] deps =
                                            text(data.toArray(ValueLayout.JAVA_BYTE)).split(",");
                                    for (String d : deps) {
                                        ctx.dependsOn(textures, d);
                                    }
                                    return deps;
                                });
                int mat = materials.load("stone.jmat");
                await(m, () -> materials.isComplete(mat));
                assertEquals(2, materials.dependencyCount(mat));
                int ta = materials.dependency(mat, 0);
                int tb = materials.dependency(mat, 1);
                assertEquals("A", text(textures.get(ta)));
                assertEquals("B", text(textures.get(tb)));
                int shared = textures.load("tex/a.txt");
                assertEquals(ta, shared);
                assertEquals(2, textures.refCount(ta));
                materials.release(mat);
                assertEquals(AssetState.UNLOADED, textures.state(tb), "released with the material");
                assertEquals(1, textures.refCount(ta));
                assertTrue(textures.isReady(ta));
            }
        }
    }

    @Test
    void failuresAreReportedAndReloadRetries() throws IOException {
        Path cooked = Files.createDirectories(dir.resolve("cooked"));
        Files.writeString(cooked.resolve("bad.txt"), "bad");
        try (Vfs vfs = new Vfs()) {
            int mount = vfs.mountDirectory(cooked);
            try (AssetManager m = new AssetManager(vfs, jobs)) {
                AssetType<String> strict =
                        m.register(
                                "strict",
                                AssetLoaders.of(
                                        (id, data, ctx) -> {
                                            String s = text(data.toArray(ValueLayout.JAVA_BYTE));
                                            if (s.startsWith("bad")) {
                                                throw new IllegalArgumentException("bad content");
                                            }
                                            return s;
                                        }));
                int missing = strict.load("missing.txt");
                int bad = strict.load("bad.txt");
                await(
                        m,
                        () ->
                                strict.state(missing) == AssetState.FAILED
                                        && strict.state(bad) == AssetState.FAILED);
                assertInstanceOf(NoSuchFileException.class, strict.error(missing));
                assertInstanceOf(IllegalArgumentException.class, strict.error(bad));
                assertNull(strict.get(bad));

                Files.writeString(cooked.resolve("bad.txt"), "good now");
                vfs.rescan(mount);
                assertEquals(1, m.reload(AssetPaths.id("bad.txt")));
                await(m, () -> strict.isReady(bad));
                assertEquals("good now", strict.get(bad));
                assertNull(strict.error(bad));
            }
        }
    }

    @Test
    void reloadSwapsInPlaceBehindTheSameHandle() throws IOException {
        Path cooked = Files.createDirectories(dir.resolve("cooked"));
        Files.writeString(cooked.resolve("cfg.txt"), "v1");
        List<String> unloaded = new CopyOnWriteArrayList<>();
        try (Vfs vfs = new Vfs()) {
            int mount = vfs.mountDirectory(cooked);
            try (AssetManager m = new AssetManager(vfs, jobs)) {
                AssetType<String> cfg =
                        m.register(
                                "config",
                                new AssetLoader<>() {
                                    @Override
                                    public String decode(
                                            long id, MemorySegment data, DecodeContext ctx) {
                                        String s = text(data.toArray(ValueLayout.JAVA_BYTE));
                                        if (s.contains("broken")) {
                                            throw new IllegalStateException("broken");
                                        }
                                        return s;
                                    }

                                    @Override
                                    public void unload(String value) {
                                        unloaded.add(value);
                                    }
                                });
                int h = cfg.load("cfg.txt");
                await(m, () -> cfg.isReady(h));
                assertEquals("v1", cfg.get(h));

                Files.writeString(cooked.resolve("cfg.txt"), "version two");
                vfs.rescan(mount);
                m.reload(AssetPaths.id("cfg.txt"));
                assertEquals(AssetState.READY, cfg.state(h), "stays ready while reloading");
                assertEquals("v1", cfg.get(h));
                await(m, () -> cfg.version(h) == 1);
                assertEquals("version two", cfg.get(h));
                assertEquals(List.of("v1"), unloaded);

                Files.writeString(cooked.resolve("cfg.txt"), "broken");
                m.reload(AssetPaths.id("cfg.txt"));
                await(m, () -> m.inFlightCount() == 0 && m.queuedCount() == 0);
                assertEquals("version two", cfg.get(h), "a failed reload keeps the old value");
                assertInstanceOf(IllegalStateException.class, cfg.error(h));
                assertEquals(1, cfg.version(h));
            }
            assertEquals(List.of("v1", "version two"), unloaded);
        }
    }

    @Test
    void largeAssetsAndAFullStagingRingStillLoad() throws IOException {
        try (Vfs vfs = new Vfs()) {
            vfs.mountPak(pak("small.jpak", 40, 3000));
            Path big = dir.resolve("big.jpak");
            byte[] bigData = new byte[50_000];
            for (int i = 0; i < bigData.length; i++) {
                bigData[i] = (byte) i;
            }
            try (PakWriter w = new PakWriter(1)) {
                w.add("big.bin", bigData, Compression.ZSTD);
                w.write(big);
            }
            vfs.mountPak(big);
            AssetManager.Config config =
                    new AssetManager.Config().stagingBytes(8192).maxInFlight(8).ioThreads(1);
            try (AssetManager m = new AssetManager(vfs, jobs, config)) {
                AssetType<byte[]> bytes = m.register("bytes", AssetLoaders.bytes());
                int[] h = new int[40];
                for (int i = 0; i < 40; i++) {
                    h[i] = bytes.load("asset/" + i + ".bin");
                }
                int b = bytes.load("big.bin");
                await(m, () -> m.queuedCount() == 0 && m.inFlightCount() == 0);
                for (int i = 0; i < 40; i++) {
                    assertEquals(3000, bytes.get(h[i]).length);
                }
                assertArrayEquals(bigData, bytes.get(b));
            }
        }
    }

    @Test
    void releasingWhileLoadingFreesTheSlotWhenTheLoadEnds() throws IOException {
        try (Vfs vfs = new Vfs()) {
            vfs.mountPak(pak("p.jpak", 20, 5000));
            try (AssetManager m = new AssetManager(vfs, jobs)) {
                AssetType<byte[]> bytes = m.register("bytes", AssetLoaders.bytes());
                int[] h = new int[20];
                for (int i = 0; i < 20; i++) {
                    h[i] = bytes.load("asset/" + i + ".bin");
                }
                m.update(); // starts some loads
                for (int i = 0; i < 20; i++) {
                    bytes.release(h[i]);
                }
                await(m, () -> m.inFlightCount() == 0 && m.liveCount() == 0);
                assertEquals(0, m.queuedCount());
            }
        }
    }

    @Test
    void idleFramesAllocateNothing() throws IOException {
        try (Vfs vfs = new Vfs()) {
            vfs.mountPak(pak("p.jpak", 4, 100));
            try (AssetManager m = new AssetManager(vfs, jobs)) {
                AssetType<byte[]> bytes = m.register("bytes", AssetLoaders.bytes());
                int[] h = new int[4];
                for (int i = 0; i < 4; i++) {
                    h[i] = bytes.load("asset/" + i + ".bin");
                }
                await(m, () -> bytes.isComplete(h[3]) && m.inFlightCount() == 0);
                Allocations.assertNoAllocation(
                        "asset frame",
                        () -> {
                            m.update();
                            m.pumpUploads(1_000_000);
                            for (int handle : h) {
                                if (!bytes.isReady(handle) || bytes.get(handle) == null) {
                                    throw new AssertionError();
                                }
                            }
                            int again = bytes.load(ASSET0); // a cache hit: one more reference
                            bytes.release(again);
                        });
                assertFalse(m.inFlightCount() > 0);
            }
        }
    }

    private static final long ASSET0 = AssetPaths.id("asset/0.bin");
}
