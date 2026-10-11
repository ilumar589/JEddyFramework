package dev.jeddy.editor.panel;

import dev.jeddy.editor.EditorContext;
import dev.jeddy.editor.UndoStack;
import dev.jeddy.ui.Ui;
import dev.jeddy.ui.Widgets;

/**
 * The undo history as a list: applied entries above the cursor, undone ones dimmed below it;
 * clicking an entry undoes or redoes up to it ({@link UndoStack#seek}).
 *
 * <p>Thread ownership: the game thread.
 */
public final class HistoryPanel extends Panel {
    private static final long ROW = 1;
    private static final long ORIGIN = 2;

    /** Creates the panel. */
    public HistoryPanel() {
        super("History");
    }

    @Override
    public void draw(EditorContext ctx, Widgets w) {
        Ui ui = w.ui;
        UndoStack undo = ctx.undo();
        if (w.selectableRow(ORIGIN, "(opened)", undo.cursor() == 0, 0)) {
            undo.seek(0);
        }
        for (int i = 0; i < undo.size(); i++) {
            boolean applied = i < undo.cursor();
            ui.pushId(i);
            w.beginSelectableRow(ROW, i == undo.cursor() - 1, 0);
            ui.text(undo.commandAt(i).name())
                    .color(
                            i == undo.cursor() - 1
                                    ? 0xFFFFFFFF
                                    : applied ? w.theme.text : w.theme.textDim);
            if (w.endSelectableRow(ROW)) {
                undo.seek(i + 1);
            }
            ui.popId();
        }
        if (undo.size() == 0) {
            w.labelDim("No edits yet");
        }
    }
}
