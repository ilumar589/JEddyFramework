package dev.jeddy.editor.rules;

import java.util.Arrays;

/**
 * Where a value lives in a {@link RulesSource}: the kind name, then alternating row indices and
 * field names down the JSON tree, for example {@code feats[3].effects[1].value} is the segments
 * {@code "feats", 3, "effects", 1, "value"}. Commands carry paths rather than object references so
 * undo and redo find the same place after rows were removed and re-inserted.
 *
 * <p>Thread ownership: immutable, any thread.
 */
public final class RulesPath {
    private final Object[] segments;

    private RulesPath(Object[] segments) {
        this.segments = segments;
    }

    /** A path from {@code segments} (strings and integers). */
    public static RulesPath of(Object... segments) {
        for (Object s : segments) {
            if (!(s instanceof String) && !(s instanceof Integer)) {
                throw new IllegalArgumentException("segment " + s);
            }
        }
        return new RulesPath(segments.clone());
    }

    /** The number of segments. */
    public int length() {
        return segments.length;
    }

    /** Segment {@code i}. */
    public Object segment(int i) {
        return segments[i];
    }

    /** The last segment. */
    public Object last() {
        return segments[segments.length - 1];
    }

    /** The path without its last segment. */
    public RulesPath parent() {
        return new RulesPath(Arrays.copyOf(segments, segments.length - 1));
    }

    /** This path with {@code segment} appended. */
    public RulesPath child(Object segment) {
        Object[] s = Arrays.copyOf(segments, segments.length + 1);
        s[segments.length] = segment;
        return of(s);
    }

    /** This path with {@code index} then {@code field} appended. */
    public RulesPath child(int index, String field) {
        return child(index).child(field);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RulesPath p && Arrays.equals(segments, p.segments);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(segments);
    }

    /** {@code kind[row].field[index]...}. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Object s : segments) {
            if (s instanceof Integer i) {
                sb.append('[').append(i).append(']');
            } else {
                if (sb.length() > 0) {
                    sb.append('.');
                }
                sb.append(s);
            }
        }
        return sb.toString();
    }
}
