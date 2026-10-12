package dev.jeddy.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.format.RulesSchema;
import dev.jeddy.math.Rng;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.Test;

/**
 * Initiative, turns, budgets, attacks of opportunity, joining and leaving, and the two drivers
 * giving the same fight (TASKS M15-05).
 */
class EncounterTest {
    private static Rules rules(long seed) {
        return new Rules(DemoContent.get(), 8, seed);
    }

    private static int character(Rules r, String id) {
        return r.buildCharacter(r.content().find(RulesSchema.CHARACTERS, id), 0);
    }

    private static int monster(Rules r, String id) {
        return r.buildMonster(r.content().find(RulesSchema.MONSTERS, id), 1);
    }

    @Test
    void initiativeOrdersByRollThenDexterityThenJoinOrder() {
        for (long seed = 1; seed <= 30; seed++) {
            Rules r = rules(seed);
            int aldric = character(r, "aldric"); // init +1, Dex 12
            int dara = character(r, "dara"); // init +5, Dex 20
            int warrior = monster(r, "skeletalWarrior"); // init +6, Dex 14
            int minion = monster(r, "skeletalMinion"); // init +1, Dex 13
            Encounter e = new Encounter(r);
            e.add(aldric);
            e.add(dara);
            e.add(warrior);
            e.add(minion);
            Rng oracle = new Rng(seed);
            int[] init = {oracle.d20() + 1, oracle.d20() + 5, oracle.d20() + 6, oracle.d20() + 1};
            int[] dex = {12, 20, 14, 13};
            int[] who = {aldric, dara, warrior, minion};
            e.start();
            assertEquals(1, e.round());
            assertEquals(-1, e.current());
            assertEquals(4, e.size());
            for (int i = 0; i < 3; i++) {
                int a = e.indexOf(who[i]);
                for (int j = i + 1; j < 4; j++) {
                    int b = e.indexOf(who[j]);
                    boolean aFirst =
                            init[i] != init[j]
                                    ? init[i] > init[j]
                                    : dex[i] != dex[j] ? dex[i] > dex[j] : true; // joined first
                    assertEquals(aFirst, a < b, "seed " + seed);
                }
            }
            for (int i = 0; i < 4; i++) {
                assertEquals(init[i], e.initiativeAt(e.indexOf(who[i])));
                assertTrue(r.creatures().hasFlag(who[i], Content.FLAG_FLAT_FOOTED));
            }
        }
    }

    @Test
    void turnsCycleAndRoundsAdvance() {
        Rules r = rules(2);
        int aldric = character(r, "aldric");
        int warrior = monster(r, "skeletalWarrior");
        Encounter e = new Encounter(r);
        e.add(aldric);
        e.add(warrior);
        e.start();
        long roundEvent = r.events().next() - 1;
        assertEquals(Events.ROUND, r.events().kind(roundEvent));
        int first = e.nextTurn();
        assertEquals(e.participant(0), first);
        assertEquals(first, e.current());
        assertFalse(r.creatures().hasFlag(first, Content.FLAG_FLAT_FOOTED));
        assertTrue(e.hasAction(first, ActionBudget.STANDARD));
        int second = e.nextTurn();
        assertEquals(e.participant(1), second);
        assertEquals(1, e.round());
        assertEquals(first, e.nextTurn()); // wraps
        assertEquals(2, e.round());
        e.end();
        assertEquals(-1, e.nextTurn());
        assertFalse(e.isActive());
    }

