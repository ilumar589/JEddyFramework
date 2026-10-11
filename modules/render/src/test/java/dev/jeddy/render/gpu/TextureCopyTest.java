package dev.jeddy.render.gpu;

import static dev.jeddy.gl.ffi.GL46.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.jeddy.gl.GLDebug;
import dev.jeddy.render.GpuTestContext;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Growing a block-compressed array texture keeps every mip level of every layer, including the tail
 * (levels smaller than a block, which Intel's Windows driver can neither copy on the GPU nor read
 * back): {@link GpuTextures#copyLayers} re-uploads the tail from the CPU mirror that {@link
 * GpuTextures#uploadLayer} keeps, and falls back to a readback only for a layer uploaded through a
 * pixel-unpack buffer.
 */
@Tag("gpu")
class TextureCopyTest {
    private static final int FORMAT = GL_COMPRESSED_RGBA_BPTC_UNORM; // BC7: 16-byte blocks, core
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
    void copyLayersKeepsEveryLevelOfASquareArray() {
        // 8x8, 4x4, 2x2, 1x1: the last two are the tail.
        assertCopyKeepsEveryLevel(8, 8, 4, false);
    }

    @Test
    void copyLayersKeepsEveryLevelOfAThinArray() {
        // 8x2 (two blocks), 4x1, 2x1, 1x1: every level is in the tail, the first one two blocks.
        assertCopyKeepsEveryLevel(8, 2, 4, false);
    }

    @Test
    void layersUploadedThroughABufferAreReadBack() {
        assertCopyKeepsEveryLevel(8, 8, 4, true);
    }

    @Test
    void tailUploadsCheckTheirSizeAndAllocateNothing() {
        GpuTextures textures = device.textures();
        int tex = textures.create(GpuTextures.KIND_2D_ARRAY, FORMAT, 8, 8, 2, 4, "sized");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment block = arena.allocate(64, 16);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> textures.uploadLayer(tex, 3, 0, block.address(), 64),
                    "a 1x1 BC7 level is one block");
            long address = block.address();
            Allocations.assertNoAllocation(
                    "uploadLayer",
                    25,
                    500,
                    () -> {
                        textures.uploadLayer(tex, 0, 1, address, 64);
                        textures.uploadLayer(tex, 2, 1, address, 16);
                        textures.uploadLayer(tex, 3, 1, address, 16);
                    });
        }
        textures.destroy(tex);
    }

    /**
     * Fills {@code layers} layers of a {@code w}x{@code h} array with distinct bytes per layer,
     * level and position, copies them into an array twice the size, and checks every byte.
     */
    private static void assertCopyKeepsEveryLevel(int w, int h, int levels, boolean viaBuffer) {
        GpuTextures textures = device.textures();
        int layers = 3;
        long errors = GLDebug.errorCount();
        try (Arena arena = Arena.ofConfined();
                UploadRing ring = new UploadRing(device, 1 << 16, 1 << 16)) {
            int small =
                    textures.create(
                            GpuTextures.KIND_2D_ARRAY, FORMAT, w, h, layers, levels, "small");
            MemorySegment data = arena.allocate(256, 16);
            for (int layer = 0; layer < layers; layer++) {
                for (int l = 0; l < levels; l++) {
                    int bytes = levelBytes(w, h, l);
                    for (int i = 0; i < bytes; i++) {
                        data.set(ValueLayout.JAVA_BYTE, i, pattern(layer, l, i));
                    }
                    if (viaBuffer) {
                        long offset = ring.reserve(bytes, 16);
                        MemorySegment.copy(data, 0, ring.memory(), offset, bytes);
                        ring.copyToTextureLayer(small, l, layer, offset, bytes);
                    } else {
                        textures.uploadLayer(small, l, layer, data.address(), bytes);
                    }
                }
            }
            int big =
                    textures.create(
                            GpuTextures.KIND_2D_ARRAY, FORMAT, w, h, layers * 2, levels, "big");
            textures.copyLayers(small, big, layers);
            // Grow once more: the mirror (or the readback) must carry over too.
            int bigger =
                    textures.create(
                            GpuTextures.KIND_2D_ARRAY, FORMAT, w, h, layers * 4, levels, "bigger");
            textures.copyLayers(big, bigger, layers);
            glFinish();
            MemorySegment back = arena.allocate(256, 16);
            for (int layer = 0; layer < layers; layer++) {
                for (int l = 0; l < levels; l++) {
                    int bytes = levelBytes(w, h, l);
                    back.fill((byte) 0);
                    glGetCompressedTextureSubImage(
                            textures.glName(bigger),
                            l,
                            0,
                            0,
                            layer,
                            Math.max(1, w >> l),
                            Math.max(1, h >> l),
                            1,
                            bytes,
                            back);
                    for (int i = 0; i < bytes; i++) {
                        assertEquals(
                                pattern(layer, l, i),
                                back.get(ValueLayout.JAVA_BYTE, i),
                                "layer " + layer + " level " + l + " byte " + i);
                    }
                }
            }
            textures.destroy(small);
            textures.destroy(big);
            textures.destroy(bigger);
        }
        assertEquals(errors, GLDebug.errorCount(), "no GL errors");
    }

    private static int levelBytes(int w, int h, int level) {
        int lw = Math.max(1, w >> level);
        int lh = Math.max(1, h >> level);
        return ((lw + 3) >> 2) * ((lh + 3) >> 2) * 16;
    }

    private static byte pattern(int layer, int level, int i) {
        return (byte) (layer * 67 + level * 13 + i);
    }
}
