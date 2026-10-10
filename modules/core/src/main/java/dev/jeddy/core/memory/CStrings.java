package dev.jeddy.core.memory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NUL-terminated UTF-8 C strings for native calls.
 *
 * <p>{@link #of(String)} interns: each distinct string is converted once into global memory and
 * the same segment is returned afterwards, so constant strings (SDL hints, mixer tags, uniform
 * names) cost nothing per call. Only use it for a bounded set of strings.
 *
 * <p>Thread ownership: thread-safe.
 */
public final class CStrings {
    private static final ConcurrentHashMap<String, MemorySegment> CACHE = new ConcurrentHashMap<>();

    private CStrings() {}

    /** Returns the cached C string for {@code s}, creating it on first use (lives forever). */
    public static MemorySegment of(String s) {
        MemorySegment cached = CACHE.get(s);
        if (cached != null) {
            return cached;
        }
        return CACHE.computeIfAbsent(s, k -> Arena.global().allocateFrom(k, StandardCharsets.UTF_8));
    }

    /**
     * Reads a NUL-terminated UTF-8 string at a native address (allocates the {@code String}).
     * Returns {@code null} for a null pointer.
     */
    public static String read(MemorySegment pointer) {
        if (pointer == null || pointer.equals(MemorySegment.NULL)) {
            return null;
        }
        return pointer.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_8);
    }
}
