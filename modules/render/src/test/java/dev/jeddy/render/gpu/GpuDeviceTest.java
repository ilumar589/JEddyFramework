package dev.jeddy.render.gpu;

import static dev.jeddy.gl.ffi.GL46.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.format.Ktx2;
import dev.jeddy.core.handle.Handles;
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

/** The GL resource layer and state cache (TASKS M5-03) on a real context. */
@Tag("gpu")
class GpuDeviceTest {
    private static GpuTestContext context;
    private static GpuDevice device;
    private static final Arena ARENA = Arena.ofShared();

    @BeforeAll
    static void init() {
        context = new GpuTestContext("gpu device test", 64);
        device = new GpuDevice(context.caps());
    }

    @AfterAll
    static void shutdown() {
        device.close();
        context.close();
        ARENA.close();
    }

    @Test
    void buffersMapUpdateAndReadBack() {
        long errors = GLDebug.errorCount();
        GpuBuffers buffers = device.buffers();
        int mapped = buffers.create(256, GpuBuffers.MAPPED, "mapped");
        MemorySegment map = buffers.mapping(mapped);
        assertEquals(256, map.byteSize());
        for (int i = 0; i < 64; i++) {
            map.setAtIndex(ValueLayout.JAVA_INT, i, i * 3);
        }
        glFinish();
        MemorySegment back = ARENA.allocate(256);
        glGetNamedBufferSubData(buffers.glName(mapped), 0, 256, back.address());
        assertEquals(63 * 3, back.getAtIndex(ValueLayout.JAVA_INT, 63));

        int dynamic = buffers.create(64, GpuBuffers.DYNAMIC, "dynamic");
        MemorySegment src = ARENA.allocateFrom(ValueLayout.JAVA_FLOAT, 1f, 2f, 3f, 4f);
        buffers.update(dynamic, 16, src);
        glGetNamedBufferSubData(buffers.glName(dynamic), 16, 16, back.address());
        assertEquals(3f, back.getAtIndex(ValueLayout.JAVA_FLOAT, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> buffers.update(dynamic, 60, src));
        buffers.destroy(mapped);
        assertThrows(IllegalArgumentException.class, () -> buffers.glName(mapped));
        assertEquals(errors, GLDebug.errorCount());
    }

    @Test
    void compressedAndPlainTexturesUploadPerLevel() {
        long errors = GLDebug.errorCount();
        GpuTextures textures = device.textures();
        // BC4: 8x8 = 4 blocks; red0 = 200 and every index 0 decodes to 200.
        MemorySegment bc4 = ARENA.allocate(32);
        for (int b = 0; b < 4; b++) {
            bc4.set(ValueLayout.JAVA_BYTE, b * 8, (byte) 200);
            bc4.set(ValueLayout.JAVA_BYTE, b * 8 + 1, (byte) 10);
        }
        int mask = textures.create2D(GL_COMPRESSED_RED_RGTC1, 8, 8, 1, "bc4 mask");
        textures.uploadLevel(mask, 0, bc4.address(), 32);
        MemorySegment pixels = ARENA.allocate(64);
        glGetTextureImage(textures.glName(mask), 0, GL_RED, GL_UNSIGNED_BYTE, 64, pixels.address());
        for (int i = 0; i < 64; i++) {
            assertEquals(200, Byte.toUnsignedInt(pixels.get(ValueLayout.JAVA_BYTE, i)));
        }

        int rgba = textures.create2D(GL_RGBA8, 4, 4, 3, "rgba mips");
        MemorySegment level1 = ARENA.allocate(2 * 2 * 4);
        level1.fill((byte) 77);
        textures.uploadLevel(rgba, 1, level1.address(), 16);
        glGetTextureImage(
                textures.glName(rgba), 1, GL_RGBA, GL_UNSIGNED_BYTE, 16, pixels.address());
        assertEquals(77, pixels.get(ValueLayout.JAVA_BYTE, 15));
        assertEquals(3, textures.levels(rgba));

        int cube = textures.create(GpuTextures.KIND_CUBE, GL_RGBA16F, 8, 8, 1, 4, "cube");
        MemorySegment faces = ARENA.allocate(8 * 8 * 8 * 6);
        textures.uploadLevel(cube, 0, faces.address(), (int) faces.byteSize());
        int array =
                textures.create(
                        GpuTextures.KIND_2D_ARRAY,
                        GL_COMPRESSED_RGBA_BPTC_UNORM,
                        16,
                        16,
                        3,
                        1,
                        "bc7 array");
        MemorySegment blocks = ARENA.allocate(16 * 16 * 3);
        textures.uploadLevel(array, 0, blocks.address(), (int) blocks.byteSize());
        assertEquals(
                GL_COMPRESSED_SRGB_ALPHA_BPTC_UNORM,
                GpuFormats.internalFormat(Ktx2.VK_FORMAT_BC7_SRGB_BLOCK));
        assertEquals(0, GpuFormats.internalFormat(12345));
        assertTrue(GpuFormats.isCompressed(GL_COMPRESSED_RG_RGTC2));
        assertEquals(errors, GLDebug.errorCount());
        textures.destroy(cube);
        textures.destroy(array);
    }

    @Test
    void samplersAreCachedByDescription() {
        GpuSamplers samplers = device.samplers();
        int a = samplers.get(SamplerDesc.TRILINEAR_REPEAT);
        assertEquals(a, samplers.get(SamplerDesc.TRILINEAR_REPEAT));
        int aniso = samplers.get(SamplerDesc.withAnisotropy(SamplerDesc.TRILINEAR_REPEAT, 8));
        assertNotEquals(a, aniso);
        int shadow = samplers.get(SamplerDesc.SHADOW);
        MemorySegment out = ARENA.allocate(4);
        glGetSamplerParameteriv(shadow, GL_TEXTURE_COMPARE_FUNC, out);
        assertEquals(GL_GEQUAL, out.get(ValueLayout.JAVA_INT, 0));
        glGetSamplerParameterfv(aniso, GL_TEXTURE_MAX_ANISOTROPY, out);
        assertTrue(out.get(ValueLayout.JAVA_FLOAT, 0) > 1f);
    }

    @Test
    void framebuffersRenderOffscreen() {
        GpuTextures textures = device.textures();
        int color = textures.create2D(GL_RGBA8, 32, 32, 1, "color");
        int depth = textures.create2D(GL_DEPTH_COMPONENT32F, 32, 32, 1, "depth");
        int fb = device.framebuffers().create(new int[] {color}, depth, "offscreen");
        StateCache state = device.state();
        state.bindFramebuffer(device.framebuffers().glName(fb));
        state.viewport(0, 0, 32, 32);
        MemorySegment clear = ARENA.allocateFrom(ValueLayout.JAVA_FLOAT, 0f, 1f, 0f, 1f);
        glClearNamedFramebufferfv(device.framebuffers().glName(fb), GL_COLOR, 0, clear);
        MemorySegment pixels = ARENA.allocate(32 * 32 * 4);
        glGetTextureImage(
                textures.glName(color),
                0,
                GL_RGBA,
                GL_UNSIGNED_BYTE,
                (int) pixels.byteSize(),
                pixels.address());
        assertEquals(255, Byte.toUnsignedInt(pixels.get(ValueLayout.JAVA_BYTE, 1)));
        assertEquals(0, Byte.toUnsignedInt(pixels.get(ValueLayout.JAVA_BYTE, 0)));
        state.bindFramebuffer(0);
        assertThrows(
                IllegalStateException.class,
                () -> device.framebuffers().create(new int[] {depth}, Handles.NULL, "bad"));
        device.framebuffers().destroy(fb);
    }

    @Test
    void stateCacheSkipsRedundantCalls() {
        StateCache state = device.state();
        state.invalidate();
        state.apply(PipelineState.OPAQUE);
        long before = state.issuedCalls();
        state.apply(PipelineState.OPAQUE);
        assertEquals(before, state.issuedCalls(), "no calls for the same state");
        MemorySegment out = ARENA.allocate(16);
        glGetIntegerv(GL_DEPTH_FUNC, out);
        assertEquals(GL_GEQUAL, out.get(ValueLayout.JAVA_INT, 0));
        assertTrue(glIsEnabled(GL_CULL_FACE));
        state.apply(PipelineState.TRANSPARENT);
        assertTrue(glIsEnabled(GL_BLEND));
        assertTrue(!glIsEnabled(GL_CULL_FACE));
        glGetIntegerv(GL_DEPTH_WRITEMASK, out);
        assertEquals(0, out.get(ValueLayout.JAVA_INT, 0));
        long t0 = state.issuedCalls();
        state.apply(
                PipelineState.withBlend(PipelineState.TRANSPARENT, PipelineState.BLEND_ADDITIVE));
        assertEquals(t0 + 2, state.issuedCalls(), "only the blend function");
        state.apply(PipelineState.DEPTH_ONLY);
        glGetBooleanv(GL_COLOR_WRITEMASK, out);
        assertEquals(0, out.get(ValueLayout.JAVA_BYTE, 0));

        int tex = device.textures().create2D(GL_RGBA8, 4, 4, 1, "bound");
        int name = device.textures().glName(tex);
        int sampler = device.samplers().get(SamplerDesc.LINEAR_CLAMP);
        Allocations.assertNoAllocation(
                "state cache",
                () -> {
                    state.apply(PipelineState.OPAQUE);
                    state.bindTexture(3, name);
                    state.bindSampler(3, sampler);
                    state.apply(PipelineState.TRANSPARENT);
                    state.bindTexture(3, 0);
                });
        long t1 = state.issuedCalls();
        state.bindTexture(3, 0);
        state.bindSampler(3, sampler);
        assertEquals(t1, state.issuedCalls());
        state.apply(PipelineState.OPAQUE);
        device.textures().destroy(tex);
    }
}
