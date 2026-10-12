package dev.jeddy.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.format.DialogueData;
import dev.jeddy.asset.format.QuestData;
import dev.jeddy.core.id.StrId;
import dev.jeddy.core.io.ByteReader;
import dev.jeddy.core.io.ByteWriter;
import dev.jeddy.script.compile.DialogueCompiler;
import dev.jeddy.script.compile.QuestCompiler;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.Test;

/** The interpreter, a conversation over a stub host, quests, flags and their saves (M17-04). */
class ConversationTest {
    /** A host with settable answers that records what the script did. */
    static final class StubHost implements ScriptHost {
        int str = 10;
        int cha = 10;
        boolean checksPass = true;
        int gold = 3;
        int amulets;
        long lastCall;
        int lastCallArg = -1;
        int checks;

        @Override
        public int stat(int ability) {
            return ability == 0 ? str : ability == 5 ? cha : 10;
        }

        @Override
        public int level() {
            return 2;
        }

        @Override
        public boolean check(int ability, int dc) {
            checks++;
            return checksPass;
        }

        @Override
        public int item(long id) {
            return id == StrId.of("amulet") ? amulets : 0;
        }

        @Override
        public void give(long id, int count) {
            if (id == StrId.of("amulet")) {
                amulets += count;
            }
        }

        @Override
        public void take(long id, int count) {
            if (id == StrId.of("amulet")) {
                amulets -= count;
            }
        }

        @Override
        public int gold() {
            return gold;
        }

        @Override
        public void addGold(int delta) {
            gold += delta;
        }

        @Override
        public int random(int n) {
            return n - 1;
        }

        @Override
        public void call(long name, int[] args, int offset, int count) {
            lastCall = name;
            lastCallArg = count > 0 ? args[offset] : -1;
        }
    }

    static DialogueData ghost() throws Exception {
        return DialogueCompiler.compile(CompilerTest.GHOST, "ghost.dlg");
    }

    static QuestData quests() throws Exception {
        return QuestCompiler.compile(
                "quest ghost\n"
                        + "  title: The ghost\n"
                        + "  stage 1: Find the bones.\n"
                        + "  stage 2: Bury them.\n"
                        + "  stage 3 done: Rest.\n",
                "crypt.quest");
    }

    @Test
    void expressionsEvaluateOnTheStackMachine() throws Exception {
        StubHost host = new StubHost();
        Quests q = new Quests(quests());
        Flags f = new Flags();
        Interpreter vm = new Interpreter(host, q, f);
        String[] exprs = {
            "1 + 2 * 3",
            "(1 + 2) * 3",
            "10 / 3",
            "10 % 3",
            "-4 + 1",
            "7 == 7",
            "7 != 7",
            "3 < 4",
            "3 >= 4",
            "true and false",
            "true or false",
            "not true",
            "1 < 2 and 2 < 3",
            "stat(str) + level()",
            "quest(ghost)",
            "flag(x)",
            "item(amulet)",
            "gold()",
            "random(6)",
            "check(cha, 15)",
            "5 / 0",
            "1 and 0 or 1",
            "not (1 == 2) and 3 > 2",
            "100000000 + 1"
        };
        int[] expected = {
            7, 9, 3, 1, -3, 1, 0, 1, 0, 0, 1, 0, 1, 12, 0, 0, 0, 3, 5, 1, 0, 1, 1, 100000001
        };
        for (int i = 0; i < exprs.length; i++) {
            String src =
                    "dialogue t\nnode start\n  a: x\n  choice \"c\" if " + exprs[i] + " -> end\n";
            DialogueData d = DialogueCompiler.compile(src, "t.dlg");
            int program = d.choiceCondition(d.nodeChoiceStart(0));
            assertEquals(expected[i], vm.run(d, program), exprs[i]);
        }
        // Short-circuit: the right side of a false 'and' never runs (no check rolled).
        host.checks = 0;
        DialogueData d =
                DialogueCompiler.compile(
                        "dialogue t\n"
                                + "node start\n"
                                + "  a: x\n"
                                + "  choice \"c\" if false and check(cha, 1) -> end\n",
                        "t.dlg");
        vm.run(d, d.choiceCondition(0));
        assertEquals(0, host.checks);
        d =
                DialogueCompiler.compile(
                        "dialogue t\n"
                                + "node start\n"
                                + "  a: x\n"
                                + "  choice \"c\" if true or check(cha, 1) -> end\n",
                        "t.dlg");
        vm.run(d, d.choiceCondition(0));
        assertEquals(0, host.checks);
    }

