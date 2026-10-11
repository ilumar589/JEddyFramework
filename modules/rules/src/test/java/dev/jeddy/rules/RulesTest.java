package dev.jeddy.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.format.RulesSchema;
import dev.jeddy.math.Dice;
import dev.jeddy.math.Rng;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.Test;

/**
 * Resolution with fixed seeds (TASKS M15-04): every roll is checked against an oracle {@link Rng}
 * seeded the same way, so the tests assert the exact numbers the engine must produce, and seeds are
 * searched so that specific naturals (1, 20, a threat) come up.
 */
class RulesTest {
    private static final int TEAM_PARTY = 0;
    private static final int TEAM_UNDEAD = 1;

    private static Rules rules(long seed) {
        return new Rules(DemoContent.get(), 8, seed);
    }

    private static int character(Rules r, String id) {
        return r.buildCharacter(r.content().find(RulesSchema.CHARACTERS, id), TEAM_PARTY);
    }

    private static int monster(Rules r, String id) {
        return r.buildMonster(r.content().find(RulesSchema.MONSTERS, id), TEAM_UNDEAD);
    }

    private static int condition(Rules r, String id) {
        return r.content().find(RulesSchema.CONDITIONS, id);
    }

    /** The first seed whose first d20 is {@code wanted}. */
    private static long seedWhereFirstD20Is(int wanted) {
        for (long s = 1; ; s++) {
            if (new Rng(s).d20() == wanted) {
                return s;
            }
        }
    }

    /** The first seed whose first two d20s are {@code a} then {@code b}. */
    private static long seedWhereD20sAre(int a, int b) {
        for (long s = 1; ; s++) {
            Rng x = new Rng(s);
            if (x.d20() == a && x.d20() == b) {
                return s;
            }
        }
    }

    @Test
    void anAttackRollIsD20PlusBonusAgainstArmorClass() {
        for (long seed = 1; seed <= 40; seed++) {
            Rules r = rules(seed);
            int aldric = character(r, "aldric");
            int skeleton = monster(r, "skeletalWarrior");
            Rng oracle = new Rng(seed);
            long first = r.events().next();
            int result = r.attack(aldric, skeleton, 0, 0, 0);
            int natural = oracle.d20();
            int total = natural + 10;
            boolean hit = natural == 20 || (natural != 1 && total >= 17);
            assertEquals(natural, r.events().natural(first));
            assertEquals(total, r.events().total(first));
            assertEquals(17, r.events().against(first));
            if (!hit) {
                assertEquals(Rules.MISS, result);
                assertEquals(Events.ATTACK_MISS, r.events().kind(first));
                assertEquals(16, r.creatures().hp(skeleton));
                continue;
            }
            boolean crit = false;
            if (natural >= 19) { // a threat: confirmed by a second roll against the same AC
                int confirm = oracle.d20();
                crit = confirm == 20 || (confirm != 1 && confirm + 10 >= 17);
            }
            int longsword = Dice.parse("1d8");
            int dmg = Dice.roll(longsword, oracle) + 4;
            if (crit) {
                dmg += Dice.roll(longsword, oracle) + 4;
            }
            assertEquals(crit ? Rules.CRIT : Rules.HIT, result, "seed " + seed);
            assertEquals(crit ? Events.CRITICAL : Events.ATTACK_HIT, r.events().kind(first));
            assertEquals(Events.DAMAGE, r.events().kind(first + 1));
            assertEquals(dmg, r.events().amount(first + 1));
            assertEquals(dmg >= 16, r.isDead(skeleton));
            if (dmg >= 16) {
                assertEquals(Events.CONDITION_ON, r.events().kind(first + 2));
                assertEquals(condition(r, "dead"), r.events().ref(first + 2));
                assertEquals(Events.DEATH, r.events().kind(first + 3));
            } else {
                assertEquals(16 - dmg, r.creatures().hp(skeleton));
            }
        }
    }

