package dev.jeddy.render.shader;

import static dev.jeddy.gl.ffi.GL46.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.render.GpuTestContext;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Program variants, async compiles and the binary cache (TASKS M5-05). */
@Tag("gpu")
class ProgramCacheTest {
    private static GpuTestContext context;

    @TempDir Path dir;

    @BeforeAll
    static void init() {
        context = new GpuTestContext("program cache test", 32);
    }

    @AfterAll
    static void shutdown() {
        context.close();
    }

    private static final ShaderSources SOURCES =
            path ->
                    Map.of(
                                    "tri.vert",
                                    "#version 460 core\n"
                                            + "#include \"common.glsl\"\n"
                                            + "void main() { gl_Position = vec4(SCALE *"
                                            + " float(gl_VertexID), 0, 0, 1); }\n",
                                    "tri.frag",
                                    "#version 460 core\n"
                                            + "layout(location = 0) out vec4 color;\n"
                                            + "void main() {\n"
                                            + "#ifdef RED\n"
                                            + " color = vec4(1, 0, 0, 1);\n"
                                            + "#else\n"
                                            + " color = vec4(0, 1, 0, 1);\n"
                                            + "#endif\n"
                                            + "}\n",
                                    "common.glsl",
                                    "#pragma once\nconst float SCALE = 0.5;\n",
                                    "broken.frag",
                                    "#version 460 core\nvoid main() { undefined_call(); }\n",
                                    "fill.comp",
                                    "#version 460 core\n"
                                        + "layout(local_size_x = 64) in;\n"
                                        + "layout(std430, binding = 0) buffer B { uint v[]; };\n"
                                        + "void main() { v[gl_GlobalInvocationID.x] = 7u; }\n")
                            .get(path);

    @Test
    void variantsCompileOnceAndFailuresCarryTheLog() {
        try (ProgramCache cache = new ProgramCache(SOURCES, context.caps(), null)) {
            ShaderFeatures features = new ShaderFeatures("RED");
            int tri = cache.define("triangle", "tri.vert", "tri.frag", features);
            int green = cache.getBlocking(tri, 0);
            int red = cache.getBlocking(tri, 1);
            assertNotEquals(0, green);
            assertNotEquals(green, red);
            assertEquals(green, cache.get(tri, 0));
            assertEquals(2, cache.compiles());
            int compute = cache.defineCompute("fill", "fill.comp", ShaderFeatures.NONE);
            assertNotEquals(0, cache.getBlocking(compute, 0));
            Allocations.assertNoAllocation("program lookup", () -> cache.get(tri, 1));

            int broken = cache.define("broken", "tri.vert", "broken.frag", ShaderFeatures.NONE);
            ShaderException e =
                    assertThrows(ShaderException.class, () -> cache.getBlocking(broken, 0));
            assertTrue(e.getMessage().contains("broken"), e.getMessage());
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void binariesSkipCompilationOnTheNextRun() throws Exception {
        ProgramBinaryCache binaries = new ProgramBinaryCache(dir, context.caps());
        try (ProgramCache first = new ProgramCache(SOURCES, context.caps(), binaries)) {
            int tri = first.define("triangle", "tri.vert", "tri.frag", new ShaderFeatures("RED"));
            first.getBlocking(tri, 1);
            assertEquals(1, first.compiles());
        }
        if (!binaries.isSupported()) {
            return; // the driver has no binary formats; nothing more to check
        }
        try (var files = Files.list(dir)) {
            assertEquals(1, files.filter(p -> p.toString().endsWith(".bin")).count());
        }
        try (ProgramCache second = new ProgramCache(SOURCES, context.caps(), binaries)) {
            int tri = second.define("triangle", "tri.vert", "tri.frag", new ShaderFeatures("RED"));
            assertNotEquals(0, second.getBlocking(tri, 1));
            assertEquals(1, second.binaryLoads());
            assertEquals(0, second.compiles());
        }
    }
}
