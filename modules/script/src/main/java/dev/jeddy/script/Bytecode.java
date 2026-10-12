package dev.jeddy.script;

/**
 * The instruction set of the script interpreter (DESIGN §24, D121): a stack machine over {@code
 * int}s where {@code true} is 1 and {@code false} is 0. An instruction is one {@code int}: the
 * opcode in the low 8 bits and a signed 24-bit operand above it ({@link #word}, {@link #op}, {@link
 * #operand}); {@link #CALL} packs the function in bits 8–15 and the argument count in bits 16–23. A
 * literal that does not fit 24 bits is {@link #PUSH_WIDE} followed by the raw word.
 *
 * <p>The functions are what a script can read or do ({@link #STAT} ... {@link #DO}); the compiler
 * knows their names and arities ({@link #FUNCTION_NAMES}, {@link #FUNCTION_ARITY}, -1 for any), the
 * interpreter routes the first eight to reads and the rest to actions. Abilities are indices 0–5 in
 * the order Strength, Dexterity, Constitution, Intelligence, Wisdom, Charisma, the order {@code
 * rules.Stat} uses, so a host can pass them through.
 *
 * <p>Thread ownership: constants; any thread.
 */
public final class Bytecode {
    private Bytecode() {}

    // ---- Opcodes --------------------------------------------------------------------------------

    /** Push the operand. */
    public static final int PUSH = 1;

    /** Push the next word as is. */
    public static final int PUSH_WIDE = 2;

    /** Push a string's index (the operand); the callee resolves it to a {@code StrId}. */
    public static final int PUSH_STR = 3;

    /**
     * Call function (bits 8–15) with argc (bits 16–23) arguments from the stack; push the result.
     */
    public static final int CALL = 4;

    public static final int NEG = 5;
    public static final int NOT = 6;
    public static final int ADD = 7;
    public static final int SUB = 8;
    public static final int MUL = 9;
    public static final int DIV = 10;
    public static final int MOD = 11;
    public static final int EQ = 12;
    public static final int NE = 13;
    public static final int LT = 14;
    public static final int LE = 15;
    public static final int GT = 16;
    public static final int GE = 17;

    /** If the top is false, jump to the operand (an index in the program), keeping the top. */
    public static final int JUMP_IF_FALSE_KEEP = 18;

    /** If the top is true, jump to the operand, keeping the top. */
    public static final int JUMP_IF_TRUE_KEEP = 19;

    /** Drop the top. */
    public static final int POP = 20;

    /** End the program; the top (if any) is the result. */
    public static final int RETURN = 21;

    // ---- Functions ------------------------------------------------------------------------------

    /** {@code stat(ability)}: the leader's ability score. */
    public static final int STAT = 0;

    /** {@code level()}: the leader's level. */
    public static final int LEVEL = 1;

    /** {@code quest(id)}: the quest's stage (0 before it starts). */
    public static final int QUEST = 2;

    /** {@code flag(id)}: a flag's value (0 unset). */
    public static final int FLAG = 3;

    /** {@code item(id)}: how many the party holds. */
    public static final int ITEM = 4;

    /** {@code gold()}. */
    public static final int GOLD = 5;

    /** {@code random(n)}: 0 to n − 1. */
    public static final int RANDOM = 6;

    /** {@code check(ability, dc)}: an ability check rolled by the host; 1 on success. */
    public static final int CHECK = 7;

    /** {@code set quest id stage}. */
    public static final int SET_QUEST = 8;

    /** {@code set flag id value}. */
    public static final int SET_FLAG = 9;

    /** {@code give item n}. */
    public static final int GIVE = 10;

    /** {@code take item n}. */
    public static final int TAKE = 11;

    /** {@code gold +n} / {@code gold -n}. */
    public static final int ADD_GOLD = 12;

    /** {@code do name(args...)}: the game's own hook, the name first on the stack. */
    public static final int DO = 13;

    public static final int FUNCTION_COUNT = 14;

    /** The first function that is an action (no result; run for its effect). */
    public static final int FIRST_ACTION = SET_QUEST;

    /** The names as they appear in a script. */
    public static final String[] FUNCTION_NAMES = {
        "stat",
        "level",
        "quest",
        "flag",
        "item",
        "gold",
        "random",
        "check",
        "setQuest",
        "setFlag",
        "give",
        "take",
        "addGold",
        "do"
    };

    /** The argument count of each function; -1 for {@link #DO} (any). */
    public static final int[] FUNCTION_ARITY = {1, 0, 1, 1, 1, 0, 1, 2, 2, 2, 2, 2, 1, -1};

    /** The ability names a script uses, in index order. */
    public static final String[] ABILITY_NAMES = {"str", "dex", "con", "int", "wis", "cha"};

    /** The index of ability {@code name}, or -1. */
    public static int ability(String name) {
        for (int i = 0; i < ABILITY_NAMES.length; i++) {
            if (ABILITY_NAMES[i].equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /** The index of function {@code name}, or -1. */
    public static int function(String name) {
        for (int i = 0; i < FUNCTION_NAMES.length; i++) {
            if (FUNCTION_NAMES[i].equals(name)) {
                return i;
            }
        }
        return -1;
    }

    // ---- Encoding -------------------------------------------------------------------------------

    /** The largest operand {@link #PUSH} holds. */
    public static final int MAX_OPERAND = (1 << 23) - 1;

    public static final int MIN_OPERAND = -(1 << 23);

    /** An instruction with a signed 24-bit operand. */
    public static int word(int op, int operand) {
        if (operand < MIN_OPERAND || operand > MAX_OPERAND) {
            throw new IllegalArgumentException("operand " + operand + " does not fit 24 bits");
        }
        return op & 0xFF | operand << 8;
    }

    /** A {@link #CALL} of {@code function} with {@code argc} arguments. */
    public static int call(int function, int argc) {
        return CALL | (function & 0xFF) << 8 | (argc & 0xFF) << 16;
    }

    public static int op(int word) {
        return word & 0xFF;
    }

    /** The signed 24-bit operand. */
    public static int operand(int word) {
        return word >> 8;
    }

    public static int callFunction(int word) {
        return word >>> 8 & 0xFF;
    }

    public static int callArgc(int word) {
        return word >>> 16 & 0xFF;
    }

    /** A readable form of one instruction, for tests and the editor's preview. */
    public static String toString(int word) {
        int op = op(word);
        return switch (op) {
            case PUSH -> "PUSH " + operand(word);
            case PUSH_WIDE -> "PUSH_WIDE";
            case PUSH_STR -> "PUSH_STR #" + operand(word);
            case CALL -> "CALL " + FUNCTION_NAMES[callFunction(word)] + "/" + callArgc(word);
            case NEG -> "NEG";
            case NOT -> "NOT";
            case ADD -> "ADD";
            case SUB -> "SUB";
            case MUL -> "MUL";
            case DIV -> "DIV";
            case MOD -> "MOD";
            case EQ -> "EQ";
            case NE -> "NE";
            case LT -> "LT";
            case LE -> "LE";
            case GT -> "GT";
            case GE -> "GE";
            case JUMP_IF_FALSE_KEEP -> "JFK " + operand(word);
            case JUMP_IF_TRUE_KEEP -> "JTK " + operand(word);
            case POP -> "POP";
            case RETURN -> "RETURN";
            default -> "?" + op;
        };
    }
}
