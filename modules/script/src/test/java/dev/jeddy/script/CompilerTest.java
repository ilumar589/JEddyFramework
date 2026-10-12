package dev.jeddy.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.format.DialogueData;
import dev.jeddy.asset.format.QuestData;
import dev.jeddy.script.compile.DialogueCompiler;
import dev.jeddy.script.compile.QuestCompiler;
import dev.jeddy.script.compile.ScriptError;
import dev.jeddy.script.compile.ScriptException;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.List;

/**
 * The .dlg and .quest compilers: shapes, bytecode, the file round trip and every error (M17-02).
 */
class CompilerTest {
    static final String GHOST =
            """
            # The ghost of the crypt
            dialogue ghost
              title: The ghost of the crypt

            node start
              -> seen if quest(ghost) >= 1
              -> intro

            node intro
              ghost: Who disturbs my rest?
              ghost: You carry steel. Are you here to end me, or to listen?
              choice "I came to listen." -> listen
              choice "End you." if stat(str) >= 12 -> fight
              choice "[Charisma] We mean no harm." check cha 12 -> calm else fight
              choice "A secret way." if flag(secret) and not flag(used) -> secret

            node listen
              ghost: Then hear my tale. My bones lie under the altar, unburied.
              set quest ghost 1
              give amulet
              gold +5
              end

            node fight
              ghost: So be it!
              do startFight(2, "ghost")
              end

            node calm
              ghost: Peace, then.
              set flag calm true
              -> listen

            node secret
              ghost: You know the way.
              set flag used
              take amulet 1
              end

            node seen
              ghost: You again. Have you buried me yet?
              choice "Not yet." -> end
              choice "It is done." if quest(ghost) == 2 -> done

            node done
              ghost: Rest at last.
              set quest ghost 3
              end
            """;

    @Test
    void theGhostCompilesToNodesChoicesGotosAndCode() throws Exception {
        DialogueData d = DialogueCompiler.compile(GHOST, "ghost.dlg");
        assertEquals("ghost", d.id());
        assertEquals("The ghost of the crypt", d.title());
        assertEquals(8, d.nodeCount());
        int start = d.findNode("start");
        assertEquals(0, start);
        assertEquals(0, d.nodeLineCount(start));
        assertEquals(2, d.nodeGotoCount(start));
        assertEquals(d.findNode("seen"), d.gotoTarget(d.nodeGotoStart(start)));
        assertTrue(d.gotoCondition(d.nodeGotoStart(start)) >= 0, "the first goto has a condition");
        assertEquals(DialogueData.NONE, d.gotoCondition(d.nodeGotoStart(start) + 1));
        int intro = d.findNode("intro");
        assertEquals(2, d.nodeLineCount(intro));
        assertEquals("ghost", d.lineSpeaker(d.nodeLineStart(intro)));
        assertEquals("Who disturbs my rest?", d.lineText(d.nodeLineStart(intro)));
        assertEquals(4, d.nodeChoiceCount(intro));
        int c0 = d.nodeChoiceStart(intro);
        assertEquals("I came to listen.", d.choiceText(c0));
        assertEquals(DialogueData.NONE, d.choiceCondition(c0));
        assertEquals(d.findNode("listen"), d.choiceTarget(c0));
        assertEquals(DialogueData.NONE, d.choiceCheckAbility(c0));
        assertEquals(5, d.choiceCheckAbility(c0 + 2), "cha");
        assertEquals(12, d.choiceCheckDc(c0 + 2));
        assertEquals(d.findNode("calm"), d.choiceTarget(c0 + 2));
        assertEquals(d.findNode("fight"), d.choiceElse(c0 + 2));
        int listen = d.findNode("listen");
        assertTrue(d.nodeActions(listen) >= 0);
        assertEquals(1, d.nodeGotoCount(listen));
        assertEquals(DialogueData.END, d.gotoTarget(d.nodeGotoStart(listen)));
        // The bytecode of "stat(str) >= 12": PUSH 0, CALL stat/1, PUSH 12, GE, RETURN.
        int cond = d.choiceCondition(c0 + 1);
        int[] code = d.code();
        assertEquals("PUSH 0", Bytecode.toString(code[cond]));
        assertEquals("CALL stat/1", Bytecode.toString(code[cond + 1]));
        assertEquals("PUSH 12", Bytecode.toString(code[cond + 2]));
        assertEquals("GE", Bytecode.toString(code[cond + 3]));
        assertEquals("RETURN", Bytecode.toString(code[cond + 4]));
        assertEquals(5, d.choiceConditionLength(c0 + 1));
        // "flag(secret) and not flag(used)" short-circuits with a keep-jump.
        int cond3 = d.choiceCondition(c0 + 3);
        assertEquals("CALL flag/1", Bytecode.toString(code[cond3 + 1]));
        assertTrue(Bytecode.toString(code[cond3 + 2]).startsWith("JFK "));
        assertEquals("POP", Bytecode.toString(code[cond3 + 3]));
        // The listen node's actions: setQuest, give, addGold, RETURN.
        int a = d.nodeActions(listen);
        assertEquals("PUSH_STR #" + d.indexOf("ghost"), Bytecode.toString(code[a]));
        assertEquals("CALL setQuest/2", Bytecode.toString(code[a + 2]));
        assertEquals("CALL give/2", Bytecode.toString(code[a + 5]));
        assertEquals("CALL addGold/1", Bytecode.toString(code[a + 7]));
        assertEquals("RETURN", Bytecode.toString(code[a + 8]));
        int fight = d.findNode("fight");
        int f = d.nodeActions(fight);
        assertEquals("CALL do/3", Bytecode.toString(code[f + 3]), "the name and two arguments");
    }

