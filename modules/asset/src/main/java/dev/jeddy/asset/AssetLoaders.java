package dev.jeddy.asset;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Ready-made {@link AssetLoader}s.
 *
 * <p>Thread ownership: stateless factories; the loaders follow {@link AssetLoader}'s rules.
 */
public final class AssetLoaders {
    private AssetLoaders() {}

    private static final AssetLoader<byte[]> BYTES =
            (id, data, ctx) -> data.toArray(ValueLayout.JAVA_BYTE);

    /** Loads an asset's bytes into a {@code byte[]} (configuration, text, small data). */
    public static AssetLoader<byte[]> bytes() {
        return BYTES;
    }

    /** Wraps a decode function that needs no upload and no unload. */
    public static <T> AssetLoader<T> of(Decoder<T> decoder) {
        return decoder::decode;
    }

    /** A decode function, for {@link #of}. */
    @FunctionalInterface
    public interface Decoder<T> {
        /** See {@link AssetLoader#decode}. */
        T decode(long id, MemorySegment data, DecodeContext context) throws Exception;
    }
}
