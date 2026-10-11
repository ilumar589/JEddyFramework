package dev.jeddy.cooker.rules;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.format.RulesData;
import dev.jeddy.asset.format.RulesSchema;
import dev.jeddy.cooker.CookReport;
import dev.jeddy.cooker.Cooker;
import dev.jeddy.cooker.CookerConfig;
import dev.jeddy.core.json.Json;
import dev.jeddy.math.Dice;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cooking rules content (TASKS M15-03): validation messages, references, nesting, the demo's set.
 */
class RulesImporterTest {
    @TempDir Path dir;

    private static final String CONDITIONS =
            """
            [{"id": "shaken", "name": "Shaken", "effects": [
               {"op": "modify", "stat": "attack", "type": "penalty", "value": -2}]},
             {"id": "dead", "name": "Dead", "flags": ["dead", "cannotAct"]}]
            """;
    private static final String FEATS =
            """
            [{"id": "dodge", "name": "Dodge", "minDex": 13,
              "effects": [{"op": "modify", "stat": "ac", "type": "dodge", "value": 1}]},
             {"id": "mobility", "name": "Mobility", "requires": ["dodge"], "flags": ["reflexes"]}]
            """;
    private static final String CLASSES =
            """
            [{"id": "mage", "name": "Mage", "hitDie": 6, "bab": "half", "will": "good", "casting": "arcane",
              "castingAbility": "int",
              "slots": [{"level": 1, "slots": [3, 1]}, {"level": 2, "slots": [4, 2]}],
              "features": [{"level": 1, "name": "Bond", "effects": [{"op": "modify", "stat": "will", "value": 1}]}]}]
            """;
    private static final String SPELLS =
            """
            [{"id": "fear", "name": "Fear", "arcaneLevel": 1, "save": "will", "saveResult": "negates",
              "effects": [{"op": "condition", "ref": "shaken", "rounds": 3}]}]
            """;
    private static final String MONSTERS =
            """
            [{"id": "rat", "name": "Rat", "size": "tiny", "level": 1, "hp": 4, "feats": ["dodge"],
              "attacks": [{"name": "bite", "bonus": 2, "damage": "1d3-1", "damageType": "piercing"}]}]
            """;

