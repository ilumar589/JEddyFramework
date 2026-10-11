package dev.jeddy.render.gpu;

import static dev.jeddy.gl.ffi.GL46.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.jeddy.gl.GLDebug;
import dev.jeddy.render.GpuTestContext;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Growing a block-compressed array texture keeps every mip level of every layer, including the 2x2
 * and 1x1 levels that are smaller than a block (an Intel driver rejected copying those with {@code
 * glCopyImageSubData}; they go through a readback).
 */
@Tag("gpu")
class TextureCopyTest {
    private static GpuTestContext context;
    private static GpuDevice device;

    @BeforeAll
    static void init() {
        context = new GpuTestContext("texture copy test", 64);
        device = new GpuDevice(context.caps());
    }

    @AfterAll
    static void shutdown() {
        device.close();
        context.close();
    }

    @Test
    void copyLayersKeepsEveryLevelOfACompressedArray() {
        GpuTextures textures = device.textures();
        int format = GL_COMPRESSED_RGBA_BPTC_UNORM; // BC7: 16-byte 4x4 blocks, GL 4.2 core
        int w = 8;
        int levels = 4; // 8x8, 4x4, 2x2, 1x1: the last two are smaller than a block
        int layers = 3;
        long errors = GLDebug.errorCount();
        try (Arena arena = Arena.ofConfined()) {
            int small =
                    textures.create(
                            GpuTextures.KIND_2D_ARRAY, format, w, w, layers, levels, "small");
            // Distinct bytes per layer and level, one or four blocks each.
            MemorySegment data = arena.allocate(64, 16);
            for (int layer = 0; layer < layers; layer++) {
                for (int l = 0; l < levels; l++) {
                    int blocks = l == 0 ? 4 : 1;
                    for (int i = 0; i < blocks * 16; i++) {
                        data.set(ValueLayout.JAVA_BYTE, i, (byte) (layer * 40 + l * 8 + i));
                    }
                    textures.uploadLayer(small, l, layer, data.address(), blocks * 16);
                }
            }
            int big =
                    textures.create(
                            GpuTextures.KIND_2D_ARRAY, format, w, w, layers * 2, levels, "big");
            textures.copyLayers(small, big, layers);
            glFinish();
            MemorySegment back = arena.allocate(64, 16);
            for (int layer = 0; layer < layers; layer++) {
                for (int l = 0; l < levels; l++) {
                    int size = Math.max(1, w >> l);
                    int blocks = l == 0 ? 4 : 1;
                    back.fill((byte) 0);
                    glGetCompressedTextureSubImage(
                            textures.glName(big), l, 0, 0, layer, size, size, 1, blocks * 16, back);
                    for (int i = 0; i < blocks * 16; i++) {
                        assertEquals(
                                (byte) (layer * 40 + l * 8 + i),
                                back.get(ValueLayout.JAVA_BYTE, i),
                                "layer " + layer + " level " + l + " byte " + i);
                    }
                }
            }
            textures.destroy(small);
            textures.destroy(big);
        }
        assertEquals(errors, GLDebug.errorCount(), "no GL errors");
    }
}