    @Test
    void naturalOneMissesAndNaturalTwentyHits() {
        Rules r = rules(seedWhereFirstD20Is(1));
        int dara = character(r, "dara");
        int skeleton = monster(r, "skeletalWarrior");
        assertEquals(Rules.MISS, r.attack(dara, skeleton, 0, 100, 0)); // +100 does not help a 1
        assertEquals(1, r.lastNatural());
        r.rng().setSeed(seedWhereD20sAre(20, 1));
        // A 20 hits whatever the penalty; the 1 that follows fails the confirmation.
        long first = r.events().next();
        assertEquals(Rules.HIT, r.attack(dara, skeleton, 0, -100, 0));
        assertEquals(20, r.events().natural(first));
        assertEquals(1, r.lastNatural()); // the failed confirmation was the last roll
    }

    @Test
    void aConfirmedThreatMultipliesTheDamage() {
        // Brunhild's greataxe: threat 20, ×3. Two 20s in a row: threat, then confirmation.
        long seed = seedWhereD20sAre(20, 20);
        Rules r = rules(seed);
        int brunhild = character(r, "brunhild");
        int skeleton = monster(r, "skeletalWarrior");
        Rng oracle = new Rng(seed);
        oracle.d20();
        oracle.d20();
        int axe = Dice.parse("1d12");
        int expected = 0;
        for (int i = 0; i < 3; i++) {
            expected += Dice.roll(axe, oracle) + 9;
        }
        long first = r.events().next();
        assertEquals(Rules.CRIT, r.attack(brunhild, skeleton, 0, 0, 0));
        assertEquals(Events.CRITICAL, r.events().kind(first));
        assertEquals(expected, r.events().amount(first + 1));
        assertTrue(r.isDead(skeleton)); // at least 3 × 10
    }

    @Test
    void monstersAttackWithTheirStatBlockAndPartyMembersFallDying() {
        long seed = seedWhereFirstD20Is(20);
        Rules r = rules(seed);
        int cassius = character(r, "cassius"); // 22 hp, Con 10: dead at −10
        int skeleton = monster(r, "skeletalWarrior");
        r.creatures().setHp(cassius, 3);
        Rng oracle = new Rng(seed);
        oracle.d20();
        oracle.d20(); // confirmation of the 20 (threat 19)
        long first = r.events().next();
        int result = r.attack(skeleton, cassius, 0, 0, 0);
        int blade = Dice.parse("1d6+2");
        int dmg = Dice.roll(blade, oracle);
        if (result == Rules.CRIT) {
            dmg += Dice.roll(blade, oracle);
        }
        assertEquals(dmg, r.events().amount(first + 1));
        assertEquals(3 - dmg, r.creatures().hp(cassius));
        if (3 - dmg > -10) {
            assertTrue(r.hasCondition(cassius, condition(r, "dying")));
            assertFalse(r.isDead(cassius));
            assertFalse(r.canAct(cassius));
            assertTrue(r.creatures().hasFlag(cassius, Content.FLAG_LOSE_DEX));
            assertEquals(Events.DYING, r.events().kind(first + 3));
        } else {
            assertTrue(r.isDead(cassius));
        }
    }

    @Test
    void dyingLosesAPointARoundUntilStabilizedOrHealed() {
        Rules r = rules(seedWhereFirstD20Is(7));
        int aldric = character(r, "aldric"); // Con 14
        int dying = condition(r, "dying");
        int dead = condition(r, "dead");
        r.damage(aldric, 49, 0, -1, 0); // 47 → −2
        assertEquals(-2, r.creatures().hp(aldric));
        assertTrue(r.hasCondition(aldric, dying));
        r.endTurn(aldric); // not a 20: −3
        assertEquals(-3, r.creatures().hp(aldric));
        r.rng().setSeed(seedWhereFirstD20Is(20));
        r.endTurn(aldric); // a 20 stabilizes: unconscious, no longer dying
        assertEquals(-3, r.creatures().hp(aldric));
        assertFalse(r.hasCondition(aldric, dying));
        assertTrue(r.hasCondition(aldric, condition(r, "unconscious")));
        assertFalse(r.canAct(aldric));
        assertEquals(4, r.heal(aldric, 4, -1, 0));
        assertEquals(1, r.creatures().hp(aldric));
        assertTrue(r.canAct(aldric)); // healing above zero wakes
        r.damage(aldric, 15, 0, -1, 0); // → −14 = −Con: dead
        assertTrue(r.hasCondition(aldric, dead));
        assertTrue(r.isDead(aldric));
        assertEquals(0, r.heal(aldric, 50, -1, 0)); // the dead stay dead
        assertEquals(0, r.damage(aldric, 5, 0, -1, 0));
    }

