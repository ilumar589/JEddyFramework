package dev.jeddy.cooker.pak;

import java.util.regex.Pattern;

/**
 * Glob patterns over {@code /}-separated asset paths: {@code **} matches across directories, {@code
 * *} and {@code ?} within one, {@code {a,b}} alternatives. Matching ignores case (asset paths are
 * lower case).
 *
 * <p>Thread ownership: immutable; any thread.
 */
public final class Glob {
    private final String pattern;
    private final Pattern regex;

    private Glob(String pattern, Pattern regex) {
        this.pattern = pattern;
        this.regex = regex;
    }

    /** Compiles {@code pattern}. */
    public static Glob of(String pattern) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '*' -> {
                    if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                        i++;
                        if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '/') {
                            i++;
                            sb.append("(?:.*/)?"); // "**/" also matches no directory
                        } else {
                            sb.append(".*");
                        }
                    } else {
                        sb.append("[^/]*");
                    }
                }
                case '?' -> sb.append("[^/]");
                case '{' -> {
                    depth++;
                    sb.append("(?:");
                }
                case '}' -> {
                    if (depth == 0) {
                        throw new IllegalArgumentException("unbalanced } in " + pattern);
                    }
                    depth--;
                    sb.append(')');
                }
                case ',' -> sb.append(depth > 0 ? "|" : ",");
                default -> {
                    if ("\\.[]()^$+|".indexOf(c) >= 0) {
                        sb.append('\\');
                    }
                    sb.append(c);
                }
            }
        }
        if (depth != 0) {
            throw new IllegalArgumentException("unbalanced { in " + pattern);
        }
        return new Glob(pattern, Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE));
    }

    /** Returns {@code true} if {@code path} matches. */
    public boolean matches(String path) {
        return regex.matcher(path).matches();
    }

    @Override
    public String toString() {
        return pattern;
    }
}
