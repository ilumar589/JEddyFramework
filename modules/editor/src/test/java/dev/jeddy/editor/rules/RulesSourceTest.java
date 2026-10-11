package dev.jeddy.editor.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.format.RulesData;
import dev.jeddy.asset.format.RulesSchema;
import dev.jeddy.asset.format.RulesSchema.Kind;
import dev.jeddy.cooker.rules.RulesImporter;
import dev.jeddy.core.json.Json;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The editor's view of the content (TASKS M15-06): paths into the JSON trees, validation with the
 * importer's messages, and saving files the importer cooks to the same binary.
 */
class RulesSourceTest {
    @TempDir Path dir;

    static Path demo() {
        Path demo = Path.of("../../samples/iso-demo/assets/rules");
        Assumptions.assumeTrue(
                Files.isDirectory(demo), "the demo's rules are not next to this build");
        return demo;
    }

    static RulesData cook(Path dir) throws Exception {
        Map<String, List<Map<String, Object>>> sources = new HashMap<>();
        for (Kind k : RulesSchema.KINDS) {
            Path f = dir.resolve(k.name() + ".json");
            if (k.hasId() && Files.isRegularFile(f)) {
                List<Map<String, Object>> rows = new ArrayList<>();
                for (Object o : Json.asArray(Json.parse(Files.readAllBytes(f)), k.name())) {
                    rows.add(Json.asObject(o, k.name()));
                }
                sources.put(k.name(), rows);
            }
        }
        return RulesImporter.build(sources);
    }

    @Test
    void pathsReadAndWriteTheTrees() throws Exception {
        RulesSource s = RulesSource.load(demo());
        int dodge = s.find("feats", "dodge");
        assertTrue(dodge >= 0);
        RulesPath value = RulesPath.of("feats", dodge, "effects", 0, "value");
        assertEquals("feats[" + dodge + "].effects[0].value", value.toString());
        assertEquals(1.0, s.get(value));
        int v0 = s.version();
        assertEquals(1.0, s.set(value, 2.0));
        assertEquals(2.0, s.get(value));
        assertEquals(v0 + 1, s.version());
        assertTrue(s.isDirty());
        // Removing a key: the importer's default applies.
        RulesPath minDex = RulesPath.of("feats", dodge, "minDex");
        assertEquals(13.0, s.set(minDex, null));
        assertNull(s.get(minDex));
        assertFalse(s.row("feats", dodge).containsKey("minDex"));
        // Lists: insert, move, remove.
        RulesPath effects = RulesPath.of("feats", dodge, "effects");
        s.insert(effects, 1, RulesSource.newOperation());
        assertEquals(2, s.list(effects).size());
        s.move(effects, 1, 0);
        assertEquals("ac", ((Map<?, ?>) s.list(effects).get(0)).get("stat"));
        assertEquals(2.0, ((Map<?, ?>) s.list(effects).get(1)).get("value"));
        assertEquals(s.list(effects).get(0), s.remove(effects, 0));
        assertEquals(1, s.list(effects).size());
        // A missing list is created in its object.
        RulesPath requires = RulesPath.of("feats", dodge, "requires");
        assertNull(s.get(requires));
        s.insert(requires, 0, "mobility");
        assertEquals(List.of("mobility"), s.get(requires));
        assertEquals("dodge2", s.freeId("feats", "dodge"));
        assertEquals("fresh", s.freeId("feats", "fresh"));
    }

    @Test
    void validationReportsWhatTheImporterRejects() throws Exception {
        RulesSource s = RulesSource.load(demo());
        List<String> out = new ArrayList<>();
        assertEquals(0, s.validate(out), String.join("\n", out));
        // Deleting 'frightened', which causeFear applies, leaves a dangling reference.
        int frightened = s.find("conditions", "frightened");
        s.remove(RulesPath.of("conditions"), frightened);
        assertTrue(s.validate(out) >= 1);
        assertTrue(out.get(0).contains("no conditions with id 'frightened'"), out.toString());
        out.clear();
        int dodge = s.find("feats", "dodge");
        s.set(RulesPath.of("feats", dodge, "effects", 0, "type"), "heroic");
        s.set(RulesPath.of("feats", dodge, "minDex"), "thirteen");
        s.set(RulesPath.of("items", s.find("items", "dagger"), "damage"), "1d");
        s.set(RulesPath.of("feats", dodge, "bogus"), 1.0);
        s.insert(RulesPath.of("feats"), 0, RulesSource.newDefinition(RulesSchema.FEATS, "dodge"));
        s.validate(out);
        assertTrue(
                out.stream().anyMatch(m -> m.contains("'heroic' is not one of")), out.toString());
        assertTrue(out.stream().anyMatch(m -> m.contains("minDex: expected a number")));
        assertTrue(out.stream().anyMatch(m -> m.contains("bad dice '1d'")));
        assertTrue(out.stream().anyMatch(m -> m.contains("unknown field 'bogus'")));
        assertTrue(out.stream().anyMatch(m -> m.contains("two entries with id dodge")));
    }

    @Test
    void savedFilesCookToTheSameBinary() throws Exception {
        RulesSource s = RulesSource.load(demo());
        RulesData original = cook(demo());
        s.save(dir);
        assertFalse(s.isDirty());
        for (Kind k : RulesSchema.KINDS) {
            if (k.hasId()) {
                assertTrue(Files.isRegularFile(dir.resolve(k.name() + ".json")), k.name());
            }
        }
        RulesData again = cook(dir);
        assertEquals(original.toBytes().length, again.toBytes().length);
        assertTrue(java.util.Arrays.equals(original.toBytes(), again.toBytes()));
        // The files keep the hand-written shape: one definition per entry, effects one per line.
        String feats = Files.readString(dir.resolve("feats.json"));
        assertTrue(feats.startsWith("[\n  {\"id\": \"toughness\", \"name\": \"Toughness\""), feats);
        assertTrue(feats.contains("\"effects\": [\n     {\"op\": \"modify\""), feats);
        assertTrue(feats.contains("\"requires\": [\"dodge\"]"), feats);
        // An edit, saved and reloaded, is in the cooked content.
        int dodge = s.find("feats", "dodge");
        s.set(RulesPath.of("feats", dodge, "effects", 0, "value"), 3.0);
        s.save(null);
        RulesSource back = RulesSource.load(dir);
        assertEquals(3.0, back.get(RulesPath.of("feats", dodge, "effects", 0, "value")));
        RulesData edited = cook(dir);
        int row = edited.find(RulesSchema.FEATS, "dodge");
        int start = edited.start(RulesSchema.FEATS, row, RulesSchema.FEATS.require("effects"));
        assertEquals(3, edited.op(start, RulesSchema.OP_VALUE));
    }
}
