package dev.jeddy.asset.format;

import static dev.jeddy.asset.format.Bytes.U32;
import static dev.jeddy.asset.format.Bytes.U64;
import static dev.jeddy.asset.format.Bytes.checkRange;

import dev.jeddy.compress.ZstdDecompressor;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/**
 * Reads KTX 2.0 textures (TASKS M4-06, {@code docs/formats/ktx2.md}): the header, the level index
 * and key/value data, over the file's memory without copying. {@link #decodeLevel} writes a mip
 * level's texel blocks into the caller's memory, decompressing zstd supercompression on the way
 * (straight into a GPU upload buffer, for example).
 *
 * <p>Supported: supercompression none or Zstandard, any {@code vkFormat} (the format tables here
 * know the BCn and common uncompressed formats the cooker produces). BasisLZ and zlib are rejected:
 * the cooker transcodes to BCn before writing.
 *
 * <p>Thread ownership: immutable after {@link #read}; any thread, while the file memory is alive.
 */
public final class Ktx2 {
    /** The 12-byte file identifier {@code «KTX 20»\r\n\x1A\n}. */
    private static final byte[] IDENTIFIER = {
        (byte) 0xAB, 'K', 'T', 'X', ' ', '2', '0', (byte) 0xBB, '\r', '\n', 0x1A, '\n'
    };

    /** Size of the header and index, where the level index starts. */
    public static final int LEVEL_INDEX_OFFSET = 80;

    /** No supercompression. */
    public static final int SUPERCOMPRESSION_NONE = 0;

    /** BasisLZ (rejected). */
    public static final int SUPERCOMPRESSION_BASIS_LZ = 1;

    /** Zstandard, one frame per level. */
    public static final int SUPERCOMPRESSION_ZSTD = 2;

    /** zlib (rejected). */
    public static final int SUPERCOMPRESSION_ZLIB = 3;

    // vkFormat values the engine uses (Vulkan's VkFormat enumeration).
    /** {@code VK_FORMAT_UNDEFINED}. */
    public static final int VK_FORMAT_UNDEFINED = 0;

    /** {@code VK_FORMAT_R8_UNORM}. */
    public static final int VK_FORMAT_R8_UNORM = 9;

    /** {@code VK_FORMAT_R8G8_UNORM}. */
    public static final int VK_FORMAT_R8G8_UNORM = 16;

    /** {@code VK_FORMAT_R8G8B8A8_UNORM}. */
    public static final int VK_FORMAT_R8G8B8A8_UNORM = 37;

    /** {@code VK_FORMAT_R8G8B8A8_SRGB}. */
    public static final int VK_FORMAT_R8G8B8A8_SRGB = 43;

    /** {@code VK_FORMAT_R16_SFLOAT}. */
    public static final int VK_FORMAT_R16_SFLOAT = 76;

    /** {@code VK_FORMAT_R16G16_SFLOAT}. */
    public static final int VK_FORMAT_R16G16_SFLOAT = 83;

    /** {@code VK_FORMAT_R16G16B16A16_SFLOAT}. */
    public static final int VK_FORMAT_R16G16B16A16_SFLOAT = 97;

    /** {@code VK_FORMAT_R32G32B32A32_SFLOAT}. */
    public static final int VK_FORMAT_R32G32B32A32_SFLOAT = 109;

    /** {@code VK_FORMAT_B10G11R11_UFLOAT_PACK32}. */
    public static final int VK_FORMAT_B10G11R11_UFLOAT_PACK32 = 122;

    /** {@code VK_FORMAT_E5B9G9R9_UFLOAT_PACK32}. */
    public static final int VK_FORMAT_E5B9G9R9_UFLOAT_PACK32 = 123;

    /** {@code VK_FORMAT_BC1_RGB_UNORM_BLOCK}. */
    public static final int VK_FORMAT_BC1_RGB_UNORM_BLOCK = 131;

    /** {@code VK_FORMAT_BC1_RGB_SRGB_BLOCK}. */
    public static final int VK_FORMAT_BC1_RGB_SRGB_BLOCK = 132;

    /** {@code VK_FORMAT_BC1_RGBA_UNORM_BLOCK}. */
    public static final int VK_FORMAT_BC1_RGBA_UNORM_BLOCK = 133;

    /** {@code VK_FORMAT_BC1_RGBA_SRGB_BLOCK}. */
    public static final int VK_FORMAT_BC1_RGBA_SRGB_BLOCK = 134;

