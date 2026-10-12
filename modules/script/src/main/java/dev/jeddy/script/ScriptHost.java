package dev.jeddy.script;

/**
 * What a script can read from and do to the game (DESIGN §24, D122): the one interface through
 * which {@code script} reaches the rules, the inventory and the game's own hooks. Names (items,
 * hooks) arrive as the {@code StrId} of the word in the script, so a host resolves them against its
 * own tables with a {@code long} compare; abilities are indices 0–5 in the order Strength,
 * Dexterity, Constitution, Intelligence, Wisdom, Charisma ({@link Bytecode#ABILITY_NAMES}). Quests
 * and flags are not here: they live in {@link Quests} and {@link Flags}.
 *
 * <p>Thread ownership: the game thread, from the interpreter, while a conversation runs.
 */
public interface ScriptHost {
    /** {@code stat(ability)}: the speaking party member's ability score. */
    int stat(int ability);

    /** {@code level()}: the speaking party member's level. */
    int level();

    /** {@code check(ability, dc)}: rolls an ability check and says whether it passed. */
    boolean check(int ability, int dc);

    /** {@code item(id)}: how many of the item the party holds. */
    int item(long id);

    /** {@code give id n}. */
    void give(long id, int count);

    /** {@code take id n}. */
    void take(long id, int count);

    /** {@code gold()}. */
    int gold();

    /** {@code gold +n} / {@code gold -n}. */
    void addGold(int delta);

    /** {@code random(n)}: a number in {@code [0, n)}. */
    int random(int n);

    /**
     * {@code do name(args)}: the game's own hook, with the arguments in {@code args[offset, offset
     * + count)} (names among them as {@code StrId}s are already resolved to {@code int} string
     * indices of the dialogue; use {@link Conversation#stringId} to read them).
     */
    void call(long name, int[] args, int offset, int count);

    /** The name shown for a speaker id, or {@code null} to show the id itself. */
    default String speakerName(long id) {
        return null;
    }
}
