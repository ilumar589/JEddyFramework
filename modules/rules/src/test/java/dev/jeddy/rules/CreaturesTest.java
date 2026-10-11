package dev.jeddy.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.format.RulesSchema;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.Test;

/**
 * Creatures built from the demo's content, with every number worked out by hand from the JSON
 * (TASKS M15-02): ability adjustments, progressions, hit points, armor class, attack and damage
 * bonuses, saves, slots.
 */
class CreaturesTest {
    private static Rules rules() {
        return new Rules(DemoContent.get(), 8, 1);
    }

    private static int character(Rules r, String id) {
        return r.buildCharacter(r.content().find(RulesSchema.CHARACTERS, id), 0);
    }

    @Test
    void aldricTheWarden() {
        // Human warden 5: Str 16, Dex 12, Con 14, Int 10, Wis 12, Cha 10 (+2 human).
        Rules r = rules();
        int c = character(r, "aldric");
        Creatures cs = r.creatures();
        assertEquals("Aldric", cs.name(c));
        assertEquals(12, cs.ability(c, Stat.CHA));
        assertEquals(3, cs.abilityModifier(c, Stat.STR));
        assertEquals(5, cs.level(c));
        assertEquals(5, cs.baseAttackBonus(c)); // full progression
        // Hit points: d10 at level 1, then 6 per level, plus Con 2 per level, plus Toughness 3.
        assertEquals(10 + 4 * 6 + 2 * 5, cs.baseMaxHp(c));
        assertEquals(44 + 3, cs.stat(c, Stat.MAX_HP));
        assertEquals(47, cs.hp(c));
        // AC: 10 + breastplate 6 + heavy shield 2 + Dex min(1, 3) + shield wall 1.
        assertEquals(20, cs.stat(c, Stat.AC));
        assertEquals(20 - 6 - 2, r.armorClass(c, Rules.ATTACK_TOUCH));
        // Attack: BAB 5 + Str 3 + weapon focus 1 + weapon training 1 (both untyped: they stack).
        assertEquals(10, cs.stat(c, Stat.ATTACK));
        assertEquals(10, r.attackBonus(c, 0));
        assertEquals(1, r.fullAttackCount(c)); // a second iterative needs BAB 6
        // Damage: Str 3 + weapon training 1 (longsword in one hand).
        assertEquals(4, cs.stat(c, Stat.DAMAGE));
        // Saves: Fort good 2 + 5/2 = 4 (+2 Con); Ref poor 5/3 = 1 (+1 Dex); Will poor 1 (+1 Wis
        // +1 bravery).
        assertEquals(6, cs.stat(c, Stat.FORTITUDE));
        assertEquals(2, cs.stat(c, Stat.REFLEX));
        assertEquals(3, cs.stat(c, Stat.WILL));
        assertEquals(1, cs.stat(c, Stat.INITIATIVE));
        assertEquals(30, cs.stat(c, Stat.SPEED));
        assertEquals(1, cs.stat(c, Stat.AOO));
        assertTrue(r.hasFeat(c, r.content().find(RulesSchema.FEATS, "toughness")));
        assertFalse(cs.diesAtZero(c));
    }

    @Test
    void brunhildWieldsHerGreataxeInTwoHands() {
        // Ironblood berserker 5: Str 18 (+2), Dex 12, Con 16, Int 8 (−2).
        Rules r = rules();
        int c = character(r, "brunhild");
        Creatures cs = r.creatures();
        assertEquals(20, cs.ability(c, Stat.STR));
        assertEquals(6, cs.ability(c, Stat.INT));
        assertEquals(12 + 4 * 7 + 3 * 5, cs.stat(c, Stat.MAX_HP));
        // Two hands: 1.5 × Str 5 = 7, plus ferocity 2.
        assertEquals(9, cs.stat(c, Stat.DAMAGE));
        assertEquals(5 + 5, cs.stat(c, Stat.ATTACK));
        // Chain shirt 4 caps Dex at 4; Dex is only 1.
        assertEquals(10 + 4 + 1, cs.stat(c, Stat.AC));
        assertEquals(30 + 10, cs.stat(c, Stat.SPEED)); // fast movement
        assertEquals(4 + 3 + 1, cs.stat(c, Stat.FORTITUDE)); // good + Con 3 + hardy
    }

