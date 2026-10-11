package dev.jeddy.editor.panel;

import dev.jeddy.asset.format.RulesSchema;
import dev.jeddy.asset.format.RulesSchema.Field;
import dev.jeddy.asset.format.RulesSchema.Kind;
import dev.jeddy.core.collections.IntList;
import dev.jeddy.editor.EditorContext;
import dev.jeddy.editor.Text;
import dev.jeddy.editor.command.Command;
import dev.jeddy.editor.rules.RulesListCommand;
import dev.jeddy.editor.rules.RulesPath;
import dev.jeddy.editor.rules.RulesSetCommand;
import dev.jeddy.editor.rules.RulesSource;
import dev.jeddy.math.Dice;
import dev.jeddy.ui.TextBuffer;
import dev.jeddy.ui.Ui;
import dev.jeddy.ui.Widgets;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Edits the rules content (DESIGN §20, D97): a kind to browse (conditions, feats, spells...), a
 * filtered list of its definitions, and for the selected one a property grid generated from {@link
 * RulesSchema}: text fields for strings and dice, number fields, checkboxes, drop-downs for enums
 * and references (the ids of the referenced kind), flag checkboxes, operation lists with add,
 * remove and reorder, nested rows (a class's features, a monster's attacks) as tree nodes with the
 * same grid. Every edit is a command on the editor's history; *Save* writes the JSON files, which
 * the cooker's watcher re-cooks into the {@code .jrules} the game hot-reloads. The panel validates
 * on every change and lists what the importer would reject.
 *
 * <p>The grid works on the JSON values the files hold, never on the cooked binary, so a field the
 * schema does not know cannot be entered; references pick from existing ids, so a dangling one can
 * only come from deleting a definition, which validation then reports.
 *
 * <p>Drawing allocates nothing once the caches are warm: text buffers come from a pool assigned in
 * declaration order, option arrays are rebuilt only when the content's version changes.
 *
 * <p>Thread ownership: the game thread.
 */
public final class RulesPanel extends Panel {
    private static final long KIND = 1;
    private static final long FILTER = 2;
    private static final long LIST = 3;
    private static final long ADD = 4;
    private static final long DUPLICATE = 5;
    private static final long DELETE = 6;
    private static final long SAVE = 7;
    private static final long ERRORS = 8;
    private static final long FIELD = 10; // inside a pushId(field index) scope
    private static final long OP = 20;
    private static final long STAT = 21;
    private static final long TYPE = 22;
    private static final long VALUE = 23;
    private static final long DICE_FIELD = 24;
    private static final long ROUNDS = 25;
    private static final long REF = 26;
    private static final long DAMAGE_TYPE = 27;
    private static final long UP = 28;
    private static final long DOWN = 29;
    private static final long REMOVE = 30;
    private static final long ADD_ROW = 31;
    private static final long NODE = 32;
    private static final long FLAG = 40; // + flag index
    private static final float LIST_HEIGHT = 180;
    private static final CharSequence[] NO_IDS = {"(none)"};
    private static final int ERROR_COLOR = 0xFFFF7A6B;

    private final List<Kind> kinds = new ArrayList<>();
    private final CharSequence[] kindNames;
    private int kindIndex;
    private final TextBuffer filter = new TextBuffer(32);
    private CharSequence[] visibleIds = new CharSequence[64];
    private final IntList visibleRows = new IntList(64);
    private int selected = -1;
    private final TextBuffer[] pool = new TextBuffer[128];
    private int poolCursor;
    private final TextBuffer overflow = new TextBuffer(64);
    private final Map<String, CharSequence[]> idOptions = new HashMap<>();
    private int idOptionsVersion = -1;
    private final List<String> errors = new ArrayList<>();
    private final StringBuilder scratch = new StringBuilder(64);
    private final PathStack stack = new PathStack();
    private final int[] poolVersion = new int[128];
    private final boolean[] poolValid = new boolean[128];
    private final CharSequence[] booleanNames = {"false", "true"};

