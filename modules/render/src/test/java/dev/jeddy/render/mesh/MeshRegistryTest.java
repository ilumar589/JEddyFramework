package dev.jeddy.render.mesh;

import static dev.jeddy.gl.ffi.GL46.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.format.MeshData;
import dev.jeddy.asset.format.MeshWriter;
import dev.jeddy.gl.GLDebug;
import dev.jeddy.gl.GLShaders;
import dev.jeddy.render.GpuTestContext;
import dev.jeddy.render.gpu.GpuDevice;
import dev.jeddy.render.gpu.UploadRing;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/** Mega buffers, sub-allocation and drawing by base vertex (TASKS M5-07). */
@Tag("gpu")
class MeshRegistryTest {
    private static GpuTestContext context;
    private static GpuDevice device;
    private static final Arena ARENA = Arena.ofShared();

    @BeforeAll
    static void init() {
        context = new GpuTestContext("mesh registry test", 64);
        device = new GpuDevice(context.caps());
    }

    @AfterAll
    static void shutdown() {
        device.close();
        context.close();
        ARENA.close();
    }

    /**
     * A quad from (x0, y0) to (x1, y1) in clip space, with a two-triangle LOD 0 and a one-triangle
     * LOD 1.
     */
    private static MeshData quad(float x0, float y0, float x1, float y1) {
        byte[] bytes =
                new MeshWriter()
                        .positions(new float[] {x0, y0, 0, x1, y0, 0, x1, y1, 0, x0, y1, 0})
                        .attributes(
                                new float[] {0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1}, null, null, null)
                        .indices(new int[] {0, 1, 2, 0, 2, 3, 0, 1, 2})
                        .submesh(0, 0, 4)
                        .lod(0, new int[] {0}, new int[] {6})
                        .lod(1, new int[] {6}, new int[] {3})
                        .toBytes();
        MemorySegment s = ARENA.allocate(bytes.length, 64);
        MemorySegment.copy(bytes, 0, s, ValueLayout.JAVA_BYTE, 0, bytes.length);
        try {
            return MeshData.read(s);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void meshesShareTheMegaBuffersAndDrawByBaseVertex() {
        long errors = GLDebug.errorCount();
        try (UploadRing ring = new UploadRing(device, 1 << 16, 1 << 16);
                MeshRegistry registry = new MeshRegistry(device, ring, 6, 8)) {
            ring.beginFrame();
            int left = registry.add(quad(-1, -1, 0, 1));
            int right = registry.add(quad(0, -1, 1, 1)); // grows both buffers
            ring.endFrame();
            assertEquals(2, registry.count());
            assertTrue(registry.vertexCapacity() >= 8);
            assertEquals(4, registry.baseVertex(right, 0) - registry.baseVertex(left, 0));
            assertEquals(2, registry.lodCount(right));
            assertEquals(3, registry.indexCount(right, 1, 0));
            assertEquals(registry.firstIndex(right, 0, 0) + 6, registry.firstIndex(right, 1, 0));
            assertEquals(0.5f, registry.sphere(right, 0));

            int program =
                    GLShaders.program(
                            "#version 460 core\n"
                                + "layout(location = 0) in vec3 p;\n"
                                + "layout(location = 1) in vec4 q;\n"
                                + "void main() { gl_Position = vec4(p, 1.0); }\n",
                            "#version 460 core\n"
                                + "layout(location = 0) out vec4 c;\n"
                                + "void main() { c = vec4(1.0, 1.0, 0.0, 1.0); }\n");
            int size = 64;
            glViewport(0, 0, size, size);
            MemorySegment clear = ARENA.allocateFrom(ValueLayout.JAVA_FLOAT, 0f, 0f, 0f, 1f);
            glClearNamedFramebufferfv(0, GL_COLOR, 0, clear);
            glUseProgram(program);
            glBindVertexArray(registry.vertexArray());
            // Left quad at LOD 0 (full), right quad at LOD 1 (lower-right triangle only).
            glDrawElementsBaseVertex(
                    GL_TRIANGLES,
                    registry.indexCount(left, 0, 0),
                    GL_UNSIGNED_INT,
                    4L * registry.firstIndex(left, 0, 0),
                    registry.baseVertex(left, 0));
            glDrawElementsBaseVertex(
                    GL_TRIANGLES,
                    registry.indexCount(right, 1, 0),
                    GL_UNSIGNED_INT,
                    4L * registry.firstIndex(right, 1, 0),
                    registry.baseVertex(right, 0));
            MemorySegment px = ARENA.allocate(4);
            glReadPixels(16, 48, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px);
            assertEquals(
                    255, Byte.toUnsignedInt(px.get(ValueLayout.JAVA_BYTE, 0)), "left quad, top");
            glReadPixels(56, 8, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px);
            assertEquals(
                    255,
                    Byte.toUnsignedInt(px.get(ValueLayout.JAVA_BYTE, 0)),
                    "right LOD 1, lower right");
            glReadPixels(40, 56, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px);
            assertEquals(
                    0,
                    Byte.toUnsignedInt(px.get(ValueLayout.JAVA_BYTE, 0)),
                    "right LOD 1 has no upper-left half");
            glDeleteProgram(program);

            registry.remove(left);
            int again = registry.add(quad(-1, -1, 0, 0));
            assertNotEquals(left, again);
            assertEquals(registry.baseVertex(again, 0), 0, "the freed range is reused");
            assertTrue(registry.uploadedBytes() > 0);
        }
        assertEquals(errors, GLDebug.errorCount());
    }
}
