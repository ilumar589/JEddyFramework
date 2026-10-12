package dev.jeddy.samples.iso;

/**
 * The signals the demo raises on its registry ({@code CryptGame.signal}, DESIGN §22, D108): the
 * bridge from the rules' event ring to the behaviours that react per entity.
 */
public final class Signals {
    /** A creature took damage: {@code a} the amount, {@code b} the damage type. */
    public static final int HURT = 1;

    private Signals() {}
}
