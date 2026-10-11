package dev.jeddy.editor.panel;

import dev.jeddy.asset.format.LevelData;
import dev.jeddy.asset.format.RulesSchema;
import dev.jeddy.editor.Console;
import dev.jeddy.editor.EditorContext;
import dev.jeddy.editor.LevelObjects;
import dev.jeddy.editor.Selection;
import dev.jeddy.ui.TextBuffer;
import dev.jeddy.ui.Ui;
import dev.jeddy.ui.Widgets;

import java.nio.file.Path;

/**
 * The console: the editor's log lines and a command line. Commands: {@code help}, {@code clear},
 * {@code select <id>...}, {@code deselect}, {@code delete}, {@code goto <id>}, {@code save [path]},
 * {@code dump}, {@code snap <step>}, {@code stats}. Typing is a cold path (strings are made when a
 * command runs).
 *
 * <p>Thread ownership: the game thread.
 */
public final class ConsolePanel extends Panel {
    private static final long LINES = 1;
    private static final long INPUT = 2;
    private static final long LINE = 3;

    private final TextBuffer input = new TextBuffer(128);
    private int seenVersion = -1;

    /** Creates the panel. */
    public ConsolePanel() {
        super("Console");
    }

    @Override
    public void draw(EditorContext ctx, Widgets w) {
        Ui ui = w.ui;
        Console console = ctx.console();
        ui.box(LINES)
                .column()
                .widthGrow()
                .heightGrow()
                .minHeight(60)
                .gap(1)
                .padding(4)
                .background(w.theme.inset)
                .radius(w.theme.radius)
                .scroll(false, true)
                .open();
        for (int i = 0; i < console.size(); i++) {
            int level = console.level(i);
            ui.pushId(i);
            ui.text(LINE)
                    .append(console.line(i))
                    .fontSize(w.theme.fontSizeSmall)
                    .color(
                            level == Console.ERROR
                                    ? 0xFFFF7070
                                    : level == Console.WARN ? 0xFFFFC860 : w.theme.text)
                    .wrap();
            ui.popId();
        }
        ui.close();
        if (console.version() != seenVersion) {
            seenVersion = console.version();
            ui.setScroll(LINES, 1, 1e9f); // the layout clamps it to the end
        }
        if (w.textField(INPUT, input, "Command (help)") || w.submitted(INPUT)) {
            if (w.submitted(INPUT) && input.length() > 0) {
                run(ctx, input.toString().trim());
                input.clear();
            }
        }
    }

    /** Runs one command line. */
    public void run(EditorContext ctx, String line) {
        Console out = ctx.console();
        String[] args = line.split("\\s+");
        String cmd = args[0].toLowerCase(java.util.Locale.ROOT);
        LevelData level = ctx.level();
        try {
            switch (cmd) {
                case "help" ->
                        out.info(
                                "help | clear | select <id>... | deselect | delete | goto <id> |"
                                        + " save [path] | dump | snap <step> | stats |"
                                        + " panel <name> [left|right|bottom|hidden] |"
                                        + " rules <kind> [id]");
                case "clear" -> out.clear();
                case "select" -> {
                    Selection sel = ctx.selection();
                    sel.clear();
                    for (int i = 1; i < args.length; i++) {
                        int id = Integer.parseInt(args[i]);
                        if (level != null && LevelObjects.exists(level, id)) {
                            sel.add(id);
                        } else {
                            out.warn("no object " + id);
                        }
                    }
                }
                case "deselect" -> ctx.selection().clear();
                case "delete" -> ctx.editor().deleteSelection();
                case "goto" -> {
                    int id = Integer.parseInt(args[1]);
                    if (level != null && LevelObjects.exists(level, id)) {
                        ctx.selection().select(id);
                        ctx.viewport().frameSelection();
                    } else {
                        out.warn("no object " + id);
                    }
                }
                case "save" -> {
                    if (args.length > 1) {
                        ctx.editor().saveAs(Path.of(args[1]));
                    } else {
                        ctx.editor().save();
                    }
                }
                case "dump" -> {
                    if (level == null) {
                        out.warn("no level");
                    } else if (ctx.editor().file() == null) {
                        out.warn("no file: save first");
                    } else {
                        out.info(
                                "wrote "
                                        + dev.jeddy.editor.LevelFiles.dumpJson(
                                                level, ctx.editor().file()));
                    }
                }
                case "snap" -> {
                    ctx.settings().translateStep = Float.parseFloat(args[1]);
                    out.info("translate step " + ctx.settings().translateStep);
                }
                case "stats" -> {
                    if (level == null) {
                        out.warn("no level");
                    } else {
                        out.info(
                                level.placementCount()
                                        + " placements, "
                                        + level.lightCount()
                                        + " lights, "
                                        + level.spawnCount()
                                        + " spawns, "
                                        + level.triggerCount()
                                        + " triggers, "
                                        + level.prefabCount()
                                        + " prefabs, "
                                        + ctx.undo().size()
                                        + " history entries, selection "
                                        + ctx.selection().size());
                    }
                }
                case "panel" -> {
                    Dock dock = ctx.editor().dock();
                    int index = -1;
                    for (int i = 0; i < dock.panels().size(); i++) {
                        if (dock.panels().get(i).name().equalsIgnoreCase(args[1])) {
                            index = i;
                        }
                    }
                    if (index < 0) {
                        out.warn("no panel " + args[1]);
                    } else if (args.length > 2) {
                        int zone =
                                switch (args[2].toLowerCase(java.util.Locale.ROOT)) {
                                    case "left" -> Dock.LEFT;
                                    case "right" -> Dock.RIGHT;
                                    case "bottom" -> Dock.BOTTOM;
                                    default -> Dock.HIDDEN;
                                };
                        dock.setZone(index, zone);
                    } else {
                        dock.setZone(
                                index,
                                dock.zone(index) == Dock.HIDDEN ? Dock.RIGHT : dock.zone(index));
                    }
                }
                case "rules" -> {
                    Dock dock = ctx.editor().dock();
                    RulesPanel panel = null;
                    int index = -1;
                    for (int i = 0; i < dock.panels().size(); i++) {
                        if (dock.panels().get(i) instanceof RulesPanel r) {
                            panel = r;
                            index = i;
                        }
                    }
                    if (panel == null || ctx.rules() == null) {
                        out.warn("no rules content");
                        break;
                    }
                    int k = 0;
                    boolean found = false;
                    for (RulesSchema.Kind kind : RulesSchema.KINDS) {
                        if (!kind.hasId()) {
                            continue;
                        }
                        if (kind.name().equalsIgnoreCase(args[1])) {
                            panel.setKindIndex(k);
                            found = true;
                            if (args.length > 2) {
                                int row = ctx.rules().find(kind.name(), args[2]);
                                if (row < 0) {
                                    out.warn("no " + kind.name() + " with id " + args[2]);
                                }
                                panel.select(row);
                            }
                        }
                        k++;
                    }
                    if (!found) {
                        out.warn("no kind " + args[1]);
                    } else {
                        dock.setZone(
                                index,
                                dock.zone(index) == Dock.HIDDEN ? Dock.RIGHT : dock.zone(index));
                    }
                }
                default -> out.warn("unknown command: " + cmd + " (try help)");
            }
        } catch (Exception e) {
            out.error(cmd + ": " + e);
        }
    }
}
