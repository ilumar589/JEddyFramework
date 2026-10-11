package dev.jeddy.rules;

import dev.jeddy.asset.format.RulesSchema;

/**
 * The statistics a modifier can target, as int constants in the order of {@link RulesSchema#STATS}
 * (the content's enum and the engine's arrays agree by construction: a static check fails loudly if
 * the two drift apart).
 *
 * <p>Thread ownership: constants.
 */
public final class Stat {
    private Stat() {}

    public static final int STR = 0;
    public static final int DEX = 1;
    public static final int CON = 2;
    public static final int INT = 3;
    public static final int WIS = 4;
    public static final int CHA = 5;
    public static final int AC = 6;
    public static final int ATTACK = 7;
    public static final int DAMAGE = 8;
    public static final int FORTITUDE = 9;
    public static final int REFLEX = 10;
    public static final int WILL = 11;
    public static final int INITIATIVE = 12;
    public static final int SPEED = 13;
    public static final int MAX_HP = 14;
    public static final int AOO = 15;
    public static final int EXTRA_ATTACKS = 16;

    /** Number of statistics. */
    public static final int COUNT = 17;

    static {
        if (RulesSchema.STATS.length != COUNT
                || !RulesSchema.STATS[AC].equals("ac")
                || !RulesSchema.STATS[AOO].equals("aoo")
                || !RulesSchema.STATS[EXTRA_ATTACKS].equals("extraAttacks")) {
            throw new ExceptionInInitializerError("Stat constants disagree with RulesSchema.STATS");
        }
    }

    /** Returns the ability modifier of a score: {@code floor((score - 10) / 2)}. */
    public static int modifier(int score) {
        return Math.floorDiv(score - 10, 2);
    }

    /** Returns whether {@code stat} is one of the six abilities. */
    public static boolean isAbility(int stat) {
        return stat >= STR && stat <= CHA;
    }

    /** Returns the statistic's name. */
    public static String name(int stat) {
        return RulesSchema.STATS[stat];
    }
}