    @Test
    void temporaryHitPointsAbsorbFirstAndExpire() {
        Rules r = rules(3);
        int c = character(r, "ewan");
        r.creatures().setTempHp(c, 5, 2);
        assertEquals(3, r.damage(c, 8, 0, -1, 0));
        assertEquals(0, r.creatures().tempHp(c));
        int max = r.creatures().stat(c, Stat.MAX_HP);
        assertEquals(max - 3, r.creatures().hp(c));
        r.creatures().setTempHp(c, 5, 2);
        r.endTurn(c);
        assertEquals(5, r.creatures().tempHp(c));
        r.endTurn(c);
        assertEquals(0, r.creatures().tempHp(c));
        assertEquals(3, r.heal(c, 10, -1, 0)); // capped at the maximum
        assertEquals(max, r.creatures().hp(c));
    }

    @Test
    void savingThrowsUseTheRightSaveAndTheNaturals() {
        for (long seed = 1; seed <= 20; seed++) {
            Rules r = rules(seed);
            int aldric = character(r, "aldric"); // Fort 6, Ref 2, Will 3
            Rng oracle = new Rng(seed);
            long first = r.events().next();
            boolean fort = r.save(aldric, Rules.SAVE_FORTITUDE, 15, -1, 0);
            int n = oracle.d20();
            assertEquals(n == 20 || (n != 1 && n + 6 >= 15), fort);
            assertEquals(fort ? Events.SAVE_SUCCESS : Events.SAVE_FAIL, r.events().kind(first));
            assertEquals(n + 6, r.events().total(first));
            assertEquals(Rules.SAVE_FORTITUDE, r.events().amount(first));
            boolean will = r.save(aldric, Rules.SAVE_WILL, 12, -1, 0);
            n = oracle.d20();
            assertEquals(n == 20 || (n != 1 && n + 3 >= 12), will);
        }
    }

    @Test
    void conditionsApplyTheirEffectsForTheirDuration() {
        Rules r = rules(5);
        int aldric = character(r, "aldric");
        int shaken = condition(r, "shaken");
        long first = r.events().next();
        r.applyCondition(aldric, shaken, 2);
        assertEquals(Events.CONDITION_ON, r.events().kind(first));
        assertEquals(8, r.creatures().stat(aldric, Stat.ATTACK));
        assertEquals(4, r.creatures().stat(aldric, Stat.FORTITUDE));
        // Applying it again keeps the longer duration and does not double the penalty.
        int h = r.applyCondition(aldric, shaken, 1);
        assertEquals(2, r.conditions().rounds(h));
        assertEquals(8, r.creatures().stat(aldric, Stat.ATTACK));
        r.applyCondition(aldric, shaken, 4);
        assertEquals(4, r.conditions().rounds(h));
        r.endTurn(aldric);
        r.endTurn(aldric);
        r.endTurn(aldric);
        assertTrue(r.hasCondition(aldric, shaken));
        long before = r.events().next();
        r.endTurn(aldric);
        assertFalse(r.hasCondition(aldric, shaken));
        assertEquals(Events.CONDITION_OFF, r.events().kind(before));
        assertEquals(10, r.creatures().stat(aldric, Stat.ATTACK));
        // An indefinite condition never ticks away.
        r.applyCondition(aldric, shaken, Conditions.INDEFINITE);
        for (int i = 0; i < 5; i++) {
            r.endTurn(aldric);
        }
        assertTrue(r.hasCondition(aldric, shaken));
        r.removeCondition(aldric, shaken);
        assertFalse(r.hasCondition(aldric, shaken));
        assertEquals(0, r.creatures().conditionHandles(aldric).size());
    }