    @Test
    void theBudgetHasStandardMoveSwiftAndAStep() {
        Rules r = rules(2);
        int aldric = character(r, "aldric");
        Encounter e = new Encounter(r);
        e.add(aldric);
        e.start();
        assertFalse(e.hasAction(aldric, ActionBudget.STANDARD)); // no turn yet
        e.nextTurn();
        assertTrue(e.hasAction(aldric, ActionBudget.FULL_ROUND));
        assertTrue(e.useAction(aldric, ActionBudget.STEP));
        assertFalse(e.hasAction(aldric, ActionBudget.STEP));
        assertTrue(e.useAction(aldric, ActionBudget.STANDARD));
        assertFalse(e.hasAction(aldric, ActionBudget.FULL_ROUND));
        assertTrue(e.hasAction(aldric, ActionBudget.MOVE));
        assertTrue(e.useAction(aldric, ActionBudget.MOVE));
        assertFalse(e.useAction(aldric, ActionBudget.MOVE));
        assertTrue(e.useAction(aldric, ActionBudget.SWIFT));
        assertEquals(0, e.budget().left(aldric));
        // Next round: a full-round action, then two move actions out of standard and move.
        e.nextTurn();
        assertTrue(e.useAction(aldric, ActionBudget.FULL_ROUND));
        assertFalse(e.hasAction(aldric, ActionBudget.MOVE));
        e.nextTurn();
        e.reportMovement(aldric);
        assertFalse(e.hasAction(aldric, ActionBudget.STEP)); // moved: no step
        assertTrue(e.useAction(aldric, ActionBudget.MOVE));
        assertTrue(e.useAction(aldric, ActionBudget.MOVE)); // the standard action downgraded
        assertFalse(e.hasAction(aldric, ActionBudget.STANDARD));
        // A creature that cannot act has no budget.
        r.applyCondition(aldric, r.content().find(RulesSchema.CONDITIONS, "stunned"), 2);
        e.nextTurn();
        assertFalse(e.hasAction(aldric, ActionBudget.STANDARD));
        assertEquals(0, e.budget().left(aldric));
    }

    @Test
    void provokingDrawsOneAttackPerThreateningEnemy() {
        Rules r = rules(3);
        int aldric = character(r, "aldric");
        int dara = character(r, "dara");
        int warrior = monster(r, "skeletalWarrior");
        int minion = monster(r, "skeletalMinion");
        Encounter e = new Encounter(r);
        e.add(aldric);
        e.add(dara);
        e.add(warrior);
        e.add(minion);
        e.start();
        int[] threat = {warrior, minion, dara, aldric};
        assertEquals(0, e.provoke(aldric, threat, 4)); // everyone is still flat-footed
        r.startTurn(warrior);
        r.startTurn(minion);
        r.startTurn(dara);
        long first = r.events().next();
        assertEquals(2, e.provoke(aldric, threat, 4)); // the two enemies, not Dara nor Aldric
        assertEquals(Events.OPPORTUNITY, r.events().kind(first));
        assertEquals(warrior, r.events().actor(first));
        assertEquals(0, e.provoke(aldric, threat, 4)); // spent for the round
        assertEquals(0, r.opportunityAttacksLeft(warrior));
        r.startTurn(warrior);
        assertEquals(1, e.provoke(dara, threat, 2));
    }

    @Test
    void creaturesJoinAndLeaveMidFight() {
        Rules r = rules(4);
        int aldric = character(r, "aldric");
        int warrior = monster(r, "skeletalWarrior");
        int minion = monster(r, "skeletalMinion");
        Encounter e = new Encounter(r);
        e.add(aldric);
        e.add(warrior);
        e.start();
        int first = e.nextTurn();
        e.join(minion);
        assertEquals(3, e.size());
        assertEquals(first, e.current()); // the current creature kept its turn
        assertTrue(r.creatures().hasFlag(minion, Content.FLAG_FLAT_FOOTED));
        int at = e.indexOf(minion);
        for (int i = 0; i < at; i++) {
            assertTrue(e.initiativeAt(i) >= e.initiativeAt(at));
        }
        // Everyone gets a turn in the next three calls.
        int seen = 0;
        for (int i = 0; i < 3; i++) {
            int c = e.nextTurn();
            seen |= c == aldric ? 1 : c == warrior ? 2 : 4;
        }
        assertEquals(7, seen);
        int current = e.current();
        e.leave(current);
        assertEquals(2, e.size());
        assertFalse(e.contains(current));
        int next = e.nextTurn();
        assertTrue(next != current);
        assertTrue(e.contains(next));
        e.leave(next);
        e.leave(e.participant(0));
        assertEquals(0, e.size());
        assertEquals(-1, e.nextTurn());
    }

