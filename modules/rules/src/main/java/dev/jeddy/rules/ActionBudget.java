package dev.jeddy.rules;

import java.util.Arrays;

/**
 * What each creature may still do this round (DESIGN §20, D100): a standard action, a move action
 * and a swift action, as bits; a full-round action spends standard and move together; a 5-foot step
 * is free once a turn as long as the creature takes no other movement. The budget resets at the
 * creature's turn start and is spent by the game through {@link Encounter#useAction}; it is per
 * creature rather than per turn so the real-time driver can let a creature spend its round while
 * others act (see {@link RealTimeDriver}).
 *
 * <p>Thread ownership: the game thread, through {@link Encounter}.
 */
public final class ActionBudget {
    /** A standard action: an attack, a spell, an item. */
    public static final int STANDARD = 1;

    /** A move action: moving up to the speed, standing up, drawing. */
    public static final int MOVE = 2;

    /** A swift action: one quick thing a round. */
    public static final int SWIFT = 4;

    /** A full-round action: a full attack, a charge; spends standard and move. */
    public static final int FULL_ROUND = STANDARD | MOVE;

    /** A 5-foot step: free once a turn when no other movement is taken. */
    public static final int STEP = 8;

    /** The whole budget of a fresh turn. */
    public static final int ALL = STANDARD | MOVE | SWIFT | STEP;

    private int[] left = new int[16];
    private boolean[] moved = new boolean[16];

    ActionBudget() {}

    private void ensure(int c) {
        if (c >= left.length) {
            int n = Math.max(c + 1, left.length * 2);
            left = Arrays.copyOf(left, n);
            moved = Arrays.copyOf(moved, n);
        }
    }

    /** Gives {@code c} a fresh turn's budget. */
    void reset(int c) {
        ensure(c);
        left[c] = ALL;
        moved[c] = false;
    }

    /** Empties {@code c}'s budget (a creature that cannot act). */
    void clear(int c) {
        ensure(c);
        left[c] = 0;
    }

    /** The actions {@code c} still has, as {@code STANDARD | MOVE | SWIFT | STEP} bits. */
    public int left(int c) {
        return c < left.length ? left[c] : 0;
    }

    /** Returns whether {@code c} can still take {@code action} (all of its bits). */
    public boolean has(int c, int action) {
        if (c >= left.length) {
            return false;
        }
        if (action == STEP) {
            return (left[c] & STEP) != 0 && !moved[c];
        }
        return (left[c] & action) == action;
    }

    /**
     * Spends {@code action} if available; a move action may be downgraded from a standard one (a
     * creature can take two move actions instead of standard plus move). Returns whether it was
     * spent.
     */
    boolean use(int c, int action) {
        if (!has(c, action)) {
            if (action == MOVE && has(c, STANDARD)) {
                left[c] &= ~STANDARD; // the second move action comes out of the standard one
                return true;
            }
            return false;
        }
        left[c] &= ~action;
        if (action == STEP) {
            moved[c] = true; // no further movement after a step
        }
        return true;
    }

    /** Records that {@code c} moved on the ground this turn, which forbids the 5-foot step. */
    void movedThisTurn(int c) {
        ensure(c);
        moved[c] = true;
        left[c] &= ~STEP;
    }

    /** Returns whether {@code c} has moved this turn (a step counts). */
    public boolean moved(int c) {
        return c < moved.length && moved[c];
    }
}