    /** Creates the panel. */
    public RulesPanel() {
        super("Rules");
        for (Kind k : RulesSchema.KINDS) {
            if (k.hasId()) {
                kinds.add(k);
            }
        }
        kindNames = new CharSequence[kinds.size()];
        for (int i = 0; i < kinds.size(); i++) {
            kindNames[i] = kinds.get(i).name();
        }
        for (int i = 0; i < pool.length; i++) {
            pool[i] = new TextBuffer(48);
        }
    }

    /** The kind shown, by index among the top-level kinds. */
    public int kindIndex() {
        return kindIndex;
    }

    /** Shows kind {@code index} (among the top-level kinds) and clears the selection. */
    public void setKindIndex(int index) {
        if (index != kindIndex) {
            kindIndex = index;
            selected = -1;
        }
    }

    /** The selected definition's row in its kind, or -1. */
    public int selected() {
        return selected;
    }

    /** Selects row {@code row} of the shown kind. */
    public void select(int row) {
        selected = row;
    }

    @Override
    public void draw(EditorContext ctx, Widgets w) {
        Ui ui = w.ui;
        RulesSource rules = ctx.rules();
        if (rules == null) {
            w.labelDim("No rules content: start with --project <assets> holding rules/*.json.");
            return;
        }
        poolCursor = 0;
        refreshCaches(rules);
        Kind kind = kinds.get(kindIndex);
        List<Object> rows = rules.rows(kind.name());
        if (selected >= rows.size()) {
            selected = rows.size() - 1;
        }

        w.row().open();
        setKindIndex(w.dropdown(KIND, kindIndex, kindNames));
        w.spacer();
        if (w.button(SAVE, rules.isDirty() ? "Save*" : "Save", rules.isDirty())) {
            ctx.editor().saveRules();
        }
        ui.close();
        kind = kinds.get(kindIndex);
        rows = rules.rows(kind.name());

        w.textField(FILTER, filter, "filter ids");
        // The visible rows: those whose id contains the filter.
        visibleRows.clear();
        int visibleSelected = -1;
        for (int i = 0; i < rows.size(); i++) {
            String id = rules.id(kind.name(), i);
            if (Text.containsIgnoreCase(id, filter)) {
                if (visibleRows.size() == visibleIds.length) {
                    visibleIds = java.util.Arrays.copyOf(visibleIds, visibleIds.length * 2);
                }
                if (i == selected) {
                    visibleSelected = visibleRows.size();
                }
                visibleIds[visibleRows.size()] = id;
                visibleRows.add(i);
            }
        }
        int picked = w.listBox(LIST, visibleSelected, visibleIds, visibleRows.size(), LIST_HEIGHT);
        if (picked != visibleSelected && picked >= 0) {
            selected = visibleRows.get(picked);
        }
        w.row().open();
        if (w.button(ADD, "Add")) {
            String id = rules.freeId(kind.name(), "new" + capitalize(kind.name()));
            int at = rows.size();
            push(
                    ctx,
                    RulesListCommand.insert(
                            RulesPath.of(kind.name()),
                            at,
                            RulesSource.newDefinition(kind, id),
                            "Add " + id),
                    false);
            selected = at;
        }
        if (w.button(DUPLICATE, "Duplicate", selected >= 0)) {
            Map<String, Object> copy = castMap(RulesSource.copy(rows.get(selected)));
            String id = rules.freeId(kind.name(), rules.id(kind.name(), selected));
            copy.put("id", id);
            int at = selected + 1;
            push(
                    ctx,
                    RulesListCommand.insert(RulesPath.of(kind.name()), at, copy, "Duplicate " + id),
                    false);
            selected = at;
        }
        if (w.button(DELETE, "Delete", selected >= 0)) {
            push(
                    ctx,
                    RulesListCommand.remove(
                            RulesPath.of(kind.name()),
                            selected,
                            "Delete " + rules.id(kind.name(), selected)),
                    false);
            selected = Math.min(selected, rows.size() - 1);
        }
        ui.close();
        if (!errors.isEmpty()) {
            if (w.treeNode(ERRORS, errorsLabel(), true)) {
                for (int i = 0; i < errors.size() && i < 8; i++) {
                    ui.text(errors.get(i))
                            .wrap()
                            .fontSize(w.theme.fontSizeSmall)
                            .color(ERROR_COLOR);
                }
                w.treeEnd();
            }
        }
        w.separator();
        if (selected < 0 || selected >= rows.size()) {
            w.labelDim("Select a definition, or add one.");
            return;
        }
        ui.pushId(selected);
        stack.clear();
        stack.push(kind.name());
        stack.push(selected);
        grid(ctx, w, rules, kind, castMap(rows.get(selected)));
        ui.popId();
    }