    @Test
    void theFileRoundTrips() throws Exception {
        DialogueData d = DialogueCompiler.compile(GHOST, "ghost.dlg");
        byte[] bytes = d.toBytes();
        DialogueData back = DialogueData.read(MemorySegment.ofArray(bytes));
        assertEquals(d.id(), back.id());
        assertEquals(d.title(), back.title());
        assertEquals(d.nodeCount(), back.nodeCount());
        assertEquals(d.lineCount(), back.lineCount());
        assertEquals(d.choiceCount(), back.choiceCount());
        assertEquals(d.gotoCount(), back.gotoCount());
        assertEquals(d.codeLength(), back.codeLength());
        for (int i = 0; i < d.codeLength(); i++) {
            assertEquals(d.code()[i], back.code()[i], "word " + i);
        }
        for (int n = 0; n < d.nodeCount(); n++) {
            assertEquals(d.nodeId(n), back.nodeId(n));
            assertEquals(d.nodeActions(n), back.nodeActions(n));
            assertEquals(d.nodeChoiceCount(n), back.nodeChoiceCount(n));
        }
        for (int c = 0; c < d.choiceCount(); c++) {
            assertEquals(d.choiceText(c), back.choiceText(c));
            assertEquals(d.choiceTarget(c), back.choiceTarget(c));
            assertEquals(d.choiceElse(c), back.choiceElse(c));
        }
        assertEquals(d.stringId(d.indexOf("ghost")), back.stringId(back.indexOf("ghost")));
        // A damaged file: a choice target past the nodes.
        byte[] bad = bytes.clone();
        int choicesAt =
                (int)
                        MemorySegment.ofArray(bad)
                                .get(java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED, 56);
        bad[choicesAt + 5 * 4] = 99; // choice 0's target
        assertThrows(
                dev.jeddy.asset.format.AssetFormatException.class,
                () -> DialogueData.read(MemorySegment.ofArray(bad)));
    }

    private static List<ScriptError> errors(String source) {
        return DialogueCompiler.check(source).errors();
    }

    private static void assertError(String source, int line, String fragment) {
        List<ScriptError> errors = errors(source);
        assertFalse(errors.isEmpty(), "expected an error about: " + fragment);
        boolean found = false;
        for (ScriptError e : errors) {
            found |= e.line() == line && e.message().contains(fragment);
        }
        assertTrue(found, "expected line " + line + " '" + fragment + "' in " + errors);
    }

