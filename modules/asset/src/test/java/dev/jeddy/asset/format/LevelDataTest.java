package dev.jeddy.asset.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;

/** The {@code .jlevel} format and its mutable store (TASKS M14-02). */
class LevelDataTest {
    private static LevelData sample() {
        LevelData l = new LevelData();
        int wall = l.prefab("levels/dungeon.jscene", "wall");
        int floor = l.prefab("levels/dungeon.jscene", "floor_tile_large");
        assertEquals(wall, l.prefab("levels/dungeon.jscene", "wall")); // deduplicated
        l.addPlacement(
                floor,
                "floor 1",
                "floor",
                LevelData.PLACEMENT_STATIC,
                2,
                0,
                2,
                0,
                0,
                0,
                1,
                1,
                1,
                1);
        l.addPlacement(
                wall,
                "wall 1",
                "wall",
                LevelData.PLACEMENT_STATIC | LevelData.PLACEMENT_FADE_OCCLUDER,
                2,
                0,
                0,
                0,
                0.7071f,
                0,
                0.7071f,
                1,
                1,
                1);
        int chest = l.addPlacement(wall, "chest", "prop", 0, 1, 0, 1, 0, 0, 0, 1, 0.5f, 0.5f, 0.5f);
        l.setPlacementCollision(chest, 0x12);
        l.addPointLight("torch", 2, 2.8f, 0.9f, 1, 0.55f, 0.25f, 8, 9, LevelData.LIGHT_FLICKER);
        l.addSpawn("party start", "party", 2, 0, 2, 1.57f);
        l.addSpawn("skeleton 1", "enemy", 10, 0, 6, 0);
        l.addTrigger(
                "entrance",
                "area",
                "on_enter:entrance",
                LevelData.TRIGGER_ENABLED,
                4,
                1,
                4,
                2,
                2,
                2,
                0.5f);
        l.addArea(5, -1, 1, new float[] {12, 40, 20, 40, 20, 48, 12, 48});
        l.addLink(3, 0, 18, 7, 0, 18, 0.5f, LevelData.LINK_BIDIRECTIONAL, 63, 4);
        l.addRule("floor*", 1, 1, 0);
        l.addRule("wall", 2 | 4 | 16 | 8, 2, 0);
        l.addRule("prop", 16 | 4, 3, 0);
        l.addSizeClass("medium", 0.4f, 1.8f, 0.4f);
        l.settings.navCellSize = 0.2f;
        l.settings.occlusionYMax = 2.5f;
        l.settings.environment = "env/crypt.ktx2";
        l.settings.sunColor[1] = 0.27f;
        l.settings.gridStep = 1;
        return l;
    }

    @Test
    void roundTripsEveryKind() throws Exception {
        LevelData a = sample();
        byte[] bytes = a.toBytes();
        assertEquals(0, bytes.length % 16);
        LevelData b = LevelData.read(MemorySegment.ofArray(bytes));
        assertEquals(2, b.prefabCount());
        assertEquals("floor_tile_large", b.prefabNode(1));
        assertEquals(3, b.placementCount());
        assertEquals("wall 1", b.placementName(1));
        assertEquals("wall", b.placementLayer(1));
        assertEquals(
                LevelData.PLACEMENT_STATIC | LevelData.PLACEMENT_FADE_OCCLUDER,
                b.placementFlags(1));
        assertEquals(0.7071f, b.placementFloat(1, 4));
        assertEquals(LevelData.COLLISION_BY_LAYER, b.placementCollision(0));
        assertEquals(0x12, b.placementCollision(2));
        assertEquals(0.5f, b.placementFloat(2, 9));
        assertEquals(1, b.lightCount());
        assertEquals(LevelData.LIGHT_POINT, b.lightType(0));
        assertEquals(LevelData.LIGHT_FLICKER, b.lightFlags(0));
        assertEquals(0.55f, b.lightFloat(0, 7));
        assertEquals(9, b.lightFloat(0, 10));
        assertEquals(2, b.spawnCount());
        assertEquals("enemy", b.spawnKind(1));
        assertEquals(1.57f, b.spawnFloat(0, 3));
        assertEquals(1, b.triggerCount());
        assertEquals("on_enter:entrance", b.triggerPayload(0));
        assertEquals(0.5f, b.triggerFloat(0, 6));
        assertEquals(1, b.areaCount());
        assertEquals(5, b.areaArea(0));
        assertTrue(Arrays.equals(new float[] {12, 40, 20, 40, 20, 48, 12, 48}, b.areaPolygon(0)));
        assertEquals(1, b.linkCount());
        assertEquals(63, b.linkArea(0));
        assertEquals(4, b.linkUserId(0));
        assertEquals(7, b.linkFloat(0, 3));
        assertEquals(3, b.ruleCount());
        assertEquals("wall", b.ruleLayer(1));
        assertEquals(30, b.ruleFlags(1));
        assertEquals(3, b.ruleSurface(2));
        assertEquals(1, b.sizeClassCount());
        assertEquals(1.8f, b.sizeClassFloat(0, 1));
        assertEquals(0.2f, b.settings.navCellSize);
        assertEquals(2.5f, b.settings.occlusionYMax);
        assertEquals("env/crypt.ktx2", b.settings.environment);
        assertEquals(0.27f, b.settings.sunColor[1]);
        assertEquals(1, b.settings.gridStep);
        assertEquals(a.nextId(), b.nextId());
        // Ids survive: the same objects have the same ids on both sides.
        for (int i = 0; i < 3; i++) {
            assertEquals(a.placementId(i), b.placementId(i));
        }
        assertEquals(a.spawnId(1), b.spawnId(1));
        assertTrue(Arrays.equals(bytes, b.toBytes()), "a second write is byte-identical");
    }

