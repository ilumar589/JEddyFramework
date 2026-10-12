package dev.jeddy.rules;

import java.util.Arrays;

/**
 * Condition instances: which creature has which condition for how many rounds. The condition's
 * effects live in {@link Modifiers} under the instance as their source, so ending the instance
 * removes exactly them; {@link Creatures#flags} caches the union of the active flags.
 *
 * <p>Thread ownership: the game thread.
 */
public final class Conditions {
    /** Lasts until removed. */
    public static final int INDEFINITE = -1;

    private int[] creature;
    private int[] condition;
    private int[] rounds;
    private boolean[] alive;
    private int count;
    private int liveCount;
    private final dev.jeddy.core.collections.IntList free;

    /** Creates an empty store with room for 32 instances. */
    public Conditions() {
        this(32);
    }

    /**
     * Creates an empty store with room for {@code capacity} instances; it doubles past it, which
     * allocates, so a game sizes it for its creatures ({@link Rules} does: four per creature).
     */
    public Conditions(int capacity) {
        int n = Math.max(1, capacity);
        creature = new int[n];
        condition = new int[n];
        rounds = new int[n];
        alive = new boolean[n];
        free = new dev.jeddy.core.collections.IntList(Math.max(8, n / 4));
    }

    int add(int creature, int condition, int rounds) {
        int h;
        if (!free.isEmpty()) {
            h = free.removeLast();
        } else {
            if (count == alive.length) {
                int n = alive.length * 2;
                this.creature = Arrays.copyOf(this.creature, n);
                this.condition = Arrays.copyOf(this.condition, n);
                this.rounds = Arrays.copyOf(this.rounds, n);
                alive = Arrays.copyOf(alive, n);
            }
            h = count++;
        }
        this.creature[h] = creature;
        this.condition[h] = condition;
        this.rounds[h] = rounds;
        alive[h] = true;
        liveCount++;
        return h;
    }

    void remove(int h) {
        alive[h] = false;
        liveCount--;
        free.add(h);
    }

    /** Instances in use. */
    public int liveCount() {
        return liveCount;
    }

    /** Instance handles are below this; the store doubles past it (allocating). */
    public int capacity() {
        return alive.length;
    }

    public boolean isAlive(int h) {
        return h >= 0 && h < count && alive[h];
    }

    public int creature(int h) {
        return creature[h];
    }

    /** The condition row of instance {@code h}. */
    public int condition(int h) {
        return condition[h];
    }

    /** Remaining rounds, or {@link #INDEFINITE}. */
    public int rounds(int h) {
        return rounds[h];
    }

    void setRounds(int h, int r) {
        rounds[h] = r;
    }
}
