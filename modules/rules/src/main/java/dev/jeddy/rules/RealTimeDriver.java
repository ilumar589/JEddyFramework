package dev.jeddy.rules;

/**
 * Runs an {@link Encounter} on a clock (DESIGN §20, D100): a round is {@link #ROUND_SECONDS} long
 * and the {@code i}th creature of {@code n} in the order starts its turn {@code i / n} of the way
 * through the round, so the turns spread over the six seconds instead of all happening at once.
 * {@link #advance} moves the clock and starts the turns that are due, telling the {@link
 * TurnListener} for each creature that can act; the budget a turn grants stays spendable until the
 * creature's next turn, so a party member waiting for an order does not lose its attack.
 *
 * <p>Switching from turn-based play: {@link #sync} sets the clock to the current turn's offset, so
 * the next creature's turn is due after its share of the round rather than at once.
 *
 * <p>Thread ownership: the game thread.
 */
public final class RealTimeDriver {
    /** The length of a round. */
    public static final float ROUND_SECONDS = 6f;

    private final Encounter encounter;
    private TurnListener listener;
    private float clock; // seconds into the current round

    /** Creates a driver over {@code encounter}. */
    public RealTimeDriver(Encounter encounter) {
        this.encounter = encounter;
    }

    public Encounter encounter() {
        return encounter;
    }

    public void setListener(TurnListener listener) {
        this.listener = listener;
    }

    /** Seconds into the current round. */
    public float clock() {
        return clock;
    }

    /** Sets the clock (a load restoring a fight mid-round). */
    public void setClock(float seconds) {
        clock = seconds;
    }

    /** When turn {@code i} of the order is due within a round. */
    public float offset(int i) {
        int n = encounter.size();
        return n == 0 ? 0 : i * ROUND_SECONDS / n;
    }

    /** Sets the clock to the current turn's offset (after turn-based play moved the turn along). */
    public void sync() {
        clock = encounter.turnIndex() < 0 ? 0 : offset(encounter.turnIndex());
    }

    /**
     * Moves the clock {@code dt} seconds and starts every turn that became due; returns how many
     * turns started. Nothing happens when the fight is not active or is over.
     */
    public int advance(float dt) {
        if (!encounter.isActive() || encounter.isOver()) {
            return 0;
        }
        clock += dt;
        int started = 0;
        while (encounter.isActive() && !encounter.isOver()) {
            int next = encounter.turnIndex() + 1;
            boolean newRound = next >= encounter.size();
            float due = newRound ? ROUND_SECONDS : offset(next);
            if (clock < due) {
                break;
            }
            if (newRound) {
                clock -= ROUND_SECONDS;
            }
            int c = encounter.nextTurn();
            if (c < 0) {
                break;
            }
            started++;
            if (listener != null && encounter.rules().canAct(c)) {
                listener.onTurn(encounter, c);
            }
        }
        return started;
    }
}
