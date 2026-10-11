package dev.jeddy.editor.rules;

import dev.jeddy.editor.EditorContext;
import dev.jeddy.editor.command.Command;

/**
 * Sets one value of the rules content (a field of a definition, of a nested row or of an operation)
 * and remembers what was there, so undo puts it back ({@code null} means the key was absent and the
 * importer's default applied). Merges with the next set of the same path, so typing a name or
 * dragging a number is one history entry.
 *
 * <p>Thread ownership: the game thread.
 */
public final class RulesSetCommand implements Command {
    private final RulesPath path;
    private Object before;
    private Object after;
    private boolean applied;

    /** Sets {@code path} to {@code after} (a {@code String}, {@code Double}, {@code Boolean}). */
    public RulesSetCommand(RulesPath path, Object after) {
        this.path = path;
        this.after = after;
    }

    /** The path this command sets. */
    public RulesPath path() {
        return path;
    }

    @Override
    public void apply(EditorContext ctx) {
        Object previous = ctx.rules().set(path, RulesSource.copy(after));
        if (!applied) {
            before = previous;
            applied = true;
        }
    }

    @Override
    public void revert(EditorContext ctx) {
        ctx.rules().set(path, RulesSource.copy(before));
    }

    @Override
    public String name() {
        return "Set " + path;
    }

    @Override
    public boolean mergeWith(Command next) {
        if (next instanceof RulesSetCommand s && s.path.equals(path)) {
            after = s.after;
            return true;
        }
        return false;
    }
}
