package dev.jeddy.rules;

/**
 * What the rules decided, as a ring of fixed records the UI drains for its combat log (DESIGN §20,
 * D99): a kind, the actor and target creatures, the natural roll, the total, the number it was
 * compared with (AC or DC), an amount (damage, healing) and a reference (a spell, condition or item
 * row). Nothing allocates; a full ring overwrites its oldest record and counts the loss.
 *
 * <p>Thread ownership: the game thread.
 */
public final class Events {
    public static final int ATTACK_HIT = 0;
    public static final int ATTACK_MISS = 1;
    public static final int CRITICAL = 2;
    public static final int DAMAGE = 3;
    public static final int HEAL = 4;
    public static final int SAVE_SUCCESS = 5;
    public static final int SAVE_FAIL = 6;
    public static final int CONDITION_ON = 7;
    public static final int CONDITION_OFF = 8;
    public static final int DYING = 9;
    public static final int DEATH = 10;
    public static final int STABLE = 11;
    public static final int TURN = 12;
    public static final int ROUND = 13;
    public static final int SPELL = 14;
    public static final int OPPORTUNITY = 15;
    public static final int TEMP_HP = 16;
    public static final int ITEM = 17;

    /** Names per kind, for logs and tests. */
    public static final String[] NAMES = {
        "hit", "miss", "critical", "damage", "heal", "save", "failed save", "condition on",
        "condition off", "dying", "death", "stable", "turn", "round", "spell", "opportunity",
        "temporary hp", "item"
    };

    private final int[] kind;
    private final int[] actor;
    private final int[] target;
    private final int[] natural;
    private final int[] total;
    private final int[] against;
    private final int[] amount;
    private final int[] ref;
    private final int capacity;
    private long written; // total ever written; the ring holds the last `capacity`
    private long dropped;

    /** Creates a ring of {@code capacity} records. */
    public Events(int capacity) {
        this.capacity = capacity;
        kind = new int[capacity];
        actor = new int[capacity];
        target = new int[capacity];
        natural = new int[capacity];
        total = new int[capacity];
        against = new int[capacity];
        amount = new int[capacity];
        ref = new int[capacity];
    }

    /** Appends a record. */
    public void add(
            int kind,
            int actor,
            int target,
            int natural,
            int total,
            int against,
            int amount,
            int ref) {
        int i = (int) (written % capacity);
        if (written >= capacity) {
            dropped++;
        }
        this.kind[i] = kind;
        this.actor[i] = actor;
        this.target[i] = target;
        this.natural[i] = natural;
        this.total[i] = total;
        this.against[i] = against;
        this.amount[i] = amount;
        this.ref[i] = ref;
        written++;
    }

    /** Returns the sequence number the next record will get (readers remember where they are). */
    public long next() {
        return written;
    }

    /** Returns the oldest sequence number still in the ring. */
    public long oldest() {
        return Math.max(0, written - capacity);
    }

    /** Returns how many records were overwritten before being read by anyone. */
    public long dropped() {
        return dropped;
    }

    private int slot(long seq) {
        if (seq < oldest() || seq >= written) {
            throw new IndexOutOfBoundsException("event " + seq);
        }
        return (int) (seq % capacity);
    }

    public int kind(long seq) {
        return kind[slot(seq)];
    }

    public int actor(long seq) {
        return actor[slot(seq)];
    }

    public int target(long seq) {
        return target[slot(seq)];
    }

    public int natural(long seq) {
        return natural[slot(seq)];
    }

    public int total(long seq) {
        return total[slot(seq)];
    }

    public int against(long seq) {
        return against[slot(seq)];
    }

    public int amount(long seq) {
        return amount[slot(seq)];
    }

    public int ref(long seq) {
        return ref[slot(seq)];
    }
}
