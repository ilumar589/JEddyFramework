package dev.jeddy.rules;

import dev.jeddy.asset.format.RulesSchema;

/**
 * Bonus types in the order of {@link RulesSchema#BONUS_TYPES}, and the stacking rule (D96): two
 * bonuses of the same type do not stack (the largest applies) except untyped, dodge and
 * circumstance bonuses, which always stack; penalties always stack.
 *
 * <p>Thread ownership: constants.
 */
public final class BonusType {
    private BonusType() {}

    public static final int UNTYPED = 0;
    public static final int ARMOR = 1;
    public static final int SHIELD = 2;
    public static final int NATURAL = 3;
    public static final int DEFLECTION = 4;
    public static final int DODGE = 5;
    public static final int ENHANCEMENT = 6;
    public static final int MORALE = 7;
    public static final int COMPETENCE = 8;
    public static final int LUCK = 9;
    public static final int SIZE = 10;
    public static final int INSIGHT = 11;
    public static final int SACRED = 12;
    public static final int PROFANE = 13;
    public static final int CIRCUMSTANCE = 14;
    public static final int RACIAL = 15;
    public static final int ALCHEMICAL = 16;
    public static final int PENALTY = 17;

    /** Number of types. */
    public static final int COUNT = 18;

    static {
        if (RulesSchema.BONUS_TYPES.length != COUNT
                || !RulesSchema.BONUS_TYPES[PENALTY].equals("penalty")
                || !RulesSchema.BONUS_TYPES[DODGE].equals("dodge")) {
            throw new ExceptionInInitializerError("BonusType constants disagree with RulesSchema");
        }
    }

    /** Returns whether bonuses of {@code type} add up rather than take the largest. */
    public static boolean stacks(int type) {
        return type == UNTYPED || type == DODGE || type == CIRCUMSTANCE || type == PENALTY;
    }

    /** Returns the type's name. */
    public static String name(int type) {
        return RulesSchema.BONUS_TYPES[type];
    }
}
