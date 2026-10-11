package dev.jeddy.editor;

import dev.jeddy.editor.command.Command;

import java.util.ArrayList;

/**
 * The editor's history (DESIGN §19, D89): a list of applied {@link Command}s and a cursor. {@link
 * #push} applies a command and drops whatever could have been redone; {@link #undo} moves the
 * cursor back by reverting, {@link #redo} forward by applying again. The stack also remembers where
 * the cursor was when the level was last saved, which is what "unsaved changes" means.
 *
 * <p>Two ways to make a continuous edit one history entry: push a command once and keep mutating
 * and re-applying the same object while the mouse is held (what the gizmos do: {@link
 * dev.jeddy.editor.command.TransformCommand#setAfter}), or push every step with {@code merge} set
 * so the previous entry folds it in through {@link Command#mergeWith} (what number fields do while
 * the user types).
 *
 * <p>Thread ownership: the game thread.
 */
public final class UndoStack {
    private final ArrayList<Command> commands = new ArrayList<>();
    private final EditorContext ctx;
    private final int capacity;
    private int cursor; // commands [0, cursor) are applied
    private int savedAt; // the cursor at the last save, or -1 once that state is unreachable

    /** Creates a stack keeping at most {@code capacity} entries (the oldest are forgotten). */
    public UndoStack(EditorContext ctx, int capacity) {
        this.ctx = ctx;
        this.capacity = capacity;
    }

    /** Applies {@code command} and records it. */
    public void push(Command command) {
        push(command, false);
    }

    /**
     * Applies {@code command} and records it; with {@code merge}, lets the previous entry absorb it
     * ({@link Command#mergeWith}) so the two undo together.
     */
    public void push(Command command, boolean merge) {
        command.apply(ctx);
        if (cursor < commands.size()) {
            commands.subList(cursor, commands.size()).clear(); // the redo tail is gone
            if (savedAt > cursor) {
                savedAt = -1;
            }
        }
        if (merge
                && cursor > 0
                && cursor != savedAt
                && commands.get(cursor - 1).mergeWith(command)) {
            return;
        }
        commands.add(command);
        cursor++;
        if (commands.size() > capacity) {
            commands.removeFirst();
            cursor--;
            savedAt = savedAt < 0 ? -1 : savedAt - 1;
        }
    }

    /** Reverts the last applied command. Returns {@code false} if there is none. */
    public boolean undo() {
        if (cursor == 0) {
            return false;
        }
        commands.get(--cursor).revert(ctx);
        return true;
    }

    /** Re-applies the last undone command. Returns {@code false} if there is none. */
    public boolean redo() {
        if (cursor == commands.size()) {
            return false;
        }
        commands.get(cursor++).apply(ctx);
        return true;
    }

    /** Returns whether {@link #undo} would do something. */
    public boolean canUndo() {
        return cursor > 0;
    }

    /** Returns whether {@link #redo} would do something. */
    public boolean canRedo() {
        return cursor < commands.size();
    }

    /** Returns the name of the command {@link #undo} would revert, or {@code null}. */
    public String undoName() {
        return cursor > 0 ? commands.get(cursor - 1).name() : null;
    }

    /** Returns the name of the command {@link #redo} would apply, or {@code null}. */
    public String redoName() {
        return cursor < commands.size() ? commands.get(cursor).name() : null;
    }

    /** Returns the last applied command, or {@code null} (a drag mutates it while it lasts). */
    public Command top() {
        return cursor > 0 ? commands.get(cursor - 1) : null;
    }

    /**
     * Reverts and forgets the last applied command (a drag cancelled with Escape): unlike {@link
     * #undo} it leaves nothing to redo.
     */
    public void cancelTop() {
        if (cursor == 0) {
            return;
        }
        commands.get(--cursor).revert(ctx);
        commands.remove(cursor);
        if (savedAt > cursor) {
            savedAt = -1;
        }
    }

    /** Records that the level on disk matches the current state. */
    public void markSaved() {
        savedAt = cursor;
    }

    /** Returns whether the level differs from the last saved (or opened) state. */
    public boolean isDirty() {
        return cursor != savedAt;
    }

    /** Forgets the history (a new level was opened) and marks it saved. */
    public void clear() {
        commands.clear();
        cursor = 0;
        savedAt = 0;
    }

    /** Returns the number of entries, applied or not (the History panel lists them). */
    public int size() {
        return commands.size();
    }

    /** Returns the cursor: entries below it are applied. */
    public int cursor() {
        return cursor;
    }

    /** Returns entry {@code i}. */
    public Command commandAt(int i) {
        return commands.get(i);
    }

    /** Undoes or redoes until {@code target} entries are applied (clicking in the History). */
    public void seek(int target) {
        while (cursor > target && undo()) {}
        while (cursor < target && redo()) {}
    }
}
