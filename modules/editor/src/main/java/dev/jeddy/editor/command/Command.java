package dev.jeddy.editor.command;

import dev.jeddy.editor.EditorContext;

/**
 * One reversible edit of the level (DESIGN §19, D89). A command carries exactly the data its {@link
 * #apply} and {@link #revert} need: the ids of the rows it touches and their field values before
 * and after, never a copy of the whole level. The {@link dev.jeddy.editor.UndoStack} applies it
 * once when pushed, reverts it on undo and applies it again on redo, so both methods must be safe
 * to call any number of times in alternation.
 *
 * <p>Commands run on the game thread between frames and may allocate: they are user actions, a few
 * per second at most.
 *
 * <p>Thread ownership: the game thread.
 */
public interface Command {
    /** Performs the edit on the level and tells the world (through the loader) what changed. */
    void apply(EditorContext ctx);

    /** Undoes the edit: restores every field {@link #apply} changed. */
    void revert(EditorContext ctx);

    /** A short label for the Edit menu and the console ("Move 3 objects"). */
    String name();

    /**
     * Folds {@code next} into this command when both are steps of one continuous edit (a gizmo
     * drag, typing digits into a field): this command keeps its "before" values and takes {@code
     * next}'s "after" values. Returns {@code false} when the two are unrelated and {@code next}
     * must be pushed on its own. {@code next} has already been applied when this is called.
     */
    default boolean mergeWith(Command next) {
        return false;
    }
}