    // ---- The grid -----------------------------------------------------------------------------

    /**
     * Declares a property row per field of {@code kind} for the object at the path stack's top.
     * Paths are only materialized when a command is pushed, so a frame allocates nothing.
     */
    private void grid(
            EditorContext ctx, Widgets w, RulesSource rules, Kind kind, Map<String, Object> obj) {
        Ui ui = w.ui;
        List<Field> fields = kind.fields();
        for (int f = 0; f < fields.size(); f++) {
            Field field = fields.get(f);
            ui.pushId(f);
            Object v = obj.get(field.name());
            String key = field.name();
            switch (field.type()) {
                case STRING -> {
                    w.beginProperty(key);
                    stringField(
                            ctx,
                            w,
                            FIELD,
                            key,
                            v instanceof String s ? s : field.defaultString(),
                            field.doc());
                    w.endProperty();
                }
                case INT -> {
                    w.beginProperty(key);
                    intField(ctx, w, FIELD, key, v, field.defaultInt(), field.doc());
                    w.endProperty();
                }
                case BOOL -> {
                    w.beginProperty(key);
                    boolean current = v instanceof Boolean b ? b : field.defaultInt() != 0;
                    boolean edited = w.checkbox(FIELD, "", current);
                    w.tooltip(FIELD, field.doc());
                    if (edited != current) {
                        push(ctx, new RulesSetCommand(stack.path(key), edited), false);
                    }
                    w.endProperty();
                }
                case ENUM -> {
                    w.beginProperty(key);
                    enumField(ctx, w, FIELD, key, field, v);
                    w.endProperty();
                }
                case FLAGS -> {
                    w.beginProperty(key);
                    w.column().open();
                    for (int k = 0; k < field.names().length; k++) {
                        boolean set = hasFlag(v, field.names()[k]);
                        if (w.checkbox(FLAG + k, field.names()[k], set) != set) {
                            List<Object> flags = new ArrayList<>();
                            if (v instanceof List<?> l) {
                                for (Object o : l) {
                                    if (!field.names()[k].equals(o)) {
                                        flags.add(o);
                                    }
                                }
                            }
                            if (!set) {
                                flags.add(field.names()[k]);
                            }
                            push(ctx, new RulesSetCommand(stack.path(key), flags), false);
                        }
                    }
                    ui.close();
                    w.endProperty();
                }
                case DICE -> {
                    w.beginProperty(key);
                    diceField(ctx, w, FIELD, key, v, field.doc());
                    w.endProperty();
                }
                case REF -> {
                    w.beginProperty(key);
                    refField(ctx, w, FIELD, key, field.refKind(), v);
                    w.endProperty();
                }
                case REF_LIST -> {
                    w.beginProperty(key);
                    w.column().open();
                    List<?> list = v instanceof List<?> l ? l : List.of();
                    stack.push(key);
                    for (int i = 0; i < list.size(); i++) {
                        ui.pushId(i);
                        w.row().open();
                        refField(ctx, w, REF, i, field.refKind(), list.get(i));
                        if (w.button(REMOVE, "x")) {
                            push(
                                    ctx,
                                    RulesListCommand.remove(stack.path(), i, "Remove " + key),
                                    false);
                        }
                        ui.close();
                        ui.popId();
                    }
                    CharSequence[] ids = idOptions(field.refKind());
                    if (w.button(ADD_ROW, "Add", ids.length > 1)) {
                        push(
                                ctx,
                                RulesListCommand.insert(
                                        stack.path(), list.size(), ids[1].toString(), "Add " + key),
                                false);
                    }
                    stack.pop();
                    ui.close();
                    w.endProperty();
                }
                case INT_LIST -> {
                    w.beginProperty(key);
                    scratch.setLength(0);
                    if (v instanceof List<?> l) {
                        for (int i = 0; i < l.size(); i++) {
                            if (i > 0) {
                                scratch.append(", ");
                            }
                            scratch.append(l.get(i) instanceof Number n ? n.intValue() : 0);
                        }
                    }
                    TextBuffer buffer = buffer();
                    if (!ui.focused(FIELD) && !Text.equals(buffer, scratch)) {
                        buffer.set(scratch);
                    }
                    if (w.textField(FIELD, buffer, "numbers, comma separated")) {
                        push(ctx, new RulesSetCommand(stack.path(key), parseInts(buffer)), true);
                    }
                    w.tooltip(FIELD, field.doc());
                    w.endProperty();
                }
                case OPS -> {
                    if (w.treeNode(NODE, key, true)) {
                        stack.push(key);
                        operations(ctx, w, v);
                        stack.pop();
                        w.treeEnd();
                    }
                }
                case NESTED -> {
                    Kind nested = RulesSchema.kind(field.refKind());
                    if (w.treeNode(NODE, key, true)) {
                        stack.push(key);
                        List<?> list = v instanceof List<?> l ? l : List.of();
                        for (int i = 0; i < list.size(); i++) {
                            ui.pushId(i);
                            Map<String, Object> row = castMap(list.get(i));
                            // A tree node is a full-width header (it opens its body when open),
                            // so the row's buttons go on the first line of the body.
                            if (w.treeNode(NODE, rowLabel(nested, i, row), i == 0)) {
                                w.row().open();
                                w.spacer();
                                if (w.button(UP, "^", i > 0)) {
                                    push(
                                            ctx,
                                            RulesListCommand.move(
                                                    stack.path(),
                                                    i,
                                                    i - 1,
                                                    "Move " + nested.name()),
                                            false);
                                }
                                if (w.button(DOWN, "v", i + 1 < list.size())) {
                                    push(
                                            ctx,
                                            RulesListCommand.move(
                                                    stack.path(),
                                                    i,
                                                    i + 1,
                                                    "Move " + nested.name()),
                                            false);
                                }
                                if (w.button(REMOVE, "x")) {
                                    push(
                                            ctx,
                                            RulesListCommand.remove(
                                                    stack.path(), i, "Remove " + nested.name()),
                                            false);
                                }
                                ui.close();
                                stack.push(i);
                                grid(ctx, w, rules, nested, row);
                                stack.pop();
                                w.treeEnd();
                            }
                            ui.popId();
                        }
                        if (w.button(ADD_ROW, "Add")) {
                            push(
                                    ctx,
                                    RulesListCommand.insert(
                                            stack.path(),
                                            list.size(),
                                            RulesSource.newDefinition(nested, ""),
                                            "Add " + nested.name()),
                                    false);
                        }
                        stack.pop();
                        w.treeEnd();
                    }
                }
            }
            ui.popId();
        }
    }

