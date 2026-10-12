package dev.jeddy.components.processor;

import java.util.ArrayList;
import java.util.List;

/**
 * What the processor collected from one compilation (DESIGN §22): components, behaviours and the
 * registry. Plain data; the generators read it.
 *
 * <p>Thread ownership: the compiler's processing thread.
 */
final class Model {
    /** Field kinds (the scalar expansion is in {@link Field#columns()}). */
    enum Kind {
        INT("int", "int", "INT"),
        LONG("long", "long", "LONG"),
        FLOAT("float", "float", "FLOAT"),
        BOOL("bool", "boolean", "BOOL"),
        ENUM("enum", "int", "INT"),
        VEC2("vec2", "float", "FLOAT"),
        VEC3("vec3", "float", "FLOAT"),
        ENTITY("entity", "int", "INT"),
        ASSET("asset", "int", "INT");

        /** The schema's name. */
        final String json;

        /** The column array's element type. */
        final String columnJava;

        /** The {@code Store} column type constant. */
        final String storeType;

        Kind(String json, String columnJava, String storeType) {
            this.json = json;
            this.columnJava = columnJava;
            this.storeType = storeType;
        }

        int columns() {
            return this == VEC2 ? 2 : this == VEC3 ? 3 : 1;
        }
    }

    /** One record component. */
    static final class Field {
        String name;
        Kind kind;

        /** The declared Java type as written (enum binary name, {@code Vec3f}, ...). */
        String javaType;

        /** Enum constants in ordinal order. */
        List<String> enumValues = new ArrayList<>();

        String assetKind = "";
        String doc = "";

        /** Default per column (an enum's as its ordinal, a bool as 0/1). */
        double[] defaults;

        double min = Double.NaN;
        double max = Double.NaN;
        boolean angle;
        boolean color;

        /** Column names: the field's, or {@code name.x}... for vectors. */
        List<String> columns() {
            return switch (kind) {
                case VEC2 -> List.of(name + ".x", name + ".y");
                case VEC3 -> List.of(name + ".x", name + ".y", name + ".z");
                default -> List.of(name);
            };
        }

        /** Java field names of the columns in the store. */
        List<String> columnFields() {
            return switch (kind) {
                case VEC2 -> List.of(name + "X", name + "Y");
                case VEC3 -> List.of(name + "X", name + "Y", name + "Z");
                default -> List.of(name);
            };
        }
    }

    /** One {@code @Component} record. */
    static final class Component {
        String pkg;
        String name;
        String doc = "";
        List<Field> fields = new ArrayList<>();

        String qualified() {
            return pkg.isEmpty() ? name : pkg + "." + name;
        }

        String store() {
            return name + "Store";
        }

        String view() {
            return name + "View";
        }

        String mut() {
            return name + "Mut";
        }

        String qualifiedStore() {
            return pkg.isEmpty() ? store() : pkg + "." + store();
        }

        String qualifiedView() {
            return pkg.isEmpty() ? view() : pkg + "." + view();
        }

        String qualifiedMut() {
            return pkg.isEmpty() ? mut() : pkg + "." + mut();
        }

        /** The registry's field for the store: lower camel case of the name. */
        String fieldName() {
            return Character.toLowerCase(name.charAt(0)) + name.substring(1);
        }
    }

    /** How a behaviour parameter is filled. */
    enum ParamKind {
        VIEW,
        MUT,
        DT,
        ALPHA,
        ENTITY,
        TRIGGER,
        KIND,
        A,
        B,
        SINGLETON
    }

    /** One behaviour parameter. */
    static final class Param {
        ParamKind kind;

        /** For views and muts. */
        Component component;

        boolean optional;

        /** For singletons: the declared type's qualified name. */
        String type;

        /** For singletons: the registry's field. */
        String field;

        boolean threadSafe;

        /** The parameter's name in the source, for messages. */
        String name;
    }