    /** {@code VK_FORMAT_BC3_UNORM_BLOCK}. */
    public static final int VK_FORMAT_BC3_UNORM_BLOCK = 137;

    /** {@code VK_FORMAT_BC3_SRGB_BLOCK}. */
    public static final int VK_FORMAT_BC3_SRGB_BLOCK = 138;

    /** {@code VK_FORMAT_BC4_UNORM_BLOCK}: masks. */
    public static final int VK_FORMAT_BC4_UNORM_BLOCK = 139;

    /** {@code VK_FORMAT_BC4_SNORM_BLOCK}. */
    public static final int VK_FORMAT_BC4_SNORM_BLOCK = 140;

    /** {@code VK_FORMAT_BC5_UNORM_BLOCK}: normal maps (Z reconstructed). */
    public static final int VK_FORMAT_BC5_UNORM_BLOCK = 141;

    /** {@code VK_FORMAT_BC5_SNORM_BLOCK}. */
    public static final int VK_FORMAT_BC5_SNORM_BLOCK = 142;

    /** {@code VK_FORMAT_BC6H_UFLOAT_BLOCK}: HDR. */
    public static final int VK_FORMAT_BC6H_UFLOAT_BLOCK = 143;

    /** {@code VK_FORMAT_BC6H_SFLOAT_BLOCK}. */
    public static final int VK_FORMAT_BC6H_SFLOAT_BLOCK = 144;

    /** {@code VK_FORMAT_BC7_UNORM_BLOCK}: ORM, emissive. */
    public static final int VK_FORMAT_BC7_UNORM_BLOCK = 145;

    /** {@code VK_FORMAT_BC7_SRGB_BLOCK}: base color. */
    public static final int VK_FORMAT_BC7_SRGB_BLOCK = 146;

    private final MemorySegment file;
    private final int vkFormat;
    private final int typeSize;
    private final int width;
    private final int height;
    private final int depth;
    private final int layers;
    private final int faces;
    private final int levelCount;
    private final int supercompression;
    private final long kvdOffset;
    private final long kvdLength;
    private final long[] levelOffsets;
    private final long[] levelLengths;
    private final long[] levelRawLengths;

    private Ktx2(MemorySegment file) throws AssetFormatException {
        this.file = file;
        checkRange(file, 0, LEVEL_INDEX_OFFSET, "KTX2 header");
        for (int i = 0; i < IDENTIFIER.length; i++) {
            if (file.get(ValueLayout.JAVA_BYTE, i) != IDENTIFIER[i]) {
                throw new AssetFormatException("not a KTX2 file");
            }
        }
        vkFormat = file.get(U32, 12);
        typeSize = file.get(U32, 16);
        width = file.get(U32, 20);
        height = file.get(U32, 24);
        depth = file.get(U32, 28);
        layers = file.get(U32, 32);
        faces = file.get(U32, 36);
        int lc = file.get(U32, 40);
        supercompression = file.get(U32, 44);
        kvdOffset = Integer.toUnsignedLong(file.get(U32, 56));
        kvdLength = Integer.toUnsignedLong(file.get(U32, 60));
        if (width <= 0 || height < 0 || depth < 0 || layers < 0) {
            throw new AssetFormatException("KTX2 has invalid dimensions " + width + "x" + height);
        }
        if (faces != 1 && faces != 6) {
            throw new AssetFormatException("KTX2 faceCount must be 1 or 6, not " + faces);
        }
        if (supercompression != SUPERCOMPRESSION_NONE
                && supercompression != SUPERCOMPRESSION_ZSTD) {
            throw new AssetFormatException(
                    "KTX2 supercompression "
                            + supercompression
                            + " is not supported (cook to BCn)");
        }
        if (lc < 0 || lc > 32) {
            throw new AssetFormatException("KTX2 levelCount " + lc + " is out of range");
        }
        levelCount = Math.max(1, lc);
        checkRange(file, kvdOffset, kvdLength, "KTX2 key/value data");
        checkRange(file, LEVEL_INDEX_OFFSET, 24L * levelCount, "KTX2 level index");
        levelOffsets = new long[levelCount];
        levelLengths = new long[levelCount];
        levelRawLengths = new long[levelCount];
        for (int i = 0; i < levelCount; i++) {
            long at = LEVEL_INDEX_OFFSET + 24L * i;
            levelOffsets[i] = file.get(U64, at);
            levelLengths[i] = file.get(U64, at + 8);
            levelRawLengths[i] = file.get(U64, at + 16);
            checkRange(file, levelOffsets[i], levelLengths[i], "KTX2 level " + i);
            if (supercompression == SUPERCOMPRESSION_NONE
                    && levelLengths[i] != levelRawLengths[i]) {
                throw new AssetFormatException("KTX2 level " + i + " has two sizes");
            }
            long expected = expectedLevelSize(i);
            if (expected > 0 && levelRawLengths[i] != expected) {
                throw new AssetFormatException(
                        "KTX2 level "
                                + i
                                + " holds "
                                + levelRawLengths[i]
                                + " bytes, expected "
                                + expected);
            }
        }
    }

