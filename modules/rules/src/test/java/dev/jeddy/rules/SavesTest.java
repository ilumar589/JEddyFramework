package dev.jeddy.rules;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.format.RulesSchema;
import dev.jeddy.core.io.ByteReader;
import dev.jeddy.core.io.ByteWriter;
import dev.jeddy.testing.Allocations;
import dev.jeddy.testing.Fixtures;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The rules write themselves by content id plus a dynamic overlay and read back at the same
 * handles, rebuilt from the content at hand (DESIGN §21, D115; TASKS M16-06).
 */
class SavesTest {
    @TempDir Path temp;

    private static Rules rules(Content content, long seed) {
        return new Rules(content, 8, seed);
    }

    private static int row(Rules r, RulesSchema.Kind kind, String id) {
        int row = r.content().find(kind, id);
        assertTrue(row >= 0, id);
        return row;
    }

    /** A party member, a monster and a hand-made creature, after some play. */
    private static Encounter play(Rules r) {
        int aldric = r.buildCharacter(row(r, RulesSchema.CHARACTERS, "aldric"), 0);
        int cassius = r.buildCharacter(row(r, RulesSchema.CHARACTERS, "cassius"), 0);
        int warrior = r.buildMonster(row(r, RulesSchema.MONSTERS, "skeletalWarrior"), 1);
        int rat = r.creatures().create("Giant rat", 1);
        r.creatures().setBaseAbility(rat, Stat.DEX, 17);
        r.creatures().setBaseMaxHp(rat, 5);
        r.creatures().setDiesAtZero(rat, true);
        r.creatures().addModifier(rat, Stat.AC, BonusType.NATURAL, 1, Modifiers.SOURCE_NONE, -1);
        Encounter e = new Encounter(r);
        e.add(aldric);
        e.add(cassius);
        e.add(warrior);
        e.add(rat);
        e.start();
        e.nextTurn();
        e.nextTurn();
        r.damage(aldric, 7, 0, warrior, 0);
        r.applyCondition(aldric, row(r, RulesSchema.CONDITIONS, "shaken"), 3);
        r.applyCondition(warrior, row(r, RulesSchema.CONDITIONS, "slowed"), Conditions.INDEFINITE);
        r.creatures().setTempHp(cassius, 4, 5);
        // Cassius knows shield: a timed spell modifier on himself.
        int shield = row(r, RulesSchema.SPELLS, "shield");
        if (!r.creatures().spells(cassius).contains(shield)) {
            r.creatures().spells(cassius).add(shield);
        }
        int[] self = {cassius};
        assertTrue(r.castSpell(cassius, shield, self, 1));
        r.learnFeat(aldric, row(r, RulesSchema.FEATS, "dodge"));
        r.equip(aldric, Creatures.SLOT_WEAPON, row(r, RulesSchema.ITEMS, "greataxe"));
        r.creatures().setAooUsed(warrior, 1);
        r.creatures().setActed(aldric, true);
        e.useAction(e.current(), ActionBudget.STANDARD);
        return e;
    }

    private static void assertSame(Rules a, Rules b, int c) {
        Creatures ca = a.creatures();
        Creatures cb = b.creatures();
        assertTrue(cb.isAlive(c));
        assertEquals(ca.name(c), cb.name(c));
        assertEquals(ca.team(c), cb.team(c));
        for (int stat = 0; stat < Stat.COUNT; stat++) {
            assertEquals(ca.stat(c, stat), cb.stat(c, stat), "stat " + stat + " of " + ca.name(c));
        }
        assertEquals(ca.hp(c), cb.hp(c));
        assertEquals(ca.tempHp(c), cb.tempHp(c));
        assertEquals(ca.tempHpRounds(c), cb.tempHpRounds(c));
        assertEquals(ca.flags(c), cb.flags(c));
        assertEquals(ca.aooUsed(c), cb.aooUsed(c));
        assertEquals(ca.acted(c), cb.acted(c));
        assertEquals(ca.diesAtZero(c), cb.diesAtZero(c));
        assertEquals(ca.feats(c).size(), cb.feats(c).size());
        assertEquals(ca.spells(c).size(), cb.spells(c).size());
        for (int l = 0; l < Creatures.SPELL_LEVELS; l++) {
            assertEquals(ca.slotsUsed(c, l), cb.slotsUsed(c, l));
        }
        for (int s = 0; s < Creatures.SLOTS; s++) {
            assertEquals(ca.equipment(c, s), cb.equipment(c, s));
        }
        assertEquals(ca.conditionHandles(c).size(), cb.conditionHandles(c).size());
        assertEquals(a.fullAttackCount(c), b.fullAttackCount(c));
        assertEquals(a.armorClass(c, 0), b.armorClass(c, 0));
    }

