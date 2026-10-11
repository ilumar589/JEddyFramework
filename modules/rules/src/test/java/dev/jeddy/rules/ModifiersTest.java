package dev.jeddy.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.core.collections.IntList;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.Test;

/** The stacking rule and the store (TASKS M15-02). */
class ModifiersTest {
    @Test
    void sameTypeTakesTheLargestAndStackingTypesAdd() {
        Modifiers m = new Modifiers();
        IntList h = new IntList(8);
        int[] scratch = new int[BonusType.COUNT];
        h.add(m.add(0, Stat.AC, BonusType.ARMOR, 4, 1, Modifiers.PERMANENT));
        h.add(m.add(0, Stat.AC, BonusType.ARMOR, 6, 2, Modifiers.PERMANENT)); // mage armor + plate
        h.add(m.add(0, Stat.AC, BonusType.DODGE, 1, 3, Modifiers.PERMANENT));
        h.add(m.add(0, Stat.AC, BonusType.DODGE, 1, 4, Modifiers.PERMANENT)); // dodge stacks
        h.add(m.add(0, Stat.AC, BonusType.UNTYPED, 1, 5, Modifiers.PERMANENT));
        h.add(m.add(0, Stat.AC, BonusType.PENALTY, -2, 6, Modifiers.PERMANENT));
        h.add(m.add(0, Stat.AC, BonusType.PENALTY, -1, 7, Modifiers.PERMANENT)); // penalties stack
        h.add(m.add(0, Stat.ATTACK, BonusType.UNTYPED, 5, 8, Modifiers.PERMANENT)); // other stat
        assertEquals(6 + 2 + 1 - 3, m.total(h, Stat.AC, scratch));
        assertEquals(5, m.total(h, Stat.ATTACK, scratch));
        assertEquals(6, m.totalOfType(h, Stat.AC, BonusType.ARMOR));
        assertEquals(2, m.totalOfType(h, Stat.AC, BonusType.DODGE));
        assertEquals(8, m.liveCount());
    }

    @Test
    void removingBySourceCompactsTheHandleList() {
        Modifiers m = new Modifiers();
        IntList h = new IntList(8);
        int[] scratch = new int[BonusType.COUNT];
        int src = Modifiers.source(Modifiers.SOURCE_FEAT, 7);
        h.add(m.add(0, Stat.AC, BonusType.DODGE, 1, src, Modifiers.PERMANENT));
        h.add(m.add(0, Stat.REFLEX, BonusType.UNTYPED, 2, src, Modifiers.PERMANENT));
        h.add(m.add(0, Stat.AC, BonusType.ARMOR, 4, 99, Modifiers.PERMANENT));
        m.removeBySource(h, src);
        assertEquals(1, h.size());
        assertEquals(4, m.total(h, Stat.AC, scratch));
        assertEquals(0, m.total(h, Stat.REFLEX, scratch));
        assertEquals(1, m.liveCount());
        assertEquals(Modifiers.SOURCE_FEAT, Modifiers.sourceKind(src));
        assertEquals(7, Modifiers.sourceId(src));
    }

    @Test
    void durationsTickAndExpire() {
        Modifiers m = new Modifiers();
        IntList h = new IntList(8);
        int[] scratch = new int[BonusType.COUNT];
        h.add(m.add(0, Stat.ATTACK, BonusType.MORALE, 1, 1, 2));
        h.add(m.add(0, Stat.ATTACK, BonusType.UNTYPED, 1, 2, Modifiers.PERMANENT));
        assertEquals(0, m.tick(h));
        assertEquals(2, m.total(h, Stat.ATTACK, scratch));
        assertEquals(1, m.tick(h));
        assertEquals(1, h.size());
        assertEquals(1, m.total(h, Stat.ATTACK, scratch));
        assertTrue(m.isAlive(h.get(0)));
        assertFalse(m.isAlive(0));
        // Freed handles are reused.
        assertEquals(0, m.add(1, Stat.AC, BonusType.ARMOR, 1, 1, Modifiers.PERMANENT));
    }

    @Test
    void growsPastTheInitialCapacity() {
        Modifiers m = new Modifiers();
        IntList h = new IntList(8);
        int[] scratch = new int[BonusType.COUNT];
        for (int i = 0; i < 200; i++) {
            h.add(m.add(0, Stat.MAX_HP, BonusType.UNTYPED, 1, i, Modifiers.PERMANENT));
        }
        assertEquals(200, m.total(h, Stat.MAX_HP, scratch));
    }

    @Test
    void totalAllocatesNothing() {
        Modifiers m = new Modifiers();
        IntList h = new IntList(8);
        int[] scratch = new int[BonusType.COUNT];
        for (int t = 0; t < BonusType.COUNT; t++) {
            h.add(m.add(0, Stat.AC, t, t % 3, t, Modifiers.PERMANENT));
        }
        int[] sink = new int[1];
        Allocations.assertNoAllocation(
                "Modifiers.total", () -> sink[0] += m.total(h, Stat.AC, scratch));
    }
}
