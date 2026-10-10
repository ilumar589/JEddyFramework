package dev.jeddy.render;

import dev.jeddy.asset.format.Ktx2;
import dev.jeddy.compress.Zstd;
import dev.jeddy.compress.ZstdCompressor;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Minimal KTX2 files (no DFD, no key/values) for render tests. */
public final class TestKtx2 {
    private TestKtx2() {}

    /** A single-level 2D texture with every texel {@code rgba} (RGBA8 UNORM). */
    public static byte[] solid(int width, int height, int rgba, boolean zstd) {
        byte[] level = new byte[width * height * 4];
        for (int i = 0; i < width * height; i++) {
            level[i * 4] = (byte) (rgba >>> 24);
            level[i * 4 + 1] = (byte) (rgba >>> 16);
            level[i * 4 + 2] = (byte) (rgba >>> 8);
            level[i * 4 + 3] = (byte) rgba;
        }
        return file(Ktx2.VK_FORMAT_R8G8B8A8_UNORM, width, height, new byte[][] {level}, zstd);
    }

    /** Writes a KTX2 file, level 0 first in {@code levels}. */
    public static byte[] file(int vkFormat, int width, int height, byte[][] levels, boolean zstd) {
        return file(vkFormat, 1, width, height, 0, levels, zstd);
    }

    /** A single-level RGBA16F 3D texture of {@code n}³ RGB triples (red fastest, alpha 1). */
    public static byte[] volume(int n, float[] rgb, boolean zstd) {
        ByteBuffer b = ByteBuffer.allocate(n * n * n * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n * n * n; i++) {
            b.putShort(Float.floatToFloat16(rgb[i * 3]));
            b.putShort(Float.floatToFloat16(rgb[i * 3 + 1]));
            b.putShort(Float.floatToFloat16(rgb[i * 3 + 2]));
            b.putShort(Float.floatToFloat16(1f));
        }
        return file(Ktx2.VK_FORMAT_R16G16B16A16_SFLOAT, 2, n, n, n, new byte[][] {b.array()}, zstd);
    }

    /** Writes a KTX2 file (depth 0 for 2D), level 0 first in {@code levels}. */
    public static byte[] file(
            int vkFormat,
            int typeSize,
            int width,
            int height,
            int depth,
            byte[][] levels,
            boolean zstd) {
        byte[][] stored = new byte[levels.length][];
        try (ZstdCompressor c = new ZstdCompressor(Zstd.DEFAULT_LEVEL);
                Arena a = Arena.ofConfined()) {
            for (int i = 0; i < levels.length; i++) {
                if (!zstd) {
                    stored[i] = levels[i];
                    continue;
                }
                MemorySegment src = a.allocate(Math.max(1, levels[i].length));
                MemorySegment.copy(levels[i], 0, src, ValueLayout.JAVA_BYTE, 0, levels[i].length);
                MemorySegment dst = a.allocate(Zstd.compressBound(levels[i].length));
                long n = c.compress(dst, src.asSlice(0, levels[i].length));
                stored[i] = dst.asSlice(0, n).toArray(ValueLayout.JAVA_BYTE);
            }
        }
        int indexEnd = Ktx2.LEVEL_INDEX_OFFSET + 24 * levels.length;
        long[] offsets = new long[levels.length];
        long p = (indexEnd + 15) & ~15L;
        for (int i = levels.length - 1; i >= 0; i--) {
            p = (p + 15) & ~15L;
            offsets[i] = p;
            p += stored[i].length;
        }
        ByteBuffer b = ByteBuffer.allocate((int) p).order(ByteOrder.LITTLE_ENDIAN);
        b.put(
                new byte[] {
                    (byte) 0xAB, 'K', 'T', 'X', ' ', '2', '0', (byte) 0xBB, '\r', '\n', 0x1A, '\n'
                });
        b.putInt(vkFormat).putInt(typeSize).putInt(width).putInt(height).putInt(depth);
        b.putInt(0).putInt(1).putInt(levels.length).putInt(zstd ? 2 : 0);
        b.putInt(0).putInt(0).putInt(0).putInt(0).putLong(0).putLong(0);
        for (int i = 0; i < levels.length; i++) {
            b.putLong(offsets[i]).putLong(stored[i].length).putLong(levels[i].length);
        }
        for (int i = 0; i < levels.length; i++) {
            b.put((int) offsets[i], stored[i]);
        }
        return b.array();
    }
}
