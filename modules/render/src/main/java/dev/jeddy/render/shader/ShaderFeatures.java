package dev.jeddy.render.shader;

import java.util.ArrayList;
import java.util.List;

/**
 * Names for the bits of a 64-bit permutation mask (DESIGN §9.2): bit {@code i} set means {@code
 * #define NAME_i 1}. A material's feature mask selects the program variant.
 *
 * <p>Thread ownership: immutable.
 */
public final class ShaderFeatures {
    private final String[] names;

    /** Creates the feature table; {@code names[i]} is bit {@code i}. */
    public ShaderFeatures(String... names) {
        if (names.length > 64) {
            throw new IllegalArgumentException("at most 64 features");
        }
        this.names = names.clone();
    }

    /** No features. */
    public static final ShaderFeatures NONE = new ShaderFeatures();

    /** Returns the bit of {@code name}, or throws. */
    public long bit(String name) {
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(name)) {
                return 1L << i;
            }
        }
        throw new IllegalArgumentException("unknown shader feature " + name);
    }

    /** Returns the {@code #define} entries for {@code mask}. */
    public List<String> defines(long mask) {
        List<String> out = new ArrayList<>(Long.bitCount(mask));
        for (int i = 0; i < names.length; i++) {
            if ((mask & (1L << i)) != 0) {
                out.add(names[i]);
            }
        }
        if ((mask & ~validMask()) != 0) {
            throw new IllegalArgumentException(
                    "mask has undefined feature bits: " + Long.toHexString(mask));
        }
        return out;
    }

    private long validMask() {
        return names.length == 64 ? -1L : (1L << names.length) - 1;
    }
}