    /** The operation list at the stack's top: one row of widgets per operation, by its op. */
    private void operations(EditorContext ctx, Widgets w, Object v) {
        Ui ui = w.ui;
        List<?> list = v instanceof List<?> l ? l : List.of();
        Field opField = RulesSchema.OP_FIELDS.get(RulesSchema.OP_OP);
        for (int i = 0; i < list.size(); i++) {
            ui.pushId(i);
            stack.push(i);
            Map<String, Object> op = castMap(list.get(i));
            int opIndex = Math.max(0, opField.enumIndex(String.valueOf(op.get("op"))));
            String opName = RulesSchema.OPS[opIndex];
            w.row().open();
            enumField(ctx, w, OP, "op", opField, op.get("op"));
            w.spacer();
            if (w.button(UP, "^", i > 0)) {
                push(
                        ctx,
                        RulesListCommand.move(stack.parentPath(), i, i - 1, "Move effect"),
                        false);
            }
            if (w.button(DOWN, "v", i + 1 < list.size())) {
                push(
                        ctx,
                        RulesListCommand.move(stack.parentPath(), i, i + 1, "Move effect"),
                        false);
            }
            if (w.button(REMOVE, "x")) {
                push(ctx, RulesListCommand.remove(stack.parentPath(), i, "Remove effect"), false);
            }
            ui.close();
            // One property row per field the operation uses (a row of four would be wider than
            // a side panel and stretch every other row with it).
            switch (opName) {
                case "modify" -> {
                    property(w, "stat");
                    enumField(
                            ctx,
                            w,
                            STAT,
                            "stat",
                            RulesSchema.OP_FIELDS.get(RulesSchema.OP_STAT),
                            op.get("stat"));
                    w.endProperty();
                    property(w, "type");
                    enumField(
                            ctx,
                            w,
                            TYPE,
                            "type",
                            RulesSchema.OP_FIELDS.get(RulesSchema.OP_TYPE),
                            op.get("type"));
                    w.endProperty();
                    property(w, "value");
                    intField(
                            ctx,
                            w,
                            VALUE,
                            "value",
                            op.get("value"),
                            0,
                            "the bonus (negative for a penalty)");
                    w.endProperty();
                    property(w, "rounds");
                    intField(
                            ctx,
                            w,
                            ROUNDS,
                            "rounds",
                            op.get("rounds"),
                            0,
                            "duration in rounds; 0 lasts while the source applies");
                    w.endProperty();
                }
                case "damage" -> {
                    property(w, "dice");
                    diceField(ctx, w, DICE_FIELD, "dice", op.get("dice"), "damage dice");
                    w.endProperty();
                    property(w, "damageType");
                    enumField(
                            ctx,
                            w,
                            DAMAGE_TYPE,
                            "damageType",
                            RulesSchema.OP_FIELDS.get(RulesSchema.OP_DAMAGE_TYPE),
                            op.get("damageType"));
                    w.endProperty();
                }
                case "heal" -> {
                    property(w, "dice");
                    diceField(ctx, w, DICE_FIELD, "dice", op.get("dice"), "healing dice");
                    w.endProperty();
                }
                case "tempHp" -> {
                    property(w, "dice");
                    diceField(
                            ctx, w, DICE_FIELD, "dice", op.get("dice"), "temporary hit point dice");
                    w.endProperty();
                    property(w, "rounds");
                    intField(ctx, w, ROUNDS, "rounds", op.get("rounds"), 0, "rounds they last");
                    w.endProperty();
                }
                case "condition" -> {
                    property(w, "ref");
                    refField(ctx, w, REF, "ref", "conditions", op.get("ref"));
                    w.endProperty();
                    property(w, "rounds");
                    intField(
                            ctx,
                            w,
                            ROUNDS,
                            "rounds",
                            op.get("rounds"),
                            0,
                            "rounds; 0 lasts while the source applies");
                    w.endProperty();
                }
                case "removeCondition" -> {
                    property(w, "ref");
                    refField(ctx, w, REF, "ref", "conditions", op.get("ref"));
                    w.endProperty();
                }
                case "grantFeat" -> {
                    property(w, "ref");
                    refField(ctx, w, REF, "ref", "feats", op.get("ref"));
                    w.endProperty();
                }
                case "extraAttack" -> {
                    property(w, "value");
                    intField(
                            ctx,
                            w,
                            VALUE,
                            "value",
                            op.get("value"),
                            0,
                            "extra attacks on a full attack");
                    w.endProperty();
                    property(w, "rounds");
                    intField(
                            ctx,
                            w,
                            ROUNDS,
                            "rounds",
                            op.get("rounds"),
                            0,
                            "rounds; 0 lasts while the source applies");
                    w.endProperty();
                }
                case "setSpeed" -> {
                    property(w, "value");
                    intField(
                            ctx,
                            w,
                            VALUE,
                            "value",
                            op.get("value"),
                            0,
                            "the new base speed in feet");
                    w.endProperty();
                }
                default -> {}
            }
            stack.pop();
            ui.popId();
        }
        if (w.button(ADD_ROW, "Add effect")) {
            push(
                    ctx,
                    RulesListCommand.insert(
                            stack.path(), list.size(), RulesSource.newOperation(), "Add effect"),
                    false);
        }
    }