    @Test
    void theFightIsOverWhenOneTeamStands() {
        Rules r = rules(5);
        int aldric = character(r, "aldric");
        int warrior = monster(r, "skeletalWarrior");
        int minion = monster(r, "skeletalMinion");
        Encounter e = new Encounter(r);
        e.add(aldric);
        e.add(warrior);
        e.add(minion);
        e.start();
        assertEquals(2, e.teamsStanding());
        assertFalse(e.isOver());
        assertEquals(-1, e.winner());
        r.damage(warrior, 100, 0, aldric, 0);
        assertFalse(e.isOver());
        r.damage(minion, 100, 0, aldric, 0);
        assertTrue(e.isOver());
        assertEquals(0, e.winner());
        r.damage(aldric, 48, 0, minion, 0); // dying counts as out
        assertEquals(0, e.teamsStanding());
        assertEquals(-1, e.winner());
    }

    /** One script of orders, run by either driver. */
    private static final class Script implements TurnListener {
        final int[] party;
        final int[] undead;
        final int[] threat = new int[8];

        Script(int[] party, int[] undead) {
            this.party = party;
            this.undead = undead;
        }

        @Override
        public void onTurn(Encounter e, int c) {
            Rules r = e.rules();
            int[] foes = r.creatures().team(c) == 0 ? undead : party;
            int target = -1;
            for (int f : foes) {
                if (!r.isDead(f) && r.canAct(f)) {
                    target = f;
                    break;
                }
            }
            if (target < 0) {
                return;
            }
            // Walk up (provoking anyone who threatens), then full attack.
            int n = 0;
            for (int f : foes) {
                threat[n++] = f;
            }
            e.reportMovement(c);
            e.useAction(c, ActionBudget.MOVE);
            e.provoke(c, threat, n);
            if (e.useAction(c, ActionBudget.STANDARD)) {
                for (int i = 0; i < r.fullAttackCount(c); i++) {
                    r.attack(c, target, i, 0, 0);
                }
            }
        }
    }

    private static long[] turnBased(long seed) {
        Rules r = rules(seed);
        Encounter e = setUp(r);
        TurnBasedDriver d = new TurnBasedDriver(e);
        Script s = new Script(party(e), undead(e));
        d.setListener(s);
        int guard = 0;
        while (!e.isOver() && guard++ < 200) {
            d.endTurn();
        }
        return summary(e);
    }

    private static long[] realTime(long seed, float dt) {
        Rules r = rules(seed);
        Encounter e = setUp(r);
        RealTimeDriver d = new RealTimeDriver(e);
        Script s = new Script(party(e), undead(e));
        d.setListener(s);
        int guard = 0;
        while (!e.isOver() && guard++ < 100_000) {
            d.advance(dt);
        }
        return summary(e);
    }

    private static Encounter setUp(Rules r) {
        Encounter e = new Encounter(r);
        e.add(character(r, "aldric"));
        e.add(character(r, "brunhild"));
        e.add(character(r, "dara"));
        e.add(monster(r, "skeletalWarrior"));
        e.add(monster(r, "skeletalWarrior"));
        e.add(monster(r, "skeletalStalker"));
        e.add(monster(r, "skeletalMinion"));
        e.start();
        return e;
    }

    private static int[] party(Encounter e) {
        return new int[] {0, 1, 2};
    }

    private static int[] undead(Encounter e) {
        return new int[] {3, 4, 5, 6};
    }

    private static long[] summary(Encounter e) {
        Rules r = e.rules();
        long hash = 7;
        for (long s = 0; s < r.events().next(); s++) {
            hash = hash * 31 + r.events().kind(s) * 7L + r.events().amount(s) + r.events().actor(s);
        }
        long hp = 0;
        for (int c = 0; c < 7; c++) {
            hp = hp * 131 + r.creatures().hp(c);
        }
        return new long[] {hash, hp, e.round(), e.winner()};
    }

