package dev.jeddy.cooker.rules;

import dev.jeddy.asset.format.RulesData;
import dev.jeddy.asset.format.RulesSchema;
import dev.jeddy.asset.format.RulesSchema.Field;
import dev.jeddy.asset.format.RulesSchema.Kind;
import dev.jeddy.cooker.CookContext;
import dev.jeddy.cooker.ImportSettings;
import dev.jeddy.cooker.Importer;
import dev.jeddy.core.json.Json;
import dev.jeddy.math.Dice;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cooks a rules set (DESIGN §20, D97): the source is a manifest {@code rules/<set>.rules.json} next
 * to one JSON file per top-level kind ({@code conditions.json}, {@code classes.json}, ..., each an
 * array of objects), which the importer claims and reads as dependencies, validates against {@link
 * RulesSchema} (unknown fields, bad enum names, unparsable dice, references to unknown ids and
 * prerequisites are errors that name the file, the definition and the field), and writes {@code
 * rules/<set>.jrules} through {@link RulesData.Builder}.
 *
 * <p>Ids are resolved in two passes (rows first, then fields), so definitions may refer to each
 * other in any order and across kinds. Nested kinds (class slots and features, monster attacks) are
 * arrays inside their parent object and become contiguous rows of their own table.
 *
 * <p>Thread ownership: cooker workers.
 */
public final class RulesImporter implements Importer {
    private static final String SUFFIX = ".rules.json";

