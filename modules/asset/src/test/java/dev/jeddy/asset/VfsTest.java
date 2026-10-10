package dev.jeddy.asset;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.pak.PakWriter;
import dev.jeddy.asset.pak.PakWriter.Compression;
import dev.jeddy.compress.ZstdDecompressor;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Mount order, loose directories, rescans, unmount safety and lookups (TASKS M4-04). */
class VfsTest {
    @TempDir Path dir;

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private Path pak(String name, String... pathsAndContents) throws IOException {
        Path file = dir.resolve(name);
        try (PakWriter w = new PakWriter(3)) {
            for (int i = 0; i < pathsAndContents.length; i += 2) {
                w.add(pathsAndContents[i], utf8(pathsAndContents[i + 1]), Compression.AUTO);
            }
            w.write(file);
        }
        return file;
    }

    private static String read(Vfs vfs, String path, ZstdDecompressor d) throws IOException {
        long id = AssetPaths.id(path);
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(Math.max(1, vfs.size(id)));
            long n = vfs.read(id, out, 0, out.byteSize(), d);
            return new String(
                    out.asSlice(0, n).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
        }
    }

    @Test
    void normalizesAssetPaths() {
        assertEquals("tex/a.ktx2", AssetPaths.normalize("Tex\\A.KTX2"));
        assertEquals("tex/a.ktx2", AssetPaths.normalize("./tex//a.ktx2"));
        assertEquals("tex/a.ktx2", AssetPaths.normalize("/tex/a.ktx2"));
        String n = "mesh/b.jmesh";
        assertTrue(n == AssetPaths.normalize(n), "no copy when already normalized");
        assertThrows(IllegalArgumentException.class, () -> AssetPaths.normalize("a/../b"));
        assertThrows(IllegalArgumentException.class, () -> AssetPaths.normalize("//"));
        assertFalse(AssetPaths.isNormalized("a/./b"));
    }

    @Test
    void laterMountsOverrideEarlierOnes() throws IOException {
        Path base = pak("base.jpak", "a.txt", "base a", "b.txt", "base b".repeat(50));
        Path patch = pak("patch.jpak", "b.txt", "patched b");
        Path loose = Files.createDirectories(dir.resolve("cooked/sub"));
        Files.writeString(loose.resolve("C.TXT"), "loose c"); // normalized to sub/c.txt
        Files.writeString(dir.resolve("cooked/a.txt"), "loose a");
        Files.writeString(dir.resolve("cooked/.hidden"), "ignored");
        Files.writeString(dir.resolve("cooked/partial.tmp"), "ignored");
        try (Vfs vfs = new Vfs();
                ZstdDecompressor d = new ZstdDecompressor()) {
            int baseMount = vfs.mountPak(base);
            assertEquals("base b".repeat(50), read(vfs, "b.txt", d));
            int patchMount = vfs.mountPak(patch);
            int looseMount = vfs.mountDirectory(dir.resolve("cooked"));
            assertEquals(3, vfs.mountCount());
            assertEquals(3, vfs.size());
            assertEquals("loose a", read(vfs, "a.txt", d));
            assertEquals("patched b", read(vfs, "B.txt", d));
            assertEquals("loose c", read(vfs, "sub/c.txt", d));
            assertTrue(vfs.isLoose(AssetPaths.id("a.txt")));
            assertFalse(vfs.isLoose(AssetPaths.id("b.txt")));
            assertFalse(vfs.exists(".hidden"));
            assertFalse(vfs.exists("partial.tmp"));
            assertTrue(vfs.describe(AssetPaths.id("b.txt")).contains("patch.jpak"));

            vfs.unmount(patchMount);
            assertEquals("base b".repeat(50), read(vfs, "b.txt", d));
            vfs.unmount(looseMount);
            assertEquals("base a", read(vfs, "a.txt", d));
            assertFalse(vfs.exists("sub/c.txt"));
            assertEquals(-1, vfs.size(AssetPaths.id("sub/c.txt")));
            assertThrows(
                    NoSuchFileException.class,
                    () -> vfs.read(AssetPaths.id("sub/c.txt"), MemorySegment.NULL, 0, 0, d));
            assertThrows(IllegalArgumentException.class, () -> vfs.unmount(patchMount));
            assertThrows(IllegalArgumentException.class, () -> vfs.rescan(baseMount));
        }
    }

