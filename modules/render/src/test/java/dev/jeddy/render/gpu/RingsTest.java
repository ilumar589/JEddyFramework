package dev.jeddy.render.gpu;

import static dev.jeddy.gl.ffi.GL46.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.Arrays;

/** Persistent mapped rings (TASKS M5-04). */
@Tag("gpu")
class RingsTest {
    private static GpuTestContext context;
    private static GpuDevice device;
    private static final Arena ARENA = Arena.ofShared();

    @BeforeAll
    static void init() {
        context = new GpuTestContext("rings test", 32);
        device = new GpuDevice(context.caps());
    }

    @AfterAll
    static void shutdown() {
        device.close();
        context.close();
        ARENA.close();
    }

    @Test
    void frameRingHandsOutDisjointRangesPerFrame() throws Exception {
        try (FrameRing ring = new FrameRing(device, 64 << 10, 3, "frame ring")) {
            for (int f = 0; f < 10; f++) {
                ring.beginFrame();
                long a = ring.allocate(1000, 256);
                long b = ring.allocate(1000, 256);
                assertEquals(0, a % 256);
                assertTrue(b >= a + 1000);
                assertEquals((f % 3) * ring.regionSize(), a);
                ring.memory().set(ValueLayout.JAVA_INT, b, f);
                ring.endFrame();
            }
            ring.beginFrame();
            assertEquals(-1, ring.allocate(ring.regionSize() + 1, 16));

            // Workers carve ranges concurrently.
            long[][] got = new long[4][1000];
            Thread[] threads = new Thread[4];
            for (int t = 0; t < 4; t++) {
                int id = t;
                threads[t] =
                        new Thread(
                                () -> {
                                    for (int i = 0; i < 1000; i++) {
                                        got[id][i] = ring.allocate(16, 16);
                                    }
                                });
                threads[t].start();
            }
            for (Thread t : threads) {
                t.join();
            }
            long[] all = Arrays.stream(got).flatMapToLong(Arrays::stream).sorted().toArray();
            for (int i = 1; i < all.length; i++) {
                assertTrue(all[i] >= all[i - 1] + 16, "disjoint");
            }
            ring.endFrame();
            Allocations.assertNoAllocation(
                    "frame ring",
                    () -> {
                        ring.beginFrame();
                        ring.allocate(64, 64);
                        ring.endFrame();
                    });
            // llvmpipe signals a fence as soon as it is placed; a real GPU's command processor can
            // lag the CPU by more than three microsecond-long empty frames (one stall in 200k
            // frames on an Intel laptop), so stalls are only required to stay rare.
            assertTrue(ring.stalls() <= 1000, "stalls: " + ring.stalls());
        }
    }

    @Test
    void uploadRingCopiesWithinBudgetAndReclaims() {
        long errors = GLDebug.errorCount();
        try (UploadRing ring = new UploadRing(device, 4096, 1024)) {
            int dst = device.buffers().create(4096, 0, "upload target");
            ring.beginFrame();
            long off = ring.reserve(512, 16);
            assertTrue(off >= 0);
            for (int i = 0; i < 128; i++) {
                ring.memory().setAtIndex(ValueLayout.JAVA_INT, off / 4 + i, i + 1);
            }
            ring.copyToBuffer(off, device.buffers().glName(dst), 1024, 512);
            assertTrue(ring.reserve(512, 16) >= 0);
            assertEquals(-1, ring.reserve(16, 16), "budget spent");
            ring.endFrame();
            MemorySegment back = ARENA.allocate(512);
            glGetNamedBufferSubData(device.buffers().glName(dst), 1024, 512, back.address());
            assertEquals(128, back.getAtIndex(ValueLayout.JAVA_INT, 127));

            // BC4 through the pixel-unpack buffer.
            ring.beginFrame();
            int tex = device.textures().create2D(GL_COMPRESSED_RED_RGTC1, 4, 4, 1, "pbo bc4");
            long t = ring.reserve(8, 16);
            MemorySegment block = ring.memory().asSlice(t, 8);
            block.fill((byte) 0);
            block.set(ValueLayout.JAVA_BYTE, 0, (byte) 99);
            ring.copyToTexture(tex, 0, t, 8);
            ring.endFrame();
            MemorySegment pixels = ARENA.allocate(16);
            glGetTextureImage(
                    device.textures().glName(tex),
                    0,
                    GL_RED,
                    GL_UNSIGNED_BYTE,
                    16,
                    pixels.address());
            assertEquals(99, Byte.toUnsignedInt(pixels.get(ValueLayout.JAVA_BYTE, 5)));

            // Fill the ring over several frames: space comes back once fences signal.
            for (int f = 0; f < 50; f++) {
                ring.beginFrame();
                assertTrue(ring.reserve(1024, 16) >= 0, "frame " + f);
                ring.endFrame();
                glFinish();
            }
            assertTrue(ring.inFlight() <= 4096);
            assertFalse(ring.uploadedBytes() < 50 * 1024);
            device.textures().destroy(tex);
            device.buffers().destroy(dst);
        }
        assertEquals(errors, GLDebug.errorCount());
    }
}
