package dev.jeddy.gl;

import static dev.jeddy.gl.ffi.GL46.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.gl.ffi.GLLinker;
import dev.jeddy.testing.Allocations;
import dev.jeddy.window.Platform;
import dev.jeddy.window.VSync;
import dev.jeddy.window.Window;
import dev.jeddy.window.WindowConfig;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Loader, capabilities, debug output and a DSA hello triangle read back from the framebuffer (TASKS
 * M3-02, M3-03, M3-04). Needs a GL 4.6 driver; CI runs it under Mesa llvmpipe.
 */
@Tag("gpu")
class GLTest {
    private static final int SIZE = 64;
    private static Window window;
    private static GLCapabilities caps;

    @BeforeAll
    static void init() {
        System.setProperty("jeddy.gl.critical", "false"); // the debug callback needs it off
        Platform.init("Jeddy GL tests", "0.1", "dev.jeddy.tests.gl");
        window =
                Window.create(
                        new WindowConfig()
                                .hidden(true)
                                .size(SIZE, SIZE)
                                .minimumSize(16, 16)
                                .glDebug(true)
                                .vsync(VSync.OFF));
        caps = GLLoader.init(window::glProcAddress);
        GLDebug.enable(true);
    }

    @AfterAll
    static void shutdown() {
        window.close();
        Platform.shutdown();
    }

    @Test
    void capabilitiesDescribeTheContext() {
        assertTrue(caps.atLeast(4, 6), caps.version());
        assertFalse(caps.renderer().isEmpty());
        assertTrue(caps.extensions().size() > 50, "extensions: " + caps.extensions().size());
        assertTrue(caps.has("GL_ARB_direct_state_access"));
        assertTrue(caps.maxTextureSize() >= 8192);
        assertTrue(caps.uniformBufferOffsetAlignment() > 0);
        assertTrue(caps.maxShaderStorageBlockSize() >= 1 << 24);
        assertTrue(GLLoader.hasCommand("glMultiDrawElementsIndirectCount"));
    }

    @Test
    void debugOutputReportsErrorsWithoutThrowing() {
        long before = GLDebug.errorCount();
        glEnable(0x7FFF_FFFF); // GL_INVALID_ENUM
        assertEquals(GL_INVALID_ENUM, glGetError());
        assertTrue(GLDebug.errorCount() > before, "the synchronous callback saw the error");
        GLDebug.pushGroup("test group");
        GLDebug.popGroup();
    }

    @Test
    void missingCommandsThrowOnlyWhenCalled() {
        // GLX resolves any name to a stub, so extension commands are gated on the extension.
        MethodHandle missing =
                GLLinker.link(
                        "glMakeTextureHandleResidentARB",
                        FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG),
                        false,
                        "GL_JEDDY_not_an_extension");
        assertThrows(
                UnsupportedOperationException.class,
                () -> {
                    try {
                        missing.invokeExact(0L);
                    } catch (Throwable t) {
                        throw GLLinker.rethrow(t);
                    }
                });
        assertThrows(
                IllegalStateException.class,
                () -> {
                    throw GLLinker.rethrow(new Exception("checked"));
                });
    }

    @Test
    void helloTriangleRendersWithDsa() {
        long errorsBefore = GLDebug.errorCount();
        try (Arena arena = Arena.ofConfined()) {
            int program = GLShaders.program(TRIANGLE_VS, TRIANGLE_FS);
            float[] vertices = {-0.8f, -0.8f, 0.8f, -0.8f, 0f, 0.8f};
            MemorySegment ids = arena.allocate(ValueLayout.JAVA_INT, 2);
            glCreateBuffers(1, ids);
            int vbo = ids.get(ValueLayout.JAVA_INT, 0);
            glNamedBufferStorage(
                    vbo,
                    vertices.length * 4L,
                    arena.allocateFrom(ValueLayout.JAVA_FLOAT, vertices),
                    0);
            glCreateVertexArrays(1, ids);
            int vao = ids.get(ValueLayout.JAVA_INT, 0);
            glVertexArrayVertexBuffer(vao, 0, vbo, 0, 8);
            glEnableVertexArrayAttrib(vao, 0);
            glVertexArrayAttribFormat(vao, 0, 2, GL_FLOAT, false, 0);
            glVertexArrayAttribBinding(vao, 0, 0);
            GLDebug.label(GL_BUFFER, vbo, "triangle vertices");

            short[] indices = {0, 1, 2};
            glCreateBuffers(1, ids);
            int ibo = ids.get(ValueLayout.JAVA_INT, 0);
            glNamedBufferStorage(ibo, 6, arena.allocateFrom(ValueLayout.JAVA_SHORT, indices), 0);
            glVertexArrayElementBuffer(vao, ibo);

            MemorySegment clear = arena.allocateFrom(ValueLayout.JAVA_FLOAT, 0.1f, 0.2f, 0.3f, 1f);
            glViewport(0, 0, window.pixelWidth(), window.pixelHeight());
            glClearNamedFramebufferfv(0, GL_COLOR, 0, clear);
            glUseProgram(program);
            glBindVertexArray(vao);
            glDrawElements(GL_TRIANGLES, 3, GL_UNSIGNED_SHORT, 0L); // offset overload
            assertEquals(GL_NO_ERROR, glGetError());

            MemorySegment pixel = arena.allocate(4);
            glReadPixels(SIZE / 2, SIZE / 2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
            assertEquals(255, Byte.toUnsignedInt(pixel.get(ValueLayout.JAVA_BYTE, 0)), "red");
            assertEquals(128, Byte.toUnsignedInt(pixel.get(ValueLayout.JAVA_BYTE, 1)), 2, "green");
            glReadPixels(1, SIZE - 2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
            assertEquals(26, Byte.toUnsignedInt(pixel.get(ValueLayout.JAVA_BYTE, 0)), 1, "clear");
            assertEquals(77, Byte.toUnsignedInt(pixel.get(ValueLayout.JAVA_BYTE, 2)), 1, "clear");

            // Per-frame calls allocate nothing (offset overload, no MemorySegment boxing).
            Allocations.assertNoAllocation(
                    "draw",
                    10,
                    500,
                    () -> {
                        glUseProgram(program);
                        glBindVertexArray(vao);
                        glDrawElements(GL_TRIANGLES, 3, GL_UNSIGNED_SHORT, 0L);
                    });
            window.swap();
            assertEquals(errorsBefore, GLDebug.errorCount(), "debug errors");

            ids.set(ValueLayout.JAVA_INT, 0, vbo);
            ids.set(ValueLayout.JAVA_INT, 4, ibo);
            glDeleteBuffers(2, ids);
            ids.set(ValueLayout.JAVA_INT, 0, vao);
            glDeleteVertexArrays(1, ids);
            glDeleteProgram(program);
        }
    }

    private static final String TRIANGLE_VS =
            """
            #version 460 core
            layout(location = 0) in vec2 position;
            void main() { gl_Position = vec4(position, 0.0, 1.0); }
            """;

    private static final String TRIANGLE_FS =
            """
            #version 460 core
            layout(location = 0) out vec4 color;
            void main() { color = vec4(1.0, 0.5, 0.0, 1.0); }
            """;
}