    @Test
    void daraIsSmallAndFinesse() {
        // Hearthkin shadow 5: Str 8, Dex 20, Con 12, Int 12, Wis 10, Cha 16; small.
        Rules r = rules();
        int c = character(r, "dara");
        Creatures cs = r.creatures();
        assertEquals(1, cs.size(c));
        assertEquals(3, cs.baseAttackBonus(c)); // three quarters of 5
        // Attack: BAB 3 + Dex 5 (finesse dagger) + size 1 + finesse feature 1 − two-weapon 2.
        assertEquals(8, cs.stat(c, Stat.ATTACK));
        // Damage: Str −1 (finesse adds nothing to damage).
        assertEquals(-1, cs.stat(c, Stat.DAMAGE));
        // AC: 10 + size 1 + leather 2 + Dex 5 (cap 6) + dodge feat 1 + uncanny dodge 1 (stack).
        assertEquals(20, cs.stat(c, Stat.AC));
        // Two-weapon fighting grants an extra attack at full bonus.
        assertEquals(2, r.fullAttackCount(c));
        assertEquals(8, r.attackBonus(c, 1));
        // Reflex: good 4 + Dex 5 + evasion 2 + luck 1.
        assertEquals(12, cs.stat(c, Stat.REFLEX));
        assertEquals(20, cs.stat(c, Stat.SPEED));
    }

    @Test
    void cassiusCastsArcaneSpells() {
        // Sylvan arcanist 5: Str 8, Dex 16, Con 10, Int 20, Wis 12, Cha 10.
        Rules r = rules();
        Content ct = r.content();
        int c = character(r, "cassius");
        Creatures cs = r.creatures();
        assertEquals(2, cs.baseAttackBonus(c));
        assertEquals(6 + 4 * 4, cs.stat(c, Stat.MAX_HP));
        assertEquals(Content.CASTING_ARCANE, cs.casting(c));
        assertEquals(Stat.INT, cs.castingAbility(c));
        // Will: good 4 + Wis 1 + arcane bond 1 + sylvan racial 2.
        assertEquals(8, cs.stat(c, Stat.WILL));
        assertEquals(3 + 4, cs.stat(c, Stat.INITIATIVE)); // improved initiative
        int fireball = ct.find(RulesSchema.SPELLS, "fireball");
        int magicMissile = ct.find(RulesSchema.SPELLS, "magicMissile");
        assertEquals(10 + 3 + 5, r.spellDc(c, fireball));
        assertEquals(10 + 1 + 5, r.spellDc(c, magicMissile));
        assertEquals(6, cs.spells(c).size());
        assertTrue(r.hasSlot(c, fireball)); // one third-level slot at level 5
        assertEquals(1, ct.classSlots(cs.classRow(c), 5, 3));
        assertEquals(3, ct.classSlots(cs.classRow(c), 5, 1));
        assertEquals(0, ct.classSlots(cs.classRow(c), 5, 4));
        assertEquals(0, ct.classSlots(cs.classRow(c), 3, 3)); // none at level 3
    }

    @Test
    void monstersUseTheirStatBlocks() {
        Rules r = rules();
        Content ct = r.content();
        int c = r.buildMonster(ct.find(RulesSchema.MONSTERS, "skeletalWarrior"), 1);
        Creatures cs = r.creatures();
        assertEquals(16, cs.hp(c));
        assertTrue(cs.diesAtZero(c));
        // AC: 10 + Dex 2 + natural 2 + the hauberk's armor 3 from its effects.
        assertEquals(17, cs.stat(c, Stat.AC));
        assertEquals(10 + 2, r.armorClass(c, Rules.ATTACK_TOUCH));
        assertEquals(2, cs.baseAttackBonus(c));
        assertEquals(3 + 2, cs.stat(c, Stat.WILL)); // undead racial +2
        assertEquals(2 + 4, cs.stat(c, Stat.INITIATIVE));
        assertEquals(1, cs.monsterAttackCount(c));
        assertEquals(4, ct.attackBonus(cs.monsterAttack(c)));
        assertEquals(1, cs.team(c));
    }

    @Test
    void creaturesAreRecycledWithTheirModifiers() {
        Rules r = rules();
        int a = character(r, "aldric");
        int live = r.modifiers().liveCount();
        assertTrue(live > 0);
        r.creatures().destroy(a);
        assertEquals(0, r.modifiers().liveCount());
        assertFalse(r.creatures().isAlive(a));
        int b = character(r, "fenna");
        assertEquals(a, b); // the slot is reused
        assertEquals("Fenna", r.creatures().name(b));
        // Stoneborn hierophant 5: Con 16, Wis 19; Fort good 4 + 3 + racial 2; Will 4 + 4 + iron
        // will 2 + steadfast 1.
        assertEquals(9, r.creatures().stat(b, Stat.FORTITUDE));
        assertEquals(11, r.creatures().stat(b, Stat.WILL));
    }

    @Test
    void statResolutionAllocatesNothing() {
        Rules r = rules();
        int c = character(r, "aldric");
        Creatures cs = r.creatures();
        int[] sink = new int[1];
        Allocations.assertNoAllocation(
                "Creatures.stat",
                () -> {
                    for (int s = 0; s < Stat.COUNT; s++) {
                        sink[0] += cs.stat(c, s);
                    }
                });
    }
}