    @Test
    void creaturesEncounterAndGeneratorRoundTrip() {
        Rules a = rules(DemoContent.get(), 7);
        Encounter ea = play(a);
        ByteWriter w = new ByteWriter();
        a.write(w);
        ea.write(w);
        w.float32(2.5f);
        int size = w.size();
        Allocations.assertNoAllocation(
                "rules write",
                () -> {
                    w.reset();
                    a.write(w);
                    ea.write(w);
                    w.float32(2.5f);
                });
        assertEquals(size, w.size());
        // Another run: the same content, a fresh engine with a different seed.
        Rules b = rules(DemoContent.get(), 99);
        Encounter eb = new Encounter(b);
        RealTimeDriver rb = new RealTimeDriver(eb);
        ByteReader r = new ByteReader(w.toBytes());
        b.read(r);
        eb.read(r);
        rb.setClock(r.float32());
        assertEquals(a.creatures().liveCount(), b.creatures().liveCount());
        for (int c = 0; c < 4; c++) {
            assertSame(a, b, c);
        }
        assertEquals(ea.isActive(), eb.isActive());
        assertEquals(ea.round(), eb.round());
        assertEquals(ea.turnIndex(), eb.turnIndex());
        assertEquals(ea.current(), eb.current());
        assertEquals(ea.size(), eb.size());
        for (int i = 0; i < ea.size(); i++) {
            assertEquals(ea.participant(i), eb.participant(i));
            assertEquals(ea.initiativeAt(i), eb.initiativeAt(i));
            assertEquals(ea.budget().left(ea.participant(i)), eb.budget().left(eb.participant(i)));
        }
        assertEquals(2.5f, rb.clock());
        // The generators continue identically: the next ten d20s agree.
        for (int i = 0; i < 10; i++) {
            assertEquals(a.rng().d20(), b.rng().d20());
        }
        // The condition came back with its rounds: it ends on the same turn in both.
        int shaken = row(a, RulesSchema.CONDITIONS, "shaken");
        assertTrue(b.hasCondition(0, shaken));
        for (int i = 0; i < 16; i++) { // four rounds of four turns; shaken lasts three
            assertEquals(a.hasCondition(0, shaken), b.hasCondition(0, shaken), "turn " + i);
            ea.nextTurn();
            eb.nextTurn();
        }
        assertFalse(b.hasCondition(0, shaken));
    }

