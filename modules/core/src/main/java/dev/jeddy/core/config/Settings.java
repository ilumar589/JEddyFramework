package dev.jeddy.core.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Settings in a TOML subset: {@code [section]} headers, {@code key = value} pairs, {@code #}
 * comments. Values are strings ({@code "..."}), integers, floats, booleans and flat arrays of
 * those. Keys are addressed with dots: {@code graphics.vsync}. Used for user settings (graphics,
 * audio, key bindings) and engine configuration.
 *
 * <p>Thread ownership: single owner thread; not thread-safe.
 */
public final class Settings {
    private final Map<String, Object> values = new LinkedHashMap<>();

    /** Creates empty settings. */
    public Settings() {}

    /** Parses settings text. Throws {@link IllegalArgumentException} with the line number. */
    public static Settings parse(String text) {
        Settings s = new Settings();
        String section = "";
        String[] lines = text.split("\r?\n", -1);
        for (int n = 0; n < lines.length; n++) {
            String line = stripComment(lines[n]).trim();
            if (line.isEmpty()) {
                continue;
            }
            try {
                if (line.startsWith("[")) {
                    if (!line.endsWith("]")) {
                        throw new IllegalArgumentException("unterminated section header");
                    }
                    section = line.substring(1, line.length() - 1).trim();
                    if (section.isEmpty()) {
                        throw new IllegalArgumentException("empty section name");
                    }
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    throw new IllegalArgumentException("expected key = value");
                }
                String key = line.substring(0, eq).trim();
                String raw = line.substring(eq + 1).trim();
                s.values.put(section.isEmpty() ? key : section + "." + key, parseValue(raw));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("line " + (n + 1) + ": " + e.getMessage(), e);
            }
        }
        return s;
    }

    /** Reads settings from a UTF-8 file; a missing file gives empty settings. */
    public static Settings load(Path file) throws IOException {
        if (!Files.exists(file)) {
            return new Settings();
        }
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** Writes the settings to a UTF-8 file. */
    public void save(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, toToml(), StandardCharsets.UTF_8);
    }

    /** Returns {@code true} if {@code key} is set. */
    public boolean contains(String key) {
        return values.containsKey(key);
    }

    /** Returns every key in insertion order. */
    public Set<String> keys() {
        return Collections.unmodifiableSet(values.keySet());
    }

    /** Returns a string value, or {@code fallback}. */
    public String getString(String key, String fallback) {
        Object v = values.get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    /** Returns an integer value, or {@code fallback}. */
    public int getInt(String key, int fallback) {
        Object v = values.get(key);
        if (v instanceof Long l) {
            return Math.toIntExact(l);
        }
        if (v == null) {
            return fallback;
        }
        throw new IllegalArgumentException(key + " is not an integer: " + v);
    }

    /** Returns a long value, or {@code fallback}. */
    public long getLong(String key, long fallback) {
        Object v = values.get(key);
        if (v instanceof Long l) {
            return l;
        }
        if (v == null) {
            return fallback;
        }
        throw new IllegalArgumentException(key + " is not an integer: " + v);
    }

    /** Returns a float value (integers are accepted), or {@code fallback}. */
    public float getFloat(String key, float fallback) {
        Object v = values.get(key);
        if (v instanceof Double d) {
            return d.floatValue();
        }
        if (v instanceof Long l) {
            return l;
        }
        if (v == null) {
            return fallback;
        }
        throw new IllegalArgumentException(key + " is not a number: " + v);
    }

    /** Returns a boolean value, or {@code fallback}. */
    public boolean getBoolean(String key, boolean fallback) {
        Object v = values.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v == null) {
            return fallback;
        }
        throw new IllegalArgumentException(key + " is not a boolean: " + v);
    }

    /** Returns an array value as strings, or an empty list. */
    public List<String> getStringList(String key) {
        Object v = values.get(key);
        if (v instanceof List<?> l) {
            List<String> out = new ArrayList<>(l.size());
            for (Object o : l) {
                out.add(String.valueOf(o));
            }
            return out;
        }
        return v == null ? List.of() : List.of(String.valueOf(v));
    }

    /** Sets a string value. */
    public void set(String key, String value) {
        values.put(key, value);
    }

    /** Sets an integer value. */
    public void set(String key, long value) {
        values.put(key, value);
    }

    /** Sets a float value. */
    public void set(String key, double value) {
        values.put(key, value);
    }

    /** Sets a boolean value. */
    public void set(String key, boolean value) {
        values.put(key, value);
    }

    /** Sets an array of strings. */
    public void set(String key, List<String> value) {
        values.put(key, new ArrayList<Object>(value));
    }

    /** Removes {@code key}. */
    public void remove(String key) {
        values.remove(key);
    }

    /** Serializes to the TOML subset, grouping keys by section. */
    public String toToml() {
        Map<String, Map<String, Object>> sections = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            int dot = e.getKey().lastIndexOf('.');
            String section = dot < 0 ? "" : e.getKey().substring(0, dot);
            String key = dot < 0 ? e.getKey() : e.getKey().substring(dot + 1);
            sections.computeIfAbsent(section, k -> new LinkedHashMap<>()).put(key, e.getValue());
        }
        StringBuilder sb = new StringBuilder();
        Map<String, Object> root = sections.remove("");
        if (root != null) {
            root.forEach((k, v) -> sb.append(k).append(" = ").append(format(v)).append('\n'));
        }
        sections.forEach(
                (section, entries) -> {
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append('[').append(section).append("]\n");
                    entries.forEach(
                            (k, v) -> sb.append(k).append(" = ").append(format(v)).append('\n'));
                });
        return sb.toString();
    }

    private static String format(Object v) {
        if (v instanceof String s) {
            return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
        }
        if (v instanceof List<?> l) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < l.size(); i++) {
                sb.append(i == 0 ? "" : ", ").append(format(l.get(i)));
            }
            return sb.append(']').toString();
        }
        if (v instanceof Double d) {
            String s = Double.toString(d);
            return s.contains(".") || s.contains("E") ? s : s + ".0";
        }
        return String.valueOf(v);
    }

    private static Object parseValue(String raw) {
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("missing value");
        }
        if (raw.startsWith("\"")) {
            if (raw.length() < 2 || !raw.endsWith("\"")) {
                throw new IllegalArgumentException("unterminated string");
            }
            return unescape(raw.substring(1, raw.length() - 1));
        }
        if (raw.startsWith("[")) {
            if (!raw.endsWith("]")) {
                throw new IllegalArgumentException("unterminated array");
            }
            List<Object> out = new ArrayList<>();
            for (String part : splitArray(raw.substring(1, raw.length() - 1))) {
                out.add(parseValue(part));
            }
            return out;
        }
        if (raw.equals("true") || raw.equals("false")) {
            return Boolean.parseBoolean(raw);
        }
        String num = raw.replace("_", "");
        try {
            if (num.contains(".")
                    || num.toLowerCase(Locale.ROOT).contains("e")
                    || num.contains("inf")
                    || num.contains("nan")) {
                return Double.parseDouble(num);
            }
            return Long.parseLong(num);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad value '" + raw + "'");
        }
    }

    private static List<String> splitArray(String body) {
        List<String> parts = new ArrayList<>();
        boolean inString = false;
        int start = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '"' && (i == 0 || body.charAt(i - 1) != '\\')) {
                inString = !inString;
            } else if (c == ',' && !inString) {
                parts.add(body.substring(start, i).trim());
                start = i + 1;
            }
        }
        String last = body.substring(start).trim();
        if (!last.isEmpty()) {
            parts.add(last);
        }
        return parts;
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char e = s.charAt(++i);
                sb.append(
                        switch (e) {
                            case 'n' -> '\n';
                            case 't' -> '\t';
                            case 'r' -> '\r';
                            default -> e;
                        });
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String stripComment(String line) {
        boolean inString = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"' && (i == 0 || line.charAt(i - 1) != '\\')) {
                inString = !inString;
            } else if (c == '#' && !inString) {
                return line.substring(0, i);
            }
        }
        return line;
    }
}