    private static Map<String, List<Map<String, Object>>> sources(String... kindAndJson) {
        Map<String, List<Map<String, Object>>> m = new java.util.HashMap<>();
        for (int i = 0; i < kindAndJson.length; i += 2) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Object o : Json.asArray(Json.parse(kindAndJson[i + 1]), kindAndJson[i])) {
                rows.add(Json.asObject(o, kindAndJson[i]));
            }
            m.put(kindAndJson[i], rows);
        }
        return m;
    }

    @Test
    void buildsTablesWithReferencesNestingAndOperations() throws Exception {
        RulesData d =
                RulesImporter.build(
                        sources(
                                "conditions",
                                CONDITIONS,
                                "feats",
                                FEATS,
                                "classes",
                                CLASSES,
                                "spells",
                                SPELLS,
                                "monsters",
                                MONSTERS));
        RulesSchema.Kind feats = RulesSchema.FEATS;
        assertEquals(2, d.rows(feats));
        int mobility = d.find(feats, "mobility");
        assertEquals(1, mobility);
        assertEquals(1, d.count(feats, mobility, feats.require("requires")));
        assertEquals(
                d.find(feats, "dodge"),
                d.pool(d.start(feats, mobility, feats.require("requires"))));
        assertEquals(1, d.getInt(feats, mobility, feats.require("flags")), "reflexes is bit 0");
        assertEquals(13, d.getInt(feats, 0, feats.require("minDex")));
        // Operations: dodge's single modify.
        int ops = feats.require("effects");
        assertEquals(1, d.count(feats, 0, ops));
        int op = d.start(feats, 0, ops);
        assertEquals(RulesSchema.indexOf(RulesSchema.OPS, "modify"), d.op(op, RulesSchema.OP_OP));
        assertEquals(RulesSchema.indexOf(RulesSchema.STATS, "ac"), d.op(op, RulesSchema.OP_STAT));
        assertEquals(
                RulesSchema.indexOf(RulesSchema.BONUS_TYPES, "dodge"),
                d.op(op, RulesSchema.OP_TYPE));
        assertEquals(1, d.op(op, RulesSchema.OP_VALUE));
        // A spell's condition op references the conditions kind.
        RulesSchema.Kind spells = RulesSchema.SPELLS;
        int fearOp = d.start(spells, 0, spells.require("effects"));
        assertEquals(
                RulesSchema.CONDITIONS.index() << 24 | d.find(RulesSchema.CONDITIONS, "shaken"),
                d.op(fearOp, RulesSchema.OP_REF));
        assertEquals(3, d.op(fearOp, RulesSchema.OP_ROUNDS));
        // Nested rows: the class's slots and features are contiguous rows of their own kinds.
        RulesSchema.Kind classes = RulesSchema.CLASSES;
        int slots = classes.require("slots");
        assertEquals(2, d.count(classes, 0, slots));
        int slotRow = d.start(classes, 0, slots) + 1;
        RulesSchema.Kind classSlots = RulesSchema.CLASS_SLOTS;
        assertEquals(2, d.getInt(classSlots, slotRow, classSlots.require("level")));
        int list = classSlots.require("slots");
        assertEquals(2, d.count(classSlots, slotRow, list));
        assertEquals(4, d.pool(d.start(classSlots, slotRow, list)));
        assertEquals(
                "Bond",
                d.getString(
                        RulesSchema.CLASS_FEATURES, 0, RulesSchema.CLASS_FEATURES.require("name")));
        // Defaults fill what the JSON left out.
        assertEquals(
                RulesSchema.indexOf(RulesSchema.SAVE, "poor"),
                d.getInt(classes, 0, classes.require("fortitude")));
        RulesSchema.Kind monsters = RulesSchema.MONSTERS;
        assertEquals(-1, d.getInt(monsters, 0, monsters.require("ancestry")));
        RulesSchema.Kind attacks = RulesSchema.MONSTER_ATTACKS;
        assertEquals(Dice.parse("1d3-1"), d.getInt(attacks, 0, attacks.require("damage")));
        assertEquals(20, d.getInt(attacks, 0, attacks.require("threat")));
        // The file round-trips.
        byte[] bytes = d.toBytes();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment m = arena.allocate(bytes.length);
            MemorySegment.copy(
                    bytes, 0, m, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, bytes.length);
            RulesData back = RulesData.read(m);
            assertArrayEquals(bytes, back.toBytes());
            assertEquals(d.find(feats, "mobility"), back.find(feats, "mobility"));
            assertEquals("Shaken", back.getString(RulesSchema.CONDITIONS, 0, 1));
        }
    }

    @Test
    void validationNamesTheProblem() {
        assertMessage(
                "unknown field 'colour'",
                "feats",
                "[{\"id\": \"x\", \"name\": \"X\", \"colour\": 1}]");
        assertMessage(
                "no feats entry with id 'nope'",
                "feats",
                "[{\"id\": \"x\", \"name\": \"X\", \"requires\": [\"nope\"]}]");
        assertMessage(
                "is not one of", "classes", "[{\"id\": \"x\", \"name\": \"X\", \"bab\": \"all\"}]");
        assertMessage(
                "modify needs a stat",
                "feats",
                "[{\"id\": \"x\", \"name\": \"X\", \"effects\": [{\"op\": \"modify\", \"value\":"
                        + " 1}]}]");
        assertMessage(
                "condition needs a ref",
                "feats",
                "[{\"id\": \"x\", \"name\": \"X\", \"effects\": [{\"op\": \"condition\"}]}]");
        assertMessage(
                "damage needs dice",
                "spells",
                "[{\"id\": \"x\", \"name\": \"X\", \"effects\": [{\"op\": \"damage\"}]}]");
        assertMessage("dice", "items", "[{\"id\": \"x\", \"name\": \"X\", \"damage\": \"1dx\"}]");
        assertMessage(
                "two entries with id",
                "feats",
                "[{\"id\": \"x\", \"name\": \"X\"}, {\"id\": \"x\", \"name\": \"Y\"}]");
        assertMessage("has no id", "feats", "[{\"name\": \"X\"}]");
        assertMessage(
                "expected an integer",
                "feats",
                "[{\"id\": \"x\", \"name\": \"X\", \"minBab\": 1.5}]");
    }

    private static void assertMessage(String expected, String kind, String json) {
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> RulesImporter.build(sources(kind, json)));
        assertTrue(
                e.getMessage().contains(expected), e.getMessage() + " should mention " + expected);
    }

    @Test
    void cooksAManifestWithItsKindFilesAsDependencies() throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/rules"));
        Files.writeString(src.resolve("core.rules.json"), "{\"name\": \"core\"}");
        Files.writeString(src.resolve("conditions.json"), CONDITIONS);
        Files.writeString(src.resolve("feats.json"), FEATS);
        try (Cooker c = new Cooker(CookerConfig.of(dir.resolve("src"), dir.resolve("cooked")))) {
            CookReport r = c.cookAll();
            assertEquals(
                    CookReport.Status.COOKED, r.item("rules/core.rules.json").status(), r.toJson());
            assertTrue(r.item("rules/core.rules.json").outputs().contains("rules/core.jrules"));
            assertEquals(null, r.item("rules/feats.json"), "claimed: not cooked on its own");
            assertTrue(Files.exists(dir.resolve("cooked/rules/core.jrules")));
            assertTrue(!Files.exists(dir.resolve("cooked/rules/feats.json")), "not copied either");
            // Editing a kind file re-cooks the manifest.
            Files.writeString(
                    src.resolve("feats.json"), FEATS.replace("\"minDex\": 13", "\"minDex\": 15"));
            CookReport again = c.cookAll();
            assertEquals(CookReport.Status.COOKED, again.item("rules/core.rules.json").status());
        }
    }

    @Test
    void theDemoContentCooks() throws Exception {
        Path demo = Path.of("../../samples/iso-demo/assets/rules");
        Assumptions.assumeTrue(
                Files.isDirectory(demo), "the demo's rules are not next to this build");
        Map<String, List<Map<String, Object>>> sources = new java.util.HashMap<>();
        for (RulesSchema.Kind k : RulesSchema.KINDS) {
            Path f = demo.resolve(k.name() + ".json");
            if (k.hasId() && Files.isRegularFile(f)) {
                List<Map<String, Object>> rows = new ArrayList<>();
                for (Object o : Json.asArray(Json.parse(Files.readAllBytes(f)), k.name())) {
                    rows.add(Json.asObject(o, k.name()));
                }
                sources.put(k.name(), rows);
            }
        }
        RulesData d = RulesImporter.build(sources);
        assertTrue(d.rows(RulesSchema.CLASSES) >= 6);
        assertTrue(d.rows(RulesSchema.SPELLS) >= 10);
        assertTrue(d.rows(RulesSchema.CHARACTERS) == 6);
        assertTrue(d.find(RulesSchema.MONSTERS, "skeletalWarrior") >= 0);
    }
}