    @Test
    void creaturesAreRebuiltFromThePatchedContentWithTheirOldWounds() throws Exception {
        Rules a = rules(DemoContent.get(), 7);
        play(a);
        int warrior = 2;
        a.damage(warrior, 5, 0, 0, 0);
        int hpBefore = a.creatures().hp(warrior);
        int maxBefore = a.creatures().stat(warrior, Stat.MAX_HP);
        ByteWriter w = new ByteWriter();
        a.write(w);
        // The patch: the warrior gets 30 hit points and loses its blade; the shaken condition is
        // gone.
        Path dir = temp.resolve("rules");
        Files.createDirectories(dir);
        Path source = Path.of("../../samples/iso-demo/assets/rules");
        try (var files = Files.list(source)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                String text = Files.readString(f);
                if (f.getFileName().toString().equals("monsters.json")) {
                    text = text.replace("\"hp\": 16", "\"hp\": 30");
                }
                Files.writeString(dir.resolve(f.getFileName()), text);
            }
        }
        Rules b = rules(new Content(DemoContent.build(dir)), 1);
        b.read(new ByteReader(w.toBytes()));
        assertEquals(hpBefore, b.creatures().hp(warrior), "the wound stayed");
        assertEquals(maxBefore + 14, b.creatures().stat(warrior, Stat.MAX_HP), "the patch applied");
        assertEquals(a.creatures().stat(0, Stat.AC), b.creatures().stat(0, Stat.AC));
        assertEquals("Giant rat", b.creatures().name(3));
        assertEquals(17, b.creatures().baseAbility(3, Stat.DEX));
        assertEquals(
                a.creatures().stat(3, Stat.AC),
                b.creatures().stat(3, Stat.AC),
                "hand-made modifier kept");
    }

    @Test
    void creatureHandlesAreRestoredWhereTheyWere() {
        Rules a = rules(DemoContent.get(), 3);
        int aldric = a.buildCharacter(row(a, RulesSchema.CHARACTERS, "aldric"), 0);
        int gone = a.buildMonster(row(a, RulesSchema.MONSTERS, "skeletalMinion"), 1);
        int stays = a.buildMonster(row(a, RulesSchema.MONSTERS, "skeletalAdept"), 1);
        a.creatures().destroy(gone);
        assertEquals(1, gone);
        ByteWriter w = new ByteWriter();
        a.write(w);
        Rules b = rules(DemoContent.get(), 3);
        b.buildMonster(row(b, RulesSchema.MONSTERS, "skeletalMinion"), 1); // a stranger at 0
        b.read(new ByteReader(w.toBytes()));
        assertTrue(b.creatures().isAlive(aldric));
        assertFalse(b.creatures().isAlive(gone), "the destroyed handle stayed free");
        assertTrue(b.creatures().isAlive(stays));
        assertEquals("Skeletal adept", b.creatures().name(stays));
        assertEquals(2, b.creatures().liveCount());
        // The freed handle is reused first by the next create, as it would have been.
        assertEquals(gone, b.creatures().create("Next", 1));
        // createAt past the count leaves free slots behind it, and over a live one replaces it.
        Creatures c = b.creatures();
        c.createAt(7, "Seven", 0);
        assertTrue(c.isAlive(7));
        assertEquals(8, c.highWaterMark());
        c.createAt(7, "Seven again", 0);
        assertEquals("Seven again", c.name(7));
        int[] expected = {6, 5, 4};
        int[] got = {c.create("x", 0), c.create("y", 0), c.create("z", 0)};
        assertArrayEquals(expected, got);
        assertNotEquals(7, c.create("w", 0));
    }

    /**
     * The golden record of rules format 1 and encounter format 1 (D117): the {@link #play} state as
     * the build that introduced the formats wrote it. A format change adds a fixture and a branch
     * in {@code read}; this test keeps the old one readable.
     */
    @Test
    void formatOneFixtureStillReads() {
        Rules a = rules(DemoContent.get(), 7);
        Encounter ea = play(a);
        ByteWriter w = new ByteWriter();
        a.write(w);
        ea.write(w);
        byte[] fixture =
                Fixtures.bytesOrCreate(SavesTest.class, "fixtures/rules-v1.bin", w.toBytes());
        Rules b = rules(DemoContent.get(), 1);
        Encounter eb = new Encounter(b);
        ByteReader r = new ByteReader(fixture);
        b.read(r);
        eb.read(r);
        assertFalse(r.hasRemaining());
        assertEquals(4, b.creatures().liveCount());
        assertEquals("Aldric", b.creatures().name(0));
        assertEquals(a.creatures().hp(0), b.creatures().hp(0));
        assertTrue(b.hasCondition(0, row(b, RulesSchema.CONDITIONS, "shaken")));
        assertTrue(b.hasCondition(2, row(b, RulesSchema.CONDITIONS, "slowed")));
        assertEquals(4, b.creatures().tempHp(1));
        assertEquals(
                a.creatures().stat(1, Stat.AC),
                b.creatures().stat(1, Stat.AC),
                "shield spell kept");
        assertEquals(ea.round(), eb.round());
        assertEquals(ea.current(), eb.current());
        assertEquals(a.rng().d20(), b.rng().d20());
        // A record from a newer build is refused rather than misread.
        ByteWriter newer = new ByteWriter();
        newer.int32(Rules.SAVE_FORMAT + 1);
        IllegalStateException e =
                assertThrows(
                        IllegalStateException.class, () -> b.read(new ByteReader(newer.toBytes())));
        assertTrue(e.getMessage().contains("format 2"), e.getMessage());
    }
}
