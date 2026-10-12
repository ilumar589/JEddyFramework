package dev.jeddy.save;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

class SaveSlotsTest {
    @TempDir Path dir;

    @Test
    void namesAreSanitizedAndSlotsMapToFiles() {
        SaveSlots slots = new SaveSlots(dir);
        assertEquals(dir.resolve("quick.jsave"), slots.pathOf(SaveSlots.QUICK));
        assertEquals(dir.resolve("auto.jsave"), slots.pathOf(SaveSlots.AUTO));
        assertEquals(dir.resolve("slot-Before_the_altar.jsave"), slots.pathOf("Before the altar"));
        assertEquals("save", SaveSlots.sanitize("../../"));
        assertEquals("a-b_c9", SaveSlots.sanitize("a-b_c9!?"));
        assertEquals(48, SaveSlots.sanitize("x".repeat(100)).length());
        assertEquals("quick", SaveSlots.slotOf(Path.of("quick.jsave")));
        assertEquals("Before_the_altar", SaveSlots.slotOf(Path.of("slot-Before_the_altar.jsave")));
        assertNull(SaveSlots.slotOf(Path.of("notes.txt")));
        assertNull(SaveSlots.slotOf(Path.of("other.jsave")));
    }

    @Test
    void saveListLoadAndDelete() throws Exception {
        SaveSlots slots = new SaveSlots(dir.resolve("saves"));
        assertTrue(slots.list().isEmpty(), "no directory yet");
        SaveWriter w = new SaveWriter();
        SaveFileTest.capture(w, 64 * 16);
        slots.save(SaveSlots.QUICK, w, 1234).await();
        assertNull(slots.job().error());
        assertTrue(slots.job().bytesWritten() > SaveFile.HEADER_BYTES);
        assertEquals(1234, slots.job().captureNanos());
        w.reset();
        w.meta("title", "named");
        w.chunk(SaveFileTest.GAME, 1).int32(5);
        slots.save("Before the altar", w, 0).await();
        Files.setLastModifiedTime(slots.pathOf(SaveSlots.QUICK), FileTime.fromMillis(1_000_000));
        Files.writeString(slots.directory().resolve("notes.txt"), "not a save");
        Files.write(slots.directory().resolve("slot-broken.jsave"), new byte[] {1, 2, 3});
        List<SaveSlots.SaveInfo> list = slots.list();
        assertEquals(2, list.size(), "the broken file is skipped");
        assertEquals("Before_the_altar", list.get(0).slot(), "newest first");
        assertEquals("named", list.get(0).meta("title", "?"));
        assertEquals(SaveSlots.QUICK, list.get(1).slot());
        assertEquals("Crypt, 2 rounds", list.get(1).meta("title", "?"));
        assertEquals("?", list.get(1).meta("missing", "?"));
        SaveFile f = slots.load("Before the altar");
        assertEquals(5, f.chunk(SaveFileTest.GAME).data().int32());
        assertTrue(slots.exists(SaveSlots.QUICK));
        assertTrue(slots.delete(SaveSlots.QUICK));
        assertFalse(slots.delete(SaveSlots.QUICK));
        assertFalse(slots.exists(SaveSlots.QUICK));
        assertThrows(IOException.class, () -> slots.load(SaveSlots.QUICK));
    }

    @Test
    void aFailedWriteKeepsThePreviousFileAndReportsTheError() throws Exception {
        SaveSlots slots = new SaveSlots(dir);
        SaveWriter w = new SaveWriter();
        SaveFileTest.capture(w, 64);
        slots.save(SaveSlots.QUICK, w, 0).await();
        byte[] before = Files.readAllBytes(slots.pathOf(SaveSlots.QUICK));
        // Make the temporary file's name a directory: the write fails before the move.
        Path temp = dir.resolve("quick.jsave.tmp");
        Files.createDirectories(temp);
        w.meta("title", "second");
        slots.save(SaveSlots.QUICK, w, 0).await();
        assertNotNull(slots.job().error());
        assertEquals(0, slots.job().bytesWritten());
        assertArrayEquals(before, Files.readAllBytes(slots.pathOf(SaveSlots.QUICK)));
        Files.delete(temp);
        slots.save(SaveSlots.QUICK, w, 0).await();
        assertNull(slots.job().error());
        assertEquals("second", SaveFile.peek(slots.pathOf(SaveSlots.QUICK)).meta().get("title"));
        assertFalse(Files.exists(temp), "the temporary file was moved away");
    }

    private static void assertArrayEquals(byte[] a, byte[] b) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(a, b);
    }

    @Test
    void submitAllocatesNothingOnceTheBuffersFit() throws Exception {
        SaveJob job = new SaveJob().level(0);
        SaveWriter w = new SaveWriter();
        SaveFileTest.capture(w, 64 * 64);
        Path target = dir.resolve("quick.jsave");
        job.submit(w, target, 0);
        job.await();
        assertNull(job.error());
        // The game thread's part of a save: the capture and the hand-over, with no allocation
        // (each call waits for the previous write, so the rounds are short).
        long[] bits = new long[64];
        Allocations.assertNoAllocation(
                "capture and submit",
                6,
                20,
                () -> {
                    w.reset();
                    w.meta("title", "Crypt");
                    w.chunk(SaveFileTest.GAME, 1).int32(1);
                    w.chunk(SaveFileTest.FOG, 1).longs(bits);
                    job.submit(w, target, 0);
                });
        job.await();
        assertNull(job.error());
        assertTrue(job.writeNanos() > 0);
        assertEquals(2, SaveFile.read(target).chunks().size());
        job.close();
        assertThrows(IllegalStateException.class, () -> job.submit(w, target, 0));
    }

    @Test
    void theDirectoryFollowsThePropertyAndTheEnvironment() {
        String old = System.getProperty("jeddy.saves.dir");
        try {
            System.setProperty("jeddy.saves.dir", dir.toString());
            assertEquals(dir, SaveDirectory.of("crypt"));
            System.clearProperty("jeddy.saves.dir");
            Path p = SaveDirectory.of("crypt");
            assertTrue(p.endsWith(Path.of("crypt", "saves")), p.toString());
        } finally {
            if (old != null) {
                System.setProperty("jeddy.saves.dir", old);
            } else {
                System.clearProperty("jeddy.saves.dir");
            }
        }
    }
}
