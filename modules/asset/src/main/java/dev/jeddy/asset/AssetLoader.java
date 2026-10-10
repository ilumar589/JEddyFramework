package dev.jeddy.asset;

import java.lang.foreign.MemorySegment;

/**
 * Turns an asset's bytes into a value of type {@code T} (TASKS M4-05). One loader per asset type,
 * registered with {@link AssetManager#register}.
 *
 * <p>Thread ownership: {@link #decode} runs on job workers, concurrently for different assets;
 * {@link #upload} runs on the main thread; {@link #unload} runs on the main thread if {@link
 * #needsUpload()}, otherwise on the asset manager's owner thread.
 *
 * @param <T> the loaded value
 */
public interface AssetLoader<T> {
    /**
     * Parses the decompressed bytes of an asset on a worker thread.
     *
     * @param id the asset id
     * @param data the bytes, valid until {@link #upload} returns (or until this method returns when
     *     no upload is needed); copy what the value keeps
     * @param context per-thread services and dependency registration
     * @return the decoded value (never {@code null})
     * @throws Exception if the data is invalid; the asset becomes {@link AssetState#FAILED}
     */
    T decode(long id, MemorySegment data, DecodeContext context) throws Exception;

    /** Returns {@code true} if decoded values go through {@link #upload} on the main thread. */
    default boolean needsUpload() {
        return false;
    }

    /**
     * Finishes loading on the main thread (creates GPU resources), within the frame's upload
     * budget. The default returns {@code decoded}.
     *
     * @param data the same bytes {@link #decode} saw
     */
    default T upload(T decoded, MemorySegment data) throws Exception {
        return decoded;
    }

    /** Frees what {@code value} holds (GPU objects, native memory). The default does nothing. */
    default void unload(T value) {}
}