    @Test
    void flatFootedLosesDexterityUntilTheFirstTurn() {
        Rules r = rules(5);
        int dara = character(r, "dara"); // AC 20 with Dex 5
        r.setFlatFooted(dara);
        assertEquals(15, r.creatures().stat(dara, Stat.AC));
        assertTrue(r.creatures().hasFlag(dara, Content.FLAG_FLAT_FOOTED));
        r.startTurn(dara);
        assertEquals(20, r.creatures().stat(dara, Stat.AC));
        assertTrue(r.creatures().acted(dara));
        // Stunned loses Dex and takes −2 on top.
        r.applyCondition(dara, condition(r, "stunned"), 1);
        assertEquals(13, r.creatures().stat(dara, Stat.AC));
        assertFalse(r.canAct(dara));
        r.endTurn(dara);
        assertTrue(r.canAct(dara));
    }

    @Test
    void fireballSpendsASlotAndReflexHalves() {
        for (long seed = 1; seed <= 20; seed++) {
            Rules r = rules(seed);
            Content ct = r.content();
            int cassius = character(r, "cassius");
            int warrior = monster(r, "skeletalWarrior"); // Ref 2 + 2 = 4
            int minion = monster(r, "skeletalMinion"); // Ref 1 + 1 = 2, 8 hp
            int fireball = ct.find(RulesSchema.SPELLS, "fireball");
            int[] targets = {warrior, minion};
            Rng oracle = new Rng(seed);
            long first = r.events().next();
            assertTrue(r.castSpell(cassius, fireball, targets, 2));
            assertEquals(1, r.creatures().slotsUsed(cassius, 3));
            assertFalse(r.hasSlot(cassius, fireball));
            assertFalse(r.castSpell(cassius, fireball, targets, 2)); // no slot left
            assertEquals(Events.SPELL, r.events().kind(first));
            assertEquals(18, r.events().against(first)); // the DC
            int dice = Dice.parse("5d6");
            long seq = first + 1;
            int[] hp = {16, 8};
            int[] reflex = {4, 2};
            for (int t = 0; t < 2; t++) {
                int n = oracle.d20();
                boolean saved = n == 20 || (n != 1 && n + reflex[t] >= 18);
                assertEquals(saved ? Events.SAVE_SUCCESS : Events.SAVE_FAIL, r.events().kind(seq));
                assertEquals(n, r.events().natural(seq));
                seq++;
                int dmg = Dice.roll(dice, oracle);
                if (saved) {
                    dmg /= 2;
                }
                assertEquals(Events.DAMAGE, r.events().kind(seq));
                assertEquals(dmg, r.events().amount(seq));
                assertEquals(
                        RulesSchema.indexOf(RulesSchema.DAMAGE_TYPES, "fire"),
                        r.events().against(seq));
                seq++;
                hp[t] -= dmg;
                if (hp[t] <= 0) {
                    assertTrue(r.isDead(targets[t]));
                    seq += 2; // condition on, death
                } else {
                    assertEquals(hp[t], r.creatures().hp(targets[t]));
                }
            }
            assertEquals(seq, r.events().next());
        }
    }

    @Test
    void magicMissileNeverMissesAndRayOfFrostNeedsATouchAttack() {
        long seed = seedWhereFirstD20Is(1); // the ray's touch attack rolls a 1
        Rules r = rules(seed);
        Content ct = r.content();
        int cassius = character(r, "cassius");
        int warrior = monster(r, "skeletalWarrior");
        int[] targets = {warrior};
        int ray = ct.find(RulesSchema.SPELLS, "rayOfFrost");
        long first = r.events().next();
        assertTrue(r.castSpell(cassius, ray, targets, 1));
        assertEquals(Events.ATTACK_MISS, r.events().kind(first + 1));
        assertEquals(12, r.events().against(first + 1)); // touch AC: no natural, no armor
        assertEquals(16, r.creatures().hp(warrior));
        assertEquals(1, r.creatures().slotsUsed(cassius, 0)); // the slot is spent either way
        int missile = ct.find(RulesSchema.SPELLS, "magicMissile");
        Rng oracle = new Rng(seed);
        oracle.d20();
        int expected = Dice.roll(Dice.parse("2d4+2"), oracle);
        long before = r.events().next();
        assertTrue(r.castSpell(cassius, missile, targets, 1));
        assertEquals(Events.DAMAGE, r.events().kind(before + 1));
        assertEquals(expected, r.events().amount(before + 1));
        assertEquals(16 - expected, r.creatures().hp(warrior));
    }

