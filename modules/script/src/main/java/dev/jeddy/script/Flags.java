package dev.jeddy.script;

import dev.jeddy.core.collections.LongIntMap;
import dev.jeddy.core.id.StrId;
import dev.jeddy.core.io.ByteReader;
import dev.jeddy.core.io.ByteWriter;

/**
 * The named flags and counters of a playthrough (DESIGN §24, D124): {@code set flag door} in a
 * script, {@code flag(door)} in a condition, keyed by the {@code StrId} of the name. A flag that
 * was never set reads 0. Saved whole; a game reads flags of its own through {@link #get(String)}.
 *
 * <p>Thread ownership: the game thread.
 */
public final class Flags {
    /** The layout {@link #write} uses (D117). */
    public static final int SAVE_FORMAT = 1;

    private final LongIntMap values;
    private int version;

    public Flags(int expected) {
        values = new LongIntMap(Math.max(16, expected), 0);
    }

    public Flags() {
        this(64);
    }

    /** The value of flag {@code key} (a {@code StrId}), 0 when unset. */
    public int get(long key) {
        return values.get(key);
    }

    public int get(String name) {
        return get(StrId.of(name));
    }

    public boolean isSet(String name) {
        return get(name) != 0;
    }

    /** Sets flag {@code key} to {@code value} (0 clears it; the entry stays). */
    public void set(long key, int value) {
        if (values.get(key) != value) {
            values.put(key, value);
            version++;
        }
    }

    public void set(String name, int value) {
        set(StrId.of(name), value);
    }

    public void set(String name, boolean value) {
        set(name, value ? 1 : 0);
    }

    /** Bumped on every change (a UI redraws when it moves). */
    public int version() {
        return version;
    }

    public int size() {
        return values.size();
    }

    public void clear() {
        values.clear();
        version++;
    }

    // ---- Saves ----------------------------------------------------------------------------------

    /** Writes every flag (key and value). Allocates nothing. */
    public void write(ByteWriter w) {
        w.int32(SAVE_FORMAT);
        w.int32(values.size());
        for (int slot = values.firstSlot(); slot >= 0; slot = values.nextSlot(slot)) {
            w.int64(values.keyAt(slot));
            w.int32(values.valueAt(slot));
        }
    }

    /** Reads what {@link #write} wrote, replacing every flag. */
    public void read(ByteReader r) {
        int format = r.int32();
        if (format != 1) {
            throw new IllegalStateException("flags save format " + format + "; this build reads 1");
        }
        values.clear();
        int n = r.int32();
        for (int i = 0; i < n; i++) {
            long key = r.int64();
            values.put(key, r.int32());
        }
        version++;
    }
}