    @Test
    void aConversationWalksTheGhost() throws Exception {
        StubHost host = new StubHost();
        Quests q = new Quests(quests());
        Flags f = new Flags();
        Conversation c = new Conversation(new Interpreter(host, q, f));
        DialogueData ghost = ghost();
        // First visit: start routes to intro (quest at 0).
        assertTrue(c.start(ghost));
        assertEquals(ghost.findNode("intro"), c.node());
        assertTrue(c.hasLine());
        assertEquals("ghost", c.speaker());
        assertEquals("Who disturbs my rest?", c.text());
        assertFalse(c.lastLine());
        c.advance();
        assertEquals("You carry steel. Are you here to end me, or to listen?", c.text());
        assertTrue(c.lastLine());
        // Choices were evaluated on entry: str 10 hides "End you.", no secret flag hides the last.
        assertEquals(2, c.choiceCount());
        assertEquals("I came to listen.", c.choiceText(0));
        assertEquals("[Charisma] We mean no harm.", c.choiceText(1));
        assertEquals(5, c.choiceCheckAbility(1));
        assertEquals(12, c.choiceCheckDc(1));
        c.advance();
        assertTrue(c.atChoices());
        assertFalse(c.hasLine());
        c.advance(); // nothing: the choices are up
        assertTrue(c.atChoices());
        // Listen: the node's actions ran on entry.
        c.choose(0);
        assertEquals(ghost.findNode("listen"), c.node());
        assertEquals(1, q.stage("ghost"));
        assertEquals(1, host.amulets);
        assertEquals(8, host.gold);
        assertEquals(1, q.entryCount());
        assertEquals("Find the bones.", q.entryText(0));
        c.advance(); // past the last line: 'end'
        assertFalse(c.isActive());
        // Second visit: routed to 'seen'; "It is done." needs stage 2.
        assertTrue(c.start(ghost));
        assertEquals(ghost.findNode("seen"), c.node());
        assertEquals(1, c.choiceCount());
        q.set("ghost", 2);
        c.start(ghost);
        assertEquals(2, c.choiceCount());
        c.choose(1);
        assertEquals(ghost.findNode("done"), c.node());
        assertEquals(3, q.stage("ghost"));
        assertEquals(QuestData.STAGE_DONE, q.kindOf(0));
        assertEquals(3, q.entryCount());
        c.advance();
        assertFalse(c.isActive());
        // A failed check takes the else branch; a passed one the target.
        q.set("ghost", 0);
        host.checksPass = false;
        c.start(ghost);
        c.advance();
        c.advance();
        c.choose(1);
        assertEquals(ghost.findNode("fight"), c.node());
        assertEquals(StrId.of("startFight"), host.lastCall);
        assertEquals(2, host.lastCallArg);
        c.end();
        host.checksPass = true;
        c.start(ghost);
        c.advance();
        c.advance();
        c.choose(1);
        assertEquals(ghost.findNode("calm"), c.node());
        assertEquals(1, f.get("calm"));
        c.advance(); // calm -> listen (a goto after the last line)
        assertEquals(ghost.findNode("listen"), c.node());
        // Strength 12 shows the fight choice; the secret flag the fourth.
        q.set("ghost", 0);
        host.str = 12;
        f.set("secret", true);
        c.start(ghost);
        assertEquals(4, c.choiceCount());
    }

