package dev.jeddy.core.profile;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Registry of profiler scope names. Register once at startup ({@code static final int SIM =
 * ScopeIds.register("sim")}) and pass the int to {@link Profiler#begin(int)}.
 *
 * <p>Thread ownership: thread-safe (registration is synchronized and rare).
 */
public final class ScopeIds {
    private static final Map<String, Integer> BY_NAME = new HashMap<>();
    private static volatile String[] names = new String[0];

    private ScopeIds() {}

    /** Returns the id for {@code name}, registering it on first use. Ids are dense from 0. */
    public static synchronized int register(String name) {
        Integer existing = BY_NAME.get(name);
        if (existing != null) {
            return existing;
        }
        int id = names.length;
        String[] next = Arrays.copyOf(names, id + 1);
        next[id] = name;
        names = next;
        BY_NAME.put(name, id);
        return id;
    }

    /** Returns the name of scope {@code id}. */
    public static String name(int id) {
        String[] n = names;
        return id >= 0 && id < n.length ? n[id] : "scope#" + id;
    }

    /** Returns the number of registered scopes. */
    public static int count() {
        return names.length;
    }
}
