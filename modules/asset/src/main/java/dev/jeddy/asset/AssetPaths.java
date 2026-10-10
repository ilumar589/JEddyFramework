package dev.jeddy.asset;

import dev.jeddy.core.id.StrId;

import java.util.Locale;

/**
 * Asset path rules (DESIGN §4.1, §10.2). An asset path is relative to the cook root, uses forward
 * slashes and is lower case, so the cooker, the paks and loose directories agree on the {@link
 * StrId} of every asset on every platform.
 *
 * <p>Thread ownership: stateless, callable from any thread.
 */
public final class AssetPaths {
    private AssetPaths() {}

    /**
     * Returns {@code path} normalized: backslashes become slashes, leading {@code ./} and {@code /}
     * are dropped, duplicate slashes collapse and letters become lower case. Returns {@code path}
     * itself (no allocation) when it is already normalized.
     *
     * @throws IllegalArgumentException for empty paths or paths with {@code .} or {@code ..}
     *     segments
     */
    public static String normalize(String path) {
        if (isNormalized(path)) {
            return path;
        }
        String p = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        StringBuilder b = new StringBuilder(p.length());
        for (String segment : p.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            if (segment.equals(".") && b.isEmpty()) {
                continue;
            }
            if (segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("relative segment in asset path: " + path);
            }
            if (!b.isEmpty()) {
                b.append('/');
            }
            b.append(segment);
        }
        if (b.isEmpty()) {
            throw new IllegalArgumentException("empty asset path: '" + path + "'");
        }
        return b.toString();
    }

    /** Returns {@code true} if {@link #normalize} would return {@code path} unchanged. */
    public static boolean isNormalized(String path) {
        int n = path.length();
        if (n == 0 || path.charAt(0) == '/' || path.charAt(n - 1) == '/') {
            return false;
        }
        int segmentStart = 0;
        for (int i = 0; i <= n; i++) {
            char c = i < n ? path.charAt(i) : '/';
            if (c == '/') {
                int len = i - segmentStart;
                if (len == 0
                        || (len == 1 && path.charAt(segmentStart) == '.')
                        || (len == 2
                                && path.charAt(segmentStart) == '.'
                                && path.charAt(segmentStart + 1) == '.')) {
                    return false;
                }
                segmentStart = i + 1;
            } else if (c == '\\' || Character.toLowerCase(c) != c) {
                return false;
            }
        }
        return true;
    }

    /** Returns the {@link StrId} of the normalized path. */
    public static long id(String path) {
        return StrId.of(normalize(path));
    }
}