    @Test
    void buffsLastTheirRoundsAndRecastingReplaces() {
        Rules r = rules(9);
        Content ct = r.content();
        int cassius = character(r, "cassius"); // AC 10 + 3 = 13
        int[] self = {cassius};
        int shield = ct.find(RulesSchema.SPELLS, "shield");
        assertEquals(13, r.creatures().stat(cassius, Stat.AC));
        assertTrue(r.castSpell(cassius, shield, self, 1));
        assertEquals(17, r.creatures().stat(cassius, Stat.AC));
        for (int i = 0; i < 9; i++) {
            r.endTurn(cassius);
        }
        assertEquals(17, r.creatures().stat(cassius, Stat.AC));
        assertTrue(r.castSpell(cassius, shield, self, 1)); // recast: one instance, fresh duration
        assertEquals(17, r.creatures().stat(cassius, Stat.AC));
        for (int i = 0; i < 9; i++) {
            r.endTurn(cassius);
        }
        assertEquals(17, r.creatures().stat(cassius, Stat.AC));
        r.endTurn(cassius);
        assertEquals(13, r.creatures().stat(cassius, Stat.AC));
        // Haste: a condition for a round per caster level (five).
        int haste = ct.find(RulesSchema.SPELLS, "haste");
        int dara = character(r, "dara");
        int[] allies = {dara};
        assertTrue(r.castSpell(cassius, haste, allies, 1));
        assertTrue(r.hasCondition(dara, condition(r, "hasted")));
        assertEquals(3, r.fullAttackCount(dara)); // 1 + two-weapon + haste
        assertEquals(50, r.creatures().stat(dara, Stat.SPEED));
        assertEquals(21, r.creatures().stat(dara, Stat.AC));
        for (int i = 0; i < 4; i++) {
            r.endTurn(dara);
        }
        assertTrue(r.hasCondition(dara, condition(r, "hasted")));
        r.endTurn(dara);
        assertFalse(r.hasCondition(dara, condition(r, "hasted")));
        assertEquals(2, r.fullAttackCount(dara));
    }

    @Test
    void slowIsNegatedByAWillSave() {
        Rules r = rules(seedWhereFirstD20Is(20));
        Content ct = r.content();
        int cassius = character(r, "cassius");
        int warrior = monster(r, "skeletalWarrior");
        int[] t = {warrior};
        assertTrue(r.castSpell(cassius, ct.find(RulesSchema.SPELLS, "slow"), t, 1));
        assertFalse(r.hasCondition(warrior, condition(r, "slowed")));
        r.rng().setSeed(seedWhereFirstD20Is(1));
        r.creatures().setSlotsUsed(cassius, 3, 0);
        assertTrue(r.castSpell(cassius, ct.find(RulesSchema.SPELLS, "slow"), t, 1));
        assertTrue(r.hasCondition(warrior, condition(r, "slowed")));
        assertEquals(16, r.creatures().stat(warrior, Stat.AC));
        assertEquals(15, r.creatures().stat(warrior, Stat.SPEED));
    }

    @Test
    void itemsHealAndFeatsHavePrerequisites() {
        long seed = 11;
        Rules r = rules(seed);
        Content ct = r.content();
        int fenna = character(r, "fenna");
        int aldric = character(r, "aldric");
        r.creatures().setHp(aldric, 10);
        Rng oracle = new Rng(seed);
        int expected = Dice.roll(Dice.parse("1d8+1"), oracle);
        r.useItem(fenna, ct.find(RulesSchema.ITEMS, "healingPotion"), aldric);
        assertEquals(10 + expected, r.creatures().hp(aldric));
        int cleave = ct.find(RulesSchema.FEATS, "cleave");
        int powerAttack = ct.find(RulesSchema.FEATS, "powerAttack");
        assertFalse(r.meetsPrerequisites(aldric, cleave)); // needs power attack
        assertTrue(r.meetsPrerequisites(aldric, powerAttack));
        r.learnFeat(aldric, powerAttack);
        assertTrue(r.meetsPrerequisites(aldric, cleave));
        assertFalse(r.meetsPrerequisites(fenna, powerAttack)); // Str 12 < 13
        assertTrue(r.hasFeatFlag(aldric, Content.FEAT_POWER_ATTACK));
        assertFalse(r.hasFeatFlag(aldric, Content.FEAT_CLEAVE));
        int reflexes = ct.find(RulesSchema.FEATS, "reflexes");
        int dara = character(r, "dara");
        assertEquals(1, r.opportunityAttacksLeft(dara));
        r.learnFeat(dara, reflexes);
        assertEquals(1 + 5, r.opportunityAttacksLeft(dara));
    }

