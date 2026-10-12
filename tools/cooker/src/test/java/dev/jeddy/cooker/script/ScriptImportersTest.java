package dev.jeddy.cooker.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.format.DialogueData;
import dev.jeddy.asset.format.QuestData;
import dev.jeddy.cooker.CookReport;
import dev.jeddy.cooker.Cooker;
import dev.jeddy.cooker.CookerConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;

/** The .dlg and .quest importers through the cooker (M17-03). */
class ScriptImportersTest {
    @TempDir Path dir;

    static final String GHOST =
            """
            dialogue ghost
              title: The ghost
            node start
              ghost: Who goes there?
              choice "A friend." -> end
              choice "Your end." if stat(str) >= 12 -> end
            """;

    static final String QUESTS =
            """
            quest ghost
              title: The ghost
              stage 1: Find the bones.
              stage 2 done: Buried.
            """;

    @Test
    void dialoguesAndQuestsCook() throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/dialogue"));
        Files.writeString(src.resolve("Ghost.dlg"), GHOST);
        Path quests = Files.createDirectories(dir.resolve("src/quests"));
        Files.writeString(quests.resolve("crypt.quest"), QUESTS);
        try (Cooker c = new Cooker(CookerConfig.of(dir.resolve("src"), dir.resolve("cooked")))) {
            CookReport r = c.cookAll();
            assertEquals(
                    CookReport.Status.COOKED, r.item("dialogue/Ghost.dlg").status(), r.toJson());
            assertTrue(r.item("dialogue/Ghost.dlg").outputs().contains("dialogue/ghost.jdlg"));
            assertEquals(
                    CookReport.Status.COOKED, r.item("quests/crypt.quest").status(), r.toJson());
            assertTrue(r.item("quests/crypt.quest").outputs().contains("quests/crypt.jquest"));
        }
        try (Arena arena = Arena.ofConfined()) {
            byte[] bytes = Files.readAllBytes(dir.resolve("cooked/dialogue/ghost.jdlg"));
            MemorySegment m = arena.allocate(bytes.length);
            MemorySegment.copy(bytes, 0, m, ValueLayout.JAVA_BYTE, 0, bytes.length);
            DialogueData d = DialogueData.read(m);
            assertEquals("ghost", d.id());
            assertEquals(2, d.choiceCount());
            byte[] q = Files.readAllBytes(dir.resolve("cooked/quests/crypt.jquest"));
            MemorySegment mq = arena.allocate(q.length);
            MemorySegment.copy(q, 0, mq, ValueLayout.JAVA_BYTE, 0, q.length);
            QuestData data = QuestData.read(mq);
            assertEquals(1, data.questCount());
            assertEquals(2, data.questStageCount(0));
        }
    }

    @Test
    void errorsNameTheFileLineAndColumn() throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/dialogue"));
        Files.writeString(
                src.resolve("bad.dlg"), "dialogue bad\nnode start\n  a: hi\n  -> nowhere\n");
        try (Cooker c = new Cooker(CookerConfig.of(dir.resolve("src"), dir.resolve("cooked")))) {
            CookReport r = c.cookAll();
            CookReport.Item item = r.item("dialogue/bad.dlg");
            assertEquals(CookReport.Status.FAILED, item.status(), r.toJson());
            assertTrue(
                    item.error().contains("dialogue/bad.dlg:4:6: no node named 'nowhere'"),
                    item.error());
        }
    }
}