    @Test
    void idsAreUniqueAcrossKindsAndStableThroughRemoval() {
        LevelData l = sample();
        int chestId = l.placementId(2);
        int lightId = l.lightId(0);
        int spawnId = l.spawnId(1);
        int triggerId = l.triggerId(0);
        assertEquals(LevelData.KIND_PLACEMENT, l.kindOf(chestId));
        assertEquals(LevelData.KIND_LIGHT, l.kindOf(lightId));
        assertEquals(LevelData.KIND_SPAWN, l.kindOf(spawnId));
        assertEquals(LevelData.KIND_TRIGGER, l.kindOf(triggerId));
        assertEquals(LevelData.KIND_NONE, l.kindOf(999));
        int n = l.nextId();
        assertTrue(chestId < n && lightId < n && spawnId < n && triggerId < n);

        // Remove the middle placement: the chest keeps its id and moves to index 1.
        l.removePlacement(1);
        assertEquals(2, l.placementCount());
        assertEquals(1, l.placementIndex(chestId));
        assertEquals("chest", l.placementName(1));
        assertEquals(0x12, l.placementCollision(1));
        // Re-insert the wall where it was (an undo): order and ids restored.
        l.insertPlacement(
                1,
                2,
                0,
                "wall 1",
                "wall",
                LevelData.PLACEMENT_STATIC,
                LevelData.COLLISION_BY_LAYER);
        assertEquals(2, l.placementId(1));
        assertEquals(2, l.placementIndex(chestId));
        assertEquals(n, l.nextId(), "re-inserting an old id does not burn a new one");
        l.insertPlacement(0, 500, 0, "late", "prop", 0, LevelData.COLLISION_BY_LAYER);
        assertEquals(501, l.nextId(), "an id from the future moves the counter past it");
    }

    @Test
    void mutationsBumpTheVersion() {
        LevelData l = sample();
        int v = l.version();
        l.setPlacementFloat(0, 0, 3);
        assertTrue(l.version() > v);
        v = l.version();
        l.setLightName(0, "sconce");
        assertTrue(l.version() > v);
    }

    @Test
    void rejectsBadFiles() {
        byte[] bytes = sample().toBytes();
        byte[] bad = bytes.clone();
        bad[0] = 0;
        assertThrows(AssetFormatException.class, () -> LevelData.read(MemorySegment.ofArray(bad)));
        byte[] truncated = Arrays.copyOf(bytes, bytes.length - 16);
        assertThrows(
                AssetFormatException.class, () -> LevelData.read(MemorySegment.ofArray(truncated)));
        byte[] wrongVersion = bytes.clone();
        wrongVersion[4] = 9;
        assertThrows(
                AssetFormatException.class,
                () -> LevelData.read(MemorySegment.ofArray(wrongVersion)));
    }

