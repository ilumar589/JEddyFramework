package dev.jeddy.asset.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/** The cooked rules container (TASKS M15-03): the schema's offsets, the builder, the file. */
class RulesDataTest {
    @Test
    void schemaOffsetsFollowFieldWidths() {
        RulesSchema.Kind feats = RulesSchema.FEATS;
        assertEquals(0, feats.offset(feats.require("id")));
        assertEquals(2, feats.offset(feats.require("name")), "a string is two ints");
        assertEquals(6, feats.offset(feats.require("minBab")));
        assertEquals(feats.offset(feats.require("effects")) + 2, feats.stride());
        for (RulesSchema.Kind k : RulesSchema.KINDS) {
            if (!k.hasId()) {
                assertEquals(-1, k.field("id"), k.name() + " is nested");
            }
        }
        assertEquals("classes", RulesSchema.CLASS_SLOTS.parent());
        assertEquals("slots", RulesSchema.CLASS_SLOTS.parentField());
    }

    @Test
    void buildWriteReadRoundTripsAndValidates() throws Exception {
        RulesData.Builder b = new RulesData.Builder();
        RulesSchema.Kind feats = RulesSchema.FEATS;
        int dodge = b.addRow(feats);
        b.setString(feats, dodge, feats.require("id"), "dodge");
        b.setString(feats, dodge, feats.require("name"), "Dodge");
        b.setInt(feats, dodge, feats.require("minDex"), 13);
        int op = b.addOp(0, 6, 5, 1, 0, 0, -1, 10);
        b.setOps(feats, dodge, feats.require("effects"), op, 1);
        int mobility = b.addRow(feats);
        b.setString(feats, mobility, feats.require("id"), "mobility");
        b.setList(feats, mobility, feats.require("requires"), new int[] {dodge}, 1);
        RulesData d = b.build();
        assertEquals(2, d.rows(feats));
        assertEquals(0, d.rows(RulesSchema.SPELLS));
        assertEquals(13, d.getInt(feats, dodge, feats.require("minDex")));
        assertEquals(0, d.getInt(feats, mobility, feats.require("minDex")), "the default");
        assertEquals(1, d.find(feats, "mobility"));
        assertEquals(-1, d.find(feats, "nope"));
        assertEquals(5, d.op(op, RulesSchema.OP_TYPE));
        byte[] bytes = d.toBytes();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment m = arena.allocate(bytes.length);
            MemorySegment.copy(bytes, 0, m, ValueLayout.JAVA_BYTE, 0, bytes.length);
            RulesData back = RulesData.read(m);
            assertArrayEquals(bytes, back.toBytes());
            assertEquals("Dodge", back.getString(feats, 0, feats.require("name")));
            // Corrupt a reference: the list's row index past the table.
            MemorySegment bad = arena.allocate(bytes.length);
            MemorySegment.copy(m, 0, bad, 0, bytes.length);
            long poolAt =
                    bad.get(
                            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(
                                    java.nio.ByteOrder.LITTLE_ENDIAN),
                            40);
            bad.set(
                    ValueLayout.JAVA_INT_UNALIGNED.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN),
                    poolAt,
                    99);
            assertThrows(AssetFormatException.class, () -> RulesData.read(bad));
            MemorySegment wrong = arena.allocate(bytes.length);
            MemorySegment.copy(m, 0, wrong, 0, bytes.length);
            wrong.set(
                    ValueLayout.JAVA_INT_UNALIGNED.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN),
                    4,
                    7);
            assertThrows(AssetFormatException.class, () -> RulesData.read(wrong));
        }
    }
}