    @Test
    void attacksOfOpportunityAreLimitedPerRound() {
        Rules r = rules(seedWhereFirstD20Is(20));
        int aldric = character(r, "aldric");
        int minion = monster(r, "skeletalMinion");
        long first = r.events().next();
        assertTrue(r.attackOfOpportunity(aldric, minion) != Rules.MISS); // a 20 hits
        assertEquals(Events.OPPORTUNITY, r.events().kind(first));
        assertEquals(0, r.opportunityAttacksLeft(aldric));
        assertEquals(Rules.MISS, r.attackOfOpportunity(aldric, minion));
        r.startTurn(aldric);
        assertEquals(1, r.opportunityAttacksLeft(aldric));
    }

    @Test
    void aSeedReplaysTheSameFight() {
        long[] a = fight(42);
        long[] b = fight(42);
        long[] c = fight(43);
        assertEquals(a[0], b[0]);
        assertEquals(a[1], b[1]);
        assertTrue(a[0] != c[0] || a[1] != c[1]);
    }

    private static long[] fight(long seed) {
        Rules r = rules(seed);
        int aldric = character(r, "aldric");
        int brunhild = character(r, "brunhild");
        int warrior = monster(r, "skeletalWarrior");
        int stalker = monster(r, "skeletalStalker");
        long hash = 7;
        for (int round = 0; round < 20 && !(r.isDead(warrior) && r.isDead(stalker)); round++) {
            r.attack(aldric, warrior, 0, 0, 0);
            r.attack(brunhild, stalker, 0, 0, 0);
            r.attack(warrior, aldric, 0, 0, 0);
            r.attack(stalker, brunhild, 0, 0, 0);
            r.attack(stalker, brunhild, 1, 0, 0);
        }
        for (long s = 0; s < r.events().next(); s++) {
            hash = hash * 31 + r.events().kind(s) * 7 + r.events().amount(s);
        }
        return new long[] {hash, r.creatures().hp(aldric) * 1000L + r.creatures().hp(brunhild)};
    }

    @Test
    void aRoundOfAttacksAllocatesNothing() {
        Rules r = rules(1);
        int aldric = character(r, "aldric");
        int brunhild = character(r, "brunhild");
        int warrior = monster(r, "skeletalWarrior");
        int stalker = monster(r, "skeletalStalker");
        int shaken = condition(r, "shaken");
        int[] targets = {warrior, stalker};
        int fireball = r.content().find(RulesSchema.SPELLS, "fireball");
        int cassius = character(r, "cassius");
        int dead = condition(r, "dead");
        Creatures cs = r.creatures();
        Allocations.assertNoAllocation(
                "Rules round",
                () -> {
                    r.startTurn(aldric);
                    r.attack(aldric, warrior, 0, 0, 0);
                    r.endTurn(aldric);
                    r.startTurn(brunhild);
                    r.attack(brunhild, stalker, 0, 0, 0);
                    r.endTurn(brunhild);
                    r.startTurn(warrior);
                    r.attack(warrior, aldric, 0, 0, 0);
                    r.endTurn(warrior);
                    r.startTurn(stalker);
                    r.attack(stalker, brunhild, 0, 0, 0);
                    r.attackOfOpportunity(stalker, aldric);
                    r.endTurn(stalker);
                    r.applyCondition(aldric, shaken, 1);
                    r.save(aldric, Rules.SAVE_WILL, 15, -1, 0);
                    cs.setSlotsUsed(cassius, 3, 0);
                    r.castSpell(cassius, fireball, targets, 2);
                    // Keep everyone alive: the dead and dying would short-circuit the calls.
                    for (int c = 0; c < 5; c++) {
                        r.heal(c, 100, -1, 0);
                        r.removeCondition(c, dead);
                        cs.setHp(c, cs.stat(c, Stat.MAX_HP));
                    }
                });
    }
}