    @Test
    void routersGotosAndLoopsAreBounded() throws Exception {
        StubHost host = new StubHost();
        Conversation c = new Conversation(new Interpreter(host, new Quests(quests()), new Flags()));
        // A router whose conditions all fail ends the conversation.
        DialogueData d =
                DialogueCompiler.compile(
                        "dialogue t\nnode start\n  -> a if false\nnode a\n  x: hi\n  end\n",
                        "t.dlg");
        assertFalse(c.start(d));
        // A loop of routers is a script bug, reported rather than hanging.
        DialogueData loop =
                DialogueCompiler.compile(
                        "dialogue t\nnode start\n  -> b\nnode b\n  -> start\n", "t.dlg");
        assertThrows(IllegalStateException.class, () -> c.start(loop));
        // Choices on a node with lines show after the lines; a node without lines shows them at
        // once.
        DialogueData direct =
                DialogueCompiler.compile(
                        "dialogue t\nnode start\n  choice \"a\" -> end\n  choice \"b\" -> end\n",
                        "t.dlg");
        assertTrue(c.start(direct));
        assertTrue(c.atChoices());
        assertEquals(2, c.choiceCount());
    }

    @Test
    void runningAllocatesNothing() throws Exception {
        StubHost host = new StubHost();
        Quests q = new Quests(quests());
        Flags f = new Flags();
        f.set("secret", true);
        Conversation c = new Conversation(new Interpreter(host, q, f));
        DialogueData ghost = ghost();
        Runnable visit =
                () -> {
                    q.set(StrId.of("ghost"), 0);
                    c.start(ghost);
                    c.advance();
                    c.advance();
                    c.choose(0);
                    c.advance();
                };
        visit.run();
        Allocations.assertNoAllocation("a conversation", visit);
    }

    @Test
    void questsFlagsAndAConversationSaveAndLoad() throws Exception {
        StubHost host = new StubHost();
        QuestData data = quests();
        Quests q = new Quests(data);
        Flags f = new Flags();
        Conversation c = new Conversation(new Interpreter(host, q, f));
        DialogueData ghost = ghost();
        f.set("secret", true);
        f.set("counter", 7);
        q.set("ghost", 2);
        c.start(ghost); // seen, with both choices
        ByteWriter w = new ByteWriter();
        q.write(w);
        f.write(w);
        c.write(w);
        Allocations.assertNoAllocation(
                "script writes",
                () -> {
                    w.reset();
                    q.write(w);
                    f.write(w);
                    c.write(w);
                });
        Quests q2 = new Quests(data);
        Flags f2 = new Flags();
        Conversation c2 = new Conversation(new Interpreter(host, q2, f2));
        ByteReader r = new ByteReader(w.toBytes());
        q2.read(r);
        f2.read(r);
        c2.read(r, ghost);
        assertFalse(r.hasRemaining());
        assertEquals(2, q2.stage("ghost"));
        assertEquals(1, q2.entryCount(), "the journal entry for stage 2");
        assertEquals("Bury them.", q2.entryText(0));
        assertEquals(7, f2.get("counter"));
        assertTrue(f2.isSet("secret"));
        assertTrue(c2.isActive());
        assertEquals(ghost.findNode("seen"), c2.node());
        assertEquals(2, c2.choiceCount());
        assertEquals("It is done.", c2.choiceText(1));
        c2.choose(1);
        assertEquals(3, q2.stage("ghost"));
        // A conversation that was not running loads as not running.
        c.end();
        ByteWriter w2 = new ByteWriter();
        c.write(w2);
        c2.read(new ByteReader(w2.toBytes()), ghost);
        assertFalse(c2.isActive());
        // Re-binding to a re-cooked quest file keeps the stages by id.
        QuestData more =
                QuestCompiler.compile(
                        "quest rats\n"
                                + "  stage 1: Rats.\n"
                                + "quest ghost\n"
                                + "  title: The ghost\n"
                                + "  stage 1: Find the bones.\n"
                                + "  stage 2: Bury them.\n"
                                + "  stage 3 done: Rest.\n"
                                + "  stage 4: Epilogue.\n",
                        "crypt.quest");
        q2.bind(more);
        assertEquals(3, q2.stage("ghost"));
        assertEquals(0, q2.stage("rats"));
        assertEquals(2, q2.entryCount());
        assertEquals("Rest.", q2.entryText(1));
    }
}
