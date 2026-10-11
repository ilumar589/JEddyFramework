package dev.jeddy.rules;

/**
 * Told by a driver when a creature's turn starts and it can act (the player's turn to give orders,
 * or the AI's); creatures that cannot act pass without a call.
 *
 * <p>Thread ownership: the game thread, from the driver's call.
 */
@FunctionalInterface
public interface TurnListener {
    /** {@code creature}'s turn started in {@code encounter}. */
    void onTurn(Encounter encounter, int creature);
}
