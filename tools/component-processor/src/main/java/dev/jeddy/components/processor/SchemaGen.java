package dev.jeddy.components.processor;

import dev.jeddy.components.processor.Model.Behaviour;
import dev.jeddy.components.processor.Model.Component;
import dev.jeddy.components.processor.Model.Field;
import dev.jeddy.components.processor.Model.Kind;
import dev.jeddy.components.processor.Model.Param;

import java.util.ArrayList;
import java.util.List;

/**
 * Emits {@code META-INF/jeddy/components.json} (DESIGN §22, D109), the schema {@code
 * asset.format.ComponentSchema} reads: components, fields, types, defaults and hints; behaviours,
 * phases and access.
 *
 * <p>Thread ownership: the compiler's processing thread.
 */
final class SchemaGen {
    private SchemaGen() {}

    static String json(Model m) {
        StringBuilder b = new StringBuilder();
        b.append("{\n  \"version\": 1,\n  \"registry\": ");
        string(
                b,
                m.registryPackage.isEmpty()
                        ? m.registryName
                        : m.registryPackage + "." + m.registryName);
        b.append(",\n  \"capacity\": ").append(m.capacity).append(",\n  \"components\": [\n");
        for (int i = 0; i < m.components.size(); i++) {
            Component c = m.components.get(i);
            b.append("    {\"name\": ");
            string(b, c.name);
            b.append(", \"type\": ");
            string(b, c.qualified());
            b.append(", \"doc\": ");
            string(b, c.doc);
            b.append(", \"fields\": [\n");
            for (int k = 0; k < c.fields.size(); k++) {
                field(b, c.fields.get(k));
                b.append(k + 1 < c.fields.size() ? ",\n" : "\n");
            }
            b.append("    ]}").append(i + 1 < m.components.size() ? ",\n" : "\n");
        }
        b.append("  ],\n  \"behaviours\": [\n");
        for (int i = 0; i < m.behaviours.size(); i++) {
            Behaviour bh = m.behaviours.get(i);
            b.append("    {\"name\": ");
            string(b, bh.name());
            b.append(", \"phase\": ");
            string(b, bh.phase.json);
            b.append(", \"parallel\": ").append(bh.parallel);
            b.append(", \"order\": ").append(bh.order);
            b.append(", \"stage\": ").append(bh.stage);
            if (bh.phase == Model.Phase.TRIGGER || bh.phase == Model.Phase.EVENT) {
                b.append(", \"kind\": ").append(bh.kindValue);
            }
            b.append(", \"reads\": ");
            strings(b, bh.reads());
            b.append(", \"writes\": ");
            strings(b, bh.writes());
            List<String> optional = new ArrayList<>();
            for (Param p : bh.params) {
                if (p.optional && !optional.contains(p.component.name)) {
                    optional.add(p.component.name);
                }
            }
            b.append(", \"optional\": ");
            strings(b, optional);
            List<String> without = new ArrayList<>();
            for (Component c : bh.without) {
                without.add(c.name);
            }
            b.append(", \"without\": ");
            strings(b, without);
            b.append("}").append(i + 1 < m.behaviours.size() ? ",\n" : "\n");
        }
        b.append("  ]\n}\n");
        return b.toString();
    }

    private static void field(StringBuilder b, Field f) {
        b.append("      {\"name\": ");
        string(b, f.name);
        b.append(", \"type\": ");
        string(b, f.kind.json);
        b.append(", \"default\": ");
        switch (f.kind) {
            case INT, ENTITY, ASSET -> b.append((int) f.defaults[0]);
            case LONG -> b.append((long) f.defaults[0]);
            case FLOAT -> b.append(number(f.defaults[0]));
            case BOOL -> b.append(f.defaults[0] != 0);
            case ENUM -> string(b, f.enumValues.get((int) f.defaults[0]));
            case VEC2, VEC3 -> {
                b.append("[");
                for (int i = 0; i < f.defaults.length; i++) {
                    b.append(i > 0 ? ", " : "").append(number(f.defaults[i]));
                }
                b.append("]");
            }
        }
        if (f.kind == Kind.ENUM) {
            b.append(", \"values\": ");
            strings(b, f.enumValues);
        }
        if (f.kind == Kind.ASSET) {
            b.append(", \"asset\": ");
            string(b, f.assetKind);
        }
        if (!Double.isNaN(f.min) && !Double.isNaN(f.max)) {
            b.append(", \"min\": ")
                    .append(number(f.min))
                    .append(", \"max\": ")
                    .append(number(f.max));
        }
        if (f.angle) {
            b.append(", \"angle\": true");
        }
        if (f.color) {
            b.append(", \"color\": true");
        }
        if (!f.doc.isEmpty()) {
            b.append(", \"doc\": ");
            string(b, f.doc);
        }
        b.append("}");
    }

    private static String number(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }

    private static void strings(StringBuilder b, List<String> list) {
        b.append("[");
        for (int i = 0; i < list.size(); i++) {
            b.append(i > 0 ? ", " : "");
            string(b, list.get(i));
        }
        b.append("]");
    }

    static void string(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        b.append('"');
    }
}
