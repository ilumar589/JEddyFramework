package dev.jeddy.editor.rules;

import dev.jeddy.asset.format.RulesSchema;
import dev.jeddy.asset.format.RulesSchema.Field;
import dev.jeddy.asset.format.RulesSchema.Kind;
import dev.jeddy.core.json.Json;
import dev.jeddy.math.Dice;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rules content as the editor edits it (DESIGN §20, D97): the JSON files of {@code
 * assets/rules} parsed into their value trees ({@code Map} objects, {@code List} arrays, {@code
 * String}, {@code Double}, {@code Boolean}), one list of definitions per top-level kind. The editor
 * never touches the cooked {@code .jrules}: it edits these trees through {@link RulesPath}s, writes
 * them back as JSON on {@link #save}, and the cooker's watcher re-cooks the binary, which the game
 * hot-reloads. The same values the importer reads are what the panel shows, so what the editor
 * cannot express, the content cannot contain.
 *
 * <p>{@link #version} increments on every change so the panel knows when to refresh its caches;
 * {@link #validate} reports what the importer would reject (unknown fields, bad enum names,
 * dangling references, malformed dice), with the same messages.
 *
 * <p>Thread ownership: the game thread. Cold: every method may allocate.
 */
public final class RulesSource {
    private final Map<String, List<Object>> tables = new LinkedHashMap<>();
    private Path directory;
    private int version;
    private long savedFingerprint;
    private long fingerprint;
    private int fingerprintVersion = -1;

    /** Creates an empty source with an empty table per top-level kind. */
    public RulesSource() {
        for (Kind k : RulesSchema.KINDS) {
            if (k.hasId()) {
                tables.put(k.name(), new ArrayList<>());
            }
        }
    }

    /** Reads every {@code <kind>.json} present in {@code dir} (missing files are empty kinds). */
    public static RulesSource load(Path dir) throws IOException {
        RulesSource s = new RulesSource();
        s.directory = dir;
        for (Kind k : RulesSchema.KINDS) {
            if (!k.hasId()) {
                continue;
            }
            Path f = dir.resolve(k.name() + ".json");
            if (Files.isRegularFile(f)) {
                List<Object> rows = Json.asArray(Json.parse(Files.readAllBytes(f)), k.name());
                for (int i = 0; i < rows.size(); i++) {
                    Json.asObject(rows.get(i), k.name() + "[" + i + "]");
                }
                s.tables.put(k.name(), new ArrayList<>(rows));
            }
        }
        s.savedFingerprint = s.fingerprint();
        return s;
    }

    /** Writes every kind's file into {@link #directory} (or {@code dir} when given). */
    public void save(Path dir) throws IOException {
        if (dir != null) {
            directory = dir;
        }
        if (directory == null) {
            throw new IOException("no rules directory");
        }
        Files.createDirectories(directory);
        for (Map.Entry<String, List<Object>> e : tables.entrySet()) {
            Path f = directory.resolve(e.getKey() + ".json");
            byte[] bytes = write(e.getValue()).getBytes(StandardCharsets.UTF_8);
            Path tmp = f.resolveSibling(e.getKey() + ".json.tmp");
            Files.write(tmp, bytes);
            Files.move(
                    tmp,
                    f,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }
        savedFingerprint = fingerprint();
    }

    /** The directory the files live in, or {@code null}. */
    public Path directory() {
        return directory;
    }

    /** Increments on every change. */
    public int version() {
        return version;
    }

    /**
     * Returns whether the content differs from what was loaded or last saved: a fingerprint of the
     * files it would write, so undoing back to the saved state is clean again. Recomputed only when
     * the version changed.
     */
    public boolean isDirty() {
        return fingerprint() != savedFingerprint;
    }

    private long fingerprint() {
        if (fingerprintVersion != version) {
            long h = 1469598103934665603L;
            for (Map.Entry<String, List<Object>> e : tables.entrySet()) {
                h = (h ^ write(e.getValue()).hashCode()) * 1099511628211L;
                h = (h ^ e.getKey().hashCode()) * 1099511628211L;
            }
            fingerprint = h;
            fingerprintVersion = version;
        }
        return fingerprint;
    }

    /** The rows of top-level {@code kind}. */
    public List<Object> rows(String kind) {
        List<Object> l = tables.get(kind);
        if (l == null) {
            throw new IllegalArgumentException("no kind " + kind);
        }
        return l;
    }

    /** The {@code i}th definition of {@code kind}. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> row(String kind, int i) {
        return (Map<String, Object>) rows(kind).get(i);
    }

    /** The {@code id} of the {@code i}th definition of {@code kind}, or "" when missing. */
    public String id(String kind, int i) {
        Object id = row(kind, i).get("id");
        return id instanceof String s ? s : "";
    }

    /** The index of the definition of {@code kind} with {@code id}, or -1. */
    public int find(String kind, String id) {
        List<Object> rows = rows(kind);
        for (int i = 0; i < rows.size(); i++) {
            if (id.equals(id(kind, i))) {
                return i;
            }
        }
        return -1;
    }

    // ---- Paths --------------------------------------------------------------------------------

    /** The value at {@code path}, or {@code null} when absent. */
    public Object get(RulesPath path) {
        Object node = tables;
        for (int i = 0; i < path.length(); i++) {
            node = step(node, path.segment(i));
            if (node == null) {
                return null;
            }
        }
        return node;
    }

    /** The list at {@code path}, created empty in its parent object when absent. */
    @SuppressWarnings("unchecked")
    public List<Object> list(RulesPath path) {
        Object v = get(path);
        if (v instanceof List<?> l) {
            return (List<Object>) l;
        }
        if (v != null) {
            throw new IllegalArgumentException(path + " is not a list");
        }
        Object parent = get(path.parent());
        if (!(parent instanceof Map<?, ?>) || !(path.last() instanceof String key)) {
            throw new IllegalArgumentException(path + " has no object to hold a list");
        }
        List<Object> l = new ArrayList<>();
        ((Map<String, Object>) parent).put(key, l);
        return l;
    }

    /**
     * Sets the value at {@code path} ({@code null} removes the key from its object: the importer's
     * default applies); the parent must exist. Returns the previous value.
     */
    @SuppressWarnings("unchecked")
    public Object set(RulesPath path, Object value) {
        Object parent = get(path.parent());
        Object before;
        if (parent instanceof Map<?, ?> m && path.last() instanceof String key) {
            Map<String, Object> map = (Map<String, Object>) m;
            before = value == null ? map.remove(key) : map.put(key, value);
        } else if (parent instanceof List<?> l && path.last() instanceof Integer i) {
            before = ((List<Object>) l).set(i, value);
        } else {
            throw new IllegalArgumentException("nothing to set at " + path);
        }
        version++;
        return before;
    }

    /** Inserts {@code element} at {@code index} of the list at {@code listPath}. */
    public void insert(RulesPath listPath, int index, Object element) {
        list(listPath).add(index, element);
        version++;
    }

    /** Removes and returns the element at {@code index} of the list at {@code listPath}. */
    public Object remove(RulesPath listPath, int index) {
        Object e = list(listPath).remove(index);
        version++;
        return e;
    }

    /** Moves the element at {@code from} of the list at {@code listPath} to {@code to}. */
    public void move(RulesPath listPath, int from, int to) {
        List<Object> l = list(listPath);
        Object e = l.remove(from);
        l.add(to, e);
        version++;
    }

    private static Object step(Object node, Object segment) {
        if (node instanceof Map<?, ?> m && segment instanceof String key) {
            return m.get(key);
        }
        if (node instanceof List<?> l && segment instanceof Integer i) {
            return i >= 0 && i < l.size() ? l.get(i) : null;
        }
        return null;
    }

    /** A deep copy of a value tree (new maps and lists; scalars are immutable). */
    @SuppressWarnings("unchecked")
    public static Object copy(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put((String) e.getKey(), copy(e.getValue()));
            }
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object o : l) {
                out.add(copy(o));
            }
            return out;
        }
        return v;
    }

    /**
     * A fresh definition of {@code kind} with {@code id}, its name, and nothing else (defaults).
     */
    public static Map<String, Object> newDefinition(Kind kind, String id) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (kind.hasId()) {
            m.put("id", id);
            if (kind.field("name") >= 0) {
                m.put("name", id);
            }
        }
        return m;
    }

    /** A fresh operation: {@code modify} of a stat. */
    public static Map<String, Object> newOperation() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", "modify");
        m.put("stat", "ac");
        m.put("type", "untyped");
        m.put("value", 1.0);
        return m;
    }

    /** An id not yet used in {@code kind}: {@code base}, {@code base2}, {@code base3}... */
    public String freeId(String kind, String base) {
        if (find(kind, base) < 0) {
            return base;
        }
        for (int n = 2; ; n++) {
            String id = base + n;
            if (find(kind, id) < 0) {
                return id;
            }
        }
    }

    // ---- Validation ---------------------------------------------------------------------------

    /**
     * Appends to {@code out} every problem the importer would report, as {@code kind 'id'.field:
     * message}; returns how many were added.
     */
    public int validate(List<String> out) {
        int before = out.size();
        Map<String, Set<String>> ids = new HashMap<>();
        for (Kind k : RulesSchema.KINDS) {
            if (!k.hasId()) {
                continue;
            }
            Set<String> set = new HashSet<>();
            List<Object> rows = rows(k.name());
            for (int i = 0; i < rows.size(); i++) {
                Object id = ((Map<?, ?>) rows.get(i)).get("id");
                if (!(id instanceof String s) || s.isEmpty()) {
                    out.add(k.name() + " entry " + i + " has no id");
                } else if (!set.add(s)) {
                    out.add(k.name() + " has two entries with id " + s);
                }
            }
            ids.put(k.name(), set);
        }
        for (Kind k : RulesSchema.KINDS) {
            if (!k.hasId()) {
                continue;
            }
            List<Object> rows = rows(k.name());
            for (int i = 0; i < rows.size(); i++) {
                Map<?, ?> row = (Map<?, ?>) rows.get(i);
                validateRow(out, ids, k, row, k.name() + " '" + row.get("id") + "'");
            }
        }
        return out.size() - before;
    }

    private static void validateRow(
            List<String> out, Map<String, Set<String>> ids, Kind k, Map<?, ?> row, String where) {
        for (Object key : row.keySet()) {
            if (k.field((String) key) < 0) {
                out.add(where + ": unknown field '" + key + "'");
            }
        }
        for (Field f : k.fields()) {
            Object v = row.get(f.name());
            if (v == null) {
                continue;
            }
            String at = where + "." + f.name();
            switch (f.type()) {
                case INT -> {
                    if (!(v instanceof Number)) {
                        out.add(at + ": expected a number");
                    }
                }
                case BOOL -> {
                    if (!(v instanceof Boolean)) {
                        out.add(at + ": expected true or false");
                    }
                }
                case STRING -> {
                    if (!(v instanceof String)) {
                        out.add(at + ": expected a string");
                    }
                }
                case ENUM -> checkEnum(out, f, v, at);
                case FLAGS -> {
                    if (v instanceof List<?> l) {
                        for (Object o : l) {
                            checkEnum(out, f, o, at);
                        }
                    } else {
                        out.add(at + ": expected a list of flag names");
                    }
                }
                case DICE -> checkDice(out, v, at);
                case REF -> checkRef(out, ids, f.refKind(), v, at);
                case REF_LIST -> {
                    if (v instanceof List<?> l) {
                        for (Object o : l) {
                            checkRef(out, ids, f.refKind(), o, at);
                        }
                    } else {
                        out.add(at + ": expected a list of ids");
                    }
                }
                case INT_LIST -> {
                    if (v instanceof List<?> l) {
                        for (Object o : l) {
                            if (!(o instanceof Number)) {
                                out.add(at + ": expected numbers");
                                break;
                            }
                        }
                    } else {
                        out.add(at + ": expected a list of numbers");
                    }
                }
                case OPS -> {
                    if (v instanceof List<?> l) {
                        for (int i = 0; i < l.size(); i++) {
                            if (l.get(i) instanceof Map<?, ?> op) {
                                validateOp(out, ids, op, at + "[" + i + "]");
                            } else {
                                out.add(at + "[" + i + "]: expected an operation object");
                            }
                        }
                    } else {
                        out.add(at + ": expected a list of operations");
                    }
                }
                case NESTED -> {
                    Kind nested = RulesSchema.kind(f.refKind());
                    if (v instanceof List<?> l) {
                        for (int i = 0; i < l.size(); i++) {
                            if (l.get(i) instanceof Map<?, ?> m) {
                                validateRow(out, ids, nested, m, at + "[" + i + "]");
                            } else {
                                out.add(at + "[" + i + "]: expected an object");
                            }
                        }
                    } else {
                        out.add(at + ": expected a list");
                    }
                }
            }
        }
    }

    private static void validateOp(
            List<String> out, Map<String, Set<String>> ids, Map<?, ?> op, String at) {
        for (Object key : op.keySet()) {
            boolean known = false;
            for (Field f : RulesSchema.OP_FIELDS) {
                known |= f.name().equals(key);
            }
            if (!known) {
                out.add(at + ": unknown operation field '" + key + "'");
            }
        }
        for (Field f : RulesSchema.OP_FIELDS) {
            Object v = op.get(f.name());
            if (v == null) {
                continue;
            }
            switch (f.type()) {
                case ENUM -> checkEnum(out, f, v, at + "." + f.name());
                case DICE -> checkDice(out, v, at + "." + f.name());
                case INT -> {
                    if (!(v instanceof Number)) {
                        out.add(at + "." + f.name() + ": expected a number");
                    }
                }
                default -> {}
            }
        }
        String refKind = opRefKind(op.get("op"));
        if (refKind != null) {
            Object ref = op.get("ref");
            if (ref == null) {
                out.add(at + ": " + op.get("op") + " needs a ref");
            } else {
                checkRef(out, ids, refKind, ref, at + ".ref");
            }
        }
    }

    /** The kind an operation's {@code ref} names, or {@code null} when it has none. */
    public static String opRefKind(Object opName) {
        if (!(opName instanceof String s)) {
            return null;
        }
        return switch (s) {
            case "condition", "removeCondition" -> "conditions";
            case "grantFeat" -> "feats";
            default -> null;
        };
    }

    private static void checkEnum(List<String> out, Field f, Object v, String at) {
        if (!(v instanceof String s) || f.enumIndex(s) < 0) {
            out.add(at + ": '" + v + "' is not one of " + String.join(", ", f.names()));
        }
    }

    private static void checkDice(List<String> out, Object v, String at) {
        if (v instanceof String s) {
            try {
                Dice.parse(s);
            } catch (IllegalArgumentException e) {
                out.add(at + ": bad dice '" + s + "'");
            }
        } else {
            out.add(at + ": expected dice like 2d6+1");
        }
    }

    private static void checkRef(
            List<String> out, Map<String, Set<String>> ids, String kind, Object v, String at) {
        if (!(v instanceof String s)) {
            out.add(at + ": expected an id of " + kind);
        } else if (!ids.getOrDefault(kind, Set.of()).contains(s)) {
            out.add(at + ": no " + kind + " with id '" + s + "'");
        }
    }

    // ---- Writing ------------------------------------------------------------------------------

    /**
     * Writes a table the way the files are kept by hand: one definition per entry starting on its
     * own line, scalars and scalar lists inline, lists of objects (effects, attacks, features) one
     * object per line beneath their key.
     */
    static String write(List<Object> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("[\n");
        for (int i = 0; i < rows.size(); i++) {
            sb.append("  ");
            writeValue(sb, rows.get(i), 2);
            sb.append(i + 1 < rows.size() ? ",\n" : "\n");
        }
        sb.append("]\n");
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object v, int indent) {
        if (v instanceof Map<?, ?> m) {
            sb.append('{');
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (i++ > 0) {
                    sb.append(", ");
                }
                sb.append(Json.write(String.valueOf(e.getKey())).trim()).append(": ");
                writeValue(sb, e.getValue(), indent);
            }
            sb.append('}');
        } else if (v instanceof List<?> l) {
            boolean objects = false;
            for (Object o : l) {
                objects |= o instanceof Map<?, ?> || o instanceof List<?>;
            }
            if (l.isEmpty() || !objects) {
                sb.append('[');
                for (int i = 0; i < l.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    writeValue(sb, l.get(i), indent);
                }
                sb.append(']');
            } else {
                sb.append("[\n");
                for (int i = 0; i < l.size(); i++) {
                    sb.append(" ".repeat(indent + 3));
                    writeValue(sb, l.get(i), indent + 3);
                    sb.append(i + 1 < l.size() ? ",\n" : "]");
                }
            }
        } else {
            sb.append(Json.write(v).trim());
        }
    }
}