    @Override
    public String name() {
        return "rules";
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public boolean accepts(String path, ImportSettings settings) {
        return path.toLowerCase(Locale.ROOT).endsWith(SUFFIX);
    }

    @Override
    public List<String> claims(Path root, String path, ImportSettings settings) {
        List<String> out = new ArrayList<>();
        String dir = path.contains("/") ? path.substring(0, path.lastIndexOf('/') + 1) : "";
        for (Kind k : RulesSchema.KINDS) {
            if (k.hasId() && Files.isRegularFile(root.resolve(dir + k.name() + ".json"))) {
                out.add(dir + k.name() + ".json");
            }
        }
        return out;
    }

    @Override
    public void cook(CookContext ctx) throws Exception {
        Path dir = ctx.file().getParent();
        Json.asObject(Json.parse(ctx.bytes()), ctx.path()); // the manifest must be an object
        Map<String, List<Map<String, Object>>> sources = new HashMap<>();
        for (Kind k : RulesSchema.KINDS) {
            if (!k.hasId()) {
                continue;
            }
            Path file = dir.resolve(k.name() + ".json");
            List<Map<String, Object>> rows = new ArrayList<>();
            if (Files.isRegularFile(file)) {
                Object parsed = Json.parse(ctx.readDependency(file));
                for (Object o : Json.asArray(parsed, file.getFileName().toString())) {
                    rows.add(Json.asObject(o, file.getFileName() + " entry"));
                }
            }
            sources.put(k.name(), rows);
        }
        RulesData data = build(sources);
        String base = ctx.outputBase();
        if (base.endsWith(".rules")) {
            base = base.substring(0, base.length() - ".rules".length());
        }
        ctx.output(base + ".jrules", data.toBytes());
        for (Kind k : RulesSchema.KINDS) {
            ctx.stat(k.name(), data.rows(k));
        }
        ctx.stat("ops", data.opCount());
    }

    /**
     * Converts parsed JSON rows per top-level kind into a {@link RulesData}; the entry point the
     * editor and the tests share with {@link #cook}.
     *
     * @throws IllegalArgumentException naming the kind, id and field of the first problem
     */
    public static RulesData build(Map<String, List<Map<String, Object>>> sources) {
        RulesData.Builder b = new RulesData.Builder();
        Map<String, Map<String, Integer>> ids = new HashMap<>();
        // Pass 1: rows and ids of every top-level kind.
        for (Kind k : RulesSchema.KINDS) {
            if (!k.hasId()) {
                continue;
            }
            Map<String, Integer> index = new HashMap<>();
            List<Map<String, Object>> rows = sources.getOrDefault(k.name(), List.of());
            for (int i = 0; i < rows.size(); i++) {
                Object id = rows.get(i).get("id");
                if (!(id instanceof String s) || s.isEmpty()) {
                    throw new IllegalArgumentException(k.name() + " entry " + i + " has no id");
                }
                if (index.put(s, b.addRow(k)) != null) {
                    throw new IllegalArgumentException(k.name() + " has two entries with id " + s);
                }
            }
            ids.put(k.name(), index);
        }
        // Pass 2: fields, nested rows and operations.
        for (Kind k : RulesSchema.KINDS) {
            if (!k.hasId()) {
                continue;
            }
            List<Map<String, Object>> rows = sources.getOrDefault(k.name(), List.of());
            for (int r = 0; r < rows.size(); r++) {
                fill(b, ids, k, r, rows.get(r), k.name() + " '" + rows.get(r).get("id") + "'");
            }
        }
        return b.build();
    }

    private static void fill(
            RulesData.Builder b,
            Map<String, Map<String, Integer>> ids,
            Kind k,
            int row,
            Map<String, Object> obj,
            String where) {
        for (String key : obj.keySet()) {
            if (k.field(key) < 0) {
                throw new IllegalArgumentException(where + ": unknown field '" + key + "'");
            }
        }
        for (int f = 0; f < k.fields().size(); f++) {
            Field field = k.fields().get(f);
            Object v = obj.get(field.name());
            String at = where + "." + field.name();
            switch (field.type()) {
                case INT -> b.setInt(k, row, f, v == null ? field.defaultInt() : integer(v, at));
                case BOOL ->
                        b.setInt(k, row, f, v == null ? field.defaultInt() : bool(v, at) ? 1 : 0);
                case STRING ->
                        b.setString(k, row, f, v == null ? field.defaultString() : string(v, at));
                case ENUM ->
                        b.setInt(
                                k,
                                row,
                                f,
                                v == null ? field.defaultInt() : enumValue(field, v, at));
                case FLAGS -> {
                    int bits = 0;
                    if (v != null) {
                        for (Object o : Json.asArray(v, at)) {
                            bits |= 1 << enumValue(field, o, at);
                        }
                    }
                    b.setInt(k, row, f, bits);
                }
                case DICE -> b.setInt(k, row, f, v == null ? 0 : dice(v, at));
                case REF -> b.setInt(k, row, f, v == null ? -1 : ref(ids, field.refKind(), v, at));
                case REF_LIST -> {
                    List<Object> list = v == null ? List.of() : Json.asArray(v, at);
                    int[] values = new int[list.size()];
                    for (int i = 0; i < values.length; i++) {
                        values[i] = ref(ids, field.refKind(), list.get(i), at);
                    }
                    b.setList(k, row, f, values, values.length);
                }
                case INT_LIST -> {
                    List<Object> list = v == null ? List.of() : Json.asArray(v, at);
                    int[] values = new int[list.size()];
                    for (int i = 0; i < values.length; i++) {
                        values[i] = integer(list.get(i), at);
                    }
                    b.setList(k, row, f, values, values.length);
                }
                case OPS -> {
                    List<Object> list = v == null ? List.of() : Json.asArray(v, at);
                    int first = b.opCount();
                    for (int i = 0; i < list.size(); i++) {
                        op(
                                b,
                                ids,
                                Json.asObject(list.get(i), at + "[" + i + "]"),
                                at + "[" + i + "]");
                    }
                    b.setOps(k, row, f, first, list.size());
                }
                case NESTED -> {
                    Kind nested = RulesSchema.kind(field.refKind());
                    List<Object> list = v == null ? List.of() : Json.asArray(v, at);
                    int start = b.rows(nested);
                    for (int i = 0; i < list.size(); i++) {
                        int nr = b.addRow(nested);
                        fill(
                                b,
                                ids,
                                nested,
                                nr,
                                Json.asObject(list.get(i), at + "[" + i + "]"),
                                at + "[" + i + "]");
                    }
                    b.setRange(k, row, f, start, list.size());
                }
            }
        }
    }

    private static void op(
            RulesData.Builder b,
            Map<String, Map<String, Integer>> ids,
            Map<String, Object> obj,
            String at) {
        for (String key : obj.keySet()) {
            boolean known = false;
            for (Field f : RulesSchema.OP_FIELDS) {
                known |= f.name().equals(key);
            }
            if (!known) {
                throw new IllegalArgumentException(at + ": unknown operation field '" + key + "'");
            }
        }
        int[] v = new int[RulesSchema.OP_INTS];
        for (int i = 0; i < RulesSchema.OP_FIELDS.size(); i++) {
            Field f = RulesSchema.OP_FIELDS.get(i);
            Object o = obj.get(f.name());
            v[i] =
                    switch (f.type()) {
                        case ENUM ->
                                o == null ? f.defaultInt() : enumValue(f, o, at + "." + f.name());
                        case DICE -> o == null ? 0 : dice(o, at + "." + f.name());
                        case STRING -> -1; // the ref, resolved below
                        default -> o == null ? f.defaultInt() : integer(o, at + "." + f.name());
                    };
        }
        String opName = RulesSchema.OPS[v[RulesSchema.OP_OP]];
        Object ref = obj.get("ref");
        String refKind =
                switch (opName) {
                    case "condition", "removeCondition" -> "conditions";
                    case "grantFeat" -> "feats";
                    default -> null;
                };
        if (refKind != null) {
            if (ref == null) {
                throw new IllegalArgumentException(at + ": " + opName + " needs a ref");
            }
            Kind target = RulesSchema.kind(refKind);
            v[RulesSchema.OP_REF] = target.index() << 24 | ref(ids, refKind, ref, at + ".ref");
        } else if (ref != null) {
            throw new IllegalArgumentException(at + ": " + opName + " takes no ref");
        }
        if (opName.equals("modify") && obj.get("stat") == null) {
            throw new IllegalArgumentException(at + ": modify needs a stat");
        }
        if ((opName.equals("damage") || opName.equals("heal") || opName.equals("tempHp"))
                && v[RulesSchema.OP_DICE] == 0) {
            throw new IllegalArgumentException(at + ": " + opName + " needs dice");
        }
        b.addOp(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7]);
    }

    private static int integer(Object v, String at) {
        if (v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
            return n.intValue();
        }
        throw new IllegalArgumentException(at + ": expected an integer, got " + v);
    }

    private static boolean bool(Object v, String at) {
        if (v instanceof Boolean b) {
            return b;
        }
        throw new IllegalArgumentException(at + ": expected true or false, got " + v);
    }

    private static String string(Object v, String at) {
        if (v instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException(at + ": expected a string, got " + v);
    }

    private static int enumValue(Field f, Object v, String at) {
        String s = string(v, at);
        int i = f.enumIndex(s);
        if (i < 0) {
            throw new IllegalArgumentException(
                    at + ": '" + s + "' is not one of " + String.join(", ", f.names()));
        }
        return i;
    }

    private static int dice(Object v, String at) {
        try {
            return Dice.parse(string(v, at));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(at + ": " + e.getMessage());
        }
    }

    private static int ref(
            Map<String, Map<String, Integer>> ids, String kind, Object v, String at) {
        String s = string(v, at);
        Integer row = ids.getOrDefault(kind, Map.of()).get(s);
        if (row == null) {
            throw new IllegalArgumentException(at + ": no " + kind + " entry with id '" + s + "'");
        }
        return row;
    }
}