    /** The phases. */
    enum Phase {
        SPAWN("spawn"),
        DESPAWN("despawn"),
        FIXED_UPDATE("fixedUpdate"),
        UPDATE("update"),
        TRIGGER("trigger"),
        TURN("turn"),
        EVENT("event");

        final String json;

        Phase(String json) {
            this.json = json;
        }
    }

    /** One behaviour method. */
    static final class Behaviour {
        /** The class's qualified name. */
        String owner;

        String ownerSimple;
        String method;
        Phase phase;

        /** The trigger kind or the signal kind. */
        int kindValue;

        int order;
        int sourceIndex;
        boolean parallel;
        int grain = 64;
        List<Param> params = new ArrayList<>();
        List<Component> without = new ArrayList<>();

        /** A unique Java identifier for the generated members. */
        String id() {
            return ownerSimple + "_" + method;
        }

        String name() {
            return ownerSimple + "." + method;
        }

        /** Required components: non-optional views and muts. */
        List<Component> required() {
            List<Component> out = new ArrayList<>();
            for (Param p : params) {
                if ((p.kind == ParamKind.VIEW || p.kind == ParamKind.MUT) && !p.optional) {
                    out.add(p.component);
                }
            }
            return out;
        }

        /** Components read: views, optional views and the excluded ones (membership). */
        List<String> reads() {
            List<String> out = new ArrayList<>();
            for (Param p : params) {
                if (p.kind == ParamKind.VIEW && !out.contains(p.component.name)) {
                    out.add(p.component.name);
                }
            }
            for (Component c : without) {
                if (!out.contains(c.name)) {
                    out.add(c.name);
                }
            }
            return out;
        }

        /** Components written: muts. */
        List<String> writes() {
            List<String> out = new ArrayList<>();
            for (Param p : params) {
                if (p.kind == ParamKind.MUT && !out.contains(p.component.name)) {
                    out.add(p.component.name);
                }
            }
            return out;
        }

        /** Whether {@code later} must wait for this one in a parallel group. */
        boolean conflictsWith(Behaviour later) {
            for (String w : writes()) {
                if (later.reads().contains(w) || later.writes().contains(w)) {
                    return true;
                }
            }
            for (String r : reads()) {
                if (later.writes().contains(r)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** One singleton the registry takes. */
    static final class Singleton {
        String type;
        String field;
    }

    final List<Component> components = new ArrayList<>();
    final List<Behaviour> behaviours = new ArrayList<>();
    final List<Singleton> singletons = new ArrayList<>();
    String registryPackage;
    String registryName;
    int capacity = 256;

    String registryQualified() {
        return registryPackage == null || registryPackage.isEmpty()
                ? registryName
                : registryPackage + "." + registryName;
    }

    Component component(String simpleName) {
        for (Component c : components) {
            if (c.name.equals(simpleName)) {
                return c;
            }
        }
        return null;
    }

    Singleton singleton(String type) {
        for (Singleton s : singletons) {
            if (s.type.equals(type)) {
                return s;
            }
        }
        Singleton s = new Singleton();
        s.type = type;
        String simple = type.substring(type.lastIndexOf('.') + 1).replace('$', '_');
        String base = Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
        String field = base;
        int n = 2;
        while (hasSingletonField(field) || isReservedField(field)) {
            field = base + n++;
        }
        s.field = field;
        singletons.add(s);
        return s;
    }

    private boolean hasSingletonField(String field) {
        for (Singleton s : singletons) {
            if (s.field.equals(field)) {
                return true;
            }
        }
        return false;
    }

    private boolean isReservedField(String field) {
        if (field.equals("entities")
                || field.equals("jobs")
                || field.equals("dt")
                || field.equals("alpha")
                || field.equals("stores")) {
            return true;
        }
        for (Component c : components) {
            if (c.fieldName().equals(field)) {
                return true;
            }
        }
        return false;
    }

    List<Behaviour> ofPhase(Phase phase) {
        List<Behaviour> out = new ArrayList<>();
        for (Behaviour b : behaviours) {
            if (b.phase == phase) {
                out.add(b);
            }
        }
        return out;
    }
}
