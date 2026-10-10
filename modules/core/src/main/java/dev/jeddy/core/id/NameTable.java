package dev.jeddy.core.id;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps {@link StrId}s back to their strings for logs and debug overlays, and detects collisions.
 * Dev builds register names as they are hashed; release builds can leave it empty.
 *
 * <p>Thread ownership: thread-safe.
 */
public final class NameTable {
    private final ConcurrentHashMap<Long, String> names = new ConcurrentHashMap<>();

    /** Creates an empty table. */
    public NameTable() {}

    /**
     * Hashes {@code name}, remembers it and returns its id.
     *
     * @throws IllegalStateException if a different string already has the same id
     */
    public long register(String name) {
        long id = StrId.of(name);
        String previous = names.putIfAbsent(id, name);
        if (previous != null && !previous.equals(name)) {
            throw new IllegalStateException(
                    "StrId collision: '" + previous + "' and '" + name + "' both hash to " + StrId.hex(id));
        }
        return id;
    }

    /** Returns the registered string for {@code id}, or its hex form. */
    public String name(long id) {
        String n = names.get(id);
        return n != null ? n : StrId.hex(id);
    }

    /** Returns the number of registered names. */
    public int size() {
        return names.size();
    }
}