    // ---- Field widgets ------------------------------------------------------------------------

    /** Opens an indented property row (operation fields sit under their operation's header). */
    private static void property(Widgets w, String label) {
        w.beginProperty(label);
    }

    private void stringField(
            EditorContext ctx, Widgets w, long id, String key, String value, String doc) {
        TextBuffer buffer = buffer();
        if (!w.ui.focused(id) && !Text.equals(buffer, value)) {
            buffer.set(value);
        }
        if (w.textField(id, buffer)) {
            push(ctx, new RulesSetCommand(stack.path(key), buffer.toString()), true);
        }
        if (doc != null) {
            w.tooltip(id, doc);
        }
    }

    private void diceField(
            EditorContext ctx, Widgets w, long id, String key, Object value, String doc) {
        int slot = poolCursor;
        TextBuffer buffer = buffer();
        String text = value instanceof String s ? s : "";
        if (!w.ui.focused(id) && !Text.equals(buffer, text)) {
            buffer.set(text);
        }
        if (w.textField(id, buffer, "2d6+1")) {
            push(ctx, new RulesSetCommand(stack.path(key), buffer.toString()), true);
        }
        // Validity is checked when the text changes (parsing a bad expression throws).
        if (slot < pool.length && poolVersion[slot] != buffer.version()) {
            poolVersion[slot] = buffer.version();
            boolean valid = true;
            try {
                Dice.parse(buffer);
            } catch (IllegalArgumentException e) {
                valid = false;
            }
            poolValid[slot] = valid;
        }
        boolean valid = slot >= pool.length || poolValid[slot];
        w.tooltip(id, valid ? doc : "not a dice expression (like 2d6+1)");
    }