    @Test
    void pinsKeepStoredEntriesMappedPastUnmount() throws IOException {
        Path file = dir.resolve("pins.jpak");
        byte[] stored = "stored bytes, read in place".getBytes(StandardCharsets.UTF_8);
        try (PakWriter w = new PakWriter(3)) {
            w.add("audio/theme.ogg", stored, Compression.STORE);
            w.add(
                    "text/notes.txt",
                    "squeeze me ".repeat(200).getBytes(StandardCharsets.UTF_8),
                    Compression.ZSTD);
            w.write(file);
        }
        try (Vfs vfs = new Vfs()) {
            int mount = vfs.mountPak(file);
            assertEquals(null, vfs.pin(AssetPaths.id("text/notes.txt")), "compressed: no pin");
            assertEquals(null, vfs.pin(AssetPaths.id("missing.bin")));
            Vfs.Pin pin = vfs.pin(AssetPaths.id("audio/theme.ogg"));
            assertArrayEquals(stored, pin.data().toArray(ValueLayout.JAVA_BYTE));
            vfs.unmount(mount);
            assertFalse(vfs.exists("audio/theme.ogg"));
            assertArrayEquals(stored, pin.data().toArray(ValueLayout.JAVA_BYTE), "still mapped");
            pin.close();
            pin.close(); // idempotent
        }
    }

    @Test
    void rescanPicksUpCookerOutput() throws IOException {
        Path cooked = Files.createDirectories(dir.resolve("cooked"));
        try (Vfs vfs = new Vfs();
                ZstdDecompressor d = new ZstdDecompressor()) {
            int m = vfs.mountDirectory(cooked);
            assertEquals(0, vfs.size());
            Files.writeString(cooked.resolve("new.txt"), "fresh");
            assertFalse(vfs.exists("new.txt"));
            vfs.rescan(m);
            assertEquals("fresh", read(vfs, "new.txt", d));
            assertEquals(cooked.toAbsolutePath().normalize(), vfs.directory(m));

            // A file that grew after the scan does not fit the scanned size.
            Files.writeString(cooked.resolve("new.txt"), "fresh and longer");
            long id = AssetPaths.id("new.txt");
            try (Arena a = Arena.ofConfined()) {
                MemorySegment out = a.allocate(5);
                IOException e = assertThrows(IOException.class, () -> vfs.read(id, out, 0, 5, d));
                assertTrue(e.getMessage().contains("grew"), e.getMessage());
            }
            vfs.rescan(m);
            assertEquals(16, vfs.size(id));
        }
    }

    @Test
    void unmountWaitsForReadersInFlight() throws Exception {
        byte[] big = new byte[4 << 20];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) (i * 31 % 253);
        }
        Path file = dir.resolve("big.jpak");
        try (PakWriter w = new PakWriter(1)) {
            w.add("big.bin", big, Compression.ZSTD);
            w.write(file);
        }
        try (Vfs vfs = new Vfs()) {
            long id = AssetPaths.id("big.bin");
            AtomicBoolean stop = new AtomicBoolean();
            AtomicReference<Throwable> error = new AtomicReference<>();
            Thread reader =
                    new Thread(
                            () -> {
                                try (Arena a = Arena.ofConfined();
                                        ZstdDecompressor d = new ZstdDecompressor()) {
                                    MemorySegment out = a.allocate(big.length);
                                    while (!stop.get()) {
                                        try {
                                            vfs.read(id, out, 0, big.length, d);
                                        } catch (NoSuchFileException expected) {
                                            // between unmount and the next mount
                                        }
                                    }
                                } catch (Throwable t) {
                                    error.set(t);
                                }
                            });
            reader.start();
            for (int i = 0; i < 20; i++) {
                int m = vfs.mountPak(file);
                Thread.sleep(2);
                vfs.unmount(m);
            }
            stop.set(true);
            reader.join();
            if (error.get() != null) {
                throw new AssertionError("reader failed", error.get());
            }
        }
    }

    @Test
    void lookupsAndPakReadsAllocateNothing() throws IOException {
        Path base = pak("base.jpak", "a.txt", "alpha".repeat(40), "b.txt", "b");
        try (Vfs vfs = new Vfs();
                ZstdDecompressor d = new ZstdDecompressor();
                Arena arena = Arena.ofConfined()) {
            vfs.mountPak(base);
            long a = AssetPaths.id("a.txt");
            long b = AssetPaths.id("b.txt");
            MemorySegment out = arena.allocate(256);
            Allocations.assertNoAllocation(
                    "vfs lookup + read",
                    () -> {
                        try {
                            if (vfs.exists(a) && vfs.size(b) == 1) {
                                vfs.read(a, out, 0, 256, d);
                                vfs.read(b, out, 0, 256, d);
                            }
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    });
            byte[] expected = utf8("alpha".repeat(40));
            vfs.read(a, out, 0, 256, d);
            assertArrayEquals(
                    expected, out.asSlice(0, expected.length).toArray(ValueLayout.JAVA_BYTE));
        }
    }
}
