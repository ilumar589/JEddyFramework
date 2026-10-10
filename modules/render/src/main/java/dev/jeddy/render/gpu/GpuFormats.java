package dev.jeddy.render.gpu;

import static dev.jeddy.gl.ffi.GL46.*;

import dev.jeddy.asset.format.Ktx2;

/**
 * Maps KTX2 {@code vkFormat}s to OpenGL texture formats (DESIGN §9.2): BC4/BC5 (RGTC) and BC6H/BC7
 * (BPTC) are core, BC1/BC3 need {@code EXT_texture_compression_s3tc} (and {@code EXT_texture_sRGB}
 * for their sRGB variants).
 *
 * <p>Thread ownership: stateless, any thread.
 */
public final class GpuFormats {
    private GpuFormats() {}

    /** Returns the GL internal format for a {@code vkFormat}, or 0 if it is not supported. */
    public static int internalFormat(int vkFormat) {
        return switch (vkFormat) {
            case Ktx2.VK_FORMAT_R8_UNORM -> GL_R8;
            case Ktx2.VK_FORMAT_R8G8_UNORM -> GL_RG8;
            case Ktx2.VK_FORMAT_R8G8B8A8_UNORM -> GL_RGBA8;
            case Ktx2.VK_FORMAT_R8G8B8A8_SRGB -> GL_SRGB8_ALPHA8;
            case Ktx2.VK_FORMAT_R16_SFLOAT -> GL_R16F;
            case Ktx2.VK_FORMAT_R16G16_SFLOAT -> GL_RG16F;
            case Ktx2.VK_FORMAT_R16G16B16A16_SFLOAT -> GL_RGBA16F;
            case Ktx2.VK_FORMAT_R32G32B32A32_SFLOAT -> GL_RGBA32F;
            case Ktx2.VK_FORMAT_B10G11R11_UFLOAT_PACK32 -> GL_R11F_G11F_B10F;
            case Ktx2.VK_FORMAT_E5B9G9R9_UFLOAT_PACK32 -> GL_RGB9_E5;
            case Ktx2.VK_FORMAT_BC1_RGB_UNORM_BLOCK -> GL_COMPRESSED_RGB_S3TC_DXT1_EXT;
            case Ktx2.VK_FORMAT_BC1_RGB_SRGB_BLOCK -> GL_COMPRESSED_SRGB_S3TC_DXT1_EXT;
            case Ktx2.VK_FORMAT_BC1_RGBA_UNORM_BLOCK -> GL_COMPRESSED_RGBA_S3TC_DXT1_EXT;
            case Ktx2.VK_FORMAT_BC1_RGBA_SRGB_BLOCK -> GL_COMPRESSED_SRGB_ALPHA_S3TC_DXT1_EXT;
            case Ktx2.VK_FORMAT_BC3_UNORM_BLOCK -> GL_COMPRESSED_RGBA_S3TC_DXT5_EXT;
            case Ktx2.VK_FORMAT_BC3_SRGB_BLOCK -> GL_COMPRESSED_SRGB_ALPHA_S3TC_DXT5_EXT;
            case Ktx2.VK_FORMAT_BC4_UNORM_BLOCK -> GL_COMPRESSED_RED_RGTC1;
            case Ktx2.VK_FORMAT_BC4_SNORM_BLOCK -> GL_COMPRESSED_SIGNED_RED_RGTC1;
            case Ktx2.VK_FORMAT_BC5_UNORM_BLOCK -> GL_COMPRESSED_RG_RGTC2;
            case Ktx2.VK_FORMAT_BC5_SNORM_BLOCK -> GL_COMPRESSED_SIGNED_RG_RGTC2;
            case Ktx2.VK_FORMAT_BC6H_UFLOAT_BLOCK -> GL_COMPRESSED_RGB_BPTC_UNSIGNED_FLOAT;
            case Ktx2.VK_FORMAT_BC6H_SFLOAT_BLOCK -> GL_COMPRESSED_RGB_BPTC_SIGNED_FLOAT;
            case Ktx2.VK_FORMAT_BC7_UNORM_BLOCK -> GL_COMPRESSED_RGBA_BPTC_UNORM;
            case Ktx2.VK_FORMAT_BC7_SRGB_BLOCK -> GL_COMPRESSED_SRGB_ALPHA_BPTC_UNORM;
            default -> 0;
        };
    }

    /** Returns the pixel transfer format of an uncompressed internal format (or 0). */
    public static int transferFormat(int internalFormat) {
        return switch (internalFormat) {
            case GL_R8, GL_R16F, GL_R32F -> GL_RED;
            case GL_RG8, GL_RG16F, GL_RG32F -> GL_RG;
            case GL_R11F_G11F_B10F, GL_RGB9_E5 -> GL_RGB;
            case GL_RGBA8, GL_SRGB8_ALPHA8, GL_RGBA16F, GL_RGBA32F -> GL_RGBA;
            case GL_DEPTH_COMPONENT32F, GL_DEPTH_COMPONENT24 -> GL_DEPTH_COMPONENT;
            default -> 0;
        };
    }

    /** Returns the pixel transfer type of an uncompressed internal format (or 0). */
    public static int transferType(int internalFormat) {
        return switch (internalFormat) {
            case GL_R8, GL_RG8, GL_RGBA8, GL_SRGB8_ALPHA8 -> GL_UNSIGNED_BYTE;
            case GL_R16F, GL_RG16F, GL_RGBA16F -> GL_HALF_FLOAT;
            case GL_R32F, GL_RG32F, GL_RGBA32F, GL_DEPTH_COMPONENT32F -> GL_FLOAT;
            case GL_R11F_G11F_B10F -> GL_UNSIGNED_INT_10F_11F_11F_REV;
            case GL_RGB9_E5 -> GL_UNSIGNED_INT_5_9_9_9_REV;
            case GL_DEPTH_COMPONENT24 -> GL_UNSIGNED_INT;
            default -> 0;
        };
    }

    /** Returns {@code true} for block-compressed internal formats. */
    public static boolean isCompressed(int internalFormat) {
        return transferFormat(internalFormat) == 0 && internalFormat != 0;
    }

    /** Returns the extension a format needs beyond GL 4.6 core, or {@code null}. */
    public static String requiredExtension(int internalFormat) {
        return switch (internalFormat) {
            case GL_COMPRESSED_RGB_S3TC_DXT1_EXT,
                    GL_COMPRESSED_RGBA_S3TC_DXT1_EXT,
                    GL_COMPRESSED_RGBA_S3TC_DXT5_EXT ->
                    "GL_EXT_texture_compression_s3tc";
            case GL_COMPRESSED_SRGB_S3TC_DXT1_EXT,
                    GL_COMPRESSED_SRGB_ALPHA_S3TC_DXT1_EXT,
                    GL_COMPRESSED_SRGB_ALPHA_S3TC_DXT5_EXT ->
                    "GL_EXT_texture_sRGB";
            default -> null;
        };
    }
}