    private void intField(
            EditorContext ctx,
            Widgets w,
            long id,
            String key,
            Object value,
            int fallback,
            String doc) {
        int current = value instanceof Number n ? n.intValue() : fallback;
        int edited = Math.round(w.numberField(id, current, 0.1f, -9999, 9999, 0));
        w.tooltip(id, doc);
        if (edited != current) {
            push(ctx, new RulesSetCommand(stack.path(key), (double) edited), true);
        }
    }

    private void enumField(
            EditorContext ctx, Widgets w, long id, String key, Field field, Object value) {
        int current = value instanceof String s ? field.enumIndex(s) : -1;
        int shown = current < 0 ? field.defaultInt() : current;
        int edited = w.dropdown(id, shown, field.names());
        if (edited != shown) {
            push(ctx, new RulesSetCommand(stack.path(key), field.names()[edited]), false);
        }
    }

    private void refField(
            EditorContext ctx, Widgets w, long id, Object key, String refKind, Object value) {
        CharSequence[] ids = idOptions(refKind);
        int current = 0;
        if (value instanceof String s) {
            for (int i = 1; i < ids.length; i++) {
                if (ids[i].equals(s)) {
                    current = i;
                    break;
                }
            }
        }
        int edited = w.dropdown(id, current, ids);
        if (edited != current) {
            push(
                    ctx,
                    new RulesSetCommand(
                            stack.path(key), edited == 0 ? null : ids[edited].toString()),
                    false);
        }
    }

