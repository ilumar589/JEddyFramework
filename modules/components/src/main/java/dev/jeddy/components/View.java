package dev.jeddy.components;

/**
 * The base of every generated view (DESIGN §22, D105): a flyweight that reads one row of one store.
 * The generated subclass adds the typed store reference, {@code at(store, row)}, {@code entity()}
 * and a getter per record component; the {@code Mut} subclass adds the setters. Views are
 * pre-allocated by the registry (one per behaviour parameter, or one per job thread for a parallel
 * behaviour) and repositioned per entity, so a loop allocates nothing.
 *
 * <p>Thread ownership: the thread running the loop that positioned it.
 */
public abstract class View {
    /** The dense row, or -1 when positioned at no row (an absent {@link Optional} component). */
    protected int row = -1;

    /** For the generated views. */
    protected View() {}

    /** Whether the view is positioned at a row (false for an absent optional component). */
    public final boolean present() {
        return row >= 0;
    }

    /** The dense row, or -1. */
    public final int row() {
        return row;
    }

    /** Positions the view at no row. */
    public final void none() {
        row = -1;
    }
}