    @Test
    void componentRowsRoundTripAndFollowTheirObjects() throws Exception {
        LevelData a = sample();
        int chest = a.placementId(2);
        int spawn = a.spawnId(0);
        int row = a.addComponentRow(chest, "Loot");
        assertEquals(row, a.addComponentRow(chest, "Loot"), "one row per object and component");
        a.setComponentInt(row, "gold", 25);
        a.setComponentFloat(row, "weight", 1.5f);
        a.setComponentBool(row, "locked", true);
        a.setComponentLong(row, "seed", 1L << 40);
        a.setComponentEnum(row, "rarity", "RARE");
        a.setComponentEntity(row, "owner", spawn);
        a.setComponentAsset(row, "sound", "sfx/chest.wav");
        a.setComponentInt(row, "gold", 30); // replaces
        int tag = a.addComponentRow(spawn, "Patrol");
        assertEquals(2, a.componentRowCount());
        assertEquals(7, a.componentValueCount(row));
        assertEquals(0, a.componentValueCount(tag));
        assertEquals(30, a.componentValueBits(row, a.componentValue(row, "gold")));
        assertEquals(-1, a.componentValue(row, "nope"));
        LevelData b = LevelData.read(MemorySegment.ofArray(a.toBytes()));
        assertEquals(2, b.componentRowCount());
        int r = b.componentRow(chest, "Loot");
        assertTrue(r >= 0);
        assertEquals(chest, b.componentRowObject(r));
        assertEquals("Loot", b.componentRowComponent(r));
        assertEquals(30, b.componentValueBits(r, b.componentValue(r, "gold")));
        assertEquals(LevelData.VALUE_INT, b.componentValueType(r, b.componentValue(r, "gold")));
        assertEquals(1.5f, b.componentValueFloat(r, b.componentValue(r, "weight")));
        assertEquals(1, b.componentValueBits(r, b.componentValue(r, "locked")));
        assertEquals(1L << 40, b.componentValueBits(r, b.componentValue(r, "seed")));
        assertEquals("RARE", b.componentValueText(r, b.componentValue(r, "rarity")));
        assertEquals(LevelData.VALUE_ENUM, b.componentValueType(r, b.componentValue(r, "rarity")));
        assertEquals(spawn, b.componentValueBits(r, b.componentValue(r, "owner")));
        assertEquals("sfx/chest.wav", b.componentValueText(r, b.componentValue(r, "sound")));
        assertTrue(b.componentRow(spawn, "Patrol") >= 0);
        assertTrue(Arrays.equals(a.toBytes(), b.toBytes()));
        assertTrue(a.toJson().contains("\"component\": \"Loot\""));
        assertTrue(a.toJson().contains("\"rarity\": \"RARE\""));
        // A copy carries the rows; a removed object takes its rows along; a duplicate copies.
        LevelData c = new LevelData();
        c.copyFrom(a);
        assertEquals(2, c.componentRowCount());
        a.removeComponentValue(row, "gold");
        assertEquals(-1, a.componentValue(row, "gold"));
        a.copyComponentRows(chest, a.placementId(0));
        assertEquals(3, a.componentRowCount());
        assertEquals(6, a.componentValueCount(a.componentRow(a.placementId(0), "Loot")));
        a.removePlacement(2);
        assertEquals(2, a.componentRowCount());
        assertEquals(-1, a.componentRow(chest, "Loot"));
        a.removeSpawn(0);
        assertEquals(1, a.componentRowCount());
        a.removeComponentRow(0);
        assertEquals(0, a.componentRowCount());
        assertEquals(0, LevelData.read(MemorySegment.ofArray(a.toBytes())).componentRowCount());
    }

    @Test
    void copiesAndDumps() {
        LevelData a = sample();
        LevelData b = new LevelData();
        b.addPlacement(b.prefab("x", ""), "old", "prop", 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1);
        b.copyFrom(a);
        assertEquals(3, b.placementCount());
        assertEquals("chest", b.placementName(2));
        assertEquals(a.nextId(), b.nextId());
        assertTrue(Arrays.equals(a.toBytes(), b.toBytes()));
        String json = a.toJson();
        assertTrue(json.contains("\"name\": \"wall 1\""));
        assertTrue(json.contains("\"payload\": \"on_enter:entrance\""));
        assertTrue(json.contains("\"gridStep\": 1.0"));
        assertFalse(json.contains("null"));
    }
}
