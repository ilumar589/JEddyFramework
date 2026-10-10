package dev.jeddy.core.json;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader and writer for cold paths: native manifests, glTF, cooker metadata. Values
 * map to {@code Map<String, Object>} (insertion-ordered), {@code List<Object>}, {@code String},
 * {@code Double}, {@code Boolean} and {@code null}.
 *
 * <p>Thread ownership: stateless, callable from any thread.
 */
public final class Json {
    private Json() {}

    /** Parses UTF-8 JSON text. */
    public static Object parse(byte[] utf8) {
        return parse(new String(utf8, StandardCharsets.UTF_8));
    }

    /**
     * Parses JSON text.
     *
     * @throws JsonException on malformed input, with the offset of the problem
     */
    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (p.pos != text.length()) {
            throw p.error("trailing characters");
        }
        return v;
    }

    /** Casts a parsed value to an object, failing with a clear message. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object value, String what) {
        if (value instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new JsonException(what + ": expected an object, got " + describe(value));
    }

    /** Casts a parsed value to an array. */
    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object value, String what) {
        if (value instanceof List<?> l) {
            return (List<Object>) l;
        }
        throw new JsonException(what + ": expected an array, got " + describe(value));
    }

    /** Returns member {@code key} of {@code obj} as a string, or {@code fallback} if absent. */
    public static String string(Map<String, Object> obj, String key, String fallback) {
        Object v = obj.get(key);
        if (v == null) {
            return fallback;
        }
        if (v instanceof String s) {
            return s;
        }
        throw new JsonException(key + ": expected a string, got " + describe(v));
    }

    /** Returns member {@code key} of {@code obj} as a number, or {@code fallback} if absent. */
    public static double number(Map<String, Object> obj, String key, double fallback) {
        Object v = obj.get(key);
        if (v == null) {
            return fallback;
        }
        if (v instanceof Double d) {
            return d;
        }
        throw new JsonException(key + ": expected a number, got " + describe(v));
    }

    /** Returns member {@code key} of {@code obj} as an int, or {@code fallback} if absent. */
    public static int integer(Map<String, Object> obj, String key, int fallback) {
        double d = number(obj, key, fallback);
        if (d != Math.rint(d) || d > Integer.MAX_VALUE || d < Integer.MIN_VALUE) {
            throw new JsonException(key + ": expected an integer, got " + d);
        }
        return (int) d;
    }

    /** Returns member {@code key} of {@code obj} as a boolean, or {@code fallback} if absent. */
    public static boolean bool(Map<String, Object> obj, String key, boolean fallback) {
        Object v = obj.get(key);
        if (v == null) {
            return fallback;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        throw new JsonException(key + ": expected a boolean, got " + describe(v));
    }

    /** Serializes a value tree to indented JSON. */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 0);
        return sb.append('\n').toString();
    }

    private static void write(StringBuilder sb, Object v, int indent) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            quote(sb, s);
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new JsonException("cannot write " + d);
            }
            if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                sb.append((long) d);
            } else {
                sb.append(d);
            }
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                indent(sb, indent + 1);
                quote(sb, String.valueOf(e.getKey()));
                sb.append(": ");
                write(sb, e.getValue(), indent + 1);
                sb.append(++i < m.size() ? ",\n" : "\n");
            }
            indent(sb, indent);
            sb.append('}');
        } else if (v instanceof List<?> l) {
            if (l.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append("[\n");
            for (int i = 0; i < l.size(); i++) {
                indent(sb, indent + 1);
                write(sb, l.get(i), indent + 1);
                sb.append(i + 1 < l.size() ? ",\n" : "\n");
            }
            indent(sb, indent);
            sb.append(']');
        } else {
            throw new JsonException("cannot write " + v.getClass().getName());
        }
    }

    private static void indent(StringBuilder sb, int n) {
        sb.append("  ".repeat(n));
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static String describe(Object v) {
        return v == null ? "null" : v.getClass().getSimpleName();
    }

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) {
            this.s = s;
        }

        JsonException error(String msg) {
            return new JsonException(msg + " at offset " + pos);
        }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object value() {
            if (pos >= s.length()) {
                throw error("unexpected end");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield number();
                    }
                    throw error("unexpected character '" + c + "'");
                }
            };
        }

        Object literal(String word, Object v) {
            if (!s.startsWith(word, pos)) {
                throw error("expected " + word);
            }
            pos += word.length();
            return v;
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++;
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '}') {
                pos++;
                return m;
            }
            while (true) {
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != '"') {
                    throw error("expected a member name");
                }
                String key = string();
                skipWs();
                expect(':');
                skipWs();
                m.put(key, value());
                skipWs();
                if (pos < s.length() && s.charAt(pos) == ',') {
                    pos++;
                    continue;
                }
                expect('}');
                return m;
            }
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            pos++;
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ']') {
                pos++;
                return l;
            }
            while (true) {
                skipWs();
                l.add(value());
                skipWs();
                if (pos < s.length() && s.charAt(pos) == ',') {
                    pos++;
                    continue;
                }
                expect(']');
                return l;
            }
        }

        void expect(char c) {
            if (pos >= s.length() || s.charAt(pos) != c) {
                throw error("expected '" + c + "'");
            }
            pos++;
        }

        String string() {
            pos++; // opening quote
            StringBuilder sb = null;
            int start = pos;
            while (true) {
                if (pos >= s.length()) {
                    throw error("unterminated string");
                }
                char c = s.charAt(pos);
                if (c == '"') {
                    String out = sb == null ? s.substring(start, pos) : sb.append(s, start, pos).toString();
                    pos++;
                    return out;
                }
                if (c == '\\') {
                    if (sb == null) {
                        sb = new StringBuilder();
                    }
                    sb.append(s, start, pos);
                    pos++;
                    if (pos >= s.length()) {
                        throw error("bad escape");
                    }
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"', '\\', '/' -> sb.append(e);
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > s.length()) {
                                throw error("bad unicode escape");
                            }
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw error("bad escape \\" + e);
                    }
                    start = pos;
                    continue;
                }
                if (c < 0x20) {
                    throw error("control character in string");
                }
                pos++;
            }
        }

        Double number() {
            int start = pos;
            if (s.charAt(pos) == '-') {
                pos++;
            }
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    pos++;
                } else {
                    break;
                }
            }
            try {
                return Double.parseDouble(s.substring(start, pos));
            } catch (NumberFormatException e) {
                pos = start;
                throw error("bad number");
            }
        }
    }
}
