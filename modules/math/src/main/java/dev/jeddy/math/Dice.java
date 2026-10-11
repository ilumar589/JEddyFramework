package dev.jeddy.math;

/**
 * Dice expressions {@code NdS+B} packed into one {@code int} (DESIGN §20): the count in bits 24–31,
 * the sides in bits 16–23 and the signed bonus in bits 0–15, so content tables store a damage
 * expression as a plain int and rolling it allocates nothing. {@code 0} is "no dice" (rolls 0); a
 * flat number is {@code 0d0+N}.
 *
 * <p>Thread ownership: stateless; the {@link Rng} passed in belongs to the caller.
 */
public final class Dice {
    private Dice() {}

    /**
     * Packs {@code count}d{@code sides}+{@code bonus} ({@code count}, {@code sides} 0..255, bonus
     * −32768..32767).
     */
    public static int pack(int count, int sides, int bonus) {
        if (count < 0 || count > 255 || sides < 0 || sides > 255) {
            throw new IllegalArgumentException("dice " + count + "d" + sides);
        }
        if (bonus < Short.MIN_VALUE || bonus > Short.MAX_VALUE) {
            throw new IllegalArgumentException("dice bonus " + bonus);
        }
        return count << 24 | sides << 16 | (bonus & 0xFFFF);
    }

    /** Returns the number of dice. */
    public static int count(int dice) {
        return dice >>> 24;
    }

    /** Returns the sides per die. */
    public static int sides(int dice) {
        return dice >>> 16 & 0xFF;
    }

    /** Returns the flat bonus (may be negative). */
    public static int bonus(int dice) {
        return (short) (dice & 0xFFFF);
    }

    /** Returns {@code dice} with {@code delta} added to its bonus. */
    public static int plus(int dice, int delta) {
        return pack(count(dice), sides(dice), bonus(dice) + delta);
    }

    /** Returns {@code dice} with its count multiplied (a critical hit multiplies the dice). */
    public static int times(int dice, int factor) {
        return pack(Math.min(255, count(dice) * factor), sides(dice), bonus(dice) * factor);
    }

    /** Rolls the dice: the sum of {@code count} rolls of {@code sides} plus the bonus. */
    public static int roll(int dice, Rng rng) {
        int n = count(dice);
        int s = sides(dice);
        int total = bonus(dice);
        if (s > 0) {
            for (int i = 0; i < n; i++) {
                total += rng.nextInt(s) + 1;
            }
        }
        return total;
    }

    /** Returns the expected value, {@code count * (sides + 1) / 2 + bonus}. */
    public static float average(int dice) {
        return count(dice) * (sides(dice) + 1) * 0.5f + bonus(dice);
    }

    /** Returns the smallest possible roll. */
    public static int min(int dice) {
        return (sides(dice) > 0 ? count(dice) : 0) + bonus(dice);
    }

    /** Returns the largest possible roll. */
    public static int max(int dice) {
        return count(dice) * sides(dice) + bonus(dice);
    }

    /**
     * Parses {@code "2d6+3"}, {@code "d8"}, {@code "1d4-1"}, {@code "5"} or {@code ""} (no dice).
     *
     * @throws IllegalArgumentException when the text is not a dice expression
     */
    public static int parse(CharSequence text) {
        int n = text.length();
        int i = 0;
        while (i < n && text.charAt(i) == ' ') {
            i++;
        }
        if (i == n) {
            return 0;
        }
        int count = 0;
        int sides = 0;
        int bonus = 0;
        int digits = 0;
        int value = 0;
        while (i < n && Character.isDigit(text.charAt(i))) {
            value = value * 10 + (text.charAt(i) - '0');
            i++;
            digits++;
        }
        if (i < n && (text.charAt(i) == 'd' || text.charAt(i) == 'D')) {
            count = digits == 0 ? 1 : value;
            i++;
            value = 0;
            digits = 0;
            while (i < n && Character.isDigit(text.charAt(i))) {
                value = value * 10 + (text.charAt(i) - '0');
                i++;
                digits++;
            }
            if (digits == 0) {
                throw new IllegalArgumentException("dice without sides: " + text);
            }
            sides = value;
            if (i < n && (text.charAt(i) == '+' || text.charAt(i) == '-')) {
                boolean negative = text.charAt(i) == '-';
                i++;
                value = 0;
                digits = 0;
                while (i < n && Character.isDigit(text.charAt(i))) {
                    value = value * 10 + (text.charAt(i) - '0');
                    i++;
                    digits++;
                }
                if (digits == 0) {
                    throw new IllegalArgumentException("dice bonus without digits: " + text);
                }
                bonus = negative ? -value : value;
            }
        } else {
            if (digits == 0) {
                throw new IllegalArgumentException("not a dice expression: " + text);
            }
            bonus = value;
        }
        while (i < n && text.charAt(i) == ' ') {
            i++;
        }
        if (i != n) {
            throw new IllegalArgumentException("not a dice expression: " + text);
        }
        return pack(count, sides, bonus);
    }

    /** Appends the expression ({@code 2d6+3}, {@code 5}, or nothing for 0) to {@code out}. */
    public static void append(int dice, StringBuilder out) {
        int c = count(dice);
        int s = sides(dice);
        int b = bonus(dice);
        if (c > 0 && s > 0) {
            out.append(c).append('d').append(s);
            if (b > 0) {
                out.append('+').append(b);
            } else if (b < 0) {
                out.append(b);
            }
        } else if (dice != 0) {
            out.append(b);
        }
    }

    /** Returns the expression as a string (allocates; for logs and tests). */
    public static String toString(int dice) {
        StringBuilder sb = new StringBuilder();
        append(dice, sb);
        return sb.toString();
    }
}
