package dev.jeddy.editor;

/**
 * Allocation-free text helpers for the panels: a case-insensitive "contains" for filters and number
 * parsing of console arguments.
 *
 * <p>Thread ownership: any (stateless).
 */
public final class Text {
    private Text() {}

    /** Returns whether {@code hay} contains {@code needle}, ignoring ASCII case; empty matches. */
    public static boolean containsIgnoreCase(CharSequence hay, CharSequence needle) {
        int n = needle.length();
        if (n == 0) {
            return true;
        }
        int h = hay.length();
        outer:
        for (int i = 0; i + n <= h; i++) {
            for (int k = 0; k < n; k++) {
                if (Character.toLowerCase(hay.charAt(i + k))
                        != Character.toLowerCase(needle.charAt(k))) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /** Returns whether {@code a} and {@code b} hold the same characters. */
    public static boolean equals(CharSequence a, CharSequence b) {
        if (a.length() != b.length()) {
            return false;
        }
        for (int i = 0; i < a.length(); i++) {
            if (a.charAt(i) != b.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