    /**
     * Parses a KTX2 file held in {@code file} (which the returned object keeps referring to).
     *
     * @throws AssetFormatException if it is not a valid, supported KTX2 file
     */
    public static Ktx2 read(MemorySegment file) throws AssetFormatException {
        return new Ktx2(file);
    }

    /** Returns the Vulkan format ({@code VK_FORMAT_*}). */
    public int vkFormat() {
        return vkFormat;
    }

    /** Returns the size of the format's data type (1 for block-compressed formats). */
    public int typeSize() {
        return typeSize;
    }

    /** Returns the width of level 0 in pixels. */
    public int width() {
        return width;
    }

    /** Returns the height of level 0 in pixels (at least 1). */
    public int height() {
        return Math.max(1, height);
    }

    /** Returns the depth of level 0 (1 for 2D textures). */
    public int depth() {
        return Math.max(1, depth);
    }

    /** Returns the number of array layers (1 for non-arrays). */
    public int layers() {
        return Math.max(1, layers);
    }

    /** Returns {@code true} if the texture is an array texture. */
    public boolean isArray() {
        return layers > 0;
    }

    /** Returns 6 for cube maps, otherwise 1. */
    public int faces() {
        return faces;
    }

    /** Returns {@code true} for cube maps. */
    public boolean isCubeMap() {
        return faces == 6;
    }

    /** Returns the number of mip levels stored (at least 1). */
    public int levels() {
        return levelCount;
    }

    /** Returns the supercompression scheme ({@link #SUPERCOMPRESSION_NONE} or zstd). */
    public int supercompression() {
        return supercompression;
    }

    /** Returns the width of {@code level} in pixels. */
    public int levelWidth(int level) {
        return Math.max(1, width >> level);
    }

    /** Returns the height of {@code level} in pixels. */
    public int levelHeight(int level) {
        return Math.max(1, height() >> level);
    }

    /** Returns the depth of {@code level}. */
    public int levelDepth(int level) {
        return Math.max(1, depth() >> level);
    }

    /** Returns the stored (possibly compressed) bytes of {@code level} as a slice. */
    public MemorySegment storedLevel(int level) {
        return file.asSlice(levelOffsets[level], levelLengths[level]);
    }

    /** Returns the size of {@code level} once decoded (all layers and faces). */
    public long levelSize(int level) {
        return levelRawLengths[level];
    }

    /** Returns the sum of {@link #levelSize} over all levels. */
    public long totalSize() {
        long sum = 0;
        for (long n : levelRawLengths) {
            sum += n;
        }
        return sum;
    }

    /**
     * Writes {@code level}'s texel data (all layers and faces, in KTX2 order) to {@code dst} at
     * {@code dstOffset}: a copy, or a zstd decompression. Allocates nothing with native memory.
     *
     * @return the number of bytes written ({@link #levelSize})
     */
    public long decodeLevel(
            int level, MemorySegment dst, long dstOffset, ZstdDecompressor decompressor) {
        long raw = levelRawLengths[level];
        if (supercompression == SUPERCOMPRESSION_NONE) {
            MemorySegment.copy(file, levelOffsets[level], dst, dstOffset, raw);
            return raw;
        }
        long n =
                decompressor.decompress(
                        dst, dstOffset, raw, file, levelOffsets[level], levelLengths[level]);
        if (n != raw) {
            throw new IllegalStateException(
                    "KTX2 level " + level + " decompressed to " + n + " bytes, expected " + raw);
        }
        return n;
    }