    @Test
    void bothDriversPlayTheSameFight() {
        for (long seed = 1; seed <= 5; seed++) {
            long[] tb = turnBased(seed);
            long[] rtFine = realTime(seed, 1f / 60);
            long[] rtCoarse = realTime(seed, 0.75f);
            assertEquals(tb[0], rtFine[0], "events, seed " + seed);
            assertEquals(tb[1], rtFine[1], "hit points, seed " + seed);
            assertEquals(tb[2], rtFine[2], "rounds, seed " + seed);
            assertEquals(tb[0], rtCoarse[0], "events at a coarse step, seed " + seed);
            assertTrue(tb[3] >= 0, "a winner, seed " + seed);
        }
    }

    @Test
    void realTimeTurnsAreSpreadOverTheRound() {
        Rules r = rules(8);
        Encounter e = setUp(r); // 7 participants
        RealTimeDriver d = new RealTimeDriver(e);
        int[] turns = new int[1];
        d.setListener((enc, c) -> turns[0]++);
        assertEquals(1, d.advance(0f)); // the first turn is due at once
        assertEquals(0, d.advance(0.5f)); // the second at 6/7 s
        assertEquals(1, d.advance(0.4f));
        assertEquals(1, e.round());
        int started = d.advance(5.5f); // the rest of round 1 and the start of round 2
        assertEquals(5 + 1, started);
        assertEquals(2, e.round());
        assertEquals(0, e.turnIndex());
        assertTrue(d.clock() < RealTimeDriver.ROUND_SECONDS);
        // Turn-based play moved the turn along; sync puts the clock at that turn's offset.
        e.nextTurn();
        e.nextTurn();
        d.sync();
        assertEquals(d.offset(2), d.clock(), 1e-6f);
        assertEquals(0, d.advance(0.1f));
        assertEquals(1, d.advance(RealTimeDriver.ROUND_SECONDS / 7));
        assertEquals(1 + 1 + 6 + 1, turns[0]); // the two manual turns bypass the driver
    }

    @Test
    void aTurnAllocatesNothing() {
        Rules r = rules(1);
        Encounter e = setUp(r);
        RealTimeDriver d = new RealTimeDriver(e);
        Script s = new Script(party(e), undead(e));
        d.setListener(s);
        Creatures cs = r.creatures();
        int dead = r.content().find(RulesSchema.CONDITIONS, "dead");
        Allocations.assertNoAllocation(
                "Encounter turn",
                () -> {
                    d.advance(1f);
                    for (int c = 0; c < 7; c++) {
                        r.removeCondition(c, dead);
                        r.heal(c, 100, -1, 0);
                        cs.setHp(c, cs.stat(c, Stat.MAX_HP));
                    }
                });
    }

    /**
     * The order lists and the budget are sized from the rules' creature capacity, so every creature
     * the rules can hold joins and leaves a running fight without the lists growing (the demo's 120
     * s perf capture caught them growing when the 134th skeleton joined).
     */
    @Test
    void everyCreatureJoiningAndLeavingAllocatesNothing() {
        int n = 160;
        Rules r = new Rules(DemoContent.get(), n, 9);
        int[] creature = new int[n];
        for (int i = 0; i < n; i++) {
            creature[i] =
                    i < 2
                            ? character(r, i == 0 ? "aldric" : "dara")
                            : monster(r, i % 2 == 0 ? "skeletalWarrior" : "skeletalStalker");
        }
        Encounter e = new Encounter(r);
        e.add(creature[0]);
        e.add(creature[1]);
        e.start();
        Allocations.assertNoAllocation(
                "Encounter join and leave",
                () -> {
                    for (int i = 2; i < n; i++) {
                        e.join(creature[i]);
                    }
                    assertEquals(n, e.size());
                    for (int i = 2; i < n; i++) {
                        e.leave(creature[i]);
                    }
                });
        assertEquals(2, e.size());
    }
}
