package dev.jeddy.gl;

import java.util.Set;

/**
 * What the current OpenGL context offers, read once by {@link GLLoader#init} (DESIGN §9.1).
 *
 * <p>Thread ownership: immutable.
 *
 * @param major context major version
 * @param minor context minor version
 * @param version {@code GL_VERSION} string
 * @param vendor {@code GL_VENDOR} string
 * @param renderer {@code GL_RENDERER} string
 * @param glslVersion {@code GL_SHADING_LANGUAGE_VERSION} string
 * @param driver the driver family, for workarounds
 * @param debugContext whether the context has {@code GL_CONTEXT_FLAG_DEBUG_BIT}
 * @param extensions every extension name the context reports
 * @param maxTextureSize {@code GL_MAX_TEXTURE_SIZE}
 * @param maxArrayTextureLayers {@code GL_MAX_ARRAY_TEXTURE_LAYERS}
 * @param maxUniformBlockSize {@code GL_MAX_UNIFORM_BLOCK_SIZE}
 * @param maxShaderStorageBlockSize {@code GL_MAX_SHADER_STORAGE_BLOCK_SIZE}
 * @param uniformBufferOffsetAlignment {@code GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT}
 * @param shaderStorageBufferOffsetAlignment {@code GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT}
 * @param maxColorAttachments {@code GL_MAX_COLOR_ATTACHMENTS}
 * @param maxSamples {@code GL_MAX_SAMPLES}
 * @param maxVertexAttribs {@code GL_MAX_VERTEX_ATTRIBS}
 * @param maxComputeWorkGroupInvocations {@code GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS}
 * @param maxAnisotropy {@code GL_MAX_TEXTURE_MAX_ANISOTROPY}
 */
public record GLCapabilities(
        int major,
        int minor,
        String version,
        String vendor,
        String renderer,
        String glslVersion,
        Driver driver,
        boolean debugContext,
        Set<String> extensions,
        int maxTextureSize,
        int maxArrayTextureLayers,
        int maxUniformBlockSize,
        long maxShaderStorageBlockSize,
        int uniformBufferOffsetAlignment,
        int shaderStorageBufferOffsetAlignment,
        int maxColorAttachments,
        int maxSamples,
        int maxVertexAttribs,
        int maxComputeWorkGroupInvocations,
        float maxAnisotropy) {

    /** Driver families that sometimes need workarounds. */
    public enum Driver {
        /** NVIDIA proprietary. */
        NVIDIA,
        /** AMD proprietary (Windows). */
        AMD,
        /** Intel (Windows). */
        INTEL,
        /** Mesa hardware drivers (radeonsi, iris, nouveau...). */
        MESA,
        /** Mesa llvmpipe or another software rasterizer (CI). */
        SOFTWARE,
        /** Anything else. */
        OTHER
    }

    /** Copies the extension set. */
    public GLCapabilities {
        extensions = Set.copyOf(extensions);
    }

    /** Returns {@code true} if the context reports {@code extension}. */
    public boolean has(String extension) {
        return extensions.contains(extension);
    }

    /** Returns {@code true} if the context version is at least {@code major.minor}. */
    public boolean atLeast(int major, int minor) {
        return this.major > major || (this.major == major && this.minor >= minor);
    }

    static Driver classify(String vendor, String renderer, String version) {
        String v = vendor.toLowerCase(java.util.Locale.ROOT);
        String r = renderer.toLowerCase(java.util.Locale.ROOT);
        if (r.contains("llvmpipe") || r.contains("softpipe") || r.contains("swrast")) {
            return Driver.SOFTWARE;
        }
        if (version.contains("Mesa") || v.contains("mesa") || v.contains("x.org")) {
            return Driver.MESA;
        }
        if (v.contains("nvidia")) {
            return Driver.NVIDIA;
        }
        if (v.contains("ati") || v.contains("amd")) {
            return Driver.AMD;
        }
        if (v.contains("intel")) {
            return Driver.INTEL;
        }
        return Driver.OTHER;
    }
}
