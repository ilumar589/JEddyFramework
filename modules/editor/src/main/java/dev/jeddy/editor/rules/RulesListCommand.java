package dev.jeddy.editor.rules;

import dev.jeddy.editor.EditorContext;
import dev.jeddy.editor.command.Command;

/**
 * Inserts, removes or moves one element of a list in the rules content: a definition in its kind, a
 * nested row, an operation, an id in a reference list. A removal keeps the element it took out, so
 * undo re-inserts the very same tree.
 *
 * <p>Thread ownership: the game thread.
 */
public final class RulesListCommand implements Command {
    private enum Op {
        INSERT,
        REMOVE,
        MOVE
    }

    private final Op op;
    private final RulesPath list;
    private final int index;
    private final int to;
    private Object element;
    private final String label;

    private RulesListCommand(
            Op op, RulesPath list, int index, int to, Object element, String label) {
        this.op = op;
        this.list = list;
        this.index = index;
        this.to = to;
        this.element = element;
        this.label = label;
    }

    /** Inserts {@code element} at {@code index} of the list at {@code list}. */
    public static RulesListCommand insert(RulesPath list, int index, Object element, String label) {
        return new RulesListCommand(Op.INSERT, list, index, index, element, label);
    }

    /** Removes the element at {@code index} of the list at {@code list}. */
    public static RulesListCommand remove(RulesPath list, int index, String label) {
        return new RulesListCommand(Op.REMOVE, list, index, index, null, label);
    }

    /** Moves the element at {@code from} of the list at {@code list} to {@code to}. */
    public static RulesListCommand move(RulesPath list, int from, int to, String label) {
        return new RulesListCommand(Op.MOVE, list, from, to, null, label);
    }

    /** The index the command inserts at or removes from. */
    public int index() {
        return index;
    }

    @Override
    public void apply(EditorContext ctx) {
        RulesSource s = ctx.rules();
        switch (op) {
            case INSERT -> s.insert(list, index, RulesSource.copy(element));
            case REMOVE -> element = s.remove(list, index);
            case MOVE -> s.move(list, index, to);
        }
    }

    @Override
    public void revert(EditorContext ctx) {
        RulesSource s = ctx.rules();
        switch (op) {
            case INSERT -> s.remove(list, index);
            case REMOVE -> s.insert(list, index, element);
            case MOVE -> s.move(list, to, index);
        }
    }

    @Override
    public String name() {
        return label;
    }
}
