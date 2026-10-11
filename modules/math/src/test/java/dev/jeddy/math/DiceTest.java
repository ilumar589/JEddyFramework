package dev.jeddy.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Packed dice expressions (TASKS M15-02). */
class DiceTest {
    @Test
    void packsParsesAndPrints() {
        int d = Dice.parse("2d6+3");
        assertEquals(2, Dice.count(d));
        assertEquals(6, Dice.sides(d));
        assertEquals(3, Dice.bonus(d));
        assertEquals("2d6+3", Dice.toString(d));
        assertEquals("1d8", Dice.toString(Dice.parse("d8")));
        assertEquals("1d4-1", Dice.toString(Dice.parse(" 1d4-1 ")));
        assertEquals("5", Dice.toString(Dice.parse("5")));
        assertEquals(0, Dice.parse(""));
        assertEquals("", Dice.toString(0));
        assertEquals(-2, Dice.bonus(Dice.pack(0, 0, -2)));
        assertEquals("3d6+9", Dice.toString(Dice.times(Dice.parse("1d6+3"), 3)));
        assertEquals("1d6+5", Dice.toString(Dice.plus(Dice.parse("1d6+3"), 2)));
        assertEquals(10, Dice.average(Dice.parse("2d6+3")), 1e-6);
        assertEquals(5, Dice.min(Dice.parse("2d6+3")));
        assertEquals(15, Dice.max(Dice.parse("2d6+3")));
        assertThrows(IllegalArgumentException.class, () -> Dice.parse("1dx"));
        assertThrows(IllegalArgumentException.class, () -> Dice.parse("2d"));
        assertThrows(IllegalArgumentException.class, () -> Dice.parse("1d6+"));
        assertThrows(IllegalArgumentException.class, () -> Dice.parse("abc"));
        assertThrows(IllegalArgumentException.class, () -> Dice.pack(300, 6, 0));
    }

    @Test
    void rollsStayInRangeAndAreDeterministic() {
        Rng a = new Rng(42);
        Rng b = new Rng(42);
        int d = Dice.parse("3d6-2");
        for (int i = 0; i < 1000; i++) {
            int r = Dice.roll(d, a);
            assertEquals(r, Dice.roll(d, b));
            assertTrue(r >= 1 && r <= 16, "3d6-2 in [1, 16]: " + r);
        }
        assertEquals(7, Dice.roll(Dice.parse("7"), a));
        assertEquals(0, Dice.roll(0, a));
    }
}