    /**
     * The path to what the grid is drawing, as a stack of segments; {@link #path} snapshots it into
     * a {@link RulesPath} only when a command needs one.
     */
    private static final class PathStack {
        private final Object[] segments = new Object[32];
        private final Integer[] boxed = new Integer[1024]; // indices without boxing per frame
        private int depth;

        void clear() {
            depth = 0;
        }

        void push(String key) {
            segments[depth++] = key;
        }

        void push(int index) {
            if (index < boxed.length) {
                if (boxed[index] == null) {
                    boxed[index] = index;
                }
                segments[depth++] = boxed[index];
            } else {
                segments[depth++] = index;
            }
        }

        void pop() {
            depth--;
        }

        RulesPath path() {
            return RulesPath.of(java.util.Arrays.copyOf(segments, depth));
        }

        RulesPath path(Object key) {
            Object[] s = java.util.Arrays.copyOf(segments, depth + 1);
            s[depth] = key;
            return RulesPath.of(s);
        }

        RulesPath parentPath() {
            return RulesPath.of(java.util.Arrays.copyOf(segments, depth - 1));
        }
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private void push(EditorContext ctx, Command c, boolean merge) {
        ctx.undo().push(c, merge);
    }

    private TextBuffer buffer() {
        return poolCursor < pool.length ? pool[poolCursor++] : overflow;
    }

    /** Rebuilds the id drop-down options and the validation report when the content changed. */
    private void refreshCaches(RulesSource rules) {
        if (rules.version() == idOptionsVersion) {
            return;
        }
        idOptionsVersion = rules.version();
        idOptions.clear();
        for (Kind k : kinds) {
            List<Object> rows = rules.rows(k.name());
            CharSequence[] options = new CharSequence[rows.size() + 1];
            options[0] = NO_IDS[0];
            for (int i = 0; i < rows.size(); i++) {
                options[i + 1] = rules.id(k.name(), i);
            }
            idOptions.put(k.name(), options);
        }
        errors.clear();
        rules.validate(errors);
    }

    private CharSequence[] idOptions(String kind) {
        CharSequence[] o = idOptions.get(kind);
        return o == null ? NO_IDS : o;
    }

    private CharSequence errorsLabel() {
        scratch.setLength(0);
        scratch.append(errors.size()).append(errors.size() == 1 ? " problem" : " problems");
        return scratch;
    }

    private CharSequence rowLabel(Kind nested, int i, Map<String, Object> row) {
        scratch.setLength(0);
        scratch.append(nested.name()).append('[').append(i).append(']');
        Object name = row.get("name");
        if (name instanceof String s && !s.isEmpty()) {
            scratch.append(' ').append(s);
        } else if (row.get("level") instanceof Number n) {
            scratch.append(" level ").append(n.intValue());
        }
        return scratch;
    }

    private static boolean hasFlag(Object list, String name) {
        if (list instanceof List<?> l) {
            for (Object o : l) {
                if (name.equals(o)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<Object> parseInts(CharSequence text) {
        List<Object> out = new ArrayList<>();
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '-' || Character.isDigit(c)) {
                int start = i++;
                while (i < n && Character.isDigit(text.charAt(i))) {
                    i++;
                }
                try {
                    out.add((double) Integer.parseInt(text.subSequence(start, i).toString()));
                } catch (NumberFormatException e) {
                    // a lone minus: skip it
                }
            } else {
                i++;
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