    /**
     * Returns the value of a key/value entry as text (without the trailing NUL), or {@code null}.
     * Allocates.
     */
    public String keyValue(String key) {
        byte[] wanted = key.getBytes(StandardCharsets.UTF_8);
        long p = kvdOffset;
        long end = kvdOffset + kvdLength;
        while (p + 4 <= end) {
            long len = Integer.toUnsignedLong(file.get(U32, p));
            long start = p + 4;
            if (start + len > end) {
                return null;
            }
            long nul = -1;
            for (long i = start; i < start + len; i++) {
                if (file.get(ValueLayout.JAVA_BYTE, i) == 0) {
                    nul = i;
                    break;
                }
            }
            if (nul >= 0 && nul - start == wanted.length) {
                boolean match = true;
                for (int i = 0; i < wanted.length && match; i++) {
                    match = file.get(ValueLayout.JAVA_BYTE, start + i) == wanted[i];
                }
                if (match) {
                    long valueEnd = start + len;
                    if (valueEnd > nul + 1 && file.get(ValueLayout.JAVA_BYTE, valueEnd - 1) == 0) {
                        valueEnd--;
                    }
                    byte[] v =
                            file.asSlice(nul + 1, valueEnd - nul - 1)
                                    .toArray(ValueLayout.JAVA_BYTE);
                    return new String(v, StandardCharsets.UTF_8);
                }
            }
            p = start + ((len + 3) & ~3L);
        }
        return null;
    }

    /** Returns the expected decoded size of {@code level}, or 0 if the format is unknown here. */
    private long expectedLevelSize(int level) {
        int block = blockBytes(vkFormat);
        long w = levelWidth(level);
        long h = levelHeight(level);
        long images = (long) levelDepth(level) * layers() * faces;
        if (block > 0) {
            return ((w + 3) / 4) * ((h + 3) / 4) * block * images;
        }
        int texel = texelBytes(vkFormat);
        return texel > 0 ? w * h * texel * images : 0;
    }

    /** Returns the bytes per 4x4 block of a BCn format, or 0 for other formats. */
    public static int blockBytes(int vkFormat) {
        return switch (vkFormat) {
            case VK_FORMAT_BC1_RGB_UNORM_BLOCK,
                    VK_FORMAT_BC1_RGB_SRGB_BLOCK,
                    VK_FORMAT_BC1_RGBA_UNORM_BLOCK,
                    VK_FORMAT_BC1_RGBA_SRGB_BLOCK,
                    VK_FORMAT_BC4_UNORM_BLOCK,
                    VK_FORMAT_BC4_SNORM_BLOCK ->
                    8;
            case VK_FORMAT_BC3_UNORM_BLOCK,
                    VK_FORMAT_BC3_SRGB_BLOCK,
                    VK_FORMAT_BC5_UNORM_BLOCK,
                    VK_FORMAT_BC5_SNORM_BLOCK,
                    VK_FORMAT_BC6H_UFLOAT_BLOCK,
                    VK_FORMAT_BC6H_SFLOAT_BLOCK,
                    VK_FORMAT_BC7_UNORM_BLOCK,
                    VK_FORMAT_BC7_SRGB_BLOCK ->
                    16;
            default -> 0;
        };
    }

    /** Returns the bytes per texel of an uncompressed format known here, or 0. */
    public static int texelBytes(int vkFormat) {
        return switch (vkFormat) {
            case VK_FORMAT_R8_UNORM -> 1;
            case VK_FORMAT_R8G8_UNORM, VK_FORMAT_R16_SFLOAT -> 2;
            case VK_FORMAT_R8G8B8A8_UNORM,
                    VK_FORMAT_R8G8B8A8_SRGB,
                    VK_FORMAT_R16G16_SFLOAT,
                    VK_FORMAT_B10G11R11_UFLOAT_PACK32,
                    VK_FORMAT_E5B9G9R9_UFLOAT_PACK32 ->
                    4;
            case VK_FORMAT_R16G16B16A16_SFLOAT -> 8;
            case VK_FORMAT_R32G32B32A32_SFLOAT -> 16;
            default -> 0;
        };
    }

    /** Returns {@code true} for block-compressed formats. */
    public static boolean isBlockCompressed(int vkFormat) {
        return blockBytes(vkFormat) > 0;
    }

    /** Returns {@code true} for sRGB-encoded formats. */
    public static boolean isSrgb(int vkFormat) {
        return switch (vkFormat) {
            case VK_FORMAT_R8G8B8A8_SRGB,
                    VK_FORMAT_BC1_RGB_SRGB_BLOCK,
                    VK_FORMAT_BC1_RGBA_SRGB_BLOCK,
                    VK_FORMAT_BC3_SRGB_BLOCK,
                    VK_FORMAT_BC7_SRGB_BLOCK ->
                    true;
            default -> false;
        };
    }
}
