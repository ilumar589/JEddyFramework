package dev.jeddy.core.id;

/**
 * 64-bit string ids: FNV-1a over the UTF-8 bytes of a string (DESIGN §4.1). The cooker and the
 * runtime compute identical ids on every platform, so assets, events and tags are referred to by
 * {@code long} instead of {@code String}. Hashing allocates nothing.
 *
 * <p>Asset paths are normalized by the caller (forward slashes, lower case) before hashing; the
 * cooker rejects collisions at build time ({@link NameTable}).
 *
 * <p>Thread ownership: stateless, callable from any thread.
 */
public final class StrId {
    /**
     * The id of no string. FNV-1a never produces 0 for real input in practice, and we reserve it.
     */
    public static final long NONE = 0L;

    private static final long OFFSET = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;

    private StrId() {}

    /** Returns the id of {@code s}. */
    public static long of(CharSequence s) {
        long h = OFFSET;
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                h = (h ^ c) * PRIME;
            } else if (c < 0x800) {
                h = (h ^ (0xC0 | (c >> 6))) * PRIME;
                h = (h ^ (0x80 | (c & 0x3F))) * PRIME;
            } else if (Character.isHighSurrogate(c)
                    && i + 1 < n
                    && Character.isLowSurrogate(s.charAt(i + 1))) {
                int cp = Character.toCodePoint(c, s.charAt(++i));
                h = (h ^ (0xF0 | (cp >> 18))) * PRIME;
                h = (h ^ (0x80 | ((cp >> 12) & 0x3F))) * PRIME;
                h = (h ^ (0x80 | ((cp >> 6) & 0x3F))) * PRIME;
                h = (h ^ (0x80 | (cp & 0x3F))) * PRIME;
            } else if (Character.isSurrogate(c)) {
                h = (h ^ '?') * PRIME; // unpaired surrogate: String.getBytes(UTF_8) writes '?'
            } else {
                h = (h ^ (0xE0 | (c >> 12))) * PRIME;
                h = (h ^ (0x80 | ((c >> 6) & 0x3F))) * PRIME;
                h = (h ^ (0x80 | (c & 0x3F))) * PRIME;
            }
        }
        return h;
    }

    /** Returns the id of {@code length} UTF-8 bytes starting at {@code offset}. */
    public static long of(byte[] utf8, int offset, int length) {
        long h = OFFSET;
        for (int i = offset; i < offset + length; i++) {
            h = (h ^ (utf8[i] & 0xFF)) * PRIME;
        }
        return h;
    }

    /** Formats an id as 16 hex digits. */
    public static String hex(long id) {
        return String.format("%016x", id);
    }
}
