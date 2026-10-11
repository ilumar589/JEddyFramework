package dev.jeddy.rules;

import dev.jeddy.core.collections.IntList;

import java.util.Arrays;

/**
 * Typed bonuses and penalties attached to creatures (DESIGN §20, D96): a struct-of-arrays store of
 * ({@code creature}, {@code stat}, {@code type}, {@code value}, {@code source}, {@code rounds})
 * with a free list, and the stacking rule in {@link #total}: per {@link BonusType} the largest
 * bonus applies, except the types that stack ({@link BonusType#stacks}) which add up. Each creature
 * keeps the handles of its modifiers ({@link Creatures}), so resolving a statistic walks that short
 * list and never the whole store.
 *
 * <p>Sources tell a modifier where it came from, so removing a condition, a feat or an item removes
 * exactly its modifiers: {@link #source(int, int)} packs a source kind and an id.
 *
 * <p>Thread ownership: the game thread. Allocates only when the store grows.
 */
public final class Modifiers {
    /** Source kinds. */
    public static final int SOURCE_NONE = 0;

    public static final int SOURCE_CONDITION = 1; // id: the condition instance handle
    public static final int SOURCE_FEAT = 2; // id: the feat row
    public static final int SOURCE_CLASS = 3; // id: the feature row
    public static final int SOURCE_ANCESTRY = 4; // id: the ancestry row
    public static final int SOURCE_ITEM = 5; // id: the equipment slot
    public static final int SOURCE_SPELL = 6; // id: the spell row
    public static final int SOURCE_MONSTER = 7; // id: the monster row
    public static final int SOURCE_CHARACTER = 8; // id: the character row
    public static final int SOURCE_GAME = 9; // id: whatever the game wants

    /** Lasts until removed by its source. */
    public static final int PERMANENT = -1;

    private int[] creature = new int[64];
    private int[] stat = new int[64];
    private int[] type = new int[64];
    private int[] value = new int[64];
    private int[] source = new int[64];
    private int[] rounds = new int[64];
    private boolean[] alive = new boolean[64];
    private int count;
    private final IntList free = new IntList(16);
    private int liveCount;

    /** Creates an empty store. */
    public Modifiers() {}

    /** Packs a source. */
    public static int source(int kind, int id) {
        return kind << 24 | (id & 0xFFFFFF);
    }

    /** The kind of a packed source. */
    public static int sourceKind(int source) {
        return source >>> 24;
    }

    /** The id of a packed source. */
    public static int sourceId(int source) {
        return source & 0xFFFFFF;
    }

    /** Returns the number of live modifiers. */
    public int liveCount() {
        return liveCount;
    }

    /**
     * Adds a modifier of {@code value} to {@code stat} of {@code creature} and returns its handle;
     * {@code rounds} is {@link #PERMANENT} or the rounds it lasts.
     */
    public int add(int creature, int stat, int type, int value, int source, int rounds) {
        int h;
        if (!free.isEmpty()) {
            h = free.removeLast();
        } else {
            if (count == alive.length) {
                grow();
            }
            h = count++;
        }
        this.creature[h] = creature;
        this.stat[h] = stat;
        this.type[h] = type;
        this.value[h] = value;
        this.source[h] = source;
        this.rounds[h] = rounds;
        alive[h] = true;
        liveCount++;
        return h;
    }

    /** Removes modifier {@code h}. */
    public void remove(int h) {
        if (alive[h]) {
            alive[h] = false;
            free.add(h);
            liveCount--;
        }
    }

    /** Returns whether handle {@code h} is live. */
    public boolean isAlive(int h) {
        return h >= 0 && h < count && alive[h];
    }

    public int creature(int h) {
        return creature[h];
    }

    public int stat(int h) {
        return stat[h];
    }

    public int type(int h) {
        return type[h];
    }

    public int value(int h) {
        return value[h];
    }

    public int source(int h) {
        return source[h];
    }

    public int rounds(int h) {
        return rounds[h];
    }

    /** Sets the remaining rounds of {@code h}. */
    public void setRounds(int h, int r) {
        rounds[h] = r;
    }

    /**
     * Sums the modifiers in {@code handles} that target {@code stat} with the stacking rule. {@code
     * scratch} must hold {@link BonusType#COUNT} ints (the caller's, so nothing allocates).
     */
    public int total(IntList handles, int stat, int[] scratch) {
        Arrays.fill(scratch, 0, BonusType.COUNT, 0);
        int stacked = 0;
        for (int i = 0, n = handles.size(); i < n; i++) {
            int h = handles.get(i);
            if (!alive[h] || this.stat[h] != stat) {
                continue;
            }
            int t = type[h];
            int v = value[h];
            if (BonusType.stacks(t)) {
                stacked += v;
            } else if (v > scratch[t]) {
                scratch[t] = v; // the largest bonus of a type; a same-type penalty would be PENALTY
            }
        }
        for (int t = 0; t < BonusType.COUNT; t++) {
            if (!BonusType.stacks(t)) {
                stacked += scratch[t];
            }
        }
        return stacked;
    }

    /**
     * Sums the live modifiers in {@code handles} targeting {@code stat} of exactly {@code type},
     * with stacking.
     */
    public int totalOfType(IntList handles, int stat, int type) {
        int best = 0;
        int sum = 0;
        for (int i = 0, n = handles.size(); i < n; i++) {
            int h = handles.get(i);
            if (alive[h] && this.stat[h] == stat && this.type[h] == type) {
                sum += value[h];
                best = Math.max(best, value[h]);
            }
        }
        return BonusType.stacks(type) ? sum : best;
    }

    /**
     * Removes every live modifier in {@code handles} whose source is {@code source}; compacts the
     * list.
     */
    public void removeBySource(IntList handles, int source) {
        for (int i = handles.size() - 1; i >= 0; i--) {
            int h = handles.get(i);
            if (!alive[h] || this.source[h] == source) {
                remove(h);
                handles.removeAt(i);
            }
        }
    }

    /**
     * Ticks the durations of {@code handles} by one round: expired ones are removed and the list
     * compacted. Returns how many expired.
     */
    public int tick(IntList handles) {
        int expired = 0;
        for (int i = handles.size() - 1; i >= 0; i--) {
            int h = handles.get(i);
            if (!alive[h]) {
                handles.removeAt(i);
                continue;
            }
            if (rounds[h] > 0 && --rounds[h] == 0) {
                remove(h);
                handles.removeAt(i);
                expired++;
            }
        }
        return expired;
    }

    private void grow() {
        int n = alive.length * 2;
        creature = Arrays.copyOf(creature, n);
        stat = Arrays.copyOf(stat, n);
        type = Arrays.copyOf(type, n);
        value = Arrays.copyOf(value, n);
        source = Arrays.copyOf(source, n);
        rounds = Arrays.copyOf(rounds, n);
        alive = Arrays.copyOf(alive, n);
    }
}