    @Test
    void everyMistakeIsReportedWithItsLine() {
        String head = "dialogue d\nnode start\n";
        assertError("node start\n  a: hi\n  end\n", 1, "write 'dialogue ID' before the first node");
        assertError("dialogue d\n", 1, "has no nodes");
        assertError(head + "  a: hi\n  end\nnode start\n  end\n", 5, "declared twice");
        assertEquals(1, errors(head + "  a: hi\n  end\nnode start\n  end\n").size(), "no cascade");
        assertError("dialogue d\nnode other\n  a: hi\n  end\n", 1, "named 'start'");
        assertError(head + "  a: hi\n", 2, "does not end");
        assertError(head + "  a: hi\n  -> nowhere\n", 4, "no node named 'nowhere'");
        assertError(
                head + "  a: hi\n  choice \"x\" -> end\n  -> end\n",
                5,
                "choices or gotos, not both");
        assertError(
                head + "  a: hi\n  -> end\n  choice \"x\" -> end\n",
                5,
                "choices or gotos, not both");
        assertError(
                head + "  a: hi\n  choice \"x\" check cha 10 -> end\n", 4, "needs 'else TARGET'");
        assertError(
                head + "  a: hi\n  choice \"x\" -> end else end\n", 4, "'else' needs a 'check'");
        assertError(
                head + "  a: hi\n  choice \"x\" check luck 10 -> end else end\n",
                4,
                "unknown ability 'luck'");
        assertError(head + "  a: hi\n  choice x -> end\n", 4, "in quotes");
        assertError(
                head + "  a: hi\n  choice \"x\" if nope(1) -> end\n", 4, "unknown function 'nope'");
        assertError(
                head + "  a: hi\n  choice \"x\" if stat(str, 2) >= 1 -> end\n",
                4,
                "takes 1 argument, not 2");
        assertError(head + "  a: hi\n  choice \"x\" if setQuest(a, 1) -> end\n", 4, "is an action");
        assertError(
                head + "  a: hi\n  choice \"x\" if level >= 1 -> end\n", 4, "needs parentheses");
        assertError(head + "  a: hi\n  choice \"x\" if (1 + -> end\n", 4, "expected a value");
        assertError(
                head + "  a: hi\n  choice \"x\" if \"unterminated -> end\n",
                4,
                "unterminated string");
        assertError(
                head + "  a: hi\n  choice \"x\" if 1 $ 2 -> end\n", 4, "unexpected character '$'");
        assertError(head + "  a:\n  end\n", 3, "has no text");
        assertError(head + "  end\n  a: hi\n", 4, "lines come before");
        assertError(head + "  -> end\n  set flag x\n", 4, "actions come before");
        assertError(head + "  set potion 1\n  end\n", 3, "'set quest' or 'set flag'");
        assertError(head + "  gold 5\n  end\n", 3, "'gold +N' or 'gold -N'");
        assertError(head + "  -> end\n  -> start\n", 4, "unreachable");
        assertError(head + "  a: hi\n  end\ndialogue e\n", 5, "one dialogue");
        assertError(head + "  a: hi\n  end\n  title: late\n", 5, "belongs in the dialogue header");
        assertError(head + "  frobnicate it\n  end\n", 3, "unknown statement");
        // Several errors at once, sorted by line.
        List<ScriptError> all =
                errors(
                        "dialogue d\n"
                                + "node start\n"
                                + "  a:\n"
                                + "  choice \"x\" -> nowhere\n"
                                + "node start\n"
                                + "  end\n");
        assertTrue(all.size() >= 3, all.toString());
        for (int i = 1; i < all.size(); i++) {
            assertTrue(all.get(i - 1).line() <= all.get(i).line());
        }
        ScriptException e =
                assertThrows(
                        ScriptException.class,
                        () -> DialogueCompiler.compile("dialogue d\n", "d.dlg"));
        assertTrue(e.getMessage().startsWith("d.dlg:1:1:"), e.getMessage());
    }

    private static void assertQuestError(String source, String fragment) {
        List<ScriptError> errors = QuestCompiler.check(source).errors();
        boolean found = false;
        for (ScriptError e : errors) {
            found |= e.message().contains(fragment);
        }
        assertTrue(found, "expected '" + fragment + "' in " + errors);
    }

    @Test
    void questsCompileAndRoundTrip() throws Exception {
        String src =
                """
                quest ghost
                  title: The ghost of the crypt
                  stage 1: Find out why the ghost lingers.
                  stage 2: Bury the bones under the altar.
                  stage 3 done: The ghost rests.
                quest rats
                  title: Rats!
                  stage 1: Kill ten rats.
                  stage 2 failed: The rats won.
                """;
        QuestData q = QuestCompiler.compile(src, "crypt.quest");
        assertEquals(2, q.questCount());
        assertEquals("ghost", q.questId(0));
        assertEquals("The ghost of the crypt", q.questTitle(0));
        assertEquals(3, q.questStageCount(0));
        assertEquals(QuestData.STAGE_DONE, q.stageKind(q.findStage(0, 3)));
        assertEquals(QuestData.STAGE_FAILED, q.stageKind(q.findStage(1, 2)));
        assertEquals("Bury the bones under the altar.", q.stageText(q.findStage(0, 2)));
        assertEquals(-1, q.findStage(0, 4));
        QuestData back = QuestData.read(MemorySegment.ofArray(q.toBytes()));
        assertEquals(2, back.questCount());
        assertEquals(q.questKey(1), back.questKey(1));
        assertEquals("The rats won.", back.stageText(back.findStage(1, 2)));
        // Errors.
        assertQuestError("quest a\n  stage 2: x\n  stage 1: y\n", "stages rise");
        assertQuestError("quest a\n  stage 0: x\n", "start at 1");
        assertQuestError("quest a\n", "has no stages");
        assertQuestError("stage 1: x\n", "after 'quest ID'");
        assertQuestError("quest a\n  stage 1: x\nquest a\n  stage 1: y\n", "declared twice");
        assertQuestError("", "no quests");
        assertQuestError("quest a\n  stage 1:\n", "no journal text");
    }
}
