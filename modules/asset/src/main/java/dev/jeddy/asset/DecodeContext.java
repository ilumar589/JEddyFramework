package dev.jeddy.asset;

import dev.jeddy.compress.ZstdDecompressor;

import java.util.Arrays;

/**
 * What {@link AssetLoader#decode} gets besides the bytes: the worker's zstd context (for formats
 * with internally compressed parts, such as KTX2 levels) and dependency registration. A material
 * calls {@link #dependsOn} for each texture; the manager loads them with the material's priority,
 * keeps them while the material lives and exposes their handles through {@link
 * AssetType#dependency}.
 *
 * <p>Thread ownership: one instance per worker thread, used only during a {@code decode} call.
 */
public final class DecodeContext {
    private final int threadIndex;
    private final ZstdDecompressor decompressor;
    private long[] ids = new long[8];
    private byte[] types = new byte[8];
    private int count;

    DecodeContext(int threadIndex, ZstdDecompressor decompressor) {
        this.threadIndex = threadIndex;
        this.decompressor = decompressor;
    }

    /** Returns the job-system thread index of the decoding worker. */
    public int threadIndex() {
        return threadIndex;
    }

    /** Returns this worker's zstd context. */
    public ZstdDecompressor decompressor() {
        return decompressor;
    }

    /**
     * Declares that the asset being decoded uses {@code id} of {@code type}. The {@code i}-th call
     * becomes {@link AssetType#dependency}{@code (handle, i)}.
     */
    public void dependsOn(AssetType<?> type, long id) {
        if (count == ids.length) {
            ids = Arrays.copyOf(ids, count * 2);
            types = Arrays.copyOf(types, count * 2);
        }
        ids[count] = id;
        types[count] = (byte) type.index();
        count++;
    }

    /** Declares a dependency by asset path. */
    public void dependsOn(AssetType<?> type, String path) {
        dependsOn(type, AssetPaths.id(path));
    }

    void reset() {
        count = 0;
    }

    long[] takeIds() {
        return count == 0 ? null : Arrays.copyOf(ids, count);
    }

    byte[] takeTypes() {
        return count == 0 ? null : Arrays.copyOf(types, count);
    }
}
