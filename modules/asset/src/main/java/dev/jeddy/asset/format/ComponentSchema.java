package dev.jeddy.asset.format;

import dev.jeddy.core.json.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A game's component schema (DESIGN §22, D109): what the component processor wrote to {@code
 * META-INF/jeddy/components.json} — every component with its fields, types, defaults and editor
 * hints, and every behaviour with its phase and access. The editor's inspector, the cooker's level
 * validation and the tools read it; the runtime never does (its stores are generated).
 *
 * <p>A field is a record component; a {@code vec2} or {@code vec3} field is two or three scalar
 * <em>columns</em> ({@code name.x}, {@code name.y}, {@code name.z}) in the store and the level
 * file, which {@link Field#columns()} lists; every other field is one column of its own name.
 *
 * <p>Thread ownership: immutable; any thread.
 */
public final class ComponentSchema {
    /** The schema file's path inside a game's class output or jar. */
    public static final String RESOURCE = "META-INF/jeddy/components.json";

    /** Field types, as the JSON names them. */
    public enum Type {
        INT("int"),
        LONG("long"),
        FLOAT("float"),
        BOOL("bool"),
        ENUM("enum"),
        VEC2("vec2"),
        VEC3("vec3"),
        ENTITY("entity"),
        ASSET("asset");

        public final String json;

        Type(String json) {
            this.json = json;
        }

        /** The type named {@code json}. */
        public static Type of(String json) {
            for (Type t : values()) {
                if (t.json.equals(json)) {
                    return t;
                }
            }
            throw new IllegalArgumentException("unknown component field type " + json);
        }

        /** Scalar columns a field of this type takes. */
        public int columns() {
            return this == VEC2 ? 2 : this == VEC3 ? 3 : 1;
        }

        /** Whether the columns are {@code float}s (else {@code int}s, or a {@code long}/bool). */
        public boolean isFloat() {
            return this == FLOAT || this == VEC2 || this == VEC3;
        }
    }

    /**
     * One record component.
     *
     * @param name the record component's name
     * @param type its type
     * @param doc a one-line description, or ""
     * @param enumValues the constants of an {@link Type#ENUM}, else empty
     * @param assetKind the asset kind of an {@link Type#ASSET}, else ""
     * @param defaults the default per column (an enum's default as its ordinal, a bool as 0/1)
     * @param min the {@code @Range} minimum, or {@code NaN}
     * @param max the {@code @Range} maximum, or {@code NaN}
     * @param angle whether the float is an angle in radians
     * @param color whether the vec3 is a linear RGB color
     */
    public record Field(
            String name,
            Type type,
            String doc,
            List<String> enumValues,
            String assetKind,
            double[] defaults,
            double min,
            double max,
            boolean angle,
            boolean color) {
        /** The column names the field expands to. */
        public List<String> columns() {
            return switch (type) {
                case VEC2 -> List.of(name + ".x", name + ".y");
                case VEC3 -> List.of(name + ".x", name + ".y", name + ".z");
                default -> List.of(name);
            };
        }

        /** Whether the field has a {@code @Range}. */
        public boolean hasRange() {
            return !Double.isNaN(min) && !Double.isNaN(max);
        }
    }

    /**
     * One component.
     *
     * @param name the record's simple name (unique in a game)
     * @param type the record's binary name
     * @param doc a one-line description, or ""
     * @param fields the record components in declaration order
     */
    public record Component(String name, String type, String doc, List<Field> fields) {
        /** The field named {@code fieldName}, or {@code null}. */
        public Field field(String fieldName) {
            for (Field f : fields) {
                if (f.name.equals(fieldName)) {
                    return f;
                }
            }
            return null;
        }

        /** The field whose column {@code column} is, or {@code null}. */
        public Field fieldOfColumn(String column) {
            for (Field f : fields) {
                if (f.columns().contains(column)) {
                    return f;
                }
            }
            return null;
        }
    }

    /**
     * One behaviour method.
     *
     * @param name {@code Class.method}
     * @param phase the phase's name ({@code fixedUpdate}, {@code spawn}, ...)
     * @param parallel whether it runs on the job system
     * @param order its declared order
     * @param reads components it takes as views
     * @param writes components it takes as muts
     * @param optional components it takes as optional
     * @param without components that exclude an entity
     */
    public record Behaviour(
            String name,
            String phase,
            boolean parallel,
            int order,
            List<String> reads,
            List<String> writes,
            List<String> optional,
            List<String> without) {}

    private final String registry;
    private final List<Component> components;
    private final List<Behaviour> behaviours;

    private ComponentSchema(
            String registry, List<Component> components, List<Behaviour> behaviours) {
        this.registry = registry;
        this.components = components;
        this.behaviours = behaviours;
    }

    /** An empty schema (a game without components). */
    public static final ComponentSchema EMPTY = new ComponentSchema("", List.of(), List.of());

    /** The registry class's binary name. */
    public String registry() {
        return registry;
    }

    public List<Component> components() {
        return components;
    }

    public List<Behaviour> behaviours() {
        return behaviours;
    }

    /** The component named {@code name}, or {@code null}. */
    public Component component(String name) {
        for (Component c : components) {
            if (c.name.equals(name)) {
                return c;
            }
        }
        return null;
    }

    /**
     * Parses a schema.
     *
     * @throws IllegalArgumentException if the JSON is not a schema
     */
    public static ComponentSchema parse(byte[] json) {
        return parse(Json.asObject(Json.parse(json), "components.json"));
    }

    /** Parses a schema from text. */
    public static ComponentSchema parse(String json) {
        return parse(Json.asObject(Json.parse(json), "components.json"));
    }

    private static ComponentSchema parse(Map<String, Object> root) {
        int version = Json.integer(root, "version", 0);
        if (version != 1) {
            throw new IllegalArgumentException("components.json version " + version);
        }
        List<Component> components = new ArrayList<>();
        for (Object o : Json.asArray(root.getOrDefault("components", List.of()), "components")) {
            Map<String, Object> c = Json.asObject(o, "component");
            String name = Json.string(c, "name", "");
            List<Field> fields = new ArrayList<>();
            for (Object fo : Json.asArray(c.getOrDefault("fields", List.of()), name + ".fields")) {
                fields.add(field(Json.asObject(fo, name + " field")));
            }
            components.add(
                    new Component(
                            name,
                            Json.string(c, "type", ""),
                            Json.string(c, "doc", ""),
                            Collections.unmodifiableList(fields)));
        }
        List<Behaviour> behaviours = new ArrayList<>();
        for (Object o : Json.asArray(root.getOrDefault("behaviours", List.of()), "behaviours")) {
            Map<String, Object> b = Json.asObject(o, "behaviour");
            behaviours.add(
                    new Behaviour(
                            Json.string(b, "name", ""),
                            Json.string(b, "phase", ""),
                            Json.bool(b, "parallel", false),
                            Json.integer(b, "order", 0),
                            strings(b, "reads"),
                            strings(b, "writes"),
                            strings(b, "optional"),
                            strings(b, "without")));
        }
        return new ComponentSchema(
                Json.string(root, "registry", ""),
                Collections.unmodifiableList(components),
                Collections.unmodifiableList(behaviours));
    }

    private static Field field(Map<String, Object> f) {
        String name = Json.string(f, "name", "");
        Type type = Type.of(Json.string(f, "type", "int"));
        List<String> values = strings(f, "values");
        double[] defaults = new double[type.columns()];
        Object d = f.get("default");
        if (d instanceof List<?> list) {
            for (int i = 0; i < defaults.length && i < list.size(); i++) {
                defaults[i] = ((Number) list.get(i)).doubleValue();
            }
        } else if (d instanceof Number n) {
            defaults[0] = n.doubleValue();
        } else if (d instanceof Boolean b) {
            defaults[0] = b ? 1 : 0;
        } else if (d instanceof String s && type == Type.ENUM) {
            defaults[0] = Math.max(0, values.indexOf(s));
        }
        return new Field(
                name,
                type,
                Json.string(f, "doc", ""),
                values,
                Json.string(f, "asset", ""),
                defaults,
                Json.number(f, "min", Double.NaN),
                Json.number(f, "max", Double.NaN),
                Json.bool(f, "angle", false),
                Json.bool(f, "color", false));
    }

    private static List<String> strings(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        if (v == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object o : Json.asArray(v, key)) {
            out.add(String.valueOf(o));
        }
        return Collections.unmodifiableList(out);
    }
}
