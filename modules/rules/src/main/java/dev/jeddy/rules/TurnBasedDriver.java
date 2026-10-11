package dev.jeddy.rules;

/**
 * Runs an {@link Encounter} one turn at a time (DESIGN §20, D100): {@link #endTurn} ends the
 * current turn and starts the next one that can act, telling the {@link TurnListener}; the game
 * calls it when the player presses *End Turn* or the AI finished its orders. Nothing happens
 * between calls, so the player can think as long as they like.
 *
 * <p>Thread ownership: the game thread.
 */
public final class TurnBasedDriver {
    private final Encounter encounter;
    private TurnListener listener;

    /** Creates a driver over {@code encounter}. */
    public TurnBasedDriver(Encounter encounter) {
        this.encounter = encounter;
    }

    public Encounter encounter() {
        return encounter;
    }

    public void setListener(TurnListener listener) {
        this.listener = listener;
    }

    /**
     * Ends the current turn and starts the next turn of a creature that can act (turns of the
     * dying, stunned and dead pass by themselves, their durations ticking). Returns that creature,
     * or -1 when the fight is over or not active.
     */
    public int endTurn() {
        if (!encounter.isActive() || encounter.isOver()) {
            return -1;
        }
        int n = encounter.size();
        for (int i = 0; i <= n; i++) { // at most one full round of passes
            int c = encounter.nextTurn();
            if (c < 0) {
                return -1;
            }
            if (encounter.rules().canAct(c)) {
                if (listener != null) {
                    listener.onTurn(encounter, c);
                }
                return c;
            }
            if (encounter.isOver()) {
                return -1;
            }
        }
        return -1;
    }
}
